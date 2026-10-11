package com.uhg0.ar_flutter_plugin_2.capture

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeCameraControllerTest {
    @Test
    fun `ordinary autofocus chooses advertised continuous mode and survives unrelated controls`() {
        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, ordinaryAutofocusMode(intArrayOf(
            CaptureRequest.CONTROL_AF_MODE_AUTO, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)))
        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            ordinaryAutofocusMode(intArrayOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)))
        assertEquals(CaptureRequest.CONTROL_AF_MODE_AUTO, ordinaryAutofocusMode(intArrayOf(CaptureRequest.CONTROL_AF_MODE_AUTO)))
        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, ordinaryAutofocusMode(intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF)))
        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, ordinaryAutofocusMode(null))
        val still = mutableListOf<Int>()
        val repeating = mutableListOf<Int>()
        val stillRegions = mutableListOf<MeteringRegion?>()
        val repeatingRegions = mutableListOf<MeteringRegion?>()
        val triggers = mutableListOf<Int>()
        val controller = RuntimeCameraController(
            applySettingsToBaseRequest = {
                still += it.resolvedAutofocusMode()
                stillRegions += it.focusRegionForRequest()
            },
            onSettingsChanged = {
                repeating += it.resolvedAutofocusMode()
                repeatingRegions += it.focusRegionForRequest()
            },
            ordinaryAfMode = ordinaryAutofocusMode(intArrayOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)),
            regionAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            onFocusTrigger = { _, trigger -> triggers += trigger },
        )
        controller.initializeOrdinaryAutofocus()
        controller.setFlashMode(RuntimeFlashMode.torch)
        controller.setISO(400)
        controller.setExposureTime(1250)
        controller.resetToAutoMode()
        assertTrue(still.all { it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO })
        assertEquals(still, repeating)
        assertTrue(triggers.isEmpty())
        controller.setAutofocusEnabled(false)
        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, still.last())
        controller.setAutofocusEnabled(true)
        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, still.last())
        assertEquals(listOf(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL), triggers)
        controller.setAutofocusEnabled(true)
        assertEquals(1, triggers.size)
        assertTrue(stillRegions.all { it == null })
        assertEquals(stillRegions, repeatingRegions)
    }

    @Test
    fun `explicit focus region starts once and restoring continuous cancels once`() {
        val staged = mutableListOf<Int>()
        val live = mutableListOf<Int>()
        val triggers = mutableListOf<Pair<Int, Int>>()
        val controller = RuntimeCameraController(
            applySettingsToBaseRequest = { staged += it.resolvedAutofocusMode() },
            onSettingsChanged = { live += it.resolvedAutofocusMode() },
            ordinaryAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            onFocusTrigger = { state, trigger -> triggers += state.resolvedAutofocusMode() to trigger },
        )
        assertTrue(controller.setFocusRegion(MeteringRegion(10, 10, 20, 20, 500)))
        assertEquals(listOf(CaptureRequest.CONTROL_AF_MODE_AUTO to CaptureRequest.CONTROL_AF_TRIGGER_START), triggers)
        controller.setFlashMode(RuntimeFlashMode.torch)
        controller.setExposureTime(1250)
        controller.setISO(400)
        assertEquals(1, triggers.size)
        assertEquals(staged, live)
        assertTrue(staged.all { it == CaptureRequest.CONTROL_AF_MODE_AUTO })
        controller.resetToAutoMode()
        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, live.last())
        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE to CaptureRequest.CONTROL_AF_TRIGGER_CANCEL, triggers.last())
        controller.resetToAutoMode()
        assertEquals(2, triggers.size)
        assertNull(controller.getCurrentFocusRegion())
    }

    @Test
    fun `focus region request intent preserves startup and persists supported reset across builders`() {
        val clearTransitions = listOf<Pair<String, (RuntimeCameraController) -> Boolean>>(
            "autofocus disabled" to { it.setAutofocusEnabled(false) },
            "autofocus enabled" to { it.setAutofocusEnabled(true) },
            "ordinary auto" to { it.setFocusMode(RuntimeFocusMode.auto) },
            "ordinary continuous" to { it.setFocusMode(RuntimeFocusMode.continuous) },
            "manual distance" to { it.setFocusDistance(0.5f) },
            "infinity" to { it.setFocusMode(RuntimeFocusMode.infinity) },
            "fixed" to { it.setFocusMode(RuntimeFocusMode.fixed) },
            "reset to auto" to { it.resetToAutoMode() },
        )
        val explicitRegion = MeteringRegion(10, 10, 20, 20, 500)
        val resetRegion = MeteringRegion(0, 0, 1, 1, 0)
        for ((name, clearRegion) in clearTransitions) {
            val stillRegions = mutableListOf<MeteringRegion?>()
            val repeatingRegions = mutableListOf<MeteringRegion?>()
            val triggers = mutableListOf<Int>()
            val controller = RuntimeCameraController(
                applySettingsToBaseRequest = { stillRegions += it.focusRegionForRequest() },
                onSettingsChanged = { repeatingRegions += it.focusRegionForRequest() },
                onFocusTrigger = { _, trigger -> triggers += trigger },
                supportsAfMetering = true,
            )
            controller.setFlashMode(RuntimeFlashMode.off)
            assertEquals(name, listOf<MeteringRegion?>(null), stillRegions)
            assertEquals(name, stillRegions, repeatingRegions)
            assertTrue(name, controller.setFocusRegion(explicitRegion))
            assertEquals(name, explicitRegion, stillRegions.last())
            assertEquals(name, listOf(CaptureRequest.CONTROL_AF_TRIGGER_START), triggers)
            assertTrue(name, clearRegion(controller))
            controller.setISO(400)
            controller.resetToAutoMode()
            assertNull(name, controller.getCurrentFocusRegion())
            assertEquals(name, listOf(resetRegion, resetRegion, resetRegion), stillRegions.takeLast(3))
            assertEquals(name, stillRegions, repeatingRegions)
            val triggerCount = triggers.size
            assertTrue(name, controller.setFocusRegion(explicitRegion))
            assertEquals(name, explicitRegion, stillRegions.last())
            assertEquals(name, stillRegions, repeatingRegions)
            assertEquals(name, triggerCount + 1, triggers.size)
        }

        val unsupportedRegions = mutableListOf<MeteringRegion?>()
        val unsupportedTriggers = mutableListOf<Int>()
        val unsupported = RuntimeCameraController(
            applySettingsToBaseRequest = { unsupportedRegions += it.focusRegionForRequest() },
            onSettingsChanged = { unsupportedRegions += it.focusRegionForRequest() },
            onFocusTrigger = { _, trigger -> unsupportedTriggers += trigger },
            supportsAfMetering = false,
        )
        val initialSettings = unsupported.getCurrentSettings()
        assertFalse(unsupported.setFocusRegion(explicitRegion))
        assertEquals(initialSettings, unsupported.getCurrentSettings())
        assertTrue(unsupportedRegions.isEmpty())
        assertTrue(unsupportedTriggers.isEmpty())
        unsupported.setFlashMode(RuntimeFlashMode.torch)
        unsupported.resetToAutoMode()
        assertEquals(listOf<MeteringRegion?>(null, null, null, null), unsupportedRegions)
        assertTrue(unsupportedTriggers.isEmpty())
    }

    @Test
    fun `AUTO-only startup scans once while fixed fallback never submits focus triggers`() {
        val autoTriggers = mutableListOf<Int>()
        val auto = RuntimeCameraController({}, ordinaryAfMode = CaptureRequest.CONTROL_AF_MODE_AUTO,
            onFocusTrigger = { _, trigger -> autoTriggers += trigger })
        auto.initializeOrdinaryAutofocus()
        auto.initializeOrdinaryAutofocus()
        assertEquals(listOf(CaptureRequest.CONTROL_AF_TRIGGER_START), autoTriggers)
        auto.setFlashMode(RuntimeFlashMode.torch)
        auto.setISO(400)
        assertEquals(1, autoTriggers.size)
        auto.setAutofocusEnabled(false)
        auto.setAutofocusEnabled(true)
        assertEquals(listOf(CaptureRequest.CONTROL_AF_TRIGGER_START, CaptureRequest.CONTROL_AF_TRIGGER_START), autoTriggers)
        val fixedTriggers = mutableListOf<Int>()
        val fixed = RuntimeCameraController({}, ordinaryAfMode = CaptureRequest.CONTROL_AF_MODE_OFF,
            regionAfMode = CaptureRequest.CONTROL_AF_MODE_OFF,
            onFocusTrigger = { _, trigger -> fixedTriggers += trigger })
        fixed.initializeOrdinaryAutofocus()
        fixed.setAutofocusEnabled(true)
        fixed.resetToAutoMode()
        assertFalse(fixed.setFocusRegion(MeteringRegion(10, 10, 20, 20, 500)))
        assertFalse(fixed.isAutofocusEnabled())
        assertTrue(fixedTriggers.isEmpty())
    }

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
