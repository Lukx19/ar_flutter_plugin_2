package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

class NativeVisibilityGrid(
    private val featureConfig: VisibilityGridFeatureConfig,
    private val depthConfig: VisibilityGridDepthConfig? = null,
) {
    private enum class FeatureHealth(val wireName: String) {
        CONFIGURED("configured"),
        HEALTHY("healthy"),
        TRANSIENT_UNAVAILABLE("transientUnavailable"),
        FAILED("failed"),
    }

    private enum class PendingGeometryState {
        UPSERT,
        REMOVAL,
    }

    private enum class DepthHealth(val wireName: String) {
        UNSUPPORTED("unsupported"),
        CONFIGURED("configured"),
        HEALTHY("healthy"),
        TRANSIENT_UNAVAILABLE("transientUnavailable"),
        FAILED("failed"),
    }

    private data class Position(
        val x: Double,
        val y: Double,
        val z: Double,
    ) {
        operator fun get(axis: Int): Double =
            when (axis) {
                0 -> x
                1 -> y
                else -> z
            }
    }

    private data class Track(
        var filtered: Position,
        var sampleCount: Int,
        val sampleSums: DoubleArray,
        val sampleSquareSums: DoubleArray,
        var firstTimestampNs: Long,
        var lastTimestampNs: Long,
        var stableKey: Long? = null,
        var stableCoordinates: IntArray? = null,
    )

    private data class DepthEvidence(
        var occupied: Int = 0,
        var free: Int = 0,
        var directionMask: Int = 0,
        var contradicted: Boolean = false,
    )

    private lateinit var group: VisibilityGridGroupConfig
    private val tracks = HashMap<Int, Track>()
    private val supportByKey = HashMap<Long, Int>()
    private val restoredKeys = HashSet<Long>()
    private val visibleKeys = HashSet<Long>()
    private val depthEvidenceByKey = HashMap<Long, DepthEvidence>()
    private val pendingGeometry = HashMap<Long, PendingGeometryState>()
    private var snapshotRequired = false
    private var geometryRevision = 0L
    private var inFlightDelta: VisibilityGridDelta? = null
    private var lastPublicationNs = Long.MIN_VALUE
    private var lastObservationTimestampNs = -1L
    private var acceptedSamples = 0L
    private var rejectedSamples = 0L
    private var capacityRejectedCandidates = 0L
    private var featureHealth = FeatureHealth.CONFIGURED
    private var featureTransientUnavailableCount = 0L
    private var featureFailureCount = 0L
    private var lastFeatureFusionNs = 0L
    private var maxFeatureFusionNs = 0L
    private var lastDepthTimestampNs = -1L
    private var depthHealth =
        if (depthConfig == null) DepthHealth.UNSUPPORTED else DepthHealth.CONFIGURED
    private var consecutiveDepthFailures = 0
    private var depthAcceptedPixels = 0L
    private var depthRejectedPixels = 0L
    private var depthCapacityRejectedPixels = 0L
    private var depthReservations = 0
    private var nonRestoredDepthEvidenceCount = 0
    private var depthRayVisits = 0L
    private var depthTransientUnavailableCount = 0L
    private var depthFailureCount = 0L
    private var lastDepthFusionNs = 0L
    private var maxDepthFusionNs = 0L

    @Synchronized
    fun startGroup(group: VisibilityGridGroupConfig): VisibilityGridSnapshot {
        require(group.capacity <= featureConfig.stableVoxelCapacity)
        require(
            group.restoredKeys.size * RESTORED_VOXEL_WORST_CASE_BYTES <=
                VISIBILITY_GRID_MEMORY_BUDGET_BYTES,
        ) {
            "restored geometry exceeds the 16 MiB state budget"
        }
        if (::group.isInitialized) {
            require(
                group.sessionGeneration > this.group.sessionGeneration ||
                    (
                        group.sessionGeneration == this.group.sessionGeneration &&
                            group.groupGeneration > this.group.groupGeneration
                    ),
            ) {
                "session or group generation must increase"
            }
        }
        this.group = group
        tracks.clear()
        supportByKey.clear()
        restoredKeys.clear()
        restoredKeys.addAll(group.restoredKeys.asList())
        visibleKeys.clear()
        visibleKeys.addAll(group.restoredKeys.asList())
        depthEvidenceByKey.clear()
        pendingGeometry.clear()
        snapshotRequired = false
        geometryRevision = group.restoredGeometryRevision
        inFlightDelta = null
        lastPublicationNs = Long.MIN_VALUE
        lastObservationTimestampNs = -1L
        acceptedSamples = 0
        rejectedSamples = 0
        capacityRejectedCandidates = 0
        featureHealth = FeatureHealth.CONFIGURED
        featureTransientUnavailableCount = 0
        featureFailureCount = 0
        lastFeatureFusionNs = 0
        maxFeatureFusionNs = 0
        lastDepthTimestampNs = -1
        depthHealth =
            if (depthConfig == null) DepthHealth.UNSUPPORTED else DepthHealth.CONFIGURED
        consecutiveDepthFailures = 0
        depthAcceptedPixels = 0
        depthRejectedPixels = 0
        depthCapacityRejectedPixels = 0
        depthReservations = 0
        nonRestoredDepthEvidenceCount = 0
        depthRayVisits = 0
        depthTransientUnavailableCount = 0
        depthFailureCount = 0
        lastDepthFusionNs = 0
        maxDepthFusionNs = 0
        return snapshot()
    }

    @Synchronized
    fun observe(observation: FeatureObservation) {
        check(::group.isInitialized) { "startGroup must be called before observe" }
        require(observation.groupGeneration == group.groupGeneration) {
            "stale group generation"
        }
        require(observation.sessionGeneration == group.sessionGeneration) {
            "stale session generation"
        }
        val startedNs = System.nanoTime()
        try {
            if (observation.timestampNs <= lastObservationTimestampNs) {
                rejectedSamples += observation.samples.size
                return
            }
            lastObservationTimestampNs = observation.timestampNs
            expireCandidates(observation.timestampNs)

            val accepted =
                if (
                    observation.sanitized &&
                    observation.samples.size <= featureConfig.maxFeaturesPerObservation
                ) {
                    rejectedSamples += observation.sourceRejectedSamples
                    observation.samples
                } else {
                    sanitizeAndBound(observation.samples)
                }
            accepted.forEach { sample ->
                val position = transformToGroup(sample)
                if (!isQuantizable(position)) {
                    rejectedSamples++
                    return@forEach
                }
                val track = tracks[sample.id]
                if (track == null) {
                    if (tracks.size >= featureConfig.featureTrackCapacity ||
                        relocationReservationCount() >= group.capacity ||
                        !canAdmitFeatureAssociation()
                    ) {
                        capacityRejectedCandidates++
                        return@forEach
                    }
                    tracks[sample.id] =
                        Track(
                            filtered = position,
                            sampleCount = 1,
                            sampleSums = doubleArrayOf(position.x, position.y, position.z),
                            sampleSquareSums =
                                doubleArrayOf(
                                    position.x * position.x,
                                    position.y * position.y,
                                    position.z * position.z,
                                ),
                            firstTimestampNs = observation.timestampNs,
                            lastTimestampNs = observation.timestampNs,
                        )
                    acceptedSamples++
                    promoteIfReady(tracks.getValue(sample.id), observation.timestampNs)
                    return@forEach
                }

                if (track.stableKey == null) {
                    val alpha = (0.15 + 0.35 * sample.confidence).coerceIn(0.15, 0.50)
                    track.filtered = interpolate(track.filtered, position, alpha)
                    addCandidateSample(track, position)
                    track.lastTimestampNs = observation.timestampNs
                    acceptedSamples++
                    promoteIfReady(track, observation.timestampNs)
                } else {
                    updateStableTrack(
                        track = track,
                        position = position,
                        confidence = sample.confidence,
                        timestampNs = observation.timestampNs,
                    )
                    acceptedSamples++
                }
            }
            featureHealth = FeatureHealth.HEALTHY
        } finally {
            lastFeatureFusionNs = System.nanoTime() - startedNs
            maxFeatureFusionNs = maxOf(maxFeatureFusionNs, lastFeatureFusionNs)
        }
    }

    @Synchronized
    fun observeDepth(observation: DepthObservation): DepthFusionResult {
        check(::group.isInitialized) { "startGroup must be called before observeDepth" }
        val config = checkNotNull(depthConfig) { "depth fusion is unsupported" }
        require(observation.imageOrientation == DepthImageOrientation.LANDSCAPE_RIGHT) {
            "Android raw depth must use landscapeRight source orientation"
        }
        require(observation.groupGeneration == group.groupGeneration) {
            "stale group generation"
        }
        require(observation.sessionGeneration == group.sessionGeneration) {
            "stale session generation"
        }
        val startedNs = System.nanoTime()
        try {
            if (!observation.tracking) {
                val rejected = observation.samples.size + observation.sourceRejectedPixels
                depthRejectedPixels += rejected
                return DepthFusionResult(0, rejected, 0)
            }
            if (observation.timestampNs <= lastDepthTimestampNs) {
                val rejected = observation.samples.size + observation.sourceRejectedPixels
                depthRejectedPixels += rejected
                return DepthFusionResult(
                    acceptedPixels = 0,
                    rejectedPixels = rejected,
                    rayVisits = 0,
                    duplicateTimestamp = true,
                )
            }
            lastDepthTimestampNs = observation.timestampNs

            val cameraWorld = transformPoint(observation.worldFromCameraGl, Position(0.0, 0.0, 0.0))
            val cameraGroup = transformPoint(group.groupFromWorldGl, cameraWorld)
            val touched = HashSet<Long>()
            val occupiedKeys = HashSet<Long>()
            val freeDirectionByKey = HashMap<Long, Int>()
            var accepted = 0
            var rejected = observation.sourceRejectedPixels
            var visits = 0
            observation.samples.take(config.maxAcceptedPixelsPerObservation).forEach { sample ->
                val depthMm = sample.depthMillimeters
                val confidence = sample.confidence
                val depthMeters = depthMm / 1_000.0
                if (depthMm <= 0 ||
                    confidence !in config.confidenceMinimum..255 ||
                    !depthMeters.isFinite() ||
                    depthMeters !in config.minimumDepthMeters..config.maximumDepthMeters
                ) {
                    rejected++
                    return@forEach
                }
                val cameraPoint =
                    Position(
                        x = (sample.x - observation.intrinsics.cx) * depthMeters /
                            observation.intrinsics.fx,
                        y = -(sample.y - observation.intrinsics.cy) * depthMeters /
                            observation.intrinsics.fy,
                        z = -depthMeters,
                    )
                val endpointWorld = transformPoint(observation.worldFromCameraGl, cameraPoint)
                val endpointGroup = transformPoint(group.groupFromWorldGl, endpointWorld)
                if (!isQuantizable(endpointGroup)) {
                    rejected++
                    return@forEach
                }
                val endpointKey = keyFor(endpointGroup)
                val endpointEvidence = evidenceFor(endpointKey)
                if (endpointEvidence == null) {
                    depthCapacityRejectedPixels++
                } else {
                    occupiedKeys += endpointKey
                }
                val remainingVisitBudget = config.maxRayVisitsPerObservation - visits
                val rayX = endpointGroup.x - cameraGroup.x
                val rayY = endpointGroup.y - cameraGroup.y
                val rayZ = endpointGroup.z - cameraGroup.z
                if (remainingVisitBudget > 0 &&
                    rayX * rayX + rayY * rayY + rayZ * rayZ >
                    config.safetyBandMeters * config.safetyBandMeters
                ) {
                    val freeKeys =
                        traverseFreeKeys(
                            camera = cameraGroup,
                            endpoint = endpointGroup,
                            maximumVisits = remainingVisitBudget,
                            safetyBandMeters = config.safetyBandMeters,
                        )
                    freeKeys.forEach { key ->
                        val evidence =
                            if (isVisible(key) || key in depthEvidenceByKey) {
                                evidenceFor(key)
                            } else {
                                null
                            }
                        if (evidence != null) {
                            freeDirectionByKey.putIfAbsent(
                                key,
                                directionBin(cameraGroup, voxelCenter(key)),
                            )
                        }
                    }
                    visits += freeKeys.size
                }
                accepted++
            }
            rejected +=
                (observation.samples.size - config.maxAcceptedPixelsPerObservation)
                    .coerceAtLeast(0)
            occupiedKeys.forEach { key ->
                depthEvidenceByKey.getValue(key).occupied =
                    saturatingIncrement(depthEvidenceByKey.getValue(key).occupied)
                touched += key
            }
            freeDirectionByKey.forEach { (key, directionBin) ->
                depthEvidenceByKey.getValue(key).let { evidence ->
                    evidence.free = saturatingIncrement(evidence.free)
                    evidence.directionMask =
                        evidence.directionMask or (1 shl directionBin)
                }
                touched += key
            }
            touched.forEach(::applyDepthState)
            depthAcceptedPixels += accepted
            depthRejectedPixels += rejected
            depthRayVisits += visits
            depthHealth = DepthHealth.HEALTHY
            consecutiveDepthFailures = 0
            return DepthFusionResult(accepted, rejected, visits)
        } finally {
            lastDepthFusionNs = System.nanoTime() - startedNs
            maxDepthFusionNs = maxOf(maxDepthFusionNs, lastDepthFusionNs)
        }
    }

    @Synchronized
    fun reportDepthTransientUnavailable() {
        if (depthConfig == null || depthHealth == DepthHealth.FAILED) return
        depthHealth = DepthHealth.TRANSIENT_UNAVAILABLE
        depthTransientUnavailableCount++
    }

    @Synchronized
    fun reportDepthFailure() {
        val config = depthConfig ?: return
        if (depthHealth == DepthHealth.FAILED) return
        consecutiveDepthFailures++
        depthFailureCount++
        if (consecutiveDepthFailures >= config.terminalFailureThreshold) {
            depthHealth = DepthHealth.FAILED
        } else {
            depthHealth = DepthHealth.TRANSIENT_UNAVAILABLE
        }
    }

    fun consumeDepth(result: DepthAcquisitionResult): DepthFusionResult? =
        when (result) {
            is DepthAcquisitionResult.Observation -> observeDepth(result.value)
            DepthAcquisitionResult.TransientUnavailable -> {
                reportDepthTransientUnavailable()
                null
            }
            is DepthAcquisitionResult.Failure -> {
                reportDepthFailure()
                null
            }
        }

    fun consumeNext(source: FeatureObservationSource): Boolean {
        val observation =
            try {
                source.poll()
            } catch (_: RuntimeException) {
                synchronized(this) {
                    featureHealth = FeatureHealth.FAILED
                    featureFailureCount++
                }
                return false
            }
        if (observation == null) {
            synchronized(this) {
                featureHealth = FeatureHealth.TRANSIENT_UNAVAILABLE
                featureTransientUnavailableCount++
            }
            return false
        }
        observe(observation)
        return true
    }

    @Synchronized
    fun snapshot(): VisibilityGridSnapshot {
        check(::group.isInitialized) { "startGroup must be called before snapshot" }
        val stableKeys = visibleKeys.sorted()
        return VisibilityGridSnapshot(
            groupId = group.groupId,
            groupGeneration = group.groupGeneration,
            sessionGeneration = group.sessionGeneration,
            geometryRevision =
                geometryRevision +
                    if (pendingGeometry.isEmpty() && !snapshotRequired) 0 else 1,
            stableKeys = stableKeys,
            supportByKey = supportByKey.toSortedMap(),
            diagnostics = diagnostics(),
        )
    }

    @Synchronized
    fun takeGeometryDelta(nowNs: Long = System.nanoTime()): VisibilityGridDelta? {
        check(::group.isInitialized) { "startGroup must be called before publication" }
        inFlightDelta?.let { return it }
        if (pendingGeometry.isEmpty() && !snapshotRequired) return null
        if (
            lastPublicationNs != Long.MIN_VALUE &&
            (nowNs < lastPublicationNs ||
                nowNs - lastPublicationNs < featureConfig.publishIntervalMs * 1_000_000L)
        ) {
            return null
        }
        val nextRevision = geometryRevision + 1
        return createDelta(
            baseRevision = geometryRevision,
            revision = nextRevision,
            reset = snapshotRequired,
            upserts =
                if (snapshotRequired) {
                    visibleKeys.sorted()
                } else {
                    pendingGeometry
                        .filterValues { it == PendingGeometryState.UPSERT }
                        .keys
                        .sorted()
                },
            removals =
                if (snapshotRequired) {
                    emptyList()
                } else {
                    pendingGeometry
                        .filterValues { it == PendingGeometryState.REMOVAL }
                        .keys
                        .sorted()
                },
        ).also {
            geometryRevision = nextRevision
            pendingGeometry.clear()
            snapshotRequired = false
            inFlightDelta = it
            lastPublicationNs = nowNs
        }
    }

    @Synchronized
    fun ackGeometry(acknowledgement: VisibilityGridGeometryAck): Boolean {
        if (
            acknowledgement.wireVersion != VISIBILITY_GRID_WIRE_VERSION ||
            acknowledgement.groupId != group.groupId ||
            acknowledgement.groupGeneration != group.groupGeneration ||
            acknowledgement.sessionGeneration != group.sessionGeneration
        ) {
            return false
        }
        val current =
            inFlightDelta
                ?: return acknowledgement.acceptedGeometryRevision == geometryRevision
        if (current.geometryRevision != acknowledgement.acceptedGeometryRevision) return false
        inFlightDelta = null
        return true
    }

    @Synchronized
    fun requestSnapshot(
        request: VisibilityGridSnapshotRequest,
        nowNs: Long = System.nanoTime(),
    ): VisibilityGridDelta? {
        check(::group.isInitialized) { "startGroup must be called before snapshot" }
        if (
            request.wireVersion != VISIBILITY_GRID_WIRE_VERSION ||
            request.groupId != group.groupId ||
            request.groupGeneration != group.groupGeneration ||
            request.sessionGeneration != group.sessionGeneration ||
            request.receiverGeometryRevision < 0
        ) {
            return null
        }
        val nextRevision = maxOf(geometryRevision, request.receiverGeometryRevision) + 1
        return createDelta(
            baseRevision = request.receiverGeometryRevision,
            revision = nextRevision,
            reset = true,
            upserts = visibleKeys.sorted(),
            removals = emptyList(),
        ).also {
            geometryRevision = nextRevision
            pendingGeometry.clear()
            snapshotRequired = false
            inFlightDelta = it
            lastPublicationNs = nowNs
        }
    }

    private fun promoteIfReady(
        track: Track,
        timestampNs: Long,
    ) {
        if (track.sampleCount < featureConfig.candidateSamples ||
            timestampNs - track.firstTimestampNs < featureConfig.candidateSpanNs ||
            !hasLowVariance(track)
        ) {
            return
        }
        val coordinates = coordinatesFor(track.filtered)
        val key = packVisibilityGridKey(coordinates[0], coordinates[1], coordinates[2])
        if (relocationReservationCount() >= group.capacity) {
            return
        }
        track.stableKey = key
        track.stableCoordinates = coordinates
        attachSupport(key)
    }

    private fun updateStableTrack(
        track: Track,
        position: Position,
        confidence: Double,
        timestampNs: Long,
    ) {
        val previousFiltered = track.filtered
        val jumpX = position.x - previousFiltered.x
        val jumpY = position.y - previousFiltered.y
        val jumpZ = position.z - previousFiltered.z
        val jumpDistanceSquared =
            jumpX * jumpX + jumpY * jumpY + jumpZ * jumpZ
        if (jumpDistanceSquared >=
            featureConfig.jumpResetMeters * featureConfig.jumpResetMeters
        ) {
            detachSupport(checkNotNull(track.stableKey))
            track.filtered = position
            track.sampleCount = 1
            (0..2).forEach { axis ->
                track.sampleSums[axis] = position[axis]
                track.sampleSquareSums[axis] = position[axis] * position[axis]
            }
            track.firstTimestampNs = timestampNs
            track.lastTimestampNs = timestampNs
            track.stableKey = null
            track.stableCoordinates = null
            return
        }

        val alpha = (0.15 + 0.35 * confidence).coerceIn(0.15, 0.50)
        track.filtered = interpolate(previousFiltered, position, alpha)
        track.lastTimestampNs = timestampNs
        val current = checkNotNull(track.stableCoordinates)
        var nextX = current[0]
        var nextY = current[1]
        var nextZ = current[2]
        val xLower =
            current[0] * group.voxelSizeMeters - featureConfig.relocationHysteresisMeters
        val xUpper =
            (current[0] + 1) * group.voxelSizeMeters +
                featureConfig.relocationHysteresisMeters
        if (track.filtered.x < xLower || track.filtered.x >= xUpper) {
            nextX = floor(track.filtered.x / group.voxelSizeMeters).toInt()
        }
        val yLower =
            current[1] * group.voxelSizeMeters - featureConfig.relocationHysteresisMeters
        val yUpper =
            (current[1] + 1) * group.voxelSizeMeters +
                featureConfig.relocationHysteresisMeters
        if (track.filtered.y < yLower || track.filtered.y >= yUpper) {
            nextY = floor(track.filtered.y / group.voxelSizeMeters).toInt()
        }
        val zLower =
            current[2] * group.voxelSizeMeters - featureConfig.relocationHysteresisMeters
        val zUpper =
            (current[2] + 1) * group.voxelSizeMeters +
                featureConfig.relocationHysteresisMeters
        if (track.filtered.z < zLower || track.filtered.z >= zUpper) {
            nextZ = floor(track.filtered.z / group.voxelSizeMeters).toInt()
        }
        val previousKey = checkNotNull(track.stableKey)
        val nextKey = packVisibilityGridKey(nextX, nextY, nextZ)
        if (nextKey == previousKey) return

        detachSupport(previousKey)
        attachSupport(nextKey)
        track.stableKey = nextKey
        track.stableCoordinates = intArrayOf(nextX, nextY, nextZ)
    }

    private fun detachSupport(key: Long) {
        val remaining = supportByKey.getValue(key) - 1
        if (remaining == 0) {
            supportByKey.remove(key)
            if (key !in restoredKeys && key in depthEvidenceByKey) {
                depthReservations++
            }
        } else {
            supportByKey[key] = remaining
        }
        refreshVisibility(key)
    }

    private fun attachSupport(key: Long) {
        supportByKey[key] = supportByKey.getOrDefault(key, 0) + 1
        if (supportByKey.getValue(key) == 1 &&
            key !in restoredKeys &&
            key in depthEvidenceByKey
        ) {
            depthReservations--
        }
        depthEvidenceByKey[key]?.let { evidence ->
            evidence.contradicted = false
            evidence.free = 0
            evidence.directionMask = 0
        }
        refreshVisibility(key)
    }

    private fun createDelta(
        baseRevision: Long,
        revision: Long,
        reset: Boolean,
        upserts: List<Long>,
        removals: List<Long>,
    ): VisibilityGridDelta =
        VisibilityGridDelta(
            groupId = group.groupId,
            groupGeneration = group.groupGeneration,
            sessionGeneration = group.sessionGeneration,
            baseGeometryRevision = baseRevision,
            geometryRevision = revision,
            reset = reset,
            upsertKeys = upserts,
            removalKeys = removals,
            capacity = group.capacity,
            diagnostics = diagnostics(),
        )

    private fun diagnostics(): VisibilityGridDiagnostics {
        return VisibilityGridDiagnostics(
            candidateTracks = tracks.values.count { it.stableKey == null },
            stableTracks = tracks.values.count { it.stableKey != null },
            stableVoxels = visibleKeys.size,
            featureTrackCapacity = featureConfig.featureTrackCapacity,
            stableVoxelCapacity = group.capacity,
            acceptedSamples = acceptedSamples,
            rejectedSamples = rejectedSamples,
            capacityRejectedCandidates = capacityRejectedCandidates,
            featureHealth = featureHealth.wireName,
            featureTransientUnavailableCount = featureTransientUnavailableCount,
            featureFailureCount = featureFailureCount,
            lastFeatureFusionNs = lastFeatureFusionNs,
            maxFeatureFusionNs = maxFeatureFusionNs,
            estimatedStateBytes = estimatedStateBytes(),
            depthHealth = depthHealth.wireName,
            depthAcceptedPixels = depthAcceptedPixels,
            depthRejectedPixels = depthRejectedPixels,
            depthCapacityRejectedPixels = depthCapacityRejectedPixels,
            depthRayVisits = depthRayVisits,
            depthTransientUnavailableCount = depthTransientUnavailableCount,
            depthFailureCount = depthFailureCount,
            lastDepthFusionNs = lastDepthFusionNs,
            maxDepthFusionNs = maxDepthFusionNs,
        )
    }

    private fun addCandidateSample(
        track: Track,
        position: Position,
    ) {
        track.sampleCount++
        (0..2).forEach { axis ->
            track.sampleSums[axis] += position[axis]
            track.sampleSquareSums[axis] += position[axis] * position[axis]
        }
    }

    private fun hasLowVariance(track: Track): Boolean =
        (0..2).all { axis ->
            val mean = track.sampleSums[axis] / track.sampleCount
            val variance =
                (track.sampleSquareSums[axis] / track.sampleCount - mean * mean)
                    .coerceAtLeast(0.0)
            sqrt(variance) <= featureConfig.candidateMaxStdDevMeters
        }

    private fun transformToGroup(sample: FeatureSample): Position {
        val matrix = group.groupFromWorldGl
        return Position(
            x =
                matrix[0] * sample.xWorld +
                    matrix[4] * sample.yWorld +
                    matrix[8] * sample.zWorld +
                    matrix[12],
            y =
                matrix[1] * sample.xWorld +
                    matrix[5] * sample.yWorld +
                    matrix[9] * sample.zWorld +
                    matrix[13],
            z =
                matrix[2] * sample.xWorld +
                    matrix[6] * sample.yWorld +
                    matrix[10] * sample.zWorld +
                    matrix[14],
        )
    }

    private fun coordinatesFor(position: Position): IntArray =
        intArrayOf(
            floor(position.x / group.voxelSizeMeters).toInt(),
            floor(position.y / group.voxelSizeMeters).toInt(),
            floor(position.z / group.voxelSizeMeters).toInt(),
        )

    private fun isQuantizable(position: Position): Boolean {
        val scaledX = position.x / group.voxelSizeMeters
        val scaledY = position.y / group.voxelSizeMeters
        val scaledZ = position.z / group.voxelSizeMeters
        if (!scaledX.isFinite() || !scaledY.isFinite() || !scaledZ.isFinite()) return false
        val x = floor(scaledX)
        val y = floor(scaledY)
        val z = floor(scaledZ)
        return x >= VOXEL_COORDINATE_MIN &&
            x <= VOXEL_COORDINATE_MAX &&
            y >= VOXEL_COORDINATE_MIN &&
            y <= VOXEL_COORDINATE_MAX &&
            z >= VOXEL_COORDINATE_MIN &&
            z <= VOXEL_COORDINATE_MAX
    }

    private fun sanitizeAndBound(samples: List<FeatureSample>): List<FeatureSample> {
        val sanitized =
            sanitizeFeatureSamples(
                samples = samples,
                minimumConfidence = featureConfig.minimumConfidence,
                maximumSamples = featureConfig.maxFeaturesPerObservation,
            )
        rejectedSamples += sanitized.rejectedSamples
        return sanitized.samples
    }

    private fun interpolate(
        previous: Position,
        current: Position,
        alpha: Double,
    ): Position =
        Position(
            x = previous.x + alpha * (current.x - previous.x),
            y = previous.y + alpha * (current.y - previous.y),
            z = previous.z + alpha * (current.z - previous.z),
        )

    private fun expireCandidates(timestampNs: Long) {
        tracks.entries.removeAll { (_, track) ->
            track.stableKey == null &&
                timestampNs - track.lastTimestampNs > featureConfig.candidateExpiryNs
        }
    }

    private fun relocationReservationCount(): Int =
        restoredKeys.size +
            tracks.values.count { it.stableKey != null } +
            depthReservations

    private fun canAdmitFeatureAssociation(): Boolean =
        estimatedStateBytes() + FEATURE_ASSOCIATION_WORST_CASE_BYTES <=
            VISIBILITY_GRID_MEMORY_BUDGET_BYTES

    private fun estimatedStateBytes(): Long =
        tracks.size * FEATURE_TRACK_ESTIMATED_BYTES +
            supportByKey.size * STABLE_VOXEL_ESTIMATED_BYTES +
            restoredKeys.size * RESTORED_VOXEL_WORST_CASE_BYTES +
            pendingGeometry.size * PENDING_GEOMETRY_KEY_ESTIMATED_BYTES +
            inFlightGeometryKeyCount() * IN_FLIGHT_GEOMETRY_KEY_ESTIMATED_BYTES +
            nonRestoredDepthEvidenceCount * DEPTH_EVIDENCE_ESTIMATED_BYTES

    private fun transformPoint(
        matrix: DoubleArray,
        point: Position,
    ): Position =
        Position(
            x =
                matrix[0] * point.x +
                    matrix[4] * point.y +
                    matrix[8] * point.z +
                    matrix[12],
            y =
                matrix[1] * point.x +
                    matrix[5] * point.y +
                    matrix[9] * point.z +
                    matrix[13],
            z =
                matrix[2] * point.x +
                    matrix[6] * point.y +
                    matrix[10] * point.z +
                    matrix[14],
        )

    private fun keyFor(position: Position): Long {
        val coordinates = coordinatesFor(position)
        return packVisibilityGridKey(coordinates[0], coordinates[1], coordinates[2])
    }

    private fun voxelCenter(key: Long): Position {
        val mask = (1L shl 21) - 1
        val bias = 1 shl 20
        val x = ((key ushr 42) and mask).toInt() - bias
        val y = ((key ushr 21) and mask).toInt() - bias
        val z = (key and mask).toInt() - bias
        val halfVoxel = group.voxelSizeMeters / 2.0
        return Position(
            x = x * group.voxelSizeMeters + halfVoxel,
            y = y * group.voxelSizeMeters + halfVoxel,
            z = z * group.voxelSizeMeters + halfVoxel,
        )
    }

    private fun evidenceFor(key: Long): DepthEvidence? {
        depthEvidenceByKey[key]?.let { return it }
        val requiresReservation =
            key !in restoredKeys && supportByKey.getOrDefault(key, 0) == 0
        if (requiresReservation && relocationReservationCount() >= group.capacity) return null
        val additionalBytes =
            if (key in restoredKeys) 0 else DEPTH_EVIDENCE_ESTIMATED_BYTES
        val nextEstimate = estimatedStateBytes() + additionalBytes
        if (nextEstimate > VISIBILITY_GRID_MEMORY_BUDGET_BYTES) return null
        return DepthEvidence().also {
            depthEvidenceByKey[key] = it
            if (key !in restoredKeys) nonRestoredDepthEvidenceCount++
            if (requiresReservation) depthReservations++
        }
    }

    private fun saturatingIncrement(value: Int): Int = minOf(255, value + 1)

    private fun directionBin(
        camera: Position,
        endpoint: Position,
    ): Int {
        val x = endpoint.x - camera.x
        val y = endpoint.y - camera.y
        val z = endpoint.z - camera.z
        val length = sqrt(x * x + y * y + z * z)
        val azimuth = atan2(x, -z)
        val azimuthBin =
            floor((azimuth + PI) / (2.0 * PI) * 8.0).toInt().coerceIn(0, 7)
        val elevation = asin((y / length).coerceIn(-1.0, 1.0))
        val elevationBin =
            when {
                elevation < -PI / 8.0 -> 0
                elevation > PI / 8.0 -> 2
                else -> 1
            }
        return elevationBin * 8 + azimuthBin
    }

    private fun traverseFreeKeys(
        camera: Position,
        endpoint: Position,
        maximumVisits: Int,
        safetyBandMeters: Double,
    ): List<Long> {
        if (maximumVisits <= 0) return emptyList()
        val dx = endpoint.x - camera.x
        val dy = endpoint.y - camera.y
        val dz = endpoint.z - camera.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        val maximumDistance = distance - safetyBandMeters
        if (!maximumDistance.isFinite() || maximumDistance <= 0.0) return emptyList()
        val unitX = dx / distance
        val unitY = dy / distance
        val unitZ = dz / distance
        val voxel = group.voxelSizeMeters
        var x = floor(camera.x / voxel).toInt()
        var y = floor(camera.y / voxel).toInt()
        var z = floor(camera.z / voxel).toInt()
        val stepX = unitX.compareTo(0.0)
        val stepY = unitY.compareTo(0.0)
        val stepZ = unitZ.compareTo(0.0)

        fun firstBoundaryDistance(
            coordinate: Double,
            cell: Int,
            step: Int,
            unit: Double,
        ): Double {
            if (step == 0) return Double.POSITIVE_INFINITY
            val boundary = if (step > 0) (cell + 1) * voxel else cell * voxel
            return (boundary - coordinate) / unit
        }

        var nextX = firstBoundaryDistance(camera.x, x, stepX, unitX)
        var nextY = firstBoundaryDistance(camera.y, y, stepY, unitY)
        var nextZ = firstBoundaryDistance(camera.z, z, stepZ, unitZ)
        val deltaX = if (stepX == 0) Double.POSITIVE_INFINITY else voxel / kotlin.math.abs(unitX)
        val deltaY = if (stepY == 0) Double.POSITIVE_INFINITY else voxel / kotlin.math.abs(unitY)
        val deltaZ = if (stepZ == 0) Double.POSITIVE_INFINITY else voxel / kotlin.math.abs(unitZ)
        val keys = ArrayList<Long>(minOf(maximumVisits, 64))
        while (keys.size < maximumVisits) {
            val next = minOf(nextX, nextY, nextZ)
            if (next >= maximumDistance) break
            val crossX = nextX == next
            val crossY = nextY == next
            val crossZ = nextZ == next
            val oldX = x
            val oldY = y
            val oldZ = z
            if (crossX) {
                x += stepX
                nextX += deltaX
            }
            if (crossY) {
                y += stepY
                nextY += deltaY
            }
            if (crossZ) {
                z += stepZ
                nextZ += deltaZ
            }
            val crossedAxes =
                intArrayOf(
                    if (crossX) 0 else -1,
                    if (crossY) 1 else -1,
                    if (crossZ) 2 else -1,
                ).filter { it >= 0 }
            for (combination in 1 until (1 shl crossedAxes.size)) {
                var candidateX = oldX
                var candidateY = oldY
                var candidateZ = oldZ
                crossedAxes.forEachIndexed { bit, axis ->
                    if (combination and (1 shl bit) != 0) {
                        when (axis) {
                            0 -> candidateX += stepX
                            1 -> candidateY += stepY
                            else -> candidateZ += stepZ
                        }
                    }
                }
                if (candidateX in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX &&
                    candidateY in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX &&
                    candidateZ in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX
                ) {
                    keys += packVisibilityGridKey(candidateX, candidateY, candidateZ)
                    if (keys.size >= maximumVisits) break
                }
            }
        }
        return keys
    }

    private fun applyDepthState(key: Long) {
        val config = checkNotNull(depthConfig)
        val evidence = depthEvidenceByKey[key] ?: return
        if (evidence.contradicted) {
            if (evidence.occupied >= config.occupiedEvidenceToShow) {
                evidence.contradicted = false
                evidence.free = 0
                evidence.directionMask = 0
            }
        } else if (
            evidence.free >= config.freeEvidenceToCarve &&
            evidence.free - evidence.occupied >= config.freeEvidenceMargin &&
            hasSeparatedDirections(evidence.directionMask, config.separatedDirectionBinsRequired)
        ) {
            evidence.contradicted = true
            evidence.occupied = 0
        }
        refreshVisibility(key)
    }

    private fun hasSeparatedDirections(
        mask: Int,
        required: Int,
    ): Boolean {
        val bins = (0 until 24).filter { mask and (1 shl it) != 0 }
        if (bins.size < required) return false
        if (required == 1) return true
        return bins.any { first ->
            bins.any { second ->
                first != second && directionBinDot(first, second) <= cos(PI / 6.0)
            }
        }
    }

    private fun directionBinDot(
        first: Int,
        second: Int,
    ): Double {
        fun vector(bin: Int): DoubleArray {
            val elevation =
                when (bin / 8) {
                    0 -> -PI / 4.0
                    1 -> 0.0
                    else -> PI / 4.0
                }
            val azimuth = -PI + (bin % 8 + 0.5) * PI / 4.0
            val horizontal = cos(elevation)
            return doubleArrayOf(
                sin(azimuth) * horizontal,
                sin(elevation),
                -cos(azimuth) * horizontal,
            )
        }
        val left = vector(first)
        val right = vector(second)
        return left[0] * right[0] + left[1] * right[1] + left[2] * right[2]
    }

    private fun isVisible(key: Long): Boolean {
        val evidence = depthEvidenceByKey[key]
        if (evidence?.contradicted == true) return false
        return key in restoredKeys ||
            supportByKey.getOrDefault(key, 0) > 0 ||
            (evidence != null &&
                evidence.occupied >= checkNotNull(depthConfig).occupiedEvidenceToShow)
    }

    private fun refreshVisibility(key: Long) {
        val shouldBeVisible = isVisible(key)
        val wasVisible = key in visibleKeys
        if (shouldBeVisible == wasVisible) return
        if (shouldBeVisible) {
            visibleKeys += key
            recordGeometryState(key, PendingGeometryState.UPSERT)
        } else {
            visibleKeys -= key
            recordGeometryState(key, PendingGeometryState.REMOVAL)
        }
    }

    private fun recordGeometryState(
        key: Long,
        state: PendingGeometryState,
    ) {
        if (snapshotRequired) return
        pendingGeometry[key] = state
        val maximumPendingKeys =
            minOf(
                group.capacity * 2,
                maxOf(1, tracks.size * 2),
            )
        if (pendingGeometry.size > maximumPendingKeys) {
            pendingGeometry.clear()
            snapshotRequired = true
        }
    }

    private fun inFlightGeometryKeyCount(): Int =
        inFlightDelta?.let { it.upsertKeys.size + it.removalKeys.size } ?: 0
}
