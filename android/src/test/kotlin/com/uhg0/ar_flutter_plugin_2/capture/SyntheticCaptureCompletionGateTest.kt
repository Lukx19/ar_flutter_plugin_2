package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticCaptureCompletionGateTest {
    @Test
    fun `held synthetic picture completes exactly once with the accepted qualifier`() {
        val gate = SyntheticCaptureCompletionGate()
        val qualifier = qualifier("manual-1")
        val received = mutableListOf<SharedCameraComponentSetV2>()
        val callback = callback(received)

        assertTrue(gate.hold(qualifier, setOf(CaptureComponentKind.JPEG), callback))
        assertFalse(gate.hold(qualifier("manual-2"), setOf(CaptureComponentKind.JPEG), callback))
        assertTrue(received.isEmpty())
        assertTrue(gate.complete())
        assertFalse(gate.complete())
        assertEquals(1, received.size)
        assertEquals(qualifier, received.single().qualifier)
        assertEquals(listOf(CaptureComponentKind.JPEG), received.single().streams.map { it.kind })
        received.single().streams.forEach { it.input.close() }
    }

    @Test
    fun `cancel and clear prevent a late synthetic picture`() {
        val gate = SyntheticCaptureCompletionGate()
        val first = qualifier("manual-1")
        val second = qualifier("manual-2")
        val received = mutableListOf<SharedCameraComponentSetV2>()
        val callback = callback(received)

        assertTrue(gate.hold(first, setOf(CaptureComponentKind.JPEG), callback))
        gate.cancel(second)
        assertTrue(gate.complete())
        assertTrue(gate.hold(second, setOf(CaptureComponentKind.JPEG), callback))
        gate.cancel(second)
        assertFalse(gate.complete())
        assertTrue(gate.hold(first, setOf(CaptureComponentKind.JPEG), callback))
        gate.clear()
        assertFalse(gate.complete())
        assertEquals(1, received.size)
        received.single().streams.forEach { it.input.close() }
    }

    private fun callback(received: MutableList<SharedCameraComponentSetV2>) =
        object : SharedCameraExposureCallbackV2 {
            override fun onComponents(components: SharedCameraComponentSetV2) {
                received += components
            }

            override fun onFailure(qualifier: CaptureAttemptQualifierV2, reason: String) {
                throw AssertionError("Unexpected failure: $reason")
            }
        }

    private fun qualifier(attemptId: String): CaptureAttemptQualifierV2 {
        val cut = CaptureLifecycleCut(
            sessionId = "session", sessionGeneration = 1,
            groupId = "group", groupGeneration = 1,
            arSessionId = "ar-session", viewId = "view", viewGeneration = 1,
            bindingToken = "binding", lifecycleSequence = 1, operationGeneration = 1,
        )
        return CaptureAttemptQualifierV2(
            CaptureAttemptIdentity(attemptId, "commit-$attemptId", 1, cut), cut, 1,
        )
    }
}
