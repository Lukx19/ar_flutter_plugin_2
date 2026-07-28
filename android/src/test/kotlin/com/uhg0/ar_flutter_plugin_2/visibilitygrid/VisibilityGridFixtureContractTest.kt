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
            listOf("occupied", "contradicted", "restored"),
            carving.getValue("evidenceSteps").jsonArray.map {
                it.jsonObject.getValue("expectedState").jsonPrimitive.content
            },
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
}
