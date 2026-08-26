package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCaptureAdapterV2Test {
    @Test
    fun `admission is durable before one qualified exposure and complete JPEG commits`() {
        val store = FakeStore()
        val camera = FakeExposure()
        val adapter = NativeCaptureAdapterV2(store, camera)
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))

        adapter.admit(request)

        assertEquals(1, store.accepted.size)
        assertEquals(1, camera.requests.size)
        val qualifier = camera.requests.single().first
        camera.components(qualifier, request, listOf(CaptureComponentKind.JPEG))

        assertEquals(1L, adapter.snapshot().exposures)
        assertEquals(1L, adapter.snapshot().committed)
        assertEquals(CaptureTerminalKind.COMMITTED_PICTURE, store.terminals.single().terminal?.kind)
        assertFalse(adapter.snapshot().running == 1)
    }

    @Test
    fun `JPEG plus DNG accepts exact orders rejects every partial duplicate permutation and closes inputs`() {
        val permutations = listOf(
            listOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG) to true,
            listOf(CaptureComponentKind.DNG, CaptureComponentKind.JPEG) to true,
            listOf(CaptureComponentKind.JPEG) to false,
            listOf(CaptureComponentKind.DNG) to false,
            listOf(CaptureComponentKind.JPEG, CaptureComponentKind.JPEG) to false,
            listOf(CaptureComponentKind.DNG, CaptureComponentKind.DNG) to false,
            listOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG, CaptureComponentKind.JPEG) to false,
        )
        permutations.forEach { (order, commits) ->
            val store = FakeStore()
            val camera = FakeExposure()
            val adapter = NativeCaptureAdapterV2(store, camera)
            val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG))
            adapter.admit(request)
            val qualifier = camera.requests.single().first
            val transferred = camera.components(qualifier, request, order)
            assertTrue(transferred.all { it.closed })
            assertEquals(
                if (commits) CaptureTerminalKind.COMMITTED_PICTURE else CaptureTerminalKind.ABANDONED_ATTEMPT,
                store.terminals.single().terminal?.kind,
            )

            val late = CloseTrackingInputStream(byteArrayOf(1))
            camera.callback!!.onComponents(SharedCameraComponentSetV2(
                qualifier,
                listOf(CaptureComponentStreamV2(CaptureComponentKind.JPEG, late)),
            ))
            assertTrue(late.closed)
            assertEquals(1L, adapter.snapshot().lateCallbacks)
        }
    }

    @Test
    fun `one running one funded waiting gives manual ready priority without preempting exposure`() {
        val store = FakeStore()
        val camera = FakeExposure()
        val adapter = NativeCaptureAdapterV2(store, camera)
        val automatic = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG), ordinal = 2)
        val manual = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 1)
        adapter.admit(automatic)
        adapter.admit(manual)
        assertEquals(1, camera.requests.size)
        assertEquals(1, adapter.snapshot().running)
        assertEquals(1, adapter.snapshot().fundedWaiting)

        camera.components(camera.requests.single().first, automatic, listOf(CaptureComponentKind.JPEG))
        assertEquals(2, camera.requests.size)
        assertEquals(manual.accepted.identity, camera.requests.last().first.identity)
    }

    @Test
    fun `capacity is resolved before durable accept and protected manual evicts waiting automatic`() {
        val store = FakeStore()
        val camera = FakeExposure()
        val adapter = NativeCaptureAdapterV2(store, camera)
        val running = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG), ordinal = 1)
        val waitingAutomatic = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG), ordinal = 2)
        val manual = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 3)
        adapter.admit(running)
        adapter.admit(waitingAutomatic)
        adapter.admit(manual)

        assertEquals(listOf(running, waitingAutomatic, manual), store.accepted)
        assertEquals("manual-priority-evicted-automatic", store.terminals.single().terminal?.reason)
        assertEquals(manual.accepted.identity, adapter.waitingIdentityForTest())

        val rejected = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG), ordinal = 4)
        assertTrue(runCatching { adapter.admit(rejected) }.exceptionOrNull()?.message == "finalizer-capacity")
        assertFalse(store.accepted.contains(rejected))
    }

    @Test
    fun `active and durable terminal replay never schedule or re-expose`() {
        val store = FakeStore()
        val camera = FakeExposure()
        val adapter = NativeCaptureAdapterV2(store, camera)
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))
        val accepted = adapter.admit(request)
        assertEquals(accepted, adapter.admit(request))
        assertEquals(1, camera.requests.size)

        val changed = CaptureCommitRequest(
            request.accepted,
            request.components.map { component ->
                CaptureComponentDescriptor(component.kind, component.byteLength, component.sha256, "changed-${component.durableObjectId}")
            },
            request.exposureTimestampNanoseconds,
            request.poseRecordHash,
            request.cameraModelHash,
            request.validationRecordHash,
            request.ledgerRecordHash,
        )
        assertTrue(runCatching { adapter.admit(changed) }.exceptionOrNull() is DurableStoreConflictV2)
        camera.components(camera.requests.single().first, request, listOf(CaptureComponentKind.JPEG))
        val terminal = store.terminals.single()
        assertEquals(terminal, adapter.admit(request))
        assertEquals(1, camera.requests.size)
        assertTrue(runCatching { adapter.admit(changed) }.exceptionOrNull() is DurableStoreConflictV2)
        assertEquals(1, camera.requests.size)
    }

    @Test
    fun `real durable store fences exact and changed terminal replay before exposure`() {
        val root = File.createTempFile("native-capture-v2", "").also { it.delete(); assertTrue(it.mkdirs()) }
        val budget = StorageBudgetCoordinatorV2(
            File(root, "budget"), StorageBudgetPolicyV2(1024 * 1024, 0), JvmDescriptorFilesystemV2(),
        ) { 1024 * 1024 }
        val durable = DurableSessionStoreV2(File(root, "store"), budget, filesystemBackend = JvmDescriptorFilesystemV2())
        try {
            val firstCamera = FakeExposure()
            val first = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(durable), firstCamera)
            val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))
            first.admit(request)
            firstCamera.components(firstCamera.requests.single().first, request, listOf(CaptureComponentKind.JPEG))

            val replayCamera = FakeExposure()
            val replay = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(durable), replayCamera)
            assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, replay.admit(request).phase)
            assertTrue(replayCamera.requests.isEmpty())
            val changed = CaptureCommitRequest(
                request.accepted,
                request.components.map { CaptureComponentDescriptor(it.kind, it.byteLength, digest("changed"), it.durableObjectId) },
                request.exposureTimestampNanoseconds,
                request.poseRecordHash,
                request.cameraModelHash,
                request.validationRecordHash,
                request.ledgerRecordHash,
            )
            assertTrue(runCatching { replay.admit(changed) }.exceptionOrNull() is DurableStoreConflictV2)
            assertTrue(replayCamera.requests.isEmpty())

            val acceptedOnly = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 2)
            durable.acceptCaptureBeforeExposure(acceptedOnly)
            val restartCamera = FakeExposure()
            val restarted = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(durable), restartCamera)
            assertEquals(CaptureAttemptPhase.ABANDONED_ATTEMPT, restarted.admit(acceptedOnly).phase)
            assertTrue(restartCamera.requests.isEmpty())

            val later = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 3)
            val laterCamera = FakeExposure()
            val laterAdapter = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(durable), laterCamera)
            laterAdapter.admit(later)
            laterCamera.components(laterCamera.requests.single().first, later, listOf(CaptureComponentKind.JPEG))
            assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, durable.queryReceipt(later.accepted.identity)?.phase)
        } finally {
            durable.close()
            budget.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `real durable abandoned replay binds full request and recovered metadata abandonment rejects replay`() {
        val root = File.createTempFile("native-abandoned-v2", "").also { it.delete(); assertTrue(it.mkdirs()) }
        val budget = StorageBudgetCoordinatorV2(
            File(root, "budget"), StorageBudgetPolicyV2(1024 * 1024, 0), JvmDescriptorFilesystemV2(),
        ) { 1024 * 1024 }
        var durable = DurableSessionStoreV2(File(root, "store"), budget, filesystemBackend = JvmDescriptorFilesystemV2())
        try {
            val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 10)
            val firstCamera = FakeExposure()
            val first = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(durable), firstCamera)
            first.admit(request)
            first.onLifecycle(CaptureLifecycleEvent.BACKGROUNDED)
            val abandoned = checkNotNull(durable.queryReceipt(request.accepted.identity))
            durable.close()
            durable = DurableSessionStoreV2(File(root, "store"), budget, filesystemBackend = JvmDescriptorFilesystemV2())

            val replayCamera = FakeExposure()
            val replay = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(durable), replayCamera)
            assertEquals(abandoned.receiptHash, replay.admit(request).receiptHash)
            assertTrue(replayCamera.requests.isEmpty())
            assertEquals(0, replay.snapshot().running)
            assertEquals(0, replay.snapshot().fundedWaiting)

            val changedDescriptor = rebuildRequest(request, components = request.components.map {
                CaptureComponentDescriptor(it.kind, it.byteLength, digest("different-component"), it.durableObjectId)
            })
            val changedObjectId = rebuildRequest(request, components = request.components.map {
                CaptureComponentDescriptor(it.kind, it.byteLength, it.sha256, "different-object")
            })
            val changedTimestamp = rebuildRequest(request, timestamp = request.exposureTimestampNanoseconds + 1)
            val changedPose = rebuildRequest(request, poseHash = digest("different-pose"))
            val changedCamera = rebuildRequest(request, cameraHash = digest("different-camera"))
            val changedValidation = rebuildRequest(request, validationHash = digest("different-validation"))
            val changedLedger = rebuildRequest(request, ledgerHash = digest("different-ledger"))
            val changedAccepted = CaptureAcceptedAttempt(
                request.accepted.identity.copy(attemptOrdinal = request.accepted.identity.attemptOrdinal + 1),
                request.accepted.lane,
                request.accepted.profile,
                request.accepted.reservation.copy(physicalStoreBytes = request.accepted.reservation.physicalStoreBytes + 1),
                request.accepted.canonicalIntentHash,
                request.accepted.acceptedReceiptHash,
            )
            listOf(
                changedDescriptor, changedObjectId, changedTimestamp, changedPose, changedCamera,
                changedValidation, changedLedger, rebuildRequest(request, accepted = changedAccepted),
            ).forEach { changed ->
                assertTrue(runCatching { replay.admit(changed) }.exceptionOrNull() is DurableStoreConflictV2)
            }
            assertTrue(replayCamera.requests.isEmpty())
            assertEquals(0, replay.snapshot().running)
            assertEquals(0, replay.snapshot().fundedWaiting)

            val recoveredRequest = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 11)
            durable.acceptCaptureBeforeExposure(recoveredRequest)
            durable.recover()
            val recoveredCamera = FakeExposure()
            val recoveredAdapter = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(durable), recoveredCamera)
            assertTrue(runCatching { recoveredAdapter.admit(recoveredRequest) }.exceptionOrNull() is DurableStoreConflictV2)
            assertTrue(recoveredCamera.requests.isEmpty())
            assertEquals(0, recoveredAdapter.snapshot().running)
            assertEquals(0, recoveredAdapter.snapshot().fundedWaiting)

            val later = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 12)
            recoveredAdapter.admit(later)
            recoveredCamera.components(recoveredCamera.requests.single().first, later, listOf(CaptureComponentKind.JPEG))
            assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, durable.queryReceipt(later.accepted.identity)?.phase)
        } finally {
            durable.close()
            budget.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `ten second automatic deadline abandons and thirty second path queries before abandonment`() {
        var clock = 0L
        val store = FakeStore()
        val camera = FakeExposure()
        val adapter = NativeCaptureAdapterV2(store, camera, nowMs = { clock })
        val automatic = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG))
        adapter.admit(automatic)
        clock = 10_000L
        adapter.advanceDeadlines()
        assertEquals("automatic-stall-10s", store.terminals.single().terminal?.reason)

        val manual = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 2)
        adapter.admit(manual)
        clock += 30_000L
        adapter.advanceDeadlines()
        assertTrue(store.queries.contains(manual.accepted.identity))
        assertEquals("terminal-fence-30s", store.terminals.last().terminal?.reason)
        assertEquals(1L, adapter.snapshot().unknownQueries)
    }

    @Test
    fun `safety signal is fail closed and invalidated with the exact lifecycle cut`() {
        val signal = CaptureSafetySignalV2()
        assertFalse(signal.isCaptureSafe())
        val accepted = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG)).accepted
        val qualifier = CaptureAttemptQualifierV2(accepted.identity, accepted.identity.lifecycleCut, 1)
        signal.bind(qualifier, CaptureReceipt(accepted.identity, CaptureAttemptPhase.RESERVED_ACCEPTED, "r", "r", true))
        assertTrue(signal.isCaptureSafe())
        signal.invalidateLifecycle(accepted.identity.lifecycleCut)
        assertFalse(signal.isCaptureSafe())
    }

    @Test
    fun `synchronous component callback cannot resurrect capture safety`() {
        val store = FakeStore()
        val signal = CaptureSafetySignalV2()
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))
        val camera = object : SharedCameraExposurePortV2 {
            override fun requestExposure(
                qualifier: CaptureAttemptQualifierV2,
                required: Set<CaptureComponentKind>,
                callback: SharedCameraExposureCallbackV2,
            ): Boolean {
                callback.onComponents(SharedCameraComponentSetV2(qualifier, listOf(
                    CaptureComponentStreamV2(CaptureComponentKind.JPEG, CloseTrackingInputStream(bytes(CaptureComponentKind.JPEG))),
                )))
                return true
            }
            override fun cancelExposure(qualifier: CaptureAttemptQualifierV2) = Unit
        }
        val adapter = NativeCaptureAdapterV2(store, camera, signal)
        adapter.admit(request)
        assertFalse(signal.isCaptureSafe())
        assertEquals(1L, adapter.snapshot().committed)
    }

    @Test
    fun `store rejection before and during consumption closes every component exactly once`() {
        listOf(0, 1).forEach { readsBeforeThrow ->
            val store = FakeStore().apply { failCommitAfterReads = readsBeforeThrow }
            val camera = FakeExposure()
            val adapter = NativeCaptureAdapterV2(store, camera)
            val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG))
            adapter.admit(request)
            val inputs = camera.components(camera.requests.single().first, request, listOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG))
            assertTrue(inputs.all { it.closeCalls == 1 })
            assertEquals(2L, adapter.snapshot().closedComponents)
            assertEquals(CaptureTerminalKind.ABANDONED_ATTEMPT, store.terminals.single().terminal?.kind)
        }
    }

    @Test
    fun `every lifecycle event fences pre-output ownership and late components close`() {
        CaptureLifecycleEvent.entries.forEachIndexed { index, event ->
            val store = FakeStore()
            val camera = FakeExposure()
            val adapter = NativeCaptureAdapterV2(store, camera)
            val lane = if (event == CaptureLifecycleEvent.AUTOMATIC_DISABLED) CaptureLane.AUTOMATIC else CaptureLane.MANUAL
            val request = request(lane, setOf(CaptureComponentKind.JPEG), ordinal = (index + 1).toLong())
            adapter.admit(request)
            val qualifier = camera.requests.single().first
            adapter.onLifecycle(event, request.accepted.identity.lifecycleCut)
            if (event == CaptureLifecycleEvent.AUTOMATIC_DISABLED) {
                assertTrue(store.terminals.isEmpty())
                camera.components(qualifier, request, listOf(CaptureComponentKind.JPEG))
                assertEquals(CaptureTerminalKind.COMMITTED_PICTURE, store.terminals.single().terminal?.kind)
                return@forEachIndexed
            }
            assertTrue(store.terminals.isNotEmpty())
            val late = CloseTrackingInputStream(byteArrayOf(1))
            camera.callback!!.onComponents(SharedCameraComponentSetV2(qualifier, listOf(
                CaptureComponentStreamV2(CaptureComponentKind.JPEG, late),
            )))
            assertTrue(late.closed)
            assertEquals(1L, adapter.snapshot().lateCallbacks)
        }
    }

    @Test
    fun `pause drains running and waiting without promoting a second exposure`() {
        val store = FakeStore()
        val camera = FakeExposure()
        val adapter = NativeCaptureAdapterV2(store, camera)
        val running = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG), ordinal = 1)
        val waiting = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 2)
        adapter.admit(running)
        adapter.admit(waiting)
        adapter.onLifecycle(CaptureLifecycleEvent.BACKGROUNDED)
        assertEquals(1, camera.requests.size)
        assertEquals(2, store.terminals.count { it.terminal?.kind == CaptureTerminalKind.ABANDONED_ATTEMPT })
        assertEquals(0, adapter.snapshot().running)
        assertEquals(0, adapter.snapshot().fundedWaiting)
    }

    @Test
    fun `shutdown classifies running and funded waiting owners then a later capture succeeds`() {
        val store = FakeStore()
        val firstPort = FakeExposure()
        val adapter = NativeCaptureAdapterV2(store, firstPort)
        val automatic = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG), ordinal = 1)
        val manual = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 2)
        adapter.admit(automatic)
        adapter.admit(manual)
        adapter.close()
        assertEquals(2, store.terminals.count { it.terminal?.kind == CaptureTerminalKind.ABANDONED_ATTEMPT })
        assertEquals(0, adapter.snapshot().running)
        assertEquals(0, adapter.snapshot().fundedWaiting)

        val secondPort = FakeExposure()
        val later = NativeCaptureAdapterV2(store, secondPort)
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 3)
        later.admit(request)
        secondPort.components(secondPort.requests.single().first, request, listOf(CaptureComponentKind.JPEG))
        assertEquals(CaptureTerminalKind.COMMITTED_PICTURE, store.terminals.last().terminal?.kind)
        assertEquals(0, later.snapshot().running)
        assertEquals(0, later.snapshot().fundedWaiting)
    }

    private fun request(lane: CaptureLane, kinds: Set<CaptureComponentKind>, ordinal: Long = 1): CaptureCommitRequest {
        val cut = CaptureLifecycleCut("session", 1, "group", 1, "ar", "view", 1, "binding", 1, 1)
        val accepted = CaptureAcceptedAttempt(
            CaptureAttemptIdentity("attempt-$ordinal-${lane.name}", "commit-$ordinal-${lane.name}", ordinal, cut),
            lane,
            CaptureComponentProfile("profile-${kinds.joinToString()}", kinds, 1024, 1024),
            CaptureReservationLiability(0, 1024, kinds.size.toLong(), 1, 0, true),
            digest("intent-$ordinal"), digest("accepted-$ordinal"),
        )
        val components = kinds.sortedBy { it.ordinal }.map { kind ->
            val bytes = bytes(kind)
            CaptureComponentDescriptor(kind, bytes.size.toLong(), sha(bytes), "$kind-object")
        }
        return CaptureCommitRequest(accepted, components, 1, digest("pose"), digest("camera"), digest("validation"), digest("ledger"))
    }

    private class FakeExposure : SharedCameraExposurePortV2 {
        val requests = mutableListOf<Pair<CaptureAttemptQualifierV2, Set<CaptureComponentKind>>>()
        var callback: SharedCameraExposureCallbackV2? = null
        override fun requestExposure(qualifier: CaptureAttemptQualifierV2, required: Set<CaptureComponentKind>, callback: SharedCameraExposureCallbackV2): Boolean {
            requests += qualifier to required; this.callback = callback; return true
        }
        override fun cancelExposure(qualifier: CaptureAttemptQualifierV2) = Unit
        fun components(qualifier: CaptureAttemptQualifierV2, request: CaptureCommitRequest, order: List<CaptureComponentKind>): List<CloseTrackingInputStream> {
            val sources = order.map { CloseTrackingInputStream(bytes(it)) }
            callback!!.onComponents(SharedCameraComponentSetV2(qualifier, order.zip(sources).map { (kind, source) ->
                CaptureComponentStreamV2(kind, source)
            }))
            return sources
        }
    }

    private class FakeStore : NativeCaptureStorePortV2 {
        val accepted = mutableListOf<CaptureCommitRequest>()
        val terminals = mutableListOf<CaptureReceipt>()
        val queries = mutableListOf<CaptureAttemptIdentity>()
        var failCommitAfterReads: Int? = null
        override fun replayFenceBeforeExposure(request: CaptureCommitRequest): CaptureReceipt? {
            terminals.firstOrNull { it.identity == request.accepted.identity }?.let { prior ->
                if (accepted.first { it.accepted.identity == request.accepted.identity } == request) return prior
                throw DurableStoreConflictV2("changed terminal replay")
            }
            accepted.firstOrNull { it.accepted.identity == request.accepted.identity }?.let { prior ->
                if (prior == request) return CaptureReceipt(request.accepted.identity, CaptureAttemptPhase.RESERVED_ACCEPTED, "accepted", "accepted", true)
                throw DurableStoreConflictV2("changed durable accepted replay")
            }
            return null
        }
        override fun acceptBeforeExposure(request: CaptureCommitRequest): CaptureReceipt {
            terminals.firstOrNull { it.identity == request.accepted.identity }?.let { prior ->
                if (accepted.first { it.accepted.identity == request.accepted.identity } == request) return prior
                throw DurableStoreConflictV2("changed terminal replay")
            }
            accepted.firstOrNull { it.accepted.identity == request.accepted.identity }?.let { prior ->
                if (prior == request) return CaptureReceipt(request.accepted.identity, CaptureAttemptPhase.RESERVED_ACCEPTED, "accepted", "accepted", true)
                throw DurableStoreConflictV2("changed active replay")
            }
            accepted += request
            return CaptureReceipt(request.accepted.identity, CaptureAttemptPhase.RESERVED_ACCEPTED, "accepted", "accepted", true)
        }
        override fun commitStreamed(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>): CaptureReceipt {
            failCommitAfterReads?.let { reads ->
                repeat(reads) { streams.first().input.read() }
                throw DurableStoreConflictV2("injected store rejection")
            }
            streams.forEach { it.input.use { source -> while (source.read() >= 0) {} } }
            val terminal = CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, request.accepted.identity, "commit", "committed", "capture", 1, "root")
            return CaptureReceipt(request.accepted.identity, CaptureAttemptPhase.COMMITTED_PICTURE, "commit", "receipt", true, terminal).also(terminals::add)
        }
        override fun queryReceipt(identity: CaptureAttemptIdentity): CaptureReceipt? { queries += identity; return terminals.firstOrNull { it.identity == identity } }
        override fun rebaseSameAttempt(request: CaptureCommitRequest): CaptureReceipt = throw IllegalStateException("not prepared")
        override fun abandon(request: CaptureCommitRequest, terminal: CaptureTerminal): CaptureReceipt =
            CaptureReceipt(terminal.identity, CaptureAttemptPhase.ABANDONED_ATTEMPT, terminal.canonicalTerminalHash, "abandoned", true, terminal).also(terminals::add)
    }

    private class CloseTrackingInputStream(private val values: ByteArray) : InputStream() {
        private var index = 0
        var closed = false
            private set
        var closeCalls = 0
            private set
        override fun read(): Int = if (index == values.size) -1 else values[index++].toInt()
        override fun close() { closeCalls++; closed = true }
    }

    private companion object {
        fun bytes(kind: CaptureComponentKind) = "component-$kind".toByteArray()
        fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map { it.toInt() and 0xff }
        fun digest(value: String) = sha(value.toByteArray())

        fun rebuildRequest(
            request: CaptureCommitRequest,
            accepted: CaptureAcceptedAttempt = request.accepted,
            components: List<CaptureComponentDescriptor> = request.components,
            timestamp: Long = request.exposureTimestampNanoseconds,
            poseHash: List<Int> = request.poseRecordHash,
            cameraHash: List<Int> = request.cameraModelHash,
            validationHash: List<Int> = request.validationRecordHash,
            ledgerHash: List<Int> = request.ledgerRecordHash,
        ) = CaptureCommitRequest(
            accepted,
            components,
            timestamp,
            poseHash,
            cameraHash,
            validationHash,
            ledgerHash,
        )
    }
}
