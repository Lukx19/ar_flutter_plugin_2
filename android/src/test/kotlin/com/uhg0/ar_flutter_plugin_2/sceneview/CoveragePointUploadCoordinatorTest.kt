package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.uploadQualifier
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoveragePointUploadCoordinatorTest {
    @Test
    fun `palette revision participates in the retained mesh upload qualifier`() {
        val geometry = snapshot(1, 1f)
        val recolored = geometry.copy(paletteRevision = 1L)

        assertNotEquals(geometry.uploadQualifier(), recolored.uploadQualifier())
        assertEquals(
            geometry.uploadQualifier(),
            geometry.copy(colors = geometry.colors.copyOf()).uploadQualifier(),
        )
    }

    @Test
    fun `submission kick requests one later renderer frame`() {
        val uploader = FakeUploader()
        var releasedPages = 0
        val coordinator = CoveragePointUploadCoordinator(
            capacity = 2,
            uploader = uploader,
            onUploadPageReleased = { releasedPages++ },
        )

        coordinator.submit(snapshot(1, 1f))
        coordinator.onRendererFrame()
        uploader.completeAll()

        assertEquals(1, releasedPages)
    }

    @Test
    fun `each bounded page kicks submission after both point buffers are queued`() {
        val uploader = FakeUploader()
        val coordinator = CoveragePointUploadCoordinator(capacity = 2, uploader = uploader)

        coordinator.submit(snapshot(1, 1f))
        coordinator.onRendererFrame()

        // The coordinator kicks the concrete bounded submission before
        // waiting for Filament's independent ownership callbacks.
        assertEquals(1, uploader.submissionKickCount)
        uploader.completeAll()
        coordinator.onRendererFrame()
        assertEquals(1, uploader.submissionKickCount)
    }

    @Test
    fun `destroyed coordinator reports callbacks without completing its upload`() {
        val uploader = FakeUploader()
        var callbacksAfterDestroy = 0
        var completedUploads = 0
        val coordinator = CoveragePointUploadCoordinator(
            capacity = 2,
            uploader = uploader,
            onDestroyedUploadCallback = { callbacksAfterDestroy++ },
            onUploadCompleted = { completedUploads++ },
        )

        coordinator.submit(snapshot(1, 1f))
        coordinator.onRendererFrame()
        coordinator.destroy()
        uploader.completeAll()

        assertEquals("both Filament buffer callbacks arrive after destruction", 2, callbacksAfterDestroy)
        assertEquals("a late callback cannot complete the destroyed upload", 0, completedUploads)
    }

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
        val replacementCallbackOrigins = mutableListOf<RendererUploadPageOrigin>()
        val replacementCompletionOrigins = mutableListOf<RendererUploadPageOrigin>()
        val replacementCoordinator = CoveragePointUploadCoordinator(
            capacity = 2,
            uploader = replacementUploader,
            onUploadCallbackAttributed = replacementCallbackOrigins::add,
            onUploadCompletedAttributed = { _, origin -> replacementCompletionOrigins += origin },
        )
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
        // A recreated centroid resource owns one paired point page: position
        // and color each release once, then the page completes once. The
        // completion can be synchronous with a caller's start acknowledgement.
        assertEquals(
            listOf(
                RendererUploadPageOrigin.RESOURCE_GENERATION_RESET,
                RendererUploadPageOrigin.RESOURCE_GENERATION_RESET,
            ),
            replacementCallbackOrigins,
        )
        assertEquals(
            listOf(RendererUploadPageOrigin.RESOURCE_GENERATION_RESET),
            replacementCompletionOrigins,
        )
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

    @Test
    fun `descriptor supersession preserves active page and releases its opaque ticket`() {
        val uploader = FakeUploader()
        val releasedTickets = mutableListOf<CoverageDescriptorPageTicket>()
        val coordinator = CoveragePointUploadCoordinator(
            capacity = 1,
            uploader = uploader,
            onUploadPageReleasedWithTicket = releasedTickets::add,
        )
        val firstTicket = CoverageDescriptorPageTicket(1)
        val secondTicket = CoverageDescriptorPageTicket(2)

        coordinator.submitPage(
            page = page(value = 1f),
            origin = RendererUploadPageOrigin.RESOURCE_GENERATION_RESET,
            ticket = firstTicket,
        )
        coordinator.onRendererFrame()
        val firstUpload = uploader.positionSubmissions.single().copyOf()

        coordinator.submitPage(
            page = page(value = 2f),
            origin = RendererUploadPageOrigin.ORDINARY,
            ticket = secondTicket,
        )
        uploader.completeAll()

        assertEquals(listOf(firstTicket), releasedTickets)
        assertArrayEquals(firstUpload, floatArrayOf(1f, 1f, 1f), 0f)

        coordinator.onRendererFrame()
        assertEquals(2, uploader.positionSubmissions.size)
        assertArrayEquals(floatArrayOf(2f, 2f, 2f), uploader.positionSubmissions.last(), 0f)
        uploader.completeAll()
        assertEquals(listOf(firstTicket, secondTicket), releasedTickets)
    }

    @Test
    fun `queued page tickets release once on replacement snapshot cancellation and destroy`() {
        val uploader = FakeUploader()
        val released = mutableListOf<CoverageDescriptorPageTicket>()
        val coordinator = CoveragePointUploadCoordinator(
            capacity = 2,
            uploader = uploader,
            onUploadPageReleasedWithTicket = released::add,
        )
        val tickets = List(4) { CoverageDescriptorPageTicket(it.toLong()) }
        coordinator.submitPage(page(1f), RendererUploadPageOrigin.ORDINARY, tickets[0])
        coordinator.submitPage(page(2f), RendererUploadPageOrigin.ORDINARY, tickets[1])
        assertEquals(listOf(tickets[0]), released)
        coordinator.submit(snapshot(1, 1f))
        assertEquals(tickets.take(2), released)
        coordinator.submitPage(page(3f), RendererUploadPageOrigin.ORDINARY, tickets[2])
        coordinator.destroy()
        coordinator.destroy()
        coordinator.submitPage(page(4f), RendererUploadPageOrigin.ORDINARY, tickets[3])
        coordinator.onRendererFrame()
        assertEquals(tickets, released)
        assertTrue(uploader.positionSubmissions.isEmpty())
        assertEquals(0, uploader.submissionKickCount)
    }

    @Test
    fun `destroy before or between consumption callbacks settles owners without completion`() {
        for (positionConsumedFirst in listOf(false, true)) {
            val uploader = FakeUploader()
            val released = mutableListOf<CoverageDescriptorPageTicket>()
            var completed = 0
            var releasedNotifications = 0
            val coordinator = CoveragePointUploadCoordinator(
                capacity = 2,
                uploader = uploader,
                onUploadPageReleasedWithTicket = released::add,
                onUploadCompleted = { completed++ },
                onUploadPageReleased = { releasedNotifications++ },
            )
            val active = CoverageDescriptorPageTicket(1)
            val queued = CoverageDescriptorPageTicket(2)
            coordinator.submitPage(page(1f), RendererUploadPageOrigin.ORDINARY, active)
            coordinator.onRendererFrame()
            val callbacks = uploader.callbackHistory.toList()
            val retained = uploader.positionBufferViews.single()
            val original = retained.get(retained.position())
            coordinator.submitPage(page(2f), RendererUploadPageOrigin.ORDINARY, queued)
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
        val uploader = FakeUploader()
        val released = mutableListOf<CoverageDescriptorPageTicket>()
        val reusablePage = page(1f)
        val coordinator = CoveragePointUploadCoordinator(
            capacity = 2,
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
                val uploader = FakeUploader().apply {
                    rejection = rejectedLane
                    consumePositionsSynchronously = synchronousPosition
                }
                val released = mutableListOf<CoverageDescriptorPageTicket>()
                var completed = 0
                val coordinator = CoveragePointUploadCoordinator(
                    capacity = 2,
                    uploader = uploader,
                    onUploadPageReleasedWithTicket = released::add,
                    onUploadCompleted = { completed++ },
                )
                val failed = CoverageDescriptorPageTicket(1)
                val successor = CoverageDescriptorPageTicket(2)
                coordinator.submitPage(page(1f), RendererUploadPageOrigin.ORDINARY, failed)
                assertThrows(IllegalStateException::class.java) { coordinator.onRendererFrame() }
                val retainedLane = rejectedLane == "colors" && !synchronousPosition
                assertEquals(if (retainedLane) emptyList() else listOf(failed), released)
                coordinator.submitPage(page(2f), RendererUploadPageOrigin.ORDINARY, successor)
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
            val uploader = FakeUploader().apply { rejection = "kick" }
            val released = mutableListOf<CoverageDescriptorPageTicket>()
            var completed = 0
            var notifications = 0
            val coordinator = CoveragePointUploadCoordinator(
                capacity = 2,
                uploader = uploader,
                onUploadPageReleasedWithTicket = released::add,
                onUploadCompleted = { completed++ },
                onUploadPageReleased = { notifications++ },
            )
            val ticket = CoverageDescriptorPageTicket(1)
            val successor = CoverageDescriptorPageTicket(2)
            coordinator.submitPage(page(1f), RendererUploadPageOrigin.ORDINARY, ticket)
            assertThrows(IllegalStateException::class.java) { coordinator.onRendererFrame() }
            assertTrue(released.isEmpty())
            val oldCallbacks = uploader.callbackHistory.toList()
            val retained = uploader.positionBufferViews.single()
            val original = retained.get(retained.position())
            if (destroyBeforeCallbacks) coordinator.destroy()
            else coordinator.submitPage(page(2f), RendererUploadPageOrigin.ORDINARY, successor)
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
            val uploader = FakeUploader().apply {
                consumePositionsSynchronously = synchronousLanes and 1 != 0
                consumeColorsSynchronously = synchronousLanes and 2 != 0
            }
            val released = mutableListOf<CoverageDescriptorPageTicket>()
            var completed = 0
            val coordinator = CoveragePointUploadCoordinator(
                capacity = 2,
                uploader = uploader,
                onUploadPageReleasedWithTicket = released::add,
                onUploadCompleted = { completed++ },
            )
            val first = CoverageDescriptorPageTicket(1)
            val successor = CoverageDescriptorPageTicket(2)
            uploader.onKick = {
                assertTrue(released.isEmpty())
                assertEquals(0, completed)
                coordinator.submitPage(page(2f), RendererUploadPageOrigin.ORDINARY, successor)
                coordinator.onRendererFrame()
                assertEquals(1, uploader.positionSubmissions.size)
            }
            coordinator.submitPage(page(1f), RendererUploadPageOrigin.ORDINARY, first)
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
        val uploader = FakeUploader().apply { consumePositionsSynchronously = true }
        val released = mutableListOf<CoverageDescriptorPageTicket>()
        var completed = 0
        var notifications = 0
        lateinit var destroy: () -> Unit
        val coordinator = CoveragePointUploadCoordinator(
            capacity = 2,
            uploader = uploader,
            onUploadPageReleasedWithTicket = released::add,
            onUploadCallback = { destroy() },
            onUploadCompleted = { completed++ },
            onUploadPageReleased = { notifications++ },
        )
        destroy = coordinator::destroy
        val ticket = CoverageDescriptorPageTicket(1)
        coordinator.submitPage(page(1f), RendererUploadPageOrigin.ORDINARY, ticket)
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
            val uploader = FakeUploader()
            val released = mutableListOf<CoverageDescriptorPageTicket>()
            val first = CoverageDescriptorPageTicket(1)
            val replaced = CoverageDescriptorPageTicket(2)
            val successor = CoverageDescriptorPageTicket(3)
            lateinit var enqueueSuccessor: () -> Unit
            val coordinator = CoveragePointUploadCoordinator(
                capacity = 2,
                uploader = uploader,
                onUploadPageReleasedWithTicket = {
                    released += it
                    if (it === first) enqueueSuccessor()
                },
            )
            enqueueSuccessor = {
                coordinator.submitPage(page(3f), RendererUploadPageOrigin.ORDINARY, successor)
            }
            coordinator.submitPage(page(1f), RendererUploadPageOrigin.ORDINARY, first)
            if (replaceWithSnapshot) {
                coordinator.submit(snapshot(1, 1f))
            } else {
                coordinator.submitPage(page(2f), RendererUploadPageOrigin.ORDINARY, replaced)
            }
            val cancelled = if (replaceWithSnapshot) listOf(first) else listOf(first, replaced)
            assertEquals(cancelled, released)
            coordinator.onRendererFrame()
            assertEquals(1, uploader.positionSubmissions.size)
            assertEquals(3f, uploader.positionSubmissions.single()[0], 0f)
            uploader.completeAll()
            assertEquals(cancelled + successor, released)
        }
    }
}

private class FakeUploader : CoveragePointVertexUploader {
    val positionSubmissions = mutableListOf<FloatArray>()
    val colorSubmissions = mutableListOf<ByteArray>()
    val pendingCallbacks = mutableListOf<() -> Unit>()
    val positionOffsets = mutableListOf<Int>()
    val colorOffsets = mutableListOf<Int>()
    var submissionKickCount = 0
    var rejection: String? = null
    var consumePositionsSynchronously = false
    var consumeColorsSynchronously = false
    var onKick: () -> Unit = {}
    val callbackHistory = mutableListOf<() -> Unit>()
    val positionBufferViews = mutableListOf<FloatBuffer>()

    override fun uploadPositions(
        buffer: FloatBuffer,
        destOffsetBytes: Int,
        elementCount: Int,
        onConsumed: () -> Unit,
    ) {
        check(rejection != "positions") { "position submission rejected" }
        positionBufferViews += buffer.duplicate()
        positionOffsets += destOffsetBytes
        val copy = FloatArray(buffer.remaining())
        buffer.duplicate().get(copy)
        positionSubmissions += copy
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
        val copy = ByteArray(buffer.remaining())
        buffer.duplicate().get(copy)
        colorSubmissions += copy
        callbackHistory += onConsumed
        if (consumeColorsSynchronously) onConsumed() else pendingCallbacks += onConsumed
    }

    override fun kickSubmission() {
        submissionKickCount++
        onKick()
        check(rejection != "kick") { "submission kick failed" }
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

private fun page(value: Float): CoveragePresentationPage = CoveragePresentationPage(
    startSlot = 0,
    totalCount = 1,
    surfaceIds = longArrayOf(1),
    positions = floatArrayOf(value, value, value),
    colors = intArrayOf(0xFF000000.toInt()),
    styleRows = ByteArray(COVERAGE_RENDERER_STYLE_ROW_BYTES),
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
