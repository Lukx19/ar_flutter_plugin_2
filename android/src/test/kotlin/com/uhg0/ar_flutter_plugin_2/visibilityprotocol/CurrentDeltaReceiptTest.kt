package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CurrentDeltaReceiptTest {
    @Test
    fun `foreign arrays getters and produced frames cannot mutate a receipt`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val commandHash = ByteArray(32) { 7 }
        val receipt = CurrentDeltaReceiptV1(CurrentDeltaSelectorV1(1, 1, 1), 0, bytes, commandHash)
        bytes.fill(0)
        commandHash.fill(0)
        receipt.bytes.fill(99)
        receipt.commandHash.fill(99)
        val frames = receipt.produceFrames(TransactionResponseProfileV1.ordinary)
        (frames[1] as TransactionChunkFrameV1).value.bytes.fill(99)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), receipt.bytes)
        assertArrayEquals(ByteArray(32) { 7 }, receipt.commandHash)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), receipt.openStream().use { it.readBytes() })
    }

    @Test
    fun `exact serialization seals escaped output and preserves prior receipts across later writes`() {
        val expected = ByteArray(4_097) { it.toByte() }
        val source = expected.copyOf()
        lateinit var escapedOutput: OutputStream
        val receipt = requireNotNull(CurrentDeltaReceiptV1.serialize(
            CurrentDeltaSelectorV1(1, 1, 1), 0, source.size.toLong(), ByteArray(32),
        ) { output ->
            escapedOutput = output
            output.write(source[0].toInt())
            DataOutputStream(output).use { it.write(source, 1, source.size - 1) }
        })
        source.fill(0)
        assertThrows(IllegalStateException::class.java) { escapedOutput.write(99) }
        assertThrows(IllegalStateException::class.java) { escapedOutput.write(byteArrayOf(99)) }
        receipt.bytes.fill(99)

        val next = requireNotNull(CurrentDeltaReceiptV1.serialize(
            CurrentDeltaSelectorV1(2, 2, 2), 1, source.size.toLong(), ByteArray(32),
        ) { it.write(source) })
        assertEquals(expected.size, receipt.byteCount)
        assertArrayEquals(expected, receipt.bytes)
        assertArrayEquals(ByteArray(source.size), next.bytes)
    }

    @Test
    fun `serialization refuses wrong or unbounded lengths and seals failed writers`() {
        val selector = CurrentDeltaSelectorV1(1, 1, 1)
        for (length in listOf(-1L, Long.MAX_VALUE,
            StructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES.toLong() + 1L)) {
            var invoked = false
            assertNull(CurrentDeltaReceiptV1.serialize(selector, 0, length, ByteArray(32)) { invoked = true })
            assertFalse(invoked)
        }
        lateinit var escapedOutput: OutputStream
        assertNull(CurrentDeltaReceiptV1.serialize(selector, 0, 4, ByteArray(32)) {
            escapedOutput = it
            it.write(byteArrayOf(1, 2, 3))
        })
        assertThrows(IllegalStateException::class.java) { escapedOutput.write(4) }
        assertNull(CurrentDeltaReceiptV1.serialize(selector, 0, 2, ByteArray(32)) {
            escapedOutput = it
            it.write(byteArrayOf(1, 2, 3))
        })
        assertThrows(IllegalStateException::class.java) { escapedOutput.write(4) }
        assertThrows(IOException::class.java) {
            CurrentDeltaReceiptV1.serialize(selector, 0, 4, ByteArray(32)) {
                escapedOutput = it
                throw IOException("source failed")
            }
        }
        assertThrows(IllegalStateException::class.java) { escapedOutput.write(4) }
        assertEquals(0, requireNotNull(CurrentDeltaReceiptV1.serialize(
            selector, 0, 0, ByteArray(32),
        ) {}).byteCount)
    }
}
