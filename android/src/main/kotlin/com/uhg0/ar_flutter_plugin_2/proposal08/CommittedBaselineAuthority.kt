package com.uhg0.ar_flutter_plugin_2.proposal08

/**
 * Process-scoped owner for the last native committed cut.
 *
 * A platform view is replaceable, but the capture-group authority is not. The
 * plugin keeps this small value object outside each view so a replacement can
 * start from the same committed geometry/style cut instead of silently
 * reverting to ZERO. Transaction identity remains owned by the stream; a
 * binding-start result therefore reports the canonical binding transaction
 * value while the full cut remains available to the native stream.
 */
data class CommittedBaselineScopeV1(
    val sessionId: Uuid,
    val captureGroupId: Uuid,
    val sessionGeneration: Long,
    val groupGeneration: Long,
) {
    init {
        require(sessionGeneration >= 0 && groupGeneration >= 0)
    }

    companion object {
        fun from(request: ControlRequest): CommittedBaselineScopeV1 =
            CommittedBaselineScopeV1(
                sessionId = request.sessionId,
                captureGroupId = request.captureGroupId,
                sessionGeneration = request.sessionGeneration,
                groupGeneration = request.groupGeneration,
            )
    }
}

/** Exact identity used to reconcile an outcome-unknown structural request. */
data class CommitReceiptQueryV1(
    val controlRequestId: Uuid,
    val scope: CommittedBaselineScopeV1,
    val nativeStreamToken: ByteArray,
    val workerBindingToken: ByteArray,
    val streamToken: Long,
    val requestSequence: Long,
    val transactionId: Long,
    val targetGeometryRevision: Long,
    val targetLineageRevision: Long,
) {
    init {
        require(nativeStreamToken.size == 16 && workerBindingToken.size == 16)
        require(streamToken > 0 && requestSequence > 0 && transactionId > 0)
        require(targetGeometryRevision >= 0 && targetLineageRevision >= 0)
    }

    fun bindingQualifier(): ByteArray = nativeStreamToken + workerBindingToken

    fun sameIdentity(other: CommitReceiptQueryV1): Boolean =
        controlRequestId == other.controlRequestId &&
            scope == other.scope &&
            nativeStreamToken.contentEquals(other.nativeStreamToken) &&
            workerBindingToken.contentEquals(other.workerBindingToken) &&
            streamToken == other.streamToken &&
            requestSequence == other.requestSequence &&
            transactionId == other.transactionId &&
            targetGeometryRevision == other.targetGeometryRevision &&
            targetLineageRevision == other.targetLineageRevision
}

/** Bounded scalar outcome for one exact COMMIT attempt. */
data class CommitReceiptV1(
    val query: CommitReceiptQueryV1,
    val committed: Boolean,
    val baseline: CommittedBaselineV1,
    val rootIsolateSurfaceBytes: Long = 0,
) {
    init {
        require(rootIsolateSurfaceBytes == 0L)
        if (committed) {
            require(baseline.transactionId == query.transactionId)
            require(baseline.geometryRevision == query.targetGeometryRevision)
            require(baseline.lineageRevision == query.targetLineageRevision)
        } else {
            require(baseline == CommittedBaselineV1.ZERO)
        }
    }
}

class CommittedBaselineAuthority {
    private val values = mutableMapOf<CommittedBaselineScopeV1, CommittedBaselineV1>()
    private val receipts = mutableListOf<CommitReceiptV1>()

    @Synchronized
    fun snapshot(scope: CommittedBaselineScopeV1): CommittedBaselineV1 =
        values[scope] ?: CommittedBaselineV1.ZERO

    @Synchronized
    fun publish(scope: CommittedBaselineScopeV1, next: CommittedBaselineV1) {
        values[scope] = next
    }

    /** Atomically records the authoritative COMMIT outcome and baseline. */
    @Synchronized
    fun publishCommit(
        query: CommitReceiptQueryV1,
        baseline: CommittedBaselineV1,
    ) {
        values[query.scope] = baseline
        publishReceipt(CommitReceiptV1(query, committed = true, baseline = baseline))
    }

    /** Records an abandon-wins zero decision without changing the baseline. */
    @Synchronized
    fun publishAbandon(query: CommitReceiptQueryV1) {
        publishReceipt(
            CommitReceiptV1(
                query = query,
                committed = false,
                baseline = CommittedBaselineV1.ZERO,
            ),
        )
    }

    /** Returns only the exact prior outcome; this method has no side effects. */
    @Synchronized
    fun queryReceipt(query: CommitReceiptQueryV1): CommitReceiptV1? =
        receipts.firstOrNull { it.query.sameIdentity(query) }

    private fun publishReceipt(receipt: CommitReceiptV1) {
        val existing = receipts.indexOfFirst { it.query.sameIdentity(receipt.query) }
        if (existing >= 0) receipts.removeAt(existing)
        receipts += receipt
        while (receipts.size > MAX_RECEIPTS) receipts.removeAt(0)
    }

    private companion object {
        const val MAX_RECEIPTS = 8
    }
}

fun CommitReceiptV1.toMap(): Map<String, Any?> = mapOf(
    "decision" to if (committed) "commit" else "abandon",
    "controlRequestId" to query.controlRequestId.hex(),
    "sessionId" to query.scope.sessionId.hex(),
    "captureGroupId" to query.scope.captureGroupId.hex(),
    "sessionGeneration" to query.scope.sessionGeneration,
    "groupGeneration" to query.scope.groupGeneration,
    "nativeStreamToken" to query.nativeStreamToken.copyOf(),
    "workerBindingToken" to query.workerBindingToken.copyOf(),
    "streamToken" to query.streamToken,
    "requestSequence" to query.requestSequence,
    "transactionId" to query.transactionId,
    "targetGeometryRevision" to query.targetGeometryRevision,
    "targetLineageRevision" to query.targetLineageRevision,
    "baseline" to baseline.toMap(),
    "rootIsolateSurfaceBytes" to rootIsolateSurfaceBytes,
)

private fun CommittedBaselineV1.toMap(): Map<String, Any?> = mapOf(
    "transactionId" to transactionId,
    "geometryRevision" to geometryRevision,
    "lineageRevision" to lineageRevision,
    "styleRevision" to styleRevision,
    "evidenceRevision" to evidenceRevision,
    "captureRevision" to captureRevision,
    "coverageRevision" to coverageRevision,
    "producedStyleRevision" to producedStyleRevision,
    "regionManifestRevision" to regionManifestRevision,
    "schemaRootRevision" to schemaRootRevision,
    "nextSurfaceIdHighWater" to nextSurfaceIdHighWater,
    "schemaRootHashIdentity" to schemaRootHashIdentity,
    "manifestRootHashIdentity" to manifestRootHashIdentity,
    "groupFrameConvention" to groupFrameConvention,
    "matrixConvention" to matrixConvention,
    "directionConvention" to directionConvention,
    "normalEncoding" to normalEncoding,
    "groupFromWorldIdentity" to groupFromWorldIdentity,
    "worldFromGroupIdentity" to worldFromGroupIdentity,
)

private fun Uuid.hex(): String = bytes.joinToString("") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}
