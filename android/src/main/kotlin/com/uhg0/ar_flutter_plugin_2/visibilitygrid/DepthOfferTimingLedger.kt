package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CurrentDeltaSelectorV1

/** Synthetic debug evidence only. Fixed primitive storage never retains observations or payloads. */
internal class DepthOfferTimingLedger(private val nanoTime: () -> Long = System::nanoTime) {
    private val ids = LongArray(CAPACITY)
    private val sources = LongArray(CAPACITY)
    private val starts = LongArray(CAPACITY)
    private val ends = LongArray(CAPACITY)
    private val transactions = LongArray(CAPACITY)
    private val geometry = LongArray(CAPACITY)
    private val lineage = LongArray(CAPACITY)
    private val states = IntArray(CAPACITY)
    private var offered = 0L

    @Synchronized fun start(sourceTimestampNs: Long): Long {
        val id = ++offered
        val slot = slot(id)
        ids[slot] = id
        sources[slot] = sourceTimestampNs
        starts[slot] = nanoTime()
        ends[slot] = 0
        transactions[slot] = 0
        geometry[slot] = 0
        lineage[slot] = 0
        states[slot] = QUEUED
        return id
    }

    @Synchronized fun stage(id: Long, state: Int) {
        val slot = matching(id) ?: return
        if (states[slot] < COMPLETED) states[slot] = state
    }

    @Synchronized fun finish(id: Long, state: Int) {
        require(state > COMPLETED)
        val slot = matching(id) ?: return
        if (states[slot] < COMPLETED) ends[slot] = nanoTime()
        stage(id, state)
    }

    @Synchronized fun refuseUnretained(id: Long) {
        val slot = matching(id) ?: return
        if (states[slot] == QUEUED || states[slot] == ADMITTING) states[slot] = REFUSED
    }

    @Synchronized fun published(id: Long, selector: CurrentDeltaSelectorV1) {
        val slot = matching(id) ?: return
        if (states[slot] >= COMPLETED) return
        transactions[slot] = selector.transactionId.toLong()
        geometry[slot] = selector.targetGeometryRevision.toLong()
        lineage[slot] = selector.targetLineageRevision.toLong()
        states[slot] = PUBLICATION_PENDING
    }

    /** Called only after exact canonical ACK and renderer application both succeeded. */
    @Synchronized fun complete(id: Long, selector: CurrentDeltaSelectorV1) {
        val slot = matching(id) ?: return
        if (states[slot] != PUBLICATION_PENDING ||
            transactions[slot] != selector.transactionId.toLong() ||
            geometry[slot] != selector.targetGeometryRevision.toLong() ||
            lineage[slot] != selector.targetLineageRevision.toLong()) return
        ends[slot] = nanoTime()
        states[slot] = COMPLETED
    }

    @Synchronized fun finishActive(state: Int) {
        require(state > COMPLETED)
        for (slot in ids.indices) if (ids[slot] != 0L && states[slot] < COMPLETED) states[slot] = state
    }

    @Synchronized fun snapshot(): Map<String, Any> = mapOf(
        "capacity" to CAPACITY,
        "portableDebugOwnerBytes" to PORTABLE_BYTES,
        "offeredCount" to offered,
        "overwrittenCount" to (offered - CAPACITY).coerceAtLeast(0),
        "entries" to ((offered - CAPACITY + 1).coerceAtLeast(1)..offered).map { id ->
            val slot = slot(id)
            mapOf(
                "offerId" to id,
                "sourceTimestampNs" to sources[slot],
                "status" to STATUS[states[slot]],
                "endToEndMicros" to if (states[slot] == COMPLETED) (ends[slot] - starts[slot]).coerceAtLeast(0) / 1_000 else -1L,
                "nonMaterialMicros" to if (states[slot] == NON_MATERIAL) (ends[slot] - starts[slot]).coerceAtLeast(0) / 1_000 else -1L,
                "transactionId" to transactions[slot],
                "targetGeometryRevision" to geometry[slot],
                "targetLineageRevision" to lineage[slot],
            )
        },
    )

    private fun slot(id: Long): Int = ((id - 1) % CAPACITY).toInt()
    private fun matching(id: Long): Int? = if (id > 0 && ids[slot(id)] == id) slot(id) else null

    companion object {
        const val CAPACITY = 64
        // Seven long columns, one int column, array headers, owner references/scalars.
        // Synthetic fixture instrumentation only; absent from the production owner budget.
        const val PORTABLE_BYTES = 64L * (7 * 8 + 4) + 8 * 24 + 104
        const val QUEUED = 0
        const val ADMITTING = 1
        const val DEFERRED = 2
        const val COMMIT_PENDING = 3
        const val PUBLICATION_PENDING = 4
        const val COMPLETED = 5
        const val REFUSED = 6
        const val REPLACED = 7
        const val LIFECYCLE = 8
        const val NON_MATERIAL = 9
        const val RESET = 10
        private val STATUS = arrayOf("queued", "admitting", "deferred", "commitPending", "publicationPending", "completed", "refused", "replaced", "lifecycle", "nonMaterial", "reset")
    }
}
