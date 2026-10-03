package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.*
import org.junit.Test

class CoveragePlanarClipperTest {
    @Test fun `observed plane origin survives clipping without snapping to voxel center`() {
        val clipper = CoveragePlanarClipper()
        assertTrue(clipper.clip(-0.1, -0.2, -1.0, 0.1, -0.07, -0.14, -0.97, 0.0, 0.0, 1.0))
        assertEquals(4, clipper.vertexCount)
        for (vertex in 0 until clipper.vertexCount) assertEquals(-0.97, clipper.componentAt(vertex, 2), 1e-12)
        assertBounded(clipper, -0.1, -0.2, -1.0, 0.1)
    }

    @Test fun `diagonal hexagon has closed perimeter and positive normal winding`() {
        val clipper = CoveragePlanarClipper()
        assertTrue(clipper.clip(-0.5, -0.5, -0.5, 1.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
        assertEquals(6, clipper.vertexCount)
        assertBounded(clipper, -0.5, -0.5, -0.5, 1.0)
        var signedArea = 0.0
        for (vertex in 0 until clipper.vertexCount) {
            val next = (vertex + 1) % clipper.vertexCount
            val x = clipper.componentAt(vertex, 0); val y = clipper.componentAt(vertex, 1); val z = clipper.componentAt(vertex, 2)
            val xx = clipper.componentAt(next, 0); val yy = clipper.componentAt(next, 1); val zz = clipper.componentAt(next, 2)
            assertEquals(0.0, x + y + z, 1e-12)
            signedArea += (y * zz - z * yy) + (z * xx - x * zz) + (x * yy - y * xx)
        }
        assertTrue(signedArea > 0.0)
    }

    @Test fun `face corner and outside intersections do not invent polygons`() {
        val clipper = CoveragePlanarClipper()
        assertTrue(clipper.clip(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0))
        assertEquals(4, clipper.vertexCount)
        assertFalse(clipper.clip(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
        assertEquals(0, clipper.vertexCount)
        assertFalse(clipper.clip(0.0, 0.0, 0.0, 1.0, 2.0, 0.0, 0.0, 1.0, 0.0, 0.0))
        assertEquals(0, clipper.vertexCount)
        assertFalse(clipper.clip(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertFalse(clipper.clip(0.0, 0.0, 0.0, 1.0, Double.NaN, 0.0, 0.0, 1.0, 0.0, 0.0))
    }

    @Test fun `translated small voxels and repeated shapes preserve finite bounds`() {
        val clipper = CoveragePlanarClipper()
        repeat(10000) { iteration ->
            val sign = if (iteration % 2 == 0) 1.0 else -1.0
            assertTrue(clipper.clip(12345.0, -23456.0, 34567.0, 0.01,
                12345.005, -23455.995, 34567.005, sign, 0.7, -0.2))
            assertBounded(clipper, 12345.0, -23456.0, 34567.0, 0.01)
        }
    }

    private fun assertBounded(clipper: CoveragePlanarClipper, x: Double, y: Double, z: Double, size: Double) {
        for (vertex in 0 until clipper.vertexCount) for (axis in 0..2) {
            val minimum = if (axis == 0) x else if (axis == 1) y else z
            val value = clipper.componentAt(vertex, axis)
            assertTrue(value.isFinite() && value >= minimum - 1e-10 && value <= minimum + size + 1e-10)
        }
    }
}
