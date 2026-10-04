package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.os.Debug
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CurrentDeltaReceiptV1
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CurrentDeltaSelectorV1
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CurrentDeltaSourceV1
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CommittedBaselineV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageCommittedRows
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRowsQualifier
import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudNativeConfig
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.sceneview.BoundedCoveragePresentation
import com.uhg0.ar_flutter_plugin_2.sceneview.CoveragePresentationMode
import com.uhg0.ar_flutter_plugin_2.sceneview.PresentationDescriptor
import com.uhg0.ar_flutter_plugin_2.sceneview.CoveragePresentationSelector
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private enum class PendingPublicationGateResult { READY, REJECTED }

private const val DEFERRED_DEPTH_OBSERVATION_OWNER_BYTES = 64L
// Portable object header + two Longs + eighteen Ints; accepted receipts alias
// the kernel's prepared/committed output and are already covered by its reserve.
internal const val RETAINED_DEPTH_REFUSAL_RECEIPT_BYTES = 104L
internal const val VISIBILITY_GRID_INTEGRATION_SCALAR_OWNER_BYTES = 364L
private const val UNAVAILABLE_ALLOCATION_BYTES = -1L

private data class PendingDepthCommit(
    val offerLedger: DepthOfferTimingLedger?,
    val offerId: Long,
    val ownership: VisibilityObservationOwnership,
    val mutation: PreparedCanonicalMutation,
    val geometryCut: CommittedGeometryCut,
    val sequence: Long,
    val admissionStartedNs: Long,
    val lookupMicros: Long,
    val mutationStartedNs: Long,
    val lookupAllocatedBytes: Long,
    val mutationStartedAllocatedBytes: Long,
)

private data class DeferredDepthObservation(
    val observation: VisibilityDepthObservation,
    val admissionStartedNs: Long,
)

private data class PendingDepthAdmissionTiming(
    val offerLedger: DepthOfferTimingLedger?,
    val offerId: Long,
    val sequence: Long,
    val admissionStartedNs: Long,
    val lookupMicros: Long,
    val mutationMicros: Long,
    val publicationStartedNs: Long,
    val lookupAllocatedBytes: Long,
    val mutationAllocatedBytes: Long,
    val publicationStartedAllocatedBytes: Long,
)

private data class PendingFeatureAdmissionTiming(
    val sequence: Long,
    val admissionStartedNs: Long,
    val planningMicros: Long,
    val mutationMicros: Long,
    val serializationMicros: Long,
    val publicationStartedNs: Long,
    val publicationMicros: Long,
    val planningAllocatedBytes: Long,
    val mutationAllocatedBytes: Long,
    val serializationAllocatedBytes: Long,
    val publicationStartedAllocatedBytes: Long,
    val publicationAllocatedBytes: Long,
)

private data class PendingRendererRebuild(
    val ownership: VisibilityObservationOwnership,
    val transactionId: Long,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val hydrateKernel: Boolean,
) {
    companion object {
        // Conservative portable model: object/header and alignment (24), one
        // ownership reference (8), three Long scalars (24), Boolean slot (8).
        const val PORTABLE_BYTES = 64L
    }
}

private data class RendererRebuildSuccess(val rowCount: Int)

internal data class PendingDepthRetentionReceipt(
    val pendingOwnerScalarBytes: Long,
    val mutationPlanBytes: Long,
    val geometryCutBytes: Long,
    val featureRemapPrimitiveBytes: Long,
    val depthPreparedBytes: Long,
    val totalBytes: Long,
    val budgetBytes: Long = BUDGET_BYTES,
) {
    companion object {
        const val PENDING_OWNER_SCALAR_BYTES = 56L
        const val BUDGET_BYTES = 32L * 1024L * 1024L
        private const val MAXIMUM_ROWS = 100_000
        private const val DEPTH_EVIDENCE_ROW_BYTES = 32L

        internal fun create(
            mutationPlanBytes: Long,
            geometryCutBytes: Long,
            featureRemapPrimitiveBytes: Long,
            depthPreparedBytes: Long,
        ): PendingDepthRetentionReceipt? = try {
            if (mutationPlanBytes < 0 || geometryCutBytes < 0 ||
                featureRemapPrimitiveBytes < 0 || depthPreparedBytes < 0
            ) return null
            val total = Math.addExact(
                Math.addExact(PENDING_OWNER_SCALAR_BYTES, mutationPlanBytes),
                Math.addExact(
                    geometryCutBytes,
                    Math.addExact(featureRemapPrimitiveBytes, depthPreparedBytes),
                ),
            )
            PendingDepthRetentionReceipt(
                PENDING_OWNER_SCALAR_BYTES, mutationPlanBytes, geometryCutBytes,
                featureRemapPrimitiveBytes, depthPreparedBytes, total,
            )
        } catch (_: ArithmeticException) {
            null
        }

        internal fun maximumModeled(): PendingDepthRetentionReceipt = requireNotNull(create(
            CanonicalActivationResources.MAXIMUM_PROFILE_RETAINED_BYTES,
            CommittedGeometryCut.modeledRetainedBytes(MAXIMUM_ROWS, MAXIMUM_ROWS),
            FeatureFusionKernel.maximumPendingCanonicalRemapPrimitiveBytes(),
            Math.multiplyExact(MAXIMUM_ROWS.toLong(), DEPTH_EVIDENCE_ROW_BYTES),
        ))
    }
}

internal data class DepthPublicationDeferralSnapshot(
    val deferred: Long,
    val replaced: Long,
    val retried: Long,
    val fenced: Long,
    val released: Long,
    val pendingPayloadBytes: Long,
) {
    fun toWireMap(): Map<String, Any> = mapOf(
        "deferred" to deferred,
        "replaced" to replaced,
        "retried" to retried,
        "fenced" to fenced,
        "released" to released,
        "pendingPayloadBytes" to pendingPayloadBytes,
    )
}

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
    private val depthKernelFactory: (VisibilityGroupFrame) -> DepthEvidenceKernel = { DepthEvidenceKernel() },
    private val featureKernelFactory: () -> FeatureFusionKernel = { FeatureFusionKernel() },
    private val beforeAdmission: () -> Unit = {},
    private val afterLifecycleFence: () -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val ownsExecutor: Boolean = true,
    private val retainedRebindDrainTimeoutMilliseconds: Long = 30_000L,
    /**
     * Optional debug-only ART counter. A null provider keeps the hot mapper
     * path free of Debug.getRuntimeStat calls in ordinary builds. The provider
     * is supplied by the debuggable host when attribution is requested.
     */
    private val allocationCounter: (() -> Long?)? = null,
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
    private var depthKernel: DepthEvidenceKernel? = null
    private var owner: SurfaceOwnership? = null
    private var resources: CanonicalRuntimeResources? = null
    private var batchSequence = 0L
    private var nextTransactionId = 0L
    private var pending: CurrentDeltaSelectorV1? = null
    @Volatile private var pendingPublicationForIngress = false
    private var pendingQueued = false
    private var pendingGeometryCut: CommittedGeometryCut? = null
    private var pendingRendererApplied = false
    private var pendingRendererRebuild: PendingRendererRebuild? = null
    private var pendingBindingAcknowledged = false
    private var pendingEarlyAcknowledged = false
    private var pendingRendererRows = 0
    private var pendingCanonicalAcknowledgement: CanonicalAcknowledgement? = null
    private var pendingDepthCommit: PendingDepthCommit? = null
    /**
     * One replaceable deferred depth value. The producer callback may clear
     * this slot while the integration executor is busy; keep that transfer
     * lock-free so frame ingress never waits for canonical work.
     */
    private val deferredDepthObservation = AtomicReference<DeferredDepthObservation?>(null)
    private val deferredDepthIngressReplacedCount = AtomicLong(0L)
    private val deferredDepthIngressReleasedCount = AtomicLong(0L)
    private var deferredDepthCount = 0L
    private var deferredDepthReplacedCount = 0L
    private var deferredDepthRetriedCount = 0L
    private var deferredDepthFencedCount = 0L
    private var deferredDepthReleasedCount = 0L
    @Volatile private var pendingDepthTiming: PendingDepthAdmissionTiming? = null
    @Volatile private var pendingFeatureTiming: PendingFeatureAdmissionTiming? = null
    private var depthTimingCompletedCount = 0L
    private var depthTimingSequence = 0L
    private var depthTimingLookupMicros = 0L
    private var depthTimingMutationMicros = 0L
    private var depthTimingPublicationAckMicros = 0L
    private var depthTimingEndToEndMicros = 0L
    private var depthTimingLookupAllocatedBytes = -1L
    private var depthTimingMutationAllocatedBytes = -1L
    private var depthTimingPublicationAckAllocatedBytes = -1L
    private var depthTimingLookupAllocatedBytesTotal = 0L
    private var depthTimingLookupAllocationSamples = 0L
    private var depthTimingMutationAllocatedBytesTotal = 0L
    private var depthTimingMutationAllocationSamples = 0L
    private var depthTimingPublicationAckAllocatedBytesTotal = 0L
    private var depthTimingPublicationAckAllocationSamples = 0L
    private var featureTimingCompletedCount = 0L
    private var featureTimingSequence = 0L
    private var featureTimingPlanningMicros = 0L
    private var featureTimingMutationMicros = 0L
    private var featureTimingSerializationMicros = 0L
    private var featureTimingPublicationMicros = 0L
    private var featureTimingEndToEndMicros = 0L
    private var featureTimingPlanningAllocatedBytes = -1L
    private var featureTimingMutationAllocatedBytes = -1L
    private var featureTimingSerializationAllocatedBytes = -1L
    private var featureTimingPublicationAllocatedBytes = -1L
    private var featureTimingPlanningAllocatedBytesTotal = 0L
    private var featureTimingPlanningAllocationSamples = 0L
    private var featureTimingMutationAllocatedBytesTotal = 0L
    private var featureTimingMutationAllocationSamples = 0L
    private var featureTimingSerializationAllocatedBytesTotal = 0L
    private var featureTimingSerializationAllocationSamples = 0L
    private var featureTimingPublicationAllocatedBytesTotal = 0L
    private var featureTimingPublicationAllocationSamples = 0L
    private var lastDepthLookupReceipt: BoundedCanonicalLookupReceipt? = null
    private var lastDepthLookupReason: BoundedCanonicalLookupReason? = null
    private var lastDepthEvidenceRefusal: DepthEvidenceRefusal? = null
    @Volatile private var lastDepthAdmissionStatus: String? = null
    private var lastDepthEvidenceReceipt: DepthEvidenceReceipt? = null
    private var lastDepthSelectedSamples = 0
    private var lastDepthSourceRejectedSamples = 0
    private var lastDepthLeaseRejectedSamples = -1
    private var canonicalSurfaceHighWater = 0L
    private var associationHighWater = 0L
    private var canonicalOwnedBytesHighWater = 0L
    private val retainedDelta = ExactCurrentDeltaSource()
    @Volatile private var committed = 0L
    @Volatile private var committedFeatures = 0L
    @Volatile private var admittedDepths = 0L
    @Volatile private var rejected = 0L
    @Volatile private var fenced = 0L
    @Volatile private var receipt = VisibilityGridIntegrationReceipt.empty()

    init {
        require(retainedRebindDrainTimeoutMilliseconds > 0L)
        binding.attachPublicationAcknowledgementListener(::acknowledge)
        binding.attachRetainedRendererRebindListener(::rebindRetainedCanonicalCut)
    }

    /** Authenticates and installs one replacement binding over the exact retained cut. */
    private fun rebindRetainedCanonicalCut(
        nextOwnership: VisibilityObservationOwnership,
        freshBaseline: committedEmptyBaseline,
        authoritativeBaseline: CommittedBaselineV1,
    ): Boolean {
        drain(retainedRebindDrainTimeoutMilliseconds)
        return synchronized(lock) {
            if (closed || freshBaseline.transactionId != 0L ||
                freshBaseline.geometryRevision != authoritativeBaseline.geometryRevision ||
                freshBaseline.lineageRevision != authoritativeBaseline.lineageRevision
            ) return@synchronized false

            val previousOwnership = cut
            if (previousOwnership == null) {
                if (resources != null || owner != null || kernel != null || depthKernel != null ||
                    pending != null || pendingQueued || pendingRendererApplied ||
                    pendingRendererRebuild != null || pendingCanonicalAcknowledgement != null ||
                    deferredDepthObservation.get() != null ||
                    authoritativeBaseline.geometryRevision != 1L ||
                    authoritativeBaseline.lineageRevision != 1L ||
                    !renderer.rebindRetainedEmptyCanonicalCut(
                        nextOwnership,
                        authoritativeBaseline,
                    )
                ) return@synchronized false

                // Canonical storage intentionally remains lazy until the first
                // observation.  The replacement binding owns the authenticated
                // empty cut now, while ensureOpened will create the integration
                // resources directly against that replacement ownership.
                receipt = receipt.copy(
                    status = "rendererRebound",
                    bindingGeneration = nextOwnership.bindingGeneration,
                    sessionGeneration = nextOwnership.sessionGeneration,
                    groupGeneration = nextOwnership.groupGeneration,
                    transactionId = 0L,
                    geometryRevision = authoritativeBaseline.geometryRevision,
                    lineageRevision = authoritativeBaseline.lineageRevision,
                )
                return@synchronized true
            }
            val rebindCut = RetainedRendererBindingCut(
                previousOwnership = previousOwnership,
                nextOwnership = nextOwnership,
                authoritativeBaseline = authoritativeBaseline,
                previousBindingTransactionId = receipt.transactionId,
            )
            if (!renderer.canRebindRetainedCanonicalCut(rebindCut)) return@synchronized false

            val exactPending = pending
            if (exactPending != null) {
                if (exactPending.transactionId != authoritativeBaseline.transactionId ||
                    exactPending.targetGeometryRevision != authoritativeBaseline.geometryRevision ||
                    exactPending.targetLineageRevision != authoritativeBaseline.lineageRevision ||
                    !pendingQueued || !pendingRendererApplied
                ) return@synchronized false
                pendingBindingAcknowledged = true
                finishPendingAcknowledgement(exactPending, drainDeferredDepth = false)
                releaseDeferredDepth(fenced = true)
                if (pending != null) return@synchronized false
            }

            check(renderer.rebindRetainedCanonicalCut(rebindCut)) {
                "Preflighted retained renderer cut changed during synchronous rebind"
            }

            cut = nextOwnership
            baseline = freshBaseline
            nextTransactionId = 1L
            receipt = receipt.copy(
                status = "rendererRebound",
                bindingGeneration = nextOwnership.bindingGeneration,
                sessionGeneration = nextOwnership.sessionGeneration,
                groupGeneration = nextOwnership.groupGeneration,
                transactionId = 0L,
                geometryRevision = authoritativeBaseline.geometryRevision,
                lineageRevision = authoritativeBaseline.lineageRevision,
            )
            true
        }
    }

    override fun admitFeature(observation: VisibilityFeatureObservation) {
        try {
            mutate(observation.ownership) {
        if (isFenced(observation.ownership)) return@mutate
        if (drainPendingPublication() == PendingPublicationGateResult.REJECTED) return@mutate
        if (retryPendingDepthCommit(observation.ownership)) return@mutate
        ensureOpened(observation.ownership) ?: return@mutate
        if (drainPendingPublication() == PendingPublicationGateResult.REJECTED) return@mutate
        val admissionStartedNs = System.nanoTime()
        val featureSequence = ++batchSequence
        val planningStartedAllocatedBytes = allocationNow()
        // This adapter is the only CAPTURE-INGRESS-to-canonical surface conversion point.  It creates
        // fixed camera/sample evidence before the kernel can mutate anything.
        val fused = if (observation.packedSamples != null) {
            requireNotNull(kernel).preparePacked(observation, featureSequence)
        } else {
            val normalEvidence = (FeatureNormalEvidence.from(observation) as? FeatureNormalEvidence.Conversion.Accepted)
                ?: run {
                    rejected++
                    receipt = receipt.copy(status = "normalEvidenceRefused", rejected = rejected)
                    return@mutate
                }
            requireNotNull(kernel).prepare(
                FeatureFusionBatch(
                    sequence = featureSequence,
                    timestampNs = observation.frame.sourceTimestampNs,
                    observations = observation.samples.zip(normalEvidence.evidence).map { (sample, normal) ->
                        FeatureFusionEvidence(sample.xWorld, sample.yWorld, sample.zWorld, 2, sample.id, normal)
                    },
                ),
            )
        }
        val planningAllocatedBytes = allocationDelta(
            planningStartedAllocatedBytes,
            allocationNow(),
        ) ?: UNAVAILABLE_ALLOCATION_BYTES
        val accepted = fused as? FeatureFusionResult.Accepted ?: run {
            rejected++
            receipt = receipt.copy(status = "kernelRefused", rejected = rejected)
            return@mutate
        }
        if (isFenced(observation.ownership)) {
            requireNotNull(kernel).discardPrepared()
            return@mutate
        }
        val featureTiming = PendingFeatureAdmissionTiming(
            sequence = featureSequence,
            admissionStartedNs = admissionStartedNs,
            planningMicros = elapsedMicros(admissionStartedNs, System.nanoTime()),
            mutationMicros = 0L,
            serializationMicros = 0L,
            publicationStartedNs = 0L,
            publicationMicros = 0L,
            planningAllocatedBytes = planningAllocatedBytes,
            mutationAllocatedBytes = UNAVAILABLE_ALLOCATION_BYTES,
            serializationAllocatedBytes = UNAVAILABLE_ALLOCATION_BYTES,
            publicationStartedAllocatedBytes = UNAVAILABLE_ALLOCATION_BYTES,
            publicationAllocatedBytes = UNAVAILABLE_ALLOCATION_BYTES,
        )
        val state = requireNotNull(owner).activationState()
        if (state?.current == CanonicalActivationCurrent.None &&
            state.cut.liveSurfaceCount == 0
        ) {
            publishInitialV6Create(observation.ownership, accepted.delta, featureTiming)
        } else {
            publishMaterialBatch(observation.ownership, accepted.delta, featureTiming)
        }
            }
        } finally {
            observation.close()
        }
    }

    /** Resolves or rejects the one exact publication that must precede new admission. */
    private fun drainPendingPublication(): PendingPublicationGateResult {
        if (pending == null) return PendingPublicationGateResult.READY
        if ((!pendingQueued || !pendingRendererApplied) && !retryPendingQueue()) {
            rejected++
            receipt = receipt.copy(rejected = rejected)
            return PendingPublicationGateResult.REJECTED
        }
        if (pending != null) {
            rejected++
            receipt = receipt.copy(status = "awaitingExactAck", rejected = rejected)
            return PendingPublicationGateResult.REJECTED
        }
        // The admission that triggered exact queue/rebuild/ACK recovery remains
        // gated even when recovery completed on this drain.
        return PendingPublicationGateResult.REJECTED
    }

    /** Depth and feature ingress share the same serialized publication lane. */
    override fun admitDepth(observation: VisibilityDepthObservation) {
        var retainedByMapper = false
        try {
            mutate(observation.ownership) {
            if (isFenced(observation.ownership)) return@mutate
            if (pending != null) {
                // Once the exact structural ACK is known, a depth turn must
                // first finish any renderer recovery for that same cut. This
                // preserves the one publication lane without leaving a
                // renderer rebuild stranded behind the newest depth slot.
                val acknowledged = synchronized(lock) { pendingBindingAcknowledged }
                if (!acknowledged) {
                    retainedByMapper = deferDepthUntilPublicationAcknowledgement(observation)
                    return@mutate
                }
                if (drainPendingPublication() == PendingPublicationGateResult.REJECTED) {
                    return@mutate
                }
            }
            if (drainPendingPublication() == PendingPublicationGateResult.REJECTED) return@mutate
            if (retryPendingDepthCommit(observation.ownership)) return@mutate
            ensureOpened(observation.ownership) ?: return@mutate
            if (drainPendingPublication() == PendingPublicationGateResult.REJECTED) {
                if (pending != null) {
                    retainedByMapper = deferDepthUntilPublicationAcknowledgement(observation)
                }
                return@mutate
            }
            admitDepthLocked(observation, System.nanoTime())
            lastDepthAdmissionStatus = receipt.status
            }
        } finally {
            if (!retainedByMapper) observation.close()
        }
    }

    override fun pause() {
        synchronized(publicationGate) {
            paused = true
            admissionEpoch.incrementAndGet()
        }
        afterLifecycleFence()
        drain()
        synchronized(lock) {
            discardPendingDepthCommit()
            releaseDeferredDepth(fenced = true)
        }
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
        val alreadyRebound = synchronized(lock) {
            if (closed || this.ownership() != ownership) return
            if (cut == ownership) return@synchronized true
            closeOwner()
            cut = null
            baseline = null
            receipt = VisibilityGridIntegrationReceipt.empty()
            false
        }
        if (!alreadyRebound) renderer.clear()
    }

    override fun snapshot(): VisibilityMappingAdmissionHealth = synchronized(lock) {
        VisibilityMappingAdmissionHealth(
            admittedFeatures = committedFeatures,
            admittedDepths = admittedDepths,
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

    override fun depthAdmissionTiming(): VisibilityDepthAdmissionTiming = synchronized(lock) {
        val pending = pendingDepthTiming
        if (pending == null) {
            return@synchronized VisibilityDepthAdmissionTiming(
                completedCount = depthTimingCompletedCount,
                sequence = depthTimingSequence,
                lookupMicros = depthTimingLookupMicros,
                mutationMicros = depthTimingMutationMicros,
                publicationAckMicros = depthTimingPublicationAckMicros,
                endToEndMicros = depthTimingEndToEndMicros,
                pendingPublicationAck = false,
            )
        }
        val now = System.nanoTime()
        VisibilityDepthAdmissionTiming(
            completedCount = depthTimingCompletedCount,
            sequence = pending.sequence,
            lookupMicros = pending.lookupMicros,
            mutationMicros = pending.mutationMicros,
            publicationAckMicros = elapsedMicros(pending.publicationStartedNs, now),
            endToEndMicros = elapsedMicros(pending.admissionStartedNs, now),
            pendingPublicationAck = true,
        )
    }

    override fun featureAdmissionTiming(): VisibilityFeatureAdmissionTiming = synchronized(lock) {
        val pending = pendingFeatureTiming
        if (pending == null) {
            return@synchronized VisibilityFeatureAdmissionTiming(
                completedCount = featureTimingCompletedCount,
                sequence = featureTimingSequence,
                planningMicros = featureTimingPlanningMicros,
                mutationMicros = featureTimingMutationMicros,
                serializationMicros = featureTimingSerializationMicros,
                publicationMicros = featureTimingPublicationMicros,
                endToEndMicros = featureTimingEndToEndMicros,
                pendingPublicationAck = false,
            )
        }
        VisibilityFeatureAdmissionTiming(
            completedCount = featureTimingCompletedCount,
            sequence = pending.sequence,
            planningMicros = pending.planningMicros,
            mutationMicros = pending.mutationMicros,
            serializationMicros = pending.serializationMicros,
            publicationMicros = pending.publicationMicros,
            endToEndMicros = elapsedMicros(pending.admissionStartedNs, System.nanoTime()),
            pendingPublicationAck = true,
        )
    }

    fun integrationReceipt(): VisibilityGridIntegrationReceipt = synchronized(lock) { receipt }

    /** Frame-thread check with no mapper lock or disk access. */
    fun hasPendingPublicationForIngress(): Boolean = pendingPublicationForIngress

    internal fun lastDepthAdmissionStatus(): String? = synchronized(lock) { lastDepthAdmissionStatus }

    /**
     * Keeps the source lane's current/latest contract end to end. When a new
     * copied depth value is about to enter an already resident lane, release
     * the older mapper slot first; the lane then owns at most current+latest.
     */
    override fun beforeDepthObservationOffer(laneHasOutstandingWork: Boolean) {
        if (!laneHasOutstandingWork) return
        // The source lane has its own current/latest slots. Atomically hand
        // off the mapper's latest slot before that lane accepts another value;
        // this keeps one deferred payload from becoming a third retained map.
        val deferred = deferredDepthObservation.getAndSet(null) ?: return
        deferredDepthIngressReplacedCount.incrementAndGet()
        deferredDepthIngressReleasedCount.incrementAndGet()
        deferred.observation.finishDebugOffer(DepthOfferTimingLedger.REPLACED)
        deferred.observation.close()
        lastDepthAdmissionStatus = "depthPublicationDeferredReplacedByLatestIngress"
    }

    override fun retainedDepthPayloadBytes(): Long =
        deferredDepthObservation.get()?.observation?.payloadBytes?.toLong() ?: 0L

    /** Latest kernel preparation attempt, not a claim of canonical admission. */
    internal fun depthEvidenceDiagnostics(): Map<String, Any> = synchronized(lock) {
        val evidence = lastDepthEvidenceReceipt
        val counts = mapOf(
            "selectedSamples" to lastDepthSelectedSamples,
            "sourceRejectedSamples" to lastDepthSourceRejectedSamples,
            // This is the lease's copy of the source rejection count, not
            // additional kernel rejection. -1 means no packed lease exists.
            "leaseRejectedSamples" to lastDepthLeaseRejectedSamples,
            "preparationOutcome" to when {
                evidence == null -> "none"
                lastDepthEvidenceRefusal != null -> "refused"
                else -> "prepared"
            },
        )
        if (evidence == null) counts else counts + mapOf(
            "acceptedSamples" to evidence.acceptedSamples,
            "rejectedSamples" to evidence.rejectedSamples,
            "rayVisits" to evidence.rayVisits,
            "touchedEvidenceRows" to evidence.touchedEvidenceRows,
            "independentDirectionVotes" to evidence.independentDirectionVotes,
            "createCount" to evidence.createCount,
            "refineCount" to evidence.refineCount,
            "relocateCount" to evidence.relocateCount,
            "mergeCount" to evidence.mergeCount,
            "splitCount" to evidence.splitCount,
            "replaceCount" to evidence.replaceCount,
            "removeCount" to evidence.removeCount,
        )
    }

    /** Fixed native-owner scalars for the debug pressure receipt. */
    internal fun pressureSnapshot(): CanonicalVisibilityPressureSnapshot = synchronized(lock) {
        // A debug snapshot must not make the platform thread wait behind the
        // canonical lease currently borrowed by an admitted mapper task. The
        // last completed high-water remains authoritative until that task
        // leaves the lane; idle snapshots and close refresh it exactly.
        if (active == 0) recordCanonicalPressureHighWater()
        val rendererPressure = renderer.pressureRevisions()
        CanonicalVisibilityPressureSnapshot(
            geometryRevision = receipt.geometryRevision,
            lineageRevision = receipt.lineageRevision,
            coverageRevision = rendererPressure.coverageRevision,
            styleRevision = rendererPressure.styleRevision,
            canonicalSurfaceHighWater = canonicalSurfaceHighWater,
            associationHighWater = associationHighWater,
            canonicalOwnedBytes = canonicalOwnedBytesHighWater,
            terminalGuidanceStatus = receipt.status,
        )
    }

    internal fun pendingDepthRetentionReceipt(): PendingDepthRetentionReceipt? = synchronized(lock) {
        pendingDepthCommit?.let(::retentionReceipt)
    }

    internal fun depthPublicationDeferralSnapshot(): DepthPublicationDeferralSnapshot = synchronized(lock) {
        DepthPublicationDeferralSnapshot(
            deferred = deferredDepthCount,
            replaced = deferredDepthReplacedCount + deferredDepthIngressReplacedCount.get(),
            retried = deferredDepthRetriedCount,
            fenced = deferredDepthFencedCount,
            released = deferredDepthReleasedCount + deferredDepthIngressReleasedCount.get(),
            pendingPayloadBytes = deferredDepthObservation.get()?.observation?.payloadBytes?.toLong() ?: 0L,
        )
    }

    internal fun depthResourceReceipt(): DepthEvidenceResourceReceipt? = synchronized(lock) {
        depthKernel?.resourceReceipt()
    }

    /** Scalar debug evidence; callers sample this off the platform/render lane. */
    internal fun allocationWorkspaceReceipt(): Map<String, Long> = synchronized(lock) {
        buildMap {
            resources?.preparationWorkspaceReceipt()?.let {
                put("canonicalPreparationOwnedCapacityBytes", it.ownedCapacityBytes)
                put("canonicalPreparationGrowthEvents", it.growthEvents)
            }
            depthKernel?.resourceReceipt()?.let {
                put("depthFixedPrimitiveBytes", it.fixedPrimitiveBytes.toLong())
                put("depthResidentEvidenceBytes", it.residentBytes.toLong())
                put("depthPreparedResidentBytes", it.preparedResidentBytes.toLong())
                put("depthModeledMaximumSemanticBytes", it.modeledMaximumSemanticStateBytes.toLong())
            }
            kernel?.resourceReceipt()?.let {
                put("featureOwnedTupleBytes", it.assignedTupleShareBytes.toLong())
            }
            put("mapperAllocationAttributionEnabled", if (allocationCounter == null) 0L else 1L)
            put("mapperDepthLookupAllocatedBytes", depthTimingLookupAllocatedBytes)
            put("mapperDepthMutationAllocatedBytes", depthTimingMutationAllocatedBytes)
            put("mapperDepthPublicationAckAllocatedBytes", depthTimingPublicationAckAllocatedBytes)
            put("mapperFeaturePlanningAllocatedBytes", featureTimingPlanningAllocatedBytes)
            put("mapperFeatureMutationAllocatedBytes", featureTimingMutationAllocatedBytes)
            put("mapperFeatureSerializationAllocatedBytes", featureTimingSerializationAllocatedBytes)
            put("mapperFeaturePublicationAllocatedBytes", featureTimingPublicationAllocatedBytes)
            put("mapperDepthLookupAllocatedBytesTotal", reportedAllocationTotal(
                depthTimingLookupAllocatedBytesTotal,
                depthTimingLookupAllocationSamples,
            ))
            put("mapperDepthLookupAllocationSamples", depthTimingLookupAllocationSamples)
            put("mapperDepthMutationAllocatedBytesTotal", reportedAllocationTotal(
                depthTimingMutationAllocatedBytesTotal,
                depthTimingMutationAllocationSamples,
            ))
            put("mapperDepthMutationAllocationSamples", depthTimingMutationAllocationSamples)
            put("mapperDepthPublicationAckAllocatedBytesTotal", reportedAllocationTotal(
                depthTimingPublicationAckAllocatedBytesTotal,
                depthTimingPublicationAckAllocationSamples,
            ))
            put("mapperDepthPublicationAckAllocationSamples", depthTimingPublicationAckAllocationSamples)
            put("mapperFeaturePlanningAllocatedBytesTotal", reportedAllocationTotal(
                featureTimingPlanningAllocatedBytesTotal,
                featureTimingPlanningAllocationSamples,
            ))
            put("mapperFeaturePlanningAllocationSamples", featureTimingPlanningAllocationSamples)
            put("mapperFeatureMutationAllocatedBytesTotal", reportedAllocationTotal(
                featureTimingMutationAllocatedBytesTotal,
                featureTimingMutationAllocationSamples,
            ))
            put("mapperFeatureMutationAllocationSamples", featureTimingMutationAllocationSamples)
            put("mapperFeatureSerializationAllocatedBytesTotal", reportedAllocationTotal(
                featureTimingSerializationAllocatedBytesTotal,
                featureTimingSerializationAllocationSamples,
            ))
            put("mapperFeatureSerializationAllocationSamples", featureTimingSerializationAllocationSamples)
            put("mapperFeaturePublicationAllocatedBytesTotal", reportedAllocationTotal(
                featureTimingPublicationAllocatedBytesTotal,
                featureTimingPublicationAllocationSamples,
            ))
            put("mapperFeaturePublicationAllocationSamples", featureTimingPublicationAllocationSamples)
        }
    }

    internal fun depthLookupReceipt(): BoundedCanonicalLookupReceipt? = synchronized(lock) {
        lastDepthLookupReceipt
    }

    /** Bounded scalar provenance for a refused or completed depth lookup. */
    internal fun depthLookupDiagnostics(): Map<String, Any> = synchronized(lock) {
        val lookup = lastDepthLookupReceipt ?: return@synchronized emptyMap()
        mapOf(
            "directLookups" to lookup.directLookups,
            "rayCellVisits" to lookup.rayCellVisits,
            "pageReads" to lookup.pageReads,
            "bytesRead" to lookup.bytesRead,
            "refusedByLimit" to lookup.refusedByLimit,
            "boundedReason" to (lastDepthLookupReason?.name ?: "none"),
            "evidenceReason" to (lastDepthEvidenceRefusal?.name ?: "none"),
        )
    }

    internal fun pendingPublicationGeometryCut(): CommittedGeometryCut? = synchronized(lock) { pendingGeometryCut }

    /** Portable scalar owners only; kernel/renderer arrays and phase buffers are named separately. */
    internal fun portableOwnerMemoryReceipt(): RuntimeOwnerMemoryReceipt = synchronized(lock) {
        RuntimeOwnerMemoryReceipt(
            // Renderer-rebuild, depth-lookup, and bounded deferred-depth
            // references plus the early-ACK scalar are part of this owner.
            // The attribution provider reference, seven latest allocation
            // scalars, seven cumulative totals and seven sample counts add
            // 176 bytes even when counter collection is disabled.
            // Three latest-attempt input counts add twelve fixed scalar bytes.
            integrationObjectBytes = VISIBILITY_GRID_INTEGRATION_SCALAR_OWNER_BYTES,
            integrationReceiptBytes = 104 +
                (if (pendingDepthTiming != null) 40L else 0L) +
                (if (pendingFeatureTiming != null) 40L else 0L) +
                (if (pendingDepthCommit != null) 32L else 0L),
            retainedDeltaOwnerBytes = 16,
            pendingRendererRebuildBytes = pendingRendererRebuild?.let {
                PendingRendererRebuild.PORTABLE_BYTES
            } ?: 0,
            retainedDepthLookupReceiptBytes = lastDepthLookupReceipt?.let {
                BoundedCanonicalLookupReceipt.PORTABLE_BYTES
            } ?: 0,
            retainedDepthRefusalReceiptBytes = if (
                lastDepthEvidenceReceipt != null && lastDepthEvidenceRefusal != null
            ) RETAINED_DEPTH_REFUSAL_RECEIPT_BYTES else 0,
            deferredDepthObservationBytes = deferredDepthObservation.get()?.let {
                DEFERRED_DEPTH_OBSERVATION_OWNER_BYTES + it.observation.payloadBytes.toLong()
            } ?: 0,
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
        synchronized(lock) {
            recordCanonicalPressureHighWater()
            closeOwner()
        }
        renderer.close()
        if (ownsExecutor) executor.shutdownNow()
    }

    private fun recordCanonicalPressureHighWater() {
        val feature = kernel?.resourceReceipt() ?: return
        val currentCut = owner?.activationState()?.cut
        canonicalSurfaceHighWater = maxOf(
            canonicalSurfaceHighWater,
            feature.surfaceCount.toLong(),
            currentCut?.liveSurfaceCount?.toLong() ?: 0,
        )
        associationHighWater = maxOf(
            associationHighWater,
            feature.associationCount.toLong(),
            currentCut?.lineageCount?.toLong() ?: 0,
        )
        // Count retained ownership, not just populated feature tuples. Depth
        // evidence rows live inside fixed arrays, so their logical row bytes
        // must not be charged a second time. The complete-current receipt
        // already includes COW proofs/indexes and feature routing ownership.
        val retainedCanonicalBytes = listOf(
            feature.assignedTupleShareBytes.toLong(),
            depthKernel?.resourceReceipt()?.fixedPrimitiveBytes?.toLong() ?: 0,
            resources?.completeCurrentLeaseReceipt()?.retainedTotalBytes ?: 0,
            resources?.retainedScalarMemoryReceipt()?.portableBytes ?: 0,
            portableOwnerMemoryReceipt().portableBytes,
        ).fold(0L, Math::addExact)
        canonicalOwnedBytesHighWater = maxOf(
            canonicalOwnedBytesHighWater,
            retainedCanonicalBytes,
        )
    }

    private fun mutate(expected: VisibilityObservationOwnership, work: () -> Unit) {
        val epoch = admissionEpoch.get()
        if (paused || ownership() != expected) {
            synchronized(lock) { fenced++ }
            return
        }
        val future = executor.submit {
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
        }
        try {
            future.get()
        } catch (failure: ExecutionException) {
            val cause = failure.cause ?: failure
            // Preserve the mapper's failure type for the lane's existing
            // stale-cut recovery. Future.get otherwise hides every failure
            // behind a checked ExecutionException and strands the lane.
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("visibility admission failed", cause)
            }
        }
    }

    private fun isFenced(expected: VisibilityObservationOwnership): Boolean =
        closed || paused || ownership() != expected

    private fun ensureOpened(expected: VisibilityObservationOwnership): committedEmptyBaseline? {
        if (cut == expected) {
            pendingRendererRebuild?.let { rebuild ->
                if (retryRendererRebuild(rebuild) != null) {
                    if (pendingRendererRebuild === rebuild) pendingRendererRebuild = null
                    receipt = receipt.copy(status = "rendererRebuildRecovered")
                }
                return null
            }
            return baseline
        }
        val seeded = binding.committedEmptyBaseline() ?: run { fenced++; return null }
        if (ownership() != expected) { fenced++; return null }
        val group = SurfaceGroup(expected.captureGroupId)
        val runtimeResources = resourcesForGroup(group)
        val openResult = if (!runtimeResources.usesSessionMemory && CanonicalActivationSelector.hasDurableSelector(group, runtimeResources.directory)) {
            runtimeResources.reopen()
        } else {
            runtimeResources.openInitial(seeded)
        }
        val opened = openResult as? SurfaceOwnershipOpenResult.Opened ?: run {
            val refusal = (openResult as SurfaceOwnershipOpenResult.Refused).reason
            val failureStatus = runtimeResources.integrationOpenFailureStatus(refusal)
            runtimeResources.close()
            rejected++
            receipt = receipt.copy(
                status = failureStatus,
                rejected = rejected,
            )
            return null
        }
        resources = runtimeResources
        owner = opened.ownership
        kernel = createBoundFeatureKernel(runtimeResources)
        depthKernel = depthKernelFactory(expected.groupFrame)
        cut = expected
        baseline = seeded
        nextTransactionId = seeded.transactionId + 1
        batchSequence = 0
        receipt = VisibilityGridIntegrationReceipt.seeded(expected, seeded)
        // A restart may have a selected unacknowledged v6 current. It is replayed
        // through the same bounded V2 receipt path before a later batch is admitted.
        opened.ownership.activationState()?.let { state ->
            if (state.currentState is CanonicalCurrentState.Unacknowledged) {
                // The selected current is replayed as the next V2 transaction.
                // Its renderer qualifier must match that queued transaction;
                // transaction zero is stale after the empty baseline cut.
                val rebuilt = rebuildCanonicalRenderer(
                    expected, state, transactionId = nextTransactionId,
                )
                publishV6Current(
                    expected, state,
                    rendererAlreadyCurrentRows = rebuilt?.rowCount,
                    rendererRebuildRequired = rebuilt == null,
                    rendererRebuildHydratesKernel = rebuilt == null,
                )
            } else {
                if (rebuildCanonicalRenderer(expected, state, transactionId = seeded.transactionId) == null) {
                    pendingRendererRebuild = PendingRendererRebuild(
                        expected, 0, state.cut.geometryRevision, state.cut.lineageRevision, true,
                    )
                    receipt = receipt.copy(status = "rendererRebuildPending")
                    return null
                }
            }
        }
        return seeded
    }

    private fun admitDepthLocked(
        observation: VisibilityDepthObservation,
        admissionStartedNs: Long,
    ) {
        // Release the prior diagnostic receipt before preparing another result.
        // Input counts come from existing metadata/columns, with no sample scan.
        clearDepthAttemptDiagnostics()
        lastDepthSelectedSamples = observation.sampleCount
        lastDepthSourceRejectedSamples = observation.sourceRejectedSamples
        lastDepthLeaseRejectedSamples = observation.packedSamples?.rejectedCount ?: -1
        val depth = requireNotNull(depthKernel)
        val groupFrame = observation.ownership.groupFrame
        val groupFromCamera = composeGroupFromCamera(
            groupFrame.groupFromWorldGl,
            observation.frame.pose.worldFromCameraGl,
        ) ?: run {
            depth.discardPrepared()
            rejected++
            receipt = receipt.copy(status = "depthFrameRefused", rejected = rejected)
            return
        }
        val batchSequenceValue = ++batchSequence
        val batch: DepthEvidenceBatch?
        val metadata: DepthEvidenceMetadata?
        if (observation.packedSamples == null) {
            batch = DepthEvidenceBatch(
                sequence = batchSequenceValue,
                sourceTimestampNs = observation.frame.sourceTimestampNs,
                groupFrame = groupFrame,
                groupFromCameraGl = groupFromCamera,
                intrinsics = observation.frame.intrinsics,
                samples = observation.samples,
                sourceRejectedSamples = observation.sourceRejectedSamples,
                tracking = observation.frame.tracking,
            )
            metadata = null
        } else {
            batch = null
            metadata = DepthEvidenceMetadata(
                sequence = batchSequenceValue,
                sourceTimestampNs = observation.frame.sourceTimestampNs,
                groupFrame = groupFrame,
                groupFromCameraGl = groupFromCamera,
                intrinsics = observation.frame.intrinsics,
                sourceRejectedSamples = observation.sourceRejectedSamples,
                tracking = observation.frame.tracking,
            )
        }
        val currentCut = requireNotNull(owner).activationState()?.cut ?: run {
            depth.discardPrepared()
            rejected++
            receipt = receipt.copy(status = "depthCurrentUnavailable", rejected = rejected)
            return
        }
        var featureSources: DepthFeatureSourceTable? = null
        val lookupStartedNs = System.nanoTime()
        val lookupStartedAllocatedBytes = allocationNow()
        val spatialMetadata: DepthEvidenceMetadataView = batch ?: metadata
            ?: error("depth evidence metadata is missing")
        if (!requireNotNull(resources).updateSpatialWindow(spatialMetadata)) {
            depth.discardPrepared()
            rejected++
            receipt = receipt.copy(status = "depthSpatialWindowRefused", rejected = rejected)
            return
        }
        val lookup = requireNotNull(resources).withBoundedCurrent(
            BoundedCanonicalLookupRequest(
                expectedGeometryRevision = currentCut.geometryRevision,
                expectedLineageRevision = currentCut.lineageRevision,
                maximumDirectLookups = 100_000,
                maximumRayCellVisits = 65_536,
                maximumPageReads = 65_536,
                // A two-metre room ray can cross well over 1,000 grid cells.
                // The bounded lookup charges each 16 KiB page read; measured
                // 8 and 16 MiB limits refused the first two-metre ray before
                // its endpoint. Authenticated-cut lookup reuse keeps the
                // populated 2000x2000 fixture below 20 MiB of page reads;
                // retain this finite streaming I/O ceiling.
                maximumBytesRead = 64L * 1024L * 1024L,
            ),
        ) { view ->
            val result = if (batch != null) {
                depth.prepare(batch, view)
            } else {
                depth.prepare(requireNotNull(observation.packedSamples), requireNotNull(metadata), view)
            }
            if (result is DepthEvidenceResult.Accepted && result.changes.isNotEmpty()) {
                featureSources = collectDepthFeatureSources(requireNotNull(kernel), view, result.changes)
            }
            result
        }
        val lookupAllocatedBytes = allocationDelta(
            lookupStartedAllocatedBytes,
            allocationNow(),
        ) ?: UNAVAILABLE_ALLOCATION_BYTES
        val lookupMicros = elapsedMicros(lookupStartedNs, System.nanoTime())
        lastDepthLookupReceipt = lookup.receipt
        lastDepthLookupReason = (lookup as? BoundedCanonicalLookupResult.Refused)?.reason
        lastDepthEvidenceRefusal = when (lookup) {
            is BoundedCanonicalLookupResult.Completed ->
                (lookup.value as? DepthEvidenceResult.Refused)?.reason
            is BoundedCanonicalLookupResult.Refused -> null
        }
        lastDepthEvidenceReceipt = when (val result =
            (lookup as? BoundedCanonicalLookupResult.Completed)?.value
        ) {
            is DepthEvidenceResult.Accepted -> result.receipt
            is DepthEvidenceResult.Refused -> result.receipt
            null -> null
        }
        val accepted = (lookup as? BoundedCanonicalLookupResult.Completed)?.value
            as? DepthEvidenceResult.Accepted ?: run {
            depth.discardPrepared()
            rejected++
            receipt = receipt.copy(status = "depthLookupRefused", rejected = rejected)
            return
        }
        if (accepted.changes.isEmpty()) {
            val remap = requireNotNull(kernel).prepareCanonicalRemap(emptyList())
            if (remap !is FeatureCanonicalRemapPreparation.Prepared) {
                depth.discardPrepared()
                rejected++
                receipt = receipt.copy(status = "depthRemapRefused", rejected = rejected)
                return
            }
            requireNotNull(kernel).applyPreparedCanonicalRemap()
            check(depth.applyPrepared() is DepthEvidenceApplyResult.Applied)
            admittedDepths++
            recordDepthTiming(
                sequence = batchSequenceValue,
                lookupMicros = lookupMicros,
                mutationMicros = 0L,
                publicationAckMicros = 0L,
                endToEndMicros = elapsedMicros(admissionStartedNs, System.nanoTime()),
                lookupAllocatedBytes = lookupAllocatedBytes,
                mutationAllocatedBytes = UNAVAILABLE_ALLOCATION_BYTES,
                publicationAckAllocatedBytes = UNAVAILABLE_ALLOCATION_BYTES,
            )
            receipt = receipt.copy(status = "nonMaterialRetained")
            observation.finishDebugOffer(DepthOfferTimingLedger.NON_MATERIAL)
            return
        }
        val mutationStartedNs = System.nanoTime()
        val mutationStartedAllocatedBytes = allocationNow()
        val preparation = requireNotNull(resources).prepareEvidenceBatch(
            CanonicalEvidenceBatchCommand(
                commandId = "${observation.ownership.bindingGeneration}:${observation.ownership.lifecycleSequence}:depth:${batchSequence}",
                expectedGeometryRevision = accepted.expectedGeometryRevision,
                expectedLineageRevision = accepted.expectedLineageRevision,
                changes = accepted.changes,
            ),
        )
        val prepared = preparation as? CanonicalMutationPreparation.Prepared ?: run {
            requireNotNull(kernel).discardPreparedCanonicalRemap()
            depth.discardPrepared()
            rejected++
            receipt = receipt.copy(status = "depthCanonicalRefused", rejected = rejected)
            return
        }
        val sourceTable = featureSources
        if (sourceTable?.isOverflowed() == true) {
            prepared.mutation.discard()
            depth.discardPrepared()
            rejected++
            receipt = receipt.copy(status = "depthRemapRefused", rejected = rejected)
            return
        }
        val remap = requireNotNull(kernel).prepareCanonicalRemap(
            sourceTable?.remapsFor(prepared.mutation) ?: emptyList(),
        )
        if (remap !is FeatureCanonicalRemapPreparation.Prepared) {
            prepared.mutation.discard()
            depth.discardPrepared()
            rejected++
            receipt = receipt.copy(status = "depthRemapRefused", rejected = rejected)
            return
        }
        val transactionId = nextTransactionId
        val geometryCut = prepared.mutation.toCommittedGeometryCut(observation.ownership, transactionId)
        if (isFenced(observation.ownership)) {
            prepared.mutation.discard()
            requireNotNull(kernel).discardPreparedCanonicalRemap()
            depth.discardPrepared()
            fenced++
            receipt = receipt.copy(status = "publicationDeferred", fenced = fenced)
            return
        }
        val committedResult = commitCanonical(requireNotNull(resources), prepared.mutation)
        val mutationAllocatedBytes = allocationDelta(
            mutationStartedAllocatedBytes,
            allocationNow(),
        ) ?: UNAVAILABLE_ALLOCATION_BYTES
        val mutationMicros = elapsedMicros(mutationStartedNs, System.nanoTime())
        val state = (committedResult as? CanonicalAdjacentCommitResult.Committed)?.state ?: run {
            if (committedResult is CanonicalAdjacentCommitResult.Refused &&
                committedResult.disposition == PreparedMutationDisposition.RETRYABLE
            ) {
                val retained = PendingDepthCommit(
                    offerLedger = observation.debugOfferLedger,
                    offerId = observation.debugOfferId,
                    ownership = observation.ownership,
                    mutation = prepared.mutation,
                    geometryCut = geometryCut,
                    sequence = batchSequenceValue,
                    admissionStartedNs = admissionStartedNs,
                    lookupMicros = lookupMicros,
                    mutationStartedNs = mutationStartedNs,
                    lookupAllocatedBytes = lookupAllocatedBytes,
                    mutationStartedAllocatedBytes = mutationStartedAllocatedBytes,
                )
                val retention = retentionReceipt(retained)
                if (retention == null || retention.totalBytes > retention.budgetBytes) {
                    retained.mutation.discard()
                    requireNotNull(kernel).discardPreparedCanonicalRemap()
                    depth.discardPrepared()
                    rejected++
                    receipt = receipt.copy(status = "depthCommitRetentionRefused", rejected = rejected)
                    return
                }
                observation.debugOfferLedger?.stage(observation.debugOfferId, DepthOfferTimingLedger.COMMIT_PENDING)
                pendingDepthCommit = retained
                rejected++
                receipt = receipt.copy(status = "depthCommitRetryPending", rejected = rejected)
                return
            }
            prepared.mutation.discard()
            requireNotNull(kernel).discardPreparedCanonicalRemap()
            depth.discardPrepared()
            rejected++
            receipt = receipt.copy(status = "depthCommitRefused", rejected = rejected)
            return
        }
        refreshFeatureFingerprintResolver()
        requireNotNull(kernel).applyPreparedCanonicalRemap()
        check(depth.applyPrepared(prepared.mutation) is DepthEvidenceApplyResult.Applied)
        admittedDepths++
        publishV6Current(
            observation.ownership,
            state,
            prebuiltGeometryCut = geometryCut,
            depthTiming = PendingDepthAdmissionTiming(
                offerLedger = observation.debugOfferLedger,
                offerId = observation.debugOfferId,
                sequence = batchSequenceValue,
                admissionStartedNs = admissionStartedNs,
                lookupMicros = lookupMicros,
                mutationMicros = mutationMicros,
                publicationStartedNs = System.nanoTime(),
                lookupAllocatedBytes = lookupAllocatedBytes,
                mutationAllocatedBytes = mutationAllocatedBytes,
                publicationStartedAllocatedBytes = allocationNow(),
            ),
        )
    }

    /** Retries one retained canonical depth capability and always gates the triggering observation. */
    private fun retryPendingDepthCommit(expected: VisibilityObservationOwnership): Boolean {
        val pendingDepth = pendingDepthCommit ?: return false
        if (pendingDepth.ownership != expected || isFenced(pendingDepth.ownership)) {
            discardPendingDepthCommit()
            fenced++
            receipt = receipt.copy(status = "depthCommitFenced", fenced = fenced)
            return true
        }
        val retryMutationStartedAllocatedBytes = allocationNow()
        when (val result = commitCanonical(requireNotNull(resources), pendingDepth.mutation)) {
            is CanonicalAdjacentCommitResult.Committed -> {
                pendingDepthCommit = null
                refreshFeatureFingerprintResolver()
                requireNotNull(kernel).applyPreparedCanonicalRemap()
                check(requireNotNull(depthKernel).applyPrepared(pendingDepth.mutation) is DepthEvidenceApplyResult.Applied)
                admittedDepths++
                publishV6Current(
                    pendingDepth.ownership,
                    result.state,
                    prebuiltGeometryCut = pendingDepth.geometryCut,
                    depthTiming = PendingDepthAdmissionTiming(
                        offerLedger = pendingDepth.offerLedger,
                        offerId = pendingDepth.offerId,
                        sequence = pendingDepth.sequence,
                        admissionStartedNs = pendingDepth.admissionStartedNs,
                        lookupMicros = pendingDepth.lookupMicros,
                        mutationMicros = elapsedMicros(
                            pendingDepth.mutationStartedNs,
                            System.nanoTime(),
                        ),
                        publicationStartedNs = System.nanoTime(),
                        lookupAllocatedBytes = pendingDepth.lookupAllocatedBytes,
                        mutationAllocatedBytes = allocationDelta(
                            retryMutationStartedAllocatedBytes,
                            allocationNow(),
                        ) ?: UNAVAILABLE_ALLOCATION_BYTES,
                        publicationStartedAllocatedBytes = allocationNow(),
                    ),
                )
            }
            is CanonicalAdjacentCommitResult.Refused -> {
                rejected++
                if (result.disposition == PreparedMutationDisposition.RETRYABLE) {
                    receipt = receipt.copy(status = "depthCommitRetryPending", rejected = rejected)
                } else {
                    discardPendingDepthCommit()
                    receipt = receipt.copy(status = "depthCommitTerminalRefused", rejected = rejected)
                }
            }
        }
        return true
    }

    private fun discardPendingDepthCommit() {
        val pendingDepth = pendingDepthCommit ?: return
        pendingDepthCommit = null
        pendingDepth.offerLedger?.finish(pendingDepth.offerId, DepthOfferTimingLedger.REFUSED)
        pendingDepth.mutation.discard()
        kernel?.discardPreparedCanonicalRemap()
        depthKernel?.discardPrepared()
    }

    private fun retentionReceipt(pendingDepth: PendingDepthCommit): PendingDepthRetentionReceipt? = try {
        PendingDepthRetentionReceipt.create(
            mutationPlanBytes = pendingDepth.mutation.work.retainedPlanBytes,
            geometryCutBytes = pendingDepth.geometryCut.modeledRetainedBytes(),
            featureRemapPrimitiveBytes = kernel?.pendingCanonicalRemapPrimitiveBytes() ?: 0L,
            depthPreparedBytes = depthKernel?.resourceReceipt()?.preparedResidentBytes?.toLong() ?: 0L,
        )
    } catch (_: ArithmeticException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun collectDepthFeatureSources(
        featureKernel: FeatureFusionKernel,
        view: BoundedCanonicalSurfaceView,
        changes: List<DepthEvidenceChange>,
    ): DepthFeatureSourceTable {
        val sources = DepthFeatureSourceTable.forChanges(changes)
        changes.forEach { change ->
            for (sourceIndex in 0 until change.sourceCount) {
                val source = change.sourceAt(sourceIndex)
                val row = view.findSurfaceById(source) ?: continue
                val slot = featureKernel.canonicalFeatureSlot(row.voxel, source) ?: continue
                sources.add(source.value, slot, row.voxel)
            }
        }
        return sources
    }

    private fun publishInitialV6Create(
        expected: VisibilityObservationOwnership,
        changes: List<FeatureFusionChange>,
        featureTiming: PendingFeatureAdmissionTiming,
    ) {
        val targets = changes.mapNotNull { (it as? FeatureFusionChange.Upsert)?.candidate }
            .mapNotNull(FeatureFusionCandidate::primaryCanonicalTarget)
        if (targets.isEmpty()) {
            check(requireNotNull(kernel).prepareCanonicalApplication(emptyList()))
            requireNotNull(kernel).applyPrepared()
            recordFeatureTiming(
                timing = featureTiming,
                mutationMicros = 0L,
                serializationMicros = 0L,
                publicationMicros = 0L,
                endToEndMicros = elapsedMicros(featureTiming.admissionStartedNs, System.nanoTime()),
            )
            receipt = receipt.copy(status = "nonMaterialRetained")
            return
        }
        val mutationStartedNs = System.nanoTime()
        val mutationStartedAllocatedBytes = allocationNow()
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
        if (!stageFeatureCanonicalApplication(changes, prepared.mutation)) return
        val state = (commitCanonical(requireNotNull(resources), prepared.mutation)
            as? CanonicalAdjacentCommitResult.Committed)?.state ?: run {
            requireNotNull(kernel).discardPrepared()
            rejected++
            receipt = receipt.copy(status = "canonicalCommitRefused", rejected = rejected)
            return
        }
        val mutationAllocatedBytes = allocationDelta(
            mutationStartedAllocatedBytes,
            allocationNow(),
        ) ?: UNAVAILABLE_ALLOCATION_BYTES
        val mutationMicros = elapsedMicros(mutationStartedNs, System.nanoTime())
        committedFeatures++
        refreshFeatureFingerprintResolver()
        requireNotNull(kernel).applyPrepared()
        publishV6Current(
            expected,
            state,
            prepared.mutation,
            featureTiming = featureTiming.copy(
                mutationMicros = mutationMicros,
                mutationAllocatedBytes = mutationAllocatedBytes,
            ),
        )
    }

    private fun publishMaterialBatch(
        expected: VisibilityObservationOwnership,
        changes: List<FeatureFusionChange>,
        featureTiming: PendingFeatureAdmissionTiming,
    ) {
        // An accepted kernel batch with no material delta needs no canonical
        // authority at all. In particular, do not turn a no-op refinement into
        // an O(history) selector/coordinator recovery scan.
        if (changes.isEmpty()) {
            check(requireNotNull(kernel).prepareCanonicalApplication(emptyList()))
            requireNotNull(kernel).applyPrepared()
            recordFeatureTiming(
                timing = featureTiming,
                mutationMicros = 0L,
                serializationMicros = 0L,
                publicationMicros = 0L,
                endToEndMicros = elapsedMicros(featureTiming.admissionStartedNs, System.nanoTime()),
            )
            receipt = receipt.copy(status = "nonMaterialRetained")
            return
        }
        val mutationStartedNs = System.nanoTime()
        val mutationStartedAllocatedBytes = allocationNow()
        val activeOwner = requireNotNull(owner)
        val preparation = requireNotNull(resources).withFeaturePlanningCurrent(changes.size) {
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
        if (!stageFeatureCanonicalApplication(changes, prepared.mutation)) return
        val committedState = (commitCanonical(requireNotNull(resources), prepared.mutation)
            as? CanonicalAdjacentCommitResult.Committed)?.state ?: run {
            requireNotNull(kernel).discardPrepared()
            rejected++
            receipt = receipt.copy(status = "batchCommitRefused", rejected = rejected)
            return
        }
        val mutationAllocatedBytes = allocationDelta(
            mutationStartedAllocatedBytes,
            allocationNow(),
        ) ?: UNAVAILABLE_ALLOCATION_BYTES
        val mutationMicros = elapsedMicros(mutationStartedNs, System.nanoTime())
        committedFeatures++
        refreshFeatureFingerprintResolver()
        requireNotNull(kernel).applyPrepared()
        if (isFenced(expected)) {
            fenced++
            receipt = receipt.copy(status = "publicationDeferred", fenced = fenced)
            return
        }
        publishV6Current(
            expected,
            committedState,
            prepared.mutation,
            featureTiming = featureTiming.copy(
                mutationMicros = mutationMicros,
                mutationAllocatedBytes = mutationAllocatedBytes,
            ),
        )
    }

    private fun canonicalAssignments(
        changes: List<FeatureFusionChange>,
        mutation: PreparedCanonicalMutation,
        current: CanonicalFeaturePlanningView,
    ): List<CanonicalFeatureAssignment>? {
        val slots = HashMap<Voxel, Int>()
        changes.forEach { change ->
            val upsert = change as? FeatureFusionChange.Upsert ?: return@forEach
            check(upsert.kernelSlot >= 0)
            slots[Voxel(upsert.x, upsert.y, upsert.z)] = upsert.kernelSlot
        }
        val dirty = HashMap<Voxel, PreparedRow>(mutation.dirtyRowCount)
        if (!mutation.visitDirtyRows { row ->
            dirty[row.voxel] = row
            slots.containsKey(row.voxel)
        }) return null
        val assignments = ArrayList<CanonicalFeatureAssignment>(slots.size)
        slots.forEach { (voxel, slot) ->
            val row = dirty[voxel]
            if (row != null) {
                assignments += CanonicalFeatureAssignment(
                    slot, voxel.x, voxel.y, voxel.z, row.id, row.allocationFingerprint,
                    row.packedNormal, row.normalConfidence,
                )
            } else {
                val existing = current.findByVoxel(voxel) ?: return null
                val source = (current.readSourceById(existing.id) as? CanonicalPageRead.Complete)?.value
                    ?: return null
                assignments += CanonicalFeatureAssignment(
                    slot, voxel.x, voxel.y, voxel.z, existing.id, source.allocationFingerprint,
                    existing.packedNormal, existing.normalConfidence,
                )
            }
        }
        return assignments
    }

    /**
     * Resolves every touched feature slot against complete canonical authority and
     * preflights its post-commit kernel writes. Failure owns the complete cleanup
     * boundary: neither the mutation capability nor kernel staging may escape.
     */
    private fun stageFeatureCanonicalApplication(
        changes: List<FeatureFusionChange>,
        mutation: PreparedCanonicalMutation,
    ): Boolean {
        val assignments = requireNotNull(resources).withFeaturePlanningCurrent(changes.size) { current ->
            canonicalAssignments(changes, mutation, current)
        }
        if (assignments == null) {
            discardFeatureCanonicalApplication(mutation, "v6ReadRefused")
            return false
        }
        if (!requireNotNull(kernel).prepareCanonicalApplication(assignments)) {
            discardFeatureCanonicalApplication(mutation, "kernelApplyRefused")
            return false
        }
        return true
    }

    private fun discardFeatureCanonicalApplication(
        mutation: PreparedCanonicalMutation,
        status: String,
    ) {
        mutation.discard()
        requireNotNull(kernel).discardPrepared()
        rejected++
        receipt = receipt.copy(status = status, rejected = rejected)
    }

    private fun publishV6Current(
        expected: VisibilityObservationOwnership,
        state: CanonicalActivationState,
        mutation: PreparedCanonicalMutation? = null,
        rendererAlreadyCurrentRows: Int? = null,
        rendererRebuildRequired: Boolean = false,
        rendererRebuildHydratesKernel: Boolean = false,
        prebuiltGeometryCut: CommittedGeometryCut? = null,
        depthTiming: PendingDepthAdmissionTiming? = null,
        featureTiming: PendingFeatureAdmissionTiming? = null,
    ) {
        val current = state.current as? CanonicalActivationCurrent.Receipt ?: run {
            rejected++
            receipt = receipt.copy(status = "currentMissing", rejected = rejected)
            depthTiming?.offerLedger?.finish(depthTiming.offerId, DepthOfferTimingLedger.REFUSED)
            return
        }
        val selector = CurrentDeltaSelectorV1(
            transactionId = nextTransactionId,
            targetGeometryRevision = state.cut.geometryRevision,
            targetLineageRevision = state.cut.lineageRevision,
        )
        val serializationStartedNs = System.nanoTime()
        val serializationStartedAllocatedBytes = allocationNow()
        val serializedCurrent = CurrentDeltaReceiptV1.serialize(
            selector,
            state.cut.geometryRevision - 1,
            current.identity.canonicalLength,
            current.identity.commandHash.toByteArray(),
            current.source::writeTo,
        )
        val serializationAllocatedBytes = allocationDelta(
            serializationStartedAllocatedBytes,
            allocationNow(),
        ) ?: UNAVAILABLE_ALLOCATION_BYTES
        val serializationMicros = elapsedMicros(serializationStartedNs, System.nanoTime())
        if (serializedCurrent == null) {
            rejected++
            receipt = receipt.copy(status = "currentCorrupt", rejected = rejected)
            depthTiming?.offerLedger?.finish(depthTiming.offerId, DepthOfferTimingLedger.REFUSED)
            return
        }
        nextTransactionId++
        if (isFenced(expected)) {
            fenced++
            receipt = receipt.copy(status = "publicationDeferred", fenced = fenced)
            depthTiming?.offerLedger?.finish(depthTiming.offerId, DepthOfferTimingLedger.REFUSED)
            return
        }
        val geometryCut = prebuiltGeometryCut ?: mutation?.toCommittedGeometryCut(expected, selector.transactionId)
        retainedDelta.retain(serializedCurrent)
        pendingCanonicalAcknowledgement = CanonicalAcknowledgement(
            current.identity.commandHash,
            state.cut.geometryRevision,
            state.cut.lineageRevision,
        )
        pending = selector
        pendingPublicationForIngress = true
        pendingQueued = false
        pendingGeometryCut = geometryCut
        pendingRendererApplied = rendererAlreadyCurrentRows != null
        pendingRendererRebuild = if (rendererRebuildRequired) PendingRendererRebuild(
            expected, selector.transactionId, state.cut.geometryRevision,
            state.cut.lineageRevision, rendererRebuildHydratesKernel,
        ) else null
        pendingBindingAcknowledged = false
        pendingEarlyAcknowledged = false
        pendingRendererRows = rendererAlreadyCurrentRows ?: 0
        depthTiming?.offerLedger?.published(depthTiming.offerId, selector)
        pendingDepthTiming = depthTiming
        pendingFeatureTiming = featureTiming?.copy(
            serializationMicros = serializationMicros,
            publicationStartedNs = System.nanoTime(),
            serializationAllocatedBytes = serializationAllocatedBytes,
            publicationStartedAllocatedBytes = allocationNow(),
        )
        committed++
        receipt = VisibilityGridIntegrationReceipt(
            "pendingAck", expected.bindingGeneration, expected.sessionGeneration,
            expected.groupGeneration, selector.transactionId, state.cut.geometryRevision,
            state.cut.lineageRevision, serializedCurrent.byteCount, pendingRendererRows,
            committed, rejected, fenced, canonicalOperation(serializedCurrent.openStream()),
        )
        retryPendingQueue()
        if (pending == selector) {
            val pendingTiming = pendingFeatureTiming
            if (pendingTiming != null) {
                pendingFeatureTiming = pendingTiming.copy(
                    publicationMicros = elapsedMicros(
                        pendingTiming.publicationStartedNs,
                        System.nanoTime(),
                    ),
                    publicationAllocatedBytes = allocationDelta(
                        pendingTiming.publicationStartedAllocatedBytes,
                        allocationNow(),
                    ) ?: UNAVAILABLE_ALLOCATION_BYTES,
                )
            }
        }
    }

    /** Idempotently correlates the already-retained durable current to V2. */
    private fun retryPendingQueue(): Boolean {
        val selector = pending ?: return true
        if (pendingEarlyAcknowledged) pendingQueued = true
        if (!pendingQueued) {
            try {
                when (queueCurrent(binding, retainedDelta, selector)) {
                    CurrentDeltaQueueResult.QUEUED,
                    CurrentDeltaQueueResult.ALREADY_QUEUED,
                    CurrentDeltaQueueResult.RECOVERED_EXACT_QUEUE -> pendingQueued = true
                }
            } catch (_: IllegalStateException) {
                receipt = receipt.copy(status = "publicationRetryPending")
                return false
            } catch (_: IllegalArgumentException) {
                receipt = receipt.copy(status = "publicationRetryPending")
                return false
            }
        }
        if (!pendingRendererApplied) {
            if (!pendingBindingAcknowledged &&
                binding.rendererPublicationAwaitingStructuralAcknowledgement()
            ) {
                return false
            }
            val rebuild = pendingRendererRebuild
            if (rebuild != null) {
                val success = retryRendererRebuild(rebuild)
                if (success == null) {
                    receipt = receipt.copy(status = "rendererRebuildPending")
                    return false
                }
                pendingRendererRebuild = null
                pendingRendererApplied = true
                pendingRendererRows = success.rowCount
            } else {
                val cut = pendingGeometryCut ?: return false
                if (isFenced(cut.ownership)) {
                    receipt = receipt.copy(status = "publicationDeferred")
                    return false
                }
                val result = try {
                    renderer.applyGeometry(cut)
                } catch (_: RuntimeException) {
                    pendingRendererRebuild = cut.toPendingRendererRebuild(hydrateKernel = false)
                    receipt = receipt.copy(status = "rendererRebuildPending")
                    return false
                }
                when (result) {
                    is RendererProjectionResult.Applied -> {
                        pendingRendererApplied = true
                        pendingRendererRows = result.rowCount
                    }
                    is RendererProjectionResult.Refused -> {
                        pendingRendererRebuild = cut.toPendingRendererRebuild(hydrateKernel = false)
                        rejected++
                        receipt = receipt.copy(status = "rendererRebuildPending", rejected = rejected)
                        return false
                    }
                }
            }
        }
        if (pendingBindingAcknowledged) finishPendingAcknowledgement(selector)
        if (pending != null) receipt = receipt.copy(status = "pendingAck", rendererRows = pendingRendererRows)
        return true
    }

    private fun rebuildCanonicalRenderer(
        expected: VisibilityObservationOwnership,
        state: CanonicalActivationState,
        transactionId: Long = 0,
        hydrateKernel: Boolean = true,
    ): RendererRebuildSuccess? {
        val rebuildCut = CommittedGeometryCut(
            ownership = expected,
            transactionId = transactionId,
            baseGeometryRevision = 0,
            geometryRevision = state.cut.geometryRevision,
            lineageRevision = state.cut.lineageRevision,
            reset = true,
            upserts = emptyList(),
            removedSurfaceIds = LongArray(0),
        )
        var finished = false
        try {
            renderer.beginRebuild(rebuildCut)
            val maximumRows = renderer.maximumRows
            val rebuilt = requireNotNull(resources).rebuildAndHydrate(
                requireNotNull(kernel), maximumRows, hydrateKernel,
            ) { page ->
                if (isFenced(expected)) throw RendererRebuildFenced()
                renderer.appendRebuildPage(rebuildCut.withUpserts(page.rows))
            } ?: run {
                rejected++
                receipt = receipt.copy(status = "rebuildRefused", rejected = rejected)
                return null
            }
            if (rebuilt != state.cut || isFenced(expected)) return null
            renderer.finishRebuild(rebuildCut)
            val rowCount = renderer.currentRowCount()
            finished = true
            return RendererRebuildSuccess(rowCount)
        } catch (_: RendererRebuildFenced) {
            return null
        } catch (_: RuntimeException) {
            return null
        } finally {
            if (!finished) runCatching { renderer.abortRebuild() }
        }
    }

    private fun retryRendererRebuild(rebuild: PendingRendererRebuild): RendererRebuildSuccess? {
        if (isFenced(rebuild.ownership)) return null
        val state = requireNotNull(owner).activationState() ?: return null
        if (state.cut.geometryRevision != rebuild.geometryRevision ||
            state.cut.lineageRevision != rebuild.lineageRevision
        ) return null
        if (rebuild.hydrateKernel) kernel = createBoundFeatureKernel(requireNotNull(resources))
        return rebuildCanonicalRenderer(
            rebuild.ownership, state, rebuild.transactionId, rebuild.hydrateKernel,
        )
    }

    /** Creates a kernel only after its exact canonical fingerprint source is bound. */
    private fun createBoundFeatureKernel(runtimeResources: CanonicalRuntimeResources): FeatureFusionKernel {
        val created = featureKernelFactory()
        check(runtimeResources.bindFeatureFingerprintResolver(created)) {
            "canonical fingerprint authority unavailable while creating feature kernel"
        }
        return created
    }

    /** Refreshes the kernel qualifier after a canonical successor is installed. */
    private fun refreshFeatureFingerprintResolver() {
        val activeResources = resources ?: return
        val activeKernel = kernel ?: return
        check(activeResources.bindFeatureFingerprintResolver(activeKernel)) {
            "canonical fingerprint authority unavailable after canonical commit"
        }
    }

    private fun CommittedGeometryCut.toPendingRendererRebuild(hydrateKernel: Boolean) = PendingRendererRebuild(
        ownership, transactionId, geometryRevision, lineageRevision, hydrateKernel,
    )

    private fun acknowledge(selector: CurrentDeltaSelectorV1) {
        if (closed) return
        runCatching {
            executor.submit {
                synchronized(lock) {
                    if (pending != selector || closed) return@synchronized
                    pendingBindingAcknowledged = true
                    if (!pendingQueued) {
                        pendingEarlyAcknowledged = true
                        return@synchronized
                    }
                    if (!pendingRendererApplied) {
                        if (!retryPendingQueue()) {
                            receipt = receipt.copy(
                                status = if (pendingRendererRebuild != null) "rendererRebuildPending" else "rendererRetryPending",
                            )
                            return@synchronized
                        }
                    }
                    finishPendingAcknowledgement(selector)
                }
            }
        }
    }

    /** Retains one newest depth view while an exact structural publication owns the lane. */
    private fun deferDepthUntilPublicationAcknowledgement(observation: VisibilityDepthObservation): Boolean {
        synchronized(lock) {
            if (closed || paused || ownership() != observation.ownership || isFenced(observation.ownership)) {
                // No slot was acquired for this observation, so there is no
                // retained payload to release or count as fenced. The mapper's
                // ordinary fenced counter records this admission refusal.
                return false
            }
            observation.debugOfferLedger?.stage(observation.debugOfferId, DepthOfferTimingLedger.DEFERRED)
            val retained = DeferredDepthObservation(observation, System.nanoTime())
            val replaced = deferredDepthObservation.getAndSet(retained)
            if (replaced != null) {
                deferredDepthReplacedCount++
                deferredDepthReleasedCount++
                replaced.observation.finishDebugOffer(DepthOfferTimingLedger.REPLACED)
                replaced.observation.close()
            }
            deferredDepthCount++
            lastDepthAdmissionStatus = "depthPublicationDeferred"
            return true
        }
    }

    /** Drops the retained slot exactly once, accounting for its ownership release. */
    private fun releaseDeferredDepth(fenced: Boolean) {
        val deferred = deferredDepthObservation.getAndSet(null) ?: return
        deferredDepthReleasedCount++
        if (fenced) deferredDepthFencedCount++
        deferred.observation.finishDebugOffer(DepthOfferTimingLedger.LIFECYCLE)
        deferred.observation.close()
    }

    /** Gives one retained depth turn to the canonical lane after its exact ACK. */
    private fun retryDeferredDepth(expected: VisibilityObservationOwnership) {
        val deferred = deferredDepthObservation.get() ?: return
        if (deferred.observation.ownership != expected || isFenced(expected)) {
            releaseDeferredDepth(fenced = true)
            lastDepthAdmissionStatus = "depthPublicationFenced"
            return
        }
        // A prior depth durability retry may still own the prepared mutation.
        // Let it make one progress attempt first; keep this latest observation
        // in the single slot if that attempt publishes another current or
        // remains retryable.
        if (pendingDepthCommit != null) {
            retryPendingDepthCommit(expected)
            if (pendingDepthCommit != null || pending != null) return
        }
        if (!deferredDepthObservation.compareAndSet(deferred, null)) return
        deferredDepthRetriedCount++
        try {
            deferred.observation.debugOfferLedger?.stage(deferred.observation.debugOfferId, DepthOfferTimingLedger.ADMITTING)
            admitDepthLocked(deferred.observation, deferred.admissionStartedNs)
        } finally {
            deferredDepthReleasedCount++
            deferred.observation.close()
        }
        lastDepthAdmissionStatus = receipt.status
    }

    private fun finishPendingAcknowledgement(
        selector: CurrentDeltaSelectorV1,
        drainDeferredDepth: Boolean = true,
    ) {
        if (pending != selector || !pendingBindingAcknowledged || !pendingRendererApplied) return
        val acknowledgement = requireNotNull(pendingCanonicalAcknowledgement)
        when (val result = requireNotNull(owner).acknowledgeCanonicalCurrent(acknowledgement)) {
            is CanonicalAcknowledgementResult.Acknowledged,
            is CanonicalAcknowledgementResult.Idempotent -> Unit
            is CanonicalAcknowledgementResult.NoOp -> {
                receipt = receipt.copy(status = "ack${result.reason.name}")
                return
            }
        }
        // The stream clears its structural ACK marker before invoking this
        // serialized listener. Keep successor style pages fenced until the
        // renderer cut has actually been installed on this same lane.
        binding.markRendererPublicationApplied(selector)
        pendingDepthTiming?.let { timing ->
            timing.offerLedger?.complete(timing.offerId, selector)
            recordDepthTiming(
                sequence = timing.sequence,
                lookupMicros = timing.lookupMicros,
                mutationMicros = timing.mutationMicros,
                publicationAckMicros = elapsedMicros(timing.publicationStartedNs, System.nanoTime()),
                endToEndMicros = elapsedMicros(timing.admissionStartedNs, System.nanoTime()),
                lookupAllocatedBytes = timing.lookupAllocatedBytes,
                mutationAllocatedBytes = timing.mutationAllocatedBytes,
                publicationAckAllocatedBytes = allocationDelta(
                    timing.publicationStartedAllocatedBytes,
                    allocationNow(),
                ) ?: UNAVAILABLE_ALLOCATION_BYTES,
            )
        }
        pendingFeatureTiming?.let { timing ->
            recordFeatureTiming(
                timing = timing,
                mutationMicros = timing.mutationMicros,
                serializationMicros = timing.serializationMicros,
                publicationMicros = timing.publicationMicros,
                endToEndMicros = elapsedMicros(timing.admissionStartedNs, System.nanoTime()),
            )
        }
        retainedDelta.acknowledge(selector)
        // Keep the rows installed for this acknowledged cut in the public
        // receipt. The pending scalar is cleared below for the next cut, so
        // copying only the status here would make a populated projection
        // report rendererRows=0 after every exact ACK.
        val acknowledgedRendererRows = pendingRendererRows
        pending = null
        pendingPublicationForIngress = false
        pendingQueued = false
        pendingGeometryCut = null
        pendingRendererApplied = false
        pendingRendererRebuild = null
        pendingBindingAcknowledged = false
        pendingEarlyAcknowledged = false
        pendingRendererRows = 0
        pendingCanonicalAcknowledgement = null
        pendingDepthTiming = null
        pendingFeatureTiming = null
        receipt = receipt.copy(
            status = "acknowledged",
            rendererRows = acknowledgedRendererRows,
        )
        if (drainDeferredDepth) {
            val acknowledgedOwnership = cut
            if (acknowledgedOwnership != null) retryDeferredDepth(acknowledgedOwnership)
        }
    }

    private fun closeOwner() {
        pendingDepthTiming?.let { it.offerLedger?.finish(it.offerId, DepthOfferTimingLedger.RESET) }
        recordCanonicalPressureHighWater()
        discardPendingDepthCommit()
        releaseDeferredDepth(fenced = true)
        retainedDelta.clear()
        pending = null
        pendingPublicationForIngress = false
        pendingQueued = false
        pendingGeometryCut = null
        pendingRendererApplied = false
        pendingRendererRebuild = null
        pendingBindingAcknowledged = false
        pendingEarlyAcknowledged = false
        pendingRendererRows = 0
        resources?.close() ?: owner?.close()
        resources = null
        owner = null
        kernel = null
        depthKernel?.close()
        depthKernel = null
        lastDepthAdmissionStatus = null
        clearDepthAttemptDiagnostics()
        pendingCanonicalAcknowledgement = null
        pendingDepthTiming = null
        pendingFeatureTiming = null
    }

    private fun clearDepthAttemptDiagnostics() {
        lastDepthLookupReceipt = null
        lastDepthLookupReason = null
        lastDepthEvidenceRefusal = null
        lastDepthEvidenceReceipt = null
        lastDepthSelectedSamples = 0
        lastDepthSourceRejectedSamples = 0
        lastDepthLeaseRejectedSamples = -1
    }

    private fun recordDepthTiming(
        sequence: Long,
        lookupMicros: Long,
        mutationMicros: Long,
        publicationAckMicros: Long,
        endToEndMicros: Long,
        lookupAllocatedBytes: Long = UNAVAILABLE_ALLOCATION_BYTES,
        mutationAllocatedBytes: Long = UNAVAILABLE_ALLOCATION_BYTES,
        publicationAckAllocatedBytes: Long = UNAVAILABLE_ALLOCATION_BYTES,
    ) = synchronized(lock) {
        depthTimingCompletedCount = minOf(
            VisibilityDepthAdmissionTiming.MAX_COMPLETED_COUNT,
            depthTimingCompletedCount + 1L,
        )
        depthTimingSequence = sequence.coerceAtLeast(0L)
        depthTimingLookupMicros = lookupMicros.coerceIn(0L, VisibilityDepthAdmissionTiming.MAX_ELAPSED_MICROS)
        depthTimingMutationMicros = mutationMicros.coerceIn(0L, VisibilityDepthAdmissionTiming.MAX_ELAPSED_MICROS)
        depthTimingPublicationAckMicros = publicationAckMicros.coerceIn(0L, VisibilityDepthAdmissionTiming.MAX_ELAPSED_MICROS)
        depthTimingEndToEndMicros = endToEndMicros.coerceIn(0L, VisibilityDepthAdmissionTiming.MAX_ELAPSED_MICROS)
        depthTimingLookupAllocatedBytes = lookupAllocatedBytes
        depthTimingMutationAllocatedBytes = mutationAllocatedBytes
        depthTimingPublicationAckAllocatedBytes = publicationAckAllocatedBytes
        if (lookupAllocatedBytes >= 0L) {
            depthTimingLookupAllocatedBytesTotal = addAllocationTotal(
                depthTimingLookupAllocatedBytesTotal,
                lookupAllocatedBytes,
            )
            depthTimingLookupAllocationSamples++
        }
        if (mutationAllocatedBytes >= 0L) {
            depthTimingMutationAllocatedBytesTotal = addAllocationTotal(
                depthTimingMutationAllocatedBytesTotal,
                mutationAllocatedBytes,
            )
            depthTimingMutationAllocationSamples++
        }
        if (publicationAckAllocatedBytes >= 0L) {
            depthTimingPublicationAckAllocatedBytesTotal = addAllocationTotal(
                depthTimingPublicationAckAllocatedBytesTotal,
                publicationAckAllocatedBytes,
            )
            depthTimingPublicationAckAllocationSamples++
        }
    }

    private fun recordFeatureTiming(
        timing: PendingFeatureAdmissionTiming,
        mutationMicros: Long,
        serializationMicros: Long,
        publicationMicros: Long,
        endToEndMicros: Long,
    ) = synchronized(lock) {
        featureTimingCompletedCount = minOf(
            VisibilityFeatureAdmissionTiming.MAX_COMPLETED_COUNT,
            featureTimingCompletedCount + 1L,
        )
        featureTimingSequence = timing.sequence.coerceAtLeast(0L)
        featureTimingPlanningMicros = timing.planningMicros.coerceIn(
            0L,
            VisibilityFeatureAdmissionTiming.MAX_ELAPSED_MICROS,
        )
        featureTimingMutationMicros = mutationMicros.coerceIn(
            0L,
            VisibilityFeatureAdmissionTiming.MAX_ELAPSED_MICROS,
        )
        featureTimingSerializationMicros = serializationMicros.coerceIn(
            0L,
            VisibilityFeatureAdmissionTiming.MAX_ELAPSED_MICROS,
        )
        featureTimingPublicationMicros = publicationMicros.coerceIn(
            0L,
            VisibilityFeatureAdmissionTiming.MAX_ELAPSED_MICROS,
        )
        featureTimingEndToEndMicros = endToEndMicros.coerceIn(
            0L,
            VisibilityFeatureAdmissionTiming.MAX_ELAPSED_MICROS,
        )
        featureTimingPlanningAllocatedBytes = timing.planningAllocatedBytes
        featureTimingMutationAllocatedBytes = timing.mutationAllocatedBytes
        featureTimingSerializationAllocatedBytes = timing.serializationAllocatedBytes
        featureTimingPublicationAllocatedBytes = timing.publicationAllocatedBytes
        if (timing.planningAllocatedBytes >= 0L) {
            featureTimingPlanningAllocatedBytesTotal = addAllocationTotal(
                featureTimingPlanningAllocatedBytesTotal,
                timing.planningAllocatedBytes,
            )
            featureTimingPlanningAllocationSamples++
        }
        if (timing.mutationAllocatedBytes >= 0L) {
            featureTimingMutationAllocatedBytesTotal = addAllocationTotal(
                featureTimingMutationAllocatedBytesTotal,
                timing.mutationAllocatedBytes,
            )
            featureTimingMutationAllocationSamples++
        }
        if (timing.serializationAllocatedBytes >= 0L) {
            featureTimingSerializationAllocatedBytesTotal = addAllocationTotal(
                featureTimingSerializationAllocatedBytesTotal,
                timing.serializationAllocatedBytes,
            )
            featureTimingSerializationAllocationSamples++
        }
        if (timing.publicationAllocatedBytes >= 0L) {
            featureTimingPublicationAllocatedBytesTotal = addAllocationTotal(
                featureTimingPublicationAllocatedBytesTotal,
                timing.publicationAllocatedBytes,
            )
            featureTimingPublicationAllocationSamples++
        }
    }

    private fun allocationNow(): Long = allocationCounter?.invoke() ?: UNAVAILABLE_ALLOCATION_BYTES

    private fun allocationDelta(before: Long, after: Long): Long =
        if (before < 0L || after < before) {
            UNAVAILABLE_ALLOCATION_BYTES
        } else {
            after - before
        }

    private fun reportedAllocationTotal(total: Long, samples: Long): Long =
        if (samples == 0L) UNAVAILABLE_ALLOCATION_BYTES else total

    private fun addAllocationTotal(total: Long, delta: Long): Long =
        if (delta < 0L || Long.MAX_VALUE - total < delta) Long.MAX_VALUE else total + delta

    private fun elapsedMicros(startNs: Long, endNs: Long): Long =
        ((endNs - startNs).coerceAtLeast(0L) / 1_000L)
            .coerceAtMost(VisibilityDepthAdmissionTiming.MAX_ELAPSED_MICROS)

    private fun drain(timeoutMilliseconds: Long = 2_000L) {
        executor.submit {}.get(timeoutMilliseconds, TimeUnit.MILLISECONDS)
    }
}

internal data class RuntimeOwnerMemoryReceipt(
    val integrationObjectBytes: Long,
    val integrationReceiptBytes: Long,
    val retainedDeltaOwnerBytes: Long,
    val pendingRendererRebuildBytes: Long,
    val retainedDepthLookupReceiptBytes: Long,
    val retainedDepthRefusalReceiptBytes: Long,
    val deferredDepthObservationBytes: Long,
    val runtimeOwnerBytes: Long,
    val bindingOwnerBytes: Long,
    val coordinatorOwnerBytes: Long,
    val rendererOwnerBytes: Long,
) {
    val portableBytes: Long get() = listOf(
        integrationObjectBytes,
        integrationReceiptBytes,
        retainedDeltaOwnerBytes,
        pendingRendererRebuildBytes,
        retainedDepthLookupReceiptBytes,
        retainedDepthRefusalReceiptBytes,
        deferredDepthObservationBytes,
        runtimeOwnerBytes,
        bindingOwnerBytes,
        coordinatorOwnerBytes,
        rendererOwnerBytes,
    ).fold(0L, Math::addExact)
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
    fun applyStyleCut(cut: QualifiedRendererStyleCut): RendererStyleCutResult =
        RendererStyleCutResult.Rejected(RendererStyleCutRejection.CLOSED)
    fun canRebindRetainedCanonicalCut(cut: RetainedRendererBindingCut): Boolean = false
    fun rebindRetainedCanonicalCut(cut: RetainedRendererBindingCut): Boolean = false
    fun rebindRetainedEmptyCanonicalCut(
        nextOwnership: VisibilityObservationOwnership,
        authoritativeBaseline: CommittedBaselineV1,
    ): Boolean = false
    fun currentRowCount(): Int = 0
    /** Borrow canonical rows synchronously; no source snapshot is retained. */
    fun withCommittedRows(
        expected: CoverageRowsQualifier,
        block: (CoverageCommittedRows) -> Unit,
    ): Boolean = false
    fun portableOwnerBytes(): Long = 0
    fun pressureRevisions(): RendererProjectionPressureSnapshot =
        RendererProjectionPressureSnapshot()
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

internal data class RetainedRendererBindingCut(
    val previousOwnership: VisibilityObservationOwnership,
    val nextOwnership: VisibilityObservationOwnership,
    val authoritativeBaseline: CommittedBaselineV1,
    val previousBindingTransactionId: Long = authoritativeBaseline.transactionId,
)

private val NO_PRESENTATION_PUBLISHER:
    (BoundedCoveragePresentation?, PointCloudNativeConfig?) -> Unit = { _, _ -> }

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

/** Identifies the work performed by the publisher callback being timed. */
internal enum class RendererPublicationTimingScope(val wireName: String) {
    SYNCHRONOUS_CALLBACK("synchronous-publisher-callback"),
    MAILBOX_ENQUEUE("mailbox-enqueue"),
}

/**
 * One coarse renderer callback timing receipt. Production projections leave
 * the sink null and do not sample clocks or allocate timing receipts. Enqueue
 * timing excludes later main application, page uploads and GPU execution.
 */
internal data class RendererProjectionTiming(
    val styleRevision: Long,
    val requestedRows: Int,
    val sourceRows: Int,
    val selectedRows: Int,
    val stateApplyMicros: Long,
    val stateApplyAllocatedBytes: Long?,
    val selectionMicros: Long,
    val selectionAllocatedBytes: Long?,
    val descriptorMicros: Long,
    val descriptorAllocatedBytes: Long?,
    val publishMicros: Long,
    val publishAllocatedBytes: Long?,
    val callbackMicros: Long,
    val callbackAllocatedBytes: Long?,
    val publishTimingScope: RendererPublicationTimingScope,
) {
    val allocationScope: String get() = "inclusive-process-counter-window-may-overlap-main"
}

private class RendererProjectionTimingCapture(
    val startedNs: Long,
    val styleRevision: Long,
    val requestedRows: Int,
    val startedAllocatedBytes: Long?,
    val publishTimingScope: RendererPublicationTimingScope,
    var sourceRows: Int = 0,
    var selectedRows: Int = 0,
    var stateApplyMicros: Long = 0L,
    var stateApplyAllocatedBytes: Long? = null,
    var selectionMicros: Long = 0L,
    var selectionAllocatedBytes: Long? = null,
    var descriptorMicros: Long = 0L,
    var descriptorAllocatedBytes: Long? = null,
    var publishMicros: Long = 0L,
    var publishAllocatedBytes: Long? = null,
) {
    fun receipt(): RendererProjectionTiming = RendererProjectionTiming(
        styleRevision = styleRevision,
        requestedRows = requestedRows,
        sourceRows = sourceRows,
        selectedRows = selectedRows,
        stateApplyMicros = stateApplyMicros,
        stateApplyAllocatedBytes = stateApplyAllocatedBytes,
        selectionMicros = selectionMicros,
        selectionAllocatedBytes = selectionAllocatedBytes,
        descriptorMicros = descriptorMicros,
        descriptorAllocatedBytes = descriptorAllocatedBytes,
        publishMicros = publishMicros,
        publishAllocatedBytes = publishAllocatedBytes,
        publishTimingScope = publishTimingScope,
        callbackMicros = elapsedMicros(startedNs),
        callbackAllocatedBytes = allocatedDelta(
            startedAllocatedBytes,
            artAllocatedBytes(),
        ),
    )

    private fun elapsedMicros(startNs: Long): Long =
        ((System.nanoTime() - startNs).coerceAtLeast(0L) / 1_000L)
}

private fun artAllocatedBytes(): Long? =
    Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()

private fun allocatedDelta(before: Long?, after: Long?): Long? =
    if (before == null || after == null) null else (after - before).coerceAtLeast(0L)

/** Dedicated V2 adapter over the existing bounded native renderer state. */
internal class NativeRendererProjection(
    private val render: (CoveragePointRenderSnapshot?, PointCloudNativeConfig?) -> Unit,
    capacity: Int = QUALIFIED_RENDERER_STYLE_CUT_MAX_ROWS,
    private val publishPresentation: (BoundedCoveragePresentation?, PointCloudNativeConfig?) -> Unit =
        NO_PRESENTATION_PUBLISHER,
    private val timingSink: ((RendererProjectionTiming) -> Unit)? = null,
    private val publishTimingScope: RendererPublicationTimingScope =
        RendererPublicationTimingScope.SYNCHRONOUS_CALLBACK,
) : CommittedRendererProjection {
    // Canonical projection retains IDs/keys/normals/style only. World
    // positions and packed colors are borrowed into bounded page staging.
    private val state = VisibilityGridRendererState(
        capacity = capacity,
        retainWorldPositions = publishPresentation === NO_PRESENTATION_PUBLISHER,
        retainCanonicalNormalMetadata = publishPresentation === NO_PRESENTATION_PUBLISHER,
    )
    private val presentationSelector = CoveragePresentationSelector(
        CoveragePresentationMode.SEMANTIC_CENTROIDS.presentationCapacity,
    )
    private var closed = false
    private var rebuildCut: CommittedGeometryCut? = null
    private var activeOwnership: VisibilityObservationOwnership? = null
    private var activeLineageRevision = 0L
    private var activeTransactionId = 0L
    private var activeRenderConfig: PointCloudNativeConfig? = null
    private var activePresentation: PresentationDescriptor? = null
    override val maximumRows: Int get() = state.capacity
    override fun portableOwnerBytes(): Long =
        56L + 96L + 64L + state.retainedGroupGeometryBytes // projection, state, native config, group geometry

    @Synchronized
    override fun pressureRevisions(): RendererProjectionPressureSnapshot =
        RendererProjectionPressureSnapshot(
            coverageRevision = state.currentCoverageRevision,
            styleRevision = state.currentStyleRevision,
        )

    @Synchronized
    override fun withCommittedRows(
        expected: CoverageRowsQualifier,
        block: (CoverageCommittedRows) -> Unit,
    ): Boolean {
        if (closed || activeRenderConfig?.rendererGeneration != expected.rendererGeneration) {
            return false
        }
        return state.withCommittedRows(expected, block)
    }

    @Synchronized
    override fun applyGeometry(cut: CommittedGeometryCut): RendererProjectionResult {
        if (closed) return RendererProjectionResult.Refused(RendererProjectionRefusal.CLOSED)
        if (activeOwnership != cut.ownership) {
            return RendererProjectionResult.Refused(RendererProjectionRefusal.STALE_OWNERSHIP)
        }
        if (cut.lineageRevision < activeLineageRevision) {
            return RendererProjectionResult.Refused(RendererProjectionRefusal.MIXED_CUT)
        }
        val removedIds = cut.removedSurfaceIds
        val removedKnownCount = removedIds.count(state::containsSurfaceId)
        val insertedCount = cut.upserts.count { !state.containsSurfaceId(it.surfaceId) }
        val targetRowCount = currentRowCount() - removedKnownCount + insertedCount
        if (targetRowCount > cut.ownership.groupFrame.modelCapacity) {
            return RendererProjectionResult.Refused(RendererProjectionRefusal.CAPACITY)
        }
        if (!state.applyGeometry(
                revision = cut.geometryRevision,
                reset = cut.reset,
                upsertRows = cut.upserts.map { it.toCanonicalRenderRow() },
                removalSurfaceIds = removedIds,
                ownership = cut.ownership,
                transactionId = cut.transactionId,
                lineageRevision = cut.lineageRevision,
            )
        ) {
            return RendererProjectionResult.Refused(RendererProjectionRefusal.NON_ADJACENT_GEOMETRY)
        }
        activeLineageRevision = cut.lineageRevision
        activeTransactionId = cut.transactionId
        val config = renderConfig(cut)
        activeRenderConfig = config
        emitPresentation(config, forceReset = cut.reset)
        return RendererProjectionResult.Applied(currentRowCount())
    }

    @Synchronized
    override fun applyStyleCut(cut: QualifiedRendererStyleCut): RendererStyleCutResult {
        if (closed) return RendererStyleCutResult.Rejected(RendererStyleCutRejection.CLOSED)
        val timingStartedNs = timingSink?.let { System.nanoTime() } ?: 0L
        val timingStartedAllocatedBytes = if (timingStartedNs == 0L) null else artAllocatedBytes()
        seedEmptyBootstrap(cut)
        val stateApplyStartedNs = if (timingStartedNs == 0L) 0L else System.nanoTime()
        val stateApplyStartedAllocatedBytes =
            if (timingStartedNs == 0L) null else artAllocatedBytes()
        val result = state.applyStyleCut(cut)
        val stateApplyMicros = if (stateApplyStartedNs == 0L) {
            0L
        } else {
            ((System.nanoTime() - stateApplyStartedNs).coerceAtLeast(0L) / 1_000L)
        }
        val stateApplyAllocatedBytes = allocatedDelta(
            stateApplyStartedAllocatedBytes,
            if (timingStartedNs == 0L) null else artAllocatedBytes(),
        )
        if (result is RendererStyleCutResult.Applied) {
            emitPresentation(
                checkNotNull(activeRenderConfig),
                forceReset = cut.reset,
                timing = if (timingStartedNs == 0L) {
                    null
                } else {
                    RendererProjectionTimingCapture(
                        startedNs = timingStartedNs,
                        styleRevision = cut.styleRevision,
                        requestedRows = cut.surfaceIds.size,
                        startedAllocatedBytes = timingStartedAllocatedBytes,
                        publishTimingScope = publishTimingScope,
                        stateApplyMicros = stateApplyMicros,
                        stateApplyAllocatedBytes = stateApplyAllocatedBytes,
                    )
                },
            )
        }
        return result
    }

    @Synchronized
    override fun canRebindRetainedCanonicalCut(cut: RetainedRendererBindingCut): Boolean {
        if (closed || rebuildCut != null || activeOwnership != cut.previousOwnership ||
            activeRenderConfig == null || activeLineageRevision != cut.authoritativeBaseline.lineageRevision ||
            activeTransactionId != cut.previousBindingTransactionId
        ) return false
        return state.canRebindRetainedCanonicalCut(
            previousOwnership = cut.previousOwnership,
            nextOwnership = cut.nextOwnership,
            previousTransactionId = cut.previousBindingTransactionId,
            geometryRevision = cut.authoritativeBaseline.geometryRevision,
            lineageRevision = cut.authoritativeBaseline.lineageRevision,
            styleRevision = cut.authoritativeBaseline.styleRevision,
        )
    }

    @Synchronized
    override fun rebindRetainedCanonicalCut(cut: RetainedRendererBindingCut): Boolean {
        if (!canRebindRetainedCanonicalCut(cut)) return false
        check(state.rebindRetainedCanonicalCut(
            previousOwnership = cut.previousOwnership,
            nextOwnership = cut.nextOwnership,
            previousTransactionId = cut.previousBindingTransactionId,
            geometryRevision = cut.authoritativeBaseline.geometryRevision,
            lineageRevision = cut.authoritativeBaseline.lineageRevision,
            styleRevision = cut.authoritativeBaseline.styleRevision,
        ))
        activeOwnership = cut.nextOwnership
        activeTransactionId = 0L
        return true
    }

    /**
     * Rebinds the bootstrap-empty canonical cut before integration ingress has
     * opened its lazy durable owner.  Style zero has no renderer owner yet;
     * a nonzero style must already be the exact empty cut owned by the old
     * binding and is rebound without rendering or mutating row/style bytes.
     */
    @Synchronized
    override fun rebindRetainedEmptyCanonicalCut(
        nextOwnership: VisibilityObservationOwnership,
        authoritativeBaseline: CommittedBaselineV1,
    ): Boolean {
        if (closed || rebuildCut != null || currentRowCount() != 0 ||
            authoritativeBaseline.transactionId != 1L ||
            authoritativeBaseline.geometryRevision != 1L ||
            authoritativeBaseline.lineageRevision != 1L
        ) return false

        val previousOwnership = activeOwnership
        if (previousOwnership == null) {
            return activeRenderConfig == null && authoritativeBaseline.styleRevision == 0L &&
                state.currentStyleRevision == 0L
        }
        if (activeRenderConfig == null || activeTransactionId != 1L ||
            activeLineageRevision != 1L
        ) return false
        return rebindRetainedCanonicalCut(
            RetainedRendererBindingCut(
                previousOwnership = previousOwnership,
                nextOwnership = nextOwnership,
                authoritativeBaseline = authoritativeBaseline,
                previousBindingTransactionId = activeTransactionId,
            ),
        )
    }

    /**
     * The worker publishes its initial empty style cut immediately after the
     * empty START bootstrap, before the first observation opens the canonical
     * renderer owner. Seed only that exact protocol cut; all other style cuts
     * still require a preceding geometry rebuild.
     */
    private fun seedEmptyBootstrap(cut: QualifiedRendererStyleCut) {
        if (activeRenderConfig != null || activeOwnership != null || rebuildCut != null ||
            cut.transactionId !in 0L..1L ||
            cut.geometryRevision != 1L || cut.lineageRevision != 1L ||
            !cut.reset || cut.surfaceIds.isNotEmpty() || cut.styleRows.isNotEmpty() ||
            cut.targetSurfaceId != null || cut.targetDirectionIndex != null ||
            cut.semanticRevision != 1L || cut.coverageRevision != 1L ||
            cut.styleRevision != 1L || cut.residencyRevision != 1L ||
            cut.targetRevision != 1L
        ) return

        val frame = cut.ownership.groupFrame
        state.startCanonicalGroup(
            config = VisibilityGridGroupConfig(
                groupId = cut.ownership.captureGroupId,
                groupGeneration = cut.ownership.groupGeneration,
                sessionGeneration = cut.ownership.sessionGeneration,
                voxelSizeMeters = frame.voxelSizeMicrometres.toDouble() / 1_000_000.0,
                capacity = frame.modelCapacity,
                groupFromWorldGl = frame.groupFromWorldGl.toDoubleArray(),
                worldFromGroupGl = frame.worldFromGroupGl.toDoubleArray(),
                restoredGeometryRevision = cut.geometryRevision,
                restoredKeys = LongArray(0),
            ),
            geometryRevision = cut.geometryRevision,
            rows = emptyList(),
            ownership = cut.ownership,
            transactionId = cut.transactionId,
            lineageRevision = cut.lineageRevision,
        )
        activeOwnership = cut.ownership
        activeLineageRevision = cut.lineageRevision
        activeTransactionId = cut.transactionId
        activeRenderConfig = PointCloudNativeConfig(
            renderCapacity = frame.modelCapacity,
            voxelRenderMode = VoxelRenderMode.CENTROIDS,
            voxelSizeMeters = frame.voxelSizeMicrometres.toFloat() / 1_000_000f,
        )
    }

    @Synchronized
    override fun currentRowCount(): Int = state.capacity - state.freeRowCount

    @Synchronized
    override fun beginRebuild(
        cut: CommittedGeometryCut,
    ) {
        check(!closed)
        require(cut.reset)
        val preserveStyleRevisionLedger = activeOwnership == cut.ownership
        val frame = cut.ownership.groupFrame
        state.startCanonicalGroup(
            config = VisibilityGridGroupConfig(
                groupId = cut.ownership.captureGroupId,
                groupGeneration = cut.ownership.groupGeneration,
                sessionGeneration = cut.ownership.sessionGeneration,
                voxelSizeMeters = frame.voxelSizeMicrometres.toDouble() / 1_000_000.0,
                capacity = frame.modelCapacity,
                groupFromWorldGl = frame.groupFromWorldGl.toDoubleArray(),
                worldFromGroupGl = frame.worldFromGroupGl.toDoubleArray(),
                restoredGeometryRevision = cut.geometryRevision,
                restoredKeys = longArrayOf(),
            ),
            geometryRevision = cut.geometryRevision,
            rows = emptyList(),
            ownership = cut.ownership,
            transactionId = cut.transactionId,
            lineageRevision = cut.lineageRevision,
            preserveStyleRevisionLedger = preserveStyleRevisionLedger,
        )
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
        require(currentRowCount() + cut.upserts.size <= cut.ownership.groupFrame.modelCapacity)
        state.appendCanonicalRows(cut.upserts.map { it.toCanonicalRenderRow() })
    }

    @Synchronized
    override fun finishRebuild(
        cut: CommittedGeometryCut,
    ) {
        check(!closed)
        check(rebuildCut?.sameIdentityAs(cut) == true) {
            "Renderer rebuild finish does not name the active canonical cut"
        }
        activeOwnership = cut.ownership
        activeLineageRevision = cut.lineageRevision
        activeTransactionId = cut.transactionId
        val config = renderConfig(cut)
        activeRenderConfig = config
        emitPresentation(config, forceReset = true)
        discardRebuild()
    }

    @Synchronized
    override fun abortRebuild() {
        discardRebuild()
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
        activeOwnership = null
        activeLineageRevision = 0
        activeTransactionId = 0
        activeRenderConfig = null
        activePresentation = null
        presentationSelector.reset()
        state.stopGroup()
        publishPresentation(null, null)
        render(null, null)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        discardRebuild()
        activeOwnership = null
        activeLineageRevision = 0
        activeTransactionId = 0
        activeRenderConfig = null
        activePresentation = null
        presentationSelector.reset()
        state.dispose()
        publishPresentation(null, null)
        render(null, null)
    }

    /** Builds an immutable bounded descriptor and keeps the raw snapshot as a test adapter. */
    @Synchronized
    private fun emitPresentation(
        config: PointCloudNativeConfig,
        forceReset: Boolean,
        timing: RendererProjectionTimingCapture? = null,
    ) {
        try {
            if (publishPresentation === NO_PRESENTATION_PUBLISHER) {
                val publishStartedNs = if (timing == null) 0L else System.nanoTime()
                val publishStartedAllocatedBytes =
                    if (timing == null) null else artAllocatedBytes()
                render(state.snapshot(), config)
                timing?.publishMicros = elapsedMicros(publishStartedNs)
                timing?.publishAllocatedBytes = allocatedDelta(
                    publishStartedAllocatedBytes,
                    if (timing == null) null else artAllocatedBytes(),
                )
                timing?.sourceRows = currentRowCount()
                return
            }
            val ownership = activeOwnership
            if (ownership == null) {
                val publishStartedNs = if (timing == null) 0L else System.nanoTime()
                val publishStartedAllocatedBytes =
                    if (timing == null) null else artAllocatedBytes()
                publishPresentation(null, config)
                timing?.publishMicros = elapsedMicros(publishStartedNs)
                timing?.publishAllocatedBytes = allocatedDelta(
                    publishStartedAllocatedBytes,
                    if (timing == null) null else artAllocatedBytes(),
                )
                timing?.sourceRows = currentRowCount()
                return
            }
            val qualifier = CoverageRowsQualifier(
                bindingGeneration = ownership.bindingGeneration,
                groupGeneration = ownership.groupGeneration,
                rendererGeneration = config.rendererGeneration,
                transactionId = activeTransactionId,
                geometryRevision = state.currentGeometryRevision,
                styleRevision = state.currentStyleRevision,
            )
            val selectionStartedNs = if (timing == null) 0L else System.nanoTime()
            val selectionStartedAllocatedBytes =
                if (timing == null) null else artAllocatedBytes()
            val sourceUpdate = state.takePresentationUpdate()
            var selected = false
            var presentationUpdate: CoveragePointRenderUpdate? = null
            state.withCommittedRows(qualifier) { rows ->
                // Selection scans the canonical source once and retains only
                // bounded destination/source-slot identities in the selector.
                presentationUpdate = presentationSelector.selectRows(
                    rows = rows,
                    requestedCapacity = CoveragePresentationMode.SEMANTIC_CENTROIDS.presentationCapacity,
                    forceReset = forceReset,
                    sourceUpdate = sourceUpdate,
                    enabled = config.enabled,
                )
                selected = true
            }
            timing?.selectionMicros = elapsedMicros(selectionStartedNs)
            timing?.selectionAllocatedBytes = allocatedDelta(
                selectionStartedAllocatedBytes,
                if (timing == null) null else artAllocatedBytes(),
            )
            timing?.sourceRows = currentRowCount()
            timing?.selectedRows = presentationSelector.selectedCount()
            if (!selected) {
                val publishStartedNs = if (timing == null) 0L else System.nanoTime()
                val publishStartedAllocatedBytes =
                    if (timing == null) null else artAllocatedBytes()
                publishPresentation(null, config)
                timing?.publishMicros = elapsedMicros(publishStartedNs)
                timing?.publishAllocatedBytes = allocatedDelta(
                    publishStartedAllocatedBytes,
                    if (timing == null) null else artAllocatedBytes(),
                )
                return
            }
            val slots = IntArray(presentationSelector.selectedCount()) { index ->
                presentationSelector.selectedSourceSlot(index)
            }
            val descriptorStartedNs = if (timing == null) 0L else System.nanoTime()
            val descriptorStartedAllocatedBytes =
                if (timing == null) null else artAllocatedBytes()
            val descriptor = state.presentationDescriptor(
                expected = qualifier,
                mode = CoveragePresentationMode.SEMANTIC_CENTROIDS,
                enabled = config.enabled,
                selectedSourceSlots = slots,
                palette = CoverageRendererPalette.COVERAGE,
                update = presentationUpdate ?: sourceUpdate,
            )
            timing?.descriptorMicros = elapsedMicros(descriptorStartedNs)
            timing?.descriptorAllocatedBytes = allocatedDelta(
                descriptorStartedAllocatedBytes,
                if (timing == null) null else artAllocatedBytes(),
            )
            activePresentation = descriptor
            val publishStartedNs = if (timing == null) 0L else System.nanoTime()
            val publishStartedAllocatedBytes =
                if (timing == null) null else artAllocatedBytes()
            publishPresentation(descriptor, config)
            timing?.publishMicros = elapsedMicros(publishStartedNs)
            timing?.publishAllocatedBytes = allocatedDelta(
                publishStartedAllocatedBytes,
                if (timing == null) null else artAllocatedBytes(),
            )
        } finally {
            timing?.let { capture ->
                timingSink?.let { sink ->
                    runCatching { sink(capture.receipt()) }
                }
            }
        }
    }

    private fun elapsedMicros(startNs: Long): Long =
        if (startNs == 0L) 0L else ((System.nanoTime() - startNs).coerceAtLeast(0L) / 1_000L)

    @Synchronized
    internal fun presentationDescriptor(): BoundedCoveragePresentation? = activePresentation


    private fun discardRebuild() {
        rebuildCut = null
    }

    private fun CommittedGeometryCut.sameIdentityAs(other: CommittedGeometryCut): Boolean =
        ownership == other.ownership &&
            transactionId == other.transactionId &&
            baseGeometryRevision == other.baseGeometryRevision &&
            geometryRevision == other.geometryRevision &&
            lineageRevision == other.lineageRevision &&
            reset == other.reset

    private fun CommittedGeometryRow.toCanonicalRenderRow() = CanonicalRenderRow(
        surfaceId = surfaceId,
        voxelKey = packVisibilityGridKey(voxel.x, voxel.y, voxel.z),
        packedNormal = packedNormal,
        normalConfidence = normalConfidence,
        lineageCount = lineageCount,
    )
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

internal data class CanonicalVisibilityPressureSnapshot(
    val geometryRevision: Long,
    val lineageRevision: Long,
    val coverageRevision: Long,
    val styleRevision: Long,
    val canonicalSurfaceHighWater: Long,
    val associationHighWater: Long,
    val canonicalOwnedBytes: Long,
    val terminalGuidanceStatus: String,
)

internal data class RendererProjectionPressureSnapshot(
    val coverageRevision: Long = 0,
    val styleRevision: Long = 0,
)

private fun canonicalOperation(stream: InputStream): String = try {
    DataInputStream(stream).use { input ->
        require(input.readInt() == 0x4d334350 && input.readInt() in 2..3)
        input.readFully(ByteArray(32))
        input.readUTF()
        PreparedMutationKind.entries[input.readInt()].name
    }
} catch (_: Exception) {
    "invalid"
}

/** Composes column-major GL transforms only when the finite affine contract survives. */
private fun composeGroupFromCamera(
    groupFromWorld: List<Double>,
    worldFromCamera: List<Double>,
): List<Double>? {
    if (groupFromWorld.size != 16 || worldFromCamera.size != 16 ||
        groupFromWorld.any { !it.isFinite() } || worldFromCamera.any { !it.isFinite() } ||
        !isAffineTransform(groupFromWorld) || !isAffineTransform(worldFromCamera)
    ) return null
    val composed = DoubleArray(16)
    for (column in 0 until 4) {
        for (row in 0 until 4) {
            var value = 0.0
            for (index in 0 until 4) {
                value += groupFromWorld[index * 4 + row] * worldFromCamera[column * 4 + index]
                if (!value.isFinite()) return null
            }
            composed[column * 4 + row] = value
        }
    }
    return composed.takeIf(::isAffineTransform)?.toList()
}

private fun isAffineTransform(matrix: List<Double>): Boolean = matrix.size == 16 &&
    kotlin.math.abs(matrix[3]) <= 1e-6 &&
    kotlin.math.abs(matrix[7]) <= 1e-6 &&
    kotlin.math.abs(matrix[11]) <= 1e-6 &&
    kotlin.math.abs(matrix[15] - 1.0) <= 1e-6

private fun isAffineTransform(matrix: DoubleArray): Boolean = matrix.size == 16 &&
    matrix.all(Double::isFinite) &&
    kotlin.math.abs(matrix[3]) <= 1e-6 &&
    kotlin.math.abs(matrix[7]) <= 1e-6 &&
    kotlin.math.abs(matrix[11]) <= 1e-6 &&
    kotlin.math.abs(matrix[15] - 1.0) <= 1e-6

private class ExactCurrentDeltaSource : CurrentDeltaSourceV1 {
    private var value: CurrentDeltaReceiptV1? = null
    @Synchronized fun retain(receipt: CurrentDeltaReceiptV1) { check(value == null); value = receipt }
    @Synchronized override fun selectCurrentDelta(selector: CurrentDeltaSelectorV1) = value?.takeIf { it.selector == selector }
    @Synchronized fun acknowledge(selector: CurrentDeltaSelectorV1) { check(value?.selector == selector); value = null }
    @Synchronized fun clear() { value = null }
}

/** Primitive bounded source table used while deriving feature identity remaps. */
private class DepthFeatureSourceTable private constructor(maximumSources: Int) {
    private val sourceIds = LongArray(maximumSources)
    private val sourceSlots = IntArray(maximumSources)
    private val sourceVoxels = LongArray(maximumSources)
    private val targetIds = LongArray(maximumSources)
    private var size = 0
    private var overflowed = false

    fun isOverflowed(): Boolean = overflowed

    fun add(id: Long, slot: Int, voxel: Voxel) {
        if (size == sourceIds.size) {
            overflowed = true
            return
        }
        val voxelKey = packVisibilityGridKey(voxel.x, voxel.y, voxel.z)
        val index = size++
        sourceIds[index] = id
        sourceSlots[index] = slot
        sourceVoxels[index] = voxelKey
    }

    fun remapsFor(prepared: PreparedCanonicalMutation): List<CanonicalFeatureRemap> {
        sortByVoxel()
        prepared.visitDirtyRows { row ->
            findSourceByVoxel(packVisibilityGridKey(row.voxel.x, row.voxel.y, row.voxel.z))
                .takeIf { it >= 0 }
                ?.let { targetIds[it] = row.id.value }
            true
        }
        var count = 0
        for (index in 0 until size) if (targetIds[index] != sourceIds[index]) count++
        if (count == 0) return emptyList()
        val slots = IntArray(count)
        val previousIds = LongArray(count)
        val nextIds = LongArray(count)
        val writeBySourceIndex = IntArray(size) { -1 }
        val fingerprints = ByteArray(Math.multiplyExact(count, FeatureFusionKernel.HASH_BYTES))
        val hasMetadata = BooleanArray(count)
        val packedNormals = IntArray(count)
        val normalConfidences = IntArray(count)
        var write = 0
        for (index in 0 until size) {
            if (targetIds[index] == sourceIds[index]) continue
            slots[write] = sourceSlots[index]
            previousIds[write] = sourceIds[index]
            nextIds[write] = targetIds[index]
            writeBySourceIndex[index] = write
            write++
        }
        prepared.visitDirtyRows { row ->
            val sourceIndex = findSourceByVoxel(packVisibilityGridKey(row.voxel.x, row.voxel.y, row.voxel.z))
            val outputIndex = if (sourceIndex < 0) -1 else writeBySourceIndex[sourceIndex]
            if (outputIndex >= 0 && row.id.value == nextIds[outputIndex]) {
                row.allocationFingerprint.toByteArray().copyInto(
                    fingerprints, outputIndex * FeatureFusionKernel.HASH_BYTES,
                )
                packedNormals[outputIndex] = row.packedNormal
                normalConfidences[outputIndex] = row.normalConfidence
                hasMetadata[outputIndex] = true
            }
            true
        }
        return CanonicalRemapList(
            slots, previousIds, nextIds, hasMetadata, fingerprints, packedNormals, normalConfidences,
        )
    }

    private fun findSourceByVoxel(key: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            when (java.lang.Long.compareUnsigned(sourceVoxels[middle], key)) {
                0 -> return middle
                -1 -> low = middle + 1
                else -> high = middle
            }
        }
        return -1
    }

    private fun sortByVoxel() {
        for (root in (size ushr 1) - 1 downTo 0) siftDown(root, size)
        for (end in size - 1 downTo 1) {
            swap(0, end)
            siftDown(0, end)
        }
    }

    private fun siftDown(start: Int, end: Int) {
        var root = start
        while (root <= (end ushr 1) - 1) {
            var child = (root shl 1) + 1
            if (child + 1 < end &&
                java.lang.Long.compareUnsigned(sourceVoxels[child], sourceVoxels[child + 1]) < 0
            ) child++
            if (java.lang.Long.compareUnsigned(sourceVoxels[root], sourceVoxels[child]) >= 0) return
            swap(root, child)
            root = child
        }
    }

    private fun swap(first: Int, second: Int) {
        var longValue = sourceVoxels[first]
        sourceVoxels[first] = sourceVoxels[second]
        sourceVoxels[second] = longValue
        longValue = sourceIds[first]
        sourceIds[first] = sourceIds[second]
        sourceIds[second] = longValue
        val slot = sourceSlots[first]
        sourceSlots[first] = sourceSlots[second]
        sourceSlots[second] = slot
        longValue = targetIds[first]
        targetIds[first] = targetIds[second]
        targetIds[second] = longValue
    }

    companion object {
        private const val MAX_FEATURE_SOURCES = 100_000

        fun forChanges(changes: List<DepthEvidenceChange>): DepthFeatureSourceTable {
            var maximum = 0
            var overflowed = false
            changes.forEach { change ->
                maximum = try {
                    Math.addExact(maximum, change.sourceCount)
                } catch (_: ArithmeticException) {
                    overflowed = true
                    MAX_FEATURE_SOURCES
                }
            }
            if (maximum > MAX_FEATURE_SOURCES) overflowed = true
            return DepthFeatureSourceTable(maximum.coerceIn(0, MAX_FEATURE_SOURCES)).also {
                it.overflowed = overflowed
            }
        }

    }
}

private class CanonicalRemapList(
    private val slots: IntArray,
    private val previousIds: LongArray,
    private val nextIds: LongArray,
    private val hasMetadata: BooleanArray,
    private val fingerprints: ByteArray,
    private val packedNormals: IntArray,
    private val normalConfidences: IntArray,
) : java.util.AbstractList<CanonicalFeatureRemap>() {
    override val size: Int get() = slots.size

    override fun get(index: Int): CanonicalFeatureRemap {
        if (index !in slots.indices) throw IndexOutOfBoundsException("remap index $index")
        return CanonicalFeatureRemap(
            slots[index],
            SurfaceId(previousIds[index]),
            nextIds[index].takeIf { it != 0L }?.let(::SurfaceId),
            nextIds[index].takeIf { it != 0L && hasMetadata[index] }?.let {
                CanonicalFeatureProvenance(
                    CanonicalReceiptBytes(fingerprints.copyOfRange(
                        index * FeatureFusionKernel.HASH_BYTES,
                        (index + 1) * FeatureFusionKernel.HASH_BYTES,
                    )),
                    packedNormals[index], normalConfidences[index],
                )
            },
        )
    }
}
