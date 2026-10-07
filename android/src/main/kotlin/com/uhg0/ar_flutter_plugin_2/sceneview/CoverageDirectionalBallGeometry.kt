package com.uhg0.ar_flutter_plugin_2.sceneview

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Immutable, explicitly supplied per-direction input for at most 256 selected
 * balls. IDs must match the selected geometry prefix. Revision qualification
 * belongs to a future negotiated publication, not this host geometry seam.
 * No aggregate coverage or target bin is accepted as directional evidence.
 */
internal class CoverageDirectionalColors private constructor(
    private val ids: LongArray,
    private val directionColors: IntArray?,
    private val observationClasses: LongArray?,
    private val classColors: IntArray,
) {
    val count: Int get() = ids.size
    val ownedArrayBytes: Long get() = ids.size * Long.SIZE_BYTES.toLong() +
        (directionColors?.size ?: 0) * Int.SIZE_BYTES.toLong() +
        (observationClasses?.size ?: 0) * Long.SIZE_BYTES.toLong() + classColors.size * Int.SIZE_BYTES.toLong()

    fun surfaceIdAt(row: Int): Long = ids[row]

    /** 3 means unavailable; -1 means explicitly supplied colors with no observation scalar. */
    fun observationClassAt(row: Int, bin: Int): Int {
        require(row in 0 until count && bin in 0 until DIRECTION_COUNT)
        val classes = observationClasses ?: return if (directionColors == null) 3 else -1
        return (classes[row] ushr (bin * 2) and 3L).toInt()
    }

    fun colorAt(row: Int, bin: Int): Int {
        require(row in 0 until count && bin in 0 until DIRECTION_COUNT)
        val supplied = directionColors
        if (supplied != null) return supplied[row * DIRECTION_COUNT + bin]
        val classes = observationClasses ?: return classColors[0]
        return classColors[(classes[row] ushr (bin * 2) and 3L).toInt()]
    }

    companion object {
        const val DIRECTION_COUNT = 24
        const val MAX_BALLS = 256

        fun copyOfColors(surfaceIds: LongArray, argbPerDirection: IntArray): CoverageDirectionalColors {
            require(surfaceIds.size <= MAX_BALLS && argbPerDirection.size == surfaceIds.size * DIRECTION_COUNT)
            return CoverageDirectionalColors(surfaceIds.copyOf(), argbPerDirection.copyOf(), null, IntArray(0))
        }

        /** 48 low bits: 0 observations, 1 observation, 2-or-more observations, or 3 unavailable. */
        fun copyOfObservationClasses(surfaceIds: LongArray, packedClasses: LongArray,
            zeroColor: Int, oneColor: Int, twoOrMoreColor: Int, unavailableColor: Int): CoverageDirectionalColors {
            require(surfaceIds.size <= MAX_BALLS && packedClasses.size == surfaceIds.size)
            for (packed in packedClasses) {
                require(packed ushr 48 == 0L)
            }
            return CoverageDirectionalColors(surfaceIds.copyOf(), null, packedClasses.copyOf(),
                intArrayOf(zeroColor, oneColor, twoOrMoreColor, unavailableColor))
        }

        /** Host conversion accepts exact nonnegative signed-64 counts, including overflow above uint16. */
        fun fromExactObservationCounts(surfaceIds: LongArray, counts: LongArray,
            zeroColor: Int, oneColor: Int, twoOrMoreColor: Int, unavailableColor: Int): CoverageDirectionalColors {
            require(surfaceIds.size <= MAX_BALLS && counts.size == surfaceIds.size * DIRECTION_COUNT)
            for (count in counts) require(count >= 0L)
            val packed = LongArray(surfaceIds.size)
            for (row in surfaceIds.indices) for (bin in 0 until DIRECTION_COUNT) {
                packed[row] = packed[row] or (minOf(counts[row * DIRECTION_COUNT + bin], 2L) shl (bin * 2))
            }
            // Transfer the just-created packed array without a second copy.
            return CoverageDirectionalColors(surfaceIds.copyOf(), null, packed,
                intArrayOf(zeroColor, oneColor, twoOrMoreColor, unavailableColor))
        }

        fun unavailable(surfaceIds: LongArray, argbColor: Int): CoverageDirectionalColors {
            require(surfaceIds.size <= MAX_BALLS)
            return CoverageDirectionalColors(surfaceIds.copyOf(), null, null, intArrayOf(argbColor))
        }
    }
}

/**
 * Octahedral core plus 24 closed square pyramids. Each pyramid has its own five
 * colored vertices, preserving independent direction colors. The 24 axes are
 * the literal normative PictureViewBins24 Q15 group-space table, normalized
 * only to bound geometric radius; no platform-angle reconstruction is used.
 * Build this writer and freeze input outside frame callbacks; write allocates
 * no arrays or row objects. Only the ranked prefix is eligible for glyph LOD.
 */
internal class CoverageDirectionalBallGeometry(private val maximumBalls: Int = MAX_BALLS) {
    private val templateVertices = DoubleArray(VERTICES_PER_BALL * 3)
    private val templateIndices = IntArray(INDICES_PER_BALL)
    private val worldVertices = FloatArray(VERTICES_PER_BALL * 3)

    init {
        require(maximumBalls in 0..MAX_BALLS)
        buildTemplate()
    }

    fun write(input: CoverageCellGeometryInput, directions: CoverageDirectionalColors,
        output: CoverageGeometryBuffers) {
        val selected = minOf(input.count, maximumBalls)
        require(directions.count == selected)
        for (row in 0 until selected) require(directions.surfaceIdAt(row) == input.surfaceIdAt(row))
        output.requireCapacity(input.count, selected, VERTICES_PER_BALL, INDICES_PER_BALL)
        output.reset()
        for (row in 0 until input.count) {
            if (row >= selected || !prepareBall(input, row)) {
                output.appendPoint(input, row)
                continue
            }
            val firstVertex = output.vertexCount
            for (vertex in 0 until VERTICES_PER_BALL) {
                for (axis in 0..2) output.positions[output.vertexCount * 3 + axis] = worldVertices[vertex * 3 + axis]
                output.colors[output.vertexCount++] = if (vertex < CORE_VERTICES) input.colorAt(row)
                    else directions.colorAt(row, (vertex - CORE_VERTICES) / VERTICES_PER_SPIKE)
            }
            for (triangle in 0 until INDICES_PER_BALL / 3) {
                val offset = triangle * 3
                output.indices[output.indexCount++] = firstVertex + templateIndices[offset]
                output.indices[output.indexCount++] = firstVertex + templateIndices[offset + if (input.reversesWinding) 2 else 1]
                output.indices[output.indexCount++] = firstVertex + templateIndices[offset + if (input.reversesWinding) 1 else 2]
            }
            output.shapeCount++
        }
    }

    private fun prepareBall(input: CoverageCellGeometryInput, row: Int): Boolean {
        val radius = input.sizeAt(row).toDouble() * 0.5
        val x = input.centerAt(row, 0).toDouble()
        val y = input.centerAt(row, 1).toDouble()
        val z = input.centerAt(row, 2).toDouble()
        for (vertex in 0 until VERTICES_PER_BALL) {
            for (axis in 0..2) {
                val value = input.worldComponent(x + templateVertices[vertex * 3] * radius,
                    y + templateVertices[vertex * 3 + 1] * radius,
                    z + templateVertices[vertex * 3 + 2] * radius, axis).toFloat()
                if (!value.isFinite()) return false
                worldVertices[vertex * 3 + axis] = value
            }
        }
        if (!hasCoreVolume()) return false
        for (triangle in 0 until INDICES_PER_BALL / 3) {
            val offset = triangle * 3
            if (!hasTriangleArea(worldVertices, templateIndices[offset], templateIndices[offset + 1],
                    templateIndices[offset + 2])) return false
        }
        return true
    }

    private fun hasCoreVolume(): Boolean {
        val ax = worldVertices[0].toDouble() - worldVertices[3]
        val ay = worldVertices[1].toDouble() - worldVertices[4]
        val az = worldVertices[2].toDouble() - worldVertices[5]
        val bx = worldVertices[6].toDouble() - worldVertices[9]
        val by = worldVertices[7].toDouble() - worldVertices[10]
        val bz = worldVertices[8].toDouble() - worldVertices[11]
        val cx = worldVertices[12].toDouble() - worldVertices[15]
        val cy = worldVertices[13].toDouble() - worldVertices[16]
        val cz = worldVertices[14].toDouble() - worldVertices[17]
        val volume = ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)
        return volume.isFinite() && volume != 0.0
    }

    private fun buildTemplate() {
        for (axis in 0..2) {
            templateVertices[(axis * 2) * 3 + axis] = CORE_RADIUS
            templateVertices[(axis * 2 + 1) * 3 + axis] = -CORE_RADIUS
        }
        var index = 0
        for (x in 0..1) for (y in 0..1) for (z in 0..1) {
            val first = x
            val second = 2 + y
            val third = 4 + z
            templateIndices[index++] = first
            templateIndices[index++] = if ((x + y + z) % 2 == 0) second else third
            templateIndices[index++] = if ((x + y + z) % 2 == 0) third else second
        }
        for (bin in 0 until DIRECTION_COUNT) {
            val qx = directionComponentQ15(bin, 0).toDouble()
            val qy = directionComponentQ15(bin, 1).toDouble()
            val qz = directionComponentQ15(bin, 2).toDouble()
            val length = sqrt(qx * qx + qy * qy + qz * qz)
            val dx = qx / length; val dy = qy / length; val dz = qz / length
            // Choose least aligned reference axis, then u cross v = direction.
            val referenceAxis = if (abs(dx) <= abs(dy) && abs(dx) <= abs(dz)) 0 else if (abs(dy) <= abs(dz)) 1 else 2
            val rawUx = if (referenceAxis == 1) dz else if (referenceAxis == 2) -dy else 0.0
            val rawUy = if (referenceAxis == 2) dx else if (referenceAxis == 0) -dz else 0.0
            val rawUz = if (referenceAxis == 0) dy else if (referenceAxis == 1) -dx else 0.0
            val tangentLength = sqrt(rawUx * rawUx + rawUy * rawUy + rawUz * rawUz)
            val ux = rawUx / tangentLength; val uy = rawUy / tangentLength; val uz = rawUz / tangentLength
            val vx = dy * uz - dz * uy; val vy = dz * ux - dx * uz; val vz = dx * uy - dy * ux
            val first = CORE_VERTICES + bin * VERTICES_PER_SPIKE
            for (corner in 0..3) {
                val u = if (corner == 0 || corner == 3) -SPIKE_HALF_WIDTH else SPIKE_HALF_WIDTH
                val v = if (corner <= 1) -SPIKE_HALF_WIDTH else SPIKE_HALF_WIDTH
                templateVertices[(first + corner) * 3] = dx * SPIKE_BASE_RADIUS + ux * u + vx * v
                templateVertices[(first + corner) * 3 + 1] = dy * SPIKE_BASE_RADIUS + uy * u + vy * v
                templateVertices[(first + corner) * 3 + 2] = dz * SPIKE_BASE_RADIUS + uz * u + vz * v
            }
            templateVertices[(first + 4) * 3] = dx
            templateVertices[(first + 4) * 3 + 1] = dy
            templateVertices[(first + 4) * 3 + 2] = dz
            // Back face points opposite the spike; side faces point out of each pyramid.
            templateIndices[index++] = first; templateIndices[index++] = first + 2; templateIndices[index++] = first + 1
            templateIndices[index++] = first; templateIndices[index++] = first + 3; templateIndices[index++] = first + 2
            for (corner in 0..3) {
                templateIndices[index++] = first + corner
                templateIndices[index++] = first + (corner + 1) % 4
                templateIndices[index++] = first + 4
            }
        }
        check(index == INDICES_PER_BALL)
    }

    companion object {
        const val MAX_BALLS = 256
        const val DIRECTION_COUNT = 24
        const val CORE_VERTICES = 6
        const val VERTICES_PER_SPIKE = 5
        const val VERTICES_PER_BALL = CORE_VERTICES + DIRECTION_COUNT * VERTICES_PER_SPIKE
        const val INDICES_PER_BALL = 24 + DIRECTION_COUNT * 18
        const val GPU_BYTES_PER_BALL = VERTICES_PER_BALL * 16 + INDICES_PER_BALL * Int.SIZE_BYTES
        const val WORKSPACE_ARRAY_BYTES = VERTICES_PER_BALL * 3 * (Double.SIZE_BYTES + Float.SIZE_BYTES) +
            INDICES_PER_BALL * Int.SIZE_BYTES + DIRECTION_COUNT * 3 * Int.SIZE_BYTES
        private const val CORE_RADIUS = 0.35
        private const val SPIKE_BASE_RADIUS = 0.24
        private const val SPIKE_HALF_WIDTH = 0.08
        private val DIRECTIONS_Q15 = intArrayOf(
            -9346, -21845, -22564, -22564, -21845, -9346, -22564, -21845, 9346, -9346, -21845, 22564,
            9346, -21845, 22564, 22564, -21845, 9346, 22564, -21845, -9346, 9346, -21845, -22564,
            -12539, 0, -30273, -30273, 0, -12539, -30273, 0, 12539, -12539, 0, 30273,
            12539, 0, 30273, 30273, 0, 12539, 30273, 0, -12539, 12539, 0, -30273,
            -9346, 21845, -22564, -22564, 21845, -9346, -22564, 21845, 9346, -9346, 21845, 22564,
            9346, 21845, 22564, 22564, 21845, 9346, 22564, 21845, -9346, 9346, 21845, -22564,
        )

        fun directionComponentQ15(bin: Int, axis: Int): Int {
            require(bin in 0 until DIRECTION_COUNT && axis in 0..2)
            return DIRECTIONS_Q15[bin * 3 + axis]
        }

        fun memory(shapeCapacity: Int, selectedRowCapacity: Int, directionInputBytes: Long,
            directionConstructionOverlapBytes: Long = directionInputBytes): CoverageGeometryMemory {
            require(shapeCapacity in 0..MAX_BALLS && selectedRowCapacity in shapeCapacity..CoverageCellGeometryInput.MAX_SELECTED_ROWS)
            require(directionInputBytes >= 0 && directionConstructionOverlapBytes >= 0)
            val gpu = shapeCapacity * GPU_BYTES_PER_BALL.toLong() + selectedRowCapacity * 16L
            val input = CoverageCellGeometryInput.inputArrayBytes(selectedRowCapacity)
            return CoverageGeometryMemory(gpu, gpu + CoverageGeometryMemory.ORDINARY_PAGE_BYTES,
                Math.addExact(input, directionInputBytes), WORKSPACE_ARRAY_BYTES.toLong(),
                Math.addExact(input, directionConstructionOverlapBytes))
        }

        fun ballsPerUploadPage(metadataBytesPerBall: Int = 0): Int = CoverageGeometryMemory.uploadRowsPerPage(
            VERTICES_PER_BALL * 16, INDICES_PER_BALL * Int.SIZE_BYTES, metadataBytesPerBall)
    }
}
