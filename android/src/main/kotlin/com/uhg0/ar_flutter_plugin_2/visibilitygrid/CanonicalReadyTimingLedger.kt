package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.PacketCodec

/**
 * Bounded diagnostic attribution for one queued canonical current delta.
 *
 * The ledger measures only the local monotonic clock. It records when an
 * exact current was successfully queued, the first parsed request that still
 * acknowledges the prior cut, and the later structural acknowledgement. It
 * never retains a current payload or geometry object.
 */
internal class CanonicalReadyTimingLedger(
    private val enabled: Boolean,
    private val clockNs: () -> Long = { System.nanoTime() },
) {
    companion object {
        // Ten identity Longs, one ordinal Long, three monotonic fence Longs,
        // and one aligned status scalar. Eleven entries stay within the
        // 1408-byte portable diagnostic budget; debug owner headers are
        // charged separately.
        const val MAX_ENTRIES = 11
        const val PORTABLE_ENTRY_BYTES = 120L
        const val PORTABLE_BYTES = MAX_ENTRIES * PORTABLE_ENTRY_BYTES
        const val DEBUG_OWNER_HEADER_BYTES = 128L

        private val DISABLED_SNAPSHOT: Map<String, Any> = mapOf("enabled" to false)
    }

    private enum class EntryStatus(val wireName: String) {
        QUEUED("queued"),
        EXCHANGE_STARTED("exchangeStarted"),
        ACKNOWLEDGED("acknowledged"),
        REPLACED("replaced"),
        RESET("reset"),
    }

    private data class Entry(
        val identity: CanonicalReadyTimingIdentity,
        val ordinal: Long,
        val queuedNs: Long,
        var exchangeNs: Long = 0L,
        var acknowledgedNs: Long = 0L,
        var status: EntryStatus = EntryStatus.QUEUED,
    )

    private val entries = arrayOfNulls<Entry>(MAX_ENTRIES)
    private var queuedCount = 0L
    private var exchangeCount = 0L
    private var acknowledgedCount = 0L
    private var duplicateQueueCount = 0L
    private var slotReplacementCount = 0L
    private var resetCount = 0L
    private var replacedCount = 0L
    private var incompleteCount = 0L
    private var lossCount = 0L
    private var lastResetReason = "none"
    private var nextOrdinal = 0L

    /** Serializes a successful stream queue with ledger publication. */
    internal fun <T> withQueueCommit(
        identity: CanonicalReadyTimingIdentity,
        disposition: CurrentDeltaQueueResult,
        block: () -> T,
    ): T {
        if (!enabled) return block()
        synchronized(this) {
            val result = block()
            recordQueuedLocked(identity, disposition)
            return result
        }
    }

    internal fun recordQueued(
        identity: CanonicalReadyTimingIdentity,
        disposition: CurrentDeltaQueueResult,
    ) {
        if (!enabled) return
        synchronized(this) {
            recordQueuedLocked(identity, disposition)
        }
    }

    private fun recordQueuedLocked(
        identity: CanonicalReadyTimingIdentity,
        disposition: CurrentDeltaQueueResult,
    ) {
        val existing = entries.firstOrNull { it?.identity == identity }
        if (existing != null) {
            if (disposition == CurrentDeltaQueueResult.ALREADY_QUEUED) {
                // A retry must never move the original queued timestamp.
                duplicateQueueCount++
            }
            return
        }
        // Fill unused slots first. Once full, replace the oldest terminal
        // record so the bounded receipt remains a chronological tail.
        val emptySlot = entries.indexOfFirst { it == null }
        val slot = if (emptySlot >= 0) {
            emptySlot
        } else {
            entries.indices
                .filter { index ->
                    entries[index]?.status == EntryStatus.ACKNOWLEDGED ||
                        entries[index]?.status == EntryStatus.RESET ||
                        entries[index]?.status == EntryStatus.REPLACED
                }
                .minByOrNull { index -> entries[index]!!.ordinal }
                ?: -1
        }
        if (slot < 0) {
            lossCount++
            return
        }
        if (entries[slot] != null) slotReplacementCount++
        nextOrdinal++
        entries[slot] = Entry(
            identity = identity,
            ordinal = nextOrdinal,
            queuedNs = clockNs(),
        )
        queuedCount++
    }

    /**
     * A recovered stream queue has no trustworthy local queued fence. Keep it
     * out of the timing sample rather than treating a later request as its
     * first exchange.
     */
    internal fun recordUnattributedRecovery() {
        if (!enabled) return
        synchronized(this) {
            lossCount++
            incompleteCount++
            lastResetReason = "recoveredWithoutQueuedReceipt"
        }
    }

    /** Records the first request that is qualified against the prior cut. */
    internal fun recordFirstCurrentExchange(request: PacketCodec.Request) {
        if (!enabled) return
        // Resync/control requests can acknowledge the same prior cut but do
        // not represent the ordinary current exchange this receipt measures.
        if (request.requestFlags != 0) return
        synchronized(this) {
            val entry = entries.firstOrNull {
                it?.status == EntryStatus.QUEUED &&
                    it.identity.streamToken == request.streamToken &&
                    it.identity.previousTransactionId == request.acknowledgedTransactionId &&
                    it.identity.previousGeometryRevision == request.acknowledgedGeometryRevision &&
                    it.identity.previousLineageRevision == request.acknowledgedLineageRevision
            } ?: return
            entry.exchangeNs = clockNs()
            entry.status = EntryStatus.EXCHANGE_STARTED
            exchangeCount++
        }
    }

    internal fun recordStructuralAcknowledgement(
        transactionId: Long,
        geometryRevision: Long,
        lineageRevision: Long,
    ) {
        if (!enabled) return
        synchronized(this) {
            val entry = entries.firstOrNull {
                (it?.status == EntryStatus.EXCHANGE_STARTED || it?.status == EntryStatus.QUEUED) &&
                    it.identity.transactionId == transactionId &&
                    it.identity.geometryRevision == geometryRevision &&
                    it.identity.lineageRevision == lineageRevision
            } ?: return
            entry.acknowledgedNs = clockNs()
            entry.status = EntryStatus.ACKNOWLEDGED
            acknowledgedCount++
        }
    }

    /** Fences active records on lifecycle replacement or ordinary reset. */
    internal fun reset(reason: String) {
        if (!enabled) return
        synchronized(this) {
            resetCount++
            lastResetReason = reason
            entries.forEachIndexed { index, entry ->
                if (entry == null || entry.status == EntryStatus.ACKNOWLEDGED ||
                    entry.status == EntryStatus.RESET || entry.status == EntryStatus.REPLACED
                ) return@forEachIndexed
                incompleteCount++
                entry.status = if (reason == "replaced") {
                    replacedCount++
                    EntryStatus.REPLACED
                } else {
                    EntryStatus.RESET
                }
                entries[index] = entry
            }
        }
    }

    @Synchronized
    internal fun snapshotWireMap(): Map<String, Any> {
        if (!enabled) return DISABLED_SNAPSHOT
        return mapOf(
            "enabled" to true,
            "schemaVersion" to 1,
            "sampledAtNanoNs" to clockNs(),
            "capacity" to MAX_ENTRIES,
            "portableBytes" to PORTABLE_BYTES,
            "debugOwnerHeaderBytes" to DEBUG_OWNER_HEADER_BYTES,
            "queued" to queuedCount,
            "firstExchange" to exchangeCount,
            "structuralAcknowledged" to acknowledgedCount,
            "duplicateQueue" to duplicateQueueCount,
            "slotReplacements" to slotReplacementCount,
            "resets" to resetCount,
            "replaced" to replacedCount,
            "incomplete" to incompleteCount,
            "loss" to lossCount,
            "lastResetReason" to lastResetReason,
            "entries" to entries.filterNotNull().sortedBy { it.ordinal }.map { it.toWireMap() },
        )
    }

    private fun Entry.toWireMap(): Map<String, Any> {
        val queueToExchange = elapsedMicros(queuedNs, exchangeNs)
        val exchangeToAcknowledgement = elapsedMicros(exchangeNs, acknowledgedNs)
        val queueToAcknowledgement = elapsedMicros(queuedNs, acknowledgedNs)
        return mapOf(
            "bindingGeneration" to identity.bindingGeneration,
            "sessionGeneration" to identity.sessionGeneration,
            "groupGeneration" to identity.groupGeneration,
            "streamToken" to identity.streamToken,
            "transactionId" to identity.transactionId,
            "geometryRevision" to identity.geometryRevision,
            "lineageRevision" to identity.lineageRevision,
            "queuedNs" to queuedNs,
            "firstExchangeNs" to exchangeNs,
            "structuralAckNs" to acknowledgedNs,
            "queueToFirstExchangeMicros" to queueToExchange,
            "firstExchangeToStructuralAckMicros" to exchangeToAcknowledgement,
            "queueToStructuralAckMicros" to queueToAcknowledgement,
            "status" to status.wireName,
        )
    }

    private fun elapsedMicros(startNs: Long, endNs: Long): Long =
        if (startNs <= 0L || endNs < startNs) -1L else (endNs - startNs) / 1_000L
}

internal data class CanonicalReadyTimingIdentity(
    val bindingGeneration: Long,
    val sessionGeneration: Long,
    val groupGeneration: Long,
    val streamToken: Long,
    val transactionId: Long,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val previousTransactionId: Long,
    val previousGeometryRevision: Long,
    val previousLineageRevision: Long,
)
