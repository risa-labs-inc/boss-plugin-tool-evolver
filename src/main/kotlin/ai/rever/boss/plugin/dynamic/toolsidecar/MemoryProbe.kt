package ai.rever.boss.plugin.dynamic.toolsidecar

import ai.rever.boss.plugin.api.LoadedPluginInfo
import java.io.File
import java.lang.management.ManagementFactory
import javax.management.ObjectName

/**
 * Per-plugin memory probe for IN-PROCESS plugins.
 *
 * The host offers no per-plugin heap attribution for in-process plugins (they
 * share the host JVM heap), so this samples the JVM's live-object class
 * histogram via the in-process DiagnosticCommand MBean (the same data as
 * `jcmd GC.class_histogram`) and attributes instances to a plugin by its code
 * package prefix. Sampling forces a full GC — it is on-demand, never periodic.
 */
class MemoryProbe {

    data class ClassStat(
        val className: String,
        val instances: Long,
        val bytes: Long,
    )

    data class MemorySnapshot(
        val atMs: Long,
        val packagePrefix: String,
        val classCount: Int,
        val instanceCount: Long,
        val totalBytes: Long,
        val topClasses: List<ClassStat>,
    )

    /** Sample live objects whose class lives under [packagePrefix]. Null if the JVM refuses the probe. */
    fun snapshot(packagePrefix: String, top: Int = 12): MemorySnapshot? {
        val histogram = classHistogram() ?: return null
        var classes = 0
        var instances = 0L
        var bytes = 0L
        val stats = ArrayList<ClassStat>()
        histogram.lineSequence().forEach { line ->
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.size < 4 || !tokens[0].endsWith(":")) return@forEach
            val inst = tokens[1].toLongOrNull() ?: return@forEach
            val b = tokens[2].toLongOrNull() ?: return@forEach
            val name = tokens[3]
            if (!name.startsWith(packagePrefix)) return@forEach
            classes++
            instances += inst
            bytes += b
            stats += ClassStat(name, inst, b)
        }
        stats.sortByDescending { it.bytes }
        return MemorySnapshot(
            atMs = System.currentTimeMillis(),
            packagePrefix = packagePrefix,
            classCount = classes,
            instanceCount = instances,
            totalBytes = bytes,
            topClasses = stats.take(top),
        )
    }

    /**
     * The package prefix used to attribute heap objects to [info]: the package of
     * the plugin's mainClass (read from its jar manifest), falling back to the
     * pluginId, which by house convention mirrors the code package.
     */
    fun packagePrefixFor(info: LoadedPluginInfo): String =
        JarManifests.packagePrefix(File(info.jarPath)) ?: info.pluginId

    /** Full live-object class histogram, or null when the MBean is unavailable. */
    fun classHistogram(): String? = try {
        val server = ManagementFactory.getPlatformMBeanServer()
        val name = ObjectName("com.sun.management:type=DiagnosticCommand")
        server.invoke(
            name,
            "gcClassHistogram",
            arrayOf<Any?>(null),
            arrayOf("[Ljava.lang.String;"),
        ) as? String
    } catch (_: Exception) {
        null
    }

    companion object {
        fun humanBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.2f GiB".format(bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> "%.2f MiB".format(bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> "%.1f KiB".format(bytes.toDouble() / (1L shl 10))
            else -> "$bytes B"
        }

        /**
         * Leak heuristics over a snapshot history plus host state. Returns
         * human-readable warnings; empty means nothing suspicious observed.
         */
        fun leakSignals(
            history: List<MemorySnapshot>,
            isLoaded: Boolean,
            runningInstances: Int,
        ): List<String> {
            val signals = mutableListOf<String>()
            if (!isLoaded && (history.lastOrNull()?.instanceCount ?: 0L) > 0L) {
                signals += "Plugin is unloaded but ${history.last().instanceCount} of its objects remain on the heap — likely leaked classloader."
            }
            if (runningInstances > 1) {
                signals += "$runningInstances live instances open — stale instances can pin the old version after a reload."
            }
            if (history.size >= 3) {
                val monotonic = history.zipWithNext().all { (a, b) -> b.totalBytes >= a.totalBytes }
                val first = history.first().totalBytes
                val last = history.last().totalBytes
                if (monotonic && first > 0 && last > first * 3 / 2) {
                    signals += "Heap use grew every sample (${humanBytes(first)} → ${humanBytes(last)} across ${history.size} samples) — possible leak."
                }
            }
            return signals
        }
    }
}
