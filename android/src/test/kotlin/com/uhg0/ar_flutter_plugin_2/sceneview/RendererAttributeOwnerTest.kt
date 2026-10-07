package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.RendererStyleCutPayloadV1
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RendererAttributeOwnerTest {
    @Test fun `codec uses fixed canonical header and distinguishes unavailable from zero`() {
        val header = RendererAttributeCodec.header(style(1), 7, 3, 2, 1, 1)
        val row = rows(longArrayOf(2))
        RendererAttributeCodec.validateRow(row, 0, 2)
        assertEquals(256, header.bytes.size)
        assertEquals(2006, RendererAttributeCodec.IDS_PER_PAGE)
        assertEquals(668, RendererAttributeCodec.ROWS_PER_PAGE)
        val malformed = header.bytes.copyOf().also { it[255] = 1 }
        assertThrows(IllegalArgumentException::class.java) { RendererAttributeCodec.decode(malformed) }
        row[11] = 1
        assertThrows(IllegalArgumentException::class.java) { RendererAttributeCodec.validateRow(row, 0, 2) }
        row.fill(0xff.toByte(), 12, 18)
        RendererAttributeCodec.validateRow(row, 0, 2)
        row[8] = 0x80.toByte()
        assertThrows(IllegalArgumentException::class.java) { RendererAttributeCodec.validateRow(row, 0, 2) }
    }

    @Test fun `selection remains bounded sorted and rejects an equal-size foreign cut`() {
        val fixture = Fixture(longArrayOf(9, 2, 5))
        val selected = fixture.select()
        val data = ByteBuffer.wrap(selected).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2L, data.getLong(256)); assertEquals(5L, data.getLong(264)); assertEquals(9L, data.getLong(272))
        val header = RendererAttributeCodec.decode(selected).first
        fixture.ids = longArrayOf(9, 2, 6)
        assertEquals("selectionChanged", fixture.outcome(fixture.owner.exchange(header.page(2, 0, 1, 0, 3).encode(rows(longArrayOf(2, 5, 9))))))
        assertNull(fixture.attached)
        assertTrue(fixture.owner.portableBytes() < 4096)
    }

    @Test fun `partial digest mismatch and stale commit never attach a mixed table`() {
        val fixture = Fixture(longArrayOf(2, 3))
        val selected = RendererAttributeCodec.decode(fixture.select()).first
        assertEquals("incomplete", fixture.outcome(fixture.owner.exchange(selected.page(3, 0, 0, 0, 0).encode())))
        val rows = rows(longArrayOf(2, 3))
        val hash = RendererAttributeCodec.digest(selected, rows)
        val page = selected.page(2, 0, 1, 0, 2, hash)
        assertEquals("accepted", fixture.outcome(fixture.owner.exchange(page.encode(rows))))
        hash[0] = (hash[0].toInt() xor 1).toByte()
        assertEquals("incomplete", fixture.outcome(fixture.owner.exchange(selected.page(3, 0, 0, 0, 0, hash).encode())))
        fixture.style = style(2)
        assertEquals("staleCut", fixture.outcome(fixture.owner.exchange(page.page(3, 0, 0, 0, 0).encode())))
        assertNull(fixture.attached)
    }

    @Test fun `complete commit is exact replay while conflicting digest and lost status stay separate from ACK`() {
        val fixture = Fixture(longArrayOf(2))
        val committed = fixture.transfer()
        assertEquals("accepted", fixture.outcome(fixture.owner.exchange(committed.encode())))
        assertEquals("accepted", fixture.outcome(fixture.owner.exchange(committed.page(4, 0, 0, 0, 0).encode())))
        val conflict = committed.bytes.copyOf().also { it[200] = (it[200].toInt() xor 1).toByte() }
        assertNotEquals("accepted", fixture.outcome(fixture.owner.exchange(conflict)))
        assertEquals(1, fixture.attachCount)
        fixture.owner.invalidate()
        assertNotEquals("accepted", fixture.outcome(fixture.owner.exchange(committed.page(4, 0, 0, 0, 0).encode())))
        assertEquals(1, fixture.attachCount)
    }

    @Test fun `two held tables block a third owner until the actual upload lease drains`() {
        val fixture = Fixture(longArrayOf(2))
        fixture.transfer()
        val first = fixture.attached!!.acquire()
        fixture.style = style(2); fixture.owner.invalidate(); fixture.transfer()
        val second = fixture.attached!!.acquire()
        fixture.style = style(3); fixture.owner.invalidate()
        assertEquals("busy", fixture.outcome(fixture.owner.exchange(fixture.request().encode())))
        assertEquals(2, fixture.owner.snapshot()["maxTables"])
        first.close()
        assertTrue(fixture.owner.exchange(fixture.request().encode()) is ByteArray)
        second.close()
        fixture.transfer()
        assertEquals(2, fixture.owner.snapshot()["maxTables"])
    }

    @Test fun `missing budget and unavailable acknowledged basis refuse before staging allocation`() {
        val fixture = Fixture(LongArray(4096) { it + 1L })
        fixture.budget = false
        assertEquals("busy", fixture.outcome(fixture.owner.exchange(fixture.request().encode())))
        assertEquals(512L, fixture.owner.portableBytes())
        fixture.budget = true; fixture.acknowledged = false
        assertEquals("staleCut", fixture.outcome(fixture.owner.exchange(fixture.request().encode())))
        fixture.acknowledged = true
        assertEquals(2006, RendererAttributeCodec.decode(fixture.select()).first.count)
    }

    private class Fixture(var ids: LongArray) {
        var style = style(1)
        var budget = true
        var acknowledged = true
        var attached: RendererAttributeTable? = null
        var attachCount = 0
        val owner = RendererAttributeOwner(
            basis = { if (!acknowledged) null else RendererAttributeBasis(style, 7, 1, 1, ids.size) { ids[it] } },
            admit = { budget },
            attach = { attached = it; attachCount++; true },
        )
        fun request() = RendererAttributeCodec.header(style, 7, 0, 1, 1, 0)
        fun select() = owner.exchange(request().encode()) as ByteArray
        fun outcome(raw: Any) = (raw as Map<*, *>)["outcome"]
        fun transfer(): RendererAttributeCodec.Header {
            val selected = RendererAttributeCodec.decode(select()).first
            val sorted = ids.sortedArray()
            val body = rows(sorted)
            val hash = RendererAttributeCodec.digest(selected, body)
            val pages = maxOf(1, (ids.size + 667) / 668)
            var first = 0
            repeat(pages) { index ->
                val count = minOf(668, ids.size - first)
                val page = selected.page(2, index, pages, first, count, hash)
                assertEquals("accepted", outcome(owner.exchange(page.encode(body, first * 24, count * 24))))
                first += count
            }
            val commit = selected.page(3, 0, 0, 0, 0, hash)
            assertEquals("accepted", outcome(owner.exchange(commit.encode())))
            return commit
        }
    }

    companion object {
        fun style(revision: Long): ByteArray = RendererStyleCutPayloadV1(
            ByteArray(16) { (it + 1).toByte() }, 12, 7, 2, 2, 1,
            revision, revision, revision, revision, revision, true,
            longArrayOf(), byteArrayOf(),
        ).canonicalBytes()
        fun rows(ids: LongArray): ByteArray = ByteArray(ids.size * 24).also { bytes ->
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            ids.forEachIndexed { index, id ->
                data.putLong(index * 24, id); data.putShort(index * 24 + 8, 0)
                bytes[index * 24 + 10] = 64; bytes[index * 24 + 11] = 3
            }
        }
    }
}
