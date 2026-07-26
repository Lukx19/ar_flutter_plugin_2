package com.uhg0.ar_flutter_plugin_2.capture

import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class PoseBatchDispatcherTest {
    @Test
    fun `keeps one in flight batch and drops oldest beyond eight pending samples`() {
        val sent = mutableListOf<Map<String, Any?>>()
        val results = mutableListOf<MethodChannel.Result>()
        val dispatcher = PoseBatchDispatcher({ _, arguments, result ->
            sent += arguments
            results += result
        })

        dispatcher.offer(sample(1))
        repeat(10) { dispatcher.offer(sample((it + 2).toLong())) }
        assertEquals(1, sent.size)

        results.single().success(null)
        assertEquals(2, sent.size)
        val second = sent[1]
        assertEquals(8, (second["samples"] as List<*>).size)
        assertEquals(2L, second["droppedOldestCount"])
        assertEquals(4L, ((second["samples"] as List<Map<String, Any?>>).first()["sequence"] as Long))
    }

    private fun sample(sequence: Long): Map<String, Any?> = mapOf("sequence" to sequence)
}
