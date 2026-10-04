package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCanonicalRuntimeTest {
    @Test fun `live mutations retain exact current and renderer rows without canonical files`() {
        val root = Files.createTempDirectory("live-canonical-runtime").toFile()
        val group = SurfaceGroup("a".repeat(32))
        val coordinator = StorageBudgetCoordinatorV2(File(root, "visibility-grid-canonical-surface-runtime"),
            StorageBudgetPolicyV2(64L * 1024 * 1024, 0), JvmDescriptorFilesystemV2())
        val runtime = CanonicalRuntimeResources.openLive(root, group, coordinator,
            SurfaceOwnershipConfiguration(surfaceCapacity = 100, lineageCapacity = 200))
        try {
            assertTrue(runtime.openInitial(committedEmptyBaseline("binding", group.value, 1, 1, 1)) is SurfaceOwnershipOpenResult.Opened)
            repeat(3) { index ->
                val cut = requireNotNull(runtime.owner().activationState()).cut
                val plan = runtime.prepareEvidenceBatch(CanonicalEvidenceBatchCommand("live-create-$index",
                    cut.geometryRevision, cut.lineageRevision,
                    listOf(DepthEvidenceChange.Create(CanonicalTarget(voxel = Voxel(index, 0, -30),
                        normalOctX = 1, normalOctY = 1, normalConfidence = 200))))) as CanonicalMutationPreparation.Prepared
                assertTrue(runtime.commitAdjacent(plan.mutation) is CanonicalAdjacentCommitResult.Committed)
                val state = requireNotNull(runtime.owner().activationState())
                val current = state.current as CanonicalActivationCurrent.Receipt
                val page = requireNotNull(runtime.readRendererPage(0))
                assertEquals(index + 1, page.rows.size)
                val lookup = runtime.withBoundedCurrent(BoundedCanonicalLookupRequest(
                    state.cut.geometryRevision, state.cut.lineageRevision, 1, 1, 0, 0)) {
                    it.findSurfaceAt(Voxel(index, 0, -30))
                } as BoundedCanonicalLookupResult.Completed
                assertTrue(lookup.value != null)
                assertEquals(0, lookup.receipt.pageReads)
                assertEquals(0L, lookup.receipt.bytesRead)
                assertTrue(runtime.owner().acknowledgeCanonicalCurrent(CanonicalAcknowledgement(
                    current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision)) is CanonicalAcknowledgementResult.Acknowledged)
            }
            assertEquals(0, root.walkTopDown().count { it.isFile })
            assertTrue(requireNotNull(runtime.completeCurrentLeaseReceipt()).retainedTotalBytes > 0)
            runtime.close()
            val fresh = CanonicalRuntimeResources.openLive(root, group, coordinator,
                SurfaceOwnershipConfiguration(surfaceCapacity = 100, lineageCapacity = 200))
            try {
                assertTrue(fresh.reopen() is SurfaceOwnershipOpenResult.Refused)
                assertTrue(fresh.openInitial(committedEmptyBaseline("replacement", group.value, 1, 1, 1)) is SurfaceOwnershipOpenResult.Opened)
                assertEquals(0, requireNotNull(fresh.owner().activationState()).cut.liveSurfaceCount)
            } finally { fresh.close() }
        } finally { runtime.close(); coordinator.close(); root.deleteRecursively() }
    }
}
