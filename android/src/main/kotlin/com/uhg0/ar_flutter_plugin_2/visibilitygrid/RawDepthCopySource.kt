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
    private val onResourceAcquired: () -> Unit = {},
    private val onResourceClosed: () -> Unit = {},
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
            depth = acquirer.acquireDepth().also { onResourceAcquired() }
            confidence = acquirer.acquireConfidence().also { onResourceAcquired() }
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
                confidence?.let {
                    it.close()
                    onResourceClosed()
                }
            } finally {
                depth?.let {
                    it.close()
                    onResourceClosed()
                }
            }
        }
    }

    private fun copyObservation(
        metadata: RawDepthFrameMetadata,
        depth: RawDepthImage,
        confidence: RawDepthImage,
    ): DepthAcquisitionResult.Observation {
        require(depth.width in 1..16_384 && depth.height in 1..16_384)
        val pixelCount = Math.multiplyExact(depth.width, depth.height)
        val samples = ArrayList<DepthPixelSample>(minOf(pixelCount, maxCopiedPixels))
        var rejected = 0
        if (pixelCount <= maxCopiedPixels) {
            for (y in 0 until depth.height) {
                for (x in 0 until depth.width) {
                    val depthMillimeters = depth.unsignedValue(x, y)
                    val confidenceValue = confidence.unsignedValue(x, y)
                    if (depthMillimeters == 0 || confidenceValue == 0 ||
                        isDiscontinuity(depth, x, y, depthMillimeters)
                    ) {
                        rejected++
                    } else {
                        samples += DepthPixelSample(x, y, depthMillimeters, confidenceValue)
                    }
                }
            }
        } else {
            // Reserve one spatial representative per tile, then spend spare
            // slots on distinct depth layers. Flat tiles no longer consume a
            // second slot that a small foreground surface elsewhere needs.
            // The full source is scanned once without a per-pixel object array.
            val tileBudget = maxOf(1, maxCopiedPixels * 3 / 4)
            val columns = kotlin.math.sqrt(
                tileBudget.toDouble() * depth.width / depth.height,
            ).toInt().coerceIn(1, minOf(depth.width, tileBudget))
            val rows = minOf(depth.height, maxOf(1, tileBudget / columns))
            val secondLayer = ArrayList<DepthPixelSample>()
            val thirdLayer = ArrayList<DepthPixelSample>()
            for (tileY in 0 until rows) {
                val top = tileY * depth.height / rows
                val bottom = (tileY + 1) * depth.height / rows
                for (tileX in 0 until columns) {
                    val left = tileX * depth.width / columns
                    val right = (tileX + 1) * depth.width / columns
                    var nearDepth = Int.MAX_VALUE
                    var nearX = -1
                    var nearY = -1
                    var secondDepth = Int.MAX_VALUE
                    var secondX = -1
                    var secondY = -1
                    var thirdDepth = Int.MAX_VALUE
                    var thirdX = -1
                    var thirdY = -1
                    for (y in top until bottom) {
                        for (x in left until right) {
                            val value = depth.unsignedValue(x, y)
                            val certainty = confidence.unsignedValue(x, y)
                            if (value == 0 || certainty == 0) {
                                rejected++
                                continue
                            }
                            if (value < nearDepth) {
                                if (nearDepth != Int.MAX_VALUE &&
                                    nearDepth - value > discontinuityThresholdMillimeters
                                ) {
                                    thirdDepth = secondDepth
                                    thirdX = secondX
                                    thirdY = secondY
                                    secondDepth = nearDepth
                                    secondX = nearX
                                    secondY = nearY
                                }
                                nearDepth = value
                                nearX = x
                                nearY = y
                            } else if (value - nearDepth > discontinuityThresholdMillimeters &&
                                value < secondDepth
                            ) {
                                if (secondDepth != Int.MAX_VALUE &&
                                    secondDepth - value > discontinuityThresholdMillimeters
                                ) {
                                    thirdDepth = secondDepth
                                    thirdX = secondX
                                    thirdY = secondY
                                }
                                secondDepth = value
                                secondX = x
                                secondY = y
                            } else if (secondDepth != Int.MAX_VALUE &&
                                value - secondDepth > discontinuityThresholdMillimeters &&
                                value < thirdDepth
                            ) {
                                thirdDepth = value
                                thirdX = x
                                thirdY = y
                            }
                        }
                    }
                    var selectedNear: DepthPixelSample? = null
                    if (nearX >= 0) {
                        selectedNear = stableSample(
                            depth, confidence, nearX, nearY, nearDepth,
                            left, top, right, bottom,
                        )
                        if (selectedNear == null) {
                            rejected++
                            if (secondX >= 0) {
                                selectedNear = stableSample(
                                    depth, confidence, secondX, secondY, secondDepth,
                                    left, top, right, bottom,
                                )
                                if (selectedNear == null) rejected++
                            }
                            if (selectedNear == null && thirdX >= 0) {
                                selectedNear = stableSample(
                                    depth, confidence, thirdX, thirdY, thirdDepth,
                                    left, top, right, bottom,
                                )
                                if (selectedNear == null) rejected++
                            }
                        }
                        selectedNear?.let(samples::add)
                    }
                    if (secondX >= 0 && secondDepth - nearDepth >
                        discontinuityThresholdMillimeters
                    ) {
                        val second = stableSample(
                                depth, confidence, secondX, secondY, secondDepth,
                                left, top, right, bottom,
                            )
                        if (second == null) rejected++ else if (second != selectedNear) {
                            secondLayer += second
                        }
                    }
                    if (thirdX >= 0 && thirdDepth - secondDepth >
                        discontinuityThresholdMillimeters
                    ) {
                        val third = stableSample(
                            depth, confidence, thirdX, thirdY, thirdDepth,
                            left, top, right, bottom,
                        )
                        if (third == null) rejected++ else if (third != selectedNear) {
                            thirdLayer += third
                        }
                    }
                }
            }
            for (layer in listOf(secondLayer, thirdLayer)) {
                for (sample in layer) {
                    if (samples.size == maxCopiedPixels) break
                    samples += sample
                }
            }
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

    private fun stableSample(
        depth: RawDepthImage,
        confidence: RawDepthImage,
        x: Int,
        y: Int,
        targetDepth: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): DepthPixelSample? {
        for (candidateY in maxOf(top, y - 2) until minOf(bottom, y + 3)) {
            for (candidateX in maxOf(left, x - 2) until minOf(right, x + 3)) {
                val value = depth.unsignedValue(candidateX, candidateY)
                if (value == 0 || kotlin.math.abs(value - targetDepth) >
                    discontinuityThresholdMillimeters / 2
                ) continue
                val certainty = confidence.unsignedValue(candidateX, candidateY)
                if (certainty == 0 || isDiscontinuity(depth, candidateX, candidateY, value)) continue
                return DepthPixelSample(candidateX, candidateY, value, certainty)
            }
        }
        return null
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
