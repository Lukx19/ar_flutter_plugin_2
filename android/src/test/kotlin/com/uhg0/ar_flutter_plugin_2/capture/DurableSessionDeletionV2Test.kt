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

    @Test fun `session deletion converges after a crash at every step`() = crashMatrix(recoverBeforeRetry = true)

    /** The retry runs on a reopened store with no binding-open recovery in between. */
    @Test fun `session deletion converges after a crash at every step without startup recovery`() =
        crashMatrix(recoverBeforeRetry = false)

    /**
     * Cuts every visit of each deletion write (up to [MAX_VISITS]), then retries twice. Every cut
     * point must fire at least once and every visit must be cut before the deletion completes.
     */
    private fun crashMatrix(recoverBeforeRetry: Boolean) {
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
        val fired = linkedMapOf<String, Int>()
        val completedAfter = linkedMapOf<String, Int>()
        for ((side, point) in cuts) {
            val cutPoint = "$side $point"
            fired[cutPoint] = 0
            for (nth in 1..MAX_VISITS) {
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
                    if (it == point && ++visits == nth) throw IllegalStateException("injected $cutPoint #$nth")
                }
                val poisoned = if (side == "budget") store(root, budget(root, cut)) else store(root, budget(root), cut)
                val label = "$cutPoint #$nth (recovery before retry: $recoverBeforeRetry)"
                try {
                    poisoned.deleteSession("deleted")
                    assertTrue("$label completed although its cut was reached", visits < nth)
                    completedAfter[cutPoint] = nth
                    break
                } catch (error: IllegalStateException) {
                    // A cut directory sync surfaces wrapped as an unknown pointer sync.
                    val messages = generateSequence<Throwable>(error) { it.cause }.map { it.message }.toList()
                    assertTrue("$label: $messages", "injected $cutPoint #$nth" in messages)
                    fired[cutPoint] = fired.getValue(cutPoint) + 1
                }
                val reopenedBudget = budget(root)
                val reopened = store(root, reopenedBudget)
                if (recoverBeforeRetry) {
                    // A restart between the crash and the retry: binding-open recovery projects
                    // nothing of the deleted session, publishes no root in it and never fails.
                    val tombstoned = File(sessionDirectory(root, "deleted"), "tombstone").isFile
                    val projected = reopened.recoverAtOpen(log = { throw AssertionError("$label: $it") })
                    if (tombstoned) assertTrue(label, projected.none { it.attemptId.startsWith("deleted") })
                }
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
        assertEquals("every cut point fired: $fired", emptyList<String>(), fired.filterValues { it == 0 }.keys.toList())
        assertEquals("every visit was cut before completion: $completedAfter", fired.keys.toList(), completedAfter.keys.toList())
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

    @Test fun `authoritative reconciliation removes orphan sessions once and preserves live bytes`() {
        val root = directory(); val budget = budget(root); val store = store(root, budget)
        commit(store, "legacy-deleted", 1, JPEG_A)
        commit(store, "kept", 1, JPEG_KEPT)
        val before = snapshot(sessionDirectory(root, "kept"))
        assertEquals(3L, store.reconcileSessions(listOf("kept")))
        assertEquals(0L, store.reconcileSessions(listOf("kept")))
        assertEquals(2L, budget.committedBytes())
        assertEquals(before, snapshot(sessionDirectory(root, "kept")))
        assertEquals(listOf("tombstone"), sessionDirectory(root, "legacy-deleted").list()!!.toList())
    }

    @Test fun `reconciliation finishes failed native deletion on restart`() {
        val root = directory(); val setup = store(root, budget(root))
        commit(setup, "deleted", 1, JPEG_A)
        commit(setup, "kept", 1, JPEG_KEPT)
        val cut = store(root, budget(root), DurableStoreFaultInjectorV2 {
            if (it == DurableStoreFaultPointV2.DELETE_RECLAIM) throw IllegalStateException("removal cut")
        })
        assertThrows(IllegalStateException::class.java) { cut.deleteSession("deleted") }
        val budget = budget(root)
        store(root, budget).reconcileSessions(listOf("kept"))
        assertEquals(2L, budget.committedBytes())
        assertEquals(listOf("tombstone"), sessionDirectory(root, "deleted").list()!!.toList())
    }

    @Test fun `damaged history deletion converges without debiting other sessions`() {
        for (damage in listOf("accepted", "receipt", "root", "fork")) {
            val root = directory(); val setup = store(root, budget(root))
            repeat(3) { commit(setup, "deleted", it + 1, JPEG_A) }
            commit(setup, "kept", 1, JPEG_KEPT)
            val directory = sessionDirectory(root, "deleted")
            val keptBefore = snapshot(sessionDirectory(root, "kept"))
            when (damage) {
                "accepted", "receipt" -> directory.walkTopDown().first { it.name == "$damage.properties" }.writeText("truncated")
                "root" -> File(directory, "objects").listFiles()!!.forEach { it.writeText("truncated") }
                "fork" -> {
                    val pointers = listOf(File(directory, "root-A.ptr"), File(directory, "root-B.ptr"))
                    val prior = pointers.minBy { it.readLines().first().toLong() }
                    val root2 = prior.readLines()[1]
                    val root1 = File(directory, "objects/$root2.root").readLines().first { it.startsWith("previous=") }.removePrefix("previous=")
                    val divergent = "schema=5\nrevision=3\nrequest=${"a".repeat(64)}\nprevious=$root2\nsecondPrevious=$root1\n".toByteArray()
                    val hash = sha256Hex(divergent)
                    File(directory, "objects/$hash.root").writeBytes(divergent)
                    prior.writeText("3\n$hash\nbranch\n")
                }
            }
            val cut = store(root, budget(root), DurableStoreFaultInjectorV2 {
                if (it == DurableStoreFaultPointV2.DELETE_RECLAIM) throw IllegalStateException("removal cut")
            })
            assertThrows(IllegalStateException::class.java) { cut.deleteSession("deleted") }
            val budget = budget(root); val restarted = store(root, budget)
            restarted.deleteSession("deleted")
            val remaining = budget.committedBytes()
            assertTrue("$damage cannot debit kept bytes", remaining >= 2L)
            assertEquals(0L, restarted.deleteSession("deleted"))
            assertEquals(remaining, budget.committedBytes())
            assertEquals(listOf("tombstone"), directory.list()!!.toList())
            assertEquals(keptBefore, snapshot(sessionDirectory(root, "kept")))
            assertTrue("$damage metadata preserved", File(root, "store/quarantine/${directory.name}").walkTopDown().any { it.extension == "record" })
        }
    }

    @Test fun `deletion preserves exact malformed receipt and pointer bytes with a valid alternate or no slot`() {
        for (damage in listOf("receipt", "unused-pointer", "all-pointers")) {
            val root = directory(); val budget = budget(root); val store = store(root, budget)
            commit(store, "deleted", 1, JPEG_A)
            commit(store, "deleted", 2, JPEG_B)
            commit(store, "kept", 1, JPEG_KEPT)
            val keptBefore = snapshot(sessionDirectory(root, "kept"))
            val directory = sessionDirectory(root, "deleted")
            val originals = when (damage) {
                "receipt" -> mapOf(directory.walkTopDown().first { it.name == "receipt.properties" } to "truncated\n".toByteArray())
                "unused-pointer" -> mapOf(File(directory, "root-A.ptr") to "damaged unused slot\n".toByteArray())
                else -> mapOf(File(directory, "root-A.ptr") to "damaged A\n".toByteArray(), File(directory, "root-B.ptr") to "damaged B\n".toByteArray())
            }
            originals.forEach { (file, bytes) -> file.writeBytes(bytes) }
            store.deleteSession("deleted")
            val restartedBudget = budget(root); val restarted = store(root, restartedBudget)
            assertEquals(0L, restarted.deleteSession("deleted"))
            for (bytes in originals.values) assertEquals(bytes.toList(), quarantineFile(root, "deleted", bytes).readBytes().toList())
            assertTrue(restartedBudget.committedBytes() >= 2L)
            assertEquals(keptBefore, snapshot(sessionDirectory(root, "kept")))
            assertEquals(listOf("tombstone"), directory.list()!!.toList())
        }
    }

    @Test fun `legitimate abandoned receipt deletion needs no quarantine and preserves kept charge`() {
        val root = directory(); val budget = budget(root); val store = store(root, budget)
        commit(store, "kept", 1, JPEG_KEPT)
        store.acceptCaptureBeforeExposure(request("pending", "pending", session = "deleted"))
        store.recoverAtOpen()
        assertEquals(0L, store.deleteSession("deleted"))
        assertEquals(2L, budget.committedBytes())
        assertFalse(File(root, "store/quarantine").exists())
    }

    @Test fun `quarantine write or readback failure keeps exact damaged originals for retry`() {
        for (failure in listOf("write", "readback")) {
            val root = directory(); val setup = store(root, budget(root))
            commit(setup, "deleted", 1, JPEG_A)
            commit(setup, "kept", 1, JPEG_KEPT)
            val directory = sessionDirectory(root, "deleted")
            val original = File(directory, "root-A.ptr")
            val bytes = "damaged pointer\n".toByteArray(); original.writeBytes(bytes)
            val target = quarantineFile(root, "deleted", bytes)
            target.parentFile.mkdirs()
            if (failure == "write") target.mkdirs() else target.writeText("wrong preserved bytes")
            assertThrows(Exception::class.java) { setup.deleteSession("deleted") }
            assertEquals(bytes.toList(), original.readBytes().toList())
            assertEquals(5L, budget(root).committedBytes())
            target.deleteRecursively()
            store(root, budget(root)).deleteSession("deleted")
            assertEquals(bytes.toList(), target.readBytes().toList())
            assertEquals(listOf("tombstone"), directory.list()!!.toList())
        }
    }

    @Test fun `authoritative reconciliation preserves unreadable identity after a tombstone cut`() {
        val root = directory(); val setup = store(root, budget(root))
        val deleted = commit(setup, "deleted", 1, JPEG_A)
        val kept = commit(setup, "kept", 1, JPEG_KEPT)
        val directory = sessionDirectory(root, "deleted")
        val keptBefore = snapshot(sessionDirectory(root, "kept"))
        val bytes = "unreadable accepted identity\n".toByteArray()
        directory.walkTopDown().first { it.name == "accepted.properties" }.writeBytes(bytes)
        val cut = store(root, budget(root), DurableStoreFaultInjectorV2 {
            if (it == DurableStoreFaultPointV2.TOMBSTONE_DIRECTORY_SYNC) throw IllegalStateException("tombstone cut")
        })
        assertThrows(IllegalStateException::class.java) { cut.deleteSession("deleted") }
        assertFalse(quarantineFile(root, "deleted", bytes).exists())
        val budget = budget(root); val restarted = store(root, budget)
        assertEquals(0L, restarted.reconcileSessions(listOf("kept")))
        assertEquals(bytes.toList(), quarantineFile(root, "deleted", bytes).readBytes().toList())
        assertEquals(5L, budget.committedBytes())
        assertEquals(keptBefore, snapshot(sessionDirectory(root, "kept")))
        assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, restarted.queryReceipt(kept.accepted.identity)?.phase)
        assertNull(restarted.queryReceipt(deleted.accepted.identity))
        assertEquals(listOf("tombstone"), directory.list()!!.toList())
        val evidence = snapshot(File(root, "store/quarantine"))
        assertEquals(0L, restarted.reconcileSessions(listOf("kept")))
        assertEquals(evidence, snapshot(File(root, "store/quarantine")))
        assertEquals(5L, budget.committedBytes())
    }

    private fun quarantineFile(root: File, session: String, bytes: ByteArray) =
        File(root, "store/quarantine/${sha256Hex(session.toByteArray())}/${sha256Hex(bytes)}.record")

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
        const val MAX_VISITS = 16
        val JPEG_A = byteArrayOf(1, 2, 3)
        val JPEG_B = byteArrayOf(4, 5, 6, 7)
        val JPEG_C = byteArrayOf(8, 8, 8, 8, 8)
        val JPEG_KEPT = byteArrayOf(9, 9)
    }
}
