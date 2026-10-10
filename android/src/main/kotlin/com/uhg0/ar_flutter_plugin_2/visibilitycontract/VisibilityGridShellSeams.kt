package com.uhg0.ar_flutter_plugin_2.visibilitycontract

/**
 * Registration points of the visibility grid platform shell. Every seam has a
 * no-op default and nothing calls it until the owning feature wires it into
 * the shared plugin files behind its flag, so adding these types changes no
 * behaviour.
 */
internal fun interface VisibilityGridFrameTap {
    /** Called once per AR frame on the frame thread; must not block. */
    fun onFrame(frame: com.google.ar.core.Frame)

    companion object {
        val NONE = VisibilityGridFrameTap { }
    }
}

/** Receives one call per photo after its durable terminal commit. */
internal fun interface PhotoSavedListener {
    /**
     * [photoId] is the 32 B SHA-256 of the durable store; [event] is a 128 B
     * buffer laid out as [PhotoSavedLayout], owned by the caller for the call.
     */
    fun onDurableTerminal(photoId: ByteArray, event: ByteArray)

    companion object {
        val NONE = PhotoSavedListener { _, _ -> }
    }
}

/** Attaches the grid renderer to the SceneView engine and scene. */
internal fun interface VisibilityGridRendererHostSeam {
    fun attach(engine: Any, scene: Any)

    companion object {
        val NONE = VisibilityGridRendererHostSeam { _, _ -> }
    }
}

/** Registers the ingest, render, display and control channels. */
internal fun interface VisibilityGridChannelRegistration {
    fun register(binaryMessenger: io.flutter.plugin.common.BinaryMessenger)

    companion object {
        val NONE = VisibilityGridChannelRegistration { }
    }
}
