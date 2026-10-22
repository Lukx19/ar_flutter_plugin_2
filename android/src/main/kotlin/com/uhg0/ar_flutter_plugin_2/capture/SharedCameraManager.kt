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
import com.uhg0.ar_flutter_plugin_2.BuildConfig
import android.media.ImageReader
import com.uhg0.ar_flutter_plugin_2.diagnostics.NativeFrameAttributionOwner
import com.uhg0.ar_flutter_plugin_2.diagnostics.NativeFrameSpan
import com.uhg0.ar_flutter_plugin_2.diagnostics.measureNativeFrame
import com.uhg0.ar_flutter_plugin_2.diagnostics.StillCaptureMetadata
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Build
import android.os.SystemClock
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
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// V2 serializes still admissions and closes every acquired Image before the
// next request. One retained reader buffer is therefore sufficient and avoids
// keeping a second multi-megapixel gralloc allocation resident throughout the
// live AR session.
private const val STILL_READER_MAX_IMAGES = 1

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

/**
 * Defers durable component consumption without leaking Camera2-owned streams
 * when shutdown removes a queued task before its executor can run it.
 */
internal class DirectStoreDispatch(
    components: SharedCameraComponentSetV2,
    private val callback: (SharedCameraComponentSetV2) -> Unit,
    private val onFailure: (String) -> Unit,
    private val analyzeJpeg: (() -> Boolean) -> PreviewBlurQuality,
    private val onQuality: (PreviewBlurQuality) -> Unit = {},
    private val retainAssessedOriginals: Boolean = false,
) : Runnable {
    private val components = components.copy(streams = components.streams.map {
        CaptureComponentStreamV2(it.kind, CloseOnceInputStreamV2(it.input))
    })
    private val started = AtomicBoolean(false)
    private val handoffLock = Any()
    @Volatile private var cancellationReason: String? = null

    override fun run() {
        if (!started.compareAndSet(false, true)) return
        var handedOff = false
        try {
            var unavailable: CaptureQualityUnavailableReasonV1? = null
            val quality = try { analyzeJpeg { cancellationReason != null } } catch (error: Exception) {
                if (!retainAssessedOriginals || error is java.util.concurrent.CancellationException || error is InterruptedException) throw error
                unavailable = CaptureQualityUnavailableReasonV1.ANALYSIS_FAILED
                null
            }
            cancellationReason?.let { closeStreams(); onFailure(it); return }
            val valid = quality != null && quality.blurScore.isFinite() && quality.blurScore >= 0.0 &&
                quality.analyzedWidth in 1..4096 && quality.analyzedHeight in 1..4096
            if (quality != null) onQuality(quality)
            if (!retainAssessedOriginals && !valid) { closeStreams(); onFailure("QUALITY_ANALYSIS_FAILED"); return }
            if (!retainAssessedOriginals && requireNotNull(quality).blurScore < JpegBlurAnalyzer.ProductionThreshold) {
                closeStreams(); onFailure("REJECTED_BLUR"); return
            }
            val assessment = if (!retainAssessedOriginals) null else if (!valid) {
                CaptureQualityAssessmentV1(policyVersion = QUALITY_POLICY_VERSION,
                    disposition = CaptureQualityDispositionV1.REVIEW_REQUIRED,
                    reason = CaptureQualityReasonV1.ANALYSIS_UNAVAILABLE,
                    threshold = JpegBlurAnalyzer.ProductionThreshold,
                    unavailableReason = unavailable ?: CaptureQualityUnavailableReasonV1.EVIDENCE_MISSING)
            } else {
                val measured = requireNotNull(quality)
                val passed = measured.blurScore >= JpegBlurAnalyzer.ProductionThreshold
                CaptureQualityAssessmentV1(policyVersion = QUALITY_POLICY_VERSION,
                    disposition = if (passed) CaptureQualityDispositionV1.POLICY_PASSED else CaptureQualityDispositionV1.REVIEW_REQUIRED,
                    reason = if (passed) CaptureQualityReasonV1.POLICY_PASSED else CaptureQualityReasonV1.BELOW_THRESHOLD,
                    score = measured.blurScore, threshold = JpegBlurAnalyzer.ProductionThreshold,
                    sampleWidth = measured.analyzedWidth, sampleHeight = measured.analyzedHeight)
            }
            val mayHandOff = synchronized(handoffLock) {
                if (cancellationReason != null) false else { handedOff = true; true }
            }
            if (!mayHandOff) { closeStreams(); onFailure(requireNotNull(cancellationReason)); return }
            callback(components.copy(qualityAssessment = assessment))
        } catch (error: Throwable) {
            closeStreams()
            onFailure(cancellationReason ?: if (handedOff) "component-dispatch" else "QUALITY_ANALYSIS_FAILED")
        }
    }

    fun cancel(reason: String) {
        val ownsQueued = synchronized(handoffLock) {
            if (cancellationReason == null) cancellationReason = reason
            started.compareAndSet(false, true)
        }
        if (!ownsQueued) return
        closeStreams(); onFailure(reason)
    }

    private fun closeStreams() { components.streams.forEach { runCatching { it.input.close() } } }
    private companion object { const val QUALITY_POLICY_VERSION = "jpegLaplacianVarianceV1:threshold110" }
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
    val requiresPose: Boolean = true,
    val pipelineTimingMs: Map<String, Long> = emptyMap(),
    val exposureBracketMembers: List<ExposureBracketMember> = emptyList(),
)

/** One JPEG member of an app-owned three-exposure capture bundle. */
data class ExposureBracketMember(
    val exposureEv: Int,
    val imageBytes: ByteArray,
    val width: Int,
    val height: Int,
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long,
    val rollingShutterSkewNs: Long,
    val observedTimestampNs: Long?,
    val intrinsics: Map<String, Any>?,
) {
    val assetName: String
        get() = when {
            exposureEv < 0 -> "jpeg_ev$exposureEv"
            exposureEv > 0 -> "jpeg_ev+$exposureEv"
            else -> "jpeg"
        }
}

/** Atomically publishes the debug-only exposure route across platform and admission threads. */
internal class AttemptQualifiedExposureHookV2 {
    private data class Route(
        val request: (
            CaptureAttemptQualifierV2,
            Set<CaptureComponentKind>,
            SharedCameraExposureCallbackV2,
        ) -> Boolean,
        val cancel: (CaptureAttemptQualifierV2) -> Unit,
    )

    private val lock = Any()
    private var current: Route? = null
    private val owners = linkedMapOf<CaptureAttemptQualifierV2, Route>()
    private val retired = linkedSetOf<CaptureAttemptQualifierV2>()

    fun install(
        request: (
            CaptureAttemptQualifierV2,
            Set<CaptureComponentKind>,
            SharedCameraExposureCallbackV2,
        ) -> Boolean,
        cancel: (CaptureAttemptQualifierV2) -> Unit,
    ) {
        synchronized(lock) { current = Route(request, cancel) }
    }

    fun request(
        qualifier: CaptureAttemptQualifierV2,
        required: Set<CaptureComponentKind>,
        callback: SharedCameraExposureCallbackV2,
    ): Boolean? {
        val installed = synchronized(lock) {
            val selected = current ?: return null
            check(owners.size < MAX_ACTIVE_OWNERS || qualifier in owners) {
                "synthetic-exposure-owner-capacity"
            }
            check(owners.putIfAbsent(qualifier, selected) == null) {
                "synthetic-exposure-owner-duplicate"
            }
            retired.remove(qualifier)
            selected
        }
        return installed.request(qualifier, required, callback)
    }

    fun cancel(qualifier: CaptureAttemptQualifierV2): Boolean {
        val installed = synchronized(lock) {
            owners.remove(qualifier)?.also {
                retired += qualifier
                while (retired.size > MAX_RETIRED_OWNERS) retired.remove(retired.first())
            } ?: return qualifier in retired
        }
        installed.cancel(qualifier)
        return true
    }

    fun clear() {
        val active = synchronized(lock) {
            current = null
            owners.toList().also { entries ->
                owners.clear()
                entries.forEach { (qualifier, _) -> retired += qualifier }
                while (retired.size > MAX_RETIRED_OWNERS) retired.remove(retired.first())
            }
        }
        active.forEach { (qualifier, installed) -> installed.cancel(qualifier) }
    }

    internal fun activeOwnersForTest(): Int = synchronized(lock) { owners.size }

    private companion object {
        const val MAX_ACTIVE_OWNERS = 2
        const val MAX_RETIRED_OWNERS = 4
    }
}

/**
 * The ordinary shared-camera JPEG path receives hardware-encoded bytes. Its
 * material wait is shutter request to correlated image delivery; it does not
 * queue a software finalization worker or perform a second JPEG encode.
 */
internal fun hardwareJpegAcquisitionTimings(
    requestStartedAtNs: Long,
    correlatedFrameAtNs: Long,
): Map<String, Long> =
    mapOf(
        CapturePipelineTimingContract.REQUEST_TO_PROCESSED_FRAME to
            ((correlatedFrameAtNs - requestStartedAtNs).coerceAtLeast(0L) / 1_000_000L),
        CapturePipelineTimingContract.PRE_ACCEPTANCE_POSE to 0L,
        CapturePipelineTimingContract.FINALIZATION_QUEUE_WAIT to 0L,
        CapturePipelineTimingContract.JPEG_ENCODING to 0L,
    )

internal class SharedBlurRejectedException(val quality: Map<String, Any>) :
    RuntimeException("Capture rejected by the pre-encode blur filter")

internal data class SharedCaptureAccepted(
    val imageId: String,
    val width: Int,
    val height: Int,
    val format: String,
    val captureTimestampMs: Long,
    val alignedPose: PoseDataExtractor.AlignedPose?,
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
    private val nativeFrameAttribution: NativeFrameAttributionOwner? = null,
    private val exposureRefusalV2: (CaptureAttemptQualifierV2) -> String? = { "motion-unavailable" },
) {
    // Additive V2 hook. V1 ImageCacheManager/correlation ownership is never
    // consulted by this route; a per-view #101 binding installs the Camera2 hook.
    private val v2ExposureHook = AttemptQualifiedExposureHookV2()

    internal fun installAttemptQualifiedExposureHookV2(
        request: (CaptureAttemptQualifierV2, Set<CaptureComponentKind>, SharedCameraExposureCallbackV2) -> Boolean,
        cancel: (CaptureAttemptQualifierV2) -> Unit = {},
    ) = v2ExposureHook.install(request, cancel)

    internal fun requestAttemptQualifiedExposureV2(
        qualifier: CaptureAttemptQualifierV2,
        required: Set<CaptureComponentKind>,
        callback: SharedCameraExposureCallbackV2,
        maximumWorkingBytes: Long = 0L,
    ): Boolean = v2ExposureHook.request(qualifier, required, callback)
        ?: if (maximumWorkingBytes >= JpegBlurAnalyzer.WorkingMemoryBytes)
            requestDirectExposureV2(qualifier, required, callback) else false

    internal fun cancelAttemptQualifiedExposureV2(qualifier: CaptureAttemptQualifierV2) {
        if (!v2ExposureHook.cancel(qualifier)) cancelDirectExposureV2(qualifier)
    }

    internal fun clearAttemptQualifiedExposureHookV2() = v2ExposureHook.clear()

    companion object {
        private const val VendorCameraDrainWindowMs = 3_500L
        private const val ShutdownCompletionPollMs = 50L
        private const val StartupTimeoutMs = 5000L
        private const val ManualCaptureTimeoutMs = 5000L
        private const val ManualCaptureRequestTagPrefix = "capture3d_manual_shared_still"
        private const val ProcessedFrameMaximumSlots = 6
        private val processRestartGate =
            SharedCameraRestartGate(cooldownMs = VendorCameraDrainWindowMs)

        /**
         * Two complete YUV frames, represented by three independently leased
         * planes. Camera2's YUV_420_888 buffers can expose interleaved chroma
         * and row padding, so reserve four profile-pixel units (two bytes per
         * pixel across each complete frame) rather than the packed 1.5x JPEG
         * size. This remains a finite profile-derived bound.
         */
        private fun processedFrameMaximumBytes(profile: Size): Long {
            val pixels = profile.width.toLong() * profile.height.toLong()
            require(pixels > 0L)
            return Math.multiplyExact(pixels, 4L)
        }
    }

    private val config: ParsedCaptureConfig = ParsedCaptureConfig.fromMap(configMap)
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val capabilityQuerier = CameraCapabilityQuerier(context)
    private val stillCaptureCorrelator = PendingStillCaptureCorrelator(timeoutMs = 30_000L)
    // Processed capture is serialized by the pending-manual-capture gate. Keep
    // the large YUV plane copies in an owner-private bounded pool and return
    // them only after the worker has produced the immutable JPEG result. The
    // profile bound covers two complete YUV frames and is charged as retained
    // capture-owner capacity; exhaustion is a nonblocking capture refusal.
    // The configured size describes the requested JPEG output. Defer pool
    // creation until capability validation has selected the actual reader
    // stream size, so the byte bound covers the CPU-side YUV planes that the
    // processed path copies.
    private val processedFrameBuffers: CaptureByteArrayPool by lazy(LazyThreadSafetyMode.NONE) {
        CaptureByteArrayPool(
            maximumSlots = ProcessedFrameMaximumSlots,
            maximumBytes = processedFrameMaximumBytes(getEffectiveResolution()),
        )
    }
    private val processedFrameCorrelator = ProcessedFrameCorrelator<PackedYuv420>(
        onFrameDiscarded = { frame -> frame.release() },
    )
    private val startupBarrier = SharedCameraStartupBarrier()
    private val sessionCallbackFence = SharedCameraSessionCallbackFence()
    private val repeatingRequestLifecycle = SharedCameraRepeatingRequestLifecycle()
    private val rawJpegCaptureCorrelator =
        RawJpegCaptureCorrelator<PendingStillImagePayload, Image, TotalCaptureResult>(
            PendingStillImagePayload::sensorTimestampNs,
            {},
            ::closeTrackedImage,
        )
    private val directRawJpegCaptureCorrelator =
        RawJpegCaptureCorrelator<Image, Image, TotalCaptureResult>(
            Image::getTimestamp,
            ::closeTrackedImage,
            ::closeTrackedImage,
        )
    private val directComponentExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "capture3d-v2-component-stream").apply { isDaemon = true }
    }
    // Durable V2 commit reads the camera-owned component stream and may fsync
    // several megabytes. Keep that work off the Camera2 callback handler so an
    // automatic still cannot pause ARCore frame delivery while it is stored.
    private val directStoreExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "capture3d-v2-store-commit").apply { isDaemon = true }
    }
    private val pendingDirectStoreDispatches =
        mutableSetOf<DirectStoreDispatch>()
    private val jpegDimensionLedger = JpegDimensionLedger()
    private var attributionCameraEpoch = 0L
    private var stillCaptureMetadata: StillCaptureMetadata? = null
    private var matchStillPreviewFpsEffective = false
    private var videoSnapshotJpegEffective = false
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
                if (BuildConfig.NATIVE_FRAME_ATTRIBUTION) recordStillStarted(session, request, timestamp, frameNumber)
                val directTag = request.tag as? DirectCaptureTagV2
                if (directTag != null && pendingDirectCapture?.qualifier == directTag.qualifier) {
                    recordCaptureObservation(timestamp, System.nanoTime())
                    captureLatencyLog(
                        "camera_capture_started",
                        directTag.qualifier.identity.attemptId,
                        exposureTimestampNs = timestamp,
                    )
                    return
                }
                val pending = pendingManualCapture ?: return
                val requestTag = request.tag as? ManualCaptureTag ?: return
                if (requestTag.generation != pending.generation ||
                    !requestGeneration.isCurrent(requestTag.generation)
                ) {
                    return
                }
                recordCaptureObservation(timestamp, System.nanoTime())
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                if (BuildConfig.NATIVE_FRAME_ATTRIBUTION && session === captureSession) {
                    val ledger=stillCaptureMetadata
                    val token=if(ledger!=null) stillToken(request) else 0L
                    if(token!=0L) try {ledger?.put(token,50,failure.reason.toLong());ledger?.put(token,4,android.os.SystemClock.elapsedRealtimeNanos());ledger?.event(token,52)}
                    catch(_:Throwable){ledger?.probeError()} finally {ledger?.end(token)}
                }
                super.onCaptureFailed(session,request,failure)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (BuildConfig.NATIVE_FRAME_ATTRIBUTION) recordStillCompleted(session, request, result)
                val attribution = nativeFrameAttribution
                if (attribution != null && attribution.active && session === captureSession) {
                    try {
                        val intent = request.get(CaptureRequest.CONTROL_CAPTURE_INTENT)
                        if (request.tag == null && intent == CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD) {
                            val arrivalNs = attribution.arrivalElapsedNs()
                            attribution.repeatingResult(attributionCameraEpoch,
                                result.get(CaptureResult.SENSOR_TIMESTAMP), result.frameNumber, arrivalNs,
                                result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                                result.get(CaptureResult.SENSOR_FRAME_DURATION))
                        } else if (isOwnedStillRequest(request)) {
                            attribution.stillResult(attributionCameraEpoch, result.frameNumber)
                        }
                    } catch (_: Throwable) { attribution.recordCameraProbeFailure() }
                }
                latestPreviewExposureState =
                    buildObservedExposureState(result, runtimeCameraController)
                if (pendingManualCapture != null) {
                    Log.i(
                        "SharedCameraManager",
                        "Capture result frame=${result.frameNumber} tag=${request.tag}",
                    )
                }
                val directTag = request.tag as? DirectCaptureTagV2
                if (directTag != null && pendingDirectCapture?.qualifier == directTag.qualifier) {
                    captureLatencyLog(
                        "camera_capture_completed",
                        directTag.qualifier.identity.attemptId,
                    )
                    // RAW/JPEG delivery also needs this result to write the DNG.
                    // The JPEG-only path streams directly from its image reader.
                    if (config.rawJpeg) {
                        result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { sensorTimestampNs ->
                            handleRawJpegCaptureResult(sensorTimestampNs, result)
                        }
                    }
                    return
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
                    bracketIndex = requestTag.bracketIndex,
                )?.let(::handleCorrelatedStillCapture)
            }
        }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewImageReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null
    private var activeCameraCharacteristics: CameraCharacteristics? = null
    private val callbackThreads = CameraCallbackThreads<HandlerThread>({ it.quitSafely(); Unit }, SystemClock::elapsedRealtime)
    private var backgroundThread: HandlerThread?
        get() = callbackThreads.camera
        set(value) { callbackThreads.camera = value }
    private var backgroundHandler: Handler? = null
    private var captureCallbackThread: HandlerThread?
        get() = callbackThreads.capture
        set(value) { callbackThreads.capture = value }
    private var captureCallbackHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Reusable per-finalizer encoding storage; no encoded output is retained here. */
    private class JpegEncodingScratch {
        var nv21 = ByteArray(0)
        val output = ReusableJpegOutput()
        var accountedNv21Bytes = 0L
        var accountedOutputBytes = 0L

        fun ensureNv21(required: Int): ByteArray {
            if (nv21.size < required) nv21 = ByteArray(required)
            return nv21
        }

        fun clear() {
            nv21 = ByteArray(0)
            output.reset()
            accountedNv21Bytes = 0L
            accountedOutputBytes = 0L
        }
    }

    private class ReusableJpegOutput : ByteArrayOutputStream() {
        fun capacityBytes(): Int = buf.size
    }

    private val jpegEncodingScratch: ThreadLocal<JpegEncodingScratch?> = ThreadLocal()
    private val jpegScratchActiveOwners = AtomicLong(0L)
    private val jpegScratchNv21ResidentBytes = AtomicLong(0L)
    private val jpegScratchOutputResidentBytes = AtomicLong(0L)
    private val jpegScratchPeakNv21Bytes = AtomicLong(0L)
    private val jpegScratchPeakOutputBytes = AtomicLong(0L)

    private fun currentJpegEncodingScratch(): JpegEncodingScratch {
        jpegEncodingScratch.get()?.let { return it }
        return JpegEncodingScratch().also {
            jpegEncodingScratch.set(it)
            jpegScratchActiveOwners.incrementAndGet()
        }
    }

    private fun recordJpegScratchCapacity(scratch: JpegEncodingScratch) {
        val nv21Bytes = scratch.nv21.size.toLong()
        val outputBytes = scratch.output.capacityBytes().toLong()
        if (nv21Bytes != scratch.accountedNv21Bytes) {
            jpegScratchNv21ResidentBytes.addAndGet(nv21Bytes - scratch.accountedNv21Bytes)
            scratch.accountedNv21Bytes = nv21Bytes
        }
        if (outputBytes != scratch.accountedOutputBytes) {
            jpegScratchOutputResidentBytes.addAndGet(outputBytes - scratch.accountedOutputBytes)
            scratch.accountedOutputBytes = outputBytes
        }
        updateMaximum(jpegScratchPeakNv21Bytes, jpegScratchNv21ResidentBytes.get())
        updateMaximum(jpegScratchPeakOutputBytes, jpegScratchOutputResidentBytes.get())
    }

    private fun releaseCurrentJpegEncodingScratch() {
        val scratch = jpegEncodingScratch.get() ?: return
        jpegScratchNv21ResidentBytes.addAndGet(-scratch.accountedNv21Bytes)
        jpegScratchOutputResidentBytes.addAndGet(-scratch.accountedOutputBytes)
        scratch.clear()
        jpegEncodingScratch.remove()
        jpegScratchActiveOwners.decrementAndGet()
    }

    private fun updateMaximum(target: AtomicLong, candidate: Long) {
        while (true) {
            val current = target.get()
            if (candidate <= current || target.compareAndSet(current, candidate)) return
        }
    }

    private val finalizationThreadFactory = ThreadFactory { runnable ->
        Thread(
            {
                try {
                    runnable.run()
                } finally {
                    releaseCurrentJpegEncodingScratch()
                }
            },
            "capture3d-v2-finalizer",
        ).apply { isDaemon = true }
    }

    private val finalizationWorkersDelegate = lazy {
        val effectiveResolution = getEffectiveResolution()
        val transientBytes =
            effectiveResolution.width.toLong() * effectiveResolution.height.toLong() * 7L
        val workers =
            CaptureFinalizationWorkerPool.recommendedWorkerCount(
                availableProcessors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                transientBytesPerJob = transientBytes.coerceAtLeast(1L),
                memoryBudgetBytes = 256L * 1024L * 1024L,
            )
        CaptureFinalizationWorkerPool(
            workerCount = workers,
            queueCapacity = workers,
            threadFactory = finalizationThreadFactory,
        )
    }
    private val finalizationWorkers by finalizationWorkersDelegate

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
    private var latestPreviewExposureState: RuntimeObservedExposureState? = null
    @Volatile
    private var cleanupRequested = false
    @Volatile
    private var captureSessionClosed = false
    @Volatile
    private var cameraDeviceClosed = false
    @Volatile
    private var cameraCloseLatch = CountDownLatch(1)
    private val cameraDeviceCloseBarrier = CameraDeviceCloseBarrier<CameraDevice>()
    private var processShutdownGeneration: Long? = null
    private var sessionCallbackGeneration = 0L
    // Fixed diagnostic manager metadata: 36 portable scalar bytes, no payloads.
    // 0=unobserved, 1=entered, 2=returned/forwarded, 3=rejected, 4=threw.
    @Volatile private var diagnosticDeviceCloseGeneration = -1L
    @Volatile private var diagnosticSessionCloseGeneration = -1L
    @Volatile private var diagnosticDeviceCloseOutcome = 0
    @Volatile private var diagnosticSessionCloseOutcome = 0
    @Volatile private var diagnosticSessionWrappedCloseGeneration = -1L
    @Volatile private var diagnosticSessionWrappedCloseOutcome = 0

    private var isInitialized = false
    private val requestGeneration = CaptureRequestGeneration()
    private val pendingManualCaptureOwner = CaptureAttemptOwner<PendingManualCapture>()
    private val pendingManualCapture: PendingManualCapture?
        get() = pendingManualCaptureOwner.get()
    private val pendingDirectCaptureOwner =
        QualifiedCaptureAttemptOwnerV2<PendingDirectCaptureV2, CaptureAttemptQualifierV2>(
            PendingDirectCaptureV2::qualifier,
        )
    private val pendingDirectCapture: PendingDirectCaptureV2?
        get() = pendingDirectCaptureOwner.get()

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
        val requiresPose: Boolean = true,
        val exposureBracket: List<ExposureBracketSpec> = emptyList(),
        val requestStartedAtNs: Long = System.nanoTime(),
        @Volatile var result: SharedCameraCaptureResult? = null,
        @Volatile var error: Throwable? = null,
        @Volatile var preEncodeQuality: Map<String, Any>? = null,
        @Volatile var preAlignedPose: PoseDataExtractor.AlignedPose? = null,
        @Volatile var accepted: SharedCaptureAccepted? = null,
        val bracketedCaptures: MutableMap<Int, ExposureBracketMember> = mutableMapOf(),
    )

    /**
     * V2's image owner.  It is intentionally distinct from PendingManualCapture:
     * no ImageCacheManager reservation, image id, or V1 finalization worker can
     * observe this image. Camera2 Images remain owned until the durable store
     * closes their bounded component streams.
     */
    private data class PendingDirectCaptureV2(
        val qualifier: CaptureAttemptQualifierV2,
        val required: Set<CaptureComponentKind>,
        val callback: SharedCameraExposureCallbackV2,
    )

    private data class ExposureBracketSpec(
        val exposureEv: Int,
        val compensationSteps: Int,
    )

    private data class ManualCaptureTag(
        val generation: Long,
        val bracketIndex: Int = 0,
    ) {
        override fun toString(): String =
            "$ManualCaptureRequestTagPrefix:$generation:$bracketIndex"
    }

    /**
     * Starts Camera2 without ever blocking the Android main thread.
     *
     * SceneView and ARCore setup must stay on the caller (main) thread, but
     * Camera2 reports readiness through its background callback. Waiting for
     * that callback on the method-channel thread can turn a transient camera
     * startup failure into an ANR.
     */
    suspend fun initialize(cacheManager: ImageCacheManager) {
        if (isInitialized) {
            throw IllegalStateException("SharedCameraManager already initialized")
        }

        try {
            imageCacheManager = cacheManager
            if (BuildConfig.NATIVE_FRAME_ATTRIBUTION) {
                stillCaptureMetadata = StillCaptureMetadata.create(true, cacheManager::reserveStillCaptureMetadata) {
                    cacheManager.releaseStillCaptureMetadata(StillCaptureMetadata.PORTABLE_BYTES)
                }
            }
            awaitSharedCameraRestartWindow()
            startBackgroundThread()
            setupCameraBasedOnConfig()
            awaitSharedCameraStartup()
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
                STILL_READER_MAX_IMAGES,
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
                    STILL_READER_MAX_IMAGES,
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

        val openCameraStartedNs = SystemClock.elapsedRealtimeNanos()
        val cameraOpenGeneration = sessionCallbackGeneration
        val deviceStateCallback =
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    startupProbe("onOpened-enter", openCameraStartedNs)
                    cameraDevice = camera
                    val resolutionProbeStartedNs = SystemClock.elapsedRealtimeNanos()
                    startupProbe("resolution-probe-enter", openCameraStartedNs)
                    try {
                        validateSharedResolutionCandidates(camera)
                    } finally {
                        startupProbe("resolution-probe-exit", resolutionProbeStartedNs)
                    }
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    val ownsClosure = cameraDeviceCloseBarrier.bindClose(cameraOpenGeneration, camera)
                    camera.close()
                    if (!ownsClosure) {
                        closeDiagnostic("device-disconnected-close-rejected", cameraOpenGeneration)
                        return
                    }
                    cameraDevice = null
                    startupBarrier.fail("Shared camera disconnected during startup")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val ownsClosure = cameraDeviceCloseBarrier.bindClose(cameraOpenGeneration, camera)
                    camera.close()
                    if (!ownsClosure) {
                        closeDiagnostic("device-error-close-rejected", cameraOpenGeneration)
                        return
                    }
                    cameraDevice = null
                    startupBarrier.fail("Shared camera failed to open (error=$error)")
                    Log.e("SharedCameraManager", "Camera error: $error")
                }

                override fun onClosed(camera: CameraDevice) {
                    if (!cameraDeviceCloseBarrier.onClosed(cameraOpenGeneration, camera)) {
                        closeDiagnostic("device-application-close-rejected", cameraOpenGeneration)
                        return
                    }
                    cameraDeviceClosed = true
                    closeDiagnostic("device-application-closed", cameraOpenGeneration)
                    signalCameraCloseBarrierIfComplete()
                    scheduleBackgroundThreadShutdownWhenClosed()
                }
            }
        val wrappedDeviceStateCallback =
            sharedCamera.createARDeviceStateCallback(deviceStateCallback, backgroundHandler)
        val observedDeviceStateCallback = if (BuildConfig.NATIVE_FRAME_ATTRIBUTION) {
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = wrappedDeviceStateCallback.onOpened(camera)
                override fun onDisconnected(camera: CameraDevice) = wrappedDeviceStateCallback.onDisconnected(camera)
                override fun onError(camera: CameraDevice, error: Int) = wrappedDeviceStateCallback.onError(camera, error)
                override fun onClosed(camera: CameraDevice) {
                    diagnosticDeviceCloseGeneration = cameraOpenGeneration
                    diagnosticDeviceCloseOutcome = 1
                    closeDiagnostic("device-wrapped-enter", cameraOpenGeneration)
                    try {
                        wrappedDeviceStateCallback.onClosed(camera)
                        diagnosticDeviceCloseOutcome = 2
                        closeDiagnostic("device-wrapped-return", cameraOpenGeneration)
                    } catch (error: Throwable) {
                        diagnosticDeviceCloseOutcome = 4
                        closeDiagnostic("device-wrapped-threw:${error.javaClass.simpleName}", cameraOpenGeneration)
                        throw error
                    }
                }
            }
        } else wrappedDeviceStateCallback

        startupProbe("open-enter", openCameraStartedNs)
        try {
            cameraManager.openCamera(
                cameraId,
                observedDeviceStateCallback,
                backgroundHandler,
            )
        } finally {
            startupProbe("open-exit", openCameraStartedNs)
        }
    }

    private data class DirectCaptureTagV2(val qualifier: CaptureAttemptQualifierV2)

    private fun requestDirectExposureV2(
        qualifier: CaptureAttemptQualifierV2,
        required: Set<CaptureComponentKind>,
        callback: SharedCameraExposureCallbackV2,
    ): Boolean {
        val supported = required == setOf(CaptureComponentKind.JPEG) ||
            required == setOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG)
        if (!supported || !isInitialized) return false
        if (pendingManualCapture != null) return false
        if ((CaptureComponentKind.DNG in required) != config.rawJpeg) return false
        val pending = PendingDirectCaptureV2(qualifier, required, callback)
        if (!pendingDirectCaptureOwner.acquire(pending)) return false
        val activeSession = captureSession
        val builder = manualCaptureRequestBuilder
        if (activeSession == null || builder == null || !repeatingRequestLifecycle.isRepeatingActive()) {
            pendingDirectCaptureOwner.release(pending)
            return false
        }
        return try {
            builder.setTag(DirectCaptureTagV2(qualifier))
            captureLatencyLog("camera_request_submit", qualifier.identity.attemptId)
            val request = builder.build()
            val refusal = exposureRefusalV2(qualifier)
            if (refusal != null) {
                pendingDirectCaptureOwner.release(pending)
                callback.onFailure(qualifier, refusal)
                return false
            }
            if (BuildConfig.NATIVE_FRAME_ATTRIBUTION) recordStillSubmission(request)
            activeSession.capture(request, sharedCameraCaptureCallback, captureCallbackHandler)
            captureLatencyLog("camera_request_submitted", qualifier.identity.attemptId)
            true
        } catch (error: Throwable) {
            pendingDirectCaptureOwner.release(pending)
            callback.onFailure(qualifier, "camera-submit")
            false
        }
    }

    private fun cancelDirectExposureV2(qualifier: CaptureAttemptQualifierV2) {
        pendingDirectCaptureOwner.cancel(qualifier) ?: return
        directRawJpegCaptureCorrelator.clear()
    }

    private fun isOwnedStillRequest(request: CaptureRequest): Boolean = when (val tag = request.tag) {
        is ManualCaptureTag -> stillCaptureMetadata?.owns(
            sessionCallbackGeneration, StillCaptureMetadata.MANUAL, tag.generation, tag.bracketIndex,
        ) == true || requestGeneration.isCurrent(tag.generation)
        is DirectCaptureTagV2 -> stillCaptureMetadata?.owns(
            sessionCallbackGeneration, StillCaptureMetadata.DIRECT, tag.qualifier.exposureGeneration, 0,
        ) == true || pendingDirectCapture?.qualifier == tag.qualifier
        else -> false
    }

    private fun stillToken(request: CaptureRequest, submitting: Boolean = false): Long {
        val ledger=stillCaptureMetadata ?: return 0
        return try {
        val tag=request.tag
        val kind:Int;val generation:Long;val ordinal:Int
        when(tag) {
            is ManualCaptureTag -> {kind=StillCaptureMetadata.MANUAL;generation=tag.generation;ordinal=tag.bracketIndex}
            is DirectCaptureTagV2 -> {kind=StillCaptureMetadata.DIRECT;generation=tag.qualifier.exposureGeneration;ordinal=0}
            else -> return 0
        }
        if(submitting) ledger.submitted(sessionCallbackGeneration,kind,generation,ordinal,android.os.SystemClock.elapsedRealtimeNanos())
        else ledger.begin(sessionCallbackGeneration,kind,generation,ordinal)
        } catch (_: Throwable) { ledger.probeError(); 0L }
    }
    private fun recordStillSubmission(request: CaptureRequest) {
        val ledger=stillCaptureMetadata ?: return
        val token=stillToken(request,true);if(token==0L)return
        try {
            ledger.put(token,16,request.get(CaptureRequest.CONTROL_CAPTURE_INTENT)?.toLong())
            request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)?.let { ledger.put(token,18,it.lower.toLong());ledger.put(token,19,it.upper.toLong()) }
            ledger.put(token,22,request.get(CaptureRequest.CONTROL_AE_MODE)?.toLong())
            ledger.put(token,24,request.get(CaptureRequest.CONTROL_AF_MODE)?.toLong())
            ledger.put(token,26,request.get(CaptureRequest.CONTROL_AWB_MODE)?.toLong())
            ledger.put(token,30,request.get(CaptureRequest.EDGE_MODE)?.toLong())
            ledger.put(token,32,request.get(CaptureRequest.NOISE_REDUCTION_MODE)?.toLong())
            ledger.put(token,34,request.get(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE)?.toLong())
            ledger.put(token,36,request.get(CaptureRequest.TONEMAP_MODE)?.toLong())
            ledger.put(token,38,request.get(CaptureRequest.SHADING_MODE)?.toLong())
            ledger.put(token,28,request.get(CaptureRequest.CONTROL_ENABLE_ZSL)?.let {if(it)1L else 0L})
            ledger.put(token,40,request.get(CaptureRequest.JPEG_QUALITY)?.toInt()?.and(255)?.toLong())
        } catch (_: Throwable) { ledger.probeError() } finally {ledger.end(token)}
    }
    private fun recordStillStarted(session:CameraCaptureSession,request:CaptureRequest,sensor:Long,frame:Long) {
        val ledger=stillCaptureMetadata ?: return
        if(session !== captureSession) return
        val token=stillToken(request);if(token==0L)return
        try {ledger.put(token,1,sensor);ledger.put(token,2,frame);ledger.put(token,3,android.os.SystemClock.elapsedRealtimeNanos());ledger.event(token,51)}
        catch(_:Throwable){ledger.probeError()} finally{ledger.end(token)}
    }
    private fun recordStillCompleted(session:CameraCaptureSession,request:CaptureRequest,result:TotalCaptureResult) {
        val ledger=stillCaptureMetadata ?: return
        if(session !== captureSession) return
        val token=stillToken(request);if(token==0L)return
        try {
            ledger.put(token,4,android.os.SystemClock.elapsedRealtimeNanos());ledger.put(token,6,result.frameNumber);ledger.event(token,52)
            ledger.put(token,5,result.get(CaptureResult.SENSOR_TIMESTAMP))
            ledger.put(token,7,result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
            ledger.put(token,8,result.get(CaptureResult.SENSOR_FRAME_DURATION))
            ledger.put(token,9,result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW))
            ledger.put(token,17,result.get(CaptureResult.CONTROL_CAPTURE_INTENT)?.toLong())
            ledger.put(token,23,result.get(CaptureResult.CONTROL_AE_MODE)?.toLong())
            ledger.put(token,25,result.get(CaptureResult.CONTROL_AF_MODE)?.toLong())
            ledger.put(token,27,result.get(CaptureResult.CONTROL_AWB_MODE)?.toLong())
            ledger.put(token,31,result.get(CaptureResult.EDGE_MODE)?.toLong())
            ledger.put(token,33,result.get(CaptureResult.NOISE_REDUCTION_MODE)?.toLong())
            ledger.put(token,35,result.get(CaptureResult.COLOR_CORRECTION_ABERRATION_MODE)?.toLong())
            ledger.put(token,37,result.get(CaptureResult.TONEMAP_MODE)?.toLong())
            ledger.put(token,39,result.get(CaptureResult.SHADING_MODE)?.toLong())
            ledger.put(token,42,result.get(CaptureResult.SENSOR_SENSITIVITY)?.toLong())
            ledger.put(token,43,result.get(CaptureResult.CONTROL_AE_STATE)?.toLong())
            ledger.put(token,44,result.get(CaptureResult.CONTROL_AF_STATE)?.toLong())
            ledger.put(token,45,result.get(CaptureResult.CONTROL_AWB_STATE)?.toLong())
            result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.let {ledger.put(token,20,it.lower.toLong());ledger.put(token,21,it.upper.toLong())}
            result.get(CaptureResult.CONTROL_ENABLE_ZSL)?.let {ledger.put(token,29,if(it)1 else 0)}
            ledger.put(token,41,result.get(CaptureResult.JPEG_QUALITY)?.toInt()?.and(255)?.toLong())
            result.get(CaptureResult.SCALER_CROP_REGION)?.let {ledger.put(token,46,it.left.toLong());ledger.put(token,47,it.top.toLong());ledger.put(token,48,it.right.toLong());ledger.put(token,49,it.bottom.toLong())}
        } catch(_:Throwable){ledger.probeError()} finally{ledger.end(token)}
    }
    private fun recordStillImage(image:Image) {
        val ledger=stillCaptureMetadata ?: return
        try {
            if(image.format != ImageFormat.JPEG) return
            val token=ledger.image(sessionCallbackGeneration,image.timestamp,android.os.SystemClock.elapsedRealtimeNanos(),image.width,image.height,image.format)
            ledger.end(token)
        } catch(_:Throwable){ledger.probeError()}
    }

    private fun captureLatencyLog(stage: String, attemptId: String, exposureTimestampNs: Long? = null) {
        val sensorFields = if (exposureTimestampNs != null) {
            val realtime = activeCameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            // Bracket the elapsed clock with both monotonic clocks. Android
            // pointer event time is uptime-based; the interval preserves its
            // millisecond quantization instead of using a delayed log timestamp.
            val uptimeBeforeMs = SystemClock.uptimeMillis()
            val monotonicBeforeNs = System.nanoTime()
            val elapsedSampleNs = SystemClock.elapsedRealtimeNanos()
            val monotonicAfterNs = System.nanoTime()
            val uptimeAfterMs = SystemClock.uptimeMillis()
            " exposureTimestampNs=$exposureTimestampNs sensorClock=${if (realtime) "elapsedRealtime" else "unqualified"}" +
                " clockSampleElapsedNs=$elapsedSampleNs" +
                " clockSampleMonotonicBeforeNs=$monotonicBeforeNs clockSampleMonotonicAfterNs=$monotonicAfterNs" +
                " clockSampleUptimeBeforeMs=$uptimeBeforeMs clockSampleUptimeAfterMs=$uptimeAfterMs"
        } else ""
        Log.i(
            "CaptureLatency",
            "stage=$stage attemptId=$attemptId elapsedRealtimeNs=${SystemClock.elapsedRealtimeNanos()}$sensorFields",
        )
    }

    /** Temporary startup diagnosis; remove after the Camera2 handoff is explained. */
    private fun startupProbe(stage: String, originNs: Long) {
        Log.i(
            "SharedCameraManager",
            "[STARTUP-PROBE] stage=$stage thread=${Thread.currentThread().name} " +
                "elapsedNs=${SystemClock.elapsedRealtimeNanos() - originNs}",
        )
    }

    private suspend fun awaitSharedCameraRestartWindow() {
        var waitLogged = false
        while (true) {
            val remaining =
                processRestartGate.restartDelayMs(
                    nowMs = SystemClock.elapsedRealtime(),
                    completionPollMs = ShutdownCompletionPollMs,
                )
            if (remaining == 0L) return
            if (!waitLogged) {
                Log.i(
                    "SharedCameraManager",
                    "Waiting for previous Camera2 session ownership to drain",
                )
                waitLogged = true
            }
            delay(remaining)
        }
    }

    private suspend fun awaitSharedCameraStartup() {
        withContext(Dispatchers.IO) {
            startupBarrier.awaitReady(StartupTimeoutMs)
        }
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
                    STILL_READER_MAX_IMAGES,
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
                    highResolutionOutputSizes = if (config.format == ImageFormat.JPEG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        configMap.getHighResolutionOutputSizes(ImageFormat.JPEG)?.map { it.width to it.height } ?: emptyList()
                    else emptyList(),
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
            val sessionOpenGeneration = sessionCallbackGeneration
            val sessionStateCallback =
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        if (!configureCameraSettings()) {
                            return
                        }
                        attributionCameraEpoch = nativeFrameAttribution?.openCameraEpoch(
                            activeCameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) ?: 0L
                        val ledger = stillCaptureMetadata
                        if (ledger != null) try {
                            ledger.openSession(sessionCallbackGeneration, activeCameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
                        } catch (_: Throwable) { ledger.probeError() }
                        startRepeatingRequest()
                    }

                    override fun onActive(session: CameraCaptureSession) {
                        // Resume ARCore on the main thread, where SceneView
                        // resumes, pauses and closes it. A Session resumed on
                        // this camera callback thread outlives the thread (it
                        // is joined before the Session closes), and closing it
                        // then blocked the main thread for 10 s on the Galaxy A40.
                        mainHandler.post { resumeArSessionOnMain(sessionOpenGeneration) }
                    }

                    private fun resumeArSessionOnMain(openGeneration: Long) {
                        if (cleanupRequested ||
                            sessionCallbackGeneration != openGeneration ||
                            !repeatingRequestLifecycle.isRepeatingActive()
                        ) {
                            return
                        }
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
                        Log.e("SharedCameraManager", "Failed to configure capture session")
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        captureSessionClosed = true
                        closeDiagnostic("session-application-closed", sessionOpenGeneration)
                        signalCameraCloseBarrierIfComplete()
                        scheduleBackgroundThreadShutdownWhenClosed()
                    }
                }
            val wrappedSessionStateCallback =
                sharedCamera.createARSessionStateCallback(sessionStateCallback, backgroundHandler)
            val observedSessionStateCallback = if (BuildConfig.NATIVE_FRAME_ATTRIBUTION) {
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) = wrappedSessionStateCallback.onConfigured(session)
                    override fun onConfigureFailed(session: CameraCaptureSession) = wrappedSessionStateCallback.onConfigureFailed(session)
                    override fun onReady(session: CameraCaptureSession) = wrappedSessionStateCallback.onReady(session)
                    override fun onActive(session: CameraCaptureSession) = wrappedSessionStateCallback.onActive(session)
                    override fun onCaptureQueueEmpty(session: CameraCaptureSession) = wrappedSessionStateCallback.onCaptureQueueEmpty(session)
                    override fun onSurfacePrepared(session: CameraCaptureSession, surface: Surface) = wrappedSessionStateCallback.onSurfacePrepared(session, surface)
                    override fun onClosed(session: CameraCaptureSession) {
                        diagnosticSessionWrappedCloseGeneration = sessionOpenGeneration
                        diagnosticSessionWrappedCloseOutcome = 1
                        closeDiagnostic("session-wrapped-enter", sessionOpenGeneration)
                        try {
                            wrappedSessionStateCallback.onClosed(session)
                            diagnosticSessionWrappedCloseOutcome = 2
                            closeDiagnostic("session-wrapped-return", sessionOpenGeneration)
                        } catch (error: Throwable) {
                            diagnosticSessionWrappedCloseOutcome = 4
                            closeDiagnostic("session-wrapped-threw:${error.javaClass.simpleName}", sessionOpenGeneration)
                            throw error
                        }
                    }
                }
            } else wrappedSessionStateCallback
            val guardedSessionStateCallback =
                guardArCoreStartupStateCallback(
                    observedSessionStateCallback,
                    sessionCallbackGeneration,
                )
            cameraDevice.createCaptureSession(
                sessionSurfaces,
                guardedSessionStateCallback,
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
                    session?.cameraConfig?.fpsRange?.let { arCoreFpsRange ->
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, arCoreFpsRange)
                        Log.i(
                            "SharedCameraManager",
                            "Requested shared-camera repeating range $arCoreFpsRange fps",
                        )
                    }
                }
            previewCaptureRequestBuilder = repeatingBuilder

            var requestBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    arCoreSurfaces.forEach(::addTarget)
                    addTarget(previewImageReader.surface)
                    rawImageReader?.surface?.let(::addTarget)
                }
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

            check(!(BuildConfig.MATCH_STILL_PREVIEW_FPS && BuildConfig.VIDEO_SNAPSHOT_JPEG)) {
                "Still FPS match and video snapshot JPEG experiments are mutually exclusive"
            }
            videoSnapshotJpegEffective = false
            if (BuildConfig.VIDEO_SNAPSHOT_JPEG) {
                val characteristics = activeCameraCharacteristics
                    ?: error("Video snapshot experiment requires active camera characteristics")
                check(cameraDevice.id == "0" && !config.processed && !config.rawJpeg &&
                    config.format == ImageFormat.JPEG && rawImageReader == null) {
                    "Video snapshot experiment supports ordinary primary camera0 JPEG only"
                }
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?: error("Video snapshot experiment requires capability qualification")
                check(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA !in capabilities &&
                    characteristics.physicalCameraIds.isEmpty()) {
                    "Video snapshot experiment does not qualify logical/physical camera overrides"
                }
                // Both requests use the no-physical-ID, non-reprocess constructor. Android's
                // public request API has no getTargets; these exact source-owned surfaces
                // are the complete additions to the initially untargeted builders.
                val ownedTargets = arCoreSurfaces + previewImageReader.surface
                var copiedCandidate: CaptureRequest.Builder? = null
                val adapter = object : VideoSnapshotRequestAdapter<CaptureRequest.Builder, CaptureRequest.Key<*>> {
                    override val captureIntentKey = CaptureRequest.CONTROL_CAPTURE_INTENT
                    override val stillCaptureIntent: Any = CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
                    override val videoSnapshotIntent: Any = CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT
                    override fun controls(request: CaptureRequest.Builder): Map<CaptureRequest.Key<*>, Any?> {
                        val snapshot = request.build()
                        return snapshot.keys.filter { it.name.startsWith("android.") }
                            .associateWith { snapshot.get(it) }
                    }
                    override fun createVideoSnapshot(): CaptureRequest.Builder =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT)
                    @Suppress("UNCHECKED_CAST")
                    override fun setControl(request: CaptureRequest.Builder, key: CaptureRequest.Key<*>, value: Any) {
                        request.set(key as CaptureRequest.Key<Any>, value)
                    }
                    override fun copyTargetsAndTag(baseline: CaptureRequest.Builder, candidate: CaptureRequest.Builder) {
                        ownedTargets.forEach(candidate::addTarget)
                        candidate.setTag(baseline.build().tag)
                        copiedCandidate = candidate
                    }
                    override fun targetsAndTagMatch(baseline: CaptureRequest.Builder, candidate: CaptureRequest.Builder): Boolean =
                        copiedCandidate === candidate && baseline.build().tag === candidate.build().tag
                    override fun isOrdinaryPrimaryRequest(request: CaptureRequest.Builder): Boolean =
                        !request.build().isReprocess
                }
                requestBuilder = VideoSnapshotRequestPolicy.transform(true, requestBuilder, adapter)
                videoSnapshotJpegEffective = true
            }
            manualCaptureRequestBuilder = requestBuilder

            matchStillPreviewFpsEffective = false
            if (BuildConfig.MATCH_STILL_PREVIEW_FPS) {
                matchStillPreviewFpsEffective = StillPreviewFpsPolicy.apply(
                    enabled = true,
                    repeatingRange = { repeatingBuilder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE) },
                    advertisedRanges = {
                        activeCameraCharacteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.asList()
                    },
                    setStillRange = { requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) },
                )
            }

            val availableAfModes = activeCameraCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            val ordinaryAfMode = ordinaryAutofocusMode(availableAfModes)
            val regionAfMode = if (availableAfModes?.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) == true)
                CaptureRequest.CONTROL_AF_MODE_AUTO else ordinaryAfMode
            runtimeCameraController = RuntimeCameraController(
                baseRequestBuilder = requestBuilder,
                onSettingsChanged = { runtimeState -> syncRepeatingRequestWithRuntimeSettings(runtimeState) },
                ordinaryAfMode = ordinaryAfMode,
                regionAfMode = regionAfMode,
                onFocusTrigger = { runtimeState, trigger -> submitRuntimeFocusTrigger(runtimeState, trigger) },
                supportsAfMetering = (activeCameraCharacteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0,
            ).also { controller ->
                controller.applyCurrentFocusSettings(requestBuilder)
                controller.applyCurrentFocusSettings(repeatingBuilder)
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

    /** Delegates lifecycle preparation to the SceneView 4.47.0 renderer host. */
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
            runtimeCameraController?.initializeOrdinaryAutofocus()
            Log.i("SharedCameraManager", "Shared-camera repeating request started")
        } catch (e: Exception) {
            startupBarrier.fail("Failed to start shared camera repeating request: ${e.message}")
            Log.e("SharedCameraManager", "Failed to start repeating request", e)
        }
    }

    private fun submitRuntimeFocusTrigger(state: RuntimeCameraSettingsState, trigger: Int) {
        if (!repeatingRequestLifecycle.shouldSubmitRuntimeUpdate()) return
        val activeSession = captureSession ?: return
        val builder = previewCaptureRequestBuilder ?: return
        try {
            state.applyCurrentFocusSettings(builder)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
            activeSession.capture(builder.build(), sharedCameraCaptureCallback, captureCallbackHandler)
        } catch (error: Exception) {
            Log.e("SharedCameraManager", "Unable to submit runtime focus control", error)
        } finally {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
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
        var imageTransferred = false
        try {
            if (image.format == ImageFormat.JPEG) jpegDimensionLedger.record(image.width, image.height, reader.width, reader.height)
            if (BuildConfig.NATIVE_FRAME_ATTRIBUTION) recordStillImage(image)
            val direct = pendingDirectCapture
            if (pendingManualCapture == null && direct == null) {
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
            if (direct != null) {
                captureLatencyLog("camera_image_arrived", direct.qualifier.identity.attemptId)
                if (CaptureComponentKind.DNG in direct.required) {
                    imageTransferred = true
                    directRawJpegCaptureCorrelator.onJpeg(image)
                        ?.let(::handleCorrelatedDirectRawJpegCapture)
                } else if (pendingDirectCaptureOwner.release(direct)) {
                    imageTransferred = true
                    val jpeg = CameraPlaneInputStreamV2(
                        image.planes.single().buffer,
                    ) { closeTrackedImage(image) }
                        dispatchDirectStoreComponents(
                            direct = direct,
                            streams = listOf(
                                CaptureComponentStreamV2(CaptureComponentKind.JPEG, jpeg),
                            ),
                            exposureTimestampNanoseconds = image.timestamp,
                            jpegForAnalysis = image.planes.single().buffer.asReadOnlyBuffer(),
                        )
                }
                return
            }
            val jpegBytes = nativeFrameAttribution.measureNativeFrame(NativeFrameSpan.JPEG_COPY) {
                readImageBytes(image)
            }
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
            pendingDirectCapture?.let { direct ->
                if (pendingDirectCaptureOwner.release(direct)) {
                    direct.callback.onFailure(direct.qualifier, "camera-component")
                }
            }
            pendingManualCapture?.let { pending ->
                pending.error = error
                pending.latch.countDown()
            }
        } finally {
            if (!imageTransferred) closeTrackedImage(image)
        }
    }

    private fun handleRawImageAvailable(reader: ImageReader) {
        val image = reader.acquireNextImage() ?: return
        resourceCounters.onImageAcquired()
        if (pendingManualCapture == null && pendingDirectCapture == null) {
            closeTrackedImage(image)
            return
        }
        val direct = pendingDirectCapture
        if (direct != null) {
            captureLatencyLog(
                "camera_raw_image_arrived",
                direct.qualifier.identity.attemptId,
            )
            directRawJpegCaptureCorrelator.onRaw(image.timestamp, image)
                ?.let(::handleCorrelatedDirectRawJpegCapture)
        } else {
            rawJpegCaptureCorrelator.onRaw(image.timestamp, image)
                ?.let(::handleCorrelatedRawJpegCapture)
        }
    }

    private fun handleRawJpegImage(image: PendingStillImagePayload) {
        rawJpegCaptureCorrelator.onJpeg(image)
            ?.let(::handleCorrelatedRawJpegCapture)
    }

    private fun handleRawJpegCaptureResult(
        sensorTimestampNs: Long,
        result: TotalCaptureResult,
    ) {
        if (pendingDirectCapture != null) {
            directRawJpegCaptureCorrelator.onResult(sensorTimestampNs, result)
                ?.let(::handleCorrelatedDirectRawJpegCapture)
        } else {
            rawJpegCaptureCorrelator.onResult(sensorTimestampNs, result)
                ?.let(::handleCorrelatedRawJpegCapture)
        }
    }

    private fun handleCorrelatedRawJpegCapture(
        capture: CorrelatedRawJpegCapture<PendingStillImagePayload, Image, TotalCaptureResult>,
    ) {
        val jpeg = capture.jpeg
        val rawImage = capture.raw
        val totalResult = capture.result
        val pending = pendingManualCapture
        val direct = pendingDirectCapture
        val characteristics = activeCameraCharacteristics
        if ((pending == null && direct == null) || characteristics == null) {
            closeTrackedImage(rawImage)
            return
        }
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val closeRaw = {
            if (closed.compareAndSet(false, true)) closeTrackedImage(rawImage)
        }
        try {
            val manual = checkNotNull(pending)
            val imageId = generateImageId()
            val sensorTimestampNs =
                totalResult.get(CaptureResult.SENSOR_TIMESTAMP) ?: jpeg.sensorTimestampNs
            manual.result =
                SharedCameraCaptureResult(
                    reservationToken = manual.reservationToken,
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
                    requiresPose = manual.requiresPose,
                )
            manual.latch.countDown()
        } catch (error: Throwable) {
            closeRaw()
            if (direct != null && pendingDirectCaptureOwner.release(direct)) {
                direct.callback.onFailure(direct.qualifier, "raw-component")
            }
            pending?.let { manual ->
                imageCacheManager?.releaseReservation(manual.reservationToken)
                manual.error = error
                manual.latch.countDown()
            }
        }
    }

    private class PackedYuv420(
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
        private val lease: PlaneBufferLease,
    ) {
        fun release() = lease.release()
    }

    private class PlaneBufferLease(
        internal val yLease: CaptureByteArrayPool.Lease,
        internal val uLease: CaptureByteArrayPool.Lease,
        internal val vLease: CaptureByteArrayPool.Lease,
    ) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (!released.compareAndSet(false, true)) return
            yLease.close()
            uLease.close()
            vLease.close()
        }
    }

    private fun copyYuv420Image(image: Image): PackedYuv420 {
        require(image.format == ImageFormat.YUV_420_888)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val crop = image.cropRect
        var yLease: CaptureByteArrayPool.Lease? = null
        var uLease: CaptureByteArrayPool.Lease? = null
        var vLease: CaptureByteArrayPool.Lease? = null
        var planeLease: PlaneBufferLease? = null
        return try {
            yLease = processedFrameBuffers.acquire(y.buffer.remaining())
                ?: throw CaptureSessionException(
                    code = "CAPTURE_BUFFER_PRESSURE",
                    message = "YUV capture scratch capacity is temporarily exhausted",
                )
            uLease = processedFrameBuffers.acquire(u.buffer.remaining())
                ?: throw CaptureSessionException(
                    code = "CAPTURE_BUFFER_PRESSURE",
                    message = "YUV capture scratch capacity is temporarily exhausted",
                )
            vLease = processedFrameBuffers.acquire(v.buffer.remaining())
                ?: throw CaptureSessionException(
                    code = "CAPTURE_BUFFER_PRESSURE",
                    message = "YUV capture scratch capacity is temporarily exhausted",
                )
            val lease = PlaneBufferLease(
                checkNotNull(yLease),
                checkNotNull(uLease),
                checkNotNull(vLease),
            )
            planeLease = lease
            yLease = null
            uLease = null
            vLease = null
            copyPlaneBuffer(y.buffer, lease.yLease.buffer)
            copyPlaneBuffer(u.buffer, lease.uLease.buffer)
            copyPlaneBuffer(v.buffer, lease.vLease.buffer)
            PackedYuv420(
                crop.width(), crop.height(),
                lease.yLease.buffer, y.rowStride, y.pixelStride,
                lease.uLease.buffer, u.rowStride, u.pixelStride,
                lease.vLease.buffer, v.rowStride, v.pixelStride,
                crop.left, crop.top,
                lease,
            )
        } catch (error: Throwable) {
            planeLease?.release()
            yLease?.close()
            uLease?.close()
            vLease?.close()
            throw error
        }
    }

    private fun copyPlaneBuffer(source: ByteBuffer, destination: ByteArray) {
        val copy = source.duplicate()
        copy.rewind()
        check(copy.remaining() <= destination.size)
        copy.get(destination, 0, copy.remaining())
    }

    private fun encodePackedYuv420AsJpeg(yuv: PackedYuv420): ByteArray {
        val width = yuv.width
        val height = yuv.height
        val scratch = currentJpegEncodingScratch()
        val nv21 = scratch.ensureNv21(width * height + width * height / 2)
        recordJpegScratchCapacity(scratch)
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
        scratch.output.reset()
        try {
            check(
                YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(
                    Rect(0, 0, width, height),
                    config.jpegQuality,
                    scratch.output,
                ),
            ) { "Android YUV JPEG encoder rejected the shared-camera frame" }
        } finally {
            recordJpegScratchCapacity(scratch)
        }
        // The returned bytes are immutable capture output. The stream's
        // backing buffer remains reusable only for the next conversion.
        return scratch.output.toByteArray()
    }

    private fun handleCorrelatedProcessedFrame(
        correlated: CorrelatedProcessedFrame<PackedYuv420>,
    ) {
        val pending = pendingManualCapture ?: run {
            correlated.frame.release()
            return
        }
        val processedFrameAtNs = System.nanoTime()
        val timing =
            PoseDataExtractor.CaptureTiming(
                sensorTimestampNs = correlated.result.sensorTimestampNs,
                exposureTimeNs = correlated.result.exposureTimeNs,
                rollingShutterSkewNs = correlated.result.rollingShutterSkewNs,
                observedTimestampNs = correlated.result.observedTimestampNs,
            )
        val poseStartedAtNs = System.nanoTime()
        val alignedPose = if (pending.requiresPose) resolvePoseBeforeEncoding(timing) else null
        val poseResolutionMs = (System.nanoTime() - poseStartedAtNs) / 1_000_000L
        if (pending.requiresPose && alignedPose == null) {
            pending.error =
                CaptureSessionException(
                    code = "POSE_SYNC_FAILED",
                    message = "No exact or interpolated pose was available before encoding",
                )
            pending.latch.countDown()
            correlated.frame.release()
            return
        }
        pending.preAlignedPose = alignedPose
        if (alignedPose != null) {
            Log.i(
                "SharedCameraManager",
                "Pose ${alignedPose.poseAlignment} resolved before finalization " +
                    "for sensor timestamp ${correlated.result.sensorTimestampNs}",
            )
        }
        val imageId = generateImageId()
        val workerStartGate = CountDownLatch(1)
        val workerSubmittedAtNs = System.nanoTime()
        val submitted =
            finalizationWorkers.submit(
                onCancelBeforeRun = correlated.frame::release,
                job = {
                    try {
                        workerStartGate.await()
                        val workerStartedAtNs = System.nanoTime()
                        val encodingStartedAtNs = System.nanoTime()
                        val encodedBytes = encodePackedYuv420AsJpeg(correlated.frame)
                        val jpegEncodingMs = (System.nanoTime() - encodingStartedAtNs) / 1_000_000L
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
                                requiresPose = pending.requiresPose,
                                pipelineTimingMs =
                                    mapOf(
                                        CapturePipelineTimingContract.REQUEST_TO_PROCESSED_FRAME to
                                            ((processedFrameAtNs - pending.requestStartedAtNs) / 1_000_000L),
                                        CapturePipelineTimingContract.PRE_ACCEPTANCE_POSE to poseResolutionMs,
                                        CapturePipelineTimingContract.FINALIZATION_QUEUE_WAIT to
                                            ((workerStartedAtNs - workerSubmittedAtNs) / 1_000_000L),
                                        CapturePipelineTimingContract.JPEG_ENCODING to jpegEncodingMs,
                                    ),
                            )
                        onCaptureEncoded(encoded, pending.qualityPolicy)
                        Log.i(
                            "SharedCameraManager",
                            "Encoded ${encodedBytes.size} ${assetName.uppercase()} bytes in " +
                                "${jpegEncodingMs}ms",
                        )
                    } catch (error: Throwable) {
                        pending.error = error
                        imageCacheManager?.releaseReservation(pending.reservationToken)
                        onCaptureFinalizationFailed(imageId, error)
                    } finally {
                        correlated.frame.release()
                    }
                },
            )
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

    private fun clearPendingRawJpegComponents() {
        rawJpegCaptureCorrelator.clear()
        directRawJpegCaptureCorrelator.clear()
    }

    private fun closeTrackedImage(image: Image) {
        if (BuildConfig.NATIVE_FRAME_ATTRIBUTION && stillCaptureMetadata != null) {
            closeStillTrackedImage(image)
            return
        }
        closeTrackedImageCore(image)
    }
    private fun closeTrackedImageCore(image: Image) {
        nativeFrameAttribution.measureNativeFrame(NativeFrameSpan.IMAGE_CLOSE) {
            image.close()
            resourceCounters.onImageClosed()
        }
    }
    private fun closeStillTrackedImage(image: Image) {
        val ledger = stillCaptureMetadata
        var token = 0L
        try { if (image.format == ImageFormat.JPEG) token = ledger?.imageCloseBegin(sessionCallbackGeneration, image.timestamp) ?: 0L }
        catch (_: Throwable) { ledger?.probeError() }
        try { closeTrackedImageCore(image) }
        catch (failure: Throwable) {
            try { ledger?.put(token,55,1) } catch (_: Throwable) { ledger?.probeError() }
            throw failure
        } finally {
            if (token != 0L) {
                try {
                    ledger?.put(token,12,android.os.SystemClock.elapsedRealtimeNanos())
                    ledger?.event(token,54)
                } catch (_: Throwable) { ledger?.probeError() }
                finally { ledger?.end(token) }
            }
        }
    }

    private fun exposureBracketSpecs(): List<ExposureBracketSpec> {
        if (config.rawJpeg || config.processed) {
            throw CaptureSessionException(
                "HDR_BRACKET_UNSUPPORTED",
                "HDR exposure bracketing is available for JPEG capture only",
            )
        }
        val characteristics = activeCameraCharacteristics
            ?: throw CaptureSessionException("CONTROL_UNSUPPORTED", "Camera characteristics are unavailable")
        val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: throw CaptureSessionException("CONTROL_UNSUPPORTED", "Exposure compensation is unavailable")
        val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?.toFloat()
            ?.takeIf { it > 0f }
            ?: throw CaptureSessionException("CONTROL_UNSUPPORTED", "Exposure compensation step is unavailable")
        val specs = listOf(-2, 0, 2).map { exposureEv ->
            ExposureBracketSpec(
                exposureEv = exposureEv,
                compensationSteps = (exposureEv / step).roundToInt().coerceIn(range.lower, range.upper),
            )
        }
        if (specs.map(ExposureBracketSpec::compensationSteps).toSet().size != specs.size) {
            throw CaptureSessionException(
                "HDR_BRACKET_UNSUPPORTED",
                "This camera cannot produce distinct -2 EV, 0 EV, and +2 EV exposures",
            )
        }
        return specs
    }

    private fun handleCorrelatedDirectRawJpegCapture(
        capture: CorrelatedRawJpegCapture<Image, Image, TotalCaptureResult>,
    ) {
        val direct = pendingDirectCapture
        val characteristics = activeCameraCharacteristics
        if (direct == null || characteristics == null || !pendingDirectCaptureOwner.release(direct)) {
            closeTrackedImage(capture.jpeg)
            closeTrackedImage(capture.raw)
            return
        }
        val rawClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        val closeRaw = {
            if (rawClosed.compareAndSet(false, true)) closeTrackedImage(capture.raw)
        }
        val dng = try {
            BoundedProducerInputStreamV2.start(
                executor = directComponentExecutor,
                capacityBytes = 64 * 1024,
                closeOwner = closeRaw,
            ) { output ->
                try {
                    DngCreator(characteristics, capture.result).use { creator ->
                        creator.writeImage(output, capture.raw)
                    }
                } finally {
                    closeRaw()
                }
            }
        } catch (error: Throwable) {
            closeRaw()
            closeTrackedImage(capture.jpeg)
            throw error
        }
        val jpeg = CameraPlaneInputStreamV2(capture.jpeg.planes.single().buffer) {
            closeTrackedImage(capture.jpeg)
        }
        try {
            dispatchDirectStoreComponents(
                direct = direct,
                streams = listOf(
                    CaptureComponentStreamV2(CaptureComponentKind.JPEG, jpeg),
                    CaptureComponentStreamV2(CaptureComponentKind.DNG, dng),
                ),
                exposureTimestampNanoseconds =
                    capture.result.get(CaptureResult.SENSOR_TIMESTAMP) ?: capture.jpeg.timestamp,
                jpegForAnalysis = capture.jpeg.planes.single().buffer.asReadOnlyBuffer(),
            )
        } catch (error: Throwable) {
            jpeg.close()
            dng.close()
            throw error
        }
    }

    private fun dispatchDirectStoreComponents(
        direct: PendingDirectCaptureV2,
        streams: List<CaptureComponentStreamV2>,
        exposureTimestampNanoseconds: Long,
        jpegForAnalysis: java.nio.ByteBuffer,
    ) {
        val dispatch = DirectStoreDispatch(
            components = SharedCameraComponentSetV2(
                direct.qualifier,
                streams,
                exposureTimestampNanoseconds,
            ),
            callback = direct.callback::onComponents,
            retainAssessedOriginals = direct.callback.retainAssessedOriginals,
            onFailure = { reason ->
                runCatching { direct.callback.onFailure(direct.qualifier, reason) }
            },
            analyzeJpeg = { cancelled ->
                JpegBlurAnalyzer.analyzeLaplacianVariance(
                    { CameraPlaneInputStreamV2(jpegForAnalysis.asReadOnlyBuffer()) {} },
                    cancelled,
                )
            },
            onQuality = { quality ->
                Log.i("CaptureJpegQuality",
                    "attempt=" + direct.qualifier.identity.attemptId +
                        " algorithm=" + quality.algorithm + " score=" + quality.blurScore +
                        " threshold=" + JpegBlurAnalyzer.ProductionThreshold +
                        " sample=" + quality.analyzedWidth + "x" + quality.analyzedHeight)
            },
        )
        synchronized(pendingDirectStoreDispatches) {
            pendingDirectStoreDispatches += dispatch
        }
        try {
            directStoreExecutor.execute {
                try {
                    dispatch.run()
                } finally {
                    synchronized(pendingDirectStoreDispatches) {
                        pendingDirectStoreDispatches -= dispatch
                    }
                }
            }
        } catch (error: Throwable) {
            synchronized(pendingDirectStoreDispatches) {
                pendingDirectStoreDispatches -= dispatch
            }
            dispatch.cancel("component-dispatch")
            throw error
        }
    }

    private fun guardArCoreStartupStateCallback(
        callback: CameraCaptureSession.StateCallback,
        generation: Long,
    ): CameraCaptureSession.StateCallback =
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) =
                forwardArCoreSessionCallback("configured", session, generation) {
                    callback.onConfigured(session)
                }

            override fun onConfigureFailed(session: CameraCaptureSession) =
                forwardArCoreSessionCallback("configureFailed", session, generation) {
                    callback.onConfigureFailed(session)
                    failSharedCameraConfiguration(
                        session = session,
                        stage = "configureFailed",
                        cause = null,
                    )
                }

            override fun onReady(session: CameraCaptureSession) =
                forwardArCoreSessionCallback("ready", session, generation) {
                    callback.onReady(session)
                }

            override fun onActive(session: CameraCaptureSession) =
                forwardArCoreSessionCallback("active", session, generation) {
                    callback.onActive(session)
                }

            override fun onCaptureQueueEmpty(session: CameraCaptureSession) =
                forwardArCoreSessionCallback("queueEmpty", session, generation) {
                    callback.onCaptureQueueEmpty(session)
                }

            override fun onClosed(session: CameraCaptureSession) =
                forwardArCoreSessionCallback("closed", session, generation, terminal = true) {
                    callback.onClosed(session)
                }

            override fun onSurfacePrepared(session: CameraCaptureSession, surface: Surface) =
                forwardArCoreSessionCallback("surfacePrepared", session, generation) {
                    callback.onSurfacePrepared(session, surface)
                }
        }

    private fun forwardArCoreSessionCallback(
        stage: String,
        session: CameraCaptureSession,
        generation: Long,
        terminal: Boolean = false,
        callback: () -> Unit,
    ) {
        SharedCameraCallbackGuard.run(
            onFailure = { error ->
                if (terminal && BuildConfig.NATIVE_FRAME_ATTRIBUTION) {
                    diagnosticSessionCloseOutcome = 4
                    closeDiagnostic("session-gate-threw:${error.javaClass.simpleName}", generation)
                }
                if (cleanupRequested) {
                    Log.w("SharedCameraManager", "Ignored ARCore callback failure during $stage teardown", error)
                } else {
                    failSharedCameraConfiguration(
                        session = session,
                        stage = stage,
                        cause = error,
                    )
                }
            },
        ) {
            if (terminal) {
                if (BuildConfig.NATIVE_FRAME_ATTRIBUTION) {
                    diagnosticSessionCloseGeneration = generation
                    diagnosticSessionCloseOutcome = 1
                    closeDiagnostic("session-gate-enter", generation)
                }
                val forwarded = sessionCallbackFence.runTerminal(generation, callback)
                if (BuildConfig.NATIVE_FRAME_ATTRIBUTION) {
                    diagnosticSessionCloseOutcome = if (forwarded) 2 else 3
                    closeDiagnostic(if (forwarded) "session-gate-forwarded" else "session-gate-rejected", generation)
                }
            } else {
                sessionCallbackFence.runActive(generation, callback)
            }
        }
    }

    private fun failSharedCameraConfiguration(
        session: CameraCaptureSession,
        stage: String,
        cause: Throwable?,
    ) {
        val error =
            SharedCameraStartupException(
                reason = SharedCameraStartupFailureReason.SESSION_CONFIGURATION,
                message = "ARCore shared-camera session configuration failed at $stage",
                cause = cause,
            )
        startupBarrier.fail(error)
        runCatching { session.close() }
        Log.e(
            "SharedCameraManager",
            "ARCore shared-camera session configuration failed at $stage",
            cause,
        )
    }

    private fun captureImage(exposureBracket: List<ExposureBracketSpec> = emptyList()): Boolean {
        val activeSession = captureSession ?: return false
        val builder = manualCaptureRequestBuilder ?: return false
        if (!repeatingRequestLifecycle.isRepeatingActive()) {
            return false
        }
        val pending = pendingManualCapture ?: return false
        val generation = pending.generation
        return try {
            if (exposureBracket.isEmpty()) {
                builder.setTag(ManualCaptureTag(generation))
                activeSession.capture(
                    builder.build().also { if (BuildConfig.NATIVE_FRAME_ATTRIBUTION) recordStillSubmission(it) },
                    sharedCameraCaptureCallback,
                    captureCallbackHandler,
                )
            } else {
                val originalCompensation =
                    runtimeCameraController?.getCurrentExposureCompensationSteps() ?: 0
                val requests = exposureBracket.mapIndexed { index, member ->
                    builder.setTag(ManualCaptureTag(generation, index))
                    builder.set(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        member.compensationSteps,
                    )
                    builder.build().also { if (BuildConfig.NATIVE_FRAME_ATTRIBUTION) recordStillSubmission(it) }
                }
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, originalCompensation)
                activeSession.captureBurst(
                    requests,
                    sharedCameraCaptureCallback,
                    captureCallbackHandler,
                )
            }
            true
        } catch (error: Exception) {
            Log.e("SharedCameraManager", "Failed to submit one-shot shared capture", error)
            false
        }
    }

    fun captureImageResult(
        qualityPolicy: CaptureQualityPolicy? = null,
        requiresPose: Boolean = true,
        exposureBracketEnabled: Boolean = false,
        timeoutMs: Long = ManualCaptureTimeoutMs,
    ): SharedCameraCaptureResult {
        if (!isInitialized) {
            throw IllegalStateException("SharedCameraManager is not initialized")
        }
        if (pendingDirectCapture != null) {
            throw CaptureSessionException("CAPTURE_IN_PROGRESS", "A native V2 capture is in progress")
        }
        val exposureBracket = if (exposureBracketEnabled) exposureBracketSpecs() else emptyList()
        val reservationToken =
            imageCacheManager?.reserveCaptureSlot()
                ?: throw IllegalStateException("ImageCacheManager is not initialized")
        val pending = PendingManualCapture(
            CountDownLatch(1),
            reservationToken,
            qualityPolicy,
            requestGeneration.next(),
            requiresPose,
            exposureBracket,
        )
        if (!pendingManualCaptureOwner.acquire(pending)) {
            imageCacheManager?.releaseReservation(reservationToken)
            throw CaptureSessionException("CAPTURE_IN_PROGRESS", "A shared capture is in progress")
        }
        val started =
            try {
                captureImage(exposureBracket)
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
            else if (exposureBracket.isNotEmpty()) 12_000L
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
        if (pendingDirectCapture != null) {
            throw CaptureSessionException("CAPTURE_IN_PROGRESS", "A native V2 capture is in progress")
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

    internal fun stillCaptureMetadataReceipt(): Map<String, Any?> =
        stillCaptureMetadata?.receipt() ?: mapOf("declared" to BuildConfig.NATIVE_FRAME_ATTRIBUTION,
            "effective" to false, "scope" to "still ledger unavailable: disabled, unfunded or closed")
    internal fun resetStillCaptureMetadata() { stillCaptureMetadata?.resetMeasurements() }

    /** Current native reader configuration and actual received-JPEG dimensions. */
    internal fun captureConfigurationReceipt(): Map<String, Any?> = mapOf(
        "cameraId" to cameraDevice?.id,
        "sessionCallbackGeneration" to sessionCallbackGeneration,
        "requestedWidth" to config.resolution.width,
        "requestedHeight" to config.resolution.height,
        "effectiveWidth" to capabilityReport?.effectiveWidth,
        "effectiveHeight" to capabilityReport?.effectiveHeight,
        "resolutionSelectionReason" to capabilityReport?.resolutionSelectionReason,
        "readerWidth" to previewImageReader?.width,
        "readerHeight" to previewImageReader?.height,
        "readerFormat" to previewImageReader?.imageFormat,
        "jpegQuality" to config.jpegQuality,
        "videoSnapshotJpegDeclared" to BuildConfig.VIDEO_SNAPSHOT_JPEG,
        "videoSnapshotJpegEffective" to (isInitialized && videoSnapshotJpegEffective),
        "stillTemplateScope" to if (BuildConfig.VIDEO_SNAPSHOT_JPEG) "ordinary primary camera0 JPEG; no physical-ID or reprocess overrides" else "baseline STILL_CAPTURE",
        "actualStillRequestCaptureIntent" to manualCaptureRequestBuilder?.get(CaptureRequest.CONTROL_CAPTURE_INTENT),
        "matchStillPreviewFpsDeclared" to BuildConfig.MATCH_STILL_PREVIEW_FPS,
        "matchStillPreviewFpsEffective" to (isInitialized && matchStillPreviewFpsEffective),
        "actualRepeatingRequestFpsLower" to previewCaptureRequestBuilder?.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)?.lower,
        "actualRepeatingRequestFpsUpper" to previewCaptureRequestBuilder?.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)?.upper,
        "actualStillRequestFpsLower" to manualCaptureRequestBuilder?.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)?.lower,
        "actualStillRequestFpsUpper" to manualCaptureRequestBuilder?.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)?.upper,
        "configuredFpsLower" to session?.cameraConfig?.fpsRange?.lower,
        "configuredFpsUpper" to session?.cameraConfig?.fpsRange?.upper,
        "receivedJpegDimensions" to jpegDimensionLedger.receipt(),
    )

    /** Scalar scratch ownership evidence used by the capture performance receipt. */
    internal fun captureScratchReceipt(): Map<String, Long> {
        val receipt = processedFrameBuffers.receipt()
        return mapOf(
            "captureYuvScratchMaximumSlots" to receipt.maximumSlots.toLong(),
            "captureYuvScratchMaximumBytes" to receipt.maximumBytes,
            "captureYuvScratchRetainedSlots" to receipt.retainedSlots.toLong(),
            "captureYuvScratchOwnedSlots" to receipt.ownedSlots.toLong(),
            "captureYuvScratchInUseSlots" to receipt.inUseSlots.toLong(),
            "captureYuvScratchRetainedCapacityBytes" to receipt.retainedCapacityBytes,
            "captureYuvScratchInUseCapacityBytes" to receipt.inUseCapacityBytes,
            "captureYuvScratchOwnedCapacityBytes" to receipt.ownedCapacityBytes,
            "captureYuvScratchPeakOwnedCapacityBytes" to receipt.peakOwnedCapacityBytes,
            "captureYuvScratchPeakInUseSlots" to receipt.peakInUseSlots.toLong(),
            "captureYuvScratchGrowthEvents" to receipt.growthEvents,
            "captureYuvScratchRefusals" to receipt.refusalCount,
            "captureYuvScratchClosed" to if (receipt.closed) 1L else 0L,
            "captureJpegScratchActiveOwners" to jpegScratchActiveOwners.get(),
            "captureJpegScratchNv21ResidentCapacityBytes" to jpegScratchNv21ResidentBytes.get(),
            "captureJpegScratchOutputResidentCapacityBytes" to jpegScratchOutputResidentBytes.get(),
            "captureJpegScratchResidentCapacityBytes" to
                jpegScratchNv21ResidentBytes.get() + jpegScratchOutputResidentBytes.get(),
            "captureJpegScratchPeakNv21CapacityBytes" to jpegScratchPeakNv21Bytes.get(),
            "captureJpegScratchPeakOutputCapacityBytes" to jpegScratchPeakOutputBytes.get(),
            "captureJpegScratchPeakCapacityBytes" to
                jpegScratchPeakNv21Bytes.get() + jpegScratchPeakOutputBytes.get(),
        )
    }

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
        v2ExposureHook.clear()
        if (processShutdownGeneration == null) {
            processShutdownGeneration = processRestartGate.markShutdownStarted()
        }
        cleanupRequested = true
        val callbackShutdownStarted = sessionCallbackFence.beginShutdown(sessionCallbackGeneration)
        if (BuildConfig.NATIVE_FRAME_ATTRIBUTION) closeDiagnostic("cleanup-begin:gateStarted=$callbackShutdownStarted")
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
        pendingDirectCapture?.let { pending ->
            pendingDirectCaptureOwner.release(pending)
            directRawJpegCaptureCorrelator.clear()
        }
        val closingCaptureSession = captureSession
        captureSessionClosed = closingCaptureSession == null
        closingCaptureSession?.close()
        captureSession = null

        val closingCameraDevice = cameraDevice
        cameraDeviceClosed = cameraDeviceCloseBarrier.beginClose(sessionCallbackGeneration, closingCameraDevice)
        closingCameraDevice?.close()
        cameraDevice = null
        signalCameraCloseBarrierIfComplete()

        activeCameraCharacteristics = null
        previewCaptureRequestBuilder = null
        stillCaptureMetadata?.close()
        stillCaptureMetadata = null
        nativeFrameAttribution?.invalidateCameraEpoch(attributionCameraEpoch)
        matchStillPreviewFpsEffective = false
        videoSnapshotJpegEffective = false
        manualCaptureRequestBuilder = null

        if (finalizationWorkersDelegate.isInitialized()) finalizationWorkers.close()
        synchronized(pendingDirectStoreDispatches) {
            pendingDirectStoreDispatches.toList().forEach { it.cancel("capture-disposed") }
            pendingDirectStoreDispatches.clear()
        }
        directStoreExecutor.shutdownNow()
        directComponentExecutor.shutdownNow()
        processedFrameCorrelator.clear()
        processedFrameBuffers.close()

        scheduleBackgroundThreadShutdownWhenClosed()
        // Vendor fallback: close callbacks are expected, but never retain a
        // handler thread indefinitely if a HAL omits one.
        backgroundHandler?.postDelayed(::quitBackgroundThreadFromCameraCallback, 3_000L)

        observedCaptureState = null
        latestPreviewExposureState = null
        sceneViewResumeReconfigurationDisabled = false
        isInitialized = false
        Log.i("SharedCameraManager", "Cleanup completed")
    }

    /**
     * Requires closure of the matching CameraDevice, then drains both owned
     * callback threads and Images before the host may destroy the ARCore Session.
     * A separate session onClosed is observational, not hardware-close authority.
     */
    fun finishCameraShutdown(timeoutMs: Long) {
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        CameraShutdownDrain.finish(
            quiesceCallbacks = {
                check(cameraCloseLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    closeDiagnostic("close-callback-deadline")
                    "Camera close callbacks did not drain; AR session retained"
                }
                stopBackgroundThreadsAfterDrain(deadlineMs)
            },
            releaseCorrelatorOwners = ::clearPendingRawJpegComponents,
            awaitImagesClosed = {
                while (resourceCounters.outstandingImages() != 0L) {
                    check(SystemClock.elapsedRealtime() < deadlineMs) { "Camera Images still owned; AR session retained" }
                    Thread.sleep(5)
                }
            },
            closeReaders = ::closeImageReaders,
            completeShutdown = {
                cameraDeviceCloseBarrier.finish(sessionCallbackGeneration)
                sessionCallbackFence.finishShutdown(sessionCallbackGeneration)
                processShutdownGeneration?.let { generation ->
                    processRestartGate.markShutdownCompleted(generation, SystemClock.elapsedRealtime())
                }
                processShutdownGeneration = null
            },
        )
    }

    /** Cold callback/shutdown observations; no new admission or close authority. */
    private fun closeDiagnostic(stage: String, callbackGeneration: Long = sessionCallbackGeneration) {
        if (!BuildConfig.NATIVE_FRAME_ATTRIBUTION) return
        val cameraThread = backgroundThread
        val imageThread = captureCallbackThread
        Log.i("SharedCameraManager", "[CAMERA-CLOSE-DIAGNOSTIC] stage=$stage " +
            "managerIdentity=${System.identityHashCode(this)} arSessionIdentity=${System.identityHashCode(session)} " +
            "callbackGeneration=$callbackGeneration currentOpenGeneration=$sessionCallbackGeneration " +
            "shutdownGeneration=$processShutdownGeneration cameraEpoch=$attributionCameraEpoch " +
            "cleanupRequested=$cleanupRequested captureSessionClosed=$captureSessionClosed cameraDeviceClosed=$cameraDeviceClosed " +
            "deviceObservedGeneration=$diagnosticDeviceCloseGeneration deviceWrappedOutcome=$diagnosticDeviceCloseOutcome " +
            "sessionObservedGeneration=$diagnosticSessionCloseGeneration sessionGateOutcome=$diagnosticSessionCloseOutcome " +
            "sessionWrappedGeneration=$diagnosticSessionWrappedCloseGeneration sessionWrappedOutcome=$diagnosticSessionWrappedCloseOutcome " +
            "cameraHandlerPresent=${backgroundHandler != null} cameraThreadPresent=${cameraThread != null} " +
            "cameraThreadAlive=${cameraThread?.isAlive} cameraThreadState=${cameraThread?.state} " +
            "imageHandlerPresent=${captureCallbackHandler != null} imageThreadPresent=${imageThread != null} " +
            "imageThreadAlive=${imageThread?.isAlive} imageThreadState=${imageThread?.state} " +
            "callbackThread=${Thread.currentThread().name} scope=nonAtomicColdObservation")
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
                    override fun setISO(
                        isoValue: Int,
                        preservedExposureTimeMicros: Long?,
                    ): Boolean =
                        controller.setISO(isoValue, preservedExposureTimeMicros)

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
                        latestPreviewExposureState ?: observedCaptureState?.exposure
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
            if (pending.exposureBracket.isNotEmpty()) {
                val bracketIndex = capture.result.bracketIndex
                val spec = pending.exposureBracket.getOrNull(bracketIndex)
                    ?: throw CaptureSessionException(
                        "CAPTURE_FAILED",
                        "Received an exposure-bracket image with an unknown index",
                    )
                val member = ExposureBracketMember(
                    exposureEv = spec.exposureEv,
                    imageBytes = capture.image.bytes,
                    width = capture.image.width,
                    height = capture.image.height,
                    sensorTimestampNs = capture.result.sensorTimestampNs,
                    exposureTimeNs = capture.result.exposureTimeNs,
                    rollingShutterSkewNs = capture.result.rollingShutterSkewNs,
                    observedTimestampNs = capture.result.observedTimestampNs,
                    intrinsics = capabilityQuerier.getCameraIntrinsicsForSize(
                        captureSize = Size(capture.image.width, capture.image.height),
                        cropRegion = capture.result.cropRegion,
                    ),
                )
                pending.bracketedCaptures[bracketIndex] = member
                if (pending.bracketedCaptures.size < pending.exposureBracket.size) {
                    return
                }
                val members = pending.exposureBracket.indices.map { index ->
                    pending.bracketedCaptures[index]
                        ?: throw CaptureSessionException(
                            "CAPTURE_FAILED",
                            "Exposure bracket completed without all expected images",
                        )
                }
                val center = members.firstOrNull { it.exposureEv == 0 }
                    ?: throw CaptureSessionException(
                        "CAPTURE_FAILED",
                        "Exposure bracket completed without a 0 EV image",
                    )
                pending.result = SharedCameraCaptureResult(
                    reservationToken = pending.reservationToken,
                    imageId = generateImageId(),
                    imageBytes = center.imageBytes,
                    format = config.format,
                    width = center.width,
                    height = center.height,
                    imageSizeBytes = members.sumOf { it.imageBytes.size },
                    captureTimestampMs = System.currentTimeMillis(),
                    sensorTimestampNs = center.sensorTimestampNs,
                    exposureTimeNs = center.exposureTimeNs,
                    rollingShutterSkewNs = center.rollingShutterSkewNs,
                    observedTimestampNs = center.observedTimestampNs,
                    intrinsics = center.intrinsics,
                    primaryAssetName = "jpeg",
                    preEncodeQuality = pending.preEncodeQuality,
                    requiresPose = pending.requiresPose,
                    pipelineTimingMs = hardwareJpegAcquisitionTimings(
                        requestStartedAtNs = pending.requestStartedAtNs,
                        correlatedFrameAtNs = System.nanoTime(),
                    ),
                    exposureBracketMembers = members,
                )
                pending.latch.countDown()
                return
            }
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
                    requiresPose = pending.requiresPose,
                    pipelineTimingMs = hardwareJpegAcquisitionTimings(
                        requestStartedAtNs = pending.requestStartedAtNs,
                        correlatedFrameAtNs = System.nanoTime(),
                    ),
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
        sessionCallbackGeneration = sessionCallbackFence.open()
        cameraDeviceCloseBarrier.open(sessionCallbackGeneration)
        if (BuildConfig.NATIVE_FRAME_ATTRIBUTION) {
            diagnosticDeviceCloseGeneration = -1L
            diagnosticSessionCloseGeneration = -1L
            diagnosticDeviceCloseOutcome = 0
            diagnosticSessionCloseOutcome = 0
            diagnosticSessionWrappedCloseGeneration = -1L
            diagnosticSessionWrappedCloseOutcome = 0
            closeDiagnostic("open-generation")
        }
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
        if (!cleanupRequested || !cameraDeviceCloseBarrier.isClosed(sessionCallbackGeneration)) return
        // Samsung may enqueue terminal capture callbacks from its executor
        // immediately after the framework device onClosed callback. Keep the
        // handler alive for a bounded drain window before stopping its looper.
        backgroundHandler?.postDelayed(
            ::quitBackgroundThreadFromCameraCallback,
            1_500L,
        )
    }

    private fun signalCameraCloseBarrierIfComplete() {
        if (cleanupRequested && cameraDeviceCloseBarrier.isClosed(sessionCallbackGeneration)) {
            cameraCloseLatch.countDown()
        }
    }

    private fun quitBackgroundThreadFromCameraCallback() {
        callbackThreads.requestQuit()
    }

    private fun stopBackgroundThreadsAfterDrain(deadlineMs: Long) {
        callbackThreads.drainUntil(deadlineMs)
        backgroundHandler = null
        captureCallbackHandler = null
    }

}
