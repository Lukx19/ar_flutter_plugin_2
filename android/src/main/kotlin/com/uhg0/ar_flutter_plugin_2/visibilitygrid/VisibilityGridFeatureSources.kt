package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudSample
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import java.util.ArrayDeque

internal data class SanitizedFeatureSamples(
    val samples: List<FeatureSample>,
    val rejectedSamples: Int,
)

internal fun sanitizeFeatureSamples(
    samples: List<FeatureSample>,
    minimumConfidence: Double,
    maximumSamples: Int,
): SanitizedFeatureSamples {
    val usable = samples.filter { it.isUsable(minimumConfidence) }
    val deduplicated =
        usable
            .groupBy(FeatureSample::id)
            .mapNotNull { (_, duplicates) ->
                duplicates.maxByOrNull(FeatureSample::confidence)
            }
            .sortedBy(FeatureSample::id)
    val accepted = deduplicated.take(maximumSamples)
    return SanitizedFeatureSamples(
        samples = accepted,
        rejectedSamples = samples.size - accepted.size,
    )
}

internal fun FeatureObservation.toRawPointRenderSnapshot(
    capacity: Int,
    color: Int,
    enabled: Boolean,
): CoveragePointRenderSnapshot {
    val rendered = samples.take(capacity)
    return CoveragePointRenderSnapshot(
        revision = timestampNs,
        enabled = enabled,
        capacity = capacity,
        count = rendered.size,
        keys = LongArray(rendered.size) { rendered[it].id.toLong() },
        positions =
            FloatArray(rendered.size * 3).also { positions ->
                rendered.forEachIndexed { index, sample ->
                    positions[index * 3] = sample.xWorld.toFloat()
                    positions[index * 3 + 1] = sample.yWorld.toFloat()
                    positions[index * 3 + 2] = sample.zWorld.toFloat()
                }
            },
        colors = IntArray(rendered.size) { color },
    )
}

fun interface FeatureObservationSource {
    fun poll(): FeatureObservation?
}

class SyntheticFeatureObservationSource(
    observations: List<FeatureObservation>,
) : FeatureObservationSource {
    private val pending = ArrayDeque(observations)

    override fun poll(): FeatureObservation? = pending.pollFirst()
}

class ArCoreFeatureObservationSource(
    private val acquire: () -> PointCloudSample?,
    private val groupGeneration: () -> Long,
    private val sessionGeneration: () -> Long,
    private val minimumConfidence: Double = 0.30,
    private val maximumSamples: Int = 2_000,
) : FeatureObservationSource {
    init {
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
        require(maximumSamples in 1..2_000)
    }

    override fun poll(): FeatureObservation? {
        val sample = acquire() ?: return null
        val sanitized =
            sanitizeFeatureSamples(
                samples =
                    sample.ids.indices.map { index ->
                        val offset = index * 4
                        FeatureSample(
                            id = sample.ids[index],
                            xWorld = sample.points[offset].toDouble(),
                            yWorld = sample.points[offset + 1].toDouble(),
                            zWorld = sample.points[offset + 2].toDouble(),
                            confidence = sample.points[offset + 3].toDouble(),
                        )
                    },
                minimumConfidence = minimumConfidence,
                maximumSamples = maximumSamples,
            )
        return FeatureObservation(
            timestampNs = sample.timestampNs,
            groupGeneration = groupGeneration(),
            sessionGeneration = sessionGeneration(),
            samples = sanitized.samples,
            sanitized = true,
            sourceRejectedSamples = sanitized.rejectedSamples,
        )
    }
}
