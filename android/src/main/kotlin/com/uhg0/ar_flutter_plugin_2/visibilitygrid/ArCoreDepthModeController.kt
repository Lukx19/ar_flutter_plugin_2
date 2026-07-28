package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.google.ar.core.Config

class ArCoreDepthModeController(
    rawDepthSupported: Boolean,
    private val automaticDepthSupported: Boolean,
    private val terminalFailureThreshold: Int = 3,
) {
    init {
        require(terminalFailureThreshold > 0)
    }

    var activeMode: Config.DepthMode =
        when {
            rawDepthSupported -> Config.DepthMode.RAW_DEPTH_ONLY
            automaticDepthSupported -> Config.DepthMode.AUTOMATIC
            else -> Config.DepthMode.DISABLED
        }
        private set

    private var consecutiveAutomaticFailures = 0

    fun recordProbe(result: DepthAcquisitionResult): Config.DepthMode {
        when (result) {
            is DepthAcquisitionResult.Observation -> consecutiveAutomaticFailures = 0
            DepthAcquisitionResult.TransientUnavailable -> Unit
            is DepthAcquisitionResult.Failure -> handleTerminalFailure()
        }
        return activeMode
    }

    private fun handleTerminalFailure() {
        when (activeMode) {
            Config.DepthMode.RAW_DEPTH_ONLY -> {
                activeMode =
                    if (automaticDepthSupported) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }
                consecutiveAutomaticFailures = 0
            }
            Config.DepthMode.AUTOMATIC -> {
                consecutiveAutomaticFailures++
                if (consecutiveAutomaticFailures >= terminalFailureThreshold) {
                    activeMode = Config.DepthMode.DISABLED
                }
            }
            Config.DepthMode.DISABLED -> Unit
        }
    }
}
