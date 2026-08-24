package com.uhg0.ar_flutter_plugin_2.m0

import io.flutter.plugin.common.BinaryMessenger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class M0aVisibilitySurfaceStreamChannelTest {
    @Test
    fun `binding qualifier rejects stale token before stream admission`() {
        val messenger = TestMessenger(88)
        val qualifier = ByteArray(32) { (it + 1).toByte() }
        val binding = M0aVisibilitySurfaceStreamChannel(
            messenger,
            88,
            bindingQualifier = qualifier,
        )
        val packet = request(sequence = 1, token = 88)

        assertNull(messenger.tryExchange(packet))
        val stale = qualifier.copyOf().also { it[0] = 99 } + packet
        assertNull(messenger.tryExchange(stale))
        assertEquals(0L, binding.transportInstrumentation.snapshot().acceptedRequests)

        val qualifiedResponse = messenger.exchange(qualifier + packet)
        assertArrayEquals(qualifier, qualifiedResponse.copyOfRange(0, qualifier.size))
        val response = M0aPacketCodec.decodeResponse(
            qualifiedResponse.copyOfRange(qualifier.size, qualifiedResponse.size),
        )
        assertEquals(1L, response.requestSequence)
        assertEquals(1L, binding.transportInstrumentation.snapshot().acceptedRequests)
        binding.dispose()
    }

    @Test
    fun `packet codec validates all portable ordinals and response envelope fields`() {
        val base = M0aPacketCodec.encodeRequest(
            M0aPacketCodec.Request(
                requestFlags = 0,
                streamToken = 91,
                acknowledgedTransactionId = 0,
                acknowledgedGeometryRevision = 0,
                acknowledgedLineageRevision = 0,
                nextStyleRevision = 0,
                maximumResponseBytes = 4096,
                styleRecords = emptyList(),
                commandBytes = byteArrayOf(),
                requestSequence = 1,
            ),
        )
        listOf(24, 32, 40, 48).forEach { offset ->
            val mutated = base.copyOf()
            ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(offset, Long.MIN_VALUE)
            rewriteCrc(mutated, 72)
            assertThrows(IllegalArgumentException::class.java) {
                M0aPacketCodec.decodeRequest(mutated)
            }
        }

        val invalidKind = M0aPacketCodec.encodeResponse(
            M0aPacketCodec.noChanges(
                streamToken = 91,
                requestSequence = 1,
                nextExpectedRequestSequence = 2,
            ),
            4096,
        ).also { packet ->
            packet[8] = 6
            rewriteCrc(packet, 104)
        }
        assertThrows(IllegalArgumentException::class.java) {
            M0aPacketCodec.decodeResponse(invalidKind)
        }

        val oversized = ByteArray(M0aPacketCodec.catchUpMaximumBytes + 1)
        oversized[0] = 'V'.code.toByte()
        oversized[1] = 'G'.code.toByte()
        oversized[2] = 'S'.code.toByte()
        oversized[3] = '2'.code.toByte()
        ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(4, 2)
            putShort(6, M0aPacketCodec.responseHeaderBytes.toShort())
            putInt(16, oversized.size)
            putInt(100, oversized.size - M0aPacketCodec.responseHeaderBytes)
        }
        rewriteCrc(oversized, 104)
        assertThrows(IllegalArgumentException::class.java) {
            M0aPacketCodec.decodeResponse(oversized)
        }

        assertThrows(IllegalArgumentException::class.java) {
            M0aPacketCodec.encodeResponse(
                M0aPacketCodec.noChanges(
                    streamToken = 91,
                    requestSequence = 1,
                    nextExpectedRequestSequence = 2,
                ).copy(
                    payload = ByteArray(M0aPacketCodec.catchUpMaximumBytes),
                ),
                M0aPacketCodec.catchUpMaximumBytes + 1,
            )
        }
    }

    @Test
    fun `serial binding returns exact response for duplicate and rejects conflict`() {
        val messenger = TestMessenger(17)
        val binding = M0aVisibilitySurfaceStreamChannel(messenger, 17)
        val request = request(sequence = 1, token = 91)

        val first = messenger.exchange(request)
        val duplicate = messenger.exchange(request)
        assertArrayEquals(first, duplicate)
        assertEquals(1L, M0aPacketCodec.decodeResponse(first).requestSequence)
        val telemetry = binding.transportInstrumentation.snapshot()
        assertEquals(2L, telemetry.submittedRequests)
        assertEquals(1L, telemetry.acceptedRequests)
        assertEquals(1L, telemetry.replayedRequests)
        assertEquals(request.size * 2L, telemetry.submittedRequestBytes)
        assertEquals(first.size.toLong(), telemetry.responseBytes)
        assertEquals(0L, telemetry.ordinaryRootSurfaceBytes)
        assertTrue(telemetry.peakQueueDepth >= 1)
        assertEquals(16 * 1024, telemetry.resourceLimits.requestCeilingBytes)
        assertEquals(16 * 1024, telemetry.resourceLimits.ordinaryResponseCeilingBytes)
        assertEquals(64 * 1024, telemetry.resourceLimits.catchUpResponseCeilingBytes)
        assertEquals(1024, telemetry.resourceLimits.diagnosticSummaryBytes)
        assertEquals(5, telemetry.resourceLimits.diagnosticSummaryRateHz)
        assertEquals(18, telemetry.resourceLimits.structuralTransactionFrames)
        assertEquals(1024, telemetry.resourceLimits.structuralChunkBytes)
        assertTrue(telemetry.allocationBytesObserved > 0)
        assertTrue(telemetry.maximumSingleAllocationBytes <= 64 * 1024)
        assertTrue(telemetry.peakWorkingSetBytes <= 256 * 1024)
        assertEquals(request.size + first.size.toLong(), telemetry.retainedAllocationBytes)
        assertEquals(0L, telemetry.compressionBytesObserved)
        assertEquals(0L, telemetry.decompressionBytesObserved)
        assertEquals(0L, telemetry.ordinaryRootIsolateTimeNanos)
        assertEquals(0, telemetry.resourceLimits.compressionInputBytes)
        assertEquals(0, telemetry.resourceLimits.decompressionOutputBytes)
        assertEquals(256 * 1024, telemetry.resourceLimits.scratchBytesPerSide)
        assertTrue(binding.transportInstrumentation.encodeBoundedSummary().size <= 1024)
        assertEquals(168, binding.transportInstrumentation.tryEncodeBoundedSummary(1_000_000_000)!!.size)
        assertNull(binding.transportInstrumentation.tryEncodeBoundedSummary(1_100_000_000))
        assertEquals(168, binding.transportInstrumentation.tryEncodeBoundedSummary(1_200_000_000)!!.size)

        val conflict = messenger.exchange(request(sequence = 1, token = 92))
        val conflictResponse = M0aPacketCodec.decodeResponse(conflict)
        assertEquals(255, conflictResponse.messageKind)
        assertEquals(30, conflictResponse.errorId)
        assertEquals(1L, conflictResponse.requestSequence)

        binding.dispose()
    }

    @Test
    fun `binding rejects a second outstanding invocation without queue growth`() {
        val messenger = TestMessenger(34)
        val executor = HoldingExecutor()
        val binding = M0aVisibilitySurfaceStreamChannel(
            messenger,
            34,
            workerExecutor = executor,
            shutdownWorkerOnDispose = false,
        )
        val firstCompleted = CountDownLatch(1)
        messenger.send(
            "visibility_surface_stream_34",
            ByteBuffer.wrap(request(sequence = 1, token = 34)),
        ) { firstCompleted.countDown() }

        val rejected = M0aPacketCodec.decodeResponse(
            messenger.exchange(request(sequence = 2, token = 34)),
        )
        assertEquals(255, rejected.messageKind)
        assertEquals(8, rejected.errorId)
        assertEquals(1L, rejected.nextExpectedRequestSequence)
        assertEquals(1, binding.transportInstrumentation.snapshot().peakQueueDepth)

        executor.runQueued()
        assertTrue(firstCompleted.await(2, TimeUnit.SECONDS))
        binding.dispose()
    }

    @Test
    fun `binding owns structural transaction receiver through disposal`() {
        val messenger = TestMessenger(25)
        val binding = M0aVisibilitySurfaceStreamChannel(messenger, 25)
        binding.structuralTransactionReceiver.begin(
            M0aTransactionBeginV1(
                transactionId = 1,
                baseGeometryRevision = 1,
                targetGeometryRevision = 2,
                targetLineageRevision = 3,
                chunkCount = 0,
                totalBytes = 0,
                payloadChecksum = 0,
            ),
        )
        assertEquals(M0aStructuralTransactionState.SENDING_BEGIN, binding.structuralTransactionReceiver.state)
        binding.dispose()
        assertEquals(M0aStructuralTransactionState.STOPPED, binding.structuralTransactionReceiver.state)
    }

    @Test
    fun `serial worker pull delivers queued structural frames and replays exactly`() {
        val messenger = TestMessenger(27)
        val binding = M0aVisibilitySurfaceStreamChannel(messenger, 27)
        val frames = M0aStructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = byteArrayOf(1, 2, 3, 4, 5),
            maximumChunkBytes = 1024,
        )
        binding.queueStructuralTransaction(frames)

        val responses = frames.indices.map { index ->
            messenger.exchange(request(sequence = index.toLong() + 1, token = 27))
        }
        responses.forEachIndexed { index, packet ->
            val response = M0aPacketCodec.decodeResponse(packet)
            val frame = M0aTransactionResponseCodecV1.decodeFrame(response)
            assertEquals(frames[index]::class, frame::class)
            assertEquals(index.toLong() + 1, response.requestSequence)
        }
        assertArrayEquals(
            responses.last(),
            messenger.exchange(request(sequence = responses.size.toLong(), token = 27)),
        )
        assertEquals(1L, binding.transportInstrumentation.snapshot().replayedRequests)
        binding.dispose()
    }

    @Test
    fun `stream accepts only the active control lifecycle token`() {
        val lifecycle = M0aControlLifecycle()
        val start = controlRequest(M0aControlOperation.START, 0, 1)
        lifecycle.handle(start, M0aControlCodec.encodeRequest(start))
        val messenger = TestMessenger(28)
        val executor = Executors.newSingleThreadExecutor()
        val binding = M0aVisibilitySurfaceStreamChannel(
            messenger = messenger,
            viewId = 28,
            workerExecutor = executor,
            shutdownWorkerOnDispose = false,
            controlLifecycle = lifecycle,
        )

        val stale = M0aPacketCodec.decodeResponse(messenger.exchange(request(1, 99)))
        assertEquals(255, stale.messageKind)
        assertEquals(4, stale.errorId)
        val accepted = M0aPacketCodec.decodeResponse(messenger.exchange(request(1, 1)))
        assertEquals(0, accepted.messageKind)
        binding.dispose()
        executor.shutdownNow()
    }

    @Test
    fun `stream adopts the baseline carried by the shared control lifecycle`() {
        val lifecycle = M0aControlLifecycle(
            initialCommittedBaseline = M0aCommittedBaselineV1(9, 10, 11, 12),
        )
        val start = controlRequest(M0aControlOperation.START, 0, 1)
        lifecycle.handle(start, M0aControlCodec.encodeRequest(start))
        val messenger = TestMessenger(32)
        val binding = M0aVisibilitySurfaceStreamChannel(
            messenger = messenger,
            viewId = 32,
            controlLifecycle = lifecycle,
        )
        val response = M0aPacketCodec.decodeResponse(
            messenger.exchange(
                request(
                    sequence = 1,
                    token = 1,
                    acknowledgedTransaction = 9,
                    acknowledgedGeometry = 10,
                    acknowledgedLineage = 11,
                    styleRevision = 12,
                ),
            ),
        )
        assertEquals(0, response.messageKind)
        assertEquals(9L, response.transactionId)
        assertEquals(10L, response.targetGeometryRevision)
        assertEquals(11L, response.targetLineageRevision)
        assertEquals(12L, response.acceptedStyleRevision)
        binding.dispose()
    }

    @Test
    fun `empty acknowledgement is fenced against a restored baseline`() {
        val messenger = TestMessenger(33)
        val binding = M0aVisibilitySurfaceStreamChannel(messenger, 33)
        binding.setCommittedBaseline(9, 10, 11, 12)
        val response = M0aPacketCodec.decodeResponse(
            messenger.exchange(
                request(
                    sequence = 1,
                    token = 33,
                    styleRevision = 13,
                ),
            ),
        )
        assertEquals(5, response.messageKind)
        binding.dispose()
    }

    @Test
    fun `exact restored and post commit baselines advance without resync`() {
        val restoredMessenger = TestMessenger(30)
        val restoredBinding = M0aVisibilitySurfaceStreamChannel(restoredMessenger, 30)
        restoredBinding.setCommittedBaseline(9, 10, 11, 12)
        val restoredResponse = M0aPacketCodec.decodeResponse(
            restoredMessenger.exchange(
                request(
                    sequence = 1,
                    token = 30,
                    acknowledgedTransaction = 9,
                    acknowledgedGeometry = 10,
                    acknowledgedLineage = 11,
                    styleRevision = 12,
                ),
            ),
        )
        assertEquals(0, restoredResponse.messageKind)
        restoredBinding.dispose()

        val messenger = TestMessenger(29)
        val binding = M0aVisibilitySurfaceStreamChannel(messenger, 29)
        binding.queueStructuralTransaction(
            M0aStructuralTransactionProducerV1.produce(
                transactionId = 3,
                baseGeometryRevision = 4,
                targetGeometryRevision = 5,
                targetLineageRevision = 6,
                bytes = byteArrayOf(1),
            ),
        )
        messenger.exchange(request(1, 29))
        messenger.exchange(request(2, 29))
        messenger.exchange(request(3, 29))
        val restored = M0aPacketCodec.decodeResponse(
            messenger.exchange(
                request(
                    sequence = 4,
                    token = 29,
                    acknowledgedTransaction = 3,
                    acknowledgedGeometry = 5,
                    acknowledgedLineage = 6,
                ),
            ),
        )
        assertEquals(0, restored.messageKind)
        val mismatched = M0aPacketCodec.decodeResponse(
            messenger.exchange(
                request(
                    sequence = 5,
                    token = 29,
                    acknowledgedTransaction = 3,
                    acknowledgedGeometry = 5,
                    acknowledgedLineage = 7,
                ),
            ),
        )
        assertEquals(5, mismatched.messageKind)
        binding.dispose()
    }

    @Test
    fun `style acknowledgement is checked independently from structural baseline`() {
        val messenger = TestMessenger(31)
        val binding = M0aVisibilitySurfaceStreamChannel(messenger, 31)
        binding.setCommittedBaseline(0, 0, 0, 4)
        val adjacent = M0aPacketCodec.decodeResponse(
            messenger.exchange(
                request(
                    sequence = 1,
                    token = 31,
                    styleRevision = 5,
                    styleRecords = listOf(ByteArray(8)),
                ),
            ),
        )
        assertEquals(0, adjacent.messageKind)
        assertEquals(5, adjacent.acceptedStyleRevision)
        val empty = M0aPacketCodec.decodeResponse(
            messenger.exchange(
                request(
                    sequence = 2,
                    token = 31,
                    styleRevision = 5,
                ),
            ),
        )
        assertEquals(0, empty.messageKind)
        assertEquals(5, empty.acceptedStyleRevision)
        val mismatch = M0aPacketCodec.decodeResponse(
            messenger.exchange(
                request(
                    sequence = 3,
                    token = 31,
                    styleRevision = 7,
                    styleRecords = listOf(ByteArray(8)),
                ),
            ),
        )
        assertEquals(5, mismatch.messageKind)
        binding.dispose()
    }

    @Test
    fun `ahead acknowledgement emits packed resync required response`() {
        val messenger = TestMessenger(26)
        val binding = M0aVisibilitySurfaceStreamChannel(messenger, 26)

        val response = M0aPacketCodec.decodeResponse(
            messenger.exchange(
                request(sequence = 1, token = 12).copyAcknowledgement(
                    transaction = 1,
                ),
            ),
        )
        assertEquals(5, response.messageKind)
        assertEquals(8, response.resultFlags)
        assertEquals(0, response.errorId)
        assertEquals(2L, response.nextExpectedRequestSequence)

        val missingResync = M0aPacketCodec.decodeResponse(
            messenger.exchange(request(sequence = 2, token = 12)),
        )
        assertEquals(255, missingResync.messageKind)
        assertEquals(34, missingResync.errorId)

        val resync = M0aPacketCodec.Request(
            requestFlags = 1 shl 2,
            streamToken = 12,
            acknowledgedTransactionId = 1,
            acknowledgedGeometryRevision = 0,
            acknowledgedLineageRevision = 0,
            nextStyleRevision = 0,
            maximumResponseBytes = 4096,
            styleRecords = emptyList(),
            commandBytes = M0aResyncCommandV1(
                M0aResyncPayloadV1(
                    lastCommittedTransactionId = 1,
                    lastCommittedGeometryRevision = 0,
                    lastCommittedLineageRevision = 0,
                    failedTransactionId = 2,
                    reason = M0aResyncReason.INVALID_TRANSACTION_ORDER,
                ),
            ).encode(),
            requestSequence = 2,
        )
        val recovered = M0aPacketCodec.decodeResponse(
            messenger.exchange(M0aPacketCodec.encodeRequest(resync)),
        )
        assertEquals(0, recovered.messageKind)
        assertEquals(2L, recovered.requestSequence)
        binding.dispose()
    }

    @Test
    fun `binding reports sequence gap and malformed packet without crashing executor`() {
        val messenger = TestMessenger(18)
        val binding = M0aVisibilitySurfaceStreamChannel(messenger, 18)

        val gap = messenger.exchange(request(sequence = 2, token = 4))
        val gapResponse = M0aPacketCodec.decodeResponse(gap)
        assertEquals(255, gapResponse.messageKind)
        assertEquals(32, gapResponse.errorId)
        assertEquals(2L, gapResponse.requestSequence)
        assertEquals(1L, gapResponse.nextExpectedRequestSequence)

        val malformed = request(sequence = 1, token = 4).also { it[20] = (it[20].toInt() xor 1).toByte() }
        val malformedResponse = M0aPacketCodec.decodeResponse(messenger.exchange(malformed))
        assertEquals(255, malformedResponse.messageKind)
        assertEquals(6, malformedResponse.errorId)

        val accepted = messenger.exchange(request(sequence = 1, token = 4))
        assertEquals(0, M0aPacketCodec.decodeResponse(accepted).messageKind)
        assertEquals(0, M0aPacketCodec.decodeResponse(messenger.exchange(request(sequence = 2, token = 4))).messageKind)
        val stale = M0aPacketCodec.decodeResponse(messenger.exchange(request(sequence = 1, token = 5)))
        assertEquals(255, stale.messageKind)
        assertEquals(31, stale.errorId)
        binding.dispose()
    }

    @Test
    fun `dispose removes handler and a replacement binding can serve the view`() {
        val messenger = TestMessenger(19)
        val firstBinding = M0aVisibilitySurfaceStreamChannel(messenger, 19)
        messenger.exchange(request(sequence = 1, token = 5))
        firstBinding.dispose()
        assertNull(messenger.tryExchange(request(sequence = 1, token = 5)))

        val replacement = M0aVisibilitySurfaceStreamChannel(messenger, 19)
        val response = messenger.exchange(request(sequence = 1, token = 5))
        assertEquals(0, M0aPacketCodec.decodeResponse(response).messageKind)
        replacement.dispose()
    }

    @Test
    fun `worker rejection returns stable binding lost error and replacement recovers`() {
        val messenger = TestMessenger(20)
        val failedBinding = M0aVisibilitySurfaceStreamChannel(
            messenger,
            20,
            workerExecutor = Executor { throw RejectedExecutionException("worker exited") },
            shutdownWorkerOnDispose = false,
        )

        val failed = M0aPacketCodec.decodeResponse(
            messenger.exchange(request(sequence = 1, token = 6)),
        )
        assertEquals(255, failed.messageKind)
        assertEquals(144, failed.errorId)
        assertEquals(1L, failed.requestSequence)
        failedBinding.dispose()

        val replacement = M0aVisibilitySurfaceStreamChannel(messenger, 20)
        val recovered = M0aPacketCodec.decodeResponse(
            messenger.exchange(request(sequence = 1, token = 6)),
        )
        assertEquals(0, recovered.messageKind)
        replacement.dispose()
    }

    @Test
    fun `accepted worker exception returns binding lost error and replacement recovers`() {
        val messenger = TestMessenger(24)
        val failedBinding = M0aVisibilitySurfaceStreamChannel(
            messenger,
            24,
            workerExecutor = Executor { command ->
                Thread(command, "m0a-accepted-worker-exit").start()
            },
            shutdownWorkerOnDispose = false,
            beforeWorkerProcessing = {
                throw IllegalStateException("worker exited after accepting task")
            },
        )

        val failed = M0aPacketCodec.decodeResponse(
            messenger.exchange(request(sequence = 1, token = 10)),
        )
        assertEquals(255, failed.messageKind)
        assertEquals(144, failed.errorId)
        assertEquals(1L, failed.requestSequence)
        assertNull(messenger.tryExchange(request(sequence = 2, token = 10)))
        failedBinding.dispose()

        val replacement = M0aVisibilitySurfaceStreamChannel(messenger, 24)
        val recovered = M0aPacketCodec.decodeResponse(
            messenger.exchange(request(sequence = 1, token = 10)),
        )
        assertEquals(0, recovered.messageKind)
        replacement.dispose()
    }

    @Test
    fun `reply delivery failure abandons the binding after claiming the reply`() {
        val messenger = TestMessenger(25)
        val executor = HoldingExecutor()
        val binding = M0aVisibilitySurfaceStreamChannel(
            messenger,
            25,
            workerExecutor = executor,
            shutdownWorkerOnDispose = false,
        )
        messenger.failNextReplyDelivery()
        messenger.send(
            "visibility_surface_stream_25",
            ByteBuffer.wrap(request(sequence = 1, token = 11)),
        ) { }

        executor.runQueued()

        assertNull(messenger.tryExchange(request(sequence = 2, token = 11)))
        binding.dispose()
    }

    @Test
    fun `queued work returns binding lost after disposal`() {
        val messenger = TestMessenger(21)
        val executor = HoldingExecutor()
        val binding = M0aVisibilitySurfaceStreamChannel(
            messenger,
            21,
            workerExecutor = executor,
            shutdownWorkerOnDispose = false,
        )
        val reply = arrayOfNulls<ByteArray>(1)
        val completed = CountDownLatch(1)
        messenger.send(
            "visibility_surface_stream_21",
            ByteBuffer.wrap(request(sequence = 1, token = 7)),
        ) { response ->
            reply[0] = response?.let { buffer ->
                val copy = ByteArray(buffer.remaining())
                buffer.slice().get(copy)
                copy
            }
            completed.countDown()
        }
        binding.dispose()
        executor.runQueued()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        val decoded = M0aPacketCodec.decodeResponse(reply[0]!!)
        assertEquals(255, decoded.messageKind)
        assertEquals(144, decoded.errorId)
        assertEquals(1L, decoded.requestSequence)
    }

    @Test
    fun `stalled accepted work abandons binding once and late worker output is ignored`() {
        val messenger = TestMessenger(22)
        val executor = HoldingExecutor()
        val scheduler = HoldingTimeoutScheduler()
        val binding = M0aVisibilitySurfaceStreamChannel(
            messenger,
            22,
            workerExecutor = executor,
            shutdownWorkerOnDispose = false,
            timeoutScheduler = scheduler,
        )
        val replies = AtomicInteger(0)
        val result = arrayOfNulls<ByteArray>(1)
        val completed = CountDownLatch(1)
        messenger.send(
            "visibility_surface_stream_22",
            ByteBuffer.wrap(request(sequence = 1, token = 8)),
        ) { response ->
            replies.incrementAndGet()
            result[0] = response?.let { buffer ->
                val copy = ByteArray(buffer.remaining())
                buffer.slice().get(copy)
                copy
            }
            completed.countDown()
        }

        scheduler.fireNext()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        val abandoned = M0aPacketCodec.decodeResponse(result[0]!!)
        assertEquals(255, abandoned.messageKind)
        assertEquals(142, abandoned.errorId)
        assertEquals(1L, abandoned.requestSequence)
        assertEquals(1, replies.get())
        assertNull(messenger.tryExchange(request(sequence = 2, token = 8)))

        executor.runQueued()
        assertEquals(1, replies.get())

        binding.dispose()
        val replacement = M0aVisibilitySurfaceStreamChannel(
            messenger,
            22,
            timeoutScheduler = HoldingTimeoutScheduler(),
        )
        val recovered = M0aPacketCodec.decodeResponse(
            messenger.exchange(request(sequence = 1, token = 8)),
        )
        assertEquals(0, recovered.messageKind)
        replacement.dispose()
    }

    @Test
    fun `active worker stall does not hold the timeout behind the channel monitor`() {
        val messenger = TestMessenger(23)
        val scheduler = HoldingTimeoutScheduler()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var workerThread: Thread? = null
        val binding = M0aVisibilitySurfaceStreamChannel(
            messenger,
            23,
            workerExecutor = Executor { command ->
                val thread = Thread(command, "m0a-active-stall")
                workerThread = thread
                thread.start()
            },
            shutdownWorkerOnDispose = false,
            timeoutScheduler = scheduler,
            beforeWorkerProcessing = {
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            },
        )
        val reply = arrayOfNulls<ByteArray>(1)
        val completed = CountDownLatch(1)
        messenger.send(
            "visibility_surface_stream_23",
            ByteBuffer.wrap(request(sequence = 1, token = 9)),
        ) { response ->
            reply[0] = response?.let { buffer ->
                val copy = ByteArray(buffer.remaining())
                buffer.slice().get(copy)
                copy
            }
            completed.countDown()
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        scheduler.fireNext()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        val abandoned = M0aPacketCodec.decodeResponse(reply[0]!!)
        assertEquals(255, abandoned.messageKind)
        assertEquals(142, abandoned.errorId)

        release.countDown()
        val completedWorker = checkNotNull(workerThread)
        completedWorker.join(2_000)
        assertTrue(!completedWorker.isAlive)
        binding.dispose()
    }

    private fun request(
        sequence: Long,
        token: Long,
        acknowledgedTransaction: Long = 0,
        acknowledgedGeometry: Long = 0,
        acknowledgedLineage: Long = 0,
        styleRevision: Long = 0,
        styleRecords: List<ByteArray> = emptyList(),
    ): ByteArray =
        M0aPacketCodec.encodeRequest(
            M0aPacketCodec.Request(
                requestFlags = 0,
                streamToken = token,
                acknowledgedTransactionId = acknowledgedTransaction,
                acknowledgedGeometryRevision = acknowledgedGeometry,
                acknowledgedLineageRevision = acknowledgedLineage,
                nextStyleRevision = styleRevision,
                maximumResponseBytes = 4096,
                styleRecords = styleRecords,
                commandBytes = byteArrayOf(),
                requestSequence = sequence,
            ),
        )

    private fun controlRequest(
        operation: M0aControlOperation,
        streamToken: Long,
        seed: Int,
    ): M0aControlRequest = M0aControlRequest(
        operation = operation,
        flags = 0,
        controlRequestId = uuid(seed),
        sessionId = uuid(20),
        captureGroupId = uuid(40),
        sessionGeneration = 1,
        groupGeneration = 1,
        coverageEpoch = 1,
        streamToken = streamToken,
        payload = if (operation == M0aControlOperation.START) {
            M0aStartRequestCodecV2.defaultPayload()
        } else {
            byteArrayOf()
        },
    )

    private fun uuid(seed: Int): M0aUuid {
        val bytes = ByteArray(16) { (seed + it).toByte() }
        bytes[6] = 0x40
        bytes[8] = 0x80.toByte()
        return M0aUuid(bytes)
    }

    private fun ByteArray.copyAcknowledgement(transaction: Long): ByteArray {
        val copy = copyOf()
        ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putLong(24, transaction)
        rewriteCrc(copy, 72)
        return copy
    }

    private fun rewriteCrc(packet: ByteArray, crcOffset: Int) {
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(crcOffset, 0)
        data.putInt(crcOffset, crc32(packet, crcOffset))
    }

    private fun crc32(bytes: ByteArray, zeroOffset: Int): Int {
        var crc = -1
        bytes.forEachIndexed { index, original ->
            val value = if (index in zeroOffset until zeroOffset + 4) 0 else original.toInt() and 0xff
            crc = crc xor value
            repeat(8) {
                crc = if ((crc and 1) == 1) (crc ushr 1) xor 0xedb88320.toInt() else crc ushr 1
            }
        }
        return crc xor -1
    }
}

private class HoldingExecutor : Executor {
    private var queued: Runnable? = null

    override fun execute(command: Runnable) {
        queued = command
    }

    fun runQueued() {
        checkNotNull(queued).run()
    }
}

private class HoldingTimeoutScheduler : M0aTimeoutScheduler {
    private var pending: (() -> Unit)? = null

    override fun schedule(delayMillis: Long, task: () -> Unit): M0aTimeoutHandle {
        pending = task
        return M0aTimeoutHandle { pending = null }
    }

    override fun shutdown() {
        pending = null
    }

    fun fireNext() {
        val task = checkNotNull(pending)
        pending = null
        task()
    }
}

private class TestMessenger(viewId: Int) : BinaryMessenger {
    private val channelName = "visibility_surface_stream_$viewId"
    private val metricsChannelName = "visibility_surface_metrics_$viewId"
    private val handlers = mutableMapOf<String, BinaryMessenger.BinaryMessageHandler>()
    @Volatile private var failNextReplyDelivery = false

    override fun send(channel: String, message: ByteBuffer?) {
        send(channel, message, null)
    }

    override fun send(
        channel: String,
        message: ByteBuffer?,
        callback: BinaryMessenger.BinaryReply?,
    ) {
        check(channel == channelName || channel == metricsChannelName)
        val currentHandler = handlers[channel]
        if (currentHandler == null) {
            callback?.reply(null)
            return
        }
        currentHandler.onMessage(
            message,
            BinaryMessenger.BinaryReply { reply ->
                if (failNextReplyDelivery) {
                    failNextReplyDelivery = false
                    throw IllegalStateException("reply port closed")
                }
                val engineReply = reply?.let { buffer ->
                    val length = buffer.position()
                    val copy = ByteArray(length)
                    buffer.duplicate().apply {
                        flip()
                        get(copy)
                    }
                    ByteBuffer.wrap(copy)
                }
                callback?.reply(engineReply)
            },
        )
    }

    fun failNextReplyDelivery() {
        failNextReplyDelivery = true
    }

    override fun setMessageHandler(
        channel: String,
        handler: BinaryMessenger.BinaryMessageHandler?,
    ) {
        check(channel == channelName || channel == metricsChannelName)
        if (handler == null) {
            handlers.remove(channel)
        } else {
            handlers[channel] = handler
        }
    }

    fun exchange(request: ByteArray): ByteArray =
        tryExchange(request) ?: error("Binding returned no response")

    fun tryExchange(request: ByteArray): ByteArray? {
        val result = arrayOfNulls<ByteArray>(1)
        val completed = CountDownLatch(1)
        send(
            channelName,
            ByteBuffer.wrap(request),
            BinaryMessenger.BinaryReply { response ->
                result[0] = response?.let { buffer ->
                    val copy = ByteArray(buffer.remaining())
                    buffer.slice().get(copy)
                    copy
                }
                completed.countDown()
            },
        )
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        return result[0]
    }
}
