package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.contractMagic
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class FocusPriorityMessageTest {
    private fun message(count: Int = 1, priority: Float = 0.75f): ByteArray {
        val b = ByteBuffer.allocate(8 + count * 16).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, contractMagic("C3FP"))
        b.putShort(4, 1.toShort())
        b.put(6, count.toByte())
        for (i in 0 until count) {
            b.putFloat(8 + i * 16, 1f)
            b.putFloat(12 + i * 16, 2f)
            b.putFloat(16 + i * 16, -3f)
            b.putFloat(20 + i * 16, priority)
        }
        return b.array()
    }

    @Test fun boundedSnapshotAndMalformedUpdatesPreservePreviousPaint() {
        val handler = DisplayModeHandler()
        assertEquals(DisplayIntake.OVERLAY, handler.onMessage(message(64)))
        val out = FloatArray(256)
        assertEquals(64, handler.snapshotFocus(out, -1))
        assertEquals(0.75f, out[255], 0f)
        val version = handler.currentFocusVersion
        for (bad in arrayOf(message(65), message(priority = Float.NaN), message(priority = 1.1f), message().copyOf(23))) {
            assertEquals(DisplayIntake.REJECTED, handler.onMessage(bad))
            assertEquals(version, handler.currentFocusVersion)
        }
        handler.snapshotFocus(out, -1)
        assertEquals(0.75f, out[255], 0f)
        assertEquals(DisplayIntake.OVERLAY, handler.onMessage(message(0)))
        assertEquals(0, handler.snapshotFocus(out, -1))
        assertTrue(out.all { it == 0f })
        handler.onMessage(message())
        handler.clearFocus()
        assertEquals(0, handler.snapshotFocus(out, -1))
    }
}
