package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import com.uhg0.ar_flutter_plugin_2.featurefusion.BoundedTsdfKernel
import com.uhg0.ar_flutter_plugin_2.featurefusion.PlanarConsolidationKernel
import com.uhg0.ar_flutter_plugin_2.featurefusion.SignedOccupancyKernel
import com.uhg0.ar_flutter_plugin_2.featurefusion.VoxelKey
import com.uhg0.ar_flutter_plugin_2.featurefusion.VoxelObservation
import com.uhg0.ar_flutter_plugin_2.visibilityguidance.GuidanceCandidateInput
import com.uhg0.ar_flutter_plugin_2.visibilityguidance.GuidanceEnvironment
import com.uhg0.ar_flutter_plugin_2.visibilityguidance.GuidanceReference
import com.uhg0.ar_flutter_plugin_2.visibilityguidance.PictureViewBins24
import com.uhg0.ar_flutter_plugin_2.visibilityguidance.PictureVisibilityCamera
import com.uhg0.ar_flutter_plugin_2.visibilityguidance.PictureVisibilityCutState
import com.uhg0.ar_flutter_plugin_2.visibilityguidance.PictureVisibilityEvaluator
import com.uhg0.ar_flutter_plugin_2.visibilityguidance.PictureVisibilityOccupancy
import com.uhg0.ar_flutter_plugin_2.visibilityguidance.PictureVisibilityRejection
import com.uhg0.ar_flutter_plugin_2.visibilityguidance.PictureVisibilitySurface
import com.uhg0.ar_flutter_plugin_2.visibilityguidance.Q15Vector
import com.uhg0.ar_flutter_plugin_2.visibilityguidance.SupercoverCells100mm
import com.uhg0.ar_flutter_plugin_2.visibilityguidance.VisibilityCoverage24
import com.uhg0.ar_flutter_plugin_2.visibilityrendering.CentroidRendererState
import com.uhg0.ar_flutter_plugin_2.visibilityrendering.CentroidRow
import com.uhg0.ar_flutter_plugin_2.visibilityrendering.DirtySpan
import com.uhg0.ar_flutter_plugin_2.visibilityrendering.RendererMode
import com.uhg0.ar_flutter_plugin_2.visibilityrendering.RendererPopulationLimits
import com.uhg0.ar_flutter_plugin_2.visibilityrendering.SemanticState
import com.uhg0.ar_flutter_plugin_2.visibilitystorage.CanonicalRegionRow
import com.uhg0.ar_flutter_plugin_2.visibilitystorage.CanonicalRegionShardCodec
import com.uhg0.ar_flutter_plugin_2.visibilitystorage.PageCoordinate
import com.uhg0.ar_flutter_plugin_2.visibilitystorage.RegionCoordinate
import com.uhg0.ar_flutter_plugin_2.visibilitystorage.RegionShardV5
import com.uhg0.ar_flutter_plugin_2.visibilitystorage.regionForMillimetres
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityProtocolReferenceSeamsTest {
    @Test
    fun `visibility protocol request and response framing round trips`() {
        val request = PacketCodec.Request(
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
        val encoded = PacketCodec.encodeRequest(request)
        val decoded = PacketCodec.decodeRequest(encoded)
        assertEquals(request.requestSequence, decoded.requestSequence)
        assertArrayEquals(request.styleRecords.single(), decoded.styleRecords.single())
        assertArrayEquals(request.commandBytes, decoded.commandBytes)

        val response = PacketCodec.encodeResponse(
            PacketCodec.noChanges(
                streamToken = 7,
                requestSequence = 1,
                nextExpectedRequestSequence = 2,
            ),
            4096,
        )
        val responseDecoded = PacketCodec.decodeResponse(response)
        assertEquals(1, responseDecoded.requestSequence)
        assertEquals(2, responseDecoded.nextExpectedRequestSequence)
        assertEquals(7, responseDecoded.streamToken)
        assertEquals(0, responseDecoded.messageKind)
    }

    @Test
    fun `visibility protocol codec rejects CRC tampering`() {
        val request = PacketCodec.Request(
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
        val encoded = PacketCodec.encodeRequest(request)
        encoded[20] = 1
        assertThrows<IllegalArgumentException> { PacketCodec.decodeRequest(encoded) }
    }

    @Test
    fun `visibility protocol control and error detail use the pinned envelopes`() {
        val id = Uuid(
            byteArrayOf(
                0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x46, 0x17,
                0x98.toByte(), 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
            ),
        )
        val session = Uuid(id.bytes.map { (it.toInt() xor 1).toByte() }.toByteArray())
        val group = Uuid(id.bytes.map { (it.toInt() xor 2).toByte() }.toByteArray())
        val request = ControlRequest(
            ControlOperation.START,
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
        val requestBytes = ControlCodec.encodeRequest(request)
        assertEquals(107, requestBytes.size)
        assertEquals(request, ControlCodec.decodeRequest(requestBytes))

        val detail = ErrorDetail(
            6, 0, 0, 2, 0, 4, 1, 0,
            7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        )
        assertEquals(detail, ControlCodec.decodeErrorDetail(ControlCodec.encodeErrorDetail(detail)))
        val response = ControlResponse(
            ControlOperation.START,
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
            ControlCodec.encodeErrorDetail(detail),
            byteArrayOf(4, 5),
        )
        val responseBytes = ControlCodec.encodeResponse(response, 4096)
        assertEquals(226, responseBytes.size)
        assertEquals(response, ControlCodec.decodeResponse(responseBytes))
    }

    @Test
    fun `every stable error detail id round trips without widening`() {
        for (errorId in 1..150) {
            val detail = ErrorDetail(
                errorId = errorId,
                scope = errorId % 8,
                disposition = errorId % 3,
                validationPhase = errorId % 10 + 1,
                recoveryAction = errorId % 10,
                fieldId = errorId,
                authorityKind = errorId + 100,
                diagnosticBytes = errorId % 257,
                geometryRevision = errorId.toLong(),
                lineageRevision = errorId + 1L,
                captureRevision = errorId + 2L,
                coverageRevision = errorId + 3L,
                acceptedStyleRevision = errorId + 4L,
                regionManifestRevision = errorId + 5L,
                nextSurfaceIdHighWater = errorId + 6L,
                expectedValue = errorId + 7L,
                observedValue = errorId + 8L,
                schemaRootRevision = errorId + 9L,
            )
            val bytes = ControlCodec.encodeErrorDetail(detail)
            assertEquals(detail, ControlCodec.decodeErrorDetail(bytes))
            assertEquals(96, bytes.size)
        }
    }

    @Test
    fun `error detail bytes match the shared one through 150 vector hash`() {
        val vector = errorDetailVector()
        val bytes = ByteArrayOutputStream()
        for (errorId in vector.int("firstErrorId")..vector.int("lastErrorId")) {
            bytes.write(
                ControlCodec.encodeErrorDetail(
                    ErrorDetail(
                        errorId = errorId,
                        scope = errorId % 8,
                        disposition = errorId % 3,
                        validationPhase = errorId % 10 + 1,
                        recoveryAction = errorId % 10,
                        fieldId = errorId,
                        authorityKind = errorId + 100,
                        diagnosticBytes = errorId % 257,
                        geometryRevision = errorId.toLong(),
                        lineageRevision = errorId + 1L,
                        captureRevision = errorId + 2L,
                        coverageRevision = errorId + 3L,
                        acceptedStyleRevision = errorId + 4L,
                        regionManifestRevision = errorId + 5L,
                        nextSurfaceIdHighWater = errorId + 6L,
                        expectedValue = errorId + 7L,
                        observedValue = errorId + 8L,
                        schemaRootRevision = errorId + 9L,
                    ),
                ),
            )
        }
        val concatenated = bytes.toByteArray()
        assertEquals(vector.int("concatenatedBytes"), concatenated.size)
        assertEquals(vector.getValue("sha256").jsonPrimitive.content, sha256(concatenated))
    }

    @Test
    fun `signed coordinates and pages match the Dart reference`() {
        assertEquals(RegionCoordinate(-1, 0, -1), regionForMillimetres(-1, 0, -3000))
        assertEquals(RegionCoordinate(-1, -2, 0), regionForMillimetres(-3000, -3001, 2999))
        assertEquals(
            PageCoordinate(RegionCoordinate(-1, 0, 0), 0, 0, 0),
            RegionCoordinate(-1, 0, 0).pageForMillimetres(-2001, 1, 1),
        )
        assertEquals(26, RegionCoordinate(0, 0, 0).neighbors26().size)
    }

    @Test
    fun `canonical shard is deterministic and tamper evident`() {
        val region = RegionCoordinate(-1, 2, 0)
        val bytes = CanonicalRegionShardCodec.encode(
            region = region,
            generation = 3,
            geometryRevision = 9,
            rows = listOf(
                CanonicalRegionRow(2, 2, 3, 4, 2),
                CanonicalRegionRow(1, -2, 3, 4, 1),
            ),
        )
        assertEquals(listOf(1L, 2L), CanonicalRegionShardCodec.decode(bytes, region, 3).map { it.surfaceId })
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows<IllegalArgumentException> { CanonicalRegionShardCodec.decode(bytes, region, 3) }
    }

    @Test
    fun `schema five canonical and coverage shards round trip`() {
        val region = RegionCoordinate(-1, 2, 0)
        val canonical = RegionShardV5.encodeCanonical(
            region = region,
            captureEvaluatedThrough = 4,
            pendingThrough = 2,
            surfaceRows = listOf(ByteArray(19)),
            lineageRows = listOf(ByteArray(9)),
        )
        val coverage = RegionShardV5.encodeCoverage(
            region = region,
            captureEvaluatedThrough = 4,
            pendingThrough = 2,
            surfaceRows = listOf(ByteArray(56)),
            overflowRows = listOf(ByteArray(13)),
            compression = RegionShardV5.Compression.ZLIB,
        )
        assertEquals(1, RegionShardV5.decode(canonical).surfaceRows.size)
        assertEquals(RegionShardV5.Compression.ZLIB, RegionShardV5.decode(coverage).compression)
        coverage[coverage.lastIndex] = (coverage.last().toInt() xor 1).toByte()
        assertThrows<IllegalArgumentException> { RegionShardV5.decode(coverage) }
    }

    @Test
    fun `centroid renderer uses stable reusable slots and accessible labels`() {
        val renderer = CentroidRendererState(3)
        assertTrue(renderer.upsert(CentroidRow("g/s1", 0f, 0f, 0f, SemanticState.COVERED)))
        assertTrue(renderer.upsert(CentroidRow("g/s2", 1f, 0f, 0f, SemanticState.PENDING)))
        assertTrue(renderer.flush().reset)
        assertEquals("g/s1", renderer.hitTest(0f, 0f, 0f))
        assertTrue(renderer.accessibilityLabel("g/s2").contains("pending"))
        renderer.remove("g/s1")
        renderer.upsert(CentroidRow("g/s3", 2f, 0f, 0f, SemanticState.UNCOVERED))
        val plan = renderer.flush()
        assertEquals(1, plan.dirtySpans.size)
        assertEquals(DirtySpan(0, 1), plan.dirtySpans.single())
        assertFalse(plan.reset)
        renderer.loseContext()
        assertTrue(renderer.isContextLost)
        assertTrue(renderer.flush().rebuild)
        renderer.restoreContext()
        assertTrue(renderer.flush().rebuild)
        assertFalse(renderer.isContextLost)
    }

    @Test
    fun `centroid renderer rejects rows beyond the fixed population`() {
        val renderer = CentroidRendererState(100_000)
        repeat(RendererPopulationLimits.CENTROID_ROWS) { index ->
            assertTrue(renderer.upsert(CentroidRow("g/$index", index.toFloat(), 0f, 0f, SemanticState.COVERED)))
        }
        assertFalse(
            renderer.upsert(
                CentroidRow(
                    "g/over-cap",
                    0f,
                    0f,
                    0f,
                    SemanticState.COVERED,
                ),
            ),
        )
        assertEquals(RendererPopulationLimits.CENTROID_ROWS, renderer.rowCount)
        assertTrue(renderer.flush().uploadBytes <= 64 * 1024)
        assertTrue(renderer.remove("g/0"))
        assertTrue(renderer.remove("g/1"))
        assertTrue(renderer.upsert(CentroidRow("g/reused", 0f, 0f, 0f, SemanticState.PENDING)))
        assertEquals(0, renderer.slotFor("g/reused"))
    }

    @Test
    fun `renderer modes honor fixed populations upload and allocation budgets`() {
        RendererMode.entries.forEach { mode ->
            val renderer = CentroidRendererState(
                RendererPopulationLimits.maximumRows(mode),
            )
            renderer.setMode(mode)
            repeat(RendererPopulationLimits.maximumRows(mode)) { index ->
                assertTrue(
                    renderer.upsert(
                        CentroidRow(
                            "$mode/$index",
                            index.toFloat(),
                            0f,
                            0f,
                            SemanticState.COVERED,
                        ),
                    ),
                )
            }
            var plan = renderer.flush()
            while (plan.dirtySpans.isNotEmpty()) {
                assertTrue(plan.uploadBytes <= 64 * 1024)
                plan = renderer.flush()
            }
            assertTrue(renderer.withinFixedPopulation)
            assertTrue(renderer.allocatedBytes <= 8 * 1024 * 1024)
            assertTrue(renderer.remove("$mode/0"))
            assertTrue(renderer.upsert(CentroidRow("$mode/replacement", 0f, 1f, 0f, SemanticState.COVERED)))
            renderer.loseContext()
            plan = renderer.flush()
            assertTrue(plan.rebuild)
            while (plan.dirtySpans.isNotEmpty()) {
                assertTrue(plan.uploadBytes <= 64 * 1024)
                plan = renderer.flush()
            }
            renderer.restoreContext()
            plan = renderer.flush()
            assertTrue(plan.rebuild)
            while (plan.dirtySpans.isNotEmpty()) {
                assertTrue(plan.uploadBytes <= 64 * 1024)
                plan = renderer.flush()
            }
        }
    }

    @Test
    fun `signed occupancy keeps supported cells and records overflow`() {
        val kernel = SignedOccupancyKernel(capacity = 10)
        val result = kernel.fuse(
            listOf(
                VoxelObservation(0, 0, 0, 2),
                VoxelObservation(0, 0, 0, 2),
                VoxelObservation(1, 0, 0, -2),
            ),
        )
        assertEquals(listOf(VoxelKey(0, 0, 0)), result.surfaces.map { it.key })
        assertEquals("A", kernel.candidateId)
    }

    @Test
    fun `fusion input and lineage bounds are deterministic and counted`() {
        val result = SignedOccupancyKernel(
            maxObservations = 3,
            maxLineageIds = 2,
        ).fuse(
            listOf(
                VoxelObservation(0, 0, 0, 2, 9),
                VoxelObservation(0, 0, 0, 2, 7),
                VoxelObservation(0, 0, 0, 2, 8),
                VoxelObservation(0, 0, 0, 2, 6),
            ),
        )
        assertEquals(listOf(7, 8), result.surfaces.single().lineageIds)
        assertEquals(3, result.surfaces.single().observationCount)
        assertEquals(2, result.overflowObservationCount)
    }

    @Test
    fun `candidate B reapplies the lineage bound after consolidation`() {
        val result = PlanarConsolidationKernel(maxLineageIds = 2).fuse(
            listOf(
                VoxelObservation(0, 0, 0, 2, 1),
                VoxelObservation(0, 0, 0, 2, 2),
                VoxelObservation(1, 0, 0, 2, 3),
                VoxelObservation(1, 0, 0, 2, 4),
                VoxelObservation(0, 1, 0, 2, 5),
                VoxelObservation(0, 1, 0, 2, 6),
                VoxelObservation(1, 1, 0, 2, 7),
                VoxelObservation(1, 1, 0, 2, 8),
            ),
        )

        assertEquals(listOf(1, 2), result.surfaces.single().lineageIds)
        assertEquals(6, result.overflowObservationCount)
    }

    @Test
    fun `visibility bins and normalization match the Dart reference`() {
        assertEquals(8, PictureViewBins24.classify(Q15Vector(0, 0, -32767)))
        assertEquals(10, PictureViewBins24.classify(Q15Vector(-32767, 0, 0)))
        assertEquals(12, PictureViewBins24.classify(Q15Vector(0, 0, 32767)))
        assertEquals(14, PictureViewBins24.classify(Q15Vector(32767, 0, 0)))
        assertEquals(9, PictureViewBins24.classify(Q15Vector(-32767, 0, -32767)))
        assertEquals(11, PictureViewBins24.classify(Q15Vector(-1, 0, 32767)))
        assertEquals(15, PictureViewBins24.classify(Q15Vector(1, 0, -32767)))
        assertEquals(21, PictureViewBins24.classify(Q15Vector(6, 3, 6)))
        assertEquals(13, PictureViewBins24.classify(Q15Vector(6, -3, 6)))
        assertEquals(Q15Vector(0, 0, -32767), PictureViewBins24.normalize(0, 0, -100))

        val coverage = VisibilityCoverage24()
        listOf(8, 10, 12).forEach { coverage.credit(it, 2) }
        val result = coverage.evaluate(Q15Vector(0, 32767, 0), 0)
        assertEquals(listOf(8, 10, 12), result.indices)
        assertEquals(255, result.score)
        assertTrue(result.complete)
        assertEquals(1, coverage.directionalNeedCode(8, complete = false))

        val overflow = VisibilityCoverage24()
        overflow.credit(0, 65536)
        assertEquals(65535, overflow.packedCounts()[0])
        assertEquals(1L, overflow.overflowCounts()[0])
        assertEquals(65536L, overflow.countAt(0))
    }

    @Test
    fun `planar candidate preserves distinct thin and opposed layers`() {
        val result = PlanarConsolidationKernel().fuse(
            listOf(
                VoxelObservation(0, 0, 0, 2),
                VoxelObservation(0, 0, 1, 2),
            ),
        )
        assertEquals(2, result.surfaces.size)
    }

    @Test
    fun `bounded tsdf uses checked ties to even averaging`() {
        assertTrue(
            BoundedTsdfKernel().fuse(
                listOf(VoxelObservation(0, 0, 0, 1), VoxelObservation(0, 0, 0, 0)),
            ).surfaces.isEmpty(),
        )
        assertEquals(
            2,
            BoundedTsdfKernel().fuse(
                listOf(VoxelObservation(0, 0, 0, 2), VoxelObservation(0, 0, 0, 1)),
            ).surfaces.single().weight,
        )
    }

    @Test
    fun `picture visibility projection occlusion and guidance match Dart reference`() {
        val camera = PictureVisibilityCamera(
            imageWidth = 100,
            imageHeight = 100,
            fxQ8 = 6144,
            fyQ8 = 6144,
            cxQ8 = 12800,
            cyQ8 = 12800,
            groupFromCameraTranslationMm = VoxelKey(50, 50, 0),
            cameraFromGroupRotationQ30 = listOf(
                1L shl 30, 0, 0,
                0, 1L shl 30, 0,
                0, 0, 1L shl 30,
            ),
        )
        val surface = PictureVisibilitySurface(
            surfaceId = 7,
            key = VoxelKey(0, 0, -2),
            normal = Q15Vector(0, 0, 32767),
            normalConfidence = 200,
        )
        val approved = PictureVisibilityEvaluator.evaluate(camera, surface)
        assertEquals(PictureVisibilityRejection.APPROVED, approved.rejection)
        assertEquals(12, approved.bin)
        assertEquals(camera.cxQ8, approved.projectedUQ8)
        assertEquals(camera.cyQ8, approved.projectedVQ8)
        assertEquals(150, approved.depthMm)
        assertTrue(approved.footprintQ16 >= 64L * 65536L)
        assertEquals(
            PictureVisibilityRejection.OCCLUSION_INDETERMINATE,
            PictureVisibilityEvaluator.evaluate(
                camera,
                surface,
                cut = PictureVisibilityCutState(occlusionIndeterminate = true),
            ).rejection,
        )
        val invalidCamera = camera.copy(cameraFromGroupRotationQ30 = List(9) { 0L })
        assertEquals(
            PictureVisibilityRejection.CAMERA_MODEL_UNSUPPORTED,
            PictureVisibilityEvaluator.evaluate(invalidCamera, surface).rejection,
        )
        assertEquals(
            PictureVisibilityRejection.OCCLUDED,
            PictureVisibilityEvaluator.evaluate(
                camera,
                surface,
                occludingCells = listOf(VoxelKey(0, 0, -1)),
            ).rejection,
        )
        assertTrue(
            SupercoverCells100mm.cellsBetween(
                camera.groupFromCameraTranslationMm,
                VoxelKey(50, 50, -150),
            ).contains(VoxelKey(0, 0, -1)),
        )
        val target = GuidanceReference.select(
            listOf(
                GuidanceCandidateInput(9, approved, surface.normal, 0, PictureVisibilityOccupancy.CONFIRMED),
                GuidanceCandidateInput(8, approved, surface.normal, 1, PictureVisibilityOccupancy.CONFIRMED),
            ),
        ).first()
        assertEquals(9L, target.surfaceId)
        assertEquals(1698, target.standpointMm.z)
        assertTrue(
            GuidanceReference.select(
                listOf(GuidanceCandidateInput(9, approved, surface.normal, 0, PictureVisibilityOccupancy.CONFIRMED)),
                environment = GuidanceEnvironment(
                    VoxelKey(-10_000, -10_000, -10_000),
                    VoxelKey(10_000, 10_000, 1_000),
                    emptySet(),
                ),
            ).isEmpty(),
        )
    }

    private fun errorDetailVector(): JsonObject =
        Json.parseToJsonElement(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("visibility_protocol_error_detail_vector_v1.json"))
                .bufferedReader()
                .use { it.readText() },
        ).jsonObject

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte)
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
