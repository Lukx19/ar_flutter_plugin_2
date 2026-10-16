package com.uhg0.ar_flutter_plugin_2.markerdetection

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Receives one marker detection message; [message] is valid during the call. */
internal fun interface MarkerDetectionSink {
    fun onMarkerDetections(message: ByteBuffer, length: Int)
}

/** One grey camera image with the pose and intrinsics at its timestamp. */
internal class GreyFrame {
    var grey: ByteBuffer = ByteBuffer.allocateDirect(0)
        private set
    var width = 0
    var height = 0
    var stride = 0
    val header = MarkerDetectionMessageWriter.Header()

    /** Dimensions the intrinsics in [header] were reported for. */
    var intrinsicsWidth = 0
    var intrinsicsHeight = 0

    /** Times the pixel buffer grew (it grows only for a larger image). */
    var growthEvents = 0
        private set

    fun ensureCapacity(bytes: Int) {
        if (grey.capacity() >= bytes) return
        grey = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
        growthEvents++
    }
}

/**
 * Turns grey frames into marker detection messages: native detection, pose,
 * quality gates, then the ingest header with the pose at the image
 * timestamp. One instance per worker thread; buffers are allocated once.
 */
internal class MarkerDetectionProducer(
    private val sink: MarkerDetectionSink,
    dictionary: Int = MarkerDetectorNative.DICTIONARY_ARUCO_MIP_36H12,
    maxRecords: Int = 64,
    factory: MarkerDetectorFactory = MarkerDetectorFactory.NATIVE,
) : GreyFrameDetector {
    private val writer = MarkerDetectionMessageWriter(maxRecords)

    // Created by the first detection or configuration on the worker thread.
    private val detector = LazyMarkerDetector(factory, dictionary)
    private var sequence = 0
    val timings = DoubleArray(3)
    var messages = 0
        private set
    var failures = 0
        private set

    override val available: Boolean get() = detector.available

    /** True once the native detector exists (it is created on first use). */
    val detectorCreated: Boolean get() = detector.isCreated

    fun configure(
        markerSizeMetres: Double,
        sizesById: Map<Int, Double> = emptyMap(),
        minSidePx: Float = 16f,
        minSharpness: Float = 0.12f,
        maxViewingAngleDeg: Float = 75f,
    ) {
        val handle = detector.handle()
        if (handle == 0L) return
        MarkerDetectorNative.nativeSetMarkerSize(handle, markerSizeMetres)
        for ((id, size) in sizesById) MarkerDetectorNative.nativeSetMarkerSizeForId(handle, id, size)
        MarkerDetectorNative.nativeSetQuality(handle, minSidePx, minSharpness, maxViewingAngleDeg)
    }

    @Volatile private var requestedMarkerSize = Double.NaN
    private var appliedMarkerSize = Double.NaN

    /**
     * Asks for [markerSizeMetres] from any thread; the worker applies it
     * before its next detection (the native handle is single-threaded).
     */
    override fun requestMarkerSize(markerSizeMetres: Double) {
        requestedMarkerSize = markerSizeMetres
    }

    override fun detect(frame: GreyFrame) {
        produce(frame)
    }

    /** Detects markers on [frame] and sends one message; returns the count or an error. */
    fun produce(frame: GreyFrame, nowNs: Long = System.nanoTime()): Int {
        val handle = detector.handle()
        if (handle == 0L) {
            failures++
            return MarkerDetectorNative.STATUS_UNAVAILABLE
        }
        val size = requestedMarkerSize
        if (size > 0.0 && size != appliedMarkerSize) {
            configure(size)
            appliedMarkerSize = size
        }
        val h = frame.header
        // Intrinsics scaled to the grey image when it differs from the
        // dimensions the intrinsics were reported for (pixel centres at
        // integer coordinates).
        val sx = if (frame.intrinsicsWidth > 0) frame.width.toDouble() / frame.intrinsicsWidth else 1.0
        val sy = if (frame.intrinsicsHeight > 0) frame.height.toDouble() / frame.intrinsicsHeight else 1.0
        val fx = h.fx * sx
        val fy = h.fy * sy
        val cx = (h.cx + 0.5) * sx - 0.5
        val cy = (h.cy + 0.5) * sy - 0.5
        MarkerDetectorNative.nativeSetCamera(handle, fx, fy, cx, cy, null)
        val count = MarkerDetectorNative.nativeDetect(
            handle,
            frame.grey,
            frame.width,
            frame.height,
            frame.stride,
            writer.buffer,
            MarkerDetectionMessageWriter.RECORDS_OFFSET,
            writer.maxRecords,
        )
        MarkerDetectorNative.nativeTimings(handle, timings)
        if (count < 0) {
            failures++
            return count
        }
        h.sequence = sequence++
        h.sendTimeNs = nowNs
        h.fx = fx.toFloat()
        h.fy = fy.toFloat()
        h.cx = cx.toFloat()
        h.cy = cy.toFloat()
        val length = writer.finish(h, count, frame.width, frame.height)
        messages++
        sink.onMarkerDetections(writer.buffer, length)
        return count
    }

    override fun close() {
        detector.close()
    }
}
