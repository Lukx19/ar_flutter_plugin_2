package com.uhg0.ar_flutter_plugin_2.capture

/** Debug-only monotonic timing: 32 samples x 8 Longs = 2,048 scalar bytes. */
internal class ProcessTelemetryTimingLedger(private val clockNs: () -> Long = System::nanoTime) {
    companion object {
        const val Capacity = 32
        const val ScalarBytes = Capacity * 8 * Long.SIZE_BYTES
    }
    enum class Stage(val wireName: String) { PSS("pss"), FD("fileDescriptors"), JAVA_THREADS("javaThreads") }
    private val rows = LongArray(Capacity * 8)
    private var epoch = 0L
    private var count = 0
    private var offered = 0L
    private var dropped = 0L
    private var staleWrites = 0L
    private var armed = false
    private var closed = false
    private var resetAtNs = 0L
    private var resetWhileRefreshPending = false

    @Synchronized fun reset(refreshPending: Boolean = false) {
        if (closed) return
        epoch++
        resetWhileRefreshPending = refreshPending
        count = 0
        offered = 0L
        dropped = 0L
        staleWrites = 0L
        rows.fill(0L)
        resetAtNs = clockNs()
        armed = true
    }

    @Synchronized fun beginSample(): Long {
        if (!armed || closed) return -1L
        val ordinal = ++offered
        if (count == Capacity) { dropped++; return -1L }
        val slot = count++
        rows[slot * 8] = ordinal
        return epoch * Capacity + slot
    }

    @Synchronized fun start(token: Long, stage: Stage) {
        val base = base(token)
        if (base < 0) return
        rows[base + 1 + stage.ordinal * 2] = clockNs()
        setStatus(base, stage, 1L)
    }

    @Synchronized fun finish(token: Long, stage: Stage, succeeded: Boolean) {
        val base = base(token)
        if (base < 0) return
        rows[base + 2 + stage.ordinal * 2] = clockNs()
        setStatus(base, stage, if (succeeded) 2L else 3L)
    }

    private fun base(token: Long): Int {
        if (token < 0L || closed) return -1
        if (token / Capacity != epoch) { staleWrites++; return -1 }
        val slot = (token % Capacity).toInt()
        if (slot >= count) { staleWrites++; return -1 }
        return slot * 8
    }

    private fun setStatus(base: Int, stage: Stage, value: Long) {
        val shift = stage.ordinal * 2
        rows[base + 7] = (rows[base + 7] and (3L shl shift).inv()) or (value shl shift)
    }

    /** Detached maps are built only by the explicit post-measurement request. */
    @Synchronized fun snapshot(): Map<String, Any?> = mapOf(
        "enabled" to true, "clock" to "androidMonotonic", "epoch" to epoch,
        "resetAtNs" to resetAtNs, "snapshotAtNs" to clockNs(),
        "resetWhileRefreshPending" to resetWhileRefreshPending,
        "capacity" to Capacity, "scalarBytes" to ScalarBytes,
        "offeredSamples" to offered, "droppedSamples" to dropped,
        "staleStageWrites" to staleWrites, "closed" to closed,
        "records" to List(count) { slot ->
            val base = slot * 8
            mapOf(
                "sample" to rows[base],
                "stages" to Stage.entries.map { stage ->
                    val status = (rows[base + 7] shr (stage.ordinal * 2)) and 3L
                    val start = rows[base + 1 + stage.ordinal * 2]
                    val end = rows[base + 2 + stage.ordinal * 2]
                    mapOf(
                        "stage" to stage.wireName,
                        "status" to when (status) { 0L -> "notStarted"; 1L -> "pending"; 2L -> "completed"; else -> "failed" },
                        "startNs" to if (status == 0L) null else start,
                        "endNs" to if (status < 2L) null else end,
                        "clockQualified" to (status >= 2L && end >= start),
                    )
                },
            )
        },
    )

    @Synchronized fun close() { closed = true; armed = false }
}

/** Reused stage readers preserve the exact production calls and failure behavior. */
internal class ProcessTelemetrySampler(
    private val pss: () -> Long?,
    private val fileDescriptors: () -> Int?,
    private val javaThreads: () -> Int?,
    private val timing: ProcessTelemetryTimingLedger?,
) {
    fun sample(): ProcessTelemetryValues {
        val token = timing?.beginSample() ?: -1L
        return ProcessTelemetryValues(
            measure(token, ProcessTelemetryTimingLedger.Stage.PSS, pss),
            measure(token, ProcessTelemetryTimingLedger.Stage.FD, fileDescriptors),
            measure(token, ProcessTelemetryTimingLedger.Stage.JAVA_THREADS, javaThreads),
        )
    }

    private fun <T> measure(token: Long, stage: ProcessTelemetryTimingLedger.Stage, read: () -> T): T {
        timing?.start(token, stage)
        var succeeded = false
        try { return read().also { succeeded = true } }
        finally { timing?.finish(token, stage, succeeded) }
    }
}
