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

    @Test
    fun `configured depth capability is cached for frame-time reads`() {
        var probes = 0
        val cache = VisibilityGridDepthModeCache()
        val session = Any()

        val configured =
            cache.configure(session) { mode ->
                probes++
                mode == Config.DepthMode.AUTOMATIC
            }

        assertEquals(Config.DepthMode.AUTOMATIC, configured)
        assertEquals(2, probes)
        repeat(120) {
            assertEquals(
                Config.DepthMode.AUTOMATIC,
                cache.configure(session) {
                    probes++
                    false
                },
            )
            assertEquals(Config.DepthMode.AUTOMATIC, cache.current())
        }
        assertEquals(2, probes)

        cache.reset()
        assertEquals(Config.DepthMode.DISABLED, cache.current())
        assertEquals(2, probes)
    }

    @Test
    fun `unsupported mode is quiet per session and replacement session is reprobed`() {
        var probes = 0
        val cache = VisibilityGridDepthModeCache()
        val unsupportedSession = Any()
        val depthSession = Any()

        repeat(120) {
            assertEquals(
                Config.DepthMode.DISABLED,
                cache.configure(unsupportedSession) {
                    probes++
                    false
                },
            )
            assertEquals(Config.DepthMode.DISABLED, cache.current())
        }
        assertEquals(2, probes)

        assertEquals(
            Config.DepthMode.RAW_DEPTH_ONLY,
            cache.configure(depthSession) {
                probes++
                it == Config.DepthMode.RAW_DEPTH_ONLY
            },
        )
        assertEquals(4, probes)
        repeat(120) {
            assertEquals(
                Config.DepthMode.RAW_DEPTH_ONLY,
                cache.configure(depthSession) {
                    probes++
                    false
                },
            )
        }
        assertEquals(4, probes)
    }
}
