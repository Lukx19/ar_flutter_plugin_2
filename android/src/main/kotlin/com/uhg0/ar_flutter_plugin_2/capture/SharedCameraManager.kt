package com.uhg0.ar_flutter_plugin_2.capture

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.DngCreator
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import com.google.ar.core.Session
import com.google.ar.core.SharedCamera
import com.uhg0.ar_flutter_plugin_2.shared_camera.camera.CameraCapabilityQuerier
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/// Complete configuration object parsed from ARCaptureConfig
data class ParsedCaptureConfig(
    val enableHighResCapture: Boolean,
    val captureIntervalMs: Int,
    val resolution: Size,
    val format: Int,
    val maxCacheSize: Int,
    val jpegQuality: Int,
    val autoExposure: Boolean,
    val autoWhiteBalance: Boolean,
    val defaultISO: Int?,
    val defaultExposureTimeMicros: Long?,
    val enablePoseStream: Boolean,
    val bufferStrategy: String,
    val rawJpeg: Boolean = false,
    val processed: Boolean = false,
){
    companion object {
        fun fromMap(configMap: Map<String, Any>): ParsedCaptureConfig {
            try {
                @Suppress("UNCHECKED_CAST")
                val resolutionMap = configMap["resolution"] as Map<String, Any>
                val resolution =
                    Size(
                        (resolutionMap["width"] as Number).toInt(),
                        (resolutionMap["height"] as Number).toInt(),
                    )

                val formatName = configMap["format"] as? String
                    ?: throw CaptureSessionException(
                        "UNSUPPORTED_CAPTURE_FORMAT",
                        "A capture format is required",
                    )
                val rawJpeg = formatName == "raw+jpeg"
                val format =
                    when (formatName) {
                        "jpeg" -> android.graphics.ImageFormat.JPEG
                        "raw+jpeg" -> android.graphics.ImageFormat.JPEG
                        else -> throw CaptureSessionException(
                            "UNSUPPORTED_CAPTURE_FORMAT",
                            "Supported formats are jpeg and raw+jpeg; received $formatName",
                        )
                    }

                return ParsedCaptureConfig(
                    enableHighResCapture = configMap["enableHighResCapture"] as Boolean? ?: false,
                    captureIntervalMs = (configMap["captureIntervalMs"] as Number?)?.toInt() ?: 5000,
                    resolution = resolution,
                    format = format,
                    maxCacheSize = (configMap["maxCacheSize"] as Number?)?.toInt() ?: 10,
                    jpegQuality = (configMap["jpegQuality"] as Number?)?.toInt() ?: 95,
                    autoExposure = configMap["autoExposure"] as Boolean? ?: true,
                    autoWhiteBalance = configMap["autoWhiteBalance"] as Boolean? ?: true,
                    defaultISO = (configMap["defaultISO"] as Number?)?.toInt(),
                    defaultExposureTimeMicros = (configMap["defaultExposureTime"] as Number?)?.toLong(),
                    enablePoseStream = configMap["enablePoseStream"] as Boolean? ?: true,
                    bufferStrategy = configMap["bufferStrategy"] as String? ?: "balanced",
                    rawJpeg = rawJpeg,
                    processed = false,
                )
            } catch (e: CaptureSessionException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid capture configuration", e)
            }
        }
    }
}

data class SharedCameraCaptureResult(
    val reservationToken: String,
    val imageId: String,
    val imageBytes: ByteArray,
    val format: Int,
    val width: Int,
    val height: Int,
    val imageSizeBytes: Int,
    val captureTimestampMs: Long,
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long,
    val rollingShutterSkewNs: Long,
    val observedTimestampNs: Long? = null,
    val intrinsics: Map<String, Any>?,
    val rawDngEncoder: (() -> ByteArray)? = null,
    val closeRawImage: (() -> Unit)? = null,
    val rawWidth: Int? = null,
    val rawHeight: Int? = null,
    val primaryAssetName: String = "jpeg",
    val preEncodeQuality: Map<String, Any>? = null,
    val preAlignedPose: PoseDataExtractor.AlignedPose? = null,
)

internal class SharedBlurRejectedException(val quality: Map<String, Any>) :
    RuntimeException("Capture rejected by the pre-encode blur filter")

internal data class SharedCaptureAccepted(
    val imageId: String,
    val width: Int,
    val height: Int,
    val format: String,
    val captureTimestampMs: Long,
    val alignedPose: PoseDataExtractor.AlignedPose,
    val quality: Map<String, Any>?,
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long,
    val rollingShutterSkewNs: Long,
    val intrinsics: Map<String, Any>?,
)

internal class SharedCameraManager(
    private val context: Context,
    private val methodChannel: MethodChannel,
    private var session: Session?,
    private val cameraTextureIds: () -> IntArray,
    private val prepareSessionResume: (Session) -> Unit,
    private val scenePreviewSurface: Surface,
    private val configMap: Map<String, Any>,
    private val onObservedCaptureStateChanged: (RuntimeObservedCaptureState) -> Unit = {},
    private val resolvePoseBeforeEncoding:
        (PoseDataExtractor.CaptureTiming) -> PoseDataExtractor.AlignedPose? = { null },
    private val onCaptureAccepted: (SharedCaptureAccepted) -> Unit = {},
    private val onCaptureEncoded:
        (SharedCameraCaptureResult, CaptureQualityPolicy?) -> Unit = { _, _ -> },
    private val onCaptureFinalizationFailed: (String, Throwable) -> Unit = { _, _ -> },
    private val resourceCounters: CaptureResourceCounters = CaptureResourceCounters(),
) {
    companion object {
        private const val StartupTimeoutMs = 5000L
        private const val ManualCaptureTimeoutMs = 5000L
        private const val ManualCaptureRequestTagPrefix = "capture3d_manual_shared_still"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val capabilityQuerier = CameraCapabilityQuerier(context)
    private val stillCaptureCorrelator = PendingStillCaptureCorrelator(timeoutMs = 30_000L)
    private val processedFrameCorrelator = ProcessedFrameCorrelator<PackedYuv420>()
    private val startupBarrier = SharedCameraStartupBarrier()
    private val repeatingRequestLifecycle = SharedCameraRepeatingRequestLifecycle()
    private val rawJpegCaptureCorrelator =
        RawJpegCaptureCorrelator<Image, TotalCaptureResult>(::closeTrackedImage)
    private val captureObservationLock = Any()
    private val captureObservedTimestampsNs = linkedMapOf<Long, Long>()
    private val sharedCameraCaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long,
            ) {
                val pending = pendingManualCapture ?: return
                val requestTag = request.tag as? ManualCaptureTag ?: return
                if (requestTag.generation != pending.generation ||
                    !requestGeneration.isCurrent(requestTag.generation)
                ) {
                    return
                }
                recordCaptureObservation(timestamp, System.nanoTime())
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (pendingManualCapture != null) {
                    Log.i(
                        "SharedCameraManager",
                        "Capture result frame=${result.frameNumber} tag=${request.tag}",
                    )
                }
                val pending = pendingManualCapture ?: return
                val requestTag = request.tag as? ManualCaptureTag ?: return
                if (requestTag.generation != pending.generation ||
                    !requestGeneration.isCurrent(requestTag.generation)
                ) {
                    Log.w(
                        "SharedCameraManager",
                        "Dropping late capture result generation=${requestTag.generation}; active=${pending.generation}",
                    )
                    return
                }
                observedCaptureState = buildObservedCaptureState(result).also(
                    onObservedCaptureStateChanged,
                )
                val sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                val observedTimestampNs = captureObservation(sensorTimestampNs)
                    ?: System.nanoTime().also {
                        recordCaptureObservation(sensorTimestampNs, it)
                    }
                if (config.rawJpeg) {
                    handleRawJpegCaptureResult(sensorTimestampNs, result)
                    return
                }
                if (config.processed) {
                    processedFrameCorrelator.onResult(
                        PendingStillResultMetadata(
                            frameNumber = result.frameNumber,
                            sensorTimestampNs = sensorTimestampNs,
                            exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                            rollingShutterSkewNs =
                                result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L,
                            cropRegion = result.get(CaptureResult.SCALER_CROP_REGION),
                            receivedAtMs = System.currentTimeMillis(),
                            observedTimestampNs = observedTimestampNs,
                        ),
                    )?.let(::handleCorrelatedProcessedFrame)
                    return
                }
                stillCaptureCorrelator.onCaptureResult(
                    frameNumber = result.frameNumber,
                    sensorTimestampNs = sensorTimestampNs,
                    exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                    rollingShutterSkewNs =
                        result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L,
                    cropRegion = result.get(CaptureResult.SCALER_CROP_REGION),
                    observedTimestampNs = observedTimestampNs,
                )?.let(::handleCorrelatedStillCapture)
            }
        }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewImageReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null
    private var activeCameraCharacteristics: CameraCharacteristics? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var captureCallbackThread: HandlerThread? = null
    private var captureCallbackHandler: Handler? = null
    private val finalizationWorkersDelegate = lazy {
        val transientBytes =
            config.resolution.width.toLong() * config.resolution.height.toLong() * 7L
        val workers =
            CaptureFinalizationWorkerPool.recommendedWorkerCount(
                availableProcessors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                transientBytesPerJob = transientBytes.coerceAtLeast(1L),
                memoryBudgetBytes = 256L * 1024L * 1024L,
            )
        CaptureFinalizationWorkerPool(workerCount = workers, queueCapacity = workers)
    }
    private val finalizationWorkers by finalizationWorkersDelegate

    private val config: ParsedCaptureConfig = ParsedCaptureConfig.fromMap(configMap)

    private var imageCacheManager: ImageCacheManager? = null
    private var runtimeCameraController: RuntimeCameraController? = null
    private var capabilityReport: SharedCaptureCapabilityReport? = null
    private var previewCaptureRequestBuilder: CaptureRequest.Builder? = null
    private var manualCaptureRequestBuilder: CaptureRequest.Builder? = null
    @Volatile
    private var arCoreResumedForSharedSession = false
    private var sceneViewResumeReconfigurationDisabled = false
    @Volatile
    private var observedCaptureState: RuntimeObservedCaptureState? = null
    @Volatile
    private var cleanupRequested = false
    @Volatile
    private var captureSessionClosed = false
    @Volatile
    private var cameraDeviceClosed = false
    @Volatile
    private var cameraCloseLatch = CountDownLatch(1)

    private var isInitialized = false
    private val requestGeneration = CaptureRequestGeneration()
    private val pendingManualCaptureOwner = CaptureAttemptOwner<PendingManualCapture>()
    private val pendingManualCapture: PendingManualCapture?
        get() = pendingManualCaptureOwner.get()

    private fun recordCaptureObservation(sensorTimestampNs: Long, observedTimestampNs: Long) {
        synchronized(captureObservationLock) {
            captureObservedTimestampsNs[sensorTimestampNs] = observedTimestampNs
            while (captureObservedTimestampsNs.size > 32) {
                captureObservedTimestampsNs.remove(captureObservedTimestampsNs.entries.first().key)
            }
        }
    }

    private fun captureObservation(sensorTimestampNs: Long): Long? =
        synchronized(captureObservationLock) {
            captureObservedTimestampsNs[sensorTimestampNs]
        }

    private data class PendingManualCapture(
        val latch: CountDownLatch,
        val reservationToken: String,
        val qualityPolicy: CaptureQualityPolicy?,
        val generation: Long,
        @Volatile var result: SharedCameraCaptureResult? = null,
        @Volatile var error: Throwable? = null,
        @Volatile var preEncodeQuality: Map<String, Any>? = null,
        @Volatile var preAlignedPose: PoseDataExtractor.AlignedPose? = null,
        @Volatile var accepted: SharedCaptureAccepted? = null,
    )

    private data class ManualCaptureTag(val generation: Long) {
        override fun toString(): String = "$ManualCaptureRequestTagPrefix:$generation"
    }

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
            Log.e("SharedCameraManager", "Shared-camera startup failed", e)
            throw RuntimeException("Failed to initialize SharedCameraManager", e)
        }
    }

    private fun requireSharedCamera(): SharedCamera =
        session?.sharedCamera
            ?: throw CaptureSessionException(
                code = "SHARED_CAMERA_UNSUPPORTED",
                message = "ARCore shared camera is not available on the active session",
            )

    private fun setupCameraBasedOnConfig() {
        val sharedCamera = requireSharedCamera()
        pauseArSessionForSharedCameraSetup()
        bindSingleSceneViewCameraTexture()
        val cameraId =
            SharedCameraInteropPlanner.resolveCameraId(
                sharedCameraId = session?.cameraConfig?.cameraId,
            )
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        activeCameraCharacteristics = characteristics
        validateConfigurationAgainstCapabilities(characteristics)
        val effectiveResolution = getEffectiveResolution()

        previewImageReader =
            ImageReader.newInstance(
                effectiveResolution.width,
                effectiveResolution.height,
                config.format,
                2,
            ).apply {
                setOnImageAvailableListener({ reader ->
                    handlePreviewImageAvailable(reader)
                }, captureCallbackHandler)
            }
        if (config.rawJpeg) {
            val rawSize = selectRawResolution(characteristics)
            rawImageReader =
                ImageReader.newInstance(
                    rawSize.width,
                    rawSize.height,
                    ImageFormat.RAW_SENSOR,
                    2,
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        handleRawImageAvailable(reader)
                    }, captureCallbackHandler)
                }
        }
        // Keep the live AR session limited to ARCore's own GPU/tracking
        // surfaces. The high-resolution readers are installed only for the
        // short still-capture phase; targeting them continuously makes this
        // Some camera HALs fail after a handful of frames when occasional
        // still-capture surfaces are also targeted by the repeating request.
        sharedCamera.setAppSurfaces(cameraId, listOf(scenePreviewSurface))

        val deviceStateCallback =
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    validateSharedResolutionCandidates(camera)
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    startupBarrier.fail("Shared camera disconnected during startup")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    startupBarrier.fail("Shared camera failed to open (error=$error)")
                    Log.e("SharedCameraManager", "Camera error: $error")
                }

                override fun onClosed(camera: CameraDevice) {
                    cameraDeviceClosed = true
                    signalCameraCloseBarrierIfComplete()
                    scheduleBackgroundThreadShutdownWhenClosed()
                }
            }
        val wrappedDeviceStateCallback =
            sharedCamera.createARDeviceStateCallback(deviceStateCallback, backgroundHandler)

        cameraManager.openCamera(
            cameraId,
            wrappedDeviceStateCallback,
            backgroundHandler,
        )
        startupBarrier.awaitReady(StartupTimeoutMs)
    }

    private fun validateSharedResolutionCandidates(camera: CameraDevice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(
                "SharedCameraManager",
                "Shared-session resolution API validation requires Android 9 or newer",
            )
            return
        }
        val sharedCamera = requireSharedCamera()
        val arCoreSurfaces =
            SharedCameraInteropPlanner.requireArCoreSurfaces(sharedCamera.arCoreSurfaces)
        val executor = java.util.concurrent.Executor { command ->
            backgroundHandler?.post(command)
        }
        val stateCallback =
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = Unit

                override fun onConfigureFailed(session: CameraCaptureSession) = Unit
            }
        val validated = mutableListOf<com.uhg0.ar_flutter_plugin_2.shared_camera.camera.CameraResolution>()

        for (candidate in capabilityQuerier.getSharedCameraResolutionCandidates(config.format)) {
            val reader =
                ImageReader.newInstance(
                    candidate.width,
                    candidate.height,
                    config.format,
                    2,
                )
            try {
                val outputs =
                    (arCoreSurfaces + reader.surface + listOfNotNull(rawImageReader?.surface))
                        .map(::OutputConfiguration)
                val configuration =
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs,
                        executor,
                        stateCallback,
                    )
                if (camera.isSessionConfigurationSupported(configuration)) {
                    validated += candidate
                }
            } catch (error: Exception) {
                Log.w(
                    "SharedCameraManager",
                    "Unable to validate shared resolution ${candidate.width}x${candidate.height}",
                    error,
                )
            } finally {
                reader.close()
            }
        }

        if (config.rawJpeg) {
            capabilityQuerier.saveSupportedRawJpegResolutions(validated)
        } else {
            capabilityQuerier.saveSupportedSharedCameraResolutions(validated)
        }
        if (config.rawJpeg && validated.none {
                it.width == getEffectiveResolution().width &&
                    it.height == getEffectiveResolution().height
            }
        ) {
            throw CaptureSessionException(
                code = "RAW_JPEG_UNSUPPORTED",
                message = "ARCore plus RAW and requested JPEG outputs are not supported together",
            )
        }
        Log.i(
            "SharedCameraManager",
            "Validated ${validated.size} shared-camera resolutions with ARCore surfaces",
        )
    }

    /// Validate configuration against actual camera capabilities
    private fun validateConfigurationAgainstCapabilities(characteristics: CameraCharacteristics) {
        val configMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw RuntimeException("Camera configuration map not available")

        // Standard mode uses Camera2's hardware JPEG output. This avoids a
        // multi-megapixel YUV copy and software encode on every capture.
        val supportedSizes =
            configMap.getOutputSizes(android.graphics.ImageFormat.JPEG)
                ?: emptyArray()

        if (config.rawJpeg) {
            val capabilities =
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?: intArrayOf()
            val rawSizes = configMap.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
            if (
                !capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ||
                    rawSizes.isEmpty()
            ) {
                throw CaptureSessionException(
                    code = "RAW_JPEG_UNSUPPORTED",
                    message = "The active shared camera does not expose RAW_SENSOR output",
                )
            }
        }

        capabilityReport =
            SharedCaptureCapabilityValidator.validateAndReport(
            capabilities =
                SharedCaptureCapabilities(
                    supportedOutputSizes = supportedSizes.map { it.width to it.height },
                    timestampSource =
                        characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),
                    isoRange =
                        characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                            it.lower..it.upper
                        },
                    exposureTimeRangeNs =
                        characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                            it.lower..it.upper
                        },
                ),
            requestedWidth = config.resolution.width,
            requestedHeight = config.resolution.height,
            defaultIso = config.defaultISO,
            defaultExposureTimeMicros = config.defaultExposureTimeMicros,
        )

        Log.i("SharedCameraManager", "Configuration validated successfully")
    }

    private fun selectRawResolution(characteristics: CameraCharacteristics): Size {
        val rawSizes =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?: throw CaptureSessionException(
                    code = "RAW_JPEG_UNSUPPORTED",
                    message = "No RAW_SENSOR sizes are available",
                )
        return rawSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: throw CaptureSessionException(
                code = "RAW_JPEG_UNSUPPORTED",
                message = "No RAW_SENSOR sizes are available",
            )
    }

    private fun allocateBuffersBasedOnConfig() {
        val bufferCount = getBufferCountForStrategy()
        val estimatedMemoryMB = estimateMemoryUsage()
        Log.i("SharedCameraManager", "Allocating $bufferCount buffers, estimated memory: ${estimatedMemoryMB}MB")
        imageCacheManager?.configure(config.maxCacheSize, estimatedMemoryMB.toInt())
    }

    private fun getBufferCountForStrategy(): Int =
        when (config.bufferStrategy) {
            "memory" -> 2
            "balanced" -> 3
            "performance" -> 5
            else -> 3
        }

    private fun estimateMemoryUsage(): Double {
        val effectiveResolution = getEffectiveResolution()
        val pixelsPerImage = effectiveResolution.width * effectiveResolution.height
        val bytesPerPixel =
            when (config.format) {
                android.graphics.ImageFormat.JPEG -> 3
                android.graphics.ImageFormat.RAW_SENSOR -> 2
                else -> 3
            }
        val bytesPerImage = pixelsPerImage * bytesPerPixel
        val totalBytes = bytesPerImage * config.maxCacheSize
        return totalBytes / (1024.0 * 1024.0)
    }

    private fun createCaptureSession() {
        val cameraDevice = this.cameraDevice ?: return
        val previewImageReader = this.previewImageReader ?: return
        val sharedCamera = requireSharedCamera()

        try {
            val arCoreSurfaces =
                SharedCameraInteropPlanner.requireArCoreSurfaces(sharedCamera.arCoreSurfaces)
            val sessionSurfaces =
                SharedCameraInteropPlanner.buildSessionSurfaces(
                    arCoreSurfaces = arCoreSurfaces,
                    appSurfaces =
                        listOfNotNull(
                            scenePreviewSurface,
                            previewImageReader.surface,
                            rawImageReader?.surface,
                        ),
                )
            val sessionStateCallback =
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        if (!configureCameraSettings()) {
                            return
                        }
                        startRepeatingRequest()
                    }

                    override fun onActive(session: CameraCaptureSession) {
                        if (!resumeArSessionAfterSharedCameraSetup()) {
                            return
                        }
                        try {
                            sharedCamera.setCaptureCallback(
                                sharedCameraCaptureCallback,
                                captureCallbackHandler,
                            )
                            recordActiveSharedResolution()
                            startupBarrier.markConfigured()
                            Log.i(
                                "SharedCameraManager",
                                "ARCore resumed after the shared repeating request became active",
                            )
                        } catch (error: Exception) {
                            startupBarrier.fail(
                                "Failed to register shared-camera callback: ${error.message}",
                            )
                            Log.e(
                                "SharedCameraManager",
                                "Failed to register shared-camera callback",
                                error,
                            )
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        startupBarrier.fail("Failed to configure shared camera capture session")
                        Log.e("SharedCameraManager", "Failed to configure capture session")
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        captureSessionClosed = true
                        signalCameraCloseBarrierIfComplete()
                        scheduleBackgroundThreadShutdownWhenClosed()
                    }
                }
            val wrappedSessionStateCallback =
                sharedCamera.createARSessionStateCallback(sessionStateCallback, backgroundHandler)
            cameraDevice.createCaptureSession(
                sessionSurfaces,
                wrappedSessionStateCallback,
                backgroundHandler,
            )
        } catch (e: Exception) {
            startupBarrier.fail("Failed to create shared camera capture session: ${e.message}")
            Log.e("SharedCameraManager", "Failed to create capture session", e)
        }
    }

    private fun recordActiveSharedResolution() {
        val reader = previewImageReader ?: return
        val active =
            com.uhg0.ar_flutter_plugin_2.shared_camera.camera.CameraResolution(
                reader.width,
                reader.height,
            )
        if (config?.rawJpeg == true) {
            capabilityQuerier.saveSupportedRawJpegResolutions(
                capabilityQuerier.getSupportedRawJpegResolutions() + active,
            )
        } else {
            capabilityQuerier.saveSupportedSharedCameraResolutions(
                capabilityQuerier.getSupportedSharedCameraResolutions() + active,
            )
        }
    }

    private fun configureCameraSettings(): Boolean {
        val cameraDevice = this.cameraDevice ?: return false
        val previewImageReader = this.previewImageReader ?: return false
        val sharedCamera = requireSharedCamera()

        try {
            val arCoreSurfaces =
                SharedCameraInteropPlanner.requireArCoreSurfaces(sharedCamera.arCoreSurfaces)
            val repeatingBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    val repeatingTargets =
                        SharedCameraInteropPlanner.buildRepeatingRequestTargets(
                            arCoreSurfaces = arCoreSurfaces,
                            appSurfaces = listOf(scenePreviewSurface),
                        )
                    repeatingTargets.forEach(::addTarget)
                }
            previewCaptureRequestBuilder = repeatingBuilder

            val requestBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    arCoreSurfaces.forEach(::addTarget)
                    addTarget(previewImageReader.surface)
                    rawImageReader?.surface?.let(::addTarget)
                }
            manualCaptureRequestBuilder = requestBuilder

            for (builder in listOf(repeatingBuilder, requestBuilder)) {
                if (config.autoExposure) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                } else {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    config.defaultISO?.let { iso ->
                        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    }
                    config.defaultExposureTimeMicros?.let { exposureTimeMicros ->
                        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeMicros * 1000)
                    }
                }

                if (config.autoWhiteBalance) {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                } else {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                }

                if (config.format == android.graphics.ImageFormat.JPEG) {
                    builder.set(CaptureRequest.JPEG_ORIENTATION, 0)
                    builder.set(CaptureRequest.JPEG_QUALITY, config.jpegQuality.toByte())
                }
            }

            runtimeCameraController =
                RuntimeCameraController(requestBuilder) { runtimeState ->
                    syncRepeatingRequestWithRuntimeSettings(runtimeState)
                }
            Log.i("SharedCameraManager", "Camera settings configured")
            return true
        } catch (e: Exception) {
            startupBarrier.fail("Failed to configure shared camera settings: ${e.message}")
            Log.e("SharedCameraManager", "Failed to configure camera settings", e)
            return false
        }
    }

    private fun pauseArSessionForSharedCameraSetup() {
        val arSession = session
            ?: throw CaptureSessionException(
                code = "CAPTURE_NOT_INITIALIZED",
                message = "ARCore session is not ready for shared-camera setup",
            )
        try {
            arSession.pause()
            Log.i("SharedCameraManager", "Paused ARCore before setting shared-camera app surfaces")
        } catch (error: Exception) {
            throw CaptureSessionException(
                code = "SHARED_CAMERA_PAUSE_FAILED",
                message = "Unable to pause ARCore for shared-camera setup: ${error.message}",
            )
        }
    }

    private fun resumeArSessionAfterSharedCameraSetup(): Boolean {
        if (arCoreResumedForSharedSession) {
            return true
        }
        val arSession = session ?: return false
        return try {
            disableSceneViewResumeReconfiguration(arSession)
            // The shared-camera setup pauses ARCore after SceneView originally
            // registered its external OES textures. Rebind those same texture
            // names before resuming so the newly-created Camera2 session feeds
            // SceneView's camera background as well as ARCore tracking.
            //
            // SceneView's normal onResumed callback cannot do this for us: it
            // also calls Session.configure(), which ARCore rejects once the
            // shared Camera2 session is SHARED_CONFIGURED.
            val textureId = bindSingleSceneViewCameraTexture()
            arSession.resume()
            arCoreResumedForSharedSession = true
            Log.i(
                "SharedCameraManager",
                "Bound SceneView camera texture $textureId and resumed ARCore",
            )
            true
        } catch (error: Exception) {
            startupBarrier.fail("Failed to resume ARCore after shared-camera setup: ${error.message}")
            Log.e("SharedCameraManager", "Failed to resume ARCore after shared-camera setup", error)
            false
        }
    }

    private fun bindSingleSceneViewCameraTexture(): Int {
        val arSession = session
            ?: throw CaptureSessionException(
                code = "CAPTURE_NOT_INITIALIZED",
                message = "ARCore session is not ready for camera texture binding",
            )
        val textureId = cameraTextureIds().firstOrNull()
            ?: throw CaptureSessionException(
                code = "CAMERA_TEXTURE_UNAVAILABLE",
                message = "SceneView camera texture is not ready for shared-camera setup",
            )
        arSession.setCameraTextureName(textureId)
        return textureId
    }

    /** Delegates lifecycle preparation to the SceneView 4.21.2 renderer host. */
    private fun disableSceneViewResumeReconfiguration(arSession: Session) {
        if (sceneViewResumeReconfigurationDisabled) {
            return
        }
        prepareSessionResume(arSession)
        sceneViewResumeReconfigurationDisabled = true
        Log.i(
            "SharedCameraManager",
            "Disabled SceneView post-resume ARCore reconfiguration for shared camera",
        )
    }

    private fun startRepeatingRequest() {
        val captureSession = this.captureSession ?: return
        val requestBuilder = previewCaptureRequestBuilder ?: return

        try {
            captureSession.setRepeatingRequest(
                requestBuilder.build(),
                sharedCameraCaptureCallback,
                captureCallbackHandler,
            )
            repeatingRequestLifecycle.markRepeatingStarted()
            Log.i("SharedCameraManager", "Shared-camera repeating request started")
        } catch (e: Exception) {
            startupBarrier.fail("Failed to start shared camera repeating request: ${e.message}")
            Log.e("SharedCameraManager", "Failed to start repeating request", e)
        }
    }

    private fun syncRepeatingRequestWithRuntimeSettings(state: RuntimeCameraSettingsState) {
        val requestBuilder = previewCaptureRequestBuilder ?: return

        try {
            state.applyCurrentSettings(requestBuilder)
            if (!repeatingRequestLifecycle.shouldSubmitRuntimeUpdate()) {
                return
            }
            captureSession?.setRepeatingRequest(
                requestBuilder.build(),
                sharedCameraCaptureCallback,
                captureCallbackHandler,
            )
            Log.i("SharedCameraManager", "Shared-camera repeating request updated from runtime controls")
        } catch (e: Exception) {
            Log.e("SharedCameraManager", "Failed to update shared-camera repeating request", e)
        }
    }

    fun onArSessionPaused() {
        arCoreResumedForSharedSession = false
        if (!repeatingRequestLifecycle.onSessionPaused()) {
            return
        }

        try {
            captureSession?.stopRepeating()
            Log.i("SharedCameraManager", "Shared-camera repeating request paused")
        } catch (e: Exception) {
            Log.e("SharedCameraManager", "Failed to pause shared-camera repeating request", e)
        }
    }

    fun onArSessionResumed() {
        if (!repeatingRequestLifecycle.onSessionResumed()) {
            return
        }

        val captureSession = this.captureSession ?: return
        val requestBuilder = previewCaptureRequestBuilder ?: return

        try {
            captureSession.setRepeatingRequest(
                requestBuilder.build(),
                sharedCameraCaptureCallback,
                captureCallbackHandler,
            )
            Log.i("SharedCameraManager", "Shared-camera repeating request resumed")
        } catch (e: Exception) {
            Log.e("SharedCameraManager", "Failed to resume shared-camera repeating request", e)
        }
    }

    private fun handlePreviewImageAvailable(reader: ImageReader) {
        // Encode at most one current frame per callback. Draining an accumulating
        // high-resolution queue synchronously can starve the capture-result
        // callback on this same handler, preventing timestamp correlation.
        val image = reader.acquireLatestImage() ?: return
        resourceCounters.onImageAcquired()
        try {
            if (pendingManualCapture == null) {
                return
            }
            val encodeStartedAtMs = System.currentTimeMillis()
            Log.i(
                "SharedCameraManager",
                "Received manual ${image.width}x${image.height} image format=${image.format}",
            )
            if (config.processed) {
                pendingManualCapture?.let { pending ->
                    val policy = pending.qualityPolicy
                    if (policy != null) {
                        val crop = image.cropRect
                        val analyzed =
                            if (policy.blurFilterEnabled) {
                                YuvPlaneBlurAnalyzer.analyze(
                                    buffer = image.planes[0].buffer,
                                    rowStride = image.planes[0].rowStride,
                                    pixelStride = image.planes[0].pixelStride,
                                    cropLeft = crop.left,
                                    cropTop = crop.top,
                                    cropWidth = crop.width(),
                                    cropHeight = crop.height(),
                                )
                            } else {
                                PreviewBlurQuality(0.0, 0, 0, "yuvLaplacianVarianceV1")
                            }
                        val quality =
                            mapOf<String, Any>(
                                "blurScore" to analyzed.blurScore,
                                "blurThreshold" to policy.blurThreshold,
                                "blurPassed" to
                                    (!policy.blurFilterEnabled ||
                                        analyzed.blurScore >= policy.blurThreshold),
                                "analyzedWidth" to analyzed.analyzedWidth,
                                "analyzedHeight" to analyzed.analyzedHeight,
                                "algorithm" to analyzed.algorithm,
                            )
                        pending.preEncodeQuality = quality
                        if (quality["blurPassed"] != true && !policy.keepRejectedCaptures) {
                            pending.error = SharedBlurRejectedException(quality)
                            pending.latch.countDown()
                            return
                        }
                    }
                }
                val timestamp = image.timestamp
                val packed = copyYuv420Image(image)
                processedFrameCorrelator.onFrame(timestamp, packed)
                    ?.let(::handleCorrelatedProcessedFrame)
                return
            }
            check(image.format == ImageFormat.JPEG) {
                "Supported shared-camera capture modes must deliver hardware JPEG"
            }
            val jpegBytes = readImageBytes(image)
            Log.i(
                "SharedCameraManager",
                "Encoded ${jpegBytes.size} bytes in ${System.currentTimeMillis() - encodeStartedAtMs}ms",
            )
            if (config.rawJpeg) {
                handleRawJpegImage(
                    PendingStillImagePayload(
                        sensorTimestampNs = image.timestamp,
                        width = image.width,
                        height = image.height,
                        bytes = jpegBytes,
                        receivedAtMs = System.currentTimeMillis(),
                    ),
                )
                return
            }
            stillCaptureCorrelator.onImageAvailable(
                sensorTimestampNs = image.timestamp,
                width = image.width,
                height = image.height,
                bytes = jpegBytes,
            )?.let(::handleCorrelatedStillCapture)
        } catch (error: Throwable) {
            Log.e("SharedCameraManager", "Failed to encode shared-camera YUV frame", error)
            pendingManualCapture?.let { pending ->
                pending.error = error
                pending.latch.countDown()
            }
        } finally {
            closeTrackedImage(image)
        }
    }

    private fun handleRawImageAvailable(reader: ImageReader) {
        val image = reader.acquireNextImage() ?: return
        resourceCounters.onImageAcquired()
        if (pendingManualCapture == null) {
            closeTrackedImage(image)
            return
        }
        rawJpegCaptureCorrelator.onRaw(image.timestamp, image)
            ?.let(::handleCorrelatedRawJpegCapture)
    }

    private fun handleRawJpegImage(image: PendingStillImagePayload) {
        rawJpegCaptureCorrelator.onJpeg(image)
            ?.let(::handleCorrelatedRawJpegCapture)
    }

    private fun handleRawJpegCaptureResult(
        sensorTimestampNs: Long,
        result: TotalCaptureResult,
    ) {
        rawJpegCaptureCorrelator.onResult(sensorTimestampNs, result)
            ?.let(::handleCorrelatedRawJpegCapture)
    }

    private fun handleCorrelatedRawJpegCapture(
        capture: CorrelatedRawJpegCapture<Image, TotalCaptureResult>,
    ) {
        val jpeg = capture.jpeg
        val rawImage = capture.raw
        val totalResult = capture.result
        val pending = pendingManualCapture
        val characteristics = activeCameraCharacteristics
        if (pending == null || characteristics == null) {
            closeTrackedImage(rawImage)
            return
        }
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val closeRaw = {
            if (closed.compareAndSet(false, true)) closeTrackedImage(rawImage)
        }
        try {
            val imageId = generateImageId()
            val sensorTimestampNs =
                totalResult.get(CaptureResult.SENSOR_TIMESTAMP) ?: jpeg.sensorTimestampNs
            pending.result =
                SharedCameraCaptureResult(
                    reservationToken = pending.reservationToken,
                    imageId = imageId,
                    imageBytes = jpeg.bytes,
                    format = ImageFormat.JPEG,
                    width = jpeg.width,
                    height = jpeg.height,
                    imageSizeBytes = jpeg.bytes.size,
                    captureTimestampMs = System.currentTimeMillis(),
                    sensorTimestampNs = sensorTimestampNs,
                    exposureTimeNs = totalResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                    rollingShutterSkewNs =
                        totalResult.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L,
                    observedTimestampNs = captureObservation(sensorTimestampNs),
                    intrinsics =
                        capabilityQuerier.getCameraIntrinsicsForSize(
                            captureSize = Size(jpeg.width, jpeg.height),
                            cropRegion = totalResult.get(CaptureResult.SCALER_CROP_REGION),
                        ),
                    rawDngEncoder = {
                        try {
                            ByteArrayOutputStream().use { output ->
                                DngCreator(characteristics, totalResult).use { creator ->
                                    creator.writeImage(output, rawImage)
                                }
                                output.toByteArray()
                            }
                        } finally {
                            closeRaw()
                        }
                    },
                    closeRawImage = closeRaw,
                    rawWidth = rawImage.width,
                    rawHeight = rawImage.height,
                    primaryAssetName = "jpeg",
                )
            pending.latch.countDown()
        } catch (error: Throwable) {
            closeRaw()
            imageCacheManager?.releaseReservation(pending.reservationToken)
            pending.error = error
            pending.latch.countDown()
        }
    }

    private data class PackedYuv420(
        val width: Int,
        val height: Int,
        val yBytes: ByteArray,
        val yRowStride: Int,
        val yPixelStride: Int,
        val uBytes: ByteArray,
        val uRowStride: Int,
        val uPixelStride: Int,
        val vBytes: ByteArray,
        val vRowStride: Int,
        val vPixelStride: Int,
        val cropLeft: Int,
        val cropTop: Int,
    )

    private fun copyYuv420Image(image: Image): PackedYuv420 {
        require(image.format == ImageFormat.YUV_420_888)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val crop = image.cropRect
        return PackedYuv420(
            crop.width(), crop.height(),
            y.buffer.toByteArrayFromStart(), y.rowStride, y.pixelStride,
            u.buffer.toByteArrayFromStart(), u.rowStride, u.pixelStride,
            v.buffer.toByteArrayFromStart(), v.rowStride, v.pixelStride,
            crop.left, crop.top,
        )
    }

    private fun encodePackedYuv420AsJpeg(yuv: PackedYuv420): ByteArray {
        val width = yuv.width
        val height = yuv.height
        val nv21 = ByteArray(width * height + width * height / 2)
        for (row in 0 until height) {
            val sourceRow = row + yuv.cropTop
            for (col in 0 until width) {
                val sourceCol = col + yuv.cropLeft
                nv21[row * width + col] =
                    yuv.yBytes[sourceRow * yuv.yRowStride + sourceCol * yuv.yPixelStride]
            }
        }
        var output = width * height
        for (row in 0 until height / 2) {
            val sourceRow = row + yuv.cropTop / 2
            for (col in 0 until width / 2) {
                val sourceCol = col + yuv.cropLeft / 2
                nv21[output++] =
                    yuv.vBytes[sourceRow * yuv.vRowStride + sourceCol * yuv.vPixelStride]
                nv21[output++] =
                    yuv.uBytes[sourceRow * yuv.uRowStride + sourceCol * yuv.uPixelStride]
            }
        }
        return ByteArrayOutputStream().use { stream ->
            check(
                YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(
                    Rect(0, 0, width, height),
                    config.jpegQuality,
                    stream,
                ),
            ) { "Android YUV JPEG encoder rejected the shared-camera frame" }
            stream.toByteArray()
        }
    }

    private fun handleCorrelatedProcessedFrame(
        correlated: CorrelatedProcessedFrame<PackedYuv420>,
    ) {
        val pending = pendingManualCapture ?: return
        val timing =
            PoseDataExtractor.CaptureTiming(
                sensorTimestampNs = correlated.result.sensorTimestampNs,
                exposureTimeNs = correlated.result.exposureTimeNs,
                rollingShutterSkewNs = correlated.result.rollingShutterSkewNs,
                observedTimestampNs = correlated.result.observedTimestampNs,
            )
        val alignedPose = resolvePoseBeforeEncoding(timing)
        if (alignedPose == null) {
            pending.error =
                CaptureSessionException(
                    code = "POSE_SYNC_FAILED",
                    message = "No exact or interpolated pose was available before encoding",
                )
            pending.latch.countDown()
            return
        }
        pending.preAlignedPose = alignedPose
        Log.i(
            "SharedCameraManager",
            "Pose ${alignedPose.poseAlignment} resolved before finalization " +
                "for sensor timestamp ${correlated.result.sensorTimestampNs}",
        )
        val imageId = generateImageId()
        val workerStartGate = CountDownLatch(1)
        val submitted =
            finalizationWorkers.submit {
                try {
                    workerStartGate.await()
                    val startedAtMs = System.currentTimeMillis()
                    val encodedBytes = encodePackedYuv420AsJpeg(correlated.frame)
                    val assetName = "jpeg"
                    val assetFormat = ImageFormat.JPEG
                    val encoded =
                        SharedCameraCaptureResult(
                            reservationToken = pending.reservationToken,
                            imageId = imageId,
                            imageBytes = encodedBytes,
                            format = assetFormat,
                            width = correlated.frame.width,
                            height = correlated.frame.height,
                            imageSizeBytes = encodedBytes.size,
                            captureTimestampMs = System.currentTimeMillis(),
                            sensorTimestampNs = correlated.result.sensorTimestampNs,
                            exposureTimeNs = correlated.result.exposureTimeNs,
                            rollingShutterSkewNs = correlated.result.rollingShutterSkewNs,
                            observedTimestampNs = correlated.result.observedTimestampNs,
                            intrinsics =
                                capabilityQuerier.getCameraIntrinsicsForSize(
                                    captureSize =
                                        Size(correlated.frame.width, correlated.frame.height),
                                    cropRegion = correlated.result.cropRegion,
                                ),
                            primaryAssetName = assetName,
                            preEncodeQuality = pending.preEncodeQuality,
                            preAlignedPose = alignedPose,
                        )
                    onCaptureEncoded(encoded, pending.qualityPolicy)
                    Log.i(
                        "SharedCameraManager",
                        "Encoded ${encodedBytes.size} ${assetName.uppercase()} bytes in " +
                            "${System.currentTimeMillis() - startedAtMs}ms",
                    )
                } catch (error: Throwable) {
                    pending.error = error
                    imageCacheManager?.releaseReservation(pending.reservationToken)
                    onCaptureFinalizationFailed(imageId, error)
                }
            }
        if (submitted != FinalizationSubmissionStatus.ACCEPTED) {
            pending.error =
                CaptureSessionException(
                    code = "ENCODER_BACKPRESSURE",
                    message = "Capture finalization queue is full",
                )
            pending.latch.countDown()
            workerStartGate.countDown()
            return
        }
        val accepted =
            SharedCaptureAccepted(
                    imageId = imageId,
                    width = correlated.frame.width,
                    height = correlated.frame.height,
                    format = "jpeg",
                    captureTimestampMs = System.currentTimeMillis(),
                    alignedPose = alignedPose,
                    quality = pending.preEncodeQuality,
                    sensorTimestampNs = correlated.result.sensorTimestampNs,
                    exposureTimeNs = correlated.result.exposureTimeNs,
                    rollingShutterSkewNs = correlated.result.rollingShutterSkewNs,
                    intrinsics =
                        capabilityQuerier.getCameraIntrinsicsForSize(
                            captureSize = Size(correlated.frame.width, correlated.frame.height),
                            cropRegion = correlated.result.cropRegion,
                        ),
                )
        pending.accepted = accepted
        try {
            onCaptureAccepted(
                accepted,
            )
        } finally {
            // The worker cannot start RGB conversion/encoding until the
            // accepted notification has been dispatched.
            workerStartGate.countDown()
            pending.latch.countDown()
        }
    }

    private fun ByteBuffer.toByteArrayFromStart(): ByteArray {
        val source = duplicate()
        source.rewind()
        return ByteArray(source.remaining()).also(source::get)
    }

    private fun clearPendingRawJpegComponents() {
        rawJpegCaptureCorrelator.clear()
    }

    private fun closeTrackedImage(image: Image) {
        image.close()
        resourceCounters.onImageClosed()
    }

    fun captureImage(): Boolean {
        val activeSession = captureSession ?: return false
        val builder = manualCaptureRequestBuilder ?: return false
        if (!repeatingRequestLifecycle.isRepeatingActive()) {
            return false
        }
        val generation = pendingManualCapture?.generation ?: return false
        builder.setTag(ManualCaptureTag(generation))
        return try {
            activeSession.capture(
                builder.build(),
                sharedCameraCaptureCallback,
                captureCallbackHandler,
            )
            true
        } catch (error: Exception) {
            Log.e("SharedCameraManager", "Failed to submit one-shot shared capture", error)
            false
        }
    }

    fun captureImageResult(
        qualityPolicy: CaptureQualityPolicy? = null,
        timeoutMs: Long = ManualCaptureTimeoutMs,
    ): SharedCameraCaptureResult {
        if (!isInitialized) {
            throw IllegalStateException("SharedCameraManager is not initialized")
        }
        val reservationToken =
            imageCacheManager?.reserveCaptureSlot()
                ?: throw IllegalStateException("ImageCacheManager is not initialized")
        val pending = PendingManualCapture(
            CountDownLatch(1),
            reservationToken,
            qualityPolicy,
            requestGeneration.next(),
        )
        if (!pendingManualCaptureOwner.acquire(pending)) {
            imageCacheManager?.releaseReservation(reservationToken)
            throw CaptureSessionException("CAPTURE_IN_PROGRESS", "A shared capture is in progress")
        }
        val started =
            try {
                captureImage()
            } catch (error: Throwable) {
                imageCacheManager?.releaseReservation(reservationToken)
                pendingManualCaptureOwner.release(pending)
                requestGeneration.clear(pending.generation)
                throw error
            }
        if (!started) {
            imageCacheManager?.releaseReservation(reservationToken)
            pendingManualCaptureOwner.release(pending)
            requestGeneration.clear(pending.generation)
            throw IllegalStateException("Shared camera capture could not start")
        }
        val effectiveTimeoutMs =
            if (timeoutMs != ManualCaptureTimeoutMs) timeoutMs
            else if (config.rawJpeg) 20_000L
            else timeoutMs
        val completed = pending.latch.await(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
        pendingManualCaptureOwner.release(pending)
        requestGeneration.clear(pending.generation)
        if (!completed) {
            clearPendingRawJpegComponents()
            imageCacheManager?.releaseReservation(reservationToken)
            throw IllegalStateException("Timed out waiting for shared camera still capture")
        }
        pending.error?.let { error ->
            imageCacheManager?.releaseReservation(reservationToken)
            when (error) {
                is RuntimeException -> throw error
                else -> throw IllegalStateException(error.message, error)
            }
        }
        return pending.result
            ?: throw IllegalStateException("Shared camera capture completed without a result")
    }

    /**
     * Returns after timestamp correlation, pose resolution, blur acceptance,
     * and bounded worker admission. Encoding/cache commit continues on the
     * finalization pool and reports through [onCaptureEncoded].
     */
    fun captureDeferredImageAccepted(
        qualityPolicy: CaptureQualityPolicy?,
        timeoutMs: Long = ManualCaptureTimeoutMs,
    ): SharedCaptureAccepted {
        check(config.processed) {
            "Two-phase capture is not enabled for this format"
        }
        if (!isInitialized) throw IllegalStateException("SharedCameraManager is not initialized")
        val reservationToken =
            imageCacheManager?.reserveCaptureSlot()
                ?: throw IllegalStateException("ImageCacheManager is not initialized")
        val pending = PendingManualCapture(
            CountDownLatch(1),
            reservationToken,
            qualityPolicy,
            requestGeneration.next(),
        )
        if (!pendingManualCaptureOwner.acquire(pending)) {
            imageCacheManager?.releaseReservation(reservationToken)
            throw CaptureSessionException("CAPTURE_IN_PROGRESS", "A shared capture is in progress")
        }
        if (!captureImage()) {
            pendingManualCaptureOwner.release(pending)
            requestGeneration.clear(pending.generation)
            imageCacheManager?.releaseReservation(reservationToken)
            throw IllegalStateException("Shared camera capture could not start")
        }
        val completed = pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pendingManualCaptureOwner.release(pending)
        requestGeneration.clear(pending.generation)
        if (!completed) {
            imageCacheManager?.releaseReservation(reservationToken)
            throw IllegalStateException("Timed out waiting for capture acceptance")
        }
        pending.error?.let { error ->
            imageCacheManager?.releaseReservation(reservationToken)
            if (error is RuntimeException) throw error
            throw IllegalStateException(error.message, error)
        }
        return pending.accepted
            ?: throw IllegalStateException("Capture completed without an accepted signal")
    }

    fun getCaptureCapacity(): Map<String, Any?> =
        imageCacheManager?.getCaptureCapacity()
            ?: throw IllegalStateException("ImageCacheManager is not initialized")

    fun getImageData(imageId: String, format: String): ByteArray =
        imageCacheManager?.getImageData(imageId, format)
            ?: throw IllegalStateException("ImageCacheManager is not initialized")

    fun getImageSize(imageId: String): Map<String, Any> =
        imageCacheManager?.getImageSize(imageId)
            ?: throw IllegalStateException("ImageCacheManager is not initialized")

    fun saveImageToFile(imageId: String, filePath: String, format: String): Boolean =
        imageCacheManager?.saveImageToFile(imageId, filePath, format)
            ?: throw IllegalStateException("ImageCacheManager is not initialized")

    fun persistCapture(
        imageId: String,
        destinationRoot: String,
        sessionFolder: String,
        baseName: String,
        format: String,
    ): Map<String, Any> =
        imageCacheManager?.persistCapture(imageId, destinationRoot, sessionFolder, baseName, format)
            ?: throw IllegalStateException("ImageCacheManager is not initialized")

    fun discardCapture(imageId: String): Boolean =
        imageCacheManager?.discardCapture(imageId)
            ?: throw IllegalStateException("ImageCacheManager is not initialized")

    fun getCameraIntrinsics(): Map<String, Any>? =
        capabilityQuerier.getCameraIntrinsicsForSize(getEffectiveResolution())
            ?: capabilityQuerier.getCameraIntrinsics()

    fun setISO(isoValue: Int): Int? = runtimeExposureControls().setISO(isoValue)

    fun setExposureTime(exposureTimeMicros: Long): Long? =
        runtimeExposureControls().setExposureTime(exposureTimeMicros)

    fun setAutoExposureEnabled(enabled: Boolean): Boolean =
        runtimeExposureControls().setAutoExposureEnabled(enabled)

    fun getCurrentISO(): Int? = runtimeExposureControls().getCurrentISO()

    fun getCurrentExposureTimeMicros(): Long? =
        runtimeExposureControls().getCurrentExposureTimeMicros()

    fun getSupportedISORange(): List<Int> = runtimeExposureControls().getSupportedISORange()

    fun getSupportedExposureRange(): Map<String, Long> =
        runtimeExposureControls().getSupportedExposureRange()

    fun getCurrentExposureState(): Map<String, Any?> =
        runtimeExposureControls().getCurrentExposureState()

    fun getExposureCompensationInfo(): Map<String, Double> =
        runtimeExposureControls().getExposureCompensationInfo()

    fun setExposureCompensation(evStep: Double): Double =
        runtimeExposureControls().setExposureCompensation(evStep)

    fun lockExposure(): Boolean = runtimeExposureControls().lockExposure()

    fun unlockExposure(): Boolean = runtimeExposureControls().unlockExposure()

    fun setFocusDistance(normalizedDistance: Double): Double? =
        runtimeFocusControls().setFocusDistance(normalizedDistance)

    fun focusAtPoint(
        x: Double,
        y: Double,
    ): Boolean = runtimeFocusControls().focusAtPoint(x, y)

    fun setAutofocusEnabled(enabled: Boolean): Boolean =
        runtimeFocusControls().setAutofocusEnabled(enabled)

    fun getCurrentFocusState(): Map<String, Any?> =
        runtimeFocusControls().getCurrentFocusState()

    fun getSupportedFocusModes(): List<String> =
        runtimeFocusControls().getSupportedFocusModes()

    fun setFocusMode(mode: String): Boolean = runtimeFocusControls().setFocusMode(mode)

    fun setWhiteBalanceMode(mode: String): Boolean =
        runtimeWhiteBalanceControls().setWhiteBalanceMode(mode)

    fun setColorTemperature(colorTemperatureK: Int): Int =
        runtimeWhiteBalanceControls().setColorTemperature(colorTemperatureK)

    fun getCurrentWhiteBalanceState(): Map<String, Any?> =
        runtimeWhiteBalanceControls().getCurrentWhiteBalanceState()

    fun getSupportedColorTemperatureRange(): Map<String, Int> =
        runtimeWhiteBalanceControls().getSupportedColorTemperatureRange()

    fun setWhiteBalanceFromPoint(
        x: Double,
        y: Double,
    ): Boolean = runtimeWhiteBalanceControls().setWhiteBalanceFromPoint(x, y)

    fun lockWhiteBalance(): Boolean = runtimeWhiteBalanceControls().lockWhiteBalance()

    fun unlockWhiteBalance(): Boolean = runtimeWhiteBalanceControls().unlockWhiteBalance()

    fun getSupportedWhiteBalanceModes(): List<String> =
        runtimeWhiteBalanceControls().getSupportedWhiteBalanceModes()

    fun setFlashMode(mode: String): Boolean = runtimeFlashControls().setFlashMode(mode)

    fun setTorchEnabled(enabled: Boolean): Boolean = runtimeFlashControls().setTorchEnabled(enabled)

    fun getCurrentFlashState(): Map<String, Any?> = runtimeFlashControls().getCurrentFlashState()

    fun isFlashAvailable(): Boolean = runtimeFlashControls().isFlashAvailable()

    fun getConfig(): Map<String, Any> {
        val effectiveConfiguration: Map<String, Any> =
            capabilityReport?.let { report ->
                val supportedResolutionMaps: List<Map<String, Int>> =
                    report.supportedOutputSizes.map { (width, height) ->
                        mapOf(
                            "width" to width,
                            "height" to height,
                        )
                    }
                mapOf(
                    "requestedResolution" to
                        mapOf(
                            "width" to report.requestedWidth,
                            "height" to report.requestedHeight,
                        ),
                    "resolution" to
                        mapOf(
                            "width" to report.effectiveWidth,
                            "height" to report.effectiveHeight,
                        ),
                    "resolutionSelectionReason" to report.resolutionSelectionReason,
                    "timestampSource" to report.timestampSourceLabel,
                    "timestampSourceRealtimeVerified" to report.timestampSourceRealtimeVerified,
                    "timestampCorrelationProbeRequired" to report.timestampCorrelationProbeRequired,
                    "supportedResolutions" to supportedResolutionMaps,
                )
            } ?: emptyMap()

        return mapOf(
            "enableHighResCapture" to config.enableHighResCapture,
            "captureIntervalMs" to config.captureIntervalMs,
            "resolution" to mapOf("width" to config.resolution.width, "height" to config.resolution.height),
            "format" to
                if (config.rawJpeg) {
                    "raw+jpeg"
                } else "jpeg",
            "maxCacheSize" to config.maxCacheSize,
            "jpegQuality" to config.jpegQuality,
            "bufferStrategy" to config.bufferStrategy,
            "effectiveConfiguration" to effectiveConfiguration,
        )
    }

    fun cleanup() {
        cleanupRequested = true
        pendingManualCapture?.let { pending ->
            pending.error = CaptureSessionException(
                code = "CAPTURE_DISPOSED",
                message = "Shared-camera capture was cancelled during cleanup",
            )
            imageCacheManager?.releaseReservation(pending.reservationToken)
            requestGeneration.clear(pending.generation)
            pendingManualCaptureOwner.release(pending)
            pending.latch.countDown()
        }
        val closingCaptureSession = captureSession
        captureSessionClosed = closingCaptureSession == null
        closingCaptureSession?.close()
        captureSession = null

        val closingCameraDevice = cameraDevice
        cameraDeviceClosed = closingCameraDevice == null
        closingCameraDevice?.close()
        cameraDevice = null
        signalCameraCloseBarrierIfComplete()

        activeCameraCharacteristics = null
        previewCaptureRequestBuilder = null
        manualCaptureRequestBuilder = null

        if (finalizationWorkersDelegate.isInitialized()) finalizationWorkers.close()

        scheduleBackgroundThreadShutdownWhenClosed()
        // Vendor fallback: close callbacks are expected, but never retain a
        // handler thread indefinitely if a HAL omits one.
        backgroundHandler?.postDelayed(::quitBackgroundThreadFromCameraCallback, 3_000L)

        observedCaptureState = null
        sceneViewResumeReconfigurationDisabled = false
        isInitialized = false
        Log.i("SharedCameraManager", "Cleanup completed")
    }

    /**
     * Waits until Camera2 has delivered both wrapped close callbacks. ARCore's
     * SharedCamera callbacks still dereference the ARCore Session, so the host
     * must not destroy that Session before this barrier opens.
     */
    fun finishCameraShutdown(timeoutMs: Long) {
        val callbacksCompleted = cameraCloseLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        // Match ARCore's SharedCamera sample: readers remain valid until the
        // wrapped camera close callback has completed, then their callback
        // handlers are drained before the ARCore Session is destroyed.
        closeImageReaders()
        stopBackgroundThreadsAfterDrain()
        if (!callbacksCompleted) {
            Log.i(
                "SharedCameraManager",
                "Camera2 close callbacks were not forwarded; callback handlers drained before ARCore disposal",
            )
        }
    }

    private fun closeImageReaders() {
        previewImageReader?.close()
        previewImageReader = null
        clearPendingRawJpegComponents()
        processedFrameCorrelator.clear()
        rawImageReader?.close()
        rawImageReader = null
    }

    private fun getActiveSharedCameraCharacteristics(): CameraCharacteristics {
        val cameraId =
            SharedCameraInteropPlanner.resolveCameraId(
                sharedCameraId = session?.cameraConfig?.cameraId,
            )
        return cameraManager.getCameraCharacteristics(cameraId)
    }

    private fun requireRuntimeCameraController(): RuntimeCameraController =
        runtimeCameraController
            ?: throw CaptureSessionException(
                code = "CAPTURE_NOT_INITIALIZED",
                message = "Runtime camera controls are not initialized",
            )

    private fun runtimeExposureControls(): RuntimeExposureControlBridge {
        val characteristics = getActiveSharedCameraCharacteristics()
        val isoRange =
            characteristics
                .get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?.let { it.lower..it.upper }
        val exposureRangeMicros =
            characteristics
                .get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?.let { (it.lower / 1000)..(it.upper / 1000) }
        val exposureCompensationRange =
            characteristics
                .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                ?.let { it.lower..it.upper }
        val exposureCompensationStep =
            characteristics
                .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                ?.toDouble()
        val exposureLockSupported =
            characteristics
                .get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)
                ?: false
        val controller = requireRuntimeCameraController()
        return RuntimeExposureControlBridge(
            capabilities =
                RuntimeExposureCapabilities(
                    supportedIsoRange = isoRange,
                    supportedExposureTimeMicrosRange = exposureRangeMicros,
                    exposureCompensationStepsRange = exposureCompensationRange,
                    exposureCompensationStepEv = exposureCompensationStep,
                    exposureLockSupported = exposureLockSupported,
                ),
            target =
                object : RuntimeExposureControlTarget {
                    override fun setISO(isoValue: Int): Boolean = controller.setISO(isoValue)

                    override fun setExposureTime(exposureTimeMicros: Long): Boolean =
                        controller.setExposureTime(exposureTimeMicros)

                    override fun setAutoExposureEnabled(enabled: Boolean): Boolean =
                        controller.setAutoExposure(enabled)

                    override fun getCurrentISO(): Int? = controller.getCurrentISO()

                    override fun getCurrentExposureTimeMicros(): Long? =
                        controller.getCurrentExposureTimeMicros()

                    override fun isAutoExposureEnabled(): Boolean =
                        controller.isAutoExposureEnabled()

                    override fun setExposureCompensationSteps(steps: Int): Boolean =
                        controller.setExposureCompensation(steps)

                    override fun getCurrentExposureCompensationSteps(): Int =
                        controller.getCurrentExposureCompensationSteps()

                    override fun setExposureLocked(locked: Boolean): Boolean =
                        controller.setExposureLocked(locked)

                    override fun isExposureLocked(): Boolean =
                        controller.isExposureLocked()

                    override fun getObservedExposureState(): RuntimeObservedExposureState? =
                        observedCaptureState?.exposure
                },
        )
    }

    private fun runtimeFocusControls(): RuntimeFocusControlBridge {
        val characteristics = getActiveSharedCameraCharacteristics()
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val maxFocusDistance =
            characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val tapFocusSupported =
            (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
        val supportedFocusModes =
            characteristics
                .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.toList()
                ?.mapNotNull { mode -> mapCamera2FocusMode(mode) }
                ?.toMutableSet()
                ?: mutableSetOf()
        if (maxFocusDistance != null && maxFocusDistance > 0f) {
            supportedFocusModes += RuntimeFocusMode.fixed.name
            supportedFocusModes += RuntimeFocusMode.infinity.name
        }
        val controller = requireRuntimeCameraController()
        return RuntimeFocusControlBridge(
            capabilities =
                RuntimeFocusCapabilities(
                    maxFocusDistanceDiopters = maxFocusDistance,
                    supportedFocusModes = supportedFocusModes,
                    sensorArea =
                        activeArray?.let {
                            SensorArea(width = it.width(), height = it.height())
                        },
                    tapFocusSupported = tapFocusSupported,
                ),
            target =
                object : RuntimeFocusControlTarget {
                    override fun setFocusDistanceDiopters(focusDistanceDiopters: Float): Boolean =
                        controller.setFocusDistance(focusDistanceDiopters)

                    override fun setAutofocusEnabled(enabled: Boolean): Boolean =
                        controller.setAutofocusEnabled(enabled)

                    override fun setFocusMode(mode: String): Boolean =
                        controller.setFocusMode(RuntimeFocusMode.valueOf(mode))

                    override fun getCurrentFocusDistanceDiopters(): Float? =
                        controller.getCurrentFocusDistanceDiopters()

                    override fun getCurrentFocusMode(): String =
                        controller.getCurrentFocusMode().name

                    override fun isAutofocusEnabled(): Boolean =
                        controller.isAutofocusEnabled()

                    override fun setFocusRegion(region: MeteringRegion): Boolean =
                        controller.setFocusRegion(region)

                    override fun getCurrentFocusRegion(): MeteringRegion? =
                        controller.getCurrentFocusRegion()

                    override fun getObservedFocusState(): RuntimeObservedFocusState? =
                        observedCaptureState?.focus
                },
        )
    }

    private fun mapCamera2FocusMode(camera2Mode: Int): String? =
        when (camera2Mode) {
            CaptureRequest.CONTROL_AF_MODE_AUTO -> RuntimeFocusMode.auto.name
            CaptureRequest.CONTROL_AF_MODE_MACRO -> RuntimeFocusMode.macro.name
            CaptureRequest.CONTROL_AF_MODE_EDOF -> RuntimeFocusMode.edof.name
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> RuntimeFocusMode.continuous.name
            else -> null
        }

    private fun runtimeWhiteBalanceControls(): RuntimeWhiteBalanceControlBridge {
        val characteristics = getActiveSharedCameraCharacteristics()
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val supportedModes =
            characteristics
                .get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?.toList()
                ?.mapNotNull { mode -> mapCamera2WhiteBalanceMode(mode) }
                ?.toSet()
                ?: emptySet()
        val pointWhiteBalanceSupported =
            (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0) > 0
        val supportsManualColorTemperature =
            characteristics
                .get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?.contains(CaptureRequest.CONTROL_AWB_MODE_OFF)
                ?: false
        val controller = requireRuntimeCameraController()
        return RuntimeWhiteBalanceControlBridge(
            capabilities =
                RuntimeWhiteBalanceCapabilities(
                    supportedModes = supportedModes,
                    supportedColorTemperatureRange =
                        if (supportsManualColorTemperature) {
                            2000..8000
                        } else {
                            null
                        },
                    sensorArea =
                        activeArray?.let {
                            SensorArea(width = it.width(), height = it.height())
                        },
                    pointWhiteBalanceSupported = pointWhiteBalanceSupported,
                ),
            target =
                object : RuntimeWhiteBalanceControlTarget {
                    override fun setWhiteBalanceMode(mode: String): Boolean =
                        controller.setWhiteBalanceMode(RuntimeWhiteBalanceMode.valueOf(mode))

                    override fun setColorTemperature(colorTemperatureK: Int): Boolean =
                        controller.setColorTemperature(colorTemperatureK)

                    override fun setWhiteBalanceLocked(locked: Boolean): Boolean =
                        controller.setWhiteBalanceLocked(locked)

                    override fun getCurrentWhiteBalanceMode(): String =
                        controller.getCurrentWhiteBalanceMode().name

                    override fun getCurrentColorTemperature(): Int? =
                        controller.getCurrentColorTemperatureKelvin()

                    override fun setWhiteBalanceRegion(region: MeteringRegion): Boolean =
                        controller.setWhiteBalanceRegion(region)

                    override fun isWhiteBalanceLocked(): Boolean =
                        controller.isWhiteBalanceLocked()

                    override fun isAutoWhiteBalanceEnabled(): Boolean =
                        controller.isAutoWhiteBalanceEnabled()

                    override fun getObservedWhiteBalanceState():
                        RuntimeObservedWhiteBalanceState? = observedCaptureState?.whiteBalance
                },
        )
    }

    private fun mapCamera2WhiteBalanceMode(camera2Mode: Int): String? =
        when (camera2Mode) {
            CaptureRequest.CONTROL_AWB_MODE_AUTO -> RuntimeWhiteBalanceMode.auto.name
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT ->
                RuntimeWhiteBalanceMode.incandescent.name
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT ->
                RuntimeWhiteBalanceMode.fluorescent.name
            CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT ->
                RuntimeWhiteBalanceMode.warmFluorescent.name
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> RuntimeWhiteBalanceMode.daylight.name
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT ->
                RuntimeWhiteBalanceMode.cloudyDaylight.name
            CaptureRequest.CONTROL_AWB_MODE_TWILIGHT -> RuntimeWhiteBalanceMode.twilight.name
            CaptureRequest.CONTROL_AWB_MODE_SHADE -> RuntimeWhiteBalanceMode.shade.name
            else -> null
        }

    private fun runtimeFlashControls(): RuntimeFlashControlBridge {
        val characteristics = getActiveSharedCameraCharacteristics()
        val flashAvailable =
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        val supportedModes =
            buildSet {
                add(RuntimeFlashMode.off.name)
                if (flashAvailable) {
                    add(RuntimeFlashMode.auto.name)
                    add(RuntimeFlashMode.on.name)
                    add(RuntimeFlashMode.redEyeReduction.name)
                    add(RuntimeFlashMode.torch.name)
                }
            }
        val controller = requireRuntimeCameraController()
        return RuntimeFlashControlBridge(
            capabilities =
                RuntimeFlashCapabilities(
                    flashAvailable = flashAvailable,
                    supportedModes = supportedModes,
                ),
            target =
                object : RuntimeFlashControlTarget {
                    override fun setFlashMode(mode: String): Boolean =
                        controller.setFlashMode(RuntimeFlashMode.valueOf(mode))

                    override fun getCurrentFlashMode(): String =
                        controller.getCurrentFlashMode().name

                    override fun getObservedFlashState(): RuntimeObservedFlashState? =
                        observedCaptureState?.flash
                },
        )
    }

    private fun generateImageId(): String =
        CaptureImageIdGenerator.next()

    private fun getBytesPerPixel(): Int =
        when (config.format) {
            android.graphics.ImageFormat.JPEG -> 3
            android.graphics.ImageFormat.RAW_SENSOR -> 2
            else -> 3
        }

    private fun estimateImageSize(image: Image): Int = image.width * image.height * getBytesPerPixel()

    private fun readImageBytes(image: Image): ByteArray {
        val firstPlane = image.planes.firstOrNull()
            ?: throw IllegalStateException("Captured image has no planes")
        return firstPlane.buffer.toByteArray()
    }

    private fun encodeYuv420ImageAsJpeg(image: Image): ByteArray {
        require(image.format == ImageFormat.YUV_420_888) {
            "Expected YUV_420_888 shared-camera frame, got ${image.format}"
        }
        val width = image.width
        val height = image.height
        val planes = image.planes
        require(planes.size == 3) { "YUV frame must expose three planes" }

        val nv21 = ByteArray(width * height + (width * height / 2))
        copyPlaneToPackedBuffer(
            plane = planes[0],
            planeWidth = width,
            planeHeight = height,
            output = nv21,
            outputOffset = 0,
            outputPixelStride = 1,
        )
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        copyPlaneToPackedBuffer(
            plane = planes[2],
            planeWidth = chromaWidth,
            planeHeight = chromaHeight,
            output = nv21,
            outputOffset = width * height,
            outputPixelStride = 2,
        )
        copyPlaneToPackedBuffer(
            plane = planes[1],
            planeWidth = chromaWidth,
            planeHeight = chromaHeight,
            output = nv21,
            outputOffset = width * height + 1,
            outputPixelStride = 2,
        )

        return ByteArrayOutputStream().use { output ->
            val encoded =
                YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(
                    Rect(0, 0, width, height),
                    config.jpegQuality,
                    output,
                )
            check(encoded) { "Android YUV JPEG encoder rejected the shared-camera frame" }
            output.toByteArray()
        }
    }

    private fun copyPlaneToPackedBuffer(
        plane: Image.Plane,
        planeWidth: Int,
        planeHeight: Int,
        output: ByteArray,
        outputOffset: Int,
        outputPixelStride: Int,
    ) {
        val input = plane.buffer.duplicate()
        val inputBase = input.position()
        var outputIndex = outputOffset
        for (row in 0 until planeHeight) {
            val rowStart = inputBase + row * plane.rowStride
            if (plane.pixelStride == 1 && outputPixelStride == 1) {
                input.position(rowStart)
                input.get(output, outputIndex, planeWidth)
                outputIndex += planeWidth
                continue
            }
            for (column in 0 until planeWidth) {
                output[outputIndex] = input.get(rowStart + column * plane.pixelStride)
                outputIndex += outputPixelStride
            }
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val duplicate = duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    private fun handleCorrelatedStillCapture(capture: CorrelatedStillCapture) {
        val pending = pendingManualCapture
        if (pending == null) {
            Log.w("SharedCameraManager", "Dropping late correlated still without an active capture request")
            return
        }
        try {
            val imageId = generateImageId()
            val intrinsics =
                capabilityQuerier.getCameraIntrinsicsForSize(
                    captureSize = Size(capture.image.width, capture.image.height),
                    cropRegion = capture.result.cropRegion,
                )
            val captureResult =
                SharedCameraCaptureResult(
                    reservationToken = pending?.reservationToken ?: "",
                    imageId = imageId,
                    imageBytes = capture.image.bytes,
                    format = config.format,
                    width = capture.image.width,
                    height = capture.image.height,
                    imageSizeBytes = capture.image.bytes.size,
                    captureTimestampMs = System.currentTimeMillis(),
                    sensorTimestampNs = capture.result.sensorTimestampNs,
                    exposureTimeNs = capture.result.exposureTimeNs,
                    rollingShutterSkewNs = capture.result.rollingShutterSkewNs,
                    observedTimestampNs = capture.result.observedTimestampNs,
                    intrinsics = intrinsics,
                    primaryAssetName = "jpeg",
                    preEncodeQuality = pending.preEncodeQuality,
                )

            pending.result = captureResult
            pending.latch.countDown()
        } catch (error: Throwable) {
            imageCacheManager?.releaseReservation(pending.reservationToken)
            pending.error = error
            pending.latch.countDown()
        }
    }

    private fun buildObservedCaptureState(
        result: TotalCaptureResult,
    ): RuntimeObservedCaptureState {
        val controller = runtimeCameraController
        return RuntimeObservedCaptureState(
            exposure = buildObservedExposureState(result, controller),
            focus = buildObservedFocusState(result, controller),
            whiteBalance = buildObservedWhiteBalanceState(result, controller),
            flash = buildObservedFlashState(result, controller),
        )
    }

    private fun buildObservedExposureState(
        result: TotalCaptureResult,
        controller: RuntimeCameraController?,
    ): RuntimeObservedExposureState {
        val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
        val isAutoExposureEnabled = aeMode != CaptureResult.CONTROL_AE_MODE_OFF
        return RuntimeObservedExposureState(
            currentISO = result.get(CaptureResult.SENSOR_SENSITIVITY),
            currentExposureTimeMicros =
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { it / 1000L },
            isAutoExposureEnabled = isAutoExposureEnabled,
            isExposureLocked =
                isAutoExposureEnabled && (result.get(CaptureResult.CONTROL_AE_LOCK) ?: false),
            exposureCompensationSteps =
                result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
                    ?: controller?.getCurrentExposureCompensationSteps(),
        )
    }

    private fun buildObservedFocusState(
        result: TotalCaptureResult,
        controller: RuntimeCameraController?,
    ): RuntimeObservedFocusState {
        val afMode = result.get(CaptureResult.CONTROL_AF_MODE)
        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
        return RuntimeObservedFocusState(
            currentFocusDistanceDiopters =
                result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    ?: controller?.getCurrentFocusDistanceDiopters(),
            currentFocusMode =
                afMode?.let(::mapCamera2FocusMode) ?: controller?.getCurrentFocusMode()?.name,
            isAutofocusEnabled = afMode != CaptureResult.CONTROL_AF_MODE_OFF,
            isFocusLocked =
                afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
            focusStatus = mapObservedFocusStatus(afState, controller?.getCurrentFocusRegion()),
            focusRegion = controller?.getCurrentFocusRegion(),
        )
    }

    private fun buildObservedWhiteBalanceState(
        result: TotalCaptureResult,
        controller: RuntimeCameraController?,
    ): RuntimeObservedWhiteBalanceState {
        val awbMode = result.get(CaptureResult.CONTROL_AWB_MODE)
        val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
        val currentColorTemperature =
            if (awbMode == CaptureResult.CONTROL_AWB_MODE_OFF) {
                controller?.getCurrentColorTemperatureKelvin()
            } else {
                null
            }
        return RuntimeObservedWhiteBalanceState(
            currentMode =
                when {
                    awbMode == CaptureResult.CONTROL_AWB_MODE_OFF &&
                        currentColorTemperature != null -> RuntimeWhiteBalanceMode.manual.name
                    awbMode != null -> mapCamera2WhiteBalanceMode(awbMode)
                    else -> controller?.getCurrentWhiteBalanceMode()?.name
                },
            currentColorTemperature = currentColorTemperature,
            isWhiteBalanceLocked =
                currentColorTemperature == null &&
                    (result.get(CaptureResult.CONTROL_AWB_LOCK) ?: false),
            isAutoWhiteBalanceEnabled = awbMode == CaptureResult.CONTROL_AWB_MODE_AUTO,
            status = mapObservedWhiteBalanceStatus(awbState),
        )
    }

    private fun buildObservedFlashState(
        result: TotalCaptureResult,
        controller: RuntimeCameraController?,
    ): RuntimeObservedFlashState {
        val flashMode = result.get(CaptureResult.FLASH_MODE)
        val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
        val flashState = result.get(CaptureResult.FLASH_STATE)
        val currentFlashMode =
            when {
                flashMode == CaptureResult.FLASH_MODE_TORCH -> RuntimeFlashMode.torch.name
                aeMode == CaptureResult.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> RuntimeFlashMode.on.name
                aeMode == CaptureResult.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE ->
                    RuntimeFlashMode.redEyeReduction.name
                aeMode == CaptureResult.CONTROL_AE_MODE_ON_AUTO_FLASH -> RuntimeFlashMode.auto.name
                flashMode == CaptureResult.FLASH_MODE_OFF -> RuntimeFlashMode.off.name
                else -> controller?.getCurrentFlashMode()?.name
            }
        return RuntimeObservedFlashState(
            currentFlashMode = currentFlashMode,
            isTorchEnabled = flashMode == CaptureResult.FLASH_MODE_TORCH,
            isFlashReady =
                when (flashState) {
                    CaptureResult.FLASH_STATE_UNAVAILABLE -> false
                    CaptureResult.FLASH_STATE_CHARGING -> false
                    null -> currentFlashMode != RuntimeFlashMode.off.name
                    else -> true
                },
            flashStatus = mapObservedFlashStatus(flashState),
        )
    }

    private fun mapObservedFocusStatus(
        afState: Int?,
        focusRegion: MeteringRegion?,
    ): String =
        when (afState) {
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "scanning"
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "locked"
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "notLocked"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "scanning"
            else -> if (focusRegion != null) "scanning" else "inactive"
        }

    private fun mapObservedWhiteBalanceStatus(awbState: Int?): String =
        when (awbState) {
            CaptureResult.CONTROL_AWB_STATE_SEARCHING -> "searching"
            CaptureResult.CONTROL_AWB_STATE_CONVERGED -> "converged"
            CaptureResult.CONTROL_AWB_STATE_LOCKED -> "locked"
            else -> "inactive"
        }

    private fun mapObservedFlashStatus(flashState: Int?): String =
        when (flashState) {
            CaptureResult.FLASH_STATE_UNAVAILABLE -> "unavailable"
            CaptureResult.FLASH_STATE_CHARGING -> "charging"
            CaptureResult.FLASH_STATE_READY -> "ready"
            CaptureResult.FLASH_STATE_FIRED -> "firing"
            CaptureResult.FLASH_STATE_PARTIAL -> "partial"
            else -> "ready"
        }

    private fun Rect.toMap(): Map<String, Int> =
        mapOf(
            "left" to left,
            "top" to top,
            "width" to width(),
            "height" to height(),
        )

    private fun getEffectiveResolution(): Size =
        capabilityReport?.let { report ->
            Size(report.effectiveWidth, report.effectiveHeight)
        } ?: config.resolution

    private fun startBackgroundThread() {
        cleanupRequested = false
        captureSessionClosed = false
        cameraDeviceClosed = false
        cameraCloseLatch = CountDownLatch(1)
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        captureCallbackThread = HandlerThread("CameraCaptureCallbacks").apply { start() }
        captureCallbackHandler = Handler(captureCallbackThread!!.looper)
    }

    private fun scheduleBackgroundThreadShutdownWhenClosed() {
        if (!cleanupRequested || !captureSessionClosed || !cameraDeviceClosed) return
        // Samsung may enqueue terminal capture callbacks from its executor
        // immediately after both framework onClosed callbacks. Keep the
        // handler alive for a bounded drain window before stopping its looper.
        backgroundHandler?.postDelayed(
            ::quitBackgroundThreadFromCameraCallback,
            1_500L,
        )
    }

    private fun signalCameraCloseBarrierIfComplete() {
        if (captureSessionClosed && cameraDeviceClosed) {
            cameraCloseLatch.countDown()
        }
    }

    private fun quitBackgroundThreadFromCameraCallback() {
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        captureCallbackThread?.quitSafely()
        captureCallbackThread = null
        captureCallbackHandler = null
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

    private fun stopBackgroundThreadsAfterDrain() {
        val cameraThread = backgroundThread
        val captureThread = captureCallbackThread
        cameraThread?.quitSafely()
        captureThread?.quitSafely()
        runCatching { cameraThread?.join() }
            .onFailure { Log.e("SharedCameraManager", "Error joining camera thread", it) }
        runCatching { captureThread?.join() }
            .onFailure { Log.e("SharedCameraManager", "Error joining capture callback thread", it) }
        backgroundThread = null
        backgroundHandler = null
        captureCallbackThread = null
        captureCallbackHandler = null
    }
}
