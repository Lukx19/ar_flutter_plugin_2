package com.uhg0.ar_flutter_plugin_2.m0

import com.uhg0.ar_flutter_plugin_2.visibilitygrid.MethodTestMessenger
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.RecordingBinaryReply
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.RecordingResult
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityGridV2Binding
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class M0aVgs2RecoveryCorpusTest {
    @Test
    fun `Issue 98 production runner executes every SHA locked VGS2 policy case`() {
        val bytes = fixture()
        val receipt = M0aVgs2RecoveryCorpus.run(
            bytes,
            executeFreshBinding = ::executeProductionFreshBinding,
        )
        assertEquals(5, receipt.policyCases)
        assertEquals(1, receipt.freshBindingCases)
    }

    @Test
    fun `Issue 98 corpus rejects missing unknown and mutated expected fields`() {
        val source = fixture().decodeToString()
        listOf(
            source.replaceFirst("\"observedValue\":40", "\"observedValue\":41"),
            source.replaceFirst("\"fieldId\":4", "\"fieldId\":9"),
            source.replaceFirst("\"scope\":1", "\"unknown\":0,\"scope\":1"),
            source.replaceFirst("\"scope\":1,", ""),
            source.replaceFirst(
                "\"oldRequestSequence\":9223372036854775807",
                "\"oldRequestSequence\":9223372036854775806",
            ),
            source.replaceFirst("\"diagnosticBytes\":0", "\"diagnosticBytes\":1"),
        ).forEach { mutation ->
            val bytes = mutation.encodeToByteArray()
            assertThrows(IllegalArgumentException::class.java) {
                M0aVgs2RecoveryCorpus.run(
                    bytes,
                    sha256(bytes),
                    ::executeProductionFreshBinding,
                )
            }
        }
    }

    private fun fixture() = File("../../../docs/m1/issue98_vgs2_recovery_corpus_v1.json").readBytes()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun executeProductionFreshBinding(
        expected: M0aVgs2RecoveryCorpus.FreshBindingExpectation,
    ): M0aVgs2RecoveryCorpus.FreshBindingObservation {
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 98,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            postToMain = { task -> task() },
            isDebuggable = true,
        )
        try {
            val channel = MethodChannel(messenger, "visibility_grid_v2_control_98")
            val preparation = RecordingResult()
            channel.invokeMethod("runDebugV2Issue98Handoff", null, preparation)
            require(preparation.completed.await(2, TimeUnit.SECONDS))
            @Suppress("UNCHECKED_CAST")
            val preparationReceipt = preparation.successValue as Map<String, Any>
            val snapshot = binding.snapshot()
            val qualifier = snapshot.nativeStreamToken + snapshot.workerBindingToken
            val request = startRequest()
            val start = RecordingResult()
            channel.invokeMethod(
                "start",
                qualifier + M0aControlCodec.encodeRequest(request),
                start,
            )
            require(start.completed.await(2, TimeUnit.SECONDS))
            val startResponse = M0aControlCodec.decodeResponse(
                stripQualifier(start.successValue as ByteArray, qualifier),
            )
            val attempted = mutableListOf<Long>()
            fun exchange(sequence: Long, acknowledgedTransactionId: Long): M0aPacketCodec.Response {
                attempted += sequence
                val reply = RecordingBinaryReply()
                messenger.send(
                    "visibility_surface_stream_98",
                    ByteBuffer.wrap(
                        qualifier + M0aPacketCodec.encodeRequest(
                            M0aPacketCodec.Request(
                                requestFlags = 0,
                                streamToken = startResponse.streamToken,
                                acknowledgedTransactionId = acknowledgedTransactionId,
                                acknowledgedGeometryRevision = if (acknowledgedTransactionId == 0L) {
                                    expected.authority.geometryRevision
                                } else {
                                    expected.targetGeometryRevision
                                },
                                acknowledgedLineageRevision = if (acknowledgedTransactionId == 0L) {
                                    expected.authority.lineageRevision
                                } else {
                                    expected.targetLineageRevision
                                },
                                nextStyleRevision = expected.authority.styleRevision,
                                maximumResponseBytes = 4096,
                                styleRecords = emptyList(),
                                commandBytes = byteArrayOf(),
                                requestSequence = sequence,
                            ),
                        ),
                    ),
                    reply,
                )
                require(reply.completed.await(2, TimeUnit.SECONDS))
                return M0aPacketCodec.decodeResponse(stripQualifier(reply.bytes!!, qualifier))
            }
            val begin = exchange(expected.beginRequestSequence, 0)
            val commit = exchange(expected.commitRequestSequence, 0)
            val acknowledged = exchange(expected.ackRequestSequence, expected.nextTransactionId)
            require(begin.messageKind == 2 && commit.messageKind == 4 && acknowledged.messageKind == 0)
            return M0aVgs2RecoveryCorpus.FreshBindingObservation(
                freshTransactionId = startResponse.nativeTransactionId,
                freshRequestSequence = startResponse.nextExchangeRequestSequence,
                attemptedRequestSequences = attempted,
                nextRequestSequence = expected.ackRequestSequence + 1,
                nextTransactionId = acknowledged.transactionId,
                geometryRevision = acknowledged.targetGeometryRevision,
                lineageRevision = acknowledged.targetLineageRevision,
                captureRevision = expected.authority.captureRevision,
                coverageRevision = expected.authority.coverageRevision,
                acceptedStyleRevision = acknowledged.acceptedStyleRevision,
                regionManifestRevision = expected.authority.regionManifestRevision,
                nextSurfaceIdHighWater = expected.authority.nextSurfaceIdHighWater,
                schemaRootRevision = expected.authority.schemaRootRevision,
                oldTokenAttemptCount = preparationReceipt.long("oldTokenAttemptCount"),
                semanticEffectCount = if (acknowledged.transactionId == 1L) 1 else 0,
                oldTokenPublicationCount = preparationReceipt.long("oldTokenPublicationCount"),
            )
        } finally {
            binding.dispose()
        }
    }

    private fun startRequest() = M0aControlRequest(
        operation = M0aControlOperation.START,
        flags = 0,
        controlRequestId = uuid(1),
        sessionId = uuid(20),
        captureGroupId = uuid(40),
        sessionGeneration = 3,
        groupGeneration = 4,
        coverageEpoch = 5,
        streamToken = 0,
        payload = M0aStartRequestCodecV2.defaultPayload(),
    )

    private fun uuid(seed: Int): M0aUuid {
        val bytes = ByteArray(16) { (seed + it).toByte() }
        bytes[6] = 0x40
        bytes[8] = 0x80.toByte()
        return M0aUuid(bytes)
    }

    private fun stripQualifier(bytes: ByteArray, qualifier: ByteArray): ByteArray {
        require(bytes.size >= qualifier.size)
        require(bytes.copyOfRange(0, qualifier.size).contentEquals(qualifier))
        return bytes.copyOfRange(qualifier.size, bytes.size)
    }

    private fun Map<String, Any>.long(key: String) = (getValue(key) as Number).toLong()
}
