package com.uhg0.ar_flutter_plugin_2.capture

internal data class RuntimeWhiteBalanceCapabilities(
    val supportedModes: Set<String>,
    val supportedColorTemperatureRange: IntRange?,
    val sensorArea: SensorArea?,
    val pointWhiteBalanceSupported: Boolean,
)

internal interface RuntimeWhiteBalanceControlTarget {
    fun setWhiteBalanceMode(mode: String): Boolean

    fun setWhiteBalanceLocked(locked: Boolean): Boolean

    fun getCurrentWhiteBalanceMode(): String

    fun setColorTemperature(colorTemperatureK: Int): Boolean

    fun getCurrentColorTemperature(): Int?

    fun setWhiteBalanceRegion(region: MeteringRegion): Boolean

    fun isWhiteBalanceLocked(): Boolean

    fun isAutoWhiteBalanceEnabled(): Boolean

    fun getObservedWhiteBalanceState(): RuntimeObservedWhiteBalanceState?
}

internal class RuntimeWhiteBalanceControlBridge(
    private val capabilities: RuntimeWhiteBalanceCapabilities,
    private val target: RuntimeWhiteBalanceControlTarget,
) {
    fun setWhiteBalanceMode(mode: String): Boolean {
        if (!capabilities.supportedModes.contains(mode)) {
            throw CaptureSessionException(
                code = "CONTROL_UNSUPPORTED",
                message = "White balance mode $mode is not supported on this camera",
            )
        }
        if (!target.setWhiteBalanceMode(mode)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message = "Failed to stage white balance mode $mode for the next still capture",
            )
        }
        return true
    }

    fun lockWhiteBalance(): Boolean {
        ensureWhiteBalanceLockSupported()
        if (!target.setWhiteBalanceLocked(true)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message = "Failed to lock white balance",
            )
        }
        return target.isWhiteBalanceLocked()
    }

    fun unlockWhiteBalance(): Boolean {
        ensureWhiteBalanceLockSupported()
        if (!target.setWhiteBalanceLocked(false)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message = "Failed to unlock white balance",
            )
        }
        return !target.isWhiteBalanceLocked()
    }

    fun getCurrentWhiteBalanceState(): Map<String, Any?> =
        target.getObservedWhiteBalanceState()?.let { observed ->
            mapOf(
                "currentMode" to (observed.currentMode ?: target.getCurrentWhiteBalanceMode()),
                "currentColorTemperature" to observed.currentColorTemperature,
                "isWhiteBalanceLocked" to observed.isWhiteBalanceLocked,
                "isAutoWhiteBalanceEnabled" to observed.isAutoWhiteBalanceEnabled,
                "status" to (observed.status ?: "inactive"),
            )
        }
            ?: mapOf(
                "currentMode" to target.getCurrentWhiteBalanceMode(),
                "currentColorTemperature" to target.getCurrentColorTemperature(),
                "isWhiteBalanceLocked" to target.isWhiteBalanceLocked(),
                "isAutoWhiteBalanceEnabled" to target.isAutoWhiteBalanceEnabled(),
                "status" to "inactive",
            )

    fun getSupportedWhiteBalanceModes(): List<String> = capabilities.supportedModes.toList().sorted()

    fun getSupportedColorTemperatureRange(): Map<String, Int> {
        val range =
            capabilities.supportedColorTemperatureRange
                ?: throw CaptureSessionException(
                    code = "CONTROL_UNSUPPORTED",
                    message = "Manual color temperature is not supported on this camera",
                )
        return mapOf(
            "min" to range.first,
            "max" to range.last,
        )
    }

    fun setColorTemperature(colorTemperatureK: Int): Int {
        val range =
            capabilities.supportedColorTemperatureRange
                ?: throw CaptureSessionException(
                    code = "CONTROL_UNSUPPORTED",
                    message = "Manual color temperature is not supported on this camera",
                )
        val clampedTemperature = colorTemperatureK.coerceIn(range.first, range.last)
        if (!target.setColorTemperature(clampedTemperature)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message =
                    "Failed to stage manual color temperature ${clampedTemperature}K for the next still capture",
            )
        }
        return target.getCurrentColorTemperature() ?: clampedTemperature
    }

    fun setWhiteBalanceFromPoint(
        x: Double,
        y: Double,
    ): Boolean {
        val sensorArea =
            capabilities.sensorArea
                ?: throw CaptureSessionException(
                    code = "CONTROL_UNSUPPORTED",
                    message = "Point white balance is not supported on this camera",
                )
        if (!capabilities.pointWhiteBalanceSupported) {
            throw CaptureSessionException(
                code = "CONTROL_UNSUPPORTED",
                message = "Point white balance is not supported on this camera",
            )
        }
        if (!target.isAutoWhiteBalanceEnabled()) {
            throw CaptureSessionException(
                code = "CONTROL_UNSUPPORTED",
                message = "Point white balance is not supported in manual color temperature mode",
            )
        }
        val region = MeteringRegionMapper.normalizedPointToMeteringRegion(x, y, sensorArea)
        if (!target.setWhiteBalanceRegion(region)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message = "Failed to stage white balance region for the next still capture",
            )
        }
        return true
    }

    private fun ensureWhiteBalanceLockSupported() {
        if (!target.isAutoWhiteBalanceEnabled()) {
            throw CaptureSessionException(
                code = "CONTROL_UNSUPPORTED",
                message = "White balance lock is not supported in manual color temperature mode",
            )
        }
    }
}
