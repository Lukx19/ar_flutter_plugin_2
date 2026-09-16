package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalTransactionTest {
    @Test
    fun `create bootstrap is transact only and publishes a mixed revision cut`() {
        val owner = opened(SurfaceOwnership.inMemory(SurfaceGroup("create")))
        val create = create("bootstrap", 0, 1)

        val committed = accepted(owner.transact(create))
        assertEquals(CanonicalOperation.CREATE, committed.receipt.kind)
        assertEquals(listOf(1L, 2L), committed.targets.map { it.id.value })
        assertEquals(emptyList<SurfaceId>(), committed.receipt.removedSurfaceIds)
        assertEquals(emptyList<LineageEdge>(), committed.receipt.lineageEdges)
        assertEquals(emptyList<ImmutableSourceSupport>(), committed.receipt.sourceSupport)
        assertTrue(committed.receipt.canonicalBytes.size > 0)
        assertCut(committed, 1, 0, 3, 2)

        // This first qualified-surface path deliberately crosses only transact;
        // an integration bootstrap cannot need the legacy apply path to obtain IDs.
        assertEquals(committed, accepted(owner.transact(create)))
        assertEquals(committed.receipt.canonicalBytes, accepted(owner.transact(create)).receipt.canonicalBytes)
        assertEquals(CanonicalTransactionRefusal.IDENTITY_CONFLICT,
            refused(owner.transact(create("bootstrap", 2))).reason)

        // Trap the legacy bootstrap path with the same durable command identity.
        // CREATE owns that identity in the transaction journal, so apply cannot
        // have run first and cannot be used afterward to mutate the committed cut.
        val applyTrap = owner.apply(SurfaceOwnershipCommand("bootstrap", listOf(candidate(99))))
        assertEquals(SurfaceOwnershipRefusal.IDENTITY_CONFLICT,
            (applyTrap as SurfaceOwnershipResult.Refused).reason)
        assertEquals(committed, accepted(owner.transact(create)))

        val relocated = accepted(owner.transact(command(
            "after-create", CanonicalOperation.RELOCATION, 1, 0, listOf(committed.targets.first().id), target(10, committed.targets.first().id),
        )))
        assertCut(relocated, 2, 1, 3, 2)
        assertEquals(listOf(committed.targets.first().id), relocated.receipt.sourceSupport.map { it.id })
        assertEquals(listOf(Voxel(0, 0, 0)), relocated.receipt.sourceSupport.map { it.voxel })
    }

    @Test
    fun `invalid and exhausted create commands retain the empty authority cut`() {
        fun empty(result: CanonicalTransactionResult) = assertEquals(CanonicalStateReceipt(0, 0, 1, 0), refused(result).receipt)

        val invalidSources = opened(SurfaceOwnership.inMemory(SurfaceGroup("create-invalid-sources")))
        assertEquals(CanonicalTransactionRefusal.INVALID_COMMAND,
            refused(invalidSources.transact(command("sources", CanonicalOperation.CREATE, 0, 0, listOf(SurfaceId(1)), target(0)))).reason)
        empty(invalidSources.transact(command("sources-2", CanonicalOperation.CREATE, 0, 0, listOf(SurfaceId(1)), target(0))))

        val explicitId = opened(SurfaceOwnership.inMemory(SurfaceGroup("create-explicit-id")))
        assertEquals(CanonicalTransactionRefusal.INVALID_COMMAND,
            refused(explicitId.transact(command("id", CanonicalOperation.CREATE, 0, 0, emptyList(), target(0, SurfaceId(1))))).reason)
        empty(explicitId.transact(command("id-2", CanonicalOperation.CREATE, 0, 0, emptyList(), target(0, SurfaceId(1)))))

        val capacity = opened(SurfaceOwnership.inMemory(SurfaceGroup("create-capacity"), SurfaceOwnershipConfiguration(surfaceCapacity = 1)))
        assertEquals(CanonicalTransactionRefusal.CAPACITY,
            refused(capacity.transact(create("capacity", 0, 1))).reason)
        empty(capacity.transact(create("capacity-retry", 0, 1)))

        val revisions = opened(SurfaceOwnership.inMemory(SurfaceGroup("create-revision"), SurfaceOwnershipConfiguration(revisionLimit = 0)))
        assertEquals(CanonicalTransactionRefusal.REVISION_EXHAUSTED,
            refused(revisions.transact(create("revision", 0))).reason)
        empty(revisions.transact(create("revision-retry", 0)))

        val journal = opened(SurfaceOwnership.inMemory(SurfaceGroup("create-journal"), SurfaceOwnershipConfiguration(changeJournalByteCapacity = 1)))
        assertEquals(CanonicalTransactionRefusal.JOURNAL_EXHAUSTED,
            refused(journal.transact(create("journal", 0))).reason)
        empty(journal.transact(create("journal-retry", 0)))
    }

    @Test
    fun `relocate merge split and replace commit exact cuts and immutable support`() {
        val owner = opened(SurfaceOwnership.inMemory(SurfaceGroup("canonical")))
        val initial = ownership(owner, "seed", 0, 1, 2, 3)

        val relocated = accepted(owner.transact(command("relocate", CanonicalOperation.RELOCATION, 0, listOf(initial[0].id), target(10, initial[0].id))))
        assertEquals(initial[0].id, relocated.targets.single().id)
        assertEquals(listOf(LineageEdge(initial[0].id, initial[0].id)), relocated.receipt.lineageEdges)
        assertCut(relocated, 1, 1, 5, 4)

        val merged = accepted(owner.transact(command("merge", CanonicalOperation.MERGE, 1, listOf(initial[1].id, initial[2].id), target(20))))
        assertEquals(5, merged.targets.single().id.value)
        assertEquals(listOf(
            LineageEdge(initial[1].id, merged.targets.single().id),
            LineageEdge(initial[2].id, merged.targets.single().id),
        ), merged.receipt.lineageEdges)
        assertCut(merged, 2, 2, 6, 3)

        val split = accepted(owner.transact(command("split", CanonicalOperation.SPLIT, 2, listOf(merged.targets.single().id), target(30), target(31))))
        assertEquals(listOf(6L, 7L), split.targets.map { it.id.value })
        assertEquals(listOf(5L, 5L), split.receipt.lineageEdges.map { it.source.value })
        assertEquals(listOf(6L, 7L), split.receipt.lineageEdges.map { it.target.value })
        assertEquals(listOf(initial[1].id, initial[2].id), split.receipt.sourceSupport.map { it.id })
        assertEquals(listOf(initial[1].voxel, initial[2].voxel), split.receipt.sourceSupport.map { it.voxel })
        assertCut(split, 3, 3, 8, 4)

        val replaced = accepted(owner.transact(command("replace", CanonicalOperation.REPLACEMENT, 3, listOf(initial[3].id), target(40))))
        assertEquals(8, replaced.targets.single().id.value)
        assertEquals(listOf(LineageEdge(initial[3].id, replaced.targets.single().id)), replaced.receipt.lineageEdges)
        assertCut(replaced, 4, 4, 9, 4)
    }

    @Test
    fun `all successors capacities and journals preflight without mutation`() {
        val capacityOwner = opened(SurfaceOwnership.inMemory(SurfaceGroup("capacity"), SurfaceOwnershipConfiguration(surfaceCapacity = 2)))
        val sources = ownership(capacityOwner, "seed", 0)
        val capacity = refused(capacityOwner.transact(command("too-many", CanonicalOperation.SPLIT, 0, listOf(sources.single().id), target(1), target(2), target(3))))
        assertEquals(CanonicalTransactionRefusal.CAPACITY, capacity.reason)
        assertEquals(CanonicalStateReceipt(0, 0, 2, 1), capacity.receipt)

        val lineageOwner = opened(SurfaceOwnership.inMemory(SurfaceGroup("lineage"), SurfaceOwnershipConfiguration(lineageCapacity = 1)))
        val lineageSource = ownership(lineageOwner, "seed", 0).single()
        val lineage = refused(lineageOwner.transact(command("split", CanonicalOperation.SPLIT, 0, listOf(lineageSource.id), target(1), target(2))))
        assertEquals(CanonicalTransactionRefusal.LINEAGE_EXHAUSTED, lineage.reason)
        assertEquals(CanonicalStateReceipt(0, 0, 2, 1), lineage.receipt)

        val revision = refused(lineageOwner.transact(command("stale", CanonicalOperation.RELOCATION, 1, listOf(lineageSource.id), target(1, lineageSource.id))))
        assertEquals(CanonicalTransactionRefusal.REVISION_CONFLICT, revision.reason)

        val exhaustedOwner = opened(SurfaceOwnership.inMemory(SurfaceGroup("revision-exhausted"), SurfaceOwnershipConfiguration(revisionLimit = 0)))
        val exhaustedSource = ownership(exhaustedOwner, "seed", 0).single()
        assertEquals(CanonicalTransactionRefusal.REVISION_EXHAUSTED,
            refused(exhaustedOwner.transact(command("exhausted", CanonicalOperation.RELOCATION, 0, listOf(exhaustedSource.id), target(1, exhaustedSource.id)))).reason)
    }

    @Test
    fun `concurrent transactions serialize with apply and close`() {
        val owner = opened(SurfaceOwnership.inMemory(SurfaceGroup("serial")))
        val sources = ownership(owner, "seed", 0, 1, 2, 3, 4, 5, 6, 7)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val results = Collections.synchronizedList(mutableListOf<CanonicalTransactionResult>())
            val tasks = sources.mapIndexed { index, source -> executor.submit {
                start.await()
                results += owner.transact(command("move-$index", CanonicalOperation.RELOCATION, 0, listOf(source.id), target(100 + index, source.id)))
            } }
            start.countDown(); tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is CanonicalTransactionResult.Accepted })
            assertEquals(7, results.count { it is CanonicalTransactionResult.Refused && it.reason == CanonicalTransactionRefusal.REVISION_CONFLICT })
            assertEquals(SurfaceOwnershipCloseResult.Closed, owner.close())
            assertEquals(CanonicalTransactionRefusal.CLOSED, refused(owner.transact(command("closed", CanonicalOperation.RELOCATION, 1, listOf(sources[1].id), target(200, sources[1].id)))).reason)
            assertEquals(SurfaceOwnershipRefusal.CLOSED, (owner.apply(SurfaceOwnershipCommand("apply-closed", listOf(candidate(300)))) as SurfaceOwnershipResult.Refused).reason)
        } finally {
            executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun command(id: String, kind: CanonicalOperation, revision: Long, sources: List<SurfaceId>, vararg targets: CanonicalTarget) =
        command(id, kind, revision, revision, sources, *targets)
    private fun command(id: String, kind: CanonicalOperation, geometryRevision: Long, lineageRevision: Long, sources: List<SurfaceId>, vararg targets: CanonicalTarget) =
        CanonicalTransactionCommand(id, kind, geometryRevision, lineageRevision, sources, targets.toList())
    private fun create(id: String, vararg xs: Int) =
        command(id, CanonicalOperation.CREATE, 0, 0, emptyList(), *xs.map(::target).toTypedArray())
    private fun target(x: Int, id: SurfaceId? = null) = CanonicalTarget(id, Voxel(x, 0, 0), 0, 0, 192)
    private fun candidate(x: Int) = SurfaceCandidate(voxel = Voxel(x, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192)
    private fun ownership(owner: SurfaceOwnership, id: String, vararg xs: Int) = (owner.apply(SurfaceOwnershipCommand(id, xs.map(::candidate))) as SurfaceOwnershipResult.Accepted).owners
    private fun opened(result: SurfaceOwnershipOpenResult) = (result as SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: CanonicalTransactionResult) = result as CanonicalTransactionResult.Accepted
    private fun refused(result: CanonicalTransactionResult) = result as CanonicalTransactionResult.Refused
    private fun assertCut(result: CanonicalTransactionResult.Accepted, geometry: Long, lineage: Long, high: Long, live: Int) {
        assertEquals(geometry, result.receipt.geometryRevision); assertEquals(lineage, result.receipt.lineageRevision)
        assertEquals(high, result.receipt.nextSurfaceIdHighWater); assertEquals(live, result.receipt.liveSurfaceCount)
    }
}
