package com.uhg0.ar_flutter_plugin_2.capture

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.SurfaceTexture
import android.media.Image
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Surface
import com.google.ar.core.TrackingState
import com.google.android.filament.Stream
import com.google.ar.core.exceptions.NotYetAvailableException
import com.uhg0.ar_flutter_plugin_2.shared_camera.camera.CameraCapabilityQuerier
import com.google.ar.core.Frame
import com.uhg0.ar_flutter_plugin_2.sceneview.SceneViewCaptureHost
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class CaptureSessionException(
    val code: String,
    override val message: String,
) : IllegalStateException(message)

internal class ArCaptureSession(
    private val sceneHost: SceneViewCaptureHost,
    private val capabilityQuerier: CameraCapabilityQuerier,
    private val captureChannel: MethodChannel,
    private val onCapacityChanged: (Map<String, Any?>) -> Unit = {},
    private val onObservedControlStateChanged: (ArCaptureSession) -> Unit = {},
    private val onCaptureAccepted: (Map<String, Any?>) -> Unit = {},
    private val onCaptureFinalized: (Map<String, Any?>) -> Unit = {},
    private val captureSafetySignalV2: CaptureSafetySignalV2 = CaptureSafetySignalV2(),
) {
    companion object {
        private const val TrackingPoseReadyTimeoutMs = 2_000L
    }

    private var config: CaptureConfig? = null
    private var isCaptureInProgress = false
    private val byteCache = CaptureByteCache()
    private val resourceCounters = CaptureResourceCounters()
    private val poseDataExtractor = PoseDataExtractor()
    private var sharedCameraManager: SharedCameraManager? = null
    // Constructed per view now; #102 only provides a future native admission caller.
    private val nativeCaptureBindingV2 = NativeCaptureBindingV2(sceneHost.context, captureSafetySignalV2)
    private var sharedImageCacheManager: ImageCacheManager? = null
    private var highResCaptureEnabled = false
    private var poseSequence = 0L
    private var sharedCameraFilamentStream: Stream? = null
    private var sharedCameraPreviewSurfaceTexture: SurfaceTexture? = null
    private var sharedCameraPreviewSurface: Surface? = null
    private val highResCapturePipeline =
        HighResCapturePipeline(
            cache =
                object : HighResCaptureCache {
                    override fun releaseReservation(reservationToken: String): Boolean =
                        sharedImageCacheManager?.releaseReservation(reservationToken) ?: false

                    override fun commitReservedImage(
                        reservationToken: String,
                        imageId: String,
                        imageBytes: ByteArray,
                        format: Int,
                    ): Boolean =
                        sharedImageCacheManager?.commitReservedImage(
                            reservationToken = reservationToken,
                            imageId = imageId,
                            imageBytes = imageBytes,
                            format = format,
                        ) ?: false

                    override fun commitReservedAssets(
                        reservationToken: String,
                        imageId: String,
                        assets: Map<String, CachedImageAsset>,
                    ): Boolean =
                        sharedImageCacheManager?.commitReservedAssets(
                            reservationToken = reservationToken,
                            imageId = imageId,
                            assets = assets,
                        ) ?: false
                },
            poseResolver =
                object : HighResPoseResolver {
                    override fun resolvePose(
                        captureTiming: PoseDataExtractor.CaptureTiming,
                    ): PoseDataExtractor.AlignedPose? {
                        val resolved = poseDataExtractor.resolvePose(captureTiming)
                        if (resolved == null) {
                            Log.w(
                                "ArCaptureSession",
                                "Shared capture pose alignment failed: " +
                                    poseDataExtractor.alignmentDiagnostics(captureTiming),
                            )
                        }
                        return resolved
                    }

                    override fun toPoseMap(
                        alignedPose: PoseDataExtractor.AlignedPose,
                    ): Map<String, Any?> = poseDataExtractor.toPoseMap(alignedPose)
                },
            qualityAnalyzer = ::analyzeSharedQuality,
            // Analysis cost depends on output size, format, and runtime load.
            // Use one conservative deadline for every supported device.
            qualityAnalysisTimeoutMs = 1_000L,
        )

    suspend fun initialize(configMap: Map<String, Any?>) {
        val parsedConfig = CaptureConfig.fromMap(configMap)
        config = parsedConfig
        highResCaptureEnabled = configMap["enableHighResCapture"] as? Boolean ?: false
        if (highResCaptureEnabled) {
            SharedCameraInteropPlanner
                .checkAvailability(
                    hasSession = sceneHost.activeSession != null,
                    hasSharedCamera = sceneHost.activeSession?.sharedCamera != null,
                )?.let { failure ->
                    throw CaptureSessionException(
                        code = failure.code,
                        message = failure.message,
                    )
                }
            @Suppress("UNCHECKED_CAST")
            val typedConfigMap = configMap.mapValues { it.value } as Map<String, Any>
            val sharedConfig = ParsedCaptureConfig.fromMap(typedConfigMap)
            val imageCacheManager =
                ImageCacheManager(
                    config = sharedConfig,
                    context = sceneHost.context,
                    onCapacityChanged = onCapacityChanged,
                )
            sharedImageCacheManager = imageCacheManager
            val scenePreviewSurface = createSharedCameraPreviewSurface()
            val createManager = {
                SharedCameraManager(
                    context = sceneHost.context,
                    methodChannel = captureChannel,
                    session = sceneHost.activeSession,
                    cameraTextureIds = { sceneHost.cameraTextureIds },
                    prepareSessionResume = sceneHost::prepareSharedCameraResume,
                    scenePreviewSurface = scenePreviewSurface,
                    configMap = typedConfigMap,
                    onObservedCaptureStateChanged = {
                        onObservedControlStateChanged(this)
                    },
                    resolvePoseBeforeEncoding = { timing ->
                        poseDataExtractor.resolvePose(timing).also { resolved ->
                            if (resolved == null) {
                                Log.w(
                                    "ArCaptureSession",
                                    "Pre-encode pose alignment failed: " +
                                        poseDataExtractor.alignmentDiagnostics(timing),
                                )
                            }
                        }
                    },
                    onCaptureAccepted = { accepted ->
                        onCaptureAccepted(
                            mapOf(
                                "imageId" to accepted.imageId,
                                "width" to accepted.width,
                                "height" to accepted.height,
                                "format" to accepted.format,
                                "captureTimestampMs" to accepted.captureTimestampMs,
                                "pose" to accepted.alignedPose?.let(
                                    poseDataExtractor::toPoseMap,
                                ),
                                "quality" to accepted.quality,
                                "state" to "acceptedPending",
                            ),
                        )
                    },
                    onCaptureEncoded = { encoded, policy ->
                        try {
                            onCaptureFinalized(
                                highResCapturePipeline.processCapture(encoded, policy) +
                                    ("state" to "committed"),
                            )
                        } catch (error: Throwable) {
                            onCaptureFinalized(
                                mapOf(
                                    "state" to "backgroundFailed",
                                    "imageId" to encoded.imageId,
                                    "code" to
                                        (error as? CaptureSessionException)?.code.orEmpty()
                                            .ifEmpty { "FINALIZATION_FAILED" },
                                    "message" to (error.message ?: "Capture finalization failed"),
                                ),
                            )
                        }
                    },
                    onCaptureFinalizationFailed = { imageId, error ->
                        onCaptureFinalized(
                            mapOf(
                                "state" to "backgroundFailed",
                                "imageId" to imageId,
                                "code" to "ENCODING_FAILED",
                                "message" to (error.message ?: "Image encoding failed"),
                            ),
                        )
                    },
                    resourceCounters = resourceCounters,
                )
            }
            var startupError: Exception? = null
            for (attempt in 1..2) {
                val manager = createManager()
                try {
                    manager.initialize(imageCacheManager)
                    sharedCameraManager = manager
                    nativeCaptureBindingV2.attachSharedCamera(manager)
                    break
                } catch (error: Exception) {
                    startupError = error
                    withContext(Dispatchers.IO) {
                        manager.finishCameraShutdown(1_000L)
                    }
                    if (
                        attempt == 2 ||
                            !SharedCameraStartupRetryPolicy.shouldRetry(error)
                    ) {
                        break
                    }
                    Log.w(
                        "ArCaptureSession",
                        "Retrying transient shared-camera startup after vendor drain",
                        error,
                    )
                }
            }
            if (sharedCameraManager == null) {
                sharedImageCacheManager = null
                val error = checkNotNull(startupError)
                throw CaptureSessionException(
                    code = "SHARED_CAMERA_STARTUP_FAILED",
                    message = error.cause?.message ?: error.message
                        ?: "Shared-camera startup failed",
                )
            }
        } else {
            byteCache.initialize(parsedConfig)
        }
        emitCapacityChanged()
    }

    fun captureImage(
        qualityPolicyMap: Map<String, Any?>? = null,
        requiresPose: Boolean = true,
        exposureBracketEnabled: Boolean = false,
    ): Map<String, Any?> {
        requireInitialized()
        if (isCaptureInProgress) {
            throw CaptureSessionException(
                code = "CAPTURE_IN_PROGRESS",
                message = "A capture is already in progress",
            )
        }

        isCaptureInProgress = true
        try {
            sharedCameraManager?.let { manager ->
                if (requiresPose && !poseDataExtractor.awaitTrackingPose(TrackingPoseReadyTimeoutMs)) {
                    throw CaptureSessionException(
                        code = "NOT_TRACKING",
                        message = "AR tracking is not ready for a shared-camera capture",
                    )
                }
                val qualityPolicy = qualityPolicyMap?.let(::buildQualityPolicyMap)
                val sharedResult =
                    try {
                        manager.captureImageResult(
                            qualityPolicy = qualityPolicy,
                            requiresPose = requiresPose,
                            exposureBracketEnabled = exposureBracketEnabled,
                        )
                    } catch (rejected: SharedBlurRejectedException) {
                        return mapOf(
                            "status" to "rejectedBlur",
                            "attemptId" to "attempt_${System.currentTimeMillis()}",
                            "imageId" to null,
                            "capture" to null,
                            "quality" to rejected.quality,
                        )
                    }
                if (requiresPose && sharedResult.preAlignedPose == null) {
                    awaitPoseAfter(sharedResult.sensorTimestampNs)
                }
                return highResCapturePipeline.processCapture(sharedResult, qualityPolicy)
            }

            val qualityPolicy = qualityPolicyMap?.let(::buildQualityPolicyMap)
            val frame = sceneHost.frameForCapture() ?: throw CaptureSessionException(
                code = "CAPTURE_NOT_INITIALIZED",
                message = "No AR frame is available for capture",
            )
            val camera = frame.camera
            if (requiresPose && camera.trackingState != TrackingState.TRACKING) {
                throw CaptureSessionException(
                    code = "NOT_TRACKING",
                    message = "AR camera is not tracking",
                )
            }

            if (requiresPose) {
                poseDataExtractor.onFrame(frame)
            }
            val image = try {
                frame.acquireCameraImage()
            } catch (error: NotYetAvailableException) {
                throw CaptureSessionException(
                    code = "CAPTURE_FAILED",
                    message = "Camera image is not available yet",
                )
            }

            image.use { acquiredImage ->
                val quality = qualityPolicy?.let { analyzeQuality(acquiredImage, it) }
                val jpegBytes = imageToJpegBytes(acquiredImage, config!!.jpegQuality)
                val timestampMs = System.currentTimeMillis()
                val sensorTimestampNs = acquiredImage.timestamp
                val imageId = byteCache.nextImageId(timestampMs)
                val intrinsics =
                    capabilityQuerier.getCameraIntrinsicsForSize(
                        Size(acquiredImage.width, acquiredImage.height),
                    ) ?: capabilityQuerier.getCameraIntrinsics()
                val alignedPose =
                    if (requiresPose) {
                        poseDataExtractor.resolvePose(
                            PoseDataExtractor.CaptureTiming(
                                sensorTimestampNs = sensorTimestampNs,
                                exposureTimeNs = 0L,
                                rollingShutterSkewNs = 0L,
                            ),
                            waitForFuturePoseMs = 0L,
                        ) ?: throw CaptureSessionException(
                            code = "POSE_SYNC_FAILED",
                            message = "No aligned pose was available for the captured image",
                        )
                    } else {
                        null
                    }

                val captureResult = mapOf(
                    "imageId" to imageId,
                    "pose" to alignedPose?.let(poseDataExtractor::toPoseMap),
                    "resolution" to mapOf(
                        "width" to acquiredImage.width,
                        "height" to acquiredImage.height,
                    ),
                    "format" to "jpeg",
                    "captureTimestampMs" to timestampMs,
                    "imageSizeBytes" to jpegBytes.size,
                    "isHighResolution" to false,
                    "exposureStartTimestampNs" to sensorTimestampNs,
                    "exposureTimeNs" to 0,
                    "rollingShutterSkewNs" to 0,
                    "intrinsics" to intrinsics,
                    "filePath" to null,
                )

                if (quality != null && !quality["blurPassed"].asBoolean()) {
                    if (qualityPolicy.keepRejectedCaptures) {
                        byteCache.cacheCapture(
                            imageId = imageId,
                            bytes = jpegBytes,
                            width = acquiredImage.width,
                            height = acquiredImage.height,
                            timestampMs = timestampMs,
                        )
                        return mapOf(
                            "status" to "rejectedBlur",
                            "attemptId" to "attempt_$timestampMs",
                            "imageId" to imageId,
                            "capture" to captureResult,
                            "quality" to quality,
                        )
                    }

                    emitCapacityChanged()
                    return mapOf(
                        "status" to "rejectedBlur",
                        "attemptId" to "attempt_$timestampMs",
                        "imageId" to null,
                        "capture" to null,
                        "quality" to quality,
                    )
                }

                byteCache.cacheCapture(
                    imageId = imageId,
                    bytes = jpegBytes,
                    width = acquiredImage.width,
                    height = acquiredImage.height,
                    timestampMs = timestampMs,
                )
                emitCapacityChanged()

                return mapOf(
                    "status" to "staged",
                    "attemptId" to "attempt_$timestampMs",
                    "imageId" to imageId,
                    "capture" to captureResult,
                    "quality" to quality,
                )
            }
        } finally {
            isCaptureInProgress = false
        }
    }

    fun getImageData(imageId: String, format: String): ByteArray? {
        requireInitialized()
        return sharedCameraManager?.getImageData(imageId, format) ?: byteCache.getImageData(imageId, format)
    }

    fun getCaptureCapacity(): Map<String, Any?> {
        requireInitialized()
        return sharedCameraManager?.getCaptureCapacity() ?: byteCache.getCaptureCapacity()
    }

    fun getPerformanceSnapshot(): Map<String, Any?> {
        val runtime = Runtime.getRuntime()
        val batteryManager =
            sceneHost.context.getSystemService(BatteryManager::class.java)
        val batteryPercent =
            batteryManager
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
        val chargeCounterMicroAh =
            batteryManager
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.takeIf { it != Int.MIN_VALUE }
        val thermalStatus =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when (sceneHost.context.getSystemService(PowerManager::class.java)?.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "none"
                    PowerManager.THERMAL_STATUS_LIGHT -> "light"
                    PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                    PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                    else -> "unknown"
                }
            } else {
                null
            }
        return resourceCounters.snapshot() +
            mapOf(
                "processPssBytes" to Debug.getPss().toLong() * 1024L,
                "dartAndJavaHeapUsedBytes" to
                    runtime.totalMemory() - runtime.freeMemory(),
                "openFileDescriptors" to
                    (File("/proc/self/fd").list()?.size ?: -1),
                "threadCount" to Thread.getAllStackTraces().size,
                "batteryPercent" to batteryPercent,
                "batteryChargeCounterMicroAh" to chargeCounterMicroAh,
                "thermalStatus" to thermalStatus,
            )
    }

    fun getImageSize(imageId: String): Map<String, Any>? {
        requireInitialized()
        return sharedCameraManager?.getImageSize(imageId) ?: byteCache.getImageSize(imageId)
    }

    fun saveImageToFile(imageId: String, filePath: String, format: String): Boolean {
        requireInitialized()
        return sharedCameraManager?.saveImageToFile(imageId, filePath, format)
            ?: byteCache.saveImageToFile(imageId, filePath, format)
    }

    fun persistCapture(
        imageId: String,
        destinationRoot: String,
        sessionFolder: String,
        baseName: String,
        format: String,
    ): Map<String, Any> {
        requireInitialized()
        val persisted =
            sharedCameraManager?.persistCapture(imageId, destinationRoot, sessionFolder, baseName, format)
                ?: byteCache.persistCapture(imageId, destinationRoot, sessionFolder, baseName, format)
        if (sharedCameraManager == null) {
            emitCapacityChanged()
        }
        return persisted
    }

    fun discardCapture(imageId: String): Boolean {
        requireInitialized()
        return (sharedCameraManager?.discardCapture(imageId) ?: byteCache.discardCapture(imageId)).also {
            if (sharedCameraManager == null) {
                emitCapacityChanged()
            }
        }
    }

    fun getCameraIntrinsics(): Map<String, Any>? {
        requireInitialized()
        sharedCameraManager?.let { manager ->
            return manager.getCameraIntrinsics()
        }
        val captureConfig = config ?: throw CaptureSessionException(
            code = "CAPTURE_NOT_INITIALIZED",
            message = "Capture session is not initialized",
        )

        return capabilityQuerier.getCameraIntrinsicsForSize(
            Size(captureConfig.resolutionWidth, captureConfig.resolutionHeight),
        ) ?: capabilityQuerier.getCameraIntrinsics()
    }

    fun setISO(isoValue: Int): Int? {
        requireInitialized()
        return requireSharedCameraControls().setISO(isoValue)
    }

    fun setExposureTime(exposureTimeMicros: Long): Long? {
        requireInitialized()
        return requireSharedCameraControls().setExposureTime(exposureTimeMicros)
    }

    fun setAutoExposureEnabled(enabled: Boolean): Boolean {
        requireInitialized()
        return requireSharedCameraControls().setAutoExposureEnabled(enabled)
    }

    fun getCurrentISO(): Int? {
        requireInitialized()
        return requireSharedCameraControls().getCurrentISO()
    }

    fun getCurrentExposureTimeMicros(): Long? {
        requireInitialized()
        return requireSharedCameraControls().getCurrentExposureTimeMicros()
    }

    fun getSupportedISORange(): List<Int> {
        requireInitialized()
        return requireSharedCameraControls().getSupportedISORange()
    }

    fun getSupportedExposureRange(): Map<String, Long> {
        requireInitialized()
        return requireSharedCameraControls().getSupportedExposureRange()
    }

    fun getCurrentExposureState(): Map<String, Any?> {
        requireInitialized()
        return requireSharedCameraControls().getCurrentExposureState()
    }

    fun getExposureCompensationInfo(): Map<String, Double> {
        requireInitialized()
        return requireSharedCameraControls().getExposureCompensationInfo()
    }

    fun setExposureCompensation(evStep: Double): Double {
        requireInitialized()
        return requireSharedCameraControls().setExposureCompensation(evStep)
    }

    fun lockExposure(): Boolean {
        requireInitialized()
        return requireSharedCameraControls().lockExposure()
    }

    fun unlockExposure(): Boolean {
        requireInitialized()
        return requireSharedCameraControls().unlockExposure()
    }

    fun setFocusDistance(normalizedDistance: Double): Double? {
        requireInitialized()
        return requireSharedCameraControls().setFocusDistance(normalizedDistance)
    }

    fun focusAtPoint(
        x: Double,
        y: Double,
    ): Boolean {
        requireInitialized()
        return requireSharedCameraControls().focusAtPoint(x, y)
    }

    fun setAutofocusEnabled(enabled: Boolean): Boolean {
        requireInitialized()
        return requireSharedCameraControls().setAutofocusEnabled(enabled)
    }

    fun getCurrentFocusState(): Map<String, Any?> {
        requireInitialized()
        return requireSharedCameraControls().getCurrentFocusState()
    }

    fun getSupportedFocusModes(): List<String> {
        requireInitialized()
        return requireSharedCameraControls().getSupportedFocusModes()
    }

    fun setFocusMode(mode: String): Boolean {
        requireInitialized()
        return requireSharedCameraControls().setFocusMode(mode)
    }

    fun setWhiteBalanceMode(mode: String): Boolean {
        requireInitialized()
        return requireSharedCameraControls().setWhiteBalanceMode(mode)
    }

    fun setColorTemperature(colorTemperatureK: Int): Int {
        requireInitialized()
        return requireSharedCameraControls().setColorTemperature(colorTemperatureK)
    }

    fun getCurrentWhiteBalanceState(): Map<String, Any?> {
        requireInitialized()
        return requireSharedCameraControls().getCurrentWhiteBalanceState()
    }

    fun getSupportedColorTemperatureRange(): Map<String, Int> {
        requireInitialized()
        return requireSharedCameraControls().getSupportedColorTemperatureRange()
    }

    fun setWhiteBalanceFromPoint(
        x: Double,
        y: Double,
    ): Boolean {
        requireInitialized()
        return requireSharedCameraControls().setWhiteBalanceFromPoint(x, y)
    }

    fun lockWhiteBalance(): Boolean {
        requireInitialized()
        return requireSharedCameraControls().lockWhiteBalance()
    }

    fun unlockWhiteBalance(): Boolean {
        requireInitialized()
        return requireSharedCameraControls().unlockWhiteBalance()
    }

    fun getSupportedWhiteBalanceModes(): List<String> {
        requireInitialized()
        return requireSharedCameraControls().getSupportedWhiteBalanceModes()
    }

    fun setFlashMode(mode: String): Boolean {
        requireInitialized()
        return requireSharedCameraControls().setFlashMode(mode)
    }

    fun setTorchEnabled(enabled: Boolean): Boolean {
        requireInitialized()
        return requireSharedCameraControls().setTorchEnabled(enabled)
    }

    fun getCurrentFlashState(): Map<String, Any?> {
        requireInitialized()
        return requireSharedCameraControls().getCurrentFlashState()
    }

    fun isFlashAvailable(): Boolean {
        requireInitialized()
        return requireSharedCameraControls().isFlashAvailable()
    }

    fun buildPoseUpdate(frame: Frame): Map<String, Any?>? {
        // AR frames also flow when high-resolution capture is disabled, and a
        // final Compose frame can race deterministic disposal. In both cases
        // there is no capture pose stream to update.
        if (config == null) return null
        poseDataExtractor.onFrame(frame)

        val sensorTimestampNs = frame.timestamp
        val latestPose = poseDataExtractor.latest() ?: return null
        return poseDataExtractor.toPoseMap(
            poseDataExtractor.toAlignedPose(
                pose = latestPose,
                sensorTimestampNs = latestPose.timestampNs,
                poseAlignment = "exact",
                poseTimeErrorNs = 0L,
            ),
        ) + mapOf(
            "wireVersion" to "pose_batch_v1",
            "sequence" to ++poseSequence,
        )
    }

    fun onSessionPaused() {
        prepareNativeCaptureForPause()
        finishSharedCameraPause()
    }

    fun prepareNativeCaptureForPause() = nativeCaptureBindingV2.onPause()

    fun finishSharedCameraPause() = sharedCameraManager?.onArSessionPaused()

    fun onSessionResumed() {
        sharedCameraManager?.onArSessionResumed()
    }

    fun dispose() {
        // The binding classifies every owner before the manager closes Camera2.
        nativeCaptureBindingV2.close()
        byteCache.dispose()
        sharedCameraManager?.let { manager ->
            nativeCaptureBindingV2.detachSharedCamera(manager)
            manager.cleanup()
            manager.finishCameraShutdown(1_000L)
        }
        sharedCameraManager = null
        sharedCameraFilamentStream?.let { stream ->
            sceneHost.destroyCaptureStream(stream)
        }
        sharedCameraFilamentStream = null
        sharedCameraPreviewSurface?.release()
        sharedCameraPreviewSurface = null
        sharedCameraPreviewSurfaceTexture?.release()
        sharedCameraPreviewSurfaceTexture = null
        sharedImageCacheManager = null
        config = null
        highResCaptureEnabled = false
    }

    private fun createSharedCameraPreviewSurface(): Surface {
        sharedCameraPreviewSurface?.let { return it }
        val textureSize = sceneHost.activeSession?.cameraConfig?.textureSize
            ?: Size(1920, 1080)
        val surfaceTexture = SurfaceTexture(0).apply {
            try {
                detachFromGLContext()
            } catch (_: RuntimeException) {
                // A newly-created SurfaceTexture may already be detached.
            }
            setDefaultBufferSize(textureSize.width, textureSize.height)
        }
        sharedCameraFilamentStream?.let(sceneHost.engine::destroyStream)
        val stream =
            Stream.Builder()
                .stream(surfaceTexture)
                .build(sceneHost.engine)
        val cameraTexture = sceneHost.cameraTexture
            ?: throw CaptureSessionException(
                code = "CAMERA_TEXTURE_UNAVAILABLE",
                message = "SceneView camera texture is not ready",
            )
        cameraTexture.setExternalStream(sceneHost.engine, stream)
        val surface = Surface(surfaceTexture)
        sharedCameraFilamentStream = stream
        sharedCameraPreviewSurfaceTexture = surfaceTexture
        sharedCameraPreviewSurface = surface
        Log.i(
            "ArCaptureSession",
            "Created ${textureSize.width}x${textureSize.height} Camera2 preview stream for Filament",
        )
        return surface
    }

    private fun emitCapacityChanged() {
        onCapacityChanged(sharedCameraManager?.getCaptureCapacity() ?: byteCache.getCaptureCapacity())
    }

    private fun awaitPoseAfter(
        sensorTimestampNs: Long,
        timeoutMs: Long = TrackingPoseReadyTimeoutMs,
    ) {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadlineNs) {
            val latestTimestampNs = poseDataExtractor.latest()?.timestampNs ?: Long.MIN_VALUE
            if (latestTimestampNs >= sensorTimestampNs) {
                return
            }
            Thread.sleep(5)
        }
        Log.w(
            "ArCaptureSession",
            "Timed out waiting for a post-capture pose after $sensorTimestampNs",
        )
    }

    private fun requireInitialized() {
        if (config == null) {
            throw CaptureSessionException(
                code = "CAPTURE_NOT_INITIALIZED",
                message = "Capture session is not initialized",
            )
        }
    }

    private fun requireSharedCameraControls(): SharedCameraManager {
        if (!highResCaptureEnabled || sharedCameraManager == null) {
            throw CaptureSessionException(
                code = "CONTROL_UNSUPPORTED",
                message =
                    "Runtime camera controls require the high-resolution shared-camera capture path",
            )
        }
        return sharedCameraManager!!
    }

    private fun buildQualityPolicyMap(
        qualityPolicyMap: Map<String, Any?>,
    ): CaptureQualityPolicy {
        return CaptureQualityPolicy(
            blurFilterEnabled = qualityPolicyMap["blurFilterEnabled"] as? Boolean ?: true,
            blurThreshold = (qualityPolicyMap["blurThreshold"] as? Number)?.toDouble()
                ?: 110.0,
            keepRejectedCaptures =
                qualityPolicyMap["keepRejectedCaptures"] as? Boolean ?: false,
        )
    }

    private fun acceptedAttemptMap(accepted: SharedCaptureAccepted): Map<String, Any?> =
        mapOf(
            "status" to "acceptedPending",
            "attemptId" to "attempt_${accepted.captureTimestampMs}",
            "imageId" to accepted.imageId,
            "capture" to accepted.alignedPose?.let { pose ->
                mapOf(
                    "imageId" to accepted.imageId,
                    "pose" to poseDataExtractor.toPoseMap(pose),
                    "resolution" to
                        mapOf("width" to accepted.width, "height" to accepted.height),
                    "format" to accepted.format,
                    "formats" to listOf(accepted.format),
                    "captureTimestampMs" to accepted.captureTimestampMs,
                    "imageSizeBytes" to 0,
                    "imageSizeBytesByFormat" to mapOf(accepted.format to 0),
                    "isHighResolution" to true,
                    "exposureStartTimestampNs" to accepted.sensorTimestampNs,
                    "exposureTimeNs" to accepted.exposureTimeNs,
                    "rollingShutterSkewNs" to accepted.rollingShutterSkewNs,
                    "intrinsics" to accepted.intrinsics,
                    "filePath" to null,
                )
            },
            "quality" to accepted.quality,
        )

    private fun analyzeQuality(
        image: Image,
        qualityPolicy: CaptureQualityPolicy,
    ): Map<String, Any> {
        if (!qualityPolicy.blurFilterEnabled) {
            return mapOf(
                "blurScore" to 0.0,
                "blurThreshold" to qualityPolicy.blurThreshold,
                "blurPassed" to true,
                "analyzedWidth" to 0,
                "analyzedHeight" to 0,
                "algorithm" to "previewLaplacianVarianceV1",
            )
        }

        if (image.format != ImageFormat.YUV_420_888) {
            throw CaptureSessionException(
                code = "QUALITY_ANALYSIS_FAILED",
                message = "Preview blur analysis requires YUV_420_888 image data",
            )
        }

        val luma = extractLumaPlane(image)
        val quality = PreviewBlurAnalyzer.analyzeLaplacianVariance(
            luma = luma,
            width = image.width,
            height = image.height,
        )
        val blurPassed = quality.blurScore >= qualityPolicy.blurThreshold
        return qualityMap(
            "blurScore" to quality.blurScore,
            "blurThreshold" to qualityPolicy.blurThreshold,
            "blurPassed" to blurPassed,
            "analyzedWidth" to quality.analyzedWidth,
            "analyzedHeight" to quality.analyzedHeight,
            "algorithm" to quality.algorithm,
        )
    }

    private fun analyzeSharedQuality(
        sharedResult: SharedCameraCaptureResult,
        qualityPolicy: CaptureQualityPolicy,
    ): Map<String, Any> {
        if (!qualityPolicy.blurFilterEnabled) {
            return mapOf(
                "blurScore" to 0.0,
                "blurThreshold" to qualityPolicy.blurThreshold,
                "blurPassed" to true,
                "analyzedWidth" to 0,
                "analyzedHeight" to 0,
                "algorithm" to "jpegLaplacianVarianceV1",
            )
        }

        val quality = JpegBlurAnalyzer.analyzeLaplacianVariance(sharedResult.imageBytes)
        val blurPassed = quality.blurScore >= qualityPolicy.blurThreshold

        return qualityMap(
            "blurScore" to quality.blurScore,
            "blurThreshold" to qualityPolicy.blurThreshold,
            "blurPassed" to blurPassed,
            "analyzedWidth" to quality.analyzedWidth,
            "analyzedHeight" to quality.analyzedHeight,
            "algorithm" to quality.algorithm,
        )
    }

    private fun qualityMap(vararg entries: Pair<String, Any>): Map<String, Any> {
        val quality = mapOf(*entries)
        Log.i(
            "ArCaptureSession",
            "Blur analysis algorithm=${quality["algorithm"]} score=${quality["blurScore"]} threshold=${quality["blurThreshold"]} passed=${quality["blurPassed"]} size=${quality["analyzedWidth"]}x${quality["analyzedHeight"]}",
        )
        return quality
    }

    private fun extractLumaPlane(image: Image): ByteArray {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val output = ByteArray(width * height)
        copyPlane(
            plane = plane,
            width = width,
            height = height,
            output = output,
            offset = 0,
            pixelStrideOut = 1,
        )
        return output
    }

    private fun Any?.asBoolean(): Boolean = this as? Boolean ?: false

    private fun imageToJpegBytes(image: Image, jpegQuality: Int): ByteArray {
        return when (image.format) {
            ImageFormat.JPEG -> image.planes.first().buffer.let { buffer ->
                ByteArray(buffer.remaining()).also(buffer::get)
            }

            ImageFormat.YUV_420_888 -> {
                val nv21 = yuv420888ToNv21(image)
                val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                ByteArrayOutputStream().use { output ->
                    if (!yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), jpegQuality, output)) {
                        error("Failed to encode JPEG")
                    }
                    output.toByteArray()
                }
            }

            else -> error("Unsupported camera image format: ${image.format}")
        }
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = image.width * image.height
        val uvSize = image.width * image.height / 2
        val output = ByteArray(ySize + uvSize)

        copyPlane(
            plane = yPlane,
            width = image.width,
            height = image.height,
            output = output,
            offset = 0,
            pixelStrideOut = 1,
        )
        interleaveChromaPlanes(
            uPlane = uPlane,
            vPlane = vPlane,
            width = image.width / 2,
            height = image.height / 2,
            output = output,
            offset = ySize,
        )

        return output
    }

    private fun copyPlane(
        plane: Image.Plane,
        width: Int,
        height: Int,
        output: ByteArray,
        offset: Int,
        pixelStrideOut: Int,
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var outputOffset = offset

        val rowData = ByteArray(rowStride)
        for (row in 0 until height) {
            val bytesPerRow = if (pixelStride == 1 && pixelStrideOut == 1) width else (width - 1) * pixelStride + 1
            buffer.position(row * rowStride)
            buffer.get(rowData, 0, bytesPerRow)

            if (pixelStride == 1 && pixelStrideOut == 1) {
                System.arraycopy(rowData, 0, output, outputOffset, width)
                outputOffset += width
            } else {
                for (column in 0 until width) {
                    output[outputOffset] = rowData[column * pixelStride]
                    outputOffset += pixelStrideOut
                }
            }
        }
    }

    private fun interleaveChromaPlanes(
        uPlane: Image.Plane,
        vPlane: Image.Plane,
        width: Int,
        height: Int,
        output: ByteArray,
        offset: Int,
    ) {
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        var outputOffset = offset

        for (row in 0 until height) {
            for (column in 0 until width) {
                val uIndex = row * uRowStride + column * uPixelStride
                val vIndex = row * vRowStride + column * vPixelStride
                output[outputOffset++] = vBuffer.get(vIndex)
                output[outputOffset++] = uBuffer.get(uIndex)
            }
        }
    }
}

private inline fun <T : Image, R> T.use(block: (T) -> R): R {
    return try {
        block(this)
    } catch (error: Exception) {
        Log.e("ArCaptureSession", "Capture pipeline failed", error)
        throw error
    } finally {
        close()
    }
}
