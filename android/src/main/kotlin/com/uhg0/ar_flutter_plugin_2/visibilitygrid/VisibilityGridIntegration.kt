package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.proposal08.CurrentDeltaReceiptV1
import com.uhg0.ar_flutter_plugin_2.proposal08.CurrentDeltaSelectorV1
import com.uhg0.ar_flutter_plugin_2.proposal08.CurrentDeltaSourceV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudNativeConfig
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One group-local canonical surface module behind the capture ingress mapper seam.
 *
 * The interface deliberately remains [VisibilityObservationMapper].  Kernel,
 * canonical owner, exact-current-delta retention, V2 publication and native
 * renderer projection stay on its single mutation lane; the root isolate gets
 * only [VisibilityGridIntegrationReceipt] scalar telemetry.
 */
internal class VisibilityGridIntegration(
    private val binding: VisibilityGridV2Binding,
    private val ownership: () -> VisibilityObservationOwnership?,
    private val directory: File,
    private val resourcesForGroup: (SurfaceGroup) -> CanonicalRuntimeResources,
    private val renderer: CommittedRendererProjection = CommittedRendererProjection.NONE,
    private val commitCanonical: (CanonicalRuntimeResources, PreparedCanonicalMutation) -> CanonicalAdjacentCommitResult =
        { runtime, mutation -> runtime.commitAdjacent(mutation) },
    private val queueCurrent: (VisibilityGridV2Binding, CurrentDeltaSourceV1, CurrentDeltaSelectorV1) -> CurrentDeltaQueueResult =
        { activeBinding, source, selector -> activeBinding.queueCommittedCurrentDelta(source, selector) },
    private val beforeAdmission: () -> Unit = {},
    private val afterLifecycleFence: () -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val ownsExecutor: Boolean = true,
) : VisibilityObservationMapper {
    private val lock = Any()
    private val publicationGate = Any()
    @Volatile private var closed = false
    @Volatile private var paused = false
    private val admissionEpoch = AtomicLong(0)
    private var active = 0
    private var cut: VisibilityObservationOwnership? = null
    private var baseline: committedEmptyBaseline? = null
    private var kernel: FeatureFusionKernel? = null
    private var owner: SurfaceOwnership? = null
    private var resources: CanonicalRuntimeResources? = null
    private var batchSequence = 0L
    private var nextTransactionId = 0L
    private var pending: CurrentDeltaSelectorV1? = null
    private var pendingQueued = false
    private var pendingGeometryCut: CommittedGeometryCut? = null
    private var pendingRendererApplied = false
    private var pendingRendererRows = 0
    private var pendingCanonicalAcknowledgement: CanonicalAcknowledgement? = null
    private val retainedDelta = ExactCurrentDeltaSource()
    @Volatile private var committed = 0L
    @Volatile private var rejected = 0L
    @Volatile private var fenced = 0L
    @Volatile private var receipt = VisibilityGridIntegrationReceipt.empty()

    init {
        binding.attachM3AcknowledgementListener(::acknowledge)
    }

    override fun admitFeature(observation: VisibilityFeatureObservation) = mutate(observation.ownership) {
        if (isFenced(observation.ownership)) return@mutate
        if (pending != null) {
            if (!pendingQueued && !retryPendingQueue()) {
                rejected++
                receipt = receipt.copy(rejected = rejected)
                return@mutate
            }
            rejected++
            receipt = receipt.copy(status = "awaitingExactAck", rejected = rejected)
            return@mutate
        }
        ensureOpened(observation.ownership) ?: return@mutate
        if (pending != null) {
            if (!pendingQueued && !retryPendingQueue()) {
                rejected++
                receipt = receipt.copy(rejected = rejected)
                return@mutate
            }
            rejected++
            receipt = receipt.copy(status = "awaitingExactAck", rejected = rejected)
            return@mutate
        }
        // This adapter is the only CAPTURE-INGRESS-to-canonical surface conversion point.  It creates
        // fixed camera/sample evidence before the kernel can mutate anything.
        val normalEvidence = (FeatureNormalEvidence.from(observation) as? FeatureNormalEvidence.Conversion.Accepted)
            ?: run {
                rejected++
                receipt = receipt.copy(status = "normalEvidenceRefused", rejected = rejected)
                return@mutate
            }
        val fused = requireNotNull(kernel).prepare(
            FeatureFusionBatch(
                sequence = ++batchSequence,
                timestampNs = observation.frame.sourceTimestampNs,
                observations = observation.samples.zip(normalEvidence.evidence).map { (sample, normal) ->
                    FeatureFusionEvidence(sample.xWorld, sample.yWorld, sample.zWorld, 2, sample.id, normal)
                },
            ),
        )
        val accepted = fused as? FeatureFusionResult.Accepted ?: run {
            rejected++
            receipt = receipt.copy(status = "kernelRefused", rejected = rejected)
            return@mutate
        }
        if (isFenced(observation.ownership)) {
            requireNotNull(kernel).discardPrepared()
            return@mutate
        }
        val state = requireNotNull(owner).activationState()
        if (state?.current == CanonicalActivationCurrent.None &&
            state.cut.liveSurfaceCount == 0
        ) {
            publishInitialV6Create(observation.ownership, accepted.delta)
        } else {
            publishMaterialBatch(observation.ownership, accepted.delta)
        }
    }

    /** #63 owns depth semantics; this feature-only integration refuses it. */
    override fun admitDepth(observation: VisibilityDepthObservation) {
        synchronized(lock) {
            rejected++
            receipt = receipt.copy(status = "depthDeferred", rejected = rejected)
        }
    }

    override fun pause() {
        synchronized(publicationGate) {
            paused = true
            admissionEpoch.incrementAndGet()
        }
        afterLifecycleFence()
        drain()
    }

    override fun resume() {
        synchronized(publicationGate) {
            if (closed) return
            paused = false
            admissionEpoch.incrementAndGet()
        }
    }

    override fun rollover(ownership: VisibilityObservationOwnership) {
        synchronized(publicationGate) {
            paused = true
            admissionEpoch.incrementAndGet()
        }
        afterLifecycleFence()
        drain()
        synchronized(lock) {
            if (closed || this.ownership() != ownership) return
            closeOwner()
            cut = null
            baseline = null
            receipt = VisibilityGridIntegrationReceipt.empty()
        }
        renderer.clear()
    }

    override fun snapshot(): VisibilityMappingAdmissionHealth = synchronized(lock) {
        VisibilityMappingAdmissionHealth(
            admittedFeatures = committed,
            admittedDepths = 0,
            replacedFeatures = 0,
            replacedDepths = 0,
            residentBytes = 0,
            residentObservations = 0,
            peakResidentBytes = 0,
            rolloverCount = if (cut == null) 0 else 1,
            rolloverDiscardedObservations = fenced,
            rolloverBindingGeneration = cut?.bindingGeneration ?: 0,
            rolloverGroupGeneration = cut?.groupGeneration ?: 0,
            rolloverLifecycleSequence = cut?.lifecycleSequence ?: 0,
            lastReceipt = null,
        )
    }

    fun integrationReceipt(): VisibilityGridIntegrationReceipt = synchronized(lock) { receipt }

    /** Portable scalar owners only; kernel/renderer arrays and phase buffers are named separately. */
    internal fun portableOwnerMemoryReceipt(): RuntimeOwnerMemoryReceipt = synchronized(lock) {
        RuntimeOwnerMemoryReceipt(
            integrationObjectBytes = 144,
            integrationReceiptBytes = 104,
            retainedDeltaOwnerBytes = 16,
            runtimeOwnerBytes = resources?.portableOwnerBytes() ?: 0,
            bindingOwnerBytes = binding.portableOwnerBytes(),
            coordinatorOwnerBytes = resources?.portableCoordinatorOwnerBytes() ?: 0,
            rendererOwnerBytes = renderer.portableOwnerBytes(),
        )
    }

    override fun close() {
        synchronized(publicationGate) {
            if (closed) return
            closed = true
            paused = true
            admissionEpoch.incrementAndGet()
        }
        afterLifecycleFence()
        drain()
        synchronized(lock) { closeOwner() }
        renderer.close()
        if (ownsExecutor) executor.shutdownNow()
    }

    private fun mutate(expected: VisibilityObservationOwnership, work: () -> Unit) {
        val epoch = admissionEpoch.get()
        if (paused || ownership() != expected) {
            synchronized(lock) { fenced++ }
            return
        }
        executor.submit {
            synchronized(lock) { active++ }
            try {
                beforeAdmission()
                synchronized(publicationGate) {
                    if (closed || paused || admissionEpoch.get() != epoch || ownership() != expected) {
                        synchronized(lock) { fenced++ }
                        return@submit
                    }
                    work()
                }
            } finally {
                synchronized(lock) { active-- }
            }
        }.get()
    }

    private fun isFenced(expected: VisibilityObservationOwnership): Boolean =
        closed || paused || ownership() != expected

    private fun ensureOpened(expected: VisibilityObservationOwnership): committedEmptyBaseline? {
        if (cut == expected) return baseline
        val seeded = binding.committedEmptyBaseline() ?: run { fenced++; return null }
        if (ownership() != expected) { fenced++; return null }
        val group = SurfaceGroup(expected.captureGroupId)
        val runtimeResources = resourcesForGroup(group)
        val opened = if (CanonicalActivationSelector.hasDurableSelector(group, runtimeResources.directory)) {
            runtimeResources.reopen()
        } else {
            runtimeResources.openInitial(seeded)
        } as? SurfaceOwnershipOpenResult.Opened ?: run {
            runtimeResources.close()
            rejected++
            return null
        }
        resources = runtimeResources
        owner = opened.ownership
        kernel = FeatureFusionKernel()
        cut = expected
        baseline = seeded
        nextTransactionId = seeded.transactionId + 1
        batchSequence = 0
        receipt = VisibilityGridIntegrationReceipt.seeded(expected, seeded)
        // A restart may have a selected unacknowledged v6 current. It is replayed
        // through the same bounded V2 receipt path before a later batch is admitted.
        opened.ownership.activationState()?.let { state ->
            if (state.currentState is CanonicalCurrentState.Unacknowledged) {
                rebuildCanonicalRenderer(expected, state)
                publishV6Current(expected, state, rendererAlreadyCurrent = true)
            } else {
                rebuildCanonicalRenderer(expected, state)
            }
        }
        return seeded
    }

    private fun publishInitialV6Create(
        expected: VisibilityObservationOwnership,
        changes: List<FeatureFusionChange>,
    ) {
        val targets = changes.mapNotNull { (it as? FeatureFusionChange.Upsert)?.candidate }
            .mapNotNull(FeatureFusionCandidate::primaryCanonicalTarget)
        if (targets.isEmpty()) {
            check(requireNotNull(kernel).prepareCanonicalApplication(emptyList()))
            requireNotNull(kernel).applyPrepared()
            receipt = receipt.copy(status = "nonMaterialRetained")
            return
        }
        val activeOwner = requireNotNull(owner)
        val preparation = requireNotNull(resources).withCurrent {
            activeOwner.prepareAdjacentMutation(it, CanonicalTransactionCommand(
                commandId = "${expected.bindingGeneration}:${expected.lifecycleSequence}:${batchSequence}",
                kind = CanonicalOperation.CREATE,
                expectedGeometryRevision = it.cut.geometryRevision,
                expectedLineageRevision = it.cut.lineageRevision,
                sourceIds = emptyList(),
                targets = targets,
            ))
        } ?: run {
            requireNotNull(kernel).discardPrepared()
            rejected++
            receipt = receipt.copy(status = "v6ReadRefused", rejected = rejected)
            return
        }
        val prepared = preparation as? CanonicalMutationPreparation.Prepared ?: run {
            requireNotNull(kernel).discardPrepared()
            rejected++
            receipt = receipt.copy(status = "canonicalRefused", rejected = rejected)
            return
        }
        val assignments = canonicalAssignments(changes, prepared.mutation)
        if (!requireNotNull(kernel).prepareCanonicalApplication(assignments)) {
            requireNotNull(kernel).discardPrepared()
            rejected++
            receipt = receipt.copy(status = "kernelApplyRefused", rejected = rejected)
            return
        }
        val state = (commitCanonical(requireNotNull(resources), prepared.mutation)
            as? CanonicalAdjacentCommitResult.Committed)?.state ?: run {
            requireNotNull(kernel).discardPrepared()
            rejected++
            receipt = receipt.copy(status = "canonicalCommitRefused", rejected = rejected)
            return
        }
        requireNotNull(kernel).applyPrepared()
        publishV6Current(expected, state, prepared.mutation)
    }

    private fun publishMaterialBatch(
        expected: VisibilityObservationOwnership,
        changes: List<FeatureFusionChange>,
    ) {
        // An accepted kernel batch with no material delta needs no canonical
        // authority at all. In particular, do not turn a no-op refinement into
        // an O(history) selector/coordinator recovery scan.
        if (changes.isEmpty()) {
            check(requireNotNull(kernel).prepareCanonicalApplication(emptyList()))
            requireNotNull(kernel).applyPrepared()
            receipt = receipt.copy(status = "nonMaterialRetained")
            return
        }
        val activeOwner = requireNotNull(owner)
        val preparation = requireNotNull(resources).withCorrelatedCurrent(changes) {
            activeOwner.prepareAdjacentMutation(
                it,
                CanonicalFeatureBatchCommand(
                    commandId = "${expected.bindingGeneration}:${expected.lifecycleSequence}:${batchSequence}",
                    expectedGeometryRevision = it.cut.geometryRevision,
                    expectedLineageRevision = it.cut.lineageRevision,
                    changes = changes,
                ),
            )
        } ?: run {
            requireNotNull(kernel).discardPrepared()
            rejected++
            receipt = receipt.copy(status = "v6ReadRefused", rejected = rejected)
            return
        }
        val prepared = preparation as? CanonicalMutationPreparation.Prepared ?: run {
            if (preparation is CanonicalMutationPreparation.NoOp) {
                check(requireNotNull(kernel).prepareCanonicalApplication(emptyList()))
                requireNotNull(kernel).applyPrepared()
                receipt = receipt.copy(status = "nonMaterialRetained")
            } else {
                requireNotNull(kernel).discardPrepared()
                rejected++
                receipt = receipt.copy(status = "batchRefused", rejected = rejected)
            }
            return
        }
        val assignments = canonicalAssignments(changes, prepared.mutation)
        if (!requireNotNull(kernel).prepareCanonicalApplication(assignments)) {
            requireNotNull(kernel).discardPrepared()
            rejected++
            receipt = receipt.copy(status = "kernelApplyRefused", rejected = rejected)
            return
        }
        val committedState = (commitCanonical(requireNotNull(resources), prepared.mutation)
            as? CanonicalAdjacentCommitResult.Committed)?.state ?: run {
            requireNotNull(kernel).discardPrepared()
            rejected++
            receipt = receipt.copy(status = "batchCommitRefused", rejected = rejected)
            return
        }
        requireNotNull(kernel).applyPrepared()
        if (isFenced(expected)) {
            fenced++
            receipt = receipt.copy(status = "publicationDeferred", fenced = fenced)
            return
        }
        publishV6Current(expected, committedState, prepared.mutation)
    }

    private fun canonicalAssignments(
        changes: List<FeatureFusionChange>,
        mutation: PreparedCanonicalMutation,
    ): List<CanonicalFeatureAssignment> {
        val slots = HashMap<Voxel, Int>()
        changes.forEach { change ->
            val upsert = change as? FeatureFusionChange.Upsert ?: return@forEach
            check(upsert.kernelSlot >= 0)
            slots[Voxel(upsert.x, upsert.y, upsert.z)] = upsert.kernelSlot
        }
        val assignments = ArrayList<CanonicalFeatureAssignment>(mutation.dirtyRowCount)
        check(mutation.visitDirtyRows { row ->
            val slot = slots[row.voxel] ?: return@visitDirtyRows false
            assignments += CanonicalFeatureAssignment(
                slot, row.voxel.x, row.voxel.y, row.voxel.z, row.id, row.allocationFingerprint,
                row.packedNormal, row.normalConfidence,
            )
            true
        })
        check(assignments.size == slots.size) {
            "prepared canonical mutation did not name every staged kernel upsert"
        }
        return assignments
    }

    private fun publishV6Current(
        expected: VisibilityObservationOwnership,
        state: CanonicalActivationState,
        mutation: PreparedCanonicalMutation? = null,
        rendererAlreadyCurrent: Boolean = false,
    ) {
        val current = state.current as? CanonicalActivationCurrent.Receipt ?: run {
            rejected++
            receipt = receipt.copy(status = "currentMissing", rejected = rejected)
            return
        }
        val canonicalBytes = ByteArrayOutputStream().use { output ->
            current.source.writeTo(output)
            output.toByteArray()
        }
        if (canonicalBytes.size.toLong() != current.identity.canonicalLength) {
            rejected++
            receipt = receipt.copy(status = "currentCorrupt", rejected = rejected)
            return
        }
        val selector = CurrentDeltaSelectorV1(
            transactionId = nextTransactionId++,
            targetGeometryRevision = state.cut.geometryRevision,
            targetLineageRevision = state.cut.lineageRevision,
        )
        if (isFenced(expected)) {
            fenced++
            receipt = receipt.copy(status = "publicationDeferred", fenced = fenced)
            return
        }
        val geometryCut = mutation?.toCommittedGeometryCut(expected, selector.transactionId)
        retainedDelta.retain(
            CurrentDeltaReceiptV1(
                selector,
                state.cut.geometryRevision - 1,
                canonicalBytes,
                current.identity.commandHash.toByteArray(),
            ),
        )
        pendingCanonicalAcknowledgement = CanonicalAcknowledgement(
            current.identity.commandHash,
            state.cut.geometryRevision,
            state.cut.lineageRevision,
        )
        pending = selector
        pendingQueued = false
        pendingGeometryCut = geometryCut
        pendingRendererApplied = rendererAlreadyCurrent
        pendingRendererRows = if (rendererAlreadyCurrent) renderer.currentRowCount() else 0
        committed++
        receipt = VisibilityGridIntegrationReceipt(
            "pendingAck", expected.bindingGeneration, expected.sessionGeneration,
            expected.groupGeneration, selector.transactionId, state.cut.geometryRevision,
            state.cut.lineageRevision, canonicalBytes.size, pendingRendererRows,
            committed, rejected, fenced, canonicalOperation(canonicalBytes),
        )
        retryPendingQueue()
    }

    /** Idempotently correlates the already-retained durable current to V2. */
    private fun retryPendingQueue(): Boolean {
        val selector = pending ?: return true
        return try {
            queueCurrent(binding, retainedDelta, selector)
            pendingQueued = true
            if (!pendingRendererApplied) {
                val cut = pendingGeometryCut ?: return false
                if (isFenced(cut.ownership)) {
                    receipt = receipt.copy(status = "publicationDeferred")
                    return false
                }
                when (val result = renderer.applyGeometry(cut)) {
                    is RendererProjectionResult.Applied -> {
                        pendingRendererApplied = true
                        pendingRendererRows = result.rowCount
                    }
                    is RendererProjectionResult.Refused -> {
                        rejected++
                        receipt = receipt.copy(status = "rendererRefused", rejected = rejected)
                        return false
                    }
                }
            }
            receipt = receipt.copy(status = "pendingAck", rendererRows = pendingRendererRows)
            true
        } catch (_: IllegalStateException) {
            pendingQueued = false
            receipt = receipt.copy(status = "publicationRetryPending")
            false
        } catch (_: IllegalArgumentException) {
            pendingQueued = false
            receipt = receipt.copy(status = "publicationRetryPending")
            false
        }
    }

    private fun rebuildCanonicalRenderer(
        expected: VisibilityObservationOwnership,
        state: CanonicalActivationState,
    ) {
        val rebuildCut = CommittedGeometryCut(
            ownership = expected,
            transactionId = 0,
            baseGeometryRevision = 0,
            geometryRevision = state.cut.geometryRevision,
            lineageRevision = state.cut.lineageRevision,
            reset = true,
            upserts = emptyList(),
            removedSurfaceIds = LongArray(0),
        )
        renderer.beginRebuild(rebuildCut)
        var finished = false
        try {
        val maximumRows = renderer.maximumRows
        val rebuilt = requireNotNull(resources).rebuildAndHydrate(requireNotNull(kernel), maximumRows) { page ->
            if (isFenced(expected)) throw RendererRebuildFenced()
            renderer.appendRebuildPage(rebuildCut.withUpserts(page.rows))
        } ?: run {
            rejected++
            receipt = receipt.copy(status = "rebuildRefused", rejected = rejected)
            return
        }
        if (rebuilt != state.cut || isFenced(expected)) return
        renderer.finishRebuild(rebuildCut)
        finished = true
        return
        } catch (_: RendererRebuildFenced) {
            return
        } finally {
            if (!finished) renderer.abortRebuild()
        }
    }

    private fun acknowledge(selector: CurrentDeltaSelectorV1) {
        if (closed) return
        runCatching {
            executor.submit {
                synchronized(lock) {
                    if (pending != selector || !pendingQueued || closed) return@synchronized
                    val acknowledgement = requireNotNull(pendingCanonicalAcknowledgement)
                    when (val result = requireNotNull(owner).acknowledgeCanonicalCurrent(acknowledgement)) {
                        is CanonicalAcknowledgementResult.Acknowledged,
                        is CanonicalAcknowledgementResult.Idempotent -> Unit
                        is CanonicalAcknowledgementResult.NoOp -> {
                            receipt = receipt.copy(status = "ack${result.reason.name}")
                            return@synchronized
                        }
                    }
                    retainedDelta.acknowledge(selector)
                    pending = null
                    pendingQueued = false
                    pendingGeometryCut = null
                    pendingRendererApplied = false
                    pendingRendererRows = 0
                    pendingCanonicalAcknowledgement = null
                    receipt = receipt.copy(status = "acknowledged")
                }
            }
        }
    }

    private fun closeOwner() {
        retainedDelta.clear()
        pending = null
        pendingQueued = false
        pendingGeometryCut = null
        pendingRendererApplied = false
        pendingRendererRows = 0
        resources?.close() ?: owner?.close()
        resources = null
        owner = null
        kernel = null
        pendingCanonicalAcknowledgement = null
    }

    private fun drain() { executor.submit {}.get(2, TimeUnit.SECONDS) }
}

internal data class RuntimeOwnerMemoryReceipt(
    val integrationObjectBytes: Long,
    val integrationReceiptBytes: Long,
    val retainedDeltaOwnerBytes: Long,
    val runtimeOwnerBytes: Long,
    val bindingOwnerBytes: Long,
    val coordinatorOwnerBytes: Long,
    val rendererOwnerBytes: Long,
) {
    val portableBytes: Long get() = integrationObjectBytes + integrationReceiptBytes + retainedDeltaOwnerBytes +
        runtimeOwnerBytes + bindingOwnerBytes + coordinatorOwnerBytes + rendererOwnerBytes
}

private class RendererRebuildFenced : RuntimeException()

/** #111 owns creating an ID for OPPOSING; current CREATE consumes pinned PRIMARY only. */
internal fun FeatureFusionCandidate.primaryCanonicalTarget(): CanonicalTarget? =
    normalCandidates.firstOrNull { it.face == FeatureNormalFace.PRIMARY }?.let {
        CanonicalTarget(
            voxel = Voxel(x, y, z),
            normalOctX = it.normalOctX,
            normalOctY = it.normalOctY,
            normalConfidence = it.normalConfidence,
        )
    }

internal interface CommittedRendererProjection : AutoCloseable {
    val maximumRows: Int get() = 0
    fun applyGeometry(cut: CommittedGeometryCut): RendererProjectionResult
    fun currentRowCount(): Int = 0
    fun portableOwnerBytes(): Long = 0
    fun beginRebuild(cut: CommittedGeometryCut) = Unit
    fun appendRebuildPage(cut: CommittedGeometryCut) = Unit
    fun finishRebuild(cut: CommittedGeometryCut) = Unit
    fun abortRebuild() = Unit
    fun clear() = Unit
    override fun close() = Unit
    companion object {
        val NONE = object : CommittedRendererProjection {
            override fun applyGeometry(cut: CommittedGeometryCut) =
                RendererProjectionResult.Applied(0)
        }
    }
}

internal sealed interface RendererProjectionResult {
    data class Applied(val rowCount: Int) : RendererProjectionResult
    data class Refused(val reason: RendererProjectionRefusal) : RendererProjectionResult
}

internal enum class RendererProjectionRefusal {
    STALE_OWNERSHIP,
    NON_ADJACENT_GEOMETRY,
    MIXED_CUT,
    CAPACITY,
    CLOSED,
}

/** Dedicated V2 adapter over the existing bounded native renderer state. */
internal class NativeRendererProjection(
    private val render: (CoveragePointRenderSnapshot?, PointCloudNativeConfig?) -> Unit,
    capacity: Int = VisibilityGridRendererState.CENTROID_PRESENTATION_CAPACITY,
) : CommittedRendererProjection {
    private val state = VisibilityGridRendererState(capacity)
    private val surfaceVoxels = HashMap<Long, Voxel>(capacity)
    private var closed = false
    private val rebuildRows = ArrayList<CommittedGeometryRow>(capacity)
    private var rebuildCut: CommittedGeometryCut? = null
    private var activeOwnership: VisibilityObservationOwnership? = null
    private var activeLineageRevision = 0L
    override val maximumRows: Int get() = state.capacity
    override fun portableOwnerBytes(): Long =
        56L + 96L + 64L + state.retainedGroupGeometryBytes // projection, state, native config, group geometry

    @Synchronized
    override fun applyGeometry(cut: CommittedGeometryCut): RendererProjectionResult {
        if (closed) return RendererProjectionResult.Refused(RendererProjectionRefusal.CLOSED)
        if (activeOwnership != cut.ownership) {
            return RendererProjectionResult.Refused(RendererProjectionRefusal.STALE_OWNERSHIP)
        }
        if (cut.lineageRevision < activeLineageRevision) {
            return RendererProjectionResult.Refused(RendererProjectionRefusal.MIXED_CUT)
        }
        val removedKnownCount = cut.removedSurfaceIds.count(surfaceVoxels::containsKey)
        val insertedCount = cut.upserts.count { !surfaceVoxels.containsKey(it.surfaceId) }
        val targetRowCount = surfaceVoxels.size - removedKnownCount + insertedCount
        if (targetRowCount > cut.ownership.groupFrame.modelCapacity) {
            return RendererProjectionResult.Refused(RendererProjectionRefusal.CAPACITY)
        }
        val removedIds = cut.removedSurfaceIds
        val removalKeys = removedIds.map { surfaceVoxels[it] }.filterNotNull()
            .map { packVisibilityGridKey(it.x, it.y, it.z) }
            .toMutableSet()
        val upsertKeys = cut.upserts.map { row ->
            surfaceVoxels[row.surfaceId]?.let { previous ->
                if (previous != row.voxel) {
                    removalKeys += packVisibilityGridKey(previous.x, previous.y, previous.z)
                }
            }
            packVisibilityGridKey(row.voxel.x, row.voxel.y, row.voxel.z)
        }
        // Stable-ID replacement may remove one surface and create another at
        // the same voxel. The voxel-keyed legacy renderer keeps that location.
        removalKeys.removeAll(upsertKeys.toSet())
        if (!state.applyGeometry(
                revision = cut.geometryRevision,
                reset = cut.reset,
                upsertKeys = upsertKeys.toLongArray(),
                removalKeys = removalKeys.toLongArray(),
            )
        ) {
            return RendererProjectionResult.Refused(RendererProjectionRefusal.NON_ADJACENT_GEOMETRY)
        }
        removedIds.forEach(surfaceVoxels::remove)
        cut.upserts.forEach { row -> surfaceVoxels[row.surfaceId] = row.voxel }
        activeLineageRevision = cut.lineageRevision
        render(state.snapshot(), renderConfig(cut))
        return RendererProjectionResult.Applied(currentRowCount())
    }

    @Synchronized
    override fun currentRowCount(): Int = state.capacity - state.freeRowCount

    @Synchronized
    override fun beginRebuild(
        cut: CommittedGeometryCut,
    ) {
        check(!closed)
        require(cut.reset)
        rebuildRows.clear()
        rebuildCut = cut
    }

    @Synchronized
    override fun appendRebuildPage(
        cut: CommittedGeometryCut,
    ) {
        check(!closed)
        check(rebuildCut?.sameIdentityAs(cut) == true) {
            "Renderer rebuild page does not name the active canonical cut"
        }
        check(cut.removedSurfaceIds.isEmpty()) { "Renderer rebuild page cannot remove rows" }
        require(rebuildRows.size + cut.upserts.size <= state.capacity)
        rebuildRows += cut.upserts
    }

    @Synchronized
    override fun finishRebuild(
        cut: CommittedGeometryCut,
    ) {
        check(!closed)
        check(rebuildCut?.sameIdentityAs(cut) == true) {
            "Renderer rebuild finish does not name the active canonical cut"
        }
        renderRows(cut, rebuildRows)
        discardRebuild()
    }

    @Synchronized
    override fun abortRebuild() {
        discardRebuild()
    }

    private fun renderRows(cut: CommittedGeometryCut, rows: List<CommittedGeometryRow>) {
        val keys = rows.map { packVisibilityGridKey(it.voxel.x, it.voxel.y, it.voxel.z) }.toLongArray()
        val frame = cut.ownership.groupFrame
        state.startGroup(
            config = VisibilityGridGroupConfig(
                groupId = cut.ownership.captureGroupId,
                groupGeneration = cut.ownership.groupGeneration,
                sessionGeneration = cut.ownership.sessionGeneration,
                voxelSizeMeters = frame.voxelSizeMicrometres.toDouble() / 1_000_000.0,
                capacity = frame.modelCapacity,
                groupFromWorldGl = frame.groupFromWorldGl.toDoubleArray(),
                worldFromGroupGl = frame.worldFromGroupGl.toDoubleArray(),
                restoredGeometryRevision = cut.geometryRevision,
                restoredKeys = keys,
            ),
            geometryRevision = cut.geometryRevision,
            restoredKeys = keys,
        )
        surfaceVoxels.clear()
        rows.forEach { row -> surfaceVoxels[row.surfaceId] = row.voxel }
        activeOwnership = cut.ownership
        activeLineageRevision = cut.lineageRevision
        render(state.snapshot(), renderConfig(cut))
    }

    private fun renderConfig(cut: CommittedGeometryCut): PointCloudNativeConfig {
        val frame = cut.ownership.groupFrame
        return PointCloudNativeConfig(
            renderCapacity = frame.modelCapacity,
            voxelRenderMode = VoxelRenderMode.CENTROIDS,
            voxelSizeMeters = frame.voxelSizeMicrometres.toFloat() / 1_000_000f,
        )
    }

    @Synchronized
    override fun clear() {
        if (closed) return
        discardRebuild()
        surfaceVoxels.clear()
        activeOwnership = null
        activeLineageRevision = 0
        state.stopGroup()
        render(null, null)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        discardRebuild()
        surfaceVoxels.clear()
        activeOwnership = null
        state.dispose()
        render(null, null)
    }

    private fun discardRebuild() {
        rebuildRows.clear()
        rebuildCut = null
    }

    private fun CommittedGeometryCut.sameIdentityAs(other: CommittedGeometryCut): Boolean =
        ownership == other.ownership &&
            transactionId == other.transactionId &&
            baseGeometryRevision == other.baseGeometryRevision &&
            geometryRevision == other.geometryRevision &&
            lineageRevision == other.lineageRevision &&
            reset == other.reset
}

/** Typed scalar test receipt; ordinary feature/surface payload bytes are absent. */
internal data class VisibilityGridIntegrationReceipt(
    val status: String,
    val bindingGeneration: Long,
    val sessionGeneration: Long,
    val groupGeneration: Long,
    val transactionId: Long,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val canonicalBytes: Int,
    val rendererRows: Int,
    val committed: Long,
    val rejected: Long,
    val fenced: Long,
    val canonicalOperation: String,
) {
    companion object {
        fun empty() = VisibilityGridIntegrationReceipt("idle", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "none")
        fun seeded(cut: VisibilityObservationOwnership, baseline: committedEmptyBaseline) =
            VisibilityGridIntegrationReceipt("seeded", cut.bindingGeneration, cut.sessionGeneration, cut.groupGeneration, baseline.transactionId, baseline.geometryRevision, baseline.lineageRevision, 0, 0, 0, 0, 0, "none")
    }
}

private fun canonicalOperation(bytes: ByteArray): String = try {
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == 0x4d334350 && input.readInt() == 2)
        input.readFully(ByteArray(32))
        input.readUTF()
        PreparedMutationKind.entries[input.readInt()].name
    }
} catch (_: Exception) {
    "invalid"
}

private class ExactCurrentDeltaSource : CurrentDeltaSourceV1 {
    private var value: CurrentDeltaReceiptV1? = null
    @Synchronized fun retain(receipt: CurrentDeltaReceiptV1) { check(value == null); value = receipt }
    @Synchronized override fun selectCurrentDelta(selector: CurrentDeltaSelectorV1) = value?.takeIf { it.selector == selector }
    @Synchronized fun acknowledge(selector: CurrentDeltaSelectorV1) { check(value?.selector == selector); value = null }
    @Synchronized fun clear() { value = null }
}
