package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CanonicalSourcePagingTest {
    @Test
    fun `source pages preserve unsigned IDs and arbitrary fingerprints in unsigned order`() {
        val ids = listOf(0x7fff_ffffL, 0x8000_0000L, 0xffff_ffffL)
        val sources = ids.mapIndexed { index, id -> source(id, index) }
        val bytes = M3CanonicalPageCache.encodeSourcePage(7, sources)
        val decoded = M3CanonicalPageCache.decodeSources(bytes)

        assertEquals(ids, decoded.map { it.id.value })
        decoded.forEachIndexed { index, source ->
            assertArrayEquals(
                ByteArray(32) { (index * 41 + it).toByte() },
                source.allocationFingerprint.toByteArray(),
            )
        }
        assertTrue(
            decoded.zipWithNext().all { (left, right) ->
                unsignedCompare(left.id.value, right.id.value) < 0
            }
        )
    }

    @Test
    fun `support page carries exact membership independent of lineage`() {
        val target = M3SurfaceId(0x8000_0000L)
        val memberships =
            listOf(
                M3PagedSupport(target, source(1, 1)),
                M3PagedSupport(target, source(0xffff_ffffL, 2)),
            )
        val decoded =
            M3CanonicalPageCache.decodeSupports(
                M3CanonicalPageCache.encodeSupportPage(2, memberships)
            )
        assertEquals(listOf(1L, 0xffff_ffffL), decoded.map { it.source.id.value })
        assertTrue(decoded.all { it.target == target })
    }

    @Test
    fun `cache performs at most one fixed read and refuses corruption before publication`() {
        val directory = Files.createTempDirectory("m3-page-cache").toFile()
        try {
            val file = File(directory, "pages")
            val bytes = M3CanonicalPageCache.encodeSourcePage(0, listOf(source(1, 0)))
            file.writeBytes(bytes)
            val entry =
                M3CanonicalDirectoryEntry(
                    M3CanonicalPageKind.SOURCE,
                    0,
                    1,
                    1,
                    0,
                    bytes.size,
                    1,
                    m3PageSha256(bytes),
                )
            M3CanonicalPageCache(file).use { cache ->
                val first = cache.read(0, entry) as M3CanonicalPageRead.Complete
                assertEquals(1, first.pageFaults)
                assertEquals(16_384, first.bytesRead)
                val second = cache.read(0, entry) as M3CanonicalPageRead.Complete
                assertEquals(0, second.pageFaults)
                assertEquals(0, second.bytesRead)
            }
            bytes[100] = 1
            file.writeBytes(bytes)
            val corrupt = M3CanonicalPageCache(file).use { it.read(0, entry) }
            assertEquals(
                M3CompactCanonicalRefusal.CORRUPT,
                (corrupt as M3CanonicalPageRead.Refused).reason,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `page inspection rejects structurally invalid padding before open publication`() {
        val bytes = M3CanonicalPageCache.encodeSourcePage(0, listOf(source(1, 0)))
        val entry =
            M3CanonicalDirectoryEntry(
                M3CanonicalPageKind.SOURCE,
                0,
                1,
                1,
                0,
                bytes.size,
                1,
                m3PageSha256(bytes),
            )
        assertTrue(M3CanonicalPageCache.inspectPage(entry, bytes) != null)
        bytes[bytes.lastIndex] = 1
        assertEquals(null, M3CanonicalPageCache.inspectPage(entry, bytes))
    }

    private fun source(id: Long, seed: Int) =
        M3PagedSource(
            M3SurfaceId(id),
            M3Voxel(seed, -seed, seed * 2),
            0x8123,
            197,
            M3CanonicalReceiptBytes(ByteArray(32) { (seed * 41 + it).toByte() }),
        )
}
