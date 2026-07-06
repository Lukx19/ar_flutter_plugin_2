package com.uhg0.ar_flutter_plugin_2.capture

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.util.Log

/// Controller for runtime camera settings adjustments
class RuntimeCameraController(
    private val captureSession: CameraCaptureSession,
    private val baseRequestBuilder: CaptureRequest.Builder
) {
    private var currentISO: Int? = null
    private var currentExposureTime: Long? = null
    private var currentFocusDistance: Float? = null
    private var isAutoExposureEnabled = true
    private var isAutoWhiteBalanceEnabled = true

    /// Apply current runtime settings to a capture request builder
    fun applyCurrentSettings(requestBuilder: CaptureRequest.Builder) {
        try {
            // Apply exposure settings
            if (isAutoExposureEnabled) {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            } else {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                currentISO?.let { iso ->
                    requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                }
                currentExposureTime?.let { exposureTime ->
                    requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
                }
            }

            // Apply white balance settings
            if (isAutoWhiteBalanceEnabled) {
                requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            } else {
                requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            }

            // Apply focus settings
            currentFocusDistance?.let { distance ->
                requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
            }

            Log.d("RuntimeCameraController", "Applied runtime settings - ISO: $currentISO, Exposure: $currentExposureTime")
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to apply runtime settings", e)
        }
    }

    /// Set ISO value for manual exposure control
    fun setISO(iso: Int): Boolean {
        return try {
            currentISO = iso
            isAutoExposureEnabled = false
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set ISO to $iso")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set ISO to $iso", e)
            false
        }
    }

    /// Set exposure time in microseconds for manual exposure control
    fun setExposureTime(exposureTimeMicros: Long): Boolean {
        return try {
            currentExposureTime = exposureTimeMicros * 1000 // Convert to nanoseconds
            isAutoExposureEnabled = false
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set exposure time to ${exposureTimeMicros}μs")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set exposure time to ${exposureTimeMicros}μs", e)
            false
        }
    }

    /// Set focus distance for manual focus control
    fun setFocusDistance(distance: Float): Boolean {
        return try {
            currentFocusDistance = distance
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set focus distance to $distance")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set focus distance to $distance", e)
            false
        }
    }

    /// Enable or disable automatic exposure
    fun setAutoExposure(enabled: Boolean): Boolean {
        return try {
            isAutoExposureEnabled = enabled
            if (enabled) {
                currentISO = null
                currentExposureTime = null
            }
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set auto exposure to $enabled")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set auto exposure to $enabled", e)
            false
        }
    }

    /// Enable or disable automatic white balance
    fun setAutoWhiteBalance(enabled: Boolean): Boolean {
        return try {
            isAutoWhiteBalanceEnabled = enabled
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Set auto white balance to $enabled")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to set auto white balance to $enabled", e)
            false
        }
    }

    /// Get current ISO setting
    fun getCurrentISO(): Int? = currentISO

    /// Get current exposure time in microseconds
    fun getCurrentExposureTimeMicros(): Long? = currentExposureTime?.let { it / 1000 }

    /// Get current focus distance
    fun getCurrentFocusDistance(): Float? = currentFocusDistance

    /// Check if auto exposure is enabled
    fun isAutoExposureEnabled(): Boolean = isAutoExposureEnabled

    /// Check if auto white balance is enabled
    fun isAutoWhiteBalanceEnabled(): Boolean = isAutoWhiteBalanceEnabled

    /// Get current camera settings as a map
    fun getCurrentSettings(): Map<String, Any?> {
        return mapOf(
            "iso" to currentISO,
            "exposureTimeMicros" to getCurrentExposureTimeMicros(),
            "focusDistance" to currentFocusDistance,
            "autoExposure" to isAutoExposureEnabled,
            "autoWhiteBalance" to isAutoWhiteBalanceEnabled
        )
    }

    /// Reset all settings to auto mode
    fun resetToAutoMode(): Boolean {
        return try {
            currentISO = null
            currentExposureTime = null
            currentFocusDistance = null
            isAutoExposureEnabled = true
            isAutoWhiteBalanceEnabled = true
            updateCameraSettings()
            Log.i("RuntimeCameraController", "Reset to auto mode")
            true
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to reset to auto mode", e)
            false
        }
    }

    /// Update camera settings with current configuration
    private fun updateCameraSettings() {
        try {
            val requestBuilder = captureSession.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            applyCurrentSettings(requestBuilder)
            
            // Set as repeating request to apply settings continuously
            captureSession.setRepeatingRequest(requestBuilder.build(), null, null)
        } catch (e: Exception) {
            Log.e("RuntimeCameraController", "Failed to update camera settings", e)
        }
    }
}