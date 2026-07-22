package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCameraSettingsStateTest {
    @Test
    fun `manual iso disables auto exposure and is reported`() {
        val state = RuntimeCameraSettingsState()

        state.setISO(400)

        assertEquals(400, state.getCurrentISO())
        assertFalse(state.isAutoExposureEnabled())
        assertEquals(400, state.getCurrentSettings()["iso"])
    }

    @Test
    fun `manual exposure stores microseconds and disables auto exposure`() {
        val state = RuntimeCameraSettingsState()

        state.setExposureTimeMicroseconds(1250)

        assertEquals(1250L, state.getCurrentExposureTimeMicros())
        assertFalse(state.isAutoExposureEnabled())
        assertEquals(1250L, state.getCurrentSettings()["exposureTimeMicros"])
    }

    @Test
    fun `re-enabling auto exposure clears manual iso and exposure`() {
        val state = RuntimeCameraSettingsState()
        state.setISO(800)
        state.setExposureTimeMicroseconds(2000)
        state.setExposureLocked(true)

        state.setAutoExposure(true)

        assertTrue(state.isAutoExposureEnabled())
        assertTrue(state.isExposureLocked())
        assertNull(state.getCurrentISO())
        assertNull(state.getCurrentExposureTimeMicros())
    }

    @Test
    fun `disabling auto exposure clears exposure lock but preserves compensation steps`() {
        val state = RuntimeCameraSettingsState()
        state.setExposureCompensationSteps(3)
        state.setExposureLocked(true)

        state.setAutoExposure(false)

        assertFalse(state.isAutoExposureEnabled())
        assertFalse(state.isExposureLocked())
        assertEquals(3, state.getCurrentExposureCompensationSteps())
        assertEquals(3, state.getCurrentSettings()["exposureCompensationSteps"])
    }

    @Test
    fun `resetToAutoMode clears all manual settings`() {
        val state = RuntimeCameraSettingsState()
        state.setISO(1600)
        state.setExposureTimeMicroseconds(3000)
        state.setFocusDistanceDiopters(1.5f)
        state.setAutoWhiteBalance(false)

        state.resetToAutoMode()

        assertTrue(state.isAutoExposureEnabled())
        assertTrue(state.isAutoWhiteBalanceEnabled())
        assertNull(state.getCurrentISO())
        assertNull(state.getCurrentExposureTimeMicros())
        assertNull(state.getCurrentFocusDistanceDiopters())
    }

    @Test
    fun `manual focus distance switches to fixed focus mode with autofocus disabled`() {
        val state = RuntimeCameraSettingsState()

        state.setFocusDistanceDiopters(2.0f)

        assertEquals(RuntimeFocusMode.fixed, state.getCurrentFocusMode())
        assertFalse(state.isAutofocusEnabled())
        assertEquals(2.0f, state.getCurrentFocusDistanceDiopters())
    }

    @Test
    fun `re-enabling autofocus restores auto focus mode from fixed focus`() {
        val state = RuntimeCameraSettingsState()
        state.setFocusDistanceDiopters(1.0f)

        state.setAutofocusEnabled(true)

        assertTrue(state.isAutofocusEnabled())
        assertEquals(RuntimeFocusMode.auto, state.getCurrentFocusMode())
    }

    @Test
    fun `white balance mode and lock are reported in current settings`() {
        val state = RuntimeCameraSettingsState()

        state.setWhiteBalanceMode(RuntimeWhiteBalanceMode.daylight)
        state.setWhiteBalanceLocked(true)

        assertEquals(RuntimeWhiteBalanceMode.daylight, state.getCurrentWhiteBalanceMode())
        assertTrue(state.isWhiteBalanceLocked())
        assertEquals("daylight", state.getCurrentSettings()["whiteBalanceMode"])
        assertEquals(true, state.getCurrentSettings()["whiteBalanceLocked"])
    }

    @Test
    fun `manual color temperature switches white balance mode and disables auto white balance`() {
        val state = RuntimeCameraSettingsState()

        state.setColorTemperatureKelvin(4200)

        assertEquals(RuntimeWhiteBalanceMode.manual, state.getCurrentWhiteBalanceMode())
        assertEquals(4200, state.getCurrentColorTemperatureKelvin())
        assertFalse(state.isAutoWhiteBalanceEnabled())
        assertEquals(4200, state.getCurrentSettings()["colorTemperatureK"])
    }

    @Test
    fun `tap focus and point white balance stage bounded regions in current settings`() {
        val state = RuntimeCameraSettingsState()

        state.setFocusRegion(MeteringRegion(left = 100, top = 200, width = 300, height = 400))
        state.setWhiteBalanceRegion(MeteringRegion(left = 500, top = 600, width = 700, height = 800))

        assertEquals(RuntimeFocusMode.auto, state.getCurrentFocusMode())
        assertTrue(state.isAutofocusEnabled())
        assertEquals(
            mapOf("left" to 100, "top" to 200, "width" to 300, "height" to 400),
            state.getCurrentSettings()["focusRegion"],
        )
        assertEquals(RuntimeWhiteBalanceMode.auto, state.getCurrentWhiteBalanceMode())
        assertTrue(state.isAutoWhiteBalanceEnabled())
        assertEquals(
            mapOf("left" to 500, "top" to 600, "width" to 700, "height" to 800),
            state.getCurrentSettings()["whiteBalanceRegion"],
        )
    }

    @Test
    fun `flash mode is reported in current settings`() {
        val state = RuntimeCameraSettingsState()

        state.setFlashMode(RuntimeFlashMode.redEyeReduction)

        assertEquals(RuntimeFlashMode.redEyeReduction, state.getCurrentFlashMode())
        assertEquals("redEyeReduction", state.getCurrentSettings()["flashMode"])
    }
}
