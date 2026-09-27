package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * A finite, debug-only synthetic scene controller.
 *
 * The controller owns only fixture/scenario sequencing. Observation data still
 * enters [AndroidVisibilityGridRuntime] through [SyntheticVisibilityObservationSource],
 * and product state is exposed through the read-only hooks supplied by the
 * production owner. No product counter or selector can be written by this
 * channel.
 */
internal class VisibilitySmallSceneDebugChannel(
    messenger: BinaryMessenger,
    viewId: Int,
    private val isDebuggable: Boolean,
    private val runtime: AndroidVisibilityGridRuntime,
    private val ownership: () -> VisibilityObservationOwnership?,
    private val referencePose: () -> DoubleArray? = { null },
    private val productHooks: VisibilitySmallSceneProductHooks =
        VisibilitySmallSceneProductHooks.NONE,
) : MethodChannel.MethodCallHandler {
    private val channel = MethodChannel(messenger, "visibility_scenario_v2_$viewId")
    private val source = SyntheticVisibilityObservationSource(runtime, ownership)
    private val lock = Any()

    private var scenarioId: String? = null
    private var preparedDepthCapability: VisibilityDepthCapability? = null
    private var lastSequence = 0L
    private val acceptedCommands = LinkedHashMap<Long, AcceptedSmallSceneCommand>()
    private var completedDisarm: AcceptedSmallSceneCommand? = null
    private var runtimePausedByDisarm = false
    private var disposed = false

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (!isDebuggable) {
            result.error(
                "VG_SCENARIO_UNAVAILABLE",
                "synthetic scene orchestration is debug-only",
                null,
            )
            return
        }
        try {
            synchronized(lock) {
                check(!disposed) { "synthetic scene channel is disposed" }
                when (call.method) {
                    "prepare" -> {
                        prepare(call)
                        result.success(null)
                    }
                    "arm" -> result.success(arm(call))
                    "emit" -> result.success(runStep(call, parseStep(call.argument<String>("step"))))
                    "setFault" -> result.success(runFault(call))
                    "pauseResume" -> result.success(runPauseResume(call))
                    "snapshot" -> result.success(runCommand(call, "SNAPSHOT") {})
                    "disarm" -> result.success(disarm(call))
                    else -> result.notImplemented()
                }
            }
        } catch (error: Exception) {
            result.error("VG_SCENARIO_REJECTED", error.message, null)
        }
    }

    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            scenarioId = null
            preparedDepthCapability = null
            lastSequence = 0L
            acceptedCommands.clear()
            completedDisarm = null
            runtimePausedByDisarm = false
            productHooks.clearPoseFixture()
        }
        channel.setMethodCallHandler(null)
    }

    /**
     * Fences real producer callbacks before observation ownership is published.
     *
     * This deliberately has no scenario identity or sequence: ARM remains the
     * first retained command and still validates the exact ownership cut.
     */
    private fun prepare(call: MethodCall) {
        check(scenarioId == null) { "synthetic scene is already armed" }
        val capability = parseCapability(call.argument<String>("depthCapability"))
        val prepared = preparedDepthCapability
        check(prepared == null || prepared == capability) {
            "depth capability does not match the prepared synthetic source"
        }
        source.setDepthCapability(capability)
        preparedDepthCapability = capability
    }

    private fun arm(call: MethodCall): Map<String, Any?> {
        val requestedScenario = call.requiredScenarioId()
        val capability = parseCapability(call.argument<String>("depthCapability"))
        check(preparedDepthCapability == null || preparedDepthCapability == capability) {
            "depth capability does not match the prepared synthetic source"
        }
        val sequence = call.requiredSequence()
        val expectedBindingGeneration =
            call.requiredPositiveLong("expectedBindingGeneration")
        val expectedGroupGeneration =
            call.requiredPositiveLong("expectedGroupGeneration")
        val commandKey = listOf(
            requestedScenario,
            sequence,
            SmallSceneStep.ARM.wireName,
            capability.wireName,
            expectedBindingGeneration,
            expectedGroupGeneration,
        ).joinToString(":")
        if (scenarioId == requestedScenario) {
            return replayOrReject(sequence, commandKey)
                ?: error("ARM sequence is not retained")
        }
        check(scenarioId == null) { "a scenario is already armed" }
        check(sequence == 1L) { "ARM must use sequence 1" }
        val current = checkNotNull(runtimeOwnership()) {
            "native observation ownership is not ready"
        }
        check(expectedBindingGeneration == current.bindingGeneration) {
            "binding generation does not match"
        }
        check(expectedGroupGeneration == current.groupGeneration) {
            "group generation does not match"
        }
        if (runtimePausedByDisarm) {
            check(runtime.resume()) { "runtime could not resume after DISARM" }
            runtimePausedByDisarm = false
        }

        source.setDepthCapability(capability)
        scenarioId = requestedScenario
        lastSequence = sequence
        acceptedCommands.clear()
        completedDisarm = null
        return rememberReceipt(sequence, commandKey)
    }

    private fun runStep(call: MethodCall, step: SmallSceneStep): Map<String, Any?> =
        runCommand(call, step.wireName) {
            when (step) {
                SmallSceneStep.POPULATE_WALL -> {
                    val current = checkNotNull(runtimeOwnership()) {
                        "native observation ownership is not ready"
                    }
                    check(productHooks.beginPoseFixture()) {
                        "live tracked pose is not ready for the synthetic camera fixture"
                    }
                    val pose = checkNotNull(referencePose()) {
                        "live reference pose is not ready for the wall fixture"
                    }
                    source.anchor(pose, current.groupFrame)
                    emitFixture(intArrayOf(0, 1, 2, 3), 1_000_000_000L)
                }
                SmallSceneStep.POPULATE_CORNER -> emitFixture(intArrayOf(4, 5, 6, 7), 2_000_000_000L)
                SmallSceneStep.ADD_FOREGROUND_OCCLUDER ->
                    emitFixture(intArrayOf(8, 9, 10, 11), 3_000_000_000L)
                SmallSceneStep.DEPTH_AFTER_PUBLICATION -> {
                    runtime.awaitDebugFixtureIdle()
                    check(source.emitDepth(4_000_000_000L, 0, 0)) {
                        "post-publication depth observation was rejected at copied ingress"
                    }
                }
                SmallSceneStep.DEPTH_COMMIT_PREPARE -> {
                    runtime.awaitDebugFixtureIdle()
                    repeat(3) { index ->
                        check(source.emitDepth(6_000_000_000L + index * DEPTH_INTERVAL_NS, 30, 30)) {
                            "preparatory depth observation was rejected at copied ingress"
                        }
                        runtime.awaitDebugFixtureIdle()
                    }
                }
                SmallSceneStep.DEPTH_COMMIT_FAULT -> {
                    runtime.awaitDebugFixtureIdle()
                    check(source.emitDepth(7_000_000_000L, 30, 30)) {
                        "faulted depth observation was rejected at copied ingress"
                    }
                }
                SmallSceneStep.DEPTH_COMMIT_RETRY -> {
                    runtime.awaitDebugFixtureIdle()
                    // Either observation lane can retry the retained depth
                    // mutation. A feature wake avoids staging new depth
                    // evidence while the previous depth cut is retained.
                    check(source.emitFeature(7_250_000_000L, 31, 30)) {
                        "feature wake for retained depth commit was rejected at copied ingress"
                    }
                }
                SmallSceneStep.OVER_OFFER -> {
                    // Four times the ordinary producer cadence, with bounded
                    // finite input and no direct product-state mutation.
                    repeat(16) { index ->
                        source.emitFeature(
                            4_500_000_000L + index * (FEATURE_INTERVAL_NS / 4),
                            100 + index,
                            index % 4,
                        )
                        if (index % 2 == 0) {
                            source.emitDepth(
                                4_500_000_000L + (index / 2) * (DEPTH_INTERVAL_NS / 4),
                                100 + index,
                                index % 4,
                            )
                        }
                    }
                }
                SmallSceneStep.MAXIMUM_SAMPLES -> {
                    runtime.awaitDebugFixtureIdle()
                    val (featureAccepted, depthAccepted) = source.emitMaximumSamples(
                        featureTimestampNs = 8_000_000_000L,
                        depthTimestampNs = 8_250_000_000L,
                    )
                    check(featureAccepted && depthAccepted) {
                        "maximum synthetic feature/depth observations were not copied"
                    }
                    runtime.awaitDebugFixtureIdle()
                }
                SmallSceneStep.MAXIMUM_DEPTH_RETRY -> {
                    runtime.awaitDebugFixtureIdle()
                    check(source.emitMaximumDepth(8_500_000_000L)) {
                        "fresh maximum synthetic depth observation was not copied"
                    }
                    runtime.awaitDebugFixtureIdle()
                }
                SmallSceneStep.SECOND_VIEW -> {
                    runtime.awaitDebugFixtureIdle()
                    productHooks.manualViewPose()
                    val current = checkNotNull(runtimeOwnership()) {
                        "native observation ownership is not ready"
                    }
                    val commandPose = checkNotNull(referencePose()) {
                        "live reference pose is not ready for SECOND_VIEW"
                    }.copyOf()
                    VisibilityCameraPose.copyOf(commandPose)
                    source.anchor(commandPose, current.groupFrame)
                    // Keep the committed-picture acceptance fixture on the
                    // exact optical axis of the command-time camera. The
                    // external journey supplies the distinct translated
                    // viewpoint; an additional lateral marker offset only
                    // makes centroid rounding and capture-time pose alignment
                    // unnecessarily fragile.
                    emitFixture(
                        markers = intArrayOf(20),
                        firstTimestampNs = 5_000_000_000L,
                        lateralMarker = 0,
                    )
                }
                SmallSceneStep.SEVERE_PRESSURE -> {
                    runtime.awaitDebugFixtureIdle()
                    check(runtime.snapshot().captureSafe) {
                        "severe feature-only pressure requires a live capture-safe owner"
                    }
                    // The production p95 window can already contain a full
                    // history of fast AR callbacks. Supply only enough slow
                    // callbacks to cross that rolling percentile.
                    for (sample in 0 until 32) {
                        check(source.emitFeature(
                            6_000_000_000L + sample,
                            marker = 40,
                            lateralMarker = 0,
                            callbackCopyNs = 2_100_000L,
                        )) { "synthetic over-budget callback was not copied" }
                        if (runtime.snapshot().callbackCopyBudgetState ==
                            "severeDepthShedCaptureSafeFeature1Hz") break
                    }
                    check(runtime.snapshot().callbackCopyBudgetState ==
                        "severeDepthShedCaptureSafeFeature1Hz") {
                        "over-budget callback did not trigger capture-safe depth shedding"
                    }
                    check(!source.emitDepth(6_000_001_000L, marker = 40, lateralMarker = 0)) {
                        "depth must stop before capture-safe feature intake"
                    }
                }
                SmallSceneStep.AUTOMATIC_REVISIT -> productHooks.automaticRevisitPose()
                else -> error("step is not an observation fixture")
            }
        }

    private fun runFault(call: MethodCall): Map<String, Any?> {
        val fault = when (call.argument<String>("fault")) {
            "rendererUnavailable" -> SmallSceneFault.RENDERER_UNAVAILABLE
            "rendererRecovered" -> SmallSceneFault.RENDERER_RECOVERED
            "guidanceTerminal" -> SmallSceneFault.GUIDANCE_TERMINAL
            "canonicalRetryableDepthCommit" -> SmallSceneFault.CANONICAL_RETRYABLE_DEPTH_COMMIT
            "rendererAllocationFailure" -> SmallSceneFault.RENDERER_ALLOCATION_FAILURE
            else -> error("unknown synthetic scene fault")
        }
        return runCommand(call, fault.wireName) {
            when (fault) {
                SmallSceneFault.RENDERER_UNAVAILABLE -> productHooks.rendererUnavailable()
                SmallSceneFault.RENDERER_RECOVERED -> productHooks.rendererRecovered()
                SmallSceneFault.GUIDANCE_TERMINAL -> productHooks.guidanceTerminal()
                SmallSceneFault.CANONICAL_RETRYABLE_DEPTH_COMMIT ->
                    productHooks.canonicalRetryableDepthCommit()
                SmallSceneFault.RENDERER_ALLOCATION_FAILURE ->
                    productHooks.rendererAllocationFailure()
            }
        }
    }

    private fun runPauseResume(call: MethodCall): Map<String, Any?> =
        runCommand(call, "pauseResume") {
            runtime.pause()
            productHooks.pause()
            productHooks.resume()
            check(runtime.resume()) { "runtime could not resume the current ownership cut" }
        }

    private fun disarm(call: MethodCall): Map<String, Any?> {
        val requestedScenario = call.requiredScenarioId()
        val sequence = call.requiredSequence()
        val commandKey = "$requestedScenario:$sequence:${SmallSceneStep.DISARM.wireName}"
        if (scenarioId == null) {
            val completed = completedDisarm
            check(completed != null && completed.commandKey == commandKey) {
                "ARM is required before a scene command"
            }
            return completed.receipt
        }
        val receipt = runCommand(call, SmallSceneStep.DISARM.wireName) {
            runtime.pause()
            runtimePausedByDisarm = true
            productHooks.clearPoseFixture()
        }
        completedDisarm = checkNotNull(acceptedCommands[sequence])
        scenarioId = null
        lastSequence = 0L
        acceptedCommands.clear()
        return receipt
    }

    private fun runCommand(
        call: MethodCall,
        commandName: String,
        action: () -> Unit,
    ): Map<String, Any?> {
        val activeScenario = checkNotNull(scenarioId) {
            "ARM is required before a scene command"
        }
        val requestedScenario = call.requiredScenarioId()
        check(requestedScenario == activeScenario) { "scenario ID does not match the armed scene" }
        val sequence = call.requiredSequence()
        val commandKey = "$activeScenario:$sequence:$commandName"
        val replay = replayOrReject(sequence, commandKey)
        if (replay != null) return replay
        check(sequence == lastSequence + 1L) {
            "scene sequence must be adjacent after $lastSequence"
        }
        check(sequence <= MAX_COMMAND_SEQUENCE) { "synthetic scene command limit exceeded" }
        action()
        lastSequence = sequence
        return rememberReceipt(sequence, commandKey)
    }

    private fun emitFixture(
        markers: IntArray,
        firstTimestampNs: Long,
        lateralMarker: Int? = null,
    ) {
        var featureTimestamp = firstTimestampNs
        var depthTimestamp = firstTimestampNs
        markers.forEach { marker ->
            source.emitFeature(featureTimestamp, marker, lateralMarker ?: marker)
            source.emitDepth(depthTimestamp, marker, lateralMarker ?: marker)
            featureTimestamp += FEATURE_INTERVAL_NS
            depthTimestamp += DEPTH_INTERVAL_NS
        }
    }

    private fun rememberReceipt(
        sequence: Long,
        commandKey: String,
    ): Map<String, Any?> {
        val receipt = receiptMap(sequence)
        acceptedCommands[sequence] = AcceptedSmallSceneCommand(commandKey, receipt)
        return receipt
    }

    private fun replayOrReject(
        sequence: Long,
        commandKey: String,
    ): Map<String, Any?>? {
        val accepted = acceptedCommands[sequence] ?: return null
        check(accepted.commandKey == commandKey) {
            "scene sequence $sequence was already used by a different command"
        }
        return accepted.receipt
    }

    private fun receiptMap(sequence: Long): Map<String, Any?> {
        val health = runtime.snapshot()
        val provided = productHooks.snapshot()
        return SmallSceneScenarioReceipt(
            scenarioId = checkNotNull(scenarioId),
            sequence = sequence,
            acceptedFeatureObservations = health.copiedFeatureObservations,
            acceptedDepthObservations = health.copiedDepthObservations,
            admittedFeatureObservations = health.admittedFeatureObservations,
            admittedDepthObservations = health.admittedDepthObservations,
            integrationStatus = provided.integrationStatus,
            geometryRevision = provided.geometryRevision,
            lineageRevision = provided.lineageRevision,
            durableCaptureRevision = provided.durableCaptureRevision,
            coverageRevision = provided.coverageRevision,
            styleRevision = provided.styleRevision,
            targetSurfaceId = provided.targetSurfaceId,
            rendererRows = provided.rendererRows,
            automaticEligible = provided.automaticEligible,
            guidanceStatus = provided.guidanceStatus,
            rootIsolateSurfaceBytes = provided.rootIsolateSurfaceBytes,
            resourceBalance = provided.resourceBalance ?: health.resourceBalance,
            callbackCopyP95Micros = health.callbackCopyP95Ns / 1_000L,
            rootIsolateImageBytes = provided.rootIsolateImageBytes,
            rendererOwnedBytes = provided.rendererOwnedBytes,
        ).toMap()
    }

    private fun runtimeOwnership(): VisibilityObservationOwnership? = ownership()

    private fun MethodCall.requiredSequence(): Long =
        requiredPositiveLong("sequence")

    private fun MethodCall.requiredPositiveLong(name: String): Long {
        val value = when (val raw = argument<Any?>(name)) {
            is Int -> raw.toLong()
            is Long -> raw
            null -> error("$name is required")
            else -> error("$name must be an integer")
        }
        require(value > 0L) { "$name must be positive" }
        return value
    }

    private fun MethodCall.requiredScenarioId(): String {
        val value = argument<String>("scenarioId") ?: error("scenarioId is required")
        require(value.length in 1..SCENARIO_ID_MAX_LENGTH)
        require(value.all { it.code in 0x21..0x7e }) { "scenarioId must be printable ASCII" }
        return value
    }

    private fun parseCapability(value: String?): VisibilityDepthCapability = when (value) {
        "unsupported" -> VisibilityDepthCapability.UNSUPPORTED
        "rawDepth" -> VisibilityDepthCapability.RAW_DEPTH
        "automatic" -> VisibilityDepthCapability.AUTOMATIC
        else -> error("unknown synthetic depth capability")
    }

    private fun parseStep(value: String?): SmallSceneStep = when (value) {
        "wall" -> SmallSceneStep.POPULATE_WALL
        "corner" -> SmallSceneStep.POPULATE_CORNER
        "foregroundOccluder" -> SmallSceneStep.ADD_FOREGROUND_OCCLUDER
        "depthAfterPublication" -> SmallSceneStep.DEPTH_AFTER_PUBLICATION
        "depthCommitPrepare" -> SmallSceneStep.DEPTH_COMMIT_PREPARE
        "depthCommitFault" -> SmallSceneStep.DEPTH_COMMIT_FAULT
        "depthCommitRetry" -> SmallSceneStep.DEPTH_COMMIT_RETRY
        "overOffer" -> SmallSceneStep.OVER_OFFER
        "maximumSamples" -> SmallSceneStep.MAXIMUM_SAMPLES
        "maximumDepthRetry" -> SmallSceneStep.MAXIMUM_DEPTH_RETRY
        "secondView" -> SmallSceneStep.SECOND_VIEW
        "severePressure" -> SmallSceneStep.SEVERE_PRESSURE
        "automaticRevisit" -> SmallSceneStep.AUTOMATIC_REVISIT
        else -> error("unknown synthetic scene step")
    }

    private enum class SmallSceneFault(val wireName: String) {
        RENDERER_UNAVAILABLE("rendererUnavailable"),
        RENDERER_RECOVERED("rendererRecovered"),
        GUIDANCE_TERMINAL("guidanceTerminal"),
        CANONICAL_RETRYABLE_DEPTH_COMMIT("canonicalRetryableDepthCommit"),
        RENDERER_ALLOCATION_FAILURE("rendererAllocationFailure"),
    }

    companion object {
        private const val SCENARIO_ID_MAX_LENGTH = 64
        private const val MAX_COMMAND_SEQUENCE = 32L
        private const val FEATURE_INTERVAL_NS = 125_000_000L
        private const val DEPTH_INTERVAL_NS = 250_000_000L
    }
}

private data class AcceptedSmallSceneCommand(
    val commandKey: String,
    val receipt: Map<String, Any?>,
)

internal enum class SmallSceneStep(val wireName: String) {
    ARM("ARM"),
    POPULATE_WALL("wall"),
    POPULATE_CORNER("corner"),
    ADD_FOREGROUND_OCCLUDER("foregroundOccluder"),
    DEPTH_AFTER_PUBLICATION("depthAfterPublication"),
    DEPTH_COMMIT_PREPARE("depthCommitPrepare"),
    DEPTH_COMMIT_FAULT("depthCommitFault"),
    DEPTH_COMMIT_RETRY("depthCommitRetry"),
    OVER_OFFER("overOffer"),
    MAXIMUM_SAMPLES("maximumSamples"),
    MAXIMUM_DEPTH_RETRY("maximumDepthRetry"),
    SECOND_VIEW("secondView"),
    SEVERE_PRESSURE("severePressure"),
    AUTOMATIC_REVISIT("automaticRevisit"),
    RENDERER_LOSS("rendererUnavailable"),
    RENDERER_RESTORE("rendererRecovered"),
    GUIDANCE_FAILURE("guidanceTerminal"),
    PAUSE_RESUME("pauseResume"),
    SNAPSHOT("SNAPSHOT"),
    DISARM("DISARM"),
}

internal data class SmallSceneScenarioReceipt(
    val scenarioId: String,
    val sequence: Long,
    val acceptedFeatureObservations: Long,
    val acceptedDepthObservations: Long,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val durableCaptureRevision: Long,
    val coverageRevision: Long,
    val styleRevision: Long,
    val targetSurfaceId: Long?,
    val rendererRows: Int,
    val automaticEligible: Boolean,
    val guidanceStatus: String,
    val rootIsolateSurfaceBytes: Long,
    val resourceBalance: Long,
    val callbackCopyP95Micros: Long = 0L,
    val rootIsolateImageBytes: Long = 0L,
    val rendererOwnedBytes: Long = 0L,
    val admittedFeatureObservations: Long = 0L,
    val admittedDepthObservations: Long = 0L,
    val integrationStatus: String = "starting",
) {
    init {
        require(scenarioId.isNotEmpty() && scenarioId.length <= 64)
        require(sequence > 0L)
        require(acceptedFeatureObservations >= 0L)
        require(acceptedDepthObservations >= 0L)
        require(geometryRevision >= 0L)
        require(lineageRevision >= 0L)
        require(durableCaptureRevision >= 0L)
        require(coverageRevision >= 0L)
        require(styleRevision >= 0L)
        require(targetSurfaceId == null || targetSurfaceId >= 0L)
        require(rendererRows >= 0)
        require(guidanceStatus.isNotEmpty() && guidanceStatus.length <= 64)
        require(rootIsolateSurfaceBytes >= 0L)
        require(resourceBalance >= 0L)
        require(callbackCopyP95Micros >= 0L)
        require(rootIsolateImageBytes >= 0L)
        require(rendererOwnedBytes >= 0L)
        require(admittedFeatureObservations >= 0L)
        require(admittedDepthObservations >= 0L)
        require(integrationStatus.isNotEmpty() && integrationStatus.length <= 64)
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "scenarioId" to scenarioId,
        "sequence" to sequence,
        "acceptedFeatureObservations" to acceptedFeatureObservations,
        "acceptedDepthObservations" to acceptedDepthObservations,
        "geometryRevision" to geometryRevision,
        "lineageRevision" to lineageRevision,
        "durableCaptureRevision" to durableCaptureRevision,
        "coverageRevision" to coverageRevision,
        "styleRevision" to styleRevision,
        "targetSurfaceId" to targetSurfaceId,
        "rendererRows" to rendererRows,
        "automaticEligible" to automaticEligible,
        "guidanceStatus" to guidanceStatus,
        "rootIsolateSurfaceBytes" to rootIsolateSurfaceBytes,
        "resourceBalance" to resourceBalance,
        "callbackCopyP95Micros" to callbackCopyP95Micros,
        "rootIsolateImageBytes" to rootIsolateImageBytes,
        "rendererOwnedBytes" to rendererOwnedBytes,
        "admittedFeatureObservations" to admittedFeatureObservations,
        "admittedDepthObservations" to admittedDepthObservations,
        "integrationStatus" to integrationStatus,
    )
}

/** Production-owner actions plus read-only scalars for the eventual ArView hook. */
internal interface VisibilitySmallSceneProductHooks {
    fun beginPoseFixture(): Boolean = true
    fun manualViewPose() {}
    fun automaticRevisitPose() {}
    fun clearPoseFixture() {}
    fun rendererUnavailable() { error("renderer-unavailable production hook is not wired") }
    fun rendererRecovered() { error("renderer-recovered production hook is not wired") }
    fun guidanceTerminal() { error("guidance-terminal production hook is not wired") }
    fun canonicalRetryableDepthCommit() {
        error("canonical retryable-depth-commit production hook is not wired")
    }
    fun rendererAllocationFailure() {
        error("renderer-allocation-failure production hook is not wired")
    }
    fun pause() { error("pause production hook is not wired") }
    fun resume() { error("resume production hook is not wired") }
    fun snapshot(): VisibilitySmallSceneReceiptScalars = VisibilitySmallSceneReceiptScalars()

    companion object {
        val NONE: VisibilitySmallSceneProductHooks = object : VisibilitySmallSceneProductHooks {}
    }
}

/** Scalar product-owned values copied into a scenario receipt; never writable by the channel. */
internal data class VisibilitySmallSceneReceiptScalars(
    val geometryRevision: Long = 0L,
    val lineageRevision: Long = 0L,
    val durableCaptureRevision: Long = 0L,
    val coverageRevision: Long = 0L,
    val styleRevision: Long = 0L,
    val targetSurfaceId: Long? = null,
    val rendererRows: Int = 0,
    val automaticEligible: Boolean = false,
    val guidanceStatus: String = "starting",
    val rootIsolateSurfaceBytes: Long = 0L,
    val resourceBalance: Long? = null,
    val rootIsolateImageBytes: Long = 0L,
    val rendererOwnedBytes: Long = 0L,
    val integrationStatus: String = "starting",
) {
    init {
        require(geometryRevision >= 0L)
        require(lineageRevision >= 0L)
        require(durableCaptureRevision >= 0L)
        require(coverageRevision >= 0L)
        require(styleRevision >= 0L)
        require(targetSurfaceId == null || targetSurfaceId >= 0L)
        require(rendererRows >= 0)
        require(guidanceStatus.length in 1..64)
        require(guidanceStatus.all { it.code in 0x21..0x7e })
        require(rootIsolateSurfaceBytes >= 0L)
        require(resourceBalance == null || resourceBalance >= 0L)
        require(rootIsolateImageBytes >= 0L)
        require(rendererOwnedBytes >= 0L)
    }
}
