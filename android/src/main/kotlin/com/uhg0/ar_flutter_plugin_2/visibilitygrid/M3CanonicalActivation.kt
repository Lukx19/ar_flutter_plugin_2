package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.File
import java.io.OutputStream

/**
 * Read-only bridge between a validated legacy authority and its inactive v6 sibling.  It owns the
 * selection rules so the later activation step receives one immutable fact, not a second chance to
 * reinterpret receipt history.
 */
internal object M3CanonicalActivation {
    fun prepare(
        group: M3SurfaceGroup,
        directory: File,
        budget: M3CanonicalStorageBudget,
        configuration: M3SurfaceOwnershipConfiguration = M3SurfaceOwnershipConfiguration(),
    ): M3CanonicalActivationPreparation = try {
        val legacy = M3SurfaceOwnershipLegacyCodec.readValidated(group, directory, configuration)
        val opened = M3CompactCanonicalStore.openV6(group, directory, budget, configuration)
        val sibling = (opened as? M3CompactCanonicalOpenResult.Opened)?.store
            ?: return M3CanonicalActivationPreparation.Refused(M3CanonicalActivationRefusal.SIBLING_INVALID)
        val cut = sibling.use { it.cut }
        if (!matchesLegacy(legacy, cut))
            return M3CanonicalActivationPreparation.Refused(M3CanonicalActivationRefusal.SIBLING_MISMATCH)

        var selected: M3LegacyCanonicalReceipt? = null
        var scanned = 0L
        var matching = 0L
        var refusal: M3CanonicalActivationRefusal? = null
        legacy.visitCanonicalReceipts { receipt ->
            scanned++
            if (receipt.geometryRevision != legacy.geometryRevision ||
                receipt.lineageRevision != legacy.lineageRevision
            ) return@visitCanonicalReceipts
            if (receipt.nextHighWater != legacy.nextHighWater ||
                receipt.liveSurfaceCount != legacy.resident.rows
            ) {
                refusal = M3CanonicalActivationRefusal.INCOMPATIBLE_FINAL_CUT
                return@visitCanonicalReceipts
            }
            matching++
            val prior = selected
            if (prior == null) {
                selected = receipt
                return@visitCanonicalReceipts
            }
            refusal = when {
                prior.commandHash == receipt.commandHash &&
                    prior.commandFingerprint == receipt.commandFingerprint &&
                    prior.canonicalHash == receipt.canonicalHash &&
                    prior.canonicalLength == receipt.canonicalLength -> null
                prior.commandHash == receipt.commandHash &&
                    prior.commandFingerprint == receipt.commandFingerprint ->
                    M3CanonicalActivationRefusal.CHANGED_RECEIPT
                prior.commandHash == receipt.commandHash -> M3CanonicalActivationRefusal.FORKED_IDENTITY
                prior.canonicalHash == receipt.canonicalHash -> M3CanonicalActivationRefusal.FORKED_IDENTITY
                else -> M3CanonicalActivationRefusal.AMBIGUOUS_CURRENT
            }
        }
        refusal?.let { return M3CanonicalActivationPreparation.Refused(it) }
        val current = selected?.let {
            M3CanonicalActivationCurrent.Receipt(
                M3CanonicalCurrentIdentity(
                    it.commandHash,
                    it.commandFingerprint,
                    it.canonicalLength,
                    it.canonicalHash,
                ),
                M3CanonicalCurrentSource(it),
            )
        } ?: M3CanonicalActivationCurrent.None
        M3CanonicalActivationPreparation.Prepared(
            M3CanonicalActivationPlan(
                M3CanonicalReceiptBytes(legacy.sourceHash), cut, current,
                M3CanonicalActivationPreparationReceipt(scanned, matching, 512),
            )
        )
    } catch (_: M3RestoreFailure) {
        M3CanonicalActivationPreparation.Refused(M3CanonicalActivationRefusal.LEGACY_INVALID)
    } catch (_: Exception) {
        M3CanonicalActivationPreparation.Refused(M3CanonicalActivationRefusal.LEGACY_INVALID)
    }

    private fun matchesLegacy(legacy: M3LegacyCanonicalState, cut: M3CompactCanonicalCut) =
        cut.group == legacy.group &&
            cut.profile == M3CompactCanonicalStore.PROFILE &&
            cut.geometryRevision == legacy.geometryRevision &&
            cut.lineageRevision == legacy.lineageRevision &&
            cut.nextSurfaceIdHighWater == legacy.nextHighWater &&
            cut.liveSurfaceCount == legacy.resident.rows &&
            cut.sourceCount == legacy.sourceCount &&
            cut.supportCount == legacy.supportCount &&
            cut.lineageCount == legacy.lineageCount &&
            cut.seededEmptyBaseline == legacy.baseline &&
            cut.sourceHash == M3CanonicalReceiptBytes(legacy.sourceHash)
}

internal data class M3CanonicalActivationPlan(
    val legacySourceHash: M3CanonicalReceiptBytes,
    val siblingCut: M3CompactCanonicalCut,
    val current: M3CanonicalActivationCurrent,
    val receipt: M3CanonicalActivationPreparationReceipt,
)

internal data class M3CanonicalActivationPreparationReceipt(
    val scannedReceipts: Long,
    val finalCutReceipts: Long,
    /** One fixed descriptor and no history or receipt body. */
    val retainedBytes: Long,
)

internal sealed interface M3CanonicalActivationPreparation {
    data class Prepared(val plan: M3CanonicalActivationPlan) : M3CanonicalActivationPreparation
    data class Refused(val reason: M3CanonicalActivationRefusal) : M3CanonicalActivationPreparation
}

internal sealed interface M3CanonicalActivationCurrent {
    data object None : M3CanonicalActivationCurrent
    data class Receipt(
        val identity: M3CanonicalCurrentIdentity,
        val source: M3CanonicalCurrentSource,
    ) : M3CanonicalActivationCurrent
}

internal data class M3CanonicalCurrentIdentity(
    val commandHash: M3CanonicalReceiptBytes,
    val commandFingerprint: M3CanonicalReceiptBytes,
    val canonicalLength: Long,
    val canonicalHash: M3CanonicalReceiptBytes,
)

/** A source can copy one selected immutable receipt, but never exposes its backing byte array. */
internal class M3CanonicalCurrentSource internal constructor(
    private val receipt: M3LegacyCanonicalReceipt,
) {
    fun writeTo(output: OutputStream) = receipt.writeCanonicalTo(output)
}

internal enum class M3CanonicalActivationRefusal {
    LEGACY_INVALID,
    SIBLING_INVALID,
    SIBLING_MISMATCH,
    INCOMPATIBLE_FINAL_CUT,
    AMBIGUOUS_CURRENT,
    FORKED_IDENTITY,
    CHANGED_RECEIPT,
}
