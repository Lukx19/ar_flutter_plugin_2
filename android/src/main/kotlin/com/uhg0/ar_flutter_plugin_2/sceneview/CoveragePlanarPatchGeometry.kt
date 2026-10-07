package com.uhg0.ar_flutter_plugin_2.sceneview

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Owned geometry for an already ranked, bounded selection. Create away from
 * frame callbacks; no array is exposed and caller mutations cannot change it.
 * Positions are group-space voxel centers, not observed surface origins.
 */
internal class CoverageCellGeometryInput private constructor(
    private val ids: LongArray,
    private val centers: FloatArray,
    private val sizes: FloatArray,
    private val normals: IntArray,
    private val confidences: IntArray,
    private val colors: IntArray,
    private val transform: DoubleArray,
) {
    val count: Int get() = ids.size
    val ownedArrayBytes: Long get() = inputArrayBytes(count)
    val reversesWinding: Boolean

    init {
        val determinant = transform[0] * (transform[5] * transform[10] - transform[9] * transform[6]) -
            transform[4] * (transform[1] * transform[10] - transform[9] * transform[2]) +
            transform[8] * (transform[1] * transform[6] - transform[5] * transform[2])
        require(determinant.isFinite() && determinant != 0.0)
        reversesWinding = determinant < 0.0
        for (row in 0 until count) for (axis in 0..2) {
            require(worldComponent(centerAt(row, 0).toDouble(), centerAt(row, 1).toDouble(),
                centerAt(row, 2).toDouble(), axis).toFloat().isFinite())
        }
    }

    fun surfaceIdAt(row: Int): Long = ids[row]
    fun centerAt(row: Int, axis: Int): Float = centers[row * 3 + axis]
    fun sizeAt(row: Int): Float = sizes[row]
    fun packedNormalAt(row: Int): Int = normals[row]
    fun confidenceAt(row: Int): Int = confidences[row]
    fun colorAt(row: Int): Int = colors[row]

    /** Translation applies to positions only. Linear directions use differences. */
    fun worldComponent(x: Double, y: Double, z: Double, axis: Int): Double =
        transform[axis] * x + transform[4 + axis] * y + transform[8 + axis] * z + transform[12 + axis]

    companion object {
        const val MAX_SELECTED_ROWS = 20_000

        fun inputArrayBytes(count: Int): Long {
            require(count in 0..MAX_SELECTED_ROWS)
            // ID 8 + center 12 + cell size 4 + packed normal 4 + confidence 4 + color 4; matrix 128.
            return count * 36L + 16L * Double.SIZE_BYTES
        }

        fun copyOf(
            surfaceIds: LongArray,
            groupCenters: FloatArray,
            cellSizes: FloatArray,
            packedNormals: IntArray,
            normalConfidences: IntArray,
            argbColors: IntArray,
            worldFromGroup: DoubleArray,
        ): CoverageCellGeometryInput {
            val count = surfaceIds.size
            require(count in 0..MAX_SELECTED_ROWS)
            require(groupCenters.size == count * 3 && cellSizes.size == count &&
                packedNormals.size == count && normalConfidences.size == count && argbColors.size == count)
            require(worldFromGroup.size == 16)
            for (value in groupCenters) require(value.isFinite())
            for (value in cellSizes) require(value.isFinite() && value > 0f)
            for (value in normalConfidences) require(value in 0..255)
            for (value in worldFromGroup) require(value.isFinite())
            require(abs(worldFromGroup[3]) <= 1e-9 && abs(worldFromGroup[7]) <= 1e-9 &&
                abs(worldFromGroup[11]) <= 1e-9 && abs(worldFromGroup[15] - 1.0) <= 1e-9)
            return CoverageCellGeometryInput(surfaceIds.copyOf(), groupCenters.copyOf(), cellSizes.copyOf(),
                packedNormals.copyOf(), normalConfidences.copyOf(), argbColors.copyOf(), worldFromGroup.copyOf())
        }
    }
}

/** Caller-owned fixed arrays. Counts delimit valid output; unused tails are untouched. */
internal class CoverageGeometryBuffers(
    val positions: FloatArray,
    val colors: IntArray,
    val indices: IntArray,
    val pointPositions: FloatArray,
    val pointColors: IntArray,
) {
    var vertexCount = 0
        internal set
    var indexCount = 0
        internal set
    var shapeCount = 0
        internal set
    var pointCount = 0
        private set

    init {
        require(positions.size == colors.size * 3 && pointPositions.size == pointColors.size * 3)
    }

    fun requireCapacity(inputCount: Int, shapes: Int, verticesPerShape: Int, indicesPerShape: Int) {
        require(colors.size >= shapes * verticesPerShape && indices.size >= shapes * indicesPerShape)
        // Any selected shape can take point fallback, so reserve for every input row.
        require(pointColors.size >= inputCount)
    }

    fun reset() { vertexCount = 0; indexCount = 0; shapeCount = 0; pointCount = 0 }

    fun appendPoint(input: CoverageCellGeometryInput, row: Int) {
        val x = input.centerAt(row, 0).toDouble()
        val y = input.centerAt(row, 1).toDouble()
        val z = input.centerAt(row, 2).toDouble()
        for (axis in 0..2) pointPositions[pointCount * 3 + axis] = input.worldComponent(x, y, z, axis).toFloat()
        pointColors[pointCount] = input.colorAt(row)
        pointCount++
    }
}

/** Primitive ownership model, not measured heap or a production admission receipt. */
internal data class CoverageGeometryMemory(
    val gpuBytes: Long,
    val stagingBytes: Long,
    val inputBytes: Long,
    val workspaceBytes: Long,
    val constructionOverlapBytes: Long,
) {
    init {
        require(gpuBytes >= 0 && stagingBytes >= 0 && inputBytes >= 0 && workspaceBytes >= 0 &&
            constructionOverlapBytes >= 0)
    }

    val modeledPeakBytes: Long get() = Math.addExact(Math.addExact(Math.addExact(gpuBytes, stagingBytes),
        Math.addExact(inputBytes, workspaceBytes)), constructionOverlapBytes)

    /** Other owner bytes must include descriptors, selectors, materials, callbacks and object overhead. */
    fun fitsActive(otherOwnerBytes: Long): Boolean {
        require(otherOwnerBytes >= 0)
        return otherOwnerBytes <= ACTIVE_LIMIT_BYTES && modeledPeakBytes <= ACTIVE_LIMIT_BYTES - otherOwnerBytes
    }

    fun fitsTransition(otherGenerationBytes: Long, otherOwnerBytes: Long): Boolean {
        require(otherGenerationBytes >= 0 && otherOwnerBytes >= 0)
        if (!fitsActive(otherOwnerBytes) || otherGenerationBytes > COMBINED_LIMIT_BYTES) return false
        return otherOwnerBytes <= COMBINED_LIMIT_BYTES - otherGenerationBytes &&
            modeledPeakBytes <= COMBINED_LIMIT_BYTES - otherGenerationBytes - otherOwnerBytes
    }

    companion object {
        const val ACTIVE_LIMIT_BYTES = 14L * 1024 * 1024
        const val COMBINED_LIMIT_BYTES = 32L * 1024 * 1024
        const val ORDINARY_PAGE_BYTES = 64 * 1024

        fun uploadRowsPerPage(vertexBytes: Int, indexBytes: Int, metadataBytes: Int = 0): Int {
            require(vertexBytes > 0 && indexBytes >= 0 && metadataBytes >= 0)
            val bytes = Math.addExact(Math.addExact(vertexBytes, indexBytes), metadataBytes)
            return ORDINARY_PAGE_BYTES / bytes
        }
    }
}

/**
 * Approximate normal-oriented cell-center patches. The supplied clip origin is
 * explicitly the center, never an observed plane. Invoke during CPU mesh
 * preparation, outside frame callbacks; write reuses fixed scratch arrays.
 * Input order is existing target/need/identity priority; this seam never ranks
 * canonical rows. Only the bounded prefix is eligible; every other row is a point.
 */
internal class CoveragePlanarPatchGeometry(
    private val maximumPatches: Int = MAX_PATCHES,
    private val minimumNormalConfidence: Int = 128,
) {
    private val clipper = CoveragePlanarClipper()
    private val worldVertices = FloatArray(MAX_CLIPPED_VERTICES * 3)

    init {
        require(maximumPatches in 0..MAX_PATCHES)
        require(minimumNormalConfidence in 1..255)
    }

    fun write(input: CoverageCellGeometryInput, output: CoverageGeometryBuffers) {
        val selected = minOf(input.count, maximumPatches)
        output.requireCapacity(input.count, selected, MAX_VERTICES_PER_PATCH, MAX_INDICES_PER_PATCH)
        output.reset()
        for (row in 0 until input.count) {
            if (row >= selected || !preparePatch(input, row)) {
                output.appendPoint(input, row)
                continue
            }
            val firstVertex = output.vertexCount
            val count = clipper.vertexCount
            // The ring is actual coplanar geometry, independent of camera/view direction.
            for (vertex in 0 until count) {
                for (axis in 0..2) output.positions[output.vertexCount * 3 + axis] = worldVertices[vertex * 3 + axis]
                output.colors[output.vertexCount++] = BORDER_ARGB
            }
            for (vertex in 0 until count) {
                for (axis in 0..2) {
                    val center = input.worldComponent(input.centerAt(row, 0).toDouble(),
                        input.centerAt(row, 1).toDouble(), input.centerAt(row, 2).toDouble(), axis)
                    output.positions[output.vertexCount * 3 + axis] =
                        (center + (worldVertices[vertex * 3 + axis] - center) * INNER_FRACTION).toFloat()
                }
                output.colors[output.vertexCount++] = BORDER_ARGB
            }
            // The colored fill has separate coincident boundary vertices, so the border
            // cannot interpolate toward the fill color within either ring triangle.
            for (vertex in 0 until count) {
                val boundary = firstVertex + count + vertex
                for (axis in 0..2) output.positions[output.vertexCount * 3 + axis] = output.positions[boundary * 3 + axis]
                output.colors[output.vertexCount++] = input.colorAt(row)
            }
            for (vertex in 0 until count) {
                val next = (vertex + 1) % count
                triangle(output, firstVertex + vertex, firstVertex + next, firstVertex + count + vertex, input.reversesWinding)
                triangle(output, firstVertex + next, firstVertex + count + next, firstVertex + count + vertex, input.reversesWinding)
            }
            for (vertex in 1 until count - 1) {
                triangle(output, firstVertex + count * 2, firstVertex + count * 2 + vertex,
                    firstVertex + count * 2 + vertex + 1, input.reversesWinding)
            }
            output.shapeCount++
        }
    }

    private fun triangle(output: CoverageGeometryBuffers, first: Int, second: Int, third: Int, reversed: Boolean) {
        output.indices[output.indexCount++] = first
        output.indices[output.indexCount++] = if (reversed) third else second
        output.indices[output.indexCount++] = if (reversed) second else third
    }

    private fun preparePatch(input: CoverageCellGeometryInput, row: Int): Boolean {
        val packed = input.packedNormalAt(row)
        if (input.confidenceAt(row) < minimumNormalConfidence || packed !in 0..0xffff ||
            packed shr 8 == 0x80 || packed and 0xff == 0x80) return false
        val high = packed shr 8
        val low = packed and 0xff
        var nx = (if (high >= 128) high - 256 else high) / 127.0
        var ny = (if (low >= 128) low - 256 else low) / 127.0
        val nz = 1.0 - abs(nx) - abs(ny)
        if (nz < 0.0) {
            val oldX = nx
            nx = (1.0 - abs(ny)) * if (oldX < 0.0) -1.0 else 1.0
            ny = (1.0 - abs(oldX)) * if (ny < 0.0) -1.0 else 1.0
        }
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (!length.isFinite() || length <= 1e-12) return false
        val x = input.centerAt(row, 0).toDouble()
        val y = input.centerAt(row, 1).toDouble()
        val z = input.centerAt(row, 2).toDouble()
        val size = input.sizeAt(row).toDouble()
        if (!clipper.clip(x - size / 2, y - size / 2, z - size / 2, size,
                x, y, z, nx / length, ny / length, nz / length)) return false
        for (vertex in 0 until clipper.vertexCount) {
            for (axis in 0..2) {
                val value = input.worldComponent(x + (clipper.componentAt(vertex, 0) - x) * CELL_WIDTH_FRACTION,
                    y + (clipper.componentAt(vertex, 1) - y) * CELL_WIDTH_FRACTION,
                    z + (clipper.componentAt(vertex, 2) - z) * CELL_WIDTH_FRACTION, axis).toFloat()
                if (!value.isFinite()) return false
                worldVertices[vertex * 3 + axis] = value
            }
        }
        // Float conversion at very large centers can collapse valid double geometry.
        for (vertex in 1 until clipper.vertexCount - 1) {
            if (!hasTriangleArea(worldVertices, 0, vertex, vertex + 1)) return false
        }
        return true
    }

    companion object {
        const val MAX_PATCHES = 4_096
        const val MAX_CLIPPED_VERTICES = 6
        const val MAX_VERTICES_PER_PATCH = MAX_CLIPPED_VERTICES * 3
        const val MAX_INDICES_PER_PATCH = 48
        const val CELL_WIDTH_FRACTION = 0.7
        const val INNER_FRACTION = 0.9
        const val BORDER_ARGB = 0xff000000.toInt()
        const val GPU_BYTES_PER_PATCH = MAX_VERTICES_PER_PATCH * 16 + MAX_INDICES_PER_PATCH * Int.SIZE_BYTES
        // Clipper arrays 18+8 doubles, plus this writer's 18 floats. Scalars/objects are other owner bytes.
        const val WORKSPACE_ARRAY_BYTES = (18 + 8) * Double.SIZE_BYTES + MAX_CLIPPED_VERTICES * 3 * Float.SIZE_BYTES

        fun memory(shapeCapacity: Int, selectedRowCapacity: Int): CoverageGeometryMemory {
            require(shapeCapacity in 0..MAX_PATCHES && selectedRowCapacity in shapeCapacity..CoverageCellGeometryInput.MAX_SELECTED_ROWS)
            val gpu = shapeCapacity * GPU_BYTES_PER_PATCH.toLong() + selectedRowCapacity * 16L
            val input = CoverageCellGeometryInput.inputArrayBytes(selectedRowCapacity)
            return CoverageGeometryMemory(gpu, gpu + CoverageGeometryMemory.ORDINARY_PAGE_BYTES,
                input, WORKSPACE_ARRAY_BYTES.toLong(), input)
        }

        fun patchesPerUploadPage(metadataBytesPerPatch: Int = 0): Int = CoverageGeometryMemory.uploadRowsPerPage(
            MAX_VERTICES_PER_PATCH * 16, MAX_INDICES_PER_PATCH * Int.SIZE_BYTES, metadataBytesPerPatch)
    }
}

/** No scratch allocation; double arithmetic avoids overflow of float cross products. */
internal fun hasTriangleArea(vertices: FloatArray, first: Int, second: Int, third: Int): Boolean {
    val ux = vertices[second * 3].toDouble() - vertices[first * 3]
    val uy = vertices[second * 3 + 1].toDouble() - vertices[first * 3 + 1]
    val uz = vertices[second * 3 + 2].toDouble() - vertices[first * 3 + 2]
    val vx = vertices[third * 3].toDouble() - vertices[first * 3]
    val vy = vertices[third * 3 + 1].toDouble() - vertices[first * 3 + 1]
    val vz = vertices[third * 3 + 2].toDouble() - vertices[first * 3 + 2]
    val cx = uy * vz - uz * vy
    val cy = uz * vx - ux * vz
    val cz = ux * vy - uy * vx
    return cx.isFinite() && cy.isFinite() && cz.isFinite() && (cx != 0.0 || cy != 0.0 || cz != 0.0)
}
