package com.uhg0.ar_flutter_plugin_2.sceneview

/** Actual host resource choice and whole-presentation promotion use this gate. */
internal object CoveragePresentationEligibility {
    enum class ResourceKind { POINTS, ATTRIBUTED, PENDING }

    /** CPU alias release does not end the mounted GPU lease or its compact display identity. */
    fun mountedResidencyResource(descriptor: BoundedCoveragePresentation?, role: CoverageDisplayRole,
        token: CoverageResourceToken?, owner: NativeCoverageRendererOwner,
        factory: CoverageRendererResourceFactory): Any? {
        if (descriptor != null || role != CoverageDisplayRole.ACTIVE || token == null ||
            owner.displayRoleFor(token) != CoverageDisplayRole.ACTIVE || !owner.acceptsResourceToken(token)) return null
        val resident = owner.activeDisplayResidencyReceipt(token) ?: return null
        if (resident.mode != token.mode || resident.qualifier.rendererGeneration != token.sourceRendererGeneration) return null
        val status = owner.status()
        if (!status.resourceAvailable || status.rendererUnavailable) return null
        return factory.mountedResource(token)
    }

    fun requiresCandidate(descriptor: BoundedCoveragePresentation?, active: BoundedCoveragePresentation?,
        resident: CoverageDisplayResidencyReceipt?, supportsAttributed: Boolean): Boolean =
        descriptor != null && (
            (resident?.qualifier ?: active?.qualifier) != descriptor.qualifier ||
                (resident?.mode ?: active?.mode) != descriptor.mode ||
                (resident?.palette ?: active?.palette) != descriptor.palette ||
                (resident?.shape?.selectionGeneration ?: active?.attributes?.selectionGeneration) !=
                    descriptor.attributes?.selectionGeneration ||
                resourceKind(descriptor, initial = false) == ResourceKind.ATTRIBUTED && !supportsAttributed)

    fun resourceKind(descriptor: BoundedCoveragePresentation?, initial: Boolean,
        completedAttributed: Boolean = false): ResourceKind {
        if (descriptor == null) return ResourceKind.PENDING
        if (descriptor.mode != CoveragePresentationMode.PLANAR_PATCHES &&
            descriptor.mode != CoveragePresentationMode.DIRECTIONAL_BALLS) return ResourceKind.POINTS
        val shape = descriptor.shapes
        val attributes = descriptor.attributes
        val expectedShape = if (descriptor.mode == CoveragePresentationMode.PLANAR_PATCHES)
            RendererAttributeCodec.PATCHES else RendererAttributeCodec.BALLS
        if (shape == null || attributes == null || (!shape.available && !completedAttributed) ||
            shape.header.selectionGeneration != attributes.selectionGeneration ||
            !shape.header.sameSelection(attributes) || attributes.shape != expectedShape ||
            attributes.total > descriptor.count) {
            return if (initial) ResourceKind.POINTS else ResourceKind.PENDING
        }
        // Zero shapes is a qualified selected point fallback with real upload bytes.
        return ResourceKind.ATTRIBUTED
    }

    fun wholeUploadComplete(descriptor: BoundedCoveragePresentation, ordinaryComplete: Boolean,
        attributedResource: Boolean, initial: Boolean = false,
        completedAttributed: Boolean = false): Boolean {
        val kind = resourceKind(descriptor, initial, completedAttributed)
        return ordinaryComplete && kind != ResourceKind.PENDING &&
            (kind != ResourceKind.ATTRIBUTED || attributedResource)
    }
}

/** Slot identity is occupied until exact detach/resource drain, even without a role. */
internal object CoverageSlotAdmission {
    fun canClearCancelledCandidate(token: CoverageResourceToken, currentSlotToken: CoverageResourceToken?,
        roleCancelled: Boolean, bindingWasAbsent: Boolean, factory: CoverageRendererResourceFactory,
        ledger: CoverageRendererAllocationLedger, hasShapeReservation: Boolean): Boolean =
        currentSlotToken == token && roleCancelled && bindingWasAbsent &&
            !factory.ownsToken(token) && !ledger.hasResourceOwnership(token) && !hasShapeReservation

    fun canStage(existing: CoverageResourceToken?, requested: CoverageResourceToken,
        retiring: Boolean): Boolean = !retiring && (existing == null || existing == requested)
}

/** One charged frozen source, keyed by its exact physical owner through drain. */
internal class CoverageFrozenBackingReservation(private val telemetry: RendererTelemetry) {
    var token: CoverageResourceToken? = null
        private set
    var identity: Any? = null
        private set
    private var shape: PreparedCoverageShapeReference? = null
    var bytes: Int = 0
        private set

    fun reserve(descriptor: BoundedCoveragePresentation, requested: CoverageResourceToken): Boolean = synchronized(telemetry) {
        if (token != null) return@synchronized token == requested &&
            identity === descriptor.backingIdentity && shape === descriptor.shapes
        val required = descriptor.ownedStorageBytes() + (descriptor.shapes?.portableSourceBytes ?: 0L)
        if (required > Int.MAX_VALUE || telemetry.ownedBufferBytesSnapshot().toLong() + required >
            CoverageRendererLimits.COMBINED_RENDERER_OWNED_LIMIT_BYTES) return@synchronized false
        telemetry.setOwnedBufferBytes(OWNER, required.toInt())
        token = requested; identity = descriptor.backingIdentity; shape = descriptor.shapes; bytes = required.toInt()
        true
    }

    fun release(expected: CoverageResourceToken? = token): Boolean = synchronized(telemetry) {
        if (token != expected) return@synchronized false
        telemetry.removeOwner(OWNER)
        token = null; identity = null; shape = null; bytes = 0
        true
    }
    private companion object { const val OWNER = "coverage-frozen-display-backing" }
}

/** Common production destruction gate: callbacks notify only after their state settles. */
internal class CoverageResourceDrainCompletion(
    private val isDrained: () -> Boolean,
    private val complete: () -> Unit,
) {
    private var requested = false
    private var completed = false
    fun requestDestroy() { requested = true; onStateSettled() }
    fun onStateSettled() {
        if (!requested || completed || !isDrained()) return
        completed = true
        complete()
    }
}
