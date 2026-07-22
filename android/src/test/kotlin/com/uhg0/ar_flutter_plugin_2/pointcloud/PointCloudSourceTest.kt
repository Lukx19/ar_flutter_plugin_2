package com.uhg0.ar_flutter_plugin_2.pointcloud

import java.nio.FloatBuffer
import java.nio.IntBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PointCloudSourceTest {
    @Test
    fun `ARCore adapter filters confidence copies rows and always closes`() {
        val acquired = FakeAcquiredPointCloud(
            idsArray = intArrayOf(7, 8, 9),
            pointsArray = floatArrayOf(
                1f, 2f, 3f, 0.9f,
                4f, 5f, 6f, 0.2f,
                Float.NaN, 8f, 9f, 1f,
            ),
            timestamp = 123L,
        )
        val source = ArCorePointCloudSource(0.3f, PointCloudAcquirer { acquired })

        val sample = source.acquire(null)!!

        assertTrue(acquired.closed)
        assertEquals(0L, sample.sequence)
        assertEquals(123L, sample.timestampNs)
        assertArrayEquals(intArrayOf(7), sample.ids)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 0.9f), sample.points, 0f)
        acquired.idsArray[0] = 99
        acquired.pointsArray[0] = 99f
        assertArrayEquals(intArrayOf(7), sample.ids)
        assertEquals(1f, sample.points[0])
    }

    @Test
    fun `ARCore adapter closes when sample construction fails`() {
        val acquired = FakeAcquiredPointCloud(
            idsArray = intArrayOf(1),
            pointsArray = floatArrayOf(0f, 0f, -1f, 1f),
            timestamp = -1L,
        )
        val source = ArCorePointCloudSource(0.3f, PointCloudAcquirer { acquired })

        assertThrows(IllegalArgumentException::class.java) { source.acquire(null) }
        assertTrue(acquired.closed)
    }

    @Test
    fun `ARCore adapter closes and returns null when every row is rejected`() {
        val acquired = FakeAcquiredPointCloud(
            idsArray = intArrayOf(1),
            pointsArray = floatArrayOf(0f, 0f, -1f, 0.1f),
            timestamp = 1L,
        )
        val source = ArCorePointCloudSource(0.3f, PointCloudAcquirer { acquired })

        assertNull(source.acquire(null))
        assertTrue(acquired.closed)
    }

    @Test
    fun `ARCore adapter counts point clouds containing only non-finite geometry`() {
        val acquired = FakeAcquiredPointCloud(
            idsArray = intArrayOf(1, 2),
            pointsArray = floatArrayOf(
                Float.NaN, 0f, -1f, 1f,
                0f, Float.POSITIVE_INFINITY, -1f, 1f,
            ),
            timestamp = 1L,
        )
        val source = ArCorePointCloudSource(0.3f, PointCloudAcquirer { acquired })

        assertNull(source.acquire(null))
        assertTrue(acquired.closed)
        assertEquals(1L, source.diagnostics().invalidPointCloudAcquisitions)
        assertEquals(2L, source.diagnostics().nonFiniteRejectedPoints)
    }

    @Test
    fun `repeated ARCore timestamp is released but does not advance sequence`() {
        val clouds = ArrayDeque(
            listOf(
                FakeAcquiredPointCloud(
                    intArrayOf(1), floatArrayOf(0f, 0f, -1f, 1f), 10L,
                ),
                FakeAcquiredPointCloud(
                    intArrayOf(2), floatArrayOf(0f, 0f, -1f, 1f), 10L,
                ),
                FakeAcquiredPointCloud(
                    intArrayOf(3), floatArrayOf(0f, 0f, -1f, 1f), 11L,
                ),
            ),
        )
        val source = ArCorePointCloudSource(0.3f, PointCloudAcquirer { clouds.removeFirst() })

        assertEquals(0L, source.acquire(null)!!.sequence)
        assertNull(source.acquire(null))
        assertEquals(1L, source.acquire(null)!!.sequence)
        assertTrue(clouds.isEmpty())
    }

    @Test
    fun `synthetic source is deterministic and monotonic`() {
        val source = SyntheticPointCloudSource()
        val first = source.acquire(null)
        val second = source.acquire(null)

        assertEquals(0L, first.sequence)
        assertEquals(1L, second.sequence)
        assertEquals(0L, first.timestampNs)
        assertEquals(100_000_000L, second.timestampNs)
        assertArrayEquals(first.ids, second.ids)
        assertArrayEquals(first.points, second.points, 0f)
    }
}

private class FakeAcquiredPointCloud(
    val idsArray: IntArray,
    val pointsArray: FloatArray,
    private val timestamp: Long,
) : AcquiredPointCloud {
    var closed = false
    override val ids: IntBuffer get() = IntBuffer.wrap(idsArray)
    override val points: FloatBuffer get() = FloatBuffer.wrap(pointsArray)
    override val timestampNs: Long get() = timestamp
    override fun close() {
        closed = true
    }
}
