package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.capture.CaptureAcceptedAttempt
import com.uhg0.ar_flutter_plugin_2.capture.CaptureAttemptIdentity
import com.uhg0.ar_flutter_plugin_2.capture.CaptureAttemptPhase
import com.uhg0.ar_flutter_plugin_2.capture.CaptureAttemptQualifierV2
import com.uhg0.ar_flutter_plugin_2.capture.CaptureCommitRequest
import com.uhg0.ar_flutter_plugin_2.capture.CaptureComponentDescriptor
import com.uhg0.ar_flutter_plugin_2.capture.CaptureComponentKind
import com.uhg0.ar_flutter_plugin_2.capture.CaptureComponentProfile
import com.uhg0.ar_flutter_plugin_2.capture.CaptureComponentStreamV2
import com.uhg0.ar_flutter_plugin_2.capture.CaptureLane
import com.uhg0.ar_flutter_plugin_2.capture.CaptureLifecycleCut
import com.uhg0.ar_flutter_plugin_2.capture.CaptureQualityAssessmentV1
import com.uhg0.ar_flutter_plugin_2.capture.CaptureQualityDispositionV1
import com.uhg0.ar_flutter_plugin_2.capture.CaptureQualityReasonV1
import com.uhg0.ar_flutter_plugin_2.capture.CaptureReceipt
import com.uhg0.ar_flutter_plugin_2.capture.CaptureReservationLiability
import com.uhg0.ar_flutter_plugin_2.capture.CaptureTerminal
import com.uhg0.ar_flutter_plugin_2.capture.CaptureTerminalKind
import com.uhg0.ar_flutter_plugin_2.capture.NativeCaptureAdapterV2
import com.uhg0.ar_flutter_plugin_2.capture.NativeCaptureReservationBoundsV2
import com.uhg0.ar_flutter_plugin_2.capture.NativeCaptureStorePortV2
import com.uhg0.ar_flutter_plugin_2.capture.SharedCameraComponentSetV2
import com.uhg0.ar_flutter_plugin_2.capture.SharedCameraExposureCallbackV2
import com.uhg0.ar_flutter_plugin_2.capture.SharedCameraExposurePortV2
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.PhotoSavedLayout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class PhotoSavedEmitterTest {
    private val clock = FakeClock(2_000_000_000L)
    private val shell = VisibilityGridIngestShell(clock, null)
    private val reply = CapturingReply()
    private val goldenPayload = golden("ingest_photo_saved.bin").copyOfRange(
        IngestMessageLayout.HEADER_BYTES, IngestMessageLayout.HEADER_BYTES + PhotoSavedLayout.BYTES,
    )

    private val captureId = (0 until 32).joinToString("") { "%02x".format(it) }
    private val attemptId = "a0a1a2a3-a4a5-a6a7-a8a9-aaabacadaeaf"
    private val exposureNs = 1_234_567_890_123L

    private fun stillCamera() {
        shell.stillCamera = StillCameraModelSource { out ->
            StillCameraModel.fromMap(
                mapOf(
                    "focalLength" to mapOf("fx" to 2800.0, "fy" to 2801.0),
                    "principalPoint" to mapOf("cx" to 2000.0, "cy" to 1500.0),
                    "resolution" to mapOf("width" to 4000, "height" to 3000),
                    "distortionCoefficients" to listOf(0.1, -0.02, 0.0, 0.0, 0.0),
                ),
                out,
            )
        }
    }

    /** Frames whose camera image clock brackets the exposure; pose translation (0.1, 0.2, 0.3). */
    private fun framesAroundExposure() {
        val frame = FakeFrame().apply { pose[0] = 0.1f; pose[1] = 0.2f; pose[2] = 0.3f }
        for (k in -3..3) {
            frame.timestampNs = 5_000_000_000L + k * 33_000_000L
            frame.androidCameraTimestampNs = exposureNs + k * 33_000_000L
            shell.process(frame, SESSION)
        }
    }

    @Test
    fun `event payload equals the photo saved golden and is queued as kind 4`() {
        shell.start(IngestShellConfig(0, 0, 0, 0, 0f, 3))
        stillCamera()
        framesAroundExposure()
        val photos = mutableListOf<DurablePhoto>()
        shell.durablePhotoListeners = arrayOf(DurablePhotoListener { photos += it })
        shell.onDurableTerminal(request(attemptId, CaptureLane.AUTOMATIC), committed(attemptId, captureId), exposureNs)

        shell.pull.onPull(pullRequest(), reply)
        val bytes = reply.bytes()
        assertEquals(IngestMessageLayout.KIND_PHOTO_SAVED, reply.kind())
        assertArrayEquals(goldenPayload, bytes.copyOfRange(IngestMessageLayout.HEADER_BYTES, bytes.size))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(exposureNs, b.getLong(IngestMessageLayout.SAMPLE_TIMESTAMP_NS))
        assertEquals(IngestMessageLayout.POSE_MATCH_EXACT, b.get(IngestMessageLayout.POSE_MATCH).toInt())
        assertEquals(0.3f, b.getFloat(IngestMessageLayout.CAMERA_TO_WORLD + 14 * 4))
        assertEquals(4000, b.getShort(IngestMessageLayout.WIDTH).toInt() and 0xffff)

        val photo = photos.single()
        assertArrayEquals(goldenPayload, photo.event)
        assertEquals(0.1, photo.camera.distortion[0], 0.0)
        assertEquals(captureId, photo.captureId)
        assertNull("no durable store in a host test", photo.jpegFile())
    }

    @Test
    fun `a photo exposed before a group restart carries the epoch of its exposure`() {
        shell.start(IngestShellConfig(0, 0, 0, 0, 0f, 3))
        stillCamera()
        framesAroundExposure() // epoch 3 around the exposure
        // The group switches (control restart to epoch 4) before the photo becomes durable.
        shell.start(IngestShellConfig(0, 0, 0, 0, 0f, 4))
        val frame = FakeFrame()
        frame.timestampNs = 5_200_000_000L
        frame.androidCameraTimestampNs = exposureNs + 200_000_000L
        shell.process(frame, SESSION)
        val photos = mutableListOf<DurablePhoto>()
        shell.durablePhotoListeners = arrayOf(DurablePhotoListener { photos += it })
        shell.onDurableTerminal(request(attemptId, CaptureLane.AUTOMATIC), committed(attemptId, captureId), exposureNs)
        assertEquals(3, photos.single().event[PhotoSavedLayout.GROUP_EPOCH_LOW].toInt())
        // A photo exposed after the switch carries the new epoch.
        val laterId = (32 until 64).joinToString("") { "%02x".format(it) }
        shell.onDurableTerminal(request(attemptId, CaptureLane.AUTOMATIC), committed(attemptId, laterId), exposureNs + 250_000_000L)
        assertEquals(4, photos.last().event[PhotoSavedLayout.GROUP_EPOCH_LOW].toInt())
    }

    @Test
    fun `missing frame pose falls back to the OpenCV shutter pose converted to OpenGL`() {
        shell.start(IngestShellConfig(0, 0, 0, 0, 0f, 3))
        stillCamera()
        // OpenCV camera: 180 degrees about x relative to the OpenGL camera, so identity in OpenGL.
        val request = request(attemptId, CaptureLane.MANUAL, shutterRotation = listOf(1.0, 0.0, 0.0, 0.0))
        shell.onDurableTerminal(request, committed(attemptId, captureId), exposureNs)
        assertEquals(1L, shell.photoEmitter.poseFromShutter)
        shell.pull.onPull(pullRequest(), reply)
        val payload = ByteBuffer.wrap(reply.bytes()).order(ByteOrder.LITTLE_ENDIAN)
        val rows = IngestMessageLayout.HEADER_BYTES + PhotoSavedLayout.CAMERA_TO_WORLD_ROWS
        val expected = floatArrayOf(1f, 0f, 0f, 0.1f, 0f, 1f, 0f, 0.2f, 0f, 0f, 1f, 0.3f)
        for (i in 0 until 12) assertEquals("row-major element $i", expected[i], payload.getFloat(rows + 4 * i), 1e-6f)
        assertEquals(0, payload.get(IngestMessageLayout.HEADER_BYTES + PhotoSavedLayout.FLAGS).toInt())
    }

    @Test
    fun `duplicate terminals are emitted once`() {
        shell.start(IngestShellConfig(0, 0, 0, 0, 0f, 3))
        val request = request(attemptId, CaptureLane.MANUAL)
        val terminal = committed(attemptId, captureId)
        shell.onDurableTerminal(request, terminal, exposureNs)
        shell.onDurableTerminal(request, terminal, exposureNs)
        assertEquals(1L, shell.photoEmitter.emitted)
        assertEquals(1L, shell.photoEmitter.duplicates)
        val photoOnly = 1 shl IngestMessageLayout.KIND_PHOTO_SAVED
        shell.pull.onPull(pullRequest(photoOnly), reply)
        assertEquals(IngestMessageLayout.KIND_PHOTO_SAVED, reply.kind())
        reply.last = null
        shell.pull.onPull(pullRequest(photoOnly), reply)
        assertNull("the duplicate was not queued", reply.last)
    }

    @Test
    fun `adapter reports one terminal per photo on the direct and both retry paths`() {
        for (path in listOf("direct", "query", "rebase")) {
            val calls = mutableListOf<Long>()
            val tap = DurableTerminalTap { _, terminal, exposure ->
                assertEquals(CaptureTerminalKind.COMMITTED_PICTURE, terminal.kind)
                calls += exposure
            }
            val store = RetryStore(path)
            val camera = SingleExposure()
            val adapter = NativeCaptureAdapterV2(store, camera, durableTerminalTap = tap)
            val request = request("attempt-$path", CaptureLane.MANUAL, exposure = 1L, assessed = false)
            adapter.admit(request)
            camera.deliver(555L)
            assertEquals(path, listOf(555L), calls)
            assertEquals(path, 1L, adapter.snapshot().committed)
            adapter.close()
        }
    }

    @Test
    fun `abandoned terminals emit nothing`() {
        val calls = mutableListOf<CaptureTerminal>()
        val store = RetryStore("abandon")
        val camera = SingleExposure()
        val adapter = NativeCaptureAdapterV2(store, camera, durableTerminalTap = { _, t, _ -> calls += t })
        adapter.admit(request("attempt-abandon", CaptureLane.MANUAL, assessed = false))
        camera.fail()
        assertEquals(emptyList<CaptureTerminal>(), calls)
        adapter.close()
    }

    @Test
    fun `jpeg locator finds the committed blob read-only`() {
        val root = Files.createTempDirectory("store").toFile()
        try {
            val blob = File(root, "sessions/s1/assets/$captureId/jpeg.abcd.blob").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            assertEquals(blob, DurableJpegLocator(root).locate(captureId, "abcd"))
            assertNull(DurableJpegLocator(root).locate(captureId, "ffff"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun request(
        attempt: String,
        lane: CaptureLane,
        exposure: Long = exposureNs,
        shutterRotation: List<Double>? = null,
        assessed: Boolean = shutterRotation == null,
    ): CaptureCommitRequest {
        val cut = CaptureLifecycleCut("session", 1, "group", 1, "ar", "view", 1, "binding", 1, 1)
        val kinds = setOf(CaptureComponentKind.JPEG)
        val accepted = CaptureAcceptedAttempt(
            CaptureAttemptIdentity(attempt, "commit-$attempt", 1, cut),
            lane,
            CaptureComponentProfile("profile", kinds, 1024, 1024),
            CaptureReservationLiability(
                1024, NativeCaptureReservationBoundsV2.physicalBytes(1024, 1), 1, 1,
                NativeCaptureReservationBoundsV2.rollbackBytes(1024), true,
            ),
            digest("intent"), digest("accepted"),
        )
        val jpeg = JPEG_BYTES
        val components = listOf(CaptureComponentDescriptor(CaptureComponentKind.JPEG, jpeg.size.toLong(), sha(jpeg), "jpeg-object"))
        val context = shutterRotation?.let {
            com.uhg0.ar_flutter_plugin_2.capture.NativeCaptureRecoveryContextV2(
                "session", "group", 0, 1, "manual", 1, 0, 1,
                listOf(0.1, 0.2, 0.3), it,
                List(16) { 0.0 }, List(16) { 0.0 }, List(16) { 0.0 }, List(16) { 0.0 },
            )
        }
        val assessment = CaptureQualityAssessmentV1(
            policyVersion = "jpeg-laplacian", disposition = CaptureQualityDispositionV1.POLICY_PASSED,
            reason = CaptureQualityReasonV1.POLICY_PASSED, score = 812.0, threshold = 110.0,
            sampleWidth = 512, sampleHeight = 384,
        )
        return CaptureCommitRequest(
            accepted, components, exposure, digest("pose"), digest("camera"), digest("validation"), digest("ledger"),
            recoveryContext = context,
            qualityAssessment = if (assessed) assessment else null,
        )
    }

    private fun committed(attempt: String, capture: String): CaptureTerminal {
        val cut = CaptureLifecycleCut("session", 1, "group", 1, "ar", "view", 1, "binding", 1, 1)
        return CaptureTerminal(
            CaptureTerminalKind.COMMITTED_PICTURE, CaptureAttemptIdentity(attempt, "commit-$attempt", 1, cut),
            "terminal-hash", "committed", capture, 1, "root", JPEG_BYTES.size.toLong(),
        )
    }

    /** Store whose commit succeeds directly, or fails after persisting (query) or before (rebase). */
    private class RetryStore(private val path: String) : NativeCaptureStorePortV2 {
        private var committed: CaptureReceipt? = null
        private var accepted: CaptureCommitRequest? = null

        override fun replayFenceBeforeExposure(request: CaptureCommitRequest): CaptureReceipt? = null
        override fun acceptBeforeExposure(request: CaptureCommitRequest): CaptureReceipt {
            accepted = request
            return CaptureReceipt(request.accepted.identity, CaptureAttemptPhase.RESERVED_ACCEPTED, "a", "a", true)
        }

        private fun receipt(request: CaptureCommitRequest): CaptureReceipt {
            val identity = request.accepted.identity
            val terminal = CaptureTerminal(
                CaptureTerminalKind.COMMITTED_PICTURE, identity, "commit", "committed",
                "capture-${identity.attemptId}", 1, "root", JPEG_BYTES.size.toLong(),
            )
            return CaptureReceipt(identity, CaptureAttemptPhase.COMMITTED_PICTURE, "commit", "receipt", true, terminal)
        }

        override fun commitStreamed(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>): CaptureReceipt {
            streams.forEach { it.input.readBytes() }
            return when (path) {
                "direct" -> receipt(request).also { committed = it }
                "query" -> {
                    committed = receipt(request)
                    throw IllegalStateException("acknowledgement lost after durable commit")
                }
                else -> throw IllegalStateException("interrupted before the pointer switch")
            }
        }

        override fun queryReceipt(identity: CaptureAttemptIdentity): CaptureReceipt? = committed
        override fun rebaseSameAttempt(request: CaptureCommitRequest): CaptureReceipt =
            if (path == "rebase") receipt(request).also { committed = it } else throw IllegalStateException("not prepared")
        override fun abandon(request: CaptureCommitRequest, terminal: CaptureTerminal): CaptureReceipt =
            CaptureReceipt(terminal.identity, CaptureAttemptPhase.ABANDONED_ATTEMPT, "x", "abandoned", true, terminal)
    }

    private class SingleExposure : SharedCameraExposurePortV2 {
        private var qualifier: CaptureAttemptQualifierV2? = null
        private var callback: SharedCameraExposureCallbackV2? = null
        override fun requestExposure(
            qualifier: CaptureAttemptQualifierV2,
            required: Set<CaptureComponentKind>,
            callback: SharedCameraExposureCallbackV2,
        ): Boolean {
            this.qualifier = qualifier
            this.callback = callback
            return true
        }
        override fun cancelExposure(qualifier: CaptureAttemptQualifierV2) = Unit
        fun deliver(exposureNs: Long) {
            callback!!.onComponents(
                SharedCameraComponentSetV2(
                    qualifier!!,
                    listOf(CaptureComponentStreamV2(CaptureComponentKind.JPEG, ByteArrayInputStream(JPEG_BYTES))),
                    exposureNs,
                ),
            )
        }
        fun fail() {
            callback!!.onFailure(qualifier!!, "synthetic-camera")
        }
    }

    private companion object {
        val SESSION = Any()
        val JPEG_BYTES = "component-JPEG".toByteArray()
        fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map { it.toInt() and 0xff }
        fun digest(value: String) = sha(value.toByteArray())
    }
}
