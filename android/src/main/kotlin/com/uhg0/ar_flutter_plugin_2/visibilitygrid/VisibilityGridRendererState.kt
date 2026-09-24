package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_NO_DIRECTION
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererGlyph
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererTarget
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageCommittedRow
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageCommittedRows
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRowsQualifier
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import com.uhg0.ar_flutter_plugin_2.pointcloud.identityGridRotation
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.sceneview.BoundedCoveragePresentation
import com.uhg0.ar_flutter_plugin_2.sceneview.CoveragePresentationMode
import com.uhg0.ar_flutter_plugin_2.sceneview.CoveragePresentationPage

/** Minimal immutable geometry retained after a group config's keys are consumed. */
internal class RendererGroupGeometry private constructor(
    val voxelSizeMeters: Double,
    val worldFromGroupGl: DoubleArray,
) {
    val portableBytes: Long get() = OBJECT_BYTES + TRANSFORM_BYTES

    companion object {
        private const val OBJECT_BYTES = 24L
        private const val TRANSFORM_BYTES = 144L

        fun from(config: VisibilityGridGroupConfig) = RendererGroupGeometry(
            voxelSizeMeters = config.voxelSizeMeters,
            worldFromGroupGl = config.worldFromGroupGl.copyOf(),
        )
    }
}

/** Stable renderer identity plus the canonical geometry needed for one slot. */
internal data class CanonicalRenderRow(
    val surfaceId: Long,
    val voxelKey: Long,
    val packedNormal: Int,
    val normalConfidence: Int,
    val lineageCount: Int,
) {
    init {
        require(surfaceId in 1 until 0x1_0000_0000L)
        require(normalConfidence in 0..255)
        require(lineageCount in 0..0xffff)
    }
}

/**
 * Dense, bounded renderer-row selection over authoritative native grid geometry.
 *
 * The semantic grid can retain 100k rows while this mirror retains only the
 * deterministic lowest stable identities admitted by its fixed presentation
 * capacity. Removal uses swap-remove, so rows are immediately reusable and a
 * replacement never requires a second full renderer map.
 */
class VisibilityGridRendererState(
    val capacity: Int,
    private val defaultColor: Int = 0xFFFF0000.toInt(),
    /** Production projection keeps canonical geometry/style only and borrows positions. */
    private val retainWorldPositions: Boolean = true,
    /** Normals/lineage are already represented by the worker style table. */
    private val retainCanonicalNormalMetadata: Boolean = true,
) {
    companion object {
        /** Chapter 17 coverage renderer maximum for clean centroid presentation rows. */
        const val CENTROID_PRESENTATION_CAPACITY = 20_000

        fun presentationCapacity(mode: VoxelRenderMode): Int =
            when (mode) {
                VoxelRenderMode.POINTS -> 2_000
                VoxelRenderMode.CENTROIDS -> CENTROID_PRESENTATION_CAPACITY
                VoxelRenderMode.CUBES -> 8_000
            }

        /**
         * Fixed primitive backing storage retained by one renderer state.
         * This deliberately excludes semantic-grid ownership and transient
         * snapshot hand-off buffers, which have distinct owners in the native
         * renderer ledger.
         */
        fun ownedStorageBytes(
            capacity: Int,
            retainWorldPositions: Boolean = true,
            retainCanonicalNormalMetadata: Boolean = true,
        ): Int {
            require(capacity > 0)
            return capacity * (
                Long.SIZE_BYTES * 2 +
                    (if (retainWorldPositions) POSITION_COMPONENTS * Float.SIZE_BYTES else 0) +
                    Int.SIZE_BYTES * when {
                        retainCanonicalNormalMetadata && retainWorldPositions -> 4
                        retainCanonicalNormalMetadata -> 3
                        else -> 0
                    } +
                    COVERAGE_RENDERER_STYLE_ROW_BYTES
                ) +
                LongRowIndex.ownedStorageBytes(capacity) +
                (if (retainCanonicalNormalMetadata || retainWorldPositions) {
                    SelectedKeyMaxHeap.ownedStorageBytes(capacity)
                } else {
                    // Production projection performs top-k selection in its
                    // bounded selector. It does not need a second 100k-key
                    // heap merely to maintain state admission.
                    0
                }) +
                // Dirty ranges may cover any canonical row, including rows
                // outside the current top-k cut, so this queue remains
                // source-sized even when geometry is borrowed.
                DirtyRowQueue.ownedStorageBytes(capacity)
        }

        private const val POSITION_COMPONENTS = 3
    }

    init {
        require(capacity in 1..100_000)
    }

    private val surfaceIds = LongArray(capacity)
    private val voxelKeys = LongArray(capacity)
    private val packedNormals = if (retainCanonicalNormalMetadata) IntArray(capacity) else IntArray(0)
    private val normalConfidences = if (retainCanonicalNormalMetadata) IntArray(capacity) else IntArray(0)
    private val lineageCounts = if (retainCanonicalNormalMetadata) IntArray(capacity) else IntArray(0)
    private val positions = if (retainWorldPositions) FloatArray(capacity * 3) else FloatArray(0)
    private val colors = if (retainWorldPositions) IntArray(capacity) else IntArray(0)
    private val styleRows = ByteArray(capacity * COVERAGE_RENDERER_STYLE_ROW_BYTES)
    private val rowsByIdentity = LongRowIndex(capacity)
    private val selectedIdentities = if (retainCanonicalNormalMetadata || retainWorldPositions) {
        SelectedKeyMaxHeap(capacity)
    } else {
        null
    }
    private val dirtyRows = DirtyRowQueue(capacity)
    private var group: RendererGroupGeometry? = null
    private var count = 0
    private var renderRevision = 0L
    private var geometryRevision = 0L
    private var visibilityRevision = 0L
    private var enabled = true
    private var mode = VoxelRenderMode.CENTROIDS
    private var disposed = false
    private var resetUpload = true
    private var ignoredVisibilityKeyCount = 0L
    /** Qualifiers of the currently installed canonical geometry cut. */
    private var installedOwnership: VisibilityObservationOwnership? = null
    private var installedGroupId: String? = null
    private var installedGroupGeneration = 0L
    private var installedTransactionId = 0L
    private var installedLineageRevision = 0L

    /** Revision ledger for the last accepted worker style/target cut. */
    private var semanticRevision = 0L
    private var coverageRevision = 0L
    private var styleRevision = 0L
    private var residencyRevision = 0L
    private var targetRevision = 0L
    private var targetSurfaceIdValue: Long? = null
    private var targetDirectionIndexValue: Int? = null
    private var lastStyleCut: QualifiedRendererStyleCut? = null

    val freeRowCount: Int
        @Synchronized get() = capacity - count

    val isDisposed: Boolean
        @Synchronized get() = disposed

    val currentGeometryRevision: Long
        @Synchronized get() = geometryRevision

    val currentVisibilityRevision: Long
        @Synchronized get() = visibilityRevision

    val ignoredDeletedVisibilityKeys: Long
        @Synchronized get() = ignoredVisibilityKeyCount

    internal val currentSemanticRevision: Long
        @Synchronized get() = semanticRevision

    internal val currentCoverageRevision: Long
        @Synchronized get() = coverageRevision

    internal val currentStyleRevision: Long
        @Synchronized get() = styleRevision

    internal val currentResidencyRevision: Long
        @Synchronized get() = residencyRevision

    internal val currentTargetRevision: Long
        @Synchronized get() = targetRevision

    internal val currentTargetSurfaceId: Long?
        @Synchronized get() = targetSurfaceIdValue

    internal val currentTargetDirectionIndex: Int?
        @Synchronized get() = targetDirectionIndexValue

    /**
     * Moves an already-installed canonical cut to a replacement stream binding.
     * Every durable and renderer revision must still name the exact retained
     * cut; only binding-scoped ownership and the stream-local transaction are
     * allowed to change.
     */
    @Synchronized
    internal fun canRebindRetainedCanonicalCut(
        previousOwnership: VisibilityObservationOwnership,
        nextOwnership: VisibilityObservationOwnership,
        previousTransactionId: Long,
        geometryRevision: Long,
        lineageRevision: Long,
        styleRevision: Long,
    ): Boolean = !(
        disposed || group == null || installedOwnership != previousOwnership ||
            installedTransactionId != previousTransactionId ||
            this.geometryRevision != geometryRevision ||
            installedLineageRevision != lineageRevision ||
            semanticRevision != styleRevision ||
            coverageRevision != styleRevision ||
            this.styleRevision != styleRevision ||
            residencyRevision != styleRevision ||
            targetRevision != styleRevision ||
            !previousOwnership.sameCanonicalScopeAs(nextOwnership) ||
            nextOwnership.bindingGeneration <= previousOwnership.bindingGeneration ||
            nextOwnership.lifecycleSequence <= previousOwnership.lifecycleSequence
    )

    @Synchronized
    internal fun rebindRetainedCanonicalCut(
        previousOwnership: VisibilityObservationOwnership,
        nextOwnership: VisibilityObservationOwnership,
        previousTransactionId: Long,
        geometryRevision: Long,
        lineageRevision: Long,
        styleRevision: Long,
    ): Boolean {
        if (!canRebindRetainedCanonicalCut(
                previousOwnership,
                nextOwnership,
                previousTransactionId,
                geometryRevision,
                lineageRevision,
                styleRevision,
            )
        ) return false

        installedOwnership = nextOwnership
        installedGroupId = nextOwnership.captureGroupId
        installedGroupGeneration = nextOwnership.groupGeneration
        installedTransactionId = 0L
        return true
    }

    /**
     * Borrows the canonical rows without materialising a renderer snapshot.
     * The view is intentionally created inside the synchronized section and
     * is valid only for the duration of [block].
     */
    @Synchronized
    internal fun withCommittedRows(
        expected: CoverageRowsQualifier,
        block: (CoverageCommittedRows) -> Unit,
    ): Boolean {
        if (disposed || expected.bindingGeneration != (installedOwnership?.bindingGeneration ?: 0L) ||
            expected.groupGeneration != installedGroupGeneration ||
            expected.transactionId != installedTransactionId ||
            expected.geometryRevision != geometryRevision ||
            expected.styleRevision != styleRevision
        ) return false
        val view = object : CoverageCommittedRows {
            override val count: Int get() = this@VisibilityGridRendererState.count
            override val capacity: Int get() = this@VisibilityGridRendererState.capacity
            override val qualifier: CoverageRowsQualifier = expected

            override fun rowAt(index: Int): CoverageCommittedRow {
                require(index in 0 until count)
                val position = index * POSITION_COMPONENTS
                val styleOffset = index * COVERAGE_RENDERER_STYLE_ROW_BYTES
                return CoverageCommittedRow(
                    surfaceId = surfaceIds[index],
                    key = voxelKeys[index],
                    x = positionComponent(index, 0),
                    y = positionComponent(index, 1),
                    z = positionComponent(index, 2),
                    color = colorAt(index),
                    style = CoverageRendererStyleRowV1.decode(styleRows, styleOffset),
                )
            }
        }
        block(view)
        return true
    }

    /** Concrete primitive storage retained by this production state. */
    val ownedStorageBytes: Int
        get() = ownedStorageBytes(capacity, retainWorldPositions, retainCanonicalNormalMetadata)

    internal val retainedGroupGeometryBytes: Long
        @Synchronized get() = group?.portableBytes ?: 0L

    @Synchronized
    internal fun containsSurfaceId(surfaceId: Long): Boolean = rowsByIdentity.containsKey(surfaceId)

    @Synchronized
    fun startGroup(
        config: VisibilityGridGroupConfig,
        geometryRevision: Long,
        visibilityRevision: Long = 0,
        restoredKeys: LongArray,
    ) {
        ensureActive()
        require(geometryRevision >= 0)
        require(restoredKeys.size <= config.capacity)
        require(restoredKeys.toSet().size == restoredKeys.size)
        clearRows()
        // restoredKeys are consumed into the renderer's fixed primitive arrays
        // below. Retain only the geometry needed for later row projection, not
        // the caller's full config (which can own another 20k-key copy).
        group = RendererGroupGeometry.from(config)
        this.geometryRevision = geometryRevision
        require(visibilityRevision >= 0)
        this.visibilityRevision = visibilityRevision
        this.installedOwnership = null
        this.installedGroupId = config.groupId
        this.installedGroupGeneration = config.groupGeneration.toLong()
        this.installedTransactionId = 0L
        this.installedLineageRevision = 0L
        resetStyleCutState()
        // Legacy visibility patches use this revision as their coverage
        // baseline, while the qualified style ledger starts from zero.
        coverageRevision = visibilityRevision
        ignoredVisibilityKeyCount = 0
        restoredKeys.forEach { key -> admitCandidate(key, key) }
        dirtyRows.addRange(count)
        resetUpload = true
        renderRevision++
    }

    /**
     * Rebuilds one mode-specific renderer state from a retained render cut.
     * The semantic grid remains authoritative; this only preserves the exact
     * style rows while a mesh mode releases its old bounded state.
     */
    @Synchronized
    fun rehydrate(
        config: VisibilityGridGroupConfig,
        snapshot: CoveragePointRenderSnapshot,
    ) {
        val update = requireNotNull(snapshot.update) { "Retained renderer snapshot needs an update" }
        require(snapshot.count in 0..snapshot.capacity)
        require(snapshot.keys.size == snapshot.count)
        require(snapshot.styleRows.size == snapshot.count * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        startGroup(
            config = config,
            geometryRevision = update.geometryRevision,
            visibilityRevision = update.visibilityRevision,
            restoredKeys = snapshot.keys,
        )
        snapshot.keys.indices.forEach { source ->
            val row = rowsByIdentity[snapshot.keys[source]] ?: return@forEach
            val styleOffset = source * COVERAGE_RENDERER_STYLE_ROW_BYTES
            val encoded = snapshot.styleRows.copyOfRange(
                styleOffset,
                styleOffset + COVERAGE_RENDERER_STYLE_ROW_BYTES,
            )
            encoded.copyInto(styleRows, row * COVERAGE_RENDERER_STYLE_ROW_BYTES)
            setColor(row, CoverageRendererStyleRowV1.decode(encoded).packedColor())
        }
        dirtyRows.addRange(count)
        resetUpload = true
        renderRevision++
    }

    @Synchronized
    fun applyGeometry(
        revision: Long,
        reset: Boolean,
        upsertKeys: LongArray,
        removalKeys: LongArray,
        selectedKeysForResetOrReplacement: (() -> LongArray)? = null,
    ): Boolean {
        ensureActive()
        val active = group ?: return false
        if ((!reset && revision != geometryRevision + 1) ||
            (reset && revision <= geometryRevision) ||
            upsertKeys.toSet().size != upsertKeys.size ||
            removalKeys.toSet().size != removalKeys.size ||
            upsertKeys.any(removalKeys.toSet()::contains)
        ) {
            return false
        }
        if (reset) {
            val selected = selectedKeysForResetOrReplacement?.invoke()
            if (selected != null &&
                (selected.size > capacity || selected.toSet().size != selected.size)
            ) return false
            clearRows()
            (selected ?: upsertKeys).forEach { key -> admitCandidate(key, key) }
            dirtyRows.addRange(count)
            resetUpload = true
        } else {
            val removedSelectedIdentity = removalKeys.any(rowsByIdentity::containsKey)
            if (removedSelectedIdentity) {
                // The semantic grid supplies only the first presentation-cap
                // identities. Reconciliation is bounded by this state's
                // capacity, never by the 100k semantic population.
                val selected = selectedKeysForResetOrReplacement?.invoke()
                if (selected != null) {
                    if (selected.size > capacity || selected.toSet().size != selected.size) return false
                    reconcileSelectedKeys(selected)
                    resetUpload = true
                } else {
                    // Unit/reference callers without a semantic-grid selector
                    // keep the historical delta-only free-row behavior.
                    removalKeys.forEach(::remove)
                    upsertKeys.forEach { key ->
                        if (key !in rowsByIdentity && count < capacity) append(key, key)
                    }
                }
            } else {
                upsertKeys.forEach { key ->
                    if (key in rowsByIdentity) return@forEach
                    if (count < capacity) {
                        append(key, key)
                    } else if (key < checkNotNull(largestSelectedIdentity())) {
                        remove(checkNotNull(largestSelectedIdentity()))
                        append(key, key)
                    }
                }
            }
        }
        geometryRevision = revision
        renderRevision++
        return true
    }

    /** Installs a complete V2 geometry cut keyed by stable canonical identity. */
    @Synchronized
    internal fun startCanonicalGroup(
        config: VisibilityGridGroupConfig,
        geometryRevision: Long,
        rows: List<CanonicalRenderRow>,
        ownership: VisibilityObservationOwnership? = null,
        transactionId: Long = 0L,
        lineageRevision: Long = 0L,
        preserveStyleRevisionLedger: Boolean = false,
    ) {
        ensureActive()
        require(geometryRevision >= 0)
        require(transactionId >= 0)
        require(lineageRevision >= 0)
        require(rows.size <= config.capacity)
        require(rows.map { it.surfaceId }.toSet().size == rows.size)
        clearRows()
        group = RendererGroupGeometry.from(config)
        this.geometryRevision = geometryRevision
        this.installedOwnership = ownership
        this.installedGroupId = config.groupId
        this.installedGroupGeneration = config.groupGeneration.toLong()
        this.installedTransactionId = transactionId
        this.installedLineageRevision = lineageRevision
        if (preserveStyleRevisionLedger) {
            // A same-owner canonical rebuild replaces every row, but the
            // worker's next style cut must still advance from the last
            // accepted protocol revision. The row-specific receipt and target
            // cannot survive that rebuild.
            targetSurfaceIdValue = null
            targetDirectionIndexValue = null
            lastStyleCut = null
        } else {
            resetStyleCutState()
        }
        ignoredVisibilityKeyCount = 0
        rows.forEach(::admitCandidate)
        dirtyRows.addRange(count)
        resetUpload = true
        renderRevision++
    }

    /**
     * Appends one bounded rebuild page directly into canonical state. Pages
     * are consumed while the rebuild fence is held; the renderer projection
     * must not accumulate a second source-sized row list between pages.
     */
    @Synchronized
    internal fun appendCanonicalRows(rows: List<CanonicalRenderRow>) {
        ensureActive()
        require(group != null)
        require(count + rows.size <= capacity)
        require(rows.map { it.surfaceId }.toSet().size == rows.size)
        rows.forEach(::admitCandidate)
    }

    /** Applies one V2 geometry delta without treating a voxel coordinate as identity. */
    @Synchronized
    internal fun applyGeometry(
        revision: Long,
        reset: Boolean,
        upsertRows: List<CanonicalRenderRow>,
        removalSurfaceIds: LongArray,
        ownership: VisibilityObservationOwnership? = null,
        transactionId: Long = installedTransactionId,
        lineageRevision: Long = installedLineageRevision,
    ): Boolean {
        ensureActive()
        require(transactionId >= 0)
        require(lineageRevision >= 0)
        if (group == null ||
            (!reset && revision != geometryRevision + 1) ||
            (reset && revision <= geometryRevision) ||
            upsertRows.map { it.surfaceId }.toSet().size != upsertRows.size ||
            removalSurfaceIds.toSet().size != removalSurfaceIds.size ||
            upsertRows.any { it.surfaceId in removalSurfaceIds.toSet() }
        ) return false

        if (reset) {
            clearRows()
            upsertRows.forEach(::admitCandidate)
            dirtyRows.addRange(count)
            resetUpload = true
        } else {
            removalSurfaceIds.forEach(::remove)
            upsertRows.forEach { next ->
                val existing = rowsByIdentity[next.surfaceId]
                if (existing != null) {
                    updateGeometry(existing, next)
                } else {
                    admitCandidate(next)
                }
            }
        }
        if (ownership != null) {
            installedOwnership = ownership
            installedGroupId = ownership.captureGroupId
            installedGroupGeneration = ownership.groupGeneration
        }
        installedTransactionId = transactionId
        installedLineageRevision = lineageRevision
        invalidateStyleCutAfterGeometryChange(reset)
        geometryRevision = revision
        renderRevision++
        return true
    }

    @Synchronized
    fun applyVisibility(
        namedGeometryRevision: Long,
        nextVisibilityRevision: Long,
        patchKeys: LongArray,
        patchStyleRows: ByteArray,
    ): Boolean {
        ensureActive()
        if (namedGeometryRevision != geometryRevision ||
            nextVisibilityRevision <= visibilityRevision ||
            patchStyleRows.size != patchKeys.size * COVERAGE_RENDERER_STYLE_ROW_BYTES ||
            patchKeys.toSet().size != patchKeys.size
        ) {
            return false
        }
        val decoded = Array(patchKeys.size) { index ->
            CoverageRendererStyleRowV1.decode(
                patchStyleRows,
                index * COVERAGE_RENDERER_STYLE_ROW_BYTES,
            )
        }
        if (!CoverageRendererStyleRowV1.hasCoherentGenerations(decoded.asIterable())) {
            return false
        }
        patchKeys.indices.forEach { index ->
            val row = rowsByIdentity[patchKeys[index]] ?: return@forEach
            val current = styleAt(row)
            val next = decoded[index]
            if (next.semanticGeneration < current.semanticGeneration ||
                next.styleGeneration < current.styleGeneration
            ) return false
        }
        patchKeys.indices.forEach { index ->
            val row = rowsByIdentity[patchKeys[index]]
            if (row == null) {
                ignoredVisibilityKeyCount++
                return@forEach
            }
            val next = decoded[index]
            val encoded = next.encode()
            val styleOffset = row * COVERAGE_RENDERER_STYLE_ROW_BYTES
            val color = next.packedColor()
            if (!styleRows.regionMatches(styleOffset, encoded) || colorAt(row) != color) {
                encoded.copyInto(styleRows, styleOffset)
                setColor(row, color)
                dirtyRows.add(row)
            }
        }
        visibilityRevision = nextVisibilityRevision
        renderRevision++
        return true
    }

    /**
     * Applies one complete worker style/target cut.  Validation deliberately
     * finishes before the first row, revision, dirty-span, or target mutation.
     */
    @Synchronized
    internal fun applyStyleCut(
        cut: QualifiedRendererStyleCut,
    ): RendererStyleCutResult {
        if (disposed) return RendererStyleCutResult.Rejected(RendererStyleCutRejection.CLOSED)

        // Copy mutable payloads at the seam.  The copied cut is also retained
        // as the exact replay receipt, so a caller cannot mutate replay state.
        val candidate = cut.copy(
            surfaceIds = cut.surfaceIds.copyOf(),
            styleRows = cut.styleRows.copyOf(),
        )
        if (lastStyleCut?.samePayload(candidate) == true) {
            return RendererStyleCutResult.Replayed(candidate.styleRevision)
        }

        if (group == null) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.GROUP_MISMATCH)
        }
        val expectedOwnership = installedOwnership
        if (expectedOwnership != null) {
            if (candidate.ownership != expectedOwnership) {
                return RendererStyleCutResult.Rejected(RendererStyleCutRejection.STALE_OWNERSHIP)
            }
        } else if (candidate.ownership.captureGroupId != installedGroupId ||
            candidate.ownership.groupGeneration != installedGroupGeneration
        ) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.GROUP_MISMATCH)
        }
        if (candidate.transactionId != installedTransactionId) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.TRANSACTION_MISMATCH)
        }
        if (candidate.geometryRevision != geometryRevision) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.GEOMETRY_REVISION_MISMATCH)
        }
        if (candidate.lineageRevision != installedLineageRevision) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.LINEAGE_REVISION_MISMATCH)
        }
        val revisionResult = validateStyleRevisions(candidate)
        if (revisionResult != null) {
            return RendererStyleCutResult.Rejected(revisionResult)
        }
        val rowCount = candidate.surfaceIds.size
        if (rowCount > capacity || rowCount > QUALIFIED_RENDERER_STYLE_CUT_MAX_ROWS) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.CAPACITY)
        }
        val expectedStyleBytes = try {
            Math.multiplyExact(rowCount, COVERAGE_RENDERER_STYLE_ROW_BYTES)
        } catch (_: ArithmeticException) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.MALFORMED_LENGTH)
        }
        if (candidate.styleRows.size != expectedStyleBytes) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.MALFORMED_LENGTH)
        }
        if ((candidate.targetSurfaceId == null) != (candidate.targetDirectionIndex == null)) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.TARGET_NOT_SUPPLIED)
        }
        if (candidate.targetDirectionIndex != null && candidate.targetDirectionIndex !in 0..23) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.TARGET_DIRECTION_INVALID)
        }
        if (rowCount == 0 &&
            !(candidate.reset && count == 0) &&
            !(!candidate.reset && targetSurfaceIdValue != null)
        ) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.EMPTY_CUT_NOT_ALLOWED)
        }

        var previousSurfaceId = 0L
        val decoded = arrayOfNulls<CoverageRendererStyleRowV1>(rowCount)
        for (index in 0 until rowCount) {
            val surfaceId = candidate.surfaceIds[index]
            if (surfaceId !in 1 until 0x1_0000_0000L) {
                return RendererStyleCutResult.Rejected(RendererStyleCutRejection.UNKNOWN_SURFACE_ID)
            }
            if (surfaceId == previousSurfaceId) {
                return RendererStyleCutResult.Rejected(RendererStyleCutRejection.DUPLICATE_SURFACE_ID)
            }
            if (index > 0 && surfaceId < previousSurfaceId) {
                return RendererStyleCutResult.Rejected(RendererStyleCutRejection.UNSORTED_SURFACE_IDS)
            }
            previousSurfaceId = surfaceId
            if (!rowsByIdentity.containsKey(surfaceId)) {
                return RendererStyleCutResult.Rejected(RendererStyleCutRejection.UNKNOWN_SURFACE_ID)
            }
            decoded[index] = try {
                CoverageRendererStyleRowV1.decode(
                    candidate.styleRows,
                    index * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                )
            } catch (_: IllegalArgumentException) {
                return RendererStyleCutResult.Rejected(RendererStyleCutRejection.MALFORMED_STYLE)
            }
        }
        val rows = decoded.map { requireNotNull(it) }
        if (!CoverageRendererStyleRowV1.hasCoherentGenerations(rows)) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.MIXED_STYLE_GENERATION)
        }
        if (rows.any {
                it.semanticGeneration != candidate.semanticRevision ||
                    it.styleGeneration != candidate.styleRevision
            }
        ) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.MALFORMED_STYLE)
        }
        if (candidate.targetSurfaceId != null &&
            candidate.targetSurfaceId !in candidate.surfaceIds
        ) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.TARGET_NOT_SUPPLIED)
        }
        val targetIndex = candidate.targetSurfaceId?.let(candidate.surfaceIds::indexOf)
        if (targetIndex != null) {
            val targetRow = rows[targetIndex]
            if (targetRow.target != CoverageRendererTarget.PRIMARY ||
                targetRow.directionBin != candidate.targetDirectionIndex ||
                targetRow.glyph != CoverageRendererGlyph.DESIRED_DIRECTION ||
                rows.indices.any { index ->
                    index != targetIndex && rows[index].target != CoverageRendererTarget.NONE
                }
            ) {
                return RendererStyleCutResult.Rejected(RendererStyleCutRejection.MALFORMED_STYLE)
            }
        } else if (rows.any { it.target != CoverageRendererTarget.NONE }) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.MALFORMED_STYLE)
        }
        if (candidate.reset && !hasExactlyCurrentSurfaceIds(candidate.surfaceIds)) {
            return RendererStyleCutResult.Rejected(RendererStyleCutRejection.INCOMPLETE_RESET)
        }

        // The complete cut is valid.  Only now do we touch row bytes and the
        // revision/target receipt, making every rejection byte-identical.
        var changedRows = 0
        val previousTargetSurfaceId = targetSurfaceIdValue
        if (previousTargetSurfaceId != null &&
            previousTargetSurfaceId != candidate.targetSurfaceId &&
            previousTargetSurfaceId !in candidate.surfaceIds
        ) {
            val previousTargetRow = rowsByIdentity[previousTargetSurfaceId]
            if (previousTargetRow != null) {
                val current = styleAt(previousTargetRow)
                if (current.target != CoverageRendererTarget.NONE) {
                    val cleared = current.copy(
                        semanticGeneration = candidate.semanticRevision,
                        styleGeneration = candidate.styleRevision,
                        target = CoverageRendererTarget.NONE,
                        directionBin = COVERAGE_RENDERER_NO_DIRECTION,
                        glyph = CoverageRendererGlyph.NONE,
                    )
                    val encoded = cleared.encode()
                    val styleOffset = previousTargetRow * COVERAGE_RENDERER_STYLE_ROW_BYTES
                    if (!styleRows.regionMatches(styleOffset, encoded) ||
                        colorAt(previousTargetRow) != cleared.packedColor()
                    ) {
                        encoded.copyInto(styleRows, styleOffset)
                        setColor(previousTargetRow, cleared.packedColor())
                        dirtyRows.add(previousTargetRow)
                        changedRows++
                    }
                }
            }
        }
        for (index in 0 until rowCount) {
            val row = checkNotNull(rowsByIdentity[candidate.surfaceIds[index]])
            val encodedOffset = index * COVERAGE_RENDERER_STYLE_ROW_BYTES
            val styleOffset = row * COVERAGE_RENDERER_STYLE_ROW_BYTES
            val encoded = candidate.styleRows.copyOfRange(
                encodedOffset,
                encodedOffset + COVERAGE_RENDERER_STYLE_ROW_BYTES,
            )
            val nextColor = rows[index].packedColor()
            if (!styleRows.regionMatches(styleOffset, encoded) || colorAt(row) != nextColor) {
                encoded.copyInto(styleRows, styleOffset)
                setColor(row, nextColor)
                dirtyRows.add(row)
                changedRows++
            }
        }
        if (candidate.reset) {
            dirtyRows.addRange(count)
            resetUpload = true
        }
        semanticRevision = candidate.semanticRevision
        coverageRevision = candidate.coverageRevision
        styleRevision = candidate.styleRevision
        residencyRevision = candidate.residencyRevision
        targetRevision = candidate.targetRevision
        visibilityRevision = candidate.coverageRevision
        targetSurfaceIdValue = candidate.targetSurfaceId
        targetDirectionIndexValue = candidate.targetDirectionIndex
        lastStyleCut = candidate
        renderRevision++
        return RendererStyleCutResult.Applied(
            styleRevision = candidate.styleRevision,
            targetRevision = candidate.targetRevision,
            changedRows = changedRows,
            targetSurfaceId = candidate.targetSurfaceId,
        )
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        ensureActive()
        if (enabled == value) return
        enabled = value
        renderRevision++
    }

    @Synchronized
    fun setRenderMode(value: VoxelRenderMode) {
        ensureActive()
        if (mode == value) return
        mode = value
        renderRevision++
    }

    @Synchronized
    fun snapshot(): CoveragePointRenderSnapshot {
        ensureActive()
        val snapshotKeys = voxelKeys.copyOf(count)
        check(retainWorldPositions) { "Full snapshots are a legacy adapter only" }
        val snapshotPositions = positions.copyOf(count * 3)
        val snapshotColors = colors.copyOf(count)
        val update = nextUpdate()
        return CoveragePointRenderSnapshot(
            revision = renderRevision,
            enabled = enabled,
            capacity = capacity,
            count = count,
            keys = snapshotKeys,
            surfaceIds = surfaceIds.copyOf(count),
            positions = snapshotPositions,
            colors = snapshotColors,
            styleRows = styleRows.copyOf(count * COVERAGE_RENDERER_STYLE_ROW_BYTES),
            gridRotationWorld = identityGridRotation(),
            update = update,
            bindingGeneration = installedOwnership?.bindingGeneration ?: 0L,
            groupGeneration = installedGroupGeneration,
            transactionId = installedTransactionId,
            geometryRevision = geometryRevision,
            styleRevision = styleRevision,
        )
    }

    /** Drains only range metadata for descriptor publication. */
    @Synchronized
    private fun nextUpdate(): CoveragePointRenderUpdate {
        val update = CoveragePointRenderUpdate(
            geometryRevision = geometryRevision,
            visibilityRevision = visibilityRevision,
            enabled = enabled,
            count = count,
            spans = dirtySpans().map { it.rangeOnly() },
            reset = resetUpload,
        )
        dirtyRows.clear()
        resetUpload = false
        return update
    }

    /** Drains the bounded range receipt without copying geometry/style values. */
    @Synchronized
    internal fun takePresentationUpdate(): CoveragePointRenderUpdate {
        ensureActive()
        return nextUpdate()
    }

    /**
     * Publishes only bounded presentation metadata.  Geometry/color values
     * stay in this qualifier-fenced state and are copied one <=512-row page
     * at a time by the returned descriptor.
     */
    @Synchronized
    internal fun presentationDescriptor(
        expected: CoverageRowsQualifier,
        mode: CoveragePresentationMode,
        enabled: Boolean,
        selectedSourceSlots: IntArray,
        palette: CoverageRendererPalette = CoverageRendererPalette.COVERAGE,
        paletteEpoch: Long = 0L,
        update: CoveragePointRenderUpdate? = null,
    ): BoundedCoveragePresentation? {
        if (!withCommittedRows(expected) { }) return null
        val boundedCount = minOf(selectedSourceSlots.size, mode.presentationCapacity)
        val slots = selectedSourceSlots.copyOf(boundedCount)
        if (slots.any { it !in 0 until count } || slots.toSet().size != slots.size) return null
        val ids = LongArray(boundedCount)
        val styles = ByteArray(boundedCount * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        repeat(boundedCount) { destination ->
            val source = slots[destination]
            ids[destination] = surfaceIds[source]
            styleRows.copyInto(
                styles,
                destination * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                source * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                (source + 1) * COVERAGE_RENDERER_STYLE_ROW_BYTES,
            )
            CoverageRendererStyleRowV1.decode(styles, destination * COVERAGE_RENDERER_STYLE_ROW_BYTES)
                .copy(palette = palette).encode().copyInto(
                    styles,
                    destination * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                )
        }
        val pageReader: (CoverageRowsQualifier, Int, Int) -> CoveragePresentationPage? =
            { pageExpected, start, maximum ->
                var page: CoveragePresentationPage? = null
                val accepted = withCommittedRows(pageExpected) { rows ->
                    val pageCount = minOf(maximum, boundedCount - start)
                    if (pageCount <= 0) return@withCommittedRows
                    val pageIds = LongArray(pageCount)
                    val pagePositions = FloatArray(pageCount * 3)
                    val pageColors = IntArray(pageCount)
                    val pageStyles = ByteArray(pageCount * COVERAGE_RENDERER_STYLE_ROW_BYTES)
                    repeat(pageCount) { offset ->
                        val destination = start + offset
                        val source = slots[destination]
                        val row = rows.rowAt(source)
                        pageIds[offset] = row.surfaceId
                        pagePositions[offset * 3] = row.x
                        pagePositions[offset * 3 + 1] = row.y
                        pagePositions[offset * 3 + 2] = row.z
                        styles.copyInto(
                            pageStyles,
                            offset * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                            destination * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                            (destination + 1) * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                        )
                        pageColors[offset] = CoverageRendererStyleRowV1.decode(
                            pageStyles,
                            offset * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                        ).packedColor()
                    }
                    page = CoveragePresentationPage(
                        startSlot = start,
                        totalCount = boundedCount,
                        surfaceIds = pageIds,
                        positions = pagePositions,
                        colors = pageColors,
                        styleRows = pageStyles,
                    )
                }
                page.takeIf { accepted }
            }
        return BoundedCoveragePresentation.create(
            qualifier = expected,
            mode = mode,
            enabled = enabled,
            capacity = mode.presentationCapacity,
            sourceCapacity = capacity,
            sourceCount = count,
            palette = palette,
            paletteEpoch = paletteEpoch,
            targetSurfaceId = targetSurfaceIdValue,
            targetDirectionIndex = targetDirectionIndexValue,
            selectedSurfaceIds = ids,
            selectedSourceSlots = slots,
            styleRows = styles,
            update = update ?: nextUpdate(),
            pageReader = pageReader,
        )
    }

    @Synchronized
    fun markUploadFailed() {
        ensureActive()
        resetUpload = true
        dirtyRows.addRange(count)
        renderRevision++
    }

    @Synchronized
    fun stopGroup() {
        if (disposed) return
        clearRows()
        group = null
        geometryRevision = 0
        visibilityRevision = 0
        installedOwnership = null
        installedGroupId = null
        installedGroupGeneration = 0L
        installedTransactionId = 0L
        installedLineageRevision = 0L
        resetStyleCutState()
        ignoredVisibilityKeyCount = 0
        resetUpload = true
        renderRevision++
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        stopGroup()
        disposed = true
    }

    private fun append(
        identity: Long,
        voxelKey: Long,
        packedNormal: Int = 0,
        normalConfidence: Int = 0,
        lineageCount: Int = 0,
    ) {
        check(count < capacity)
        val row = count++
        surfaceIds[row] = identity
        voxelKeys[row] = voxelKey
        if (retainCanonicalNormalMetadata) {
            packedNormals[row] = packedNormal
            normalConfidences[row] = normalConfidence
            lineageCounts[row] = lineageCount
        }
        val initialStyle = CoverageRendererStyleRowV1()
        initialStyle.encode().copyInto(styleRows, row * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        setColor(row, initialStyle.packedColor())
        writePosition(row, voxelKey)
        rowsByIdentity[identity] = row
        selectedIdentities?.add(identity, rowsByIdentity::containsKey)
        dirtyRows.add(row)
    }

    /** Deterministic bounded admission without a steady full-key sort. */
    private fun admitCandidate(identity: Long, voxelKey: Long) {
        if (rowsByIdentity.containsKey(identity)) return
        if (count < capacity) {
            append(identity, voxelKey)
            return
        }
        val largest = largestSelectedIdentity() ?: return
        if (identity < largest) {
            remove(largest)
            append(identity, voxelKey)
        }
    }

    private fun admitCandidate(row: CanonicalRenderRow) {
        val existing = rowsByIdentity[row.surfaceId]
        if (existing != null) {
            updateGeometry(existing, row)
            return
        }
        if (count < capacity) {
            append(
                row.surfaceId,
                row.voxelKey,
                row.packedNormal,
                row.normalConfidence,
                row.lineageCount,
            )
            return
        }
        val largest = largestSelectedIdentity() ?: return
        if (row.surfaceId < largest) {
            remove(largest)
            append(
                row.surfaceId,
                row.voxelKey,
                row.packedNormal,
                row.normalConfidence,
                row.lineageCount,
            )
        }
    }

    private fun updateGeometry(slot: Int, next: CanonicalRenderRow) {
        val changed = voxelKeys[slot] != next.voxelKey ||
            (retainCanonicalNormalMetadata &&
                (packedNormals[slot] != next.packedNormal ||
                    normalConfidences[slot] != next.normalConfidence ||
                    lineageCounts[slot] != next.lineageCount))
        voxelKeys[slot] = next.voxelKey
        if (retainCanonicalNormalMetadata) {
            packedNormals[slot] = next.packedNormal
            normalConfidences[slot] = next.normalConfidence
            lineageCounts[slot] = next.lineageCount
        }
        if (changed) {
            if (targetSurfaceIdValue == surfaceIds[slot]) {
                targetSurfaceIdValue = null
                targetDirectionIndexValue = null
            }
            val clearedRow = CoverageRendererStyleRowV1()
            val cleared = clearedRow.encode()
            cleared.copyInto(styleRows, slot * COVERAGE_RENDERER_STYLE_ROW_BYTES)
            setColor(slot, clearedRow.packedColor())
            writePosition(slot, next.voxelKey)
            dirtyRows.add(slot)
        }
    }

    private fun remove(identity: Long) {
        val row = rowsByIdentity.remove(identity) ?: return
        if (targetSurfaceIdValue == identity) {
            targetSurfaceIdValue = null
            targetDirectionIndexValue = null
        }
        val last = --count
        if (row != last) {
            val movedIdentity = surfaceIds[last]
            surfaceIds[row] = movedIdentity
            voxelKeys[row] = voxelKeys[last]
            if (retainCanonicalNormalMetadata) {
                packedNormals[row] = packedNormals[last]
                normalConfidences[row] = normalConfidences[last]
                lineageCounts[row] = lineageCounts[last]
            }
            setColor(row, colorAt(last))
            styleRows.copyInto(
                styleRows,
                row * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                last * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                (last + 1) * COVERAGE_RENDERER_STYLE_ROW_BYTES,
            )
            if (retainWorldPositions) {
                positions[last * 3].let { positions[row * 3] = it }
                positions[last * 3 + 1].let { positions[row * 3 + 1] = it }
                positions[last * 3 + 2].let { positions[row * 3 + 2] = it }
            }
            rowsByIdentity[movedIdentity] = row
            dirtyRows.add(row)
        }
        surfaceIds[last] = 0
        voxelKeys[last] = 0
        if (retainCanonicalNormalMetadata) {
            packedNormals[last] = 0
            normalConfidences[last] = 0
            lineageCounts[last] = 0
        }
        setColor(last, 0)
        styleRows.fill(
            0,
            last * COVERAGE_RENDERER_STYLE_ROW_BYTES,
            (last + 1) * COVERAGE_RENDERER_STYLE_ROW_BYTES,
        )
    }

    private fun positionComponent(row: Int, component: Int): Float {
        require(component in 0..2)
        if (retainWorldPositions) return positions[row * 3 + component]
        val active = checkNotNull(group)
        val coordinates = unpackVisibilityGridKey(voxelKeys[row])
        val half = active.voxelSizeMeters / 2.0
        val x = coordinates[0] * active.voxelSizeMeters + half
        val y = coordinates[1] * active.voxelSizeMeters + half
        val z = coordinates[2] * active.voxelSizeMeters + half
        val matrix = active.worldFromGroupGl
        return when (component) {
            0 -> (matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]).toFloat()
            1 -> (matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]).toFloat()
            else -> (matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]).toFloat()
        }
    }

    private fun colorAt(row: Int): Int = if (retainWorldPositions) {
        colors[row]
    } else {
        CoverageRendererStyleRowV1.decode(
            styleRows,
            row * COVERAGE_RENDERER_STYLE_ROW_BYTES,
        ).packedColor()
    }

    private fun setColor(row: Int, value: Int) {
        if (retainWorldPositions) colors[row] = value
    }

    /** Returns the largest admitted identity without a production heap. */
    private fun largestSelectedIdentity(): Long? {
        val heap = selectedIdentities
        if (heap != null) return heap.largest(rowsByIdentity::containsKey)
        if (count == 0) return null
        var largest = surfaceIds[0]
        for (row in 1 until count) largest = maxOf(largest, surfaceIds[row])
        return largest
    }

    private fun writePosition(row: Int, key: Long) {
        if (!retainWorldPositions) return
        val active = checkNotNull(group)
        val coordinates = unpackVisibilityGridKey(key)
        val half = active.voxelSizeMeters / 2.0
        val x = coordinates[0] * active.voxelSizeMeters + half
        val y = coordinates[1] * active.voxelSizeMeters + half
        val z = coordinates[2] * active.voxelSizeMeters + half
        val matrix = active.worldFromGroupGl
        positions[row * 3] =
            (matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]).toFloat()
        positions[row * 3 + 1] =
            (matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]).toFloat()
        positions[row * 3 + 2] =
            (matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]).toFloat()
    }

    private fun dirtySpans(): List<CoveragePointSpan> {
        val activeRows = dirtyRows.drainActive(count)
        if (activeRows.isEmpty()) return emptyList()
        var spanCount = 1
        for (index in 1 until activeRows.size) {
            if (activeRows[index] != activeRows[index - 1] + 1) spanCount++
        }
        // SceneViewHost retains this list across the handoff boundary. Give its
        // backing array the exact retained span count so its portable ownership
        // receipt does not depend on ArrayList's geometric growth history.
        val spans = ArrayList<CoveragePointSpan>(spanCount)
        var cursor = 0
        while (cursor < activeRows.size) {
            val start = activeRows[cursor]
            var end = start
            cursor++
            while (cursor < activeRows.size && activeRows[cursor] == end + 1) {
                end = activeRows[cursor++]
            }
            spans += if (retainWorldPositions) {
                CoveragePointSpan(
                    startSlot = start,
                    positions = positions.copyOfRange(start * 3, (end + 1) * 3),
                    colors = IntArray(end - start + 1) { offset -> colorAt(start + offset) },
                    styleRows = styleRows.copyOfRange(
                        start * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                        (end + 1) * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                    ),
                )
            } else {
                CoveragePointSpan(
                    startSlot = start,
                    positions = FloatArray(0),
                    colors = IntArray(0),
                    endSlotExclusive = end + 1,
                )
            }
        }
        return spans
    }

    private fun clearRows() {
        surfaceIds.fill(0)
        voxelKeys.fill(0)
        packedNormals.fill(0)
        normalConfidences.fill(0)
        lineageCounts.fill(0)
        if (retainWorldPositions) positions.fill(0f)
        if (retainWorldPositions) colors.fill(0)
        styleRows.fill(0)
        rowsByIdentity.clear()
        selectedIdentities?.clear()
        dirtyRows.clear()
        count = 0
    }

    private fun reconcileSelectedKeys(nextSelected: LongArray) {
        val desired = LongRowIndex(maxOf(1, nextSelected.size))
        nextSelected.forEach { key -> desired[key] = 0 }
        val removals = LongArray(count)
        var removalCount = 0
        for (row in 0 until count) {
            val key = surfaceIds[row]
            if (!desired.containsKey(key)) removals[removalCount++] = key
        }
        for (index in 0 until removalCount) remove(removals[index])
        nextSelected.forEach { key ->
            if (key !in rowsByIdentity) append(key, key)
        }
        check(count == nextSelected.size)
    }

    private fun validateStyleRevisions(
        cut: QualifiedRendererStyleCut,
    ): RendererStyleCutRejection? {
        val revisions = arrayOf(
            cut.semanticRevision,
            cut.coverageRevision,
            cut.styleRevision,
            cut.residencyRevision,
            cut.targetRevision,
        )
        val current = longArrayOf(
            semanticRevision,
            coverageRevision,
            styleRevision,
            residencyRevision,
            targetRevision,
        )
        val staleReasons = arrayOf(
            RendererStyleCutRejection.SEMANTIC_REVISION_STALE,
            RendererStyleCutRejection.COVERAGE_REVISION_STALE,
            RendererStyleCutRejection.STYLE_REVISION_STALE,
            RendererStyleCutRejection.RESIDENCY_REVISION_STALE,
            RendererStyleCutRejection.TARGET_REVISION_STALE,
        )
        revisions.indices.forEach { index ->
            if (revisions[index] < 0) return RendererStyleCutRejection.REVISION_OVERFLOW
            if (current[index] == Long.MAX_VALUE) return RendererStyleCutRejection.REVISION_OVERFLOW
            if (revisions[index] <= current[index]) return staleReasons[index]
            if (revisions[index] != current[index] + 1L) {
                return RendererStyleCutRejection.REVISION_GAP
            }
        }
        return null
    }

    private fun hasExactlyCurrentSurfaceIds(ids: LongArray): Boolean {
        if (ids.size != count) return false
        val current = surfaceIds.copyOf(count)
        current.sort()
        return current.contentEquals(ids)
    }

    private fun resetStyleCutState() {
        semanticRevision = 0L
        coverageRevision = 0L
        styleRevision = 0L
        residencyRevision = 0L
        targetRevision = 0L
        targetSurfaceIdValue = null
        targetDirectionIndexValue = null
        lastStyleCut = null
    }

    /** Geometry identity changed; an old style receipt can never be replayed. */
    private fun invalidateStyleCutAfterGeometryChange(reset: Boolean) {
        lastStyleCut = null
        if (reset) {
            targetSurfaceIdValue = null
            targetDirectionIndexValue = null
        } else if (targetSurfaceIdValue != null &&
            !rowsByIdentity.containsKey(checkNotNull(targetSurfaceIdValue))
        ) {
            targetSurfaceIdValue = null
            targetDirectionIndexValue = null
        }
    }

    private fun QualifiedRendererStyleCut.samePayload(
        other: QualifiedRendererStyleCut,
    ): Boolean = ownership == other.ownership &&
        transactionId == other.transactionId &&
        geometryRevision == other.geometryRevision &&
        lineageRevision == other.lineageRevision &&
        semanticRevision == other.semanticRevision &&
        coverageRevision == other.coverageRevision &&
        styleRevision == other.styleRevision &&
        residencyRevision == other.residencyRevision &&
        targetRevision == other.targetRevision &&
        reset == other.reset &&
        targetSurfaceId == other.targetSurfaceId &&
        targetDirectionIndex == other.targetDirectionIndex &&
        surfaceIds.contentEquals(other.surfaceIds) &&
        styleRows.contentEquals(other.styleRows)

    private fun ensureActive() {
        check(!disposed) { "VisibilityGridRendererState is disposed" }
    }

    private fun styleAt(row: Int): CoverageRendererStyleRowV1 =
        CoverageRendererStyleRowV1.decode(
            styleRows,
            row * COVERAGE_RENDERER_STYLE_ROW_BYTES,
        )

    private fun ByteArray.regionMatches(offset: Int, other: ByteArray): Boolean {
        for (index in other.indices) {
            if (this[offset + index] != other[index]) return false
        }
        return true
    }
}

private fun VisibilityObservationOwnership.sameCanonicalScopeAs(
    other: VisibilityObservationOwnership,
): Boolean = sessionId == other.sessionId &&
    sessionGeneration == other.sessionGeneration &&
    captureGroupId == other.captureGroupId &&
    groupGeneration == other.groupGeneration &&
    coverageEpoch == other.coverageEpoch &&
    arSessionIdentity == other.arSessionIdentity &&
    viewInstanceId == other.viewInstanceId &&
    viewGeneration == other.viewGeneration &&
    groupFrame == other.groupFrame

/** One snapshot retained across the explicit SceneViewHost handoff boundary. */
internal data class RendererSnapshotOwnershipReceipt(
    val snapshotObjectBytes: Long,
    val primaryArrayBytes: Long,
    val updateObjectBytes: Long,
    val spanListBytes: Long,
    val spanObjectAndArrayBytes: Long,
) {
    val portableBytes: Long get() = snapshotObjectBytes + primaryArrayBytes + updateObjectBytes +
        spanListBytes + spanObjectAndArrayBytes

    companion object {
        private fun alignedArray(payload: Long) = ((16L + payload + 7L) / 8L) * 8L
        private fun listBytes(count: Int) = if (count == 0) 0L else 24L + alignedArray(count * 4L)
        private fun arrays(rows: Int) = alignedArray(rows * Long.SIZE_BYTES.toLong()) +
            alignedArray(rows * 3L * Float.SIZE_BYTES) + alignedArray(rows * Int.SIZE_BYTES.toLong()) +
            alignedArray(rows * COVERAGE_RENDERER_STYLE_ROW_BYTES.toLong()) + alignedArray(9L * Float.SIZE_BYTES)
        private fun span(rows: Int) = 32L + alignedArray(rows * 3L * Float.SIZE_BYTES) +
            alignedArray(rows * Int.SIZE_BYTES.toLong()) +
            alignedArray(rows * COVERAGE_RENDERER_STYLE_ROW_BYTES.toLong())

        fun fullResync(rows: Int) = RendererSnapshotOwnershipReceipt(
            56L, arrays(rows), 40L, listBytes(if (rows == 0) 0 else 1),
            if (rows == 0) 0L else span(rows),
        )

        /** Worst legal sparse dirty set: alternating rows, one row per span. */
        fun maximumSparse(rows: Int): RendererSnapshotOwnershipReceipt {
            val spans = (rows + 1) / 2
            return RendererSnapshotOwnershipReceipt(
                56L, arrays(rows), 40L, listBytes(spans), spans * span(1),
            )
        }
    }
}

internal fun CoveragePointRenderSnapshot.ownershipReceipt(): RendererSnapshotOwnershipReceipt {
    val spans = update?.spans.orEmpty()
    fun alignedArray(payload: Long) = ((16L + payload + 7L) / 8L) * 8L
    val primary = alignedArray(keys.size * Long.SIZE_BYTES.toLong()) +
        alignedArray(positions.size * Float.SIZE_BYTES.toLong()) +
        alignedArray(colors.size * Int.SIZE_BYTES.toLong()) + alignedArray(styleRows.size.toLong()) +
        alignedArray(gridRotationWorld.size * Float.SIZE_BYTES.toLong())
    val list = if (spans.isEmpty()) 0L else 24L + alignedArray(spans.size * 4L)
    val spanBytes = spans.sumOf { span ->
        32L + alignedArray(span.positions.size * Float.SIZE_BYTES.toLong()) +
            alignedArray(span.colors.size * Int.SIZE_BYTES.toLong()) + alignedArray(span.styleRows.size.toLong())
    }
    return RendererSnapshotOwnershipReceipt(56L, primary, if (update == null) 0L else 40L, list, spanBytes)
}
