package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Executes the production AR entry boundary and publication scheduler callback. */
class SceneViewHostCadenceTest {
    @Test
    fun `upload continuations do not inflate AR cadence or hide stalled AR delivery`() {
        val fixture = HostFixture()
        var cameraNs = 2_000_000_000L
        var arArrivalNs = fixture.clockNs
        fixture.host.recordArSessionFrameCadence(cameraNs, arArrivalNs)
        fixture.uploadContinuation(arArrivalNs + 16_000_000L)
        assertEquals(0, fixture.arCallbacks.snapshot()["sampleCount"])
        assertEquals(0, fixture.cameraFrames.snapshot()["sampleCount"])
        repeat(15) {
            arArrivalNs += 33_000_000L
            cameraNs += 33_000_000L
            fixture.host.recordArSessionFrameCadence(cameraNs, arArrivalNs)
            fixture.uploadContinuation(arArrivalNs + 16_000_000L)
        }
        assertTrue(fixture.host.depthIntakeFrameHealthy())
        assertEquals(15, fixture.arCallbacks.snapshot()["sampleCount"])
        assertEquals(15, fixture.continuations.snapshot()["sampleCount"])
        // Other Android callbacks continue while AR session delivery stalls.
        for (step in 2..5) fixture.uploadContinuation(arArrivalNs + step * 20_000_000L)
        arArrivalNs += 120_000_000L
        cameraNs += 33_000_000L
        fixture.host.recordArSessionFrameCadence(cameraNs, arArrivalNs)
        assertEquals(16, fixture.arCallbacks.snapshot()["sampleCount"])
        assertEquals(120.0, fixture.arCallbacks.snapshot()["maxFrameIntervalMs"] as Double, 0.0)
        assertEquals(33.0, fixture.cameraFrames.snapshot()["maxFrameIntervalMs"] as Double, 0.0)
        assertTrue(fixture.cameraFrames.healthyForDepthIntake())
        assertFalse(fixture.host.depthIntakeFrameHealthy())
    }

    @Test
    fun `AR entry arrival preserves a 120 ms gap through unequal callback work`() {
        val fixture = HostFixture()
        var arrivalNs = fixture.clockNs
        var cameraNs = 2_000_000_000L
        repeat(16) { index ->
            fixture.clockNs = arrivalNs
            // Sampling after work would place the final smooth callback 100 ms
            // late and compress the following real 120 ms arrival gap to 20 ms.
            fixture.arUpdate(cameraNs, workNs = if (index == 15) 100_000_000L else 0L)
            arrivalNs += 33_000_000L
            cameraNs += 33_000_000L
        }
        assertTrue(fixture.host.depthIntakeFrameHealthy())
        arrivalNs += 87_000_000L
        fixture.clockNs = arrivalNs
        fixture.arUpdate(cameraNs)
        assertEquals(16, fixture.arCallbacks.snapshot()["sampleCount"])
        assertEquals(120.0, fixture.arCallbacks.snapshot()["maxFrameIntervalMs"] as Double, 0.0)
        assertEquals(33.0, fixture.cameraFrames.snapshot()["maxFrameIntervalMs"] as Double, 0.0)
        assertTrue(fixture.cameraFrames.healthyForDepthIntake())
        assertFalse(fixture.host.depthIntakeFrameHealthy())
        assertEquals(0, fixture.callbackDepth.get())
    }

    @Test
    fun `paused AR entry excludes renderer work and cadence but preserves session delivery`() {
        val fixture = HostFixture()
        fixture.setPaused(true)
        fixture.arUpdate(2_000_000_000L, workNs = 100_000_000L)
        fixture.clockNs += 120_000_000L
        fixture.arUpdate(2_033_000_000L)
        assertEquals(0, fixture.rendererWorkCount)
        assertEquals(2, fixture.sessionWorkCount)
        assertEquals(0, fixture.arCallbacks.snapshot()["sampleCount"])
        assertEquals(0, fixture.cameraFrames.snapshot()["sampleCount"])
        assertEquals(0, fixture.callbackDepth.get())
        fixture.setPaused(false)
        fixture.arUpdate(2_066_000_000L)
        fixture.clockNs += 33_000_000L
        fixture.arUpdate(2_099_000_000L)
        assertEquals(2, fixture.rendererWorkCount)
        assertEquals(4, fixture.sessionWorkCount)
        assertEquals(1, fixture.arCallbacks.snapshot()["sampleCount"])
        assertEquals(33.0, fixture.arCallbacks.snapshot()["maxFrameIntervalMs"] as Double, 0.0)
        assertEquals(33.0, fixture.cameraFrames.snapshot()["maxFrameIntervalMs"] as Double, 0.0)
        assertEquals(0, fixture.callbackDepth.get())
    }

    private class HostFixture {
        val host: SceneViewHost
        val arCallbacks = FrameCadenceTracker()
        val cameraFrames = FrameCadenceTracker()
        val continuations = UploadContinuationCadenceTracker()
        val callbackDepth = AtomicInteger()
        var clockNs = 1_000_000_000L
        var rendererWorkCount = 0
        var sessionWorkCount = 0
        private val pendingUpload = AtomicBoolean(false)
        private val callbacks = ArrayDeque<() -> Unit>()
        private val request = SceneViewHost::class.java.getDeclaredMethod("requestCoveragePublicationFrame")
            .apply { isAccessible = true }
        private val arEntry = SceneViewHost::class.java.getDeclaredMethod(
            "processArSessionUpdate", Function0::class.java, Function0::class.java, Function0::class.java,
        ).apply { isAccessible = true }

        init {
            val unsafeType = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            host = unsafeType.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, SceneViewHost::class.java) as SceneViewHost
            inject("arSessionCallbackCadenceTracker", arCallbacks)
            inject("cameraFrameCadenceTracker", cameraFrames)
            inject("uploadContinuationCadenceTracker", continuations)
            inject("frameCallbackClockNs", { clockNs })
            inject("rendererCallbackDepth", callbackDepth)
            inject("coverageRendererOwner", NativeCoverageRendererOwner())
            inject("rendererTelemetry", RendererTelemetry())
            inject("coveragePublications", CoverageRendererPublicationMailbox<Any>())
            inject("coverageUploadFramePending", pendingUpload)
            inject("coveragePresentationRefreshPending", AtomicBoolean(false))
            inject("coverageSlotRefs", arrayOf(AtomicReference<Any?>(null), AtomicReference<Any?>(null)))
            inject("rendererPageFrameScheduler\$delegate", lazyOf(RendererPageFrameScheduler { callbacks.addLast(it) }))
        }

        private fun inject(name: String, value: Any) = SceneViewHost::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(host, value)

        fun setPaused(paused: Boolean) = inject("rendererPaused", paused)

        fun arUpdate(cameraNs: Long, workNs: Long = 0L) {
            val cameraTimestamp: () -> Long = { cameraNs }
            val rendererWork: () -> Unit = {
                rendererWorkCount++
                clockNs += workNs
            }
            val sessionWork: () -> Unit = { sessionWorkCount++ }
            arEntry.invoke(host, cameraTimestamp, rendererWork, sessionWork)
        }

        fun uploadContinuation(atNs: Long) {
            clockNs = atNs
            pendingUpload.set(true)
            request.invoke(host)
            check(callbacks.size == 1)
            callbacks.removeFirst().invoke()
        }
    }
}
