package ai.rever.boss.plugin.dynamic.toolevolver

import java.io.File

/**
 * AI coding CLIs the Tool Evolver can hand an existing plugin repo to for evolution.
 *
 * Each agent gets the evolve skill written into the plugin's repo in its
 * own native format, and is launched in a new BossTerm tab with a kick-off prompt
 * that engages that skill.
 */
enum class CliAgent(
    val displayName: String,
    val binary: String,
    /**
     * Flags that put the CLI in its hands-free "auto" mode so the evolve loop
     * (edit → build → hot-reload via MCP) runs without approval prompts.
     * OpenCode doesn't gate tools by default, so it needs none.
     */
    private val autoFlags: String,
) {
    CLAUDE_CODE("Claude Code", "claude", "--permission-mode auto"),
    CODEX("Codex", "codex", "--sandbox workspace-write -a on-failure"),
    GEMINI("Gemini", "gemini", "--approval-mode yolo"),
    OPENCODE("OpenCode", "opencode", "");

    /**
     * Shell command that opens the CLI inside the plugin repo with the
     * evolve skill already engaged. Kept apostrophe- and quote-free so it
     * survives shell quoting untouched.
     */
    fun launchCommand(task: String? = null): String {
        val ask = sanitize(task)
        val suffix = if (ask.isEmpty()) "" else " Requested evolution: $ask"
        val auto = if (autoFlags.isBlank()) "" else " $autoFlags"
        return when (this) {
            CLAUDE_CODE ->
                if (ask.isEmpty()) "claude$auto \"/evolve\""
                else "claude$auto \"/evolve $ask\""
            CODEX ->
                "codex$auto \"Load the evolve skill in .codex/skills/evolve/SKILL.md and follow it to evolve this plugin.$suffix\""
            GEMINI ->
                "gemini$auto -i \"Read .claude/skills/evolve/SKILL.md and follow it to evolve this plugin.$suffix\""
            OPENCODE ->
                "opencode --prompt \"Follow the evolve command in .opencode/command/evolve.md to evolve this plugin.$suffix\""
        }
    }

    /** Best-effort check whether the CLI binary is on PATH (or in common install dirs). */
    fun isInstalled(): Boolean = binaryOnPath(binary)

    companion object {
        /**
         * Absolute path of [binary] on PATH or a common install dir, or null.
         * ProcessBuilder resolves bare command names against the PARENT's PATH,
         * which is nearly empty when the packaged host launches from Finder —
         * callers must pass this resolved path instead of the bare name.
         */
        fun resolveBinary(binary: String): File? {
            val home = System.getProperty("user.home")
            val extraDirs = listOf("$home/.local/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
            val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
            return (pathDirs + extraDirs).asSequence()
                .filter { it.isNotBlank() }
                .map { File(it, binary) }
                .firstOrNull { it.isFile && it.canExecute() }
        }

        /** True when [binary] is an executable file on PATH or a common install dir. */
        fun binaryOnPath(binary: String): Boolean = resolveBinary(binary) != null

        /** Strip characters that would break the double-quoted shell command. */
        fun sanitize(task: String?): String =
            (task ?: "")
                .replace(Regex("[\"'`$\\\\]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()

        fun fromId(id: String?): CliAgent? = when (id?.lowercase()?.trim()) {
            null, "" -> null
            "claude", "claude_code", "claude-code", "claudecode" -> CLAUDE_CODE
            "codex" -> CODEX
            "gemini" -> GEMINI
            "opencode" -> OPENCODE
            else -> null
        }
    }
}
