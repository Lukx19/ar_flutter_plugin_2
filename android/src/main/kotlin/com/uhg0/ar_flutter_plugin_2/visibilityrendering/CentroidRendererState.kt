package com.uhg0.ar_flutter_plugin_2.visibilityrendering

import java.util.PriorityQueue

enum class RendererMode {
    CENTROIDS,
    CUBES,
    RAW_POINTS,
    WARM_PROXIES,
    OVERVIEW,
    GLYPHS,
    SUPPRESSED_DEBUG,
}
enum class SemanticState { UNCOVERED, COVERED, PENDING, STALE, DEGRADED }

object RendererPopulationLimits {
    const val CENTROID_ROWS = 20_000
    const val CUBE_ROWS = 8_000
    const val RAW_POINT_ROWS = 2_000
    const val WARM_PROXY_ROWS = 4_096
    const val OVERVIEW_ROWS = 512
    const val GLYPH_ROWS = 256
    const val SUPPRESSED_DEBUG_ROWS = 1_024

    fun maximumRows(mode: RendererMode): Int = when (mode) {
        RendererMode.CENTROIDS -> CENTROID_ROWS
        RendererMode.CUBES -> CUBE_ROWS
        RendererMode.RAW_POINTS -> RAW_POINT_ROWS
        RendererMode.WARM_PROXIES -> WARM_PROXY_ROWS
        RendererMode.OVERVIEW -> OVERVIEW_ROWS
        RendererMode.GLYPHS -> GLYPH_ROWS
        RendererMode.SUPPRESSED_DEBUG -> SUPPRESSED_DEBUG_ROWS
    }

    fun fixedAllocationBytes(mode: RendererMode): Int = when (mode) {
        RendererMode.CUBES -> 2_944_000 + 1_024_000 + 1_920_000
        RendererMode.CENTROIDS,
        RendererMode.RAW_POINTS,
        RendererMode.WARM_PROXIES,
        RendererMode.OVERVIEW,
        RendererMode.GLYPHS,
        RendererMode.SUPPRESSED_DEBUG -> 0
    }
}

data class CentroidRow(
    val qualifiedKey: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val state: SemanticState,
    val alpha: Float = 1f,
)

data class DirtySpan(val start: Int, val endExclusive: Int)

data class RendererFramePlan(
    val mode: RendererMode,
    val dirtySpans: List<DirtySpan>,
    val uploadBytes: Int,
    val reset: Boolean,
    val rowCount: Int,
    val rebuild: Boolean = false,
)

class CentroidRendererState(
    private val capacity: Int,
    private val bytesPerRow: Int = 32,
    private val maxUploadBytes: Int = 64 * 1024,
) {
    init {
        require(capacity > 0 && bytesPerRow > 0 && maxUploadBytes > 0)
    }

    private val slotsByKey = linkedMapOf<String, Int>()
    private val rows = mutableListOf<CentroidRow?>()
    private val freeSlots = PriorityQueue<Int>()
    private val dirtyRows = sortedSetOf<Int>()
    private var reset = true
    private var rebuild = false
    var isContextLost: Boolean = false
        private set
    var mode: RendererMode = RendererMode.CENTROIDS
        private set

    val rowCount: Int get() = slotsByKey.size
    val allocatedBytes: Int
        get() = rows.size * bytesPerRow + RendererPopulationLimits.fixedAllocationBytes(mode)
    val withinFixedPopulation: Boolean
        get() = rowCount <= RendererPopulationLimits.maximumRows(mode)

    fun slotFor(key: String): Int? = slotsByKey[key]

    fun loseContext() {
        isContextLost = true
        rebuild = true
        reset = true
        dirtyRows += slotsByKey.values
    }

    fun restoreContext() {
        isContextLost = false
        rebuild = true
        reset = true
        dirtyRows += slotsByKey.values
    }

    fun setMode(value: RendererMode) {
        if (mode == value) return
        if (rowCount > RendererPopulationLimits.maximumRows(value)) return
        mode = value
        reset = true
        dirtyRows += slotsByKey.values
    }

    fun upsert(row: CentroidRow): Boolean {
        val existing = slotsByKey[row.qualifiedKey]
        if (existing != null) {
            if (rows[existing] == row) return false
            rows[existing] = row
            dirtyRows += existing
            return true
        }
        if (rowCount >= capacity ||
            rowCount >= RendererPopulationLimits.maximumRows(mode)) return false
        val slot = if (freeSlots.isNotEmpty()) {
            freeSlots.remove()
        } else {
            if (rows.size >= capacity) return false
            rows.size.also { rows += null }
        }
        slotsByKey[row.qualifiedKey] = slot
        rows[slot] = row
        dirtyRows += slot
        return true
    }

    fun remove(key: String): Boolean {
        val slot = slotsByKey.remove(key) ?: return false
        rows[slot] = null
        freeSlots += slot
        dirtyRows += slot
        return true
    }

    fun flush(): RendererFramePlan {
        val selectedRows = dirtyRows.take(maxUploadBytes / bytesPerRow).toSet()
        val spans = selectedRows.toList().let { dirty ->
            if (dirty.isEmpty()) emptyList() else buildList {
                var start = dirty.first()
                var previous = start
                dirty.drop(1).forEach { row ->
                    if (row != previous + 1) {
                        add(DirtySpan(start, previous + 1))
                        start = row
                    }
                    previous = row
                }
                add(DirtySpan(start, previous + 1))
            }
        }
        val bytes = spans.sumOf { it.endExclusive - it.start } * bytesPerRow
        val plan = RendererFramePlan(mode, spans, minOf(bytes, maxUploadBytes), reset, rowCount, rebuild)
        dirtyRows.removeAll(selectedRows)
        reset = false
        rebuild = false
        return plan
    }

    fun hitTest(x: Float, y: Float, z: Float, radius: Float = 0.05f): String? {
        val radiusSquared = radius * radius
        return slotsByKey.entries.mapNotNull { entry ->
            val row = rows[entry.value] ?: return@mapNotNull null
            val dx = row.x - x
            val dy = row.y - y
            val dz = row.z - z
            val distance = dx * dx + dy * dy + dz * dz
            if (distance <= radiusSquared) entry.key to distance else null
        }.minByOrNull { it.second }?.first
    }

    fun accessibilityLabel(key: String): String {
        val row = slotsByKey[key]?.let(rows::get) ?: return "Surface unavailable"
        return when (row.state) {
            SemanticState.UNCOVERED -> "Surface uncovered"
            SemanticState.COVERED -> "Surface covered"
            SemanticState.PENDING -> "Surface coverage pending"
            SemanticState.STALE -> "Surface coverage stale"
            SemanticState.DEGRADED -> "Surface visualization degraded"
        }
    }
}
