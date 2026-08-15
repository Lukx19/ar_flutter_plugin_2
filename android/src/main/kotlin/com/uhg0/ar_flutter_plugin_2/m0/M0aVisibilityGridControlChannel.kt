package com.uhg0.ar_flutter_plugin_2.m0

import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/**
 * Debug-only packed VGC2 control seam for the M0a reference campaign.
 *
 * The production V1 channel remains the compatibility adapter. This channel
 * deliberately accepts one Uint8List and returns one Uint8List for each
 * control method; it never exposes a map, surface array, or sensor buffer.
 */
class M0aVisibilityGridControlChannel(
    messenger: BinaryMessenger,
    viewId: Int,
) : MethodChannel.MethodCallHandler {
    private val channel = MethodChannel(messenger, "visibility_grid_control_$viewId")
    private val executor = Executors.newSingleThreadExecutor()
    private var disposed = false
    private var lastRequest: ByteArray? = null
    private var lastResponse: ByteArray? = null

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "dispose") {
            dispose()
            result.success(true)
            return
        }
        synchronized(this) {
            if (disposed) {
                result.error("VG_NOT_INITIALIZED", "M0a control channel is disposed", null)
                return
            }
        }
        val operation = operationFor(call.method)
        val bytes = call.arguments as? ByteArray
        if (operation == null || bytes == null) {
            result.error("VG_PROTOCOL_INVALID", "M0a control requires one Uint8List", null)
            return
        }
        executor.execute {
            try {
                val response = synchronized(this) {
                    if (disposed) return@synchronized null
                    val request = M0aControlCodec.decodeRequest(bytes)
                    require(request.operation == operation) { "Control method and operation differ" }
                    val previous = lastRequest
                    if (previous != null && previous.contentEquals(bytes)) {
                        return@synchronized lastResponse!!.copyOf()
                    }
                    require(previous == null || request.controlRequestId !=
                        M0aControlCodec.decodeRequest(previous).controlRequestId) {
                        "Control request replay conflict"
                    }
                    val responseBytes = M0aControlCodec.encodeResponse(
                        M0aControlResponse(
                            operation = request.operation,
                            outcome = 1,
                            resultFlags = 0,
                            errorId = 0,
                            controlRequestId = request.controlRequestId,
                            sessionId = request.sessionId,
                            captureGroupId = request.captureGroupId,
                            sessionGeneration = request.sessionGeneration,
                            groupGeneration = request.groupGeneration,
                            coverageEpoch = request.coverageEpoch,
                            streamToken = if (request.operation == M0aControlOperation.START) 1 else request.streamToken,
                            nextExchangeRequestSequence = 1,
                            nativeTransactionId = 0,
                        ),
                        M0aControlCodec.hardCeilingBytes,
                    )
                    lastRequest = bytes.copyOf()
                    lastResponse = responseBytes.copyOf()
                    responseBytes
                }
                result.success(response)
            } catch (error: Exception) {
                result.error("VG_PROTOCOL_INVALID", error.message, null)
            }
        }
    }

    fun dispose() {
        synchronized(this) {
            if (disposed) return
            disposed = true
            lastRequest = null
            lastResponse = null
        }
        channel.setMethodCallHandler(null)
        executor.shutdownNow()
    }

    private fun operationFor(method: String): M0aControlOperation? = when (method) {
        "start" -> M0aControlOperation.START
        "beginCheckpoint" -> M0aControlOperation.BEGIN_CHECKPOINT
        "releaseCheckpoint" -> M0aControlOperation.RELEASE_CHECKPOINT
        "stop" -> M0aControlOperation.STOP
        else -> null
    }
}
