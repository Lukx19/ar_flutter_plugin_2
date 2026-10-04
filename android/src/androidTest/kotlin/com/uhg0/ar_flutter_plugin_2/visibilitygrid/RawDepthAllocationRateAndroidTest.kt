package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RawDepthAllocationRateAndroidTest {
    @Test(timeout = 50_000)
    fun largeDepthSelectionReportsPerMinuteAllocationRate() {
        val closed = AtomicInteger()
        val published = AtomicInteger()
        val minimumSamples = AtomicInteger(Int.MAX_VALUE)
        val packedPool = DepthSamplesLeasePool()
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
        var completion = CountDownLatch(1)
        val processor = BoundedDepthObservationProcessor<PreparedRawDepthFrame, PackedDepthAcquisitionResult>(
            process = { prepared ->
                prepared.processPacked(
                    packedPool,
                    SampleLeaseGeneration(1, metadata.sessionGeneration, metadata.groupGeneration, metadata.timestampNs),
                )
            },
            publish = { result, _ ->
                val observation = result as PackedDepthAcquisitionResult.Observation
                val samples = observation.lease.samples
                minimumSamples.getAndUpdate { minOf(it, samples.count) }
                if ((0 until samples.count).any { samples.depthMillimetresAt(it) == 500 }) {
                    published.incrementAndGet()
                }
                observation.lease.close()
                completion.countDown()
            },
        )
        fun frame() = PreparedRawDepthFrame(
            metadata,
            SyntheticImage(2_000, 2_000, closed, foreground = true),
            SyntheticImage(2_000, 2_000, closed, foreground = false),
            V2_DEPTH_SAMPLE_CAPACITY,
        )
        try {
            repeat(10) {
                completion = CountDownLatch(1)
                assertTrue(processor.offer(frame(), 0))
                assertTrue(completion.await(2, TimeUnit.SECONDS))
                assertTrue(processor.awaitIdle(2_000))
            }
            val allocatedBefore = artStat("art.gc.bytes-allocated")
            val freedBefore = artStat("art.gc.bytes-freed")
            val gcBefore = artStat("art.gc.gc-count")
            val nativeBefore = Debug.getNativeHeapAllocatedSize()
            var nativePeak = nativeBefore
            val started = SystemClock.elapsedRealtime()
            var accepted = 0
            var dropped = 0
            repeat(120) { index ->
                completion = CountDownLatch(1)
                if (processor.offer(frame(), 0)) {
                    accepted++
                    assertTrue(completion.await(2, TimeUnit.SECONDS))
                } else {
                    dropped++
                }
                if (index % 20 == 19) {
                    nativePeak = maxOf(nativePeak, Debug.getNativeHeapAllocatedSize())
                }
                val nextOffer = started + (index + 1) * 250L
                SystemClock.sleep(maxOf(0L, nextOffer - SystemClock.elapsedRealtime()))
            }
            assertTrue(processor.awaitIdle(2_000))
            val elapsed = SystemClock.elapsedRealtime() - started
            val allocated = artStat("art.gc.bytes-allocated") - allocatedBefore
            val freed = artStat("art.gc.bytes-freed") - freedBefore
            val nativeAfter = Debug.getNativeHeapAllocatedSize()
            assertTrue(elapsed in 30_000..50_000)
            assertTrue(accepted > 0)
            assertEquals(120, accepted + dropped)
            assertEquals(accepted + 10, published.get())
            assertEquals(260, closed.get())
            assertTrue("minimum retained samples ${minimumSamples.get()}", minimumSamples.get() > 1_536)
            assertTrue(allocated >= 0 && freed >= 0)
            val allocationRate = allocated * 60_000 / elapsed
            // The 4,096-slot content-aware selector measured 51,696,931
            // bytes/min at four Hz on API 30. The 64 MiB/min guard allows
            // this density while still catching per-pixel boxing; pooled
            // observations remain a later allocation improvement.
            assertTrue("ART allocation rate $allocationRate", allocationRate <= 64L * 1024L * 1024L)
            Log.i(
                "RawDepthAllocationRate",
                "depth_2000x2000_duration_ms=$elapsed offered=120 accepted=$accepted dropped=$dropped " +
                    "minimum_retained_samples=${minimumSamples.get()} " +
                    "art_allocated_bytes_per_minute=$allocationRate " +
                    "art_freed_bytes_per_minute=${freed * 60_000 / elapsed} " +
                    "art_gc_count=${artStat("art.gc.gc-count") - gcBefore} " +
                    "native_heap_start_bytes=$nativeBefore native_heap_end_bytes=$nativeAfter " +
                    "native_heap_peak_sample_bytes=$nativePeak",
            )
        } finally {
            processor.close()
            packedPool.close()
        }
    }

    @Test(timeout = 50_000)
    fun depthSelectionMappingFusionAndCanonicalPreparationReportSeparateRate() {
        val closed = AtomicInteger()
        val published = AtomicInteger()
        val minimumSamples = AtomicInteger(Int.MAX_VALUE)
        val completion = CountDownLatch(1)
        val sequence = AtomicInteger()
        val identity = identityTransform()
        val groupFrame = VisibilityGroupFrame.copyOf(identity, identity, 100_000, 100_000)
        val canonical = emptyCanonicalView()
        val kernel = DepthEvidenceKernel()
        val packedPool = DepthSamplesLeasePool()
        val metadata = RawDepthFrameMetadata(
            timestampNs = 1,
            groupGeneration = 1,
            sessionGeneration = 1,
            tracking = true,
            width = 2_000,
            height = 2_000,
            intrinsics = DepthIntrinsics(1_000.0, 1_000.0, 1_000.0, 1_000.0),
            worldFromCameraGl = identity,
        )
        fun frame() = PreparedRawDepthFrame(
            metadata,
            SyntheticImage(2_000, 2_000, closed, foreground = true),
            SyntheticImage(2_000, 2_000, closed, foreground = false),
            V2_DEPTH_SAMPLE_CAPACITY,
        )
        val processor = BoundedDepthObservationProcessor<PreparedRawDepthFrame, PackedDepthAcquisitionResult>(
            process = { prepared ->
                val observation = prepared.processPacked(
                    packedPool,
                    SampleLeaseGeneration(1, metadata.sessionGeneration, metadata.groupGeneration, sequence.incrementAndGet().toLong()),
                ) as PackedDepthAcquisitionResult.Observation
                val samples = observation.lease.samples
                val batchSequence = sequence.get().toLong()
                val batch = DepthEvidenceMetadata(
                    batchSequence,
                    batchSequence,
                    groupFrame,
                    identity.toList(),
                    VisibilityCameraIntrinsics(2_000, 2_000, 1_000.0, 1_000.0, 1_000.0, 1_000.0),
                    samples.rejectedCount,
                    true,
                )
                val result = kernel.prepare(samples, batch, canonical)
                check(result is DepthEvidenceResult.Accepted) { "mapping/fusion preparation refused: $result" }
                kernel.discardPrepared()
                observation
            },
            publish = { result, _ ->
                val observation = result as PackedDepthAcquisitionResult.Observation
                minimumSamples.getAndUpdate { minOf(it, observation.lease.samples.count) }
                published.incrementAndGet()
                observation.lease.close()
                completion.countDown()
            },
        )
        try {
            repeat(10) {
                assertTrue(processor.offer(frame(), 0))
                assertTrue(completion.await(2, TimeUnit.SECONDS))
                assertTrue(processor.awaitIdle(2_000))
            }
            val allocatedBefore = artStat("art.gc.bytes-allocated")
            val freedBefore = artStat("art.gc.bytes-freed")
            val gcBefore = artStat("art.gc.gc-count")
            val nativeBefore = Debug.getNativeHeapAllocatedSize()
            var nativePeak = nativeBefore
            val started = SystemClock.elapsedRealtime()
            var accepted = 0
            var dropped = 0
            repeat(120) { index ->
                if (processor.offer(frame(), 0)) {
                    accepted++
                    assertTrue(completion.await(2, TimeUnit.SECONDS))
                } else {
                    dropped++
                }
                if (index % 20 == 19) {
                    nativePeak = maxOf(nativePeak, Debug.getNativeHeapAllocatedSize())
                }
                val nextOffer = started + (index + 1) * 250L
                SystemClock.sleep(maxOf(0L, nextOffer - SystemClock.elapsedRealtime()))
            }
            assertTrue(processor.awaitIdle(2_000))
            val elapsed = SystemClock.elapsedRealtime() - started
            val allocated = artStat("art.gc.bytes-allocated") - allocatedBefore
            val freed = artStat("art.gc.bytes-freed") - freedBefore
            val nativeAfter = Debug.getNativeHeapAllocatedSize()
            assertTrue(elapsed in 30_000..50_000)
            assertTrue(accepted > 0)
            assertEquals(120, accepted + dropped)
            assertEquals(accepted + 10, published.get())
            assertEquals(260, closed.get())
            assertTrue("minimum retained samples ${minimumSamples.get()}", minimumSamples.get() > 1_536)
            assertTrue(allocated >= 0 && freed >= 0)
            val allocationRate = allocated * 60_000 / elapsed
            // This is intentionally a separate guard from selection-only
            // allocation: it includes depth mapping, ray traversal, fusion,
            // and canonical preparation. The tablet baseline was
            // 548,598,050 bytes/min; keep a small diagnostic margin while
            // the optimized owner is measured on this same fixed workload.
            val currentBaselineGuard = 600L * 1024L * 1024L
            Log.i(
                "RawDepthAllocationRate",
                "depth_2000x2000_full_processing_duration_ms=$elapsed offered=120 " +
                    "accepted=$accepted dropped=$dropped minimum_retained_samples=${minimumSamples.get()} " +
                    "art_allocated_bytes_per_minute=$allocationRate " +
                    "art_freed_bytes_per_minute=${freed * 60_000 / elapsed} " +
                    "art_gc_count=${artStat("art.gc.gc-count") - gcBefore} " +
                    "native_heap_start_bytes=$nativeBefore native_heap_end_bytes=$nativeAfter " +
                    "native_heap_peak_sample_bytes=$nativePeak " +
                    "current_baseline_guard_bytes_per_minute=$currentBaselineGuard",
            )
            assertTrue("ART full processing allocation rate $allocationRate",
                allocationRate <= currentBaselineGuard)
        } finally {
            processor.close()
            packedPool.close()
            kernel.close()
        }
    }

    private fun artStat(name: String): Long = Debug.getRuntimeStat(name)!!.toLong()

    private fun identityTransform() = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }

    private fun emptyCanonicalView() = object : BoundedCanonicalSurfaceView {
        override val revisionPair = CanonicalRevisionPair(0, 0)
        override val surfaceCount = 0
        override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? = null
        override fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface? = null
        override fun findSurfaceAtInto(x: Int, y: Int, z: Int, scratch: CanonicalSurfaceScratch): Boolean {
            scratch.clear()
            return false
        }
        override fun findSurfaceByIdInto(id: Long, scratch: CanonicalSurfaceScratch): Boolean {
            scratch.clear()
            return false
        }
        override fun visitRayCells(
            startGroupMm: DepthPointMm,
            endpointGroupMm: DepthPointMm,
            maximumVisits: Int,
            visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
        ): DepthRayVisitResult = DepthRaySupercover.visit(
            startGroupMm, endpointGroupMm, 100_000, maximumVisits,
        ) { voxel -> visitor(voxel, null) }
    }

    private class SyntheticImage(
        override val width: Int,
        override val height: Int,
        private val closed: AtomicInteger,
        private val foreground: Boolean,
    ) : RawDepthImage {
        private val wasClosed = AtomicBoolean()
        override fun unsignedValue(x: Int, y: Int): Int =
            if (!foreground) 255 else if ((x / 32) % 2 == 0) 500 else 1_000
        override fun close() {
            if (wasClosed.compareAndSet(false, true)) closed.incrementAndGet()
        }
    }
}
