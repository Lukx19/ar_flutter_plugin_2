package com.uhg0.ar_flutter_plugin_2.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log

/** Capture feedback uses a bounded motor pulse, rather than the often imperceptible keyboard tap. */
object CaptureHapticFeedback {
    /** Shared coordinator calls once after a live, quality-qualified photo is retained. */
    fun captured(context: Context, stillOwned: () -> Boolean = { true }, completed: () -> Unit = {}) =
        request(context, CaptureHapticPattern.CAPTURED, stillOwned, completed)

    /** UI-owned, once per failed manual request. Automatic refusals must never call this. */
    fun rejected(context: Context, stillOwned: () -> Boolean = { true }, completed: () -> Unit = {}) =
        request(context, CaptureHapticPattern.REJECTED, stillOwned, completed)

    private fun request(context: Context, pattern: CaptureHapticPattern,
                        stillOwned: () -> Boolean, completed: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            var reason = "inactive"
            try {
                val activity = activity(context)
                val view = activity?.window?.decorView
                val enabled = Settings.System.getInt(context.contentResolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
                val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                }
                val allowed = CaptureHapticPolicy.allowed(
                    owned = stillOwned(),
                    foreground = activity != null && !activity.isFinishing && !activity.isDestroyed &&
                        view?.isAttachedToWindow == true && view.hasWindowFocus(),
                    viewEnabled = view?.isHapticFeedbackEnabled == true,
                    systemEnabled = enabled,
                    hasVibrator = vibrator?.hasVibrator() == true,
                )
                if (allowed && vibrator != null) {
                    val timing = pattern.timings()
                    if (Build.VERSION.SDK_INT >= 26) {
                        val effect = VibrationEffect.createWaveform(timing, -1)
                        if (Build.VERSION.SDK_INT >= 33) {
                            vibrator.vibrate(effect, VibrationAttributes.Builder()
                                .setUsage(VibrationAttributes.USAGE_TOUCH).build())
                        } else {
                            vibrator.vibrate(effect, audioAttributes())
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(timing, -1, audioAttributes())
                    }
                    reason = "motor-requested"
                }
            } catch (_: SecurityException) {
                reason = "permission-unavailable"
            } catch (_: IllegalStateException) {
                reason = "service-unavailable"
            }
            // The Dart coordinator keeps capture admission blocked until this
            // reply, so a rejection pulse cannot shake the next exposure.
            if (reason == "motor-requested") handler.postDelayed(completed, pattern.exclusionMs())
            else completed()
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                // The API receipt establishes a motor request, not subjective vibration strength.
                Log.i("CaptureShutter", "haptic pattern=${pattern.name} reason=$reason")
            }
        }
    }

    private fun audioAttributes() = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()

    private fun activity(context: Context): Activity? {
        var current = context
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
        while (seen.add(current)) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }
}

internal enum class CaptureHapticPattern {
    CAPTURED, REJECTED;
    // Waveform starts with a silent delay. One 45 ms pulse confirms a shot;
    // two separated 45 ms pulses distinguish a failed manual request.
    fun exclusionMs(): Long = when (this) {
        CAPTURED -> 70L
        REJECTED -> 190L
    }
    fun timings(): LongArray = when (this) {
        CAPTURED -> longArrayOf(0, 45)
        REJECTED -> longArrayOf(0, 45, 70, 45)
    }
}

internal object CaptureHapticPolicy {
    fun allowed(owned: Boolean, foreground: Boolean, viewEnabled: Boolean,
                systemEnabled: Boolean, hasVibrator: Boolean): Boolean =
        owned && foreground && viewEnabled && systemEnabled && hasVibrator
}
