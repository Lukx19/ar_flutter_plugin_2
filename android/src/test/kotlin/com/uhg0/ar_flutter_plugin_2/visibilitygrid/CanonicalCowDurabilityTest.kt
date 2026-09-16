package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalCowDurabilityTest {
    private val plans = java.util.IdentityHashMap<PreparedIntent, PreparedCanonicalMutation>()
    @Test
    fun `every durable cut reconciles through a new real coordinator and process store`() {
        CanonicalCowFault.entries.forEach { fault ->
            val directory = Files.createTempDirectory("canonical-surface-cow-real-${fault.name}").toFile()
            try {
                val parent = File(directory, "generations").also { assertTrue(it.mkdirs()) }
                val base = EmptyView("real-${fault.name}")
                val preparedIntent = intent(base, "command", File(directory, "intent"))
                coordinator(parent).use { firstCoordinator ->
                    val delegate = CoordinatorStorageBudget(firstCoordinator)
                    val processDeath = object : CanonicalStorageBudget by delegate {
                        override fun releaseCandidate(token: Any, staging: File) = Unit
                        override fun release(token: Any) = Unit
                    }
                    val firstStore = requireNotNull(CanonicalCommitStore.open(parent, processDeath))
                    val first = firstStore.commit(requireNotNull(plans[preparedIntent]), base, CanonicalCommitFaults(cow = fault))
                    assertTrue("$fault returned $first", first is CanonicalCommitResult.Refused)
                    firstStore.close()
                }

                coordinator(parent).use { reopenedCoordinator ->
                    val reopenedStore = requireNotNull(CanonicalCommitStore.open(parent, CoordinatorStorageBudget(reopenedCoordinator)))
                    val recovered = reopenedStore.commit(requireNotNull(plans[preparedIntent]), base)
                    assertTrue("$fault recovery returned $recovered", recovered is CanonicalCommitResult.Committed)
                    (recovered as CanonicalCommitResult.Committed).commit.close()
                    val generation = requireNotNull(parent.listFiles().orEmpty().singleOrNull { it.name.matches(Regex("canonical-surface-cow-command-[0-9a-f]{64}")) }?.let(CanonicalCowGeneration::open))
                    val physical = reopenedCoordinator.physicallyAllocatedTreeBytes(generation.directory)
                    assertTrue("$fault generation is charged", reopenedCoordinator.committedBytes() >= physical)
                    assertEquals("$fault full authority", authorityPhysicalBytes(parent, reopenedCoordinator), reopenedCoordinator.committedBytes())
                    assertEquals("$fault outstanding reservation", 0L, reopenedCoordinator.reservedBytes())
                    assertTrue("$fault exact reopen", CanonicalCowGeneration.open(generation.directory) != null)
                    reopenedStore.close()
                }
            } finally { directory.deleteRecursively() }
        }
    }

    @Test
    fun `unrelated command startup reclaims COW orphan after metadata removal before tree deletion`() {
        val directory = Files.createTempDirectory("canonical-surface-cow-real-orphan").toFile()
        try {
            val parent = File(directory, "generations").also { assertTrue(it.mkdirs()) }
            val hash = "a".repeat(64)
            val staging = File(parent, ".canonical-surface-cow-command-$hash.staging")
            val target = File(parent, "canonical-surface-cow-command-$hash")
            lateinit var token: String
            coordinator(parent).use { first ->
                val reservation = requireNotNull(first.reserveCandidate(
                    "canonical-surface:canonical:v6-migration", staging, target, mapOf("partial.pages" to 8_192L), 32_768L,
                ))
                token = reservation.token
                assertTrue(staging.isDirectory)
                // Exact crash cut in releaseCandidate: authority is gone, physical tree deletion did not run.
                assertTrue(File(parent, "reservations-v2/$token.reservation").delete())
            }
            assertTrue(staging.isDirectory)

            val unrelatedBase = EmptyView("unrelated")
            val unrelatedIntent = intent(unrelatedBase, "different-command", File(directory, "unrelated-intent"))
            coordinator(parent).use { reopened ->
                val store = requireNotNull(CanonicalCommitStore.open(parent, CoordinatorStorageBudget(reopened)))
                val result = store.commit(requireNotNull(plans[unrelatedIntent]), unrelatedBase)
                assertTrue(result.toString(), result is CanonicalCommitResult.Committed)
                (result as CanonicalCommitResult.Committed).commit.close()
                assertFalse("uncharged COW orphan survived unrelated startup", staging.exists())
                val generation = requireNotNull(parent.listFiles().orEmpty().singleOrNull { it.name.matches(Regex("canonical-surface-cow-command-[0-9a-f]{64}")) }?.let(CanonicalCowGeneration::open))
                assertTrue(reopened.committedBytes() >= reopened.physicallyAllocatedTreeBytes(generation.directory))
                assertEquals(authorityPhysicalBytes(parent, reopened), reopened.committedBytes())
                assertEquals(0L, reopened.reservedBytes())
            }
        } finally { directory.deleteRecursively() }
    }

    private fun coordinator(parent: File) = StorageBudgetCoordinatorV2(
        parent,
        StorageBudgetPolicyV2(64L * 1024 * 1024, 0),
        JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
        freeBytes = { 128L * 1024 * 1024 },
    )

    private fun authorityPhysicalBytes(parent: File, coordinator: StorageBudgetCoordinatorV2): Long =
        parent.listFiles().orEmpty().filter { file ->
            file.isDirectory && (file.name.matches(Regex("canonical-surface-cow-command-[0-9a-f]{64}")) ||
                file.name.matches(Regex("canonical-surface-canonical-v6-[0-9a-f]{64}\\.(?:allocation-[0-9]+|intent)"))) ||
                file.isFile && (file.name.matches(Regex("canonical-surface-selector-root-[0-9a-f]{64}\\.root")) ||
                    file.name in setOf("canonical-surface-root-selector", "canonical-surface-root-A.slot", "canonical-surface-root-B.slot"))
        }.sumOf { file -> if (file.isDirectory) coordinator.physicallyAllocatedTreeBytes(file) else round(file.length(), 4_096L) }

    private fun round(bytes: Long, unit: Long) = if (bytes == 0L) 0L else ((bytes - 1L) / unit + 1L) * unit

    private fun intent(base: CanonicalStateView, commandId: String, directory: File): PreparedIntent {
        val command = FeatureMutationCommand(commandId, 0, 0, CanonicalTarget(null, Voxel(0, 0, 0), 0, 0, 192))
        val plan = (SurfaceOwnership.prepareMutation(
            base, SurfaceOwnershipConfiguration(), command,
        ) as CanonicalMutationPreparation.Prepared).mutation
        val journal = (CanonicalDirtyJournal.open(
            base, directory, UnlimitedBudget,
        ) as CanonicalDirtyJournalOpenResult.Opened).journal
        return (journal.flush(plan) as CanonicalDirtyJournalFlushResult.Prepared).intent.also { plans[it] = plan }
    }

    private object UnlimitedBudget : ExclusiveFakeStorageBudget() {
        override fun reserveBytes(bytes: Long): Any = bytes
        override fun commitBytes(token: Any, actualBytes: Long) = Unit
        override fun releaseBytes(token: Any) = Unit
        override fun allocationUnitBytes(path: File) = 4_096L
    }

    private class EmptyView(name: String) : CanonicalStateView {
        override val cut = CompactCanonicalCut(
            SurfaceGroup(name), CompactCanonicalStore.PROFILE, 0, 0, 1, 0, 0, 0, 0, null,
            receipt("root-$name"), receipt("source-$name"),
        )
        override fun findById(id: SurfaceId): CompactSurface? = null
        override fun findByVoxel(voxel: Voxel): CompactSurface? = null
        override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int) = CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: SurfaceId): CanonicalPageRead<PagedSource?> = CanonicalPageRead.Complete(null, 0, 0)
        override fun visitSourceSupport(target: SurfaceId, cursor: SourceSupportCursor?, sink: (PagedSupport) -> Boolean) =
            SourceSupportRead.Complete(0, null, 0, 0)
        override fun retainedMemoryReceipt(): CompactRetainedMemoryReceipt = error("unused")
        override fun allocatedStorageReceipt(): CompactStorageReceipt = error("unused")
        override fun close() = Unit
    }

    companion object {
        private fun receipt(text: String) = CanonicalReceiptBytes(
            MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()),
        )
    }
}
