package com.uhg0.ar_flutter_plugin_2.markerdetection

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.PhotoSavedLayout
import com.uhg0.ar_flutter_plugin_2.visibilityingest.FakeClock
import com.uhg0.ar_flutter_plugin_2.visibilityingest.VisibilityGridIngestShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native marker detectors (about 8 MB AprilTag decode table each) are
 * created on first use on their worker thread, never when the grid shell
 * builds its marker taps, and destroyed once.
 */
class LazyMarkerDetectorTest {
    private class CountingFactory(private val handle: Long = 42L) : MarkerDetectorFactory {
        var creates = 0
        val destroyed = ArrayList<Long>()
        override val available = true
        override fun create(dictionary: Int): Long {
            creates++
            return handle
        }

        override fun destroy(handle: Long) {
            destroyed += handle
        }
    }

    /** Runs each job at once on the calling thread. */
    private class InlineWorker : MarkerDetectionWorker {
        var quit = false
        override fun post(job: Runnable): Boolean {
            if (quit) return false
            job.run()
            return true
        }

        override fun quit(last: Runnable) {
            last.run()
            quit = true
        }
    }

    private val clock = FakeClock()
    private val shell = VisibilityGridIngestShell(clock, null)

    @Test
    fun `marker shell taps create no native detector until used`() {
        val factory = CountingFactory()
        val taps = GridMarkerShellTaps.create({ null }, { shell }, clock, factory, { InlineWorker() }, { InlineWorker() })
        assertNotNull(taps)
        assertEquals(0, factory.creates)
        taps!!.close()
        assertEquals(0, factory.creates)
        assertEquals(emptyList<Long>(), factory.destroyed)
    }

    @Test
    fun `marker shell taps are not built without the detector library`() {
        val factory = object : MarkerDetectorFactory {
            override val available = false
            override fun create(dictionary: Int): Long = error("no library")
            override fun destroy(handle: Long) = error("no library")
        }
        assertEquals(null, GridMarkerShellTaps.create({ null }, { shell }, clock, factory))
    }

    @Test
    fun `tie point detection creates its detector on the first photo and destroys it once at close`() {
        val factory = CountingFactory(handle = 42L)
        val batches = ArrayList<Int>()
        val producer = PhotoTiePointProducer(
            TiePointBatchSink { batch, _ -> batches += batch.getShort(TiePointBatchLayout.STATUS).toInt() },
            factory = factory,
            worker = InlineWorker(),
        )
        assertTrue(producer.available)
        assertFalse(producer.detectorCreated)
        assertEquals(0, factory.creates)

        val event = ByteBuffer.allocate(PhotoSavedLayout.BYTES).order(ByteOrder.LITTLE_ENDIAN).array()
        // The host JPEG decode yields nothing, so each photo ends as an argument failure after the detector exists.
        producer.onPhotoDurable(ByteArray(32), event, { "/no/such.jpg" }, 1, null)
        assertEquals(1, factory.creates)
        assertTrue(producer.detectorCreated)
        producer.onPhotoDurable(ByteArray(32), event, { "/no/such.jpg" }, 1, null)
        assertEquals(1, factory.creates)
        assertEquals(listOf(-MarkerDetectorNative.STATUS_ARGUMENT, -MarkerDetectorNative.STATUS_ARGUMENT), batches)

        producer.close()
        assertEquals(listOf(42L), factory.destroyed)
        assertFalse(producer.available)
    }

    @Test
    fun `a failed detector creation is not retried and makes live detection unavailable`() {
        val factory = CountingFactory(handle = 0L)
        val producer = MarkerDetectionProducer({ _, _ -> error("no message without a detector") }, factory = factory)
        assertTrue(producer.available)
        assertEquals(0, factory.creates)
        val frame = GreyFrame()
        assertEquals(MarkerDetectorNative.STATUS_UNAVAILABLE, producer.produce(frame))
        assertEquals(1, factory.creates)
        assertFalse(producer.available)
        assertEquals(MarkerDetectorNative.STATUS_UNAVAILABLE, producer.produce(frame))
        assertEquals(1, factory.creates)
        assertEquals(2, producer.failures)
        producer.close()
        assertEquals(emptyList<Long>(), factory.destroyed)
    }

    @Test
    fun `live detection closed before use never creates a detector`() {
        val factory = CountingFactory()
        val producer = MarkerDetectionProducer({ _, _ -> }, factory = factory)
        producer.close()
        assertFalse(producer.available)
        assertEquals(MarkerDetectorNative.STATUS_UNAVAILABLE, producer.produce(GreyFrame()))
        assertEquals(0, factory.creates)
        assertEquals(emptyList<Long>(), factory.destroyed)
    }
}
