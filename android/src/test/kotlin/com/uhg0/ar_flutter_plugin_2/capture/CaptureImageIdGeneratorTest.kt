package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureImageIdGeneratorTest {
    @Test
    fun `generated ids are UUID backed and unique`() {
        val ids = List(10_000) { CaptureImageIdGenerator.next() }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(
            ids.all {
                it.matches(
                    Regex(
                        "img_[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-" +
                            "[89ab][0-9a-f]{3}-[0-9a-f]{12}",
                    ),
                )
            },
        )
    }
}
