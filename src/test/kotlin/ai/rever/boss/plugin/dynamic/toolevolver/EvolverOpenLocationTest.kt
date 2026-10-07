package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.RegisteredMcpTool
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class EvolverOpenLocationTest {
    @Test
    fun `native evolve asks the existing chooser before opening in the main panel`() = runBlocking {
        Harness().use { h ->
            h.ready()
            h.vm.launchEvolve(CliAgent.FLUCK_AGENT)
            await { h.vm.pendingOpen.value != null }
            val pending = assertIs<PendingOpen.Evolve>(h.vm.pendingOpen.value)
            assertEquals(CliAgent.FLUCK_AGENT, pending.agent)
            assertEquals(h.directory.absolutePath, pending.dirPath)
            assertNull(h.registry.arguments, "Fluck launched before the human chose a destination")
            h.vm.onOpenLocationChosen(EvolveOpenLocation.NEW_TAB, remember = false)
            h.launched()
            assertEquals("new_tab", h.registry.location())
        }
    }

    @Test
    fun `native worktree reopen asks the chooser and forwards the chosen split`() = runBlocking {
        Harness().use { h ->
            h.ready()
            val worktree = WorktreeInfo("test", h.directory.absolutePath, "evolve/test")
            h.vm.reopenWorktree(worktree, CliAgent.FLUCK_AGENT)
            await { h.vm.pendingOpen.value != null }
            val pending = assertIs<PendingOpen.Evolve>(h.vm.pendingOpen.value)
            assertEquals(worktree.path, pending.dirPath)
            assertEquals(worktree.branch, pending.branch)
            assertNull(h.registry.arguments)
            h.vm.onOpenLocationChosen(EvolveOpenLocation.SPLIT_RIGHT, remember = false)
            h.launched()
            assertEquals("split_right", h.registry.location())
            assertEquals(worktree.branch, h.services.evolveLauncher.sessions.value.single().branch)
        }
    }

    @Test
    fun `native evolve honors the destination remembered by the link chooser`() = runBlocking {
        Harness().use { h ->
            h.ready()
            h.vm.openIssue(IssueSummary(1, "Test issue", "https://example.invalid/issue/1"))
            await { h.vm.pendingOpen.value != null }
            assertIs<PendingOpen.OpenUrl>(h.vm.pendingOpen.value)
            h.vm.onOpenLocationChosen(EvolveOpenLocation.SPLIT_DOWN, remember = true)
            await { h.preferences["evolve_open_location"] == EvolveOpenLocation.SPLIT_DOWN.name }
            h.vm.launchEvolve(CliAgent.FLUCK_AGENT)
            h.launched()
            assertNull(h.vm.pendingOpen.value)
            assertEquals("split_down", h.registry.location())
        }
    }

    @Test
    fun `cancelling native destination selection does not launch the agent`() = runBlocking {
        Harness().use { h ->
            h.ready()
            h.vm.launchEvolve(CliAgent.FLUCK_AGENT)
            await { h.vm.pendingOpen.value != null }
            h.vm.dismissOpenDialog()
            assertNull(h.vm.pendingOpen.value)
            assertNull(h.registry.arguments)
        }
    }

    private class Harness : AutoCloseable {
        val directory = Files.createTempDirectory("evolver-location-test").toFile()
        val registry = RecordingRegistry()
        val preferences = ConcurrentHashMap<String, String>()
        private val target = LoadedPluginInfo(
            pluginId = "test.location.plugin", displayName = "Location Test", version = "1.0",
            // Prevent startup's optional GitHub list probes: this fixture has no GitHub repository.
            url = "file:///test/location-plugin.git",
        )
        private val loader = proxy(PluginLoaderDelegate::class.java) { name, _ ->
            when (name) {
                "getLoadedPlugins" -> listOf(target)
                "isPluginLoaded" -> true
                "getRunningInstanceCount" -> 1
                "getPluginsDirectory" -> directory.absolutePath
                else -> error("Unexpected loader operation: $name")
            }
        }
        private val storage = proxy(PluginStorageProvider::class.java) { name, args ->
            when (name) {
                "getString" -> preferences[args!![0] as String]
                "putString" -> { preferences[args!![0] as String] = args[1] as String; Unit }
                else -> error("Unexpected storage operation: $name")
            }
        }
        private val factory = proxy(PluginStorageFactory::class.java) { name, _ ->
            check(name == "createStorage")
            storage
        }
        private val context = proxy(PluginContext::class.java) { name, args ->
            when (name) {
                "getPluginAPI" -> loader.takeIf { args!![0] == PluginLoaderDelegate::class.java }
                "getMcpToolRegistry" -> registry
                "getPluginStorageFactory" -> factory
                else -> null
            }
        }
        val services = EvolverServices(context).also {
            it.evolveLauncher.setRepoOverride(target.pluginId, directory.absolutePath)
        }
        val vm = EvolverTabViewModel(services, EvolverTabInfo(target.pluginId, target.displayName))

        suspend fun ready() = await {
            vm.target.value != null && vm.repoPath.value == directory.absolutePath &&
                vm.agentAvailability.value[CliAgent.FLUCK_AGENT] == true
        }

        suspend fun launched() = await { services.evolveLauncher.sessions.value.size == 1 }

        override fun close() {
            vm.dispose()
            services.dispose()
            directory.deleteRecursively()
        }
    }

    private class RecordingRegistry : McpToolRegistry {
        private val definition = RegisteredMcpTool("ai.rever.boss.plugin.dynamic.fluckagent", McpToolDefinition(
            name = "fluck_launch", description = "Test native launch", readOnly = false,
            handler = McpToolHandler { McpToolResult("unused") },
        ))
        override val tools = MutableStateFlow(listOf(definition))
        override val allTools = tools
        override val disabledToolNames = MutableStateFlow(emptySet<String>())
        @Volatile var arguments: String? = null
        override fun setToolEnabled(toolName: String, enabled: Boolean) = Unit
        override suspend fun invoke(toolName: String, arguments: String): McpToolResult {
            check(toolName == "fluck_launch")
            this.arguments = arguments
            return McpToolResult("{\"tab_id\":\"native-location-tab\"}")
        }
        fun location(): String = Json.parseToJsonElement(checkNotNull(arguments)).jsonObject
            .getValue("location").jsonPrimitive.content
    }

    companion object {
        private suspend fun await(predicate: () -> Boolean) = withTimeout(15_000) {
            while (!predicate()) delay(10)
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, value: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                value(method.name, args)
            } as T
    }
}
