package com.uhg0.ar_flutter_plugin_2.sceneview

/**
 * ARCore only guarantees its preferred ordering for the first item in the
 * complete result. Filtering that list by camera id afterwards can therefore
 * accidentally select a 30 fps entry before a supported 60 fps entry.
 */
internal fun <T> preferHighestFpsConfig(
    configs: List<T>,
    maximumFps: (T) -> Int,
): T? = configs.maxByOrNull(maximumFps)

/** Sizes and frame rate of one ARCore CameraConfig. */
internal data class ArCameraConfigShape(
    val imageWidth: Int,
    val imageHeight: Int,
    val textureWidth: Int,
    val textureHeight: Int,
    val maxFps: Int,
) {
    fun toChannelMap(): Map<String, Any> = mapOf(
        "imageWidth" to imageWidth,
        "imageHeight" to imageHeight,
        "textureWidth" to textureWidth,
        "textureHeight" to textureHeight,
        "maxFps" to maxFps,
    )

    override fun toString(): String =
        "cpu ${imageWidth}x$imageHeight gpu ${textureWidth}x$textureHeight ${maxFps}fps"
}

/** AR camera image tiers offered in Settings (image height in pixels). */
internal val arCameraResolutionTiers = listOf(1080, 720, 480)

/**
 * The config for an AR camera tier of at most [maxImageHeight] CPU image
 * rows; 0 keeps the default choice ([preferHighestFpsConfig]). A tier only
 * ever lowers the session: candidates fit the tier and are no larger than
 * the default in CPU image and GPU texture height (a larger CPU image beside
 * the shared camera's still stream failed the Galaxy A40 camera session and
 * crashed its camera HAL). Among them it prefers the highest frame rate,
 * then a GPU texture that fits too, then the largest CPU image, then the
 * smallest texture. Without a candidate the default choice stays.
 */
internal fun <T> selectConfigForMaxImageHeight(
    configs: List<T>,
    maxImageHeight: Int,
    shape: (T) -> ArCameraConfigShape,
): T? {
    val default = preferHighestFpsConfig(configs) { shape(it).maxFps }
    if (maxImageHeight <= 0) return default
    val base = default?.let(shape) ?: return null
    val fitting = configs.filter {
        val s = shape(it)
        s.imageHeight <= maxImageHeight &&
            s.imageHeight <= base.imageHeight &&
            s.textureHeight <= base.textureHeight
    }
    if (fitting.isEmpty()) return default
    return fitting.maxWithOrNull(
        compareBy<T> { shape(it).maxFps }
            .thenBy { if (shape(it).textureHeight <= maxImageHeight) 1 else 0 }
            .thenBy { shape(it).imageHeight }
            .thenByDescending { shape(it).textureHeight },
    )
}

/**
 * Tiers of [arCameraResolutionTiers] that select a config other than the
 * default, with the shape each selects; the default's shape under "auto".
 */
internal fun arCameraResolutionChoices(shapes: List<ArCameraConfigShape>): Map<String, Any> {
    val auto = selectConfigForMaxImageHeight(shapes, 0) { it } ?: return emptyMap()
    val tiers = LinkedHashMap<String, Any>()
    for (tier in arCameraResolutionTiers) {
        val selected = selectConfigForMaxImageHeight(shapes, tier) { it } ?: continue
        if (selected != auto && selected.imageHeight <= tier) tiers[tier.toString()] = selected.toChannelMap()
    }
    return mapOf("auto" to auto.toChannelMap(), "tiers" to tiers)
}

/**
 * Configs of the last ARCore session per rear camera id, and ARCore's
 * default rear camera id, so Settings opened over a running capture can list
 * the tiers without a second ARCore session.
 */
internal object ArCameraConfigCatalog {
    private val byCamera = java.util.concurrent.ConcurrentHashMap<String, List<ArCameraConfigShape>>()

    /** ARCore's default rear camera id (a new session's camera), once seen. */
    @Volatile var defaultCameraId: String? = null
        private set

    fun record(cameraId: String, shapes: List<ArCameraConfigShape>) {
        if (shapes.isNotEmpty()) byCamera[cameraId] = shapes
    }

    /** Records the camera id of a new ARCore session before its config is changed. */
    fun recordDefaultCameraId(cameraId: String) {
        defaultCameraId = cameraId
    }

    /**
     * Configs of rear camera [cameraId]; null [cameraId] is ARCore's default
     * rear camera. Null when that camera's configs, or the default camera
     * id, were not recorded yet.
     */
    fun shapes(cameraId: String?): List<ArCameraConfigShape>? =
        (cameraId ?: defaultCameraId)?.let { byCamera[it] }

    fun clear() {
        byCamera.clear()
        defaultCameraId = null
    }
}

/**
 * AR views from creation until their disposal released the ARCore session.
 * While any is open, opening another ARCore session (the tier probe) could
 * disrupt the live shared-camera session.
 */
internal object OpenArViews {
    private val views = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<Any, Boolean>(),
    )

    fun opened(view: Any) {
        synchronized(views) { views.add(view) }
    }

    fun closed(view: Any) {
        synchronized(views) { views.remove(view) }
    }

    val any: Boolean get() = synchronized(views) { views.isNotEmpty() }

    fun clear() {
        synchronized(views) { views.clear() }
    }
}

/** Configs a temporary ARCore session reported for one rear camera. */
internal data class ArCameraConfigProbe(
    /** The temporary session's own camera: ARCore's default rear camera. */
    val defaultCameraId: String,
    val cameraId: String,
    val shapes: List<ArCameraConfigShape>,
)

/**
 * AR camera tiers of rear camera [cameraId] (null: ARCore's default) for
 * Settings: the recorded configs of a running or earlier AR session when
 * known. Otherwise [probe] opens a temporary ARCore session for them, but
 * only while no AR view is open ([arViewOpen]); with one open the tiers are
 * unknown (an empty reply: Settings offers Auto only). A null probe result
 * is unknown too.
 */
internal fun lookUpArCameraResolutions(
    cameraId: String?,
    arViewOpen: Boolean,
    probe: (cameraId: String?) -> ArCameraConfigProbe?,
): Map<String, Any> {
    ArCameraConfigCatalog.shapes(cameraId)?.let { return arCameraResolutionChoices(it) }
    if (arViewOpen) return emptyMap()
    val probed = probe(cameraId) ?: return emptyMap()
    ArCameraConfigCatalog.recordDefaultCameraId(probed.defaultCameraId)
    ArCameraConfigCatalog.record(probed.cameraId, probed.shapes)
    return arCameraResolutionChoices(probed.shapes)
}

/** Selectable rear cameras of the last enumeration (channel maps). */
internal object RearCameraCatalog {
    @Volatile var cameras: List<Map<String, Any?>>? = null
        private set

    fun record(cameras: List<Map<String, Any?>>) {
        this.cameras = cameras
    }

    fun clear() {
        cameras = null
    }
}

/**
 * Selectable rear cameras for Settings: [enumerate] opens a temporary ARCore
 * session, so only while no AR view is open ([arViewOpen]); with one open,
 * the last enumeration or none (Settings hides the choice). A null
 * enumeration (ARCore failed) is none and is not remembered.
 */
internal fun lookUpSelectableRearCameras(
    arViewOpen: Boolean,
    enumerate: () -> List<Map<String, Any?>>?,
): List<Map<String, Any?>> {
    if (arViewOpen) return RearCameraCatalog.cameras ?: emptyList()
    val cameras = enumerate() ?: return emptyList()
    RearCameraCatalog.record(cameras)
    return cameras
}
