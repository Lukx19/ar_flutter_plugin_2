package com.uhg0.ar_flutter_plugin_2.capture

import android.graphics.ImageFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HighResCapturePipelineTest {
    @Test
    fun `releases reservation and skips commit when blur is rejected without retention`() {
        val cache = FakeCache()
        val poseResolver = FakePoseResolver()
        val quality = qualityMap(blurPassed = false, blurThreshold = 120.0)
        val pipeline =
            HighResCapturePipeline(
                cache = cache,
                poseResolver = poseResolver,
                qualityAnalyzer = { _, _ -> quality },
            )

        val result =
            pipeline.processCapture(
                sharedResult = sampleSharedResult(),
                qualityPolicy =
                    CaptureQualityPolicy(
                        blurFilterEnabled = true,
                        blurThreshold = 120.0,
                        keepRejectedCaptures = false,
                    ),
            )

        assertEquals("rejectedBlur", result["status"])
        assertNull(result["imageId"])
        assertNull(result["capture"])
        assertSame(quality, result["quality"])
        assertEquals(listOf("reservation-1"), cache.releasedReservations)
        assertTrue(cache.committedReservations.isEmpty())
        assertEquals(1, poseResolver.resolvedTimings.size)
    }

    @Test
    fun `starts pose resolution before blur analysis completes`() {
        val cache = FakeCache()
        val poseStarted = CountDownLatch(1)
        val qualityObservedPose = CountDownLatch(1)
        val poseResolver =
            FakePoseResolver(
                onResolvePose = {
                    poseStarted.countDown()
                },
            )
        val pipeline =
            HighResCapturePipeline(
                cache = cache,
                poseResolver = poseResolver,
                qualityAnalyzer = { _, _ ->
                    if (poseStarted.await(1, TimeUnit.SECONDS)) {
                        qualityObservedPose.countDown()
                    }
                    qualityMap(blurPassed = true, blurThreshold = 110.0)
                },
            )

        val result =
            pipeline.processCapture(
                sharedResult = sampleSharedResult(),
                qualityPolicy =
                    CaptureQualityPolicy(
                        blurFilterEnabled = true,
                        blurThreshold = 110.0,
                        keepRejectedCaptures = false,
                    ),
            )

        assertEquals("staged", result["status"])
        assertTrue(qualityObservedPose.await(0, TimeUnit.SECONDS))
    }

    @Test
    fun `commits retained blur rejection after pose resolution`() {
        val cache = FakeCache()
        val poseResolver = FakePoseResolver()
        val quality = qualityMap(blurPassed = false, blurThreshold = 120.0)
        val pipeline =
            HighResCapturePipeline(
                cache = cache,
                poseResolver = poseResolver,
                qualityAnalyzer = { _, _ -> quality },
            )

        val result =
            pipeline.processCapture(
                sharedResult = sampleSharedResult(),
                qualityPolicy =
                    CaptureQualityPolicy(
                        blurFilterEnabled = true,
                        blurThreshold = 120.0,
                        keepRejectedCaptures = true,
                    ),
            )

        assertEquals("rejectedBlur", result["status"])
        assertEquals("image-1", result["imageId"])
        assertSame(quality, result["quality"])
        assertTrue(cache.releasedReservations.isEmpty())
        assertEquals(listOf("reservation-1"), cache.committedReservations)
        val capture = result["capture"] as Map<*, *>
        assertEquals("image-1", capture["imageId"])
        assertEquals(true, capture["isHighResolution"])
        assertEquals(1, poseResolver.resolvedTimings.size)
    }

    @Test
    fun `releases reservation when pose resolution fails before commit`() {
        val cache = FakeCache()
        val poseResolver = FakePoseResolver(resolvedPose = null)
        val pipeline =
            HighResCapturePipeline(
                cache = cache,
                poseResolver = poseResolver,
                qualityAnalyzer = { _, _ -> qualityMap(blurPassed = true, blurThreshold = 110.0) },
            )

        val error =
            try {
                pipeline.processCapture(
                    sharedResult = sampleSharedResult(),
                    qualityPolicy =
                        CaptureQualityPolicy(
                            blurFilterEnabled = true,
                            blurThreshold = 110.0,
                            keepRejectedCaptures = false,
                        ),
                )
                null
            } catch (failure: CaptureSessionException) {
                failure
            }

        requireNotNull(error)
        assertEquals("POSE_SYNC_FAILED", error.code)
        assertEquals(listOf("reservation-1"), cache.releasedReservations)
        assertTrue(cache.committedReservations.isEmpty())
    }

    @Test
    fun `commits accepted capture and returns staged result`() {
        val cache = FakeCache()
        val poseResolver = FakePoseResolver()
        val quality = qualityMap(blurPassed = true, blurThreshold = 110.0)
        val pipeline =
            HighResCapturePipeline(
                cache = cache,
                poseResolver = poseResolver,
                qualityAnalyzer = { _, _ -> quality },
            )

        val result =
            pipeline.processCapture(
                sharedResult = sampleSharedResult(),
                qualityPolicy =
                    CaptureQualityPolicy(
                        blurFilterEnabled = true,
                        blurThreshold = 110.0,
                        keepRejectedCaptures = false,
                    ),
            )

        assertEquals("staged", result["status"])
        assertEquals("image-1", result["imageId"])
        assertSame(quality, result["quality"])
        assertTrue(cache.releasedReservations.isEmpty())
        assertEquals(listOf("reservation-1"), cache.committedReservations)
        val capture = result["capture"] as Map<*, *>
        assertEquals("image-1", capture["imageId"])
        assertEquals(4_000_000L, capture["exposureTimeNs"])
        val timing = result["pipelineTimingMs"] as Map<*, *>
        assertTrue(timing.containsKey("qualityAwait"))
        assertTrue(timing.containsKey("poseAwait"))
        assertTrue(timing.containsKey("cacheCommit"))
        assertTrue(timing.containsKey("nativeFinalizationTotal"))
    }

    @Test
    fun `releases reservation when quality analysis exceeds deadline`() {
        val cache = FakeCache()
        val poseResolver = FakePoseResolver()
        val pipeline =
            HighResCapturePipeline(
                cache = cache,
                poseResolver = poseResolver,
                qualityAnalyzer = { _, _ ->
                    Thread.sleep(250)
                    qualityMap(blurPassed = true, blurThreshold = 110.0)
                },
                qualityAnalysisTimeoutMs = 200,
            )

        val error =
            try {
                pipeline.processCapture(
                    sharedResult = sampleSharedResult(),
                    qualityPolicy =
                        CaptureQualityPolicy(
                            blurFilterEnabled = true,
                            blurThreshold = 110.0,
                            keepRejectedCaptures = false,
                        ),
                )
                null
            } catch (failure: CaptureSessionException) {
                failure
            }

        requireNotNull(error)
        assertEquals("QUALITY_ANALYSIS_FAILED", error.code)
        assertEquals(listOf("reservation-1"), cache.releasedReservations)
        assertTrue(cache.committedReservations.isEmpty())
    }

    @Test
    fun `raw only capture commits DNG without running JPEG blur analysis`() {
        val cache = FakeCache()
        var qualityCalls = 0
        val pipeline =
            HighResCapturePipeline(
                cache = cache,
                poseResolver = FakePoseResolver(),
                qualityAnalyzer = { _, _ ->
                    qualityCalls += 1
                    qualityMap(blurPassed = true, blurThreshold = 110.0)
                },
            )
        val rawResult =
            sampleSharedResult().copy(
                format = ImageFormat.RAW_SENSOR,
                primaryAssetName = "raw",
            )

        val result =
            pipeline.processCapture(
                rawResult,
                CaptureQualityPolicy(
                    blurFilterEnabled = true,
                    blurThreshold = 110.0,
                    keepRejectedCaptures = false,
                ),
            )

        assertEquals("staged", result["status"])
        assertEquals(0, qualityCalls)
        assertEquals(listOf("reservation-1"), cache.committedReservations)
        val capture = result["capture"] as Map<*, *>
        assertEquals("raw", capture["format"])
        assertEquals(listOf("raw"), capture["formats"])
    }

    private fun sampleSharedResult() =
        SharedCameraCaptureResult(
            reservationToken = "reservation-1",
            imageId = "image-1",
            imageBytes = byteArrayOf(1, 2, 3, 4),
            format = ImageFormat.JPEG,
            width = 1920,
            height = 1080,
            imageSizeBytes = 4,
            captureTimestampMs = 1234L,
            sensorTimestampNs = 1_000_000_000L,
            exposureTimeNs = 4_000_000L,
            rollingShutterSkewNs = 500_000L,
            intrinsics = mapOf("fx" to 1000.0),
        )

    private fun qualityMap(
        blurPassed: Boolean,
        blurThreshold: Double,
    ) = mapOf(
        "blurScore" to if (blurPassed) 150.0 else 50.0,
        "blurThreshold" to blurThreshold,
        "blurPassed" to blurPassed,
        "analyzedWidth" to 640,
        "analyzedHeight" to 480,
        "algorithm" to "jpegLaplacianVarianceV1",
    )

    private class FakeCache : HighResCaptureCache {
        val releasedReservations = mutableListOf<String>()
        val committedReservations = mutableListOf<String>()

        override fun releaseReservation(reservationToken: String): Boolean {
            releasedReservations += reservationToken
            return true
        }

        override fun commitReservedImage(
            reservationToken: String,
            imageId: String,
            imageBytes: ByteArray,
            format: Int,
        ): Boolean {
            committedReservations += reservationToken
            return true
        }
    }

    private class FakePoseResolver(
        private val resolvedPose: PoseDataExtractor.AlignedPose? =
            PoseDataExtractor(capacity = 1).toAlignedPose(
                pose =
                    PoseDataExtractor.CachedPose(
                        position = floatArrayOf(1f, 2f, 3f),
                        rotationQuaternion = floatArrayOf(0f, 0f, 0f, 1f),
                        transform =
                            floatArrayOf(
                                1f, 0f, 0f, 0f,
                                0f, 1f, 0f, 0f,
                                0f, 0f, 1f, 0f,
                                1f, 2f, 3f, 1f,
                            ),
                        timestampNs = 1_002_000_000L,
                        systemTimestampMs = 1002L,
                        isTracking = true,
                        confidence = 1.0f,
                        trackingState = "tracking",
                    ),
                sensorTimestampNs = 1_000_000_000L,
                poseAlignment = "exact",
                poseTimeErrorNs = 0L,
                exposureTimeNs = 4_000_000L,
                rollingShutterSkewNs = 500_000L,
            ),
        private val onResolvePose: (() -> Unit)? = null,
    ) : HighResPoseResolver {
        val resolvedTimings = mutableListOf<PoseDataExtractor.CaptureTiming>()

        override fun resolvePose(
            captureTiming: PoseDataExtractor.CaptureTiming,
        ): PoseDataExtractor.AlignedPose? {
            onResolvePose?.invoke()
            resolvedTimings += captureTiming
            return resolvedPose
        }

        override fun toPoseMap(
            alignedPose: PoseDataExtractor.AlignedPose,
        ): Map<String, Any?> =
            mapOf(
                "position" to mapOf("x" to 1.0, "y" to 2.0, "z" to 3.0),
                "rotation" to mapOf("x" to 0.0, "y" to 0.0, "z" to 0.0, "w" to 1.0),
                "transform" to alignedPose.pose.transform.map { it.toDouble() },
                "convention" to PoseDataExtractor.OPENCV_CONVENTION,
                "timestampMs" to alignedPose.pose.systemTimestampMs,
                "sensorTimestampNs" to alignedPose.sensorTimestampNs,
                "confidence" to alignedPose.pose.confidence.toDouble(),
                "isTracking" to alignedPose.pose.isTracking,
                "trackingState" to alignedPose.pose.trackingState,
                "poseAlignment" to alignedPose.poseAlignment,
                "poseTimeErrorNs" to alignedPose.poseTimeErrorNs,
            )
    }
}
