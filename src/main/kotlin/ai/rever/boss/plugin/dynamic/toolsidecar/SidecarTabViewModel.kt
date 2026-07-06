package ai.rever.boss.plugin.dynamic.toolsidecar

import ai.rever.boss.plugin.api.ConsoleLogsAPI
import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.LogEntryData
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PluginLogMatcher
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for one sidecar tab, bound to a single target plugin. Probe state
 * (memory samples, leak signals, filtered logs) and Evolve state (repo, agent,
 * action log) live here.
 */
class SidecarTabViewModel(
    private val services: SidecarServices,
    val tabInfo: SidecarTabInfo,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val targetPluginId = tabInfo.targetPluginId

    private val _target = MutableStateFlow<LoadedPluginInfo?>(null)
    val target: StateFlow<LoadedPluginInfo?> = _target.asStateFlow()

    private val _isLoaded = MutableStateFlow(true)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _section = MutableStateFlow(tabInfo.initialSection)
    val section: StateFlow<SidecarSection> = _section.asStateFlow()

    // ------------------------------------------------------------------ probe

    private val _snapshots = MutableStateFlow<List<MemoryProbe.MemorySnapshot>>(emptyList())
    val snapshots: StateFlow<List<MemoryProbe.MemorySnapshot>> = _snapshots.asStateFlow()

    private val _sampling = MutableStateFlow(false)
    val sampling: StateFlow<Boolean> = _sampling.asStateFlow()

    private val _leakSignals = MutableStateFlow<List<String>>(emptyList())
    val leakSignals: StateFlow<List<String>> = _leakSignals.asStateFlow()

    private val _instances = MutableStateFlow(0)
    val instances: StateFlow<Int> = _instances.asStateFlow()

    private val _logQuery = MutableStateFlow("")
    val logQuery: StateFlow<String> = _logQuery.asStateFlow()

    private val consoleLogs: ConsoleLogsAPI? =
        services.context.getPluginAPI(ConsoleLogsAPI::class.java)

    /**
     * Lines attributed to the target plugin. Preferably the Console plugin's
     * shared flow (one attribution implementation app-wide); when the Console
     * plugin is absent, the same [PluginLogMatcher] heuristic is applied to the
     * host's raw log stream directly.
     */
    private val attributedLogs: StateFlow<List<LogEntryData>> =
        consoleLogs?.logsForPlugin(targetPluginId, tabInfo.targetDisplayName)
            ?: (services.context.logDataProvider?.logs ?: MutableStateFlow(emptyList()))
                .map { PluginLogMatcher.filter(it, targetPluginId, tabInfo.targetDisplayName) }
                .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val logs: StateFlow<List<LogEntryData>> = combine(
        attributedLogs,
        _logQuery,
    ) { entries, query ->
        val matched =
            if (query.isBlank()) entries
            else entries.filter { it.message.contains(query, ignoreCase = true) }
        matched.takeLast(400)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** True when the Console plugin is present, enabling "Open in Console". */
    val canOpenInConsole: Boolean = consoleLogs != null

    // ----------------------------------------------------------------- evolve

    private val _repoPath = MutableStateFlow<String?>(null)
    val repoPath: StateFlow<String?> = _repoPath.asStateFlow()

    private val _task = MutableStateFlow("")
    val task: StateFlow<String> = _task.asStateFlow()

    /** Editable git URL + parent dir for cloning a repo when none is found locally. */
    private val _cloneUrl = MutableStateFlow("")
    val cloneUrl: StateFlow<String> = _cloneUrl.asStateFlow()

    private val _cloneParent = MutableStateFlow(services.evolveLauncher.defaultCloneParent().absolutePath)
    val cloneParent: StateFlow<String> = _cloneParent.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _actionLog = MutableStateFlow<List<String>>(emptyList())
    val actionLog: StateFlow<List<String>> = _actionLog.asStateFlow()

    val agentAvailability: Map<CliAgent, Boolean> =
        CliAgent.entries.associateWith { it.isInstalled() }

    init {
        refreshTarget()
        scope.launch(Dispatchers.IO) {
            _target.value?.let { target ->
                _repoPath.value = services.evolveLauncher.resolveSourceRepo(target)?.absolutePath
                if (_cloneUrl.value.isBlank()) _cloneUrl.value = services.evolveLauncher.guessGitUrl(target)
            }
        }
    }

    fun setSection(section: SidecarSection) {
        _section.value = section
    }

    fun refreshTarget() {
        scope.launch(Dispatchers.IO) {
            _target.value = services.findTool(targetPluginId)
            _isLoaded.value = services.loader?.isPluginLoaded(targetPluginId) ?: false
            _instances.value = services.loader?.getRunningInstanceCount(targetPluginId) ?: 0
            if (_repoPath.value == null) {
                _target.value?.let { t ->
                    _repoPath.value = services.evolveLauncher.resolveSourceRepo(t)?.absolutePath
                }
            }
            recomputeLeakSignals()
        }
    }

    fun sampleMemory() {
        if (_sampling.value) return
        _sampling.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val prefix = _target.value?.let { services.memoryProbe.packagePrefixFor(it) } ?: targetPluginId
                val snapshot = services.memoryProbe.snapshot(prefix)
                if (snapshot != null) {
                    _snapshots.update { (it + snapshot).takeLast(50) }
                } else {
                    appendAction("Memory histogram unavailable on this JVM")
                }
                _instances.value = services.loader?.getRunningInstanceCount(targetPluginId) ?: 0
                _isLoaded.value = services.loader?.isPluginLoaded(targetPluginId) ?: false
                recomputeLeakSignals()
            } finally {
                _sampling.value = false
            }
        }
    }

    fun requestGC() {
        services.context.performanceDataProvider?.requestGC()
            ?: appendAction("GC request unavailable (no performance provider)")
    }

    fun reloadPlugin() {
        scope.launch {
            services.hotReloader.hotReload(targetPluginId, null).fold(
                onSuccess = {
                    appendAction(it)
                    services.toastSuccess(it)
                },
                onFailure = {
                    appendAction("Reload failed: ${it.message}")
                    services.toastError(it.message ?: "Reload failed")
                },
            )
            refreshTarget()
        }
    }

    fun setLogQuery(value: String) {
        _logQuery.value = value
    }

    fun setRepoPath(path: String) {
        _repoPath.value = path
        services.evolveLauncher.setRepoOverride(targetPluginId, path)
    }

    fun browseRepo() {
        val picker = services.context.directoryPickerProvider ?: run {
            appendAction("Directory picker unavailable — type the repo path instead")
            return
        }
        picker.pickDirectory { path -> if (path != null) setRepoPath(path) }
    }

    fun setTask(value: String) {
        _task.value = value
    }

    fun setCloneUrl(value: String) {
        _cloneUrl.value = value
    }

    fun setCloneParent(value: String) {
        _cloneParent.value = value
    }

    fun browseCloneParent() {
        val picker = services.context.directoryPickerProvider ?: run {
            appendAction("Directory picker unavailable — type the location instead")
            return
        }
        picker.pickDirectory { path -> if (path != null) setCloneParent(path) }
    }

    /** Clone the plugin's repo into the chosen parent dir, then use it as the source. */
    fun cloneRepo() {
        if (_busy.value) return
        val target = _target.value ?: run {
            appendAction("Plugin is not loaded — cannot clone")
            return
        }
        val url = _cloneUrl.value.ifBlank { services.evolveLauncher.guessGitUrl(target) }
        val parent = File(_cloneParent.value)
        _busy.value = true
        scope.launch(Dispatchers.IO) {
            try {
                services.evolveLauncher.cloneRepo(target, url, parent, ::appendAction).fold(
                    onSuccess = { dir ->
                        _repoPath.value = dir.absolutePath
                        appendAction("Cloned into ${dir.absolutePath}")
                        services.toastSuccess("Cloned ${target.displayName} for evolution")
                    },
                    onFailure = {
                        appendAction("Clone failed: ${it.message}")
                        services.toastError(it.message ?: "Clone failed")
                    },
                )
            } finally {
                _busy.value = false
            }
        }
    }

    fun launchEvolve(agent: CliAgent) {
        val target = _target.value ?: run {
            appendAction("Plugin is not loaded — cannot evolve")
            return
        }
        val repo = _repoPath.value?.let(::File) ?: run {
            appendAction("No source repo — set one (searched the workspace roots without a match)")
            return
        }
        scope.launch(Dispatchers.IO) {
            services.evolveLauncher.launchEvolve(target, agent, repo, _task.value.ifBlank { null }).fold(
                onSuccess = {
                    appendAction(it)
                    services.toastSuccess("${agent.displayName} is evolving ${target.displayName}")
                },
                onFailure = {
                    appendAction("Evolve failed: ${it.message}")
                    services.toastError(it.message ?: "Evolve failed")
                },
            )
        }
    }

    fun rebuildAndReload() {
        if (_busy.value) return
        val repo = _repoPath.value?.let(::File) ?: run {
            appendAction("No source repo set")
            return
        }
        _busy.value = true
        scope.launch(Dispatchers.IO) {
            try {
                services.hotReloader.rebuildAndReload(repo, targetPluginId, ::appendAction).fold(
                    onSuccess = {
                        appendAction(it)
                        services.toastSuccess(it)
                    },
                    onFailure = {
                        appendAction("Rebuild failed: ${it.message}")
                        services.toastError(it.message ?: "Rebuild failed")
                    },
                )
            } finally {
                _busy.value = false
                refreshTarget()
            }
        }
    }

    private fun recomputeLeakSignals() {
        _leakSignals.value = MemoryProbe.leakSignals(_snapshots.value, _isLoaded.value, _instances.value)
    }

    private fun appendAction(line: String) {
        _actionLog.update { (it + line).takeLast(400) }
    }

    /**
     * Select this plugin in the Console panel's filter and reveal the panel —
     * the console owns the full log-viewing experience; the sidecar's list is a
     * probe-sized excerpt.
     */
    fun openInConsole() {
        val api = consoleLogs ?: run {
            appendAction("Console plugin not installed — cannot open")
            return
        }
        api.setPluginFilter(targetPluginId)
        val windowId = services.context.windowId
        val panelEvents = services.context.panelEventProvider
        if (windowId == null || panelEvents == null) {
            appendAction("Console filter set to $targetPluginId (open the Console panel to view)")
            return
        }
        scope.launch {
            // Matched host-side by panelId string + pluginId; order is not compared.
            panelEvents.openPanel(PanelId("console", 0), windowId)
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
