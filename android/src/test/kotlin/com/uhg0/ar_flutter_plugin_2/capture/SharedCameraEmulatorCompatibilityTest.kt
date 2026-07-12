package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedCameraEmulatorCompatibilityTest {
    @Test
    fun `recognizes the configured VirtualScene emulator`() {
        assertTrue(
            SharedCameraEmulatorCompatibility.isRunningOnEmulator(
                fingerprint = "generic/sdk_gphone_x86_64",
                model = "sdk_gphone_x86_64",
                hardware = "ranchu",
            ),
        )
    }

    @Test
    fun `does not relax validation for a physical device signature`() {
        assertFalse(
            SharedCameraEmulatorCompatibility.isRunningOnEmulator(
                fingerprint = "google/panther/panther:15/AP4A.250205.002/1234567:user/release-keys",
                model = "Pixel 7",
                hardware = "tensor",
            ),
        )
    }
}
