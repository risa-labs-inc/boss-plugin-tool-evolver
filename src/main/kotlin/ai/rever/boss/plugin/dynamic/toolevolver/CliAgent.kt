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
) {
    CLAUDE_CODE("Claude Code", "claude"),
    CODEX("Codex", "codex"),
    GEMINI("Gemini", "gemini"),
    OPENCODE("OpenCode", "opencode");

    /**
     * Shell command that opens the CLI inside the plugin repo with the
     * evolve skill already engaged. Kept apostrophe- and quote-free so it
     * survives shell quoting untouched.
     */
    fun launchCommand(task: String? = null): String {
        val ask = sanitize(task)
        val suffix = if (ask.isEmpty()) "" else " Requested evolution: $ask"
        return when (this) {
            CLAUDE_CODE ->
                if (ask.isEmpty()) "claude \"/evolve\""
                else "claude \"/evolve $ask\""
            CODEX ->
                "codex \"Load the evolve skill in .codex/skills/evolve/SKILL.md and follow it to evolve this plugin.$suffix\""
            GEMINI ->
                "gemini -i \"Read .claude/skills/evolve/SKILL.md and follow it to evolve this plugin.$suffix\""
            OPENCODE ->
                "opencode --prompt \"Follow the evolve command in .opencode/command/evolve.md to evolve this plugin.$suffix\""
        }
    }

    /** Best-effort check whether the CLI binary is on PATH (or in common install dirs). */
    fun isInstalled(): Boolean {
        val home = System.getProperty("user.home")
        val extraDirs = listOf("$home/.local/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        return (pathDirs + extraDirs).any { dir ->
            dir.isNotBlank() && File(dir, binary).let { it.isFile && it.canExecute() }
        }
    }

    companion object {
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
