package com.uhg0.ar_flutter_plugin_2.playback

import com.google.ar.core.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArPlaybackTest {
    private val filesDir = File("/sdcard/Android/data/com.capture3d.app/files")
    private val walk = File(filesDir, "recordings/walk1.mp4")

    private fun select(params: Map<String, Any?>?, debuggable: Boolean = true, exists: Boolean = true) =
        ArPlaybackSelector.select(params, debuggable, filesDir) { exists && it == walk }

    @Test fun noDatasetKeepsTheCamera() {
        assertEquals(ArPlaybackSelection.Disabled, select(null))
        assertEquals(ArPlaybackSelection.Disabled, select(mapOf("enableHighResCapture" to true)))
        assertEquals(ArPlaybackSelection.Disabled, select(mapOf(ArPlaybackSelector.DATASET_PARAM to " ")))
    }

    @Test fun relativePathsResolveAgainstExternalFilesDir() {
        val selection = select(
            mapOf(
                ArPlaybackSelector.DATASET_PARAM to "recordings/walk1.mp4",
                ArPlaybackSelector.RESTART_PARAM to "session",
                ArPlaybackSelector.GENERATION_PARAM to 2,
            ),
        )
        assertEquals(
            ArPlaybackSelection.Enabled(ArPlaybackConfig(walk.path, ArPlaybackRestart.SESSION, 2)),
            selection,
        )
        // An absolute device path is used as given (Android paths start with '/').
        val absolute = "/data/local/tmp/walk2.mp4"
        assertEquals(
            ArPlaybackSelection.Enabled(ArPlaybackConfig(File(absolute).path, ArPlaybackRestart.VIEW, 0)),
            ArPlaybackSelector.select(mapOf(ArPlaybackSelector.DATASET_PARAM to absolute), true, filesDir) {
                it == File(absolute)
            },
        )
    }

    @Test fun playbackCarriesTheCustomTrackIds() {
        val id = "5b0a4c1e-6f3c-4d8e-9a71-2c3d4e5f6a7b"
        val selection = select(
            mapOf(
                ArPlaybackSelector.DATASET_PARAM to "recordings/walk1.mp4",
                ArPlaybackSelector.TRACK_IDS_PARAM to listOf(id, "not-a-uuid"),
            ),
        ) as ArPlaybackSelection.Enabled
        assertEquals(listOf(java.util.UUID.fromString(id)), selection.config.trackIds)
        assertTrue(ArPlaybackSelector.describe(selection)!!.endsWith("tracks=1"))
    }

    @Test fun releaseBuildsMissingFilesAndParentSegmentsAreRefused() {
        val params = mapOf(ArPlaybackSelector.DATASET_PARAM to "recordings/walk1.mp4")
        assertEquals("release-build", (select(params, debuggable = false) as ArPlaybackSelection.Refused).reason)
        assertEquals("dataset-missing", (select(params, exists = false) as ArPlaybackSelection.Refused).reason)
        assertEquals(
            "parent-segment",
            (select(mapOf(ArPlaybackSelector.DATASET_PARAM to "../x.mp4")) as ArPlaybackSelection.Refused).reason,
        )
        assertEquals(
            "no-external-files-dir",
            (ArPlaybackSelector.select(params, true, null) { true } as ArPlaybackSelection.Refused).reason,
        )
    }

    @Test fun playbackDropsSharedCameraOnly() {
        val requested = setOf(Session.Feature.SHARED_CAMERA)
        val enabled = select(mapOf(ArPlaybackSelector.DATASET_PARAM to "recordings/walk1.mp4"))
        assertEquals(emptySet<Session.Feature>(), ArPlaybackSelector.sessionFeatures(requested, enabled))
        assertEquals(requested, ArPlaybackSelector.sessionFeatures(requested, ArPlaybackSelection.Disabled))
        assertEquals(
            requested,
            ArPlaybackSelector.sessionFeatures(requested, ArPlaybackSelection.Refused("x", "dataset-missing")),
        )
    }

    private class FakeSession(override var isResumed: Boolean = false) : ArPlaybackSessionPort {
        val calls = mutableListOf<String>()
        var failDataset = false
        override fun setPlaybackDataset(path: String) {
            check(!isResumed) { "session not paused" }
            if (failDataset) throw IllegalStateException("PlaybackFailedException")
            calls += "dataset:$path"
            // ARCore resets the texture names when a dataset is set.
            calls += "textures-reset"
        }
        override fun setCameraTextureNames(textureIds: IntArray) { calls += "textures:${textureIds.joinToString()}" }
        override fun pause() { isResumed = false; calls += "pause" }
        override fun resume() { isResumed = true; calls += "resume" }
    }

    private val logs = mutableListOf<String>()
    private fun controller(restart: ArPlaybackRestart = ArPlaybackRestart.VIEW, generation: Int = 0) =
        ArPlaybackController(ArPlaybackConfig("/data/walk1.mp4", restart, generation), logs::add)

    @Test fun datasetIsSetBeforeTheTextureNames() {
        val session = FakeSession()
        assertTrue(controller().onSessionCreated(session, intArrayOf(7)))
        // The texture names are set after the dataset reset them; resume is SceneView's.
        assertEquals(listOf("dataset:/data/walk1.mp4", "textures-reset", "textures:7"), session.calls)
        assertEquals(listOf("[ARPlayback] started path=/data/walk1.mp4 restart=view"), logs)
    }

    @Test fun anAlreadyResumedSessionIsPausedAroundTheDataset() {
        val session = FakeSession(isResumed = true)
        assertTrue(controller().onSessionCreated(session, intArrayOf(7)))
        assertEquals(listOf("pause", "dataset:/data/walk1.mp4", "textures-reset", "textures:7", "resume"), session.calls)
    }

    @Test fun aRebuiltViewLogsARestart() {
        val c = controller(generation = 3)
        assertTrue(c.onSessionCreated(FakeSession(), intArrayOf(7)))
        assertEquals(3, c.restarts)
        assertEquals(listOf("[ARPlayback] restarted path=/data/walk1.mp4 strategy=view restarts=3"), logs)
    }

    @Test fun datasetFailureIsLoggedAndNotStarted() {
        val session = FakeSession().apply { failDataset = true }
        val c = controller()
        assertFalse(c.onSessionCreated(session, intArrayOf(7)))
        assertEquals(listOf("textures:7"), session.calls)
        assertTrue(logs.single().startsWith("[ARPlayback] failed path=/data/walk1.mp4 stage=set-dataset"))
        assertEquals(ArPlaybackAction.NONE, c.onFrame("FINISHED"))
    }

    @Test fun finishedRequestsOneViewRestart() {
        val c = controller()
        c.onSessionCreated(FakeSession(), intArrayOf(7))
        assertEquals(ArPlaybackAction.NONE, c.onFrame("OK"))
        assertEquals(ArPlaybackAction.RESTART_VIEW, c.onFrame("FINISHED"))
        assertEquals(ArPlaybackAction.NONE, c.onFrame("FINISHED"))
        assertEquals("[ARPlayback] finished path=/data/walk1.mp4 restarts=0 next=view", logs.last())
        assertEquals(mapOf("path" to "/data/walk1.mp4", "restart" to "view", "restarts" to 0), c.finishedEvent())
    }

    @Test fun sessionRestartPausesSetsDatasetThenTexturesAndResumes() {
        val c = controller(ArPlaybackRestart.SESSION)
        val session = FakeSession()
        c.onSessionCreated(session, intArrayOf(7))
        session.isResumed = true
        session.calls.clear()
        assertEquals(ArPlaybackAction.RESTART_SESSION, c.onFrame("FINISHED"))
        assertTrue(c.restartSession(session, intArrayOf(9)))
        assertEquals(listOf("pause", "dataset:/data/walk1.mp4", "textures-reset", "textures:9", "resume"), session.calls)
        assertEquals(1, c.restarts)
        assertEquals("[ARPlayback] restarted path=/data/walk1.mp4 strategy=session restarts=1", logs.last())
        // The next end of the dataset is reported again.
        assertEquals(ArPlaybackAction.RESTART_SESSION, c.onFrame("FINISHED"))
    }

    @Test fun failedSessionRestartResumesTheSession() {
        val c = controller(ArPlaybackRestart.SESSION)
        val session = FakeSession()
        c.onSessionCreated(session, intArrayOf(7))
        session.isResumed = true
        session.failDataset = true
        assertFalse(c.restartSession(session, intArrayOf(7)))
        assertTrue(session.isResumed)
        assertTrue(logs.last().startsWith("[ARPlayback] failed path=/data/walk1.mp4 stage=restart"))
    }

    @Test fun offStopsAtTheEndAndIoErrorsAreLoggedOnce() {
        val c = controller(ArPlaybackRestart.OFF)
        c.onSessionCreated(FakeSession(), intArrayOf(7))
        assertEquals(ArPlaybackAction.NONE, c.onFrame("FINISHED"))
        c.onFrame("IO_ERROR")
        c.onFrame("IO_ERROR")
        assertEquals(1, logs.count { it == "[ARPlayback] failed path=/data/walk1.mp4 status=IO_ERROR" })
    }
}
