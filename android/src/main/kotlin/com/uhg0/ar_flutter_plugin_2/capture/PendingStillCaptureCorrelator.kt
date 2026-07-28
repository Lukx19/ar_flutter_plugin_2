package com.uhg0.ar_flutter_plugin_2.capture

import android.graphics.Rect

internal data class PendingStillImagePayload(
    val sensorTimestampNs: Long,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val receivedAtMs: Long,
)

internal data class PendingStillResultMetadata(
    val frameNumber: Long,
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long,
    val rollingShutterSkewNs: Long,
    val cropRegion: Rect? = null,
    val receivedAtMs: Long,
    val observedTimestampNs: Long? = null,
    val bracketIndex: Int = 0,
)

internal data class CorrelatedStillCapture(
    val image: PendingStillImagePayload,
    val result: PendingStillResultMetadata,
)

internal data class PendingStillCorrelationSnapshot(
    val pendingImages: Int,
    val pendingResults: Int,
)

internal class PendingStillCaptureCorrelator(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val timeoutMs: Long = 1000L,
    private val maxPendingEntries: Int = 8,
    private val completionHistoryLimit: Int = 32,
) {
    private val pendingImagesByTimestampNs = linkedMapOf<Long, PendingStillImagePayload>()
    private val pendingResultsByTimestampNs = linkedMapOf<Long, PendingStillResultMetadata>()
    private val completedFrameNumbers = linkedSetOf<Long>()
    private val completedSensorTimestampsNs = linkedSetOf<Long>()

    @Synchronized
    fun onImageAvailable(
        sensorTimestampNs: Long,
        width: Int,
        height: Int,
        bytes: ByteArray,
    ): CorrelatedStillCapture? {
        cleanupExpired()
        if (completedSensorTimestampsNs.contains(sensorTimestampNs)) {
            return null
        }
        pendingResultsByTimestampNs.remove(sensorTimestampNs)?.let { result ->
            recordCompletion(result.frameNumber, sensorTimestampNs)
            return CorrelatedStillCapture(
                image =
                    PendingStillImagePayload(
                        sensorTimestampNs = sensorTimestampNs,
                        width = width,
                        height = height,
                        bytes = bytes.copyOf(),
                        receivedAtMs = clockMs(),
                    ),
                result = result,
            )
        }

        pendingImagesByTimestampNs.putIfAbsent(
            sensorTimestampNs,
            PendingStillImagePayload(
                sensorTimestampNs = sensorTimestampNs,
                width = width,
                height = height,
                bytes = bytes.copyOf(),
                receivedAtMs = clockMs(),
            ),
        )
        trimPendingMap(pendingImagesByTimestampNs)
        return null
    }

    @Synchronized
    fun onCaptureResult(
        frameNumber: Long,
        sensorTimestampNs: Long,
        exposureTimeNs: Long,
        rollingShutterSkewNs: Long,
        cropRegion: Rect? = null,
        observedTimestampNs: Long? = null,
        bracketIndex: Int = 0,
    ): CorrelatedStillCapture? {
        cleanupExpired()
        if (
            completedFrameNumbers.contains(frameNumber) ||
                completedSensorTimestampsNs.contains(sensorTimestampNs) ||
                pendingResultsByTimestampNs[sensorTimestampNs]?.frameNumber == frameNumber
        ) {
            return null
        }

        pendingImagesByTimestampNs.remove(sensorTimestampNs)?.let { image ->
            recordCompletion(frameNumber, sensorTimestampNs)
            return CorrelatedStillCapture(
                image = image,
                result =
                    PendingStillResultMetadata(
                        frameNumber = frameNumber,
                        sensorTimestampNs = sensorTimestampNs,
                        exposureTimeNs = exposureTimeNs,
                        rollingShutterSkewNs = rollingShutterSkewNs,
                        cropRegion = cropRegion,
                        receivedAtMs = clockMs(),
                    observedTimestampNs = observedTimestampNs,
                    bracketIndex = bracketIndex,
                    ),
            )
        }

        pendingResultsByTimestampNs.putIfAbsent(
            sensorTimestampNs,
            PendingStillResultMetadata(
                frameNumber = frameNumber,
                sensorTimestampNs = sensorTimestampNs,
                exposureTimeNs = exposureTimeNs,
                rollingShutterSkewNs = rollingShutterSkewNs,
                cropRegion = cropRegion,
                receivedAtMs = clockMs(),
                observedTimestampNs = observedTimestampNs,
                bracketIndex = bracketIndex,
            ),
        )
        trimPendingMap(pendingResultsByTimestampNs)
        return null
    }

    @Synchronized
    fun cleanupExpired() {
        val nowMs = clockMs()
        pendingImagesByTimestampNs.entries.removeIf { nowMs - it.value.receivedAtMs > timeoutMs }
        pendingResultsByTimestampNs.entries.removeIf { nowMs - it.value.receivedAtMs > timeoutMs }
    }

    @Synchronized
    fun snapshot(): PendingStillCorrelationSnapshot =
        PendingStillCorrelationSnapshot(
            pendingImages = pendingImagesByTimestampNs.size,
            pendingResults = pendingResultsByTimestampNs.size,
        )

    private fun recordCompletion(
        frameNumber: Long,
        sensorTimestampNs: Long,
    ) {
        completedFrameNumbers.add(frameNumber)
        completedSensorTimestampsNs.add(sensorTimestampNs)

        while (completedFrameNumbers.size > completionHistoryLimit) {
            completedFrameNumbers.remove(completedFrameNumbers.first())
        }
        while (completedSensorTimestampsNs.size > completionHistoryLimit) {
            completedSensorTimestampsNs.remove(completedSensorTimestampsNs.first())
        }
    }

    private fun <T> trimPendingMap(pendingMap: LinkedHashMap<Long, T>) {
        while (pendingMap.size > maxPendingEntries) {
            pendingMap.remove(pendingMap.firstKey())
        }
    }

    private fun <K, V> LinkedHashMap<K, V>.firstKey(): K = entries.first().key
}
