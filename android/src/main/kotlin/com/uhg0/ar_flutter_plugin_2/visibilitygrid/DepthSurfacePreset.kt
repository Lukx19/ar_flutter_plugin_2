package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.ControlRequest
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.Uuid
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Five implemented, bounded compositions. No live map may change its estimator. */
internal enum class DepthSurfacePreset(val wireId: Int, val wireName: String) {
    VIEW_RAY_WEIGHTED(0, "viewRayWeighted"),
    LOCAL_NORMALS(1, "localNormals"),
    COMPATIBLE_PLANES(2, "compatiblePlanes"),
    REUSABLE_COMPATIBLE_PLANES(3, "reusableCompatiblePlanes"),
    BULK_COMPATIBLE_PLANES(4, "bulkCompatiblePlanes");

    fun configuration(surfaceCapacity: Int = 100_000): DepthEvidenceConfiguration = DepthEvidenceConfiguration(
        surfaceCapacity = surfaceCapacity,
        surfaceEstimationMode = when (this) {
            VIEW_RAY_WEIGHTED -> DepthSurfaceEstimationMode.DISABLED
            LOCAL_NORMALS -> DepthSurfaceEstimationMode.LOCAL_NORMALS
            else -> DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES
        },
        planeFitStrategy = when (this) {
            REUSABLE_COMPATIBLE_PLANES -> DepthPlaneFitStrategy.REUSABLE_SAMPLE_SUPPORT
            BULK_COMPATIBLE_PLANES -> DepthPlaneFitStrategy.BULK_CELL_MOMENTS
            else -> DepthPlaneFitStrategy.PER_SAMPLE_SUPPORT
        },
    )
}

/** Binding-qualified pre-START companion, keeping the canonical START packet unchanged. */
internal data class DepthSurfaceSelection(
    val sessionId: Uuid,
    val captureGroupId: Uuid,
    val sessionGeneration: Long,
    val groupGeneration: Long,
    val preset: DepthSurfacePreset,
) {
    fun validateReplacement(current: DepthSurfaceSelection?, idle: Boolean) {
        check(idle || current == this) { "Depth surface preset is latched for the active group" }
    }

    fun matches(request: ControlRequest): Boolean = sessionId == request.sessionId &&
        captureGroupId == request.captureGroupId && sessionGeneration == request.sessionGeneration &&
        groupGeneration == request.groupGeneration

    companion object {
        const val BYTE_LENGTH = 49
        fun decode(bytes: ByteArray): DepthSurfaceSelection {
            require(bytes.size == BYTE_LENGTH) { "Invalid depth surface selection length" }
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val sessionGeneration = data.getLong(32)
            val groupGeneration = data.getLong(40)
            require(sessionGeneration > 0 && groupGeneration > 0)
            val ordinal = bytes[48].toInt() and 0xff
            val preset = requireNotNull(DepthSurfacePreset.entries.singleOrNull { it.wireId == ordinal }) { "Unsupported depth surface preset" }
            return DepthSurfaceSelection(Uuid(bytes.copyOfRange(0,16)), Uuid(bytes.copyOfRange(16,32)),
                sessionGeneration, groupGeneration, preset)
        }
    }
}
