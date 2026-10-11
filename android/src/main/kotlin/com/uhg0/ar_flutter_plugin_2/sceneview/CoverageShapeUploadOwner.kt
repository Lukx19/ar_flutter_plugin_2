package com.uhg0.ar_flutter_plugin_2.sceneview

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun interface CoverageShapePageUploader {
    fun upload(vertices: ByteBuffer, vertexStart: Int, vertexCount: Int,
        indices: ByteBuffer, indexStart: Int, indexCount: Int, ticket: Long, completed: CoverageShapePageCompletion)
}
internal fun interface CoverageShapePageCompletion { fun complete(ticket: Long) }

/**
 * One coalesced latest mesh and one callback-owned page. No mesh construction,
 * trigonometry, canonical borrow or row objects occur on renderer frames.
 * Two fixed16KiB buffers give <=32KiB/shape frame, leaving ordinary point pages
 * below the shared64KiB limit. A callback releases ownership; only a real frame
 * admits the successor page. Late callbacks never publish a newer selection.
 */
internal class CoverageShapeUploadOwner(
    private val uploader: CoverageShapePageUploader,
    private val current: (PreparedCoverageShapeReference) -> Boolean,
    private val onCompleted: (PreparedCoverageShapeReference) -> Unit,
    private val onPageReleased: () -> Unit,
) {
    private val vertices = ByteBuffer.allocateDirect(16 * 1024).order(ByteOrder.nativeOrder())
    private val indices = ByteBuffer.allocateDirect(16 * 1024).order(ByteOrder.nativeOrder())
    private var latest: PreparedCoverageShapeReference? = null
    private var active: PreparedCoverageShapeReference? = null
    private var vertexCursor = 0
    private var indexCursor = 0
    private var busy = false
    private var closed = false
    private var poisoned = false
    private var submitting = false
    private var completedDuringSubmission = false
    var completionWasReuse = false
        private set
    private var completedSelection = 0L
    private var pageSerial = 0L
    private var inFlightMesh: PreparedCoverageShapeReference? = null
    private val pageCompleted = object : CoverageShapePageCompletion {
        override fun complete(ticket: Long) { completePage(ticket) }
    }
    var maxUploadBytes = 0
        private set
    var maxPages = 0
        private set
    val inFlight: Boolean get() = busy
    val completedMesh: PreparedCoverageShapeReference? get() {
        val mesh = latest ?: return null
        return if (completedSelection == mesh.header.selectionGeneration && mesh.available && current(mesh)) mesh else null
    }

    fun releaseCompletedSource(): Boolean {
        if (busy || latest == null || completedSelection == 0L) return false
        latest = null
        active = null
        completedSelection = 0
        return true
    }

    fun offer(mesh: PreparedCoverageShapeReference?) {
        if (closed || poisoned) return
        val previous = latest
        if (mesh != null && previous != null && mesh.sharesContent(previous) && mesh.available && current(mesh)) {
            latest = mesh
            if (active?.sharesContent(mesh) == true) active = mesh
            if (completedSelection == previous.header.selectionGeneration) {
                completedSelection = mesh.header.selectionGeneration
                completionWasReuse = true
                try { onCompleted(mesh) } finally { completionWasReuse = false }
            }
            return
        }
        if (latest !== mesh) completedSelection = 0
        latest = mesh
        if (!busy && active !== mesh) releaseActive()
    }

    fun onFrame() {
        if (closed || poisoned || busy) return
        val mesh = latest ?: return
        if (!current(mesh) || completedMesh === mesh) return
        if (active !== mesh) {
            releaseActive(); active = mesh
            vertexCursor = 0; indexCursor = 0
        }
        val vertexTotal = mesh.vertexCount + mesh.pointCount
        val indexTotal = mesh.indexCount + mesh.pointCount
        val vertexCount = minOf(1024, vertexTotal - vertexCursor)
        val indexCount = minOf(4096, indexTotal - indexCursor)
        if (vertexCount == 0 && indexCount == 0) { complete(mesh); return }
        if (!mesh.copyPage(vertices, vertexCursor, vertexCount, indices, indexCursor, indexCount)) return
        val firstVertex = vertexCursor; val firstIndex = indexCursor
        vertexCursor += vertexCount; indexCursor += indexCount
        busy = true; maxPages = 1
        val serial = ++pageSerial
        inFlightMesh = mesh
        maxUploadBytes = maxOf(maxUploadBytes, vertexCount * 16 + indexCount * 4)
        try {
            submitting = true
            uploader.upload(vertices, firstVertex, vertexCount, indices, firstIndex, indexCount, serial, pageCompleted)
        } catch (_: RuntimeException) {
            // Submission may have accepted one buffer before throwing. Neither
            // bytes nor the attribute lease can be reused until actual drain.
            poisoned = true; latest = null
        } finally {
            submitting = false
            if (completedDuringSubmission) { completedDuringSubmission = false; completePage(serial) }
        }
    }

    private fun completePage(ticket: Long) {
        if (!busy || ticket != pageSerial) return
        if (submitting) { completedDuringSubmission = true; return }
        val original = inFlightMesh ?: return
        val mesh = latest?.takeIf { it.sharesContent(original) && it.available && current(it) } ?: original
        busy = false; inFlightMesh = null
        if (closed || poisoned || latest !== mesh || !mesh.available || !current(mesh)) releaseActive()
        else if (vertexCursor == mesh.vertexCount + mesh.pointCount &&
            indexCursor == mesh.indexCount + mesh.pointCount) complete(mesh)
        onPageReleased()
    }

    private fun complete(mesh: PreparedCoverageShapeReference) {
        if (!closed && latest === mesh && mesh.available && current(mesh)) {
            completedSelection = mesh.header.selectionGeneration
            onCompleted(mesh)
        }
        releaseActive()
    }
    private fun releaseActive() { active = null }
    fun close() { closed = true; latest = null; completedSelection = 0; if (!busy) releaseActive() }
}
