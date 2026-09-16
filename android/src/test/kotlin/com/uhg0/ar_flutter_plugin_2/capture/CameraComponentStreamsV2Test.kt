package com.uhg0.ar_flutter_plugin_2.capture

import java.nio.ByteBuffer
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraComponentStreamsV2Test {
    @Test
    fun `camera plane transfers directly and closes owner once`() {
        var closes = 0
        val stream = CameraPlaneInputStreamV2(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))) { closes++ }

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), stream.readBytes())
        stream.close()
        stream.close()
        assertEquals(1, closes)
    }

    @Test
    fun `bounded producer streams output and surfaces producer failure`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            var closes = 0
            val success = BoundedProducerInputStreamV2.start(executor, 4096, { closes++ }) { output ->
                repeat(8192) { output.write(it and 0xff) }
            }
            assertEquals(8192, success.readBytes().size)
            success.close()
            assertEquals(1, closes)

            val failure = BoundedProducerInputStreamV2.start(executor, 4096, {}) { output ->
                output.write(7)
                error("producer-cut")
            }
            assertThrows(java.io.IOException::class.java) { failure.readBytes() }
            failure.close()
        } finally {
            executor.shutdownNow()
        }
    }
}
