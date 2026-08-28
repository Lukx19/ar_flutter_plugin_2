package com.uhg0.ar_flutter_plugin_2.capture

internal data class CorrelatedRawJpegCapture<Jpeg, Raw, Result>(
    val jpeg: Jpeg,
    val raw: Raw,
    val result: Result,
)

/** Matches the two occasional still outputs and their Camera2 result by sensor timestamp. */
internal class RawJpegCaptureCorrelator<Jpeg, Raw, Result>(
    private val jpegTimestamp: (Jpeg) -> Long,
    private val closeJpeg: (Jpeg) -> Unit,
    private val closeRaw: (Raw) -> Unit,
) {
    private val jpegByTimestamp = linkedMapOf<Long, Jpeg>()
    private val rawByTimestamp = linkedMapOf<Long, Raw>()
    private val resultByTimestamp = linkedMapOf<Long, Result>()

    @Synchronized
    fun onJpeg(jpeg: Jpeg): CorrelatedRawJpegCapture<Jpeg, Raw, Result>? {
        val timestamp = jpegTimestamp(jpeg)
        jpegByTimestamp.put(timestamp, jpeg)?.let(closeJpeg)
        return takeIfComplete(timestamp)
    }

    @Synchronized
    fun onRaw(
        sensorTimestampNs: Long,
        raw: Raw,
    ): CorrelatedRawJpegCapture<Jpeg, Raw, Result>? {
        rawByTimestamp.put(sensorTimestampNs, raw)?.let(closeRaw)
        return takeIfComplete(sensorTimestampNs)
    }

    @Synchronized
    fun onResult(
        sensorTimestampNs: Long,
        result: Result,
    ): CorrelatedRawJpegCapture<Jpeg, Raw, Result>? {
        resultByTimestamp.putIfAbsent(sensorTimestampNs, result)
        return takeIfComplete(sensorTimestampNs)
    }

    @Synchronized
    fun clear() {
        jpegByTimestamp.values.forEach(closeJpeg)
        rawByTimestamp.values.forEach(closeRaw)
        rawByTimestamp.clear()
        jpegByTimestamp.clear()
        resultByTimestamp.clear()
    }

    private fun takeIfComplete(
        sensorTimestampNs: Long,
    ): CorrelatedRawJpegCapture<Jpeg, Raw, Result>? {
        val jpeg = jpegByTimestamp[sensorTimestampNs] ?: return null
        val raw = rawByTimestamp[sensorTimestampNs] ?: return null
        val result = resultByTimestamp[sensorTimestampNs] ?: return null
        jpegByTimestamp.remove(sensorTimestampNs)
        rawByTimestamp.remove(sensorTimestampNs)
        resultByTimestamp.remove(sensorTimestampNs)
        return CorrelatedRawJpegCapture(jpeg, raw, result)
    }
}
