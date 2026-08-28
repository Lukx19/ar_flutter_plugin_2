package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CanonicalTransactionTest {
    @Test
    fun `create bootstrap is transact only and publishes a mixed revision cut`() {
        val owner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("create")))
        val create = create("bootstrap", 0, 1)

        val committed = accepted(owner.transact(create))
        assertEquals(M3CanonicalOperation.CREATE, committed.receipt.kind)
        assertEquals(listOf(1L, 2L), committed.targets.map { it.id.value })
        assertEquals(emptyList<M3SurfaceId>(), committed.receipt.removedSurfaceIds)
        assertEquals(emptyList<M3LineageEdge>(), committed.receipt.lineageEdges)
        assertEquals(emptyList<M3ImmutableSourceSupport>(), committed.receipt.sourceSupport)
        assertTrue(committed.receipt.canonicalBytes.size > 0)
        assertCut(committed, 1, 0, 3, 2)

        // This first qualified-surface path deliberately crosses only transact;
        // an integration bootstrap cannot need the legacy apply path to obtain IDs.
        assertEquals(committed, accepted(owner.transact(create)))
        assertEquals(committed.receipt.canonicalBytes, accepted(owner.transact(create)).receipt.canonicalBytes)
        assertEquals(M3CanonicalTransactionRefusal.IDENTITY_CONFLICT,
            refused(owner.transact(create("bootstrap", 2))).reason)

        // Trap the legacy bootstrap path with the same durable command identity.
        // CREATE owns that identity in the transaction journal, so apply cannot
        // have run first and cannot be used afterward to mutate the committed cut.
        val applyTrap = owner.apply(M3SurfaceOwnershipCommand("bootstrap", listOf(candidate(99))))
        assertEquals(M3SurfaceOwnershipRefusal.IDENTITY_CONFLICT,
            (applyTrap as M3SurfaceOwnershipResult.Refused).reason)
        assertEquals(committed, accepted(owner.transact(create)))

        val relocated = accepted(owner.transact(command(
            "after-create", M3CanonicalOperation.RELOCATION, 1, 0, listOf(committed.targets.first().id), target(10, committed.targets.first().id),
        )))
        assertCut(relocated, 2, 1, 3, 2)
        assertEquals(listOf(committed.targets.first().id), relocated.receipt.sourceSupport.map { it.id })
        assertEquals(listOf(M3Voxel(0, 0, 0)), relocated.receipt.sourceSupport.map { it.voxel })
    }

    @Test
    fun `invalid and exhausted create commands retain the empty authority cut`() {
        fun empty(result: M3CanonicalTransactionResult) = assertEquals(M3CanonicalStateReceipt(0, 0, 1, 0), refused(result).receipt)

        val invalidSources = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("create-invalid-sources")))
        assertEquals(M3CanonicalTransactionRefusal.INVALID_COMMAND,
            refused(invalidSources.transact(command("sources", M3CanonicalOperation.CREATE, 0, 0, listOf(M3SurfaceId(1)), target(0)))).reason)
        empty(invalidSources.transact(command("sources-2", M3CanonicalOperation.CREATE, 0, 0, listOf(M3SurfaceId(1)), target(0))))

        val explicitId = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("create-explicit-id")))
        assertEquals(M3CanonicalTransactionRefusal.INVALID_COMMAND,
            refused(explicitId.transact(command("id", M3CanonicalOperation.CREATE, 0, 0, emptyList(), target(0, M3SurfaceId(1))))).reason)
        empty(explicitId.transact(command("id-2", M3CanonicalOperation.CREATE, 0, 0, emptyList(), target(0, M3SurfaceId(1)))))

        val capacity = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("create-capacity"), M3SurfaceOwnershipConfiguration(surfaceCapacity = 1)))
        assertEquals(M3CanonicalTransactionRefusal.CAPACITY,
            refused(capacity.transact(create("capacity", 0, 1))).reason)
        empty(capacity.transact(create("capacity-retry", 0, 1)))

        val revisions = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("create-revision"), M3SurfaceOwnershipConfiguration(revisionLimit = 0)))
        assertEquals(M3CanonicalTransactionRefusal.REVISION_EXHAUSTED,
            refused(revisions.transact(create("revision", 0))).reason)
        empty(revisions.transact(create("revision-retry", 0)))

        val journal = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("create-journal"), M3SurfaceOwnershipConfiguration(changeJournalByteCapacity = 1)))
        assertEquals(M3CanonicalTransactionRefusal.JOURNAL_EXHAUSTED,
            refused(journal.transact(create("journal", 0))).reason)
        empty(journal.transact(create("journal-retry", 0)))
    }

    @Test
    fun `relocate merge split and replace commit exact cuts and immutable support`() {
        val owner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("canonical")))
        val initial = ownership(owner, "seed", 0, 1, 2, 3)

        val relocated = accepted(owner.transact(command("relocate", M3CanonicalOperation.RELOCATION, 0, listOf(initial[0].id), target(10, initial[0].id))))
        assertEquals(initial[0].id, relocated.targets.single().id)
        assertEquals(listOf(M3LineageEdge(initial[0].id, initial[0].id)), relocated.receipt.lineageEdges)
        assertCut(relocated, 1, 1, 5, 4)

        val merged = accepted(owner.transact(command("merge", M3CanonicalOperation.MERGE, 1, listOf(initial[1].id, initial[2].id), target(20))))
        assertEquals(5, merged.targets.single().id.value)
        assertEquals(listOf(
            M3LineageEdge(initial[1].id, merged.targets.single().id),
            M3LineageEdge(initial[2].id, merged.targets.single().id),
        ), merged.receipt.lineageEdges)
        assertCut(merged, 2, 2, 6, 3)

        val split = accepted(owner.transact(command("split", M3CanonicalOperation.SPLIT, 2, listOf(merged.targets.single().id), target(30), target(31))))
        assertEquals(listOf(6L, 7L), split.targets.map { it.id.value })
        assertEquals(listOf(5L, 5L), split.receipt.lineageEdges.map { it.source.value })
        assertEquals(listOf(6L, 7L), split.receipt.lineageEdges.map { it.target.value })
        assertEquals(listOf(initial[1].id, initial[2].id), split.receipt.sourceSupport.map { it.id })
        assertEquals(listOf(initial[1].voxel, initial[2].voxel), split.receipt.sourceSupport.map { it.voxel })
        assertCut(split, 3, 3, 8, 4)

        val replaced = accepted(owner.transact(command("replace", M3CanonicalOperation.REPLACEMENT, 3, listOf(initial[3].id), target(40))))
        assertEquals(8, replaced.targets.single().id.value)
        assertEquals(listOf(M3LineageEdge(initial[3].id, replaced.targets.single().id)), replaced.receipt.lineageEdges)
        assertCut(replaced, 4, 4, 9, 4)
    }

    @Test
    fun `all successors capacities and journals preflight without mutation`() {
        val capacityOwner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("capacity"), M3SurfaceOwnershipConfiguration(surfaceCapacity = 2)))
        val sources = ownership(capacityOwner, "seed", 0)
        val capacity = refused(capacityOwner.transact(command("too-many", M3CanonicalOperation.SPLIT, 0, listOf(sources.single().id), target(1), target(2), target(3))))
        assertEquals(M3CanonicalTransactionRefusal.CAPACITY, capacity.reason)
        assertEquals(M3CanonicalStateReceipt(0, 0, 2, 1), capacity.receipt)

        val lineageOwner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("lineage"), M3SurfaceOwnershipConfiguration(lineageCapacity = 1)))
        val lineageSource = ownership(lineageOwner, "seed", 0).single()
        val lineage = refused(lineageOwner.transact(command("split", M3CanonicalOperation.SPLIT, 0, listOf(lineageSource.id), target(1), target(2))))
        assertEquals(M3CanonicalTransactionRefusal.LINEAGE_EXHAUSTED, lineage.reason)
        assertEquals(M3CanonicalStateReceipt(0, 0, 2, 1), lineage.receipt)

        val revision = refused(lineageOwner.transact(command("stale", M3CanonicalOperation.RELOCATION, 1, listOf(lineageSource.id), target(1, lineageSource.id))))
        assertEquals(M3CanonicalTransactionRefusal.REVISION_CONFLICT, revision.reason)

        val exhaustedOwner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("revision-exhausted"), M3SurfaceOwnershipConfiguration(revisionLimit = 0)))
        val exhaustedSource = ownership(exhaustedOwner, "seed", 0).single()
        assertEquals(M3CanonicalTransactionRefusal.REVISION_EXHAUSTED,
            refused(exhaustedOwner.transact(command("exhausted", M3CanonicalOperation.RELOCATION, 0, listOf(exhaustedSource.id), target(1, exhaustedSource.id)))).reason)
    }

    @Test
    fun `concurrent transactions serialize with apply and close`() {
        val owner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("serial")))
        val sources = ownership(owner, "seed", 0, 1, 2, 3, 4, 5, 6, 7)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val results = Collections.synchronizedList(mutableListOf<M3CanonicalTransactionResult>())
            val tasks = sources.mapIndexed { index, source -> executor.submit {
                start.await()
                results += owner.transact(command("move-$index", M3CanonicalOperation.RELOCATION, 0, listOf(source.id), target(100 + index, source.id)))
            } }
            start.countDown(); tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is M3CanonicalTransactionResult.Accepted })
            assertEquals(7, results.count { it is M3CanonicalTransactionResult.Refused && it.reason == M3CanonicalTransactionRefusal.REVISION_CONFLICT })
            assertEquals(M3SurfaceOwnershipCloseResult.Closed, owner.close())
            assertEquals(M3CanonicalTransactionRefusal.CLOSED, refused(owner.transact(command("closed", M3CanonicalOperation.RELOCATION, 1, listOf(sources[1].id), target(200, sources[1].id)))).reason)
            assertEquals(M3SurfaceOwnershipRefusal.CLOSED, (owner.apply(M3SurfaceOwnershipCommand("apply-closed", listOf(candidate(300)))) as M3SurfaceOwnershipResult.Refused).reason)
        } finally {
            executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun command(id: String, kind: M3CanonicalOperation, revision: Long, sources: List<M3SurfaceId>, vararg targets: M3CanonicalTarget) =
        command(id, kind, revision, revision, sources, *targets)
    private fun command(id: String, kind: M3CanonicalOperation, geometryRevision: Long, lineageRevision: Long, sources: List<M3SurfaceId>, vararg targets: M3CanonicalTarget) =
        M3CanonicalTransactionCommand(id, kind, geometryRevision, lineageRevision, sources, targets.toList())
    private fun create(id: String, vararg xs: Int) =
        command(id, M3CanonicalOperation.CREATE, 0, 0, emptyList(), *xs.map(::target).toTypedArray())
    private fun target(x: Int, id: M3SurfaceId? = null) = M3CanonicalTarget(id, M3Voxel(x, 0, 0), 0, 0, 192)
    private fun candidate(x: Int) = M3SurfaceCandidate(voxel = M3Voxel(x, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192)
    private fun ownership(owner: M3SurfaceOwnership, id: String, vararg xs: Int) = (owner.apply(M3SurfaceOwnershipCommand(id, xs.map(::candidate))) as M3SurfaceOwnershipResult.Accepted).owners
    private fun opened(result: M3SurfaceOwnershipOpenResult) = (result as M3SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: M3CanonicalTransactionResult) = result as M3CanonicalTransactionResult.Accepted
    private fun refused(result: M3CanonicalTransactionResult) = result as M3CanonicalTransactionResult.Refused
    private fun assertCut(result: M3CanonicalTransactionResult.Accepted, geometry: Long, lineage: Long, high: Long, live: Int) {
        assertEquals(geometry, result.receipt.geometryRevision); assertEquals(lineage, result.receipt.lineageRevision)
        assertEquals(high, result.receipt.nextSurfaceIdHighWater); assertEquals(live, result.receipt.liveSurfaceCount)
    }
}
