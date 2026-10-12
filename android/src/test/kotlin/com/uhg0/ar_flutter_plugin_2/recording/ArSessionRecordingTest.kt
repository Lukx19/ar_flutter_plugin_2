package com.uhg0.ar_flutter_plugin_2.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class ArSessionRecordingTest {
    private val filesDir = Files.createTempDirectory("ar-recording").toFile()
    private val trackId = UUID.fromString("5b0a4c1e-6f3c-4d8e-9a71-2c3d4e5f6a7b")

    private class FakePort : ArRecordingSessionPort {
        val calls = mutableListOf<String>()
        var startFailure: Exception? = null
        override var recordingStatus: String = "NONE"
        override fun startRecording(request: ArRecordingRequest) {
            calls += "start:${File(request.datasetPath).name}:${request.track?.id}"
            startFailure?.let { throw it }
            recordingStatus = "OK"
        }
        override fun stopRecording() {
            calls += "stop"
            recordingStatus = "NONE"
        }
    }

    private class FakeFrame(var refuse: Boolean = false) : ArTrackDataSink {
        val records = mutableListOf<Pair<UUID, ByteArray>>()
        override fun recordTrackData(trackId: UUID, data: ByteArray) {
            if (refuse) throw IllegalStateException("load")
            records += trackId to data
        }
    }

    private fun parse(args: Map<String, Any?>, debuggable: Boolean = true, exists: Boolean = false) =
        ArRecordingRequests.parse(args, debuggable, filesDir) { exists }

    private fun request(): ArRecordingRequest =
        (parse(mapOf(
            ArRecordingRequests.PATH to "recordings/s1-20261012T101500Z.mp4",
            ArRecordingRequests.TRACK_ID to trackId.toString(),
            ArRecordingRequests.TRACK_MIME_TYPE to "application/x-capture3d-actions",
            ArRecordingRequests.TRACK_METADATA to byteArrayOf(1),
        )) as ArRecordingRequestParse.Accepted).request

    @Test fun requestResolvesUnderExternalFilesDirWithTheTrack() {
        val request = request()
        assertEquals(File(filesDir, "recordings/s1-20261012T101500Z.mp4").path, request.datasetPath)
        assertEquals(ArRecordingTrack(trackId, "application/x-capture3d-actions", byteArrayOf(1)), request.track)
        val noTrack = parse(mapOf(ArRecordingRequests.PATH to "/data/local/tmp/a.mp4")) as ArRecordingRequestParse.Accepted
        assertNull(noTrack.request.track)
        assertEquals(File("/data/local/tmp/a.mp4").path, noTrack.request.datasetPath)
        val defaultMime = parse(mapOf(
            ArRecordingRequests.PATH to "a.mp4",
            ArRecordingRequests.TRACK_ID to trackId.toString(),
        )) as ArRecordingRequestParse.Accepted
        assertEquals(ArRecordingRequests.DEFAULT_MIME_TYPE, defaultMime.request.track?.mimeType)
    }

    @Test fun requestRefusals() {
        fun reason(args: Map<String, Any?>, debuggable: Boolean = true, exists: Boolean = false) =
            (parse(args, debuggable, exists) as ArRecordingRequestParse.Refused).reason
        val ok = mapOf(ArRecordingRequests.PATH to "recordings/a.mp4")
        assertEquals("release-build", reason(ok, debuggable = false))
        assertEquals("path-missing", reason(mapOf(ArRecordingRequests.PATH to " ")))
        assertEquals("parent-segment", reason(mapOf(ArRecordingRequests.PATH to "../a.mp4")))
        assertEquals("not-mp4", reason(mapOf(ArRecordingRequests.PATH to "recordings/a.bin")))
        assertEquals("dataset-exists", reason(ok, exists = true))
        assertEquals("bad-track-id", reason(ok + (ArRecordingRequests.TRACK_ID to "nope")))
        assertEquals(
            "no-external-files-dir",
            (ArRecordingRequests.parse(ok, true, null) { false } as ArRecordingRequestParse.Refused).reason,
        )
    }

    @Test fun queuedTrackDataReachesTheNextFrameInOrder() {
        val log = mutableListOf<String>()
        val recorder = ArSessionRecorder(log = { log += it })
        val port = FakePort()
        assertFalse("not recording", recorder.enqueue(trackId, byteArrayOf(9)))
        val status = recorder.start(port, request()).getOrThrow()
        assertEquals(true, status["recording"])
        assertEquals("OK", status["status"])
        assertTrue(File(filesDir, "recordings").isDirectory)
        assertFalse("other track", recorder.enqueue(UUID.randomUUID(), byteArrayOf(1)))
        assertTrue(recorder.enqueue(trackId, byteArrayOf(1)))
        assertTrue(recorder.enqueue(trackId, byteArrayOf(2)))
        val frame = FakeFrame()
        recorder.onFrame(port, frame)
        assertEquals(listOf(1, 2), frame.records.map { it.second.single().toInt() })
        assertEquals(2, recorder.recorded)
        assertTrue(log.first().startsWith("[ARRecording] started path="))
    }

    @Test fun refusedFramesKeepDataThenDropAfterTheLimit() {
        val recorder = ArSessionRecorder(log = {})
        val port = FakePort()
        recorder.start(port, request())
        recorder.enqueue(trackId, byteArrayOf(1))
        val frame = FakeFrame(refuse = true)
        recorder.onFrame(port, frame)
        assertEquals(1, recorder.status(port)["pendingTrackData"])
        frame.refuse = false
        recorder.onFrame(port, frame)
        assertEquals(1, frame.records.size)
        recorder.enqueue(trackId, byteArrayOf(2))
        frame.refuse = true
        repeat(ArSessionRecorder.MAX_FRAME_REFUSALS) { recorder.onFrame(port, frame) }
        assertEquals(1, recorder.dropped)
        assertEquals(0, recorder.status(port)["pendingTrackData"])
    }

    @Test fun fullQueueDropsWithACounter() {
        val recorder = ArSessionRecorder(log = {}, queueCapacity = 2)
        recorder.start(FakePort(), request())
        assertTrue(recorder.enqueue(trackId, byteArrayOf(1)))
        assertTrue(recorder.enqueue(trackId, byteArrayOf(2)))
        assertFalse(recorder.enqueue(trackId, byteArrayOf(3)))
        assertEquals(1, recorder.dropped)
    }

    @Test fun stopWaitsForQueuedDataThenStops() {
        val recorder = ArSessionRecorder(log = {})
        val port = FakePort()
        recorder.start(port, request())
        recorder.enqueue(trackId, byteArrayOf(7))
        val results = mutableListOf<Map<String, Any?>>()
        recorder.requestStop(port) { results += it }
        assertTrue("waits for a frame", results.isEmpty())
        assertFalse("no data after a stop request", recorder.enqueue(trackId, byteArrayOf(8)))
        val frame = FakeFrame()
        recorder.onFrame(port, frame)
        assertEquals(1, frame.records.size)
        assertEquals(1, results.size)
        assertEquals(false, results.single()["recording"])
        assertEquals(1, results.single()["recordedTrackData"])
        assertEquals(listOf("start:s1-20261012T101500Z.mp4:$trackId", "stop"), port.calls)
        assertFalse(recorder.isRecording)
        // A later timeout is harmless.
        recorder.stopNow(port)
        assertEquals(1, port.calls.count { it == "stop" })
    }

    @Test fun stopWithoutQueuedDataAnswersAtOnceAndTimeoutDropsQueuedData() {
        val recorder = ArSessionRecorder(log = {})
        val port = FakePort()
        recorder.start(port, request())
        val results = mutableListOf<Map<String, Any?>>()
        recorder.requestStop(port) { results += it }
        assertEquals(1, results.size)
        recorder.start(port, request())
        recorder.enqueue(trackId, byteArrayOf(1))
        recorder.requestStop(port) { results += it }
        recorder.stopNow(port)
        assertEquals(2, results.size)
        assertEquals(1, results.last()["droppedTrackData"])
    }

    @Test fun failedStartLeavesTheRecorderIdle() {
        val recorder = ArSessionRecorder(log = {})
        val port = FakePort().apply { startFailure = IllegalStateException("bad state") }
        assertTrue(recorder.start(port, request()).isFailure)
        assertFalse(recorder.isRecording)
        port.startFailure = null
        assertTrue(recorder.start(port, request()).isSuccess)
        assertTrue(recorder.start(port, request()).isFailure)
    }

    @Test fun playbackTrackReaderDeliversRecordsOfItsTracks() {
        val other = UUID.randomUUID()
        val delivered = mutableListOf<List<ArTrackRecord>>()
        val reader = ArPlaybackTrackReader(listOf(trackId), deliver = { delivered += it }, log = {})
        reader.onFrame { id -> if (id == trackId) listOf(10L to byteArrayOf(1), 10L to byteArrayOf(2)) else emptyList() }
        reader.onFrame { emptyList() }
        assertEquals(1, delivered.size)
        val event = ArPlaybackTrackReader.event(delivered.single())
        assertEquals(trackId.toString(), event[0]["trackId"])
        assertEquals(10L, event[0]["frameTimestampNs"])
        assertArrayEquals(byteArrayOf(2), event[1]["data"] as ByteArray)
        assertEquals(2, reader.delivered)
        // A failing read is logged once and never thrown.
        val log = mutableListOf<String>()
        val failing = ArPlaybackTrackReader(listOf(other), deliver = {}, log = { log += it })
        repeat(2) { failing.onFrame { throw IllegalStateException("x") } }
        assertEquals(1, log.size)
    }

    @Test fun playbackTrackIdsParseSkipsInvalidEntries() {
        assertEquals(
            listOf(trackId),
            ArPlaybackTrackReader.parseTrackIds(listOf(trackId.toString(), "bad", 3, " $trackId")),
        )
        assertEquals(emptyList<UUID>(), ArPlaybackTrackReader.parseTrackIds(null))
    }
}
