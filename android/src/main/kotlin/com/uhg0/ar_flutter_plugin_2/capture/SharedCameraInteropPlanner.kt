package com.uhg0.ar_flutter_plugin_2.capture

internal object SharedCameraInteropPlanner {
    data class AvailabilityFailure(
        val code: String,
        val message: String,
    )

    fun checkAvailability(
        hasSession: Boolean,
        hasSharedCamera: Boolean,
    ): AvailabilityFailure? =
        when {
            !hasSession ->
                AvailabilityFailure(
                    code = "CAPTURE_NOT_INITIALIZED",
                    message = "ARCore session is not ready for shared-camera capture",
                )
            !hasSharedCamera ->
                AvailabilityFailure(
                    code = "SHARED_CAMERA_UNSUPPORTED",
                    message = "ARCore shared camera is not available on the active session",
                )
            else -> null
        }

    fun <T> requireArCoreSurfaces(
        arCoreSurfaces: List<T>?,
    ): List<T> =
        arCoreSurfaces?.takeIf { it.isNotEmpty() }
            ?: throw CaptureSessionException(
                code = "CAPTURE_NOT_INITIALIZED",
                message = "ARCore shared camera surfaces are not ready",
            )

    fun resolveCameraId(
        sharedCameraId: String?,
    ): String =
        sharedCameraId
            ?: throw CaptureSessionException(
                code = "CAPTURE_NOT_INITIALIZED",
                message = "ARCore shared camera id is not ready",
            )

    fun <T> buildSessionSurfaces(
        arCoreSurfaces: List<T>,
        appSurfaces: List<T>,
    ): List<T> =
        buildList(arCoreSurfaces.size + appSurfaces.size) {
            addAll(arCoreSurfaces)
            addAll(appSurfaces)
        }

    fun <T> buildRepeatingRequestTargets(
        arCoreSurfaces: List<T>,
        appSurfaces: List<T>,
    ): List<T> = buildSessionSurfaces(arCoreSurfaces, appSurfaces)
}
