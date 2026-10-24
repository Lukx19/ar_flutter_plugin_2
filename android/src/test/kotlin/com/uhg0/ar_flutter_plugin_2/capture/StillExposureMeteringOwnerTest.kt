package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StillExposureMeteringOwnerTest {
    // Equal controls are not equal submissions: camera callbacks must carry the exact accepted object.
    private data class Request(val ev: Int = 0, val auto: Boolean = true)
    private val owner = StillExposureMeteringOwner<Request>()
    private val sample = StillExposureMetering(1_000_000_000L, 1_000_000_000L, 7L,
        30_000_000L, 200, 33_333_333L, 50)
    private val capabilities = StillExposureCapabilities(true, 100_000L..1_000_000_000L, 50..3200, 1600, true)

    private fun plan() = owner.withMetering { metering ->
        StillExposurePolicy.plan(capabilities, metering, 1_100_000_000L, 7L, true)
    }

    private fun accept(request: Request): Request {
        owner.replaceRepeating { request }
        return request
    }

    @Test fun `only an accepted repeating request with current converged metering enables shortening`() {
        val request = Request()
        assertFalse(owner.publish(request, sample))
        assertNull(plan())
        accept(request)
        assertNull(plan())
        assertTrue(owner.publish(request, null))
        assertNull(plan())
        var previewUpdates = 0
        assertTrue(owner.publish(request, sample) { previewUpdates++ })
        assertEquals(600, plan()?.iso)
        assertEquals(1, previewUpdates)
    }

    @Test fun `EV mutation invalidates before settings change and late earlier result cannot repopulate`() {
        val old = accept(Request())
        owner.publish(old, sample)
        var ev = 0
        owner.changeControls {
            assertNull("metering must clear before applying the new EV", plan())
            ev = 1
            assertFalse(owner.publish(old, sample))
        }
        assertEquals(1, ev)
        assertNull(plan())
        val current = accept(Request(ev = ev))
        var stalePreviewUpdates = 0
        assertFalse(owner.publish(old, sample) { stalePreviewUpdates++ })
        assertEquals(0, stalePreviewUpdates)
        assertNull(plan())
        owner.publish(current, sample.copy(exposureNs = 20_000_000L, iso = 400))
        assertEquals(800, plan()?.iso)
    }

    @Test fun `auto off then on cannot reuse a prechange sample even with identical final controls`() {
        val first = accept(Request())
        owner.publish(first, sample)
        val manual = Request(auto = false)
        owner.changeControls { accept(manual) }
        owner.publish(manual, null)
        assertNull(plan())
        val restored = Request()
        owner.changeControls { accept(restored) }
        assertEquals(first, restored)
        assertFalse(owner.publish(first, sample))
        assertNull(plan())
        assertTrue(owner.publish(restored, sample))
        assertNotNull(plan())
    }

    @Test fun `focus one shot cannot replace repeating metering or update preview state`() {
        val repeating = accept(Request())
        owner.publish(repeating, sample)
        var controlPreviewWrites = 0
        val focusTrigger = Request()
        assertFalse(owner.publish(focusTrigger, sample.copy(iso = 1600)) { controlPreviewWrites++ })
        assertEquals(0, controlPreviewWrites)
        assertEquals(600, plan()?.iso)
    }

    @Test fun `pause refuses delayed current result and resume needs new request convergence`() {
        val paused = accept(Request())
        owner.publish(paused, sample)
        var pausedState = false
        owner.changeControls {
            assertNull(plan())
            pausedState = true
        }
        assertTrue(pausedState)
        assertFalse(owner.publish(paused, sample))
        assertNull(plan())
        val resumed = accept(Request())
        assertFalse(owner.publish(paused, sample))
        assertNull(plan())
        assertTrue(owner.publish(resumed, sample))
        assertEquals(600, plan()?.iso)
    }

    @Test fun `failed repeating submission leaves automatic baseline until a new accepted result`() {
        val previous = accept(Request())
        owner.publish(previous, sample)
        val failed = Request(ev = 1)
        try {
            owner.replaceRepeating {
                assertNull(plan())
                throw IllegalStateException("camera submission failed")
            }
            fail("expected rejected repeating request")
        } catch (_: IllegalStateException) { }
        assertFalse(owner.publish(previous, sample))
        assertFalse(owner.publish(failed, sample))
        assertNull(plan())
        val recovered = accept(Request(ev = 1))
        assertNull(plan())
        owner.publish(recovered, sample)
        assertNotNull(plan())
    }

    @Test fun `failed control mutation never restores the earlier accepted request`() {
        val previous = accept(Request())
        owner.publish(previous, sample)
        try {
            owner.changeControls { error("invalid control") }
            fail("expected rejected control")
        } catch (_: IllegalStateException) { }
        assertFalse(owner.publish(previous, sample))
        assertNull(plan())
        owner.invalidate()
        assertNull(plan())
    }

    @Test fun `still construction and control mutation cannot consume a sample across their transition`() {
        val request = accept(Request())
        owner.publish(request, sample)
        val executor = Executors.newFixedThreadPool(2)
        val reading = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val controlStarted = CountDownLatch(1)
        val mutated = CountDownLatch(1)
        try {
            val still = executor.submit<StillExposurePlan?> {
                owner.withMetering { metering ->
                    reading.countDown()
                    check(releaseRead.await(2, TimeUnit.SECONDS))
                    StillExposurePolicy.plan(capabilities, metering, 1_100_000_000L, 7L, true)
                }
            }
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            val control = executor.submit {
                controlStarted.countDown()
                owner.changeControls {
                    assertNull(plan())
                    mutated.countDown()
                }
            }
            assertTrue(controlStarted.await(2, TimeUnit.SECONDS))
            assertFalse(mutated.await(50, TimeUnit.MILLISECONDS))
            releaseRead.countDown()
            assertEquals(600, still.get(2, TimeUnit.SECONDS)?.iso)
            control.get(2, TimeUnit.SECONDS)
            assertEquals(0L, mutated.count)
            assertFalse(owner.publish(request, sample))
            assertNull(plan())
        } finally {
            releaseRead.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
