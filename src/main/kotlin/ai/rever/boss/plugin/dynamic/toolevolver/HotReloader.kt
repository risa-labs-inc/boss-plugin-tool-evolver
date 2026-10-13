package ai.rever.boss.plugin.dynamic.toolevolver

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Installs a freshly built plugin jar into the RUNNING host's plugins directory
 * and live-reloads it — no app restart. The plugins directory comes from the
 * host (`PluginLoaderDelegate.getPluginsDirectory()`), so an installed host uses
 * `~/.boss/plugins` and a dev-mode host uses `~/.boss_debug/plugins` automatically.
 */
class HotReloader(private val services: EvolverServices) {

    /**
     * Hot-reload a plugin. With [jarPath]: copy that jar into the live plugins
     * dir (replacing older jars of the same plugin) and load it. Without it:
     * plain in-place reload of [pluginId] from its installed jar.
     */
    suspend fun hotReload(pluginId: String?, jarPath: String?): Result<String> {
        val loader = services.loader
            ?: return Result.failure(IllegalStateException("PluginLoaderDelegate unavailable — host too old?"))
        return runCatching {
            when {
                !jarPath.isNullOrBlank() -> {
                    val src = File(jarPath)
                    require(src.isFile) { "Jar not found: $jarPath" }
                    val manifestId = JarManifests.pluginId(src)
                        ?: pluginId
                        ?: error("Cannot determine pluginId from ${src.name}; pass plugin_id explicitly")
                    if (!pluginId.isNullOrBlank() && pluginId != manifestId) {
                        error("Jar belongs to $manifestId, not $pluginId")
                    }
                    val pluginsDir = File(loader.getPluginsDirectory()).apply { mkdirs() }
                    if (loader.isPluginLoaded(manifestId)) {
                        loader.unloadPlugin(manifestId)
                    }
                    // Drop older jars of the same plugin so the host reconciler
                    // can't resurrect a stale version next launch.
                    pluginsDir.listFiles { f -> f.isFile && f.extension == "jar" && f.name != src.name }
                        ?.filter { JarManifests.pluginId(it) == manifestId }
                        ?.forEach { it.delete() }
                    val dest = File(pluginsDir, src.name)
                    src.copyTo(dest, overwrite = true)
                    val loaded = loader.loadPlugin(dest.absolutePath)
                        ?: error("Host failed to load ${dest.name} — check Console logs")
                    "Hot-reloaded ${loaded.displayName} ${loaded.version} into ${pluginsDir.absolutePath}"
                }
                !pluginId.isNullOrBlank() -> {
                    val loaded = loader.reloadPlugin(pluginId)
                        ?: error("Reload failed for $pluginId — is it loaded?")
                    "Reloaded ${loaded.displayName} ${loaded.version} in place"
                }
                else -> error("Provide plugin_id and/or jar_path")
            }
        }
    }

    /**
     * Run `./gradlew buildPluginJar` in [repoDir], then hot-reload the newest
     * built jar. Streams build output lines to [onLine].
     */
    suspend fun rebuildAndReload(
        repoDir: File,
        pluginId: String,
        onLine: (String) -> Unit,
    ): Result<String> = runCatching {
        val gradlew = File(repoDir, "gradlew")
        require(gradlew.isFile) { "No gradlew in ${repoDir.absolutePath}" }
        onLine("$ ./gradlew buildPluginJar")
        val process = ProcessBuilder("./gradlew", "buildPluginJar")
            .directory(repoDir)
            .redirectErrorStream(true)
            .also { pb ->
                val env = pb.environment()
                // The packaged host launches with a bare PATH and no JAVA_HOME;
                // gradlew needs both to find a JVM and common tooling. The host's
                // own java.home is a jlinked runtime with no bin/java in the
                // packaged app, and gradlew rejects it ("JAVA_HOME is set to an
                // invalid directory"), so only a home with a launcher is passed.
                val javaHome = buildJavaHome(env["JAVA_HOME"])
                if (javaHome != null) env["JAVA_HOME"] = javaHome else env.remove("JAVA_HOME")
                val home = System.getProperty("user.home")
                val extras = listOf("$home/.local/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
                env["PATH"] = (extras + (env["PATH"] ?: "")).joinToString(File.pathSeparator)
            }
            .start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            error("Build timed out after 15 minutes")
        }
        if (process.exitValue() != 0) error("Build failed (exit ${process.exitValue()})")
        val jar = File(repoDir, "build/libs").listFiles { f ->
            f.isFile && f.extension == "jar" &&
                !f.name.endsWith("-thin.jar") && !f.name.endsWith("-all.jar")
        }?.maxByOrNull { it.lastModified() }
            ?: error("No plugin jar found in ${repoDir.absolutePath}/build/libs")
        onLine("Built ${jar.name}")
        hotReload(pluginId, jar.absolutePath).getOrThrow()
    }
}

/**
 * The JDK home a plugin build should run on: the caller's JAVA_HOME, else the host's
 * java.home, else macOS's `/usr/libexec/java_home` - the first that has a `bin/java`.
 * Null means none does, and gradlew should find `java` on the PATH itself.
 *
 * The JDK 17+ floor applies only to the discovered macOS JDK. An inherited JAVA_HOME
 * or the host's java.home is trusted as the user's choice and checked for a launcher
 * alone. [systemJavaHome] runs only when neither of those is usable.
 */
internal fun buildJavaHome(
    inherited: String?,
    hostJavaHome: String? = System.getProperty("java.home"),
    systemJavaHome: () -> String? = ::macSystemJavaHome,
): String? {
    fun usable(dir: String?) = dir?.takeIf { it.isNotBlank() && hasJavaLauncher(File(it)) }
    return usable(inherited) ?: usable(hostJavaHome) ?: usable(systemJavaHome())
}

private fun hasJavaLauncher(home: File): Boolean = File(home, "bin/java").canExecute()

// Wait before reading: reading first blocks until the child closes stdout, so a hung
// java_home would never reach the timeout. Its output is one short line, well under
// the pipe buffer. `-v 17+`: Gradle's daemon will not start on anything older.
// stderr is discarded so a warning can never be read as part of the path.
private fun macSystemJavaHome(): String? = runCatching {
    val tool = File("/usr/libexec/java_home")
    if (!tool.canExecute()) return null
    val process = ProcessBuilder(tool.path, "-v", "17+")
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    val out = process.inputStream.bufferedReader().readText().trim()
    if (process.exitValue() == 0) out else null
}.getOrNull()
