package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

class CanonicalReadyHintOwnerTest {
    @Test fun `binary envelope validates version length generation and every owner lane`() {
        val hint = hint(2)
        assertEquals(200, hint.bytes.size)
        assertEquals(2L, hint.transactionId)
        assertTrue(hint.sameOwner(hint(3)))
        for (index in listOf(8, 24, 40, 56, 72, 88, 104, 112, 120, 128, 136, 144, 152, 160, 168)) {
            val foreign = hint.bytes.copyOf().also { it[index] = (it[index].toInt() + 1).toByte() }
            assertFalse(hint.sameOwner(requireNotNull(CanonicalReadyHintV1.decode(foreign))))
        }
        assertNull(CanonicalReadyHintV1.decode(hint.bytes.copyOf(199)))
        assertNull(CanonicalReadyHintV1.decode(hint.bytes.copyOf().also { it[4] = 2 }))
        assertNull(CanonicalReadyHintV1.decode(hint.bytes.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(128, 0)
        }))
    }

    @Test fun `registration observes ready state on either side of publication`() {
        for (registerFirst in listOf(true, false)) {
            val fixture = Fixture()
            if (registerFirst) fixture.owner.register()
            fixture.owner.stage(hint(2))
            val stamp = fixture.owner.publicationStamp()
            assertTrue(fixture.posts.isEmpty())
            fixture.owner.publish(stamp)
            if (!registerFirst) fixture.owner.register()
            assertEquals(1, fixture.posts.size)
            fixture.runPost()
            assertEquals(2L, fixture.sent.single().transactionId)
            fixture.complete(true)
            assertEquals(0, fixture.owner.snapshot()["pendingCallbacks"])
        }
    }

    @Test fun `old finally cannot publish a newer still protected stage`() {
        val fixture = Fixture()
        fixture.owner.register()
        fixture.owner.stage(hint(2))
        val oldStamp = fixture.owner.publicationStamp()
        fixture.owner.stage(hint(3))
        fixture.owner.publish(oldStamp)
        assertTrue(fixture.posts.isEmpty())
        fixture.owner.publish(fixture.owner.publicationStamp())
        fixture.runPost()
        assertEquals(3L, fixture.sent.single().transactionId)
    }

    @Test fun `held delivery coalesces one latest with two envelopes and one callback`() {
        val fixture = Fixture()
        fixture.owner.register()
        fixture.offer(2)
        fixture.runPost()
        for (transaction in 3L..1002L) fixture.offer(transaction)
        assertTrue(fixture.posts.isEmpty())
        assertEquals(1, fixture.callbacks.size)
        assertEquals(2L, fixture.owner.snapshot()["maxHints"])
        assertEquals(1L, fixture.owner.snapshot()["maxCallbacks"])
        fixture.complete(true)
        fixture.runPost()
        assertEquals(1002L, fixture.sent.last().transactionId)
        fixture.complete(true)
        assertEquals(0, fixture.owner.snapshot()["pendingCallbacks"])
    }

    @Test fun `pause and resume observe latest without runnable paused work`() {
        val fixture = Fixture()
        fixture.owner.register()
        fixture.offer(2)
        fixture.owner.unregister()
        fixture.runPost()
        assertTrue(fixture.sent.isEmpty())
        assertEquals(0, fixture.owner.snapshot()["pendingHints"])
        fixture.owner.restorePublished(hint(2))
        fixture.owner.register()
        fixture.runPost()
        assertEquals(2L, fixture.sent.single().transactionId)
    }

    @Test fun `replacement reserves old callback and fences its late completion`() {
        val fixture = Fixture()
        fixture.owner.register()
        fixture.offer(2)
        fixture.runPost()
        fixture.owner.reset()
        fixture.owner.register()
        fixture.offer(3)
        assertTrue(fixture.posts.isEmpty())
        fixture.complete(true)
        assertEquals(0L, fixture.owner.snapshot()["delivered"])
        fixture.runPost()
        assertEquals(3L, fixture.sent.last().transactionId)
    }

    @Test fun `lost notification cannot create another RPC or unbounded history`() {
        val fixture = Fixture()
        fixture.owner.register()
        fixture.offer(2)
        fixture.runPost()
        for (transaction in 3L..1002L) fixture.offer(transaction)
        assertEquals(1, fixture.sent.size)
        assertEquals(2, fixture.owner.snapshot()["pendingHints"])
        assertEquals(1, fixture.owner.snapshot()["pendingCallbacks"])
        fixture.owner.reset()
        assertEquals(0, fixture.owner.snapshot()["pendingHints"])
        fixture.complete(false)
        assertEquals(0, fixture.owner.snapshot()["pendingCallbacks"])
    }

    @Test fun `disposal before posted delivery suppresses send and drains owner`() {
        val fixture = Fixture()
        fixture.owner.register()
        fixture.offer(2)
        fixture.owner.reset()
        fixture.runPost()
        assertTrue(fixture.sent.isEmpty())
        assertEquals(0, fixture.owner.snapshot()["pendingHints"])
        assertEquals(0, fixture.owner.snapshot()["pendingCallbacks"])
    }

    @Test fun `process reservation survives unknown old delivery and releases only after callback drain`() {
        val posts = ArrayDeque<() -> Unit>()
        var completion: ((Boolean) -> Unit)? = null
        val owner = requireNotNull(CanonicalReadyHintOwner.tryCreate({ posts.add(it) }, { _, done -> completion = done }))
        assertNull(CanonicalReadyHintOwner.tryCreate({}, { _, _ -> }))
        owner.register()
        owner.stage(hint(2))
        owner.publish(owner.publicationStamp())
        posts.removeFirst().invoke()
        owner.close()
        assertEquals(true, owner.snapshot()["unknownTransportReserved"])
        assertNull(CanonicalReadyHintOwner.tryCreate({}, { _, _ -> }))
        requireNotNull(completion)(false)
        val replacement = requireNotNull(CanonicalReadyHintOwner.tryCreate({}, { _, _ -> }))
        owner.close()
        assertNull(CanonicalReadyHintOwner.tryCreate({}, { _, _ -> }))
        replacement.close()
        assertEquals(0, owner.snapshot()["pendingCallbacks"])
    }

    private class Fixture {
        val posts = ArrayDeque<() -> Unit>()
        val callbacks = ArrayDeque<(Boolean) -> Unit>()
        val sent = mutableListOf<CanonicalReadyHintV1>()
        val owner = CanonicalReadyHintOwner(
            post = { posts.add(it) },
            send = { bytes, done -> sent.add(requireNotNull(CanonicalReadyHintV1.decode(bytes))); callbacks.add(done) },
        )
        fun offer(transaction: Long) { owner.stage(hint(transaction)); owner.publish(owner.publicationStamp()) }
        fun runPost() = posts.removeFirst().invoke()
        fun complete(accepted: Boolean) = callbacks.removeFirst().invoke(accepted)
    }

    companion object {
        private fun hint(transaction: Long) = CanonicalReadyHintV1.create(
            List(6) { index -> ByteArray(16) { (index + 1).toByte() } },
            longArrayOf(0, 2, 3, 4, 5, 6, 7, 8, 9, transaction, transaction, 1),
        )
    }
}
