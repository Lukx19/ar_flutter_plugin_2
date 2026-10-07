package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CameraShutdownDrainTest {
    @Test fun `unmatched RAW releases once after callbacks and before zero Image barrier`() {
        var outstanding = 1; var rawClosed = 0
        val events = mutableListOf<String>()
        val correlator = RawJpegCaptureCorrelator<Long, String, Long>({ it }, {}, {
            rawClosed++; outstanding--; events += "raw closed"
        })
        correlator.onRaw(42, "owned raw")
        CameraShutdownDrain.finish(
            { events += "callbacks drained" }, correlator::clear,
            { assertEquals(0, outstanding); events += "zero Images" },
            { events += "readers closed" }, { events += "complete" },
        )
        correlator.clear()
        assertEquals(1, rawClosed)
        assertEquals(listOf("callbacks drained", "raw closed", "zero Images", "readers closed", "complete"), events)
    }

    @Test fun `failed callback quiescence retains RAW reader and Session owners`() {
        var rawReleased = 0; var readersClosed = 0; var completed = 0
        assertThrows(IllegalStateException::class.java) {
            CameraShutdownDrain.finish({ error("callbacks still executing") }, { rawReleased++ }, {},
                { readersClosed++ }, { completed++ })
        }
        assertEquals(0, rawReleased); assertEquals(0, readersClosed); assertEquals(0, completed)
    }

    @Test fun `delayed quit retains joinable callback until actual termination`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val callback = Thread { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
        var quitRequests = 0
        val owner = CameraCallbackThreads<Thread>({ quitRequests++ }, System::currentTimeMillis)
        owner.capture = callback
        callback.start(); assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            owner.requestQuit()
            assertSame(callback, owner.capture)
            assertThrows(IllegalStateException::class.java) { owner.drainUntil(System.currentTimeMillis() + 10) }
            assertSame(callback, owner.capture); assertTrue(callback.isAlive)
            release.countDown()
            owner.drainUntil(System.currentTimeMillis() + 1000)
            assertNull(owner.capture); assertFalse(callback.isAlive); assertTrue(quitRequests >= 2)
        } finally { release.countDown(); callback.join(1000) }
    }

    @Test fun `one terminated handler cannot release the other still held handler owner`() {
        val release = CountDownLatch(1); val entered = CountDownLatch(1)
        val first = Thread {}; first.start(); first.join(1000)
        val held = Thread { entered.countDown(); release.await(2, TimeUnit.SECONDS) }; held.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val owner = CameraCallbackThreads<Thread>({}, System::currentTimeMillis)
        owner.camera = first; owner.capture = held
        try {
            assertThrows(IllegalStateException::class.java) { owner.drainUntil(System.currentTimeMillis() + 10) }
            assertSame(first, owner.camera); assertSame(held, owner.capture)
        } finally { release.countDown(); owner.drainUntil(System.currentTimeMillis() + 1000) }
        assertNull(owner.camera); assertNull(owner.capture)
    }
}
