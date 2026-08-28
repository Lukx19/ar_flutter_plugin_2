package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Canonical Chapter 13 StartResultV2 payload. */
object M0aStartResultCodecV2 {
    const val byteLength = 184
    private const val persistenceSchema = 5
    private const val acceptedModelCapacity = 100_000
    private const val acceptedPendingObservationCapacity = 200_000
    private const val residentRegionCount = 3
    private const val regionSurfaceSoftLimit = 100_000
    private const val regionDirectoryCacheBytes = 1024 * 1024
    private const val cleanPointRenderBudget = 20_000
    private const val cleanCubeRenderBudget = 8_000
    private const val warmProxyRenderBudget = 4_096
    private const val coldRegionRenderBudget = 512
    private const val rawPointRenderBudget = 2_000
    private const val ordinaryResponseBytes = 16 * 1024
    private const val catchUpResponseBytes = 64 * 1024
    private const val regionCommandLimit = 8
    private const val directionBinCount = 24
    private const val regionEdgeMillimetres = 3_000
    private const val pageEdgeMillimetres = 1_000

    /** Encodes one accepted restored cut without inventing a private payload. */
    fun encode(
        configuration: M0aStartRequestCodecV2.Configuration,
        baseline: M0aCommittedBaselineV1,
    ): ByteArray {
        val packet = ByteArray(byteLength)
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        data.putShort(0, M0aStartRequestCodecV2.supportedMinor.toShort())
        data.putShort(2, configuration.persistenceSchema.toShort())
        data.put(4, configuration.profile.toByte())
        data.put(5, 0)
        data.putShort(6, 0)
        val acceptedCapabilities =
            (configuration.requiredCapabilities or configuration.desiredCapabilities) and
                M0aStartRequestCodecV2.supportedCapabilities
        data.putLong(8, acceptedCapabilities)
        data.putLong(16, M0aStartRequestCodecV2.supportedCapabilities)
        data.putInt(24, if (configuration.requestedModelCapacity == 0) acceptedModelCapacity else configuration.requestedModelCapacity)
        data.putInt(28, if (configuration.requestedPendingObservationCapacity == 0) acceptedPendingObservationCapacity else configuration.requestedPendingObservationCapacity)
        data.putInt(32, residentRegionCount)
        data.putInt(36, regionSurfaceSoftLimit)
        data.putInt(40, regionDirectoryCacheBytes)
        data.putInt(44, cleanPointRenderBudget)
        data.putInt(48, cleanCubeRenderBudget)
        data.putInt(52, warmProxyRenderBudget)
        data.putInt(56, coldRegionRenderBudget)
        data.putInt(60, rawPointRenderBudget)
        data.putInt(64, configuration.requestedOrdinaryResponseBytes)
        data.putInt(68, configuration.requestedCatchUpResponseBytes)
        data.putShort(72, configuration.requestedRegionCommandLimit.toShort())
        data.putShort(74, directionBinCount.toShort())
        data.putInt(76, regionEdgeMillimetres)
        data.putInt(80, pageEdgeMillimetres)
        data.putInt(84, 0)
        // The request supplies the accepted configuration, while a retained
        // baseline supplies one complete accepted revision/high-water cut.
        // Never overlay selected fields from unrelated cuts.
        val revisions = if (baseline != M0aCommittedBaselineV1.ZERO) {
            baseline.resultRevisionCut()
        } else {
            configuration.restoredRevisions + longArrayOf(1L, 0L)
        }
        revisions.forEachIndexed { index, revision -> data.putLong(88 + index * 8, revision) }
        data.putLong(168, 1)
        data.putLong(176, 0)
        return packet
    }
}
