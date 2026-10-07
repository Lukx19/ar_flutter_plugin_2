package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CameraDeviceCloseBarrierTest {
    @Test fun `matching device closes without session callback then every independent drain completes`() {
        val device = Any()
        val barrier = CameraDeviceCloseBarrier<Any>()
        barrier.open(1)
        assertFalse(barrier.beginClose(1, device))
        assertTrue(barrier.onClosed(1, device))
        val events = mutableListOf<String>()
        CameraShutdownDrain.finish(
            { check(barrier.isClosed(1)); events += "threads joined" },
            { events += "RAW released" }, { events += "zero Images" },
            { events += "readers closed" }, { barrier.finish(1); events += "Session releasable" },
        )
        assertEquals(listOf("threads joined", "RAW released", "zero Images", "readers closed", "Session releasable"), events)
        assertFalse(barrier.isClosed(1))
    }

    @Test fun `session closure alone cannot authorize device or resource release`() {
        val barrier = CameraDeviceCloseBarrier<Any>()
        barrier.open(2)
        barrier.beginClose(2, Any())
        // A session notification is not supplied to the device-closure authority.
        var released = false
        assertThrows(IllegalStateException::class.java) {
            CameraShutdownDrain.finish({ check(barrier.isClosed(2)) }, { released = true }, {}, {}, {})
        }
        assertFalse(released)
        assertThrows(IllegalStateException::class.java) { barrier.finish(2) }
    }

    @Test fun `equal but different device identity cannot open close barrier`() {
        data class Device(val id: String)
        val owned = Device("camera0")
        val other = Device("camera0")
        val barrier = CameraDeviceCloseBarrier<Device>()
        barrier.open(3)
        barrier.beginClose(3, owned)
        assertFalse(barrier.onClosed(3, other))
        assertFalse(barrier.isClosed(3))
        assertTrue(barrier.onClosed(3, owned))
    }

    @Test fun `old and duplicate callbacks cannot complete a newer closing generation`() {
        val old = Any(); val current = Any()
        val barrier = CameraDeviceCloseBarrier<Any>()
        barrier.open(4); barrier.beginClose(4, old)
        assertTrue(barrier.onClosed(4, old))
        assertFalse(barrier.onClosed(4, old))
        barrier.finish(4)
        barrier.open(5); barrier.beginClose(5, current)
        assertFalse(barrier.bindClose(4, old))
        assertFalse(barrier.bindClose(5, old))
        assertFalse(barrier.onClosed(4, old))
        assertFalse(barrier.onClosed(4, current))
        assertFalse(barrier.onClosed(5, old))
        assertFalse(barrier.isClosed(5))
        assertTrue(barrier.onClosed(5, current))
    }

    @Test fun `no owned device preserves empty cleanup without accepting stale closure`() {
        val barrier = CameraDeviceCloseBarrier<Any>()
        barrier.open(6)
        assertFalse(barrier.beginClose(5, null))
        assertFalse(barrier.isClosed(6))
        assertTrue(barrier.beginClose(6, null))
        assertTrue(barrier.isClosed(6))
        assertFalse(barrier.onClosed(5, Any()))
        barrier.finish(6)
    }

    @Test fun `callback before close binding cannot manufacture matching device ownership`() {
        val device = Any()
        val barrier = CameraDeviceCloseBarrier<Any>()
        barrier.open(7)
        assertFalse(barrier.onClosed(7, device))
        assertFalse(barrier.beginClose(7, device))
        assertFalse(barrier.beginClose(7, null))
        assertFalse(barrier.beginClose(7, Any()))
        assertFalse(barrier.isClosed(7))
        assertTrue(barrier.onClosed(7, device))
        assertTrue(barrier.beginClose(7, null))
    }

    @Test fun `matched device does not release Session while callback thread remains owned`() {
        val device = Any()
        val barrier = CameraDeviceCloseBarrier<Any>()
        barrier.open(8); barrier.beginClose(8, device); barrier.onClosed(8, device)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val callback = Thread { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
        val threads = CameraCallbackThreads<Thread>({}, System::currentTimeMillis)
        threads.camera = callback
        callback.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        var released = false
        try {
            assertThrows(IllegalStateException::class.java) {
                CameraShutdownDrain.finish(
                    { check(barrier.isClosed(8)); threads.drainUntil(System.currentTimeMillis() + 10) },
                    { released = true }, {}, {}, { barrier.finish(8) },
                )
            }
            assertFalse(released); assertSame(callback, threads.camera); assertTrue(barrier.isClosed(8))
        } finally { release.countDown(); threads.drainUntil(System.currentTimeMillis() + 1000) }
    }

    @Test fun `matched device does not release readers or Session when Image barrier fails`() {
        val device = Any()
        val barrier = CameraDeviceCloseBarrier<Any>()
        barrier.open(9); barrier.beginClose(9, device); barrier.onClosed(9, device)
        var rawReleased = false; var readersClosed = false; var completed = false
        assertThrows(IllegalStateException::class.java) {
            CameraShutdownDrain.finish(
                { check(barrier.isClosed(9)) }, { rawReleased = true }, { error("Image still owned") },
                { readersClosed = true }, { barrier.finish(9); completed = true },
            )
        }
        assertTrue(rawReleased); assertFalse(readersClosed); assertFalse(completed)
        assertTrue(barrier.isClosed(9))
    }

    @Test fun `error or disconnect callback before cleanup preserves matched closure credit`() {
        val device = Any()
        val barrier = CameraDeviceCloseBarrier<Any>()
        barrier.open(10)
        assertTrue(barrier.bindClose(10, device))
        assertFalse(barrier.isClosed(10))
        assertTrue(barrier.onClosed(10, device))
        // Error/disconnect has cleared the active CameraDevice handle by cleanup.
        assertTrue(barrier.beginClose(10, null))
        assertTrue(barrier.isClosed(10))
        barrier.finish(10)
    }

    @Test fun `error or disconnect callback after cleanup releases only its retained device identity`() {
        val device = Any()
        val barrier = CameraDeviceCloseBarrier<Any>()
        barrier.open(11)
        assertTrue(barrier.bindClose(11, device))
        assertFalse(barrier.beginClose(11, null))
        assertFalse(barrier.onClosed(11, Any()))
        assertFalse(barrier.onClosed(10, device))
        assertFalse(barrier.isClosed(11))
        assertTrue(barrier.onClosed(11, device))
        assertTrue(barrier.beginClose(11, null))
        barrier.finish(11)
    }

    @Test fun `missing error or disconnect close callback retains device and downstream owners`() {
        val device = Any()
        val barrier = CameraDeviceCloseBarrier<Any>()
        barrier.open(12)
        assertTrue(barrier.bindClose(12, device))
        repeat(2) { assertFalse(barrier.beginClose(12, null)) }
        var released = false
        assertThrows(IllegalStateException::class.java) {
            CameraShutdownDrain.finish({ check(barrier.isClosed(12)) }, { released = true }, {}, {}, { barrier.finish(12) })
        }
        assertFalse(released)
        assertFalse(barrier.isClosed(12))
        // Retained reference still rejects replacement and accepts only its callback.
        assertFalse(barrier.bindClose(12, Any()))
        assertTrue(barrier.onClosed(12, device))
    }
}
