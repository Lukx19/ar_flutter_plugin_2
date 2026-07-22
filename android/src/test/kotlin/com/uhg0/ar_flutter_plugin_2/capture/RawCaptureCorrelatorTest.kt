package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawCaptureCorrelatorTest {
    @Test
    fun `correlates raw and result in either arrival order`() {
        listOf(listOf("raw", "result"), listOf("result", "raw")).forEach { order ->
            val correlator = RawCaptureCorrelator<String, String>({})
            var completed: CorrelatedRawCapture<String, String>? = null
            order.forEachIndexed { index, component ->
                val value =
                    if (component == "raw") {
                        correlator.onRaw(42L, "raw-data")
                    } else {
                        correlator.onResult(42L, "result-data")
                    }
                if (index == 0) assertNull(value)
                completed = value ?: completed
            }
            requireNotNull(completed)
            assertEquals("raw-data", completed!!.raw)
            assertEquals("result-data", completed!!.result)
        }
    }

    @Test
    fun `clear closes an unmatched raw image`() {
        val closed = mutableListOf<String>()
        val correlator = RawCaptureCorrelator<String, String>(closed::add)
        correlator.onRaw(42L, "raw-data")

        correlator.clear()

        assertEquals(listOf("raw-data"), closed)
    }
}
