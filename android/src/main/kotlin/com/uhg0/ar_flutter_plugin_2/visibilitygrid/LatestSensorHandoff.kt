package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/**
 * Two independently coalesced latest-value slots for native sensor callbacks.
 *
 * Callers provide their own synchronization. Replacing one sensor never
 * discards the other sensor's pending value.
 */
internal class LatestSensorHandoff<Feature : Any, Depth : Any> {
    private var feature: Feature? = null
    private var depth: Depth? = null

    val hasPending: Boolean
        get() = feature != null || depth != null

    fun offerFeature(value: Feature): Boolean {
        val replaced = feature != null
        feature = value
        return replaced
    }

    fun offerDepth(value: Depth): Boolean {
        val replaced = depth != null
        depth = value
        return replaced
    }

    fun take(): SensorHandoffBatch<Feature, Depth>? {
        if (!hasPending) return null
        return SensorHandoffBatch(feature, depth).also {
            feature = null
            depth = null
        }
    }

    fun clear() {
        feature = null
        depth = null
    }
}

internal data class SensorHandoffBatch<Feature : Any, Depth : Any>(
    val feature: Feature?,
    val depth: Depth?,
)

internal enum class SensorHandoffSource {
    FEATURE,
    DEPTH,
}

internal fun sensorProcessingOrder(
    featureTimestampNs: Long?,
    depthTimestampNs: Long?,
): List<SensorHandoffSource> =
    when {
        featureTimestampNs == null && depthTimestampNs == null -> emptyList()
        featureTimestampNs == null -> listOf(SensorHandoffSource.DEPTH)
        depthTimestampNs == null -> listOf(SensorHandoffSource.FEATURE)
        depthTimestampNs < featureTimestampNs ->
            listOf(SensorHandoffSource.DEPTH, SensorHandoffSource.FEATURE)
        else -> listOf(SensorHandoffSource.FEATURE, SensorHandoffSource.DEPTH)
    }
