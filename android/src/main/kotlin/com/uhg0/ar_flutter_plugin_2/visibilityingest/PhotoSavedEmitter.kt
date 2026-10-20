package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.capture.CaptureCommitRequest
import com.uhg0.ar_flutter_plugin_2.capture.CaptureComponentKind
import com.uhg0.ar_flutter_plugin_2.capture.CaptureLane
import com.uhg0.ar_flutter_plugin_2.capture.CaptureQualityReasonV1
import com.uhg0.ar_flutter_plugin_2.capture.CaptureTerminal
import com.uhg0.ar_flutter_plugin_2.capture.CaptureTerminalKind
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.PhotoSavedLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.PhotoSavedListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Called by the native capture adapter once per durable terminal it resolves. */
internal fun interface DurableTerminalTap {
    /** [exposureTimestampNs] is the Camera2 sensor timestamp of the committed still. */
    fun onDurableTerminal(request: CaptureCommitRequest, terminal: CaptureTerminal, exposureTimestampNs: Long)

    companion object {
        val NONE = DurableTerminalTap { _, _, _ -> }
    }
}

/** Still camera model at the still resolution, sensor (unrotated) axes like the legacy picture record. */
internal class StillCameraModel {
    var fx = 0.0
    var fy = 0.0
    var cx = 0.0
    var cy = 0.0
    var width = 0
    var height = 0

    /** OpenCV distortion (k1, k2, p1, p2, k3), zeros when not reported. */
    val distortion = DoubleArray(5)

    val valid: Boolean
        get() = fx.isFinite() && fx > 0.0 && fy.isFinite() && fy > 0.0 && cx.isFinite() && cy.isFinite() &&
            width > 0 && height > 0

    fun clear() {
        fx = 0.0; fy = 0.0; cx = 0.0; cy = 0.0; width = 0; height = 0
        distortion.fill(0.0)
    }

    companion object {
        /**
         * Reads the intrinsics map of `CameraIntrinsicsDeriver` (focalLength,
         * principalPoint, resolution, distortionCoefficients) or a flat map
         * with fx, fy, cx, cy, width, height.
         */
        fun fromMap(map: Map<*, *>?, out: StillCameraModel): Boolean {
            out.clear()
            if (map == null) return false
            fun number(m: Map<*, *>?, key: String): Double? = (m?.get(key) as? Number)?.toDouble()
            val focal = map["focalLength"] as? Map<*, *>
            val principal = map["principalPoint"] as? Map<*, *>
            val resolution = map["resolution"] as? Map<*, *>
            out.fx = number(map, "fx") ?: number(focal, "fx") ?: 0.0
            out.fy = number(map, "fy") ?: number(focal, "fy") ?: 0.0
            out.cx = number(map, "cx") ?: number(principal, "cx") ?: 0.0
            out.cy = number(map, "cy") ?: number(principal, "cy") ?: 0.0
            out.width = (number(map, "width") ?: number(resolution, "width") ?: 0.0).toInt()
            out.height = (number(map, "height") ?: number(resolution, "height") ?: 0.0).toInt()
            (map["distortionCoefficients"] as? List<*>)?.let { values ->
                for (i in 0 until minOf(5, values.size)) out.distortion[i] = (values[i] as? Number)?.toDouble() ?: 0.0
            }
            return out.valid
        }
    }
}

/** Supplies the current still camera model; false when unknown. */
internal fun interface StillCameraModelSource {
    fun read(out: StillCameraModel): Boolean

    companion object {
        val NONE = StillCameraModelSource { it.clear(); false }
    }
}

/**
 * A durable photo for consumers that need more than the 128 B event, such as
 * full-resolution tie-point detection: the event bytes, the store identity,
 * a resolver for the committed JPEG and the still camera model including
 * Camera2 lens distortion. Built once per photo off the frame path.
 */
internal class DurablePhoto(
    val photoId: ByteArray,
    val event: ByteArray,
    val captureId: String,
    val manifestId: String,
    val attemptId: String,
    val exposureTimestampNs: Long,
    val jpegSha256Hex: String?,
    val jpegBytes: Long,
    val camera: StillCameraModel,
    private val jpegLocator: DurableJpegLocator,
) {
    /** The committed JPEG blob (read-only), or null when it cannot be found. */
    fun jpegFile(): File? = jpegSha256Hex?.let { jpegLocator.locate(captureId, it) }
}

/** Receives every durable photo after its photo saved event was emitted. */
internal fun interface DurablePhotoListener {
    fun onPhotoDurable(photo: DurablePhoto)
}

/** Finds a committed JPEG blob in the native durable store; read-only. */
internal class DurableJpegLocator(private val storeRoot: File?) {
    fun locate(captureId: String, jpegSha256Hex: String): File? {
        val sessions = storeRoot?.let { File(it, "sessions") } ?: return null
        val entries = sessions.listFiles() ?: return null
        for (session in entries) {
            val file = File(session, "assets/$captureId/jpeg.$jpegSha256Hex.blob")
            if (file.isFile) return file
        }
        return null
    }
}

/**
 * Builds the photo saved event (contract 2.5) once per committed durable
 * terminal, including terminals reached through the adapter's retry paths
 * (store query and same-attempt rebase), and hands it to the frozen
 * [PhotoSavedListener] seam and then to every [DurablePhotoListener].
 *
 * Exactly once: the adapter claims each terminal once; the emitter also
 * ignores a capture id it emitted among the last [RECENT] photos.
 *
 * Pose: the ARCore `Camera.getPose()` (sensor oriented, camera looks along
 * -Z, y up, image v down; the legacy `arcore_gl_c2w_v1` coverage convention)
 * matched at the Camera2 exposure timestamp in a history keyed by
 * `Frame.getAndroidCameraTimestamp()`, as the legacy still-pose extractor
 * does. Intrinsics: the still model at the still resolution in sensor axes,
 * as the legacy committed picture record. If no frame pose matches, the
 * shutter pose of the recovery context (OpenCV camera-to-world of
 * `pose_batch_v2`) is converted to the OpenGL convention; without either the
 * event carries zero intrinsics, which the grid treats as invalid.
 *
 * Epoch: the [UNGROUPED_EPOCH] of a shell started before its first group
 * names no group: a photo exposed then belongs to the group that relabelled
 * the same AR session ([recordEpochStart] `continuesSession`), as the
 * session commits it (a playback still is up to half the still interval
 * older than its request; a live shot can expose before the group's shell
 * restart reaches the frame).
 */
internal class PhotoSavedEmitter(
    private val stillCamera: () -> StillCameraModelSource,
    private val listener: () -> PhotoSavedListener,
    private val durableListeners: () -> Array<DurablePhotoListener>,
    private val jpegLocator: DurableJpegLocator,
    private val groupEpoch: () -> Int,
    /**
     * Receives one line per emitted photo with its epoch derivation
     * ([resolveEpoch]; debug and profile builds, null in release), so a
     * device run shows whether an exposure fell in the ungrouped epoch.
     */
    private val epochTrace: ((String) -> Unit)? = null,
) : DurableTerminalTap {
    companion object {
        const val RECENT = 64
        const val EPOCH_HISTORY = 16

        /** Epoch of a shell started without a group (capture group indices are 1-based). */
        const val UNGROUPED_EPOCH = 0
        private val HEX = Regex("[0-9a-f]{64}")
    }

    private val poseLock = Any()
    private val photoPoses = DepthPoseHistory()
    private val matched = FloatArray(16)
    private val event = ByteArray(PhotoSavedLayout.BYTES)
    private val eventView = ByteBuffer.wrap(event).order(ByteOrder.LITTLE_ENDIAN)
    private val photoId = ByteArray(32)
    private val recent = arrayOfNulls<String>(RECENT)
    private var recentNext = 0
    private val model = StillCameraModel()

    @Volatile var emitted = 0L; private set
    @Volatile var duplicates = 0L; private set
    @Volatile var poseFromHistory = 0L; private set
    @Volatile var poseFromShutter = 0L; private set
    @Volatile var poseMissing = 0L; private set
    @Volatile var listenerFailures = 0L; private set

    /** Pose match code of the last emitted event (header byte 49). */
    @Volatile var lastPoseMatch = IngestMessageLayout.POSE_MATCH_MISSING; private set
    @Volatile var lastPoseGapUs = 0; private set

    /** AR thread: records the frame pose under the camera image clock. */
    fun recordFramePose(androidCameraTimestampNs: Long, tq: FloatArray) {
        if (androidCameraTimestampNs <= 0L) return
        synchronized(poseLock) { photoPoses.pushTq(androidCameraTimestampNs, tq) }
    }

    fun clearPoses() {
        synchronized(poseLock) { photoPoses.clear() }
    }

    // Group epochs by start time under the camera image clock (the exposure
    // clock), newest last; a small ring because epochs change rarely.
    private val epochStartNs = LongArray(EPOCH_HISTORY)
    private val epochValue = IntArray(EPOCH_HISTORY)

    // The entry relabels the AR session of the previous entry (no restart).
    private val epochContinues = BooleanArray(EPOCH_HISTORY)
    private var epochCount = 0

    /**
     * AR thread: [epoch] applies from the frame with camera image time
     * [androidCameraTimestampNs] on. A photo is stamped with the epoch active
     * at its exposure, so a photo exposed before a group switch that becomes
     * durable after the shell restarted still belongs to its own group.
     * [continuesSession]: the frame belongs to the same AR session (same
     * world frame) as the previous one; false after a restart.
     */
    fun recordEpochStart(androidCameraTimestampNs: Long, epoch: Int, continuesSession: Boolean = false) {
        if (androidCameraTimestampNs <= 0L) return
        synchronized(poseLock) {
            if (epochCount > 0 && epochValue[epochCount - 1] == epoch) return
            if (epochCount == EPOCH_HISTORY) {
                System.arraycopy(epochStartNs, 1, epochStartNs, 0, EPOCH_HISTORY - 1)
                System.arraycopy(epochValue, 1, epochValue, 0, EPOCH_HISTORY - 1)
                System.arraycopy(epochContinues, 1, epochContinues, 0, EPOCH_HISTORY - 1)
                epochCount--
            }
            epochStartNs[epochCount] = androidCameraTimestampNs
            epochValue[epochCount] = epoch
            epochContinues[epochCount] = continuesSession
            epochCount++
        }
    }

    /**
     * Epoch trace line of the last emitted photo, built in [fill] under the
     * same [poseLock] acquisition that resolved the event's epoch; null
     * without an [epochTrace] sink.
     */
    private var pendingEpochTrace: String? = null

    /**
     * Under [poseLock]: the epoch the event of a photo exposed at [exposureNs]
     * carries, the newest epoch started at or before it. Without a recorded
     * epoch or exposure time, the current epoch; before the oldest recorded
     * start, the oldest recorded epoch (older ones were evicted). The
     * ungrouped epoch resolves to the group that relabelled the same AR
     * session after it. With [captureId], also sets [pendingEpochTrace] to
     * its derivation from the same history indices: the epoch in effect at
     * the exposure and its start, and the resolved epoch and its start
     * (camera image clock).
     */
    private fun resolveEpoch(exposureNs: Long, captureId: String?): Int {
        val exposed = exposedEpochIndex(exposureNs)
        val resolved = if (exposed < 0) -1 else resolvedEpochIndex(exposed)
        val epoch = if (resolved < 0) groupEpoch() else epochValue[resolved]
        if (captureId != null) {
            val prefix = "photo epoch capture=${captureId.take(12)} exposureNs=$exposureNs"
            pendingEpochTrace = if (resolved < 0) {
                "$prefix exposedEpoch=none resolvedEpoch=$epoch"
            } else {
                "$prefix exposedEpoch=${epochValue[exposed]} exposedStartNs=${epochStartNs[exposed]} " +
                    "resolvedEpoch=$epoch resolvedStartNs=${epochStartNs[resolved]}"
            }
        }
        return epoch
    }

    /** Under [poseLock]: history index in effect at [exposureNs], -1 without history or exposure time. */
    private fun exposedEpochIndex(exposureNs: Long): Int {
        if (epochCount == 0 || exposureNs <= 0L) return -1
        for (i in epochCount - 1 downTo 0) if (epochStartNs[i] <= exposureNs) return i
        return 0
    }

    /** Under [poseLock]: the ungrouped epoch at [exposed] moves to the group that relabelled the same AR session. */
    private fun resolvedEpochIndex(exposed: Int): Int {
        var at = exposed
        while (epochValue[at] == UNGROUPED_EPOCH && at + 1 < epochCount && epochContinues[at + 1]) at++
        return at
    }

    @Synchronized
    override fun onDurableTerminal(request: CaptureCommitRequest, terminal: CaptureTerminal, exposureTimestampNs: Long) {
        if (terminal.kind != CaptureTerminalKind.COMMITTED_PICTURE) return
        val captureId = terminal.captureId ?: return
        for (id in recent) {
            if (id == captureId) {
                duplicates++
                return
            }
        }
        recent[recentNext] = captureId
        recentNext = (recentNext + 1) % RECENT
        fill(request, terminal, captureId, exposureTimestampNs)
        emitted++
        epochTrace?.let { trace ->
            val line = pendingEpochTrace
            pendingEpochTrace = null
            if (line != null) {
                try {
                    trace(line)
                } catch (_: RuntimeException) {
                    listenerFailures++
                }
            }
        }
        try {
            listener().onDurableTerminal(photoId, event)
        } catch (_: RuntimeException) {
            listenerFailures++
        }
        val listeners = durableListeners()
        if (listeners.isEmpty()) return
        val camera = StillCameraModel().also { copy ->
            copy.fx = model.fx; copy.fy = model.fy; copy.cx = model.cx; copy.cy = model.cy
            copy.width = model.width; copy.height = model.height
            model.distortion.copyInto(copy.distortion)
        }
        val jpeg = request.components.firstOrNull { it.kind == CaptureComponentKind.JPEG }
        val photo = DurablePhoto(
            photoId.copyOf(), event.copyOf(), captureId, terminal.manifestId.orEmpty(), terminal.identity.attemptId,
            exposureTimestampNs, jpeg?.sha256?.let(::hex), jpeg?.byteLength ?: 0L, camera, jpegLocator,
        )
        for (l in listeners) {
            try {
                l.onPhotoDurable(photo)
            } catch (_: RuntimeException) {
                listenerFailures++
            }
        }
    }

    private fun fill(request: CaptureCommitRequest, terminal: CaptureTerminal, captureId: String, exposureNs: Long) {
        val b = eventView
        java.util.Arrays.fill(event, 0)
        idBytes(captureId, photoId)
        System.arraycopy(photoId, 0, event, PhotoSavedLayout.PHOTO_ID, 32)
        attemptUuid(terminal.identity.attemptId, event, PhotoSavedLayout.ATTEMPT_UUID)
        b.putLong(PhotoSavedLayout.EXPOSURE_TIMESTAMP_NS, exposureNs)

        var match: Int
        var gapUs = 0
        synchronized(poseLock) {
            match = if (exposureNs > 0L) photoPoses.match(exposureNs, matched) else IngestMessageLayout.POSE_MATCH_MISSING
            if (match != IngestMessageLayout.POSE_MATCH_MISSING) gapUs = gapUs(photoPoses.errorNs)
        }
        var posed = match != IngestMessageLayout.POSE_MATCH_MISSING
        if (posed) {
            poseFromHistory++
        } else if (shutterPose(request, matched)) {
            posed = true
            poseFromShutter++
        } else {
            poseMissing++
        }
        lastPoseMatch = match
        lastPoseGapUs = gapUs
        if (posed) {
            for (row in 0 until 3) for (col in 0 until 4) {
                b.putFloat(PhotoSavedLayout.CAMERA_TO_WORLD_ROWS + 4 * (row * 4 + col), matched[col * 4 + row])
            }
        }
        val hasCamera = try {
            stillCamera().read(model)
        } catch (_: RuntimeException) {
            model.clear()
            false
        }
        if (posed && hasCamera) {
            b.putFloat(PhotoSavedLayout.INTRINSICS, model.fx.toFloat())
            b.putFloat(PhotoSavedLayout.INTRINSICS + 4, model.fy.toFloat())
            b.putFloat(PhotoSavedLayout.INTRINSICS + 8, model.cx.toFloat())
            b.putFloat(PhotoSavedLayout.INTRINSICS + 12, model.cy.toFloat())
            b.putShort(PhotoSavedLayout.WIDTH_DIV_8, (model.width / 8).toShort())
            b.putShort(PhotoSavedLayout.HEIGHT_DIV_8, (model.height / 8).toShort())
        }
        val assessment = terminal.qualityAssessment ?: request.qualityAssessment
        val score = assessment?.score
        if (score != null && score.isFinite() && score > 0.0) {
            b.putShort(PhotoSavedLayout.SHARPNESS, Math.round(score).coerceIn(0L, 65_535L).toInt().toShort())
        }
        var flags = 0
        if (request.accepted.lane == CaptureLane.AUTOMATIC) flags = flags or PhotoSavedLayout.FLAG_AUTOMATIC
        if (assessment?.reason == CaptureQualityReasonV1.BELOW_THRESHOLD) flags = flags or PhotoSavedLayout.FLAG_BLUR_REJECTED
        b.put(PhotoSavedLayout.FLAGS, flags.toByte())
        // One poseLock acquisition resolves the carried epoch and its trace line.
        val epoch = synchronized(poseLock) { resolveEpoch(exposureNs, if (epochTrace != null) captureId else null) }
        b.put(PhotoSavedLayout.GROUP_EPOCH_LOW, epoch.toByte())
    }

    /** Shutter pose from the recovery context: OpenCV camera-to-world to OpenGL (flip camera y and z). */
    private fun shutterPose(request: CaptureCommitRequest, out: FloatArray): Boolean {
        val context = request.recoveryContext ?: return false
        val p = context.position
        val q = context.rotation
        if (p.size != 3 || q.size != 4) return false
        val n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (!(n > 0.5)) return false
        val x = q[0] / n
        val y = q[1] / n
        val z = q[2] / n
        val w = q[3] / n
        // Column-major rotation of the OpenCV camera, then columns 1 and 2 negated.
        val r00 = 1 - 2 * (y * y + z * z); val r10 = 2 * (x * y + z * w); val r20 = 2 * (x * z - y * w)
        val r01 = 2 * (x * y - z * w); val r11 = 1 - 2 * (x * x + z * z); val r21 = 2 * (y * z + x * w)
        val r02 = 2 * (x * z + y * w); val r12 = 2 * (y * z - x * w); val r22 = 1 - 2 * (x * x + y * y)
        out[0] = r00.toFloat(); out[1] = r10.toFloat(); out[2] = r20.toFloat(); out[3] = 0f
        out[4] = (-r01).toFloat(); out[5] = (-r11).toFloat(); out[6] = (-r21).toFloat(); out[7] = 0f
        out[8] = (-r02).toFloat(); out[9] = (-r12).toFloat(); out[10] = (-r22).toFloat(); out[11] = 0f
        out[12] = p[0].toFloat(); out[13] = p[1].toFloat(); out[14] = p[2].toFloat(); out[15] = 1f
        return out.all { it.isFinite() }
    }

    /** The durable store's capture id is SHA-256 hex of the attempt id; anything else is hashed. */
    private fun idBytes(captureId: String, out: ByteArray) {
        if (HEX.matches(captureId)) {
            for (i in 0 until 32) {
                out[i] = ((Character.digit(captureId[2 * i], 16) shl 4) or Character.digit(captureId[2 * i + 1], 16)).toByte()
            }
        } else {
            MessageDigest.getInstance("SHA-256").digest(captureId.toByteArray(Charsets.UTF_8)).copyInto(out)
        }
    }

    /** Attempt ids are UUID strings; anything else maps to the first 16 bytes of its SHA-256. */
    private fun attemptUuid(attemptId: String, out: ByteArray, offset: Int) {
        val uuid = try {
            java.util.UUID.fromString(attemptId)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (uuid != null && attemptId.length == 36) {
            val view = ByteBuffer.wrap(out, offset, 16).order(ByteOrder.BIG_ENDIAN)
            view.putLong(offset, uuid.mostSignificantBits)
            view.putLong(offset + 8, uuid.leastSignificantBits)
        } else {
            MessageDigest.getInstance("SHA-256").digest(attemptId.toByteArray(Charsets.UTF_8)).copyInto(out, offset, 0, 16)
        }
    }

    private fun hex(values: List<Int>): String {
        val s = StringBuilder(values.size * 2)
        for (v in values) {
            s.append(Character.forDigit((v shr 4) and 0xf, 16)).append(Character.forDigit(v and 0xf, 16))
        }
        return s.toString()
    }
}

/**
 * Converts one photo saved event into an ingest message of kind 4 and offers
 * it to the ingest queue: the frozen [PhotoSavedListener] seam implemented by
 * the shell. The header carries the exposure timestamp, the same pose
 * (column-major) and the still intrinsics and size.
 */
internal class PhotoSavedIngestWriter(
    private val sink: IngestMessageSink,
    private val clock: IngestClock,
    private val trackingState: () -> Int,
    private val groupEpoch: () -> Int,
    private val poseMatch: () -> Int,
    private val poseGapUs: () -> Int,
) : PhotoSavedListener {
    private val message = ByteBuffer.allocateDirect(IngestMessageLayout.HEADER_BYTES + PhotoSavedLayout.BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
    private val event = ByteBuffer.allocate(PhotoSavedLayout.BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val pose = FloatArray(16)

    @Volatile var offered = 0L; private set

    @Synchronized
    override fun onDurableTerminal(photoId: ByteArray, event: ByteArray) {
        require(event.size == PhotoSavedLayout.BYTES)
        val e = this.event
        e.clear()
        e.put(event)
        val exposureNs = e.getLong(PhotoSavedLayout.EXPOSURE_TIMESTAMP_NS)
        for (row in 0 until 3) for (col in 0 until 4) {
            pose[col * 4 + row] = e.getFloat(PhotoSavedLayout.CAMERA_TO_WORLD_ROWS + 4 * (row * 4 + col))
        }
        pose[3] = 0f; pose[7] = 0f; pose[11] = 0f; pose[15] = 1f
        val b = message
        IngestMessageWriter.begin(b, IngestMessageLayout.KIND_PHOTO_SAVED)
        IngestMessageWriter.payloadBytes(b, PhotoSavedLayout.BYTES)
        IngestMessageWriter.timing(b, exposureNs, exposureNs, clock.nowNs())
        IngestMessageWriter.pose(b, trackingState(), poseMatch(), groupEpoch(), poseGapUs(), pose)
        IngestMessageWriter.intrinsics(b, e.getFloat(PhotoSavedLayout.INTRINSICS), e.getFloat(PhotoSavedLayout.INTRINSICS + 4),
            e.getFloat(PhotoSavedLayout.INTRINSICS + 8), e.getFloat(PhotoSavedLayout.INTRINSICS + 12))
        val w = (e.getShort(PhotoSavedLayout.WIDTH_DIV_8).toInt() and 0xffff) * 8
        val h = (e.getShort(PhotoSavedLayout.HEIGHT_DIV_8).toInt() and 0xffff) * 8
        IngestMessageWriter.image(b, w and 0xffff, h and 0xffff, 0, IngestMessageLayout.DEPTH_UNIT_U16_MM, IngestMessageLayout.CONFIDENCE_NONE)
        for (i in 0 until PhotoSavedLayout.BYTES) b.put(IngestMessageLayout.HEADER_BYTES + i, event[i])
        if (sink.offer(b, IngestMessageLayout.HEADER_BYTES + PhotoSavedLayout.BYTES)) offered++
    }
}
