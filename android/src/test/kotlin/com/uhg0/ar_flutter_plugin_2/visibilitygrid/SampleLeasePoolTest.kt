package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.openjdk.jol.info.GraphLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleLeasePoolTest {
    @Test
    fun `terminal close preserves borrowed samples until consumer drain and releases storage`() {
        val generation = SampleLeaseGeneration(1, 2, 3, 4)
        val features = FeatureSamplesLeasePool(capacity = 8)
        val depths = DepthSamplesLeasePool(capacity = 16)
        val feature = requireNotNull(features.tryAcquire(generation))
        val depth = requireNotNull(depths.tryAcquire(generation))
        assertTrue(feature.append(17, 0.1, 0.2, -0.3, 0.75))
        assertTrue(depth.append(5, 7, 2_000, 200))
        features.close()
        depths.close()
        assertNull(features.tryAcquire(generation))
        assertNull(depths.tryAcquire(generation))
        assertEquals(1, features.receipt().outstanding)
        assertEquals(1, depths.receipt().outstanding)
        assertEquals(8L * 36L + 16L * 4L, features.retainedPrimitiveBytes())
        assertEquals(16L * 26L, depths.retainedPrimitiveBytes())
        assertTrue(features.receipt().portableOwnedBytes > 0L)
        assertTrue(depths.receipt().portableOwnedBytes > 0L)
        assertEquals(17, feature.samples.idAt(0))
        assertEquals(-0.3, feature.samples.zWorldAt(0), 0.0)
        assertEquals(5, depth.samples.xAt(0))
        assertEquals(2_000, depth.samples.depthMillimetresAt(0))
        feature.close()
        depth.close()
        feature.close()
        depth.close()
        assertEquals(0, features.receipt().outstanding)
        assertEquals(0, depths.receipt().outstanding)
        assertEquals(0L, features.retainedPrimitiveBytes())
        assertEquals(0L, depths.retainedPrimitiveBytes())
        assertEquals(
            856L,
            features.receipt().portableOwnedBytes,
        )
        assertEquals(
            984L,
            depths.receipt().portableOwnedBytes,
        )
        assertThrows(IllegalStateException::class.java) { feature.samples.count }
        assertThrows(IllegalStateException::class.java) { depth.samples.count }
    }

    @Test
    fun `portable receipt uses literal headers and covers a live small pool graph`() {
        val features = FeatureSamplesLeasePool(capacity = 8)
        val depths = DepthSamplesLeasePool(capacity = 16)
        try {
            val feature = features.receipt()
            val depth = depths.receipt()
            assertEquals(704L, feature.primitiveBytes)
            assertEquals(832L, depth.primitiveBytes)
            // 32-byte object headers, 24-byte array headers, 8-byte alignment,
            // two slots, two live lease/view/generation envelopes and one
            // frozen 4x4 pose per possible live observation.
            assertEquals(3_880L, feature.portableMaximumOwnedBytes)
            assertEquals(4_136L, depth.portableMaximumOwnedBytes)
            assertTrue(feature.portableOwnedBytes < feature.portableMaximumOwnedBytes)
            assertTrue(depth.portableOwnedBytes < depth.portableMaximumOwnedBytes)
            assertTrue(
                GraphLayout.parseInstance(features).totalSize() <= feature.portableMaximumOwnedBytes,
            )
            assertTrue(
                GraphLayout.parseInstance(depths).totalSize() <= depth.portableMaximumOwnedBytes,
            )

            val lease = requireNotNull(features.tryAcquire(SampleLeaseGeneration(1, 2, 3, 4)))
            features.close()
            val held = features.receipt()
            assertEquals(1, held.outstanding)
            assertTrue(held.portableOwnedBytes > 0L)
            assertEquals(feature.portableMaximumOwnedBytes, held.portableMaximumOwnedBytes)
            assertTrue(held.portableOwnedBytes <= held.portableMaximumOwnedBytes)
            lease.close()
            val drained = features.receipt()
            assertEquals(856L, drained.portableOwnedBytes)
            assertEquals(feature.portableMaximumOwnedBytes, drained.portableMaximumOwnedBytes)
            assertTrue(drained.portableOwnedBytes > SampleLeasePoolPortableMemory.poolBytes(2))
            assertTrue(GraphLayout.parseInstance(features).totalSize() <= drained.portableOwnedBytes)
            depths.close()
            val drainedDepth = depths.receipt()
            assertEquals(984L, drainedDepth.portableOwnedBytes)
            assertEquals(depth.portableMaximumOwnedBytes, drainedDepth.portableMaximumOwnedBytes)
            assertTrue(GraphLayout.parseInstance(depths).totalSize() <= drainedDepth.portableOwnedBytes)
        } finally {
            features.close()
            depths.close()
        }
    }

    @Test
    fun `depth pool refuses a third live slot and stale reads fail after replacement`() {
        val pool = DepthSamplesLeasePool(capacity = 16)
        val generation = SampleLeaseGeneration(7, 8, 9, 10)
        val first = requireNotNull(pool.tryAcquire(generation))
        val second = requireNotNull(pool.tryAcquire(generation))
        assertNull(pool.tryAcquire(generation))

        assertTrue(first.append(1, 2, 3_000, 255))
        assertEquals(1, first.samples.count)
        assertEquals(1, first.samples.xAt(0))
        first.close()
        first.close()

        val replacement = requireNotNull(pool.tryAcquire(generation))
        assertEquals(first.slotId, replacement.slotId)
        assertTrue(replacement.epoch != first.epoch)
        assertThrows(IllegalStateException::class.java) { first.samples.count }
        assertEquals(0, replacement.samples.count)

        second.close()
        replacement.close()
        pool.close()
        assertThrows(IllegalStateException::class.java) { replacement.samples.count }
    }

    @Test
    fun `feature pool deduplicates ids without allocating sample objects`() {
        val pool = FeatureSamplesLeasePool(capacity = 8)
        val generation = SampleLeaseGeneration(1, 2, 3, 4)
        val lease = requireNotNull(pool.tryAcquire(generation))
        assertTrue(lease.acceptId(17))
        assertFalse(lease.acceptId(17))
        assertTrue(lease.append(17, 0.1, 0.2, -0.3, 0.75))
        assertEquals(1, lease.samples.count)
        assertEquals(17, lease.samples.idAt(0))
        assertEquals(0.75, lease.samples.confidenceAt(0), 0.0)
        lease.close()
        assertEquals(0, pool.leasedCount())
    }

    @Test
    fun `packed feature copy preserves values rejection count and lease lifetime`() {
        val pool = FeatureSamplesLeasePool(capacity = 8)
        val lease = requireNotNull(pool.tryAcquire(SampleLeaseGeneration(1, 1, 1, 1)))
        val result = copyVisibilityFeatureSamplesPacked(
            sourceIds = java.nio.IntBuffer.wrap(intArrayOf(4, 4, 5)),
            sourcePoints = java.nio.FloatBuffer.wrap(floatArrayOf(
                1f, 2f, 3f, 0.8f,
                4f, 5f, 6f, 0.9f,
                7f, 8f, 9f, 0.2f,
            )),
            minimumFeatureConfidence = 0.3,
            lease = lease,
        )
        assertTrue(result is VisibilityFeatureSamplesCopyResult.Packed)
        assertEquals(1, lease.samples.count)
        // The duplicate id and the below-threshold sample are both source
        // rejections; the packed receipt preserves that complete count.
        assertEquals(2, lease.samples.rejectedCount)
        assertEquals(4, lease.samples.idAt(0))
        assertEquals(3.0, lease.samples.zWorldAt(0), 0.0)
        lease.close()
        assertThrows(IllegalStateException::class.java) { lease.samples.count }
    }
}
