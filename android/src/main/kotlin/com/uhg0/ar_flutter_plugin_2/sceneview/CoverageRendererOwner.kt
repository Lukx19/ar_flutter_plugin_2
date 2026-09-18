package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererSemantic
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererGlyph
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudNativeConfig
import com.uhg0.ar_flutter_plugin_2.pointcloud.rangeOnly
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.pointcloud.deepCopyWithoutSpanValues
import com.uhg0.ar_flutter_plugin_2.pointcloud.deepCopy
import com.uhg0.ar_flutter_plugin_2.pointcloud.rewritePaletteBuffers
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_NO_DIRECTION
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageCommittedRow
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageCommittedRows
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageCommittedRowsBorrower
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRowsQualifier
import java.util.Collections

/** Stable renderer modes exposed by the V2 visibility binding. */
enum class CoveragePresentationMode(val wireName: String) {
    SEMANTIC_CENTROIDS("semanticCentroids"),
    SEMANTIC_CUBES("semanticCubes"),
    RAW_FEATURES("rawFeatures"),
    WARM_PROXIES("warmProxies"),
    OVERVIEW("overview"),
    SUPPRESSED_DEBUG("suppressedDebug"),
    ;

    val presentationCapacity: Int
        get() = when (this) {
            SEMANTIC_CENTROIDS -> CoverageRendererLimits.CENTROID_CAPACITY
            SEMANTIC_CUBES -> CoverageRendererLimits.CUBE_CAPACITY
            RAW_FEATURES -> CoverageRendererLimits.RAW_POINT_CAPACITY
            WARM_PROXIES -> CoverageRendererLimits.WARM_PROXY_CAPACITY
            OVERVIEW -> CoverageRendererLimits.COLD_OVERVIEW_CAPACITY
            SUPPRESSED_DEBUG -> CoverageRendererLimits.DEBUG_ROW_CAPACITY
        }

    companion object {
        fun fromWire(value: String): CoveragePresentationMode =
            entries.firstOrNull { it.wireName == value } ?:
                throw IllegalArgumentException("Unknown coverage presentation mode: $value")
    }
}

internal fun CoveragePresentationMode.toVoxelRenderMode(): VoxelRenderMode = when (this) {
    CoveragePresentationMode.RAW_FEATURES -> VoxelRenderMode.POINTS
    CoveragePresentationMode.SEMANTIC_CUBES -> VoxelRenderMode.CUBES
    CoveragePresentationMode.SEMANTIC_CENTROIDS,
    CoveragePresentationMode.WARM_PROXIES,
    CoveragePresentationMode.OVERVIEW,
    CoveragePresentationMode.SUPPRESSED_DEBUG,
    -> VoxelRenderMode.CENTROIDS
}

internal fun VoxelRenderMode.toDefaultCoveragePresentationMode(): CoveragePresentationMode =
    when (this) {
        VoxelRenderMode.POINTS -> CoveragePresentationMode.RAW_FEATURES
        VoxelRenderMode.CENTROIDS -> CoveragePresentationMode.SEMANTIC_CENTROIDS
        VoxelRenderMode.CUBES -> CoveragePresentationMode.SEMANTIC_CUBES
    }

typealias CoveragePalette = CoverageRendererPalette
typealias CoverageSemanticLabel = CoverageRendererSemantic
typealias CoverageLabel = CoverageRendererCoverage

internal data class CoverageScreenPoint(
    val xPx: Float,
    val yPx: Float,
    val depth: Float,
) {
    init {
        require(xPx.isFinite() && yPx.isFinite() && depth.isFinite())
    }
}

internal fun interface CoverageWorldToScreenProjection {
    fun project(x: Float, y: Float, z: Float): CoverageScreenPoint?
}

internal data class CoverageRendererControls(
    val visible: Boolean,
    val mode: CoveragePresentationMode,
    val palette: CoveragePalette,
)

/**
 * Owner-issued identity for one concrete GPU resource lifetime.
 *
 * The renderer generation is an upstream semantic qualifier.  [epoch] is
 * deliberately separate: a resource may be replaced for the same semantic
 * snapshot, and late Compose/resource callbacks must not be allowed to affect
 * the replacement.
 */
internal data class CoverageResourceToken(
    val epoch: Long,
    val sourceRendererGeneration: Long,
    val mode: CoveragePresentationMode,
) {
    init {
        require(epoch > 0L)
        require(sourceRendererGeneration >= 0L)
    }
}

/** One immutable row in the latest fully committed presentation cut. */
internal data class VisibilityRendererRow(
    val surfaceId: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val semanticLabel: CoverageSemanticLabel,
    val coverageLabel: CoverageLabel,
    val targetDirectionIndex: Int?,
    val style: CoverageRendererStyleRowV1 = CoverageRendererStyleRowV1(
        semantic = semanticLabel,
        coverage = coverageLabel,
        target = if (targetDirectionIndex == null) {
            com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererTarget.NONE
        } else {
            com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererTarget.PRIMARY
        },
        directionBin = targetDirectionIndex ?: COVERAGE_RENDERER_NO_DIRECTION,
        glyph = if (targetDirectionIndex == null) {
            com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererGlyph.NONE
        } else {
            com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererGlyph.DESIRED_DIRECTION
        },
    ),
) {
    init {
        require(surfaceId >= 0L)
        require(x.isFinite() && y.isFinite() && z.isFinite())
        require(targetDirectionIndex == null || targetDirectionIndex in 0..23)
    }
}

/**
 * One immutable, bounded presentation cut shared by mesh, hit, and telemetry
 * consumers. Selection order is authoritative: target, coverage need,
 * residency, then stable surface identity.
 */
internal class CoveragePresentationPlan(
    val mode: CoveragePresentationMode,
    val palette: CoveragePalette,
    rows: List<VisibilityRendererRow>,
    renderSnapshot: CoveragePointRenderSnapshot?,
) {
    val rows: List<VisibilityRendererRow> = Collections.unmodifiableList(
        rows.map { it.copy(style = it.style.copy()) },
    )
    val renderSnapshot: CoveragePointRenderSnapshot? = renderSnapshot?.deepCopyWithoutSpanValues()
    val residentRowCount: Int = this.rows.size
    val residentGlyphCount: Int = this.rows.count {
        it.style.glyph != com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererGlyph.NONE
    }
    val surfaceIds: List<Long> = this.rows.map { it.surfaceId }

    init {
        require(rows.map { it.surfaceId }.distinct().size == rows.size)
    }
}

internal fun compareCoverageRows(
    first: VisibilityRendererRow,
    second: VisibilityRendererRow,
): Int {
    val target = targetRank(first.style).compareTo(targetRank(second.style))
    if (target != 0) return -target
    val need = needRank(first.style).compareTo(needRank(second.style))
    if (need != 0) return -need
    val residency = residencyRank(first.style).compareTo(residencyRank(second.style))
    if (residency != 0) return -residency
    return first.surfaceId.compareTo(second.surfaceId)
}

private fun targetRank(style: CoverageRendererStyleRowV1): Int =
    style.target.code

private fun needRank(style: CoverageRendererStyleRowV1): Int =
    when (style.coverage) {
        CoverageRendererCoverage.UNCOVERED -> 2
        CoverageRendererCoverage.PARTIAL -> 1
        CoverageRendererCoverage.COMPLETE -> 0
    }

private fun residencyRank(style: CoverageRendererStyleRowV1): Int =
    when (style.residency) {
        com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererResidency.ACTIVE_L0 -> 2
        com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererResidency.WARM_L1 -> 1
        com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererResidency.COLD_L2 -> 0
    }

/**
 * Immutable, generation-qualified renderer input. It contains only the
 * latest complete cut; partial geometry/style pages are never represented.
 */
internal class VisibilityRendererSnapshot(
    val bindingGeneration: Long,
    val groupGeneration: Long,
    val rendererGeneration: Long,
    val transactionId: Long,
    val geometryRevision: Long,
    val styleRevision: Long,
    rows: List<VisibilityRendererRow>,
    renderSnapshot: CoveragePointRenderSnapshot? = null,
    val semanticRevision: Long = geometryRevision,
    val coverageRevision: Long = styleRevision,
    val residencyRevision: Long = styleRevision,
    val targetRevision: Long = styleRevision,
    val targetSurfaceId: Long? = null,
    val targetDirectionIndex: Int? = null,
    val rowCountOverride: Int? = null,
    /** Canonical source dimensions may be supplied without retaining a source snapshot. */
    val sourceCapacity: Int? = null,
    val sourceCount: Int? = null,
    val update: CoveragePointRenderUpdate? = null,
) {
    val rows: List<VisibilityRendererRow> = Collections.unmodifiableList(
        rows.map { it.copy() },
    )
    val renderSnapshot: CoveragePointRenderSnapshot? = renderSnapshot?.deepCopy()
    val rowCount: Int get() = rowCountOverride ?: rows.size

    init {
        require(bindingGeneration >= 0L)
        require(groupGeneration >= 0L)
        require(rendererGeneration >= 0L)
        require(transactionId >= 0L)
        require(geometryRevision >= 0L)
        require(styleRevision >= 0L)
        require(semanticRevision >= 0L)
        require(coverageRevision >= 0L)
        require(residencyRevision >= 0L)
        require(targetRevision >= 0L)
        require(targetSurfaceId == null || targetSurfaceId >= 0L)
        require(targetDirectionIndex == null || targetDirectionIndex in 0..23)
        require(rowCountOverride == null || rowCountOverride >= rows.size)
        require(sourceCapacity == null || sourceCapacity in 0..100_000)
        require(sourceCount == null || sourceCount >= 0)
        require(sourceCapacity == null || sourceCount == null || sourceCount <= sourceCapacity)
        require(rows.map { it.surfaceId }.distinct().size == rows.size)
    }
}

/**
 * Lightweight owner metadata for the accepted semantic cut. The complete
 * source arrays and row list are consumed during [NativeCoverageRendererOwner.install]
 * and are never retained by the owner after that call returns.
 */
internal data class CoverageRendererCutMetadata(
    val bindingGeneration: Long,
    val groupGeneration: Long,
    val rendererGeneration: Long,
    val transactionId: Long,
    val geometryRevision: Long,
    val styleRevision: Long,
    val rowCount: Int,
    val sourceCapacity: Int,
    val sourceCount: Int,
    val targetSurfaceId: Long?,
    val targetDirectionIndex: Int?,
    val update: CoveragePointRenderUpdate? = null,
) {
    init {
        require(rowCount >= 0)
        require(sourceCapacity in 0..100_000)
        require(sourceCount in 0..sourceCapacity)
    }
}

private fun VisibilityRendererSnapshot.toCutMetadata(): CoverageRendererCutMetadata {
    val source = renderSnapshot
    return CoverageRendererCutMetadata(
        bindingGeneration = bindingGeneration,
        groupGeneration = groupGeneration,
        rendererGeneration = rendererGeneration,
        transactionId = transactionId,
        geometryRevision = geometryRevision,
        styleRevision = styleRevision,
        rowCount = rowCount,
        sourceCapacity = sourceCapacity ?: source?.capacity ?: rowCount,
        sourceCount = sourceCount ?: source?.count ?: rowCount,
        targetSurfaceId = targetSurfaceId,
        targetDirectionIndex = targetDirectionIndex,
        update = (update ?: source?.update)?.rangeOnly(),
    )
}

internal data class RendererInstallReceipt(
    val installed: Boolean,
    val rendererUnavailable: Boolean,
    val rowCount: Int,
    val bindingGeneration: Long,
    val groupGeneration: Long,
    val rendererGeneration: Long,
    val geometryRevision: Long,
    val styleRevision: Long,
    val stale: Boolean = false,
    val replayed: Boolean = false,
)

internal data class RendererControlReceipt(
    val accepted: Boolean,
    val rendererUnavailable: Boolean,
    val visible: Boolean,
    val mode: CoveragePresentationMode,
    val palette: CoveragePalette,
    val rowCount: Int,
    val rendererGeneration: Long,
)

internal data class RendererRecoveryReceipt(
    val recovered: Boolean,
    val rendererUnavailable: Boolean,
    val rehydrated: Boolean,
    val rowCount: Int,
    val rendererGeneration: Long,
    val geometryRevision: Long,
    val styleRevision: Long,
)

internal data class CoverageRendererStatus(
    val rendererUnavailable: Boolean,
    val visible: Boolean,
    val mode: CoveragePresentationMode,
    val palette: CoveragePalette,
    val rowCount: Int,
    val selectedRowCount: Int,
    val rendererGeneration: Long,
    val geometryRevision: Long,
    val styleRevision: Long,
    val residentRowCount: Int = 0,
    val residentGlyphCount: Int = 0,
    val resourceAvailable: Boolean = true,
    val recoveryPending: Boolean = false,
    val resourceFailureCount: Int = 0,
    val lowMemoryPressure: Boolean = false,
)

internal data class CoverageHitResult(
    val surfaceId: Long,
    val semanticLabel: CoverageSemanticLabel,
    val coverageLabel: CoverageLabel,
    val targetDirectionIndex: Int?,
    val geometryRevision: Long,
    val styleRevision: Long,
)

internal sealed interface CoverageHitReceipt {
    data class Hit(val result: CoverageHitResult) : CoverageHitReceipt
    data object Miss : CoverageHitReceipt
    data class Stale(
        val expectedGeometryRevision: Long,
        val actualGeometryRevision: Long,
        val expectedStyleRevision: Long,
        val actualStyleRevision: Long,
    ) : CoverageHitReceipt
}

internal interface CoverageRendererOwner {
    fun install(snapshot: VisibilityRendererSnapshot): RendererInstallReceipt
    fun setControls(controls: CoverageRendererControls): RendererControlReceipt
    fun hitTest(xPx: Float, yPx: Float): CoverageHitResult?
    fun pause()
    fun resume(): RendererRecoveryReceipt
    fun dispose()

    fun hitTestReceipt(
        xPx: Float,
        yPx: Float,
        expectedGeometryRevision: Long?,
        expectedStyleRevision: Long?,
    ): CoverageHitReceipt = hitTest(xPx, yPx)?.let { CoverageHitReceipt.Hit(it) }
        ?: CoverageHitReceipt.Miss

    fun status(): CoverageRendererStatus = CoverageRendererStatus(
        rendererUnavailable = false,
        visible = true,
        mode = CoveragePresentationMode.SEMANTIC_CENTROIDS,
        palette = CoverageRendererPalette.COVERAGE,
        rowCount = 0,
        selectedRowCount = 0,
        rendererGeneration = 0L,
        geometryRevision = 0L,
        styleRevision = 0L,
    )
}

/**
 * Host-owned V2 renderer owner. Resource creation is deliberately abstracted
 * behind this stateful owner: the canonical cut remains available while the
 * GPU mount is paused or being replaced.
 */
internal class NativeCoverageRendererOwner(
    private val onControlsChanged: (CoverageRendererControls) -> Boolean = { true },
    private val onPresentationChanged: (CoveragePointRenderSnapshot?, CoveragePresentationMode, CoverageResourceToken?) -> Unit = { _, _, _ -> },
    private val onPresentationDescriptorChanged: (BoundedCoveragePresentation?, CoveragePresentationMode, CoverageResourceToken?) -> Unit = { _, _, _ -> },
    private val onResourceLifecycleChanged: (CoverageResourceToken, Boolean) -> Unit = { _, _ -> },
    private val worldToScreen: CoverageWorldToScreenProjection =
        CoverageWorldToScreenProjection { x, y, _ -> CoverageScreenPoint(x, y, 1f) },
    private val committedRowsBorrower: CoverageCommittedRowsBorrower? = null,
) : CoverageRendererOwner {
    private var latest: CoverageRendererCutMetadata? = null
    private var controls = CoverageRendererControls(
        visible = true,
        mode = CoveragePresentationMode.SEMANTIC_CENTROIDS,
        palette = CoverageRendererPalette.COVERAGE,
    )
    private var unavailable = false
    private var disposed = false
    private var recoveryPending = false
    private var lowMemoryPressure = false
    private var lifecyclePaused = false
    private var controlsConfigured = false
    // Pure owner tests and the pre-Compose owner seam start healthy; the host
    // replaces this with concrete mount/upload receipts as resources appear.
    private var resourceMounted = true
    private var resourceFailureCount = 0
    private var paletteRevision = 0L
    private var lastAcceptedQualifier: InstallQualifier? = null
    private var presentationPlan: CoveragePresentationPlan? = null
    private var latestPresentationDescriptor: PresentationDescriptor? = null
    private val presentationSelector =
        CoveragePresentationSelector(CoverageRendererLimits.CENTROID_CAPACITY)
    private var selectorEpoch: SelectorEpoch? = null
    private var resourceEpoch = 0L
    private var resourceToken: CoverageResourceToken? = null

    @Synchronized
    fun attachCommittedRowsBorrower(value: CoverageCommittedRowsBorrower) {
        // Projection is created after SceneViewHost. Attaching once keeps the
        // owner as the only lifecycle seam without retaining a source snapshot.
        check(borrower == null) { "Committed rows borrower already attached" }
        borrower = value
    }

    private var borrower: CoverageCommittedRowsBorrower? = committedRowsBorrower

    private data class SelectorEpoch(
        val bindingGeneration: Long,
        val groupGeneration: Long,
        val rendererGeneration: Long,
        val mode: CoveragePresentationMode,
    )

    private data class InstallQualifier(
        val bindingGeneration: Long,
        val groupGeneration: Long,
        val rendererGeneration: Long,
        val transactionId: Long,
        val geometryRevision: Long,
        val styleRevision: Long,
    ) : Comparable<InstallQualifier> {
        override fun compareTo(other: InstallQualifier): Int {
            listOf(
                bindingGeneration,
                groupGeneration,
                transactionId,
                geometryRevision,
                styleRevision,
                rendererGeneration,
            ).zip(
                listOf(
                    other.bindingGeneration,
                    other.groupGeneration,
                    other.transactionId,
                    other.geometryRevision,
                    other.styleRevision,
                    other.rendererGeneration,
                ),
            ).forEach { (left, right) ->
                left.compareTo(right).takeIf { it != 0 }?.let { return it }
            }
            return 0
        }
    }

    private fun VisibilityRendererSnapshot.qualifier() = InstallQualifier(
        bindingGeneration,
        groupGeneration,
        rendererGeneration,
        transactionId,
        geometryRevision,
        styleRevision,
    )

    private fun CoverageRendererCutMetadata.qualifier(): CoverageRowsQualifier =
        CoverageRowsQualifier(
            bindingGeneration = bindingGeneration,
            groupGeneration = groupGeneration,
            rendererGeneration = rendererGeneration,
            transactionId = transactionId,
            geometryRevision = geometryRevision,
            styleRevision = styleRevision,
        )

    /**
     * Stream one transient canonical cut through the bounded selector. The
     * borrower callback is fenced by authoritative state and the returned
     * snapshot contains only selected presentation rows (at most 20k).
     */
    private fun borrowPresentationSnapshot(
        forceReset: Boolean,
        fullPaletteRecolor: Boolean = false,
    ): CoveragePointRenderSnapshot? {
        val current = latest ?: return null
        var borrowed: CoveragePointRenderSnapshot? = null
        val accepted = borrower?.withCommittedRows(current.qualifier()) { rows ->
            borrowed = presentationSelector
                .select(
                    rows = rows,
                    requestedCapacity = controls.mode.presentationCapacity,
                    forceReset = forceReset,
                    // The install/resync fence carries reset semantics in
                    // forceReset. Do not replay an old reset flag on every
                    // later status/presentation borrow.
                    sourceUpdate = current.update?.copy(reset = false),
                    enabled = controls.visible,
                )
                .rewritePaletteBuffers(
                    palette = controls.palette,
                    fullSpanOnPaletteChange = fullPaletteRecolor,
                )
                .copy(paletteRevision = paletteRevision)
        } ?: false
        if (accepted) return borrowed
        return null
    }

    @Synchronized
    override fun install(snapshot: VisibilityRendererSnapshot): RendererInstallReceipt {
        if (disposed) {
            return RendererInstallReceipt(
                installed = false,
                rendererUnavailable = true,
                rowCount = 0,
                bindingGeneration = snapshot.bindingGeneration,
                groupGeneration = snapshot.groupGeneration,
                rendererGeneration = snapshot.rendererGeneration,
                geometryRevision = snapshot.geometryRevision,
                styleRevision = snapshot.styleRevision,
            )
        }
        val qualifier = snapshot.qualifier()
        val priorQualifier = lastAcceptedQualifier
        if (priorQualifier != null && qualifier < priorQualifier) {
            val current = latest
            return RendererInstallReceipt(
                installed = false,
                rendererUnavailable = unavailable,
                rowCount = current?.let { selectedRows().size } ?: 0,
                bindingGeneration = current?.bindingGeneration ?: snapshot.bindingGeneration,
                groupGeneration = current?.groupGeneration ?: snapshot.groupGeneration,
                rendererGeneration = current?.rendererGeneration ?: snapshot.rendererGeneration,
                geometryRevision = current?.geometryRevision ?: snapshot.geometryRevision,
                styleRevision = current?.styleRevision ?: snapshot.styleRevision,
                stale = true,
            )
        }
        val replayed = priorQualifier == qualifier
        latest = snapshot.toCutMetadata()
        latestPresentationDescriptor = null
        lastAcceptedQualifier = qualifier
        val source = snapshot.renderSnapshot
        // A host snapshot may intentionally omit its source buffers because
        // canonical rows are borrowed from the projection. Only an explicit
        // reset fences the retained selector; a missing payload is an ordinary
        // range-qualified install.
        val explicitResync = (snapshot.update ?: source?.update)?.reset ?: false
        recomputePresentationPlan(
            sourceOverride = source,
            rowsOverride = snapshot.rows,
            explicitResync = explicitResync,
        )
        val mounted = !unavailable && resourceMounted
        return RendererInstallReceipt(
            installed = mounted,
            rendererUnavailable = unavailable,
            rowCount = selectedRows().size,
            bindingGeneration = snapshot.bindingGeneration,
            groupGeneration = snapshot.groupGeneration,
            rendererGeneration = snapshot.rendererGeneration,
            geometryRevision = snapshot.geometryRevision,
            styleRevision = snapshot.styleRevision,
            replayed = replayed,
        )
    }

    /** Installs projection metadata without taking a source-sized snapshot. */
    @Synchronized
    fun installPresentation(
        descriptor: BoundedCoveragePresentation,
        config: PointCloudNativeConfig,
    ): RendererInstallReceipt {
        if (disposed) {
            return RendererInstallReceipt(
                installed = false,
                rendererUnavailable = true,
                rowCount = 0,
                bindingGeneration = descriptor.qualifier.bindingGeneration,
                groupGeneration = descriptor.qualifier.groupGeneration,
                rendererGeneration = config.rendererGeneration,
                geometryRevision = descriptor.qualifier.geometryRevision,
                styleRevision = descriptor.qualifier.styleRevision,
            )
        }
        val qualifier = InstallQualifier(
            descriptor.qualifier.bindingGeneration,
            descriptor.qualifier.groupGeneration,
            config.rendererGeneration,
            descriptor.qualifier.transactionId,
            descriptor.qualifier.geometryRevision,
            descriptor.qualifier.styleRevision,
        )
        val priorQualifier = lastAcceptedQualifier
        if (priorQualifier != null && qualifier < priorQualifier) {
            val current = latest
            return RendererInstallReceipt(
                installed = false,
                rendererUnavailable = unavailable,
                rowCount = latestPresentationDescriptor?.count ?: 0,
                bindingGeneration = current?.bindingGeneration ?: descriptor.qualifier.bindingGeneration,
                groupGeneration = current?.groupGeneration ?: descriptor.qualifier.groupGeneration,
                rendererGeneration = current?.rendererGeneration ?: config.rendererGeneration,
                geometryRevision = current?.geometryRevision ?: descriptor.qualifier.geometryRevision,
                styleRevision = current?.styleRevision ?: descriptor.qualifier.styleRevision,
                stale = true,
            )
        }
        val replayed = priorQualifier == qualifier
        val presentation = descriptor
            .forMode(controls.mode, enabled = controls.visible)
            .recolor(controls.palette, paletteRevision, fullRange = false)
        latest = CoverageRendererCutMetadata(
            bindingGeneration = descriptor.qualifier.bindingGeneration,
            groupGeneration = descriptor.qualifier.groupGeneration,
            rendererGeneration = config.rendererGeneration,
            transactionId = descriptor.qualifier.transactionId,
            geometryRevision = descriptor.qualifier.geometryRevision,
            styleRevision = descriptor.qualifier.styleRevision,
            rowCount = descriptor.sourceCount,
            sourceCapacity = descriptor.sourceCapacity,
            sourceCount = descriptor.sourceCount,
            targetSurfaceId = descriptor.targetSurfaceId,
            targetDirectionIndex = descriptor.targetDirectionIndex,
            update = descriptor.update,
        )
        lastAcceptedQualifier = qualifier
        latestPresentationDescriptor = presentation
        presentationPlan = null
        return RendererInstallReceipt(
            installed = !unavailable && resourceMounted,
            rendererUnavailable = unavailable,
            rowCount = presentation.count,
            bindingGeneration = descriptor.qualifier.bindingGeneration,
            groupGeneration = descriptor.qualifier.groupGeneration,
            rendererGeneration = config.rendererGeneration,
            geometryRevision = descriptor.qualifier.geometryRevision,
            styleRevision = descriptor.qualifier.styleRevision,
            replayed = replayed,
        )
    }

    @Synchronized
    override fun setControls(controls: CoverageRendererControls): RendererControlReceipt {
        if (disposed) {
            return RendererControlReceipt(
                accepted = false,
                rendererUnavailable = true,
                visible = controls.visible,
                mode = controls.mode,
                palette = controls.palette,
                rowCount = 0,
                rendererGeneration = 0L,
            )
        }
        val modeChanged = this.controls.mode != controls.mode
        if (modeChanged && borrower != null) {
            // Admission of a new presentation mode is transactional: prove
            // the qualifier-matched canonical source is borrowable before
            // changing controls or resetting the retained selector.
            val current = latest
            var sourceAvailable = false
            val accepted = current?.let {
                borrower?.withCommittedRows(it.qualifier()) { _ ->
                    sourceAvailable = true
                }
            } ?: false
            if (!accepted || !sourceAvailable) {
                return RendererControlReceipt(
                    accepted = false,
                    rendererUnavailable = unavailable,
                    visible = this.controls.visible,
                    mode = this.controls.mode,
                    palette = this.controls.palette,
                    rowCount = current?.let { selectedRows().size } ?: 0,
                    rendererGeneration = current?.rendererGeneration ?: 0L,
                )
            }
        }
        if (!onControlsChanged(controls)) {
            val current = latest
            return RendererControlReceipt(
                accepted = false,
                rendererUnavailable = unavailable,
                visible = this.controls.visible,
                mode = this.controls.mode,
                palette = this.controls.palette,
                rowCount = current?.let { selectedRows().size } ?: 0,
                rendererGeneration = current?.rendererGeneration ?: 0L,
            )
        }
        val paletteOnlyChange = this.controls.visible == controls.visible &&
            this.controls.mode == controls.mode &&
            this.controls.palette != controls.palette
        if (this.controls.palette != controls.palette) {
            paletteRevision++
        }
        this.controls = controls
        if (modeChanged) resourceToken = null
        controlsConfigured = true
        recomputePresentationPlan(
            explicitResync = modeChanged,
            fullPaletteRecolor = paletteOnlyChange,
        )
        notifyPresentationChanged()
        val snapshot = latest
        return RendererControlReceipt(
            accepted = !unavailable && resourceMounted,
            rendererUnavailable = unavailable,
            visible = controls.visible,
            mode = controls.mode,
            palette = controls.palette,
            rowCount = snapshot?.let { selectedRows().size } ?: 0,
            rendererGeneration = snapshot?.rendererGeneration ?: 0L,
        )
    }

    @Synchronized
    override fun hitTest(xPx: Float, yPx: Float): CoverageHitResult? =
        when (val receipt = hitTestReceipt(xPx, yPx, null, null)) {
            is CoverageHitReceipt.Hit -> receipt.result
            CoverageHitReceipt.Miss, is CoverageHitReceipt.Stale -> null
        }

    @Synchronized
    override fun hitTestReceipt(
        xPx: Float,
        yPx: Float,
        expectedGeometryRevision: Long?,
        expectedStyleRevision: Long?,
    ): CoverageHitReceipt {
        val snapshot = latest ?: return CoverageHitReceipt.Miss
        if ((expectedGeometryRevision != null &&
                expectedGeometryRevision != snapshot.geometryRevision) ||
            (expectedStyleRevision != null &&
                expectedStyleRevision != snapshot.styleRevision)
        ) {
            return CoverageHitReceipt.Stale(
                expectedGeometryRevision ?: snapshot.geometryRevision,
                snapshot.geometryRevision,
                expectedStyleRevision ?: snapshot.styleRevision,
                snapshot.styleRevision,
            )
        }
        if (unavailable || !controls.visible || controls.mode == CoveragePresentationMode.SUPPRESSED_DEBUG) {
            return CoverageHitReceipt.Miss
        }
        var best: Triple<VisibilityRendererRow, Float, Float>? = null
        fun consider(row: VisibilityRendererRow) {
            val projected = worldToScreen.project(row.x, row.y, row.z) ?: return
            if (projected.depth <= 0f) return
            val dx = projected.xPx - xPx
            val dy = projected.yPx - yPx
            val distanceSquared = dx * dx + dy * dy
            if (!distanceSquared.isFinite() || distanceSquared > HIT_RADIUS_PX * HIT_RADIUS_PX) {
                return
            }
            val candidate = Triple(row, distanceSquared, projected.depth)
            val current = best
            if (current == null || compareHitCandidates(candidate, current) < 0) best = candidate
        }
        val descriptor = latestPresentationDescriptor
        if (descriptor != null) {
            var start = 0
            var accepted = true
            while (start < descriptor.count) {
                val pageStart = start
                accepted = descriptor.withPage(snapshot.qualifier(), pageStart, 512) { page ->
                    repeat(page.count) { index ->
                        val style = CoverageRendererStyleRowV1.decode(
                            page.styleRows,
                            index * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                        )
                        val position = index * 3
                        consider(
                            VisibilityRendererRow(
                                surfaceId = page.surfaceIds[index],
                                x = page.positions[position],
                                y = page.positions[position + 1],
                                z = page.positions[position + 2],
                                semanticLabel = style.semantic,
                                coverageLabel = style.coverage,
                                targetDirectionIndex = style.directionBin.takeUnless {
                                    it == COVERAGE_RENDERER_NO_DIRECTION
                                },
                                style = style,
                            ),
                        )
                    }
                }
                if (!accepted) break
                start += minOf(512, descriptor.count - start)
            }
            if (!accepted) return CoverageHitReceipt.Miss
        }
        val borrowed = borrower?.withCommittedRows(snapshot.qualifier()) { rows ->
            if (descriptor != null) return@withCommittedRows
            repeat(presentationSelector.selectedCount()) { destination ->
                val sourceSlot = presentationSelector.selectedSourceSlot(destination)
                if (sourceSlot in 0 until rows.count) {
                    consider(rows.rowAt(sourceSlot).toVisibilityRendererRow(controls.palette))
                }
            }
        } ?: false
        if (!borrowed) selectedRows().forEach(::consider)
        val row = best?.first ?: return CoverageHitReceipt.Miss
        return CoverageHitReceipt.Hit(
            CoverageHitResult(
                surfaceId = row.surfaceId,
                semanticLabel = row.semanticLabel,
                coverageLabel = row.coverageLabel,
                targetDirectionIndex = row.targetDirectionIndex,
                geometryRevision = snapshot.geometryRevision,
                styleRevision = snapshot.styleRevision,
            ),
        )
    }

    @Synchronized
    override fun pause() {
        if (disposed) return
        resetPresentationSelector()
        lifecyclePaused = true
        unavailable = true
        recoveryPending = true
    }

    @Synchronized
    override fun resume(): RendererRecoveryReceipt {
        if (disposed) {
            return RendererRecoveryReceipt(false, true, false, 0, 0L, 0L, 0L)
        }
        if (!unavailable || !recoveryPending) {
            val current = latest
            return RendererRecoveryReceipt(
                recovered = false,
                rendererUnavailable = unavailable,
                rehydrated = false,
                rowCount = current?.let { selectedRows().size } ?: 0,
                rendererGeneration = current?.rendererGeneration ?: 0L,
                geometryRevision = current?.geometryRevision ?: 0L,
                styleRevision = current?.styleRevision ?: 0L,
            )
        }
        val current = latest
        resetPresentationSelector()
        lifecyclePaused = false
        unavailable = !resourceMounted
        recoveryPending = !resourceMounted
        return RendererRecoveryReceipt(
            // A recovery receipt means the latest committed cut is eligible
            // for a fresh resource generation. Mount/upload availability is
            // reported separately when that generation actually succeeds.
            recovered = current != null,
            rendererUnavailable = unavailable,
            rehydrated = resourceMounted && current != null,
            rowCount = current?.let { selectedRows().size } ?: 0,
            rendererGeneration = current?.rendererGeneration ?: 0L,
            geometryRevision = current?.geometryRevision ?: 0L,
            styleRevision = current?.styleRevision ?: 0L,
        )
    }

    @Synchronized
    override fun dispose() {
        disposed = true
        lifecyclePaused = true
        unavailable = true
        recoveryPending = false
        latest = null
        presentationPlan = null
        latestPresentationDescriptor = null
        resetPresentationSelector()
        resourceMounted = false
    }

    /** Called only after the concrete SceneView resource has mounted. */
    @Synchronized
    fun markResourceMounted(rendererGeneration: Long): Boolean {
        if (disposed || latest?.rendererGeneration != rendererGeneration) return false
        resourceMounted = true
        unavailable = lifecyclePaused
        recoveryPending = lifecyclePaused
        return true
    }

    /** Returns the stable token for the current semantic source and mode. */
    @Synchronized
    fun issueResourceToken(): CoverageResourceToken? {
        val current = latest ?: return null
        if (disposed) return null
        val existing = resourceToken
        if (existing != null &&
            existing.sourceRendererGeneration == current.rendererGeneration &&
            existing.mode == controls.mode
        ) {
            return existing
        }
        return createResourceToken(current.rendererGeneration, controls.mode)
    }

    /** Forces a new owner lifetime without changing the semantic source cut. */
    @Synchronized
    fun requestResourceReplacement(): CoverageResourceToken? {
        val current = latest ?: return null
        if (disposed) return null
        return createResourceToken(current.rendererGeneration, controls.mode)
    }

    @Synchronized
    fun currentResourceToken(): CoverageResourceToken? = resourceToken

    @Synchronized
    fun acceptsResourceToken(token: CoverageResourceToken): Boolean =
        !disposed && resourceToken == token && latest?.rendererGeneration == token.sourceRendererGeneration &&
            controls.mode == token.mode

    @Synchronized
    fun markResourceMounted(token: CoverageResourceToken): Boolean {
        if (!acceptsResourceToken(token)) return false
        resourceMounted = true
        unavailable = lifecyclePaused
        recoveryPending = lifecyclePaused
        lowMemoryPressure = false
        onResourceLifecycleChanged(token, true)
        return true
    }

    /**
     * Fences only renderer resources under OS low-memory pressure. The latest
     * semantic cut and controls remain available for a later resume/remount.
     */
    @Synchronized
    fun markLowMemoryPressure(): Boolean {
        if (disposed) return false
        resetPresentationSelector()
        resourceMounted = false
        unavailable = true
        recoveryPending = true
        lowMemoryPressure = true
        resourceFailureCount++
        resourceToken?.let { onResourceLifecycleChanged(it, false) }
        return true
    }

    /** Keeps semantic state intact while exposing a renderer-only failure. */
    @Synchronized
    fun markResourceFailure(rendererGeneration: Long): Boolean {
        if (disposed || latest?.rendererGeneration != rendererGeneration) return false
        resetPresentationSelector()
        resourceMounted = false
        unavailable = true
        recoveryPending = true
        lowMemoryPressure = false
        resourceFailureCount++
        return true
    }

    @Synchronized
    fun markResourceFailure(token: CoverageResourceToken): Boolean {
        if (!acceptsResourceToken(token)) return false
        resetPresentationSelector()
        resourceMounted = false
        unavailable = true
        recoveryPending = true
        lowMemoryPressure = false
        resourceFailureCount++
        onResourceLifecycleChanged(token, false)
        return true
    }

    @Synchronized
    fun markUploadFailure(rendererGeneration: Long): Boolean =
        markResourceFailure(rendererGeneration)

    @Synchronized
    fun markUploadFailure(token: CoverageResourceToken): Boolean =
        markResourceFailure(token)

    /**
     * Clears resource state only for the still-current lifetime.  A late
     * release from an outgoing resource therefore cannot clear its successor.
     */
    @Synchronized
    fun markResourceReleased(token: CoverageResourceToken): Boolean {
        if (!acceptsResourceToken(token)) return false
        resourceMounted = false
        unavailable = true
        recoveryPending = true
        lowMemoryPressure = false
        onResourceLifecycleChanged(token, false)
        return true
    }

    /** Restores the still-mounted lifetime after a coexistence allocation fails. */
    @Synchronized
    fun restoreResourceToken(token: CoverageResourceToken): Boolean {
        if (disposed || latest?.rendererGeneration != token.sourceRendererGeneration ||
            controls.mode != token.mode
        ) return false
        resourceToken = token
        resourceMounted = true
        unavailable = lifecyclePaused
        recoveryPending = lifecyclePaused
        lowMemoryPressure = false
        onResourceLifecycleChanged(token, true)
        notifyPresentationChanged()
        return true
    }

    @Synchronized
    fun controlsConfigured(): Boolean = controlsConfigured

    @Synchronized
    fun snapshot(): CoverageRendererCutMetadata? = latest

    @Synchronized
    fun presentationSnapshot(): CoveragePointRenderSnapshot? {
        latestPresentationDescriptor?.let { return it.toLegacySnapshot() }
        if (borrower != null) {
            return borrowPresentationSnapshot(forceReset = false)
        }
        return presentationPlan?.renderSnapshot
    }

    /**
     * Returns only the canonical source dimensions needed for resource
     * admission. The selector never retains source values or update spans;
     * those are read from the incoming cut and the single bounded plan.
     */
    @Synchronized
    fun sourceCapacity(): Int? = latest?.sourceCapacity

    @Synchronized
    fun sourceRowCount(): Int? = latest?.sourceCount

    /** Exact selector charge for the currently retained presentation cut. */
    @Synchronized
    fun presentationStorageBytes(): Int? =
        latest?.let { presentationSelector.ownedStorageBytes.takeIf { bytes -> bytes > 0 } }

    /** Exact selector charge expected after a mode cut, including source high-water. */
    @Synchronized
    fun presentationStorageBytesFor(mode: CoveragePresentationMode): Int? {
        val current = latest ?: return null
        return presentationSelector.ownedStorageBytesFor(
            requestedCapacity = mode.presentationCapacity,
            sourceCapacity = minOf(current.sourceCapacity, current.sourceCount),
        )
    }

    @Synchronized
    fun presentationPlan(): CoveragePresentationPlan? = presentationPlan

    @Synchronized
    fun presentationDescriptor(): BoundedCoveragePresentation? = latestPresentationDescriptor

    /** Compact selected identity receipt; source values remain in canonical state. */
    @Synchronized
    fun selectedSurfaceIds(): LongArray {
        latestPresentationDescriptor?.let { return it.selectedSurfaceIds }
        presentationPlan?.let { return it.surfaceIds.toLongArray() }
        return LongArray(presentationSelector.selectedCount()) { destination ->
            presentationSelector.selectedSurfaceId(destination)
        }
    }

    @Synchronized
    fun refreshPresentation() {
        notifyPresentationChanged()
    }

    @Synchronized
    fun clearLatest() {
        latest = null
        presentationPlan = null
        latestPresentationDescriptor = null
        resetPresentationSelector()
        resourceToken = null
        lowMemoryPressure = false
    }

    @Synchronized
    override fun status(): CoverageRendererStatus {
        val current = latest
        val plan = presentationPlan
        val selected = selectedRows()
        val descriptor = latestPresentationDescriptor
        val selectedCount = descriptor?.count ?: current?.let { selected.size } ?: 0
        val descriptorGlyphCount = descriptor?.let { descriptorValue ->
            (0 until descriptorValue.count).count { index ->
                CoverageRendererStyleRowV1.decode(
                    descriptorValue.styleRows,
                    index * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                ).glyph != CoverageRendererGlyph.NONE
            }
        } ?: 0
        return CoverageRendererStatus(
            rendererUnavailable = unavailable,
            visible = controls.visible,
            mode = controls.mode,
            palette = controls.palette,
            rowCount = current?.rowCount ?: 0,
            selectedRowCount = selectedCount,
            rendererGeneration = current?.rendererGeneration ?: 0L,
            geometryRevision = current?.geometryRevision ?: 0L,
            styleRevision = current?.styleRevision ?: 0L,
            residentRowCount = descriptor?.count ?: plan?.residentRowCount ?: selected.size,
            residentGlyphCount = descriptor?.let { descriptorGlyphCount } ?: plan?.residentGlyphCount ?: selected.count {
                it.style.glyph != CoverageRendererGlyph.NONE
            },
            resourceAvailable = resourceMounted,
            recoveryPending = recoveryPending,
            resourceFailureCount = resourceFailureCount,
            lowMemoryPressure = lowMemoryPressure,
        )
    }

    private fun selectedRows(): List<VisibilityRendererRow> {
        if (latestPresentationDescriptor != null) return emptyList()
        presentationPlan?.let { return it.rows }
        val current = latest
        if (current != null && borrower != null) {
            val rows = ArrayList<VisibilityRendererRow>(presentationSelector.selectedCount())
            val accepted = borrower?.withCommittedRows(current.qualifier()) { borrowedRows ->
                repeat(presentationSelector.selectedCount()) { destination ->
                    val sourceSlot = presentationSelector.selectedSourceSlot(destination)
                    if (sourceSlot in 0 until borrowedRows.count) {
                        rows += borrowedRows.rowAt(sourceSlot).toVisibilityRendererRow(controls.palette)
                    }
                }
            } ?: false
            if (accepted) return rows
        }
        return emptyList()
    }

    private fun compareHitCandidates(
        first: Triple<VisibilityRendererRow, Float, Float>,
        second: Triple<VisibilityRendererRow, Float, Float>,
    ): Int = compareValuesBy(first, second, { it.second }, { it.third }, { it.first.surfaceId })

    private fun CoverageCommittedRow.toVisibilityRendererRow(
        palette: CoveragePalette,
    ): VisibilityRendererRow = VisibilityRendererRow(
        surfaceId = surfaceId,
        x = x,
        y = y,
        z = z,
        semanticLabel = style.semantic,
        coverageLabel = style.coverage,
        targetDirectionIndex = style.directionBin.takeUnless {
            it == COVERAGE_RENDERER_NO_DIRECTION
        },
        style = style.copy(palette = palette),
    )

    private fun notifyPresentationChanged() {
        if (unavailable || disposed) return
        latestPresentationDescriptor?.let {
            onPresentationDescriptorChanged(it, controls.mode, resourceToken)
            return
        }
        onPresentationChanged(presentationSnapshot(), controls.mode, resourceToken)
    }

    private fun recomputePresentationPlan(
        sourceOverride: CoveragePointRenderSnapshot? = null,
        rowsOverride: List<VisibilityRendererRow>? = null,
        explicitResync: Boolean = false,
        fullPaletteRecolor: Boolean = false,
    ) {
        val current = latest ?: run {
            presentationPlan = null
            latestPresentationDescriptor = null
            resetPresentationSelector()
            return
        }
        latestPresentationDescriptor?.let { descriptor ->
            latestPresentationDescriptor = descriptor
                .forMode(controls.mode, enabled = controls.visible)
                .recolor(
                    controls.palette,
                    paletteRevision,
                    fullRange = fullPaletteRecolor || explicitResync,
                )
            presentationPlan = null
            return
        }
        val epoch = SelectorEpoch(
            bindingGeneration = current.bindingGeneration,
            groupGeneration = current.groupGeneration,
            rendererGeneration = current.rendererGeneration,
            mode = controls.mode,
        )
        val selectorReset = explicitResync || selectorEpoch != epoch
        if (selectorReset) {
            presentationSelector.reset()
            selectorEpoch = epoch
        }
        if (borrower != null) {
            // Canonical state is borrowed only for bounded selection and
            // presentation copying. No source-sized rows/list is retained.
            presentationPlan = null
            borrowPresentationSnapshot(
                forceReset = selectorReset,
                fullPaletteRecolor = fullPaletteRecolor,
            )
            return
        }
        val source = sourceOverride ?:
            presentationPlan?.renderSnapshot
        val rows = (rowsOverride ?: source?.toVisibilityRows() ?: presentationPlan?.rows.orEmpty())
            .sortedWith(::compareCoverageRows)
            .map { row -> row.copy(style = row.style.copy(palette = controls.palette)) }
        val selectedSnapshot = source?.let { sourceSnapshot ->
            presentationSelector
                .select(
                    sourceSnapshot,
                    controls.mode.presentationCapacity,
                    forceReset = selectorReset,
                )
                .rewritePaletteBuffers(
                    palette = controls.palette,
                    fullSpanOnPaletteChange = fullPaletteRecolor,
                )
                .copy(paletteRevision = paletteRevision)
        }
        val authoritativeRows = selectedSnapshot?.let { selected ->
            val rowsBySurface = rows.associateBy { it.surfaceId }
            selected.surfaceIds.mapIndexed { index, surfaceId ->
                rowsBySurface[surfaceId] ?: run {
                    val style = selected.styleRows
                        .takeIf { it.isNotEmpty() }
                        ?.let { bytes ->
                            CoverageRendererStyleRowV1.decode(
                                bytes,
                                index * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                            )
                        } ?: CoverageRendererStyleRowV1()
                    val offset = index * 3
                    VisibilityRendererRow(
                        surfaceId = surfaceId,
                        x = selected.positions[offset],
                        y = selected.positions[offset + 1],
                        z = selected.positions[offset + 2],
                        semanticLabel = style.semantic,
                        coverageLabel = style.coverage,
                        targetDirectionIndex = style.directionBin.takeUnless {
                            it == COVERAGE_RENDERER_NO_DIRECTION
                        },
                        style = style,
                    )
                }
            }.take(controls.mode.presentationCapacity)
        } ?: rows.take(controls.mode.presentationCapacity)
        // Production state is borrowed from NativeRendererProjection on
        // demand. Keep the old bounded plan only for source-provider test
        // callers that predate the state-only seam.
        presentationPlan = if (borrower == null) {
            CoveragePresentationPlan(
                mode = controls.mode,
                palette = controls.palette,
                rows = authoritativeRows,
                renderSnapshot = selectedSnapshot,
            )
        } else {
            null
        }
    }

    private fun resetPresentationSelector() {
        presentationSelector.reset()
        selectorEpoch = null
    }

    private fun CoveragePointRenderSnapshot.toVisibilityRows(): List<VisibilityRendererRow> =
        (0 until count).map { index ->
            val style = if (styleRows.isEmpty()) {
                CoverageRendererStyleRowV1()
            } else {
                CoverageRendererStyleRowV1.decode(
                    styleRows,
                    index * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                )
            }
            val offset = index * 3
            VisibilityRendererRow(
                surfaceId = surfaceIds[index],
                x = positions[offset],
                y = positions[offset + 1],
                z = positions[offset + 2],
                semanticLabel = style.semantic,
                coverageLabel = style.coverage,
                targetDirectionIndex = style.directionBin.takeUnless {
                    it == COVERAGE_RENDERER_NO_DIRECTION
                },
                style = style,
            )
        }

    private fun CoveragePointRenderSnapshot.toVisibilityRows(index: Int): VisibilityRendererRow {
        val style = if (styleRows.isEmpty()) {
            CoverageRendererStyleRowV1()
        } else {
            CoverageRendererStyleRowV1.decode(
                styleRows,
                index * COVERAGE_RENDERER_STYLE_ROW_BYTES,
            )
        }
        val offset = index * 3
        return VisibilityRendererRow(
            surfaceId = surfaceIds[index],
            x = positions[offset],
            y = positions[offset + 1],
            z = positions[offset + 2],
            semanticLabel = style.semantic,
            coverageLabel = style.coverage,
            targetDirectionIndex = style.directionBin.takeUnless {
                it == COVERAGE_RENDERER_NO_DIRECTION
            },
            style = style,
        )
    }

    private fun createResourceToken(
        sourceRendererGeneration: Long,
        mode: CoveragePresentationMode,
    ): CoverageResourceToken {
        val token = CoverageResourceToken(
            epoch = ++resourceEpoch,
            sourceRendererGeneration = sourceRendererGeneration,
            mode = mode,
        )
        resourceToken = token
        return token
    }

    private companion object {
        const val HIT_RADIUS_PX = 32.0
    }
}
