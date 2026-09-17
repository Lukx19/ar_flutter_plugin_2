package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererSemantic
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.pointcloud.deepCopy
import com.uhg0.ar_flutter_plugin_2.pointcloud.rewritePaletteBuffers
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_NO_DIRECTION
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
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
    val renderSnapshot: CoveragePointRenderSnapshot? = renderSnapshot?.deepCopy()
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
) {
    val rows: List<VisibilityRendererRow> = Collections.unmodifiableList(
        rows.map { it.copy() },
    )
    val renderSnapshot: CoveragePointRenderSnapshot? = renderSnapshot?.deepCopy()
    val rowCount: Int get() = rows.size

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
        require(rows.map { it.surfaceId }.distinct().size == rows.size)
    }
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
    private val onResourceLifecycleChanged: (CoverageResourceToken, Boolean) -> Unit = { _, _ -> },
    private val worldToScreen: CoverageWorldToScreenProjection =
        CoverageWorldToScreenProjection { x, y, _ -> CoverageScreenPoint(x, y, 1f) },
) : CoverageRendererOwner {
    private var latest: VisibilityRendererSnapshot? = null
    private var controls = CoverageRendererControls(
        visible = true,
        mode = CoveragePresentationMode.SEMANTIC_CENTROIDS,
        palette = CoverageRendererPalette.COVERAGE,
    )
    private var unavailable = false
    private var disposed = false
    private var recoveryPending = false
    private var lifecyclePaused = false
    private var controlsConfigured = false
    // Pure owner tests and the pre-Compose owner seam start healthy; the host
    // replaces this with concrete mount/upload receipts as resources appear.
    private var resourceMounted = true
    private var resourceFailureCount = 0
    private var paletteRevision = 0L
    private var lastAcceptedQualifier: InstallQualifier? = null
    private var presentationPlan: CoveragePresentationPlan? = null
    private val presentationSelector =
        CoveragePresentationSelector(CoverageRendererLimits.CENTROID_CAPACITY)
    private var selectorEpoch: SelectorEpoch? = null
    private var resourceEpoch = 0L
    private var resourceToken: CoverageResourceToken? = null

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
                rowCount = current?.let { selectedRows(it).size } ?: 0,
                bindingGeneration = current?.bindingGeneration ?: snapshot.bindingGeneration,
                groupGeneration = current?.groupGeneration ?: snapshot.groupGeneration,
                rendererGeneration = current?.rendererGeneration ?: snapshot.rendererGeneration,
                geometryRevision = current?.geometryRevision ?: snapshot.geometryRevision,
                styleRevision = current?.styleRevision ?: snapshot.styleRevision,
                stale = true,
            )
        }
        val replayed = priorQualifier == qualifier
        latest = snapshot
        lastAcceptedQualifier = qualifier
        val explicitResync = snapshot.renderSnapshot?.update?.let { it.reset } ?: true
        recomputePresentationPlan(explicitResync = explicitResync)
        val mounted = !unavailable && resourceMounted
        return RendererInstallReceipt(
            installed = mounted,
            rendererUnavailable = unavailable,
            rowCount = selectedRows(snapshot).size,
            bindingGeneration = snapshot.bindingGeneration,
            groupGeneration = snapshot.groupGeneration,
            rendererGeneration = snapshot.rendererGeneration,
            geometryRevision = snapshot.geometryRevision,
            styleRevision = snapshot.styleRevision,
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
        if (!onControlsChanged(controls)) {
            val current = latest
            return RendererControlReceipt(
                accepted = false,
                rendererUnavailable = unavailable,
                visible = this.controls.visible,
                mode = this.controls.mode,
                palette = this.controls.palette,
                rowCount = current?.let { selectedRows(it).size } ?: 0,
                rendererGeneration = current?.rendererGeneration ?: 0L,
            )
        }
        val paletteOnlyChange = this.controls.visible == controls.visible &&
            this.controls.mode == controls.mode &&
            this.controls.palette != controls.palette
        val modeChanged = this.controls.mode != controls.mode
        if (this.controls.palette != controls.palette) {
            paletteRevision++
        }
        this.controls = controls
        if (modeChanged) resourceToken = null
        controlsConfigured = true
        recomputePresentationPlan(fullPaletteRecolor = paletteOnlyChange)
        notifyPresentationChanged()
        val snapshot = latest
        return RendererControlReceipt(
            accepted = !unavailable && resourceMounted,
            rendererUnavailable = unavailable,
            visible = controls.visible,
            mode = controls.mode,
            palette = controls.palette,
            rowCount = snapshot?.let { selectedRows(it).size } ?: 0,
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
        val row = selectedRows(snapshot)
            .asSequence()
            .mapNotNull { row ->
                val projected = worldToScreen.project(row.x, row.y, row.z) ?: return@mapNotNull null
                if (projected.depth <= 0f) return@mapNotNull null
                val dx = projected.xPx - xPx
                val dy = projected.yPx - yPx
                val distanceSquared = dx * dx + dy * dy
                if (!distanceSquared.isFinite() || distanceSquared > HIT_RADIUS_PX * HIT_RADIUS_PX) {
                    return@mapNotNull null
                }
                Triple(row, distanceSquared, projected.depth)
            }
            .sortedWith(
                compareBy<Triple<VisibilityRendererRow, Float, Float>> { it.second }
                    .thenBy { it.third }
                    .thenBy { it.first.surfaceId },
            )
            .firstOrNull()?.first ?: return CoverageHitReceipt.Miss
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
                rowCount = current?.let { selectedRows(it).size } ?: 0,
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
            rowCount = current?.let { selectedRows(it).size } ?: 0,
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
        onResourceLifecycleChanged(token, true)
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
        onResourceLifecycleChanged(token, true)
        notifyPresentationChanged()
        return true
    }

    @Synchronized
    fun controlsConfigured(): Boolean = controlsConfigured

    @Synchronized
    fun snapshot(): VisibilityRendererSnapshot? = latest

    @Synchronized
    fun presentationSnapshot(): CoveragePointRenderSnapshot? {
        return presentationPlan?.renderSnapshot
    }

    @Synchronized
    fun presentationPlan(): CoveragePresentationPlan? = presentationPlan

    @Synchronized
    fun refreshPresentation() {
        notifyPresentationChanged()
    }

    @Synchronized
    fun clearLatest() {
        latest = null
        presentationPlan = null
        resetPresentationSelector()
        resourceToken = null
    }

    @Synchronized
    override fun status(): CoverageRendererStatus {
        val current = latest
        val plan = presentationPlan
        return CoverageRendererStatus(
            rendererUnavailable = unavailable,
            visible = controls.visible,
            mode = controls.mode,
            palette = controls.palette,
            rowCount = current?.rowCount ?: 0,
            selectedRowCount = current?.let { selectedRows(it).size } ?: 0,
            rendererGeneration = current?.rendererGeneration ?: 0L,
            geometryRevision = current?.geometryRevision ?: 0L,
            styleRevision = current?.styleRevision ?: 0L,
            residentRowCount = plan?.residentRowCount ?: 0,
            residentGlyphCount = plan?.residentGlyphCount ?: 0,
            resourceAvailable = resourceMounted,
            recoveryPending = recoveryPending,
            resourceFailureCount = resourceFailureCount,
        )
    }

    private fun selectedRows(snapshot: VisibilityRendererSnapshot): List<VisibilityRendererRow> {
        return presentationPlan?.rows ?: snapshot.rows
            .sortedWith(::compareCoverageRows)
            .take(controls.mode.presentationCapacity)
    }

    private fun notifyPresentationChanged() {
        if (unavailable || disposed) return
        onPresentationChanged(presentationSnapshot(), controls.mode, resourceToken)
    }

    private fun recomputePresentationPlan(
        explicitResync: Boolean = false,
        fullPaletteRecolor: Boolean = false,
    ) {
        val current = latest ?: run {
            presentationPlan = null
            resetPresentationSelector()
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
        val rows = current.rows
            .sortedWith(::compareCoverageRows)
            .map { row -> row.copy(style = row.style.copy(palette = controls.palette)) }
        val selectedSnapshot = current.renderSnapshot?.let { source ->
            presentationSelector
                .select(
                    source,
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
        presentationPlan = CoveragePresentationPlan(
            mode = controls.mode,
            palette = controls.palette,
            rows = authoritativeRows,
            renderSnapshot = selectedSnapshot,
        )
    }

    private fun resetPresentationSelector() {
        presentationSelector.reset()
        selectorEpoch = null
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
