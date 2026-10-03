package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageCubeUploadCoordinatorTest {
    @Test
    fun `cube submission kick requests one later renderer frame`() {
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
    fun `each bounded page kicks submission after both cube buffers are queued`() {
        val uploader = FakeCubeUploader()
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 1,
            halfSize = 0.5f,
            uploader = uploader,
        )

        coordinator.submit(cubeSnapshot(1, floatArrayOf(1f, 1f, 1f)))
        coordinator.onRendererFrame()

        assertEquals(1, uploader.submissionKickCount)
        uploader.completeAll()
        coordinator.onRendererFrame()
        assertEquals(1, uploader.submissionKickCount)
    }

    @Test
    fun `destroyed cube coordinator reports callbacks without completing its upload`() {
        val uploader = FakeCubeUploader()
        var callbacksAfterDestroy = 0
        var completedUploads = 0
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 1,
            halfSize = 0.5f,
            uploader = uploader,
            onDestroyedUploadCallback = { callbacksAfterDestroy++ },
            onUploadCompleted = { completedUploads++ },
        )

        coordinator.submit(cubeSnapshot(1, floatArrayOf(1f, 1f, 1f)))
        coordinator.onRendererFrame()
        coordinator.destroy()
        uploader.completeAll()

        assertEquals("both Filament buffer callbacks arrive after destruction", 2, callbacksAfterDestroy)
        assertEquals("a late callback cannot complete the destroyed upload", 0, completedUploads)
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

    @Test
    fun `descriptor supersession preserves active cube page and releases its ticket`() {
        val uploader = FakeCubeUploader()
        val releasedTickets = mutableListOf<CoverageDescriptorPageTicket>()
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 1,
            halfSize = 0.5f,
            uploader = uploader,
            onUploadPageReleasedWithTicket = releasedTickets::add,
        )
        val firstTicket = CoverageDescriptorPageTicket(1)
        val secondTicket = CoverageDescriptorPageTicket(2)

        coordinator.submitPage(cubePage(1f), RendererUploadPageOrigin.RESOURCE_GENERATION_RESET, firstTicket)
        coordinator.onRendererFrame()
        coordinator.submitPage(cubePage(2f), RendererUploadPageOrigin.ORDINARY, secondTicket)
        uploader.completeAll()

        assertEquals(listOf(firstTicket), releasedTickets)
        coordinator.onRendererFrame()
        assertEquals(2, uploader.positionSubmissions.size)
        assertEquals(1.5f, uploader.positionSubmissions.last()[0], 0f)
        uploader.completeAll()
        assertEquals(listOf(firstTicket, secondTicket), releasedTickets)
    }

    @Test
    fun `queued page tickets release once on replacement snapshot cancellation and destroy`() {
        val uploader = FakeCubeUploader()
        val released = mutableListOf<CoverageDescriptorPageTicket>()
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 2,
            halfSize = 0.5f,
            uploader = uploader,
            onUploadPageReleasedWithTicket = released::add,
        )
        val tickets = List(4) { CoverageDescriptorPageTicket(it.toLong()) }
        coordinator.submitPage(cubePage(1f), RendererUploadPageOrigin.ORDINARY, tickets[0])
        coordinator.submitPage(cubePage(2f), RendererUploadPageOrigin.ORDINARY, tickets[1])
        assertEquals(listOf(tickets[0]), released)
        coordinator.submit(cubeSnapshot(1, floatArrayOf(1f, 1f, 1f)))
        assertEquals(tickets.take(2), released)
        coordinator.submitPage(cubePage(3f), RendererUploadPageOrigin.ORDINARY, tickets[2])
        coordinator.destroy()
        coordinator.destroy()
        coordinator.submitPage(cubePage(4f), RendererUploadPageOrigin.ORDINARY, tickets[3])
        coordinator.onRendererFrame()
        assertEquals(tickets, released)
        assertTrue(uploader.positionSubmissions.isEmpty())
        assertEquals(0, uploader.submissionKickCount)
    }

    @Test
    fun `destroy before or between consumption callbacks settles owners without completion`() {
        for (positionConsumedFirst in listOf(false, true)) {
            val uploader = FakeCubeUploader()
            val released = mutableListOf<CoverageDescriptorPageTicket>()
            var completed = 0
            var releasedNotifications = 0
            val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
                capacity = 2,
                halfSize = 0.5f,
                uploader = uploader,
                onUploadPageReleasedWithTicket = released::add,
                onUploadCompleted = { completed++ },
                onUploadPageReleased = { releasedNotifications++ },
            )
            val active = CoverageDescriptorPageTicket(1)
            val queued = CoverageDescriptorPageTicket(2)
            coordinator.submitPage(cubePage(1f), RendererUploadPageOrigin.ORDINARY, active)
            coordinator.onRendererFrame()
            val callbacks = uploader.callbackHistory.toList()
            val retained = uploader.positionBufferViews.single()
            val original = retained.get(retained.position())
            coordinator.submitPage(cubePage(2f), RendererUploadPageOrigin.ORDINARY, queued)
            if (positionConsumedFirst) callbacks[0]()
            coordinator.destroy()
            coordinator.destroy()
            assertEquals(listOf(queued), released)
            callbacks[0]()
            callbacks[0]()
            coordinator.onRendererFrame()
            assertEquals(listOf(queued), released)
            assertEquals(original, retained.get(retained.position()), 0f)
            callbacks[1]()
            callbacks[1]()
            coordinator.destroy()
            assertEquals(listOf(queued, active), released)
            assertEquals(0, completed)
            assertEquals(0, releasedNotifications)
            assertEquals(1, uploader.positionSubmissions.size)
        }
    }

    @Test
    fun `duplicate and stale callbacks cannot release or mutate a successor page`() {
        val uploader = FakeCubeUploader()
        val released = mutableListOf<CoverageDescriptorPageTicket>()
        val reusablePage = cubePage(1f)
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 2,
            halfSize = 0.5f,
            uploader = uploader,
            onUploadPageReleasedWithTicket = {
                released += it
                reusablePage.positions[0] += 10f
            },
        )
        val first = CoverageDescriptorPageTicket(1)
        val second = CoverageDescriptorPageTicket(2)
        coordinator.submitPage(reusablePage, RendererUploadPageOrigin.ORDINARY, first)
        coordinator.onRendererFrame()
        val firstCallbacks = uploader.callbackHistory.toList()
        firstCallbacks[0]()
        firstCallbacks[0]()
        assertTrue(released.isEmpty())
        assertEquals(1f, reusablePage.positions[0], 0f)
        firstCallbacks[1]()
        assertEquals(11f, reusablePage.positions[0], 0f)
        coordinator.submitPage(reusablePage, RendererUploadPageOrigin.ORDINARY, second)
        coordinator.onRendererFrame()
        val retained = uploader.positionBufferViews.last()
        val original = retained.get(retained.position())
        firstCallbacks.forEach { it() }
        assertEquals(listOf(first), released)
        assertEquals(11f, reusablePage.positions[0], 0f)
        assertEquals(original, retained.get(retained.position()), 0f)
        uploader.callbackHistory[2]()
        firstCallbacks[1]()
        assertEquals(listOf(first), released)
        uploader.callbackHistory[3]()
        uploader.callbackHistory[3]()
        assertEquals(listOf(first, second), released)
        assertEquals(21f, reusablePage.positions[0], 0f)
    }

    @Test
    fun `submission rejection retains only previously accepted lane until consumption`() {
        for (rejectedLane in listOf("positions", "colors")) {
            for (synchronousPosition in listOf(false, true)) {
                val uploader = FakeCubeUploader().apply {
                    rejection = rejectedLane
                    consumePositionsSynchronously = synchronousPosition
                }
                val released = mutableListOf<CoverageDescriptorPageTicket>()
                var completed = 0
                val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
                    capacity = 2,
                    halfSize = 0.5f,
                    uploader = uploader,
                    onUploadPageReleasedWithTicket = released::add,
                    onUploadCompleted = { completed++ },
                )
                val failed = CoverageDescriptorPageTicket(1)
                val successor = CoverageDescriptorPageTicket(2)
                coordinator.submitPage(cubePage(1f), RendererUploadPageOrigin.ORDINARY, failed)
                assertThrows(IllegalStateException::class.java) { coordinator.onRendererFrame() }
                val retainedLane = rejectedLane == "colors" && !synchronousPosition
                assertEquals(if (retainedLane) emptyList() else listOf(failed), released)
                coordinator.submitPage(cubePage(2f), RendererUploadPageOrigin.ORDINARY, successor)
                uploader.rejection = null
                if (retainedLane) {
                    val retained = uploader.positionBufferViews.single()
                    val original = retained.get(retained.position())
                    coordinator.onRendererFrame()
                    assertEquals(1, uploader.positionSubmissions.size)
                    assertEquals(original, retained.get(retained.position()), 0f)
                    uploader.completeAll()
                }
                assertEquals(listOf(failed), released)
                assertEquals(0, completed)
                coordinator.onRendererFrame()
                uploader.completeAll()
                assertEquals(listOf(failed, successor), released)
                assertEquals(1, completed)
            }
        }
    }

    @Test
    fun `submission kick failure retains both accepted lanes until consumption or teardown`() {
        for (destroyBeforeCallbacks in listOf(false, true)) {
            val uploader = FakeCubeUploader().apply { rejection = "kick" }
            val released = mutableListOf<CoverageDescriptorPageTicket>()
            var completed = 0
            var notifications = 0
            val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
                capacity = 2,
                halfSize = 0.5f,
                uploader = uploader,
                onUploadPageReleasedWithTicket = released::add,
                onUploadCompleted = { completed++ },
                onUploadPageReleased = { notifications++ },
            )
            val ticket = CoverageDescriptorPageTicket(1)
            val successor = CoverageDescriptorPageTicket(2)
            coordinator.submitPage(cubePage(1f), RendererUploadPageOrigin.ORDINARY, ticket)
            assertThrows(IllegalStateException::class.java) { coordinator.onRendererFrame() }
            assertTrue(released.isEmpty())
            val oldCallbacks = uploader.callbackHistory.toList()
            val retained = uploader.positionBufferViews.single()
            val original = retained.get(retained.position())
            if (destroyBeforeCallbacks) coordinator.destroy()
            else coordinator.submitPage(cubePage(2f), RendererUploadPageOrigin.ORDINARY, successor)
            oldCallbacks[1]()
            oldCallbacks[1]()
            coordinator.onRendererFrame()
            assertTrue(released.isEmpty())
            assertEquals(1, uploader.positionSubmissions.size)
            assertEquals(original, retained.get(retained.position()), 0f)
            oldCallbacks[0]()
            oldCallbacks[0]()
            assertEquals(listOf(ticket), released)
            assertEquals(0, completed)
            assertEquals(if (destroyBeforeCallbacks) 0 else 1, notifications)
            if (!destroyBeforeCallbacks) {
                uploader.rejection = null
                coordinator.onRendererFrame()
                oldCallbacks.forEach { it() }
                assertEquals(listOf(ticket), released)
                uploader.completeAll()
                assertEquals(listOf(ticket, successor), released)
                assertEquals(1, completed)
            }
        }
    }

    @Test
    fun `synchronous callbacks cannot release a page or admit its successor before kick exits`() {
        for (synchronousLanes in listOf(1, 2, 3)) {
            val uploader = FakeCubeUploader().apply {
                consumePositionsSynchronously = synchronousLanes and 1 != 0
                consumeColorsSynchronously = synchronousLanes and 2 != 0
            }
            val released = mutableListOf<CoverageDescriptorPageTicket>()
            var completed = 0
            val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
                capacity = 2,
                halfSize = 0.5f,
                uploader = uploader,
                onUploadPageReleasedWithTicket = released::add,
                onUploadCompleted = { completed++ },
            )
            val first = CoverageDescriptorPageTicket(1)
            val successor = CoverageDescriptorPageTicket(2)
            uploader.onKick = {
                assertTrue(released.isEmpty())
                assertEquals(0, completed)
                coordinator.submitPage(cubePage(2f), RendererUploadPageOrigin.ORDINARY, successor)
                coordinator.onRendererFrame()
                assertEquals(1, uploader.positionSubmissions.size)
            }
            coordinator.submitPage(cubePage(1f), RendererUploadPageOrigin.ORDINARY, first)
            coordinator.onRendererFrame()
            uploader.completeAll()
            assertEquals(listOf(first), released)
            assertEquals(1, completed)
            uploader.onKick = {}
            coordinator.onRendererFrame()
            uploader.completeAll()
            assertEquals(listOf(first, successor), released)
            assertEquals(2, completed)
        }
    }

    @Test
    fun `destruction inside synchronous consumption cancels the unsubmitted second lane`() {
        val uploader = FakeCubeUploader().apply { consumePositionsSynchronously = true }
        val released = mutableListOf<CoverageDescriptorPageTicket>()
        var completed = 0
        var notifications = 0
        lateinit var destroy: () -> Unit
        val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 2,
            halfSize = 0.5f,
            uploader = uploader,
            onUploadPageReleasedWithTicket = released::add,
            onUploadCallback = { destroy() },
            onUploadCompleted = { completed++ },
            onUploadPageReleased = { notifications++ },
        )
        destroy = coordinator::destroy
        val ticket = CoverageDescriptorPageTicket(1)
        coordinator.submitPage(cubePage(1f), RendererUploadPageOrigin.ORDINARY, ticket)
        coordinator.onRendererFrame()
        assertEquals(listOf(ticket), released)
        assertTrue(uploader.colorSubmissions.isEmpty())
        assertEquals(0, uploader.submissionKickCount)
        uploader.callbackHistory.single()()
        coordinator.destroy()
        assertEquals(listOf(ticket), released)
        assertEquals(0, completed)
        assertEquals(0, notifications)
    }

    @Test
    fun `queued cancellation installs state before a release callback enqueues its successor`() {
        for (replaceWithSnapshot in listOf(false, true)) {
            val uploader = FakeCubeUploader()
            val released = mutableListOf<CoverageDescriptorPageTicket>()
            val first = CoverageDescriptorPageTicket(1)
            val replaced = CoverageDescriptorPageTicket(2)
            val successor = CoverageDescriptorPageTicket(3)
            lateinit var enqueueSuccessor: () -> Unit
            val coordinator = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
                capacity = 2,
                halfSize = 0.5f,
                uploader = uploader,
                onUploadPageReleasedWithTicket = {
                    released += it
                    if (it === first) enqueueSuccessor()
                },
            )
            enqueueSuccessor = {
                coordinator.submitPage(cubePage(3f), RendererUploadPageOrigin.ORDINARY, successor)
            }
            coordinator.submitPage(cubePage(1f), RendererUploadPageOrigin.ORDINARY, first)
            if (replaceWithSnapshot) {
                coordinator.submit(cubeSnapshot(1, floatArrayOf(1f, 1f, 1f)))
            } else {
                coordinator.submitPage(cubePage(2f), RendererUploadPageOrigin.ORDINARY, replaced)
            }
            val cancelled = if (replaceWithSnapshot) listOf(first) else listOf(first, replaced)
            assertEquals(cancelled, released)
            coordinator.onRendererFrame()
            assertEquals(1, uploader.positionSubmissions.size)
            assertEquals(2.5f, uploader.positionSubmissions.single()[0], 0f)
            uploader.completeAll()
            assertEquals(cancelled + successor, released)
        }
    }
}

private class FakeCubeUploader : CoverageCubeMeshResources.CoverageCubeVertexUploader {
    val positionSubmissions = mutableListOf<FloatArray>()
    val colorSubmissions = mutableListOf<ByteArray>()
    val positionElementCounts = mutableListOf<Int>()
    val colorByteCounts = mutableListOf<Int>()
    var submissionKickCount = 0
    var rejection: String? = null
    var consumePositionsSynchronously = false
    var consumeColorsSynchronously = false
    var onKick: () -> Unit = {}
    val callbackHistory = mutableListOf<() -> Unit>()
    val positionBufferViews = mutableListOf<FloatBuffer>()
    val positionOffsets = mutableListOf<Int>()
    val colorOffsets = mutableListOf<Int>()
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    override fun uploadPositions(
        buffer: FloatBuffer,
        destOffsetBytes: Int,
        elementCount: Int,
        onConsumed: () -> Unit,
    ) {
        check(rejection != "positions") { "position submission rejected" }
        positionBufferViews += buffer.duplicate()
        positionOffsets += destOffsetBytes
        positionSubmissions += FloatArray(buffer.remaining()).also { copy ->
            buffer.duplicate().get(copy)
        }
        positionElementCounts += elementCount
        callbackHistory += onConsumed
        if (consumePositionsSynchronously) onConsumed() else pendingCallbacks += onConsumed
    }

    override fun uploadColors(
        buffer: ByteBuffer,
        destOffsetBytes: Int,
        byteCount: Int,
        onConsumed: () -> Unit,
    ) {
        check(rejection != "colors") { "color submission rejected" }
        colorOffsets += destOffsetBytes
        colorSubmissions += ByteArray(buffer.remaining()).also { copy ->
            buffer.duplicate().get(copy)
        }
        colorByteCounts += byteCount
        callbackHistory += onConsumed
        if (consumeColorsSynchronously) onConsumed() else pendingCallbacks += onConsumed
    }

    override fun kickSubmission() {
        submissionKickCount++
        onKick()
        check(rejection != "kick") { "submission kick failed" }
    }

    fun completeAll() {
        while (pendingCallbacks.isNotEmpty()) {
            pendingCallbacks.removeAt(0).invoke()
        }
    }
}

private fun cubePage(value: Float): CoveragePresentationPage = CoveragePresentationPage(
    startSlot = 0,
    totalCount = 1,
    surfaceIds = longArrayOf(1),
    positions = floatArrayOf(value, value, value),
    colors = intArrayOf(0xFF000000.toInt()),
    styleRows = ByteArray(COVERAGE_RENDERER_STYLE_ROW_BYTES),
)

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
