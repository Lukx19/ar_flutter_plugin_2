package com.uhg0.ar_flutter_plugin_2.capture

internal data class RuntimeFocusCapabilities(
    val maxFocusDistanceDiopters: Float?,
    val supportedFocusModes: Set<String>,
    val sensorArea: SensorArea?,
    val tapFocusSupported: Boolean,
)

internal interface RuntimeFocusControlTarget {
    fun setFocusDistanceDiopters(focusDistanceDiopters: Float): Boolean

    fun setAutofocusEnabled(enabled: Boolean): Boolean

    fun setFocusMode(mode: String): Boolean

    fun getCurrentFocusDistanceDiopters(): Float?

    fun getCurrentFocusMode(): String

    fun isAutofocusEnabled(): Boolean

    fun setFocusRegion(region: MeteringRegion): Boolean

    fun getCurrentFocusRegion(): MeteringRegion?

    fun getObservedFocusState(): RuntimeObservedFocusState?
}

internal class RuntimeFocusControlBridge(
    private val capabilities: RuntimeFocusCapabilities,
    private val target: RuntimeFocusControlTarget,
) {
    fun setFocusDistance(normalizedDistance: Double): Double? {
        val maxFocusDistance =
            capabilities.maxFocusDistanceDiopters
                ?: throw CaptureSessionException(
                    code = "CONTROL_UNSUPPORTED",
                    message = "Manual focus distance is not supported on this camera",
                )
        val clampedDistance = normalizedDistance.coerceIn(0.0, 1.0)
        val diopters = (maxFocusDistance * (1.0 - clampedDistance)).toFloat()
        if (!target.setFocusDistanceDiopters(diopters)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message = "Failed to stage focus distance for the next still capture",
            )
        }
        return getCurrentFocusDistance()
    }

    fun setAutofocusEnabled(enabled: Boolean): Boolean {
        if (!target.setAutofocusEnabled(enabled)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message = "Failed to update autofocus mode",
            )
        }
        return target.isAutofocusEnabled()
    }

    fun getCurrentFocusDistance(): Double? {
        val maxFocusDistance = capabilities.maxFocusDistanceDiopters ?: return null
        val diopters =
            target.getObservedFocusState()?.currentFocusDistanceDiopters
                ?: target.getCurrentFocusDistanceDiopters()
                ?: return null
        if (maxFocusDistance <= 0f) {
            return null
        }
        return (1.0 - (diopters / maxFocusDistance)).coerceIn(0.0, 1.0).toDouble()
    }

    fun getSupportedFocusModes(): List<String> = capabilities.supportedFocusModes.toList().sorted()

    fun focusAtPoint(
        x: Double,
        y: Double,
    ): Boolean {
        val sensorArea =
            capabilities.sensorArea
                ?: throw CaptureSessionException(
                    code = "CONTROL_UNSUPPORTED",
                    message = "Point focus is not supported on this camera",
                )
        if (!capabilities.tapFocusSupported) {
            throw CaptureSessionException(
                code = "CONTROL_UNSUPPORTED",
                message = "Point focus is not supported on this camera",
            )
        }
        val region = MeteringRegionMapper.normalizedPointToMeteringRegion(x, y, sensorArea)
        if (!target.setFocusRegion(region)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message = "Failed to stage focus region for the next still capture",
            )
        }
        return true
    }

    fun setFocusMode(mode: String): Boolean {
        if (!capabilities.supportedFocusModes.contains(mode)) {
            throw CaptureSessionException(
                code = "CONTROL_UNSUPPORTED",
                message = "Focus mode $mode is not supported on this camera",
            )
        }
        if (!target.setFocusMode(mode)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message = "Failed to stage focus mode $mode for the next still capture",
            )
        }
        return true
    }

    fun getCurrentFocusState(): Map<String, Any?> {
        val observed = target.getObservedFocusState()
        val focusRegion = observed?.focusRegion ?: target.getCurrentFocusRegion()
        val normalizedFocusRegion =
            if (focusRegion != null && capabilities.sensorArea != null) {
                focusRegion.toNormalizedMap(capabilities.sensorArea)
            } else {
                null
            }
        return mapOf(
            "currentFocusDistance" to getCurrentFocusDistance(),
            "isAutofocusEnabled" to
                (observed?.isAutofocusEnabled ?: target.isAutofocusEnabled()),
            "currentFocusMode" to (observed?.currentFocusMode ?: target.getCurrentFocusMode()),
            "isFocusLocked" to (observed?.isFocusLocked ?: false),
            "isFocusPeakingEnabled" to false,
            "focusStatus" to
                (observed?.focusStatus
                    ?: if (normalizedFocusRegion != null) "scanning" else "inactive"),
            "focusRegion" to normalizedFocusRegion,
        )
    }
}
