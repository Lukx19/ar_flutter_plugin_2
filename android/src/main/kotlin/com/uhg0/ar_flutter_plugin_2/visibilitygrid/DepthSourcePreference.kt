package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.BuildConfig
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.ControlRequest
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.Uuid
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class DepthSourcePreference(val wireId: Int) { DENSE(0), RAW(1) }

/** Explicit choices override the build experiment; release always keeps dense. */
internal fun rawDepthCandidateForPreference(
    preference: DepthSourcePreference?,
    debug: Boolean = BuildConfig.DEBUG,
    qualificationFallback: Boolean = BuildConfig.RAW_DEPTH_CANDIDATE,
): Boolean = debug && when (preference) {
    DepthSourcePreference.DENSE -> false
    DepthSourcePreference.RAW -> true
    null -> qualificationFallback
}

/** Authenticated pre-START companion; changing an active group is refused. */
internal data class DepthSourceSelection(
    val sessionId: Uuid,
    val captureGroupId: Uuid,
    val sessionGeneration: Long,
    val groupGeneration: Long,
    val preference: DepthSourcePreference,
) {
    fun validateReplacement(current: DepthSourceSelection?, idle: Boolean) {
        check(idle || current == this) { "Depth source is latched for the active group" }
    }

    fun matches(request: ControlRequest): Boolean = sessionId == request.sessionId &&
        captureGroupId == request.captureGroupId && sessionGeneration == request.sessionGeneration &&
        groupGeneration == request.groupGeneration

    companion object {
        const val BYTE_LENGTH = 49
        fun decode(bytes: ByteArray): DepthSourceSelection {
            require(bytes.size == BYTE_LENGTH) { "Invalid depth source selection length" }
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val sessionGeneration = data.getLong(32)
            val groupGeneration = data.getLong(40)
            require(sessionGeneration > 0 && groupGeneration > 0)
            val ordinal = bytes[48].toInt() and 0xff
            val preference = requireNotNull(DepthSourcePreference.entries.singleOrNull { it.wireId == ordinal }) {
                "Unsupported depth source"
            }
            return DepthSourceSelection(
                Uuid(bytes.copyOfRange(0, 16)), Uuid(bytes.copyOfRange(16, 32)),
                sessionGeneration, groupGeneration, preference,
            )
        }
    }
}
