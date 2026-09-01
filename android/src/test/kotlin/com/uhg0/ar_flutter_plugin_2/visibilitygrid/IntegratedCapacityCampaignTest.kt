package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openjdk.jol.info.GraphLayout

class IntegratedCapacityCampaignTest {
    @Test
    fun `constructed maximum profile survives ACK adjacent coexistence within fixed limits`() {
        val directory = Files.createTempDirectory("canonical-surface-integrated-maximum").toFile()
        try {
            val group = SurfaceGroup("integrated-maximum")
            CanonicalStoreMigrationTest().writeMaximumV3Fixture(
                directory, group, version = 5, includeCanonicalCurrent = true,
            )
            StorageBudgetCoordinatorV2(
                directory, StorageBudgetPolicyV2(256L * 1024 * 1024, 0),
                JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
                freeBytes = { 512L * 1024 * 1024 },
            ).use { coordinator ->
                val budget = CoordinatorStorageBudget(coordinator)
                val migrated = CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
                    as CompactCanonicalMigrationResult.Prepared
                val base = (CompactCanonicalStore.openV6(group, directory, budget)
                    as CompactCanonicalOpenResult.Opened).store
                try {
                    assertEquals(100_000, migrated.cut.liveSurfaceCount)
                    assertEquals(200_000, migrated.cut.lineageCount)
                    assertEquals(300_000, migrated.cut.sourceCount)
                    assertEquals(300_000, migrated.cut.supportCount)
                    val memory = base.retainedMemoryReceipt()
                    val storage = base.allocatedStorageReceipt()
                    val commitReopenOwnerBytes = requireNotNull(CanonicalCommitStore.open(directory, budget)).use {
                        GraphLayout.parseInstance(it).totalSize()
                    }
                    assertEquals(14_565_056L, memory.residentTotalBytes)
                    assertTrue(storage.directoryBytes <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)

                    val activation = (CanonicalActivation.prepare(group, directory, budget)
                        as CanonicalActivationPreparation.Prepared).plan
                    val owner = (SurfaceOwnership.open(group, directory, budget, activation)
                        as SurfaceOwnershipOpenResult.Opened).ownership
                    try {
                        val before = requireNotNull(owner.activationState())
                        val selected = before.current as CanonicalActivationCurrent.Receipt
                        assertTrue(selected.identity.canonicalLength in 1..CanonicalActivationResources.MAX_CURRENT_BYTES)
                        assertTrue(owner.acknowledgeCanonicalCurrent(
                            CanonicalAcknowledgement(
                                selected.identity.commandHash,
                                before.cut.geometryRevision,
                                before.cut.lineageRevision,
                            ),
                        ) is CanonicalAcknowledgementResult.Acknowledged)

                        val mutation = (owner.prepareAdjacentMutation(
                            base,
                            FeatureMutationCommand(
                                "maximum-adjacent",
                                before.cut.geometryRevision,
                                before.cut.lineageRevision,
                                CanonicalTarget(SurfaceId(1), Voxel(0, 0, 0), 0x13, 0x34, 197),
                            ),
                        ) as CanonicalMutationPreparation.Prepared).mutation
                        val ownership = mutableListOf<CanonicalAdjacentOwnershipObservation>()
                        CanonicalActivationTestHooks.onAdjacentOwnership = ownership::add
                        try {
                            // The production seam itself must reject a second maximum-resident
                            // authority; closing a test fixture is not the ownership invariant.
                            val duplicate = (CompactCanonicalStore.openV6(group, directory, budget)
                                as CompactCanonicalOpenResult.Opened).store
                            try {
                                val refused = owner.commitAdjacentCanonicalMutation(mutation)
                                    as CanonicalAdjacentCommitResult.Refused
                                assertEquals(CanonicalAdjacentCommitRefusal.DUPLICATE_RESIDENT_AUTHORITY, refused.reason)
                                assertEquals(PreparedMutationDisposition.RETRYABLE, refused.disposition)
                                assertEquals(PreparedMutationLifecycle.READY, mutation.lifecycle())
                                assertEquals(2, ownership.single().liveStoreCount)
                                assertEquals(memory.residentTotalBytes * 2, ownership.single().liveStoreBytes)
                            } finally { duplicate.close() }
                            ownership.clear()
                            assertTrue(owner.commitAdjacentCanonicalMutation(mutation) is CanonicalAdjacentCommitResult.Committed)
                            assertEquals(PreparedMutationLifecycle.CONSUMED, mutation.lifecycle())
                        } finally { CanonicalActivationTestHooks.onAdjacentOwnership = null }
                        assertEquals(CanonicalAdjacentOwnershipStage.entries.toSet(), ownership.map { it.stage }.toSet())
                        assertTrue(ownership.all { it.liveStoreCount == 1 && it.liveStoreBytes == memory.residentTotalBytes })
                        val after = requireNotNull(owner.activationState())
                        val next = after.current as CanonicalActivationCurrent.Receipt
                        assertEquals(100_000, after.cut.liveSurfaceCount)
                        assertEquals(200_000, after.cut.lineageCount)
                        assertEquals(300_000, after.cut.sourceCount)
                        assertEquals(300_000, after.cut.supportCount)
                        assertEquals(EXPECTED_MODELED_RESIDENT_BYTES, memory.residentTotalBytes)
                        assertEquals(1, ownership.maxOf { it.liveStoreCount })

                        val intentBytes = directory.listFiles().orEmpty()
                            .filter { it.isDirectory && it.name.endsWith(".intent") }
                            .sumOf(budget::allocatedBytes)
                        val instrumentedPhaseBytes = ownership.maxOf { observation -> maxOf(
                            observation.retainedPlanBytes + observation.writerScratchBytes,
                            observation.constructionPeakBytes,
                        ) }
                        val sharedPhaseBytes = maxOf(
                            selected.identity.canonicalLength,
                            next.identity.canonicalLength,
                            instrumentedPhaseBytes,
                            intentBytes,
                        )
                        val ownerBytes = GraphLayout.parseInstance(owner, before, after).totalSize()
                        val completePeakBytes = Math.addExact(
                            memory.residentTotalBytes,
                            Math.addExact(ownerBytes, Math.addExact(commitReopenOwnerBytes, sharedPhaseBytes)),
                        )
                        // JOL owner graphs are diagnostic; modeled receipts and portable ceilings are normative.
                        assertEquals(EXPECTED_SHARED_PHASE_BYTES, sharedPhaseBytes)
                        assertEquals(EXPECTED_DIRECTORY_BYTES, storage.directoryBytes)
                        assertTrue("shared phase=$sharedPhaseBytes", sharedPhaseBytes <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
                        assertTrue("complete peak=$completePeakBytes", completePeakBytes <= CompactCanonicalStore.C17_TOTAL_BYTES)

                        val activationPrefix = "canonical-surface-activation-${group.hash.joinToString("") { "%02x".format(it) }}"
                        val files = directory.walkTopDown().filter(File::isFile).toList()
                        assertEquals(2, files.count { it.name.startsWith("$activationPrefix-root-") })
                        assertEquals(1, files.count { it.name.startsWith("$activationPrefix-current-") })
                        assertTrue(files.none { it.name.endsWith(".attempt") || it.name.endsWith(".transaction") })
                        assertEquals(0L, coordinator.reservedBytes())

                        val chargedPhysicalBytes = directory.listFiles().orEmpty().filter { entry ->
                            !entry.name.startsWith("canonical-surface-surface-") &&
                                entry.name !in setOf("ledger-v2", "reservations-v2", "reclaims-v2")
                        }.sumOf(budget::allocatedBytes)
                        assertEquals(EXPECTED_COMMITTED_PHYSICAL_BYTES, chargedPhysicalBytes)
                        assertEquals(EXPECTED_COMMITTED_PHYSICAL_BYTES, coordinator.committedBytes())
                        assertEquals(chargedPhysicalBytes, coordinator.committedBytes())
                        println(
                            "CANONICAL_SURFACE_INTEGRATED_MAXIMUM=resident=${memory.residentTotalBytes} " +
                                "liveStores=${ownership.maxOf { it.liveStoreCount }} owner=$ownerBytes " +
                                "commitReopenOwner=$commitReopenOwnerBytes " +
                                "sharedPhase=$sharedPhaseBytes completePeak=$completePeakBytes " +
                                "directory=${storage.directoryBytes} committed=${coordinator.committedBytes()} " +
                                "chargedPhysical=$chargedPhysicalBytes",
                        )
                    } finally { owner.close() }
                } finally { base.close() }
            }
        } finally { directory.deleteRecursively() }
    }

    private companion object {
        const val EXPECTED_MODELED_RESIDENT_BYTES = 14_565_056L
        const val EXPECTED_SHARED_PHASE_BYTES = 139_520L
        const val EXPECTED_DIRECTORY_BYTES = 139_264L
        const val EXPECTED_COMMITTED_PHYSICAL_BYTES = 42_188_800L
    }
}
