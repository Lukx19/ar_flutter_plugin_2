package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackgroundCancellationSchemaTest {
    @Test
    fun `accepts only the exact versioned cancellation schema`() {
        assertEquals(
            "request-7",
            parseBackgroundCancellationRequest(
                mapOf("version" to "visibility_grid_wire_v1", "backgroundRequestId" to "request-7"),
                "visibility_grid_wire_v1",
            ),
        )
        listOf<Any?>(
            null,
            mapOf("backgroundRequestId" to "request-7"),
            mapOf("version" to 1, "backgroundRequestId" to "request-7"),
            mapOf("version" to "wrong", "backgroundRequestId" to "request-7"),
            mapOf("version" to "visibility_grid_wire_v1", "backgroundRequestId" to 7),
            mapOf("version" to "visibility_grid_wire_v1", "backgroundRequestId" to ""),
            mapOf("version" to "visibility_grid_wire_v1", "backgroundRequestId" to "request-7", "extra" to true),
        ).forEach { arguments ->
            assertThrows(IllegalArgumentException::class.java) {
                parseBackgroundCancellationRequest(arguments, "visibility_grid_wire_v1")
            }
        }
    }
}
