package com.uhg0.ar_flutter_plugin_2.shared_camera.camera

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
