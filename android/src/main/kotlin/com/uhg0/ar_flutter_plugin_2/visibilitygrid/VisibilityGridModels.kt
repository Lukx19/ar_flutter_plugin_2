package com.uhg0.ar_flutter_plugin_2.visibilitygrid

const val VISIBILITY_GRID_WIRE_VERSION = "visibility_grid_wire_v1"

private const val VOXEL_COORDINATE_BIAS = 1L shl 20
internal const val VOXEL_COORDINATE_MIN = -(1 shl 20)
internal const val VOXEL_COORDINATE_MAX = (1 shl 20) - 1
internal const val FEATURE_TRACK_ESTIMATED_BYTES = 192L
internal const val STABLE_VOXEL_ESTIMATED_BYTES = 128L
internal const val RESTORED_VOXEL_WORST_CASE_BYTES = 160L
internal const val PENDING_GEOMETRY_KEY_ESTIMATED_BYTES = 64L
internal const val IN_FLIGHT_GEOMETRY_KEY_ESTIMATED_BYTES = 32L
internal const val FEATURE_ASSOCIATION_WORST_CASE_BYTES = 512L
internal const val VISIBILITY_GRID_MEMORY_BUDGET_BYTES = 16L * 1024L * 1024L

data class VisibilityGridFeatureConfig(
    val stableVoxelCapacity: Int = 100_000,
    val featureTrackCapacity: Int = 200_000,
    val maxFeaturesPerObservation: Int = 2_000,
    val publishIntervalMs: Int = 500,
    val minimumConfidence: Double = 0.30,
    val candidateSamples: Int = 5,
    val candidateSpanNs: Long = 500_000_000,
    val candidateMaxStdDevMeters: Double = 0.05,
    val relocationHysteresisMeters: Double = 0.015,
    val jumpResetMeters: Double = 0.30,
    val candidateExpiryNs: Long = 2_000_000_000,
) {
    init {
        require(stableVoxelCapacity in 1..100_000)
        require(featureTrackCapacity in 1..200_000)
        require(maxFeaturesPerObservation in 1..2_000)
        require(publishIntervalMs >= 500)
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
        require(candidateSamples > 0)
        require(candidateSpanNs >= 0)
        require(candidateMaxStdDevMeters.isFinite() && candidateMaxStdDevMeters >= 0.0)
        require(relocationHysteresisMeters.isFinite() && relocationHysteresisMeters >= 0.0)
        require(jumpResetMeters.isFinite() && jumpResetMeters > 0.0)
        require(candidateExpiryNs > 0)
    }
}

data class VisibilityGridGroupConfig(
    val wireVersion: String = VISIBILITY_GRID_WIRE_VERSION,
    val groupId: String,
    val groupGeneration: Long,
    val sessionGeneration: Long,
    val voxelSizeMeters: Double,
    val capacity: Int,
    val groupFrameConvention: String = "gravity_y_up_meters_v1",
    val matrixConvention: String = "column_major_gl_v1",
    val groupFromWorldGl: DoubleArray,
    val worldFromGroupGl: DoubleArray = identityVisibilityGridTransform(),
    val restoredGeometryRevision: Long = 0,
    val restoredKeys: LongArray = longArrayOf(),
) {
    init {
        require(wireVersion == VISIBILITY_GRID_WIRE_VERSION)
        require(groupId.isNotBlank())
        require(groupGeneration >= 0)
        require(sessionGeneration >= 0)
        require(voxelSizeMeters.isFinite() && voxelSizeMeters > 0.0)
        require(capacity in 1..100_000)
        require(groupFrameConvention == "gravity_y_up_meters_v1")
        require(matrixConvention == "column_major_gl_v1")
        require(groupFromWorldGl.size == 16 && groupFromWorldGl.all(Double::isFinite))
        require(worldFromGroupGl.size == 16 && worldFromGroupGl.all(Double::isFinite))
        require(areInverseTransforms(groupFromWorldGl, worldFromGroupGl))
        require(restoredGeometryRevision >= 0)
        require(restoredKeys.distinct().size == restoredKeys.size)
        require(restoredKeys.size <= capacity)
        require(restoredKeys.isEmpty() || restoredGeometryRevision > 0)
    }
}

private fun areInverseTransforms(
    first: DoubleArray,
    second: DoubleArray,
): Boolean =
    (0 until 4).all { row ->
        (0 until 4).all { column ->
            val actual =
                (0 until 4).sumOf { index ->
                    first[index * 4 + row] * second[column * 4 + index]
                }
            val expected = if (row == column) 1.0 else 0.0
            kotlin.math.abs(actual - expected) <= 1e-6
        }
    }

data class FeatureSample(
    val id: Int,
    val xWorld: Double,
    val yWorld: Double,
    val zWorld: Double,
    val confidence: Double,
) {
    fun isUsable(minimumConfidence: Double): Boolean =
        xWorld.isFinite() &&
            yWorld.isFinite() &&
            zWorld.isFinite() &&
            confidence.isFinite() &&
            confidence >= minimumConfidence
}

data class FeatureObservation(
    val timestampNs: Long,
    val groupGeneration: Long,
    val sessionGeneration: Long,
    val samples: List<FeatureSample>,
    val sanitized: Boolean = false,
    val sourceRejectedSamples: Int = 0,
) {
    init {
        require(timestampNs >= 0)
        require(sourceRejectedSamples >= 0)
        require(sanitized || sourceRejectedSamples == 0)
    }
}

data class VisibilityGridDiagnostics(
    val candidateTracks: Int,
    val stableTracks: Int,
    val stableVoxels: Int,
    val featureTrackCapacity: Int,
    val stableVoxelCapacity: Int,
    val acceptedSamples: Long,
    val rejectedSamples: Long,
    val capacityRejectedCandidates: Long,
    val featureHealth: String,
    val featureTransientUnavailableCount: Long,
    val featureFailureCount: Long,
    val lastFeatureFusionNs: Long,
    val maxFeatureFusionNs: Long,
    val estimatedStateBytes: Long,
)

data class VisibilityGridGeometryAck(
    val wireVersion: String = VISIBILITY_GRID_WIRE_VERSION,
    val groupId: String,
    val groupGeneration: Long,
    val sessionGeneration: Long,
    val acceptedGeometryRevision: Long,
)

data class VisibilityGridSnapshotRequest(
    val wireVersion: String = VISIBILITY_GRID_WIRE_VERSION,
    val groupId: String,
    val groupGeneration: Long,
    val sessionGeneration: Long,
    val receiverGeometryRevision: Long,
)

data class VisibilityGridSnapshot(
    val groupId: String,
    val groupGeneration: Long,
    val sessionGeneration: Long,
    val geometryRevision: Long,
    val stableKeys: List<Long>,
    val supportByKey: Map<Long, Int>,
    val diagnostics: VisibilityGridDiagnostics,
)

data class VisibilityGridDelta(
    val groupId: String,
    val groupGeneration: Long,
    val sessionGeneration: Long,
    val baseGeometryRevision: Long,
    val geometryRevision: Long,
    val reset: Boolean,
    val upsertKeys: List<Long>,
    val removalKeys: List<Long>,
    val capacity: Int,
    val diagnostics: VisibilityGridDiagnostics,
) {
    fun toWireMap(): Map<String, Any> =
        mapOf(
            "version" to VISIBILITY_GRID_WIRE_VERSION,
            "groupId" to groupId,
            "groupGeneration" to groupGeneration,
            "sessionGeneration" to sessionGeneration,
            "baseGeometryRevision" to baseGeometryRevision,
            "geometryRevision" to geometryRevision,
            "reset" to reset,
            "upsertKeys" to upsertKeys.toLongArray(),
            "removalKeys" to removalKeys.toLongArray(),
            "capacity" to capacity,
            "sourceHealth" to
                mapOf(
                    "feature" to diagnostics.featureHealth,
                    "depth" to "unsupported",
                    "renderer" to "configured",
                    "totalGrid" to
                        if (diagnostics.featureHealth == "failed") {
                            "failed"
                        } else {
                            "healthy"
                        },
                ),
            "diagnostics" to
                mapOf(
                    "candidateTracks" to diagnostics.candidateTracks,
                    "stableTracks" to diagnostics.stableTracks,
                    "stableVoxels" to diagnostics.stableVoxels,
                    "featureTrackCapacity" to diagnostics.featureTrackCapacity,
                    "stableVoxelCapacity" to diagnostics.stableVoxelCapacity,
                    "capacityRejectedCandidates" to diagnostics.capacityRejectedCandidates,
                    "featureTransientUnavailableCount" to
                        diagnostics.featureTransientUnavailableCount,
                    "featureFailureCount" to diagnostics.featureFailureCount,
                    "lastFeatureFusionNs" to diagnostics.lastFeatureFusionNs,
                    "maxFeatureFusionNs" to diagnostics.maxFeatureFusionNs,
                    "estimatedStateBytes" to diagnostics.estimatedStateBytes,
                ),
        )
}

fun identityVisibilityGridTransform(): DoubleArray =
    doubleArrayOf(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0,
    )

fun packVisibilityGridKey(
    x: Int,
    y: Int,
    z: Int,
): Long {
    require(x in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX)
    require(y in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX)
    require(z in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX)
    return ((x + VOXEL_COORDINATE_BIAS) shl 42) or
        ((y + VOXEL_COORDINATE_BIAS) shl 21) or
        (z + VOXEL_COORDINATE_BIAS)
}
