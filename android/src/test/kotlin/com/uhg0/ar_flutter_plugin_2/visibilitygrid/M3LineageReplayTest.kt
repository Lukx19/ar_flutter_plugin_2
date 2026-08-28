package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class M3LineageReplayTest {
    @Test
    fun `duplicate replay is exact changed replay conflicts and receipt survives reopen`() {
        val directory = Files.createTempDirectory("m3-lineage-replay").toFile()
        val group = M3SurfaceGroup("replay")
        val owner = opened(M3SurfaceOwnership.open(group, directory))
        val source = seed(owner)
        val command = relocate("same", source, 10)
        val committed = accepted(owner.transact(command))
        assertEquals(committed, accepted(owner.transact(command)))
        assertEquals(M3CanonicalTransactionRefusal.IDENTITY_CONFLICT, refused(owner.transact(relocate("same", source, 11))).reason)
        owner.close()

        val reopened = opened(M3SurfaceOwnership.open(group, directory))
        val replay = accepted(reopened.transact(command))
        assertEquals(committed, replay)
        assertEquals(committed.receipt.lineageEdges, replay.receipt.lineageEdges)
        assertEquals(1, replay.receipt.lineageEdges.size)
    }

    @Test
    fun `reservation and private snapshot fault cuts expose complete prior cuts while committed cut remains complete`() {
        val cases = listOf(
            M3SurfaceOwnershipFault.AFTER_RESERVATION_FLUSH to true,
            M3SurfaceOwnershipFault.AFTER_PRIVATE_MUTATION to false,
            M3SurfaceOwnershipFault.BEFORE_SNAPSHOT_FLUSH to true,
            M3SurfaceOwnershipFault.AFTER_SNAPSHOT_FLUSH to false,
        )
        cases.forEachIndexed { index, (fault, allocates) ->
            val directory = Files.createTempDirectory("m3-fault-$index").toFile()
            val group = M3SurfaceGroup("fault-$index")
            val seeder = opened(M3SurfaceOwnership.open(group, directory))
            val source = seed(seeder); seeder.close()
            val owner = opened(M3SurfaceOwnership.open(group, directory, fault = fault))
            val command = if (allocates) M3CanonicalTransactionCommand("replace", M3CanonicalOperation.REPLACEMENT, 0, 0,
                listOf(source), listOf(M3CanonicalTarget(voxel = M3Voxel(10, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192)))
            else relocate("relocate", source, 10)
            val result = owner.transact(command)
            if (fault == M3SurfaceOwnershipFault.AFTER_SNAPSHOT_FLUSH) {
                assertEquals(1, accepted(result).receipt.geometryRevision)
            } else {
                val refusal = refused(result)
                assertEquals(M3CanonicalTransactionRefusal.DURABILITY_FAILURE, refusal.reason)
                assertEquals(0, refusal.receipt.geometryRevision)
            }
            owner.close()
            val reopened = opened(M3SurfaceOwnership.open(group, directory))
            if (fault == M3SurfaceOwnershipFault.AFTER_SNAPSHOT_FLUSH) {
                assertEquals(result, reopened.transact(command))
            } else {
                val retry = accepted(reopened.transact(command))
                assertEquals(1, retry.receipt.geometryRevision)
                if (allocates) assertNotEquals(2L, retry.targets.single().id.value)
            }
        }
    }

    @Test
    fun `receipt and lineage journal bounds refuse deterministically`() {
        val receiptOwner = opened(M3SurfaceOwnership.inMemory(M3SurfaceGroup("receipt"), M3SurfaceOwnershipConfiguration(transactionCapacity = 1)))
        val first = seed(receiptOwner)
        accepted(receiptOwner.transact(relocate("one", first, 1)))
        val second = refused(receiptOwner.transact(relocate("two", first, 2, revision = 1)))
        assertEquals(M3CanonicalTransactionRefusal.JOURNAL_EXHAUSTED, second.reason)
        assertEquals(1, second.receipt.geometryRevision)
    }

    private fun seed(owner: M3SurfaceOwnership): M3SurfaceId =
        (owner.apply(M3SurfaceOwnershipCommand("seed", listOf(M3SurfaceCandidate(voxel = M3Voxel(0, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192)))) as M3SurfaceOwnershipResult.Accepted).owners.single().id
    private fun relocate(id: String, source: M3SurfaceId, x: Int, revision: Long = 0) = M3CanonicalTransactionCommand(
        id, M3CanonicalOperation.RELOCATION, revision, revision, listOf(source),
        listOf(M3CanonicalTarget(source, M3Voxel(x, 0, 0), 0, 0, 192)),
    )
    private fun opened(result: M3SurfaceOwnershipOpenResult) = (result as M3SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: M3CanonicalTransactionResult) = result as M3CanonicalTransactionResult.Accepted
    private fun refused(result: M3CanonicalTransactionResult) = result as M3CanonicalTransactionResult.Refused
}
