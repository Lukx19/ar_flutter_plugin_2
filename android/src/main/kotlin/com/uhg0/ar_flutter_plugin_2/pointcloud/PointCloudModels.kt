package com.uhg0.ar_flutter_plugin_2.pointcloud

const val POINT_CLOUD_WIRE_VERSION = "pointcloud_wire_v4"

enum class VoxelRenderMode(val wireName: String) {
    POINTS("points"),
    CUBES("cubes"),
    ;

    companion object {
        fun fromWire(value: String): VoxelRenderMode =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("voxelRenderMode must be points or cubes")
    }
}

data class PointCloudNativeConfig(
    val wireVersion: String = POINT_CLOUD_WIRE_VERSION,
    val renderCapacity: Int = 100_000,
    val defaultColor: Int = 0xFFFF0000.toInt(),
    val pointSizePx: Float = 6f,
    val frameRateHz: Int = 10,
    val maxConsecutiveAcquisitionErrors: Int = 3,
    val minConfidence: Float = 0.3f,
    val enabled: Boolean = true,
    val syntheticSource: Boolean = false,
    val voxelRenderMode: VoxelRenderMode = VoxelRenderMode.POINTS,
    val voxelSizeMeters: Float = 0.1f,
) {
    init {
        require(wireVersion == POINT_CLOUD_WIRE_VERSION)
        require(renderCapacity in 1..100_000)
        require(pointSizePx.isFinite() && pointSizePx > 0f)
        require(frameRateHz in 1..60)
        require(maxConsecutiveAcquisitionErrors > 0)
        require(minConfidence in 0f..1f)
        require(voxelSizeMeters.isFinite() && voxelSizeMeters > 0f)
    }
}

data class PointCloudSample(
    val sequence: Long,
    val timestampNs: Long,
    val ids: IntArray,
    val points: FloatArray,
) {
    init {
        require(sequence >= 0)
        require(timestampNs >= 0)
        require(points.size == ids.size * 4)
    }
}

data class CoveragePointRenderSnapshot(
    val revision: Long,
    val enabled: Boolean,
    val capacity: Int,
    val count: Int,
    val keys: LongArray,
    val positions: FloatArray,
    val colors: IntArray,
    val update: CoveragePointRenderUpdate? = null,
)

data class CoveragePointSpan(
    val startSlot: Int,
    val positions: FloatArray,
    val colors: IntArray,
)

data class CoveragePointRenderUpdate(
    val geometryRevision: Long,
    val visibilityRevision: Long,
    val enabled: Boolean,
    val count: Int,
    val spans: List<CoveragePointSpan>,
    val reset: Boolean,
)

data class PointCloudRenderStats(
    val fps: Double,
    val livePointCount: Int,
    val bufferBytes: Int,
    val emittedFrames: Long,
    val coalescedFrames: Long,
    val lastAppliedColorEpoch: Long,
    val arFrameCallbackFps: Double = fps,
    val fixedStateArrayBytes: Long = 0,
    val keyIndexEntries: Long = 0,
    val rendererDesiredBytes: Long = 0,
    val uploadStagingBytes: Long = 0,
    val gpuVertexBytes: Long = 0,
    val gpuIndexBytes: Long = 0,
    val uploadInFlight: Boolean = false,
    val pendingDirtyRows: Long = 0,
    val droppedVoxelRows: Long = 0,
    val unchangedVoxelRows: Long = 0,
    val fullResyncUploads: Long = 0,
    val partialUploads: Long = 0,
    val uploadedBytes: Long = 0,
    val coalescedRendererUpdates: Long = 0,
    val frameCallbackInFlight: Long = 0,
    val frameCallbackPending: Long = 0,
    val rendererMounted: Boolean = false,
    val trackingFrameCallbacks: Long = 0,
    val nonTrackingFrameCallbacks: Long = 0,
    val acquisitionAttempts: Long = 0,
    val emptyAcquisitions: Long = 0,
    val acquisitionErrors: Long = 0,
    val rawPointCloudIds: Int = 0,
    val rawPointCloudFloats: Int = 0,
    val acceptedSourcePoints: Int = 0,
    val confidenceRejectedPoints: Long = 0,
    val nonFiniteRejectedPoints: Long = 0,
    val invalidPointCloudAcquisitions: Long = 0,
    val unchangedPointCloudTimestamps: Long = 0,
    val lastPointCloudTimestampNs: Long = 0,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "fps" to fps,
        "livePointCount" to livePointCount,
        "bufferBytes" to bufferBytes,
        "emittedFrames" to emittedFrames,
        "coalescedFrames" to coalescedFrames,
        "lastAppliedColorEpoch" to lastAppliedColorEpoch,
        "arFrameCallbackFps" to arFrameCallbackFps,
        "fixedStateArrayBytes" to fixedStateArrayBytes,
        "keyIndexEntries" to keyIndexEntries,
        "rendererDesiredBytes" to rendererDesiredBytes,
        "uploadStagingBytes" to uploadStagingBytes,
        "gpuVertexBytes" to gpuVertexBytes,
        "gpuIndexBytes" to gpuIndexBytes,
        "uploadInFlight" to uploadInFlight,
        "pendingDirtyRows" to pendingDirtyRows,
        "droppedVoxelRows" to droppedVoxelRows,
        "unchangedVoxelRows" to unchangedVoxelRows,
        "fullResyncUploads" to fullResyncUploads,
        "partialUploads" to partialUploads,
        "uploadedBytes" to uploadedBytes,
        "coalescedRendererUpdates" to coalescedRendererUpdates,
        "frameCallbackInFlight" to frameCallbackInFlight,
        "frameCallbackPending" to frameCallbackPending,
        "rendererMounted" to rendererMounted,
        "trackingFrameCallbacks" to trackingFrameCallbacks,
        "nonTrackingFrameCallbacks" to nonTrackingFrameCallbacks,
        "acquisitionAttempts" to acquisitionAttempts,
        "emptyAcquisitions" to emptyAcquisitions,
        "acquisitionErrors" to acquisitionErrors,
        "rawPointCloudIds" to rawPointCloudIds,
        "rawPointCloudFloats" to rawPointCloudFloats,
        "acceptedSourcePoints" to acceptedSourcePoints,
        "confidenceRejectedPoints" to confidenceRejectedPoints,
        "nonFiniteRejectedPoints" to nonFiniteRejectedPoints,
        "invalidPointCloudAcquisitions" to invalidPointCloudAcquisitions,
        "unchangedPointCloudTimestamps" to unchangedPointCloudTimestamps,
        "lastPointCloudTimestampNs" to lastPointCloudTimestampNs,
    )
}

data class PointCloudError(
    val code: String,
    val message: String,
    val fatalToAcquisition: Boolean = false,
    val fatalToRenderer: Boolean = false,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "code" to code,
        "message" to message,
        "fatalToAcquisition" to fatalToAcquisition,
        "fatalToRenderer" to fatalToRenderer,
    )
}
