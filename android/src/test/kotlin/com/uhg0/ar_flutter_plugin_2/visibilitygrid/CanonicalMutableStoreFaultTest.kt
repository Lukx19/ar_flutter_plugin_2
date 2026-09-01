package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalMutableStoreFaultTest {
    @Test
    fun `process reopen replays exact current and blocks distinct work before any durable change`() {
        val parent = Files.createTempDirectory("canonical-surface-current-pending-").toFile()
        try {
            val base = EmptyView("pending")
            val original = plan(base, "original", 0)
            val firstCoordinator = coordinator(parent)
            val firstStore = requireNotNull(CanonicalCommitStore.open(parent, CoordinatorStorageBudget(firstCoordinator)))
            val committed = firstStore.commit(original, base) as CanonicalCommitResult.Committed
            val selectedCut = committed.commit.view.cut
            val selectedCurrent = committed.commit.current
            val distinct = plan(committed.commit.view, "distinct", 1)
            committed.commit.close(); firstStore.close(); firstCoordinator.close()

            val reopenedCoordinator = coordinator(parent)
            val reopenedStore = requireNotNull(CanonicalCommitStore.open(parent, CoordinatorStorageBudget(reopenedCoordinator)))
            val beforeFiles = snapshot(parent)
            val beforeCommitted = reopenedCoordinator.committedBytes()
            val replay = reopenedStore.commit(original, base) as CanonicalCommitResult.Committed
            assertTrue(replay.replayed)
            assertEquals(selectedCut, replay.commit.view.cut)
            assertEquals(selectedCurrent, replay.commit.current)
            replay.commit.close()
            assertEquals(beforeFiles, snapshot(parent))
            assertEquals(beforeCommitted, reopenedCoordinator.committedBytes())
            assertEquals(0L, reopenedCoordinator.reservedBytes())

            val pending = reopenedStore.commit(distinct, base) as CanonicalCommitResult.Refused
            assertEquals(CanonicalCommitRefusal.CURRENT_PENDING, pending.reason)
            val changedBytes = reopenedStore.commit(plan(base, "original", 2), base) as CanonicalCommitResult.Refused
            assertEquals(CanonicalCommitRefusal.CURRENT_PENDING, changedBytes.reason)
            assertEquals(beforeFiles, snapshot(parent))
            assertEquals(beforeCommitted, reopenedCoordinator.committedBytes())
            assertEquals(0L, reopenedCoordinator.reservedBytes())
            val selected = reopenedStore.reopen(base) as CanonicalReopenResult.Selected
            assertEquals(selectedCut, selected.commit.view.cut)
            assertEquals(selectedCurrent, selected.commit.current)
            selected.commit.close()
            assertEquals(beforeCommitted, allAuthorityPhysicalBytes(parent, reopenedCoordinator))
            reopenedStore.close(); reopenedCoordinator.close()
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun `every selector fault is observed through commit and reopens exactly old or new`() {
        CanonicalSelectorFault.entries.filterNot { it.name.startsWith("PROCESS_CRASH") }.forEach { fault ->
            val parent = Files.createTempDirectory("canonical-surface-selector-${fault.name}-").toFile()
            try {
                val base = EmptyView("fault-${fault.name}")
                val store = requireNotNull(CanonicalCommitStore.open(parent, RecordingBudget()))
                val result = store.commit(plan(base, "command", 0), base, CanonicalCommitFaults(selector = fault))
                when (result) {
                    is CanonicalCommitResult.Committed -> result.commit.close()
                    is CanonicalCommitResult.UnknownAfterSwitch -> Unit
                    is CanonicalCommitResult.Refused -> assertEquals(CanonicalCommitRefusal.SELECTOR_REFUSED, result.reason)
                }
                val reopened = requireNotNull(CanonicalCommitStore.open(parent, RecordingBudget())).reopen(base)
                if (result is CanonicalCommitResult.UnknownAfterSwitch || result is CanonicalCommitResult.Committed) {
                    reopened as CanonicalReopenResult.Selected
                    assertEquals(1, reopened.commit.view.cut.liveSurfaceCount); reopened.commit.close()
                } else assertTrue("$fault returned $result then $reopened", reopened is CanonicalReopenResult.GenerationZero)
                store.close()
            } finally { parent.deleteRecursively() }
        }
    }

    @Test
    fun `hard process cuts reconcile old or new through production commit`() {
        listOf(
            CanonicalSelectorFault.PROCESS_CRASH_BEFORE_SELECTOR_SWITCH to false,
            CanonicalSelectorFault.PROCESS_CRASH_AFTER_SELECTOR_SWITCH to true,
        ).forEach { (fault, selectedAfterCrash) ->
            val parent = Files.createTempDirectory("canonical-surface-hard-${fault.name}-").toFile()
            try {
                val base = EmptyView("hard-${fault.name}")
                val firstCoordinator = coordinator(parent)
                val firstStore = requireNotNull(CanonicalCommitStore.open(parent, CoordinatorStorageBudget(firstCoordinator)))
                assertThrows(CanonicalSimulatedProcessCrash::class.java) {
                    firstStore.commit(plan(base, "command", 0), base, CanonicalCommitFaults(selector = fault))
                }
                assertTrue(firstCoordinator.reservedBytes() > 0L)
                firstCoordinator.close()
                val reopenedCoordinator = coordinator(parent)
                val reopenedStore = requireNotNull(CanonicalCommitStore.open(parent, CoordinatorStorageBudget(reopenedCoordinator)))
                val reopened = reopenedStore.reopen(base)
                assertEquals(selectedAfterCrash, reopened is CanonicalReopenResult.Selected)
                if (reopened is CanonicalReopenResult.Selected) reopened.commit.close()
                assertEquals(0L, reopenedCoordinator.reservedBytes())
                assertEquals(reopenedCoordinator.committedBytes(), allAuthorityPhysicalBytes(parent, reopenedCoordinator))
                val repeated = reopenedStore.reopen(base)
                assertEquals(selectedAfterCrash, repeated is CanonicalReopenResult.Selected)
                if (repeated is CanonicalReopenResult.Selected) repeated.commit.close()
                reopenedStore.close(); reopenedCoordinator.close()
            } finally { parent.deleteRecursively() }
        }
    }

    @Test
    fun `selected corruption fails closed and never falls back to generation zero`() {
        val parent = Files.createTempDirectory("canonical-surface-selected-corrupt-").toFile()
        try {
            val base = EmptyView("corrupt")
            val store = requireNotNull(CanonicalCommitStore.open(parent, RecordingBudget()))
            (store.commit(plan(base, "command", 0), base) as CanonicalCommitResult.Committed).commit.close()
            File(parent, "canonical-surface-root-selector").writeBytes(byteArrayOf(1, 2, 3))
            val refused = store.reopen(base) as CanonicalReopenResult.Refused
            assertEquals(CanonicalSelectorRefusal.CORRUPT_SELECTED_ROOT, refused.reason)
            store.close()
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun `unknown after switch is resolved by exact lookup and changed identity conflicts`() {
        val parent = Files.createTempDirectory("canonical-surface-lookup-").toFile()
        try {
            val base = EmptyView("lookup")
            val store = requireNotNull(CanonicalCommitStore.open(parent, RecordingBudget()))
            val result = store.commit(plan(base, "lookup", 0), base, CanonicalCommitFaults(selector = CanonicalSelectorFault.AFTER_SELECTOR_SWITCH))
            assertTrue(result is CanonicalCommitResult.UnknownAfterSwitch)
            val selected = store.reopen(base) as CanonicalReopenResult.Selected
            val root = selected.commit.roots.single()
            val query = CanonicalCommitQuery("lookup", root.commandKind, root.generationHash, root.current)
            selected.commit.close()
            val found = store.lookupCommit(query, base) as CanonicalCommitLookup.Found
            assertEquals(root.current, found.commit.current); found.commit.close()
            val changed = query.copy(current = query.current.copy(length = query.current.length + 1L))
            assertEquals(CanonicalSelectorRefusal.IDENTITY_CONFLICT, (store.lookupCommit(changed, base) as CanonicalCommitLookup.Refused).reason)
            store.close()
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun `fixed selector codecs and every selected authority fail closed`() {
        val parent = Files.createTempDirectory("canonical-surface-codecs-").toFile()
        try {
            val base = EmptyView("codecs")
            val store = requireNotNull(CanonicalCommitStore.open(parent, RecordingBudget()))
            (store.commit(plan(base, "codec", 0), base) as CanonicalCommitResult.Committed).commit.close()
            val selector = File(parent, "canonical-surface-root-selector")
            val selectedSlot = File(parent, if (selector.readBytes()[8].toInt() == 0) "canonical-surface-root-A.slot" else "canonical-surface-root-B.slot")
            val root = parent.listFiles().orEmpty().single { it.name.matches(Regex("canonical-surface-selector-root-[0-9a-f]{64}\\.root")) }
            val generation = parent.listFiles().orEmpty().single { it.name.matches(Regex("canonical-surface-cow-command-[0-9a-f]{64}")) }
            assertEquals(88L, selector.length()); assertEquals(80L, selectedSlot.length()); assertEquals(352L, root.length())
            listOf(selector, selectedSlot, root, File(generation, CanonicalCowGeneration.CURRENT_UNACKED_FILE)).forEach { authority ->
                val original = authority.readBytes()
                authority.writeBytes(original.copyOf(original.size / 2))
                assertEquals(authority.name, CanonicalSelectorRefusal.CORRUPT_SELECTED_ROOT, (store.reopen(base) as CanonicalReopenResult.Refused).reason)
                authority.writeBytes(original)
                (store.reopen(base) as CanonicalReopenResult.Selected).commit.close()
            }
            store.close()
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun `pointer work is dense independent and cleanup deletes only validated unreachable generations`() {
        val directory = Files.createTempDirectory("canonical-surface-pointer-bounds-").toFile()
        try {
            fun publish(name: String, declared: Int): CanonicalPublicationReceipt {
                val parent = File(directory, name)
                val base = EmptyView(name, declared)
                val fixtureParent = File(directory, "$name-fixture")
                val fixtureBase = EmptyView("$name-orphan")
                val fixture = requireNotNull(CanonicalCommitStore.open(fixtureParent, RecordingBudget()))
                (fixture.commit(plan(fixtureBase, "orphan", 7), fixtureBase) as CanonicalCommitResult.Committed).commit.close()
                val orphan = fixtureParent.listFiles().orEmpty().single { it.name.matches(Regex("canonical-surface-cow-command-[0-9a-f]{64}")) }
                assertTrue(parent.mkdirs()); assertTrue(orphan.renameTo(File(parent, orphan.name)))
                val invalidGeneration = File(parent, "canonical-surface-cow-command-${"a".repeat(64)}").also { it.mkdirs(); File(it, "partial").writeText("invalid") }
                val invalidRoot = File(parent, "canonical-surface-selector-root-${"b".repeat(64)}.root").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
                val store = requireNotNull(CanonicalCommitStore.open(parent, RecordingBudget()))
                val selectedPlan = if (declared == 0) plan(base, "selected", 1)
                    else plan(base, "selected", 0, SurfaceId(1), 191)
                val committed = store.commit(selectedPlan, base) as CanonicalCommitResult.Committed
                assertFalse("validated unreachable generation survived", File(parent, orphan.name).exists())
                assertTrue("invalid generation was destructively cleaned", invalidGeneration.exists())
                assertTrue("invalid root was destructively cleaned", invalidRoot.exists())
                val receipt = committed.commit.receipt
                committed.commit.close(); store.close(); fixture.close()
                return receipt
            }
            val small = publish("small", 0)
            val dense = publish("dense", 100_000)
            assertEquals(small.pointerBytes, dense.pointerBytes)
            assertEquals(small.hashOperations, dense.hashOperations)
            assertEquals(small.rootOperations, dense.rootOperations)
            assertTrue(dense.phasePeakBytes <= 1_048_576L)
        } finally { directory.deleteRecursively() }
    }

    private fun coordinator(parent: File) = StorageBudgetCoordinatorV2(
        parent, StorageBudgetPolicyV2(64L * 1024 * 1024, 0),
        JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
        freeBytes = { 128L * 1024 * 1024 },
    )

    private fun snapshot(parent: File): Map<String, Pair<Long, String>> = parent.walkTopDown().filter(File::isFile).associate { file ->
        file.relativeTo(parent).invariantSeparatorsPath to (file.length() to MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
    }

    private fun allAuthorityPhysicalBytes(parent: File, coordinator: StorageBudgetCoordinatorV2): Long =
        parent.listFiles().orEmpty().filter { file -> file.isDirectory && (
            file.name.matches(Regex("canonical-surface-cow-command-[0-9a-f]{64}")) || file.name.matches(Regex("canonical-surface-canonical-v6-[0-9a-f]{64}\\.(?:allocation-[0-9]+|intent)"))
            ) || file.isFile && (
            file.name.matches(Regex("canonical-surface-selector-root-[0-9a-f]{64}\\.root")) || file.name in setOf("canonical-surface-root-selector", "canonical-surface-root-A.slot", "canonical-surface-root-B.slot")
            )
        }.sumOf { if (it.isDirectory) coordinator.physicallyAllocatedTreeBytes(it) else round(it.length(), 4_096L) }

    private fun plan(
        base: CanonicalStateView,
        commandId: String,
        x: Int,
        existing: SurfaceId? = null,
        confidence: Int = 192,
    ): PreparedCanonicalMutation {
        val command = FeatureMutationCommand(commandId, base.cut.geometryRevision, base.cut.lineageRevision, CanonicalTarget(existing, Voxel(x, 0, 0), 0, 0, confidence))
        return (SurfaceOwnership.prepareMutation(base, SurfaceOwnershipConfiguration(), command) as CanonicalMutationPreparation.Prepared).mutation
    }

    private class EmptyView(name: String, declared: Int = 0) : CanonicalStateView {
        private val representative = CompactSurface(SurfaceId(1), Voxel(0, 0, 0), 0, 192).takeIf { declared > 0 }
        override val cut = CompactCanonicalCut(SurfaceGroup(name), CompactCanonicalStore.PROFILE, 0, 0, declared + 1L, declared, declared, declared, 0, null, hash("root-$name"), hash("source-$name"))
        override fun findById(id: SurfaceId): CompactSurface? = representative?.takeIf { it.id == id }
        override fun findByVoxel(voxel: Voxel): CompactSurface? = representative?.takeIf { it.voxel == voxel }
        override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int) = CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: SurfaceId): CanonicalPageRead<PagedSource?> = CanonicalPageRead.Complete(
            representative?.takeIf { it.id == id }?.let { PagedSource(it.id, it.voxel, it.packedNormal, it.normalConfidence, hash("fingerprint-$id")) }, 0, 0,
        )
        override fun visitSourceSupport(target: SurfaceId, cursor: SourceSupportCursor?, sink: (PagedSupport) -> Boolean): SourceSupportRead {
            val row = representative?.takeIf { it.id == target } ?: return SourceSupportRead.Complete(0, null, 0, 0)
            val source = PagedSource(row.id, row.voxel, row.packedNormal, row.normalConfidence, hash("fingerprint-${row.id}"))
            return SourceSupportRead.Complete(if (sink(PagedSupport(target, source))) 1 else 0, null, 0, 0)
        }
        override fun retainedMemoryReceipt(): CompactRetainedMemoryReceipt = error("unused")
        override fun allocatedStorageReceipt(): CompactStorageReceipt = error("unused")
        override fun close() = Unit
    }

    private class RecordingBudget : ExclusiveFakeStorageBudget() {
        override fun reserveBytes(bytes: Long): Any = bytes
        override fun commitBytes(token: Any, actualBytes: Long) = Unit
        override fun releaseBytes(token: Any) = Unit
        override fun allocationUnitBytes(path: File) = 4_096L
    }

    companion object {
        private fun hash(value: String) = CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))
        private fun round(bytes: Long, unit: Long) = if (bytes == 0L) 0L else ((bytes - 1L) / unit + 1L) * unit
    }
}
