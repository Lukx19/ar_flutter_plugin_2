package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeExposureControlBridgeTest {
    @Test
    fun `first manual iso change preserves observed auto exposure time`() {
        val target =
            FakeExposureTarget().apply {
                observedState =
                    RuntimeObservedExposureState(
                        currentISO = 320,
                        currentExposureTimeMicros = 10000L,
                        isAutoExposureEnabled = true,
                        isExposureLocked = false,
                        exposureCompensationSteps = 0,
                    )
            }
        val bridge =
            RuntimeExposureControlBridge(
                capabilities =
                    RuntimeExposureCapabilities(
                        supportedIsoRange = 100..1600,
                        supportedExposureTimeMicrosRange = 500L..50000L,
                        exposureCompensationStepsRange = -4..4,
                        exposureCompensationStepEv = 0.5,
                        exposureLockSupported = true,
                    ),
                target = target,
            )

        bridge.setISO(321)

        assertEquals(321, target.stagedIso)
        assertEquals(10000L, target.stagedExposureTimeMicros)
        assertFalse(target.autoExposureEnabled)
    }

    @Test
    fun `setISO clamps to supported range before staging`() {
        val target = FakeExposureTarget()
        val bridge =
            RuntimeExposureControlBridge(
                capabilities =
                    RuntimeExposureCapabilities(
                        supportedIsoRange = 100..1600,
                        supportedExposureTimeMicrosRange = 500L..50000L,
                        exposureCompensationStepsRange = -4..4,
                        exposureCompensationStepEv = 0.5,
                        exposureLockSupported = true,
                    ),
                target = target,
            )

        val actualIso = bridge.setISO(50)

        assertEquals(100, actualIso)
        assertEquals(100, target.stagedIso)
        assertFalse(target.autoExposureEnabled)
    }

    @Test
    fun `setExposureTime clamps to supported range before staging`() {
        val target = FakeExposureTarget()
        val bridge =
            RuntimeExposureControlBridge(
                capabilities =
                    RuntimeExposureCapabilities(
                        supportedIsoRange = 100..1600,
                        supportedExposureTimeMicrosRange = 1000L..20000L,
                        exposureCompensationStepsRange = -4..4,
                        exposureCompensationStepEv = 0.5,
                        exposureLockSupported = true,
                    ),
                target = target,
            )

        val actualExposureTime = bridge.setExposureTime(50000L)

        assertEquals(20000L, actualExposureTime)
        assertEquals(20000L, target.stagedExposureTimeMicros)
        assertFalse(target.autoExposureEnabled)
    }

    @Test
    fun `unsupported ISO range throws stable unsupported error`() {
        val bridge =
            RuntimeExposureControlBridge(
                capabilities =
                    RuntimeExposureCapabilities(
                        supportedIsoRange = null,
                        supportedExposureTimeMicrosRange = 1000L..20000L,
                        exposureCompensationStepsRange = -4..4,
                        exposureCompensationStepEv = 0.5,
                        exposureLockSupported = true,
                    ),
                target = FakeExposureTarget(),
            )

        val error =
            runCatching {
                bridge.getSupportedISORange()
            }.exceptionOrNull()

        require(error is CaptureSessionException)
        assertEquals("CONTROL_UNSUPPORTED", error.code)
    }

    @Test
    fun `failed control update throws stable update error`() {
        val bridge =
            RuntimeExposureControlBridge(
                capabilities =
                    RuntimeExposureCapabilities(
                        supportedIsoRange = 100..1600,
                        supportedExposureTimeMicrosRange = 1000L..20000L,
                        exposureCompensationStepsRange = -4..4,
                        exposureCompensationStepEv = 0.5,
                        exposureLockSupported = true,
                    ),
                target = FakeExposureTarget(failIsoUpdate = true),
            )

        val error =
            runCatching {
                bridge.setISO(400)
            }.exceptionOrNull()

        require(error is CaptureSessionException)
        assertEquals("CONTROL_UPDATE_FAILED", error.code)
    }

    @Test
    fun `auto exposure state map reflects manual and auto transitions`() {
        val target = FakeExposureTarget()
        val bridge =
            RuntimeExposureControlBridge(
                capabilities =
                    RuntimeExposureCapabilities(
                        supportedIsoRange = 100..1600,
                        supportedExposureTimeMicrosRange = 1000L..20000L,
                        exposureCompensationStepsRange = -4..4,
                        exposureCompensationStepEv = 0.5,
                        exposureLockSupported = true,
                    ),
                target = target,
            )

        bridge.setISO(320)
        val manualState = bridge.getCurrentExposureState()
        bridge.setAutoExposureEnabled(true)
        val autoState = bridge.getCurrentExposureState()

        assertEquals("manual", manualState["exposureMode"])
        assertEquals(320, manualState["currentISO"])
        assertEquals("auto", autoState["exposureMode"])
        assertNull(autoState["currentISO"])
        assertTrue(autoState["isAutoExposureEnabled"] as Boolean)
    }

    @Test
    fun `exposure compensation info and clamped writes map back to EV values`() {
        val target = FakeExposureTarget()
        val bridge =
            RuntimeExposureControlBridge(
                capabilities =
                    RuntimeExposureCapabilities(
                        supportedIsoRange = 100..1600,
                        supportedExposureTimeMicrosRange = 1000L..20000L,
                        exposureCompensationStepsRange = -6..6,
                        exposureCompensationStepEv = 0.25,
                        exposureLockSupported = true,
                    ),
                target = target,
            )

        val info = bridge.getExposureCompensationInfo()
        val actualCompensation = bridge.setExposureCompensation(4.0)

        assertEquals(-1.5, info["minCompensation"] as Double, 0.0001)
        assertEquals(1.5, info["maxCompensation"] as Double, 0.0001)
        assertEquals(0.0, info["currentCompensation"] as Double, 0.0001)
        assertEquals(0.25, info["stepSize"] as Double, 0.0001)
        assertEquals(1.5, actualCompensation, 0.0001)
        assertEquals(6, target.stagedExposureCompensationSteps)
    }

    @Test
    fun `exposure lock toggles and current state reflects it only in auto mode`() {
        val target = FakeExposureTarget()
        val bridge =
            RuntimeExposureControlBridge(
                capabilities =
                    RuntimeExposureCapabilities(
                        supportedIsoRange = 100..1600,
                        supportedExposureTimeMicrosRange = 1000L..20000L,
                        exposureCompensationStepsRange = -2..2,
                        exposureCompensationStepEv = 0.5,
                        exposureLockSupported = true,
                    ),
                target = target,
            )

        assertTrue(bridge.lockExposure())
        val lockedAutoState = bridge.getCurrentExposureState()
        bridge.setISO(400)
        val manualState = bridge.getCurrentExposureState()
        assertTrue(bridge.unlockExposure())
        val unlockedState = bridge.getCurrentExposureState()

        assertTrue(lockedAutoState["isExposureLocked"] as Boolean)
        assertFalse(manualState["isExposureLocked"] as Boolean)
        assertFalse(unlockedState["isExposureLocked"] as Boolean)
    }

    @Test
    fun `current exposure state prefers observed capture state when available`() {
        val target =
            FakeExposureTarget().apply {
                stagedIso = 100
                stagedExposureTimeMicros = 1000L
                autoExposureEnabled = false
                stagedExposureCompensationSteps = 1
                observedState =
                    RuntimeObservedExposureState(
                        currentISO = 640,
                        currentExposureTimeMicros = 8000L,
                        isAutoExposureEnabled = true,
                        isExposureLocked = true,
                        exposureCompensationSteps = -2,
                    )
            }
        val bridge =
            RuntimeExposureControlBridge(
                capabilities =
                    RuntimeExposureCapabilities(
                        supportedIsoRange = 100..1600,
                        supportedExposureTimeMicrosRange = 1000L..20000L,
                        exposureCompensationStepsRange = -4..4,
                        exposureCompensationStepEv = 0.5,
                        exposureLockSupported = true,
                    ),
                target = target,
            )

        val state = bridge.getCurrentExposureState()

        assertEquals(640, state["currentISO"])
        assertEquals(8000L, state["currentExposureTime"])
        assertEquals("auto", state["exposureMode"])
        assertEquals(true, state["isAutoExposureEnabled"])
        assertEquals(true, state["isExposureLocked"])
        assertEquals(-1.0, state["exposureCompensation"] as Double, 0.0001)
    }

    private class FakeExposureTarget(
        private val failIsoUpdate: Boolean = false,
    ) : RuntimeExposureControlTarget {
        var stagedIso: Int? = null
        var stagedExposureTimeMicros: Long? = null
        var autoExposureEnabled: Boolean = true
        var stagedExposureCompensationSteps: Int = 0
        var exposureLocked: Boolean = false
        var observedState: RuntimeObservedExposureState? = null

        override fun setISO(
            isoValue: Int,
            preservedExposureTimeMicros: Long?,
        ): Boolean {
            if (failIsoUpdate) {
                return false
            }
            stagedIso = isoValue
            if (autoExposureEnabled && preservedExposureTimeMicros != null) {
                stagedExposureTimeMicros = preservedExposureTimeMicros
            }
            autoExposureEnabled = false
            return true
        }

        override fun setExposureTime(exposureTimeMicros: Long): Boolean {
            stagedExposureTimeMicros = exposureTimeMicros
            autoExposureEnabled = false
            return true
        }

        override fun setAutoExposureEnabled(enabled: Boolean): Boolean {
            autoExposureEnabled = enabled
            if (enabled) {
                stagedIso = null
                stagedExposureTimeMicros = null
            } else {
                exposureLocked = false
            }
            return true
        }

        override fun getCurrentISO(): Int? = stagedIso

        override fun getCurrentExposureTimeMicros(): Long? = stagedExposureTimeMicros

        override fun isAutoExposureEnabled(): Boolean = autoExposureEnabled

        override fun setExposureCompensationSteps(steps: Int): Boolean {
            stagedExposureCompensationSteps = steps
            return true
        }

        override fun getCurrentExposureCompensationSteps(): Int =
            stagedExposureCompensationSteps

        override fun setExposureLocked(locked: Boolean): Boolean {
            exposureLocked = locked
            return true
        }

        override fun isExposureLocked(): Boolean = exposureLocked

        override fun getObservedExposureState(): RuntimeObservedExposureState? =
            observedState
    }
}
