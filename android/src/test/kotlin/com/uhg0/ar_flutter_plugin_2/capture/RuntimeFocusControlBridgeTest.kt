package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFocusControlBridgeTest {
    @Test
    fun `normalized focus distance clamps and maps to diopters`() {
        val target = FakeFocusTarget()
        val bridge =
            RuntimeFocusControlBridge(
                capabilities =
                    RuntimeFocusCapabilities(
                        maxFocusDistanceDiopters = 10f,
                        supportedFocusModes = setOf("auto", "fixed", "infinity"),
                        sensorArea = SensorArea(width = 4000, height = 3000),
                        tapFocusSupported = true,
                    ),
                target = target,
            )

        val actualDistance = bridge.setFocusDistance(-1.0)

        assertEquals(0.0, actualDistance!!, 0.0001)
        assertEquals(10f, target.stagedFocusDistanceDiopters)
        assertFalse(target.autofocusEnabled)
    }

    @Test
    fun `unsupported manual focus reports stable unsupported error`() {
        val bridge =
            RuntimeFocusControlBridge(
                capabilities =
                    RuntimeFocusCapabilities(
                        maxFocusDistanceDiopters = null,
                        supportedFocusModes = setOf("auto"),
                        sensorArea = null,
                        tapFocusSupported = false,
                    ),
                target = FakeFocusTarget(),
            )

        val error =
            runCatching {
                bridge.setFocusDistance(0.5)
            }.exceptionOrNull()

        require(error is CaptureSessionException)
        assertEquals("CONTROL_UNSUPPORTED", error.code)
    }

    @Test
    fun `supported focus modes are reported and unsupported ones are rejected`() {
        val bridge =
            RuntimeFocusControlBridge(
                capabilities =
                    RuntimeFocusCapabilities(
                        maxFocusDistanceDiopters = 8f,
                        supportedFocusModes = setOf("auto", "continuous", "fixed"),
                        sensorArea = SensorArea(width = 4000, height = 3000),
                        tapFocusSupported = true,
                    ),
                target = FakeFocusTarget(),
            )

        val modes = bridge.getSupportedFocusModes()
        val error =
            runCatching {
                bridge.setFocusMode("macro")
            }.exceptionOrNull()

        assertEquals(listOf("auto", "continuous", "fixed"), modes)
        require(error is CaptureSessionException)
        assertEquals("CONTROL_UNSUPPORTED", error.code)
    }

    @Test
    fun `current focus state maps staged values into dart payload shape`() {
        val target =
            FakeFocusTarget().apply {
                autofocusEnabled = false
                stagedFocusMode = "fixed"
                stagedFocusDistanceDiopters = 2f
            }
        val bridge =
            RuntimeFocusControlBridge(
                capabilities =
                    RuntimeFocusCapabilities(
                        maxFocusDistanceDiopters = 4f,
                        supportedFocusModes = setOf("auto", "fixed"),
                        sensorArea = SensorArea(width = 4000, height = 3000),
                        tapFocusSupported = true,
                    ),
                target = target,
            )

        val state = bridge.getCurrentFocusState()

        assertEquals(0.5, state["currentFocusDistance"] as Double, 0.0001)
        assertEquals("fixed", state["currentFocusMode"])
        assertFalse(state["isAutofocusEnabled"] as Boolean)
        assertEquals("inactive", state["focusStatus"])
    }

    @Test
    fun `autofocus toggle returns applied state`() {
        val target = FakeFocusTarget()
        val bridge =
            RuntimeFocusControlBridge(
                capabilities =
                    RuntimeFocusCapabilities(
                        maxFocusDistanceDiopters = 10f,
                        supportedFocusModes = setOf("auto", "fixed"),
                        sensorArea = SensorArea(width = 4000, height = 3000),
                        tapFocusSupported = true,
                    ),
                target = target,
            )

        val enabled = bridge.setAutofocusEnabled(false)

        assertFalse(enabled)
        assertFalse(target.autofocusEnabled)
    }

    @Test
    fun `focus point maps normalized coordinates into a bounded focus region`() {
        val target = FakeFocusTarget()
        val bridge =
            RuntimeFocusControlBridge(
                capabilities =
                    RuntimeFocusCapabilities(
                        maxFocusDistanceDiopters = 10f,
                        supportedFocusModes = setOf("auto", "fixed"),
                        sensorArea = SensorArea(width = 4000, height = 3000),
                        tapFocusSupported = true,
                    ),
                target = target,
            )

        val applied = bridge.focusAtPoint(1.0, 0.0)
        val state = bridge.getCurrentFocusState()
        val region = state["focusRegion"] as Map<*, *>

        assertTrue(applied)
        assertEquals("auto", state["currentFocusMode"])
        assertEquals("scanning", state["focusStatus"])
        assertEquals(0.85, region["left"] as Double, 0.0001)
        assertEquals(0.0, region["top"] as Double, 0.0001)
        assertEquals(0.15, region["width"] as Double, 0.0001)
        assertEquals(0.15, region["height"] as Double, 0.0001)
    }

    @Test
    fun `current focus state prefers observed capture state when available`() {
        val target =
            FakeFocusTarget().apply {
                stagedFocusMode = "fixed"
                autofocusEnabled = false
                stagedFocusDistanceDiopters = 9f
                observedState =
                    RuntimeObservedFocusState(
                        currentFocusDistanceDiopters = 2f,
                        currentFocusMode = "auto",
                        isAutofocusEnabled = true,
                        isFocusLocked = true,
                        focusStatus = "locked",
                        focusRegion = MeteringRegion(left = 100, top = 200, width = 600, height = 450),
                    )
            }
        val bridge =
            RuntimeFocusControlBridge(
                capabilities =
                    RuntimeFocusCapabilities(
                        maxFocusDistanceDiopters = 4f,
                        supportedFocusModes = setOf("auto", "fixed"),
                        sensorArea = SensorArea(width = 4000, height = 3000),
                        tapFocusSupported = true,
                    ),
                target = target,
            )

        val state = bridge.getCurrentFocusState()
        val region = state["focusRegion"] as Map<*, *>

        assertEquals(0.5, state["currentFocusDistance"] as Double, 0.0001)
        assertEquals("auto", state["currentFocusMode"])
        assertEquals(true, state["isAutofocusEnabled"])
        assertEquals(true, state["isFocusLocked"])
        assertEquals("locked", state["focusStatus"])
        assertEquals(0.025, region["left"] as Double, 0.0001)
        assertEquals(0.06666666666666667, region["top"] as Double, 0.0001)
    }

    private class FakeFocusTarget : RuntimeFocusControlTarget {
        var stagedFocusDistanceDiopters: Float? = null
        var stagedFocusMode: String = "auto"
        var autofocusEnabled: Boolean = true
        var stagedFocusRegion: MeteringRegion? = null
        var observedState: RuntimeObservedFocusState? = null

        override fun setFocusDistanceDiopters(focusDistanceDiopters: Float): Boolean {
            stagedFocusDistanceDiopters = focusDistanceDiopters
            stagedFocusMode = "fixed"
            autofocusEnabled = false
            return true
        }

        override fun setAutofocusEnabled(enabled: Boolean): Boolean {
            autofocusEnabled = enabled
            if (enabled) {
                stagedFocusMode = "auto"
            }
            return true
        }

        override fun setFocusMode(mode: String): Boolean {
            stagedFocusMode = mode
            autofocusEnabled = mode != "fixed" && mode != "infinity"
            if (mode == "infinity") {
                stagedFocusDistanceDiopters = 0f
            }
            stagedFocusRegion = null
            return true
        }

        override fun setFocusRegion(region: MeteringRegion): Boolean {
            stagedFocusRegion = region
            stagedFocusMode = "auto"
            autofocusEnabled = true
            stagedFocusDistanceDiopters = null
            return true
        }

        override fun getCurrentFocusDistanceDiopters(): Float? = stagedFocusDistanceDiopters

        override fun getCurrentFocusMode(): String = stagedFocusMode

        override fun isAutofocusEnabled(): Boolean = autofocusEnabled

        override fun getCurrentFocusRegion(): MeteringRegion? = stagedFocusRegion

        override fun getObservedFocusState(): RuntimeObservedFocusState? = observedState
    }
}
