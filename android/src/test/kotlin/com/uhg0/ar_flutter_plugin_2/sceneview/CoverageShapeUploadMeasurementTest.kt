package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CoverageShapeUploadMeasurementTest {
    @Test fun `freeze closes frame admission before held clock and excludes completion waiting behind it`() {
        val measurement = CoverageShapeUploadMeasurement()
        measurement.begin(100)
        measurement.frame(110, true); measurement.frame(120, false); measurement.frame(130, true)
        measurement.completed(500, 7, 11, RendererAttributeCodec.BALLS, 24, false)
        val clockEntered = CountDownLatch(1)
        val releaseClock = CountDownLatch(1)
        val completionStarted = CountDownLatch(1)
        val framesFinished = CountDownLatch(1)
        val freezer = CheckedTask("shape-freeze", {
            measurement.freeze {
                clockEntered.countDown(); awaitClockRelease(releaseClock); 900
            }
        })
        val completion = CheckedTask("shape-late-completion", {
            completionStarted.countDown()
            measurement.completed(850, 99, 88, RendererAttributeCodec.PATCHES, 6, false)
        })
        val frames = CheckedTask("shape-frames-during-freeze", {
            repeat(512) { measurement.frame(800, it % 2 == 0) }
            framesFinished.countDown()
        })
        freezer.start()
        try {
            await(clockEntered)
            completion.start(); await(completionStarted); awaitBlocked(completion.thread)
            frames.start()
            // Frames must finish while the clock supplier still holds the measurement monitor.
            await(framesFinished)
        } finally {
            releaseClock.countDown()
            finishAll(freezer, completion, frames)
        }
        val frozen = measurement.snapshot()
        assertWindow(frozen, 100, 900)
        assertEquals(3L, number(frozen, "FrameCount"))
        assertEquals(2L, number(frozen, "MountedFrameCount"))
        assertEquals(true, frozen["shapeMeasurementFrameCountComplete"])
        assertEquals(1L, number(frozen, "UploadCompletions"))
        assertEquals(0L, number(frozen, "MetadataReuses"))
        assertEquals(500L, number(frozen, "FirstCompletionNs"))
        assertEquals(500L, number(frozen, "LastCompletionNs"))
        assertEquals(7L, number(frozen, "ResourceGeneration"))
        assertEquals(11L, number(frozen, "SelectionGeneration"))
        assertEquals(24L, number(frozen, "CompletedPrimitiveCount"))
        assertEquals("directionalBalls", frozen["shapeMeasurementMode"])
        measurement.frame(899, true)
        measurement.completed(899, 100, 100, RendererAttributeCodec.PATCHES, 100, false)
        measurement.completed(901, 100, 100, RendererAttributeCodec.PATCHES, 100, true)
        measurement.freeze { error("A frozen window must not sample another clock") }
        assertEquals(frozen, measurement.snapshot())
    }

    @Test fun `begin queued behind freeze resets all evidence without changing held old snapshot`() {
        val measurement = CoverageShapeUploadMeasurement()
        measurement.begin(100)
        measurement.frame(110, true); measurement.frame(120, false)
        measurement.completed(200, 13, 14, RendererAttributeCodec.PATCHES, 6, false)
        measurement.completed(250, 90, 91, RendererAttributeCodec.BALLS, 100, true)
        val clockEntered = CountDownLatch(1)
        val releaseClock = CountDownLatch(1)
        val resetStarted = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)
        val admitNewEvents = CountDownLatch(1)
        val eventsFinished = CountDownLatch(1)
        val oldSnapshot = AtomicReference<Map<String, Any>>()
        val emptyNewSnapshot = AtomicReference<Map<String, Any>>()
        val reuseOnlySnapshot = AtomicReference<Map<String, Any>>()
        val freezer = CheckedTask("shape-freeze-before-reset", {
            // Keep the observation atomic with freeze, before the queued begin can acquire the monitor.
            synchronized(measurement) {
                measurement.freeze { clockEntered.countDown(); awaitClockRelease(releaseClock); 400 }
                oldSnapshot.set(measurement.snapshot())
            }
        })
        val reset = CheckedTask("shape-reset-after-freeze", {
            resetStarted.countDown()
            measurement.begin(1000)
            emptyNewSnapshot.set(measurement.snapshot())
            resetFinished.countDown()
            await(admitNewEvents)
            measurement.frame(999, true)
            measurement.completed(999, 13, 14, RendererAttributeCodec.PATCHES, 6, false)
            measurement.frame(1005, true); measurement.frame(1006, false)
            measurement.completed(1007, 99, 99, RendererAttributeCodec.PATCHES, 99, true)
            measurement.completed(1008, 99, 99, RendererAttributeCodec.PATCHES, 0, false)
            reuseOnlySnapshot.set(measurement.snapshot())
            measurement.completed(1100, 101, 202, RendererAttributeCodec.BALLS, 24, false)
            eventsFinished.countDown()
        })
        freezer.start()
        try {
            await(clockEntered)
            reset.start(); await(resetStarted); awaitBlocked(reset.thread)
            measurement.frame(300, true)
            releaseClock.countDown(); await(resetFinished)
            val previous = oldSnapshot.get()
            assertWindow(previous, 100, 400)
            assertEquals(2L, number(previous, "FrameCount"))
            assertEquals(1L, number(previous, "MountedFrameCount"))
            assertEquals(1L, number(previous, "UploadCompletions"))
            assertEquals(1L, number(previous, "MetadataReuses"))
            assertEquals("planarPatches", previous["shapeMeasurementMode"])
            val resetSnapshot = emptyNewSnapshot.get()
            assertEquals(1000L, number(resetSnapshot, "WindowStartNs"))
            assertEquals(0L, number(resetSnapshot, "WindowEndNs"))
            assertEquals(false, resetSnapshot["shapeMeasurementWindowComplete"])
            assertEquals(false, resetSnapshot["shapeMeasurementFrameCountComplete"])
            assertEvidenceEmpty(resetSnapshot)
            admitNewEvents.countDown(); await(eventsFinished)
            val reuse = reuseOnlySnapshot.get()
            assertEquals(2L, number(reuse, "FrameCount"))
            assertEquals(1L, number(reuse, "MountedFrameCount"))
            assertEquals(1L, number(reuse, "MetadataReuses"))
            assertEquals(0L, number(reuse, "UploadCompletions"))
            assertEquals(0L, number(reuse, "FirstCompletionNs"))
            assertEquals(0L, number(reuse, "LastCompletionNs"))
            assertEquals(0L, number(reuse, "ResourceGeneration"))
            assertEquals(0L, number(reuse, "SelectionGeneration"))
            assertEquals(0L, number(reuse, "CompletedPrimitiveCount"))
            assertEquals("unavailable", reuse["shapeMeasurementMode"])
            measurement.freeze { 1200 }
            val next = measurement.snapshot()
            assertWindow(next, 1000, 1200)
            assertEquals(2L, number(next, "FrameCount"))
            assertEquals(1L, number(next, "MountedFrameCount"))
            assertEquals(1L, number(next, "UploadCompletions"))
            assertEquals(1L, number(next, "MetadataReuses"))
            assertEquals(1100L, number(next, "FirstCompletionNs"))
            assertEquals(1100L, number(next, "LastCompletionNs"))
            assertEquals(101L, number(next, "ResourceGeneration"))
            assertEquals(202L, number(next, "SelectionGeneration"))
            assertEquals(24L, number(next, "CompletedPrimitiveCount"))
            assertEquals("directionalBalls", next["shapeMeasurementMode"])
            assertEquals(100L, number(previous, "WindowStartNs"))
            assertEquals(400L, number(previous, "WindowEndNs"))
            assertEquals(13L, number(previous, "ResourceGeneration"))
            assertEquals(14L, number(previous, "SelectionGeneration"))
            assertEquals(6L, number(previous, "CompletedPrimitiveCount"))
        } finally {
            releaseClock.countDown(); admitNewEvents.countDown()
            finishAll(freezer, reset)
        }
    }

    @Test fun `competing freezes retain first native clock cut and do not sample losing supplier`() {
        val measurement = CoverageShapeUploadMeasurement()
        measurement.begin(100)
        measurement.frame(110, true)
        val clockEntered = CountDownLatch(1)
        val releaseClock = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val losingClockCalls = AtomicInteger()
        val first = CheckedTask("shape-first-freeze", {
            measurement.freeze { clockEntered.countDown(); awaitClockRelease(releaseClock); 500 }
        })
        val second = CheckedTask("shape-competing-freeze", {
            secondStarted.countDown()
            measurement.freeze { losingClockCalls.incrementAndGet(); 900 }
        })
        first.start()
        try {
            await(clockEntered)
            second.start(); await(secondStarted); awaitBlocked(second.thread)
            measurement.frame(300, true)
            assertEquals(0, losingClockCalls.get())
        } finally {
            releaseClock.countDown()
            finishAll(first, second)
        }
        val frozen = measurement.snapshot()
        assertWindow(frozen, 100, 500)
        assertEquals(1L, number(frozen, "FrameCount"))
        assertEquals(1L, number(frozen, "MountedFrameCount"))
        assertEquals(0, losingClockCalls.get())
    }

    @Test fun `frame capacity refuses overflow and reset restores complete bounded exposure`() {
        val measurement = CoverageShapeUploadMeasurement()
        measurement.begin(100)
        // Exercise the public scalar writer at its real limit, without private-state mutation or per-frame snapshots.
        repeat(MAXIMUM_FRAME_COUNT) { measurement.frame(110, true) }
        measurement.frame(111, false)
        measurement.freeze { 120 }
        val overflowed = measurement.snapshot()
        assertWindow(overflowed, 100, 120)
        assertEquals(MAXIMUM_FRAME_COUNT.toLong(), number(overflowed, "FrameCount"))
        assertEquals(MAXIMUM_FRAME_COUNT.toLong(), number(overflowed, "MountedFrameCount"))
        assertEquals(false, overflowed["shapeMeasurementFrameCountComplete"])
        measurement.frame(119, true)
        assertEquals(overflowed, measurement.snapshot())
        measurement.begin(200)
        measurement.frame(199, true)
        measurement.frame(210, false); measurement.frame(211, true)
        measurement.freeze { 220 }
        val reset = measurement.snapshot()
        assertWindow(reset, 200, 220)
        assertEquals(2L, number(reset, "FrameCount"))
        assertEquals(1L, number(reset, "MountedFrameCount"))
        assertEquals(true, reset["shapeMeasurementFrameCountComplete"])
        assertEquals(false, overflowed["shapeMeasurementFrameCountComplete"])
        assertEquals(MAXIMUM_FRAME_COUNT.toLong(), number(overflowed, "FrameCount"))
    }

    @Test fun `window generation exhaustion refuses reused frame epoch and preserves prior frozen cut`() {
        val measurement = CoverageShapeUploadMeasurement()
        repeat(MAXIMUM_WINDOWS) { measurement.begin(it + 1L) }
        measurement.frame(MAXIMUM_WINDOWS + 1L, true)
        measurement.freeze { MAXIMUM_WINDOWS + 2L }
        val lastSupported = measurement.snapshot()
        assertEquals(1L, number(lastSupported, "FrameCount"))
        assertEquals(1L, number(lastSupported, "MountedFrameCount"))
        assertEquals(true, lastSupported["shapeMeasurementFrameCountComplete"])
        measurement.begin(MAXIMUM_WINDOWS + 10L)
        measurement.frame(MAXIMUM_WINDOWS + 11L, true)
        measurement.completed(MAXIMUM_WINDOWS + 12L, 7, 9, RendererAttributeCodec.BALLS, 24, false)
        measurement.freeze { MAXIMUM_WINDOWS + 15L }
        val exhausted = measurement.snapshot()
        assertWindow(exhausted, MAXIMUM_WINDOWS + 10L, MAXIMUM_WINDOWS + 15L)
        assertEquals(0L, number(exhausted, "FrameCount"))
        assertEquals(0L, number(exhausted, "MountedFrameCount"))
        assertEquals(false, exhausted["shapeMeasurementFrameCountComplete"])
        assertEquals(1L, number(exhausted, "UploadCompletions"))
        measurement.begin(MAXIMUM_WINDOWS + 20L)
        measurement.frame(MAXIMUM_WINDOWS + 21L, true)
        measurement.freeze { MAXIMUM_WINDOWS + 25L }
        val later = measurement.snapshot()
        assertEquals(0L, number(later, "FrameCount"))
        assertEquals(false, later["shapeMeasurementFrameCountComplete"])
        assertEquals(1L, number(lastSupported, "FrameCount"))
        assertEquals(true, lastSupported["shapeMeasurementFrameCountComplete"])
        assertEquals(1L, number(exhausted, "UploadCompletions"))
    }

    private fun assertWindow(snapshot: Map<String, Any>, start: Long, end: Long) {
        assertEquals("androidMonotonic", snapshot["shapeMeasurementClock"])
        assertEquals(start, number(snapshot, "WindowStartNs"))
        assertEquals(end, number(snapshot, "WindowEndNs"))
        assertEquals(true, snapshot["shapeMeasurementWindowComplete"])
        assertTrue(number(snapshot, "MountedFrameCount") <= number(snapshot, "FrameCount"))
    }

    private fun assertEvidenceEmpty(snapshot: Map<String, Any>) {
        for (field in listOf("UploadCompletions", "MetadataReuses", "FirstCompletionNs", "LastCompletionNs",
            "CompletedPrimitiveCount", "ResourceGeneration", "SelectionGeneration", "FrameCount", "MountedFrameCount")) {
            assertEquals(field, 0L, number(snapshot, field))
        }
        assertEquals("unavailable", snapshot["shapeMeasurementMode"])
    }

    private fun number(snapshot: Map<String, Any>, field: String): Long =
        (snapshot.getValue("shapeMeasurement$field") as Number).toLong()

    private fun await(latch: CountDownLatch) { assertTrue("Coordinated test phase did not advance", latch.await(5, TimeUnit.SECONDS)) }
    private fun awaitClockRelease(latch: CountDownLatch) {
        assertTrue("Freeze clock was not released", latch.await(15, TimeUnit.SECONDS))
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED) {
            assertTrue("Expected caller to wait behind the held freeze monitor", thread.isAlive && System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    private fun finishAll(vararg tasks: CheckedTask) {
        var firstFailure: Throwable? = null
        for (task in tasks) {
            try { task.finish() } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error else firstFailure.addSuppressed(error)
            }
        }
        firstFailure?.let { throw it }
    }

    private class CheckedTask(name: String, action: () -> Unit) {
        private val failure = AtomicReference<Throwable>()
        val thread = Thread({ try { action() } catch (error: Throwable) { failure.set(error) } }, name).apply { isDaemon = true }
        fun start() { thread.start() }
        fun finish() {
            if (thread.state == Thread.State.NEW) return
            thread.join(5000)
            if (thread.isAlive) {
                thread.interrupt(); thread.join(500)
                fail("Coordinated test thread did not finish: ${thread.name}")
            }
            failure.get()?.let { throw AssertionError("Coordinated test thread failed: ${thread.name}").apply { initCause(it) } }
        }
    }

    private companion object {
        const val MAXIMUM_FRAME_COUNT = 8_388_607
        const val MAXIMUM_WINDOWS = 65_535
    }
}
