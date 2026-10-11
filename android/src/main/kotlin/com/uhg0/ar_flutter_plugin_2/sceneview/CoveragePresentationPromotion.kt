package com.uhg0.ar_flutter_plugin_2.sceneview

/**
 * Narrow readiness seam shared by the real SceneView binding and host-path
 * fixtures.  A candidate becomes visible only after its complete point/index
 * and attributed shape/fallback receipts have drained.
 */
internal fun interface CoveragePromotionReadiness {
    fun isWholeDescriptorUploadComplete(descriptor: BoundedCoveragePresentation): Boolean
}

internal object CoveragePresentationPromotion {
    /**
     * Performs the production ordering: prove the whole candidate, activate
     * the prepared factory generation, then atomically promote the owner role.
     */
    fun promote(
        owner: NativeCoverageRendererOwner,
        factory: CoverageRendererResourceFactory,
        binding: CoveragePromotionReadiness,
    ): Boolean {
        val token = owner.currentCandidateResourceToken() ?: return false
        val descriptor = owner.candidateDisplayDescriptor() ?: return false
        if (CoveragePresentationEligibility.resourceKind(descriptor, initial = false,
                completedAttributed = owner.completedShapeDrawReceipt(token, descriptor) != null) ==
            CoveragePresentationEligibility.ResourceKind.PENDING) return false
        if (!owner.candidatePromotionAllowed(token)) return false
        if (!binding.isWholeDescriptorUploadComplete(descriptor)) return false
        val qualifiedAt = owner.candidatePromotionQualificationTime(token) ?: return false
        if (!factory.activatePrepared(token)) return false
        return owner.promoteCandidateDisplay(token, qualifiedAt)
    }
}
