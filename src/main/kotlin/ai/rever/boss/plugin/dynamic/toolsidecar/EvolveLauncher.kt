package ai.rever.boss.plugin.dynamic.toolsidecar

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Starts an "evolution" of an installed plugin: writes the sidecar-evolve skill
 * (with full plugin context) into the plugin's source repo in every supported
 * CLI's native format, then opens a BossTerm tab in that repo running the chosen
 * AI CLI with the skill engaged. The skill closes the loop: build → hot-reload
 * via the `sidecar_hot_reload` MCP tool → verify → open a PR.
 */
class EvolveLauncher(private val services: SidecarServices) {

    /** Session-scoped manual repo choices, keyed by pluginId. */
    private val repoOverrides = ConcurrentHashMap<String, String>()

    fun setRepoOverride(pluginId: String, path: String) {
        repoOverrides[pluginId] = path
    }

    /**
     * Locate the plugin's source repo: manual override first, then scan the
     * house workspace roots for a checkout whose plugin.json carries the id.
     */
    fun resolveSourceRepo(info: LoadedPluginInfo): File? {
        repoOverrides[info.pluginId]?.let { override ->
            File(override).takeIf { it.isDirectory }?.let { return it }
        }
        val home = System.getProperty("user.home")
        val roots = listOf(
            File(home, "Development/Boss/boss_plugins"),
            File(home, "BossTools"),
            File(home, "Development"),
        )
        for (root in roots) {
            if (!root.isDirectory) continue
            root.listFiles { f -> f.isDirectory }?.forEach { dir ->
                val manifest = File(dir, "src/main/resources/META-INF/boss-plugin/plugin.json")
                if (manifest.isFile && manifest.readText().contains("\"${info.pluginId}\"")) return dir
            }
        }
        return null
    }

    /** Write skills + open the CLI terminal. Returns a human-readable status. */
    fun launchEvolve(
        info: LoadedPluginInfo,
        agent: CliAgent,
        repoDir: File,
        task: String? = null,
    ): Result<String> = runCatching {
        require(repoDir.isDirectory) { "Source repo not found: ${repoDir.absolutePath}" }
        writeSkills(info, repoDir)
        val ops = services.context.splitViewOperations
            ?: error("Terminal unavailable — run manually: cd ${repoDir.absolutePath} && ${agent.launchCommand(task)}")
        ops.openTab(
            TerminalTabInfo(
                id = "sidecar-evolve-${info.pluginId}-${System.currentTimeMillis()}",
                typeId = TerminalTabType.typeId,
                title = "Evolve: ${info.displayName}",
                initialCommand = agent.launchCommand(task),
                workingDirectory = repoDir.absolutePath,
            )
        )
        "Opened ${agent.displayName} on ${repoDir.absolutePath}"
    }

    /**
     * Materialize the sidecar-evolve skill in all four CLI formats so the repo
     * works with whichever agent opens it later (tool-creator's convention).
     */
    fun writeSkills(info: LoadedPluginInfo, repoDir: File) {
        val body = renderSkillBody(info, repoDir)
        val description =
            "Evolve the ${info.displayName} BOSS plugin: implement the change, build, hot-reload into the running BOSS instance, verify, then open a PR"
        listOf(".claude/skills/sidecar-evolve", ".codex/skills/sidecar-evolve").forEach { dir ->
            File(repoDir, "$dir/SKILL.md").apply { parentFile.mkdirs() }.writeText(
                "---\nname: sidecar-evolve\ndescription: $description\n---\n\n$body"
            )
        }
        File(repoDir, ".gemini/commands/sidecar-evolve.toml").apply { parentFile.mkdirs() }.writeText(
            "description = \"$description\"\nprompt = \"\"\"\n$body\n\"\"\"\n"
        )
        File(repoDir, ".opencode/command/sidecar-evolve.md").apply { parentFile.mkdirs() }.writeText(
            "---\ndescription: $description\n---\n\n$body"
        )
    }

    private fun renderSkillBody(info: LoadedPluginInfo, repoDir: File): String {
        val template = javaClass.classLoader
            .getResourceAsStream("templates/evolve-skill-body.md")
            ?.bufferedReader()?.readText()
            ?: error("Bundled template missing: templates/evolve-skill-body.md")
        val pluginsDir = services.loader?.getPluginsDirectory()
            ?: (System.getProperty("user.home") + "/.boss/plugins")
        return template
            .replace("@@PLUGIN_ID@@", info.pluginId)
            .replace("@@PLUGIN_NAME@@", info.displayName)
            .replace("@@PLUGIN_VERSION@@", info.version)
            .replace("@@PLUGINS_DIR@@", pluginsDir)
            .replace("@@REPO_DIR@@", repoDir.absolutePath)
            .replace("@@JAR_PATH@@", info.jarPath.ifBlank { "(unknown)" })
    }
}
