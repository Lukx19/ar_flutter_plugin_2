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
        renderableInstance: Int = 1, parentVisible: Boolean = true,
        role: CoverageDisplayRole = CoverageDisplayRole.ACTIVE,
        residentReceipt: CoverageShapeDrawReceipt? = null,
        residentPresentation: CoverageDisplayResidencyReceipt? = null): Boolean {
        val accepts = if (role == CoverageDisplayRole.CANDIDATE) owner.acceptsCandidateResourceToken(token)
            else owner.acceptsResourceToken(token)
        val ownerCurrent = if (role == CoverageDisplayRole.CANDIDATE) owner.candidateDisplayDescriptor()
            else owner.activeDisplayDescriptor()
        val descriptorVisible = descriptor != null && ownerCurrent === descriptor && descriptor.enabled && descriptor.count > 0
        val receiptVisible = residentPresentation != null && role == CoverageDisplayRole.ACTIVE &&
            residentPresentation.tokenEpoch == token.epoch && residentPresentation.enabled && residentPresentation.count > 0
        val visibleIntent = enabled && owner.displayRequestedVisible() && accepts &&
            owner.isDisplayResidencyVisible(token) && (descriptorVisible || receiptVisible)
        if (!visibleIntent || renderableInstance == 0 || !parentVisible) {
            target.applyDrawState(0, 0, 0, 0, 0, visibleIntent, renderableInstance)
            return false
        }
        val current = completed?.takeIf { it === descriptor?.shapes && it.available }
        val selected = current?.header?.total ?: residentReceipt?.total ?: residentPresentation?.shape?.total ?: 0
        val indexCount = current?.indexCount ?: residentReceipt?.indexCount ?: residentPresentation?.shape?.indexCount ?: 0
        val pointCount = current?.pointCount ?: residentReceipt?.pointCount ?: residentPresentation?.shape?.pointCount ?: 0
        val shapeCount = current?.shapeCount ?: residentReceipt?.shapeCount ?: residentPresentation?.shape?.shapeCount ?: 0
        val descriptorCount = descriptor?.count ?: residentPresentation?.count ?: selected
        val effectiveVisible = target.applyDrawState(selected, (descriptorCount - selected).coerceAtLeast(0),
            indexCount, indexCount, pointCount, true, renderableInstance)
        return effectiveVisible && shapeCount > 0
    }
}
