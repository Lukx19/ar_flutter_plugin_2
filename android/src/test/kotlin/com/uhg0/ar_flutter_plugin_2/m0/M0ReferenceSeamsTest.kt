package com.uhg0.ar_flutter_plugin_2.m0

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M0ReferenceSeamsTest {
    @Test
    fun `M0a request and response framing round trips`() {
        val request = M0aPacketCodec.Request(
            requestFlags = 0,
            streamToken = 7,
            acknowledgedTransactionId = 0,
            acknowledgedGeometryRevision = 0,
            acknowledgedLineageRevision = 0,
            nextStyleRevision = 0,
            maximumResponseBytes = 4096,
            styleRecords = listOf(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
            commandBytes = byteArrayOf(9, 10),
            requestSequence = 1,
        )
        val encoded = M0aPacketCodec.encodeRequest(request)
        val decoded = M0aPacketCodec.decodeRequest(encoded)
        assertEquals(request.requestSequence, decoded.requestSequence)
        assertArrayEquals(request.styleRecords.single(), decoded.styleRecords.single())
        assertArrayEquals(request.commandBytes, decoded.commandBytes)

        val response = M0aPacketCodec.encodeResponse(
            M0aPacketCodec.noChanges(
                streamToken = 7,
                requestSequence = 1,
                nextExpectedRequestSequence = 2,
            ),
            4096,
        )
        val responseDecoded = M0aPacketCodec.decodeResponse(response)
        assertEquals(1, responseDecoded.requestSequence)
        assertEquals(2, responseDecoded.nextExpectedRequestSequence)
        assertEquals(7, responseDecoded.streamToken)
        assertEquals(0, responseDecoded.messageKind)
    }

    @Test
    fun `M0a codec rejects CRC tampering`() {
        val request = M0aPacketCodec.Request(
            requestFlags = 0,
            streamToken = 1,
            acknowledgedTransactionId = 0,
            acknowledgedGeometryRevision = 0,
            acknowledgedLineageRevision = 0,
            nextStyleRevision = 0,
            maximumResponseBytes = 4096,
            styleRecords = emptyList(),
            commandBytes = byteArrayOf(),
            requestSequence = 1,
        )
        val encoded = M0aPacketCodec.encodeRequest(request)
        encoded[20] = 1
        assertThrows<IllegalArgumentException> { M0aPacketCodec.decodeRequest(encoded) }
    }

    @Test
    fun `signed coordinates and pages match the Dart reference`() {
        assertEquals(M0RegionCoordinate(-1, 0, -1), m0RegionForMillimetres(-1, 0, -3000))
        assertEquals(M0RegionCoordinate(-1, -2, 0), m0RegionForMillimetres(-3000, -3001, 2999))
        assertEquals(
            M0PageCoordinate(M0RegionCoordinate(-1, 0, 0), 0, 0, 0),
            M0RegionCoordinate(-1, 0, 0).pageForMillimetres(-2001, 1, 1),
        )
        assertEquals(26, M0RegionCoordinate(0, 0, 0).neighbors26().size)
    }

    @Test
    fun `canonical shard is deterministic and tamper evident`() {
        val region = M0RegionCoordinate(-1, 2, 0)
        val bytes = M0CanonicalRegionShardCodec.encode(
            region = region,
            generation = 3,
            geometryRevision = 9,
            rows = listOf(
                M0CanonicalRegionRow(2, 2, 3, 4, 2),
                M0CanonicalRegionRow(1, -2, 3, 4, 1),
            ),
        )
        assertEquals(listOf(1L, 2L), M0CanonicalRegionShardCodec.decode(bytes, region, 3).map { it.surfaceId })
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows<IllegalArgumentException> { M0CanonicalRegionShardCodec.decode(bytes, region, 3) }
    }

    @Test
    fun `centroid renderer uses stable reusable slots and accessible labels`() {
        val renderer = M0CentroidRendererState(3)
        assertTrue(renderer.upsert(M0CentroidRow("g/s1", 0f, 0f, 0f, M0SemanticState.COVERED)))
        assertTrue(renderer.upsert(M0CentroidRow("g/s2", 1f, 0f, 0f, M0SemanticState.PENDING)))
        assertTrue(renderer.flush().reset)
        assertEquals("g/s1", renderer.hitTest(0f, 0f, 0f))
        assertTrue(renderer.accessibilityLabel("g/s2").contains("pending"))
        renderer.remove("g/s1")
        renderer.upsert(M0CentroidRow("g/s3", 2f, 0f, 0f, M0SemanticState.UNCOVERED))
        val plan = renderer.flush()
        assertEquals(1, plan.dirtySpans.size)
        assertEquals(M0DirtySpan(0, 1), plan.dirtySpans.single())
        assertFalse(plan.reset)
    }

    @Test
    fun `signed occupancy keeps supported cells and records overflow`() {
        val kernel = M0SignedOccupancyKernel(capacity = 10)
        val result = kernel.fuse(
            listOf(
                M0VoxelObservation(0, 0, 0, 2),
                M0VoxelObservation(0, 0, 0, 2),
                M0VoxelObservation(1, 0, 0, -2),
            ),
        )
        assertEquals(listOf(M0VoxelKey(0, 0, 0)), result.surfaces.map { it.key })
        assertEquals("A", kernel.candidateId)
    }

    @Test
    fun `planar candidate preserves distinct thin and opposed layers`() {
        val result = M0PlanarConsolidationKernel().fuse(
            listOf(
                M0VoxelObservation(0, 0, 0, 2),
                M0VoxelObservation(0, 0, 1, 2),
            ),
        )
        assertEquals(2, result.surfaces.size)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            throw error
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }
}
