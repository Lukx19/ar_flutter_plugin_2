package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.m0.M0aCurrentDeltaReceiptV1
import com.uhg0.ar_flutter_plugin_2.m0.M0aCurrentDeltaSelectorV1
import com.uhg0.ar_flutter_plugin_2.m0.M0aCurrentDeltaSourceV1
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
 * One group-local M3 module behind the M2 mapper seam.
 *
 * The interface deliberately remains [VisibilityObservationMapper].  Kernel,
 * canonical owner, exact-current-delta retention, V2 publication and native
 * renderer projection stay on its single mutation lane; the root isolate gets
 * only [M3VisibilityGridIntegrationReceipt] scalar telemetry.
 */
internal class M3VisibilityGridIntegration(
    private val binding: VisibilityGridV2Binding,
    private val ownership: () -> VisibilityObservationOwnership?,
    private val directory: File,
    private val resourcesForGroup: (M3SurfaceGroup) -> M3CanonicalRuntimeResources,
    private val renderer: M3CommittedRendererProjection = M3CommittedRendererProjection.NONE,
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
    private var baseline: M3CommittedEmptyBaseline? = null
    private var kernel: M3FeatureFusionKernel? = null
    private var owner: M3SurfaceOwnership? = null
    private var resources: M3CanonicalRuntimeResources? = null
    private var batchSequence = 0L
    private var nextTransactionId = 0L
    private var pending: M0aCurrentDeltaSelectorV1? = null
    private var pendingCanonicalAcknowledgement: M3CanonicalAcknowledgement? = null
    private val retainedDelta = M3ExactCurrentDeltaSource()
    @Volatile private var committed = 0L
    @Volatile private var rejected = 0L
    @Volatile private var fenced = 0L
    @Volatile private var receipt = M3VisibilityGridIntegrationReceipt.empty()

    init {
        binding.attachM3AcknowledgementListener(::acknowledge)
    }

    override fun admitFeature(observation: VisibilityFeatureObservation) = mutate(observation.ownership) {
        if (isFenced(observation.ownership)) return@mutate
        if (pending != null) {
            rejected++
            receipt = receipt.copy(status = "awaitingExactAck", rejected = rejected)
            return@mutate
        }
        ensureOpened(observation.ownership) ?: return@mutate
        if (pending != null) {
            rejected++
            receipt = receipt.copy(status = "awaitingExactAck", rejected = rejected)
            return@mutate
        }
        // This adapter is the only M2-to-M3 conversion point.  It creates
        // fixed camera/sample evidence before the kernel can mutate anything.
        val normalEvidence = (M3FeatureNormalEvidence.from(observation) as? M3FeatureNormalEvidence.Conversion.Accepted)
            ?: run {
                rejected++
                receipt = receipt.copy(status = "normalEvidenceRefused", rejected = rejected)
                return@mutate
            }
        val fused = requireNotNull(kernel).accept(
            M3FeatureFusionBatch(
                sequence = ++batchSequence,
                timestampNs = observation.frame.sourceTimestampNs,
                observations = observation.samples.zip(normalEvidence.evidence).map { (sample, normal) ->
                    M3FeatureFusionEvidence(sample.xWorld, sample.yWorld, sample.zWorld, 2, sample.id, normal)
                },
            ),
        )
        val accepted = fused as? M3FeatureFusionResult.Accepted ?: run {
            rejected++
            receipt = receipt.copy(status = "kernelRefused", rejected = rejected)
            return@mutate
        }
        if (isFenced(observation.ownership)) return@mutate
        val state = requireNotNull(owner).activationState()
        if (state?.current == M3CanonicalActivationCurrent.None &&
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
            receipt = M3VisibilityGridIntegrationReceipt.empty()
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

    fun integrationReceipt(): M3VisibilityGridIntegrationReceipt = synchronized(lock) { receipt }

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

    private fun ensureOpened(expected: VisibilityObservationOwnership): M3CommittedEmptyBaseline? {
        if (cut == expected) return baseline
        val seeded = binding.m3CommittedEmptyBaseline() ?: run { fenced++; return null }
        if (ownership() != expected) { fenced++; return null }
        val group = M3SurfaceGroup(expected.captureGroupId)
        val runtimeResources = resourcesForGroup(group)
        val opened = if (M3CanonicalActivationSelector.hasDurableSelector(group, runtimeResources.directory)) {
            runtimeResources.reopen()
        } else {
            runtimeResources.openInitial(seeded)
        } as? M3SurfaceOwnershipOpenResult.Opened ?: run {
            runtimeResources.close()
            rejected++
            return null
        }
        resources = runtimeResources
        owner = opened.ownership
        kernel = M3FeatureFusionKernel()
        cut = expected
        baseline = seeded
        nextTransactionId = seeded.transactionId + 1
        batchSequence = 0
        receipt = M3VisibilityGridIntegrationReceipt.seeded(expected, seeded)
        // A restart may have a selected unacknowledged v6 current. It is replayed
        // through the same bounded V2 receipt path before a later batch is admitted.
        opened.ownership.activationState()?.let { state ->
            if (state.currentState is M3CanonicalCurrentState.Unacknowledged) {
                rebuildCanonicalRenderer(expected, state)
                publishV6Current(expected, state, emptyList(), rendererAlreadyCurrent = true)
            } else {
                rebuildCanonicalRenderer(expected, state)
            }
        }
        return seeded
    }

    private fun publishInitialV6Create(
        expected: VisibilityObservationOwnership,
        changes: List<M3FeatureFusionChange>,
    ) {
        val targets = changes.mapNotNull { (it as? M3FeatureFusionChange.Upsert)?.candidate }
            .mapNotNull(M3FeatureFusionCandidate::primaryCanonicalTarget)
        if (targets.isEmpty()) {
            receipt = receipt.copy(status = "nonMaterialRetained")
            return
        }
        val activeOwner = requireNotNull(owner)
        val preparation = requireNotNull(resources).withCurrent {
            activeOwner.prepareAdjacentMutation(it, M3CanonicalTransactionCommand(
                commandId = "${expected.bindingGeneration}:${expected.lifecycleSequence}:${batchSequence}",
                kind = M3CanonicalOperation.CREATE,
                expectedGeometryRevision = it.cut.geometryRevision,
                expectedLineageRevision = it.cut.lineageRevision,
                sourceIds = emptyList(),
                targets = targets,
            ))
        } ?: run {
            rejected++
            receipt = receipt.copy(status = "v6ReadRefused", rejected = rejected)
            return
        }
        val prepared = preparation as? M3CanonicalMutationPreparation.Prepared ?: run {
            rejected++
            receipt = receipt.copy(status = "canonicalRefused", rejected = rejected)
            return
        }
        val voxels = ArrayList<M3Voxel>(prepared.mutation.dirtyRowCount)
        prepared.mutation.visitDirtyRows { row -> voxels += row.voxel; true }
        val state = (activeOwner.commitAdjacentCanonicalMutation(prepared.mutation)
            as? M3CanonicalAdjacentCommitResult.Committed)?.state ?: run {
            rejected++
            receipt = receipt.copy(status = "canonicalCommitRefused", rejected = rejected)
            return
        }
        publishV6Current(expected, state, voxels)
    }

    private fun publishMaterialBatch(
        expected: VisibilityObservationOwnership,
        changes: List<M3FeatureFusionChange>,
    ) {
        val activeOwner = requireNotNull(owner)
        val preparation = requireNotNull(resources).withCurrent {
            activeOwner.prepareAdjacentMutation(
                it,
                M3CanonicalFeatureBatchCommand(
                    commandId = "${expected.bindingGeneration}:${expected.lifecycleSequence}:${batchSequence}",
                    expectedGeometryRevision = it.cut.geometryRevision,
                    expectedLineageRevision = it.cut.lineageRevision,
                    changes = changes,
                ),
            )
        } ?: run {
            rejected++
            receipt = receipt.copy(status = "v6ReadRefused", rejected = rejected)
            return
        }
        val prepared = preparation as? M3CanonicalMutationPreparation.Prepared ?: run {
            if (preparation is M3CanonicalMutationPreparation.NoOp) {
                receipt = receipt.copy(status = "nonMaterialRetained")
            } else {
                rejected++
                receipt = receipt.copy(status = "batchRefused", rejected = rejected)
            }
            return
        }
        val voxels = ArrayList<M3Voxel>(prepared.mutation.dirtyRowCount)
        prepared.mutation.visitDirtyRows { row -> voxels += row.voxel; true }
        val committedState = (activeOwner.commitAdjacentCanonicalMutation(prepared.mutation)
            as? M3CanonicalAdjacentCommitResult.Committed)?.state ?: run {
            rejected++
            receipt = receipt.copy(status = "batchCommitRefused", rejected = rejected)
            return
        }
        if (isFenced(expected)) {
            fenced++
            receipt = receipt.copy(status = "publicationDeferred", fenced = fenced)
            return
        }
        publishV6Current(expected, committedState, voxels)
    }

    private fun publishV6Current(
        expected: VisibilityObservationOwnership,
        state: M3CanonicalActivationState,
        voxels: List<M3Voxel>,
        rendererAlreadyCurrent: Boolean = false,
    ) {
        val current = state.current as? M3CanonicalActivationCurrent.Receipt ?: run {
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
        val selector = M0aCurrentDeltaSelectorV1(
            transactionId = nextTransactionId++,
            targetGeometryRevision = state.cut.geometryRevision,
            targetLineageRevision = state.cut.lineageRevision,
        )
        retainedDelta.retain(
            M0aCurrentDeltaReceiptV1(
                selector,
                state.cut.geometryRevision - 1,
                canonicalBytes,
                current.identity.commandHash.toByteArray(),
            ),
        )
        pendingCanonicalAcknowledgement = M3CanonicalAcknowledgement(
            current.identity.commandHash,
            state.cut.geometryRevision,
            state.cut.lineageRevision,
        )
        pending = selector
        try {
            binding.queueCommittedCurrentDelta(retainedDelta, selector)
        } catch (_: IllegalStateException) {
            fenced++
            receipt = receipt.copy(status = "publicationFenced", fenced = fenced)
            return
        } catch (_: IllegalArgumentException) {
            fenced++
            receipt = receipt.copy(status = "publicationFenced", fenced = fenced)
            return
        }
        // Renderer and worker name the same durable cut. This callback never
        // enters Flutter and cannot expose ordinary row bytes.
        val rendererRows = if (rendererAlreadyCurrent) renderer.currentRowCount()
        else renderer.project(expected, state.cut.geometryRevision, state.cut.lineageRevision, voxels)
        committed++
        receipt = M3VisibilityGridIntegrationReceipt(
            "pendingAck", expected.bindingGeneration, expected.sessionGeneration,
            expected.groupGeneration, selector.transactionId, state.cut.geometryRevision,
            state.cut.lineageRevision, canonicalBytes.size, rendererRows,
            committed, rejected, fenced, canonicalOperation(canonicalBytes),
        )
    }

    private fun rebuildCanonicalRenderer(
        expected: VisibilityObservationOwnership,
        state: M3CanonicalActivationState,
    ) {
        renderer.beginRebuild(expected, state.cut.geometryRevision, state.cut.lineageRevision)
        val maximumRows = renderer.maximumRows
        if (maximumRows == 0) return
        var cursor = 0L
        var rebuilt = 0
        while (true) {
            if (isFenced(expected)) return
            val page = requireNotNull(resources).readRendererPage(
                cursor, minOf(512, maximumRows - rebuilt),
            ) ?: run {
                rejected++
                receipt = receipt.copy(status = "rebuildRefused", rejected = rejected)
                return
            }
            renderer.appendRebuildPage(expected, page.cut.geometryRevision, page.cut.lineageRevision, page.voxels)
            rebuilt += page.voxels.size
            if (rebuilt >= maximumRows) return
            cursor = page.nextCursor ?: return
        }
    }

    private fun acknowledge(selector: M0aCurrentDeltaSelectorV1) {
        if (closed) return
        runCatching {
            executor.submit {
                synchronized(lock) {
                    if (pending != selector || closed) return@synchronized
                    val acknowledgement = requireNotNull(pendingCanonicalAcknowledgement)
                    when (val result = requireNotNull(owner).acknowledgeCanonicalCurrent(acknowledgement)) {
                        is M3CanonicalAcknowledgementResult.Acknowledged,
                        is M3CanonicalAcknowledgementResult.Idempotent -> Unit
                        is M3CanonicalAcknowledgementResult.NoOp -> {
                            receipt = receipt.copy(status = "ack${result.reason.name}")
                            return@synchronized
                        }
                    }
                    retainedDelta.acknowledge(selector)
                    pending = null
                    pendingCanonicalAcknowledgement = null
                    receipt = receipt.copy(status = "acknowledged")
                }
            }
        }
    }

    private fun closeOwner() {
        retainedDelta.clear()
        pending = null
        resources?.close() ?: owner?.close()
        resources = null
        owner = null
        kernel = null
        pendingCanonicalAcknowledgement = null
    }

    private fun drain() { executor.submit {}.get(2, TimeUnit.SECONDS) }
}

/** #111 owns creating an ID for OPPOSING; current CREATE consumes pinned PRIMARY only. */
internal fun M3FeatureFusionCandidate.primaryCanonicalTarget(): M3CanonicalTarget? =
    normalCandidates.firstOrNull { it.face == M3FeatureNormalFace.PRIMARY }?.let {
        M3CanonicalTarget(
            voxel = M3Voxel(x, y, z),
            normalOctX = it.normalOctX,
            normalOctY = it.normalOctY,
            normalConfidence = it.normalConfidence,
        )
    }

internal interface M3CommittedRendererProjection : AutoCloseable {
    val maximumRows: Int get() = 0
    fun project(
        ownership: VisibilityObservationOwnership,
        geometryRevision: Long,
        lineageRevision: Long,
        voxels: List<M3Voxel>,
    ): Int
    fun currentRowCount(): Int = 0
    fun beginRebuild(
        ownership: VisibilityObservationOwnership,
        geometryRevision: Long,
        lineageRevision: Long,
    ) = Unit
    fun appendRebuildPage(
        ownership: VisibilityObservationOwnership,
        geometryRevision: Long,
        lineageRevision: Long,
        voxels: List<M3Voxel>,
    ) = Unit
    fun clear() = Unit
    override fun close() = Unit
    companion object {
        val NONE = object : M3CommittedRendererProjection {
            override fun project(
                ownership: VisibilityObservationOwnership,
                geometryRevision: Long,
                lineageRevision: Long,
                voxels: List<M3Voxel>,
            ) = 0
        }
    }
}

/** Dedicated V2 adapter over the existing bounded native renderer state. */
internal class M3NativeRendererProjection(
    private val render: (CoveragePointRenderSnapshot?, PointCloudNativeConfig?) -> Unit,
    capacity: Int = VisibilityGridRendererState.CENTROID_PRESENTATION_CAPACITY,
) : M3CommittedRendererProjection {
    private val state = VisibilityGridRendererState(capacity)
    private val config = PointCloudNativeConfig(
        renderCapacity = capacity,
        voxelRenderMode = VoxelRenderMode.CENTROIDS,
        voxelSizeMeters = 0.1f,
    )
    private var closed = false
    private var rebuildKeys = LongArray(capacity)
    private var rebuildCount = 0
    override val maximumRows: Int get() = state.capacity

    @Synchronized
    override fun project(
        ownership: VisibilityObservationOwnership,
        geometryRevision: Long,
        lineageRevision: Long,
        voxels: List<M3Voxel>,
    ): Int {
        check(!closed)
        val keys = voxels.map { packVisibilityGridKey(it.x, it.y, it.z) }.toLongArray()
        check(state.applyGeometry(geometryRevision, false, keys, LongArray(0))) {
            "Renderer delta is not adjacent to its complete canonical cut"
        }
        render(state.snapshot(), config)
        return currentRowCount()
    }

    @Synchronized
    override fun currentRowCount(): Int = state.capacity - state.freeRowCount

    @Synchronized
    override fun beginRebuild(
        ownership: VisibilityObservationOwnership,
        geometryRevision: Long,
        lineageRevision: Long,
    ) {
        check(!closed)
        rebuildCount = 0
    }

    @Synchronized
    override fun appendRebuildPage(
        ownership: VisibilityObservationOwnership,
        geometryRevision: Long,
        lineageRevision: Long,
        voxels: List<M3Voxel>,
    ) {
        check(!closed)
        appendKeys(voxels)
        renderKeys(ownership, geometryRevision)
    }

    private fun appendKeys(voxels: List<M3Voxel>) {
        require(rebuildCount + voxels.size <= rebuildKeys.size)
        voxels.forEach { rebuildKeys[rebuildCount++] = packVisibilityGridKey(it.x, it.y, it.z) }
    }

    private fun renderKeys(
        ownership: VisibilityObservationOwnership,
        geometryRevision: Long,
    ) {
        val keys = rebuildKeys.copyOf(rebuildCount)
        state.startGroup(
            config = VisibilityGridGroupConfig(
                groupId = ownership.captureGroupId,
                groupGeneration = ownership.groupGeneration,
                sessionGeneration = ownership.sessionGeneration,
                voxelSizeMeters = 0.1,
                capacity = 100_000,
                groupFromWorldGl = identityVisibilityGridTransform(),
                restoredGeometryRevision = geometryRevision,
                restoredKeys = keys,
            ),
            geometryRevision = geometryRevision,
            restoredKeys = keys,
        )
        render(state.snapshot(), config)
    }

    @Synchronized
    override fun clear() {
        if (closed) return
        state.stopGroup()
        render(null, null)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        state.dispose()
        render(null, null)
    }
}

/** Typed scalar test receipt; ordinary feature/surface payload bytes are absent. */
internal data class M3VisibilityGridIntegrationReceipt(
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
        fun empty() = M3VisibilityGridIntegrationReceipt("idle", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "none")
        fun seeded(cut: VisibilityObservationOwnership, baseline: M3CommittedEmptyBaseline) =
            M3VisibilityGridIntegrationReceipt("seeded", cut.bindingGeneration, cut.sessionGeneration, cut.groupGeneration, baseline.transactionId, baseline.geometryRevision, baseline.lineageRevision, 0, 0, 0, 0, 0, "none")
    }
}

private fun canonicalOperation(bytes: ByteArray): String = try {
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == 0x4d334350 && input.readInt() == 2)
        input.readFully(ByteArray(32))
        input.readUTF()
        M3PreparedMutationKind.entries[input.readInt()].name
    }
} catch (_: Exception) {
    "invalid"
}

private class M3ExactCurrentDeltaSource : M0aCurrentDeltaSourceV1 {
    private var value: M0aCurrentDeltaReceiptV1? = null
    @Synchronized fun retain(receipt: M0aCurrentDeltaReceiptV1) { check(value == null); value = receipt }
    @Synchronized override fun selectCurrentDelta(selector: M0aCurrentDeltaSelectorV1) = value?.takeIf { it.selector == selector }
    @Synchronized fun acknowledge(selector: M0aCurrentDeltaSelectorV1) { check(value?.selector == selector); value = null }
    @Synchronized fun clear() { value = null }
}
