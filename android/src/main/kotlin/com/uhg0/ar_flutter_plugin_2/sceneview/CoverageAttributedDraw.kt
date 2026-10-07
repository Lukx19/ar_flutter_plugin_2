package com.uhg0.ar_flutter_plugin_2.sceneview

/** One renderable mutation; implementations write every primitive and its Filament layer. */
internal interface CoverageAttributedDrawTarget {
    fun applyDrawState(pointStart: Int, pointCount: Int, triangleCount: Int,
        fallbackStart: Int, fallbackCount: Int, visibleIntent: Boolean,
        renderableInstance: Int): Boolean
}

/** Stateless draw admission, shared by renderer frames and upload completions. */
internal object CoverageAttributedDraw {
    fun apply(target: CoverageAttributedDrawTarget, owner: NativeCoverageRendererOwner,
        token: CoverageResourceToken, descriptor: PresentationDescriptor?,
        completed: PreparedCoverageShapeReference?, enabled: Boolean,
        renderableInstance: Int = 1, parentVisible: Boolean = true): Boolean {
        val visibleIntent = enabled && owner.acceptsResourceToken(token) && descriptor != null &&
            owner.presentationDescriptor() === descriptor && descriptor.enabled && descriptor.count > 0
        if (!visibleIntent || renderableInstance == 0 || !parentVisible) {
            target.applyDrawState(0, 0, 0, 0, 0, visibleIntent, renderableInstance)
            return false
        }
        val current = completed?.takeIf { it === descriptor!!.shapes && it.available }
        val selected = current?.header?.total ?: 0
        val effectiveVisible = target.applyDrawState(selected, (descriptor!!.count - selected).coerceAtLeast(0),
            current?.indexCount ?: 0, current?.indexCount ?: 0, current?.pointCount ?: 0, true, renderableInstance)
        return effectiveVisible && current != null && current.shapeCount > 0
    }
}
