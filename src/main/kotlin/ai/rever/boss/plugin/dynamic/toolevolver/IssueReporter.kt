package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.LoadedPluginInfo
import java.io.File
import java.util.concurrent.TimeUnit

/** Readiness of the `gh` CLI for filing issues. */
enum class GhStatus { NOT_INSTALLED, NOT_AUTHENTICATED, READY }

/** A GitHub issue summary for the Issue tab's open-issues list. */
data class IssueSummary(val number: Int, val title: String, val url: String)

/** A GitHub pull-request summary for the Evolve section's open-PRs card. */
data class PrSummary(val number: Int, val title: String, val url: String, val branch: String)

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
        when (ghStatus()) {
            GhStatus.NOT_INSTALLED -> error("GitHub CLI (gh) is not installed — install it from https://cli.github.com")
            GhStatus.NOT_AUTHENTICATED -> error("gh is not authenticated — run `gh auth login`")
            GhStatus.READY -> {}
        }
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

    /**
     * Open issues for [slug] via `gh issue list` (uses gh's built-in jq to emit
     * TSV, so no JSON dependency). Returns empty (not error) when gh isn't ready.
     */
    fun listOpenIssues(slug: String, limit: Int = 30): Result<List<IssueSummary>> = runCatching {
        if (ghStatus() != GhStatus.READY) return@runCatching emptyList()
        val (out, exit) = runGh(
            listOf(
                "issue", "list", "--repo", slug, "--state", "open", "--limit", limit.toString(),
                "--json", "number,title,url", "--jq", ".[] | [.number, .title, .url] | @tsv",
            )
        )
        if (exit != 0) error(out.trim().takeLast(300).ifBlank { "gh issue list failed" })
        out.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val number = parts[0].toIntOrNull() ?: return@mapNotNull null
            IssueSummary(number, parts[1], parts[2])
        }.toList()
    }

    /**
     * Open pull requests targeting [slug] via `gh pr list` (same TSV trick as
     * [listOpenIssues]). Returns empty (not error) when gh isn't ready.
     */
    fun listOpenPrs(slug: String, limit: Int = 30): Result<List<PrSummary>> = runCatching {
        if (ghStatus() != GhStatus.READY) return@runCatching emptyList()
        val (out, exit) = runGh(
            listOf(
                "pr", "list", "--repo", slug, "--state", "open", "--limit", limit.toString(),
                "--json", "number,title,url,headRefName",
                "--jq", ".[] | [.number, .title, .url, .headRefName] | @tsv",
            )
        )
        if (exit != 0) error(out.trim().takeLast(300).ifBlank { "gh pr list failed" })
        out.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size < 4) return@mapNotNull null
            val number = parts[0].toIntOrNull() ?: return@mapNotNull null
            PrSummary(number, parts[1], parts[2], parts[3])
        }.toList()
    }

    /**
     * gh readiness for the UI: distinguishes "not installed" (no binary on PATH)
     * from "not authenticated" (binary present, `gh auth status` non-zero) so the
     * tab can show the right guidance instead of a generic failure.
     */
    fun ghStatus(): GhStatus {
        if (!binaryOnPath("gh")) return GhStatus.NOT_INSTALLED
        val exit = runCatching { runGh(listOf("auth", "status")).second }.getOrNull()
            ?: return GhStatus.NOT_INSTALLED // start() threw despite the PATH probe
        return if (exit == 0) GhStatus.READY else GhStatus.NOT_AUTHENTICATED
    }

    /** True when [bin] is an executable file on PATH or a common install dir. */
    private fun binaryOnPath(bin: String): Boolean {
        val home = System.getProperty("user.home")
        val extras = listOf("$home/.local/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        return (pathDirs + extras).any { dir ->
            dir.isNotBlank() && File(dir, bin).let { it.isFile && it.canExecute() }
        }
    }

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
