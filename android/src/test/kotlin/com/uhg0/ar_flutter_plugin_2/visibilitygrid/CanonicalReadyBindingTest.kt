package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.*
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CanonicalReadyBindingTest {
    @Test fun `qualified registration observes queued cut only after producer release and never ACKs it`() {
        val posts = ArrayDeque<() -> Unit>()
        val sent = mutableListOf<CanonicalReadyHintV1>()
        var completion: ((Boolean) -> Unit)? = null
        val hints = CanonicalReadyHintOwner(post = { posts.add(it) }, send = { bytes, done ->
            sent.add(requireNotNull(CanonicalReadyHintV1.decode(bytes))); completion = done
        })
        val fixture = Fixture(hints)
        try {
            val snapshot = fixture.binding.snapshot()
            val owner = envelope(snapshot, 1, 1, 1)
            val selector = CurrentDeltaSelectorV1(2, 2, 1)
            val delta = CurrentDeltaReceiptV1(selector, 1, byteArrayOf(1, 2), ByteArray(32) { 7 })
            fixture.binding.queueCommittedCurrentDelta(CurrentDeltaSourceV1 { delta.takeIf { it.selector == selector } }, selector)
            val registered = RecordingResult()
            assertTrue(fixture.binding.handleCanonicalReadyCall(MethodCall("registerCanonicalReady", owner.bytes), registered))
            assertEquals(true, registered.successValue)
            assertTrue(posts.isEmpty())
            fixture.binding.publishCanonicalReadyHint(fixture.binding.canonicalReadyPublicationStamp())
            assertEquals(1, posts.size)
            posts.removeFirst().invoke()
            val ready = sent.single()
            assertTrue(ready.sameOwner(owner))
            assertEquals(2L, ready.transactionId)
            assertEquals(2L, ready.geometryRevision)
            assertEquals(1L, fixture.binding.currentCommittedBaseline().geometryRevision)
            requireNotNull(completion)(true)
            assertEquals(1L, fixture.binding.currentCommittedBaseline().geometryRevision)
            for (offset in listOf(8, 24, 40, 56, 72, 88, 104, 112, 120, 128, 136, 144, 152, 160, 168)) {
                val foreign = owner.bytes.copyOf().also { it[offset] = (it[offset].toInt() + 1).toByte() }
                val rejected = RecordingResult()
                fixture.binding.handleCanonicalReadyCall(MethodCall("registerCanonicalReady", foreign), rejected)
                assertEquals(false, rejected.successValue)
            }
        } finally { fixture.binding.dispose() }
        assertEquals(0, hints.snapshot()["pendingHints"])
        assertEquals(0, hints.snapshot()["pendingCallbacks"])
    }

    @Test fun `default disabled binding refuses optional registration without a ready owner`() {
        val fixture = Fixture(null)
        try {
            val result = RecordingResult()
            fixture.binding.handleCanonicalReadyCall(MethodCall("registerCanonicalReady",
                envelope(fixture.binding.snapshot(), 1, 1, 1).bytes), result)
            assertEquals(false, result.successValue)
            assertEquals(false, fixture.binding.canonicalReadyWakeSnapshot()["enabled"])
        } finally { fixture.binding.dispose() }
    }

    private class Fixture(hints: CanonicalReadyHintOwner?) {
        private val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(messenger = messenger, viewId = 127,
            CommittedBaselineAuthority = CommittedBaselineAuthority(),
            postToMain = { it() }, canonicalReadyHints = hints)
        private val qualifier = binding.snapshot().let { it.nativeStreamToken + it.workerBindingToken }
        private val streamToken: Long
        init {
            val request = ControlRequest(operation = ControlOperation.START, flags = 0,
                controlRequestId = uuid(1), sessionId = uuid(20), captureGroupId = uuid(40),
                sessionGeneration = 3, groupGeneration = 4, coverageEpoch = 5, streamToken = 0,
                payload = StartRequestCodecV2.defaultPayload())
            val result = RecordingResult()
            MethodChannel(messenger, "visibility_grid_v2_control_127").invokeMethod("start",
                qualifier + ControlCodec.encodeRequest(request), result)
            assertTrue(result.completed.await(2, TimeUnit.SECONDS))
            streamToken = ControlCodec.decodeResponse((result.successValue as ByteArray).copyOfRange(32,
                (result.successValue as ByteArray).size)).streamToken
            assertEquals(2, exchange(1, 0, 0, 0).messageKind)
            assertEquals(4, exchange(2, 0, 0, 0).messageKind)
            assertEquals(0, exchange(3, 1, 1, 1).messageKind)
        }
        private fun exchange(sequence: Long, transaction: Long, geometry: Long, lineage: Long): PacketCodec.Response {
            val reply = RecordingBinaryReply()
            messenger.send("visibility_surface_stream_127", ByteBuffer.wrap(qualifier + PacketCodec.encodeRequest(
                PacketCodec.Request(requestFlags = 0, streamToken = streamToken,
                    acknowledgedTransactionId = transaction, acknowledgedGeometryRevision = geometry,
                    acknowledgedLineageRevision = lineage, nextStyleRevision = 0,
                    maximumResponseBytes = TransactionResponseProfileV1.ordinary.responseCeilingBytes,
                    styleRecords = emptyList(), commandBytes = byteArrayOf(), requestSequence = sequence),
            )), reply)
            assertTrue(reply.completed.await(2, TimeUnit.SECONDS))
            return PacketCodec.decodeResponse(reply.bytes!!.copyOfRange(32, reply.bytes!!.size))
        }
    }

    companion object {
        private fun uuid(seed: Int) = Uuid(ByteArray(16) { (seed + it).toByte() }.also { it[6] = 0x40; it[8] = 0x80.toByte() })
        private fun envelope(snapshot: VisibilityGridV2Binding.Snapshot, transaction: Long, geometry: Long, lineage: Long) =
            CanonicalReadyHintV1.create(listOf(snapshot.sessionId!!.bytes, snapshot.captureGroupId!!.bytes,
                snapshot.arSessionIdentity, snapshot.viewInstanceId, snapshot.nativeStreamToken, snapshot.workerBindingToken),
                longArrayOf(snapshot.viewId.toLong(), snapshot.viewGeneration, snapshot.bindingGeneration,
                    snapshot.sessionGeneration, snapshot.groupGeneration, snapshot.coverageEpoch,
                    snapshot.lifecycleSequence, snapshot.operationGeneration, snapshot.streamToken,
                    transaction, geometry, lineage))
    }
}
