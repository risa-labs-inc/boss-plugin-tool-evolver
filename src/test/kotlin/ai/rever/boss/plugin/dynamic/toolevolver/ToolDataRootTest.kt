package ai.rever.boss.plugin.dynamic.toolevolver

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolDataRootTest {
    @Test
    fun `default clone workspace is contained beneath boss`() {
        val home = Files.createTempDirectory("tool-evolver-home")

        val workspace = EvolveLauncher.defaultCloneParent(home.toString()).toPath()

        val expectedRoot = home.resolve(".boss").toFile().canonicalFile.toPath()
        assertEquals(expectedRoot.resolve("workspaces/tools"), workspace)
        assertTrue(workspace.startsWith(expectedRoot))
        assertTrue(Files.isDirectory(workspace))
    }
}
