package com.uhg0.ar_flutter_plugin_2.m0

import io.flutter.plugin.common.BinaryMessenger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only execution of the real packed stream and lifecycle handoff path. */
internal object M0aIssue98HandoffRunner {
    data class Receipt(
        val oldTransactionId: Long,
        val oldRequestSequence: Long,
        val terminalResultFlags: Int,
        val terminalNextExpectedRequestSequence: Long,
        val freshTransactionId: Long,
        val freshRequestSequence: Long,
        val nextTransactionId: Long,
        val geometryRevision: Long,
        val lineageRevision: Long,
        val captureRevision: Long,
        val coverageRevision: Long,
        val acceptedStyleRevision: Long,
        val regionManifestRevision: Long,
        val nextSurfaceIdHighWater: Long,
        val schemaRootRevision: Long,
        val semanticEffectCount: Int,
        val oldTokenPublicationCount: Int,
        val rootIsolateSurfaceBytes: Long,
        val oldClosedResources: Long,
        val freshActiveResources: Long,
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "oldTransactionId" to oldTransactionId,
            "oldRequestSequence" to oldRequestSequence,
            "terminalResultFlags" to terminalResultFlags,
            "terminalNextExpectedRequestSequence" to terminalNextExpectedRequestSequence,
            "freshTransactionId" to freshTransactionId,
            "freshRequestSequence" to freshRequestSequence,
            "nextTransactionId" to nextTransactionId,
            "geometryRevision" to geometryRevision,
            "lineageRevision" to lineageRevision,
            "captureRevision" to captureRevision,
            "coverageRevision" to coverageRevision,
            "acceptedStyleRevision" to acceptedStyleRevision,
            "regionManifestRevision" to regionManifestRevision,
            "nextSurfaceIdHighWater" to nextSurfaceIdHighWater,
            "schemaRootRevision" to schemaRootRevision,
            "semanticEffectCount" to semanticEffectCount,
            "oldTokenPublicationCount" to oldTokenPublicationCount,
            "rootIsolateSurfaceBytes" to rootIsolateSurfaceBytes,
            "oldClosedResources" to oldClosedResources,
            "freshActiveResources" to freshActiveResources,
        )
    }

    fun run(): Receipt {
        val oldBaseline = M0aCommittedBaselineV1(
            transactionId = Long.MAX_VALUE,
            geometryRevision = 11,
            lineageRevision = 12,
            styleRevision = 15,
            captureRevision = 13,
            coverageRevision = 14,
            regionManifestRevision = 16,
            nextSurfaceIdHighWater = 17,
            schemaRootRevision = 18,
        )
        val oldMessenger = LocalMessenger(9801)
        val oldStream = M0aVisibilitySurfaceStreamChannel(
            oldMessenger, 9801, initialNextExpectedSequence = Long.MAX_VALUE,
        )
        oldStream.setCommittedBaseline(
            oldBaseline.transactionId, oldBaseline.geometryRevision,
            oldBaseline.lineageRevision, oldBaseline.styleRevision,
            captureRevision = oldBaseline.captureRevision,
            coverageRevision = oldBaseline.coverageRevision,
            regionManifestRevision = oldBaseline.regionManifestRevision,
            schemaRootRevision = oldBaseline.schemaRootRevision,
            nextSurfaceIdHighWater = oldBaseline.nextSurfaceIdHighWater,
        )
        val terminal = M0aPacketCodec.decodeResponse(oldMessenger.exchange(request(
            token = 99, sequence = Long.MAX_VALUE, flags = 1 shl 5,
            acknowledgedTransaction = Long.MAX_VALUE,
            acknowledgedGeometry = oldBaseline.geometryRevision,
            acknowledgedLineage = oldBaseline.lineageRevision,
            styleRevision = oldBaseline.styleRevision,
        )))
        check(terminal.resultFlags == 5 &&
            terminal.nextExpectedRequestSequence == Long.MAX_VALUE &&
            terminal.transactionId == Long.MAX_VALUE)
        val oldResourcesBefore = oldStream.lifecycleResources()
        val oldRootBytes = oldStream.transportInstrumentation.snapshot().ordinaryRootSurfaceBytes
        oldStream.dispose()
        val oldResourcesAfter = oldStream.lifecycleResources()
        val oldClosedResources = oldResourcesBefore.total() - oldResourcesAfter.total()

        val lifecycle = M0aControlLifecycle(initialCommittedBaseline = oldBaseline)
        val startRequest = startRequest(oldBaseline)
        val start = M0aControlCodec.decodeResponse(
            lifecycle.handle(startRequest, M0aControlCodec.encodeRequest(startRequest)),
        )
        check(start.outcome == 0 && start.nativeTransactionId == 0L &&
            start.nextExchangeRequestSequence == 1L)
        val freshBaseline = lifecycle.committedBaseline()
        check(freshBaseline == M0aCommittedBaselineV1.forFreshBinding(oldBaseline))

        val freshMessenger = LocalMessenger(9802)
        val effects = AtomicInteger()
        val freshStream = M0aVisibilitySurfaceStreamChannel(
            messenger = freshMessenger,
            viewId = 9802,
            controlLifecycle = lifecycle,
            onCommitPublished = { _, _ -> effects.incrementAndGet() },
        )
        freshStream.queueStructuralTransaction(M0aStructuralTransactionProducerV1.produce(
            transactionId = 1,
            baseGeometryRevision = freshBaseline.geometryRevision,
            targetGeometryRevision = freshBaseline.geometryRevision + 1,
            targetLineageRevision = freshBaseline.lineageRevision + 1,
            bytes = byteArrayOf(),
        ))
        val begin = M0aPacketCodec.decodeResponse(freshMessenger.exchange(request(
            lifecycle.streamToken(), 1, acknowledgedTransaction = 0,
            acknowledgedGeometry = freshBaseline.geometryRevision,
            acknowledgedLineage = freshBaseline.lineageRevision,
            styleRevision = freshBaseline.styleRevision,
        )))
        val commit = M0aPacketCodec.decodeResponse(freshMessenger.exchange(request(
            lifecycle.streamToken(), 2, acknowledgedTransaction = 0,
            acknowledgedGeometry = freshBaseline.geometryRevision,
            acknowledgedLineage = freshBaseline.lineageRevision,
            styleRevision = freshBaseline.styleRevision,
        )))
        check(begin.messageKind == 2 && commit.messageKind == 4 && effects.get() == 1)
        val ack = M0aPacketCodec.decodeResponse(freshMessenger.exchange(request(
            lifecycle.streamToken(), 3, acknowledgedTransaction = 1,
            acknowledgedGeometry = freshBaseline.geometryRevision + 1,
            acknowledgedLineage = freshBaseline.lineageRevision + 1,
            styleRevision = freshBaseline.styleRevision,
        )))
        check(ack.messageKind == 0 && ack.transactionId == 1L)

        val effectsBeforeOldToken = effects.get()
        val stale = M0aPacketCodec.decodeResponse(freshMessenger.exchange(request(
            token = 99, sequence = 4, acknowledgedTransaction = 1,
            acknowledgedGeometry = freshBaseline.geometryRevision + 1,
            acknowledgedLineage = freshBaseline.lineageRevision + 1,
            styleRevision = freshBaseline.styleRevision,
        )))
        check(stale.errorId == 4 && stale.resultFlags == 2 &&
            stale.nextExpectedRequestSequence == 0L && effects.get() == effectsBeforeOldToken)
        val freshResources = freshStream.lifecycleResources().total()
        val freshRootBytes = freshStream.transportInstrumentation.snapshot().ordinaryRootSurfaceBytes
        freshStream.dispose()
        return Receipt(
            oldBaseline.transactionId, Long.MAX_VALUE, terminal.resultFlags,
            terminal.nextExpectedRequestSequence, freshBaseline.transactionId, 1, 1,
            freshBaseline.geometryRevision, freshBaseline.lineageRevision,
            freshBaseline.captureRevision, freshBaseline.coverageRevision,
            freshBaseline.styleRevision, freshBaseline.regionManifestRevision,
            freshBaseline.nextSurfaceIdHighWater, freshBaseline.schemaRootRevision,
            effects.get(), effects.get() - effectsBeforeOldToken,
            oldRootBytes + freshRootBytes, oldClosedResources, freshResources,
        )
    }

    private fun M0aVisibilitySurfaceStreamChannel.LifecycleResources.total() =
        handlerCount + timeoutSchedulerCount + pendingReplyCount

    private fun request(
        token: Long,
        sequence: Long,
        flags: Int = 0,
        acknowledgedTransaction: Long = 0,
        acknowledgedGeometry: Long = 0,
        acknowledgedLineage: Long = 0,
        styleRevision: Long = 0,
    ) = M0aPacketCodec.encodeRequest(M0aPacketCodec.Request(
        flags, token, acknowledgedTransaction, acknowledgedGeometry,
        acknowledgedLineage, styleRevision, 4096, emptyList(), byteArrayOf(), sequence,
    ))

    private fun startRequest(baseline: M0aCommittedBaselineV1): M0aControlRequest {
        val payload = M0aStartRequestCodecV2.defaultPayload()
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(7, 1)
            baseline.resultRevisionCut().take(10).forEachIndexed { index, value ->
                putLong(56 + index * 8, value)
            }
        }
        fun uuid(seed: Int): M0aUuid = M0aUuid(ByteArray(16) { (seed + it).toByte() }.also {
            it[6] = 0x40
            it[8] = 0x80.toByte()
        })
        return M0aControlRequest(
            M0aControlOperation.START, 0, uuid(1), uuid(20), uuid(40),
            1, 1, 1, 0, payload,
        )
    }

    private class LocalMessenger(viewId: Int) : BinaryMessenger {
        private val names = setOf(
            "visibility_surface_stream_$viewId", "visibility_surface_metrics_$viewId",
        )
        private val handlers = mutableMapOf<String, BinaryMessenger.BinaryMessageHandler>()
        override fun send(channel: String, message: ByteBuffer?) = send(channel, message, null)
        override fun send(
            channel: String,
            message: ByteBuffer?,
            callback: BinaryMessenger.BinaryReply?,
        ) {
            check(channel in names)
            handlers[channel]?.onMessage(message) { response ->
                callback?.reply(response?.let { buffer ->
                    val bytes = ByteArray(buffer.position())
                    buffer.duplicate().apply { flip(); get(bytes) }
                    ByteBuffer.wrap(bytes)
                })
            } ?: callback?.reply(null)
        }
        override fun setMessageHandler(
            channel: String,
            handler: BinaryMessenger.BinaryMessageHandler?,
        ) {
            check(channel in names)
            if (handler == null) handlers.remove(channel) else handlers[channel] = handler
        }
        fun exchange(bytes: ByteArray): ByteArray {
            val latch = CountDownLatch(1)
            var result: ByteArray? = null
            send(names.first { it.contains("stream") }, ByteBuffer.wrap(bytes)) { response ->
                result = response?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.slice().get(it) }
                }
                latch.countDown()
            }
            check(latch.await(5, TimeUnit.SECONDS)) { "Issue 98 stream exchange timed out" }
            return checkNotNull(result)
        }
    }
}
