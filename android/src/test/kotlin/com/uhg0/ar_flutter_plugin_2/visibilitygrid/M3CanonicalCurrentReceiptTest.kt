package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CanonicalCurrentReceiptTest {
    @Test
    fun `active current rejects changed or unrelated replay before another reservation`() {
        val directory = Files.createTempDirectory("m3-current-pending").toFile()
        try {
            val group = M3SurfaceGroup("current-pending")
            val budget = CountingBudget()
            val owner = (M3SurfaceOwnership.open(group, directory) as M3SurfaceOwnershipOpenResult.Opened).ownership
            val id = (owner.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0)))) as M3SurfaceOwnershipResult.Accepted).owners.single().id
            owner.transact(M3CanonicalTransactionCommand(
                "current", M3CanonicalOperation.RELOCATION, 0, 0, listOf(id),
                listOf(M3CanonicalTarget(id, M3Voxel(1, 0, 0), 0, 0, 192)),
            ))
            owner.close()
            M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
            val plan = (M3CanonicalActivation.prepare(group, directory, budget) as M3CanonicalActivationPreparation.Prepared).plan
            val activation = M3SurfaceOwnership.activateV6(group, directory, budget, plan)
            assertTrue("activation=$activation", activation is M3CanonicalActivationResult.Active)
            val reservationsAfterActivation = budget.reservations
            val receipt = plan.current as M3CanonicalActivationCurrent.Receipt
            val changed = plan.copy(current = M3CanonicalActivationCurrent.Receipt(
                receipt.identity.copy(canonicalHash = M3CanonicalReceiptBytes(ByteArray(32) { 3 })), receipt.source,
            ))
            val unrelated = plan.copy(current = M3CanonicalActivationCurrent.Receipt(
                receipt.identity.copy(commandHash = M3CanonicalReceiptBytes(ByteArray(32) { 4 })), receipt.source,
            ))

            assertEquals(M3CanonicalActivationSelectorRefusal.CHANGED_CURRENT,
                (M3SurfaceOwnership.activateV6(group, directory, budget, changed) as M3CanonicalActivationResult.Refused).reason)
            assertEquals(M3CanonicalActivationSelectorRefusal.CURRENT_PENDING,
                (M3SurfaceOwnership.activateV6(group, directory, budget, unrelated) as M3CanonicalActivationResult.Refused).reason)
            assertEquals(reservationsAfterActivation, budget.reservations)
        } finally { directory.deleteRecursively() }
    }

    private fun candidate(x: Int) = M3SurfaceCandidate(null, M3Voxel(x, 0, 0), 0, 0, 192)
    private class CountingBudget : M3CanonicalStorageBudget {
        var reservations = 0
        override fun reserve(bytes: Long): Any = bytes.also { reservations++; require(it >= 0) }
        override fun reserveCandidateExclusive(staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long) =
            M3CanonicalCandidateReservation.QuotaRefused
        override fun commit(token: Any, actualBytes: Long) = Unit
        override fun release(token: Any) = Unit
        override fun allocationUnitBytes(path: File) = 4_096L
    }
}
