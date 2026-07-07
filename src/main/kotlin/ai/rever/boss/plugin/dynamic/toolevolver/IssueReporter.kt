package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.LoadedPluginInfo
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Files a GitHub issue against an installed plugin's repo via the `gh` CLI.
 *
 * The target `owner/repo` is resolved from the plugin's local checkout's origin
 * remote when one is found, otherwise from [EvolveLauncher.guessGitUrl] (its
 * manifest url, else the house `risa-labs-inc/boss-plugin-<slug>` convention).
 * `gh issue create --repo <slug>` works without a local checkout.
 */
class IssueReporter(private val services: EvolverServices) {

    /** Best-effort `owner/repo` for [info], or null if it can't be derived. */
    fun repoSlug(info: LoadedPluginInfo): String? {
        services.evolveLauncher.resolveSourceRepo(info)?.let { dir ->
            originRemote(dir)?.let { slugFromUrl(it)?.let { slug -> return slug } }
        }
        return slugFromUrl(services.evolveLauncher.guessGitUrl(info))
    }

    /**
     * Create an issue on [slug] and return the created issue URL. Requires `gh`
     * on PATH and an authenticated session.
     */
    fun createIssue(slug: String, title: String, body: String): Result<String> = runCatching {
        require(title.isNotBlank()) { "Issue title is required" }
        val (out, exit) = runGh(
            listOf("issue", "create", "--repo", slug, "--title", title, "--body", body.ifBlank { " " })
        )
        if (exit != 0) {
            val hint = when {
                out.contains("gh auth login") || out.contains("authentication") ->
                    " (run `gh auth login`)"
                out.contains("Could not resolve") || out.contains("not found") ->
                    " (repo $slug not found — set the right repo)"
                else -> ""
            }
            error("gh issue create failed$hint: ${out.trim().takeLast(300)}")
        }
        // gh prints the new issue URL on its own line.
        out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("https://") }
            ?: "Issue created on $slug"
    }

    /** True when `gh` is on PATH and authenticated (for enabling the UI). */
    fun ghAvailable(): Boolean = runCatching {
        val (_, exit) = runGh(listOf("auth", "status"))
        exit == 0
    }.getOrDefault(false)

    private fun originRemote(dir: File): String? = runCatching {
        val (out, exit) = run(dir, listOf("git", "remote", "get-url", "origin"))
        if (exit == 0) out.trim().ifBlank { null } else null
    }.getOrNull()

    private fun slugFromUrl(url: String): String? {
        val u = url.trim()
        val m = Regex("github\\.com[:/]+([^/]+)/([^/\\s]+?)(?:\\.git)?/?$").find(u) ?: return null
        return "${m.groupValues[1]}/${m.groupValues[2]}"
    }

    private fun runGh(args: List<String>) = run(null, listOf("gh") + args)

    /** Run a process with a widened PATH (the packaged host launches with a bare PATH). */
    private fun run(dir: File?, command: List<String>): Pair<String, Int> {
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        if (dir != null) pb.directory(dir)
        val home = System.getProperty("user.home")
        val extras = listOf("$home/.local/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
        pb.environment()["PATH"] = (extras + (pb.environment()["PATH"] ?: "")).joinToString(File.pathSeparator)
        val process = pb.start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "timed out" to -1
        }
        return out to process.exitValue()
    }
}
