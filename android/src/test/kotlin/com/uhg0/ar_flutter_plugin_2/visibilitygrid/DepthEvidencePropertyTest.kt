package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthEvidencePropertyTest {
    @Test
    fun `permuting pixels preserves the deterministic accepted packet`() {
        val samples = listOf(
            VisibilityDepthSample(0, 0, 1_000, 255),
            VisibilityDepthSample(1, 0, 1_000, 255),
            VisibilityDepthSample(2, 0, 1_000, 255),
        )
        val first = prepare(DepthEvidenceBatch(1, 1, frame(), identity(), intrinsics(), samples, 0))
        val reversed = prepare(DepthEvidenceBatch(1, 1, frame(), identity(), intrinsics(), samples.reversed(), 0))

        assertEquals(first, reversed)
    }

    @Test
    fun `invalid inputs use typed refusals without consuming sequence`() {
        val kernel = DepthEvidenceKernel()
        val view = PropertyView()
        val valid = batch(1)
        assertTrue(kernel.prepare(valid, view) is DepthEvidenceResult.Accepted)
        kernel.applyPrepared()

        val duplicate = kernel.prepare(batch(1), view) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceRefusal.DUPLICATE_TIMESTAMP, duplicate.reason)
        val notTracking = kernel.prepare(batch(2, tracking = false), view) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceRefusal.NOT_TRACKING, notTracking.reason)
        val malformedMatrix = kernel.prepare(
            DepthEvidenceBatch(3, 3, frame(), listOf(1.0), intrinsics(), listOf(sample()), 0),
            view,
        ) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceRefusal.INVALID_FRAME, malformedMatrix.reason)
        assertEquals(1, kernel.resourceReceipt().residentEvidenceRows)
    }

    @Test
    fun `sample ray and surface capacities refuse before state mutation`() {
        val sampleLimited = DepthEvidenceKernel(DepthEvidenceConfiguration(sampleCapacity = 1))
        val tooManySamples = sampleLimited.prepare(
            DepthEvidenceBatch(1, 1, frame(), identity(), intrinsics(), listOf(sample(), sample()), 0),
            PropertyView(),
        ) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceRefusal.SAMPLE_CAPACITY, tooManySamples.reason)

        val rayLimited = DepthEvidenceKernel(DepthEvidenceConfiguration(rayVisitCapacity = 1))
        val tooManyRays = rayLimited.prepare(batch(1), PropertyView(rayCells = listOf(Voxel(0, 0, -2), Voxel(0, 0, -3)))) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceRefusal.RAY_VISIT_CAPACITY, tooManyRays.reason)

        val surfaceLimited = DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceCapacity = 1))
        val twoEndpoints = surfaceLimited.prepare(
            DepthEvidenceBatch(
                1, 1, frame(), identity(), intrinsics(),
                listOf(VisibilityDepthSample(0, 0, 1_000, 255), VisibilityDepthSample(1, 0, 1_000, 255)), 0,
            ),
            PropertyView(),
        ) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceRefusal.SURFACE_CAPACITY, twoEndpoints.reason)
        assertEquals(0, surfaceLimited.resourceReceipt().residentEvidenceRows)
    }

    @Test
    fun `invalid sample and checked counter overflow are bounded`() {
        val kernel = DepthEvidenceKernel()
        val invalid = kernel.prepare(
            DepthEvidenceBatch(1, 1, frame(), identity(), intrinsics(), listOf(VisibilityDepthSample(0, 0, 1, 255)), 0),
            PropertyView(),
        ) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceRefusal.INVALID_SAMPLE, invalid.reason)

        val overflow = kernel.prepare(
            DepthEvidenceBatch(2, 2, frame(), identity(), intrinsics(), listOf(VisibilityDepthSample(0, 0, 1, 255)), Int.MAX_VALUE),
            PropertyView(),
        ) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceRefusal.ARITHMETIC_OVERFLOW, overflow.reason)
    }

    @Test
    fun `coordinate boundaries remain quantized or refuse without wraparound`() {
        val atMinimum = DepthPointMm(-104_857_600.0, 0.0, 0.0)
        val atMaximum = DepthPointMm(104_857_599.9, 0.0, 0.0)
        assertEquals(Voxel(VOXEL_COORDINATE_MIN, 0, 0), quantize(atMinimum))
        assertEquals(Voxel(VOXEL_COORDINATE_MAX, 0, 0), quantize(atMaximum))
        assertTrue(quantize(DepthPointMm(104_857_600.0, 0.0, 0.0)) == null)
    }

    private fun prepare(batch: DepthEvidenceBatch): DepthEvidenceResult.Accepted {
        val kernel = DepthEvidenceKernel()
        return kernel.prepare(batch, PropertyView()) as DepthEvidenceResult.Accepted
    }

    private fun batch(timestamp: Long, tracking: Boolean = true) = DepthEvidenceBatch(
        timestamp, timestamp, frame(), identity(), intrinsics(), listOf(sample()), 0, tracking,
    )

    private fun sample() = VisibilityDepthSample(0, 0, 1_000, 255)

    private fun frame() = VisibilityGroupFrame.copyOf(identity().toDoubleArray(), identity().toDoubleArray(), 100_000, 100)
    private fun identity() = identityVisibilityGridTransform().toList()
    private fun intrinsics() = VisibilityCameraIntrinsics(4, 1, 2.0, 2.0, 1.5, 0.0)

    private fun quantize(point: DepthPointMm): Voxel? {
        val value = Math.floor(point.x / 100.0)
        return if (value >= VOXEL_COORDINATE_MIN && value <= VOXEL_COORDINATE_MAX) {
            Voxel(value.toInt(), 0, 0)
        } else {
            null
        }
    }

    private class PropertyView(
        private val rayCells: List<Voxel> = emptyList(),
    ) : BoundedCanonicalSurfaceView {
        override val geometryRevision: Long = 0
        override val lineageRevision: Long = 0
        override val surfaceCount: Int = 0

        override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? = null
        override fun findSurfaceAt(voxel: Voxel): DepthCanonicalSurface? = null
        override fun visitRayCells(
            startGroupMm: DepthPointMm,
            endpointGroupMm: DepthPointMm,
            maximumVisits: Int,
            visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
        ): DepthRayVisitResult {
            rayCells.take(maximumVisits).forEach { visitor(it, null) }
            return DepthRayVisitResult(rayCells.size.coerceAtMost(maximumVisits), rayCells.size > maximumVisits)
        }
    }
}
