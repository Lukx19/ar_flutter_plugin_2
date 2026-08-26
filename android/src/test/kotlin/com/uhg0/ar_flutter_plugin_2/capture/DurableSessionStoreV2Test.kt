package com.uhg0.ar_flutter_plugin_2.capture

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
        assertThrows(IllegalStateException::class.java) { store.commitStreamed(first, streams("changed".toByteArray())) }
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
        assertThrows(DurableStoreConflictV2::class.java) { store.commitStreamed(request, streams("jpeg".toByteArray())) }
        assertNull(store.queryReceipt(request.accepted.identity))
    }

    @Test fun `generated durable fault matrix asserts exact classification cleanup release and later success at every cut`() {
        assertEquals(21, DurableStoreFaultPointV2.entries.size)
        DurableStoreFaultPointV2.entries.forEach { cut ->
            val root = directory()
            var injected = 0
            val poisoned = DurableSessionStoreV2(File(root, "store"), budget(root), DurableStoreFaultInjectorV2 {
                if (it == cut) { injected++; throw IllegalStateException("injected-$cut") }
            })
            val request = request("fault-$cut", "attempt-$cut", "jpeg".toByteArray())
            val clean = { DurableSessionStoreV2(File(root, "store"), budget(root)) }

            when (cut) {
                DurableStoreFaultPointV2.ACCEPTED_RECORD -> {
                    assertThrows(IllegalStateException::class.java) { poisoned.acceptBeforeExposure(request.accepted) }
                    assertNull(clean().queryReceipt(request.accepted.identity))
                    assertEquals(0L, budget(root).reservedBytes())
                }
                DurableStoreFaultPointV2.ABANDONMENT_RECORD,
                DurableStoreFaultPointV2.ABANDONMENT_CLEANUP -> {
                    poisoned.acceptBeforeExposure(request.accepted)
                    assertThrows(IllegalStateException::class.java) {
                        poisoned.abandon(abandonment(request, "injected"))
                    }
                    assertNull(clean().queryReceipt(request.accepted.identity))
                    assertTrue(budget(root).reservedBytes() > 0)
                    clean().recover()
                    assertAbandonedAndReleased(root, request)
                }
                DurableStoreFaultPointV2.TOMBSTONE_RECORD,
                DurableStoreFaultPointV2.TOMBSTONE_DIRECTORY_SYNC -> {
                    poisoned.acceptBeforeExposure(request.accepted)
                    assertThrows(IllegalStateException::class.java) {
                        poisoned.tombstoneSession(request.accepted.identity.lifecycleCut.sessionId)
                    }
                    clean().recover()
                    if (cut == DurableStoreFaultPointV2.TOMBSTONE_RECORD) {
                        assertEquals(CaptureAttemptPhase.ABANDONED_ATTEMPT, clean().queryReceipt(request.accepted.identity)?.phase)
                    } else {
                        assertNull(clean().queryReceipt(request.accepted.identity))
                    }
                    assertEquals(0L, budget(root).reservedBytes())
                    assertFalse(hasStagingOrAsset(root, request))
                }
                DurableStoreFaultPointV2.RECOVERY -> {
                    poisoned.acceptBeforeExposure(request.accepted)
                    assertThrows(IllegalStateException::class.java) {
                        poisoned.commitStreamed(request, streams("wrong".toByteArray()))
                    }
                    assertThrows(IllegalStateException::class.java) { poisoned.recover() }
                    assertNull(clean().queryReceipt(request.accepted.identity))
                    assertTrue(budget(root).reservedBytes() > 0)
                    clean().recover()
                    assertAbandonedAndReleased(root, request)
                }
                else -> {
                    poisoned.acceptBeforeExposure(request.accepted)
                    assertThrows(IllegalStateException::class.java) {
                        poisoned.commitStreamed(request, streams("jpeg".toByteArray()))
                    }
                    when (cut) {
                        DurableStoreFaultPointV2.RECEIPT_FILE_SYNC,
                        DurableStoreFaultPointV2.POINTER_SLOT_REPLACE -> {
                            assertNull(clean().queryReceipt(request.accepted.identity))
                            assertTrue(budget(root).reservedBytes() > 0)
                            assertTrue(hasStagingOrAsset(root, request))
                            assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, clean().rebaseSameAttempt(request).phase)
                        }
                        DurableStoreFaultPointV2.POINTER_DIRECTORY_SYNC,
                        DurableStoreFaultPointV2.DELETE_RECLAIM -> {
                            assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, clean().queryReceipt(request.accepted.identity)?.phase)
                            clean().recover()
                        }
                        else -> {
                            assertNull(clean().queryReceipt(request.accepted.identity))
                            assertTrue(budget(root).reservedBytes() > 0)
                            clean().recover()
                            assertEquals(CaptureAttemptPhase.ABANDONED_ATTEMPT, clean().queryReceipt(request.accepted.identity)?.phase)
                        }
                    }
                    assertEquals(0L, budget(root).reservedBytes())
                    assertFalse(hasStaging(root, request))
                    val committedCut = cut in setOf(
                        DurableStoreFaultPointV2.RECEIPT_FILE_SYNC,
                        DurableStoreFaultPointV2.POINTER_SLOT_REPLACE,
                        DurableStoreFaultPointV2.POINTER_DIRECTORY_SYNC,
                        DurableStoreFaultPointV2.DELETE_RECLAIM,
                    )
                    assertEquals(committedCut, hasAsset(root, request))
                }
            }
            assertTrue("fault cut was not exercised: $cut", injected > 0)

            val recovered = clean()
            val laterSession = if (cut == DurableStoreFaultPointV2.TOMBSTONE_DIRECTORY_SYNC) "session-after-tombstone" else "session-1"
            val later = request("later-$cut", "later-attempt-$cut", "later".toByteArray(), laterSession)
            assertEquals(CaptureAttemptPhase.RESERVED_ACCEPTED, recovered.acceptBeforeExposure(later.accepted).phase)
            assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, recovered.commitStreamed(later, streams("later".toByteArray())).phase)
            assertEquals(0L, budget(root).reservedBytes())
        }
    }

    @Test fun `historic receipt replay remains exact through current and two retained predecessors`() {
        val root = directory(); val store = DurableSessionStoreV2(File(root, "store"), budget(root))
        val requests = (1..4).map { number -> request("historic-$number", "historic-attempt-$number", "jpeg-$number".toByteArray()) }
        val receipts = requests.mapIndexed { index, value ->
            store.acceptBeforeExposure(value.accepted)
            store.commitStreamed(value, streams("jpeg-${index + 1}".toByteArray()))
        }
        val restarted = DurableSessionStoreV2(File(root, "store"), budget(root))
        assertNull(restarted.queryReceipt(requests.first().accepted.identity))
        requests.drop(1).zip(receipts.drop(1)).forEach { (request, receipt) ->
            assertEquals(receipt.receiptHash, restarted.queryReceipt(request.accepted.identity)?.receiptHash)
            assertEquals(receipt.receiptHash, restarted.commitStreamed(request, streams("jpeg-${receipt.terminal!!.captureRevision}".toByteArray())).receiptHash)
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
        assertThrows(IllegalStateException::class.java) { failing.commitStreamed(request, streams("jpeg".toByteArray())) }
        assertNull(failing.queryReceipt(request.accepted.identity))
        assertTrue(quota.reservedBytes() > 0)
        val restarted = DurableSessionStoreV2(File(root, "store"), quota)
        assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, restarted.rebaseSameAttempt(request).phase)
        assertEquals(0, quota.reservedBytes())
    }

    @Test fun `slot fork broken predecessor and high filename are rejected from authority selection`() {
        val root = directory(); val store = DurableSessionStoreV2(File(root, "store"), budget(root))
        (1..3).forEach { number ->
            val request = request("branch-$number", "branch-attempt-$number", "jpeg-$number".toByteArray())
            store.acceptBeforeExposure(request.accepted); store.commitStreamed(request, streams("jpeg-$number".toByteArray()))
        }
        val session = File(root, "store/sessions").walkTopDown().first { it.name == "objects" }.parentFile
        val pointers = listOf(File(session, "root-A.ptr"), File(session, "root-B.ptr"))
        val current = pointers.maxBy { it.readLines().first().toLong() }
        val prior = pointers.minBy { it.readLines().first().toLong() }
        val root2 = prior.readLines()[1]
        val root1 = File(session, "objects/$root2.root").readLines().first { it.startsWith("previous=") }.removePrefix("previous=")
        val divergent = "schema=5\nrevision=3\nrequest=${"a".repeat(64)}\nprevious=$root2\nsecondPrevious=$root1\n".toByteArray()
        val divergentHash = MessageDigest.getInstance("SHA-256").digest(divergent).joinToString("") { "%02x".format(it) }
        File(session, "objects/$divergentHash.root").writeBytes(divergent)
        prior.writeText("3\n$divergentHash\nbranch\n")
        val fork = request("fork", "fork-attempt", "jpeg".toByteArray())
        fork.accepted // no exposure occurs before store acceptance
        assertThrows(DurableStoreConflictV2::class.java) {
            store.acceptBeforeExposure(fork.accepted)
            store.commitStreamed(fork, streams("jpeg".toByteArray()))
        }

        // An orphan with a numerically enormous filename is never scanned.
        File(session, "objects/${"f".repeat(64)}.root").writeText("schema=5\nrevision=999\nrequest=${"b".repeat(64)}\nprevious=-\nsecondPrevious=-\n")
        prior.writeText("bad\n")
        val recovered = DurableSessionStoreV2(File(root, "store"), budget(root))
        val after = request("after", "after-attempt", "jpeg".toByteArray())
        recovered.acceptBeforeExposure(after.accepted)
        assertEquals(4L, recovered.commitStreamed(after, streams("jpeg".toByteArray())).terminal!!.captureRevision)
    }

    private fun request(commit: String, attempt: String, jpeg: ByteArray, session: String = "session-1"): CaptureCommitRequest {
        val identity = CaptureAttemptIdentity(attempt, commit, 1, CaptureLifecycleCut(session, 1, "group-1", 1, "ar-1", "view-1", 1, "binding-1", 1, 1))
        val profile = CaptureComponentProfile("jpeg", setOf(CaptureComponentKind.JPEG), 64, 64)
        val accepted = CaptureAcceptedAttempt(identity, CaptureLane.MANUAL, profile, CaptureReservationLiability(0, 64, 1, 1, 0, true), digest("intent"), digest("accepted"))
        return CaptureCommitRequest(accepted, listOf(CaptureComponentDescriptor(CaptureComponentKind.JPEG, jpeg.size.toLong(), sha(jpeg), "object")), 1, digest("pose"), digest("camera"), digest("validation"), digest("ledger"))
    }
    private fun streams(bytes: ByteArray) = listOf(CaptureComponentStreamV2(CaptureComponentKind.JPEG, ByteArrayInputStream(bytes)))
    private fun abandonment(request: CaptureCommitRequest, reason: String) = CaptureTerminal(
        CaptureTerminalKind.ABANDONED_ATTEMPT, request.accepted.identity, "abandoned-${request.accepted.identity.commitId}", reason,
    )
    private fun assertAbandonedAndReleased(root: File, request: CaptureCommitRequest) {
        val recovered = DurableSessionStoreV2(File(root, "store"), budget(root))
        assertEquals(CaptureAttemptPhase.ABANDONED_ATTEMPT, recovered.queryReceipt(request.accepted.identity)?.phase)
        assertEquals(0L, budget(root).reservedBytes())
        assertFalse(hasStagingOrAsset(root, request))
    }
    private fun hasStagingOrAsset(root: File, request: CaptureCommitRequest): Boolean {
        return hasStaging(root, request) || hasAsset(root, request)
    }
    private fun hasStaging(root: File, request: CaptureCommitRequest): Boolean {
        val store = File(root, "store")
        val attemptHash = sha256Hex(request.accepted.identity.commitId)
        return store.walkTopDown().any { it.name == "staging" && it.parentFile?.name == attemptHash }
    }
    private fun hasAsset(root: File, request: CaptureCommitRequest): Boolean = File(root, "store").walkTopDown()
        .any { it.name == sha256Hex(request.accepted.identity.attemptId) && it.parentFile?.name == "assets" }
    private fun budget(root: File) = StorageBudgetCoordinatorV2(File(root, "budget"), StorageBudgetPolicyV2(1024 * 1024, 0)) { 1024 * 1024 }
    private fun directory(): File = File.createTempFile("durable-store-v2", "").also { it.delete(); assertTrue(it.mkdirs()); directories += it }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).map { it.toInt() and 0xff }
    private fun digest(value: String) = sha(value.toByteArray())
    private fun sha256Hex(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
