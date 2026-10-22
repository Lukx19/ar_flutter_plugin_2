package com.uhg0.ar_flutter_plugin_2.gridrenderer

import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFeaturePointBufferTest {
    private fun cloud(vararg xyzc: Float): FloatBuffer = FloatBuffer.wrap(xyzc)

    private fun copied(buffer: RawFeaturePointBuffer): FloatArray {
        val out = FloatBuffer.allocate(RawFeaturePointBuffer.MAX_POINTS * 3)
        val count = buffer.copyTo(out)
        return FloatArray(count * 3).also { out.get(it) }
    }

    @Test
    fun `copies nothing until the display mode asks for raw points`() {
        val buffer = RawFeaturePointBuffer()
        buffer.offer(cloud(1f, 2f, 3f, 0.9f), 1, 10L, 10L)
        assertEquals(0L, buffer.clouds)
        assertEquals(0, copied(buffer).size)

        buffer.wanted = true
        buffer.offer(cloud(1f, 2f, 3f, 0.9f), 1, 10L, 10L)
        assertEquals(1L, buffer.clouds)
        assertEquals(listOf(1f, 2f, 3f), copied(buffer).toList())
    }

    @Test
    fun `drops non-finite points, ignores a repeated cloud and caps the count`() {
        val buffer = RawFeaturePointBuffer(capacity = 2).apply { wanted = true }
        val versionBefore = buffer.version
        buffer.offer(cloud(Float.NaN, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 2f, 2f, 2f, 1f, 3f, 3f, 3f, 1f), 4, 5L, 5L)
        assertEquals(listOf(1f, 1f, 1f, 2f, 2f, 2f), copied(buffer).toList())
        assertEquals(4L, buffer.pointsIn)
        assertEquals(2L, buffer.pointsKept)
        val version = buffer.version
        assertTrue(version > versionBefore)

        // ARCore returns the same cloud for frames without a new one.
        buffer.offer(cloud(9f, 9f, 9f, 1f), 1, 5L, 6L)
        assertEquals(version, buffer.version)
        assertEquals(1L, buffer.clouds)
    }

    @Test
    fun `clear forgets the points and logs counts at most every two seconds`() {
        val lines = ArrayList<String>()
        val buffer = RawFeaturePointBuffer(log = { lines += it }).apply { wanted = true }
        var t = 0L
        repeat(90) { // 3 s at 30 Hz
            t += 33_333_333L
            buffer.offer(cloud(1f, 1f, 1f, 1f, 2f, 2f, 2f, 1f), 2, t, t)
        }
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("[grid-raw-points] clouds=1 pointsIn=2 pointsKept=2 last=2"))
        buffer.clear()
        assertEquals(0, copied(buffer).size)
        // Cleared means drawing is off: a late cloud is not copied.
        buffer.offer(cloud(3f, 3f, 3f, 1f), 1, t + 1, t + 1)
        assertEquals(0, copied(buffer).size)
    }
}
