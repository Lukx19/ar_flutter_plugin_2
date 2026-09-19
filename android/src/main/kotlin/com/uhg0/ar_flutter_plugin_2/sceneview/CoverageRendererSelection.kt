package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageCommittedRow
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageCommittedRows
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityGridRendererState
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.QUALIFIED_RENDERER_STYLE_CUT_MAX_ROWS

/** Exact logical ownership receipt shared by admission and renderer telemetry. */
internal data class CoverageRendererOwnershipReceipt(
    val canonicalStateBytes: Int,
    val mutableProjectionSelectorBytes: Int,
    val descriptorBackingBytes: Int,
    val pageReaderCapturedMappingBytes: Int,
    val stagingBytes: Int,
    val meshBytes: Int,
    val combinedRendererOwnedLimitBytes: Int = CoverageRendererLimits.COMBINED_RENDERER_OWNED_LIMIT_BYTES,
    val transitionHeadroomBytes: Int = CoverageRendererLimits.TRANSITION_HEADROOM_BYTES,
) {
    init {
        require(canonicalStateBytes >= 0)
        require(mutableProjectionSelectorBytes >= 0)
        require(descriptorBackingBytes >= 0)
        require(pageReaderCapturedMappingBytes >= 0)
        require(stagingBytes >= 0)
        require(meshBytes >= 0)
        require(combinedRendererOwnedLimitBytes >= 0)
        require(transitionHeadroomBytes >= 0)
    }

    val totalBytes: Int
        get() = canonicalStateBytes +
            mutableProjectionSelectorBytes +
            descriptorBackingBytes +
            pageReaderCapturedMappingBytes +
            stagingBytes +
            meshBytes

    companion object {
        /** Compatibility receipt for test/fake admissions without owner detail. */
        internal fun unattributed(bytes: Int): CoverageRendererOwnershipReceipt =
            CoverageRendererOwnershipReceipt(
                canonicalStateBytes = 0,
                mutableProjectionSelectorBytes = 0,
                descriptorBackingBytes = 0,
                pageReaderCapturedMappingBytes = 0,
                stagingBytes = 0,
                meshBytes = bytes,
            )
    }
}

/** Chapter 17 fixed presentation maxima; semantic-grid capacity is separate. */
internal object CoverageRendererLimits {
    const val RAW_POINT_CAPACITY = 2_000
    const val CENTROID_CAPACITY = 20_000
    const val CUBE_CAPACITY = 8_000
    const val WARM_PROXY_CAPACITY = 4_096
    const val COLD_OVERVIEW_CAPACITY = 512
    const val GLYPH_CAPACITY = 256
    const val DEBUG_ROW_CAPACITY = 1_024
    /** Maximum renderer-owned CPU/native buffers for one mounted generation. */
    const val ACTIVE_RENDERER_OWNED_LIMIT_BYTES = 14 * 1024 * 1024

    /** Combined renderer-owned ceiling while generations coexist in transition. */
    const val COMBINED_RENDERER_OWNED_LIMIT_BYTES = 32 * 1024 * 1024

    /** Derived replacement headroom between one-generation and combined limits. */
    const val TRANSITION_HEADROOM_BYTES =
        COMBINED_RENDERER_OWNED_LIMIT_BYTES - ACTIVE_RENDERER_OWNED_LIMIT_BYTES
    /** @deprecated use [COMBINED_RENDERER_OWNED_LIMIT_BYTES]. */
    @Deprecated("Use COMBINED_RENDERER_OWNED_LIMIT_BYTES")
    const val INSTANTANEOUS_TRANSITION_LIMIT_BYTES = COMBINED_RENDERER_OWNED_LIMIT_BYTES
    /** @deprecated use [TRANSITION_HEADROOM_BYTES]. */
    @Deprecated("Use TRANSITION_HEADROOM_BYTES")
    const val TRANSITION_RESERVE_BYTES = TRANSITION_HEADROOM_BYTES
    /** Maximum one-page descriptor/upload staging retained by a mesh. */
    const val PAGE_STAGING_BYTES = 64 * 1024

    const val AUXILIARY_ROW_BYTES = 16
    const val AUXILIARY_BYTES =
        (WARM_PROXY_CAPACITY + COLD_OVERVIEW_CAPACITY + GLYPH_CAPACITY + DEBUG_ROW_CAPACITY) *
            AUXILIARY_ROW_BYTES

    /**
     * The three mode resources are deliberately lazy and mutually exclusive.
     * These are the peak bytes of the one active production resource, including
     * its direct startup-index staging until Filament consumes it.
     */
    fun resourcePeakBytes(mode: VoxelRenderMode): Int =
        when (mode) {
            VoxelRenderMode.POINTS ->
                RAW_POINT_CAPACITY * CoveragePointMeshResources.PEAK_OWNED_BYTES_PER_ROW
            VoxelRenderMode.CENTROIDS ->
                CENTROID_CAPACITY * CoveragePointMeshResources.PEAK_OWNED_BYTES_PER_ROW
            VoxelRenderMode.CUBES ->
                CUBE_CAPACITY * CoverageCubeMeshResources.PEAK_OWNED_BYTES_PER_VOXEL
        }

    fun presentationCapacity(mode: VoxelRenderMode): Int =
        mode.toDefaultCoveragePresentationMode().presentationCapacity

    /**
     * Canonical geometry state is owned once by the projection at centroid
     * capacity. Presentation modes retain only compact selected slots.
     */
    fun rendererStateBytes(mode: VoxelRenderMode): Int =
        // The projection's canonical table retains the negotiated 100k
        // identity/key/style rows but borrows world positions into pages.
        VisibilityGridRendererState.ownedStorageBytes(
            QUALIFIED_RENDERER_STYLE_CUT_MAX_ROWS,
            retainWorldPositions = false,
            retainCanonicalNormalMetadata = false,
        )

    fun presentationStorageBytes(mode: VoxelRenderMode): Int =
        CoveragePresentationStorage.estimatedOwnedStorageBytes(presentationCapacity(mode))

    fun presentationStorageBytes(mode: VoxelRenderMode, sourceCapacity: Int): Int =
        CoveragePresentationStorage.estimatedOwnedStorageBytes(
            presentationCapacity(mode),
            sourceCapacity,
        )

    /**
     * One receipt for every concrete renderer-owned CPU/native allocation.
     * The descriptor and page-reader terms are bounded by the one 20k backing;
     * they are deliberately charged once even while a page is in flight.
     */
    fun ownershipReceipt(
        mode: VoxelRenderMode,
        selectorStorageBytes: Int? = null,
    ): CoverageRendererOwnershipReceipt {
        val presentationCapacity = CENTROID_CAPACITY
        return CoverageRendererOwnershipReceipt(
            canonicalStateBytes = rendererStateBytes(mode),
            mutableProjectionSelectorBytes = selectorStorageBytes
                ?: CoveragePresentationStorage.estimatedOwnedStorageBytes(
                    presentationCapacity,
                    sourceCapacity = 0,
                ),
            descriptorBackingBytes = PresentationDescriptor.estimatedBackingBytes(
                presentationCapacity,
            ),
            pageReaderCapturedMappingBytes = presentationCapacity * (
                Long.SIZE_BYTES * 2 + Int.SIZE_BYTES
            ),
            stagingBytes = PAGE_STAGING_BYTES,
            meshBytes = resourcePeakBytes(mode),
        )
    }

    fun activeRendererPeakBytes(mode: VoxelRenderMode): Int =
        activeRendererPeakBytes(mode, presentationCapacity(mode), presentationCapacity(mode))

    fun activeRendererPeakBytes(
        mode: VoxelRenderMode,
        sourceCapacity: Int,
        retainedCount: Int,
        selectorStorageBytes: Int? = null,
    ): Int = ownershipReceipt(mode, selectorStorageBytes).totalBytes

    val maximumActiveRendererBytes: Int = VoxelRenderMode.entries.maxOf(::activeRendererPeakBytes)

    init {
        check(maximumActiveRendererBytes <= ACTIVE_RENDERER_OWNED_LIMIT_BYTES)
    }

    fun snapshotHandoffBytes(mode: VoxelRenderMode): Int =
        // Mesh and hit consumers borrow canonical state; no retained handoff.
        0

    fun snapshotHandoffBytes(mode: VoxelRenderMode, retainedCount: Int): Int {
        require(retainedCount >= 0)
        return 0
    }
}

/**
 * Production-owned renderer allocation ledger. Host state, active mesh buffers
 * and the startup index buffers all reserve against one telemetry instance.
 * The mesh constructors call the same methods as the T5 campaign, so receipts
 * exercise the actual ownership model rather than reconstructing a formula.
 */
internal class CoverageRendererAllocationLedger(
    private val telemetry: RendererTelemetry,
) {
    private val resourceOwners = linkedSetOf<String>()
    /**
     * Read-only admission for a fixed-capacity replacement.  The candidate
     * peak includes the shared state, hand-off and startup staging that the
     * new production resource will charge after construction.  Keeping the
     * old total in the sum makes clear-first a safe decision whenever the two
     * generations cannot coexist under the renderer cap.
     */
    fun admitResourceReplacement(
        mode: VoxelRenderMode,
        sourceCapacity: Int = CoverageRendererLimits.presentationCapacity(mode),
        retainedCount: Int = CoverageRendererLimits.presentationCapacity(mode),
        selectorStorageBytes: Int? = null,
    ): CoverageRendererResourceAdmission {
        val currentBytes = telemetry.ownedBufferBytesSnapshot()
        val ownershipReceipt = CoverageRendererLimits.ownershipReceipt(
            mode,
            selectorStorageBytes,
        )
        val candidateBytes = ownershipReceipt.totalBytes
        val combinedBytes = currentBytes + candidateBytes
        val admission = CoverageRendererResourceAdmission(
            strategy = when {
                candidateBytes > CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES ->
                    CoverageRendererTransitionStrategy.REJECT
                combinedBytes <= CoverageRendererLimits.COMBINED_RENDERER_OWNED_LIMIT_BYTES ->
                    CoverageRendererTransitionStrategy.COEXIST
                else -> CoverageRendererTransitionStrategy.CLEAR_FIRST
            },
            currentBytes = currentBytes,
            candidateBytes = candidateBytes,
            combinedBytes = combinedBytes,
            ownershipReceipt = ownershipReceipt,
        )
        telemetry.recordResourceAdmission(admission)
        return admission
    }

    fun installPersistentCoverageState(mode: VoxelRenderMode) {
        installPersistentCoverageStateForCapacity(
            presentationCapacity = CoverageRendererLimits.presentationCapacity(mode),
        )
    }

    /** The retained selector/state charge is lazy until a snapshot exists. */
    fun installPersistentCoverageState(
        mode: VoxelRenderMode,
        snapshot: CoveragePointRenderSnapshot?,
        selectorStorageBytes: Int? = null,
    ) {
        if (snapshot == null) {
            clearCoverageState()
            return
        }
        installPersistentCoverageStateForCapacity(
            presentationCapacity = CoverageRendererLimits.presentationCapacity(mode),
            sourceCapacity = snapshot.capacity,
            selectorStorageBytes = selectorStorageBytes,
        )
    }

    /** Charges the concrete bounded state received from the native renderer. */
    fun installPersistentCoverageState(rendererState: VisibilityGridRendererState) {
        chargePersistentCoverageState(
            CoverageRendererLimits.rendererStateBytes(VoxelRenderMode.CENTROIDS),
            CoverageRendererLimits.CENTROID_CAPACITY,
            rendererState.capacity,
        )
    }

    fun installPersistentCoverageStateForCapacity(
        presentationCapacity: Int,
        sourceCapacity: Int = presentationCapacity,
        selectorStorageBytes: Int? = null,
    ) {
        chargePersistentCoverageState(
            CoverageRendererLimits.rendererStateBytes(VoxelRenderMode.CENTROIDS),
            presentationCapacity,
            sourceCapacity,
            selectorStorageBytes,
        )
    }

    private fun chargePersistentCoverageState(
        rendererStateBytes: Int,
        presentationCapacity: Int,
        sourceCapacity: Int?,
        selectorStorageBytes: Int? = null,
    ) {
        telemetry.setOwnedBufferBytes(
            RENDERER_STATE_OWNER,
            rendererStateBytes,
        )
        val ownership = CoverageRendererLimits.ownershipReceipt(VoxelRenderMode.CENTROIDS)
        telemetry.setOwnedBufferBytes(
            MUTABLE_SELECTOR_OWNER,
            selectorStorageBytes ?: ownership.mutableProjectionSelectorBytes,
        )
        telemetry.setOwnedBufferBytes(
            DESCRIPTOR_BACKING_OWNER,
            ownership.descriptorBackingBytes,
        )
        telemetry.setOwnedBufferBytes(
            PAGE_READER_MAPPING_OWNER,
            ownership.pageReaderCapturedMappingBytes,
        )
        telemetry.setOwnedBufferBytes(PAGE_STAGING_OWNER, CoverageRendererLimits.PAGE_STAGING_BYTES)
        // Auxiliary arrays are lazy. A mode-specific auxiliary owner is
        // charged only by the concrete proxy/glyph resource when requested.
        telemetry.removeOwner(AUXILIARY_OWNER)
        if (sourceCapacity == null) {
            telemetry.removeOwner(PRESENTATION_STORAGE_OWNER)
        } else {
            telemetry.removeOwner(PRESENTATION_STORAGE_OWNER)
        }
    }

    fun updateSnapshotHandoff(mode: VoxelRenderMode) {
        telemetry.setOwnedBufferBytes(
            SNAPSHOT_HANDOFF_OWNER,
            CoverageRendererLimits.snapshotHandoffBytes(mode),
        )
    }

    /** Charges only the retained hand-off that actually exists. */
    fun updateSnapshotHandoff(
        mode: VoxelRenderMode,
        snapshot: CoveragePointRenderSnapshot?,
    ) {
        val count = snapshot?.count ?: 0
        val bytes = if (count == 0) {
            0
        } else {
            CoverageRendererLimits.snapshotHandoffBytes(
                mode,
                count,
            )
        }
        if (bytes == 0) telemetry.removeOwner(SNAPSHOT_HANDOFF_OWNER)
        else telemetry.setOwnedBufferBytes(SNAPSHOT_HANDOFF_OWNER, bytes)
    }

    fun clearCoverageState() {
        releaseRendererResources()
        telemetry.removeOwner(RENDERER_STATE_OWNER)
        telemetry.removeOwner(AUXILIARY_OWNER)
        telemetry.removeOwner(PRESENTATION_STORAGE_OWNER)
        telemetry.removeOwner(MUTABLE_SELECTOR_OWNER)
        telemetry.removeOwner(DESCRIPTOR_BACKING_OWNER)
        telemetry.removeOwner(PAGE_READER_MAPPING_OWNER)
        telemetry.removeOwner(PAGE_STAGING_OWNER)
        telemetry.removeOwner(SNAPSHOT_HANDOFF_OWNER)
    }

    /**
     * Releases mesh generations and page staging while retaining the committed
     * canonical projection and its immutable presentation backing.
     * Repeated pressure/pause/failure callbacks are intentionally idempotent.
     */
    fun releaseRendererResources() {
        telemetry.removeOwner(PAGE_STAGING_OWNER)
        telemetry.removeOwner(SNAPSHOT_HANDOFF_OWNER)
        resourceOwners.forEach(telemetry::removeOwner)
        resourceOwners.clear()
        telemetry.removeCoverageMeshOwners()
    }

    fun installPointResources(owner: String, capacity: Int) {
        resourceOwners += owner
        telemetry.setOwnedBufferBytes(
            owner,
            capacity * CoveragePointMeshResources.STEADY_OWNED_BYTES_PER_ROW,
        )
        telemetry.setOwnedBufferBytes(
            pointStartupOwner(owner),
            capacity * CoveragePointMeshResources.STARTUP_INDEX_STAGING_BYTES_PER_ROW,
        )
    }

    fun completePointStartup(owner: String) {
        telemetry.removeOwner(pointStartupOwner(owner))
    }

    fun releasePointResources(owner: String) {
        completePointStartup(owner)
        telemetry.removeOwner(owner)
        resourceOwners.remove(owner)
    }

    fun installCubeResources(owner: String, capacity: Int) {
        resourceOwners += owner
        telemetry.setOwnedBufferBytes(
            owner,
            capacity * CoverageCubeMeshResources.STEADY_OWNED_BYTES_PER_VOXEL,
        )
        telemetry.setOwnedBufferBytes(
            cubeTriangleStartupOwner(owner),
            capacity * CoverageCubeMeshResources.TRIANGLE_INDEX_STAGING_BYTES_PER_VOXEL,
        )
        telemetry.setOwnedBufferBytes(
            cubeOutlineStartupOwner(owner),
            capacity * CoverageCubeMeshResources.OUTLINE_INDEX_STAGING_BYTES_PER_VOXEL,
        )
    }

    fun completeCubeTriangleStartup(owner: String) {
        telemetry.removeOwner(cubeTriangleStartupOwner(owner))
    }

    fun completeCubeOutlineStartup(owner: String) {
        telemetry.removeOwner(cubeOutlineStartupOwner(owner))
    }

    fun releaseCubeResources(owner: String) {
        completeCubeTriangleStartup(owner)
        completeCubeOutlineStartup(owner)
        telemetry.removeOwner(owner)
        resourceOwners.remove(owner)
    }

    private fun pointStartupOwner(owner: String) = "$owner-startup-index"
    private fun cubeTriangleStartupOwner(owner: String) = "$owner-startup-triangle-index"
    private fun cubeOutlineStartupOwner(owner: String) = "$owner-startup-outline-index"

    private companion object {
        const val RENDERER_STATE_OWNER = "coverage-renderer-state"
        const val AUXILIARY_OWNER = "coverage-auxiliary-state"
        const val PRESENTATION_STORAGE_OWNER = "coverage-presentation-storage"
        const val MUTABLE_SELECTOR_OWNER = "coverage-mutable-projection-selector"
        const val DESCRIPTOR_BACKING_OWNER = "coverage-descriptor-backing"
        const val PAGE_READER_MAPPING_OWNER = "coverage-page-reader-mapping"
        const val PAGE_STAGING_OWNER = "coverage-page-staging"
        const val SNAPSHOT_HANDOFF_OWNER = "coverage-snapshot-handoff"
    }
}

/**
 * Persistent, bounded presentation selector. It performs a complete source
 * pass only when its identity basis changes (initial mount, a resync, or a
 * source-slot rewrite). Ordinary changes are translated through retained
 * primitive source/key-to-presentation-slot tables, avoiding a 20k sort and
 * an 8k reset for every revision.
 *
 * The policy is deterministic: keep the lowest identities, with source-slot
 * order as the tie-breaker. New candidates can replace the current largest
 * identity. A replacement reuses the evicted row's destination, so every
 * retained row preserves its GPU destination and only the replacement row is
 * dirty.
 */
internal class CoveragePresentationSelector(
    private val maximumCapacity: Int,
) {
    private var activeCapacity = 0
    private val storage = CoveragePresentationStorage(maximumCapacity)
    private val selectedSourceSlots get() = storage.selectedSourceSlots
    private val selectedKeys get() = storage.selectedKeys
    private val selectedSurfaceIds get() = storage.selectedSurfaceIds
    private val freeDestinations get() = storage.freeDestinations
    private var freeDestinationCount = 0
    private var selectedCount = 0
    private var sourceCount = 0
    private val sourceSlotToDestination get() = storage.sourceSlotToDestination
    private val sourceRankingSurfaceIds get() = storage.sourceRankingSurfaceIds
    private val sourceRankingKeys get() = storage.sourceRankingKeys
    private val sourceRankingStyles get() = storage.sourceRankingStyles
    private var initialized = false
    private var styleRowsPresent = false
    /**
     * Production source-stream state. Unlike the snapshot compatibility path,
     * this retains only selected source slots and their stable identities; a
     * 100k canonical cut is visited through [CoverageCommittedRows] and never
     * copied into source-sized selector arrays.
     */
    private var streamInitialized = false
    private var streamSourceCount = 0
    private var streamStyleRevision = -1L
    private var streamGeometryRevision = -1L

    /** Binary-search lookup over selected stable identities. */
    fun destinationForSurfaceId(surfaceId: Long): Int {
        val size = selectedCount
        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                storage.sortedSurfaceIds[middle] < surfaceId -> low = middle + 1
                storage.sortedSurfaceIds[middle] > surfaceId -> high = middle - 1
                else -> return storage.sortedDestinations[middle]
            }
        }
        return -1
    }

    fun selectedSourceSlot(destination: Int): Int {
        require(destination in 0 until selectedCount)
        return selectedSourceSlots[destination]
    }

    fun selectedSurfaceId(destination: Int): Long {
        require(destination in 0 until selectedCount)
        return selectedSurfaceIds[destination]
    }

    fun selectedCount(): Int = selectedCount

    init {
        require(maximumCapacity > 0)
    }

    val ownedStorageBytes: Int
        get() = storage.ownedStorageBytes

    /** Exact post-selection storage, including this selector's source high-water. */
    fun ownedStorageBytesFor(requestedCapacity: Int, sourceCapacity: Int): Int {
        require(requestedCapacity in 1..maximumCapacity)
        require(sourceCapacity >= 0)
        if (streamInitialized) {
            return CoveragePresentationStorage.estimatedOwnedStorageBytes(requestedCapacity)
        }
        return CoveragePresentationStorage.estimatedOwnedStorageBytes(
            requestedCapacity,
            maxOf(storage.sourceCapacity, sourceCapacity),
        )
    }

    fun select(
        snapshot: CoveragePointRenderSnapshot,
        requestedCapacity: Int = maximumCapacity,
        forceReset: Boolean = snapshot.update?.reset ?: true,
    ): CoveragePointRenderSnapshot {
        require(requestedCapacity in 1..maximumCapacity)
        validate(snapshot)
        val styleRowsAvailable = snapshot.styleRows.isNotEmpty()
        val capacityChanged = activeCapacity != requestedCapacity
        val styleChanged = initialized && storage.styleRowsPresent != styleRowsAvailable
        if (capacityChanged) {
            if (activeCapacity > 0) reset()
            activeCapacity = requestedCapacity
        }
        storage.ensurePresentationCapacity(requestedCapacity, styleRowsAvailable)
        if (styleChanged) reset()
        if (forceReset && initialized) reset()
        styleRowsPresent = styleRowsAvailable
        // Capacity describes the upstream fixed array; only committed rows
        // need selector metadata. This is the bounded source high-water.
        ensureSourceCapacity(snapshot.count)
        val sourceShrunk = initialized && snapshot.count < sourceCount
        val sourceRewritten = initialized && !sourceShrunk && selectedIdentityChanged(snapshot)
        val rankingRewritten = initialized && sourceRankingChanged(snapshot)
        if (!initialized || sourceRewritten || rankingRewritten || sourceShrunk) {
            initialize(snapshot)
            return presentation(snapshot, reset = true, spans = fullSpan())
        }

        val membershipDirtyDestinations = acceptNewCandidates(snapshot)
        if (membershipDirtyDestinations.isNotEmpty()) {
            sourceCount = snapshot.count
            rememberSourceRanking(snapshot)
            return presentation(
                snapshot,
                reset = false,
                spans = dirtySpans(membershipDirtyDestinations),
            )
        }

        sourceCount = snapshot.count
        rememberSourceRanking(snapshot)
        val update = snapshot.update
        if (update == null) {
            // The owner fences explicit resyncs before calling the selector.
            // Retained controls (especially palette changes) must not turn a
            // missing dirty list into a selector reset.
            rebuildSelectedRows(snapshot)
            return presentation(snapshot, reset = false, spans = fullSpan())
        }
        if (update.reset) {
            rebuildSelectedRows(snapshot)
            return presentation(snapshot, reset = false, spans = fullSpan())
        }

        val spans = applyDirtySpans(snapshot, update)
        return presentation(snapshot, reset = false, spans = spans)
    }

    /**
     * Selects a bounded presentation directly from a synchronous canonical
     * row borrow. The source view is valid only for this call. Production
     * projection callers use [selectRows] so no render snapshot is created.
     */
    fun select(
        rows: CoverageCommittedRows,
        requestedCapacity: Int = maximumCapacity,
        forceReset: Boolean = false,
        sourceUpdate: CoveragePointRenderUpdate? = rows.update,
        enabled: Boolean = true,
    ): CoveragePointRenderSnapshot {
        val update = selectRows(rows, requestedCapacity, forceReset, sourceUpdate, enabled)
        return streamPresentation(rows, enabled, update.reset, update.spans)
    }

    /**
     * Production-only selection seam. It updates the bounded selected-slot
     * table and returns range metadata; canonical geometry and style values
     * stay behind the borrow callback and are not materialised as a snapshot.
     */
    fun selectRows(
        rows: CoverageCommittedRows,
        requestedCapacity: Int = maximumCapacity,
        forceReset: Boolean = false,
        sourceUpdate: CoveragePointRenderUpdate? = rows.update,
        enabled: Boolean = true,
    ): CoveragePointRenderUpdate {
        require(requestedCapacity in 1..maximumCapacity)
        require(rows.count in 0..rows.capacity)
        if (storage.sourceCapacity > 0) storage.dropSourceTracking()
        val capacityChanged = activeCapacity != requestedCapacity
        if (capacityChanged) {
            if (activeCapacity > 0) reset()
            activeCapacity = requestedCapacity
        }
        storage.ensurePresentationCapacity(requestedCapacity, withStyleRows = true)
        val updateReset = sourceUpdate?.reset == true
        val styleChanged = streamInitialized &&
            rows.qualifier.styleRevision != streamStyleRevision
        val geometryRewritten = streamInitialized &&
            rows.qualifier.geometryRevision != streamGeometryRevision && sourceUpdate == null
        val mustReset = forceReset || updateReset || !streamInitialized || styleChanged || geometryRewritten ||
            rows.count < streamSourceCount
        val reinitialized = if (mustReset) {
            streamInitialize(rows)
            true
        } else {
            streamAcceptChanges(rows, sourceUpdate)
        }
        streamSourceCount = rows.count
        streamStyleRevision = rows.qualifier.styleRevision
        streamGeometryRevision = rows.qualifier.geometryRevision
        val spans = if (mustReset || reinitialized) fullSpan() else {
            val sourceSpans = sourceUpdate?.spans.orEmpty()
            val incremental = remapStreamSpans(sourceSpans)
            val membership = storage.drainDirty(selectedCount)
            mergeSpans(incremental, dirtySpans(membership))
        }
        return CoveragePointRenderUpdate(
            geometryRevision = sourceUpdate?.geometryRevision ?: rows.qualifier.geometryRevision,
            visibilityRevision = sourceUpdate?.visibilityRevision ?: rows.qualifier.geometryRevision,
            enabled = enabled,
            count = selectedCount,
            spans = spans,
            reset = mustReset,
        )
    }

    /** Drops retained source-to-presentation state before a new resource cut. */
    fun reset() {
        if (activeCapacity == 0) {
            initialized = false
            styleRowsPresent = false
            streamInitialized = false
            streamSourceCount = 0
            streamStyleRevision = -1L
            streamGeometryRevision = -1L
            return
        }
        selectedCount = 0
        sourceCount = 0
        freeDestinationCount = activeCapacity
        repeat(activeCapacity) { destination ->
            selectedSourceSlots[destination] = -1
            freeDestinations[destination] = activeCapacity - destination - 1
        }
        sourceSlotToDestination.fill(-1)
        sourceRankingSurfaceIds.fill(0L)
        sourceRankingKeys.fill(0L)
        sourceRankingStyles.fill(0)
        storage.clearDirty()
        initialized = false
        styleRowsPresent = false
        streamInitialized = false
        streamSourceCount = 0
        streamStyleRevision = -1L
        streamGeometryRevision = -1L
    }

    private fun streamInitialize(rows: CoverageCommittedRows) {
        storage.clearDirty()
        freeDestinationCount = activeCapacity
        repeat(activeCapacity) { destination ->
            freeDestinations[destination] = activeCapacity - destination - 1
            selectedSourceSlots[destination] = -1
        }
        selectedCount = minOf(rows.count, activeCapacity)
        val heap = IntArray(selectedCount)
        var heapSize = 0
        for (source in 0 until rows.count) {
            if (heapSize < selectedCount) {
                heap[heapSize] = source
                streamSiftUp(heap, heapSize, rows)
                heapSize++
            } else if (selectedCount > 0 && compareCommittedRows(rows.rowAt(source), rows.rowAt(heap[0])) < 0) {
                heap[0] = source
                streamSiftDown(heap, 0, heapSize, rows)
            }
        }
        val selectedSources = IntArray(selectedCount)
        for (destination in selectedCount - 1 downTo 0) {
            selectedSources[destination] = heap[0]
            heap[0] = heap[--heapSize]
            if (heapSize > 0) streamSiftDown(heap, 0, heapSize, rows)
        }
        selectedCount = 0
        selectedSources.forEach { source -> assignStreamSource(rows, source) }
        rebuildIdentityLookup()
        streamInitialized = true
    }

    private fun streamAcceptChanges(
        rows: CoverageCommittedRows,
        sourceUpdate: CoveragePointRenderUpdate?,
    ): Boolean {
        storage.clearDirty()
        if (rows.count > streamSourceCount) {
            for (source in streamSourceCount until rows.count) {
                considerStreamCandidate(rows, source)
            }
        }
        sourceUpdate?.spans.orEmpty().forEach { span ->
            val end = minOf(span.endSlotExclusive, rows.count)
            for (source in span.startSlot until end) {
                val row = rows.rowAt(source)
                val destination = selectedDestinationForSource(source)
                if (destination >= 0) {
                    // A source identity replacement invalidates the compact
                    // source-slot cut; recompute deterministically on the next
                    // call while preserving the current callback's safety.
                    if (row.surfaceId != selectedSurfaceIds[destination] ||
                        row.key != selectedKeys[destination]
                    ) {
                        streamInitialize(rows)
                        return true
                    }
                    selectedKeys[destination] = row.key
                    selectedSurfaceIds[destination] = row.surfaceId
                } else {
                    considerStreamCandidate(rows, source)
                }
            }
        }
        rebuildIdentityLookup()
        return false
    }

    private fun considerStreamCandidate(rows: CoverageCommittedRows, source: Int) {
        if (selectedCount < activeCapacity) {
            val destination = assignStreamSource(rows, source)
            storage.markDirty(destination)
            return
        }
        if (selectedCount == 0) return
        val largestDestination = (0 until selectedCount).maxWithOrNull { first, second ->
            compareCommittedRows(
                rows.rowAt(selectedSourceSlots[first]),
                rows.rowAt(selectedSourceSlots[second]),
            )
        } ?: return
        val largestSource = selectedSourceSlots[largestDestination]
        if (compareCommittedRows(rows.rowAt(source), rows.rowAt(largestSource)) < 0) {
            selectedSourceSlots[largestDestination] = source
            selectedKeys[largestDestination] = rows.rowAt(source).key
            selectedSurfaceIds[largestDestination] = rows.rowAt(source).surfaceId
            storage.markDirty(largestDestination)
        }
    }

    private fun assignStreamSource(rows: CoverageCommittedRows, source: Int): Int {
        check(freeDestinationCount > 0)
        val destination = freeDestinations[--freeDestinationCount]
        val row = rows.rowAt(source)
        selectedSourceSlots[destination] = source
        selectedKeys[destination] = row.key
        selectedSurfaceIds[destination] = row.surfaceId
        selectedCount++
        return destination
    }

    private fun selectedDestinationForSource(source: Int): Int {
        for (destination in 0 until selectedCount) {
            if (selectedSourceSlots[destination] == source) return destination
        }
        return -1
    }

    private fun remapStreamSpans(sourceSpans: List<CoveragePointSpan>): List<CoveragePointSpan> {
        if (sourceSpans.isEmpty() || selectedCount == 0) return emptyList()
        val destinations = ArrayList<Int>()
        sourceSpans.forEach { span ->
            repeat(selectedCount) { destination ->
                val source = selectedSourceSlots[destination]
                if (source >= span.startSlot && source < span.endSlotExclusive) destinations += destination
            }
        }
        destinations.sort()
        return dirtySpans(destinations.distinct().toIntArray())
    }

    private fun mergeSpans(first: List<CoveragePointSpan>, second: List<CoveragePointSpan>): List<CoveragePointSpan> {
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        val ranges = (first + second).sortedBy { it.startSlot }
        val merged = ArrayList<CoveragePointSpan>()
        var start = ranges.first().startSlot
        var end = ranges.first().endSlotExclusive
        ranges.drop(1).forEach { span ->
            if (span.startSlot <= end) end = maxOf(end, span.endSlotExclusive)
            else {
                merged += CoveragePointSpan(start, FloatArray(0), IntArray(0), endSlotExclusive = end)
                start = span.startSlot
                end = span.endSlotExclusive
            }
        }
        merged += CoveragePointSpan(start, FloatArray(0), IntArray(0), endSlotExclusive = end)
        return merged
    }

    private fun streamPresentation(
        rows: CoverageCommittedRows,
        enabled: Boolean,
        reset: Boolean,
        spans: List<CoveragePointSpan>,
    ): CoveragePointRenderSnapshot {
        val keys = LongArray(selectedCount)
        val surfaces = LongArray(selectedCount)
        val positions = FloatArray(selectedCount * CoveragePointMeshResources.POSITION_COMPONENTS)
        val colors = IntArray(selectedCount)
        val styles = ByteArray(selectedCount * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        repeat(selectedCount) { destination ->
            val row = rows.rowAt(selectedSourceSlots[destination])
            keys[destination] = row.key
            surfaces[destination] = row.surfaceId
            val offset = destination * CoveragePointMeshResources.POSITION_COMPONENTS
            positions[offset] = row.x
            positions[offset + 1] = row.y
            positions[offset + 2] = row.z
            colors[destination] = row.color
            row.style.encode().copyInto(styles, destination * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        }
        val qualifier = rows.qualifier
        return CoveragePointRenderSnapshot(
            revision = qualifier.rendererGeneration,
            enabled = enabled,
            capacity = activeCapacity,
            count = selectedCount,
            keys = keys,
            surfaceIds = surfaces,
            positions = positions,
            colors = colors,
            styleRows = styles,
            update = CoveragePointRenderUpdate(
                geometryRevision = qualifier.geometryRevision,
                visibilityRevision = qualifier.styleRevision,
                enabled = enabled,
                count = selectedCount,
                spans = spans,
                reset = reset,
            ),
            bindingGeneration = qualifier.bindingGeneration,
            groupGeneration = qualifier.groupGeneration,
            transactionId = qualifier.transactionId,
            geometryRevision = qualifier.geometryRevision,
            styleRevision = qualifier.styleRevision,
        )
    }

    private fun streamSiftUp(heap: IntArray, start: Int, rows: CoverageCommittedRows) {
        var child = start
        while (child > 0) {
            val parent = (child - 1) / 2
            if (compareCommittedRows(rows.rowAt(heap[child]), rows.rowAt(heap[parent])) <= 0) return
            val value = heap[parent]
            heap[parent] = heap[child]
            heap[child] = value
            child = parent
        }
    }

    private fun streamSiftDown(heap: IntArray, start: Int, size: Int, rows: CoverageCommittedRows) {
        var parent = start
        while (true) {
            val left = parent * 2 + 1
            if (left >= size) return
            val right = left + 1
            val child = if (right < size &&
                compareCommittedRows(rows.rowAt(heap[right]), rows.rowAt(heap[left])) > 0
            ) right else left
            if (compareCommittedRows(rows.rowAt(heap[child]), rows.rowAt(heap[parent])) <= 0) return
            val value = heap[parent]
            heap[parent] = heap[child]
            heap[child] = value
            parent = child
        }
    }

    private fun compareCommittedRows(first: CoverageCommittedRow, second: CoverageCommittedRow): Int {
        val firstRenderer = VisibilityRendererRow(
            surfaceId = first.surfaceId,
            x = first.x,
            y = first.y,
            z = first.z,
            semanticLabel = first.style.semantic,
            coverageLabel = first.style.coverage,
            targetDirectionIndex = first.style.directionBin.takeUnless { it == 0xff },
            style = first.style,
        )
        val secondRenderer = VisibilityRendererRow(
            surfaceId = second.surfaceId,
            x = second.x,
            y = second.y,
            z = second.z,
            semanticLabel = second.style.semantic,
            coverageLabel = second.style.coverage,
            targetDirectionIndex = second.style.directionBin.takeUnless { it == 0xff },
            style = second.style,
        )
        val order = compareCoverageRows(firstRenderer, secondRenderer)
        if (order != 0) return order
        val keyOrder = first.key.compareTo(second.key)
        return if (keyOrder != 0) keyOrder else first.surfaceId.compareTo(second.surfaceId)
    }

    private fun initialize(snapshot: CoveragePointRenderSnapshot) {
        sourceSlotToDestination.fill(-1)
        freeDestinationCount = activeCapacity
        for (destination in 0 until activeCapacity) {
            freeDestinations[destination] = activeCapacity - destination - 1
            selectedSourceSlots[destination] = -1
        }
        selectedCount = minOf(snapshot.count, activeCapacity)
        val heap = IntArray(selectedCount)
        var heapSize = 0
        for (source in 0 until snapshot.count) {
            if (heapSize < selectedCount) {
                heap[heapSize] = source
                siftUp(heap, heapSize, snapshot)
                heapSize++
            } else if (selectedCount > 0 && compareSource(source, heap[0], snapshot) < 0) {
                heap[0] = source
                siftDown(heap, 0, heapSize, snapshot)
            }
        }
        val selectedSources = IntArray(selectedCount)
        for (destination in selectedCount - 1 downTo 0) {
            selectedSources[destination] = heap[0]
            heap[0] = heap[--heapSize]
            if (heapSize > 0) siftDown(heap, 0, heapSize, snapshot)
        }
        selectedCount = 0
        selectedSources.forEach { source -> assignSourceToFreeDestination(snapshot, source) }
        sourceCount = snapshot.count
        rememberSourceRanking(snapshot)
        rebuildIdentityLookup()
        initialized = true
    }

    private fun acceptNewCandidates(snapshot: CoveragePointRenderSnapshot): IntArray {
        storage.clearDirty()
        for (source in sourceCount until snapshot.count) {
            if (selectedCount < activeCapacity) {
                val destination = assignSourceToFreeDestination(snapshot, source)
                storage.markDirty(destination)
            } else if (selectedCount > 0) {
                val largestDestination = (0 until selectedCount).maxWithOrNull { first, second ->
                    compareSource(selectedSourceSlots[first], selectedSourceSlots[second], snapshot)
                }
                val evictedSource = largestDestination?.let(selectedSourceSlots::get)
                if (largestDestination != null && evictedSource != null &&
                    compareSource(source, evictedSource, snapshot) < 0
                ) {
                    val destination = largestDestination
                    sourceSlotToDestination[evictedSource] = -1
                    selectedSourceSlots[destination] = source
                    selectedKeys[destination] = snapshot.keys[source]
                    selectedSurfaceIds[destination] = snapshot.surfaceIds[source]
                    sourceSlotToDestination[source] = destination
                    storage.markDirty(destination)
                }
            }
        }
        rebuildIdentityLookup()
        return storage.drainDirty(selectedCount)
    }

    private fun assignSourceToFreeDestination(
        snapshot: CoveragePointRenderSnapshot,
        source: Int,
    ): Int {
        check(freeDestinationCount > 0)
        val destination = freeDestinations[--freeDestinationCount]
        selectedSourceSlots[destination] = source
        selectedKeys[destination] = snapshot.keys[source]
        selectedSurfaceIds[destination] = snapshot.surfaceIds[source]
        sourceSlotToDestination[source] = destination
        selectedCount++
        return destination
    }

    private fun rebuildIdentityLookup() {
        for (destination in 0 until selectedCount) {
            storage.sortedSurfaceIds[destination] = selectedSurfaceIds[destination]
            storage.sortedDestinations[destination] = destination
        }
        val order = (0 until selectedCount).toMutableList()
        order.sortBy { storage.sortedSurfaceIds[it] }
        val ids = LongArray(selectedCount)
        val destinations = IntArray(selectedCount)
        order.forEachIndexed { index, original ->
            ids[index] = storage.sortedSurfaceIds[original]
            destinations[index] = storage.sortedDestinations[original]
        }
        ids.copyInto(storage.sortedSurfaceIds)
        destinations.copyInto(storage.sortedDestinations)
    }

    private fun applyDirtySpans(
        snapshot: CoveragePointRenderSnapshot,
        update: CoveragePointRenderUpdate,
    ): List<CoveragePointSpan> {
        val spans = ArrayList<CoveragePointSpan>()
        update.spans.forEach { span ->
            var source = span.startSlot
            val end = span.endSlotExclusive
            while (source < end) {
                val destination = sourceSlotToDestination.getOrElse(source) { -1 }
                if (destination < 0) {
                    source++
                    continue
                }
                val firstDestination = destination
                var run = 1
                source++
                while (source < end &&
                    sourceSlotToDestination.getOrElse(source) { -1 } == firstDestination + run
                ) {
                    source++
                    run++
                }
                spans += CoveragePointSpan(
                    startSlot = firstDestination,
                    positions = FloatArray(0),
                    colors = IntArray(0),
                    styleRows = ByteArray(0),
                    endSlotExclusive = firstDestination + run,
                )
            }
        }
        return spans
    }

    private fun selectedIdentityChanged(snapshot: CoveragePointRenderSnapshot): Boolean =
        (0 until selectedCount).any { destination ->
            snapshot.surfaceIds[selectedSourceSlots[destination]] != selectedSurfaceIds[destination]
        }

    private fun rebuildSelectedRows(snapshot: CoveragePointRenderSnapshot) {
        for (destination in 0 until selectedCount) {
            val source = selectedSourceSlots[destination]
            selectedKeys[destination] = snapshot.keys[source]
            selectedSurfaceIds[destination] = snapshot.surfaceIds[source]
        }
    }

    private fun presentation(
        source: CoveragePointRenderSnapshot,
        reset: Boolean,
        spans: List<CoveragePointSpan>,
    ): CoveragePointRenderSnapshot = source.copy(
        capacity = activeCapacity,
        count = selectedCount,
        keys = selectedKeys.copyOf(selectedCount),
        surfaceIds = LongArray(selectedCount) { destination ->
            source.surfaceIds[selectedSourceSlots[destination]]
        },
        positions = FloatArray(selectedCount * CoveragePointMeshResources.POSITION_COMPONENTS) {
            destination ->
                val sourceSlot = selectedSourceSlots[
                    destination / CoveragePointMeshResources.POSITION_COMPONENTS
                ]
                source.positions[
                    sourceSlot * CoveragePointMeshResources.POSITION_COMPONENTS +
                        destination % CoveragePointMeshResources.POSITION_COMPONENTS
                ]
        },
        colors = IntArray(selectedCount) { destination ->
            source.colors[selectedSourceSlots[destination]]
        },
        styleRows = if (!styleRowsPresent) ByteArray(0) else ByteArray(
            selectedCount * COVERAGE_RENDERER_STYLE_ROW_BYTES,
        ).also { styles ->
            repeat(selectedCount) { destination ->
                val sourceSlot = selectedSourceSlots[destination]
                source.styleRows.copyInto(
                    styles,
                    destination * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                    sourceSlot * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                    (sourceSlot + 1) * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                )
            }
        },
        update = CoveragePointRenderUpdate(
            geometryRevision = source.update?.geometryRevision ?: source.revision,
            visibilityRevision = source.update?.visibilityRevision ?: source.revision,
            enabled = source.enabled,
            count = selectedCount,
            spans = spans,
            reset = reset,
        ),
    )

    private fun fullSpan(): List<CoveragePointSpan> =
        if (selectedCount == 0) emptyList() else {
            listOf(
                CoveragePointSpan(
                    startSlot = 0,
                    positions = FloatArray(0),
                    colors = IntArray(0),
                    endSlotExclusive = selectedCount,
                ),
            )
        }

    private fun dirtySpans(destinations: IntArray): List<CoveragePointSpan> {
        if (destinations.isEmpty()) return emptyList()
        val spans = ArrayList<CoveragePointSpan>()
        var first = destinations[0]
        var previous = first
        fun appendSpan(start: Int, endInclusive: Int) {
            val endExclusive = endInclusive + 1
            spans += CoveragePointSpan(
                startSlot = start,
                positions = FloatArray(0),
                colors = IntArray(0),
                endSlotExclusive = endExclusive,
            )
        }
        for (index in 1 until destinations.size) {
            val destination = destinations[index]
            if (destination != previous + 1) {
                appendSpan(first, previous)
                first = destination
            }
            previous = destination
        }
        appendSpan(first, previous)
        return spans
    }

    private fun ensureSourceCapacity(sourceCapacity: Int) {
        storage.ensureSourceCapacity(sourceCapacity)
    }

    /**
     * Detects ranking changes in every previously known source slot. An
     * unselected row can become the best candidate after a target/need/
     * residency change, so retaining the prior cut would make mesh, hit and
     * telemetry selection disagree. Geometry and color are intentionally
     * absent from this fingerprint and continue through dirty-span updates.
     */
    private fun sourceRankingChanged(snapshot: CoveragePointRenderSnapshot): Boolean {
        val comparedCount = minOf(sourceCount, snapshot.count)
        return (0 until comparedCount).any { source ->
            val style = styleAt(snapshot, source)
            snapshot.surfaceIds[source] != sourceRankingSurfaceIds[source] ||
                snapshot.keys[source] != sourceRankingKeys[source] ||
                rankingStyle(style) != sourceRankingStyles[source]
        }
    }

    private fun rememberSourceRanking(snapshot: CoveragePointRenderSnapshot) {
        for (source in 0 until snapshot.count) {
            val style = styleAt(snapshot, source)
            sourceRankingSurfaceIds[source] = snapshot.surfaceIds[source]
            sourceRankingKeys[source] = snapshot.keys[source]
            sourceRankingStyles[source] = rankingStyle(style)
        }
    }

    private fun rankingStyle(style: CoverageRendererStyleRowV1): Int =
        style.target.code or
            (style.coverage.code shl 4) or
            (style.residency.code shl 8)

    private fun validate(snapshot: CoveragePointRenderSnapshot) {
        require(snapshot.count in 0..snapshot.capacity)
        require(snapshot.keys.size == snapshot.count)
        require(snapshot.positions.size == snapshot.count * CoveragePointMeshResources.POSITION_COMPONENTS)
        require(snapshot.colors.size == snapshot.count)
        require(
            snapshot.styleRows.isEmpty() ||
                snapshot.styleRows.size == snapshot.count * COVERAGE_RENDERER_STYLE_ROW_BYTES,
        )
    }

    private fun siftUp(heap: IntArray, start: Int, snapshot: CoveragePointRenderSnapshot) {
        var child = start
        while (child > 0) {
            val parent = (child - 1) / 2
            if (compareSource(heap[child], heap[parent], snapshot) <= 0) return
            val value = heap[parent]
            heap[parent] = heap[child]
            heap[child] = value
            child = parent
        }
    }

    private fun siftDown(heap: IntArray, start: Int, size: Int, snapshot: CoveragePointRenderSnapshot) {
        var parent = start
        while (true) {
            val left = parent * 2 + 1
            if (left >= size) return
            val right = left + 1
            val child = if (right < size && compareSource(heap[right], heap[left], snapshot) > 0) right else left
            if (compareSource(heap[child], heap[parent], snapshot) <= 0) return
            val value = heap[parent]
            heap[parent] = heap[child]
            heap[child] = value
            parent = child
        }
    }

    private fun compareSource(
        first: Int,
        second: Int,
        snapshot: CoveragePointRenderSnapshot,
    ): Int {
        val firstStyle = styleAt(snapshot, first)
        val secondStyle = styleAt(snapshot, second)
        val firstRow = VisibilityRendererRow(
            surfaceId = snapshot.surfaceIds[first],
            x = snapshot.positions[first * CoveragePointMeshResources.POSITION_COMPONENTS],
            y = snapshot.positions[first * CoveragePointMeshResources.POSITION_COMPONENTS + 1],
            z = snapshot.positions[first * CoveragePointMeshResources.POSITION_COMPONENTS + 2],
            semanticLabel = firstStyle.semantic,
            coverageLabel = firstStyle.coverage,
            targetDirectionIndex = firstStyle.directionBin.takeUnless { it == 0xff },
            style = firstStyle,
        )
        val secondRow = VisibilityRendererRow(
            surfaceId = snapshot.surfaceIds[second],
            x = snapshot.positions[second * CoveragePointMeshResources.POSITION_COMPONENTS],
            y = snapshot.positions[second * CoveragePointMeshResources.POSITION_COMPONENTS + 1],
            z = snapshot.positions[second * CoveragePointMeshResources.POSITION_COMPONENTS + 2],
            semanticLabel = secondStyle.semantic,
            coverageLabel = secondStyle.coverage,
            targetDirectionIndex = secondStyle.directionBin.takeUnless { it == 0xff },
            style = secondStyle,
        )
        val rowOrder = compareCoverageRows(firstRow, secondRow)
        return if (rowOrder != 0) rowOrder else {
            val keyOrder = snapshot.keys[first].compareTo(snapshot.keys[second])
            if (keyOrder != 0) keyOrder else first.compareTo(second)
        }
    }

    private fun styleAt(snapshot: CoveragePointRenderSnapshot, index: Int): CoverageRendererStyleRowV1 =
        if (snapshot.styleRows.isEmpty()) CoverageRendererStyleRowV1()
        else CoverageRendererStyleRowV1.decode(
            snapshot.styleRows,
            index * COVERAGE_RENDERER_STYLE_ROW_BYTES,
        )
}

/** Stateless compatibility helper for a one-off presentation request. */
internal fun CoveragePointRenderSnapshot.boundedForPresentation(
    presentationCapacity: Int,
): CoveragePointRenderSnapshot = CoveragePresentationSelector(presentationCapacity).select(this)
