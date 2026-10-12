package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class PlaybackCameraImageExposureV2Test {
    private val cut = CaptureLifecycleCut("session", 1, "group", 1, "ar", "view", 1, "binding", 1, 1)
    private val qualifier = CaptureAttemptQualifierV2(CaptureAttemptIdentity("attempt", "commit", 1, cut), cut, 1)

    private class Callback(override val retainAssessedOriginals: Boolean = false) : SharedCameraExposureCallbackV2 {
        val components = mutableListOf<SharedCameraComponentSetV2>()
        val failures = mutableListOf<String>()
        override fun onComponents(components: SharedCameraComponentSetV2) { this.components += components }
        override fun onFailure(qualifier: CaptureAttemptQualifierV2, reason: String) { failures += reason }
    }

    private val queued = mutableListOf<Runnable>()
    private val queue = Executor { queued += it }
    private val jpeg = byteArrayOf(-1, -40, 7, 7, -1, -39)
    private val image = PlaybackCameraImage(ByteArray(640 * 480 * 3 / 2), 640, 480, timestampNs = 42L)

    private fun exposure(
        refusal: String? = null,
        score: Double = 500.0,
        executor: Executor = queue,
        encoded: MutableList<PlaybackCameraImage> = mutableListOf(),
    ) = PlaybackCameraImageExposureV2(
        refusal = { refusal },
        executor = executor,
        encodeJpeg = { encoded += it; jpeg },
        analyzeJpeg = { _, _ -> PreviewBlurQuality(score, 160, 120, "jpegLaplacianVarianceV1") },
        maximumWaitFrames = 3,
    )

    @Test fun nextFrameImageBecomesTheJpegComponentWithItsTimestamp() {
        val encoded = mutableListOf<PlaybackCameraImage>()
        val port = exposure(encoded = encoded)
        val callback = Callback()
        assertTrue(port.requestExposure(qualifier, setOf(CaptureComponentKind.JPEG), callback))
        assertTrue(port.hasPendingExposure)
        port.onFrame { image }
        assertFalse("the frame thread only copies; dispatch is queued", port.hasPendingExposure)
        assertTrue(callback.components.isEmpty())
        queued.single().run()
        assertEquals(listOf(image), encoded)
        val set = callback.components.single()
        assertEquals(qualifier, set.qualifier)
        assertEquals(42L, set.exposureTimestampNanoseconds)
        assertEquals(CaptureComponentKind.JPEG, set.streams.single().kind)
        assertArrayEquals(jpeg, set.streams.single().input.readBytes())
        assertTrue(callback.failures.isEmpty())
    }

    @Test fun onlyOneExposureAtATimeAndNoDng() {
        val port = exposure()
        val callback = Callback()
        assertFalse(port.requestExposure(qualifier, setOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG), callback))
        assertTrue(port.requestExposure(qualifier, setOf(CaptureComponentKind.JPEG), callback))
        assertFalse(port.requestExposure(qualifier, setOf(CaptureComponentKind.JPEG), Callback()))
    }

    @Test fun refusalFailsTheAttemptWithoutPending() {
        val port = exposure(refusal = "motion-skipped")
        val callback = Callback()
        assertFalse(port.requestExposure(qualifier, setOf(CaptureComponentKind.JPEG), callback))
        assertEquals(listOf("motion-skipped"), callback.failures)
        assertFalse(port.hasPendingExposure)
    }

    @Test fun exposureIsStampedWithTheFrameAndroidCameraClock() {
        assertEquals(9_000L, playbackExposureTimestampNs(androidCameraTimestampNs = 9_000L, imageTimestampNs = 42L))
        assertEquals(42L, playbackExposureTimestampNs(androidCameraTimestampNs = 0L, imageTimestampNs = 42L))
    }

    @Test fun missingCpuImagesFailAfterTheWaitBound() {
        val port = exposure()
        val callback = Callback()
        port.requestExposure(qualifier, setOf(CaptureComponentKind.JPEG), callback)
        port.onFrame { null }
        port.onFrame { throw IllegalStateException("NotYetAvailable") }
        assertTrue(callback.failures.isEmpty())
        port.onFrame { null }
        assertEquals(listOf("playback-camera-image-unavailable"), callback.failures)
        assertFalse(port.hasPendingExposure)
    }

    @Test fun cancelAndCloseDropThePendingExposure() {
        val port = exposure()
        val cancelled = Callback()
        port.requestExposure(qualifier, setOf(CaptureComponentKind.JPEG), cancelled)
        port.cancelExposure(qualifier)
        var acquired = 0
        port.onFrame { acquired++; image }
        assertEquals("no image is copied without a pending exposure", 0, acquired)
        val closed = Callback()
        port.requestExposure(qualifier, setOf(CaptureComponentKind.JPEG), closed)
        port.close()
        assertEquals(listOf("owner-closed"), closed.failures)
        assertFalse(port.requestExposure(qualifier, setOf(CaptureComponentKind.JPEG), Callback()))
        assertTrue(cancelled.failures.isEmpty())
    }

    @Test fun blurryReplayFramesAreRejectedLikeCameraStills() {
        val port = exposure(score = 1.0)
        val callback = Callback()
        port.requestExposure(qualifier, setOf(CaptureComponentKind.JPEG), callback)
        port.onFrame { image }
        queued.single().run()
        assertEquals(listOf("REJECTED_BLUR"), callback.failures)
        assertTrue(callback.components.isEmpty())
    }

    @Test fun rejectedDispatchFailsTheAttempt() {
        val port = exposure(executor = { throw RejectedExecutionException() })
        val callback = Callback()
        port.requestExposure(qualifier, setOf(CaptureComponentKind.JPEG), callback)
        port.onFrame { image }
        assertEquals(listOf("component-dispatch"), callback.failures)
    }
}
