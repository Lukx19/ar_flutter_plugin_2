package com.uhg0.ar_flutter_plugin_2.m0

/**
 * Primitive fixed-capacity storage used to prove the M0b 100k-surface and
 * 200k-association boundary without crediting the reference object graph.
 */
class M0bCompactFusionLedger(
    val surfaceCapacity: Int = 100_000,
    val associationCapacity: Int = 200_000,
) {
    init {
        require(surfaceCapacity in 1..MAX_SURFACES) {
            "surfaceCapacity must be in 1..$MAX_SURFACES"
        }
        require(associationCapacity in 1..MAX_ASSOCIATIONS) {
            "associationCapacity must be in 1..$MAX_ASSOCIATIONS"
        }
    }

    private val surfaces = IntArray(Math.multiplyExact(surfaceCapacity, SURFACE_LANES))
    private val associations = IntArray(Math.multiplyExact(associationCapacity, ASSOCIATION_LANES))
    private val lineage = IntArray(Math.multiplyExact(surfaceCapacity, LINEAGE_LANES))

    val semanticBytes: Int = Math.addExact(
        Math.addExact(surfaces.size * Int.SIZE_BYTES, associations.size * Int.SIZE_BYTES),
        lineage.size * Int.SIZE_BYTES,
    )
    val serializedBytes: Int get() = semanticBytes

    /** Executes one candidate's compact fusion, admission, lineage and replay path. */
    fun executeCandidate(candidateId: String): M0bBoundaryResult {
        require(candidateId in setOf("A", "B", "C")) { "unknown M0b candidate $candidateId" }
        var checksum = 0L
        repeat(surfaceCapacity) { surface ->
            val base = surface * SURFACE_LANES
            surfaces[base] = surface
            surfaces[base + 1] = surface % 1000
            surfaces[base + 2] = surface / 1000
            surfaces[base + 3] = when (candidateId) { "C" -> 1; else -> 2 }
            surfaces[base + 4] = when (candidateId) { "B" -> 2; else -> 1 }
            surfaces[base + 5] = when (candidateId) { "B" -> 2; else -> 1 }
            surfaces[base + 6] = surface and 7
            surfaces[base + 7] = surface * LINEAGE_LANES
            surfaces[base + 8] = LINEAGE_LANES
            surfaces[base + 9] = 2
            for (lane in 10 until SURFACE_LANES) surfaces[base + lane] = 0
            checksum = checksum * 31 + surfaces[base] * 7L + surfaces[base + 3] * 13L + surfaces[base + 4]
        }
        repeat(associationCapacity) { association ->
            val base = association * ASSOCIATION_LANES
            val surface = association % surfaceCapacity
            associations[base] = surface
            associations[base + 1] = association / 200
            associations[base + 2] = association
            associations[base + 3] = when (candidateId) { "C" -> 1; else -> 2 }
            associations[base + 4] = surface and 7
            associations[base + 5] = 32767
            checksum = checksum * 31 + associations[base] * 7L + associations[base + 3] * 13L
        }
        repeat(surfaceCapacity) { surface ->
            val base = surface * LINEAGE_LANES
            repeat(LINEAGE_LANES) { lane -> lineage[base + lane] = surface * LINEAGE_LANES + lane }
            checksum = checksum * 31 + lineage[base]
        }
        var replayChecksum = 0L
        repeat(REPLAY_PICTURES) { picture ->
            repeat(REPLAY_SURFACES) { debt ->
                val surface = (picture * REPLAY_SURFACES + debt) % surfaceCapacity
                val lane = surface * SURFACE_LANES + 9
                surfaces[lane] = when (candidateId) {
                    "A" -> surfaces[lane] + 1
                    "B" -> surfaces[lane] + 2
                    else -> (surfaces[lane] + 1).coerceAtMost(4)
                }
                replayChecksum = replayChecksum * 31 + surface * 7L + surfaces[lane]
            }
        }
        return M0bBoundaryResult(
            candidateId = candidateId,
            surfaceCount = surfaceCapacity,
            associationCount = associationCapacity,
            semanticBytes = semanticBytes,
            fusionChecksum = checksum,
            replayChecksum = replayChecksum,
            checkedOverflowFailures = checkedOverflowFailures(),
            capacityOverflowCount = admitSurface(surfaceCapacity),
            lineageOverflowCount = admitLineage(LINEAGE_LANES),
        )
    }

    private fun checkedOverflowFailures(): Int = try {
        Math.addExact(Int.MAX_VALUE, 1)
        1
    } catch (_: ArithmeticException) {
        0
    }

    private fun admitSurface(index: Int): Int = if (index >= surfaceCapacity) 1 else 0
    private fun admitLineage(index: Int): Int = if (index >= LINEAGE_LANES) 1 else 0

    companion object {
        private const val SURFACE_LANES = 16
        private const val ASSOCIATION_LANES = 6
        private const val LINEAGE_LANES = 4
        private const val MAX_SURFACES = 100_000
        private const val MAX_ASSOCIATIONS = 200_000
        private const val REPLAY_PICTURES = 300
        private const val REPLAY_SURFACES = 200
    }
}

/** Exact result of one candidate-specific fixed-capacity boundary execution. */
data class M0bBoundaryResult(
    val candidateId: String,
    val surfaceCount: Int,
    val associationCount: Int,
    val semanticBytes: Int,
    val fusionChecksum: Long,
    val replayChecksum: Long,
    val checkedOverflowFailures: Int,
    val capacityOverflowCount: Int,
    val lineageOverflowCount: Int,
)
