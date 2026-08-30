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

class M3IntegratedCapacityCampaignTest {
    @Test
    fun `constructed maximum profile survives ACK adjacent coexistence within fixed limits`() {
        System.setProperty("jol.magicFieldOffset", "true")
        val directory = Files.createTempDirectory("m3-integrated-maximum").toFile()
        try {
            val group = M3SurfaceGroup("integrated-maximum")
            M3CanonicalStoreMigrationTest().writeMaximumV3Fixture(
                directory, group, version = 5, includeCanonicalCurrent = true,
            )
            StorageBudgetCoordinatorV2(
                directory, StorageBudgetPolicyV2(256L * 1024 * 1024, 0),
                JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
                freeBytes = { 512L * 1024 * 1024 },
            ).use { coordinator ->
                val budget = M3CoordinatorStorageBudget(coordinator)
                val migrated = M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
                    as M3CompactCanonicalMigrationResult.Prepared
                val base = (M3CompactCanonicalStore.openV6(group, directory, budget)
                    as M3CompactCanonicalOpenResult.Opened).store
                try {
                    assertEquals(100_000, migrated.cut.liveSurfaceCount)
                    assertEquals(200_000, migrated.cut.lineageCount)
                    assertEquals(300_000, migrated.cut.sourceCount)
                    assertEquals(300_000, migrated.cut.supportCount)
                    val memory = base.retainedMemoryReceipt()
                    val storage = base.allocatedStorageReceipt()
                    assertEquals(14_565_056L, memory.residentTotalBytes)
                    assertTrue(storage.directoryBytes <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)

                    val activation = (M3CanonicalActivation.prepare(group, directory, budget)
                        as M3CanonicalActivationPreparation.Prepared).plan
                    val owner = (M3SurfaceOwnership.open(group, directory, budget, activation)
                        as M3SurfaceOwnershipOpenResult.Opened).ownership
                    try {
                        val before = requireNotNull(owner.activationState())
                        val selected = before.current as M3CanonicalActivationCurrent.Receipt
                        assertTrue(selected.identity.canonicalLength in 1..M3CanonicalActivationResources.MAX_CURRENT_BYTES)
                        assertTrue(owner.acknowledgeCanonicalCurrent(
                            M3CanonicalAcknowledgement(
                                selected.identity.commandHash,
                                before.cut.geometryRevision,
                                before.cut.lineageRevision,
                            ),
                        ) is M3CanonicalAcknowledgementResult.Acknowledged)

                        val mutation = (M3SurfaceOwnership.prepareMutation(
                            base, M3SurfaceOwnershipConfiguration(),
                            M3FeatureMutationCommand(
                                "maximum-adjacent",
                                before.cut.geometryRevision,
                                before.cut.lineageRevision,
                                M3CanonicalTarget(M3SurfaceId(1), M3Voxel(0, 0, 0), 0x13, 0x34, 197),
                            ),
                        ) as M3CanonicalMutationPreparation.Prepared).mutation
                        assertTrue(owner.commitAdjacentCanonicalMutation(mutation) is M3CanonicalAdjacentCommitResult.Committed)
                        val after = requireNotNull(owner.activationState())
                        val next = after.current as M3CanonicalActivationCurrent.Receipt
                        assertEquals(100_000, after.cut.liveSurfaceCount)
                        assertEquals(200_000, after.cut.lineageCount)
                        assertEquals(300_000, after.cut.sourceCount)
                        assertEquals(300_000, after.cut.supportCount)

                        val intentBytes = directory.listFiles().orEmpty()
                            .filter { it.isDirectory && it.name.endsWith(".intent") }
                            .sumOf(budget::allocatedBytes)
                        val sharedPhaseBytes = maxOf(
                            selected.identity.canonicalLength,
                            next.identity.canonicalLength,
                            mutation.work.constructionPeakBytes,
                            Math.addExact(mutation.work.retainedPlanBytes, mutation.work.writerScratchBytes),
                            intentBytes,
                        )
                        val ownerBytes = GraphLayout.parseInstance(owner, before, after).totalSize()
                        val completePeakBytes = Math.addExact(
                            memory.residentTotalBytes,
                            Math.addExact(ownerBytes, sharedPhaseBytes),
                        )
                        assertTrue("shared phase=$sharedPhaseBytes", sharedPhaseBytes <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
                        assertTrue("complete peak=$completePeakBytes", completePeakBytes <= M3CompactCanonicalStore.C17_TOTAL_BYTES)

                        val activationPrefix = "m3-activation-${group.hash.joinToString("") { "%02x".format(it) }}"
                        val files = directory.walkTopDown().filter(File::isFile).toList()
                        assertEquals(2, files.count { it.name.startsWith("$activationPrefix-root-") })
                        assertEquals(1, files.count { it.name.startsWith("$activationPrefix-current-") })
                        assertTrue(files.none { it.name.endsWith(".attempt") || it.name.endsWith(".transaction") })
                        assertEquals(0L, coordinator.reservedBytes())

                        val chargedPhysicalBytes = directory.listFiles().orEmpty().filter { entry ->
                            !entry.name.startsWith("m3-surface-") &&
                                entry.name !in setOf("ledger-v2", "reservations-v2", "reclaims-v2")
                        }.sumOf(budget::allocatedBytes)
                        assertEquals(chargedPhysicalBytes, coordinator.committedBytes())
                        println(
                            "M3_INTEGRATED_MAXIMUM=resident=${memory.residentTotalBytes} " +
                                "owner=$ownerBytes sharedPhase=$sharedPhaseBytes completePeak=$completePeakBytes " +
                                "directory=${storage.directoryBytes} committed=${coordinator.committedBytes()} " +
                                "chargedPhysical=$chargedPhysicalBytes",
                        )
                    } finally { owner.close() }
                } finally { base.close() }
            }
        } finally { directory.deleteRecursively() }
    }
}
