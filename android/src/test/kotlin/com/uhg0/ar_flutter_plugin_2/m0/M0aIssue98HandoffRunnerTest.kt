package com.uhg0.ar_flutter_plugin_2.m0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M0aIssue98HandoffRunnerTest {
    @Test
    fun `Issue 98 real terminal drain replacement publishes transaction one and fences old token`() {
        val receipt = M0aIssue98HandoffRunner.run()
        assertEquals(Long.MAX_VALUE, receipt.oldTransactionId)
        assertEquals(Long.MAX_VALUE, receipt.oldRequestSequence)
        assertEquals(5, receipt.terminalResultFlags)
        assertEquals(Long.MAX_VALUE, receipt.terminalNextExpectedRequestSequence)
        assertEquals(0, receipt.freshTransactionId)
        assertEquals(1, receipt.freshRequestSequence)
        assertEquals(1, receipt.nextTransactionId)
        assertEquals(1, receipt.semanticEffectCount)
        assertEquals(0, receipt.oldTokenPublicationCount)
        assertEquals(0, receipt.rootIsolateSurfaceBytes)
        assertTrue(receipt.oldClosedResources > 0)
        assertTrue(receipt.freshActiveResources > 0)
    }
}
