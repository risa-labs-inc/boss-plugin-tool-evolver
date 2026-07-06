package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Tool Evolver — probe and evolve installed tools (plugins).
 *
 * Registers:
 * - a sidebar panel listing installed tools (with a picker dialog),
 * - a main-panel tab type hosting a per-tool evolver (Probe + Evolve sections),
 * - MCP tools (`evolver_*`) so in-terminal agents can probe, hot-reload, and
 *   evolve plugins — the same channel the evolve skill uses to close the loop.
 */
class ToolEvolverDynamicPlugin : DynamicPlugin {

    override val pluginId = EvolverServices.SELF_PLUGIN_ID
    override val displayName = "Tool Evolver"
    override val version = "0.2.0"
    override val description =
        "Probe installed tools (memory, leak signals, logs) and evolve them with Claude Code, Codex, Gemini, or OpenCode — hot reload + PR included"
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-tool-evolver"

    private var services: EvolverServices? = null

    override fun register(context: PluginContext) {
        val services = EvolverServices(context).also { this.services = it }

        context.panelRegistry.registerPanel(ToolEvolverPanelInfo) { ctx, panelInfo ->
            ToolEvolverPanelComponent(ctx, panelInfo, services)
        }
        context.tabRegistry.registerTabType(EvolverTabType) { tabInfo, ctx ->
            EvolverTabComponent(ctx, tabInfo, services)
        }
        context.registerMcpToolProvider(ToolEvolverMcpToolProvider(pluginId, services))
    }

    override fun dispose() {
        services?.dispose()
        services = null
    }
}
