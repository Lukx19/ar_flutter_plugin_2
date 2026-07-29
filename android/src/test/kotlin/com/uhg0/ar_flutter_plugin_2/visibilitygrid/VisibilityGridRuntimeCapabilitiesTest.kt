package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.google.ar.core.Config
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibilityGridRuntimeCapabilitiesTest {
    @Test
    fun `unsupported depth and unavailable renderer are reported honestly`() {
        val capabilities =
            VisibilityGridRuntimeCapabilities(
                featureReady = true,
                depthMode = Config.DepthMode.DISABLED,
                rendererReady = false,
            )

        assertEquals("unsupported", capabilities.depthCapability)
        assertEquals("featureOnly", capabilities.depthActiveMode)
        assertEquals(
            mapOf(
                "feature" to "configured",
                "depth" to "unsupported",
                "renderer" to "transientUnavailable",
                "totalGrid" to "healthy",
            ),
            capabilities.initialHealth(),
        )
    }

    @Test
    fun `failed feature capability makes the grid unavailable`() {
        val capabilities =
            VisibilityGridRuntimeCapabilities(
                featureReady = false,
                depthMode = Config.DepthMode.DISABLED,
                rendererReady = true,
            )

        assertEquals("failed", capabilities.initialHealth()["totalGrid"])
    }

    @Test
    fun `configured depth keeps the grid available without features`() {
        val capabilities =
            VisibilityGridRuntimeCapabilities(
                featureReady = false,
                depthMode = Config.DepthMode.AUTOMATIC,
                rendererReady = true,
            )

        assertEquals("healthy", capabilities.initialHealth()["totalGrid"])
        assertEquals("configured", capabilities.initialHealth()["depth"])
    }
}
