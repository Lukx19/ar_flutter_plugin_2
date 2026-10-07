package com.uhg0.ar_flutter_plugin_2.sceneview

/** Two physical GPU/page generations, including retired and unknown submissions. */
internal class CoverageShapeResourceBudget(
    private val ownedBytes: () -> Long,
    private val charge: (String, Int) -> Unit,
    private val release: (String) -> Unit,
) {
    private data class Reservation(val bytes: Int, var owner: Any? = null)
    private val reservations = LinkedHashMap<CoverageResourceToken, Reservation>()
    var maximumGenerations = 0
        private set

    @Synchronized fun reserve(token: CoverageResourceToken, bytes: Int,
        activeOtherBytes: Long, candidateBaseBytes: Long): Boolean {
        require(bytes >= 0 && activeOtherBytes >= 0 && candidateBaseBytes >= 0)
        if (reservations.containsKey(token)) return false
        if (reservations.size >= 2 || activeOtherBytes + candidateBaseBytes + bytes >
            CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES ||
            ownedBytes() + candidateBaseBytes + bytes > CoverageRendererLimits.COMBINED_RENDERER_OWNED_LIMIT_BYTES) return false
        reservations[token] = Reservation(bytes)
        charge(ownerName(token), bytes)
        maximumGenerations = maxOf(maximumGenerations, reservations.size)
        return true
    }

    /** Strong retention ends only after GPU destruction and actual page drain. */
    @Synchronized fun attach(token: CoverageResourceToken, owner: Any) {
        checkNotNull(reservations[token]).owner = owner
    }
    @Synchronized fun drained(token: CoverageResourceToken) {
        if (reservations.remove(token) != null) release(ownerName(token))
    }
    @Synchronized fun contains(token: CoverageResourceToken): Boolean = reservations.containsKey(token)
    @Synchronized fun snapshot(): Map<String, Any> = mapOf(
        "shapeResourceGenerations" to reservations.size,
        "maximumShapeResourceGenerations" to maximumGenerations,
        "shapeGpuAndPageReservedBytes" to reservations.values.sumOf { it.bytes },
        "actualHeapMeasured" to false,
    )
    private fun ownerName(token: CoverageResourceToken) = "coverage-shape-resource-${token.epoch}"
    companion object {
        const val PAGE_BYTES = 32 * 1024
        const val MODELED_OWNER_ALLOWANCE_BYTES = 512
        fun bytes(shape: CoveragePresentationMode, rows: Int): Int {
            require(rows in 1..if (shape == CoveragePresentationMode.DIRECTIONAL_BALLS) 256 else 4096)
            return rows * (if (shape == CoveragePresentationMode.DIRECTIONAL_BALLS) 3860 else CoveragePlanarPatchGeometry.GPU_BYTES_PER_PATCH + 20) +
                PAGE_BYTES + MODELED_OWNER_ALLOWANCE_BYTES
        }
    }
}
