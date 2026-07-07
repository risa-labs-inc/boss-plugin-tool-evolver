package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.ConsoleLogsAPI
import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.TabSplitMode
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
import kotlinx.coroutines.withContext

/**
 * State for one evolver tab, bound to a single target plugin. Probe state
 * (memory samples, leak signals, filtered logs) and Evolve state (repo, agent,
 * action log) live here.
 */
/** What the shared open-location chooser dialog will open when a location is picked. */
sealed interface PendingOpen {
    val dialogTitle: String

    data class Evolve(val agent: CliAgent) : PendingOpen {
        override val dialogTitle get() = "Open ${agent.displayName}"
    }

    data class OpenUrl(val url: String, val label: String) : PendingOpen {
        override val dialogTitle get() = "Open $label"
    }
}

class EvolverTabViewModel(
    private val services: EvolverServices,
    val tabInfo: EvolverTabInfo,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val targetPluginId = tabInfo.targetPluginId

    private val _target = MutableStateFlow<LoadedPluginInfo?>(null)
    val target: StateFlow<LoadedPluginInfo?> = _target.asStateFlow()

    private val _isLoaded = MutableStateFlow(true)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _section = MutableStateFlow(tabInfo.initialSection)
    val section: StateFlow<EvolverSection> = _section.asStateFlow()

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

    /** Non-null while the open-location chooser dialog is showing. */
    private val _pendingOpen = MutableStateFlow<PendingOpen?>(null)
    val pendingOpen: StateFlow<PendingOpen?> = _pendingOpen.asStateFlow()

    private val _actionLog = MutableStateFlow<List<String>>(emptyList())
    val actionLog: StateFlow<List<String>> = _actionLog.asStateFlow()

    val agentAvailability: Map<CliAgent, Boolean> =
        CliAgent.entries.associateWith { it.isInstalled() }

    // ------------------------------------------------------------------ issue

    private val _issueTitle = MutableStateFlow("")
    val issueTitle: StateFlow<String> = _issueTitle.asStateFlow()

    private val _issueBody = MutableStateFlow("")
    val issueBody: StateFlow<String> = _issueBody.asStateFlow()

    private val _attachDiagnostics = MutableStateFlow(false)
    val attachDiagnostics: StateFlow<Boolean> = _attachDiagnostics.asStateFlow()

    private val _issueBusy = MutableStateFlow(false)
    val issueBusy: StateFlow<Boolean> = _issueBusy.asStateFlow()

    /** Resolved target repo slug (owner/repo) for the issue, or null if unknown. */
    private val _issueRepo = MutableStateFlow<String?>(null)
    val issueRepo: StateFlow<String?> = _issueRepo.asStateFlow()

    private val _issueLog = MutableStateFlow<List<String>>(emptyList())
    val issueLog: StateFlow<List<String>> = _issueLog.asStateFlow()

    // Optimistic until the async check completes (avoids blocking UI on subprocesses).
    private val _ghStatus = MutableStateFlow(GhStatus.READY)
    val ghStatus: StateFlow<GhStatus> = _ghStatus.asStateFlow()

    init {
        // Re-focusing this tab (e.g. via "Open Evolver" / "Report Issue" again)
        // switches it to the requested section instead of opening a duplicate.
        scope.launch {
            services.sectionRequests.collect { (pluginId, requested) ->
                if (pluginId == targetPluginId) _section.value = requested
            }
        }
        refreshTarget()
        scope.launch(Dispatchers.IO) {
            _ghStatus.value = services.issueReporter.ghStatus()
            _target.value?.let { target ->
                _repoPath.value = services.evolveLauncher.resolveSourceRepo(target)?.absolutePath
                if (_cloneUrl.value.isBlank()) _cloneUrl.value = services.evolveLauncher.guessGitUrl(target)
                if (_issueRepo.value == null) _issueRepo.value = services.issueReporter.repoSlug(target)
            }
        }
    }

    fun setSection(section: EvolverSection) {
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
        if (_target.value == null) {
            appendAction("Plugin is not loaded — cannot evolve")
            return
        }
        if (_repoPath.value == null) {
            appendAction("No source repo — set one (searched the workspace roots without a match)")
            return
        }
        scope.launch {
            // Honor a remembered choice; otherwise show the open-location chooser
            // (the card dialog, matching the terminal-link chooser).
            val remembered = services.getRememberedOpenLocation()
            if (remembered != null) doLaunch(agent, remembered)
            else _pendingOpen.value = PendingOpen.Evolve(agent)
        }
    }

    /** The user picked a location in the chooser dialog (optionally remembered). */
    fun onOpenLocationChosen(location: EvolveOpenLocation, remember: Boolean) {
        val pending = _pendingOpen.value ?: return
        _pendingOpen.value = null
        scope.launch {
            if (remember) services.setRememberedOpenLocation(location)
            when (pending) {
                is PendingOpen.Evolve -> doLaunch(pending.agent, location)
                is PendingOpen.OpenUrl -> openUrlAt(pending.url, pending.label, location)
            }
        }
    }

    fun dismissOpenDialog() {
        _pendingOpen.value = null
    }

    private fun openUrlAt(url: String, label: String, location: EvolveOpenLocation) {
        val ops = services.context.splitViewOperations ?: return
        when (location) {
            EvolveOpenLocation.NEW_TAB -> ops.openUrlInActivePanel(url, label, forceNewTab = true)
            EvolveOpenLocation.EXISTING_SPLIT -> ops.openUrlInSplit(url, label, TabSplitMode.EXISTING_SPLIT)
            EvolveOpenLocation.SPLIT_RIGHT -> ops.openUrlInSplit(url, label, TabSplitMode.VERTICAL_SPLIT)
            EvolveOpenLocation.SPLIT_DOWN -> ops.openUrlInSplit(url, label, TabSplitMode.HORIZONTAL_SPLIT)
        }
    }

    private suspend fun doLaunch(agent: CliAgent, location: EvolveOpenLocation) {
        val target = _target.value ?: return
        val repo = _repoPath.value?.let(::File) ?: return
        withContext(Dispatchers.IO) {
            services.evolveLauncher.launchEvolve(target, agent, repo, _task.value.ifBlank { null }, location).fold(
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

    // ------------------------------------------------------------------ issue

    fun setIssueTitle(value: String) { _issueTitle.value = value }
    fun setIssueBody(value: String) { _issueBody.value = value }
    fun setAttachDiagnostics(value: Boolean) { _attachDiagnostics.value = value }
    fun setIssueRepo(value: String) { _issueRepo.value = value.trim().ifBlank { null } }

    /** File a GitHub issue on the plugin's repo (optionally attaching diagnostics). */
    fun createIssue() {
        if (_issueBusy.value) return
        val title = _issueTitle.value.trim()
        if (title.isBlank()) {
            appendIssueLog("Title is required")
            return
        }
        val slug = _issueRepo.value?.trim().takeUnless { it.isNullOrBlank() } ?: run {
            appendIssueLog("No target repo — set owner/repo above")
            return
        }
        _issueBusy.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val body = buildIssueBody()
                appendIssueLog("Creating issue on $slug…")
                services.issueReporter.createIssue(slug, title, body).fold(
                    onSuccess = { url ->
                        appendIssueLog("Created: $url")
                        services.toastSuccess("Issue filed on $slug")
                        _issueTitle.value = ""
                        _issueBody.value = ""
                        // Open the new issue through the same "Open Link" chooser
                        // (honoring a remembered choice), so it can land in a split.
                        if (url.startsWith("https://")) {
                            val remembered = services.getRememberedOpenLocation()
                            if (remembered != null) openUrlAt(url, "Issue", remembered)
                            else _pendingOpen.value = PendingOpen.OpenUrl(url, "Issue")
                        }
                    },
                    onFailure = {
                        appendIssueLog("Failed: ${it.message}")
                        services.toastError(it.message ?: "Issue creation failed")
                    },
                )
            } finally {
                _issueBusy.value = false
            }
        }
    }

    private fun buildIssueBody(): String {
        val base = _issueBody.value.trim()
        val target = _target.value
        val footer = buildString {
            appendLine()
            appendLine()
            appendLine("---")
            appendLine("Filed via Tool Evolver.")
            target?.let { appendLine("Plugin: ${it.pluginId} v${it.version}") }
        }
        if (!_attachDiagnostics.value) return base + footer
        val diag = buildString {
            appendLine()
            appendLine("### Diagnostics")
            val snap = _snapshots.value.lastOrNull()
            if (snap != null) {
                appendLine("- Memory: ${MemoryProbe.humanBytes(snap.totalBytes)} across ${snap.instanceCount} instances / ${snap.classCount} classes")
            }
            _instances.value.let { appendLine("- Open instances: $it") }
            val signals = _leakSignals.value
            if (signals.isNotEmpty()) {
                appendLine("- Leak signals:")
                signals.forEach { appendLine("  - $it") }
            }
            val recent = logs.value.takeLast(20)
            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("Recent log lines:")
                appendLine("```")
                recent.forEach { appendLine("${it.formatTimestamp()} ${it.message.take(300)}") }
                appendLine("```")
            }
        }
        return base + "\n" + diag + footer
    }

    private fun appendIssueLog(line: String) {
        _issueLog.update { (it + line).takeLast(100) }
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
     * the console owns the full log-viewing experience; the evolver's list is a
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
