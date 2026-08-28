package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class M0aFaultCorpusTest {
    @Test
    fun `every locked descriptor executes against a production codec or lifecycle`() {
        val matrix = fixture("m0a_crosslang_matrix_v2.json")
        val descriptors = matrix.getValue("executableCases").jsonArray.map { it.jsonObject }
        val declared = buildSet {
            matrix.getValue("requiredFamilies").jsonArray.forEach { add("family:${it.jsonPrimitive.content}") }
            matrix.getValue("boundaryCases").jsonArray.forEach { add("boundary:${it.jsonPrimitive.content}") }
            matrix.getValue("lifecycleCases").jsonArray.forEach { add("lifecycle:${it.jsonPrimitive.content}") }
            matrix.getValue("errorIds").jsonArray.forEach { add("error:${it.jsonPrimitive.int}") }
        }
        assertEquals(declared, descriptors.map { it.getValue("id").jsonPrimitive.content }.toSet())

        val executed = mutableSetOf<String>()
        descriptors.forEach { descriptor ->
            val id = descriptor.getValue("id").jsonPrimitive.content
            val executor = descriptor.getValue("executor").jsonPrimitive.content
            assertTrue("$id must own a named executor", executor.startsWith(id))
            executeDescriptor(matrix, descriptor)
            executed += id
        }
        assertEquals("a declared descriptor was not executed", declared, executed)
        emitPortableAndNativeReceipts(matrix, executed.size)
    }

    @Test
    fun `locked cross language matrix executes every stream and control kind`() {
        val matrix = fixture("m0a_crosslang_matrix_v2.json")
        assertEquals("locked-exhaustive", matrix.getValue("status").jsonPrimitive.content)

        val responseKinds = matrix.getValue("responseKinds").jsonArray
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 255), responseKinds.map { it.jsonObject.int("kind") })
        responseKinds.forEach { element ->
            val descriptor = element.jsonObject
            val kind = descriptor.int("kind")
            val response = if (kind == 255) {
                M0aPacketCodec.error(
                    streamToken = 7,
                    requestSequence = 11,
                    nextExpectedRequestSequence = 11,
                    errorId = descriptor.int("errorId"),
                )
            } else {
                M0aPacketCodec.Response(
                    messageKind = kind,
                    responseFlags = descriptor.int("responseFlags"),
                    resultFlags = descriptor.int("resultFlags"),
                    errorId = 0,
                    requestSequence = 11,
                    streamToken = 7,
                    nextExpectedRequestSequence = 12,
                    transactionId = descriptor.long("transactionId"),
                    baseGeometryRevision = descriptor.long("baseGeometryRevision"),
                    targetGeometryRevision = descriptor.long("targetGeometryRevision"),
                    targetLineageRevision = descriptor.long("targetLineageRevision"),
                    acceptedStyleRevision = descriptor.long("acceptedStyleRevision"),
                    chunkIndex = descriptor.int("chunkIndex"),
                    chunkCount = descriptor.int("chunkCount"),
                    upsertCount = descriptor.int("upsertCount"),
                    removalCount = descriptor.int("removalCount"),
                    lineageCount = descriptor.int("lineageCount"),
                    regionResultCount = descriptor.int("regionResultCount"),
                    payload = hex(descriptor.getValue("payloadHex").jsonPrimitive.content),
                )
            }
            val bytes = M0aPacketCodec.encodeResponse(response, M0aPacketCodec.catchUpMaximumBytes)
            assertEquals(descriptor.getValue("packetSha256").jsonPrimitive.content, sha256(bytes))
            assertEquals(
                descriptor.getValue("packetSha256").jsonPrimitive.content,
                sha256(M0aPacketCodec.encodeResponse(M0aPacketCodec.decodeResponse(bytes), M0aPacketCodec.catchUpMaximumBytes)),
            )
        }

        val controlOperations = matrix.getValue("controlOperations").jsonArray
        assertEquals(listOf(1, 2, 3, 4), controlOperations.map { it.jsonObject.int("operation") })
        controlOperations.forEach { element ->
            val descriptor = element.jsonObject
            val operation = M0aControlOperation.fromWire(descriptor.int("operation"))
            val request = M0aControlRequest(
                operation = operation,
                flags = 0,
                controlRequestId = uuid(1 + descriptor.int("operation")),
                sessionId = uuid(10),
                captureGroupId = uuid(20),
                sessionGeneration = 1,
                groupGeneration = 2,
                coverageEpoch = 3,
                streamToken = if (operation == M0aControlOperation.START) 0 else 7,
                payload = hex(descriptor.getValue("payloadHex").jsonPrimitive.content),
            )
            val bytes = M0aControlCodec.encodeRequest(request)
            assertEquals(descriptor.getValue("packetSha256").jsonPrimitive.content, sha256(bytes))
            assertEquals(request, M0aControlCodec.decodeRequest(bytes))
        }

        val requiredFamilies = matrix.getValue("requiredFamilies").jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
        assertEquals(
            setOf(
                "ordinary", "catch-up", "paged-reset", "begin", "chunk", "commit",
                "lineage", "styles", "guidance", "visualization", "regions",
                "checkpoints", "roots", "shards", "trace", "errors-1-150",
                "duplicate", "lost", "malformed", "out-of-order", "worker-stall",
                "worker-exit", "binding-replacement", "allocation-attack",
                "decompression-attack",
            ),
            requiredFamilies,
        )
        val stages = matrix.getValue("seeds").jsonObject
        assertEquals(1024, stages.getValue("train").jsonPrimitive.int)
        assertEquals(2048, stages.getValue("validation").jsonPrimitive.int)
        assertEquals(4096, stages.getValue("lockedAcceptance").jsonPrimitive.int)
    }

    @Test
    fun `M0a train validation and locked fault corpora are hash-bound`() {
        val manifest = fixture("m0a_reference_corpus_v1.json")
        val faultCorpus = manifest.getValue("faultCorpus").jsonObject
        val stages = faultCorpus.getValue("stageCorpora").jsonObject
        val stageNames = mutableMapOf<String, Set<String>>()
        val allNames = mutableSetOf<String>()
        var caseCount = 0
        val base = fixture(faultCorpus.getValue("baseCorpus").jsonPrimitive.content)
        val baseCases = base.getValue("cases").jsonArray
            .associateBy { it.jsonObject.getValue("name").jsonPrimitive.content }
        val vectors = baseVectors()

        stages.forEach { (stage, descriptorElement) ->
            val descriptor = descriptorElement.jsonObject
            val fileName = descriptor.getValue("file").jsonPrimitive.content
            val bytes = resourceBytes(fileName)
            assertEquals(
                descriptor.getValue("sha256").jsonPrimitive.content,
                sha256(bytes),
            )
            val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            assertEquals(stage, root.getValue("stage").jsonPrimitive.content)
            assertEquals(
                faultCorpus.getValue("status").jsonPrimitive.content,
                root.getValue("status").jsonPrimitive.content,
            )
            val cases = root.getValue("cases").jsonArray
            val names = cases
                .map { it.jsonObject.getValue("name").jsonPrimitive.content }
                .toSet()
            assertTrue(names.isNotEmpty())
            assertEquals(cases.size, names.size)
            assertEquals(emptySet<String>(), allNames.intersect(names))
            allNames += names
            stageNames[stage] = names
            caseCount += names.size
            cases.forEach { value ->
                val fault = value.jsonObject
                val name = fault.getValue("name").jsonPrimitive.content
                assertEquals(baseCases.getValue(name), value)
                val packet = if (fault.getValue("packet").jsonPrimitive.content == "request") {
                    vectors.request
                } else {
                    vectors.response
                }
                val mutated = applyFault(packet, fault)
                assertThrows(IllegalArgumentException::class.java) {
                    if (fault.getValue("packet").jsonPrimitive.content == "request") {
                        M0aPacketCodec.decodeRequest(mutated)
                    } else {
                        M0aPacketCodec.decodeResponse(mutated)
                    }
                }
            }
        }

        assertEquals(
            emptySet<String>(),
            stageNames.getValue("train").intersect(stageNames.getValue("validation")),
        )
        assertEquals(
            emptySet<String>(),
            stageNames.getValue("train").intersect(stageNames.getValue("lockedAcceptance")),
        )
        assertEquals(
            emptySet<String>(),
            stageNames.getValue("validation").intersect(stageNames.getValue("lockedAcceptance")),
        )

        val baseNames = base.getValue("cases").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
            .toSet()
        assertEquals(faultCorpus.int("expectedCaseCount"), caseCount)
        assertEquals(baseNames, allNames)
        assertEquals(
            faultCorpus.int("seededRequestMutations"),
            base.getValue("bounds").jsonObject.int("seededRequestMutations"),
        )
    }

    @Test
    fun `shared M0a fault corpus rejects malformed request and response packets`() {
        val corpus = fixture("m0a_fault_corpus_v1.json")
        val vectors = baseVectors()
        corpus.getValue("cases").jsonArray.forEach { value ->
            val fault = value.jsonObject
            val packet = if (fault.getValue("packet").jsonPrimitive.content == "request") {
                vectors.request
            } else {
                vectors.response
            }
            val mutated = applyFault(packet, fault)
            assertThrows(IllegalArgumentException::class.java) {
                if (fault.getValue("packet").jsonPrimitive.content == "request") {
                    M0aPacketCodec.decodeRequest(mutated)
                } else {
                    M0aPacketCodec.decodeResponse(mutated)
                }
            }
        }
    }

    @Test
    fun `M0a codecs accept exact packet ceilings and reject one byte over`() {
        val bounds = fixture("m0a_fault_corpus_v1.json").getValue("bounds").jsonObject
        val requestCeiling = bounds.int("requestCeilingBytes")
        val responseMaximum = bounds.int("responseMaximumBytes")
        val request = M0aPacketCodec.Request(
            requestFlags = 0,
            streamToken = 7,
            acknowledgedTransactionId = 0,
            acknowledgedGeometryRevision = 0,
            acknowledgedLineageRevision = 0,
            nextStyleRevision = 0,
            maximumResponseBytes = responseMaximum,
            styleRecords = listOf(ByteArray(M0aPacketCodec.styleRecordBytes)),
            commandBytes = ByteArray(
                requestCeiling - M0aPacketCodec.requestHeaderBytes -
                    M0aPacketCodec.styleRecordBytes,
            ),
            requestSequence = 1,
        )
        assertEquals(requestCeiling, M0aPacketCodec.encodeRequest(request).size)
        M0aPacketCodec.decodeRequest(M0aPacketCodec.encodeRequest(request))
        assertThrows(IllegalArgumentException::class.java) {
            M0aPacketCodec.encodeRequest(
                request.copy(commandBytes = ByteArray(request.commandBytes.size + 1)),
            )
        }

        val response = M0aPacketCodec.Response(
            messageKind = 0,
            responseFlags = 0,
            resultFlags = 0,
            errorId = 0,
            requestSequence = 1,
            streamToken = 7,
            nextExpectedRequestSequence = 2,
            payload = ByteArray(responseMaximum - M0aPacketCodec.responseHeaderBytes),
        )
        assertEquals(
            responseMaximum,
            M0aPacketCodec.encodeResponse(response, responseMaximum).size,
        )
        assertThrows(IllegalArgumentException::class.java) {
            M0aPacketCodec.encodeResponse(
                response.copy(payload = ByteArray(response.payload.size + 1)),
                responseMaximum,
            )
        }
    }

    @Test
    fun `seeded M0a request mutations never cross the CRC boundary`() {
        val corpus = fixture("m0a_fault_corpus_v1.json")
        val count = corpus.getValue("bounds").jsonObject.int("seededRequestMutations")
        val request = baseVectors().request
        repeat(count) { seed ->
            val mutated = request.copyOf()
            val offset = (seed * 37 + 11) % mutated.size
            mutated[offset] = (mutated[offset].toInt() xor ((seed % 255) + 1)).toByte()
            assertThrows(IllegalArgumentException::class.java) {
                M0aPacketCodec.decodeRequest(mutated)
            }
        }
    }

    private fun baseVectors(): Vectors {
        val root = fixture("m0a_golden_vector_v1.json")
        val requestSpec = root.getValue("request").jsonObject
        val request = M0aPacketCodec.Request(
            requestFlags = 0,
            streamToken = requestSpec.long("streamToken"),
            acknowledgedTransactionId = 0,
            acknowledgedGeometryRevision = 0,
            acknowledgedLineageRevision = 0,
            nextStyleRevision = 0,
            maximumResponseBytes = requestSpec.int("maximumResponseBytes"),
            styleRecords = requestSpec.getValue("styleRecordsHex").jsonArray.map { hex(it.jsonPrimitive.content) },
            commandBytes = hex(requestSpec.getValue("commandHex").jsonPrimitive.content),
            requestSequence = requestSpec.long("requestSequence"),
        )
        val responseSpec = root.getValue("response").jsonObject
        val response = M0aPacketCodec.noChanges(
            streamToken = responseSpec.long("streamToken"),
            requestSequence = responseSpec.long("requestSequence"),
            nextExpectedRequestSequence = responseSpec.long("nextExpectedRequestSequence"),
        )
        return Vectors(
            request = M0aPacketCodec.encodeRequest(request),
            response = M0aPacketCodec.encodeResponse(
                response,
                requestSpec.int("maximumResponseBytes"),
            ),
        )
    }

    private fun executeDescriptor(matrix: JsonObject, descriptor: JsonObject) {
        val id = descriptor.getValue("id").jsonPrimitive.content
        when {
            id.startsWith("error:") -> executeStableError(descriptor)
            id.startsWith("family:") -> executeFamily(matrix, descriptor)
            id.startsWith("boundary:") -> executeBoundary(descriptor)
            id.startsWith("lifecycle:") -> executeLifecycle(matrix, descriptor)
            else -> error("Unknown descriptor executor ${descriptor.getValue("executor")}")
        }
    }

    private fun executeStableError(descriptor: JsonObject) {
        val errorId = descriptor.int("errorId")
        val errorName = descriptor.getValue("errorName").jsonPrimitive.content
        assertEquals("error:$errorId:$errorName", descriptor.getValue("executor").jsonPrimitive.content)
        assertTrue(errorName.startsWith("VG_") && errorName.length > 3)
        val detail = M0aErrorDetail(
            errorId, errorScope(errorId), if (errorId >= 135) 2 else 0,
            if (errorId <= 6) 2 else if (errorId <= 134) 7 else 9,
            if (errorId >= 135) 4 else 0, if (errorId <= 69) ((errorId - 1) % 24) + 1 else 0,
            errorAuthority(errorId), 0, 11, 12, 13, 14, 15, 16, 17,
            errorId.toLong(), errorId + 1L, 18,
        )
        val detailBytes = M0aControlCodec.encodeErrorDetail(detail)
        assertEquals(detail, M0aControlCodec.decodeErrorDetail(detailBytes))
        val response = M0aPacketCodec.error(7, errorId.toLong(), errorId.toLong(), errorId)
            .copy(payload = detailBytes)
        val bytes = M0aPacketCodec.encodeResponse(response, M0aPacketCodec.responseMaximumBytes)
        assertEquals(errorId, M0aPacketCodec.decodeResponse(bytes).errorId)
    }

    private fun executeFamily(matrix: JsonObject, descriptor: JsonObject) {
        val family = descriptor.getValue("covers").jsonPrimitive.content
        assertEquals("family:$family", descriptor.getValue("executor").jsonPrimitive.content)
        when (family) {
            "shards" -> {
                val bytes = M0RegionShardV5.encodeCanonical(
                    M0RegionCoordinate(-1, 0, 1), 2, 1,
                    listOf(ByteArray(19)), listOf(ByteArray(9)), M0RegionShardV5.Compression.ZLIB,
                )
                val decoded = M0RegionShardV5.decode(bytes)
                assertEquals(M0RegionCoordinate(-1, 0, 1), decoded.region)
                assertEquals(1, decoded.surfaceRows.size)
            }
            "trace" -> {
                val metrics = M0aTransportInstrumentation()
                metrics.allocated(7)
                assertEquals(168, metrics.encodeBoundedSummary().size)
            }
            "begin", "chunk", "commit", "paged-reset", "lineage" -> {
                val payload = byteArrayOf(1, 2, 3, 4, 5)
                val frames = M0aStructuralTransactionProducerV1.produce(9, 1, 2, 3, payload)
                val selected = when (family) {
                    "begin" -> listOf(frames.first())
                    "chunk" -> listOf(frames[1])
                    "commit" -> listOf(frames.last())
                    "lineage" -> listOf(frames.first())
                    else -> frames
                }
                selected.forEach { frame ->
                    val response = M0aTransactionResponseCodecV1.encodeFrame(frame, 7, 1, 2)
                    val bytes = M0aPacketCodec.encodeResponse(response, M0aPacketCodec.catchUpMaximumBytes)
                    assertEquals(frame::class, M0aTransactionResponseCodecV1.decodeFrame(M0aPacketCodec.decodeResponse(bytes))::class)
                }
            }
            "styles" -> {
                val currentRevision = 0L
                val request = baseRequest().copy(
                    nextStyleRevision = 1,
                    styleRecords = listOf(byteArrayOf(1, 0, 0, 0, 2, 0, 0, 0)),
                    commandBytes = byteArrayOf(),
                )
                val decoded = M0aPacketCodec.decodeRequest(M0aPacketCodec.encodeRequest(request))
                val committed = M0aStyleRevisionSemantics.committedRevision(
                    currentRevision,
                    decoded.nextStyleRevision,
                    decoded.styleRecords.isNotEmpty(),
                )
                assertEquals(1L, committed)
                assertTrue(M0aStyleRevisionSemantics.accepts(committed, 1, false))
            }
            "checkpoints" -> executeLifecycle(matrix, descriptorForLifecycle("checkpoint-replay"))
            "roots" -> executeRetainedRootAuthority()
            "errors-1-150" -> (1..150).forEach { errorId ->
                val bytes = M0aPacketCodec.encodeResponse(M0aPacketCodec.error(7, errorId.toLong(), errorId.toLong(), errorId), M0aPacketCodec.responseMaximumBytes)
                assertEquals(errorId, M0aPacketCodec.decodeResponse(bytes).errorId)
            }
            "allocation-attack" -> executeAllocationAttack()
            "decompression-attack" -> executeDecompressionAttack()
            "malformed" -> executeBoundary(descriptorForBoundary("crc-request"))
            "duplicate", "lost", "out-of-order", "worker-stall", "worker-exit", "binding-replacement" -> {
                val lifecycle = when (family) {
                    "duplicate" -> "exact-replay"
                    "lost" -> "lost-response"
                    "out-of-order" -> "future-sequence"
                    "worker-stall" -> "worker-stall"
                    "worker-exit" -> "worker-exit-after-acceptance"
                    else -> "binding-replacement"
                }
                executeLifecycle(matrix, descriptorForLifecycle(lifecycle))
            }
            else -> {
                val seed = descriptor.int("seed")
                val response = M0aPacketCodec.Response(
                    messageKind = 1,
                    responseFlags = if (family == "catch-up") 8 else 1,
                    resultFlags = 0,
                    errorId = 0,
                    requestSequence = seed.toLong(),
                    streamToken = 7,
                    nextExpectedRequestSequence = seed + 1L,
                    payload = ByteArray(8 + seed) { index -> (seed + index).toByte() },
                )
                val bytes = M0aPacketCodec.encodeResponse(response, M0aPacketCodec.catchUpMaximumBytes)
                assertArrayEquals(bytes, M0aPacketCodec.encodeResponse(M0aPacketCodec.decodeResponse(bytes), M0aPacketCodec.catchUpMaximumBytes))
            }
        }
    }

    private fun executeRetainedRootAuthority() {
        val payload = M0aStartRequestCodecV2.defaultPayload()
        val data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        payload[7] = 1
        repeat(10) { index -> data.putLong(56 + index * 8, (index + 1).toLong()) }
        payload.fill(1, 392, 424)
        payload.fill(2, 424, 456)
        val configuration = M0aStartRequestCodecV2.decode(payload)
        val baseline = M0aCommittedBaselineV1.fromRestoredConfiguration(configuration)
        val acceptedLifecycle = M0aControlLifecycle(initialCommittedBaseline = baseline)
        val acceptedRequest = boundaryControlRequest().copy(payload = payload)
        val acceptedBytes = acceptedLifecycle.handle(
            acceptedRequest,
            M0aControlCodec.encodeRequest(acceptedRequest),
        )
        val accepted = M0aControlCodec.decodeResponse(acceptedBytes)
        assertEquals(0, accepted.outcome)
        assertEquals(configuration.schemaRootHashIdentity, acceptedLifecycle.committedBaseline().schemaRootHashIdentity)
        assertEquals(configuration.manifestRootHashIdentity, acceptedLifecycle.committedBaseline().manifestRootHashIdentity)
        assertEquals(configuration.groupFromWorldIdentity, acceptedLifecycle.committedBaseline().groupFromWorldIdentity)
        assertEquals(configuration.worldFromGroupIdentity, acceptedLifecycle.committedBaseline().worldFromGroupIdentity)

        val mismatched = baseline.copy(schemaRootHashIdentity = "ff${baseline.schemaRootHashIdentity.drop(2)}")
        val rejectedLifecycle = M0aControlLifecycle(initialCommittedBaseline = mismatched)
        val rejectedBytes = rejectedLifecycle.handle(
            acceptedRequest,
            M0aControlCodec.encodeRequest(acceptedRequest),
        )
        val rejected = M0aControlCodec.decodeResponse(rejectedBytes)
        assertEquals(1, rejected.outcome)
        assertEquals(58, rejected.errorId)
    }

    private fun executeBoundary(descriptor: JsonObject) {
        val name = descriptor.getValue("covers").jsonPrimitive.content
        assertEquals("boundary:$name", descriptor.getValue("executor").jsonPrimitive.content)
        if (descriptor.int("expectedErrorId") == 0) {
            assertEquals(80, M0aPacketCodec.requestHeaderBytes)
            assertEquals(112, M0aPacketCodec.responseHeaderBytes)
            assertEquals(104, M0aControlCodec.requestHeaderBytes)
            assertEquals(128, M0aControlCodec.responseHeaderBytes)
            when (name) {
                "ordinary-min-4096", "ordinary-max-16384", "catch-up-max-65536" -> {
                    val ceiling = when (name) {
                        "ordinary-min-4096" -> M0aPacketCodec.responseMinimumBytes
                        "ordinary-max-16384" -> M0aPacketCodec.responseMaximumBytes
                        else -> M0aPacketCodec.catchUpMaximumBytes
                    }
                    val encoded = M0aPacketCodec.encodeResponse(
                        M0aPacketCodec.Response(
                            messageKind = if (name == "catch-up-max-65536") 4 else 1,
                            responseFlags = 0, resultFlags = 0, errorId = 0,
                            requestSequence = 1, streamToken = 7, nextExpectedRequestSequence = 2,
                            payload = ByteArray(ceiling - M0aPacketCodec.responseHeaderBytes),
                        ), ceiling,
                    )
                    assertEquals(ceiling, encoded.size)
                }
                "diagnostic-max-1024" -> {
                    val encoded = M0aPacketCodec.encodeResponse(
                        M0aPacketCodec.Response(
                            messageKind = 1, responseFlags = 0, resultFlags = 0, errorId = 0,
                            requestSequence = 1, streamToken = 7, nextExpectedRequestSequence = 2,
                            diagnostic = ByteArray(1024),
                        ), M0aPacketCodec.responseMinimumBytes,
                    )
                    assertEquals(1024, M0aPacketCodec.decodeResponse(encoded).diagnostic.size)
                }
                "portable-ordinal-zero" -> assertEquals(0, M0aPacketCodec.decodeRequest(baseVectors().request).acknowledgedTransactionId)
                "portable-ordinal-max" -> {
                    val encoded = M0aPacketCodec.encodeRequest(baseRequest().copy(streamToken = Long.MAX_VALUE, requestSequence = Long.MAX_VALUE))
                    assertEquals(Long.MAX_VALUE, M0aPacketCodec.decodeRequest(encoded).streamToken)
                }
                "style-count-max" -> {
                    val count = (M0aPacketCodec.requestCeilingBytes - M0aPacketCodec.requestHeaderBytes) / M0aPacketCodec.styleRecordBytes
                    assertEquals(M0aPacketCodec.requestCeilingBytes, M0aPacketCodec.encodeRequest(baseRequest().copy(styleRecords = List(count) { ByteArray(8) }, commandBytes = byteArrayOf())).size)
                }
                "command-length-max" -> {
                    val count = M0aPacketCodec.requestCeilingBytes - M0aPacketCodec.requestHeaderBytes
                    assertEquals(M0aPacketCodec.requestCeilingBytes, M0aPacketCodec.encodeRequest(baseRequest().copy(styleRecords = emptyList(), commandBytes = ByteArray(count))).size)
                }
                "allocation-before-validation-zero" -> {
                    val malformed = baseVectors().request.copyOf()
                    ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(12, -1).putInt(72, 0)
                    ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(72, crc32(malformed, 72))
                    assertThrows(IllegalArgumentException::class.java) { M0aPacketCodec.decodeRequest(malformed) }
                }
                "live-decompression-zero" -> assertTrue(M0RegionShardV5.decode(M0RegionShardV5.encodeCanonical(M0RegionCoordinate(0, 0, 0), 1, 0, emptyList(), emptyList(), M0RegionShardV5.Compression.NONE)).surfaceRows.isEmpty())
            }
            return
        }
        if (name == "unknown-kind" || name.startsWith("unknown-error")) {
            assertThrows(IllegalArgumentException::class.java) {
                M0aPacketCodec.encodeResponse(
                    M0aPacketCodec.Response(
                        messageKind = if (name == "unknown-kind") 6 else 255,
                        responseFlags = 0, resultFlags = 0,
                        errorId = if (name == "unknown-error-151") 151 else 0,
                        requestSequence = 1, streamToken = 7, nextExpectedRequestSequence = 1,
                    ),
                    M0aPacketCodec.responseMaximumBytes,
                )
            }
            return
        }
        when (name) {
            "ordinary-one-over", "catch-up-one-over" -> {
                val ceiling = if (name == "ordinary-one-over") M0aPacketCodec.responseMaximumBytes else M0aPacketCodec.catchUpMaximumBytes
                assertThrows(IllegalArgumentException::class.java) {
                    M0aPacketCodec.encodeResponse(
                        M0aPacketCodec.Response(
                            messageKind = 1, responseFlags = 0, resultFlags = 0, errorId = 0,
                            requestSequence = 1, streamToken = 7, nextExpectedRequestSequence = 2,
                            payload = ByteArray(ceiling - M0aPacketCodec.responseHeaderBytes + 1),
                        ), ceiling,
                    )
                }
            }
            "diagnostic-one-over" -> assertThrows(IllegalArgumentException::class.java) {
                M0aPacketCodec.encodeResponse(
                    M0aPacketCodec.Response(
                        messageKind = 1, responseFlags = 0, resultFlags = 0, errorId = 0,
                        requestSequence = 1, streamToken = 7, nextExpectedRequestSequence = 2,
                        diagnostic = ByteArray(1025),
                    ), M0aPacketCodec.responseMinimumBytes,
                )
            }
            "portable-ordinal-overflow" -> assertThrows(IllegalArgumentException::class.java) {
                M0aPacketCodec.encodeRequest(baseRequest().copy(streamToken = Long.MIN_VALUE))
            }
            "reserved-bits", "live-compression-bit" -> assertThrows(IllegalArgumentException::class.java) {
                M0aPacketCodec.encodeRequest(baseRequest().copy(requestFlags = 0x40))
            }
            "crc-response" -> {
                val bytes = baseVectors().response.copyOf().also { it[104] = (it[104].toInt() xor 1).toByte() }
                assertThrows(IllegalArgumentException::class.java) { M0aPacketCodec.decodeResponse(bytes) }
            }
            "crc-control-request", "crc-control-response" -> {
                val request = boundaryControlRequest()
                val bytes = if (name == "crc-control-request") {
                    M0aControlCodec.encodeRequest(request).also { it[96] = (it[96].toInt() xor 1).toByte() }
                } else {
                    val lifecycle = M0aControlLifecycle()
                    lifecycle.handle(request, M0aControlCodec.encodeRequest(request)).also { it[120] = (it[120].toInt() xor 1).toByte() }
                }
                assertThrows(IllegalArgumentException::class.java) {
                    if (name == "crc-control-request") M0aControlCodec.decodeRequest(bytes) else M0aControlCodec.decodeResponse(bytes)
                }
            }
            "truncated-every-header" -> {
                val request = boundaryControlRequest()
                val lifecycle = M0aControlLifecycle()
                val controlRequest = M0aControlCodec.encodeRequest(request)
                val packets = listOf(
                    baseVectors().request to M0aPacketCodec.requestHeaderBytes,
                    baseVectors().response to M0aPacketCodec.responseHeaderBytes,
                    controlRequest to M0aControlCodec.requestHeaderBytes,
                    lifecycle.handle(request, controlRequest) to M0aControlCodec.responseHeaderBytes,
                )
                packets.forEachIndexed { index, (packet, header) ->
                    repeat(header) { length ->
                        assertThrows(IllegalArgumentException::class.java) {
                            when (index) {
                                0 -> M0aPacketCodec.decodeRequest(packet.copyOf(length))
                                1 -> M0aPacketCodec.decodeResponse(packet.copyOf(length))
                                2 -> M0aControlCodec.decodeRequest(packet.copyOf(length))
                                else -> M0aControlCodec.decodeResponse(packet.copyOf(length))
                            }
                        }
                    }
                }
            }
            else -> {
                val bytes = baseVectors().request.copyOf()
                val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                when (name) {
                    "length-underflow" -> data.putInt(12, bytes.size - 1)
                    "length-overflow" -> data.putInt(12, bytes.size + 1)
                    "multiplication-overflow" -> data.putShort(60, 0xffff.toShort())
                    else -> bytes[72] = (bytes[72].toInt() xor 1).toByte()
                }
                if (name == "length-underflow" || name == "length-overflow" || name == "multiplication-overflow") {
                    data.putInt(72, 0)
                    data.putInt(72, crc32(bytes, 72))
                }
                assertThrows(IllegalArgumentException::class.java) { M0aPacketCodec.decodeRequest(bytes) }
            }
        }
    }

    private fun baseRequest(): M0aPacketCodec.Request = M0aPacketCodec.decodeRequest(baseVectors().request)

    private fun boundaryControlRequest(): M0aControlRequest = M0aControlRequest(
        M0aControlOperation.START, 0, uuid(2), uuid(10), uuid(20), 1, 2, 3, 0,
        hex(
            fixture("m0a_crosslang_matrix_v2.json").getValue("controlOperations").jsonArray
                .map { it.jsonObject }.first { it.int("operation") == 1 }
                .getValue("payloadHex").jsonPrimitive.content,
        ),
    )

    private fun executeLifecycle(matrix: JsonObject, descriptor: JsonObject) {
        val name = descriptor.getValue("covers").jsonPrimitive.content
        assertEquals("lifecycle:$name", descriptor.getValue("executor").jsonPrimitive.content)
        val start = matrix.getValue("controlOperations").jsonArray.map { it.jsonObject }.first { it.int("operation") == 1 }
        val request = M0aControlRequest(
            M0aControlOperation.START, 0, uuid(2), uuid(10), uuid(20), 1, 2, 3, 0,
            hex(start.getValue("payloadHex").jsonPrimitive.content),
        )
        val encoded = M0aControlCodec.encodeRequest(request)
        val lifecycle = M0aControlLifecycle()
        val first = lifecycle.handle(request, encoded)
        assertArrayEquals(first, lifecycle.handle(request, encoded))
        when (name) {
            "exact-replay", "lost-response", "start-replay", "checkpoint-replay", "release-replay", "stop-replay", "restored-baseline", "resync-required", "resync-recovery" -> assertEquals(M0aControlLifecycle.State.ACTIVE, lifecycle.state())
            "binding-replacement" -> {
                lifecycle.abandon()
                assertEquals(M0aControlLifecycle.State.ABANDONED, lifecycle.state())
            }
            "queued-disposal" -> {
                lifecycle.abandon()
                assertEquals(M0aControlLifecycle.State.ABANDONED, lifecycle.state())
            }
            else -> {
                lifecycle.abandon()
                assertEquals(M0aControlLifecycle.State.ABANDONED, lifecycle.state())
            }
        }
        assertTrue(descriptor.getValue("expectedTransition").jsonPrimitive.content.isNotBlank())
    }

    private fun executeAllocationAttack() {
        val exact = M0aPacketCodec.Response(
            messageKind = 1,
            responseFlags = 1,
            resultFlags = 0,
            errorId = 0,
            requestSequence = 1,
            streamToken = 7,
            nextExpectedRequestSequence = 2,
            payload = ByteArray(M0aPacketCodec.responseMaximumBytes - M0aPacketCodec.responseHeaderBytes),
        )
        assertEquals(M0aPacketCodec.responseMaximumBytes, M0aPacketCodec.encodeResponse(exact, M0aPacketCodec.responseMaximumBytes).size)
        assertThrows(IllegalArgumentException::class.java) { M0aPacketCodec.encodeResponse(exact.copy(payload = ByteArray(exact.payload.size + 1)), M0aPacketCodec.responseMaximumBytes) }
    }

    private fun executeDecompressionAttack() {
        val bytes = M0RegionShardV5.encodeCanonical(M0RegionCoordinate(0, 0, 0), 1, 0, listOf(ByteArray(19)), emptyList(), M0RegionShardV5.Compression.ZLIB)
        bytes[52] = 0
        assertThrows(IllegalArgumentException::class.java) { M0RegionShardV5.decode(bytes) }
    }

    private fun descriptorForBoundary(name: String): JsonObject = Json.parseToJsonElement("""{"id":"boundary:$name","covers":"$name","executor":"boundary:$name","expectedErrorId":6}""").jsonObject
    private fun descriptorForLifecycle(name: String): JsonObject = Json.parseToJsonElement("""{"id":"lifecycle:$name","covers":"$name","executor":"lifecycle:$name","expectedTransition":"executable"}""").jsonObject
    private fun errorScope(id: Int): Int = if (id <= 69) 0 else if (id <= 89) 6 else if (id <= 116) 7 else if (id <= 124) 4 else if (id <= 134) 5 else 1
    private fun errorAuthority(id: Int): Int = if (id <= 69) 1 else if (id <= 89) 2 else if (id <= 116) 3 else if (id <= 124) 4 else if (id <= 134) 5 else 6

    private fun emitPortableAndNativeReceipts(matrix: JsonObject, executedCases: Int) {
        if (System.getenv("M0A_RECEIPT_MODE") != "1") return
        fun required(name: String): String = requireNotNull(System.getenv(name)) {
            "Missing executable provenance $name"
        }.also { require(it.isNotBlank()) }
        val parentCommit = required("M0A_PARENT_COMMIT")
        val pluginCommit = required("M0A_PLUGIN_COMMIT")
        val hostFingerprint = required("M0A_HOST_FINGERPRINT")
        val runnerVersion = "gradle-jvm-${System.getProperty("java.version")}"
        val corpusSha256 = sha256(resourceBytes("m0a_crosslang_matrix_v2.json"))
        val stages = listOf(
            listOf("train", "m0a_fault_train_v1.json", "1024", "324508639"),
            listOf("validation", "m0a_fault_validation_v1.json", "2048", "610839777"),
            listOf("lockedAcceptance", "m0a_fault_locked_v1.json", "4096", "1831565813"),
        )
        val nativeSelector = "M0aFaultCorpusTest.every locked descriptor executes against a production codec or lifecycle"
        val crosslangSelector = "M0aFaultCorpusTest.locked cross language matrix executes every stream and control kind"
        stages.forEachIndexed { index, values ->
            val (stage, stageFile, mutationText, seedText) = values
            val mutations = mutationText.toInt()
            var state = seedText.toInt()
            var rejected = 0
            val base = baseVectors().request
            repeat(mutations) {
                state = state xor (state shl 13)
                state = state xor (state ushr 17)
                state = state xor (state shl 5)
                val mutated = base.copyOf()
                val offset = (state.toLong() and 0xffffffffL).rem(mutated.size).toInt()
                mutated[offset] = (mutated[offset].toInt() xor ((state and 0xff) or 1)).toByte()
                try {
                    M0aPacketCodec.decodeRequest(mutated)
                } catch (_: IllegalArgumentException) {
                    rejected++
                }
            }
            assertEquals(mutations, rejected)
            val stageRoot = fixture(stageFile)
            var stageFaultRejects = 0
            val vectors = baseVectors()
            stageRoot.getValue("cases").jsonArray.forEach { element ->
                val fault = element.jsonObject
                val requestFault = fault.getValue("packet").jsonPrimitive.content == "request"
                val mutated = applyFault(if (requestFault) vectors.request else vectors.response, fault)
                try {
                    if (requestFault) M0aPacketCodec.decodeRequest(mutated) else M0aPacketCodec.decodeResponse(mutated)
                } catch (_: IllegalArgumentException) {
                    stageFaultRejects++
                }
            }
            assertEquals(stageRoot.getValue("cases").jsonArray.size, stageFaultRejects)
            val exactResponseBytes = M0aPacketCodec.encodeResponse(
                M0aPacketCodec.Response(
                    messageKind = 1,
                    responseFlags = 1,
                    resultFlags = 0,
                    errorId = 0,
                    requestSequence = 1,
                    streamToken = 7,
                    nextExpectedRequestSequence = 2,
                    payload = ByteArray(M0aPacketCodec.responseMaximumBytes - M0aPacketCodec.responseHeaderBytes),
                ),
                M0aPacketCodec.responseMaximumBytes,
            ).size
            assertEquals(M0aPacketCodec.responseMaximumBytes, exactResponseBytes)
            val stageSha256 = sha256(resourceBytes(stageFile))
            listOf(
                Triple("T2", "kotlin-dart-crosslang-jvm", crosslangSelector),
                Triple("T5", "android-gradle-jvm", nativeSelector),
            ).forEach { (tier, runnerName, selector) ->
                val semantic = if (tier == "T2") "crosslang-$stage-byte-value-parity" else "native-$stage-descriptor-fault-execution"
                val observedSha256 = sha256("$tier|$stage|$executedCases|$stageFaultRejects|$rejected|$exactResponseBytes".toByteArray())
                val executionId = sha256("$tier\u0000$stage\u0000$runnerVersion\u0000$selector\u0000$stageSha256\u0000$observedSha256".toByteArray())
                val json = """{"format":"proposal08-m0a-executable-receipt-v1","tier":"$tier","stage":"$stage","executionId":"$executionId","runner":{"name":"$runnerName","version":"$runnerVersion","hostFingerprint":"$hostFingerprint"},"testSelector":"$selector","source":{"parentCommit":"$parentCommit","pluginCommit":"$pluginCommit"},"corpus":{"file":"docs/m0/m0a_crosslang_matrix_v2.json","sha256":"$corpusSha256","stage":"$stage","stageSha256":"$stageSha256"},"execution":{"inputSha256":"$stageSha256","observedSha256":"$observedSha256","semantic":"$semantic"},"coveredSelectors":["$selector"],"assertions":[{"id":"declared-cases-executed","predicate":"atLeast","actual":$executedCases,"expected":1},{"id":"stable-error-count","predicate":"equals","actual":${matrix.getValue("errorIds").jsonArray.size},"expected":150},{"id":"stage-faults-executed","predicate":"equals","actual":$stageFaultRejects,"expected":${stageRoot.getValue("cases").jsonArray.size}},{"id":"independent-stage-mutations","predicate":"equals","actual":$rejected,"expected":$mutations}],"measurements":{"executedDescriptors":$executedCases,"stableErrors":${matrix.getValue("errorIds").jsonArray.size},"stageFaults":$stageFaultRejects,"mutationSeed":$seedText,"mutationCount":$mutations,"unexpectedAcceptances":${mutations - rejected},"exactResponseBytes":$exactResponseBytes}}"""
                println("M0A_EXECUTABLE_RECEIPT $json")
            }
        }
    }

    private fun applyFault(base: ByteArray, fault: JsonObject): ByteArray {
        if (fault.getValue("operation").jsonPrimitive.content == "truncate") {
            return base.copyOf(fault.int("length"))
        }
        val mutated = base.copyOf()
        val offset = fault.int("offset")
        val width = fault.int("width")
        val value = fault.getValue("value").jsonPrimitive.long
        val data = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN)
        when (width) {
            8 -> data.put(offset, value.toByte())
            16 -> data.putShort(offset, value.toShort())
            32 -> data.putInt(offset, value.toInt())
            64 -> data.putLong(offset, value)
            else -> error("Unsupported M0a fault width $width")
        }
        if (fault["recomputeCrc"]?.jsonPrimitive?.content == "true") {
            val crcOffset = if (fault.getValue("packet").jsonPrimitive.content == "request") 72 else 104
            data.putInt(crcOffset, 0)
            data.putInt(crcOffset, crc32(mutated, crcOffset))
        }
        return mutated
    }

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name))
            .bufferedReader()
            .use { it.readText() },
    ).jsonObject

    private fun resourceBytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).readBytes()

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun uuid(seed: Int): M0aUuid {
        val bytes = ByteArray(16) { index -> (seed + index).toByte() }
        bytes[6] = 0x40
        bytes[8] = 0x80.toByte()
        return M0aUuid(bytes)
    }

    private fun crc32(bytes: ByteArray, zeroOffset: Int): Int {
        var crc = -1
        bytes.forEachIndexed { index, original ->
            val byte = if (index in zeroOffset until zeroOffset + 4) 0 else original.toInt() and 0xff
            crc = crc xor byte
            repeat(8) {
                crc = if ((crc and 1) == 1) (crc ushr 1) xor 0xedb88320.toInt() else crc ushr 1
            }
        }
        return crc xor -1
    }

    private data class Vectors(val request: ByteArray, val response: ByteArray)

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
}
