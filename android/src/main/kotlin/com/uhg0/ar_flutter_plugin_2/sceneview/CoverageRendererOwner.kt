package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererSemantic
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
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

/** One immutable row in the latest fully committed presentation cut. */
internal data class VisibilityRendererRow(
    val surfaceId: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val semanticLabel: CoverageSemanticLabel,
    val coverageLabel: CoverageLabel,
    val targetDirectionIndex: Int?,
) {
    init {
        require(surfaceId >= 0L)
        require(x.isFinite() && y.isFinite() && z.isFinite())
        require(targetDirectionIndex == null || targetDirectionIndex in 0..23)
    }
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
    val renderSnapshot: CoveragePointRenderSnapshot? = renderSnapshot?.let { source ->
        source.copy(
            keys = source.keys.copyOf(),
            surfaceIds = source.surfaceIds.copyOf(),
            positions = source.positions.copyOf(),
            colors = source.colors.copyOf(),
            styleRows = source.styleRows.copyOf(),
            gridRotationWorld = source.gridRotationWorld.copyOf(),
            update = source.update?.let { update ->
                update.copy(
                    spans = update.spans.map { span ->
                        span.copy(
                            positions = span.positions.copyOf(),
                            colors = span.colors.copyOf(),
                            styleRows = span.styleRows.copyOf(),
                        )
                    },
                )
            },
        )
    }
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
    private val onControlsChanged: (CoverageRendererControls) -> Unit = {},
    private val onPresentationChanged: (CoveragePointRenderSnapshot?, CoveragePresentationMode) -> Unit = { _, _ -> },
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
    private var lastAcceptedQualifier: InstallQualifier? = null

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
        val mounted = !unavailable
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
        this.controls = controls
        onControlsChanged(controls)
        notifyPresentationChanged()
        val snapshot = latest
        return RendererControlReceipt(
            accepted = !unavailable,
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
        unavailable = false
        recoveryPending = false
        return RendererRecoveryReceipt(
            recovered = true,
            rendererUnavailable = false,
            rehydrated = current != null,
            rowCount = current?.let { selectedRows(it).size } ?: 0,
            rendererGeneration = current?.rendererGeneration ?: 0L,
            geometryRevision = current?.geometryRevision ?: 0L,
            styleRevision = current?.styleRevision ?: 0L,
        )
    }

    @Synchronized
    override fun dispose() {
        disposed = true
        unavailable = true
        recoveryPending = false
        latest = null
    }

    @Synchronized
    fun snapshot(): VisibilityRendererSnapshot? = latest

    @Synchronized
    fun presentationSnapshot(): CoveragePointRenderSnapshot? {
        val current = latest ?: return null
        return current.renderSnapshot?.let { selectForMode(it) }
    }

    @Synchronized
    fun refreshPresentation() {
        notifyPresentationChanged()
    }

    @Synchronized
    fun clearLatest() {
        latest = null
    }

    @Synchronized
    override fun status(): CoverageRendererStatus {
        val current = latest
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
        )
    }

    private fun selectedRows(snapshot: VisibilityRendererSnapshot): List<VisibilityRendererRow> {
        return snapshot.rows.sortedBy { it.surfaceId }.take(controls.mode.presentationCapacity)
    }

    private fun selectForMode(snapshot: CoveragePointRenderSnapshot): CoveragePointRenderSnapshot {
        return snapshot.boundedForPresentation(controls.mode.presentationCapacity)
    }

    private fun notifyPresentationChanged() {
        if (unavailable || disposed) return
        onPresentationChanged(presentationSnapshot(), controls.mode)
    }

    private companion object {
        const val HIT_RADIUS_PX = 32.0
    }
}
