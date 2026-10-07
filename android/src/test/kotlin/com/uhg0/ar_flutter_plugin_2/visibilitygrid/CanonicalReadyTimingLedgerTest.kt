package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.PacketCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalReadyTimingLedgerTest {
    @Test
    fun duplicateQueueKeepsFirstTimestampAndCompletesOneEntry() {
        var now = 1_000L
        val ledger = CanonicalReadyTimingLedger(true) { now }
        val identity = identity()

        ledger.recordQueued(identity, CurrentDeltaQueueResult.QUEUED)
        now = 6_000L
        ledger.recordQueued(identity, CurrentDeltaQueueResult.ALREADY_QUEUED)
        now = 11_000L
        ledger.recordFirstCurrentExchange(request(identity))
        now = 21_000L
        ledger.recordStructuralAcknowledgement(7L, 8L, 9L)

        val entry = entry(ledger.snapshotWireMap())
        assertEquals("acknowledged", entry["status"])
        assertEquals(10L, entry["queueToFirstExchangeMicros"])
        assertEquals(10L, entry["firstExchangeToStructuralAckMicros"])
        assertEquals(20L, entry["queueToStructuralAckMicros"])
        assertEquals(1_000L, entry["queuedNs"])
        assertEquals(11_000L, entry["firstExchangeNs"])
        assertEquals(21_000L, entry["structuralAckNs"])
        assertEquals(1L, ledger.snapshotWireMap()["duplicateQueue"])
        assertTrue((ledger.snapshotWireMap()["sampledAtNanoNs"] as Long) >= 21_000L)
    }

    @Test
    fun heldExchangeIsIncompleteUntilTheQualifiedRequestArrives() {
        var now = 1_000L
        val ledger = CanonicalReadyTimingLedger(true) { now }
        val identity = identity()
        ledger.recordQueued(identity, CurrentDeltaQueueResult.QUEUED)
        now = 9_000L

        var entry = entry(ledger.snapshotWireMap())
        assertEquals("queued", entry["status"])
        assertEquals(-1L, entry["queueToFirstExchangeMicros"])
        assertEquals(-1L, entry["queueToStructuralAckMicros"])

        ledger.recordFirstCurrentExchange(request(identity))
        now = 19_000L
        ledger.recordStructuralAcknowledgement(7L, 8L, 9L)
        entry = entry(ledger.snapshotWireMap())
        assertEquals("acknowledged", entry["status"])
        assertEquals(18L, entry["queueToStructuralAckMicros"])
    }

    @Test
    fun resetMarksActiveWorkReplacedAndReportsIncomplete() {
        val ledger = CanonicalReadyTimingLedger(true) { 1_000L }
        ledger.recordQueued(identity(), CurrentDeltaQueueResult.QUEUED)
        ledger.reset("replaced")

        val snapshot = ledger.snapshotWireMap()
        assertEquals(1L, snapshot["resets"])
        assertEquals(1L, snapshot["replaced"])
        assertEquals(1L, snapshot["incomplete"])
        assertEquals("replaced", entry(snapshot)["status"])
    }

    @Test
    fun unattributedRecoveryIsReportedWithoutCreatingAFalseTimingSample() {
        val ledger = CanonicalReadyTimingLedger(true) { 1_000L }
        ledger.recordUnattributedRecovery()

        val snapshot = ledger.snapshotWireMap()
        assertEquals(1L, snapshot["loss"])
        assertEquals(1L, snapshot["incomplete"])
        assertEquals("recoveredWithoutQueuedReceipt", snapshot["lastResetReason"])
        assertTrue((snapshot["entries"] as List<*>).isEmpty())
    }

    @Test
    fun pauseFencesPendingExchangeWithoutCallingItReplaced() {
        val ledger = CanonicalReadyTimingLedger(true) { 1_000L }
        ledger.recordQueued(identity(), CurrentDeltaQueueResult.QUEUED)
        ledger.reset("paused")

        val snapshot = ledger.snapshotWireMap()
        assertEquals("reset", entry(snapshot)["status"])
        assertEquals(1L, snapshot["incomplete"])
        assertEquals(0L, snapshot["replaced"])
        assertEquals("paused", snapshot["lastResetReason"])
    }

    @Test
    fun capacityLossIsExplicitAndDisabledModeHasNoReceiptOwner() {
        val enabled = CanonicalReadyTimingLedger(true) { 1_000L }
        repeat(CanonicalReadyTimingLedger.MAX_ENTRIES) { index ->
            enabled.recordQueued(
                identity(transactionId = index.toLong() + 1L),
                CurrentDeltaQueueResult.QUEUED,
            )
            enabled.recordFirstCurrentExchange(request(identity(transactionId = index.toLong() + 1L)))
            enabled.recordStructuralAcknowledgement(index.toLong() + 1L, 8L, 9L)
        }
        enabled.recordQueued(
            identity(transactionId = CanonicalReadyTimingLedger.MAX_ENTRIES.toLong() + 1L),
            CurrentDeltaQueueResult.QUEUED,
        )
        val completedTail = enabled.snapshotWireMap()["entries"] as List<*>
        assertEquals(CanonicalReadyTimingLedger.MAX_ENTRIES, completedTail.size)
        @Suppress("UNCHECKED_CAST")
        val tailIds = completedTail.map { (it as Map<String, Any>)["transactionId"] as Long }
        assertEquals(
            (2L..(CanonicalReadyTimingLedger.MAX_ENTRIES + 1L)).toList(),
            tailIds,
        )
        assertEquals(1L, enabled.snapshotWireMap()["slotReplacements"])

        val active = CanonicalReadyTimingLedger(true) { 1_000L }
        repeat(CanonicalReadyTimingLedger.MAX_ENTRIES) { index ->
            active.recordQueued(
                identity(transactionId = index.toLong() + 1L),
                CurrentDeltaQueueResult.QUEUED,
            )
        }
        active.recordQueued(
            identity(transactionId = CanonicalReadyTimingLedger.MAX_ENTRIES.toLong() + 1L),
            CurrentDeltaQueueResult.QUEUED,
        )
        assertEquals(1L, active.snapshotWireMap()["loss"])

        var clockCalls = 0
        val disabled = CanonicalReadyTimingLedger(false) {
            clockCalls++
            error("disabled ledger sampled the clock")
        }
        disabled.recordQueued(identity(), CurrentDeltaQueueResult.QUEUED)
        val snapshot = disabled.snapshotWireMap()
        assertFalse(snapshot["enabled"] as Boolean)
        assertTrue("entries" !in snapshot)
        assertEquals(0, clockCalls)
    }

    @Test
    fun flaggedRequestDoesNotQualifyAsCurrentExchange() {
        val ledger = CanonicalReadyTimingLedger(true) { 2_000L }
        val identity = identity()
        ledger.recordQueued(identity, CurrentDeltaQueueResult.QUEUED)
        ledger.recordFirstCurrentExchange(request(identity).copy(requestFlags = 1))

        assertEquals("queued", entry(ledger.snapshotWireMap())["status"])
        assertEquals(0L, ledger.snapshotWireMap()["firstExchange"])
    }

    private fun identity(transactionId: Long = 7L) = CanonicalReadyTimingIdentity(
        bindingGeneration = 2L,
        sessionGeneration = 3L,
        groupGeneration = 4L,
        streamToken = 5L,
        transactionId = transactionId,
        geometryRevision = 8L,
        lineageRevision = 9L,
        previousTransactionId = 6L,
        previousGeometryRevision = 7L,
        previousLineageRevision = 8L,
    )

    private fun request(identity: CanonicalReadyTimingIdentity) = PacketCodec.Request(
        requestFlags = 0,
        streamToken = identity.streamToken,
        acknowledgedTransactionId = identity.previousTransactionId,
        acknowledgedGeometryRevision = identity.previousGeometryRevision,
        acknowledgedLineageRevision = identity.previousLineageRevision,
        nextStyleRevision = 0L,
        maximumResponseBytes = PacketCodec.responseMinimumBytes,
        styleRecords = emptyList(),
        commandBytes = byteArrayOf(),
        requestSequence = 1L,
    )

    @Suppress("UNCHECKED_CAST")
    private fun entry(snapshot: Map<String, Any>): Map<String, Any> =
        (snapshot["entries"] as List<Map<String, Any>>).single()
}
