package com.uhg0.ar_flutter_plugin_2.capture

import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic only: proves Handler/Looper callback drain and full-cut fencing, not Camera2 hardware. */
@RunWith(AndroidJUnit4::class)
class NativeCaptureAdapterV2AndroidTest {
    @Test
    fun perViewBindingDrainsAndFencesLateComponents() {
        val thread = HandlerThread("native-capture-v2-test").apply { start() }
        try {
            val store = Store()
            val port = Port()
            val adapter = NativeCaptureAdapterV2(store, port)
            val request = request()
            adapter.admit(request)
            val qualifier = port.qualifier!!
            val done = CountDownLatch(1)
            Handler(thread.looper).post {
                port.callback!!.onComponents(SharedCameraComponentSetV2(qualifier, listOf(
                    CaptureComponentStreamV2(CaptureComponentKind.JPEG, ByteArrayInputStream("jpeg".toByteArray())),
                )))
                done.countDown()
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val late = ByteArrayInputStream(byteArrayOf(1))
            port.callback!!.onComponents(SharedCameraComponentSetV2(qualifier, listOf(
                CaptureComponentStreamV2(CaptureComponentKind.JPEG, late),
            )))
            assertEquals(-1, late.read())
            assertEquals(1L, adapter.snapshot().committed)
            assertEquals(1L, adapter.snapshot().lateCallbacks)
            adapter.close()
        } finally {
            thread.quitSafely()
            thread.join(5_000L)
        }
    }

    private fun request(): CaptureCommitRequest {
        val cut = CaptureLifecycleCut("session", 1, "group", 1, "ar", "view", 1, "binding", 1, 1)
        val accepted = CaptureAcceptedAttempt(
            CaptureAttemptIdentity("attempt", "commit", 1, cut), CaptureLane.MANUAL,
            CaptureComponentProfile("jpeg", setOf(CaptureComponentKind.JPEG), 128, 128),
            CaptureReservationLiability(0, 128, 1, 1, 0, true), digest("intent"), digest("accepted"),
        )
        val bytes = "jpeg".toByteArray()
        return CaptureCommitRequest(accepted, listOf(CaptureComponentDescriptor(CaptureComponentKind.JPEG, bytes.size.toLong(), digest("jpeg"), "object")), 1, digest("p"), digest("c"), digest("v"), digest("l"))
    }

    private class Port : SharedCameraExposurePortV2 {
        var qualifier: CaptureAttemptQualifierV2? = null
        var callback: SharedCameraExposureCallbackV2? = null
        override fun requestExposure(qualifier: CaptureAttemptQualifierV2, required: Set<CaptureComponentKind>, callback: SharedCameraExposureCallbackV2): Boolean {
            this.qualifier = qualifier; this.callback = callback; return true
        }
        override fun cancelExposure(qualifier: CaptureAttemptQualifierV2) = Unit
    }

    private class Store : NativeCaptureStorePortV2 {
        override fun acceptBeforeExposure(attempt: CaptureAcceptedAttempt) = CaptureReceipt(attempt.identity, CaptureAttemptPhase.RESERVED_ACCEPTED, "a", "a", true)
        override fun commitStreamed(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>): CaptureReceipt {
            streams.forEach { it.input.close() }
            val terminal = CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, request.accepted.identity, "c", "c", "id", 1, "root")
            return CaptureReceipt(request.accepted.identity, CaptureAttemptPhase.COMMITTED_PICTURE, "c", "c", true, terminal)
        }
        override fun queryReceipt(identity: CaptureAttemptIdentity): CaptureReceipt? = null
        override fun rebaseSameAttempt(request: CaptureCommitRequest): CaptureReceipt = throw IllegalStateException()
        override fun abandon(terminal: CaptureTerminal) = CaptureReceipt(terminal.identity, CaptureAttemptPhase.ABANDONED_ATTEMPT, "a", "a", true, terminal)
    }

    private companion object {
        fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).map { it.toInt() and 0xff }
    }
}
