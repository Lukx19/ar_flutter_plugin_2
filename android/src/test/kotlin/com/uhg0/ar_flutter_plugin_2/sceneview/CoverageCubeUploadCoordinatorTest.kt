package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageCubeUploadCoordinatorTest {
    @Test
    fun `cube driver completion requests one later renderer frame`() {
        val uploader = FakeCubeUploader()
        var releasedPages = 0
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 1,
            halfSize = 0.5f,
            uploader = uploader,
            onUploadPageReleased = { releasedPages++ },
        )

        coordinator.submit(cubeSnapshot(1, floatArrayOf(1f, 1f, 1f)))
        coordinator.onRendererFrame()
        uploader.completeAll()

        assertEquals(1, releasedPages)
    }

    @Test
    fun `each bounded page explicitly flushes after both cube buffers are queued`() {
        val uploader = FakeCubeUploader()
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 1,
            halfSize = 0.5f,
            uploader = uploader,
        )

        coordinator.submit(cubeSnapshot(1, floatArrayOf(1f, 1f, 1f)))
        coordinator.onRendererFrame()

        assertEquals(1, uploader.flushCount)
        uploader.completeAll()
        coordinator.onRendererFrame()
        assertEquals(1, uploader.flushCount)
    }

    @Test
    fun `cube reset and checkpoint callbacks retain their page origins`() {
        val uploader = FakeCubeUploader()
        val submittedOrigins = mutableListOf<RendererUploadPageOrigin>()
        val callbackOrigins = mutableListOf<RendererUploadPageOrigin>()
        val completionOrigins = mutableListOf<RendererUploadPageOrigin>()
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 1,
            halfSize = 0.5f,
            uploader = uploader,
            onUploadAttributed = { _, origin -> submittedOrigins += origin },
            onUploadCallbackAttributed = callbackOrigins::add,
            onUploadCompletedAttributed = { _, origin -> completionOrigins += origin },
        )

        coordinator.submitForResourceGeneration(cubeSnapshot(7, floatArrayOf(7f, 7f, 7f)))
        coordinator.submit(cubeSnapshot(8, floatArrayOf(8f, 8f, 8f)))
        coordinator.onRendererFrame()
        uploader.completeAll()
        coordinator.onRendererFrame()
        uploader.completeAll()

        assertEquals(
            listOf(
                RendererUploadPageOrigin.RESOURCE_GENERATION_RESET,
                RendererUploadPageOrigin.ORDINARY,
            ),
            submittedOrigins,
        )
        assertEquals(
            listOf(
                RendererUploadPageOrigin.RESOURCE_GENERATION_RESET,
                RendererUploadPageOrigin.RESOURCE_GENERATION_RESET,
                RendererUploadPageOrigin.ORDINARY,
                RendererUploadPageOrigin.ORDINARY,
            ),
            callbackOrigins,
        )
        assertEquals(submittedOrigins, completionOrigins)
    }

    @Test
    fun `retained cube reset waits for a subsequent renderer frame`() {
        val uploader = FakeCubeUploader()
        var resetSchedules = 0
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 1,
            halfSize = 0.5f,
            uploader = uploader,
            onResourceResetScheduled = { resetSchedules++ },
        )

        coordinator.onRendererFrame()
        coordinator.submitForResourceGeneration(
            cubeSnapshot(1, floatArrayOf(1f, 1f, 1f)),
        )

        assertEquals(1, resetSchedules)
        assertTrue(uploader.positionSubmissions.isEmpty())
        assertTrue(uploader.colorSubmissions.isEmpty())

        coordinator.onRendererFrame()

        assertEquals(1, uploader.positionSubmissions.size)
        assertEquals(1, uploader.colorSubmissions.size)
    }

    @Test
    fun `one voxel expands to eight colored cube vertices`() {
        val uploader = FakeCubeUploader()
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 2,
            halfSize = 0.5f,
            uploader = uploader,
        )

        coordinator.submit(
            cubeSnapshot(
                revision = 1,
                position = floatArrayOf(1f, 2f, 3f),
                color = 0x7F112233,
            ),
        )
        coordinator.onRendererFrame()

        assertArrayEquals(
            floatArrayOf(
                0.5f, 1.5f, 2.5f,
                1.5f, 1.5f, 2.5f,
                1.5f, 2.5f, 2.5f,
                0.5f, 2.5f, 2.5f,
                0.5f, 1.5f, 3.5f,
                1.5f, 1.5f, 3.5f,
                1.5f, 2.5f, 3.5f,
                0.5f, 2.5f, 3.5f,
            ),
            uploader.positionSubmissions.single(),
            0f,
        )
        assertArrayEquals(
            ByteArray(8 * 4) { index ->
                byteArrayOf(0x11, 0x22, 0x33, 0x7F)[index % 4]
            },
            uploader.colorSubmissions.single(),
        )
        assertEquals(24, uploader.positionElementCounts.single())
        assertEquals(32, uploader.colorByteCounts.single())
    }

    @Test
    fun `busy cube upload retains its buffers and publishes the newest snapshot`() {
        val uploader = FakeCubeUploader()
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 2,
            halfSize = 0.5f,
            uploader = uploader,
        )

        coordinator.submit(cubeSnapshot(1, floatArrayOf(1f, 1f, 1f)))
        coordinator.onRendererFrame()
        coordinator.submit(cubeSnapshot(2, floatArrayOf(2f, 2f, 2f)))
        coordinator.submit(cubeSnapshot(3, floatArrayOf(3f, 3f, 3f)))

        assertEquals(1, uploader.positionSubmissions.size)
        uploader.completeAll()
        assertEquals(1, uploader.positionSubmissions.size)
        coordinator.onRendererFrame()

        assertEquals(2, uploader.positionSubmissions.size)
        assertArrayEquals(
            floatArrayOf(2.5f, 2.5f, 2.5f),
            uploader.positionSubmissions.last().copyOfRange(0, 3),
            0f,
        )
    }

    @Test
    fun `queued incremental cube update keeps its one dirty destination`() {
        val uploader = FakeCubeUploader()
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 2,
            halfSize = 0.5f,
            uploader = uploader,
        )

        coordinator.submit(twoCubeSnapshot(1, 1f))
        coordinator.onRendererFrame()
        uploader.completeAll()

        coordinator.submit(twoCubeSnapshot(2, 2f, dirtySecondCube = true))
        coordinator.onRendererFrame()
        coordinator.submit(twoCubeSnapshot(3, 3f, dirtySecondCube = true))
        uploader.completeAll()
        coordinator.onRendererFrame()

        // The retained baseline makes this a one-row update, even after a
        // busy callback coalesces it. A reset would submit both cubes at
        // destination zero instead.
        assertEquals(listOf(0, 8 * 3 * Float.SIZE_BYTES, 8 * 3 * Float.SIZE_BYTES), uploader.positionOffsets)
        assertEquals(8 * 3, uploader.positionSubmissions.last().size)
        assertEquals(2.5f, uploader.positionSubmissions.last().first(), 0f)
    }

    @Test
    fun `cube corners follow the visibility grid rotation`() {
        val uploader = FakeCubeUploader()
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 1,
            halfSize = 0.5f,
            uploader = uploader,
        )

        coordinator.submit(
            cubeSnapshot(
                revision = 1,
                position = floatArrayOf(0f, 0f, 0f),
                gridRotationWorld = floatArrayOf(
                    0f, 1f, 0f,
                    -1f, 0f, 0f,
                    0f, 0f, 1f,
                ),
            ),
        )
        coordinator.onRendererFrame()

        assertArrayEquals(
            floatArrayOf(0.5f, -0.5f, -0.5f),
            uploader.positionSubmissions.single().copyOfRange(0, 3),
            0f,
        )
    }

    @Test
    fun `destroy cancels a pending cube upload and late callbacks are harmless`() {
        val uploader = FakeCubeUploader()
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 1,
            halfSize = 0.5f,
            uploader = uploader,
        )

        coordinator.submit(cubeSnapshot(1, floatArrayOf(1f, 1f, 1f)))
        coordinator.onRendererFrame()
        coordinator.submit(cubeSnapshot(2, floatArrayOf(2f, 2f, 2f)))
        coordinator.destroy()
        uploader.completeAll()

        assertEquals(1, uploader.positionSubmissions.size)
    }

    @Test
    fun `cube upload reports hand-off duration after both callbacks`() {
        val uploader = FakeCubeUploader()
        var now = 10L
        val completions = mutableListOf<Long>()
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 1,
            halfSize = 0.5f,
            uploader = uploader,
            onUploadCompleted = completions::add,
            clockNanos = { now },
        )

        coordinator.submit(cubeSnapshot(1, floatArrayOf(1f, 1f, 1f)))
        coordinator.onRendererFrame()
        now = 90L
        uploader.completeAll()

        assertEquals(listOf(80L), completions)
    }

    @Test
    fun `a large cube reset is paged at the ordinary upload ceiling`() {
        val uploader = FakeCubeUploader()
        val submittedBytes = mutableListOf<Int>()
        val count = 513
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = count,
            halfSize = 0.5f,
            uploader = uploader,
            onUploadSubmitted = submittedBytes::add,
        )

        coordinator.submit(
            CoveragePointRenderSnapshot(
                revision = 1,
                enabled = true,
                capacity = count,
                count = count,
                keys = LongArray(count) { it.toLong() },
                positions = FloatArray(count * 3),
                colors = IntArray(count) { 0xFF000000.toInt() },
            ),
        )
        coordinator.onRendererFrame()
        uploader.completeAll()
        assertEquals(1, uploader.positionSubmissions.size)
        coordinator.onRendererFrame()
        uploader.completeAll()

        assertEquals(listOf(64 * 1024, 128), submittedBytes)
        assertTrue(submittedBytes.all { it <= 64 * 1024 })
        assertEquals(listOf(0, 512 * 8 * 3 * Float.SIZE_BYTES), uploader.positionOffsets)
        assertEquals(listOf(0, 512 * 8 * 4), uploader.colorOffsets)
    }

    @Test
    fun `destroy after a paged cube callback prevents next frame resume`() {
        val uploader = FakeCubeUploader()
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 513,
            halfSize = 0.5f,
            uploader = uploader,
        )
        coordinator.submit(
            CoveragePointRenderSnapshot(
                revision = 1,
                enabled = true,
                capacity = 513,
                count = 513,
                keys = LongArray(513) { it.toLong() },
                positions = FloatArray(513 * 3),
                colors = IntArray(513),
            ),
        )
        coordinator.onRendererFrame()

        uploader.completeAll()
        coordinator.destroy()
        coordinator.onRendererFrame()

        assertEquals(1, uploader.positionSubmissions.size)
    }
}

private class FakeCubeUploader : CoverageCubeMeshResources.CoverageCubeVertexUploader {
    val positionSubmissions = mutableListOf<FloatArray>()
    val colorSubmissions = mutableListOf<ByteArray>()
    val positionElementCounts = mutableListOf<Int>()
    val colorByteCounts = mutableListOf<Int>()
    var flushCount = 0
    val positionOffsets = mutableListOf<Int>()
    val colorOffsets = mutableListOf<Int>()
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    override fun uploadPositions(
        buffer: FloatBuffer,
        destOffsetBytes: Int,
        elementCount: Int,
        onConsumed: () -> Unit,
    ) {
        positionOffsets += destOffsetBytes
        positionSubmissions += FloatArray(buffer.remaining()).also { copy ->
            buffer.duplicate().get(copy)
        }
        positionElementCounts += elementCount
        pendingCallbacks += onConsumed
    }

    override fun uploadColors(
        buffer: ByteBuffer,
        destOffsetBytes: Int,
        byteCount: Int,
        onConsumed: () -> Unit,
    ) {
        colorOffsets += destOffsetBytes
        colorSubmissions += ByteArray(buffer.remaining()).also { copy ->
            buffer.duplicate().get(copy)
        }
        colorByteCounts += byteCount
        pendingCallbacks += onConsumed
    }

    override fun flush() {
        flushCount++
    }

    fun completeAll() {
        while (pendingCallbacks.isNotEmpty()) {
            pendingCallbacks.removeAt(0).invoke()
        }
    }
}

private fun cubeSnapshot(
    revision: Long,
    position: FloatArray,
    color: Int = 0xFF445566.toInt(),
    gridRotationWorld: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    ),
): CoveragePointRenderSnapshot = CoveragePointRenderSnapshot(
    revision = revision,
    enabled = true,
    capacity = 2,
    count = 1,
    keys = longArrayOf(revision),
    positions = position,
    colors = intArrayOf(color),
    gridRotationWorld = gridRotationWorld,
)

private fun twoCubeSnapshot(
    revision: Long,
    secondCubePosition: Float,
    dirtySecondCube: Boolean = false,
): CoveragePointRenderSnapshot = CoveragePointRenderSnapshot(
    revision = revision,
    enabled = true,
    capacity = 2,
    count = 2,
    keys = longArrayOf(1, 2),
    positions = floatArrayOf(1f, 1f, 1f, secondCubePosition, secondCubePosition, secondCubePosition),
    colors = intArrayOf(0xFF445566.toInt(), 0xFF445566.toInt()),
    update = if (dirtySecondCube) {
        com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate(
            geometryRevision = revision,
            visibilityRevision = revision,
            enabled = true,
            count = 2,
            spans = listOf(
                com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan(
                    startSlot = 1,
                    positions = floatArrayOf(
                        secondCubePosition,
                        secondCubePosition,
                        secondCubePosition,
                    ),
                    colors = intArrayOf(0xFF445566.toInt()),
                ),
            ),
            reset = false,
        )
    } else {
        null
    },
)
