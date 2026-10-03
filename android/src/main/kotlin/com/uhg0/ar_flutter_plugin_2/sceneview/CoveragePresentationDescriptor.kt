package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererGlyph
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_NO_DIRECTION
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRowsQualifier
import com.uhg0.ar_flutter_plugin_2.pointcloud.rangeOnly
import java.util.PriorityQueue

/** One bounded geometry/style page borrowed by a mesh upload. */
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
 * The one immutable bounded table shared by every descriptor control view.
 * A control change changes only the view (count, palette, visibility, and
 * update metadata); it never creates a transformed reader chain or another
 * selected identity/style table.
 */
private class PresentationBacking(
    val qualifier: CoverageRowsQualifier,
    val sourceCapacity: Int,
    val sourceCount: Int,
    val basePalette: CoverageRendererPalette,
    selectedSurfaceIds: LongArray,
    selectedSourceSlots: IntArray,
    styleRows: ByteArray,
    private val pageReader: (CoverageRowsQualifier, Int, Int) -> CoveragePresentationPage?,
) {
    private val surfaceIdTable = selectedSurfaceIds.copyOf()
    private val sourceSlotTable = selectedSourceSlots.copyOf()
    private val styleTable = styleRows.copyOf()
    private val glyphPrefix = IntArray(surfaceIdTable.size + 1)

    init {
        require(sourceCapacity >= sourceCount && sourceCount >= 0)
        require(surfaceIdTable.size == sourceSlotTable.size)
        require(surfaceIdTable.size <= CoverageRendererLimits.CENTROID_CAPACITY)
        require(styleTable.size == surfaceIdTable.size * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        require(surfaceIdTable.toSet().size == surfaceIdTable.size)
        require(sourceSlotTable.all { it >= 0 && it < sourceCount })
        val bestGlyphRows = PriorityQueue<Int>(CoverageRendererLimits.GLYPH_CAPACITY) { first, second ->
            compareGlyphPriority(second, first)
        }
        repeat(surfaceIdTable.size) { index ->
            if (CoverageRendererStyleRowV1.validatedGlyph(styleTable,
                    index * COVERAGE_RENDERER_STYLE_ROW_BYTES) == CoverageRendererGlyph.NONE) return@repeat
            if (bestGlyphRows.size < CoverageRendererLimits.GLYPH_CAPACITY) {
                bestGlyphRows.add(index)
            } else if (compareGlyphPriority(index, checkNotNull(bestGlyphRows.peek())) < 0) {
                bestGlyphRows.remove()
                bestGlyphRows.add(index)
            }
        }
        val retainedGlyphRows = bestGlyphRows.toIntArray().sortedArray()
        repeat(surfaceIdTable.size) { index ->
            val glyph = CoverageRendererStyleRowV1.validatedGlyph(styleTable,
                index * COVERAGE_RENDERER_STYLE_ROW_BYTES)
            val retained = glyph != CoverageRendererGlyph.NONE &&
                retainedGlyphRows.binarySearch(index) >= 0
            if (glyph != CoverageRendererGlyph.NONE && !retained) {
                styleAt(index).copy(
                    glyph = CoverageRendererGlyph.NONE,
                    directionBin = COVERAGE_RENDERER_NO_DIRECTION,
                ).encode()
                    .copyInto(styleTable, index * COVERAGE_RENDERER_STYLE_ROW_BYTES)
            }
            glyphPrefix[index + 1] = glyphPrefix[index] + if (retained) 1 else 0
        }
    }

    private fun styleAt(index: Int): CoverageRendererStyleRowV1 =
        CoverageRendererStyleRowV1.decode(styleTable, index * COVERAGE_RENDERER_STYLE_ROW_BYTES)

    private fun compareGlyphPriority(first: Int, second: Int): Int = compareCoverageStylePriority(
        styleAt(first), surfaceIdTable[first], styleAt(second), surfaceIdTable[second],
    )

    val count: Int get() = surfaceIdTable.size

    fun selectedSurfaceIdsCopy(count: Int): LongArray = surfaceIdTable.copyOf(count)

    fun surfaceIdAt(index: Int): Long = surfaceIdTable[index]

    fun selectedSourceSlotsCopy(count: Int): IntArray = sourceSlotTable.copyOf(count)

    /** Legacy metadata access is a defensive copy; the backing remains shared. */
    fun styleRowsCopy(count: Int, palette: CoverageRendererPalette): ByteArray {
        val copied = styleTable.copyOf(count * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        if (palette == basePalette) return copied
        recolorStyleRows(copied, count, palette)
        return copied
    }

    fun glyphCount(count: Int): Int = glyphPrefix[count]

    fun withPage(
        expected: CoverageRowsQualifier,
        startSlot: Int,
        maximumRows: Int,
        viewCount: Int,
        palette: CoverageRendererPalette,
        block: (CoveragePresentationPage) -> Unit,
    ): Boolean {
        if (expected != qualifier || startSlot !in 0..viewCount || maximumRows !in 1..512) {
            return false
        }
        val page = pageReader(expected, startSlot, minOf(maximumRows, viewCount - startSlot))
            ?: return false
        val end = page.startSlot + page.count
        if (page.startSlot != startSlot || page.count > maximumRows || end > viewCount) {
            return false
        }
        val pageStyles = styleRowsCopyRange(startSlot, page.count, palette)
        val pageColors = IntArray(page.count) { index ->
            CoverageRendererStyleRowV1.validatedPackedColor(pageStyles, index * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        }
        block(
            page.copy(
                totalCount = viewCount,
                colors = pageColors,
                styleRows = pageStyles,
            ),
        )
        return true
    }

    private fun styleRowsCopyRange(
        startSlot: Int,
        count: Int,
        palette: CoverageRendererPalette,
    ): ByteArray {
        val start = startSlot * COVERAGE_RENDERER_STYLE_ROW_BYTES
        val copied = styleTable.copyOfRange(start, start + count * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        if (palette != basePalette) recolorStyleRows(copied, count, palette)
        return copied
    }

    /** Includes the one immutable bounded table, not borrowed page payloads. */
    fun ownedStorageBytes(): Long =
        PresentationDescriptor.estimatedBackingBytes(surfaceIdTable.size).toLong()

    private companion object {
        fun recolorStyleRows(
            rows: ByteArray,
            count: Int,
            palette: CoverageRendererPalette,
        ) {
            repeat(count) { index ->
                val offset = index * COVERAGE_RENDERER_STYLE_ROW_BYTES
                CoverageRendererStyleRowV1.decode(rows, offset)
                    .copy(palette = palette)
                    .encode()
                    .copyInto(rows, offset)
            }
        }
    }
}

/** Immutable bounded presentation metadata published by the native projection. */
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
    private val backing: PresentationBacking,
    val update: CoveragePointRenderUpdate?,
) {
    init {
        require(capacity in 0..CoverageRendererLimits.CENTROID_CAPACITY)
        require(count in 0..capacity)
        require(count <= backing.count)
        require(backing.qualifier == qualifier)
        require(backing.sourceCapacity == sourceCapacity)
        require(backing.sourceCount == sourceCount)
        require(paletteEpoch >= 0L)
        require(targetSurfaceId == null || targetSurfaceId >= 0L)
        require(targetDirectionIndex == null || targetDirectionIndex in 0..23)
    }

    /** Defensive copies are retained only for legacy callers. */
    val selectedSurfaceIds: LongArray get() = backing.selectedSurfaceIdsCopy(count)
    val selectedSourceSlots: IntArray get() = backing.selectedSourceSlotsCopy(count)
    val styleRows: ByteArray get() = backing.styleRowsCopy(count, palette)

    /** O(1) scalar used by renderer status and telemetry. */
    val glyphCount: Int get() = backing.glyphCount(count)

    /** Compares bounded destination identities without copying or retaining row arrays. */
    internal fun changedSelectionRows(previous: PresentationDescriptor): Int {
        var changed = kotlin.math.abs(count - previous.count)
        repeat(minOf(count, previous.count)) { index ->
            if (backing.surfaceIdAt(index) != previous.backing.surfaceIdAt(index)) changed++
        }
        return changed
    }

    /** Borrows one <=512-row page only while the qualifier is still current. */
    fun withPage(
        expected: CoverageRowsQualifier,
        startSlot: Int,
        maximumRows: Int = 512,
        block: (CoveragePresentationPage) -> Unit,
    ): Boolean = backing.withPage(expected, startSlot, maximumRows, count, palette, block)

    /** Derives a flat control view over the same immutable bounded backing. */
    fun withControls(
        mode: CoveragePresentationMode,
        enabled: Boolean = this.enabled,
        palette: CoverageRendererPalette = this.palette,
        paletteEpoch: Long = this.paletteEpoch,
        fullRange: Boolean = false,
    ): PresentationDescriptor {
        if (!fullRange && mode == this.mode && enabled == this.enabled &&
            palette == this.palette && paletteEpoch == this.paletteEpoch
        ) return this
        val nextCount = minOf(backing.count, mode.presentationCapacity)
        val nextUpdate = if (fullRange) {
            fullRangeUpdate(update, enabled, nextCount)
        } else {
            update?.copy(
                enabled = enabled,
                count = nextCount,
                spans = clipSpans(update.spans, nextCount),
            )
        }
        return PresentationDescriptor(
            qualifier = qualifier,
            mode = mode,
            enabled = enabled,
            capacity = mode.presentationCapacity,
            count = nextCount,
            sourceCapacity = sourceCapacity,
            sourceCount = sourceCount,
            palette = palette,
            paletteEpoch = paletteEpoch,
            targetSurfaceId = targetSurfaceId,
            targetDirectionIndex = targetDirectionIndex,
            backing = backing,
            update = nextUpdate,
        )
    }

    internal fun ownedStorageBytes(): Long = backing.ownedStorageBytes()

    companion object {
        /** Exact bytes of the immutable bounded backing, excluding borrowed pages. */
        internal fun estimatedBackingBytes(rowCapacity: Int): Int {
            require(rowCapacity in 0..CoverageRendererLimits.CENTROID_CAPACITY)
            return 96 +
                rowCapacity * (
                    Long.SIZE_BYTES +
                        Int.SIZE_BYTES +
                        COVERAGE_RENDERER_STYLE_ROW_BYTES +
                        Int.SIZE_BYTES
                ) + Int.SIZE_BYTES
        }

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
        ): PresentationDescriptor {
            require(capacity == mode.presentationCapacity)
            require(selectedSurfaceIds.size <= capacity)
            val backing = PresentationBacking(
                qualifier = qualifier,
                sourceCapacity = sourceCapacity,
                sourceCount = sourceCount,
                basePalette = palette,
                selectedSurfaceIds = selectedSurfaceIds,
                selectedSourceSlots = selectedSourceSlots,
                styleRows = styleRows,
                pageReader = pageReader,
            )
            return PresentationDescriptor(
                qualifier = qualifier,
                mode = mode,
                enabled = enabled,
                capacity = capacity,
                count = selectedSurfaceIds.size,
                sourceCapacity = sourceCapacity,
                sourceCount = sourceCount,
                palette = palette,
                paletteEpoch = paletteEpoch,
                targetSurfaceId = targetSurfaceId,
                targetDirectionIndex = targetDirectionIndex,
                backing = backing,
                update = update?.rangeOnly(),
            )
        }

        private fun fullRangeUpdate(
            source: CoveragePointRenderUpdate?,
            enabled: Boolean,
            count: Int,
        ): CoveragePointRenderUpdate? = source?.copy(
            enabled = enabled,
            count = count,
            spans = if (count == 0) emptyList() else listOf(
                CoveragePointSpan(
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
