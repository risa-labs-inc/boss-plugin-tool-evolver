package ai.rever.boss.plugin.dynamic.toolevolver

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
 * MCP tools (`mcp__boss__evolver_*`) exposing the evolver to in-terminal agents.
 * `evolver_hot_reload` is the tool the evolve skill calls after a rebuild to
 * live-swap the plugin in the running host.
 *
 * CONTRACT: `evolver_open` and its `plugin_id` argument are mirrored by
 * BossConsole's `EvolverContract` (SidePanel's "Open Evolver" ⋮ menu item is
 * gated on the tool name and dispatches through it). Renaming either silently
 * removes that menu item — update the host constants in the same change.
 */
class ToolEvolverMcpToolProvider(
    override val providerId: String,
    private val services: EvolverServices,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "evolver_list_tools",
            description = "List installed BOSS tools (plugins) with id, version, enabled/health state, jar path, open instance count, and the local source repo when one is found.",
            handler = McpToolHandler { _ ->
                withContext(Dispatchers.IO) { listTools() }
            },
        ),
        McpToolDefinition(
            name = "evolver_probe",
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
            name = "evolver_open",
            description = "Open the Tool Evolver tab for a plugin in the main panel (sections: evolve, probe, issue).",
            inputSchema = """{"type":"object","properties":{
                "plugin_id":{"type":"string","description":"Plugin id to open the evolver for"},
                "section":{"type":"string","enum":["evolve","probe","issue"],"description":"Section to open (default evolve)"}
            },"required":["plugin_id"]}""".trimIndent(),
            readOnly = false,
            handler = McpToolHandler { args ->
                val pluginId = args.string("plugin_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: plugin_id", isError = true)
                val target = services.findTool(pluginId)
                    ?: return@McpToolHandler McpToolResult("No loaded plugin with id $pluginId", isError = true)
                val section = when (args.string("section")?.lowercase()) {
                    "probe" -> EvolverSection.PROBE
                    "issue" -> EvolverSection.ISSUE
                    else -> EvolverSection.EVOLVE
                }
                if (services.openEvolverTab(target, section)) McpToolResult("Opened evolver for ${target.displayName} (${section.name.lowercase()})")
                else McpToolResult("Host does not expose split view operations", isError = true)
            },
        ),
        McpToolDefinition.withRbac(
            name = "evolver_evolve",
            description = "Start evolving a plugin with an AI CLI: writes the evolve skill (with plugin context) into the plugin's source repo and opens a BossTerm tab there running the CLI. If no local checkout is found it clones the repo into the plugins umbrella first. Agents: claude, codex, gemini, opencode.",
            inputSchema = """{"type":"object","properties":{
                "plugin_id":{"type":"string","description":"Plugin id to evolve"},
                "agent":{"type":"string","enum":["claude","codex","gemini","opencode"],"description":"AI CLI to launch (default claude)"},
                "task":{"type":"string","description":"Optional evolution request passed to the CLI"},
                "repo_path":{"type":"string","description":"Source repo path; omitted = auto-detected in the workspace"}
            },"required":["plugin_id"]}""".trimIndent(),
            readOnly = false,
            // Gated: only exposed to users holding the plugin-development permission.
            requiredPermissions = listOf(EvolverServices.EVOLVE_PERMISSION),
            handler = McpToolHandler { args ->
                val pluginId = args.string("plugin_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: plugin_id", isError = true)
                withContext(Dispatchers.IO) { evolve(pluginId, args.string("agent"), args.string("task"), args.string("repo_path")) }
            },
        ),
        McpToolDefinition(
            name = "evolver_hot_reload",
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
        McpToolDefinition(
            name = "evolver_create_issue",
            description = "File a GitHub issue on a plugin's repo via the gh CLI. The repo is auto-resolved from the plugin (its manifest url or the risa-labs-inc/boss-plugin-<slug> convention) unless `repo` (owner/repo) is given. Requires gh installed + authenticated.",
            inputSchema = """{"type":"object","properties":{
                "plugin_id":{"type":"string","description":"Plugin id the issue is about"},
                "title":{"type":"string","description":"Issue title"},
                "body":{"type":"string","description":"Issue body (markdown)"},
                "repo":{"type":"string","description":"Override target repo as owner/repo"}
            },"required":["plugin_id","title"]}""".trimIndent(),
            readOnly = false,
            handler = McpToolHandler { args ->
                val pluginId = args.string("plugin_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: plugin_id", isError = true)
                val title = args.string("title")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: title", isError = true)
                withContext(Dispatchers.IO) { createIssue(pluginId, title, args.string("body") ?: "", args.string("repo")) }
            },
        ),
    )

    private fun createIssue(pluginId: String, title: String, body: String, repoOverride: String?): McpToolResult {
        val target = services.findTool(pluginId)
        val slug = repoOverride?.trim()?.takeUnless { it.isBlank() }
            ?: target?.let { services.issueReporter.repoSlug(it) }
            ?: return McpToolResult("Cannot resolve a repo for $pluginId — pass repo=owner/repo", isError = true)
        return services.issueReporter.createIssue(slug, title, body).fold(
            onSuccess = { McpToolResult("Filed issue on $slug: $it") },
            onFailure = { McpToolResult(it.message ?: "Issue creation failed", isError = true) },
        )
    }

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
            return McpToolResult("No loaded plugin with id $pluginId (use evolver_list_tools)", isError = true)
        }
        // Prefer the Performance plugin's shared probe (api ≥ 1.0.64) — one sample
        // history across the Performance panel, its MCP tools, and this probe.
        val viaPerformance = services.performanceProbe.sample(pluginId)
        val prefix = target?.let { services.memoryProbe.packagePrefixFor(it) } ?: pluginId
        val snapshot = viaPerformance ?: services.memoryProbe.snapshot(prefix)
        val history = viaPerformance?.let { services.performanceProbe.history(pluginId) }
            ?: listOfNotNull(snapshot)
        val instances = loader?.getRunningInstanceCount(pluginId) ?: 0
        val signals = MemoryProbe.leakSignals(history, isLoaded, instances)
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
                val source = if (viaPerformance != null) "via Performance plugin" else "local histogram"
                appendLine("Memory (live objects under ${snapshot.packagePrefix}, GC forced, $source):")
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
        if (!agent.isInstalled()) {
            return McpToolResult(
                "${agent.displayName} CLI ('${agent.binary}') is not installed on this machine — install it or pick another agent (claude/codex/gemini/opencode).",
                isError = true,
            )
        }
        val repo = repoPath?.let { File(it) }
            ?: services.evolveLauncher.resolveSourceRepo(target)
            ?: run {
                // No local checkout — clone it (like tool-creator's acquisition step)
                // into the plugins umbrella so evolution has a working tree.
                val url = services.evolveLauncher.guessGitUrl(target)
                val parent = services.evolveLauncher.defaultCloneParent()
                services.evolveLauncher.cloneRepo(target, url, parent) { }.getOrElse {
                    return McpToolResult(
                        "No source repo for $pluginId and clone failed (${it.message}). " +
                            "Pass repo_path, or check the repo URL ($url).",
                        isError = true,
                    )
                }
            }
        return services.evolveLauncher.launchEvolve(target, agent, repo, task).fold(
            onSuccess = { McpToolResult(it) },
            onFailure = { McpToolResult(it.message ?: "Evolve launch failed", isError = true) },
        )
    }
}
