package com.uhg0.ar_flutter_plugin_2.sceneview

/** Renderer-thread metadata only. At most one promotion attempt for a qualified selection. */
internal class CoverageShapeResourceUpgrade {
    private var attemptedRendererGeneration = -1L
    private var attemptedSelection = 0L

    fun request(mode: CoveragePresentationMode, rendererGeneration: Long, selection: Long,
        geometryAvailable: Boolean, currentSupportsCapacity: Boolean): Boolean {
        if (mode != CoveragePresentationMode.PLANAR_PATCHES && mode != CoveragePresentationMode.DIRECTIONAL_BALLS) return false
        if (!geometryAvailable || currentSupportsCapacity || rendererGeneration < 0 || selection <= 0) return false
        if (attemptedRendererGeneration == rendererGeneration && attemptedSelection == selection) return false
        attemptedRendererGeneration = rendererGeneration
        attemptedSelection = selection
        return true
    }

    fun reset() { attemptedRendererGeneration = -1; attemptedSelection = 0 }
}
