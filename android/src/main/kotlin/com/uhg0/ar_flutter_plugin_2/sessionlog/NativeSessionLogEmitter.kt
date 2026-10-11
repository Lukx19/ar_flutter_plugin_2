package com.uhg0.ar_flutter_plugin_2.sessionlog

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.FileProvider
import com.uhg0.ar_flutter_plugin_2.BuildConfig
import com.uhg0.ar_flutter_plugin_2.capture.PoseDataExtractor
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * Native side of the app's per-session audit log
 * (`ar_flutter_plugin_2/session_log`). Native producers call [record] (or
 * [occur] then [record] with `admitted` on hot paths); the app drains the
 * records every 2 s and on pause and close. Every reply is a map with a
 * `status`, never null.
 *
 * Methods: `identity` (ARCore version), `setActive` (records are kept only
 * while a session log is open), `drain`, `share` (export through the share
 * sheet), and in debug builds `debugThrow` (tests the handler wrapper).
 */
object NativeSessionLogEmitter {
    const val CHANNEL = "ar_flutter_plugin_2/session_log"
    private const val TAG = "NativeSessionLog"

    val buffer = NativeSessionLogBuffer()

    @Volatile
    var active: Boolean = false
        private set

    private var context: Context? = null
    private var memoryCallbacks: ComponentCallbacks2? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    fun occur(code: String, variant: String? = null): Boolean = active && buffer.occur(code, variant)

    fun record(
        code: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        variant: String? = null,
        level: String = NativeSessionLogBuffer.LEVEL_WARNING,
        admitted: Boolean = false,
    ) {
        if (!active) return
        buffer.record(code, message, data, variant, level, admitted)
    }

    /** A shutter refused by the exposure motion gate, with its scalar diagnostics. */
    internal fun recordMotionRefusal(attemptId: String, d: PoseDataExtractor.ExposureMotionDiagnostic) {
        if (!active || d.gate == PoseDataExtractor.ExposureMotionGate.APPROVED) return
        val code = "CAPTURE_REFUSED_${d.gate.name}"
        if (!buffer.occur(code)) return
        val refusing = d.firstRefusingPair
        buffer.record(
            code,
            "Shutter refused by the motion gate: ${d.gate.name}",
            mapOf(
                "stage" to "native_shutter",
                "attemptId" to attemptId,
                "gate" to d.gate.name,
                "ageMs" to d.ageMs,
                "dwellMs" to d.stableDwellMs,
                "requiredDwellMs" to 400,
                "historyMs" to d.historySpanMs,
                "pairs" to d.pairCount,
                "latestMps" to d.latestTranslationSpeedMps,
                "latestDegps" to d.latestAngularSpeedDegreesPerSecond,
                "medianMps" to d.medianTranslationSpeedMps,
                "medianDegps" to d.medianAngularSpeedDegreesPerSecond,
                "minPositiveCameraGapNs" to d.minimumPositiveCameraGapNs,
                "duplicatePairs" to d.duplicatePairCount,
                "timestampReadMismatches" to d.timestampReadMismatchCount,
                "refusingPairGapMs" to refusing?.let { it.cameraGapNs / 1e6 },
                "refusingPairTracking" to refusing?.newer?.trackingState,
            ),
            admitted = true,
        )
    }

    /**
     * Runs a method channel handler body; an exception becomes a
     * `NATIVE_HANDLER_ERROR` record and an error reply instead of a crash or
     * a null reply.
     */
    inline fun guard(channel: String, call: MethodCall, result: MethodChannel.Result, body: () -> Unit) {
        try {
            body()
        } catch (e: Exception) {
            val variant = "$channel/${call.method}/${e.javaClass.simpleName}"
            if (occur("NATIVE_HANDLER_ERROR", variant)) {
                record(
                    "NATIVE_HANDLER_ERROR",
                    "Native handler failed: ${e.javaClass.simpleName}",
                    mapOf("channel" to channel, "method" to call.method, "error" to (e.message ?: "")),
                    variant = variant,
                    admitted = true,
                )
            }
            result.error("NATIVE_HANDLER_ERROR", e.message, null)
        }
    }

    fun register(messenger: BinaryMessenger, appContext: Context): MethodChannel {
        context = appContext
        registerSystemListeners(appContext)
        return MethodChannel(messenger, CHANNEL).apply {
            setMethodCallHandler { call, result ->
                guard(CHANNEL, call, result) {
                    when (call.method) {
                        "identity" -> result.success(
                            mapOf("status" to "ok", "arVersion" to arCoreVersion(appContext)),
                        )
                        "setActive" -> {
                            val on = call.argument<Boolean>("active") == true
                            if (on != active) buffer.reset()
                            active = on
                            result.success(mapOf("status" to "ok"))
                        }
                        "drain" -> result.success(mapOf("status" to "ok", "records" to buffer.drain()))
                        // Debug-only seam for the emulator test of the handler wrapper.
                        "debugThrow" -> if (BuildConfig.DEBUG) {
                            throw IllegalStateException("forced handler failure")
                        } else {
                            result.success(mapOf("status" to "notImplemented"))
                        }
                        "share" -> {
                            val paths = call.argument<List<String>>("paths").orEmpty()
                            result.success(mapOf("status" to if (share(appContext, paths)) "ok" else "unavailable"))
                        }
                        else -> result.success(mapOf("status" to "notImplemented"))
                    }
                }
            }
        }
    }

    fun unregister(channel: MethodChannel?) {
        channel?.setMethodCallHandler(null)
        val ctx = context ?: return
        memoryCallbacks?.let(ctx::unregisterComponentCallbacks)
        memoryCallbacks = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.removeThermalStatusListener(it) }
        }
        thermalListener = null
        active = false
        context = null
    }

    private fun registerSystemListeners(ctx: Context) {
        if (memoryCallbacks == null) {
            memoryCallbacks = object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
                        level != ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) return
                    val runtime = Runtime.getRuntime()
                    record(
                        "MEMORY_PRESSURE",
                        "System memory pressure (trim level $level)",
                        mapOf(
                            "trimLevel" to level,
                            "javaHeapUsedMb" to (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
                        ),
                        variant = "trim$level",
                    )
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {
                    record("MEMORY_PRESSURE", "System low memory", mapOf("trimLevel" to "lowMemory"), variant = "low")
                }
            }.also(ctx::registerComponentCallbacks)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener == null) {
            val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
                    record(
                        "THERMAL_THROTTLING",
                        "Thermal status $status",
                        mapOf("thermalStatus" to status),
                        variant = "status$status",
                    )
                }
            }.also(power::addThermalStatusListener)
        }
    }

    private fun arCoreVersion(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo("com.google.ar.core", 0).versionName ?: "unavailable"
    } catch (e: Exception) {
        "unavailable"
    }

    /** Shares exported files (under the cache folder `session_log_export`). */
    private fun share(ctx: Context, paths: List<String>): Boolean {
        val exportRoot = File(ctx.cacheDir, "session_log_export").canonicalPath
        val uris = ArrayList<android.net.Uri>()
        for (path in paths) {
            val file = File(path)
            if (!file.canonicalPath.startsWith(exportRoot) || !file.isFile) return false
            uris.add(FileProvider.getUriForFile(ctx, "${ctx.packageName}.sessionlog.fileprovider", file))
        }
        if (uris.isEmpty()) return false
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            ctx.startActivity(
                Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "share failed: ${e.message}")
            false
        }
    }
}
