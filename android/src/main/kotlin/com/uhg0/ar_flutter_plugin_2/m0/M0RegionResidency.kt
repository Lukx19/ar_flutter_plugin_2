package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class M0RegionCoordinate(val x: Int, val y: Int, val z: Int) : Comparable<M0RegionCoordinate> {
    override fun compareTo(other: M0RegionCoordinate): Int =
        compareValuesBy(this, other, M0RegionCoordinate::x, M0RegionCoordinate::y, M0RegionCoordinate::z)

    fun pageForMillimetres(px: Int, py: Int, pz: Int): M0PageCoordinate = M0PageCoordinate(
        this,
        floorDiv(px - x * regionEdgeMillimetres, pageEdgeMillimetres),
        floorDiv(py - y * regionEdgeMillimetres, pageEdgeMillimetres),
        floorDiv(pz - z * regionEdgeMillimetres, pageEdgeMillimetres),
    )

    fun neighbors26(): List<M0RegionCoordinate> = buildList {
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            if (dx != 0 || dy != 0 || dz != 0) add(M0RegionCoordinate(x + dx, y + dy, z + dz))
        }
    }
}

data class M0PageCoordinate(val region: M0RegionCoordinate, val x: Int, val y: Int, val z: Int) : Comparable<M0PageCoordinate> {
    override fun compareTo(other: M0PageCoordinate): Int = compareValuesBy(
        this,
        other,
        M0PageCoordinate::region,
        M0PageCoordinate::x,
        M0PageCoordinate::y,
        M0PageCoordinate::z,
    )

    val isValidLocalPage: Boolean get() = x in 0..2 && y in 0..2 && z in 0..2
    val linearIndex: Int get() {
        require(isValidLocalPage)
        return x + 3 * (y + 3 * z)
    }
}

data class M0ResolvedResidencyCoordinate(
    val xMillimetres: Int,
    val yMillimetres: Int,
    val zMillimetres: Int,
    val owner: M0RegionCoordinate,
    val page: M0PageCoordinate,
)

data class M0ResidencyDecision(
    val accepted: Boolean,
    val active: List<M0RegionCoordinate>,
    val reason: String? = null,
)

/** The common signed-owner residency seam used by every M0c policy drain. */
class M0ResidencyModel(private val maxResidentRegions: Int) {
    private val activeOwners = sortedSetOf<M0RegionCoordinate>()

    init {
        require(maxResidentRegions > 0)
    }

    val activeRegions: List<M0RegionCoordinate> get() = activeOwners.toList()

    fun resolveMillimetres(x: Int, y: Int, z: Int): M0ResolvedResidencyCoordinate {
        val owner = m0RegionForMillimetres(x, y, z)
        val page = owner.pageForMillimetres(x, y, z)
        require(page.isValidLocalPage)
        return M0ResolvedResidencyCoordinate(x, y, z, owner, page)
    }

    fun submitDemand(requested: Collection<M0RegionCoordinate>): M0ResidencyDecision {
        val demanded = requested.toSortedSet()
        if (demanded.size > maxResidentRegions) {
            return M0ResidencyDecision(false, activeRegions, "densityExceeded")
        }
        activeOwners.clear()
        activeOwners.addAll(demanded)
        return M0ResidencyDecision(true, activeRegions)
    }
}

fun m0RegionForMillimetres(x: Int, y: Int, z: Int): M0RegionCoordinate = M0RegionCoordinate(
    floorDiv(x, regionEdgeMillimetres),
    floorDiv(y, regionEdgeMillimetres),
    floorDiv(z, regionEdgeMillimetres),
)

private fun floorDiv(numerator: Int, denominator: Int): Int = Math.floorDiv(numerator, denominator)

const val regionEdgeMillimetres = 3000
const val pageEdgeMillimetres = 1000

data class M0CanonicalRegionRow(
    val surfaceId: Long,
    val xMillimetres: Int,
    val yMillimetres: Int,
    val zMillimetres: Int,
    val semanticRevision: Long,
)

object M0CanonicalRegionShardCodec {
    private const val headerBytes = 72
    private const val rowBytes = 28

    fun encode(
        region: M0RegionCoordinate,
        generation: Long,
        geometryRevision: Long,
        rows: List<M0CanonicalRegionRow>,
    ): ByteArray {
        require(generation > 0 && geometryRevision >= 0 && rows.size <= 100_000)
        val sorted = rows.sortedBy { it.surfaceId }
        require(sorted.zipWithNext().none { it.first.surfaceId == it.second.surfaceId })
        val packet = ByteArray(headerBytes + sorted.size * rowBytes)
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        packet.magic("RCS1")
        data.putShort(4, 1)
        data.putShort(6, headerBytes.toShort())
        data.putInt(8, region.x)
        data.putInt(12, region.y)
        data.putInt(16, region.z)
        data.putLong(20, generation)
        data.putLong(28, geometryRevision)
        data.putInt(36, sorted.size)
        data.putInt(40, 0)
        var offset = headerBytes
        sorted.forEach { row ->
            require(row.surfaceId in 1..0xffffffffL && row.semanticRevision >= 0)
            data.putInt(offset, row.surfaceId.toInt())
            data.putInt(offset + 4, row.xMillimetres)
            data.putInt(offset + 8, row.yMillimetres)
            data.putInt(offset + 12, row.zMillimetres)
            data.putLong(offset + 16, row.semanticRevision)
            data.putInt(offset + 24, 0)
            offset += rowBytes
        }
        val digest = sha256(packet.copyOfRange(headerBytes, packet.size))
        digest.copyInto(packet, 40)
        return packet
    }

    fun decode(packet: ByteArray, expectedRegion: M0RegionCoordinate, expectedGeneration: Long): List<M0CanonicalRegionRow> {
        require(packet.size >= headerBytes && (packet.size - headerBytes) % rowBytes == 0)
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        require(packet.magicIs("RCS1"))
        require(data.getShort(4).toInt() and 0xffff == 1)
        require(data.getShort(6).toInt() and 0xffff == headerBytes)
        require(data.getInt(8) == expectedRegion.x && data.getInt(12) == expectedRegion.y && data.getInt(16) == expectedRegion.z)
        require(data.getLong(20) == expectedGeneration)
        val count = data.getInt(36)
        require(count >= 0 && headerBytes + count * rowBytes == packet.size)
        require(sha256(packet.copyOfRange(headerBytes, packet.size)).contentEquals(packet.copyOfRange(40, 72)))
        var previousId = 0L
        var offset = headerBytes
        return buildList {
            repeat(count) {
                val id = data.getInt(offset).toLong() and 0xffffffffL
                require(id > previousId)
                previousId = id
                require(data.getInt(offset + 24) == 0)
                add(M0CanonicalRegionRow(id, data.getInt(offset + 4), data.getInt(offset + 8), data.getInt(offset + 12), data.getLong(offset + 16)))
                offset += rowBytes
            }
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.magic(value: String) = value.toByteArray(Charsets.US_ASCII).copyInto(this)
    private fun ByteArray.magicIs(value: String): Boolean = value.toByteArray(Charsets.US_ASCII).contentEquals(copyOfRange(0, 4))
}
