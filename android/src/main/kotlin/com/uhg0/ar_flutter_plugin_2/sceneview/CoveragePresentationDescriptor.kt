package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRowsQualifier
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.pointcloud.rangeOnly

/** One bounded geometry/style page borrowed by a mesh upload.
 *
 * Page buffers are intentionally short lived.  A descriptor owns no source
 * positions or colors; [withPage] borrows them from the qualifier-matched
 * canonical projection and copies at most 512 rows for one upload.
 */
internal data class CoveragePresentationPage(
    val startSlot: Int,
    val totalCount: Int,
    val surfaceIds: LongArray,
    val positions: FloatArray,
    val colors: IntArray,
    val styleRows: ByteArray,
) {
    val count: Int get() = surfaceIds.size

    init {
        require(startSlot >= 0)
        require(totalCount >= count)
        require(positions.size == count * 3)
        require(colors.size == count)
        require(styleRows.size == count * COVERAGE_RENDERER_STYLE_ROW_BYTES)
    }
}

/**
 * Immutable bounded presentation metadata published by the native projection.
 *
 * The selected identity/style table is bounded by the active presentation
 * mode.  The canonical 100k source remains behind [pageReader] and is never
 * handed to SceneView as a full [CoveragePointRenderSnapshot].
 */
internal class PresentationDescriptor private constructor(
    val qualifier: CoverageRowsQualifier,
    val mode: CoveragePresentationMode,
    val enabled: Boolean,
    val capacity: Int,
    val count: Int,
    val sourceCapacity: Int,
    val sourceCount: Int,
    val palette: CoverageRendererPalette,
    val paletteEpoch: Long,
    val targetSurfaceId: Long? = null,
    val targetDirectionIndex: Int? = null,
    selectedSurfaceIds: LongArray,
    selectedSourceSlots: IntArray,
    styleRows: ByteArray,
    val update: CoveragePointRenderUpdate?,
    private val pageReader: (CoverageRowsQualifier, Int, Int) -> CoveragePresentationPage?,
) {
    private val identityTable = selectedSurfaceIds.copyOf()
    private val sourceSlotTable = selectedSourceSlots.copyOf()
    private val styleTable = styleRows.copyOf()

    init {
        require(capacity in 0..20_000)
        require(count == identityTable.size && count == sourceSlotTable.size)
        require(count <= capacity)
        require(sourceCapacity >= sourceCount && sourceCount >= 0)
        require(styleTable.size == count * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        require(identityTable.toSet().size == identityTable.size)
        require(sourceSlotTable.all { it >= 0 && it < sourceCount })
        require(paletteEpoch >= 0L)
        require(targetSurfaceId == null || targetSurfaceId >= 0L)
        require(targetDirectionIndex == null || targetDirectionIndex in 0..23)
    }

    val selectedSurfaceIds: LongArray get() = identityTable.copyOf()
    val selectedSourceSlots: IntArray get() = sourceSlotTable.copyOf()
    val styleRows: ByteArray get() = styleTable.copyOf()

    /** Borrows one <=512-row page only while the qualifier is still current. */
    fun withPage(
        expected: CoverageRowsQualifier,
        startSlot: Int,
        maximumRows: Int = 512,
        block: (CoveragePresentationPage) -> Unit,
    ): Boolean {
        if (startSlot !in 0..count || maximumRows !in 1..512 || expected != qualifier) {
            return false
        }
        val page = pageReader(expected, startSlot, minOf(maximumRows, count - startSlot))
            ?: return false
        block(page)
        return true
    }

    /** Returns a bounded mode view without copying canonical source values. */
    fun forMode(
        mode: CoveragePresentationMode,
        enabled: Boolean = this.enabled,
            palette: CoverageRendererPalette = this.palette,
            paletteEpoch: Long = this.paletteEpoch,
            fullRange: Boolean = false,
    ): PresentationDescriptor {
        val nextCount = minOf(count, mode.presentationCapacity)
        val nextStyleRows = styleTable.copyOf(nextCount * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        if (palette != this.palette) {
            repeat(nextCount) { index ->
                val offset = index * COVERAGE_RENDERER_STYLE_ROW_BYTES
                CoverageRendererStyleRowV1.decode(nextStyleRows, offset)
                    .copy(palette = palette).encode().copyInto(nextStyleRows, offset)
            }
        }
        val nextUpdate = update?.let {
            it.copy(
                enabled = enabled,
                count = nextCount,
                spans = clipSpans(it.spans, nextCount),
            )
        }
        // The fullRange branch is filled by [fullRangeUpdate] below.  Keeping
        // range-only updates here avoids retaining page payloads in metadata.
        return create(
            qualifier = qualifier,
            mode = mode,
            enabled = enabled,
            capacity = mode.presentationCapacity,
            sourceCapacity = sourceCapacity,
            sourceCount = sourceCount,
            palette = palette,
            paletteEpoch = paletteEpoch,
            targetSurfaceId = targetSurfaceId,
            targetDirectionIndex = targetDirectionIndex,
            selectedSurfaceIds = identityTable.copyOf(nextCount),
            selectedSourceSlots = sourceSlotTable.copyOf(nextCount),
            styleRows = nextStyleRows,
            update = if (fullRange) fullRangeUpdate(update, enabled, nextCount) else nextUpdate,
            pageReader = { expected, start, maximum ->
                pageReader(expected, start, maximum)?.takeIf { it.startSlot + it.count <= nextCount }
                    ?.let { page ->
                        val pageStyles = nextStyleRows.copyOfRange(
                            start * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                            (start + page.count) * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                        )
                        page.copy(
                            totalCount = nextCount,
                            colors = IntArray(page.count) { index ->
                                CoverageRendererStyleRowV1.decode(
                                    pageStyles,
                                    index * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                                ).packedColor()
                            },
                            styleRows = pageStyles,
                        )
                    }
            },
        )
    }

    fun recolor(
        palette: CoverageRendererPalette,
        paletteEpoch: Long,
        fullRange: Boolean,
    ): PresentationDescriptor {
        if (palette == this.palette && paletteEpoch == this.paletteEpoch) return this
        val recolored = styleTable.copyOf()
        repeat(count) { index ->
            val offset = index * COVERAGE_RENDERER_STYLE_ROW_BYTES
            CoverageRendererStyleRowV1.decode(recolored, offset)
                .copy(palette = palette).encode().copyInto(recolored, offset)
        }
        return create(
            qualifier = qualifier,
            mode = mode,
            enabled = enabled,
            capacity = capacity,
            sourceCapacity = sourceCapacity,
            sourceCount = sourceCount,
            palette = palette,
            paletteEpoch = paletteEpoch,
            targetSurfaceId = targetSurfaceId,
            targetDirectionIndex = targetDirectionIndex,
            selectedSurfaceIds = identityTable,
            selectedSourceSlots = sourceSlotTable,
            styleRows = recolored,
            update = if (fullRange) fullRangeUpdate(update, enabled, count) else update,
            pageReader = { expected, start, maximum ->
                pageReader(expected, start, maximum)?.let { page ->
                    val pageStyles = recolored.copyOfRange(
                        start * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                        (start + page.count) * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                    )
                    page.copy(
                        colors = IntArray(page.count) { index ->
                            CoverageRendererStyleRowV1.decode(
                                pageStyles,
                                index * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                            ).packedColor()
                        },
                        styleRows = pageStyles,
                    )
                }
            },
        )
    }

    internal fun ownedStorageBytes(): Long =
        96L + identityTable.size * Long.SIZE_BYTES.toLong() +
            sourceSlotTable.size * Int.SIZE_BYTES.toLong() + styleTable.size

    companion object {
        fun create(
            qualifier: CoverageRowsQualifier,
            mode: CoveragePresentationMode,
            enabled: Boolean,
            capacity: Int,
            sourceCapacity: Int,
            sourceCount: Int,
            palette: CoverageRendererPalette,
            paletteEpoch: Long,
            targetSurfaceId: Long? = null,
            targetDirectionIndex: Int? = null,
            selectedSurfaceIds: LongArray,
            selectedSourceSlots: IntArray,
            styleRows: ByteArray,
            update: CoveragePointRenderUpdate?,
            pageReader: (CoverageRowsQualifier, Int, Int) -> CoveragePresentationPage?,
        ) = PresentationDescriptor(
            qualifier, mode, enabled, capacity, selectedSurfaceIds.size,
            sourceCapacity, sourceCount, palette, paletteEpoch,
            targetSurfaceId, targetDirectionIndex,
            selectedSurfaceIds, selectedSourceSlots, styleRows, update?.rangeOnly(), pageReader,
        )

        private fun fullRangeUpdate(
            source: CoveragePointRenderUpdate?,
            enabled: Boolean,
            count: Int,
        ): CoveragePointRenderUpdate? = source?.copy(
            enabled = enabled,
            count = count,
            spans = if (count == 0) emptyList() else listOf(
                com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan(
                    startSlot = 0,
                    positions = FloatArray(0),
                    colors = IntArray(0),
                    endSlotExclusive = count,
                ),
            ),
            reset = true,
        )

        private fun clipSpans(
            spans: List<CoveragePointSpan>,
            count: Int,
        ): List<CoveragePointSpan> = spans.mapNotNull { span ->
            val start = span.startSlot.coerceAtLeast(0)
            val end = span.endSlotExclusive.coerceAtMost(count)
            if (start >= end) null else span.rangeOnly().copy(
                startSlot = start,
                endSlotExclusive = end,
            )
        }
    }
}

/** Domain-facing name retained for callers that do not need implementation detail. */
internal typealias BoundedCoveragePresentation = PresentationDescriptor

/** Raw snapshot adapter kept solely for legacy tests and non-V2 callers. */
internal fun PresentationDescriptor.toLegacySnapshot(): CoveragePointRenderSnapshot? {
    val ids = selectedSurfaceIds
    val positions = FloatArray(count * 3)
    val colors = IntArray(count)
    val styles = styleRows
    var start = 0
    while (start < count) {
        var accepted = false
        withPage(qualifier, start, 512) { page ->
            repeat(page.count) { index ->
                val destination = start + index
                page.positions.copyInto(positions, destination * 3, index * 3, index * 3 + 3)
                colors[destination] = page.colors[index]
            }
            accepted = true
        }
        if (!accepted) return null
        start += minOf(512, count - start)
    }
    return CoveragePointRenderSnapshot(
        revision = qualifier.geometryRevision,
        enabled = enabled,
        capacity = capacity,
        count = count,
        keys = LongArray(count) { index -> selectedSourceSlots[index].toLong() },
        surfaceIds = ids,
        positions = positions,
        colors = colors,
        styleRows = styles,
        update = update,
        bindingGeneration = qualifier.bindingGeneration,
        groupGeneration = qualifier.groupGeneration,
        transactionId = qualifier.transactionId,
        geometryRevision = qualifier.geometryRevision,
        styleRevision = qualifier.styleRevision,
        paletteRevision = paletteEpoch,
    )
}
