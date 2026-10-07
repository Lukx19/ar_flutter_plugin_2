package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.*
import org.junit.Test

class StillPreviewFpsPolicyTest {
    private data class Fps(val lower: Int, val upper: Int)

    @Test fun `disabled preserves request identity and does not read range capabilities or write`() {
        val request = linkedMapOf<String, Any>("fps" to Fps(7, 30), "quality" to 95)
        val same = request
        assertFalse(StillPreviewFpsPolicy.apply<Fps>(false,
            { error("disabled repeating read") }, { error("disabled capability read") },
            { error("disabled write") }))
        assertSame(same, request)
        assertEquals(mapOf("fps" to Fps(7, 30), "quality" to 95), request)
    }

    @Test fun `enabled changes only FPS and preserves target tag generation pose and processing contracts`() {
        val targets = listOf(Any(), Any())
        val tag = Any()
        val request = linkedMapOf<String, Any>(
            "fps" to Fps(7, 30), "template" to "STILL", "quality" to 95,
            "crop" to listOf(0, 0, 4128, 3096), "processing" to listOf(2, 2, 2, 2, 2),
            "targets" to targets, "tag" to tag, "generation" to 8L,
            "requiresPose" to true, "probeRequiresPose" to false,
            "aeAfAwb" to listOf(1, 4, 1),
        )
        val before = request.toMap()
        val actual = Fps(30, 30)
        var reads = 0
        var writes = 0
        assertTrue(StillPreviewFpsPolicy.apply(true,
            { reads++; actual }, { listOf(Fps(7, 30), Fps(30, 30)) },
            { writes++; request["fps"] = it }))
        assertEquals(1, reads)
        assertEquals(1, writes)
        assertSame(actual, request["fps"])
        assertEquals(before - "fps", request - "fps")
        assertSame(targets, request["targets"])
        assertSame(tag, request["tag"])
    }

    @Test fun `missing actual repeating range rejects before capability read or write`() {
        assertRejected { StillPreviewFpsPolicy.apply<Fps>(true,
            { null }, { error("must reject before capability lookup") }, { error("write") }) }
    }

    @Test fun `missing or unsupported capability rejects without fallback or partial write`() {
        val actual = Fps(30, 30)
        assertRejected { StillPreviewFpsPolicy.apply(true, { actual }, { null }, { error("write") }) }
        assertRejected { StillPreviewFpsPolicy.apply(true, { actual }, { listOf(Fps(7, 30)) }, { error("write") }) }
    }

    @Test fun `request setter failure propagates instead of falsely reporting an effective match`() {
        val failure = IllegalArgumentException("setter rejected range")
        try {
            StillPreviewFpsPolicy.apply(true, { Fps(30, 30) }, { listOf(Fps(30, 30)) }, { throw failure })
            fail("Expected setter failure")
        } catch (actual: IllegalArgumentException) { assertSame(failure, actual) }
    }

    private fun assertRejected(action: () -> Unit) {
        try { action(); fail("Expected missing or unsupported range rejection") }
        catch (_: IllegalStateException) { }
    }
}
