package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.*
import org.junit.Test

class StillExposurePolicyTest {
    private val capabilities = StillExposureCapabilities(true, 100_000L..1_000_000_000L, 50..3200, 1600, true)
    private val metering = StillExposureMetering(1_000_000_000L, 1_000_000_000L, 7L, 30_000_000L, 200, 33_333_333L, 50)

    private fun plan(
        sample: StillExposureMetering? = metering,
        caps: StillExposureCapabilities = capabilities,
        ordinary: Boolean = true,
        nowNs: Long = 1_100_000_000L,
        generation: Long = 7L,
    ) = StillExposurePolicy.plan(caps, sample, nowNs, generation, ordinary)

    @Test fun `converged metering shortens one lighting cycle and preserves exposure product`() {
        val result = requireNotNull(plan())
        assertEquals(10_000_000L, result.exposureNs)
        assertEquals(600, result.iso)
        assertEquals(metering.frameDurationNs, result.frameDurationNs)
        assertEquals(metering.exposureNs * metering.iso, result.exposureNs * result.iso)
    }

    @Test fun `sixty hertz uses one complete cycle and rounds ISO upward within analog range`() {
        val source = metering.copy(flickerHz = 60, iso = 201)
        val result = requireNotNull(plan(source))
        assertEquals(8_333_334L, result.exposureNs)
        assertEquals(724, result.iso)
        assertTrue(result.exposureNs * result.iso >= source.exposureNs * source.iso)
        assertTrue(result.exposureNs * (result.iso - 1) < source.exposureNs * source.iso)
    }

    @Test fun `no detected flicker permits shortening but unavailable or invalid flicker preserves AE`() {
        assertNotNull(plan(metering.copy(flickerHz = 0)))
        assertNull(plan(metering.copy(flickerHz = null)))
        assertNull(plan(metering.copy(flickerHz = 100)))
    }

    @Test fun `insufficient analog or sensor ISO headroom preserves AE without darkening`() {
        assertNull(plan(metering.copy(iso = 800)))
        assertNull(plan(caps = capabilities.copy(maxAnalogIso = 500)))
        assertNull(plan(caps = capabilities.copy(isoRange = 50..500)))
        assertNotNull(plan(caps = capabilities.copy(maxAnalogIso = 600)))
        assertNull(plan(caps = capabilities.copy(maxAnalogIso = null)))
    }

    @Test fun `already short exposure is never lengthened or made noisier`() {
        assertNull(plan(metering.copy(exposureNs = 10_000_000L)))
        assertNull(plan(metering.copy(exposureNs = 5_000_000L)))
    }

    @Test fun `manual HDR locked flash and unsupported callers retain ordinary request`() {
        assertNull(plan(ordinary = false))
        assertNull(plan(caps = capabilities.copy(manualSensorSupported = false)))
        assertNull(plan(caps = capabilities.copy(fixedAperture = false)))
        assertNull(plan(caps = capabilities.copy(exposureRangeNs = null)))
        assertNull(plan(caps = capabilities.copy(isoRange = null)))
    }

    @Test fun `missing old future and previous camera generation metering retain AE`() {
        assertNull(plan(sample = null))
        assertNull(plan(nowNs = 1_250_000_001L))
        assertNotNull(plan(nowNs = 1_250_000_000L))
        assertNull(plan(nowNs = 999_999_999L))
        assertNull(plan(generation = 8L))
        assertNull(plan(metering.copy(sensorTimestampNs = 1L)))
        assertNull(plan(metering.copy(sensorTimestampNs = 1_100_000_001L)))
        assertNull(plan(metering.copy(sensorTimestampNs = 0L)))
        assertNull(plan(metering.copy(receivedAtNs = 0L)))
    }

    @Test fun `sensor minimum uses complete lighting cycles and too long minimum preserves AE`() {
        val result = requireNotNull(plan(caps = capabilities.copy(exposureRangeNs = 15_000_000L..1_000_000_000L)))
        assertEquals(20_000_000L, result.exposureNs)
        assertEquals(300, result.iso)
        assertNull(plan(caps = capabilities.copy(exposureRangeNs = 30_000_000L..1_000_000_000L)))
    }

    @Test fun `invalid sensor metadata and arithmetic overflow cannot create manual exposure`() {
        for (source in listOf(metering.copy(iso = 0), metering.copy(exposureNs = 0),
            metering.copy(frameDurationNs = 20_000_000L), metering.copy(iso = 4000))) assertNull(plan(source))
        assertNull(plan(caps = capabilities.copy(exposureRangeNs = 0L..1_000_000_000L)))
        assertNull(plan(caps = capabilities.copy(isoRange = 0..3200)))
        assertNull(plan(caps = capabilities.copy(maxAnalogIso = 0)))
        assertNull(plan(metering.copy(exposureNs = Long.MAX_VALUE, frameDurationNs = Long.MAX_VALUE),
            capabilities.copy(exposureRangeNs = 1L..Long.MAX_VALUE)))
    }

    private val exposurePlan = StillExposurePlan(10_000_000L, 600, 33_333_333L)

    @Test fun `temporary still controls preserve targets quality tag and subsequent AE requests`() {
        val tag = Any()
        val targets = listOf(Any(), Any())
        val builder = linkedMapOf<String, Any?>("ae" to true, "iso" to null, "exposure" to null,
            "duration" to null, "tag" to tag, "targets" to targets, "quality" to 95, "width" to 4128, "height" to 3096)
        val baseline = builder.toMap()
        val result = StillExposurePolicy.buildRequest(baseline, exposurePlan,
            apply = { builder["ae"] = false; builder["iso"] = it.iso; builder["exposure"] = it.exposureNs; builder["duration"] = it.frameDurationNs },
            build = { builder.toMap() }, restore = { builder.clear(); builder.putAll(baseline) },
            onFailure = { throw AssertionError(it) })
        assertEquals(false, result["ae"])
        assertEquals(600, result["iso"])
        assertEquals(10_000_000L, result["exposure"])
        assertSame(tag, result["tag"])
        assertSame(targets, result["targets"])
        assertEquals(baseline.filterKeys { it !in setOf("ae", "iso", "exposure", "duration") },
            result.filterKeys { it !in setOf("ae", "iso", "exposure", "duration") })
        assertEquals(baseline, builder)
    }

    @Test fun `partial setter and request build failure return exact baseline and restore all controls`() {
        for (failDuringApply in listOf(true, false)) {
            var ae = true
            var iso: Int? = null
            val baseline = Any()
            var failures = 0
            val result = StillExposurePolicy.buildRequest(baseline, exposurePlan,
                apply = { ae = false; iso = it.iso; if (failDuringApply) error("setter") },
                build = { error("builder") }, restore = { ae = true; iso = null },
                onFailure = { failures++ })
            assertSame(baseline, result)
            assertTrue(ae)
            assertNull(iso)
            assertEquals(1, failures)
        }
    }
}
