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
        val store = store(root, budget)
        val request = request("commit-1", "attempt-1", jpeg = "jpeg".toByteArray())

        val accepted = store.acceptBeforeExposure(request.accepted)
        assertEquals(CaptureAttemptPhase.RESERVED_ACCEPTED, accepted.phase)
        assertEquals(request.accepted.reservation.totalStoreLiability, budget.reservedBytes())

        val committed = store.commitStreamed(request, streams("jpeg".toByteArray()))
        assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, committed.phase)
        assertEquals(0, budget.reservedBytes())
        assertEquals(committed.requestHash, store.commitStreamed(request, streams("jpeg".toByteArray())).requestHash)

        val recovered = store(root, budget(root))
        assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, recovered.queryReceipt(request.accepted.identity)?.phase)
    }

    @Test fun `changed bytes conflict without moving selected root and abandonment releases once for later success`() {
        val root = directory(); val store = store(root, budget(root))
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
        val root = directory(); val store = store(root, budget(root))
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
            val poisoned = store(root, budget(root), DurableStoreFaultInjectorV2 {
                if (it == cut && injected++ == 0) throw IllegalStateException("injected-$cut")
            })
            val request = request("fault-$cut", "attempt-$cut", "jpeg".toByteArray())
            val clean = { store(root, budget(root)) }

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
                        DurableStoreFaultPointV2.POINTER_DIRECTORY_SYNC -> {
                            assertNull(poisoned.queryReceipt(request.accepted.identity))
                            assertTrue(budget(root).reservedBytes() > 0)
                            assertTrue(hasStagingOrAsset(root, request))
                            assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, poisoned.rebaseSameAttempt(request).phase)
                        }
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
        val root = directory(); val store = store(root, budget(root))
        val requests = (1..4).map { number -> request("historic-$number", "historic-attempt-$number", "jpeg-$number".toByteArray()) }
        val receipts = requests.mapIndexed { index, value ->
            store.acceptBeforeExposure(value.accepted)
            store.commitStreamed(value, streams("jpeg-${index + 1}".toByteArray()))
        }
        val restarted = store(root, budget(root))
        assertNull(restarted.queryReceipt(requests.first().accepted.identity))
        requests.drop(1).zip(receipts.drop(1)).forEach { (request, receipt) ->
            assertEquals(receipt.receiptHash, restarted.queryReceipt(request.accepted.identity)?.receiptHash)
            assertEquals(receipt.receiptHash, restarted.commitStreamed(request, streams("jpeg-${receipt.terminal!!.captureRevision}".toByteArray())).receiptHash)
        }
    }

    @Test fun `session root retains two predecessors and corrupt current slot falls back without filename selection`() {
        val root = directory(); val store = store(root, budget(root))
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
        val failing = store(root, quota, DurableStoreFaultInjectorV2 {
            if (it == DurableStoreFaultPointV2.POINTER_SLOT_REPLACE) throw IllegalStateException("pointer cut")
        })
        val request = request("unknown", "unknown-attempt", "jpeg".toByteArray())
        failing.acceptBeforeExposure(request.accepted)
        assertThrows(IllegalStateException::class.java) { failing.commitStreamed(request, streams("jpeg".toByteArray())) }
        assertNull(failing.queryReceipt(request.accepted.identity))
        assertTrue(quota.reservedBytes() > 0)
        val restarted = store(root, quota)
        assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, restarted.rebaseSameAttempt(request).phase)
        assertEquals(0, quota.reservedBytes())
    }

    @Test fun `pointer directory cut remains unknown until actual parent sync succeeds`() {
        val root = directory(); val quota = budget(root); val synced = mutableListOf<File>(); var injected = false
        val backend = JvmDescriptorFilesystemV2(onDirectorySync = { synced += it.canonicalFile })
        val store = DurableSessionStoreV2(
            File(root, "store"), quota,
            DurableStoreFaultInjectorV2 {
                if (it == DurableStoreFaultPointV2.POINTER_DIRECTORY_SYNC && !injected) {
                    injected = true
                    throw IllegalStateException("before-directory-fsync")
                }
            },
            backend,
        )
        val request = request("directory-unknown", "directory-unknown-attempt", "jpeg".toByteArray())
        store.acceptBeforeExposure(request.accepted)
        val session = File(root, "store/sessions").walkTopDown().first { it.name.matches(Regex("[0-9a-f]{64}")) }
        val before = synced.count { it == session.canonicalFile }
        assertThrows(PointerDirectorySyncUnknownV2::class.java) {
            store.commitStreamed(request, streams("jpeg".toByteArray()))
        }
        assertEquals(before, synced.count { it == session.canonicalFile })
        assertNull(store.queryReceipt(request.accepted.identity))
        assertTrue(quota.reservedBytes() > 0)
        assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, store.rebaseSameAttempt(request).phase)
        assertEquals(before + 1, synced.count { it == session.canonicalFile })
        assertEquals(0L, quota.reservedBytes())
    }

    @Test fun `complete valid same revision branches are rejected as a fork`() {
        val root = directory(); val store = store(root, budget(root))
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
        val recovered = store(root, budget(root))
        val after = request("after", "after-attempt", "jpeg".toByteArray())
        recovered.acceptBeforeExposure(after.accepted)
        assertEquals(4L, recovered.commitStreamed(after, streams("jpeg".toByteArray())).terminal!!.captureRevision)
    }

    @Test fun `parseable pointer to corrupt root is ignored beside complete valid slot`() {
        val root = directory(); val store = store(root, budget(root))
        (1..3).forEach { number ->
            val request = request("valid-$number", "valid-attempt-$number", "jpeg-$number".toByteArray())
            store.acceptBeforeExposure(request.accepted)
            store.commitStreamed(request, streams("jpeg-$number".toByteArray()))
        }
        val session = File(root, "store/sessions").walkTopDown().first { it.name == "objects" }.parentFile
        val pointers = listOf(File(session, "root-A.ptr"), File(session, "root-B.ptr"))
        val valid = pointers.maxBy { it.readLines().first().toLong() }
        val invalid = pointers.first { it != valid }
        val corruptBytes = "schema=5\nrevision=3\nrequest=${"c".repeat(64)}\nprevious=${"d".repeat(64)}\nsecondPrevious=${"e".repeat(64)}\n".toByteArray()
        val corruptHash = MessageDigest.getInstance("SHA-256").digest(corruptBytes).joinToString("") { "%02x".format(it) }
        File(session, "objects/$corruptHash.root").writeBytes(corruptBytes)
        invalid.writeText("3\n$corruptHash\nparseable-corrupt\n")

        val after = request("valid-after-corrupt", "valid-after-corrupt-attempt", "jpeg-4".toByteArray())
        val recovered = store(root, budget(root))
        recovered.acceptBeforeExposure(after.accepted)
        assertEquals(4L, recovered.commitStreamed(after, streams("jpeg-4".toByteArray())).terminal!!.captureRevision)
    }

    @Test fun `store and budget close owned roots once without closing borrowed collaborators`() {
        val root = directory(); val closedRoots = mutableListOf<File>()
        val borrowedFactory = JvmDescriptorFilesystemV2(onRootClose = { closedRoots += it })
        val budget = StorageBudgetCoordinatorV2(
            File(root, "budget"), StorageBudgetPolicyV2(1024, 0), borrowedFactory,
        ) { 1024 }
        val store = DurableSessionStoreV2(File(root, "store"), budget, filesystemBackend = borrowedFactory)

        store.close()
        store.close()
        assertEquals(listOf(File(root, "store").canonicalFile), closedRoots)
        assertThrows(IllegalStateException::class.java) { store.recover() }
        assertNotNull(budget.reserve("capture:after-store-close", 1))

        var failedConstructorCloses = 0
        val failingFactory = JvmDescriptorFilesystemV2(
            beforeComponentOpen = { throw IllegalStateException("injected initialization failure") },
            onRootClose = { failedConstructorCloses += 1 },
        )
        assertThrows(IllegalStateException::class.java) {
            DurableSessionStoreV2(File(root, "failing-store"), budget, filesystemBackend = failingFactory)
        }
        assertEquals(1, failedConstructorCloses)

        budget.close()
        budget.close()
        assertEquals(2, closedRoots.size)
        assertThrows(IllegalStateException::class.java) { budget.reservedBytes() }
    }

    @Test fun `startup recovery projects only the two newest exact durable outcomes`() {
        val root = directory(); val budget = budget(root); val store = store(root, budget)
        val oldest = request("projection-old", "projection-old-attempt", "old".toByteArray())
        store.acceptBeforeExposure(oldest.accepted)
        store.commitStreamed(oldest, streams("old".toByteArray()))
        Thread.sleep(2)
        val committed = request("projection-commit", "projection-commit-attempt", "new".toByteArray())
        store.acceptBeforeExposure(committed.accepted)
        val committedReceipt = store.commitStreamed(committed, streams("new".toByteArray()))
        Thread.sleep(2)
        val acceptedOnly = request("projection-accepted", "projection-accepted-attempt", "lost".toByteArray())
        store.acceptBeforeExposure(acceptedOnly.accepted)

        val projections = store.recoverAndProject(limit = 2)

        assertEquals(2, projections.size)
        assertEquals(setOf(committed.accepted.identity.attemptId, acceptedOnly.accepted.identity.attemptId), projections.map { it.attemptId }.toSet())
        val projectedCommit = projections.single { it.attemptId == committed.accepted.identity.attemptId }
        assertEquals(CaptureTerminalKind.COMMITTED_PICTURE, projectedCommit.kind)
        assertEquals(committedReceipt.terminal!!.captureId, projectedCommit.captureId)
        assertEquals(committedReceipt.terminal!!.manifestId, projectedCommit.manifestId)
        val projectedAbsent = projections.single { it.attemptId == acceptedOnly.accepted.identity.attemptId }
        assertEquals(CaptureTerminalKind.ABANDONED_ATTEMPT, projectedAbsent.kind)
        assertEquals("recovered-proven-absent", projectedAbsent.reason)
        store.close(); budget.close()
    }

    @Test fun `startup recovery scan work is indexed bounded and cancellable`() {
        val root = directory()
        val firstBudget = budget(root)
        val firstStore = store(root, firstBudget)
        repeat(12) { ordinal ->
            firstStore.acceptBeforeExposure(
                request("bounded-$ordinal", "bounded-attempt-$ordinal", byteArrayOf(ordinal.toByte()), "session-$ordinal").accepted,
            )
        }
        firstStore.close()
        firstBudget.close()
        val index = java.util.Properties().apply {
            File(root, "store/recovery-index.properties").inputStream().use(::load)
        }
        assertEquals("3", index.getProperty("count"))
        repeat(100) { File(root, "store/sessions/junk-$it").mkdirs() }

        var directoryLists = 0
        val secondBudget = budget(root)
        val secondStore = DurableSessionStoreV2(
            File(root, "store"),
            secondBudget,
            filesystemBackend = JvmDescriptorFilesystemV2(onList = { directoryLists++ }),
        )
        var candidates = 0
        val projected = secondStore.recoverAndProject(
            limit = 2,
            onCandidateExamined = { candidates++ },
        )
        assertEquals(2, projected.size)
        assertEquals(setOf("bounded-attempt-11", "bounded-attempt-10"), projected.map { it.attemptId }.toSet())
        assertEquals(2, candidates)
        assertEquals(0, directoryLists)

        var cancelledCandidates = 0
        assertTrue(
            secondStore.recoverAndProject(
                shouldContinue = { false },
                onCandidateExamined = { cancelledCandidates++ },
            ).isEmpty(),
        )
        assertEquals(0, cancelledCandidates)
        assertEquals(0, directoryLists)
        secondStore.close()
        secondBudget.close()
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
        val recovered = store(root, budget(root))
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
    private fun store(root: File, budget: StorageBudgetCoordinatorV2, faults: DurableStoreFaultInjectorV2 = DurableStoreFaultInjectorV2 { }) =
        DurableSessionStoreV2(File(root, "store"), budget, faults, JvmDescriptorFilesystemV2())
    private fun budget(root: File) = StorageBudgetCoordinatorV2(
        File(root, "budget"), StorageBudgetPolicyV2(1024 * 1024, 0), JvmDescriptorFilesystemV2(),
    ) { 1024 * 1024 }
    private fun directory(): File = File.createTempFile("durable-store-v2", "").also { it.delete(); assertTrue(it.mkdirs()); directories += it }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).map { it.toInt() and 0xff }
    private fun digest(value: String) = sha(value.toByteArray())
    private fun sha256Hex(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
