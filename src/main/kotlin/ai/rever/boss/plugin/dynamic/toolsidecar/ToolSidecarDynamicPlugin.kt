package ai.rever.boss.plugin.dynamic.toolsidecar

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Tool Sidecar — probe and evolve installed tools (plugins).
 *
 * Registers:
 * - a sidebar panel listing installed tools (with a picker dialog),
 * - a main-panel tab type hosting a per-tool sidecar (Probe + Evolve sections),
 * - MCP tools (`sidecar_*`) so in-terminal agents can probe, hot-reload, and
 *   evolve plugins — the same channel the evolve skill uses to close the loop.
 */
class ToolSidecarDynamicPlugin : DynamicPlugin {

    override val pluginId = SidecarServices.SELF_PLUGIN_ID
    override val displayName = "Tool Sidecar"
    override val version = "0.2.0"
    override val description =
        "Probe installed tools (memory, leak signals, logs) and evolve them with Claude Code, Codex, Gemini, or OpenCode — hot reload + PR included"
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-tool-sidecar"

    private var services: SidecarServices? = null

    override fun register(context: PluginContext) {
        val services = SidecarServices(context).also { this.services = it }

        context.panelRegistry.registerPanel(ToolSidecarPanelInfo) { ctx, panelInfo ->
            ToolSidecarPanelComponent(ctx, panelInfo, services)
        }
        context.tabRegistry.registerTabType(SidecarTabType) { tabInfo, ctx ->
            SidecarTabComponent(ctx, tabInfo, services)
        }
        context.registerMcpToolProvider(ToolSidecarMcpToolProvider(pluginId, services))
    }

    override fun dispose() {
        services?.dispose()
        services = null
    }
}
