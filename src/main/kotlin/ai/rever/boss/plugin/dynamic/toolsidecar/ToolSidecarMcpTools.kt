package ai.rever.boss.plugin.dynamic.toolsidecar

import ai.rever.boss.plugin.api.ConsoleLogsAPI
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PluginLogMatcher
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MCP tools (`mcp__boss__sidecar_*`) exposing the sidecar to in-terminal agents.
 * `sidecar_hot_reload` is the tool the evolve skill calls after a rebuild to
 * live-swap the plugin in the running host.
 *
 * CONTRACT: `sidecar_open` and its `plugin_id` argument are mirrored by
 * BossConsole's `SidecarContract` (SidePanel's "Open Sidecar" ⋮ menu item is
 * gated on the tool name and dispatches through it). Renaming either silently
 * removes that menu item — update the host constants in the same change.
 */
class ToolSidecarMcpToolProvider(
    override val providerId: String,
    private val services: SidecarServices,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "sidecar_list_tools",
            description = "List installed BOSS tools (plugins) with id, version, enabled/health state, jar path, open instance count, and the local source repo when one is found.",
            handler = McpToolHandler { _ ->
                withContext(Dispatchers.IO) { listTools() }
            },
        ),
        McpToolDefinition(
            name = "sidecar_probe",
            description = "Probe one installed plugin: metadata, per-plugin heap footprint (live-object histogram filtered to the plugin's package — forces a GC), leak signals, and recent log lines mentioning the plugin.",
            inputSchema = """{"type":"object","properties":{
                "plugin_id":{"type":"string","description":"Plugin id, e.g. ai.rever.boss.plugin.dynamic.bookmarks"},
                "log_lines":{"type":"integer","description":"Max matching log lines to include (default 20)"}
            },"required":["plugin_id"]}""".trimIndent(),
            handler = McpToolHandler { args ->
                val pluginId = args.string("plugin_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: plugin_id", isError = true)
                withContext(Dispatchers.IO) { probe(pluginId, args.int("log_lines") ?: 20) }
            },
        ),
        McpToolDefinition(
            name = "sidecar_open",
            description = "Open the Tool Sidecar tab for a plugin in the main panel (sections: probe, evolve).",
            inputSchema = """{"type":"object","properties":{
                "plugin_id":{"type":"string","description":"Plugin id to open the sidecar for"},
                "section":{"type":"string","enum":["probe","evolve"],"description":"Section to open (default probe)"}
            },"required":["plugin_id"]}""".trimIndent(),
            readOnly = false,
            handler = McpToolHandler { args ->
                val pluginId = args.string("plugin_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: plugin_id", isError = true)
                val target = services.findTool(pluginId)
                    ?: return@McpToolHandler McpToolResult("No loaded plugin with id $pluginId", isError = true)
                val section = if (args.string("section")?.lowercase() == "evolve") SidecarSection.EVOLVE else SidecarSection.PROBE
                if (services.openSidecarTab(target, section)) McpToolResult("Opened sidecar for ${target.displayName} (${section.name.lowercase()})")
                else McpToolResult("Host does not expose split view operations", isError = true)
            },
        ),
        McpToolDefinition(
            name = "sidecar_evolve",
            description = "Start evolving a plugin with an AI CLI: writes the sidecar-evolve skill (with plugin context) into the plugin's source repo and opens a BossTerm tab there running the CLI. Agents: claude, codex, gemini, opencode.",
            inputSchema = """{"type":"object","properties":{
                "plugin_id":{"type":"string","description":"Plugin id to evolve"},
                "agent":{"type":"string","enum":["claude","codex","gemini","opencode"],"description":"AI CLI to launch (default claude)"},
                "task":{"type":"string","description":"Optional evolution request passed to the CLI"},
                "repo_path":{"type":"string","description":"Source repo path; omitted = auto-detected in the workspace"}
            },"required":["plugin_id"]}""".trimIndent(),
            readOnly = false,
            handler = McpToolHandler { args ->
                val pluginId = args.string("plugin_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: plugin_id", isError = true)
                withContext(Dispatchers.IO) { evolve(pluginId, args.string("agent"), args.string("task"), args.string("repo_path")) }
            },
        ),
        McpToolDefinition(
            name = "sidecar_hot_reload",
            description = "Hot-reload a plugin in the RUNNING BOSS instance. With jar_path: copies the jar into the live plugins directory (installed host: ~/.boss/plugins, dev host: ~/.boss_debug/plugins) and live-loads it. Without jar_path: reloads the plugin in place. Call this after `./gradlew buildPluginJar` to apply an evolution.",
            inputSchema = """{"type":"object","properties":{
                "plugin_id":{"type":"string","description":"Plugin id to reload"},
                "jar_path":{"type":"string","description":"Absolute path to a freshly built plugin jar"}
            }}""".trimIndent(),
            readOnly = false,
            handler = McpToolHandler { args ->
                withContext(Dispatchers.IO) {
                    services.hotReloader.hotReload(args.string("plugin_id"), args.string("jar_path")).fold(
                        onSuccess = { McpToolResult(it) },
                        onFailure = { McpToolResult(it.message ?: "Hot reload failed", isError = true) },
                    )
                }
            },
        ),
    )

    private fun listTools(): McpToolResult {
        val tools = services.listTools()
        if (tools.isEmpty()) return McpToolResult("No plugins reported by the host (PluginLoaderDelegate unavailable?)", isError = true)
        val text = buildString {
            appendLine("${tools.size} installed tools:")
            tools.forEach { t ->
                val repo = services.evolveLauncher.resolveSourceRepo(t)?.absolutePath
                val instances = services.loader?.getRunningInstanceCount(t.pluginId) ?: 0
                appendLine(
                    "- ${t.displayName} | id=${t.pluginId} | v${t.version} | " +
                        (if (t.isEnabled) "enabled" else "disabled") +
                        (if (t.healthy) "" else " | UNHEALTHY") +
                        " | instances=$instances | jar=${t.jarPath.ifBlank { "?" }}" +
                        (repo?.let { " | repo=$it" } ?: "")
                )
            }
        }
        return McpToolResult(text.trimEnd())
    }

    private fun probe(pluginId: String, logLines: Int): McpToolResult {
        val target = services.findTool(pluginId)
        val loader = services.loader
        val isLoaded = loader?.isPluginLoaded(pluginId) ?: false
        if (target == null && !isLoaded) {
            return McpToolResult("No loaded plugin with id $pluginId (use sidecar_list_tools)", isError = true)
        }
        val prefix = target?.let { services.memoryProbe.packagePrefixFor(it) } ?: pluginId
        val snapshot = services.memoryProbe.snapshot(prefix)
        val instances = loader?.getRunningInstanceCount(pluginId) ?: 0
        val signals = MemoryProbe.leakSignals(listOfNotNull(snapshot), isLoaded, instances)
        val logs = recentLogsFor(target?.displayName, pluginId, logLines)
        val text = buildString {
            target?.let { t ->
                appendLine("${t.displayName} (${t.pluginId}) v${t.version}")
                appendLine("enabled=${t.isEnabled} healthy=${t.healthy} loaded=$isLoaded openInstances=$instances")
                appendLine("jar: ${t.jarPath.ifBlank { "?" }}")
            } ?: appendLine("$pluginId — not in loaded list (loaded=$isLoaded)")
            appendLine()
            if (snapshot == null) {
                appendLine("Memory: histogram unavailable on this JVM")
            } else {
                appendLine("Memory (live objects under $prefix, GC forced):")
                appendLine("  ${snapshot.instanceCount} instances of ${snapshot.classCount} classes, ${MemoryProbe.humanBytes(snapshot.totalBytes)} total")
                snapshot.topClasses.take(8).forEach { c ->
                    appendLine("  ${MemoryProbe.humanBytes(c.bytes).padStart(10)}  ${c.instances.toString().padStart(8)}x  ${c.className}")
                }
            }
            appendLine()
            appendLine(if (signals.isEmpty()) "Leak signals: none observed" else "Leak signals:")
            signals.forEach { appendLine("  ⚠ $it") }
            appendLine()
            if (logs.isEmpty()) appendLine("Logs: no recent lines mention this plugin")
            else {
                appendLine("Recent matching log lines (${logs.size}):")
                logs.forEach { appendLine("  $it") }
            }
        }
        return McpToolResult(text.trimEnd())
    }

    private fun recentLogsFor(displayName: String?, pluginId: String, limit: Int): List<String> {
        // Prefer the Console plugin's shared attribution flow; same PluginLogMatcher
        // heuristic applies directly when the console is absent.
        val entries = services.context.getPluginAPI(ConsoleLogsAPI::class.java)
            ?.logsForPlugin(pluginId, displayName)?.value
            ?: services.context.logDataProvider?.logs?.value
                ?.let { PluginLogMatcher.filter(it, pluginId, displayName) }
            ?: return emptyList()
        return entries
            .takeLast(limit.coerceIn(1, 200))
            .map { "${it.formatTimestamp()} ${it.message.take(400)}" }
    }

    private fun evolve(pluginId: String, agentId: String?, task: String?, repoPath: String?): McpToolResult {
        val target = services.findTool(pluginId)
            ?: return McpToolResult("No loaded plugin with id $pluginId", isError = true)
        val agent = CliAgent.fromId(agentId) ?: CliAgent.CLAUDE_CODE
        val repo = repoPath?.let { File(it) } ?: services.evolveLauncher.resolveSourceRepo(target)
            ?: return McpToolResult(
                "No source repo found for $pluginId — pass repo_path (searched ~/Development/Boss/boss_plugins, ~/BossTools, ~/Development)",
                isError = true,
            )
        return services.evolveLauncher.launchEvolve(target, agent, repo, task).fold(
            onSuccess = { McpToolResult(it) },
            onFailure = { McpToolResult(it.message ?: "Evolve launch failed", isError = true) },
        )
    }
}
