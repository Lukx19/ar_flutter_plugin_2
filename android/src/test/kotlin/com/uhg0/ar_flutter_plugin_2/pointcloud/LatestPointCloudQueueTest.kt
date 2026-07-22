package com.uhg0.ar_flutter_plugin_2.pointcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestPointCloudQueueTest {
    @Test
    fun `keeps one in flight and replaces pending with latest`() {
        val queue = LatestPointCloudQueue()
        assertEquals(1L, queue.offer(sample(1))?.sequence)
        assertNull(queue.offer(sample(2)))
        assertNull(queue.offer(sample(3)))
        assertEquals(Pair(1, 1), queue.stateCounts())
        assertEquals(1L, queue.coalescedCount)
        assertEquals(3L, queue.acknowledge(1)?.sequence)
        assertEquals(Pair(1, 0), queue.stateCounts())
        assertNull(queue.fail(1))
        assertNull(queue.acknowledge(2))
        assertNull(queue.failCurrent())
        assertEquals(Pair(0, 0), queue.stateCounts())
    }

    @Test
    fun `clear is idempotent`() {
        val queue = LatestPointCloudQueue()
        queue.offer(sample(1))
        queue.offer(sample(2))
        queue.clear()
        queue.clear()
        assertEquals(Pair(0, 0), queue.stateCounts())
        assertEquals(0L, queue.coalescedCount)
    }

    private fun sample(sequence: Long) = PointCloudSample(
        sequence = sequence,
        timestampNs = sequence,
        ids = intArrayOf(sequence.toInt()),
        points = floatArrayOf(0f, 0f, -1f, 1f),
    )
}
