package com.uhg0.ar_flutter_plugin_2.sceneview

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.lang.ref.WeakReference

/**
 * Census (debuggable builds: debug and profile) of disposed scene hosts still
 * reachable. A disposed host must become collectable once its terminal
 * composition disposal ran; the capture view retention integration check
 * reads this after N view rebuilds. Release builds record nothing.
 */
internal class SceneViewHostRetentionCensus(
    private val collect: () -> Unit = {
        System.gc()
        System.runFinalization()
    },
) {
    private val disposed = mutableListOf<WeakReference<Any>>()
    private var disposedTotal = 0L

    @Synchronized
    fun recordDisposed(host: Any) {
        disposed.removeAll { it.get() == null }
        disposed += WeakReference(host)
        disposedTotal++
    }

    /** Collects two to [rounds] times and reports the disposed hosts still reachable. */
    fun retainedAfterCollection(rounds: Int = 5, pauseMs: Long = 100): Map<String, Long> {
        repeat(rounds) { round ->
            // Two collections always run so the heap figure excludes garbage.
            if (round >= 2 && retained() == 0L) return@repeat
            collect()
            Thread.sleep(pauseMs)
        }
        val runtime = Runtime.getRuntime()
        return synchronized(this) {
            disposed.removeAll { it.get() == null }
            mapOf(
                "disposedHosts" to disposedTotal,
                "retainedDisposedHosts" to disposed.size.toLong(),
                "javaHeapUsedBytes" to runtime.totalMemory() - runtime.freeMemory(),
                "javaHeapMaxBytes" to runtime.maxMemory(),
            )
        }
    }

    @Synchronized
    private fun retained(): Long = disposed.count { it.get() != null }.toLong()

    companion object {
        const val CHANNEL = "ar_flutter_plugin_2/scene_view_retention"

        @Volatile
        var active: SceneViewHostRetentionCensus? = null
            private set

        /** Debuggable (debug, profile) builds only; release builds register and record nothing. */
        fun register(messenger: BinaryMessenger, isDebuggable: Boolean): MethodChannel? {
            if (!isDebuggable) return null
            val census = active ?: SceneViewHostRetentionCensus().also { active = it }
            val main = Handler(Looper.getMainLooper())
            return MethodChannel(messenger, CHANNEL).apply {
                setMethodCallHandler { call, result ->
                    if (call.method != "retainedDisposedHosts") {
                        result.notImplemented()
                        return@setMethodCallHandler
                    }
                    Thread({
                        val receipt = runCatching { census.retainedAfterCollection() }
                        main.post {
                            receipt.fold(result::success) { error ->
                                result.error("SCENE_VIEW_RETENTION_FAILED", error.message, null)
                            }
                        }
                    }, "scene-view-retention-census").apply { isDaemon = true }.start()
                }
            }
        }

        fun unregister(channel: MethodChannel?) {
            channel?.setMethodCallHandler(null)
        }
    }
}
