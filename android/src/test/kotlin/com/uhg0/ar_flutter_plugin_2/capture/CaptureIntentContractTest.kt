package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureIntentContractTest {
    @Test
    fun `native reservation bounds include durable metadata coexistence and rollback with checked arithmetic`() {
        val physical = NativeCaptureReservationBoundsV2.physicalBytes(1024, 2)
        assertTrue(physical > 1024 + NativeCaptureReservationBoundsV2.COEXISTENCE_BYTES)
        assertEquals(1024 + NativeCaptureReservationBoundsV2.ROLLBACK_METADATA_BYTES,
            NativeCaptureReservationBoundsV2.rollbackBytes(1024))
        assertFails { NativeCaptureReservationBoundsV2.physicalBytes(Long.MAX_VALUE, 2) }
        assertFails { NativeCaptureReservationBoundsV2.rollbackBytes(Long.MAX_VALUE) }
    }
    @Test
    fun `accepted record is durable before exactly one exposure and one committed terminal`() {
        val accepted = accepted()
        val machine = CaptureAttemptReferenceMachine(accepted)
        assertTrue(machine.receipt.durable)
        assertEquals(CaptureTransitionDisposition.APPLIED, machine.requestExposure("expose").disposition)
        assertEquals(CaptureTransitionDisposition.EXACT_REPLAY, machine.requestExposure("expose").disposition)
        assertEquals(1, machine.exposureCount)
        val components = components()
        machine.ownSensorOutput("output", components)
        machine.validate("validate")
        assertEquals(CaptureTransitionDisposition.APPLIED, machine.prepareDurable("prepare", commit(accepted, components)).disposition)
        assertEquals(0, machine.retainedImageBytes)
        val terminal = machine.commit("commit", "capture", 1, "manifest")
        assertEquals(CaptureTerminalKind.COMMITTED_PICTURE, terminal.receipt.terminal?.kind)
        assertEquals(CaptureTransitionDisposition.EXACT_REPLAY, machine.commit("commit", "capture", 1, "manifest").disposition)
        assertEquals(CaptureTransitionDisposition.CONFLICT, machine.commit("changed", "other", 2, "other").disposition)
    }

    @Test
    fun `timeout is unknown and overflow abandons metadata only`() {
        val accepted = accepted(storeBytes = 100)
        val machine = CaptureAttemptReferenceMachine(accepted)
        machine.requestExposure("expose")
        assertEquals(CaptureAttemptPhase.EXPOSURE_REQUESTED, machine.timeoutUnknown().receipt.phase)
        assertTrue(machine.outcomeUnknown)
        val components = components(80)
        machine.ownSensorOutput("output", components)
        machine.validate("validate")
        val terminal = machine.prepareDurable("prepare", commit(accepted, components)).receipt.terminal
        assertEquals(CaptureTerminalKind.ABANDONED_ATTEMPT, terminal?.kind)
        assertEquals("reservation-overflow", terminal?.reason)
        assertEquals(0, machine.retainedImageBytes)
        assertFalse(machine.outcomeUnknown)
        assertEquals("late-producer-fenced", machine.lateCallback().reason)
    }

    @Test
    fun `generated matrix covers every preterminal fault and lifecycle cut`() {
        val matrix = CaptureFaultLifecycleMatrix.generate()
        assertEquals(CaptureLane.entries.size * 5 * CaptureFault.entries.size * CaptureLifecycleEvent.entries.size, matrix.size)
        assertEquals(CaptureLane.entries.toSet(), matrix.map { it.lane }.toSet())
        assertEquals(CaptureAttemptPhase.entries.take(5).toSet(), matrix.map { it.phase }.toSet())
        assertEquals(CaptureFault.entries.toSet(), matrix.map { it.fault }.toSet())
        assertEquals(CaptureLifecycleEvent.entries.toSet(), matrix.map { it.lifecycleEvent }.toSet())
        assertEquals(CaptureMatrixOutcome.entries.toSet(), matrix.map { it.expectedOutcome }.toSet())
    }

    @Test
    fun `transition table rejects every noncanonical edge`() {
        val pairs = CaptureAttemptPhase.entries.flatMap { from ->
            CaptureAttemptPhase.entries.map { destination -> Pair(from, destination) }
        }
        assertEquals(10, pairs.count { CaptureAttemptTransitionTable.isLegal(it.first, it.second) })
        assertEquals(39, pairs.count { !CaptureAttemptTransitionTable.isLegal(it.first, it.second) })
    }

    @Test
    fun `intent numeric domains and digests reject malformed values`() {
        val intent = CaptureIntent(CaptureLane.AUTOMATIC, lifecycle(), profile(), true, true, true, true, digest("intent"))
        assertEquals(CaptureLane.AUTOMATIC, intent.lane)
        assertEquals(CaptureIntentState.entries.size, 8)
        assertFails { CaptureComponentDescriptor(CaptureComponentKind.JPEG, -1, digest("jpeg"), "object") }
        assertFails { CaptureReservationLiability(-1, 0, 0, 0, 0, true) }
        assertFails { CaptureReservationLiability(0, 0, CAPTURE_PORTABLE_ENTRY_MAXIMUM + 1, 0, 0, true) }
        assertFails { CaptureAttemptIdentity("a", "c", 0, lifecycle()) }
        assertFails { CaptureLifecycleCut("s", -1, "g", 0, "a", "v", 0, "b", 0, 0) }
        assertFails { CaptureComponentDescriptor(CaptureComponentKind.JPEG, 1, listOf(1), "object") }
        assertFails { CaptureReservationLiability(0, Long.MAX_VALUE, 0, 0, 1, true) }
    }

    @Test
    fun `contract values defensively copy collections and retain structural equality`() {
        val mutableDigest = digest("intent").toMutableList()
        val firstIntent = CaptureIntent(CaptureLane.AUTOMATIC, lifecycle(), profile(), true, true, true, true, mutableDigest)
        val equalIntent = CaptureIntent(CaptureLane.AUTOMATIC, lifecycle(), profile(), true, true, true, true, mutableDigest.toList())
        val stableHash = firstIntent.hashCode()
        val machine = CaptureAttemptReferenceMachine(CaptureAcceptedAttempt(
            CaptureAttemptIdentity("immutable", "immutable-commit", 7, lifecycle()), CaptureLane.AUTOMATIC,
            profile(), CaptureReservationLiability(10, 10, 2, 1, 0, true), mutableDigest, digest("receipt")))
        val stableReceipt = machine.receipt.receiptHash
        mutableDigest[0] = mutableDigest[0] xor 0xff
        assertEquals(equalIntent, firstIntent)
        assertEquals(stableHash, firstIntent.hashCode())
        assertEquals(stableReceipt, machine.receipt.receiptHash)
        assertFails { (firstIntent.canonicalIntentHash as MutableList<Int>)[0] = 0 }

        val mutableComponents = components().toMutableList()
        val accepted = accepted()
        val request = CaptureCommitRequest(accepted, mutableComponents, 10, digest("pose"), digest("camera"),
            digest("validation"), digest("ledger"))
        val equalRequest = commit(accepted, components())
        val requestHash = request.hashCode()
        mutableComponents.clear()
        assertEquals(2, request.components.size)
        assertEquals(equalRequest, request)
        assertEquals(requestHash, request.hashCode())
        assertFails { (request.components as MutableList<CaptureComponentDescriptor>).clear() }
        assertEquals(profile(), profile())
        assertEquals(profile().hashCode(), profile().hashCode())
    }

    @Test
    fun `scheduler gives manual running priority and promotes deterministically`() {
        val manual = accepted(CaptureLane.MANUAL, 1)
        val automatic = accepted(CaptureLane.AUTOMATIC, 2)
        val scheduler = CaptureFinalizerScheduler()
        val assignments = scheduler.scheduleReady(listOf(automatic, manual, automatic))
        assertEquals(listOf(CaptureFinalizerPosition.RUNNING, CaptureFinalizerPosition.FUNDED_WAITING, CaptureFinalizerPosition.REJECTED), assignments.map { it.position })
        assertEquals(manual, scheduler.running)
        assertEquals(automatic, scheduler.fundedWaiting)
        assertFalse(scheduler.ownsExposure(automatic.identity))
        assertEquals(0, CaptureAttemptReferenceMachine(automatic).exposureCount)
        assertEquals(automatic, scheduler.release(manual.identity))
        assertTrue(scheduler.ownsExposure(automatic.identity))
        assertEquals(null, scheduler.release(automatic.identity))
    }

    @Test
    fun `all 540 rows execute canonical query terminal and replay outcomes`() {
        val attempts = mapOf(CaptureLane.MANUAL to accepted(CaptureLane.MANUAL, 1), CaptureLane.AUTOMATIC to accepted(CaptureLane.AUTOMATIC, 2))
        val counts = mutableMapOf<CaptureMatrixOutcome, Int>()
        val lifecycleEffects = mutableMapOf<CaptureLifecycleEvent, MutableSet<CaptureLifecycleOwnershipEffect>>()
        CaptureFaultLifecycleMatrix.generate().forEach { row ->
            val attempt = requireNotNull(attempts[row.lane])
            val execution = CaptureFaultLifecycleMatrix.execute(row, attempt, commit(attempt, components()))
            assertEquals(row.expectedOutcome, execution.outcome)
            assertTrue(execution.exactReplay)
            assertTrue(execution.changedReplayConflict)
            lifecycleEffects.getOrPut(row.lifecycleEvent) { mutableSetOf() }.add(execution.ownershipEffect)
            assertEquals(row.fault == CaptureFault.LATE_CALLBACK, execution.lateCallbackNoOp)
            assertEquals(if (row.phase == CaptureAttemptPhase.RESERVED_ACCEPTED &&
                execution.outcome != CaptureMatrixOutcome.COMMITTED) 0 else 1, execution.exposureCount)
            assertEquals(row.phase.ordinal >= CaptureAttemptPhase.SENSOR_OUTPUT_OWNED.ordinal,
                execution.sensorOutputOwnedAtCut)
            val activeOwnerContinues = row.lifecycleEvent == CaptureLifecycleEvent.AUTOMATIC_DISABLED &&
                row.fault == CaptureFault.LATE_CALLBACK
            assertEquals(activeOwnerContinues && row.phase == CaptureAttemptPhase.RESERVED_ACCEPTED,
                execution.continuedExposureAfterCut)
            assertEquals(activeOwnerContinues && row.phase.ordinal < CaptureAttemptPhase.SENSOR_OUTPUT_OWNED.ordinal,
                execution.sensorOutputAcquiredAfterCut)
            assertTrue(execution.classificationReason.contains(execution.ownershipEffect.name))
            counts[execution.outcome] = (counts[execution.outcome] ?: 0) + 1
        }
        assertEquals(mapOf(CaptureMatrixOutcome.ABANDONED to 378, CaptureMatrixOutcome.OUTCOME_UNKNOWN to 90,
            CaptureMatrixOutcome.COMMITTED to 72), counts)
        assertEquals(mapOf(
            CaptureLifecycleEvent.AUTOMATIC_DISABLED to setOf(CaptureLifecycleOwnershipEffect.AUTOMATIC_ACTIVE_OWNER_CONTINUES),
            CaptureLifecycleEvent.ROUTE_LEFT to setOf(CaptureLifecycleOwnershipEffect.ROUTE_PRE_OUTPUT_CLASSIFIED,
                CaptureLifecycleOwnershipEffect.ROUTE_OWNED_OUTPUT_TRANSFERRED, CaptureLifecycleOwnershipEffect.ROUTE_DURABLE_STORE_OWNED),
            CaptureLifecycleEvent.BACKGROUNDED to setOf(CaptureLifecycleOwnershipEffect.BACKGROUND_PRE_OUTPUT_CLASSIFIED,
                CaptureLifecycleOwnershipEffect.BACKGROUND_OWNED_OUTPUT_FINISHING, CaptureLifecycleOwnershipEffect.BACKGROUND_DURABLE_STORE_OWNED),
            CaptureLifecycleEvent.VIEW_REPLACED to setOf(CaptureLifecycleOwnershipEffect.VIEW_PRE_OUTPUT_FENCED,
                CaptureLifecycleOwnershipEffect.VIEW_OWNED_OUTPUT_TRANSFERRED, CaptureLifecycleOwnershipEffect.VIEW_DURABLE_STORE_OWNED),
            CaptureLifecycleEvent.AR_SESSION_REPLACED to setOf(CaptureLifecycleOwnershipEffect.AR_PRE_OUTPUT_GROUP_FENCED,
                CaptureLifecycleOwnershipEffect.AR_OWNED_OUTPUT_FROZEN_GROUP, CaptureLifecycleOwnershipEffect.AR_DURABLE_OLD_GROUP_STORE_OWNED),
            CaptureLifecycleEvent.PROCESS_RESTARTED to setOf(CaptureLifecycleOwnershipEffect.PROCESS_PRE_DURABLE_ABSENT,
                CaptureLifecycleOwnershipEffect.PROCESS_DURABLE_RECEIPT_RECOVERED),
        ), lifecycleEffects)

        val attempt = requireNotNull(attempts[CaptureLane.MANUAL])
        val committed = CaptureFaultLifecycleCase(CaptureLane.MANUAL, CaptureAttemptPhase.DURABLE_PREPARED,
            CaptureFault.TIMEOUT, CaptureLifecycleEvent.BACKGROUNDED, CaptureMatrixOutcome.COMMITTED)
        val mutated = committed.copy(fault = CaptureFault.COMPONENT_FAILURE, expectedOutcome = CaptureMatrixOutcome.ABANDONED)
        assertFalse(CaptureFaultLifecycleMatrix.execute(committed, attempt, commit(attempt, components())).outcome ==
            CaptureFaultLifecycleMatrix.execute(mutated, attempt, commit(attempt, components())).outcome)

        val background = CaptureFaultLifecycleCase(CaptureLane.MANUAL, CaptureAttemptPhase.SENSOR_OUTPUT_OWNED,
            CaptureFault.LATE_CALLBACK, CaptureLifecycleEvent.BACKGROUNDED, CaptureMatrixOutcome.COMMITTED)
        val restarted = background.copy(lifecycleEvent = CaptureLifecycleEvent.PROCESS_RESTARTED,
            expectedOutcome = CaptureMatrixOutcome.ABANDONED)
        val backgroundExecution = CaptureFaultLifecycleMatrix.execute(background, attempt, commit(attempt, components()))
        val restartExecution = CaptureFaultLifecycleMatrix.execute(restarted, attempt, commit(attempt, components()))
        assertEquals(CaptureMatrixOutcome.COMMITTED, backgroundExecution.outcome)
        assertEquals(CaptureMatrixOutcome.ABANDONED, restartExecution.outcome)
        assertFalse(backgroundExecution.ownershipEffect == restartExecution.ownershipEffect)

        fun classify(event: CaptureLifecycleEvent, phase: CaptureAttemptPhase): CaptureMatrixExecution =
            CaptureFaultLifecycleMatrix.execute(CaptureFaultLifecycleCase(CaptureLane.MANUAL, phase,
                CaptureFault.LATE_CALLBACK, event, CaptureMatrixOutcome.ABANDONED), attempt, commit(attempt, components()))

        val automaticPre = classify(CaptureLifecycleEvent.AUTOMATIC_DISABLED, CaptureAttemptPhase.RESERVED_ACCEPTED)
        val routePre = classify(CaptureLifecycleEvent.ROUTE_LEFT, CaptureAttemptPhase.RESERVED_ACCEPTED)
        assertEquals(CaptureMatrixOutcome.COMMITTED, automaticPre.outcome)
        assertEquals(1, automaticPre.exposureCount)
        assertTrue(automaticPre.continuedExposureAfterCut)
        assertTrue(automaticPre.sensorOutputAcquiredAfterCut)
        assertEquals(CaptureMatrixOutcome.ABANDONED, routePre.outcome)
        assertEquals(0, routePre.exposureCount)
        assertFalse(routePre.sensorOutputAcquiredAfterCut)

        listOf(CaptureLifecycleEvent.ROUTE_LEFT, CaptureLifecycleEvent.BACKGROUNDED,
            CaptureLifecycleEvent.VIEW_REPLACED, CaptureLifecycleEvent.AR_SESSION_REPLACED).forEach { event ->
            val requested = classify(event, CaptureAttemptPhase.EXPOSURE_REQUESTED)
            assertEquals(CaptureMatrixOutcome.ABANDONED, requested.outcome)
            assertEquals(1, requested.exposureCount)
            assertFalse(requested.sensorOutputOwnedAtCut)
            assertFalse(requested.sensorOutputAcquiredAfterCut)
            assertTrue(requested.exactReplay)
            assertTrue(requested.changedReplayConflict)
        }

        val backgroundTimeout = CaptureFaultLifecycleMatrix.execute(CaptureFaultLifecycleCase(CaptureLane.MANUAL,
            CaptureAttemptPhase.EXPOSURE_REQUESTED, CaptureFault.TIMEOUT, CaptureLifecycleEvent.BACKGROUNDED,
            CaptureMatrixOutcome.OUTCOME_UNKNOWN), attempt, commit(attempt, components()))
        assertEquals(CaptureMatrixOutcome.OUTCOME_UNKNOWN, backgroundTimeout.outcome)
        assertEquals(CaptureAttemptPhase.EXPOSURE_REQUESTED, backgroundTimeout.phase)
        assertTrue(backgroundTimeout.classificationReason.contains("query-same-identity"))
        assertFalse(backgroundTimeout.sensorOutputAcquiredAfterCut)

        val viewOwned = classify(CaptureLifecycleEvent.VIEW_REPLACED, CaptureAttemptPhase.SENSOR_OUTPUT_OWNED)
        val arOwned = classify(CaptureLifecycleEvent.AR_SESSION_REPLACED, CaptureAttemptPhase.SENSOR_OUTPUT_OWNED)
        assertEquals(CaptureLifecycleOwnershipEffect.VIEW_OWNED_OUTPUT_TRANSFERRED, viewOwned.ownershipEffect)
        assertEquals(CaptureLifecycleOwnershipEffect.AR_OWNED_OUTPUT_FROZEN_GROUP, arOwned.ownershipEffect)
        assertFalse(viewOwned.classificationReason == arOwned.classificationReason)
        CaptureLifecycleEvent.entries.forEach { event ->
            val durable = classify(event, CaptureAttemptPhase.DURABLE_PREPARED)
            assertEquals(CaptureMatrixOutcome.COMMITTED, durable.outcome)
            assertTrue(durable.sensorOutputOwnedAtCut)
            assertFalse(durable.sensorOutputAcquiredAfterCut)
        }
    }

    private fun accepted(storeBytes: Long = 200) = accepted(CaptureLane.MANUAL, 1, storeBytes)

    private fun accepted(lane: CaptureLane, ordinal: Long, storeBytes: Long = 200) = CaptureAcceptedAttempt(
        identity = CaptureAttemptIdentity("attempt-$ordinal", "commit-$ordinal", ordinal, lifecycle()),
        lane = lane,
        profile = profile(),
        reservation = CaptureReservationLiability(1024, storeBytes, 2, 1, 0, true),
        canonicalIntentHash = digest("intent"),
        acceptedReceiptHash = digest("accepted"),
    )

    private fun lifecycle() = CaptureLifecycleCut("session", 1, "group", 1, "ar", "view", 1, "binding", 1, 1)

    private fun profile() = CaptureComponentProfile("raw+jpeg", setOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG), 200, 1024)

    private fun components(bytes: Long = 40) = listOf(
        CaptureComponentDescriptor(CaptureComponentKind.JPEG, bytes, digest("jpeg"), "jpeg-object"),
        CaptureComponentDescriptor(CaptureComponentKind.DNG, bytes, digest("dng"), "dng-object"),
    )

    private fun commit(accepted: CaptureAcceptedAttempt, components: List<CaptureComponentDescriptor>) =
        CaptureCommitRequest(accepted, components, 10, digest("pose"), digest("camera"), digest("validation"), digest("ledger"))

    private fun digest(value: String) = List(32) { index -> value[index % value.length].code xor index }

    private fun assertFails(block: () -> Unit) {
        try { block(); throw AssertionError("expected failure") } catch (_: RuntimeException) { }
    }
}
