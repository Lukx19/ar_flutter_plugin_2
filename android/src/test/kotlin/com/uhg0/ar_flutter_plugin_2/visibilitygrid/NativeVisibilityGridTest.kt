package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVisibilityGridTest {
    @Test
    fun `bounded render selection is ordered without copying the semantic population`() {
        val grid = NativeVisibilityGrid(VisibilityGridFeatureConfig(stableVoxelCapacity = 100_000))
        val first = packVisibilityGridKey(1, 0, 0)
        val second = packVisibilityGridKey(2, 0, 0)
        val third = packVisibilityGridKey(3, 0, 0)
        grid.startGroup(
            group().copy(
                capacity = 100_000,
                restoredGeometryRevision = 1,
                restoredKeys = longArrayOf(third, first, second),
            ),
        )

        assertArrayEquals(longArrayOf(first, second), grid.selectedRenderKeys(2))
        assertEquals(3, grid.snapshot().stableKeys.size)
    }

    @Test
    fun `stable same-id jitter contributes exactly one voxel`() {
        val grid = newGrid()
        grid.startGroup(group())

        val samples =
            listOf(
                feature(0, 0.020, 0.020, 0.020),
                feature(125_000_000, 0.021, 0.020, 0.020),
                feature(250_000_000, 0.019, 0.020, 0.020),
                feature(375_000_000, 0.020, 0.021, 0.020),
                feature(500_000_000, 0.020, 0.019, 0.020),
                feature(625_000_000, 0.022, 0.020, 0.020),
            )

        samples.forEach(grid::observe)

        val snapshot = grid.snapshot()
        assertEquals(listOf(packVisibilityGridKey(0, 0, 0)), snapshot.stableKeys)
        assertEquals(1, snapshot.diagnostics.stableTracks)
        assertEquals(0, snapshot.diagnostics.candidateTracks)
        assertEquals(1, snapshot.supportByKey.getValue(packVisibilityGridKey(0, 0, 0)))
        assertTrue(snapshot.geometryRevision > 0)
    }

    @Test
    fun `world observations are transformed into the group-local frame before quantization`() {
        val grid =
            NativeVisibilityGrid(
                VisibilityGridFeatureConfig(
                    stableVoxelCapacity = 10,
                    featureTrackCapacity = 10,
                    candidateSamples = 1,
                    candidateSpanNs = 0,
                ),
            )
        val worldFromGroup =
            doubleArrayOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                1.0, 2.0, 3.0, 1.0,
            )
        val groupFromWorld =
            doubleArrayOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                -1.0, -2.0, -3.0, 1.0,
            )
        grid.startGroup(
            group().copy(
                capacity = 10,
                groupFromWorldGl = groupFromWorld,
                worldFromGroupGl = worldFromGroup,
            ),
        )
        grid.observe(feature(0, 1.05, 2.05, 3.05))

        assertEquals(listOf(packVisibilityGridKey(0, 0, 0)), grid.snapshot().stableKeys)
        assertFails {
            group().copy(
                capacity = 10,
                groupFromWorldGl = groupFromWorld,
            )
        }
    }

    @Test
    fun `same id relocates atomically and a large jump returns it to candidate`() {
        val grid = newGrid()
        grid.startGroup(group())
        promotionSamples().forEach(grid::observe)

        grid.observe(feature(625_000_000, 0.160, 0.020, 0.020))
        grid.observe(feature(750_000_000, 0.170, 0.020, 0.020))

        val relocated = grid.snapshot()
        assertEquals(listOf(packVisibilityGridKey(1, 0, 0)), relocated.stableKeys)
        assertEquals(1, relocated.diagnostics.stableTracks)

        grid.observe(feature(875_000_000, 2.170, 0.020, 0.020))

        val reset = grid.snapshot()
        assertTrue(reset.stableKeys.isEmpty())
        assertEquals(0, reset.diagnostics.stableTracks)
        assertEquals(1, reset.diagnostics.candidateTracks)
        assertEquals(1, reset.diagnostics.featureMigrations)
        assertEquals(1, reset.diagnostics.featureJumpResets)
        assertEquals(2, reset.diagnostics.supportRemovals)

        listOf(1_000_000_000L, 1_125_000_000L, 1_250_000_000L, 1_375_000_000L)
            .forEach { timestamp ->
                grid.observe(feature(timestamp, 2.170, 0.020, 0.020))
            }
        assertEquals(
            listOf(packVisibilityGridKey(21, 0, 0)),
            grid.snapshot().stableKeys,
        )
        assertEquals(12, grid.snapshot().diagnostics.featureObservationCount)
    }

    @Test
    fun `moving one id retains a shared voxel while support remains`() {
        val grid = newGrid()
        grid.startGroup(group())
        val positions =
            listOf(
                Triple(0L, 0.020, 0.020),
                Triple(125_000_000L, 0.021, 0.020),
                Triple(250_000_000L, 0.019, 0.020),
                Triple(375_000_000L, 0.020, 0.021),
                Triple(500_000_000L, 0.020, 0.019),
            )
        positions.forEach { (timestamp, x, y) ->
            grid.observe(
                FeatureObservation(
                    timestampNs = timestamp,
                    groupGeneration = 1,
                    sessionGeneration = 1,
                    samples =
                        listOf(
                            FeatureSample(42, x, y, 0.020, 0.9),
                            FeatureSample(84, x, y, 0.020, 0.9),
                        ),
                ),
            )
        }
        assertEquals(2, grid.snapshot().supportByKey.getValue(packVisibilityGridKey(0, 0, 0)))

        grid.observe(feature(625_000_000, 0.160, 0.020, 0.020))
        grid.observe(feature(750_000_000, 0.170, 0.020, 0.020))

        val snapshot = grid.snapshot()
        assertEquals(
            mapOf(
                packVisibilityGridKey(0, 0, 0) to 1,
                packVisibilityGridKey(1, 0, 0) to 1,
            ),
            snapshot.supportByKey,
        )
    }

    @Test
    fun `geometry publication coalesces final state and snapshot bridges revision gaps`() {
        val grid =
            NativeVisibilityGrid(
                VisibilityGridFeatureConfig(
                    stableVoxelCapacity = 10,
                    featureTrackCapacity = 10,
                    candidateSamples = 1,
                    candidateSpanNs = 0,
                    relocationHysteresisMeters = 0.0,
                    jumpResetMeters = 100.0,
                ),
            )
        grid.startGroup(group().copy(capacity = 10))
        grid.observe(feature(0, 0.020, 0.020, 0.020))

        val first = requireNotNull(grid.takeGeometryDelta(nowNs = 0))
        assertEquals(0, first.baseGeometryRevision)
        assertEquals(1, first.geometryRevision)
        assertEquals(listOf(packVisibilityGridKey(0, 0, 0)), first.upsertKeys)
        assertTrue(first.removalKeys.isEmpty())
        // Worker-pull reads this retained value; publication cannot advance
        // semantic state until that exact revision is acknowledged.
        assertEquals(first, grid.inFlightGeometryDelta())
        assertEquals(first, grid.takeGeometryDelta(nowNs = 0))
        assertTrue(!grid.ackGeometry(ack(1, groupId = "wrong-group")))
        assertEquals(first, grid.takeGeometryDelta(nowNs = 0))
        assertTrue(grid.ackGeometry(ack(1)))
        assertEquals(null, grid.inFlightGeometryDelta())

        grid.observe(feature(1, 0.200, 0.020, 0.020))
        grid.observe(feature(2, 0.020, 0.020, 0.020))

        assertEquals(null, grid.takeGeometryDelta(nowNs = 499_999_999))
        val coalesced = requireNotNull(grid.takeGeometryDelta(nowNs = 500_000_000))
        assertEquals(1, coalesced.baseGeometryRevision)
        assertEquals(2, coalesced.geometryRevision)
        assertEquals(listOf(packVisibilityGridKey(0, 0, 0)), coalesced.upsertKeys)
        assertEquals(listOf(packVisibilityGridKey(1, 0, 0)), coalesced.removalKeys)
        assertTrue(coalesced.diagnostics.coalescedGeometryChanges > 0)
        assertEquals(2, coalesced.diagnostics.publishedDeltaCount)
        assertEquals(1, coalesced.diagnostics.unacknowledgedGeometryCallbacks)
        assertEquals(coalesced.geometryRevision, coalesced.diagnostics.geometryRevision)
        assertEquals(coalesced.upsertKeys.size, coalesced.diagnostics.rendererRows)
        val wire = coalesced.toWireMap()
        assertTrue("featureIds" !in wire)
        assertTrue("points" !in wire)
        assertTrue("positions" !in wire)

        assertEquals(
            null,
            grid.requestSnapshot(
                request = snapshotRequest(0, groupId = "stale-group"),
                nowNs = 600_000_000,
            ),
        )
        val snapshot =
            requireNotNull(
                grid.requestSnapshot(
                    request = snapshotRequest(0),
                    nowNs = 600_000_000,
                ),
            )
        assertTrue(snapshot.reset)
        assertEquals(0, snapshot.baseGeometryRevision)
        assertTrue(snapshot.geometryRevision > coalesced.geometryRevision)
        assertEquals(listOf(packVisibilityGridKey(0, 0, 0)), snapshot.upsertKeys)
        assertEquals(1, snapshot.diagnostics.snapshotRecoveryCount)
        assertTrue(grid.ackGeometry(ack(snapshot.geometryRevision)))
        assertEquals(2, grid.snapshot().diagnostics.geometryAcknowledgementCount)
    }

    @Test
    fun `unpolled relocation history falls back to one bounded reset snapshot`() {
        val grid =
            NativeVisibilityGrid(
                VisibilityGridFeatureConfig(
                    stableVoxelCapacity = 10,
                    featureTrackCapacity = 10,
                    candidateSamples = 1,
                    candidateSpanNs = 0,
                    relocationHysteresisMeters = 0.0,
                    jumpResetMeters = 1_000.0,
                ),
            )
        grid.startGroup(group().copy(capacity = 2))
        grid.observe(feature(0, 0.02, 0.02, 0.02))
        grid.observe(feature(1, 0.42, 0.02, 0.02))
        grid.observe(feature(2, 0.82, 0.02, 0.02))

        val delta = requireNotNull(grid.takeGeometryDelta(nowNs = 0))
        assertTrue(delta.reset)
        assertEquals(grid.snapshot().stableKeys, delta.upsertKeys)
        assertTrue(delta.removalKeys.isEmpty())
    }

    @Test
    fun `ingestion bounds and sanitizes duplicates confidence values and timestamps`() {
        val grid =
            NativeVisibilityGrid(
                VisibilityGridFeatureConfig(
                    stableVoxelCapacity = 10,
                    featureTrackCapacity = 10,
                    maxFeaturesPerObservation = 2,
                    minimumConfidence = 0.5,
                    candidateSamples = 1,
                    candidateSpanNs = 0,
                ),
            )
        grid.startGroup(group().copy(capacity = 10))
        grid.observe(
            FeatureObservation(
                timestampNs = 10,
                groupGeneration = 1,
                sessionGeneration = 1,
                samples =
                    listOf(
                        FeatureSample(1, 0.02, 0.02, 0.02, 0.8),
                        FeatureSample(1, 0.22, 0.02, 0.02, 0.9),
                        FeatureSample(2, Double.NaN, 0.02, 0.02, 0.9),
                        FeatureSample(3, 0.32, 0.02, 0.02, 0.1),
                        FeatureSample(4, 0.42, 0.02, 0.02, 0.9),
                        FeatureSample(5, 0.52, 0.02, 0.02, 0.9),
                    ),
            ),
        )
        grid.observe(feature(timestampNs = 10, x = 0.0, y = 0.0, z = 0.0))
        grid.observe(feature(timestampNs = 9, x = 0.0, y = 0.0, z = 0.0))
        grid.observe(
            FeatureObservation(
                timestampNs = 11,
                groupGeneration = 1,
                sessionGeneration = 1,
                samples =
                    listOf(
                        FeatureSample(6, Double.MAX_VALUE, 0.0, 0.0, 0.9),
                    ),
            ),
        )

        val snapshot = grid.snapshot()
        assertEquals(
            listOf(
                packVisibilityGridKey(2, 0, 0),
                packVisibilityGridKey(4, 0, 0),
            ),
            snapshot.stableKeys,
        )
        assertEquals(2, snapshot.diagnostics.acceptedSamples)
        assertEquals(7, snapshot.diagnostics.rejectedSamples)
    }

    @Test
    fun `candidate expiry preserves off-screen stable cells and group reset rejects stale work`() {
        val grid =
            NativeVisibilityGrid(
                VisibilityGridFeatureConfig(
                    stableVoxelCapacity = 10,
                    featureTrackCapacity = 10,
                    candidateSamples = 2,
                    candidateSpanNs = 0,
                    candidateExpiryNs = 100,
                ),
            )
        grid.startGroup(group().copy(capacity = 10))
        grid.observe(
            FeatureObservation(
                timestampNs = 0,
                groupGeneration = 1,
                sessionGeneration = 1,
                samples =
                    listOf(
                        FeatureSample(1, 0.02, 0.02, 0.02, 0.9),
                        FeatureSample(2, 0.22, 0.02, 0.02, 0.9),
                    ),
            ),
        )
        grid.observe(feature(50, 0.22, 0.02, 0.02, id = 2))
        grid.observe(feature(200, 0.42, 0.02, 0.02, id = 3))

        val expired = grid.snapshot()
        assertEquals(listOf(packVisibilityGridKey(2, 0, 0)), expired.stableKeys)
        assertEquals(1, expired.diagnostics.stableTracks)
        assertEquals(1, expired.diagnostics.candidateTracks)
        assertEquals(1, expired.diagnostics.candidateExpirations)

        assertFails { grid.startGroup(group().copy(capacity = 10)) }
        grid.startGroup(
            group().copy(groupId = "group-2", groupGeneration = 2, capacity = 10),
        )
        assertTrue(grid.snapshot().stableKeys.isEmpty())
        assertFails {
            grid.observe(feature(300, 0.0, 0.0, 0.0))
        }
    }

    @Test
    fun `capacity rejects new candidates without blocking relocation or retraction`() {
        val grid =
            NativeVisibilityGrid(
                VisibilityGridFeatureConfig(
                    stableVoxelCapacity = 1,
                    featureTrackCapacity = 1,
                    candidateSamples = 1,
                    candidateSpanNs = 0,
                    relocationHysteresisMeters = 0.0,
                    jumpResetMeters = 1.0,
                ),
            )
        grid.startGroup(group().copy(capacity = 1))
        grid.observe(feature(0, 0.02, 0.02, 0.02, id = 1))
        grid.observe(feature(1, 0.42, 0.02, 0.02, id = 2))
        grid.observe(feature(2, 0.22, 0.02, 0.02, id = 1))

        val relocated = grid.snapshot()
        assertEquals(listOf(packVisibilityGridKey(1, 0, 0)), relocated.stableKeys)
        assertEquals(1, relocated.diagnostics.capacityRejectedCandidates)

        grid.observe(feature(3, 2.22, 0.02, 0.02, id = 1))
        val retracted = grid.snapshot()
        assertTrue(retracted.stableKeys.isEmpty())
        assertEquals(1, retracted.diagnostics.candidateTracks)
    }

    @Test
    fun `shared support can split at capacity without exceeding the voxel bound`() {
        val grid =
            NativeVisibilityGrid(
                VisibilityGridFeatureConfig(
                    stableVoxelCapacity = 2,
                    featureTrackCapacity = 2,
                    candidateSamples = 1,
                    candidateSpanNs = 0,
                    relocationHysteresisMeters = 0.0,
                    jumpResetMeters = 1.0,
                ),
            )
        grid.startGroup(group().copy(capacity = 2))
        grid.observe(
            FeatureObservation(
                timestampNs = 0,
                groupGeneration = 1,
                sessionGeneration = 1,
                samples =
                    listOf(
                        FeatureSample(1, 0.02, 0.02, 0.02, 0.9),
                        FeatureSample(2, 0.02, 0.02, 0.02, 0.9),
                    ),
            ),
        )
        grid.observe(feature(1, 0.22, 0.02, 0.02, id = 1))

        val snapshot = grid.snapshot()
        assertEquals(2, snapshot.diagnostics.stableVoxels)
        assertEquals(
            mapOf(
                packVisibilityGridKey(0, 0, 0) to 1,
                packVisibilityGridKey(1, 0, 0) to 1,
            ),
            snapshot.supportByKey,
        )
    }

    @Test
    fun `synthetic and ARCore adapters drive the same observation seam`() {
        val config =
            VisibilityGridFeatureConfig(
                stableVoxelCapacity = 10,
                featureTrackCapacity = 10,
                candidateSamples = 1,
                candidateSpanNs = 0,
            )
        val syntheticGrid = NativeVisibilityGrid(config)
        val arCoreGrid = NativeVisibilityGrid(config)
        syntheticGrid.startGroup(group().copy(capacity = 10))
        arCoreGrid.startGroup(group().copy(capacity = 10))
        val synthetic =
            SyntheticFeatureObservationSource(
                listOf(feature(0, 0.02, 0.02, 0.02, id = 7)),
            )
        var sample: PointCloudSample? =
            PointCloudSample(
                sequence = 0,
                timestampNs = 0,
                ids = intArrayOf(7),
                points = floatArrayOf(0.02f, 0.02f, 0.02f, 0.9f),
            )
        val arCore =
            ArCoreFeatureObservationSource(
                acquire = {
                    sample.also { sample = null }
                },
                groupGeneration = { 1 },
                sessionGeneration = { 1 },
            )

        assertTrue(syntheticGrid.consumeNext(synthetic))
        assertTrue(arCoreGrid.consumeNext(arCore))
        assertEquals(syntheticGrid.snapshot().stableKeys, arCoreGrid.snapshot().stableKeys)
        assertEquals(syntheticGrid.snapshot().supportByKey, arCoreGrid.snapshot().supportByKey)
        assertTrue(!syntheticGrid.consumeNext(synthetic))
        assertTrue(!arCoreGrid.consumeNext(arCore))
        assertEquals(
            "transientUnavailable",
            syntheticGrid.snapshot().diagnostics.featureHealth,
        )
        val failingSource =
            FeatureObservationSource {
                throw IllegalStateException("synthetic acquisition failure")
            }
        assertTrue(!syntheticGrid.consumeNext(failingSource))
        assertEquals("failed", syntheticGrid.snapshot().diagnostics.featureHealth)
        assertEquals(1, syntheticGrid.snapshot().diagnostics.featureFailureCount)

        val hostileGrid =
            NativeVisibilityGrid(
                config.copy(
                    minimumConfidence = 0.5,
                    maxFeaturesPerObservation = 2,
                ),
            )
        hostileGrid.startGroup(group().copy(capacity = 10))
        var hostileSample: PointCloudSample? =
            PointCloudSample(
                sequence = 0,
                timestampNs = 0,
                ids = intArrayOf(1, 1, 2, 3),
                points =
                    floatArrayOf(
                        0.02f, 0.02f, 0.02f, 0.4f,
                        0.22f, 0.02f, 0.02f, 0.9f,
                        Float.NaN, 0.02f, 0.02f, 0.9f,
                        0.42f, 0.02f, 0.02f, 0.8f,
                    ),
            )
        val hostileArCore =
            ArCoreFeatureObservationSource(
                acquire = { hostileSample.also { hostileSample = null } },
                groupGeneration = { 1 },
                sessionGeneration = { 1 },
                minimumConfidence = 0.5,
                maximumSamples = 2,
            )
        assertTrue(hostileGrid.consumeNext(hostileArCore))
        assertEquals(
            listOf(
                packVisibilityGridKey(2, 0, 0),
                packVisibilityGridKey(4, 0, 0),
            ),
            hostileGrid.snapshot().stableKeys,
        )
        assertEquals(2, hostileGrid.snapshot().diagnostics.rejectedSamples)
    }

    @Test
    fun `twenty thousand stable voxels meet feature latency and memory budgets`() {
        val pointCount = 20_000
        val samples =
            List(pointCount) { index ->
                FeatureSample(
                    id = index,
                    xWorld = (index % 200) * 0.1 + 0.02,
                    yWorld = (index / 200) * 0.1 + 0.02,
                    zWorld = 0.02,
                    confidence = 0.9,
                )
            }
        val baselineHeapBytes = usedHeapAfterGc()
        val grid =
            NativeVisibilityGrid(
                VisibilityGridFeatureConfig(
                    stableVoxelCapacity = pointCount,
                    featureTrackCapacity = pointCount,
                    maxFeaturesPerObservation = 2_000,
                    candidateSamples = 1,
                    candidateSpanNs = 0,
                ),
            )
        grid.startGroup(group().copy(capacity = pointCount))
        samples.chunked(2_000).forEachIndexed { index, chunk ->
            grid.observe(FeatureObservation(index.toLong(), 1, 1, chunk, sanitized = true))
        }
        val benchmarkSamples = samples.take(1_000)
        repeat(3) { index ->
            grid.observe(
                FeatureObservation(
                    (index + 10).toLong(),
                    1,
                    1,
                    benchmarkSamples,
                    sanitized = true,
                ),
            )
        }
        val timings =
            List(20) { index ->
                val started = System.nanoTime()
                grid.observe(
                    FeatureObservation(
                        (index + 13).toLong(),
                        1,
                        1,
                        benchmarkSamples,
                        sanitized = true,
                    ),
                )
                System.nanoTime() - started
            }.sorted()
        val p95Index =
            kotlin.math.ceil(timings.size * 0.95).toInt().minus(1).coerceAtLeast(0)
        val p95Ns = timings[p95Index]
        val diagnostics = grid.snapshot().diagnostics
        val retainedHeapBytes = (usedHeapAfterGc() - baselineHeapBytes).coerceAtLeast(0)

        assertEquals(pointCount, diagnostics.stableVoxels)
        assertTrue(
            "measured retained heap $retainedHeapBytes exceeds 16 MiB",
            retainedHeapBytes <= 16L * 1024L * 1024L,
        )
        assertTrue(
            "estimated native state ${diagnostics.estimatedStateBytes} exceeds 16 MiB",
            diagnostics.estimatedStateBytes <= 16L * 1024L * 1024L,
        )
        assertTrue(
            "feature fusion p95 ${p95Ns / 1_000_000.0} ms exceeds 3 ms per 1,000; " +
                "samples=${timings.map { it / 1_000_000.0 }}",
            p95Ns <= 3_000_000,
        )
        val defaults = VisibilityGridFeatureConfig()
        assertEquals(100_000, defaults.stableVoxelCapacity)
        assertEquals(200_000, defaults.featureTrackCapacity)
        assertFails { VisibilityGridFeatureConfig(maxFeaturesPerObservation = 2_001) }
    }

    @Test
    fun `zero accepted observations do not enter normalized feature latency p95`() {
        val grid =
            NativeVisibilityGrid(
                VisibilityGridFeatureConfig(
                    stableVoxelCapacity = 10,
                    featureTrackCapacity = 10,
                    candidateSamples = 1,
                    candidateSpanNs = 0,
                ),
            )
        grid.startGroup(group().copy(capacity = 10))
        grid.observe(feature(timestampNs = 1, x = 0.02, y = 0.02, z = 0.02))
        val p95AfterAccepted = grid.snapshot().diagnostics.featureFusionP95Ns

        grid.observe(
            FeatureObservation(
                timestampNs = 2,
                groupGeneration = 1,
                sessionGeneration = 1,
                samples = emptyList(),
                sanitized = true,
            ),
        )

        assertEquals(
            p95AfterAccepted,
            grid.snapshot().diagnostics.featureFusionP95Ns,
        )
    }

    private fun newGrid() =
        NativeVisibilityGrid(
            VisibilityGridFeatureConfig(
                stableVoxelCapacity = 100,
                featureTrackCapacity = 100,
            ),
        )

    private fun group() =
        VisibilityGridGroupConfig(
            groupId = "group-1",
            groupGeneration = 1,
            sessionGeneration = 1,
            voxelSizeMeters = 0.1,
            capacity = 100,
            groupFromWorldGl = identityVisibilityGridTransform(),
        )

    private fun feature(
        timestampNs: Long,
        x: Double,
        y: Double,
        z: Double,
        id: Int = 42,
        confidence: Double = 0.9,
    ) = FeatureObservation(
        timestampNs = timestampNs,
        groupGeneration = 1,
        sessionGeneration = 1,
        samples = listOf(FeatureSample(id, x, y, z, confidence)),
    )

    private fun promotionSamples() =
        listOf(
            feature(0, 0.020, 0.020, 0.020),
            feature(125_000_000, 0.021, 0.020, 0.020),
            feature(250_000_000, 0.019, 0.020, 0.020),
            feature(375_000_000, 0.020, 0.021, 0.020),
            feature(500_000_000, 0.020, 0.019, 0.020),
        )

    private fun ack(
        revision: Long,
        groupId: String = "group-1",
    ) = VisibilityGridGeometryAck(
        groupId = groupId,
        groupGeneration = 1,
        sessionGeneration = 1,
        acceptedGeometryRevision = revision,
    )

    private fun snapshotRequest(
        receiverRevision: Long,
        groupId: String = "group-1",
    ) = VisibilityGridSnapshotRequest(
        groupId = groupId,
        groupGeneration = 1,
        sessionGeneration = 1,
        receiverGeometryRevision = receiverRevision,
    )

    private fun usedHeapAfterGc(): Long {
        repeat(2) {
            System.gc()
            System.runFinalization()
            Thread.sleep(25)
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
