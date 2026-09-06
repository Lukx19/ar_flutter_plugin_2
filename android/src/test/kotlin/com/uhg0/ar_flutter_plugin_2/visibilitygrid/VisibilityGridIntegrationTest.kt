package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CommittedBaselineAuthority
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.ControlCodec
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.ControlOperation
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.ControlRequest
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CurrentDeltaReceiptV1
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.PacketCodec
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.StartRequestCodecV2
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.TransactionResponseProfileV1
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.TransactionChunkFrameV1
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.TransactionResponseCodecV1
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.Uuid
import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks Option A's BINDING-LIFECYCLE-ACK seeded CREATE cut without a Flutter payload seam. */
class VisibilityGridIntegrationTest {
    @Test
    fun `stable identity replacement may reuse the removed surface voxel`() {
        val projection = NativeRendererProjection(render = { _, _ -> })
        val ownership = rendererOwnership()
        val rebuild = rebuildCut(ownership, 4, 1)
        projection.beginRebuild(rebuild)
        projection.appendRebuildPage(rebuild.withUpserts(listOf(row(1, 0))))
        projection.finishRebuild(rebuild)

        val replacement = CommittedGeometryCut(
            ownership = ownership,
            transactionId = 5,
            baseGeometryRevision = 4,
            geometryRevision = 5,
            lineageRevision = 2,
            reset = false,
            upserts = listOf(row(2, 0)),
            removedSurfaceIds = longArrayOf(1),
        )

        assertEquals(RendererProjectionResult.Applied(1), projection.applyGeometry(replacement))
        projection.close()
    }

    @Test
    fun `renderer rebuild publishes one exact snapshot after all pages`() {
        val rendered = mutableListOf<com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot>()
        val projection = NativeRendererProjection(render = { snapshot, _ -> snapshot?.let(rendered::add) })
        val cut = rendererOwnership()

        val rebuild = rebuildCut(cut, 9, 3)
        projection.beginRebuild(rebuild)
        projection.appendRebuildPage(rebuild.withUpserts(listOf(row(1, 0), row(2, 1))))
        assertTrue(rendered.isEmpty())
        projection.appendRebuildPage(rebuild.withUpserts(listOf(row(3, 2))))
        assertTrue(rendered.isEmpty())
        projection.finishRebuild(rebuild)

        assertEquals(1, rendered.size)
        assertEquals(3, rendered.single().count)
        assertEquals(9L, rendered.single().update?.geometryRevision)
    }

    @Test
    fun `renderer single page rebuild publishes once and aborted epoch publishes nothing`() {
        val rendered = mutableListOf<com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot>()
        val projection = NativeRendererProjection(render = { snapshot, _ -> snapshot?.let(rendered::add) })
        val first = rendererOwnership()
        val replacement = first.copy(coverageEpoch = 2, operationGeneration = 2)

        val firstRebuild = rebuildCut(first, 4, 1)
        projection.beginRebuild(firstRebuild)
        projection.appendRebuildPage(firstRebuild.withUpserts(listOf(row(1, 0))))
        projection.abortRebuild()
        assertTrue(rendered.isEmpty())

        val replacementRebuild = rebuildCut(replacement, 5, 1)
        projection.beginRebuild(replacementRebuild)
        projection.appendRebuildPage(replacementRebuild.withUpserts(listOf(row(2, 0))))
        projection.finishRebuild(replacementRebuild)
        assertEquals(1, rendered.size)
        assertEquals(5L, rendered.single().update?.geometryRevision)
    }

    @Test
    fun `canonical integration consumes pinned primary after stronger opposing evidence`() {
        val kernel = FeatureFusionKernel()
        val first = kernel.accept(FeatureFusionBatch(1, 1, List(2) { normalEvidence(it, true) }))
            as FeatureFusionResult.Accepted
        val firstCandidate = (first.delta.single() as FeatureFusionChange.Upsert).candidate
        val original = firstCandidate.normalCandidates.single { it.face == FeatureNormalFace.PRIMARY }

        val later = kernel.accept(FeatureFusionBatch(2, 2, List(3) { normalEvidence(it + 10, false) }))
            as FeatureFusionResult.Accepted
        val laterCandidate = (later.delta.single() as FeatureFusionChange.Upsert).candidate
        val target = requireNotNull(laterCandidate.primaryCanonicalTarget())

        assertEquals(original.normalOctX, target.normalOctX)
        assertEquals(original.normalOctY, target.normalOctY)
        assertTrue(laterCandidate.normalCandidates.single { it.face == FeatureNormalFace.OPPOSING }.normalConfidence > target.normalConfidence)
    }

    @Test
    fun `real binding publishes adjacent CREATE with exact replay and same renderer cut`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-integration").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(messenger, 2106, CommittedBaselineAuthority(), postToMain = { it() })
        val rendered = mutableListOf<com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot>()
        val integration = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
            renderer = NativeRendererProjection(
                render = { snapshot, _ -> snapshot?.let(rendered::add) },
            ),
        )
        try {
            val stream = start(binding, messenger, 2106)
            assertEquals(2, exchange(messenger, 2106, stream, 1, 0, 0, 0).first.messageKind)
            assertEquals(4, exchange(messenger, 2106, stream, 2, 0, 0, 0).first.messageKind)
            assertEquals(0, exchange(messenger, 2106, stream, 3, 1, 1, 1).first.messageKind)
            val cut = requireNotNull(binding.currentObservationOwnership())
            assertEquals(100_000, cut.groupFrame.modelCapacity)
            integration.admitFeature(feature(cut, 10))
            val staged = integration.integrationReceipt()
            assertEquals("pendingAck", staged.status)
            assertEquals(2, staged.transactionId)
            assertEquals(2, staged.geometryRevision)
            assertEquals(1, staged.lineageRevision)
            assertEquals("CREATE", staged.canonicalOperation)
            assertTrue(staged.canonicalBytes > 0)
            assertEquals(2, rendered.size)
            assertEquals(2L, rendered.last().update?.geometryRevision)
            assertEquals(staged.rendererRows, rendered.last().count)

            val first = exchange(messenger, 2106, stream, 4, 1, 1, 1)
            val replay = exchange(messenger, 2106, stream, 4, 1, 1, 1)
            assertEquals(2, first.first.messageKind)
            assertArrayEquals(first.second, replay.second)
            assertEquals(3, exchange(messenger, 2106, stream, 5, 1, 1, 1).first.messageKind)
            assertEquals(4, exchange(messenger, 2106, stream, 6, 1, 1, 1).first.messageKind)
            assertEquals(0, exchange(messenger, 2106, stream, 7, 2, 2, 1).first.messageKind)
            repeat(100) {
                if (integration.integrationReceipt().status == "acknowledged") return@repeat
                Thread.sleep(5)
            }
            assertEquals("acknowledged", integration.integrationReceipt().status)
            assertTrue(integration.integrationReceipt()::class.java.declaredFields.none { it.type == ByteArray::class.java })
            assertEquals(null, integration.snapshot().lastReceipt)
        } finally {
            integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `real binding publishes one depth wall create through canonical renderer and v2`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-depth-integration").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val viewId = 2130
        val binding = VisibilityGridV2Binding(messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() })
        lateinit var depthKernel: DepthEvidenceKernel
        val renderedCuts = mutableListOf<CommittedGeometryCut>()
        val integration = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
            depthKernelFactory = {
                DepthEvidenceKernel(DepthEvidenceConfiguration(occupiedEvidenceToShow = 1)).also { depthKernel = it }
            },
            renderer = object : CommittedRendererProjection {
                override fun applyGeometry(cut: CommittedGeometryCut): RendererProjectionResult {
                    renderedCuts += cut
                    return RendererProjectionResult.Applied(cut.upserts.size)
                }
            },
        )
        try {
            val stream = start(binding, messenger, viewId)
            exchange(messenger, viewId, stream, 1, 0, 0, 0)
            exchange(messenger, viewId, stream, 2, 0, 0, 0)
            exchange(messenger, viewId, stream, 3, 1, 1, 1)
            val cut = requireNotNull(binding.currentObservationOwnership())

            integration.admitDepth(depth(cut, 10))

            val staged = integration.integrationReceipt()
            assertEquals("pendingAck", staged.status)
            assertEquals(2, staged.transactionId)
            assertEquals(2, staged.geometryRevision)
            assertEquals(1, staged.lineageRevision)
            assertEquals("DEPTH_BATCH", staged.canonicalOperation)
            assertEquals(1, renderedCuts.size)
            val rendererCut = renderedCuts.last()
            assertEquals(staged.transactionId, rendererCut.transactionId)
            assertEquals(staged.geometryRevision, rendererCut.geometryRevision)
            assertEquals(staged.lineageRevision, rendererCut.lineageRevision)
            assertEquals(cut, rendererCut.ownership)
            assertEquals(1, rendererCut.upserts.size)
            assertTrue(rendererCut.removedSurfaceIds.isEmpty())
            assertEquals(1L, integration.snapshot().admittedDepths)
            assertEquals(1, depthKernel.resourceReceipt().residentEvidenceRows)

            assertEquals(2, exchange(messenger, viewId, stream, 4, 1, 1, 1).first.messageKind)
            exchange(messenger, viewId, stream, 5, 1, 1, 1)
            exchange(messenger, viewId, stream, 6, 1, 1, 1)
            exchange(messenger, viewId, stream, 7, 2, 2, 1)
            await { integration.integrationReceipt().status == "acknowledged" }
        } finally {
            integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `depth queue boundary retains synchronous early ACK and recovers without reapplying evidence`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-depth-queue-boundary").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val viewId = 2135
        val binding = VisibilityGridV2Binding(messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() })
        lateinit var depthKernel: DepthEvidenceKernel
        lateinit var featureKernel: FeatureFusionKernel
        val queuedReceipts = mutableListOf<CurrentDeltaReceiptV1>()
        val queuedCuts = mutableListOf<CommittedGeometryCut>()
        val projected = mutableListOf<CommittedGeometryCut>()
        var throwAfterExactQueue = true
        lateinit var stream: Stream
        lateinit var integration: VisibilityGridIntegration
        integration = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
            depthKernelFactory = {
                DepthEvidenceKernel(DepthEvidenceConfiguration(occupiedEvidenceToShow = 1)).also { depthKernel = it }
            },
            featureKernelFactory = { FeatureFusionKernel().also { featureKernel = it } },
            queueCurrent = { activeBinding, source, selector ->
                queuedReceipts += requireNotNull(source.selectCurrentDelta(selector))
                queuedCuts += requireNotNull(integration.pendingPublicationGeometryCut())
                val result = activeBinding.queueCommittedCurrentDelta(source, selector)
                if (throwAfterExactQueue) {
                    throwAfterExactQueue = false
                    exchange(messenger, viewId, stream, 4, 1, 1, 1)
                    exchange(messenger, viewId, stream, 5, 1, 1, 1)
                    exchange(messenger, viewId, stream, 6, 1, 1, 1)
                    exchange(messenger, viewId, stream, 7, 2, 2, 1)
                    throw IllegalStateException("injected lost queue acknowledgement")
                }
                result
            },
            renderer = object : CommittedRendererProjection {
                override fun applyGeometry(cut: CommittedGeometryCut): RendererProjectionResult {
                    projected += cut
                    return RendererProjectionResult.Applied(cut.upserts.size)
                }
            },
        )
        try {
            stream = start(binding, messenger, viewId)
            exchange(messenger, viewId, stream, 1, 0, 0, 0)
            exchange(messenger, viewId, stream, 2, 0, 0, 0)
            exchange(messenger, viewId, stream, 3, 1, 1, 1)
            val cut = requireNotNull(binding.currentObservationOwnership())

            integration.admitDepth(depth(cut, 10))
            assertEquals("publicationRetryPending", integration.integrationReceipt().status)
            assertTrue(projected.isEmpty())
            assertEquals(1L, integration.snapshot().admittedDepths)
            val depthAfterCommit = depthKernel.resourceReceipt()
            val featureAfterCommit = featureKernel.resourceReceipt()

            integration.admitDepth(depth(cut, 11))

            assertEquals("acknowledged", integration.integrationReceipt().status)
            assertEquals(1, queuedReceipts.size)
            assertEquals(1, projected.size)
            assertEquals(1, queuedCuts.size)
            assertTrue(queuedCuts[0] === projected.single())
            assertEquals(queuedReceipts[0].selector.transactionId, projected.single().transactionId)
            assertEquals(queuedReceipts[0].selector.targetGeometryRevision, projected.single().geometryRevision)
            assertEquals(depthAfterCommit, depthKernel.resourceReceipt())
            assertEquals(featureAfterCommit, featureKernel.resourceReceipt())
            assertEquals(1L, integration.snapshot().admittedDepths)

            integration.admitDepth(depth(cut, 12))
            assertEquals("pendingAck", integration.integrationReceipt().status)
            assertEquals(2L, integration.snapshot().admittedDepths)
        } finally {
            integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `depth renderer refusal retains ACK then rebuilds canonical cut without reapplying evidence`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-depth-renderer-rebuild").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val viewId = 2136
        val binding = VisibilityGridV2Binding(messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() })
        lateinit var depthKernel: DepthEvidenceKernel
        val deltaAttempts = mutableListOf<CommittedGeometryCut>()
        val rebuildBegins = mutableListOf<CommittedGeometryCut>()
        val rebuildPages = mutableListOf<CommittedGeometryCut>()
        val rebuildFinishes = mutableListOf<CommittedGeometryCut>()
        val integration = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
            depthKernelFactory = {
                DepthEvidenceKernel(DepthEvidenceConfiguration(occupiedEvidenceToShow = 1)).also { depthKernel = it }
            },
            renderer = object : CommittedRendererProjection {
                override val maximumRows = 100_000
                override fun applyGeometry(cut: CommittedGeometryCut): RendererProjectionResult {
                    deltaAttempts += cut
                    return RendererProjectionResult.Refused(RendererProjectionRefusal.CAPACITY)
                }
                override fun beginRebuild(cut: CommittedGeometryCut) { rebuildBegins += cut }
                override fun appendRebuildPage(cut: CommittedGeometryCut) { rebuildPages += cut }
                override fun finishRebuild(cut: CommittedGeometryCut) { rebuildFinishes += cut }
                override fun currentRowCount() = rebuildPages.sumOf { it.upserts.size }
            },
        )
        try {
            val stream = start(binding, messenger, viewId)
            exchange(messenger, viewId, stream, 1, 0, 0, 0)
            exchange(messenger, viewId, stream, 2, 0, 0, 0)
            exchange(messenger, viewId, stream, 3, 1, 1, 1)
            val cut = requireNotNull(binding.currentObservationOwnership())
            rebuildBegins.clear(); rebuildPages.clear(); rebuildFinishes.clear()

            integration.admitDepth(depth(cut, 10))
            assertEquals("rendererRebuildPending", integration.integrationReceipt().status)
            assertEquals(1, deltaAttempts.size)
            assertEquals(1L, integration.snapshot().admittedDepths)
            val appliedDepth = depthKernel.resourceReceipt()
            rebuildBegins.clear(); rebuildPages.clear(); rebuildFinishes.clear()

            exchange(messenger, viewId, stream, 4, 1, 1, 1)
            exchange(messenger, viewId, stream, 5, 1, 1, 1)
            exchange(messenger, viewId, stream, 6, 1, 1, 1)
            exchange(messenger, viewId, stream, 7, 2, 2, 1)
            await { integration.integrationReceipt().status == "rendererRebuildPending" }

            integration.admitDepth(depth(cut, 11))

            assertEquals("acknowledged", integration.integrationReceipt().status)
            assertEquals(1, deltaAttempts.size)
            assertEquals(appliedDepth, depthKernel.resourceReceipt())
            assertEquals(1L, integration.snapshot().admittedDepths)
            assertEquals(1, rebuildBegins.size)
            assertTrue(rebuildPages.isNotEmpty())
            assertEquals(1, rebuildFinishes.size)
            assertEquals(2, rebuildBegins.single().transactionId)
            assertEquals(2, rebuildBegins.single().geometryRevision)
            assertEquals(1, rebuildBegins.single().lineageRevision)
            assertEquals(2, rebuildPages.single().geometryRevision)
        } finally {
            integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `retryable depth commit retains exact work and next admission publishes it once`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-depth-retry").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val viewId = 2131
        val binding = VisibilityGridV2Binding(messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() })
        lateinit var depthKernel: DepthEvidenceKernel
        val attemptedMutations = mutableListOf<PreparedCanonicalMutation>()
        val renderedCuts = mutableListOf<CommittedGeometryCut>()
        val integration = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
            depthKernelFactory = {
                DepthEvidenceKernel(DepthEvidenceConfiguration(occupiedEvidenceToShow = 1)).also { depthKernel = it }
            },
            commitCanonical = { runtime, mutation ->
                attemptedMutations += mutation
                if (attemptedMutations.size == 1) {
                    CanonicalAdjacentCommitResult.Refused(
                        CanonicalAdjacentCommitRefusal.DURABILITY_FAILURE,
                        disposition = PreparedMutationDisposition.RETRYABLE,
                    )
                } else runtime.commitAdjacent(mutation)
            },
            renderer = object : CommittedRendererProjection {
                override fun applyGeometry(cut: CommittedGeometryCut): RendererProjectionResult {
                    renderedCuts += cut
                    return RendererProjectionResult.Applied(cut.upserts.size)
                }
            },
        )
        try {
            val stream = start(binding, messenger, viewId)
            exchange(messenger, viewId, stream, 1, 0, 0, 0)
            exchange(messenger, viewId, stream, 2, 0, 0, 0)
            exchange(messenger, viewId, stream, 3, 1, 1, 1)
            val cut = requireNotNull(binding.currentObservationOwnership())

            integration.admitDepth(depth(cut, 10))
            assertEquals("depthCommitRetryPending", integration.integrationReceipt().status)
            assertTrue(renderedCuts.isEmpty())
            assertEquals(0, exchange(messenger, viewId, stream, 4, 1, 1, 1).first.messageKind)
            assertEquals(0, depthKernel.resourceReceipt().residentEvidenceRows)
            assertTrue(depthKernel.resourceReceipt().preparedEvidenceRows > 0)
            assertEquals(0L, integration.snapshot().admittedDepths)
            val retained = requireNotNull(integration.pendingDepthRetentionReceipt())
            assertTrue(retained.pendingOwnerScalarBytes > 0)
            assertTrue(retained.mutationPlanBytes > 0)
            assertTrue(retained.geometryCutBytes > 0)
            assertTrue(retained.featureRemapPrimitiveBytes > 0)
            assertTrue(retained.depthPreparedBytes > 0)
            val checkedTotal = listOf(
                retained.pendingOwnerScalarBytes,
                retained.mutationPlanBytes,
                retained.geometryCutBytes,
                retained.featureRemapPrimitiveBytes,
                retained.depthPreparedBytes,
            ).fold(0L) { total, bytes -> Math.addExact(total, bytes) }
            assertEquals(checkedTotal, retained.totalBytes)
            assertTrue(retained.totalBytes <= retained.budgetBytes)

            integration.admitDepth(depth(cut, 11))
            assertEquals("pendingAck", integration.integrationReceipt().status)
            assertEquals(2, attemptedMutations.size)
            assertTrue(attemptedMutations[0] === attemptedMutations[1])
            assertEquals(1, renderedCuts.size)
            assertEquals(2, renderedCuts.single().transactionId)
            assertEquals(1, depthKernel.resourceReceipt().residentEvidenceRows)
            assertEquals(1L, integration.snapshot().admittedDepths)
            assertEquals(null, integration.pendingDepthRetentionReceipt())
        } finally {
            integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `repeated retryable depth refusal retains one capability until pause discards it`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-depth-repeat-retry").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val viewId = 2133
        val binding = VisibilityGridV2Binding(messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() })
        lateinit var depthKernel: DepthEvidenceKernel
        val attemptedMutations = mutableListOf<PreparedCanonicalMutation>()
        val renderedCuts = mutableListOf<CommittedGeometryCut>()
        val integration = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
            depthKernelFactory = {
                DepthEvidenceKernel(DepthEvidenceConfiguration(occupiedEvidenceToShow = 1)).also { depthKernel = it }
            },
            commitCanonical = { _, mutation ->
                attemptedMutations += mutation
                CanonicalAdjacentCommitResult.Refused(
                    CanonicalAdjacentCommitRefusal.DURABILITY_FAILURE,
                    disposition = PreparedMutationDisposition.RETRYABLE,
                )
            },
            renderer = object : CommittedRendererProjection {
                override fun applyGeometry(cut: CommittedGeometryCut): RendererProjectionResult {
                    renderedCuts += cut
                    return RendererProjectionResult.Applied(cut.upserts.size)
                }
            },
        )
        try {
            val stream = start(binding, messenger, viewId)
            exchange(messenger, viewId, stream, 1, 0, 0, 0)
            exchange(messenger, viewId, stream, 2, 0, 0, 0)
            exchange(messenger, viewId, stream, 3, 1, 1, 1)
            val cut = requireNotNull(binding.currentObservationOwnership())

            integration.admitDepth(depth(cut, 10))
            integration.admitFeature(feature(cut, 11))
            integration.admitDepth(depth(cut, 12))

            assertEquals(3, attemptedMutations.size)
            assertTrue(attemptedMutations.all { it === attemptedMutations.first() })
            assertEquals("depthCommitRetryPending", integration.integrationReceipt().status)
            assertTrue(renderedCuts.isEmpty())
            assertEquals(0L, integration.snapshot().admittedDepths)
            assertTrue(depthKernel.resourceReceipt().preparedEvidenceRows > 0)

            integration.pause()
            assertEquals(PreparedMutationLifecycle.DISCARDED, attemptedMutations.first().lifecycle())
            assertEquals(0, depthKernel.resourceReceipt().preparedEvidenceRows)
            assertEquals(null, integration.pendingDepthRetentionReceipt())
        } finally {
            integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `terminal pending depth refusal discards exact work and permits a later fresh batch`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-depth-terminal-retry").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val viewId = 2134
        val binding = VisibilityGridV2Binding(messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() })
        lateinit var depthKernel: DepthEvidenceKernel
        val attemptedMutations = mutableListOf<PreparedCanonicalMutation>()
        val integration = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
            depthKernelFactory = {
                DepthEvidenceKernel(DepthEvidenceConfiguration(occupiedEvidenceToShow = 1)).also { depthKernel = it }
            },
            commitCanonical = { runtime, mutation ->
                attemptedMutations += mutation
                when (attemptedMutations.size) {
                    1 -> CanonicalAdjacentCommitResult.Refused(
                        CanonicalAdjacentCommitRefusal.DURABILITY_FAILURE,
                        disposition = PreparedMutationDisposition.RETRYABLE,
                    )
                    2 -> {
                        mutation.discard()
                        CanonicalAdjacentCommitResult.Refused(
                            CanonicalAdjacentCommitRefusal.DURABILITY_FAILURE,
                            disposition = PreparedMutationDisposition.TERMINAL,
                        )
                    }
                    else -> runtime.commitAdjacent(mutation)
                }
            },
        )
        try {
            val stream = start(binding, messenger, viewId)
            exchange(messenger, viewId, stream, 1, 0, 0, 0)
            exchange(messenger, viewId, stream, 2, 0, 0, 0)
            exchange(messenger, viewId, stream, 3, 1, 1, 1)
            val cut = requireNotNull(binding.currentObservationOwnership())

            integration.admitDepth(depth(cut, 10))
            integration.admitDepth(depth(cut, 11))
            assertEquals("depthCommitTerminalRefused", integration.integrationReceipt().status)
            assertTrue(attemptedMutations[0] === attemptedMutations[1])
            assertEquals(0, depthKernel.resourceReceipt().preparedEvidenceRows)
            assertEquals(0L, integration.snapshot().admittedDepths)
            assertEquals(null, integration.pendingDepthRetentionReceipt())

            integration.admitDepth(depth(cut, 12))
            assertEquals("pendingAck", integration.integrationReceipt().status)
            assertEquals(3, attemptedMutations.size)
            assertTrue(attemptedMutations[2] !== attemptedMutations[0])
            assertEquals(1L, integration.snapshot().admittedDepths)
        } finally {
            integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `pending depth maximum memory formula stays within its checked budget`() {
        val maximum = PendingDepthRetentionReceipt.maximumModeled()
        val checkedTotal = listOf(
            maximum.pendingOwnerScalarBytes,
            maximum.mutationPlanBytes,
            maximum.geometryCutBytes,
            maximum.featureRemapPrimitiveBytes,
            maximum.depthPreparedBytes,
        ).fold(0L) { total, bytes -> Math.addExact(total, bytes) }

        assertEquals(100_000L * 32L, maximum.depthPreparedBytes)
        assertEquals(checkedTotal, maximum.totalBytes)
        assertTrue(maximum.totalBytes <= maximum.budgetBytes)
        assertEquals(null, PendingDepthRetentionReceipt.create(Long.MAX_VALUE, 1, 1, 1))
    }

    @Test
    fun `depth removal clears feature correlation when the source voxel is vacated`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-depth-remap").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val viewId = 2132
        val binding = VisibilityGridV2Binding(messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() })
        lateinit var featureKernel: FeatureFusionKernel
        val renderedCuts = mutableListOf<CommittedGeometryCut>()
        val integration = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
            featureKernelFactory = {
                FeatureFusionKernel().also { featureKernel = it }
            },
            depthKernelFactory = {
                DepthEvidenceKernel(DepthEvidenceConfiguration(
                    safetyBandMillimetres = 0,
                    minimumDepthMillimetres = 1,
                    freeEvidenceToCarve = 1,
                    freeEvidenceMargin = 0,
                    separatedDirectionBinsRequired = 1,
                    occupiedEvidenceToShow = 4,
                ))
            },
            renderer = object : CommittedRendererProjection {
                override fun applyGeometry(cut: CommittedGeometryCut): RendererProjectionResult {
                    renderedCuts += cut
                    return RendererProjectionResult.Applied(cut.upserts.size)
                }
            },
        )
        try {
            val stream = start(binding, messenger, viewId)
            exchange(messenger, viewId, stream, 1, 0, 0, 0)
            exchange(messenger, viewId, stream, 2, 0, 0, 0)
            exchange(messenger, viewId, stream, 3, 1, 1, 1)
            val cut = requireNotNull(binding.currentObservationOwnership())

            integration.admitFeature(feature(cut, 10, x = 0.02, y = 0.02, z = -0.45))
            assertEquals("pendingAck", integration.integrationReceipt().status)
            exchange(messenger, viewId, stream, 4, 1, 1, 1)
            exchange(messenger, viewId, stream, 5, 1, 1, 1)
            exchange(messenger, viewId, stream, 6, 1, 1, 1)
            exchange(messenger, viewId, stream, 7, 2, 2, 1)
            await { integration.integrationReceipt().status == "acknowledged" }
            val correlation = requireNotNull(featureKernel.canonicalCorrelation(0))
            assertEquals(SurfaceId(1), correlation.id)
            assertTrue(renderedCuts.first().upserts.any {
                it.surfaceId == SurfaceId(1).value && it.voxel == Voxel(0, 0, -5)
            })
            assertEquals(0, featureKernel.canonicalFeatureSlot(Voxel(0, 0, -5), SurfaceId(1)))

            integration.admitDepth(
                depth(
                    cut, 20, depthMillimeters = 10,
                    principalX = 2.0, principalY = 0.0, cameraXMeters = 0.005,
                ),
            )

            assertEquals("nonMaterialRetained", integration.integrationReceipt().status)
            assertEquals(1, renderedCuts.size)
            assertEquals(SurfaceId(1), featureKernel.canonicalCorrelation(0)?.id)

            integration.admitDepth(
                depth(
                    cut, 21, depthMillimeters = 10,
                    sampleX = 3, principalX = 1.0, principalY = 0.0, cameraXMeters = -0.004,
                ),
            )

            assertEquals("pendingAck", integration.integrationReceipt().status)
            assertEquals("DEPTH_BATCH", integration.integrationReceipt().canonicalOperation)
            assertEquals(2, renderedCuts.size)
            assertArrayEquals(longArrayOf(SurfaceId(1).value), renderedCuts.last().removedSurfaceIds)
            assertTrue(renderedCuts.last().upserts.isEmpty())
            assertEquals(null, featureKernel.canonicalCorrelation(0))
        } finally {
            integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `canonical commit refusal leaves new and refined kernel batches retryable`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-kernel-retry").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(messenger, 2116, CommittedBaselineAuthority(), postToMain = { it() })
        var refuseNext = true
        val integration = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
            commitCanonical = { runtime, mutation ->
                if (refuseNext) {
                    refuseNext = false
                    runtime.commitAdjacent(
                        mutation,
                        CanonicalCommitFaults(journal = CanonicalDirtyJournalFault.BEFORE_ALLOCATION_RESERVATION),
                    )
                } else runtime.commitAdjacent(mutation)
            },
        )
        try {
            val stream = start(binding, messenger, 2116)
            exchange(messenger, 2116, stream, 1, 0, 0, 0)
            exchange(messenger, 2116, stream, 2, 0, 0, 0)
            exchange(messenger, 2116, stream, 3, 1, 1, 1)
            val cut = requireNotNull(binding.currentObservationOwnership())

            integration.admitFeature(feature(cut, 10))
            assertEquals("canonicalCommitRefused", integration.integrationReceipt().status)
            var kernel = privateField<FeatureFusionKernel>(integration, "kernel")
            assertEquals(0, kernel.resourceReceipt().surfaceCount)
            assertEquals(0, kernel.resourceReceipt().associationCount)

            integration.admitFeature(feature(cut, 11))
            assertEquals("pendingAck", integration.integrationReceipt().status)
            assertEquals(1, kernel.resourceReceipt().surfaceCount)
            assertEquals(1, kernel.resourceReceipt().associationCount)
            exchange(messenger, 2116, stream, 4, 1, 1, 1)
            exchange(messenger, 2116, stream, 5, 1, 1, 1)
            exchange(messenger, 2116, stream, 6, 1, 1, 1)
            exchange(messenger, 2116, stream, 7, 2, 2, 1)
            await { integration.integrationReceipt().status == "acknowledged" }

            refuseNext = true
            integration.admitFeature(feature(cut, 12, cameraX = 1.0))
            assertEquals("batchCommitRefused", integration.integrationReceipt().status)
            assertEquals(1, kernel.resourceReceipt().associationCount)
            integration.admitFeature(feature(cut, 13, cameraX = 1.0))
            assertEquals("pendingAck", integration.integrationReceipt().status)
            assertEquals(2, kernel.resourceReceipt().associationCount)
        } finally {
            integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `queue throws before or after exact install retry ACK and admit a later batch`() {
        listOf(false, true).forEachIndexed { index, throwAfterQueue ->
            val directory = Files.createTempDirectory("canonical-surface-runtime-queue-retry-$index").toFile()
            val coordinator = budget(directory)
            val messenger = MethodTestMessenger()
            val viewId = 2120 + index
            val binding = VisibilityGridV2Binding(messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() })
            var armed = true
            val projected = mutableListOf<CommittedGeometryCut>()
            val integration = VisibilityGridIntegration(
                binding, binding::currentObservationOwnership, directory,
                resourcesForGroup = resources(directory, coordinator),
                renderer = object : CommittedRendererProjection {
                    override fun applyGeometry(cut: CommittedGeometryCut): RendererProjectionResult {
                        projected += cut
                        return RendererProjectionResult.Applied(cut.upserts.size)
                    }
                },
                queueCurrent = { activeBinding, source, selector ->
                    if (armed) {
                        armed = false
                        if (throwAfterQueue) activeBinding.queueCommittedCurrentDelta(source, selector)
                        throw IllegalStateException("injected queue boundary")
                    }
                    activeBinding.queueCommittedCurrentDelta(source, selector)
                },
            )
            try {
                val stream = start(binding, messenger, viewId)
                exchange(messenger, viewId, stream, 1, 0, 0, 0)
                exchange(messenger, viewId, stream, 2, 0, 0, 0)
                exchange(messenger, viewId, stream, 3, 1, 1, 1)
                val cut = requireNotNull(binding.currentObservationOwnership())

                integration.admitFeature(feature(cut, 10))
                assertEquals("publicationRetryPending", integration.integrationReceipt().status)
                assertTrue("A refused queue must not project geometry", projected.isEmpty())
                val kernel = privateField<FeatureFusionKernel>(integration, "kernel")
                assertEquals(1, kernel.resourceReceipt().surfaceCount)
                assertEquals(1, kernel.resourceReceipt().associationCount)
                integration.admitFeature(feature(cut, 11, 0.32))
                assertEquals("awaitingExactAck", integration.integrationReceipt().status)
                assertEquals(1, projected.size)
                assertEquals(1, kernel.resourceReceipt().surfaceCount)
                assertEquals(1, kernel.resourceReceipt().associationCount)

                exchange(messenger, viewId, stream, 4, 1, 1, 1)
                exchange(messenger, viewId, stream, 5, 1, 1, 1)
                exchange(messenger, viewId, stream, 6, 1, 1, 1)
                exchange(messenger, viewId, stream, 7, 2, 2, 1)
                await { integration.integrationReceipt().status == "acknowledged" }
                integration.admitFeature(feature(cut, 12, 0.32))
                assertEquals("pendingAck", integration.integrationReceipt().status)
                assertEquals(2, kernel.resourceReceipt().surfaceCount)
                assertEquals(2, kernel.resourceReceipt().associationCount)
            } finally {
                integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `renderer refusal retains ACK and rebuilds canonical current instead of retrying delta`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-renderer-retry").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val viewId = 2122
        val binding = VisibilityGridV2Binding(messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() })
        val attempts = mutableListOf<CommittedGeometryCut>()
        val rebuildBegins = mutableListOf<CommittedGeometryCut>()
        val rebuildPages = mutableListOf<CommittedGeometryCut>()
        val rebuildFinishes = mutableListOf<CommittedGeometryCut>()
        val integration = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
            renderer = object : CommittedRendererProjection {
                override val maximumRows = 100_000
                override fun applyGeometry(cut: CommittedGeometryCut): RendererProjectionResult {
                    attempts += cut
                    return RendererProjectionResult.Refused(RendererProjectionRefusal.CAPACITY)
                }
                override fun beginRebuild(cut: CommittedGeometryCut) { rebuildBegins += cut }
                override fun appendRebuildPage(cut: CommittedGeometryCut) { rebuildPages += cut }
                override fun finishRebuild(cut: CommittedGeometryCut) { rebuildFinishes += cut }
                override fun currentRowCount() = rebuildPages.sumOf { it.upserts.size }
            },
        )
        try {
            val stream = start(binding, messenger, viewId)
            exchange(messenger, viewId, stream, 1, 0, 0, 0)
            exchange(messenger, viewId, stream, 2, 0, 0, 0)
            exchange(messenger, viewId, stream, 3, 1, 1, 1)
            val ownership = requireNotNull(binding.currentObservationOwnership())
            rebuildBegins.clear(); rebuildPages.clear(); rebuildFinishes.clear()

            integration.admitFeature(feature(ownership, 10))
            assertEquals("rendererRebuildPending", integration.integrationReceipt().status)
            val retained = attempts.single()
            assertEquals(2, retained.transactionId)
            assertEquals(1, retained.baseGeometryRevision)
            assertEquals(2, retained.geometryRevision)
            assertEquals(1, retained.lineageRevision)
            assertEquals(1, retained.upserts.size)
            rebuildBegins.clear(); rebuildPages.clear(); rebuildFinishes.clear()

            exchange(messenger, viewId, stream, 4, 1, 1, 1)
            exchange(messenger, viewId, stream, 5, 1, 1, 1)
            exchange(messenger, viewId, stream, 6, 1, 1, 1)
            exchange(messenger, viewId, stream, 7, 2, 2, 1)
            await { integration.integrationReceipt().status == "rendererRebuildPending" }

            integration.admitFeature(feature(ownership, 11, 0.32))
            assertEquals(1, attempts.size)
            assertEquals("acknowledged", integration.integrationReceipt().status)
            assertEquals(1, rebuildBegins.size)
            assertTrue(rebuildPages.isNotEmpty())
            assertEquals(1, rebuildFinishes.size)
            assertEquals(retained.transactionId, rebuildBegins.single().transactionId)
            assertEquals(retained.geometryRevision, rebuildBegins.single().geometryRevision)
            assertEquals(retained.lineageRevision, rebuildBegins.single().lineageRevision)
            assertEquals(retained.upserts.single().surfaceId, rebuildPages.single().upserts.single().surfaceId)
        } finally {
            integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `acknowledged initial CREATE admits the next material kernel batch through v6`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-next-batch").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(messenger, 2108, CommittedBaselineAuthority(), postToMain = { it() })
        val rendered = mutableListOf<com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot>()
        var activeResources: CanonicalRuntimeResources? = null
        val integration = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = { group ->
                CanonicalRuntimeResources.open(directory, group, coordinator).also { activeResources = it }
            },
            renderer = NativeRendererProjection(render = { snapshot, _ -> snapshot?.let(rendered::add) }),
        )
        try {
            val stream = start(binding, messenger, 2108)
            exchange(messenger, 2108, stream, 1, 0, 0, 0)
            exchange(messenger, 2108, stream, 2, 0, 0, 0)
            exchange(messenger, 2108, stream, 3, 1, 1, 1)
            val cut = requireNotNull(binding.currentObservationOwnership())
            val baselineAccounting = chargedPhysical(directory, coordinator) to coordinator.committedBytes()

            integration.admitFeature(feature(cut, 10, 0.12))
            val createPendingAccounting = chargedPhysical(directory, coordinator) to coordinator.committedBytes()
            exchange(messenger, 2108, stream, 4, 1, 1, 1)
            exchange(messenger, 2108, stream, 5, 1, 1, 1)
            exchange(messenger, 2108, stream, 6, 1, 1, 1)
            exchange(messenger, 2108, stream, 7, 2, 2, 1)
            await { integration.integrationReceipt().status == "acknowledged" }
            val createAccounting = chargedPhysical(directory, coordinator) to coordinator.committedBytes()

            integration.admitFeature(feature(cut, 11, 0.32))
            val later = integration.integrationReceipt()
            assertEquals("pendingAck", later.status)
            assertEquals(3, later.transactionId)
            assertEquals(3, later.geometryRevision)
            assertEquals(1, later.lineageRevision)
            assertEquals("FEATURE_BATCH", later.canonicalOperation)
            assertEquals(2, later.committed)
            assertEquals(3, rendered.size)
            assertEquals(later.geometryRevision, rendered.last().update?.geometryRevision)
            assertEquals(later.rendererRows, rendered.last().count)
            val canonical = requireNotNull(activeResources).readRendererPage(0, 512)
            assertEquals(later.geometryRevision, canonical?.cut?.geometryRevision)
            assertArrayEquals(
                canonical?.rows?.map { row ->
                    packVisibilityGridKey(row.voxel.x, row.voxel.y, row.voxel.z)
                }?.toLongArray(),
                rendered.last().keys,
            )
            val current = requireNotNull(activeResources.owner().activationState()?.current)
                as CanonicalActivationCurrent.Receipt
            val currentBytes = ByteArrayOutputStream().use { output ->
                current.source.writeTo(output); output.toByteArray()
            }
            assertArrayEquals(
                current.identity.canonicalHash.toByteArray(),
                MessageDigest.getInstance("SHA-256").digest(currentBytes),
            )
            val chargedPhysicalBytes = chargedPhysical(directory, coordinator)
            assertEquals(
                "multi-generation ledger equals physical baseline=$baselineAccounting createPending=$createPendingAccounting create=$createAccounting",
                chargedPhysicalBytes,
                coordinator.committedBytes(),
            )
        } finally {
            integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `empty accepted refinement does not borrow v6 or scan durable history`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-empty-delta").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(messenger, 2110, CommittedBaselineAuthority(), postToMain = { it() })
        val integration = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
        )
        try {
            val stream = start(binding, messenger, 2110)
            exchange(messenger, 2110, stream, 1, 0, 0, 0)
            exchange(messenger, 2110, stream, 2, 0, 0, 0)
            exchange(messenger, 2110, stream, 3, 1, 1, 1)
            val cut = requireNotNull(binding.currentObservationOwnership())
            integration.admitFeature(feature(cut, 10, 0.12))
            exchange(messenger, 2110, stream, 4, 1, 1, 1)
            exchange(messenger, 2110, stream, 5, 1, 1, 1)
            exchange(messenger, 2110, stream, 6, 1, 1, 1)
            exchange(messenger, 2110, stream, 7, 2, 2, 1)
            await { integration.integrationReceipt().status == "acknowledged" }

            val groupDirectory = File(
                directory,
                "visibility-grid-canonical-surface-runtime/${cut.captureGroupId}",
            )
            val selector = requireNotNull(groupDirectory.listFiles()).single { it.name.endsWith(".selector") }
            assertTrue(selector.delete())
            val committedBefore = coordinator.committedBytes()

            // The second equal observation changes retained kernel confidence
            // inside the same canonical band, so its material delta is empty.
            // A v6/current/coordinator borrow would now fail on the missing
            // selector; the early no-op path remains independent of durability.
            integration.admitFeature(feature(cut, 11, 0.12))
            assertEquals("nonMaterialRetained", integration.integrationReceipt().status)
            assertEquals(committedBefore, coordinator.committedBytes())
            assertEquals(0, exchange(messenger, 2110, stream, 8, 2, 2, 1).first.messageKind)
        } finally {
            integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `reopened unacknowledged current recovers failed rebuild before completing ACK`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-reopen-unack-rebuild").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val viewId = 2140
        val firstBinding = VisibilityGridV2Binding(messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() })
        val first = VisibilityGridIntegration(
            firstBinding, firstBinding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
        )
        try {
            val stream = start(firstBinding, messenger, viewId)
            exchange(messenger, viewId, stream, 1, 0, 0, 0)
            exchange(messenger, viewId, stream, 2, 0, 0, 0)
            exchange(messenger, viewId, stream, 3, 1, 1, 1)
            first.admitFeature(feature(requireNotNull(firstBinding.currentObservationOwnership()), 10))
            assertEquals("pendingAck", first.integrationReceipt().status)
        } finally {
            first.close(); firstBinding.dispose()
        }

        val binding = VisibilityGridV2Binding(messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() })
        var failBegin = true
        var beginCount = 0
        var finishCount = 0
        val replacement = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
            renderer = object : CommittedRendererProjection {
                override val maximumRows = 100_000
                override fun applyGeometry(cut: CommittedGeometryCut) = RendererProjectionResult.Applied(cut.upserts.size)
                override fun beginRebuild(cut: CommittedGeometryCut) {
                    beginCount++
                    if (failBegin) { failBegin = false; throw IllegalStateException("injected reopen begin") }
                }
                override fun appendRebuildPage(cut: CommittedGeometryCut) = Unit
                override fun finishRebuild(cut: CommittedGeometryCut) { finishCount++ }
                override fun currentRowCount() = 1
            },
        )
        try {
            val stream = start(binding, messenger, viewId)
            exchange(messenger, viewId, stream, 1, 0, 0, 0)
            exchange(messenger, viewId, stream, 2, 0, 0, 0)
            exchange(messenger, viewId, stream, 3, 1, 1, 1)
            val cut = requireNotNull(binding.currentObservationOwnership())

            replacement.admitFeature(feature(cut, 11, 0.32))
            assertTrue(beginCount >= 2)
            assertEquals(1, finishCount)
            assertEquals("awaitingExactAck", replacement.integrationReceipt().status)

            exchange(messenger, viewId, stream, 4, 1, 1, 1)
            exchange(messenger, viewId, stream, 5, 1, 1, 1)
            exchange(messenger, viewId, stream, 6, 1, 1, 1)
            exchange(messenger, viewId, stream, 7, 2, 2, 1)
            await { replacement.integrationReceipt().status == "acknowledged" }

            replacement.admitFeature(feature(cut, 12, 0.32))
            assertEquals("pendingAck", replacement.integrationReceipt().status)
            assertEquals(3, replacement.integrationReceipt().geometryRevision)
        } finally {
            replacement.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `reopened acknowledged current gates begin and page failures until rebuild succeeds`() {
        listOf("begin", "page").forEachIndexed { index, failurePoint ->
            val directory = Files.createTempDirectory("canonical-surface-runtime-reopen-ack-$failurePoint").toFile()
            val coordinator = budget(directory)
            val messenger = MethodTestMessenger()
            val viewId = 2141 + index
            val firstBinding = VisibilityGridV2Binding(messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() })
            val first = VisibilityGridIntegration(
                firstBinding, firstBinding::currentObservationOwnership, directory,
                resourcesForGroup = resources(directory, coordinator),
            )
            try {
                val stream = start(firstBinding, messenger, viewId)
                exchange(messenger, viewId, stream, 1, 0, 0, 0)
                exchange(messenger, viewId, stream, 2, 0, 0, 0)
                exchange(messenger, viewId, stream, 3, 1, 1, 1)
                val cut = requireNotNull(firstBinding.currentObservationOwnership())
                first.admitFeature(feature(cut, 10))
                exchange(messenger, viewId, stream, 4, 1, 1, 1)
                exchange(messenger, viewId, stream, 5, 1, 1, 1)
                exchange(messenger, viewId, stream, 6, 1, 1, 1)
                exchange(messenger, viewId, stream, 7, 2, 2, 1)
                await { first.integrationReceipt().status == "acknowledged" }
            } finally {
                first.close(); firstBinding.dispose()
            }

            val binding = VisibilityGridV2Binding(messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() })
            var armed = true
            var finishCount = 0
            val replacement = VisibilityGridIntegration(
                binding, binding::currentObservationOwnership, directory,
                resourcesForGroup = resources(directory, coordinator),
                renderer = object : CommittedRendererProjection {
                    override val maximumRows = 100_000
                    override fun applyGeometry(cut: CommittedGeometryCut) = RendererProjectionResult.Applied(cut.upserts.size)
                    override fun beginRebuild(cut: CommittedGeometryCut) {
                        if (armed && failurePoint == "begin") { armed = false; throw IllegalStateException("injected begin") }
                    }
                    override fun appendRebuildPage(cut: CommittedGeometryCut) {
                        if (armed && failurePoint == "page") { armed = false; throw IllegalStateException("injected page") }
                    }
                    override fun finishRebuild(cut: CommittedGeometryCut) { finishCount++ }
                },
            )
            try {
                val stream = start(binding, messenger, viewId)
                exchange(messenger, viewId, stream, 1, 0, 0, 0)
                exchange(messenger, viewId, stream, 2, 0, 0, 0)
                exchange(messenger, viewId, stream, 3, 1, 1, 1)
                val cut = requireNotNull(binding.currentObservationOwnership())

                replacement.admitFeature(feature(cut, 11, 0.32))
                assertEquals("rendererRebuildPending", replacement.integrationReceipt().status)
                assertEquals(0, replacement.integrationReceipt().committed)

                replacement.admitFeature(feature(cut, 12, 0.32))
                assertEquals("rendererRebuildRecovered", replacement.integrationReceipt().status)
                assertEquals(1, finishCount)

                replacement.admitFeature(feature(cut, 13, 0.12))
                assertEquals("nonMaterialRetained", replacement.integrationReceipt().status)
            } finally {
                replacement.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `restart replays an unacknowledged v6 receipt before admitting a later batch`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-replay").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val firstBinding = VisibilityGridV2Binding(messenger, 2109, CommittedBaselineAuthority(), postToMain = { it() })
        val first = VisibilityGridIntegration(
            firstBinding, firstBinding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
        )
        lateinit var expectedCurrentHash: ByteArray
        try {
            val firstStream = start(firstBinding, messenger, 2109)
            exchange(messenger, 2109, firstStream, 1, 0, 0, 0)
            exchange(messenger, 2109, firstStream, 2, 0, 0, 0)
            exchange(messenger, 2109, firstStream, 3, 1, 1, 1)
            first.admitFeature(feature(requireNotNull(firstBinding.currentObservationOwnership()), 10, 0.12))
            assertEquals("pendingAck", first.integrationReceipt().status)
            expectedCurrentHash = ((requireNotNull(privateField<CanonicalRuntimeResources>(first, "resources"))
                .owner().activationState()?.current) as CanonicalActivationCurrent.Receipt)
                .identity.canonicalHash.toByteArray()
        } finally {
            first.close(); firstBinding.dispose()
        }

        val replacementBinding = VisibilityGridV2Binding(messenger, 2109, CommittedBaselineAuthority(), postToMain = { it() })
        val replayRendered = mutableListOf<com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot>()
        val replacement = VisibilityGridIntegration(
            replacementBinding, replacementBinding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
            renderer = NativeRendererProjection(render = { snapshot, _ -> snapshot?.let(replayRendered::add) }),
        )
        try {
            val stream = start(replacementBinding, messenger, 2109)
            exchange(messenger, 2109, stream, 1, 0, 0, 0)
            exchange(messenger, 2109, stream, 2, 0, 0, 0)
            exchange(messenger, 2109, stream, 3, 1, 1, 1)
            val cut = requireNotNull(replacementBinding.currentObservationOwnership())

            replacement.admitFeature(feature(cut, 11, 0.32))
            assertEquals("awaitingExactAck", replacement.integrationReceipt().status)
            assertTrue(replayRendered.isNotEmpty())
            assertEquals(1, replayRendered.last().count)
            assertEquals(2L, replayRendered.last().update?.geometryRevision)
            var sequence = 4L
            val begin = exchange(messenger, 2109, stream, sequence++, 1, 1, 1).first
            assertEquals(2, begin.messageKind)
            assertEquals(1, begin.baseGeometryRevision)
            assertEquals(2, begin.targetGeometryRevision)
            val replayed = ByteArrayOutputStream()
            repeat(begin.chunkCount) { index ->
                val chunk = exchange(messenger, 2109, stream, sequence++, 1, 1, 1).first
                assertEquals(3, chunk.messageKind)
                assertEquals(index, chunk.chunkIndex)
                replayed.write((TransactionResponseCodecV1.decodeFrame(chunk) as TransactionChunkFrameV1).value.bytes)
            }
            assertEquals(4, exchange(messenger, 2109, stream, sequence++, 1, 1, 1).first.messageKind)
            assertArrayEquals(expectedCurrentHash, MessageDigest.getInstance("SHA-256").digest(replayed.toByteArray()))
            assertEquals(0, exchange(messenger, 2109, stream, sequence, 2, 2, 1).first.messageKind)
            await { replacement.integrationReceipt().status == "acknowledged" }

            replacement.admitFeature(feature(cut, 12, 0.32))
            assertEquals("pendingAck", replacement.integrationReceipt().status)
            assertEquals(3, replacement.integrationReceipt().geometryRevision)
        } finally {
            replacement.close(); replacementBinding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `pause replacement and close fence feature publication`() {
        val directory = Files.createTempDirectory("canonical-surface-runtime-pause").toFile()
        val coordinator = budget(directory)
        val messenger = MethodTestMessenger()
        val binding = VisibilityGridV2Binding(messenger, 2107, CommittedBaselineAuthority(), postToMain = { it() })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fenced = CountDownLatch(1)
        val integration = VisibilityGridIntegration(
            binding, binding::currentObservationOwnership, directory,
            resourcesForGroup = resources(directory, coordinator),
            beforeAdmission = { entered.countDown(); release.await(2, TimeUnit.SECONDS) },
            afterLifecycleFence = { fenced.countDown() },
        )
        try {
            val stream = start(binding, messenger, 2107)
            exchange(messenger, 2107, stream, 1, 0, 0, 0)
            exchange(messenger, 2107, stream, 2, 0, 0, 0)
            exchange(messenger, 2107, stream, 3, 1, 1, 1)
            val cut = requireNotNull(binding.currentObservationOwnership())
            val admitted = Thread { integration.admitFeature(feature(cut, 11)) }.apply { start() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val pausing = Thread(integration::pause).apply { start() }
            assertTrue(fenced.await(2, TimeUnit.SECONDS))
            release.countDown()
            admitted.join(2_000); pausing.join(2_000)
            assertEquals("idle", integration.integrationReceipt().status)
            assertEquals(0, exchange(messenger, 2107, stream, 4, 1, 1, 1).first.messageKind)

            integration.resume()
            binding.dispose()
            integration.admitFeature(feature(cut, 12))
            assertTrue(integration.integrationReceipt().transactionId < 2)

            val beforeClose = integration.integrationReceipt()
            integration.close()
            integration.admitFeature(feature(cut, 13))
            assertEquals(beforeClose, integration.integrationReceipt())
        } finally {
            integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
        }
    }

    @Test
    fun `lifecycle fences worker between precheck and publication gate without deadlock`() {
        listOf("pause", "rollover", "replacement", "close").forEachIndexed { index, action ->
            val directory = Files.createTempDirectory("canonical-surface-runtime-barrier-$action").toFile()
            val coordinator = budget(directory)
            val messenger = MethodTestMessenger()
            val viewId = 2200 + index
            val binding = VisibilityGridV2Binding(
                messenger, viewId, CommittedBaselineAuthority(), postToMain = { it() },
            )
            var current: VisibilityObservationOwnership? = null
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val lifecycleFenced = CountDownLatch(1)
            val integration = VisibilityGridIntegration(
                binding,
                ownership = { current ?: binding.currentObservationOwnership() },
                directory = directory,
                resourcesForGroup = resources(directory, coordinator),
                beforeAdmission = { entered.countDown(); release.await(2, TimeUnit.SECONDS) },
                afterLifecycleFence = { lifecycleFenced.countDown() },
            )
            try {
                val stream = start(binding, messenger, viewId)
                exchange(messenger, viewId, stream, 1, 0, 0, 0)
                exchange(messenger, viewId, stream, 2, 0, 0, 0)
                exchange(messenger, viewId, stream, 3, 1, 1, 1)
                val cut = requireNotNull(binding.currentObservationOwnership())
                current = cut
                val worker = Thread { integration.admitFeature(feature(cut, 100 + index.toLong())) }.apply { start() }
                assertTrue("$action worker did not reach barrier", entered.await(2, TimeUnit.SECONDS))
                val lifecycle = Thread {
                    when (action) {
                        "pause" -> integration.pause()
                        "rollover" -> integration.rollover(cut)
                        "replacement" -> {
                            val replacement = cut.copy(bindingGeneration = cut.bindingGeneration + 1)
                            current = replacement
                            integration.rollover(replacement)
                        }
                        else -> integration.close()
                    }
                }.apply { start() }
                assertTrue("$action did not publish its fence", lifecycleFenced.await(2, TimeUnit.SECONDS))
                release.countDown()
                worker.join(2_000); lifecycle.join(2_000)
                assertTrue("$action worker deadlocked", !worker.isAlive)
                assertTrue("$action lifecycle deadlocked", !lifecycle.isAlive)
                assertEquals("$action published stale state", 0L, integration.integrationReceipt().committed)
                assertEquals(0, exchange(messenger, viewId, stream, 4, 1, 1, 1).first.messageKind)
            } finally {
                release.countDown()
                integration.close(); binding.dispose(); coordinator.close(); directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `seeded binding runtime empty baseline makes first atomic CREATE adjacent and lineage-stable`() {
        val baseline = committedEmptyBaseline("binding:group:7:9", "group", 1, 1, 1)
        val opened = SurfaceOwnership.inMemory(
            SurfaceGroup("group"),
            SurfaceOwnershipConfiguration(seededEmptyBaseline = baseline),
        ) as SurfaceOwnershipOpenResult.Opened
        val result = opened.ownership.transact(
            CanonicalTransactionCommand(
                commandId = "create-after-binding-ack",
                kind = CanonicalOperation.CREATE,
                expectedGeometryRevision = 1,
                expectedLineageRevision = 1,
                sourceIds = emptyList(),
                targets = listOf(CanonicalTarget(voxel = Voxel(1, 2, 3), normalOctX = 1, normalOctY = 1, normalConfidence = 200)),
            ),
        ) as CanonicalTransactionResult.Accepted
        assertEquals(2, result.receipt.geometryRevision)
        assertEquals(1, result.receipt.lineageRevision)
        assertTrue(result.receipt.lineageEdges.isEmpty())
        assertEquals(1, result.targets.size)
    }

    @Test
    fun `runtime resources activate direct empty v6 roots inside isolated group directories`() {
        val root = Files.createTempDirectory("canonical-surface-runtime-groups").toFile()
        val coordinator = budget(root)
        val groups = listOf("a".repeat(32), "b".repeat(32))
        try {
            groups.forEachIndexed { index, value ->
                val group = SurfaceGroup(value)
                CanonicalRuntimeResources.open(root, group, coordinator).use { resources ->
                    val expected = File(root, "visibility-grid-canonical-surface-runtime/$value")
                        .absoluteFile.toPath().normalize().toFile()
                    assertEquals(expected, resources.directory)
                    assertEquals(expected, resources.groupDirectory)
                    val baseline = committedEmptyBaseline("binding-$index", value, 1, 1, 1)
                    val opened = resources.openInitial(baseline) as SurfaceOwnershipOpenResult.Opened
                    val state = requireNotNull(opened.ownership.activationState())
                    assertEquals(baseline, state.cut.seededEmptyBaseline)
                    assertEquals(0, state.cut.liveSurfaceCount)
                    assertTrue(state.currentState is CanonicalCurrentState.None)
                    assertTrue(expected.walkTopDown().filter(File::isFile).all { file ->
                        file.absoluteFile.toPath().normalize().startsWith(expected.toPath())
                    })
                    assertTrue(expected.listFiles().orEmpty().none { it.name.contains("ownership") })
                }
            }
            val shared = File(root, "visibility-grid-canonical-surface-runtime")
            val authority = shared.walkTopDown().filter(File::isFile).filter {
                it.name.startsWith("canonical-surface-") || it.name.endsWith(".selector") || it.name.endsWith(".slot")
            }.toList()
            assertTrue(authority.isNotEmpty())
            assertTrue(authority.all { file -> groups.any { value ->
                file.absoluteFile.toPath().normalize().startsWith(File(shared, value).toPath())
            } })
            assertTrue(coordinator.committedBytes() > 0)
        } finally {
            coordinator.close(); root.deleteRecursively()
        }
    }

    @Test
    fun `seed identity is durable before CREATE and mismatched reopen fails closed`() {
        val directory = Files.createTempDirectory("canonical-surface-seeded-baseline").toFile()
        try {
            val baseline = committedEmptyBaseline("binding-a", "group", 1, 1, 1)
            val configuration = SurfaceOwnershipConfiguration(seededEmptyBaseline = baseline)
            val first = SurfaceOwnership.open(SurfaceGroup("group"), directory, configuration)
                as SurfaceOwnershipOpenResult.Opened
            first.ownership.close()

            val reopened = SurfaceOwnership.open(SurfaceGroup("group"), directory, configuration)
            assertTrue(reopened is SurfaceOwnershipOpenResult.Opened)
            (reopened as SurfaceOwnershipOpenResult.Opened).ownership.close()

            val mismatch = baseline.copy(bindingIdentity = "binding-b")
            val refused = SurfaceOwnership.open(
                SurfaceGroup("group"),
                directory,
                SurfaceOwnershipConfiguration(seededEmptyBaseline = mismatch),
            ) as SurfaceOwnershipOpenResult.Refused
            assertEquals(SurfaceOwnershipRestoreRefusal.FORK, refused.reason)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `committed CREATE reopens with byte exact current receipt`() {
        val directory = Files.createTempDirectory("canonical-surface-create-current-delta").toFile()
        try {
            val baseline = committedEmptyBaseline("binding", "group", 1, 1, 1)
            val configuration = SurfaceOwnershipConfiguration(seededEmptyBaseline = baseline)
            val first = SurfaceOwnership.open(SurfaceGroup("group"), directory, configuration)
                as SurfaceOwnershipOpenResult.Opened
            val accepted = first.ownership.transact(
                CanonicalTransactionCommand(
                    "create", CanonicalOperation.CREATE, 1, 1, emptyList(),
                    listOf(
                        CanonicalTarget(
                            voxel = Voxel(3, 2, 1),
                            normalOctX = 1,
                            normalOctY = 1,
                            normalConfidence = 200,
                        ),
                    ),
                ),
            ) as CanonicalTransactionResult.Accepted
            first.ownership.close()

            val reopened = SurfaceOwnership.open(SurfaceGroup("group"), directory, configuration)
                as SurfaceOwnershipOpenResult.Opened
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

    private data class Stream(
        val qualifier: ByteArray,
        val token: Long,
    )

    private fun start(binding: VisibilityGridV2Binding, messenger: MethodTestMessenger, viewId: Int): Stream {
        val snapshot = binding.snapshot()
        val qualifier = snapshot.nativeStreamToken + snapshot.workerBindingToken
        val result = RecordingResult()
        MethodChannel(messenger, "visibility_grid_v2_control_$viewId").invokeMethod(
            "start", qualifier + ControlCodec.encodeRequest(startRequest()), result,
        )
        assertTrue(result.completed.await(2, TimeUnit.SECONDS))
        val response = ControlCodec.decodeResponse(strip(result.successValue as ByteArray, qualifier))
        assertEquals(0, response.outcome)
        return Stream(qualifier, response.streamToken)
    }

    private fun exchange(
        messenger: MethodTestMessenger,
        viewId: Int,
        stream: Stream,
        sequence: Long,
        transaction: Long,
        geometry: Long,
        lineage: Long,
    ): Pair<PacketCodec.Response, ByteArray> {
        val request = stream.qualifier + PacketCodec.encodeRequest(
            PacketCodec.Request(
                0, stream.token, transaction, geometry, lineage, 0,
                TransactionResponseProfileV1.ordinary.responseCeilingBytes,
                emptyList(), byteArrayOf(), sequence,
            ),
        )
        val reply = RecordingBinaryReply()
        messenger.send("visibility_surface_stream_$viewId", ByteBuffer.wrap(request), reply)
        assertTrue(reply.completed.await(2, TimeUnit.SECONDS))
        val raw = strip(requireNotNull(reply.bytes), stream.qualifier)
        return PacketCodec.decodeResponse(raw) to raw
    }

    private fun feature(
        cut: VisibilityObservationOwnership,
        timestamp: Long,
        x: Double = 0.12,
        y: Double = 0.02,
        z: Double = 0.02,
        cameraX: Double = 0.0,
    ): VisibilityFeatureObservation {
        val samples = VisibilityFeatureObservation.copySamples(
            listOf(VisibilityFeatureSample(7, x, y, z, 1.0)),
        )
        val pose = identityVisibilityGridTransform().also { it[12] = cameraX }
        return VisibilityFeatureObservation(
            ownership = cut,
            frame = VisibilityObservationFrame(
                VisibilityObservationSource.SYNTHETIC_FEATURE, timestamp, timestamp, timestamp,
                "synthetic-camera", true, "landscape_right_x_right_y_down_v1",
                VisibilityCameraPose.copyOf(pose),
                VisibilityCameraIntrinsics(16, 12, 10.0, 10.0, 8.0, 6.0),
                VisibilityDepthCapability.UNSUPPORTED,
            ),
            samples = samples,
            sourceRejectedSamples = 0,
            payloadBytes = VisibilityFeatureObservation.FEATURE_FIXED_BYTES +
                samples.size * VisibilityFeatureObservation.FEATURE_SAMPLE_BYTES,
        )
    }

    private fun depth(
        cut: VisibilityObservationOwnership,
        timestamp: Long,
        sampleX: Int = 0,
        depthMillimeters: Int = 1_000,
        principalX: Double = 1.5,
        principalY: Double = 0.5,
        cameraXMeters: Double = 0.0,
    ): VisibilityDepthObservation {
        val samples = listOf(VisibilityDepthSample(sampleX, 0, depthMillimeters, 255))
        val pose = identityVisibilityGridTransform().also { it[12] = cameraXMeters }
        return VisibilityDepthObservation(
            ownership = cut,
            frame = VisibilityObservationFrame(
                VisibilityObservationSource.SYNTHETIC_DEPTH, timestamp, timestamp, timestamp,
                "synthetic-camera", true, "landscape_right_x_right_y_down_v1",
                VisibilityCameraPose.copyOf(pose),
                VisibilityCameraIntrinsics(4, 3, 2.0, 1.0, principalX, principalY),
                VisibilityDepthCapability.RAW_DEPTH,
            ),
            samples = samples,
            sourceRejectedSamples = 0,
            payloadBytes = VisibilityDepthObservation.DEPTH_FIXED_BYTES +
                samples.size * VisibilityDepthObservation.DEPTH_SAMPLE_BYTES,
        )
    }

    private fun normalEvidence(supportId: Int, positive: Boolean): FeatureFusionEvidence {
        val cameraX = if (positive) 1_020 else -980
        return FeatureFusionEvidence(
            0.02, 0.02, 0.02, 2, supportId,
            FeatureNormalEvidence(0, 0, 0, 20, 20, 20, cameraX, 20, 20, 32_767),
        )
    }

    private fun rendererOwnership() = VisibilityObservationOwnership(
        sessionId = "01".repeat(16), sessionGeneration = 1,
        captureGroupId = "02".repeat(16), groupGeneration = 1, coverageEpoch = 1,
        arSessionIdentity = "03".repeat(16), viewInstanceId = "04".repeat(16), viewGeneration = 1,
        nativeStreamToken = "05".repeat(16), workerBindingToken = "06".repeat(16),
        bindingGeneration = 1, lifecycleSequence = 1, operationGeneration = 1,
        groupFrame = VisibilityGroupFrame.copyOf(
            identityVisibilityGridTransform(), identityVisibilityGridTransform(), 1_000, 100_000,
        ),
    )

    private fun rebuildCut(
        ownership: VisibilityObservationOwnership,
        geometryRevision: Long,
        lineageRevision: Long,
    ) = CommittedGeometryCut(
        ownership = ownership,
        transactionId = 0,
        baseGeometryRevision = 0,
        geometryRevision = geometryRevision,
        lineageRevision = lineageRevision,
        reset = true,
        upserts = emptyList(),
        removedSurfaceIds = LongArray(0),
    )

    private fun row(id: Long, x: Int) = CommittedGeometryRow(
        surfaceId = id,
        voxel = Voxel(x, 0, 0),
        packedNormal = 0,
        normalConfidence = 0,
        lineageCount = 0,
    )

    private fun startRequest() = ControlRequest(
        ControlOperation.START, 0, uuid(1), uuid(20), uuid(40), 3, 4, 5, 0,
        StartRequestCodecV2.defaultPayload(),
    )

    private fun uuid(seed: Int): Uuid {
        val bytes = ByteArray(16) { (seed + it).toByte() }
        bytes[6] = 0x40; bytes[8] = 0x80.toByte()
        return Uuid(bytes)
    }

    private fun strip(bytes: ByteArray, qualifier: ByteArray): ByteArray {
        assertArrayEquals(qualifier, bytes.copyOfRange(0, qualifier.size))
        return bytes.copyOfRange(qualifier.size, bytes.size)
    }

    private fun await(condition: () -> Boolean) {
        repeat(100) { if (condition()) return else Thread.sleep(5) }
        assertTrue(condition())
    }

    private fun budget(directory: File) = StorageBudgetCoordinatorV2(
        File(directory, "visibility-grid-canonical-surface-runtime"),
        StorageBudgetPolicyV2(64L * 1024L * 1024L, 0),
        JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
    ) { 128L * 1024L * 1024L }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T

    private fun chargedPhysical(directory: File, coordinator: StorageBudgetCoordinatorV2) =
        File(directory, "visibility-grid-canonical-surface-runtime").listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in setOf("reservations-v2", "reclaims-v2") }
            .sumOf { group -> group.listFiles().orEmpty().sumOf(CoordinatorStorageBudget(coordinator)::allocatedBytes) }

    private fun resources(
        directory: File,
        coordinator: StorageBudgetCoordinatorV2,
    ): (SurfaceGroup) -> CanonicalRuntimeResources = { group ->
        CanonicalRuntimeResources.open(directory, group, coordinator)
    }
}
