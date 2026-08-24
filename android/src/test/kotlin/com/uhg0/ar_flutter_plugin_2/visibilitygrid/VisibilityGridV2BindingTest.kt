package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.m0.M0aCommittedBaselineAuthority
import com.uhg0.ar_flutter_plugin_2.m0.M0aCommittedBaselineScopeV1
import com.uhg0.ar_flutter_plugin_2.m0.M0aCommitReceiptQueryV1
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlCodec
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlOperation
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlRequest
import com.uhg0.ar_flutter_plugin_2.m0.M0aPacketCodec
import com.uhg0.ar_flutter_plugin_2.m0.M0aStartRequestCodecV2
import com.uhg0.ar_flutter_plugin_2.m0.M0aUuid
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityGridV2BindingTest {
    @Test
    fun `debug recovery trace is bounded and the recovery gate disarms`() {
        val seam = VisibilityGridV2DebugRecoverySeam()
        seam.arm()
        repeat(80) { seam.acceptedCut(startRequest()) }
        val bounded = seam.snapshot()["trace"] as List<*>
        assertEquals(64, bounded.size)

        val stalled = Executors.newSingleThreadExecutor()
        val continuation = stalled.submit {
            seam.beforeExchange()
            seam.oldContinuationFenced()
        }
        seam.releaseAbandonedExchange()
        continuation.get(2, TimeUnit.SECONDS)
        stalled.shutdownNow()

        val cut = VisibilityGridV2Binding.RecoveryGroupCut.from(startRequest())
        seam.replacementSeeded(cut)
        seam.acceptedCut(startRequest())
        val completedTrace = seam.snapshot()["trace"] as List<*>
        seam.acceptedCut(startRequest())
        assertEquals(completedTrace, seam.snapshot()["trace"])

        assertEquals(true, seam.armCommitPublication()["armed"])
    }

    @Test
    fun `exact receipt query reports commit winner and rejects stale replay qualification`() {
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 83,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            postToMain = { task -> task() },
        )
        val channel = MethodChannel(messenger, "visibility_grid_v2_control_83")
        val original = binding.snapshot()
        val qualifier = original.nativeStreamToken + original.workerBindingToken
        val start = RecordingResult()
        channel.invokeMethod(
            "start",
            qualifier + M0aControlCodec.encodeRequest(startRequest()),
            start,
        )
        assertTrue(start.completed.await(2, TimeUnit.SECONDS))
        val startResponse = M0aControlCodec.decodeResponse(
            stripQualifier(start.successValue as ByteArray, qualifier),
        )
        val streamToken = startResponse.streamToken

        fun exchange(sequence: Long): M0aPacketCodec.Response {
            val response = RecordingBinaryReply()
            messenger.send(
                "visibility_surface_stream_83",
                ByteBuffer.wrap(
                    qualifier + M0aPacketCodec.encodeRequest(
                        M0aPacketCodec.Request(
                            requestFlags = 0,
                            streamToken = streamToken,
                            acknowledgedTransactionId = 0,
                            acknowledgedGeometryRevision = 0,
                            acknowledgedLineageRevision = 0,
                            nextStyleRevision = 0,
                            maximumResponseBytes = 4096,
                            styleRecords = emptyList(),
                            commandBytes = byteArrayOf(),
                            requestSequence = sequence,
                        ),
                    ),
                ),
                response,
            )
            assertTrue(response.completed.await(2, TimeUnit.SECONDS))
            return M0aPacketCodec.decodeResponse(stripQualifier(response.bytes!!, qualifier))
        }

        assertEquals(2, exchange(1).messageKind)
        assertEquals(4, exchange(2).messageKind)

        fun query(targetGeometry: Long = 1): RecordingResult {
            val result = RecordingResult()
            channel.invokeMethod(
                "queryCommitReceipt",
                mapOf(
                    "currentBindingQualifier" to qualifier,
                    "controlRequestId" to startRequest().controlRequestId.hex(),
                    "sessionId" to startRequest().sessionId.hex(),
                    "captureGroupId" to startRequest().captureGroupId.hex(),
                    "sessionGeneration" to 3L,
                    "groupGeneration" to 4L,
                    "nativeStreamToken" to qualifier.copyOfRange(0, 16),
                    "workerBindingToken" to qualifier.copyOfRange(16, 32),
                    "streamToken" to streamToken,
                    "requestSequence" to 2L,
                    "transactionId" to 1L,
                    "targetGeometryRevision" to targetGeometry,
                    "targetLineageRevision" to 1L,
                ),
                result,
            )
            assertTrue(result.completed.await(2, TimeUnit.SECONDS))
            return result
        }

        val first = query()
        assertEquals(1, first.successCount)
        val firstMap = first.successValue as Map<*, *>
        assertEquals("commit", firstMap["decision"])
        assertEquals(0L, firstMap["rootIsolateSurfaceBytes"])
        val baseline = firstMap["baseline"] as Map<*, *>
        assertEquals(1L, baseline["transactionId"])
        assertEquals(1L, baseline["geometryRevision"])
        assertEquals(1L, baseline["lineageRevision"])

        val replay = query()
        assertEquals(1, replay.successCount)
        val replayMap = replay.successValue as Map<*, *>
        assertEquals(firstMap["decision"], replayMap["decision"])
        assertEquals(firstMap["transactionId"], replayMap["transactionId"])
        assertEquals(firstMap["baseline"], replayMap["baseline"])
        assertArrayEquals(
            firstMap["nativeStreamToken"] as ByteArray,
            replayMap["nativeStreamToken"] as ByteArray,
        )

        val stale = query(targetGeometry = 2)
        assertEquals(1, stale.errorCount)
        assertEquals("VG_STALE_RECEIPT", stale.errorCode)
        assertEquals(1L, binding.snapshot().streamToken)
        assertEquals(false, binding.snapshot().disposed)
        binding.dispose()
    }

    @Test
    fun `abandon fences delayed START publication and preserves the view and group cut`() {
        val messenger = MethodTestMessenger()
        val enteredPublication = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 79,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            executor = executor,
            postToMain = { task -> task() },
            beforeControlPublication = {
                enteredPublication.countDown()
                while (!releasePublication.await(10, TimeUnit.MILLISECONDS)) {
                    // Deliberately ignore executor interruption so the test can
                    // release the exact late native continuation.
                }
            },
        )
        val original = binding.snapshot()
        val qualifier = original.nativeStreamToken + original.workerBindingToken
        val channel = MethodChannel(messenger, "visibility_grid_v2_control_79")
        val start = RecordingResult()

        channel.invokeMethod("start", qualifier + M0aControlCodec.encodeRequest(startRequest()), start)
        assertTrue(enteredPublication.await(2, TimeUnit.SECONDS))

        val abandoned = RecordingResult()
        channel.invokeMethod("abandonBinding", qualifier, abandoned)
        assertTrue(abandoned.completed.await(2, TimeUnit.SECONDS))
        assertEquals(1, abandoned.successCount)
        assertTrue(start.completed.await(2, TimeUnit.SECONDS))
        assertEquals("VG_STREAM_BINDING_ABANDONED", start.errorCode)

        releasePublication.countDown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
        assertEquals(0, start.successCount)
        assertEquals(1, start.errorCount)

        val replacement = RecordingResult()
        channel.invokeMethod("bindingSnapshot", null, replacement)
        assertTrue(replacement.completed.await(2, TimeUnit.SECONDS))
        val snapshot = replacement.successValue as Map<*, *>
        assertNotEquals(original.bindingGeneration, snapshot["bindingGeneration"])
        assertArrayEquals(original.arSessionIdentity, snapshot["arSessionIdentity"] as ByteArray)
        assertArrayEquals(original.viewInstanceId, snapshot["viewInstanceId"] as ByteArray)
        assertEquals(original.viewGeneration, snapshot["viewGeneration"])
        assertEquals(uuid(20).hex(), snapshot["sessionId"])
        assertEquals(uuid(40).hex(), snapshot["captureGroupId"])
        assertEquals(3L, snapshot["sessionGeneration"])
        assertEquals(4L, snapshot["groupGeneration"])
        assertEquals(5L, snapshot["coverageEpoch"])
        assertEquals(0L, snapshot["acceptedControls"])
        assertEquals(false, snapshot["initialTransactionQueued"])

        binding.dispose()
    }

    @Test
    fun `real receipt query rejects inexact and out of range numbers before lookup`() {
        val authority = M0aCommittedBaselineAuthority()
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 86,
            committedBaselineAuthority = authority,
            postToMain = { task -> task() },
        )
        val snapshot = binding.snapshot()
        val channel = MethodChannel(messenger, "visibility_grid_v2_control_86")
        val validQuery = M0aCommitReceiptQueryV1(
            controlRequestId = uuid(1),
            scope = M0aCommittedBaselineScopeV1(
                sessionId = uuid(20),
                captureGroupId = uuid(40),
                sessionGeneration = 0,
                groupGeneration = 0,
            ),
            nativeStreamToken = snapshot.nativeStreamToken,
            workerBindingToken = snapshot.workerBindingToken,
            streamToken = 1,
            requestSequence = 1,
            transactionId = 1,
            targetGeometryRevision = 0,
            targetLineageRevision = 0,
        )
        authority.publishAbandon(validQuery)

        fun query(overrides: Map<String, Any?> = emptyMap()): RecordingResult {
            val result = RecordingResult()
            channel.invokeMethod(
                "queryCommitReceipt",
                validQuery.toChannelMap() + overrides + mapOf(
                    "currentBindingQualifier" to
                        snapshot.nativeStreamToken + snapshot.workerBindingToken,
                ),
                result,
            )
            assertTrue(result.completed.await(2, TimeUnit.SECONDS))
            return result
        }

        listOf(
            "targetGeometryRevision" to 1.5,
            "targetGeometryRevision" to Double.NaN,
            "targetGeometryRevision" to Double.POSITIVE_INFINITY,
            "targetGeometryRevision" to -9.223372036854778E18,
            "targetGeometryRevision" to 9.223372036854778E18,
        ).forEach { (field, value) ->
            val rejected = query(mapOf(field to value))
            assertEquals(0, rejected.successCount)
            assertEquals(1, rejected.errorCount)
            assertEquals("VG_PROTOCOL_INVALID", rejected.errorCode)
        }

        // The invalid attempts above did not alter or consume the exact receipt.
        val valid = query()
        assertEquals(1, valid.successCount)
        assertEquals("abandon", (valid.successValue as Map<*, *>) ["decision"])
        binding.dispose()
    }

    @Test
    fun `real receipt query preserves signed 64 bit boundary values`() {
        val authority = M0aCommittedBaselineAuthority()
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 87,
            committedBaselineAuthority = authority,
            postToMain = { task -> task() },
        )
        val snapshot = binding.snapshot()
        val maximum = Long.MAX_VALUE
        val query = M0aCommitReceiptQueryV1(
            controlRequestId = uuid(2),
            scope = M0aCommittedBaselineScopeV1(
                sessionId = uuid(21),
                captureGroupId = uuid(41),
                sessionGeneration = maximum,
                groupGeneration = maximum,
            ),
            nativeStreamToken = snapshot.nativeStreamToken,
            workerBindingToken = snapshot.workerBindingToken,
            streamToken = maximum,
            requestSequence = maximum,
            transactionId = maximum,
            targetGeometryRevision = maximum,
            targetLineageRevision = maximum,
        )
        authority.publishAbandon(query)

        val result = RecordingResult()
        MethodChannel(messenger, "visibility_grid_v2_control_87").invokeMethod(
            "queryCommitReceipt",
            query.toChannelMap() + mapOf(
                "currentBindingQualifier" to
                    snapshot.nativeStreamToken + snapshot.workerBindingToken,
            ),
            result,
        )
        assertTrue(result.completed.await(2, TimeUnit.SECONDS))
        assertEquals(1, result.successCount)
        val response = result.successValue as Map<*, *>
        assertEquals(maximum, response["streamToken"])
        assertEquals(maximum, response["requestSequence"])
        assertEquals(maximum, response["transactionId"])
        assertEquals(maximum, response["targetGeometryRevision"])
        assertEquals(maximum, response["targetLineageRevision"])
        val scope = response["sessionGeneration"]
        assertEquals(maximum, scope)
        assertEquals(maximum, response["groupGeneration"])
        binding.dispose()
    }

    private fun M0aCommitReceiptQueryV1.toChannelMap(): Map<String, Any> = mapOf(
        "controlRequestId" to controlRequestId.hex(),
        "sessionId" to scope.sessionId.hex(),
        "captureGroupId" to scope.captureGroupId.hex(),
        "sessionGeneration" to scope.sessionGeneration,
        "groupGeneration" to scope.groupGeneration,
        "nativeStreamToken" to nativeStreamToken,
        "workerBindingToken" to workerBindingToken,
        "streamToken" to streamToken,
        "requestSequence" to requestSequence,
        "transactionId" to transactionId,
        "targetGeometryRevision" to targetGeometryRevision,
        "targetLineageRevision" to targetLineageRevision,
    )

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

    private fun M0aUuid.hex(): String = bytes.joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun stripQualifier(bytes: ByteArray, qualifier: ByteArray): ByteArray {
        assertTrue(bytes.size >= qualifier.size)
        assertArrayEquals(qualifier, bytes.copyOfRange(0, qualifier.size))
        return bytes.copyOfRange(qualifier.size, bytes.size)
    }
}

private class RecordingResult : MethodChannel.Result {
    val completed = CountDownLatch(1)
    var successCount = 0
    var errorCount = 0
    var successValue: Any? = null
    var errorCode: String? = null

    override fun success(result: Any?) {
        successCount++
        successValue = result
        completed.countDown()
    }

    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
        errorCount++
        this.errorCode = errorCode
        completed.countDown()
    }

    override fun notImplemented() {
        completed.countDown()
    }
}

private class RecordingBinaryReply : BinaryMessenger.BinaryReply {
    val completed = CountDownLatch(1)
    var bytes: ByteArray? = null

    override fun reply(reply: ByteBuffer?) {
        bytes = reply?.duplicate()?.let { buffer ->
            if (buffer.position() > 0) buffer.flip()
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        completed.countDown()
    }
}

private class MethodTestMessenger : BinaryMessenger {
    private val handlers = mutableMapOf<String, BinaryMessenger.BinaryMessageHandler>()

    override fun send(channel: String, message: ByteBuffer?) = send(channel, message, null)

    override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) {
        val handler = handlers[channel]
        if (handler == null) {
            callback?.reply(null)
        } else {
            val inbound = message?.duplicate()?.apply {
                if (position() > 0) flip()
            }
            handler.onMessage(inbound) { reply ->
                val outbound = reply?.duplicate()?.apply {
                    if (position() > 0) flip()
                }
                callback?.reply(outbound)
            }
        }
    }

    override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) {
        synchronized(handlers) {
            if (handler == null) handlers.remove(channel) else handlers[channel] = handler
        }
    }
}
