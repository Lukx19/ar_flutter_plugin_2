package com.uhg0.ar_flutter_plugin_2.pointcloud

import com.google.ar.core.Frame
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicLong

fun interface PointCloudSourceFactory {
    fun create(config: PointCloudNativeConfig): PointCloudSource
}

interface PointCloudSource {
    fun acquire(frame: Frame?): PointCloudSample?

    fun diagnostics(): PointCloudSourceDiagnostics = PointCloudSourceDiagnostics()
}

data class PointCloudSourceDiagnostics(
    val rawIds: Int = 0,
    val rawPointFloats: Int = 0,
    val acceptedPoints: Int = 0,
    val confidenceRejectedPoints: Long = 0,
    val nonFiniteRejectedPoints: Long = 0,
    val invalidPointCloudAcquisitions: Long = 0,
    val unchangedTimestampAcquisitions: Long = 0,
    val lastTimestampNs: Long = 0,
)

interface AcquiredPointCloud : AutoCloseable {
    val ids: IntBuffer
    val points: FloatBuffer
    val timestampNs: Long
}

fun interface PointCloudAcquirer {
    fun acquire(frame: Frame?): AcquiredPointCloud
}

class ArCorePointCloudSource(
    private val minConfidence: Float,
    private val acquirer: PointCloudAcquirer = PointCloudAcquirer { frame ->
        val pointCloud = requireNotNull(frame).acquirePointCloud()
        object : AcquiredPointCloud {
            override val ids: IntBuffer get() = pointCloud.ids
            override val points: FloatBuffer get() = pointCloud.points
            override val timestampNs: Long get() = pointCloud.timestamp
            override fun close() = pointCloud.release()
        }
    },
) : PointCloudSource {
    private val sequence = AtomicLong(0)
    private var lastPointCloudTimestampNs = Long.MIN_VALUE
    private var rawIds = 0
    private var rawPointFloats = 0
    private var acceptedPoints = 0
    private var confidenceRejectedPoints = 0L
    private var nonFiniteRejectedPoints = 0L
    private var invalidPointCloudAcquisitions = 0L
    private var unchangedTimestampAcquisitions = 0L
    private var lastTimestampNs = 0L

    override fun acquire(frame: Frame?): PointCloudSample? {
        val pointCloud = acquirer.acquire(frame)
        pointCloud.use {
            val sourceIds = it.ids
            val sourcePoints = it.points
            rawIds = sourceIds.remaining()
            rawPointFloats = sourcePoints.remaining()
            acceptedPoints = 0
            lastTimestampNs = it.timestampNs
            if (it.timestampNs <= lastPointCloudTimestampNs) {
                unchangedTimestampAcquisitions++
                return null
            }
            lastPointCloudTimestampNs = it.timestampNs
            val acceptedIds = IntArray(sourceIds.remaining())
            val acceptedPointValues = FloatArray(sourceIds.remaining() * 4)
            val nonFiniteBefore = nonFiniteRejectedPoints
            var accepted = 0
            while (sourceIds.hasRemaining() && sourcePoints.remaining() >= 4) {
                val id = sourceIds.get()
                val x = sourcePoints.get()
                val y = sourcePoints.get()
                val z = sourcePoints.get()
                val confidence = sourcePoints.get()
                if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !confidence.isFinite()) {
                    nonFiniteRejectedPoints++
                    continue
                }
                if (confidence < minConfidence) {
                    confidenceRejectedPoints++
                    continue
                }
                acceptedIds[accepted] = id
                val offset = accepted * 4
                acceptedPointValues[offset] = x
                acceptedPointValues[offset + 1] = y
                acceptedPointValues[offset + 2] = z
                acceptedPointValues[offset + 3] = confidence
                accepted++
            }
            acceptedPoints = accepted
            if (
                accepted == 0 &&
                rawIds > 0 &&
                nonFiniteRejectedPoints > nonFiniteBefore
            ) {
                invalidPointCloudAcquisitions++
            }
            if (accepted == 0) return null
            return PointCloudSample(
                sequence = sequence.getAndIncrement(),
                timestampNs = it.timestampNs,
                ids = acceptedIds.copyOf(accepted),
                points = acceptedPointValues.copyOf(accepted * 4),
            )
        }
    }

    override fun diagnostics(): PointCloudSourceDiagnostics = PointCloudSourceDiagnostics(
        rawIds = rawIds,
        rawPointFloats = rawPointFloats,
        acceptedPoints = acceptedPoints,
        confidenceRejectedPoints = confidenceRejectedPoints,
        nonFiniteRejectedPoints = nonFiniteRejectedPoints,
        invalidPointCloudAcquisitions = invalidPointCloudAcquisitions,
        unchangedTimestampAcquisitions = unchangedTimestampAcquisitions,
        lastTimestampNs = lastTimestampNs,
    )
}

data class SyntheticPointCloudFixture(
    val ids: IntArray,
    val points: FloatArray,
    val timestampStepNs: Long = 100_000_000L,
) {
    init {
        require(points.size == ids.size * 4)
        require(timestampStepNs > 0)
    }
}

class SyntheticPointCloudSource(
    private val fixture: SyntheticPointCloudFixture = defaultFixture(),
) : PointCloudSource {
    private var sequence = 0L

    override fun acquire(frame: Frame?): PointCloudSample = PointCloudSample(
        sequence = sequence,
        timestampNs = sequence++ * fixture.timestampStepNs,
        ids = fixture.ids.copyOf(),
        points = fixture.points.copyOf(),
    )

    companion object {
        fun defaultFixture(): SyntheticPointCloudFixture = SyntheticPointCloudFixture(
            ids = intArrayOf(1, 2, 3, 4),
            points = floatArrayOf(
                -0.1f, 0f, -1f, 1f,
                0.1f, 0f, -1f, 1f,
                0f, -0.1f, -1f, 1f,
                0f, 0.1f, -1f, 1f,
            ),
        )
    }
}
