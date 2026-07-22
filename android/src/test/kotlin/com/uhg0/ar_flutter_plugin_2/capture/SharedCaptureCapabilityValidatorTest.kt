package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedCaptureCapabilityValidatorTest {
    @Test
    fun `accepts supported resolution with realtime timestamp source`() {
        SharedCaptureCapabilityValidator.validate(
            capabilities =
                SharedCaptureCapabilities(
                    supportedOutputSizes = listOf(4032 to 3024, 1920 to 1080),
                    timestampSource = SharedCaptureCapabilityValidator.TIMESTAMP_SOURCE_REALTIME,
                ),
            requestedWidth = 4032,
            requestedHeight = 3024,
        )
    }

    @Test
    fun `report exposes effective resolution timestamp source and sorted supported sizes`() {
        val report =
            SharedCaptureCapabilityValidator.validateAndReport(
                capabilities =
                    SharedCaptureCapabilities(
                        supportedOutputSizes = listOf(1920 to 1080, 4032 to 3024, 1280 to 720),
                        timestampSource = SharedCaptureCapabilityValidator.TIMESTAMP_SOURCE_REALTIME,
                    ),
                requestedWidth = 1920,
                requestedHeight = 1080,
            )

        assertEquals(1920, report.effectiveWidth)
        assertEquals(1080, report.effectiveHeight)
        assertEquals(1920, report.requestedWidth)
        assertEquals(1080, report.requestedHeight)
        assertEquals("exact", report.resolutionSelectionReason)
        assertEquals("realtime", report.timestampSourceLabel)
        assertTrue(report.timestampSourceRealtimeVerified)
        assertFalse(report.timestampCorrelationProbeRequired)
        assertEquals(listOf(4032 to 3024, 1920 to 1080, 1280 to 720), report.supportedOutputSizes)
    }

    @Test
    fun `selects closest supported resolution when exact request is unavailable`() {
        val report =
            SharedCaptureCapabilityValidator.validateAndReport(
                capabilities =
                    SharedCaptureCapabilities(
                        supportedOutputSizes = listOf(4032 to 3024, 1920 to 1080, 1280 to 720),
                        timestampSource = SharedCaptureCapabilityValidator.TIMESTAMP_SOURCE_REALTIME,
                    ),
                requestedWidth = 2000,
                requestedHeight = 1100,
            )

        assertEquals(1920, report.effectiveWidth)
        assertEquals(1080, report.effectiveHeight)
        assertEquals(2000, report.requestedWidth)
        assertEquals(1100, report.requestedHeight)
        assertEquals("nearest_supported", report.resolutionSelectionReason)
    }

    @Test
    fun `rejects when no supported output sizes are available`() {
        val error =
            captureException {
                SharedCaptureCapabilityValidator.validate(
                    capabilities =
                        SharedCaptureCapabilities(
                            supportedOutputSizes = emptyList(),
                            timestampSource = SharedCaptureCapabilityValidator.TIMESTAMP_SOURCE_REALTIME,
                        ),
                    requestedWidth = 4032,
                    requestedHeight = 3024,
                )
            }

        assertEquals("No supported output sizes are available for the configured format", error.message)
    }

    @Test
    fun `unknown timestamp source remains provisional until operational correlation probe`() {
        val report =
            SharedCaptureCapabilityValidator.validateAndReport(
                capabilities =
                    SharedCaptureCapabilities(
                        supportedOutputSizes = listOf(1280 to 720),
                        timestampSource = SharedCaptureCapabilityValidator.TIMESTAMP_SOURCE_UNKNOWN,
                    ),
                requestedWidth = 1280,
                requestedHeight = 720,
            )

        assertEquals("unknown", report.timestampSourceLabel)
        assertFalse(report.timestampSourceRealtimeVerified)
        assertTrue(report.timestampCorrelationProbeRequired)
    }

    @Test
    fun `missing timestamp metadata also requires operational correlation probe`() {
        val report =
            SharedCaptureCapabilityValidator.validateAndReport(
                capabilities =
                    SharedCaptureCapabilities(
                        supportedOutputSizes = listOf(1280 to 720),
                        timestampSource = null,
                    ),
                requestedWidth = 1280,
                requestedHeight = 720,
            )

        assertEquals("missing", report.timestampSourceLabel)
        assertFalse(report.timestampSourceRealtimeVerified)
        assertTrue(report.timestampCorrelationProbeRequired)
    }

    @Test
    fun `rejects manual iso outside supported range`() {
        val error =
            captureException {
                SharedCaptureCapabilityValidator.validate(
                    capabilities =
                        SharedCaptureCapabilities(
                            supportedOutputSizes = listOf(4032 to 3024),
                            timestampSource = SharedCaptureCapabilityValidator.TIMESTAMP_SOURCE_REALTIME,
                            isoRange = 100..800,
                        ),
                    requestedWidth = 4032,
                    requestedHeight = 3024,
                    defaultIso = 1600,
                )
            }

        assertEquals("ISO 1600 not supported (range: 100-800)", error.message)
    }

    @Test
    fun `rejects manual exposure outside supported range`() {
        val error =
            captureException {
                SharedCaptureCapabilityValidator.validate(
                    capabilities =
                        SharedCaptureCapabilities(
                            supportedOutputSizes = listOf(4032 to 3024),
                            timestampSource = SharedCaptureCapabilityValidator.TIMESTAMP_SOURCE_REALTIME,
                            exposureTimeRangeNs = 1000L..1_000_000L,
                        ),
                    requestedWidth = 4032,
                    requestedHeight = 3024,
                    defaultExposureTimeMicros = 2000L,
                )
            }

        assertEquals("Exposure time 2000us not supported", error.message)
    }

    private fun captureException(block: () -> Unit): IllegalStateException {
        try {
            block()
        } catch (error: IllegalStateException) {
            return error
        }
        throw AssertionError("Expected IllegalStateException to be thrown")
    }
}
