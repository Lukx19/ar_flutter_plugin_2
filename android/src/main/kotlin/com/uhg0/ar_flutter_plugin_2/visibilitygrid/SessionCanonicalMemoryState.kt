package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.OutputStream
import java.security.MessageDigest

/**
 * Process-local canonical authority for one live capture session.
 *
 * The durable adapter remains the separate storage and recovery authority. This
 * type is deliberately session-only and non-resumable: it keeps the complete canonical identity,
 * sources, support associations, and lineage in packed primitive storage and
 * never publishes a selector or performs file I/O during a mutation.
 */
internal class SessionCanonicalMemoryState(
    private val group: SurfaceGroup,
    private val configuration: SurfaceOwnershipConfiguration,
    override val authorityParentKey: String,
) : ScalarCanonicalAuthority, AutoCloseable {
    private val sourceCapacity = Math.addExact(configuration.surfaceCapacity, configuration.lineageCapacity)
    private val rowCapacity = configuration.surfaceCapacity
    private val supportCapacity = sourceCapacity
    private val lineageCapacity = configuration.lineageCapacity
    private val rowTableSize = tableSizeFor(rowCapacity)

    private val rowIds = IntArray(rowCapacity)
    private val rowX = IntArray(rowCapacity)
    private val rowY = IntArray(rowCapacity)
    private val rowZ = IntArray(rowCapacity)
    private val rowNormals = ShortArray(rowCapacity)
    private val rowConfidences = ByteArray(rowCapacity)
    private val rowUsed = BooleanArray(rowCapacity)
    private val freeRows = IntArray(rowCapacity)
    private var freeRowCount = rowCapacity
    private val orderedIds = IntArray(rowCapacity)
    private val orderedSlots = IntArray(rowCapacity)
    private var orderedCount = 0
    internal var rendererPageSearchComparisons = 0; private set
    internal var rendererPageVisitedRows = 0; private set

    private val voxelKeys = LongArray(rowTableSize)
    private val voxelSlots = IntArray(rowTableSize)

    // Sources are allocated monotonically, but the high-water mark may start above one;
    // retain a compact open-addressed index and grow it only as sources are admitted.
    private var sourceKeys = IntArray(tableSizeFor(minOf(sourceCapacity, 16)))
    private var sourceSlots = IntArray(sourceKeys.size)
    private var sourceCount = 0
    private val sourceChunks = arrayOfNulls<SourceChunk>(chunkCount(sourceCapacity))

    private val supportHeads = IntArray(rowCapacity) { EMPTY_LINK }
    private val supportNext = ChunkedIntColumn(supportCapacity)
    private val supportSources = ChunkedIntColumn(supportCapacity)
    private var supportFreeHead = EMPTY_LINK
    private var supportCount = 0
    private var supportNextAppend = 0

    private val lineageHeads = IntArray(sourceCapacity) { EMPTY_LINK }
    private val lineageNext = ChunkedIntColumn(lineageCapacity)
    private val lineageTargets = ChunkedIntColumn(lineageCapacity)
    private var lineageFreeHead = EMPTY_LINK
    private var lineageCount = 0
    private var lineageNextAppend = 0

    // One buffer remains pinned behind the acknowledged/current receipt while the
    // other is filled by the next prepared mutation. A failed serialization or
    // staged validation therefore cannot corrupt the last acknowledged current.
    private val currentBuffers = arrayOf(
        ByteArray(CURRENT_BUFFER_BYTES), ByteArray(CURRENT_BUFFER_BYTES),
    )
    private var activeBufferIndex = 0
    private val currentDigest = MessageDigest.getInstance("SHA-256")
    private var currentLength = 0
    private var currentGeneration = 0L
    private var closed = false
    private val stageWorkspace = MutationWorkspace()

    override var cut: CompactCanonicalCut = initialCut()
        private set
    private var activation: CanonicalActivationState = CanonicalActivationState(
        cut,
        CanonicalActivationCurrent.None,
        CanonicalActivationIdentity(cut.rootHash),
        CanonicalCurrentState.None,
    )

    init {
        for (index in freeRows.indices) freeRows[index] = rowCapacity - index - 1
    }

    internal fun activationState(): CanonicalActivationState = synchronized(this) {
        check(!closed)
        activation
    }

    /** Applies one already prepared mutation without touching the durable store. */
    internal fun commit(plan: PreparedCanonicalMutation): CanonicalAdjacentCommitResult = synchronized(this) {
        if (closed) return@synchronized refused(CanonicalAdjacentCommitRefusal.NO_ACTIVE_AUTHORITY)
        if (plan.lifecycle() == PreparedMutationLifecycle.CONSUMED ||
            plan.lifecycle() == PreparedMutationLifecycle.DISCARDED
        ) return@synchronized refused(CanonicalAdjacentCommitRefusal.PLAN_DISCARDED)
        if (plan.sourceCut != cut) return@synchronized refused(CanonicalAdjacentCommitRefusal.STALE_CUT)
        if (activation.currentState is CanonicalCurrentState.Unacknowledged) {
            return@synchronized refused(
                CanonicalAdjacentCommitRefusal.CURRENT_UNACKNOWLEDGED,
                PreparedMutationDisposition.RETRYABLE,
            )
        }

        val serialized = serializeCurrent(plan) ?: return@synchronized refused(CanonicalAdjacentCommitRefusal.COMMIT_REFUSED)
        val staged = stage(plan) ?: return@synchronized refused(CanonicalAdjacentCommitRefusal.COMMIT_REFUSED)
        // Allocate all chunk pages needed by the infallible apply before mutating
        // any row, index, or link. A failed reservation cannot leave a half-cut.
        reserveApplyChunks(staged)
        apply(staged)

        val nextRoot = nextToken("root", plan.commandHash, plan.targetGeometryRevision, plan.targetLineageRevision.toInt())
        val nextSource = nextToken("source", plan.commandHash, plan.targetSourceCount.toLong(), plan.targetSupportCount)
        cut = CompactCanonicalCut(
            group = group,
            profile = CompactCanonicalStore.PROFILE,
            geometryRevision = plan.targetGeometryRevision,
            lineageRevision = plan.targetLineageRevision,
            nextSurfaceIdHighWater = plan.targetHighWater,
            liveSurfaceCount = plan.targetLiveSurfaceCount,
            sourceCount = plan.targetSourceCount,
            supportCount = plan.targetSupportCount,
            lineageCount = plan.targetLineageCount,
            seededEmptyBaseline = cut.seededEmptyBaseline,
            rootHash = nextRoot,
            sourceHash = nextSource,
        )
        currentGeneration++
        currentLength = serialized.length
        activeBufferIndex = serialized.bufferIndex
        val sourceGeneration = currentGeneration
        val sourceBufferIndex = activeBufferIndex
        val identity = CanonicalCurrentIdentity(
            commandHash = plan.commandHash,
            commandFingerprint = plan.commandFingerprint,
            canonicalLength = serialized.length.toLong(),
            canonicalHash = serialized.hash,
        )
        val source = CanonicalCurrentSource { output ->
            synchronized(this) {
                check(!closed && sourceGeneration == currentGeneration) { "session canonical current source is stale" }
                output.write(currentBuffers[sourceBufferIndex], 0, currentLength)
            }
        }
        activation = CanonicalActivationState(
            cut,
            CanonicalActivationCurrent.Receipt(identity, source),
            CanonicalActivationIdentity(cut.rootHash),
            CanonicalCurrentState.Unacknowledged(identity),
        )
        CanonicalAdjacentCommitResult.Committed(activation)
    }

    internal fun acknowledge(acknowledgement: CanonicalAcknowledgement): CanonicalAcknowledgementResult = synchronized(this) {
        if (closed) return@synchronized CanonicalAcknowledgementResult.NoOp(CanonicalAcknowledgementNoOp.CLOSED)
        val current = activation.current
        val identity = (current as? CanonicalActivationCurrent.Receipt)?.identity
            ?: return@synchronized CanonicalAcknowledgementResult.NoOp(CanonicalAcknowledgementNoOp.NO_CURRENT)
        if (activation.cut.geometryRevision != acknowledgement.geometryRevision ||
            activation.cut.lineageRevision != acknowledgement.lineageRevision
        ) return@synchronized CanonicalAcknowledgementResult.NoOp(CanonicalAcknowledgementNoOp.STALE_REVISION)
        if (identity.commandHash != acknowledgement.commandHash) {
            return@synchronized CanonicalAcknowledgementResult.NoOp(CanonicalAcknowledgementNoOp.COMMAND_MISMATCH)
        }
        if (activation.currentState is CanonicalCurrentState.Acknowledged) {
            return@synchronized CanonicalAcknowledgementResult.Idempotent(activation)
        }
        activation = activation.copy(currentState = CanonicalCurrentState.Acknowledged(identity))
        CanonicalAcknowledgementResult.Acknowledged(activation)
    }

    /** Visits the current rows without creating a row-sized object graph. */
    internal fun visitRows(sink: (CompactSurface, CanonicalReceiptBytes) -> Boolean): Boolean = synchronized(this) {
        if (closed) return@synchronized false
        for (slot in rowUsed.indices) if (rowUsed[slot]) {
            val id = SurfaceId(idValue(rowIds[slot]))
            val source = sourceSlot(id) ?: return@synchronized false
            if (!sink(row(slot), sourceFingerprint(source))) return@synchronized false
        }
        true
    }

    internal fun rendererPage(cursor: Long, limit: Int): CanonicalRendererPage? = synchronized(this) {
        rendererPageSearchComparisons = 0
        rendererPageVisitedRows = 0
        if (closed || limit !in 1..512 || cursor < 0L) return@synchronized null
        if (cursor >= UINT32_MASK) return@synchronized CanonicalRendererPage(cut, emptyList(), null)
        val cursorRaw = cursor.toInt()
        var start = 0
        var high = orderedCount
        while (start < high) {
            rendererPageSearchComparisons++
            val middle = (start + high) ushr 1
            if (Integer.compareUnsigned(orderedIds[middle], cursorRaw) <= 0) start = middle + 1 else high = middle
        }
        if (start == orderedCount) return@synchronized CanonicalRendererPage(cut, emptyList(), null)
        val end = minOf(orderedCount, start + limit)
        val rows = ArrayList<CommittedGeometryRow>(end - start)
        for (index in start until end) {
            rendererPageVisitedRows++
            val id = idValue(orderedIds[index])
            val slot = orderedSlots[index]
            rows += CommittedGeometryRow(
                id, Voxel(rowX[slot], rowY[slot], rowZ[slot]),
                rowNormal(slot), rowConfidence(slot), cut.lineageCount.coerceAtMost(0xffff),
            )
        }
        CanonicalRendererPage(cut, rows, idValue(orderedIds[end - 1]).takeIf { end < orderedCount })
    }

    internal fun occupiedKeys(): LongArray = synchronized(this) {
        if (closed) return@synchronized LongArray(0)
        val values = LongArray(rowUsed.count { it })
        var index = 0
        for (slot in rowUsed.indices) if (rowUsed[slot]) values[index++] = packVisibilityGridKey(rowX[slot], rowY[slot], rowZ[slot])
        values
    }

    override fun findById(id: SurfaceId): CompactSurface? = synchronized(this) {
        idSlot(id.value)?.let(::row)
    }

    override fun findByVoxel(voxel: Voxel): CompactSurface? = synchronized(this) {
        voxelSlot(packVisibilityGridKey(voxel.x, voxel.y, voxel.z))?.let(::row)
    }

    override fun findByIdBounded(id: SurfaceId, maximumPageReads: Long, maximumBytesRead: Long) =
        bounded(maximumPageReads, maximumBytesRead) { findById(id) }

    override fun findByVoxelBounded(voxel: Voxel, maximumPageReads: Long, maximumBytesRead: Long) =
        bounded(maximumPageReads, maximumBytesRead) { findByVoxel(voxel) }

    override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int): CompactPage {
        if (closed || cursor < 0 || limit !in 1..512) return CompactPage(emptyList(), null, 0)
        val rows = ArrayList<CompactSurface>(limit)
        var skipped = 0
        synchronized(this) {
            for (slot in rowUsed.indices) if (rowUsed[slot]) {
                if (CompactLocation(configuration, Voxel(rowX[slot], rowY[slot], rowZ[slot])) != CompactLocation(region, page)) continue
                if (skipped++ < cursor) continue
                rows += row(slot)
                if (rows.size == limit) break
            }
        }
        return CompactPage(rows, if (rows.size == limit) cursor + rows.size else null, rows.size)
    }

    override fun readSourceById(id: SurfaceId): CanonicalPageRead<PagedSource?> = synchronized(this) {
        sourceSlot(id)?.let { CanonicalPageRead.Complete(source(it), 0, 0) }
            ?: CanonicalPageRead.Complete(null, 0, 0)
    }

    override fun readSourceByIdBounded(id: SurfaceId, maximumPageReads: Long, maximumBytesRead: Long) =
        if (maximumPageReads < 0L || maximumBytesRead < 0L) {
            CanonicalBoundedReadResult.Refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
        } else CanonicalBoundedReadResult.Complete(
            when (val value = readSourceById(id)) {
                is CanonicalPageRead.Complete -> value.value
                is CanonicalPageRead.Refused -> null
            }, CanonicalReadWork.ZERO,
        )

    override fun visitSourceSupport(
        target: SurfaceId,
        cursor: SourceSupportCursor?,
        sink: (PagedSupport) -> Boolean,
    ): SourceSupportRead = synchronized(this) {
        if (closed) return@synchronized SourceSupportRead.Refused(CompactCanonicalRefusal.CLOSED)
        if (cursor != null && (cursor.rootHash != cut.rootHash || cursor.target != target || cursor.ordinal != 0 || cursor.offset < 0)) {
            return@synchronized SourceSupportRead.Refused(CompactCanonicalRefusal.STALE_CURSOR)
        }
        val slot = idSlot(target) ?: return@synchronized SourceSupportRead.Complete(0, null, 0, 0)
        var node = supportHeads[slot]
        var index = 0
        val start = cursor?.offset ?: 0
        var delivered = 0
        while (node != EMPTY_LINK) {
            if (index++ >= start) {
                val source = source(sourceSlotAt(node))
                if (!sink(PagedSupport(target, source))) {
                    return@synchronized SourceSupportRead.Complete(
                        delivered, SourceSupportCursor(cut.rootHash, target, 0, start + delivered), 0, 0,
                    )
                }
                delivered++
            }
            node = supportNext[node]
        }
        SourceSupportRead.Complete(delivered, null, 0, 0)
    }

    override fun visitLineage(source: SurfaceId, cursor: LineageCursor?, sink: (LineageEdge) -> Boolean): LineageRead = synchronized(this) {
        if (closed) return@synchronized LineageRead.Refused(CompactCanonicalRefusal.CLOSED)
        if (cursor != null && (cursor.rootHash != cut.rootHash || cursor.source != source || cursor.offset < 0)) {
            return@synchronized LineageRead.Refused(CompactCanonicalRefusal.STALE_CURSOR)
        }
        val sourceSlot = sourceSlot(source) ?: return@synchronized LineageRead.Complete(0, null)
        val start = cursor?.offset ?: 0
        var index = 0
        var delivered = 0
        var node = lineageHeads[sourceSlot]
        while (node != EMPTY_LINK) {
            if (index++ >= start) {
                if (!sink(LineageEdge(source, SurfaceId(idValue(lineageTargets[node]))))) {
                    return@synchronized LineageRead.Complete(
                        delivered, LineageCursor(cut.rootHash, source, start + delivered),
                    )
                }
                delivered++
            }
            node = lineageNext[node]
        }
        LineageRead.Complete(delivered, null)
    }

    override fun visitLineageBounded(
        source: SurfaceId,
        cursor: LineageCursor?,
        maximumPageReads: Long,
        maximumBytesRead: Long,
        sink: (LineageEdge) -> Boolean,
    ) = if (maximumPageReads < 0L || maximumBytesRead < 0L) {
        CanonicalBoundedReadResult.Refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
    } else CanonicalBoundedReadResult.Complete(visitLineage(source, cursor, sink), CanonicalReadWork.ZERO)

    override fun retainedMemoryReceipt(): CompactRetainedMemoryReceipt {
        val rowBytes = rowCapacity.toLong() * (4L + 4L * 3L + 2L + 1L + 1L) +
            freeRows.size * 4L + supportHeads.size * 4L + orderedIds.size * 4L + orderedSlots.size * 4L
        val sourceBytes = sourceChunks.count { it != null }.toLong() * CHUNK_SIZE * 51L
        val supportBytes = supportNext.retainedBytes(4) + supportSources.retainedBytes(4)
        val lineageBytes = lineageNext.retainedBytes(4) + lineageTargets.retainedBytes(4) + lineageHeads.size * 4L
        val indexes = voxelKeys.size * 8L + voxelSlots.size * 4L +
            sourceKeys.size * 4L + sourceSlots.size * 4L
        val currentBytes = CURRENT_BUFFER_BYTES.toLong() * currentBuffers.size
        return CompactRetainedMemoryReceipt(
            kernelBytes = 0L,
            rowColumnsBytes = rowBytes + sourceBytes + supportBytes + currentBytes,
            idOrderBytes = 0L,
            voxelOrderBytes = 0L,
            pageOrderBytes = 0L,
            pageRangeBytes = 0L,
            lineageColumnsBytes = lineageBytes,
            directoryColumnsBytes = indexes,
            cachePayloadBytes = 0L,
            cacheResidentPages = 0,
            cacheMetadataBytes = 0L,
            scalarAndObjectBytes = 4_096L + stageWorkspace.retainedBytes(),
            migrationOpenScratchBytes = 0L,
        )
    }

    override fun allocatedStorageReceipt() = CompactStorageReceipt(0L, 0L, 0L, 0L, 0L)

    override fun close() = synchronized(this) {
        if (!closed) {
            closed = true
            currentGeneration++
            rowUsed.fill(false)
            voxelSlots.fill(0)
            sourceSlots.fill(0)
        }
    }

    private fun stage(plan: PreparedCanonicalMutation): MutationStage? {
        val stage = MutationStage(plan, stageWorkspace)
        if (!plan.visitDirtyRows { row -> stage.addRow(row) }) return null
        plan.visitRemovedSurfaceIds { id -> stage.addRemoved(id) }
        if (!plan.visitDirtySources { source -> stage.addSource(source) }) return null
        plan.visitDirtySupport { support -> stage.addSupport(support) }
        plan.visitDirtyLineage { edge -> stage.addLineage(edge); true }
        if (!stage.validCounts()) return null
        if (stage.removedSet.size != stage.removedCount || stage.rowIdsSet.size != stage.rowCount) return null
        var removedSupports = 0
        var removedLineage = 0
        var removedRows = 0
        for (index in 0 until stage.removedCount) {
            val removedId = idValue(stage.removedIds[index])
            val slot = idSlot(removedId) ?: return null
            removedRows++
            removedSupports = Math.addExact(removedSupports, countSupports(slot))
            removedLineage = Math.addExact(removedLineage, countLineage(removedId))
        }
        var stagedNewRows = 0
        for (index in 0 until stage.rowCount) {
            val id = idValue(stage.rowIds[index])
            if (idSlot(id) == null || stage.removedSet.contains(id)) stagedNewRows++
        }
        val finalLive = cut.liveSurfaceCount - removedRows + stagedNewRows
        val finalSource = sourceCount + stage.sourceCount
        val finalSupport = supportCount - removedSupports + stage.supportCount
        val finalLineage = lineageCount - removedLineage + stage.lineageCount
        if (finalLive != plan.targetLiveSurfaceCount || finalSource != plan.targetSourceCount ||
            finalSupport != plan.targetSupportCount || finalLineage != plan.targetLineageCount ||
            plan.targetHighWater < cut.nextSurfaceIdHighWater ||
            plan.targetGeometryRevision != cut.geometryRevision + 1L ||
            plan.targetLineageRevision !in cut.lineageRevision..(cut.lineageRevision + 1L)
        ) return null
        if (removedSupports != plan.removedSupportRecords || removedLineage != plan.removedLineageRecords) return null
        for (index in 0 until stage.rowCount) {
            val id = idValue(stage.rowIds[index])
            val old = idSlot(id)
            if (old == null && id < cut.nextSurfaceIdHighWater && !stage.removedSet.contains(id)) return null
            val occupied = voxelSlot(stage.voxelKeys[index])
            if (occupied != null && idValue(rowIds[occupied]) != id && !stage.removedSet.contains(idValue(rowIds[occupied]))) return null
            if (stage.hasNewSource(id) && sourceSlot(id) != null) return null
        }
        for (index in 0 until stage.supportCount) {
            if (idSlot(idValue(stage.supportTargets[index])) == null && !stage.rowIdsSet.containsRaw(stage.supportTargets[index])) return null
            if (sourceSlot(idValue(stage.supportSources[index])) == null && !stage.hasNewSource(idValue(stage.supportSources[index]))) return null
        }
        for (index in 0 until stage.lineageCount) {
            if (sourceSlot(idValue(stage.lineageSources[index])) == null && !stage.hasNewSource(idValue(stage.lineageSources[index]))) return null
            if (idSlot(idValue(stage.lineageTargets[index])) == null && !stage.rowIdsSet.containsRaw(stage.lineageTargets[index])) return null
        }
        return stage
    }

    private fun apply(stage: MutationStage) {
        for (index in 0 until stage.removedCount) removeRow(idValue(stage.removedIds[index]))
        check(stage.plan.visitDirtyRows { row -> upsertRow(row); true })
        check(stage.plan.visitDirtySources { source -> addSource(source); true })
        for (index in 0 until stage.supportCount) addSupport(idValue(stage.supportTargets[index]), idValue(stage.supportSources[index]))
        for (index in 0 until stage.lineageCount) addLineage(idValue(stage.lineageSources[index]), idValue(stage.lineageTargets[index]))
        check(sourceCount == stage.plan.targetSourceCount)
        check(supportCount == stage.plan.targetSupportCount)
        check(lineageCount == stage.plan.targetLineageCount)
    }

    private fun reserveApplyChunks(stage: MutationStage) {
        fun reserve(startIndex: Int, endExclusive: Int, capacity: Int, reserve: (Int) -> Unit) {
            val boundedEnd = minOf(capacity, endExclusive)
            var index = maxOf(0, startIndex)
            while (index < boundedEnd) {
                reserve(index)
                index = ((index ushr CHUNK_SHIFT) + 1) shl CHUNK_SHIFT
            }
        }
        reserve(supportNextAppend, Math.addExact(supportNextAppend, stage.supportCount), supportCapacity) { index ->
            supportNext.ensureCapacityFor(index); supportSources.ensureCapacityFor(index)
        }
        reserve(lineageNextAppend, Math.addExact(lineageNextAppend, stage.lineageCount), lineageCapacity) { index ->
            lineageNext.ensureCapacityFor(index); lineageTargets.ensureCapacityFor(index)
        }
        val sourceEnd = Math.addExact(sourceCount, stage.sourceCount)
        var source = sourceCount
        while (source < sourceEnd) {
            sourceChunk(source)
            source = ((source ushr CHUNK_SHIFT) + 1) shl CHUNK_SHIFT
        }
        reserveSourceIndex(stage.sourceCount)
    }

    private fun serializeCurrent(plan: PreparedCanonicalMutation): SerializedCurrent? {
        val stagingBufferIndex = activeBufferIndex xor 1
        val output = FixedBufferOutput(currentBuffers[stagingBufferIndex])
        return try {
            plan.writeCurrentTo(output)
            currentDigest.reset()
            currentDigest.update(currentBuffers[stagingBufferIndex], 0, output.count)
            SerializedCurrent(output.count, CanonicalReceiptBytes(currentDigest.digest()), stagingBufferIndex)
        } catch (_: BufferOverflow) {
            null
        }
    }

    private fun initialCut(): CompactCanonicalCut {
        val baseline = configuration.seededEmptyBaseline ?: committedEmptyBaseline(
            authorityParentKey.ifBlank { "session" }, group.value, 0L, 1L, 1L,
        )
        require(baseline.groupIdentity == group.value)
        val root = token("initial-root", baseline.geometryRevision, baseline.lineageRevision)
        val source = token("initial-source", baseline.geometryRevision, baseline.lineageRevision)
        return CompactCanonicalCut(
            group, CompactCanonicalStore.PROFILE, baseline.geometryRevision, baseline.lineageRevision,
            1L, 0, 0, 0, 0, baseline, root, source,
        )
    }

    private fun nextToken(prefix: String, command: CanonicalReceiptBytes, first: Long, second: Int): CanonicalReceiptBytes =
        digest { digest ->
            digest.update(prefix.encodeToByteArray()); digest.update(command.toByteArray())
            digest.update(java.nio.ByteBuffer.allocate(12).putLong(first).putInt(second).array())
            digest.update(cut.rootHash.toByteArray())
        }

    private fun token(prefix: String, first: Long, second: Long): CanonicalReceiptBytes = digest { digest ->
        digest.update(prefix.encodeToByteArray())
        digest.update(java.nio.ByteBuffer.allocate(16).putLong(first).putLong(second).array())
        digest.update(group.value.encodeToByteArray())
    }

    private fun digest(write: (MessageDigest) -> Unit): CanonicalReceiptBytes {
        currentDigest.reset(); write(currentDigest); return CanonicalReceiptBytes(currentDigest.digest())
    }

    private fun refused(
        reason: CanonicalAdjacentCommitRefusal,
        disposition: PreparedMutationDisposition = PreparedMutationDisposition.TERMINAL,
    ) = CanonicalAdjacentCommitResult.Refused(reason, disposition = disposition)

    private fun row(slot: Int) = CompactSurface(
        SurfaceId(idValue(rowIds[slot])), Voxel(rowX[slot], rowY[slot], rowZ[slot]), rowNormal(slot), rowConfidence(slot),
    )

    private fun rowNormal(slot: Int) = rowNormals[slot].toInt() and 0xffff
    private fun rowConfidence(slot: Int) = rowConfidences[slot].toInt() and 0xff

    private fun orderedInsert(rawId: Int, rowSlot: Int) {
        check(orderedCount < orderedIds.size)
        var index = orderedCount
        while (index > 0 && Integer.compareUnsigned(orderedIds[index - 1], rawId) > 0) {
            orderedIds[index] = orderedIds[index - 1]; index--
            orderedSlots[index] = orderedSlots[index - 1]
        }
        orderedIds[index] = rawId
        orderedSlots[index] = rowSlot
        orderedCount++
    }

    private fun orderedRemove(rawId: Int) {
        var low = 0
        var high = orderedCount - 1
        var index = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                orderedIds[middle] == rawId -> { index = middle; break }
                Integer.compareUnsigned(orderedIds[middle], rawId) < 0 -> low = middle + 1
                else -> high = middle - 1
            }
        }
        if (index < 0) return
        while (index + 1 < orderedCount) { orderedIds[index] = orderedIds[index + 1]; orderedSlots[index] = orderedSlots[index + 1]; index++ }
        orderedCount--
    }

    private fun bounded(maximumPageReads: Long, maximumBytesRead: Long, lookup: () -> CompactSurface?) =
        if (maximumPageReads < 0L || maximumBytesRead < 0L) {
            CanonicalBoundedReadResult.Refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
        } else CanonicalBoundedReadResult.Complete(lookup(), CanonicalReadWork.ZERO)

    private fun source(slot: Int): PagedSource {
        val chunk = requireNotNull(sourceChunks[slot ushr CHUNK_SHIFT])
        val offset = slot and CHUNK_MASK
        return PagedSource(
            SurfaceId(idValue(chunk.ids[offset])), Voxel(chunk.x[offset], chunk.y[offset], chunk.z[offset]),
            chunk.normals[offset].toInt() and 0xffff, chunk.confidences[offset].toInt() and 0xff,
            CanonicalReceiptBytes(chunk.fingerprints.copyOfRange(offset * FINGERPRINT_BYTES, (offset + 1) * FINGERPRINT_BYTES)),
        )
    }

    private fun sourceFingerprint(slot: Int) = source(slot).allocationFingerprint

    private fun addSource(source: ImmutableSourceSupport) {
        val id = source.id.value
        check(sourceSlot(id) == null)
        val slot = sourceCount++
        val chunk = sourceChunk(slot)
        val offset = slot and CHUNK_MASK
        chunk.ids[offset] = encodeId(id); chunk.x[offset] = source.voxel.x; chunk.y[offset] = source.voxel.y; chunk.z[offset] = source.voxel.z
        chunk.normals[offset] = source.packedNormal.toShort(); chunk.confidences[offset] = source.normalConfidence.toByte()
        source.allocationFingerprint.toByteArray().copyInto(chunk.fingerprints, offset * FINGERPRINT_BYTES)
        sourcePut(id, slot)
    }

    private fun upsertRow(row: PreparedRow) {
        val id = row.id.value
        val rawId = encodeId(id)
        val slot = idSlot(id) ?: run {
            check(freeRowCount > 0)
            val allocated = freeRows[--freeRowCount]
            rowUsed[allocated] = true; orderedInsert(rawId, allocated); allocated
        }
        val oldKey = if (rowIds[slot] != 0) packVisibilityGridKey(rowX[slot], rowY[slot], rowZ[slot]) else null
        val newKey = packVisibilityGridKey(row.voxel.x, row.voxel.y, row.voxel.z)
        if (oldKey != null && oldKey != newKey) voxelRemove(oldKey)
        rowIds[slot] = rawId; rowX[slot] = row.voxel.x; rowY[slot] = row.voxel.y; rowZ[slot] = row.voxel.z
        rowNormals[slot] = row.packedNormal.toShort(); rowConfidences[slot] = row.normalConfidence.toByte()
        voxelPut(newKey, slot)
    }

    private fun removeRow(id: Long) {
        val slot = idSlot(id) ?: error("missing session surface")
        removeSupports(slot); removeLineage(id)
        voxelRemove(packVisibilityGridKey(rowX[slot], rowY[slot], rowZ[slot]))
        rowUsed[slot] = false; rowIds[slot] = 0; orderedRemove(encodeId(id)); freeRows[freeRowCount++] = slot
    }

    private fun addSupport(target: Long, source: Long) {
        val slot = idSlot(target) ?: error("support target is not live")
        val sourceSlot = sourceSlot(source) ?: error("support source is missing")
        val node = allocateSupport()
        supportSources[node] = encodeId(source)
        supportNext[node] = supportHeads[slot]
        supportHeads[slot] = node
        check(sourceSlot >= 0)
    }

    private fun addLineage(source: Long, target: Long) {
        val sourceSlot = sourceSlot(source) ?: error("lineage source is missing")
        check(idSlot(target) != null)
        val node = allocateLineage()
        lineageTargets[node] = encodeId(target); lineageNext[node] = lineageHeads[sourceSlot]; lineageHeads[sourceSlot] = node
    }

    private fun removeSupports(slot: Int) {
        var node = supportHeads[slot]
        supportHeads[slot] = EMPTY_LINK
        while (node != EMPTY_LINK) {
            val next = supportNext[node]; supportNext[node] = supportFreeHead; supportFreeHead = node; supportCount--; node = next
        }
    }

    private fun removeLineage(source: Long) {
        val slot = sourceSlot(source) ?: return
        var node = lineageHeads[slot]
        lineageHeads[slot] = EMPTY_LINK
        while (node != EMPTY_LINK) {
            val next = lineageNext[node]; lineageNext[node] = lineageFreeHead; lineageFreeHead = node; lineageCount--; node = next
        }
    }

    private fun countSupports(slot: Int): Int {
        var count = 0; var node = supportHeads[slot]
        while (node != EMPTY_LINK) { count++; node = supportNext[node] }
        return count
    }

    private fun countLineage(source: Long): Int {
        val slot = sourceSlot(source) ?: return 0
        var count = 0; var node = lineageHeads[slot]
        while (node != EMPTY_LINK) { count++; node = lineageNext[node] }
        return count
    }

    private fun sourceChunk(slot: Int): SourceChunk {
        val chunkIndex = slot ushr CHUNK_SHIFT
        return sourceChunks[chunkIndex] ?: SourceChunk().also { sourceChunks[chunkIndex] = it }
    }

    private fun allocateSupport(): Int {
        val node = if (supportFreeHead != EMPTY_LINK) supportFreeHead.also { supportFreeHead = supportNext[it] } else supportNextAppend++
        if (node >= supportCapacity) error("session support capacity")
        supportCount++
        return node
    }

    private fun allocateLineage(): Int {
        val node = if (lineageFreeHead != EMPTY_LINK) lineageFreeHead.also { lineageFreeHead = lineageNext[it] } else lineageNextAppend++
        if (node >= lineageCapacity) error("session lineage capacity")
        lineageCount++
        return node
    }

    private fun idSlot(id: SurfaceId): Int? = idSlot(id.value)
    private fun idSlot(id: Long): Int? {
        val rawId = encodeId(id)
        var low = 0
        var high = orderedCount - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                orderedIds[middle] == rawId -> return orderedSlots[middle]
                Integer.compareUnsigned(orderedIds[middle], rawId) < 0 -> low = middle + 1
                else -> high = middle - 1
            }
        }
        return null
    }
    private fun voxelSlot(key: Long): Int? = tableGet(voxelKeys, voxelSlots, key)
    private fun sourceSlot(id: SurfaceId): Int? = sourceSlot(id.value)
    private fun sourceSlot(id: Long): Int? = intTableGet(sourceKeys, sourceSlots, encodeId(id))
    private fun sourceSlotAt(node: Int): Int = sourceSlot(requireNotNull(sourceIdAt(node))) ?: error("support source missing")
    private fun sourceIdAt(node: Int): Long? = idValue(supportSources[node])

    private fun voxelPut(key: Long, slot: Int) = tablePut(voxelKeys, voxelSlots, key, slot)
    private fun sourcePut(id: Long, slot: Int) {
        if (sourceCount * 10 >= sourceKeys.size * 8) {
            growSourceIndex()
        }
            intTablePut(sourceKeys, sourceSlots, encodeId(id), slot)
    }
    private fun reserveSourceIndex(additional: Int) {
        val required = Math.addExact(sourceCount, additional)
        while (required * 10 >= sourceKeys.size * 8) growSourceIndex()
    }
    private fun growSourceIndex() {
        val oldKeys = sourceKeys
        val oldSlots = sourceSlots
        sourceKeys = IntArray(oldKeys.size shl 1)
        sourceSlots = IntArray(sourceKeys.size)
        for (index in oldSlots.indices) if (oldSlots[index] != 0) {
            intTablePut(sourceKeys, sourceSlots, oldKeys[index], oldSlots[index] - 1)
        }
    }
    private fun voxelRemove(key: Long) = tableRemove(voxelKeys, voxelSlots, key)

    private class MutationStage(val plan: PreparedCanonicalMutation, private val workspace: MutationWorkspace) {
        val expectedRows = plan.dirtyRowCount
        val expectedRemoved = plan.removedSurfaceCount
        val expectedSources = plan.work.dirtySourceRecords
        val expectedSupport = plan.work.dirtySupportRecords
        val expectedLineage = plan.work.dirtyLineageRecords
        init { workspace.prepare(expectedRows, expectedRemoved, expectedSources, expectedSupport, expectedLineage) }
        val rowIds get() = workspace.rowIds; val voxelKeys get() = workspace.voxelKeys
        val removedIds get() = workspace.removedIds
        val sourceIds get() = workspace.sourceIds
        val supportTargets get() = workspace.supportTargets; val supportSources get() = workspace.supportSources
        val lineageSources get() = workspace.lineageSources; val lineageTargets get() = workspace.lineageTargets
        var rowCount = 0; var removedCount = 0; var sourceCount = 0; var supportCount = 0; var lineageCount = 0
        val removedSet get() = workspace.removedSet; val rowIdsSet get() = workspace.rowIdsSet; val sourceIdsSet get() = workspace.sourceIdsSet
        fun addRow(row: PreparedRow): Boolean { if (rowCount >= expectedRows || !rowIdsSet.addRaw(encodeId(row.id.value))) return false; rowIds[rowCount] = encodeId(row.id.value); voxelKeys[rowCount] = packVisibilityGridKey(row.voxel.x, row.voxel.y, row.voxel.z); rowCount++; return true }
        fun addRemoved(id: SurfaceId): Boolean { if (removedCount >= expectedRemoved || !removedSet.addRaw(encodeId(id.value))) return false; removedIds[removedCount++] = encodeId(id.value); return true }
        fun addSource(source: ImmutableSourceSupport): Boolean { if (sourceCount >= expectedSources || !sourceIdsSet.addRaw(encodeId(source.id.value))) return false; sourceIds[sourceCount++] = encodeId(source.id.value); return true }
        fun addSupport(value: PreparedSupport): Boolean { if (supportCount >= expectedSupport) return false; supportTargets[supportCount] = encodeId(value.target.value); supportSources[supportCount++] = encodeId(value.source.id.value); return true }
        fun addLineage(value: LineageEdge): Boolean { if (lineageCount >= expectedLineage) return false; lineageSources[lineageCount] = encodeId(value.source.value); lineageTargets[lineageCount++] = encodeId(value.target.value); return true }
        fun validCounts() = rowCount == expectedRows && removedCount == expectedRemoved && sourceCount == expectedSources && supportCount == expectedSupport && lineageCount == expectedLineage
        fun hasNewSource(id: Long): Boolean = sourceIdsSet.contains(id)
    }

    private class MutationWorkspace {
        var rowIds = IntArray(0); var voxelKeys = LongArray(0)
        var removedIds = IntArray(0)
        var sourceIds = IntArray(0)
        var supportTargets = IntArray(0); var supportSources = IntArray(0)
        var lineageSources = IntArray(0); var lineageTargets = IntArray(0)
        val removedSet = LongSet(0); val rowIdsSet = LongSet(0); val sourceIdsSet = LongSet(0)
        fun prepare(rows: Int, removed: Int, sources: Int, support: Int, lineage: Int) {
            rowIds = grow(rowIds, rows); voxelKeys = grow(voxelKeys, rows)
            removedIds = grow(removedIds, removed)
            sourceIds = grow(sourceIds, sources)
            supportTargets = grow(supportTargets, support); supportSources = grow(supportSources, support)
            lineageSources = grow(lineageSources, lineage); lineageTargets = grow(lineageTargets, lineage)
            removedSet.reset(removed); rowIdsSet.reset(rows); sourceIdsSet.reset(sources)
        }
        fun retainedBytes() = rowIds.size * 4L + voxelKeys.size * 8L + removedIds.size * 4L + sourceIds.size * 4L +
            supportTargets.size * 4L + supportSources.size * 4L + lineageSources.size * 4L + lineageTargets.size * 4L + removedSet.retainedBytes() + rowIdsSet.retainedBytes() + sourceIdsSet.retainedBytes()
        private fun grow(values: IntArray, required: Int): IntArray = if (values.size >= required) values else IntArray(grown(values.size, required))
        private fun grow(values: LongArray, required: Int): LongArray = if (values.size >= required) values else LongArray(grown(values.size, required))
        private fun grow(values: ByteArray, required: Int): ByteArray = if (values.size >= required) values else ByteArray(grown(values.size, required))
        private fun grown(current: Int, required: Int): Int { var result = maxOf(1, current); while (result < required) result = if (result > Int.MAX_VALUE / 2) required else result shl 1; return result }
    }

    private data class SerializedCurrent(val length: Int, val hash: CanonicalReceiptBytes, val bufferIndex: Int)
    private class BufferOverflow : RuntimeException()
    private class FixedBufferOutput(private val buffer: ByteArray) : OutputStream() { var count = 0; override fun write(value: Int) { if (count == buffer.size) throw BufferOverflow(); buffer[count++] = value.toByte() }; override fun write(bytes: ByteArray, offset: Int, length: Int) { if (length < 0 || count > buffer.size - length) throw BufferOverflow(); bytes.copyInto(buffer, count, offset, offset + length); count += length } }
    private class LongSet(expected: Int) {
        private var keys = IntArray(tableSizeFor(maxOf(1, expected)))
        private val mask get() = keys.size - 1
        var size = 0; private set
        fun reset(expected: Int) { val required = tableSizeFor(maxOf(1, expected)); if (keys.size < required) keys = IntArray(required) else keys.fill(0); size = 0 }
        fun add(value: Long): Boolean = addRaw(encodeId(value))
        fun addRaw(value: Int): Boolean { var slot = mix(value.toLong()) and mask; while (keys[slot] != 0) { if (keys[slot] == value) return false; slot = (slot + 1) and mask }; keys[slot] = value; size++; return true }
        fun contains(value: Long): Boolean = containsRaw(encodeId(value))
        fun containsRaw(value: Int): Boolean { var slot = mix(value.toLong()) and mask; while (keys[slot] != 0) { if (keys[slot] == value) return true; slot = (slot + 1) and mask }; return false }
        fun retainedBytes() = keys.size * 4L
    }
    private class SourceChunk { val ids = IntArray(CHUNK_SIZE); val x = IntArray(CHUNK_SIZE); val y = IntArray(CHUNK_SIZE); val z = IntArray(CHUNK_SIZE); val normals = ShortArray(CHUNK_SIZE); val confidences = ByteArray(CHUNK_SIZE); val fingerprints = ByteArray(CHUNK_SIZE * FINGERPRINT_BYTES) }
    private class ChunkedIntColumn(capacity: Int) {
        private val chunks = arrayOfNulls<IntArray>(chunkCount(capacity))
        operator fun get(index: Int): Int { val chunk = requireNotNull(chunks[index ushr CHUNK_SHIFT]); return chunk[index and CHUNK_MASK] }
        operator fun set(index: Int, value: Int) { val chunkIndex = index ushr CHUNK_SHIFT; val chunk = chunks[chunkIndex] ?: IntArray(CHUNK_SIZE) { EMPTY_LINK }.also { chunks[chunkIndex] = it }; chunk[index and CHUNK_MASK] = value }
        fun ensureCapacityFor(index: Int) { if (index >= 0) { val chunkIndex = index ushr CHUNK_SHIFT; if (chunks[chunkIndex] == null) chunks[chunkIndex] = IntArray(CHUNK_SIZE) { EMPTY_LINK } } }
        fun retainedBytes(valueWidth: Int) = chunks.count { it != null }.toLong() * CHUNK_SIZE * valueWidth
    }

    companion object {
        private const val EMPTY_LINK = -1
        private const val UINT32_MASK = 0xffff_ffffL
        private const val CURRENT_BUFFER_BYTES = 1_048_576
        private const val FINGERPRINT_BYTES = 32
        private const val CHUNK_SHIFT = 10
        private const val CHUNK_SIZE = 1 shl CHUNK_SHIFT
        private const val CHUNK_MASK = CHUNK_SIZE - 1
        private fun chunkCount(capacity: Int) = (capacity + CHUNK_MASK) ushr CHUNK_SHIFT
        private fun tableSizeFor(capacity: Int): Int { var size = 1; while (size.toLong() * 4L < capacity.toLong() * 5L) size = size shl 1; return size }
        private fun mix(value: Long): Int { var mixed = value xor (value ushr 33); mixed *= -49064778989728563L; mixed = mixed xor (mixed ushr 33); return mixed.toInt() }
        private fun tableGet(keys: LongArray, slots: IntArray, key: Long): Int? { var index = mix(key) and (slots.size - 1); while (slots[index] != 0) { if (keys[index] == key) return slots[index] - 1; index = (index + 1) and (slots.size - 1) }; return null }
        private fun tablePut(keys: LongArray, slots: IntArray, key: Long, value: Int) { var index = mix(key) and (slots.size - 1); while (slots[index] != 0 && keys[index] != key) index = (index + 1) and (slots.size - 1); keys[index] = key; slots[index] = value + 1 }
        private fun tableRemove(keys: LongArray, slots: IntArray, key: Long) { var index = mix(key) and (slots.size - 1); while (slots[index] != 0 && keys[index] != key) index = (index + 1) and (slots.size - 1); if (slots[index] == 0) return; slots[index] = 0; var next = (index + 1) and (slots.size - 1); while (slots[next] != 0) { val movedKey = keys[next]; val movedValue = slots[next] - 1; slots[next] = 0; tablePut(keys, slots, movedKey, movedValue); next = (next + 1) and (slots.size - 1) } }
        private fun intTableGet(keys: IntArray, slots: IntArray, key: Int): Int? { var index = mix(key.toLong()) and (slots.size - 1); while (slots[index] != 0) { if (keys[index] == key) return slots[index] - 1; index = (index + 1) and (slots.size - 1) }; return null }
        private fun intTablePut(keys: IntArray, slots: IntArray, key: Int, value: Int) { var index = mix(key.toLong()) and (slots.size - 1); while (slots[index] != 0 && keys[index] != key) index = (index + 1) and (slots.size - 1); keys[index] = key; slots[index] = value + 1 }
        private fun intTableRemove(keys: IntArray, slots: IntArray, key: Int) { var index = mix(key.toLong()) and (slots.size - 1); while (slots[index] != 0 && keys[index] != key) index = (index + 1) and (slots.size - 1); if (slots[index] == 0) return; slots[index] = 0; var next = (index + 1) and (slots.size - 1); while (slots[next] != 0) { val movedKey = keys[next]; val movedValue = slots[next] - 1; slots[next] = 0; intTablePut(keys, slots, movedKey, movedValue); next = (next + 1) and (slots.size - 1) } }
        private fun idValue(raw: Int): Long = raw.toLong() and UINT32_MASK
        private fun encodeId(id: Long): Int { require(id in 1L..UINT32_MASK); return id.toInt() }
    }
}
