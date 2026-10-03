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
) {
    companion object {
        /** Portable shallow owner retained by the integration after one depth lookup. */
        const val PORTABLE_BYTES = 40L
    }
}

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
 * Primitive row cache for one serial bounded borrow.
 *
 * The cache is owned by the serial canonical runtime and is reset between
 * borrows.  A lookup can therefore reuse the row obtained by an endpoint
 * lookup when the same row is immediately checked by id, without retaining a
 * mutable view across cut revisions or allocating boxed voxel map keys on the
 * ray path.  The two open-address tables are deliberately bounded; a full
 * cache falls back to the authenticated delegate and never changes semantics.
 */
internal class CanonicalBoundedSurfaceCache(
    private val entryCapacity: Int = ENTRY_CAPACITY,
    private val tableCapacity: Int = TABLE_CAPACITY,
) {
    init {
        require(entryCapacity > 0 && entryCapacity * 2 <= tableCapacity)
        require(tableCapacity and (tableCapacity - 1) == 0)
    }

    private val entryEpoch = IntArray(entryCapacity)
    private val entryIds = LongArray(entryCapacity)
    private val sourceX = IntArray(entryCapacity)
    private val sourceY = IntArray(entryCapacity)
    private val sourceZ = IntArray(entryCapacity)
    private val addressedX = IntArray(entryCapacity)
    private val addressedY = IntArray(entryCapacity)
    private val addressedZ = IntArray(entryCapacity)
    private val normals = IntArray(entryCapacity)
    private val confidences = IntArray(entryCapacity)
    private val lineages = IntArray(entryCapacity)

    private val voxelSlots = IntArray(tableCapacity)
    private val voxelX = IntArray(tableCapacity)
    private val voxelY = IntArray(tableCapacity)
    private val voxelZ = IntArray(tableCapacity)
    private val idSlots = IntArray(tableCapacity)
    private val idKeys = LongArray(tableCapacity)
    private val tableMask = tableCapacity - 1
    private var epoch = 0
    private var nextEntry = 0

    internal fun beginBorrow(@Suppress("UNUSED_PARAMETER") revision: CanonicalRevisionPair) {
        // The revision is part of the owner-side borrow fence.  The caller
        // validates it before constructing this view; this cache only needs a
        // new epoch so stale indexes can never be observed by a later cut.
        epoch = if (epoch == Int.MAX_VALUE) {
            entryEpoch.fill(0)
            1
        } else {
            epoch + 1
        }
        nextEntry = 0
        voxelSlots.fill(0)
        idSlots.fill(0)
    }

    internal fun readByVoxel(x: Int, y: Int, z: Int, scratch: CanonicalSurfaceScratch): Boolean {
        var slot = voxelSlot(x, y, z)
        while (voxelSlots[slot] != 0) {
            val entry = voxelSlots[slot] - 1
            if (entryEpoch[entry] == epoch && voxelX[slot] == x && voxelY[slot] == y && voxelZ[slot] == z) {
                write(entry, scratch)
                return true
            }
            slot = (slot + 1) and tableMask
        }
        return false
    }

    internal fun readById(id: Long, scratch: CanonicalSurfaceScratch): Boolean {
        var slot = idSlot(id)
        while (idSlots[slot] != 0) {
            val entry = idSlots[slot] - 1
            if (entryEpoch[entry] == epoch && idKeys[slot] == id) {
                write(entry, scratch)
                return true
            }
            slot = (slot + 1) and tableMask
        }
        return false
    }

    internal fun remember(scratch: CanonicalSurfaceScratch) {
        if (!scratch.present || scratch.id <= 0L || nextEntry >= entryCapacity) return
        var voxel = voxelSlot(scratch.addressedVoxelX, scratch.addressedVoxelY, scratch.addressedVoxelZ)
        while (voxelSlots[voxel] != 0) {
            val entry = voxelSlots[voxel] - 1
            if (entryEpoch[entry] == epoch && voxelX[voxel] == scratch.addressedVoxelX &&
                voxelY[voxel] == scratch.addressedVoxelY && voxelZ[voxel] == scratch.addressedVoxelZ
            ) {
                writeEntry(entry, scratch)
                return
            }
            voxel = (voxel + 1) and tableMask
        }
        val entry = nextEntry++
        writeEntry(entry, scratch)
        voxelSlots[voxel] = entry + 1
        voxelX[voxel] = scratch.addressedVoxelX
        voxelY[voxel] = scratch.addressedVoxelY
        voxelZ[voxel] = scratch.addressedVoxelZ

        var id = idSlot(scratch.id)
        while (idSlots[id] != 0) {
            val existing = idSlots[id] - 1
            if (entryEpoch[existing] == epoch && idKeys[id] == scratch.id) return
            id = (id + 1) and tableMask
        }
        idSlots[id] = entry + 1
        idKeys[id] = scratch.id
    }

    private fun writeEntry(entry: Int, scratch: CanonicalSurfaceScratch) {
        entryEpoch[entry] = epoch
        entryIds[entry] = scratch.id
        sourceX[entry] = scratch.voxelX
        sourceY[entry] = scratch.voxelY
        sourceZ[entry] = scratch.voxelZ
        addressedX[entry] = scratch.addressedVoxelX
        addressedY[entry] = scratch.addressedVoxelY
        addressedZ[entry] = scratch.addressedVoxelZ
        normals[entry] = scratch.packedNormal
        confidences[entry] = scratch.normalConfidence
        lineages[entry] = scratch.lineageCount
    }

    private fun write(entry: Int, scratch: CanonicalSurfaceScratch) = scratch.setAddressed(
        entryIds[entry], addressedX[entry], addressedY[entry], addressedZ[entry],
        sourceX[entry], sourceY[entry], sourceZ[entry], normals[entry], confidences[entry], lineages[entry],
    )

    private fun voxelSlot(x: Int, y: Int, z: Int): Int {
        var hash = x * -0x7a143595
        hash = (hash xor y) * 0x6d2b79f5
        hash = (hash xor z) * 0x1b873593
        return (hash xor (hash ushr 16)) and tableMask
    }

    private fun idSlot(id: Long): Int {
        var hash = id xor (id ushr 33)
        hash *= -49064778989728563L
        hash = hash xor (hash ushr 33)
        return hash.toInt() and tableMask
    }

    internal fun retainedBytes(): Long = RETAINED_BYTES

    companion object {
        const val ENTRY_CAPACITY = 4_096
        const val TABLE_CAPACITY = 8_192
        // Primitive payloads plus array envelopes and this owner's header.
        const val RETAINED_BYTES = 426_432L
    }
}

/**
 * A bounded adapter over one complete canonical cut.  It keeps no row index of
 * its own and never exposes the underlying iterator or page collections.
 */
internal class BoundedCanonicalCurrentView internal constructor(
    private val delegate: CanonicalStateView,
    private val request: BoundedCanonicalLookupRequest,
    private val voxelMicrometers: Int,
    reuseAuthenticatedSurfaceReads: Boolean = false,
    private val surfaceCache: CanonicalBoundedSurfaceCache? = null,
) : BoundedCanonicalSurfaceView {
    override val supportsEmptyBlockSkipping: Boolean get() = delegate is CanonicalEmptyBlockView

    override fun isKnownEmptyBlock(blockX: Int, blockY: Int, blockZ: Int, blockVoxels: Int): Boolean =
        (delegate as? CanonicalEmptyBlockView)?.isKnownEmptyBlock(
            blockX, blockY, blockZ, revisionPair.geometryRevision, revisionPair.lineageRevision, blockVoxels,
        ) == true

    // Only authenticated immutable current cuts opt in. Fault-injection views
    // intentionally keep every read observable to the kernel tests.
    private val byVoxel = if (reuseAuthenticatedSurfaceReads) {
        HashMap<Voxel, AddressedCanonicalSurface>()
    } else null
    private val byId = if (reuseAuthenticatedSurfaceReads) {
        HashMap<SurfaceId, DepthCanonicalSurface>()
    } else null
    private var directLookups = 0L
    private var rayCellVisits = 0L
    private var pageReads = 0L
    private var bytesRead = 0L
    private var refusedByLimit = false
    private var failureReason: BoundedCanonicalLookupReason? = null
    private val objectScratch = CanonicalSurfaceScratch()

    override val revisionPair = CanonicalRevisionPair(
        delegate.cut.geometryRevision,
        delegate.cut.lineageRevision,
    )

    init {
        surfaceCache?.beginBorrow(revisionPair)
    }

    override val surfaceCount: Int get() = delegate.cut.liveSurfaceCount

    override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? {
        if (!chargeDirectLookup()) return null
        if (cacheUsable() && surfaceCache!!.readById(id.value, objectScratch)) {
            return toSurface(objectScratch)
        }
        byId?.get(id)?.let { return it }
        val row = readDirect { remainingPages, remainingBytes ->
            delegate.findByIdBounded(id, remainingPages, remainingBytes)
        } ?: return null
        if (refusedByLimit || failureReason != null) return null
        return surface(row)?.also { value ->
            if (cacheUsable()) rememberCached(row.voxel, value)
            else remember(value)
        }
    }

    override fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface? {
        if (!chargeDirectLookup()) return null
        if (cacheUsable() && surfaceCache!!.readByVoxel(voxel.x, voxel.y, voxel.z, objectScratch)) {
            return AddressedCanonicalSurface(voxel, toSurface(objectScratch))
        }
        byVoxel?.get(voxel)?.let { return it }
        val row = readDirect { remainingPages, remainingBytes ->
            delegate.findByVoxelBounded(voxel, remainingPages, remainingBytes)
        } ?: return null
        if (refusedByLimit || failureReason != null || row.voxel != voxel) return null
        return surface(row)?.let { value ->
            if (cacheUsable()) rememberCached(voxel, value) else remember(value)
            AddressedCanonicalSurface(voxel, value)
        }
    }

    override fun findSurfaceAtInto(x: Int, y: Int, z: Int, scratch: CanonicalSurfaceScratch): Boolean {
        if (!chargeDirectLookup()) {
            scratch.clear()
            return false
        }
        if (cacheUsable() && surfaceCache!!.readByVoxel(x, y, z, scratch)) return true
        val result = delegate.findByVoxelBoundedInto(
            x, y, z, remainingPageBudget(), remainingByteBudget(), scratch,
        )
        if (!acceptScalarWork(result)) {
            scratch.clear()
            return false
        }
        if (result !is CanonicalBoundedReadResult.Complete || !result.value) {
            scratch.clear()
            return false
        }
        if (scratch.addressedVoxelX != x || scratch.addressedVoxelY != y || scratch.addressedVoxelZ != z) {
            failureReason = BoundedCanonicalLookupReason.CANONICAL_READ_FAILURE
            scratch.clear()
            return false
        }
        val lineage = lineageCount(SurfaceId(scratch.id)) ?: run {
            scratch.clear()
            return false
        }
        scratch.set(
            scratch.id, scratch.voxelX, scratch.voxelY, scratch.voxelZ,
            scratch.packedNormal, scratch.normalConfidence, lineage,
        )
        scratch.setAddressed(
            scratch.id, x, y, z, scratch.voxelX, scratch.voxelY, scratch.voxelZ,
            scratch.packedNormal, scratch.normalConfidence, lineage,
        )
        if (cacheUsable()) surfaceCache!!.remember(scratch)
        return true
    }

    override fun findSurfaceByIdInto(id: Long, scratch: CanonicalSurfaceScratch): Boolean {
        if (!chargeDirectLookup()) {
            scratch.clear()
            return false
        }
        if (cacheUsable() && surfaceCache!!.readById(id, scratch)) return true
        val result = delegate.findByIdBoundedInto(
            id, remainingPageBudget(), remainingByteBudget(), scratch,
        )
        if (!acceptScalarWork(result)) {
            scratch.clear()
            return false
        }
        if (result !is CanonicalBoundedReadResult.Complete || !result.value || scratch.id != id) {
            scratch.clear()
            return false
        }
        val lineage = lineageCount(SurfaceId(id)) ?: run {
            scratch.clear()
            return false
        }
        scratch.set(
            scratch.id, scratch.voxelX, scratch.voxelY, scratch.voxelZ,
            scratch.packedNormal, scratch.normalConfidence, lineage,
        )
        if (cacheUsable()) surfaceCache!!.remember(scratch)
        return true
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
            val surface = lookupSurfaceForRay(voxel)
            if (refusedByLimit || failureReason != null) return@visit false
            visitor(voxel, surface)
        }
        if (result.truncated &&
            (request.maximumRayCellVisits.toLong() <= maximumVisits.toLong() || remaining < maximumVisits.toLong())
        ) refusedByLimit = true
        return result
    }

    override fun visitRayCellsInto(
        startGroupMm: DepthPointMm,
        endpointGroupMm: DepthPointMm,
        maximumVisits: Int,
        scratch: CanonicalSurfaceScratch,
        visitor: (Int, Int, Int, CanonicalSurfaceScratch) -> Boolean,
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
        val result = DepthRaySupercover.visitCoordinates(
            startGroupMm, endpointGroupMm, voxelMicrometers, allowed,
        ) { x, y, z ->
            rayCellVisits++
            val found = findSurfaceAtInto(x, y, z, scratch)
            if (refusedByLimit || failureReason != null) return@visitCoordinates false
            if (!found) scratch.clear()
            visitor(x, y, z, scratch)
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

    private fun acceptScalarWork(result: CanonicalBoundedReadResult<Boolean>): Boolean = when (result) {
        is CanonicalBoundedReadResult.Complete -> acceptWork(result.work)
        is CanonicalBoundedReadResult.Refused -> {
            if (!acceptWork(result.work)) {
                failureReason = BoundedCanonicalLookupReason.CANONICAL_READ_FAILURE
            } else {
                when (result.reason) {
                    CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED -> refusedByLimit = true
                    CanonicalBoundedReadRefusal.CANONICAL_READ_FAILURE ->
                        failureReason = BoundedCanonicalLookupReason.CANONICAL_READ_FAILURE
                }
            }
            false
        }
    }

    private fun lookupSurfaceForRay(voxel: Voxel): DepthCanonicalSurface? {
        if (cacheUsable() && surfaceCache!!.readByVoxel(voxel.x, voxel.y, voxel.z, objectScratch)) {
            return toSurface(objectScratch)
        }
        byVoxel?.get(voxel)?.let { return it.surface }
        val row = readDirect { remainingPages, remainingBytes ->
            delegate.findByVoxelBounded(voxel, remainingPages, remainingBytes)
        } ?: return null
        if (refusedByLimit || failureReason != null) return null
        if (row.voxel != voxel) {
            failureReason = BoundedCanonicalLookupReason.CANONICAL_READ_FAILURE
            return null
        }
        return surface(row)?.also { value ->
            if (cacheUsable()) rememberCached(voxel, value) else remember(value)
        }
    }

    private fun cacheUsable(): Boolean = surfaceCache != null &&
        CanonicalRuntimeCurrentTestHooks.failFeatureRouteRead == null

    private fun toSurface(scratch: CanonicalSurfaceScratch) = DepthCanonicalSurface(
        SurfaceId(scratch.id), Voxel(scratch.voxelX, scratch.voxelY, scratch.voxelZ),
        scratch.packedNormal, scratch.normalConfidence, scratch.lineageCount,
    )

    private fun rememberCached(addressed: Voxel, value: DepthCanonicalSurface) {
        objectScratch.setAddressed(
            value.id.value, addressed.x, addressed.y, addressed.z,
            value.voxel.x, value.voxel.y, value.voxel.z,
            value.packedNormal, value.normalConfidence, value.lineageCount,
        )
        surfaceCache!!.remember(objectScratch)
    }

    private fun remember(surface: DepthCanonicalSurface) {
        byId?.let { if (it.size < MAX_CACHED_SURFACES) it[surface.id] = surface }
        byVoxel?.let {
            if (it.size < MAX_CACHED_SURFACES) {
                it[surface.voxel] = AddressedCanonicalSurface(surface.voxel, surface)
            }
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
        const val MAX_CACHED_SURFACES = 8_192
    }
}
