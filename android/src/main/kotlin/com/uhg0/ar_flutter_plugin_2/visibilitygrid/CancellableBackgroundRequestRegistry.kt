package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns background-isolate MethodChannel results until operation completion or
 * an explicit cancel acknowledgement. Each original result is completed once;
 * operation replies arriving after cancellation are deliberately suppressed.
 */
internal class CancellableBackgroundRequestRegistry {
    private val pending = ConcurrentHashMap<String, OnceResult>()

    val pendingCount: Int
        get() = pending.size

    fun register(requestId: String, result: MethodChannel.Result): MethodChannel.Result {
        require(requestId.isNotBlank()) { "backgroundRequestId must be non-empty" }
        val once = OnceResult(requestId, result, ::remove)
        check(pending.putIfAbsent(requestId, once) == null) {
            "backgroundRequestId is already pending"
        }
        return once
    }

    /** Returns only after the original result has received its cancellation. */
    fun cancel(requestId: String): Boolean {
        val request = pending[requestId] ?: return false
        return request.cancel()
    }

    fun cancelAll() {
        pending.values.toList().forEach(OnceResult::cancel)
    }

    private fun remove(requestId: String, result: OnceResult) {
        pending.remove(requestId, result)
    }

    private class OnceResult(
        private val requestId: String,
        private val delegate: MethodChannel.Result,
        private val completed: (String, OnceResult) -> Unit,
    ) : MethodChannel.Result {
        private val terminal = AtomicBoolean(false)

        fun cancel(): Boolean = complete {
            delegate.error(
                "VG_CANCELLED",
                "Background visibility-grid request was cancelled",
                mapOf("backgroundRequestId" to requestId),
            )
        }

        override fun success(result: Any?) {
            complete { delegate.success(result) }
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            complete { delegate.error(errorCode, errorMessage, errorDetails) }
        }

        override fun notImplemented() {
            complete(delegate::notImplemented)
        }

        private inline fun complete(deliver: () -> Unit): Boolean {
            if (!terminal.compareAndSet(false, true)) return false
            try {
                deliver()
            } finally {
                completed(requestId, this)
            }
            return true
        }
    }
}
