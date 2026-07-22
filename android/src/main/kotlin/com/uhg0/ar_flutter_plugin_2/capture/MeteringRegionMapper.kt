package com.uhg0.ar_flutter_plugin_2.capture

internal data class SensorArea(
    val width: Int,
    val height: Int,
)

internal data class MeteringRegion(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val weight: Int = 1000,
) {
    fun toNormalizedMap(sensorArea: SensorArea): Map<String, Double> =
        mapOf(
            "left" to left.toDouble() / sensorArea.width.toDouble(),
            "top" to top.toDouble() / sensorArea.height.toDouble(),
            "width" to width.toDouble() / sensorArea.width.toDouble(),
            "height" to height.toDouble() / sensorArea.height.toDouble(),
        )
}

internal object MeteringRegionMapper {
    private const val DEFAULT_BOX_FRACTION = 0.15

    fun normalizedPointToMeteringRegion(
        x: Double,
        y: Double,
        sensorArea: SensorArea,
        boxFraction: Double = DEFAULT_BOX_FRACTION,
    ): MeteringRegion {
        val clampedX = x.coerceIn(0.0, 1.0)
        val clampedY = y.coerceIn(0.0, 1.0)
        val regionWidth =
            (sensorArea.width * boxFraction).toInt().coerceIn(1, sensorArea.width)
        val regionHeight =
            (sensorArea.height * boxFraction).toInt().coerceIn(1, sensorArea.height)
        val centerX = (clampedX * sensorArea.width).toInt().coerceIn(0, sensorArea.width)
        val centerY = (clampedY * sensorArea.height).toInt().coerceIn(0, sensorArea.height)
        val left =
            (centerX - (regionWidth / 2)).coerceIn(0, sensorArea.width - regionWidth)
        val top =
            (centerY - (regionHeight / 2)).coerceIn(0, sensorArea.height - regionHeight)
        return MeteringRegion(
            left = left,
            top = top,
            width = regionWidth,
            height = regionHeight,
        )
    }
}
