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
        coordinator.submit(cubeSnapshot(2, floatArrayOf(2f, 2f, 2f)))
        coordinator.submit(cubeSnapshot(3, floatArrayOf(3f, 3f, 3f)))

        assertEquals(1, uploader.positionSubmissions.size)
        uploader.completeAll()

        assertEquals(2, uploader.positionSubmissions.size)
        assertArrayEquals(
            floatArrayOf(2.5f, 2.5f, 2.5f),
            uploader.positionSubmissions.last().copyOfRange(0, 3),
            0f,
        )
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
        uploader.completeAll()

        assertEquals(listOf(64 * 1024, 128), submittedBytes)
        assertTrue(submittedBytes.all { it <= 64 * 1024 })
    }
}

private class FakeCubeUploader : CoverageCubeMeshResources.CoverageCubeVertexUploader {
    val positionSubmissions = mutableListOf<FloatArray>()
    val colorSubmissions = mutableListOf<ByteArray>()
    val positionElementCounts = mutableListOf<Int>()
    val colorByteCounts = mutableListOf<Int>()
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    override fun uploadPositions(
        buffer: FloatBuffer,
        destOffsetBytes: Int,
        elementCount: Int,
        onConsumed: () -> Unit,
    ) {
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
        colorSubmissions += ByteArray(buffer.remaining()).also { copy ->
            buffer.duplicate().get(copy)
        }
        colorByteCounts += byteCount
        pendingCallbacks += onConsumed
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
