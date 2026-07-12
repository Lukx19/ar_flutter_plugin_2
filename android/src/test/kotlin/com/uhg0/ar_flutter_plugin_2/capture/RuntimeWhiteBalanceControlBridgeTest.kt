package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeWhiteBalanceControlBridgeTest {
    @Test
    fun `supported white balance modes are reported and can be staged`() {
        val target = FakeWhiteBalanceTarget()
        val bridge =
            RuntimeWhiteBalanceControlBridge(
                capabilities =
                    RuntimeWhiteBalanceCapabilities(
                        supportedModes = setOf("auto", "daylight", "shade"),
                        supportedColorTemperatureRange = 2000..8000,
                        sensorArea = SensorArea(width = 4000, height = 3000),
                        pointWhiteBalanceSupported = true,
                    ),
                target = target,
            )

        val modes = bridge.getSupportedWhiteBalanceModes()
        val applied = bridge.setWhiteBalanceMode("daylight")

        assertEquals(listOf("auto", "daylight", "shade"), modes)
        assertTrue(applied)
        assertEquals("daylight", target.stagedMode)
    }

    @Test
    fun `unsupported white balance mode throws stable unsupported error`() {
        val bridge =
            RuntimeWhiteBalanceControlBridge(
                capabilities =
                    RuntimeWhiteBalanceCapabilities(
                        supportedModes = setOf("auto", "shade"),
                        supportedColorTemperatureRange = null,
                        sensorArea = SensorArea(width = 4000, height = 3000),
                        pointWhiteBalanceSupported = true,
                    ),
                target = FakeWhiteBalanceTarget(),
            )

        val error =
            runCatching {
                bridge.setWhiteBalanceMode("daylight")
            }.exceptionOrNull()

        require(error is CaptureSessionException)
        assertEquals("CONTROL_UNSUPPORTED", error.code)
    }

    @Test
    fun `white balance lock toggles and current state maps to dart payload`() {
        val target = FakeWhiteBalanceTarget()
        val bridge =
            RuntimeWhiteBalanceControlBridge(
                capabilities =
                    RuntimeWhiteBalanceCapabilities(
                        supportedModes = setOf("auto", "daylight"),
                        supportedColorTemperatureRange = 2000..8000,
                        sensorArea = SensorArea(width = 4000, height = 3000),
                        pointWhiteBalanceSupported = true,
                    ),
                target = target,
            )

        bridge.setWhiteBalanceMode("daylight")
        bridge.lockWhiteBalance()
        val lockedState = bridge.getCurrentWhiteBalanceState()
        bridge.unlockWhiteBalance()
        val unlockedState = bridge.getCurrentWhiteBalanceState()

        assertEquals("daylight", lockedState["currentMode"])
        assertTrue(lockedState["isWhiteBalanceLocked"] as Boolean)
        assertTrue(lockedState["isAutoWhiteBalanceEnabled"] as Boolean)
        assertEquals("inactive", lockedState["status"])
        assertFalse(unlockedState["isWhiteBalanceLocked"] as Boolean)
    }

    @Test
    fun `manual color temperature clamps into range and maps current manual state`() {
        val target = FakeWhiteBalanceTarget()
        val bridge =
            RuntimeWhiteBalanceControlBridge(
                capabilities =
                    RuntimeWhiteBalanceCapabilities(
                        supportedModes = setOf("auto", "daylight"),
                        supportedColorTemperatureRange = 2000..8000,
                        sensorArea = SensorArea(width = 4000, height = 3000),
                        pointWhiteBalanceSupported = true,
                    ),
                target = target,
            )

        val supportedRange = bridge.getSupportedColorTemperatureRange()
        val appliedTemperature = bridge.setColorTemperature(9000)
        val state = bridge.getCurrentWhiteBalanceState()

        assertEquals(mapOf("min" to 2000, "max" to 8000), supportedRange)
        assertEquals(8000, appliedTemperature)
        assertEquals("manual", state["currentMode"])
        assertEquals(8000, state["currentColorTemperature"])
        assertFalse(state["isAutoWhiteBalanceEnabled"] as Boolean)
    }

    @Test
    fun `white balance lock is unsupported once manual color temperature is active`() {
        val target = FakeWhiteBalanceTarget()
        val bridge =
            RuntimeWhiteBalanceControlBridge(
                capabilities =
                    RuntimeWhiteBalanceCapabilities(
                        supportedModes = setOf("auto", "daylight"),
                        supportedColorTemperatureRange = 2000..8000,
                        sensorArea = SensorArea(width = 4000, height = 3000),
                        pointWhiteBalanceSupported = true,
                    ),
                target = target,
            )

        bridge.setColorTemperature(4200)
        val error =
            runCatching {
                bridge.lockWhiteBalance()
            }.exceptionOrNull()

        require(error is CaptureSessionException)
        assertEquals("CONTROL_UNSUPPORTED", error.code)
    }

    @Test
    fun `point white balance stages a bounded region and clears manual state`() {
        val target = FakeWhiteBalanceTarget()
        val bridge =
            RuntimeWhiteBalanceControlBridge(
                capabilities =
                    RuntimeWhiteBalanceCapabilities(
                        supportedModes = setOf("auto", "daylight"),
                        supportedColorTemperatureRange = 2000..8000,
                        sensorArea = SensorArea(width = 4000, height = 3000),
                        pointWhiteBalanceSupported = true,
                    ),
                target = target,
            )

        bridge.setColorTemperature(4200)
        val error =
            runCatching {
                bridge.setWhiteBalanceFromPoint(0.0, 1.0)
            }.exceptionOrNull()
        require(error is CaptureSessionException)
        assertEquals("CONTROL_UNSUPPORTED", error.code)

        bridge.setWhiteBalanceMode("daylight")
        val applied = bridge.setWhiteBalanceFromPoint(0.0, 1.0)

        assertTrue(applied)
        assertEquals("auto", target.stagedMode)
        assertEquals(null, target.stagedColorTemperature)
        assertEquals(MeteringRegion(left = 0, top = 2550, width = 600, height = 450), target.stagedWhiteBalanceRegion)
    }

    @Test
    fun `current white balance state prefers observed capture state when available`() {
        val target =
            FakeWhiteBalanceTarget().apply {
                stagedMode = "daylight"
                stagedColorTemperature = null
                locked = false
                observedState =
                    RuntimeObservedWhiteBalanceState(
                        currentMode = "manual",
                        currentColorTemperature = 4200,
                        isWhiteBalanceLocked = false,
                        isAutoWhiteBalanceEnabled = false,
                        status = "locked",
                    )
            }
        val bridge =
            RuntimeWhiteBalanceControlBridge(
                capabilities =
                    RuntimeWhiteBalanceCapabilities(
                        supportedModes = setOf("auto", "daylight"),
                        supportedColorTemperatureRange = 2000..8000,
                        sensorArea = SensorArea(width = 4000, height = 3000),
                        pointWhiteBalanceSupported = true,
                    ),
                target = target,
            )

        val state = bridge.getCurrentWhiteBalanceState()

        assertEquals("manual", state["currentMode"])
        assertEquals(4200, state["currentColorTemperature"])
        assertEquals(false, state["isAutoWhiteBalanceEnabled"])
        assertEquals("locked", state["status"])
    }

    private class FakeWhiteBalanceTarget : RuntimeWhiteBalanceControlTarget {
        var stagedMode: String = "auto"
        var stagedColorTemperature: Int? = null
        var locked: Boolean = false
        var stagedWhiteBalanceRegion: MeteringRegion? = null
        var observedState: RuntimeObservedWhiteBalanceState? = null

        override fun setWhiteBalanceMode(mode: String): Boolean {
            stagedMode = mode
            stagedColorTemperature = null
            stagedWhiteBalanceRegion = null
            return true
        }

        override fun setColorTemperature(colorTemperatureK: Int): Boolean {
            stagedMode = "manual"
            stagedColorTemperature = colorTemperatureK
            locked = false
            stagedWhiteBalanceRegion = null
            return true
        }

        override fun setWhiteBalanceRegion(region: MeteringRegion): Boolean {
            stagedMode = "auto"
            stagedColorTemperature = null
            stagedWhiteBalanceRegion = region
            locked = false
            return true
        }

        override fun setWhiteBalanceLocked(locked: Boolean): Boolean {
            this.locked = locked
            return true
        }

        override fun getCurrentWhiteBalanceMode(): String = stagedMode

        override fun getCurrentColorTemperature(): Int? = stagedColorTemperature

        override fun isWhiteBalanceLocked(): Boolean = locked

        override fun isAutoWhiteBalanceEnabled(): Boolean = stagedColorTemperature == null

        override fun getObservedWhiteBalanceState(): RuntimeObservedWhiteBalanceState? =
            observedState
    }
}
