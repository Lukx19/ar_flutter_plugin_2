package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactCanonicalStoreTest {
    @Test
    fun `seeded baseline high water and revisions remain part of the exact cut`() {
        val directory = Files.createTempDirectory("canonical-surface-compact-baseline").toFile()
        try {
            val group = SurfaceGroup("compact-baseline")
            val baseline = committedEmptyBaseline("binding", group.value, 7, 11, 13)
            val configuration = SurfaceOwnershipConfiguration(seededEmptyBaseline = baseline)
            val legacy = opened(SurfaceOwnership.open(group, directory, configuration))
            val accepted =
                accepted(legacy.apply(SurfaceOwnershipCommand("seed", listOf(candidate(0)))))
            legacy.close()
            val prepared =
                CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    acceptingBudget(),
                    configuration,
                ) as CompactCanonicalMigrationResult.Prepared
            assertEquals(baseline, prepared.cut.seededEmptyBaseline)
            assertEquals(
                accepted.receipt.nextSurfaceIdHighWater,
                prepared.cut.nextSurfaceIdHighWater,
            )
            assertEquals(11, prepared.cut.geometryRevision)
            assertEquals(13, prepared.cut.lineageRevision)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `v6 opens exact canonical rows by id voxel and bounded page`() {
        val directory = Files.createTempDirectory("canonical-surface-compact-view").toFile()
        try {
            val group = SurfaceGroup("compact-view")
            val legacy = opened(SurfaceOwnership.open(group, directory))
            val seeded =
                accepted(
                    legacy.apply(
                        SurfaceOwnershipCommand(
                            "seed",
                            listOf(candidate(-1), candidate(0), candidate(31)),
                        )
                    )
                )
            accepted(
                legacy.transact(
                    CanonicalTransactionCommand(
                        "move",
                        CanonicalOperation.RELOCATION,
                        0,
                        0,
                        listOf(seeded.owners.first().id),
                        listOf(
                            CanonicalTarget(
                                seeded.owners.first().id,
                                Voxel(-2, 0, 0),
                                1,
                                2,
                                193,
                            )
                        ),
                    )
                )
            )
            legacy.close()

            val prepared =
                CompactCanonicalStore.prepareV6SiblingMigration(
                    group,
                    directory,
                    acceptingBudget(),
                )
            assertTrue(
                "$prepared files=${directory.walkTopDown().map { it.name }.toList()} open=${CompactCanonicalStore.openV6(group, directory, acceptingBudget())}",
                prepared is CompactCanonicalMigrationResult.Prepared,
            )
            val cut = (prepared as CompactCanonicalMigrationResult.Prepared).cut
            val store =
                (CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                        as CompactCanonicalOpenResult.Opened)
                    .store
            assertEquals(cut, store.cut)
            assertEquals(
                Voxel(-2, 0, 0),
                requireNotNull(store.findById(seeded.owners.first().id)).voxel,
            )
            assertEquals(
                seeded.owners[1].id,
                requireNotNull(store.findByVoxel(Voxel(0, 0, 0))).id,
            )
            assertNull(store.findByVoxel(Voxel(99, 0, 0)))
            val first = store.readPage(StorageRegion(-1, 0, 0), 2, 0, 1)
            assertEquals(1, first.rows.size)
            assertEquals(1, first.inspectedRows)
            assertTrue(first.rows.single().id.value > 0)
            val empty = store.readPage(StorageRegion(Int.MAX_VALUE, 0, 0), 0, 0, 1)
            assertTrue(empty.rows.isEmpty())
            assertEquals(0, empty.inspectedRows)
            val sources = mutableListOf<PagedSupport>()
            val support =
                store.visitSourceSupport(seeded.owners.first().id, null) {
                    sources += it
                    true
                }
            assertEquals(1, (support as SourceSupportRead.Complete).delivered)
            assertEquals(seeded.owners.first().id, sources.single().source.id)
            assertEquals(32, sources.single().source.allocationFingerprint.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `receipt includes every retained owner and reserves the mutation journal`() {
        val directory = Files.createTempDirectory("canonical-surface-compact-memory").toFile()
        try {
            val group = SurfaceGroup("compact-memory")
            val legacy = opened(SurfaceOwnership.open(group, directory))
            accepted(legacy.apply(SurfaceOwnershipCommand("seed", listOf(candidate(0)))))
            legacy.close()
            CompactCanonicalStore.prepareV6SiblingMigration(group, directory, acceptingBudget())
            val receipt =
                ((CompactCanonicalStore.openV6(group, directory, acceptingBudget())
                            as CompactCanonicalOpenResult.Opened)
                        .store)
                    .retainedMemoryReceipt()
            assertEquals(7_589_936L, receipt.kernelBytes)
            assertTrue(
                receipt.rowColumnsBytes > 0 &&
                    receipt.idOrderBytes > 0 &&
                    receipt.pageOrderBytes > 0
            )
            assertTrue(
                receipt.lineageColumnsBytes > 0 &&
                    receipt.directoryColumnsBytes > 0 &&
                    receipt.cachePayloadBytes == 65_536L
            )
            assertTrue(receipt.withinIssue114Budget)
            assertTrue(receipt.preservesIssue115Reserve)
            assertEquals(1_048_576L, receipt.journalReserveBytes)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun candidate(x: Int) =
        SurfaceCandidate(
            voxel = Voxel(x, 0, 0),
            normalOctX = 0,
            normalOctY = 0,
            normalConfidence = 192,
        )

    private fun opened(result: SurfaceOwnershipOpenResult) =
        (result as SurfaceOwnershipOpenResult.Opened).ownership

    private fun accepted(result: SurfaceOwnershipResult) =
        result as SurfaceOwnershipResult.Accepted

    private fun accepted(result: CanonicalTransactionResult) =
        result as CanonicalTransactionResult.Accepted

    private fun acceptingBudget() =
        object : CanonicalStorageBudget {
            override fun reserve(bytes: Long): Any = bytes
            override fun reserveCandidateExclusive(staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long) =
                CanonicalCandidateReservation.QuotaRefused

            override fun commit(token: Any, actualBytes: Long) = Unit

            override fun release(token: Any) = Unit

            override fun allocationUnitBytes(path: File) = 4_096L
        }
}
