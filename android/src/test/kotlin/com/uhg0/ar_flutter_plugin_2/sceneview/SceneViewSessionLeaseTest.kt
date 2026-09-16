package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneViewSessionLeaseTest {
    @Test
    fun replacementDoesNotAcquireUntilOutgoingGenerationReleases() {
        val lease = SceneViewSessionLease()
        val granted = mutableListOf<Long>()

        lease.request(1) { granted += 1 }
        lease.request(2) { granted += 2 }

        assertEquals(listOf(1L), granted)
        assertEquals(1L, lease.activeGenerationForTest())
        assertEquals(1, lease.queuedGenerationCountForTest())

        lease.releaseOrCancel(1)

        assertEquals(listOf(1L, 2L), granted)
        assertEquals(2L, lease.activeGenerationForTest())
        assertEquals(0, lease.queuedGenerationCountForTest())
    }

    @Test
    fun disposedQueuedGenerationNeverAcquiresTheSession() {
        val lease = SceneViewSessionLease()
        val granted = mutableListOf<Long>()

        lease.request(1) { granted += 1 }
        lease.request(2) { granted += 2 }
        lease.releaseOrCancel(2)
        lease.releaseOrCancel(1)

        assertEquals(listOf(1L), granted)
        assertNull(lease.activeGenerationForTest())
        assertEquals(0, lease.queuedGenerationCountForTest())
    }
}
