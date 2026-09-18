package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityGridRendererState
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageCommittedRow
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageCommittedRows
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRowsQualifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageRendererSelectionTest {
    @Test
    fun `streamed canonical source is bounded to centroid and cube presentation caps`() {
        val rows = streamedRows(100_000)
        val selector = CoveragePresentationSelector(CoverageRendererLimits.CENTROID_CAPACITY)

        val centroid = selector.select(
            rows,
            requestedCapacity = CoverageRendererLimits.CENTROID_CAPACITY,
            forceReset = true,
        )
        assertEquals(CoverageRendererLimits.CENTROID_CAPACITY, centroid.count)
        val centroidStorage = selector.ownedStorageBytes
        assertEquals(
            CoveragePresentationStorage.estimatedOwnedStorageBytes(
                CoverageRendererLimits.CENTROID_CAPACITY,
                sourceCapacity = 0,
            ),
            centroidStorage,
        )

        val cube = selector.select(
            rows,
            requestedCapacity = CoverageRendererLimits.CUBE_CAPACITY,
            forceReset = true,
        )
        assertEquals(CoverageRendererLimits.CUBE_CAPACITY, cube.count)
        assertTrue(selector.ownedStorageBytes < centroidStorage)
        assertEquals(1L, cube.surfaceIds.first())
        assertEquals(8_000L, cube.surfaceIds.last())
    }

    @Test
    fun `streamed ordinary update carries one range-only presentation span`() {
        val rows = streamedRows(100_000)
        val selector = CoveragePresentationSelector(CoverageRendererLimits.CENTROID_CAPACITY)
        selector.select(rows, forceReset = true)
        val before = selector.ownedStorageBytes
        rows.geometryRevision = 2L
        rows.xOffset = 42f
        val update = CoveragePointRenderUpdate(
            geometryRevision = 2L,
            visibilityRevision = 1L,
            enabled = true,
            count = rows.count,
            spans = listOf(
                CoveragePointSpan(
                    startSlot = 0,
                    positions = FloatArray(0),
                    colors = IntArray(0),
                    endSlotExclusive = 1,
                ),
            ),
            reset = false,
        )
        val next = selector.select(rows, sourceUpdate = update, forceReset = false)
        assertEquals(42f, next.positions[0])
        assertEquals(before, selector.ownedStorageBytes)
        val span = next.update!!.spans.single()
        assertTrue(span.positions.isEmpty())
        assertTrue(span.colors.isEmpty())
        assertEquals(0, span.startSlot)
        assertEquals(1, span.endSlotExclusive)
    }

    @Test
    fun `canonical source capacity rejects one row beyond the contract`() {
        assertThrows(IllegalArgumentException::class.java) {
            VisibilityGridRendererState(100_001)
        }
    }

    @Test
    fun `selection identity lookup uses compact sorted surface ids`() {
        val selector = CoveragePresentationSelector(8)
        selector.select(
            CoveragePointRenderSnapshot(
                revision = 1L,
                enabled = true,
                capacity = 3,
                count = 3,
                keys = longArrayOf(30L, 10L, 20L),
                surfaceIds = longArrayOf(30L, 10L, 20L),
                positions = FloatArray(9),
                colors = IntArray(3),
            ),
            requestedCapacity = 3,
        )

        val destinationForTen = selector.destinationForSurfaceId(10L)
        val destinationForThirty = selector.destinationForSurfaceId(30L)
        assertTrue(destinationForTen >= 0)
        assertTrue(destinationForThirty >= 0)
        assertEquals(10L, selector.selectedSurfaceId(destinationForTen))
        assertEquals(30L, selector.selectedSurfaceId(destinationForThirty))
        assertEquals(-1, selector.destinationForSurfaceId(99L))
    }

    private fun streamedRows(count: Int): MutableStreamRows = MutableStreamRows(count)

    private class MutableStreamRows(
        override val count: Int,
    ) : CoverageCommittedRows {
        override val capacity: Int = 100_000
        var geometryRevision: Long = 1L
        var xOffset: Float = 0f
        override val qualifier: CoverageRowsQualifier
            get() = CoverageRowsQualifier(1L, 1L, 1L, 1L, geometryRevision, 1L)

        override fun rowAt(index: Int): CoverageCommittedRow {
            require(index in 0 until count)
            return CoverageCommittedRow(
                surfaceId = index + 1L,
                key = index + 1L,
                x = index.toFloat() + xOffset,
                y = 0f,
                z = 0f,
                color = 0,
                style = CoverageRendererStyleRowV1(),
            )
        }
    }

    @Test
    fun `mode cycle shrinks cube cut and restores centroid from canonical source while dirty values stay top level`() {
        val sourceCount = CoverageRendererLimits.CENTROID_CAPACITY
        val positions = FloatArray(sourceCount * 3)
        positions[0] = 7f
        val source = CoveragePointRenderSnapshot(
            revision = 1L,
            enabled = true,
            capacity = sourceCount,
            count = sourceCount,
            keys = LongArray(sourceCount) { it.toLong() },
            positions = positions,
            colors = IntArray(sourceCount),
        )
        val selector = CoveragePresentationSelector(CoverageRendererLimits.CENTROID_CAPACITY)

        val cube = selector.select(
            source,
            requestedCapacity = CoverageRendererLimits.CUBE_CAPACITY,
        )
        assertEquals(CoverageRendererLimits.CUBE_CAPACITY, cube.count)
        assertEquals(
            CoveragePresentationStorage.estimatedOwnedStorageBytes(
                CoverageRendererLimits.CUBE_CAPACITY,
                sourceCapacity = source.capacity,
            ),
            selector.ownedStorageBytes,
        )
        assertTrue(
            CoverageRendererLimits.activeRendererPeakBytes(
                VoxelRenderMode.CUBES,
                sourceCapacity = source.capacity,
                retainedCount = source.count,
            ) <= CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES,
        )

        val mutatedPositions = positions.copyOf().also { it[0] = 42f }
        val mutated = source.copy(
            revision = 2L,
            positions = mutatedPositions,
            update = CoveragePointRenderUpdate(
                geometryRevision = 2L,
                visibilityRevision = 2L,
                enabled = true,
                count = sourceCount,
                spans = listOf(
                    CoveragePointSpan(
                        startSlot = 0,
                        positions = FloatArray(0),
                        colors = IntArray(0),
                        endSlotExclusive = 1,
                    ),
                ),
                reset = false,
            ),
        )
        val mutatedCube = selector.select(
            mutated,
            requestedCapacity = CoverageRendererLimits.CUBE_CAPACITY,
            forceReset = false,
        )
        assertEquals(42f, mutatedCube.positions[0])
        val dirtyUpdate = checkNotNull(mutatedCube.update)
        assertTrue(dirtyUpdate.spans.single().positions.isEmpty())
        assertTrue(dirtyUpdate.spans.single().colors.isEmpty())

        val centroid = selector.select(
            mutated,
            requestedCapacity = CoverageRendererLimits.CENTROID_CAPACITY,
        )
        assertEquals(CoverageRendererLimits.CENTROID_CAPACITY, centroid.count)
        assertEquals(42f, centroid.positions[0])
        assertEquals(
            CoveragePresentationStorage.estimatedOwnedStorageBytes(
                CoverageRendererLimits.CENTROID_CAPACITY,
                sourceCapacity = source.capacity,
            ),
            selector.ownedStorageBytes,
        )
    }

    @Test
    fun `retained selector storage is lazy and follows the active presentation capacity`() {
        val selector = CoveragePresentationSelector(CoverageRendererLimits.CENTROID_CAPACITY)
        assertEquals(0, selector.ownedStorageBytes)
        val source = CoveragePointRenderSnapshot(
            revision = 1L,
            enabled = true,
            capacity = 2,
            count = 2,
            keys = longArrayOf(2L, 1L),
            positions = FloatArray(6),
            colors = IntArray(2),
        )

        selector.select(source, requestedCapacity = CoverageRendererLimits.COLD_OVERVIEW_CAPACITY)
        val overviewBytes = selector.ownedStorageBytes
        selector.select(source.copy(revision = 2L), requestedCapacity = CoverageRendererLimits.CENTROID_CAPACITY)

        assertTrue(overviewBytes > 0)
        assertTrue(selector.ownedStorageBytes > overviewBytes)
        assertEquals(
            CoveragePresentationStorage.estimatedOwnedStorageBytes(
                CoverageRendererLimits.CENTROID_CAPACITY,
                sourceCapacity = source.capacity,
            ),
            selector.ownedStorageBytes,
        )
    }

    @Test
    fun `production resource factory installs replacement before releasing old generation`() {
        val events = mutableListOf<String>()
        val modes = mutableListOf<com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode>()
        val factory = CoverageRendererResourceFactory()
        factory.replaceCube(
            8_000,
            "cubes",
            create = { mode, capacity, owner -> "cube".also { events += "create:$it:$mode:$capacity:$owner"; modes += mode } },
            release = { value: String -> events += "release:$value" },
        )
        factory.replacePoint(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            20_000,
            "centroids",
            create = { mode, capacity, owner -> "centroid".also { events += "create:$it:$mode:$capacity:$owner"; modes += mode } },
            release = { value: String -> events += "release:$value" },
        )
        factory.clear()
        assertEquals(
            listOf("create:cube:CUBES:8000:cubes", "create:centroid:CENTROIDS:20000:centroids", "release:cube", "release:centroid"),
            events,
        )
        assertEquals(
            listOf(
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES,
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            ),
            modes,
        )
    }

    @Test
    fun `token-qualified resource owners keep both generations chargeable during coexistence`() {
        val owners = mutableListOf<String>()
        val factory = CoverageRendererResourceFactory()
        factory.replacePoint(
            VoxelRenderMode.POINTS,
            1,
            "coverage-points",
            token = CoverageResourceToken(1L, 7L, CoveragePresentationMode.RAW_FEATURES),
            create = { _, _, owner -> owners += owner; "old" },
            release = { _: String -> },
        )
        factory.replacePoint(
            VoxelRenderMode.POINTS,
            1,
            "coverage-points",
            token = CoverageResourceToken(2L, 7L, CoveragePresentationMode.RAW_FEATURES),
            create = { _, _, owner -> owners += owner; "new" },
            release = { _: String -> },
        )

        assertEquals(listOf("coverage-points-epoch-1", "coverage-points-epoch-2"), owners)
    }

    @Test
    fun `failed replacement leaves the current renderer owner mounted`() {
        val released = mutableListOf<String>()
        val factory = CoverageRendererResourceFactory()
        factory.replaceCube(
            8_000,
            "current",
            create = { _, _, _ -> "current" },
            release = { value: String -> released += value },
        )

        val failed =
            factory.replacePoint(
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
                20_000,
                "replacement",
                create = { _, _, _ -> throw IllegalStateException("allocation failed") },
                release = { value: String -> released += value },
            )
        assertNull(failed)
        assertEquals(emptyList<String>(), released)
        factory.clear()
        assertEquals(listOf("current"), released)
    }

    @Test
    fun `cube to centroid replacement clears first and restores ledger after one release each`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        val events = mutableListOf<String>()
        val factory = CoverageRendererResourceFactory(
            admit = { mode, _ -> ledger.admitResourceReplacement(mode) },
            onClearFirst = { transition ->
                assertEquals(CoverageRendererTransitionStrategy.CLEAR_FIRST, transition.admission.strategy)
                telemetry.removeOwner("transition-pressure")
                events += "clear-first"
            },
        )

        fun construct(mode: com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode): String {
            ledger.installPersistentCoverageState(mode)
            ledger.updateSnapshotHandoff(mode)
            when (mode) {
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES ->
                    ledger.installCubeResources("active", CoverageRendererLimits.CUBE_CAPACITY)
                else ->
                    ledger.installPointResources("active", CoverageRendererLimits.CENTROID_CAPACITY)
            }
            events += "create:$mode"
            return mode.name
        }
        fun release(mode: com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode) {
            if (mode == com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES) {
                ledger.releaseCubeResources("active")
            } else {
                ledger.releasePointResources("active")
            }
            ledger.clearCoverageState()
            events += "release:$mode"
        }

        factory.replaceCube(
            CoverageRendererLimits.CUBE_CAPACITY,
            "active",
            create = { mode, _, _ -> construct(mode) },
            release = { release(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES) },
        )
        factory.replacePoint(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            CoverageRendererLimits.CENTROID_CAPACITY,
            "active",
            create = { mode, _, _ -> construct(mode) },
            release = { release(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS) },
        )
        factory.clear()

        assertEquals(
            listOf("create:CUBES", "clear-first", "release:CUBES", "create:CENTROIDS", "release:CENTROIDS"),
            events,
        )
        assertTrue(
            (telemetry.snapshot().getValue("peakOwnedBufferBytes") as Int) <=
                CoverageRendererLimits.INSTANTANEOUS_TRANSITION_LIMIT_BYTES,
        )
        assertEquals(0, telemetry.snapshot().getValue("ownedBufferBytes"))
    }

    @Test
    fun `within-budget coexistence retains old resource when replacement creation fails`() {
        val telemetry = RendererTelemetry()
        telemetry.setOwnedBufferBytes("unrelated", 1)
        val ledger = CoverageRendererAllocationLedger(telemetry)
        val released = mutableListOf<String>()
        val factory = CoverageRendererResourceFactory(
            admit = { mode, _ -> ledger.admitResourceReplacement(mode) },
        )
        factory.replacePoint(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.POINTS,
            1,
            "old",
            create = { _, _, _ -> "old" },
            release = { value: String -> released += value },
        )

        val failed =
            factory.replacePoint(
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.POINTS,
                1,
                "new",
                create = { _, _, _ -> throw IllegalStateException("allocation failed") },
                release = { value: String -> released += value },
            )
        assertNull(failed)
        assertEquals(emptyList<String>(), released)
        factory.clear()
        assertEquals(listOf("old"), released)
    }

    @Test
    fun `admission records candidate peaks and rejects a standalone over active ceiling`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)

        val coexist = ledger.admitResourceReplacement(VoxelRenderMode.POINTS)
        assertEquals(CoverageRendererTransitionStrategy.COEXIST, coexist.strategy)
        assertEquals("coexist", telemetry.snapshot().getValue("lastAdmissionStrategy"))
        assertEquals(coexist.candidateBytes, telemetry.snapshot().getValue("lastAdmissionCandidateBytes"))

        val rejected = ledger.admitResourceReplacement(
            VoxelRenderMode.POINTS,
            selectorStorageBytes = CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES,
        )
        assertEquals(CoverageRendererTransitionStrategy.REJECT, rejected.strategy)
        assertEquals("reject", telemetry.snapshot().getValue("lastAdmissionStrategy"))
        assertTrue(
            rejected.candidateBytes > CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES,
        )
    }

    @Test
    fun `admission uses clear first only when both generations exceed transition ceiling`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        val candidate = ledger.admitResourceReplacement(VoxelRenderMode.CENTROIDS).candidateBytes
        telemetry.setOwnedBufferBytes(
            "existing-generation",
            CoverageRendererLimits.INSTANTANEOUS_TRANSITION_LIMIT_BYTES - candidate + 1,
        )

        val admission = ledger.admitResourceReplacement(VoxelRenderMode.CENTROIDS)
        assertEquals(CoverageRendererTransitionStrategy.CLEAR_FIRST, admission.strategy)
        assertTrue(
            admission.candidateBytes <= CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES,
        )
        assertTrue(
            admission.combinedBytes > CoverageRendererLimits.INSTANTANEOUS_TRANSITION_LIMIT_BYTES,
        )
    }

    @Test
    fun `admission coexists at the transition boundary and clears one byte above it`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        val candidate = ledger.admitResourceReplacement(VoxelRenderMode.CUBES).candidateBytes

        telemetry.setOwnedBufferBytes(
            "existing-generation",
            CoverageRendererLimits.INSTANTANEOUS_TRANSITION_LIMIT_BYTES - candidate,
        )
        assertEquals(
            CoverageRendererTransitionStrategy.COEXIST,
            ledger.admitResourceReplacement(VoxelRenderMode.CUBES).strategy,
        )

        telemetry.removeOwner("existing-generation")
        telemetry.setOwnedBufferBytes(
            "existing-generation",
            CoverageRendererLimits.INSTANTANEOUS_TRANSITION_LIMIT_BYTES - candidate + 1,
        )
        assertEquals(
            CoverageRendererTransitionStrategy.CLEAR_FIRST,
            ledger.admitResourceReplacement(VoxelRenderMode.CUBES).strategy,
        )
    }

    @Test
    fun `rejected replacement does not invoke creation or release current resource`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        val events = mutableListOf<String>()
        var admissionCalls = 0
        val factory = CoverageRendererResourceFactory(
            admit = { mode, _ ->
                admissionCalls++
                if (admissionCalls == 1) {
                    CoverageRendererResourceAdmission(
                        strategy = CoverageRendererTransitionStrategy.COEXIST,
                        currentBytes = 0,
                        candidateBytes = 0,
                        combinedBytes = 0,
                    )
                } else {
                    ledger.admitResourceReplacement(
                        mode,
                        selectorStorageBytes = CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES,
                    )
                }
            },
            onCreationFailure = { events += "rejected" },
        )
        factory.replacePoint(
            VoxelRenderMode.POINTS,
            1,
            "old",
            create = { _, _, _ -> "old" },
            release = { events += "release" },
        )
        val replacement = factory.replacePoint(
            VoxelRenderMode.POINTS,
            1,
            "new",
            create = { _, _, _ -> events += "create"; "new" },
            release = { events += "release-new" },
        )

        assertNull(replacement)
        assertEquals(listOf("rejected"), events)
        factory.clear()
        assertEquals(listOf("rejected", "release"), events)
    }

    @Test
    fun `clear-first resource failure is typed and does not throw through composition`() {
        val events = mutableListOf<String>()
        val factory = CoverageRendererResourceFactory(
            admit = { _, _ ->
                CoverageRendererResourceAdmission(
                    strategy = CoverageRendererTransitionStrategy.CLEAR_FIRST,
                    currentBytes = 8,
                    candidateBytes = 9,
                    combinedBytes = 17,
                )
            },
            onClearFirst = { transition -> events += "clear:${transition.rendererGeneration}" },
            onCreationFailure = { transition -> events += "failed:${transition.rendererGeneration}" },
        )
        factory.replacePoint(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.POINTS,
            1,
            "current",
            rendererGeneration = 4L,
            create = { _, _, _ -> "current" },
            release = { events += "release" },
        )

        val result = factory.replacePoint(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            1,
            "replacement",
            rendererGeneration = 5L,
            create = { _, _, _ -> throw IllegalStateException("allocation failed") },
            release = { events += "replacement-release" },
        )

        assertNull(result)
        assertEquals(listOf("clear:5", "release", "failed:5"), events)
        factory.clear()
        assertEquals(listOf("clear:5", "release", "failed:5"), events)
    }

    @Test
    fun `renderer lazy mode resource peaks stay within the active fourteen MiB cap`() {
        assertEquals(2_000, CoverageRendererLimits.RAW_POINT_CAPACITY)
        assertEquals(20_000, CoverageRendererLimits.CENTROID_CAPACITY)
        assertEquals(8_000, CoverageRendererLimits.CUBE_CAPACITY)
        assertEquals(13_197_572, CoverageRendererLimits.maximumActiveRendererBytes)
        com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.entries.forEach { mode ->
            assertTrue(
                "$mode startup peak must fit the active renderer cap",
                CoverageRendererLimits.activeRendererPeakBytes(mode) <=
                    CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES,
            )
        }
    }

    @Test
    fun `production ownership receipt includes every live renderer owner exactly once`() {
        val receipt = CoverageRendererLimits.ownershipReceipt(VoxelRenderMode.CUBES)

        assertEquals(14 * 1024 * 1024, CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES)
        assertEquals(18 * 1024 * 1024, CoverageRendererLimits.INSTANTANEOUS_TRANSITION_LIMIT_BYTES)
        assertEquals(5_403_936, receipt.canonicalStateBytes)
        assertEquals(800_000, receipt.mutableProjectionSelectorBytes)
        assertEquals(640_100, receipt.descriptorBackingBytes)
        assertEquals(400_000, receipt.pageReaderCapturedMappingBytes)
        assertEquals(65_536, receipt.stagingBytes)
        assertEquals(5_888_000, receipt.meshBytes)
        assertEquals(13_197_572, receipt.totalBytes)
        assertTrue(receipt.canonicalStateBytes > 0)
        assertTrue(receipt.mutableProjectionSelectorBytes > 0)
        assertTrue(receipt.descriptorBackingBytes > 0)
        assertTrue(receipt.pageReaderCapturedMappingBytes > 0)
        assertTrue(receipt.stagingBytes > 0)
        assertTrue(receipt.meshBytes > 0)
        assertEquals(
            receipt.totalBytes,
            receipt.canonicalStateBytes +
                receipt.mutableProjectionSelectorBytes +
                receipt.descriptorBackingBytes +
                receipt.pageReaderCapturedMappingBytes +
                receipt.stagingBytes +
                receipt.meshBytes,
        )

        val telemetry = RendererTelemetry()
        val admission = CoverageRendererAllocationLedger(telemetry)
            .admitResourceReplacement(VoxelRenderMode.CUBES)
        assertEquals(receipt, admission.ownershipReceipt)
        assertEquals(receipt.totalBytes, admission.candidateBytes)
        assertEquals(receipt.totalBytes, telemetry.snapshot().getValue("lastAdmissionOwnershipBytes"))

        val rejected = admission
            .let { CoverageRendererAllocationLedger(RendererTelemetry()) }
            .admitResourceReplacement(
                VoxelRenderMode.CUBES,
                selectorStorageBytes = CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES -
                    receipt.totalBytes + receipt.mutableProjectionSelectorBytes + 1,
            )
        assertEquals(CoverageRendererTransitionStrategy.REJECT, rejected.strategy)
        assertEquals(CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES + 1, rejected.candidateBytes)
    }

    @Test
    fun `descriptor backing is charged once while in flight and releases to zero`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)

        ledger.installPersistentCoverageState(VoxelRenderMode.CUBES)
        val first = telemetry.snapshot().getValue("ownedBufferBytes")
        ledger.installPersistentCoverageState(VoxelRenderMode.CUBES)
        assertEquals(first, telemetry.snapshot().getValue("ownedBufferBytes"))

        ledger.clearCoverageState()
        assertEquals(0, telemetry.snapshot().getValue("ownedBufferBytes"))
    }

    @Test
    fun `resource-only release retains semantic receipt and removes mesh and staging`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)

        ledger.installPersistentCoverageState(VoxelRenderMode.CUBES)
        ledger.installCubeResources("coverage-cubes-epoch-1", CoverageRendererLimits.CUBE_CAPACITY)

        ledger.releaseRendererResources()

        assertEquals(7_244_036, telemetry.snapshot().getValue("ownedBufferBytes"))
        assertEquals(
            5_403_936,
            telemetry.snapshot().getValue("ownedBufferBytesByOwner").let { owners ->
                @Suppress("UNCHECKED_CAST")
                (owners as Map<String, Int>).getValue("coverage-renderer-state")
            },
        )
        @Suppress("UNCHECKED_CAST")
        val owners = telemetry.snapshot().getValue("ownedBufferBytesByOwner") as Map<String, Int>
        assertTrue("coverage-page-staging" !in owners)
        assertTrue(owners.keys.none { it.startsWith("coverage-cubes") })
    }

    @Test
    fun `repeated resource release and remount are idempotent`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)

        ledger.installPersistentCoverageState(VoxelRenderMode.CENTROIDS)
        ledger.installPointResources("coverage-centroids-epoch-1", CoverageRendererLimits.CENTROID_CAPACITY)
        ledger.releaseRendererResources()
        ledger.releaseRendererResources()
        assertEquals(7_244_036, telemetry.snapshot().getValue("ownedBufferBytes"))

        ledger.installPersistentCoverageState(VoxelRenderMode.CENTROIDS)
        assertEquals(7_309_572, telemetry.snapshot().getValue("ownedBufferBytes"))
        ledger.releaseRendererResources()
        assertEquals(7_244_036, telemetry.snapshot().getValue("ownedBufferBytes"))
    }

    @Test
    fun `clear-first creation failure retains semantic receipt after resource release`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        var admissions = 0
        val factory = CoverageRendererResourceFactory(
            admit = { _, _ ->
                admissions++
                if (admissions == 1) {
                    CoverageRendererResourceAdmission(
                        strategy = CoverageRendererTransitionStrategy.COEXIST,
                        currentBytes = 0,
                        candidateBytes = 0,
                        combinedBytes = 0,
                    )
                } else {
                    CoverageRendererResourceAdmission(
                        strategy = CoverageRendererTransitionStrategy.CLEAR_FIRST,
                        currentBytes = 1,
                        candidateBytes = 1,
                        combinedBytes = 2,
                    )
                }
            },
            onClearFirst = { ledger.releaseRendererResources() },
            onCreationFailure = { ledger.releaseRendererResources() },
        )
        ledger.installPersistentCoverageState(VoxelRenderMode.CUBES)
        factory.replaceCube(
            CoverageRendererLimits.CUBE_CAPACITY,
            "coverage-cubes",
            token = CoverageResourceToken(1L, 1L, CoveragePresentationMode.SEMANTIC_CUBES),
            create = { _, capacity, owner ->
                ledger.installCubeResources(owner, capacity)
                "mounted"
            },
            release = { value: String ->
                ledger.releaseCubeResources("coverage-cubes-epoch-1")
                check(value == "mounted")
            },
        )

        val failed = factory.replacePoint(
            VoxelRenderMode.CENTROIDS,
            CoverageRendererLimits.CENTROID_CAPACITY,
            "coverage-centroids",
            token = CoverageResourceToken(2L, 2L, CoveragePresentationMode.SEMANTIC_CENTROIDS),
            create = { _, _, _ -> error("synthetic allocation failure") },
            release = { _: String -> error("must not release failed allocation") },
        )

        assertNull(failed)
        assertEquals(7_244_036, telemetry.snapshot().getValue("ownedBufferBytes"))
    }

    @Test
    fun `terminal clear after resource loss removes retained descriptor and mapping`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        ledger.installPersistentCoverageState(VoxelRenderMode.CUBES)
        ledger.installCubeResources("coverage-cubes-epoch-1", CoverageRendererLimits.CUBE_CAPACITY)

        ledger.releaseRendererResources()
        assertEquals(7_244_036, telemetry.snapshot().getValue("ownedBufferBytes"))
        ledger.clearCoverageState()
        ledger.clearCoverageState()
        assertEquals(0, telemetry.snapshot().getValue("ownedBufferBytes"))
    }

    @Test
    fun `lazy ledger charges selector and handoff only after an actual snapshot exists`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        ledger.installPersistentCoverageState(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            snapshot = null,
        )
        ledger.updateSnapshotHandoff(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            snapshot = null,
        )
        assertEquals(0, telemetry.snapshot().getValue("ownedBufferBytes"))

        val snapshot = CoveragePointRenderSnapshot(
            revision = 1L,
            enabled = true,
            capacity = 1,
            count = 1,
            keys = longArrayOf(1L),
            positions = FloatArray(3),
            colors = IntArray(1),
        )
        val selector = CoveragePresentationSelector(CoverageRendererLimits.CENTROID_CAPACITY)
        selector.select(snapshot)
        ledger.installPersistentCoverageState(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            snapshot,
            selectorStorageBytes = selector.ownedStorageBytes,
        )
        ledger.updateSnapshotHandoff(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            snapshot,
        )
        assertEquals(
            CoverageRendererLimits.ownershipReceipt(
                VoxelRenderMode.CENTROIDS,
                selectorStorageBytes = selector.ownedStorageBytes,
            ).let { receipt ->
                receipt.canonicalStateBytes +
                    receipt.mutableProjectionSelectorBytes +
                    receipt.descriptorBackingBytes +
                    receipt.pageReaderCapturedMappingBytes +
                    receipt.stagingBytes
            } +
                CoverageRendererLimits.snapshotHandoffBytes(
                    com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
                ),
            telemetry.snapshot().getValue("ownedBufferBytes"),
        )
    }

    @Test
    fun `production maximum cube resources reserve every owner and reject exact limit plus one`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        ledger.installPersistentCoverageState(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES)
        ledger.updateSnapshotHandoff(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES)
        ledger.installCubeResources("cubes", CoverageRendererLimits.CUBE_CAPACITY)
        assertEquals(
            CoverageRendererLimits.maximumActiveRendererBytes,
            telemetry.snapshot().getValue("ownedBufferBytes"),
        )

        telemetry.setOwnedBufferBytes(
            "exact-transition-reservation",
            CoverageRendererLimits.INSTANTANEOUS_TRANSITION_LIMIT_BYTES -
                CoverageRendererLimits.maximumActiveRendererBytes,
        )
        assertThrows(IllegalStateException::class.java) {
            telemetry.setOwnedBufferBytes("transition-limit-plus-one", 1)
        }
    }

    @Test
    fun `replacement fence releases actual cube startup owners before installing centroid startup`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        ledger.installPersistentCoverageState(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES,
        )
        ledger.updateSnapshotHandoff(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES)
        ledger.installCubeResources("active", CoverageRendererLimits.CUBE_CAPACITY)
        ledger.releaseCubeResources("active")
        ledger.installPersistentCoverageState(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
        )
        ledger.updateSnapshotHandoff(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS)
        ledger.installPointResources("active", CoverageRendererLimits.CENTROID_CAPACITY)

        assertEquals(
            CoverageRendererLimits.activeRendererPeakBytes(
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            ),
            telemetry.snapshot().getValue("ownedBufferBytes"),
        )
        assertEquals(
            CoverageRendererLimits.maximumActiveRendererBytes,
            telemetry.snapshot().getValue("peakOwnedBufferBytes"),
        )
    }

    @Test
    fun `point startup index staging is retained only until its production completion owner is released`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        ledger.installPersistentCoverageState(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
        )
        ledger.updateSnapshotHandoff(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS)
        ledger.installPointResources("centroids", CoverageRendererLimits.CENTROID_CAPACITY)

        assertEquals(
            CoverageRendererLimits.activeRendererPeakBytes(
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            ),
            telemetry.snapshot().getValue("ownedBufferBytes"),
        )

        ledger.completePointStartup("centroids")

        assertEquals(
            CoverageRendererLimits.activeRendererPeakBytes(
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            ) -
                CoverageRendererLimits.CENTROID_CAPACITY *
                    CoveragePointMeshResources.STARTUP_INDEX_STAGING_BYTES_PER_ROW,
            telemetry.snapshot().getValue("ownedBufferBytes"),
        )
    }

    @Test
    fun `over-cap cube presentation selects stable identities and forces a reset`() {
        val snapshot = CoveragePointRenderSnapshot(
            revision = 7,
            enabled = true,
            capacity = 20_000,
            count = 3,
            keys = longArrayOf(30, 10, 20),
            positions = floatArrayOf(30f, 0f, 0f, 10f, 0f, 0f, 20f, 0f, 0f),
            colors = intArrayOf(30, 10, 20),
        )

        val bounded = snapshot.boundedForPresentation(2)

        assertEquals(2, bounded.capacity)
        assertEquals(2, bounded.count)
        assertArrayEquals(longArrayOf(10, 20), bounded.keys)
        assertArrayEquals(floatArrayOf(10f, 0f, 0f, 20f, 0f, 0f), bounded.positions, 0f)
        val update = checkNotNull(bounded.update)
        assertTrue(update.reset)
        assertEquals(2, update.spans.single().rowCount)
        assertTrue(update.spans.single().positions.isEmpty())
    }

    @Test
    fun `ordinary retained-row change preserves selected identities and emits one dirty span`() {
        val selector = CoveragePresentationSelector(2)
        selector.select(
            CoveragePointRenderSnapshot(
                revision = 1,
                enabled = true,
                capacity = 4,
                count = 4,
                keys = longArrayOf(30, 10, 20, 40),
                positions = FloatArray(12),
                colors = IntArray(4),
            ),
        )

        val bounded = selector.select(
            CoveragePointRenderSnapshot(
                revision = 2,
                enabled = true,
                capacity = 4,
                count = 4,
                keys = longArrayOf(30, 10, 20, 40),
                positions = floatArrayOf(0f, 0f, 0f, 11f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                colors = IntArray(4),
                update = com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate(
                    geometryRevision = 2,
                    visibilityRevision = 0,
                    enabled = true,
                    count = 4,
                    spans = listOf(
                        com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan(
                            startSlot = 1,
                            positions = floatArrayOf(11f, 0f, 0f),
                            colors = intArrayOf(0),
                        ),
                    ),
                    reset = false,
                ),
            ),
        )

        assertArrayEquals(longArrayOf(10, 20), bounded.keys)
        assertEquals(false, checkNotNull(bounded.update).reset)
        assertEquals(0, bounded.update.spans.single().startSlot)
        assertEquals(1, bounded.update.spans.single().rowCount)
        assertTrue(bounded.update.spans.single().positions.isEmpty())
    }

    @Test
    fun `presentation selection preserves style rows with their stable identities`() {
        val uncovered = CoverageRendererStyleRowV1().encode()
        val complete = CoverageRendererStyleRowV1(
            semanticGeneration = 3,
            styleGeneration = 4,
            coverage = CoverageRendererCoverage.COMPLETE,
        ).encode()
        val snapshot = CoveragePointRenderSnapshot(
            revision = 7,
            enabled = true,
            capacity = 3,
            count = 3,
            keys = longArrayOf(30, 10, 20),
            positions = FloatArray(9),
            colors = intArrayOf(30, 10, 20),
            styleRows = complete + complete + uncovered,
        )

        val bounded = snapshot.boundedForPresentation(2)

        assertArrayEquals(longArrayOf(20, 10), bounded.keys)
        assertEquals(
            CoverageRendererCoverage.UNCOVERED,
            CoverageRendererStyleRowV1.decode(bounded.styleRows).coverage,
        )
        assertEquals(
            CoverageRendererCoverage.COMPLETE,
            CoverageRendererStyleRowV1.decode(bounded.styleRows, 16).coverage,
        )
        assertTrue(bounded.update!!.spans.single().styleRows.isEmpty())
    }

    @Test
    fun `new lower identity replaces one full selector slot without moving retained slots`() {
        val selector = CoveragePresentationSelector(3)
        selector.select(
            CoveragePointRenderSnapshot(
                revision = 1,
                enabled = true,
                capacity = 4,
                count = 3,
                keys = longArrayOf(30, 20, 40),
                positions = FloatArray(9),
                colors = IntArray(3),
            ),
        )

        val bounded = selector.select(
            CoveragePointRenderSnapshot(
                revision = 2,
                enabled = true,
                capacity = 4,
                count = 4,
                keys = longArrayOf(30, 20, 40, 10),
                positions = FloatArray(12),
                colors = IntArray(4),
                update = com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate(
                    geometryRevision = 2,
                    visibilityRevision = 0,
                    enabled = true,
                    count = 4,
                    spans = listOf(
                        com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan(
                            startSlot = 3,
                            positions = floatArrayOf(0f, 0f, 0f),
                            colors = intArrayOf(0),
                        ),
                    ),
                    reset = false,
                ),
            ),
        )

        // 20 and 30 retain their presentation destinations. The largest
        // selected key (40) is evicted and its slot is deterministically
        // reused by the newly admitted lower key (10).
        assertArrayEquals(longArrayOf(20, 30, 10), bounded.keys)
        val update = checkNotNull(bounded.update)
        assertEquals(false, update.reset)
        assertEquals(1, update.spans.size)
        assertEquals(2, update.spans.single().startSlot)
        assertEquals(1, update.spans.single().rowCount)
    }

    @Test
    fun `retained selector shrinks safely and clears removed source mappings`() {
        val selector = CoveragePresentationSelector(3)
        selector.select(
            CoveragePointRenderSnapshot(
                revision = 1,
                enabled = true,
                capacity = 3,
                count = 3,
                keys = longArrayOf(10, 20, 30),
                positions = FloatArray(9),
                colors = IntArray(3),
            ),
        )

        val bounded = selector.select(
            CoveragePointRenderSnapshot(
                revision = 2,
                enabled = true,
                capacity = 2,
                count = 2,
                keys = longArrayOf(30, 10),
                positions = FloatArray(6),
                colors = IntArray(2),
            ),
        )

        assertEquals(2, bounded.count)
        assertArrayEquals(longArrayOf(10, 30), bounded.keys)
        assertTrue(checkNotNull(bounded.update).reset)
        assertEquals(2, bounded.update.spans.single().rowCount)
    }

    @Test
    fun `retained selector recomputes when an unselected row becomes higher priority`() {
        val selector = CoveragePresentationSelector(2)
        selector.select(
            CoveragePointRenderSnapshot(
                revision = 1,
                enabled = true,
                capacity = 3,
                count = 3,
                keys = longArrayOf(10, 20, 30),
                positions = FloatArray(9),
                colors = IntArray(3),
                styleRows = CoverageRendererStyleRowV1().encode().let { it + it + it },
            ),
        )
        val promoted = CoverageRendererStyleRowV1(
            target = com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererTarget.PRIMARY,
            directionBin = 1,
            glyph = com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererGlyph.DESIRED_DIRECTION,
        ).encode()
        val styles = CoverageRendererStyleRowV1().encode().let { it + it + it }
        promoted.copyInto(styles, 16 * 2)

        val bounded = selector.select(
            CoveragePointRenderSnapshot(
                revision = 2,
                enabled = true,
                capacity = 3,
                count = 3,
                keys = longArrayOf(10, 20, 30),
                positions = FloatArray(9),
                colors = IntArray(3),
                styleRows = styles,
                update = com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate(
                    geometryRevision = 2,
                    visibilityRevision = 2,
                    enabled = true,
                    count = 3,
                    spans = listOf(
                        com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan(
                            startSlot = 2,
                            positions = FloatArray(3),
                            colors = intArrayOf(0),
                            styleRows = promoted,
                        ),
                    ),
                    reset = false,
                ),
            ),
        )

        assertArrayEquals(longArrayOf(30, 10), bounded.keys)
        assertTrue(checkNotNull(bounded.update).reset)
    }
}
