package com.uhg0.ar_flutter_plugin_2.sceneview

import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock

/** Fixed metadata only. Delayed descriptors cannot retain a full table or mesh. */
internal class PreparedCoverageShapeReference internal constructor(
    val header: RendererAttributeCodec.Header,
    val shapeCount: Int, val pointCount: Int, val vertexCount: Int, val indexCount: Int,
    private val owner: PreparedCoverageShapeOwner,
    private val contentGeneration: Long,
) {
    val available: Boolean get() = owner.currentContentGeneration == contentGeneration
    fun sharesContent(other: PreparedCoverageShapeReference): Boolean =
        owner === other.owner && contentGeneration == other.contentGeneration
    fun copyPage(vertices: ByteBuffer, firstVertex: Int, vertexCount: Int,
        indices: ByteBuffer, firstIndex: Int, indexCount: Int): Boolean =
        owner.copyPage(contentGeneration, vertices, firstVertex, vertexCount, indices, firstIndex, indexCount)
}

/**
 * One current body and one producer candidate. Construction is outside the
 * monitor; frames try-lock only while copying <=32KiB immutable page bytes.
 * Callbacks own those direct pages, never the source arrays. Refusing an old
 * handle is an explicit point fallback, not freshness of an evicted mesh.
 */
internal class PreparedCoverageShapeOwner {
    private val gate = ReentrantLock()
    private var body: PreparedCoverageShapes? = null
    private var building = false
    @Volatile var currentSelection = 0L
        private set
    @Volatile var currentContentGeneration = 0L
        private set
    private var contentSequence = 0L
    var maxBodies = 0
        private set

    fun begin(): Boolean {
        gate.lock()
        try {
            if (building) return false
            building = true
            maxBodies = maxOf(maxBodies, (if (body != null) 1 else 0) + 1)
            check(maxBodies <= 2)
            return true
        } finally { gate.unlock() }
    }
    fun cancel() { gate.lock(); try { building = false } finally { gate.unlock() } }
    fun publish(prepared: PreparedCoverageShapes): PreparedCoverageShapeReference {
        val previous: PreparedCoverageShapes?
        gate.lock()
        try { check(building); previous = body } finally { gate.unlock() }
        // A producer may borrow the current immutable body while comparing.
        // The existing producer reservation funds both arrays; frames never
        // wait on this bounded comparison.
        val sameContent = previous != null && previous.header.shape == prepared.header.shape &&
            previous.header.color == prepared.header.color && identicalGeometry(previous.geometry, prepared.geometry)
        gate.lock()
        try {
            check(building)
            // Comparing the bounded candidate happens on the binding executor.
            // Metadata may advance without restarting identical upload bytes.
            val reused = sameContent && body === previous
            val geometry = if (reused) previous!!.geometry else prepared.geometry
            if (!reused) currentContentGeneration = ++contentSequence
            body = PreparedCoverageShapes(prepared.header, geometry)
            building = false; currentSelection = prepared.header.selectionGeneration
            return PreparedCoverageShapeReference(prepared.header, geometry.shapeCount, geometry.pointCount,
                geometry.vertexCount, geometry.indexCount, this, currentContentGeneration)
        } finally { gate.unlock() }
    }
    fun invalidate() { gate.lock(); try { body = null; currentSelection = 0; currentContentGeneration = 0 } finally { gate.unlock() } }

    fun copyPage(selection: Long, vertices: ByteBuffer, firstVertex: Int, vertexCount: Int,
        indices: ByteBuffer, firstIndex: Int, indexCount: Int): Boolean {
        if (!gate.tryLock()) return false
        try {
            val source = body ?: return false
            if (currentContentGeneration != selection) return false
            val geometry = source.geometry
            require(firstVertex >= 0 && vertexCount >= 0 && firstVertex + vertexCount <= geometry.vertexCount + geometry.pointCount)
            require(firstIndex >= 0 && indexCount >= 0 && firstIndex + indexCount <= geometry.indexCount + geometry.pointCount)
            require(vertexCount * 16 <= vertices.capacity() && indexCount * 4 <= indices.capacity())
            vertices.clear(); indices.clear()
            repeat(vertexCount) { offset ->
                val vertex = firstVertex + offset; val point = vertex - geometry.vertexCount
                val positions = if (point < 0) geometry.positions else geometry.pointPositions
                val row = if (point < 0) vertex else point
                repeat(3) { vertices.putFloat(positions[row * 3 + it]) }
                val color = if (point < 0) geometry.colors[vertex] else geometry.pointColors[point]
                vertices.put((color ushr 16).toByte()).put((color ushr 8).toByte()).put(color.toByte()).put((color ushr 24).toByte())
            }
            repeat(indexCount) {
                val index = firstIndex + it
                indices.putInt(if (index < geometry.indexCount) geometry.indices[index] else geometry.vertexCount + index - geometry.indexCount)
            }
            vertices.flip(); indices.flip()
            return true
        } finally { gate.unlock() }
    }
    fun portableBytes(): Long { gate.lock(); try { return 64L + (body?.portableBytes ?: 0) } finally { gate.unlock() } }
    private fun identicalGeometry(a: CoverageGeometryBuffers, b: CoverageGeometryBuffers): Boolean =
        a.shapeCount == b.shapeCount && a.pointCount == b.pointCount && a.vertexCount == b.vertexCount &&
            a.indexCount == b.indexCount && a.positions.contentEquals(b.positions) && a.colors.contentEquals(b.colors) &&
            a.indices.contentEquals(b.indices) && a.pointPositions.contentEquals(b.pointPositions) && a.pointColors.contentEquals(b.pointColors)
}
