package com.uhg0.ar_flutter_plugin_2.capture

import android.content.Context
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityCaptureSafePredicate
import java.io.InputStream
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The native-only #101 store boundary.  Its component inputs are transferred
 * directly from Camera2 to the V2 store and are never materialized as a ready
 * picture, a legacy cache entry, or a platform-channel value.
 */
internal interface NativeCaptureStorePortV2 {
    fun replayFenceBeforeExposure(request: CaptureCommitRequest): CaptureReceipt?
    fun acceptBeforeExposure(request: CaptureCommitRequest): CaptureReceipt
    fun commitStreamed(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>): CaptureReceipt
    fun commitStreamed(
        request: CaptureCommitRequest,
        exposureTimestampNanoseconds: Long,
        streams: List<CaptureComponentStreamV2>,
    ): CaptureReceipt = commitStreamed(request, streams)
    fun queryReceipt(identity: CaptureAttemptIdentity): CaptureReceipt?
    fun rebaseSameAttempt(request: CaptureCommitRequest): CaptureReceipt
    fun abandon(request: CaptureCommitRequest, terminal: CaptureTerminal): CaptureReceipt
}
internal class DurableNativeCaptureStorePortV2(
    private val store: DurableSessionStoreV2,
) : NativeCaptureStorePortV2 {
    private fun template(request: CaptureCommitRequest) = CapturePostOutputTemplateV2(
        request.accepted,
        request.poseRecordHash,
        request.cameraModelHash,
        request.validationRecordHash,
        request.ledgerRecordHash,
    )
    override fun replayFenceBeforeExposure(request: CaptureCommitRequest) =
        if (request.components.isEmpty()) store.replayTemplateFenceBeforeExposure(template(request))
        else store.replayFenceBeforeExposure(request)
    override fun acceptBeforeExposure(request: CaptureCommitRequest) = store.acceptCaptureBeforeExposure(request)
    override fun commitStreamed(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>) =
        commitStreamed(request, request.exposureTimestampNanoseconds, streams)
    override fun commitStreamed(
        request: CaptureCommitRequest,
        exposureTimestampNanoseconds: Long,
        streams: List<CaptureComponentStreamV2>,
    ) = if (request.components.isEmpty()) {
        store.commitStreamedFinalized(
            template(request),
            exposureTimestampNanoseconds,
            streams,
        )
    } else {
        // Accepted #101 callers retain their descriptor-qualified regression
        // seam; production #102 admission cannot populate this collection.
        store.commitStreamed(request, streams)
    }
    override fun queryReceipt(identity: CaptureAttemptIdentity) = store.queryReceipt(identity)
    override fun rebaseSameAttempt(request: CaptureCommitRequest) = store.rebaseSameAttempt(request)
    override fun abandon(request: CaptureCommitRequest, terminal: CaptureTerminal) =
        if (request.components.isEmpty()) store.abandonTemplate(template(request), terminal)
        else store.abandonCapture(request, terminal)
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
    val exposureTimestampNanoseconds: Long = 0L,
)

internal enum class NativeCaptureEventKindV2 { ACCEPTED, FINALIZING, RECOVERING, RECOVERY_FAILED, READY, COMMITTED, ABANDONED, HEALTH }

/** Strict bounded event: scalar identities/counters only, never paths, descriptors, or bytes. */
internal data class NativeCaptureEventV2(
    val kind: NativeCaptureEventKindV2,
    val attemptId: String? = null,
    val captureId: String? = null,
    val captureRevision: Long? = null,
    val manifestId: String? = null,
    val reason: String? = null,
    val resources: CaptureResourceSnapshotV2? = null,
    val recoveryContext: NativeCaptureRecoveryContextV2? = null,
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

/** Off-main startup recovery with close fencing and recovery-before-live ordering. */
internal class NativeCaptureRecoveryDispatcherV2(
    private val recover: ((() -> Boolean) -> List<DurableSessionStoreV2.RecoveryProjectionV2>),
    private val events: (NativeCaptureEventV2) -> Unit,
    private val executor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "capture3d-v2-recovery").apply { isDaemon = true }
    },
) : AutoCloseable {
    private enum class State { INITIALIZING, READY, FAILED, CLOSED }

    private val lock = Any()
    private var state = State.INITIALIZING
    private val replay = LinkedHashMap<String, NativeCaptureEventV2>()

    init {
        executor.execute {
            val recovered = runCatching { recover(::isActive) }
            synchronized(lock) {
                if (state == State.CLOSED) return@execute
                recovered.fold(
                    onSuccess = { projections ->
                        state = State.READY
                        projections.map { it.toNativeEvent() }.forEach(::emitAndRetainLocked)
                        if (projections.isEmpty()) events(readyEvent())
                    },
                    onFailure = {
                        state = State.FAILED
                        emitAndRetainLocked(
                            NativeCaptureEventV2(
                                NativeCaptureEventKindV2.RECOVERY_FAILED,
                                reason = RECOVERY_FAILED_REASON,
                            ),
                        )
                    },
                )
            }
        }
    }

    fun isReady(): Boolean = synchronized(lock) { state == State.READY }

    fun requireAdmissionReady() = synchronized(lock) {
        when (state) {
            State.READY -> Unit
            State.INITIALIZING -> throw NativeCaptureRecoveryAdmissionExceptionV2(
                "NATIVE_CAPTURE_V2_RECOVERY_PENDING",
                "Native capture recovery is still running.",
            )
            State.FAILED -> throw NativeCaptureRecoveryAdmissionExceptionV2(
                "NATIVE_CAPTURE_V2_RECOVERY_FAILED",
                "Native capture recovery failed; recreate the capture view before admitting exposure.",
            )
            State.CLOSED -> throw NativeCaptureRecoveryAdmissionExceptionV2(
                "NATIVE_CAPTURE_V2_RECOVERY_CLOSED",
                "Native capture recovery is closed.",
            )
        }
    }

    fun emitLive(event: NativeCaptureEventV2) = synchronized(lock) {
        if (state != State.CLOSED) {
            requireAdmissionReady()
            emitAndRetainLocked(event)
        }
    }

    fun replaySnapshot(): List<NativeCaptureEventV2> = synchronized(lock) {
        when (state) {
            State.CLOSED, State.INITIALIZING -> emptyList()
            State.FAILED -> replay.values.toList()
            State.READY -> replay.values.toList().ifEmpty { listOf(readyEvent()) }
        }
    }

    fun acknowledgeTerminal(attemptId: String) = synchronized(lock) {
        require(attemptId.isNotEmpty())
        replay.remove(attemptId)
        if (state == State.READY && replay.isEmpty()) events(readyEvent())
    }

    private fun emitAndRetainLocked(event: NativeCaptureEventV2) {
        when {
            event.kind == NativeCaptureEventKindV2.RECOVERY_FAILED -> replay["recovery-failed"] = event
            event.kind == NativeCaptureEventKindV2.COMMITTED || event.kind == NativeCaptureEventKindV2.ABANDONED || event.kind == NativeCaptureEventKindV2.RECOVERING -> {
                val key = requireNotNull(event.attemptId)
                replay[key] = event
                while (replay.size > 2) replay.remove(replay.keys.first())
            }
        }
        events(event)
    }

    private fun readyEvent() = NativeCaptureEventV2(NativeCaptureEventKindV2.READY, reason = "native-owner-ready")

    private fun isActive(): Boolean = synchronized(lock) {
        state == State.INITIALIZING && !Thread.currentThread().isInterrupted
    }

    override fun close() = synchronized(lock) {
        if (state == State.CLOSED) return@synchronized
        state = State.CLOSED
        executor.shutdownNow()
    }

    internal fun awaitTerminationForTest(timeout: Long, unit: TimeUnit): Boolean =
        executor.awaitTermination(timeout, unit)

    private fun DurableSessionStoreV2.RecoveryProjectionV2.toNativeEvent() = NativeCaptureEventV2(
        kind = when (kind) {
            CaptureTerminalKind.COMMITTED_PICTURE -> NativeCaptureEventKindV2.COMMITTED
            CaptureTerminalKind.ABANDONED_ATTEMPT -> NativeCaptureEventKindV2.ABANDONED
            null -> NativeCaptureEventKindV2.RECOVERING
        },
        attemptId = attemptId,
        captureId = captureId,
        captureRevision = captureRevision,
        manifestId = manifestId,
        reason = reason,
        recoveryContext = recoveryContext,
    )

    private companion object {
        const val RECOVERY_FAILED_REASON = "durable-startup-recovery-failed"
    }
}

internal class NativeCaptureRecoveryAdmissionExceptionV2(
    val code: String,
    message: String,
) : IllegalStateException(message)

/** One-shot async preparation whose success or failure is replayed to every later disposer. */
internal class ReplayableShutdownPreparationV2 {
    private val lock = Any()
    private var started = false
    private var result: Result<Unit>? = null
    private val callbacks = mutableListOf<(Result<Unit>) -> Unit>()

    fun request(callback: (Result<Unit>) -> Unit): Boolean = synchronized(lock) {
        result?.let {
            callback(it)
            return@synchronized false
        }
        callbacks += callback
        if (started) return@synchronized false
        started = true
        true
    }

    fun complete(completed: Result<Unit>) {
        val pending = synchronized(lock) {
            if (result != null) return
            result = completed
            callbacks.toList().also { callbacks.clear() }
        }
        pending.forEach { it(completed) }
    }
}

/**
 * One bounded durable worker per platform view. Calls return after ownership of
 * the operation has transferred to the worker; completions are dispatched on
 * the supplied owner (Android's main looper in production). FIFO execution is
 * the lifecycle ordering fence between admission, lifecycle cuts, and close.
 */
internal class NativeCaptureSerialOwnerV2(
    private val completionExecutor: Executor,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "capture3d-v2-durable").apply { isDaemon = true }
    },
    private val deadlineWorker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "capture3d-v2-close-deadline").apply { isDaemon = true }
    },
) {
    private val lock = Any()
    private var closing = false

    fun <T> submit(operation: () -> T, completion: (Result<T>) -> Unit): Boolean =
        enqueue(operation, completion)

    fun close(
        timeoutMillis: Long,
        operation: () -> Unit,
        timeoutOperation: () -> Unit,
        completion: (Result<Unit>) -> Unit,
    ): Boolean =
        synchronized(lock) {
            if (closing) return@synchronized false
            closing = true
            val settled = AtomicBoolean(false)
            deadlineWorker.schedule(
                {
                    val result = runCatching(timeoutOperation)
                    if (settled.compareAndSet(false, true)) completionExecutor.execute { completion(result) }
                    deadlineWorker.shutdown()
                },
                timeoutMillis,
                TimeUnit.MILLISECONDS,
            )
            worker.execute {
                val operationResult = runCatching(operation)
                val result = if (operationResult.isSuccess) operationResult else runCatching(timeoutOperation)
                if (settled.compareAndSet(false, true)) completionExecutor.execute { completion(result) }
                deadlineWorker.shutdownNow()
                worker.shutdown()
            }
            true
        }

    private fun <T> enqueue(
        operation: () -> T,
        completion: (Result<T>) -> Unit,
    ): Boolean = synchronized(lock) {
        if (closing) return@synchronized false
        worker.execute {
            val result = runCatching(operation)
            completionExecutor.execute { completion(result) }
        }
        true
    }

    internal fun awaitTerminationForTest(timeout: Long, unit: TimeUnit): Boolean =
        worker.awaitTermination(timeout, unit)
}

/** Per-view native owner. #102 may supply intent, but not this binding or its lifecycle. */
internal class NativeCaptureBindingV2(
    context: Context,
    private val safety: CaptureSafetySignalV2,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val events: (NativeCaptureEventV2) -> Unit = {},
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
    private var resourcesClosed = false
    private val bridge = SharedCameraManagerExposurePortV2(
        request = { qualifier, required, callback ->
            val manager = synchronized(lock) { if (closed) null else sharedCamera }
            manager?.requestAttemptQualifiedExposureV2(qualifier, required, callback) ?: false
        },
        cancel = { qualifier ->
            val manager = synchronized(lock) { sharedCamera }
            manager?.cancelAttemptQualifiedExposureV2(qualifier)
        },
    )
    private val recovery = NativeCaptureRecoveryDispatcherV2(
        recover = { shouldContinue -> store.recoverAndProject(shouldContinue = shouldContinue) },
        events = events,
    )
    private val adapter = NativeCaptureAdapterV2(
        DurableNativeCaptureStorePortV2(store), bridge, safety, nowMs, recovery::emitLive,
    )
    private val deadlineScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "capture3d-v2-deadline").apply { isDaemon = true }
    }.apply {
        scheduleAtFixedRate(
            { runCatching { adapter.advanceDeadlines() } },
            1,
            1,
            TimeUnit.SECONDS,
        )
    }
    fun attachSharedCamera(manager: SharedCameraManager) = synchronized(lock) {
        check(!closed) { "NativeCaptureBindingV2 is closed" }
        sharedCamera = manager
        // #102's manager-owned direct route is the default. Tests may install
        // a synthetic hook explicitly below; neither route can consult V1's
        // cache/correlation APIs.
    }
    fun detachSharedCamera(manager: SharedCameraManager) = synchronized(lock) { if (sharedCamera === manager) sharedCamera = null }
    fun admit(request: CaptureCommitRequest): CaptureReceipt {
        synchronized(lock) {
            check(!closed) { "NativeCaptureBindingV2 is closed" }
            recovery.requireAdmissionReady()
        }
        return adapter.admit(request)
    }
    fun onLifecycle(event: CaptureLifecycleEvent, cut: CaptureLifecycleCut) = adapter.onLifecycle(event, cut)
    fun onLifecycle(event: CaptureLifecycleEvent) = adapter.onLifecycle(event)
    fun onPause() = adapter.onLifecycle(CaptureLifecycleEvent.BACKGROUNDED)
    fun onViewReplacement() = adapter.onLifecycle(CaptureLifecycleEvent.VIEW_REPLACED)
    fun onArSessionReplacement() = adapter.onLifecycle(CaptureLifecycleEvent.AR_SESSION_REPLACED)
    fun forceRecoveryForDebug() = adapter.forceRecoveryForDebug()
    fun replayRecovery(): List<NativeCaptureEventV2> = recovery.replaySnapshot()
    fun acknowledgeTerminal(attemptId: String) = recovery.acknowledgeTerminal(attemptId)
    fun snapshot() = adapter.snapshot()

    /** Native instrumentation seam; it is never registered with a Flutter channel. */
    internal fun syntheticAdapterForTest(): NativeCaptureAdapterV2 = adapter

    internal fun installSyntheticExposureHookForTest(
        request: (CaptureAttemptQualifierV2, Set<CaptureComponentKind>, SharedCameraExposureCallbackV2) -> Boolean,
        cancel: (CaptureAttemptQualifierV2) -> Unit = {},
    ): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val manager = sharedCamera ?: return@synchronized false
        manager.installAttemptQualifiedExposureHookV2(request, cancel)
        true
    }

    override fun close() {
        synchronized(lock) {
            if (resourcesClosed) return
            closed = true
        }
        deadlineScheduler.shutdownNow()
        recovery.close()
        adapter.onLifecycle(CaptureLifecycleEvent.VIEW_REPLACED)
        adapter.close()
        val manager = synchronized(lock) { sharedCamera.also { sharedCamera = null } }
        manager?.clearAttemptQualifiedExposureHookV2()
        store.close()
        budget.close()
        synchronized(lock) { resourcesClosed = true }
    }

    /** Safe non-blocking half-close for the serial owner's shutdown deadline. */
    internal fun forceCloseForDeadline() {
        synchronized(lock) { closed = true }
        deadlineScheduler.shutdownNow()
        recovery.close()
        adapter.forceCloseForDeadline()
        synchronized(lock) { sharedCamera }?.clearAttemptQualifiedExposureHookV2()
    }

    private fun createNativeCaptureRootV2(context: Context): java.io.File {
        val root = java.io.File(context.filesDir, "capture-v2-native")
        AndroidDescriptorFilesystemV2().use { unbound ->
            unbound.bind(root).use { }
        }
        return root
    }
}

/**
 * Bounded scalar observability only; no component lengths, bytes, or paths escape this owner.
 * Terminal counters are exact unique exposed attempts. unknownQueries is the current number of
 * unique exposed attempts with unresolved durable outcomes, never a cumulative query-call count.
 */
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
    private val lock = Any()
    private val exposures = AtomicLong()
    private val lateCallbacks = AtomicLong()
    private val closedComponents = AtomicLong()
    private val committed = AtomicLong()
    private val abandoned = AtomicLong()
    private val unknownAttempts = linkedSetOf<CaptureAttemptIdentity>()
    fun exposure() = exposures.incrementAndGet()
    fun lateCallback() = lateCallbacks.incrementAndGet()
    fun componentsClosed(count: Int) = closedComponents.addAndGet(count.toLong())
    fun committed() = committed.incrementAndGet()
    fun abandoned() = abandoned.incrementAndGet()
    fun unknownQuery(identity: CaptureAttemptIdentity) = synchronized(lock) {
        unknownAttempts += identity
    }
    fun terminalResolved(identity: CaptureAttemptIdentity) = synchronized(lock) {
        unknownAttempts -= identity
    }
    fun snapshot(running: Int, waiting: Int) = synchronized(lock) {
        CaptureResourceSnapshotV2(
            exposures.get(), lateCallbacks.get(), closedComponents.get(), committed.get(), abandoned.get(),
            unknownAttempts.size.toLong(), running, waiting,
        )
    }
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
    private val events: (NativeCaptureEventV2) -> Unit = {},
) : AutoCloseable {
    private data class Work(
        val request: CaptureCommitRequest,
        val acceptedReceipt: CaptureReceipt,
        val acceptedAtMs: Long,
        val qualifier: CaptureAttemptQualifierV2,
        var exposureRequested: Boolean = false,
        var exposureCounted: Boolean = false,
        var submissionInFlight: Boolean = false,
        var submissionResultResolved: Boolean = false,
        var submissionFailure: String? = null,
        var pendingLifecycleReason: String? = null,
        var lifecycleTransferInProgress: Boolean = false,
        var componentsClaimed: Boolean = false,
        var pendingComponents: SharedCameraComponentSetV2? = null,
        var inFlightStoreComponents: SharedCameraComponentSetV2? = null,
        var tenSecondPresented: Boolean = false,
        var terminalClaimed: Boolean = false,
    )
    private val lock = ReentrantLock(true)
    private val storeIdle = lock.newCondition()
    private val submissionIdle = lock.newCondition()
    private val admissionLock = ReentrantLock(true)
    private val scheduler = CaptureFinalizerScheduler()
    private val counters = CaptureResourceCountersV2()
    private val work = linkedMapOf<CaptureAttemptIdentity, Work>()
    private var closed = false
    private var closing = false
    private var storeOperations = 0
    private var submissionOperations = 0
    private var lifecycleDrain = false
    private var lifecycleGeneration = 0L
    private var automaticLifecycleGeneration = 0L
    private var nextExposureGeneration = 0L

    fun admit(request: CaptureCommitRequest): CaptureReceipt = admissionLock.withLock { lock.withLock {
        check(!closed && !closing) { "NativeCaptureAdapterV2 is closed" }
        val admissionLifecycleGeneration = lifecycleGeneration
        val admissionAutomaticLifecycleGeneration = automaticLifecycleGeneration
        val accepted = request.accepted
        fun lifecycleCutDuringAdmission(): Boolean =
            lifecycleGeneration != admissionLifecycleGeneration ||
                (accepted.lane == CaptureLane.AUTOMATIC &&
                    automaticLifecycleGeneration != admissionAutomaticLifecycleGeneration)
        validateReservationLiabilities(accepted)
        work[accepted.identity]?.let { active ->
            if (active.request == request) return@withLock active.acceptedReceipt
            throw DurableStoreConflictV2("Changed replay conflicts with active capture identity")
        }
        check(work.values.none { it.request.accepted.lane == accepted.lane }) {
            "lane-capacity-${accepted.lane.name.lowercase()}"
        }
        storeCallLocked { store.replayFenceBeforeExposure(request) }?.let { replay ->
            if (replay.terminal != null) return@withLock replay
            // A durable accepted identity from a prior owner is outcome-unknown.
            // Fence it metadata-only; never infer that a second shutter is safe.
            return@withLock abandonLocked(request, "durable-replay-no-reexposure")
        }
        check(!lifecycleCutDuringAdmission() && !closing) { "lifecycle-cut-during-admission" }

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
            storeCallLocked { store.acceptBeforeExposure(request) }
        } catch (error: Throwable) {
            scheduler.release(accepted.identity)
            scheduler.running?.let { next -> work[next.identity]?.let(::startLocked) }
            throw error
        }
        if (lifecycleCutDuringAdmission() || closing) {
            scheduler.release(accepted.identity)
            val terminal = CaptureTerminal(
                CaptureTerminalKind.ABANDONED_ATTEMPT,
                accepted.identity,
                "lifecycle-cut-during-admission",
                "lifecycle-cut-during-admission",
            )
            val abandoned = storeCallLocked { store.abandon(request, terminal) }
            scheduler.running?.let { next -> work[next.identity]?.let(::startLocked) }
            return@withLock abandoned
        }
        if (receipt.terminal != null) {
            scheduler.release(accepted.identity)
            scheduler.running?.let { next -> work[next.identity]?.let(::startLocked) }
            return@withLock receipt
        }
        val qualifier = CaptureAttemptQualifierV2(accepted.identity, accepted.identity.lifecycleCut, ++nextExposureGeneration)
        work[accepted.identity] = Work(request, receipt, nowMs(), qualifier)
        emitLocked(NativeCaptureEventV2(NativeCaptureEventKindV2.ACCEPTED, accepted.identity.attemptId))
        if (assignment.position == CaptureFinalizerPosition.RUNNING) startLocked(work.getValue(accepted.identity))
        receipt
    } }

    fun onLifecycle(event: CaptureLifecycleEvent, cut: CaptureLifecycleCut) = lock.withLock {
        drainLifecycleLocked(event, setOf(cut))
    }

    fun onLifecycle(event: CaptureLifecycleEvent) = lock.withLock {
        drainLifecycleLocked(event, work.values.map { it.qualifier.lifecycleCut }.toSet())
    }

    private fun drainLifecycleLocked(event: CaptureLifecycleEvent, cuts: Set<CaptureLifecycleCut>) {
        if (event == CaptureLifecycleEvent.AUTOMATIC_DISABLED) {
            automaticLifecycleGeneration += 1
        } else {
            lifecycleGeneration += 1
        }
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
                    CaptureLifecycleEvent.ROUTE_LEFT -> lifecycleTransferLocked(value, "route-left")
                    CaptureLifecycleEvent.VIEW_REPLACED -> lifecycleTransferLocked(value, "view-replaced")
                    CaptureLifecycleEvent.AR_SESSION_REPLACED -> lifecycleTransferLocked(value, "ar-session-replaced")
                    CaptureLifecycleEvent.BACKGROUNDED -> lifecycleTransferLocked(value, "backgrounded")
                    CaptureLifecycleEvent.PROCESS_RESTARTED -> lifecycleTransferLocked(value, "process-restarted")
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
    fun advanceDeadlines() = lock.withLock {
        val now = nowMs()
        work.values.toList().forEach { value ->
            val elapsed = now - value.acceptedAtMs
            if (!value.exposureRequested) {
                if (elapsed >= AUTOMATIC_STALL_MS) {
                    abandonLocked(value.request, "funded-waiting-timeout-before-exposure")
                }
                return@forEach
            }
            // The SharedCamera submission call runs without this lock. Its
            // Boolean result, not a synchronous failure callback, establishes
            // whether Camera2 accepted exposure ownership.
            if (!value.exposureCounted) return@forEach
            if (elapsed >= TERMINAL_FENCE_MS) {
                queryOrAbandonLocked(value, "terminal-fence-30s")
            } else if (elapsed >= AUTOMATIC_STALL_MS && !value.tenSecondPresented) {
                value.tenSecondPresented = true
                presentUnknownLocked(value, "terminal-presentation-10s")
            }
        }
    }

    /** Debug-only platform-view selector seam; production recovery uses deadlines. */
    internal fun forceRecoveryForDebug() = lock.withLock {
        work.values.toList().forEach { value ->
            when {
                value.exposureCounted -> queryOrAbandonLocked(value, "debug-terminal-query")
                !value.exposureRequested -> abandonLocked(value.request, "debug-before-exposure")
            }
        }
    }

    fun snapshot(): CaptureResourceSnapshotV2 = lock.withLock {
        counters.snapshot(if (scheduler.running == null) 0 else 1, if (scheduler.fundedWaiting == null) 0 else 1)
    }

    internal fun waitingIdentityForTest(): CaptureAttemptIdentity? = lock.withLock {
        scheduler.fundedWaiting?.identity
    }

    override fun close() = lock.withLock {
        if (closed || closing) return@withLock
        closing = true
        lifecycleGeneration += 1
        work.values.filter { it.submissionInFlight }.forEach {
            if (it.pendingLifecycleReason == null) it.pendingLifecycleReason = "shutdown"
            discardPendingComponentsLocked(it)
        }
        while (submissionOperations != 0) submissionIdle.await()
        while (storeOperations != 0) storeIdle.await()
        closed = true
        work.values.toList().forEach { value ->
            if (value.exposureCounted) transferUnknownLocked(value, "shutdown-transfer")
            else abandonLocked(value.request, "shutdown-before-exposure")
        }
        closing = false
    }

    /**
     * Deadline fallback used only after the owning serial close could not run or
     * finish. It performs no external/store call: every durable accepted owner
     * transfers to restart recovery, safety fails closed, and late components
     * are rejected before platform teardown is allowed to continue.
     */
    internal fun forceCloseForDeadline() = lock.withLock {
        if (closed) return@withLock
        closing = true
        closed = true
        lifecycleGeneration += 1
        safety.invalidateAll()
        work.values.toList().forEach { value ->
            if (value.pendingLifecycleReason == null) value.pendingLifecycleReason = "shutdown-deadline"
            discardPendingComponentsLocked(value)
            value.inFlightStoreComponents?.let {
                closeStreams(it.streams)
                value.inFlightStoreComponents = null
            }
            counters.unknownQuery(value.request.accepted.identity)
            emitLocked(
                NativeCaptureEventV2(
                    NativeCaptureEventKindV2.RECOVERING,
                    value.request.accepted.identity.attemptId,
                    reason = "shutdown-deadline-transfer",
                    recoveryContext = value.request.recoveryContext,
                ),
            )
            safety.release(value.qualifier)
            work.remove(value.request.accepted.identity)
            scheduler.release(value.request.accepted.identity)
        }
        closing = false
    }

    private fun startLocked(value: Work) {
        if (closed || value.exposureRequested || scheduler.running?.identity != value.request.accepted.identity) return
        // Bind before submission: a fake or Camera2 bridge may complete on the
        // same stack. Binding afterwards would resurrect a stale true signal.
        safety.bind(value.qualifier, value.acceptedReceipt)
        value.exposureRequested = true
        value.submissionInFlight = true
        submissionOperations += 1
        var requested = false
        var propagatedSubmissionFailure: Throwable? = null
        try {
            try {
                requested = externalCallLocked {
                    exposure.requestExposure(value.qualifier, value.request.accepted.profile.requiredComponents, callback)
                }
            } catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException) {
                    propagatedSubmissionFailure = error
                }
            } catch (error: Error) {
                propagatedSubmissionFailure = error
            }
            // A legal fake/Camera2 bridge may have completed synchronously.
            if (work[value.request.accepted.identity] !== value) {
                propagatedSubmissionFailure?.let { throw it }
                return
            }
            value.submissionResultResolved = true
            val submissionFailure = value.submissionFailure
            value.submissionFailure = null
            val pendingLifecycleReason = value.pendingLifecycleReason
            value.pendingLifecycleReason = null
            propagatedSubmissionFailure?.let { failure ->
                discardPendingComponentsLocked(value)
                value.exposureRequested = false
                safety.release(value.qualifier)
                abandonLocked(value.request, "submission-interrupted-before-result")
                throw failure
            }
            if (!requested) {
                discardPendingComponentsLocked(value)
                value.exposureRequested = false
                safety.release(value.qualifier)
                abandonLocked(
                    value.request,
                    submissionFailure?.let { "camera-$it" }
                        ?: pendingLifecycleReason?.let { "$it-before-exposure" }
                        ?: "exposure-request-rejected",
                )
                return
            }
            countExposureLocked(value)
            when {
                pendingLifecycleReason != null -> {
                    discardPendingComponentsLocked(value)
                    transferUnknownLocked(value, "$pendingLifecycleReason-transfer")
                }
                closing -> {
                    discardPendingComponentsLocked(value)
                    transferUnknownLocked(value, "shutdown-transfer")
                }
                submissionFailure != null -> {
                    discardPendingComponentsLocked(value)
                    abandonLocked(value.request, "camera-$submissionFailure")
                }
                value.pendingComponents != null -> {
                    val components = checkNotNull(value.pendingComponents)
                    value.pendingComponents = null
                    processComponentsLocked(value, components)
                }
            }
        } finally {
            value.submissionInFlight = false
            value.submissionResultResolved = false
            submissionOperations -= 1
            check(submissionOperations >= 0)
            if (submissionOperations == 0) submissionIdle.signalAll()
        }
    }

    private val callback = object : SharedCameraExposureCallbackV2 {
        override fun onComponents(components: SharedCameraComponentSetV2) = lock.withLock {
            val value = work[components.qualifier.identity]
            if (
                value == null || value.qualifier != components.qualifier ||
                scheduler.running?.identity != components.qualifier.identity || closed || closing ||
                value.pendingLifecycleReason != null || value.componentsClaimed
            ) {
                closeStreams(components.streams)
                counters.lateCallback()
                return@withLock
            }
            value.componentsClaimed = true
            if (components.streams.size > value.request.accepted.profile.requiredComponents.size) {
                closeStreams(components.streams)
                if (value.submissionInFlight && !value.submissionResultResolved) {
                    value.submissionFailure = "malformed-component-set"
                } else {
                    countExposureLocked(value)
                    abandonLocked(value.request, "malformed-component-set")
                }
                return@withLock
            }
            val owned = components.copy(
                streams = components.streams.map { stream ->
                    CaptureComponentStreamV2(stream.kind, CloseOnceInputStreamV2(stream.input))
                },
            )
            if (value.submissionInFlight && !value.submissionResultResolved) {
                value.pendingComponents = owned
                return@withLock
            }
            processComponentsLocked(value, owned)
        }

        override fun onFailure(qualifier: CaptureAttemptQualifierV2, reason: String) = lock.withLock {
            val value = work[qualifier.identity]
            if (
                value == null || value.qualifier != qualifier || closed || closing ||
                value.pendingLifecycleReason != null
            ) {
                counters.lateCallback()
                return@withLock
            }
            if (value.submissionInFlight && !value.submissionResultResolved) {
                value.submissionFailure = reason
                return@withLock
            }
            abandonLocked(value.request, "camera-$reason")
        }
    }

    private fun processComponentsLocked(value: Work, components: SharedCameraComponentSetV2) {
        check(lock.isHeldByCurrentThread)
        countExposureLocked(value)
        val kinds = components.streams.map { it.kind }
        if (kinds.size != kinds.toSet().size || kinds.toSet() != value.request.accepted.profile.requiredComponents) {
            closeStreams(components.streams)
            abandonLocked(value.request, "malformed-component-set")
            return
        }
        beginStoreOperationLocked()
        emitLocked(NativeCaptureEventV2(NativeCaptureEventKindV2.FINALIZING, value.request.accepted.identity.attemptId))
        if (
            work[value.request.accepted.identity] !== value ||
            value.pendingLifecycleReason != null || closing
        ) {
            if (work[value.request.accepted.identity] === value) {
                transferUnknownLocked(value, "${value.pendingLifecycleReason ?: "shutdown"}-transfer")
            }
            closeStreams(components.streams)
            endStoreOperationLocked()
            return
        }
        var receipt: CaptureReceipt? = null
        var storeFailed = false
        var propagatedStoreFailure: Throwable? = null
        value.inFlightStoreComponents = components
        try {
            receipt = externalCallLocked {
                store.commitStreamed(
                    value.request,
                    components.exposureTimestampNanoseconds,
                    components.streams,
                )
            }
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) {
                propagatedStoreFailure = error
            } else {
                storeFailed = true
            }
        } catch (error: Error) {
            propagatedStoreFailure = error
        } finally {
            closeStreams(components.streams)
            value.inFlightStoreComponents = null
            endStoreOperationLocked()
        }
        if (work[value.request.accepted.identity] !== value) {
            propagatedStoreFailure?.let { throw it }
            return
        }
        val cutReason = value.pendingLifecycleReason
        when {
            cutReason != null && value.lifecycleTransferInProgress -> Unit
            cutReason != null -> transferUnknownLocked(value, "$cutReason-transfer")
            closing -> transferUnknownLocked(value, "shutdown-transfer")
            propagatedStoreFailure != null -> queryOrAbandonLocked(value, "store-interrupted")
            storeFailed -> queryOrAbandonLocked(value, "store-unknown")
            else -> finishLocked(value, checkNotNull(receipt))
        }
        propagatedStoreFailure?.let { throw it }
    }

    private fun discardPendingComponentsLocked(value: Work) {
        value.pendingComponents?.let { closeStreams(it.streams) }
        value.pendingComponents = null
    }

    private fun queryOrAbandonLocked(value: Work, reason: String) {
        emitLocked(
            NativeCaptureEventV2(
                NativeCaptureEventKindV2.RECOVERING,
                value.request.accepted.identity.attemptId,
                reason = reason,
                recoveryContext = value.request.recoveryContext,
            ),
        )
        counters.unknownQuery(value.request.accepted.identity)
        val receipt = storeCallLocked { store.queryReceipt(value.request.accepted.identity) }
        if (work[value.request.accepted.identity] !== value) return
        if (receipt?.terminal != null) { finishLocked(value, receipt); return }
        // Rebase only a durable prepared/committed identity; the port rejects changed bytes.
        var propagatedRebaseFailure: Throwable? = null
        val rebased = try {
            storeCallLocked { store.rebaseSameAttempt(value.request) }
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) propagatedRebaseFailure = error
            null
        } catch (error: Error) {
            propagatedRebaseFailure = error
            null
        }
        rebased?.let {
            if (work[value.request.accepted.identity] === value) finishLocked(value, it)
            return
        }
        if (work[value.request.accepted.identity] !== value) {
            propagatedRebaseFailure?.let { throw it }
            return
        }
        propagatedRebaseFailure?.let { failure ->
            if (value.pendingLifecycleReason == null && !closing) {
                abandonLocked(value.request, "rebase-interrupted")
            }
            throw failure
        }
        abandonLocked(value.request, reason)
    }

    private fun presentUnknownLocked(value: Work, reason: String) {
        emitLocked(
            NativeCaptureEventV2(
                NativeCaptureEventKindV2.RECOVERING,
                value.request.accepted.identity.attemptId,
                reason = reason,
                recoveryContext = value.request.recoveryContext,
            ),
        )
        counters.unknownQuery(value.request.accepted.identity)
        storeCallLocked { store.queryReceipt(value.request.accepted.identity) }?.takeIf { it.terminal != null }
            ?.let { receipt ->
                if (work[value.request.accepted.identity] === value) finishLocked(value, receipt)
            }
    }

    private fun lifecycleTransferLocked(value: Work, reason: String) {
        if (value.submissionInFlight) {
            if (value.pendingLifecycleReason == null) value.pendingLifecycleReason = reason
            discardPendingComponentsLocked(value)
            return
        }
        if (value.exposureCounted) {
            if (value.pendingLifecycleReason == null) value.pendingLifecycleReason = reason
            transferUnknownLocked(value, "$reason-transfer")
        }
        else abandonLocked(value.request, "$reason-before-exposure")
    }

    private fun transferUnknownLocked(value: Work, reason: String) {
        if (value.lifecycleTransferInProgress) return
        value.lifecycleTransferInProgress = true
        try {
        emitLocked(
            NativeCaptureEventV2(
                NativeCaptureEventKindV2.RECOVERING,
                value.request.accepted.identity.attemptId,
                reason = reason,
                recoveryContext = value.request.recoveryContext,
            ),
        )
        counters.unknownQuery(value.request.accepted.identity)
        // A lifecycle owner cut transfers authority to durable recovery even
        // if a concurrent store handoff has just reached a terminal. Projecting
        // that terminal on the closing view would publish after the cut.
        storeCallLocked { store.queryReceipt(value.request.accepted.identity) }
        if (work[value.request.accepted.identity] !== value) return
        safety.release(value.qualifier)
        work.remove(value.request.accepted.identity)
        scheduler.release(value.request.accepted.identity)
        externalCallLocked { exposure.cancelExposure(value.qualifier) }
        } finally {
            value.lifecycleTransferInProgress = false
        }
    }

    private fun abandonLocked(request: CaptureCommitRequest, reason: String): CaptureReceipt {
        val accepted = request.accepted
        val value = work[accepted.identity]
        val terminal = CaptureTerminal(CaptureTerminalKind.ABANDONED_ATTEMPT, accepted.identity, reason, reason)
        val receipt = storeCallLocked { store.abandon(request, terminal) }
        finishLocked(value, receipt, request.recoveryContext)
        return receipt
    }

    private fun finishLocked(
        value: Work?,
        receipt: CaptureReceipt,
        recoveryContext: NativeCaptureRecoveryContextV2? = value?.request?.recoveryContext,
    ) {
        val identity = receipt.identity
        val terminal = checkNotNull(receipt.terminal) { "A finished capture requires durable terminal authority" }
        if (value != null) {
            if (value.terminalClaimed || work[identity] !== value) return
            value.terminalClaimed = true
        }
        work.remove(identity)
        scheduler.release(identity)
        value?.let { safety.release(it.qualifier) }
        value?.let { externalCallLocked { exposure.cancelExposure(it.qualifier) } }
        if (value != null) {
            counters.terminalResolved(identity)
            if (value.exposureCounted) {
                if (terminal.kind == CaptureTerminalKind.COMMITTED_PICTURE) counters.committed() else counters.abandoned()
            }
        }
        emitLocked(
            NativeCaptureEventV2(
                if (terminal.kind == CaptureTerminalKind.COMMITTED_PICTURE) {
                    NativeCaptureEventKindV2.COMMITTED
                } else {
                    NativeCaptureEventKindV2.ABANDONED
                },
                attemptId = identity.attemptId,
                captureId = terminal.captureId,
                captureRevision = terminal.captureRevision,
                manifestId = terminal.manifestId,
                reason = terminal.reason,
                recoveryContext = recoveryContext,
            ),
        )
        emitLocked(NativeCaptureEventV2(NativeCaptureEventKindV2.HEALTH, resources = snapshot()))
        if (!lifecycleDrain) scheduler.running?.let { next -> work[next.identity]?.let(::startLocked) }
    }

    private fun countExposureLocked(value: Work) {
        if (!value.exposureCounted) {
            value.exposureCounted = true
            counters.exposure()
        }
    }

    private fun closeStreams(streams: List<CaptureComponentStreamV2>) {
        streams.forEach { runCatching { it.input.close() } }
        counters.componentsClosed(streams.size)
    }

    private inline fun <T> storeCallLocked(block: () -> T): T {
        check(lock.isHeldByCurrentThread)
        beginStoreOperationLocked()
        return try {
            externalCallLocked(block)
        } finally {
            endStoreOperationLocked()
        }
    }

    private fun beginStoreOperationLocked() {
        check(lock.isHeldByCurrentThread)
        storeOperations += 1
    }

    private fun endStoreOperationLocked() {
        check(lock.isHeldByCurrentThread)
        storeOperations -= 1
        check(storeOperations >= 0)
        if (storeOperations == 0) storeIdle.signalAll()
    }

    private inline fun <T> externalCallLocked(block: () -> T): T {
        check(lock.isHeldByCurrentThread && lock.holdCount == 1)
        lock.unlock()
        return try {
            block()
        } finally {
            lock.lock()
        }
    }

    private fun emitLocked(event: NativeCaptureEventV2) {
        externalCallLocked { events(event) }
    }

    private fun validateReservationLiabilities(accepted: CaptureAcceptedAttempt) {
        val profile = accepted.profile
        val reservation = accepted.reservation
        require(reservation.physicallyBacked) { "reservation-not-physical" }
        require(reservation.memoryBytes >= profile.maximumWorkingBytes) { "reservation-memory-underfunded" }
        require(reservation.physicalStoreBytes >= NativeCaptureReservationBoundsV2.physicalBytes(profile.maximumComponentBytes, profile.requiredComponents.size)) { "reservation-store-underfunded" }
        require(reservation.rollbackBytes >= NativeCaptureReservationBoundsV2.rollbackBytes(profile.maximumComponentBytes)) { "reservation-rollback-underfunded" }
        require(reservation.componentEntries == profile.requiredComponents.size.toLong()) { "reservation-component-ledger" }
        require(reservation.terminalEntries == 1L) { "reservation-terminal-ledger" }
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
