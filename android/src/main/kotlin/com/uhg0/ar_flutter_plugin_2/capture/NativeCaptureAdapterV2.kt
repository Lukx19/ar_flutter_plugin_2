package com.uhg0.ar_flutter_plugin_2.capture

import android.content.Context
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityCaptureSafePredicate
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The native-only #101 store boundary.  Its component inputs are transferred
 * directly from Camera2 to the V2 store and are never materialized as a ready
 * picture, a legacy cache entry, or a platform-channel value.
 */
internal interface NativeCaptureStorePortV2 {
    fun replayFenceBeforeExposure(request: CaptureCommitRequest): CaptureReceipt?
    fun acceptBeforeExposure(request: CaptureCommitRequest): CaptureReceipt
    fun commitStreamed(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>): CaptureReceipt
    fun queryReceipt(identity: CaptureAttemptIdentity): CaptureReceipt?
    fun rebaseSameAttempt(request: CaptureCommitRequest): CaptureReceipt
    fun abandon(request: CaptureCommitRequest, terminal: CaptureTerminal): CaptureReceipt
}

internal class DurableNativeCaptureStorePortV2(
    private val store: DurableSessionStoreV2,
) : NativeCaptureStorePortV2 {
    override fun replayFenceBeforeExposure(request: CaptureCommitRequest) = store.replayFenceBeforeExposure(request)
    override fun acceptBeforeExposure(request: CaptureCommitRequest) = store.acceptCaptureBeforeExposure(request)
    override fun commitStreamed(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>) =
        store.commitStreamed(request, streams)
    override fun queryReceipt(identity: CaptureAttemptIdentity) = store.queryReceipt(identity)
    override fun rebaseSameAttempt(request: CaptureCommitRequest) = store.rebaseSameAttempt(request)
    override fun abandon(request: CaptureCommitRequest, terminal: CaptureTerminal) = store.abandonCapture(request, terminal)
}

/** Full immutable callback qualifier; timestamp or generation alone is never sufficient. */
internal data class CaptureAttemptQualifierV2(
    val identity: CaptureAttemptIdentity,
    val lifecycleCut: CaptureLifecycleCut,
    val exposureGeneration: Long,
) {
    init {
        require(exposureGeneration > 0)
        require(identity.lifecycleCut == lifecycleCut)
    }
}

/** A whole exact component set is handed off once.  Implementations must close every input on rejection. */
internal data class SharedCameraComponentSetV2(
    val qualifier: CaptureAttemptQualifierV2,
    val streams: List<CaptureComponentStreamV2>,
)

internal interface SharedCameraExposureCallbackV2 {
    fun onComponents(components: SharedCameraComponentSetV2)
    fun onFailure(qualifier: CaptureAttemptQualifierV2, reason: String)
}

/**
 * Fakeable, attempt-qualified Camera2 seam.  The existing V1 SharedCamera
 * cache/correlation APIs deliberately do not implement this interface.
 */
internal interface SharedCameraExposurePortV2 {
    fun requestExposure(
        qualifier: CaptureAttemptQualifierV2,
        required: Set<CaptureComponentKind>,
        callback: SharedCameraExposureCallbackV2,
    ): Boolean

    fun cancelExposure(qualifier: CaptureAttemptQualifierV2)
}

/**
 * Adapter shape for the eventual SharedCameraManager Camera2 callback hook.
 * It deliberately accepts only a qualified request and a component-set
 * callback, so the legacy manager cannot accidentally feed its V1 cache into
 * V2.  ArCaptureSession keeps this bridge unbound until #102 enables a product
 * caller and supplies the real Camera2 hook.
 */
internal class SharedCameraManagerExposurePortV2(
    private val request: (
        CaptureAttemptQualifierV2,
        Set<CaptureComponentKind>,
        SharedCameraExposureCallbackV2,
    ) -> Boolean,
    private val cancel: (CaptureAttemptQualifierV2) -> Unit,
) : SharedCameraExposurePortV2 {
    override fun requestExposure(qualifier: CaptureAttemptQualifierV2, required: Set<CaptureComponentKind>, callback: SharedCameraExposureCallbackV2) =
        request(qualifier, required, callback)
    override fun cancelExposure(qualifier: CaptureAttemptQualifierV2) = cancel(qualifier)
}

/** Per-view native owner. #102 may supply intent, but not this binding or its lifecycle. */
internal class NativeCaptureBindingV2(
    context: Context,
    private val safety: CaptureSafetySignalV2,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val lock = Any()
    private val root = createNativeCaptureRootV2(context)
    private val budget = StorageBudgetCoordinatorV2(
        java.io.File(root, "budget"),
        StorageBudgetPolicyV2((context.filesDir.usableSpace / 2).coerceAtLeast(CAPTURE_COEXISTENCE_BYTES), 0),
    )
    private val store = DurableSessionStoreV2(java.io.File(root, "store"), budget)
    private var sharedCamera: SharedCameraManager? = null
    private var closed = false
    private val bridge = SharedCameraManagerExposurePortV2(
        request = { qualifier, required, callback ->
            synchronized(lock) { sharedCamera?.requestAttemptQualifiedExposureV2(qualifier, required, callback) ?: false }
        },
        cancel = { qualifier -> synchronized(lock) { sharedCamera?.cancelAttemptQualifiedExposureV2(qualifier) } },
    )
    private val adapter = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(store), bridge, safety, nowMs)

    fun attachSharedCamera(manager: SharedCameraManager) = synchronized(lock) {
        check(!closed) { "NativeCaptureBindingV2 is closed" }
        sharedCamera = manager
        // The bridge is installed on the production manager now. Until #102
        // supplies an admitted intent, no request is made; a request without a
        // native Camera2 component hook fails closed instead of falling back to V1.
        manager.installAttemptQualifiedExposureHookV2(request = { _, _, _ -> false })
    }
    fun detachSharedCamera(manager: SharedCameraManager) = synchronized(lock) { if (sharedCamera === manager) sharedCamera = null }
    fun admit(request: CaptureCommitRequest): CaptureReceipt = adapter.admit(request)
    fun onLifecycle(event: CaptureLifecycleEvent, cut: CaptureLifecycleCut) = adapter.onLifecycle(event, cut)
    fun onPause() = adapter.onLifecycle(CaptureLifecycleEvent.BACKGROUNDED)
    fun onViewReplacement() = adapter.onLifecycle(CaptureLifecycleEvent.VIEW_REPLACED)
    fun onArSessionReplacement() = adapter.onLifecycle(CaptureLifecycleEvent.AR_SESSION_REPLACED)
    fun snapshot() = adapter.snapshot()

    /** Native instrumentation seam; it is never registered with a Flutter channel. */
    internal fun syntheticAdapterForTest(): NativeCaptureAdapterV2 = adapter

    internal fun installSyntheticExposureHookForTest(
        request: (CaptureAttemptQualifierV2, Set<CaptureComponentKind>, SharedCameraExposureCallbackV2) -> Boolean,
        cancel: (CaptureAttemptQualifierV2) -> Unit = {},
    ) = synchronized(lock) { sharedCamera?.installAttemptQualifiedExposureHookV2(request, cancel) }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        adapter.onLifecycle(CaptureLifecycleEvent.VIEW_REPLACED)
        adapter.close()
        sharedCamera = null
        store.close()
        budget.close()
    }

    private fun createNativeCaptureRootV2(context: Context): java.io.File {
        val root = java.io.File(context.filesDir, "capture-v2-native")
        AndroidDescriptorFilesystemV2().use { unbound ->
            unbound.bind(root).use { }
        }
        return root
    }
}

/** Bounded scalar observability only; no component lengths, bytes, or paths escape this owner. */
internal data class CaptureResourceSnapshotV2(
    val exposures: Long,
    val lateCallbacks: Long,
    val closedComponents: Long,
    val committed: Long,
    val abandoned: Long,
    val unknownQueries: Long,
    val running: Int,
    val fundedWaiting: Int,
)

internal class CaptureResourceCountersV2 {
    private val exposures = AtomicLong()
    private val lateCallbacks = AtomicLong()
    private val closedComponents = AtomicLong()
    private val committed = AtomicLong()
    private val abandoned = AtomicLong()
    private val unknownQueries = AtomicLong()
    fun exposure() = exposures.incrementAndGet()
    fun lateCallback() = lateCallbacks.incrementAndGet()
    fun componentsClosed(count: Int) = closedComponents.addAndGet(count.toLong())
    fun committed() = committed.incrementAndGet()
    fun abandoned() = abandoned.incrementAndGet()
    fun unknownQuery() = unknownQueries.incrementAndGet()
    fun snapshot(running: Int, waiting: Int) = CaptureResourceSnapshotV2(
        exposures.get(), lateCallbacks.get(), closedComponents.get(), committed.get(), abandoned.get(),
        unknownQueries.get(), running, waiting,
    )
}

/**
 * Exact-cut signal consumed by M2.  It is false until a V2 owner has both a
 * durable accepted receipt and the matching live per-view lifecycle cut.
 */
internal class CaptureSafetySignalV2 : VisibilityCaptureSafePredicate {
    private val lock = Any()
    private var binding: CaptureAttemptQualifierV2? = null
    private var durableAccepted = false
    private var ownerLive = false

    fun bind(qualifier: CaptureAttemptQualifierV2, receipt: CaptureReceipt) = synchronized(lock) {
        binding = qualifier
        durableAccepted = receipt.durable && receipt.phase == CaptureAttemptPhase.RESERVED_ACCEPTED
        ownerLive = durableAccepted
    }

    fun release(qualifier: CaptureAttemptQualifierV2) = synchronized(lock) {
        if (binding == qualifier) { ownerLive = false; durableAccepted = false; binding = null }
    }

    fun invalidateLifecycle(cut: CaptureLifecycleCut) = synchronized(lock) {
        if (binding?.lifecycleCut == cut) { ownerLive = false; durableAccepted = false; binding = null }
    }

    /** View pause/dispose has no trusted current V2 cut, so it must fail closed for every binding. */
    fun invalidateLifecycleForViewPause() = invalidateAll()
    fun invalidateAll() = synchronized(lock) { ownerLive = false; durableAccepted = false; binding = null }

    override fun isCaptureSafe(): Boolean = synchronized(lock) {
        // A safety proof is an exact V2 owner/store/cut binding, never a Dart policy flag.
        binding != null && durableAccepted && ownerLive
    }
}

/**
 * One running finalizer and one funded waiting finalizer.  Waiting work never
 * requests Camera2.  A manual owner gets ready-slot priority but cannot
 * preempt an already requested exposure.
 */
internal class NativeCaptureAdapterV2(
    private val store: NativeCaptureStorePortV2,
    private val exposure: SharedCameraExposurePortV2,
    private val safety: CaptureSafetySignalV2 = CaptureSafetySignalV2(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private data class Work(
        val request: CaptureCommitRequest,
        val acceptedReceipt: CaptureReceipt,
        val acceptedAtMs: Long,
        val qualifier: CaptureAttemptQualifierV2,
    )
    private val lock = Any()
    private val scheduler = CaptureFinalizerScheduler()
    private val counters = CaptureResourceCountersV2()
    private val work = linkedMapOf<CaptureAttemptIdentity, Work>()
    private var closed = false
    private var lifecycleDrain = false
    private var nextExposureGeneration = 0L

    fun admit(request: CaptureCommitRequest): CaptureReceipt = synchronized(lock) {
        check(!closed) { "NativeCaptureAdapterV2 is closed" }
        val accepted = request.accepted

        work[accepted.identity]?.let { active ->
            if (active.request == request) return@synchronized active.acceptedReceipt
            throw DurableStoreConflictV2("Changed replay conflicts with active capture identity")
        }
        store.replayFenceBeforeExposure(request)?.let { replay ->
            if (replay.terminal != null) return@synchronized replay
            // A durable accepted identity from a prior owner is outcome-unknown.
            // Fence it metadata-only; never infer that a second shutter is safe.
            return@synchronized abandonLocked(request, "durable-replay-no-reexposure")
        }

        // Capacity and protected-manual priority are settled before durable
        // acceptance. A waiting automatic owner is optional and yields to a
        // manual owner; an active exposure is never preempted.
        if (scheduler.running != null && scheduler.fundedWaiting != null) {
            val waiting = checkNotNull(scheduler.fundedWaiting)
            if (accepted.lane == CaptureLane.MANUAL && waiting.lane == CaptureLane.AUTOMATIC) {
                abandonLocked(checkNotNull(work[waiting.identity]).request, "manual-priority-evicted-automatic")
            } else {
                throw IllegalStateException("finalizer-capacity")
            }
        }
        val assignment = scheduler.scheduleReady(listOf(accepted)).single()
        check(assignment.position != CaptureFinalizerPosition.REJECTED) { "finalizer-capacity" }

        val receipt = try {
            store.acceptBeforeExposure(request)
        } catch (error: Throwable) {
            scheduler.release(accepted.identity)
            scheduler.running?.let { next -> work[next.identity]?.let(::startLocked) }
            throw error
        }
        if (receipt.terminal != null) {
            scheduler.release(accepted.identity)
            scheduler.running?.let { next -> work[next.identity]?.let(::startLocked) }
            return@synchronized receipt
        }
        val qualifier = CaptureAttemptQualifierV2(accepted.identity, accepted.identity.lifecycleCut, ++nextExposureGeneration)
        work[accepted.identity] = Work(request, receipt, nowMs(), qualifier)
        if (assignment.position == CaptureFinalizerPosition.RUNNING) startLocked(work.getValue(accepted.identity))
        receipt
    }

    fun onLifecycle(event: CaptureLifecycleEvent, cut: CaptureLifecycleCut) = synchronized(lock) {
        drainLifecycleLocked(event, setOf(cut))
    }

    fun onLifecycle(event: CaptureLifecycleEvent) = synchronized(lock) {
        drainLifecycleLocked(event, work.values.map { it.qualifier.lifecycleCut }.toSet())
    }

    private fun drainLifecycleLocked(event: CaptureLifecycleEvent, cuts: Set<CaptureLifecycleCut>) {
        val runningBefore = scheduler.running?.identity
        lifecycleDrain = true
        try {
            // Event-specific C18 handling. Waiting owners are classified while
            // promotion is suppressed, so teardown can never create exposure.
            work.values.filter { it.qualifier.lifecycleCut in cuts }.toList().forEach { value ->
                when (event) {
                    CaptureLifecycleEvent.AUTOMATIC_DISABLED -> if (
                        value.request.accepted.lane == CaptureLane.AUTOMATIC &&
                        scheduler.running?.identity != value.request.accepted.identity
                    ) abandonLocked(value.request, "automatic-disabled-waiting")
                    CaptureLifecycleEvent.ROUTE_LEFT -> abandonLocked(value.request, "route-left")
                    CaptureLifecycleEvent.VIEW_REPLACED -> abandonLocked(value.request, "view-replaced")
                    CaptureLifecycleEvent.AR_SESSION_REPLACED -> abandonLocked(value.request, "ar-session-replaced")
                    CaptureLifecycleEvent.BACKGROUNDED -> abandonLocked(value.request, "backgrounded")
                    CaptureLifecycleEvent.PROCESS_RESTARTED -> queryOrAbandonLocked(value, "process-restarted")
                }
            }
        } finally {
            lifecycleDrain = false
        }
        if (event != CaptureLifecycleEvent.AUTOMATIC_DISABLED) {
            cuts.forEach(safety::invalidateLifecycle)
        }
        val runningAfter = scheduler.running?.identity
        if (runningAfter != null && runningAfter != runningBefore) {
            scheduler.running?.let { next -> work[next.identity]?.let(::startLocked) }
        }
    }

    /** 10 s stops stalled automatic ownership; 30 s queries/rebases exactly once without a shutter retry. */
    fun advanceDeadlines() = synchronized(lock) {
        val now = nowMs()
        work.values.toList().forEach { value ->
            val elapsed = now - value.acceptedAtMs
            if (value.request.accepted.lane == CaptureLane.AUTOMATIC && elapsed >= AUTOMATIC_STALL_MS) {
                abandonLocked(value.request, "automatic-stall-10s")
            } else if (elapsed >= TERMINAL_FENCE_MS) {
                queryOrAbandonLocked(value, "terminal-fence-30s")
            }
        }
    }

    fun snapshot(): CaptureResourceSnapshotV2 = synchronized(lock) {
        counters.snapshot(if (scheduler.running == null) 0 else 1, if (scheduler.fundedWaiting == null) 0 else 1)
    }

    internal fun waitingIdentityForTest(): CaptureAttemptIdentity? = synchronized(lock) {
        scheduler.fundedWaiting?.identity
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        work.values.toList().forEach { queryOrAbandonLocked(it, "shutdown") }
    }

    private fun startLocked(value: Work) {
        if (closed || scheduler.running?.identity != value.request.accepted.identity) return
        // Bind before submission: a fake or Camera2 bridge may complete on the
        // same stack. Binding afterwards would resurrect a stale true signal.
        safety.bind(value.qualifier, value.acceptedReceipt)
        val requested = try {
            exposure.requestExposure(value.qualifier, value.request.accepted.profile.requiredComponents, callback)
        } catch (_: Throwable) {
            safety.release(value.qualifier)
            false
        }
        // A legal fake/Camera2 bridge may have completed synchronously.
        if (work[value.request.accepted.identity] !== value) {
            if (requested) counters.exposure()
            return
        }
        if (!requested) {
            safety.release(value.qualifier)
            abandonLocked(value.request, "exposure-request-rejected")
            return
        }
        counters.exposure()
    }

    private val callback = object : SharedCameraExposureCallbackV2 {
        override fun onComponents(components: SharedCameraComponentSetV2) = synchronized(lock) {
            val value = work[components.qualifier.identity]
            if (value == null || value.qualifier != components.qualifier || scheduler.running?.identity != components.qualifier.identity || closed) {
                closeStreams(components.streams); counters.lateCallback(); return@synchronized
            }
            val kinds = components.streams.map { it.kind }
            if (kinds.size != kinds.toSet().size || kinds.toSet() != value.request.accepted.profile.requiredComponents) {
                closeStreams(components.streams); abandonLocked(value.request, "malformed-component-set"); return@synchronized
            }
            val owned = components.streams.map { stream ->
                CaptureComponentStreamV2(stream.kind, CloseOnceInputStreamV2(stream.input))
            }
            try {
                val receipt = store.commitStreamed(value.request, owned)
                finishLocked(value, receipt)
            } catch (_: Throwable) {
                // Store errors after a streaming handoff are an exact identity query, never another request.
                queryOrAbandonLocked(value, "store-unknown")
            } finally {
                owned.forEach { runCatching { it.input.close() } }
                counters.componentsClosed(owned.size)
            }
        }

        override fun onFailure(qualifier: CaptureAttemptQualifierV2, reason: String) = synchronized(lock) {
            val value = work[qualifier.identity]
            if (value == null || value.qualifier != qualifier || closed) { counters.lateCallback(); return@synchronized }
            abandonLocked(value.request, "camera-$reason")
        }
    }

    private fun queryOrAbandonLocked(value: Work, reason: String) {
        counters.unknownQuery()
        val receipt = store.queryReceipt(value.request.accepted.identity)
        if (receipt?.terminal != null) { finishLocked(value, receipt); return }
        // Rebase only a durable prepared/committed identity; the port rejects changed bytes.
        runCatching { store.rebaseSameAttempt(value.request) }.getOrNull()?.let { finishLocked(value, it); return }
        abandonLocked(value.request, reason)
    }

    private fun abandonLocked(request: CaptureCommitRequest, reason: String): CaptureReceipt {
        val accepted = request.accepted
        val terminal = CaptureTerminal(CaptureTerminalKind.ABANDONED_ATTEMPT, accepted.identity, reason, reason)
        val receipt = store.abandon(request, terminal)
        finishLocked(work[accepted.identity], receipt)
        return receipt
    }

    private fun finishLocked(value: Work?, receipt: CaptureReceipt) {
        val identity = receipt.identity
        value?.let { exposure.cancelExposure(it.qualifier); safety.release(it.qualifier) }
        work.remove(identity)
        scheduler.release(identity)
        if (receipt.terminal?.kind == CaptureTerminalKind.COMMITTED_PICTURE) counters.committed() else counters.abandoned()
        if (!lifecycleDrain) scheduler.running?.let { next -> work[next.identity]?.let(::startLocked) }
    }

    private fun closeStreams(streams: List<CaptureComponentStreamV2>) {
        streams.forEach { runCatching { it.input.close() } }
        counters.componentsClosed(streams.size)
    }

    private companion object {
        const val AUTOMATIC_STALL_MS = 10_000L
        const val TERMINAL_FENCE_MS = 30_000L
    }
}

/** Idempotent adapter-owned wrapper; the underlying Camera2 input closes once. */
internal class CloseOnceInputStreamV2(private val delegate: InputStream) : InputStream() {
    private val closed = AtomicBoolean(false)
    override fun read(): Int = delegate.read()
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
    override fun skip(count: Long): Long = delegate.skip(count)
    override fun available(): Int = delegate.available()
    override fun close() {
        if (closed.compareAndSet(false, true)) delegate.close()
    }
}
