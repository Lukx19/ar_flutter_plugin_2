package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class CoverageDirectionalBallGeometryTest {
    @Test fun `all normative bins create bounded independent colored spikes and complete topology`() {
        val input = input(1, size = 2f)
        val colors = IntArray(24) { 0xff000000.toInt() or (it * 0x070501) }
        val output = buffers(1, 1)
        CoverageDirectionalBallGeometry().write(input, CoverageDirectionalColors.copyOfColors(longArrayOf(1), colors), output)
        assertEquals(1, output.shapeCount)
        assertEquals(126, output.vertexCount)
        assertEquals(456, output.indexCount)
        assertEquals(0, output.pointCount)
        for (vertex in 0 until output.vertexCount) {
            val x = output.positions[vertex * 3].toDouble()
            val y = output.positions[vertex * 3 + 1].toDouble()
            val z = output.positions[vertex * 3 + 2].toDouble()
            assertTrue(x.isFinite() && y.isFinite() && z.isFinite())
            assertTrue(x * x + y * y + z * z <= 1.000001)
            assertEquals(if (vertex < 6) input.colorAt(0) else colors[(vertex - 6) / 5], output.colors[vertex])
        }
        val normative = intArrayOf(
            -9346, -21845, -22564, -22564, -21845, -9346, -22564, -21845, 9346, -9346, -21845, 22564,
            9346, -21845, 22564, 22564, -21845, 9346, 22564, -21845, -9346, 9346, -21845, -22564,
            -12539, 0, -30273, -30273, 0, -12539, -30273, 0, 12539, -12539, 0, 30273,
            12539, 0, 30273, 30273, 0, 12539, 30273, 0, -12539, 12539, 0, -30273,
            -9346, 21845, -22564, -22564, 21845, -9346, -22564, 21845, 9346, -9346, 21845, 22564,
            9346, 21845, 22564, 22564, 21845, 9346, 22564, 21845, -9346, 9346, 21845, -22564)
        for (bin in 0 until 24) {
            val qx = normative[bin * 3].toDouble(); val qy = normative[bin * 3 + 1].toDouble(); val qz = normative[bin * 3 + 2].toDouble()
            val length = sqrt(qx * qx + qy * qy + qz * qz)
            val apex = 6 + bin * 5 + 4
            for (axis in 0..2) {
                assertEquals(normative[bin * 3 + axis], CoverageDirectionalBallGeometry.directionComponentQ15(bin, axis))
                assertEquals(normative[bin * 3 + axis] / length, output.positions[apex * 3 + axis].toDouble(), 3e-8)
            }
        }
        assertClosedAndOutward(output)
    }

    @Test fun `world transform applies translation to centers and linear rotation to every viewing axis`() {
        val matrix = identity()
        matrix[0] = 0.0; matrix[1] = 1.0; matrix[4] = -1.0; matrix[5] = 0.0
        matrix[12] = 2.0; matrix[13] = -4.0; matrix[14] = 6.0
        val output = buffers(1, 1)
        val writer = CoverageDirectionalBallGeometry()
        val colors = CoverageDirectionalColors.unavailable(longArrayOf(1), 99)
        writer.write(input(1, size = 2f, matrix = matrix), colors, output)
        for (bin in 0 until 24) {
            val direction = direction(bin)
            val apex = 6 + bin * 5 + 4
            assertEquals(2.0 - direction[1], output.positions[apex * 3].toDouble(), 3e-7)
            assertEquals(-4.0 + direction[0], output.positions[apex * 3 + 1].toDouble(), 3e-7)
            assertEquals(6.0 + direction[2], output.positions[apex * 3 + 2].toDouble(), 3e-7)
        }
        // Affine reflection preserves closed topology and reverses triangle order.
        val baseline = buffers(1, 1)
        writer.write(input(1), colors, baseline)
        writer.write(input(1, matrix = identity().also { it[0] = -1.0 }), colors, output)
        for (triangle in 0 until 152) {
            assertEquals(baseline.indices[triangle * 3], output.indices[triangle * 3])
            assertEquals(baseline.indices[triangle * 3 + 1], output.indices[triangle * 3 + 2])
            assertEquals(baseline.indices[triangle * 3 + 2], output.indices[triangle * 3 + 1])
        }
        assertClosedAndOutward(output, reflected = true, radius = 0.5)
    }

    @Test fun `exact counts above uint16 and signed maximum saturate independently without truncation`() {
        val counts = LongArray(24) { when (it % 6) { 0 -> 0L; 1 -> 1L; 2 -> 2L; 3 -> 65535L; 4 -> 65536L; else -> Long.MAX_VALUE } }
        val colors = intArrayOf(10, 20, 30)
        val directions = CoverageDirectionalColors.fromExactObservationCounts(longArrayOf(1), counts, 10, 20, 30, 99)
        counts.fill(0)
        val output = buffers(1, 1)
        CoverageDirectionalBallGeometry().write(input(1), directions, output)
        for (bin in 0 until 24) {
            val expectedClass = minOf(bin % 6, 2)
            assertEquals(expectedClass, directions.observationClassAt(0, bin))
            for (vertex in 0 until 5) assertEquals(colors[expectedClass], output.colors[6 + bin * 5 + vertex])
        }
        assertEquals(32L, directions.ownedArrayBytes)
    }

    @Test fun `packed observation classes reject high reserved bits and negative exact counts`() {
        for (packed in longArrayOf(1L shl 48, Long.MIN_VALUE)) {
            expectRefusal { CoverageDirectionalColors.copyOfObservationClasses(longArrayOf(1), longArrayOf(packed), 10, 20, 30, 99) }
        }
        expectRefusal { CoverageDirectionalColors.fromExactObservationCounts(longArrayOf(1), LongArray(24).also { it[23] = -1 }, 10, 20, 30, 99) }
        expectRefusal { CoverageDirectionalColors.fromExactObservationCounts(longArrayOf(1), LongArray(24).also { it[0] = Long.MIN_VALUE }, 10, 20, 30, 99) }
        expectRefusal { CoverageDirectionalColors.copyOfColors(LongArray(257), IntArray(257 * 24)) }
        val packed = longArrayOf(2L shl 46 or 1L)
        val directions = CoverageDirectionalColors.copyOfObservationClasses(longArrayOf(1), packed, 10, 20, 30, 99)
        packed[0] = 0
        assertEquals(1, directions.observationClassAt(0, 0))
        assertEquals(2, directions.observationClassAt(0, 23))
        val partial = CoverageDirectionalColors.copyOfObservationClasses(longArrayOf(1), longArrayOf(3L shl 46), 10, 20, 30, 99)
        assertEquals(0, partial.observationClassAt(0, 0))
        assertEquals(3, partial.observationClassAt(0, 23))
        assertEquals(99, partial.colorAt(0, 23))
    }

    @Test fun `unavailable evidence remains distinguishable from zero count and explicit colors are owned`() {
        val ids = longArrayOf(1)
        val supplied = IntArray(24) { it + 100 }
        val direct = CoverageDirectionalColors.copyOfColors(ids, supplied)
        val unavailable = CoverageDirectionalColors.unavailable(ids, 99)
        val zero = CoverageDirectionalColors.copyOfObservationClasses(ids, longArrayOf(0), 10, 20, 30, 99)
        ids[0] = 2; supplied.fill(0)
        assertEquals(1L, direct.surfaceIdAt(0))
        for (bin in 0 until 24) {
            assertEquals(bin + 100, direct.colorAt(0, bin))
            assertEquals(-1, direct.observationClassAt(0, bin))
            assertEquals(3, unavailable.observationClassAt(0, bin))
            assertEquals(99, unavailable.colorAt(0, bin))
            assertEquals(0, zero.observationClassAt(0, bin))
            assertEquals(10, zero.colorAt(0, bin))
        }
        assertEquals(104L, direct.ownedArrayBytes)
        assertEquals(12L, unavailable.ownedArrayBytes)
    }

    @Test fun `empty maximum over cap and reduced LOD preserve ranked prefix and point remainder`() {
        val writer = CoverageDirectionalBallGeometry()
        val output = buffers(256, 257)
        writer.write(input(0), CoverageDirectionalColors.unavailable(LongArray(0), 99), output)
        assertEquals(0, output.indexCount)
        val ids = LongArray(256) { it + 1L }
        writer.write(input(257), CoverageDirectionalColors.unavailable(ids, 99), output)
        assertEquals(256, output.shapeCount)
        assertEquals(256 * 126, output.vertexCount)
        assertEquals(256 * 456, output.indexCount)
        assertEquals(1, output.pointCount)
        assertEquals(256, output.pointColors[0])
        for (index in 0 until output.indexCount) assertTrue(output.indices[index] in 0 until output.vertexCount)
        CoverageDirectionalBallGeometry(2).write(input(3), CoverageDirectionalColors.unavailable(longArrayOf(1, 2), 99), output)
        assertEquals(2, output.shapeCount)
        assertEquals(1, output.pointCount)
        assertEquals(2, output.pointColors[0])
        CoverageDirectionalBallGeometry(0).write(input(3), CoverageDirectionalColors.unavailable(LongArray(0), 99), output)
        assertEquals(0, output.shapeCount)
        assertEquals(3, output.pointCount)
    }

    @Test fun `mismatched immutable evidence or undersized caller storage refuses before output writes`() {
        val output = buffers(1, 1)
        output.colors.fill(77); output.indices.fill(78); output.pointColors.fill(79)
        val writer = CoverageDirectionalBallGeometry()
        expectRefusal { writer.write(input(1), CoverageDirectionalColors.unavailable(longArrayOf(2), 99), output) }
        expectRefusal { writer.write(input(1), CoverageDirectionalColors.unavailable(LongArray(0), 99), output) }
        assertTrue(output.colors.all { it == 77 } && output.indices.all { it == 78 } && output.pointColors[0] == 79)
        val small = buffers(0, 1)
        small.pointColors[0] = 80
        expectRefusal { writer.write(input(1), CoverageDirectionalColors.unavailable(longArrayOf(1), 99), small) }
        assertEquals(80, small.pointColors[0])
    }

    @Test fun `repeated writes leave buffer tails untouched and collapsed or overflowing balls fall back to points`() {
        val output = buffers(2, 2)
        output.positions.fill(91f); output.colors.fill(92); output.indices.fill(93)
        output.pointPositions.fill(94f); output.pointColors.fill(95)
        val writer = CoverageDirectionalBallGeometry()
        val directions = CoverageDirectionalColors.unavailable(longArrayOf(1), 99)
        val input = input(1)
        repeat(100) { writer.write(input, directions, output) }
        for (index in 378 until output.positions.size) assertEquals(91f, output.positions[index], 0f)
        for (index in 126 until output.colors.size) assertEquals(92, output.colors[index])
        for (index in 456 until output.indices.size) assertEquals(93, output.indices[index])
        assertArrayEquals(FloatArray(6) { 94f }, output.pointPositions, 0f)
        assertArrayEquals(IntArray(2) { 95 }, output.pointColors)
        writer.write(input(1, center = floatArrayOf(Float.MAX_VALUE, 0f, 0f)), directions, output)
        assertEquals(0, output.shapeCount)
        assertEquals(1, output.pointCount)
        assertTrue(output.pointPositions[0].isFinite())
        writer.write(input(1, center = floatArrayOf(Float.MAX_VALUE, 0f, 0f), size = Float.MAX_VALUE), directions, output)
        assertEquals(0, output.shapeCount)
        assertEquals(1, output.pointCount)
    }

    @Test fun `complete index vertex fallback input and constructor accounting respects unchanged owner limits`() {
        assertEquals(3840, CoverageDirectionalBallGeometry.GPU_BYTES_PER_BALL)
        assertEquals(1_966_080L, 2L * 256 * CoverageDirectionalBallGeometry.GPU_BYTES_PER_BALL)
        assertEquals(17, CoverageDirectionalBallGeometry.ballsPerUploadPage())
        assertEquals(16, CoverageDirectionalBallGeometry.ballsPerUploadPage(16))
        assertTrue(17 * 3840 <= 65536 && 18 * 3840 > 65536)
        val ids = LongArray(256) { it + 1L }
        val explicit = CoverageDirectionalColors.copyOfColors(ids, IntArray(256 * 24))
        val model = CoverageDirectionalBallGeometry.memory(256, 256, explicit.ownedArrayBytes)
        assertEquals(26624L, explicit.ownedArrayBytes)
        assertEquals(2_118_392L, model.modeledPeakBytes)
        val packed = CoverageDirectionalColors.copyOfObservationClasses(ids, LongArray(256), 10, 20, 30, 99)
        assertEquals(4112L, packed.ownedArrayBytes)
        assertEquals(2_073_368L, CoverageDirectionalBallGeometry.memory(256, 256, packed.ownedArrayBytes).modeledPeakBytes)
        // fromExactObservationCounts retains 24*8 source count bytes plus source IDs at the constructor seam.
        assertEquals(2_120_456L, CoverageDirectionalBallGeometry.memory(256, 256, packed.ownedArrayBytes, 256 * (8L + 24 * 8L)).modeledPeakBytes)
        val remaining = CoverageGeometryMemory.ACTIVE_LIMIT_BYTES - model.modeledPeakBytes
        assertTrue(model.fitsActive(remaining))
        assertFalse(model.fitsActive(remaining + 1))
        assertTrue(model.fitsTransition(CoverageGeometryMemory.COMBINED_LIMIT_BYTES - CoverageGeometryMemory.ACTIVE_LIMIT_BYTES, remaining))
        assertFalse(model.fitsTransition(Long.MAX_VALUE, 0))
        expectRefusal { CoverageDirectionalBallGeometry.memory(257, 257, 0) }
    }

    private fun assertClosedAndOutward(output: CoverageGeometryBuffers, reflected: Boolean = false, radius: Double = 1.0) {
        val edges = mutableMapOf<Pair<Int, Int>, Pair<Int, Int>>()
        for (triangle in 0 until output.indexCount / 3) {
            val indices = IntArray(3) { output.indices[triangle * 3 + it] }
            val u = DoubleArray(3) { output.positions[indices[1] * 3 + it].toDouble() - output.positions[indices[0] * 3 + it] }
            val v = DoubleArray(3) { output.positions[indices[2] * 3 + it].toDouble() - output.positions[indices[0] * 3 + it] }
            val normal = doubleArrayOf(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
            val mean = DoubleArray(3) { axis -> indices.sumOf { output.positions[it * 3 + axis].toDouble() } / 3 }
            val interior = if (triangle < 8) DoubleArray(3) else direction((triangle - 8) / 6).also {
                for (axis in 0..2) it[axis] *= 0.43 * radius
                if (reflected) it[0] = -it[0]
            }
            assertTrue(normal.indices.sumOf { normal[it] * (mean[it] - interior[it]) } > 0)
            for (edge in 0..2) {
                val a = indices[edge]; val b = indices[(edge + 1) % 3]
                val key = minOf(a, b) to maxOf(a, b)
                val previous = edges[key] ?: (0 to 0)
                edges[key] = previous.first + 1 to previous.second + if (a < b) 1 else -1
            }
        }
        assertTrue(edges.values.all { it.first == 2 && it.second == 0 })
    }

    private fun direction(bin: Int): DoubleArray {
        val q = DoubleArray(3) { CoverageDirectionalBallGeometry.directionComponentQ15(bin, it).toDouble() }
        val length = sqrt(q.sumOf { it * it })
        return DoubleArray(3) { q[it] / length }
    }

    private fun input(count: Int, center: FloatArray = FloatArray(3), size: Float = 1f,
        matrix: DoubleArray = identity()): CoverageCellGeometryInput = CoverageCellGeometryInput.copyOf(
        LongArray(count) { it + 1L }, FloatArray(count * 3) { center[it % 3] }, FloatArray(count) { size },
        IntArray(count), IntArray(count) { 255 }, IntArray(count) { it }, matrix)

    private fun identity(): DoubleArray = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }
    private fun buffers(shapes: Int, rows: Int): CoverageGeometryBuffers = CoverageGeometryBuffers(
        FloatArray(shapes * 126 * 3), IntArray(shapes * 126), IntArray(shapes * 456), FloatArray(rows * 3), IntArray(rows))

    private fun expectRefusal(action: () -> Unit) {
        try { action(); fail("Expected bounded-input refusal") } catch (_: IllegalArgumentException) { }
        catch (_: ArithmeticException) { }
    }
}
