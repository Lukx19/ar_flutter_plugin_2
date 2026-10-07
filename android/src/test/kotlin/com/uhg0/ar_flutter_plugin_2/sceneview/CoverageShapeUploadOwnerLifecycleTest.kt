package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.RendererStyleCutPayloadV1
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class CoverageShapeUploadOwnerLifecycleTest {
    @Test fun `partial index submission failure retains accepted vertex page through successor and close until real drain`() {
        val source = PreparedCoverageShapeOwner()
        val first = publish(source, selection = 1)
        val uploader = DelayedUploader(failAfterVertices = true)
        val fixture = Fixture(uploader)
        fixture.offer(first)
        fixture.uploads.onFrame()
        val held = uploader.pages.single()
        assertTrue(held.verticesAccepted)
        assertEquals(1, uploader.indexAttempts)
        assertFalse(held.indicesAccepted)
        assertTrue(fixture.uploads.inFlight)
        assertEquals(1, fixture.uploads.maxPages)
        assertTrue(held.vertices.isDirect && held.indices.isDirect)

        // The source body can be evicted: its immutable bytes were copied to the held direct page.
        val successor = publish(source, selection = 2, balls = true)
        assertFalse(first.available)
        assertTrue(successor.available)
        assertEquals(2, source.maxBodies)
        fixture.offer(successor)
        repeat(4) { fixture.uploads.onFrame() }
        held.assertBytesUnchanged()
        assertEquals(1, uploader.pages.size)
        assertEquals(0, fixture.released)
        assertTrue(fixture.completed.isEmpty())

        fixture.uploads.close()
        fixture.uploads.close()
        source.invalidate()
        assertFalse(successor.available)
        held.completeWith(held.ticket + 1)
        assertTrue(fixture.uploads.inFlight)
        assertEquals(0, fixture.released)
        held.assertBytesUnchanged()
        held.drainVertices()
        assertFalse(fixture.uploads.inFlight)
        assertEquals(1, fixture.released)
        assertTrue(fixture.completed.isEmpty())
        assertNull(fixture.uploads.completedMesh)
        held.completeWith(held.ticket)
        assertEquals(1, fixture.released)
        fixture.offer(successor)
        fixture.uploads.onFrame()
        assertEquals(1, uploader.pages.size)
    }

    @Test fun `replacement resource has independent pages and old callback cannot release its live upload`() {
        val source = PreparedCoverageShapeOwner()
        val old = publish(source, selection = 1)
        val oldUploader = DelayedUploader(failAfterVertices = true)
        val outgoing = Fixture(oldUploader)
        outgoing.offer(old); outgoing.uploads.onFrame()
        val oldPage = oldUploader.pages.single()
        outgoing.uploads.close()

        val replacement = publish(source, selection = 2, balls = true)
        val newUploader = DelayedUploader()
        val incoming = Fixture(newUploader)
        incoming.offer(replacement); incoming.uploads.onFrame()
        val newPage = newUploader.pages.single()
        assertNotSame(oldPage.vertices, newPage.vertices)
        assertNotSame(oldPage.indices, newPage.indices)
        assertNotSame(oldPage.completion, newPage.completion)
        // Tickets are owner-local; callback identity must keep equally numbered lifetimes separate.
        assertEquals(oldPage.ticket, newPage.ticket)
        oldPage.assertBytesUnchanged()
        oldPage.drainVertices()
        assertFalse(outgoing.uploads.inFlight)
        assertEquals(1, outgoing.released)
        assertTrue(incoming.uploads.inFlight)
        assertEquals(0, incoming.released)
        assertTrue(outgoing.completed.isEmpty())
        newPage.assertBytesUnchanged()
        incoming.uploads.close()
        assertTrue(incoming.uploads.inFlight)
        assertEquals(0, incoming.released)
        newPage.assertBytesUnchanged()
        newPage.drainIndices()
        assertTrue(incoming.uploads.inFlight)
        newPage.drainVertices()
        assertFalse(incoming.uploads.inFlight)
        assertTrue(incoming.completed.isEmpty())
        assertNull(incoming.uploads.completedMesh)
        assertEquals(1, incoming.released)
        oldPage.completeWith(oldPage.ticket)
        assertEquals(1, incoming.released)
        assertTrue(incoming.completed.isEmpty())
    }

    @Test fun `successful upload admits exact bounded pages only on frames after both callbacks drain`() {
        val source = PreparedCoverageShapeOwner()
        val mesh = publish(source, selection = 1, balls = true, rows = 40)
        val uploader = DelayedUploader()
        val fixture = Fixture(uploader)
        fixture.offer(mesh); fixture.uploads.onFrame()
        val first = uploader.pages.single()
        assertEquals(1024, first.vertexCount)
        assertEquals(4096, first.indexCount)
        assertEquals(32768, first.vertexCount * 16 + first.indexCount * 4)
        assertEquals(32768, fixture.uploads.maxUploadBytes)
        first.drainVertices()
        repeat(3) { fixture.uploads.onFrame() }
        assertTrue(fixture.uploads.inFlight)
        assertEquals(1, uploader.pages.size)
        first.assertBytesUnchanged()
        first.drainIndices()
        assertFalse(fixture.uploads.inFlight)
        assertEquals(1, fixture.released)
        assertEquals(1, uploader.pages.size)
        assertTrue(fixture.completed.isEmpty())

        for (pageIndex in 1 until 5) {
            fixture.uploads.onFrame()
            assertEquals(pageIndex + 1, uploader.pages.size)
            val page = uploader.pages.last()
            assertSame(first.vertices, page.vertices)
            assertSame(first.indices, page.indices)
            assertSame(first.completion, page.completion)
            assertEquals(pageIndex * 1024, page.vertexStart)
            assertEquals(pageIndex * 4096, page.indexStart)
            assertTrue(page.vertexCount * 16 + page.indexCount * 4 <= 32768)
            page.drainIndices()
            assertTrue(fixture.uploads.inFlight)
            page.drainVertices()
        }
        assertEquals(944, uploader.pages.last().vertexCount)
        assertEquals(1856, uploader.pages.last().indexCount)
        assertEquals(5, fixture.released)
        assertFalse(fixture.uploads.inFlight)
        assertEquals(listOf(mesh), fixture.completed)
        assertSame(mesh, fixture.uploads.completedMesh)
        repeat(3) { fixture.uploads.onFrame() }
        assertEquals(5, uploader.pages.size)
    }

    @Test fun `old duplicate and foreign tickets cannot release or complete successor page`() {
        val source = PreparedCoverageShapeOwner()
        val mesh = publish(source, selection = 1, rows = 200)
        val uploader = DelayedUploader()
        val fixture = Fixture(uploader)
        fixture.offer(mesh); fixture.uploads.onFrame()
        val first = uploader.pages.single()
        first.completeWith(0)
        first.completeWith(first.ticket + 1)
        assertTrue(fixture.uploads.inFlight)
        assertEquals(0, fixture.released)
        first.drainVertices(); first.drainIndices()
        val vertices = mesh.vertexCount + mesh.pointCount
        val indices = mesh.indexCount + mesh.pointCount
        val pageCount = maxOf((vertices + 1023) / 1024, (indices + 4095) / 4096)
        assertTrue(pageCount >= 2)
        var previous = first
        for (pageIndex in 1 until pageCount) {
            fixture.uploads.onFrame()
            val page = uploader.pages.last()
            assertEquals(pageIndex + 1, uploader.pages.size)
            assertTrue(page.ticket > previous.ticket)
            assertEquals(minOf(vertices, pageIndex * 1024), page.vertexStart)
            assertEquals(minOf(indices, pageIndex * 4096), page.indexStart)
            assertEquals(minOf(1024, (vertices - pageIndex * 1024).coerceAtLeast(0)), page.vertexCount)
            assertEquals(minOf(4096, (indices - pageIndex * 4096).coerceAtLeast(0)), page.indexCount)
            page.assertBytesUnchanged()
            previous.completeWith(previous.ticket)
            page.completeWith(page.ticket + 1)
            assertTrue(fixture.uploads.inFlight)
            assertEquals(pageIndex, fixture.released)
            assertTrue(fixture.completed.isEmpty())
            page.assertBytesUnchanged()
            if (page.verticesAccepted) {
                page.drainVertices()
                if (page.indicesAccepted) {
                    assertTrue(fixture.uploads.inFlight)
                    assertEquals(pageIndex, fixture.released)
                }
            }
            if (page.indicesAccepted) page.drainIndices()
            assertFalse(fixture.uploads.inFlight)
            assertEquals(pageIndex + 1, fixture.released)
            if (pageIndex == pageCount - 1) assertEquals(listOf(mesh), fixture.completed)
            else assertTrue(fixture.completed.isEmpty())
            page.completeWith(page.ticket)
            assertEquals(pageIndex + 1, fixture.released)
            previous = page
        }
        assertEquals(pageCount, fixture.released)
        assertEquals(listOf(mesh), fixture.completed)
    }

    @Test fun `coalesced successor expires prior source handles without changing callback owned bytes`() {
        val source = PreparedCoverageShapeOwner()
        val first = publish(source, selection = 1)
        val uploader = DelayedUploader()
        val fixture = Fixture(uploader)
        fixture.offer(first); fixture.uploads.onFrame()
        val held = uploader.pages.single()
        val superseded = publish(source, selection = 2)
        fixture.offer(superseded)
        val newest = publish(source, selection = 3, balls = true)
        fixture.offer(newest)
        assertFalse(first.available)
        assertFalse(superseded.available)
        assertTrue(newest.available)
        assertEquals(2, source.maxBodies)
        repeat(3) { fixture.uploads.onFrame() }
        assertEquals(1, uploader.pages.size)
        held.assertBytesUnchanged()
        held.drainIndices(); held.drainVertices()
        assertTrue(fixture.completed.isEmpty())
        assertEquals(1, fixture.released)
        assertEquals(1, uploader.pages.size)
        fixture.uploads.onFrame()
        assertEquals(2, uploader.pages.size)
        val replacement = uploader.pages.last()
        assertEquals(126, replacement.vertexCount)
        assertEquals(456, replacement.indexCount)
        replacement.drainVertices(); replacement.drainIndices()
        assertEquals(listOf(newest), fixture.completed)
        assertSame(newest, fixture.uploads.completedMesh)
        source.invalidate()
        assertNull(fixture.uploads.completedMesh)
        fixture.offer(superseded)
        fixture.uploads.onFrame()
        assertEquals(2, uploader.pages.size)
    }

    @Test fun `empty mesh completes without upload and selected point fallbacks encode bounded vertex and index bytes`() {
        val source = PreparedCoverageShapeOwner()
        val empty = publish(source, selection = 1, rows = 0)
        val uploader = DelayedUploader()
        val fixture = Fixture(uploader)
        fixture.offer(empty); fixture.uploads.onFrame()
        assertTrue(uploader.pages.isEmpty())
        assertEquals(listOf(empty), fixture.completed)
        assertEquals(0, fixture.released)
        assertEquals(0, fixture.uploads.maxUploadBytes)
        assertEquals(0, fixture.uploads.maxPages)
        val points = publish(source, selection = 2, rows = 2, normalConfidence = 0)
        assertEquals(0, points.shapeCount)
        assertEquals(2, points.pointCount)
        fixture.offer(points); fixture.uploads.onFrame()
        val page = uploader.pages.single()
        assertEquals(2, page.vertexCount)
        assertEquals(2, page.indexCount)
        assertEquals(40, fixture.uploads.maxUploadBytes)
        assertEquals(0, page.indices.getInt(0))
        assertEquals(1, page.indices.getInt(4))
        for (row in 0..1) {
            assertEquals(2f, page.vertices.getFloat(row * 16), 0f)
            assertEquals(0x22.toByte(), page.vertices.get(row * 16 + 12))
            assertEquals(0x44.toByte(), page.vertices.get(row * 16 + 13))
            assertEquals((2 + row).toByte(), page.vertices.get(row * 16 + 14))
            assertEquals(0xff.toByte(), page.vertices.get(row * 16 + 15))
        }
        page.drainVertices()
        assertTrue(fixture.uploads.inFlight)
        page.drainIndices()
        assertFalse(fixture.uploads.inFlight)
        assertEquals(listOf(empty, points), fixture.completed)
    }

    @Test fun `unknown failed attempt remains callback owned without invented release after close`() {
        val source = PreparedCoverageShapeOwner()
        val mesh = publish(source, selection = 1)
        val uploader = DelayedUploader(failBeforeVertices = true)
        val fixture = Fixture(uploader)
        fixture.offer(mesh); fixture.uploads.onFrame()
        val uncertain = uploader.pages.single()
        assertFalse(uncertain.verticesAccepted)
        assertFalse(uncertain.indicesAccepted)
        assertTrue(fixture.uploads.inFlight)
        uncertain.assertBytesUnchanged()
        fixture.uploads.close()
        source.invalidate()
        repeat(5) { fixture.uploads.onFrame() }
        assertTrue(fixture.uploads.inFlight)
        assertEquals(1, uploader.pages.size)
        assertEquals(0, fixture.released)
        assertTrue(fixture.completed.isEmpty())
        uncertain.assertBytesUnchanged()
    }

    private class Fixture(uploader: DelayedUploader) {
        var current: PreparedCoverageShapeReference? = null
        val completed = mutableListOf<PreparedCoverageShapeReference>()
        var released = 0
        val uploads = CoverageShapeUploadOwner(uploader, { it === current && it.available },
            { completed.add(it) }, { released++ })
        fun offer(mesh: PreparedCoverageShapeReference) { current = mesh; uploads.offer(mesh) }
    }

    /** Models two real callback lanes; only submitted lanes can discharge the held page. */
    private class DelayedUploader(
        private val failAfterVertices: Boolean = false,
        private val failBeforeVertices: Boolean = false,
    ) : CoverageShapePageUploader {
        val pages = mutableListOf<HeldPage>()
        var indexAttempts = 0
        override fun upload(vertices: ByteBuffer, vertexStart: Int, vertexCount: Int,
            indices: ByteBuffer, indexStart: Int, indexCount: Int, ticket: Long,
            completed: CoverageShapePageCompletion) {
            val page = HeldPage(vertices, vertexStart, vertexCount, indices, indexStart, indexCount, ticket, completed)
            pages.add(page)
            if (failBeforeVertices) throw IllegalStateException("Submission outcome unavailable")
            page.verticesAccepted = vertexCount > 0
            indexAttempts++
            if (failAfterVertices) throw IllegalStateException("Index submission refused after vertex acceptance")
            page.indicesAccepted = indexCount > 0
        }
    }

    private class HeldPage(
        val vertices: ByteBuffer, val vertexStart: Int, val vertexCount: Int,
        val indices: ByteBuffer, val indexStart: Int, val indexCount: Int,
        val ticket: Long, val completion: CoverageShapePageCompletion,
    ) {
        var verticesAccepted = false
        var indicesAccepted = false
        private var verticesDrained = false
        private var indicesDrained = false
        private var discharged = false
        private val vertexBytes = ByteArray(vertices.limit()) { vertices.get(it) }
        private val indexBytes = ByteArray(indices.limit()) { indices.get(it) }
        fun assertBytesUnchanged() {
            assertEquals(vertexBytes.size, vertices.limit())
            assertEquals(indexBytes.size, indices.limit())
            assertArrayEquals(vertexBytes, ByteArray(vertices.limit()) { vertices.get(it) })
            assertArrayEquals(indexBytes, ByteArray(indices.limit()) { indices.get(it) })
        }
        fun completeWith(value: Long) { completion.complete(value) }
        fun drainVertices() { check(verticesAccepted); verticesDrained = true; dischargeWhenDrained() }
        fun drainIndices() { check(indicesAccepted); indicesDrained = true; dischargeWhenDrained() }
        private fun dischargeWhenDrained() {
            if (!discharged && (!verticesAccepted || verticesDrained) && (!indicesAccepted || indicesDrained)) {
                discharged = true; completion.complete(ticket)
            }
        }
    }

    private fun publish(source: PreparedCoverageShapeOwner, selection: Long, balls: Boolean = false,
        rows: Int = 1, normalConfidence: Int = 255): PreparedCoverageShapeReference {
        val ids = LongArray(rows) { it + 1L }
        val input = CoverageCellGeometryInput.copyOf(ids,
            FloatArray(rows * 3) { if (it % 3 == 0) selection.toFloat() else 0f }, FloatArray(rows) { 1f },
            IntArray(rows) { 0x2a2a }, IntArray(rows) { normalConfidence },
            IntArray(rows) { 0xff224400.toInt() or (selection.toInt() + it) },
            DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 })
        val geometry = CoverageGeometryBuffers(FloatArray(rows * (if (balls) 126 else CoveragePlanarPatchGeometry.MAX_VERTICES_PER_PATCH) * 3),
            IntArray(rows * if (balls) 126 else CoveragePlanarPatchGeometry.MAX_VERTICES_PER_PATCH),
            IntArray(rows * if (balls) 456 else CoveragePlanarPatchGeometry.MAX_INDICES_PER_PATCH),
            FloatArray(rows * 3), IntArray(rows))
        if (balls) CoverageDirectionalBallGeometry().write(input, CoverageDirectionalColors.unavailable(ids, 0xff999999.toInt()), geometry)
        else CoveragePlanarPatchGeometry().write(input, geometry)
        val style = RendererStyleCutPayloadV1(ByteArray(16) { (it + 1).toByte() }, 12, 7, 2, 2, 1,
            selection, selection, selection, selection, selection, true, longArrayOf(), byteArrayOf()).canonicalBytes()
        val header = RendererAttributeCodec.header(style, 7, selection,
            if (balls) RendererAttributeCodec.BALLS else RendererAttributeCodec.PATCHES, RendererAttributeCodec.OBSERVATIONS, rows)
        assertTrue(source.begin())
        return source.publish(PreparedCoverageShapes(header, geometry))
    }
}
