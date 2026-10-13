package ai.rever.boss.plugin.dynamic.toolevolver

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuildJavaHomeTest {
    private val root = Files.createTempDirectory("java-home-test").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun jdk(name: String): String {
        val home = File(root, name)
        val java = File(home, "bin/java")
        java.parentFile.mkdirs()
        java.writeText("#!/bin/sh\n")
        java.setExecutable(true)
        return home.absolutePath
    }

    /** The packaged host's jlinked runtime: conf, legal, lib, release and no bin/. */
    private fun jlinkedRuntime(): String = File(root, "runtime").apply { File(this, "lib").mkdirs() }.absolutePath

    @Test
    fun `the packaged host runtime without bin java is skipped for the system JDK`() {
        val system = jdk("system")
        assertEquals(system, buildJavaHome(null, jlinkedRuntime()) { system })
    }

    @Test
    fun `an inherited JAVA_HOME with a launcher wins`() {
        val inherited = jdk("inherited")
        assertEquals(inherited, buildJavaHome(inherited, jdk("host")) { jdk("system") })
    }

    @Test
    fun `an inherited JAVA_HOME without a launcher falls through`() {
        val host = jdk("host")
        assertEquals(host, buildJavaHome(jlinkedRuntime(), host) { null })
    }

    @Test
    fun `a blank inherited JAVA_HOME falls through`() {
        val host = jdk("host")
        assertEquals(host, buildJavaHome("", host) { null })
    }

    @Test
    fun `no usable home leaves JAVA_HOME unset`() {
        assertNull(buildJavaHome(null, jlinkedRuntime()) { null })
    }
}
