package com.uhg0.ar_flutter_plugin_2.recording

import android.net.Uri
import com.google.ar.core.Frame
import com.google.ar.core.RecordingConfig
import com.google.ar.core.Session
import com.google.ar.core.Track
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/** [ArRecordingSessionPort] over an ARCore session. */
internal class ArCoreRecordingPort(private val session: Session) : ArRecordingSessionPort {
    override val recordingStatus: String get() = session.recordingStatus.name

    override fun startRecording(request: ArRecordingRequest) {
        val config = RecordingConfig(session)
            .setMp4DatasetUri(Uri.fromFile(File(request.datasetPath)))
            .setAutoStopOnPause(true)
        request.track?.let { track ->
            val arTrack = Track(session).setId(track.id).setMimeType(track.mimeType)
            if (track.metadata.isNotEmpty()) arTrack.setMetadata(ByteBuffer.wrap(track.metadata))
            config.addTrack(arTrack)
        }
        session.startRecording(config)
    }

    override fun stopRecording() = session.stopRecording()
}

/** Attaches [data] to this frame of a recording session. */
internal fun Frame.recordTrackBytes(trackId: UUID, data: ByteArray) =
    recordTrackData(trackId, ByteBuffer.wrap(data))

/** This playback frame's records of [trackId] as (frame timestamp ns, bytes). */
internal fun Frame.readTrackRecords(trackId: UUID): List<Pair<Long, ByteArray>> =
    getUpdatedTrackData(trackId).map { record ->
        try {
            val buffer = record.data
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            record.frameTimestamp to bytes
        } finally {
            record.close()
        }
    }
