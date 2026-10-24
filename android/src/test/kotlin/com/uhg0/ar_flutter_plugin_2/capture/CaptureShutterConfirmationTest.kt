package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class CaptureShutterConfirmationTest {
    @Test fun aLogicalBracketConfirmsOnlyItsFinalSubmittedExposure() {
        val confirmation = CaptureShutterConfirmation()
        var count = 0
        repeat(3) { confirmation.onExposureCompleted(memberIndex = 0, lastMemberIndex = 2) { count++ } }
        confirmation.onExposureCompleted(memberIndex = 1, lastMemberIndex = 2) { count++ }
        assertEquals(0, count)
        repeat(3) { confirmation.onExposureCompleted(memberIndex = 2, lastMemberIndex = 2) { count++ } }
        assertEquals(1, count)
    }

    @Test fun noExposureHasNoFeedbackAndTheNextPhotoHasItsOwnConfirmation() {
        var count = 0
        CaptureShutterConfirmation()
        assertEquals(0, count)
        repeat(2) { CaptureShutterConfirmation().onExposureCompleted { count++ } }
        assertEquals(2, count)
    }

    @Test fun cancellationSuppressesLateCameraCompletion() {
        val confirmation = CaptureShutterConfirmation()
        var count = 0
        confirmation.cancel()
        confirmation.onExposureCompleted { count++ }
        assertEquals(0, count)
    }

    @Test fun overlappingExposureCallbacksStillConfirmOnce() {
        val confirmation = CaptureShutterConfirmation()
        val count = AtomicInteger()
        val ready = CountDownLatch(1)
        val threads = List(8) { Thread { ready.await(); confirmation.onExposureCompleted { count.incrementAndGet() } } }
        threads.forEach { it.start() }
        ready.countDown()
        threads.forEach { it.join(2000); check(!it.isAlive) }
        assertEquals(1, count.get())
    }
}
