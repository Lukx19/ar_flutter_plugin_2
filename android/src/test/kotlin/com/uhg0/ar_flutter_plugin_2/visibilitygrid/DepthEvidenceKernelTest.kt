package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthEvidenceKernelTest {
    @Test
    fun `canonical view traversal adapter matches kernel supercover and direct addresses`() {
        val addressed = surface(7, Voxel(0, 0, -1))
        val adapter = CanonicalSurfaceRayViewAdapter(
            FakeCanonicalView(mapOf(addressed.voxel to addressed)),
            frame(),
        )
        val visits = mutableListOf<Pair<Voxel, SurfaceId?>>()

        val result = adapter.visitRayCells(
            DepthPointMm(50.0, 50.0, 50.0),
            DepthPointMm(50.0, 50.0, -250.0),
            3,
        ) { voxel, canonical -> visits += voxel to canonical?.id; true }

        assertEquals(DepthRayVisitResult(3, truncated = true), result)
        assertEquals(
            listOf(
                Voxel(0, 0, 0) to null,
                Voxel(0, 0, -1) to SurfaceId(7),
                Voxel(0, 0, -2) to null,
            ),
            visits,
        )
        val mismatched = CanonicalSurfaceRayViewAdapter(
            FakeCanonicalView(mapOf(addressed.voxel to addressed), addressedVoxelOverride = Voxel(1, 0, -1)),
            frame(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            mismatched.visitRayCells(
                DepthPointMm(50.0, 50.0, -50.0), DepthPointMm(50.0, 50.0, -50.0), 1,
            ) { _, _ -> true }
        }
    }

    @Test
    fun `prepare ignores canonical view traversal override`() {
        val result = DepthEvidenceKernel().prepare(depthBatchForFrame(1, frame(), identity()), FakeCanonicalView())
        assertEquals(expectedAccepted(1, rayVisits = 11, virtualWork = 13), result)
    }

    @Test
    fun `concurrent preparations serialize and preserve the single prepared slot`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val view = BlockingCanonicalView(entered, release)
        val kernel = DepthEvidenceKernel()
        val executor = Executors.newFixedThreadPool(3)
        try {
            val first = executor.submit<DepthEvidenceResult> {
                kernel.prepare(depthBatchForFrame(1, frame(), identity()), view)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<DepthEvidenceResult> {
                kernel.prepare(depthBatchForFrame(2, frame(), identity()), FakeCanonicalView())
            }
            val resource = executor.submit<DepthEvidenceResourceReceipt> { kernel.resourceReceipt() }
            assertThrows(TimeoutException::class.java) { second.get(100, TimeUnit.MILLISECONDS) }
            assertThrows(TimeoutException::class.java) { resource.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS) is DepthEvidenceResult.Accepted)
            assertEquals(
                DepthEvidenceResult.Refused(
                    DepthEvidenceRefusal.PREPARED_BUSY,
                    DepthEvidenceReceipt(),
                ),
                second.get(5, TimeUnit.SECONDS),
            )
            assertEquals(1, resource.get(5, TimeUnit.SECONDS).preparedEvidenceRows)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent terminal lifecycle calls cannot double apply or expose torn resources`() {
        repeat(20) { iteration ->
            val kernel = DepthEvidenceKernel()
            val prepared = kernel.prepare(depthBatchForFrame(iteration + 1L, frame(), identity()), FakeCanonicalView())
            assertTrue(prepared is DepthEvidenceResult.Accepted)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(4)
            try {
                val applies = List(2) { executor.submit<DepthEvidenceApplyResult> { start.await(); kernel.applyPrepared() } }
                val discard = executor.submit<DepthEvidenceDiscardResult> { start.await(); kernel.discardPrepared() }
                val close = executor.submit { start.await(); kernel.close() }
                start.countDown()
                val appliedCount = applies.count { it.get(5, TimeUnit.SECONDS) is DepthEvidenceApplyResult.Applied }
                discard.get(5, TimeUnit.SECONDS)
                close.get(5, TimeUnit.SECONDS)
                assertTrue(appliedCount <= 1)
                assertEquals(
                    DepthEvidenceResourceReceipt(0, 0, 0, 0, 100_000, 0, true, 0, 0),
                    kernel.resourceReceipt(),
                )
                kernel.close()
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `every canonical view exception is typed and leaves preparation recoverable`() {
        ThrowAccess.entries.forEach { access ->
            val canonical = surface(71, Voxel(0, 0, -10))
            val view = ThrowingCanonicalView(access, canonical.takeIf { access == ThrowAccess.SOURCE })
            val kernel = DepthEvidenceKernel()

            assertEquals(
                DepthEvidenceResult.Refused(DepthEvidenceRefusal.CANONICAL_LOOKUP_FAILED, DepthEvidenceReceipt()),
                kernel.prepare(depthBatchForFrame(1, frame(), identity()), view),
            )
            assertEquals(0, kernel.resourceReceipt().preparedEvidenceRows)
            assertTrue(kernel.prepare(depthBatchForFrame(2, frame(), identity()), view) is DepthEvidenceResult.Accepted)
            kernel.discardPrepared()
        }
    }

    @Test
    fun `fatal canonical view errors are not converted into lookup refusals`() {
        val fatal = object : BoundedCanonicalSurfaceView {
            override val revisionPair: CanonicalRevisionPair get() = throw AssertionError("fatal")
            override val surfaceCount: Int = 0
            override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? = null
            override fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface? = null
            override fun visitRayCells(
                startGroupMm: DepthPointMm,
                endpointGroupMm: DepthPointMm,
                maximumVisits: Int,
                visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
            ): DepthRayVisitResult = error("prepare must own ray traversal")
        }
        assertThrows(AssertionError::class.java) {
            DepthEvidenceKernel().prepare(depthBatchForFrame(1, frame(), identity()), fatal)
        }
    }

    @Test
    fun `kernel supercover visits axis diagonal corner and negative cells exactly`() {
        val kernel = DepthEvidenceKernel()
        fun trace(start: DepthPointMm, end: DepthPointMm): List<Voxel> {
            val visited = mutableListOf<Voxel>()
            val receipt = kernel.visitRayCells(start, end, frame(), 100) { visited += it; true }
            assertEquals(DepthRayVisitResult(visited.size), receipt)
            return visited
        }
        assertEquals(
            listOf(Voxel(0, 0, 0), Voxel(0, 0, -1), Voxel(0, 0, -2), Voxel(0, 0, -3)),
            trace(DepthPointMm(50.0, 50.0, 50.0), DepthPointMm(50.0, 50.0, -250.0)),
        )
        assertEquals(
            listOf(
                Voxel(0, 0, 0), Voxel(1, 0, 0), Voxel(0, 1, 0), Voxel(1, 1, 0),
                Voxel(2, 1, 0), Voxel(1, 2, 0), Voxel(2, 2, 0),
            ),
            trace(DepthPointMm(50.0, 50.0, 50.0), DepthPointMm(250.0, 250.0, 50.0)),
        )
        assertEquals(
            listOf(
                Voxel(0, 0, 0), Voxel(1, 0, 0), Voxel(0, 1, 0), Voxel(1, 1, 0),
                Voxel(0, 0, 1), Voxel(1, 0, 1), Voxel(0, 1, 1), Voxel(1, 1, 1),
            ),
            trace(DepthPointMm(50.0, 50.0, 50.0), DepthPointMm(150.0, 150.0, 150.0)),
        )
        assertEquals(
            listOf(Voxel(-1, 0, 0), Voxel(-2, 0, 0), Voxel(-3, 0, 0)),
            trace(DepthPointMm(-50.0, 50.0, 50.0), DepthPointMm(-250.0, 50.0, 50.0)),
        )
        assertEquals(
            listOf(
                Voxel(0, 0, 0), Voxel(0, 0, -1), Voxel(0, 0, -2), Voxel(0, 0, -3),
                Voxel(1, 0, -3), Voxel(1, 0, -4), Voxel(1, 0, -5), Voxel(1, 0, -6),
                Voxel(1, 0, -7), Voxel(2, 0, -7), Voxel(1, 0, -8), Voxel(2, 0, -8),
                Voxel(2, 0, -9), Voxel(2, 0, -10),
            ),
            trace(DepthPointMm(20.0, 20.0, 20.0), DepthPointMm(270.0, 20.0, -980.0)),
        )
        assertEquals(
            (0..10).map { Voxel(0, 0, it) },
            trace(DepthPointMm(0.0, 0.0, 0.0), DepthPointMm(0.0, 0.0, 1_000.0)),
        )
    }

    @Test
    fun `kernel supercover reports checked overflow and visit capacity`() {
        val kernel = DepthEvidenceKernel()
        val overflow = kernel.visitRayCells(
            DepthPointMm(0.0, 0.0, 0.0), DepthPointMm(Double.MAX_VALUE, 0.0, 0.0), frame(), 65_536,
        ) { true }
        assertEquals(DepthRayVisitResult(0, arithmeticOverflow = true), overflow)
        val visited = mutableListOf<Voxel>()
        val truncated = kernel.visitRayCells(
            DepthPointMm(50.0, 50.0, 50.0), DepthPointMm(50.0, 50.0, -250.0), frame(), 3,
        ) { visited += it; true }
        assertEquals(listOf(Voxel(0, 0, 0), Voxel(0, 0, -1), Voxel(0, 0, -2)), visited)
        assertEquals(DepthRayVisitResult(3, truncated = true), truncated)
        assertEquals(
            DepthEvidenceResult.Refused(
                DepthEvidenceRefusal.RAY_VISIT_CAPACITY,
                DepthEvidenceReceipt(capacityRefusals = 1),
            ),
            DepthEvidenceKernel(DepthEvidenceConfiguration(rayVisitCapacity = 3)).prepare(
                depthBatchForFrame(1, frame(), identity()),
                FakeCanonicalView(),
            ),
        )
    }

    @Test
    fun `translated group emits the locked target and group-space normal`() {
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView()
        fun translatedBatch(sequence: Long) = batch(
            timestamp = sequence,
            groupFrame = frame(),
            groupFromCamera = translation(0.02, 0.02, 0.02),
            samples = listOf(VisibilityDepthSample(2, 1, 1_000, 200)),
        )
        repeat(3) { index ->
            assertEquals(expectedAccepted(index + 1L, rayVisits = 14, virtualWork = 16), kernel.prepare(translatedBatch(index + 1L), view))
            kernel.applyPrepared()
        }
        val result = kernel.prepare(translatedBatch(4), view)

        assertEquals(
            expectedAccepted(
                4,
                changes = listOf(DepthEvidenceChange.Create(
                    canonicalTarget(null, Voxel(2, 0, -10), -25, 0, 255),
                )),
                createCount = 1,
                rayVisits = 14,
                virtualWork = 16,
            ),
            result,
        )
    }

    @Test
    fun `duplicate endpoint samples count as one occupied observation`() {
        val kernel = DepthEvidenceKernel()
        val duplicateSamples = List(32) { sample() }
        val result = kernel.prepare(
            batch(1, frame(), translation(0.0, 0.0, 0.0), duplicateSamples),
            FakeCanonicalView(),
        ) as DepthEvidenceResult.Accepted

        assertEquals(expectedAccepted(1, acceptedSamples = 32, rayVisits = 992, virtualWork = 1_025), result)
    }

    @Test
    fun `duplicate endpoint samples cannot cross the create threshold`() {
        val kernel = DepthEvidenceKernel()
        val duplicateSamples = List(64) { sample() }

        val result = kernel.prepare(
            batch(1, frame(), translation(0.0, 0.0, 0.0), duplicateSamples),
            FakeCanonicalView(),
        ) as DepthEvidenceResult.Accepted

        assertEquals(expectedAccepted(1, acceptedSamples = 64, rayVisits = 1_984, virtualWork = 2_049), result)
    }

    @Test
    fun `depth observations derive relocation when one canonical source moves`() {
        val target = Voxel(0, 0, -10)
        val source = surface(21, Voxel(3, 0, -10))
        val view = FakeCanonicalView(surfaces = mapOf(target to source))
        val kernel = DepthEvidenceKernel()

        repeat(3) { index ->
            assertEquals(
                expectedAccepted(index + 1L, rayVisits = 11, virtualWork = 13, relationCount = 1, relationAdmissionWork = 2),
                kernel.prepare(depthBatchForFrame(index + 1L, frame(), identity()), view),
            )
            kernel.applyPrepared()
        }
        val result = kernel.prepare(depthBatchForFrame(4, frame(), identity()), view)
            as DepthEvidenceResult.Accepted

        assertEquals(
            expectedAccepted(
                4,
                changes = listOf(DepthEvidenceChange.Relocate(
                    SurfaceId(21), canonicalTarget(SurfaceId(21), target, 16, 16, 200),
                )),
                relocateCount = 1,
                rayVisits = 11,
                virtualWork = 13,
                relationCount = 1,
                relationAdmissionWork = 2,
            ),
            result,
        )
    }

    @Test
    fun `depth observations derive merge from two source relations at one target`() {
        val target = Voxel(0, 0, -10)
        val first = surface(31, Voxel(-2, 0, -10))
        val second = surface(32, Voxel(2, 0, -10))
        val view = FakeCanonicalView(
            surfaces = mapOf(target to first),
            idLookup = mapOf(first.id to first, second.id to second),
        )
        val kernel = DepthEvidenceKernel()

        repeat(3) { index ->
            kernel.prepare(depthBatchForFrame(index + 1L, frame(), identity()), view)
            kernel.applyPrepared()
        }
        view.replaceSurfaces(mapOf(target to second))
        view.reportSurfaceCount(100)
        val result = kernel.prepare(depthBatchForFrame(4, frame(), identity()), view)
            as DepthEvidenceResult.Accepted

        assertEquals(
            expectedAccepted(
                4,
                changes = listOf(DepthEvidenceChange.Merge(
                    listOf(SurfaceId(31), SurfaceId(32)), canonicalTarget(null, target, 16, 16, 200),
                )),
                rayVisits = 11,
                mergeCount = 1,
                virtualWork = 13,
            ),
            result,
        )
        val merge = result.changes.single() as DepthEvidenceChange.Merge
        assertThrows(RuntimeException::class.java) {
            (merge.sourceIds as MutableList).add(SurfaceId(99))
        }
        assertEquals(listOf(SurfaceId(31), SurfaceId(32)), merge.sourceIds)
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

        assertEquals(
            expectedAccepted(
                4,
                changes = listOf(DepthEvidenceChange.Split(
                    SurfaceId(41),
                    listOf(
                        canonicalTarget(null, firstTarget, 16, 16, 200),
                        canonicalTarget(null, secondTarget, 16, 16, 200),
                    ),
                )),
                acceptedSamples = 2,
                rayVisits = 42,
                touchedRows = 2,
                splitCount = 1,
                virtualWork = 46,
                relationCount = 2,
                relationAdmissionWork = 4,
            ),
            result,
        )
        val split = result.changes.single() as DepthEvidenceChange.Split
        assertThrows(RuntimeException::class.java) {
            (split.targets as MutableList).add(split.targets.single())
        }
        assertEquals(2, split.targets.size)

        val fullView = FakeCanonicalView(
            surfaces = mapOf(firstTarget to source, secondTarget to source),
            reportedSurfaceCount = 100,
        )
        val fullKernel = DepthEvidenceKernel()
        repeat(3) { index ->
            fullKernel.prepare(depthBatchForSamples(index + 1L, frame(), identity(), intrinsics, samples), fullView)
            fullKernel.applyPrepared()
        }
        assertEquals(
            DepthEvidenceResult.Refused(
                DepthEvidenceRefusal.SURFACE_CAPACITY,
                expectedAccepted(
                    3, acceptedSamples = 2, rayVisits = 42, touchedRows = 2, virtualWork = 46,
                    relationCount = 2,
                    relationAdmissionWork = 4,
                ).receipt.copy(capacityRefusals = 1),
            ),
            fullKernel.prepare(depthBatchForSamples(4, frame(), identity(), intrinsics, samples), fullView),
        )
    }

    @Test
    fun `depth observations derive replace from two sources across two targets`() {
        val firstTarget = Voxel(-8, 0, -10)
        val secondTarget = Voxel(7, 0, -10)
        val firstSource = surface(51, Voxel(-2, 0, -10))
        val secondSource = surface(52, Voxel(2, 0, -10))
        val view = FakeCanonicalView(
            surfaces = mapOf(firstTarget to firstSource, secondTarget to secondSource),
            idLookup = mapOf(firstSource.id to firstSource, secondSource.id to secondSource),
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
        view.replaceSurfaces(mapOf(firstTarget to secondSource, secondTarget to firstSource))
        view.reportSurfaceCount(100)
        val result = kernel.prepare(
            depthBatchForSamples(4, frame(), identity(), intrinsics, samples), view,
        ) as DepthEvidenceResult.Accepted

        assertEquals(
            expectedAccepted(
                4,
                changes = listOf(DepthEvidenceChange.Replace(
                    listOf(SurfaceId(51), SurfaceId(52)),
                    listOf(
                        canonicalTarget(null, firstTarget, 16, 16, 200),
                        canonicalTarget(null, secondTarget, 16, 16, 200),
                    ),
                )),
                acceptedSamples = 2,
                rayVisits = 42,
                touchedRows = 2,
                replaceCount = 1,
                virtualWork = 46,
                relationCount = 4,
            ),
            result,
        )
        val replace = result.changes.single() as DepthEvidenceChange.Replace
        assertThrows(RuntimeException::class.java) {
            (replace.sourceIds as MutableList).add(SurfaceId(99))
        }
        assertThrows(RuntimeException::class.java) {
            (replace.targets as MutableList).add(replace.targets.single())
        }
        assertEquals(listOf(SurfaceId(51), SurfaceId(52)), replace.sourceIds)
        assertEquals(2, replace.targets.size)
    }

    @Test
    fun `two removals sharing one source refuse as source overlap`() {
        val first = Voxel(-4, 0, -5)
        val second = Voxel(3, 0, -5)
        val source = surface(71, Voxel(0, 0, -7))
        val view = FakeCanonicalView(
            surfaces = mapOf(first to source, second to source),
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

        assertEquals(
            DepthEvidenceResult.Refused(
                DepthEvidenceRefusal.SOURCE_OVERLAP,
                expectedAccepted(
                    22,
                    acceptedSamples = 2,
                    rayVisits = 56,
                    touchedRows = 5,
                    virtualWork = 63,
                    relationCount = 2,
                ).receipt,
            ),
            candidate,
        )
        assertEquals(0, kernel.resourceReceipt().preparedEvidenceRows)
    }

    @Test
    fun `canonical source lookup inconsistencies refuse preparation`() {
        val target = Voxel(0, 0, -10)
        val source = surface(61, target)

        val missing = DepthEvidenceKernel().prepare(
            depthBatchForFrame(1, frame(), identity()),
            FakeCanonicalView(surfaces = mapOf(target to source), idLookup = emptyMap()),
        ) as DepthEvidenceResult.Refused
        assertEquals(
            DepthEvidenceResult.Refused(DepthEvidenceRefusal.CANONICAL_LOOKUP_FAILED, DepthEvidenceReceipt()),
            missing,
        )

        val conflicting = DepthEvidenceKernel().prepare(
            depthBatchForFrame(1, frame(), identity()),
            FakeCanonicalView(
                surfaces = mapOf(target to source),
                idLookup = mapOf(SurfaceId(61) to surface(61, Voxel(1, 0, -10))),
            ),
        ) as DepthEvidenceResult.Refused
        assertEquals(
            DepthEvidenceResult.Refused(DepthEvidenceRefusal.DUPLICATE_TARGET, DepthEvidenceReceipt()),
            conflicting,
        )
    }

    @Test
    fun `canonical lookup refuses evidence bound to a different addressed voxel`() {
        val target = Voxel(0, 0, -10)
        val source = surface(62, Voxel(3, 0, -10))
        val result = DepthEvidenceKernel().prepare(
            depthBatchForFrame(1, frame(), identity()),
            FakeCanonicalView(
                surfaces = mapOf(target to source),
                addressedVoxelOverride = Voxel(1, 0, -10),
            ),
        )

        assertEquals(
            DepthEvidenceResult.Refused(
                DepthEvidenceRefusal.CANONICAL_LOOKUP_FAILED,
                DepthEvidenceReceipt(),
            ),
            result,
        )
    }

    @Test
    fun `threshold crossing refuses a stale retained source identity`() {
        val target = Voxel(0, 0, -10)
        val old = surface(71, target)
        val replacement = surface(72, target)
        val kernel = DepthEvidenceKernel(DepthEvidenceConfiguration(occupiedEvidenceToShow = 2))
        val first = kernel.prepare(
            depthBatchForFrame(1, frame(), identity()),
            FakeCanonicalView(surfaces = mapOf(target to old)),
        ) as DepthEvidenceResult.Accepted
        kernel.applyPrepared()

        assertEquals(
            DepthEvidenceResult.Refused(DepthEvidenceRefusal.CANONICAL_LOOKUP_FAILED, first.receipt),
            kernel.prepare(
                depthBatchForFrame(2, frame(), identity()),
                FakeCanonicalView(surfaces = mapOf(target to replacement)),
            ),
        )
        assertEquals(0, kernel.resourceReceipt().preparedEvidenceRows)
    }

    @Test
    fun `address alias emits a resolvable relocated source`() {
        val addressed = Voxel(0, 0, -10)
        val canonical = surface(73, Voxel(3, 0, -10))
        val view = FakeCanonicalView(
            surfaces = mapOf(addressed to canonical),
            idLookup = mapOf(canonical.id to canonical),
        )
        val kernel = DepthEvidenceKernel(DepthEvidenceConfiguration(occupiedEvidenceToShow = 2))
        kernel.prepare(depthBatchForFrame(1, frame(), identity()), view)
        kernel.applyPrepared()

        val emitted = kernel.prepare(depthBatchForFrame(2, frame(), identity()), view)
            as DepthEvidenceResult.Accepted
        val relocate = emitted.changes.single() as DepthEvidenceChange.Relocate
        assertEquals(canonical.id, relocate.sourceId)
        assertEquals(addressed, relocate.target.voxel)
        assertEquals(canonical.id, view.findSurfaceById(relocate.sourceId)?.id)
    }

    @Test
    fun `implicit relations retain unsigned source extremes in canonical order`() {
        val target = Voxel(0, 0, -10)
        val maximum = surface(0xffff_ffffL, Voxel(2, 0, -10))
        val minimum = surface(1, Voxel(-2, 0, -10))
        val kernel = DepthEvidenceKernel(DepthEvidenceConfiguration(occupiedEvidenceToShow = 2))
        kernel.prepare(
            depthBatchForFrame(1, frame(), identity()),
            FakeCanonicalView(surfaces = mapOf(target to maximum)),
        )
        kernel.applyPrepared()
        val view = FakeCanonicalView(
            surfaces = mapOf(target to minimum),
            idLookup = mapOf(minimum.id to minimum, maximum.id to maximum),
        )

        val result = kernel.prepare(depthBatchForFrame(2, frame(), identity()), view)
            as DepthEvidenceResult.Accepted
        val merge = result.changes.single() as DepthEvidenceChange.Merge
        assertEquals(listOf(minimum.id, maximum.id), merge.sourceIds)
        assertEquals(target, merge.target.voxel)
    }

    @Test
    fun `rotated group emits the locked target and group-space normal`() {
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView()
        repeat(3) { index ->
            assertEquals(
                expectedAccepted(index + 1L, rayVisits = 11, virtualWork = 13),
                kernel.prepare(depthBatchForFrame(index + 1L, rotatedFrame(), rotationY180()), view),
            )
            kernel.applyPrepared()
        }
        val result = kernel.prepare(depthBatchForFrame(4, rotatedFrame(), rotationY180()), view)
            as DepthEvidenceResult.Accepted

        assertEquals(
            expectedAccepted(
                4,
                changes = listOf(DepthEvidenceChange.Create(
                    canonicalTarget(null, Voxel(0, 0, 10), 127, 127, 255),
                )),
                createCount = 1,
                rayVisits = 11,
                virtualWork = 13,
            ),
            result,
        )
    }

    @Test
    fun `four independent occupied observations prepare one create and apply atomically`() {
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView()
        val first = batch(1, frame(), translation(0.0, 0.0, 0.0), listOf(sample()))

        repeat(3) { index ->
            assertEquals(expectedAccepted(index + 1L, rayVisits = 31, virtualWork = 33), kernel.prepare(first.copyWithTimestamp(index + 1L), view))
            kernel.applyPrepared()
        }
        val staged = kernel.prepare(first.copyWithTimestamp(4), view) as DepthEvidenceResult.Accepted

        assertEquals(
            expectedAccepted(
                4,
                changes = listOf(DepthEvidenceChange.Create(
                    canonicalTarget(null, Voxel(-8, 5, -10), 42, -28, 255),
                )),
                createCount = 1,
                rayVisits = 31,
                virtualWork = 33,
            ),
            staged,
        )
        assertEquals(1, kernel.resourceReceipt().residentEvidenceRows)
        assertEquals(DepthEvidenceApplyResult.Applied(staged.receipt), kernel.applyPrepared())
        assertEquals(1, kernel.resourceReceipt().residentEvidenceRows)
        assertEquals(32, kernel.resourceReceipt().residentBytes)
        assertEquals(DepthEvidenceApplyResult.NoPrepared(staged.receipt), kernel.applyPrepared())
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
        )
        val kernel = DepthEvidenceKernel()
        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatch(index + 1L, cameraX = 0.05, endpointX = 0.05),
                view,
            ) as DepthEvidenceResult.Accepted
            val changes = if (index == 3) listOf(
                DepthEvidenceChange.Create(
                    canonicalTarget(null, Voxel(0, 0, -10), 0, 0, 255),
                ),
            ) else emptyList()
            assertEquals(
                expectedAccepted(
                    index + 1L,
                    changes = changes,
                    rayVisits = 11,
                    touchedRows = 3,
                    directionVotes = if (index == 0) 1 else 0,
                    createCount = if (index == 3) 1 else 0,
                    virtualWork = 15,
                    relationCount = 2,
                ),
                result,
            )
            kernel.applyPrepared()
        }
        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatch(10L + index, cameraX = 0.55, endpointX = -0.45),
                view,
            ) as DepthEvidenceResult.Accepted
            val changes = if (index == 3) listOf(
                DepthEvidenceChange.Create(
                    canonicalTarget(null, Voxel(-5, 0, -10), 63, 0, 255),
                ),
                DepthEvidenceChange.Remove(SurfaceId(7)),
            ) else emptyList()
            assertEquals(
                expectedAccepted(
                    10L + index,
                    changes = changes,
                    rayVisits = 31,
                    touchedRows = 2,
                    directionVotes = if (index == 0) 1 else 0,
                    createCount = if (index == 3) 1 else 0,
                    removeCount = if (index == 3) 1 else 0,
                    virtualWork = 34,
                    relationCount = 1,
                ),
                result,
            )
            kernel.applyPrepared()
        }

        val last = kernel.prepare(
            depthBatch(30, cameraX = 0.55, endpointX = -0.45),
            view,
        ) as DepthEvidenceResult.Accepted
        assertEquals(expectedAccepted(30, rayVisits = 31, touchedRows = 2, virtualWork = 34, relationCount = 1), last)
    }

    @Test
    fun `one observation with repeated rays contributes one free vote`() {
        val stale = Voxel(0, 0, -5)
        val view = FakeCanonicalView(
            surfaces = mapOf(stale to surface(7, stale)),
        )
        val kernel = DepthEvidenceKernel()
        val result = kernel.prepare(depthBatch(1, 0.05, 0.05), view) as DepthEvidenceResult.Accepted

        assertEquals(
            expectedAccepted(1, rayVisits = 11, touchedRows = 2, directionVotes = 1, virtualWork = 14, relationCount = 1),
            result,
        )
    }

    @Test
    fun `carved canonical identity is restored after four occupied observations`() {
        val stale = Voxel(0, 0, -5)
        val view = FakeCanonicalView(
            surfaces = mapOf(stale to surface(7, stale)),
        )
        val kernel = DepthEvidenceKernel()
        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatch(1L + index, cameraX = 0.05, endpointX = 0.05), view,
            ) as DepthEvidenceResult.Accepted
            val changes = if (index == 3) listOf(
                DepthEvidenceChange.Create(canonicalTarget(null, Voxel(0, 0, -10), 0, 0, 255)),
            ) else emptyList()
            assertEquals(
                expectedAccepted(
                    1L + index,
                    changes = changes,
                    rayVisits = 11,
                    touchedRows = 2,
                    directionVotes = if (index == 0) 1 else 0,
                    createCount = if (index == 3) 1 else 0,
                    virtualWork = 14,
                    relationCount = 1,
                ),
                result,
            )
            kernel.applyPrepared()
        }
        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatch(10L + index, cameraX = 0.55, endpointX = -0.45), view,
            ) as DepthEvidenceResult.Accepted
            val changes = if (index == 3) listOf(
                DepthEvidenceChange.Create(canonicalTarget(null, Voxel(-5, 0, -10), 63, 0, 255)),
                DepthEvidenceChange.Remove(SurfaceId(7)),
            ) else emptyList()
            assertEquals(
                expectedAccepted(
                    10L + index,
                    changes = changes,
                    rayVisits = 31,
                    touchedRows = 2,
                    directionVotes = if (index == 0) 1 else 0,
                    createCount = if (index == 3) 1 else 0,
                    removeCount = if (index == 3) 1 else 0,
                    virtualWork = 34,
                    relationCount = 1,
                ),
                result,
            )
            kernel.applyPrepared()
        }

        repeat(3) { index ->
            val result = kernel.prepare(
                depthBatch(30L + index, cameraX = 0.05, endpointX = 0.05, depthMillimeters = 500), view,
            ) as DepthEvidenceResult.Accepted
            assertEquals(
                expectedAccepted(
                    30L + index,
                    rayVisits = 6,
                    conflictsRetained = 1,
                    virtualWork = 8,
                    relationCount = 1,
                    relationAdmissionWork = 2,
                ),
                result,
            )
            kernel.applyPrepared()
        }
        val restored = kernel.prepare(depthBatch(40, 0.05, 0.05, depthMillimeters = 500), view)
            as DepthEvidenceResult.Accepted
        assertEquals(
            expectedAccepted(
                40,
                changes = listOf(DepthEvidenceChange.Refine(
                    SurfaceId(7), canonicalTarget(SurfaceId(7), stale, 16, 16, 200),
                )),
                rayVisits = 6,
                refineCount = 1,
                conflictsRetained = 1,
                virtualWork = 8,
                relationCount = 1,
                relationAdmissionWork = 2,
            ),
            restored,
        )
        kernel.applyPrepared()
        val next = kernel.prepare(depthBatch(41, 0.05, 0.05, depthMillimeters = 500), view)
            as DepthEvidenceResult.Accepted
        assertEquals(
            expectedAccepted(41, rayVisits = 6, virtualWork = 8, relationCount = 1, relationAdmissionWork = 2),
            next,
        )
    }

    @Test
    fun `voxel volume touching the safety band is rejected at exact boundary`() {
        val candidate = Voxel(0, 0, -3)
        val view = FakeCanonicalView(mapOf(candidate to surface(7, candidate)))
        val largeFrame = frame(voxelSizeMicrometres = 300_000)
        val result = kernelForSafety().prepare(
            depthBatchForFrame(1, largeFrame, translation(0.0, 0.0, -0.05)),
            view,
        ) as DepthEvidenceResult.Accepted

        assertEquals(expectedAccepted(1, rayVisits = 4, touchedRows = 2, virtualWork = 7, relationCount = 1), result)
    }

    @Test
    fun `ray cells behind the endpoint cannot provide free evidence`() {
        val candidate = Voxel(0, 0, -13)
        val view = FakeCanonicalView(mapOf(candidate to surface(7, candidate)))
        val result = kernelForSafety().prepare(
            depthBatchForFrame(1, frame(), translation(0.0, 0.0, 0.0)),
            view,
        ) as DepthEvidenceResult.Accepted

        assertEquals(expectedAccepted(1, rayVisits = 11, touchedRows = 1, virtualWork = 13), result)
    }

    @Test
    fun `corridor fixture records the clear span but not the phantom safety band`() {
        val clear = Voxel(0, 0, -6)
        val safety = Voxel(0, 0, -9)
        val behind = Voxel(0, 0, -13)
        val view = FakeCanonicalView(
            mapOf(clear to surface(1, clear), safety to surface(2, safety), behind to surface(3, behind)),
        )

        val result = DepthEvidenceKernel().prepare(
            depthBatchForFrame(1, frame(), translation(0.05, 0.05, 0.05)),
            view,
        ) as DepthEvidenceResult.Accepted

        assertEquals(
            expectedAccepted(1, rayVisits = 11, touchedRows = 3, directionVotes = 1, virtualWork = 15, relationCount = 2),
            result,
        )
    }

    @Test
    fun `five voxel corridor reduction removes the literal phantom span`() {
        val phantom = listOf(
            Voxel(0, 0, -6), Voxel(0, 0, -5), Voxel(0, 0, -4),
            Voxel(0, 0, -3), Voxel(0, 0, -2),
        )
        val sources = phantom.mapIndexed { index, voxel -> voxel to surface(101L + index, voxel) }.toMap()
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView(surfaces = sources)
        val emittedChanges = mutableListOf<DepthEvidenceChange>()
        var lastResult: DepthEvidenceResult.Accepted? = null

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
                emittedChanges += result.changes
                lastResult = result
                kernel.applyPrepared()
            }
        }

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
                emittedChanges += result.changes
                lastResult = result
                kernel.applyPrepared()
            }
        }

        assertEquals(
            listOf(
                DepthEvidenceChange.Refine(SurfaceId(101), canonicalTarget(SurfaceId(101), phantom[0], 16, 16, 200)),
                DepthEvidenceChange.Refine(SurfaceId(102), canonicalTarget(SurfaceId(102), phantom[1], 16, 16, 200)),
                DepthEvidenceChange.Create(canonicalTarget(null, Voxel(-3, 0, -10), 57, 0, 255)),
                DepthEvidenceChange.Refine(SurfaceId(103), canonicalTarget(SurfaceId(103), phantom[2], 16, 16, 200)),
                DepthEvidenceChange.Refine(SurfaceId(104), canonicalTarget(SurfaceId(104), phantom[3], 16, 16, 200)),
                DepthEvidenceChange.Refine(SurfaceId(105), canonicalTarget(SurfaceId(105), phantom[4], 16, 16, 200)),
                DepthEvidenceChange.Create(canonicalTarget(null, Voxel(0, 0, -10), 0, 0, 255)),
                DepthEvidenceChange.Remove(SurfaceId(101)),
                DepthEvidenceChange.Remove(SurfaceId(102)),
                DepthEvidenceChange.Remove(SurfaceId(103)),
                DepthEvidenceChange.Create(canonicalTarget(null, Voxel(-5, 0, -10), 63, 0, 255)),
                DepthEvidenceChange.Create(canonicalTarget(null, Voxel(-7, 0, -10), 70, 0, 255)),
                DepthEvidenceChange.Remove(SurfaceId(104)),
                DepthEvidenceChange.Create(canonicalTarget(null, Voxel(-12, 0, -10), 79, 0, 255)),
                DepthEvidenceChange.Remove(SurfaceId(105)),
                DepthEvidenceChange.Create(canonicalTarget(null, Voxel(-20, 0, -10), 90, 0, 255)),
            ),
            emittedChanges,
        )
        assertEquals(expectedAccepted(139, rayVisits = 11, touchedRows = 6, virtualWork = 18, relationCount = 5), lastResult)
        assertEquals(11, kernel.resourceReceipt().residentEvidenceRows)
    }

    @Test
    fun `viable capacity carves a stale row while preserving the bounded budget`() {
        val stale = Voxel(0, 0, -3)
        val staleSurface = surface(81, stale)
        val view = FakeCanonicalView(surfaces = mapOf(stale to staleSurface))
        val groupFrame = frame(voxelSizeMicrometres = 200_000)
        val kernel = DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceCapacity = 2))

        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatchForDepth(1L + index, groupFrame, identity(), 500),
                view,
            ) as DepthEvidenceResult.Accepted
            assertEquals(
                expectedAccepted(
                    1L + index,
                    changes = if (index == 3) listOf(DepthEvidenceChange.Refine(
                        SurfaceId(81), canonicalTarget(SurfaceId(81), stale, 16, 16, 200),
                    )) else emptyList(),
                    refineCount = if (index == 3) 1 else 0,
                    rayVisits = 4,
                    virtualWork = 6,
                    relationCount = 1,
                    relationAdmissionWork = 2,
                ),
                result,
            )
            kernel.applyPrepared()
        }
        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatchForDepth(10L + index, groupFrame, identity(), 1_000),
                view,
            ) as DepthEvidenceResult.Accepted
            assertEquals(
                expectedAccepted(
                    10L + index,
                    changes = if (index == 3) listOf(DepthEvidenceChange.Create(
                        canonicalTarget(null, Voxel(0, 0, -5), 0, 0, 255),
                    )) else emptyList(),
                    createCount = if (index == 3) 1 else 0,
                    rayVisits = 6,
                    touchedRows = 2,
                    directionVotes = if (index == 0) 1 else 0,
                    virtualWork = 9,
                    relationCount = 1,
                ),
                result,
            )
            kernel.applyPrepared()
        }

        val carvingView = FakeCanonicalView(
            surfaces = mapOf(stale to staleSurface),
        )
        repeat(8) { index ->
            val result = kernel.prepare(
                depthBatchAtFrame(
                    timestamp = 20L + index,
                    groupFrame = groupFrame,
                    cameraX = if (index < 4) 0.35 else 0.05,
                    endpointX = 0.05,
                    depthMillimeters = 1_000,
                ),
                carvingView,
            ) as DepthEvidenceResult.Accepted
            assertEquals(
                expectedAccepted(
                    20L + index,
                    changes = if (index == 3) listOf(DepthEvidenceChange.Remove(SurfaceId(81))) else emptyList(),
                    rayVisits = if (index < 4) 7 else 6,
                    touchedRows = 2,
                    directionVotes = if (index == 0) 1 else 0,
                    removeCount = if (index == 3) 1 else 0,
                    virtualWork = if (index < 4) 10 else 9,
                    relationCount = 1,
                ),
                result,
            )
            kernel.applyPrepared()
        }

        assertEquals(
            DepthEvidenceResourceReceipt(2, 64, 0, 0, 2, 2_160, false, 2_546_760, 2_548_920),
            kernel.resourceReceipt(),
        )
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

        assertEquals(
            expectedAccepted(
                1,
                acceptedSamples = V2_DEPTH_SAMPLE_CAPACITY,
                rayVisits = 16_896,
                virtualWork = 18_433,
            ),
            result,
        )
        assertEquals(12_676_816, kernel.resourceReceipt().fixedPrimitiveBytes)
        kernel.discardPrepared()
    }

    @Test
    fun `hole and low confidence samples retain conservative evidence`() {
        val hole = Voxel(2, 0, -5)
        val view = FakeCanonicalView(
            surfaces = mapOf(hole to surface(91, hole)),
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

        assertEquals(
            expectedAccepted(1, rejectedSamples = 1, rayVisits = 11, touchedRows = 1, virtualWork = 13),
            result,
        )
    }

    @Test
    fun `opposing supported thin and double walls survive the carve threshold`() {
        val thin = Voxel(0, 0, -9)
        val double = Voxel(0, 0, -12)
        val intrinsics = VisibilityCameraIntrinsics(1, 1, 1.0, 1.0, 0.0, 0.0)
        fun supportedWall(
            source: DepthCanonicalSurface,
            frontDepth: Int,
            opposingDepth: Int,
        ): Int {
            val kernel = DepthEvidenceKernel()
            val view = FakeCanonicalView(
                surfaces = mapOf(source.voxel to source),
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
                assertEquals(
                    expectedAccepted(
                        source.id.value * 10 + index,
                        changes = if (index == 7) listOf(
                            DepthEvidenceChange.Create(
                                canonicalTarget(null, Voxel(0, 0, -16), 123, 123, 255),
                            ),
                        ) else emptyList(),
                        acceptedSamples = 2,
                        rayVisits = if (source.id == SurfaceId(1)) 27 else if (index % 2 == 0) 30 else 24,
                        touchedRows = if (index == 0) 2 else 3,
                        directionVotes = when (index) { 0, 2 -> 1; 1 -> 2; else -> 0 },
                        createCount = if (index == 7) 1 else 0,
                        conflictsRetained = if (index < 2) 1 else 2,
                        virtualWork = if (source.id == SurfaceId(1)) {
                            if (index == 0) 31 else 32
                        } else if (index == 0) 34 else if (index % 2 == 0) 35 else 29,
                        relationCount = 1,
                        relationAdmissionWork = 3,
                    ),
                    result,
                )
                kernel.applyPrepared()
            }
            return kernel.resourceReceipt().residentEvidenceRows
        }
        assertEquals(3, supportedWall(surface(1, thin), frontDepth = 900, opposingDepth = 900))
        assertEquals(3, supportedWall(surface(2, double), frontDepth = 1_200, opposingDepth = 650))
    }

    @Test
    fun `hole foreground edge fixture rejects off-ray foreground evidence`() {
        val edge = Voxel(2, 0, -5)
        val view = FakeCanonicalView(mapOf(edge to surface(1, edge)))
        val result = DepthEvidenceKernel().prepare(
            depthBatchForFrame(1, frame(), translation(0.0, 0.0, 0.0)), view,
        ) as DepthEvidenceResult.Accepted

        assertEquals(expectedAccepted(1, rayVisits = 11, touchedRows = 1, virtualWork = 13), result)
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
        assertEquals(expectedAccepted(5, rayVisits = 11, virtualWork = 13), refusal)
        val committedReceipt = expectedAccepted(
            4,
            changes = listOf(DepthEvidenceChange.Create(
                canonicalTarget(null, Voxel(0, 0, -10), 0, 0, 255),
            )),
            createCount = 1,
            rayVisits = 11,
            virtualWork = 13,
        ).receipt
        assertEquals(
            DepthEvidenceResult.Refused(DepthEvidenceRefusal.PREPARED_BUSY, committedReceipt),
            kernel.prepare(valid.copyWithTimestamp(6), view),
        )
        assertEquals(
            DepthEvidenceResult.Refused(DepthEvidenceRefusal.PREPARED_BUSY, committedReceipt),
            kernel.prepare(valid.copyWithTimestamp(7), view),
        )
        kernel.discardPrepared()
        assertEquals(before, kernel.resourceReceipt())

        val invalid = valid.copyWithTimestamp(8).withSamples(listOf(VisibilityDepthSample(0, 0, 1, 255)))
        val invalidResult = kernel.prepare(invalid, view) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.INVALID_SAMPLE, committedReceipt), invalidResult)
        assertEquals(before, kernel.resourceReceipt())
    }

    @Test
    fun `close releases logical rows and refuses later preparation`() {
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView()
        kernel.prepare(depthBatch(1, 0.05, 0.05), view)
        kernel.applyPrepared()
        kernel.close()

        val closed = DepthEvidenceResourceReceipt(0, 0, 0, 0, 100_000, 0, true, 0, 0)
        assertEquals(closed, kernel.resourceReceipt())
        kernel.close()
        assertEquals(closed, kernel.resourceReceipt())
        assertEquals(
            DepthEvidenceResult.Refused(DepthEvidenceRefusal.CLOSED, expectedAccepted(1, rayVisits = 11, virtualWork = 13).receipt),
            kernel.prepare(depthBatch(2, 0.05, 0.05), view),
        )
    }

    @Test
    fun `canonical cut change during addressed lookup cannot publish mixed evidence`() {
        val kernel = DepthEvidenceKernel()
        val view = RevisionMutatingCanonicalView(mutateDuringAddressLookup = true)

        assertEquals(
            DepthEvidenceResult.Refused(DepthEvidenceRefusal.STALE_CANONICAL_CUT, DepthEvidenceReceipt()),
            kernel.prepare(depthBatchForFrame(1, frame(), identity()), view),
        )
        assertEquals(0, kernel.resourceReceipt().preparedEvidenceRows)
        assertEquals(DepthEvidenceApplyResult.NoPrepared(DepthEvidenceReceipt()), kernel.applyPrepared())
        assertEquals(
            expectedAccepted(2, rayVisits = 11, virtualWork = 13, geometryRevision = 2, lineageRevision = 1),
            kernel.prepare(depthBatchForFrame(2, frame(), identity()), view),
        )
    }

    @Test
    fun `canonical cut change during source lookup cannot publish mixed evidence`() {
        val canonical = surface(41, Voxel(0, 0, -10))
        val kernel = DepthEvidenceKernel()
        val view = RevisionMutatingCanonicalView(canonical, mutateDuringSourceLookup = true)

        assertEquals(
            DepthEvidenceResult.Refused(DepthEvidenceRefusal.STALE_CANONICAL_CUT, DepthEvidenceReceipt()),
            kernel.prepare(depthBatchForFrame(1, frame(), identity()), view),
        )
        assertEquals(0, kernel.resourceReceipt().preparedEvidenceRows)
        assertEquals(DepthEvidenceApplyResult.NoPrepared(DepthEvidenceReceipt()), kernel.applyPrepared())
    }

    @Test
    fun `stable canonical cut publishes its single atomic revision pair`() {
        val view = RevisionMutatingCanonicalView(revision = CanonicalRevisionPair(7, 9))
        val result = DepthEvidenceKernel().prepare(depthBatchForFrame(1, frame(), identity()), view)

        assertEquals(expectedAccepted(1, rayVisits = 11, virtualWork = 13, geometryRevision = 7, lineageRevision = 9), result)
        assertEquals(2, view.revisionReads)
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

    private fun canonicalTarget(
        id: SurfaceId?,
        voxel: Voxel,
        normalOctX: Int,
        normalOctY: Int,
        normalConfidence: Int,
    ) = CanonicalTarget(id, voxel, normalOctX, normalOctY, normalConfidence)

    private fun expectedAccepted(
        sequence: Long,
        changes: List<DepthEvidenceChange> = emptyList(),
        acceptedSamples: Int = 1,
        rejectedSamples: Int = 0,
        rayVisits: Int = 0,
        touchedRows: Int = 1,
        directionVotes: Int = 0,
        createCount: Int = 0,
        refineCount: Int = 0,
        relocateCount: Int = 0,
        mergeCount: Int = 0,
        splitCount: Int = 0,
        replaceCount: Int = 0,
        removeCount: Int = 0,
        conflictsRetained: Int = 0,
        capacityRefusals: Int = 0,
        overflowCount: Int = 0,
        virtualWork: Int = acceptedSamples + rayVisits + touchedRows,
        relationCount: Int = changes.sumOf { change ->
            when (change) {
                is DepthEvidenceChange.Create -> 0
                is DepthEvidenceChange.Refine, is DepthEvidenceChange.Relocate,
                is DepthEvidenceChange.Split, is DepthEvidenceChange.Remove -> 1
                is DepthEvidenceChange.Merge -> change.sourceIds.size
                is DepthEvidenceChange.Replace -> change.sourceIds.size
            }
        },
        relationAdmissionWork: Int = relationCount,
        geometryRevision: Long = 0,
        lineageRevision: Long = 0,
    ): DepthEvidenceResult.Accepted {
        val positiveTargets = changes.sumOf { change ->
            when (change) {
                is DepthEvidenceChange.Create, is DepthEvidenceChange.Refine,
                is DepthEvidenceChange.Relocate, is DepthEvidenceChange.Merge -> 1
                is DepthEvidenceChange.Split -> change.targets.size
                is DepthEvidenceChange.Replace -> change.targets.size
                is DepthEvidenceChange.Remove -> 0
            }
        }
        val positiveSources = changes.flatMap { change ->
            when (change) {
                is DepthEvidenceChange.Refine -> listOf(change.sourceId)
                is DepthEvidenceChange.Relocate -> listOf(change.sourceId)
                is DepthEvidenceChange.Merge -> change.sourceIds
                is DepthEvidenceChange.Split -> listOf(change.sourceId)
                is DepthEvidenceChange.Replace -> change.sourceIds
                is DepthEvidenceChange.Create, is DepthEvidenceChange.Remove -> emptyList()
            }
        }.distinct().size
        val removals = changes.count { it is DepthEvidenceChange.Remove }
        val planningAndValidationWork =
            relationAdmissionWork + 3 * touchedRows + 11 * relationCount + 10 * positiveTargets +
                18 * positiveSources + 2 * changes.size + 9 * removals
        val accountedWork = virtualWork + touchedRows * 24 + planningAndValidationWork
        return DepthEvidenceResult.Accepted(
        expectedGeometryRevision = geometryRevision,
        expectedLineageRevision = lineageRevision,
        changes = changes,
        receipt = DepthEvidenceReceipt(
            sequence = sequence,
            sourceTimestampNs = sequence,
            acceptedSamples = acceptedSamples,
            rejectedSamples = rejectedSamples,
            rayVisits = rayVisits,
            touchedEvidenceRows = touchedRows,
            independentDirectionVotes = directionVotes,
            createCount = createCount,
            refineCount = refineCount,
            relocateCount = relocateCount,
            mergeCount = mergeCount,
            splitCount = splitCount,
            replaceCount = replaceCount,
            removeCount = removeCount,
            conflictsRetained = conflictsRetained,
            capacityRefusals = capacityRefusals,
            overflowCount = overflowCount,
            preparedResidentBytes = touchedRows * 32,
            p50VirtualWorkUnits = accountedWork,
            p95VirtualWorkUnits = accountedWork,
        ),
        work = DepthEvidenceWorkReceipt(
            distinctTouchedVoxelCount = touchedRows,
            emittedChangeCount = changes.size,
            rayVisits = rayVisits,
            independentDirectionVotes = directionVotes,
            virtualWorkUnits = accountedWork,
        ),
    )
    }

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
        private var surfaces: Map<Voxel, DepthCanonicalSurface> = emptyMap(),
        private val idLookup: Map<SurfaceId, DepthCanonicalSurface>? = null,
        private val addressedVoxelOverride: Voxel? = null,
        reportedSurfaceCount: Int? = null,
    ) : BoundedCanonicalSurfaceView {
        override val revisionPair = CanonicalRevisionPair(0, 0)
        private var reportedCount: Int? = reportedSurfaceCount
        override val surfaceCount: Int get() = reportedCount ?: maxOf(surfaces.size, idLookup?.size ?: 0)
        override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? = if (idLookup != null) {
            idLookup[id]
        } else {
            surfaces.values.firstOrNull { it.id == id }
        }

        override fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface? =
            surfaces[voxel]?.let { AddressedCanonicalSurface(addressedVoxelOverride ?: voxel, it) }

        override fun visitRayCells(
            startGroupMm: DepthPointMm,
            endpointGroupMm: DepthPointMm,
            maximumVisits: Int,
            visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
        ): DepthRayVisitResult = error("prepare must own ray traversal")

        fun replaceSurfaces(replacement: Map<Voxel, DepthCanonicalSurface>) {
            surfaces = replacement
        }

        fun reportSurfaceCount(count: Int) {
            reportedCount = count
        }

    }

    private class RevisionMutatingCanonicalView(
        private val canonical: DepthCanonicalSurface? = null,
        private val mutateDuringAddressLookup: Boolean = false,
        private val mutateDuringSourceLookup: Boolean = false,
        revision: CanonicalRevisionPair = CanonicalRevisionPair(1, 1),
    ) : BoundedCanonicalSurfaceView {
        private var currentRevision = revision
        private var mutated = false
        var revisionReads = 0
            private set
        override val revisionPair: CanonicalRevisionPair
            get() {
                revisionReads++
                return currentRevision
            }
        override val surfaceCount: Int = if (canonical == null) 0 else 1

        override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? {
            if (mutateDuringSourceLookup && !mutated) advanceRevision()
            return canonical?.takeIf { it.id == id }
        }

        override fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface? {
            if (mutateDuringAddressLookup && !mutated) advanceRevision()
            return canonical?.let { AddressedCanonicalSurface(voxel, it) }
        }

        override fun visitRayCells(
            startGroupMm: DepthPointMm,
            endpointGroupMm: DepthPointMm,
            maximumVisits: Int,
            visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
        ): DepthRayVisitResult = error("prepare must own ray traversal")

        private fun advanceRevision() {
            currentRevision = currentRevision.copy(geometryRevision = currentRevision.geometryRevision + 1)
            mutated = true
        }
    }

    private class BlockingCanonicalView(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : BoundedCanonicalSurfaceView {
        private var blocked = false
        override val revisionPair: CanonicalRevisionPair
            get() {
                if (!blocked) {
                    blocked = true
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return CanonicalRevisionPair(0, 0)
            }
        override val surfaceCount: Int = 0
        override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? = null
        override fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface? = null
        override fun visitRayCells(
            startGroupMm: DepthPointMm,
            endpointGroupMm: DepthPointMm,
            maximumVisits: Int,
            visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
        ): DepthRayVisitResult = error("prepare must own ray traversal")
    }

    private enum class ThrowAccess { INITIAL_REVISION, FINAL_REVISION, SURFACE_COUNT, ADDRESS, SOURCE }

    private class ThrowingCanonicalView(
        private val access: ThrowAccess,
        private val canonical: DepthCanonicalSurface?,
    ) : BoundedCanonicalSurfaceView {
        private var thrown = false
        private var revisionReads = 0
        override val revisionPair: CanonicalRevisionPair
            get() {
                revisionReads++
                if (!thrown && (access == ThrowAccess.INITIAL_REVISION ||
                        access == ThrowAccess.FINAL_REVISION && revisionReads == 2)
                ) fail()
                return CanonicalRevisionPair(0, 0)
            }
        override val surfaceCount: Int
            get() {
                if (!thrown && access == ThrowAccess.SURFACE_COUNT) fail()
                return if (canonical == null) 0 else 1
            }
        override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? {
            if (!thrown && access == ThrowAccess.SOURCE) fail()
            return canonical?.takeIf { it.id == id }
        }
        override fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface? {
            if (!thrown && access == ThrowAccess.ADDRESS) fail()
            return canonical?.let { AddressedCanonicalSurface(voxel, it) }
        }
        override fun visitRayCells(
            startGroupMm: DepthPointMm,
            endpointGroupMm: DepthPointMm,
            maximumVisits: Int,
            visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
        ): DepthRayVisitResult = error("prepare must own ray traversal")

        private fun fail(): Nothing {
            thrown = true
            throw IllegalStateException("invalidated canonical view")
        }
    }
}
