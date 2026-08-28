package com.uhg0.ar_flutter_plugin_2.m0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class M0cShardCampaignTest {
    @Test
    fun `schema five codecs process the fixed maximum surface populations`() {
        val canonical = M0RegionShardV5.encodeCanonical(
            region = M0RegionCoordinate(-1, 2, -3),
            captureEvaluatedThrough = 2,
            pendingThrough = 1,
            surfaceRows = List(100_000) { index -> row(19, index) },
            lineageRows = emptyList(),
        )
        assertEquals(100_000, M0RegionShardV5.decode(canonical).surfaceRows.size)
        assertTrue(canonical.size < M0RegionShardV5.maximumBytes)

        val coverage = M0RegionShardV5.encodeCoverage(
            region = M0RegionCoordinate(-1, 2, -3),
            captureEvaluatedThrough = 2,
            pendingThrough = 1,
            surfaceRows = List(100_000) { index -> row(56, index) },
            overflowRows = emptyList(),
        )
        assertEquals(100_000, M0RegionShardV5.decode(coverage).surfaceRows.size)
        assertTrue(coverage.size < M0RegionShardV5.maximumBytes)
    }

    @Test
    fun `schema five deterministic payload mutations fail before row exposure`() {
        val packet = M0RegionShardV5.encodeCoverage(
            region = M0RegionCoordinate(1, 2, 3),
            captureEvaluatedThrough = 2,
            pendingThrough = 1,
            surfaceRows = listOf(row(56, 7)),
            overflowRows = listOf(row(13, 11)),
            compression = M0RegionShardV5.Compression.ZLIB,
        )
        repeat(256) { seed ->
            val malformed = packet.copyOf()
            val index = M0RegionShardV5.headerBytes + seed % (malformed.size - M0RegionShardV5.headerBytes)
            malformed[index] = (malformed[index].toInt() xor (1 shl (seed % 8))).toByte()
            assertThrows("seed=$seed", IllegalArgumentException::class.java) {
                M0RegionShardV5.decode(malformed)
            }
        }
    }

    @Test
    fun `schema five rejects maximum plus one before decompression allocation`() {
        assertThrows(IllegalArgumentException::class.java) {
            M0RegionShardV5.decode(ByteArray(M0RegionShardV5.maximumBytes + 1))
        }
    }

    @Test
    fun `coverage encoder rejects overflow rows beyond the fixed per surface bound`() {
        assertThrows(IllegalArgumentException::class.java) {
            M0RegionShardV5.encodeCoverage(
                region = M0RegionCoordinate(0, 0, 0),
                captureEvaluatedThrough = 2,
                pendingThrough = 1,
                surfaceRows = listOf(row(56, 1)),
                overflowRows = List(25) { row(13, it) },
            )
        }
    }

    private fun row(width: Int, seed: Int): ByteArray =
        ByteArray(width) { index -> (seed * 31 + index * 17).toByte() }
}
