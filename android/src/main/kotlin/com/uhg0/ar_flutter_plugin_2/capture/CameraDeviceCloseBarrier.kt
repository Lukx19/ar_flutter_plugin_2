package com.uhg0.ar_flutter_plugin_2.capture

/** Hardware closure only; callback-thread and Image drains remain independent. */
internal class CameraDeviceCloseBarrier<D : Any> {
    private var generation = 0L
    private var closing = false
    private var device: D? = null
    private var closed = false

    @Synchronized fun open(openGeneration: Long) {
        generation = openGeneration
        closing = false
        device = null
        closed = false
    }

    /** Bind the exact closing owner before calling CameraDevice.close(). */
    @Synchronized fun bindClose(openGeneration: Long, closingDevice: D): Boolean {
        if (openGeneration != generation) return false
        if (closing) return device === closingDevice
        closing = true
        device = closingDevice
        closed = false
        return true
    }

    /** Cleanup's null active handle must preserve an earlier error-close owner. */
    @Synchronized fun beginClose(openGeneration: Long, closingDevice: D?): Boolean {
        if (openGeneration != generation) return false
        if (closing) return (closingDevice == null || device === closingDevice) && closed
        if (closingDevice != null) {
            bindClose(openGeneration, closingDevice)
            return closed
        }
        closing = true
        closed = true
        return closed
    }

    @Synchronized fun onClosed(openGeneration: Long, closedDevice: D): Boolean {
        if (openGeneration != generation || !closing || closed || device !== closedDevice) return false
        closed = true
        return true
    }

    @Synchronized fun isClosed(openGeneration: Long): Boolean =
        openGeneration == generation && closing && closed

    /** Release the identity only after every independent shutdown phase succeeds. */
    @Synchronized fun finish(openGeneration: Long) {
        check(isClosed(openGeneration)) { "Camera device closure not established; owner retained" }
        device = null
        closing = false
    }
}
