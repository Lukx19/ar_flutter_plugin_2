package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CanonicalActivationPreparationTest {
    @Test
    fun `preparation binds one exact final receipt as an immutable streaming source`() {
        val directory = Files.createTempDirectory("m3-activation-current").toFile()
        try {
            val group = M3SurfaceGroup("activation-current")
            val owner = opened(M3SurfaceOwnership.open(group, directory))
            val id = accepted(owner.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0))))).owners.single().id
            val committed = accepted(owner.transact(relocate("final", id, 1)))
            owner.close()
            migrated(group, directory)

            val prepared = prepared(M3CanonicalActivation.prepare(group, directory, budget()))
            assertEquals(group, prepared.siblingCut.group)
            assertEquals(committed.receipt.geometryRevision, prepared.siblingCut.geometryRevision)
            assertEquals(committed.receipt.lineageRevision, prepared.siblingCut.lineageRevision)
            assertEquals(committed.receipt.nextSurfaceIdHighWater, prepared.siblingCut.nextSurfaceIdHighWater)
            assertEquals(1, prepared.receipt.finalCutReceipts)
            assertTrue(prepared.receipt.retainedBytes <= 1_024L)
            val current = prepared.current as M3CanonicalActivationCurrent.Receipt
            val copied = ByteArrayOutputStream().also(current.source::writeTo).toByteArray()
            assertArrayEquals(committed.receipt.canonicalBytes.toByteArray(), copied)
            assertEquals(copied.size.toLong(), current.identity.canonicalLength)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `preparation returns current none only when the final cut has no receipt`() {
        val directory = Files.createTempDirectory("m3-activation-none").toFile()
        try {
            val group = M3SurfaceGroup("activation-none")
            opened(M3SurfaceOwnership.open(group, directory)).close()
            migrated(group, directory)
            val prepared = prepared(M3CanonicalActivation.prepare(group, directory, budget()))
            assertEquals(M3CanonicalActivationCurrent.None, prepared.current)
            assertEquals(0L, prepared.receipt.finalCutReceipts)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `corrupt inactive sibling yields a typed refusal and leaves legacy authority readable`() {
        val directory = Files.createTempDirectory("m3-activation-corrupt").toFile()
        try {
            val group = M3SurfaceGroup("activation-corrupt")
            val owner = opened(M3SurfaceOwnership.open(group, directory))
            accepted(owner.apply(M3SurfaceOwnershipCommand("seed", listOf(candidate(0)))))
            owner.close()
            val migration = migrated(group, directory)
            assertTrue(File(migration.candidateDirectory, "root.v6").delete())

            val result = M3CanonicalActivation.prepare(group, directory, budget())
            assertEquals(
                M3CanonicalActivationRefusal.SIBLING_INVALID,
                (result as M3CanonicalActivationPreparation.Refused).reason,
            )
            assertTrue(M3SurfaceOwnershipLegacyCodec.readValidated(
                group, directory, M3SurfaceOwnershipConfiguration(),
            ).resident.rows == 1)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun migrated(group: M3SurfaceGroup, directory: File) =
        M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget())
            as M3CompactCanonicalMigrationResult.Prepared

    private fun budget() = object : M3CanonicalStorageBudget {
        override fun reserve(bytes: Long): Any = bytes
        override fun reserveCandidateExclusive(
            staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long,
        ) = M3CanonicalCandidateReservation.QuotaRefused
        override fun commit(token: Any, actualBytes: Long) = Unit
        override fun release(token: Any) = Unit
        override fun allocationUnitBytes(path: File) = 4_096L
    }

    private fun candidate(x: Int) = M3SurfaceCandidate(
        voxel = M3Voxel(x, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192,
    )
    private fun relocate(command: String, id: M3SurfaceId, x: Int) = M3CanonicalTransactionCommand(
        command, M3CanonicalOperation.RELOCATION, 0, 0, listOf(id),
        listOf(M3CanonicalTarget(id, M3Voxel(x, 0, 0), 0, 0, 192)),
    )
    private fun opened(result: M3SurfaceOwnershipOpenResult) =
        (result as M3SurfaceOwnershipOpenResult.Opened).ownership
    private fun accepted(result: M3SurfaceOwnershipResult) = result as M3SurfaceOwnershipResult.Accepted
    private fun accepted(result: M3CanonicalTransactionResult) = result as M3CanonicalTransactionResult.Accepted
    private fun prepared(result: M3CanonicalActivationPreparation) =
        (result as M3CanonicalActivationPreparation.Prepared).plan
}
