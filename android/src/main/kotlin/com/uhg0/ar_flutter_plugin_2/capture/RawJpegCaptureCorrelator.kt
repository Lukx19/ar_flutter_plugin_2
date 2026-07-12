package com.uhg0.ar_flutter_plugin_2.capture

internal data class CorrelatedRawJpegCapture<Raw, Result>(
    val jpeg: PendingStillImagePayload,
    val raw: Raw,
    val result: Result,
)

/** Matches the two occasional still outputs and their Camera2 result by sensor timestamp. */
internal class RawJpegCaptureCorrelator<Raw, Result>(
    private val closeRaw: (Raw) -> Unit,
) {
    private val jpegByTimestamp = linkedMapOf<Long, PendingStillImagePayload>()
    private val rawByTimestamp = linkedMapOf<Long, Raw>()
    private val resultByTimestamp = linkedMapOf<Long, Result>()

    @Synchronized
    fun onJpeg(jpeg: PendingStillImagePayload): CorrelatedRawJpegCapture<Raw, Result>? {
        jpegByTimestamp.putIfAbsent(jpeg.sensorTimestampNs, jpeg)
        return takeIfComplete(jpeg.sensorTimestampNs)
    }

    @Synchronized
    fun onRaw(
        sensorTimestampNs: Long,
        raw: Raw,
    ): CorrelatedRawJpegCapture<Raw, Result>? {
        rawByTimestamp.put(sensorTimestampNs, raw)?.let(closeRaw)
        return takeIfComplete(sensorTimestampNs)
    }

    @Synchronized
    fun onResult(
        sensorTimestampNs: Long,
        result: Result,
    ): CorrelatedRawJpegCapture<Raw, Result>? {
        resultByTimestamp.putIfAbsent(sensorTimestampNs, result)
        return takeIfComplete(sensorTimestampNs)
    }

    @Synchronized
    fun clear() {
        rawByTimestamp.values.forEach(closeRaw)
        rawByTimestamp.clear()
        jpegByTimestamp.clear()
        resultByTimestamp.clear()
    }

    private fun takeIfComplete(
        sensorTimestampNs: Long,
    ): CorrelatedRawJpegCapture<Raw, Result>? {
        val jpeg = jpegByTimestamp[sensorTimestampNs] ?: return null
        val raw = rawByTimestamp[sensorTimestampNs] ?: return null
        val result = resultByTimestamp[sensorTimestampNs] ?: return null
        jpegByTimestamp.remove(sensorTimestampNs)
        rawByTimestamp.remove(sensorTimestampNs)
        resultByTimestamp.remove(sensorTimestampNs)
        return CorrelatedRawJpegCapture(jpeg, raw, result)
    }
}
