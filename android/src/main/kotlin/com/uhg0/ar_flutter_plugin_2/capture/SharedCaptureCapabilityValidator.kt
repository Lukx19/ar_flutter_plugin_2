package com.uhg0.ar_flutter_plugin_2.capture

internal data class SharedCaptureCapabilities(
    val supportedOutputSizes: List<Pair<Int, Int>>,
    val timestampSource: Int?,
    val isoRange: IntRange? = null,
    val exposureTimeRangeNs: LongRange? = null,
)

internal data class SharedCaptureCapabilityReport(
    val requestedWidth: Int,
    val requestedHeight: Int,
    val effectiveWidth: Int,
    val effectiveHeight: Int,
    val resolutionSelectionReason: String,
    val timestampSource: Int?,
    val timestampSourceLabel: String,
    val timestampSourceRealtimeVerified: Boolean,
    val timestampCorrelationProbeRequired: Boolean,
    val supportedOutputSizes: List<Pair<Int, Int>>,
)

internal object SharedCaptureCapabilityValidator {
    const val TIMESTAMP_SOURCE_UNKNOWN = 0
    const val TIMESTAMP_SOURCE_REALTIME = 1

    fun validateAndReport(
        capabilities: SharedCaptureCapabilities,
        requestedWidth: Int,
        requestedHeight: Int,
        defaultIso: Int? = null,
        defaultExposureTimeMicros: Long? = null,
    ): SharedCaptureCapabilityReport {
        if (capabilities.supportedOutputSizes.isEmpty()) {
            throw IllegalStateException(
                "No supported output sizes are available for the configured format",
            )
        }
        val resolutionSelection =
            selectEffectiveResolution(
                supportedOutputSizes = capabilities.supportedOutputSizes,
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight,
            )

        val timestampSourceRealtimeVerified =
            capabilities.timestampSource == TIMESTAMP_SOURCE_REALTIME

        if (defaultIso != null) {
            val isoRange = capabilities.isoRange
                ?: throw IllegalStateException("ISO range is unavailable for manual exposure validation")
            if (defaultIso !in isoRange) {
                throw IllegalStateException(
                    "ISO $defaultIso not supported (range: ${isoRange.first}-${isoRange.last})",
                )
            }
        }

        if (defaultExposureTimeMicros != null) {
            val exposureRangeNs = capabilities.exposureTimeRangeNs
                ?: throw IllegalStateException("Exposure range is unavailable for manual exposure validation")
            val exposureTimeNs = defaultExposureTimeMicros * 1000
            if (exposureTimeNs !in exposureRangeNs) {
                throw IllegalStateException(
                    "Exposure time ${defaultExposureTimeMicros}us not supported",
                )
            }
        }

        return SharedCaptureCapabilityReport(
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight,
            effectiveWidth = resolutionSelection.resolution.first,
            effectiveHeight = resolutionSelection.resolution.second,
            resolutionSelectionReason = resolutionSelection.reason,
            timestampSource = capabilities.timestampSource,
            timestampSourceLabel = describeTimestampSource(capabilities.timestampSource),
            timestampSourceRealtimeVerified = timestampSourceRealtimeVerified,
            timestampCorrelationProbeRequired = !timestampSourceRealtimeVerified,
            supportedOutputSizes =
                capabilities.supportedOutputSizes.sortedByDescending { (width, height) ->
                    width.toLong() * height.toLong()
                },
        )
    }

    fun validate(
        capabilities: SharedCaptureCapabilities,
        requestedWidth: Int,
        requestedHeight: Int,
        defaultIso: Int? = null,
        defaultExposureTimeMicros: Long? = null,
    ) {
        validateAndReport(
            capabilities = capabilities,
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight,
            defaultIso = defaultIso,
            defaultExposureTimeMicros = defaultExposureTimeMicros,
        )
    }

    private data class ResolutionSelection(
        val resolution: Pair<Int, Int>,
        val reason: String,
    )

    private fun selectEffectiveResolution(
        supportedOutputSizes: List<Pair<Int, Int>>,
        requestedWidth: Int,
        requestedHeight: Int,
    ): ResolutionSelection {
        val exactMatch =
            supportedOutputSizes.firstOrNull { (width, height) ->
                width == requestedWidth && height == requestedHeight
            }
        if (exactMatch != null) {
            return ResolutionSelection(
                resolution = exactMatch,
                reason = "exact",
            )
        }

        val requestedPixels = requestedWidth.toLong() * requestedHeight.toLong()
        val requestedAspectRatio = requestedWidth.toDouble() / requestedHeight.toDouble()

        val nearest =
            supportedOutputSizes.minWith(
                compareBy<Pair<Int, Int>> { (width, height) ->
                    kotlin.math.abs(width.toLong() * height.toLong() - requestedPixels)
                }.thenBy { (width, height) ->
                    kotlin.math.abs((width.toDouble() / height.toDouble()) - requestedAspectRatio)
                }.thenByDescending { (width, height) ->
                    width.toLong() * height.toLong()
                },
            )
        return ResolutionSelection(
            resolution = nearest,
            reason = "nearest_supported",
        )
    }

    private fun describeTimestampSource(timestampSource: Int?): String =
        when (timestampSource) {
            TIMESTAMP_SOURCE_REALTIME -> "realtime"
            TIMESTAMP_SOURCE_UNKNOWN -> "unknown"
            null -> "missing"
            else -> "other_$timestampSource"
        }
}
