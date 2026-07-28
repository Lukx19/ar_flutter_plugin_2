package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.google.ar.core.Config
import org.junit.Assert.assertEquals
import org.junit.Test

class ArCoreDepthModeControllerTest {
    @Test
    fun `failed raw probe falls back to automatic before feature-only`() {
        val controller =
            ArCoreDepthModeController(
                rawDepthSupported = true,
                automaticDepthSupported = true,
            )

        assertEquals(Config.DepthMode.RAW_DEPTH_ONLY, controller.activeMode)
        assertEquals(
            Config.DepthMode.AUTOMATIC,
            controller.recordProbe(DepthAcquisitionResult.Failure("raw probe failed")),
        )
        repeat(2) {
            assertEquals(
                Config.DepthMode.AUTOMATIC,
                controller.recordProbe(DepthAcquisitionResult.Failure("automatic failed")),
            )
        }
        assertEquals(
            Config.DepthMode.DISABLED,
            controller.recordProbe(DepthAcquisitionResult.Failure("automatic failed")),
        )
    }

    @Test
    fun `transient probe does not change mode or count as terminal`() {
        val controller =
            ArCoreDepthModeController(
                rawDepthSupported = true,
                automaticDepthSupported = false,
            )

        repeat(5) {
            assertEquals(
                Config.DepthMode.RAW_DEPTH_ONLY,
                controller.recordProbe(DepthAcquisitionResult.TransientUnavailable),
            )
        }
        assertEquals(
            Config.DepthMode.DISABLED,
            controller.recordProbe(DepthAcquisitionResult.Failure("terminal")),
        )
    }
}
