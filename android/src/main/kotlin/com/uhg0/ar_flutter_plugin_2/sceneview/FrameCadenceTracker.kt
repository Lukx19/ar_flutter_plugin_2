package com.uhg0.ar_flutter_plugin_2.sceneview

import java.util.Arrays

/**
 * Keeps bounded cadence history without allocating on the frame callback.
 *
 * The timestamps supplied to [record] are only compared with one another. A
 * measurement window is marked with the ordinal of the last complete interval
 * so camera timestamps and renderer timestamps never need to share a clock.
 */
internal class FrameCadenceTracker(
    private val maximumSamples: Int = 600,
    private val clockNs: () -> Long = System::nanoTime,
    debugGapTimingEnabled: Boolean = false,
) {
    private companion object {
        // The tail retains enough samples to calculate an exact p99 for a
        // 60-second window at up to 120 Hz without retaining the whole window.
        private const val MEASUREMENT_TAIL_CAPACITY = 128
    }

    init {
        require(maximumSamples > 0) { "maximumSamples must be positive" }
    }

    private val intervalNs = LongArray(maximumSamples)
    private val intervalOrdinals = LongArray(maximumSamples)
    private var ringStart = 0
    private var ringSize = 0
    private var previousFrameNs = 0L
    private var hasPreviousFrame = false
    private var intervalOrdinal = 0L
    private var measurementWindowActive = false
    private var measurementFirstEligibleOrdinal = 0L
    private val measurementTailNs = LongArray(MEASUREMENT_TAIL_CAPACITY)
    private var measurementSampleCount = 0
    private var measurementTailSize = 0
    private var measurementMaximumNs = 0L
    private var measurementGapsOver100Ms = 0
    private var lastArrivalNs = 0L
    private var hasAdvancingArrival = false
    // Debug reservation only: first 16 gaps x 4 Longs = 512 scalar bytes.
    private val gapTiming = if (debugGapTimingEnabled) LongArray(16 * 4) else null
    private var gapEpoch = 0L
    private var gapCount = 0
    private var gapObserved = 0L
    private var gapWindowStartNs = 0L
    private var gapWindowEndNs = 0L
    private var gapEnabledForWindow = false
    private var gapCapturing = false


    /** Records one frame interval; this path does not allocate. */
    @Synchronized fun record(frameTimeNs: Long) {
        recordInternal(frameTimeNs, frameTimeNs)
    }

    /** Records an AR timestamp with the local callback-arrival clock. */
    @Synchronized fun record(frameTimeNs: Long, arrivalTimeNs: Long) {
        recordInternal(frameTimeNs, arrivalTimeNs)
    }

    private fun recordInternal(frameTimeNs: Long, arrivalTimeNs: Long) {
        if (hasPreviousFrame && frameTimeNs <= previousFrameNs) return
        if (!hasPreviousFrame) {
            previousFrameNs = frameTimeNs
            hasPreviousFrame = true
            lastArrivalNs = arrivalTimeNs
            hasAdvancingArrival = true
            return
        }

        val previousArrival = lastArrivalNs
        val interval = frameTimeNs - previousFrameNs
        previousFrameNs = frameTimeNs
        lastArrivalNs = arrivalTimeNs
        hasAdvancingArrival = true
        val ordinal = ++intervalOrdinal
        val writeIndex: Int
        if (ringSize < maximumSamples) {
            writeIndex = (ringStart + ringSize) % maximumSamples
            ringSize++
        } else {
            writeIndex = ringStart
            ringStart = (ringStart + 1) % maximumSamples
        }
        intervalNs[writeIndex] = interval
        intervalOrdinals[writeIndex] = ordinal
        if (measurementWindowActive && ordinal >= measurementFirstEligibleOrdinal) {
            recordMeasurementInterval(interval)
            if (gapCapturing && interval > 100_000_000L) {
                gapObserved++
                if (gapCount < 16) {
                    val base = gapCount++ * 4
                    val target = checkNotNull(gapTiming)
                    target[base] = ordinal
                    target[base + 1] = interval
                    target[base + 2] = previousArrival
                    target[base + 3] = arrivalTimeNs
                }
            }
        }
    }

    /**
     * Starts a debug measurement window at a complete interval boundary.
     *
     * When a frame is already in progress, the first interval after this call
     * crosses the boundary and is skipped; subsequent complete intervals are
     * included by [snapshot]. A window started before the first frame includes
     * the first pair that forms a complete interval.
     */
    @Synchronized fun beginMeasurementWindow(captureDiagnosticTiming: Boolean = false) {
        gapEpoch++
        gapCount = 0
        gapObserved = 0L
        gapWindowEndNs = 0L
        gapEnabledForWindow = captureDiagnosticTiming && gapTiming != null
        gapCapturing = gapEnabledForWindow
        gapWindowStartNs = if (gapEnabledForWindow) clockNs() else 0L
        // Before the first frame there is no boundary-crossing interval: the
        // first pair of frames forms the first complete interval in the window.
        measurementWindowActive = true
        measurementFirstEligibleOrdinal =
            intervalOrdinal + (if (hasPreviousFrame) 2L else 1L)
        measurementSampleCount = 0
        measurementTailSize = 0
        measurementMaximumNs = 0L
        measurementGapsOver100Ms = 0
    }

    /** Freeze at the same endpoint as the cadence receipt; teardown cannot add gaps. */
    @Synchronized fun freezeDiagnosticTiming() {
        if (!gapCapturing) return
        gapWindowEndNs = clockNs()
        gapCapturing = false
    }

    /** Explicit post-measurement serialization, never called from record(). */
    @Synchronized fun diagnosticTimingSnapshot(): Map<String, Any?> = mapOf(
        "enabled" to gapEnabledForWindow,
        "clock" to "androidMonotonic",
        "epoch" to gapEpoch,
        "windowStartNs" to if (gapEnabledForWindow) gapWindowStartNs else null,
        "windowEndNs" to if (gapEnabledForWindow && !gapCapturing) gapWindowEndNs else null,
        "frozen" to (gapEnabledForWindow && !gapCapturing),
        "capacity" to 16,
        "scalarBytes" to if (gapTiming == null) 0 else 512,
        "observedGaps" to gapObserved,
        "droppedGaps" to gapObserved - gapCount,
        "records" to List(gapCount) { slot ->
            val base = slot * 4
            val source = checkNotNull(gapTiming)
            mapOf(
                "intervalOrdinal" to source[base],
                // Camera sensor interval determines its gate; only callback arrivals
                // share the telemetry clock. Never subtract the two clock domains.
                "sourceFrameIntervalNs" to source[base + 1],
                "arrivalStartNs" to source[base + 2],
                "arrivalEndNs" to source[base + 3],
                "callbackArrivalIntervalNs" to source[base + 3] - source[base + 2],
                "arrivalClockQualified" to (source[base + 3] >= source[base + 2]),
            )
        },
    )

    @Synchronized fun snapshot(): Map<String, Any> {
        val sorted = sortedIntervals()
        if (sorted.isEmpty()) {
            val empty = emptySnapshot()
            return if (measurementWindowActive) {
                empty + measurementWindowSnapshot()
            } else {
                empty
            }
        }

        Arrays.sort(sorted)
        val middle = sorted.size / 2
        val medianNs = if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle].toDouble()
        }
        var gapsOver50Ms = 0
        var gapsOver100Ms = 0
        for (interval in sorted) {
            if (interval > 50_000_000L) gapsOver50Ms++
            if (interval > 100_000_000L) gapsOver100Ms++
        }
        val snapshot = mapOf(
            "sampleCount" to sorted.size,
            "medianFrameIntervalMs" to medianNs / 1_000_000.0,
            "medianFps" to 1_000_000_000.0 / medianNs,
            "p95FrameIntervalMs" to sorted[percentileIndex(sorted.size, 95)] / 1_000_000.0,
            "p99FrameIntervalMs" to sorted[percentileIndex(sorted.size, 99)] / 1_000_000.0,
            "maxFrameIntervalMs" to sorted[sorted.lastIndex] / 1_000_000.0,
            "frameGapsOver50Ms" to gapsOver50Ms,
            "frameGapsOver100Ms" to gapsOver100Ms,
        )
        return if (measurementWindowActive) {
            snapshot + measurementWindowSnapshot()
        } else {
            snapshot
        }
    }

    /** Cheap recent-frame guard for background depth work on the AR callback. */
    @Synchronized fun healthyForDepthIntake(): Boolean {
        if (ringSize < 15) return true
        var longGaps = 0
        val first = (ringSize - 60).coerceAtLeast(0)
        for (logicalIndex in ringSize - 1 downTo first) {
            val interval = intervalNs[ringIndex(logicalIndex)]
            if (interval > 100_000_000L) return false
            if (interval > 50_000_000L) longGaps++
        }
        return longGaps <= 3
    }

    private fun sortedIntervals(): LongArray {
        if (!measurementWindowActive) {
            val result = LongArray(ringSize)
            for (logicalIndex in 0 until ringSize) {
                result[logicalIndex] = intervalNs[ringIndex(logicalIndex)]
            }
            return result
        }

        var selectedCount = 0
        for (logicalIndex in 0 until ringSize) {
            if (intervalOrdinals[ringIndex(logicalIndex)] >= measurementFirstEligibleOrdinal) {
                selectedCount++
            }
        }
        val result = LongArray(selectedCount)
        var resultIndex = 0
        for (logicalIndex in 0 until ringSize) {
            val index = ringIndex(logicalIndex)
            if (intervalOrdinals[index] >= measurementFirstEligibleOrdinal) {
                result[resultIndex++] = intervalNs[index]
            }
        }
        return result
    }

    private fun recordMeasurementInterval(interval: Long) {
        measurementSampleCount++
        if (interval > measurementMaximumNs) measurementMaximumNs = interval
        if (interval > 100_000_000L) measurementGapsOver100Ms++

        if (measurementTailSize < MEASUREMENT_TAIL_CAPACITY) {
            measurementTailNs[measurementTailSize++] = interval
            return
        }

        var smallestIndex = 0
        var smallest = measurementTailNs[0]
        for (index in 1 until MEASUREMENT_TAIL_CAPACITY) {
            val candidate = measurementTailNs[index]
            if (candidate < smallest) {
                smallest = candidate
                smallestIndex = index
            }
        }
        if (interval > smallest) measurementTailNs[smallestIndex] = interval
    }

    private fun measurementWindowSnapshot(): Map<String, Any> {
        val p99 = measurementP99Ns()
        return mapOf(
            "measurementWindowSampleCount" to measurementSampleCount,
            "measurementWindowP99FrameIntervalMs" to p99.intervalNs / 1_000_000.0,
            "measurementWindowP99Complete" to p99.complete,
            "measurementWindowMaxFrameIntervalMs" to measurementMaximumNs / 1_000_000.0,
            "measurementWindowFrameGapsOver100Ms" to measurementGapsOver100Ms,
            "measurementWindowLastArrivalAgeMs" to lastArrivalAgeMs(),
            "measurementWindowHasAdvancingFrame" to hasAdvancingArrival,
        )
    }

    private fun lastArrivalAgeMs(): Double {
        if (!hasAdvancingArrival) return 0.0
        return (clockNs() - lastArrivalNs).coerceAtLeast(0L) / 1_000_000.0
    }

    private fun measurementP99Ns(): MeasurementP99 {
        if (measurementSampleCount == 0) return MeasurementP99(0L, true)
        val sortedTail = measurementTailNs.copyOf(measurementTailSize)
        Arrays.sort(sortedTail)
        val rank = ((measurementSampleCount.toLong() * 99L + 99L) / 100L - 1L).toInt()
        val tailStart = measurementSampleCount - measurementTailSize
        if (rank < tailStart) return MeasurementP99(0L, false)
        return MeasurementP99(sortedTail[rank - tailStart], true)
    }

    private data class MeasurementP99(
        val intervalNs: Long,
        val complete: Boolean,
    )

    private fun ringIndex(logicalIndex: Int): Int =
        (ringStart + logicalIndex) % maximumSamples

    private fun emptySnapshot(): Map<String, Any> = mapOf(
        "sampleCount" to 0,
        "medianFrameIntervalMs" to 0.0,
        "medianFps" to 0.0,
        "p95FrameIntervalMs" to 0.0,
        "p99FrameIntervalMs" to 0.0,
        "maxFrameIntervalMs" to 0.0,
        "frameGapsOver50Ms" to 0,
        "frameGapsOver100Ms" to 0,
    )

    private fun percentileIndex(size: Int, percentile: Int): Int =
        ((size * percentile + 99) / 100 - 1).coerceIn(0, size - 1)
}
