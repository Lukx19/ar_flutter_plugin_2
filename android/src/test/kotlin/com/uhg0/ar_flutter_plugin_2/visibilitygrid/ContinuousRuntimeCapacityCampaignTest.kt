package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import com.uhg0.ar_flutter_plugin_2.proposal08.CommittedBaselineAuthority
import com.uhg0.ar_flutter_plugin_2.proposal08.ControlCodec
import com.uhg0.ar_flutter_plugin_2.proposal08.ControlOperation
import com.uhg0.ar_flutter_plugin_2.proposal08.ControlRequest
import com.uhg0.ar_flutter_plugin_2.proposal08.PacketCodec
import com.uhg0.ar_flutter_plugin_2.proposal08.StartRequestCodecV2
import com.uhg0.ar_flutter_plugin_2.proposal08.TransactionResponseProfileV1
import com.uhg0.ar_flutter_plugin_2.proposal08.TransactionChunkFrameV1
import com.uhg0.ar_flutter_plugin_2.proposal08.TransactionResponseCodecV1
import com.uhg0.ar_flutter_plugin_2.proposal08.Uuid
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openjdk.jol.info.GraphLayout

/** Complete canonical surface proof through the same mapper, v6, V2 and renderer seams used at runtime. */
class ContinuousRuntimeCapacityCampaignTest {
    @Test
    fun `diagnostic records ordinary verification work at three six and twelve batches`() {
        val observations = mutableListOf<BatchVerification>()
        var opens = 0L
        var pages = 0L
        var checksumBytes = 0L
        CanonicalCowGenerationTestHooks.onVerifiedOpen = { observation ->
            opens++
            pages += observation.verifiedPages
            checksumBytes += observation.currentChecksumBytes
        }
        CanonicalRuntimeCurrentTestHooks.onAuthenticatedBorrow = { checksumBytes += it }
        val ownershipByThread = ConcurrentHashMap<Long, MutableList<CanonicalAdjacentOwnershipObservation>>()
        CanonicalActivationTestHooks.onAdjacentOwnership = { observation ->
            ownershipByThread.computeIfAbsent(Thread.currentThread().id) { mutableListOf() } += observation
        }
        try {
            listOf(3_600, 7_200, 14_400).forEachIndexed { index, target ->
                val root = Files.createTempDirectory("canonical-surface-continuous-verification-$target").toFile()
                try {
                    runCampaign(
                        root, 2270 + index, verifyProtocol = false, ownershipByThread,
                        surfaceTarget = target,
                        verificationSnapshot = { Triple(opens, pages, checksumBytes) },
                        verificationBatches = observations,
                    )
                } finally { root.deleteRecursively() }
            }
            println("CANONICAL_SURFACE_VERIFICATION_WORK " + observations.joinToString())
            val firstCreates = observations.groupBy { it.surfaceTarget }.values.map { it.first() }
            val ordinaryLater = observations.groupBy { it.surfaceTarget }.values.flatMap { it.drop(1) }
            assertTrue(firstCreates.all { it.generationOpens == 3L && it.verifiedPages == 60L })
            assertTrue(ordinaryLater.all { it.generationOpens == 1L && it.verifiedPages == 20L })
            // Ordinary later work authenticates only the fixed-size new
            // generation/current; it never reopens a historical generation.
            assertTrue(ordinaryLater.all { it.currentChecksumBytes in 225_781L..225_782L })
        } finally {
            CanonicalCowGenerationTestHooks.onVerifiedOpen = null
            CanonicalRuntimeCurrentTestHooks.onAuthenticatedBorrow = null
            CanonicalActivationTestHooks.onAdjacentOwnership = null
        }
    }

    @Test
    fun `narrow campaign proves later proportionality and acknowledged worker reopen`() {
        val root = Files.createTempDirectory("canonical-surface-continuous-narrow").toFile()
        val ownershipByThread = ConcurrentHashMap<Long, MutableList<CanonicalAdjacentOwnershipObservation>>()
        CanonicalActivationTestHooks.onAdjacentOwnership = { observation ->
            ownershipByThread.computeIfAbsent(Thread.currentThread().id) { mutableListOf() } += observation
        }
        try {
            val result = runCampaign(root, 2299, verifyProtocol = true, ownershipByThread, surfaceTarget = 3_600)
            assertEquals(32, result.workerCurrentHash.size)
        } finally {
            CanonicalActivationTestHooks.onAdjacentOwnership = null
            root.deleteRecursively()
        }
    }

    @Test
    fun `continuous runtime reaches exact capacity and reopens the same bounded cut`() {
        val firstRoot = Files.createTempDirectory("canonical-surface-continuous-a").toFile()
        val secondRoot = Files.createTempDirectory("canonical-surface-continuous-b").toFile()
        val campaigns = Executors.newFixedThreadPool(2)
        val ownershipByThread = ConcurrentHashMap<Long, MutableList<CanonicalAdjacentOwnershipObservation>>()
        CanonicalActivationTestHooks.onAdjacentOwnership = { observation ->
            ownershipByThread.computeIfAbsent(Thread.currentThread().id) { mutableListOf() } += observation
        }
        try {
            val firstFuture = campaigns.submit<CampaignResult> {
                runCampaign(firstRoot, 2301, verifyProtocol = true, ownershipByThread)
            }
            val secondFuture = campaigns.submit<CampaignResult> {
                runCampaign(secondRoot, 2302, verifyProtocol = false, ownershipByThread)
            }
            campaigns.shutdown()
            assertTrue(
                "both exact-capacity campaign lanes complete within the test-harness deadline",
                campaigns.awaitTermination(3, TimeUnit.HOURS),
            )
            val firstResult = runCatching { firstFuture.get() }
            val secondResult = runCatching { secondFuture.get() }
            val failures = listOfNotNull(firstResult.exceptionOrNull(), secondResult.exceptionOrNull())
            if (failures.isNotEmpty()) {
                failures.drop(1).forEach(failures.first()::addSuppressed)
                throw failures.first()
            }
            val first = firstResult.getOrThrow()
            val second = secondResult.getOrThrow()

            assertArrayEquals(first.rootHash, second.rootHash)
            assertArrayEquals(first.sourceHash, second.sourceHash)
            assertArrayEquals(first.canonicalSelectionHash, second.canonicalSelectionHash)
            assertArrayEquals(first.rendererSelectionHash, second.rendererSelectionHash)
            assertArrayEquals(first.workerCurrentHash, second.workerCurrentHash)
            assertEquals(first.portableCompleteBytes, second.portableCompleteBytes)
            assertEquals(first.directoryBytes, second.directoryBytes)
            assertEquals(first.committedPhysicalBytes, second.committedPhysicalBytes)
        } finally {
            CanonicalActivationTestHooks.onAdjacentOwnership = null
            campaigns.shutdownNow()
            assertTrue(campaigns.awaitTermination(5, TimeUnit.MINUTES))
            firstRoot.deleteRecursively()
            secondRoot.deleteRecursively()
        }
    }

    private fun runCampaign(
        root: File,
        viewId: Int,
        verifyProtocol: Boolean,
        ownershipByThread: ConcurrentHashMap<Long, MutableList<CanonicalAdjacentOwnershipObservation>>,
        surfaceTarget: Int = SURFACES,
        verificationSnapshot: (() -> Triple<Long, Long, Long>)? = null,
        verificationBatches: MutableList<BatchVerification>? = null,
    ): CampaignResult {
        require(surfaceTarget in V2_FEATURE_SAMPLE_CAPACITY * 3..SURFACES)
        val associationTarget = surfaceTarget * 2
        val expectedRendererRows = minOf(surfaceTarget, RENDERER_ROWS)
        val coordinator = coordinator(root)
        val messenger = MethodTestMessenger()
        val baselineAuthority = CommittedBaselineAuthority()
        val deterministicLifecycleSequence = java.util.concurrent.atomic.AtomicLong()
        val lifecycleSequenceAllocator = { deterministicLifecycleSequence.incrementAndGet() }
        val binding = VisibilityGridV2Binding(
            messenger, viewId, baselineAuthority, bindingGenerationSeed = 101,
            lifecycleSequenceAllocator = lifecycleSequenceAllocator, postToMain = { it() },
        )
        val executor = DirectExecutorService()
        var resources: CanonicalRuntimeResources? = null
        var rendered: CoveragePointRenderSnapshot? = null
        var maximumObservedRendererHandoff = 0L
        var maximumDirtyRendererRows = 0
        var lastDirtyRendererRows = 0
        val renderer = NativeRendererProjection(render = { snapshot, _ ->
            rendered = snapshot
            if (snapshot != null) maximumObservedRendererHandoff =
                maxOf(maximumObservedRendererHandoff, snapshot.ownershipReceipt().portableBytes)
            lastDirtyRendererRows = snapshot?.update?.spans?.sumOf { it.positions.size / 3 } ?: 0
            maximumDirtyRendererRows = maxOf(
                maximumDirtyRendererRows,
                lastDirtyRendererRows,
            )
        })
        val integration = VisibilityGridIntegration(
            binding = binding,
            ownership = binding::currentObservationOwnership,
            directory = root,
            resourcesForGroup = { group ->
                CanonicalRuntimeResources.open(root, group, coordinator).also { resources = it }
            },
            renderer = renderer,
            executor = executor,
            ownsExecutor = false,
        )
        val ownershipObservations = ownershipByThread.computeIfAbsent(Thread.currentThread().id) { mutableListOf() }
        try {
            val stream = start(binding, messenger, viewId)
            var sequence = 1L
            assertEquals(2, exchange(messenger, viewId, stream, sequence++, Ack.ZERO).response.messageKind)
            assertEquals(4, exchange(messenger, viewId, stream, sequence++, Ack.ZERO).response.messageKind)
            assertEquals(0, exchange(messenger, viewId, stream, sequence++, Ack(1, 1, 1)).response.messageKind)
            val cut = requireNotNull(binding.currentObservationOwnership())

            var previousAck = Ack(1, 1, 1)
            var maximumCurrentBytes = 0
            var lastWorkerHash = ByteArray(0)
            var materialBatches = 0
            var created = 0
            var maximumLaterCanonicalBytesPerRow = 0L
            var maximumLaterPersistenceBytesPerRow = 0L
            while (created < surfaceTarget) {
                val verificationBefore = verificationSnapshot?.invoke()
                val count = minOf(V2_FEATURE_SAMPLE_CAPACITY, surfaceTarget - created)
                val groupDirectory = File(root, "visibility-grid-canonical-surface-runtime").listFiles().orEmpty()
                    .singleOrNull { it.isDirectory && it.name !in COORDINATOR_FILES }
                val persistenceBefore = groupDirectory?.listFiles().orEmpty().associate { it.name to CoordinatorStorageBudget(coordinator).allocatedBytes(it) }
                lastDirtyRendererRows = 0
                integration.admitFeature(observation(cut, materialBatches + 10L, created, count, 0))
                val receipt = integration.integrationReceipt()
                assertEquals(
                    "batch=$materialBatches created=$created",
                    "pendingAck",
                    receipt.status,
                )
                assertEquals(if (materialBatches == 0) "CREATE" else "FEATURE_BATCH", receipt.canonicalOperation)
                assertEquals(previousAck.geometry + 1, receipt.geometryRevision)
                assertEquals(previousAck.lineage, receipt.lineageRevision)
                assertEquals(minOf(created + count, RENDERER_ROWS), receipt.rendererRows)
                maximumCurrentBytes = maxOf(maximumCurrentBytes, receipt.canonicalBytes)
                if (materialBatches > 0) {
                    assertEquals(minOf(count, maxOf(0, RENDERER_ROWS - created)), lastDirtyRendererRows)
                    val encodedLimit = 4_096L + count * 256L
                    assertTrue("later encoded batch=$materialBatches bytes=${receipt.canonicalBytes} limit=$encodedLimit", receipt.canonicalBytes <= encodedLimit)
                    maximumLaterCanonicalBytesPerRow = maxOf(
                        maximumLaterCanonicalBytesPerRow,
                        (receipt.canonicalBytes + count - 1L) / count,
                    )
                }

                val current = requireNotNull(resources).owner().activationState()?.current
                    as CanonicalActivationCurrent.Receipt
                val beforeHash = requireNotNull(resources).owner().activationState()?.cut?.rootHash?.toByteArray()
                val delivery = exchange(messenger, viewId, stream, sequence, previousAck)
                assertEquals(2, delivery.response.messageKind)
                assertEquals(receipt.transactionId, delivery.response.transactionId)
                assertEquals(receipt.geometryRevision, delivery.response.targetGeometryRevision)
                assertEquals(receipt.lineageRevision, delivery.response.targetLineageRevision)
                if (verifyProtocol) {
                    val replay = exchange(messenger, viewId, stream, sequence, previousAck)
                    assertArrayEquals(delivery.raw, replay.raw)
                }
                sequence++
                val workerBytes = ByteArrayOutputStream()
                repeat(delivery.response.chunkCount) {
                    val chunk = exchange(messenger, viewId, stream, sequence++, previousAck)
                    assertEquals(3, chunk.response.messageKind)
                    assertEquals(it, chunk.response.chunkIndex)
                    val frame = TransactionResponseCodecV1.decodeFrame(chunk.response)
                        as TransactionChunkFrameV1
                    workerBytes.write(frame.value.bytes)
                }
                val commit = exchange(messenger, viewId, stream, sequence++, previousAck)
                assertEquals(4, commit.response.messageKind)
                TransactionResponseCodecV1.decodeFrame(commit.response)
                assertArrayEquals(current.identity.canonicalHash.toByteArray(), testSha256(workerBytes.toByteArray()))
                lastWorkerHash = testSha256(workerBytes.toByteArray())
                assertArrayEquals(beforeHash, requireNotNull(resources).owner().activationState()?.cut?.rootHash?.toByteArray())
                previousAck = Ack(receipt.transactionId, receipt.geometryRevision, receipt.lineageRevision)
                assertEquals(0, exchange(messenger, viewId, stream, sequence++, previousAck).response.messageKind)
                assertEquals("acknowledged", integration.integrationReceipt().status)
                if (materialBatches > 0) {
                    val selectedGroup = requireNotNull(groupDirectory)
                    val persistenceAfter = selectedGroup.listFiles().orEmpty().associate { it.name to CoordinatorStorageBudget(coordinator).allocatedBytes(it) }
                    val addedPersistence = persistenceAfter.entries
                        .filter { (name, _) -> name !in persistenceBefore }
                        .sumOf(Map.Entry<String, Long>::value)
                    val persistenceLimit = 131_072L + count * 512L
                    assertTrue("later persistence batch=$materialBatches bytes=$addedPersistence limit=$persistenceLimit", addedPersistence <= persistenceLimit)
                    maximumLaterPersistenceBytesPerRow = maxOf(
                        maximumLaterPersistenceBytesPerRow,
                        (addedPersistence + count - 1L) / count,
                    )
                }
                if (verificationBefore != null) {
                    val after = requireNotNull(verificationSnapshot).invoke()
                    verificationBatches?.add(BatchVerification(
                        surfaceTarget, materialBatches + 1,
                        after.first - verificationBefore.first,
                        after.second - verificationBefore.second,
                        after.third - verificationBefore.third,
                    ))
                }
                created += count
                materialBatches++
            }
            assertTrue(materialBatches > 2)
            assertTrue(ownershipObservations.any { it.stage == CanonicalAdjacentOwnershipStage.ADMISSION })
            assertTrue(ownershipObservations.all { it.liveStoreCount == 0 })

            // A second observation for every retained surface reaches the exact
            // association cap. Confidence remains in the same canonical band,
            // so these changed-input batches correctly publish no transaction.
            val verificationBeforeRefinement = verificationSnapshot?.invoke()
            var refined = 0
            while (refined < surfaceTarget) {
                val count = minOf(V2_FEATURE_SAMPLE_CAPACITY, surfaceTarget - refined)
                integration.admitFeature(observation(cut, materialBatches + refined / V2_FEATURE_SAMPLE_CAPACITY + 20L, refined, count, surfaceTarget))
                assertEquals("nonMaterialRetained", integration.integrationReceipt().status)
                assertEquals(previousAck.transaction, integration.integrationReceipt().transactionId)
                refined += count
            }
            verificationBeforeRefinement?.let { assertEquals(it, requireNotNull(verificationSnapshot).invoke()) }

            val kernel = privateField<FeatureFusionKernel>(integration, "kernel")
            assertEquals(surfaceTarget, privateInt(kernel, "surfaceCount"))
            assertEquals(associationTarget, privateInt(kernel, "associationCount"))
            val activeResources = requireNotNull(resources)
            val finalState = requireNotNull(activeResources.owner().activationState())
            assertEquals(surfaceTarget, finalState.cut.liveSurfaceCount)
            assertEquals(materialBatches + 1L, finalState.cut.geometryRevision)
            assertEquals(1L, finalState.cut.lineageRevision)
            assertTrue(finalState.currentState is CanonicalCurrentState.Acknowledged)

            val fullKeys = rendererKeys(activeResources)
            assertEquals(surfaceTarget, fullKeys.size)
            val canonicalSelection = fullKeys.sorted().take(expectedRendererRows).toLongArray()
            val rendererKeys = requireNotNull(rendered).keys.sortedArray()
            assertEquals(expectedRendererRows, rendererKeys.size)
            assertArrayEquals(canonicalSelection, rendererKeys)

            var storage: CompactStorageReceipt? = null
            activeResources.withCurrent { view ->
                storage = view.allocatedStorageReceipt()
            }
            val scalarMemory = requireNotNull(activeResources.retainedScalarMemoryReceipt())
            val kernelBytes = kernel.resourceReceipt().assignedTupleShareBytes.toLong()
            val allocated = requireNotNull(storage)
            val rendererRebuildBytes = RENDERER_ROWS.toLong() * Long.SIZE_BYTES
            val rendererBytes = VisibilityGridRendererState.ownedStorageBytes(RENDERER_ROWS).toLong() +
                rendererRebuildBytes
            val fullRendererHandoff = RendererSnapshotOwnershipReceipt.fullResync(RENDERER_ROWS).portableBytes
            val sparseRendererHandoff = RendererSnapshotOwnershipReceipt.maximumSparse(RENDERER_ROWS).portableBytes
            val rendererHandoffBytes = maxOf(fullRendererHandoff, sparseRendererHandoff)
            assertTrue(maximumObservedRendererHandoff <= rendererHandoffBytes)
            val ownerMemory = integration.portableOwnerMemoryReceipt()
            val verificationProofBytes = activeResources.retainedCurrentProofBytes()
            val maximumOperationBytes = ownershipObservations.maxOf { observation ->
                maxOf(
                    observation.retainedPlanBytes + observation.writerScratchBytes,
                    observation.constructionPeakBytes,
                )
            }
            val currentHandoffBytes = maximumCurrentBytes.toLong() * 2L +
                TransactionResponseProfileV1.ordinary.responseCeilingBytes.toLong()
            val sharedPhaseBytes = maxOf(
                currentHandoffBytes, allocated.directoryBytes, maximumOperationBytes,
            )
            val portableCompleteBytes = kernelBytes + scalarMemory.portableBytes + ownerMemory.portableBytes + rendererBytes +
                rendererHandoffBytes + verificationProofBytes + sharedPhaseBytes
            assertTrue(maximumCurrentBytes in 1..CanonicalActivationResources.MAX_CURRENT_BYTES)
            assertTrue(allocated.directoryBytes <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue(maximumOperationBytes <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue(sharedPhaseBytes <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue(rendererRebuildBytes <= 1L * 1024L * 1024L)
            assertTrue(verificationProofBytes in 1L..(1L * 1024L * 1024L))
            assertTrue(
                "portable complete=$portableCompleteBytes scalar=${scalarMemory.portableBytes} " +
                    "kernel=$kernelBytes owners=${ownerMemory.portableBytes} v6Allocated=${allocated.allocatedBytes} " +
                    "renderer=$rendererBytes rendererRebuild=$rendererRebuildBytes handoff=$rendererHandoffBytes " +
                    "verificationProof=$verificationProofBytes " +
                    "current=$maximumCurrentBytes currentHandoff=$currentHandoffBytes directory=${allocated.directoryBytes} " +
                    "operation=$maximumOperationBytes shared=$sharedPhaseBytes",
                portableCompleteBytes <= CompactCanonicalStore.C17_TOTAL_BYTES,
            )
            assertTrue(maximumDirtyRendererRows <= V2_FEATURE_SAMPLE_CAPACITY)
            assertTrue(maximumLaterCanonicalBytesPerRow in 1..256L)
            assertTrue(maximumLaterPersistenceBytesPerRow in 1..1_024L)

            // The JOL graph is diagnostic only. The portable modeled/encoded
            // receipts above are normative and include the kernel reservation,
            // v6 arrays, renderer storage and the maximum shared operation phase.
            val completeLayout = GraphLayout.parseInstance(integration, binding, coordinator)
            val jolBytes = completeLayout.totalSize()
            // This diagnostic intentionally includes test callbacks/messenger and therefore
            // Gradle/JUnit/class-loader infrastructure. Portable acceptance above instead
            // names only strongly reachable production owners and exact bounded buffers.
            assertTrue("JOL complete graph=$jolBytes", jolBytes <= CompactCanonicalStore.C17_TOTAL_BYTES)

            val beforeRefusal = finalState.cut
            if (surfaceTarget == SURFACES) {
                val before = verificationSnapshot?.invoke()
                integration.admitFeature(observation(cut, 10_000, SURFACES, 1, ASSOCIATIONS))
                assertEquals("kernelRefused", integration.integrationReceipt().status)
                assertEquals(beforeRefusal, activeResources.owner().activationState()?.cut)
                assertEquals(previousAck.transaction, integration.integrationReceipt().transactionId)
                before?.let { assertEquals(it, requireNotNull(verificationSnapshot).invoke()) }
            }

            val verificationBeforeRemoval = verificationSnapshot?.invoke()
            val removal = activeResources.withCurrent { view ->
                activeResources.owner().prepareAdjacentMutation(
                    view,
                    CanonicalFeatureBatchCommand(
                        commandId = "removal-retains-durable-row",
                        expectedGeometryRevision = view.cut.geometryRevision,
                        expectedLineageRevision = view.cut.lineageRevision,
                        changes = listOf(FeatureFusionChange.Removal(1, 0, 0)),
                    ),
                )
            }
            assertTrue(removal is CanonicalMutationPreparation.NoOp)
            verificationBeforeRemoval?.let { assertEquals(it, requireNotNull(verificationSnapshot).invoke()) }
            assertEquals(beforeRefusal, activeResources.owner().activationState()?.cut)
            assertEquals(0, exchange(messenger, viewId, stream, sequence, previousAck).response.messageKind)

            val chargedPhysicalBytesAtCut = chargedPhysical(File(root, "visibility-grid-canonical-surface-runtime"), coordinator)
            val committedBytesAtCut = coordinator.committedBytes()
            assertEquals(chargedPhysicalBytesAtCut, committedBytesAtCut)
            assertEquals(0L, coordinator.reservedBytes())

            val group = SurfaceGroup(cut.captureGroupId)
            val rootHash = beforeRefusal.rootHash.toByteArray()
            val sourceHash = beforeRefusal.sourceHash.toByteArray()
            val canonicalSelectionHash = hashKeys(canonicalSelection)
            val rendererSelectionHash = hashKeys(rendererKeys)
            assertArrayEquals(canonicalSelectionHash, rendererSelectionHash)
            assertEquals(32, lastWorkerHash.size)

            integration.close()
            binding.dispose()

            // Reopen through the ordinary runtime module. The harmless retained
            // feature opens v6 and triggers bounded 512-row renderer resync; it
            // does not publish because it matches the durable canonical row.
            val reopenedMessenger = MethodTestMessenger()
            val reopenedBinding = VisibilityGridV2Binding(
                reopenedMessenger, viewId, baselineAuthority, bindingGenerationSeed = 102,
                lifecycleSequenceAllocator = lifecycleSequenceAllocator, postToMain = { it() },
            )
            var reopenedResources: CanonicalRuntimeResources? = null
            val reopenedRenders = mutableListOf<CoveragePointRenderSnapshot>()
            val reopenedExecutor = DirectExecutorService()
            val reopened = VisibilityGridIntegration(
                reopenedBinding, reopenedBinding::currentObservationOwnership, root,
                resourcesForGroup = { selected ->
                    assertEquals(group, selected)
                    CanonicalRuntimeResources.open(root, selected, coordinator).also { reopenedResources = it }
                },
                renderer = NativeRendererProjection(render = { snapshot, _ -> snapshot?.let(reopenedRenders::add) }),
                executor = reopenedExecutor,
                ownsExecutor = false,
            )
            try {
                val reopenedStream = start(reopenedBinding, reopenedMessenger, viewId)
                var reopenedSequence = 1L
                val reopenedAck = Ack(0, previousAck.geometry, previousAck.lineage)
                val acknowledged = exchange(reopenedMessenger, viewId, reopenedStream, reopenedSequence++, reopenedAck)
                assertEquals(
                    "acknowledged reopen response tx=${acknowledged.response.transactionId} " +
                        "base=${acknowledged.response.baseGeometryRevision} target=${acknowledged.response.targetGeometryRevision} " +
                        "lineage=${acknowledged.response.targetLineageRevision} chunks=${acknowledged.response.chunkCount}",
                    0,
                    acknowledged.response.messageKind,
                )
                assertEquals(0, acknowledged.response.transactionId)
                assertEquals(previousAck.geometry, acknowledged.response.targetGeometryRevision)
                assertEquals(previousAck.lineage, acknowledged.response.targetLineageRevision)
                assertTrue(reopenedRenders.isEmpty())
                val reopenedCut = requireNotNull(reopenedBinding.currentObservationOwnership())
                reopened.admitFeature(observation(reopenedCut, 20_000, 0, 1, 0))
                assertEquals("nonMaterialRetained", reopened.integrationReceipt().status)
                val reopenedState = requireNotNull(reopenedResources).owner().activationState()?.cut
                assertEquals(beforeRefusal, reopenedState)
                assertEquals(1, reopenedRenders.size)
                assertArrayEquals(rendererKeys, reopenedRenders.single().keys.sortedArray())
                assertArrayEquals(rootHash, reopenedState?.rootHash?.toByteArray())
                assertArrayEquals(sourceHash, reopenedState?.sourceHash?.toByteArray())
                assertTrue(
                    requireNotNull(reopenedResources).owner().activationState()?.current is
                        CanonicalActivationCurrent.None,
                )

                if (surfaceTarget < SURFACES) {
                    reopened.admitFeature(observation(reopenedCut, 30_000, surfaceTarget, 1, associationTarget))
                    val next = reopened.integrationReceipt()
                    assertEquals("pendingAck", next.status)
                    assertEquals("FEATURE_BATCH", next.canonicalOperation)
                    assertEquals(1, next.transactionId)
                    assertEquals(previousAck.geometry + 1, next.geometryRevision)
                    assertEquals(previousAck.lineage, next.lineageRevision)
                    val begin = exchange(reopenedMessenger, viewId, reopenedStream, reopenedSequence++, reopenedAck)
                    assertEquals(2, begin.response.messageKind)
                    assertEquals(previousAck.geometry, begin.response.baseGeometryRevision)
                    assertEquals(next.geometryRevision, begin.response.targetGeometryRevision)
                    val bytes = ByteArrayOutputStream()
                    repeat(begin.response.chunkCount) { index ->
                        val chunk = exchange(reopenedMessenger, viewId, reopenedStream, reopenedSequence++, reopenedAck)
                        assertEquals(3, chunk.response.messageKind)
                        assertEquals(index, chunk.response.chunkIndex)
                        bytes.write((TransactionResponseCodecV1.decodeFrame(chunk.response) as TransactionChunkFrameV1).value.bytes)
                    }
                    assertEquals(4, exchange(reopenedMessenger, viewId, reopenedStream, reopenedSequence++, reopenedAck).response.messageKind)
                    val nextCurrent = requireNotNull(reopenedResources).owner().activationState()?.current
                        as CanonicalActivationCurrent.Receipt
                    assertArrayEquals(nextCurrent.identity.canonicalHash.toByteArray(), testSha256(bytes.toByteArray()))
                    val nextAck = Ack(next.transactionId, next.geometryRevision, next.lineageRevision)
                    assertEquals(0, exchange(reopenedMessenger, viewId, reopenedStream, reopenedSequence, nextAck).response.messageKind)
                    assertEquals("acknowledged", reopened.integrationReceipt().status)
                    assertEquals(surfaceTarget + 1, reopenedRenders.last().count)
                }
            } finally {
                reopened.close()
                reopenedBinding.dispose()
                reopenedExecutor.shutdown()
            }

            val chargedPhysicalBytes = chargedPhysical(File(root, "visibility-grid-canonical-surface-runtime"), coordinator)
            val committedBytes = coordinator.committedBytes()
            assertEquals(chargedPhysicalBytes, committedBytes)
            println(
                "CANONICAL_SURFACE_CONTINUOUS_RUNTIME=surfaces=$surfaceTarget associations=$associationTarget " +
                    "materialBatches=$materialBatches kernel=$kernelBytes " +
                    "scalar=${scalarMemory.portableBytes} owners=${ownerMemory.portableBytes} " +
                    "v6Allocated=${allocated.allocatedBytes} renderer=$rendererBytes handoff=$rendererHandoffBytes " +
                    "verificationProof=$verificationProofBytes " +
                    "currentMax=$maximumCurrentBytes directory=${allocated.directoryBytes} " +
                    "operationMax=$maximumOperationBytes sharedPhase=$sharedPhaseBytes " +
                    "portableComplete=$portableCompleteBytes jol=$jolBytes " +
                    "committedAtCut=$committedBytesAtCut chargedPhysicalAtCut=$chargedPhysicalBytesAtCut " +
                    "committed=$committedBytes chargedPhysical=$chargedPhysicalBytes",
            )
            return CampaignResult(
                rootHash, sourceHash, canonicalSelectionHash, rendererSelectionHash, lastWorkerHash,
                portableCompleteBytes, allocated.directoryBytes, chargedPhysicalBytes,
            )
        } finally {
            integration.close()
            binding.dispose()
            executor.shutdown()
            coordinator.close()
        }
    }

    private fun observation(
        cut: VisibilityObservationOwnership,
        timestamp: Long,
        firstSurface: Int,
        count: Int,
        supportOffset: Int,
    ): VisibilityFeatureObservation {
        val samples = VisibilityFeatureObservation.copySamples(List(count) { offset ->
            val surface = firstSurface + offset
            VisibilityFeatureSample(
                id = supportOffset + surface,
                xWorld = (surface + 1) * 0.1 + 0.02,
                yWorld = 0.0,
                zWorld = 0.0,
                confidence = 1.0,
            )
        })
        return VisibilityFeatureObservation(
            ownership = cut,
            frame = VisibilityObservationFrame(
                VisibilityObservationSource.SYNTHETIC_FEATURE,
                timestamp,
                timestamp,
                timestamp,
                "capacity-camera",
                true,
                "landscape_right_x_right_y_down_v1",
                VisibilityCameraPose.copyOf(identityVisibilityGridTransform()),
                VisibilityCameraIntrinsics(16, 12, 10.0, 10.0, 8.0, 6.0),
                VisibilityDepthCapability.UNSUPPORTED,
            ),
            samples = samples,
            sourceRejectedSamples = 0,
            payloadBytes = VisibilityFeatureObservation.FEATURE_FIXED_BYTES +
                samples.size * VisibilityFeatureObservation.FEATURE_SAMPLE_BYTES,
        )
    }

    private fun rendererKeys(resources: CanonicalRuntimeResources): LongArray {
        return requireNotNull(resources.readAllRendererKeys())
    }

    private data class Stream(val qualifier: ByteArray, val token: Long)
    private data class Ack(val transaction: Long, val geometry: Long, val lineage: Long) {
        companion object { val ZERO = Ack(0, 0, 0) }
    }
    private data class Exchange(val response: PacketCodec.Response, val raw: ByteArray)

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
        ack: Ack,
    ): Exchange {
        val request = stream.qualifier + PacketCodec.encodeRequest(
            PacketCodec.Request(
                0, stream.token, ack.transaction, ack.geometry, ack.lineage, 0,
                TransactionResponseProfileV1.ordinary.responseCeilingBytes,
                emptyList(), byteArrayOf(), sequence,
            ),
        )
        val reply = RecordingBinaryReply()
        messenger.send("visibility_surface_stream_$viewId", ByteBuffer.wrap(request), reply)
        assertTrue("worker reply timed out at request sequence $sequence", reply.completed.await(30, TimeUnit.SECONDS))
        val raw = strip(requireNotNull(reply.bytes), stream.qualifier)
        return Exchange(PacketCodec.decodeResponse(raw), raw)
    }

    private fun startRequest() = ControlRequest(
        ControlOperation.START, 0, uuid(1), uuid(20), uuid(40), 3, 4, 5, 0,
        StartRequestCodecV2.defaultPayload(),
    )

    private fun uuid(seed: Int): Uuid {
        val bytes = ByteArray(16) { (seed + it).toByte() }
        bytes[6] = 0x40
        bytes[8] = 0x80.toByte()
        return Uuid(bytes)
    }

    private fun strip(bytes: ByteArray, qualifier: ByteArray): ByteArray {
        assertArrayEquals(qualifier, bytes.copyOfRange(0, qualifier.size))
        return bytes.copyOfRange(qualifier.size, bytes.size)
    }

    private fun coordinator(root: File) = StorageBudgetCoordinatorV2(
        File(root, "visibility-grid-canonical-surface-runtime"),
        StorageBudgetPolicyV2(256L * 1024L * 1024L, 0),
        JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
    ) { 512L * 1024L * 1024L }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T

    private fun privateInt(owner: Any, name: String): Int = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.getInt(owner)

    private fun hashKeys(keys: LongArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(Long.SIZE_BYTES)
        keys.forEach { key ->
            bytes.clear()
            bytes.putLong(key)
            digest.update(bytes.array())
        }
        return digest.digest()
    }

    /** Mirrors coordinator tree charging while excluding uncharged group-container directories. */
    private fun chargedPhysical(runtime: File, coordinator: StorageBudgetCoordinatorV2): Long {
        val budget = CoordinatorStorageBudget(coordinator)
        return runtime.listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in COORDINATOR_FILES }
            .sumOf { group -> group.listFiles().orEmpty().sumOf(budget::allocatedBytes) }
    }

    private class DirectExecutorService : AbstractExecutorService() {
        private var shutdown = false
        override fun execute(command: Runnable) {
            check(!shutdown)
            command.run()
        }
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
        override fun isShutdown() = shutdown
        override fun isTerminated() = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = shutdown
    }

    private data class CampaignResult(
        val rootHash: ByteArray,
        val sourceHash: ByteArray,
        val canonicalSelectionHash: ByteArray,
        val rendererSelectionHash: ByteArray,
        val workerCurrentHash: ByteArray,
        val portableCompleteBytes: Long,
        val directoryBytes: Long,
        val committedPhysicalBytes: Long,
    )

    private data class BatchVerification(
        val surfaceTarget: Int,
        val batch: Int,
        val generationOpens: Long,
        val verifiedPages: Long,
        val currentChecksumBytes: Long,
    )

    private companion object {
        const val SURFACES = 100_000
        const val ASSOCIATIONS = 200_000
        const val RENDERER_ROWS = VisibilityGridRendererState.CENTROID_PRESENTATION_CAPACITY
        val COORDINATOR_FILES = setOf("ledger-v2", "reservations-v2", "reclaims-v2")
    }
}
