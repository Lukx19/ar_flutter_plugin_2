package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.nio.FloatBuffer
import java.nio.IntBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArCoreVisibilityObservationAdapterTest {
    @Test
    fun `depth viewpoint gate admits motion and periodic static refresh`() {
        val gate = DepthViewNoveltyGate()
        val origin = floatArrayOf(0f, 0f, 0f)
        val identity = floatArrayOf(0f, 0f, 0f, 1f)
        assertTrue(gate.isNovel(1_000_000_000L, origin, identity))
        gate.remember(1_000_000_000L, origin, identity)
        assertFalse(gate.isNovel(1_250_000_000L, origin, identity))
        assertFalse(gate.isNovel(1_500_000_000L, floatArrayOf(0.04f, 0f, 0f), identity))
        assertTrue(gate.isNovel(1_500_000_000L, floatArrayOf(0.06f, 0f, 0f), identity))
        assertTrue(gate.isNovel(1_500_000_000L, origin,
            floatArrayOf(0f, 0f, 0.08715574f, 0.9961947f)))
        assertTrue(gate.isNovel(4_000_000_000L, origin, identity))
    }

    @Test
    fun `empty copied depth observations remain transient until motion produces samples`() {
        val result = convertDepthObservationResult(
            source = DepthObservation(
                timestampNs = 10,
                groupGeneration = 1,
                sessionGeneration = 1,
                tracking = true,
                width = 2,
                height = 2,
                samples = emptyList(),
                intrinsics = DepthIntrinsics(1.0, 1.0, 0.0, 0.0),
                worldFromCameraGl = identityMatrix(),
            ),
            ownership = ownership(),
            depthCapability = VisibilityDepthCapability.AUTOMATIC,
            frameSequence = 1,
            frameTimestampNs = 11,
        )

        assertSame(VisibilityDepthCopyResult.TransientUnavailable, result)
    }

    @Test
    fun `empty and fully filtered point clouds are transient`() {
        assertSame(
            VisibilityFeatureSamplesCopyResult.TransientUnavailable,
            copyVisibilityFeatureSamples(
                sourceIds = IntBuffer.wrap(intArrayOf()),
                sourcePoints = FloatBuffer.wrap(floatArrayOf()),
                minimumFeatureConfidence = 0.30,
                maxCopiedSamples = V2_FEATURE_SAMPLE_CAPACITY,
            ),
        )

        assertSame(
            VisibilityFeatureSamplesCopyResult.TransientUnavailable,
            copyVisibilityFeatureSamples(
                sourceIds = IntBuffer.wrap(intArrayOf(1, 2)),
                sourcePoints = FloatBuffer.wrap(
                    floatArrayOf(
                        1f, 2f, 3f, 0.29f,
                        Float.NaN, 2f, 3f, 0.80f,
                    ),
                ),
                minimumFeatureConfidence = 0.30,
                maxCopiedSamples = V2_FEATURE_SAMPLE_CAPACITY,
            ),
        )
    }

    @Test
    fun `mismatched feature buffer lengths are rejected`() {
        val result = copyVisibilityFeatureSamples(
            sourceIds = IntBuffer.wrap(intArrayOf(1)),
            sourcePoints = FloatBuffer.wrap(floatArrayOf(1f, 2f, 3f)),
            minimumFeatureConfidence = 0.30,
            maxCopiedSamples = V2_FEATURE_SAMPLE_CAPACITY,
        )

        assertTrue(result is VisibilityFeatureSamplesCopyResult.Rejected)
        assertEquals(
            "feature buffers have mismatched lengths",
            (result as VisibilityFeatureSamplesCopyResult.Rejected).reason,
        )
    }

    @Test
    fun `valid samples preserve the rejected sample count`() {
        val result = copyVisibilityFeatureSamples(
            sourceIds = IntBuffer.wrap(intArrayOf(7, 8, 7, 9)),
            sourcePoints = FloatBuffer.wrap(
                floatArrayOf(
                    1f, 2f, 3f, 0.80f,
                    4f, 5f, 6f, 0.20f,
                    1f, 2f, 3f, 0.90f,
                    Float.POSITIVE_INFINITY, 5f, 6f, 0.90f,
                ),
            ),
            minimumFeatureConfidence = 0.30,
            maxCopiedSamples = V2_FEATURE_SAMPLE_CAPACITY,
        )

        assertTrue(result is VisibilityFeatureSamplesCopyResult.Samples)
        result as VisibilityFeatureSamplesCopyResult.Samples
        assertEquals(listOf(7), result.values.map(VisibilityFeatureSample::id))
        assertEquals(3, result.sourceRejectedSamples)
    }

    private fun ownership() = VisibilityObservationOwnership(
        sessionId = "0".repeat(32),
        sessionGeneration = 1,
        captureGroupId = "1".repeat(32),
        groupGeneration = 1,
        coverageEpoch = 1,
        arSessionIdentity = "2".repeat(32),
        viewInstanceId = "3".repeat(32),
        viewGeneration = 1,
        nativeStreamToken = "4".repeat(32),
        workerBindingToken = "5".repeat(32),
        bindingGeneration = 1,
        lifecycleSequence = 1,
        operationGeneration = 1,
        groupFrame = VisibilityGroupFrame.copyOf(
            groupFromWorldGl = identityMatrix(),
            worldFromGroupGl = identityMatrix(),
            voxelSizeMicrometres = 10_000,
            modelCapacity = 100,
        ),
    )

    private fun identityMatrix() = DoubleArray(16) { index ->
        if (index % 5 == 0) 1.0 else 0.0
    }
}
