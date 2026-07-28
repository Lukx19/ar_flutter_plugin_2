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
        assertTrue("depth_safe_band_and_multiview_carving" in names)
        assertTrue("geometry_revision_and_resync" in names)

        val transform =
            scenarios.single {
                it.getValue("name").jsonPrimitive.content == "group_transform_and_packed_keys"
            }
        val expectedKeys =
            transform.getValue("expectedPackedKeys").jsonArray.map {
                it.jsonPrimitive.content.toLong()
            }
        assertEquals(
            listOf(
                4_611_688_217_451_692_032L,
                4_611_692_615_498_203_136L,
                4_611_683_819_405_180_918L,
            ),
            expectedKeys,
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
    }
}
