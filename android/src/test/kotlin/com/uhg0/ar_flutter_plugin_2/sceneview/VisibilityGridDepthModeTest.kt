package com.uhg0.ar_flutter_plugin_2.sceneview

import com.google.ar.core.Config
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibilityGridDepthModeTest {
    @Test
    fun `raw depth is preferred before automatic and feature-only fallback`() {
        assertEquals(
            Config.DepthMode.RAW_DEPTH_ONLY,
            selectVisibilityGridDepthMode { true },
        )
        assertEquals(
            Config.DepthMode.AUTOMATIC,
            selectVisibilityGridDepthMode { it == Config.DepthMode.AUTOMATIC },
        )
        assertEquals(
            Config.DepthMode.DISABLED,
            selectVisibilityGridDepthMode { false },
        )
    }
}
