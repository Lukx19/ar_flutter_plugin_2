package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeCaptureAdapterV2Test {
    @Test
    fun `startup recovery runs off caller and fences callbacks after close`() {
        val caller = Thread.currentThread()
        val recoveryThread = AtomicReference<Thread>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = mutableListOf<NativeCaptureEventV2>()
        val dispatcher = NativeCaptureRecoveryDispatcherV2(
            recover = { active ->
                recoveryThread.set(Thread.currentThread())
                entered.countDown()
                while (active() && !release.await(10, TimeUnit.MILLISECONDS)) {
                    // Polling the cancellation predicate is the bounded recovery contract.
                }
                listOf(
                    DurableSessionStoreV2.RecoveryProjectionV2(
                        "recovered-attempt",
                        CaptureTerminalKind.ABANDONED_ATTEMPT,
                        reason = "recovered-proven-absent",
                    ),
                )
            },
            events = events::add,
        )
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertFalse(recoveryThread.get() === caller)

        dispatcher.close()
        release.countDown()
        assertTrue(dispatcher.awaitTerminationForTest(5, TimeUnit.SECONDS))
        dispatcher.emitLive(NativeCaptureEventV2(NativeCaptureEventKindV2.ACCEPTED, "late"))

        assertFalse(dispatcher.isReady())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `recovered terminals precede live events`() {
        val recovered = CountDownLatch(1)
        val events = mutableListOf<NativeCaptureEventV2>()
        val dispatcher = NativeCaptureRecoveryDispatcherV2(
            recover = {
                listOf(
                    DurableSessionStoreV2.RecoveryProjectionV2(
                        "recovered",
                        CaptureTerminalKind.COMMITTED_PICTURE,
                        captureId = "capture",
                        captureRevision = 1,
                        manifestId = "manifest",
                        reason = "recovered-committed",
                    ),
                )
            },
            events = {
                events += it
                recovered.countDown()
            },
        )
        assertTrue(recovered.await(5, TimeUnit.SECONDS))
        assertTrue(dispatcher.isReady())
        assertEquals(listOf("recovered"), dispatcher.replaySnapshot().map { it.attemptId })
        dispatcher.emitLive(NativeCaptureEventV2(NativeCaptureEventKindV2.ACCEPTED, "live"))
        assertEquals(
            listOf(NativeCaptureEventKindV2.COMMITTED, NativeCaptureEventKindV2.ACCEPTED),
            events.map { it.kind },
        )
        dispatcher.acknowledgeTerminal("recovered")
        assertEquals(NativeCaptureEventKindV2.READY, events.last().kind)
        assertEquals(NativeCaptureEventKindV2.READY, dispatcher.replaySnapshot().single().kind)
        dispatcher.close()
        assertTrue(dispatcher.awaitTerminationForTest(5, TimeUnit.SECONDS))
        assertTrue(dispatcher.replaySnapshot().isEmpty())
    }

    @Test
    fun `recovery failure emits bounded diagnostic blocks exposure and remains fenced after close`() {
        val diagnosed = CountDownLatch(1)
        val events = mutableListOf<NativeCaptureEventV2>()
        val dispatcher = NativeCaptureRecoveryDispatcherV2(
            recover = { throw DurableStoreConflictV2("corrupt /private/path must not escape") },
            events = {
                events += it
                diagnosed.countDown()
            },
        )
        assertTrue(diagnosed.await(5, TimeUnit.SECONDS))
        assertFalse(dispatcher.isReady())

        var exposures = 0
        val failure = assertThrows(NativeCaptureRecoveryAdmissionExceptionV2::class.java) {
            dispatcher.requireAdmissionReady()
            exposures++
        }
        assertEquals("NATIVE_CAPTURE_V2_RECOVERY_FAILED", failure.code)
        assertEquals(0, exposures)
        assertEquals(1, events.size)
        assertEquals(NativeCaptureEventKindV2.RECOVERY_FAILED, events.single().kind)
        assertEquals("durable-startup-recovery-failed", events.single().reason)
        assertFalse(events.single().reason!!.contains("private"))

        dispatcher.close()
        assertTrue(dispatcher.awaitTerminationForTest(5, TimeUnit.SECONDS))
        dispatcher.emitLive(NativeCaptureEventV2(NativeCaptureEventKindV2.ACCEPTED, "late"))
        assertEquals(1, events.size)
    }

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
    fun `one manual and one automatic lane are the complete funded capacity`() {
        val store = FakeStore()
        val camera = FakeExposure()
        val adapter = NativeCaptureAdapterV2(store, camera)
        val running = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG), ordinal = 1)
        val duplicateAutomaticLane = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG), ordinal = 2)
        val manual = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 3)
        adapter.admit(running)
        assertEquals(
            "lane-capacity-automatic",
            runCatching { adapter.admit(duplicateAutomaticLane) }.exceptionOrNull()?.message,
        )
        adapter.admit(manual)

        assertEquals(listOf(running, manual), store.accepted)
        assertTrue(store.terminals.isEmpty())
        assertEquals(manual.accepted.identity, adapter.waitingIdentityForTest())

        val duplicateManualLane = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 4)
        assertEquals(
            "lane-capacity-manual",
            runCatching { adapter.admit(duplicateManualLane) }.exceptionOrNull()?.message,
        )
        assertFalse(store.accepted.contains(duplicateManualLane))
    }

    @Test
    fun `all reservation liabilities are validated before durable acceptance`() {
        val baseline = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG))
        val invalidReservations = listOf(
            baseline.accepted.reservation.copy(memoryBytes = baseline.accepted.profile.maximumWorkingBytes - 1),
            baseline.accepted.reservation.copy(physicalStoreBytes = baseline.accepted.profile.maximumComponentBytes - 1),
            baseline.accepted.reservation.copy(componentEntries = 1),
            baseline.accepted.reservation.copy(terminalEntries = 0),
            baseline.accepted.reservation.copy(physicallyBacked = false),
        )
        invalidReservations.forEach { reservation ->
            val store = FakeStore()
            val camera = FakeExposure()
            val adapter = NativeCaptureAdapterV2(store, camera)
            val accepted = CaptureAcceptedAttempt(
                baseline.accepted.identity,
                baseline.accepted.lane,
                baseline.accepted.profile,
                reservation,
                baseline.accepted.canonicalIntentHash,
                baseline.accepted.acceptedReceiptHash,
            )
            val request = rebuildRequest(baseline, accepted = accepted)
            assertTrue(runCatching { adapter.admit(request) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(store.accepted.isEmpty())
            assertTrue(camera.requests.isEmpty())
        }
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
            File(root, "budget"), StorageBudgetPolicyV2(16L * 1024 * 1024, 0), JvmDescriptorFilesystemV2(),
        ) { 16L * 1024 * 1024 }
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
    fun `accepted template derives JPEG DNG descriptors once and exact replay never reexposes`() {
        val root = File.createTempFile("native-capture-template-v2", "").also { it.delete(); assertTrue(it.mkdirs()) }
        val budget = StorageBudgetCoordinatorV2(
            File(root, "budget"), StorageBudgetPolicyV2(16L * 1024 * 1024, 0), JvmDescriptorFilesystemV2(),
        ) { 16L * 1024 * 1024 }
        val durable = DurableSessionStoreV2(File(root, "store"), budget, filesystemBackend = JvmDescriptorFilesystemV2())
        try {
            val described = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG))
            val template = rebuildRequest(described, components = emptyList(), timestamp = 0)
            val camera = FakeExposure()
            val adapter = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(durable), camera)
            adapter.admit(template)
            camera.components(camera.requests.single().first, template, listOf(CaptureComponentKind.DNG, CaptureComponentKind.JPEG))
            assertEquals(1L, adapter.snapshot().committed)
            assertEquals(2L, adapter.snapshot().closedComponents)

            val replayCamera = FakeExposure()
            val replay = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(durable), replayCamera)
            assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, replay.admit(template).phase)
            assertTrue(replayCamera.requests.isEmpty())
            val changedTemplate = rebuildRequest(template, poseHash = digest("changed-template"))
            assertTrue(runCatching { replay.admit(changedTemplate) }.exceptionOrNull() is DurableStoreConflictV2)
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
            File(root, "budget"), StorageBudgetPolicyV2(16L * 1024 * 1024, 0), JvmDescriptorFilesystemV2(),
        ) { 16L * 1024 * 1024 }
        var durable = DurableSessionStoreV2(File(root, "store"), budget, filesystemBackend = JvmDescriptorFilesystemV2())
        try {
            val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 10)
            val firstCamera = FakeExposure()
            val first = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(durable), firstCamera)
            first.admit(request)
            first.onLifecycle(CaptureLifecycleEvent.BACKGROUNDED)
            assertEquals(null, durable.queryReceipt(request.accepted.identity))
            durable.close()
            durable = DurableSessionStoreV2(File(root, "store"), budget, filesystemBackend = JvmDescriptorFilesystemV2())
            durable.recover()
            checkNotNull(durable.queryReceipt(request.accepted.identity))

            val replayCamera = FakeExposure()
            val replay = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(durable), replayCamera)
            assertTrue(runCatching { replay.admit(request) }.exceptionOrNull() is DurableStoreConflictV2)
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
    fun `ten seconds presents unknown and thirty seconds durably classifies without a shutter retry`() {
        var clock = 0L
        val store = FakeStore()
        val camera = FakeExposure()
        val events = mutableListOf<NativeCaptureEventV2>()
        val adapter = NativeCaptureAdapterV2(store, camera, nowMs = { clock }, events = events::add)
        val automatic = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG))
        adapter.admit(automatic)
        clock = 10_000L
        adapter.advanceDeadlines()
        assertTrue(store.terminals.isEmpty())
        assertEquals(1, camera.requests.size)
        assertEquals(1, adapter.snapshot().running)
        assertEquals("terminal-presentation-10s", events.last { it.kind == NativeCaptureEventKindV2.RECOVERING }.reason)
        assertEquals(1L, adapter.snapshot().unknownQueries)

        clock = 30_000L
        adapter.advanceDeadlines()
        assertTrue(store.queries.contains(automatic.accepted.identity))
        assertEquals("terminal-fence-30s", store.terminals.last().terminal?.reason)
        assertEquals(0L, adapter.snapshot().unknownQueries)
        assertEquals(1, camera.requests.size)
        assertEquals(0, adapter.snapshot().running)
    }

    @Test
    fun `terminal persistence racing exact query counts one exposure terminal and clears unknown`() {
        var clock = 0L
        val store = FakeStore().apply {
            abandonEntered = CountDownLatch(1)
            abandonRelease = CountDownLatch(1)
        }
        val camera = FakeExposure()
        val events = mutableListOf<NativeCaptureEventV2>()
        val adapter = NativeCaptureAdapterV2(store, camera, nowMs = { clock }, events = events::add)
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))
        adapter.admit(request)
        val qualifier = camera.requests.single().first
        val executor = Executors.newSingleThreadExecutor()
        try {
            val producer = executor.submit {
                camera.callback!!.onFailure(qualifier, "synthetic-camera")
            }
            assertTrue(store.abandonEntered!!.await(5, TimeUnit.SECONDS))

            clock = 30_000L
            adapter.advanceDeadlines()
            store.abandonRelease!!.countDown()
            producer.get(5, TimeUnit.SECONDS)

            val health = adapter.snapshot()
            assertEquals(1L, health.exposures)
            assertEquals(0L, health.committed)
            assertEquals(1L, health.abandoned)
            assertEquals(0L, health.unknownQueries)
            assertEquals(
                1,
                events.count {
                    it.kind == NativeCaptureEventKindV2.ABANDONED &&
                        it.attemptId == request.accepted.identity.attemptId
                },
            )
        } finally {
            store.abandonRelease!!.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `three abandoned and one committed remain exact across recovery queries`() {
        val store = FakeStore()
        val camera = FakeExposure()
        val events = mutableListOf<NativeCaptureEventV2>()
        val startupReady = CountDownLatch(1)
        val dispatcher = NativeCaptureRecoveryDispatcherV2(
            recover = { emptyList() },
            events = {
                events += it
                if (it.kind == NativeCaptureEventKindV2.READY) startupReady.countDown()
            },
        )
        assertTrue(startupReady.await(5, TimeUnit.SECONDS))
        events.clear()
        val adapter = NativeCaptureAdapterV2(store, camera, events = dispatcher::emitLive)

        val cameraFailure = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 1)
        adapter.admit(cameraFailure)
        camera.callback!!.onFailure(camera.requests.last().first, "synthetic-camera")
        dispatcher.acknowledgeTerminal(cameraFailure.accepted.identity.attemptId)

        val malformed = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG), ordinal = 2)
        adapter.admit(malformed)
        camera.components(
            camera.requests.last().first,
            malformed,
            listOf(CaptureComponentKind.JPEG, CaptureComponentKind.JPEG),
        )
        dispatcher.acknowledgeTerminal(malformed.accepted.identity.attemptId)

        store.failCommitAfterReads = 0
        val storeFailure = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG), ordinal = 3)
        adapter.admit(storeFailure)
        camera.components(camera.requests.last().first, storeFailure, listOf(CaptureComponentKind.JPEG))
        store.failCommitAfterReads = null
        dispatcher.acknowledgeTerminal(storeFailure.accepted.identity.attemptId)

        val committed = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG), ordinal = 4)
        adapter.admit(committed)
        camera.components(camera.requests.last().first, committed, listOf(CaptureComponentKind.JPEG))
        repeat(5) {
            adapter.forceRecoveryForDebug()
            val replay = dispatcher.replaySnapshot()
            assertEquals(1, replay.size)
            assertEquals(NativeCaptureEventKindV2.COMMITTED, replay.single().kind)
            assertEquals(committed.accepted.identity.attemptId, replay.single().attemptId)
        }

        val health = adapter.snapshot()
        assertEquals(4L, health.exposures)
        assertEquals(1L, health.committed)
        assertEquals(3L, health.abandoned)
        assertEquals(0L, health.unknownQueries)
        assertEquals(1, events.count { it.kind == NativeCaptureEventKindV2.COMMITTED })
        assertEquals(3, events.count { it.kind == NativeCaptureEventKindV2.ABANDONED })
        dispatcher.acknowledgeTerminal(committed.accepted.identity.attemptId)
        assertEquals(NativeCaptureEventKindV2.READY, dispatcher.replaySnapshot().single().kind)
        assertEquals(health, adapter.snapshot())
        dispatcher.close()
        assertTrue(dispatcher.awaitTerminationForTest(5, TimeUnit.SECONDS))
    }

    @Test
    fun `synchronous submit failure returning false is terminal before exposure`() {
        val store = FakeStore()
        val events = mutableListOf<NativeCaptureEventV2>()
        var requests = 0
        var acceptedCallback: SharedCameraExposureCallbackV2? = null
        var acceptedQualifier: CaptureAttemptQualifierV2? = null
        val camera = object : SharedCameraExposurePortV2 {
            override fun requestExposure(
                qualifier: CaptureAttemptQualifierV2,
                required: Set<CaptureComponentKind>,
                callback: SharedCameraExposureCallbackV2,
            ): Boolean {
                requests += 1
                if (requests == 1) {
                    callback.onFailure(qualifier, "camera-submit")
                    return false
                }
                acceptedCallback = callback
                acceptedQualifier = qualifier
                return true
            }

            override fun cancelExposure(qualifier: CaptureAttemptQualifierV2) = Unit
        }
        val adapter = NativeCaptureAdapterV2(store, camera, events = events::add)
        val rejected = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 1)
        adapter.admit(rejected)

        var health = adapter.snapshot()
        assertEquals(0L, health.exposures)
        assertEquals(0L, health.committed)
        assertEquals(0L, health.abandoned)
        assertEquals(0L, health.unknownQueries)
        assertEquals(0, health.running)
        assertEquals(1, events.count { it.kind == NativeCaptureEventKindV2.ABANDONED })

        val later = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 2)
        adapter.admit(later)
        acceptedCallback!!.onComponents(
            SharedCameraComponentSetV2(
                acceptedQualifier!!,
                listOf(
                    CaptureComponentStreamV2(
                        CaptureComponentKind.JPEG,
                        CloseTrackingInputStream(bytes(CaptureComponentKind.JPEG)),
                    ),
                ),
            ),
        )
        health = adapter.snapshot()
        assertEquals(1L, health.exposures)
        assertEquals(1L, health.committed)
        assertEquals(0L, health.abandoned)
        assertEquals(0L, health.unknownQueries)
    }

    @Test
    fun `synchronous components followed by false stay pre-exposure and close exactly once`() {
        val store = FakeStore()
        val events = mutableListOf<NativeCaptureEventV2>()
        val inputs = mutableListOf<CloseTrackingInputStream>()
        var result = false
        val camera = object : SharedCameraExposurePortV2 {
            override fun requestExposure(
                qualifier: CaptureAttemptQualifierV2,
                required: Set<CaptureComponentKind>,
                callback: SharedCameraExposureCallbackV2,
            ): Boolean {
                val input = CloseTrackingInputStream(bytes(CaptureComponentKind.JPEG)).also(inputs::add)
                callback.onComponents(
                    SharedCameraComponentSetV2(
                        qualifier,
                        listOf(CaptureComponentStreamV2(CaptureComponentKind.JPEG, input)),
                    ),
                )
                return result
            }
            override fun cancelExposure(qualifier: CaptureAttemptQualifierV2) = Unit
        }
        val adapter = NativeCaptureAdapterV2(store, camera, events = events::add)
        val rejected = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 1)
        adapter.admit(rejected)

        var health = adapter.snapshot()
        assertEquals(0L, health.exposures)
        assertEquals(0L, health.committed)
        assertEquals(0L, health.abandoned)
        assertEquals(0L, health.unknownQueries)
        assertEquals(1, inputs.single().closeCalls)
        assertEquals(1, store.terminals.count { it.identity == rejected.accepted.identity })
        assertEquals(0, events.count { it.kind == NativeCaptureEventKindV2.COMMITTED })

        result = true
        val later = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 2)
        adapter.admit(later)
        health = adapter.snapshot()
        assertEquals(1L, health.exposures)
        assertEquals(1L, health.committed)
        assertEquals(0L, health.abandoned)
        assertEquals(0L, health.unknownQueries)
        assertEquals(1, inputs.last().closeCalls)
    }

    @Test
    fun `future synthetic route does not swallow cancellation of a direct owner`() {
        data class DirectOwner(val qualifier: CaptureAttemptQualifierV2)

        val hook = AttemptQualifiedExposureHookV2()
        val direct = QualifiedCaptureAttemptOwnerV2<DirectOwner, CaptureAttemptQualifierV2>(DirectOwner::qualifier)
        val callback = object : SharedCameraExposureCallbackV2 {
            override fun onComponents(components: SharedCameraComponentSetV2) = Unit
            override fun onFailure(qualifier: CaptureAttemptQualifierV2, reason: String) = Unit
        }
        var directCorrelatorClears = 0
        val syntheticCancels = mutableListOf<String>()
        fun qualifier(ordinal: Long, generation: Long): CaptureAttemptQualifierV2 {
            val accepted = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal).accepted
            return CaptureAttemptQualifierV2(accepted.identity, accepted.identity.lifecycleCut, generation)
        }
        fun cancel(qualifier: CaptureAttemptQualifierV2) {
            if (!hook.cancel(qualifier) && direct.cancel(qualifier) != null) {
                directCorrelatorClears += 1
            }
        }

        val directQualifier = qualifier(200, 1)
        assertTrue(direct.acquire(DirectOwner(directQualifier)))
        hook.install(request = { _, _, _ -> true }, cancel = { syntheticCancels += "B" })
        cancel(directQualifier)
        assertEquals(null, direct.get())
        assertEquals(1, directCorrelatorClears)
        assertTrue(syntheticCancels.isEmpty())

        hook.clear()
        val laterDirectQualifier = qualifier(201, 2)
        val laterDirect = DirectOwner(laterDirectQualifier)
        assertTrue(direct.acquire(laterDirect))
        assertTrue(direct.release(laterDirect))

        hook.install(request = { _, _, _ -> true }, cancel = { syntheticCancels += "B" })
        val syntheticQualifier = qualifier(202, 3)
        assertEquals(
            true,
            hook.request(syntheticQualifier, setOf(CaptureComponentKind.JPEG), callback),
        )
        cancel(syntheticQualifier)
        assertEquals(listOf("B"), syntheticCancels)
        hook.install(request = { _, _, _ -> true }, cancel = { syntheticCancels += "C" })
        cancel(syntheticQualifier)
        assertEquals(listOf("B"), syntheticCancels)
        assertEquals(1, directCorrelatorClears)
        assertEquals(0, hook.activeOwnersForTest())
        hook.clear()
    }

    @Test
    fun `concurrent route install and request observes complete route or no route`() {
        val executor = Executors.newFixedThreadPool(2)
        val callback = object : SharedCameraExposureCallbackV2 {
            override fun onComponents(components: SharedCameraComponentSetV2) = Unit
            override fun onFailure(qualifier: CaptureAttemptQualifierV2, reason: String) = Unit
        }
        try {
            repeat(100) { index ->
                val hook = AttemptQualifiedExposureHookV2()
                val barrier = CyclicBarrier(3)
                val cancelled = mutableListOf<Int>()
                val described = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 100L + index)
                val qualifier = CaptureAttemptQualifierV2(
                    described.accepted.identity,
                    described.accepted.identity.lifecycleCut,
                    1,
                )
                val installer = executor.submit {
                    barrier.await(5, TimeUnit.SECONDS)
                    hook.install(request = { _, _, _ -> true }, cancel = { cancelled += index })
                }
                val requester = executor.submit<Boolean?> {
                    barrier.await(5, TimeUnit.SECONDS)
                    hook.request(qualifier, setOf(CaptureComponentKind.JPEG), callback)
                }
                barrier.await(5, TimeUnit.SECONDS)
                installer.get(5, TimeUnit.SECONDS)
                val result = requester.get(5, TimeUnit.SECONDS)
                if (result == true) assertTrue(hook.cancel(qualifier))
                assertTrue(result == null || result)
                assertEquals(if (result == true) listOf(index) else emptyList(), cancelled)
                assertEquals(0, hook.activeOwnersForTest())
                hook.clear()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `route replacement cancels the exact in-flight owner without leaking`() {
        val hook = AttemptQualifiedExposureHookV2()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = mutableListOf<String>()
        val described = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 90)
        val qualifier = CaptureAttemptQualifierV2(
            described.accepted.identity,
            described.accepted.identity.lifecycleCut,
            1,
        )
        val callback = object : SharedCameraExposureCallbackV2 {
            override fun onComponents(components: SharedCameraComponentSetV2) = Unit
            override fun onFailure(qualifier: CaptureAttemptQualifierV2, reason: String) = Unit
        }
        hook.install(
            request = { _, _, _ ->
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                true
            },
            cancel = { cancelled += "A" },
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val accepted = executor.submit<Boolean?> {
                hook.request(qualifier, setOf(CaptureComponentKind.JPEG), callback)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            hook.install(request = { _, _, _ -> false }, cancel = { cancelled += "B" })
            release.countDown()
            assertEquals(true, accepted.get(5, TimeUnit.SECONDS))

            assertTrue(hook.cancel(qualifier))
            assertEquals(listOf("A"), cancelled)
            assertEquals(0, hook.activeOwnersForTest())
            assertTrue(hook.cancel(qualifier))
            assertEquals(listOf("A"), cancelled)

            val second = qualifier.copy(exposureGeneration = 2)
            assertEquals(false, hook.request(second, setOf(CaptureComponentKind.JPEG), callback))
            assertEquals(1, hook.activeOwnersForTest())
            hook.clear()
            assertEquals(listOf("A", "B"), cancelled)
            assertEquals(0, hook.activeOwnersForTest())
            assertTrue(hook.cancel(second))
            assertEquals(listOf("A", "B"), cancelled)
        } finally {
            release.countDown()
            hook.clear()
            executor.shutdownNow()
        }
    }

    @Test
    fun `synthetic camera malformed store and committed sequence counts four accepted exposures`() {
        val root = File.createTempFile("native-synthetic-sequence-v2", "").also {
            it.delete()
            assertTrue(it.mkdirs())
        }
        val budget = StorageBudgetCoordinatorV2(
            File(root, "budget"),
            StorageBudgetPolicyV2(16L * 1024 * 1024, 0),
            JvmDescriptorFilesystemV2(),
        ) { 16L * 1024 * 1024 }
        val durable = DurableSessionStoreV2(
            File(root, "store"),
            budget,
            filesystemBackend = JvmDescriptorFilesystemV2(),
        )
        val hook = AttemptQualifiedExposureHookV2()
        val cancelledModes = mutableListOf<String>()
        val camera = object : SharedCameraExposurePortV2 {
            override fun requestExposure(
                qualifier: CaptureAttemptQualifierV2,
                required: Set<CaptureComponentKind>,
                callback: SharedCameraExposureCallbackV2,
            ): Boolean {
                return hook.request(qualifier, required, callback) ?: false
            }
            override fun cancelExposure(qualifier: CaptureAttemptQualifierV2) {
                hook.cancel(qualifier)
            }
        }
        fun install(mode: String) {
            hook.install(request = { qualifier, required, callback ->
                when (mode) {
                    "camera" -> callback.onFailure(qualifier, "synthetic-camera")
                    "malformed" -> callback.onComponents(
                        SharedCameraComponentSetV2(
                            qualifier,
                            listOf(
                                CaptureComponentStreamV2(CaptureComponentKind.JPEG, CloseTrackingInputStream(byteArrayOf(1))),
                                CaptureComponentStreamV2(CaptureComponentKind.JPEG, CloseTrackingInputStream(byteArrayOf(2))),
                            ),
                        ),
                    )
                    "store" -> callback.onComponents(
                        SharedCameraComponentSetV2(
                            qualifier,
                            required.map { kind ->
                                CaptureComponentStreamV2(kind, object : InputStream() {
                                    override fun read(): Int = throw java.io.IOException("synthetic-store-stream")
                                })
                            },
                        ),
                    )
                    else -> callback.onComponents(
                        SharedCameraComponentSetV2(
                            qualifier,
                            required.map { kind ->
                                CaptureComponentStreamV2(kind, CloseTrackingInputStream(bytes(kind)))
                            },
                        ),
                    )
                }
                true
            }, cancel = { cancelledModes += mode })
        }
        val recoveryReady = CountDownLatch(1)
        val dispatcher = NativeCaptureRecoveryDispatcherV2(
            recover = { emptyList() },
            events = { if (it.kind == NativeCaptureEventKindV2.READY) recoveryReady.countDown() },
        )
        assertTrue(recoveryReady.await(5, TimeUnit.SECONDS))
        val adapter = NativeCaptureAdapterV2(
            DurableNativeCaptureStorePortV2(durable),
            camera,
            events = dispatcher::emitLive,
        )
        val admissionExecutor = Executors.newSingleThreadExecutor()
        fun template(lane: CaptureLane, ordinal: Long): CaptureCommitRequest {
            val described = request(lane, setOf(CaptureComponentKind.JPEG), ordinal = ordinal)
            return rebuildRequest(described, components = emptyList(), timestamp = 0)
        }
        fun admit(lane: CaptureLane, ordinal: Long) {
            admissionExecutor.submit<CaptureReceipt> { adapter.admit(template(lane, ordinal)) }
                .get(5, TimeUnit.SECONDS)
        }
        try {
            install("camera")
            admit(CaptureLane.MANUAL, 1)
            dispatcher.acknowledgeTerminal(dispatcher.replaySnapshot().single().attemptId!!)
            install("malformed")
            admit(CaptureLane.AUTOMATIC, 2)
            dispatcher.acknowledgeTerminal(dispatcher.replaySnapshot().single().attemptId!!)
            install("store")
            admit(CaptureLane.AUTOMATIC, 3)
            dispatcher.acknowledgeTerminal(dispatcher.replaySnapshot().single().attemptId!!)
            install("committed")
            admit(CaptureLane.AUTOMATIC, 4)
            val committedReplay = dispatcher.replaySnapshot().single()
            repeat(3) { assertEquals(committedReplay, dispatcher.replaySnapshot().single()) }

            val health = adapter.snapshot()
            assertEquals(4L, health.exposures)
            assertEquals(1L, health.committed)
            assertEquals(3L, health.abandoned)
            assertEquals(0L, health.unknownQueries)
            assertEquals(0, health.running)
            assertEquals(0, health.fundedWaiting)
            assertEquals(listOf("camera", "malformed", "store", "committed"), cancelledModes)
            assertEquals(0, hook.activeOwnersForTest())
            dispatcher.acknowledgeTerminal(committedReplay.attemptId!!)
        } finally {
            admissionExecutor.shutdownNow()
            adapter.close()
            dispatcher.close()
            assertTrue(dispatcher.awaitTerminationForTest(5, TimeUnit.SECONDS))
            durable.close()
            budget.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `funded waiting deadline abandons before exposure without unknown liability`() {
        var clock = 0L
        val store = FakeStore()
        val camera = FakeExposure()
        val events = mutableListOf<NativeCaptureEventV2>()
        val adapter = NativeCaptureAdapterV2(store, camera, nowMs = { clock }, events = events::add)
        val running = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG), ordinal = 1)
        val waiting = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 2)
        adapter.admit(running)
        adapter.admit(waiting)

        clock = 10_000L
        adapter.advanceDeadlines()
        var health = adapter.snapshot()
        assertEquals(1L, health.exposures)
        assertEquals(0L, health.committed)
        assertEquals(0L, health.abandoned)
        assertEquals(1L, health.unknownQueries)
        assertEquals(1, health.running)
        assertEquals(0, health.fundedWaiting)
        assertTrue(
            events.none {
                it.kind == NativeCaptureEventKindV2.RECOVERING &&
                    it.attemptId == waiting.accepted.identity.attemptId
            },
        )
        assertEquals(
            1,
            events.count {
                it.kind == NativeCaptureEventKindV2.ABANDONED &&
                    it.attemptId == waiting.accepted.identity.attemptId
            },
        )

        camera.components(camera.requests.first().first, running, listOf(CaptureComponentKind.JPEG))
        val later = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = 3)
        adapter.admit(later)
        camera.components(camera.requests.last().first, later, listOf(CaptureComponentKind.JPEG))
        health = adapter.snapshot()
        assertEquals(2L, health.exposures)
        assertEquals(2L, health.committed)
        assertEquals(0L, health.abandoned)
        assertEquals(0L, health.unknownQueries)
        assertEquals(0, health.running)
        assertEquals(0, health.fundedWaiting)
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
    fun `streaming store io does not hold adapter state ownership`() {
        val store = FakeStore().apply {
            commitEntered = CountDownLatch(1)
            commitRelease = CountDownLatch(1)
            serializeCommitAndQuery = true
        }
        val camera = FakeExposure()
        val events = mutableListOf<NativeCaptureEventV2>()
        val adapter = NativeCaptureAdapterV2(store, camera, events = events::add)
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))
        adapter.admit(request)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val commit = executor.submit {
                camera.components(
                    camera.requests.single().first,
                    request,
                    listOf(CaptureComponentKind.JPEG),
                )
            }
            assertTrue(store.commitEntered!!.await(5, TimeUnit.SECONDS))

            assertEquals(
                1,
                executor.submit(java.util.concurrent.Callable { adapter.snapshot().running })
                    .get(500, TimeUnit.MILLISECONDS),
            )
            val lifecycle = executor.submit {
                adapter.onLifecycle(
                    CaptureLifecycleEvent.BACKGROUNDED,
                    request.accepted.identity.lifecycleCut,
                )
            }
            assertThrows(TimeoutException::class.java) { lifecycle.get(100, TimeUnit.MILLISECONDS) }

            store.commitRelease!!.countDown()
            commit.get(5, TimeUnit.SECONDS)
            lifecycle.get(5, TimeUnit.SECONDS)
            assertEquals(1L, adapter.snapshot().exposures)
            assertEquals(1L, adapter.snapshot().unknownQueries)
            assertEquals(0L, adapter.snapshot().committed)
            assertTrue(events.none { it.kind == NativeCaptureEventKindV2.COMMITTED })
            val durableTerminal = store.terminals.single()
            assertEquals(CaptureTerminalKind.COMMITTED_PICTURE, durableTerminal.terminal?.kind)
            val recoveredCamera = FakeExposure()
            val recoveredAdapter = NativeCaptureAdapterV2(store, recoveredCamera)
            assertEquals(durableTerminal, recoveredAdapter.admit(request))
            assertTrue(recoveredCamera.requests.isEmpty())
        } finally {
            store.commitRelease?.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `lifecycle cut during finalizing event prevents store handoff and terminal publication`() {
        val store = FakeStore()
        val camera = FakeExposure()
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))
        lateinit var adapter: NativeCaptureAdapterV2
        val events = mutableListOf<NativeCaptureEventV2>()
        adapter = NativeCaptureAdapterV2(store, camera, events = { event ->
            events += event
            if (event.kind == NativeCaptureEventKindV2.FINALIZING) {
                adapter.onLifecycle(
                    CaptureLifecycleEvent.BACKGROUNDED,
                    request.accepted.identity.lifecycleCut,
                )
            }
        })
        adapter.admit(request)
        val inputs = camera.components(
            camera.requests.single().first,
            request,
            listOf(CaptureComponentKind.JPEG),
        )

        assertEquals(1, inputs.single().closeCalls)
        assertTrue(store.terminals.isEmpty())
        assertEquals(listOf(request.accepted.identity), store.queries)
        assertEquals(1L, adapter.snapshot().exposures)
        assertEquals(1L, adapter.snapshot().unknownQueries)
        assertEquals(0L, adapter.snapshot().committed)
        assertTrue(events.none { it.kind == NativeCaptureEventKindV2.COMMITTED })
    }

    @Test
    fun `lifecycle cut during durable acceptance returns promptly and prevents exposure`() {
        val store = FakeStore().apply {
            acceptEntered = CountDownLatch(1)
            acceptRelease = CountDownLatch(1)
        }
        val camera = FakeExposure()
        val events = mutableListOf<NativeCaptureEventV2>()
        val adapter = NativeCaptureAdapterV2(store, camera, events = events::add)
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val admission = executor.submit(java.util.concurrent.Callable { adapter.admit(request) })
            assertTrue(store.acceptEntered!!.await(5, TimeUnit.SECONDS))

            executor.submit {
                adapter.onLifecycle(
                    CaptureLifecycleEvent.BACKGROUNDED,
                    request.accepted.identity.lifecycleCut,
                )
            }.get(500, TimeUnit.MILLISECONDS)

            store.acceptRelease!!.countDown()
            val abandoned = admission.get(5, TimeUnit.SECONDS)
            assertEquals(CaptureTerminalKind.ABANDONED_ATTEMPT, abandoned.terminal?.kind)
            assertTrue(camera.requests.isEmpty())
            assertEquals(0, adapter.snapshot().running)
            assertTrue(events.isEmpty())
            val recoveredCamera = FakeExposure()
            val recoveredAdapter = NativeCaptureAdapterV2(store, recoveredCamera)
            assertEquals(abandoned, recoveredAdapter.admit(request))
            assertTrue(recoveredCamera.requests.isEmpty())
        } finally {
            store.acceptRelease?.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `automatic disable cannot fence concurrent manual durable acceptance`() {
        val store = FakeStore().apply {
            acceptEntered = CountDownLatch(1)
            acceptRelease = CountDownLatch(1)
        }
        val camera = FakeExposure()
        val adapter = NativeCaptureAdapterV2(store, camera)
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val admission = executor.submit(java.util.concurrent.Callable { adapter.admit(request) })
            assertTrue(store.acceptEntered!!.await(5, TimeUnit.SECONDS))

            executor.submit {
                adapter.onLifecycle(CaptureLifecycleEvent.AUTOMATIC_DISABLED)
            }.get(500, TimeUnit.MILLISECONDS)

            store.acceptRelease!!.countDown()
            assertEquals(CaptureAttemptPhase.RESERVED_ACCEPTED, admission.get(5, TimeUnit.SECONDS).phase)
            assertEquals(1, camera.requests.size)
            assertEquals(1, adapter.snapshot().running)
        } finally {
            store.acceptRelease?.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `automatic disable fences concurrent automatic durable acceptance`() {
        val store = FakeStore().apply {
            acceptEntered = CountDownLatch(1)
            acceptRelease = CountDownLatch(1)
        }
        val camera = FakeExposure()
        val adapter = NativeCaptureAdapterV2(store, camera)
        val request = request(CaptureLane.AUTOMATIC, setOf(CaptureComponentKind.JPEG))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val admission = executor.submit(java.util.concurrent.Callable { adapter.admit(request) })
            assertTrue(store.acceptEntered!!.await(5, TimeUnit.SECONDS))

            executor.submit {
                adapter.onLifecycle(CaptureLifecycleEvent.AUTOMATIC_DISABLED)
            }.get(500, TimeUnit.MILLISECONDS)

            store.acceptRelease!!.countDown()
            assertEquals(
                CaptureTerminalKind.ABANDONED_ATTEMPT,
                admission.get(5, TimeUnit.SECONDS).terminal?.kind,
            )
            assertTrue(camera.requests.isEmpty())
            assertEquals(0, adapter.snapshot().running)
        } finally {
            store.acceptRelease?.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `close waits for transferred store ownership and fences later callbacks`() {
        val store = FakeStore().apply {
            commitEntered = CountDownLatch(1)
            commitRelease = CountDownLatch(1)
        }
        val camera = FakeExposure()
        val events = mutableListOf<NativeCaptureEventV2>()
        val adapter = NativeCaptureAdapterV2(store, camera, events = events::add)
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))
        adapter.admit(request)
        val qualifier = camera.requests.single().first
        val executor = Executors.newFixedThreadPool(2)
        try {
            val commit = executor.submit {
                camera.components(qualifier, request, listOf(CaptureComponentKind.JPEG))
            }
            assertTrue(store.commitEntered!!.await(5, TimeUnit.SECONDS))
            val close = executor.submit { adapter.close() }
            assertThrows(TimeoutException::class.java) { close.get(100, TimeUnit.MILLISECONDS) }

            store.commitRelease!!.countDown()
            commit.get(5, TimeUnit.SECONDS)
            close.get(5, TimeUnit.SECONDS)
            assertEquals(1L, adapter.snapshot().exposures)
            assertEquals(1L, adapter.snapshot().unknownQueries)
            assertEquals(0L, adapter.snapshot().committed)
            assertTrue(events.none { it.kind == NativeCaptureEventKindV2.COMMITTED })
            val durableTerminal = store.terminals.single()
            assertEquals(CaptureTerminalKind.COMMITTED_PICTURE, durableTerminal.terminal?.kind)
            val recoveredCamera = FakeExposure()
            val recoveredAdapter = NativeCaptureAdapterV2(store, recoveredCamera)
            assertEquals(durableTerminal, recoveredAdapter.admit(request))
            assertTrue(recoveredCamera.requests.isEmpty())

            val late = camera.components(qualifier, request, listOf(CaptureComponentKind.JPEG))
            assertEquals(1, late.single().closeCalls)
            assertEquals(1L, adapter.snapshot().lateCallbacks)
        } finally {
            store.commitRelease?.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `lifecycle retains an in-flight accepted submission as exposed unknown`() {
        val store = FakeStore()
        val camera = BlockingExposure(result = true, componentsBeforeResult = true)
        val events = mutableListOf<NativeCaptureEventV2>()
        val adapter = NativeCaptureAdapterV2(store, camera, events = events::add)
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val admission = executor.submit(java.util.concurrent.Callable { adapter.admit(request) })
            assertTrue(camera.entered.await(5, TimeUnit.SECONDS))

            executor.submit {
                adapter.onLifecycle(
                    CaptureLifecycleEvent.BACKGROUNDED,
                    request.accepted.identity.lifecycleCut,
                )
            }.get(500, TimeUnit.MILLISECONDS)
            assertEquals(1, adapter.snapshot().running)
            assertEquals(1, camera.synchronousInput!!.closeCalls)

            camera.release.countDown()
            admission.get(5, TimeUnit.SECONDS)

            val health = adapter.snapshot()
            assertEquals(1L, health.exposures)
            assertEquals(0L, health.committed)
            assertEquals(0L, health.abandoned)
            assertEquals(1L, health.unknownQueries)
            assertEquals(0, health.running)
            assertEquals(1, store.queries.count { it == request.accepted.identity })
            assertEquals(listOf(camera.qualifier), camera.cancellations)
            assertEquals(1, camera.synchronousInput!!.closeCalls)
            assertTrue(store.terminals.isEmpty())
            assertEquals(
                1,
                events.count {
                    it.kind == NativeCaptureEventKindV2.RECOVERING &&
                        it.attemptId == request.accepted.identity.attemptId
                },
            )

            val late = camera.components(request)
            assertTrue(late.closed)
            assertEquals(1L, adapter.snapshot().lateCallbacks)
        } finally {
            camera.release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `close waits for an in-flight accepted submission then drains and fences it`() {
        val store = FakeStore()
        val camera = BlockingExposure(result = true, componentsBeforeResult = true)
        val events = mutableListOf<NativeCaptureEventV2>()
        val adapter = NativeCaptureAdapterV2(store, camera, events = events::add)
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val admission = executor.submit(java.util.concurrent.Callable { adapter.admit(request) })
            assertTrue(camera.entered.await(5, TimeUnit.SECONDS))
            val close = executor.submit { adapter.close() }
            assertThrows(TimeoutException::class.java) { close.get(100, TimeUnit.MILLISECONDS) }
            assertEquals(1, camera.synchronousInput!!.closeCalls)

            camera.release.countDown()
            admission.get(5, TimeUnit.SECONDS)
            close.get(5, TimeUnit.SECONDS)

            val health = adapter.snapshot()
            assertEquals(1L, health.exposures)
            assertEquals(0L, health.committed)
            assertEquals(0L, health.abandoned)
            assertEquals(1L, health.unknownQueries)
            assertEquals(0, health.running)
            assertEquals(1, store.queries.count { it == request.accepted.identity })
            assertEquals(listOf(camera.qualifier), camera.cancellations)
            assertEquals(1, camera.synchronousInput!!.closeCalls)
            assertTrue(store.terminals.isEmpty())
            val eventCountAfterClose = events.size
            val late = camera.components(request)
            assertTrue(late.closed)
            assertEquals(eventCountAfterClose, events.size)
            assertEquals(1L, adapter.snapshot().lateCallbacks)
        } finally {
            camera.release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `lifecycle and close keep rejected in-flight submissions pre-exposure`() {
        listOf(false, true).forEachIndexed { index, closeAdapter ->
            val store = FakeStore()
            val camera = BlockingExposure(result = false, componentsBeforeResult = true)
            val adapter = NativeCaptureAdapterV2(store, camera)
            val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = (index + 1).toLong())
            val executor = Executors.newFixedThreadPool(2)
            try {
                val admission = executor.submit(java.util.concurrent.Callable { adapter.admit(request) })
                assertTrue(camera.entered.await(5, TimeUnit.SECONDS))
                val cut = executor.submit {
                    if (closeAdapter) {
                        adapter.close()
                    } else {
                        adapter.onLifecycle(
                            CaptureLifecycleEvent.BACKGROUNDED,
                            request.accepted.identity.lifecycleCut,
                        )
                    }
                }
                if (closeAdapter) {
                    assertThrows(TimeoutException::class.java) { cut.get(100, TimeUnit.MILLISECONDS) }
                } else {
                    cut.get(500, TimeUnit.MILLISECONDS)
                }

                camera.release.countDown()
                admission.get(5, TimeUnit.SECONDS)
                cut.get(5, TimeUnit.SECONDS)

                val health = adapter.snapshot()
                assertEquals(0L, health.exposures)
                assertEquals(0L, health.committed)
                assertEquals(0L, health.abandoned)
                assertEquals(0L, health.unknownQueries)
                assertEquals(0, health.running)
                assertEquals(0, store.queries.size)
                assertEquals(1, store.terminals.count { it.identity == request.accepted.identity })
                assertEquals(1, camera.synchronousInput!!.closeCalls)
            } finally {
                camera.release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `lifecycle cut dominates deferred failure after accepted submission`() {
        val store = FakeStore()
        val camera = BlockingExposure(result = true, failureBeforeResult = true)
        val events = mutableListOf<NativeCaptureEventV2>()
        val adapter = NativeCaptureAdapterV2(store, camera, events = events::add)
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val admission = executor.submit(java.util.concurrent.Callable { adapter.admit(request) })
            assertTrue(camera.entered.await(5, TimeUnit.SECONDS))
            adapter.onLifecycle(CaptureLifecycleEvent.BACKGROUNDED, request.accepted.identity.lifecycleCut)
            camera.release.countDown()
            admission.get(5, TimeUnit.SECONDS)

            assertEquals(1L, adapter.snapshot().exposures)
            assertEquals(1L, adapter.snapshot().unknownQueries)
            assertEquals(0L, adapter.snapshot().abandoned)
            assertTrue(store.terminals.isEmpty())
            assertTrue(events.none { it.kind == NativeCaptureEventKindV2.ABANDONED })
        } finally {
            camera.release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `close dominates deferred malformed components after accepted submission`() {
        val store = FakeStore()
        val camera = BlockingExposure(result = true, malformedBeforeResult = true)
        val events = mutableListOf<NativeCaptureEventV2>()
        val adapter = NativeCaptureAdapterV2(store, camera, events = events::add)
        val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val admission = executor.submit(java.util.concurrent.Callable { adapter.admit(request) })
            assertTrue(camera.entered.await(5, TimeUnit.SECONDS))
            val close = executor.submit { adapter.close() }
            assertThrows(TimeoutException::class.java) { close.get(100, TimeUnit.MILLISECONDS) }
            assertTrue(camera.synchronousInputs.all { it.closeCalls == 1 })
            camera.release.countDown()
            admission.get(5, TimeUnit.SECONDS)
            close.get(5, TimeUnit.SECONDS)

            assertEquals(1L, adapter.snapshot().exposures)
            assertEquals(1L, adapter.snapshot().unknownQueries)
            assertEquals(0L, adapter.snapshot().abandoned)
            assertTrue(store.terminals.isEmpty())
            assertTrue(events.none { it.kind == NativeCaptureEventKindV2.ABANDONED })
        } finally {
            camera.release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `submission and store cancellation or error propagate after ownership cleanup`() {
        listOf<Throwable>(java.util.concurrent.CancellationException("cancel"), AssertionError("fatal")).forEachIndexed { index, failure ->
            val store = FakeStore()
            val camera = object : SharedCameraExposurePortV2 {
                override fun requestExposure(
                    qualifier: CaptureAttemptQualifierV2,
                    required: Set<CaptureComponentKind>,
                    callback: SharedCameraExposureCallbackV2,
                ): Boolean = throw failure
                override fun cancelExposure(qualifier: CaptureAttemptQualifierV2) = Unit
            }
            val adapter = NativeCaptureAdapterV2(store, camera)
            val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = (index + 1).toLong())
            assertThrows(failure.javaClass) { adapter.admit(request) }
            assertEquals(0, adapter.snapshot().running)
            assertEquals(0L, adapter.snapshot().exposures)
        }

        listOf<Throwable>(java.util.concurrent.CancellationException("cancel"), AssertionError("fatal")).forEachIndexed { index, failure ->
            val store = FakeStore().apply { commitFailure = failure }
            val camera = FakeExposure()
            val adapter = NativeCaptureAdapterV2(store, camera)
            val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = (index + 3).toLong())
            adapter.admit(request)
            assertThrows(failure.javaClass) {
                camera.components(camera.requests.single().first, request, listOf(CaptureComponentKind.JPEG))
            }
            assertEquals(0, adapter.snapshot().running)
            assertEquals(1L, adapter.snapshot().exposures)
        }

        listOf<Throwable>(java.util.concurrent.CancellationException("cancel"), AssertionError("fatal")).forEachIndexed { index, failure ->
            val store = FakeStore().apply {
                failCommitAfterReads = 0
                rebaseFailure = failure
            }
            val camera = FakeExposure()
            val adapter = NativeCaptureAdapterV2(store, camera)
            val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = (index + 5).toLong())
            adapter.admit(request)
            assertThrows(failure.javaClass) {
                camera.components(camera.requests.single().first, request, listOf(CaptureComponentKind.JPEG))
            }
            assertEquals(0, adapter.snapshot().running)
        }
    }

    @Test
    fun `lifecycle transfer cannot swallow blocked rebase cancellation or error`() {
        listOf<Throwable>(java.util.concurrent.CancellationException("cancel"), AssertionError("fatal")).forEachIndexed { index, failure ->
            val store = FakeStore().apply {
                failCommitAfterReads = 0
                rebaseFailure = failure
                rebaseEntered = CountDownLatch(1)
                rebaseRelease = CountDownLatch(1)
                serializeCommitAndQuery = true
            }
            val camera = FakeExposure()
            val events = mutableListOf<NativeCaptureEventV2>()
            val adapter = NativeCaptureAdapterV2(store, camera, events = events::add)
            val request = request(CaptureLane.MANUAL, setOf(CaptureComponentKind.JPEG), ordinal = (index + 10).toLong())
            adapter.admit(request)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val callback = executor.submit {
                    camera.components(camera.requests.single().first, request, listOf(CaptureComponentKind.JPEG))
                }
                assertTrue(store.rebaseEntered!!.await(5, TimeUnit.SECONDS))
                val lifecycle = executor.submit {
                    adapter.onLifecycle(CaptureLifecycleEvent.BACKGROUNDED, request.accepted.identity.lifecycleCut)
                }
                assertThrows(TimeoutException::class.java) { lifecycle.get(100, TimeUnit.MILLISECONDS) }
                store.rebaseRelease!!.countDown()
                val thrown = assertThrows(java.util.concurrent.ExecutionException::class.java) {
                    callback.get(5, TimeUnit.SECONDS)
                }
                assertEquals(failure.javaClass, thrown.cause!!.javaClass)
                lifecycle.get(5, TimeUnit.SECONDS)
                assertEquals(0, adapter.snapshot().running)
                assertTrue(store.terminals.isEmpty())
                assertTrue(events.none { it.kind == NativeCaptureEventKindV2.ABANDONED || it.kind == NativeCaptureEventKindV2.COMMITTED })
            } finally {
                store.rebaseRelease?.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `every lifecycle event transfers post-exposure ownership as unknown and late components close`() {
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
            assertTrue(store.terminals.isEmpty())
            assertTrue(store.queries.contains(request.accepted.identity))
            assertTrue(camera.cancellations.contains(qualifier))
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
        assertEquals(1, store.terminals.count { it.terminal?.kind == CaptureTerminalKind.ABANDONED_ATTEMPT })
        assertEquals(1, store.queries.size)
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
        assertEquals(1, store.terminals.count { it.terminal?.kind == CaptureTerminalKind.ABANDONED_ATTEMPT })
        assertEquals(1, store.queries.size)
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
            CaptureReservationLiability(
                1024,
                NativeCaptureReservationBoundsV2.physicalBytes(1024, kinds.size),
                kinds.size.toLong(),
                1,
                NativeCaptureReservationBoundsV2.rollbackBytes(1024),
                true,
            ),
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
        val cancellations = mutableListOf<CaptureAttemptQualifierV2>()
        var callback: SharedCameraExposureCallbackV2? = null
        override fun requestExposure(qualifier: CaptureAttemptQualifierV2, required: Set<CaptureComponentKind>, callback: SharedCameraExposureCallbackV2): Boolean {
            requests += qualifier to required; this.callback = callback; return true
        }
        override fun cancelExposure(qualifier: CaptureAttemptQualifierV2) { cancellations += qualifier }
        fun components(qualifier: CaptureAttemptQualifierV2, request: CaptureCommitRequest, order: List<CaptureComponentKind>): List<CloseTrackingInputStream> {
            val sources = order.map { CloseTrackingInputStream(bytes(it)) }
            callback!!.onComponents(SharedCameraComponentSetV2(qualifier, order.zip(sources).map { (kind, source) ->
                CaptureComponentStreamV2(kind, source)
            }))
            return sources
        }
    }

    private class BlockingExposure(
        private val result: Boolean,
        private val componentsBeforeResult: Boolean = false,
        private val failureBeforeResult: Boolean = false,
        private val malformedBeforeResult: Boolean = false,
    ) : SharedCameraExposurePortV2 {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var qualifier: CaptureAttemptQualifierV2
            private set
        private lateinit var callback: SharedCameraExposureCallbackV2
        val cancellations = mutableListOf<CaptureAttemptQualifierV2>()
        val synchronousInputs = mutableListOf<CloseTrackingInputStream>()
        val synchronousInput: CloseTrackingInputStream? get() = synchronousInputs.firstOrNull()
        override fun requestExposure(
            qualifier: CaptureAttemptQualifierV2,
            required: Set<CaptureComponentKind>,
            callback: SharedCameraExposureCallbackV2,
        ): Boolean {
            this.qualifier = qualifier
            this.callback = callback
            if (componentsBeforeResult || malformedBeforeResult) {
                val count = if (malformedBeforeResult) 2 else 1
                val inputs = List(count) { CloseTrackingInputStream(bytes(CaptureComponentKind.JPEG)) }
                synchronousInputs += inputs
                callback.onComponents(
                    SharedCameraComponentSetV2(
                        qualifier,
                        inputs.map { CaptureComponentStreamV2(CaptureComponentKind.JPEG, it) },
                    ),
                )
            }
            if (failureBeforeResult) callback.onFailure(qualifier, "deferred-failure")
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            return result
        }
        override fun cancelExposure(qualifier: CaptureAttemptQualifierV2) {
            cancellations += qualifier
        }
        fun components(request: CaptureCommitRequest): CloseTrackingInputStream {
            val input = CloseTrackingInputStream(bytes(CaptureComponentKind.JPEG))
            callback.onComponents(
                SharedCameraComponentSetV2(
                    qualifier,
                    listOf(CaptureComponentStreamV2(CaptureComponentKind.JPEG, input)),
                ),
            )
            return input
        }
    }

    private class FakeStore : NativeCaptureStorePortV2 {
        val accepted = mutableListOf<CaptureCommitRequest>()
        val terminals = mutableListOf<CaptureReceipt>()
        val queries = mutableListOf<CaptureAttemptIdentity>()
        var failCommitAfterReads: Int? = null
        var commitFailure: Throwable? = null
        var rebaseFailure: Throwable? = null
        var rebaseEntered: CountDownLatch? = null
        var rebaseRelease: CountDownLatch? = null
        var acceptEntered: CountDownLatch? = null
        var acceptRelease: CountDownLatch? = null
        var commitEntered: CountDownLatch? = null
        var commitRelease: CountDownLatch? = null
        var serializeCommitAndQuery: Boolean = false
        var abandonEntered: CountDownLatch? = null
        var abandonRelease: CountDownLatch? = null
        private val firstAbandonBlocked = AtomicBoolean(false)
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
            acceptEntered?.countDown()
            acceptRelease?.await(5, TimeUnit.SECONDS)
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
        private val storeMutex = Any()
        override fun commitStreamed(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>): CaptureReceipt =
            if (serializeCommitAndQuery) synchronized(storeMutex) { commitBody(request, streams) }
            else commitBody(request, streams)
        private fun commitBody(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>): CaptureReceipt {
            commitEntered?.countDown()
            commitRelease?.await(5, TimeUnit.SECONDS)
            commitFailure?.let { throw it }
            failCommitAfterReads?.let { reads ->
                repeat(reads) { streams.first().input.read() }
                throw DurableStoreConflictV2("injected store rejection")
            }
            streams.forEach { it.input.use { source -> while (source.read() >= 0) {} } }
            val terminal = CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, request.accepted.identity, "commit", "committed", "capture", 1, "root")
            return CaptureReceipt(request.accepted.identity, CaptureAttemptPhase.COMMITTED_PICTURE, "commit", "receipt", true, terminal).also(terminals::add)
        }
        override fun queryReceipt(identity: CaptureAttemptIdentity): CaptureReceipt? =
            if (serializeCommitAndQuery) synchronized(storeMutex) { queryBody(identity) }
            else queryBody(identity)
        private fun queryBody(identity: CaptureAttemptIdentity): CaptureReceipt? {
            queries += identity
            return terminals.firstOrNull { it.identity == identity }
        }
        override fun rebaseSameAttempt(request: CaptureCommitRequest): CaptureReceipt {
            return if (serializeCommitAndQuery) synchronized(storeMutex) { rebaseBody() }
            else rebaseBody()
        }
        private fun rebaseBody(): CaptureReceipt {
            rebaseEntered?.countDown()
            rebaseRelease?.await(5, TimeUnit.SECONDS)
            rebaseFailure?.let { throw it }
            throw IllegalStateException("not prepared")
        }
        override fun abandon(request: CaptureCommitRequest, terminal: CaptureTerminal): CaptureReceipt {
            val receipt = CaptureReceipt(terminal.identity, CaptureAttemptPhase.ABANDONED_ATTEMPT, terminal.canonicalTerminalHash, "abandoned", true, terminal)
                .also(terminals::add)
            if (abandonEntered != null && firstAbandonBlocked.compareAndSet(false, true)) {
                abandonEntered!!.countDown()
                abandonRelease!!.await(5, TimeUnit.SECONDS)
            }
            return receipt
        }
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
