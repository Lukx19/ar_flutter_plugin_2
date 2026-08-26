package com.uhg0.ar_flutter_plugin_2.capture

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
            assertTrue(store.terminals.isNotEmpty())
            val late = CloseTrackingInputStream(byteArrayOf(1))
            camera.callback!!.onComponents(SharedCameraComponentSetV2(qualifier, listOf(
                CaptureComponentStreamV2(CaptureComponentKind.JPEG, late),
            )))
            assertTrue(late.closed)
            assertEquals(1L, adapter.snapshot().lateCallbacks)
        }
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
        val accepted = mutableListOf<CaptureAcceptedAttempt>()
        val terminals = mutableListOf<CaptureReceipt>()
        val queries = mutableListOf<CaptureAttemptIdentity>()
        override fun acceptBeforeExposure(attempt: CaptureAcceptedAttempt): CaptureReceipt {
            accepted += attempt
            return CaptureReceipt(attempt.identity, CaptureAttemptPhase.RESERVED_ACCEPTED, "accepted", "accepted", true)
        }
        override fun commitStreamed(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>): CaptureReceipt {
            streams.forEach { it.input.use { source -> while (source.read() >= 0) {} } }
            val terminal = CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, request.accepted.identity, "commit", "committed", "capture", 1, "root")
            return CaptureReceipt(request.accepted.identity, CaptureAttemptPhase.COMMITTED_PICTURE, "commit", "receipt", true, terminal).also(terminals::add)
        }
        override fun queryReceipt(identity: CaptureAttemptIdentity): CaptureReceipt? { queries += identity; return terminals.firstOrNull { it.identity == identity } }
        override fun rebaseSameAttempt(request: CaptureCommitRequest): CaptureReceipt = throw IllegalStateException("not prepared")
        override fun abandon(terminal: CaptureTerminal): CaptureReceipt =
            CaptureReceipt(terminal.identity, CaptureAttemptPhase.ABANDONED_ATTEMPT, terminal.canonicalTerminalHash, "abandoned", true, terminal).also(terminals::add)
    }

    private class CloseTrackingInputStream(private val values: ByteArray) : InputStream() {
        private var index = 0
        var closed = false
            private set
        override fun read(): Int = if (index == values.size) -1 else values[index++].toInt()
        override fun close() { closed = true }
    }

    private companion object {
        fun bytes(kind: CaptureComponentKind) = "component-$kind".toByteArray()
        fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map { it.toInt() and 0xff }
        fun digest(value: String) = sha(value.toByteArray())
    }
}
