package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityGridInitialHandoffPolicyTest {
    @Test
    fun `full delta start applies initial geometry immediately`() {
        assertTrue(VisibilityGridInitialHandoffPolicy.applyOnStart(summaryOnly = false))
    }

    @Test
    fun `summary start reserves initial geometry for worker pull`() {
        assertFalse(VisibilityGridInitialHandoffPolicy.applyOnStart(summaryOnly = true))
    }
}
