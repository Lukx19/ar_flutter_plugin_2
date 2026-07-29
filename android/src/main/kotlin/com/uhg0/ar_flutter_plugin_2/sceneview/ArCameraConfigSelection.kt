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
