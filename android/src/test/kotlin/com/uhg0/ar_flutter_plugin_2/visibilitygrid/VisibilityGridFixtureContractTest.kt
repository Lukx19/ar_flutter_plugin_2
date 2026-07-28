package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityGridFixtureContractTest {
    @Test
    fun `shared corpus pins Kotlin visibility-grid contract`() {
        val fixtureText =
            checkNotNull(
                javaClass.classLoader?.getResourceAsStream(
                    "visibility_grid/visibility_grid_wire_v1.json",
                ),
            ) { "Shared visibility-grid fixture is missing from test resources." }
                .bufferedReader()
                .use { it.readText() }
        val fixture = Json.parseToJsonElement(fixtureText).jsonObject

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

    private fun kotlinx.serialization.json.JsonObject.double(field: String): Double =
        getValue(field).jsonPrimitive.content.toDouble()

    private fun kotlinx.serialization.json.JsonObject.int(field: String): Int =
        getValue(field).jsonPrimitive.content.toInt()

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
