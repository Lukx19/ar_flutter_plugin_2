package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CoveragePointUploadCoordinatorTest {
    @Test
    fun `busy slot preserves A bytes and coalesces pending updates`() {
        val uploader = FakeUploader()
        val coordinator = CoveragePointUploadCoordinator(4, uploader)
        val a = snapshot(1, 1f)
        val b = snapshot(2, 2f)
        val c = snapshot(3, 3f)
        val d = snapshot(4, 4f)

        coordinator.submit(a)
        val positionsAtA = uploader.positionSubmissions.single().copyOf()
        coordinator.submit(b)
        coordinator.submit(c)
        coordinator.submit(d)
        assertArrayEquals(positionsAtA, uploader.positionSubmissions.single(), 0f)

        uploader.completeNext()
        assertEquals(1, uploader.positionSubmissions.size)
        uploader.completeNext()
        assertEquals(2, uploader.positionSubmissions.size)
        assertArrayEquals(floatArrayOf(4f, 4f, 4f), uploader.positionSubmissions[1], 0f)
    }

    @Test
    fun `stale duplicate callbacks and destroy cannot submit more work`() {
        val uploader = FakeUploader()
        val coordinator = CoveragePointUploadCoordinator(2, uploader)
        coordinator.submit(snapshot(1, 1f))
        val first = uploader.pendingCallbacks[0]
        first()
        first()
        assertEquals(1, uploader.positionSubmissions.size)
        coordinator.submit(snapshot(2, 2f))
        coordinator.destroy()
        uploader.completeAll()
        assertEquals(1, uploader.positionSubmissions.size)
    }

    @Test
    fun `first snapshot after a mesh replacement uploads even without dirty spans`() {
        val uploader = FakeUploader()
        val coordinator = CoveragePointUploadCoordinator(2, uploader)
        coordinator.submit(snapshot(1, 1f))
        uploader.completeAll()

        coordinator.destroy()
        val replacement = CoveragePointUploadCoordinator(2, uploader)
        replacement.submit(snapshot(2, 2f, withEmptyUpdate = true))

        assertEquals(2, uploader.positionSubmissions.size)
    }
}

private class FakeUploader : CoveragePointVertexUploader {
    val positionSubmissions = mutableListOf<FloatArray>()
    val colorSubmissions = mutableListOf<ByteArray>()
    val pendingCallbacks = mutableListOf<() -> Unit>()

    override fun uploadPositions(
        buffer: FloatBuffer,
        destOffsetBytes: Int,
        elementCount: Int,
        onConsumed: () -> Unit,
    ) {
        val copy = FloatArray(buffer.remaining())
        buffer.duplicate().get(copy)
        positionSubmissions += copy
        pendingCallbacks += onConsumed
    }

    override fun uploadColors(
        buffer: ByteBuffer,
        destOffsetBytes: Int,
        byteCount: Int,
        onConsumed: () -> Unit,
    ) {
        val copy = ByteArray(buffer.remaining())
        buffer.duplicate().get(copy)
        colorSubmissions += copy
        pendingCallbacks += onConsumed
    }

    fun completeNext() {
        val callback = pendingCallbacks.removeAt(0)
        callback()
    }

    fun completeAll() {
        while (pendingCallbacks.isNotEmpty()) completeNext()
    }
}

private fun snapshot(
    revision: Long,
    value: Float,
    withEmptyUpdate: Boolean = false,
): CoveragePointRenderSnapshot =
    CoveragePointRenderSnapshot(
        revision = revision,
        enabled = true,
        capacity = 2,
        count = 1,
        keys = longArrayOf(revision),
        positions = floatArrayOf(value, value, value),
        colors = intArrayOf(0xFF000000.toInt()),
        update = if (withEmptyUpdate) {
            com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate(
                geometryRevision = revision,
                visibilityRevision = revision,
                enabled = true,
                count = 1,
                spans = emptyList(),
                reset = false,
            )
        } else {
            null
        },
    )
