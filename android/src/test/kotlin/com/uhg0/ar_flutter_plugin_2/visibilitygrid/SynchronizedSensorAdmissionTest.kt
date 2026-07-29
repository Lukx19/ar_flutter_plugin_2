package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynchronizedSensorAdmissionTest {
    @Test
    fun `barrier starting after early frame check rejects the handoff commit`() {
        val lock = Any()
        var barrierActive = false
        var offered = false
        val earlyCheckPassed = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)

        val frame =
            Thread {
                assertFalse(barrierActive)
                earlyCheckPassed.countDown()
                assertTrue(allowCommit.await(1, TimeUnit.SECONDS))
                admitSensorWork(
                    lock = lock,
                    isBlocked = { barrierActive },
                    offer = { offered = true },
                )
            }
        frame.start()

        assertTrue(earlyCheckPassed.await(1, TimeUnit.SECONDS))
        synchronized(lock) {
            barrierActive = true
        }
        allowCommit.countDown()
        frame.join(1_000)

        assertFalse(frame.isAlive)
        assertFalse(offered)
    }
}
