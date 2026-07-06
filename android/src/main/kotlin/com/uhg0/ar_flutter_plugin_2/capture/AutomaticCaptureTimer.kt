package com.uhg0.ar_flutter_plugin_2.capture

import android.os.Handler
import android.util.Log

/// Timer for automatic capture functionality
class AutomaticCaptureTimer(
    private val intervalMs: Int,
    private val handler: Handler?,
    private val captureCallback: () -> Unit
) {
    private var isRunning = false
    private var captureRunnable: Runnable? = null

    /// Start the automatic capture timer
    fun start() {
        if (isRunning) {
            Log.w("AutomaticCaptureTimer", "Timer already running")
            return
        }

        if (intervalMs <= 0) {
            Log.w("AutomaticCaptureTimer", "Invalid interval: $intervalMs ms")
            return
        }

        isRunning = true
        scheduleNextCapture()
        Log.i("AutomaticCaptureTimer", "Started automatic capture timer with interval: ${intervalMs}ms")
    }

    /// Stop the automatic capture timer
    fun stop() {
        if (!isRunning) {
            return
        }

        isRunning = false
        captureRunnable?.let { runnable ->
            handler?.removeCallbacks(runnable)
        }
        captureRunnable = null
        Log.i("AutomaticCaptureTimer", "Stopped automatic capture timer")
    }

    /// Check if timer is currently running
    fun isRunning(): Boolean = isRunning

    /// Get current interval in milliseconds
    fun getIntervalMs(): Int = intervalMs

    /// Schedule the next capture
    private fun scheduleNextCapture() {
        if (!isRunning) return

        captureRunnable = Runnable {
            if (isRunning) {
                try {
                    captureCallback()
                    Log.d("AutomaticCaptureTimer", "Automatic capture triggered")
                } catch (e: Exception) {
                    Log.e("AutomaticCaptureTimer", "Error during automatic capture", e)
                }
                
                // Schedule next capture
                scheduleNextCapture()
            }
        }

        handler?.postDelayed(captureRunnable!!, intervalMs.toLong())
    }
}