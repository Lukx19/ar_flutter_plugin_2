package com.uhg0.ar_flutter_plugin_2.capture

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityCaptureSafePredicate
import com.uhg0.ar_flutter_plugin_2.visibilityingest.DurableTerminalTap
import com.uhg0.ar_flutter_plugin_2.visibilityingest.VisibilityGridShellRegistry
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
internal class AssessedReplayUnavailableV2 : UnsupportedOperationException("Assessed replay is unavailable")

internal interface NativeCaptureStorePortV2 {
    fun replayFenceBeforeExposure(request: CaptureCommitRequest): CaptureReceipt?
    fun replayAssessedFenceBeforeExposure(request: CaptureCommitRequest): CaptureReceipt? =
        throw AssessedReplayUnavailableV2()
    fun acceptBeforeExposure(request: CaptureCommitRequest): CaptureReceipt
    fun commitStreamed(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>): CaptureReceipt
    fun commitStreamed(
        request: CaptureCommitRequest,
        exposureTimestampNanoseconds: Long,
        streams: List<CaptureComponentStreamV2>,
    ): CaptureReceipt = commitStreamed(request, streams)
    fun commitStreamedAssessed(
        request: CaptureCommitRequest, exposureTimestampNanoseconds: Long,
        streams: List<CaptureComponentStreamV2>, qualityAssessment: CaptureQualityAssessmentV1,
    ): CaptureReceipt = throw UnsupportedOperationException("Assessed durable commit is unavailable")
    fun queryReceipt(identity: CaptureAttemptIdentity): CaptureReceipt?
    fun rebaseSameAttempt(request: CaptureCommitRequest): CaptureReceipt
    fun abandon(request: CaptureCommitRequest, terminal: CaptureTerminal): CaptureReceipt
}
internal class DurableNativeCaptureStorePortV2(
    private val store: DurableSessionStoreV2,
    private val retainAssessedOriginals: Boolean = false,
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
    override fun replayAssessedFenceBeforeExposure(request: CaptureCommitRequest) =
        if (request.components.isEmpty()) store.replayAssessedTemplateFenceBeforeExposure(template(request))
        else store.replayFenceBeforeExposure(request)
    override fun acceptBeforeExposure(request: CaptureCommitRequest) =
        store.acceptCaptureBeforeExposure(request, requireRecoveryIdentity = retainAssessedOriginals)
    override fun commitStreamedAssessed(
        request: CaptureCommitRequest, exposureTimestampNanoseconds: Long,
        streams: List<CaptureComponentStreamV2>, qualityAssessment: CaptureQualityAssessmentV1,
    ): CaptureReceipt = if (request.components.isEmpty()) {
        store.commitStreamedFinalizedAssessed(template(request), exposureTimestampNanoseconds, streams, qualityAssessment)
    } else {
        store.commitStreamedAssessed(CaptureCommitRequest(request.accepted, request.components,
            exposureTimestampNanoseconds, request.poseRecordHash, request.cameraModelHash,
            request.validationRecordHash, request.ledgerRecordHash, request.recoveryContext, qualityAssessment), streams)
    }
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
    val qualityAssessment: CaptureQualityAssessmentV1? = null,
)

internal enum class NativeCaptureEventKindV2 { ACCEPTED, FINALIZING, RECOVERING, RECOVERY_FAILED, READY, COMMITTED, ABANDONED, HEALTH }

/** Strict bounded event: scalar identities/counters only, never paths, descriptors, or bytes. */
internal data class NativeCaptureEventV2(
    val kind: NativeCaptureEventKindV2,
    val attemptId: String? = null,
    val captureId: String? = null,
    val captureRevision: Long? = null,
    val manifestId: String? = null,
    val jpegSizeBytes: Long? = null,
    val dngSizeBytes: Long? = null,
    val reason: String? = null,
    val resources: CaptureResourceSnapshotV2? = null,
    val recoveryContext: NativeCaptureRecoveryContextV2? = null,
    val qualityAssessment: CaptureQualityAssessmentV1? = null,
    val qualityAssessmentHash: String? = null,
    val verifiedTerminal: CaptureTerminal? = null,
    val heicSizeBytes: Long? = null,
)

/** Both live and recovered assessed events use the contract encoder and verified receipt authority. */
internal fun encodeNativeCaptureEventV2(value: NativeCaptureEventV2): Map<String, Any?> {
    if (value.qualityAssessment == null && value.qualityAssessmentHash == null) {
        require(value.verifiedTerminal?.qualityAssessment == null) { "Assessed terminal cannot use legacy encoding" }
        return NativeCaptureWireV2.event(value)
    }
    val terminal = requireNotNull(value.verifiedTerminal) { "Assessed event has no verified durable terminal" }
    require(value.qualityAssessment != null && value.qualityAssessment == terminal.qualityAssessment &&
        value.qualityAssessmentHash == terminal.qualityAssessmentHash)
    val context = requireNotNull(value.recoveryContext)
    require(context.sessionId == terminal.identity.lifecycleCut.sessionId &&
        context.groupId == terminal.identity.lifecycleCut.groupId &&
        context.groupGeneration == terminal.identity.lifecycleCut.groupGeneration)
    return NativeCaptureWireV2.assessedCommittedEvent(value, terminal)
}

internal interface SharedCameraExposureCallbackV2 {
    val retainAssessedOriginals: Boolean get() = false
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

    /** Supplies the already funded workspace without extending the platform wire. */
    fun requestExposure(
        qualifier: CaptureAttemptQualifierV2,
        required: Set<CaptureComponentKind>,
        callback: SharedCameraExposureCallbackV2,
        maximumWorkingBytes: Long,
    ): Boolean = requestExposure(qualifier, required, callback)

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
    private val fundedRequest: ((CaptureAttemptQualifierV2, Set<CaptureComponentKind>,
        SharedCameraExposureCallbackV2, Long) -> Boolean)? = null,
) : SharedCameraExposurePortV2 {
    override fun requestExposure(qualifier: CaptureAttemptQualifierV2, required: Set<CaptureComponentKind>, callback: SharedCameraExposureCallbackV2) =
        request(qualifier, required, callback)
    override fun requestExposure(qualifier: CaptureAttemptQualifierV2, required: Set<CaptureComponentKind>,
        callback: SharedCameraExposureCallbackV2, maximumWorkingBytes: Long): Boolean =
        fundedRequest?.invoke(qualifier, required, callback, maximumWorkingBytes)
            ?: requestExposure(qualifier, required, callback)
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
            val recovered = runCatching { recover(::isActive).map { projection ->
                projection.toNativeEvent().also { event ->
                    if (event.qualityAssessment != null || event.qualityAssessmentHash != null) encodeNativeCaptureEventV2(event)
                }
            } }
            synchronized(lock) {
                if (state == State.CLOSED) return@execute
                recovered.fold(
                    onSuccess = { projections ->
                        state = State.READY
                        projections.forEach(::emitAndRetainLocked)
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
        if (event.qualityAssessment != null || event.qualityAssessmentHash != null) encodeNativeCaptureEventV2(event)
        when {
            event.kind == NativeCaptureEventKindV2.RECOVERY_FAILED -> replay["recovery-failed"] = event
            event.kind == NativeCaptureEventKindV2.COMMITTED || event.kind == NativeCaptureEventKindV2.ABANDONED || event.kind == NativeCaptureEventKindV2.RECOVERING -> {
                val key = requireNotNull(event.attemptId)
                val existing = replay[key]
                if (existing != null && existing.kind in setOf(NativeCaptureEventKindV2.COMMITTED, NativeCaptureEventKindV2.ABANDONED) &&
                    event.kind in setOf(NativeCaptureEventKindV2.COMMITTED, NativeCaptureEventKindV2.ABANDONED) &&
                    listOf(existing.kind, existing.captureId, existing.captureRevision, existing.manifestId,
                        existing.jpegSizeBytes, existing.dngSizeBytes, existing.heicSizeBytes, existing.recoveryContext,
                        existing.qualityAssessment, existing.qualityAssessmentHash,
                        existing.reason.takeIf { existing.kind == NativeCaptureEventKindV2.ABANDONED }) !=
                    listOf(event.kind, event.captureId, event.captureRevision, event.manifestId,
                        event.jpegSizeBytes, event.dngSizeBytes, event.heicSizeBytes, event.recoveryContext,
                        event.qualityAssessment, event.qualityAssessmentHash,
                        event.reason.takeIf { event.kind == NativeCaptureEventKindV2.ABANDONED })) {
                    throw DurableStoreConflictV2("Changed durable terminal conflicts with replay identity")
                }
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
        jpegSizeBytes = jpegSizeBytes,
        heicSizeBytes = heicSizeBytes,
        dngSizeBytes = dngSizeBytes,
        reason = reason,
        recoveryContext = recoveryContext,
        qualityAssessment = qualityAssessment,
        qualityAssessmentHash = qualityAssessmentHash,
        verifiedTerminal = verifiedTerminal,
    )

    private companion object {
        const val RECOVERY_FAILED_REASON = "durable-startup-recovery-failed"
    }
}

internal open class NativeCaptureRecoveryAdmissionExceptionV2(
    val code: String,
    message: String,
) : IllegalStateException(message)

internal class NativeCaptureDurableRootPendingV2 : NativeCaptureRecoveryAdmissionExceptionV2(
    "NATIVE_CAPTURE_V2_ROOT_PENDING",
    "The previous capture view still owns the durable root; retry after its shutdown completes.",
)

internal class NativeCaptureBindingClosedV2 : NativeCaptureRecoveryAdmissionExceptionV2(
    "NATIVE_CAPTURE_V2_RECOVERY_CLOSED",
    "Native capture binding is closed.",
)

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
 * Process-local exclusive authority for one durable capture root. A timed-out
 * view may release UI/Camera2 resources, but replacement recovery cannot open
 * this root until the old durable worker has actually terminated and closed it.
 */
internal class NativeCaptureDurableRootLeaseV2 private constructor(
    private val key: String,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        synchronized(activeRoots) {
            check(activeRoots.remove(key)) { "Native capture durable root lease was not active" }
        }
    }

    companion object {
        private val activeRoots = mutableSetOf<String>()

        fun acquire(root: java.io.File): NativeCaptureDurableRootLeaseV2 {
            val key = root.canonicalFile.path
            synchronized(activeRoots) {
                if (!activeRoots.add(key)) throw NativeCaptureDurableRootPendingV2()
            }
            return NativeCaptureDurableRootLeaseV2(key)
        }
    }
}

/** Retries durable handles before releasing the exclusive root authority. */
internal class NativeCaptureDurableRootCloserV2(
    private val closeStore: () -> Unit,
    private val closeBudget: () -> Unit,
    private val releaseLease: () -> Unit,
    private val onClosed: () -> Unit = {},
    private val retryDelayMillis: Long = 250L,
    private val retryWorker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "capture3d-v2-root-close-retry").apply { isDaemon = true }
    },
) {
    private val lock = Any()
    private var storeClosed = false
    private var budgetClosed = false
    private var closed = false
    private var retryScheduled = false

    fun close(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        var failure: Throwable? = null
        if (!storeClosed) {
            runCatching(closeStore).onSuccess { storeClosed = true }.onFailure { failure = it }
        }
        if (!budgetClosed) {
            runCatching(closeBudget).onSuccess { budgetClosed = true }.onFailure { if (failure == null) failure = it }
        }
        if (storeClosed && budgetClosed) {
            releaseLease()
            closed = true
            onClosed()
            retryWorker.shutdownNow()
        } else if (!retryScheduled) {
            retryScheduled = true
            retryWorker.schedule(
                {
                    synchronized(lock) { retryScheduled = false }
                    runCatching<Unit> { close() }
                },
                retryDelayMillis,
                TimeUnit.MILLISECONDS,
            )
        }
        failure?.let { throw it }
    }

    fun isClosed(): Boolean = synchronized(lock) { closed }
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
                    if (!settled.compareAndSet(false, true)) return@schedule
                    val result = runCatching(timeoutOperation)
                    completionExecutor.execute { completion(result) }
                    deadlineWorker.shutdown()
                },
                timeoutMillis,
                TimeUnit.MILLISECONDS,
            )
            worker.execute {
                val operationResult = runCatching(operation)
                if (settled.compareAndSet(false, true)) {
                    val result = if (operationResult.isSuccess) operationResult else runCatching(timeoutOperation)
                    completionExecutor.execute { completion(result) }
                }
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

/** Bound of the pause wait for exposed attempts (below the app's 10 s drain). */
private const val PAUSE_SETTLE_MS = 5_000L

/** Per-view native owner. #102 may supply intent, but not this binding or its lifecycle. */
internal class NativeCaptureBindingV2(
    context: Context,
    private val safety: CaptureSafetySignalV2,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val events: (NativeCaptureEventV2) -> Unit = {},
    private val retainAssessedOriginals: Boolean = false,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val rootResourcesDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createNativeCaptureRootResourcesV2(context)
    }
    private val rootResources by rootResourcesDelegate
    private val budget get() = rootResources.budget
    private val store get() = rootResources.store
    private var sharedCamera: SharedCameraManager? = null
    /** Debug/profile ARCore playback: CPU-image exposures when no shared camera is attached. */
    private var playbackExposure: SharedCameraExposurePortV2? = null
    private var closed = false
    private var resourcesClosed = false
    private val durableRootCloserDelegate = lazy {
        NativeCaptureDurableRootCloserV2(
            closeStore = {
                // Unregistered first: a session deletion running on this store finishes before it closes.
                NativeCaptureOpenRootStoresV2.unregister(rootResources.leaseKey, store)
                store.close()
            },
            closeBudget = budget::close,
            releaseLease = rootResources.lease::close,
            onClosed = { synchronized(lock) { resourcesClosed = true } },
        )
    }
    private val durableRootCloser by durableRootCloserDelegate
    private val bridge = SharedCameraManagerExposurePortV2(
        request = { qualifier, required, callback ->
            val (manager, playback) = synchronized(lock) {
                if (closed) null to null else sharedCamera to playbackExposure
            }
            manager?.requestAttemptQualifiedExposureV2(qualifier, required, callback)
                ?: playback?.requestExposure(qualifier, required, callback)
                ?: false
        },
        cancel = { qualifier ->
            val (manager, playback) = synchronized(lock) { sharedCamera to playbackExposure }
            if (manager != null) manager.cancelAttemptQualifiedExposureV2(qualifier) else playback?.cancelExposure(qualifier)
        },
        fundedRequest = { qualifier, required, callback, maximumWorkingBytes ->
            val (manager, playback) = synchronized(lock) {
                if (closed) null to null else sharedCamera to playbackExposure
            }
            manager?.requestAttemptQualifiedExposureV2(qualifier, required, callback, maximumWorkingBytes)
                ?: playback?.requestExposure(qualifier, required, callback, maximumWorkingBytes)
                ?: false
        },
    )
    private val recoveryDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        // Acquire the root inside the binding gate, before recovery can escape
        // onto its worker and race close-before-first-use.
        rootResources
        NativeCaptureRecoveryDispatcherV2(
            // Projection, then reservation reconciliation before the first admission.
            recover = { shouldContinue -> store.recoverAtOpen(shouldContinue) },
            events = events,
        )
    }
    private val recovery by recoveryDelegate
    private val adapterDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeCaptureAdapterV2(
            DurableNativeCaptureStorePortV2(store, retainAssessedOriginals), bridge, safety, nowMs, recovery::emitLive,
            retainAssessedOriginals,
        )
    }
    private val adapter by adapterDelegate
    private val deadlineSchedulerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "capture3d-v2-deadline").apply { isDaemon = true }
        }.apply {
            scheduleAtFixedRate(
                { runCatching { adapter.advanceDeadlines() } },
                1,
                1,
                TimeUnit.SECONDS,
            )
        }
    }
    private val deadlineScheduler by deadlineSchedulerDelegate
    fun attachSharedCamera(manager: SharedCameraManager) = synchronized(lock) {
        if (closed) throw NativeCaptureBindingClosedV2()
        sharedCamera = manager
        // #102's manager-owned direct route is the default. Tests may install
        // a synthetic hook explicitly below; neither route can consult V1's
        // cache/correlation APIs.
    }
    fun detachSharedCamera(manager: SharedCameraManager) = synchronized(lock) { if (sharedCamera === manager) sharedCamera = null }
    /** Playback test mode only; an attached shared camera keeps precedence. */
    fun attachPlaybackExposure(port: SharedCameraExposurePortV2?) = synchronized(lock) {
        if (closed) throw NativeCaptureBindingClosedV2()
        playbackExposure = port
    }
    fun admit(request: CaptureCommitRequest): CaptureReceipt {
        val (activeAdapter, activeRecovery) = durableOwnersForOperation()
        activeRecovery.requireAdmissionReady()
        return activeAdapter.admit(request)
    }
    fun onLifecycle(event: CaptureLifecycleEvent, cut: CaptureLifecycleCut) {
        adapterForLifecycleOrNull()?.onLifecycle(event, cut)
    }
    fun onLifecycle(event: CaptureLifecycleEvent) {
        adapterForLifecycleOrNull()?.onLifecycle(event)
    }
    /**
     * Pause: attempts that requested exposure get [PAUSE_SETTLE_MS] to reach
     * their terminal (published normally) before the rest are cut; a store
     * commit that still completes after the cut is published as well.
     */
    fun onPause() {
        val active = adapterForPauseOrNull() ?: return
        active.awaitExposedAttemptsSettled(PAUSE_SETTLE_MS)
        active.onLifecycle(CaptureLifecycleEvent.BACKGROUNDED)
    }
    fun onViewReplacement() {
        adapterForLifecycleOrNull()?.onLifecycle(CaptureLifecycleEvent.VIEW_REPLACED)
    }
    fun onArSessionReplacement() {
        adapterForLifecycleOrNull()?.onLifecycle(CaptureLifecycleEvent.AR_SESSION_REPLACED)
    }
    fun forceRecoveryForDebug() = adapterForOperation().forceRecoveryForDebug()
    fun replayRecovery(): List<NativeCaptureEventV2> = recoveryForOperation().replaySnapshot()
    fun acknowledgeTerminal(attemptId: String) = recoveryForOperation().acknowledgeTerminal(attemptId)
    fun snapshot() = adapterForOperation().snapshot()

    /** Returns an app-private preview path for a retained committed JPEG. */
    fun materializeJpegPreview(manifestId: String, captureId: String): String? {
        return NativeCapturePreviewOwnerV2(appContext.filesDir).materialize(manifestId, captureId)
    }

    /** Native instrumentation seam; it is never registered with a Flutter channel. */
    internal fun syntheticAdapterForTest(): NativeCaptureAdapterV2 = adapterForOperation()

    internal fun installSyntheticExposureHookForTest(
        request: (CaptureAttemptQualifierV2, Set<CaptureComponentKind>, SharedCameraExposureCallbackV2) -> Boolean,
        cancel: (CaptureAttemptQualifierV2) -> Unit = {},
    ): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val manager = sharedCamera ?: return@synchronized false
        manager.installAttemptQualifiedExposureHookV2(request, cancel)
        true
    }

    private fun durableOwnersForOperation(): Pair<NativeCaptureAdapterV2, NativeCaptureRecoveryDispatcherV2> =
        synchronized(lock) {
            if (closed) throw NativeCaptureBindingClosedV2()
            // Initialize every lazy durable dependency while close is excluded.
            deadlineScheduler
            adapter to recovery
        }

    private fun adapterForOperation(): NativeCaptureAdapterV2 = durableOwnersForOperation().first

    private fun recoveryForOperation(): NativeCaptureRecoveryDispatcherV2 = synchronized(lock) {
        if (closed) throw NativeCaptureBindingClosedV2()
        recovery
    }

    /**
     * A pause settles only attempts of an adapter that admission or a
     * lifecycle event already created. Before that there is nothing to
     * settle, and creating the adapter would acquire the durable root: a view
     * replaced before first use (a quick replay/camera switch) then failed its
     * disposal pause while the replacing view held the root, and kept its
     * ARCore session, so the replacing view never started.
     */
    private fun adapterForPauseOrNull(): NativeCaptureAdapterV2? = synchronized(lock) {
        if (!closed && !adapterDelegate.isInitialized()) null else adapterForLifecycleOrNull()
    }

    private fun adapterForLifecycleOrNull(): NativeCaptureAdapterV2? = synchronized(lock) {
        if (closed) {
            safety.invalidateAll()
            null
        } else {
            deadlineScheduler
            adapter
        }
    }

    override fun close() {
        synchronized(lock) {
            if (resourcesClosed) return
            closed = true
        }
        var closeFailure: Throwable? = null
        fun attempt(operation: () -> Unit) {
            runCatching(operation).onFailure { if (closeFailure == null) closeFailure = it }
        }
        if (deadlineSchedulerDelegate.isInitialized()) attempt { deadlineScheduler.shutdownNow() }
        if (recoveryDelegate.isInitialized()) attempt { recovery.close() }
        if (adapterDelegate.isInitialized()) {
            attempt { adapter.onLifecycle(CaptureLifecycleEvent.VIEW_REPLACED) }
            attempt { adapter.close() }
        }
        val manager = synchronized(lock) { sharedCamera.also { sharedCamera = null } }
        attempt { manager?.clearAttemptQualifiedExposureHookV2() }
        if (rootResourcesDelegate.isInitialized()) {
            attempt { closeDurableResources() }
        } else {
            synchronized(lock) { resourcesClosed = true }
        }
        closeFailure?.let { throw it }
    }

    /** Safe non-blocking half-close for the serial owner's shutdown deadline. */
    internal fun forceCloseForDeadline() {
        synchronized(lock) {
            if (resourcesClosed) return
            closed = true
        }
        if (deadlineSchedulerDelegate.isInitialized()) deadlineScheduler.shutdownNow()
        if (recoveryDelegate.isInitialized()) recovery.close()
        if (adapterDelegate.isInitialized()) adapter.forceCloseForDeadline()
        synchronized(lock) { sharedCamera }?.clearAttemptQualifiedExposureHookV2()
    }

    private fun closeDurableResources() {
        durableRootCloser.close()
        synchronized(lock) { resourcesClosed = durableRootCloser.isClosed() }
    }

    private fun createNativeCaptureRootResourcesV2(context: Context): NativeCaptureDurableRootV2 =
        NativeCaptureDurableRootV2.open(java.io.File(context.filesDir, NATIVE_CAPTURE_ROOT_DIRECTORY)).also { opened ->
            NativeCaptureOpenRootStoresV2.register(opened.leaseKey, opened.store)
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
 * Exact-cut signal consumed by capture ingress.  It is false until a V2 owner has both a
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

    fun isCaptureSafeFor(qualifier: CaptureAttemptQualifierV2): Boolean = synchronized(lock) {
        binding == qualifier && ownerLive && durableAccepted
    }

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
    private val retainAssessedOriginals: Boolean = false,
    private val durableTerminalTap: DurableTerminalTap = VisibilityGridShellRegistry.durableTerminalTap,
) : AutoCloseable {
    private fun captureLatencyLog(stage: String, attemptId: String, detail: String = "") {
        Log.i(
            "CaptureLatency",
            "stage=$stage attemptId=$attemptId elapsedRealtimeNs=${SystemClock.elapsedRealtimeNanos()}$detail",
        )
    }

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
        var exposureTimestampNs: Long = 0L,
        /** Store terminal that arrived while a pause transfer was querying the store. */
        var pauseTerminal: CaptureReceipt? = null,
    )
    private val lock = ReentrantLock(true)
    private val storeIdle = lock.newCondition()
    private val attemptSettled = lock.newCondition()
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

    private fun replayFenceForAdmission(request: CaptureCommitRequest): CaptureReceipt? = try {
        // Read capability is independent of the producer mode: an assessed
        // terminal already on disk must replay after an OFF restart.
        store.replayAssessedFenceBeforeExposure(request)
    } catch (error: AssessedReplayUnavailableV2) {
        // Keep direct legacy/fake store entrypoints usable; real durable ports
        // implement the disposition-aware lookup and preserve conflicts.
        if (retainAssessedOriginals) throw error
        store.replayFenceBeforeExposure(request)
    }

    fun admit(request: CaptureCommitRequest): CaptureReceipt = admissionLock.withLock { lock.withLock {
        captureLatencyLog("native_admit_start", request.accepted.identity.attemptId)
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
        storeCallLocked {
            replayFenceForAdmission(request)
        }?.let { replay ->
            if (replay.terminal != null) return@withLock replay
            // A durable accepted identity from a prior owner is outcome-unknown.
            // Fence it metadata-only; never infer that a second shutter is safe.
            return@withLock abandonLocked(request, "durable-replay-no-reexposure")
        }
        check(!lifecycleCutDuringAdmission() && !closing) { "lifecycle-cut-during-admission" }
        captureLatencyLog("native_preexposure_start", accepted.identity.attemptId)

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
        captureLatencyLog("native_preexposure_accepted", accepted.identity.attemptId)
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
                    CaptureLifecycleEvent.BACKGROUNDED -> lifecycleTransferLocked(value, PAUSE_LIFECYCLE_REASON)
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
            attemptSettled.signalAll()
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
            captureLatencyLog("native_camera_request_start", value.request.accepted.identity.attemptId)
            try {
                requested = externalCallLocked {
                    exposure.requestExposure(value.qualifier, value.request.accepted.profile.requiredComponents, callback, value.request.accepted.profile.maximumWorkingBytes)
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
                val reason = submissionFailure?.let { "camera-$it" }
                    ?: pendingLifecycleReason?.let { "$it-before-exposure" }
                    ?: "exposure-request-rejected"
                captureLatencyLog("native_camera_request_refused", value.request.accepted.identity.attemptId, " reason=$reason")
                abandonLocked(value.request, reason)
                return
            }
            captureLatencyLog("native_camera_request_accepted", value.request.accepted.identity.attemptId)
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
        override val retainAssessedOriginals: Boolean get() = this@NativeCaptureAdapterV2.retainAssessedOriginals
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
        value.exposureTimestampNs = components.exposureTimestampNanoseconds
        val kinds = components.streams.map { it.kind }
        if (kinds.size != kinds.toSet().size || kinds.toSet() != value.request.accepted.profile.requiredComponents) {
            closeStreams(components.streams)
            abandonLocked(value.request, "malformed-component-set")
            return
        }
        if (retainAssessedOriginals != (components.qualityAssessment != null)) {
            closeStreams(components.streams)
            abandonLocked(value.request, "quality-assessment-gate-mismatch")
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
                if (retainAssessedOriginals) store.commitStreamedAssessed(
                    value.request, components.exposureTimestampNanoseconds, components.streams,
                    requireNotNull(components.qualityAssessment),
                ) else store.commitStreamed(
                    value.request, components.exposureTimestampNanoseconds, components.streams,
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
            // Transferred by a pause while the store committed: the view stays, so the
            // durable terminal is still published (and retained for replay).
            if (value.pendingLifecycleReason == PAUSE_LIFECYCLE_REASON && receipt?.terminal != null) {
                publishTerminalAfterPauseTransferLocked(value, checkNotNull(receipt))
            }
            propagatedStoreFailure?.let { throw it }
            return
        }
        val cutReason = value.pendingLifecycleReason
        when {
            cutReason == PAUSE_LIFECYCLE_REASON && value.lifecycleTransferInProgress && receipt?.terminal != null ->
                value.pauseTerminal = receipt
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
        // that terminal on the closing view would publish after the cut. A
        // pause (backgrounded) keeps the view: its terminal is published.
        val receipt = storeCallLocked { store.queryReceipt(value.request.accepted.identity) }
            ?.takeIf { it.terminal != null } ?: value.pauseTerminal
        if (work[value.request.accepted.identity] !== value) return
        if (value.pendingLifecycleReason == PAUSE_LIFECYCLE_REASON && receipt?.terminal != null) {
            finishLocked(value, receipt)
            return
        }
        safety.release(value.qualifier)
        work.remove(value.request.accepted.identity)
        scheduler.release(value.request.accepted.identity)
        attemptSettled.signalAll()
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
        attemptSettled.signalAll()
        value?.let { safety.release(it.qualifier) }
        value?.let { externalCallLocked { exposure.cancelExposure(it.qualifier) } }
        if (value != null) {
            counters.terminalResolved(identity)
            if (value.exposureCounted) {
                if (terminal.kind == CaptureTerminalKind.COMMITTED_PICTURE) counters.committed() else counters.abandoned()
            }
        }
        emitTerminalLocked(value, receipt, recoveryContext)
        if (!lifecycleDrain) scheduler.running?.let { next -> work[next.identity]?.let(::startLocked) }
    }

    private fun emitTerminalLocked(value: Work?, receipt: CaptureReceipt, recoveryContext: NativeCaptureRecoveryContextV2?) {
        val identity = receipt.identity
        val terminal = checkNotNull(receipt.terminal)
        // Every live terminal is visible next to the admission stages.
        captureLatencyLog("native_terminal", identity.attemptId, " kind=${terminal.kind} reason=${terminal.reason}")
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
                jpegSizeBytes = terminal.jpegSizeBytes,
                heicSizeBytes = terminal.heicSizeBytes,
                dngSizeBytes = terminal.dngSizeBytes,
                reason = terminal.reason,
                recoveryContext = recoveryContext,
                qualityAssessment = terminal.qualityAssessment,
                qualityAssessmentHash = terminal.qualityAssessmentHash,
                verifiedTerminal = terminal,
            ),
        )
        emitLocked(NativeCaptureEventV2(NativeCaptureEventKindV2.HEALTH, resources = snapshot()))
        // Visibility grid photo saved seam: once per claimed durable terminal, outside the lock.
        if (value != null && terminal.kind == CaptureTerminalKind.COMMITTED_PICTURE) {
            val exposureNs = if (value.exposureTimestampNs > 0L) value.exposureTimestampNs
                else value.request.exposureTimestampNanoseconds
            externalCallLocked { runCatching { durableTerminalTap.onDurableTerminal(value.request, terminal, exposureNs) } }
        }
    }

    /**
     * A pause transferred [value] to recovery while its store commit was still
     * running, and the commit reached the terminal of [receipt]. The view was
     * only paused, so the terminal is published once (the app settles the
     * attempt and the recovery replay retains it) instead of only after a
     * restart.
     */
    private fun publishTerminalAfterPauseTransferLocked(value: Work, receipt: CaptureReceipt) {
        if (value.terminalClaimed) return
        value.terminalClaimed = true
        if (value.exposureCounted) {
            if (receipt.terminal?.kind == CaptureTerminalKind.COMMITTED_PICTURE) counters.committed() else counters.abandoned()
        }
        emitTerminalLocked(value, receipt, value.request.recoveryContext)
    }

    /**
     * Waits up to [timeoutMs] until every attempt that had requested exposure
     * when called reached a terminal or left this owner, so a pause cuts only
     * attempts the camera or store did not finish in time. Attempts still
     * waiting for funding are not waited for (the pause abandons them before
     * exposure). Returns whether all of them settled.
     */
    fun awaitExposedAttemptsSettled(timeoutMs: Long): Boolean = lock.withLock {
        val exposed = work.values.filter { it.exposureRequested }
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (exposed.any { work[it.request.accepted.identity] === it }) {
            if (remaining <= 0L) return@withLock false
            remaining = attemptSettled.awaitNanos(remaining)
        }
        true
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
        const val PAUSE_LIFECYCLE_REASON = "backgrounded"
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
