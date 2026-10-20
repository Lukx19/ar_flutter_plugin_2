package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneViewHostRetentionCensusTest {
    private class Host

    private fun census() = SceneViewHostRetentionCensus(collect = { System.gc() })

    @Test
    fun `collectable disposed hosts are not counted as retained`() {
        val census = census()
        repeat(5) { census.recordDisposed(Host()) }

        val receipt = census.retainedAfterCollection(rounds = 20, pauseMs = 10)

        assertEquals(5L, receipt["disposedHosts"])
        assertEquals(0L, receipt["retainedDisposedHosts"])
        assertTrue(receipt.getValue("javaHeapUsedBytes") > 0)
    }

    @Test
    fun `a strongly reachable disposed host is counted as retained`() {
        val census = census()
        val kept = Host()
        census.recordDisposed(kept)
        census.recordDisposed(Host())

        val receipt = census.retainedAfterCollection(rounds = 20, pauseMs = 10)

        assertEquals(2L, receipt["disposedHosts"])
        assertEquals(1L, receipt["retainedDisposedHosts"])
        // Keeps the host strongly reachable until after the census.
        assertNotNull(kept)
    }
}
