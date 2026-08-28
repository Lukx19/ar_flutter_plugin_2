package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.m0.M0aCurrentDeltaReceiptV1
import com.uhg0.ar_flutter_plugin_2.m0.M0aCurrentDeltaSelectorV1
import com.uhg0.ar_flutter_plugin_2.m0.M0aCurrentDeltaSourceV1
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    @Volatile private var closed = false
    private var active = 0
    private var cut: VisibilityObservationOwnership? = null
    private var baseline: M3CommittedEmptyBaseline? = null
    private var kernel: M3FeatureFusionKernel? = null
    private var owner: M3SurfaceOwnership? = null
    private var batchSequence = 0L
    private var nextTransactionId = 0L
    private var pending: M0aCurrentDeltaSelectorV1? = null
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
        val fused = requireNotNull(kernel).accept(
            M3FeatureFusionBatch(
                sequence = ++batchSequence,
                timestampNs = observation.frame.sourceTimestampNs,
                observations = observation.samples.map {
                    M3FeatureFusionEvidence(it.xWorld, it.yWorld, it.zWorld, 2, it.id)
                },
            ),
        )
        val accepted = fused as? M3FeatureFusionResult.Accepted ?: run {
            rejected++
            receipt = receipt.copy(status = "kernelRefused", rejected = rejected)
            return@mutate
        }
        val targets = accepted.candidates.filter { M3Voxel(it.x, it.y, it.z) !in knownVoxels }
        if (targets.isEmpty()) return@mutate
        val surfaceOwner = requireNotNull(owner)
        val base = requireNotNull(baseline)
        val command = M3CanonicalTransactionCommand(
            commandId = "${observation.ownership.bindingGeneration}:${observation.ownership.lifecycleSequence}:${batchSequence}",
            kind = M3CanonicalOperation.CREATE,
            expectedGeometryRevision = base.geometryRevision,
            expectedLineageRevision = base.lineageRevision,
            sourceIds = emptyList(),
            targets = targets.map {
                M3CanonicalTarget(
                    voxel = M3Voxel(it.x, it.y, it.z),
                    normalOctX = if (it.normalOctant and 2 == 0) -1 else 1,
                    normalOctY = if (it.normalOctant and 1 == 0) -1 else 1,
                    normalConfidence = it.observationCount.coerceIn(0, 255),
                )
            },
        )
        val result = surfaceOwner.transact(command) as? M3CanonicalTransactionResult.Accepted ?: run {
            rejected++
            receipt = receipt.copy(status = "canonicalRefused", rejected = rejected)
            return@mutate
        }
        if (closed || ownership() != observation.ownership) {
            // The owner was fenced before a publication cut.  Never allow an
            // old view to queue a receipt through a replacement binding.
            fenced++
            return@mutate
        }
        publishCommittedCreate(observation.ownership, result)
    }

    /** #63 owns depth semantics; this feature-only integration refuses it. */
    override fun admitDepth(observation: VisibilityDepthObservation) {
        beforeAdmission()
        synchronized(lock) {
            rejected++
            receipt = receipt.copy(status = "depthDeferred", rejected = rejected)
        }
    }

    override fun rollover(ownership: VisibilityObservationOwnership) {
        drain()
        synchronized(lock) {
            if (closed || this.ownership() != ownership) return
            closeOwner()
            cut = null
            baseline = null
            receipt = M3VisibilityGridIntegrationReceipt.empty()
        }
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
        synchronized(lock) { if (closed) return; closed = true }
        drain()
        synchronized(lock) { closeOwner() }
        if (ownsExecutor) executor.shutdownNow()
    }

    private fun mutate(expected: VisibilityObservationOwnership, work: () -> Unit) {
        if (ownership() != expected) {
            synchronized(lock) { fenced++ }
            return
        }
        executor.submit {
            synchronized(lock) { active++ }
            try {
                synchronized(lock) {
                    if (closed || ownership() != expected) { fenced++; return@submit }
                }
                work()
            } finally {
                synchronized(lock) { active-- }
            }
        }.get()
    }

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
        renderer.project(canonical, result.targets)
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
    }

    private fun drain() { executor.submit {}.get(2, TimeUnit.SECONDS) }
}

internal fun interface M3CommittedRendererProjection {
    fun project(receipt: M3CanonicalTransactionReceipt, rows: List<M3SurfaceOwner>)
    companion object { val NONE = M3CommittedRendererProjection { _, _ -> } }
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
