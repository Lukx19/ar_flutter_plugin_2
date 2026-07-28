package com.uhg0.ar_flutter_plugin_2.visibilitygrid

interface RawDepthImage : AutoCloseable {
    val width: Int
    val height: Int

    fun unsignedValue(
        x: Int,
        y: Int,
    ): Int

    override fun close()
}

interface PairedRawDepthAcquirer {
    fun acquireDepth(): RawDepthImage

    fun acquireConfidence(): RawDepthImage
}

class DepthNotYetAvailableException : RuntimeException()

data class RawDepthFrameMetadata(
    val timestampNs: Long,
    val groupGeneration: Long,
    val sessionGeneration: Long,
    val tracking: Boolean,
    val width: Int,
    val height: Int,
    val intrinsics: DepthIntrinsics,
    val worldFromCameraGl: DoubleArray,
    val imageOrientation: DepthImageOrientation = DepthImageOrientation.LANDSCAPE_RIGHT,
)

sealed interface DepthAcquisitionResult {
    data class Observation(val value: DepthObservation) : DepthAcquisitionResult

    data object TransientUnavailable : DepthAcquisitionResult

    data class Failure(val reason: String) : DepthAcquisitionResult
}

class RawDepthCopySource(
    private val acquirer: PairedRawDepthAcquirer,
    private val maxCopiedPixels: Int = 4_096,
    private val discontinuityThresholdMillimeters: Int = 100,
) {
    init {
        require(maxCopiedPixels in 1..4_096)
        require(discontinuityThresholdMillimeters >= 0)
    }

    fun acquire(metadata: RawDepthFrameMetadata): DepthAcquisitionResult {
        return acquire { _, _ -> metadata }
    }

    fun acquire(
        metadataForDimensions: (width: Int, height: Int) -> RawDepthFrameMetadata,
    ): DepthAcquisitionResult {
        var depth: RawDepthImage? = null
        var confidence: RawDepthImage? = null
        return try {
            depth = acquirer.acquireDepth()
            confidence = acquirer.acquireConfidence()
            val metadata = metadataForDimensions(depth.width, depth.height)
            if (
                depth.width != confidence.width ||
                depth.height != confidence.height ||
                depth.width != metadata.width ||
                depth.height != metadata.height
            ) {
                DepthAcquisitionResult.Failure("mismatched depth image dimensions")
            } else {
                copyObservation(metadata, depth, confidence)
            }
        } catch (_: DepthNotYetAvailableException) {
            DepthAcquisitionResult.TransientUnavailable
        } catch (error: RuntimeException) {
            DepthAcquisitionResult.Failure(error.message ?: error.javaClass.simpleName)
        } finally {
            try {
                confidence?.close()
            } finally {
                depth?.close()
            }
        }
    }

    private fun copyObservation(
        metadata: RawDepthFrameMetadata,
        depth: RawDepthImage,
        confidence: RawDepthImage,
    ): DepthAcquisitionResult.Observation {
        val pixelCount = depth.width * depth.height
        val stride = maxOf(1, (pixelCount + maxCopiedPixels - 1) / maxCopiedPixels)
        val samples = ArrayList<DepthPixelSample>(minOf(pixelCount, maxCopiedPixels))
        var rejected = 0
        var index = 0
        while (index < pixelCount && samples.size < maxCopiedPixels) {
            val x = index % depth.width
            val y = index / depth.width
            val depthMillimeters = depth.unsignedValue(x, y)
            val confidenceValue = confidence.unsignedValue(x, y)
            if (isDiscontinuity(depth, x, y, depthMillimeters)) {
                rejected++
            } else {
                samples +=
                    DepthPixelSample(
                        x = x,
                        y = y,
                        depthMillimeters = depthMillimeters,
                        confidence = confidenceValue,
                    )
            }
            index += stride
        }
        return DepthAcquisitionResult.Observation(
            DepthObservation(
                timestampNs = metadata.timestampNs,
                groupGeneration = metadata.groupGeneration,
                sessionGeneration = metadata.sessionGeneration,
                tracking = metadata.tracking,
                width = metadata.width,
                height = metadata.height,
                samples = samples,
                sourceRejectedPixels = rejected,
                intrinsics = metadata.intrinsics,
                worldFromCameraGl = metadata.worldFromCameraGl.copyOf(),
                imageOrientation = metadata.imageOrientation,
            ),
        )
    }

    private fun isDiscontinuity(
        depth: RawDepthImage,
        x: Int,
        y: Int,
        center: Int,
    ): Boolean {
        if (center <= 0) return false
        fun differs(value: Int): Boolean =
            value > 0 &&
                kotlin.math.abs(value - center) > discontinuityThresholdMillimeters
        return (x > 0 && differs(depth.unsignedValue(x - 1, y))) ||
            (x + 1 < depth.width && differs(depth.unsignedValue(x + 1, y))) ||
            (y > 0 && differs(depth.unsignedValue(x, y - 1))) ||
            (y + 1 < depth.height && differs(depth.unsignedValue(x, y + 1)))
    }
}
