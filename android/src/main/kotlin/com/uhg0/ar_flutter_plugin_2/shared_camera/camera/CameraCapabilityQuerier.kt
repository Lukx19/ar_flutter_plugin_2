package com.uhg0.ar_flutter_plugin_2.shared_camera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import android.util.SizeF
import kotlin.math.atan
import kotlin.math.abs

data class CameraResolution(val width: Int, val height: Int)

enum class ImageFormat {
    JPEG, RAW
}

class CameraCapabilityQuerier(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val capabilityCache = mutableMapOf<String, Any>()

    fun getSupportedResolutions(): List<CameraResolution> {
        val cacheKey = "supported_resolutions"
        if (capabilityCache.containsKey(cacheKey)) {
            @Suppress("UNCHECKED_CAST")
            return capabilityCache[cacheKey] as List<CameraResolution>
        }

        try {
            val cameraId = getDefaultCameraId()
            val characteristics = getCameraCharacteristics(cameraId)
            val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return emptyList()

            val jpegSizes = configMap.getOutputSizes(android.graphics.ImageFormat.JPEG) ?: emptyArray()
            val resolutions = jpegSizes.map { size ->
                CameraResolution(size.width, size.height)
            }.sortedByDescending { it.width * it.height }

            capabilityCache[cacheKey] = resolutions
            return resolutions
        } catch (e: Exception) {
            return emptyList()
        }
    }

    fun getSupportedFormats(): List<ImageFormat> {
        val cacheKey = "supported_formats"
        if (capabilityCache.containsKey(cacheKey)) {
            @Suppress("UNCHECKED_CAST")
            return capabilityCache[cacheKey] as List<ImageFormat>
        }

        try {
            val cameraId = getDefaultCameraId()
            val characteristics = getCameraCharacteristics(cameraId)
            val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return listOf(ImageFormat.JPEG)

            val supportedFormats = mutableListOf<ImageFormat>()

            // Check for JPEG support (should always be available)
            val jpegSizes = configMap.getOutputSizes(android.graphics.ImageFormat.JPEG)
            if (jpegSizes != null && jpegSizes.isNotEmpty()) {
                supportedFormats.add(ImageFormat.JPEG)
            }

            // Check for RAW support
            val rawSizes = configMap.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
            if (rawSizes != null && rawSizes.isNotEmpty()) {
                supportedFormats.add(ImageFormat.RAW)
            }

            capabilityCache[cacheKey] = supportedFormats
            return supportedFormats
        } catch (e: Exception) {
            return listOf(ImageFormat.JPEG) // Fallback to JPEG
        }
    }

    fun getSupportedISORange(): List<Int> {
        val cacheKey = "supported_iso_range"
        if (capabilityCache.containsKey(cacheKey)) {
            @Suppress("UNCHECKED_CAST")
            return capabilityCache[cacheKey] as List<Int>
        }

        try {
            val cameraId = getDefaultCameraId()
            val characteristics = getCameraCharacteristics(cameraId)
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?: return emptyList()

            val isoValues = generateISORange(isoRange.lower, isoRange.upper)
            capabilityCache[cacheKey] = isoValues
            return isoValues
        } catch (e: Exception) {
            return emptyList()
        }
    }

    fun getSupportedExposureRange(): Map<String, Long> {
        val cacheKey = "supported_exposure_range"
        if (capabilityCache.containsKey(cacheKey)) {
            @Suppress("UNCHECKED_CAST")
            return capabilityCache[cacheKey] as Map<String, Long>
        }

        try {
            val cameraId = getDefaultCameraId()
            val characteristics = getCameraCharacteristics(cameraId)
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?: return emptyMap()

            val rangeMap = mapOf(
                "min" to exposureRange.lower / 1000, // Convert nanoseconds to microseconds
                "max" to exposureRange.upper / 1000
            )

            capabilityCache[cacheKey] = rangeMap
            return rangeMap
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    fun isResolutionSupported(resolution: CameraResolution): Boolean {
        val supportedResolutions = getSupportedResolutions()
        return supportedResolutions.any {
            it.width == resolution.width && it.height == resolution.height
        }
    }

    fun isFormatSupported(format: ImageFormat): Boolean {
        val supportedFormats = getSupportedFormats()
        return supportedFormats.contains(format)
    }

    fun getDefaultCameraId(): String {
        val cacheKey = "default_camera_id"
        if (capabilityCache.containsKey(cacheKey)) {
            return capabilityCache[cacheKey] as String
        }

        try {
            val cameraIds = cameraManager.cameraIdList
            // Prefer back-facing camera for AR applications
            for (cameraId in cameraIds) {
                val characteristics = getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    capabilityCache[cacheKey] = cameraId
                    return cameraId
                }
            }

            // Fallback to first available camera
            val defaultId = cameraIds.firstOrNull() ?: "0"
            capabilityCache[cacheKey] = defaultId
            return defaultId
        } catch (e: Exception) {
            return "0" // Fallback
        }
    }

    fun getCameraIntrinsics(): Map<String, Any>? {
        val cacheKey = "camera_intrinsics"
        if (capabilityCache.containsKey(cacheKey)) {
            @Suppress("UNCHECKED_CAST")
            return capabilityCache[cacheKey] as? Map<String, Any>
        }

        return try {
            val cameraId = getDefaultCameraId()
            val characteristics = getCameraCharacteristics(cameraId)
            val intrinsics = extractUnifiedIntrinsics(characteristics)
            
            if (validateIntrinsicsData(intrinsics)) {
                capabilityCache[cacheKey] = intrinsics
                intrinsics
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getCameraIntrinsicsForSize(captureSize: Size): Map<String, Any>? {
        return try {
            val cameraId = getDefaultCameraId()
            val characteristics = getCameraCharacteristics(cameraId)
            extractUnifiedIntrinsicsForSize(characteristics, captureSize)
        } catch (e: Exception) {
            null
        }
    }

    private fun getCameraCharacteristics(cameraId: String): CameraCharacteristics {
        return cameraManager.getCameraCharacteristics(cameraId)
    }

    private fun generateISORange(min: Int, max: Int): List<Int> {
        val commonISOValues = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
        return commonISOValues.filter { it >= min && it <= max }
    }

    private fun clearCache() {
        capabilityCache.clear()
    }

    private fun extractUnifiedIntrinsics(characteristics: CameraCharacteristics): Map<String, Any> {
        // Extract focal length
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: floatArrayOf(1000.0f) // Default fallback
        val focalLength = focalLengths[0] // Use primary focal length

        // Get sensor size and active array size to calculate focal length in pixels
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        
        if (sensorSize == null || activeArraySize == null) {
            throw IllegalStateException("Cannot extract intrinsics: missing sensor information")
        }

        // Calculate focal length in pixels
        val fx = (focalLength / sensorSize.width) * activeArraySize.width()
        val fy = (focalLength / sensorSize.height) * activeArraySize.height()

        // Principal point (usually at image center)
        val cx = activeArraySize.width() / 2.0
        val cy = activeArraySize.height() / 2.0

        // Calculate field of view
        val fovH = 2.0 * atan(activeArraySize.width() / (2.0 * fx))
        val fovV = 2.0 * atan(activeArraySize.height() / (2.0 * fy))

        // Get distortion coefficients if available
        val distortionCoefficients = characteristics.get(CameraCharacteristics.LENS_DISTORTION)
            ?.toList() ?: listOf(0.0, 0.0, 0.0, 0.0, 0.0)

        return mapOf(
            "focalLength" to mapOf(
                "fx" to fx,
                "fy" to fy
            ),
            "principalPoint" to mapOf(
                "cx" to cx,
                "cy" to cy
            ),
            "resolution" to mapOf(
                "width" to activeArraySize.width(),
                "height" to activeArraySize.height()
            ),
            "distortionCoefficients" to distortionCoefficients,
            "fieldOfView" to mapOf(
                "horizontal" to fovH,
                "vertical" to fovV
            )
        )
    }

    private fun extractUnifiedIntrinsicsForSize(
        characteristics: CameraCharacteristics,
        captureSize: Size
    ): Map<String, Any> {
        // Similar to extractUnifiedIntrinsics but uses specific capture resolution
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: floatArrayOf(1000.0f)
        val focalLength = focalLengths[0]

        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: throw IllegalStateException("Sensor size not available")
        
        // Calculate focal length in pixels for specific capture size
        val fx = (focalLength / sensorSize.width) * captureSize.width
        val fy = (focalLength / sensorSize.height) * captureSize.height

        val cx = captureSize.width / 2.0
        val cy = captureSize.height / 2.0

        val fovH = 2.0 * atan(captureSize.width / (2.0 * fx))
        val fovV = 2.0 * atan(captureSize.height / (2.0 * fy))

        val distortionCoefficients = characteristics.get(CameraCharacteristics.LENS_DISTORTION)
            ?.toList() ?: listOf(0.0, 0.0, 0.0, 0.0, 0.0)

        return mapOf(
            "focalLength" to mapOf("fx" to fx, "fy" to fy),
            "principalPoint" to mapOf("cx" to cx, "cy" to cy),
            "resolution" to mapOf("width" to captureSize.width, "height" to captureSize.height),
            "distortionCoefficients" to distortionCoefficients,
            "fieldOfView" to mapOf("horizontal" to fovH, "vertical" to fovV)
        )
    }

    private fun validateIntrinsicsData(intrinsicsMap: Map<String, Any>): Boolean {
        return try {
            // Validate focal length
            val focalLengthMap = intrinsicsMap["focalLength"] as? Map<String, Any> ?: return false
            val fx = (focalLengthMap["fx"] as? Number)?.toDouble() ?: return false
            val fy = (focalLengthMap["fy"] as? Number)?.toDouble() ?: return false
            if (fx <= 0 || fy <= 0) return false

            // Validate principal point
            val principalPointMap = intrinsicsMap["principalPoint"] as? Map<String, Any> ?: return false
            val cx = (principalPointMap["cx"] as? Number)?.toDouble() ?: return false
            val cy = (principalPointMap["cy"] as? Number)?.toDouble() ?: return false
            if (cx < 0 || cy < 0) return false

            // Validate resolution
            val resolutionMap = intrinsicsMap["resolution"] as? Map<String, Any> ?: return false
            val width = (resolutionMap["width"] as? Number)?.toInt() ?: return false
            val height = (resolutionMap["height"] as? Number)?.toInt() ?: return false
            if (width <= 0 || height <= 0) return false

            // Validate field of view
            val fovMap = intrinsicsMap["fieldOfView"] as? Map<String, Any> ?: return false
            val fovH = (fovMap["horizontal"] as? Number)?.toDouble() ?: return false
            val fovV = (fovMap["vertical"] as? Number)?.toDouble() ?: return false
            if (fovH <= 0 || fovH >= kotlin.math.PI || fovV <= 0 || fovV >= kotlin.math.PI) return false

            // Check consistency between focal length, resolution, and field of view
            val expectedFx = width / (2.0 * kotlin.math.tan(fovH / 2.0))
            val expectedFy = height / (2.0 * kotlin.math.tan(fovV / 2.0))
            val fxError = abs(fx - expectedFx) / fx
            val fyError = abs(fy - expectedFy) / fy
            
            // Allow up to 5% error due to rounding
            if (fxError > 0.05 || fyError > 0.05) {
                return false
            }

            // Check if principal point is within reasonable bounds
            if (cx >= width || cy >= height) return false

            true
        } catch (e: Exception) {
            false
        }
    }
}