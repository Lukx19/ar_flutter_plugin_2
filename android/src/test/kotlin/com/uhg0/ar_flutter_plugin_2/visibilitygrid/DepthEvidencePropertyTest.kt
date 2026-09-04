package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

        val expected = accepted(sequence = 1, acceptedSamples = 3, touchedRows = 3, virtualWork = 6)
        assertEquals(expected, first)
        assertEquals(expected, reversed)
    }

    @Test
    fun `invalid inputs use typed refusals without consuming sequence`() {
        val kernel = DepthEvidenceKernel()
        val view = PropertyView()
        val valid = batch(1)
        assertTrue(kernel.prepare(valid, view) is DepthEvidenceResult.Accepted)
        kernel.applyPrepared()

        val committed = accepted(1).receipt
        val duplicate = kernel.prepare(batch(1), view)
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.DUPLICATE_TIMESTAMP, committed), duplicate)
        val notTracking = kernel.prepare(batch(2, tracking = false), view)
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.NOT_TRACKING, committed), notTracking)
        val malformedMatrix = kernel.prepare(
            DepthEvidenceBatch(3, 3, frame(), listOf(1.0), intrinsics(), listOf(sample()), 0),
            view,
        )
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.INVALID_FRAME, committed), malformedMatrix)
        assertEquals(1, kernel.resourceReceipt().residentEvidenceRows)
    }

    @Test
    fun `sample ray and surface capacities refuse before state mutation`() {
        val sampleLimited = DepthEvidenceKernel(DepthEvidenceConfiguration(sampleCapacity = 1))
        val tooManySamples = sampleLimited.prepare(
            DepthEvidenceBatch(1, 1, frame(), identity(), intrinsics(), listOf(sample(), sample()), 0),
            PropertyView(),
        ) as DepthEvidenceResult.Refused
        val capacityReceipt = DepthEvidenceReceipt(capacityRefusals = 1)
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.SAMPLE_CAPACITY, capacityReceipt), tooManySamples)
        assertEquals(0, sampleLimited.resourceReceipt().residentEvidenceRows)
        val afterSampleRefusal = sampleLimited.prepare(
            DepthEvidenceBatch(2, 2, frame(), identity(), intrinsics(), listOf(sample()), 0),
            PropertyView(),
        ) as DepthEvidenceResult.Accepted
        assertEquals(accepted(2, capacityRefusals = 1), afterSampleRefusal)
        sampleLimited.discardPrepared()

        val rayLimited = DepthEvidenceKernel(DepthEvidenceConfiguration(rayVisitCapacity = 1))
        val tooManyRays = rayLimited.prepare(batch(1), PropertyView(rayCells = listOf(Voxel(0, 0, -2), Voxel(0, 0, -3)))) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.RAY_VISIT_CAPACITY, capacityReceipt), tooManyRays)
        assertEquals(0, rayLimited.resourceReceipt().residentEvidenceRows)

        val surfaceLimited = DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceCapacity = 1))
        val twoEndpoints = surfaceLimited.prepare(
            DepthEvidenceBatch(
                1, 1, frame(), identity(), intrinsics(),
                listOf(VisibilityDepthSample(0, 0, 1_000, 255), VisibilityDepthSample(1, 0, 1_000, 255)), 0,
            ),
            PropertyView(),
        ) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.SURFACE_CAPACITY, capacityReceipt), twoEndpoints)
        assertEquals(0, surfaceLimited.resourceReceipt().residentEvidenceRows)
    }

    @Test
    fun `resource receipt reports fixed primitive ownership and staged row bytes`() {
        val kernel = DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceCapacity = 1))
        val before = kernel.resourceReceipt()
        assertEquals(1_024, before.fixedPrimitiveBytes)

        val accepted = kernel.prepare(batch(1), PropertyView()) as DepthEvidenceResult.Accepted
        val staged = kernel.resourceReceipt()
        assertEquals(1, staged.preparedEvidenceRows)
        assertEquals(32, staged.preparedResidentBytes)
        assertEquals(before.fixedPrimitiveBytes, staged.fixedPrimitiveBytes)
        assertEquals(32, accepted.receipt.preparedResidentBytes)
        kernel.discardPrepared()
        assertEquals(0, kernel.resourceReceipt().preparedEvidenceRows)
    }

    @Test
    fun `maximum configured ownership remains within the semantic state budget`() {
        val receipt = DepthEvidenceKernel(
            DepthEvidenceConfiguration(
                sampleCapacity = 1_536,
                rayVisitCapacity = 65_536,
                surfaceCapacity = 100_000,
            ),
        ).resourceReceipt()

        assertEquals(
            DepthEvidenceResourceReceipt(
                residentEvidenceRows = 0,
                residentBytes = 0,
                preparedEvidenceRows = 0,
                preparedResidentBytes = 0,
                evidenceRowCapacity = 100_000,
                fixedPrimitiveBytes = 16_735_904,
                closed = false,
            ),
            receipt,
        )
        assertTrue(receipt.fixedPrimitiveBytes <= 16 * 1024 * 1024)
        assertThrows(IllegalArgumentException::class.java) {
            DepthEvidenceConfiguration(surfaceCapacity = 100_001)
        }
    }

    @Test
    fun `prepared bytes account for an update to an existing resident row`() {
        val kernel = DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceCapacity = 1))
        val view = PropertyView()

        kernel.prepare(batch(1), view)
        kernel.applyPrepared()
        val update = kernel.prepare(batch(2), view) as DepthEvidenceResult.Accepted
        val staged = kernel.resourceReceipt()

        assertEquals(1, staged.residentEvidenceRows)
        assertEquals(32, staged.residentBytes)
        assertEquals(1, staged.preparedEvidenceRows)
        assertEquals(32, staged.preparedResidentBytes)
        assertEquals(32, update.receipt.preparedResidentBytes)
        kernel.discardPrepared()
        assertEquals(0, kernel.resourceReceipt().preparedEvidenceRows)
        assertEquals(1, kernel.resourceReceipt().residentEvidenceRows)
    }

    @Test
    fun `invalid sample and checked counter overflow are bounded`() {
        val kernel = DepthEvidenceKernel()
        val invalid = kernel.prepare(
            DepthEvidenceBatch(1, 1, frame(), identity(), intrinsics(), listOf(VisibilityDepthSample(0, 0, 1, 255)), 0),
            PropertyView(),
        )
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.INVALID_SAMPLE, DepthEvidenceReceipt()), invalid)

        val overflow = kernel.prepare(
            DepthEvidenceBatch(2, 2, frame(), identity(), intrinsics(), listOf(VisibilityDepthSample(0, 0, 1, 255)), Int.MAX_VALUE),
            PropertyView(),
        )
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.ARITHMETIC_OVERFLOW, DepthEvidenceReceipt()), overflow)
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

    private fun accepted(
        sequence: Long,
        acceptedSamples: Int = 1,
        touchedRows: Int = 1,
        capacityRefusals: Int = 0,
        virtualWork: Int = acceptedSamples + touchedRows,
    ) = DepthEvidenceResult.Accepted(
        expectedGeometryRevision = 0,
        expectedLineageRevision = 0,
        changes = emptyList(),
        receipt = DepthEvidenceReceipt(
            sequence = sequence,
            sourceTimestampNs = sequence,
            acceptedSamples = acceptedSamples,
            touchedEvidenceRows = touchedRows,
            capacityRefusals = capacityRefusals,
            preparedResidentBytes = touchedRows * 32,
            p50VirtualWorkUnits = virtualWork,
            p95VirtualWorkUnits = virtualWork,
        ),
        work = DepthEvidenceWorkReceipt(touchedRows, 0, 0, 0, virtualWork),
    )

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
