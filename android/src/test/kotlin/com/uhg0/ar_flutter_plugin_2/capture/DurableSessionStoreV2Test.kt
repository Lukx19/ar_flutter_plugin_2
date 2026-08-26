package com.uhg0.ar_flutter_plugin_2.capture

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableSessionStoreV2Test {
    private val directories = mutableListOf<File>()
    @After fun cleanUp() { directories.forEach { it.deleteRecursively() } }

    @Test fun `acceptance reserves durable liability before streamed commit and replay is exact after restart`() {
        val root = directory()
        val budget = budget(root)
        val store = DurableSessionStoreV2(File(root, "store"), budget)
        val request = request("commit-1", "attempt-1", jpeg = "jpeg".toByteArray())

        val accepted = store.acceptBeforeExposure(request.accepted)
        assertEquals(CaptureAttemptPhase.RESERVED_ACCEPTED, accepted.phase)
        assertEquals(request.accepted.reservation.totalStoreLiability, budget.reservedBytes())

        val committed = store.commitStreamed(request, streams("jpeg".toByteArray()))
        assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, committed.phase)
        assertEquals(0, budget.reservedBytes())
        assertEquals(committed.requestHash, store.commitStreamed(request, streams("jpeg".toByteArray())).requestHash)

        val recovered = DurableSessionStoreV2(File(root, "store"), budget(root))
        assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, recovered.queryReceipt(request.accepted.identity)?.phase)
    }

    @Test fun `changed bytes conflict without moving selected root and abandonment releases once for later success`() {
        val root = directory(); val store = DurableSessionStoreV2(File(root, "store"), budget(root))
        val first = request("commit-2", "attempt-2", jpeg = "jpeg".toByteArray())
        store.acceptBeforeExposure(first.accepted)
        try { store.commitStreamed(first, streams("changed".toByteArray())) } catch (_: IllegalStateException) { }
        val abandoned = store.abandon(CaptureTerminal(CaptureTerminalKind.ABANDONED_ATTEMPT, first.accepted.identity, "failed", "component-failure"))
        assertEquals(CaptureAttemptPhase.ABANDONED_ATTEMPT, abandoned.phase)
        assertEquals(abandoned.receiptHash, store.abandon(CaptureTerminal(CaptureTerminalKind.ABANDONED_ATTEMPT, first.accepted.identity, "changed", "late")).receiptHash)

        val later = request("commit-3", "attempt-3", jpeg = "later".toByteArray())
        store.acceptBeforeExposure(later.accepted)
        assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, store.commitStreamed(later, streams("later".toByteArray())).phase)
    }

    @Test fun `tombstone wins over a late capture callback`() {
        val root = directory(); val store = DurableSessionStoreV2(File(root, "store"), budget(root))
        val request = request("commit-4", "attempt-4", jpeg = "jpeg".toByteArray())
        store.acceptBeforeExposure(request.accepted)
        store.tombstoneSession(request.accepted.identity.lifecycleCut.sessionId)
        try { store.commitStreamed(request, streams("jpeg".toByteArray())); throw AssertionError("expected tombstone fence") }
        catch (_: DurableStoreConflictV2) { }
        assertNull(store.queryReceipt(request.accepted.identity))
    }

    private fun request(commit: String, attempt: String, jpeg: ByteArray): CaptureCommitRequest {
        val identity = CaptureAttemptIdentity(attempt, commit, 1, CaptureLifecycleCut("session-1", 1, "group-1", 1, "ar-1", "view-1", 1, "binding-1", 1, 1))
        val profile = CaptureComponentProfile("jpeg", setOf(CaptureComponentKind.JPEG), 64, 64)
        val accepted = CaptureAcceptedAttempt(identity, CaptureLane.MANUAL, profile, CaptureReservationLiability(0, 64, 1, 1, 0, true), digest("intent"), digest("accepted"))
        return CaptureCommitRequest(accepted, listOf(CaptureComponentDescriptor(CaptureComponentKind.JPEG, jpeg.size.toLong(), sha(jpeg), "object")), 1, digest("pose"), digest("camera"), digest("validation"), digest("ledger"))
    }
    private fun streams(bytes: ByteArray) = listOf(CaptureComponentStreamV2(CaptureComponentKind.JPEG, ByteArrayInputStream(bytes)))
    private fun budget(root: File) = StorageBudgetCoordinatorV2(File(root, "budget"), StorageBudgetPolicyV2(1024 * 1024, 0)) { 1024 * 1024 }
    private fun directory(): File = File.createTempFile("durable-store-v2", "").also { it.delete(); assertTrue(it.mkdirs()); directories += it }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).map { it.toInt() and 0xff }
    private fun digest(value: String) = sha(value.toByteArray())
}
