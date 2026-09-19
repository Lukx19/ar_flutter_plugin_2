package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/** Exact signed-oct nearest-direction search owned by one feature-fusion kernel. */
internal class FeatureNormalOctEncoder {
    private val decodedX = IntArray(GRID_AREA)
    private val decodedY = IntArray(GRID_AREA)
    private val decodedZ = IntArray(GRID_AREA)
    private val minimumX = IntArray(NODE_COUNT)
    private val maximumX = IntArray(NODE_COUNT)
    private val minimumY = IntArray(NODE_COUNT)
    private val maximumY = IntArray(NODE_COUNT)
    private val minimumZ = IntArray(NODE_COUNT)
    private val maximumZ = IntArray(NODE_COUNT)
    private val minimumPacked = IntArray(NODE_COUNT) { Int.MAX_VALUE }
    private val childOrdinals = IntArray(INTERNAL_DEPTH * CHILDREN)
    private val childBounds = LongArray(INTERNAL_DEPTH * CHILDREN)
    private var lastVisitedNodes = 0
    private var lastEvaluatedCandidates = 0

    init {
        for (gridX in 0 until VALID_GRID_SIZE) {
            for (gridY in 0 until VALID_GRID_SIZE) {
                val decoded = requireNotNull(
                    FeatureNormalMath.decodeOct(gridX - GRID_OFFSET, gridY - GRID_OFFSET),
                )
                val index = gridIndex(gridX, gridY)
                decodedX[index] = decoded[0]
                decodedY[index] = decoded[1]
                decodedZ[index] = decoded[2]
            }
        }
        buildNode(ROOT_NODE, 0, 0, GRID_SIZE)
    }

    /** Uses component bounds only for safe pruning; winners remain exhaustive-byte exact. */
    fun encode(vector: IntArray): Int {
        require(vector.size >= 3)
        if (vector[0] == 0 && vector[1] == 0 && vector[2] == 0) {
            lastVisitedNodes = 0
            lastEvaluatedCandidates = 0
            return 0
        }
        val search = Search(vector)
        searchNode(ROOT_NODE, 0, 0, GRID_SIZE, 0, search)
        lastVisitedNodes = search.visitedNodes
        lastEvaluatedCandidates = search.evaluatedCandidates
        return search.winner
    }

    fun workReceipt() = FeatureNormalOctEncodingWorkReceipt(
        visitedNodes = lastVisitedNodes,
        evaluatedCandidates = lastEvaluatedCandidates,
    )

    fun portableBytes(): Int = PORTABLE_BYTES

    private fun buildNode(node: Int, startX: Int, startY: Int, size: Int) {
        if (size == LEAF_SIZE) {
            for (gridX in startX until minOf(startX + size, VALID_GRID_SIZE)) {
                for (gridY in startY until minOf(startY + size, VALID_GRID_SIZE)) {
                    val index = gridIndex(gridX, gridY)
                    include(node, decodedX[index], decodedY[index], decodedZ[index], packedCode(gridX, gridY))
                }
            }
            return
        }
        val half = size / 2
        for (ordinal in 0 until CHILDREN) {
            val child = childNode(node, ordinal)
            buildNode(
                child,
                startX + if ((ordinal and 2) != 0) half else 0,
                startY + if ((ordinal and 1) != 0) half else 0,
                half,
            )
            includeBounds(node, child)
        }
    }

    private fun include(node: Int, x: Int, y: Int, z: Int, packed: Int) {
        if (minimumPacked[node] == Int.MAX_VALUE) {
            minimumX[node] = x; maximumX[node] = x
            minimumY[node] = y; maximumY[node] = y
            minimumZ[node] = z; maximumZ[node] = z
            minimumPacked[node] = packed
            return
        }
        minimumX[node] = minOf(minimumX[node], x); maximumX[node] = maxOf(maximumX[node], x)
        minimumY[node] = minOf(minimumY[node], y); maximumY[node] = maxOf(maximumY[node], y)
        minimumZ[node] = minOf(minimumZ[node], z); maximumZ[node] = maxOf(maximumZ[node], z)
        minimumPacked[node] = minOf(minimumPacked[node], packed)
    }

    private fun includeBounds(node: Int, child: Int) {
        check(minimumPacked[child] != Int.MAX_VALUE)
        if (minimumPacked[node] == Int.MAX_VALUE) {
            minimumX[node] = minimumX[child]; maximumX[node] = maximumX[child]
            minimumY[node] = minimumY[child]; maximumY[node] = maximumY[child]
            minimumZ[node] = minimumZ[child]; maximumZ[node] = maximumZ[child]
            minimumPacked[node] = minimumPacked[child]
            return
        }
        minimumX[node] = minOf(minimumX[node], minimumX[child]); maximumX[node] = maxOf(maximumX[node], maximumX[child])
        minimumY[node] = minOf(minimumY[node], minimumY[child]); maximumY[node] = maxOf(maximumY[node], maximumY[child])
        minimumZ[node] = minOf(minimumZ[node], minimumZ[child]); maximumZ[node] = maxOf(maximumZ[node], maximumZ[child])
        minimumPacked[node] = minOf(minimumPacked[node], minimumPacked[child])
    }

    private fun searchNode(
        node: Int,
        startX: Int,
        startY: Int,
        size: Int,
        depth: Int,
        search: Search,
    ) {
        search.visitedNodes++
        val upper = upperBound(node, search.vector)
        if (upper < search.best || (upper == search.best && minimumPacked[node] >= search.winner)) return
        if (size == LEAF_SIZE) {
            for (gridX in startX until minOf(startX + size, VALID_GRID_SIZE)) {
                for (gridY in startY until minOf(startY + size, VALID_GRID_SIZE)) {
                    val index = gridIndex(gridX, gridY)
                    val dot = decodedX[index].toLong() * search.vector[0] +
                        decodedY[index].toLong() * search.vector[1] +
                        decodedZ[index].toLong() * search.vector[2]
                    val packed = packedCode(gridX, gridY)
                    search.evaluatedCandidates++
                    if (dot > search.best || (dot == search.best && packed < search.winner)) {
                        search.best = dot
                        search.winner = packed
                    }
                }
            }
            return
        }

        val offset = depth * CHILDREN
        for (ordinal in 0 until CHILDREN) {
            val child = childNode(node, ordinal)
            childOrdinals[offset + ordinal] = ordinal
            childBounds[offset + ordinal] = upperBound(child, search.vector)
        }
        for (index in 1 until CHILDREN) {
            val ordinal = childOrdinals[offset + index]
            val bound = childBounds[offset + index]
            var insertion = index
            while (insertion > 0) {
                val previousOrdinal = childOrdinals[offset + insertion - 1]
                val previousBound = childBounds[offset + insertion - 1]
                val beforePrevious = bound > previousBound ||
                    (bound == previousBound && minimumPacked[childNode(node, ordinal)] < minimumPacked[childNode(node, previousOrdinal)])
                if (!beforePrevious) break
                childOrdinals[offset + insertion] = previousOrdinal
                childBounds[offset + insertion] = previousBound
                insertion--
            }
            childOrdinals[offset + insertion] = ordinal
            childBounds[offset + insertion] = bound
        }

        val half = size / 2
        for (index in 0 until CHILDREN) {
            val ordinal = childOrdinals[offset + index]
            searchNode(
                childNode(node, ordinal),
                startX + if ((ordinal and 2) != 0) half else 0,
                startY + if ((ordinal and 1) != 0) half else 0,
                half,
                depth + 1,
                search,
            )
        }
    }

    private fun upperBound(node: Int, vector: IntArray): Long =
        vector[0].toLong() * (if (vector[0] < 0) minimumX[node] else maximumX[node]) +
            vector[1].toLong() * (if (vector[1] < 0) minimumY[node] else maximumY[node]) +
            vector[2].toLong() * (if (vector[2] < 0) minimumZ[node] else maximumZ[node])

    private fun gridIndex(gridX: Int, gridY: Int) = gridX * GRID_SIZE + gridY
    private fun packedCode(gridX: Int, gridY: Int) =
        (((gridX - GRID_OFFSET) and 0xff) shl 8) or ((gridY - GRID_OFFSET) and 0xff)
    private fun childNode(node: Int, ordinal: Int) = node * CHILDREN + 1 + ordinal

    private class Search(val vector: IntArray) {
        var best = Long.MIN_VALUE
        var winner = 0
        var visitedNodes = 0
        var evaluatedCandidates = 0
    }

    companion object {
        private const val GRID_SIZE = 256
        private const val VALID_GRID_SIZE = 255
        private const val GRID_OFFSET = 127
        private const val GRID_AREA = GRID_SIZE * GRID_SIZE
        private const val LEAF_SIZE = 4
        private const val INTERNAL_DEPTH = 6
        private const val CHILDREN = 4
        private const val NODE_COUNT = 5_461
        private const val ROOT_NODE = 0
        private const val OWNER_BYTES = 72
        private const val ARRAY_HEADER_BYTES = 16

        private fun alignedArrayBytes(length: Int, elementBytes: Int): Int {
            val payload = Math.multiplyExact(length, elementBytes)
            return Math.addExact(ARRAY_HEADER_BYTES, (payload + 7) and -8)
        }

        internal val PORTABLE_BYTES: Int = listOf(
            OWNER_BYTES,
            3 * alignedArrayBytes(GRID_AREA, Int.SIZE_BYTES),
            7 * alignedArrayBytes(NODE_COUNT, Int.SIZE_BYTES),
            alignedArrayBytes(INTERNAL_DEPTH * CHILDREN, Int.SIZE_BYTES),
            alignedArrayBytes(INTERNAL_DEPTH * CHILDREN, Long.SIZE_BYTES),
        ).fold(0, Math::addExact)
    }
}

internal data class FeatureNormalOctEncodingWorkReceipt(
    val visitedNodes: Int,
    val evaluatedCandidates: Int,
)
