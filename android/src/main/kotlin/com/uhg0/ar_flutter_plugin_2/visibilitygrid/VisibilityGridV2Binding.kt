package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.os.Handler
import android.os.Looper
import com.uhg0.ar_flutter_plugin_2.m0.M0aCommittedBaselineAuthority
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlCodec
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlLifecycle
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlOperation
import com.uhg0.ar_flutter_plugin_2.m0.M0aUuid
import com.uhg0.ar_flutter_plugin_2.m0.M0aStructuralTransactionProducerV1
import com.uhg0.ar_flutter_plugin_2.m0.M0aVisibilitySurfaceStreamChannel
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.ArrayDeque
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Production owner for one immutable V2 platform-view binding generation.
 *
 * Control and high-rate exchange enter the same serial executor. A successful
 * fresh START queues one empty, revisioned structural transaction so the
 * worker must validate BEGIN/COMMIT and publish an exact ACK before the
 * binding is considered established.
 */
class VisibilityGridV2Binding(
    private val messenger: BinaryMessenger,
    private val viewId: Int,
    private val committedBaselineAuthority: M0aCommittedBaselineAuthority,
    private val bindingGenerationSeed: Long = nextBindingGeneration.incrementAndGet(),
    private val viewGeneration: Long = nextViewGeneration.incrementAndGet(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
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
    private val arSessionIdentity = newOpaqueToken()
    private val viewInstanceId = newOpaqueToken()
    private var initialTransactionQueued = false
    private var acceptedControls = 0L
    private var closedResources = 0L
    private var activeControlRequestId: M0aUuid? = null
    private var activeSessionId: M0aUuid? = null
    private var activeCaptureGroupId: M0aUuid? = null
    private var activeSessionGeneration = 0L
    private var activeGroupGeneration = 0L
    private var activeCoverageEpoch = 0L
    private var executorOrdinal = 0L
    private var lifecycleSequence = nextLifecycleSequence.incrementAndGet()
    private var operationGeneration = 0L
    private val executorTrace = ArrayDeque<String>()

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
        if (call.method == "bindingSnapshot") {
            try {
                executor.execute {
                    recordExecutorOperation("control:binding_snapshot")
                    val snapshot = snapshot()
                    main.post {
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
                    val teardownReceipt = replaceBinding()
                    main.post { result.success(teardownReceipt.toMap()) }
                }
            } catch (_: RejectedExecutionException) {
                result.error("VG_NOT_INITIALIZED", "V2 binding executor is closed", null)
            }
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
        try {
            executor.execute {
                val outcome = runCatching {
                    check(!disposed.get()) { "V2 binding is disposed" }
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
                        operationGeneration++
                        lifecycleSequence = nextLifecycleSequence.incrementAndGet()
                        activeControlRequestId = request.controlRequestId
                        activeSessionId = request.sessionId
                        activeCaptureGroupId = request.captureGroupId
                        activeSessionGeneration = request.sessionGeneration
                        activeGroupGeneration = request.groupGeneration
                        activeCoverageEpoch = request.coverageEpoch
                        queueInitialTransaction()
                    }
                    acceptedControls++
                    qualify(response)
                }
                main.post {
                    outcome.fold(
                        onSuccess = result::success,
                        onFailure = {
                            result.error("VG_PROTOCOL_INVALID", it.message, null)
                        },
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            result.error("VG_NOT_INITIALIZED", "V2 binding executor is closed", null)
        }
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
        check(!disposed.get()) { "V2 binding is disposed" }
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
        activeSessionId = null
        activeCaptureGroupId = null
        activeSessionGeneration = 0L
        activeGroupGeneration = 0L
        activeCoverageEpoch = 0L
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
        if (!disposed.compareAndSet(false, true)) return
        controlChannel.setMethodCallHandler(null)
        recordClosedResource()
        streamChannel.dispose()
        recordClosedResource()
        lifecycle.abandon()
        executor.shutdown()
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
