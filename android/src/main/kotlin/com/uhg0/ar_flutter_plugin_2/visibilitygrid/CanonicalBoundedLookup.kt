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
    CANONICAL_READ_FAILURE,
    LINEAGE_UNREPRESENTABLE,
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
    private var failureReason: BoundedCanonicalLookupReason? = null

    override val revisionPair = CanonicalRevisionPair(
        delegate.cut.geometryRevision,
        delegate.cut.lineageRevision,
    )
    override val surfaceCount: Int get() = delegate.cut.liveSurfaceCount

    override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? {
        if (!chargeDirectLookup()) return null
        val row = readDirect { remainingPages, remainingBytes ->
            delegate.findByIdBounded(id, remainingPages, remainingBytes)
        } ?: return null
        if (refusedByLimit || failureReason != null) return null
        return surface(row)
    }

    override fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface? {
        if (!chargeDirectLookup()) return null
        val row = readDirect { remainingPages, remainingBytes ->
            delegate.findByVoxelBounded(voxel, remainingPages, remainingBytes)
        } ?: return null
        if (refusedByLimit || failureReason != null || row.voxel != voxel) return null
        return surface(row)?.let { AddressedCanonicalSurface(voxel, it) }
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
            if (refusedByLimit || failureReason != null) return@visit false
            val surface = row?.let(::surface)
            if (refusedByLimit || failureReason != null) return@visit false
            visitor(voxel, surface)
        }
        if (result.truncated &&
            (request.maximumRayCellVisits.toLong() <= maximumVisits.toLong() || remaining < maximumVisits.toLong())
        ) refusedByLimit = true
        return result
    }

    internal fun <T> result(value: T): BoundedCanonicalLookupResult<T> {
        val receipt = receipt()
        val reason = failureReason
        return if (reason != null) {
            BoundedCanonicalLookupResult.Refused(reason, receipt)
        } else if (refusedByLimit) {
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
        return readDirect { remainingPages, remainingBytes ->
            delegate.findByVoxelBounded(voxel, remainingPages, remainingBytes)
        }
    }

    private inline fun <T> readDirect(
        read: (remainingPages: Long, remainingBytes: Long) -> CanonicalBoundedReadResult<T>,
    ): T? {
        val operation = read(remainingPageBudget(), remainingByteBudget())
        return when (operation) {
            is CanonicalBoundedReadResult.Complete -> {
                if (!acceptWork(operation.work)) {
                    failureReason = BoundedCanonicalLookupReason.CANONICAL_READ_FAILURE
                    null
                } else operation.value
            }
            is CanonicalBoundedReadResult.Refused -> {
                if (!acceptWork(operation.work)) {
                    failureReason = BoundedCanonicalLookupReason.CANONICAL_READ_FAILURE
                } else {
                    when (operation.reason) {
                        CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED -> refusedByLimit = true
                        CanonicalBoundedReadRefusal.CANONICAL_READ_FAILURE ->
                            failureReason = BoundedCanonicalLookupReason.CANONICAL_READ_FAILURE
                    }
                }
                null
            }
        }
    }

    private fun surface(row: CompactSurface): DepthCanonicalSurface? {
        val lineage = lineageCount(row.id) ?: return null
        return DepthCanonicalSurface(
            row.id,
            row.voxel,
            row.packedNormal,
            row.normalConfidence,
            lineage,
        )
    }

    private fun lineageCount(id: SurfaceId): Int? {
        var count = 0
        var cursor: LineageCursor? = null
        do {
            val read = readLineage(id, cursor) {
                if (count >= MAX_LINEAGE_COUNT) {
                    failureReason = BoundedCanonicalLookupReason.LINEAGE_UNREPRESENTABLE
                    false
                } else {
                    count++
                    true
                }
            } ?: return null
            when (read) {
                is LineageRead.Refused -> {
                    failureReason = BoundedCanonicalLookupReason.CANONICAL_READ_FAILURE
                    return null
                }
                is LineageRead.Complete -> cursor = read.nextCursor
            }
        } while (cursor != null && failureReason == null)
        return count
    }

    private fun readLineage(
        id: SurfaceId,
        cursor: LineageCursor?,
        sink: (LineageEdge) -> Boolean,
    ): LineageRead? {
        val operation = delegate.visitLineageBounded(
            id, cursor, remainingPageBudget(), remainingByteBudget(), sink,
        )
        return when (operation) {
            is CanonicalBoundedReadResult.Complete -> {
                if (!acceptWork(operation.work)) {
                    failureReason = BoundedCanonicalLookupReason.CANONICAL_READ_FAILURE
                    null
                } else operation.value
            }
            is CanonicalBoundedReadResult.Refused -> {
                if (!acceptWork(operation.work)) {
                    failureReason = BoundedCanonicalLookupReason.CANONICAL_READ_FAILURE
                } else {
                    when (operation.reason) {
                        CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED -> refusedByLimit = true
                        CanonicalBoundedReadRefusal.CANONICAL_READ_FAILURE ->
                            failureReason = BoundedCanonicalLookupReason.CANONICAL_READ_FAILURE
                    }
                }
                null
            }
        }
    }

    private fun remainingPageBudget() = request.maximumPageReads.toLong() - pageReads
    private fun remainingByteBudget() = request.maximumBytesRead - bytesRead

    private fun acceptWork(work: CanonicalReadWork): Boolean {
        if (work.pageReads < 0 || work.bytesRead < 0 ||
            work.pageReads > remainingPageBudget() || work.bytesRead > remainingByteBudget()
        ) return false
        pageReads = try { Math.addExact(pageReads, work.pageReads) } catch (_: ArithmeticException) { return false }
        bytesRead = try { Math.addExact(bytesRead, work.bytesRead) } catch (_: ArithmeticException) { return false }
        return true
    }

    private fun Long.toIntExactOrLimit(): Int = when {
        this < 0L -> 0
        this > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
        else -> toInt()
    }

    private companion object {
        const val MAX_LINEAGE_COUNT = 0xffff
    }
}
