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
        require(surfaceCapacity > 0 && associationCapacity > 0)
    }

    private val surfaces = IntArray(Math.multiplyExact(surfaceCapacity, SURFACE_LANES))
    private val associations = IntArray(Math.multiplyExact(associationCapacity, ASSOCIATION_LANES))
    private val lineage = IntArray(Math.multiplyExact(surfaceCapacity, LINEAGE_LANES))

    val semanticBytes: Int = Math.addExact(
        Math.addExact(surfaces.size * Int.SIZE_BYTES, associations.size * Int.SIZE_BYTES),
        lineage.size * Int.SIZE_BYTES,
    )
    val serializedBytes: Int get() = semanticBytes

    fun populateDeterministically(): Long {
        var checksum = 0L
        surfaces.indices.forEach { index ->
            surfaces[index] = index
            checksum = checksum xor surfaces[index].toLong()
        }
        associations.indices.forEach { index ->
            associations[index] = index * 31
            checksum = checksum xor associations[index].toLong()
        }
        lineage.indices.forEach { index ->
            lineage[index] = index * 17
            checksum = checksum xor lineage[index].toLong()
        }
        return checksum
    }

    companion object {
        private const val SURFACE_LANES = 16
        private const val ASSOCIATION_LANES = 6
        private const val LINEAGE_LANES = 4
    }
}
