package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineageReplayTest {
    @Test
    fun `create receipt reopens byte identically while preserving legacy operation ordinals`() {
        val directory = Files.createTempDirectory("canonical-surface-create-replay").toFile()
        val group = SurfaceGroup("create-replay")
        val create = create("bootstrap", 0, 1)
        val owner = opened(SurfaceOwnership.open(group, directory))
        val committed = accepted(owner.transact(create))
        owner.close()

        val reopened = opened(SurfaceOwnership.open(group, directory))
        val replay = accepted(reopened.transact(create))
        assertEquals(committed, replay)
        assertEquals(committed.receipt.canonicalBytes, replay.receipt.canonicalBytes)
        assertEquals(CanonicalTransactionRefusal.IDENTITY_CONFLICT,
            refused(reopened.transact(create("bootstrap", 2))).reason)
        assertEquals(listOf(0, 1, 2, 3, 4), CanonicalOperation.entries.map { it.ordinal })
        assertEquals(CanonicalOperation.RELOCATION, CanonicalOperation.entries[0])
        assertEquals(CanonicalOperation.REPLACEMENT, CanonicalOperation.entries[3])
        assertEquals(CanonicalOperation.CREATE, CanonicalOperation.entries[4])
    }

    @Test
    fun `non monotonic transaction order persists one canonically sorted lineage history`() {
        val directory = Files.createTempDirectory("canonical-surface-lineage-order").toFile()
        val group = SurfaceGroup("lineage-order")
        val owner = opened(SurfaceOwnership.open(group, directory))
        val sources = (owner.apply(SurfaceOwnershipCommand("seed", (0 until 5).map { x ->
            SurfaceCandidate(voxel = Voxel(x, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192)
        })) as SurfaceOwnershipResult.Accepted).owners
        val order = listOf(1L, 2L, 3L, 5L, 4L)
        var revision = 0L
        val receipts = order.mapIndexed { index, id ->
            accepted(owner.transact(relocate("ordered-$id", sources.single { it.id.value == id }.id, 20 + index, revision++)))
        }
        owner.close()

        val reopened = opened(SurfaceOwnership.open(group, directory))
        order.forEachIndexed { index, id ->
            val replay = accepted(reopened.transact(relocate("ordered-$id", sources.single { it.id.value == id }.id, 20 + index, index.toLong())))
            assertEquals(receipts[index], replay)
            assertEquals(receipts[index].receipt.canonicalBytes, replay.receipt.canonicalBytes)
        }
        assertEquals(5, receipts.last().receipt.geometryRevision)
        assertEquals(6, receipts.last().receipt.nextSurfaceIdHighWater)
        assertEquals(5, receipts.last().receipt.liveSurfaceCount)
    }

    @Test
    fun `duplicate replay is exact changed replay conflicts and receipt survives reopen`() {
        val directory = Files.createTempDirectory("canonical-surface-lineage-replay").toFile()
        val group = SurfaceGroup("replay")
        val owner = opened(SurfaceOwnership.open(group, directory))
        val source = seed(owner)
        val command = relocate("same", source, 10)
        val committed = accepted(owner.transact(command))
        assertEquals(committed, accepted(owner.transact(command)))
        assertEquals(CanonicalTransactionRefusal.IDENTITY_CONFLICT, refused(owner.transact(relocate("same", source, 11))).reason)
        owner.close()

        val reopened = opened(SurfaceOwnership.open(group, directory))
        val replay = accepted(reopened.transact(command))
        assertEquals(committed, replay)
        assertEquals(committed.receipt.lineageEdges, replay.receipt.lineageEdges)
        assertEquals(1, replay.receipt.lineageEdges.size)
        assertEquals(committed.receipt.canonicalBytes, replay.receipt.canonicalBytes)
        assertEquals(committed.receipt.canonicalBytes.toByteArray().toList(), replay.receipt.canonicalBytes.toByteArray().toList())
    }

    @Test
    fun `create reservation and snapshot fault cuts expose empty or complete cuts`() {
        val cases = listOf(
            SurfaceOwnershipFault.AFTER_RESERVATION_FLUSH to true,
            SurfaceOwnershipFault.AFTER_PRIVATE_CANDIDATE to true,
            SurfaceOwnershipFault.BEFORE_SNAPSHOT_FLUSH to true,
            SurfaceOwnershipFault.AFTER_SNAPSHOT_FILE_SYNC_BEFORE_ROOT_SWITCH to true,
            SurfaceOwnershipFault.AFTER_ROOT_SWITCH_BEFORE_DIRECTORY_SYNC to false,
            SurfaceOwnershipFault.AFTER_ROOT_DIRECTORY_SYNC to false,
        )
        cases.forEachIndexed { index, (fault, burnsReservation) ->
            val directory = Files.createTempDirectory("canonical-surface-fault-$index").toFile()
            val group = SurfaceGroup("fault-$index")
            val owner = opened(SurfaceOwnership.open(group, directory, fault = fault))
            val command = create("bootstrap", 10)
            val result = owner.transact(command)
            if (fault == SurfaceOwnershipFault.AFTER_ROOT_SWITCH_BEFORE_DIRECTORY_SYNC || fault == SurfaceOwnershipFault.AFTER_ROOT_DIRECTORY_SYNC) {
                assertEquals(1, accepted(result).receipt.geometryRevision)
            } else {
                val refusal = refused(result)
                assertEquals(CanonicalTransactionRefusal.DURABILITY_FAILURE, refusal.reason)
                assertEquals(0, refusal.receipt.geometryRevision)
                assertEquals(0, refusal.receipt.lineageRevision)
                assertEquals(2, refusal.receipt.nextSurfaceIdHighWater)
                assertEquals(0, refusal.receipt.liveSurfaceCount)
            }
            owner.close()
            val reopened = opened(SurfaceOwnership.open(group, directory))
            if (fault == SurfaceOwnershipFault.AFTER_ROOT_SWITCH_BEFORE_DIRECTORY_SYNC || fault == SurfaceOwnershipFault.AFTER_ROOT_DIRECTORY_SYNC) {
                assertEquals(result, reopened.transact(command))
            } else {
                val retry = accepted(reopened.transact(command))
                assertEquals(1, retry.receipt.geometryRevision)
                assertEquals(0, retry.receipt.lineageRevision)
                assertEquals(1, retry.receipt.liveSurfaceCount)
                assertTrue(burnsReservation)
                assertEquals(2, retry.targets.single().id.value)
                assertEquals(3, retry.receipt.nextSurfaceIdHighWater)
            }
        }
    }

    @Test
    fun `receipt and lineage journal bounds refuse deterministically`() {
        val receiptOwner = opened(SurfaceOwnership.inMemory(SurfaceGroup("receipt"), SurfaceOwnershipConfiguration(transactionCapacity = 1)))
        val first = seed(receiptOwner)
        accepted(receiptOwner.transact(relocate("one", first, 1)))
        val second = refused(receiptOwner.transact(relocate("two", first, 2, revision = 1)))
        assertEquals(CanonicalTransactionRefusal.JOURNAL_EXHAUSTED, second.reason)
        assertEquals(1, second.receipt.geometryRevision)

        val measuring = opened(SurfaceOwnership.inMemory(SurfaceGroup("bytes")))
        val measuredSource = seed(measuring)
        val exactSize = accepted(measuring.transact(relocate("bounded", measuredSource, 1))).receipt.canonicalBytes.size
        assertEquals(239, exactSize)
        val exactEntrySize = exactSize + 68
        val exact = opened(SurfaceOwnership.inMemory(SurfaceGroup("bytes"), SurfaceOwnershipConfiguration(changeJournalByteCapacity = exactEntrySize)))
        accepted(exact.transact(relocate("bounded", seed(exact), 1)))
        val over = opened(SurfaceOwnership.inMemory(SurfaceGroup("bytes"), SurfaceOwnershipConfiguration(changeJournalByteCapacity = exactEntrySize - 1)))
        assertEquals(CanonicalTransactionRefusal.JOURNAL_EXHAUSTED, refused(over.transact(relocate("bounded", seed(over), 1))).reason)
    }

    @Test
    fun `apply transact and close share one interface lock under true interleaving`() {
        val owner = opened(SurfaceOwnership.inMemory(SurfaceGroup("interleave")))
        val source = seed(owner)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val transaction = executor.submit<CanonicalTransactionResult> { start.await(); owner.transact(relocate("move", source, 10)) }
            val apply = executor.submit<SurfaceOwnershipResult> { start.await(); owner.apply(SurfaceOwnershipCommand("apply", listOf(SurfaceCandidate(voxel = Voxel(20, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192)))) }
            val close = executor.submit<SurfaceOwnershipCloseResult> { start.await(); owner.close() }
            start.countDown()
            val transactionResult = transaction.get(5, TimeUnit.SECONDS)
            val applyResult = apply.get(5, TimeUnit.SECONDS)
            assertEquals(SurfaceOwnershipCloseResult.Closed, close.get(5, TimeUnit.SECONDS))
            assertTrue(transactionResult is CanonicalTransactionResult.Accepted || (transactionResult as CanonicalTransactionResult.Refused).reason == CanonicalTransactionRefusal.CLOSED)
            assertTrue(applyResult is SurfaceOwnershipResult.Accepted || (applyResult as SurfaceOwnershipResult.Refused).reason == SurfaceOwnershipRefusal.CLOSED)
            assertEquals(CanonicalTransactionRefusal.CLOSED, refused(owner.transact(relocate("after", source, 30))).reason)
        } finally { executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS) }
    }

    private fun seed(owner: SurfaceOwnership): SurfaceId =
        (owner.apply(SurfaceOwnershipCommand("seed", listOf(SurfaceCandidate(voxel = Voxel(0, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192)))) as SurfaceOwnershipResult.Accepted).owners.single().id
    private fun relocate(id: String, source: SurfaceId, x: Int, revision: Long = 0) = CanonicalTransactionCommand(
        id, CanonicalOperation.RELOCATION, revision, revision, listOf(source),
        listOf(CanonicalTarget(source, Voxel(x, 0, 0), 0, 0, 192)),
    )
    private fun create(id: String, vararg xs: Int) = CanonicalTransactionCommand(
        id, CanonicalOperation.CREATE, 0, 0, emptyList(),
        xs.map { CanonicalTarget(voxel = Voxel(it, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192) },
    )
    private fun opened(result: SurfaceOwnershipOpenResult) = (result as SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: CanonicalTransactionResult) = result as CanonicalTransactionResult.Accepted
    private fun refused(result: CanonicalTransactionResult) = result as CanonicalTransactionResult.Refused
}
