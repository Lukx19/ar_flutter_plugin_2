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
import org.junit.Assert.assertThrows
import org.junit.Test

/** Numerical characterization through real session-memory canonical mutation and exact ACK. */
class DepthPlaneFusionTest {
    @Test fun `single pixel boundary noise remains an honest unobservable plane characterization`() {
        for (mode in DepthSurfaceEstimationMode.values()) {
            Fixture(mode).use { fixture ->
                listOf(1030, 1050, 1070, 1050).forEachIndexed { index, depth ->
                    fixture.step(fixture.batch(index + 1L, depth, grid = false))
                }
                assertEquals(listOf(Voxel(0, 0, -11)), fixture.rows().map { it.voxel })
            }
            Fixture(mode).use { fixture ->
                repeat(8) { fixture.step(fixture.batch(it + 1L, if (it % 2 == 0) 990 else 1010, grid = false)) }
                assertEquals(setOf(-10, -11), fixture.rows().map { it.voxel.z }.toSet())
                assertEquals(2, fixture.rows().size)
            }
        }
    }

    @Test fun `coherent local planes collapse twenty millimetre boundary noise before publication`() {
        for (mode in DepthSurfaceEstimationMode.values()) Fixture(mode).use { fixture ->
            repeat(8) {
                fixture.step(fixture.batch(it + 1L, if (it % 2 == 0) 990 else 1010))
                if (it < 3) assertTrue(fixture.rows().isEmpty())
            }
            assertEquals(if (mode == DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES) 1 else 2,
                fixture.rows().size)
            if (mode != DepthSurfaceEstimationMode.DISABLED) {
                assertEquals(9, fixture.kernel.surfaceEstimationReceipt().locallyFittedSamples)
                fixture.rows().forEach { assertTrue(normalError(it.packedNormal, 0.0, 0.0, 1.0) < 1.0) }
                if (mode == DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES) {
                    val position = requireNotNull(fixture.kernel.surfacePositionEstimate(fixture.rows().single().voxel))
                    assertTrue(kotlin.math.abs(position.zMillimetres + 1000.0) <= 5.0)
                    assertEquals(4, position.independentSupportsToPublication)
                }
            }
        }
    }

    @Test fun `forty millimetre true parallel surfaces preserve independent canonical identities`() {
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES).use { fixture ->
            repeat(8) { fixture.step(fixture.batch(it + 1L, if (it % 2 == 0) 990 else 1030)) }
            val rows = fixture.rows()
            assertEquals(2, rows.size)
            assertEquals(2, rows.map { it.surfaceId }.toSet().size)
            assertEquals(setOf(-10, -11), rows.map { it.voxel.z }.toSet())
            assertEquals(0, fixture.kernel.surfaceEstimationReceipt().associatedSamples)
        }
    }

    @Test fun `opposing close walls retain signed normal and separate surfaces`() {
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES).use { fixture ->
            repeat(8) { index ->
                val camera = if (index % 2 == 0) translation(0.05, 0.05, 0.0)
                    else yaw(Math.PI, 0.05, 0.05, -2.0)
                fixture.step(fixture.batch(index + 1L, 990, camera = camera))
            }
            val rows = fixture.rows()
            assertEquals(2, rows.size)
            assertEquals(setOf(-10, -11), rows.map { it.voxel.z }.toSet())
            assertTrue(rows.any { normalError(it.packedNormal, 0.0, 0.0, 1.0) < 1.0 })
            assertTrue(rows.any { normalError(it.packedNormal, 0.0, 0.0, -1.0) < 1.0 })
        }
    }

    @Test fun `thin foreground and discontinuity keep conservative layer evidence`() {
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES).use { fixture ->
            repeat(8) { index ->
                val samples = gridSamples(1030).map {
                    if (it.x == 10 && it.y == 10) it.copy(depthMillimeters = 990) else it
                }
                fixture.step(fixture.batch(index + 1L, 1030, samples = samples))
            }
            assertEquals(setOf(-10, -11), fixture.rows().map { it.voxel.z }.toSet())
            assertEquals(2, fixture.rows().size)
        }
    }

    @Test fun `oblique plane normal measures geometry rather than camera viewing ray`() {
        val samples = gridSamples(1000).map {
            it.copy(depthMillimeters = (1000.0 / (1.0 - 0.5 * (it.x - 10) / OBSERVABLE_FOCAL)).toInt())
        }
        for (mode in listOf(DepthSurfaceEstimationMode.DISABLED, DepthSurfaceEstimationMode.LOCAL_NORMALS)) {
            Fixture(mode).use { fixture ->
                repeat(4) { fixture.step(fixture.batch(it + 1L, 1000, samples = samples)) }
                val errors = fixture.rows().map { normalError(it.packedNormal, 0.5, 0.0, 1.0) }
                assertTrue(errors.isNotEmpty())
                if (mode == DepthSurfaceEstimationMode.LOCAL_NORMALS) assertTrue(errors.all { it < 2.0 })
                else assertTrue(errors.all { it > 20.0 })
                println("depth-plane-normal mode=$mode errorDegrees=$errors")
            }
        }
    }

    @Test fun `moving camera in translated rotated group keeps one world plane`() {
        val groupFromWorld = yaw(Math.PI / 2, 0.15, 0.10, 0.10)
        val worldFromGroup = inverseRigid(groupFromWorld)
        val frame = VisibilityGroupFrame.copyOf(groupFromWorld, worldFromGroup, 100_000, 256)
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, frame).use { fixture ->
            repeat(8) { index ->
                val angle = if (index % 2 == 0) 0.0 else Math.PI / 9
                val camera = yaw(angle, 0.05 + kotlin.math.tan(angle), 0.05, 0.0)
                val samples = gridSamples(1000).map {
                    val rayZ = -sin(angle) * (it.x - 10) / OBSERVABLE_FOCAL - cos(angle)
                    it.copy(depthMillimeters = (-1000.0 / rayZ).toInt())
                }
                fixture.step(fixture.batch(index + 1L, 1000, camera = multiply(groupFromWorld, camera), samples = samples))
            }
            assertEquals(1, fixture.rows().size)
            assertTrue(normalError(fixture.rows().single().packedNormal, 1.0, 0.0, 0.0) < 2.0)
        }
    }

    @Test fun `duplicate estimates held exact acknowledgement and discard preserve temporal evidence`() {
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES).use { fixture ->
            repeat(3) { fixture.step(fixture.batch(it + 1L, if (it % 2 == 0) 990 else 1010)) }
            val before = fixture.kernel.resourceReceipt()
            val beforePosition = fixture.kernel.surfacePositionEstimate(Voxel(0, 0, -10))
            val beforeCut = requireNotNull(fixture.runtime.owner().activationState()).cut
            val original = fixture.batch(4, 1010)
            val staged = fixture.prepare(original) as DepthEvidenceResult.Accepted
            assertEquals(1, staged.changes.size)
            fixture.kernel.discardPrepared()
            assertEquals(before, fixture.kernel.resourceReceipt())
            assertEquals(beforePosition, fixture.kernel.surfacePositionEstimate(Voxel(0, 0, -10)))
            assertEquals(beforeCut, requireNotNull(fixture.runtime.owner().activationState()).cut)
            assertTrue(fixture.rows().isEmpty())
            val retry = fixture.prepare(original) as DepthEvidenceResult.Accepted
            assertEquals(staged, retry)
            val mutation = fixture.commit(retry, acknowledgeCurrent = false)
            assertEquals(before.residentEvidenceRows, fixture.kernel.resourceReceipt().residentEvidenceRows)
            assertEquals(beforePosition, fixture.kernel.surfacePositionEstimate(Voxel(0, 0, -10)))
            assertEquals(DepthEvidenceRefusal.PREPARED_BUSY,
                (fixture.prepare(fixture.batch(5, 990)) as DepthEvidenceResult.Refused).reason)
            val current = fixture.currentAck()
            assertTrue(fixture.runtime.owner().acknowledgeCanonicalCurrent(current.copy(geometryRevision = current.geometryRevision + 1)) is CanonicalAcknowledgementResult.NoOp)
            fixture.acknowledge()
            assertTrue(fixture.kernel.applyPrepared(mutation) is DepthEvidenceApplyResult.Applied)
            assertEquals(DepthEvidenceRefusal.DUPLICATE_TIMESTAMP,
                (fixture.prepare(fixture.batch(5, 990).copy(sourceTimestampNs = 4)) as DepthEvidenceResult.Refused).reason)
            val idBeforeRefinement = fixture.rows().single().surfaceId
            val lineageBeforeRefinement = requireNotNull(fixture.runtime.owner().activationState()).cut.lineageRevision
            fixture.step(fixture.batch(5, 990))
            assertEquals(1, fixture.rows().size)
            assertEquals(idBeforeRefinement, fixture.rows().single().surfaceId)
            assertEquals(lineageBeforeRefinement, requireNotNull(fixture.runtime.owner().activationState()).cut.lineageRevision)
            assertEquals(0, fixture.kernel.resourceReceipt().preparedEvidenceRows)
        }
    }

    @Test fun `maximum portable estimator ownership stays inside existing semantic budget`() {
        val disabled = DepthEvidenceKernel().use { it.resourceReceipt() }
        DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceEstimationMode = DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES)).use { kernel ->
            val receipt = kernel.resourceReceipt()
            assertEquals(718_880, receipt.fixedPrimitiveBytes - disabled.fixedPrimitiveBytes)
            assertEquals(16_706_424, receipt.modeledMaximumSemanticStateBytes)
            assertEquals(16 * 1024 * 1024, receipt.semanticStateBudgetBytes)
            assertTrue(receipt.modeledMaximumSemanticStateBytes <= receipt.semanticStateBudgetBytes)
            val arrays = kernel.primitiveArraysForAccounting()
            assertEquals(arrays.size, arrays.toSet().size)
        }
    }

    @Test fun `actual packed raw selection sparse planes preserve lease bounds and compare kernel cost`() {
        val outcomes = mutableListOf<Triple<DepthSurfaceEstimationMode, Int, Int>>()
        for (mode in DepthSurfaceEstimationMode.values()) Fixture(mode).use { fixture ->
            val pool = DepthSamplesLeasePool()
            val kernelTimes = mutableListOf<Long>(); val canonicalTimes = mutableListOf<Long>()
            val kernelAllocations = mutableListOf<Long>(); val canonicalAllocations = mutableListOf<Long>()
            var mutations = 0; var virtualWork = 0L
            var selected = 0; var fitted = 0; var associated = 0
            try {
                repeat(16) { index ->
                    var closes = 0
                    val source = RawDepthCopySource(object : PairedRawDepthAcquirer {
                        override fun acquireDepth() = object : RawDepthImage {
                            override val width = 128; override val height = 128
                            override fun unsignedValue(x: Int, y: Int) = if (index % 2 == 0) 990 else 1010
                            override fun close() { closes++ }
                        }
                        override fun acquireConfidence(): RawDepthImage? = null
                    })
                    val result = source.acquirePacked({ width, height -> RawDepthFrameMetadata(index + 1L, 1, 1, true, width, height,
                        DepthIntrinsics(4000.0, 4000.0, 63.5, 63.5), translation(0.05, 0.05, 0.0)) },
                        pool, SampleLeaseGeneration(1, 1, 1, 1)) as PackedDepthAcquisitionResult.Observation
                    val lease = result.lease
                    try {
                        assertEquals(1, closes)
                        selected = lease.samples.count
                        assertTrue(selected in 1000..4096)
                        val metadata = DepthEvidenceMetadata(index + 1L, index + 1L, fixture.frame,
                            translation(0.05, 0.05, 0.0).toList(), VisibilityCameraIntrinsics(128, 128, 4000.0, 4000.0, 63.5, 63.5),
                            lease.samples.rejectedCount)
                        val allocatedBefore = allocatedBytes()
                        val start = System.nanoTime()
                        val accepted = fixture.lookup { fixture.kernel.prepare(lease.samples, metadata, it) } as DepthEvidenceResult.Accepted
                        kernelTimes += System.nanoTime() - start
                        kernelAllocations += allocationDelta(allocatedBefore)
                        mutations += accepted.changes.size
                        virtualWork += accepted.receipt.p95VirtualWorkUnits
                        fitted += fixture.kernel.surfaceEstimationReceipt().locallyFittedSamples
                        associated += fixture.kernel.surfaceEstimationReceipt().associatedSamples
                        val canonicalAllocatedBefore = allocatedBytes()
                        val commitStart = System.nanoTime()
                        fixture.finish(accepted)
                        canonicalTimes += System.nanoTime() - commitStart
                        canonicalAllocations += allocationDelta(canonicalAllocatedBefore)
                    } finally { lease.close() }
                    assertEquals(0, pool.leasedCount())
                }
                outcomes += Triple(mode, fixture.rows().size, fitted - selected * 12)
                println("depth-plane-cost mode=$mode selected=$selected fits=$fitted associated=$associated rows=${fixture.rows().size} changes=$mutations virtualWork=$virtualWork allocationScope=JVM_THREAD allocationStatus=${if (kernelAllocations.any { it < 0 } || canonicalAllocations.any { it < 0 }) "UNAVAILABLE" else "AVAILABLE"} kernelAllocatedBytes=${kernelAllocations.drop(2)} canonicalAllocatedBytes=${canonicalAllocations.drop(2)} kernelNs=${kernelTimes.drop(2).sorted()} canonicalAckNs=${canonicalTimes.drop(2).sorted()}")
            } finally { pool.close() }
        }
        for ((mode, rows, fittedMargin) in outcomes) {
            assertEquals(if (mode == DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES) 1 else 2, rows)
            if (mode != DepthSurfaceEstimationMode.DISABLED) assertTrue(fittedMargin >= 0)
        }
    }

    @Test fun `association gates scale with cell size while preserving closer real layers`() {
        for (size in listOf(50_000, 100_000, 200_000)) {
            val frame = VisibilityGroupFrame.copyOf(identityVisibilityGridTransform(), identityVisibilityGridTransform(), size, 256)
            val noise = if (size == 50_000) 5 else 10
            val separation = if (size == 50_000) 20 else 40
            val camera = translation(size / 2_000_000.0, size / 2_000_000.0, 0.0)
            val focal = if (size == 50_000) 820.0 else OBSERVABLE_FOCAL
            // Fixture depth extrema include both noise and genuine-layer controls.
            verifyObservableSquare(size, focal, 1000 - separation / 2, 1000 + separation / 2)
            Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, frame).use { fixture ->
                repeat(8) { fixture.step(fixture.batch(it + 1L, 1000 + if (it % 2 == 0) -noise else noise, camera = camera).copy(
                    intrinsics = VisibilityCameraIntrinsics(21, 21, focal, focal, 10.0, 10.0))) }
                assertEquals(1, fixture.rows().size)
            }
            Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, frame).use { fixture ->
                repeat(8) { fixture.step(fixture.batch(it + 1L, 1000 + if (it % 2 == 0) -separation / 2 else separation / 2, camera = camera).copy(
                    intrinsics = VisibilityCameraIntrinsics(21, 21, focal, focal, 10.0, 10.0))) }
                assertEquals(2, fixture.rows().size)
            }
        }
    }

    @Test fun `maximum selected sample plane keeps one vote per independent estimate`() {
        val samples = (0 until 4096).map { VisibilityDepthSample(it % 64 * 16, it / 64 * 16, 990, 255) }
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES).use { fixture ->
            repeat(4) { index ->
                val depth = if (index % 2 == 0) 990 else 1010
                val batch = DepthEvidenceBatch(index + 1L, index + 1L, fixture.frame,
                    translation(0.05, 0.05, 0.0).toList(),
                    VisibilityCameraIntrinsics(1024, 1024, 40_000.0, 40_000.0, 504.0, 504.0),
                    samples.map { it.copy(depthMillimeters = depth) }, 0)
                val accepted = fixture.step(batch)
                assertEquals(4096, accepted.receipt.acceptedSamples)
                assertEquals(1, fixture.kernel.surfaceEstimationReceipt().independentPlaneFits)
                if (index < 3) assertTrue(fixture.rows().isEmpty())
            }
            assertEquals(1, fixture.rows().size)
        }
    }

    @Test fun `integer depth uncertainty rejects tiny maximum cardinality footprints`() {
        for (perturbed in listOf(false, true)) Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES).use { fixture ->
            repeat(8) { index ->
                val depth = if (index % 2 == 0) 990 else 1010
                val samples = (0 until 4096).map {
                    VisibilityDepthSample(it % 64, it / 64, depth + if (perturbed && it % 2 == 0) 1 else 0, 255)
                }
                val batch = DepthEvidenceBatch(index + 1L, index + 1L, fixture.frame,
                    translation(0.05, 0.05, 0.0).toList(),
                    VisibilityCameraIntrinsics(64, 64, 40_000.0, 40_000.0, 31.5, 31.5), samples, 0)
                val accepted = fixture.step(batch)
                assertEquals(4096, accepted.receipt.acceptedSamples)
                val receipt = fixture.kernel.surfaceEstimationReceipt()
                assertEquals(0, receipt.locallyFittedSamples)
                assertEquals(0, receipt.independentPlaneFits)
                assertEquals(0, receipt.associatedSamples)
            }
            assertEquals(2, fixture.rows().size)
        }
    }

    @Test fun `selected footprint bounds qualify missing edge samples and reset on empty eligibility`() {
        val samples = listOf(0, 62, 125).flatMap { y -> listOf(0, 62, 125).map { x -> VisibilityDepthSample(x, y, 990, 255) } }
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES).use { fixture ->
            repeat(8) { index ->
                val batch = DepthEvidenceBatch(index + 1L, index + 1L, fixture.frame,
                    translation(0.05, 0.05, 0.0).toList(),
                    VisibilityCameraIntrinsics(128, 128, 4000.0, 4000.0, 62.5, 62.5),
                    samples.map { it.copy(depthMillimeters = if (index % 2 == 0) 990 else 1010) }, 0)
                fixture.step(batch)
                assertEquals(9, fixture.kernel.surfaceEstimationReceipt().locallyFittedSamples)
            }
            assertEquals(1, fixture.rows().size)
            DepthLocalPlaneEstimator(4096).use { estimator ->
                val invalid = fixture.batch(20, 1000, samples = gridSamples(1000).map { it.copy(confidence = 0) })
                estimator.index(invalid, invalid, DepthEvidenceConfiguration())
                assertTrue(!estimator.estimate(invalid, invalid, 0, DepthEvidenceConfiguration()))
                assertEquals(0, estimator.fittedSamples)
            }
        }
    }

    @Test fun `current estimate support reuse preserves aliased geometry means votes and canonical planning`() {
        val mode = DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES
        Fixture(mode, reuse = false).use { control -> Fixture(mode, reuse = true).use { candidate ->
            repeat(8) { index ->
                val depth = if (index % 2 == 0) 990 else 1010
                val expected = control.step(control.batch(index + 1L, depth))
                var aliasHint = false
                val input = candidate.batch(index + 1L, depth)
                val actual = candidate.lookup { view -> candidate.kernel.prepare(input, object : BoundedCanonicalSurfaceView by view {
                    override fun findSurfaceAtInto(x: Int, y: Int, z: Int, scratch: CanonicalSurfaceScratch): Boolean {
                        if (index % 2 != 0 && candidate.kernel.currentEstimateDestinationHintForTest(Voxel(0,0,-11)) >= 0) aliasHint = true
                        return view.findSurfaceAtInto(x,y,z,scratch)
                    }
                }) } as DepthEvidenceResult.Accepted
                if (index % 2 != 0) assertTrue(aliasHint)
                candidate.finish(actual)
                assertEquals(expected.changes, actual.changes)
                assertEquals(expected.receipt.acceptedSamples, actual.receipt.acceptedSamples)
                assertEquals(control.rows(), candidate.rows())
                val receipt = candidate.kernel.surfaceEstimationReceipt()
                assertEquals(1, receipt.independentPlaneFits)
                assertEquals(9, receipt.fullFitSuccesses + receipt.reuseQualifiedPoints)
                assertTrue(receipt.reuseQualifiedPoints > 0)
                assertEquals(9 * 27, receipt.associationCellLookups)
                assertThrows(IllegalStateException::class.java) { candidate.kernel.readPlaneSupportWord(0) }
            }
            val row = candidate.rows().single()
            assertEquals(control.kernel.surfacePositionEstimate(row.voxel), candidate.kernel.surfacePositionEstimate(row.voxel))
            assertEquals(control.kernel.resourceReceipt().fixedPrimitiveBytes, candidate.kernel.resourceReceipt().fixedPrimitiveBytes)
            assertEquals(control.kernel.resourceReceipt().modeledMaximumSemanticStateBytes, candidate.kernel.resourceReceipt().modeledMaximumSemanticStateBytes)
        } }
    }

    @Test fun `cached supplied support refuses extrapolation residual and crossing tangent witnesses`() {
        val samples = gridSamples(1000) + listOf(VisibilityDepthSample(5, 5, 1000, 255),
            VisibilityDepthSample(19, 19, 1000, 255), VisibilityDepthSample(6, 6, 1020, 255))
        Fixture(DepthSurfaceEstimationMode.LOCAL_NORMALS).use { fixture ->
            val batch = fixture.batch(1, 1000, samples = samples)
            val values = IntArray(64)
            val words = object : CurrentEstimatePlaneSupportWords {
                override fun readPlaneSupportWord(index: Int) = values[index]
                override fun writePlaneSupportWord(index: Int, value: Int) { values[index] = value }
            }
            DepthLocalPlaneEstimator(4096).use { estimator ->
                estimator.index(batch, batch, DepthEvidenceConfiguration())
                assertTrue(estimator.estimate(batch, batch, 0, DepthEvidenceConfiguration(), words))
                assertTrue(!estimator.tryReuse(batch, batch, 10, DepthEvidenceConfiguration(), words, 0))
                assertTrue(!estimator.tryReuse(batch, batch, 11, DepthEvidenceConfiguration(), words, 0))
                val crossed = batch.copy(samples = gridSamples(1000) + listOf(
                    VisibilityDepthSample(5, 5, 1000, 255), VisibilityDepthSample(6, 5, 1005, 255)))
                estimator.index(crossed, crossed, DepthEvidenceConfiguration())
                assertTrue(estimator.estimate(crossed, crossed, 0, DepthEvidenceConfiguration(), words))
                // The centre itself lies on the cached plane; its local tangent disagrees.
                assertTrue(!estimator.tryReuse(crossed, crossed, 9, DepthEvidenceConfiguration(), words, 0))
            }
        }
    }

    @Test fun `failed first support fit never suppresses later observable points in the same cell`() {
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, reuse = true).use { fixture ->
            val samples = listOf(VisibilityDepthSample(10, 10, 1070, 255)) + gridSamples(1030)
            repeat(4) { index ->
                val accepted = fixture.step(fixture.batch(index + 1L, 1030, samples = samples))
                assertEquals(10, accepted.receipt.acceptedSamples)
                val receipt = fixture.kernel.surfaceEstimationReceipt()
                assertTrue(receipt.fullFitAttempts >= 2)
                assertTrue(receipt.fullFitSuccesses > 0)
                assertTrue(receipt.reuseQualifiedPoints > 0)
                assertEquals(1, receipt.independentPlaneFits)
            }
            assertEquals(1, fixture.rows().size)
        }
    }

    @Test fun `support scratch ends before held ACK and resets after exception discard and tiny capacity`() {
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, reuse = true).use { fixture ->
            repeat(3) { fixture.step(fixture.batch(it + 1L, if (it % 2 == 0) 990 else 1010)) }
            val before = fixture.kernel.surfacePositionEstimate(Voxel(0, 0, -10))
            val batch = fixture.batch(4, 1010)
            val failed = fixture.lookup { view -> fixture.kernel.prepare(batch, object : BoundedCanonicalSurfaceView by view {
                override fun findSurfaceAtInto(x: Int, y: Int, z: Int, scratch: CanonicalSurfaceScratch): Boolean = error("lookup fault")
            }) } as DepthEvidenceResult.Refused
            assertEquals(DepthEvidenceRefusal.CANONICAL_LOOKUP_FAILED, failed.reason)
            assertEquals(before, fixture.kernel.surfacePositionEstimate(Voxel(0, 0, -10)))
            assertThrows(IllegalStateException::class.java) { fixture.kernel.readPlaneSupportWord(0) }
            val prepared = fixture.prepare(batch) as DepthEvidenceResult.Accepted
            assertThrows(IllegalStateException::class.java) { fixture.kernel.readPlaneSupportWord(0) }
            fixture.kernel.discardPrepared()
            val retry = fixture.prepare(batch) as DepthEvidenceResult.Accepted
            assertEquals(prepared, retry)
            val mutation = fixture.commit(retry, acknowledgeCurrent = false)
            assertEquals(DepthEvidenceRefusal.PREPARED_BUSY,
                (fixture.prepare(fixture.batch(5, 990)) as DepthEvidenceResult.Refused).reason)
            assertEquals(before, fixture.kernel.surfacePositionEstimate(Voxel(0, 0, -10)))
            assertThrows(IllegalStateException::class.java) { fixture.kernel.readPlaneSupportWord(0) }
            fixture.acknowledge()
            assertTrue(fixture.kernel.applyPrepared(mutation) is DepthEvidenceApplyResult.Applied)
            fixture.step(fixture.batch(5, 990))
            assertEquals(1, fixture.rows().size)
        }
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, reuse = true, capacity = 20).use { fixture ->
            assertEquals(0, fixture.kernel.currentEstimateSupportCapacityForTest())
            repeat(4) { fixture.step(fixture.batch(it + 1L, 1000)) }
            assertEquals(0, fixture.kernel.surfaceEstimationReceipt().reuseAttempts)
            assertEquals(9, fixture.kernel.surfaceEstimationReceipt().fullFitAttempts)
            assertEquals(1, fixture.rows().size)
        }
    }

    @Test fun `full bounded support cache falls back without changing many cell canonical results`() {
        val intrinsics = VisibilityCameraIntrinsics(1100, 41, 1000.0, 1000.0, 550.0, 20.0)
        val samples = (0 until 10).flatMap { patch -> listOf(0, 20, 40).flatMap { y ->
            listOf(-20, 0, 20).map { dx -> VisibilityDepthSample(50 + patch * 100 + dx, y, 1000, 255) } } }
        Fixture(DepthSurfaceEstimationMode.LOCAL_NORMALS, reuse = false, capacity = 64).use { control ->
            Fixture(DepthSurfaceEstimationMode.LOCAL_NORMALS, reuse = true, capacity = 64).use { candidate ->
                assertEquals(2, candidate.kernel.currentEstimateSupportCapacityForTest())
                val pair = (0 until 10).groupBy { candidate.kernel.currentEstimateSupportHashForTest(Voxel(it-5,0,-10)) }
                    .values.first { it.size >= 2 }.take(2)
                val patchOrder = pair + (0 until 10).filter { it !in pair }
                val reordered = patchOrder.flatMap { patch -> samples.filter { it.x in (30+patch*100)..(70+patch*100) } }
                repeat(4) { index ->
                    val expected = control.step(control.batch(index + 1L, 1000, samples = reordered).copy(intrinsics = intrinsics))
                    val actual = candidate.step(candidate.batch(index + 1L, 1000, samples = reordered).copy(intrinsics = intrinsics))
                    assertEquals(expected.changes, actual.changes)
                    assertEquals(control.rows(), candidate.rows())
                    assertEquals(90, actual.receipt.acceptedSamples)
                    val receipt = candidate.kernel.surfaceEstimationReceipt()
                    assertTrue(receipt.reuseQualifiedPoints > 0)
                    assertTrue(receipt.fullFitAttempts > 2)
                    assertEquals(10, receipt.independentPlaneFits)
                }
                assertEquals(10, candidate.rows().size)
            }
        }
    }

    @Test fun `support reuse retains signed layers foreground quantization and transformed camera controls`() {
        for (kind in listOf("parallel", "opposing", "foreground", "tiny")) {
            Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, reuse = false).use { control ->
                Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, reuse = true).use { candidate ->
                    repeat(8) { index ->
                        val depth = if (kind == "parallel" && index % 2 != 0) 1030 else 990
                        val camera = if (kind == "opposing" && index % 2 != 0) yaw(Math.PI, .05, .05, -2.0) else translation(.05,.05,0.0)
                        val samples = if (kind == "foreground") gridSamples(1030).map {
                            if (it.x == 10 && it.y == 10) it.copy(depthMillimeters = 990) else it
                        } else null
                        var left = control.batch(index + 1L, depth, camera = camera, samples = samples)
                        var right = candidate.batch(index + 1L, depth, camera = camera, samples = samples)
                        if (kind == "tiny") {
                            val pixels = (0 until 4096).map { VisibilityDepthSample(it % 64, it / 64,
                                if (index % 2 == 0) 990 else 1010, 255) }
                            val calibrated = VisibilityCameraIntrinsics(64,64,40000.0,40000.0,31.5,31.5)
                            left = left.copy(intrinsics = calibrated, samples = pixels)
                            right = right.copy(intrinsics = calibrated, samples = pixels)
                        }
                        control.step(left); candidate.step(right)
                        assertEquals(control.rows(), candidate.rows())
                    }
                    assertEquals(2, candidate.rows().size)
                }
            }
        }
        val transform = yaw(Math.PI / 2, .15, .10, .10)
        val frame = VisibilityGroupFrame.copyOf(transform, inverseRigid(transform), 100000,256)
        Fixture(DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES, frame, reuse = true).use { fixture ->
            repeat(8) { index ->
                val angle = if (index % 2 == 0) 0.0 else Math.PI / 9
                val camera = yaw(angle,.05+kotlin.math.tan(angle),.05,0.0)
                val samples = gridSamples(1000).map { it.copy(depthMillimeters =
                    (-1000.0/(-sin(angle)*(it.x-10)/OBSERVABLE_FOCAL-cos(angle))).toInt()) }
                fixture.step(fixture.batch(index+1L,1000,camera=multiply(transform,camera),samples=samples))
            }
            assertEquals(1,fixture.rows().size)
            assertTrue(normalError(fixture.rows().single().packedNormal,1.0,0.0,0.0)<2.0)
        }
    }

    @Test fun `support reuse owns only phase dead arrays and existing scalar reserve`() {
        val config=DepthEvidenceConfiguration(surfaceEstimationMode=DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES)
        DepthEvidenceKernel(config).use { reference -> DepthEvidenceKernel(config.copy(reuseCurrentEstimatePlaneSupport=true)).use { candidate ->
            assertEquals(2048,candidate.currentEstimateSupportCapacityForTest())
            assertEquals(reference.resourceReceipt().fixedPrimitiveBytes,candidate.resourceReceipt().fixedPrimitiveBytes)
            assertEquals(16706424,candidate.resourceReceipt().modeledMaximumSemanticStateBytes)
            val before=candidate.primitiveArraysForAccounting()
            assertEquals(reference.primitiveArraysForAccounting().map { java.lang.reflect.Array.getLength(it) },
                before.map { java.lang.reflect.Array.getLength(it) })
            assertThrows(IllegalStateException::class.java) { candidate.readPlaneSupportWord(0) }
            assertTrue(before.indices.all { before[it] === candidate.primitiveArraysForAccounting()[it] })
        } }
    }

    @Test fun `actual packed selector alternates control reuse control kernel and canonical costs`() {
        runSupportReuseComparison(manyCells = false)
    }

    @Test fun `actual many cell discontinuity selector keeps conservative reuse fallback costs`() {
        runSupportReuseComparison(manyCells = true)
    }

    private fun runSupportReuseComparison(manyCells: Boolean) {
        for (mode in DepthSurfaceEstimationMode.values()) {
            val lanes = if (mode == DepthSurfaceEstimationMode.DISABLED) listOf(ReuseCostLane("baseline",Fixture(mode))) else listOf(
                ReuseCostLane("control_before",Fixture(mode,reuse=false)), ReuseCostLane("reuse",Fixture(mode,reuse=true)),
                ReuseCostLane("control_after",Fixture(mode,reuse=false)))
            val pool = DepthSamplesLeasePool()
            var selected = 0
            try {
                repeat(16) { index ->
                    var closes = 0
                    val source = RawDepthCopySource(object: PairedRawDepthAcquirer {
                        override fun acquireDepth() = object: RawDepthImage {
                            override val width=128; override val height=128
                            override fun unsignedValue(x:Int,y:Int):Int = if (manyCells) {
                                if ((x/16+y/16)%2==0) 990 else 1030
                            } else if (index%2==0) 990 else 1010
                            override fun close() { closes++ }
                        }
                        override fun acquireConfidence():RawDepthImage?=null
                    })
                    val focal=if(manyCells)500.0 else 4000.0
                    val result=source.acquirePacked({ w,h->RawDepthFrameMetadata(index+1L,1,1,true,w,h,
                        DepthIntrinsics(focal,focal,63.5,63.5),translation(.05,.05,0.0))},pool,SampleLeaseGeneration(1,1,1,1)) as PackedDepthAcquisitionResult.Observation
                    val lease=result.lease
                    try {
                        assertEquals(1,closes);selected=lease.samples.count
                        assertTrue(selected in 1000..4096)
                        if(!manyCells)assertEquals(3025,selected)
                        val order=if(index%2==0)lanes.indices else lanes.indices.reversed()
                        for(laneIndex in order){
                            val lane=lanes[laneIndex];val fixture=lane.fixture
                            val metadata=DepthEvidenceMetadata(index+1L,index+1L,fixture.frame,translation(.05,.05,0.0).toList(),
                                VisibilityCameraIntrinsics(128,128,focal,focal,63.5,63.5),lease.samples.rejectedCount)
                            val allocation=allocatedBytes();val start=System.nanoTime()
                            val accepted=fixture.lookup { fixture.kernel.prepare(lease.samples,metadata,it) } as DepthEvidenceResult.Accepted
                            lane.kernelNs+=System.nanoTime()-start;lane.kernelBytes+=allocationDelta(allocation)
                            val receipt=fixture.kernel.surfaceEstimationReceipt()
                            lane.fullAttempts+=receipt.fullFitAttempts;lane.fullSuccess+=receipt.fullFitSuccesses
                            lane.reuseAttempts+=receipt.reuseAttempts;lane.reused+=receipt.reuseQualifiedPoints;lane.refused+=receipt.reuseRefusals
                            lane.witnessWork+=receipt.reuseWitnessWork;lane.scratchWork+=receipt.reuseScratchWork
                            lane.independent+=receipt.independentPlaneFits;lane.associations+=receipt.associationCellLookups
                            lane.changes+=accepted.changes.size
                            val commitAllocation=allocatedBytes();val commitStart=System.nanoTime()
                            fixture.finish(accepted);lane.ackNs+=System.nanoTime()-commitStart;lane.ackBytes+=allocationDelta(commitAllocation)
                        }
                    }finally{lease.close()}
                    assertEquals(0,pool.leasedCount())
                }
                for(lane in lanes)println("depth-support-reuse-cost manyCells=$manyCells mode=$mode lane=${lane.name} selected=$selected rows=${lane.fixture.rows().size} changes=${lane.changes} fullFitAttempts=${lane.fullAttempts} fullFitSuccesses=${lane.fullSuccess} reuseAttempts=${lane.reuseAttempts} reusedPoints=${lane.reused} reuseRefusals=${lane.refused} witnessWork=${lane.witnessWork} scratchWork=${lane.scratchWork} independentFits=${lane.independent} associationLookups=${lane.associations} allocationScope=JVM_THREAD coldKernelNs=${lane.kernelNs.take(2)} coldAckNs=${lane.ackNs.take(2)} kernelNs=${lane.kernelNs.drop(2)} canonicalAckNs=${lane.ackNs.drop(2)} kernelAllocatedBytes=${lane.kernelBytes.drop(2)} canonicalAllocatedBytes=${lane.ackBytes.drop(2)}")
                if(!manyCells){
                    for(lane in lanes){assertEquals(if(mode==DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES)1 else 2,lane.fixture.rows().size)
                        if(mode!=DepthSurfaceEstimationMode.DISABLED)assertTrue(lane.fullSuccess+lane.reused>=selected*12)}
                }
                if(lanes.size==3){
                    assertEquals(lanes[0].fixture.rows(),lanes[2].fixture.rows())
                    assertEquals(lanes[0].fixture.rows(),lanes[1].fixture.rows())
                    assertTrue(lanes[1].reused>0 || manyCells)
                    assertEquals(lanes[0].independent,lanes[1].independent)
                    if(mode==DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES)assertEquals(lanes[0].associations,lanes[1].associations)
                }
            }finally{pool.close();lanes.forEach{it.fixture.close()}}
        }
    }

    private class ReuseCostLane(val name:String,val fixture:Fixture){
        val kernelNs=mutableListOf<Long>();val ackNs=mutableListOf<Long>()
        val kernelBytes=mutableListOf<Long>();val ackBytes=mutableListOf<Long>()
        var fullAttempts=0L;var fullSuccess=0L;var reuseAttempts=0L;var reused=0L;var refused=0L
        var witnessWork=0L;var scratchWork=0L;var independent=0L;var associations=0L;var changes=0L
    }

    private class Fixture(mode: DepthSurfaceEstimationMode,
        val frame: VisibilityGroupFrame = VisibilityGroupFrame.copyOf(identityVisibilityGridTransform(), identityVisibilityGridTransform(), 100_000, 256), reuse: Boolean = false, capacity: Int = 256) : AutoCloseable {
        private val root = Files.createTempDirectory("depth-plane-fusion").toFile()
        private val group = SurfaceGroup("f".repeat(32))
        private val coordinator = StorageBudgetCoordinatorV2(File(root, "visibility-grid-canonical-surface-runtime"),
            StorageBudgetPolicyV2(64L * 1024 * 1024, 0), JvmDescriptorFilesystemV2())
        val runtime = CanonicalRuntimeResources.openLive(root, group, coordinator,
            SurfaceOwnershipConfiguration(voxelMicrometers = frame.voxelSizeMicrometres, surfaceCapacity = capacity, lineageCapacity = capacity * 2))
        val kernel = DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceCapacity = capacity, surfaceEstimationMode = mode, reuseCurrentEstimatePlaneSupport = reuse))
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
