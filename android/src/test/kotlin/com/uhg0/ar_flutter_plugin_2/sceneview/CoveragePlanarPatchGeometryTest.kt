package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CoveragePlanarPatchGeometryTest {
    @Test fun `normal aligned patch outer width is seventy percent with an opaque black border`() {
        val output = buffers(1, 1)
        CoveragePlanarPatchGeometry().write(input(1, packedNormal = 0, size = 2f), output)
        assertEquals(12, output.vertexCount)
        assertEquals(30, output.indexCount)
        val xs = (0 until 4).map { output.positions[it * 3] }
        val ys = (0 until 4).map { output.positions[it * 3 + 1] }
        assertEquals(1.4f, xs.maxOrNull()!! - xs.minOrNull()!!, 1e-6f)
        assertEquals(1.4f, ys.maxOrNull()!! - ys.minOrNull()!!, 1e-6f)
        assertOpaqueBorderAndFill(output, 4, 0)
        repeat(12) { assertEquals(0f, output.positions[it * 3 + 2], 0f) }
        assertIndices(output)
    }

    @Test fun `six signed axis normals produce contained patches with matching winding`() {
        val normals = intArrayOf(0x7f00, 0x8100, 0x007f, 0x0081, 0x0000, 0x7f7f)
        for (axis in 0..2) for (sign in 0..1) {
            val normal = normals[axis * 2 + sign]
            val input = input(1, normal, center = floatArrayOf(2f, -3f, 4f), size = 2f)
            val output = buffers(1, 1)
            CoveragePlanarPatchGeometry().write(input, output)
            assertEquals(1, output.shapeCount)
            assertEquals(12, output.vertexCount)
            assertEquals(30, output.indexCount)
            assertEquals(0, output.pointCount)
            for (vertex in 0 until output.vertexCount) {
                for (component in 0..2) {
                    val center = input.centerAt(0, component)
                    val value = output.positions[vertex * 3 + component]
                    assertTrue(value.isFinite() && value >= center - 0.7f && value <= center + 0.7f)
                    if (component == axis) assertEquals(center, value, 0f)
                }
                assertEquals(if (vertex < 8) 0xff000000.toInt() else input.colorAt(0), output.colors[vertex])
            }
            assertOpaqueBorderAndFill(output, 4, input.colorAt(0))
            for (triangle in 0 until output.indexCount / 3) {
                assertTrue(triangleNormal(output, triangle)[axis] * (if (sign == 0) 1 else -1) > 0)
            }
        }
    }

    @Test fun `diagonal normal patch has a coplanar black ring and bounded inner fill`() {
        val output = buffers(1, 1)
        CoveragePlanarPatchGeometry().write(input(1, 0x2a2a), output)
        assertEquals(18, output.vertexCount)
        assertEquals(48, output.indexCount)
        assertOpaqueBorderAndFill(output, 6, 0)
        // Decoded octahedral vector is proportional to (42, 42, 43).
        for (vertex in 0 until output.vertexCount) {
            val x = output.positions[vertex * 3]
            val y = output.positions[vertex * 3 + 1]
            val z = output.positions[vertex * 3 + 2]
            assertEquals(0.0, 42 * x.toDouble() + 42 * y.toDouble() + 43 * z.toDouble(), 2e-6)
            assertTrue(abs(x) <= 0.5f && abs(y) <= 0.5f && abs(z) <= 0.5f)
        }
        assertIndices(output)
        for (triangle in 0 until output.indexCount / 3) {
            val normal = triangleNormal(output, triangle)
            assertTrue(normal[0] * 42 + normal[1] * 42 + normal[2] * 43 > 0)
        }
    }

    @Test fun `rotation translation scale and reflection transform geometry without changing its local plane`() {
        val transform = identity()
        transform[0] = 0.0; transform[1] = 2.0
        transform[4] = -3.0; transform[5] = 0.0
        transform[10] = 4.0
        transform[12] = 10.0; transform[13] = 20.0; transform[14] = -5.0
        val output = buffers(1, 1)
        CoveragePlanarPatchGeometry().write(input(1, 0x7f00, matrix = transform), output)
        for (vertex in 0 until output.vertexCount) {
            assertEquals(20f, output.positions[vertex * 3 + 1], 0f)
            assertTrue(output.positions[vertex * 3] in 8.5f..11.5f)
            assertTrue(output.positions[vertex * 3 + 2] in -7f..-3f)
        }
        for (triangle in 0 until 2) assertTrue(triangleNormal(output, triangle)[1] > 0)
        transform[1] = -2.0
        CoveragePlanarPatchGeometry().write(input(1, 0x7f00, matrix = transform), output)
        for (triangle in 0 until 2) assertTrue(triangleNormal(output, triangle)[1] < 0)
    }

    @Test fun `unknown low confidence and reserved normals become deterministic points without refilling prefix`() {
        val normals = intArrayOf(0x8000, 0x0080, -1, 65536, 0, 0, 0)
        val confidences = intArrayOf(255, 255, 255, 255, 0, 4, 255)
        val input = CoverageCellGeometryInput.copyOf(LongArray(7) { it + 1L }, FloatArray(21), FloatArray(7) { 1f },
            normals, confidences, IntArray(7) { 100 + it }, identity())
        val output = buffers(6, 7)
        CoveragePlanarPatchGeometry(maximumPatches = 6, minimumNormalConfidence = 5).write(input, output)
        assertEquals(0, output.shapeCount)
        assertEquals(7, output.pointCount)
        assertArrayEquals(IntArray(7) { 100 + it }, output.pointColors)
        assertArrayEquals(FloatArray(21), output.pointPositions, 0f)
    }

    @Test fun `empty maximum and over capacity selections obey stable prefix LOD`() {
        val writer = CoveragePlanarPatchGeometry()
        val output = buffers(CoveragePlanarPatchGeometry.MAX_PATCHES, CoveragePlanarPatchGeometry.MAX_PATCHES + 1)
        writer.write(input(0), output)
        assertEquals(0, output.vertexCount)
        assertEquals(0, output.pointCount)
        val count = CoveragePlanarPatchGeometry.MAX_PATCHES + 1
        writer.write(input(count, 0x2a2a), output)
        assertEquals(4096, output.shapeCount)
        assertEquals(4096 * 18, output.vertexCount)
        assertEquals(4096 * 48, output.indexCount)
        assertEquals(1, output.pointCount)
        assertEquals(4096, output.pointColors[0])
        assertIndices(output)
        val pointsOnly = buffers(0, 2)
        CoveragePlanarPatchGeometry(0).write(input(2), pointsOnly)
        assertEquals(0, pointsOnly.shapeCount)
        assertEquals(2, pointsOnly.pointCount)
    }

    @Test fun `immutable input owns geometry style normal and transform independently of caller arrays`() {
        val ids = longArrayOf(19)
        val centers = floatArrayOf(2f, 3f, 4f)
        val sizes = floatArrayOf(2f)
        val normals = intArrayOf(0)
        val confidences = intArrayOf(255)
        val colors = intArrayOf(0xff123456.toInt())
        val transform = identity()
        val input = CoverageCellGeometryInput.copyOf(ids, centers, sizes, normals, confidences, colors, transform)
        ids[0] = 21; centers.fill(99f); sizes[0] = 100f; normals[0] = 0x8000
        confidences[0] = 0; colors[0] = 0; transform[14] = 100.0
        val output = buffers(1, 1)
        CoveragePlanarPatchGeometry().write(input, output)
        assertEquals(19L, input.surfaceIdAt(0))
        assertEquals(1, output.shapeCount)
        for (vertex in 0 until output.vertexCount) {
            assertEquals(4f, output.positions[vertex * 3 + 2], 0f)
            assertEquals(if (vertex < 8) 0xff000000.toInt() else 0xff123456.toInt(), output.colors[vertex])
        }
        assertEquals(164L, input.ownedArrayBytes)
    }

    @Test fun `invalid admission is refused and unrepresentable float patches use finite point fallback`() {
        expectRefusal { input(1, center = floatArrayOf(Float.NaN, 0f, 0f)) }
        expectRefusal { input(1, size = 0f) }
        expectRefusal { input(1, matrix = identity().also { it[3] = 1.0 }) }
        expectRefusal { input(1, matrix = identity().also { it[0] = 0.0 }) }
        expectRefusal { input(1, matrix = identity().also { it[12] = Double.MAX_VALUE }) }
        val output = buffers(1, 1)
        val writer = CoveragePlanarPatchGeometry()
        writer.write(input(1, center = floatArrayOf(Float.MAX_VALUE, 0f, 0f)), output)
        assertEquals(0, output.shapeCount)
        assertEquals(1, output.pointCount)
        assertTrue(output.pointPositions[0].isFinite())
        writer.write(input(1, center = floatArrayOf(Float.MAX_VALUE, 0f, 0f), size = Float.MAX_VALUE), output)
        assertEquals(0, output.shapeCount)
        assertEquals(1, output.pointCount)
    }

    @Test fun `repeated writes retain caller buffers and tails while insufficient capacity writes nothing`() {
        val output = buffers(2, 2)
        output.positions.fill(91f); output.colors.fill(92); output.indices.fill(93)
        output.pointPositions.fill(94f); output.pointColors.fill(95)
        val writer = CoveragePlanarPatchGeometry()
        val input = input(1)
        repeat(100) { writer.write(input, output) }
        assertEquals(12, output.vertexCount)
        for (index in 36 until output.positions.size) assertEquals(91f, output.positions[index], 0f)
        for (index in 12 until output.colors.size) assertEquals(92, output.colors[index])
        for (index in 30 until output.indices.size) assertEquals(93, output.indices[index])
        assertArrayEquals(FloatArray(6) { 94f }, output.pointPositions, 0f)
        assertArrayEquals(IntArray(2) { 95 }, output.pointColors)
        val small = buffers(0, 1)
        small.pointColors[0] = 96
        expectRefusal { writer.write(input, small) }
        assertEquals(96, small.pointColors[0])
        assertEquals(0, small.pointCount)
    }

    @Test fun `complete upload and owner arithmetic respects page and active transition ceilings`() {
        assertEquals(480, CoveragePlanarPatchGeometry.GPU_BYTES_PER_PATCH)
        assertEquals(3_932_160L, 2L * 4096 * CoveragePlanarPatchGeometry.GPU_BYTES_PER_PATCH)
        assertEquals(136, CoveragePlanarPatchGeometry.patchesPerUploadPage())
        assertEquals(134, CoveragePlanarPatchGeometry.patchesPerUploadPage(8))
        assertTrue(136 * 480 <= 65536 && 137 * 480 > 65536)
        val model = CoveragePlanarPatchGeometry.memory(4096, 4096)
        assertEquals(4_424_216L, model.modeledPeakBytes)
        assertEquals(147_584L, model.inputBytes)
        assertEquals(147_584L, model.constructionOverlapBytes)
        val remaining = CoverageGeometryMemory.ACTIVE_LIMIT_BYTES - model.modeledPeakBytes
        assertTrue(model.fitsActive(remaining))
        assertFalse(model.fitsActive(remaining + 1))
        assertFalse(model.fitsActive(Long.MAX_VALUE))
        assertTrue(model.fitsTransition(CoverageGeometryMemory.COMBINED_LIMIT_BYTES - CoverageGeometryMemory.ACTIVE_LIMIT_BYTES, remaining))
        assertFalse(model.fitsTransition(CoverageGeometryMemory.COMBINED_LIMIT_BYTES, 0))
        expectRefusal { CoverageGeometryMemory.uploadRowsPerPage(Int.MAX_VALUE, 1) }
    }

    private fun input(count: Int, packedNormal: Int = 0, center: FloatArray = FloatArray(3), size: Float = 1f,
        matrix: DoubleArray = identity()): CoverageCellGeometryInput = CoverageCellGeometryInput.copyOf(
        LongArray(count) { it + 1L }, FloatArray(count * 3) { center[it % 3] }, FloatArray(count) { size },
        IntArray(count) { packedNormal }, IntArray(count) { 255 }, IntArray(count) { it }, matrix)

    private fun identity(): DoubleArray = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }
    private fun buffers(shapes: Int, rows: Int): CoverageGeometryBuffers = CoverageGeometryBuffers(
        FloatArray(shapes * CoveragePlanarPatchGeometry.MAX_VERTICES_PER_PATCH * 3),
        IntArray(shapes * CoveragePlanarPatchGeometry.MAX_VERTICES_PER_PATCH),
        IntArray(shapes * CoveragePlanarPatchGeometry.MAX_INDICES_PER_PATCH), FloatArray(rows * 3), IntArray(rows))

    private fun triangleNormal(output: CoverageGeometryBuffers, triangle: Int): DoubleArray {
        val a = output.indices[triangle * 3]; val b = output.indices[triangle * 3 + 1]; val c = output.indices[triangle * 3 + 2]
        val u = DoubleArray(3) { output.positions[b * 3 + it].toDouble() - output.positions[a * 3 + it] }
        val v = DoubleArray(3) { output.positions[c * 3 + it].toDouble() - output.positions[a * 3 + it] }
        return doubleArrayOf(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
    }

    private fun assertOpaqueBorderAndFill(output: CoverageGeometryBuffers, boundaryCount: Int, fillColor: Int) {
        for (index in 0 until boundaryCount * 6) {
            assertEquals(CoveragePlanarPatchGeometry.BORDER_ARGB, output.colors[output.indices[index]])
        }
        for (index in boundaryCount * 6 until output.indexCount) {
            assertEquals(fillColor, output.colors[output.indices[index]])
        }
        for (vertex in 0 until boundaryCount) for (axis in 0..2) {
            assertEquals(output.positions[(boundaryCount + vertex) * 3 + axis],
                output.positions[(boundaryCount * 2 + vertex) * 3 + axis], 0f)
        }
    }

    private fun assertIndices(output: CoverageGeometryBuffers) {
        for (index in 0 until output.indexCount) assertTrue(output.indices[index] in 0 until output.vertexCount)
    }

    private fun expectRefusal(action: () -> Unit) {
        try { action(); fail("Expected bounded-input refusal") } catch (_: IllegalArgumentException) { }
        catch (_: ArithmeticException) { }
    }
}
