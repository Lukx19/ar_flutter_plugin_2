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
 * Configs of the last ARCore session per rear camera id, so Settings opened
 * over a running capture can list the tiers without a second ARCore session.
 */
internal object ArCameraConfigCatalog {
    private val byCamera = java.util.concurrent.ConcurrentHashMap<String, List<ArCameraConfigShape>>()

    fun record(cameraId: String, shapes: List<ArCameraConfigShape>) {
        if (shapes.isNotEmpty()) byCamera[cameraId] = shapes
    }

    fun shapes(cameraId: String?): List<ArCameraConfigShape>? =
        if (cameraId != null) byCamera[cameraId] else byCamera.values.firstOrNull()

    fun clear() = byCamera.clear()
}
