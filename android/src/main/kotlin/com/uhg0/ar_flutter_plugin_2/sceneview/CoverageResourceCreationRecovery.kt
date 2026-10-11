package com.uhg0.ar_flutter_plugin_2.sceneview

/** A later canonical cut may retry one failed, unmounted generation once. */
internal data class CoverageResourceRecoveryCut(
    val bindingGeneration: Long,
    val groupGeneration: Long,
    val transactionId: Long,
    val geometryRevision: Long,
)

internal class CoverageResourceCreationRecovery {
    private data class Failure(val token: CoverageResourceToken, val cut: CoverageResourceRecoveryCut)
    private var failure: Failure? = null
    private var automaticRetryUsed = false
    private var awaitingMount: CoverageResourceToken? = null

    fun recordFailure(token: CoverageResourceToken, cut: CoverageResourceRecoveryCut) {
        val prior = failure
        if (prior == null || prior.cut.bindingGeneration != cut.bindingGeneration ||
            prior.cut.groupGeneration != cut.groupGeneration ||
            prior.token.sourceRendererGeneration != token.sourceRendererGeneration ||
            prior.token.mode != token.mode) automaticRetryUsed = false
        failure = Failure(token, cut)
        awaitingMount = null
    }

    /** The production owner/epoch operation, shared by the host and real-factory regressions. */
    fun tryReplaceFailedGeneration(owner: NativeCoverageRendererOwner,
        currentSlotToken: CoverageResourceToken?, sourceGeneration: Long,
        hostAlive: Boolean, slotEmpty: Boolean,
        factory: CoverageRendererResourceFactory): CoverageResourceToken? = synchronized(owner) {
        val current = owner.snapshot() ?: return@synchronized null
        val token = owner.currentResourceToken() ?: return@synchronized null
        val status = owner.status()
        val valid = hostAlive && status.visible && status.rendererUnavailable &&
            token.sourceRendererGeneration == sourceGeneration && token.mode == status.mode &&
            currentSlotToken == token
        if (!claim(token, CoverageResourceRecoveryCut(current.bindingGeneration, current.groupGeneration,
                current.transactionId, current.geometryRevision), valid,
                slotEmpty && factory.occupiedGenerationCount() == 0)) return@synchronized null
        val next = owner.requestResourceReplacement() ?: return@synchronized null
        replacementIssued(token, next)
        next
    }

    fun claim(token: CoverageResourceToken, cut: CoverageResourceRecoveryCut,
        ownershipValid: Boolean, resourcesDrained: Boolean): Boolean {
        val failed = failure ?: return false
        if (!ownershipValid || !resourcesDrained || automaticRetryUsed || token != failed.token ||
            cut.bindingGeneration != failed.cut.bindingGeneration ||
            cut.groupGeneration != failed.cut.groupGeneration ||
            cut.transactionId <= failed.cut.transactionId && cut.geometryRevision <= failed.cut.geometryRevision)
            return false
        automaticRetryUsed = true
        return true
    }

    fun replacementIssued(prior: CoverageResourceToken, next: CoverageResourceToken) {
        if (failure?.token == prior) awaitingMount = next
    }

    fun mounted(token: CoverageResourceToken) {
        if (token != awaitingMount) return
        failure = null
        awaitingMount = null
        automaticRetryUsed = false
    }
}
