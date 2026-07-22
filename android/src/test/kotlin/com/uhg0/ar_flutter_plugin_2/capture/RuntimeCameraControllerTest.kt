package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeCameraControllerTest {
    @Test
    fun `manual exposure update stages still settings and notifies live sync callback`() {
        val stagedSnapshots = mutableListOf<Map<String, Any?>>()
        val liveSnapshots = mutableListOf<Map<String, Any?>>()
        val controller =
            RuntimeCameraController(
                applySettingsToBaseRequest = { state ->
                    stagedSnapshots += state.getCurrentSettings().toMap()
                },
                onSettingsChanged = { state ->
                    liveSnapshots += state.getCurrentSettings().toMap()
                },
            )

        controller.setISO(400)

        assertEquals(1, stagedSnapshots.size)
        assertEquals(1, liveSnapshots.size)
        assertEquals(400, stagedSnapshots.single()["iso"])
        assertEquals(400, liveSnapshots.single()["iso"])
        assertEquals(false, stagedSnapshots.single()["autoExposure"])
        assertEquals(false, liveSnapshots.single()["autoExposure"])
    }

    @Test
    fun `reset to auto mode notifies live sync after clearing manual state`() {
        val liveSnapshots = mutableListOf<Map<String, Any?>>()
        val controller =
            RuntimeCameraController(
                applySettingsToBaseRequest = { },
                onSettingsChanged = { state ->
                    liveSnapshots += state.getCurrentSettings().toMap()
                },
            )

        controller.setISO(800)
        controller.setExposureTime(2500)
        controller.resetToAutoMode()

        val finalSnapshot = liveSnapshots.last()
        assertEquals(true, finalSnapshot["autoExposure"])
        assertNull(finalSnapshot["iso"])
        assertNull(finalSnapshot["exposureTimeMicros"])
        assertFalse(controller.getCurrentSettings().containsKey("focusDistance"))
    }
}
