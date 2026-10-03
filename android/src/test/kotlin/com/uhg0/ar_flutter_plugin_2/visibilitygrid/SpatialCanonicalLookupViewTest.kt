package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialCanonicalLookupViewTest {
    @Test
    fun `canonical id validation survives a rotated spatial cache miss`() {
        val root = Files.createTempDirectory("spatial-canonical-id-validation").toFile()
        val group = SurfaceGroup("c".repeat(32))
        val coordinator = StorageBudgetCoordinatorV2(
            File(root, "visibility-grid-canonical-surface-runtime"),
            StorageBudgetPolicyV2(64L * 1024L * 1024L, 0),
            JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
        )
        val resources = CanonicalRuntimeResources.openLive(root, group, coordinator)
        val identity = identityTransform()
        val reverse = doubleArrayOf(
            -1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, -1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        val groupFrame = VisibilityGroupFrame.copyOf(identity, identity, 100_000, 100)
        val intrinsics = VisibilityCameraIntrinsics(640, 480, 500.0, 500.0, 320.0, 240.0)
        try {
            assertTrue(
                resources.openInitial(committedEmptyBaseline("spatial-id", group.value, 1, 1, 1))
                    is SurfaceOwnershipOpenResult.Opened,
            )
            val emptyCut = requireNotNull(resources.owner().activationState()).cut
            val emptyCertificate = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(emptyCut.geometryRevision, emptyCut.lineageRevision, 0, 0, 0, 0),
            ) { view ->
                assertTrue(view.supportsEmptyBlockSkipping)
                view.isKnownEmptyBlock(0, 0, -8)
            } as BoundedCanonicalLookupResult.Completed
            assertEquals(true, emptyCertificate.value)
            val created = resources.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "spatial-id-create", 1, 1,
                    listOf(DepthEvidenceChange.Create(CanonicalTarget(
                        voxel = Voxel(0, 0, -30), normalOctX = 0, normalOctY = 0, normalConfidence = 200,
                    ))),
                ),
            ) as CanonicalMutationPreparation.Prepared
            assertTrue(resources.commitAdjacent(created.mutation) is CanonicalAdjacentCommitResult.Committed)
            val committed = requireNotNull(resources.owner().activationState())
            val receipt = committed.current as CanonicalActivationCurrent.Receipt
            assertTrue(resources.owner().acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(receipt.identity.commandHash, committed.cut.geometryRevision, committed.cut.lineageRevision),
            ) is CanonicalAcknowledgementResult.Acknowledged)

            assertTrue(resources.updateSpatialWindow(window(1, identity, groupFrame, intrinsics)))
            assertTrue(resources.updateSpatialWindow(window(2, reverse, groupFrame, intrinsics)))

            val cut = requireNotNull(resources.owner().activationState()).cut
            val indexedCertificate = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 0, 0, 0, 0),
            ) { view ->
                // Rotation cannot hide an occupied canonical child from the certificate.
                assertTrue(!view.isKnownEmptyBlock(0, 0, -8))
                view.isKnownEmptyBlock(1, 0, -15, 2)
            } as BoundedCanonicalLookupResult.Completed
            assertEquals(true, indexedCertificate.value)
            val lookup = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 1, 0, 0, 0),
            ) { view -> view.findSurfaceById(SurfaceId(1)) }
            val completed = lookup as BoundedCanonicalLookupResult.Completed
            assertEquals(SurfaceId(1), requireNotNull(completed.value).id)
            assertEquals(BoundedCanonicalLookupReceipt(1, 0, 0, 0, false), completed.receipt)
        } finally {
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    private fun window(
        sequence: Long,
        pose: DoubleArray,
        groupFrame: VisibilityGroupFrame,
        intrinsics: VisibilityCameraIntrinsics,
    ) = DepthEvidenceBatch(
        sequence = sequence,
        sourceTimestampNs = sequence * 1_000_000_000L,
        groupFrame = groupFrame,
        groupFromCameraGl = pose.toList(),
        intrinsics = intrinsics,
        samples = emptyList(),
        sourceRejectedSamples = 0,
    )

    private fun identityTransform() = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }
}
