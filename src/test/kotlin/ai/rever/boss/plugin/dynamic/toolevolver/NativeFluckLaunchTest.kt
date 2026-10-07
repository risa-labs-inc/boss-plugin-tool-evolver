package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.RegisteredMcpTool
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeFluckLaunchTest {
    @Test
    fun `native launch passes exact repository and prompt through public MCP registry`() = runBlocking {
        val repo = File("/tmp/plugin worktree/dark-mode")
        val registry = RecordingRegistry()
        val task = "Keep \"quoted\" text and $ signs\nSecond line"
        val prompt = EvolveLauncher.nativePrompt("Test plugin", repo, task)

        val tabId = EvolveLauncher.nativeFluckTabId(registry, repo, prompt, "Evolve: Test plugin")

        assertEquals("native-fluck-conversation-42", tabId)
        assertEquals("fluck_launch", registry.receivedTool)
        val arguments = Json.parseToJsonElement(registry.receivedArguments!!).jsonObject
        assertEquals(repo.absolutePath, arguments["project"]!!.jsonPrimitive.content)
        assertEquals(prompt, arguments["prompt"]!!.jsonPrimitive.content)
        assertEquals("Evolve: Test plugin", arguments["title"]!!.jsonPrimitive.content)
        assertEquals("new_tab", arguments["location"]!!.jsonPrimitive.content)
        assertTrue(prompt.contains(task), "Native requests must not be shell-sanitized")
        assertTrue(prompt.contains(repo.absolutePath))
        assertTrue(prompt.contains(".claude/skills/evolve/SKILL.md"))
        assertTrue(prompt.contains("evolver_hot_reload"))
        assertTrue(prompt.contains("Do not push main"))
    }

    @Test
    fun `native launch forwards every chooser destination through MCP`() = runBlocking {
        val choices = mapOf(
            EvolveOpenLocation.NEW_TAB to "new_tab",
            EvolveOpenLocation.EXISTING_SPLIT to "existing_split",
            EvolveOpenLocation.SPLIT_RIGHT to "split_right",
            EvolveOpenLocation.SPLIT_DOWN to "split_down",
        )
        choices.forEach { (location, expected) ->
            val registry = RecordingRegistry()
            EvolveLauncher.nativeFluckTabId(registry, File("/tmp/repo"), "evolve", "Evolve", location)
            val arguments = Json.parseToJsonElement(registry.receivedArguments!!).jsonObject
            assertEquals(expected, arguments["location"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `tool errors remain actionable and never record fake session ids`() = runBlocking {
        val registry = RecordingRegistry(McpToolResult("Launch denied by your policy", isError = true))
        val error = assertFailsWith<IllegalStateException> {
            EvolveLauncher.nativeFluckTabId(registry, File("/tmp/repo"), "evolve", "Evolve")
        }
        assertEquals("Launch denied by your policy", error.message)
    }

    @Test
    fun `malformed and missing returned tab ids fail rather than open a fallback terminal`() = runBlocking {
        listOf("not JSON", "{}", "{\"tab_id\":42}", "{\"tab_id\":\"\"}").forEach { response ->
            assertFailsWith<IllegalStateException> {
                EvolveLauncher.nativeFluckTabId(RecordingRegistry(McpToolResult(response)), File("/tmp/repo"), "evolve", "Evolve")
            }
        }
    }

    @Test
    fun `availability honors user tool filtering and provider ownership`() {
        assertFalse(EvolveLauncher.nativeAvailable(null))
        val registry = RecordingRegistry()
        assertTrue(EvolveLauncher.nativeAvailable(registry))
        registry.tools.value = listOf(registry.tools.value.single().copy(providerId = "other.plugin"))
        assertFalse(EvolveLauncher.nativeAvailable(registry))
        registry.tools.value = emptyList()
        assertFalse(EvolveLauncher.nativeAvailable(registry))
    }

    @Test
    fun `availability accepts host namespaces and rejects lookalike owners`() {
        val pluginId = "ai.rever.boss.plugin.dynamic.fluckagent"
        val registry = RecordingRegistry()
        val tool = registry.tools.value.single()
        listOf(pluginId, "$pluginId::$pluginId", "$pluginId::authoring").forEach { owner ->
            registry.tools.value = listOf(tool.copy(providerId = owner))
            assertTrue(EvolveLauncher.nativeAvailable(registry), owner)
        }
        listOf("$pluginId::", "$pluginId-extra::authoring", "other.plugin::$pluginId").forEach { owner ->
            registry.tools.value = listOf(tool.copy(providerId = owner))
            assertFalse(EvolveLauncher.nativeAvailable(registry), owner)
        }
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

    private class RecordingRegistry(
        private val result: McpToolResult = McpToolResult("{\"tab_id\":\"native-fluck-conversation-42\",\"project\":\"/tmp/repo\"}"),
    ) : McpToolRegistry {
        override val tools = MutableStateFlow(listOf(RegisteredMcpTool(
            providerId = "ai.rever.boss.plugin.dynamic.fluckagent",
            definition = McpToolDefinition(name = "fluck_launch", description = "Launch native chat", handler = McpToolHandler { result }),
        )))
        override val allTools = tools
        override val disabledToolNames = MutableStateFlow(emptySet<String>())
        var receivedTool: String? = null
        var receivedArguments: String? = null
        override fun setToolEnabled(toolName: String, enabled: Boolean) = Unit
        override suspend fun invoke(toolName: String, arguments: String): McpToolResult {
            receivedTool = toolName
            receivedArguments = arguments
            return result
        }
    }
}
