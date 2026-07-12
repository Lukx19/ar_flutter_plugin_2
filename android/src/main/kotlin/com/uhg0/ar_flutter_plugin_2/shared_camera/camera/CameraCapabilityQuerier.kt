package com.uhg0.ar_flutter_plugin_2.shared_camera.camera

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import kotlin.math.atan
import kotlin.math.abs

data class CameraResolution(val width: Int, val height: Int)

enum class ImageFormat {
    JPEG, RAW
}

class CameraCapabilityQuerier(private val context: Context) {

    companion object {
        const val CAPABILITY_PRESET_VERSION = 6
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val capabilityCache = mutableMapOf<String, Any>()
    private val preferences =
        context.getSharedPreferences("ar_camera_capabilities", Context.MODE_PRIVATE)

    fun getDeviceCapabilityProfile(): Map<String, Any?> {
        val primaryId = getDefaultCameraId()
        val fingerprint =
            listOf(Build.FINGERPRINT, primaryId, cameraManager.cameraIdList.joinToString(","))
                .joinToString("|")
        val cacheHit =
            preferences.getInt("profile_version", 0) == CAPABILITY_PRESET_VERSION &&
                preferences.getString("profile_fingerprint", null) == fingerprint
        val characteristics = getCameraCharacteristics(primaryId)
        val capabilities = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val rearConcurrentIds =
            if (cacheHit) {
                preferences.getString("profile_rear_concurrent_ids", "")
                    .orEmpty().split(',').filter(String::isNotBlank)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cameraManager.concurrentCameraIds
                    .filter { primaryId in it }
                    .flatten()
                    .filter { cameraId ->
                        cameraId != primaryId &&
                            getCameraCharacteristics(cameraId)
                                .get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_BACK
                    }
                    .distinct()
                    .sorted()
            } else {
                emptyList()
            }
        val validatedResolutions = getSupportedSharedCameraResolutions()
        val validatedRawJpegResolutions = getSupportedRawJpegResolutions()
        val probeStatus =
            if (cacheHit) {
                preferences.getString("profile_shared_camera_status", "pending").orEmpty()
            } else {
                "pending"
            }
        val rawCapture = cacheHit && preferences.contains("profile_raw_capture")
            .let { cached -> if (cached) preferences.getBoolean("profile_raw_capture", false)
                else capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) }
        val manualControls = cacheHit && preferences.contains("profile_manual_controls")
            .let { cached -> if (cached) preferences.getBoolean("profile_manual_controls", false)
                else capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) }
        val flash = if (cacheHit && preferences.contains("profile_flash")) {
            preferences.getBoolean("profile_flash", false)
        } else {
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }

        return mapOf(
            "fingerprint" to fingerprint,
            "presetVersion" to CAPABILITY_PRESET_VERSION,
            "primaryCameraId" to primaryId,
            "sharedResolutionValidationComplete" to
                preferences.contains(validatedResolutionKey(primaryId)),
            "sharedCameraProbeStatus" to probeStatus,
            "sharedCameraProbeError" to
                if (cacheHit) preferences.getString("profile_shared_camera_error", null) else null,
            "validatedSharedResolutions" to validatedResolutions.map {
                mapOf("width" to it.width, "height" to it.height)
            },
            "validatedRawJpegResolutions" to validatedRawJpegResolutions.map {
                mapOf("width" to it.width, "height" to it.height)
            },
            "rawJpegProbeStatus" to
                if (cacheHit) preferences.getString("profile_raw_jpeg_status", "pending").orEmpty()
                else "pending",
            "rawJpegProbeError" to
                if (cacheHit) preferences.getString("profile_raw_jpeg_error", null) else null,
            "validatedRawOnlyResolutions" to getSupportedRawOnlyResolutions().map {
                mapOf("width" to it.width, "height" to it.height)
            },
            "rawOnlyProbeStatus" to if (cacheHit) preferences.getString("profile_raw_only_status", "pending").orEmpty() else "pending",
            "rawOnlyProbeError" to if (cacheHit) preferences.getString("profile_raw_only_error", null) else null,
            "validatedPngResolutions" to getSupportedPngResolutions().map {
                mapOf("width" to it.width, "height" to it.height)
            },
            "pngProbeStatus" to if (cacheHit) preferences.getString("profile_png_status", "pending").orEmpty() else "pending",
            "rawCapture" to rawCapture,
            "manualSensorControls" to manualControls,
            "flash" to flash,
            // These are rear cameras advertised by Camera2 in a concurrent set
            // containing the primary rear camera. No manufacturer allowlist is used.
            "rearConcurrentCameraIds" to rearConcurrentIds,
            "rearConcurrentCamera" to rearConcurrentIds.isNotEmpty(),
            "cached" to cacheHit,
        ).also {
            val editor = preferences.edit()
                .putInt("profile_version", CAPABILITY_PRESET_VERSION)
                .putString("profile_fingerprint", fingerprint)
                .putBoolean("profile_raw_capture", rawCapture)
                .putBoolean("profile_manual_controls", manualControls)
                .putBoolean("profile_flash", flash)
                .putString("profile_rear_concurrent_ids", rearConcurrentIds.joinToString(","))
            if (!cacheHit) {
                editor.putString("profile_shared_camera_status", "pending")
                    .remove("profile_shared_camera_error")
                    .putString("profile_raw_jpeg_status", "pending")
                    .remove("profile_raw_jpeg_error")
                    .putString("profile_raw_only_status", "pending")
                    .remove("profile_raw_only_error")
                    .putString("profile_png_status", "pending")
            }
            editor.apply()
        }
    }

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

    fun getCameraIntrinsicsForSize(
        captureSize: Size,
        cropRegion: Rect? = null,
    ): Map<String, Any>? {
        return try {
            val cameraId = getDefaultCameraId()
            val characteristics = getCameraCharacteristics(cameraId)
            extractUnifiedIntrinsicsForSize(
                characteristics,
                captureSize,
                cropRegion,
            )
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
        val baseIntrinsics = extractBaseIntrinsics(characteristics)
        return CameraIntrinsicsDeriver.derive(
            baseIntrinsics = baseIntrinsics,
            outputSize = Size(
                baseIntrinsics.activeArrayWidth,
                baseIntrinsics.activeArrayHeight,
            ),
        )
    }

    /** Candidate hardware-JPEG sizes. These are not shared-session validated yet. */
    fun getSharedCameraResolutionCandidates(
        format: Int = android.graphics.ImageFormat.JPEG,
    ): List<CameraResolution> {
        val cacheKey = "shared_camera_resolution_candidates_$format"
        if (capabilityCache.containsKey(cacheKey)) {
            @Suppress("UNCHECKED_CAST")
            return capabilityCache[cacheKey] as List<CameraResolution>
        }

        return try {
            val cameraId = getDefaultCameraId()
            val characteristics = getCameraCharacteristics(cameraId)
            val configMap =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return emptyList()
            val resolutions =
                (configMap.getOutputSizes(format)
                    ?: emptyArray())
                    .map { size -> CameraResolution(size.width, size.height) }
                    .distinct()
                    .sortedByDescending { it.width.toLong() * it.height.toLong() }

            capabilityCache[cacheKey] = resolutions
            resolutions
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Sizes validated with CameraDevice.isSessionConfigurationSupported. */
    fun getSupportedSharedCameraResolutions(): List<CameraResolution> {
        val cameraId = getDefaultCameraId()
        val encoded =
            preferences.getString(validatedResolutionKey(cameraId), null)
                ?: return emptyList()
        return encoded
            .split(',')
            .mapNotNull { value ->
                val dimensions = value.split('x')
                if (dimensions.size != 2) return@mapNotNull null
                val width = dimensions[0].toIntOrNull() ?: return@mapNotNull null
                val height = dimensions[1].toIntOrNull() ?: return@mapNotNull null
                CameraResolution(width, height)
            }
            .distinct()
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
    }

    fun saveSupportedSharedCameraResolutions(resolutions: List<CameraResolution>) {
        val cameraId = getDefaultCameraId()
        val encoded =
            resolutions
                .distinct()
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
                .joinToString(",") { "${it.width}x${it.height}" }
        preferences.edit()
            .putString(validatedResolutionKey(cameraId), encoded)
            .putString("profile_shared_camera_status", "supported")
            .remove("profile_shared_camera_error")
            .apply()
    }

    fun saveSharedCameraUnsupported(reason: String) {
        preferences.edit()
            .putString("profile_shared_camera_status", "unsupported")
            .putString("profile_shared_camera_error", reason)
            .apply()
    }

    fun getSupportedRawJpegResolutions(): List<CameraResolution> =
        decodeResolutions(preferences.getString(rawJpegResolutionKey(getDefaultCameraId()), null))

    fun saveSupportedRawJpegResolutions(resolutions: List<CameraResolution>) {
        preferences.edit()
            .putString(rawJpegResolutionKey(getDefaultCameraId()), encodeResolutions(resolutions))
            .apply()
    }

    fun saveRawJpegProbeResult(supported: Boolean, reason: String?) {
        val editor = preferences.edit().putString(
            "profile_raw_jpeg_status",
            if (supported) "supported" else "unsupported",
        )
        if (reason == null) editor.remove("profile_raw_jpeg_error")
        else editor.putString("profile_raw_jpeg_error", reason)
        editor.apply()
    }

    fun getSupportedRawOnlyResolutions() =
        decodeResolutions(preferences.getString(rawOnlyResolutionKey(getDefaultCameraId()), null))

    fun saveSupportedRawOnlyResolutions(resolutions: List<CameraResolution>) {
        preferences.edit().putString(
            rawOnlyResolutionKey(getDefaultCameraId()), encodeResolutions(resolutions),
        ).putString("profile_raw_only_status", "supported").remove("profile_raw_only_error").apply()
    }

    fun getSupportedPngResolutions() =
        decodeResolutions(preferences.getString(pngResolutionKey(getDefaultCameraId()), null))

    fun saveSupportedPngResolutions(resolutions: List<CameraResolution>) {
        preferences.edit().putString(
            pngResolutionKey(getDefaultCameraId()), encodeResolutions(resolutions),
        ).putString("profile_png_status", if (resolutions.isEmpty()) "unsupported" else "supported")
            .remove("profile_png_error").apply()
    }

    fun saveFormatProbeResult(format: String, supported: Boolean, reason: String?) {
        require(format == "raw_only" || format == "png")
        val editor = preferences.edit().putString(
            "profile_${format}_status", if (supported) "supported" else "unsupported",
        )
        if (reason == null) editor.remove("profile_${format}_error")
        else editor.putString("profile_${format}_error", reason)
        editor.apply()
    }

    private fun encodeResolutions(resolutions: List<CameraResolution>) =
        resolutions.distinct()
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
            .joinToString(",") { "${it.width}x${it.height}" }

    private fun decodeResolutions(encoded: String?): List<CameraResolution> =
        encoded.orEmpty().split(',').mapNotNull { value ->
            val dimensions = value.split('x')
            if (dimensions.size != 2) return@mapNotNull null
            CameraResolution(
                dimensions[0].toIntOrNull() ?: return@mapNotNull null,
                dimensions[1].toIntOrNull() ?: return@mapNotNull null,
            )
        }.distinct().sortedByDescending { it.width.toLong() * it.height.toLong() }

    private fun validatedResolutionKey(cameraId: String) =
        "validated_shared_resolutions_v${CAPABILITY_PRESET_VERSION}_${Build.FINGERPRINT}_$cameraId"

    private fun rawJpegResolutionKey(cameraId: String) =
        "validated_raw_jpeg_resolutions_v${CAPABILITY_PRESET_VERSION}_${Build.FINGERPRINT}_$cameraId"

    private fun rawOnlyResolutionKey(cameraId: String) =
        "validated_raw_only_resolutions_v${CAPABILITY_PRESET_VERSION}_${Build.FINGERPRINT}_$cameraId"

    private fun pngResolutionKey(cameraId: String) =
        "validated_png_resolutions_v${CAPABILITY_PRESET_VERSION}_${Build.FINGERPRINT}_$cameraId"

    private fun extractUnifiedIntrinsicsForSize(
        characteristics: CameraCharacteristics,
        captureSize: Size,
        cropRegion: Rect? = null,
    ): Map<String, Any> {
        return CameraIntrinsicsDeriver.derive(
            baseIntrinsics = extractBaseIntrinsics(characteristics),
            outputSize = captureSize,
            cropRegion = cropRegion,
        )
    }

    private fun extractBaseIntrinsics(
        characteristics: CameraCharacteristics,
    ): BaseCameraIntrinsics {
        val focalLengths =
            characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?: floatArrayOf(1000.0f)
        val focalLength = focalLengths[0]
        val sensorSize =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?: throw IllegalStateException("Sensor size not available")
        val activeArraySize =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: throw IllegalStateException("Active array size not available")
        val lensCalibration =
            characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val distortionCoefficients =
            characteristics.get(CameraCharacteristics.LENS_DISTORTION)?.toList()
                ?: listOf(0.0, 0.0, 0.0, 0.0, 0.0)
        val fx =
            ((focalLength / sensorSize.width) * activeArraySize.width()).toDouble()
        val fy =
            ((focalLength / sensorSize.height) * activeArraySize.height()).toDouble()
        val cx = lensCalibration?.getOrNull(2)?.toDouble()
            ?: activeArraySize.width() / 2.0
        val cy = lensCalibration?.getOrNull(3)?.toDouble()
            ?: activeArraySize.height() / 2.0

        return BaseCameraIntrinsics(
            fx = fx,
            fy = fy,
            cx = cx,
            cy = cy,
            activeArrayWidth = activeArraySize.width(),
            activeArrayHeight = activeArraySize.height(),
            distortionCoefficients = distortionCoefficients.map { it.toDouble() },
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
