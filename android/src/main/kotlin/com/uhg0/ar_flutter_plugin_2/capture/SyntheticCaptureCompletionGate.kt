package com.uhg0.ar_flutter_plugin_2.capture

import java.io.ByteArrayInputStream

/** Holds one debug synthetic Camera2 completion until the test releases it. */
internal class SyntheticCaptureCompletionGate {
    private data class Pending(
        val qualifier: CaptureAttemptQualifierV2,
        val required: Set<CaptureComponentKind>,
        val callback: SharedCameraExposureCallbackV2,
    )

    private val lock = Any()
    private var pending: Pending? = null

    fun hold(
        qualifier: CaptureAttemptQualifierV2,
        required: Set<CaptureComponentKind>,
        callback: SharedCameraExposureCallbackV2,
    ): Boolean = synchronized(lock) {
        if (pending != null) return@synchronized false
        pending = Pending(qualifier, required.toSet(), callback)
        true
    }

    fun complete(): Boolean {
        val owned = synchronized(lock) {
            val current = pending ?: return false
            pending = null
            current
        }
        owned.callback.onComponents(
            SharedCameraComponentSetV2(
                owned.qualifier,
                owned.required.sortedBy { it.ordinal }.map { kind ->
                    CaptureComponentStreamV2(
                        kind,
                        ByteArrayInputStream(byteArrayOf(kind.ordinal.toByte(), 7, 9)),
                    )
                },
                exposureTimestampNanoseconds = 1L,
            ),
        )
        return true
    }

    fun cancel(qualifier: CaptureAttemptQualifierV2) = synchronized(lock) {
        if (pending?.qualifier == qualifier) pending = null
    }

    fun clear() = synchronized(lock) { pending = null }
}
