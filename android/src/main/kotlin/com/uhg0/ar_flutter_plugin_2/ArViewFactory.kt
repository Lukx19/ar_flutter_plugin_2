package com.uhg0.ar_flutter_plugin_2

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.ar.core.Session
import com.uhg0.ar_flutter_plugin_2.playback.ArPlaybackSelection
import com.uhg0.ar_flutter_plugin_2.playback.ArPlaybackSelector
import com.uhg0.ar_flutter_plugin_2.playback.TAG as PLAYBACK_TAG
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import androidx.lifecycle.Lifecycle
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CommittedBaselineAuthority

class ArViewFactory(
    private val messenger: BinaryMessenger,
    private val activity: Activity,
    private val lifecycle: Lifecycle,
    private val CommittedBaselineAuthority: CommittedBaselineAuthority,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val creationParams = args as? Map<*, *>
        val enableHighResCapture =
            creationParams?.get("enableHighResCapture") as? Boolean ?: false
        val retainAssessedOriginals = selectRetainAssessedOriginals(creationParams)
        val requestedRearCameraId = creationParams?.get("rearCameraId") as? String
        val depthInputEnabled = creationParams?.get("depthInputEnabled") as? Boolean ?: true
        val maxCameraImageHeight = (creationParams?.get("maxCameraImageHeight") as? Number)?.toInt() ?: 0
        val requestedSessionFeatures =
            if (enableHighResCapture) setOf(Session.Feature.SHARED_CAMERA) else emptySet()
        val playback = ArPlaybackSelector.select(
            creationParams = creationParams,
            isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            externalFilesDir = context.getExternalFilesDir(null),
        )
        ArPlaybackSelector.describe(playback)?.let { line ->
            if (playback is ArPlaybackSelection.Refused) Log.e(PLAYBACK_TAG, line) else Log.i(PLAYBACK_TAG, line)
        }
        val initialSessionFeatures = ArPlaybackSelector.sessionFeatures(requestedSessionFeatures, playback)

        return ArView(
            context = context,
            activity = activity,
            lifecycle = lifecycle,
            messenger = messenger,
            id = viewId,
            initialSessionFeatures = initialSessionFeatures,
            requestedRearCameraId = requestedRearCameraId,
            depthInputEnabled = depthInputEnabled,
            maxCameraImageHeight = maxCameraImageHeight,
            retainAssessedOriginals = retainAssessedOriginals,
            CommittedBaselineAuthority = CommittedBaselineAuthority,
            playbackConfig = (playback as? ArPlaybackSelection.Enabled)?.config,
        )
    }
}

internal fun selectRetainAssessedOriginals(
    creationParams: Map<*, *>?,
    isDebugBuild: Boolean = BuildConfig.DEBUG,
): Boolean = isDebugBuild && creationParams?.get("retainAssessedOriginals") == true
