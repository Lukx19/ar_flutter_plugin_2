package com.uhg0.ar_flutter_plugin_2.capture

internal data class RuntimeFlashCapabilities(
    val flashAvailable: Boolean,
    val supportedModes: Set<String>,
)

internal interface RuntimeFlashControlTarget {
    fun setFlashMode(mode: String): Boolean

    fun getCurrentFlashMode(): String

    fun getObservedFlashState(): RuntimeObservedFlashState?
}

internal class RuntimeFlashControlBridge(
    private val capabilities: RuntimeFlashCapabilities,
    private val target: RuntimeFlashControlTarget,
) {
    fun setFlashMode(mode: String): Boolean {
        if (mode != "off" && !capabilities.flashAvailable) {
            return target.setFlashMode("off")
        }
        if (!capabilities.supportedModes.contains(mode)) {
            throw CaptureSessionException(
                code = "CONTROL_UNSUPPORTED",
                message = "Flash mode $mode is not supported on this camera",
            )
        }
        if (!target.setFlashMode(mode)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message = "Failed to stage flash mode $mode for the next still capture",
            )
        }
        return true
    }

    fun isFlashAvailable(): Boolean = capabilities.flashAvailable

    fun setTorchEnabled(enabled: Boolean): Boolean =
        setFlashMode(if (enabled) "torch" else "off")

    fun getCurrentFlashState(): Map<String, Any?> =
        target.getObservedFlashState()?.let { observed ->
            mapOf(
                "currentFlashMode" to (observed.currentFlashMode ?: target.getCurrentFlashMode()),
                "isTorchEnabled" to observed.isTorchEnabled,
                "isFlashReady" to observed.isFlashReady,
                "flashStatus" to
                    (observed.flashStatus
                        ?: if (capabilities.flashAvailable) "ready" else "unavailable"),
            )
        }
            ?: mapOf(
                "currentFlashMode" to target.getCurrentFlashMode(),
                "isTorchEnabled" to (target.getCurrentFlashMode() == "torch"),
                "isFlashReady" to capabilities.flashAvailable,
                "flashStatus" to if (capabilities.flashAvailable) "ready" else "unavailable",
            )
}
