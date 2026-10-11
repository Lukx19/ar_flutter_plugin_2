package com.uhg0.ar_flutter_plugin_2.posetracking

import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestTrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anchor table, creation, update thresholds, limit fallback and activation of the pose tracker. */
class AnchorPoseTrackerTest {
    private val clock = FakePoseClock(1_000_000_000L)
    private val backend = FakeAnchorBackend()
    private val tracker = AnchorPoseTracker(backend, clock, initialCapacity = 64)
    private val frame = FakeAnchorFrame()
    private val out = replyBuffer()
    private var sequence = 0

    private fun query(
        flags: Int = PoseQueryLayout.FLAG_TRACKING,
        limit: Int = 0,
        detachAfter: Int = 0,
        requests: List<KeyframeRequest> = emptyList(),
    ): DecodedReply {
        val length = tracker.handleQuery(poseQuery(flags, 7, limit, detachAfter, requests), out, sequence++)
        assertTrue("reply written", length > 0)
        return DecodedReply(out, length)
    }

    private fun step(advanceNs: Long = 33_000_000L, updated: List<FakeAnchor> = emptyList()) {
        frame.timestampNs += advanceNs
        frame.setUpdated(updated)
        tracker.onFrame(SESSION, frame)
    }

    private fun createKeyframes(vararg ids: Int): List<FakeAnchor> {
        query(requests = ids.map { KeyframeRequest(it, subject = it.toLong() * 10, captureNs = 5L) })
        val before = backend.created.size
        step()
        return backend.created.subList(before, backend.created.size).toList()
    }

    @Test
    fun `keyframe requests yield one creation record each and an initial update`() {
        val anchors = createKeyframes(FIRST, FIRST + 1)
        assertEquals(2, anchors.size)
        val reply = query()
        assertEquals(2, reply.creationCount)
        assertEquals(listOf(FIRST, FIRST + 1), reply.creations.map { it.anchorId })
        reply.creations.forEach {
            assertEquals(AnchorKind.KEYFRAME, it.kind)
            assertEquals(AnchorCreation.CREATED, it.status)
            assertEquals(5L, it.captureNs)
            assertEquals(frame.timestampNs, it.frameNs)
        }
        assertEquals((FIRST + 1) * 10L, reply.creations[1].subject)
        assertEquals(2, reply.updateCount)
        assertEquals(AnchorTracking.TRACKING, reply.updates[0].tracking)
        assertEquals(0, query().creationCount)
        assertEquals(0, query().updateCount)
        assertEquals(2, tracker.trackedAnchors)
    }

    @Test
    fun `updates are emitted only above one millimetre or 0_05 degrees`() {
        val a = createKeyframes(FIRST).single()
        query()
        a.pose[0] = 0.0009f
        step(updated = listOf(a))
        assertEquals("0.9 mm stays quiet", 0, query().updateCount)
        a.pose[0] = 0.0011f
        step(updated = listOf(a))
        val moved = query()
        assertEquals(1, moved.updateCount)
        assertEquals(0.0011f, moved.updates[0].rows[3], 1e-7f)

        yawPose(0.04, 0.0011f).copyInto(a.pose)
        step(updated = listOf(a))
        assertEquals("0.04 degrees stays quiet", 0, query().updateCount)
        yawPose(0.06, 0.0011f).copyInto(a.pose)
        step(updated = listOf(a))
        assertEquals(1, query().updateCount)

        a.pose[1] = 5f
        step(updated = emptyList())
        assertEquals("anchors not updated by the frame are not read", 0, query().updateCount)
    }

    @Test
    fun `a tracking change emits an update`() {
        val a = createKeyframes(FIRST).single()
        query()
        a.tracking = AnchorTracking.PAUSED
        step(updated = listOf(a))
        val paused = query()
        assertEquals(1, paused.updateCount)
        assertEquals(AnchorTracking.PAUSED, paused.updates[0].tracking)
        step(updated = listOf(a))
        assertEquals(0, query().updateCount)
        a.tracking = AnchorTracking.TRACKING
        step(updated = listOf(a))
        assertEquals(AnchorTracking.TRACKING, query().updates.single().tracking)
    }

    @Test
    fun `photos get ids from one and one creation record each`() {
        query()
        assertEquals(1, tracker.enqueuePhoto(11L, 21L, identityPose(1f), true))
        assertEquals(2, tracker.enqueuePhoto(12L, 22L, identityPose(2f), true))
        assertEquals(3, tracker.enqueuePhoto(13L, 23L, identityPose(3f), false))
        step()
        val reply = query()
        assertEquals(listOf(1, 2, 3), reply.creations.map { it.anchorId })
        assertEquals(listOf(AnchorCreation.CREATED, AnchorCreation.CREATED, AnchorCreation.FAILED), reply.creations.map { it.status })
        assertEquals(listOf(11L, 12L, 13L), reply.creations.map { it.subject })
        assertTrue(reply.creations.all { it.kind == AnchorKind.PHOTO })
        assertEquals(2, reply.updateCount)
        assertEquals(2, backend.created.size)
    }

    @Test
    fun `photos are ignored while inactive`() {
        assertEquals(-1, tracker.enqueuePhoto(1L, 2L, identityPose(), true))
    }

    @Test
    fun `creation needs camera tracking and reports failures`() {
        frame.cameraTracking = IngestTrackingState.PAUSED
        createKeyframes(FIRST)
        assertEquals(AnchorCreation.NOT_TRACKING, query().creations.single().status)
        frame.cameraTracking = IngestTrackingState.TRACKING
        backend.failCreates = true
        createKeyframes(FIRST + 1)
        assertEquals(AnchorCreation.FAILED, query().creations.single().status)
        assertEquals(0, tracker.trackedAnchors)
    }

    @Test
    fun `an anchor moving more than 5 cm within one frame is reported once per frame as a jump`() {
        val jumps = ArrayList<Pair<Long, Int>>()
        tracker.jumpListener = AnchorJumpListener { frameNs, mm -> jumps += frameNs to mm }
        val (a, b) = createKeyframes(FIRST, FIRST + 1)
        query()
        a.pose[0] = 0.03f
        step(updated = listOf(a))
        assertTrue("3 cm is ordinary drift", jumps.isEmpty())
        a.pose[0] = 0.10f
        b.pose[1] = 0.25f
        step(updated = listOf(a, b))
        assertEquals(listOf(frame.timestampNs to 250), jumps)
        // A move while the anchor is not tracking, and its return to tracking, are no jumps.
        a.tracking = AnchorTracking.PAUSED
        a.pose[0] = 1f
        step(updated = listOf(a))
        a.tracking = AnchorTracking.TRACKING
        step(updated = listOf(a))
        assertEquals(1, jumps.size)
        assertEquals(1L, tracker.snapshot()["anchorJumps"])
    }

    @Test
    fun `submap requests create submap anchors whose pose changes are reported and the limit never detaches`() {
        query(requests = listOf(KeyframeRequest(FIRST, tq = identityPose(1f), flags = PoseQueryLayout.REQUEST_FLAG_SUBMAP)))
        step()
        val submap = backend.created.single()
        val created = query(limit = 1, detachAfter = 1)
        assertEquals(AnchorKind.SUBMAP, created.creations.single().kind)
        assertEquals(AnchorCreation.CREATED, created.creations.single().status)
        assertEquals(AnchorKind.SUBMAP, created.updates.single().kind)
        submap.pose[0] = 1.2f
        step(updated = listOf(submap))
        val moved = query(limit = 1, detachAfter = 1).updates.single()
        assertEquals(AnchorKind.SUBMAP, moved.kind)
        assertEquals(1.2f, moved.rows[3], 1e-6f)
        // Long stable and over the limit: a keyframe request is refused rather than detaching the submap anchor.
        step(advanceNs = 2_000_000_000L)
        query(limit = 1, detachAfter = 1, requests = listOf(KeyframeRequest(FIRST + 1)))
        step()
        assertFalse(submap.detached)
        assertEquals(AnchorCreation.OVER_LIMIT, query(limit = 1, detachAfter = 1).creations.single().status)
    }

    @Test
    fun `over the limit without stable anchors new creations are refused`() {
        query(limit = 1)
        createKeyframes(FIRST)
        query(limit = 1)
        query(limit = 1, requests = listOf(KeyframeRequest(FIRST + 1)))
        step()
        val reply = query(limit = 1)
        assertEquals(AnchorCreation.OVER_LIMIT, reply.creations.single().status)
        assertEquals(1, tracker.trackedAnchors)
        assertEquals(1L, tracker.snapshot()["overLimit"])
    }

    @Test
    fun `detach by limit picks long-stable anchors oldest first and sends a final update`() {
        val oldest = createKeyframes(FIRST).single()
        step(advanceNs = 100_000_000L)
        val second = createKeyframes(FIRST + 1).single()
        step(advanceNs = 100_000_000L)
        val third = createKeyframes(FIRST + 2).single()
        query(limit = 3, detachAfter = 1)
        // The oldest moves by 6 mm just before the limit is hit: it is no longer long-stable.
        step(advanceNs = 900_000_000L)
        oldest.pose[2] = 0.006f
        step(updated = listOf(oldest))
        step(advanceNs = 300_000_000L)
        query(limit = 3, detachAfter = 1, requests = listOf(KeyframeRequest(FIRST + 3)))
        step()
        val reply = query(limit = 3, detachAfter = 1)
        assertTrue(second.detached)
        assertFalse(oldest.detached)
        assertFalse(third.detached)
        val final = reply.updates.single { it.anchorId == FIRST + 1 }
        assertEquals(AnchorTracking.DETACHED_BY_LIMIT, final.tracking)
        assertEquals(AnchorCreation.CREATED, reply.creations.single().status)
        assertEquals(3, tracker.trackedAnchors)
        assertEquals(3, reply.header(AnchorPoseUpdateLayout.TRACKED_ANCHORS_OFFSET))
        assertEquals(0, query(limit = 3, detachAfter = 1).updateCount)
        assertEquals(1L, tracker.snapshot()["detachedByLimit"])

        // Next creation: the oldest long-stable anchor is now the third one (oldest moved recently).
        query(limit = 3, detachAfter = 1, requests = listOf(KeyframeRequest(FIRST + 4)))
        step()
        assertTrue(third.detached)
        assertFalse(oldest.detached)
    }

    @Test
    fun `tracking turns inactive ten seconds after the last query and detaches everything`() {
        val anchors = createKeyframes(FIRST, FIRST + 1)
        clock.now += 9_900_000_000L
        step()
        assertTrue(tracker.isActive)
        clock.now += 100_000_000L
        step()
        assertFalse(tracker.isActive)
        assertTrue(anchors.all { it.detached })
        assertEquals(0, tracker.trackedAnchors)
        assertFalse(tracker.armed)
    }

    @Test
    fun `clearing the tracking flag detaches on the next frame`() {
        val anchors = createKeyframes(FIRST)
        val reply = query(flags = 0)
        assertEquals(0, reply.header(AnchorPoseUpdateLayout.TRACKED_ANCHORS_OFFSET))
        assertTrue("armed until the detach ran", tracker.armed)
        assertFalse(anchors.single().detached)
        step()
        assertTrue(anchors.single().detached)
        assertFalse(tracker.armed)
    }

    @Test
    fun `detach all clears the group before handling the requests`() {
        val old = createKeyframes(FIRST, FIRST + 1)
        query()
        val reply = query(
            flags = PoseQueryLayout.FLAG_TRACKING or PoseQueryLayout.FLAG_DETACH_ALL,
            requests = listOf(KeyframeRequest(FIRST + 1)),
        )
        assertEquals(0, reply.updateCount)
        step()
        assertTrue(old.all { it.detached })
        val next = query()
        assertEquals(AnchorCreation.CREATED, next.creations.single().status)
        assertEquals(FIRST + 1, next.updates.single().anchorId)
        assertEquals(1, tracker.trackedAnchors)
    }

    @Test
    fun `session replacement detaches and clears every anchor`() {
        val anchors = createKeyframes(FIRST)
        query()
        frame.timestampNs += 33_000_000L
        tracker.onFrame(OTHER_SESSION, frame)
        assertTrue(anchors.single().detached)
        assertEquals(0, tracker.trackedAnchors)
        assertEquals(1L, tracker.snapshot()["sessionReplacements"])
    }

    @Test
    fun `reply reports the longest frame interval since the last reply`() {
        createKeyframes(FIRST)
        query()
        step(advanceNs = 40_000_000L)
        step(advanceNs = 90_000_000L)
        step(advanceNs = 30_000_000L)
        assertEquals(90_000, query().header(AnchorPoseUpdateLayout.FRAME_INTERVAL_MAX_MICROS_OFFSET))
        step(advanceNs = 20_000_000L)
        assertEquals(20_000, query().header(AnchorPoseUpdateLayout.FRAME_INTERVAL_MAX_MICROS_OFFSET))
    }

    @Test
    fun `tables do not grow in steady state`() {
        val big = AnchorPoseTracker(backend, clock, initialCapacity = 256)
        val requests = (0 until 64).map { KeyframeRequest(FIRST + it) }
        big.handleQuery(poseQuery(requests = requests), out, 0)
        frame.setUpdated(emptyList())
        big.onFrame(SESSION, frame)
        val anchors = backend.created.toList()
        big.handleQuery(poseQuery(), out, 1)
        val warm = big.growthEvents
        frame.setUpdated(anchors)
        repeat(200) { i ->
            anchors.forEach { it.pose[0] = (i % 2) * 0.01f }
            frame.timestampNs += 33_000_000L
            big.onFrame(SESSION, frame)
            if (i % 3 == 0) big.handleQuery(poseQuery(), out, 2 + i)
        }
        assertEquals(0, warm)
        assertEquals(0, big.growthEvents)
    }

    @Test
    fun `frames and replies allocate nothing in steady state`() {
        val requests = (0 until 64).map { KeyframeRequest(FIRST + it) }
        tracker.handleQuery(poseQuery(requests = requests), out, 0)
        frame.setUpdated(emptyList())
        tracker.onFrame(SESSION, frame)
        val anchors = backend.created.toList()
        frame.setUpdated(anchors)
        val q = poseQuery()
        var flip = 0f
        val work = {
            for (i in 0 until 200) {
                flip = 0.01f - flip
                for (a in 0 until anchors.size) anchors[a].pose[0] = flip
                frame.timestampNs += 33_000_000L
                tracker.onFrame(SESSION, frame)
                tracker.handleQuery(q, out, i)
            }
        }
        val probe = AllocationProbe()
        repeat(5) { work() }
        val warmGrowth = tracker.growthEvents
        val empty = probe.measure { }
        val allocated = probe.measure(work) - empty
        assertTrue("bytes allocated by 200 frames and replies: $allocated", allocated < 1024L)
        assertEquals(warmGrowth, tracker.growthEvents)
    }

    /** Thread allocation counter through reflection (the Android compile classpath has no java.lang.management). */
    private class AllocationProbe {
        private val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        private val read = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        private val thread: Any = Thread.currentThread().id

        init {
            Class.forName("com.sun.management.ThreadMXBean")
                .getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
            repeat(10) { measure { } }
        }

        fun measure(block: () -> Unit): Long {
            val before = read.invoke(bean, thread) as Long
            block()
            return (read.invoke(bean, thread) as Long) - before
        }
    }

    @Test
    fun `rows convert to the pose they were built from`() {
        val tq = yawPose(30.0, 1.5f)
        val rows = FloatArray(12)
        AnchorPoseTracker.poseMatrix(tq, 0, rows, rows = true)
        val back = FloatArray(7)
        assertTrue(AnchorPoseTracker.rowsToPose(rows, 0, back))
        for (k in 0 until 7) assertEquals(tq[k], back[k], 1e-6f)
        assertFalse(AnchorPoseTracker.rowsToPose(FloatArray(12), 0, back))
    }

    private companion object {
        const val FIRST = AnchorIds.FIRST_KEYFRAME_ID
        val SESSION = Any()
        val OTHER_SESSION = Any()
    }
}
