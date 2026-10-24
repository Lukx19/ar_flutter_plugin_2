package com.uhg0.ar_flutter_plugin_2.capture

/** Fresh, converged automatic metering from the active repeating request, never a still. */
internal data class StillExposureMetering(
    val receivedAtNs: Long,
    val sensorTimestampNs: Long,
    val generation: Long,
    val exposureNs: Long,
    val iso: Int,
    val frameDurationNs: Long,
    /** 0 means the camera reported no flicker; null means unavailable. */
    val flickerHz: Int?,
)

internal data class StillExposureCapabilities(
    val manualSensorSupported: Boolean,
    val exposureRangeNs: LongRange?,
    val isoRange: IntRange?,
    val maxAnalogIso: Int?,
    val fixedAperture: Boolean,
)

internal data class StillExposurePlan(val exposureNs: Long, val iso: Int, val frameDurationNs: Long)

/**
 * Shortens one ordinary auto-metered still without changing the repeating AR camera.
 * ISO is raised only when the full metered ISO * duration fits the analog range.
 * Missing/stale metering, manual controls, HDR and insufficient headroom keep ordinary AE.
 */
internal object StillExposurePolicy {
    private const val MaximumMeteringAgeNs = 250_000_000L

    fun plan(
        capabilities: StillExposureCapabilities,
        metering: StillExposureMetering?,
        nowNs: Long,
        generation: Long,
        ordinaryUnlockedAuto: Boolean,
    ): StillExposurePlan? {
        if (!ordinaryUnlockedAuto || !capabilities.manualSensorSupported || !capabilities.fixedAperture) return null
        val sample = metering ?: return null
        if (sample.receivedAtNs <= 0L || sample.generation != generation || nowNs < sample.receivedAtNs ||
            nowNs - sample.receivedAtNs > MaximumMeteringAgeNs || sample.sensorTimestampNs <= 0L ||
            nowNs < sample.sensorTimestampNs || nowNs - sample.sensorTimestampNs > MaximumMeteringAgeNs) return null
        val exposureRange = capabilities.exposureRangeNs ?: return null
        val isoRange = capabilities.isoRange ?: return null
        val maxAnalogIso = capabilities.maxAnalogIso ?: return null
        if (exposureRange.first <= 0L || exposureRange.last < exposureRange.first ||
            isoRange.first <= 0 || isoRange.last < isoRange.first || maxAnalogIso < isoRange.first ||
            sample.exposureNs !in exposureRange || sample.iso !in isoRange ||
            sample.frameDurationNs < sample.exposureNs) return null
        // One complete lighting cycle avoids introducing a shorter partial mains cycle.
        val cycleNs = when (sample.flickerHz) {
            0, 50 -> 10_000_000L
            60 -> 8_333_334L
            else -> return null
        }
        val cycles = exposureRange.first / cycleNs + if (exposureRange.first % cycleNs == 0L) 0L else 1L
        val targetNs = try {
            Math.multiplyExact(cycles, cycleNs)
        } catch (_: ArithmeticException) { return null }
        if (targetNs >= sample.exposureNs || targetNs > exposureRange.last) return null
        val meteredProduct = try {
            Math.multiplyExact(sample.exposureNs, sample.iso.toLong())
        } catch (_: ArithmeticException) { return null }
        val requestedIso = meteredProduct / targetNs + if (meteredProduct % targetNs == 0L) 0L else 1L
        val ceiling = minOf(isoRange.last, maxAnalogIso)
        if (requestedIso < sample.iso || requestedIso > ceiling.toLong()) return null
        return StillExposurePlan(targetNs, requestedIso.toInt(), sample.frameDurationNs)
    }

    /** Immutable fallback and restored builder also cover partial setters or build rejection. */
    fun <R> buildRequest(
        baseline: R,
        plan: StillExposurePlan,
        apply: (StillExposurePlan) -> Unit,
        build: () -> R,
        restore: () -> Unit,
        onFailure: (Exception) -> Unit,
    ): R = try {
        apply(plan)
        build()
    } catch (error: Exception) {
        onFailure(error)
        baseline
    } finally {
        restore()
    }
}
