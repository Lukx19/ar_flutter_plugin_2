package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFlashControlBridgeTest {
    @Test
    fun `flash availability and staged state map are reported`() {
        val target = FakeFlashTarget()
        val bridge =
            RuntimeFlashControlBridge(
                capabilities =
                    RuntimeFlashCapabilities(
                        flashAvailable = true,
                        supportedModes = setOf("off", "auto", "on", "redEyeReduction", "torch"),
                    ),
                target = target,
            )

        bridge.setFlashMode("auto")
        val state = bridge.getCurrentFlashState()

        assertTrue(bridge.isFlashAvailable())
        assertEquals("auto", state["currentFlashMode"])
        assertEquals(true, state["isFlashReady"])
        assertEquals("ready", state["flashStatus"])
        assertEquals(false, state["isTorchEnabled"])
    }

    @Test
    fun `unsupported flash modes throw stable unsupported error`() {
        val bridge =
            RuntimeFlashControlBridge(
                capabilities =
                    RuntimeFlashCapabilities(
                        flashAvailable = true,
                        supportedModes = setOf("off", "auto", "torch"),
                    ),
                target = FakeFlashTarget(),
            )

        val error =
            runCatching {
                bridge.setFlashMode("redEyeReduction")
            }.exceptionOrNull()

        require(error is CaptureSessionException)
        assertEquals("CONTROL_UNSUPPORTED", error.code)
    }

    @Test
    fun `non-off flash requests on flashless hardware fall back to off`() {
        val target = FakeFlashTarget()
        val bridge =
            RuntimeFlashControlBridge(
                capabilities =
                    RuntimeFlashCapabilities(
                        flashAvailable = false,
                        supportedModes = setOf("off"),
                    ),
                target = target,
            )

        val applied = bridge.setFlashMode("on")

        assertTrue(applied)
        assertEquals("off", target.stagedFlashMode)
        assertFalse(bridge.isFlashAvailable())
    }

    @Test
    fun `torch alias stages torch mode and reports it in flash state`() {
        val target = FakeFlashTarget()
        val bridge =
            RuntimeFlashControlBridge(
                capabilities =
                    RuntimeFlashCapabilities(
                        flashAvailable = true,
                        supportedModes = setOf("off", "auto", "torch"),
                    ),
                target = target,
            )

        val enabled = bridge.setTorchEnabled(true)
        val torchState = bridge.getCurrentFlashState()
        val disabled = bridge.setTorchEnabled(false)
        val offState = bridge.getCurrentFlashState()

        assertTrue(enabled)
        assertEquals("torch", torchState["currentFlashMode"])
        assertEquals(true, torchState["isTorchEnabled"])
        assertTrue(disabled)
        assertEquals("off", offState["currentFlashMode"])
        assertEquals(false, offState["isTorchEnabled"])
    }

    @Test
    fun `current flash state prefers observed capture state when available`() {
        val target =
            FakeFlashTarget().apply {
                stagedFlashMode = "off"
                observedState =
                    RuntimeObservedFlashState(
                        currentFlashMode = "torch",
                        isTorchEnabled = true,
                        isFlashReady = false,
                        flashStatus = "charging",
                    )
            }
        val bridge =
            RuntimeFlashControlBridge(
                capabilities =
                    RuntimeFlashCapabilities(
                        flashAvailable = true,
                        supportedModes = setOf("off", "auto", "torch"),
                    ),
                target = target,
            )

        val state = bridge.getCurrentFlashState()

        assertEquals("torch", state["currentFlashMode"])
        assertEquals(true, state["isTorchEnabled"])
        assertEquals(false, state["isFlashReady"])
        assertEquals("charging", state["flashStatus"])
    }

    private class FakeFlashTarget : RuntimeFlashControlTarget {
        var stagedFlashMode: String = "off"
        var observedState: RuntimeObservedFlashState? = null

        override fun setFlashMode(mode: String): Boolean {
            stagedFlashMode = mode
            return true
        }

        override fun getCurrentFlashMode(): String = stagedFlashMode

        override fun getObservedFlashState(): RuntimeObservedFlashState? = observedState
    }
}
