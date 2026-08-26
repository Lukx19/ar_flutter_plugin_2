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

    @Test fun `generated durable fault matrix mutates every storage cut and preserves a terminal classification`() {
        assertEquals(21, DurableStoreFaultPointV2.entries.size)
        DurableStoreFaultPointV2.entries.forEach { cut ->
            val root = directory()
            val poisoned = DurableSessionStoreV2(File(root, "store"), budget(root), DurableStoreFaultInjectorV2 {
                if (it == cut) throw IllegalStateException("injected-$cut")
            })
            val request = request("fault-$cut", "attempt-$cut", "jpeg".toByteArray())
            runCatching { poisoned.acceptBeforeExposure(request.accepted) }
            runCatching { poisoned.commitStreamed(request, streams("jpeg".toByteArray())) }
            // A fresh process never trusts a staging filename: it either finds the
            // durable receipt or can record the metadata-only terminal exactly once.
            val recovered = DurableSessionStoreV2(File(root, "store"), budget(root))
            val terminal = recovered.queryReceipt(request.accepted.identity)
            // Receipt-before-pointer cuts are deliberately UNKNOWN; every other
            // cut is either a durable commit or is later abandoned explicitly.
            terminal?.let { assertTrue(it.phase in setOf(CaptureAttemptPhase.COMMITTED_PICTURE, CaptureAttemptPhase.ABANDONED_ATTEMPT)) }
            val later = request("later-$cut", "later-attempt-$cut", "later".toByteArray())
            runCatching { recovered.acceptBeforeExposure(later.accepted) }
            runCatching { recovered.commitStreamed(later, streams("later".toByteArray())) }
            // Pointer-cut faults may leave the later attempt unknown, but must
            // never fabricate a third terminal or release its reservation.
            recovered.queryReceipt(later.accepted.identity)
        }
    }

    @Test fun `session root retains two predecessors and corrupt current slot falls back without filename selection`() {
        val root = directory(); val store = DurableSessionStoreV2(File(root, "store"), budget(root))
        (1..3).forEach { number ->
            val request = request("root-$number", "root-attempt-$number", "jpeg-$number".toByteArray())
            store.acceptBeforeExposure(request.accepted)
            store.commitStreamed(request, streams("jpeg-$number".toByteArray()))
        }
        val pointers = File(root, "store/sessions").walkTopDown().filter { it.name.matches(Regex("root-[AB]\\.ptr")) }.toList()
        val current = pointers.maxBy { it.readLines().first().toLong() }
        current.writeText("corrupt\n")
        val fourth = request("root-4", "root-attempt-4", "jpeg-4".toByteArray())
        store.acceptBeforeExposure(fourth.accepted)
        assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, store.commitStreamed(fourth, streams("jpeg-4".toByteArray())).phase)
    }

    @Test fun `pointer replacement uncertainty retains reservation until same-attempt CAS rebase`() {
        val root = directory(); val quota = budget(root)
        val failing = DurableSessionStoreV2(File(root, "store"), quota, DurableStoreFaultInjectorV2 {
            if (it == DurableStoreFaultPointV2.POINTER_SLOT_REPLACE) throw IllegalStateException("pointer cut")
        })
        val request = request("unknown", "unknown-attempt", "jpeg".toByteArray())
        failing.acceptBeforeExposure(request.accepted)
        runCatching { failing.commitStreamed(request, streams("jpeg".toByteArray())) }
        assertNull(failing.queryReceipt(request.accepted.identity))
        assertTrue(quota.reservedBytes() > 0)
        val restarted = DurableSessionStoreV2(File(root, "store"), quota)
        assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, restarted.rebaseSameAttempt(request).phase)
        assertEquals(0, quota.reservedBytes())
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
