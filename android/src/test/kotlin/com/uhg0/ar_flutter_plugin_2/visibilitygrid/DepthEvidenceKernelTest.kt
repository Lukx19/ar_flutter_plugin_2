package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthEvidenceKernelTest {
    @Test
    fun `translated group unprojects the locked depth fixture`() {
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView()
        val batch = batch(
            timestamp = 1,
            groupFrame = frame(),
            groupFromCamera = translation(0.02, 0.02, 0.02),
            samples = listOf(VisibilityDepthSample(2, 1, 1_000, 200)),
        )

        val result = kernel.prepare(batch, view)

        assertTrue(result is DepthEvidenceResult.Accepted)
        val accepted = result as DepthEvidenceResult.Accepted
        assertEquals(0, accepted.changes.size)
        assertEquals(1, accepted.receipt.acceptedSamples)
        assertEquals(Voxel(2, 0, -10), view.visitedEndpoints.single())
    }

    @Test
    fun `duplicate endpoint samples count as one occupied observation`() {
        val kernel = DepthEvidenceKernel()
        val duplicateSamples = List(32) { sample() }
        val result = kernel.prepare(
            batch(1, frame(), translation(0.0, 0.0, 0.0), duplicateSamples),
            FakeCanonicalView(),
        ) as DepthEvidenceResult.Accepted

        assertEquals(32, result.receipt.acceptedSamples)
        assertEquals(1, result.receipt.touchedEvidenceRows)
        assertEquals(0, result.receipt.createCount)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `duplicate endpoint samples cannot cross the create threshold`() {
        val kernel = DepthEvidenceKernel()
        val duplicateSamples = List(64) { sample() }

        val result = kernel.prepare(
            batch(1, frame(), translation(0.0, 0.0, 0.0), duplicateSamples),
            FakeCanonicalView(),
        ) as DepthEvidenceResult.Accepted

        assertEquals(1, result.receipt.touchedEvidenceRows)
        assertEquals(0, result.receipt.createCount)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `depth observations derive relocation when one canonical source moves`() {
        val target = Voxel(0, 0, -10)
        val source = surface(21, Voxel(3, 0, -10))
        val view = FakeCanonicalView(surfaces = mapOf(target to source))
        val kernel = DepthEvidenceKernel()

        repeat(3) { index ->
            assertTrue(kernel.prepare(depthBatchForFrame(index + 1L, frame(), identity()), view)
                is DepthEvidenceResult.Accepted)
            kernel.applyPrepared()
        }
        val result = kernel.prepare(depthBatchForFrame(4, frame(), identity()), view)
            as DepthEvidenceResult.Accepted

        val relocation = result.changes.single() as DepthEvidenceChange.Relocate
        assertEquals(SurfaceId(21), relocation.sourceId)
        assertEquals(target, relocation.target.voxel)
    }

    @Test
    fun `depth observations derive merge from two source relations at one target`() {
        val target = Voxel(0, 0, -10)
        val first = surface(31, Voxel(-2, 0, -10))
        val second = surface(32, Voxel(2, 0, -10))
        val view = FakeCanonicalView(
            surfaces = mapOf(target to first),
            rayHits = listOf(target to second),
        )
        val kernel = DepthEvidenceKernel()

        repeat(3) { index ->
            kernel.prepare(depthBatchForFrame(index + 1L, frame(), identity()), view)
            kernel.applyPrepared()
        }
        val result = kernel.prepare(depthBatchForFrame(4, frame(), identity()), view)
            as DepthEvidenceResult.Accepted

        val merge = result.changes.single() as DepthEvidenceChange.Merge
        assertEquals(listOf(SurfaceId(31), SurfaceId(32)), merge.sourceIds)
        assertEquals(target, merge.target.voxel)
    }

    @Test
    fun `depth observations derive split from one source across two targets`() {
        val firstTarget = Voxel(-8, 0, -10)
        val secondTarget = Voxel(7, 0, -10)
        val source = surface(41, Voxel(2, 0, -10))
        val view = FakeCanonicalView(
            surfaces = mapOf(firstTarget to source, secondTarget to source),
        )
        val kernel = DepthEvidenceKernel()
        val intrinsics = VisibilityCameraIntrinsics(4, 1, 2.0, 1.0, 1.5, 0.0)
        val samples = listOf(
            VisibilityDepthSample(0, 0, 1_000, 255),
            VisibilityDepthSample(3, 0, 1_000, 255),
        )

        repeat(3) { index ->
            kernel.prepare(depthBatchForSamples(index + 1L, frame(), identity(), intrinsics, samples), view)
            kernel.applyPrepared()
        }
        val result = kernel.prepare(
            depthBatchForSamples(4, frame(), identity(), intrinsics, samples), view,
        ) as DepthEvidenceResult.Accepted

        val split = result.changes.single() as DepthEvidenceChange.Split
        assertEquals(SurfaceId(41), split.sourceId)
        assertEquals(listOf(firstTarget, secondTarget), split.targets.map { it.voxel })
    }

    @Test
    fun `depth observations derive replace from two sources across two targets`() {
        val firstTarget = Voxel(-8, 0, -10)
        val secondTarget = Voxel(7, 0, -10)
        val firstSource = surface(51, Voxel(-2, 0, -10))
        val secondSource = surface(52, Voxel(2, 0, -10))
        val view = FakeCanonicalView(
            surfaces = mapOf(firstTarget to firstSource, secondTarget to secondSource),
            rayHits = listOf(firstTarget to secondSource, secondTarget to firstSource),
        )
        val kernel = DepthEvidenceKernel()
        val intrinsics = VisibilityCameraIntrinsics(4, 1, 2.0, 1.0, 1.5, 0.0)
        val samples = listOf(
            VisibilityDepthSample(0, 0, 1_000, 255),
            VisibilityDepthSample(3, 0, 1_000, 255),
        )

        repeat(3) { index ->
            kernel.prepare(depthBatchForSamples(index + 1L, frame(), identity(), intrinsics, samples), view)
            kernel.applyPrepared()
        }
        val result = kernel.prepare(
            depthBatchForSamples(4, frame(), identity(), intrinsics, samples), view,
        ) as DepthEvidenceResult.Accepted

        val replace = result.changes.single() as DepthEvidenceChange.Replace
        assertEquals(listOf(SurfaceId(51), SurfaceId(52)), replace.sourceIds)
        assertEquals(listOf(firstTarget, secondTarget), replace.targets.map { it.voxel })
    }

    @Test
    fun `two removals sharing one source refuse as source overlap`() {
        val first = Voxel(-4, 0, -5)
        val second = Voxel(3, 0, -5)
        val source = surface(71, Voxel(0, 0, -7))
        val view = FakeCanonicalView(
            surfaces = mapOf(first to source, second to source),
            rayCells = listOf(first, second),
        )
        val kernel = DepthEvidenceKernel()
        val endpointIntrinsics = VisibilityCameraIntrinsics(2, 1, 0.625, 1.0, 0.5, 0.0)
        val endpoints = listOf(
            VisibilityDepthSample(0, 0, 500, 255),
            VisibilityDepthSample(1, 0, 500, 255),
        )
        repeat(4) { index ->
            kernel.prepare(
                depthBatchForSamples(index + 1L, frame(), identity(), endpointIntrinsics, endpoints),
                view,
            )
            kernel.applyPrepared()
        }

        val firstFreeIntrinsics = VisibilityCameraIntrinsics(2, 1, 0.625, 1.0, 0.5, 0.0)
        val firstFreeSamples = listOf(
            VisibilityDepthSample(0, 0, 1_000, 255),
            VisibilityDepthSample(1, 0, 1_000, 255),
        )
        repeat(4) { index ->
            kernel.prepare(
                depthBatchForSamples(10L + index, frame(), translation(0.0, 0.05, 0.0), firstFreeIntrinsics, firstFreeSamples),
                view,
            )
            kernel.applyPrepared()
        }
        val secondFreeIntrinsics = VisibilityCameraIntrinsics(2, 1, 0.625, 1.0, 0.5, 0.5)
        val secondFreeSamples = listOf(
            VisibilityDepthSample(0, 0, 1_000, 255),
            VisibilityDepthSample(1, 0, 1_000, 255),
        )
        repeat(3) { index ->
            kernel.prepare(
                depthBatchForSamples(20L + index, frame(), translation(0.0, -0.2, 0.0), secondFreeIntrinsics, secondFreeSamples),
                view,
            )
            kernel.applyPrepared()
        }
        val candidate = kernel.prepare(
            depthBatchForSamples(23, frame(), translation(0.0, -0.2, 0.0), secondFreeIntrinsics, secondFreeSamples),
            view,
        )

        assertTrue("candidate=$candidate", candidate is DepthEvidenceResult.Refused)
        val refusal = candidate as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceRefusal.SOURCE_OVERLAP, refusal.reason)
        assertEquals(0, kernel.resourceReceipt().preparedEvidenceRows)
    }

    @Test
    fun `missing and duplicate canonical source lookups refuse at the real prepare seam`() {
        val target = Voxel(0, 0, -10)
        val source = surface(61, target)

        val missing = DepthEvidenceKernel().prepare(
            depthBatchForFrame(1, frame(), identity()),
            FakeCanonicalView(surfaces = mapOf(target to source), idLookup = emptyMap()),
        ) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceRefusal.CANONICAL_LOOKUP_FAILED, missing.reason)

        val conflicting = DepthEvidenceKernel().prepare(
            depthBatchForFrame(1, frame(), identity()),
            FakeCanonicalView(
                surfaces = mapOf(target to source),
                idLookup = mapOf(SurfaceId(61) to surface(61, Voxel(1, 0, -10))),
            ),
        ) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceRefusal.DUPLICATE_TARGET, conflicting.reason)
    }

    @Test
    fun `nonidentity group transform remains deterministic at the prepare seam`() {
        val view = FakeCanonicalView()
        val result = DepthEvidenceKernel().prepare(
            depthBatchForFrame(1, rotatedFrame(), rotationY180()),
            view,
        ) as DepthEvidenceResult.Accepted

        assertEquals(Voxel(0, 0, 10), view.visitedEndpoints.single())
        assertEquals(1, result.receipt.acceptedSamples)
    }

    @Test
    fun `four independent occupied observations prepare one create and apply atomically`() {
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView()
        val first = batch(1, frame(), translation(0.0, 0.0, 0.0), listOf(sample()))

        repeat(3) { index ->
            assertTrue(kernel.prepare(first.copyWithTimestamp(index + 1L), view) is DepthEvidenceResult.Accepted)
            kernel.applyPrepared()
        }
        val staged = kernel.prepare(first.copyWithTimestamp(4), view) as DepthEvidenceResult.Accepted

        assertEquals(1, staged.changes.size)
        assertTrue(staged.changes.single() is DepthEvidenceChange.Create)
        assertEquals(1, kernel.resourceReceipt().residentEvidenceRows)
        assertTrue(kernel.applyPrepared() is DepthEvidenceApplyResult.Applied)
        assertEquals(1, kernel.resourceReceipt().residentEvidenceRows)
        assertEquals(32, kernel.resourceReceipt().residentBytes)
        assertTrue(kernel.applyPrepared() is DepthEvidenceApplyResult.NoPrepared)
    }

    @Test
    fun `endpoint safety and separated views remove only stale canonical cells`() {
        val stale = Voxel(0, 0, -5)
        val safety = Voxel(0, 0, -9)
        val view = FakeCanonicalView(
            surfaces = mapOf(
                stale to surface(7, stale),
                safety to surface(8, safety),
            ),
            rayCells = listOf(stale, safety),
        )
        val kernel = DepthEvidenceKernel()
        val removedIds = mutableListOf<SurfaceId>()
        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatch(index + 1L, cameraX = 0.05, endpointX = 0.05),
                view,
            ) as DepthEvidenceResult.Accepted
            removedIds += result.changes.filterIsInstance<DepthEvidenceChange.Remove>().map { it.sourceId }
            kernel.applyPrepared()
            assertTrue(result.receipt.rayVisits <= 2)
        }
        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatch(10L + index, cameraX = 0.55, endpointX = -0.45),
                view,
            ) as DepthEvidenceResult.Accepted
            removedIds += result.changes.filterIsInstance<DepthEvidenceChange.Remove>().map { it.sourceId }
            kernel.applyPrepared()
        }

        val receipt = kernel.resourceReceipt()
        assertTrue(SurfaceId(7) in removedIds)
        assertTrue(SurfaceId(8) !in removedIds)
        val last = kernel.prepare(
            depthBatch(30, cameraX = 0.55, endpointX = -0.45),
            view,
        ) as DepthEvidenceResult.Accepted
        assertTrue(last.changes.none { it is DepthEvidenceChange.Remove && it.sourceId == SurfaceId(8) })
    }

    @Test
    fun `one observation with repeated rays contributes one free vote`() {
        val stale = Voxel(0, 0, -5)
        val view = FakeCanonicalView(
            surfaces = mapOf(stale to surface(7, stale)),
            rayCells = List(32) { stale },
        )
        val kernel = DepthEvidenceKernel()
        val result = kernel.prepare(depthBatch(1, 0.05, 0.05), view) as DepthEvidenceResult.Accepted

        assertEquals("receipt=${result.receipt}", 2, result.receipt.touchedEvidenceRows)
        assertEquals("receipt=${result.receipt}", 1, result.receipt.independentDirectionVotes)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `carved canonical identity is restored after four occupied observations`() {
        val stale = Voxel(0, 0, -5)
        val view = FakeCanonicalView(
            surfaces = mapOf(stale to surface(7, stale)),
            rayCells = listOf(stale),
        )
        val kernel = DepthEvidenceKernel()
        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatch(1L + index, cameraX = 0.05, endpointX = 0.05), view,
            ) as DepthEvidenceResult.Accepted
            kernel.applyPrepared()
            assertTrue(result.changes.none { it is DepthEvidenceChange.Remove })
        }
        var removed = false
        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatch(10L + index, cameraX = 0.55, endpointX = -0.45), view,
            ) as DepthEvidenceResult.Accepted
            removed = removed || result.changes.any { it is DepthEvidenceChange.Remove && it.sourceId == SurfaceId(7) }
            kernel.applyPrepared()
        }
        assertTrue(removed)

        repeat(3) { index ->
            val result = kernel.prepare(
                depthBatch(30L + index, cameraX = 0.05, endpointX = 0.05, depthMillimeters = 500), view,
            ) as DepthEvidenceResult.Accepted
            assertTrue(result.changes.none { it is DepthEvidenceChange.Relocate || it is DepthEvidenceChange.Refine })
            kernel.applyPrepared()
        }
        val restored = kernel.prepare(depthBatch(40, 0.05, 0.05, depthMillimeters = 500), view)
            as DepthEvidenceResult.Accepted
        assertTrue(restored.changes.any { it is DepthEvidenceChange.Refine && it.sourceId == SurfaceId(7) })
        kernel.applyPrepared()
        val next = kernel.prepare(depthBatch(41, 0.05, 0.05, depthMillimeters = 500), view)
            as DepthEvidenceResult.Accepted
        assertTrue(next.changes.none { it is DepthEvidenceChange.Remove })
    }

    @Test
    fun `voxel volume touching the safety band is rejected at exact boundary`() {
        val candidate = Voxel(0, 0, -3)
        val view = FakeCanonicalView(mapOf(candidate to surface(7, candidate)), listOf(candidate))
        val largeFrame = frame(voxelSizeMicrometres = 300_000)
        val result = kernelForSafety().prepare(
            depthBatchForFrame(1, largeFrame, translation(0.0, 0.0, -0.05)),
            view,
        ) as DepthEvidenceResult.Accepted

        assertEquals(2, result.receipt.touchedEvidenceRows)
        assertEquals(0, result.receipt.independentDirectionVotes)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `ray cells behind the endpoint cannot provide free evidence`() {
        val candidate = Voxel(0, 0, -13)
        val view = FakeCanonicalView(mapOf(candidate to surface(7, candidate)), listOf(candidate))
        val result = kernelForSafety().prepare(
            depthBatchForFrame(1, frame(), translation(0.0, 0.0, 0.0)),
            view,
        ) as DepthEvidenceResult.Accepted

        assertEquals(0, result.receipt.independentDirectionVotes)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `corridor fixture records the clear span but not the phantom safety band`() {
        val clear = Voxel(0, 0, -6)
        val safety = Voxel(0, 0, -9)
        val behind = Voxel(0, 0, -13)
        val view = FakeCanonicalView(
            mapOf(clear to surface(1, clear), safety to surface(2, safety), behind to surface(3, behind)),
            listOf(clear, safety, behind),
        )

        val result = DepthEvidenceKernel().prepare(
            depthBatchForFrame(1, frame(), translation(0.05, 0.05, 0.05)),
            view,
        ) as DepthEvidenceResult.Accepted

        assertEquals(1, result.receipt.independentDirectionVotes)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `five voxel corridor reduction removes the literal phantom span`() {
        val phantom = listOf(
            Voxel(0, 0, -6), Voxel(0, 0, -5), Voxel(0, 0, -4),
            Voxel(0, 0, -3), Voxel(0, 0, -2),
        )
        val sources = phantom.mapIndexed { index, voxel -> voxel to surface(101L + index, voxel) }.toMap()
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView(surfaces = sources, rayCells = phantom)

        phantom.forEachIndexed { index, voxel ->
            repeat(4) { observation ->
                val result = kernel.prepare(
                    depthBatchForDepth(
                        timestamp = 1L + index * 4 + observation,
                        groupFrame = frame(),
                        groupFromCamera = identity(),
                        depthMillimeters = -voxel.z * 100,
                    ),
                    view,
                ) as DepthEvidenceResult.Accepted
                assertTrue(result.changes.none { it is DepthEvidenceChange.Remove })
                kernel.applyPrepared()
            }
        }

        val removedIds = mutableSetOf<SurfaceId>()
        phantom.forEachIndexed { index, voxel ->
            val targetZ = (voxel.z + 0.5) * 0.1
            val fraction = (0.05 - targetZ) / 1.0
            val endpointX = 0.55 + (0.05 - 0.55) / fraction
            repeat(8) { observation ->
                val cameraX = if (observation < 4) 0.55 else 0.05
                val result = kernel.prepare(
                    depthBatchAtFrame(
                        timestamp = 100L + index * 8 + observation,
                        groupFrame = frame(),
                        cameraX = cameraX,
                        endpointX = if (cameraX == 0.05) 0.05 else endpointX,
                    ),
                    view,
                ) as DepthEvidenceResult.Accepted
                removedIds += result.changes.filterIsInstance<DepthEvidenceChange.Remove>()
                    .map { it.sourceId }
                kernel.applyPrepared()
            }
        }

        assertEquals(
            setOf(SurfaceId(101), SurfaceId(102), SurfaceId(103), SurfaceId(104), SurfaceId(105)),
            removedIds,
        )
        assertTrue(kernel.resourceReceipt().residentEvidenceRows >= phantom.size)
    }

    @Test
    fun `viable capacity carves a stale row while preserving the bounded budget`() {
        val stale = Voxel(0, 0, -3)
        val staleSurface = surface(81, stale)
        val view = FakeCanonicalView(surfaces = mapOf(stale to staleSurface))
        val groupFrame = frame(voxelSizeMicrometres = 200_000)
        val kernel = DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceCapacity = 2))

        repeat(4) { index ->
            kernel.prepare(
                depthBatchForDepth(1L + index, groupFrame, identity(), 500),
                view,
            ) as DepthEvidenceResult.Accepted
            kernel.applyPrepared()
        }
        repeat(4) { index ->
            kernel.prepare(
                depthBatchForDepth(10L + index, groupFrame, identity(), 1_000),
                view,
            ) as DepthEvidenceResult.Accepted
            kernel.applyPrepared()
        }

        val carvingView = FakeCanonicalView(
            surfaces = mapOf(stale to staleSurface),
            rayCells = listOf(stale),
        )
        var removed = false
        repeat(8) { index ->
            val result = kernel.prepare(
                depthBatchAtFrame(
                    timestamp = 20L + index,
                    groupFrame = groupFrame,
                    cameraX = if (index < 4) 0.65 else 0.05,
                    endpointX = if (index < 4) 0.0 else 0.05,
                    depthMillimeters = 1_000,
                ),
                carvingView,
            ) as DepthEvidenceResult.Accepted
            removed = removed || result.changes.any {
                it is DepthEvidenceChange.Remove && it.sourceId == SurfaceId(81)
            }
            assertEquals(0, result.receipt.capacityRefusals)
            kernel.applyPrepared()
        }

        assertTrue(removed)
        assertEquals(2, kernel.resourceReceipt().residentEvidenceRows)
        assertEquals(2, kernel.resourceReceipt().evidenceRowCapacity)
        assertEquals(64, kernel.resourceReceipt().residentBytes)
    }

    @Test
    fun `full sample budget remains bounded and duplicate rows remain one observation`() {
        val configuration = DepthEvidenceConfiguration()
        val kernel = DepthEvidenceKernel(configuration)
        val samples = List(V2_DEPTH_SAMPLE_CAPACITY) { sample() }
        val result = kernel.prepare(
            depthBatchForSamples(
                1L,
                frame(),
                identity(),
                VisibilityCameraIntrinsics(1, 1, 1.0, 1.0, 0.0, 0.0),
                samples,
            ),
            FakeCanonicalView(),
        ) as DepthEvidenceResult.Accepted

        assertEquals(V2_DEPTH_SAMPLE_CAPACITY, result.receipt.acceptedSamples)
        assertEquals(1, result.receipt.touchedEvidenceRows)
        assertEquals(0, result.receipt.createCount)
        assertEquals(0, result.changes.size)
        assertTrue(kernel.resourceReceipt().fixedPrimitiveBytes > 32)
        assertEquals(result.receipt.acceptedSamples + 1, result.work.virtualWorkUnits)
        kernel.discardPrepared()
    }

    @Test
    fun `hole and low confidence samples remain conservative at the new seam`() {
        val hole = Voxel(2, 0, -5)
        val view = FakeCanonicalView(
            surfaces = mapOf(hole to surface(91, hole)),
            rayCells = listOf(hole),
        )
        val result = DepthEvidenceKernel().prepare(
            depthBatchForSamples(
                1L,
                frame(),
                identity(),
                VisibilityCameraIntrinsics(1, 1, 1.0, 1.0, 0.0, 0.0),
                listOf(
                    VisibilityDepthSample(0, 0, 1_000, 255),
                    VisibilityDepthSample(0, 0, 500, 127),
                ),
            ),
            view,
        ) as DepthEvidenceResult.Accepted

        assertEquals(1, result.receipt.acceptedSamples)
        assertEquals(1, result.receipt.rejectedSamples)
        assertEquals(0, result.receipt.independentDirectionVotes)
        assertTrue(result.changes.isEmpty())
        assertEquals(listOf(Voxel(0, 0, -10)), view.visitedEndpoints)
    }

    @Test
    fun `opposing supported thin and double walls survive the carve threshold`() {
        val thin = Voxel(0, 0, -9)
        val double = Voxel(0, 0, -12)
        val kernel = DepthEvidenceKernel()
        val intrinsics = VisibilityCameraIntrinsics(1, 1, 1.0, 1.0, 0.0, 0.0)
        fun supportedWall(
            source: DepthCanonicalSurface,
            frontDepth: Int,
            opposingDepth: Int,
        ) {
            val view = FakeCanonicalView(
                surfaces = mapOf(source.voxel to source),
                rayCells = listOf(source.voxel),
            )
            repeat(8) { index ->
                val opposing = index % 2 == 1
                val result = kernel.prepare(
                    depthBatchForSamples(
                        timestamp = source.id.value * 10 + index,
                        groupFrame = frame(),
                        groupFromCamera = if (opposing) opposingTransform(-1.8) else translation(0.0, 0.05, 0.05),
                        intrinsics = intrinsics,
                        samples = listOf(
                            VisibilityDepthSample(0, 0, if (opposing) opposingDepth else frontDepth, 255),
                            VisibilityDepthSample(0, 0, 1_600, 255),
                        ),
                    ),
                    view,
                ) as DepthEvidenceResult.Accepted
                assertTrue("source=${source.id} index=$index receipt=${result.receipt}", result.receipt.conflictsRetained >= 1)
                assertTrue(result.changes.none { it is DepthEvidenceChange.Remove })
                kernel.applyPrepared()
            }
        }
        supportedWall(surface(1, thin), frontDepth = 900, opposingDepth = 900)
        supportedWall(surface(2, double), frontDepth = 1_200, opposingDepth = 650)
        assertTrue(kernel.resourceReceipt().residentEvidenceRows >= 2)
    }

    @Test
    fun `hole foreground edge fixture rejects off-ray foreground evidence`() {
        val edge = Voxel(2, 0, -5)
        val view = FakeCanonicalView(mapOf(edge to surface(1, edge)), listOf(edge))
        val result = DepthEvidenceKernel().prepare(
            depthBatchForFrame(1, frame(), translation(0.0, 0.0, 0.0)), view,
        ) as DepthEvidenceResult.Accepted

        assertEquals(0, result.receipt.independentDirectionVotes)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `typed refusal leaves prepared and committed state unchanged`() {
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView()
        val valid = depthBatch(1, 0.05, 0.05)
        repeat(4) { index ->
            kernel.prepare(valid.copyWithTimestamp(index + 1L), view)
            kernel.applyPrepared()
        }
        val before = kernel.resourceReceipt()
        val refusal = kernel.prepare(valid.copyWithTimestamp(5), view)
        assertTrue(refusal is DepthEvidenceResult.Accepted)
        assertTrue(kernel.prepare(valid.copyWithTimestamp(6), view) is DepthEvidenceResult.Refused)
        assertEquals(DepthEvidenceRefusal.PREPARED_BUSY, (kernel.prepare(valid.copyWithTimestamp(7), view) as DepthEvidenceResult.Refused).reason)
        kernel.discardPrepared()
        assertEquals(before, kernel.resourceReceipt())

        val invalid = valid.copyWithTimestamp(8).withSamples(listOf(VisibilityDepthSample(0, 0, 1, 255)))
        val invalidResult = kernel.prepare(invalid, view) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceRefusal.INVALID_SAMPLE, invalidResult.reason)
        assertEquals(before, kernel.resourceReceipt())
    }

    @Test
    fun `close releases logical rows and refuses later preparation`() {
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView()
        kernel.prepare(depthBatch(1, 0.05, 0.05), view)
        kernel.applyPrepared()
        kernel.close()

        val closed = kernel.resourceReceipt()
        assertEquals(0, closed.residentEvidenceRows)
        assertEquals(0, closed.residentBytes)
        assertEquals(0, closed.preparedEvidenceRows)
        assertEquals(0, closed.fixedPrimitiveBytes)
        kernel.close()
        assertEquals(closed, kernel.resourceReceipt())
        assertEquals(DepthEvidenceRefusal.CLOSED, (kernel.prepare(depthBatch(2, 0.05, 0.05), view) as DepthEvidenceResult.Refused).reason)
    }

    private fun batch(
        timestamp: Long,
        groupFrame: VisibilityGroupFrame,
        groupFromCamera: DoubleArray,
        samples: List<VisibilityDepthSample>,
    ) = DepthEvidenceBatch(
        sequence = timestamp,
        sourceTimestampNs = timestamp,
        groupFrame = groupFrame,
        groupFromCameraGl = groupFromCamera.toList(),
        intrinsics = VisibilityCameraIntrinsics(4, 3, 2.0, 2.0, 1.5, 1.0),
        samples = samples,
        sourceRejectedSamples = 0,
    )

    private fun depthBatch(timestamp: Long, cameraX: Double, endpointX: Double, depthMillimeters: Int = 1_000) =
        depthBatchAtFrame(timestamp, frame(), cameraX, endpointX, depthMillimeters)

    private fun depthBatchAtFrame(
        timestamp: Long,
        groupFrame: VisibilityGroupFrame,
        cameraX: Double,
        endpointX: Double,
        depthMillimeters: Int = 1_000,
    ) = DepthEvidenceBatch(
            sequence = timestamp,
            sourceTimestampNs = timestamp,
            groupFrame = groupFrame,
            groupFromCameraGl = translation(cameraX, 0.05, 0.05).toList(),
            intrinsics = VisibilityCameraIntrinsics(
                8, 1, 1.0, 1.0, (cameraX - endpointX) / 1.0, 0.0,
            ),
            samples = listOf(VisibilityDepthSample(0, 0, depthMillimeters, 255)),
            sourceRejectedSamples = 0,
        )

    private fun depthBatchForDepth(
        timestamp: Long,
        groupFrame: VisibilityGroupFrame,
        groupFromCamera: DoubleArray,
        depthMillimeters: Int,
    ) = DepthEvidenceBatch(
        sequence = timestamp,
        sourceTimestampNs = timestamp,
        groupFrame = groupFrame,
        groupFromCameraGl = groupFromCamera.toList(),
        intrinsics = VisibilityCameraIntrinsics(1, 1, 1.0, 1.0, 0.0, 0.0),
        samples = listOf(VisibilityDepthSample(0, 0, depthMillimeters, 255)),
        sourceRejectedSamples = 0,
    )

    private fun sample() = VisibilityDepthSample(0, 0, 1_000, 255)

    private fun surface(id: Long, voxel: Voxel) = DepthCanonicalSurface(
        SurfaceId(id), voxel, 0x1010, 200, 1,
    )

    private fun DepthEvidenceBatch.copyWithTimestamp(timestamp: Long) = DepthEvidenceBatch(
        sequence = timestamp,
        sourceTimestampNs = timestamp,
        groupFrame = groupFrame,
        groupFromCameraGl = groupFromCameraGl,
        intrinsics = intrinsics,
        samples = samples,
        sourceRejectedSamples = sourceRejectedSamples,
        tracking = tracking,
    )

    private fun DepthEvidenceBatch.withSamples(samples: List<VisibilityDepthSample>) = DepthEvidenceBatch(
        sequence = sequence,
        sourceTimestampNs = sourceTimestampNs,
        groupFrame = groupFrame,
        groupFromCameraGl = groupFromCameraGl,
        intrinsics = intrinsics,
        samples = samples,
        sourceRejectedSamples = sourceRejectedSamples,
        tracking = tracking,
    )

    private fun frame(voxelSizeMicrometres: Int = 100_000) = VisibilityGroupFrame.copyOf(
        translation(0.3, -0.2, 0.1),
        translation(-0.3, 0.2, -0.1),
        voxelSizeMicrometres = voxelSizeMicrometres,
        modelCapacity = 100,
    )

    private fun rotatedFrame() = VisibilityGroupFrame.copyOf(
        rotationY180(), rotationY180(), voxelSizeMicrometres = 100_000, modelCapacity = 100,
    )

    private fun rotationY180() = doubleArrayOf(
        -1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, -1.0, 0.0,
        0.0, 0.0, 0.0, 1.0,
    )

    private fun opposingTransform(cameraZ: Double) = rotationY180().also {
        it[13] = 0.05
        it[14] = cameraZ
    }

    private fun kernelForSafety() = DepthEvidenceKernel()

    private fun depthBatchForFrame(
        timestamp: Long,
        groupFrame: VisibilityGroupFrame,
        groupFromCamera: DoubleArray,
    ) = DepthEvidenceBatch(
        sequence = timestamp,
        sourceTimestampNs = timestamp,
        groupFrame = groupFrame,
        groupFromCameraGl = groupFromCamera.toList(),
        intrinsics = VisibilityCameraIntrinsics(1, 1, 1.0, 1.0, 0.0, 0.0),
        samples = listOf(VisibilityDepthSample(0, 0, 1_000, 255)),
        sourceRejectedSamples = 0,
    )

    private fun depthBatchForSamples(
        timestamp: Long,
        groupFrame: VisibilityGroupFrame,
        groupFromCamera: DoubleArray,
        intrinsics: VisibilityCameraIntrinsics,
        samples: List<VisibilityDepthSample>,
    ) = DepthEvidenceBatch(
        sequence = timestamp,
        sourceTimestampNs = timestamp,
        groupFrame = groupFrame,
        groupFromCameraGl = groupFromCamera.toList(),
        intrinsics = intrinsics,
        samples = samples,
        sourceRejectedSamples = 0,
    )

    private fun translation(x: Double, y: Double, z: Double) =
        identityVisibilityGridTransform().also {
            it[12] = x
            it[13] = y
            it[14] = z
        }

    private fun identity() = identityVisibilityGridTransform()

    private class FakeCanonicalView(
        private val surfaces: Map<Voxel, DepthCanonicalSurface> = emptyMap(),
        private val rayCells: List<Voxel> = emptyList(),
        private val rayHits: List<Pair<Voxel, DepthCanonicalSurface?>> = emptyList(),
        private val idLookup: Map<SurfaceId, DepthCanonicalSurface>? = null,
    ) : BoundedCanonicalSurfaceView {
        override val geometryRevision: Long = 0
        override val lineageRevision: Long = 0
        override val surfaceCount: Int = maxOf(surfaces.size, idLookup?.size ?: 0)
        val visitedEndpoints = mutableListOf<Voxel>()

        override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? = if (idLookup != null) {
            idLookup[id]
        } else {
            surfaces.values.firstOrNull { it.id == id } ?: rayHits.asSequence()
                .mapNotNull { it.second }.firstOrNull { it.id == id }
        }

        override fun findSurfaceAt(voxel: Voxel): DepthCanonicalSurface? {
            visitedEndpoints += voxel
            return surfaces[voxel]
        }

        override fun visitRayCells(
            startGroupMm: DepthPointMm,
            endpointGroupMm: DepthPointMm,
            maximumVisits: Int,
            visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
        ): DepthRayVisitResult {
            val hits = if (rayHits.isNotEmpty()) rayHits else rayCells.map { it to surfaces[it] }
            hits.take(maximumVisits).forEach { (voxel, surface) -> visitor(voxel, surface) }
            return DepthRayVisitResult(hits.size.coerceAtMost(maximumVisits), hits.size > maximumVisits)
        }
    }
}
