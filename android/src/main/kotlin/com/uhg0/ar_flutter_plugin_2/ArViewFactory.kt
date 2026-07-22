package com.uhg0.ar_flutter_plugin_2

import android.app.Activity
import android.content.Context
import com.google.ar.core.Session
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import androidx.lifecycle.Lifecycle

class ArViewFactory(
    private val messenger: BinaryMessenger,
    private val activity: Activity,
    private val lifecycle: Lifecycle
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val creationParams = args as? Map<*, *>
        val enableHighResCapture =
            creationParams?.get("enableHighResCapture") as? Boolean ?: false
        val initialSessionFeatures =
            if (enableHighResCapture) setOf(Session.Feature.SHARED_CAMERA) else emptySet()

        return ArView(
            context = context,
            activity = activity,
            lifecycle = lifecycle,
            messenger = messenger,
            id = viewId,
            initialSessionFeatures = initialSessionFeatures,
        )
    }
}
