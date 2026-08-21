package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedReplyFenceTest {
    @Test fun `success replies exactly once`() {
        val values = mutableListOf<String>(); val fence = BoundedReplyFence<String>()
        val token = fence.begin(values::add, "superseded")
        assertTrue(fence.settle(token, "success")); assertFalse(fence.settle(token, "late"))
        assertEquals(listOf("success"), values)
    }
    @Test fun `timeout and failure are terminal replies`() {
        val values = mutableListOf<String>(); val fence = BoundedReplyFence<String>()
        assertTrue(fence.settle(fence.begin(values::add, "superseded"), "timeout"))
        assertTrue(fence.settle(fence.begin(values::add, "superseded"), "failure"))
        assertEquals(listOf("timeout", "failure"), values)
    }
    @Test fun `supersede and disposal fence late completion`() {
        val values = mutableListOf<String>(); val fence = BoundedReplyFence<String>()
        val old = fence.begin({ values += "old:$it" }, "superseded")
        val current = fence.begin({ values += "new:$it" }, "superseded")
        assertFalse(fence.settle(old, "success")); fence.dispose("cancelled")
        assertFalse(fence.settle(current, "late"))
        assertEquals(listOf("old:superseded", "new:cancelled"), values)
    }
}
