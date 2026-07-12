package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class SharedCameraStartupBarrier {
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var failureMessage: String? = null

    @Volatile
    private var configured = false

    fun markConfigured() {
        configured = true
        readyLatch.countDown()
    }

    fun fail(message: String) {
        failureMessage = message
        readyLatch.countDown()
    }

    fun awaitReady(timeoutMs: Long) {
        val completed = readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            throw IllegalStateException("Timed out waiting for shared camera startup")
        }

        failureMessage?.let { message ->
            throw IllegalStateException(message)
        }

        if (!configured) {
            throw IllegalStateException("Shared camera startup completed without success")
        }
    }
}
