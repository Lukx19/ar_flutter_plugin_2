package com.uhg0.ar_flutter_plugin_2.sceneview

import android.content.Context
import com.google.android.filament.Engine
import com.google.android.filament.Stream
import com.google.android.filament.Texture
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import java.io.File
import kotlin.math.sqrt

/** SceneView-independent transform payload. Matrices use Flutter's column-major order. */
internal data class PluginTransform(
    val matrix: List<Double>,
) {
    init {
        require(matrix.size == 16) { "A transform must contain 16 column-major values" }
        require(matrix.all(Double::isFinite)) { "Transform values must be finite" }
    }
}

internal data class PluginVector3(
    val x: Double,
    val y: Double,
    val z: Double,
)

internal data class PluginQuaternion(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
)

internal data class PluginTransformComponents(
    val position: PluginVector3,
    val rotation: PluginQuaternion,
    val scale: PluginVector3,
)

internal fun PluginTransform.decompose(): PluginTransformComponents {
    val m = matrix
    val sx = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
    val sy = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
    val sz = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
    require(sx > 0.0 && sy > 0.0 && sz > 0.0) { "Transform scale must be non-zero" }

    val r00 = m[0] / sx
    val r10 = m[1] / sx
    val r20 = m[2] / sx
    val r01 = m[4] / sy
    val r11 = m[5] / sy
    val r21 = m[6] / sy
    val r02 = m[8] / sz
    val r12 = m[9] / sz
    val r22 = m[10] / sz
    val trace = r00 + r11 + r22
    val quaternion = if (trace > 0.0) {
        val s = sqrt(trace + 1.0) * 2.0
        PluginQuaternion(
            x = (r21 - r12) / s,
            y = (r02 - r20) / s,
            z = (r10 - r01) / s,
            w = 0.25 * s,
        )
    } else if (r00 > r11 && r00 > r22) {
        val s = sqrt(1.0 + r00 - r11 - r22) * 2.0
        PluginQuaternion(
            x = 0.25 * s,
            y = (r01 + r10) / s,
            z = (r02 + r20) / s,
            w = (r21 - r12) / s,
        )
    } else if (r11 > r22) {
        val s = sqrt(1.0 + r11 - r00 - r22) * 2.0
        PluginQuaternion(
            x = (r01 + r10) / s,
            y = 0.25 * s,
            z = (r12 + r21) / s,
            w = (r02 - r20) / s,
        )
    } else {
        val s = sqrt(1.0 + r22 - r00 - r11) * 2.0
        PluginQuaternion(
            x = (r02 + r20) / s,
            y = (r12 + r21) / s,
            z = 0.25 * s,
            w = (r10 - r01) / s,
        )
    }
    return PluginTransformComponents(
        position = PluginVector3(m[12], m[13], m[14]),
        rotation = quaternion,
        scale = PluginVector3(sx, sy, sz),
    )
}

internal enum class PluginNodeSource {
    FLUTTER_ASSET_GLTF2,
    WEB_GLB,
    APP_FOLDER_GLB,
    APP_FOLDER_GLTF2,

    ;

    companion object {
        fun fromDartOrdinal(ordinal: Int): PluginNodeSource = entries.getOrNull(ordinal)
            ?: throw IllegalArgumentException("Unsupported node type: $ordinal")
    }
}

internal fun resolveNodeUri(
    source: PluginNodeSource,
    rawUri: String,
    appDataDirectory: String,
    flutterAssetResolver: (String) -> String,
): String = when (source) {
    PluginNodeSource.FLUTTER_ASSET_GLTF2 -> flutterAssetResolver(rawUri)
    PluginNodeSource.APP_FOLDER_GLB,
    PluginNodeSource.APP_FOLDER_GLTF2 ->
        File(File(appDataDirectory, "app_flutter"), rawUri).toURI().toString()
    PluginNodeSource.WEB_GLB -> rawUri
}

internal data class PluginNodeRecord(
    val id: String,
    val source: PluginNodeSource,
    val uri: String,
    val transform: PluginTransform,
    val data: Map<String, Any?> = emptyMap(),
)

internal data class PluginAnchorRecord(
    val id: String,
    val transform: PluginTransform,
    val childNodeIds: List<String> = emptyList(),
    val cloudAnchorId: String? = null,
    val ttlDays: Int = 1,
)

internal enum class PluginHitType {
    UNDEFINED,
    PLANE,
    POINT,
}

internal data class PluginHitResult(
    val type: PluginHitType,
    val distanceMeters: Double,
    val worldTransform: PluginTransform,
)

internal data class PluginSessionConfig(
    val showPlanes: Boolean,
    val showFeaturePoints: Boolean,
    val showWorldOrigin: Boolean,
    val handleTaps: Boolean,
    val handlePans: Boolean,
    val handleRotation: Boolean,
    val planeFindingMode: Config.PlaneFindingMode,
    val cloudAnchorEnabled: Boolean = false,
    val customPlaneTexturePath: String? = null,
)

internal data class PluginSceneError(
    val code: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
)

/** Minimal renderer/session surface consumed by the shared-camera capture pipeline. */
internal interface SceneViewCaptureHost {
    val context: Context
    val engine: Engine
    val activeSession: Session?
    val cameraTextureIds: IntArray
    val cameraTexture: Texture?

    /** Releases a capture-owned Filament stream while the renderer is still alive. */
    fun destroyCaptureStream(stream: Stream): Boolean {
        engine.destroyStream(stream)
        return true
    }

    /** Returns a current frame without double-updating a Compose-owned target session. */
    fun frameForCapture(): Frame?

    /** Prepares SceneView lifecycle state before SharedCamera resumes ARCore. */
    fun prepareSharedCameraResume(session: Session)
}

/** Enforces exactly-one create/dispose ownership independently of renderer implementation. */
internal class SceneViewHostOwnership {
    var createCount: Int = 0
        private set
    var disposeCount: Int = 0
        private set

    private var created = false
    private var disposed = false

    fun onCreate() {
        check(!created) { "SceneView host was created more than once" }
        check(!disposed) { "A disposed SceneView host cannot be recreated" }
        created = true
        createCount += 1
    }

    /** Returns true only for the first valid disposal. */
    fun onDispose(): Boolean {
        if (!created || disposed) return false
        disposed = true
        disposeCount += 1
        return true
    }
}

/** Coordinates SceneView's first resume with ARCore SharedCamera startup. */
internal class SharedCameraSceneLifecycleGate(sharedCameraRequested: Boolean) {
    private var resumeAllowed = !sharedCameraRequested

    /** The first SceneView-driven resume must be paused until Camera2 repeating is active. */
    fun shouldPauseSceneViewResume(): Boolean = !resumeAllowed

    /** Called immediately before SharedCameraManager resumes the coordinated ARCore session. */
    fun prepareSharedCameraResume() {
        resumeAllowed = true
    }
}

/** Exactly-once terminal reply fence for a bounded, replaceable native operation. */
internal class BoundedReplyFence<T> {
    private var generation = 0L
    private var reply: ((T) -> Unit)? = null

    fun begin(next: (T) -> Unit, superseded: T): Long {
        reply?.invoke(superseded)
        generation++
        reply = next
        return generation
    }

    fun settle(token: Long, terminal: T): Boolean {
        if (token != generation) return false
        val current = reply ?: return false
        reply = null
        current(terminal)
        return true
    }

    fun dispose(cancelled: T) {
        generation++
        reply?.invoke(cancelled)
        reply = null
    }
}
