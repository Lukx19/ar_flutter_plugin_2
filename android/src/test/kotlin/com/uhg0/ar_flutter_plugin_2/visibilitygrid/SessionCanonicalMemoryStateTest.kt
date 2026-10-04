package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCanonicalMemoryStateTest {
    @Test
    fun `feature rows refine in place and current bytes are reusable only after exact ack`() {
        val state = state()
        try {
            val first = prepare(state, FeatureMutationCommand(
                "create", state.cut.geometryRevision, state.cut.lineageRevision,
                target(null, 0),
            ))
            val firstState = commit(state, first)
            assertEquals(1, firstState.cut.liveSurfaceCount)
            assertEquals(1, firstState.cut.sourceCount)
            assertEquals(1, firstState.cut.supportCount)
            assertEquals(Voxel(0, 0, 0), state.findById(SurfaceId(1))?.voxel)

            val firstReceipt = firstState.current as CanonicalActivationCurrent.Receipt
            val firstBytes = write(firstReceipt)
            assertEquals(firstReceipt.identity.canonicalHash, sha256(firstBytes))
            assertTrue(state.acknowledge(CanonicalAcknowledgement(
                CanonicalReceiptBytes(ByteArray(32) { 7 }), firstState.cut.geometryRevision, firstState.cut.lineageRevision,
            )) is CanonicalAcknowledgementResult.NoOp)
            assertTrue(state.acknowledge(CanonicalAcknowledgement(
                firstReceipt.identity.commandHash, firstState.cut.geometryRevision, firstState.cut.lineageRevision,
            )) is CanonicalAcknowledgementResult.Acknowledged)
            assertTrue(state.acknowledge(CanonicalAcknowledgement(
                firstReceipt.identity.commandHash, firstState.cut.geometryRevision, firstState.cut.lineageRevision,
            )) is CanonicalAcknowledgementResult.Idempotent)

            val refine = prepare(state, FeatureMutationCommand(
                "refine", state.cut.geometryRevision, state.cut.lineageRevision,
                target(SurfaceId(1), 0, normal = 1, confidence = 201),
            ))
            val refined = commit(state, refine)
            assertEquals(1, refined.cut.liveSurfaceCount)
            assertEquals(1, refined.cut.sourceCount)
            assertEquals(1, refined.cut.supportCount)
            assertEquals(256, state.findById(SurfaceId(1))?.packedNormal)

            val stale = ByteArrayOutputStream()
            var staleRejected = false
            try {
                firstReceipt.source.writeTo(stale)
            } catch (_: IllegalStateException) {
                staleRejected = true
            }
            assertTrue("an acknowledged source is invalidated only after the next commit", staleRejected)
        } finally {
            state.close()
        }
    }

    @Test
    fun `merge split and removal preserve packed support and lineage after free list reuse`() {
        val state = state()
        try {
            val created = commit(state, prepare(state, transaction(state, "create", CanonicalOperation.CREATE, emptyList(), target(null, 0), target(null, 1),
            )))
            acknowledge(state, created)

            val merged = commit(state, prepare(state, transaction(
                state, "merge", CanonicalOperation.MERGE, listOf(SurfaceId(1), SurfaceId(2)), target(null, 2),
            )))
            acknowledge(state, merged)
            assertEquals(listOf(1L, 2L), supports(state, SurfaceId(3)))
            assertEquals(listOf(3L), lineage(state, SurfaceId(1)))
            assertEquals(listOf(3L), lineage(state, SurfaceId(2)))
            assertEquals(listOf(Triple(3L, 2, 2)), rendererRows(state, 0, 10))

            val split = commit(state, prepare(state, transaction(
                state, "split", CanonicalOperation.SPLIT, listOf(SurfaceId(3)), target(null, 3), target(null, 4),
            )))
            acknowledge(state, split)
            assertEquals(listOf(1L, 2L), supports(state, SurfaceId(4)))
            assertEquals(listOf(1L, 2L), supports(state, SurfaceId(5)))
            assertEquals(listOf(4L, 5L), lineage(state, SurfaceId(3)))
            assertEquals(listOf(Triple(4L, 4, 3), Triple(5L, 4, 4)), rendererRows(state, 0, 10))

            val removed = prepare(state, CanonicalEvidenceBatchCommand(
                "remove", state.cut.geometryRevision, state.cut.lineageRevision,
                listOf(DepthEvidenceChange.Remove(SurfaceId(4))),
            ))
            val afterRemoval = commit(state, removed)
            assertEquals(1, afterRemoval.cut.liveSurfaceCount)
            assertEquals(null, state.findById(SurfaceId(4)))
            assertEquals(listOf(4, 0, 0), unpackVisibilityGridKey(state.occupiedKeys().single()).toList())
            assertEquals(listOf(Triple(5L, 4, 4)), rendererRows(state, 0, 10))
            acknowledge(state, afterRemoval)

            val added = commit(state, prepare(state, CanonicalEvidenceBatchCommand(
                "reuse", state.cut.geometryRevision, state.cut.lineageRevision,
                listOf(DepthEvidenceChange.Create(target(null, 6))),
            )))
            acknowledge(state, added)
            assertEquals(listOf(Triple(5L, 4, 4)), rendererRows(state, 0, 1))
            assertEquals(listOf(Triple(6L, 4, 6)), rendererRows(state, 5, 1))
            val removedAgain = commit(state, prepare(state, CanonicalEvidenceBatchCommand(
                "remove-again", state.cut.geometryRevision, state.cut.lineageRevision,
                listOf(DepthEvidenceChange.Remove(SurfaceId(5))),
            )))
            acknowledge(state, removedAgain)
            assertEquals(listOf(Triple(6L, 4, 6)), rendererRows(state, 0, 10))
        } finally {
            state.close()
        }
    }

    @Test
    fun `source support and lineage columns cross the reusable chunk boundary`() {
        val state = state(surfaceCapacity = 1_100, lineageCapacity = 2_048)
        try {
            val targets = (0..1_024).map { target(null, it) }
            val activation = commit(state, prepare(state, transaction(
                state, "chunk-create", CanonicalOperation.CREATE, emptyList(), *targets.toTypedArray(),
            )))
            acknowledge(state, activation)
            assertEquals(1_025, state.cut.liveSurfaceCount)
            assertEquals(1_025, state.cut.sourceCount)
            assertEquals(1_025, state.cut.supportCount)
            assertEquals(Voxel(1_024, 0, 0), state.findById(SurfaceId(1_025))?.voxel)
            assertEquals(listOf(1_025L), supports(state, SurfaceId(1_025)))
            assertTrue(state.retainedMemoryReceipt().residentTotalBytes > 2L * 1_048_576L)
        } finally {
            state.close()
        }
    }

    @Test
    fun `failed oversized current serialization preserves the acknowledged bytes`() {
        val state = state(surfaceCapacity = 20_100, lineageCapacity = 64)
        try {
            val first = commit(state, prepare(state, FeatureMutationCommand(
                "seed", state.cut.geometryRevision, state.cut.lineageRevision, target(null, 0),
            )))
            acknowledge(state, first)
            val receipt = first.current as CanonicalActivationCurrent.Receipt
            val before = write(receipt)
            val workspaceBefore = state.retainedMemoryReceipt().residentTotalBytes

            val oversizedRows = (2L..20_001L).map { id ->
                SurfaceOwner(
                    SurfaceId(id), state.cut.group, Voxel(id.toInt(), 0, 0), StorageRegion(0, 0, 0),
                    0, 0, 192, ByteArray(32),
                )
            }
            val plan = PreparedCanonicalMutation(
                authorityLease = CanonicalAuthorityLease(), sourceCut = state.cut,
                commandHash = CanonicalReceiptBytes(ByteArray(32) { 3 }),
                commandFingerprint = CanonicalReceiptBytes(ByteArray(32) { 4 }), commandId = "oversized",
                kind = PreparedMutationKind.FEATURE_BATCH, rows = PreparedRowTable.from(oversizedRows),
                removedIds = LongArray(0), supports = PreparedSourceTable.EMPTY,
                supportMode = PreparedSupportMode.SELF, removedSupportRecords = 0,
                targetHighWater = 20_002L, targetLiveSurfaceCount = 20_001,
                targetSourceCount = 20_001, targetSupportCount = 20_001,
                targetLineageCount = state.cut.lineageCount,
                targetGeometryRevision = state.cut.geometryRevision + 1,
                targetLineageRevision = state.cut.lineageRevision,
                work = CanonicalMutationWork(
                    dirtyRows = 20_000, dirtyIdIndexRecords = 20_000, dirtyVoxelIndexRecords = 20_000,
                    dirtySupportRecords = 20_000, dirtySourceRecords = 20_000, dirtyLineageRecords = 0,
                    sourcePageFaults = 0, sourceBytesRead = 0, directLookupCount = 0,
                    walBytes = 0, currentBytes = 0, stagingBytes = 0,
                ),
            )
            assertEquals(PreparedMutationClaimResult.Claimed, plan.claim())
            val refused = state.commit(plan) as CanonicalAdjacentCommitResult.Refused
            assertEquals(CanonicalAdjacentCommitRefusal.COMMIT_REFUSED, refused.reason)
            plan.finish(PreparedMutationFinish.TERMINAL)
            val after = write((state.activationState().current as CanonicalActivationCurrent.Receipt))
            assertEquals(before.toList(), after.toList())
            assertEquals(first.cut, state.cut)
            assertEquals(workspaceBefore, state.retainedMemoryReceipt().residentTotalBytes)
        } finally {
            state.close()
        }
    }

    @Test
    fun `serial preparation workspace reports warm growth separately from retained capacity`() {
        val state = state()
        try {
            val first = commit(state, prepare(state, FeatureMutationCommand(
                "workspace-seed", state.cut.geometryRevision, state.cut.lineageRevision,
                target(null, 0),
            )))
            acknowledge(state, first)
            val warm = state.preparationWorkspaceReceipt()
            assertTrue(warm.growthEvents > 0)
            assertTrue(warm.ownedCapacityBytes > 0)

            val refined = commit(state, prepare(state, FeatureMutationCommand(
                "workspace-refine", state.cut.geometryRevision, state.cut.lineageRevision,
                target(SurfaceId(1), 0, normal = 1, confidence = 201),
            )))
            acknowledge(state, refined)
            val repeated = state.preparationWorkspaceReceipt()
            assertEquals(warm.growthEvents, repeated.growthEvents)
            assertEquals(warm.ownedCapacityBytes, repeated.ownedCapacityBytes)
        } finally {
            state.close()
        }
    }

    @Test
    fun `unsigned identity boundaries preserve lookup support lineage and ordered paging`() {
        for (firstId in listOf(0x7fffffffL, 0xfffffffdL)) {
            val state = state()
            try {
                // Construct a sparse-ID prepared batch to exercise the packed
                // representation without allocating billions of prior IDs.
                val rows = (0..2).map { index -> SurfaceOwner(
                    SurfaceId(firstId + index), state.cut.group, Voxel(index, 0, 0),
                    StorageRegion(0, 0, 0), 0, 65535, 255, ByteArray(32) { 3 },
                ) }
                val plan = PreparedCanonicalMutation(
                    authorityLease = CanonicalAuthorityLease(), sourceCut = state.cut,
                    commandHash = CanonicalReceiptBytes(ByteArray(32) { 3 }),
                    commandFingerprint = CanonicalReceiptBytes(ByteArray(32) { 4 }),
                    commandId = "unsigned-create", kind = PreparedMutationKind.FEATURE_BATCH,
                    rows = PreparedRowTable.from(rows), removedIds = LongArray(0),
                    supports = PreparedSourceTable.EMPTY, supportMode = PreparedSupportMode.SELF,
                    removedSupportRecords = 0, targetHighWater = firstId + 3,
                    targetLiveSurfaceCount = 3, targetSourceCount = 3, targetSupportCount = 3,
                    targetLineageCount = 0, targetGeometryRevision = state.cut.geometryRevision + 1,
                    targetLineageRevision = state.cut.lineageRevision,
                    work = CanonicalMutationWork(
                        dirtyRows = 3, dirtyIdIndexRecords = 3, dirtyVoxelIndexRecords = 3,
                        dirtySupportRecords = 3, dirtySourceRecords = 3, dirtyLineageRecords = 0,
                        sourcePageFaults = 0, sourceBytesRead = 0, directLookupCount = 0,
                        walBytes = 0, currentBytes = 0, stagingBytes = 0,
                    ),
                )
                val created = commit(state, plan)
                acknowledge(state, created)
                assertEquals((firstId..firstId + 2).toList(),
                    state.rendererPage(0, 10)!!.rows.map { it.surfaceId })
                for (id in firstId..firstId + 2) {
                    val row = state.findById(SurfaceId(id))!!
                    assertEquals(65535, row.packedNormal)
                    assertEquals(255, row.normalConfidence)
                    assertEquals(listOf(id), supports(state, SurfaceId(id)))
                }
                assertEquals(listOf(firstId + 1, firstId + 2),
                    state.rendererPage(firstId, 10)!!.rows.map { it.surfaceId })
                assertTrue(state.rendererPage(0xffffffffL, 10)!!.rows.isEmpty())
                assertTrue(state.rendererPage(0x100000000L, 10)!!.rows.isEmpty())
                if (firstId == 0x7fffffffL) {
                    val merged = commit(state, prepare(state, transaction(
                        state, "unsigned-merge", CanonicalOperation.MERGE,
                        listOf(SurfaceId(firstId), SurfaceId(firstId + 1)), target(null, 10),
                    )))
                    acknowledge(state, merged)
                    assertEquals(listOf(firstId, firstId + 1), supports(state, SurfaceId(firstId + 3)))
                    assertEquals(listOf(firstId + 3), lineage(state, SurfaceId(firstId + 1)))
                    assertEquals(listOf(firstId + 2, firstId + 3),
                        state.rendererPage(0, 10)!!.rows.map { it.surfaceId })
                }
            } finally {
                state.close()
            }
        }
    }

    private fun state(surfaceCapacity: Int = 8, lineageCapacity: Int = 16): SessionCanonicalMemoryState {
        val group = SurfaceGroup("memory-state-test")
        val baseline = committedEmptyBaseline("memory-parent", group.value, 1, 1, 1)
        val configuration = SurfaceOwnershipConfiguration(
            surfaceCapacity = surfaceCapacity,
            lineageCapacity = lineageCapacity,
            seededEmptyBaseline = baseline,
        )
        return SessionCanonicalMemoryState(group, configuration, "memory-parent")
    }

    private fun prepare(state: SessionCanonicalMemoryState, command: Any): PreparedCanonicalMutation {
        val preparation = when (command) {
            is FeatureMutationCommand -> SurfaceOwnership.prepareMutation(state, configuration(state), command)
            is CanonicalTransactionCommand -> SurfaceOwnership.prepareMutation(state, configuration(state), command)
            is CanonicalEvidenceBatchCommand -> MutableCanonicalOverlay.prepare(state, configuration(state), command)
            else -> error("unsupported test command")
        }
        return (preparation as CanonicalMutationPreparation.Prepared).mutation
    }

    private fun configuration(state: SessionCanonicalMemoryState) = SurfaceOwnershipConfiguration(
        surfaceCapacity = 100_000,
        lineageCapacity = 200_000,
        seededEmptyBaseline = state.cut.seededEmptyBaseline,
    )

    private fun commit(state: SessionCanonicalMemoryState, plan: PreparedCanonicalMutation): CanonicalActivationState {
        assertEquals(PreparedMutationClaimResult.Claimed, plan.claim())
        val result = state.commit(plan) as CanonicalAdjacentCommitResult.Committed
        assertEquals(PreparedMutationLifecycle.CONSUMED, plan.finish(PreparedMutationFinish.SUCCESS))
        return result.state
    }

    private fun acknowledge(state: SessionCanonicalMemoryState, activation: CanonicalActivationState) {
        val current = activation.current as CanonicalActivationCurrent.Receipt
        assertTrue(state.acknowledge(CanonicalAcknowledgement(
            current.identity.commandHash, activation.cut.geometryRevision, activation.cut.lineageRevision,
        )) is CanonicalAcknowledgementResult.Acknowledged)
    }

    private fun transaction(
        state: SessionCanonicalMemoryState,
        id: String,
        kind: CanonicalOperation,
        sources: List<SurfaceId>,
        vararg targets: CanonicalTarget,
    ) = CanonicalTransactionCommand(
        id, kind, state.cut.geometryRevision, state.cut.lineageRevision, sources, targets.toList(),
    )

    private fun target(id: SurfaceId?, x: Int, normal: Int = 0, confidence: Int = 192) =
        CanonicalTarget(id, Voxel(x, 0, 0), normal, 0, confidence)

    private fun supports(state: SessionCanonicalMemoryState, target: SurfaceId): List<Long> {
        val values = mutableListOf<Long>()
        state.visitSourceSupport(target, null) { values += it.source.id.value; true }
        return values.sorted()
    }

    private fun lineage(state: SessionCanonicalMemoryState, source: SurfaceId): List<Long> {
        val values = mutableListOf<Long>()
        state.visitLineage(source, null) { values += it.target.value; true }
        return values.sorted()
    }

    private fun rendererRows(state: SessionCanonicalMemoryState, cursor: Long, limit: Int) =
        state.rendererPage(cursor, limit)?.rows.orEmpty().map { Triple(it.surfaceId, it.lineageCount, it.voxel.x) }

    private fun write(receipt: CanonicalActivationCurrent.Receipt): ByteArray = ByteArrayOutputStream().also {
        receipt.source.writeTo(it)
    }.toByteArray()

    private fun sha256(bytes: ByteArray) = CanonicalReceiptBytes(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )

}
