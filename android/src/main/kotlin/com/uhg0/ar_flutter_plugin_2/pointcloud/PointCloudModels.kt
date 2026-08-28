package com.uhg0.ar_flutter_plugin_2.pointcloud

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val POINT_CLOUD_WIRE_VERSION = "pointcloud_wire_v4"
const val COVERAGE_RENDERER_STYLE_ROW_BYTES = 16
const val COVERAGE_RENDERER_MAX_STYLE_PATCH_ROWS = 2_048
const val COVERAGE_RENDERER_NO_DIRECTION = 0xff
private const val COVERAGE_RENDERER_STYLE_VERSION = 1
private const val COVERAGE_RENDERER_U32_MAX = 0xffff_ffffL

enum class CoverageRendererSemantic(val code: Int) {
    CONFIRMED(0),
    AMBIGUOUS(1),
    SUPPRESSED_DEBUG(2),
}

enum class CoverageRendererCoverage(val code: Int) {
    UNCOVERED(0),
    PARTIAL(1),
    COMPLETE(2),
}

enum class CoverageRendererPalette(val code: Int) {
    UNIFORM(0),
    COVERAGE(1),
    NORMAL(2),
    OCCUPANCY(3),
    LINEAGE(4),
    AGE(5),
    SOURCE_HEALTH(6),
    RESIDENCY(7),
    DIRECTION(8),
}

enum class CoverageRendererCut(val code: Int) {
    EXACT_CURRENT(0),
    STALE_DISPLAY(1),
    LOWER_BOUND(2),
    COVERAGE_PENDING(3),
    INDETERMINATE_HISTORY(4),
    UNAVAILABLE(5),
}

enum class CoverageRendererResidency(val code: Int) {
    ACTIVE_L0(0),
    WARM_L1(1),
    COLD_L2(2),
}

enum class CoverageRendererTarget(val code: Int) {
    NONE(0),
    PRIMARY(1),
    HALO(2),
}

enum class CoverageRendererGlyph(val code: Int) {
    NONE(0),
    NORMAL(1),
    DESIRED_DIRECTION(2),
    VIEW_ROSE(3),
}

enum class CoverageRendererAge(val code: Int) {
    FRESH(0),
    RECENT(1),
    AGING(2),
    OLD(3),
}

enum class CoverageRendererSourceHealth(val code: Int) {
    HEALTHY(0),
    FEATURE_ONLY(1),
    TRANSIENT_UNAVAILABLE(2),
    FAILED(3),
    UNSUPPORTED(4),
}

/**
 * Fixed-width renderer projection of one committed semantic/style cut.
 *
 * This is disposable presentation state, never semantic authority. Every enum
 * uses an explicit byte code, the two generations are unsigned 32-bit values,
 * lineage is an unsigned 16-bit count, and all reserved bytes must be zero.
 */
data class CoverageRendererStyleRowV1(
    val semanticGeneration: Long = 0,
    val styleGeneration: Long = 0,
    val semantic: CoverageRendererSemantic = CoverageRendererSemantic.CONFIRMED,
    val coverage: CoverageRendererCoverage = CoverageRendererCoverage.UNCOVERED,
    val palette: CoverageRendererPalette = CoverageRendererPalette.COVERAGE,
    val cut: CoverageRendererCut = CoverageRendererCut.EXACT_CURRENT,
    val residency: CoverageRendererResidency = CoverageRendererResidency.ACTIVE_L0,
    val target: CoverageRendererTarget = CoverageRendererTarget.NONE,
    val directionBin: Int = COVERAGE_RENDERER_NO_DIRECTION,
    val glyph: CoverageRendererGlyph = CoverageRendererGlyph.NONE,
    val lineageCount: Int = 0,
    val age: CoverageRendererAge = CoverageRendererAge.FRESH,
    val sourceHealth: CoverageRendererSourceHealth = CoverageRendererSourceHealth.HEALTHY,
) {
    init {
        require(semanticGeneration in 0..COVERAGE_RENDERER_U32_MAX)
        require(styleGeneration in 0..COVERAGE_RENDERER_U32_MAX)
        require(lineageCount in 0..0xffff)
        val hasDirection = directionBin in 0..23
        require(hasDirection || directionBin == COVERAGE_RENDERER_NO_DIRECTION)
        require(
            when (glyph) {
                CoverageRendererGlyph.NONE,
                CoverageRendererGlyph.NORMAL,
                -> !hasDirection
                CoverageRendererGlyph.DESIRED_DIRECTION,
                CoverageRendererGlyph.VIEW_ROSE,
                -> hasDirection
            },
        )
    }

    fun encode(): ByteArray =
        ByteBuffer.allocate(COVERAGE_RENDERER_STYLE_ROW_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(COVERAGE_RENDERER_STYLE_VERSION.toByte())
                put(
                    (semantic.code or
                        (coverage.code shl 2) or
                        (residency.code shl 4) or
                        (target.code shl 6)).toByte(),
                )
                put((palette.code or (cut.code shl 4)).toByte())
                put(
                    (glyph.code or
                        (age.code shl 2) or
                        (sourceHealth.code shl 4)).toByte(),
                )
                put(directionBin.toByte())
                put(0)
                putShort(lineageCount.toShort())
                putInt(semanticGeneration.toInt())
                putInt(styleGeneration.toInt())
            }.array()

    /** Deterministic ARGB projection used by the current point/cube material. */
    fun packedColor(): Int {
        when (cut) {
            CoverageRendererCut.COVERAGE_PENDING -> return 0xffffa000.toInt()
            CoverageRendererCut.INDETERMINATE_HISTORY -> return 0xff616161.toInt()
            CoverageRendererCut.UNAVAILABLE -> return 0x00000000
            CoverageRendererCut.STALE_DISPLAY -> return 0xff8d6e63.toInt()
            CoverageRendererCut.LOWER_BOUND -> return 0xff5c6bc0.toInt()
            CoverageRendererCut.EXACT_CURRENT -> Unit
        }
        return when (palette) {
            CoverageRendererPalette.UNIFORM -> 0xffffffff.toInt()
            CoverageRendererPalette.COVERAGE -> when (coverage) {
                CoverageRendererCoverage.UNCOVERED -> 0xffd50000.toInt()
                CoverageRendererCoverage.PARTIAL -> 0xffffab00.toInt()
                CoverageRendererCoverage.COMPLETE -> 0xff00c853.toInt()
            }
            CoverageRendererPalette.NORMAL -> 0xff42a5f5.toInt()
            CoverageRendererPalette.OCCUPANCY -> when (semantic) {
                CoverageRendererSemantic.CONFIRMED -> 0xff1e88e5.toInt()
                CoverageRendererSemantic.AMBIGUOUS -> 0xfffb8c00.toInt()
                CoverageRendererSemantic.SUPPRESSED_DEBUG -> 0xff8e24aa.toInt()
            }
            CoverageRendererPalette.LINEAGE -> when (lineageCount) {
                0 -> 0xff78909c.toInt()
                1 -> 0xff3949ab.toInt()
                else -> 0xff6a1b9a.toInt()
            }
            CoverageRendererPalette.AGE -> when (age) {
                CoverageRendererAge.FRESH -> 0xff26c6da.toInt()
                CoverageRendererAge.RECENT -> 0xff66bb6a.toInt()
                CoverageRendererAge.AGING -> 0xffffca28.toInt()
                CoverageRendererAge.OLD -> 0xff8d6e63.toInt()
            }
            CoverageRendererPalette.SOURCE_HEALTH -> when (sourceHealth) {
                CoverageRendererSourceHealth.HEALTHY -> 0xff00c853.toInt()
                CoverageRendererSourceHealth.FEATURE_ONLY -> 0xff039be5.toInt()
                CoverageRendererSourceHealth.TRANSIENT_UNAVAILABLE -> 0xffffa000.toInt()
                CoverageRendererSourceHealth.FAILED -> 0xffd50000.toInt()
                CoverageRendererSourceHealth.UNSUPPORTED -> 0xff757575.toInt()
            }
            CoverageRendererPalette.RESIDENCY -> when (residency) {
                CoverageRendererResidency.ACTIVE_L0 -> 0xff26a69a.toInt()
                CoverageRendererResidency.WARM_L1 -> 0xffffb300.toInt()
                CoverageRendererResidency.COLD_L2 -> 0xff78909c.toInt()
            }
            CoverageRendererPalette.DIRECTION -> when (directionBin / 8) {
                0 -> 0xff5c6bc0.toInt()
                1 -> 0xff29b6f6.toInt()
                else -> 0xff26a69a.toInt()
            }
        }
    }

    companion object {
        /** A renderer packet may contain many rows, but only one committed cut. */
        fun hasCoherentGenerations(rows: Iterable<CoverageRendererStyleRowV1>): Boolean {
            var semanticGeneration: Long? = null
            var styleGeneration: Long? = null
            rows.forEach { row ->
                if (semanticGeneration == null) {
                    semanticGeneration = row.semanticGeneration
                    styleGeneration = row.styleGeneration
                } else if (
                    row.semanticGeneration != semanticGeneration ||
                    row.styleGeneration != styleGeneration
                ) {
                    return false
                }
            }
            return true
        }

        fun decode(bytes: ByteArray, offset: Int = 0): CoverageRendererStyleRowV1 {
            require(offset >= 0 && bytes.size - offset >= COVERAGE_RENDERER_STYLE_ROW_BYTES)
            val data = ByteBuffer.wrap(bytes, offset, COVERAGE_RENDERER_STYLE_ROW_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            require(data.get().toInt() and 0xff == COVERAGE_RENDERER_STYLE_VERSION)
            val semanticBits = data.u8()
            val semantic = enumByCode<CoverageRendererSemantic>(semanticBits and 0x3)
            val coverage = enumByCode<CoverageRendererCoverage>((semanticBits ushr 2) and 0x3)
            val residency = enumByCode<CoverageRendererResidency>((semanticBits ushr 4) and 0x3)
            val target = enumByCode<CoverageRendererTarget>((semanticBits ushr 6) and 0x3)
            val paletteCutBits = data.u8()
            val palette = enumByCode<CoverageRendererPalette>(paletteCutBits and 0xf)
            val cut = enumByCode<CoverageRendererCut>((paletteCutBits ushr 4) and 0x7)
            require(paletteCutBits and 0x80 == 0)
            val glyphAgeHealthBits = data.u8()
            val glyph = enumByCode<CoverageRendererGlyph>(glyphAgeHealthBits and 0x3)
            val age = enumByCode<CoverageRendererAge>((glyphAgeHealthBits ushr 2) and 0x3)
            val sourceHealth =
                enumByCode<CoverageRendererSourceHealth>((glyphAgeHealthBits ushr 4) and 0x7)
            require(glyphAgeHealthBits and 0x80 == 0)
            val directionBin = data.u8()
            require(data.u8() == 0)
            val lineageCount = data.short.toInt() and 0xffff
            val semanticGeneration = data.int.toLong() and COVERAGE_RENDERER_U32_MAX
            val styleGeneration = data.int.toLong() and COVERAGE_RENDERER_U32_MAX
            return CoverageRendererStyleRowV1(
                semanticGeneration = semanticGeneration,
                styleGeneration = styleGeneration,
                semantic = semantic,
                coverage = coverage,
                palette = palette,
                cut = cut,
                residency = residency,
                target = target,
                directionBin = directionBin,
                glyph = glyph,
                lineageCount = lineageCount,
                age = age,
                sourceHealth = sourceHealth,
            )
        }

        private inline fun <reified T> enumByCode(code: Int): T where T : Enum<T> =
            enumValues<T>().firstOrNull { enumCode(it) == code }
                ?: throw IllegalArgumentException("Reserved renderer style enum code")

        private fun enumCode(value: Enum<*>): Int = when (value) {
            is CoverageRendererSemantic -> value.code
            is CoverageRendererCoverage -> value.code
            is CoverageRendererPalette -> value.code
            is CoverageRendererCut -> value.code
            is CoverageRendererResidency -> value.code
            is CoverageRendererTarget -> value.code
            is CoverageRendererGlyph -> value.code
            is CoverageRendererAge -> value.code
            is CoverageRendererSourceHealth -> value.code
            else -> error("Unsupported renderer style enum")
        }
    }
}

private fun ByteBuffer.u8(): Int = get().toInt() and 0xff

enum class VoxelRenderMode(val wireName: String) {
    POINTS("points"),
    CENTROIDS("centroids"),
    CUBES("cubes"),
    ;

    companion object {
        fun fromWire(value: String): VoxelRenderMode =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException(
                    "voxelRenderMode must be points, centroids, or cubes",
                )
    }
}

data class CoverageVisualizationLayers(
    val rawPointCloud: Boolean,
    val visibilityGridCentroids: Boolean,
    val visibilityGridCubes: Boolean,
)

fun PointCloudNativeConfig.visualizationLayers(): CoverageVisualizationLayers =
    CoverageVisualizationLayers(
        rawPointCloud = enabled && voxelRenderMode == VoxelRenderMode.POINTS,
        visibilityGridCentroids =
            enabled && voxelRenderMode == VoxelRenderMode.CENTROIDS,
        visibilityGridCubes = enabled && voxelRenderMode == VoxelRenderMode.CUBES,
    )

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
    val cubeSizeFactor: Float = 1f,
    /** Identifies one requested Compose renderer resource generation. */
    val rendererGeneration: Long = 0L,
) {
    init {
        require(wireVersion == POINT_CLOUD_WIRE_VERSION)
        require(renderCapacity in 1..100_000)
        require(pointSizePx.isFinite() && pointSizePx > 0f)
        require(frameRateHz in 1..60)
        require(maxConsecutiveAcquisitionErrors > 0)
        require(minConfidence in 0f..1f)
        require(voxelSizeMeters.isFinite() && voxelSizeMeters > 0f)
        require(cubeSizeFactor.isFinite() && cubeSizeFactor in 0.1f..1f)
        require(rendererGeneration >= 0L)
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

fun PointCloudSample.toRawPointRenderSnapshot(
    capacity: Int,
    color: Int,
    enabled: Boolean,
): CoveragePointRenderSnapshot {
    val renderedCount = minOf(ids.size, capacity)
    val positions = FloatArray(renderedCount * 3)
    repeat(renderedCount) { index ->
        val sourceOffset = index * 4
        val destinationOffset = index * 3
        positions[destinationOffset] = points[sourceOffset]
        positions[destinationOffset + 1] = points[sourceOffset + 1]
        positions[destinationOffset + 2] = points[sourceOffset + 2]
    }
    return CoveragePointRenderSnapshot(
        revision = sequence,
        enabled = enabled,
        capacity = capacity,
        count = renderedCount,
        keys = LongArray(renderedCount) { ids[it].toLong() },
        positions = positions,
        colors = IntArray(renderedCount) { color },
    )
}

data class CoveragePointRenderSnapshot(
    val revision: Long,
    val enabled: Boolean,
    val capacity: Int,
    val count: Int,
    val keys: LongArray,
    val positions: FloatArray,
    val colors: IntArray,
    val styleRows: ByteArray = ByteArray(0),
    val gridRotationWorld: FloatArray = identityGridRotation(),
    val update: CoveragePointRenderUpdate? = null,
) {
    init {
        require(styleRows.isEmpty() || styleRows.size == count * COVERAGE_RENDERER_STYLE_ROW_BYTES)
    }
}

fun identityGridRotation(): FloatArray = floatArrayOf(
    1f, 0f, 0f,
    0f, 1f, 0f,
    0f, 0f, 1f,
)

data class CoveragePointSpan(
    val startSlot: Int,
    val positions: FloatArray,
    val colors: IntArray,
    val styleRows: ByteArray = ByteArray(0),
) {
    init {
        require(
            styleRows.isEmpty() ||
                styleRows.size == colors.size * COVERAGE_RENDERER_STYLE_ROW_BYTES,
        )
    }
}

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
