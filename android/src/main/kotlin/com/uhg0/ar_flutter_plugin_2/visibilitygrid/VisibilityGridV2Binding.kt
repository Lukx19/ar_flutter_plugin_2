package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.os.Handler
import android.os.Looper
import com.uhg0.ar_flutter_plugin_2.m0.M0aCommittedBaselineAuthority
import com.uhg0.ar_flutter_plugin_2.m0.M0aCommittedBaselineScopeV1
import com.uhg0.ar_flutter_plugin_2.m0.M0aCommitReceiptQueryV1
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlCodec
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlLifecycle
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlOperation
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlRequest
import com.uhg0.ar_flutter_plugin_2.m0.M0aPacketCodec
import com.uhg0.ar_flutter_plugin_2.m0.M0aCommittedBaselineV1
import com.uhg0.ar_flutter_plugin_2.m0.toMap
import com.uhg0.ar_flutter_plugin_2.m0.M0aUuid
import com.uhg0.ar_flutter_plugin_2.m0.M0aStructuralTransactionProducerV1
import com.uhg0.ar_flutter_plugin_2.m0.M0aStartRequestCodecV2
import com.uhg0.ar_flutter_plugin_2.m0.M0aVisibilitySurfaceStreamChannel
import com.uhg0.ar_flutter_plugin_2.m0.M0aDebugTransportProbe
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.nio.ByteBuffer
import java.math.BigDecimal
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Production owner for one immutable V2 platform-view binding generation.
 *
 * Control and high-rate exchange enter the same serial executor. A successful
 * fresh START queues one empty, revisioned structural transaction so the
 * worker must validate BEGIN/COMMIT and publish an exact ACK before the
 * binding is considered established.
 */
class VisibilityGridV2Binding internal constructor(
    private val messenger: BinaryMessenger,
    private val viewId: Int,
    private val committedBaselineAuthority: M0aCommittedBaselineAuthority,
    private val bindingGenerationSeed: Long = nextBindingGeneration.incrementAndGet(),
    private val viewGeneration: Long = nextViewGeneration.incrementAndGet(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val arSessionIdentity: ByteArray = newOpaqueToken(),
    private val viewInstanceId: ByteArray = newOpaqueToken(),
    private val postToMain: ((() -> Unit) -> Unit)? = null,
    private val beforeControlAdmission: (() -> Unit)? = null,
    private val beforeControlPublication: (() -> Unit)? = null,
    private val activeSessionIdSeed: M0aUuid? = null,
    private val activeCaptureGroupIdSeed: M0aUuid? = null,
    private val activeSessionGenerationSeed: Long = 0L,
    private val activeGroupGenerationSeed: Long = 0L,
    private val activeCoverageEpochSeed: Long = 0L,
    private val isDebuggable: Boolean = false,
    private val debugRecoverySeam: VisibilityGridV2DebugRecoverySeam =
        VisibilityGridV2DebugRecoverySeam(),
    private val cleanupAuthority: CleanupAuthority = CleanupAuthority(),
    private val initialCommittedBaselineSeed: M0aCommittedBaselineV1 =
        M0aCommittedBaselineV1.ZERO,
    internal val beforeAbandonCleanup: (() -> Unit)? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private val disposed = AtomicBoolean(false)
    private val controlHandlerInstalled = AtomicBoolean(true)
    @Volatile private var lifecycle = newLifecycle()
    private val controlChannel = MethodChannel(
        messenger,
        "visibility_grid_v2_control_$viewId",
    )
    private var nativeStreamToken = newOpaqueToken()
    private var workerBindingToken = newOpaqueToken()
    @Volatile private var issue98Probe: M0aDebugTransportProbe? = null
    @Volatile private var issue98Correlation: ByteArray? = null
    @Volatile private var issue98Preparation: Map<String, Any>? = null
    @Volatile private var streamChannel = newStreamChannel()
    @Volatile private var currentBindingGeneration = bindingGenerationSeed
    private var initialTransactionQueued = false
    private var acceptedControls = 0L
    private var closedResources = 0L
    private var activeControlRequestId: M0aUuid? = null
    private var activeSessionId: M0aUuid? = activeSessionIdSeed
    private var activeCaptureGroupId: M0aUuid? = activeCaptureGroupIdSeed
    private var activeSessionGeneration = activeSessionGenerationSeed
    private var activeGroupGeneration = activeGroupGenerationSeed
    private var activeCoverageEpoch = activeCoverageEpochSeed
    private var executorOrdinal = 0L
    private var lifecycleSequence = nextLifecycleSequence.incrementAndGet()
    private var operationGeneration = 0L
    private val executorTrace = ArrayDeque<String>()
    private val publicationFence = Any()
    private val pendingControlResults = ConcurrentHashMap.newKeySet<PendingControlResult>()
    private val pendingCleanupResults = ConcurrentHashMap.newKeySet<PendingCleanupResult>()
    @Volatile private var recoveryGroupCut: RecoveryGroupCut? = null
    @Volatile private var replacementBinding: VisibilityGridV2Binding? = null
    @Volatile private var observationRuntime: AndroidVisibilityGridRuntime? = null

    private fun newLifecycle() = M0aControlLifecycle(
        committedBaselineAuthority = committedBaselineAuthority,
        initialCommittedBaseline = initialCommittedBaselineSeed,
    )

    private fun newStreamChannel() = M0aVisibilitySurfaceStreamChannel(
        messenger = messenger,
        viewId = viewId,
        workerExecutor = executor,
        shutdownWorkerOnDispose = false,
        controlLifecycle = lifecycle,
        onExecutorOperation = ::recordExecutorOperation,
        bindingQualifier = bindingQualifier(),
        beforeWorkerProcessing = debugRecoverySeam::beforeExchange,
        beforeRequestProcessing = debugRecoverySeam::beforeRequest,
        onCommitPublished = { request, baseline ->
            activeReceiptQuery(request, baseline)?.let { query ->
                committedBaselineAuthority.publishCommit(query, baseline)
            }
            debugRecoverySeam.commitPublished()
        },
        afterCommitPublication = debugRecoverySeam::afterCommitPublication,
        onAbandonedRequest = { request, targetBaseline ->
            activeReceiptQuery(request, targetBaseline)?.let { query ->
                committedBaselineAuthority.publishAbandon(query)
            }
        },
        onAbandonedContinuation = debugRecoverySeam::oldContinuationFenced,
        debugTransportProbe = issue98Probe,
    )

    init {
        cleanupAuthority.publishCurrent(currentIdentity())
        controlChannel.setMethodCallHandler(::onControlCall)
    }

    data class Snapshot(
        val bindingGeneration: Long,
        val streamToken: Long,
        val acceptedControls: Long,
        val initialTransactionQueued: Boolean,
        val disposed: Boolean,
        val closedResources: Long,
        val controlRequestId: M0aUuid?,
        val sessionId: M0aUuid?,
        val captureGroupId: M0aUuid?,
        val sessionGeneration: Long,
        val groupGeneration: Long,
        val coverageEpoch: Long,
        val nativeStreamToken: ByteArray,
        val workerBindingToken: ByteArray,
        val arSessionIdentity: ByteArray,
        val viewInstanceId: ByteArray,
        val viewId: Int,
        val viewGeneration: Long,
        val lifecycleSequence: Long,
        val operationGeneration: Long,
        val executorTrace: List<String>,
    )

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        bindingGeneration = currentBindingGeneration,
        streamToken = lifecycle.streamToken(),
        acceptedControls = acceptedControls,
        initialTransactionQueued = initialTransactionQueued,
        disposed = disposed.get(),
        closedResources = closedResources,
        controlRequestId = activeControlRequestId,
        sessionId = activeSessionId,
        captureGroupId = activeCaptureGroupId,
        sessionGeneration = activeSessionGeneration,
        groupGeneration = activeGroupGeneration,
        coverageEpoch = activeCoverageEpoch,
        nativeStreamToken = nativeStreamToken.copyOf(),
        workerBindingToken = workerBindingToken.copyOf(),
        arSessionIdentity = arSessionIdentity.copyOf(),
        viewInstanceId = viewInstanceId.copyOf(),
        viewId = viewId,
        viewGeneration = viewGeneration,
        lifecycleSequence = lifecycleSequence,
        operationGeneration = operationGeneration,
        executorTrace = executorTrace.toList(),
    )

    /** Returns the exact active V2 lifecycle cut or null before a qualified START. */
    internal fun currentObservationOwnership(): VisibilityObservationOwnership? {
        replacementBinding?.let { return it.currentObservationOwnership() }
        val current = snapshot()
        if (current.disposed || !current.initialTransactionQueued) return null
        val sessionId = current.sessionId ?: return null
        val captureGroupId = current.captureGroupId ?: return null
        return VisibilityObservationOwnership(
            sessionId = sessionId.hex(),
            sessionGeneration = current.sessionGeneration,
            captureGroupId = captureGroupId.hex(),
            groupGeneration = current.groupGeneration,
            coverageEpoch = current.coverageEpoch,
            arSessionIdentity = current.arSessionIdentity.hex(),
            viewInstanceId = current.viewInstanceId.hex(),
            viewGeneration = current.viewGeneration,
            nativeStreamToken = current.nativeStreamToken.hex(),
            workerBindingToken = current.workerBindingToken.hex(),
            bindingGeneration = current.bindingGeneration,
            lifecycleSequence = current.lifecycleSequence,
            operationGeneration = current.operationGeneration,
        )
    }

    internal fun attachObservationRuntime(runtime: AndroidVisibilityGridRuntime) {
        observationRuntime = runtime
        replacementBinding?.attachObservationRuntime(runtime)
    }

    private fun onControlCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "configureDebugV2ExchangeStall") {
            if (!isDebuggable) {
                result.error("VG_PROTOCOL_INVALID", "V2 recovery seam is debug-only", null)
            } else {
                result.success(debugRecoverySeam.arm())
            }
            return
        }
        if (call.method == "configureDebugV2CommitPublicationStall") {
            if (!isDebuggable) {
                result.error("VG_PROTOCOL_INVALID", "V2 recovery seam is debug-only", null)
            } else {
                result.success(debugRecoverySeam.armCommitPublication())
            }
            return
        }
        if (call.method == "configureDebugV2AckStall") {
            if (!isDebuggable) {
                result.error("VG_PROTOCOL_INVALID", "V2 recovery seam is debug-only", null)
            } else {
                result.success(debugRecoverySeam.armAcknowledgement())
            }
            return
        }
        if (call.method == "configureDebugV2RestoredStartStall") {
            if (!isDebuggable) {
                result.error("VG_PROTOCOL_INVALID", "V2 recovery seam is debug-only", null)
            } else {
                result.success(debugRecoverySeam.armRestoredStart())
            }
            return
        }
        if (call.method == "getDebugV2RecoveryTrace") {
            if (!isDebuggable) {
                result.error("VG_PROTOCOL_INVALID", "V2 recovery seam is debug-only", null)
            } else {
                result.success(debugRecoverySeam.snapshot())
            }
            return
        }
        if (call.method == "prepareDebugV2Issue98Handoff") {
            if (!isDebuggable) {
                result.error("VG_PROTOCOL_INVALID", "V2 recovery seam is debug-only", null)
            } else {
                val old = M0aCommittedBaselineV1(
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
                lifecycle.setCommittedBaseline(old)
                streamChannel.setCommittedBaseline(
                    old.transactionId, old.geometryRevision, old.lineageRevision,
                    old.styleRevision, captureRevision = old.captureRevision,
                    coverageRevision = old.coverageRevision,
                    regionManifestRevision = old.regionManifestRevision,
                    schemaRootRevision = old.schemaRootRevision,
                    nextSurfaceIdHighWater = old.nextSurfaceIdHighWater,
                )
                val terminal = streamChannel.executeDebugTerminalDrain(99)
                val rootBytes = streamChannel.transportInstrumentation.snapshot()
                    .ordinaryRootSurfaceBytes
                val oldQualifier = bindingQualifier()
                val before = lifecycleResources().ownedResourceCount
                val correlation = newOpaqueToken()
                issue98Correlation = correlation
                val staleEffectRequest = M0aPacketCodec.encodeRequest(
                    M0aPacketCodec.Request(
                        requestFlags = 0,
                        streamToken = 99,
                        acknowledgedTransactionId = old.transactionId,
                        acknowledgedGeometryRevision = old.geometryRevision,
                        acknowledgedLineageRevision = old.lineageRevision,
                        nextStyleRevision = old.styleRevision + 1,
                        maximumResponseBytes = M0aPacketCodec.responseMinimumBytes,
                        styleRecords = listOf(ByteArray(M0aPacketCodec.styleRecordBytes)),
                        commandBytes = byteArrayOf(),
                        requestSequence = 1,
                    ),
                )
                issue98Probe = M0aDebugTransportProbe(oldQualifier + staleEffectRequest, old)
                replaceBinding()
                val after = lifecycleResources().ownedResourceCount
                val preparation = mapOf<String, Any>(
                    "oldTransactionId" to old.transactionId,
                    "oldRequestSequence" to Long.MAX_VALUE,
                    "terminalResultFlags" to terminal.resultFlags,
                    "terminalNextExpectedRequestSequence" to
                        terminal.nextExpectedRequestSequence,
                    "freshTransactionId" to 0L,
                    "freshRequestSequence" to 1L,
                    "nextTransactionId" to 1L,
                    "geometryRevision" to old.geometryRevision,
                    "lineageRevision" to old.lineageRevision,
                    "captureRevision" to old.captureRevision,
                    "coverageRevision" to old.coverageRevision,
                    "acceptedStyleRevision" to old.styleRevision,
                    "regionManifestRevision" to old.regionManifestRevision,
                    "nextSurfaceIdHighWater" to old.nextSurfaceIdHighWater,
                    "schemaRootRevision" to old.schemaRootRevision,
                    "correlationId" to correlation,
                    "oldBindingQualifier" to oldQualifier,
                    "staleRequestBytes" to staleEffectRequest,
                    "rootIsolateSurfaceBytes" to rootBytes,
                    "oldClosedResources" to before,
                    "freshActiveResources" to after,
                )
                issue98Preparation = preparation
                result.success(preparation)
            }
            return
        }
        if (call.method == "finalizeDebugV2Issue98Handoff") {
            val correlation = call.arguments as? ByteArray
            val expected = issue98Correlation
            val preparation = issue98Preparation
            val probeReceipt = streamChannel.debugProbeReceipt()
            if (!isDebuggable || correlation == null || expected == null ||
                !correlation.contentEquals(expected) || preparation == null || probeReceipt == null
            ) {
                result.error("VG_PROTOCOL_INVALID", "Issue 98 attempt is not correlated", null)
            } else {
                issue98Correlation = null
                issue98Preparation = null
                result.success(
                    preparation.filterKeys {
                        it != "correlationId" && it != "oldBindingQualifier" &&
                            it != "staleRequestBytes"
                    } + probeReceipt,
                )
            }
            return
        }
        if (call.method == "bindingSnapshot") {
            val pending = PendingControlResult(result) { pendingControlResults.remove(it) }
            pendingControlResults.add(pending)
            try {
                executor.execute {
                    recordExecutorOperation("control:binding_snapshot")
                    val snapshot = snapshot()
                    post {
                        if (pending.tryClaim()) pending.result.success(snapshot.toMap())
                    }
                }
            } catch (_: RejectedExecutionException) {
                if (pending.tryClaim()) {
                    result.error("VG_NOT_INITIALIZED", "V2 binding executor is closed", null)
                }
            }
            return
        }
        if (call.method == "claimBindingLease") {
            val lease = call.arguments as? ByteArray
            val identity = currentIdentity()
            if (lease == null || lease.size != CLEANUP_LEASE_BYTES ||
                !cleanupAuthority.claimLease(lease, identity)
            ) {
                result.error("VG_STREAM_BINDING_ABANDONED", "V2 cleanup lease claim rejected", null)
                return
            }
            val pending = PendingControlResult(result) { pendingControlResults.remove(it) }
            pendingControlResults.add(pending)
            try {
                executor.execute {
                    recordExecutorOperation("control:claim_binding_lease")
                    val snapshot = snapshot()
                    post {
                        if (pending.tryClaim()) pending.result.success(snapshot.toMap())
                    }
                }
            } catch (_: RejectedExecutionException) {
                if (pending.tryClaim()) {
                    result.error("VG_NOT_INITIALIZED", "V2 binding executor is closed", null)
                }
            }
            return
        }
        if (call.method == "disposeBinding") {
            val admission = admitCleanupBinding(call.arguments as? ByteArray)
            if (admission == null) {
                result.error("VG_STREAM_BINDING_ABANDONED", "V2 binding token mismatch", null)
                return
            }
            debugRecoverySeam.cleanupAdmitted("dispose", admission.identity)
            val pending = PendingCleanupResult(admission.lease, result) {
                pendingCleanupResults.remove(it)
            }
            pendingCleanupResults.add(pending)
            try {
                executor.execute {
                    val outcome = try {
                        runCatching {
                            cleanupBinding(
                                admission,
                                abandonStream = false,
                                currentCleanup = pending,
                            )
                        }
                    } finally {
                        admission.lease.release()
                    }
                    post {
                        if (!pending.tryClaim()) return@post
                        outcome.fold(
                            onSuccess = {
                                debugRecoverySeam.cleanupTerminal("dispose", admission.identity, it)
                                pending.result.success(it.receipt)
                            },
                            onFailure = { pending.result.error("VG_STREAM_BINDING_ABANDONED", it.message, null) },
                        )
                    }
                }
            } catch (_: RejectedExecutionException) {
                val receipt = cleanupAuthority.receiptFor(admission.identity)
                admission.lease.release()
                if (receipt != null && pending.tryClaim()) {
                    result.success(receipt)
                } else if (pending.tryClaim()) {
                    result.error("VG_NOT_INITIALIZED", "V2 binding executor is closed", null)
                }
            }
            return
        }
        if (call.method == "abandonBinding") {
            val admission = admitCleanupBinding(call.arguments as? ByteArray)
            if (admission == null) {
                result.error("VG_STREAM_BINDING_ABANDONED", "V2 binding token mismatch", null)
                return
            }
            debugRecoverySeam.cleanupAdmitted("abandon", admission.identity)
            val pending = PendingCleanupResult(admission.lease, result) {
                pendingCleanupResults.remove(it)
            }
            pendingCleanupResults.add(pending)
            try {
                beforeAbandonCleanup?.invoke()
                val outcome = cleanupBinding(
                    admission,
                    abandonStream = true,
                    currentCleanup = pending,
                )
                debugRecoverySeam.cleanupTerminal("abandon", admission.identity, outcome)
                // The winning abandon constructs and publishes the replacement
                // before any accepted serial-dispose reply can escape. A worker
                // observing that reply can therefore bind immediately without
                // passing through a transient handler-free state.
                if (outcome.wonCleanup) {
                    drainPendingCleanup(admission.identity, outcome.receipt, pending)
                }
                if (pending.tryClaim()) pending.result.success(outcome.receipt)
            } catch (error: Exception) {
                if (pending.tryClaim()) {
                    pending.result.error("VG_STREAM_BINDING_ABANDONED", error.message, null)
                }
            } finally {
                admission.lease.release()
            }
            return
        }
        if (call.method == "queryCommitReceipt") {
            queryCommitReceipt(call, result)
            return
        }
        val operation = when (call.method) {
            "start" -> M0aControlOperation.START
            "beginCheckpoint" -> M0aControlOperation.BEGIN_CHECKPOINT
            "releaseCheckpoint" -> M0aControlOperation.RELEASE_CHECKPOINT
            "stop" -> M0aControlOperation.STOP
            else -> null
        }
        val admission = operation?.let { admitControl(call.arguments as? ByteArray) }
        if (operation == null || admission == null) {
            result.error("VG_PROTOCOL_INVALID", "V2 control requires one Uint8List", null)
            return
        }
        val pending = PendingControlResult(result) { pendingControlResults.remove(it) }
        pendingControlResults.add(pending)
        try {
            executor.execute {
                val outcome = runCatching {
                    checkCurrentBinding(admission.generation, admission.qualifier)
                    recordExecutorOperation("control:${operation.name.lowercase()}")
                    val correlated = M0aControlCodec.decodeCorrelatedRequest(admission.payload, operation)
                    val request = correlated.request
                    val framingFailure = correlated.failure
                    val payloadFailure = if (framingFailure == null) {
                        M0aControlCodec.validateControlPayload(request)
                    } else null
                    val malformed = framingFailure ?: payloadFailure
                    if (malformed != null) {
                        val response = lifecycle.malformed(request, malformed)
                        beforeControlPublication?.invoke()
                        synchronized(publicationFence) {
                            checkCurrentBinding(admission.generation, admission.qualifier)
                            qualify(response)
                        }
                    } else {
                        val wasIdle = lifecycle.state() == M0aControlLifecycle.State.IDLE
                        val response = lifecycle.handle(request, admission.payload)
                        val decoded = M0aControlCodec.decodeResponse(response)
                        if (
                            wasIdle && operation == M0aControlOperation.START &&
                            decoded.outcome == 0
                        ) {
                            recoveryGroupCut = RecoveryGroupCut.from(request)
                            debugRecoverySeam.acceptedCut(request)
                        }
                        debugRecoverySeam.afterRestoredStartQualification(request)
                        beforeControlPublication?.invoke()
                        synchronized(publicationFence) {
                            checkCurrentBinding(admission.generation, admission.qualifier)
                            if (
                                wasIdle && operation == M0aControlOperation.START &&
                                decoded.outcome == 0
                            ) {
                                operationGeneration++
                                lifecycleSequence = nextLifecycleSequence.incrementAndGet()
                                activeControlRequestId = request.controlRequestId
                                activeSessionId = request.sessionId
                                activeCaptureGroupId = request.captureGroupId
                                activeSessionGeneration = request.sessionGeneration
                                activeGroupGeneration = request.groupGeneration
                                activeCoverageEpoch = request.coverageEpoch
                                queueInitialTransaction(lifecycle.committedBaseline())
                            }
                            acceptedControls++
                            qualify(response)
                        }
                    }
                }
                post {
                    if (pending.tryClaim()) {
                        outcome.fold(
                            onSuccess = pending.result::success,
                            onFailure = {
                                val code = if (it is BindingAbandonedException) {
                                    "VG_STREAM_BINDING_ABANDONED"
                                } else {
                                    "VG_PROTOCOL_INVALID"
                                }
                                pending.result.error(code, it.message, null)
                            },
                        )
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (pending.tryClaim()) {
                pending.result.error("VG_NOT_INITIALIZED", "V2 binding executor is closed", null)
            }
        }
    }

    private fun post(task: () -> Unit) {
        val injected = postToMain
        if (injected != null) injected(task) else main.post(task)
    }

    private fun checkCurrentBinding(generation: Long, qualifier: ByteArray) {
        if (
            disposed.get() ||
            generation != currentBindingGeneration ||
            !qualifier.contentEquals(bindingQualifier())
        ) {
            throw BindingAbandonedException()
        }
    }

    private fun queryCommitReceipt(call: MethodCall, result: MethodChannel.Result) {
        val arguments = call.arguments as? Map<*, *>
        if (arguments == null || !qualifierMatches(arguments["currentBindingQualifier"] as? ByteArray)) {
            result.error("VG_STREAM_BINDING_ABANDONED", "V2 binding token mismatch", null)
            return
        }
        try {
            val query = M0aCommitReceiptQueryV1(
                controlRequestId = parseUuid(arguments.requiredString("controlRequestId")),
                scope = M0aCommittedBaselineScopeV1(
                    sessionId = parseUuid(arguments.requiredString("sessionId")),
                    captureGroupId = parseUuid(arguments.requiredString("captureGroupId")),
                    sessionGeneration = arguments.requiredLong("sessionGeneration"),
                    groupGeneration = arguments.requiredLong("groupGeneration"),
                ),
                nativeStreamToken = arguments.requiredToken("nativeStreamToken"),
                workerBindingToken = arguments.requiredToken("workerBindingToken"),
                streamToken = arguments.requiredLong("streamToken"),
                requestSequence = arguments.requiredLong("requestSequence"),
                transactionId = arguments.requiredLong("transactionId"),
                targetGeometryRevision = arguments.requiredLong("targetGeometryRevision"),
                targetLineageRevision = arguments.requiredLong("targetLineageRevision"),
            )
            val receipt = committedBaselineAuthority.queryReceipt(query)
                ?: throw StaleReceiptException()
            result.success(receipt.toMap())
        } catch (_: StaleReceiptException) {
            result.error("VG_STALE_RECEIPT", "V2 receipt qualification is stale", null)
        } catch (error: Exception) {
            result.error("VG_PROTOCOL_INVALID", error.message, null)
        }
    }

    private fun activeReceiptQuery(
        request: M0aPacketCodec.Request,
        targetBaseline: M0aCommittedBaselineV1,
    ): M0aCommitReceiptQueryV1? {
        val controlRequestId = activeControlRequestId ?: return null
        val sessionId = activeSessionId ?: return null
        val captureGroupId = activeCaptureGroupId ?: return null
        return M0aCommitReceiptQueryV1(
            controlRequestId = controlRequestId,
            scope = M0aCommittedBaselineScopeV1(
                sessionId = sessionId,
                captureGroupId = captureGroupId,
                sessionGeneration = activeSessionGeneration,
                groupGeneration = activeGroupGeneration,
            ),
            nativeStreamToken = nativeStreamToken.copyOf(),
            workerBindingToken = workerBindingToken.copyOf(),
            streamToken = request.streamToken,
            requestSequence = request.requestSequence,
            transactionId = targetBaseline.transactionId,
            targetGeometryRevision = targetBaseline.geometryRevision,
            targetLineageRevision = targetBaseline.lineageRevision,
        )
    }

    @Synchronized
    private fun queueInitialTransaction(baseline: M0aCommittedBaselineV1) {
        check(!initialTransactionQueued) { "Initial transaction already queued" }
        check(baseline.transactionId == 0L) {
            "A fresh binding must allocate transaction 1 from cursor zero"
        }
        streamChannel.setCommittedBaseline(
            transactionId = baseline.transactionId,
            geometryRevision = baseline.geometryRevision,
            lineageRevision = baseline.lineageRevision,
            styleRevision = baseline.styleRevision,
            evidenceRevision = baseline.evidenceRevision,
            captureRevision = baseline.captureRevision,
            coverageRevision = baseline.coverageRevision,
            producedStyleRevision = baseline.producedStyleRevision,
            regionManifestRevision = baseline.regionManifestRevision,
            schemaRootRevision = baseline.schemaRootRevision,
            nextSurfaceIdHighWater = baseline.nextSurfaceIdHighWater,
            schemaRootHashIdentity = baseline.schemaRootHashIdentity,
            manifestRootHashIdentity = baseline.manifestRootHashIdentity,
            groupFrameConvention = baseline.groupFrameConvention,
            matrixConvention = baseline.matrixConvention,
            directionConvention = baseline.directionConvention,
            normalEncoding = baseline.normalEncoding,
            groupFromWorldIdentity = baseline.groupFromWorldIdentity,
            worldFromGroupIdentity = baseline.worldFromGroupIdentity,
        )
        streamChannel.queueStructuralTransaction(
            M0aStructuralTransactionProducerV1.produce(
                transactionId = 1,
                baseGeometryRevision = baseline.geometryRevision,
                targetGeometryRevision = baseline.geometryRevision + 1,
                targetLineageRevision = baseline.lineageRevision + 1,
                bytes = byteArrayOf(),
            ),
        )
        initialTransactionQueued = true
    }

    /**
     * Fences one worker binding and creates a fresh binding on the same view.
     *
     * The old stream is disposed before the new lifecycle is published. Since
     * this runs on the shared executor, no old exchange can race the new START.
     */
    @Synchronized
    private fun replaceBinding(
        currentCleanup: PendingCleanupResult? = null,
    ): Map<String, Any?> {
        val resourcesBefore = lifecycleResources(excludedCleanup = currentCleanup)
        val closedBefore = closedResources
        recordExecutorOperation("control:dispose_binding")
        streamChannel.dispose()
        recordClosedResource()
        lifecycle.abandon()
        operationGeneration++
        lifecycleSequence = nextLifecycleSequence.incrementAndGet()
        val teardownReceipt = snapshot().toMap().withCleanupBalances(
            closedBefore = closedBefore,
            before = resourcesBefore,
            after = lifecycleResources(excludedCleanup = currentCleanup),
        )
        synchronized(publicationFence) {
            lifecycle = M0aControlLifecycle(
                committedBaselineAuthority = committedBaselineAuthority,
                initialCommittedBaseline = lifecycle.committedBaseline(),
            )
            currentBindingGeneration = nextBindingGeneration.incrementAndGet()
            nativeStreamToken = newOpaqueToken()
            workerBindingToken = newOpaqueToken()
            streamChannel = newStreamChannel()
        }
        initialTransactionQueued = false
        acceptedControls = 0L
        activeControlRequestId = null
        synchronized(this) {
            executorOrdinal = 0L
            executorTrace.clear()
        }
        cleanupAuthority.publishCurrent(currentIdentity())
        return teardownReceipt
    }

    @Synchronized
    private fun recordExecutorOperation(kind: String) {
        executorOrdinal++
        if (executorTrace.size == MAX_EXECUTOR_TRACE) executorTrace.removeFirst()
        executorTrace.addLast("$executorOrdinal:$kind")
    }

    fun dispose() {
        if (disposed.compareAndSet(false, true)) closeBindingResources()
        replacementBinding?.dispose()
    }

    /**
     * Immediately abandons this binding without entering the serial
     * executor. It clears the old channel and installs a new binding object
     * on the same view, so a stalled executor cannot block fresh identity
     * recovery or deliver an old reply into the replacement.
     */
    private fun abandonAndReplace(
        currentCleanup: PendingCleanupResult? = null,
    ): Map<String, Any?> {
        val resourcesBefore = lifecycleResources(excludedCleanup = currentCleanup)
        val closedBefore = closedResources
        synchronized(publicationFence) {
            check(disposed.compareAndSet(false, true)) { "V2 binding is already abandoned" }
        }
        closeBindingResources(abandonStream = true)
        val oldSnapshot = snapshot().toMap()
        val retainedGroupCut = recoveryGroupCut ?: RecoveryGroupCut(
            sessionId = activeSessionId,
            captureGroupId = activeCaptureGroupId,
            sessionGeneration = activeSessionGeneration,
            groupGeneration = activeGroupGeneration,
            coverageEpoch = activeCoverageEpoch,
        )
        debugRecoverySeam.replacementSeeded(retainedGroupCut)
        replacementBinding = VisibilityGridV2Binding(
            messenger = messenger,
            viewId = viewId,
            committedBaselineAuthority = committedBaselineAuthority,
            viewGeneration = viewGeneration,
            arSessionIdentity = arSessionIdentity,
            viewInstanceId = viewInstanceId,
            postToMain = postToMain,
            activeSessionIdSeed = retainedGroupCut.sessionId,
            activeCaptureGroupIdSeed = retainedGroupCut.captureGroupId,
            activeSessionGenerationSeed = retainedGroupCut.sessionGeneration,
            activeGroupGenerationSeed = retainedGroupCut.groupGeneration,
            activeCoverageEpochSeed = retainedGroupCut.coverageEpoch,
            isDebuggable = isDebuggable,
            debugRecoverySeam = debugRecoverySeam,
            cleanupAuthority = cleanupAuthority,
            initialCommittedBaselineSeed = lifecycle.committedBaseline(),
        ).also { replacement ->
            observationRuntime?.let(replacement::attachObservationRuntime)
        }
        return oldSnapshot.withCleanupBalances(
            closedBefore = closedBefore,
            before = resourcesBefore,
            after = checkNotNull(replacementBinding).lifecycleResources(),
        )
    }

    private fun cleanupBinding(
        admission: BindingAdmission,
        abandonStream: Boolean,
        currentCleanup: PendingCleanupResult? = null,
    ): CleanupOutcome {
        val outcome = cleanupAuthority.claim(admission.identity) {
            val receipt = if (abandonStream) {
                abandonAndReplace(currentCleanup)
            } else {
                replaceBinding(currentCleanup)
            }
            receipt
        } ?: throw BindingAbandonedException()
        return outcome
    }

    private fun drainPendingCleanup(
        winnerIdentity: BindingIdentity,
        winnerReceipt: Map<String, Any?>,
        excludedCleanup: PendingCleanupResult? = null,
    ) {
        pendingCleanupResults.toList().forEach { pending ->
            if (pending === excludedCleanup) return@forEach
            val receipt = if (pending.identity == winnerIdentity) {
                winnerReceipt
            } else {
                cleanupAuthority.receiptFor(pending.identity)
            }
            if (pending.tryClaim()) {
                if (receipt != null) {
                    pending.result.success(receipt)
                } else {
                    pending.result.error(
                        "VG_STREAM_BINDING_ABANDONED",
                        "V2 binding token mismatch",
                        null,
                    )
                }
            }
        }
    }

    private fun closeBindingResources(abandonStream: Boolean = false) {
        terminatePendingControlResults()
        controlChannel.setMethodCallHandler(null)
        controlHandlerInstalled.set(false)
        recordClosedResource()
        if (abandonStream) {
            streamChannel.abandon()
            debugRecoverySeam.releaseAbandonedExchange()
        } else {
            streamChannel.dispose()
        }
        recordClosedResource()
        lifecycle.abandon()
        if (!abandonStream) terminatePendingCleanupResults()
        executor.shutdownNow()
        recordClosedResource()
    }

    /**
     * Executor shutdown is also a terminal callback fence. A task removed by
     * shutdownNow cannot reach its main-thread delivery closure, so claim its
     * result here while the binding is still the authoritative owner.
     */
    private fun terminatePendingControlResults() {
        pendingControlResults.toList().forEach { pending ->
            if (pending.tryClaim()) {
                pending.result.error(
                    "VG_STREAM_BINDING_ABANDONED",
                    "V2 binding was abandoned before control publication",
                    null,
                )
            }
        }
    }

    /**
     * Ordinary lifecycle disposal owns the same callback terminal fence as
     * abandon-driven executor shutdown. Claim every admitted cleanup before
     * shutdownNow can discard its runnable. A cleanup whose winner already
     * published replays that exact receipt; all other queued work receives the
     * canonical binding-abandoned terminal. PendingCleanupResult.tryClaim also
     * releases the admission lease and removes the registration exactly once.
     */
    private fun terminatePendingCleanupResults() {
        pendingCleanupResults.toList().forEach { pending ->
            val receipt = cleanupAuthority.receiptFor(pending.identity)
            if (!pending.tryClaim()) return@forEach
            if (receipt != null) {
                pending.result.success(receipt)
            } else {
                pending.result.error(
                    "VG_STREAM_BINDING_ABANDONED",
                    "V2 binding was abandoned before cleanup publication",
                    null,
                )
            }
        }
    }

    @Synchronized
    private fun recordClosedResource() {
        closedResources++
    }

    private fun bindingQualifier(): ByteArray = nativeStreamToken + workerBindingToken

    private fun admitCleanupBinding(bytes: ByteArray?): BindingAdmission? =
        cleanupAuthority.admit(bytes)?.let { identity ->
            BindingAdmission(CleanupAdmissionLease(cleanupAuthority, identity))
        }

    private fun qualifierMatches(bytes: ByteArray?): Boolean =
        bytes != null && bytes.contentEquals(bindingQualifier())

    private fun admitControl(bytes: ByteArray?): ControlAdmission? = synchronized(publicationFence) {
        beforeControlAdmission?.invoke()
        val qualifier = bindingQualifier()
        if (disposed.get() || bytes == null || bytes.size < qualifier.size ||
            !bytes.copyOfRange(0, qualifier.size).contentEquals(qualifier)
        ) return@synchronized null
        ControlAdmission(
            generation = currentBindingGeneration,
            qualifier = qualifier,
            payload = bytes.copyOfRange(qualifier.size, bytes.size),
        )
    }

    private fun qualify(bytes: ByteArray): ByteArray = bindingQualifier() + bytes

    private fun currentIdentity(): BindingIdentity =
        BindingIdentity(currentBindingGeneration, bindingQualifier())

    private fun M0aUuid.hex(): String = bytes.joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun ByteArray.hex(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun Snapshot.toMap(): Map<String, Any?> = mapOf(
        "bindingGeneration" to bindingGeneration,
        "streamToken" to streamToken,
        "acceptedControls" to acceptedControls,
        "initialTransactionQueued" to initialTransactionQueued,
        "disposed" to disposed,
        "closedResources" to closedResources,
        "controlRequestId" to controlRequestId?.hex(),
        "sessionId" to sessionId?.hex(),
        "captureGroupId" to captureGroupId?.hex(),
        "sessionGeneration" to sessionGeneration,
        "groupGeneration" to groupGeneration,
        "coverageEpoch" to coverageEpoch,
        "nativeStreamToken" to nativeStreamToken,
        "workerBindingToken" to workerBindingToken,
        "arSessionIdentity" to arSessionIdentity,
        "viewInstanceId" to viewInstanceId,
        "viewId" to viewId,
        "viewGeneration" to viewGeneration,
        "lifecycleSequence" to lifecycleSequence,
        "operationGeneration" to operationGeneration,
        "executorTrace" to executorTrace,
        "sourceHealth" to observationRuntime?.snapshotWireMap(),
    )

    private fun Map<String, Any?>.withCleanupBalances(
        closedBefore: Long,
        before: LifecycleResources,
        after: LifecycleResources,
    ): Map<String, Any?> = this + mapOf(
        "closedResourcesBefore" to closedBefore,
        "closedResourcesAfter" to closedResources,
        "handlerCountBefore" to before.handlerCount,
        "handlerCountAfter" to after.handlerCount,
        "handlerBalance" to after.handlerCount - before.handlerCount,
        "callbackCountBefore" to before.callbackCount,
        "callbackCountAfter" to after.callbackCount,
        "callbackBalance" to after.callbackCount - before.callbackCount,
        "executorCountBefore" to before.executorCount,
        "executorCountAfter" to after.executorCount,
        "executorBalance" to after.executorCount - before.executorCount,
        "timeoutSchedulerCountBefore" to before.timeoutSchedulerCount,
        "timeoutSchedulerCountAfter" to after.timeoutSchedulerCount,
        "timeoutSchedulerBalance" to after.timeoutSchedulerCount - before.timeoutSchedulerCount,
        "ownedResourceCountBefore" to before.ownedResourceCount,
        "ownedResourceCountAfter" to after.ownedResourceCount,
        "ownedResourceBalance" to after.ownedResourceCount - before.ownedResourceCount,
    )

    private data class LifecycleResources(
        val handlerCount: Long,
        val executorCount: Long,
        val timeoutSchedulerCount: Long,
        val callbackCount: Long,
    ) {
        val ownedResourceCount: Long
            get() = handlerCount + executorCount + timeoutSchedulerCount
    }

    private fun lifecycleResources(
        excludedCleanup: PendingCleanupResult? = null,
    ): LifecycleResources {
        val stream = streamChannel.lifecycleResources()
        val executorCount = if (executor.isShutdown) 0L else 1L
        return LifecycleResources(
            handlerCount = (if (controlHandlerInstalled.get()) 1L else 0L) + stream.handlerCount,
            executorCount = executorCount,
            timeoutSchedulerCount = stream.timeoutSchedulerCount,
            callbackCount = pendingControlResults.size.toLong() +
                stream.pendingReplyCount +
                pendingCleanupResults.count { it !== excludedCleanup },
        )
    }

    private class BindingAbandonedException : IllegalStateException("V2 binding is abandoned")

    private class StaleReceiptException : IllegalStateException()

    internal data class RecoveryGroupCut(
        val sessionId: M0aUuid?,
        val captureGroupId: M0aUuid?,
        val sessionGeneration: Long,
        val groupGeneration: Long,
        val coverageEpoch: Long,
    ) {
        companion object {
            fun from(request: M0aControlRequest) = RecoveryGroupCut(
                sessionId = request.sessionId,
                captureGroupId = request.captureGroupId,
                sessionGeneration = request.sessionGeneration,
                groupGeneration = request.groupGeneration,
                coverageEpoch = request.coverageEpoch,
            )
        }
    }

    private class PendingControlResult(
        val result: MethodChannel.Result,
        private val onClaimed: (PendingControlResult) -> Unit,
    ) {
        private val claimed = AtomicBoolean(false)

        fun tryClaim(): Boolean {
            val ownsResult = claimed.compareAndSet(false, true)
            if (ownsResult) onClaimed(this)
            return ownsResult
        }
    }

    private class PendingCleanupResult(
        private val admissionLease: CleanupAdmissionLease,
        val result: MethodChannel.Result,
        private val onClaimed: (PendingCleanupResult) -> Unit,
    ) {
        private val claimed = AtomicBoolean(false)
        val identity: BindingIdentity get() = admissionLease.identity

        fun tryClaim(): Boolean {
            val ownsResult = claimed.compareAndSet(false, true)
            if (ownsResult) {
                admissionLease.release()
                onClaimed(this)
            }
            return ownsResult
        }
    }

    internal data class BindingIdentity(
        val generation: Long,
        val qualifier: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is BindingIdentity && generation == other.generation &&
                qualifier.contentEquals(other.qualifier)

        override fun hashCode(): Int = 31 * generation.hashCode() + qualifier.contentHashCode()
    }

    private data class BindingAdmission(val lease: CleanupAdmissionLease) {
        val identity: BindingIdentity get() = lease.identity
    }

    private data class ControlAdmission(
        val generation: Long,
        val qualifier: ByteArray,
        val payload: ByteArray,
    )

    private class CleanupAdmissionLease(
        private val authority: CleanupAuthority,
        val identity: BindingIdentity,
    ) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) authority.release(identity)
        }
    }

    internal data class CleanupOutcome(
        val receipt: Map<String, Any?>,
        val wonCleanup: Boolean,
    )

    internal class CleanupAuthority {
        private val lock = Any()
        private var current: BindingIdentity? = null
        private val terminals = mutableListOf<Pair<BindingIdentity, Map<String, Any?>>>()
        private val activeAdmissions = mutableMapOf<BindingIdentity, Int>()
        private val leases = mutableListOf<Pair<ByteArray, BindingIdentity>>()

        fun publishCurrent(identity: BindingIdentity) = synchronized(lock) {
            current = identity
        }

        fun claimLease(lease: ByteArray, identity: BindingIdentity): Boolean = synchronized(lock) {
            if (lease.size != CLEANUP_LEASE_BYTES || current != identity) return@synchronized false
            leases.firstOrNull { it.first.contentEquals(lease) }?.let {
                return@synchronized it.second == identity
            }
            if (leases.any { it.second == identity }) return@synchronized false
            leases += lease.copyOf() to identity
            trimLeases()
            true
        }

        fun admit(bytes: ByteArray?): BindingIdentity? = synchronized(lock) {
            if (bytes == null) return@synchronized null
            val identity = leases.firstOrNull { it.first.contentEquals(bytes) }?.second
                ?: current?.takeIf { it.qualifier.contentEquals(bytes) }
                ?: terminals.firstOrNull { it.first.qualifier.contentEquals(bytes) }?.first
                ?: return@synchronized null
            activeAdmissions[identity] = (activeAdmissions[identity] ?: 0) + 1
            identity
        }

        fun release(identity: BindingIdentity) = synchronized(lock) {
            val count = activeAdmissions[identity] ?: return@synchronized
            if (count == 1) activeAdmissions.remove(identity) else activeAdmissions[identity] = count - 1
            trimTerminals()
            trimLeases()
        }

        fun receiptFor(identity: BindingIdentity): Map<String, Any?>? = synchronized(lock) {
            terminals.firstOrNull { it.first == identity }?.second
        }

        fun claim(
            identity: BindingIdentity,
            winner: () -> Map<String, Any?>,
        ): CleanupOutcome? = synchronized(lock) {
            terminals.firstOrNull { it.first == identity }?.let {
                return@synchronized CleanupOutcome(it.second, wonCleanup = false)
            }
            if (current != identity) return@synchronized null
            val receipt = winner()
            terminals += identity to receipt
            trimTerminals()
            trimLeases()
            CleanupOutcome(receipt, wonCleanup = true)
        }

        private fun trimLeases() {
            while (leases.size > MAX_RETAINED_CLEANUP_LEASES) {
                val eviction = leases.indexOfFirst { (_, identity) ->
                    identity != current && activeAdmissions[identity] == null
                }
                if (eviction < 0) return
                leases.removeAt(eviction)
            }
        }

        /**
         * Retains at most [MAX_RETAINED_CLEANUP_TERMINALS] unpinned recent
         * terminals. An admitted cleanup pins its exact identity until claim
         * completion; release immediately restores the fixed history bound.
         */
        private fun trimTerminals() {
            while (terminals.size > MAX_RETAINED_CLEANUP_TERMINALS) {
                val eviction = terminals.indexOfFirst { activeAdmissions[it.first] == null }
                if (eviction < 0) return
                terminals.removeAt(eviction)
            }
        }

        internal fun retainedTerminalCount(): Int = synchronized(lock) { terminals.size }
        internal fun retainedLeaseCount(): Int = synchronized(lock) { leases.size }
        internal fun activeAdmissionCount(): Int = synchronized(lock) {
            activeAdmissions.values.sum()
        }
    }

    private companion object {
        val nextBindingGeneration = AtomicLong()
        val nextViewGeneration = AtomicLong()
        val nextLifecycleSequence = AtomicLong()
        const val MAX_EXECUTOR_TRACE = 16
        internal const val MAX_RETAINED_CLEANUP_TERMINALS = 8
        internal const val MAX_RETAINED_CLEANUP_LEASES = 8
        internal const val CLEANUP_LEASE_BYTES = 16

        fun newOpaqueToken(): ByteArray {
            val uuid = UUID.randomUUID()
            return ByteBuffer.allocate(16)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()
        }
    }
}

private fun Map<*, *>.requiredString(key: String): String =
    this[key] as? String ?: error("V2 receipt field $key is not a string")

private fun Map<*, *>.requiredLong(key: String): Long {
    val value = this[key] as? Number
        ?: error("V2 receipt field $key is not numeric")
    return value.toExactLong(key)
}

/**
 * Admits only an exact, finite, integral signed-64-bit platform number.
 *
 * Flutter's standard codec normally delivers Dart ints as Long, but a caller
 * can still send Double/Float values over the MethodChannel. Converting those
 * with Number.toLong() would truncate fractions and saturate non-finite or
 * out-of-range values, changing the receipt identity before qualification.
 */
private fun Number.toExactLong(key: String): Long = when (this) {
    is Long -> this
    is Int -> toLong()
    is Short -> toLong()
    is Byte -> toLong()
    is Double -> toExactFloatingLong(key)
    is Float -> toDouble().toExactFloatingLong(key)
    else -> {
        val decimal = try {
            BigDecimal(toString())
        } catch (_: NumberFormatException) {
            error("V2 receipt field $key is not an exact integer")
        }
        try {
            decimal.longValueExact()
        } catch (_: ArithmeticException) {
            error("V2 receipt field $key is not an exact signed-64-bit integer")
        }
    }
}

private fun Double.toExactFloatingLong(key: String): Long {
    // 2^63 is exactly representable as a Double but is one past Long.MAX_VALUE.
    val signed64ExclusiveUpperBound = 9.223372036854776E18
    if (!isFinite() || this % 1.0 != 0.0 ||
        this < -signed64ExclusiveUpperBound ||
        this >= signed64ExclusiveUpperBound
    ) {
        error("V2 receipt field $key is not an exact signed-64-bit integer")
    }
    return toLong()
}

private fun Map<*, *>.requiredToken(key: String): ByteArray {
    val value = this[key] as? ByteArray ?: error("V2 receipt field $key is not bytes")
    require(value.size == 16) { "V2 receipt field $key must be 16 bytes" }
    return value.copyOf()
}

private fun parseUuid(value: String): M0aUuid {
    require(value.length == 32) { "V2 receipt UUID must be 32 hex characters" }
    val bytes = ByteArray(16) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    return M0aUuid(bytes)
}

internal class VisibilityGridV2DebugRecoverySeam {
    companion object {
        private const val MAX_TRACE_ENTRIES = 64
    }

    private val lock = Any()
    private val trace = mutableListOf<String>()
    private var exchangeGate: CountDownLatch? = null
    private var oldContinuation: CountDownLatch? = null
    private var stallClaimed = false
    private var commitPublicationStall = false
    private var acknowledgementStall = false
    private var restoredStartStall = false
    private var restoredStartPhase = false
    private var recoveryTraceActive = false
    private var replacementSeeded = false

    fun arm(): Map<String, Any> = synchronized(lock) {
        check(exchangeGate == null) { "V2 recovery seam is already armed" }
        trace.clear()
        commitPublicationStall = false
        acknowledgementStall = false
        restoredStartStall = false
        restoredStartPhase = false
        appendTrace("armed:first-exchange")
        exchangeGate = CountDownLatch(1)
        oldContinuation = CountDownLatch(1)
        stallClaimed = false
        recoveryTraceActive = true
        replacementSeeded = false
        mapOf("armed" to true)
    }

    fun armCommitPublication(): Map<String, Any> = synchronized(lock) {
        check(exchangeGate == null) { "V2 recovery seam is already armed" }
        trace.clear()
        commitPublicationStall = true
        acknowledgementStall = false
        restoredStartStall = false
        restoredStartPhase = false
        appendTrace("armed:commit-publication")
        exchangeGate = CountDownLatch(1)
        oldContinuation = CountDownLatch(1)
        stallClaimed = false
        recoveryTraceActive = true
        replacementSeeded = false
        mapOf("armed" to true)
    }

    fun armAcknowledgement(): Map<String, Any> = synchronized(lock) {
        check(exchangeGate == null) { "V2 recovery seam is already armed" }
        trace.clear()
        commitPublicationStall = false
        acknowledgementStall = true
        restoredStartStall = false
        restoredStartPhase = false
        appendTrace("armed:acknowledgement")
        exchangeGate = CountDownLatch(1)
        oldContinuation = CountDownLatch(1)
        stallClaimed = false
        recoveryTraceActive = true
        replacementSeeded = false
        mapOf("armed" to true)
    }

    fun armRestoredStart(): Map<String, Any> = synchronized(lock) {
        check(exchangeGate == null) { "V2 recovery seam is already armed" }
        trace.clear()
        commitPublicationStall = false
        acknowledgementStall = true
        restoredStartStall = true
        restoredStartPhase = false
        appendTrace("armed:restored-start")
        exchangeGate = CountDownLatch(1)
        oldContinuation = CountDownLatch(1)
        stallClaimed = false
        recoveryTraceActive = true
        replacementSeeded = false
        mapOf("armed" to true)
    }

    fun beforeExchange() {
        if (synchronized(lock) { commitPublicationStall || acknowledgementStall }) return
        val gate = synchronized(lock) {
            val candidate = exchangeGate
            if (candidate == null || stallClaimed) return
            stallClaimed = true
            appendTrace("stalled:first-exchange")
            candidate
        }
        var interrupted = false
        while (true) {
            try {
                gate.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    fun beforeRequest(request: M0aPacketCodec.Request) {
        val shouldStall = synchronized(lock) {
            acknowledgementStall && request.requestSequence == 3L && !stallClaimed
        }
        if (!shouldStall) return
        val gate = synchronized(lock) {
            if (!acknowledgementStall || stallClaimed) return
            stallClaimed = true
            appendTrace("stalled:acknowledgement")
            checkNotNull(exchangeGate)
        }
        var interrupted = false
        while (true) {
            try {
                gate.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        oldContinuationFenced()
    }

    fun afterCommitPublication() {
        val gate = synchronized(lock) {
            if (!commitPublicationStall || exchangeGate == null || stallClaimed) return
            stallClaimed = true
            appendTrace("stalled:commit-publication")
            checkNotNull(exchangeGate)
        }
        var interrupted = false
        while (true) {
            try {
                gate.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        oldContinuationFenced()
    }

    fun afterRestoredStartQualification(request: M0aControlRequest) {
        val restored = request.operation == M0aControlOperation.START &&
            M0aStartRequestCodecV2.decode(request.payload).restoreRequested
        if (!restored) return
        val gate = synchronized(lock) {
            if (!restoredStartStall || !restoredStartPhase || stallClaimed) return
            stallClaimed = true
            appendTrace("stalled:restored-start")
            checkNotNull(exchangeGate)
        }
        var interrupted = false
        while (true) {
            try {
                gate.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        oldContinuationFenced()
    }

    fun commitPublished() = synchronized(lock) {
        if (commitPublicationStall || acknowledgementStall) appendTrace("commit-published")
    }

    fun acceptedCut(request: M0aControlRequest) = synchronized(lock) {
        if (recoveryTraceActive) {
            appendTrace("accepted-cut:${request.cutIdentity()}")
            val start = M0aStartRequestCodecV2.decode(request.payload)
            appendTrace(
                "accepted-start:restore=${start.restoreRequested}:" +
                    "geometry=${start.restoredRevisions[1]}:lineage=${start.restoredRevisions[2]}",
            )
            if (replacementSeeded && !restoredStartStall) recoveryTraceActive = false
        }
    }

    fun replacementSeeded(cut: VisibilityGridV2Binding.RecoveryGroupCut) = synchronized(lock) {
        if (recoveryTraceActive) {
            appendTrace("replacement-seeded:${cut.cutIdentity()}")
            replacementSeeded = true
        }
    }

    fun cleanupAdmitted(
        kind: String,
        identity: VisibilityGridV2Binding.BindingIdentity,
    ) = synchronized(lock) {
        if (recoveryTraceActive) {
            appendTrace("cleanup-admitted:$kind:${identity.traceIdentity()}")
        }
    }

    fun cleanupTerminal(
        kind: String,
        identity: VisibilityGridV2Binding.BindingIdentity,
        outcome: VisibilityGridV2Binding.CleanupOutcome,
    ) = synchronized(lock) {
        if (recoveryTraceActive) {
            val winner = if (outcome.wonCleanup) "winner" else "replay"
            val resources = outcome.receipt["closedResources"]
            appendTrace(
                "cleanup-terminal:$kind:$winner:${identity.traceIdentity()}:resources=$resources",
            )
        }
    }

    fun releaseAbandonedExchange() {
        val (gate, continuation) = synchronized(lock) {
            val activeGate = exchangeGate ?: return
            appendTrace("abandon-won")
            activeGate to checkNotNull(oldContinuation)
        }
        gate.countDown()
        try {
            check(continuation.await(2, TimeUnit.SECONDS)) {
                "Abandoned V2 exchange did not reach its publication fence"
            }
        } finally {
            synchronized(lock) {
                if (restoredStartStall && !restoredStartPhase) {
                    exchangeGate = CountDownLatch(1)
                    oldContinuation = CountDownLatch(1)
                    stallClaimed = false
                    acknowledgementStall = false
                    restoredStartPhase = true
                } else {
                    exchangeGate = null
                    oldContinuation = null
                    stallClaimed = false
                    commitPublicationStall = false
                    acknowledgementStall = false
                    restoredStartStall = false
                    restoredStartPhase = false
                }
            }
        }
    }

    fun oldContinuationFenced() {
        synchronized(lock) {
            if (exchangeGate == null) return
            appendTrace("late-old-completion-fenced")
            oldContinuation?.countDown()
        }
    }

    fun snapshot(): Map<String, Any> = synchronized(lock) {
        mapOf("trace" to trace.toList())
    }

    private fun appendTrace(entry: String) {
        if (trace.size == MAX_TRACE_ENTRIES) trace.removeAt(0)
        trace += entry
    }

    private fun M0aControlRequest.cutIdentity(): String =
        "${sessionId.hex()}:${captureGroupId.hex()}:" +
            "$sessionGeneration:$groupGeneration:$coverageEpoch"

    private fun VisibilityGridV2Binding.BindingIdentity.traceIdentity(): String =
        "$generation:${qualifier.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }}"

    private fun VisibilityGridV2Binding.RecoveryGroupCut.cutIdentity(): String =
        "${sessionId?.hex()}:${captureGroupId?.hex()}:" +
            "$sessionGeneration:$groupGeneration:$coverageEpoch"

    private fun M0aUuid.hex(): String = bytes.joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
