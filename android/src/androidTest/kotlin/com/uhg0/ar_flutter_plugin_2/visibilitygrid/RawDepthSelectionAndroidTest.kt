package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RawDepthSelectionAndroidTest {
    @Test
    fun directPixelBuffersSelectAndPrepareFusionWithinOneSecond() {
        val width = 2_000
        val height = 2_000
        val depthPixels = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        val confidencePixels = ByteBuffer.allocateDirect(width * height)
        repeat(width * height) { index ->
            val x = index % width
            val y = index / width
            // A dense, layered room keeps every tile meaningful while the
            // three small foreground patches exercise the near-surface path.
            val tileX = x * 55 / width
            val tileY = y * 55 / height
            val layer = (tileX * 7 + tileY * 11) % 5
            depthPixels.putShort(index * 2, (700 + layer * 180).toShort())
            confidencePixels.put(index, 255.toByte())
        }
        for (y in 968..976) for (x in 967..975) {
            depthPixels.putShort((y * width + x) * 2, 400.toShort())
        }
        for (y in 968..976) for (x in 993..1_001) {
            depthPixels.putShort((y * width + x) * 2, 600.toShort())
        }
        for (y in 968..976) for (x in 1_018..1_026) {
            depthPixels.putShort((y * width + x) * 2, 300.toShort())
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
        val identity = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }
        val groupFrame = VisibilityGroupFrame.copyOf(identity, identity, 100_000, 100_000)
        val kernel = DepthEvidenceKernel()
        val emptyCanonical = object : BoundedCanonicalSurfaceView {
            override val revisionPair = CanonicalRevisionPair(0, 0)
            override val surfaceCount = 0
            override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? = null
            override fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface? = null
            override fun visitRayCells(
                startGroupMm: DepthPointMm,
                endpointGroupMm: DepthPointMm,
                maximumVisits: Int,
                visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
            ): DepthRayVisitResult = DepthRaySupercover.visit(
                startGroupMm, endpointGroupMm, 100_000, maximumVisits,
            ) { voxel -> visitor(voxel, null) }
        }
        repeat(2) { source.acquire(metadata) }
        val selectionDurations = mutableListOf<Long>()
        val fullDurations = (1..3).map { sequence ->
            val started = SystemClock.elapsedRealtimeNanos()
            val observation = source.acquire(metadata) as DepthAcquisitionResult.Observation
            selectionDurations += SystemClock.elapsedRealtimeNanos() - started
            Log.i("RawDepthSelectionTest", "dense_fixture_samples=${observation.value.samples.size} " +
                "depths=${observation.value.samples.map { it.depthMillimeters }.distinct().sorted()}")
            assertTrue(observation.value.samples.size <= V2_DEPTH_SAMPLE_CAPACITY)
            assertTrue("400 mm patch missing", observation.value.samples.any { it.depthMillimeters == 400 })
            assertTrue("600 mm patch missing", observation.value.samples.any { it.depthMillimeters == 600 })
            assertTrue("300 mm patch missing", observation.value.samples.any { it.depthMillimeters == 300 })
            assertTrue("dense fixture retained ${observation.value.samples.size} samples",
                observation.value.samples.size > 1_536)
            assertTrue("layered fixture lost depth variety",
                observation.value.samples.map { it.depthMillimeters }.distinct().size >= 6)
            val batch = DepthEvidenceBatch(
                sequence.toLong(), sequence.toLong(), groupFrame, identity.toList(),
                VisibilityCameraIntrinsics(width, height, 1_000.0, 1_000.0, 1_000.0, 1_000.0),
                observation.value.samples.map {
                    VisibilityDepthSample(it.x, it.y, it.depthMillimeters, it.confidence)
                },
                observation.value.sourceRejectedPixels,
            )
            val result = kernel.prepare(batch, emptyCanonical) as DepthEvidenceResult.Accepted
            assertTrue(result.receipt.acceptedSamples > 0)
            kernel.discardPrepared()
            SystemClock.elapsedRealtimeNanos() - started
        }
        kernel.close()
        val selectionWorstMillis = selectionDurations.max() / 1_000_000.0
        val fullWorstMillis = fullDurations.max() / 1_000_000.0
        Log.i("RawDepthSelectionTest", "depth_direct_buffer_2000x2000_selection_worst_ms=$selectionWorstMillis " +
            "selection_and_fusion_prepare_worst_ms=$fullWorstMillis")
        assertTrue("Direct-buffer selection and fusion preparation took ${fullWorstMillis}ms", fullWorstMillis < 1_000.0)
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
    fun repeatedRealCallbacksKeepP95BelowTwoMillisecondsWithBoundedMax() {
        val depthClosed = AtomicInteger()
        val confidenceClosed = AtomicInteger()
        fun frame(): PreparedRawDepthFrame {
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
            return PreparedRawDepthFrame(metadata(), depth, confidence, V2_DEPTH_SAMPLE_CAPACITY)
        }
        var completed = CountDownLatch(1)
        val foregroundPublished = AtomicInteger()
        val processor = BoundedDepthObservationProcessor<PreparedRawDepthFrame, DepthAcquisitionResult>(
            process = PreparedRawDepthFrame::process,
            publish = { result, _ ->
                if ((result as DepthAcquisitionResult.Observation).value.samples.any {
                    it.depthMillimeters == 500
                }) foregroundPublished.incrementAndGet()
                completed.countDown()
            },
        )
        try {
            repeat(3) {
                completed = CountDownLatch(1)
                assertTrue(processor.offer(frame(), 0))
                assertTrue(completed.await(2, TimeUnit.SECONDS))
                assertTrue(processor.awaitIdle(1_000))
            }
            val callbackNanos = ArrayList<Long>(32)
            repeat(32) {
                completed = CountDownLatch(1)
                val started = SystemClock.elapsedRealtimeNanos()
                assertTrue(processor.offer(frame(), 0))
                callbackNanos += SystemClock.elapsedRealtimeNanos() - started
                assertTrue(completed.await(2, TimeUnit.SECONDS))
                assertTrue(processor.awaitIdle(1_000))
            }
            val sorted = callbackNanos.sorted()
            val p95Index = ((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)
            val p95Nanos = sorted[p95Index]
            val maximumNanos = sorted.last()
            Log.i("RawDepthSelectionTest", "depth_worker_2000x2000_callbacks=${sorted.size} " +
                "callback_p95_us=${p95Nanos / 1_000.0} callback_max_us=${maximumNanos / 1_000.0}")
            assertTrue("callback p95 ${p95Nanos / 1_000.0}us", p95Nanos <= 2_000_000L)
            assertTrue("callback max ${maximumNanos / 1_000.0}us", maximumNanos <= 8_000_000L)
            assertTrue(foregroundPublished.get() == 35)
            assertTrue(depthClosed.get() == 35)
            assertTrue(confidenceClosed.get() == 35)
        } finally {
            processor.close()
        }
    }

    private fun metadata() = RawDepthFrameMetadata(
        timestampNs = 1,
        groupGeneration = 1,
        sessionGeneration = 1,
        tracking = true,
        width = 2_000,
        height = 2_000,
        intrinsics = DepthIntrinsics(1_000.0, 1_000.0, 1_000.0, 1_000.0),
        worldFromCameraGl = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 },
    )
}
