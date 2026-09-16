package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableBackgroundRequestRegistryTest {
    @Test
    fun `cancel completes a never-reply result once and suppresses a late reply`() {
        val registry = CancellableBackgroundRequestRegistry()
        val original = RecordingResult()
        val operation = registry.register("view-1-7", original)

        assertTrue(registry.cancel("view-1-7"))
        operation.success(mapOf("applied" to true))

        assertEquals(1, original.completions.size)
        assertEquals("VG_CANCELLED", original.completions.single())
        assertEquals(0, registry.pendingCount)
    }

    @Test
    fun `cancel after an operation reply acknowledges no cancellation`() {
        val registry = CancellableBackgroundRequestRegistry()
        val original = RecordingResult()
        val operation = registry.register("view-1-8", original)

        operation.success(mapOf("applied" to true))

        assertFalse(registry.cancel("view-1-8"))
        assertEquals(1, original.completions.size)
        assertEquals("success", original.completions.single())
    }

    @Test
    fun `timeout cancellation before a late error still completes exactly once`() {
        val registry = CancellableBackgroundRequestRegistry()
        val original = RecordingResult()
        val operation = registry.register("view-1-9", original)

        assertTrue(registry.cancel("view-1-9"))
        operation.error("VG_INTERNAL", "late", null)
        operation.notImplemented()

        assertEquals(listOf("VG_CANCELLED"), original.completions)
    }

    private class RecordingResult : MethodChannel.Result {
        val completions = mutableListOf<String>()

        override fun success(result: Any?) {
            completions += "success"
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            completions += errorCode
        }

        override fun notImplemented() {
            completions += "notImplemented"
        }
    }
}
