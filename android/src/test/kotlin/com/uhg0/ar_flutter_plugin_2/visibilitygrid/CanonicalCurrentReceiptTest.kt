package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalCurrentReceiptTest {
    @Test
    fun `active current rejects changed or unrelated replay before another reservation`() {
        val directory = Files.createTempDirectory("canonical-surface-current-pending").toFile()
        try {
            val group = SurfaceGroup("current-pending")
            val budget = CountingBudget()
            val owner = (SurfaceOwnership.open(group, directory) as SurfaceOwnershipOpenResult.Opened).ownership
            val id = (owner.apply(SurfaceOwnershipCommand("seed", listOf(candidate(0)))) as SurfaceOwnershipResult.Accepted).owners.single().id
            owner.transact(CanonicalTransactionCommand(
                "current", CanonicalOperation.RELOCATION, 0, 0, listOf(id),
                listOf(CanonicalTarget(id, Voxel(1, 0, 0), 0, 0, 192)),
            ))
            owner.close()
            CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
            val plan = (CanonicalActivation.prepare(group, directory, budget) as CanonicalActivationPreparation.Prepared).plan
            val activation = SurfaceOwnership.activateV6(group, directory, budget, plan)
            assertTrue("activation=$activation", activation is CanonicalActivationResult.Active)
            val reservationsAfterActivation = budget.reservations
            val receipt = plan.current as CanonicalActivationCurrent.Receipt
            val changed = CanonicalActivation.Plan(plan.legacySourceHash, plan.siblingCut,
                CanonicalActivationCurrent.Receipt(
                    receipt.identity.copy(canonicalHash = CanonicalReceiptBytes(ByteArray(32) { 3 })), receipt.source,
                ), plan.receipt)
            val unrelated = CanonicalActivation.Plan(plan.legacySourceHash, plan.siblingCut,
                CanonicalActivationCurrent.Receipt(
                    receipt.identity.copy(commandHash = CanonicalReceiptBytes(ByteArray(32) { 4 })), receipt.source,
                ), plan.receipt)

            assertEquals(CanonicalActivationSelectorRefusal.CHANGED_CURRENT,
                (SurfaceOwnership.activateV6(group, directory, budget, changed) as CanonicalActivationResult.Refused).reason)
            assertEquals(CanonicalActivationSelectorRefusal.CURRENT_PENDING,
                (SurfaceOwnership.activateV6(group, directory, budget, unrelated) as CanonicalActivationResult.Refused).reason)
            assertEquals(reservationsAfterActivation, budget.reservations)
        } finally { directory.deleteRecursively() }
    }

    private fun candidate(x: Int) = SurfaceCandidate(null, Voxel(x, 0, 0), 0, 0, 192)
    private class CountingBudget : CanonicalStorageBudget {
        var reservations = 0
        override fun reserve(bytes: Long): Any = bytes.also { reservations++; require(it >= 0) }
        override fun reserveCandidateExclusive(staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long) =
            CanonicalCandidateReservation.QuotaRefused
        override fun commit(token: Any, actualBytes: Long) = Unit
        override fun release(token: Any) = Unit
        override fun allocationUnitBytes(path: File) = 4_096L
    }
}
