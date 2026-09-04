package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityGridFixtureContractTest {
    @Test
    fun `shared corpus executes every exact depth evidence fixture`() {
        val specification = loadFixture().getValue("depthEvidenceKernel").jsonObject
        assertEquals(
            "bounded_depth_evidence_fixture_v1",
            specification.getValue("format").jsonPrimitive.content,
        )
        val cases = specification.getValue("cases").jsonArray.map { it.jsonObject }
        val requiredCoverage = setOf(
            "wall", "corridor", "safety band", "no behind endpoint", "hole",
            "foreground edge", "thin wall", "double wall", "opposed views",
            "create", "refine", "relocate", "merge", "split", "replace", "remove",
            "capacity", "full budget", "transformed frame", "refusal",
        )
        val actualCoverage = cases.flatMap { case ->
            case.getValue("covers").jsonArray.map { it.jsonPrimitive.content }
        }.toSet()
        assertTrue(actualCoverage.containsAll(requiredCoverage))
        cases.forEach { case ->
            assertTrue(case.containsKey("input"))
            assertTrue(case.getValue("input").jsonObject.getValue("runs").jsonArray.isNotEmpty())
        }
        cases.forEach { case ->
            executeDepthEvidenceCase(specification, case)
        }
    }

    @Test
    fun `synthetic ARCore and ARKit-shaped sensor frames share the native seam`() {
        val fixture = loadFixture()
        val sensor =
            fixture.getValue("scenarios").jsonArray
                .map { it.jsonObject }
                .single {
                    it.getValue("name").jsonPrimitive.content ==
                        "synthetic_arcore_arkit_sensor_frames"
                }
        assertEquals(
            "pointcloud_wire_v4",
            sensor.getValue("pointCloudWireVersion").jsonPrimitive.content,
        )

        val pointClouds = sensor.getValue("pointClouds").jsonObject
        val arCore = pointClouds.getValue("arcore").jsonObject
        val arCoreIds =
            arCore.getValue("ids").jsonArray.map { it.jsonPrimitive.content.toInt() }
        val arCoreValues =
            arCore.getValue("points").jsonArray.map { it.jsonPrimitive.content.toFloat() }
        var arCoreSample: PointCloudSample? =
            PointCloudSample(
                sequence = arCore.getValue("sequence").jsonPrimitive.content.toLong(),
                timestampNs = arCore.getValue("timestampNs").jsonPrimitive.content.toLong(),
                ids = arCoreIds.toIntArray(),
                points = arCoreValues.toFloatArray(),
            )
        val arCoreGrid = featureGrid()
        val arCoreSource =
            ArCoreFeatureObservationSource(
                acquire = { arCoreSample.also { arCoreSample = null } },
                groupGeneration = { 1 },
                sessionGeneration = { 1 },
                minimumConfidence =
                    arCore.getValue("minimumConfidence").jsonPrimitive.content.toDouble(),
            )
        assertTrue(arCoreGrid.consumeNext(arCoreSource))
        assertEquals(
            listOf(packKey(listOf(0, 0, -5)), packKey(listOf(2, 0, -5))),
            arCoreGrid.snapshot().stableKeys,
        )
        assertEquals(
            arCore.getValue("expectedAcceptedIds").jsonArray
                .map { it.jsonPrimitive.content.toInt() }
                .size
                .toLong(),
            arCoreGrid.snapshot().diagnostics.acceptedSamples,
        )

        val arKit = pointClouds.getValue("arkit").jsonObject
        val arKitIds =
            arKit.getValue("identifiers").jsonArray.map { it.jsonPrimitive.content.toInt() }
        val arKitValues =
            arKit.getValue("points").jsonArray.map { it.jsonPrimitive.content.toDouble() }
        val arKitConfidence =
            arKit.getValue("defaultConfidence").jsonPrimitive.content.toDouble()
        val arKitGrid = featureGrid()
        val arKitSamples =
            arKitIds.mapIndexed { index, id ->
                FeatureSample(
                    id = id,
                    xWorld = arKitValues[index * 3],
                    yWorld = arKitValues[index * 3 + 1],
                    zWorld = arKitValues[index * 3 + 2],
                    confidence = arKitConfidence,
                )
            }
        assertTrue(
            arKitGrid.consumeNext(
                SyntheticFeatureObservationSource(
                    listOf(
                        FeatureObservation(
                            timestampNs =
                                arKit.getValue("timestampNs").jsonPrimitive.content.toLong(),
                            groupGeneration = 1,
                            sessionGeneration = 1,
                            samples = arKitSamples,
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(
                packKey(listOf(0, 0, -5)),
                packKey(listOf(1, 0, -5)),
                packKey(listOf(2, 0, -5)),
            ),
            arKitGrid.snapshot().stableKeys,
        )

        val depthMaps = sensor.getValue("depthMaps").jsonObject
        val arCoreDepth = depthMaps.getValue("arcoreRaw").jsonObject
        val depthValues =
            arCoreDepth.getValue("depthMillimeters").jsonArray
                .map { it.jsonPrimitive.content.toInt() }
        val confidenceValues =
            arCoreDepth.getValue("confidence").jsonArray
                .map { it.jsonPrimitive.content.toInt() }
        val depthGrid =
            NativeVisibilityGrid(
                VisibilityGridFeatureConfig(candidateSamples = 1, candidateSpanNs = 0),
                VisibilityGridDepthConfig(
                    confidenceMinimum =
                        arCoreDepth.getValue("minimumConfidence").jsonPrimitive.content.toInt(),
                ),
            )
        depthGrid.startGroup(group().copy(capacity = 100))
        val depthResult =
            depthGrid.observeDepth(
                DepthObservation(
                    timestampNs = 3_000_000_000,
                    groupGeneration = 1,
                    sessionGeneration = 1,
                    tracking = true,
                    width = arCoreDepth.getValue("width").jsonPrimitive.content.toInt(),
                    height = arCoreDepth.getValue("height").jsonPrimitive.content.toInt(),
                    samples =
                        depthValues.mapIndexed { index, value ->
                            DepthPixelSample(
                                x = index % 4,
                                y = index / 4,
                                depthMillimeters = value,
                                confidence = confidenceValues[index],
                            )
                        },
                    intrinsics = DepthIntrinsics(100.0, 100.0, 1.5, 1.0),
                    worldFromCameraGl = identityVisibilityGridTransform(),
                ),
            )
        assertEquals(
            arCoreDepth.getValue("expectedValidPixelCount").jsonPrimitive.content.toInt(),
            depthResult.acceptedPixels,
        )
        assertEquals(12 - depthResult.acceptedPixels, depthResult.rejectedPixels)
    }

    @Test
    fun `shared synthetic wall meets the quality threshold in Kotlin`() {
        val fixture = loadFixture()
        val quality =
            fixture.getValue("scenarios").jsonArray
                .map { it.jsonObject }
                .single {
                    it.getValue("name").jsonPrimitive.content ==
                        "synthetic_quality_certification"
                }
        val voxelSize = quality.double("voxelSizeMeters")
        val wallCoordinates =
            quality.getValue("wallCellCoordinates").jsonArray.map { value ->
                value.jsonArray.map {
                    it.jsonPrimitive.content.toInt()
                }
            }
        val grid =
            NativeVisibilityGrid(
                VisibilityGridFeatureConfig(
                    stableVoxelCapacity = 100,
                    featureTrackCapacity = 100,
                ),
            )
        grid.startGroup(
            VisibilityGridGroupConfig(
                groupId = "synthetic-quality",
                groupGeneration = 1,
                sessionGeneration = 1,
                voxelSizeMeters = voxelSize,
                capacity = 100,
                groupFromWorldGl = identityVisibilityGridTransform(),
            ),
        )
        repeat(5) { observationIndex ->
            grid.observe(
                FeatureObservation(
                    timestampNs = observationIndex * 125_000_000L,
                    groupGeneration = 1,
                    sessionGeneration = 1,
                    samples =
                        wallCoordinates.mapIndexed { index, coordinate ->
                            FeatureSample(
                                id = index + 1,
                                xWorld = (coordinate[0] + 0.5) * voxelSize,
                                yWorld = (coordinate[1] + 0.5) * voxelSize,
                                zWorld = (coordinate[2] + 0.5) * voxelSize,
                                confidence = 1.0,
                            )
                        },
                ),
            )
        }

        val surfaceCell =
            kotlin.math.floor(
                quality.double("wallSurfaceZMeters") / voxelSize,
            ).toInt()
        val maximumDistance =
            quality.int("maximumWallDistanceVoxels")
        val stableCoordinates =
            grid.snapshot().stableKeys.map(::unpackVisibilityGridKey)
        val inlierFraction =
            stableCoordinates.count {
                kotlin.math.abs(it[2] - surfaceCell) <= maximumDistance
            }.toDouble() / stableCoordinates.size
        assertEquals(wallCoordinates.size, stableCoordinates.size)
        assertTrue(
            "wall inlier fraction was $inlierFraction",
            inlierFraction >= quality.double("minimumWallInlierFraction"),
        )

        val corridorCoordinates =
            quality.getValue("corridorPhantomCoordinates").coordinateList()
        val corridorKeys = corridorCoordinates.map(::packKey)
        val directionBins =
            quality.getValue("freeEvidenceDirectionBins").jsonArray.map {
                it.jsonPrimitive.content.toInt()
            }
        val corridorGrid = qualityDepthGrid(voxelSize, corridorKeys)
        repeat(directionBins.count { it == 0 }) { index ->
            corridorGrid.observeDepth(qualityRay(10L + index, 0.05, 0.05))
        }
        corridorCoordinates.sortedBy { it[2] }.forEachIndexed { coordinateIndex, coordinates ->
            val targetZ = (coordinates[2] + 0.5) * voxelSize
            val fractionAlongRay = (0.05 - targetZ) / 1.0
            val cameraX = 0.55
            val endpointX =
                cameraX + (0.05 - cameraX) / fractionAlongRay
            repeat(directionBins.count { it == 2 }) { observationIndex ->
                corridorGrid.observeDepth(
                    qualityRay(
                        timestampNs =
                            100L + coordinateIndex * 10 + observationIndex,
                        cameraX = cameraX,
                        endpointX = endpointX,
                    ),
                )
            }
        }
        val remainingPhantomKeys =
            corridorGrid.snapshot().stableKeys.toSet().intersect(corridorKeys.toSet())
        assertTrue(
            "remaining phantom keys were $remainingPhantomKeys",
            remainingPhantomKeys.size <=
                quality.int("maximumRemainingPhantomThicknessVoxels"),
        )

        val protectedCoordinates =
            quality.getValue("thinWallCoordinates").coordinateList() +
                quality.getValue("doubleWallCoordinates").coordinateList()
        val protectedKeys = protectedCoordinates.map(::packKey)
        val protectedGrid = qualityDepthGrid(voxelSize, protectedKeys)
        repeat(directionBins.count { it == 0 }) { index ->
            protectedGrid.observeDepth(qualityRay(1_000L + index, 0.05, 0.05))
        }
        repeat(directionBins.count { it == 2 }) { index ->
            protectedGrid.observeDepth(qualityRay(2_000L + index, 0.55, -0.45))
        }
        val protectedStableKeys = protectedGrid.snapshot().stableKeys.toSet()
        assertTrue(
            "protected wall keys missing from $protectedStableKeys",
            protectedKeys.all { it in protectedStableKeys },
        )
    }

    @Test
    fun `shared corpus pins Kotlin visibility-grid contract`() {
        val fixture = loadFixture()

        assertEquals("visibility_grid_wire_v1", fixture.getValue("version").jsonPrimitive.content)
        assertEquals("coverage_grid_v3", fixture.getValue("voxelKeyConvention").jsonPrimitive.content)

        val scenarios = fixture.getValue("scenarios").jsonArray.map { it.jsonObject }
        val names = scenarios.map { it.getValue("name").jsonPrimitive.content }.toSet()
        assertTrue("persistent_id_relocation" in names)
        assertTrue("shared_feature_support" in names)
        assertTrue("android_raw_depth_unprojection" in names)
        assertTrue("depth_safe_band_and_multiview_carving" in names)
        assertTrue("ios_scene_depth_orientation_and_fallback" in names)
        assertTrue("source_health_and_resource_closure" in names)
        assertTrue("synthetic_arcore_arkit_sensor_frames" in names)
        assertTrue("synthetic_quality_certification" in names)
        assertTrue("geometry_revision_and_resync" in names)

        val transform =
            scenarios.single {
                it.getValue("name").jsonPrimitive.content == "group_transform_and_packed_keys"
            }
        val groupFromWorld = transform.getValue("groupFromWorldGl").doubleList()
        val worldPoints =
            transform.getValue("worldPoints").jsonArray.map {
                it.doubleList()
            }
        val computedCoordinates =
            worldPoints.map { point ->
                transformPoint(groupFromWorld, point)
                    .map { coordinate -> kotlin.math.floor(coordinate / 0.1).toInt() }
            }
        assertEquals(
            transform.getValue("expectedGroupCellCoordinates").jsonArray.map {
                it.jsonArray.map { coordinate -> coordinate.jsonPrimitive.content.toInt() }
            },
            computedCoordinates,
        )
        assertEquals(
            transform.getValue("expectedPackedKeys").jsonArray.map {
                it.jsonPrimitive.content.toLong()
            },
            computedCoordinates.map(::packKey),
        )

        val unprojection =
            scenarios.single {
                it.getValue("name").jsonPrimitive.content == "android_raw_depth_unprojection"
            }
        val frame = unprojection.getValue("sourceFrame").jsonObject
        assertEquals(
            DepthImageOrientation.LANDSCAPE_RIGHT.wireName,
            frame.getValue("imageOrientation").jsonPrimitive.content,
        )
        val intrinsics = frame.getValue("intrinsics").jsonObject
        val sample = unprojection.getValue("sample").jsonObject
        val pixel =
            sample.getValue("pixel").jsonArray.map { it.jsonPrimitive.content.toInt() }
        val depthMeters =
            sample.getValue("depthMillimeters").jsonPrimitive.content.toDouble() / 1000.0
        val cameraPoint =
            listOf(
                (pixel[0] - intrinsics.double("cx")) * depthMeters / intrinsics.double("fx"),
                -(pixel[1] - intrinsics.double("cy")) * depthMeters / intrinsics.double("fy"),
                -depthMeters,
            )
        assertClose(unprojection.getValue("expectedCameraGl").doubleList(), cameraPoint)
        val worldPoint =
            transformPoint(frame.getValue("worldFromCameraGl").doubleList(), cameraPoint)
        val groupPoint =
            transformPoint(frame.getValue("groupFromWorldGl").doubleList(), worldPoint)
        assertClose(unprojection.getValue("expectedGroupPoint").doubleList(), groupPoint)
        val depthCoordinates =
            groupPoint.map { coordinate -> kotlin.math.floor(coordinate / 0.1).toInt() }
        assertEquals(
            unprojection.getValue("expectedCellCoordinates").jsonArray.map {
                it.jsonPrimitive.content.toInt()
            },
            depthCoordinates,
        )
        assertEquals(
            unprojection.getValue("expectedPackedKey").jsonPrimitive.content.toLong(),
            packKey(depthCoordinates),
        )
        val depthDefaults = fixture.getValue("defaults").jsonObject
        val depthGrid =
            NativeVisibilityGrid(
                featureConfig = VisibilityGridFeatureConfig(),
                depthConfig =
                    VisibilityGridDepthConfig(
                        confidenceMinimum = depthDefaults.int("depthConfidenceMinimum"),
                        safetyBandMeters = depthDefaults.double("safetyBandMeters"),
                        freeEvidenceToCarve = depthDefaults.int("freeEvidenceToCarve"),
                        separatedDirectionBinsRequired =
                            depthDefaults.int("separatedDirectionBinsRequired"),
                    ),
            )
        val depthGroupFromWorld =
            frame.getValue("groupFromWorldGl").doubleList().toDoubleArray()
        val worldFromGroup =
            doubleArrayOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                1.0, 2.0, 3.0, 1.0,
            )
        depthGrid.startGroup(
            VisibilityGridGroupConfig(
                groupId = "depth-fixture",
                groupGeneration = 1,
                sessionGeneration = 1,
                voxelSizeMeters = depthDefaults.double("voxelSizeMeters"),
                capacity = 100,
                groupFromWorldGl = depthGroupFromWorld,
                worldFromGroupGl = worldFromGroup,
            ),
        )
        repeat(4) { index ->
            depthGrid.observeDepth(
                DepthObservation(
                    timestampNs =
                        frame.getValue("timestampNs").jsonPrimitive.content.toLong() + index,
                    groupGeneration = 1,
                    sessionGeneration = 1,
                    tracking = frame.getValue("tracking").jsonPrimitive.content.toBoolean(),
                    width = frame.int("imageWidth"),
                    height = frame.int("imageHeight"),
                    samples =
                        listOf(
                            DepthPixelSample(
                                x = pixel[0],
                                y = pixel[1],
                                depthMillimeters = sample.int("depthMillimeters"),
                                confidence = sample.int("confidence"),
                            ),
                        ),
                    intrinsics =
                        DepthIntrinsics(
                            fx = intrinsics.double("fx"),
                            fy = intrinsics.double("fy"),
                            cx = intrinsics.double("cx"),
                            cy = intrinsics.double("cy"),
                        ),
                    worldFromCameraGl =
                        frame.getValue("worldFromCameraGl").doubleList().toDoubleArray(),
                    imageOrientation = DepthImageOrientation.LANDSCAPE_RIGHT,
                ),
            )
        }
        assertEquals(
            listOf(unprojection.getValue("expectedPackedKey").jsonPrimitive.content.toLong()),
            depthGrid.snapshot().stableKeys,
        )

        val carving =
            scenarios.single {
                it.getValue("name").jsonPrimitive.content ==
                    "depth_safe_band_and_multiview_carving"
            }
        assertFalse(
            carving.getValue("singleViewRemovesOccupied").jsonPrimitive.content.toBoolean(),
        )
        assertEquals(
            2,
            carving.getValue("separatedDirectionBinsRequired").jsonPrimitive.content.toInt(),
        )
        assertEquals(
            referenceCarvingStates(carving),
            carving.getValue("evidenceSteps").jsonArray.map {
                it.jsonObject.getValue("expectedState").jsonPrimitive.content
            },
        )
        assertEquals(
            carving.getValue("freeKeysBeforeSafetyBand").jsonArray.map {
                it.jsonPrimitive.content.toLong()
            },
            referenceFreeKeys(carving),
        )

        val movement =
            scenarios.single {
                it.getValue("name").jsonPrimitive.content == "persistent_id_relocation"
            }
        val defaults = fixture.getValue("defaults").jsonObject
        val featureResult =
            referenceFeatureStates(
                movement,
                defaults,
            )
        assertEquals(
            movement.getValue("observations").jsonArray.map {
                it.jsonObject.getValue("expectedState").jsonPrimitive.content
            },
            featureResult.states,
        )
        assertEquals(
            movement.getValue("promotedKey").jsonPrimitive.content.toLong(),
            featureResult.promotedKey,
        )
        assertEquals(
            movement.getValue("relocatedKey").jsonPrimitive.content.toLong(),
            featureResult.relocatedKey,
        )
        val jump = movement.getValue("jumpObservation").jsonObject
        assertEquals(jump.getValue("expectedState").jsonPrimitive.content, featureResult.jumpState)
        assertEquals(
            jump.getValue("expectedRemovedKey").jsonPrimitive.content.toLong(),
            featureResult.jumpRemovedKey,
        )
        assertEquals(
            jump.getValue("expectedContributedVoxels").jsonPrimitive.content.toInt(),
            featureResult.jumpContributedVoxels,
        )
        val nativeGrid =
            NativeVisibilityGrid(
                VisibilityGridFeatureConfig(
                    stableVoxelCapacity = 100,
                    featureTrackCapacity = 100,
                    candidateSamples = defaults.int("candidateSamples"),
                    candidateSpanNs =
                        defaults.getValue("candidateSpanNs").jsonPrimitive.content.toLong(),
                    candidateMaxStdDevMeters = defaults.double("candidateMaxStdDevMeters"),
                    relocationHysteresisMeters =
                        defaults.double("relocationHysteresisMeters"),
                    jumpResetMeters = defaults.double("jumpResetMeters"),
                ),
            )
        nativeGrid.startGroup(
            VisibilityGridGroupConfig(
                groupId = "fixture-group",
                groupGeneration = 1,
                sessionGeneration = 1,
                voxelSizeMeters = defaults.double("voxelSizeMeters"),
                capacity = 100,
                groupFromWorldGl = identityVisibilityGridTransform(),
            ),
        )
        val featureId = movement.getValue("featureId").jsonPrimitive.content.toInt()
        movement.getValue("observations").jsonArray.forEach { value ->
            val observation = value.jsonObject
            val position = observation.getValue("positionGroup").doubleList()
            nativeGrid.observe(
                FeatureObservation(
                    timestampNs =
                        observation.getValue("timestampNs").jsonPrimitive.content.toLong(),
                    groupGeneration = 1,
                    sessionGeneration = 1,
                    samples =
                        listOf(
                            FeatureSample(
                                id = featureId,
                                xWorld = position[0],
                                yWorld = position[1],
                                zWorld = position[2],
                                confidence = observation.double("confidence"),
                            ),
                        ),
                ),
            )
        }
        assertEquals(listOf(featureResult.relocatedKey), nativeGrid.snapshot().stableKeys)
        val jumpPosition = jump.getValue("positionGroup").doubleList()
        nativeGrid.observe(
            FeatureObservation(
                timestampNs = jump.getValue("timestampNs").jsonPrimitive.content.toLong(),
                groupGeneration = 1,
                sessionGeneration = 1,
                samples =
                    listOf(
                        FeatureSample(
                            id = featureId,
                            xWorld = jumpPosition[0],
                            yWorld = jumpPosition[1],
                            zWorld = jumpPosition[2],
                            confidence = jump.double("confidence"),
                        ),
                    ),
            ),
        )
        assertTrue(nativeGrid.snapshot().stableKeys.isEmpty())
        movement.getValue("jumpRecoveryObservations").jsonArray.forEach { value ->
            val observation = value.jsonObject
            val position = observation.getValue("positionGroup").doubleList()
            nativeGrid.observe(
                FeatureObservation(
                    timestampNs =
                        observation.getValue("timestampNs").jsonPrimitive.content.toLong(),
                    groupGeneration = 1,
                    sessionGeneration = 1,
                    samples =
                        listOf(
                            FeatureSample(
                                id = featureId,
                                xWorld = position[0],
                                yWorld = position[1],
                                zWorld = position[2],
                                confidence = observation.double("confidence"),
                            ),
                        ),
                ),
            )
        }
        assertEquals(
            listOf(
                movement.getValue("expectedRecoveredKey").jsonPrimitive.content.toLong(),
            ),
            nativeGrid.snapshot().stableKeys,
        )
        assertEquals(
            movement.int("expectedRecoveredContributedVoxels"),
            nativeGrid.snapshot().stableKeys.size,
        )

        val sharedSupport =
            scenarios.single {
                it.getValue("name").jsonPrimitive.content == "shared_feature_support"
            }
        val featureIds =
            sharedSupport.getValue("featureIds").jsonArray
                .map { it.jsonPrimitive.content }
                .toMutableSet()
        assertEquals(sharedSupport.int("supportAfterPromotion"), featureIds.size)
        featureIds.remove(sharedSupport.getValue("removedFeatureId").jsonPrimitive.content)
        assertEquals(sharedSupport.int("supportAfterRemoval"), featureIds.size)
        assertEquals(
            sharedSupport.getValue("voxelRemainsOccupied").jsonPrimitive.content.toBoolean(),
            featureIds.isNotEmpty(),
        )

        val ios =
            scenarios.single {
                it.getValue("name").jsonPrimitive.content ==
                    "ios_scene_depth_orientation_and_fallback"
            }
        val iosFrame = ios.getValue("sourceFrame").jsonObject
        val rawPixel =
            ios.getValue("rawPixel").jsonArray.map { it.jsonPrimitive.content.toInt() }
        val orientedPixel =
            listOf(
                rawPixel[1],
                iosFrame.getValue("imageWidth").jsonPrimitive.content.toInt() - 1 - rawPixel[0],
            )
        assertEquals(
            ios.getValue("expectedOrientedPixel").jsonArray.map {
                it.jsonPrimitive.content.toInt()
            },
            orientedPixel,
        )
        val confidenceRank = mapOf("low" to 0, "medium" to 1, "high" to 2)
        val minimum = confidenceRank.getValue(
            ios.getValue("confidenceMinimum").jsonPrimitive.content,
        )
        ios.getValue("samples").jsonArray.forEach { value ->
            val confidenceSample = value.jsonObject
            assertEquals(
                confidenceSample.getValue("expectedAccepted").jsonPrimitive.content.toBoolean(),
                confidenceRank.getValue(
                    confidenceSample.getValue("confidence").jsonPrimitive.content,
                ) >= minimum,
            )
        }

        val resources =
            scenarios.single {
                it.getValue("name").jsonPrimitive.content ==
                    "source_health_and_resource_closure"
            }
        val android = resources.getValue("android").jsonObject
        listOf("usableFrame", "transientUnavailable").forEach { eventName ->
            val event = android.getValue(eventName).jsonObject
            assertEquals(event.int("depthImagesAcquired"), event.int("depthImagesClosed"))
            assertEquals(
                event.int("confidenceImagesAcquired"),
                event.int("confidenceImagesClosed"),
            )
        }
        val iosResources = resources.getValue("ios").jsonObject
        assertEquals(
            iosResources.int("sceneDepthBuffersRetained"),
            iosResources.int("sceneDepthBuffersReleased"),
        )
        assertEquals(
            iosResources.int("confidenceBuffersRetained"),
            iosResources.int("confidenceBuffersReleased"),
        )

        val revisions =
            scenarios.single {
                it.getValue("name").jsonPrimitive.content == "geometry_revision_and_resync"
            }
        val ordered = revisions.getValue("orderedDelta").jsonObject
        assertEquals(ordered.int("baseGeometryRevision") + 1, ordered.int("geometryRevision"))
        val gap = revisions.getValue("gapDelta").jsonObject
        assertTrue(gap.int("baseGeometryRevision") != gap.int("receiverGeometryRevision"))
        assertEquals(gap.int("baseGeometryRevision") + 1, gap.int("geometryRevision"))
        assertEquals(
            "requestSnapshot",
            revisions.getValue("expectedGapAction").jsonPrimitive.content,
        )
        val snapshot = revisions.getValue("snapshot").jsonObject
        assertTrue(snapshot.getValue("reset").jsonPrimitive.content.toBoolean())
        assertEquals(gap.int("geometryRevision"), snapshot.int("geometryRevision"))
        assertEquals(
            snapshot.int("geometryRevision"),
            revisions.getValue("acknowledgedGeometryRevision").jsonPrimitive.content.toInt(),
        )
    }

    private fun kotlinx.serialization.json.JsonElement.doubleList(): List<Double> =
        jsonArray.map { it.jsonPrimitive.content.toDouble() }

    private fun kotlinx.serialization.json.JsonElement.coordinateList(): List<List<Int>> =
        jsonArray.map { value ->
            value.jsonArray.map { it.jsonPrimitive.content.toInt() }
        }

    private fun loadFixture() =
        Json.parseToJsonElement(
            checkNotNull(
                javaClass.classLoader?.getResourceAsStream(
                    "visibility_grid/visibility_grid_wire_v1.json",
                ),
            ) { "Shared visibility-grid fixture is missing from test resources." }
                .bufferedReader()
                .use { it.readText() },
        ).jsonObject

    private fun kotlinx.serialization.json.JsonObject.double(field: String): Double =
        getValue(field).jsonPrimitive.content.toDouble()

    private fun kotlinx.serialization.json.JsonObject.int(field: String): Int =
        getValue(field).jsonPrimitive.content.toInt()

    private fun executeDepthEvidenceCase(
        specification: kotlinx.serialization.json.JsonObject,
        case: kotlinx.serialization.json.JsonObject,
    ) {
        val caseName = case.getValue("name").jsonPrimitive.content
        val runs = case.getValue("input").jsonObject.getValue("runs").jsonArray
        runs.forEachIndexed { runIndex, runValue ->
            val run = runValue.jsonObject
            val configured = run["configuration"]?.jsonObject
            val configuration = DepthEvidenceConfiguration(
                confidenceMinimum = configured?.get("confidenceMinimum")?.jsonPrimitive?.content?.toInt() ?: 128,
                safetyBandMillimetres = configured?.get("safetyBandMillimetres")?.jsonPrimitive?.content?.toInt() ?: 150,
                occupiedEvidenceToShow = configured?.get("occupiedEvidenceToShow")?.jsonPrimitive?.content?.toInt() ?: 4,
                freeEvidenceToCarve = configured?.get("freeEvidenceToCarve")?.jsonPrimitive?.content?.toInt() ?: 8,
                freeEvidenceMargin = configured?.get("freeEvidenceMargin")?.jsonPrimitive?.content?.toInt() ?: 4,
                separatedDirectionBinsRequired = configured?.get("separatedDirectionBinsRequired")?.jsonPrimitive?.content?.toInt() ?: 2,
                surfaceCapacity = configured?.get("surfaceCapacity")?.jsonPrimitive?.content?.toInt() ?: 100_000,
            )
            val kernel = DepthEvidenceKernel(configuration)
            val view = CorpusDepthView(run["view"]?.jsonObject)
            val frameSpec = run["frame"]?.jsonObject
            val voxelSize = frameSpec?.get("voxelSizeMicrometres")?.jsonPrimitive?.content?.toInt() ?: 100_000
            val modelCapacity = frameSpec?.get("modelCapacity")?.jsonPrimitive?.content?.toInt() ?: 100
            val frame = VisibilityGroupFrame.copyOf(
                corpusTransform(frameSpec?.get("worldFromGroup")?.jsonObject),
                corpusTransform(frameSpec?.get("groupFromWorld")?.jsonObject),
                voxelSize,
                modelCapacity,
            )
            run.getValue("steps").jsonArray.forEachIndexed { stepIndex, stepValue ->
                val step = stepValue.jsonObject
                val intrinsics = step.getValue("intrinsics").jsonArray.map { it.jsonPrimitive.content.toDouble() }
                val sampleElement = step.getValue("samples")
                val sampleValues = if (sampleElement is kotlinx.serialization.json.JsonObject) {
                    List(sampleElement.int("repeat")) { sampleElement.getValue("value") }
                } else {
                    sampleElement.jsonArray
                }
                val samples = sampleValues.map { sampleValue ->
                    val sample = sampleValue.jsonArray.map { it.jsonPrimitive.content.toInt() }
                    VisibilityDepthSample(sample[0], sample[1], sample[2], sample[3])
                }
                val sequence = step.getValue("sequence").jsonPrimitive.content.toLong()
                val batch = DepthEvidenceBatch(
                    sequence = sequence,
                    sourceTimestampNs = step["sourceTimestampNs"]?.jsonPrimitive?.content?.toLong() ?: sequence,
                    groupFrame = frame,
                    groupFromCameraGl = corpusTransform(step.getValue("groupFromCamera").jsonObject).toList(),
                    intrinsics = VisibilityCameraIntrinsics(
                        intrinsics[0].toInt(), intrinsics[1].toInt(), intrinsics[2],
                        intrinsics[3], intrinsics[4], intrinsics[5],
                    ),
                    samples = samples,
                    sourceRejectedSamples = step["sourceRejectedSamples"]?.jsonPrimitive?.content?.toInt() ?: 0,
                    tracking = step["tracking"]?.jsonPrimitive?.content?.toBoolean() ?: true,
                )
                val expected = expectedDepthEvidenceResult(specification, step.getValue("expected").jsonObject)
                val actual = kernel.prepare(batch, view)
                assertEquals("$caseName run=$runIndex step=$stepIndex", expected, actual)
                when (step["lifecycle"]?.jsonPrimitive?.content ?: "none") {
                    "apply" -> assertEquals(
                        DepthEvidenceApplyResult.Applied((expected as DepthEvidenceResult.Accepted).receipt),
                        kernel.applyPrepared(),
                    )
                    "discard" -> assertEquals(
                        DepthEvidenceDiscardResult.Discarded((expected as DepthEvidenceResult.Accepted).receipt),
                        kernel.discardPrepared(),
                    )
                    "none" -> Unit
                    else -> error("Unknown depth-evidence fixture lifecycle")
                }
            }
        }
    }

    private fun corpusTransform(value: kotlinx.serialization.json.JsonObject?): DoubleArray {
        val matrix = identityVisibilityGridTransform()
        when (value?.get("type")?.jsonPrimitive?.content ?: "identity") {
            "identity" -> Unit
            "translation" -> requireNotNull(value).getValue("xyz").jsonArray.forEachIndexed { index, coordinate ->
                matrix[12 + index] = coordinate.jsonPrimitive.content.toDouble()
            }
            "rotation_y_180" -> {
                matrix[0] = -1.0
                matrix[10] = -1.0
                requireNotNull(value)["translation"]?.jsonArray?.forEachIndexed { index, coordinate ->
                    matrix[12 + index] = coordinate.jsonPrimitive.content.toDouble()
                }
            }
            else -> error("Unknown depth-evidence fixture transform")
        }
        return matrix
    }

    private class CorpusDepthView(specification: kotlinx.serialization.json.JsonObject?) : BoundedCanonicalSurfaceView {
        private fun voxel(value: kotlinx.serialization.json.JsonElement): Voxel {
            val coordinates = value.jsonArray.map { it.jsonPrimitive.content.toInt() }
            return Voxel(coordinates[0], coordinates[1], coordinates[2])
        }
        private val surfaces = specification?.get("surfaces")?.jsonArray?.associate { value ->
            val surface = value.jsonObject
            val canonicalVoxel = voxel(surface.getValue("voxel"))
            val addressed = surface["addressedAt"]?.let(::voxel) ?: canonicalVoxel
            addressed to DepthCanonicalSurface(
                SurfaceId(surface.getValue("sourceId").jsonPrimitive.content.toLong()),
                canonicalVoxel,
                surface["packedNormal"]?.jsonPrimitive?.content?.toInt() ?: 0x1010,
                surface["normalConfidence"]?.jsonPrimitive?.content?.toInt() ?: 200,
                surface["lineageCount"]?.jsonPrimitive?.content?.toInt() ?: 1,
            )
        }.orEmpty()
        private val byId = surfaces.values.associateBy { it.id }
        private val rayCells = specification?.get("rayCells")?.jsonArray?.map(::voxel).orEmpty()
        private val rayHits = specification?.get("rayHits")?.jsonArray?.map { value ->
            val hit = value.jsonObject
            voxel(hit.getValue("voxel")) to
                hit["sourceId"]?.jsonPrimitive?.content?.toLong()?.let { byId[SurfaceId(it)] }
        }
        private val missingIds = specification?.get("idLookup")?.jsonPrimitive?.content == "missing"
        override val geometryRevision = 0L
        override val lineageRevision = 0L
        override val surfaceCount = specification?.get("surfaceCount")?.jsonPrimitive?.content?.toInt() ?: surfaces.size
        override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? = if (missingIds) null else byId[id]
        override fun findSurfaceAt(voxel: Voxel): DepthCanonicalSurface? = surfaces[voxel]
        override fun visitRayCells(
            startGroupMm: DepthPointMm,
            endpointGroupMm: DepthPointMm,
            maximumVisits: Int,
            visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
        ): DepthRayVisitResult {
            val hits = rayHits ?: rayCells.map { it to surfaces[it] }
            hits.take(maximumVisits).forEach { visitor(it.first, it.second) }
            return DepthRayVisitResult(hits.size.coerceAtMost(maximumVisits), hits.size > maximumVisits)
        }
    }

    private fun expectedDepthEvidenceResult(
        specification: kotlinx.serialization.json.JsonObject,
        case: kotlinx.serialization.json.JsonObject,
    ): DepthEvidenceResult {
        val defaults = specification.getValue("receiptDefaults").jsonObject
        val receiptValues = case.getValue("expectedReceipt").jsonObject
        fun receiptLong(field: String): Long =
            (receiptValues[field] ?: defaults[field])?.jsonPrimitive?.content?.toLong() ?: 0L
        fun receiptInt(field: String): Int = receiptLong(field).toInt()
        val receipt = DepthEvidenceReceipt(
            sequence = receiptLong("sequence"),
            sourceTimestampNs = receiptLong("sourceTimestampNs"),
            acceptedSamples = receiptInt("acceptedSamples"),
            rejectedSamples = receiptInt("rejectedSamples"),
            rayVisits = receiptInt("rayVisits"),
            touchedEvidenceRows = receiptInt("touchedEvidenceRows"),
            independentDirectionVotes = receiptInt("independentDirectionVotes"),
            createCount = receiptInt("createCount"),
            refineCount = receiptInt("refineCount"),
            relocateCount = receiptInt("relocateCount"),
            mergeCount = receiptInt("mergeCount"),
            splitCount = receiptInt("splitCount"),
            replaceCount = receiptInt("replaceCount"),
            removeCount = receiptInt("removeCount"),
            conflictsRetained = receiptInt("conflictsRetained"),
            capacityRefusals = receiptInt("capacityRefusals"),
            overflowCount = receiptInt("overflowCount"),
            preparedResidentBytes = receiptInt("preparedResidentBytes"),
            p50VirtualWorkUnits = receiptInt("p50VirtualWorkUnits"),
            p95VirtualWorkUnits = receiptInt("p95VirtualWorkUnits"),
        )
        if (case.getValue("expectedStatus").jsonPrimitive.content == "refused") {
            return DepthEvidenceResult.Refused(
                DepthEvidenceRefusal.valueOf(case.getValue("expectedRefusal").jsonPrimitive.content),
                receipt,
            )
        }
        fun target(value: kotlinx.serialization.json.JsonObject): CanonicalTarget {
            val sourceValue = value["sourceId"]
            val coordinates = value.getValue("voxel").jsonArray.map { it.jsonPrimitive.content.toInt() }
            return CanonicalTarget(
                if (sourceValue == null || sourceValue.toString() == "null") null
                else SurfaceId(sourceValue.jsonPrimitive.content.toLong()),
                Voxel(coordinates[0], coordinates[1], coordinates[2]),
                value.int("normalOctX"),
                value.int("normalOctY"),
                value.int("normalConfidence"),
            )
        }
        val changes = case.getValue("expectedChanges").jsonArray.map { value ->
            val change = value.jsonObject
            val sources = change["sourceIds"]?.jsonArray?.map { SurfaceId(it.jsonPrimitive.content.toLong()) }.orEmpty()
            when (change.getValue("kind").jsonPrimitive.content) {
                "create" -> DepthEvidenceChange.Create(target(change.getValue("target").jsonObject))
                "refine" -> DepthEvidenceChange.Refine(sources.single(), target(change.getValue("target").jsonObject))
                "relocate" -> DepthEvidenceChange.Relocate(sources.single(), target(change.getValue("target").jsonObject))
                "merge" -> DepthEvidenceChange.Merge(sources, target(change.getValue("target").jsonObject))
                "split" -> DepthEvidenceChange.Split(sources.single(), change.getValue("targets").jsonArray.map { target(it.jsonObject) })
                "replace" -> DepthEvidenceChange.Replace(sources, change.getValue("targets").jsonArray.map { target(it.jsonObject) })
                "remove" -> DepthEvidenceChange.Remove(sources.single())
                else -> error("Unsupported depth-evidence change fixture")
            }
        }
        val workValue = case.getValue("expectedWork").jsonObject
        return DepthEvidenceResult.Accepted(
            expectedGeometryRevision = 0,
            expectedLineageRevision = 0,
            changes = changes,
            receipt = receipt,
            work = DepthEvidenceWorkReceipt(
                distinctTouchedVoxelCount = workValue.int("distinctTouchedVoxelCount"),
                emittedChangeCount = workValue.int("emittedChangeCount"),
                rayVisits = workValue.int("rayVisits"),
                independentDirectionVotes = workValue.int("independentDirectionVotes"),
                virtualWorkUnits = workValue.int("virtualWorkUnits"),
            ),
        )
    }

    private fun featureGrid(): NativeVisibilityGrid =
        NativeVisibilityGrid(
            VisibilityGridFeatureConfig(candidateSamples = 1, candidateSpanNs = 0),
        ).also { it.startGroup(group()) }

    private fun group(): VisibilityGridGroupConfig =
        VisibilityGridGroupConfig(
            groupId = "synthetic-sensor",
            groupGeneration = 1,
            sessionGeneration = 1,
            voxelSizeMeters = 0.1,
            capacity = 100,
            groupFromWorldGl = identityVisibilityGridTransform(),
        )

    private fun transformPoint(
        matrix: List<Double>,
        point: List<Double>,
    ): List<Double> {
        val homogeneous = point + 1.0
        return List(3) { row ->
            (0 until 4).sumOf { column ->
                matrix[column * 4 + row] * homogeneous[column]
            }
        }
    }

    private fun packKey(coordinates: List<Int>): Long {
        val bias = 1L shl 20
        return ((coordinates[0] + bias) shl 42) or
            ((coordinates[1] + bias) shl 21) or
            (coordinates[2] + bias)
    }

    private fun qualityDepthGrid(
        voxelSize: Double,
        restoredKeys: List<Long>,
    ): NativeVisibilityGrid =
        NativeVisibilityGrid(
            featureConfig = VisibilityGridFeatureConfig(),
            depthConfig = VisibilityGridDepthConfig(),
        ).also { grid ->
            grid.startGroup(
                VisibilityGridGroupConfig(
                    groupId = "shared-quality-depth",
                    groupGeneration = 1,
                    sessionGeneration = 1,
                    voxelSizeMeters = voxelSize,
                    capacity = 100,
                    groupFromWorldGl = identityVisibilityGridTransform(),
                    restoredGeometryRevision = 1,
                    restoredKeys = restoredKeys.toLongArray(),
                ),
            )
        }

    private fun qualityRay(
        timestampNs: Long,
        cameraX: Double,
        endpointX: Double,
    ): DepthObservation {
        val depthMillimeters = 1_000
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
                identityVisibilityGridTransform().also {
                    it[12] = cameraX
                    it[13] = 0.05
                    it[14] = 0.05
                },
        )
    }

    private fun assertClose(
        expected: List<Double>,
        actual: List<Double>,
    ) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals(expected[index], actual[index], 1e-9)
        }
    }

    private data class FeatureReferenceResult(
        val states: List<String>,
        val promotedKey: Long,
        val relocatedKey: Long,
        val jumpState: String,
        val jumpRemovedKey: Long,
        val jumpContributedVoxels: Int,
    )

    private fun referenceFeatureStates(
        fixture: kotlinx.serialization.json.JsonObject,
        defaults: kotlinx.serialization.json.JsonObject,
    ): FeatureReferenceResult {
        val voxelSize = defaults.double("voxelSizeMeters")
        val requiredSamples = defaults.int("candidateSamples")
        val requiredSpan = defaults.getValue("candidateSpanNs").jsonPrimitive.content.toLong()
        val maxStdDev = defaults.double("candidateMaxStdDevMeters")
        val hysteresis = defaults.double("relocationHysteresisMeters")
        val jumpThreshold = defaults.double("jumpResetMeters")
        val samples = mutableListOf<List<Double>>()
        val states = mutableListOf<String>()
        var filtered: List<Double>? = null
        var firstTimestamp: Long? = null
        var stableCoordinates: List<Int>? = null
        var promotedKey: Long? = null
        var relocatedKey: Long? = null

        fixture.getValue("observations").jsonArray.forEach { value ->
            val observation = value.jsonObject
            val position = observation.getValue("positionGroup").doubleList()
            val timestamp = observation.getValue("timestampNs").jsonPrimitive.content.toLong()
            val confidence = observation.double("confidence")
            filtered =
                filtered?.let { previous ->
                    val alpha = (0.15 + 0.35 * confidence).coerceIn(0.15, 0.50)
                    List(3) { index ->
                        previous[index] + alpha * (position[index] - previous[index])
                    }
                } ?: position

            if (stableCoordinates == null) {
                if (firstTimestamp == null) firstTimestamp = timestamp
                samples += position
                val stableEnough =
                    samples.size >= requiredSamples &&
                        timestamp - checkNotNull(firstTimestamp) >= requiredSpan &&
                        (0 until 3).all { axis ->
                            val mean = samples.sumOf { sample -> sample[axis] } / samples.size
                            val variance =
                                samples.sumOf { sample ->
                                    val difference = sample[axis] - mean
                                    difference * difference
                                } / samples.size
                            kotlin.math.sqrt(variance) <= maxStdDev
                        }
                if (stableEnough) {
                    stableCoordinates =
                        checkNotNull(filtered).map { coordinate ->
                            kotlin.math.floor(coordinate / voxelSize).toInt()
                        }
                    promotedKey = packKey(checkNotNull(stableCoordinates))
                    states += "stable"
                } else {
                    states += "candidate"
                }
                return@forEach
            }

            val current = checkNotNull(stableCoordinates)
            val next = current.toMutableList()
            (0 until 3).forEach { axis ->
                val lower = current[axis] * voxelSize - hysteresis
                val upper = (current[axis] + 1) * voxelSize + hysteresis
                val coordinate = checkNotNull(filtered)[axis]
                if (coordinate < lower || coordinate >= upper) {
                    next[axis] = kotlin.math.floor(coordinate / voxelSize).toInt()
                }
            }
            if (packKey(next) != packKey(current)) {
                stableCoordinates = next
                relocatedKey = packKey(next)
                states += "relocated"
            } else {
                states += "stable"
            }
        }

        val jump = fixture.getValue("jumpObservation").jsonObject
        val jumpPosition = jump.getValue("positionGroup").doubleList()
        val jumpDistance =
            kotlin.math.sqrt(
                (0 until 3).sumOf { axis ->
                    val difference = jumpPosition[axis] - checkNotNull(filtered)[axis]
                    difference * difference
                },
            )
        val removedKey = packKey(checkNotNull(stableCoordinates))
        return FeatureReferenceResult(
            states = states,
            promotedKey = checkNotNull(promotedKey),
            relocatedKey = checkNotNull(relocatedKey),
            jumpState = if (jumpDistance >= jumpThreshold) "candidate" else "stable",
            jumpRemovedKey = removedKey,
            jumpContributedVoxels = if (jumpDistance >= jumpThreshold) 0 else 1,
        )
    }

    private fun referenceCarvingStates(
        fixture: kotlinx.serialization.json.JsonObject,
    ): List<String> {
        val freeThreshold = fixture.int("freeEvidenceToCarve")
        val occupiedRestore = fixture.int("occupiedEvidenceToRestore")
        val requiredBins = fixture.int("separatedDirectionBinsRequired")
        var contradicted = false
        return fixture.getValue("evidenceSteps").jsonArray.map { value ->
            val step = value.jsonObject
            val occupied = step.int("occupied")
            val free = step.int("free")
            val bins = step.getValue("directionBins").jsonArray.toSet().size
            if (contradicted && occupied >= occupiedRestore) {
                contradicted = false
                "restored"
            } else if (
                !contradicted &&
                free >= freeThreshold &&
                bins >= requiredBins &&
                free >= occupied + 4
            ) {
                contradicted = true
                "contradicted"
            } else {
                "occupied"
            }
        }
    }

    private fun referenceFreeKeys(
        fixture: kotlinx.serialization.json.JsonObject,
    ): List<Long> {
        val start = fixture.getValue("cameraGroup").doubleList()
        val end = fixture.getValue("surfaceGroup").doubleList()
        val safety = fixture.double("safetyBandMeters")
        val voxelSize = 0.1
        val delta = List(3) { index -> end[index] - start[index] }
        val length = kotlin.math.sqrt(delta.sumOf { value -> value * value })
        val direction = delta.map { value -> value / length }
        val keys = mutableListOf<Long>()
        var distance = voxelSize
        while (distance <= length - safety + 1e-9) {
            val point = List(3) { index -> start[index] + direction[index] * distance }
            val coordinates =
                point.map { coordinate -> kotlin.math.floor(coordinate / voxelSize).toInt() }
            val key = packKey(coordinates)
            if (keys.lastOrNull() != key) keys += key
            distance += voxelSize
        }
        return keys
    }
}
