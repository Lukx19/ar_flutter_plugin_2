package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val M0A_IDENTITY_MATRIX_IDENTITY =
    "3ff0000000000000,0,0,0,0,3ff0000000000000,0,0,0,0,3ff0000000000000,0,0,0,0,3ff0000000000000"

internal fun m0aMatrixIdentity(data: ByteBuffer, offset: Int): String =
    (0 until 16).joinToString(",") { index ->
        data.getDouble(offset + index * 8).toBits().toString(16)
    }

internal fun m0aHashIdentity(bytes: ByteArray): String =
    bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private val M0A_EMPTY_HASH_WIRE_IDENTITY = "00".repeat(32)

private fun String.asRestoredHashWireIdentity(): String =
    if (isEmpty()) M0A_EMPTY_HASH_WIRE_IDENTITY else this

/** Strict decoder for the canonical Chapter 13 StartRequestV2 payload. */
object M0aStartRequestCodecV2 {
    const val byteLength = 464
    /** Chapter 13 freezes the M0 start negotiation at minor version zero. */
    const val supportedMinor = 0
    /** Chapter 13 MVP support: occupancy, normals, lineage, and region paging. */
    const val supportedCapabilities = 0x107L

    data class Configuration(
        val minimumMinor: Int,
        val maximumMinor: Int,
        val persistenceSchema: Int,
        val profile: Int,
        val restoreRequested: Boolean,
        val requiredCapabilities: Long,
        val desiredCapabilities: Long,
        val requestedOrdinaryResponseBytes: Int,
        val requestedCatchUpResponseBytes: Int,
        val requestedDiagnosticBytes: Int,
        val requestedRegionCommandLimit: Int,
        val voxelSizeMicrometres: Int,
        val requestedModelCapacity: Int,
        val requestedPendingObservationCapacity: Int,
        val schemaRootHashIdentity: String,
        val manifestRootHashIdentity: String,
        val groupFrameConvention: Int,
        val matrixConvention: Int,
        val directionConvention: Int,
        val normalEncoding: Int,
        val groupFromWorldIdentity: String,
        val worldFromGroupIdentity: String,
        val restoredRevisions: LongArray,
    ) {
        fun hasRestoredCutConflict(baseline: M0aCommittedBaselineV1): Boolean =
            restoreRequested && baseline != M0aCommittedBaselineV1.ZERO &&
                (restoredRevisions.indices.any { index ->
                    restoredRevisions[index] != baseline.resultRevisionCut()[index]
                } ||
                    schemaRootHashIdentity !=
                    baseline.schemaRootHashIdentity.asRestoredHashWireIdentity() ||
                    manifestRootHashIdentity !=
                    baseline.manifestRootHashIdentity.asRestoredHashWireIdentity() ||
                    groupFrameConvention != baseline.groupFrameConvention ||
                    matrixConvention != baseline.matrixConvention ||
                    directionConvention != baseline.directionConvention ||
                    normalEncoding != baseline.normalEncoding ||
                    groupFromWorldIdentity != baseline.groupFromWorldIdentity ||
                    worldFromGroupIdentity != baseline.worldFromGroupIdentity)
    }

    sealed interface DetailedDecode {
        data class Valid(val configuration: Configuration) : DetailedDecode
        data class Invalid(val failure: M0aControlValidationFailure) : DetailedDecode
    }

    class ValidationException(val failure: M0aControlValidationFailure) :
        IllegalArgumentException("Invalid StartRequestV2 payload: $failure")

    fun decode(bytes: ByteArray): Configuration = when (val detailed = decodeDetailed(bytes)) {
        is DetailedDecode.Valid -> detailed.configuration
        is DetailedDecode.Invalid -> throw ValidationException(detailed.failure)
    }

    /** Authoritative START payload decoder with stable pre-admission error evidence. */
    fun decodeDetailed(bytes: ByteArray): DetailedDecode {
        fun invalid(error: Int, phase: Int, field: Int, expected: Long, observed: Long) =
            DetailedDecode.Invalid(
                M0aControlValidationFailure(error, phase, field, expected, observed),
            )
        if (bytes.size != byteLength) return invalid(6, 2, 3, byteLength.toLong(), bytes.size.toLong())
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val minimumMinor = data.getShort(0).toInt() and 0xffff
        val maximumMinor = data.getShort(2).toInt() and 0xffff
        val flags = data.get(7).toInt() and 0xff
        val profile = data.get(6).toInt() and 0xff
        if (profile > 2) return invalid(6, 3, 5, 2, profile.toLong())
        if (flags and 0xfe != 0) return invalid(6, 3, 5, 1, flags.toLong())
        val restoreRequested = flags and 1 != 0
        val persistenceSchema = data.getShort(4).toInt() and 0xffff
        if (persistenceSchema != 5) {
            return invalid(if (restoreRequested) 2 else 1, 4, 2, 5, persistenceSchema.toLong())
        }
        if (maximumMinor < minimumMinor) {
            return invalid(36, 4, 20, minimumMinor.toLong(), maximumMinor.toLong())
        }
        val requiredCapabilities = data.getLong(8)
        val desiredCapabilities = data.getLong(16)
        if (requiredCapabilities < 0) return invalid(36, 4, 17, Long.MAX_VALUE, requiredCapabilities)
        if (desiredCapabilities < 0) return invalid(36, 4, 17, Long.MAX_VALUE, desiredCapabilities)
        val ordinary = data.getInt(24)
        val catchUp = data.getInt(28)
        val diagnostic = data.getShort(32).toInt() and 0xffff
        val regionLimit = data.getShort(34).toInt() and 0xffff
        val voxel = data.getInt(36)
        val modelCapacity = data.getInt(40)
        val pendingCapacity = data.getInt(44)
        val groupFrameConvention = data.getShort(48).toInt() and 0xffff
        val matrixConvention = data.getShort(50).toInt() and 0xffff
        val directionConvention = data.getShort(52).toInt() and 0xffff
        val normalEncoding = data.getShort(54).toInt() and 0xffff
        if (ordinary !in 4096..16384) return invalid(36, 4, 20, 4096, ordinary.toLong())
        if (catchUp !in ordinary..65536) return invalid(36, 4, 20, ordinary.toLong(), catchUp.toLong())
        if (diagnostic > 1024) return invalid(36, 4, 20, 1024, diagnostic.toLong())
        if (regionLimit !in 1..8) return invalid(36, 4, 20, 8, regionLimit.toLong())
        if (voxel <= 0 || 1_000_000 % voxel != 0 || 3_000_000 % voxel != 0) {
            return invalid(36, 4, 20, 1_000_000, voxel.toLong())
        }
        if (modelCapacity !in 0..100_000) return invalid(36, 4, 20, 100_000, modelCapacity.toLong())
        if (pendingCapacity !in 0..200_000) return invalid(36, 4, 20, 200_000, pendingCapacity.toLong())
        for (convention in intArrayOf(
            groupFrameConvention,
            matrixConvention,
            directionConvention,
            normalEncoding,
        )) {
            if (convention != 1) return invalid(6, 5, 21, 1, convention.toLong())
        }
        val revisions = LongArray(10) { index -> data.getLong(56 + index * 8) }
        revisions.firstOrNull { it < 0 }?.let { revision ->
            return invalid(36, 4, 20, Long.MAX_VALUE, revision)
        }
        for (matrixOffset in intArrayOf(136, 264)) {
            repeat(16) { index ->
                val value = data.getDouble(matrixOffset + index * 8)
                if (!value.isFinite()) {
                    return invalid(36, 5, 21, 0, value.toRawBits())
                }
            }
        }
        val schemaHash = bytes.copyOfRange(392, 424)
        val manifestHash = bytes.copyOfRange(424, 456)
        val schemaEmpty = schemaHash.all { it.toInt() == 0 }
        val manifestEmpty = manifestHash.all { it.toInt() == 0 }
        val hashState = if (schemaEmpty && manifestEmpty) 0L
        else if (!schemaEmpty && !manifestEmpty) 1L else 2L
        if (restoreRequested) {
            // M1's first committed transaction has no content-root hashes.
            // A restored request may therefore carry the exact canonical
            // empty identities, or two complete non-empty roots; mixed or
            // partial roots remain invalid.
            if (hashState == 2L) return invalid(6, 6, 22, 1, hashState)
        } else {
            if (revisions.any { it != 0L } || hashState != 0L) {
                return invalid(6, 6, 22, 0, if (revisions.any { it != 0L }) 3 else hashState)
            }
        }
        val reservedIndex = (456 until byteLength).firstOrNull { bytes[it].toInt() != 0 }
        if (reservedIndex != null) {
            return invalid(6, 7, 5, 0, bytes[reservedIndex].toLong() and 0xff)
        }
        return DetailedDecode.Valid(Configuration(
            minimumMinor,
            maximumMinor,
            5,
            profile,
            restoreRequested,
            requiredCapabilities,
            desiredCapabilities,
            ordinary,
            catchUp,
            diagnostic,
            regionLimit,
            voxel,
            modelCapacity,
            pendingCapacity,
            m0aHashIdentity(schemaHash),
            m0aHashIdentity(manifestHash),
            groupFrameConvention,
            matrixConvention,
            directionConvention,
            normalEncoding,
            m0aMatrixIdentity(data, 136),
            m0aMatrixIdentity(data, 264),
            revisions,
        ))
    }

    /** Valid empty-group request used by the JVM reference corpus. */
    fun defaultPayload(): ByteArray = ByteArray(byteLength).also { bytes ->
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.putShort(0, 0)
        data.putShort(2, 0)
        data.putShort(4, 5)
        data.put(6, 0)
        data.put(7, 0)
        data.putLong(8, 0)
        data.putLong(16, 0)
        data.putInt(24, 4096)
        data.putInt(28, 65536)
        data.putShort(32, 0)
        data.putShort(34, 8)
        data.putInt(36, 1000)
        data.putInt(40, 0)
        data.putInt(44, 0)
        data.putShort(48, 1)
        data.putShort(50, 1)
        data.putShort(52, 1)
        data.putShort(54, 1)
        data.putDouble(136, 1.0)
        data.putDouble(176, 1.0)
        data.putDouble(216, 1.0)
        data.putDouble(256, 1.0)
        data.putDouble(264, 1.0)
        data.putDouble(304, 1.0)
        data.putDouble(344, 1.0)
        data.putDouble(384, 1.0)
    }

}
