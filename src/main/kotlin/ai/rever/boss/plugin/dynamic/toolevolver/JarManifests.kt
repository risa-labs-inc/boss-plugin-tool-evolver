package ai.rever.boss.plugin.dynamic.toolevolver

import java.io.File
import java.util.jar.JarFile

/**
 * Best-effort reader for `META-INF/boss-plugin/plugin.json` inside plugin jars.
 * Regex-based on purpose: keeps the plugin free of a JSON dependency and tolerates
 * minor manifest drift. All readers return null instead of throwing.
 */
object JarManifests {

    fun pluginId(jar: File): String? = field(jar, "pluginId")

    fun mainClass(jar: File): String? = field(jar, "mainClass")

    fun version(jar: File): String? = field(jar, "version")

    /** The Kotlin package holding the plugin's code, derived from its mainClass. */
    fun packagePrefix(jar: File): String? =
        mainClass(jar)?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() }

    private fun field(jar: File, name: String): String? = try {
        if (!jar.isFile) null
        else JarFile(jar).use { jf ->
            jf.getEntry("META-INF/boss-plugin/plugin.json")?.let { entry ->
                val text = jf.getInputStream(entry).bufferedReader().readText()
                Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            }
        }
    } catch (_: Exception) {
        null
    }
}
