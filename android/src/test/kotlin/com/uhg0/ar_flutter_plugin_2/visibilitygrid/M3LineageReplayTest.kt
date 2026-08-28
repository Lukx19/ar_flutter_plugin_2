package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3LineageReplayTest {
    @Test
    fun `create receipt reopens byte identically while preserving legacy operation ordinals`() {
        val directory = Files.createTempDirectory("m3-create-replay").toFile()
        val group = M3SurfaceGroup("create-replay")
        val create = create("bootstrap", 0, 1)
        val owner = opened(M3SurfaceOwnership.open(group, directory))
        val committed = accepted(owner.transact(create))
        owner.close()

        val reopened = opened(M3SurfaceOwnership.open(group, directory))
        val replay = accepted(reopened.transact(create))
        assertEquals(committed, replay)
        assertEquals(committed.receipt.canonicalBytes, replay.receipt.canonicalBytes)
        assertEquals(M3CanonicalTransactionRefusal.IDENTITY_CONFLICT,
            refused(reopened.transact(create("bootstrap", 2))).reason)
        assertEquals(listOf(0, 1, 2, 3, 4), M3CanonicalOperation.entries.map { it.ordinal })
        assertEquals(M3CanonicalOperation.RELOCATION, M3CanonicalOperation.entries[0])
        assertEquals(M3CanonicalOperation.REPLACEMENT, M3CanonicalOperation.entries[3])
        assertEquals(M3CanonicalOperation.CREATE, M3CanonicalOperation.entries[4])
    }

    @Test
    fun `non monotonic transaction order persists one canonically sorted lineage history`() {
        val directory = Files.createTempDirectory("m3-lineage-order").toFile()
        val group = M3SurfaceGroup("lineage-order")
        val owner = opened(M3SurfaceOwnership.open(group, directory))
        val sources = (owner.apply(M3SurfaceOwnershipCommand("seed", (0 until 5).map { x ->
            M3SurfaceCandidate(voxel = M3Voxel(x, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192)
        })) as M3SurfaceOwnershipResult.Accepted).owners
        val order = listOf(1L, 2L, 3L, 5L, 4L)
        var revision = 0L
        val receipts = order.mapIndexed { index, id ->
            accepted(owner.transact(relocate("ordered-$id", sources.single { it.id.value == id }.id, 20 + index, revision++)))
        }
        owner.close()

        val reopened = opened(M3SurfaceOwnership.open(group, directory))
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
        val directory = Files.createTempDirectory("m3-lineage-replay").toFile()
        val group = M3SurfaceGroup("replay")
        val owner = opened(M3SurfaceOwnership.open(group, directory))
        val source = seed(owner)
        val command = relocate("same", source, 10)
        val committed = accepted(owner.transact(command))
        assertEquals(committed, accepted(owner.transact(command)))
        assertEquals(M3CanonicalTransactionRefusal.IDENTITY_CONFLICT, refused(owner.transact(relocate("same", source, 11))).reason)
        owner.close()

        val reopened = opened(M3SurfaceOwnership.open(group, directory))
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
            M3SurfaceOwnershipFault.AFTER_RESERVATION_FLUSH to true,
            M3SurfaceOwnershipFault.AFTER_PRIVATE_CANDIDATE to true,
            M3SurfaceOwnershipFault.BEFORE_SNAPSHOT_FLUSH to true,
            M3SurfaceOwnershipFault.AFTER_SNAPSHOT_FILE_SYNC_BEFORE_ROOT_SWITCH to true,
            M3SurfaceOwnershipFault.AFTER_ROOT_SWITCH_BEFORE_DIRECTORY_SYNC to false,
            M3SurfaceOwnershipFault.AFTER_ROOT_DIRECTORY_SYNC to false,
        )
        cases.forEachIndexed { index, (fault, burnsReservation) ->
            val directory = Files.createTempDirectory("m3-fault-$index").toFile()
            val group = M3SurfaceGroup("fault-$index")
            val owner = opened(M3SurfaceOwnership.open(group, directory, fault = fault))
            val command = create("bootstrap", 10)
            val result = owner.transact(command)
            if (fault == M3SurfaceOwnershipFault.AFTER_ROOT_SWITCH_BEFORE_DIRECTORY_SYNC || fault == M3SurfaceOwnershipFault.AFTER_ROOT_DIRECTORY_SYNC) {
                assertEquals(1, accepted(result).receipt.geometryRevision)
            } else {
                val refusal = refused(result)
                assertEquals(M3CanonicalTransactionRefusal.DURABILITY_FAILURE, refusal.reason)
                assertEquals(0, refusal.receipt.geometryRevision)
                assertEquals(0, refusal.receipt.lineageRevision)
                assertEquals(2, refusal.receipt.nextSurfaceIdHighWater)
                assertEquals(0, refusal.receipt.liveSurfaceCount)
            }
            owner.close()
            val reopened = opened(M3SurfaceOwnership.open(group, directory))
            if (fault == M3SurfaceOwnershipFault.AFTER_ROOT_SWITCH_BEFORE_DIRECTORY_SYNC || fault == M3SurfaceOwnershipFault.AFTER_ROOT_DIRECTORY_SYNC) {
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
        val receiptOwner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("receipt"), M3SurfaceOwnershipConfiguration(transactionCapacity = 1)))
        val first = seed(receiptOwner)
        accepted(receiptOwner.transact(relocate("one", first, 1)))
        val second = refused(receiptOwner.transact(relocate("two", first, 2, revision = 1)))
        assertEquals(M3CanonicalTransactionRefusal.JOURNAL_EXHAUSTED, second.reason)
        assertEquals(1, second.receipt.geometryRevision)

        val measuring = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("bytes")))
        val measuredSource = seed(measuring)
        val exactSize = accepted(measuring.transact(relocate("bounded", measuredSource, 1))).receipt.canonicalBytes.size
        assertEquals(239, exactSize)
        val exactEntrySize = exactSize + 68
        val exact = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("bytes"), M3SurfaceOwnershipConfiguration(changeJournalByteCapacity = exactEntrySize)))
        accepted(exact.transact(relocate("bounded", seed(exact), 1)))
        val over = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("bytes"), M3SurfaceOwnershipConfiguration(changeJournalByteCapacity = exactEntrySize - 1)))
        assertEquals(M3CanonicalTransactionRefusal.JOURNAL_EXHAUSTED, refused(over.transact(relocate("bounded", seed(over), 1))).reason)
    }

    @Test
    fun `apply transact and close share one interface lock under true interleaving`() {
        val owner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("interleave")))
        val source = seed(owner)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val transaction = executor.submit<M3CanonicalTransactionResult> { start.await(); owner.transact(relocate("move", source, 10)) }
            val apply = executor.submit<M3SurfaceOwnershipResult> { start.await(); owner.apply(M3SurfaceOwnershipCommand("apply", listOf(M3SurfaceCandidate(voxel = M3Voxel(20, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192)))) }
            val close = executor.submit<M3SurfaceOwnershipCloseResult> { start.await(); owner.close() }
            start.countDown()
            val transactionResult = transaction.get(5, TimeUnit.SECONDS)
            val applyResult = apply.get(5, TimeUnit.SECONDS)
            assertEquals(M3SurfaceOwnershipCloseResult.Closed, close.get(5, TimeUnit.SECONDS))
            assertTrue(transactionResult is M3CanonicalTransactionResult.Accepted || (transactionResult as M3CanonicalTransactionResult.Refused).reason == M3CanonicalTransactionRefusal.CLOSED)
            assertTrue(applyResult is M3SurfaceOwnershipResult.Accepted || (applyResult as M3SurfaceOwnershipResult.Refused).reason == M3SurfaceOwnershipRefusal.CLOSED)
            assertEquals(M3CanonicalTransactionRefusal.CLOSED, refused(owner.transact(relocate("after", source, 30))).reason)
        } finally { executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS) }
    }

    private fun seed(owner: M3SurfaceOwnership): M3SurfaceId =
        (owner.apply(M3SurfaceOwnershipCommand("seed", listOf(M3SurfaceCandidate(voxel = M3Voxel(0, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192)))) as M3SurfaceOwnershipResult.Accepted).owners.single().id
    private fun relocate(id: String, source: M3SurfaceId, x: Int, revision: Long = 0) = M3CanonicalTransactionCommand(
        id, M3CanonicalOperation.RELOCATION, revision, revision, listOf(source),
        listOf(M3CanonicalTarget(source, M3Voxel(x, 0, 0), 0, 0, 192)),
    )
    private fun create(id: String, vararg xs: Int) = M3CanonicalTransactionCommand(
        id, M3CanonicalOperation.CREATE, 0, 0, emptyList(),
        xs.map { M3CanonicalTarget(voxel = M3Voxel(it, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192) },
    )
    private fun opened(result: M3SurfaceOwnershipOpenResult) = (result as M3SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: M3CanonicalTransactionResult) = result as M3CanonicalTransactionResult.Accepted
    private fun refused(result: M3CanonicalTransactionResult) = result as M3CanonicalTransactionResult.Refused
}
