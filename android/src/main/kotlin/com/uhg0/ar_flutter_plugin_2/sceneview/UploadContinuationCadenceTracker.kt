package com.uhg0.ar_flutter_plugin_2.sceneview

import java.util.Arrays

/** Fixed recent continuation intervals; no measurement tail or timing-window arrays. */
internal class UploadContinuationCadenceTracker {
    companion object {
        private const val CAPACITY = 64
        // Primitive payload only: ring, previous timestamp, two indices, previous flag.
        // This excludes VM-dependent object/array headers and is not total heap size.
        const val MODELED_PRIMITIVE_STORAGE_BYTES = CAPACITY * Long.SIZE_BYTES +
            Long.SIZE_BYTES + 2 * Int.SIZE_BYTES + 1
    }

    private val intervalsNs = LongArray(CAPACITY)
    private var previousNs = 0L
    private var sampleCount = 0
    private var writeIndex = 0
    private var hasPrevious = false

    @Synchronized fun record(timestampNs: Long) {
        if (hasPrevious && timestampNs <= previousNs) return
        if (hasPrevious) {
            intervalsNs[writeIndex] = timestampNs - previousNs
            writeIndex = (writeIndex + 1) % CAPACITY
            sampleCount = minOf(sampleCount + 1, CAPACITY)
        }
        previousNs = timestampNs
        hasPrevious = true
    }

    /** Explicit on-demand serialization, never called from a frame callback. */
    @Synchronized fun snapshot(): Map<String, Any> {
        if (sampleCount == 0) return mapOf("sampleCount" to 0,
            "p95FrameIntervalMs" to 0.0, "maxFrameIntervalMs" to 0.0)
        val sorted = intervalsNs.copyOf(sampleCount)
        Arrays.sort(sorted)
        val p95Index = ((sampleCount * 95 + 99) / 100 - 1).coerceIn(0, sorted.lastIndex)
        return mapOf("sampleCount" to sampleCount,
            "p95FrameIntervalMs" to sorted[p95Index] / 1e6,
            "maxFrameIntervalMs" to sorted.last() / 1e6)
    }
}
