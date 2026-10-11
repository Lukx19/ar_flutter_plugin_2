package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.File
import java.nio.file.Files
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/** Numerical characterization through real session-memory canonical mutation and exact ACK. */
class DepthCellPlaneEstimatorTest {
    @Test fun `bulk coherent plane preserves sample weights independent support and projected endpoints`() {
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, strategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { f ->
            val samples = gridSamples(1030) + List(4) { VisibilityDepthSample(20, 20, 1030, 255) }
            repeat(4) { i ->
                val accepted = f.step(f.batch(i + 1L, 1030, samples = samples))
                assertEquals(13, accepted.receipt.acceptedSamples)
                val receipt = f.kernel.bulkPlaneReceipt()
                assertEquals(1, receipt.populatedCells); assertEquals(1, receipt.qualifiedCells)
                assertEquals(1, receipt.covarianceSolves); assertEquals(1, receipt.certificateAttempts)
                assertEquals(13, receipt.projectedSamples)
                assertEquals(1, f.kernel.surfaceEstimationReceipt().independentPlaneFits)
                assertEquals(13 * 27, f.kernel.surfaceEstimationReceipt().associationCellLookups)
                val point = f.kernel.currentProjectedPointForTest()
                assertEquals(70.6, point.x, 1e-9); assertEquals(29.4, point.y, 1e-9)
                assertEquals(-1030.0, point.z, 1e-9)
            }
            assertEquals(1, f.rows().size)
            val position = requireNotNull(f.kernel.surfacePositionEstimate(Voxel(0, 0, -11)))
            assertEquals(50.0 + 4 * 20.6 / 13, position.xMillimetres, 1.0)
            assertEquals(50.0 - 4 * 20.6 / 13, position.yMillimetres, 1.0)
            assertEquals(4, position.independentSupportsToPublication)
        }
    }

    @Test fun `bulk boundary noise retains one canonical plane and exact temporal rollback`() {
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, strategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { f ->
            repeat(3) { f.step(f.batch(it + 1L, if (it % 2 == 0) 990 else 1010)) }
            val before = f.kernel.surfacePositionEstimate(Voxel(0, 0, -10))
            val beforeRows = f.kernel.resourceReceipt()
            val batch = f.batch(4, 1010)
            val prepared = f.prepare(batch) as DepthEvidenceResult.Accepted
            assertEquals(1, prepared.changes.size)
            assertEquals(before, f.kernel.surfacePositionEstimate(Voxel(0, 0, -10)))
            f.kernel.discardPrepared()
            assertEquals(before, f.kernel.surfacePositionEstimate(Voxel(0, 0, -10)))
            assertEquals(beforeRows, f.kernel.resourceReceipt())
            val repeated = f.prepare(batch) as DepthEvidenceResult.Accepted
            assertEquals(prepared.changes, repeated.changes)
            val mutation = f.commit(repeated, false)
            val current = f.currentAck()
            assertTrue(f.runtime.owner().acknowledgeCanonicalCurrent(current.copy(geometryRevision = current.geometryRevision + 1)) is CanonicalAcknowledgementResult.NoOp)
            assertEquals(DepthEvidenceRefusal.PREPARED_BUSY, (f.prepare(f.batch(5, 990)) as DepthEvidenceResult.Refused).reason)
            assertEquals(before, f.kernel.surfacePositionEstimate(Voxel(0, 0, -10)))
            assertClosedPhase(f.kernel)
            f.acknowledge()
            assertTrue(f.kernel.applyPrepared(mutation) is DepthEvidenceApplyResult.Applied)
            assertEquals(1, f.rows().size)
            assertEquals(4, requireNotNull(f.kernel.surfacePositionEstimate(Voxel(0, 0, -10))).independentSupportsToPublication)
            assertEquals(DepthEvidenceRefusal.DUPLICATE_TIMESTAMP, (f.prepare(batch) as DepthEvidenceResult.Refused).reason)
            val id = f.rows().single().surfaceId
            val lineage = requireNotNull(f.runtime.owner().activationState()).cut.lineageRevision
            val lineageCount = f.rows().single().lineageCount
            f.step(f.batch(5, 990))
            assertEquals(id, f.rows().single().surfaceId)
            assertEquals(lineage, requireNotNull(f.runtime.owner().activationState()).cut.lineageRevision)
            assertEquals(lineageCount, f.rows().single().lineageCount)
        }
    }

    @Test fun `bulk oblique and moving nonidentity planes keep signed geometric normals`() {
        val samples = gridSamples(1030).map { it.copy(depthMillimeters = (1030.0 / (1.0 - .5 * (it.x - 10) / OBSERVABLE_FOCAL)).toInt()) }
        Fixture(DepthSurfaceEstimationMode.LOCAL_NORMALS, strategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { f ->
            repeat(4) { f.step(f.batch(it + 1L, 1030, samples = samples)); assertEquals(1, f.kernel.bulkPlaneReceipt().qualifiedCells) }
            val error = f.rows().maxOf { normalError(it.packedNormal, .5, 0.0, 1.0) }
            println("depth-bulk-normal errorDegrees=$error")
            assertTrue(error < 2.0)
        }
        val group = yaw(Math.PI / 2, .13, .10, .10)
        val frame = VisibilityGroupFrame.copyOf(group, inverseRigid(group), 100_000, 256)
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, frame, DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { f ->
            repeat(8) { i ->
                val angle = if (i % 2 == 0) 0.0 else Math.PI / 9
                val camera = yaw(angle, .05 + kotlin.math.tan(angle), .05, 0.0)
                val points = gridSamples(1000).map { it.copy(depthMillimeters =
                    (-1000.0 / (-sin(angle) * (it.x - 10) / OBSERVABLE_FOCAL - cos(angle))).toInt()) }
                assertEquals(9, f.step(f.batch(i + 1L, 1000, camera = multiply(group, camera), samples = points)).receipt.acceptedSamples)
                assertTrue(f.kernel.bulkPlaneReceipt().qualifiedCells > 0)
            }
            assertEquals(1, f.rows().size)
            assertTrue(normalError(f.rows().single().packedNormal, 1.0, 0.0, 0.0) < 2.0)
        }
    }

    @Test fun `bulk mixed sheets crossing foreground and duplicate pixels retain raw population`() {
        val dense = (0..20).flatMap { y -> (0..20).map { x -> VisibilityDepthSample(x, y, 1030, 255) } }
        val variants = linkedMapOf(
            "parallel20" to (gridSamples(1030) + gridSamples(1050)),
            "parallel40" to (gridSamples(1030) + gridSamples(1070)),
            "sparseForeground" to (dense + VisibilityDepthSample(10, 10, 1070, 255)),
            "embeddedForeground" to dense.map { if (it.x == 10 && it.y == 10) it.copy(depthMillimeters = 1070) else it },
            "crossingUnique" to dense.map { if (it.x < 10) it.copy(depthMillimeters = 1030 + 2 * (10 - it.x)) else it },
            "checkerboard" to dense.map { if ((it.x + it.y) % 2 == 0) it.copy(depthMillimeters = 1070) else it },
            "crossing" to (dense + dense.map { it.copy(depthMillimeters = (1030.0 + 2.0 * (it.x - 10)).toInt()) }),
            "duplicateConflict" to (gridSamples(1030) + VisibilityDepthSample(10, 10, 1031, 255)),
            "collinear" to (0..20).map { VisibilityDepthSample(it, 10, 1030, 255) },
            "disconnected" to (listOf(0, 1, 39, 40).flatMap { y -> listOf(0, 1, 39, 40).map { x -> VisibilityDepthSample(x, y, 1030, 255) } }))
        for ((name, points) in variants) {
            Fixture(DepthSurfaceEstimationMode.DISABLED).use { raw ->
                Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, strategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { bulk ->
                    repeat(4) { i ->
                        val a = populationBatch(raw.frame, i + 1L, points, 41, 41, 500.0, 20.0)
                        val b = populationBatch(bulk.frame, i + 1L, points, 41, 41, 500.0, 20.0)
                        val reference = raw.step(a); val candidate = bulk.step(b)
                        assertEquals(name, points.size, candidate.receipt.acceptedSamples)
                        assertEquals(name, reference.changes, candidate.changes)
                        assertEquals(name, 0, bulk.kernel.bulkPlaneReceipt().qualifiedCells)
                        assertEquals(name, 0, bulk.kernel.bulkPlaneReceipt().projectedSamples)
                        assertEquals(name, 0, bulk.kernel.surfaceEstimationReceipt().independentPlaneFits)
                    }
                    assertEquals(name, raw.rows(), bulk.rows())
                }
            }
        }
    }

    @Test fun `bulk signed close layers scaled cells and thin foreground preserve identities`() {
        for (size in listOf(50_000, 100_000, 200_000)) {
            val frame = VisibilityGroupFrame.copyOf(identityVisibilityGridTransform(), identityVisibilityGridTransform(), size, 256)
            Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, frame, DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { f ->
                val separation = if (size == 50_000) 20 else 40
                val near = 1000 - separation / 2
                val far = 1000 + separation / 2
                val focal = if (size == 50_000) 820.0 else 500.0
                verifyObservableSquare(size, focal, near, far)
                repeat(8) { i ->
                    val depth = if (i % 2 == 0) near else far
                    val points = gridSamples(depth)
                    val camera = translation(size / 2e6, size / 2e6, 0.0)
                    val batch = DepthEvidenceBatch(i + 1L, i + 1L, frame, camera.toList(), VisibilityCameraIntrinsics(21, 21, focal, focal, 10.0, 10.0), points, 0)
                    assertEquals(9, f.step(batch).receipt.acceptedSamples)
                }
                assertEquals(2, f.rows().size)
                assertEquals(2, f.rows().map { it.surfaceId }.toSet().size)
            }
        }
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, strategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { f ->
            repeat(8) { i -> f.step(f.batch(i + 1L, 990, camera = if (i % 2 == 0) translation(.05, .05, 0.0) else yaw(Math.PI, .05, .05, -2.0))) }
            assertEquals(2, f.rows().size)
            assertTrue(f.rows().any { normalError(it.packedNormal, 0.0, 0.0, 1.0) < 1.0 })
            assertTrue(f.rows().any { normalError(it.packedNormal, 0.0, 0.0, -1.0) < 1.0 })
        }
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, strategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { f ->
            repeat(8) { i -> f.step(f.batch(i + 1L, 1030, samples = gridSamples(1030).map {
                if (it.x == 10 && it.y == 10) it.copy(depthMillimeters = 990) else it })) }
            assertEquals(setOf(-10, -11), f.rows().map { it.voxel.z }.toSet())
        }
    }

    @Test fun `bulk outlier first and population permutations preserve all member decisions`() {
        val points = gridSamples(1030) + List(4) { VisibilityDepthSample(20, 20, 1030, 255) }
        val variants = listOf(points, points.reversed(), points.drop(5) + points.take(5))
        val positions = variants.map { samples -> Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, strategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { f ->
            repeat(4) { f.step(f.batch(it + 1L, 1030, samples = samples)); assertEquals(1, f.kernel.bulkPlaneReceipt().qualifiedCells) }
            requireNotNull(f.kernel.surfacePositionEstimate(Voxel(0, 0, -11)))
        } }
        for (position in positions.drop(1)) { assertEquals(positions[0].xMillimetres, position.xMillimetres, 1.0)
            assertEquals(positions[0].yMillimetres, position.yMillimetres, 1.0); assertEquals(positions[0].packedNormal, position.packedNormal) }
        for (samples in listOf(listOf(VisibilityDepthSample(10, 10, 1070, 255)) + points,
            points + VisibilityDepthSample(10, 10, 1070, 255))) {
            Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, strategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { f ->
                val accepted = f.step(f.batch(1, 1030, samples = samples))
                assertEquals(14, accepted.receipt.acceptedSamples); assertEquals(0, f.kernel.bulkPlaneReceipt().qualifiedCells)
                assertEquals(0, f.kernel.bulkPlaneReceipt().certificateAttempts)
            }
        }
    }

    @Test fun `bulk tiny maximum population never gains quantization support`() {
        val points = (0..63).flatMap { y -> (0..63).map { x -> VisibilityDepthSample(x, y, 1000 + if ((x + y) % 2 == 0) 0 else 1, 255) } }
        for (perturbed in listOf(false, true)) Fixture(DepthSurfaceEstimationMode.LOCAL_NORMALS, strategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { f ->
            val samples = if (perturbed) points else points.map { it.copy(depthMillimeters = 1000) }
            val batch = DepthEvidenceBatch(1, 1, f.frame, translation(.05, .05, 0.0).toList(), VisibilityCameraIntrinsics(64, 64, 40000.0, 40000.0, 31.5, 31.5), samples, 0)
            assertEquals(4096, f.step(batch).receipt.acceptedSamples)
            assertEquals(0, f.kernel.bulkPlaneReceipt().qualifiedCells)
            assertEquals(0, f.kernel.bulkPlaneReceipt().projectedSamples)
            assertEquals(0, f.kernel.surfaceEstimationReceipt().independentPlaneFits)
        }
    }

    @Test fun `bulk scratch resets on lookup exception held packet discard and tiny capacity`() {
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, strategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { f ->
            val batch = f.batch(1, 1030)
            val result = f.lookup { view -> f.kernel.prepare(batch, object : BoundedCanonicalSurfaceView by view {
                override fun findSurfaceAtInto(x: Int, y: Int, z: Int, scratch: CanonicalSurfaceScratch): Boolean = error("lookup fault")
            }) }
            assertEquals(DepthEvidenceRefusal.CANONICAL_LOOKUP_FAILED, (result as DepthEvidenceResult.Refused).reason)
            assertClosedPhase(f.kernel)
            val prepared = f.prepare(batch) as DepthEvidenceResult.Accepted
            f.kernel.discardPrepared(); assertClosedPhase(f.kernel)
            assertEquals(prepared.changes, (f.prepare(batch) as DepthEvidenceResult.Accepted).changes)
            f.kernel.discardPrepared()
            f.step(f.batch(2, 1030)); assertEquals(1, f.kernel.bulkPlaneReceipt().qualifiedCells)
        }
        val frame = VisibilityGroupFrame.copyOf(identityVisibilityGridTransform(), identityVisibilityGridTransform(), 1_000_000, 2)
        Fixture(DepthSurfaceEstimationMode.LOCAL_NORMALS, frame, DepthPlaneFitStrategy.BULK_CELL_MOMENTS, 2).use { f ->
            val accepted = f.step(f.batch(1, 1000, grid = false))
            assertEquals(1, accepted.receipt.acceptedSamples)
            assertEquals(0, f.kernel.bulkPlaneReceipt().accumulatorCapacity)
            assertTrue(f.kernel.bulkPlaneReceipt().wholeEstimateUnrefined)
            assertClosedPhase(f.kernel)
        }
    }

    @Test fun `bulk covariance solver rejects rank loss nonfinite and cancellation without extra arrays`() {
        class ScalarScratch : DepthCellPlaneScratch {
            val words = IntArray(32); val moments = DoubleArray(9)
            override fun cellWord(index: Int) = words[index]
            override fun cellWord(index: Int, value: Int) { words[index] = value }
            override fun cellMoment(cell: Int, component: Int) = moments[component]
            override fun cellMoment(cell: Int, component: Int, value: Double) { moments[component] = value }
        }
        for (kind in listOf("rank", "nan", "negative", "ambiguous", "overflow")) {
            val s = ScalarScratch(); s.words[3] = 9; s.moments[2] = -1000.0
            s.moments[3] = 10.0; s.moments[6] = 10.0; s.moments[8] = 0.0
            when (kind) { "rank" -> s.moments[6] = 0.0; "nan" -> s.moments[4] = Double.NaN
                "negative" -> s.moments[8] = -1.0; "ambiguous" -> s.moments[8] = 10.0
                else -> { s.moments[3] = Double.MAX_VALUE; s.moments[6] = Double.MAX_VALUE } }
            assertFalse(kind, DepthCellPlaneEstimator.solve(s, 0, 0, 0.0, 0.0, 0.0))
        }
        val stable = ScalarScratch()
        for (point in gridSamples(1030)) DepthCellPlaneEstimator.accumulate(stable, 0, 0, 1e8 + point.x * 2.06, 1e8 + point.y * 2.06, -1030.0)
        assertTrue(DepthCellPlaneEstimator.solve(stable, 0, 0, 1e8, 1e8, 0.0))
        assertEquals(1.0, DepthCellPlaneEstimator.double(stable, 26), 1e-12)
    }

    @Test fun `bulk capacity fallback preserves admitted population and portable array ownership`() {
        val points = (0..6).flatMap { y -> (0..6).map { x -> VisibilityDepthSample(x * 10, y * 10, 200, 255) } }
        val frame = VisibilityGroupFrame.copyOf(identityVisibilityGridTransform(), identityVisibilityGridTransform(), 100_000, 128)
        Fixture(DepthSurfaceEstimationMode.DISABLED, frame, capacity = 128).use { raw ->
            Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, frame, DepthPlaneFitStrategy.BULK_CELL_MOMENTS, 128).use { bulk ->
                val arrays = bulk.kernel.primitiveArraysForAccounting()
                repeat(4) { i ->
                    val a = populationBatch(frame, i + 1L, points, 61, 61, 20.0, 30.0)
                    val reference = raw.step(a); val candidate = bulk.step(a)
                    assertEquals(49, candidate.receipt.acceptedSamples); assertEquals(reference.changes, candidate.changes)
                    assertTrue(bulk.kernel.bulkPlaneReceipt().wholeEstimateUnrefined)
                    assertEquals(0, bulk.kernel.bulkPlaneReceipt().certificateAttempts)
                    assertEquals(0, bulk.kernel.bulkPlaneReceipt().qualifiedCells)
                    val stageCapacity = 128
                    val expected = Integer.highestOneBit(minOf(4096, stageCapacity / 3, 3 * stageCapacity / 34))
                    assertEquals(8, expected)
                    assertEquals(expected, bulk.kernel.bulkPlaneReceipt().accumulatorCapacity)
                    assertTrue(3 * expected <= stageCapacity && 34 * expected <= 3 * stageCapacity)
                    assertTrue(3 * (2 * expected) > stageCapacity || 34 * (2 * expected) > 3 * stageCapacity)
                    assertTrue(arrays.indices.all { arrays[it] === bulk.kernel.primitiveArraysForAccounting()[it] })
                }
                assertEquals(raw.rows(), bulk.rows())
            }
        }
        DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceEstimationMode = DepthSurfaceEstimationMode.LOCAL_NORMALS)).use { reference ->
            DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceEstimationMode = DepthSurfaceEstimationMode.LOCAL_NORMALS, planeFitStrategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS)).use { bulk ->
                assertEquals(reference.resourceReceipt().fixedPrimitiveBytes, bulk.resourceReceipt().fixedPrimitiveBytes)
                assertEquals(16_706_424, bulk.resourceReceipt().modeledMaximumSemanticStateBytes)
                assertEquals(4096, bulk.bulkPlaneReceipt().accumulatorCapacity)
                assertClosedPhase(bulk)
            }
        }
    }

    @Test fun `actual packed bulk coherent selector compares all modes and unchanged canonical costs`() { runCost(false, false) }
    @Test fun `actual packed bulk discontinuity selector compares conservative cell rejection costs`() { runCost(true, false) }
    @Test fun `actual packed bulk high cell selector compares whole estimate raw fallback costs`() { runCost(false, true) }

    @Test fun `bulk observable maximum population keeps every point and one vote per estimate`() {
        val points = (0 until 4096).map { VisibilityDepthSample(it % 64 * 16, it / 64 * 16, 990, 255) }
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, strategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { f ->
            repeat(4) { i ->
                val batch = DepthEvidenceBatch(i + 1L, i + 1L, f.frame, translation(.05, .05, 0.0).toList(),
                    VisibilityCameraIntrinsics(1024, 1024, 40000.0, 40000.0, 504.0, 504.0), points.map { it.copy(depthMillimeters = if (i % 2 == 0) 990 else 1010) }, 0)
                assertEquals(4096, f.step(batch).receipt.acceptedSamples)
                val receipt = f.kernel.bulkPlaneReceipt()
                assertEquals(1, receipt.certificateAttempts); assertEquals(1, receipt.qualifiedCells)
                assertEquals(4096, receipt.projectedSamples)
                assertEquals(1, f.kernel.surfaceEstimationReceipt().independentPlaneFits)
            }
            assertEquals(1, f.rows().size)
        }
    }

    @Test fun `bulk confidence range and empty eligibility preserve ordinary admission`() {
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, strategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { f ->
            val points = gridSamples(1030) + listOf(VisibilityDepthSample(21, 10, 1030, 255),
                VisibilityDepthSample(10, 10, 199, 255), VisibilityDepthSample(10, 10, 8001, 255), VisibilityDepthSample(10, 10, 1070, 127))
            val accepted = f.step(f.batch(1, 1030, samples = points))
            assertEquals(9, accepted.receipt.acceptedSamples); assertEquals(4, accepted.receipt.rejectedSamples)
            assertEquals(1, f.kernel.bulkPlaneReceipt().qualifiedCells)
            val retainedResources = f.kernel.resourceReceipt()
            val retainedPosition = f.kernel.surfacePositionEstimate(Voxel(0, 0, -11))
            val retainedRows = f.rows()
            val retainedCut = requireNotNull(f.runtime.owner().activationState()).cut
            val allInvalid = f.prepare(f.batch(2, 1030, samples = points.map { it.copy(confidence = 0) })) as DepthEvidenceResult.Refused
            assertEquals(DepthEvidenceRefusal.INVALID_SAMPLE, allInvalid.reason)
            assertEquals(0, allInvalid.receipt.acceptedSamples); assertEquals(13, allInvalid.receipt.rejectedSamples)
            assertEquals(0, f.kernel.bulkPlaneReceipt().populatedCells)
            assertEquals(0, f.kernel.bulkPlaneReceipt().qualifiedCells)
            assertEquals(0, f.kernel.bulkPlaneReceipt().certificateAttempts)
            assertEquals(retainedResources, f.kernel.resourceReceipt())
            assertEquals(retainedPosition, f.kernel.surfacePositionEstimate(Voxel(0, 0, -11)))
            assertEquals(retainedRows, f.rows())
            assertEquals(retainedCut, requireNotNull(f.runtime.owner().activationState()).cut)
            assertClosedPhase(f.kernel)
            val empty = f.step(f.batch(3, 1030, samples = emptyList()))
            assertEquals(0, empty.receipt.acceptedSamples); assertEquals(0, empty.receipt.rejectedSamples)
            assertEquals(0, f.kernel.bulkPlaneReceipt().populatedCells)
            assertEquals(0, f.kernel.bulkPlaneReceipt().qualifiedCells)
            assertEquals(0, f.kernel.bulkPlaneReceipt().certificateAttempts)
            assertEquals(retainedPosition, f.kernel.surfacePositionEstimate(Voxel(0, 0, -11)))
            assertEquals(retainedRows, f.rows())
            assertEquals(retainedCut, requireNotNull(f.runtime.owner().activationState()).cut)
            assertClosedPhase(f.kernel)
        }
    }

    @Test fun `bulk exact voxel hash collisions preserve both raw populations`() {
        Fixture(DepthSurfaceEstimationMode.LOCAL_NORMALS, strategy = DepthPlaneFitStrategy.BULK_CELL_MOMENTS).use { bulk ->
            val pairs = (-4..4).flatMap { y -> (-4..4).map { x -> Voxel(x, y, -2) } }
            val colliding = pairs.groupBy { bulk.kernel.bulkHashForTest(packVisibilityGridKey(it.x, it.y, it.z)) }.values.first { it.size >= 2 }.take(2)
            val points = colliding.map { VisibilityDepthSample(64 + it.x * 10, 64 - it.y * 10, 200, 255) }
            Fixture(DepthSurfaceEstimationMode.DISABLED).use { raw ->
                repeat(4) { i ->
                    val batch = populationBatch(bulk.frame, i + 1L, points, 128, 128, 20.0, 64.0)
                    val reference = raw.step(batch); val candidate = bulk.step(batch)
                    assertEquals(2, candidate.receipt.acceptedSamples); assertEquals(reference.changes, candidate.changes)
                    assertEquals(2, bulk.kernel.bulkPlaneReceipt().populatedCells)
                    assertFalse(bulk.kernel.bulkPlaneReceipt().wholeEstimateUnrefined)
                    assertEquals(0, bulk.kernel.bulkPlaneReceipt().qualifiedCells)
                }
                assertEquals(raw.rows(), bulk.rows()); assertEquals(2, bulk.rows().size)
            }
        }
    }

    @Test fun `actual packed bulk complete many cell populations compare certificate and residual costs`() {
        runCost(true, false, completePopulations = true)
    }

    private fun runCost(manyCells: Boolean, fallback: Boolean, completePopulations: Boolean = false) {
        val checks = mutableListOf<() -> Unit>()
        for (mode in DepthSurfaceEstimationMode.values()) {
            val capacity = if (completePopulations) 512 else if (fallback) 128 else 256
            val frame = VisibilityGroupFrame.copyOf(identityVisibilityGridTransform(), identityVisibilityGridTransform(), 100_000, capacity)
            val lanes = if (mode == DepthSurfaceEstimationMode.DISABLED) listOf(CostLane("baseline", Fixture(mode, frame, capacity = capacity))) else listOf(
                CostLane("control_before", Fixture(mode, frame, capacity = capacity)),
                CostLane("bulk", Fixture(mode, frame, DepthPlaneFitStrategy.BULK_CELL_MOMENTS, capacity)),
                CostLane("control_after", Fixture(mode, frame, capacity = capacity)))
            val pool = DepthSamplesLeasePool(); var selected = 0
            val lastCellMembers = mutableMapOf<Voxel, Int>()
            try {
                repeat(16) { i ->
                    var closes = 0
                    val source = RawDepthCopySource(object : PairedRawDepthAcquirer {
                        override fun acquireDepth() = object : RawDepthImage {
                            override val width = 128; override val height = 128
                            override fun unsignedValue(x: Int, y: Int) = if (fallback) 200 else if (manyCells) {
                                if ((x / 16 + y / 16) % 2 == 0) 990 else 1030
                            } else if (i % 2 == 0) 990 else 1010
                            override fun close() { closes++ }
                        }
                        override fun acquireConfidence(): RawDepthImage? = null
                    })
                    val focal = if (fallback) 42.0 else if (manyCells) 500.0 else 4000.0
                    val acquired = source.acquirePacked({ w, h -> RawDepthFrameMetadata(i + 1L, 1, 1, true, w, h,
                        DepthIntrinsics(focal, focal, 63.5, 63.5), translation(.05, .05, 0.0)) }, pool, SampleLeaseGeneration(1, 1, 1, 1)) as PackedDepthAcquisitionResult.Observation
                    acquired.lease.use { lease ->
                        selected = lease.samples.count; assertEquals(3025, selected); assertEquals(1, closes)
                        val order = if (i % 2 == 0) lanes.indices else lanes.indices.reversed()
                        for (laneIndex in order) {
                            val lane = lanes[laneIndex]; val f = lane.fixture
                            val metadata = DepthEvidenceMetadata(i + 1L, i + 1L, frame, translation(.05, .05, 0.0).toList(),
                                VisibilityCameraIntrinsics(128, 128, focal, focal, 63.5, 63.5), lease.samples.rejectedCount)
                            val allocation = allocatedBytes(); val start = System.nanoTime()
                            val accepted = f.lookup { f.kernel.prepare(lease.samples, metadata, it) } as DepthEvidenceResult.Accepted
                            lane.kernelNs += System.nanoTime() - start; lane.kernelBytes += allocationDelta(allocation)
                            lane.admitted += accepted.receipt.acceptedSamples; lane.changes += accepted.changes.size
                            val local = f.kernel.surfaceEstimationReceipt(); val bulk = f.kernel.bulkPlaneReceipt()
                            lane.fullFits += local.locallyFittedSamples; lane.independent += local.independentPlaneFits; lane.associations += local.associationCellLookups
                            lane.populated += bulk.populatedCells; lane.qualified += bulk.qualifiedCells; lane.rejected += bulk.rejectedCells
                            lane.solves += bulk.covarianceSolves; lane.certificates += bulk.certificateAttempts; lane.residualWork += bulk.allPointResidualWork
                            lane.scratchWork += bulk.scratchWork; lane.projected += bulk.projectedSamples
                            if (bulk.wholeEstimateUnrefined) lane.fallbacks++
                            val ackAllocation = allocatedBytes(); val ackStart = System.nanoTime()
                            f.finish(accepted); lane.ackNs += System.nanoTime() - ackStart; lane.ackBytes += allocationDelta(ackAllocation)
                        }
                        // Only the new nonfallback input computes a final-map census after all timed lanes.
                        if (completePopulations && i == 15) for (sample in 0 until selected) {
                            val depth = lease.samples.depthMillimetresAt(sample)
                            val cameraX = (lease.samples.xAt(sample) - 63.5) * depth / focal
                            val cameraY = -(lease.samples.yAt(sample) - 63.5) * depth / focal
                            val point = DepthPointMm((cameraX / 1000.0 + .05) * 1000.0,
                                (cameraY / 1000.0 + .05) * 1000.0, -depth.toDouble())
                            val voxel = requireNotNull(DepthVoxelAddressing.quantize(point, frame.voxelSizeMicrometres))
                            lastCellMembers[voxel] = (lastCellMembers[voxel] ?: 0) + 1
                        }
                    }
                    assertEquals(0, pool.leasedCount())
                }
                if (completePopulations && lanes.size == 3) {
                    val bulk = lanes[1]
                    for ((voxel, members) in lastCellMembers) if (bulk.fixture.kernel.surfacePositionEstimate(voxel) != null) {
                        bulk.expectedQualified += 16L; bulk.expectedProjected += members * 16L
                    }
                }
                for (lane in lanes) println("depth-bulk-cost capacity=$capacity completePopulations=$completePopulations manyCells=$manyCells fallback=$fallback mode=$mode lane=${lane.name} selected=$selected admitted=${lane.admitted} rows=${lane.fixture.rows().size} changes=${lane.changes} fullFits=${lane.fullFits} independentFits=${lane.independent} associationLookups=${lane.associations} populatedCells=${lane.populated} qualifiedCells=${lane.qualified} rejectedCells=${lane.rejected} covarianceSolves=${lane.solves} certificateAttempts=${lane.certificates} allPointResidualWork=${lane.residualWork} scratchWork=${lane.scratchWork} projectedSamples=${lane.projected} wholeEstimateFallbacks=${lane.fallbacks} expectedQualifiedCells=${if (completePopulations && lane.name == "bulk") lane.expectedQualified else "UNAVAILABLE"} expectedProjectedSamples=${if (completePopulations && lane.name == "bulk") lane.expectedProjected else "UNAVAILABLE"} allocationScope=JVM_THREAD coldKernelNs=${lane.kernelNs.take(2)} coldAckNs=${lane.ackNs.take(2)} kernelNs=${lane.kernelNs.drop(2)} canonicalAckNs=${lane.ackNs.drop(2)} kernelAllocatedBytes=${lane.kernelBytes.drop(2)} canonicalAllocatedBytes=${lane.ackBytes.drop(2)}")
                val snapshots = lanes.map { it.fixture.rows() }
                val bulkAccumulatorCapacity = if (lanes.size == 3) lanes[1].fixture.kernel.bulkPlaneReceipt().accumulatorCapacity else 0
                checks += {
                for (lane in lanes) assertEquals(3025L * 16, lane.admitted)
                if (lanes.size == 3) {
                    assertEquals(snapshots[0], snapshots[2])
                    val bulk = lanes[1]
                    assertTrue(bulk.certificates <= bulk.populated)
                    if (!manyCells && !fallback) {
                        assertEquals(snapshots[0], snapshots[1])
                        assertEquals(16L, bulk.solves); assertEquals(16L, bulk.certificates)
                        assertEquals(16L, bulk.qualified); assertEquals(3025L * 16, bulk.projected)
                        assertEquals(16L, bulk.independent)
                        if (mode == DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES) assertEquals(3025L * 16 * 27, bulk.associations)
                        assertEquals(if (mode == DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES) 1 else 2, snapshots[1].size)
                    } else {
                        assertEquals(snapshots[0].map { it.voxel }, snapshots[1].map { it.voxel })
                        assertEquals(lanes[0].changes, bulk.changes)
                        if (fallback) { assertEquals(16L, bulk.fallbacks); assertEquals(0L, bulk.certificates); assertEquals(0L, bulk.projected) }
                        if (completePopulations) {
                            assertEquals(32, bulkAccumulatorCapacity)
                            assertEquals(18, lastCellMembers.size)
                            assertEquals(18L * 16, bulk.populated)
                            assertEquals(bulk.populated, bulk.qualified + bulk.rejected)
                            assertEquals(bulk.populated, bulk.solves)
                            assertEquals(0L, bulk.fallbacks)
                            assertEquals(bulk.expectedQualified, bulk.qualified)
                            assertEquals(bulk.expectedProjected, bulk.projected)
                            assertEquals(bulk.qualified, bulk.independent)
                            assertTrue(bulk.residualWork >= 2 * bulk.projected)
                            if (mode == DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES) assertEquals(27 * bulk.projected, bulk.associations)
                            else assertEquals(0L, bulk.associations)
                        }
                    }
                }
                }
            } finally { pool.close(); lanes.forEach { it.fixture.close() } }
        }
        checks.forEach { it() }
    }

    private class CostLane(val name: String, val fixture: Fixture) {
        val kernelNs = mutableListOf<Long>(); val ackNs = mutableListOf<Long>()
        val kernelBytes = mutableListOf<Long>(); val ackBytes = mutableListOf<Long>()
        var admitted = 0L; var changes = 0L; var fullFits = 0L; var independent = 0L; var associations = 0L
        var populated = 0L; var qualified = 0L; var rejected = 0L; var solves = 0L; var certificates = 0L
        var residualWork = 0L; var scratchWork = 0L; var projected = 0L; var fallbacks = 0L
        var expectedQualified = 0L; var expectedProjected = 0L
    }

    private fun assertClosedPhase(kernel: DepthEvidenceKernel) {
        try { kernel.bulkScratchWordForTest(0); fail("bulk scratch outlived the population phase") } catch (_: IllegalStateException) { }
    }

    private fun populationBatch(frame: VisibilityGroupFrame, timestamp: Long, points: List<VisibilityDepthSample>, width: Int, height: Int, focal: Double, centre: Double) =
        DepthEvidenceBatch(timestamp, timestamp, frame, translation(.05, .05, 0.0).toList(), VisibilityCameraIntrinsics(width, height, focal, focal, centre, centre), points, 0)

    private class Fixture(mode: DepthSurfaceEstimationMode,
        val frame: VisibilityGroupFrame = VisibilityGroupFrame.copyOf(identityVisibilityGridTransform(), identityVisibilityGridTransform(), 100_000, 256),
        strategy: DepthPlaneFitStrategy = DepthPlaneFitStrategy.PER_SAMPLE_SUPPORT, capacity: Int = 256) : AutoCloseable {
        private val root = Files.createTempDirectory("depth-plane-fusion").toFile()
        private val group = SurfaceGroup("f".repeat(32))
        private val coordinator = StorageBudgetCoordinatorV2(File(root, "visibility-grid-canonical-surface-runtime"),
            StorageBudgetPolicyV2(64L * 1024 * 1024, 0), JvmDescriptorFilesystemV2())
        val runtime = CanonicalRuntimeResources.openLive(root, group, coordinator,
            SurfaceOwnershipConfiguration(voxelMicrometers = frame.voxelSizeMicrometres, surfaceCapacity = capacity, lineageCapacity = capacity * 2))
        val kernel = DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceCapacity = capacity, surfaceEstimationMode = mode, planeFitStrategy = strategy))
        init { assertTrue(runtime.openInitial(committedEmptyBaseline("depth-plane", group.value, 1, 1, 1)) is SurfaceOwnershipOpenResult.Opened) }
        fun batch(timestamp: Long, depth: Int, grid: Boolean = true,
            camera: DoubleArray = translation(0.05, 0.05, 0.0), samples: List<VisibilityDepthSample>? = null) = DepthEvidenceBatch(
            timestamp, timestamp, frame, camera.toList(),
            if (grid) VisibilityCameraIntrinsics(21, 21, OBSERVABLE_FOCAL, OBSERVABLE_FOCAL, 10.0, 10.0)
            else VisibilityCameraIntrinsics(1, 1, 1.0, 1.0, 0.0, 0.0),
            samples ?: if (grid) gridSamples(depth) else listOf(VisibilityDepthSample(0, 0, depth, 255)), 0)
        fun prepare(batch: DepthEvidenceBatch): DepthEvidenceResult = lookup { kernel.prepare(batch, it) }
        fun <T> lookup(action: (BoundedCanonicalSurfaceView) -> T): T {
            val cut = requireNotNull(runtime.owner().activationState()).cut
            val result = runtime.withBoundedCurrent(BoundedCanonicalLookupRequest(cut.geometryRevision, cut.lineageRevision,
                100_000, 65_536, 65_536, 64L * 1024 * 1024), action)
            return (result as BoundedCanonicalLookupResult.Completed<T>).value
        }
        fun step(batch: DepthEvidenceBatch): DepthEvidenceResult.Accepted =
            (prepare(batch) as DepthEvidenceResult.Accepted).also { finish(it) }
        fun finish(accepted: DepthEvidenceResult.Accepted) {
            if (accepted.changes.isEmpty()) assertTrue(kernel.applyPrepared() is DepthEvidenceApplyResult.Applied)
            else assertTrue(kernel.applyPrepared(commit(accepted, true)) is DepthEvidenceApplyResult.Applied)
        }
        fun commit(accepted: DepthEvidenceResult.Accepted, acknowledgeCurrent: Boolean): PreparedCanonicalMutation {
            val plan = runtime.prepareEvidenceBatch(CanonicalEvidenceBatchCommand("depth-plane-${accepted.receipt.sequence}",
                accepted.expectedGeometryRevision, accepted.expectedLineageRevision, accepted.changes)) as CanonicalMutationPreparation.Prepared
            assertTrue(runtime.commitAdjacent(plan.mutation) is CanonicalAdjacentCommitResult.Committed)
            if (acknowledgeCurrent) acknowledge()
            return plan.mutation
        }
        fun currentAck(): CanonicalAcknowledgement {
            val state = requireNotNull(runtime.owner().activationState())
            val current = state.current as CanonicalActivationCurrent.Receipt
            return CanonicalAcknowledgement(current.identity.commandHash, state.cut.geometryRevision, state.cut.lineageRevision)
        }
        fun acknowledge() { assertTrue(runtime.owner().acknowledgeCanonicalCurrent(currentAck()) is CanonicalAcknowledgementResult.Acknowledged) }
        fun rows(): List<CommittedGeometryRow> = requireNotNull(runtime.readRendererPage(0)).rows
        override fun close() { kernel.close(); runtime.close(); coordinator.close(); root.deleteRecursively() }
    }

    private companion object {
        const val OBSERVABLE_FOCAL = 500.0
        fun verifyObservableSquare(sizeMicrometres: Int, focal: Double, minimumDepth: Int, maximumDepth: Int) {
            for (depth in minimumDepth..maximumDepth) for (sample in gridSamples(depth)) {
                val dx = (sample.x - 10) * depth / focal
                val dy = (sample.y - 10) * depth / focal
                val tangentMargin = minOf(35.0, sizeMicrometres / 1000.0 * 0.35) - sqrt(dx * dx + dy * dy)
                assertTrue("declared square support exceeds association footprint", tangentMargin > 0.05)
                val span = 20.0 * depth / focal
                val q = sqrt(1.0 + 2.0 * (10.0 / focal) * (10.0 / focal))
                val ratio = (4.0 * q * span + 4.0 * q * q) / (span * span)
                assertTrue("declared square support lacks quantization margin",
                    DepthLocalPlaneEstimator.ANGULAR_DISCRIMINATION_SINE - ratio > 0.0005)
            }
        }
        // Android unit-test compilation omits desktop management packages.
        // The host counter is optional and resolved once through public JVM APIs.
        private val allocationReader: (() -> Long)? by lazy {
            try {
                val factory = Class.forName("java.lang.management.ManagementFactory")
                val bean = factory.getMethod("getThreadMXBean").invoke(null)
                val type = Class.forName("com.sun.management.ThreadMXBean")
                if (type.isInstance(bean) && type.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true &&
                    type.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) == true) {
                    val method = type.getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE)
                    val reader: () -> Long = { method.invoke(bean, Thread.currentThread().id) as Long }
                    reader
                } else null
            } catch (_: ReflectiveOperationException) { null }
        }
        fun allocatedBytes(): Long = try { allocationReader?.invoke() ?: -1L }
            catch (_: ReflectiveOperationException) { -1L }
        fun allocationDelta(before: Long): Long {
            val after = allocatedBytes()
            if (before < 0 || after < 0) return -1
            assertTrue("JVM thread allocation counter reset", after >= before)
            return after - before
        }
        fun gridSamples(depth: Int) = (0..2).flatMap { y -> (0..2).map { x -> VisibilityDepthSample(x * 10, y * 10, depth, 255) } }
        fun translation(x: Double, y: Double, z: Double) = identityVisibilityGridTransform().also { it[12] = x; it[13] = y; it[14] = z }
        fun yaw(angle: Double, x: Double, y: Double, z: Double) = translation(x, y, z).also {
            it[0] = cos(angle); it[2] = -sin(angle); it[8] = sin(angle); it[10] = cos(angle)
        }
        fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(16) { index ->
            val row = index % 4; val col = index / 4
            (0..3).sumOf { a[it * 4 + row] * b[col * 4 + it] }
        }
        fun inverseRigid(m: DoubleArray) = identityVisibilityGridTransform().also { inverse ->
            for (row in 0..2) for (col in 0..2) inverse[col * 4 + row] = m[row * 4 + col]
            for (row in 0..2) inverse[12 + row] = -(0..2).sumOf { inverse[it * 4 + row] * m[12 + it] }
        }
        fun normalError(packed: Int, expectedX: Double, expectedY: Double, expectedZ: Double): Double {
            var x = (packed ushr 8).toByte().toInt() / 127.0; var y = packed.toByte().toInt() / 127.0
            val z = 1.0 - kotlin.math.abs(x) - kotlin.math.abs(y)
            if (z < 0) { val oldX = x; x = (1 - kotlin.math.abs(y)) * if (x < 0) -1 else 1
                y = (1 - kotlin.math.abs(oldX)) * if (y < 0) -1 else 1 }
            val denominator = sqrt(x * x + y * y + z * z) * sqrt(expectedX * expectedX + expectedY * expectedY + expectedZ * expectedZ)
            return Math.toDegrees(acos(((x * expectedX + y * expectedY + z * expectedZ) / denominator).coerceIn(-1.0, 1.0)))
        }
    }
}
