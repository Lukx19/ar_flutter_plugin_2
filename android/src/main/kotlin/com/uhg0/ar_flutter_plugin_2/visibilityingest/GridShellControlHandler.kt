package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.NativeStatusCode
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.ShellControlLayout
import io.flutter.plugin.common.BasicMessageChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handler of the control channel `capture3d/visibility_grid/control`: 32 B
 * shell control messages (start with configuration, stop, set marker
 * detection, set recording) from the UI isolate. Each message is answered
 * with a 4 B little-endian `NativeStatusCode` (0 accepted).
 *
 * A start or set marker detection message that turns marker detection on
 * while [markerDetectorAvailable] is false (the detector library is not
 * packaged in this build) is refused with `MARKER_DETECTOR_UNAVAILABLE` and
 * changes nothing, so the app never believes markers run.
 */
internal class GridShellControlHandler(
    private val shell: () -> VisibilityGridIngestShell?,
    private val markerDetectorAvailable: () -> Boolean,
) : BasicMessageChannel.MessageHandler<ByteBuffer> {
    override fun onMessage(message: ByteBuffer?, reply: BasicMessageChannel.Reply<ByteBuffer>) {
        reply.reply(status(handle(message)))
    }

    /** Applies one control message; returns the status code. */
    fun handle(message: ByteBuffer?): NativeStatusCode {
        if (message == null || message.remaining() < ShellControlLayout.BYTES) return NativeStatusCode.CONTRACT_MESSAGE_MALFORMED
        val b = message.order(ByteOrder.LITTLE_ENDIAN)
        val at = b.position()
        if (b.getInt(at) != ShellControlLayout.MAGIC) return NativeStatusCode.CONTRACT_MESSAGE_MALFORMED
        if (b.getShort(at + 4).toInt() != ShellControlLayout.VERSION) return NativeStatusCode.CONTRACT_VERSION_MISMATCH
        // No shell: the plugin is detached from its engine.
        val target = shell() ?: return NativeStatusCode.AR_SESSION_UNSUPPORTED
        val flags = b.get(at + ShellControlLayout.FLAGS).toInt() and 0xff
        val hz = b.getShort(at + ShellControlLayout.MARKER_DETECTION_HZ).toInt() and 0xffff
        val size = b.getFloat(at + ShellControlLayout.MARKER_SIZE_METRES)
        val markersRequested = flags and ShellControlLayout.FLAG_MARKER_DETECTION != 0
        return when (b.get(at + ShellControlLayout.KIND).toInt() and 0xff) {
            ShellControlLayout.KIND_START -> {
                if (markersRequested && !markerDetectorAvailable()) return NativeStatusCode.MARKER_DETECTOR_UNAVAILABLE
                target.start(
                    IngestShellConfig(
                        flags = flags,
                        depthSourcePreference = b.get(at + ShellControlLayout.DEPTH_SOURCE_PREFERENCE).toInt() and 0xff,
                        runMode = b.get(at + ShellControlLayout.RUN_MODE).toInt() and 0xff,
                        markerDetectionHz = hz,
                        markerSizeMetres = size,
                        groupEpoch = b.getInt(at + ShellControlLayout.GROUP_EPOCH),
                    ),
                )
                if (flags and ShellControlLayout.FLAG_RECORDING != 0 && !target.setRecording(true)) {
                    NativeStatusCode.RECORDING_WRITE_FAILED
                } else {
                    NativeStatusCode.OK
                }
            }
            ShellControlLayout.KIND_STOP -> {
                target.setRecording(false)
                target.stop()
                NativeStatusCode.OK
            }
            ShellControlLayout.KIND_SET_MARKER_DETECTION ->
                if (markersRequested && !markerDetectorAvailable()) {
                    NativeStatusCode.MARKER_DETECTOR_UNAVAILABLE
                } else {
                    target.setMarkerDetection(markersRequested, hz, size)
                    NativeStatusCode.OK
                }
            ShellControlLayout.KIND_SET_RECORDING ->
                if (target.setRecording(flags and ShellControlLayout.FLAG_RECORDING != 0)) NativeStatusCode.OK
                else NativeStatusCode.RECORDING_WRITE_FAILED
            else -> NativeStatusCode.CONTRACT_MESSAGE_MALFORMED
        }
    }

    private fun status(code: NativeStatusCode): ByteBuffer {
        val b = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, code.code)
        b.position(4)
        return b
    }
}
