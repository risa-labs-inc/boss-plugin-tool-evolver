package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Shared brain for the Tool Evolver plugin: one instance per plugin activation,
 * handed to the panel, every evolver tab, and the MCP tools. All host providers
 * are pulled lazily and may be null — callers degrade gracefully.
 */
class EvolverServices(val context: PluginContext) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val memoryProbe = MemoryProbe()
    val evolveLauncher = EvolveLauncher(this)
    val hotReloader = HotReloader(this)
    val issueReporter = IssueReporter(this)

    /** Host plugin-management API; null on hosts too old to register it. */
    val loader: PluginLoaderDelegate?
        get() = context.getPluginAPI(PluginLoaderDelegate::class.java)

    /** Installed tools (loaded plugins), sorted for display. */
    fun listTools(): List<LoadedPluginInfo> =
        (loader?.getLoadedPlugins() ?: emptyList())
            .sortedBy { it.displayName.lowercase() }

    fun findTool(pluginId: String): LoadedPluginInfo? =
        listTools().firstOrNull { it.pluginId == pluginId }

    /**
     * Section-switch requests for already-open evolver tabs, keyed by pluginId.
     * When [openEvolverTab] focuses an existing tab, it emits here so that tab's
     * ViewModel jumps to the requested section (e.g. "Report Issue" → Issue).
     */
    private val _sectionRequests = MutableSharedFlow<Pair<String, EvolverSection>>(extraBufferCapacity = 16)
    val sectionRequests: SharedFlow<Pair<String, EvolverSection>> = _sectionRequests.asSharedFlow()

    /**
     * Open (or focus) the evolver tab for [target] in the main panel. The tab id
     * is stable per plugin (`tool-evolver-<pluginId>`); if a tab with that id is
     * already open we FOCUS it (and switch its section) instead of opening a
     * duplicate — the host's openTab doesn't dedupe by id, and duplicate ids
     * share one component, so stacking them makes closing one blank the others.
     */
    fun openEvolverTab(target: LoadedPluginInfo, section: EvolverSection = EvolverSection.EVOLVE): Boolean {
        val tabInfo = EvolverTabInfo(
            targetPluginId = target.pluginId,
            targetDisplayName = target.displayName,
            initialSection = section,
        )
        val existing = context.activeTabsProvider?.activeTabs?.value?.firstOrNull { it.tabId == tabInfo.id }
        if (existing != null) {
            context.activeTabsProvider?.selectTab(existing.tabId, existing.panelId)
            _sectionRequests.tryEmit(target.pluginId to section)
            return true
        }
        val ops = context.splitViewOperations ?: run {
            toastError("Cannot open tab — host does not expose split view operations")
            return false
        }
        ops.openTab(tabInfo)
        return true
    }

    fun toastSuccess(message: String) {
        context.notificationProvider?.showToast(message, NotificationType.SUCCESS, title = "Tool Evolver")
    }

    fun toastError(message: String) {
        context.notificationProvider?.showToast(message, NotificationType.ERROR, title = "Tool Evolver")
    }

    // Persistent prefs (null on hosts without storage — remember is then session-only).
    private val storage: PluginStorageProvider? by lazy {
        context.pluginStorageFactory?.createStorage(SELF_PLUGIN_ID)
    }

    /** The user's remembered evolve open-location, or null to ask each time. */
    suspend fun getRememberedOpenLocation(): EvolveOpenLocation? =
        storage?.getString(KEY_OPEN_LOCATION)
            ?.let { runCatching { EvolveOpenLocation.valueOf(it) }.getOrNull() }

    suspend fun setRememberedOpenLocation(location: EvolveOpenLocation) {
        storage?.putString(KEY_OPEN_LOCATION, location.name)
    }

    fun dispose() {
        scope.cancel()
    }

    /**
     * Whether the current user may evolve (launch AI CLIs on plugin source).
     * Admins bypass; otherwise the [EVOLVE_PERMISSION] must be held. If the host
     * exposes no auth provider (e.g. a dev host without RBAC), allow — the MCP
     * side is still gated via withRbac.
     */
    fun evolveAllowed(): Boolean {
        val auth = context.authDataProvider ?: return true
        return auth.isAdmin.value || auth.hasPermission(EVOLVE_PERMISSION)
    }

    companion object {
        const val SELF_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.toolevolver"
        private const val KEY_OPEN_LOCATION = "evolve_open_location"

        /**
         * Permission required to evolve a plugin (launch an AI CLI on its source,
         * hot-reload, open PRs). The plugin-development capability held by the
         * `boss_admin` role — the same gate the Tool Creator uses.
         */
        const val EVOLVE_PERMISSION = "plugins.admin.publish"
    }
}
