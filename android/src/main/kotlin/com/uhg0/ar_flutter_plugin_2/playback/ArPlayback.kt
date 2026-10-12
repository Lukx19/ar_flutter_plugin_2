package com.uhg0.ar_flutter_plugin_2.playback

import android.util.Log
import com.google.ar.core.Session
import com.uhg0.ar_flutter_plugin_2.recording.ArPlaybackTrackReader
import java.io.File
import java.util.UUID

/**
 * Debug/profile test mode: the view's ARCore session replays a Recording &
 * Playback dataset instead of the camera (`CAPTURE3D_ARCORE_PLAYBACK_DATASET`).
 *
 * Every line this mode logs starts with [LOG_PREFIX] under tag [TAG], so a
 * device driver can follow the replay with `logcat -s ARPlayback`.
 */
internal const val TAG = "ARPlayback"
internal const val LOG_PREFIX = "[ARPlayback]"

/** How the replay continues after the dataset ends. */
internal enum class ArPlaybackRestart(val wireValue: String) {
    /** Flutter rebuilds the AR view: a new ARCore session replays the dataset. */
    VIEW("view"),

    /** Pause the same session, set the dataset again and resume. */
    SESSION("session"),

    /** Stay at the end of the dataset. */
    OFF("off"),
    ;

    companion object {
        fun parse(value: Any?): ArPlaybackRestart =
            values().firstOrNull { it.wireValue == value } ?: VIEW
    }
}

internal data class ArPlaybackConfig(
    /** Absolute dataset path on the device. */
    val datasetPath: String,
    val restart: ArPlaybackRestart,
    /** Number of earlier view restarts; 0 for the first view. */
    val restartGeneration: Int,
    /** Custom data tracks the view reads back from every playback frame. */
    val trackIds: List<UUID> = emptyList(),
)

internal sealed interface ArPlaybackSelection {
    /** No dataset was requested: the live camera runs. */
    object Disabled : ArPlaybackSelection

    data class Enabled(val config: ArPlaybackConfig) : ArPlaybackSelection

    /** A dataset was requested but cannot be used; the live camera runs. */
    data class Refused(val requestedPath: String, val reason: String) : ArPlaybackSelection
}

internal object ArPlaybackSelector {
    const val DATASET_PARAM = "arcorePlaybackDataset"
    const val RESTART_PARAM = "arcorePlaybackRestart"
    const val GENERATION_PARAM = "arcorePlaybackRestartGeneration"
    const val TRACK_IDS_PARAM = "arcorePlaybackTrackIds"

    /**
     * Resolves the creation parameters. Playback needs a debuggable build
     * (debug or profile); a relative path resolves against [externalFilesDir]
     * (`recordings/walk1.mp4` -> `<externalFilesDir>/recordings/walk1.mp4`).
     */
    fun select(
        creationParams: Map<*, *>?,
        isDebuggable: Boolean,
        externalFilesDir: File?,
        isFile: (File) -> Boolean = File::isFile,
    ): ArPlaybackSelection {
        val requested = (creationParams?.get(DATASET_PARAM) as? String)?.trim().orEmpty()
        if (requested.isEmpty()) return ArPlaybackSelection.Disabled
        if (!isDebuggable) return ArPlaybackSelection.Refused(requested, "release-build")
        if (requested.split('/', '\\').any { it == ".." }) {
            return ArPlaybackSelection.Refused(requested, "parent-segment")
        }
        val file = if (requested.startsWith("/")) {
            File(requested)
        } else {
            externalFilesDir?.let { File(it, requested) }
                ?: return ArPlaybackSelection.Refused(requested, "no-external-files-dir")
        }
        if (!isFile(file)) return ArPlaybackSelection.Refused(file.path, "dataset-missing")
        val generation = (creationParams?.get(GENERATION_PARAM) as? Number)?.toInt() ?: 0
        return ArPlaybackSelection.Enabled(
            ArPlaybackConfig(
                datasetPath = file.path,
                restart = ArPlaybackRestart.parse(creationParams?.get(RESTART_PARAM)),
                restartGeneration = generation.coerceAtLeast(0),
                trackIds = ArPlaybackTrackReader.parseTrackIds(creationParams?.get(TRACK_IDS_PARAM)),
            ),
        )
    }

    /**
     * ARCore cannot replay a Shared Camera session, so playback drops
     * [Session.Feature.SHARED_CAMERA]; shutter photos then use ARCore's CPU
     * camera image ([com.uhg0.ar_flutter_plugin_2.capture.PlaybackCameraImageExposureV2]).
     */
    fun sessionFeatures(
        requested: Set<Session.Feature>,
        selection: ArPlaybackSelection,
    ): Set<Session.Feature> =
        if (selection is ArPlaybackSelection.Enabled) requested - Session.Feature.SHARED_CAMERA else requested

    fun describe(selection: ArPlaybackSelection): String? = when (selection) {
        ArPlaybackSelection.Disabled -> null
        is ArPlaybackSelection.Enabled -> "$LOG_PREFIX configured path=${selection.config.datasetPath} " +
            "restart=${selection.config.restart.wireValue} generation=${selection.config.restartGeneration} " +
            "tracks=${selection.config.trackIds.size}"
        is ArPlaybackSelection.Refused -> "$LOG_PREFIX refused path=${selection.requestedPath} " +
            "reason=${selection.reason} (live camera)"
    }
}

/** The part of an ARCore session the playback lifecycle drives (fakeable in JVM tests). */
internal interface ArPlaybackSessionPort {
    /** True while the session is resumed. */
    val isResumed: Boolean
    fun setPlaybackDataset(path: String)
    fun setCameraTextureNames(textureIds: IntArray)
    fun pause()
    fun resume()
}

/** What the host does after a frame's playback status. */
internal enum class ArPlaybackAction { NONE, RESTART_VIEW, RESTART_SESSION }

/**
 * Playback lifecycle of one AR view.
 *
 * Order (benchmark replay fix, `benchmarks/visibility_grid/README.md`):
 * the session is configured, then the dataset is set, then the camera texture
 * names, then the session resumes (ARSession sets the display geometry on
 * resume). Setting a dataset resets the texture names; setting them before the
 * dataset makes every update fail with `AR_ERROR_TEXTURE_NOT_SET`.
 */
internal class ArPlaybackController(
    val config: ArPlaybackConfig,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    /** Restarts so far: earlier view generations plus session restarts. */
    @Volatile var restarts: Int = config.restartGeneration
        private set
    @Volatile var started = false
        private set
    private var finishedReported = false
    private var failureReported = false

    private val path get() = config.datasetPath

    /** Called once per new ARCore session, after configure and before resume. */
    fun onSessionCreated(port: ArPlaybackSessionPort, textureIds: IntArray): Boolean {
        val resumed = port.isResumed
        val ok = try {
            if (resumed) port.pause()
            port.setPlaybackDataset(path)
            true
        } catch (error: Exception) {
            log("$LOG_PREFIX failed path=$path stage=set-dataset error=${error.javaClass.simpleName}: ${error.message}")
            false
        }
        try {
            if (textureIds.isNotEmpty()) port.setCameraTextureNames(textureIds)
            if (resumed) port.resume()
        } catch (error: Exception) {
            log("$LOG_PREFIX failed path=$path stage=texture error=${error.javaClass.simpleName}: ${error.message}")
            return false
        }
        if (!ok) return false
        started = true
        finishedReported = false
        failureReported = false
        log(
            if (config.restartGeneration == 0) {
                "$LOG_PREFIX started path=$path restart=${config.restart.wireValue}"
            } else {
                "$LOG_PREFIX restarted path=$path strategy=view restarts=$restarts"
            },
        )
        return true
    }

    /** Called for every frame with `Session.playbackStatus.name`. */
    fun onFrame(playbackStatus: String): ArPlaybackAction {
        if (!started) return ArPlaybackAction.NONE
        when (playbackStatus) {
            "FINISHED" -> {
                if (finishedReported) return ArPlaybackAction.NONE
                finishedReported = true
                log("$LOG_PREFIX finished path=$path restarts=$restarts next=${config.restart.wireValue}")
                return when (config.restart) {
                    ArPlaybackRestart.VIEW -> ArPlaybackAction.RESTART_VIEW
                    ArPlaybackRestart.SESSION -> ArPlaybackAction.RESTART_SESSION
                    ArPlaybackRestart.OFF -> ArPlaybackAction.NONE
                }
            }
            "IO_ERROR" -> if (!failureReported) {
                failureReported = true
                log("$LOG_PREFIX failed path=$path status=IO_ERROR")
            }
        }
        return ArPlaybackAction.NONE
    }

    /** Same-session restart; call on the thread that updates the session, outside a frame callback. */
    fun restartSession(port: ArPlaybackSessionPort, textureIds: IntArray): Boolean {
        return try {
            port.pause()
            port.setPlaybackDataset(path)
            if (textureIds.isNotEmpty()) port.setCameraTextureNames(textureIds)
            port.resume()
            restarts++
            finishedReported = false
            log("$LOG_PREFIX restarted path=$path strategy=session restarts=$restarts")
            true
        } catch (error: Exception) {
            log("$LOG_PREFIX failed path=$path stage=restart error=${error.javaClass.simpleName}: ${error.message}")
            runCatching { if (!port.isResumed) port.resume() }
            false
        }
    }

    /** Arguments of the `onArPlaybackFinished` session-channel message. */
    fun finishedEvent(): Map<String, Any> = mapOf(
        "path" to path,
        "restart" to config.restart.wireValue,
        "restarts" to restarts,
    )
}
