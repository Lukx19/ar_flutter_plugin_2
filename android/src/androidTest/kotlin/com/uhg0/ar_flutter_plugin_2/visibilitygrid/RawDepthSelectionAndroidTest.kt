package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RawDepthSelectionAndroidTest {
    @Test
    fun directPixelBuffersFinishWithinOneSecondWithoutPerFrameBufferAllocation() {
        val width = 2_000
        val height = 2_000
        val depthPixels = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        val confidencePixels = ByteBuffer.allocateDirect(width * height)
        repeat(width * height) { index ->
            depthPixels.putShort(index * 2, 1_000.toShort())
            confidencePixels.put(index, 255.toByte())
        }
        for (y in 980..991) for (x in 980..991) {
            depthPixels.putShort((y * width + x) * 2, 500.toShort())
        }
        for (y in 980..991) for (x in 1_005..1_016) {
            depthPixels.putShort((y * width + x) * 2, 700.toShort())
        }
        val source = RawDepthCopySource(
            acquirer = object : PairedRawDepthAcquirer {
                override fun acquireDepth() = object : RawDepthImage {
                    override val width = 2_000
                    override val height = 2_000
                    override fun unsignedValue(x: Int, y: Int) =
                        depthPixels.getShort((y * width + x) * 2).toInt() and 0xffff
                    override fun close() = Unit
                }
                override fun acquireConfidence() = object : RawDepthImage {
                    override val width = 2_000
                    override val height = 2_000
                    override fun unsignedValue(x: Int, y: Int) =
                        confidencePixels.get(y * width + x).toInt() and 0xff
                    override fun close() = Unit
                }
            },
            maxCopiedPixels = V2_DEPTH_SAMPLE_CAPACITY,
        )
        val metadata = RawDepthFrameMetadata(
            timestampNs = 1, groupGeneration = 1, sessionGeneration = 1, tracking = true,
            width = width, height = height,
            intrinsics = DepthIntrinsics(1_000.0, 1_000.0, 1_000.0, 1_000.0),
            worldFromCameraGl = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 },
        )
        repeat(2) { source.acquire(metadata) }
        val durations = (1..3).map {
            val started = SystemClock.elapsedRealtimeNanos()
            val observation = source.acquire(metadata) as DepthAcquisitionResult.Observation
            assertTrue(observation.value.samples.size <= V2_DEPTH_SAMPLE_CAPACITY)
            assertTrue(observation.value.samples.any { it.depthMillimeters == 500 })
            assertTrue(observation.value.samples.any { it.depthMillimeters == 700 })
            SystemClock.elapsedRealtimeNanos() - started
        }
        val worstMillis = durations.max() / 1_000_000.0
        Log.i("RawDepthSelectionTest", "depth_direct_buffer_2000x2000_worst_ms=$worstMillis")
        assertTrue("Direct-buffer selection took " + worstMillis + "ms", worstMillis < 1_000.0)
    }

    @Test
    fun largeImagePreservesSmallForegroundWithinOneSecond() {
        val depth = object : RawDepthImage {
            override val width = 2_000
            override val height = 2_000
            override fun unsignedValue(x: Int, y: Int): Int =
                if (x in 990..1_001 && y in 990..1_001) 500 else 1_000
            override fun close() = Unit
        }
        val confidence = object : RawDepthImage {
            override val width = 2_000
            override val height = 2_000
            override fun unsignedValue(x: Int, y: Int) = 255
            override fun close() = Unit
        }
        val source = RawDepthCopySource(
            acquirer = object : PairedRawDepthAcquirer {
                override fun acquireDepth() = depth
                override fun acquireConfidence() = confidence
            },
            maxCopiedPixels = V2_DEPTH_SAMPLE_CAPACITY,
        )
        val metadata = RawDepthFrameMetadata(
            timestampNs = 1,
            groupGeneration = 1,
            sessionGeneration = 1,
            tracking = true,
            width = 2_000,
            height = 2_000,
            intrinsics = DepthIntrinsics(1_000.0, 1_000.0, 1_000.0, 1_000.0),
            worldFromCameraGl = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 },
        )
        repeat(2) { source.acquire(metadata) }
        val durations = (1..3).map {
            val started = SystemClock.elapsedRealtimeNanos()
            val result = source.acquire(metadata) as DepthAcquisitionResult.Observation
            assertTrue(result.value.samples.size <= V2_DEPTH_SAMPLE_CAPACITY)
            assertTrue(result.value.samples.any { it.depthMillimeters == 500 })
            SystemClock.elapsedRealtimeNanos() - started
        }
        val worstMillis = durations.max() / 1_000_000.0
        Log.i("RawDepthSelectionTest", "depth_selection_2000x2000_worst_ms=$worstMillis")
        assertTrue("Depth selection took ${worstMillis}ms", worstMillis < 1_000.0)
    }

    @Test
    fun largeImageCompletesOffCallbackAndClosesEachImageOnce() {
        val depthClosed = AtomicInteger()
        val confidenceClosed = AtomicInteger()
        val depth = object : RawDepthImage {
            override val width = 2_000
            override val height = 2_000
            override fun unsignedValue(x: Int, y: Int): Int =
                if (x in 990..1_001 && y in 990..1_001) 500 else 1_000
            override fun close() {
                depthClosed.incrementAndGet()
            }
        }
        val confidence = object : RawDepthImage {
            override val width = 2_000
            override val height = 2_000
            override fun unsignedValue(x: Int, y: Int) = 255
            override fun close() {
                confidenceClosed.incrementAndGet()
            }
        }
        val metadata = RawDepthFrameMetadata(
            timestampNs = 1,
            groupGeneration = 1,
            sessionGeneration = 1,
            tracking = true,
            width = 2_000,
            height = 2_000,
            intrinsics = DepthIntrinsics(1_000.0, 1_000.0, 1_000.0, 1_000.0),
            worldFromCameraGl = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 },
        )
        val completed = CountDownLatch(1)
        val published = AtomicReference<DepthAcquisitionResult>()
        val processor = BoundedDepthObservationProcessor<PreparedRawDepthFrame, DepthAcquisitionResult>(
            process = PreparedRawDepthFrame::process,
            publish = { result, _ -> published.set(result); completed.countDown() },
        )
        try {
            val started = SystemClock.elapsedRealtimeNanos()
            assertTrue(processor.offer(
                PreparedRawDepthFrame(metadata, depth, confidence, V2_DEPTH_SAMPLE_CAPACITY), 0,
            ))
            val callbackMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            val totalMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            val result = published.get() as DepthAcquisitionResult.Observation
            assertTrue(result.value.samples.any { it.depthMillimeters == 500 })
            assertTrue(result.value.samples.size <= V2_DEPTH_SAMPLE_CAPACITY)
            assertTrue(processor.awaitIdle(1_000))
            assertTrue("Callback took ${callbackMillis}ms", callbackMillis < 2.0)
            assertTrue("Depth pipeline took ${totalMillis}ms", totalMillis < 1_000.0)
            assertTrue(depthClosed.get() == 1)
            assertTrue(confidenceClosed.get() == 1)
            Log.i("RawDepthSelectionTest", "depth_worker_2000x2000_callback_ms=$callbackMillis total_ms=$totalMillis")
        } finally {
            processor.close()
        }
    }
}
