package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CoordinateFrameTransforms

private const val VOXEL_COORDINATE_BIAS = 1L shl 20
internal const val VOXEL_COORDINATE_MIN = -(1 shl 20)
internal const val VOXEL_COORDINATE_MAX = (1 shl 20) - 1

data class DepthIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
) {
    init {
        require(fx.isFinite() && fx > 0.0)
        require(fy.isFinite() && fy > 0.0)
        require(cx.isFinite())
        require(cy.isFinite())
    }
}

enum class DepthImageOrientation(val wireName: String) {
    LANDSCAPE_RIGHT("landscapeRight"),
}

data class DepthObservation(
    val timestampNs: Long,
    val groupGeneration: Long,
    val sessionGeneration: Long,
    val tracking: Boolean,
    val width: Int,
    val height: Int,
    val samples: List<DepthPixelSample>,
    val sourceRejectedPixels: Int = 0,
    val intrinsics: DepthIntrinsics,
    val worldFromCameraGl: DoubleArray,
    val imageOrientation: DepthImageOrientation = DepthImageOrientation.LANDSCAPE_RIGHT,
) {
    init {
        require(timestampNs >= 0)
        require(groupGeneration >= 0)
        require(sessionGeneration >= 0)
        require(width > 0 && height > 0)
        require(samples.size <= 4_096)
        require(sourceRejectedPixels >= 0)
        require(samples.all { it.x in 0 until width && it.y in 0 until height })
        require(worldFromCameraGl.size == 16 && worldFromCameraGl.all(Double::isFinite))
    }
}

data class DepthPixelSample(
    val x: Int,
    val y: Int,
    val depthMillimeters: Int,
    val confidence: Int,
)

data class VisibilityGridGroupConfig(
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
    val restoredVisibilityRevision: Long = 0,
    val restoredKeys: LongArray = longArrayOf(),
) {
    init {
        require(groupId.isNotBlank())
        require(groupGeneration >= 0)
        require(sessionGeneration >= 0)
        require(voxelSizeMeters.isFinite() && voxelSizeMeters > 0.0)
        require(capacity in 1..100_000)
        require(groupFrameConvention == "gravity_y_up_meters_v1")
        require(matrixConvention == "column_major_gl_v1")
        require(groupFromWorldGl.size == 16 && groupFromWorldGl.all(Double::isFinite))
        require(worldFromGroupGl.size == 16 && worldFromGroupGl.all(Double::isFinite))
        require(CoordinateFrameTransforms.areFiniteAffineInverses(groupFromWorldGl, worldFromGroupGl))
        require(restoredGeometryRevision >= 0)
        require(restoredVisibilityRevision >= 0)
        require(restoredKeys.distinct().size == restoredKeys.size)
        require(restoredKeys.size <= capacity)
        require(restoredKeys.isEmpty() || restoredGeometryRevision > 0)
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

fun unpackVisibilityGridKey(key: Long): IntArray {
    val mask = (1L shl 21) - 1
    return intArrayOf(
        ((key ushr 42) and mask).toInt() - VOXEL_COORDINATE_BIAS.toInt(),
        ((key ushr 21) and mask).toInt() - VOXEL_COORDINATE_BIAS.toInt(),
        (key and mask).toInt() - VOXEL_COORDINATE_BIAS.toInt(),
    )
}
