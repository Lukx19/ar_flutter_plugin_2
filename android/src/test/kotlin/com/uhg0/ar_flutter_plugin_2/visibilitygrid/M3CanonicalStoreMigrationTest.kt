package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CanonicalStoreMigrationTest {
    @Test
    fun `valid legacy authority deterministically prepares one non destructive sibling`() {
        val directory = Files.createTempDirectory("m3-compact-migrate").toFile()
        try {
            val group = M3SurfaceGroup("compact-migrate")
            val owner = opened(M3SurfaceOwnership.open(group, directory))
            accepted(owner.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0), candidate(1)))))
            owner.close()
            val prefix = sha256(group.value.encodeToByteArray()).hex()
            val legacySnapshot = directory.resolve("m3-surface-$prefix.snapshot").readBytes()
            val legacyLedger = directory.resolve("m3-surface-$prefix.ledger").readBytes()
            val first = M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory) as M3CompactCanonicalMigrationResult.Prepared
            val second = M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory) as M3CompactCanonicalMigrationResult.Prepared
            assertEquals(first.cut, second.cut)
            assertEquals(legacySnapshot.toList(), directory.resolve("m3-surface-$prefix.snapshot").readBytes().toList())
            assertEquals(legacyLedger.toList(), directory.resolve("m3-surface-$prefix.ledger").readBytes().toList())
            assertTrue(first.candidateDirectory.resolve("canonical.v6").isFile)
            assertTrue(first.candidateDirectory.resolve("directory.v6").isFile)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `migration faults clean only staging and retain legacy authority`() {
        M3CompactCanonicalMigrationFault.entries.forEach { fault ->
            val directory = Files.createTempDirectory("m3-compact-fault").toFile()
            try {
                val group = M3SurfaceGroup("compact-fault-$fault")
                val owner = opened(M3SurfaceOwnership.open(group, directory))
                accepted(owner.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0)))))
                owner.close()
                val prefix = sha256(group.value.encodeToByteArray()).hex()
                val before = directory.resolve("m3-surface-$prefix.snapshot").readBytes()
                val result = M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, fault = fault)
                assertEquals(M3CompactCanonicalRefusal.DURABILITY_FAILURE, (result as M3CompactCanonicalMigrationResult.Refused).reason)
                assertEquals(before.toList(), directory.resolve("m3-surface-$prefix.snapshot").readBytes().toList())
                assertFalse(directory.listFiles().orEmpty().any { it.name.contains(".staging-") || it.name.startsWith("m3-canonical-v6-") })
                assertTrue(M3SurfaceOwnership.open(group, directory) is M3SurfaceOwnershipOpenResult.Opened)
            } finally { directory.deleteRecursively() }
        }
    }

    private fun candidate(x: Int) = M3SurfaceCandidate(voxel = M3Voxel(x, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun opened(result: M3SurfaceOwnershipOpenResult) = (result as M3SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: M3SurfaceOwnershipResult) = result as M3SurfaceOwnershipResult.Accepted
}
