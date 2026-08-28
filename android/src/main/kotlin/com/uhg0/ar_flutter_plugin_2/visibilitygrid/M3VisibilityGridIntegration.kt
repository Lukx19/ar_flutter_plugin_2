package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.m0.M0aCurrentDeltaReceiptV1
import com.uhg0.ar_flutter_plugin_2.m0.M0aCurrentDeltaSelectorV1
import com.uhg0.ar_flutter_plugin_2.m0.M0aCurrentDeltaSourceV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudNativeConfig
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
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
    private val renderer: M3CommittedRendererProjection = M3CommittedRendererProjection.NONE,
    private val beforeAdmission: () -> Unit = {},
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
    private var batchSequence = 0L
    private var nextTransactionId = 0L
    private var pending: M0aCurrentDeltaSelectorV1? = null
    private var unpublished: M3CanonicalTransactionResult.Accepted? = null
    private val retainedDelta = M3ExactCurrentDeltaSource()
    private val knownVoxels = hashSetOf<M3Voxel>()
    @Volatile private var committed = 0L
    @Volatile private var rejected = 0L
    @Volatile private var fenced = 0L
    @Volatile private var receipt = M3VisibilityGridIntegrationReceipt.empty()

    init {
        binding.attachM3AcknowledgementListener(::acknowledge)
    }

    override fun admitFeature(observation: VisibilityFeatureObservation) = mutate(observation.ownership) {
        beforeAdmission()
        if (isFenced(observation.ownership)) return@mutate
        if (pending != null) {
            rejected++
            receipt = receipt.copy(status = "awaitingExactAck", rejected = rejected)
            return@mutate
        }
        ensureOpened(observation.ownership) ?: return@mutate
        if (committed > 0L) {
            // #63 owns relocation/conflict semantics. Feature-only #62 may
            // publish the initial qualified CREATE and nothing beyond it.
            rejected++
            receipt = receipt.copy(status = "featureConflictDeferred", rejected = rejected)
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
        val targets = accepted.delta.mapNotNull { (it as? M3FeatureFusionChange.Upsert)?.candidate }
            .filter { M3Voxel(it.x, it.y, it.z) !in knownVoxels }
        if (targets.isEmpty()) return@mutate
        val surfaceOwner = requireNotNull(owner)
        val base = requireNotNull(baseline)
        val command = M3CanonicalTransactionCommand(
            commandId = "${observation.ownership.bindingGeneration}:${observation.ownership.lifecycleSequence}:${batchSequence}",
            kind = M3CanonicalOperation.CREATE,
            expectedGeometryRevision = base.geometryRevision,
            expectedLineageRevision = base.lineageRevision,
            sourceIds = emptyList(),
            targets = targets.mapNotNull { candidate ->
                // #111 owns the identity decision for a second opposing face;
                // the current #62 CREATE consumes only the primary hypothesis.
                candidate.normalCandidates.firstOrNull { it.face == M3FeatureNormalFace.PRIMARY }?.let {
                M3CanonicalTarget(
                    voxel = M3Voxel(candidate.x, candidate.y, candidate.z),
                    normalOctX = it.normalOctX,
                    normalOctY = it.normalOctY,
                    normalConfidence = it.normalConfidence,
                )
                }
            },
        )
        synchronized(publicationGate) {
            if (isFenced(observation.ownership)) return@mutate
            val result = surfaceOwner.transact(command) as? M3CanonicalTransactionResult.Accepted ?: run {
                rejected++
                receipt = receipt.copy(status = "canonicalRefused", rejected = rejected)
                return@mutate
            }
            if (isFenced(observation.ownership)) {
                // Binding replacement is independent of the lifecycle gate.
                // Keep the durable result private rather than publishing it
                // through a stale view.
                fenced++
                unpublished = result
                receipt = receipt.copy(status = "publicationDeferred", fenced = fenced)
                return@mutate
            }
            publishCommittedCreate(observation.ownership, result)
        }
    }

    /** #63 owns depth semantics; this feature-only integration refuses it. */
    override fun admitDepth(observation: VisibilityDepthObservation) {
        beforeAdmission()
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
        drain()
    }

    override fun resume() {
        synchronized(publicationGate) {
            if (closed) return
            paused = false
            admissionEpoch.incrementAndGet()
        }
        val expected = ownership() ?: return
        val retained = unpublished ?: return
        executor.submit {
            synchronized(publicationGate) {
                if (!isFenced(expected) && pending == null && unpublished === retained) {
                    unpublished = null
                    publishCommittedCreate(expected, retained)
                }
            }
        }.get()
    }

    override fun rollover(ownership: VisibilityObservationOwnership) {
        synchronized(publicationGate) {
            paused = true
            admissionEpoch.incrementAndGet()
        }
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
                synchronized(lock) {
                    if (closed || paused || admissionEpoch.get() != epoch || ownership() != expected) {
                        fenced++
                        return@submit
                    }
                }
                work()
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
        val opened = M3SurfaceOwnership.open(
            group = M3SurfaceGroup(expected.captureGroupId),
            directory = directory,
            configuration = M3SurfaceOwnershipConfiguration(seededEmptyBaseline = seeded),
        ) as? M3SurfaceOwnershipOpenResult.Opened ?: run { rejected++; return null }
        owner = opened.ownership
        kernel = M3FeatureFusionKernel()
        cut = expected
        baseline = seeded
        nextTransactionId = seeded.transactionId + 1
        batchSequence = 0
        knownVoxels.clear()
        receipt = M3VisibilityGridIntegrationReceipt.seeded(expected, seeded)
        opened.ownership.currentCanonicalTransaction()?.let { restored ->
            if (restored.receipt.kind != M3CanonicalOperation.CREATE ||
                restored.receipt.geometryRevision != seeded.geometryRevision + 1 ||
                restored.receipt.lineageRevision != seeded.lineageRevision
            ) {
                rejected++
                receipt = receipt.copy(status = "restoreFork", rejected = rejected)
                return null
            }
            publishCommittedCreate(expected, restored)
            return null
        }
        return seeded
    }

    private fun publishCommittedCreate(
        expected: VisibilityObservationOwnership,
        result: M3CanonicalTransactionResult.Accepted,
    ) {
        val canonical = result.receipt
        val selector = M0aCurrentDeltaSelectorV1(
            transactionId = nextTransactionId++,
            targetGeometryRevision = canonical.geometryRevision,
            targetLineageRevision = canonical.lineageRevision,
        )
        retainedDelta.retain(
            M0aCurrentDeltaReceiptV1(
                selector,
                canonical.geometryRevision - 1,
                canonical.canonicalBytes.toByteArray(),
            ),
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
        renderer.project(expected, canonical, result.targets)
        knownVoxels += result.targets.map { it.voxel }
        committed++
        receipt = M3VisibilityGridIntegrationReceipt(
            "pendingAck", expected.bindingGeneration, expected.sessionGeneration,
            expected.groupGeneration, selector.transactionId, canonical.geometryRevision,
            canonical.lineageRevision, canonical.canonicalBytes.size, result.targets.size,
            committed, rejected, fenced,
        )
    }

    private fun acknowledge(selector: M0aCurrentDeltaSelectorV1) {
        if (closed) return
        runCatching {
            executor.submit {
                synchronized(lock) {
                    if (pending != selector || closed) return@synchronized
                    retainedDelta.acknowledge(selector)
                    pending = null
                    receipt = receipt.copy(status = "acknowledged")
                }
            }
        }
    }

    private fun closeOwner() {
        retainedDelta.clear()
        pending = null
        owner?.close()
        owner = null
        kernel = null
        knownVoxels.clear()
        unpublished = null
    }

    private fun drain() { executor.submit {}.get(2, TimeUnit.SECONDS) }
}

internal interface M3CommittedRendererProjection : AutoCloseable {
    fun project(
        ownership: VisibilityObservationOwnership,
        receipt: M3CanonicalTransactionReceipt,
        rows: List<M3SurfaceOwner>,
    )
    fun clear() = Unit
    override fun close() = Unit
    companion object {
        val NONE = object : M3CommittedRendererProjection {
            override fun project(
                ownership: VisibilityObservationOwnership,
                receipt: M3CanonicalTransactionReceipt,
                rows: List<M3SurfaceOwner>,
            ) = Unit
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

    @Synchronized
    override fun project(
        ownership: VisibilityObservationOwnership,
        receipt: M3CanonicalTransactionReceipt,
        rows: List<M3SurfaceOwner>,
    ) {
        check(!closed)
        require(rows.all { it.group.value == ownership.captureGroupId })
        val keys = rows.map { packVisibilityGridKey(it.voxel.x, it.voxel.y, it.voxel.z) }.toLongArray()
        state.startGroup(
            config = VisibilityGridGroupConfig(
                groupId = ownership.captureGroupId,
                groupGeneration = ownership.groupGeneration,
                sessionGeneration = ownership.sessionGeneration,
                voxelSizeMeters = 0.1,
                capacity = 100_000,
                groupFromWorldGl = identityVisibilityGridTransform(),
                restoredGeometryRevision = receipt.geometryRevision,
                restoredKeys = keys,
            ),
            geometryRevision = receipt.geometryRevision,
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
) {
    companion object {
        fun empty() = M3VisibilityGridIntegrationReceipt("idle", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        fun seeded(cut: VisibilityObservationOwnership, baseline: M3CommittedEmptyBaseline) =
            M3VisibilityGridIntegrationReceipt("seeded", cut.bindingGeneration, cut.sessionGeneration, cut.groupGeneration, baseline.transactionId, baseline.geometryRevision, baseline.lineageRevision, 0, 0, 0, 0, 0)
    }
}

private class M3ExactCurrentDeltaSource : M0aCurrentDeltaSourceV1 {
    private var value: M0aCurrentDeltaReceiptV1? = null
    @Synchronized fun retain(receipt: M0aCurrentDeltaReceiptV1) { check(value == null); value = receipt }
    @Synchronized override fun selectCurrentDelta(selector: M0aCurrentDeltaSelectorV1) = value?.takeIf { it.selector == selector }
    @Synchronized fun acknowledge(selector: M0aCurrentDeltaSelectorV1) { check(value?.selector == selector); value = null }
    @Synchronized fun clear() { value = null }
}
