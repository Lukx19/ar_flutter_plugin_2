package com.uhg0.ar_flutter_plugin_2.recording

import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Developer-build session recording: the view's ARCore session writes a
 * Recording & Playback MP4 dataset (camera, IMU) plus custom data tracks the
 * app fills with [recordTrackData] (its user actions).
 *
 * ARCore records Shared Camera sessions ("Sessions that use Shared Camera can
 * be recorded", Recording & Playback developer guide); only their playback
 * needs a session without Shared Camera, which the playback test mode already
 * selects. Recording therefore keeps the capture session as it is.
 *
 * Every line logs under [TAG] with [LOG_PREFIX] (`logcat -s ARRecording`).
 */
internal const val TAG = "ARRecording"
internal const val LOG_PREFIX = "[ARRecording]"

/** One recording the app asked for. */
internal data class ArRecordingRequest(
    /** Absolute MP4 path on the device. */
    val datasetPath: String,
    /** Custom data track of the recording, or null for none. */
    val track: ArRecordingTrack?,
)

internal class ArRecordingTrack(
    val id: UUID,
    val mimeType: String,
    val metadata: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ArRecordingTrack && other.id == id && other.mimeType == mimeType &&
            other.metadata.contentEquals(metadata)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ArRecordingTrack($id, $mimeType, ${metadata.size} B)"
}

internal sealed interface ArRecordingRequestParse {
    data class Accepted(val request: ArRecordingRequest) : ArRecordingRequestParse
    data class Refused(val reason: String) : ArRecordingRequestParse
}

/** Parses the `startRecording` method arguments. */
internal object ArRecordingRequests {
    const val PATH = "path"
    const val TRACK_ID = "trackId"
    const val TRACK_MIME_TYPE = "trackMimeType"
    const val TRACK_METADATA = "trackMetadata"
    const val DEFAULT_MIME_TYPE = "application/octet-stream"

    /**
     * Recording needs a debuggable build (debug or profile, like playback);
     * a relative path resolves against [externalFilesDir]; the file must be
     * an `.mp4` and must not exist yet (a recording never overwrites one).
     */
    fun parse(
        arguments: Map<*, *>?,
        isDebuggable: Boolean,
        externalFilesDir: File?,
        exists: (File) -> Boolean = File::exists,
    ): ArRecordingRequestParse {
        if (!isDebuggable) return ArRecordingRequestParse.Refused("release-build")
        val requested = (arguments?.get(PATH) as? String)?.trim().orEmpty()
        if (requested.isEmpty()) return ArRecordingRequestParse.Refused("path-missing")
        if (requested.split('/', '\\').any { it == ".." }) {
            return ArRecordingRequestParse.Refused("parent-segment")
        }
        if (!requested.endsWith(".mp4")) return ArRecordingRequestParse.Refused("not-mp4")
        val file = if (requested.startsWith("/")) {
            File(requested)
        } else {
            externalFilesDir?.let { File(it, requested) }
                ?: return ArRecordingRequestParse.Refused("no-external-files-dir")
        }
        if (exists(file)) return ArRecordingRequestParse.Refused("dataset-exists")
        val trackId = arguments?.get(TRACK_ID) as? String
        val track = if (trackId.isNullOrBlank()) {
            null
        } else {
            val id = runCatching { UUID.fromString(trackId.trim()) }.getOrNull()
                ?: return ArRecordingRequestParse.Refused("bad-track-id")
            ArRecordingTrack(
                id = id,
                mimeType = (arguments[TRACK_MIME_TYPE] as? String)?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_MIME_TYPE,
                metadata = arguments[TRACK_METADATA] as? ByteArray ?: ByteArray(0),
            )
        }
        return ArRecordingRequestParse.Accepted(ArRecordingRequest(file.path, track))
    }
}

/** The part of an ARCore session and its current frame recording drives (fakeable in JVM tests). */
internal interface ArRecordingSessionPort {
    /** `Session.recordingStatus.name`: NONE, OK or IO_ERROR. */
    val recordingStatus: String

    /** Builds the RecordingConfig (auto stop on pause) and starts recording. */
    fun startRecording(request: ArRecordingRequest)
    fun stopRecording()
}

/** The current frame's `recordTrackData`. */
internal fun interface ArTrackDataSink {
    fun recordTrackData(trackId: UUID, data: ByteArray)
}

/**
 * Recording lifecycle of one AR view. Track data waits in a bounded queue
 * until the next frame: ARCore attaches it to the frame `Session.update`
 * returned, so the playback returns it with that frame's timestamp. A frame
 * that refuses data (load, not recording) keeps it for the next frame.
 *
 * Thread-safe: method calls and frames may run on different threads.
 */
internal class ArSessionRecorder(
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    private class Pending(val trackId: UUID, val data: ByteArray, var refusals: Int = 0)

    private val lock = Any()
    private val pending = ArrayDeque<Pending>()
    private var active: ArRecordingRequest? = null
    private var stopWaiter: ((Map<String, Any?>) -> Unit)? = null

    /** Track data records attached to frames. */
    var recorded: Int = 0
        private set

    /** Track data records dropped: full queue or refused by too many frames. */
    var dropped: Int = 0
        private set

    val isRecording: Boolean get() = synchronized(lock) { active != null }

    val activePath: String? get() = synchronized(lock) { active?.datasetPath }

    /** Starts [request]; the parent directory is created first. */
    fun start(port: ArRecordingSessionPort, request: ArRecordingRequest): Result<Map<String, Any?>> =
        synchronized(lock) {
            if (active != null) {
                return Result.failure(IllegalStateException("recording-active"))
            }
            try {
                File(request.datasetPath).parentFile?.mkdirs()
                port.startRecording(request)
            } catch (error: Exception) {
                log("$LOG_PREFIX failed path=${request.datasetPath} stage=start " +
                    "error=${error.javaClass.simpleName}: ${error.message}")
                return Result.failure(error)
            }
            active = request
            pending.clear()
            recorded = 0
            dropped = 0
            log("$LOG_PREFIX started path=${request.datasetPath} track=${request.track?.id}")
            Result.success(statusLocked(port))
        }

    /** Queues [data] for the next frame; false when not recording or the queue is full. */
    fun enqueue(trackId: UUID, data: ByteArray): Boolean = synchronized(lock) {
        val request = active ?: return false
        if (request.track?.id != trackId || stopWaiter != null) return false
        if (pending.size >= queueCapacity) {
            dropped++
            return false
        }
        pending.addLast(Pending(trackId, data))
        true
    }

    /** Frame callback: attaches queued data to [frame], then finishes a waiting stop. */
    fun onFrame(port: ArRecordingSessionPort, frame: ArTrackDataSink) {
        val finish: Pair<(Map<String, Any?>) -> Unit, Map<String, Any?>>?
        synchronized(lock) {
            if (active == null) return
            while (pending.isNotEmpty()) {
                val next = pending.first()
                try {
                    frame.recordTrackData(next.trackId, next.data)
                    pending.removeFirst()
                    recorded++
                } catch (error: Exception) {
                    next.refusals++
                    if (next.refusals >= MAX_FRAME_REFUSALS) {
                        pending.removeFirst()
                        dropped++
                        log("$LOG_PREFIX dropped track data after ${next.refusals} refusals " +
                            "error=${error.javaClass.simpleName}")
                    }
                    break
                }
            }
            val waiter = stopWaiter
            finish = if (waiter != null && pending.isEmpty()) waiter to stopLocked(port) else null
            if (finish != null) stopWaiter = null
        }
        finish?.let { (waiter, status) -> waiter(status) }
    }

    /**
     * Stops after the queued data reached a frame. [onStopped] runs at once
     * when nothing is queued, otherwise from the frame that drains the queue
     * or from [stopNow] (the host's timeout).
     */
    fun requestStop(port: ArRecordingSessionPort, onStopped: (Map<String, Any?>) -> Unit) {
        val status = synchronized(lock) {
            if (active == null) return@synchronized statusLocked(port)
            if (pending.isEmpty()) return@synchronized stopLocked(port)
            val earlier = stopWaiter
            stopWaiter = { result -> earlier?.invoke(result); onStopped(result) }
            null
        }
        if (status != null) onStopped(status)
    }

    /** Stops now, dropping queued data (stop timeout, view disposal). */
    fun stopNow(port: ArRecordingSessionPort) {
        val finish = synchronized(lock) {
            if (active == null) return
            dropped += pending.size
            pending.clear()
            val status = stopLocked(port)
            val waiter = stopWaiter
            stopWaiter = null
            waiter?.let { it to status }
        }
        finish?.let { (waiter, status) -> waiter(status) }
    }

    fun status(port: ArRecordingSessionPort?): Map<String, Any?> = synchronized(lock) { statusLocked(port) }

    private fun stopLocked(port: ArRecordingSessionPort): Map<String, Any?> {
        val path = active?.datasetPath
        try {
            port.stopRecording()
        } catch (error: Exception) {
            log("$LOG_PREFIX failed path=$path stage=stop error=${error.javaClass.simpleName}: ${error.message}")
        }
        val status = statusLocked(port, pathOverride = path)
        active = null
        log("$LOG_PREFIX stopped path=$path recorded=$recorded dropped=$dropped")
        return status
    }

    private fun statusLocked(port: ArRecordingSessionPort?, pathOverride: String? = null): Map<String, Any?> = mapOf(
        "path" to (pathOverride ?: active?.datasetPath),
        "recording" to (active != null && pathOverride == null),
        "status" to (runCatching { port?.recordingStatus }.getOrNull() ?: "NONE"),
        "recordedTrackData" to recorded,
        "droppedTrackData" to dropped,
        "pendingTrackData" to pending.size,
    )

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 256

        /** Frames a queued record may be refused before it is dropped. */
        const val MAX_FRAME_REFUSALS = 30

        /** Host timeout for a stop waiting on a frame. */
        const val STOP_TIMEOUT_MS = 500L
    }
}

/** One custom track record read back from a playback frame. */
internal class ArTrackRecord(val trackId: UUID, val frameTimestampNs: Long, val data: ByteArray)

/** The current playback frame's `getUpdatedTrackData` (fakeable). */
internal fun interface ArTrackDataSource {
    /** (frame timestamp ns, bytes) of every record of [trackId] on this frame. */
    fun updatedTrackData(trackId: UUID): List<Pair<Long, ByteArray>>
}

/**
 * Reads the custom tracks of a playback dataset frame by frame and hands
 * their records to [deliver] (the host posts them to Flutter as
 * `onArPlaybackTrackData`).
 */
internal class ArPlaybackTrackReader(
    val trackIds: List<UUID>,
    private val deliver: (List<ArTrackRecord>) -> Unit,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    var delivered: Int = 0
        private set
    private var failureLogged = false

    fun onFrame(frame: ArTrackDataSource) {
        if (trackIds.isEmpty()) return
        val records = ArrayList<ArTrackRecord>(0)
        for (id in trackIds) {
            try {
                frame.updatedTrackData(id).forEach { (timestamp, bytes) ->
                    records += ArTrackRecord(id, timestamp, bytes)
                }
            } catch (error: Exception) {
                if (!failureLogged) {
                    failureLogged = true
                    log("$LOG_PREFIX track read failed track=$id error=${error.javaClass.simpleName}: ${error.message}")
                }
            }
        }
        if (records.isEmpty()) return
        delivered += records.size
        deliver(records)
    }

    companion object {
        /** `onArPlaybackTrackData` arguments: one map per record. */
        fun event(records: List<ArTrackRecord>): List<Map<String, Any>> = records.map {
            mapOf(
                "trackId" to it.trackId.toString(),
                "frameTimestampNs" to it.frameTimestampNs,
                "data" to it.data,
            )
        }

        /** Track ids of the playback creation parameter (unknown entries skipped). */
        fun parseTrackIds(value: Any?): List<UUID> =
            (value as? List<*>).orEmpty().mapNotNull { entry ->
                (entry as? String)?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
            }.distinct()
    }
}
