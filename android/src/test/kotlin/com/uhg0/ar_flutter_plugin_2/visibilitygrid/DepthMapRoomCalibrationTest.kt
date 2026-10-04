package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calibrates the current bounded depth work budget with one reusable camera
 * image shaped like the requested 2,000 x 2,000 synthetic prediction output.
 *
 * This is deliberately a host test: it exercises the same kernel admission
 * and supercover work as the device path while keeping the input and receipt
 * deterministic.  The production ray budget is not changed by this fixture.
 */
class DepthMapRoomCalibrationTest {
    @Test
    fun `reusable room depth map records coincident mixed and far work`() {
        val image = ReusableDepthImage()
        val pool = DepthSamplesLeasePool(capacity = SAMPLE_CAPACITY)
        try {
            val coincident = runCase(image, pool, DepthCase.COINCIDENT, 3_072)
            val coincidentMaximum = runCase(image, pool, DepthCase.COINCIDENT, 4_096)
            val mixed = runCase(image, pool, DepthCase.MIXED_ROOM, 3_072)
            val mixedMaximum = runCase(image, pool, DepthCase.MIXED_ROOM, 4_096)
            val far = runCase(image, pool, DepthCase.FAR_ROOM, 4_096)

            assertAccepted(coincident)
            assertAccepted(coincidentMaximum)
            assertRoomOutcome(mixed)
            assertRoomOutcome(mixedMaximum)
            assertEquals(1, coincident.uniqueEndpointVoxels)
            assertEquals(1, coincidentMaximum.uniqueEndpointVoxels)
            assertTrue(coincidentMaximum.receipt.rayVisits < 1_000)
            assertTrue(coincidentMaximum.directLookups >= coincidentMaximum.receipt.acceptedSamples)
            assertTrue(mixed.uniqueEndpointVoxels > 300)
            assertTrue(mixedMaximum.uniqueEndpointVoxels > 300)
            assertTrue(mixedMaximum.uniqueEndpointVoxels > mixed.uniqueEndpointVoxels)
            assertEquals(
                "far unique=${far.uniqueEndpointVoxels} receipt=${far.receipt} lookups=${far.directLookups}",
                DepthEvidenceRefusal.RAY_VISIT_CAPACITY,
                far.refusal,
            )
            assertTrue("far receipt=${far.receipt}" , far.receipt.rayVisits >= 65_536)
            assertTrue("far lookups=${far.directLookups} receipt=${far.receipt}", far.directLookups >= far.receipt.acceptedSamples)

            val receipts = listOf(
                coincident.resource,
                coincidentMaximum.resource,
                mixed.resource,
                mixedMaximum.resource,
                far.resource,
            )
            receipts.forEach { resource ->
                assertTrue(resource.fixedPrimitiveBytes > 0)
                assertTrue(resource.modeledMaximumSemanticStateBytes <= resource.semanticStateBudgetBytes)
            }
            assertEquals(coincident.resource.fixedPrimitiveBytes, mixed.resource.fixedPrimitiveBytes)
            assertEquals(coincident.resource.modeledMaximumSemanticStateBytes, far.resource.modeledMaximumSemanticStateBytes)
        } finally {
            pool.close()
        }
    }

    private fun assertAccepted(observation: CalibrationObservation) {
        assertEquals(null, observation.refusal)
        assertEquals(observation.sampleCount, observation.receipt.acceptedSamples)
        assertTrue(observation.receipt.rayVisits >= observation.uniqueEndpointVoxels)
        assertTrue(observation.directLookups >= observation.uniqueEndpointVoxels)
    }

    private fun assertRoomOutcome(observation: CalibrationObservation) {
        assertTrue(observation.refusal == null || observation.refusal == DepthEvidenceRefusal.RAY_VISIT_CAPACITY)
        if (observation.refusal == null) {
            assertAccepted(observation)
        } else {
            assertTrue(observation.receipt.acceptedSamples > 0)
            assertTrue(observation.receipt.rayVisits >= 65_536)
            assertTrue(observation.directLookups >= observation.receipt.acceptedSamples)
        }
    }

    private fun runCase(
        image: ReusableDepthImage,
        pool: DepthSamplesLeasePool,
        depthCase: DepthCase,
        sampleCount: Int,
    ): CalibrationObservation {
        image.fill(depthCase, sampleCount)
        val ownership = SampleLeaseGeneration(1, 1, 1, sampleCount.toLong() + depthCase.ordinal + 1L)
        val lease = requireNotNull(pool.tryAcquire(ownership))
        lease.clear()
        repeat(sampleCount) { index ->
            require(lease.append(image.xAt(index), image.yAt(index), image.depthAt(index), 255))
        }
        val metadata = DepthEvidenceMetadata(
            sequence = sampleCount.toLong() + depthCase.ordinal + 1L,
            sourceTimestampNs = sampleCount.toLong() + depthCase.ordinal + 1L,
            groupFrame = roomFrame(),
            groupFromCameraGl = identityTransform().toList(),
            intrinsics = ROOM_INTRINSICS,
            sourceRejectedSamples = 0,
        )
        val view = CountingSurfaceView()
        val kernel = DepthEvidenceKernel()
        return try {
            val result = kernel.prepare(lease.samples, metadata, view)
            val receipt = when (result) {
                is DepthEvidenceResult.Accepted -> result.receipt
                is DepthEvidenceResult.Refused -> result.receipt
            }
            val refusal = (result as? DepthEvidenceResult.Refused)?.reason
            val resource = kernel.resourceReceipt()
            if (result is DepthEvidenceResult.Accepted) kernel.discardPrepared()
            CalibrationObservation(
                sampleCount = sampleCount,
                uniqueEndpointVoxels = image.uniqueEndpointVoxels(sampleCount),
                directLookups = view.directLookupCount,
                receipt = receipt,
                refusal = refusal,
                resource = resource,
            )
        } finally {
            kernel.close()
            lease.close()
        }
    }

    private enum class DepthCase {
        COINCIDENT,
        MIXED_ROOM,
        FAR_ROOM,
    }

    private class ReusableDepthImage {
        private val depthMillimetres = IntArray(IMAGE_WIDTH * IMAGE_HEIGHT)
        private val xValues = IntArray(SAMPLE_CAPACITY)
        private val yValues = IntArray(SAMPLE_CAPACITY)

        fun fill(depthCase: DepthCase, sampleCount: Int) {
            repeat(sampleCount) { index ->
                val x: Int
                val y: Int
                val depth: Int
                when (depthCase) {
                    DepthCase.COINCIDENT -> {
                        x = 1_001 + index % 4
                        y = 1_001 + (index / 4) % 4
                        depth = 2_000
                    }
                    DepthCase.MIXED_ROOM -> {
                        x = 16 + (index % 64) * 31
                        y = 16 + ((index / 64) % 64) * 31
                        depth = 2_000 + ((index / 1_024) % 3) * 500
                    }
                    DepthCase.FAR_ROOM -> {
                        x = 20 + (index % 64) * 30
                        y = 20 + ((index / 64) % 64) * 30
                        depth = 6_000 + (index % 3) * 1_000
                    }
                }
                xValues[index] = x
                yValues[index] = y
                depthMillimetres[y * IMAGE_WIDTH + x] = depth
            }
        }

        fun xAt(index: Int): Int = xValues[index]
        fun yAt(index: Int): Int = yValues[index]
        fun depthAt(index: Int): Int = depthMillimetres[yValues[index] * IMAGE_WIDTH + xValues[index]]

        fun uniqueEndpointVoxels(sampleCount: Int): Int {
            return (0 until sampleCount).mapTo(HashSet()) { index ->
                val depth = depthAt(index).toDouble()
                val x = (xAt(index) - ROOM_INTRINSICS.cx) * depth / ROOM_INTRINSICS.fx
                val y = -(yAt(index) - ROOM_INTRINSICS.cy) * depth / ROOM_INTRINSICS.fy
                val z = -depth
                Voxel(kotlin.math.floor(x / VOXEL_MM).toInt(), kotlin.math.floor(y / VOXEL_MM).toInt(), kotlin.math.floor(z / VOXEL_MM).toInt())
            }.size
        }
    }

    private class CountingSurfaceView : BoundedCanonicalSurfaceView {
        var directLookupCount: Int = 0
            private set

        override val revisionPair = CanonicalRevisionPair(0, 0)
        override val surfaceCount: Int = 0
        override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? = null
        override fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface? = null
        override fun findSurfaceAtInto(x: Int, y: Int, z: Int, scratch: CanonicalSurfaceScratch): Boolean {
            directLookupCount++
            scratch.clear()
            return false
        }
        override fun visitRayCells(
            startGroupMm: DepthPointMm,
            endpointGroupMm: DepthPointMm,
            maximumVisits: Int,
            visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
        ): DepthRayVisitResult = error("kernel owns ray traversal")
    }

    private data class CalibrationObservation(
        val sampleCount: Int,
        val uniqueEndpointVoxels: Int,
        val directLookups: Int,
        val receipt: DepthEvidenceReceipt,
        val refusal: DepthEvidenceRefusal?,
        val resource: DepthEvidenceResourceReceipt,
    )

    private fun roomFrame() = VisibilityGroupFrame.copyOf(
        identityTransform(), identityTransform(), VOXEL_MICROMETRES, 100_000,
    )

    private fun identityTransform() = DoubleArray(16).also {
        it[0] = 1.0
        it[5] = 1.0
        it[10] = 1.0
        it[15] = 1.0
    }

    private companion object {
        const val IMAGE_WIDTH = 2_000
        const val IMAGE_HEIGHT = 2_000
        const val SAMPLE_CAPACITY = 4_096
        const val VOXEL_MM = 100.0
        const val VOXEL_MICROMETRES = 100_000
        val ROOM_INTRINSICS = VisibilityCameraIntrinsics(
            IMAGE_WIDTH, IMAGE_HEIGHT, 1_500.0, 1_500.0, 1_000.0, 1_000.0,
        )
    }
}
