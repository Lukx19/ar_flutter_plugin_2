package com.uhg0.ar_flutter_plugin_2.capture

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.util.Log
import kotlin.math.ln
import kotlin.math.pow

internal enum class RuntimeFocusMode {
    auto,
    macro,
    infinity,
    fixed,
    edof,
    continuous,
}

internal enum class RuntimeWhiteBalanceMode {
    auto,
    incandescent,
    fluorescent,
    warmFluorescent,
    daylight,
    cloudyDaylight,
    twilight,
    shade,
    manual,
}

internal enum class RuntimeFlashMode {
    off,
    auto,
    on,
    redEyeReduction,
    torch,
}

internal class RuntimeCameraSettingsState {
    private var currentISO: Int? = null
    private var currentExposureTimeNs: Long? = null
    private var currentExposureCompensationSteps: Int = 0
    private var currentFocusDistanceDiopters: Float? = null
    private var currentFocusMode: RuntimeFocusMode = RuntimeFocusMode.auto
    private var isAutofocusEnabled = true
    private var isAutoExposureEnabled = true
    private var isExposureLocked = false
    private var currentWhiteBalanceMode: RuntimeWhiteBalanceMode = RuntimeWhiteBalanceMode.auto
    private var currentColorTemperatureK: Int? = null
    private var isWhiteBalanceLocked = false
    private var currentFlashMode: RuntimeFlashMode = RuntimeFlashMode.off
    private var currentFocusRegion: MeteringRegion? = null
    private var currentWhiteBalanceRegion: MeteringRegion? = null

    fun applyCurrentSettings(requestBuilder: CaptureRequest.Builder) {
        if (isAutoExposureEnabled) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        } else {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            currentISO?.let { iso ->
                requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            }
            currentExposureTimeNs?.let { exposureTime ->
                requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
            }
        }
        requestBuilder.set(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            currentExposureCompensationSteps,
        )
        requestBuilder.set(
            CaptureRequest.CONTROL_AE_LOCK,
            isAutoExposureEnabled && isExposureLocked,
        )

        when (currentFlashMode) {
            RuntimeFlashMode.off -> {
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            RuntimeFlashMode.auto -> {
                requestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH,
                )
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            RuntimeFlashMode.on -> {
                requestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH,
                )
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            RuntimeFlashMode.redEyeReduction -> {
                requestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE,
                )
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            RuntimeFlashMode.torch -> {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
        }

        val colorTemperature = currentColorTemperatureK
        if (colorTemperature != null) {
            requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            requestBuilder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                kelvinToRggbGains(colorTemperature),
            )
            requestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        } else {
            val awbMode =
                when (currentWhiteBalanceMode) {
                    RuntimeWhiteBalanceMode.auto -> CaptureRequest.CONTROL_AWB_MODE_AUTO
                    RuntimeWhiteBalanceMode.incandescent ->
                        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                    RuntimeWhiteBalanceMode.fluorescent ->
                        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                    RuntimeWhiteBalanceMode.warmFluorescent ->
                        CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT
                    RuntimeWhiteBalanceMode.daylight -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                    RuntimeWhiteBalanceMode.cloudyDaylight ->
                        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                    RuntimeWhiteBalanceMode.twilight -> CaptureRequest.CONTROL_AWB_MODE_TWILIGHT
                    RuntimeWhiteBalanceMode.shade -> CaptureRequest.CONTROL_AWB_MODE_SHADE
                    RuntimeWhiteBalanceMode.manual -> CaptureRequest.CONTROL_AWB_MODE_OFF
                }
            requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
            requestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, isWhiteBalanceLocked)
        }

        val afMode =
            when {
                !isAutofocusEnabled -> CaptureRequest.CONTROL_AF_MODE_OFF
                currentFocusMode == RuntimeFocusMode.macro -> CaptureRequest.CONTROL_AF_MODE_MACRO
                currentFocusMode == RuntimeFocusMode.edof -> CaptureRequest.CONTROL_AF_MODE_EDOF
                currentFocusMode == RuntimeFocusMode.continuous ->
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                else -> CaptureRequest.CONTROL_AF_MODE_AUTO
            }
        requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        currentFocusRegion?.let { focusRegion ->
            requestBuilder.set(
                CaptureRequest.CONTROL_AF_REGIONS,
                arrayOf(focusRegion.toMeteringRectangle()),
            )
            requestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        }

        if (afMode == CaptureRequest.CONTROL_AF_MODE_OFF) {
            requestBuilder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                currentFocusDistanceDiopters ?: 0f,
            )
        }
        if (currentWhiteBalanceRegion != null && currentColorTemperatureK == null) {
            requestBuilder.set(
                CaptureRequest.CONTROL_AWB_REGIONS,
                arrayOf(currentWhiteBalanceRegion!!.toMeteringRectangle()),
            )
        }
    }

    fun setISO(iso: Int) {
        currentISO = iso
        isAutoExposureEnabled = false
    }

    fun setExposureTimeMicroseconds(exposureTimeMicros: Long) {
        currentExposureTimeNs = exposureTimeMicros * 1000
        isAutoExposureEnabled = false
    }

    fun setFocusDistanceDiopters(distance: Float) {
        currentFocusDistanceDiopters = distance
        currentFocusMode = RuntimeFocusMode.fixed
        isAutofocusEnabled = false
        currentFocusRegion = null
    }

    fun setAutoExposure(enabled: Boolean) {
        isAutoExposureEnabled = enabled
        if (enabled) {
            currentISO = null
            currentExposureTimeNs = null
        } else {
            isExposureLocked = false
        }
    }

    fun setExposureCompensationSteps(steps: Int) {
        currentExposureCompensationSteps = steps
    }

    fun setExposureLocked(locked: Boolean) {
        isExposureLocked = locked
    }

    fun setAutoWhiteBalance(enabled: Boolean) {
        currentColorTemperatureK = null
        currentWhiteBalanceMode = if (enabled) RuntimeWhiteBalanceMode.auto else RuntimeWhiteBalanceMode.daylight
    }

    fun setWhiteBalanceMode(mode: RuntimeWhiteBalanceMode) {
        currentColorTemperatureK = null
        currentWhiteBalanceMode = mode
        currentWhiteBalanceRegion = null
    }

    fun setColorTemperatureKelvin(colorTemperatureK: Int) {
        currentWhiteBalanceMode = RuntimeWhiteBalanceMode.manual
        currentColorTemperatureK = colorTemperatureK
        isWhiteBalanceLocked = false
        currentWhiteBalanceRegion = null
    }

    fun setWhiteBalanceLocked(locked: Boolean) {
        isWhiteBalanceLocked = locked
    }

    fun setFlashMode(mode: RuntimeFlashMode) {
        currentFlashMode = mode
    }

    fun setAutofocusEnabled(enabled: Boolean) {
        isAutofocusEnabled = enabled
        if (enabled && (currentFocusMode == RuntimeFocusMode.fixed || currentFocusMode == RuntimeFocusMode.infinity)) {
            currentFocusMode = RuntimeFocusMode.auto
        }
        if (!enabled && currentFocusMode == RuntimeFocusMode.auto) {
            currentFocusMode = RuntimeFocusMode.fixed
            if (currentFocusDistanceDiopters == null) {
                currentFocusDistanceDiopters = 0f
            }
        }
        if (!enabled) {
            currentFocusRegion = null
        }
    }

    fun setFocusMode(mode: RuntimeFocusMode) {
        currentFocusMode = mode
        when (mode) {
            RuntimeFocusMode.auto,
            RuntimeFocusMode.macro,
            RuntimeFocusMode.edof,
            RuntimeFocusMode.continuous -> {
                isAutofocusEnabled = true
                currentFocusDistanceDiopters = null
            }

            RuntimeFocusMode.infinity -> {
                isAutofocusEnabled = false
                currentFocusDistanceDiopters = 0f
                currentFocusRegion = null
            }

            RuntimeFocusMode.fixed -> {
                isAutofocusEnabled = false
                if (currentFocusDistanceDiopters == null) {
                    currentFocusDistanceDiopters = 0f
                }
                currentFocusRegion = null
            }
        }
    }

    fun setFocusRegion(region: MeteringRegion) {
        currentFocusRegion = region
        currentFocusMode = RuntimeFocusMode.auto
        isAutofocusEnabled = true
        currentFocusDistanceDiopters = null
    }

    fun setWhiteBalanceRegion(region: MeteringRegion) {
        currentWhiteBalanceRegion = region
        currentWhiteBalanceMode = RuntimeWhiteBalanceMode.auto
        currentColorTemperatureK = null
        isWhiteBalanceLocked = false
    }

    fun getCurrentISO(): Int? = currentISO

    fun getCurrentExposureTimeMicros(): Long? = currentExposureTimeNs?.let { it / 1000 }

    fun getCurrentExposureCompensationSteps(): Int = currentExposureCompensationSteps

    fun getCurrentFocusDistanceDiopters(): Float? = currentFocusDistanceDiopters

    fun getCurrentFocusMode(): RuntimeFocusMode = currentFocusMode

    fun getCurrentFocusRegion(): MeteringRegion? = currentFocusRegion

    fun isAutofocusEnabled(): Boolean = isAutofocusEnabled

    fun isAutoExposureEnabled(): Boolean = isAutoExposureEnabled

    fun isExposureLocked(): Boolean = isExposureLocked

    fun getCurrentWhiteBalanceMode(): RuntimeWhiteBalanceMode = currentWhiteBalanceMode

    fun getCurrentColorTemperatureKelvin(): Int? = currentColorTemperatureK

    fun getCurrentWhiteBalanceRegion(): MeteringRegion? = currentWhiteBalanceRegion

    fun isAutoWhiteBalanceEnabled(): Boolean = currentColorTemperatureK == null

    fun isWhiteBalanceLocked(): Boolean = isWhiteBalanceLocked

    fun getCurrentFlashMode(): RuntimeFlashMode = currentFlashMode

    fun getCurrentSettings(): Map<String, Any?> =
        mapOf(
            "iso" to currentISO,
            "exposureTimeMicros" to getCurrentExposureTimeMicros(),
            "focusDistanceDiopters" to currentFocusDistanceDiopters,
            "focusMode" to currentFocusMode.name,
            "focusRegion" to currentFocusRegion?.let { mapOf("left" to it.left, "top" to it.top, "width" to it.width, "height" to it.height) },
            "autofocusEnabled" to isAutofocusEnabled,
            "autoExposure" to isAutoExposureEnabled,
            "exposureCompensationSteps" to currentExposureCompensationSteps,
            "exposureLocked" to isExposureLocked,
            "whiteBalanceMode" to getCurrentWhiteBalanceMode().name,
            "colorTemperatureK" to currentColorTemperatureK,
            "whiteBalanceRegion" to currentWhiteBalanceRegion?.let { mapOf("left" to it.left, "top" to it.top, "width" to it.width, "height" to it.height) },
            "whiteBalanceLocked" to isWhiteBalanceLocked,
            "autoWhiteBalance" to isAutoWhiteBalanceEnabled(),
            "flashMode" to currentFlashMode.name,
        )

    fun resetToAutoMode() {
        currentISO = null
        currentExposureTimeNs = null
        currentFocusDistanceDiopters = null
        currentFocusMode = RuntimeFocusMode.auto
        isAutofocusEnabled = true
        currentFocusRegion = null
        isAutoExposureEnabled = true
        currentExposureCompensationSteps = 0
        isExposureLocked = false
        currentWhiteBalanceMode = RuntimeWhiteBalanceMode.auto
        currentColorTemperatureK = null
        currentWhiteBalanceRegion = null
        isWhiteBalanceLocked = false
        currentFlashMode = RuntimeFlashMode.off
    }

    private fun MeteringRegion.toMeteringRectangle(): MeteringRectangle =
        MeteringRectangle(left, top, width, height, weight)

    private fun kelvinToRggbGains(colorTemperatureK: Int): RggbChannelVector {
        val temperature = (colorTemperatureK.coerceIn(1000, 40000) / 100.0)

        val red =
            if (temperature <= 66.0) {
                255.0
            } else {
                329.698727446 * (temperature - 60.0).pow(-0.1332047592)
            }
        val green =
            if (temperature <= 66.0) {
                99.4708025861 * ln(temperature) - 161.1195681661
            } else {
                288.1221695283 * (temperature - 60.0).pow(-0.0755148492)
            }
        val blue =
            when {
                temperature >= 66.0 -> 255.0
                temperature <= 19.0 -> 0.0
                else -> 138.5177312231 * ln(temperature - 10.0) - 305.0447927307
            }

        val normalizedGreen = green.coerceIn(1.0, 255.0)
        val redGain = (red.coerceIn(0.0, 255.0) / normalizedGreen).toFloat().coerceIn(0.5f, 4.0f)
        val blueGain = (blue.coerceIn(0.0, 255.0) / normalizedGreen).toFloat().coerceIn(0.5f, 4.0f)
        return RggbChannelVector(redGain, 1.0f, 1.0f, blueGain)
    }
}

internal class RuntimeCameraController(
    private val applySettingsToBaseRequest: (RuntimeCameraSettingsState) -> Unit,
    private val onSettingsChanged: ((RuntimeCameraSettingsState) -> Unit)? = null,
) {
    private val state = RuntimeCameraSettingsState()

    constructor(
        baseRequestBuilder: CaptureRequest.Builder,
        onSettingsChanged: ((RuntimeCameraSettingsState) -> Unit)? = null,
    ) : this(
        applySettingsToBaseRequest = { runtimeState ->
            runtimeState.applyCurrentSettings(baseRequestBuilder)
        },
        onSettingsChanged = onSettingsChanged,
    )

    fun applyCurrentSettings(requestBuilder: CaptureRequest.Builder) {
        try {
            state.applyCurrentSettings(requestBuilder)
            Log.d(
                "RuntimeCameraController",
                "Applied runtime settings - ISO: ${state.getCurrentISO()}, Exposure: ${state.getCurrentExposureTimeMicros()}us",
            )
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to apply runtime settings", e)
        }
    }

    fun setISO(iso: Int): Boolean =
        try {
            state.setISO(iso)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set ISO to $iso")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set ISO to $iso", e)
            false
        }

    fun setExposureTime(exposureTimeMicros: Long): Boolean =
        try {
            state.setExposureTimeMicroseconds(exposureTimeMicros)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set exposure time to ${exposureTimeMicros}us")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set exposure time to ${exposureTimeMicros}us", e)
            false
        }

    fun setFocusDistance(distance: Float): Boolean =
        try {
            state.setFocusDistanceDiopters(distance)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set focus distance to $distance")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set focus distance to $distance", e)
            false
        }

    fun setAutoExposure(enabled: Boolean): Boolean =
        try {
            state.setAutoExposure(enabled)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set auto exposure to $enabled")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set auto exposure to $enabled", e)
            false
        }

    fun setExposureCompensation(steps: Int): Boolean =
        try {
            state.setExposureCompensationSteps(steps)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set exposure compensation steps to $steps")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set exposure compensation steps to $steps", e)
            false
        }

    fun setExposureLocked(locked: Boolean): Boolean =
        try {
            state.setExposureLocked(locked)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set exposure lock to $locked")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set exposure lock to $locked", e)
            false
        }

    fun setAutoWhiteBalance(enabled: Boolean): Boolean =
        try {
            state.setAutoWhiteBalance(enabled)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set auto white balance to $enabled")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set auto white balance to $enabled", e)
            false
        }

    fun setWhiteBalanceMode(mode: RuntimeWhiteBalanceMode): Boolean =
        try {
            state.setWhiteBalanceMode(mode)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set white balance mode to ${mode.name}")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set white balance mode to ${mode.name}", e)
            false
        }

    fun setColorTemperature(colorTemperatureK: Int): Boolean =
        try {
            state.setColorTemperatureKelvin(colorTemperatureK)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set color temperature to ${colorTemperatureK}K")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set color temperature to ${colorTemperatureK}K", e)
            false
        }

    fun setWhiteBalanceLocked(locked: Boolean): Boolean =
        try {
            state.setWhiteBalanceLocked(locked)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set white balance lock to $locked")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set white balance lock to $locked", e)
            false
        }

    fun setFlashMode(mode: RuntimeFlashMode): Boolean =
        try {
            state.setFlashMode(mode)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set flash mode to ${mode.name}")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set flash mode to ${mode.name}", e)
            false
        }

    fun setAutofocusEnabled(enabled: Boolean): Boolean =
        try {
            state.setAutofocusEnabled(enabled)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set autofocus to $enabled")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set autofocus to $enabled", e)
            false
        }

    fun setFocusMode(mode: RuntimeFocusMode): Boolean =
        try {
            state.setFocusMode(mode)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set focus mode to ${mode.name}")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set focus mode to ${mode.name}", e)
            false
        }

    fun setFocusRegion(region: MeteringRegion): Boolean =
        try {
            state.setFocusRegion(region)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set focus region to $region")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set focus region to $region", e)
            false
        }

    fun setWhiteBalanceRegion(region: MeteringRegion): Boolean =
        try {
            state.setWhiteBalanceRegion(region)
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set white balance region to $region")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set white balance region to $region", e)
            false
        }

    fun getCurrentISO(): Int? = state.getCurrentISO()

    fun getCurrentExposureTimeMicros(): Long? = state.getCurrentExposureTimeMicros()

    fun getCurrentExposureCompensationSteps(): Int = state.getCurrentExposureCompensationSteps()

    fun getCurrentFocusDistanceDiopters(): Float? = state.getCurrentFocusDistanceDiopters()

    fun getCurrentFocusMode(): RuntimeFocusMode = state.getCurrentFocusMode()

    fun getCurrentFocusRegion(): MeteringRegion? = state.getCurrentFocusRegion()

    fun isAutofocusEnabled(): Boolean = state.isAutofocusEnabled()

    fun isAutoExposureEnabled(): Boolean = state.isAutoExposureEnabled()

    fun isExposureLocked(): Boolean = state.isExposureLocked()

    fun isAutoWhiteBalanceEnabled(): Boolean = state.isAutoWhiteBalanceEnabled()

    fun getCurrentWhiteBalanceMode(): RuntimeWhiteBalanceMode = state.getCurrentWhiteBalanceMode()

    fun getCurrentColorTemperatureKelvin(): Int? = state.getCurrentColorTemperatureKelvin()

    fun getCurrentWhiteBalanceRegion(): MeteringRegion? = state.getCurrentWhiteBalanceRegion()

    fun isWhiteBalanceLocked(): Boolean = state.isWhiteBalanceLocked()

    fun getCurrentFlashMode(): RuntimeFlashMode = state.getCurrentFlashMode()

    fun getCurrentSettings(): Map<String, Any?> = state.getCurrentSettings()

    fun resetToAutoMode(): Boolean =
        try {
            state.resetToAutoMode()
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Reset to auto mode")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to reset to auto mode", e)
            false
        }

    private fun updateCameraSettings() {
        try {
            applySettingsToBaseRequest(state)
            onSettingsChanged?.invoke(state)
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to update camera settings", e)
        }
    }
}
