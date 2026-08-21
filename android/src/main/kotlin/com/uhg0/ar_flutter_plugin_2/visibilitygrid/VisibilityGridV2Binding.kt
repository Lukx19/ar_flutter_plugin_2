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

/**
 * Production owner for one immutable V2 platform-view binding generation.
 *
 * Control and high-rate exchange enter the same serial executor. A successful
 * fresh START queues one empty, revisioned structural transaction so the
 * worker must validate BEGIN/COMMIT and publish an exact ACK before the
 * binding is considered established.
 */
class VisibilityGridV2Binding(
    messenger: BinaryMessenger,
    viewId: Int,
    committedBaselineAuthority: M0aCommittedBaselineAuthority,
    val bindingGeneration: Long = nextBindingGeneration.incrementAndGet(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val main = Handler(Looper.getMainLooper())
    private val disposed = AtomicBoolean(false)
    private val lifecycle = M0aControlLifecycle(
        committedBaselineAuthority = committedBaselineAuthority,
    )
    private val controlChannel = MethodChannel(
        messenger,
        "visibility_grid_v2_control_$viewId",
    )
    private val streamChannel = M0aVisibilitySurfaceStreamChannel(
        messenger = messenger,
        viewId = viewId,
        workerExecutor = executor,
        shutdownWorkerOnDispose = false,
        controlLifecycle = lifecycle,
        onExecutorOperation = ::recordExecutorOperation,
    )
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
    private val executorTrace = ArrayDeque<String>()

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
        val executorTrace: List<String>,
    )

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        bindingGeneration = bindingGeneration,
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
        executorTrace = executorTrace.toList(),
    )

    private fun onControlCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "bindingSnapshot") {
            try {
                executor.execute {
                    recordExecutorOperation("control:binding_snapshot")
                    val snapshot = snapshot()
                    main.post {
                        result.success(
                            mapOf(
                                "bindingGeneration" to snapshot.bindingGeneration,
                                "streamToken" to snapshot.streamToken,
                                "acceptedControls" to snapshot.acceptedControls,
                                "initialTransactionQueued" to snapshot.initialTransactionQueued,
                                "disposed" to snapshot.disposed,
                                "closedResources" to snapshot.closedResources,
                                "controlRequestId" to snapshot.controlRequestId?.hex(),
                                "sessionId" to snapshot.sessionId?.hex(),
                                "captureGroupId" to snapshot.captureGroupId?.hex(),
                                "sessionGeneration" to snapshot.sessionGeneration,
                                "groupGeneration" to snapshot.groupGeneration,
                                "coverageEpoch" to snapshot.coverageEpoch,
                                "executorTrace" to snapshot.executorTrace,
                            ),
                        )
                    }
                }
            } catch (_: RejectedExecutionException) {
                result.error("VG_NOT_INITIALIZED", "V2 binding executor is closed", null)
            }
            return
        }
        if (call.method == "disposeBinding") {
            try {
                executor.execute {
                    recordExecutorOperation("control:dispose_binding")
                    lifecycle.abandon()
                    main.post { result.success(true) }
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
        val bytes = call.arguments as? ByteArray
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
                        activeControlRequestId = request.controlRequestId
                        activeSessionId = request.sessionId
                        activeCaptureGroupId = request.captureGroupId
                        activeSessionGeneration = request.sessionGeneration
                        activeGroupGeneration = request.groupGeneration
                        activeCoverageEpoch = request.coverageEpoch
                        queueInitialTransaction()
                    }
                    acceptedControls++
                    response
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

    private fun M0aUuid.hex(): String = bytes.joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private companion object {
        val nextBindingGeneration = AtomicLong()
        const val MAX_EXECUTOR_TRACE = 16
    }
}
