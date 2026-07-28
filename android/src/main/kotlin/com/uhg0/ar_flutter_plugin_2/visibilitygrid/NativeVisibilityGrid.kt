package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.floor
import kotlin.math.sqrt

class NativeVisibilityGrid(
    private val config: VisibilityGridFeatureConfig,
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

    private lateinit var group: VisibilityGridGroupConfig
    private val tracks = HashMap<Int, Track>()
    private val supportByKey = HashMap<Long, Int>()
    private val restoredKeys = HashSet<Long>()
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

    @Synchronized
    fun startGroup(group: VisibilityGridGroupConfig): VisibilityGridSnapshot {
        require(group.capacity <= config.stableVoxelCapacity)
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
                    observation.samples.size <= config.maxFeaturesPerObservation
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
                    if (tracks.size >= config.featureTrackCapacity ||
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
        val stableKeys = (restoredKeys + supportByKey.keys).sorted()
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
                nowNs - lastPublicationNs < config.publishIntervalMs * 1_000_000L)
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
                    (restoredKeys + supportByKey.keys).sorted()
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
            upserts = (restoredKeys + supportByKey.keys).sorted(),
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
        if (track.sampleCount < config.candidateSamples ||
            timestampNs - track.firstTimestampNs < config.candidateSpanNs ||
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
        if (jumpDistanceSquared >= config.jumpResetMeters * config.jumpResetMeters) {
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
        val xLower = current[0] * group.voxelSizeMeters - config.relocationHysteresisMeters
        val xUpper =
            (current[0] + 1) * group.voxelSizeMeters + config.relocationHysteresisMeters
        if (track.filtered.x < xLower || track.filtered.x >= xUpper) {
            nextX = floor(track.filtered.x / group.voxelSizeMeters).toInt()
        }
        val yLower = current[1] * group.voxelSizeMeters - config.relocationHysteresisMeters
        val yUpper =
            (current[1] + 1) * group.voxelSizeMeters + config.relocationHysteresisMeters
        if (track.filtered.y < yLower || track.filtered.y >= yUpper) {
            nextY = floor(track.filtered.y / group.voxelSizeMeters).toInt()
        }
        val zLower = current[2] * group.voxelSizeMeters - config.relocationHysteresisMeters
        val zUpper =
            (current[2] + 1) * group.voxelSizeMeters + config.relocationHysteresisMeters
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
            if (key !in restoredKeys) {
                recordGeometryState(key, PendingGeometryState.REMOVAL)
            }
        } else {
            supportByKey[key] = remaining
        }
    }

    private fun attachSupport(key: Long) {
        val wasVisible = key in restoredKeys || supportByKey.getOrDefault(key, 0) > 0
        supportByKey[key] = supportByKey.getOrDefault(key, 0) + 1
        if (!wasVisible) recordGeometryState(key, PendingGeometryState.UPSERT)
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
        val stableKeys = restoredKeys + supportByKey.keys
        return VisibilityGridDiagnostics(
            candidateTracks = tracks.values.count { it.stableKey == null },
            stableTracks = tracks.values.count { it.stableKey != null },
            stableVoxels = stableKeys.size,
            featureTrackCapacity = config.featureTrackCapacity,
            stableVoxelCapacity = group.capacity,
            acceptedSamples = acceptedSamples,
            rejectedSamples = rejectedSamples,
            capacityRejectedCandidates = capacityRejectedCandidates,
            featureHealth = featureHealth.wireName,
            featureTransientUnavailableCount = featureTransientUnavailableCount,
            featureFailureCount = featureFailureCount,
            lastFeatureFusionNs = lastFeatureFusionNs,
            maxFeatureFusionNs = maxFeatureFusionNs,
            estimatedStateBytes =
                tracks.size * FEATURE_TRACK_ESTIMATED_BYTES +
                    (supportByKey.size + restoredKeys.size) *
                    STABLE_VOXEL_ESTIMATED_BYTES +
                    pendingGeometry.size * PENDING_GEOMETRY_KEY_ESTIMATED_BYTES +
                    inFlightGeometryKeyCount() * IN_FLIGHT_GEOMETRY_KEY_ESTIMATED_BYTES,
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
            sqrt(variance) <= config.candidateMaxStdDevMeters
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
                minimumConfidence = config.minimumConfidence,
                maximumSamples = config.maxFeaturesPerObservation,
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
                timestampNs - track.lastTimestampNs > config.candidateExpiryNs
        }
    }

    private fun relocationReservationCount(): Int =
        restoredKeys.size + tracks.values.count { it.stableKey != null }

    private fun canAdmitFeatureAssociation(): Boolean =
        (tracks.size + 1L) * FEATURE_ASSOCIATION_WORST_CASE_BYTES +
            restoredKeys.size * RESTORED_VOXEL_WORST_CASE_BYTES <=
            VISIBILITY_GRID_MEMORY_BUDGET_BYTES

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
