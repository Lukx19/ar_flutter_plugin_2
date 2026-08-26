package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureIntentContractTest {
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
        assertTrue(matrix.all { it.expectedTerminal == CaptureTerminalKind.ABANDONED_ATTEMPT })
    }

    @Test
    fun `transition table rejects every noncanonical edge`() {
        val pairs = CaptureAttemptPhase.entries.flatMap { from ->
            CaptureAttemptPhase.entries.map { destination -> Pair(from, destination) }
        }
        assertEquals(10, pairs.count { CaptureAttemptTransitionTable.isLegal(it.first, it.second) })
        assertEquals(39, pairs.count { !CaptureAttemptTransitionTable.isLegal(it.first, it.second) })
    }

    private fun accepted(storeBytes: Long = 200) = CaptureAcceptedAttempt(
        identity = CaptureAttemptIdentity("attempt-1", "commit-1", 1, lifecycle()),
        lane = CaptureLane.MANUAL,
        profile = CaptureComponentProfile("raw+jpeg", setOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG), 200, 1024),
        reservation = CaptureReservationLiability(1024, storeBytes, 2, 1, 0, true),
        canonicalIntentHash = "intent",
        acceptedReceiptHash = "accepted",
    )

    private fun lifecycle() = CaptureLifecycleCut("session", 1, "group", 1, "ar", "view", 1, "binding", 1, 1)

    private fun components(bytes: Long = 40) = listOf(
        CaptureComponentDescriptor(CaptureComponentKind.JPEG, bytes, "jpeg", "jpeg-object"),
        CaptureComponentDescriptor(CaptureComponentKind.DNG, bytes, "dng", "dng-object"),
    )

    private fun commit(accepted: CaptureAcceptedAttempt, components: List<CaptureComponentDescriptor>) =
        CaptureCommitRequest(accepted, components, 10, "pose", "camera", "validation", "ledger")
}
