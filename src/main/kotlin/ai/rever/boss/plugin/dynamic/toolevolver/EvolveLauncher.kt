package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.TabSplitMode
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the evolve terminal opens — the "new tab vs split" choice, mirroring the
 * host's terminal-link chooser. Splits use [TabSplitMode] via
 * [ai.rever.boss.plugin.api.SplitViewOperations.openTabInSplit]; NEW_TAB uses openTab.
 */
enum class EvolveOpenLocation(val label: String) {
    NEW_TAB("new tab"),
    EXISTING_SPLIT("existing split"),
    SPLIT_RIGHT("split right"),
    SPLIT_DOWN("split down"),
}

/**
 * How an evolution session works on the repo:
 * - [NORMAL]: evolve directly in the repo's main working tree (one session).
 * - [WORKTREE]: evolve in an isolated `git worktree` under `<repo>/.worktrees/<slug>`
 *   on a dedicated `evolve/<slug>` branch, so several features/issues can be
 *   evolved for the same plugin in parallel without clobbering each other.
 */
enum class EvolveMode { NORMAL, WORKTREE }

/** An existing evolution worktree under `<repo>/.worktrees/`. */
data class WorktreeInfo(val slug: String, val path: String, val branch: String)

/**
 * Starts an "evolution" of an installed plugin: writes the evolve skill
 * (with full plugin context) into the plugin's source repo in every supported
 * CLI's native format, then opens a BossTerm tab in that repo running the chosen
 * AI CLI with the skill engaged. The skill closes the loop: build → hot-reload
 * via the `evolver_hot_reload` MCP tool → verify → open a PR.
 */
class EvolveLauncher(private val services: EvolverServices) {

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

    /**
     * Where evolution checkouts land when cloned: the house plugins umbrella if
     * present, else a stable ~/BossTools fallback (mirrors tool-creator).
     */
    fun defaultCloneParent(): File {
        val home = System.getProperty("user.home")
        val umbrella = File(home, "Development/Boss/boss_plugins")
        return if (umbrella.isDirectory) umbrella
        else File(home, "BossTools").apply { mkdirs() }
    }

    /**
     * Best-effort git URL for a plugin's repo: its manifest [LoadedPluginInfo.url]
     * when that points at a git host, otherwise the house naming convention
     * `risa-labs-inc/boss-plugin-<lastIdSegment>`. Always editable in the UI.
     */
    fun guessGitUrl(info: LoadedPluginInfo): String {
        val url = info.url.trim()
        if (url.contains("github.com") || url.endsWith(".git")) {
            return if (url.endsWith(".git")) url else "${url.trimEnd('/')}.git"
        }
        val slug = info.pluginId.substringAfterLast('.')
        return "https://github.com/risa-labs-inc/boss-plugin-$slug.git"
    }

    /** Directory name a clone of [gitUrl] would create. */
    fun repoDirName(gitUrl: String): String =
        gitUrl.trimEnd('/').substringAfterLast('/').removeSuffix(".git")

    /**
     * Clone [gitUrl] into [parentDir] and record the result as this plugin's repo
     * override. If the target dir already exists and is a git checkout, reuse it
     * (idempotent). Streams git output to [onLine].
     */
    fun cloneRepo(
        info: LoadedPluginInfo,
        gitUrl: String,
        parentDir: File,
        onLine: (String) -> Unit,
    ): Result<File> = runCatching {
        require(gitUrl.isNotBlank()) { "No git URL" }
        parentDir.mkdirs()
        val dir = File(parentDir, repoDirName(gitUrl))
        if (File(dir, ".git").isDirectory) {
            onLine("Reusing existing checkout at ${dir.absolutePath}")
        } else {
            require(!dir.exists()) { "${dir.absolutePath} exists but is not a git checkout" }
            onLine("$ git clone $gitUrl ${dir.name}")
            runGit(parentDir, listOf("clone", gitUrl, dir.name), onLine).getOrThrow()
            require(File(dir, ".git").isDirectory) { "Clone did not produce a git repo" }
        }
        setRepoOverride(info.pluginId, dir.absolutePath)
        dir
    }

    private fun runGit(dir: File, args: List<String>, onLine: (String) -> Unit): Result<Unit> = runCatching {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(dir)
            .redirectErrorStream(true)
            .also { pb ->
                val home = System.getProperty("user.home")
                val extras = listOf("$home/.local/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
                pb.environment()["PATH"] = (extras + (pb.environment()["PATH"] ?: "")).joinToString(File.pathSeparator)
            }
            .start()
        process.inputStream.bufferedReader().useLines { it.forEach(onLine) }
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
            process.destroyForcibly()
            error("git ${args.first()} timed out")
        }
        if (process.exitValue() != 0) error("git ${args.first()} failed (exit ${process.exitValue()})")
    }

    // ------------------------------------------------------------ worktrees

    /** Branch-safe slug for a feature/issue name. */
    fun slugify(name: String): String =
        name.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "evolution" }

    private fun worktreesDir(repo: File): File = File(repo, ".worktrees")

    /** Existing evolution worktrees under `<repo>/.worktrees/` (from `git worktree list`). */
    fun listWorktrees(repo: File): List<WorktreeInfo> {
        val (out, exit) = runGitCapture(repo, listOf("worktree", "list", "--porcelain"))
        if (exit != 0) return emptyList()
        val wtRoot = worktreesDir(repo).absolutePath
        val result = mutableListOf<WorktreeInfo>()
        var path: String? = null
        var branch = ""
        fun flush() {
            val p = path ?: return
            if (File(p).absolutePath.startsWith(wtRoot)) {
                result += WorktreeInfo(slug = File(p).name, path = p, branch = branch.removePrefix("refs/heads/"))
            }
            path = null; branch = ""
        }
        out.lineSequence().forEach { line ->
            when {
                line.startsWith("worktree ") -> { flush(); path = line.removePrefix("worktree ").trim() }
                line.startsWith("branch ") -> branch = line.removePrefix("branch ").trim()
            }
        }
        flush()
        return result.sortedBy { it.slug }
    }

    /**
     * Ensure a worktree exists at `<repo>/.worktrees/<slug>` on branch
     * `evolve/<slug>`, creating it (and the branch) if needed. Reuses an existing
     * worktree/branch. Returns the worktree directory.
     */
    fun ensureWorktree(repo: File, slug: String, onLine: (String) -> Unit): Result<File> = runCatching {
        require(slug.isNotBlank()) { "Worktree name is required" }
        ensureGitignoreWorktrees(repo)
        val dir = File(worktreesDir(repo), slug)
        val branch = "evolve/$slug"
        if (File(dir, ".git").exists()) {
            onLine("Reusing worktree ${dir.absolutePath} ($branch)")
            return@runCatching dir
        }
        worktreesDir(repo).mkdirs()
        val branchExists = runGitCapture(repo, listOf("rev-parse", "--verify", "--quiet", "refs/heads/$branch")).second == 0
        val addArgs = if (branchExists) {
            onLine("$ git worktree add .worktrees/$slug $branch")
            listOf("worktree", "add", ".worktrees/$slug", branch)
        } else {
            onLine("$ git worktree add .worktrees/$slug -b $branch")
            listOf("worktree", "add", ".worktrees/$slug", "-b", branch)
        }
        runGit(repo, addArgs, onLine).getOrThrow()
        require(File(dir, ".git").exists()) { "Worktree add did not produce ${dir.absolutePath}" }
        dir
    }

    /** Remove a worktree (its branch is kept — it may have commits / an open PR). */
    fun removeWorktree(repo: File, slug: String, onLine: (String) -> Unit): Result<Unit> = runCatching {
        onLine("$ git worktree remove --force .worktrees/$slug")
        runGit(repo, listOf("worktree", "remove", "--force", ".worktrees/$slug"), onLine).getOrThrow()
    }

    /** Add `.worktrees/` to the repo's .gitignore if not already present. */
    private fun ensureGitignoreWorktrees(repo: File) {
        val gitignore = File(repo, ".gitignore")
        val entry = ".worktrees/"
        val lines = if (gitignore.exists()) gitignore.readLines() else emptyList()
        if (lines.none { it.trim() == entry || it.trim() == ".worktrees" }) {
            gitignore.appendText((if (lines.isNotEmpty() && lines.last().isNotBlank()) "\n" else "") + "$entry\n")
        }
    }

    private fun runGitCapture(dir: File, args: List<String>): Pair<String, Int> {
        return try {
            val process = ProcessBuilder(listOf("git") + args)
                .directory(dir)
                .redirectErrorStream(true)
                .also { pb ->
                    val home = System.getProperty("user.home")
                    val extras = listOf("$home/.local/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
                    pb.environment()["PATH"] = (extras + (pb.environment()["PATH"] ?: "")).joinToString(File.pathSeparator)
                }
                .start()
            val out = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.MINUTES)) {
                process.destroyForcibly(); return "timed out" to -1
            }
            out to process.exitValue()
        } catch (e: Exception) {
            (e.message ?: "git failed") to -1
        }
    }

    /** Write skills + open the CLI terminal. Returns a human-readable status. */
    fun launchEvolve(
        info: LoadedPluginInfo,
        agent: CliAgent,
        repoDir: File,
        task: String? = null,
        location: EvolveOpenLocation = EvolveOpenLocation.NEW_TAB,
        branch: String? = null,
    ): Result<String> = runCatching {
        require(repoDir.isDirectory) { "Source repo not found: ${repoDir.absolutePath}" }
        writeSkills(info, repoDir, branch)
        val ops = services.context.splitViewOperations
            ?: error("Terminal unavailable — run manually: cd ${repoDir.absolutePath} && ${agent.launchCommand(task)}")
        val label = branch?.removePrefix("evolve/")?.let { " ($it)" } ?: ""
        val tabInfo = TerminalTabInfo(
            id = "evolve-${info.pluginId}-${System.currentTimeMillis()}",
            typeId = TerminalTabType.typeId,
            title = "Evolve: ${info.displayName}$label",
            initialCommand = agent.launchCommand(task),
            workingDirectory = repoDir.absolutePath,
        )
        when (location) {
            EvolveOpenLocation.NEW_TAB -> ops.openTab(tabInfo)
            EvolveOpenLocation.EXISTING_SPLIT -> ops.openTabInSplit(tabInfo, TabSplitMode.EXISTING_SPLIT)
            EvolveOpenLocation.SPLIT_RIGHT -> ops.openTabInSplit(tabInfo, TabSplitMode.VERTICAL_SPLIT)
            EvolveOpenLocation.SPLIT_DOWN -> ops.openTabInSplit(tabInfo, TabSplitMode.HORIZONTAL_SPLIT)
        }
        "Opened ${agent.displayName} on ${repoDir.absolutePath} (${location.label})"
    }

    /**
     * Materialize the evolve skill in all four CLI formats so the repo
     * works with whichever agent opens it later (tool-creator's convention).
     */
    fun writeSkills(info: LoadedPluginInfo, repoDir: File, branch: String? = null) {
        val body = renderSkillBody(info, repoDir, branch)
        val description =
            "Evolve the ${info.displayName} BOSS plugin: implement the change, build, hot-reload into the running BOSS instance, verify, then open a PR"
        listOf(".claude/skills/evolve", ".codex/skills/evolve").forEach { dir ->
            File(repoDir, "$dir/SKILL.md").apply { parentFile.mkdirs() }.writeText(
                "---\nname: evolve\ndescription: $description\n---\n\n$body"
            )
        }
        File(repoDir, ".gemini/commands/evolve.toml").apply { parentFile.mkdirs() }.writeText(
            "description = \"$description\"\nprompt = \"\"\"\n$body\n\"\"\"\n"
        )
        File(repoDir, ".opencode/command/evolve.md").apply { parentFile.mkdirs() }.writeText(
            "---\ndescription: $description\n---\n\n$body"
        )
    }

    private fun renderSkillBody(info: LoadedPluginInfo, repoDir: File, branch: String?): String {
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
            .replace("@@BRANCH_GUIDANCE@@", branchGuidance(branch))
    }

    /** PR/branch instructions for the skill: worktree mode is already on its branch. */
    private fun branchGuidance(branch: String?): String = if (branch != null) """
You are working in a git worktree already checked out on the dedicated branch `$branch`.
Commit your work here and open a PR — do NOT push `main` (pushing `main` triggers a store release):

```bash
git add -A
git commit -m "<type>: <what evolved>"   # feat/fix/refactor…
git push -u origin $branch
gh pr create --fill --body "<what changed, why, and how it was verified (hot-reloaded live via Tool Evolver)>"
```
""".trim() else """
Create a branch and open a PR — do NOT push `main` directly (pushing `main` triggers a store release):

```bash
git checkout -b evolve/<short-topic>
git add -A
git commit -m "<type>: <what evolved>"   # feat/fix/refactor…
git push -u origin evolve/<short-topic>
gh pr create --fill --body "<what changed, why, and how it was verified (hot-reloaded live via Tool Evolver)>"
```
""".trim()
}
