package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureHapticFeedbackTest {
    @Test fun ownedForegroundExposureWithMotorAndEnabledSettingsCanVibrate() {
        assertTrue(CaptureHapticPolicy.allowed(true, true, true, true, true))
    }

    @Test fun cancellationBetweenCameraCallbackAndMainThreadSuppressesFeedback() {
        assertFalse(CaptureHapticPolicy.allowed(false, true, true, true, true))
    }

    @Test fun losingForegroundBeforeDeliverySuppressesFeedback() {
        assertFalse(CaptureHapticPolicy.allowed(true, false, true, true, true))
    }

    @Test fun disabledViewFeedbackIsRespected() {
        assertFalse(CaptureHapticPolicy.allowed(true, true, false, true, true))
    }

    @Test fun disabledSystemTouchFeedbackIsRespected() {
        assertFalse(CaptureHapticPolicy.allowed(true, true, true, false, true))
    }

    @Test fun missingMotorDoesNotReportCaptureFeedback() {
        assertFalse(CaptureHapticPolicy.allowed(true, true, true, true, false))
    }

    @Test fun confirmationIsOneBoundedPulseAndManualFailureIsDistinctTwoPulses() {
        assertArrayEquals(longArrayOf(0, 45), CaptureHapticPattern.CAPTURED.timings())
        assertArrayEquals(longArrayOf(0, 45, 70, 45), CaptureHapticPattern.REJECTED.timings())
        assertTrue(CaptureHapticPattern.REJECTED.timings().sum() < 200)
        for (pattern in CaptureHapticPattern.values()) {
            assertTrue(pattern.exclusionMs() > pattern.timings().sum())
        }
    }
}
