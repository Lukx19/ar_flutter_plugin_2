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
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityGridV2BindingTest {
    @Test
    fun `exact qualified malformed control returns canonical correlated bytes with no effect`() {
        val authority = M0aCommittedBaselineAuthority()
        val request = startRequest()
        val scope = M0aCommittedBaselineScopeV1.from(request)
        val baseline = com.uhg0.ar_flutter_plugin_2.m0.M0aCommittedBaselineV1(
            transactionId = 9,
            geometryRevision = 10,
            lineageRevision = 11,
            styleRevision = 12,
            captureRevision = 13,
            coverageRevision = 14,
            regionManifestRevision = 15,
            schemaRootRevision = 16,
            nextSurfaceIdHighWater = 17,
        )
        authority.publish(scope, baseline)
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 1200,
            committedBaselineAuthority = authority,
            postToMain = { task -> task() },
        )
        try {
            val before = binding.snapshot()
            val qualifier = before.nativeStreamToken + before.workerBindingToken
            val malformed = M0aControlCodec.encodeRequest(request).also { bytes ->
                bytes[96] = (bytes[96].toInt() xor 0x01).toByte()
            }
            val result = RecordingResult()
            MethodChannel(messenger, "visibility_grid_v2_control_1200").invokeMethod(
                "start",
                qualifier + malformed,
                result,
            )
            assertTrue(result.completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, result.successCount)
            val response = M0aControlCodec.decodeResponse(
                stripQualifier(result.successValue as ByteArray, qualifier),
            )
            assertEquals(M0aControlOperation.START, response.operation)
            assertEquals(1, response.outcome)
            assertEquals(0, response.resultFlags)
            assertEquals(6, response.errorId)
            assertEquals(request.controlRequestId, response.controlRequestId)
            assertEquals(request.sessionId, response.sessionId)
            assertEquals(request.captureGroupId, response.captureGroupId)
            assertEquals(request.sessionGeneration, response.sessionGeneration)
            assertEquals(request.groupGeneration, response.groupGeneration)
            assertEquals(request.coverageEpoch, response.coverageEpoch)
            assertEquals(0, response.streamToken)
            assertEquals(0, response.nextExchangeRequestSequence)
            assertEquals(9, response.nativeTransactionId)
            val detail = M0aControlCodec.decodeErrorDetail(response.payload)
            assertEquals(6, detail.errorId)
            assertEquals(0, detail.disposition)
            assertEquals(2, detail.validationPhase)
            assertEquals(5, detail.recoveryAction)
            assertEquals(4, detail.fieldId)
            assertEquals(10, detail.geometryRevision)
            assertEquals(11, detail.lineageRevision)
            assertEquals(13, detail.captureRevision)
            assertEquals(14, detail.coverageRevision)
            assertEquals(12, detail.acceptedStyleRevision)
            assertEquals(15, detail.regionManifestRevision)
            assertEquals(17, detail.nextSurfaceIdHighWater)
            assertEquals(16, detail.schemaRootRevision)

            val afterMalformed = binding.snapshot()
            assertEquals(before.acceptedControls, afterMalformed.acceptedControls)
            assertEquals(before.operationGeneration, afterMalformed.operationGeneration)
            assertEquals(before.lifecycleSequence, afterMalformed.lifecycleSequence)
            assertEquals(before.initialTransactionQueued, afterMalformed.initialTransactionQueued)
            assertEquals(before.streamToken, afterMalformed.streamToken)
            assertEquals(baseline, authority.snapshot(scope))

            val malformedPayload = RecordingResult()
            val shortStart = request.copy(payload = request.payload.copyOf(request.payload.size - 1))
            MethodChannel(messenger, "visibility_grid_v2_control_1200").invokeMethod(
                "start",
                qualifier + M0aControlCodec.encodeRequest(shortStart),
                malformedPayload,
            )
            assertTrue(malformedPayload.completed.await(2, TimeUnit.SECONDS))
            val payloadError = M0aControlCodec.decodeResponse(
                stripQualifier(malformedPayload.successValue as ByteArray, qualifier),
            )
            val payloadDetail = M0aControlCodec.decodeErrorDetail(payloadError.payload)
            assertEquals(6, payloadError.errorId)
            assertEquals(6, payloadDetail.validationPhase)
            assertEquals(15, payloadDetail.fieldId)
            assertEquals(M0aStartRequestCodecV2.byteLength.toLong(), payloadDetail.expectedValue)
            assertEquals(shortStart.payload.size.toLong(), payloadDetail.observedValue)
            assertEquals(0, binding.snapshot().acceptedControls)
            assertEquals(baseline, authority.snapshot(scope))

            val corrected = RecordingResult()
            MethodChannel(messenger, "visibility_grid_v2_control_1200").invokeMethod(
                "start",
                qualifier + M0aControlCodec.encodeRequest(request),
                corrected,
            )
            assertTrue(corrected.completed.await(2, TimeUnit.SECONDS))
            val correctedResponse = M0aControlCodec.decodeResponse(
                stripQualifier(corrected.successValue as ByteArray, qualifier),
            )
            assertEquals(0, correctedResponse.outcome)
            assertEquals(1, correctedResponse.streamToken)
            assertEquals(1, binding.snapshot().acceptedControls)
            assertEquals(baseline, authority.snapshot(scope))
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun `wrong qualifier and unreadable correlation remain platform failures`() {
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 1199,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            postToMain = { task -> task() },
        )
        try {
            val snapshot = binding.snapshot()
            val qualifier = snapshot.nativeStreamToken + snapshot.workerBindingToken
            val request = M0aControlCodec.encodeRequest(startRequest())

            val wrong = qualifier.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val stale = RecordingResult()
            MethodChannel(messenger, "visibility_grid_v2_control_1199").invokeMethod(
                "start",
                wrong + request,
                stale,
            )
            assertEquals(1, stale.errorCount)
            assertEquals("VG_PROTOCOL_INVALID", stale.errorCode)

            val anonymous = RecordingResult()
            MethodChannel(messenger, "visibility_grid_v2_control_1199").invokeMethod(
                "start",
                qualifier + ByteArray(M0aControlCodec.requestHeaderBytes - 1),
                anonymous,
            )
            assertTrue(anonymous.completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, anonymous.errorCount)
            assertEquals("VG_PROTOCOL_INVALID", anonymous.errorCode)
            assertEquals(0, binding.snapshot().acceptedControls)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun `malformed control publication loses cleanly to binding replacement`() {
        val messenger = MethodTestMessenger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 1198,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            postToMain = { task -> task() },
            beforeControlPublication = {
                entered.countDown()
                release.await()
            },
        )
        try {
            val snapshot = binding.snapshot()
            val qualifier = snapshot.nativeStreamToken + snapshot.workerBindingToken
            val malformed = M0aControlCodec.encodeRequest(startRequest()).also { bytes ->
                bytes[96] = (bytes[96].toInt() xor 1).toByte()
            }
            val control = RecordingResult()
            val channel = MethodChannel(messenger, "visibility_grid_v2_control_1198")
            channel.invokeMethod("start", qualifier + malformed, control)
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            val abandon = RecordingResult()
            channel.invokeMethod("abandonBinding", qualifier, abandon)
            assertEquals(1, abandon.successCount)
            release.countDown()
            assertTrue(control.completed.await(2, TimeUnit.SECONDS))
            assertEquals(0, control.successCount)
            assertEquals(1, control.errorCount)
            assertEquals("VG_STREAM_BINDING_ABANDONED", control.errorCode)
            assertEquals(1, control.successCount + control.errorCount)
            assertEquals(0, binding.snapshot().acceptedControls)
        } finally {
            release.countDown()
            binding.dispose()
        }
    }

    @Test
    fun `cleanup lease and terminal histories remain bounded`() {
        val authority = VisibilityGridV2Binding.CleanupAuthority()
        val leases = mutableListOf<ByteArray>()
        repeat(12) { index ->
            val identity = VisibilityGridV2Binding.BindingIdentity(
                generation = index.toLong() + 1,
                qualifier = ByteArray(32) { (index + it).toByte() },
            )
            val lease = ByteArray(16) { (index * 17 + it).toByte() }
            leases += lease
            authority.publishCurrent(identity)
            assertTrue(authority.claimLease(lease, identity))
            val admitted = checkNotNull(authority.admit(lease))
            checkNotNull(authority.claim(admitted) { mapOf("closedResources" to 3L) })
            authority.release(admitted)
        }
        assertEquals(8, authority.retainedLeaseCount())
        assertEquals(8, authority.retainedTerminalCount())
        assertEquals(null, authority.admit(leases.first()))
        val newest = checkNotNull(authority.admit(leases.last()))
        authority.release(newest)
    }

    @Test
    fun `stalled initial lease claim abandons exact binding and cannot rotate replacement`() {
        val messenger = MethodTestMessenger()
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute {
            entered.countDown()
            try {
                release.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 1201,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            executor = executor,
            postToMain = { task -> task() },
        )
        val channel = MethodChannel(messenger, "visibility_grid_v2_control_1201")
        val lease = ByteArray(16) { (it + 7).toByte() }
        val claim = RecordingResult()
        channel.invokeMethod("claimBindingLease", lease, claim)
        assertEquals(0, claim.successCount)

        val abandon = RecordingResult()
        channel.invokeMethod("abandonBinding", lease, abandon)
        assertEquals(1, abandon.successCount)
        val receipt = abandon.successValue as Map<*, *>
        assertEquals(3L, receipt["closedResources"])
        assertEquals(0L, receipt["handlerBalance"])
        assertEquals(3L, receipt["handlerCountBefore"])
        assertEquals(3L, receipt["handlerCountAfter"])
        assertEquals(1L, receipt["callbackCountBefore"])
        assertEquals(0L, receipt["callbackCountAfter"])
        assertEquals(-1L, receipt["callbackBalance"])
        assertEquals(0L, receipt["executorBalance"])
        assertEquals(1L, receipt["timeoutSchedulerCountBefore"])
        assertEquals(1L, receipt["timeoutSchedulerCountAfter"])
        assertEquals(5L, receipt["ownedResourceCountBefore"])
        assertEquals(5L, receipt["ownedResourceCountAfter"])
        assertEquals(0L, receipt["ownedResourceBalance"])
        assertEquals(1, claim.errorCount)
        assertEquals("VG_STREAM_BINDING_ABANDONED", claim.errorCode)

        val beforeResult = RecordingResult()
        channel.invokeMethod("bindingSnapshot", null, beforeResult)
        assertTrue(beforeResult.completed.await(2, TimeUnit.SECONDS))
        val before = beforeResult.successValue as Map<*, *>
        release.countDown()
        Thread.yield()
        assertEquals(0, claim.successCount)
        val stale = RecordingResult()
        channel.invokeMethod("abandonBinding", lease, stale)
        assertEquals(1, stale.successCount)
        val afterResult = RecordingResult()
        channel.invokeMethod("bindingSnapshot", null, afterResult)
        assertTrue(afterResult.completed.await(2, TimeUnit.SECONDS))
        val after = afterResult.successValue as Map<*, *>
        assertEquals(before["bindingGeneration"], after["bindingGeneration"])
        assertEquals(before["closedResources"], after["closedResources"])
        assertArrayEquals(
            before["nativeStreamToken"] as ByteArray,
            after["nativeStreamToken"] as ByteArray,
        )
        binding.dispose()
    }

    @Test
    fun `queued binding snapshot is claimed once when executor shuts down`() {
        val messenger = MethodTestMessenger()
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute {
            entered.countDown()
            try {
                while (!release.await(10, TimeUnit.MILLISECONDS)) {
                    // Keep the queued snapshot behind an explicit executor cut.
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 1202,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            executor = executor,
            postToMain = { task -> task() },
        )
        try {
            val result = RecordingResult()
            MethodChannel(messenger, "visibility_grid_v2_control_1202")
                .invokeMethod("bindingSnapshot", null, result)
            assertEquals(0, result.successCount)
            assertEquals(0, result.errorCount)

            binding.dispose()

            assertTrue(result.completed.await(2, TimeUnit.SECONDS))
            assertEquals(0, result.successCount)
            assertEquals(1, result.errorCount)
            assertEquals("VG_STREAM_BINDING_ABANDONED", result.errorCode)

            release.countDown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(1, result.successCount + result.errorCount)
        } finally {
            release.countDown()
            binding.dispose()
        }
    }

    @Test
    fun `lifecycle dispose drains queued cleanup and releases its admission once`() {
        val messenger = MethodTestMessenger()
        val executor = Executors.newSingleThreadExecutor()
        val authority = VisibilityGridV2Binding.CleanupAuthority()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute {
            entered.countDown()
            try {
                while (!release.await(10, TimeUnit.MILLISECONDS)) {
                    // Keep the admitted cleanup queued behind an explicit cut.
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 1203,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            executor = executor,
            postToMain = { task -> task() },
            cleanupAuthority = authority,
        )
        try {
            val current = binding.snapshot()
            val cleanup = RecordingResult()
            MethodChannel(messenger, "visibility_grid_v2_control_1203").invokeMethod(
                "disposeBinding",
                current.nativeStreamToken + current.workerBindingToken,
                cleanup,
            )
            assertEquals(1, authority.activeAdmissionCount())

            binding.dispose()

            assertTrue(cleanup.completed.await(2, TimeUnit.SECONDS))
            assertEquals(0, cleanup.successCount)
            assertEquals(1, cleanup.errorCount)
            assertEquals("VG_STREAM_BINDING_ABANDONED", cleanup.errorCode)
            assertEquals(0, authority.activeAdmissionCount())

            release.countDown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(1, cleanup.successCount + cleanup.errorCount)
            assertEquals(0, authority.activeAdmissionCount())
        } finally {
            release.countDown()
            binding.dispose()
        }
    }

    @Test
    fun `lifecycle dispose replays retained cleanup terminal before executor shutdown`() {
        val messenger = MethodTestMessenger()
        val authority = VisibilityGridV2Binding.CleanupAuthority()
        val posted = CountDownLatch(1)
        val queuedPosts = ArrayDeque<() -> Unit>()
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 1204,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            postToMain = { task ->
                synchronized(queuedPosts) { queuedPosts.addLast(task) }
                posted.countDown()
            },
            cleanupAuthority = authority,
        )
        try {
            val original = binding.snapshot()
            val cleanup = RecordingResult()
            MethodChannel(messenger, "visibility_grid_v2_control_1204").invokeMethod(
                "disposeBinding",
                original.nativeStreamToken + original.workerBindingToken,
                cleanup,
            )
            assertTrue(posted.await(2, TimeUnit.SECONDS))
            assertEquals(0, cleanup.successCount)
            assertEquals(0, authority.activeAdmissionCount())
            assertEquals(1, authority.retainedTerminalCount())

            binding.dispose()

            assertTrue(cleanup.completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, cleanup.successCount)
            assertEquals(0, cleanup.errorCount)
            val receipt = cleanup.successValue as Map<*, *>
            assertEquals(original.bindingGeneration, receipt["bindingGeneration"])
            assertEquals(0L, receipt["callbackCountBefore"])
            assertEquals(0L, receipt["callbackCountAfter"])
            assertEquals(0L, receipt["callbackBalance"])
            assertEquals(0, authority.activeAdmissionCount())

            synchronized(queuedPosts) {
                while (queuedPosts.isNotEmpty()) queuedPosts.removeFirst().invoke()
            }
            assertEquals(1, cleanup.successCount + cleanup.errorCount)
            assertEquals(0, authority.activeAdmissionCount())
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun `restored START stall spans two exact abandon fences then disarms`() {
        val seam = VisibilityGridV2DebugRecoverySeam()
        assertEquals(true, seam.armRestoredStart()["armed"])
        seam.acceptedCut(startRequest())
        seam.commitPublished()

        val acknowledgement = Executors.newSingleThreadExecutor()
        val acknowledgementContinuation = acknowledgement.submit {
            seam.beforeRequest(
                M0aPacketCodec.Request(
                    requestFlags = 0,
                    streamToken = 1,
                    acknowledgedTransactionId = 1,
                    acknowledgedGeometryRevision = 1,
                    acknowledgedLineageRevision = 1,
                    nextStyleRevision = 0,
                    maximumResponseBytes = 4096,
                    styleRecords = emptyList(),
                    commandBytes = byteArrayOf(),
                    requestSequence = 3,
                ),
            )
        }
        seam.releaseAbandonedExchange()
        acknowledgementContinuation.get(2, TimeUnit.SECONDS)
        acknowledgement.shutdownNow()

        val restoredRequest = startRequest().copy(
            payload = M0aStartRequestCodecV2.defaultPayload().also { it[7] = 1 },
        )
        val cut = VisibilityGridV2Binding.RecoveryGroupCut.from(restoredRequest)
        seam.replacementSeeded(cut)
        seam.acceptedCut(restoredRequest)
        val restoredStart = Executors.newSingleThreadExecutor()
        val restoredContinuation = restoredStart.submit {
            seam.afterRestoredStartQualification(restoredRequest)
        }
        seam.releaseAbandonedExchange()
        restoredContinuation.get(2, TimeUnit.SECONDS)
        restoredStart.shutdownNow()

        seam.replacementSeeded(cut)
        seam.acceptedCut(restoredRequest)
        val trace = seam.snapshot()["trace"] as List<*>
        assertTrue(
            trace.containsAll(
                listOf(
                    "armed:restored-start",
                    "commit-published",
                    "stalled:acknowledgement",
                    "stalled:restored-start",
                ),
            ),
        )
        assertEquals(2, trace.count { it == "abandon-won" })
        assertEquals(2, trace.count { it == "late-old-completion-fenced" })
        assertEquals(2, trace.count { it == "replacement-seeded:${cut.cutIdentityForTest()}" })
        val completedTrace = trace.toList()
        seam.acceptedCut(restoredRequest)
        assertEquals(completedTrace, seam.snapshot()["trace"])
    }

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
        val cleanupReceipt = abandoned.successValue as Map<*, *>
        assertEquals(3L, cleanupReceipt["handlerCountBefore"])
        assertEquals(3L, cleanupReceipt["handlerCountAfter"])
        assertEquals(1L, cleanupReceipt["callbackCountBefore"])
        assertEquals(0L, cleanupReceipt["callbackCountAfter"])
        assertEquals(-1L, cleanupReceipt["callbackBalance"])
        assertEquals(1L, cleanupReceipt["timeoutSchedulerCountBefore"])
        assertEquals(1L, cleanupReceipt["timeoutSchedulerCountAfter"])
        assertEquals(5L, cleanupReceipt["ownedResourceCountBefore"])
        assertEquals(5L, cleanupReceipt["ownedResourceCountAfter"])
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
    fun `queued stale dispose cannot rotate replacement`() {
        val messenger = MethodTestMessenger()
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacementInstalled = CountDownLatch(1)
        val releaseStaleDispose = CountDownLatch(1)
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 92,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            executor = executor,
            postToMain = { task -> task() },
        )
        try {
            executor.execute {
                entered.countDown()
                release.await()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            val original = binding.snapshot()
            val qualifier = original.nativeStreamToken + original.workerBindingToken
            val channel = MethodChannel(messenger, "visibility_grid_v2_control_92")
            val first = RecordingResult()
            val second = RecordingResult()
            channel.invokeMethod("disposeBinding", qualifier, first)
            executor.execute {
                replacementInstalled.countDown()
                releaseStaleDispose.await()
            }
            channel.invokeMethod("disposeBinding", qualifier, second)

            release.countDown()
            assertTrue(first.completed.await(5, TimeUnit.SECONDS))
            assertTrue(replacementInstalled.await(5, TimeUnit.SECONDS))
            assertEquals(1, first.successCount)
            assertEquals(0, first.errorCount)

            val teardown = first.successValue as Map<*, *>
            assertEquals(original.bindingGeneration, teardown["bindingGeneration"])
            assertEquals(1L, teardown["closedResources"])
            assertEquals(false, teardown["disposed"])

            val replacement = binding.snapshot()
            assertNotEquals(original.bindingGeneration, replacement.bindingGeneration)
            assertFalse(original.nativeStreamToken.contentEquals(replacement.nativeStreamToken))
            assertFalse(original.workerBindingToken.contentEquals(replacement.workerBindingToken))
            assertEquals(original.viewGeneration, replacement.viewGeneration)
            assertArrayEquals(original.arSessionIdentity, replacement.arSessionIdentity)
            assertArrayEquals(original.viewInstanceId, replacement.viewInstanceId)
            assertEquals(0L, replacement.acceptedControls)
            assertEquals(false, replacement.disposed)
            assertEquals(1L, replacement.closedResources)

            releaseStaleDispose.countDown()
            assertTrue(second.completed.await(5, TimeUnit.SECONDS))
            assertEquals(1, second.successCount)
            assertEquals(0, second.errorCount)
            assertReceiptEqual(first.successValue, second.successValue)

            val afterStale = binding.snapshot()
            assertEquals(replacement.bindingGeneration, afterStale.bindingGeneration)
            assertArrayEquals(replacement.nativeStreamToken, afterStale.nativeStreamToken)
            assertArrayEquals(replacement.workerBindingToken, afterStale.workerBindingToken)
            assertEquals(replacement.closedResources, afterStale.closedResources)
            assertEquals(0, afterStale.executorTrace.count {
                it.endsWith(":control:dispose_binding")
            })
        } finally {
            release.countDown()
            releaseStaleDispose.countDown()
            binding.dispose()
        }
    }

    @Test
    fun `dispose rotation before reply lets abandon replay the exact old receipt`() {
        val messenger = MethodTestMessenger()
        val posted = CountDownLatch(1)
        val queuedPosts = ArrayDeque<() -> Unit>()
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 93,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            postToMain = { task ->
                synchronized(queuedPosts) { queuedPosts.addLast(task) }
                posted.countDown()
            },
        )
        try {
            val original = binding.snapshot()
            val qualifier = original.nativeStreamToken + original.workerBindingToken
            val channel = MethodChannel(messenger, "visibility_grid_v2_control_93")
            val disposed = RecordingResult()
            channel.invokeMethod("disposeBinding", qualifier, disposed)
            assertTrue(posted.await(2, TimeUnit.SECONDS))

            val replacementBefore = binding.snapshot()
            val abandoned = RecordingResult()
            channel.invokeMethod("abandonBinding", qualifier, abandoned)
            assertEquals(1, abandoned.successCount)
            assertEquals(0, abandoned.errorCount)
            assertEquals(0, disposed.successCount)

            synchronized(queuedPosts) {
                while (queuedPosts.isNotEmpty()) queuedPosts.removeFirst().invoke()
            }
            assertEquals(1, disposed.successCount)
            assertEquals(0, disposed.errorCount)
            assertReceiptEqual(disposed.successValue, abandoned.successValue)
            val receipt = disposed.successValue as Map<*, *>
            assertEquals(original.bindingGeneration, receipt["bindingGeneration"])
            assertEquals(1L, receipt["closedResources"])
            assertEquals(false, receipt["disposed"])
            assertEquals(0L, receipt["callbackCountBefore"])
            assertEquals(0L, receipt["callbackCountAfter"])
            assertEquals(0L, receipt["callbackBalance"])

            val replacementAfter = binding.snapshot()
            assertEquals(replacementBefore.bindingGeneration, replacementAfter.bindingGeneration)
            assertArrayEquals(replacementBefore.nativeStreamToken, replacementAfter.nativeStreamToken)
            assertArrayEquals(replacementBefore.workerBindingToken, replacementAfter.workerBindingToken)
            assertEquals(replacementBefore.closedResources, replacementAfter.closedResources)
            assertEquals(replacementBefore.operationGeneration, replacementAfter.operationGeneration)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun `abandon precheck before dispose rotation replays the old receipt`() {
        val messenger = MethodTestMessenger()
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val abandonAdmitted = CountDownLatch(1)
        val releaseAbandon = CountDownLatch(1)
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 94,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            executor = executor,
            postToMain = { task -> task() },
            beforeAbandonCleanup = {
                abandonAdmitted.countDown()
                releaseAbandon.await(2, TimeUnit.SECONDS)
            },
        )
        val abandonCaller = Executors.newSingleThreadExecutor()
        try {
            executor.execute {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            val original = binding.snapshot()
            val qualifier = original.nativeStreamToken + original.workerBindingToken
            val channel = MethodChannel(messenger, "visibility_grid_v2_control_94")
            val disposed = RecordingResult()
            channel.invokeMethod("disposeBinding", qualifier, disposed)

            val abandoned = RecordingResult()
            abandonCaller.execute {
                channel.invokeMethod("abandonBinding", qualifier, abandoned)
            }
            assertTrue(abandonAdmitted.await(2, TimeUnit.SECONDS))

            release.countDown()
            assertTrue(disposed.completed.await(5, TimeUnit.SECONDS))
            assertEquals(1, disposed.successCount)
            assertEquals(0, disposed.errorCount)
            val replacement = binding.snapshot()

            releaseAbandon.countDown()
            assertTrue(abandoned.completed.await(5, TimeUnit.SECONDS))
            assertEquals(1, abandoned.successCount)
            assertEquals(0, abandoned.errorCount)
            assertReceiptEqual(disposed.successValue, abandoned.successValue)

            val after = binding.snapshot()
            assertEquals(replacement.bindingGeneration, after.bindingGeneration)
            assertArrayEquals(replacement.nativeStreamToken, after.nativeStreamToken)
            assertArrayEquals(replacement.workerBindingToken, after.workerBindingToken)
            assertEquals(replacement.closedResources, after.closedResources)
            assertEquals(replacement.operationGeneration, after.operationGeneration)
        } finally {
            release.countDown()
            releaseAbandon.countDown()
            abandonCaller.shutdownNow()
            binding.dispose()
        }
    }

    @Test
    fun `abandon winner publishes replacement before replaying pending dispose receipt`() {
        val messenger = MethodTestMessenger()
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 95,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            executor = executor,
            postToMain = { task -> task() },
        )
        try {
            executor.execute {
                entered.countDown()
                release.await()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            val original = binding.snapshot()
            val qualifier = original.nativeStreamToken + original.workerBindingToken
            val channelName = "visibility_grid_v2_control_95"
            val channel = MethodChannel(messenger, channelName)
            var replacementPublishedAtDisposeReply = false
            val disposed = RecordingResult {
                replacementPublishedAtDisposeReply = messenger.hasHandler(channelName)
            }
            channel.invokeMethod("disposeBinding", qualifier, disposed)

            val abandoned = RecordingResult()
            channel.invokeMethod("abandonBinding", qualifier, abandoned)
            assertEquals(1, abandoned.successCount)
            assertEquals(1, disposed.successCount)
            assertTrue(replacementPublishedAtDisposeReply)
            assertReceiptEqual(abandoned.successValue, disposed.successValue)

            val replacementResult = RecordingResult()
            channel.invokeMethod("bindingSnapshot", null, replacementResult)
            assertTrue(replacementResult.completed.await(2, TimeUnit.SECONDS))
            val replacement = replacementResult.successValue as Map<*, *>

            val repeated = RecordingResult()
            channel.invokeMethod("abandonBinding", qualifier, repeated)
            assertEquals(1, repeated.successCount)
            assertReceiptEqual(abandoned.successValue, repeated.successValue)

            val unrelated = RecordingResult()
            channel.invokeMethod("abandonBinding", ByteArray(32) { 0x5a }, unrelated)
            assertEquals(1, unrelated.errorCount)
            assertEquals("VG_STREAM_BINDING_ABANDONED", unrelated.errorCode)

            val after = RecordingResult()
            channel.invokeMethod("bindingSnapshot", null, after)
            assertTrue(after.completed.await(2, TimeUnit.SECONDS))
            val afterSnapshot = after.successValue as Map<*, *>
            assertEquals(replacement["bindingGeneration"], afterSnapshot["bindingGeneration"])
            assertEquals(replacement["streamToken"], afterSnapshot["streamToken"])
            assertEquals(replacement["closedResources"], afterSnapshot["closedResources"])
            assertEquals(replacement["disposed"], afterSnapshot["disposed"])
            assertEquals(replacement["lifecycleSequence"], afterSnapshot["lifecycleSequence"])
            assertEquals(replacement["operationGeneration"], afterSnapshot["operationGeneration"])
            assertArrayEquals(
                replacement["nativeStreamToken"] as ByteArray,
                afterSnapshot["nativeStreamToken"] as ByteArray,
            )
            assertArrayEquals(
                replacement["workerBindingToken"] as ByteArray,
                afterSnapshot["workerBindingToken"] as ByteArray,
            )
        } finally {
            release.countDown()
            binding.dispose()
        }
    }

    @Test
    fun `Q1 abandon drains queued snapshots and cleanup callbacks with exact self exclusion`() {
        val messenger = MethodTestMessenger()
        val executor = Executors.newSingleThreadExecutor()
        val initialEntered = CountDownLatch(1)
        val releaseInitial = CountDownLatch(1)
        val posted = CountDownLatch(3)
        val queuedPosts = ArrayDeque<() -> Unit>()
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 951,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            executor = executor,
            postToMain = { task ->
                synchronized(queuedPosts) { queuedPosts.addLast(task) }
                posted.countDown()
            },
        )
        val channel = MethodChannel(messenger, "visibility_grid_v2_control_951")
        try {
            executor.execute {
                initialEntered.countDown()
                releaseInitial.await()
            }
            assertTrue(initialEntered.await(2, TimeUnit.SECONDS))

            val q0 = binding.snapshot()
            val q0Qualifier = q0.nativeStreamToken + q0.workerBindingToken
            val snapshot = RecordingResult()
            val firstDispose = RecordingResult()
            val secondDispose = RecordingResult()
            channel.invokeMethod("bindingSnapshot", null, snapshot)
            channel.invokeMethod("disposeBinding", q0Qualifier, firstDispose)
            channel.invokeMethod("disposeBinding", q0Qualifier, secondDispose)

            releaseInitial.countDown()
            assertTrue(posted.await(2, TimeUnit.SECONDS))
            val q1 = binding.snapshot()
            assertNotEquals(q0.bindingGeneration, q1.bindingGeneration)

            val interposedEntered = CountDownLatch(1)
            val releaseInterposed = CountDownLatch(1)
            executor.execute {
                interposedEntered.countDown()
                releaseInterposed.await()
            }
            assertTrue(interposedEntered.await(2, TimeUnit.SECONDS))

            val q1Dispose = RecordingResult()
            channel.invokeMethod("disposeBinding", q1.nativeStreamToken + q1.workerBindingToken, q1Dispose)
            val q1Abandon = RecordingResult()
            channel.invokeMethod("abandonBinding", q1.nativeStreamToken + q1.workerBindingToken, q1Abandon)

            assertEquals(1, q1Abandon.successCount)
            val q1Receipt = q1Abandon.successValue as Map<*, *>
            assertEquals(4L, q1Receipt["callbackCountBefore"])
            assertEquals(0L, q1Receipt["callbackCountAfter"])
            assertEquals(-4L, q1Receipt["callbackBalance"])

            assertTrue(snapshot.completed.await(2, TimeUnit.SECONDS))
            assertEquals(0, snapshot.successCount)
            assertEquals(1, snapshot.errorCount)
            assertEquals("VG_STREAM_BINDING_ABANDONED", snapshot.errorCode)
            assertEquals(1, firstDispose.successCount)
            assertEquals(1, secondDispose.successCount)
            assertEquals(1, q1Dispose.successCount)
            assertReceiptEqual(firstDispose.successValue, secondDispose.successValue)
            assertReceiptEqual(q1Abandon.successValue, q1Dispose.successValue)

            releaseInterposed.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            synchronized(queuedPosts) {
                while (queuedPosts.isNotEmpty()) queuedPosts.removeFirst().invoke()
            }
            assertEquals(1, snapshot.successCount + snapshot.errorCount)
            assertEquals(1, firstDispose.successCount + firstDispose.errorCount)
            assertEquals(1, secondDispose.successCount + secondDispose.errorCount)
            assertEquals(1, q1Dispose.successCount + q1Dispose.errorCount)
        } finally {
            releaseInitial.countDown()
            binding.dispose()
        }
    }

    @Test
    fun `Q1 abandon drains delayed Q0 with own receipt while completing Q1 cleanup`() {
        val messenger = MethodTestMessenger()
        val executor = Executors.newSingleThreadExecutor()
        val posted = CountDownLatch(1)
        val queuedPosts = ArrayDeque<() -> Unit>()
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 96,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            executor = executor,
            postToMain = { task ->
                synchronized(queuedPosts) { queuedPosts.addLast(task) }
                posted.countDown()
            },
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val channel = MethodChannel(messenger, "visibility_grid_v2_control_96")
            val q0 = binding.snapshot()
            val q0Qualifier = q0.nativeStreamToken + q0.workerBindingToken
            val q0Dispose = RecordingResult()
            channel.invokeMethod("disposeBinding", q0Qualifier, q0Dispose)
            assertTrue(posted.await(2, TimeUnit.SECONDS))

            val q1 = binding.snapshot()
            val q1Qualifier = q1.nativeStreamToken + q1.workerBindingToken
            executor.execute {
                entered.countDown()
                release.await()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val q1Dispose = RecordingResult()
            channel.invokeMethod("disposeBinding", q1Qualifier, q1Dispose)

            val q1Abandon = RecordingResult()
            channel.invokeMethod("abandonBinding", q1Qualifier, q1Abandon)
            assertEquals(1, q1Abandon.successCount)
            assertEquals(1, q1Dispose.successCount)
            assertEquals(1, q0Dispose.successCount)
            assertReceiptEqual(q1Abandon.successValue, q1Dispose.successValue)
            assertEquals(q1.bindingGeneration, (q1Abandon.successValue as Map<*, *>)["bindingGeneration"])
            assertEquals(q0.bindingGeneration, (q0Dispose.successValue as Map<*, *>)["bindingGeneration"])

            release.countDown()
            synchronized(queuedPosts) {
                while (queuedPosts.isNotEmpty()) queuedPosts.removeFirst().invoke()
            }
            assertEquals(1, q0Dispose.successCount)
            assertNotEquals(
                (q0Dispose.successValue as Map<*, *>)["bindingGeneration"],
                (q1Dispose.successValue as Map<*, *>)["bindingGeneration"],
            )
        } finally {
            release.countDown()
            binding.dispose()
        }
    }

    @Test
    fun `cleanup terminal history is bounded and evicted qualifier is canonically stale`() {
        val messenger = MethodTestMessenger()
        val authority = VisibilityGridV2Binding.CleanupAuthority()
        val binding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = 97,
            committedBaselineAuthority = M0aCommittedBaselineAuthority(),
            postToMain = { task -> task() },
            cleanupAuthority = authority,
        )
        try {
            val channel = MethodChannel(messenger, "visibility_grid_v2_control_97")
            val disposedQualifiers = mutableListOf<ByteArray>()
            repeat(12) {
                val current = binding.snapshot()
                val qualifier = current.nativeStreamToken + current.workerBindingToken
                disposedQualifiers += qualifier
                val disposed = RecordingResult()
                channel.invokeMethod("disposeBinding", qualifier, disposed)
                assertTrue(disposed.completed.await(2, TimeUnit.SECONDS))
                assertEquals(1, disposed.successCount)
            }

            assertEquals(8, authority.retainedTerminalCount())
            val beforeStale = binding.snapshot()
            val evicted = RecordingResult()
            channel.invokeMethod("abandonBinding", disposedQualifiers.first(), evicted)
            assertEquals(1, evicted.errorCount)
            assertEquals("VG_STREAM_BINDING_ABANDONED", evicted.errorCode)

            val retained = RecordingResult()
            channel.invokeMethod("abandonBinding", disposedQualifiers.last(), retained)
            assertEquals(1, retained.successCount)
            assertEquals(
                beforeStale.bindingGeneration - 1,
                (retained.successValue as Map<*, *>)["bindingGeneration"],
            )
            val after = binding.snapshot()
            assertEquals(beforeStale.bindingGeneration, after.bindingGeneration)
            assertArrayEquals(beforeStale.nativeStreamToken, after.nativeStreamToken)
            assertArrayEquals(beforeStale.workerBindingToken, after.workerBindingToken)
            assertEquals(beforeStale.closedResources, after.closedResources)
            assertEquals(beforeStale.operationGeneration, after.operationGeneration)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun `repeated abandon winners release discarded dispose leases and keep history bounded`() {
        val messenger = MethodTestMessenger()
        val authority = VisibilityGridV2Binding.CleanupAuthority()
        val qualifiers = mutableListOf<ByteArray>()
        val receipts = mutableListOf<Any?>()
        var finalBinding: VisibilityGridV2Binding? = null
        var finalChannel: MethodChannel? = null

        try {
            repeat(12) { generation ->
                val executor = Executors.newSingleThreadExecutor()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                executor.execute {
                    entered.countDown()
                    release.await()
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))

                val viewId = 980 + generation
                val binding = VisibilityGridV2Binding(
                    messenger = messenger,
                    viewId = viewId,
                    committedBaselineAuthority = M0aCommittedBaselineAuthority(),
                    executor = executor,
                    postToMain = { task -> task() },
                    cleanupAuthority = authority,
                )
                val channel = MethodChannel(messenger, "visibility_grid_v2_control_$viewId")
                val original = binding.snapshot()
                val qualifier = original.nativeStreamToken + original.workerBindingToken
                qualifiers += qualifier

                val queuedDispose = RecordingResult()
                channel.invokeMethod("disposeBinding", qualifier, queuedDispose)
                val abandon = RecordingResult()
                channel.invokeMethod("abandonBinding", qualifier, abandon)
                assertEquals(1, abandon.successCount)
                assertEquals(1, queuedDispose.successCount)
                assertReceiptEqual(abandon.successValue, queuedDispose.successValue)
                receipts += abandon.successValue
                release.countDown()

                if (generation == 11) {
                    finalBinding = binding
                    finalChannel = channel
                } else {
                    binding.dispose()
                }
            }

            assertEquals(8, authority.retainedTerminalCount())
            val channel = checkNotNull(finalChannel)
            val beforeResult = RecordingResult()
            channel.invokeMethod("bindingSnapshot", null, beforeResult)
            assertTrue(beforeResult.completed.await(2, TimeUnit.SECONDS))
            val before = beforeResult.successValue as Map<*, *>

            val ancient = RecordingResult()
            channel.invokeMethod("abandonBinding", qualifiers.first(), ancient)
            assertEquals(1, ancient.errorCount)
            assertEquals("VG_STREAM_BINDING_ABANDONED", ancient.errorCode)

            val currentTerminal = RecordingResult()
            channel.invokeMethod("abandonBinding", qualifiers.last(), currentTerminal)
            assertEquals(1, currentTerminal.successCount)
            assertReceiptEqual(receipts.last(), currentTerminal.successValue)

            val afterResult = RecordingResult()
            channel.invokeMethod("bindingSnapshot", null, afterResult)
            assertTrue(afterResult.completed.await(2, TimeUnit.SECONDS))
            val after = afterResult.successValue as Map<*, *>
            assertEquals(before["bindingGeneration"], after["bindingGeneration"])
            assertEquals(before["lifecycleSequence"], after["lifecycleSequence"])
            assertEquals(before["operationGeneration"], after["operationGeneration"])
            assertEquals(before["closedResources"], after["closedResources"])
            assertArrayEquals(
                before["nativeStreamToken"] as ByteArray,
                after["nativeStreamToken"] as ByteArray,
            )
            assertArrayEquals(
                before["workerBindingToken"] as ByteArray,
                after["workerBindingToken"] as ByteArray,
            )
            assertEquals(8, authority.retainedTerminalCount())
        } finally {
            finalBinding?.dispose()
        }
    }

    @Test
    fun `Q1 abandon drains both Q0 disposes with Q0 receipt across bounded history`() {
        val messenger = MethodTestMessenger()
        val authority = VisibilityGridV2Binding.CleanupAuthority()
        val ancientQualifiers = mutableListOf<ByteArray>()
        var newestQ1Qualifier: ByteArray? = null
        var newestQ1Receipt: Any? = null
        var finalBinding: VisibilityGridV2Binding? = null
        var finalChannel: MethodChannel? = null
        var finalDelayPosts: AtomicBoolean? = null

        try {
            repeat(12) { iteration ->
                val executor = Executors.newSingleThreadExecutor()
                val initialEntered = CountDownLatch(1)
                val releaseInitial = CountDownLatch(1)
                val interposedEntered = CountDownLatch(1)
                val releaseInterposed = CountDownLatch(1)
                val delayedPosts = ArrayDeque<() -> Unit>()
                val delayPosts = AtomicBoolean(true)
                executor.execute {
                    initialEntered.countDown()
                    releaseInitial.await()
                }
                assertTrue(initialEntered.await(2, TimeUnit.SECONDS))

                val viewId = 1100 + iteration
                val binding = VisibilityGridV2Binding(
                    messenger = messenger,
                    viewId = viewId,
                    committedBaselineAuthority = M0aCommittedBaselineAuthority(),
                    executor = executor,
                    postToMain = { task ->
                        if (delayPosts.get()) delayedPosts.addLast(task) else task()
                    },
                    cleanupAuthority = authority,
                )
                val channel = MethodChannel(messenger, "visibility_grid_v2_control_$viewId")
                val q0 = binding.snapshot()
                val q0Qualifier = q0.nativeStreamToken + q0.workerBindingToken
                ancientQualifiers += q0Qualifier

                val firstQ0 = RecordingResult()
                val secondQ0 = RecordingResult()
                channel.invokeMethod("disposeBinding", q0Qualifier, firstQ0)
                executor.execute {
                    interposedEntered.countDown()
                    releaseInterposed.await()
                }
                channel.invokeMethod("disposeBinding", q0Qualifier, secondQ0)

                releaseInitial.countDown()
                assertTrue(interposedEntered.await(2, TimeUnit.SECONDS))
                val q1 = binding.snapshot()
                val q1Qualifier = q1.nativeStreamToken + q1.workerBindingToken
                val q1Abandon = RecordingResult()
                channel.invokeMethod("abandonBinding", q1Qualifier, q1Abandon)
                assertEquals(1, q1Abandon.successCount)
                assertEquals(1, firstQ0.successCount)
                assertEquals(1, secondQ0.successCount)
                assertReceiptEqual(firstQ0.successValue, secondQ0.successValue)
                assertEquals(
                    q0.bindingGeneration,
                    (firstQ0.successValue as Map<*, *>)["bindingGeneration"],
                )
                assertEquals(
                    q1.bindingGeneration,
                    (q1Abandon.successValue as Map<*, *>)["bindingGeneration"],
                )
                assertNotEquals(
                    (firstQ0.successValue as Map<*, *>)["bindingGeneration"],
                    (q1Abandon.successValue as Map<*, *>)["bindingGeneration"],
                )
                releaseInterposed.countDown()
                delayPosts.set(false)

                newestQ1Qualifier = q1Qualifier
                newestQ1Receipt = q1Abandon.successValue
                if (iteration == 11) {
                    finalBinding = binding
                    finalChannel = channel
                    finalDelayPosts = delayPosts
                } else {
                    binding.dispose()
                }
            }

            assertEquals(8, authority.retainedTerminalCount())
            val channel = checkNotNull(finalChannel)
            checkNotNull(finalDelayPosts).set(false)
            val beforeResult = RecordingResult()
            channel.invokeMethod("bindingSnapshot", null, beforeResult)
            assertTrue(beforeResult.completed.await(2, TimeUnit.SECONDS))
            val before = beforeResult.successValue as Map<*, *>

            val ancient = RecordingResult()
            channel.invokeMethod("abandonBinding", ancientQualifiers.first(), ancient)
            assertEquals(1, ancient.errorCount)
            assertEquals("VG_STREAM_BINDING_ABANDONED", ancient.errorCode)

            val replay = RecordingResult()
            channel.invokeMethod("abandonBinding", checkNotNull(newestQ1Qualifier), replay)
            assertEquals(1, replay.successCount)
            assertReceiptEqual(newestQ1Receipt, replay.successValue)

            val afterResult = RecordingResult()
            channel.invokeMethod("bindingSnapshot", null, afterResult)
            assertTrue(afterResult.completed.await(2, TimeUnit.SECONDS))
            val after = afterResult.successValue as Map<*, *>
            assertEquals(before["bindingGeneration"], after["bindingGeneration"])
            assertEquals(before["lifecycleSequence"], after["lifecycleSequence"])
            assertEquals(before["operationGeneration"], after["operationGeneration"])
            assertEquals(before["closedResources"], after["closedResources"])
            assertArrayEquals(
                before["nativeStreamToken"] as ByteArray,
                after["nativeStreamToken"] as ByteArray,
            )
            assertArrayEquals(
                before["workerBindingToken"] as ByteArray,
                after["workerBindingToken"] as ByteArray,
            )
            assertEquals(8, authority.retainedTerminalCount())
        } finally {
            finalBinding?.dispose()
        }
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

    private fun VisibilityGridV2Binding.RecoveryGroupCut.cutIdentityForTest(): String =
        "${sessionId?.hex()}:${captureGroupId?.hex()}:" +
            "$sessionGeneration:$groupGeneration:$coverageEpoch"

    private fun stripQualifier(bytes: ByteArray, qualifier: ByteArray): ByteArray {
        assertTrue(bytes.size >= qualifier.size)
        assertArrayEquals(qualifier, bytes.copyOfRange(0, qualifier.size))
        return bytes.copyOfRange(qualifier.size, bytes.size)
    }

    private fun assertReceiptEqual(left: Any?, right: Any?) {
        val expected = left as Map<*, *>
        val actual = right as Map<*, *>
        assertEquals(expected["bindingGeneration"], actual["bindingGeneration"])
        assertEquals(expected["streamToken"], actual["streamToken"])
        assertEquals(expected["closedResources"], actual["closedResources"])
        assertEquals(expected["disposed"], actual["disposed"])
        assertEquals(expected["operationGeneration"], actual["operationGeneration"])
        assertArrayEquals(
            expected["nativeStreamToken"] as ByteArray,
            actual["nativeStreamToken"] as ByteArray,
        )
        assertArrayEquals(
            expected["workerBindingToken"] as ByteArray,
            actual["workerBindingToken"] as ByteArray,
        )
        assertEquals(expected["executorTrace"], actual["executorTrace"])
    }
}

private class RecordingResult(
    private val onSuccess: (() -> Unit)? = null,
) : MethodChannel.Result {
    val completed = CountDownLatch(1)
    var successCount = 0
    var errorCount = 0
    var successValue: Any? = null
    var errorCode: String? = null

    override fun success(result: Any?) {
        successCount++
        successValue = result
        onSuccess?.invoke()
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

    fun hasHandler(channel: String): Boolean = synchronized(handlers) {
        handlers.containsKey(channel)
    }
}
