package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/**
 * Time-slices sensor acquisition so expensive feature and depth copies never
 * run on every AR render callback or on the same callback.
 *
 * The scheduler is latest-value only: a delayed callback advances from "now"
 * instead of replaying missed work and creating a catch-up spike.
 */
internal class VisibilityGridFrameCadence(
    featureRateHz: Int = DEFAULT_FEATURE_RATE_HZ,
    depthRateHz: Int = DEFAULT_DEPTH_RATE_HZ,
) {
    private val featureIntervalNs = intervalNs(featureRateHz)
    private val depthIntervalNs = intervalNs(depthRateHz)
    private var nextFeatureNs = Long.MIN_VALUE
    private var nextDepthNs = Long.MIN_VALUE

    fun plan(timestampNs: Long, depthEnabled: Boolean): VisibilityGridFramePlan {
        require(timestampNs >= 0)
        if (nextFeatureNs == Long.MIN_VALUE || timestampNs < nextFeatureNs - featureIntervalNs) {
            nextFeatureNs = timestampNs
            nextDepthNs = timestampNs + featureIntervalNs / 2
        }

        val featureDue = timestampNs >= nextFeatureNs
        val depthDue = depthEnabled && timestampNs >= nextDepthNs
        return when {
            featureDue && depthDue && nextDepthNs < nextFeatureNs -> {
                nextDepthNs = timestampNs + depthIntervalNs
                VisibilityGridFramePlan(acquireDepth = true)
            }
            featureDue -> {
                nextFeatureNs = timestampNs + featureIntervalNs
                VisibilityGridFramePlan(acquireFeature = true)
            }
            depthDue -> {
                nextDepthNs = timestampNs + depthIntervalNs
                VisibilityGridFramePlan(acquireDepth = true)
            }
            else -> VisibilityGridFramePlan()
        }
    }

    fun reset() {
        nextFeatureNs = Long.MIN_VALUE
        nextDepthNs = Long.MIN_VALUE
    }

    private fun intervalNs(rateHz: Int): Long {
        require(rateHz in 1..60)
        return NANOS_PER_SECOND / rateHz
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val DEFAULT_FEATURE_RATE_HZ = 10
        const val DEFAULT_DEPTH_RATE_HZ = 5
    }
}

internal data class VisibilityGridFramePlan(
    val acquireFeature: Boolean = false,
    val acquireDepth: Boolean = false,
)
