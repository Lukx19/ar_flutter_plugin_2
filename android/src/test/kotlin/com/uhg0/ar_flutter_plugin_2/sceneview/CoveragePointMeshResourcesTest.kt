package com.uhg0.ar_flutter_plugin_2.sceneview

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoveragePointMeshResourcesTest {
    @Test
    fun `argb colors become normalized rgba vertex bytes`() {
        val buffer = CoveragePointMeshResources.rgbaBytes(
            intArrayOf(0x7F112233, 0xFFEEDDCC.toInt()),
        )

        assertArrayEquals(
            byteArrayOf(
                0x11, 0x22, 0x33, 0x7F,
                0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xFF.toByte(),
            ),
            buffer.toByteArray(),
        )
    }

    @Test
    fun `empty startup snapshot has a finite renderable bound`() {
        val box = CoveragePointMeshResources.DEFAULT_BOUNDING_BOX

        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f),
            box.getCenter(),
            0f,
        )
        assertArrayEquals(
            floatArrayOf(1_000f, 1_000f, 1_000f),
            box.getHalfExtent(),
            0f,
        )
    }

    @Test
    fun `empty cube mode replacement has a finite renderable bound`() {
        val box = CoverageCubeMeshResources.DEFAULT_BOUNDING_BOX

        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f),
            box.getCenter(),
            0f,
        )
        assertArrayEquals(
            floatArrayOf(1_000f, 1_000f, 1_000f),
            box.getHalfExtent(),
            0f,
        )
    }

    @Test
    fun `position uploads use float elements rather than byte count`() {
        assertEquals(
            12,
            CoveragePointMeshResources.positionBufferElementCount(pointCount = 4),
        )
    }

    @Test
    fun `cube geometry contains six faces over eight corners`() {
        val indices = CoverageCubeMeshResources.cubeIndices()
        val corners = CoverageCubeMeshResources.cubeCorners()

        assertEquals(36, indices.size)
        assertEquals(24, corners.size)
        assertEquals(0, indices.minOrNull())
        assertEquals(7, indices.maxOrNull())
        assertEquals(8, indices.toSet().size)

        for (offset in indices.indices step 3) {
            val a = indices[offset] * 3
            val b = indices[offset + 1] * 3
            val c = indices[offset + 2] * 3
            val abx = corners[b] - corners[a]
            val aby = corners[b + 1] - corners[a + 1]
            val abz = corners[b + 2] - corners[a + 2]
            val acx = corners[c] - corners[a]
            val acy = corners[c + 1] - corners[a + 1]
            val acz = corners[c + 2] - corners[a + 2]
            val normalX = aby * acz - abz * acy
            val normalY = abz * acx - abx * acz
            val normalZ = abx * acy - aby * acx
            val centerX = (corners[a] + corners[b] + corners[c]) / 3f
            val centerY = (corners[a + 1] + corners[b + 1] + corners[c + 1]) / 3f
            val centerZ = (corners[a + 2] + corners[b + 2] + corners[c + 2]) / 3f
            assertTrue(
                "triangle $offset must face outward",
                normalX * centerX + normalY * centerY + normalZ * centerZ > 0f,
            )
        }
    }

    @Test
    fun `cube outline contains all twelve edges exactly once`() {
        val indices = CoverageCubeMeshResources.cubeOutlineIndices()

        assertEquals(24, indices.size)
        assertEquals(0, indices.minOrNull())
        assertEquals(7, indices.maxOrNull())

        val edges = indices
            .toList()
            .chunked(2)
            .map { edge -> edge.sorted() }
            .toSet()
        assertEquals(12, edges.size)

        val degreeByCorner = indices.toList().groupingBy { it }.eachCount()
        for (corner in 0..7) {
            assertEquals(3, degreeByCorner[corner])
        }
    }

    @Test
    fun `repeated physical frame uploads reuse bounded direct buffers`() {
        val uploads = CoveragePointUploadBuffers(capacity = 4)
        val positionBuffer = uploads.positionBuffer
        val colorBuffer = uploads.colorBuffer

        uploads.write(
            positions = floatArrayOf(
                1f, 2f, 3f,
                4f, 5f, 6f,
            ),
            colors = intArrayOf(0x7F112233, 0xFF445566.toInt()),
        )
        uploads.write(
            positions = floatArrayOf(
                7f, 8f, 9f,
                10f, 11f, 12f,
                13f, 14f, 15f,
            ),
            colors = intArrayOf(
                0xFFEEDDCC.toInt(),
                0xFF010203.toInt(),
                0xFF040506.toInt(),
            ),
        )

        assertSame(positionBuffer, uploads.positionBuffer)
        assertSame(colorBuffer, uploads.colorBuffer)
        assertEquals(9, uploads.positionBuffer.remaining())
        assertEquals(12, uploads.colorBuffer.remaining())
        assertEquals(7f, uploads.positionBuffer.get(), 0f)
        assertArrayEquals(
            byteArrayOf(
                0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xFF.toByte(),
                0x01, 0x02, 0x03, 0xFF.toByte(),
                0x04, 0x05, 0x06, 0xFF.toByte(),
            ),
            uploads.colorBuffer.toByteArray(),
        )
    }

    @Test
    fun `range uploads reset limits before a later disjoint span`() {
        val uploads = CoveragePointUploadBuffers(capacity = 6)
        val positions = floatArrayOf(
            1f, 2f, 3f,
            4f, 5f, 6f,
            7f, 8f, 9f,
            10f, 11f, 12f,
            13f, 14f, 15f,
        )
        val colors = intArrayOf(
            0xFF010203.toInt(),
            0xFF040506.toInt(),
            0xFF070809.toInt(),
            0xFF0A0B0C.toInt(),
            0xFF0D0E0F.toInt(),
        )

        uploads.writeRange(positions, colors, startSlot = 0, endSlotExclusive = 2)
        uploads.writeRange(positions, colors, startSlot = 2, endSlotExclusive = 5)

        assertEquals(9, uploads.positionBuffer.remaining())
        assertEquals(7f, uploads.positionBuffer.get(), 0f)
        assertEquals(12, uploads.colorBuffer.remaining())
    }
}

private fun ByteBuffer.toByteArray(): ByteArray = ByteArray(remaining()).also(::get)
