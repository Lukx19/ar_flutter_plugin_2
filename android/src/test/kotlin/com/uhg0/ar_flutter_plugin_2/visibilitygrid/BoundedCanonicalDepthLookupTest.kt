package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedCanonicalDepthLookupTest {
    @Test
    fun `unwarmed bounded current refuses without request work`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-unwarmed").toFile()
        val coordinator = coordinator(root)
        val resources = CanonicalRuntimeResources.open(root, SurfaceGroup("0".repeat(32)), coordinator)
        try {
            val result = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(1, 1, 1, 1, 1, 1),
            ) { error("an unwarmed lifecycle must not expose a current") }
            assertEquals(
                BoundedCanonicalLookupResult.Refused(
                    BoundedCanonicalLookupReason.CURRENT_UNAVAILABLE,
                    BoundedCanonicalLookupReceipt(0, 0, 0, 0, false),
                ),
                result,
            )
            assertEquals(null, resources.retainedCompleteCurrentMemoryReceipt())
        } finally {
            resources.close(); coordinator.close(); root.deleteRecursively()
        }
    }

    @Test
    fun `warm current serves lookup and evidence preparation without canonical reopen`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-warm").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup("9".repeat(32))
        val resources = CanonicalRuntimeResources.open(root, group, coordinator)
        try {
            assertTrue(resources.openInitial(committedEmptyBaseline("binding", group.value, 1, 1, 1)) is SurfaceOwnershipOpenResult.Opened)
            val generationZeroReceipt = requireNotNull(resources.completeCurrentLeaseReceipt())
            assertEquals(0L, generationZeroReceipt.cowProofAndIndexBytes)
            assertEquals(2_373_056L, generationZeroReceipt.featurePlanningRouteBytes)
            assertEquals(
                Math.addExact(
                    generationZeroReceipt.baseRetained.residentTotalBytes,
                    generationZeroReceipt.featurePlanningRouteBytes,
                ),
                generationZeroReceipt.retainedTotalBytes,
            )
            val planningMemory = requireNotNull(resources.featurePlanningMemoryReceipt(1))
            assertEquals(2_373_056L, planningMemory.routeRetainedBytes)
            assertEquals(1_572_960L, planningMemory.lifecycleConstructionScratchBytes)
            assertEquals(200L, planningMemory.borrowCacheBytes)
            assertEquals(108L, planningMemory.maximumRouteDeltaBytes)
            assertTrue(planningMemory.routeRetainedBytes <= 4L * 1024L * 1024L)
            val create = resources.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "warm-create", 1, 1,
                    listOf(DepthEvidenceChange.Create(CanonicalTarget(
                        voxel = Voxel(1, 0, 0), normalOctX = 1, normalOctY = 1, normalConfidence = 200,
                    ))),
                ),
            ) as CanonicalMutationPreparation.Prepared
            assertTrue(resources.commitAdjacent(create.mutation) is CanonicalAdjacentCommitResult.Committed)
            val cut = requireNotNull(resources.owner().activationState()).cut
            val current = requireNotNull(resources.owner().activationState()).current as CanonicalActivationCurrent.Receipt
            assertTrue(resources.owner().acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(current.identity.commandHash, cut.geometryRevision, cut.lineageRevision),
            ) is CanonicalAcknowledgementResult.Acknowledged)
            var cowOpens = 0
            CanonicalCowGenerationTestHooks.onVerifiedOpen = { cowOpens++ }

            repeat(2) {
                val lookup = resources.withBoundedCurrent(
                    BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 1, 0, 2, 32_768),
                ) { it.findSurfaceAt(Voxel(1, 0, 0)) }
                val completed = lookup as BoundedCanonicalLookupResult.Completed
                val value = requireNotNull(completed.value)
                assertEquals(Voxel(1, 0, 0), value.addressedVoxel)
                assertEquals(SurfaceId(1), value.surface.id)
            }
            val zeroBudget = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 1, 0, 0, 0),
            ) { it.findSurfaceAt(Voxel(1, 0, 0)) }
            assertEquals(
                BoundedCanonicalLookupReason.LIMIT_EXHAUSTED,
                (zeroBudget as BoundedCanonicalLookupResult.Refused).reason,
            )
            val prepared = resources.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "warm-second", cut.geometryRevision, cut.lineageRevision,
                    listOf(DepthEvidenceChange.Create(CanonicalTarget(
                        voxel = Voxel(2, 0, 0), normalOctX = 1, normalOctY = 1, normalConfidence = 200,
                    ))),
                ),
            ) as CanonicalMutationPreparation.Prepared
            assertTrue(resources.commitAdjacent(prepared.mutation) is CanonicalAdjacentCommitResult.Committed)
            val successor = requireNotNull(resources.owner().activationState()).cut
            assertEquals(cut.geometryRevision + 1, successor.geometryRevision)

            // Exactly one verified open belongs to the newly staged successor;
            // no prior generation is reopened during prepare or commit.
            assertEquals(1, cowOpens)
            val leaseReceipt = requireNotNull(resources.completeCurrentLeaseReceipt())
            assertEquals(resources.retainedCurrentProofBytes(), leaseReceipt.cowProofAndIndexBytes)
            assertTrue(leaseReceipt.cowProofAndIndexBytes > 0)
            assertEquals(
                Math.addExact(
                    Math.addExact(
                        leaseReceipt.baseRetained.residentTotalBytes,
                        leaseReceipt.cowProofAndIndexBytes,
                    ),
                    leaseReceipt.featurePlanningRouteBytes,
                ),
                leaseReceipt.retainedTotalBytes,
            )
            assertTrue(leaseReceipt.lifecycleOpenPeakBytes >= leaseReceipt.retainedTotalBytes)
        } finally {
            CanonicalCowGenerationTestHooks.onVerifiedOpen = null
            resources.close()
            assertEquals(null, resources.retainedCompleteCurrentMemoryReceipt())
            assertEquals(null, resources.completeCurrentLeaseReceipt())
            coordinator.close(); root.deleteRecursively()
        }
    }

    @Test
    fun `addressed request work ignores unrelated retained generation history`() {
        val shallow = runtimeWithCommitHistory(1, "7".repeat(32))
        val deep = runtimeWithCommitHistory(6, "8".repeat(32))
        try {
            var cowOpens = 0
            CanonicalCowGenerationTestHooks.onVerifiedOpen = { cowOpens++ }
            fun lookup(runtime: CanonicalRuntimeResources, voxel: Voxel): BoundedCanonicalLookupResult<AddressedCanonicalSurface?> {
                val cut = requireNotNull(runtime.owner().activationState()).cut
                return runtime.withBoundedCurrent(
                    BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 1, 0, 2, 32_768),
                ) { it.findSurfaceAt(voxel) }
            }

            val shallowResult = lookup(shallow.runtime, Voxel(0, 0, 0))
            val deepResult = lookup(deep.runtime, Voxel(5, 0, 0))
            val shallowCompleted = shallowResult as BoundedCanonicalLookupResult.Completed
            val deepCompleted = deepResult as BoundedCanonicalLookupResult.Completed
            assertEquals(shallowCompleted.receipt, deepCompleted.receipt)
            assertEquals(Voxel(0, 0, 0), requireNotNull(shallowCompleted.value).addressedVoxel)
            assertEquals(SurfaceId(1), shallowCompleted.value.surface.id)
            assertEquals(Voxel(5, 0, 0), requireNotNull(deepCompleted.value).addressedVoxel)
            assertEquals(SurfaceId(6), deepCompleted.value.surface.id)
            assertEquals(0, cowOpens)
        } finally {
            CanonicalCowGenerationTestHooks.onVerifiedOpen = null
            shallow.close(); deep.close()
        }
    }

    @Test
    fun `feature planning routed work is identical across shallow and deep history`() {
        val deepGenerationCount = 3
        val shallow = runtimeWithCommitHistory(1, "1".repeat(32))
        val deep = runtimeWithCommitHistory(deepGenerationCount, "2".repeat(32))
        try {
            fun reads(runtime: CanonicalRuntimeResources, occupied: Voxel): List<Pair<String, CowReadWork>> {
                val work = mutableListOf<Pair<String, CowReadWork>>()
                CanonicalRuntimeCurrentTestHooks.onFeatureRouteRead = { kind, receipt -> work += kind to receipt }
                requireNotNull(runtime.withFeaturePlanningCurrent(2) { view ->
                    assertEquals(null, view.findByVoxel(Voxel(99_999, 0, 0)))
                    val row = requireNotNull(view.findByVoxel(occupied))
                    val source = requireNotNull((view.readSourceById(row.id) as CanonicalPageRead.Complete).value)
                    row.id to source.allocationFingerprint
                })
                return work
            }

            val shallowWork = reads(shallow.runtime, Voxel(0, 0, 0))
            val deepWork = reads(deep.runtime, Voxel(deepGenerationCount - 1, 0, 0))
            assertEquals(shallowWork, deepWork)
            assertEquals(listOf("row", "source"), shallowWork.map { it.first })
            assertTrue(shallowWork.all { (_, work) -> work.pages in 1L..2L && work.bytes > 0L })
        } finally {
            CanonicalRuntimeCurrentTestHooks.onFeatureRouteRead = null
            shallow.close(); deep.close()
        }
    }

    @Test
    fun `feature planning refuses a routed provider read failure without treating occupancy as empty`() {
        val durable = runtimeWithCommitHistory(1, "3".repeat(32))
        try {
            CanonicalRuntimeCurrentTestHooks.failFeatureRouteRead = { it == "row" }
            assertEquals(null, durable.runtime.withFeaturePlanningCurrent(1) { view ->
                assertEquals(null, view.findByVoxel(Voxel(0, 0, 0)))
                "must be discarded by the poisoned borrow"
            })
            CanonicalRuntimeCurrentTestHooks.failFeatureRouteRead = null
            assertEquals(SurfaceId(1), durable.runtime.withFeaturePlanningCurrent(1) { view ->
                requireNotNull(view.findByVoxel(Voxel(0, 0, 0))).id
            })
        } finally {
            CanonicalRuntimeCurrentTestHooks.failFeatureRouteRead = null
            durable.close()
        }
    }

    @Test
    fun `feature planning refuses an occupied routed read before zero page and byte caps perform work`() {
        val durable = runtimeWithCommitHistory(1, "4".repeat(32))
        try {
            val work = mutableListOf<CowReadWork>()
            CanonicalRuntimeCurrentTestHooks.onFeatureRouteRead = { _, receipt -> work += receipt }
            assertEquals(null, durable.runtime.withFeaturePlanningCurrent(1, 0, 0) { view ->
                view.findByVoxel(Voxel(0, 0, 0))
            })
            assertTrue(work.isEmpty())
        } finally {
            CanonicalRuntimeCurrentTestHooks.onFeatureRouteRead = null
            durable.close()
        }
    }

    @Test
    fun `feature planning uses replacement canonical provenance instead of remapped slot history`() {
        val durable = runtimeWithCommitHistory(1, "6".repeat(32))
        var resources = durable.runtime
        try {
            var cut = requireNotNull(resources.owner().activationState()).cut
            val oldSource = requireNotNull(resources.withCurrent {
                (it.readSourceById(SurfaceId(1)) as CanonicalPageRead.Complete).value
            })
            val replacement = requireNotNull(resources.withCurrent {
                resources.owner().prepareAdjacentMutation(
                    it,
                    CanonicalTransactionCommand(
                        "replace-provenance", CanonicalOperation.REPLACEMENT,
                        cut.geometryRevision, cut.lineageRevision,
                        listOf(SurfaceId(1)),
                        listOf(CanonicalTarget(null, Voxel(0, 0, 0), 9, 7, 220)),
                    ),
                )
            }) as CanonicalMutationPreparation.Prepared
            assertEquals(96L, replacement.mutation.work.removedRouteBytes)
            assertEquals(108L, replacement.mutation.work.routeDeltaConstructionBytes)
            assertTrue(replacement.mutation.work.constructionPeakBytes >=
                replacement.mutation.work.retainedPlanBytes + replacement.mutation.work.routeDeltaConstructionBytes)
            var routeDelta: CanonicalFeatureRouteDeltaMemoryReceipt? = null
            CanonicalRuntimeCurrentTestHooks.onFeatureRouteDeltaPrepared = { routeDelta = it }
            assertTrue(resources.commitAdjacent(replacement.mutation) is CanonicalAdjacentCommitResult.Committed)
            assertEquals(CanonicalFeatureRouteDeltaMemoryReceipt(1, 1, 108L), routeDelta)
            CanonicalRuntimeCurrentTestHooks.onFeatureRouteDeltaPrepared = null
            cut = requireNotNull(resources.owner().activationState()).cut
            val replacementCurrent = requireNotNull(resources.owner().activationState()).current as CanonicalActivationCurrent.Receipt
            assertTrue(resources.owner().acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(
                    replacementCurrent.identity.commandHash, cut.geometryRevision, cut.lineageRevision,
                ),
            ) is CanonicalAcknowledgementResult.Acknowledged)
            val replacementId = SurfaceId(2)
            val replacementSource = requireNotNull(resources.withCurrent {
                (it.readSourceById(replacementId) as CanonicalPageRead.Complete).value
            })
            assertTrue(replacementSource.allocationFingerprint != oldSource.allocationFingerprint)
            resources.close()
            resources = CanonicalRuntimeResources.open(
                durable.root, SurfaceGroup("6".repeat(32)), durable.coordinator,
            )
            assertTrue(resources.reopen() is SurfaceOwnershipOpenResult.Opened)
            cut = requireNotNull(resources.owner().activationState()).cut

            val candidate = FeatureFusionCandidate(
                0, 0, 0, 2, 2,
                listOf(FeatureNormalCandidate(0, 0, 0, FeatureNormalFace.PRIMARY, -8, 6, 230)),
            )
            val change = FeatureFusionChange.Upsert(
                candidate,
                canonicalCorrelation = CanonicalFeatureCorrelation(
                    replacementId, oldSource.allocationFingerprint,
                    oldSource.packedNormal, oldSource.normalConfidence,
                ),
            )
            val planned = requireNotNull(resources.withFeaturePlanningCurrent(1) { view ->
                assertEquals(replacementId, requireNotNull(view.findByVoxel(Voxel(0, 0, 0))).id)
                val row = requireNotNull(view.findById(replacementId))
                val source = requireNotNull((view.readSourceById(replacementId) as CanonicalPageRead.Complete).value)
                assertEquals(replacementSource.packedNormal, row.packedNormal)
                assertEquals(replacementSource.allocationFingerprint, source.allocationFingerprint)
                resources.owner().prepareAdjacentMutation(
                    view,
                    CanonicalFeatureBatchCommand(
                        "refine-replacement-provenance", cut.geometryRevision, cut.lineageRevision,
                        listOf(change),
                    ),
                )
            }) as CanonicalMutationPreparation.Prepared
            assertTrue(resources.commitAdjacent(planned.mutation) is CanonicalAdjacentCommitResult.Committed)
            val finalSource = requireNotNull(resources.withCurrent {
                (it.readSourceById(replacementId) as CanonicalPageRead.Complete).value
            })
            assertEquals(replacementSource.allocationFingerprint, finalSource.allocationFingerprint)
        } finally {
            CanonicalRuntimeCurrentTestHooks.onFeatureRouteDeltaPrepared = null
            resources.close()
            durable.close()
        }
    }

    @Test
    fun `feature routing removes the current relocated voxel when replacement commits`() {
        val durable = runtimeWithCommitHistory(1, "9".repeat(32))
        val resources = durable.runtime
        try {
            fun commitAndAcknowledge(command: CanonicalTransactionCommand) {
                val prepared = requireNotNull(resources.withCurrent {
                    resources.owner().prepareAdjacentMutation(it, command)
                }) as CanonicalMutationPreparation.Prepared
                assertTrue(resources.commitAdjacent(prepared.mutation) is CanonicalAdjacentCommitResult.Committed)
                val activation = requireNotNull(resources.owner().activationState())
                val current = activation.current as CanonicalActivationCurrent.Receipt
                assertTrue(resources.owner().acknowledgeCanonicalCurrent(
                    CanonicalAcknowledgement(
                        current.identity.commandHash,
                        activation.cut.geometryRevision,
                        activation.cut.lineageRevision,
                    ),
                ) is CanonicalAcknowledgementResult.Acknowledged)
            }

            var cut = requireNotNull(resources.owner().activationState()).cut
            commitAndAcknowledge(CanonicalTransactionCommand(
                "relocate-route", CanonicalOperation.RELOCATION,
                cut.geometryRevision, cut.lineageRevision,
                listOf(SurfaceId(1)),
                listOf(CanonicalTarget(SurfaceId(1), Voxel(10, 0, 0), 9, 7, 220)),
            ))
            cut = requireNotNull(resources.owner().activationState()).cut
            commitAndAcknowledge(CanonicalTransactionCommand(
                "replace-relocated-route", CanonicalOperation.REPLACEMENT,
                cut.geometryRevision, cut.lineageRevision,
                listOf(SurfaceId(1)),
                listOf(CanonicalTarget(null, Voxel(10, 0, 0), 9, 7, 220)),
            ))
            assertEquals(SurfaceId(2), resources.withFeaturePlanningCurrent(2) { view ->
                assertEquals(null, view.findByVoxel(Voxel(0, 0, 0)))
                requireNotNull(view.findByVoxel(Voxel(10, 0, 0))).id
            })
        } finally {
            durable.close()
        }
    }

    @Test
    fun `feature planning after reopen refines the depth owned voxel identity`() {
        val durable = runtimeWithCommitHistory(1, "5".repeat(32))
        durable.runtime.close()
        val resources = CanonicalRuntimeResources.open(
            durable.root, SurfaceGroup("5".repeat(32)), durable.coordinator,
        )
        try {
            assertTrue(resources.reopen() is SurfaceOwnershipOpenResult.Opened)
            val cut = requireNotNull(resources.owner().activationState()).cut
            val change = FeatureFusionChange.Upsert(FeatureFusionCandidate(
                0, 0, 0, 2, 2,
                listOf(FeatureNormalCandidate(0, 0, 0, FeatureNormalFace.PRIMARY, -9, 7, 230)),
            ))
            val prepared = requireNotNull(resources.withFeaturePlanningCurrent(1) { view ->
                resources.owner().prepareAdjacentMutation(
                    view,
                    CanonicalFeatureBatchCommand(
                        "reopen-feature-on-depth", cut.geometryRevision, cut.lineageRevision,
                        listOf(change),
                    ),
                )
            }) as CanonicalMutationPreparation.Prepared
            assertTrue(resources.commitAdjacent(prepared.mutation) is CanonicalAdjacentCommitResult.Committed)
            val finalCut = requireNotNull(resources.owner().activationState()).cut
            assertEquals(1, finalCut.liveSurfaceCount)
            assertEquals(2L, finalCut.nextSurfaceIdHighWater)
            resources.withCurrent { view ->
                assertEquals(SurfaceId(1), requireNotNull(view.findByVoxel(Voxel(0, 0, 0))).id)
                assertEquals(null, view.findById(SurfaceId(2)))
            }
        } finally {
            resources.close(); durable.close()
        }
    }

    @Test
    fun `negative lookup cap is refused before borrowing current`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-invalid").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup("a".repeat(32))
        val resources = CanonicalRuntimeResources.open(root, group, coordinator)
        try {
            resources.openInitial(committedEmptyBaseline("binding", group.value, 1, 1, 1))

            val result = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(1, 1, -1, 0, 0, 0),
            ) { error("invalid request must not borrow current") }

            assertTrue(result is BoundedCanonicalLookupResult.Refused)
            val refused = result as BoundedCanonicalLookupResult.Refused
            assertEquals(BoundedCanonicalLookupReason.INVALID_REQUEST, refused.reason)
            assertEquals(
                BoundedCanonicalLookupReceipt(0, 0, 0, 0, false),
                refused.receipt,
            )
        } finally {
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `authenticated current maps one addressed surface without enumeration`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-one-row").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup("b".repeat(32))
        val resources = CanonicalRuntimeResources.open(root, group, coordinator)
        try {
            resources.openInitial(committedEmptyBaseline("binding", group.value, 1, 1, 1))
            val created = resources.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "create-one", 1, 1, listOf(
                        DepthEvidenceChange.Create(
                            CanonicalTarget(voxel = Voxel(2, 3, 4), normalOctX = 7, normalOctY = -3, normalConfidence = 211),
                        ),
                    ),
                ),
            ) as CanonicalMutationPreparation.Prepared
            resources.commitAdjacent(created.mutation)

            val cut = requireNotNull(resources.owner().activationState()).cut
            val result = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 1, 0, Int.MAX_VALUE, Long.MAX_VALUE),
            ) { view ->
                view.findSurfaceAt(Voxel(2, 3, 4))
            }
            val completed = result as BoundedCanonicalLookupResult.Completed
            assertEquals(
                AddressedCanonicalSurface(
                    Voxel(2, 3, 4),
                    DepthCanonicalSurface(SurfaceId(1), Voxel(2, 3, 4), 0x07fd, 211, 0),
                ),
                completed.value,
            )
            assertEquals(BoundedCanonicalLookupReceipt(1, 0, 2, 32_768, false), completed.receipt)
        } finally {
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `direct lookup limit suppresses the callback result`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-direct-limit").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup("c".repeat(32))
        val resources = CanonicalRuntimeResources.open(root, group, coordinator)
        try {
            resources.openInitial(committedEmptyBaseline("binding", group.value, 1, 1, 1))
            val created = resources.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "create-limit", 1, 1,
                    listOf(DepthEvidenceChange.Create(CanonicalTarget(voxel = Voxel(2, 3, 4), normalOctX = 1, normalOctY = 1, normalConfidence = 200))),
                ),
            ) as CanonicalMutationPreparation.Prepared
            resources.commitAdjacent(created.mutation)
            val cut = requireNotNull(resources.owner().activationState()).cut

            val result = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 0, 0, 0, 0),
            ) { view -> view.findSurfaceById(SurfaceId(1)) }

            val refused = result as BoundedCanonicalLookupResult.Refused
            assertEquals(BoundedCanonicalLookupReason.LIMIT_EXHAUSTED, refused.reason)
            assertEquals(BoundedCanonicalLookupReceipt(0, 0, 0, 0, true), refused.receipt)
        } finally {
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `revision mismatch is typed and zero-work caps may complete`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-revision").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup("d".repeat(32))
        val resources = CanonicalRuntimeResources.open(root, group, coordinator)
        try {
            resources.openInitial(committedEmptyBaseline("binding", group.value, 1, 1, 1))

            val complete = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(1, 1, 0, 0, 0, 0),
            ) { it.revisionPair }
            assertEquals(
                BoundedCanonicalLookupResult.Completed(
                    CanonicalRevisionPair(1, 1),
                    BoundedCanonicalLookupReceipt(0, 0, 0, 0, false),
                ),
                complete,
            )

            val refused = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(2, 1, 0, 0, 0, 0),
            ) { error("revision mismatch must not borrow current") }
            assertEquals(BoundedCanonicalLookupReason.REVISION_CONFLICT, (refused as BoundedCanonicalLookupResult.Refused).reason)
            assertEquals(BoundedCanonicalLookupReceipt(0, 0, 0, 0, false), refused.receipt)
        } finally {
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing selected current is unavailable`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-unavailable").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup("e".repeat(32))
        val resources = CanonicalRuntimeResources.open(root, group, coordinator)
        try {
            val result = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(1, 1, 0, 0, 0, 0),
            ) { error("unavailable current must not borrow") }
            val refused = result as BoundedCanonicalLookupResult.Refused
            assertEquals(BoundedCanonicalLookupReason.CURRENT_UNAVAILABLE, refused.reason)
            assertEquals(BoundedCanonicalLookupReceipt(0, 0, 0, 0, false), refused.receipt)
        } finally {
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `ray visit charges ray cells without charging direct lookups`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-ray").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup("f".repeat(32))
        val resources = CanonicalRuntimeResources.open(root, group, coordinator)
        try {
            resources.openInitial(committedEmptyBaseline("binding", group.value, 1, 1, 1))
            val created = resources.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "create-ray", 1, 1,
                    listOf(DepthEvidenceChange.Create(CanonicalTarget(voxel = Voxel(1, 0, 0), normalOctX = 1, normalOctY = 1, normalConfidence = 200))),
                ),
            ) as CanonicalMutationPreparation.Prepared
            resources.commitAdjacent(created.mutation)
            val cut = requireNotNull(resources.owner().activationState()).cut
            val visited = mutableListOf<Voxel>()

            val result = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 0, 8, Int.MAX_VALUE, Long.MAX_VALUE),
            ) { view ->
                view.visitRayCells(DepthPointMm(0.0, 0.0, 0.0), DepthPointMm(250.0, 0.0, 0.0), 8) { voxel, _ ->
                    visited += voxel
                    true
                }
            }

            val completed = result as BoundedCanonicalLookupResult.Completed
            assertEquals(3, completed.value.visitedCells)
            assertEquals(listOf(Voxel(0, 0, 0), Voxel(1, 0, 0), Voxel(2, 0, 0)), visited)
            assertEquals(BoundedCanonicalLookupReceipt(0, 3, 2, 32_768, false), completed.receipt)
        } finally {
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `evidence preparation and bounded lookup survive runtime reopen`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-reopen").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup("a".repeat(32))
        val baseline = committedEmptyBaseline("binding", group.value, 1, 1, 1)
        val first = CanonicalRuntimeResources.open(root, group, coordinator)
        try {
            first.openInitial(baseline)
            val created = first.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "create-reopen", 1, 1,
                    listOf(DepthEvidenceChange.Create(CanonicalTarget(voxel = Voxel(3, 0, 0), normalOctX = 1, normalOctY = 1, normalConfidence = 200))),
                ),
            ) as CanonicalMutationPreparation.Prepared
            assertTrue(first.commitAdjacent(created.mutation) is CanonicalAdjacentCommitResult.Committed)
        } finally {
            first.close()
        }

        val reopened = CanonicalRuntimeResources.open(root, group, coordinator)
        try {
            assertTrue(reopened.reopen() is SurfaceOwnershipOpenResult.Opened)
            val cut = requireNotNull(reopened.owner().activationState()).cut
            val result = reopened.withBoundedCurrent(
                BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 1, 0, Int.MAX_VALUE, Long.MAX_VALUE),
            ) { view -> view.findSurfaceById(SurfaceId(1)) }
            val completed = result as BoundedCanonicalLookupResult.Completed
            assertEquals(SurfaceId(1), completed.value?.id)
            assertEquals(BoundedCanonicalLookupReceipt(1, 0, 1, 16_384, false), completed.receipt)

            val state = requireNotNull(reopened.owner().activationState())
            val current = state.current as CanonicalActivationCurrent.Receipt
            assertTrue(
                reopened.owner().acknowledgeCanonicalCurrent(
                    CanonicalAcknowledgement(current.identity.commandHash, cut.geometryRevision, cut.lineageRevision),
                ) is CanonicalAcknowledgementResult.Acknowledged,
            )
            val second = reopened.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "create-reopen-second", cut.geometryRevision, cut.lineageRevision,
                    listOf(DepthEvidenceChange.Create(CanonicalTarget(voxel = Voxel(4, 0, 0), normalOctX = 1, normalOctY = 1, normalConfidence = 200))),
                ),
            ) as CanonicalMutationPreparation.Prepared
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(1, CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())
            assertTrue(reopened.commitAdjacent(second.mutation) is CanonicalAdjacentCommitResult.Committed)
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeLeaseCount())
            assertEquals(0, CanonicalAuthorityLeaseRegistry.activeResidentStoreCount())
        } finally {
            reopened.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `ray limit suppresses truncated traversal result`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-ray-limit").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup("b".repeat(32))
        val resources = CanonicalRuntimeResources.open(root, group, coordinator)
        try {
            resources.openInitial(committedEmptyBaseline("binding", group.value, 1, 1, 1))
            val created = resources.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "create-ray-limit", 1, 1,
                    listOf(DepthEvidenceChange.Create(CanonicalTarget(voxel = Voxel(1, 0, 0), normalOctX = 1, normalOctY = 1, normalConfidence = 200))),
                ),
            ) as CanonicalMutationPreparation.Prepared
            resources.commitAdjacent(created.mutation)
            val cut = requireNotNull(resources.owner().activationState()).cut

            val result = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 0, 2, Int.MAX_VALUE, Long.MAX_VALUE),
            ) { view ->
                view.visitRayCells(DepthPointMm(0.0, 0.0, 0.0), DepthPointMm(250.0, 0.0, 0.0), 8) { _, _ -> true }
            }

            val refused = result as BoundedCanonicalLookupResult.Refused
            assertEquals(BoundedCanonicalLookupReason.LIMIT_EXHAUSTED, refused.reason)
            assertEquals(BoundedCanonicalLookupReceipt(0, 2, 2, 32_768, true), refused.receipt)
        } finally {
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `bounded COW composes touched pages across generations and refuses before the next page`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-cow-generations").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup("f".repeat(32))
        val resources = CanonicalRuntimeResources.open(root, group, coordinator)
        fun acknowledge(cut: CompactCanonicalCut) {
            val current = requireNotNull(resources.owner().activationState()).current as CanonicalActivationCurrent.Receipt
            assertTrue(
                resources.owner().acknowledgeCanonicalCurrent(
                    CanonicalAcknowledgement(current.identity.commandHash, cut.geometryRevision, cut.lineageRevision),
                ) is CanonicalAcknowledgementResult.Acknowledged,
            )
        }
        fun commit(commandId: String, cut: CompactCanonicalCut, change: DepthEvidenceChange): CompactCanonicalCut {
            val preparation = resources.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(commandId, cut.geometryRevision, cut.lineageRevision, listOf(change)),
            ) as CanonicalMutationPreparation.Prepared
            assertTrue(resources.commitAdjacent(preparation.mutation) is CanonicalAdjacentCommitResult.Committed)
            return requireNotNull(resources.owner().activationState()).cut
        }
        try {
            resources.openInitial(committedEmptyBaseline("binding", group.value, 1, 1, 1))
            var cut = requireNotNull(resources.owner().activationState()).cut
            cut = commit("cow-generation-one", cut, DepthEvidenceChange.Create(CanonicalTarget(voxel = Voxel(1, 0, 0), normalOctX = 1, normalOctY = 1, normalConfidence = 200)))
            acknowledge(cut)
            cut = commit("cow-generation-two", cut, DepthEvidenceChange.Create(CanonicalTarget(voxel = Voxel(2, 0, 0), normalOctX = 1, normalOctY = 1, normalConfidence = 200)))
            acknowledge(cut)
            cut = commit("cow-generation-three", cut, DepthEvidenceChange.Relocate(SurfaceId(1), CanonicalTarget(SurfaceId(1), Voxel(3, 0, 0), 1, 1, 200)))
            acknowledge(cut)
            cut = commit("cow-generation-four", cut, DepthEvidenceChange.Create(CanonicalTarget(voxel = Voxel(4, 0, 0), normalOctX = 1, normalOctY = 1, normalConfidence = 200)))

            val idResult = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 1, 0, Int.MAX_VALUE, Long.MAX_VALUE),
            ) { it.findSurfaceById(SurfaceId(1)) }
            assertEquals(Voxel(3, 0, 0), (idResult as BoundedCanonicalLookupResult.Completed).value?.voxel)
            assertEquals(BoundedCanonicalLookupReceipt(1, 0, 2, 32_768, false), idResult.receipt)

            val voxelResult = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 1, 0, Int.MAX_VALUE, Long.MAX_VALUE),
            ) { it.findSurfaceAt(Voxel(3, 0, 0)) }
            assertEquals(SurfaceId(1), (voxelResult as BoundedCanonicalLookupResult.Completed).value?.surface?.id)
            assertEquals(BoundedCanonicalLookupReceipt(1, 0, 4, 65_536, false), voxelResult.receipt)

            val refused = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 1, 0, 1, CanonicalCowGeneration.PAGE_BYTES.toLong()),
            ) { it.findSurfaceById(SurfaceId(1)) }
            assertEquals(BoundedCanonicalLookupReason.LIMIT_EXHAUSTED, (refused as BoundedCanonicalLookupResult.Refused).reason)
            assertEquals(BoundedCanonicalLookupReceipt(1, 0, 1, CanonicalCowGeneration.PAGE_BYTES.toLong(), true), refused.receipt)
        } finally {
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `stale runtime evidence preparation refuses without changing the cut`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-stale-batch").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup("c".repeat(32))
        val resources = CanonicalRuntimeResources.open(root, group, coordinator)
        try {
            resources.openInitial(committedEmptyBaseline("binding", group.value, 1, 1, 1))
            val before = requireNotNull(resources.owner().activationState()).cut
            val result = resources.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "stale-batch", before.geometryRevision + 1, before.lineageRevision,
                    listOf(DepthEvidenceChange.Create(CanonicalTarget(voxel = Voxel(7, 0, 0), normalOctX = 1, normalOctY = 1, normalConfidence = 200))),
                ),
            )
            assertEquals(CanonicalMutationRefusal.REVISION_CONFLICT, (result as CanonicalMutationPreparation.Refused).reason)
            assertEquals(before, requireNotNull(resources.owner().activationState()).cut)
        } finally {
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `selector authentication failure is reported as unavailable`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-auth-failure").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup("d".repeat(32))
        val resources = CanonicalRuntimeResources.open(root, group, coordinator)
        try {
            resources.openInitial(committedEmptyBaseline("binding", group.value, 1, 1, 1))
            val created = resources.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "create-auth-failure", 1, 1,
                    listOf(DepthEvidenceChange.Create(CanonicalTarget(voxel = Voxel(8, 0, 0), normalOctX = 1, normalOctY = 1, normalConfidence = 200))),
                ),
            ) as CanonicalMutationPreparation.Prepared
            resources.commitAdjacent(created.mutation)
            val cut = requireNotNull(resources.owner().activationState()).cut
            val selector = resources.groupDirectory.listFiles().orEmpty().single { it.name.endsWith(".selector") }
            assertTrue(selector.delete())

            val result = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 1, 0, Int.MAX_VALUE, Long.MAX_VALUE),
            ) { error("authentication failure must not borrow current") }
            assertEquals(BoundedCanonicalLookupReason.CURRENT_UNAVAILABLE, (result as BoundedCanonicalLookupResult.Refused).reason)
        } finally {
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `surface lookup reports exact outgoing lineage count`() {
        val root = Files.createTempDirectory("bounded-canonical-depth-lineage").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup("e".repeat(32))
        val resources = CanonicalRuntimeResources.open(root, group, coordinator)
        try {
            resources.openInitial(committedEmptyBaseline("binding", group.value, 1, 1, 1))
            val create = resources.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "create-lineage", 1, 1,
                    listOf(DepthEvidenceChange.Create(CanonicalTarget(voxel = Voxel(10, 0, 0), normalOctX = 1, normalOctY = 1, normalConfidence = 200))),
                ),
            ) as CanonicalMutationPreparation.Prepared
            resources.commitAdjacent(create.mutation)
            val firstState = requireNotNull(resources.owner().activationState())
            val firstCurrent = firstState.current as CanonicalActivationCurrent.Receipt
            assertTrue(
                resources.owner().acknowledgeCanonicalCurrent(
                    CanonicalAcknowledgement(firstCurrent.identity.commandHash, firstState.cut.geometryRevision, firstState.cut.lineageRevision),
                ) is CanonicalAcknowledgementResult.Acknowledged,
            )
            val relocation = resources.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "relocate-lineage", firstState.cut.geometryRevision, firstState.cut.lineageRevision,
                    listOf(DepthEvidenceChange.Relocate(SurfaceId(1), CanonicalTarget(SurfaceId(1), Voxel(11, 0, 0), 1, 1, 200))),
                ),
            ) as CanonicalMutationPreparation.Prepared
            resources.commitAdjacent(relocation.mutation)
            val cut = requireNotNull(resources.owner().activationState()).cut

            val result = resources.withBoundedCurrent(
                BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision, 1, 0, Int.MAX_VALUE, Long.MAX_VALUE),
            ) { view -> view.findSurfaceById(SurfaceId(1)) }
            val completed = result as BoundedCanonicalLookupResult.Completed
            assertEquals(1, completed.value?.lineageCount)
            assertEquals(BoundedCanonicalLookupReceipt(1, 0, 2, 32_768, false), completed.receipt)
        } finally {
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `addressed lookup work is independent of one hundred thousand unrelated rows`() {
        val small = durableRuntimeWithRows(1, "f".repeat(32))
        val large = durableRuntimeWithRows(100_000, "a".repeat(32))
        try {
            val smallCut = requireNotNull(small.runtime.owner().activationState()).cut
            val largeCut = requireNotNull(large.runtime.owner().activationState()).cut
            assertEquals(1, smallCut.liveSurfaceCount)
            assertEquals(100_000, largeCut.liveSurfaceCount)

            val smallResult = small.runtime.withBoundedCurrent(
                BoundedCanonicalLookupRequest(smallCut.geometryRevision, smallCut.lineageRevision, 1, 0, 0, 0),
            ) { it.findSurfaceAt(Voxel(0, 0, 0)) }
            val largeResult = large.runtime.withBoundedCurrent(
                BoundedCanonicalLookupRequest(largeCut.geometryRevision, largeCut.lineageRevision, 1, 0, 0, 0),
            ) { it.findSurfaceAt(Voxel(0, 0, 0)) }

            assertEquals(smallResult, largeResult)
            assertEquals(
                AddressedCanonicalSurface(
                    Voxel(0, 0, 0),
                    DepthCanonicalSurface(SurfaceId(1), Voxel(0, 0, 0), 0x0101, 200, 0),
                ),
                (smallResult as BoundedCanonicalLookupResult.Completed).value,
            )
            assertEquals(BoundedCanonicalLookupReceipt(1, 0, 0, 0, false), smallResult.receipt)
        } finally {
            small.close()
            large.close()
        }
    }

    @Test
    fun `page read cap refuses after exact underlying page work`() {
        val view = MeteredCanonicalLookupView(pageReadDelta = 1, byteReadDelta = 0)
        val bounded = BoundedCanonicalCurrentView(
            view,
            BoundedCanonicalLookupRequest(1, 1, 1, 0, 1, 128),
            100_000,
        )

        val result = bounded.result(bounded.findSurfaceById(SurfaceId(1)))

        assertTrue(result is BoundedCanonicalLookupResult.Refused)
        val refused = result as BoundedCanonicalLookupResult.Refused
        assertEquals(BoundedCanonicalLookupReason.LIMIT_EXHAUSTED, refused.reason)
        assertEquals(BoundedCanonicalLookupReceipt(1, 0, 1, 0, true), refused.receipt)
        assertEquals(1, view.lineageReadAttempts)
        assertEquals(0, view.lineageReadStarts)
    }

    @Test
    fun `byte read cap refuses after exact underlying byte work`() {
        val view = MeteredCanonicalLookupView(pageReadDelta = 0, byteReadDelta = 64)
        val bounded = BoundedCanonicalCurrentView(
            view,
            BoundedCanonicalLookupRequest(1, 1, 1, 0, 1, 100),
            100_000,
        )

        val result = bounded.result(bounded.findSurfaceById(SurfaceId(1)))

        assertTrue(result is BoundedCanonicalLookupResult.Refused)
        val refused = result as BoundedCanonicalLookupResult.Refused
        assertEquals(BoundedCanonicalLookupReason.LIMIT_EXHAUSTED, refused.reason)
        assertEquals(BoundedCanonicalLookupReceipt(1, 0, 0, 64, true), refused.receipt)
        assertEquals(1, view.lineageReadAttempts)
        assertEquals(0, view.lineageReadStarts)
    }

    @Test
    fun `lineage exceeding uint16 representation refuses without full enumeration`() {
        val view = MeteredCanonicalLookupView(
            pageReadDelta = 0,
            byteReadDelta = 0,
            lineageEdgeCount = 100_000,
        )
        val bounded = BoundedCanonicalCurrentView(
            view,
            BoundedCanonicalLookupRequest(1, 1, 1, 0, 0, 0),
            100_000,
        )

        val result = bounded.result(bounded.findSurfaceById(SurfaceId(1)))

        assertTrue(result is BoundedCanonicalLookupResult.Refused)
        val refused = result as BoundedCanonicalLookupResult.Refused
        assertEquals(BoundedCanonicalLookupReason.LINEAGE_UNREPRESENTABLE, refused.reason)
        assertEquals(BoundedCanonicalLookupReceipt(1, 0, 0, 0, false), refused.receipt)
        assertEquals(65_536, view.lineageEdgesObserved)
    }

    @Test
    fun `lineage read refusal is canonical failure without a partial surface`() {
        val view = MeteredCanonicalLookupView(
            pageReadDelta = 0,
            byteReadDelta = 0,
            lineageReadFailure = true,
        )
        val bounded = BoundedCanonicalCurrentView(
            view,
            BoundedCanonicalLookupRequest(1, 1, 1, 0, 0, 0),
            100_000,
        )

        val result = bounded.result(bounded.findSurfaceById(SurfaceId(1)))

        assertTrue(result is BoundedCanonicalLookupResult.Refused)
        val refused = result as BoundedCanonicalLookupResult.Refused
        assertEquals(BoundedCanonicalLookupReason.CANONICAL_READ_FAILURE, refused.reason)
        assertEquals(BoundedCanonicalLookupReceipt(1, 0, 0, 0, false), refused.receipt)
    }

    @Test
    fun `default bounded read refuses without entering unbounded delegate`() {
        val view = DefaultBoundedFallbackView()
        val bounded = BoundedCanonicalCurrentView(
            view,
            BoundedCanonicalLookupRequest(1, 1, 1, 0, 10, 100),
            100_000,
        )

        val result = bounded.result(bounded.findSurfaceById(SurfaceId(1)))

        assertTrue(result is BoundedCanonicalLookupResult.Refused)
        assertEquals(
            BoundedCanonicalLookupReason.CANONICAL_READ_FAILURE,
            (result as BoundedCanonicalLookupResult.Refused).reason,
        )
        assertEquals(BoundedCanonicalLookupReceipt(1, 0, 0, 0, false), result.receipt)
        assertEquals(0, view.unboundedCalls)

        val lineage = view.visitLineageBounded(SurfaceId(1), null, 10, 100) { true }
        assertEquals(
            CanonicalBoundedReadResult.Refused(CanonicalBoundedReadRefusal.CANONICAL_READ_FAILURE),
            lineage,
        )
        assertEquals(0, view.unboundedCalls)
    }

    @Test
    fun `bounded refusal propagates work spent before refusal`() {
        val view = MeteredCanonicalLookupView(
            pageReadDelta = 0,
            byteReadDelta = 0,
            lineageReadRefusalWork = CanonicalReadWork(0, 1, 0, 64),
        )
        val bounded = BoundedCanonicalCurrentView(
            view,
            BoundedCanonicalLookupRequest(1, 1, 1, 0, 4, 100),
            100_000,
        )

        val result = bounded.result(bounded.findSurfaceById(SurfaceId(1)))

        assertEquals(BoundedCanonicalLookupReason.LIMIT_EXHAUSTED, (result as BoundedCanonicalLookupResult.Refused).reason)
        assertEquals(BoundedCanonicalLookupReceipt(1, 0, 1, 64, true), result.receipt)
        assertEquals(0, view.lineageReadStarts)
    }

    private fun coordinator(root: File) = StorageBudgetCoordinatorV2(
        File(root, "visibility-grid-canonical-surface-runtime"),
        StorageBudgetPolicyV2(64L * 1024L * 1024L, 0),
        JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
    ) { 128L * 1024L * 1024L }

    private data class DurableRuntime(
        val root: File,
        val coordinator: StorageBudgetCoordinatorV2,
        val runtime: CanonicalRuntimeResources,
    ) : AutoCloseable {
        override fun close() {
            runtime.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    private fun durableRuntimeWithRows(rows: Int, groupValue: String): DurableRuntime {
        val root = Files.createTempDirectory("bounded-canonical-depth-$rows").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup(groupValue)
        val directory = File(
            File(root, "visibility-grid-canonical-surface-runtime"), group.value,
        ).also { require(it.mkdirs() || it.isDirectory) }
        val legacy = SurfaceOwnership.open(group, directory)
        val ownership = (legacy as SurfaceOwnershipOpenResult.Opened).ownership
        val candidates = List(rows) { index ->
            SurfaceCandidate(
                voxel = Voxel(index, 0, 0),
                normalOctX = 1,
                normalOctY = 1,
                normalConfidence = 200,
            )
        }
        assertTrue(
            ownership.apply(SurfaceOwnershipCommand("seed-$rows", candidates))
                is SurfaceOwnershipResult.Accepted,
        )
        ownership.close()

        val budget = CoordinatorStorageBudget(coordinator)
        assertTrue(
            CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget)
                is CompactCanonicalMigrationResult.Prepared,
        )
        val activation = CanonicalActivation.prepare(group, directory, budget)
        assertTrue(activation is CanonicalActivationPreparation.Prepared)
        assertTrue(
            SurfaceOwnership.activateV6(
                group, directory, budget,
                (activation as CanonicalActivationPreparation.Prepared).plan,
            ) is CanonicalActivationResult.Active,
        )
        val runtime = CanonicalRuntimeResources.open(root, group, coordinator)
        assertTrue(runtime.reopen() is SurfaceOwnershipOpenResult.Opened)
        return DurableRuntime(root, coordinator, runtime)
    }

    private fun runtimeWithCommitHistory(generations: Int, groupValue: String): DurableRuntime {
        val root = Files.createTempDirectory("bounded-canonical-history-$generations").toFile()
        val coordinator = coordinator(root)
        val group = SurfaceGroup(groupValue)
        val runtime = CanonicalRuntimeResources.open(root, group, coordinator)
        assertTrue(runtime.openInitial(committedEmptyBaseline("binding", group.value, 1, 1, 1)) is SurfaceOwnershipOpenResult.Opened)
        repeat(generations) { index ->
            val cut = requireNotNull(runtime.owner().activationState()).cut
            val prepared = runtime.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "history-$index", cut.geometryRevision, cut.lineageRevision,
                    listOf(DepthEvidenceChange.Create(CanonicalTarget(
                        voxel = Voxel(index, 0, 0), normalOctX = 1, normalOctY = 1, normalConfidence = 200,
                    ))),
                ),
            ) as CanonicalMutationPreparation.Prepared
            assertTrue(runtime.commitAdjacent(prepared.mutation) is CanonicalAdjacentCommitResult.Committed)
            val committedCut = requireNotNull(runtime.owner().activationState()).cut
            val current = requireNotNull(runtime.owner().activationState()).current as CanonicalActivationCurrent.Receipt
            assertTrue(
                runtime.owner().acknowledgeCanonicalCurrent(
                    CanonicalAcknowledgement(
                        current.identity.commandHash,
                        committedCut.geometryRevision,
                        committedCut.lineageRevision,
                    ),
                ) is CanonicalAcknowledgementResult.Acknowledged,
            )
        }
        return DurableRuntime(root, coordinator, runtime)
    }

    private class MeteredCanonicalLookupView(
        private val pageReadDelta: Long,
        private val byteReadDelta: Long,
        private val lineageEdgeCount: Int = 0,
        private val lineageReadFailure: Boolean = false,
        private val lineageReadRefusalWork: CanonicalReadWork? = null,
    ) : CanonicalStateView {
        private var pageReads = 0L
        private var bytesRead = 0L
        var lineageReadAttempts = 0
            private set
        var lineageReadStarts = 0
            private set
        var lineageEdgesObserved = 0
            private set

        override val cut = CompactCanonicalCut(
            SurfaceGroup("a".repeat(32)), CompactCanonicalStore.PROFILE,
            1, 1, 2, 1, 1, 1, 0, null,
            CanonicalReceiptBytes.EMPTY, CanonicalReceiptBytes.EMPTY,
        )

        override fun findById(id: SurfaceId): CompactSurface? {
            return CompactSurface(SurfaceId(1), Voxel(0, 0, 0), 0x0101, 200)
                .takeIf { id == it.id }
        }

        override fun findByVoxel(voxel: Voxel): CompactSurface? = null
        override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int) =
            CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: SurfaceId): CanonicalPageRead<PagedSource?> =
            CanonicalPageRead.Complete(null, 0, 0)
        override fun visitSourceSupport(
            target: SurfaceId,
            cursor: SourceSupportCursor?,
            sink: (PagedSupport) -> Boolean,
        ) = SourceSupportRead.Complete(0, null, 0, 0)
        override fun visitLineage(
            source: SurfaceId,
            cursor: LineageCursor?,
            sink: (LineageEdge) -> Boolean,
        ): LineageRead {
            return LineageRead.Complete(0, null)
        }

        override fun findByIdBounded(
            id: SurfaceId,
            maximumPageReads: Long,
            maximumBytesRead: Long,
        ): CanonicalBoundedReadResult<CompactSurface?> {
            if (!fits(maximumPageReads, maximumBytesRead)) {
                return CanonicalBoundedReadResult.Refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
            }
            pageReads += pageReadDelta
            bytesRead += byteReadDelta
            return CanonicalBoundedReadResult.Complete(
                findById(id), CanonicalReadWork(0, pageReadDelta, 0, byteReadDelta),
            )
        }

        override fun visitLineageBounded(
            source: SurfaceId,
            cursor: LineageCursor?,
            maximumPageReads: Long,
            maximumBytesRead: Long,
            sink: (LineageEdge) -> Boolean,
        ): CanonicalBoundedReadResult<LineageRead> {
            lineageReadAttempts++
            lineageReadRefusalWork?.let {
                pageReads += it.pageReads
                bytesRead += it.bytesRead
                return CanonicalBoundedReadResult.Refused(
                    CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED,
                    it,
                )
            }
            if (!fits(maximumPageReads, maximumBytesRead)) {
                return CanonicalBoundedReadResult.Refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
            }
            lineageReadStarts++
            pageReads += pageReadDelta
            bytesRead += byteReadDelta
            if (lineageReadFailure) {
                return CanonicalBoundedReadResult.Complete(
                    LineageRead.Refused(CompactCanonicalRefusal.CORRUPT),
                    CanonicalReadWork(0, pageReadDelta, 0, byteReadDelta),
                )
            }
            var delivered = 0
            for (index in 0 until lineageEdgeCount) {
                lineageEdgesObserved++
                if (!sink(LineageEdge(source, SurfaceId(index.toLong() + 2)))) break
                delivered++
            }
            val next = if (delivered < lineageEdgeCount) {
                LineageCursor(cut.rootHash, source, delivered)
            } else null
            return CanonicalBoundedReadResult.Complete(
                LineageRead.Complete(delivered, next),
                CanonicalReadWork(0, pageReadDelta, 0, byteReadDelta),
            )
        }

        private fun fits(maximumPageReads: Long, maximumBytesRead: Long) =
            pageReadDelta in 0..maximumPageReads && byteReadDelta in 0..maximumBytesRead
        override fun retainedMemoryReceipt() = CompactRetainedMemoryReceipt(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        override fun allocatedStorageReceipt() = CompactStorageReceipt(0, 0, 0, 0, 0)
        override fun readWorkReceipt() = CanonicalReadWork(0, pageReads, 0, bytesRead)
        override fun close() = Unit
    }

    private class DefaultBoundedFallbackView : CanonicalStateView {
        var unboundedCalls = 0

        override val cut = CompactCanonicalCut(
            SurfaceGroup("a".repeat(32)), CompactCanonicalStore.PROFILE,
            1, 1, 2, 1, 1, 1, 0, null,
            CanonicalReceiptBytes.EMPTY, CanonicalReceiptBytes.EMPTY,
        )

        override fun findById(id: SurfaceId): CompactSurface? {
            unboundedCalls++
            return CompactSurface(SurfaceId(1), Voxel(0, 0, 0), 0x0101, 200)
        }

        override fun findByVoxel(voxel: Voxel): CompactSurface? {
            unboundedCalls++
            return null
        }

        override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int) =
            CompactPage(emptyList(), null, 0)

        override fun readSourceById(id: SurfaceId): CanonicalPageRead<PagedSource?> =
            CanonicalPageRead.Complete(null, 0, 0)

        override fun visitSourceSupport(
            target: SurfaceId,
            cursor: SourceSupportCursor?,
            sink: (PagedSupport) -> Boolean,
        ) = SourceSupportRead.Complete(0, null, 0, 0)

        override fun visitLineage(
            source: SurfaceId,
            cursor: LineageCursor?,
            sink: (LineageEdge) -> Boolean,
        ): LineageRead {
            unboundedCalls++
            return LineageRead.Complete(0, null)
        }

        override fun retainedMemoryReceipt() = CompactRetainedMemoryReceipt(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )

        override fun allocatedStorageReceipt() = CompactStorageReceipt(0, 0, 0, 0, 0)
        override fun close() = Unit
    }
}
