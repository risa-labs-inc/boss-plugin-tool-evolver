package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.LoadedPluginInfo
import java.io.File
import java.io.StringWriter
import java.util.concurrent.TimeUnit

/** Readiness of the `gh` CLI for filing issues. */
enum class GhStatus { NOT_INSTALLED, NOT_AUTHENTICATED, READY }

/** A GitHub issue summary for the Issue tab's open-issues list. */
data class IssueSummary(val number: Int, val title: String, val url: String)

/** A GitHub pull-request summary for the Evolve section's open-PRs card. */
data class PrSummary(val number: Int, val title: String, val url: String, val branch: String)

/**
 * GitHub operations against an installed plugin's repo via the `gh` CLI:
 * filing issues and listing open issues / pull requests.
 *
 * The target `owner/repo` is resolved from the plugin's local checkout's origin
 * remote when one is found, otherwise from [EvolveLauncher.guessGitUrl] (its
 * manifest url, else the house `risa-labs-inc/boss-plugin-<slug>` convention).
 * All commands pass `--repo <slug>`, so no local checkout is needed.
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
     * Open issues for [slug] via `gh issue list`. Returns empty (not error) when
     * gh isn't ready. Pass an already-probed [status] to skip the extra
     * `gh auth status` subprocess.
     */
    fun listOpenIssues(slug: String, limit: Int = 30, status: GhStatus = ghStatus()): Result<List<IssueSummary>> =
        listOpenTsv("issue", slug, limit, status, fields = "number,title,url") { parts ->
            parts[0].toIntOrNull()?.let { IssueSummary(it, parts[1], parts[2]) }
        }

    /**
     * Open pull requests targeting [slug] via `gh pr list`. Returns empty (not
     * error) when gh isn't ready. Pass an already-probed [status] to skip the
     * extra `gh auth status` subprocess.
     */
    fun listOpenPrs(slug: String, limit: Int = 30, status: GhStatus = ghStatus()): Result<List<PrSummary>> =
        listOpenTsv("pr", slug, limit, status, fields = "number,title,url,headRefName") { parts ->
            parts[0].toIntOrNull()?.let { PrSummary(it, parts[1], parts[2], parts[3]) }
        }

    /**
     * Shared `gh <kind> list` runner: emits [fields] as TSV via gh's built-in jq
     * (no JSON dependency), splits columns, and un-escapes @tsv's in-field
     * escapes before handing each row to [build].
     */
    private fun <T> listOpenTsv(
        kind: String,
        slug: String,
        limit: Int,
        status: GhStatus,
        fields: String,
        build: (List<String>) -> T?,
    ): Result<List<T>> = runCatching {
        if (status != GhStatus.READY) return@runCatching emptyList()
        val names = fields.split(',')
        val (out, exit) = runGh(
            listOf(
                kind, "list", "--repo", slug, "--state", "open", "--limit", limit.toString(),
                "--json", fields,
                "--jq", ".[] | [${names.joinToString(", ") { ".$it" }}] | @tsv",
            )
        )
        if (exit != 0) error(out.trim().takeLast(300).ifBlank { "gh $kind list failed" })
        out.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size < names.size) return@mapNotNull null
            build(parts.map(::unescapeTsv))
        }.toList()
    }

    /** Reverse jq @tsv's in-field escapes (\t \n \r \\) back to real characters. */
    private fun unescapeTsv(field: String): String {
        if ('\\' !in field) return field
        val sb = StringBuilder(field.length)
        var i = 0
        while (i < field.length) {
            val c = field[i]
            if (c == '\\' && i + 1 < field.length) {
                val unescaped = when (field[i + 1]) {
                    't' -> '\t'
                    'n' -> '\n'
                    'r' -> '\r'
                    '\\' -> '\\'
                    else -> null
                }
                if (unescaped != null) {
                    sb.append(unescaped)
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /**
     * gh readiness for the UI: distinguishes "not installed" (no binary on PATH)
     * from "not authenticated" (binary present, `gh auth status` non-zero) so the
     * tab can show the right guidance instead of a generic failure.
     */
    fun ghStatus(): GhStatus {
        if (CliAgent.resolveBinary("gh") == null) return GhStatus.NOT_INSTALLED
        val exit = runCatching { runGh(listOf("auth", "status")).second }.getOrNull()
            ?: return GhStatus.NOT_INSTALLED // start() threw despite the PATH probe
        return if (exit == 0) GhStatus.READY else GhStatus.NOT_AUTHENTICATED
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
        // A bare command name is resolved against the PARENT's PATH, not the
        // widened child PATH below — resolve it to an absolute path ourselves.
        val exe = CliAgent.resolveBinary(command.first())?.absolutePath ?: command.first()
        val pb = ProcessBuilder(listOf(exe) + command.drop(1)).redirectErrorStream(true)
        if (dir != null) pb.directory(dir)
        val home = System.getProperty("user.home")
        val extras = listOf("$home/.local/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
        pb.environment()["PATH"] = (extras + (pb.environment()["PATH"] ?: "")).joinToString(File.pathSeparator)
        val process = pb.start()
        // Drain output on a daemon thread: reading to EOF on this thread would
        // block until the child closes its stream, making the timeout below
        // unreachable if the child hangs (and the loading UI stuck with it).
        val out = StringWriter()
        val drainer = Thread { runCatching { process.inputStream.bufferedReader().copyTo(out) } }
            .apply { isDaemon = true; start() }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "timed out" to -1
        }
        drainer.join(5_000)
        return out.toString() to process.exitValue()
    }
}
