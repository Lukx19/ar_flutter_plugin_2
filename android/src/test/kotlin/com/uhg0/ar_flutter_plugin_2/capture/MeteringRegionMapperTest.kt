package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class MeteringRegionMapperTest {
    @Test
    fun `center point maps to centered metering box`() {
        val region =
            MeteringRegionMapper.normalizedPointToMeteringRegion(
                x = 0.5,
                y = 0.5,
                sensorArea = SensorArea(width = 4000, height = 3000),
            )

        assertEquals(MeteringRegion(left = 1700, top = 1275, width = 600, height = 450), region)
    }

    @Test
    fun `edge points clamp the metering box inside sensor bounds`() {
        val region =
            MeteringRegionMapper.normalizedPointToMeteringRegion(
                x = -1.0,
                y = 2.0,
                sensorArea = SensorArea(width = 4000, height = 3000),
            )

        assertEquals(MeteringRegion(left = 0, top = 2550, width = 600, height = 450), region)
    }
}
