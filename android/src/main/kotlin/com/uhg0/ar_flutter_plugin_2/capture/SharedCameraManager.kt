package com.uhg0.ar_flutter_plugin_2.capture

import android.content.Context
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import com.google.ar.core.Session

/// Complete configuration object parsed from ARCaptureConfig
data class ParsedCaptureConfig(
    val enableHighResCapture: Boolean,
    val captureIntervalMs: Int,
    val resolution: Size,
    val format: Int, // ImageFormat constant
    val maxCacheSize: Int,
    val jpegQuality: Int,
    val autoExposure: Boolean,
    val autoWhiteBalance: Boolean,
    val defaultISO: Int?,
    val defaultExposureTimeMicros: Long?,
    val enablePoseStream: Boolean,
    val bufferStrategy: String
)

class SharedCameraManager(
    private val context: Context,
    private val methodChannel: MethodChannel,
    private var session: Session?,
    private val configMap: Map<String, Any> // ARCaptureConfig.toMap() result
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Parsed configuration
    private val config: ParsedCaptureConfig = parseConfig(configMap)

    // Runtime components
    private var imageCacheManager: ImageCacheManager? = null
    private var automaticCaptureTimer: AutomaticCaptureTimer? = null
    private var runtimeCameraController: RuntimeCameraController? = null

    // State
    private var isInitialized = false
    private var isCapturing = false

    /// Initialize camera system with provided configuration
    fun initialize(cacheManager: ImageCacheManager) {
        if (isInitialized) {
            throw IllegalStateException("SharedCameraManager already initialized")
        }

        try {
            imageCacheManager = cacheManager

            startBackgroundThread()
            setupCameraBasedOnConfig()
            allocateBuffersBasedOnConfig()

            isInitialized = true
            Log.i("SharedCameraManager", "Initialized with config: $config")
        } catch (e: Exception) {
            cleanup()
            throw RuntimeException("Failed to initialize SharedCameraManager", e)
        }
    }

    /// Parse configuration map into typed configuration object
    private fun parseConfig(configMap: Map<String, Any>): ParsedCaptureConfig {
        try {
            val resolutionMap = configMap["resolution"] as Map<String, Any>
            val resolution = Size(
                (resolutionMap["width"] as Number).toInt(),
                (resolutionMap["height"] as Number).toInt()
            )

            val format = when (configMap["format"] as String) {
                "jpeg" -> android.graphics.ImageFormat.JPEG
                "raw" -> android.graphics.ImageFormat.RAW_SENSOR
                else -> android.graphics.ImageFormat.JPEG
            }

            return ParsedCaptureConfig(
                enableHighResCapture = configMap["enableHighResCapture"] as Boolean? ?: false,
                captureIntervalMs = (configMap["captureIntervalMs"] as Number?)?.toInt() ?: 5000,
                resolution = resolution,
                format = format,
                maxCacheSize = (configMap["maxCacheSize"] as Number?)?.toInt() ?: 10,
                jpegQuality = (configMap["jpegQuality"] as Number?)?.toInt() ?: 85,
                autoExposure = configMap["autoExposure"] as Boolean? ?: true,
                autoWhiteBalance = configMap["autoWhiteBalance"] as Boolean? ?: true,
                defaultISO = (configMap["defaultISO"] as Number?)?.toInt(),
                defaultExposureTimeMicros = (configMap["defaultExposureTime"] as Number?)?.toLong(),
                enablePoseStream = configMap["enablePoseStream"] as Boolean? ?: true,
                bufferStrategy = configMap["bufferStrategy"] as String? ?: "balanced"
            )
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid capture configuration", e)
        }
    }

    /// Setup camera based on configuration
    private fun setupCameraBasedOnConfig() {
        val cameraId = getBackFacingCameraId()
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        // Validate configuration against camera capabilities
        validateConfigurationAgainstCapabilities(characteristics)

        // Setup image reader with configuration
        imageReader = ImageReader.newInstance(
            config.resolution.width,
            config.resolution.height,
            config.format,
            getBufferCountForStrategy()
        ).apply {
            setOnImageAvailableListener({ reader ->
                handleImageAvailable(reader)
            }, backgroundHandler)
        }

        // Open camera
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                Log.e("SharedCameraManager", "Camera error: $error")
            }
        }, backgroundHandler)
    }

    /// Validate configuration against actual camera capabilities
    private fun validateConfigurationAgainstCapabilities(characteristics: CameraCharacteristics) {
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw RuntimeException("Camera configuration map not available")

        // Check if resolution is supported
        val supportedSizes = when (config.format) {
            android.graphics.ImageFormat.JPEG -> configMap.getOutputSizes(android.graphics.ImageFormat.JPEG)
            android.graphics.ImageFormat.RAW_SENSOR -> configMap.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
            else -> configMap.getOutputSizes(android.graphics.ImageFormat.JPEG)
        }

        val isResolutionSupported = supportedSizes?.any { size ->
            size.width == config.resolution.width && size.height == config.resolution.height
        } ?: false

        if (!isResolutionSupported) {
            throw RuntimeException("Resolution ${config.resolution} not supported for format ${config.format}")
        }

        // Validate ISO range if specified
        config.defaultISO?.let { iso ->
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            if (isoRange != null && (iso < isoRange.lower || iso > isoRange.upper)) {
                throw RuntimeException("ISO $iso not supported (range: ${isoRange.lower}-${isoRange.upper})")
            }
        }

        // Validate exposure time if specified
        config.defaultExposureTimeMicros?.let { exposureTimeMicros ->
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val exposureTimeNanos = exposureTimeMicros * 1000
            if (exposureRange != null && (exposureTimeNanos < exposureRange.lower || exposureTimeNanos > exposureRange.upper)) {
                throw RuntimeException("Exposure time ${exposureTimeMicros}μs not supported")
            }
        }

        Log.i("SharedCameraManager", "Configuration validated successfully")
    }

    /// Allocate buffers based on configuration strategy
    private fun allocateBuffersBasedOnConfig() {
        val bufferCount = getBufferCountForStrategy()
        val estimatedMemoryMB = estimateMemoryUsage()

        Log.i("SharedCameraManager", "Allocating $bufferCount buffers, estimated memory: ${estimatedMemoryMB}MB")

        // Configure cache manager with appropriate size
        imageCacheManager?.configure(config.maxCacheSize, estimatedMemoryMB.toInt())
    }

    /// Get buffer count based on strategy
    private fun getBufferCountForStrategy(): Int {
        return when (config.bufferStrategy) {
            "memory" -> 2
            "balanced" -> 3
            "performance" -> 5
            else -> 3
        }
    }

    /// Estimate memory usage in MB
    private fun estimateMemoryUsage(): Double {
        val pixelsPerImage = config.resolution.width * config.resolution.height
        val bytesPerPixel = when (config.format) {
            android.graphics.ImageFormat.JPEG -> 3 // RGB
            android.graphics.ImageFormat.RAW_SENSOR -> 2 // 16-bit
            else -> 3
        }
        val bytesPerImage = pixelsPerImage * bytesPerPixel
        val totalBytes = bytesPerImage * config.maxCacheSize

        return totalBytes / (1024.0 * 1024.0)
    }

    /// Create capture session with configured settings
    private fun createCaptureSession() {
        val cameraDevice = this.cameraDevice ?: return
        val imageReader = this.imageReader ?: return

        try {
            val surfaces = listOf(imageReader.surface)

            cameraDevice.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    configureCameraSettings()

                    // Start automatic capture if configured
                    if (config.captureIntervalMs > 0) {
                        startAutomaticCapture()
                    }

                    Log.i("SharedCameraManager", "Capture session configured successfully")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("SharedCameraManager", "Failed to configure capture session")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e("SharedCameraManager", "Failed to create capture session", e)
        }
    }

    /// Configure camera settings based on configuration
    private fun configureCameraSettings() {
        val captureSession = this.captureSession ?: return
        val cameraDevice = this.cameraDevice ?: return
        val imageReader = this.imageReader ?: return

        try {
            val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            requestBuilder.addTarget(imageReader.surface)

            // Configure exposure
            if (config.autoExposure) {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            } else {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                config.defaultISO?.let { iso ->
                    requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                }
                config.defaultExposureTimeMicros?.let { exposureTimeMicros ->
                    requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeMicros * 1000)
                }
            }

            // Configure white balance
            if (config.autoWhiteBalance) {
                requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            } else {
                requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            }

            // Configure JPEG quality
            if (config.format == android.graphics.ImageFormat.JPEG) {
                requestBuilder.set(CaptureRequest.JPEG_QUALITY, config.jpegQuality.toByte())
            }

            // Create runtime camera controller for dynamic settings
            runtimeCameraController = RuntimeCameraController(captureSession, requestBuilder)

            Log.i("SharedCameraManager", "Camera settings configured")
        } catch (e: Exception) {
            Log.e("SharedCameraManager", "Failed to configure camera settings", e)
        }
    }

    /// Start automatic capture based on configuration
    private fun startAutomaticCapture() {
        if (config.captureIntervalMs <= 0) return

        automaticCaptureTimer = AutomaticCaptureTimer(
            config.captureIntervalMs,
            backgroundHandler
        ) {
            captureImage()
        }
        automaticCaptureTimer?.start()

        Log.i("SharedCameraManager", "Automatic capture started (${config.captureIntervalMs}ms interval)")
    }

    /// Handle image available from camera
    private fun handleImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return

        try {
            val imageId = generateImageId()
            imageCacheManager?.cacheImage(imageId, image, config.format)

            // Notify Flutter side
            val resultMap = mapOf(
                "imageId" to imageId,
                "timestamp" to System.currentTimeMillis(),
                "isManualCapture" to false,
                "imageSize" to mapOf(
                    "width" to image.width,
                    "height" to image.height,
                    "bytesPerPixel" to getBytesPerPixel(),
                    "totalBytes" to estimateImageSize(image)
                )
            )

            methodChannel.invokeMethod("onAutomaticCapture", resultMap)
        } catch (e: Exception) {
            Log.e("SharedCameraManager", "Failed to handle captured image", e)
        } finally {
            image.close()
        }
    }

    /// Capture single image manually
    fun captureImage(): Boolean {
        val captureSession = this.captureSession ?: return false
        val cameraDevice = this.cameraDevice ?: return false
        val imageReader = this.imageReader ?: return false

        return try {
            val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            requestBuilder.addTarget(imageReader.surface)

            // Apply current runtime settings
            runtimeCameraController?.applyCurrentSettings(requestBuilder)

            captureSession.capture(requestBuilder.build(), null, backgroundHandler)
            true
        } catch (e: Exception) {
            Log.e("SharedCameraManager", "Failed to capture image", e)
            false
        }
    }

    /// Get current configuration
    fun getConfig(): Map<String, Any> {
        return mapOf(
            "enableHighResCapture" to config.enableHighResCapture,
            "captureIntervalMs" to config.captureIntervalMs,
            "resolution" to mapOf(
                "width" to config.resolution.width,
                "height" to config.resolution.height
            ),
            "format" to when (config.format) {
                android.graphics.ImageFormat.JPEG -> "jpeg"
                android.graphics.ImageFormat.RAW_SENSOR -> "raw"
                else -> "jpeg"
            },
            "maxCacheSize" to config.maxCacheSize,
            "jpegQuality" to config.jpegQuality,
            "bufferStrategy" to config.bufferStrategy
        )
    }

    /// Cleanup resources
    fun cleanup() {
        automaticCaptureTimer?.stop()
        automaticCaptureTimer = null

        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null

        imageReader?.close()
        imageReader = null

        stopBackgroundThread()

        isInitialized = false
        Log.i("SharedCameraManager", "Cleanup completed")
    }

    // Helper methods
    private fun getBackFacingCameraId(): String {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId
            }
        }
        return "0" // Fallback
    }

    private fun generateImageId(): String {
        return "img_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
    }

    private fun getBytesPerPixel(): Int {
        return when (config.format) {
            android.graphics.ImageFormat.JPEG -> 3
            android.graphics.ImageFormat.RAW_SENSOR -> 2
            else -> 3
        }
    }

    private fun estimateImageSize(image: android.media.Image): Int {
        return image.width * image.height * getBytesPerPixel()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("SharedCameraManager", "Error stopping background thread", e)
        }
    }
}