package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageRendererPublicationMailboxTest {
    @Test
    fun `only the latest pending cut survives and skipped cuts require full refresh`() {
        val mailbox = CoverageRendererPublicationMailbox<Long>()
        assertTrue(mailbox.offer(1L))
        assertTrue(mailbox.offer(2L))
        assertTrue(mailbox.offer(3L))

        val latest = checkNotNull(mailbox.take())
        assertEquals(3L, latest.value)
        assertTrue(latest.coalesced)
        assertFalse(latest.clearBeforeApply)
        assertNull(mailbox.take())

        mailbox.offer(4L)
        assertFalse(checkNotNull(mailbox.take()).coalesced)
    }

    @Test
    fun `clear remains a generation boundary when a newer cut supersedes it`() {
        val mailbox = CoverageRendererPublicationMailbox<String>()
        mailbox.offer("old group")
        mailbox.offer("clear", clearsRenderer = true)
        mailbox.offer("new group")
        mailbox.offer("new group latest cut")

        val latest = checkNotNull(mailbox.take())
        assertEquals("new group latest cut", latest.value)
        assertTrue(latest.clearBeforeApply)
        assertTrue(latest.coalesced)

        mailbox.offer("next adjacent cut")
        assertFalse(checkNotNull(mailbox.take()).clearBeforeApply)
    }

    @Test
    fun `host close releases pending publication and fences late producer offers`() {
        val mailbox = CoverageRendererPublicationMailbox<Any>()
        mailbox.offer(Any())
        mailbox.close()

        assertTrue(mailbox.isClosed)
        assertNull(mailbox.take())
        assertFalse(mailbox.offer(Any()))
        assertNull(mailbox.take())
    }
}
