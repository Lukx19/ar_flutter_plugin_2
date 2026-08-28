package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CanonicalStoreMigrationTest {
    @Test
    fun `independent v1 through v5 fixtures migrate with defined compatibility defaults`() {
        for (version in 1..5) {
            val directory = Files.createTempDirectory("m3-v$version").toFile()
            try {
                val group = M3SurfaceGroup("legacy-v$version")
                writeMinimalFixture(directory, group, version)
                val prepared =
                    M3CompactCanonicalStore.prepareV6SiblingMigration(
                        group,
                        directory,
                        acceptingBudget(),
                    ) as M3CompactCanonicalMigrationResult.Prepared
                val store =
                    (M3CompactCanonicalStore.openV6(group, directory)
                            as M3CompactCanonicalOpenResult.Opened)
                        .store
                assertEquals(1, prepared.cut.liveSurfaceCount)
                assertEquals(M3SurfaceId(1), store.findById(M3SurfaceId(1))!!.id)
                assertEquals(null, prepared.cut.seededEmptyBaseline)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `independent maximum v3 fixture preserves 300k fingerprints support and high UInt32 IDs`() {
        val directory = Files.createTempDirectory("m3-compact-maximum").toFile()
        try {
            val group = M3SurfaceGroup("compact-maximum")
            writeMaximumV3Fixture(directory, group)
            val prepared =
                M3CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    acceptingBudget(),
                ) as M3CompactCanonicalMigrationResult.Prepared
            val store =
                (M3CompactCanonicalStore.openV6(group, directory)
                        as M3CompactCanonicalOpenResult.Opened)
                    .store
            assertEquals(300_000, prepared.cut.sourceCount)
            listOf(0x7fff_ffffL, 0x8000_0000L, 0xffff_ffffL).forEach { id ->
                val read = store.readSourceById(M3SurfaceId(id)) as M3CanonicalPageRead.Complete
                assertEquals(id, read.value!!.id.value)
                assertEquals(32, read.value!!.allocationFingerprint.size)
                assertTrue(read.pageFaults in 0..1)
            }
            assertEquals(100_000, prepared.cut.liveSurfaceCount)
            assertEquals(200_000, prepared.cut.lineageCount)
            assertEquals(300_000, prepared.cut.supportCount)
            val support = mutableListOf<M3PagedSupport>()
            val read =
                store.visitSourceSupport(M3SurfaceId(0xffff_ffffL), null) {
                    support += it
                    true
                } as M3SourceSupportRead.Complete
            assertEquals(3, read.delivered)
            assertEquals(0xffff_ffffL, support.last().source.id.value)
            assertTrue(read.pageFaults in 0..1)
            assertTrue(read.bytesRead == 0 || read.bytesRead == 16_384)
            val memory = store.retainedMemoryReceipt()
            val storage = store.allocatedStorageReceipt()
            assertEquals(12_065_056L, memory.residentTotalBytes)
            assertEquals(12_130_592L, memory.peakWithScratchBytes)
            assertTrue(memory.withinIssue114Budget)
            assertTrue(memory.preservesIssue115Reserve)
            assertEquals(65_536L, memory.cachePayloadBytes)
            assertEquals(138_296L, memory.directoryColumnsBytes)
            assertEquals(38_404_096L, storage.pageBytes)
            assertTrue(storage.directoryBytes <= 1_048_576L)
            println("M3_MAX_MEMORY=$memory")
            println("M3_MAX_STORAGE=$storage allocated=${storage.allocatedBytes}")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `valid legacy authority deterministically prepares one non destructive sibling`() {
        val directory = Files.createTempDirectory("m3-compact-migrate").toFile()
        try {
            val group = M3SurfaceGroup("compact-migrate")
            val owner = opened(M3SurfaceOwnership.open(group, directory))
            accepted(
                owner.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0), candidate(1))))
            )
            owner.close()
            val prefix = sha256(group.value.encodeToByteArray()).hex()
            val legacySnapshot = directory.resolve("m3-surface-$prefix.snapshot").readBytes()
            val legacyLedger = directory.resolve("m3-surface-$prefix.ledger").readBytes()
            val firstResult =
                M3CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    acceptingBudget(),
                )
            assertTrue(
                firstResult.toString(),
                firstResult is M3CompactCanonicalMigrationResult.Prepared,
            )
            val first = firstResult as M3CompactCanonicalMigrationResult.Prepared
            val second =
                M3CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    acceptingBudget(),
                ) as M3CompactCanonicalMigrationResult.Prepared
            assertEquals(first.cut, second.cut)
            assertEquals(
                legacySnapshot.toList(),
                directory.resolve("m3-surface-$prefix.snapshot").readBytes().toList(),
            )
            assertEquals(
                legacyLedger.toList(),
                directory.resolve("m3-surface-$prefix.ledger").readBytes().toList(),
            )
            assertTrue(first.candidateDirectory.resolve("root.v6").isFile)
            assertTrue(first.candidateDirectory.resolve("directory.v6").isFile)
        } finally {
            directory.deleteRecursively()
        }
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
                val result =
                    M3CompactCanonicalStore.prepareV6SiblingMigration(
                        group,
                        directory,
                        acceptingBudget(),
                        fault = fault,
                    )
                assertEquals(
                    M3CompactCanonicalRefusal.DURABILITY_FAILURE,
                    (result as M3CompactCanonicalMigrationResult.Refused).reason,
                )
                assertEquals(
                    before.toList(),
                    directory.resolve("m3-surface-$prefix.snapshot").readBytes().toList(),
                )
                assertFalse(directory.listFiles().orEmpty().any { it.name.contains(".staging-") })
                val published =
                    fault == M3CompactCanonicalMigrationFault.AFTER_RENAME ||
                        fault == M3CompactCanonicalMigrationFault.AFTER_PARENT_SYNC
                assertEquals(
                    published,
                    directory.listFiles().orEmpty().any { it.name.startsWith("m3-canonical-v6-") },
                )
                if (published)
                    assertTrue(
                        M3CompactCanonicalStore.openV6(group, directory)
                            is M3CompactCanonicalOpenResult.Opened
                    )
                assertTrue(
                    M3SurfaceOwnership.open(group, directory) is M3SurfaceOwnershipOpenResult.Opened
                )
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun candidate(x: Int) =
        M3SurfaceCandidate(
            voxel = M3Voxel(x, 0, 0),
            normalOctX = 0,
            normalOctY = 0,
            normalConfidence = 192,
        )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun opened(result: M3SurfaceOwnershipOpenResult) =
        (result as M3SurfaceOwnershipOpenResult.Opened).ownership

    private fun accepted(result: M3SurfaceOwnershipResult) =
        result as M3SurfaceOwnershipResult.Accepted

    private fun acceptingBudget() =
        object : M3CanonicalStorageBudget {
            override fun reserve(bytes: Long): Any = bytes

            override fun commit(token: Any, actualBytes: Long) = Unit

            override fun release(token: Any) = Unit
        }

    /** A test-only v3 encoder independent of every v6 production encoder. */
    private fun writeMaximumV3Fixture(directory: File, group: M3SurfaceGroup) {
        val prefix = sha256(group.value.encodeToByteArray()).hex()
        val ledger = directory.resolve("m3-surface-$prefix.ledger")
        val reservationBody =
            java.io.ByteArrayOutputStream().use { raw ->
                DataOutputStream(raw).use { out ->
                    out.writeInt(0x4d33524c)
                    out.writeInt(1)
                    out.writeLong(1)
                    out.writeLong(1)
                    out.writeLong(0x1_0000_0000L)
                    out.write(sha256(group.value.encodeToByteArray()))
                    out.write(ByteArray(32) { 1 })
                    out.write(ByteArray(32) { 2 })
                    out.write(ByteArray(32))
                }
                raw.toByteArray()
            }
        ledger.writeBytes(reservationBody + sha256(reservationBody))

        val snapshot = directory.resolve("m3-surface-$prefix.snapshot")
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(snapshot).use { file ->
            val digestOutput = DigestOutputStream(file, digest)
            val out = DataOutputStream(digestOutput)
            out.writeInt(0x4d33534f)
            out.writeInt(3)
            out.writeLong(0x1_0000_0000L)
            out.writeInt(100_000)
            repeat(100_000) { index ->
                writeOwner(
                    out,
                    group,
                    maximumRowId(index),
                    index,
                    ByteArray(32) { byte -> (index + byte).toByte() },
                )
            }
            out.writeInt(0)
            out.writeLong(4)
            out.writeLong(3)
            out.writeInt(100_000)
            repeat(100_000) { targetIndex ->
                out.writeLong(maximumRowId(targetIndex))
                out.writeInt(3)
                repeat(3) { offset ->
                    out.writeLong(maximumSourceId(targetIndex * 3 + offset))
                }
            }
            out.writeInt(300_000)
            for (id in 1L..299_997L) writeSource(out, id)
            writeSource(out, 0x7fff_ffffL)
            writeSource(out, 0x8000_0000L)
            writeSource(out, 0xffff_ffffL)
            out.writeInt(200_000)
            repeat(100_000) { sourceIndex ->
                val source = maximumRowId(sourceIndex)
                out.writeLong(source)
                out.writeLong(1)
                out.writeLong(source)
                out.writeLong(2)
            }
            out.writeInt(0)
            out.flush()
            digestOutput.on(false)
            file.write(digest.digest())
        }
    }

    private fun writeMinimalFixture(directory: File, group: M3SurfaceGroup, version: Int) {
        val prefix = sha256(group.value.encodeToByteArray()).hex()
        writeLedger(directory.resolve("m3-surface-$prefix.ledger"), group, 2)
        writeSnapshot(directory.resolve("m3-surface-$prefix.snapshot")) { out ->
            out.writeInt(0x4d33534f)
            out.writeInt(version)
            out.writeLong(2)
            out.writeInt(1)
            writeOwner(out, group, 1, 0, ByteArray(32) { 7 })
            out.writeInt(0)
            if (version >= 2) {
                out.writeLong(1)
                out.writeLong(0)
                out.writeInt(1)
                out.writeLong(1)
                out.writeInt(1)
                out.writeLong(1)
                if (version >= 3) {
                    out.writeInt(1)
                    writeSource(out, 1)
                }
                out.writeInt(0)
                out.writeInt(0)
                if (version >= 5) out.writeBoolean(false)
            }
        }
    }

    private fun writeOwner(
        out: DataOutputStream,
        group: M3SurfaceGroup,
        id: Long,
        x: Int,
        allocatedBy: ByteArray,
    ) {
        out.writeLong(id)
        out.writeUTF(group.value)
        out.writeInt(x)
        out.writeInt(0)
        out.writeInt(0)
        out.writeInt(Math.floorDiv(x, 30))
        out.writeInt(0)
        out.writeInt(0)
        out.writeInt(Math.floorMod(x, 30) / 10)
        out.writeInt(0x1234)
        out.writeInt(197)
        out.write(allocatedBy)
    }

    private fun writeSource(out: DataOutputStream, id: Long) {
        out.writeLong(id)
        out.writeInt((id and 0x7fff).toInt())
        out.writeInt(-1)
        out.writeInt(2)
        out.writeInt(0x1234)
        out.writeInt(197)
        out.write(ByteArray(32) { index -> (id + index).toByte() })
    }

    private fun maximumRowId(index: Int): Long =
        when (index) {
            99_997 -> 0x7fff_ffffL
            99_998 -> 0x8000_0000L
            99_999 -> 0xffff_ffffL
            else -> index + 1L
        }

    private fun maximumSourceId(index: Int): Long =
        when (index) {
            299_997 -> 0x7fff_ffffL
            299_998 -> 0x8000_0000L
            299_999 -> 0xffff_ffffL
            else -> index + 1L
        }

    private fun writeLedger(file: File, group: M3SurfaceGroup, end: Long) {
        val body =
            java.io.ByteArrayOutputStream().use { raw ->
                DataOutputStream(raw).use { out ->
                    out.writeInt(0x4d33524c)
                    out.writeInt(1)
                    out.writeLong(1)
                    out.writeLong(1)
                    out.writeLong(end)
                    out.write(sha256(group.value.encodeToByteArray()))
                    out.write(ByteArray(32) { 1 })
                    out.write(ByteArray(32) { 2 })
                    out.write(ByteArray(32))
                }
                raw.toByteArray()
            }
        file.writeBytes(body + sha256(body))
    }

    private fun writeSnapshot(file: File, write: (DataOutputStream) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(file).use { output ->
            val digestOutput = DigestOutputStream(output, digest)
            val data = DataOutputStream(digestOutput)
            write(data)
            data.flush()
            digestOutput.on(false)
            output.write(digest.digest())
        }
    }
}
