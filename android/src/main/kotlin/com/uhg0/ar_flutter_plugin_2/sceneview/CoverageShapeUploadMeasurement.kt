package com.uhg0.ar_flutter_plugin_2.sceneview

/** Fixed native-clock evidence frozen by the existing measurement endpoint. */
internal class CoverageShapeUploadMeasurement {
    @Volatile private var start = 0L
    private var end = 0L
    private var completions = 0L
    private var reuses = 0L
    private var first = 0L
    private var last = 0L
    private var primitives = 0
    private var resource = 0L
    private var selection = 0L
    private var mode = "unavailable"
    private val frames = java.util.concurrent.atomic.AtomicLong()
    private var windowSequence = 0L
    @Synchronized fun begin(now: Long) {
        require(now > 0); start = now; end = 0; completions = 0; reuses = 0
        first = 0; last = 0; primitives = 0; resource = 0; selection = 0; mode = "unavailable"
        if (windowSequence == MAX_WINDOWS) {
            frames.set((windowSequence shl GENERATION_SHIFT) or OVERFLOW); return
        }
        frames.set((++windowSequence shl GENERATION_SHIFT) or OPEN)
    }
    @Synchronized fun freeze(now: () -> Long) {
        if (start > 0 && end == 0L) {
            frames.getAndUpdate { it and OPEN.inv() }
            end = now().also { require(it >= start) }
        }
    }
    /** One CAS scalar; no frame allocation, lock, source borrow or callback. */
    fun frame(now: Long, mounted: Boolean) {
        val generation = (frames.get() ushr GENERATION_SHIFT) and MAX_WINDOWS
        if (now < start) return
        while (true) {
            val before = frames.get()
            if ((before ushr GENERATION_SHIFT) and MAX_WINDOWS != generation) return
            if (before and OPEN == 0L) return
            if (before and MASK == MASK || (before ushr COUNT_BITS) and MASK == MASK) {
                if (frames.compareAndSet(before, before or OVERFLOW)) return
                continue
            }
            if (frames.compareAndSet(before, before + 1 + if (mounted) 1L shl COUNT_BITS else 0)) return
        }
    }
    @Synchronized fun completed(now: Long, token: Long, selectionGeneration: Long,
        shape: Int, primitiveCount: Int, reuse: Boolean) {
        if (start == 0L || end != 0L || now < start || primitiveCount <= 0) return
        if (reuse) { reuses++; return }
        completions++; if (first == 0L) first = now; last = now
        primitives = primitiveCount; resource = token; selection = selectionGeneration
        mode = if (shape == RendererAttributeCodec.BALLS) "directionalBalls" else "planarPatches"
    }
    @Synchronized fun snapshot(): Map<String, Any> = frames.get().let { packed -> mapOf(
        "shapeMeasurementClock" to "androidMonotonic",
        "shapeMeasurementWindowStartNs" to start, "shapeMeasurementWindowEndNs" to end,
        "shapeMeasurementWindowComplete" to (start > 0 && end > 0),
        "shapeMeasurementUploadCompletions" to completions, "shapeMeasurementMetadataReuses" to reuses,
        "shapeMeasurementFirstCompletionNs" to first, "shapeMeasurementLastCompletionNs" to last,
        "shapeMeasurementCompletedPrimitiveCount" to primitives, "shapeMeasurementResourceGeneration" to resource,
        "shapeMeasurementSelectionGeneration" to selection, "shapeMeasurementMode" to mode,
        "shapeMeasurementFrameCount" to (packed and MASK),
        "shapeMeasurementMountedFrameCount" to ((packed ushr COUNT_BITS) and MASK),
        "shapeMeasurementFrameCountComplete" to (start > 0 && end > 0 && packed and OVERFLOW == 0L),
    ) }
    private companion object {
        const val COUNT_BITS = 23
        const val OPEN = 1L shl 46
        const val GENERATION_SHIFT = 47
        const val MAX_WINDOWS = 65535L
        const val MASK = (1L shl COUNT_BITS) - 1
        const val OVERFLOW = Long.MIN_VALUE
    }
}
