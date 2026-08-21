package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityGridRendererLifecycleTest {
    @Test
    fun `outgoing mesh callbacks cannot complete a replacement generation`() {
        val lifecycle = VisibilityGridRendererLifecycle()
        val first = lifecycle.requestReplacement()
        assertTrue(lifecycle.markMounted(first))

        val replacement = lifecycle.requestReplacement()
        assertFalse(lifecycle.markMounted(first))
        assertTrue(lifecycle.markUnmounted(first))
        assertNull(lifecycle.mountedGeneration())

        assertTrue(lifecycle.markMounted(replacement))
        assertEquals(replacement, lifecycle.mountedGeneration())
        assertFalse(lifecycle.markUnmounted(first))
        assertEquals(replacement, lifecycle.mountedGeneration())
    }
}
