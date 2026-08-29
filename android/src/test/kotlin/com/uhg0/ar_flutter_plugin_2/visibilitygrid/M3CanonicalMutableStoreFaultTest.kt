package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CanonicalMutableStoreFaultTest {
    @Test
    fun `generation zero is used only without a selector and selected corruption never falls back`() {
        val directory = Files.createTempDirectory("m3-selector-corrupt").toFile()
        try {
            val base = EmptyView("corrupt")
            val budget = RecordingBudget()
            val store = requireNotNull(M3CanonicalMutableStore.open(File(directory, "generations"), budget))
            assertTrue(store.reopen(base) is M3CanonicalReopenResult.GenerationZero)
            val generation = prepared(store.stage(intent(base, "create", File(directory, "intent")), base)).generation
            val published = store.publish(generation, base)
            assertTrue(published.toString(), published is M3CanonicalPublishResult.Committed)
            val reopened = store.reopen(base) as M3CanonicalReopenResult.Selected
            assertEquals(1, reopened.commit.view.cut.liveSurfaceCount)
            reopened.commit.close()

            File(File(directory, "generations"), "m3-root-selector").writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(M3CanonicalSelectorRefusal.CORRUPT_SELECTED_ROOT, (store.reopen(base) as M3CanonicalReopenResult.Refused).reason)
            store.close()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `every selector cut reopens exactly old or new and post switch never reverts`() {
        M3CanonicalSelectorFault.entries.forEach { fault ->
            val directory = Files.createTempDirectory("m3-selector-${fault.name}").toFile()
            try {
                val base = EmptyView("fault-${fault.name}")
                val parent = File(directory, "generations")
                val budget = RecordingBudget()
                val store = requireNotNull(M3CanonicalMutableStore.open(parent, budget))
                val generation = prepared(store.stage(intent(base, "create", File(directory, "intent")), base)).generation
                val result = store.publish(generation, base, fault)
                val reopenedStore = requireNotNull(M3CanonicalMutableStore.open(parent, budget))
                when (result) {
                    is M3CanonicalPublishResult.UnknownAfterSwitch -> {
                        val reopened = reopenedStore.reopen(base) as M3CanonicalReopenResult.Selected
                        assertEquals("$fault must retain the new cut", 1, reopened.commit.view.cut.liveSurfaceCount)
                        reopened.commit.close()
                    }
                    is M3CanonicalPublishResult.Refused -> {
                        val reopened = reopenedStore.reopen(base)
                        assertTrue("$fault returned $result then $reopened", reopened is M3CanonicalReopenResult.GenerationZero)
                    }
                    is M3CanonicalPublishResult.Committed -> {
                        val reopened = reopenedStore.reopen(base) as M3CanonicalReopenResult.Selected
                        assertEquals(1, reopened.commit.view.cut.liveSurfaceCount)
                        reopened.commit.close()
                    }
                }
                reopenedStore.close(); store.close()
            } finally { directory.deleteRecursively() }
        }
    }

    @Test
    fun `selector chains generations and exact command replay conflicts on changed identity`() {
        val directory = Files.createTempDirectory("m3-selector-chain").toFile()
        try {
            val parent = File(directory, "generations")
            val base = EmptyView("chain")
            val store = requireNotNull(M3CanonicalMutableStore.open(parent, RecordingBudget()))
            val first = prepared(store.stage(intent(base, "first", File(directory, "intent-1")), base)).generation
            val firstCommit = (store.publish(first, base) as M3CanonicalPublishResult.Committed).commit
            assertEquals(2L, firstCommit.view.cut.nextSurfaceIdHighWater)
            val second = prepared(store.stage(intent(firstCommit.view, "second", File(directory, "intent-2")), firstCommit.view)).generation
            assertTrue(store.publish(second, base) is M3CanonicalPublishResult.Committed)
            firstCommit.close()

            val reopened = (store.reopen(base) as M3CanonicalReopenResult.Selected).commit
            assertEquals(2, reopened.view.cut.liveSurfaceCount)
            assertEquals(3L, reopened.view.cut.nextSurfaceIdHighWater)
            assertNotNull(reopened.view.findById(M3SurfaceId(1)))
            assertNotNull(reopened.view.findById(M3SurfaceId(2)))

            val replay = store.publish(second, base) as M3CanonicalPublishResult.Committed
            assertTrue(replay.replayed)
            replay.commit.close()
            val changedBase = EmptyView("changed")
            val changed = prepared(store.stage(intent(changedBase, "second", File(directory, "intent-conflict")), changedBase)).generation
            assertEquals(M3CanonicalSelectorRefusal.IDENTITY_CONFLICT, (store.publish(changed, base) as M3CanonicalPublishResult.Refused).reason)
            reopened.close(); store.close()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `publication receipt is pointer bounded and cleanup removes only unreachable valid generations`() {
        val directory = Files.createTempDirectory("m3-selector-bounds").toFile()
        try {
            fun publish(name: String, declared: Int): M3CanonicalPublicationReceipt {
                val root = File(directory, name)
                val base = EmptyView(name, declared)
                val store = requireNotNull(M3CanonicalMutableStore.open(root, RecordingBudget()))
                val selected = prepared(store.stage(intent(base, "command", File(directory, "$name-intent")), base)).generation
                val orphanBase = EmptyView("$name-orphan", declared)
                val orphan = prepared(store.stage(intent(orphanBase, "orphan", File(directory, "$name-orphan-intent")), orphanBase)).generation
                val invalidGeneration = File(root, "m3-cow-command-${"a".repeat(64)}").also { it.mkdirs(); File(it, "partial").writeText("invalid") }
                val invalidRoot = File(root, "m3-selector-root-${"b".repeat(64)}.root").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
                val result = store.publish(selected, base) as M3CanonicalPublishResult.Committed
                assertFalse("unreachable complete generation survived cleanup", orphan.directory.exists())
                assertTrue(selected.directory.exists())
                assertTrue("unvalidated generation was destructively cleaned", invalidGeneration.exists())
                assertTrue("unvalidated root was destructively cleaned", invalidRoot.exists())
                val receipt = result.commit.receipt
                result.commit.close(); store.close()
                return receipt
            }
            val small = publish("small", 0)
            val dense = publish("dense", 100_000)
            assertEquals(small.pointerBytes, dense.pointerBytes)
            assertEquals(small.hashOperations, dense.hashOperations)
            assertEquals(small.rootOperations, dense.rootOperations)
            assertTrue(dense.phasePeakBytes <= 1_048_576L)
            assertTrue(dense.maximumPhysicalBytes >= dense.committedPhysicalBytes)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `fixed selector codecs fail closed for every selected authority while stale slot is ignored`() {
        val directory = Files.createTempDirectory("m3-selector-codecs").toFile()
        try {
            val parent = File(directory, "generations")
            val base = EmptyView("codecs")
            val store = requireNotNull(M3CanonicalMutableStore.open(parent, RecordingBudget()))
            val generation = prepared(store.stage(intent(base, "codec", File(directory, "intent")), base)).generation
            val result = store.publish(generation, base) as M3CanonicalPublishResult.Committed
            result.commit.close()
            val selector = File(parent, "m3-root-selector")
            val selectedSlot = File(parent, if (selector.readBytes()[8].toInt() == 0) "m3-root-A.slot" else "m3-root-B.slot")
            val staleSlot = File(parent, if (selectedSlot.name.endsWith("A.slot")) "m3-root-B.slot" else "m3-root-A.slot")
            val root = parent.listFiles().orEmpty().single { it.name.matches(Regex("m3-selector-root-[0-9a-f]{64}\\.root")) }
            assertEquals(88L, selector.length()); assertEquals(80L, selectedSlot.length()); assertEquals(352L, root.length())

            staleSlot.writeBytes(byteArrayOf(1, 2, 3))
            (store.reopen(base) as M3CanonicalReopenResult.Selected).commit.close()
            listOf(selector, selectedSlot, root, File(generation.directory, M3CanonicalCowGeneration.CURRENT_UNACKED_FILE)).forEach { authority ->
                val original = authority.readBytes()
                authority.writeBytes(original.copyOf(original.size / 2))
                assertEquals(authority.name, M3CanonicalSelectorRefusal.CORRUPT_SELECTED_ROOT, (store.reopen(base) as M3CanonicalReopenResult.Refused).reason)
                authority.writeBytes(original)
                (store.reopen(base) as M3CanonicalReopenResult.Selected).commit.close()
            }
            store.close()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `unknown after switch is resolved by exact lookup and changed current conflicts`() {
        val directory = Files.createTempDirectory("m3-selector-lookup").toFile()
        try {
            val parent = File(directory, "generations")
            val base = EmptyView("lookup")
            val store = requireNotNull(M3CanonicalMutableStore.open(parent, RecordingBudget()))
            val generation = prepared(store.stage(intent(base, "lookup-command", File(directory, "intent")), base)).generation
            val query = M3CanonicalCommitQuery.from(generation)
            assertTrue(store.publish(generation, base, M3CanonicalSelectorFault.AFTER_SELECTOR_SWITCH) is M3CanonicalPublishResult.UnknownAfterSwitch)
            val found = store.lookupCommit(query, base) as M3CanonicalCommitLookup.Found
            assertEquals(generation.root.current, found.commit.current)
            assertEquals(generation.root.current, found.commit.roots.single().current)
            found.commit.close()
            val changed = query.copy(current = query.current.copy(length = query.current.length + 1L))
            assertEquals(M3CanonicalSelectorRefusal.IDENTITY_CONFLICT, (store.lookupCommit(changed, base) as M3CanonicalCommitLookup.Refused).reason)
            store.close()
            assertEquals(M3CanonicalSelectorRefusal.CLOSED, (store.lookupCommit(query, base) as M3CanonicalCommitLookup.Refused).reason)
            store.close()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `ancestry limit refuses without wrap or selected authority change`() {
        val directory = Files.createTempDirectory("m3-selector-limit").toFile()
        try {
            val parent = File(directory, "generations")
            val base = EmptyView("limit")
            val budget = RecordingBudget()
            val store = requireNotNull(M3CanonicalMutableStore.open(parent, budget))
            val selector = M3PrivateRootSelector(parent, budget, maximumGenerations = 1)
            val first = prepared(store.stage(intent(base, "first", File(directory, "intent-1")), base)).generation
            val firstCommit = (selector.publish(first, base, null) as M3CanonicalPublishResult.Committed).commit
            val second = prepared(store.stage(intent(firstCommit.view, "second", File(directory, "intent-2")), firstCommit.view)).generation
            assertEquals(M3CanonicalSelectorRefusal.ANCESTRY_LIMIT, (selector.publish(second, base, null) as M3CanonicalPublishResult.Refused).reason)
            val reopened = selector.reopen(base) as M3CanonicalReopenResult.Selected
            assertEquals(1, reopened.commit.view.cut.liveSurfaceCount)
            reopened.commit.close(); firstCommit.close(); store.close()
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `real coordinator reconciles reservation publication cleanup and process reopen`() {
        val directory = Files.createTempDirectory("m3-selector-budget").toFile()
        try {
            val parent = File(directory, "authority").also { assertTrue(it.mkdirs()) }
            fun coordinator() = StorageBudgetCoordinatorV2(
                parent,
                StorageBudgetPolicyV2(64L * 1024 * 1024, 4_096L),
                JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
                freeBytes = { 128L * 1024 * 1024 },
            )
            val base = EmptyView("budget")
            val firstCoordinator = coordinator()
            val store = requireNotNull(M3CanonicalMutableStore.open(parent, M3CoordinatorStorageBudget(firstCoordinator)))
            val selected = prepared(store.stage(intent(base, "selected", File(directory, "intent-selected")), base)).generation
            val orphanBase = EmptyView("orphan")
            val orphan = prepared(store.stage(intent(orphanBase, "orphan", File(directory, "intent-orphan")), orphanBase)).generation
            val before = firstCoordinator.committedBytes()
            assertTrue(before >= firstCoordinator.physicallyAllocatedTreeBytes(selected.directory) + firstCoordinator.physicallyAllocatedTreeBytes(orphan.directory))
            val published = store.publish(selected, base) as M3CanonicalPublishResult.Committed
            assertFalse(orphan.directory.exists())
            assertEquals(published.commit.receipt.selectedAuthorityBytes, firstCoordinator.committedBytes())
            assertEquals(0L, firstCoordinator.reservedBytes())
            assertTrue(published.commit.receipt.cleanupReclaimedBytes > 0L)
            published.commit.close(); store.close(); firstCoordinator.close()

            val reopenedCoordinator = coordinator()
            val reopenedStore = requireNotNull(M3CanonicalMutableStore.open(parent, M3CoordinatorStorageBudget(reopenedCoordinator)))
            val reopened = reopenedStore.reopen(base) as M3CanonicalReopenResult.Selected
            assertEquals(1, reopened.commit.view.cut.liveSurfaceCount)
            assertEquals(reopened.commit.receipt.selectedAuthorityBytes, reopenedCoordinator.committedBytes())
            assertEquals(0L, reopenedCoordinator.reservedBytes())
            reopened.commit.close(); reopenedStore.close(); reopenedCoordinator.close()
        } finally { directory.deleteRecursively() }
    }

    private fun prepared(result: M3CanonicalCowStageResult) = result as M3CanonicalCowStageResult.Prepared
    private fun intent(base: M3CanonicalStateView, commandId: String, directory: File): M3PreparedIntent {
        val next = base.cut.liveSurfaceCount
        val dense = base is EmptyView && next > 0
        val existing = M3SurfaceId(1).takeIf { dense }
        val command = M3FeatureMutationCommand(commandId, base.cut.geometryRevision, base.cut.lineageRevision, M3CanonicalTarget(existing, M3Voxel(if (dense) 0 else next, 0, 0), 0, 0, if (dense) 191 else 192))
        val plan = (M3SurfaceOwnership.prepareMutation(base, M3SurfaceOwnershipConfiguration(), command) as M3CanonicalMutationPreparation.Prepared).mutation
        val journal = (M3CanonicalDirtyJournal.open(base, directory, RecordingBudget()) as M3CanonicalDirtyJournalOpenResult.Opened).journal
        return (journal.flush(plan) as M3CanonicalDirtyJournalFlushResult.Prepared).intent
    }
    private class EmptyView(name: String, declared: Int = 0) : M3CanonicalStateView {
        override val cut = M3CompactCanonicalCut(M3SurfaceGroup(name), M3CompactCanonicalStore.PROFILE, 0, 0, declared + 1L, declared, declared, declared, 0, null, hash("root-$name"), hash("source-$name"))
        private val representative = M3CompactSurface(M3SurfaceId(1), M3Voxel(0, 0, 0), 0, 192).takeIf { declared > 0 }
        override fun findById(id: M3SurfaceId): M3CompactSurface? = representative?.takeIf { it.id == id }
        override fun findByVoxel(voxel: M3Voxel): M3CompactSurface? = representative?.takeIf { it.voxel == voxel }
        override fun readPage(region: M3StorageRegion, page: Int, cursor: Int, limit: Int) = M3CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: M3SurfaceId): M3CanonicalPageRead<M3PagedSource?> = M3CanonicalPageRead.Complete(representative?.takeIf { it.id == id }?.let { M3PagedSource(it.id, it.voxel, it.packedNormal, it.normalConfidence, hash("fingerprint-$id")) }, 0, 0)
        override fun visitSourceSupport(target: M3SurfaceId, cursor: M3SourceSupportCursor?, sink: (M3PagedSupport) -> Boolean): M3SourceSupportRead {
            val row = representative?.takeIf { it.id == target } ?: return M3SourceSupportRead.Complete(0, null, 0, 0)
            val source = M3PagedSource(row.id, row.voxel, row.packedNormal, row.normalConfidence, hash("fingerprint-${row.id}"))
            return M3SourceSupportRead.Complete(if (sink(M3PagedSupport(target, source))) 1 else 0, null, 0, 0)
        }
        override fun retainedMemoryReceipt(): M3CompactRetainedMemoryReceipt = error("unused")
        override fun allocatedStorageReceipt(): M3CompactStorageReceipt = error("unused")
        override fun close() = Unit
    }
    private class RecordingBudget : M3CanonicalStorageBudget {
        override fun reserve(bytes: Long): Any = bytes
        override fun commit(token: Any, actualBytes: Long) = Unit
        override fun release(token: Any) = Unit
        override fun allocationUnitBytes(path: File) = 4_096L
    }
    companion object {
        private fun hash(value: String) = M3CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))
    }
}
