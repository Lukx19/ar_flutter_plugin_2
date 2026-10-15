package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The runtime camera control replies shared with iOS (`CameraControlBridge`):
 * the state maps of the four Android bridges carry exactly the keys of
 * `test_assets/camera_controls/runtime_control_replies.json`, and the
 * Android mode enums have exactly its mode names.
 */
class RuntimeControlReplyContractTest {
    private val contract =
        Json.parseToJsonElement(
            File("../test_assets/camera_controls/runtime_control_replies.json").readText(),
        ).jsonObject

    private fun keys(method: String): Set<String> =
        contract["stateKeys"]!!.jsonObject[method]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun modes(kind: String): Set<String> =
        (contract["modeNames"]!!.jsonObject[kind] as JsonArray).map { it.jsonPrimitive.content }.toSet()

    @Test
    fun `android bridge state maps carry the shared contract keys`() {
        val exposure =
            RuntimeExposureControlBridge(
                RuntimeExposureCapabilities(100..3200, 10L..500_000L, -6..6, 1.0 / 3.0, true),
                ExposureTarget(),
            )
        assertEquals(keys("getCurrentExposureState"), exposure.getCurrentExposureState().keys)
        assertEquals(keys("getExposureCompensationInfo"), exposure.getExposureCompensationInfo().keys)
        assertEquals(keys("getSupportedExposureRange"), exposure.getSupportedExposureRange().keys)

        val focus =
            RuntimeFocusControlBridge(
                RuntimeFocusCapabilities(10f, setOf("auto", "continuous"), SensorArea(4000, 3000), true),
                FocusTarget(),
            )
        assertEquals(keys("getCurrentFocusState"), focus.getCurrentFocusState().keys)

        val whiteBalance =
            RuntimeWhiteBalanceControlBridge(
                RuntimeWhiteBalanceCapabilities(setOf("auto", "daylight"), 2000..8000, SensorArea(4000, 3000), true),
                WhiteBalanceTarget(),
            )
        assertEquals(keys("getCurrentWhiteBalanceState"), whiteBalance.getCurrentWhiteBalanceState().keys)
        assertEquals(keys("getSupportedColorTemperatureRange"), whiteBalance.getSupportedColorTemperatureRange().keys)

        val flash = RuntimeFlashControlBridge(RuntimeFlashCapabilities(true, setOf("off", "torch")), FlashTarget())
        assertEquals(keys("getCurrentFlashState"), flash.getCurrentFlashState().keys)
    }

    @Test
    fun `android mode enums equal the shared mode names`() {
        assertEquals(modes("focus"), RuntimeFocusMode.values().map { it.name }.toSet())
        assertEquals(modes("whiteBalance"), RuntimeWhiteBalanceMode.values().map { it.name }.toSet())
        assertEquals(modes("flash"), RuntimeFlashMode.values().map { it.name }.toSet())
    }

    private class ExposureTarget : RuntimeExposureControlTarget {
        override fun setISO(isoValue: Int, preservedExposureTimeMicros: Long?) = true
        override fun setExposureTime(exposureTimeMicros: Long) = true
        override fun setAutoExposureEnabled(enabled: Boolean) = true
        override fun getCurrentISO(): Int? = 200
        override fun getCurrentExposureTimeMicros(): Long? = 16_667
        override fun isAutoExposureEnabled() = true
        override fun setExposureCompensationSteps(steps: Int) = true
        override fun getCurrentExposureCompensationSteps() = 0
        override fun setExposureLocked(locked: Boolean) = true
        override fun isExposureLocked() = false
        override fun getObservedExposureState(): RuntimeObservedExposureState? = null
    }

    private class FocusTarget : RuntimeFocusControlTarget {
        override fun setFocusDistanceDiopters(focusDistanceDiopters: Float) = true
        override fun setAutofocusEnabled(enabled: Boolean) = true
        override fun setFocusMode(mode: String) = true
        override fun getCurrentFocusDistanceDiopters(): Float? = 5f
        override fun getCurrentFocusMode() = "auto"
        override fun isAutofocusEnabled() = true
        override fun setFocusRegion(region: MeteringRegion) = true
        override fun getCurrentFocusRegion(): MeteringRegion? = MeteringRegion(1700, 1275, 600, 450)
        override fun getObservedFocusState(): RuntimeObservedFocusState? = null
    }

    private class WhiteBalanceTarget : RuntimeWhiteBalanceControlTarget {
        override fun setWhiteBalanceMode(mode: String) = true
        override fun setWhiteBalanceLocked(locked: Boolean) = true
        override fun getCurrentWhiteBalanceMode() = "auto"
        override fun setColorTemperature(colorTemperatureK: Int) = true
        override fun getCurrentColorTemperature(): Int? = 5000
        override fun setWhiteBalanceRegion(region: MeteringRegion) = true
        override fun isWhiteBalanceLocked() = false
        override fun isAutoWhiteBalanceEnabled() = true
        override fun getObservedWhiteBalanceState(): RuntimeObservedWhiteBalanceState? = null
    }

    private class FlashTarget : RuntimeFlashControlTarget {
        override fun setFlashMode(mode: String) = true
        override fun getCurrentFlashMode() = "off"
        override fun getObservedFlashState(): RuntimeObservedFlashState? = null
    }
}
