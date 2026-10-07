package com.uhg0.ar_flutter_plugin_2.diagnostics

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

import android.os.Build
import android.os.SystemClock
import android.os.Trace

/** Constant labels allow scheduler traces without allocating per-frame records. */
internal object NativeFrameSpan {
    const val PUBLICATION = 0
    const val UPLOAD = 1
    const val HOST_PLANES = 2
    const val OBSERVATION = 3
    const val POSE = 4
    const val AR_PLANES = 5
    const val JPEG_COPY = 6
    const val IMAGE_CLOSE = 7
    fun traceName(stage: Int) = when (stage) {
        PUBLICATION -> "Capture3D.publicationConsume"
        UPLOAD -> "Capture3D.rendererUpload"
        HOST_PLANES -> "Capture3D.hostPlanes"
        OBSERVATION -> "Capture3D.observationIntake"
        POSE -> "Capture3D.poseDispatch"
        AR_PLANES -> "Capture3D.arPlanes"
        JPEG_COPY -> "Capture3D.jpegCopy"
        IMAGE_CLOSE -> "Capture3D.camera2ImageClose"
        else -> error("Unknown frame span")
    }
}

internal interface NativeFrameClock {
    fun monotonicNs(): Long
    fun elapsedRealtimeNs(): Long
}
internal interface NativeFrameTrace {
    fun begin(name: String)
    fun end()
    fun counter(name: String, value: Long)
    fun enabled(): Boolean
}
private object AndroidFrameClock : NativeFrameClock {
    override fun monotonicNs() = System.nanoTime()
    override fun elapsedRealtimeNs() = SystemClock.elapsedRealtimeNanos()
}
private object AndroidFrameTrace : NativeFrameTrace {
    override fun begin(name: String) = Trace.beginSection(name)
    override fun end() = Trace.endSection()
    override fun counter(name: String, value: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.setCounter(name, value)
    }
    override fun enabled() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()
}
private class NativeSpanCounters {
    var count = 0L
    var failures = 0L
    var totalNs = 0L
    var maxNs = 0L
    var over50Ms = 0L
    fun record(duration: Long, failed: Boolean) {
        count++
        if (failed) failures++
        totalNs += duration
        maxNs = maxOf(maxNs, duration)
        if (duration > 50_000_000L) over50Ms++
    }
    fun reset() { count = 0; failures = 0; totalNs = 0; maxNs = 0; over50Ms = 0 }
    fun receipt() = mapOf("count" to count, "failures" to failures, "totalNs" to totalNs,
        "maxNs" to maxNs, "over50Ms" to over50Ms)
}

/**
 * Funded scalar-only owner. No frame/Image/pose references, timing arrays or history.
 * 2 KiB is a portable accounting allowance for bounded scalar state, locks and
 * release callback; actual VM heap size is not certified. It is reserved inside the existing capture envelope.
 */
internal class NativeFrameAttributionOwner private constructor(
    private val clock: NativeFrameClock,
    private val trace: NativeFrameTrace,
    private val releaseFunding: () -> Unit,
) {
    companion object {
        const val MODELED_OWNER_BYTES = 2048L
        private val ownerSequence = java.util.concurrent.atomic.AtomicLong()
        fun create(enabled: Boolean, reserve: (Long) -> Boolean, release: () -> Unit,
            clock: NativeFrameClock = AndroidFrameClock,
            trace: NativeFrameTrace = AndroidFrameTrace,
        ): NativeFrameAttributionOwner? {
            if (!enabled || !reserve(MODELED_OWNER_BYTES)) return null
            return try { NativeFrameAttributionOwner(clock, trace, release) }
            catch (_: OutOfMemoryError) { release(); null }
        }
    }
    val ownerEpoch = ownerSequence.incrementAndGet()
    @Volatile var measurementEpoch = 1L
        private set
    @Volatile var active = true
        private set
    @Volatile private var closing = false
    private val fundingReleased = AtomicBoolean()
    private val state = ReentrantLock()
    private val activeSpans = AtomicLong()
    private val activeProbeBorrows = AtomicLong()
    private val spanAdmissionMisses = AtomicLong()
    private val spanCompletionMisses = AtomicLong()
    private val cameraObservationMisses = AtomicLong()
    private val cameraProbeReadFailures = AtomicLong()
    private val repeatingObservationMisses = AtomicLong()
    private val stillObservationMisses = AtomicLong()
    private val repeatingGapPending = AtomicBoolean()
    private var rejectedSpanCompletions = 0L
    private val probeErrors = AtomicLong()
    private val publication = NativeSpanCounters()
    private val upload = NativeSpanCounters()
    private val hostPlanes = NativeSpanCounters()
    private val observation = NativeSpanCounters()
    private val pose = NativeSpanCounters()
    private val arPlanes = NativeSpanCounters()
    private val jpegCopy = NativeSpanCounters()
    private val imageClose = NativeSpanCounters()
    private var cameraEpoch = 0L
    private var realtimeSensorClock = false
    private var lastSensorNs = 0L
    private var lastArrivalNs = 0L
    private var lastFrameNumber = -1L
    private var repeatingResults = 0L
    private var staleCameraResults = 0L
    private var invalidSensorResults = 0L
    private var interveningFrameNumbers = 0L
    private var observedStillResults = 0L
    private var lastStillFrameNumber = -1L
    private var adjacentRepeatingSensorIntervals = 0L
    private var adjacentRepeatingSensorMaxNs = 0L
    private var adjacentRepeatingSensorGapsOver50Ms = 0L
    private var repeatingPairsCrossingObservedStill = 0L
    private var sensorIntervals = 0L
    private var sensorMaxIntervalNs = 0L
    private var sensorGapsOver50Ms = 0L
    private var arrivalIntervals = 0L
    private var arrivalMaxIntervalNs = 0L
    private var arrivalGapsOver50Ms = 0L
    private var qualifiedAgeSamples = 0L
    private var maxSensorToArrivalNs = 0L
    private var invalidClockComparisons = 0L
    private var exposureSamples = 0L
    private var lastExposureNs = 0L
    private var frameDurationSamples = 0L
    private var lastFrameDurationNs = 0L
    private var bracketMonotonicBeforeNs = 0L
    private var bracketElapsedNs = 0L
    private var bracketMonotonicAfterNs = 0L

    fun openCameraEpoch(realtimeClock: Boolean): Long {
        val epoch = state.withLock {
            if (!active) return 0L
            resetMeasurementsLocked()
            cameraEpoch++
            realtimeSensorClock = realtimeClock
            activeProbeBorrows.incrementAndGet()
            cameraEpoch
        }
        try {
            // Clock reads and kernel trace writes never retain the counter gate.
            val before = clock.monotonicNs()
            val elapsed = clock.elapsedRealtimeNs()
            val after = clock.monotonicNs()
            state.withLock {
                if (active && cameraEpoch == epoch) {
                    bracketMonotonicBeforeNs = before; bracketElapsedNs = elapsed; bracketMonotonicAfterNs = after
                }
            }
            safeCounter("Capture3D.ownerEpoch", ownerEpoch)
            safeCounter("Capture3D.cameraEpoch", epoch)
        } catch (_: Throwable) { probeErrors.incrementAndGet() }
        finally { endProbeBorrow() }
        return epoch
    }
    fun invalidateCameraEpoch(expected: Long) = state.withLock {
        if (expected == cameraEpoch) {
            resetMeasurementsLocked()
            cameraEpoch++; lastSensorNs = 0; lastArrivalNs = 0; lastFrameNumber = -1
        }
    }
    fun stillResult(expectedEpoch: Long, frameNumber: Long) {
        if (!state.tryLock()) { cameraObservationMisses.incrementAndGet(); stillObservationMisses.incrementAndGet(); return }
        var locked = true
        var borrowed = false
        try {
            if (!active || expectedEpoch == 0L || expectedEpoch != cameraEpoch) {
                staleCameraResults++; return
            }
            activeProbeBorrows.incrementAndGet(); borrowed = true
            observedStillResults++
            lastStillFrameNumber = maxOf(lastStillFrameNumber, frameNumber)
            state.unlock(); locked = false
            safeCounter("Capture3D.stillFrameNumber", frameNumber)
        } finally {
            if (locked) state.unlock()
            if (borrowed) endProbeBorrow()
        }
    }
    /** Metadata reads and the arrival clock are supplied only for enabled, live callbacks. */
    fun repeatingResult(expectedEpoch: Long, sensorNs: Long?, frameNumber: Long,
        arrivalElapsedNs: Long, exposureNs: Long?, frameDurationNs: Long?) {
        if (!state.tryLock()) {
            cameraObservationMisses.incrementAndGet(); repeatingObservationMisses.incrementAndGet()
            repeatingGapPending.set(true)
            return
        }
        var locked = true
        var borrowed = false
        try {
        if (!active || expectedEpoch == 0L || expectedEpoch != cameraEpoch) {
            staleCameraResults++; return
        }
        activeProbeBorrows.incrementAndGet(); borrowed = true
        repeatingResults++
        if (repeatingGapPending.getAndSet(false)) {
            lastSensorNs = 0; lastArrivalNs = 0; lastFrameNumber = -1
        }
        if (sensorNs == null || sensorNs <= 0 || frameNumber <= lastFrameNumber) {
            invalidSensorResults++; return
        }
        if (lastSensorNs > 0 && sensorNs > lastSensorNs) {
            val adjacent = sensorNs - lastSensorNs
            adjacentRepeatingSensorIntervals++
            adjacentRepeatingSensorMaxNs = maxOf(adjacentRepeatingSensorMaxNs, adjacent)
            if (adjacent > 50_000_000L) adjacentRepeatingSensorGapsOver50Ms++
            if (lastStillFrameNumber > lastFrameNumber && lastStillFrameNumber < frameNumber) {
                repeatingPairsCrossingObservedStill++
            }
        }
        val consecutive = lastFrameNumber >= 0 && frameNumber == lastFrameNumber + 1
        if (lastFrameNumber >= 0 && frameNumber > lastFrameNumber + 1) {
            interveningFrameNumbers += frameNumber - lastFrameNumber - 1
        }
        if (consecutive && lastSensorNs > 0 && sensorNs > lastSensorNs) {
            val delta = sensorNs - lastSensorNs
            sensorIntervals++; sensorMaxIntervalNs = maxOf(sensorMaxIntervalNs, delta)
            if (delta > 50_000_000L) sensorGapsOver50Ms++
        } else if (consecutive && sensorNs <= lastSensorNs) invalidSensorResults++
        if (lastArrivalNs > 0 && arrivalElapsedNs >= lastArrivalNs) {
            val delta = arrivalElapsedNs - lastArrivalNs
            arrivalIntervals++; arrivalMaxIntervalNs = maxOf(arrivalMaxIntervalNs, delta)
            if (delta > 50_000_000L) arrivalGapsOver50Ms++
        }
        if (realtimeSensorClock && arrivalElapsedNs >= sensorNs) {
            qualifiedAgeSamples++
            maxSensorToArrivalNs = maxOf(maxSensorToArrivalNs, arrivalElapsedNs - sensorNs)
        } else if (realtimeSensorClock) invalidClockComparisons++
        if (exposureNs != null && exposureNs > 0) { exposureSamples++; lastExposureNs = exposureNs }
        if (frameDurationNs != null && frameDurationNs > 0) {
            frameDurationSamples++; lastFrameDurationNs = frameDurationNs
        }
        lastSensorNs = sensorNs; lastArrivalNs = arrivalElapsedNs; lastFrameNumber = frameNumber
        state.unlock(); locked = false
        safeCounter("Capture3D.repeatingSensorNs", sensorNs)
        safeCounter("Capture3D.repeatingArrivalElapsedNs", arrivalElapsedNs)
        safeCounter("Capture3D.repeatingFrameNumber", frameNumber)
        if (exposureNs != null) safeCounter("Capture3D.repeatingExposureNs", exposureNs)
        if (frameDurationNs != null) safeCounter("Capture3D.repeatingFrameDurationNs", frameDurationNs)
        } finally {
            if (locked) state.unlock()
            if (borrowed) endProbeBorrow()
        }
    }
    fun recordCameraProbeFailure() {
        probeErrors.incrementAndGet(); cameraObservationMisses.incrementAndGet()
        cameraProbeReadFailures.incrementAndGet(); repeatingGapPending.set(true)
    }
    fun arrivalElapsedNs() = clock.elapsedRealtimeNs()
    fun beginSpan(stage: Int, epoch: Long): Long {
        if (!state.tryLock()) { spanAdmissionMisses.incrementAndGet(); return 0L }
        try {
            if (!active || epoch != measurementEpoch) return 0L
            activeProbeBorrows.incrementAndGet(); activeSpans.incrementAndGet()
        } finally { state.unlock() }
        val start = try { clock.monotonicNs() }
            catch (_: Throwable) { probeErrors.incrementAndGet(); endSpanBorrow(); return 0L }
        if (start <= 0L) { probeErrors.incrementAndGet(); endSpanBorrow(); return 0L }
        try { trace.begin(NativeFrameSpan.traceName(stage)) }
        catch (_: Throwable) { probeErrors.incrementAndGet(); endSpanBorrow(); return 0L }
        return start
    }
    fun endSpan(stage: Int, start: Long, epoch: Long, failed: Boolean) {
        if (start == 0L) return
        try {
            val end = try { clock.monotonicNs() } catch (_: Throwable) { probeErrors.incrementAndGet(); null }
            // Pair the trace on its calling thread even when reset/close or contention prevents a sample.
            try { trace.end() } catch (_: Throwable) { probeErrors.incrementAndGet() }
            if (!state.tryLock()) spanCompletionMisses.incrementAndGet()
            else try {
                if (end != null && active && epoch == measurementEpoch && end >= start) counters(stage).record(end - start, failed)
                else rejectedSpanCompletions++
            } catch (_: Throwable) { probeErrors.incrementAndGet() }
            finally { state.unlock() }
        } finally { endSpanBorrow() }
    }
    private fun endSpanBorrow() {
        check(activeSpans.decrementAndGet() >= 0L)
        endProbeBorrow()
    }
    private fun endProbeBorrow() {
        check(activeProbeBorrows.decrementAndGet() >= 0L)
        releaseIfClosed()
    }
    private fun releaseIfClosed() {
        if (closing && activeProbeBorrows.get() == 0L && fundingReleased.compareAndSet(false, true)) releaseFunding()
    }
    fun resetMeasurements() = state.withLock { resetMeasurementsLocked() }
    private fun resetMeasurementsLocked() {
        measurementEpoch++
        publication.reset(); upload.reset(); hostPlanes.reset(); observation.reset()
        pose.reset(); arPlanes.reset(); jpegCopy.reset(); imageClose.reset()
        repeatingResults = 0; staleCameraResults = 0; invalidSensorResults = 0
        interveningFrameNumbers = 0; sensorIntervals = 0; sensorMaxIntervalNs = 0
        observedStillResults = 0; lastStillFrameNumber = -1
        adjacentRepeatingSensorIntervals = 0; adjacentRepeatingSensorMaxNs = 0
        adjacentRepeatingSensorGapsOver50Ms = 0; repeatingPairsCrossingObservedStill = 0
        sensorGapsOver50Ms = 0; arrivalIntervals = 0; arrivalMaxIntervalNs = 0
        arrivalGapsOver50Ms = 0; qualifiedAgeSamples = 0; maxSensorToArrivalNs = 0
        invalidClockComparisons = 0; exposureSamples = 0; frameDurationSamples = 0
        lastSensorNs = 0; lastArrivalNs = 0; lastFrameNumber = -1
    }
    fun close() {
        state.withLock {
            if (closing) return
            closing = true; active = false; measurementEpoch++; cameraEpoch++
        }
        releaseIfClosed()
    }
    private fun counters(stage: Int) = when(stage) {
        NativeFrameSpan.PUBLICATION -> publication; NativeFrameSpan.UPLOAD -> upload
        NativeFrameSpan.HOST_PLANES -> hostPlanes; NativeFrameSpan.OBSERVATION -> observation
        NativeFrameSpan.POSE -> pose; NativeFrameSpan.AR_PLANES -> arPlanes
        NativeFrameSpan.JPEG_COPY -> jpegCopy
        NativeFrameSpan.IMAGE_CLOSE -> imageClose
        else -> error("Unknown frame span")
    }
    private fun safeCounter(name: String, value: Long) {
        try { trace.counter(name, value) } catch (_: Throwable) { probeErrors.incrementAndGet() }
    }
    fun receipt(): Map<String, Any?> {
        val traceEnabled = try { trace.enabled() } catch (_: Throwable) { probeErrors.incrementAndGet(); false }
        return state.withLock { mapOf(
        "effective" to active, "ownerEpoch" to ownerEpoch, "measurementEpoch" to measurementEpoch,
        "cameraEpoch" to cameraEpoch, "modeledOwnerBytes" to if (fundingReleased.get()) 0L else MODELED_OWNER_BYTES,
        "activeSpans" to activeSpans.get(), "activeProbeBorrows" to activeProbeBorrows.get(),
        "spanAdmissionMissesLifetime" to spanAdmissionMisses.get(),
        "spanCompletionMissesLifetime" to spanCompletionMisses.get(),
        "cameraObservationMissesLifetime" to cameraObservationMisses.get(),
        "cameraProbeReadFailuresLifetime" to cameraProbeReadFailures.get(),
        "repeatingObservationMissesLifetime" to repeatingObservationMisses.get(),
        "stillObservationMissesLifetime" to stillObservationMisses.get(),
        "samplingCompleteness" to "window misses require lifetime before/after delta; a missed repeating observation resets pair continuity",
        "probeAdmission" to "hot state try-admission; skipped observations explicit; clocks and trace outside counter gate", "rejectedSpanCompletions" to rejectedSpanCompletions,
        "probeErrors" to probeErrors.get(), "sensorClock" to if (realtimeSensorClock) "elapsedRealtime" else "unqualified",
        "arrivalClock" to "elapsedRealtime", "spanClock" to "monotonic",
        "clockBracketMonotonicBeforeNs" to bracketMonotonicBeforeNs,
        "clockBracketElapsedNs" to bracketElapsedNs, "clockBracketMonotonicAfterNs" to bracketMonotonicAfterNs,
        "repeatingResults" to repeatingResults, "staleCameraResults" to staleCameraResults,
        "invalidSensorResults" to invalidSensorResults, "interveningFrameNumbers" to interveningFrameNumbers,
        "observedStillResults" to observedStillResults,
        "adjacentRepeatingSensorIntervals" to adjacentRepeatingSensorIntervals,
        "adjacentRepeatingSensorMaxNs" to adjacentRepeatingSensorMaxNs,
        "adjacentRepeatingSensorGapsOver50Ms" to adjacentRepeatingSensorGapsOver50Ms,
        "repeatingPairsCrossingObservedStill" to repeatingPairsCrossingObservedStill,
        "sensorIntervalQualification" to "strict consecutive frame numbers; excludes interruptions",
        "adjacentIntervalQualification" to "includes intervening still/unobserved frames; attribution unknown",
        "sensorIntervals" to sensorIntervals, "sensorMaxIntervalNs" to sensorMaxIntervalNs,
        "sensorGapsOver50Ms" to sensorGapsOver50Ms,
        "arrivalIntervals" to arrivalIntervals, "arrivalMaxIntervalNs" to arrivalMaxIntervalNs,
        "arrivalGapsOver50Ms" to arrivalGapsOver50Ms,
        "qualifiedSensorToArrivalSamples" to qualifiedAgeSamples,
        "maxSensorToArrivalNs" to if (qualifiedAgeSamples > 0) maxSensorToArrivalNs else null,
        "invalidClockComparisons" to invalidClockComparisons,
        "exposureSamples" to exposureSamples, "lastExposureNs" to if (exposureSamples > 0) lastExposureNs else null,
        "frameDurationSamples" to frameDurationSamples,
        "lastFrameDurationNs" to if (frameDurationSamples > 0) lastFrameDurationNs else null,
        "publicationConsume" to publication.receipt(), "rendererUpload" to upload.receipt(),
        "hostPlanes" to hostPlanes.receipt(), "observationIntake" to observation.receipt(),
        "poseDispatch" to pose.receipt(), "arPlanes" to arPlanes.receipt(), "jpegCopy" to jpegCopy.receipt(), "camera2ImageClose" to imageClose.receipt(),
        "jpegCopyScope" to "legacy hardware JPEG/RAW+JPEG callback allocation and copy only; Image close is a separate owner; direct store copy unavailable",
        "rendererUploadScope" to "optional renderer frame owner wall time; GPU completion unavailable",
        "sessionUpdate" to "unavailable: SceneView owns upstream update",
        "upstreamArCore" to "unavailable", "presentation" to "unavailable",
        "historyScope" to "external Android trace only; no internal records",
        "traceLoss" to "unavailable; collector must qualify trace loss",
        "traceEnabledAtReceipt" to traceEnabled, "diagnosticOnly" to true,
    ) }
    }
}

/** Inline direct delegation means the null/disabled path reads no clock or trace. */
internal inline fun <T> NativeFrameAttributionOwner?.measureNativeFrame(
    stage: Int, block: () -> T,
): T {
    val owner = this ?: return block()
    if (!owner.active) return block()
    val epoch = owner.measurementEpoch
    val start = owner.beginSpan(stage, epoch)
    var failed = false
    try { return block() }
    catch (error: Throwable) { failed = true; throw error }
    finally { owner.endSpan(stage, start, epoch, failed) }
}
