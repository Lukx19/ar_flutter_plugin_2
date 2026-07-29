package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDepthFusionTest {
    @Test
    fun `shared Android depth fixture unprojects into the expected group voxel`() {
        val grid =
            NativeVisibilityGrid(
                featureConfig =
                    VisibilityGridFeatureConfig(
                        candidateSamples = 1,
                        candidateSpanNs = 0,
                    ),
                depthConfig = VisibilityGridDepthConfig(),
            )
        grid.startGroup(
            VisibilityGridGroupConfig(
                groupId = "depth-fixture",
                groupGeneration = 1,
                sessionGeneration = 1,
                voxelSizeMeters = 0.1,
                capacity = 100,
                groupFromWorldGl =
                    translationTransform(
                        x = -1.0,
                        y = -2.0,
                        z = -3.0,
                    ),
                worldFromGroupGl =
                    translationTransform(
                        x = 1.0,
                        y = 2.0,
                        z = 3.0,
                    ),
            ),
        )

        repeat(4) { index ->
            val result =
                grid.observeDepth(
                    syntheticDepthObservation(
                        timestampNs = 1_000_000_000L + index,
                        cameraFromWorldTranslation = doubleArrayOf(1.0, 2.0, 3.0),
                        samples =
                            listOf(
                                SyntheticDepthPixel(
                                    x = 2,
                                    y = 1,
                                    depthMillimeters = 1_000,
                                    confidence = 200,
                                ),
                            ),
                    ),
                )
            assertEquals(1, result.acceptedPixels)
        }

        assertTrue(packVisibilityGridKey(2, 0, -10) in grid.snapshot().stableKeys)
    }

    @Test
    fun `free evidence is multi-view and stops before the surface safety band`() {
        val staleKey = packVisibilityGridKey(0, 0, -5)
        val safetyBandKey = packVisibilityGridKey(0, 0, -9)
        val grid = depthGrid(restoredKeys = longArrayOf(staleKey, safetyBandKey))

        repeat(4) { index ->
            grid.observeDepth(
                singleRay(
                    timestampNs = 10L + index,
                    cameraX = 0.05,
                    endpointX = 0.05,
                ),
            )
        }
        assertTrue(staleKey in grid.snapshot().stableKeys)

        repeat(4) { index ->
            grid.observeDepth(
                singleRay(
                    timestampNs = 20L + index,
                    cameraX = 0.55,
                    endpointX = -0.45,
                ),
            )
        }

        assertFalse(staleKey in grid.snapshot().stableKeys)
        assertTrue(safetyBandKey in grid.snapshot().stableKeys)
    }

    @Test
    fun `synthetic corridor removes a five-voxel phantom band`() {
        val phantomCoordinates = (-6..-2).map { z -> intArrayOf(0, 0, z) }
        val phantomKeys =
            phantomCoordinates.map { coordinates ->
                packVisibilityGridKey(
                    coordinates[0],
                    coordinates[1],
                    coordinates[2],
                )
            }
        val grid = depthGrid(restoredKeys = phantomKeys.toLongArray())

        repeat(4) { index ->
            grid.observeDepth(singleRay(10L + index, 0.05, 0.05))
        }
        phantomCoordinates.forEachIndexed { coordinateIndex, coordinates ->
            val targetX = 0.05
            val targetZ = (coordinates[2] + 0.5) * 0.1
            val fractionAlongRay = (0.05 - targetZ) / 1.0
            val cameraX = 0.55
            val endpointX =
                cameraX + (targetX - cameraX) / fractionAlongRay
            repeat(4) { observationIndex ->
                grid.observeDepth(
                    singleRay(
                        timestampNs =
                            100L + coordinateIndex * 10 + observationIndex,
                        cameraX = cameraX,
                        endpointX = endpointX,
                    ),
                )
            }
        }

        val remaining =
            phantomCoordinates.filter { coordinates ->
                packVisibilityGridKey(
                    coordinates[0],
                    coordinates[1],
                    coordinates[2],
                ) in grid.snapshot().stableKeys
            }
        assertTrue(
            "remaining phantom band was ${remaining.size} voxels: " +
                remaining.joinToString { it.contentToString() },
            remaining.size <= 2,
        )
    }

    @Test
    fun `synthetic thin and double walls survive conservative carving`() {
        val thinFront = packVisibilityGridKey(0, 0, -9)
        val doubleBack = packVisibilityGridKey(0, 0, -12)
        val grid =
            depthGrid(restoredKeys = longArrayOf(thinFront, doubleBack))

        repeat(4) { index ->
            grid.observeDepth(singleRay(10L + index, 0.05, 0.05))
        }
        repeat(4) { index ->
            grid.observeDepth(singleRay(20L + index, 0.55, -0.45))
        }

        val stable = grid.snapshot().stableKeys
        assertTrue(thinFront in stable)
        assertTrue(doubleBack in stable)
    }

    @Test
    fun `many rays from one observation count as only one view per voxel`() {
        val staleKey = packVisibilityGridKey(0, 0, -5)
        val grid = depthGrid(restoredKeys = longArrayOf(staleKey))
        val oneView =
            singleRay(timestampNs = 1, cameraX = 0.05, endpointX = 0.05).let {
                it.copy(samples = List(32) { _ -> it.samples.single() })
            }

        grid.observeDepth(oneView)

        assertTrue(staleKey in grid.snapshot().stableKeys)
    }

    @Test
    fun `occupied evidence restores a carved cell without an immediate loop`() {
        val staleKey = packVisibilityGridKey(0, 0, -5)
        val grid = depthGrid(restoredKeys = longArrayOf(staleKey))
        repeat(4) { index ->
            grid.observeDepth(singleRay(10L + index, 0.05, 0.05))
        }
        repeat(4) { index ->
            grid.observeDepth(singleRay(20L + index, 0.55, -0.45))
        }
        assertFalse(staleKey in grid.snapshot().stableKeys)

        repeat(4) { index ->
            grid.observeDepth(
                singleRay(
                    timestampNs = 30L + index,
                    cameraX = 0.05,
                    endpointX = 0.05,
                    depthMillimeters = 500,
                ),
            )
        }
        assertTrue(staleKey in grid.snapshot().stableKeys)

        grid.observeDepth(singleRay(40L, 0.05, 0.05))
        assertTrue(staleKey in grid.snapshot().stableKeys)
    }

    @Test
    fun `invalid duplicate non-tracking and over-budget observations are bounded`() {
        val grid =
            depthGrid(
                depthConfig =
                    VisibilityGridDepthConfig(
                        maxAcceptedPixelsPerObservation = 2,
                        maxRayVisitsPerObservation = 3,
                    ),
            )
        val invalid =
            DepthObservation(
                timestampNs = 1,
                groupGeneration = 1,
                sessionGeneration = 1,
                tracking = true,
                width = 4,
                height = 1,
                samples =
                    listOf(
                        DepthPixelSample(0, 0, 0, 255),
                        DepthPixelSample(1, 0, 1_000, 127),
                        DepthPixelSample(2, 0, 1_000, 255),
                        DepthPixelSample(3, 0, 1_000, 255),
                    ),
                intrinsics = DepthIntrinsics(2.0, 2.0, 1.5, 0.0),
                worldFromCameraGl = identityVisibilityGridTransform(),
            )

        val first = grid.observeDepth(invalid)
        val duplicate = grid.observeDepth(invalid)
        val nonTracking = grid.observeDepth(invalid.copy(timestampNs = 2, tracking = false))

        assertEquals(0, first.acceptedPixels)
        assertEquals(4, first.rejectedPixels)
        assertTrue(first.rayVisits <= 3)
        assertTrue(duplicate.duplicateTimestamp)
        assertEquals(4, nonTracking.rejectedPixels)
    }

    @Test
    fun `wall and double-wall fixtures preserve holes and reject low confidence`() {
        val grid = depthGrid()
        val frame =
            DepthObservation(
                timestampNs = 1,
                groupGeneration = 1,
                sessionGeneration = 1,
                tracking = true,
                width = 4,
                height = 1,
                samples =
                    listOf(
                        DepthPixelSample(0, 0, 500, 255),
                        DepthPixelSample(1, 0, 0, 255),
                        DepthPixelSample(2, 0, 1_000, 255),
                        DepthPixelSample(3, 0, 750, 127),
                    ),
                intrinsics = DepthIntrinsics(100.0, 100.0, 1.0, 0.0),
                worldFromCameraGl = identityVisibilityGridTransform(),
            )
        repeat(4) { index -> grid.observeDepth(frame.copy(timestampNs = 1L + index)) }

        assertEquals(
            listOf(
                packVisibilityGridKey(-1, 0, -5),
                packVisibilityGridKey(0, 0, -10),
            ).sorted(),
            grid.snapshot().stableKeys,
        )
    }

    @Test
    fun `transient and terminal depth failures preserve feature-only geometry`() {
        val grid = depthGrid()
        grid.observe(
            FeatureObservation(
                timestampNs = 1,
                groupGeneration = 1,
                sessionGeneration = 1,
                samples = listOf(FeatureSample(7, 0.01, 0.01, 0.01, 1.0)),
            ),
        )
        val featureKey = packVisibilityGridKey(0, 0, 0)
        assertTrue(featureKey in grid.snapshot().stableKeys)

        grid.consumeDepth(DepthAcquisitionResult.TransientUnavailable)
        assertEquals("transientUnavailable", grid.snapshot().diagnostics.depthHealth)
        repeat(3) {
            grid.consumeDepth(DepthAcquisitionResult.Failure("synthetic terminal failure"))
        }

        val snapshot = grid.snapshot()
        assertEquals("failed", snapshot.diagnostics.depthHealth)
        assertTrue(featureKey in snapshot.stableKeys)
    }

    @Test
    fun `healthy depth keeps total grid usable after feature failure`() {
        val grid = depthGrid()
        repeat(4) { index ->
            grid.observeDepth(singleRay(1L + index, 0.05, 0.05))
        }
        grid.consumeNext(
            FeatureObservationSource {
                throw IllegalStateException("synthetic feature failure")
            },
        )

        val delta = checkNotNull(grid.takeGeometryDelta(nowNs = 0))
        @Suppress("UNCHECKED_CAST")
        val health = delta.toWireMap().getValue("sourceHealth") as Map<String, String>
        assertEquals("failed", health.getValue("feature"))
        assertEquals("healthy", health.getValue("depth"))
        assertEquals("healthy", health.getValue("totalGrid"))
    }

    @Test
    fun `capacity rejects new depth cells but still permits carving`() {
        val restoredKey = packVisibilityGridKey(0, 0, -5)
        val grid =
            NativeVisibilityGrid(
                featureConfig =
                    VisibilityGridFeatureConfig(
                        stableVoxelCapacity = 1,
                        featureTrackCapacity = 1,
                    ),
                depthConfig = VisibilityGridDepthConfig(),
            )
        grid.startGroup(
            VisibilityGridGroupConfig(
                groupId = "capacity",
                groupGeneration = 1,
                sessionGeneration = 1,
                voxelSizeMeters = 0.1,
                capacity = 1,
                groupFromWorldGl = identityVisibilityGridTransform(),
                restoredGeometryRevision = 1,
                restoredKeys = longArrayOf(restoredKey),
            ),
        )

        val rejected = grid.observeDepth(singleRay(1, 0.05, 0.05))
        assertEquals(1, rejected.acceptedPixels)
        repeat(3) { grid.observeDepth(singleRay(2L + it, 0.05, 0.05)) }
        repeat(4) { grid.observeDepth(singleRay(10L + it, 0.55, -0.45)) }

        assertFalse(restoredKey in grid.snapshot().stableKeys)
        assertTrue(grid.snapshot().diagnostics.depthCapacityRejectedPixels > 0)
    }

    @Test
    fun `synthetic full-budget depth fusion stays within latency and memory budgets`() {
        val baselineHeapBytes = usedHeapAfterGc()
        val grid = depthGrid()
        val samples =
            List(4_096) { index ->
                DepthPixelSample(
                    x = index % 64,
                    y = index / 64,
                    depthMillimeters = 200,
                    confidence = 255,
                )
            }
        val durations =
            List(60) { index ->
                val started = System.nanoTime()
                grid.observeDepth(
                    DepthObservation(
                        timestampNs = 100L + index,
                        groupGeneration = 1,
                        sessionGeneration = 1,
                        tracking = true,
                        width = 64,
                        height = 64,
                        samples = samples,
                        intrinsics = DepthIntrinsics(1_000.0, 1_000.0, 31.5, 31.5),
                        worldFromCameraGl = identityVisibilityGridTransform(),
                    ),
                )
                System.nanoTime() - started
            }.drop(10).sorted()
        val p95Ns = durations[(durations.size * 95 / 100).coerceAtMost(durations.lastIndex)]
        val retainedHeapBytes = (usedHeapAfterGc() - baselineHeapBytes).coerceAtLeast(0)

        assertTrue("p95 was ${p95Ns / 1_000_000.0} ms", p95Ns <= 10_000_000)
        assertTrue(
            "retained heap was $retainedHeapBytes bytes",
            retainedHeapBytes <= VISIBILITY_GRID_MEMORY_BUDGET_BYTES,
        )
        assertTrue(
            grid.snapshot().diagnostics.estimatedStateBytes <=
                VISIBILITY_GRID_MEMORY_BUDGET_BYTES,
        )
    }

    @Test
    fun `depth-first state cannot make later feature admission exceed memory budget`() {
        val restored =
            LongArray(80_000) { index ->
                packVisibilityGridKey(index % 400, index / 400, 0)
            }
        val grid =
            NativeVisibilityGrid(
                featureConfig = VisibilityGridFeatureConfig(),
                depthConfig = VisibilityGridDepthConfig(safetyBandMeters = 2.0),
            )
        grid.startGroup(
            VisibilityGridGroupConfig(
                groupId = "depth-first-memory",
                groupGeneration = 1,
                sessionGeneration = 1,
                voxelSizeMeters = 0.1,
                capacity = 100_000,
                groupFromWorldGl = identityVisibilityGridTransform(),
                restoredGeometryRevision = 1,
                restoredKeys = restored,
            ),
        )
        grid.observeDepth(
            DepthObservation(
                timestampNs = 1,
                groupGeneration = 1,
                sessionGeneration = 1,
                tracking = true,
                width = 4_096,
                height = 1,
                samples =
                    List(4_096) { index ->
                        DepthPixelSample(index, 0, 1_000, 255)
                    },
                intrinsics = DepthIntrinsics(1.0, 1.0, 0.0, 0.0),
                worldFromCameraGl = identityVisibilityGridTransform(),
            ),
        )
        repeat(15) { chunk ->
            grid.observe(
                FeatureObservation(
                    timestampNs = 2L + chunk,
                    groupGeneration = 1,
                    sessionGeneration = 1,
                    samples =
                        List(2_000) { offset ->
                            val id = chunk * 2_000 + offset
                            FeatureSample(
                                id = id,
                                xWorld = id * 0.001,
                                yWorld = 0.0,
                                zWorld = 0.0,
                                confidence = 1.0,
                            )
                        },
                    sanitized = true,
                ),
            )
        }

        val diagnostics = grid.snapshot().diagnostics
        assertTrue(diagnostics.candidateTracks < 30_000)
        assertTrue(diagnostics.capacityRejectedCandidates > 0)
        assertTrue(diagnostics.estimatedStateBytes <= VISIBILITY_GRID_MEMORY_BUDGET_BYTES)
    }

    private fun depthGrid(
        restoredKeys: LongArray = longArrayOf(),
        depthConfig: VisibilityGridDepthConfig = VisibilityGridDepthConfig(),
    ): NativeVisibilityGrid =
        NativeVisibilityGrid(
            featureConfig =
                VisibilityGridFeatureConfig(
                    candidateSamples = 1,
                    candidateSpanNs = 0,
                ),
            depthConfig = depthConfig,
        ).also { grid ->
            grid.startGroup(
                VisibilityGridGroupConfig(
                    groupId = "depth-test",
                    groupGeneration = 1,
                    sessionGeneration = 1,
                    voxelSizeMeters = 0.1,
                    capacity = 1_000,
                    groupFromWorldGl = identityVisibilityGridTransform(),
                    restoredGeometryRevision = if (restoredKeys.isEmpty()) 0 else 1,
                    restoredKeys = restoredKeys,
                ),
            )
        }

    private fun singleRay(
        timestampNs: Long,
        cameraX: Double,
        endpointX: Double,
        depthMillimeters: Int = 1_000,
    ): DepthObservation {
        val depthMeters = depthMillimeters / 1_000.0
        return DepthObservation(
            timestampNs = timestampNs,
            groupGeneration = 1,
            sessionGeneration = 1,
            tracking = true,
            width = 1,
            height = 1,
            samples = listOf(DepthPixelSample(0, 0, depthMillimeters, 255)),
            intrinsics =
                DepthIntrinsics(
                    fx = 1.0,
                    fy = 1.0,
                    cx = (cameraX - endpointX) / depthMeters,
                    cy = 0.0,
                ),
            worldFromCameraGl =
                translationTransform(
                    x = cameraX,
                    y = 0.05,
                    z = 0.05,
                ),
        )
    }

    private fun syntheticDepthObservation(
        timestampNs: Long,
        cameraFromWorldTranslation: DoubleArray,
        samples: List<SyntheticDepthPixel>,
    ): DepthObservation {
        return DepthObservation(
            timestampNs = timestampNs,
            groupGeneration = 1,
            sessionGeneration = 1,
            tracking = true,
            width = 4,
            height = 3,
            samples =
                samples.map {
                    DepthPixelSample(
                        x = it.x,
                        y = it.y,
                        depthMillimeters = it.depthMillimeters,
                        confidence = it.confidence,
                    )
                },
            intrinsics = DepthIntrinsics(fx = 2.0, fy = 2.0, cx = 1.5, cy = 1.0),
            worldFromCameraGl =
                translationTransform(
                    x = cameraFromWorldTranslation[0],
                    y = cameraFromWorldTranslation[1],
                    z = cameraFromWorldTranslation[2],
                ),
        )
    }

    private fun translationTransform(
        x: Double,
        y: Double,
        z: Double,
    ): DoubleArray =
        identityVisibilityGridTransform().also {
            it[12] = x
            it[13] = y
            it[14] = z
        }

    private data class SyntheticDepthPixel(
        val x: Int,
        val y: Int,
        val depthMillimeters: Int,
        val confidence: Int,
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
}
