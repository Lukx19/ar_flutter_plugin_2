package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoveragePointUploadCoordinatorTest {
    @Test
    fun `retained reset and checkpoint pages retain separate origins`() {
        val uploader = FakeUploader()
        val submittedOrigins = mutableListOf<RendererUploadPageOrigin>()
        val callbackOrigins = mutableListOf<RendererUploadPageOrigin>()
        val completionOrigins = mutableListOf<RendererUploadPageOrigin>()
        val coordinator = CoveragePointUploadCoordinator(
            capacity = 2,
            uploader = uploader,
            onUploadAttributed = { _, origin -> submittedOrigins += origin },
            onUploadCallbackAttributed = callbackOrigins::add,
            onUploadCompletedAttributed = { _, origin -> completionOrigins += origin },
        )

        coordinator.submitForResourceGeneration(snapshot(7, 7f, withEmptyUpdate = true))
        // A checkpoint may republish geometry in the same device measurement
        // window. It is an ordinary page, not a second replacement reset.
        coordinator.submit(snapshot(8, 8f, withEmptyUpdate = true))
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
    fun `retained replacement reset waits for a subsequent renderer frame`() {
        val uploader = FakeUploader()
        val completions = mutableListOf<Long>()
        var resetSchedules = 0
        val coordinator = CoveragePointUploadCoordinator(
            capacity = 2,
            uploader = uploader,
            onResourceResetScheduled = { resetSchedules++ },
            onUploadCompleted = completions::add,
        )
        val retained = snapshot(7, 7f, withEmptyUpdate = true)

        // A frame can race between registering the replacement binding and
        // queuing its retained reset. That earlier frame must not become a
        // reusable token which submits work outside a later renderer frame.
        coordinator.onRendererFrame()
        coordinator.submitForResourceGeneration(retained)
        assertEquals(1, resetSchedules)
        assertTrue(uploader.positionSubmissions.isEmpty())
        assertTrue(uploader.colorSubmissions.isEmpty())

        coordinator.onRendererFrame()
        assertEquals(1, uploader.positionSubmissions.size)
        assertEquals(1, uploader.colorSubmissions.size)
        uploader.completeAll()
        coordinator.onRendererFrame()

        assertEquals(1, uploader.positionSubmissions.size)
        assertEquals(1, uploader.colorSubmissions.size)
        assertEquals(1, completions.size)
    }

    @Test
    fun `only the attached current generation rehydrates a retained cut`() {
        val generationGate = CoverageMeshGenerationGate()
        val oldGeneration = generationGate.reserve()
        // The outgoing composition requests rehydration before NodeLifecycle
        // attaches it. A replacement is reserved before that attach runs.
        val replacementGeneration = generationGate.reserve()
        val oldUploader = FakeUploader()
        val replacementUploader = FakeUploader()
        val oldCoordinator = CoveragePointUploadCoordinator(2, oldUploader)
        val replacementCoordinator = CoveragePointUploadCoordinator(2, replacementUploader)
        val retained = snapshot(7, 7f, withEmptyUpdate = true)

        // The current binding attaches first. A delayed outgoing Compose
        // effect must still be unable to replace it afterwards.
        assertTrue(generationGate.attachIfCurrent(replacementGeneration) {
            replacementCoordinator.submitForResourceGeneration(retained)
        })
        assertFalse(generationGate.attachIfCurrent(oldGeneration) {
            oldCoordinator.submitForResourceGeneration(retained)
        })
        oldCoordinator.onRendererFrame()
        replacementCoordinator.onRendererFrame()
        oldUploader.completeAll()
        replacementUploader.completeAll()

        assertTrue(oldUploader.positionSubmissions.isEmpty())
        assertEquals(1, replacementUploader.positionSubmissions.size)
        assertEquals(1, replacementUploader.colorSubmissions.size)
    }

    @Test
    fun `busy slot preserves A bytes and coalesces pending updates`() {
        val uploader = FakeUploader()
        val coordinator = CoveragePointUploadCoordinator(4, uploader)
        val a = snapshot(1, 1f)
        val b = snapshot(2, 2f)
        val c = snapshot(3, 3f)
        val d = snapshot(4, 4f)

        coordinator.submit(a)
        coordinator.onRendererFrame()
        val positionsAtA = uploader.positionSubmissions.single().copyOf()
        coordinator.submit(b)
        coordinator.submit(c)
        coordinator.submit(d)
        assertArrayEquals(positionsAtA, uploader.positionSubmissions.single(), 0f)

        uploader.completeNext()
        assertEquals(1, uploader.positionSubmissions.size)
        uploader.completeNext()
        assertEquals(1, uploader.positionSubmissions.size)
        coordinator.onRendererFrame()
        assertEquals(2, uploader.positionSubmissions.size)
        assertArrayEquals(floatArrayOf(4f, 4f, 4f), uploader.positionSubmissions[1], 0f)
    }

    @Test
    fun `stale duplicate callbacks and destroy cannot submit more work`() {
        val uploader = FakeUploader()
        val coordinator = CoveragePointUploadCoordinator(2, uploader)
        coordinator.submit(snapshot(1, 1f))
        coordinator.onRendererFrame()
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
    fun `completed upload reports one bounded hand-off duration and ignores late callbacks`() {
        val uploader = FakeUploader()
        var now = 100L
        val completions = mutableListOf<Long>()
        val coordinator = CoveragePointUploadCoordinator(
            capacity = 2,
            uploader = uploader,
            onUploadCompleted = completions::add,
            clockNanos = { now },
        )

        coordinator.submit(snapshot(1, 1f))
        coordinator.onRendererFrame()
        now = 175L
        uploader.completeAll()
        uploader.completeAll()

        assertEquals(listOf(75L), completions)
    }

    @Test
    fun `first snapshot after a mesh replacement uploads even without dirty spans`() {
        val uploader = FakeUploader()
        val coordinator = CoveragePointUploadCoordinator(2, uploader)
        coordinator.submit(snapshot(1, 1f))
        coordinator.onRendererFrame()
        uploader.completeAll()

        coordinator.destroy()
        val replacement = CoveragePointUploadCoordinator(2, uploader)
        replacement.submit(snapshot(2, 2f, withEmptyUpdate = true))
        replacement.onRendererFrame()

        assertEquals(2, uploader.positionSubmissions.size)
    }

    @Test
    fun `same retained cut rehydrates a replacement resource and fences old callbacks`() {
        val uploader = FakeUploader()
        val retained = snapshot(1, 1f, withEmptyUpdate = true)
        val oldCompletions = mutableListOf<Long>()
        val old = CoveragePointUploadCoordinator(
            capacity = 2,
            uploader = uploader,
            onUploadCompleted = oldCompletions::add,
        )
        old.submit(retained)
        old.onRendererFrame()
        old.destroy()

        val newCompletions = mutableListOf<Long>()
        val replacement = CoveragePointUploadCoordinator(
            capacity = 2,
            uploader = uploader,
            onUploadCompleted = newCompletions::add,
        )
        replacement.submitForResourceGeneration(retained)
        replacement.onRendererFrame()
        uploader.completeAll()

        assertEquals(2, uploader.positionSubmissions.size)
        assertEquals(emptyList<Long>(), oldCompletions)
        assertEquals(1, newCompletions.size)
    }

    @Test
    fun `replacing a pending partial update uploads the latest complete state`() {
        val uploader = FakeUploader()
        val coordinator = CoveragePointUploadCoordinator(2, uploader)
        coordinator.submit(snapshot(1, 1f))
        coordinator.onRendererFrame()
        uploader.completeAll()

        coordinator.submit(partialSnapshot(2, floatArrayOf(2f, 2f, 2f, 20f, 20f, 20f), 0))
        coordinator.submit(partialSnapshot(3, floatArrayOf(3f, 3f, 3f, 30f, 30f, 30f), 1))
        coordinator.onRendererFrame()
        uploader.completeAll()

        assertArrayEquals(
            floatArrayOf(3f, 3f, 3f, 30f, 30f, 30f),
            uploader.positionSubmissions.last(),
            0f,
        )
    }

    @Test
    fun `a reset larger than one ordinary frame is paged at sixty four KiB`() {
        val uploader = FakeUploader()
        val submittedBytes = mutableListOf<Int>()
        val coordinator = CoveragePointUploadCoordinator(
            capacity = 5_000,
            uploader = uploader,
            onUploadSubmitted = submittedBytes::add,
        )
        val count = 5_000
        coordinator.submit(
            CoveragePointRenderSnapshot(
                revision = 1,
                enabled = true,
                capacity = count,
                count = count,
                keys = LongArray(count) { it.toLong() },
                positions = FloatArray(count * 3) { it.toFloat() },
                colors = IntArray(count) { 0xFF000000.toInt() },
            ),
        )
        coordinator.onRendererFrame()
        uploader.completeAll()
        assertEquals(1, uploader.positionSubmissions.size)
        coordinator.onRendererFrame()
        uploader.completeAll()

        assertEquals(listOf(64 * 1024, (count - 4_096) * 16), submittedBytes)
        assertTrue(submittedBytes.all { it <= 64 * 1024 })
        assertEquals(2, uploader.positionSubmissions.size)
        assertEquals(4_096 * 3, uploader.positionSubmissions.first().size)
        assertEquals((count - 4_096) * 3, uploader.positionSubmissions.last().size)
        assertEquals(listOf(0, 4_096 * 3 * Float.SIZE_BYTES), uploader.positionOffsets)
        assertEquals(listOf(0, 4_096 * 4), uploader.colorOffsets)
    }

    @Test
    fun `destroy after a paged callback prevents the next frame from resuming uploads`() {
        val uploader = FakeUploader()
        val coordinator = CoveragePointUploadCoordinator(5_000, uploader)
        coordinator.submit(
            CoveragePointRenderSnapshot(
                revision = 1,
                enabled = true,
                capacity = 5_000,
                count = 5_000,
                keys = LongArray(5_000) { it.toLong() },
                positions = FloatArray(5_000 * 3),
                colors = IntArray(5_000),
            ),
        )
        coordinator.onRendererFrame()

        uploader.completeAll()
        coordinator.destroy()
        coordinator.onRendererFrame()

        assertEquals(1, uploader.positionSubmissions.size)
    }
}

private class FakeUploader : CoveragePointVertexUploader {
    val positionSubmissions = mutableListOf<FloatArray>()
    val colorSubmissions = mutableListOf<ByteArray>()
    val pendingCallbacks = mutableListOf<() -> Unit>()
    val positionOffsets = mutableListOf<Int>()
    val colorOffsets = mutableListOf<Int>()

    override fun uploadPositions(
        buffer: FloatBuffer,
        destOffsetBytes: Int,
        elementCount: Int,
        onConsumed: () -> Unit,
    ) {
        positionOffsets += destOffsetBytes
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
        colorOffsets += destOffsetBytes
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

private fun partialSnapshot(
    revision: Long,
    positions: FloatArray,
    dirtyRow: Int,
): CoveragePointRenderSnapshot =
    CoveragePointRenderSnapshot(
        revision = revision,
        enabled = true,
        capacity = 2,
        count = 2,
        keys = longArrayOf(1, 2),
        positions = positions,
        colors = intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt()),
        update =
            com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate(
                geometryRevision = revision,
                visibilityRevision = revision,
                enabled = true,
                count = 2,
                spans =
                    listOf(
                        com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan(
                            startSlot = dirtyRow,
                            positions =
                                positions.copyOfRange(dirtyRow * 3, dirtyRow * 3 + 3),
                            colors = intArrayOf(0xFF000000.toInt()),
                        ),
                    ),
                reset = false,
            ),
    )
