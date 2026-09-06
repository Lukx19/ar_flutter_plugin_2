package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/** Explicit work limits for one authenticated canonical-current borrow. */
internal data class BoundedCanonicalLookupRequest(
    val expectedGeometryRevision: Long,
    val expectedLineageRevision: Long,
    val maximumDirectLookups: Int,
    val maximumRayCellVisits: Int,
    val maximumPageReads: Int,
    val maximumBytesRead: Long,
)

/** Actual work charged by one bounded canonical-current borrow. */
internal data class BoundedCanonicalLookupReceipt(
    val directLookups: Int,
    val rayCellVisits: Int,
    val pageReads: Int,
    val bytesRead: Long,
    val refusedByLimit: Boolean,
)

internal enum class BoundedCanonicalLookupReason {
    INVALID_REQUEST,
    CURRENT_UNAVAILABLE,
    REVISION_CONFLICT,
    LIMIT_EXHAUSTED,
}

internal sealed interface BoundedCanonicalLookupResult<out T> {
    val receipt: BoundedCanonicalLookupReceipt

    data class Completed<T>(
        val value: T,
        override val receipt: BoundedCanonicalLookupReceipt,
    ) : BoundedCanonicalLookupResult<T>

    data class Refused(
        val reason: BoundedCanonicalLookupReason,
        override val receipt: BoundedCanonicalLookupReceipt,
    ) : BoundedCanonicalLookupResult<Nothing>
}

/**
 * A bounded adapter over one complete canonical cut.  It keeps no row index of
 * its own and never exposes the underlying iterator or page collections.
 */
internal class BoundedCanonicalCurrentView internal constructor(
    private val delegate: CanonicalStateView,
    private val request: BoundedCanonicalLookupRequest,
    private val voxelMicrometers: Int,
) : BoundedCanonicalSurfaceView {
    private var directLookups = 0L
    private var rayCellVisits = 0L
    private var pageReads = 0L
    private var bytesRead = 0L
    private var refusedByLimit = false

    override val revisionPair = CanonicalRevisionPair(
        delegate.cut.geometryRevision,
        delegate.cut.lineageRevision,
    )
    override val surfaceCount: Int get() = delegate.cut.liveSurfaceCount

    override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? {
        if (!chargeDirectLookup()) return null
        val row = readDirect { delegate.findById(id) } ?: return null
        if (refusedByLimit) return null
        return surface(row)
    }

    override fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface? {
        if (!chargeDirectLookup()) return null
        val row = readDirect { delegate.findByVoxel(voxel) } ?: return null
        if (refusedByLimit || row.voxel != voxel) return null
        return AddressedCanonicalSurface(voxel, surface(row))
    }

    override fun visitRayCells(
        startGroupMm: DepthPointMm,
        endpointGroupMm: DepthPointMm,
        maximumVisits: Int,
        visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
    ): DepthRayVisitResult {
        if (maximumVisits !in 0..65_536 || voxelMicrometers <= 0) {
            return DepthRayVisitResult(0, arithmeticOverflow = true)
        }
        if (refusedByLimit) return DepthRayVisitResult(0, truncated = true)
        val remaining = request.maximumRayCellVisits.toLong() - rayCellVisits
        if (remaining <= 0L) {
            refusedByLimit = true
            return DepthRayVisitResult(0, truncated = true)
        }
        val allowed = minOf(maximumVisits.toLong(), remaining).toInt()
        val result = DepthRaySupercover.visit(
            startGroupMm, endpointGroupMm, voxelMicrometers, allowed,
        ) { voxel ->
            rayCellVisits++
            val row = lookupVoxelForRay(voxel)
            if (refusedByLimit) return@visit false
            visitor(voxel, row?.let(::surface))
        }
        if (result.truncated &&
            (request.maximumRayCellVisits.toLong() <= maximumVisits.toLong() || remaining < maximumVisits.toLong())
        ) refusedByLimit = true
        return result
    }

    internal fun <T> result(value: T): BoundedCanonicalLookupResult<T> {
        val receipt = receipt()
        return if (refusedByLimit) {
            BoundedCanonicalLookupResult.Refused(BoundedCanonicalLookupReason.LIMIT_EXHAUSTED, receipt)
        } else {
            BoundedCanonicalLookupResult.Completed(value, receipt)
        }
    }

    internal fun receipt() = BoundedCanonicalLookupReceipt(
        directLookups = directLookups.toIntExactOrLimit(),
        rayCellVisits = rayCellVisits.toIntExactOrLimit(),
        pageReads = pageReads.toIntExactOrLimit(),
        bytesRead = bytesRead,
        refusedByLimit = refusedByLimit,
    )

    private fun chargeDirectLookup(): Boolean {
        if (refusedByLimit || directLookups >= request.maximumDirectLookups.toLong()) {
            refusedByLimit = true
            return false
        }
        directLookups++
        return true
    }

    private fun lookupVoxelForRay(voxel: Voxel): CompactSurface? {
        return readDirect { delegate.findByVoxel(voxel) }
    }

    private inline fun <T> readDirect(read: () -> T): T {
        val before = delegate.readWorkReceipt()
        val value = read()
        recordWork(before, delegate.readWorkReceipt())
        return value
    }

    private fun surface(row: CompactSurface): DepthCanonicalSurface {
        val before = delegate.readWorkReceipt()
        val lineage = lineageCount(row.id)
        recordWork(before, delegate.readWorkReceipt())
        return DepthCanonicalSurface(
            row.id,
            row.voxel,
            row.packedNormal,
            row.normalConfidence,
            lineage,
        )
    }

    private fun lineageCount(id: SurfaceId): Int {
        var count = 0
        var cursor: LineageCursor? = null
        do {
            when (val read = delegate.visitLineage(id, cursor) { count++; true }) {
                is LineageRead.Refused -> return count.coerceAtMost(0xffff)
                is LineageRead.Complete -> cursor = read.nextCursor
            }
        } while (cursor != null && count < 0xffff)
        return count
    }

    private fun recordWork(before: CanonicalReadWork, after: CanonicalReadWork) {
        val pageDelta = after.pageReads - before.pageReads
        val byteDelta = after.bytesRead - before.bytesRead
        pageReads = try { Math.addExact(pageReads, pageDelta) } catch (_: ArithmeticException) { Long.MAX_VALUE }
        bytesRead = try { Math.addExact(bytesRead, byteDelta) } catch (_: ArithmeticException) { Long.MAX_VALUE }
        if (pageReads > request.maximumPageReads.toLong() || bytesRead > request.maximumBytesRead) {
            refusedByLimit = true
        }
    }

    private fun Long.toIntExactOrLimit(): Int = when {
        this < 0L -> 0
        this > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
        else -> toInt()
    }
}
