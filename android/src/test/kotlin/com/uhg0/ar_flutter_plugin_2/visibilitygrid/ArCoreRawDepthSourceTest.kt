package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.google.ar.core.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ArCoreRawDepthSourceTest {
    @Test
    fun `automatic and raw depth modes use the raw depth source contract`() {
        assertNotNull(ArCoreRawDepthSource(depthMode = Config.DepthMode.AUTOMATIC))
        assertNotNull(ArCoreRawDepthSource(depthMode = Config.DepthMode.RAW_DEPTH_ONLY))
    }

    @Test
    fun `disabled depth mode is rejected before frame acquisition`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArCoreRawDepthSource(depthMode = Config.DepthMode.DISABLED)
        }
    }
    @Test
    fun `automatic raw candidate routes acquired planes and dense default stays single image`() {
        for (enabled in listOf(false, true)) {
            val calls = ArrayList<String>()
            val mode = depthAcquisitionModeForSession(Config.DepthMode.AUTOMATIC, enabled)
            val depth = OwnedPlane(845720289472481L, 1_000)
            val confidence = OwnedPlane(7, 255)
            val acquirer = selectedDepthAcquirer(mode,
                acquireDense = { calls += "dense16"; depth },
                acquireRaw = { calls += "raw16"; depth },
                acquireRawConfidence = { calls += "confidence"; confidence })
            val result = prepareRawDepthFrame(acquirer, { _, _ ->
                RawDepthFrameMetadata(timestampNs = 845720290140319L, groupGeneration = 1, sessionGeneration = 1,
                    tracking = true, width = 4, height = 3, intrinsics = DepthIntrinsics(2.0, 2.0, 1.5, 1.0),
                    worldFromCameraGl = identityVisibilityGridTransform(), cameraImageTimestampNs = 845720257661069L,
                    acquisitionMode = mode, acquisitionFrameBeforeNs = 845720290140319L,
                    acquisitionAndroidCameraBeforeNs = 845720257661069L)
            })
            if (enabled) {
                assertEquals(listOf("raw16", "confidence"), calls)
                val prepared = (result as PreparedRawDepthResult.Ready).frame
                assertEquals(DepthAcquisitionMode.RAW, prepared.metadata.acquisitionMode)
                val copied = prepared.process() as DepthAcquisitionResult.Observation
                assertEquals(12, copied.value.samples.size)
                assertEquals(845720290140319L, copied.value.projectionFrameTimestampNs)
                assertEquals(1, confidence.closes)
            } else {
                assertEquals(listOf("dense16"), calls)
                assertTrue(result is PreparedRawDepthResult.Refused)
                assertEquals(0, confidence.closes)
            }
            assertEquals(1, depth.closes)
        }
    }

    private class OwnedPlane(override val timestampNs: Long, private val value: Int) : RawDepthImage {
        override val width = 4
        override val height = 3
        var closes = 0
        override fun unsignedValue(x: Int, y: Int) = value
        override fun close() { closes++ }
    }

}
