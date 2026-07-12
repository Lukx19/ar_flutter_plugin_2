package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawJpegCaptureCorrelatorTest {
    @Test
    fun `correlates all six component arrival orders`() {
        val permutations =
            listOf(
                listOf("jpeg", "raw", "result"),
                listOf("jpeg", "result", "raw"),
                listOf("raw", "jpeg", "result"),
                listOf("raw", "result", "jpeg"),
                listOf("result", "jpeg", "raw"),
                listOf("result", "raw", "jpeg"),
            )
        assertEquals(6, permutations.size)
        permutations.forEach { order ->
            val correlator = RawJpegCaptureCorrelator<String, String>({})
            var completed: CorrelatedRawJpegCapture<String, String>? = null
            order.forEachIndexed { index, component ->
                val value =
                    when (component) {
                        "jpeg" -> correlator.onJpeg(jpeg())
                        "raw" -> correlator.onRaw(42L, "raw-data")
                        else -> correlator.onResult(42L, "result-data")
                    }
                if (index < 2) assertNull(value)
                completed = value ?: completed
            }
            requireNotNull(completed)
            assertEquals("raw-data", completed!!.raw)
            assertEquals("result-data", completed!!.result)
            assertEquals(42L, completed!!.jpeg.sensorTimestampNs)
        }
    }

    @Test
    fun `clear closes unmatched raw component`() {
        val closed = mutableListOf<String>()
        val correlator = RawJpegCaptureCorrelator<String, String>(closed::add)
        correlator.onRaw(42L, "raw-data")

        correlator.clear()

        assertEquals(listOf("raw-data"), closed)
    }

    private fun jpeg() =
        PendingStillImagePayload(
            sensorTimestampNs = 42L,
            width = 10,
            height = 8,
            bytes = byteArrayOf(1),
            receivedAtMs = 0L,
        )
}
