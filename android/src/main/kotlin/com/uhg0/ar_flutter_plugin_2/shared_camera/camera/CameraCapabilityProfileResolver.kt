package com.uhg0.ar_flutter_plugin_2.shared_camera.camera

import kotlin.math.roundToInt

internal object CameraCapabilityProfileResolver {
    fun resolveCachedOrDetectedBoolean(
        cacheHit: Boolean,
        cachedValuePresent: Boolean,
        cachedValue: Boolean,
        detectedValue: Boolean,
    ): Boolean =
        if (cacheHit && cachedValuePresent) {
            cachedValue
        } else {
            detectedValue
        }

    /** A RAW stream alone never qualifies simultaneous app-route output. */
    fun pipelineCaptureFormats(
        cacheIdentityMatches: Boolean,
        rawPairProbeStatus: String?,
        hasValidatedPairedSizes: Boolean,
        hardwareHeicAvailable: Boolean = false,
    ): List<String> = listOf("jpeg") + (if (
        cacheIdentityMatches && rawPairProbeStatus == "supported" && hasValidatedPairedSizes
    ) listOf("raw+jpeg") else emptyList()) + if (hardwareHeicAvailable) listOf("heic") else emptyList()

    /**
     * The JPEG bracket route requests -2/0/+2 EV, rounded to sensor steps and
     * clamped to the advertised range. Match SharedCameraManager's distinct
     * member prerequisite; this metadata does not prove an HDR merged result.
     */
    fun exposureBracketingAvailable(
        minimumSteps: Int?,
        maximumSteps: Int?,
        stepEv: Double?,
    ): Boolean? {
        if (minimumSteps == null || maximumSteps == null || stepEv == null) return null
        if (minimumSteps > maximumSteps || !stepEv.isFinite() || stepEv <= 0) return false
        return listOf(-2, 0, 2).map {
            (it.toFloat() / stepEv.toFloat()).roundToInt().coerceIn(minimumSteps, maximumSteps)
        }.distinct().size == 3
    }

    fun rearConcurrentCameraIds(
        primaryId: String,
        concurrentCameraIdSets: Set<Set<String>>,
        lensFacingByCameraId: Map<String, Int?>,
        backFacingValue: Int,
    ): List<String> =
        concurrentCameraIdSets
            .asSequence()
            .filter { primaryId in it }
            .flatten()
            .filter { cameraId ->
                cameraId != primaryId &&
                    lensFacingByCameraId[cameraId] == backFacingValue
            }
            .distinct()
            .sorted()
            .toList()
}
