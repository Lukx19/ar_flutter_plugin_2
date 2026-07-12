package com.uhg0.ar_flutter_plugin_2.capture

internal data class CorrelatedRawCapture<Raw, Result>(val raw: Raw, val result: Result)

internal class RawCaptureCorrelator<Raw, Result>(private val closeRaw: (Raw) -> Unit) {
    private val raws = linkedMapOf<Long, Raw>()
    private val results = linkedMapOf<Long, Result>()

    @Synchronized fun onRaw(timestamp: Long, raw: Raw): CorrelatedRawCapture<Raw, Result>? {
        raws.put(timestamp, raw)?.let(closeRaw)
        return take(timestamp)
    }

    @Synchronized fun onResult(timestamp: Long, result: Result): CorrelatedRawCapture<Raw, Result>? {
        results[timestamp] = result
        return take(timestamp)
    }

    @Synchronized fun clear() { raws.values.forEach(closeRaw); raws.clear(); results.clear() }

    private fun take(timestamp: Long): CorrelatedRawCapture<Raw, Result>? {
        val raw = raws[timestamp] ?: return null
        val result = results[timestamp] ?: return null
        raws.remove(timestamp); results.remove(timestamp)
        return CorrelatedRawCapture(raw, result)
    }
}
