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

    @Test
    fun `paired sends hold a lone sample until its pair arrives and keep order`() {
        val sent = mutableListOf<Map<String, Any?>>()
        val results = mutableListOf<MethodChannel.Result>()
        val flushes = mutableListOf<Runnable>()
        val dispatcher = PoseBatchDispatcher(
            { _, arguments, result -> sent += arguments; results += result },
            samplesPerSend = 2,
            scheduleFlush = { flushes += it },
        )

        dispatcher.offer(sample(1))
        assertEquals(0, sent.size)
        assertEquals(1, flushes.size)
        dispatcher.offer(sample(2))
        assertEquals(1, sent.size)
        assertEquals(2, sent[0]["sampleCount"])
        assertEquals(listOf(1L, 2L), sequences(sent[0]["sampleBytes"] as ByteArray))

        // The flush scheduled for sample 1 finds nothing pending.
        flushes.removeAt(0).run()
        assertEquals(1, sent.size)

        results[0].success(null)
        dispatcher.offer(sample(3))
        dispatcher.offer(sample(4))
        assertEquals(2, sent.size)
        assertEquals(listOf(3L, 4L), sequences(sent[1]["sampleBytes"] as ByteArray))
    }

    @Test
    fun `paired sends flush a held sample alone after the hold delay`() {
        val sent = mutableListOf<Map<String, Any?>>()
        val results = mutableListOf<MethodChannel.Result>()
        val flushes = mutableListOf<Runnable>()
        val dispatcher = PoseBatchDispatcher(
            { _, arguments, result -> sent += arguments; results += result },
            samplesPerSend = 2,
            scheduleFlush = { flushes += it },
        )

        dispatcher.offer(sample(1))
        flushes.removeAt(0).run()
        assertEquals(1, sent.size)
        assertEquals(1, sent[0]["sampleCount"])
        assertEquals(listOf(1L), sequences(sent[0]["sampleBytes"] as ByteArray))

        // A sample offered while a batch is in flight is held after the ACK
        // and flushed by a newly scheduled delay.
        dispatcher.offer(sample(2))
        assertEquals(0, flushes.size)
        results[0].success(null)
        assertEquals(1, sent.size)
        assertEquals(1, flushes.size)
        flushes.removeAt(0).run()
        assertEquals(2, sent.size)
        assertEquals(listOf(2L), sequences(sent[1]["sampleBytes"] as ByteArray))
    }

    @Test
    fun `paired sends keep drop oldest accounting while a batch is in flight`() {
        val sent = mutableListOf<Map<String, Any?>>()
        val results = mutableListOf<MethodChannel.Result>()
        val dispatcher = PoseBatchDispatcher(
            { _, arguments, result -> sent += arguments; results += result },
            samplesPerSend = 2,
            scheduleFlush = {},
        )

        dispatcher.offer(sample(1))
        dispatcher.offer(sample(2))
        repeat(9) { dispatcher.offer(sample((it + 3).toLong())) }
        results.single().success(null)
        assertEquals(2, sent.size)
        assertEquals(8, sent[1]["sampleCount"])
        assertEquals(1L, sent[1]["droppedOldestCount"])
        assertEquals((4L..11L).toList(), sequences(sent[1]["sampleBytes"] as ByteArray))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `paired sends require a flush scheduler`() {
        PoseBatchDispatcher({ _, _, _ -> }, samplesPerSend = 2)
    }

    private fun sample(sequence: Long): ByteArray = ByteArray(PackedPoseWireV2.SAMPLE_BYTES).also {
        PackedPoseWireV2.putLong(it, 0, sequence)
    }
    private fun sequence(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
    private fun sequences(bytes: ByteArray): List<Long> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until bytes.size / PackedPoseWireV2.SAMPLE_BYTES).map { buffer.getLong(it * PackedPoseWireV2.SAMPLE_BYTES) }
    }
}
