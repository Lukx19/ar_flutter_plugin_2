package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Strict decoder for the canonical Chapter 13 StartRequestV2 payload. */
object M0aStartRequestCodecV2 {
    const val byteLength = 464
    /** Chapter 13 freezes the M0 start negotiation at minor version zero. */
    const val supportedMinor = 0
    /** M0a has no promoted optional capability bits yet. */
    const val supportedCapabilities = 0L

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
        val restoredRevisions: LongArray,
    ) {
        fun hasRestoredCutConflict(baseline: M0aCommittedBaselineV1): Boolean =
            restoreRequested && baseline != M0aCommittedBaselineV1.ZERO &&
                (restoredRevisions[1] != baseline.geometryRevision ||
                    restoredRevisions[2] != baseline.lineageRevision ||
                    restoredRevisions[6] != baseline.styleRevision)
    }

    fun decode(bytes: ByteArray): Configuration {
        require(bytes.size == byteLength) { "Start request payload must be exactly 464 bytes" }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(data.getShort(4).toInt() and 0xffff == 5) { "Unsupported persistence schema" }
        val minimumMinor = data.getShort(0).toInt() and 0xffff
        val maximumMinor = data.getShort(2).toInt() and 0xffff
        require(maximumMinor >= minimumMinor)
        val flags = data.get(7).toInt() and 0xff
        require(flags and 0xfe == 0)
        val profile = data.get(6).toInt() and 0xff
        require(profile <= 2)
        val requiredCapabilities = data.getLong(8)
        val desiredCapabilities = data.getLong(16)
        require(requiredCapabilities >= 0 && desiredCapabilities >= 0)
        val ordinary = data.getInt(24)
        val catchUp = data.getInt(28)
        val diagnostic = data.getShort(32).toInt() and 0xffff
        val regionLimit = data.getShort(34).toInt() and 0xffff
        val voxel = data.getInt(36)
        val modelCapacity = data.getInt(40)
        val pendingCapacity = data.getInt(44)
        require(ordinary in 4096..16384)
        require(catchUp in ordinary..65536)
        require(diagnostic <= 1024 && regionLimit in 1..8)
        require(voxel > 0 && 1_000_000 % voxel == 0 && 3_000_000 % voxel == 0)
        require(modelCapacity in 0..100_000 && pendingCapacity in 0..200_000)
        require(data.getShort(48).toInt() and 0xffff == 1)
        require(data.getShort(50).toInt() and 0xffff == 1)
        require(data.getShort(52).toInt() and 0xffff == 1)
        require(data.getShort(54).toInt() and 0xffff == 1)
        val revisions = LongArray(10) { index -> data.getLong(56 + index * 8) }
        require(revisions.all { it >= 0 })
        validateMatrix(data, 136)
        validateMatrix(data, 264)
        val schemaHash = bytes.copyOfRange(392, 424)
        val manifestHash = bytes.copyOfRange(424, 456)
        val restoreRequested = flags and 1 != 0
        if (restoreRequested) {
            require(schemaHash.any { it.toInt() != 0 } && manifestHash.any { it.toInt() != 0 })
        } else {
            require(revisions.all { it == 0L })
            require(schemaHash.all { it.toInt() == 0 } && manifestHash.all { it.toInt() == 0 })
        }
        require(bytes.copyOfRange(456, byteLength).all { it.toInt() == 0 })
        return Configuration(
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
            revisions,
        )
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

    private fun validateMatrix(data: ByteBuffer, offset: Int) {
        repeat(16) { index -> require(data.getDouble(offset + index * 8).isFinite()) }
    }
}
