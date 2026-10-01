package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.google.ar.core.Config
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ArCoreRawDepthSourceTest {
    @Test
    fun `automatic and raw depth modes use the raw depth source contract`() {
        assertNotNull(ArCoreRawDepthSource(depthMode = Config.DepthMode.AUTOMATIC))
        assertNotNull(ArCoreRawDepthSource(depthMode = Config.DepthMode.RAW_DEPTH_ONLY))
    }

    @Test
    fun `disabled depth mode is rejected before frame acquisition`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArCoreRawDepthSource(depthMode = Config.DepthMode.DISABLED)
        }
    }
}
