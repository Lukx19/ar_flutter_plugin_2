package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalMutableStoreSortTest {
    @Test
    fun `scratch pool reuses growth buffers and caps total retention`() {
        val pool = CowSortedPageScratchPool()
        val small = pool.acquire(32 * 1024)
        val large = pool.acquire(96 * 1024)
        pool.release(small)
        pool.release(large)

        assertEquals(2, pool.retainedBufferCount())
        assertEquals(CowSortedPageWriter.MAX_RETAINED_POOL_BYTES, pool.retainedBytes())

        val reused = pool.acquire(16 * 1024)
        assertSame(small, reused)
        pool.release(reused)
        pool.release(ByteArray(256 * 1024))
        assertEquals(2, pool.retainedBufferCount())
        assertEquals(CowSortedPageWriter.MAX_RETAINED_POOL_BYTES, pool.retainedBytes())

        pool.clear()
        assertEquals(0, pool.retainedBufferCount())
        assertEquals(0, pool.retainedBytes())
    }

    @Test
    fun `bounded in memory sort matches external radix output`() {
        val inMemoryDirectory = Files.createTempDirectory("canonical-sort-memory").toFile()
        val externalDirectory = Files.createTempDirectory("canonical-sort-external").toFile()
        try {
            val records = listOf(
                0L to intArrayOf(-4, 7, 2, 91),
                Long.MIN_VALUE to intArrayOf(12, -3, 8, 17),
                Long.MAX_VALUE to intArrayOf(-12, 4, -8, 3),
                11L to intArrayOf(0, 0, 0, 4),
                3L to intArrayOf(1, 2, 3, 4),
                -7L to intArrayOf(1, -2, 300, 5),
                7L to intArrayOf(-1, -2, -3, 6),
            )
            val kind = CowFragmentKind.VOXEL_INDEX
            val memoryWriter = CowSortedPageWriter(
                inMemoryDirectory,
                kind,
                null,
                CowSortedPageScratchPool(),
                inMemoryRecordLimit = CowSortedPageWriter.IN_MEMORY_RECORD_LIMIT,
            )
            val externalWriter = CowSortedPageWriter(
                externalDirectory,
                kind,
                null,
                CowSortedPageScratchPool(),
                inMemoryRecordLimit = 1,
            )
            records.forEach { (key, values) ->
                val write: (java.io.DataOutputStream) -> Unit = { output ->
                    output.writeInt(values[0]); output.writeInt(values[1]); output.writeInt(values[2]); output.writeLong(values[3].toLong())
                }
                assertTrue(memoryWriter.record(key, write))
                assertTrue(externalWriter.record(key, write))
            }

            assertTrue(memoryWriter.temporaryFiles().isEmpty())
            assertFalse(externalWriter.temporaryFiles().isEmpty())
            val memoryEntries = requireNotNull(memoryWriter.finish())
            val externalEntries = requireNotNull(externalWriter.finish())
            val memoryBytes = File(inMemoryDirectory, kind.file).readBytes()
            val externalBytes = File(externalDirectory, kind.file).readBytes()

            assertArrayEquals(externalBytes, memoryBytes)
            assertEquals(externalEntries.size, memoryEntries.size)
            externalEntries.zip(memoryEntries).forEach { (external, memory) ->
                assertEquals(external.kind, memory.kind)
                assertEquals(external.page, memory.page)
                assertEquals(external.minimumKey, memory.minimumKey)
                assertEquals(external.maximumKey, memory.maximumKey)
                assertEquals(external.offset, memory.offset)
                assertEquals(external.length, memory.length)
                assertEquals(external.count, memory.count)
                assertArrayEquals(external.hash, memory.hash)
            }
            assertTrue(inMemoryDirectory.listFiles()?.none { it.name.contains("sort-") } == true)
            assertTrue(externalDirectory.listFiles()?.none { it.name.contains("sort-") } == true)
        } finally {
            inMemoryDirectory.deleteRecursively()
            externalDirectory.deleteRecursively()
        }
    }
}
