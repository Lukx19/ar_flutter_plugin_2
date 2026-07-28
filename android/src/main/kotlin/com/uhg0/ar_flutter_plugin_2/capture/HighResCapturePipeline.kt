package com.uhg0.ar_flutter_plugin_2.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal data class CaptureQualityPolicy(
    val blurFilterEnabled: Boolean,
    val blurThreshold: Double,
    val keepRejectedCaptures: Boolean,
)

internal interface HighResCaptureCache {
    fun releaseReservation(reservationToken: String): Boolean

    fun commitReservedImage(
        reservationToken: String,
        imageId: String,
        imageBytes: ByteArray,
        format: Int,
    ): Boolean

    fun commitReservedAssets(
        reservationToken: String,
        imageId: String,
        assets: Map<String, CachedImageAsset>,
    ): Boolean = commitReservedImage(
        reservationToken,
        imageId,
        assets.values.first().bytes,
        assets.values.first().format,
    )
}

internal interface HighResPoseResolver {
    fun resolvePose(captureTiming: PoseDataExtractor.CaptureTiming): PoseDataExtractor.AlignedPose?

    fun toPoseMap(alignedPose: PoseDataExtractor.AlignedPose): Map<String, Any?>
}

internal object CapturePipelineTimingContract {
    const val VERSION = "capture_pipeline_timing_v1"

    const val REQUEST_TO_PROCESSED_FRAME = "requestToProcessedFrame"
    const val PRE_ACCEPTANCE_POSE = "preAcceptancePose"
    const val FINALIZATION_QUEUE_WAIT = "finalizationQueueWait"
    const val JPEG_ENCODING = "jpegEncoding"
    const val QUALITY_AWAIT = "qualityAwait"
    const val POSE_AWAIT = "poseAwait"
    const val RAW_DNG_ENCODING = "rawDngEncoding"
    const val CACHE_COMMIT = "cacheCommit"
    const val NATIVE_FINALIZATION_TOTAL = "nativeFinalizationTotal"

    val REQUIRED_KEYS =
        setOf(
            REQUEST_TO_PROCESSED_FRAME,
            PRE_ACCEPTANCE_POSE,
            FINALIZATION_QUEUE_WAIT,
            JPEG_ENCODING,
            QUALITY_AWAIT,
            POSE_AWAIT,
            RAW_DNG_ENCODING,
            CACHE_COMMIT,
            NATIVE_FINALIZATION_TOTAL,
        )
}

internal class HighResCapturePipeline(
    private val cache: HighResCaptureCache?,
    private val poseResolver: HighResPoseResolver,
    private val qualityAnalyzer: (SharedCameraCaptureResult, CaptureQualityPolicy) -> Map<String, Any>,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val qualityAnalysisTimeoutMs: Long = 200L,
) {
    /// A process-wide session instance serializes all finalization ownership.
    /// Camera callbacks can be concurrent, but cache mutation and terminal
    /// emission for this capture session must remain exactly once and ordered.
    @Synchronized
    fun processCapture(
        sharedResult: SharedCameraCaptureResult,
        qualityPolicy: CaptureQualityPolicy?,
    ): Map<String, Any?> = runBlocking {
        val finalizationStartedAtNs = System.nanoTime()
        var committed = false
        var qualityAwaitMs = 0L
        var poseAwaitMs = 0L
        var rawDngEncodingMs = 0L
        var cacheCommitMs = 0L
        val poseDeferred =
            if (sharedResult.requiresPose) {
                async(workerDispatcher) {
                    sharedResult.preAlignedPose ?: poseResolver.resolvePose(
                        PoseDataExtractor.CaptureTiming(
                            sensorTimestampNs = sharedResult.sensorTimestampNs,
                            exposureTimeNs = sharedResult.exposureTimeNs,
                            rollingShutterSkewNs = sharedResult.rollingShutterSkewNs,
                            observedTimestampNs = sharedResult.observedTimestampNs,
                        ),
                    )
                }
            } else {
                null
            }
        val qualityDeferred =
            qualityPolicy?.let { policy ->
                if (sharedResult.format == android.graphics.ImageFormat.RAW_SENSOR &&
                    sharedResult.preEncodeQuality == null
                ) {
                    null
                } else {
                async(workerDispatcher) {
                    sharedResult.preEncodeQuality ?: withTimeout(qualityAnalysisTimeoutMs) {
                            qualityAnalyzer(sharedResult, policy)
                        }
                }
                }
            }

        try {
            val qualityStartNs = System.nanoTime()
            val quality =
                try {
                    qualityDeferred?.await()
                } catch (_: TimeoutCancellationException) {
                    throw CaptureSessionException(
                        code = "QUALITY_ANALYSIS_FAILED",
                        message = "Blur analysis exceeded ${qualityAnalysisTimeoutMs}ms deadline",
                    )
                }
            qualityAwaitMs = (System.nanoTime() - qualityStartNs) / 1_000_000L
            if (quality != null && qualityAwaitMs > qualityAnalysisTimeoutMs) {
                throw CaptureSessionException(
                    code = "QUALITY_ANALYSIS_FAILED",
                    message = "Blur analysis exceeded ${qualityAnalysisTimeoutMs}ms deadline",
                )
            }

            val result =
                if (quality != null && !quality["blurPassed"].asBoolean() && !qualityPolicy!!.keepRejectedCaptures) {
                    poseDeferred?.cancel()
                    sharedResult.closeRawImage?.invoke()
                    cache?.releaseReservation(sharedResult.reservationToken)
                    mapOf(
                        "status" to "rejectedBlur",
                        "attemptId" to "attempt_${sharedResult.captureTimestampMs}",
                        "imageId" to null,
                        "capture" to null,
                        "quality" to quality,
                    )
                } else {
                    val poseAwaitStartedAtNs = System.nanoTime()
                    val alignedPose =
                        if (sharedResult.requiresPose) {
                            (try {
                                poseDeferred?.await()
                            } catch (_: CancellationException) {
                                null
                            } ?: throw CaptureSessionException(
                                code = "POSE_SYNC_FAILED",
                                message = "No aligned pose was available for the captured image",
                            )).also {
                                poseAwaitMs =
                                    (System.nanoTime() - poseAwaitStartedAtNs) / 1_000_000L
                            }
                        } else {
                            null
                        }
                    val assets = linkedMapOf(
                        sharedResult.primaryAssetName to CachedImageAsset(
                            sharedResult.imageBytes,
                            sharedResult.format,
                        ),
                    )
                    sharedResult.exposureBracketMembers
                        .filter { member -> member.assetName != sharedResult.primaryAssetName }
                        .forEach { member ->
                            assets[member.assetName] = CachedImageAsset(
                                member.imageBytes,
                                android.graphics.ImageFormat.JPEG,
                            )
                        }
                    val rawDngEncodingStartedAtNs = System.nanoTime()
                    sharedResult.rawDngEncoder?.invoke()?.let { dngBytes ->
                        assets["dng"] = CachedImageAsset(
                            dngBytes,
                            android.graphics.ImageFormat.RAW_SENSOR,
                        )
                    }
                    rawDngEncodingMs =
                        (System.nanoTime() - rawDngEncodingStartedAtNs) / 1_000_000L
                    val captureResult =
                        alignedPose?.let { pose ->
                            buildSharedCaptureResult(sharedResult, pose, assets)
                        }
                    val cacheCommitStartedAtNs = System.nanoTime()
                    val cacheCommitted = cache?.commitReservedAssets(
                        reservationToken = sharedResult.reservationToken,
                        imageId = sharedResult.imageId,
                        assets = assets,
                    ) ?: false
                    if (!cacheCommitted) {
                        throw CaptureSessionException(
                            code = "CACHE_COMMIT_REJECTED",
                            message = "Capture cache rejected the reserved assets",
                        )
                    }
                    cacheCommitMs =
                        (System.nanoTime() - cacheCommitStartedAtNs) / 1_000_000L
                    committed = true

                    if (quality != null && !quality["blurPassed"].asBoolean()) {
                        mapOf(
                            "status" to "rejectedBlur",
                            "attemptId" to "attempt_${sharedResult.captureTimestampMs}",
                            "imageId" to sharedResult.imageId,
                            "capture" to captureResult,
                            "quality" to quality,
                        )
                    } else {
                        mapOf(
                            "status" to "staged",
                            "attemptId" to "attempt_${sharedResult.captureTimestampMs}",
                            "imageId" to sharedResult.imageId,
                            "capture" to captureResult,
                            "quality" to quality,
                        )
                    }
                }

            result +
                ("pipelineTimingVersion" to CapturePipelineTimingContract.VERSION) +
                ("pipelineTimingMs" to
                    (sharedResult.pipelineTimingMs +
                        mapOf(
                            CapturePipelineTimingContract.QUALITY_AWAIT to qualityAwaitMs,
                            CapturePipelineTimingContract.POSE_AWAIT to poseAwaitMs,
                            CapturePipelineTimingContract.RAW_DNG_ENCODING to rawDngEncodingMs,
                            CapturePipelineTimingContract.CACHE_COMMIT to cacheCommitMs,
                            CapturePipelineTimingContract.NATIVE_FINALIZATION_TOTAL to
                                ((System.nanoTime() - finalizationStartedAtNs) / 1_000_000L),
                        )))
        } catch (error: Throwable) {
            qualityDeferred?.cancel()
            poseDeferred?.cancel()
            if (!committed) {
                sharedResult.closeRawImage?.invoke()
                cache?.releaseReservation(sharedResult.reservationToken)
            }
            throw error
        }
    }

    private fun buildSharedCaptureResult(
        sharedResult: SharedCameraCaptureResult,
        alignedPose: PoseDataExtractor.AlignedPose,
        assets: Map<String, CachedImageAsset>,
    ): Map<String, Any?> =
        mapOf(
            "imageId" to sharedResult.imageId,
            "pose" to poseResolver.toPoseMap(alignedPose),
            "resolution" to mapOf(
                "width" to sharedResult.width,
                "height" to sharedResult.height,
            ),
            "format" to if (sharedResult.rawDngEncoder == null) sharedResult.primaryAssetName else "raw+jpeg",
            "formats" to if (sharedResult.rawDngEncoder == null) listOf(sharedResult.primaryAssetName) else listOf("dng", sharedResult.primaryAssetName),
            "captureTimestampMs" to sharedResult.captureTimestampMs,
            "imageSizeBytes" to assets.values.sumOf { it.bytes.size },
            "imageSizeBytesByFormat" to mapOf(
                "jpeg" to assets.values.sumOf { it.bytes.size },
            ),
            "isHighResolution" to true,
            "exposureStartTimestampNs" to sharedResult.sensorTimestampNs,
            "exposureTimeNs" to sharedResult.exposureTimeNs,
            "rollingShutterSkewNs" to sharedResult.rollingShutterSkewNs,
            "intrinsics" to sharedResult.intrinsics,
            "filePath" to null,
        ) + if (sharedResult.exposureBracketMembers.isNotEmpty()) {
            mapOf(
                "exposureBracket" to sharedResult.exposureBracketMembers.map { member ->
                    mapOf(
                        "exposureEv" to member.exposureEv,
                        "sensorTimestampNs" to member.sensorTimestampNs,
                        "exposureTimeNs" to member.exposureTimeNs,
                        "rollingShutterSkewNs" to member.rollingShutterSkewNs,
                        "imageSizeBytes" to member.imageBytes.size,
                    )
                },
            )
        } else {
            emptyMap()
        }

    private fun Any?.asBoolean(): Boolean = this as? Boolean ?: false
}
