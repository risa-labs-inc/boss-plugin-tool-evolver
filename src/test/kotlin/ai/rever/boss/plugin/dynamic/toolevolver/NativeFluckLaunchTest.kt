package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.NewTabContext
import ai.rever.boss.plugin.api.NewTabSpec
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeFluckLaunchTest {
    @Test
    fun `native launch passes exact repository and window to registered factory`() {
        val repo = File("/tmp/plugin worktree/dark-mode")
        val factory = RecordingFactory()
        val task = "Keep \"quoted\" text and $ signs\nSecond line"
        val prompt = EvolveLauncher.nativePrompt("Test plugin", repo, task)

        val tab = EvolveLauncher.nativeFluckTab(factory, repo, "window-42", prompt)

        assertSame(factory.tab, tab)
        assertEquals(NewTabContext(repo.absolutePath, "window-42"), factory.receivedContext)
        assertEquals(prompt, factory.receivedPrompt)
        assertTrue(prompt.contains(task), "Native requests must not be shell-sanitized")
        assertTrue(prompt.contains(repo.absolutePath))
        assertTrue(prompt.contains(".claude/skills/evolve/SKILL.md"))
        assertTrue(prompt.contains("evolver_hot_reload"))
        assertTrue(prompt.contains("Do not push main"))
    }

    @Test
    fun `factory rejection reports actionable failure without creating a terminal`() {
        val error = assertFailsWith<IllegalStateException> {
            EvolveLauncher.nativeFluckTab(RecordingFactory(reject = true), File("/tmp/repo"), null, "evolve")
        }
        assertTrue(error.message.orEmpty().contains("enable it and refresh"))
    }

    @Test
    fun `native agent aliases route to Fluck and never create a shell command`() {
        listOf("fluck", "fluck-agent", "FLUCK_AGENT").forEach { assertEquals(CliAgent.FLUCK_AGENT, CliAgent.fromId(it)) }
        assertTrue(CliAgent.FLUCK_AGENT.isNative)
        assertFailsWith<IllegalStateException> { CliAgent.FLUCK_AGENT.launchCommand("evolve") }
    }

    @Test
    fun `existing terminal agents keep their evolution commands`() {
        assertEquals("claude --permission-mode auto \"/evolve fix colors\"", CliAgent.CLAUDE_CODE.launchCommand("fix colors"))
        assertTrue(CliAgent.CODEX.launchCommand("fix colors").contains(".codex/skills/evolve/SKILL.md"))
        assertTrue(CliAgent.GEMINI.launchCommand().startsWith("gemini "))
        assertTrue(CliAgent.OPENCODE.launchCommand().startsWith("opencode "))
    }

    private class RecordingFactory(private val reject: Boolean = false) : TabTypeInfo {
        override val typeId = TabTypeId("fluck-agent", "ai.rever.boss.plugin.dynamic.fluckagent")
        override val displayName = "Fluck Agent"
        override val icon: ImageVector get() = error("No icon is needed for a handoff")
        override val newTabSpec = NewTabSpec(inputOptional = true)
        var receivedPrompt: String? = null
        var receivedContext: NewTabContext? = null
        val tab = object : TabInfo {
            override val id = "native-fluck-conversation-42"
            override val typeId = this@RecordingFactory.typeId
            override val title = "Fluck Agent"
            override val icon: ImageVector get() = error("No icon is needed for a handoff")
        }
        override fun createTabInfo(input: String, context: NewTabContext): TabInfo? {
            receivedPrompt = input
            receivedContext = context
            return if (reject) null else tab
        }
    }
}
