package com.uhg0.ar_flutter_plugin_2.capture

import android.os.Build

/**
 * Narrow compatibility policy for the Android emulator's ARCore VirtualScene.
 *
 * It must never relax shared-camera validation on hardware.
 */
internal object SharedCameraEmulatorCompatibility {
    fun isRunningOnEmulator(
        fingerprint: String = Build.FINGERPRINT,
        model: String = Build.MODEL,
        hardware: String = Build.HARDWARE,
    ): Boolean =
        fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            model.contains("google_sdk", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            hardware.contains("goldfish", ignoreCase = true) ||
            hardware.contains("ranchu", ignoreCase = true)
}
