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
import com.uhg0.ar_flutter_plugin_2.m0.M0aVisibilitySurfaceStreamChannel
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
    private val beforeControlPublication: (() -> Unit)? = null,
    private val activeSessionIdSeed: M0aUuid? = null,
    private val activeCaptureGroupIdSeed: M0aUuid? = null,
    private val activeSessionGenerationSeed: Long = 0L,
    private val activeGroupGenerationSeed: Long = 0L,
    private val activeCoverageEpochSeed: Long = 0L,
    private val isDebuggable: Boolean = false,
    private val debugRecoverySeam: VisibilityGridV2DebugRecoverySeam =
        VisibilityGridV2DebugRecoverySeam(),
) {
    private val main = Handler(Looper.getMainLooper())
    private val disposed = AtomicBoolean(false)
    @Volatile private var lifecycle = newLifecycle()
    private val controlChannel = MethodChannel(
        messenger,
        "visibility_grid_v2_control_$viewId",
    )
    private var nativeStreamToken = newOpaqueToken()
    private var workerBindingToken = newOpaqueToken()
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
    @Volatile private var recoveryGroupCut: RecoveryGroupCut? = null
    @Volatile private var replacementBinding: VisibilityGridV2Binding? = null

    private fun newLifecycle() = M0aControlLifecycle(
        committedBaselineAuthority = committedBaselineAuthority,
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
    )

    init {
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
        if (call.method == "getDebugV2RecoveryTrace") {
            if (!isDebuggable) {
                result.error("VG_PROTOCOL_INVALID", "V2 recovery seam is debug-only", null)
            } else {
                result.success(debugRecoverySeam.snapshot())
            }
            return
        }
        if (call.method == "bindingSnapshot") {
            try {
                executor.execute {
                    recordExecutorOperation("control:binding_snapshot")
                    val snapshot = snapshot()
                    post {
                        result.success(snapshot.toMap())
                    }
                }
            } catch (_: RejectedExecutionException) {
                result.error("VG_NOT_INITIALIZED", "V2 binding executor is closed", null)
            }
            return
        }
        if (call.method == "disposeBinding") {
            if (!qualifierMatches(call.arguments as? ByteArray)) {
                result.error("VG_STREAM_BINDING_ABANDONED", "V2 binding token mismatch", null)
                return
            }
            try {
                executor.execute {
                    recordExecutorOperation("control:dispose_binding")
                    val outcome = runCatching { replaceBinding() }
                    main.post {
                        outcome.fold(
                            onSuccess = { result.success(it.toMap()) },
                            onFailure = {
                                result.error(
                                    "VG_STREAM_BINDING_ABANDONED",
                                    it.message,
                                    null,
                                )
                            },
                        )
                    }
                }
            } catch (_: RejectedExecutionException) {
                result.error("VG_NOT_INITIALIZED", "V2 binding executor is closed", null)
            }
            return
        }
        if (call.method == "abandonBinding") {
            if (!qualifierMatches(call.arguments as? ByteArray)) {
                result.error("VG_STREAM_BINDING_ABANDONED", "V2 binding token mismatch", null)
                return
            }
            try {
                val teardownReceipt = abandonAndReplace()
                result.success(teardownReceipt.toMap())
            } catch (error: Exception) {
                result.error("VG_STREAM_BINDING_ABANDONED", error.message, null)
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
        val bytes = authenticatedPayload(call.arguments as? ByteArray)
        if (operation == null || bytes == null) {
            result.error("VG_PROTOCOL_INVALID", "V2 control requires one Uint8List", null)
            return
        }
        val admittedGeneration = currentBindingGeneration
        val admittedQualifier = bindingQualifier()
        val pending = PendingControlResult(result) { pendingControlResults.remove(it) }
        pendingControlResults.add(pending)
        try {
            executor.execute {
                val outcome = runCatching {
                    checkCurrentBinding(admittedGeneration, admittedQualifier)
                    val request = M0aControlCodec.decodeRequest(bytes)
                    recordExecutorOperation("control:${operation.name.lowercase()}")
                    require(request.operation == operation) {
                        "Control method and operation differ"
                    }
                    val wasIdle = lifecycle.state() == M0aControlLifecycle.State.IDLE
                    val response = lifecycle.handle(request, bytes)
                    val decoded = M0aControlCodec.decodeResponse(response)
                    if (
                        wasIdle && operation == M0aControlOperation.START &&
                        decoded.outcome == 0
                    ) {
                        recoveryGroupCut = RecoveryGroupCut.from(request)
                        debugRecoverySeam.acceptedCut(request)
                    }
                    beforeControlPublication?.invoke()
                    synchronized(publicationFence) {
                        checkCurrentBinding(admittedGeneration, admittedQualifier)
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
                            if (lifecycle.committedBaseline() == M0aCommittedBaselineV1.ZERO) {
                                queueInitialTransaction()
                            } else {
                                // A restored authoritative cut already contains
                                // the committed transaction. Mark startup as
                                // established without replaying transaction 1.
                                initialTransactionQueued = true
                            }
                        }
                        acceptedControls++
                        qualify(response)
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
    private fun queueInitialTransaction() {
        check(!initialTransactionQueued) { "Initial transaction already queued" }
        streamChannel.queueStructuralTransaction(
            M0aStructuralTransactionProducerV1.produce(
                transactionId = 1,
                baseGeometryRevision = 0,
                targetGeometryRevision = 1,
                targetLineageRevision = 1,
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
    private fun replaceBinding(): Snapshot {
        // A qualified serial dispose can already be queued when the bounded
        // recovery path independently abandons this generation. Once that
        // abandon wins, its replacement owns the channel and this late task
        // is cleanup-only: return the old terminal snapshot without touching
        // the replacement or throwing on the executor thread.
        if (disposed.get()) return snapshot()
        streamChannel.dispose()
        recordClosedResource()
        lifecycle.abandon()
        operationGeneration++
        lifecycleSequence = nextLifecycleSequence.incrementAndGet()
        val teardownReceipt = snapshot()
        lifecycle = newLifecycle()
        currentBindingGeneration = nextBindingGeneration.incrementAndGet()
        nativeStreamToken = newOpaqueToken()
        workerBindingToken = newOpaqueToken()
        streamChannel = newStreamChannel()
        initialTransactionQueued = false
        acceptedControls = 0L
        activeControlRequestId = null
        synchronized(this) {
            executorOrdinal = 0L
            executorTrace.clear()
        }
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
    private fun abandonAndReplace(): Snapshot {
        synchronized(publicationFence) {
            check(disposed.compareAndSet(false, true)) { "V2 binding is already abandoned" }
        }
        pendingControlResults.toList().forEach { pending ->
            if (pending.tryClaim()) {
                pending.result.error(
                    "VG_STREAM_BINDING_ABANDONED",
                    "V2 binding was abandoned before control publication",
                    null,
                )
            }
        }
        closeBindingResources(abandonStream = true)
        val teardownReceipt = snapshot()
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
        )
        return teardownReceipt
    }

    private fun closeBindingResources(abandonStream: Boolean = false) {
        controlChannel.setMethodCallHandler(null)
        recordClosedResource()
        if (abandonStream) {
            streamChannel.abandon()
            debugRecoverySeam.releaseAbandonedExchange()
        } else {
            streamChannel.dispose()
        }
        recordClosedResource()
        lifecycle.abandon()
        executor.shutdownNow()
        recordClosedResource()
    }

    @Synchronized
    private fun recordClosedResource() {
        closedResources++
    }

    private fun bindingQualifier(): ByteArray = nativeStreamToken + workerBindingToken

    private fun qualifierMatches(bytes: ByteArray?): Boolean =
        bytes != null && bytes.contentEquals(bindingQualifier())

    private fun authenticatedPayload(bytes: ByteArray?): ByteArray? {
        val qualifier = bindingQualifier()
        if (bytes == null || bytes.size < qualifier.size ||
            !bytes.copyOfRange(0, qualifier.size).contentEquals(qualifier)) return null
        return bytes.copyOfRange(qualifier.size, bytes.size)
    }

    private fun qualify(bytes: ByteArray): ByteArray = bindingQualifier() + bytes

    private fun M0aUuid.hex(): String = bytes.joinToString("") { byte ->
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
    )

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

    private companion object {
        val nextBindingGeneration = AtomicLong()
        val nextViewGeneration = AtomicLong()
        val nextLifecycleSequence = AtomicLong()
        const val MAX_EXECUTOR_TRACE = 16

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
    private val lock = Any()
    private val trace = mutableListOf<String>()
    private var exchangeGate: CountDownLatch? = null
    private var oldContinuation: CountDownLatch? = null
    private var stallClaimed = false
    private var commitPublicationStall = false

    fun arm(): Map<String, Any> = synchronized(lock) {
        check(exchangeGate == null) { "V2 recovery seam is already armed" }
        trace.clear()
        commitPublicationStall = false
        trace += "armed:first-exchange"
        exchangeGate = CountDownLatch(1)
        oldContinuation = CountDownLatch(1)
        stallClaimed = false
        mapOf("armed" to true)
    }

    fun armCommitPublication(): Map<String, Any> = synchronized(lock) {
        check(exchangeGate == null) { "V2 recovery seam is already armed" }
        trace.clear()
        commitPublicationStall = true
        trace += "armed:commit-publication"
        exchangeGate = CountDownLatch(1)
        oldContinuation = CountDownLatch(1)
        stallClaimed = false
        mapOf("armed" to true)
    }

    fun beforeExchange() {
        if (synchronized(lock) { commitPublicationStall }) return
        val gate = synchronized(lock) {
            val candidate = exchangeGate
            if (candidate == null || stallClaimed) return
            stallClaimed = true
            trace += "stalled:first-exchange"
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

    fun afterCommitPublication() {
        val gate = synchronized(lock) {
            if (!commitPublicationStall || exchangeGate == null || stallClaimed) return
            stallClaimed = true
            trace += "stalled:commit-publication"
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
        if (commitPublicationStall) trace += "commit-published"
    }

    fun acceptedCut(request: M0aControlRequest) = synchronized(lock) {
        if (exchangeGate != null) trace += "accepted-cut:${request.cutIdentity()}"
    }

    fun replacementSeeded(cut: VisibilityGridV2Binding.RecoveryGroupCut) = synchronized(lock) {
        if (exchangeGate != null) trace += "replacement-seeded:${cut.cutIdentity()}"
    }

    fun releaseAbandonedExchange() {
        val (gate, continuation) = synchronized(lock) {
            val activeGate = exchangeGate ?: return
            trace += "abandon-won"
            activeGate to checkNotNull(oldContinuation)
        }
        gate.countDown()
        check(continuation.await(2, TimeUnit.SECONDS)) {
            "Abandoned V2 exchange did not reach its publication fence"
        }
    }

    fun oldContinuationFenced() {
        synchronized(lock) {
            if (exchangeGate == null) return
            trace += "late-old-completion-fenced"
            oldContinuation?.countDown()
        }
    }

    fun snapshot(): Map<String, Any> = synchronized(lock) {
        mapOf("trace" to trace.toList())
    }

    private fun M0aControlRequest.cutIdentity(): String =
        "${sessionId.hex()}:${captureGroupId.hex()}:" +
            "$sessionGeneration:$groupGeneration:$coverageEpoch"

    private fun VisibilityGridV2Binding.RecoveryGroupCut.cutIdentity(): String =
        "${sessionId?.hex()}:${captureGroupId?.hex()}:" +
            "$sessionGeneration:$groupGeneration:$coverageEpoch"

    private fun M0aUuid.hex(): String = bytes.joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
