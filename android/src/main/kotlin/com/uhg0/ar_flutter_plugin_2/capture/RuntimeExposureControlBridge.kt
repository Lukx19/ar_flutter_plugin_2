package com.uhg0.ar_flutter_plugin_2.capture

internal data class RuntimeExposureCapabilities(
    val supportedIsoRange: IntRange?,
    val supportedExposureTimeMicrosRange: LongRange?,
    val exposureCompensationStepsRange: IntRange?,
    val exposureCompensationStepEv: Double?,
    val exposureLockSupported: Boolean,
)

internal interface RuntimeExposureControlTarget {
    fun setISO(isoValue: Int): Boolean

    fun setExposureTime(exposureTimeMicros: Long): Boolean

    fun setAutoExposureEnabled(enabled: Boolean): Boolean

    fun getCurrentISO(): Int?

    fun getCurrentExposureTimeMicros(): Long?

    fun isAutoExposureEnabled(): Boolean

    fun setExposureCompensationSteps(steps: Int): Boolean

    fun getCurrentExposureCompensationSteps(): Int

    fun setExposureLocked(locked: Boolean): Boolean

    fun isExposureLocked(): Boolean

    fun getObservedExposureState(): RuntimeObservedExposureState?
}

internal class RuntimeExposureControlBridge(
    private val capabilities: RuntimeExposureCapabilities,
    private val target: RuntimeExposureControlTarget,
) {
    fun setISO(isoValue: Int): Int? {
        val isoRange =
            capabilities.supportedIsoRange
                ?: throw CaptureSessionException(
                    code = "CONTROL_UNSUPPORTED",
                    message = "Runtime ISO control is not supported on this camera",
                )
        val clampedIso = isoValue.coerceIn(isoRange.first, isoRange.last)
        if (!target.setISO(clampedIso)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message = "Failed to stage ISO $clampedIso for the next still capture",
            )
        }
        return target.getCurrentISO()
    }

    fun setExposureTime(exposureTimeMicros: Long): Long? {
        val exposureRange =
            capabilities.supportedExposureTimeMicrosRange
                ?: throw CaptureSessionException(
                    code = "CONTROL_UNSUPPORTED",
                    message = "Manual exposure time is not supported on this camera",
                )
        val clampedExposureMicros =
            exposureTimeMicros.coerceIn(exposureRange.first, exposureRange.last)
        if (!target.setExposureTime(clampedExposureMicros)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message =
                    "Failed to stage exposure time ${clampedExposureMicros}us for the next still capture",
            )
        }
        return target.getCurrentExposureTimeMicros()
    }

    fun setAutoExposureEnabled(enabled: Boolean): Boolean {
        if (!target.setAutoExposureEnabled(enabled)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message = "Failed to update auto exposure mode",
            )
        }
        return target.isAutoExposureEnabled()
    }

    fun getCurrentISO(): Int? =
        target.getObservedExposureState()?.currentISO ?: target.getCurrentISO()

    fun getCurrentExposureTimeMicros(): Long? =
        target.getObservedExposureState()?.currentExposureTimeMicros
            ?: target.getCurrentExposureTimeMicros()

    fun getSupportedISORange(): List<Int> {
        val isoRange =
            capabilities.supportedIsoRange
                ?: throw CaptureSessionException(
                    code = "CONTROL_UNSUPPORTED",
                    message = "Runtime ISO control is not supported on this camera",
                )
        return listOf(isoRange.first, isoRange.last)
    }

    fun getSupportedExposureRange(): Map<String, Long> {
        val exposureRange =
            capabilities.supportedExposureTimeMicrosRange
                ?: throw CaptureSessionException(
                    code = "CONTROL_UNSUPPORTED",
                    message = "Manual exposure time is not supported on this camera",
                )
        return mapOf(
            "min" to exposureRange.first,
            "max" to exposureRange.last,
        )
    }

    fun getExposureCompensationInfo(): Map<String, Double> {
        val compensationRange = requireExposureCompensationRange()
        val stepSize = requireExposureCompensationStep()
        return mapOf(
            "minCompensation" to compensationRange.first * stepSize,
            "maxCompensation" to compensationRange.last * stepSize,
            "currentCompensation" to target.getCurrentExposureCompensationSteps() * stepSize,
            "stepSize" to stepSize,
        )
    }

    fun setExposureCompensation(evStep: Double): Double {
        val compensationRange = requireExposureCompensationRange()
        val stepSize = requireExposureCompensationStep()
        val requestedSteps = kotlin.math.round(evStep / stepSize).toInt()
        val clampedSteps =
            requestedSteps.coerceIn(compensationRange.first, compensationRange.last)
        if (!target.setExposureCompensationSteps(clampedSteps)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message =
                    "Failed to stage exposure compensation ${clampedSteps * stepSize} EV for the next still capture",
            )
        }
        return target.getCurrentExposureCompensationSteps() * stepSize
    }

    fun lockExposure(): Boolean {
        ensureExposureLockSupported()
        if (!target.setExposureLocked(true)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message = "Failed to lock exposure for the next still capture",
            )
        }
        return target.isExposureLocked()
    }

    fun unlockExposure(): Boolean {
        ensureExposureLockSupported()
        if (!target.setExposureLocked(false)) {
            throw CaptureSessionException(
                code = "CONTROL_UPDATE_FAILED",
                message = "Failed to unlock exposure for the next still capture",
            )
        }
        return !target.isExposureLocked()
    }

    fun getCurrentExposureState(): Map<String, Any?> =
        target.getObservedExposureState()?.let { observed ->
            mapOf(
                "currentISO" to observed.currentISO,
                "currentExposureTime" to observed.currentExposureTimeMicros,
                "isAutoExposureEnabled" to observed.isAutoExposureEnabled,
                "isExposureLocked" to observed.isExposureLocked,
                "exposureCompensation" to currentExposureCompensationEv(observed),
                "exposureMode" to if (observed.isAutoExposureEnabled) "auto" else "manual",
            )
        }
            ?: mapOf(
                "currentISO" to target.getCurrentISO(),
                "currentExposureTime" to target.getCurrentExposureTimeMicros(),
                "isAutoExposureEnabled" to target.isAutoExposureEnabled(),
                "isExposureLocked" to
                    (target.isAutoExposureEnabled() && target.isExposureLocked()),
                "exposureCompensation" to currentExposureCompensationEv(),
                "exposureMode" to if (target.isAutoExposureEnabled()) "auto" else "manual",
            )

    private fun currentExposureCompensationEv(
        observedState: RuntimeObservedExposureState? = null,
    ): Double {
        val stepSize = capabilities.exposureCompensationStepEv ?: return 0.0
        val steps =
            observedState?.exposureCompensationSteps
                ?: target.getCurrentExposureCompensationSteps()
        return steps * stepSize
    }

    private fun requireExposureCompensationRange(): IntRange =
        capabilities.exposureCompensationStepsRange
            ?: throw CaptureSessionException(
                code = "CONTROL_UNSUPPORTED",
                message = "Exposure compensation is not supported on this camera",
            )

    private fun requireExposureCompensationStep(): Double =
        capabilities.exposureCompensationStepEv
            ?.takeIf { it > 0.0 }
            ?: throw CaptureSessionException(
                code = "CONTROL_UNSUPPORTED",
                message = "Exposure compensation is not supported on this camera",
            )

    private fun ensureExposureLockSupported() {
        if (!capabilities.exposureLockSupported) {
            throw CaptureSessionException(
                code = "CONTROL_UNSUPPORTED",
                message = "Exposure lock is not supported on this camera",
            )
        }
    }
}
