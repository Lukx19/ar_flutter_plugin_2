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
import java.util.Arrays

/** One upload-owned bounded page. Only the active prefix belongs to this submission. */
internal data class CoveragePresentationPage(
    var startSlot: Int,
    var totalCount: Int,
    val surfaceIds: LongArray,
    val positions: FloatArray,
    val colors: IntArray,
    val styleRows: ByteArray,
    var count: Int = surfaceIds.size,
) {
    init { configure(startSlot, totalCount, count) }

    fun configure(start: Int, total: Int, active: Int) {
        require(start >= 0 && active >= 0 && total >= start + active)
        require(active <= surfaceIds.size && active <= colors.size)
        require(active <= positions.size / 3 && active <= styleRows.size / COVERAGE_RENDERER_STYLE_ROW_BYTES)
        startSlot = start
        totalCount = total
        count = active
    }

    companion object {
        fun allocate(capacity: Int): CoveragePresentationPage {
            require(capacity in 0..512)
            return CoveragePresentationPage(0, 0, LongArray(capacity), FloatArray(capacity * 3),
                IntArray(capacity), ByteArray(capacity * COVERAGE_RENDERER_STYLE_ROW_BYTES), 0)
        }
    }
}

/** Scalar access prevents page readers from capturing duplicate immutable row tables. */
internal interface PresentationRowMapping {
    fun sourceSlotAt(index: Int): Int
    fun surfaceIdAt(index: Int): Long
}

internal fun interface PresentationPageReader {
    fun read(expected: CoverageRowsQualifier, start: Int, count: Int,
        mapping: PresentationRowMapping, destination: CoveragePresentationPage): Boolean
}

/** Serial publication scratch; no published table or page is retained or recycled here. */
internal class PresentationWorkspace(private val capacity: Int) {
    private val ids = LongArray(capacity)
    private val slots = IntArray(capacity)
    private val heap = IntArray(CoverageRendererLimits.GLYPH_CAPACITY)

    fun validSlots(source: IntArray, sourceCount: Int): Boolean {
        if (source.size > capacity) return false
        source.copyInto(slots)
        Arrays.sort(slots, 0, source.size)
        for (i in source.indices) {
            if (slots[i] !in 0 until sourceCount || (i > 0 && slots[i] == slots[i - 1])) return false
        }
        return true
    }

    fun prepare(surfaceIds: LongArray, sourceSlots: IntArray, rows: ByteArray, prefix: IntArray,
        sourceCount: Int) {
        require(surfaceIds.size <= capacity && validSlots(sourceSlots, sourceCount))
        surfaceIds.copyInto(ids)
        Arrays.sort(ids, 0, surfaceIds.size)
        for (i in 1 until surfaceIds.size) require(ids[i] != ids[i - 1])
        var size = 0
        fun compare(first: Int, second: Int): Int {
            val a = rows[first * COVERAGE_RENDERER_STYLE_ROW_BYTES + 1].toInt() and 255
            val b = rows[second * COVERAGE_RENDERER_STYLE_ROW_BYTES + 1].toInt() and 255
            var result = (b ushr 6).compareTo(a ushr 6)
            if (result == 0) result = ((a ushr 2) and 3).compareTo((b ushr 2) and 3)
            if (result == 0) result = ((a ushr 4) and 3).compareTo((b ushr 4) and 3)
            return if (result == 0) surfaceIds[first].compareTo(surfaceIds[second]) else result
        }
        for (index in surfaceIds.indices) {
            val offset = index * COVERAGE_RENDERER_STYLE_ROW_BYTES
            CoverageRendererStyleRowV1.validateEncoded(rows, offset)
            if ((rows[offset + 3].toInt() and 3) == 0) continue
            if (size < heap.size) {
                var child = size++
                while (child > 0) {
                    val parent = (child - 1) / 2
                    if (compare(index, heap[parent]) <= 0) break
                    heap[child] = heap[parent]
                    child = parent
                }
                heap[child] = index
            } else if (compare(index, heap[0]) < 0) {
                var parent = 0
                while (parent * 2 + 1 < size) {
                    var child = parent * 2 + 1
                    if (child + 1 < size && compare(heap[child + 1], heap[child]) > 0) child++
                    if (compare(index, heap[child]) >= 0) break
                    heap[parent] = heap[child]
                    parent = child
                }
                heap[parent] = index
            }
        }
        Arrays.sort(heap, 0, size)
        for (index in surfaceIds.indices) {
            val offset = index * COVERAGE_RENDERER_STYLE_ROW_BYTES
            val hasGlyph = (rows[offset + 3].toInt() and 3) != 0
            val retained = hasGlyph && Arrays.binarySearch(heap, 0, size, index) >= 0
            if (hasGlyph && !retained) {
                rows[offset + 3] = (rows[offset + 3].toInt() and 0xfc).toByte()
                rows[offset + 4] = COVERAGE_RENDERER_NO_DIRECTION.toByte()
            }
            prefix[index + 1] = prefix[index] + if (retained) 1 else 0
        }
    }

    companion object {
        fun ownedStorageBytes(capacity: Int): Int =
            96 + capacity * (Long.SIZE_BYTES + Int.SIZE_BYTES) + CoverageRendererLimits.GLYPH_CAPACITY * Int.SIZE_BYTES
    }
}

/** One exclusively transferred immutable table shared by all descriptor control views. */
private class PresentationBacking(
    val qualifier: CoverageRowsQualifier,
    val sourceCapacity: Int,
    val sourceCount: Int,
    val basePalette: CoverageRendererPalette,
    private val surfaceIdTable: LongArray,
    private val sourceSlotTable: IntArray,
    private val styleTable: ByteArray,
    workspace: PresentationWorkspace,
    private val pageReader: PresentationPageReader,
) : PresentationRowMapping {
    private val glyphPrefix = IntArray(surfaceIdTable.size + 1)
    init {
        require(sourceCapacity >= sourceCount && sourceCount >= 0)
        require(surfaceIdTable.size == sourceSlotTable.size)
        require(surfaceIdTable.size <= CoverageRendererLimits.CENTROID_CAPACITY)
        require(styleTable.size == surfaceIdTable.size * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        workspace.prepare(surfaceIdTable, sourceSlotTable, styleTable, glyphPrefix, sourceCount)
    }
    val count: Int get() = surfaceIdTable.size
    override fun sourceSlotAt(index: Int): Int = sourceSlotTable[index]
    override fun surfaceIdAt(index: Int): Long = surfaceIdTable[index]
    fun selectedSurfaceIdsCopy(count: Int): LongArray = surfaceIdTable.copyOf(count)
    fun selectedSourceSlotsCopy(count: Int): IntArray = sourceSlotTable.copyOf(count)
    fun styleRowsCopy(count: Int, palette: CoverageRendererPalette): ByteArray =
        styleTable.copyOf(count * COVERAGE_RENDERER_STYLE_ROW_BYTES).also { recolor(it, count, palette) }
    fun glyphCount(count: Int): Int = glyphPrefix[count]

    private fun recolor(rows: ByteArray, count: Int, palette: CoverageRendererPalette) {
        if (palette == basePalette) return
        repeat(count) { index ->
            val offset = index * COVERAGE_RENDERER_STYLE_ROW_BYTES + 2
            rows[offset] = ((rows[offset].toInt() and 0xf0) or palette.code).toByte()
        }
    }

    fun withPageInto(expected: CoverageRowsQualifier, start: Int, maximum: Int, viewCount: Int,
        palette: CoverageRendererPalette, destination: CoveragePresentationPage): Boolean {
        destination.configure(0, 0, 0)
        if (expected != qualifier || start !in 0..viewCount || maximum !in 1..512) return false
        val size = minOf(maximum, viewCount - start)
        if (size > destination.surfaceIds.size || size * 3 > destination.positions.size ||
            size > destination.colors.size || size * COVERAGE_RENDERER_STYLE_ROW_BYTES > destination.styleRows.size) return false
        if (!pageReader.read(expected, start, size, this, destination)) return false
        repeat(size) { index -> if (destination.surfaceIds[index] != surfaceIdTable[start + index]) return false }
        val offset = start * COVERAGE_RENDERER_STYLE_ROW_BYTES
        styleTable.copyInto(destination.styleRows, 0, offset, offset + size * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        recolor(destination.styleRows, size, palette)
        repeat(size) { index -> destination.colors[index] = CoverageRendererStyleRowV1.validatedPackedColor(
            destination.styleRows, index * COVERAGE_RENDERER_STYLE_ROW_BYTES) }
        destination.configure(start, viewCount, size)
        return true
    }

    fun withPage(expected: CoverageRowsQualifier, start: Int, maximum: Int, viewCount: Int,
        palette: CoverageRendererPalette, block: (CoveragePresentationPage) -> Unit): Boolean {
        if (start !in 0..viewCount || maximum !in 1..512) return false
        val page = CoveragePresentationPage.allocate(minOf(maximum, viewCount - start))
        if (!withPageInto(expected, start, maximum, viewCount, palette, page)) return false
        block(page)
        return true
    }
    fun ownedStorageBytes(): Long = PresentationDescriptor.estimatedBackingBytes(count).toLong()
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

    fun withPageInto(expected: CoverageRowsQualifier, startSlot: Int, maximumRows: Int,
        destination: CoveragePresentationPage): Boolean =
        backing.withPageInto(expected, startSlot, maximumRows, count, palette, destination)

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
            return createOwned(qualifier, mode, enabled, capacity, sourceCapacity, sourceCount,
                palette, paletteEpoch, targetSurfaceId, targetDirectionIndex,
                selectedSurfaceIds.copyOf(), selectedSourceSlots.copyOf(), styleRows.copyOf(), update,
                PresentationWorkspace(selectedSurfaceIds.size),
                PresentationPageReader { expected, start, count, _, destination ->
                    val page = pageReader(expected, start, count)
                    if (page == null || page.startSlot != start || page.count != count) false else {
                        page.surfaceIds.copyInto(destination.surfaceIds, 0, 0, count)
                        page.positions.copyInto(destination.positions, 0, 0, count * 3)
                        true
                    }
                })
        }

        /** Transfers private builder arrays; callers must never mutate them after publication. */
        fun createOwned(
            qualifier: CoverageRowsQualifier, mode: CoveragePresentationMode, enabled: Boolean,
            capacity: Int, sourceCapacity: Int, sourceCount: Int,
            palette: CoverageRendererPalette, paletteEpoch: Long,
            targetSurfaceId: Long? = null, targetDirectionIndex: Int? = null,
            selectedSurfaceIds: LongArray, selectedSourceSlots: IntArray, styleRows: ByteArray,
            update: CoveragePointRenderUpdate?, workspace: PresentationWorkspace,
            pageReader: PresentationPageReader,
        ): PresentationDescriptor {
            require(capacity == mode.presentationCapacity)
            require(selectedSurfaceIds.size <= capacity)
            val backing = PresentationBacking(qualifier, sourceCapacity, sourceCount, palette,
                selectedSurfaceIds, selectedSourceSlots, styleRows, workspace, pageReader)
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
    val slots = selectedSourceSlots
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
        keys = LongArray(count) { index -> slots[index].toLong() },
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
