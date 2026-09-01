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

class CanonicalStoreMigrationTest {
    @Test
    fun `v3 embedded canonical receipt must exactly relate to its inline accepted result`() {
        listOf(false, true).forEach { corrupt ->
            val directory = Files.createTempDirectory("canonical-surface-v3-relation").toFile()
            try {
                val group = SurfaceGroup("v3-relation-$corrupt")
                writeV3ReceiptRelationFixture(directory, group, corrupt)
                val result = CompactCanonicalStore.prepareV6SiblingMigration(
                    group, directory, acceptingBudget(),
                )
                if (corrupt) assertEquals(
                    CompactCanonicalRefusal.CORRUPT,
                    (result as CompactCanonicalMigrationResult.Refused).reason,
                ) else assertTrue(result.toString(), result is CompactCanonicalMigrationResult.Prepared)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `independent v2 reconstructed journal accepts exact configured maximum and rejects one byte less`() {
        val command = "v2-" + "x".repeat(60_000)
        val group = SurfaceGroup("v2-journal-boundary")
        val canonicalBytes = 8 + modifiedUtfSize(group.value) + modifiedUtfSize(command) +
            4 + 3 * 8 + 4 + 4 + 4 + 4 + 4
        val exactJournal = 68 + canonicalBytes
        listOf(exactJournal to true, exactJournal - 1 to false).forEach { (capacity, accepted) ->
            val directory = Files.createTempDirectory("canonical-surface-v2-journal-$accepted").toFile()
            try {
                writeV2JournalFixture(directory, group, command)
                val configuration = SurfaceOwnershipConfiguration(changeJournalByteCapacity = capacity)
                val result = CompactCanonicalStore.prepareV6SiblingMigration(
                    group, directory, acceptingBudget(), configuration,
                )
                if (accepted) assertTrue(result is CompactCanonicalMigrationResult.Prepared)
                else assertEquals(
                    CompactCanonicalRefusal.CORRUPT,
                    (result as CompactCanonicalMigrationResult.Refused).reason,
                )
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `real budget adapter commits authoritative allocated blocks including candidate directory`() {
        val directory = Files.createTempDirectory("canonical-surface-physical-budget").toFile()
        try {
            val group = SurfaceGroup("physical-budget")
            writeMinimalFixture(directory, group, 5)
            StorageBudgetCoordinatorV2(
                    directory,
                    StorageBudgetPolicyV2(64L * 1024 * 1024, 0),
                    JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
                ) { 128L * 1024 * 1024 }
                .use { coordinator ->
                    val budget = CoordinatorStorageBudget(coordinator)
                    val result = CompactCanonicalStore.prepareV6SiblingMigration(
                            group,
                            directory,
                            budget,
                        )
                    assertTrue(result.toString(), result is CompactCanonicalMigrationResult.Prepared)
                    val prepared = result as CompactCanonicalMigrationResult.Prepared
                    val authoritative =
                        coordinator.physicallyAllocatedTreeBytes(prepared.candidateDirectory)
                    assertEquals(authoritative, prepared.storage.allocatedBytes)
                    assertEquals(authoritative, coordinator.committedBytes())
                    assertTrue(prepared.storage.filesystemBytes >= 0)
                    assertEquals(
                        prepared.storage,
                        ((CompactCanonicalStore.openV6(group, directory, budget)
                                    as CompactCanonicalOpenResult.Opened)
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
        val directory = Files.createTempDirectory("canonical-surface-unsorted-v3").toFile()
        try {
            val group = SurfaceGroup("unsorted-v3")
            writeUnsortedV3Fixture(directory, group)
            val prepared =
                CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    acceptingBudget(),
                ) as CompactCanonicalMigrationResult.Prepared
            assertEquals(2, prepared.cut.liveSurfaceCount)
            val indexReceipt = requireNotNull(prepared.sourceIndex)
            assertEquals(2, indexReceipt.records)
            assertEquals(8L, indexReceipt.retainedBytes)
            assertEquals(4L, indexReceipt.lookups)
            assertTrue(indexReceipt.maximumLookupIdReads <= 2)
            val store =
                (CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                        as CompactCanonicalOpenResult.Opened)
                    .store
            assertEquals(1L, store.findById(SurfaceId(1))!!.id.value)
            assertEquals(2L, store.findByVoxel(Voxel(2, 0, 0))!!.id.value)
            listOf(1L, 2L).forEach { id ->
                val source = store.readSourceById(SurfaceId(id)) as CanonicalPageRead.Complete
                assertEquals(id, source.value!!.id.value)
                val support = mutableListOf<PagedSupport>()
                val read = store.visitSourceSupport(SurfaceId(id), null) {
                    support += it
                    true
                } as SourceSupportRead.Complete
                assertEquals(1, read.delivered)
                assertEquals(id, support.single().source.id.value)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `independent unsorted v1 rows emit unsigned id ordered sources and support`() {
        val directory = Files.createTempDirectory("canonical-surface-unsorted-v1").toFile()
        try {
            val group = SurfaceGroup("unsorted-v1")
            writeUnsortedV1Fixture(directory, group)
            val prepared = CompactCanonicalStore.prepareV6SiblingMigration(
                group, directory, acceptingBudget(),
            ) as CompactCanonicalMigrationResult.Prepared
            val store = (CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                as CompactCanonicalOpenResult.Opened).store
            listOf(1L, 2L).forEach { id ->
                val support = mutableListOf<PagedSupport>()
                val read = store.visitSourceSupport(SurfaceId(id), null) {
                    support += it; true
                } as SourceSupportRead.Complete
                assertEquals(1, read.delivered)
                assertEquals(id, support.single().source.id.value)
                assertEquals((id + 7).toByte(), support.single().source.allocationFingerprint.toByteArray()[7])
            }
            assertEquals(2, prepared.cut.sourceCount)
            store.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `duplicate ownership hashes are corrupt while canonical duplicates remain readable`() {
        listOf(true, false).forEach { ownership ->
            val directory = Files.createTempDirectory("canonical-surface-duplicate-receipt").toFile()
            try {
                val group = SurfaceGroup("duplicate-${if (ownership) "ownership" else "canonical"}")
                writeDuplicateReceiptV5Fixture(directory, group, ownership)
                val result = CompactCanonicalStore.prepareV6SiblingMigration(
                    group, directory, acceptingBudget(),
                )
                if (ownership) assertEquals(
                    CompactCanonicalRefusal.CORRUPT,
                    (result as CompactCanonicalMigrationResult.Refused).reason,
                ) else assertTrue(result is CompactCanonicalMigrationResult.Prepared)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `migration retains durable ledger high water beyond an empty snapshot including UInt32 ceiling`() {
        listOf(10L, 0x1_0000_0000L).forEach { ledgerHigh ->
            val directory = Files.createTempDirectory("canonical-surface-ledger-high").toFile()
            try {
                val group = SurfaceGroup("ledger-high-$ledgerHigh")
                writeEmptyV5Fixture(directory, group, ledgerHigh)
                val prepared =
                    CompactCanonicalStore.prepareV6SiblingMigration(
                        group,
                        directory,
                        acceptingBudget(),
                    ) as CompactCanonicalMigrationResult.Prepared
                assertEquals(ledgerHigh, prepared.cut.nextSurfaceIdHighWater)
                assertEquals(0, prepared.cut.liveSurfaceCount)
                assertEquals(
                    ledgerHigh,
                    ((CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                                as CompactCanonicalOpenResult.Opened)
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
    fun `missing snapshot migrates durable burned reservations without id reuse`() {
        listOf(10L, 0x1_0000_0000L).forEach { ledgerHigh ->
            val directory = Files.createTempDirectory("canonical-surface-ledger-only").toFile()
            try {
                val group = SurfaceGroup("ledger-only-$ledgerHigh")
                val prefix = testSha256(group.value.encodeToByteArray()).toLowerHex()
                writeLedger(directory.resolve("canonical-surface-surface-$prefix.ledger"), group, ledgerHigh)
                val prepared = CompactCanonicalStore.prepareV6SiblingMigration(
                    group, directory, acceptingBudget(),
                ) as CompactCanonicalMigrationResult.Prepared
                assertEquals(ledgerHigh, prepared.cut.nextSurfaceIdHighWater)
                assertEquals(0, prepared.cut.liveSurfaceCount)
                val store = (CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                    as CompactCanonicalOpenResult.Opened).store
                assertEquals(ledgerHigh, store.cut.nextSurfaceIdHighWater)
                assertEquals(null, store.findById(SurfaceId(1)))
                store.close()
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `independent v1 through v5 fixtures migrate with defined compatibility defaults`() {
        for (version in 1..5) {
            val directory = Files.createTempDirectory("canonical-surface-v$version").toFile()
            try {
                val group = SurfaceGroup("legacy-v$version")
                writeMinimalFixture(directory, group, version)
                val prepared =
                    CompactCanonicalStore.prepareV6SiblingMigration(
                        group,
                        directory,
                        acceptingBudget(),
                    ) as CompactCanonicalMigrationResult.Prepared
                val store =
                    (CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                            as CompactCanonicalOpenResult.Opened)
                        .store
                assertEquals(1, prepared.cut.liveSurfaceCount)
                assertEquals(SurfaceId(1), store.findById(SurfaceId(1))!!.id)
                assertEquals(null, prepared.cut.seededEmptyBaseline)
                assertGenuinePreparedIntentVisitor(store, directory, "migrated-small")
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `independent maximum v3 fixture preserves 300k fingerprints support and high UInt32 IDs`() {
        val directory = Files.createTempDirectory("canonical-surface-compact-maximum").toFile()
        try {
            val group = SurfaceGroup("compact-maximum")
            writeMaximumV3Fixture(directory, group)
            val migrationBytes = measureMigrationGraph(group, directory)
            assertTrue("migration graph bytes=$migrationBytes", migrationBytes <= 15_728_640L)
            println("CANONICAL_SURFACE_MAX_MIGRATION_MEMORY=constructedBytes=$migrationBytes owners=kernel,legacyResidentColumns,idOrder,voxelOrder,pageOrder,rowOffsets,supportOffsets,lineageColumns,sourceAndSupportCursorClosures,maxDirectoryColumns,256SourcePageObjects,16384PageBuffer,65536CodecScratch")
            val prepared =
                CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    acceptingBudget(),
                ) as CompactCanonicalMigrationResult.Prepared
            val store =
                (CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                        as CompactCanonicalOpenResult.Opened)
                    .store
            assertEquals(300_000, prepared.cut.sourceCount)
            listOf(0x7fff_ffffL, 0x8000_0000L, 0xffff_ffffL).forEach { id ->
                val read = store.readSourceById(SurfaceId(id)) as CanonicalPageRead.Complete
                assertEquals(id, read.value!!.id.value)
                assertEquals(32, read.value!!.allocationFingerprint.size)
                assertTrue(read.pageFaults in 0..1)
            }
            assertEquals(100_000, prepared.cut.liveSurfaceCount)
            assertGenuinePreparedIntentVisitor(store, directory, "migrated-maximum")
            assertEquals(200_000, prepared.cut.lineageCount)
            assertEquals(300_000, prepared.cut.supportCount)
            assertEquals(
                maximumExpectedDigest().toList(),
                maximumPagesDigest(prepared.candidateDirectory).toList(),
            )
            val support = mutableListOf<PagedSupport>()
            val read =
                store.visitSourceSupport(SurfaceId(0xffff_ffffL), null) {
                    support += it
                    true
                } as SourceSupportRead.Complete
            assertEquals(3, read.delivered)
            assertEquals(0xffff_ffffL, support.last().source.id.value)
            assertTrue(read.pageFaults in 0..1)
            assertTrue(read.bytesRead == 0 || read.bytesRead == 16_384)
            listOf(1L, 257L, 513L, 769L).forEach {
                assertTrue(store.readSourceById(SurfaceId(it)) is CanonicalPageRead.Complete)
            }
            val kernel = FeatureFusionKernel()
            assertTrue(
                kernel.accept(FeatureFusionBatch(1, 1, emptyList()))
                    is FeatureFusionResult.Accepted
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
            println("CANONICAL_SURFACE_MAX_MEMORY=$memory constructedKernelStoreCacheBytes=$constructedBytes owners=kernel,rowColumns,idOrder,voxelOrder,pageOrder,pageRanges,lineageColumns,directoryColumns,fourPageCache,cacheMetadata,storeScalars")
            println("CANONICAL_SURFACE_MAX_STORAGE=$storage allocated=${storage.allocatedBytes}")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `maximum unsorted v5 sources use bounded radix index for all 300k support joins`() {
        val directory = Files.createTempDirectory("canonical-surface-compact-maximum-unsorted").toFile()
        try {
            val group = SurfaceGroup("compact-maximum-unsorted")
            writeMaximumV3Fixture(directory, group, version = 5, unsortedSources = true)
            val migrationBytes = measureMigrationGraph(group, directory)
            assertTrue("unsorted migration graph bytes=$migrationBytes", migrationBytes <= 15_728_640L)
            val prepared = CompactCanonicalStore.prepareV6SiblingMigration(
                group, directory, acceptingBudget(),
            ) as CompactCanonicalMigrationResult.Prepared
            val index = requireNotNull(prepared.sourceIndex)
            assertEquals(300_000, index.records)
            assertEquals(1_200_000L, index.retainedBytes)
            assertEquals(600_000L, index.lookups)
            assertTrue(index.buildIdReads <= 2_700_000L)
            assertTrue(index.lookupIdReads <= 11_400_000L)
            assertTrue(index.maximumLookupIdReads <= 19)
            assertEquals(300_000, prepared.cut.sourceCount)
            assertEquals(300_000, prepared.cut.supportCount)
            assertEquals(200_000, prepared.cut.lineageCount)
            assertEquals(
                maximumExpectedDigest().toList(),
                maximumPagesDigest(prepared.candidateDirectory).toList(),
            )
            val store = (CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                as CompactCanonicalOpenResult.Opened).store
            listOf(1L, 150_000L, 0x7fff_ffffL, 0x8000_0000L, 0xffff_ffffL).forEach { id ->
                val source = store.readSourceById(SurfaceId(id)) as CanonicalPageRead.Complete
                assertEquals(id, source.value!!.id.value)
            }
            store.close()
            println("CANONICAL_SURFACE_MAX_UNSORTED_MIGRATION_MEMORY=constructedBytes=$migrationBytes sourceIndex=$index owners=kernel,legacyColumns,sourceOrdinalRadixIndex,directoryColumns,pageObjects,pageBuffer,codecScratch")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `valid legacy authority deterministically prepares one non destructive sibling`() {
        val directory = Files.createTempDirectory("canonical-surface-compact-migrate").toFile()
        try {
            val group = SurfaceGroup("compact-migrate")
            val owner = opened(SurfaceOwnership.open(group, directory))
            accepted(
                owner.apply(SurfaceOwnershipCommand("seed", listOf(candidate(0), candidate(1))))
            )
            owner.close()
            val prefix = testSha256(group.value.encodeToByteArray()).toLowerHex()
            val legacySnapshot = directory.resolve("canonical-surface-surface-$prefix.snapshot").readBytes()
            val legacyLedger = directory.resolve("canonical-surface-surface-$prefix.ledger").readBytes()
            val firstResult =
                CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    acceptingBudget(),
                )
            assertTrue(
                firstResult.toString(),
                firstResult is CompactCanonicalMigrationResult.Prepared,
            )
            val first = firstResult as CompactCanonicalMigrationResult.Prepared
            val second =
                CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    acceptingBudget(),
                ) as CompactCanonicalMigrationResult.Prepared
            assertEquals(first.cut, second.cut)
            assertEquals(
                legacySnapshot.toList(),
                directory.resolve("canonical-surface-surface-$prefix.snapshot").readBytes().toList(),
            )
            assertEquals(
                legacyLedger.toList(),
                directory.resolve("canonical-surface-surface-$prefix.ledger").readBytes().toList(),
            )
            assertTrue(first.candidateDirectory.resolve("root.v6").isFile)
            assertTrue(first.candidateDirectory.resolve("directory.v6").isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `migration faults clean only staging and retain legacy authority`() {
        CompactCanonicalMigrationFault.entries.forEach { fault ->
            val directory = Files.createTempDirectory("canonical-surface-compact-fault").toFile()
            try {
                val group = SurfaceGroup("compact-fault-$fault")
                val owner = opened(SurfaceOwnership.open(group, directory))
                accepted(owner.apply(SurfaceOwnershipCommand("seed", listOf(candidate(0)))))
                owner.close()
                val prefix = testSha256(group.value.encodeToByteArray()).toLowerHex()
                val before = directory.resolve("canonical-surface-surface-$prefix.snapshot").readBytes()
                val coordinator = StorageBudgetCoordinatorV2(
                    directory,
                    StorageBudgetPolicyV2(64L * 1024 * 1024, 0),
                    JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
                ) { 128L * 1024 * 1024 }
                val budget = CoordinatorStorageBudget(coordinator)
                val result =
                    CompactCanonicalStore.prepareV6SiblingMigration(
                        group,
                        directory,
                        budget,
                        fault = fault,
                    )
                assertEquals(
                    CompactCanonicalRefusal.DURABILITY_FAILURE,
                    (result as CompactCanonicalMigrationResult.Refused).reason,
                )
                assertEquals(
                    before.toList(),
                    directory.resolve("canonical-surface-surface-$prefix.snapshot").readBytes().toList(),
                )
                assertFalse(directory.listFiles().orEmpty().any { it.name.contains(".staging-") })
                val published =
                    fault == CompactCanonicalMigrationFault.AFTER_RENAME ||
                        fault == CompactCanonicalMigrationFault.AFTER_PARENT_SYNC
                assertTrue(
                    directory.resolve("reservations-v2").listFiles().orEmpty()
                        .none { it.name.endsWith(".allocation") }
                )
                assertEquals(0L, coordinator.reservedBytes())
                assertEquals(
                    published,
                    directory.listFiles().orEmpty().any { it.name.startsWith("canonical-surface-canonical-v6-") },
                )
                if (published)
                    assertTrue(
                        coordinator.committedBytes() ==
                            coordinator.physicallyAllocatedTreeBytes(
                                directory.listFiles().single { it.name.startsWith("canonical-surface-canonical-v6-") }
                            ) && CompactCanonicalStore.openV6(group, directory, budget)
                            is CompactCanonicalOpenResult.Opened
                    )
                else assertEquals(0L, coordinator.committedBytes())
                coordinator.close()
                assertTrue(
                    SurfaceOwnership.open(group, directory) is SurfaceOwnershipOpenResult.Opened
                )
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun candidate(x: Int) =
        SurfaceCandidate(
            voxel = Voxel(x, 0, 0),
            normalOctX = 0,
            normalOctY = 0,
            normalConfidence = 192,
        )

    /** #121 consumes the real #114 reader; this deliberately avoids a synthetic state view. */
    private fun assertGenuinePreparedIntentVisitor(store: CanonicalStateView, directory: File, command: String) {
        val row = requireNotNull(store.findById(SurfaceId(1)))
        val plan = SurfaceOwnership.prepareMutation(
            store,
            SurfaceOwnershipConfiguration(),
            FeatureMutationCommand(command, store.cut.geometryRevision, store.cut.lineageRevision,
                CanonicalTarget(row.id, row.voxel, 1, 0, 191)),
        ) as CanonicalMutationPreparation.Prepared
        val journal = (CanonicalDirtyJournal.open(store, directory, acceptingBudget()) as CanonicalDirtyJournalOpenResult.Opened).journal
        val intent = (journal.flush(plan.mutation) as CanonicalDirtyJournalFlushResult.Prepared).intent
        val expectedWal = java.io.ByteArrayOutputStream().also(plan.mutation::writeWalTo).toByteArray()
        val expectedCurrent = java.io.ByteArrayOutputStream().also(plan.mutation::writeCurrentTo).toByteArray()
        val visitor = object : PreparedIntentVisitor {
            var rows = 0; var terminal = 0
            override fun onHeader(identity: PreparedIntentIdentity) = true
            override fun onDirtyRow(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) = true.also { rows++ }
            override fun onRemovedId(id: Long) = true
            override fun onDirtySupport(targetId: Long, sourceId: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) = true
            override fun onDirtySource(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) = true
            override fun onDirtyLineage(sourceId: Long, targetId: Long) = true
            override fun onTerminal(currentReceipt: PreparedIntentCurrentReceipt) = true.also { terminal++ }
        }
        val before = store.readWorkReceipt()
        val visited = intent.visit(visitor) as PreparedIntentVisitResult.Complete
        val work = store.readWorkReceipt() - before
        val identity = (intent.identity() as PreparedIntentIdentityResult.Complete).identity
        assertEquals(1, visitor.rows); assertEquals(1, visitor.terminal)
        assertEquals(expectedWal.size.toLong(), plan.mutation.work.walBytes.toLong())
        assertEquals(expectedWal.size.toLong(), identity.walReceipt.length)
        assertEquals(expectedCurrent.size.toLong(), plan.mutation.work.currentBytes.toLong())
        assertEquals(expectedCurrent.size.toLong(), identity.expectedCurrentReceipt.length)
        assertEquals(identity.expectedCurrentReceipt.length, visited.currentReceipt.length)
        assertEquals(CanonicalReceiptBytes(testSha256(expectedWal)), identity.walReceipt.hash)
        assertEquals(CanonicalReceiptBytes(testSha256(expectedCurrent)), identity.expectedCurrentReceipt.hash)
        assertEquals(identity.expectedCurrentReceipt.hash, visited.currentReceipt.hash)
        assertEquals(CanonicalReadWork.ZERO, work)
    }

    

    

    private fun opened(result: SurfaceOwnershipOpenResult) =
        (result as SurfaceOwnershipOpenResult.Opened).ownership

    private fun accepted(result: SurfaceOwnershipResult) =
        result as SurfaceOwnershipResult.Accepted

    private fun acceptingBudget() =
        object : ExclusiveFakeStorageBudget() {
            override fun reserveBytes(bytes: Long): Any = bytes

            override fun commitBytes(token: Any, actualBytes: Long) = Unit

            override fun releaseBytes(token: Any) = Unit

            override fun allocationUnitBytes(path: File) = 4_096L
        }

    private fun measureMigrationGraph(group: SurfaceGroup, directory: File): Long {
        val legacy = SurfaceOwnershipLegacyCodec.readValidated(
            group, directory, SurfaceOwnershipConfiguration(),
        )
        val kernel = FeatureFusionKernel()
        assertTrue(kernel.accept(FeatureFusionBatch(1, 1, emptyList())) is FeatureFusionResult.Accepted)
        val directoryColumns = CompactDirectory(2_344)
        val sourcePage = ArrayList<PagedSource>(CanonicalPageCache.MAX_RECORDS)
        repeat(CanonicalPageCache.MAX_RECORDS) { index ->
            sourcePage += PagedSource(
                SurfaceId(index + 1L), Voxel(index, 0, 0), 0x1234, 197,
                CanonicalReceiptBytes(ByteArray(32) { (index + it).toByte() }),
            )
        }
        val pageBuffer = ByteArray(CanonicalPageCache.PAGE_BYTES)
        val codecScratch = ByteArray(65_536)
        return GraphLayout.parseInstance(
            kernel, legacy, directoryColumns, sourcePage, pageBuffer, codecScratch,
        ).totalSize()
    }

    /** A test-only v3 encoder independent of every v6 production encoder. */
    internal fun writeMaximumV3Fixture(
        directory: File,
        group: SurfaceGroup,
        version: Int = 3,
        unsortedSources: Boolean = false,
        includeCanonicalCurrent: Boolean = false,
    ) {
        require(version in 3..5)
        require(!includeCanonicalCurrent || version >= 4)
        val prefix = testSha256(group.value.encodeToByteArray()).toLowerHex()
        val ledger = directory.resolve("canonical-surface-surface-$prefix.ledger")
        val reservationBody =
            java.io.ByteArrayOutputStream().use { raw ->
                DataOutputStream(raw).use { out ->
                    out.writeInt(0x4d33524c)
                    out.writeInt(1)
                    out.writeLong(1)
                    out.writeLong(1)
                    out.writeLong(0x1_0000_0000L)
                    out.write(testSha256(group.value.encodeToByteArray()))
                    out.write(ByteArray(32) { 1 })
                    out.write(ByteArray(32) { 2 })
                    out.write(ByteArray(32))
                }
                raw.toByteArray()
            }
        ledger.writeBytes(reservationBody + testSha256(reservationBody))

        val snapshot = directory.resolve("canonical-surface-surface-$prefix.snapshot")
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(snapshot).use { file ->
            val digestOutput = DigestOutputStream(file, digest)
            val out = DataOutputStream(digestOutput)
            out.writeInt(0x4d33534f)
            out.writeInt(version)
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
            repeat(300_000) { serialized ->
                val ordinal = if (unsortedSources)
                    ((serialized.toLong() * 200_003L) % 300_000L).toInt()
                else serialized
                writeSource(out, maximumSourceId(ordinal))
            }
            out.writeInt(200_000)
            repeat(100_000) { sourceIndex ->
                val source = maximumRowId(sourceIndex)
                out.writeLong(source)
                out.writeLong(1)
                out.writeLong(source)
                out.writeLong(2)
            }
            if (includeCanonicalCurrent) {
                val canonical = maximumCanonicalReceipt(group)
                out.writeInt(1)
                out.write(ByteArray(32) { 0x61 })
                out.write(ByteArray(32) { 0x62 })
                out.writeInt(canonical.size)
                out.write(canonical)
            } else out.writeInt(0)
            if (version >= 5) out.writeBoolean(false)
            out.flush()
            digestOutput.on(false)
            file.write(digest.digest())
        }
    }

    private fun maximumCanonicalReceipt(group: SurfaceGroup) =
        java.io.ByteArrayOutputStream().use { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(0x4d334352); out.writeInt(1); out.writeUTF(group.value)
                out.writeUTF("maximum-current"); out.writeInt(CanonicalOperation.RELOCATION.ordinal)
                out.writeLong(4); out.writeLong(3); out.writeLong(0x1_0000_0000L); out.writeInt(100_000)
                repeat(4) { out.writeInt(0) }
            }
            raw.toByteArray()
        }

    private fun writeMinimalFixture(directory: File, group: SurfaceGroup, version: Int) {
        val prefix = testSha256(group.value.encodeToByteArray()).toLowerHex()
        writeLedger(directory.resolve("canonical-surface-surface-$prefix.ledger"), group, 2)
        writeSnapshot(directory.resolve("canonical-surface-surface-$prefix.snapshot")) { out ->
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

    private fun writeEmptyV5Fixture(directory: File, group: SurfaceGroup, ledgerHigh: Long) {
        val prefix = testSha256(group.value.encodeToByteArray()).toLowerHex()
        writeLedger(directory.resolve("canonical-surface-surface-$prefix.ledger"), group, ledgerHigh)
        writeSnapshot(directory.resolve("canonical-surface-surface-$prefix.snapshot")) { out ->
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

    private fun writeUnsortedV3Fixture(directory: File, group: SurfaceGroup) {
        val prefix = testSha256(group.value.encodeToByteArray()).toLowerHex()
        writeLedger(directory.resolve("canonical-surface-surface-$prefix.ledger"), group, 3)
        writeSnapshot(directory.resolve("canonical-surface-surface-$prefix.snapshot")) { out ->
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

    private fun writeUnsortedV1Fixture(directory: File, group: SurfaceGroup) {
        val prefix = testSha256(group.value.encodeToByteArray()).toLowerHex()
        writeLedger(directory.resolve("canonical-surface-surface-$prefix.ledger"), group, 3)
        writeSnapshot(directory.resolve("canonical-surface-surface-$prefix.snapshot")) { out ->
            out.writeInt(0x4d33534f)
            out.writeInt(1)
            out.writeLong(3)
            out.writeInt(2)
            writeOwner(out, group, 2, 2, ByteArray(32) { index -> (2 + index).toByte() })
            writeOwner(out, group, 1, 1, ByteArray(32) { index -> (1 + index).toByte() })
            out.writeInt(0)
        }
    }

    private fun writeDuplicateReceiptV5Fixture(
        directory: File,
        group: SurfaceGroup,
        ownership: Boolean,
    ) {
        val prefix = testSha256(group.value.encodeToByteArray()).toLowerHex()
        writeLedger(directory.resolve("canonical-surface-surface-$prefix.ledger"), group, 1)
        val canonical =
            java.io.ByteArrayOutputStream().use { raw ->
                DataOutputStream(raw).use { out ->
                    out.writeInt(0x4d334352)
                    out.writeInt(1)
                    out.writeUTF(group.value)
                    out.writeUTF("accepted-empty")
                    out.writeInt(CanonicalOperation.RELOCATION.ordinal)
                    out.writeLong(0)
                    out.writeLong(0)
                    out.writeLong(1)
                    out.writeInt(0)
                    repeat(4) { out.writeInt(0) }
                }
                raw.toByteArray()
            }
        val repeatedHash = ByteArray(32) { 0x55 }
        writeSnapshot(directory.resolve("canonical-surface-surface-$prefix.snapshot")) { out ->
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
        group: SurfaceGroup,
        corrupt: Boolean,
    ) {
        val prefix = testSha256(group.value.encodeToByteArray()).toLowerHex()
        writeLedger(directory.resolve("canonical-surface-surface-$prefix.ledger"), group, 1)
        val canonical = emptyCanonicalReceipt(group, if (corrupt) "different" else "accepted-empty")
        writeSnapshot(directory.resolve("canonical-surface-surface-$prefix.snapshot")) { out ->
            out.writeInt(0x4d33534f); out.writeInt(3); out.writeLong(1); out.writeInt(0)
            out.writeInt(0); out.writeLong(0); out.writeLong(0)
            out.writeInt(0); out.writeInt(0); out.writeInt(0)
            out.writeInt(1)
            out.write(ByteArray(32) { 0x11 }); out.write(ByteArray(32) { 0x22 })
            out.writeUTF("accepted-empty"); out.writeInt(CanonicalOperation.RELOCATION.ordinal)
            out.writeInt(0); out.writeInt(0); out.writeInt(0)
            out.writeLong(0); out.writeLong(0); out.writeLong(1); out.writeInt(0)
            out.writeInt(0); out.writeInt(canonical.size); out.write(canonical)
        }
    }

    private fun writeV2JournalFixture(
        directory: File,
        group: SurfaceGroup,
        command: String,
    ) {
        val prefix = testSha256(group.value.encodeToByteArray()).toLowerHex()
        writeLedger(directory.resolve("canonical-surface-surface-$prefix.ledger"), group, 1)
        writeSnapshot(directory.resolve("canonical-surface-surface-$prefix.snapshot")) { out ->
            out.writeInt(0x4d33534f); out.writeInt(2); out.writeLong(1); out.writeInt(0)
            out.writeInt(0); out.writeLong(0); out.writeLong(0)
            out.writeInt(0); out.writeInt(0)
            out.writeInt(1)
            out.write(ByteArray(32) { 0x31 }); out.write(ByteArray(32) { 0x32 })
            out.writeUTF(command); out.writeInt(CanonicalOperation.RELOCATION.ordinal)
            out.writeInt(0); out.writeInt(0); out.writeInt(0)
            out.writeLong(0); out.writeLong(0); out.writeLong(1); out.writeInt(0)
        }
    }

    private fun modifiedUtfSize(value: String): Int = 2 + value.sumOf { character ->
        when (character.code) {
            in 0x0001..0x007f -> 1
            in 0x0000..0x07ff -> 2
            else -> 3
        }
    }

    private fun emptyCanonicalReceipt(group: SurfaceGroup, command: String) =
        java.io.ByteArrayOutputStream().use { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(0x4d334352); out.writeInt(1); out.writeUTF(group.value)
                out.writeUTF(command); out.writeInt(CanonicalOperation.RELOCATION.ordinal)
                out.writeLong(0); out.writeLong(0); out.writeLong(1); out.writeInt(0)
                repeat(4) { out.writeInt(0) }
            }
            raw.toByteArray()
        }

    private fun writeOwner(
        out: DataOutputStream,
        group: SurfaceGroup,
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

    private fun writeLedger(file: File, group: SurfaceGroup, end: Long) {
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
                    out.write(testSha256(group.value.encodeToByteArray()))
                    out.write(ByteArray(32) { 1 })
                    out.write(ByteArray(32) { 2 })
                    out.write(ByteArray(32))
                }
                raw.toByteArray()
            }
        file.writeBytes(body + testSha256(body))
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
