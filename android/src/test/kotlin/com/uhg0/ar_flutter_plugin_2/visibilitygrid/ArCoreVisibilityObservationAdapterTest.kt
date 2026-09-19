package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.nio.FloatBuffer
import java.nio.IntBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArCoreVisibilityObservationAdapterTest {
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
}
