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
     * Open (or focus) the evolver tab for [target] in the main panel. The tab id
     * is stable per plugin so repeated opens don't multiply tabs.
     */
    fun openEvolverTab(target: LoadedPluginInfo, section: EvolverSection = EvolverSection.EVOLVE): Boolean {
        val ops = context.splitViewOperations ?: run {
            toastError("Cannot open tab — host does not expose split view operations")
            return false
        }
        ops.openTab(
            EvolverTabInfo(
                targetPluginId = target.pluginId,
                targetDisplayName = target.displayName,
                initialSection = section,
            )
        )
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

    companion object {
        const val SELF_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.toolevolver"
        private const val KEY_OPEN_LOCATION = "evolve_open_location"
    }
}
