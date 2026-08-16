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
    fun `M0a control and error detail use the pinned envelopes`() {
        val id = M0aUuid(
            byteArrayOf(
                0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x46, 0x17,
                0x98.toByte(), 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
            ),
        )
        val session = M0aUuid(id.bytes.map { (it.toInt() xor 1).toByte() }.toByteArray())
        val group = M0aUuid(id.bytes.map { (it.toInt() xor 2).toByte() }.toByteArray())
        val request = M0aControlRequest(
            M0aControlOperation.START,
            0,
            id,
            session,
            group,
            1,
            2,
            3,
            0,
            byteArrayOf(1, 2, 3),
        )
        val requestBytes = M0aControlCodec.encodeRequest(request)
        assertEquals(107, requestBytes.size)
        assertEquals(request, M0aControlCodec.decodeRequest(requestBytes))

        val detail = M0aErrorDetail(
            6, 0, 0, 2, 0, 4, 1, 0,
            7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        )
        assertEquals(detail, M0aControlCodec.decodeErrorDetail(M0aControlCodec.encodeErrorDetail(detail)))
        val response = M0aControlResponse(
            M0aControlOperation.START,
            1,
            1,
            6,
            id,
            session,
            group,
            1,
            2,
            3,
            0,
            0,
            0,
            M0aControlCodec.encodeErrorDetail(detail),
            byteArrayOf(4, 5),
        )
        val responseBytes = M0aControlCodec.encodeResponse(response, 4096)
        assertEquals(226, responseBytes.size)
        assertEquals(response, M0aControlCodec.decodeResponse(responseBytes))
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
    fun `schema five canonical and coverage shards round trip`() {
        val region = M0RegionCoordinate(-1, 2, 0)
        val canonical = M0RegionShardV5.encodeCanonical(
            region = region,
            captureEvaluatedThrough = 4,
            pendingThrough = 2,
            surfaceRows = listOf(ByteArray(19)),
            lineageRows = listOf(ByteArray(9)),
        )
        val coverage = M0RegionShardV5.encodeCoverage(
            region = region,
            captureEvaluatedThrough = 4,
            pendingThrough = 2,
            surfaceRows = listOf(ByteArray(56)),
            overflowRows = listOf(ByteArray(13)),
            compression = M0RegionShardV5.Compression.ZLIB,
        )
        assertEquals(1, M0RegionShardV5.decode(canonical).surfaceRows.size)
        assertEquals(M0RegionShardV5.Compression.ZLIB, M0RegionShardV5.decode(coverage).compression)
        coverage[coverage.lastIndex] = (coverage.last().toInt() xor 1).toByte()
        assertThrows<IllegalArgumentException> { M0RegionShardV5.decode(coverage) }
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
        renderer.loseContext()
        assertTrue(renderer.isContextLost)
        assertTrue(renderer.flush().rebuild)
        renderer.restoreContext()
        assertTrue(renderer.flush().rebuild)
        assertFalse(renderer.isContextLost)
    }

    @Test
    fun `centroid renderer uses a bounded free list for 100k rows`() {
        val renderer = M0CentroidRendererState(100_000)
        repeat(100_000) { index ->
            assertTrue(renderer.upsert(M0CentroidRow("g/$index", index.toFloat(), 0f, 0f, M0SemanticState.COVERED)))
        }
        assertEquals(100_000, renderer.rowCount)
        assertTrue(renderer.flush().uploadBytes <= 64 * 1024)
        assertTrue(renderer.remove("g/0"))
        assertTrue(renderer.remove("g/1"))
        assertTrue(renderer.upsert(M0CentroidRow("g/reused", 0f, 0f, 0f, M0SemanticState.PENDING)))
        assertEquals(0, renderer.slotFor("g/reused"))
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
    fun `visibility bins and normalization match the Dart reference`() {
        assertEquals(8, M0PictureViewBins24.classify(M0Q15Vector(0, 0, -32767)))
        assertEquals(10, M0PictureViewBins24.classify(M0Q15Vector(-32767, 0, 0)))
        assertEquals(12, M0PictureViewBins24.classify(M0Q15Vector(0, 0, 32767)))
        assertEquals(14, M0PictureViewBins24.classify(M0Q15Vector(32767, 0, 0)))
        assertEquals(9, M0PictureViewBins24.classify(M0Q15Vector(-32767, 0, -32767)))
        assertEquals(11, M0PictureViewBins24.classify(M0Q15Vector(-1, 0, 32767)))
        assertEquals(15, M0PictureViewBins24.classify(M0Q15Vector(1, 0, -32767)))
        assertEquals(21, M0PictureViewBins24.classify(M0Q15Vector(6, 3, 6)))
        assertEquals(13, M0PictureViewBins24.classify(M0Q15Vector(6, -3, 6)))
        assertEquals(M0Q15Vector(0, 0, -32767), M0PictureViewBins24.normalize(0, 0, -100))

        val coverage = M0VisibilityCoverage24()
        listOf(8, 10, 12).forEach { coverage.credit(it, 2) }
        val result = coverage.evaluate(M0Q15Vector(0, 32767, 0), 0)
        assertEquals(listOf(8, 10, 12), result.indices)
        assertEquals(255, result.score)
        assertTrue(result.complete)
        assertEquals(1, coverage.directionalNeedCode(8, complete = false))

        val overflow = M0VisibilityCoverage24()
        overflow.credit(0, 65536)
        assertEquals(65535, overflow.packedCounts()[0])
        assertEquals(1L, overflow.overflowCounts()[0])
        assertEquals(65536L, overflow.countAt(0))
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

    @Test
    fun `bounded tsdf uses checked ties to even averaging`() {
        assertTrue(
            M0BoundedTsdfKernel().fuse(
                listOf(M0VoxelObservation(0, 0, 0, 1), M0VoxelObservation(0, 0, 0, 0)),
            ).surfaces.isEmpty(),
        )
        assertEquals(
            2,
            M0BoundedTsdfKernel().fuse(
                listOf(M0VoxelObservation(0, 0, 0, 2), M0VoxelObservation(0, 0, 0, 1)),
            ).surfaces.single().weight,
        )
    }

    @Test
    fun `picture visibility projection occlusion and guidance match Dart reference`() {
        val camera = M0PictureVisibilityCamera(
            imageWidth = 100,
            imageHeight = 100,
            fxQ8 = 6144,
            fyQ8 = 6144,
            cxQ8 = 12800,
            cyQ8 = 12800,
            groupFromCameraTranslationMm = M0VoxelKey(50, 50, 0),
            cameraFromGroupRotationQ30 = listOf(
                1L shl 30, 0, 0,
                0, 1L shl 30, 0,
                0, 0, 1L shl 30,
            ),
        )
        val surface = M0PictureVisibilitySurface(
            surfaceId = 7,
            key = M0VoxelKey(0, 0, -2),
            normal = M0Q15Vector(0, 0, 32767),
            normalConfidence = 200,
        )
        val approved = M0PictureVisibilityEvaluator.evaluate(camera, surface)
        assertEquals(M0PictureVisibilityRejection.APPROVED, approved.rejection)
        assertEquals(12, approved.bin)
        assertEquals(camera.cxQ8, approved.projectedUQ8)
        assertEquals(camera.cyQ8, approved.projectedVQ8)
        assertEquals(150, approved.depthMm)
        assertTrue(approved.footprintQ16 >= 64L * 65536L)
        assertEquals(
            M0PictureVisibilityRejection.OCCLUSION_INDETERMINATE,
            M0PictureVisibilityEvaluator.evaluate(
                camera,
                surface,
                cut = M0PictureVisibilityCutState(occlusionIndeterminate = true),
            ).rejection,
        )
        val invalidCamera = camera.copy(cameraFromGroupRotationQ30 = List(9) { 0L })
        assertEquals(
            M0PictureVisibilityRejection.CAMERA_MODEL_UNSUPPORTED,
            M0PictureVisibilityEvaluator.evaluate(invalidCamera, surface).rejection,
        )
        assertEquals(
            M0PictureVisibilityRejection.OCCLUDED,
            M0PictureVisibilityEvaluator.evaluate(
                camera,
                surface,
                occludingCells = listOf(M0VoxelKey(0, 0, -1)),
            ).rejection,
        )
        assertTrue(
            M0SupercoverCells100mm.cellsBetween(
                camera.groupFromCameraTranslationMm,
                M0VoxelKey(50, 50, -150),
            ).contains(M0VoxelKey(0, 0, -1)),
        )
        val target = M0GuidanceReference.select(
            listOf(
                M0GuidanceCandidateInput(9, approved, surface.normal, 0, M0PictureVisibilityOccupancy.CONFIRMED),
                M0GuidanceCandidateInput(8, approved, surface.normal, 1, M0PictureVisibilityOccupancy.CONFIRMED),
            ),
        ).first()
        assertEquals(9L, target.surfaceId)
        assertEquals(1698, target.standpointMm.z)
        assertTrue(
            M0GuidanceReference.select(
                listOf(M0GuidanceCandidateInput(9, approved, surface.normal, 0, M0PictureVisibilityOccupancy.CONFIRMED)),
                environment = M0GuidanceEnvironment(
                    M0VoxelKey(-10_000, -10_000, -10_000),
                    M0VoxelKey(10_000, 10_000, 1_000),
                    emptySet(),
                ),
            ).isEmpty(),
        )
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
