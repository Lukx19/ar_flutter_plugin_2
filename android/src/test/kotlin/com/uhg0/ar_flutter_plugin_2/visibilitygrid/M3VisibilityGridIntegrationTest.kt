package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks Option A's M1-ACK seeded CREATE cut without a Flutter payload seam. */
class M3VisibilityGridIntegrationTest {
    @Test
    fun `seeded M1 empty baseline makes first atomic CREATE adjacent and lineage-stable`() {
        val baseline = M3CommittedEmptyBaseline("binding:group:7:9", "group", 1, 1, 1)
        val opened = M3SurfaceOwnership.inMemory(
            M3SurfaceGroup("group"),
            M3SurfaceOwnershipConfiguration(seededEmptyBaseline = baseline),
        ) as M3SurfaceOwnershipOpenResult.Opened
        val result = opened.ownership.transact(
            M3CanonicalTransactionCommand(
                commandId = "create-after-m1-ack",
                kind = M3CanonicalOperation.CREATE,
                expectedGeometryRevision = 1,
                expectedLineageRevision = 1,
                sourceIds = emptyList(),
                targets = listOf(M3CanonicalTarget(voxel = M3Voxel(1, 2, 3), normalOctX = 1, normalOctY = 1, normalConfidence = 200)),
            ),
        ) as M3CanonicalTransactionResult.Accepted
        assertEquals(2, result.receipt.geometryRevision)
        assertEquals(1, result.receipt.lineageRevision)
        assertTrue(result.receipt.lineageEdges.isEmpty())
        assertEquals(1, result.targets.size)
    }

    @Test
    fun `seed identity is durable before CREATE and mismatched reopen fails closed`() {
        val directory = Files.createTempDirectory("m3-seeded-baseline").toFile()
        try {
            val baseline = M3CommittedEmptyBaseline("binding-a", "group", 1, 1, 1)
            val configuration = M3SurfaceOwnershipConfiguration(seededEmptyBaseline = baseline)
            val first = M3SurfaceOwnership.open(M3SurfaceGroup("group"), directory, configuration)
                as M3SurfaceOwnershipOpenResult.Opened
            first.ownership.close()

            val reopened = M3SurfaceOwnership.open(M3SurfaceGroup("group"), directory, configuration)
            assertTrue(reopened is M3SurfaceOwnershipOpenResult.Opened)
            (reopened as M3SurfaceOwnershipOpenResult.Opened).ownership.close()

            val mismatch = baseline.copy(bindingIdentity = "binding-b")
            val refused = M3SurfaceOwnership.open(
                M3SurfaceGroup("group"),
                directory,
                M3SurfaceOwnershipConfiguration(seededEmptyBaseline = mismatch),
            ) as M3SurfaceOwnershipOpenResult.Refused
            assertEquals(M3SurfaceOwnershipRestoreRefusal.FORK, refused.reason)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `committed CREATE reopens with byte exact current receipt`() {
        val directory = Files.createTempDirectory("m3-create-current-delta").toFile()
        try {
            val baseline = M3CommittedEmptyBaseline("binding", "group", 1, 1, 1)
            val configuration = M3SurfaceOwnershipConfiguration(seededEmptyBaseline = baseline)
            val first = M3SurfaceOwnership.open(M3SurfaceGroup("group"), directory, configuration)
                as M3SurfaceOwnershipOpenResult.Opened
            val accepted = first.ownership.transact(
                M3CanonicalTransactionCommand(
                    "create", M3CanonicalOperation.CREATE, 1, 1, emptyList(),
                    listOf(
                        M3CanonicalTarget(
                            voxel = M3Voxel(3, 2, 1),
                            normalOctX = 1,
                            normalOctY = 1,
                            normalConfidence = 200,
                        ),
                    ),
                ),
            ) as M3CanonicalTransactionResult.Accepted
            first.ownership.close()

            val reopened = M3SurfaceOwnership.open(M3SurfaceGroup("group"), directory, configuration)
                as M3SurfaceOwnershipOpenResult.Opened
            val restored = requireNotNull(reopened.ownership.currentCanonicalTransaction())
            assertEquals(2, restored.receipt.geometryRevision)
            assertEquals(1, restored.receipt.lineageRevision)
            assertArrayEquals(
                accepted.receipt.canonicalBytes.toByteArray(),
                restored.receipt.canonicalBytes.toByteArray(),
            )
            reopened.ownership.close()
        } finally {
            directory.deleteRecursively()
        }
    }
}
