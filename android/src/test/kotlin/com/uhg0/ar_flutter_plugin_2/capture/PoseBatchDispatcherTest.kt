package com.uhg0.ar_flutter_plugin_2.capture

import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        assertEquals(8, second["sampleCount"])
        assertEquals(2L, second["droppedOldestCount"])
        assertEquals(4L, sequence(second["sampleBytes"] as ByteArray))
        assertEquals(1L, sequence(sent.first()["sampleBytes"] as ByteArray))
    }

    @Test
    fun `clear drops pending samples while old acknowledged payload remains immutable`() {
        val sent = mutableListOf<Map<String, Any?>>()
        val results = mutableListOf<MethodChannel.Result>()
        val dispatcher = PoseBatchDispatcher({ _, arguments, result -> sent += arguments; results += result })
        dispatcher.offer(sample(1))
        dispatcher.offer(sample(2))
        dispatcher.clear()
        dispatcher.offer(sample(3))
        results[0].error("cancelled", null, null)
        assertEquals(2, sent.size)
        assertEquals(3L, sequence(sent[1]["sampleBytes"] as ByteArray))
        assertEquals(1L, sequence(sent[0]["sampleBytes"] as ByteArray))
        assertEquals(0L, sent[1]["droppedOldestCount"])
    }

    private fun sample(sequence: Long): ByteArray = ByteArray(PackedPoseWireV2.SAMPLE_BYTES).also {
        PackedPoseWireV2.putLong(it, 0, sequence)
    }
    private fun sequence(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
}
