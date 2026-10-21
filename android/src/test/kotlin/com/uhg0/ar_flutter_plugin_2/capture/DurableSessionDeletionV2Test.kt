package com.uhg0.ar_flutter_plugin_2.capture

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole-session deletion of the native durable store (`deleteNativeCaptureSession`): tombstone
 * first, the published bytes leave the budget exactly once, blobs go, the session stays fenced,
 * and a crash at any step converges on the next call.
 */
class DurableSessionDeletionV2Test {
    private val directories = mutableListOf<File>()
    @After fun cleanUp() { directories.forEach { it.deleteRecursively() } }

    @Test fun `tombstone is idempotent and keeps the fence`() {
        val root = directory(); val store = store(root, budget(root))
        val request = request("commit-1", "attempt-1", session = "deleted")
        store.acceptCaptureBeforeExposure(request)
        store.tombstoneSession("deleted")
        store.tombstoneSession("deleted")
        assertEquals("deleted\n", File(sessionDirectory(root, "deleted"), "tombstone").readText())
        assertThrows(DurableStoreConflictV2::class.java) { store.commitStreamed(request, streams(JPEG_A)) }
        assertEquals(0L, budget(root).reservedBytes())
    }

    @Test fun `session deletion reclaims its committed bytes once and keeps other sessions`() {
        val root = directory(); val budget = budget(root); val store = store(root, budget)
        val deleted = commit(store, "deleted", 1, JPEG_A)
        commit(store, "deleted", 2, JPEG_B)
        val kept = commit(store, "kept", 1, JPEG_KEPT)
        val pending = request("deleted-pending", "deleted-pending", session = "deleted")
        store.acceptCaptureBeforeExposure(pending)
        assertEquals(9L, budget.committedBytes())
        val keptBefore = snapshot(sessionDirectory(root, "kept"))

        assertEquals(7L, store.deleteSession("deleted"))
        assertEquals(2L, budget.committedBytes())
        assertEquals(0L, budget.reservedBytes())
        val directory = sessionDirectory(root, "deleted")
        assertEquals(listOf("tombstone"), directory.list()!!.toList())
        assertFalse(root.walkTopDown().any { it.name.startsWith("jpeg.${sha256Hex(JPEG_A)}") })
        val reclaimId = DurableSessionStoreV2.sessionReclaimId("deleted")
        assertEquals("DONE\n$reclaimId\n7\n9\n", File(root, "budget/reclaims-v2/verified-$reclaimId.reclaim").readText())

        assertEquals(0L, store.deleteSession("deleted"))
        val restartedBudget = budget(root); val restarted = store(root, restartedBudget)
        assertEquals(0L, restarted.deleteSession("deleted"))
        assertEquals(2L, restartedBudget.committedBytes())
        assertEquals(keptBefore, snapshot(sessionDirectory(root, "kept")))
        assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, restarted.queryReceipt(kept.accepted.identity)?.phase)
        assertNull(restarted.queryReceipt(deleted.accepted.identity))
    }

    @Test fun `live attempts of a deleted session are refused and their reservations closed`() {
        val root = directory(); val budget = budget(root); val store = store(root, budget)
        commit(store, "deleted", 1, JPEG_A)
        val live = request("live-commit", "live-attempt", session = "deleted")
        store.acceptCaptureBeforeExposure(live)
        assertTrue(budget.reservedBytes() > 0)

        assertEquals(3L, store.deleteSession("deleted"))
        assertEquals(0L, budget.reservedBytes())
        assertThrows(DurableStoreConflictV2::class.java) { store.commitStreamed(live, streams(JPEG_A)) }
        val later = request("later-commit", "later-attempt", session = "deleted")
        assertThrows(DurableStoreConflictV2::class.java) { store.acceptCaptureBeforeExposure(later) }
        assertNull(store.queryReceipt(live.accepted.identity))
        assertEquals(0L, budget.committedBytes())
        assertEquals(0L, budget.reservedBytes())
        assertThrows(IllegalArgumentException::class.java) { store.deleteSession("") }
    }

    /**
     * A commit whose pointer is published but whose settlement was cut keeps a live reservation.
     * The tombstone charges it, so the session reclaim never debits another session's bytes.
     */
    @Test fun `published commit with an unsettled reservation is charged before the reclaim`() {
        val root = directory()
        val store = store(root, budget(root))
        commit(store, "kept", 1, JPEG_KEPT)
        commit(store, "deleted", 1, JPEG_A)
        unsettledPublishedCommit(root, "deleted", 2, JPEG_B)
        assertEquals(5L, budget(root).committedBytes())

        val budget = budget(root)
        assertEquals(7L, store(root, budget).deleteSession("deleted"))
        assertEquals(2L, budget.committedBytes())
        assertEquals(0L, budget.reservedBytes())
    }

    @Test fun `session deletion converges after a crash at every step`() {
        val cuts = listOf(
            "budget" to DurableStoreFaultPointV2.RESERVATION_COMMIT_REPLACE,
            "budget" to DurableStoreFaultPointV2.LEDGER_REPLACE,
            "budget" to DurableStoreFaultPointV2.RECLAIM_MARKER_REPLACE,
            "budget" to DurableStoreFaultPointV2.POINTER_DIRECTORY_SYNC,
            "budget" to DurableStoreFaultPointV2.DELETE_RECLAIM,
            "store" to DurableStoreFaultPointV2.TOMBSTONE_RECORD,
            "store" to DurableStoreFaultPointV2.TOMBSTONE_DIRECTORY_SYNC,
            "store" to DurableStoreFaultPointV2.DELETE_RECLAIM,
        )
        var exercised = 0
        for ((side, point) in cuts) {
            for (nth in 1..12) {
                val root = directory()
                val setup = store(root, budget(root))
                commit(setup, "kept", 1, JPEG_KEPT)
                commit(setup, "deleted", 1, JPEG_A)
                unsettledPublishedCommit(root, "deleted", 2, JPEG_B)
                // A committed receipt whose pointer switch never happened: released, never charged.
                val lost = request("deleted-commit-3", "deleted-attempt-3", session = "deleted", jpeg = JPEG_C)
                val unresolved = store(root, budget(root), DurableStoreFaultInjectorV2 {
                    if (it == DurableStoreFaultPointV2.POINTER_SLOT_REPLACE) throw IllegalStateException("slot rename lost")
                })
                unresolved.acceptCaptureBeforeExposure(lost)
                assertThrows(IllegalStateException::class.java) { unresolved.commitStreamed(lost, streams(JPEG_C)) }
                setup.acceptCaptureBeforeExposure(request("deleted-pending", "deleted-pending", session = "deleted"))
                val keptBefore = snapshot(sessionDirectory(root, "kept"))

                var visits = 0
                val cut = DurableStoreFaultInjectorV2 {
                    if (it == point && ++visits == nth) throw IllegalStateException("injected $side $point #$nth")
                }
                val poisoned = if (side == "budget") store(root, budget(root, cut)) else store(root, budget(root), cut)
                val label = "$side $point #$nth"
                try {
                    poisoned.deleteSession("deleted")
                    assertTrue("$label completed although its cut was reached", visits < nth)
                    break
                } catch (error: IllegalStateException) {
                    // A cut directory sync surfaces wrapped as an unknown pointer sync.
                    val messages = generateSequence<Throwable>(error) { it.cause }.map { it.message }.toList()
                    assertTrue("$label: $messages", "injected $side $point #$nth" in messages)
                    exercised++
                }
                // A restart between the crash and the retry: binding-open recovery projects
                // nothing of the deleted session, publishes no root in it and never fails.
                val reopenedBudget = budget(root)
                val reopened = store(root, reopenedBudget)
                val tombstoned = File(sessionDirectory(root, "deleted"), "tombstone").isFile
                val projected = reopened.recoverAtOpen(log = { throw AssertionError("$label: $it") })
                if (tombstoned) assertTrue(label, projected.none { it.attemptId.startsWith("deleted") })
                reopened.deleteSession("deleted")
                reopened.deleteSession("deleted")
                val budget = budget(root)
                assertEquals("$label: only the kept session stays charged", 2L, budget.committedBytes())
                assertEquals(label, 0L, budget.reservedBytes())
                assertEquals(label, listOf("tombstone"), sessionDirectory(root, "deleted").list()!!.toList())
                assertEquals(label, keptBefore, snapshot(sessionDirectory(root, "kept")))
                assertEquals(label, CaptureAttemptPhase.COMMITTED_PICTURE,
                    store(root, budget).queryReceipt(request("kept-commit-1", "kept-attempt-1", session = "kept").accepted.identity)?.phase)
            }
        }
        assertTrue("the crash matrix reached every deletion step ($exercised)", exercised >= 20)
    }

    @Test fun `deletion reaches the open view's store and opens the root once the view closed`() {
        val root = directory()
        val leaseKey = "test-root-${root.name}"
        val viewBudget = budget(root); val viewStore = store(root, viewBudget)
        commit(viewStore, "deleted", 1, JPEG_A)
        val live = request("live-commit", "live-attempt", session = "deleted")
        viewStore.acceptCaptureBeforeExposure(live)
        var opens = 0
        var waits = 0
        var viewOpen = true
        val open = {
            opens++
            if (viewOpen) throw NativeCaptureDurableRootPendingV2()
            val budget = budget(root)
            NativeCaptureRootStoreHandleV2(AutoCloseable { budget.close() }, store(root, budget))
        }
        NativeCaptureOpenRootStoresV2.register(leaseKey, viewStore)
        try {
            assertEquals(3L, NativeCaptureSessionDeletionV2.delete("deleted", open, leaseKey) { waits++ })
            assertEquals(0, waits)
            assertThrows(DurableStoreConflictV2::class.java) { viewStore.commitStreamed(live, streams(JPEG_A)) }
        } finally {
            NativeCaptureOpenRootStoresV2.unregister(leaseKey, viewStore)
        }
        // Between unregistering its store and releasing its lease the view is waited for.
        assertThrows(NativeCaptureDurableRootPendingV2::class.java) {
            NativeCaptureSessionDeletionV2.delete("deleted", open, leaseKey) { waits++ }
        }
        assertEquals(NativeCaptureSessionDeletionV2.ATTEMPTS, waits)
        viewOpen = false
        assertEquals(0L, NativeCaptureSessionDeletionV2.delete("deleted", open, leaseKey) { waits++ })
        assertEquals(0L, viewBudget.committedBytes())
        assertThrows(IllegalArgumentException::class.java) {
            NativeCaptureSessionDeletionV2.delete("", open, leaseKey) { }
        }
        assertTrue(opens > NativeCaptureSessionDeletionV2.ATTEMPTS)
    }

    private fun commit(store: DurableSessionStoreV2, session: String, index: Int, jpeg: ByteArray): CaptureCommitRequest {
        val request = request("$session-commit-$index", "$session-attempt-$index", session = session, jpeg = jpeg)
        store.acceptCaptureBeforeExposure(request)
        store.commitStreamed(request, streams(jpeg))
        return request
    }

    /** Pointer published, settlement cut before its `COMMITTING` record: the reservation stays live. */
    private fun unsettledPublishedCommit(root: File, session: String, index: Int, jpeg: ByteArray) {
        val budget = budget(root, DurableStoreFaultInjectorV2 {
            if (it == DurableStoreFaultPointV2.RESERVATION_COMMIT_REPLACE) throw IllegalStateException("settlement cut")
        })
        val store = store(root, budget)
        val request = request("$session-commit-$index", "$session-attempt-$index", session = session, jpeg = jpeg)
        store.acceptCaptureBeforeExposure(request)
        assertThrows(IllegalStateException::class.java) { store.commitStreamed(request, streams(jpeg)) }
        assertTrue(budget(root).reservedBytes() > 0)
    }

    private fun request(commit: String, attempt: String, session: String, jpeg: ByteArray = JPEG_A): CaptureCommitRequest {
        val identity = CaptureAttemptIdentity(attempt, commit, 1, CaptureLifecycleCut(session, 1, "group-1", 1, "ar-1", "view-1", 1, "binding-1", 1, 1))
        val profile = CaptureComponentProfile("jpeg", setOf(CaptureComponentKind.JPEG), 64, 64)
        val accepted = CaptureAcceptedAttempt(identity, CaptureLane.MANUAL, profile, CaptureReservationLiability(0, 64L, 1L, 1, 0, true), digest("intent"), digest("accepted"))
        return CaptureCommitRequest(
            accepted,
            listOf(CaptureComponentDescriptor(CaptureComponentKind.JPEG, jpeg.size.toLong(), sha(jpeg), "object")),
            1,
            digest("pose"), digest("camera"), digest("validation"), digest("ledger"),
            NativeCaptureRecoveryContextV2(
                session, "group-1", 0, 1, "manual", 1, 1, 1,
                listOf(0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0, 1.0),
                identityMatrix(), identityMatrix(), identityMatrix(), identityMatrix(),
            ),
        )
    }
    private fun identityMatrix() = List(16) { if (it % 5 == 0) 1.0 else 0.0 }
    private fun streams(bytes: ByteArray) = listOf(CaptureComponentStreamV2(CaptureComponentKind.JPEG, ByteArrayInputStream(bytes)))
    private fun sessionDirectory(root: File, session: String) = File(root, "store/sessions/${sha256Hex(session.toByteArray())}")
    private fun snapshot(directory: File): Map<String, String> = directory.walkTopDown().filter(File::isFile)
        .associate { it.relativeTo(directory).path to sha256Hex(it.readBytes()) }
    private fun store(root: File, budget: StorageBudgetCoordinatorV2, faults: DurableStoreFaultInjectorV2 = DurableStoreFaultInjectorV2 { }) =
        DurableSessionStoreV2(File(root, "store"), budget, faults, JvmDescriptorFilesystemV2())
    private fun budget(root: File, faults: DurableStoreFaultInjectorV2 = DurableStoreFaultInjectorV2 { }) = StorageBudgetCoordinatorV2(
        File(root, "budget"), StorageBudgetPolicyV2(1024 * 1024, 0),
        JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }), faults,
    ) { 1024 * 1024 }
    private fun directory(): File = File.createTempFile("durable-delete-v2", "").also { it.delete(); assertTrue(it.mkdirs()); directories += it }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).map { it.toInt() and 0xff }
    private fun digest(value: String) = sha(value.toByteArray())
    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val JPEG_A = byteArrayOf(1, 2, 3)
        val JPEG_B = byteArrayOf(4, 5, 6, 7)
        val JPEG_C = byteArrayOf(8, 8, 8, 8, 8)
        val JPEG_KEPT = byteArrayOf(9, 9)
    }
}
