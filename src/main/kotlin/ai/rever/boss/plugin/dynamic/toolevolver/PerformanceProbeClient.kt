package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.PluginContext

/**
 * Client for the Performance plugin's cross-plugin `PluginMemoryProbeAPI`
 * (api ≥ 1.0.64). When available, the evolver probes plugin memory through the
 * Performance plugin instead of its own [MemoryProbe], so both surfaces share
 * one sample history — leak heuristics then see samples taken from either.
 *
 * Deliberately reflection-only: the host's binary-compatibility validator
 * pre-scans the whole jar against the runtime api layer and disables the
 * plugin on any unresolved reference, so the interface must not be referenced
 * statically while older api layers are in circulation. Null everywhere means
 * fall back to the local [MemoryProbe].
 */
class PerformanceProbeClient(private val context: PluginContext) {

    private val api: Any?
        get() = try {
            @Suppress("UNCHECKED_CAST")
            val iface = Class.forName(
                "ai.rever.boss.plugin.api.PluginMemoryProbeAPI",
                false,
                PerformanceProbeClient::class.java.classLoader,
            ) as Class<Any>
            context.getPluginAPI(iface)
        } catch (_: Throwable) {
            null
        }

    /** Whether the Performance plugin's probe is registered on this host. */
    val available: Boolean get() = api != null

    /**
     * Sample [pluginId] through the Performance plugin (records into the shared
     * history). Null when the API is absent or the JVM refuses the histogram.
     */
    fun sample(pluginId: String, top: Int = 12): MemoryProbe.MemorySnapshot? {
        val a = api ?: return null
        return try {
            a.javaClass
                .getMethod("sampleMemory", String::class.java, Int::class.javaPrimitiveType)
                .invoke(a, pluginId, top)
                ?.let(::toSnapshot)
        } catch (_: Throwable) {
            null
        }
    }

    /** The shared sample history for [pluginId], oldest first; null when unavailable. */
    fun history(pluginId: String): List<MemoryProbe.MemorySnapshot>? {
        val a = api ?: return null
        return try {
            (a.javaClass.getMethod("sampleHistory", String::class.java).invoke(a, pluginId) as List<*>)
                .mapNotNull { it?.let(::toSnapshot) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun toSnapshot(sample: Any): MemoryProbe.MemorySnapshot {
        fun prop(name: String): Any? = sample.javaClass.getMethod(name).invoke(sample)
        val topClasses = (prop("getTopClasses") as? List<*>).orEmpty().mapNotNull { stat ->
            stat?.let {
                MemoryProbe.ClassStat(
                    className = it.javaClass.getMethod("getClassName").invoke(it) as String,
                    instances = it.javaClass.getMethod("getInstances").invoke(it) as Long,
                    bytes = it.javaClass.getMethod("getBytes").invoke(it) as Long,
                )
            }
        }
        return MemoryProbe.MemorySnapshot(
            atMs = prop("getAtMs") as Long,
            packagePrefix = prop("getPackagePrefix") as String,
            classCount = prop("getClassCount") as Int,
            instanceCount = prop("getInstanceCount") as Long,
            totalBytes = prop("getTotalBytes") as Long,
            topClasses = topClasses,
        )
    }
}
