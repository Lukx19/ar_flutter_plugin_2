package com.uhg0.ar_flutter_plugin_2.capture

import com.google.ar.core.Session

internal data class SharedCameraSessionFeaturePlan(
    val targetSessionFeatures: Set<Session.Feature>,
    val requiresSceneRebuild: Boolean,
    val failureCode: String? = null,
    val failureMessage: String? = null,
)

internal object SharedCameraSessionFeaturePlanner {
    private val sharedCameraFeature = setOf(Session.Feature.SHARED_CAMERA)

    fun plan(
        enableHighResCapture: Boolean,
        currentSessionFeatures: Set<Session.Feature>,
        hasLiveSession: Boolean,
    ): SharedCameraSessionFeaturePlan {
        if (!enableHighResCapture) {
            return SharedCameraSessionFeaturePlan(
                targetSessionFeatures = currentSessionFeatures,
                requiresSceneRebuild = false,
            )
        }

        if (currentSessionFeatures.contains(Session.Feature.SHARED_CAMERA)) {
            return SharedCameraSessionFeaturePlan(
                targetSessionFeatures = currentSessionFeatures,
                requiresSceneRebuild = false,
            )
        }

        if (hasLiveSession) {
            return SharedCameraSessionFeaturePlan(
                targetSessionFeatures = currentSessionFeatures,
                requiresSceneRebuild = false,
                failureCode = "SHARED_CAMERA_REQUIRES_RESTART",
                failureMessage =
                    "Shared camera must be requested before the AR session is created",
            )
        }

        return SharedCameraSessionFeaturePlan(
            targetSessionFeatures = sharedCameraFeature,
            requiresSceneRebuild = true,
        )
    }
}
