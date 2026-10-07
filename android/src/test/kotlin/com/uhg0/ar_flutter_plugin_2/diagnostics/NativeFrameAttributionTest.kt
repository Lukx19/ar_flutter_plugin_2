package com.uhg0.ar_flutter_plugin_2.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

class NativeFrameAttributionTest {
    private class Clock : NativeFrameClock {
        var now = 1_000_000L
        var calls = 0
        override fun monotonicNs(): Long { calls++; return now }
        override fun elapsedRealtimeNs(): Long { calls++; return now + 100_000L }
    }
    private class Traces : NativeFrameTrace {
        var begins = 0; var ends = 0; var counters = 0
        override fun begin(name: String) { begins++ }
        override fun end() { ends++ }
        override fun counter(name: String, value: Long) { counters++ }
        override fun enabled() = true
    }
    private fun owner(clock: Clock = Clock(), trace: Traces = Traces(), release: () -> Unit = {}) =
        NativeFrameAttributionOwner.create(true, { it == 2048L }, release, clock, trace)!!

    @Test fun `disabled and unfunded probes never read clocks or delegate twice`() {
        val clock = Clock(); val trace = Traces(); var reservations = 0; var calls = 0
        val disabled = NativeFrameAttributionOwner.create(false, { reservations++; true }, {}, clock, trace)
        assertNull(disabled)
        assertEquals(7, disabled.measureNativeFrame(NativeFrameSpan.POSE) { calls++; 7 })
        val unfunded = NativeFrameAttributionOwner.create(true, { false }, {}, clock, trace)
        assertNull(unfunded)
        val jpegBytes = byteArrayOf(1, 2, 3)
        assertSame(jpegBytes, disabled.measureNativeFrame(NativeFrameSpan.JPEG_COPY) { calls++; jpegBytes })
        assertEquals(2, calls); assertEquals(0, reservations)
        assertEquals(0, clock.calls); assertEquals(0, trace.begins)
    }
    @Test fun `regular sensor cadence and late arrivals remain separate observations`() {
        val owner = owner(); val epoch = owner.openCameraEpoch(true)
        owner.repeatingResult(epoch, 1_000_000_000, 10, 1_010_000_000, 8_000_000, 33_000_000)
        owner.repeatingResult(epoch, 1_033_000_000, 11, 1_310_000_000, 8_000_000, 33_000_000)
        val receipt = owner.receipt()
        assertEquals(33_000_000L, receipt["sensorMaxIntervalNs"])
        assertEquals(300_000_000L, receipt["arrivalMaxIntervalNs"])
        assertEquals(0L, receipt["sensorGapsOver50Ms"])
        assertEquals(1L, receipt["arrivalGapsOver50Ms"])
        assertEquals(277_000_000L, receipt["maxSensorToArrivalNs"])
        owner.close()
    }
    @Test fun `unqualified sensor clock cannot fabricate cross clock age`() {
        val owner = owner(); val epoch = owner.openCameraEpoch(false)
        owner.repeatingResult(epoch, 7_000_000_000, 1, 1_000_000_000, null, null)
        val receipt = owner.receipt()
        assertEquals("unqualified", receipt["sensorClock"])
        assertNull(receipt["maxSensorToArrivalNs"])
        assertNull(receipt["lastExposureNs"]); assertNull(receipt["lastFrameDurationNs"])
        assertEquals(0L, receipt["qualifiedSensorToArrivalSamples"])
        owner.close()
    }
    @Test fun `missing callback frames cannot masquerade as a sensor stall`() {
        val owner = owner(); val epoch = owner.openCameraEpoch(true)
        owner.repeatingResult(epoch, 1_000_000_000, 1, 1_010_000_000, null, null)
        owner.repeatingResult(epoch, 1_200_000_000, 7, 1_210_000_000, null, null)
        assertEquals(5L, owner.receipt()["interveningFrameNumbers"])
        assertEquals(0L, owner.receipt()["sensorIntervals"])
        assertEquals(0L, owner.receipt()["sensorGapsOver50Ms"])
        owner.close()
    }
    @Test fun `still boundary retains adjacent repeating gap with strict interval unavailable`() {
        val owner = owner(); val epoch = owner.openCameraEpoch(true)
        owner.repeatingResult(epoch, 1_000_000_000, 1, 1_010_000_000, null, null)
        owner.stillResult(epoch, 2)
        owner.repeatingResult(epoch, 1_230_000_000, 3, 1_240_000_000, null, null)
        val receipt = owner.receipt()
        assertEquals(0L, receipt["sensorIntervals"])
        assertEquals(230_000_000L, receipt["adjacentRepeatingSensorMaxNs"])
        assertEquals(1L, receipt["adjacentRepeatingSensorGapsOver50Ms"])
        assertEquals(1L, receipt["repeatingPairsCrossingObservedStill"])
        assertEquals(1L, receipt["observedStillResults"])
        owner.close()
    }
    @Test fun `clock failure on exit still closes trace and releases funding`() {
        var reads = 0; var released = 0; val trace = Traces()
        val clock = object : NativeFrameClock {
            override fun monotonicNs(): Long {
                if (++reads == 2) throw IllegalStateException("diagnostic clock")
                return 100L
            }
            override fun elapsedRealtimeNs() = 100L
        }
        val owner = NativeFrameAttributionOwner.create(true, { true }, { released++ }, clock, trace)!!
        val result = owner.measureNativeFrame(NativeFrameSpan.POSE) { 17 }
        assertEquals(17, result)
        assertEquals(1, trace.begins); assertEquals(1, trace.ends)
        assertEquals(0L, owner.receipt()["activeSpans"])
        assertEquals(1L, owner.receipt()["probeErrors"])
        owner.close(); assertEquals(1, released)
    }
    @Test fun `camera replacement clears clock statistics and rejects late epoch`() {
        val owner = owner(); val old = owner.openCameraEpoch(true)
        owner.repeatingResult(old, 1_000_000, 1, 2_000_000, null, null)
        val next = owner.openCameraEpoch(false)
        owner.repeatingResult(old, 2_000_000, 2, 3_000_000, null, null)
        assertEquals(0L, owner.receipt()["repeatingResults"])
        assertEquals(1L, owner.receipt()["staleCameraResults"])
        assertEquals("unqualified", owner.receipt()["sensorClock"])
        owner.invalidateCameraEpoch(next)
        owner.repeatingResult(next, 3_000_000, 3, 4_000_000, null, null)
        assertEquals(0L, owner.receipt()["repeatingResults"])
        owner.close()
    }
    @Test fun `normal nonlocal return closes the trace without a false callback failure`() {
        val trace = Traces(); val owner = owner(trace = trace)
        fun delegate(): Int {
            owner.measureNativeFrame(NativeFrameSpan.PUBLICATION) { return 23 }
            error("unreachable")
        }
        assertEquals(23, delegate())
        val stage = owner.receipt()["publicationConsume"] as Map<*, *>
        assertEquals(1L, stage["count"]); assertEquals(0L, stage["failures"])
        assertEquals(1, trace.begins); assertEquals(1, trace.ends)
        owner.close()
    }
    @Test fun `throwing work preserves callback error with paired trace ownership`() {
        for (stage in listOf(NativeFrameSpan.OBSERVATION, NativeFrameSpan.JPEG_COPY)) {
            val clock = Clock(); val trace = Traces(); val owner = owner(clock, trace)
            var calls = 0; var callerCloses = 0
            val failure = IllegalStateException("actual callback")
            val observed = assertThrows(IllegalStateException::class.java) {
                try {
                    owner.measureNativeFrame(stage) {
                        calls++; clock.now += 100_000L; throw failure
                    }
                } finally { callerCloses++ }
            }
            assertSame(failure, observed); assertEquals(1, calls); assertEquals(1, callerCloses)
            assertEquals(1, trace.begins); assertEquals(1, trace.ends)
            assertEquals(0L, owner.receipt()["activeSpans"])
            val field = if (stage == NativeFrameSpan.JPEG_COPY) "jpegCopy" else "observationIntake"
            val counters = owner.receipt()[field] as Map<*, *>
            assertEquals(1L, counters["failures"]); assertEquals(100_000L, counters["totalNs"])
            owner.close()
        }
    }
    @Test fun `close retains metadata funding until the paired span exits once`() {
        val clock = Clock(); val trace = Traces(); var releases = 0
        val owner = owner(clock, trace) { releases++ }
        owner.measureNativeFrame(NativeFrameSpan.UPLOAD) {
            owner.close(); owner.close(); assertEquals(0, releases)
            clock.now += 20_000L
        }
        assertEquals(1, releases); assertEquals(1, trace.begins); assertEquals(1, trace.ends)
        assertEquals(0L, owner.receipt()["modeledOwnerBytes"])
        assertEquals(1L, owner.receipt()["rejectedSpanCompletions"])
        val reads = clock.calls
        owner.measureNativeFrame(NativeFrameSpan.UPLOAD) { 1 }
        assertEquals(reads, clock.calls)
    }
    @Test fun `reset during work pairs the old trace without mixing new window stats`() {
        val owner = owner()
        owner.measureNativeFrame(NativeFrameSpan.PUBLICATION) { owner.resetMeasurements() }
        val stage = owner.receipt()["publicationConsume"] as Map<*, *>
        assertEquals(0L, stage["count"])
        assertEquals(1L, owner.receipt()["rejectedSpanCompletions"])
        assertEquals("unavailable: SceneView owns upstream update", owner.receipt()["sessionUpdate"])
        assertEquals("unavailable", owner.receipt()["presentation"])
        owner.close()
    }
    private fun withBusyGate(owner: NativeFrameAttributionOwner, action: () -> Unit) {
        val gate = NativeFrameAttributionOwner::class.java.getDeclaredField("state").apply { isAccessible = true }
            .get(owner) as ReentrantLock
        val entered = CountDownLatch(1); val leave = CountDownLatch(1)
        val holder = Thread {
            gate.lock()
            try { entered.countDown(); check(leave.await(2, TimeUnit.SECONDS)) }
            finally { gate.unlock() }
        }
        holder.start(); assertTrue(entered.await(1, TimeUnit.SECONDS))
        try { action() } finally { leave.countDown(); holder.join(2000); assertFalse(holder.isAlive) }
    }

    @Test fun `busy frame admission delegates once without clocks or trace`() {
        val clock = Clock(); val trace = Traces(); val owner = owner(clock, trace); var calls = 0
        withBusyGate(owner) {
            assertEquals(23, owner.measureNativeFrame(NativeFrameSpan.POSE) { calls++; 23 })
        }
        assertEquals(1, calls); assertEquals(0, clock.calls)
        assertEquals(0, trace.begins); assertEquals(0, trace.ends)
        assertEquals(1L, owner.receipt()["spanAdmissionMissesLifetime"])
        owner.close()
    }

    @Test fun `busy completion pairs trace on calling thread and releases closed owner once`() {
        val trace = Traces(); var released = 0; val owner = owner(trace = trace, release = { released++ })
        val epoch = owner.measurementEpoch; val token = owner.beginSpan(NativeFrameSpan.JPEG_COPY, epoch)
        owner.close(); assertEquals(0, released)
        withBusyGate(owner) { owner.endSpan(NativeFrameSpan.JPEG_COPY, token, epoch, false) }
        assertEquals(1, trace.begins); assertEquals(1, trace.ends); assertEquals(1, released)
        assertEquals(0L, owner.receipt()["activeProbeBorrows"])
        assertEquals(1L, owner.receipt()["spanCompletionMissesLifetime"])
        owner.close(); assertEquals(1, released)
    }

    @Test fun `missed repeating observation invalidates pair continuity without invented skips`() {
        val owner = owner(); val epoch = owner.openCameraEpoch(true)
        owner.repeatingResult(epoch, 100, 1, 101, null, null)
        withBusyGate(owner) { owner.repeatingResult(epoch, 200, 2, 201, null, null) }
        owner.repeatingResult(epoch, 300, 3, 301, null, null)
        val receipt = owner.receipt()
        assertEquals(1L, receipt["repeatingObservationMissesLifetime"])
        assertEquals(0L, receipt["adjacentRepeatingSensorIntervals"])
        assertEquals(0L, receipt["sensorIntervals"]); assertEquals(0L, receipt["interveningFrameNumbers"])
        owner.repeatingResult(epoch, 400, 4, 401, null, null)
        assertEquals(1L, owner.receipt()["sensorIntervals"])
        owner.close()
    }

    @Test fun `camera trace writes hold borrow without holding frame aggregation gate`() {
        val entered = CountDownLatch(1); val leave = CountDownLatch(1); val released = AtomicInteger()
        val trace = object : NativeFrameTrace {
            override fun begin(name: String) {}
            override fun end() {}
            override fun enabled() = true
            override fun counter(name: String, value: Long) {
                if (name == "Capture3D.repeatingSensorNs") {
                    entered.countDown(); check(leave.await(2, TimeUnit.SECONDS))
                }
            }
        }
        val owner = NativeFrameAttributionOwner.create(true, { true }, { released.incrementAndGet() }, Clock(), trace)!!
        val epoch = owner.openCameraEpoch(true)
        val camera = Thread { owner.repeatingResult(epoch, 100, 1, 101, null, null) }
        camera.start(); assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            assertEquals(19, owner.measureNativeFrame(NativeFrameSpan.POSE) { 19 })
            assertEquals(0L, owner.receipt()["spanAdmissionMissesLifetime"])
            owner.close(); assertEquals(0, released.get())
        } finally { leave.countDown(); camera.join(2000); assertFalse(camera.isAlive) }
        assertEquals(1, released.get()); assertEquals(0L, owner.receipt()["activeProbeBorrows"])
    }

}
