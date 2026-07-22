package com.uhg0.ar_flutter_plugin_2.capture

internal data class RuntimeObservedExposureState(
    val currentISO: Int?,
    val currentExposureTimeMicros: Long?,
    val isAutoExposureEnabled: Boolean,
    val isExposureLocked: Boolean,
    val exposureCompensationSteps: Int?,
)

internal data class RuntimeObservedFocusState(
    val currentFocusDistanceDiopters: Float?,
    val currentFocusMode: String?,
    val isAutofocusEnabled: Boolean,
    val isFocusLocked: Boolean,
    val focusStatus: String?,
    val focusRegion: MeteringRegion?,
)

internal data class RuntimeObservedWhiteBalanceState(
    val currentMode: String?,
    val currentColorTemperature: Int?,
    val isWhiteBalanceLocked: Boolean,
    val isAutoWhiteBalanceEnabled: Boolean,
    val status: String?,
)

internal data class RuntimeObservedFlashState(
    val currentFlashMode: String?,
    val isTorchEnabled: Boolean,
    val isFlashReady: Boolean,
    val flashStatus: String?,
)

internal data class RuntimeObservedCaptureState(
    val exposure: RuntimeObservedExposureState,
    val focus: RuntimeObservedFocusState,
    val whiteBalance: RuntimeObservedWhiteBalanceState,
    val flash: RuntimeObservedFlashState,
)
