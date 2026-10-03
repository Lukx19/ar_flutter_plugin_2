package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.*
import org.junit.Test

class DepthRaySupercoverSkippingTest {
    @Test fun `empty segments preserve occupied cell order at signed face edge and corner ties`() {
        val starts = listOf(0.0, 50.0, -400.0, 400.0)
        val ends = listOf(-1_650.0, -400.0, 0.0, 400.0, 1_650.0)
        fun empty(x: Int, y: Int, z: Int) = Math.floorMod(x + 3 * y + 5 * z, 4) != 0
        fun occupied(voxel: Voxel) = !empty(Math.floorDiv(voxel.x, 4), Math.floorDiv(voxel.y, 4), Math.floorDiv(voxel.z, 4))
        for (start in starts) for (x in ends) for (y in ends) for (z in ends) {
            val camera = DepthPointMm(start, start, start)
            val endpoint = DepthPointMm(x, y, z)
            val original = mutableListOf<Voxel>()
            val expected = DepthRaySupercover.visit(camera, endpoint, 100_000, 65_536) { original += it; true }
            val skipped = mutableListOf<Voxel>()
            val actual = DepthRaySupercover.visitCoordinates(camera, endpoint, 100_000, 65_536, { a, b, c, size ->
                empty(Math.floorDiv(a * size, 4), Math.floorDiv(b * size, 4), Math.floorDiv(c * size, 4))
            }) { a, b, c ->
                skipped += Voxel(a, b, c); true
            }
            assertFalse(expected.truncated)
            assertFalse(actual.truncated)
            assertFalse(actual.arithmeticOverflow)
            assertEquals("$camera -> $endpoint", original.filter(::occupied), skipped.filter(::occupied))
        }
    }

    @Test fun `unknown certificates retain original traversal and empty segments spend the same work cap`() {
        val camera = DepthPointMm(50.0, 50.0, 50.0)
        val endpoint = DepthPointMm(50.0, 50.0, -3_050.0)
        val original = mutableListOf<Voxel>()
        val expected = DepthRaySupercover.visit(camera, endpoint, 100_000, 65_536) { original += it; true }
        val fallback = mutableListOf<Voxel>()
        val unknown = DepthRaySupercover.visitCoordinates(camera, endpoint, 100_000, 65_536, { _, _, _, _ -> false }) { x, y, z ->
            fallback += Voxel(x, y, z); true
        }
        assertEquals(expected, unknown)
        assertEquals(original, fallback)
        val bounded = DepthRaySupercover.visitCoordinates(camera, endpoint, 100_000, 3, { _, _, _, _ -> true }) { _, _, _ ->
            fail("certified empty cells must not reach the visitor"); true
        }
        assertTrue(bounded.truncated)
        assertEquals(0, bounded.visitedCells)
        assertEquals(3, bounded.emptyBlockVisits)
        assertEquals(3, bounded.workUnits)
    }

    @Test fun `adaptive descent skips empty children of a partially occupied parent at a corner tie`() {
        val occupied = Voxel(3, 3, 3)
        val cells = mutableListOf<Voxel>()
        val sizes = mutableSetOf<Int>()
        val result = DepthRaySupercover.visitCoordinates(
            DepthPointMm(50.0, 50.0, 50.0), DepthPointMm(350.0, 350.0, 350.0), 100_000, 65_536,
            { x, y, z, size ->
                sizes += size
                occupied.x !in x * size until (x + 1) * size ||
                    occupied.y !in y * size until (y + 1) * size ||
                    occupied.z !in z * size until (z + 1) * size
            },
        ) { x, y, z -> cells += Voxel(x, y, z); true }
        assertEquals(listOf(occupied), cells)
        assertEquals(setOf(4, 2, 1), sizes)
        assertEquals(1, result.visitedCells)
        assertTrue(result.emptyBlockVisits > 0)
        assertTrue(result.workUnits < 22)
    }
}
