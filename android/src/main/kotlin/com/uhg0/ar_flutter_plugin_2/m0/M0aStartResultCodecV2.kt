package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Canonical Chapter 13 StartResultV2 payload. */
object M0aStartResultCodecV2 {
    const val byteLength = 184
    private const val persistenceSchema = 5
    private const val acceptedModelCapacity = 100_000
    private const val acceptedPendingObservationCapacity = 200_000
    private const val ordinaryResponseBytes = 16 * 1024
    private const val catchUpResponseBytes = 64 * 1024
    private const val regionCommandLimit = 8
    private const val directionBinCount = 24
    private const val regionEdgeMillimetres = 3_000
    private const val pageEdgeMillimetres = 1_000

    /** Encodes one accepted restored cut without inventing a private payload. */
    fun encode(baseline: M0aCommittedBaselineV1): ByteArray {
        val packet = ByteArray(byteLength)
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        data.putShort(0, 0)
        data.putShort(2, persistenceSchema.toShort())
        data.put(4, 0)
        data.put(5, 0)
        data.putShort(6, 0)
        data.putLong(8, 0)
        data.putLong(16, 0)
        data.putInt(24, acceptedModelCapacity)
        data.putInt(28, acceptedPendingObservationCapacity)
        data.putInt(32, 0)
        data.putInt(36, 0)
        data.putInt(40, 0)
        data.putInt(44, 0)
        data.putInt(48, 0)
        data.putInt(52, 0)
        data.putInt(56, 0)
        data.putInt(60, 0)
        data.putInt(64, ordinaryResponseBytes)
        data.putInt(68, catchUpResponseBytes)
        data.putShort(72, regionCommandLimit.toShort())
        data.putShort(74, directionBinCount.toShort())
        data.putInt(76, regionEdgeMillimetres)
        data.putInt(80, pageEdgeMillimetres)
        data.putInt(84, 0)
        data.putLong(88, 0)
        data.putLong(96, baseline.geometryRevision)
        data.putLong(104, baseline.lineageRevision)
        data.putLong(112, 0)
        data.putLong(120, 0)
        data.putLong(128, 0)
        data.putLong(136, baseline.styleRevision)
        data.putLong(144, 0)
        data.putLong(152, 0)
        data.putLong(160, 0)
        data.putLong(168, 1)
        data.putLong(176, 0)
        return packet
    }
}
