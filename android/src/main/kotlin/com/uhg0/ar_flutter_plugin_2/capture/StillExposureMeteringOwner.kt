package com.uhg0.ar_flutter_plugin_2.capture

/**
 * One accepted immutable repeating request owns metering. Control mutation, replacement,
 * publication and still construction share this monitor; no callback from an earlier
 * request can restore metering after a control change or pause.
 */
internal class StillExposureMeteringOwner<R : Any> {
    private var activeRequest: R? = null
    private var metering: StillExposureMetering? = null

    @Synchronized
    fun <T> changeControls(change: () -> T): T {
        clear()
        return change()
    }

    /** The request becomes active only after its actual camera submission succeeds. */
    @Synchronized
    fun replaceRepeating(submit: () -> R?) {
        clear()
        activeRequest = submit()
    }

    @Synchronized
    fun publish(request: R, sample: StillExposureMetering?, onCurrent: () -> Unit = {}): Boolean {
        if (request !== activeRequest) return false
        metering = sample
        onCurrent()
        return true
    }

    /** Keep metering and the controls used to build/restore one still coherent. */
    @Synchronized
    fun <T> withMetering(build: (StillExposureMetering?) -> T): T = build(metering)

    @Synchronized
    fun invalidate() = clear()

    private fun clear() {
        activeRequest = null
        metering = null
    }
}
