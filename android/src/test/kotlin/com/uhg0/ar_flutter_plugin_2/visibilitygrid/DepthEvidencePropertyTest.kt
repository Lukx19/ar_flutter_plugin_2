package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.Collections
import java.util.IdentityHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthEvidencePropertyTest {
    @Test
    fun `depth batch rejects oversized inputs before copying them`() {
        var matrixElementRead = false
        val oversizedMatrix = object : AbstractList<Double>() {
            override val size = 17
            override fun get(index: Int): Double {
                matrixElementRead = true
                error("invalid matrix must not be copied")
            }
        }
        var sampleElementRead = false
        val oversizedSamples = object : AbstractList<VisibilityDepthSample>() {
            override val size = V2_DEPTH_SAMPLE_CAPACITY + 1
            override fun get(index: Int): VisibilityDepthSample {
                sampleElementRead = true
                error("over-capacity batch must not be copied")
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            DepthEvidenceBatch(1, 1, frame(), oversizedMatrix, intrinsics(), emptyList(), 0)
        }
        assertEquals(false, matrixElementRead)
        assertThrows(IllegalArgumentException::class.java) {
            DepthEvidenceBatch(1, 1, frame(), identity(), intrinsics(), oversizedSamples, 0)
        }
        assertEquals(false, sampleElementRead)
    }

    @Test
    fun `depth observation rejects over capacity before copying samples`() {
        var elementRead = false
        val samples = object : AbstractList<VisibilityDepthSample>() {
            override val size = V2_DEPTH_SAMPLE_CAPACITY + 1
            override fun get(index: Int): VisibilityDepthSample {
                elementRead = true
                error("over-capacity input must not be copied")
            }
        }
        val groupFrame = frame()
        val ownership = VisibilityObservationOwnership(
            sessionId = "0".repeat(32), sessionGeneration = 1,
            captureGroupId = "1".repeat(32), groupGeneration = 1, coverageEpoch = 1,
            arSessionIdentity = "2".repeat(32), viewInstanceId = "3".repeat(32), viewGeneration = 1,
            nativeStreamToken = "4".repeat(32), workerBindingToken = "5".repeat(32),
            bindingGeneration = 1, lifecycleSequence = 1, operationGeneration = 1,
            groupFrame = groupFrame,
        )
        val observationFrame = VisibilityObservationFrame(
            source = VisibilityObservationSource.SYNTHETIC_DEPTH,
            frameSequence = 1, frameTimestampNs = 1, sourceTimestampNs = 1,
            cameraIdentity = "camera", tracking = true,
            imageOrientation = "landscape_right_x_right_y_down_v1",
            pose = VisibilityCameraPose.copyOf(identity().toDoubleArray()),
            intrinsics = VisibilityCameraIntrinsics(1, 1, 1.0, 1.0, 0.0, 0.0),
            depthCapability = VisibilityDepthCapability.RAW_DEPTH,
        )

        assertThrows(IllegalArgumentException::class.java) {
            VisibilityDepthObservation(
                ownership = ownership,
                frame = observationFrame,
                samples = samples,
                sourceRejectedSamples = 0,
                payloadBytes = VisibilityDepthObservation.DEPTH_FIXED_BYTES +
                    samples.size * VisibilityDepthObservation.DEPTH_SAMPLE_BYTES,
            )
        }
        assertEquals(false, elementRead)
    }

    @Test
    fun `permuting pixels preserves the deterministic accepted packet`() {
        val samples = listOf(
            VisibilityDepthSample(0, 0, 1_000, 255),
            VisibilityDepthSample(1, 0, 1_000, 255),
            VisibilityDepthSample(2, 0, 1_000, 255),
        )
        val first = prepare(DepthEvidenceBatch(1, 1, frame(), identity(), intrinsics(), samples, 0))
        val reversed = prepare(DepthEvidenceBatch(1, 1, frame(), identity(), intrinsics(), samples.reversed(), 0))

        val expected = accepted(sequence = 1, acceptedSamples = 3, rayVisits = 52, touchedRows = 3, virtualWork = 58)
        assertEquals(expected, first)
        assertEquals(expected, reversed)
    }

    @Test
    fun `invalid inputs use typed refusals without consuming sequence`() {
        val kernel = DepthEvidenceKernel()
        val view = PropertyView()
        val valid = batch(1)
        assertTrue(kernel.prepare(valid, view) is DepthEvidenceResult.Accepted)
        kernel.applyPrepared()

        val committed = accepted(1, rayVisits = 22, virtualWork = 24).receipt
        val duplicate = kernel.prepare(batch(1), view)
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.DUPLICATE_TIMESTAMP, committed), duplicate)
        val notTracking = kernel.prepare(batch(2, tracking = false), view)
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.NOT_TRACKING, committed), notTracking)
        assertThrows(IllegalArgumentException::class.java) {
            DepthEvidenceBatch(3, 3, frame(), listOf(1.0), intrinsics(), listOf(sample()), 0)
        }
        assertEquals(1, kernel.resourceReceipt().residentEvidenceRows)
    }

    @Test
    fun `sample ray and surface capacities refuse before state mutation`() {
        val sampleLimited = DepthEvidenceKernel(DepthEvidenceConfiguration(sampleCapacity = 1))
        val tooManySamples = sampleLimited.prepare(
            DepthEvidenceBatch(1, 1, frame(), identity(), intrinsics(), listOf(sample(), sample()), 0),
            PropertyView(),
        ) as DepthEvidenceResult.Refused
        val capacityReceipt = DepthEvidenceReceipt(capacityRefusals = 1)
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.SAMPLE_CAPACITY, capacityReceipt), tooManySamples)
        assertEquals(0, sampleLimited.resourceReceipt().residentEvidenceRows)
        val afterSampleRefusal = sampleLimited.prepare(
            DepthEvidenceBatch(2, 2, frame(), identity(), intrinsics(), listOf(sample()), 0),
            PropertyView(),
        ) as DepthEvidenceResult.Accepted
        assertEquals(accepted(2, rayVisits = 22, capacityRefusals = 1, virtualWork = 24), afterSampleRefusal)
        sampleLimited.discardPrepared()

        val rayLimited = DepthEvidenceKernel(DepthEvidenceConfiguration(rayVisitCapacity = 1))
        val tooManyRays = rayLimited.prepare(batch(1), PropertyView()) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.RAY_VISIT_CAPACITY, capacityReceipt), tooManyRays)
        assertEquals(0, rayLimited.resourceReceipt().residentEvidenceRows)

        val surfaceLimited = DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceCapacity = 1))
        val twoEndpoints = surfaceLimited.prepare(
            DepthEvidenceBatch(
                1, 1, frame(), identity(), intrinsics(),
                listOf(VisibilityDepthSample(0, 0, 1_000, 255), VisibilityDepthSample(1, 0, 1_000, 255)), 0,
            ),
            PropertyView(),
        ) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.SURFACE_CAPACITY, capacityReceipt), twoEndpoints)
        assertEquals(0, surfaceLimited.resourceReceipt().residentEvidenceRows)
    }

    @Test
    fun `projected canonical capacity refuses an addition to a full view`() {
        val kernel = DepthEvidenceKernel()
        val fullView = PropertyView(surfaceCount = 100_000)
        val fullFrame = VisibilityGroupFrame.copyOf(
            identity().toDoubleArray(), identity().toDoubleArray(), 100_000, 100_000,
        )
        repeat(3) { index ->
            kernel.prepare(batch(index + 1L).copy(groupFrame = fullFrame), fullView)
            kernel.applyPrepared()
        }

        val result = kernel.prepare(batch(4).copy(groupFrame = fullFrame), fullView)

        assertEquals(
            DepthEvidenceResult.Refused(
                DepthEvidenceRefusal.SURFACE_CAPACITY,
                accepted(3, rayVisits = 22, virtualWork = 24).receipt.copy(capacityRefusals = 1),
            ),
            result,
        )
        assertEquals(0, kernel.resourceReceipt().preparedEvidenceRows)
    }

    @Test
    fun `canonical evidence is inconsistent with a zero surface count`() {
        val target = Voxel(-8, 0, -10)
        val surface = DepthCanonicalSurface(SurfaceId(1), target, 0x1010, 200, 1)
        val result = DepthEvidenceKernel().prepare(
            batch(1),
            PropertyView(surfaceCount = 0, surface = surface),
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
    fun `resource receipt reports fixed primitive ownership and staged row bytes`() {
        val kernel = DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceCapacity = 1))
        val before = kernel.resourceReceipt()
        assertEquals(2_024, before.fixedPrimitiveBytes)

        val accepted = kernel.prepare(batch(1), PropertyView()) as DepthEvidenceResult.Accepted
        val staged = kernel.resourceReceipt()
        assertEquals(1, staged.preparedEvidenceRows)
        assertEquals(32, staged.preparedResidentBytes)
        assertEquals(before.fixedPrimitiveBytes, staged.fixedPrimitiveBytes)
        assertEquals(32, accepted.receipt.preparedResidentBytes)
        kernel.discardPrepared()
        assertEquals(0, kernel.resourceReceipt().preparedEvidenceRows)
    }

    @Test
    fun `maximum configured ownership remains within the semantic state budget`() {
        val receipt = DepthEvidenceKernel(
            DepthEvidenceConfiguration(
                sampleCapacity = 1_536,
                rayVisitCapacity = 65_536,
                surfaceCapacity = 100_000,
            ),
        ).resourceReceipt()

        assertEquals(
            DepthEvidenceResourceReceipt(
                residentEvidenceRows = 0,
                residentBytes = 0,
                preparedEvidenceRows = 0,
                preparedResidentBytes = 0,
                evidenceRowCapacity = 100_000,
                fixedPrimitiveBytes = 11_871_904,
                closed = false,
                maximumAcceptedOutputReserveBytes = 3_842_320,
                modeledMaximumSemanticStateBytes = 15_714_224,
            ),
            receipt,
        )
        val maximumMixedPacketBytes =
            65_536 * (24 + 24) +
                V2_DEPTH_SAMPLE_CAPACITY * (24 + 80) +
                (16 + (65_536 + V2_DEPTH_SAMPLE_CAPACITY) * 8) +
                (32 + 24) +
                (56 + 104 + 40)
        assertEquals(3_842_320, maximumMixedPacketBytes)
        assertEquals(maximumMixedPacketBytes, receipt.maximumAcceptedOutputReserveBytes)
        assertTrue(receipt.modeledMaximumSemanticStateBytes <= 16 * 1024 * 1024)
        assertThrows(IllegalArgumentException::class.java) {
            DepthEvidenceConfiguration(surfaceCapacity = 100_001)
        }
    }

    @Test
    fun `primitive ownership ledger contains every allocated array column exactly once`() {
        val kernel = DepthEvidenceKernel()
        val reflected = kernel.javaClass.declaredFields.mapNotNull { field ->
            if (!field.type.isArray || field.type.componentType?.isPrimitive != true) return@mapNotNull null
            field.isAccessible = true
            field.get(kernel)
        }
        val reflectedIdentities = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).apply {
            addAll(reflected)
        }
        val ledger = kernel.primitiveArraysForAccounting()
        val ledgerIdentities = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).apply {
            addAll(ledger)
        }

        assertEquals(reflected.size, reflectedIdentities.size)
        assertEquals(reflectedIdentities, ledgerIdentities)
        assertEquals(reflected.size, ledger.size)
    }

    @Test
    fun `reverse ordered maximum endpoint packet remains inside work and output headroom`() {
        val capacity = V2_DEPTH_SAMPLE_CAPACITY
        val width = 48
        val height = 32
        val samples = List(capacity) { index ->
            VisibilityDepthSample(index % width, index / width, 200, 255)
        }
        val largeFrame = VisibilityGroupFrame.copyOf(
            identity().toDoubleArray(), identity().toDoubleArray(), 100_000, 100_000,
        )
        val batch = DepthEvidenceBatch(
            1, 1, largeFrame, identity(),
            VisibilityCameraIntrinsics(width, height, 2.0, 2.0, 23.5, 15.5),
            samples, 0,
        )
        val kernel = DepthEvidenceKernel(DepthEvidenceConfiguration(safetyBandMillimetres = 10_000))
        repeat(3) { index ->
            kernel.prepare(batch.copy(sequence = index + 1L, sourceTimestampNs = index + 1L), PropertyView())
            kernel.applyPrepared()
        }
        val result = kernel.prepare(
            batch.copy(sequence = 4, sourceTimestampNs = 4, samples = samples.reversed()),
            PropertyView(),
        )
            as DepthEvidenceResult.Accepted

        assertEquals(capacity, result.changes.size)
        assertEquals(
            capacity,
            result.changes.map { (it as DepthEvidenceChange.Create).target.voxel }.toSet().size,
        )
        assertEquals(
            DepthEvidenceReceipt(
                sequence = 4,
                sourceTimestampNs = 4,
                acceptedSamples = capacity,
                rayVisits = 38_336,
                touchedEvidenceRows = capacity,
                createCount = capacity,
                preparedResidentBytes = capacity * 32,
                p50VirtualWorkUnits = 78_272,
                p95VirtualWorkUnits = 78_272,
            ),
            result.receipt,
        )
        assertEquals(
            DepthEvidenceWorkReceipt(capacity, capacity, 38_336, 0, 78_272),
            result.work,
        )
        assertEquals(3_842_320, kernel.resourceReceipt().maximumAcceptedOutputReserveBytes)
        assertEquals(15_714_224, kernel.resourceReceipt().modeledMaximumSemanticStateBytes)
    }

    @Test
    fun `prepared bytes account for an update to an existing resident row`() {
        val kernel = DepthEvidenceKernel(DepthEvidenceConfiguration(surfaceCapacity = 1))
        val view = PropertyView()

        kernel.prepare(batch(1), view)
        kernel.applyPrepared()
        val update = kernel.prepare(batch(2), view) as DepthEvidenceResult.Accepted
        val staged = kernel.resourceReceipt()

        assertEquals(1, staged.residentEvidenceRows)
        assertEquals(32, staged.residentBytes)
        assertEquals(1, staged.preparedEvidenceRows)
        assertEquals(32, staged.preparedResidentBytes)
        assertEquals(32, update.receipt.preparedResidentBytes)
        kernel.discardPrepared()
        assertEquals(0, kernel.resourceReceipt().preparedEvidenceRows)
        assertEquals(1, kernel.resourceReceipt().residentEvidenceRows)
    }

    @Test
    fun `invalid sample and checked counter overflow are bounded`() {
        val kernel = DepthEvidenceKernel()
        val invalid = kernel.prepare(
            DepthEvidenceBatch(1, 1, frame(), identity(), intrinsics(), listOf(VisibilityDepthSample(0, 0, 1, 255)), 0),
            PropertyView(),
        )
        assertEquals(DepthEvidenceResult.Refused(DepthEvidenceRefusal.INVALID_SAMPLE, DepthEvidenceReceipt()), invalid)

        val overflow = kernel.prepare(
            DepthEvidenceBatch(2, 2, frame(), identity(), intrinsics(), listOf(VisibilityDepthSample(0, 0, 1, 255)), Int.MAX_VALUE),
            PropertyView(),
        )
        assertEquals(
            DepthEvidenceResult.Refused(
                DepthEvidenceRefusal.ARITHMETIC_OVERFLOW,
                DepthEvidenceReceipt(overflowCount = 1),
            ),
            overflow,
        )
    }

    @Test
    fun `prepare distinguishes endpoint coordinate and traversal origin overflow`() {
        fun translated(xMetres: Double) = identityVisibilityGridTransform().also { it[12] = xMetres }.toList()
        val endpointOverflow = DepthEvidenceKernel().prepare(
            DepthEvidenceBatch(
                1, 1, frame(), translated(200_000.0), intrinsics(), listOf(sample()), 0,
            ),
            PropertyView(),
        )
        assertEquals(
            DepthEvidenceResult.Refused(
                DepthEvidenceRefusal.ARITHMETIC_OVERFLOW,
                DepthEvidenceReceipt(overflowCount = 1),
            ),
            endpointOverflow,
        )

        // The camera lies 400 mm beyond the representable positive edge, but
        // this off-axis endpoint returns 350 mm inside it. Endpoint admission
        // succeeds and the traversal-origin quantization is the failing path.
        val traversalOverflow = DepthEvidenceKernel().prepare(
            DepthEvidenceBatch(
                1, 1, frame(), translated(104_858.0), intrinsics(), listOf(sample()), 0,
            ),
            PropertyView(),
        )
        assertEquals(
            DepthEvidenceResult.Refused(
                DepthEvidenceRefusal.ARITHMETIC_OVERFLOW,
                DepthEvidenceReceipt(overflowCount = 1),
            ),
            traversalOverflow,
        )
    }

    @Test
    fun `coordinate boundaries remain quantized or refuse without wraparound`() {
        val atMinimum = DepthPointMm(-104_857_600.0, 0.0, 0.0)
        val atMaximum = DepthPointMm(104_857_599.9, 0.0, 0.0)
        assertEquals(Voxel(VOXEL_COORDINATE_MIN, 0, 0), quantize(atMinimum))
        assertEquals(Voxel(VOXEL_COORDINATE_MAX, 0, 0), quantize(atMaximum))
        assertTrue(quantize(DepthPointMm(104_857_600.0, 0.0, 0.0)) == null)
    }

    private fun prepare(batch: DepthEvidenceBatch): DepthEvidenceResult.Accepted {
        val kernel = DepthEvidenceKernel()
        return kernel.prepare(batch, PropertyView()) as DepthEvidenceResult.Accepted
    }

    private fun accepted(
        sequence: Long,
        acceptedSamples: Int = 1,
        rayVisits: Int = 0,
        touchedRows: Int = 1,
        capacityRefusals: Int = 0,
        virtualWork: Int = acceptedSamples + rayVisits + touchedRows,
    ): DepthEvidenceResult.Accepted {
        val accountedWork = virtualWork + touchedRows * 24
        return DepthEvidenceResult.Accepted(
        expectedGeometryRevision = 0,
        expectedLineageRevision = 0,
        changes = emptyList(),
        receipt = DepthEvidenceReceipt(
            sequence = sequence,
            sourceTimestampNs = sequence,
            acceptedSamples = acceptedSamples,
            rayVisits = rayVisits,
            touchedEvidenceRows = touchedRows,
            capacityRefusals = capacityRefusals,
            preparedResidentBytes = touchedRows * 32,
            p50VirtualWorkUnits = accountedWork,
            p95VirtualWorkUnits = accountedWork,
        ),
        work = DepthEvidenceWorkReceipt(touchedRows, 0, rayVisits, 0, accountedWork),
    )
    }

    private fun batch(timestamp: Long, tracking: Boolean = true) = DepthEvidenceBatch(
        timestamp, timestamp, frame(), identity(), intrinsics(), listOf(sample()), 0, tracking,
    )

    private fun sample() = VisibilityDepthSample(0, 0, 1_000, 255)

    private fun frame() = VisibilityGroupFrame.copyOf(identity().toDoubleArray(), identity().toDoubleArray(), 100_000, 100)
    private fun identity() = identityVisibilityGridTransform().toList()
    private fun intrinsics() = VisibilityCameraIntrinsics(4, 1, 2.0, 2.0, 1.5, 0.0)

    private fun quantize(point: DepthPointMm): Voxel? {
        val value = Math.floor(point.x / 100.0)
        return if (value >= VOXEL_COORDINATE_MIN && value <= VOXEL_COORDINATE_MAX) {
            Voxel(value.toInt(), 0, 0)
        } else {
            null
        }
    }

    private class PropertyView(
        override val surfaceCount: Int = 0,
        private val surface: DepthCanonicalSurface? = null,
    ) : BoundedCanonicalSurfaceView {
        override val geometryRevision: Long = 0
        override val lineageRevision: Long = 0

        override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? = surface?.takeIf { it.id == id }
        override fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface? =
            surface?.takeIf { it.voxel == voxel }?.let { AddressedCanonicalSurface(voxel, it) }
    }
}
