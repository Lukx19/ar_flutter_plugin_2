package com.uhg0.ar_flutter_plugin_2.m0

enum class M0RendererMode { CENTROIDS, CUBES, RAW_POINTS, OVERVIEW }
enum class M0SemanticState { UNCOVERED, COVERED, PENDING, STALE, DEGRADED }

data class M0CentroidRow(
    val qualifiedKey: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val state: M0SemanticState,
    val alpha: Float = 1f,
)

data class M0DirtySpan(val start: Int, val endExclusive: Int)

data class M0RendererFramePlan(
    val mode: M0RendererMode,
    val dirtySpans: List<M0DirtySpan>,
    val uploadBytes: Int,
    val reset: Boolean,
    val rowCount: Int,
    val rebuild: Boolean = false,
)

class M0CentroidRendererState(
    private val capacity: Int,
    private val bytesPerRow: Int = 32,
    private val maxUploadBytes: Int = 64 * 1024,
) {
    init {
        require(capacity > 0 && bytesPerRow > 0 && maxUploadBytes > 0)
    }

    private val slotsByKey = linkedMapOf<String, Int>()
    private val rows = mutableListOf<M0CentroidRow?>()
    private val dirtyRows = sortedSetOf<Int>()
    private var reset = true
    private var rebuild = false
    var isContextLost: Boolean = false
        private set
    var mode: M0RendererMode = M0RendererMode.CENTROIDS
        private set

    val rowCount: Int get() = slotsByKey.size

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

    fun setMode(value: M0RendererMode) {
        if (mode == value) return
        mode = value
        reset = true
        dirtyRows += slotsByKey.values
    }

    fun upsert(row: M0CentroidRow): Boolean {
        val existing = slotsByKey[row.qualifiedKey]
        if (existing != null) {
            if (rows[existing] == row) return false
            rows[existing] = row
            dirtyRows += existing
            return true
        }
        if (rowCount >= capacity) return false
        val slot = rows.indexOfFirst { it == null }.let { if (it == -1) rows.size else it }
        if (slot == rows.size) rows += null
        slotsByKey[row.qualifiedKey] = slot
        rows[slot] = row
        dirtyRows += slot
        return true
    }

    fun remove(key: String): Boolean {
        val slot = slotsByKey.remove(key) ?: return false
        rows[slot] = null
        dirtyRows += slot
        return true
    }

    fun flush(): M0RendererFramePlan {
        val selectedRows = dirtyRows.take(maxUploadBytes / bytesPerRow).toSet()
        val spans = selectedRows.toList().let { dirty ->
            if (dirty.isEmpty()) emptyList() else buildList {
                var start = dirty.first()
                var previous = start
                dirty.drop(1).forEach { row ->
                    if (row != previous + 1) {
                        add(M0DirtySpan(start, previous + 1))
                        start = row
                    }
                    previous = row
                }
                add(M0DirtySpan(start, previous + 1))
            }
        }
        val bytes = spans.sumOf { it.endExclusive - it.start } * bytesPerRow
        val plan = M0RendererFramePlan(mode, spans, minOf(bytes, maxUploadBytes), reset, rowCount, rebuild)
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
            M0SemanticState.UNCOVERED -> "Surface uncovered"
            M0SemanticState.COVERED -> "Surface covered"
            M0SemanticState.PENDING -> "Surface coverage pending"
            M0SemanticState.STALE -> "Surface coverage stale"
            M0SemanticState.DEGRADED -> "Surface visualization degraded"
        }
    }
}
