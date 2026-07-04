package ai.rever.boss.plugin.dynamic.toolsidecar

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Shared brain for the Tool Sidecar plugin: one instance per plugin activation,
 * handed to the panel, every sidecar tab, and the MCP tools. All host providers
 * are pulled lazily and may be null — callers degrade gracefully.
 */
class SidecarServices(val context: PluginContext) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val memoryProbe = MemoryProbe()
    val evolveLauncher = EvolveLauncher(this)
    val hotReloader = HotReloader(this)

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
     * Open (or focus) the sidecar tab for [target] in the main panel. The tab id
     * is stable per plugin so repeated opens don't multiply tabs.
     */
    fun openSidecarTab(target: LoadedPluginInfo, section: SidecarSection = SidecarSection.PROBE): Boolean {
        val ops = context.splitViewOperations ?: run {
            toastError("Cannot open tab — host does not expose split view operations")
            return false
        }
        ops.openTab(
            SidecarTabInfo(
                targetPluginId = target.pluginId,
                targetDisplayName = target.displayName,
                initialSection = section,
            )
        )
        return true
    }

    fun toastSuccess(message: String) {
        context.notificationProvider?.showToast(message, NotificationType.SUCCESS, title = "Tool Sidecar")
    }

    fun toastError(message: String) {
        context.notificationProvider?.showToast(message, NotificationType.ERROR, title = "Tool Sidecar")
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        const val SELF_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.toolsidecar"
    }
}
