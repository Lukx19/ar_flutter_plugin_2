package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRequestGenerationTest {
    @Test
    fun `late callback from A cannot own B`() {
        val generations = CaptureRequestGeneration()
        val attemptA = generations.next()
        assertTrue(generations.clear(attemptA))
        val attemptB = generations.next()

        assertFalse(generations.isCurrent(attemptA))
        assertTrue(generations.isCurrent(attemptB))
        assertFalse(generations.clear(attemptA))
        assertTrue(generations.isCurrent(attemptB))
    }
}
