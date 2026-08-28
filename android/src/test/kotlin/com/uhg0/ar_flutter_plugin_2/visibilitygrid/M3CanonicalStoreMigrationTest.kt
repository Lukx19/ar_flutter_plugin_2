package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openjdk.jol.info.GraphLayout

class M3CanonicalStoreMigrationTest {
    @Test
    fun `v3 embedded canonical receipt must exactly relate to its inline accepted result`() {
        listOf(false, true).forEach { corrupt ->
            val directory = Files.createTempDirectory("m3-v3-relation").toFile()
            try {
                val group = M3SurfaceGroup("v3-relation-$corrupt")
                writeV3ReceiptRelationFixture(directory, group, corrupt)
                val result = M3CompactCanonicalStore.prepareV6SiblingMigration(
                    group, directory, acceptingBudget(),
                )
                if (corrupt) assertEquals(
                    M3CompactCanonicalRefusal.CORRUPT,
                    (result as M3CompactCanonicalMigrationResult.Refused).reason,
                ) else assertTrue(result.toString(), result is M3CompactCanonicalMigrationResult.Prepared)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `real budget adapter commits authoritative allocated blocks including candidate directory`() {
        val directory = Files.createTempDirectory("m3-physical-budget").toFile()
        try {
            val group = M3SurfaceGroup("physical-budget")
            writeMinimalFixture(directory, group, 5)
            StorageBudgetCoordinatorV2(
                    directory,
                    StorageBudgetPolicyV2(64L * 1024 * 1024, 0),
                    JvmDescriptorFilesystemV2(),
                ) { 128L * 1024 * 1024 }
                .use { coordinator ->
                    val budget = M3CoordinatorStorageBudget(coordinator)
                    val prepared =
                        M3CompactCanonicalStore.prepareV6SiblingMigration(
                            group,
                            directory,
                            budget,
                        ) as M3CompactCanonicalMigrationResult.Prepared
                    val authoritative =
                        coordinator.physicallyAllocatedTreeBytes(prepared.candidateDirectory)
                    assertEquals(authoritative, prepared.storage.allocatedBytes)
                    assertEquals(authoritative, coordinator.committedBytes())
                    assertTrue(prepared.storage.filesystemBytes >= 0)
                    assertEquals(
                        prepared.storage,
                        ((M3CompactCanonicalStore.openV6(group, directory, budget)
                                    as M3CompactCanonicalOpenResult.Opened)
                                .store)
                            .allocatedStorageReceipt(),
                    )
                }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `independent unsorted v3 rows sources and support targets retain accepted semantics`() {
        val directory = Files.createTempDirectory("m3-unsorted-v3").toFile()
        try {
            val group = M3SurfaceGroup("unsorted-v3")
            writeUnsortedV3Fixture(directory, group)
            val prepared =
                M3CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    acceptingBudget(),
                ) as M3CompactCanonicalMigrationResult.Prepared
            assertEquals(2, prepared.cut.liveSurfaceCount)
            val store =
                (M3CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                        as M3CompactCanonicalOpenResult.Opened)
                    .store
            assertEquals(1L, store.findById(M3SurfaceId(1))!!.id.value)
            assertEquals(2L, store.findByVoxel(M3Voxel(2, 0, 0))!!.id.value)
            listOf(1L, 2L).forEach { id ->
                val source = store.readSourceById(M3SurfaceId(id)) as M3CanonicalPageRead.Complete
                assertEquals(id, source.value!!.id.value)
                val support = mutableListOf<M3PagedSupport>()
                val read = store.visitSourceSupport(M3SurfaceId(id), null) {
                    support += it
                    true
                } as M3SourceSupportRead.Complete
                assertEquals(1, read.delivered)
                assertEquals(id, support.single().source.id.value)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `duplicate legacy ownership and canonical command hashes are corrupt`() {
        listOf(true, false).forEach { ownership ->
            val directory = Files.createTempDirectory("m3-duplicate-receipt").toFile()
            try {
                val group = M3SurfaceGroup("duplicate-${if (ownership) "ownership" else "canonical"}")
                writeDuplicateReceiptV5Fixture(directory, group, ownership)
                val refused =
                    M3CompactCanonicalStore.prepareV6SiblingMigration(
                        group,
                        directory,
                        acceptingBudget(),
                    ) as M3CompactCanonicalMigrationResult.Refused
                assertEquals(M3CompactCanonicalRefusal.CORRUPT, refused.reason)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `migration retains durable ledger high water beyond an empty snapshot including UInt32 ceiling`() {
        listOf(10L, 0x1_0000_0000L).forEach { ledgerHigh ->
            val directory = Files.createTempDirectory("m3-ledger-high").toFile()
            try {
                val group = M3SurfaceGroup("ledger-high-$ledgerHigh")
                writeEmptyV5Fixture(directory, group, ledgerHigh)
                val prepared =
                    M3CompactCanonicalStore.prepareV6SiblingMigration(
                        group,
                        directory,
                        acceptingBudget(),
                    ) as M3CompactCanonicalMigrationResult.Prepared
                assertEquals(ledgerHigh, prepared.cut.nextSurfaceIdHighWater)
                assertEquals(0, prepared.cut.liveSurfaceCount)
                assertEquals(
                    ledgerHigh,
                    ((M3CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                                as M3CompactCanonicalOpenResult.Opened)
                            .store)
                        .cut
                        .nextSurfaceIdHighWater,
                )
            } finally {
                directory.deleteRecursively()
            }
        }
    }

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
                    (M3CompactCanonicalStore.openV6(group, directory, acceptingBudget())
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
                (M3CompactCanonicalStore.openV6(group, directory, acceptingBudget())
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
            assertEquals(
                maximumExpectedDigest().toList(),
                maximumPagesDigest(prepared.candidateDirectory).toList(),
            )
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
            listOf(1L, 257L, 513L, 769L).forEach {
                assertTrue(store.readSourceById(M3SurfaceId(it)) is M3CanonicalPageRead.Complete)
            }
            val kernel = M3FeatureFusionKernel()
            assertTrue(
                kernel.accept(M3FeatureFusionBatch(1, 1, emptyList()))
                    is M3FeatureFusionResult.Accepted
            )
            val memory = store.retainedMemoryReceipt()
            val storage = store.allocatedStorageReceipt()
            assertEquals(4, memory.cacheResidentPages)
            assertEquals(14_565_056L, memory.residentTotalBytes)
            assertEquals(14_630_592L, memory.peakWithScratchBytes)
            assertTrue(memory.withinIssue114Budget)
            assertTrue(memory.preservesIssue115Reserve)
            assertEquals(65_536L, memory.cachePayloadBytes)
            assertEquals(138_296L, memory.directoryColumnsBytes)
            assertTrue(storage.pageBytes >= 38_404_096L)
            assertTrue(storage.directoryBytes <= 1_048_576L)
            val constructedBytes = GraphLayout.parseInstance(kernel, store).totalSize()
            assertTrue(constructedBytes <= memory.residentTotalBytes)
            println("M3_MAX_MEMORY=$memory constructedKernelStoreCacheBytes=$constructedBytes owners=kernel,rowColumns,idOrder,voxelOrder,pageOrder,pageRanges,lineageColumns,directoryColumns,fourPageCache,cacheMetadata,storeScalars")
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
                        M3CompactCanonicalStore.openV6(group, directory, acceptingBudget())
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

    private fun writeEmptyV5Fixture(directory: File, group: M3SurfaceGroup, ledgerHigh: Long) {
        val prefix = sha256(group.value.encodeToByteArray()).hex()
        writeLedger(directory.resolve("m3-surface-$prefix.ledger"), group, ledgerHigh)
        writeSnapshot(directory.resolve("m3-surface-$prefix.snapshot")) { out ->
            out.writeInt(0x4d33534f)
            out.writeInt(5)
            out.writeLong(1) // Snapshot predates later durable burned reservations.
            out.writeInt(0) // rows
            out.writeInt(0) // ownership receipts
            out.writeLong(0) // geometry revision
            out.writeLong(0) // lineage revision
            out.writeInt(0) // support targets
            out.writeInt(0) // sources
            out.writeInt(0) // lineage edges
            out.writeInt(0) // canonical receipts
            out.writeBoolean(false)
        }
    }

    private fun writeUnsortedV3Fixture(directory: File, group: M3SurfaceGroup) {
        val prefix = sha256(group.value.encodeToByteArray()).hex()
        writeLedger(directory.resolve("m3-surface-$prefix.ledger"), group, 3)
        writeSnapshot(directory.resolve("m3-surface-$prefix.snapshot")) { out ->
            out.writeInt(0x4d33534f)
            out.writeInt(3)
            out.writeLong(3)
            out.writeInt(2)
            writeOwner(out, group, 2, 2, ByteArray(32) { 2 })
            writeOwner(out, group, 1, 1, ByteArray(32) { 1 })
            out.writeInt(0)
            out.writeLong(1)
            out.writeLong(1)
            out.writeInt(2)
            out.writeLong(2)
            out.writeInt(1)
            out.writeLong(2)
            out.writeLong(1)
            out.writeInt(1)
            out.writeLong(1)
            out.writeInt(2)
            writeSource(out, 2)
            writeSource(out, 1)
            out.writeInt(0)
            out.writeInt(0)
        }
    }

    private fun writeDuplicateReceiptV5Fixture(
        directory: File,
        group: M3SurfaceGroup,
        ownership: Boolean,
    ) {
        val prefix = sha256(group.value.encodeToByteArray()).hex()
        writeLedger(directory.resolve("m3-surface-$prefix.ledger"), group, 1)
        val canonical =
            java.io.ByteArrayOutputStream().use { raw ->
                DataOutputStream(raw).use { out ->
                    out.writeInt(0x4d334352)
                    out.writeInt(1)
                    out.writeUTF(group.value)
                    out.writeUTF("accepted-empty")
                    out.writeInt(M3CanonicalOperation.RELOCATION.ordinal)
                    out.writeLong(0)
                    out.writeLong(0)
                    out.writeLong(1)
                    out.writeInt(0)
                    repeat(4) { out.writeInt(0) }
                }
                raw.toByteArray()
            }
        val repeatedHash = ByteArray(32) { 0x55 }
        writeSnapshot(directory.resolve("m3-surface-$prefix.snapshot")) { out ->
            out.writeInt(0x4d33534f)
            out.writeInt(5)
            out.writeLong(1)
            out.writeInt(0)
            out.writeInt(if (ownership) 2 else 0)
            if (ownership) repeat(2) {
                out.write(repeatedHash)
                out.write(ByteArray(32) { 0x33 })
                out.writeInt(0)
                out.writeLong(1)
                out.writeInt(0)
                out.writeInt(0)
                out.writeInt(0)
            }
            out.writeLong(0)
            out.writeLong(0)
            out.writeInt(0)
            out.writeInt(0)
            out.writeInt(0)
            out.writeInt(if (ownership) 0 else 2)
            if (!ownership) repeat(2) {
                out.write(repeatedHash)
                out.write(ByteArray(32) { 0x44 })
                out.writeInt(canonical.size)
                out.write(canonical)
            }
            out.writeBoolean(false)
        }
    }

    private fun writeV3ReceiptRelationFixture(
        directory: File,
        group: M3SurfaceGroup,
        corrupt: Boolean,
    ) {
        val prefix = sha256(group.value.encodeToByteArray()).hex()
        writeLedger(directory.resolve("m3-surface-$prefix.ledger"), group, 1)
        val canonical = emptyCanonicalReceipt(group, if (corrupt) "different" else "accepted-empty")
        writeSnapshot(directory.resolve("m3-surface-$prefix.snapshot")) { out ->
            out.writeInt(0x4d33534f); out.writeInt(3); out.writeLong(1); out.writeInt(0)
            out.writeInt(0); out.writeLong(0); out.writeLong(0)
            out.writeInt(0); out.writeInt(0); out.writeInt(0)
            out.writeInt(1)
            out.write(ByteArray(32) { 0x11 }); out.write(ByteArray(32) { 0x22 })
            out.writeUTF("accepted-empty"); out.writeInt(M3CanonicalOperation.RELOCATION.ordinal)
            out.writeInt(0); out.writeInt(0); out.writeInt(0)
            out.writeLong(0); out.writeLong(0); out.writeLong(1); out.writeInt(0)
            out.writeInt(0); out.writeInt(canonical.size); out.write(canonical)
        }
    }

    private fun emptyCanonicalReceipt(group: M3SurfaceGroup, command: String) =
        java.io.ByteArrayOutputStream().use { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(0x4d334352); out.writeInt(1); out.writeUTF(group.value)
                out.writeUTF(command); out.writeInt(M3CanonicalOperation.RELOCATION.ordinal)
                out.writeLong(0); out.writeLong(0); out.writeLong(1); out.writeInt(0)
                repeat(4) { out.writeInt(0) }
            }
            raw.toByteArray()
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

    private fun maximumExpectedDigest(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), digest)).use { out ->
            repeat(300_000) { index ->
                val id = maximumSourceId(index)
                writeDigestRecord(
                    out, 1, 0, id, (id and 0x7fff).toInt(), -1, 2, 0x1234, 197,
                    ByteArray(32) { byte -> (id + byte).toByte() },
                )
            }
            repeat(100_000) { targetIndex ->
                val target = maximumRowId(targetIndex)
                repeat(3) { offset ->
                    val id = maximumSourceId(targetIndex * 3 + offset)
                    writeDigestRecord(
                        out, 2, target, id, (id and 0x7fff).toInt(), -1, 2, 0x1234, 197,
                        ByteArray(32) { byte -> (id + byte).toByte() },
                    )
                }
            }
        }
        return digest.digest()
    }

    /** Reads raw fixed pages without using the production page decoder. */
    private fun maximumPagesDigest(candidate: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), digest)).use { out ->
            FileInputStream(candidate.resolve("sources.v6.pages")).use { file ->
                val bytes = ByteArray(16_384)
                while (file.readNBytes(bytes, 0, bytes.size) == bytes.size) {
                    DataInputStream(ByteArrayInputStream(bytes)).use { page ->
                        assertEquals(0x4d335047, page.readInt())
                        assertEquals(1, page.readInt())
                        val kind = page.readUnsignedByte()
                        page.readInt() // ordinal
                        repeat(page.readUnsignedShort()) {
                            val target = if (kind == 2) Integer.toUnsignedLong(page.readInt()) else 0L
                            val id = Integer.toUnsignedLong(page.readInt())
                            val x = page.readInt()
                            val y = page.readInt()
                            val z = page.readInt()
                            val normal = page.readUnsignedShort()
                            val confidence = page.readUnsignedByte()
                            val fingerprint = ByteArray(32).also(page::readFully)
                            writeDigestRecord(
                                out, kind, target, id, x, y, z, normal, confidence, fingerprint,
                            )
                        }
                    }
                }
            }
        }
        return digest.digest()
    }

    private fun writeDigestRecord(
        out: DataOutputStream,
        kind: Int,
        target: Long,
        id: Long,
        x: Int,
        y: Int,
        z: Int,
        normal: Int,
        confidence: Int,
        fingerprint: ByteArray,
    ) {
        out.writeByte(kind)
        out.writeLong(target)
        out.writeLong(id)
        out.writeInt(x)
        out.writeInt(y)
        out.writeInt(z)
        out.writeInt(normal)
        out.writeInt(confidence)
        out.write(fingerprint)
    }

    private fun writeLedger(file: File, group: M3SurfaceGroup, end: Long) {
        if (end == 1L) {
            assertTrue(!file.exists())
            return
        }
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
