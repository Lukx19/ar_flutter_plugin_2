package com.uhg0.ar_flutter_plugin_2.m0

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M0bGuidanceVectorTest {
    @Test
    fun `synthetic M0b guidance vector matches the fixed-point reference`() {
        val root = fixture()
        val cameraJson = root.getValue("camera").jsonObject
        val camera = M0PictureVisibilityCamera(
            imageWidth = cameraJson.int("imageWidth"),
            imageHeight = cameraJson.int("imageHeight"),
            fxQ8 = cameraJson.int("fxQ8"),
            fyQ8 = cameraJson.int("fyQ8"),
            cxQ8 = cameraJson.int("cxQ8"),
            cyQ8 = cameraJson.int("cyQ8"),
            groupFromCameraTranslationMm = key(cameraJson.getValue("groupFromCameraTranslationMm")),
            cameraFromGroupRotationQ30 = cameraJson.getValue("cameraFromGroupRotationQ30")
                .jsonArray.map { it.jsonPrimitive.content.toLong() },
        )
        val surfaceJson = root.getValue("surface").jsonObject
        val surface = M0PictureVisibilitySurface(
            surfaceId = surfaceJson.int("surfaceId").toLong(),
            key = key(surfaceJson.getValue("key")),
            normal = vector(surfaceJson.getValue("normal")),
            normalConfidence = surfaceJson.int("normalConfidence"),
        )
        val evaluation = M0PictureVisibilityEvaluator.evaluate(camera, surface)
        val expectedEvaluation = root.getValue("expectedEvaluation").jsonObject
        assertEquals(
            M0PictureVisibilityRejection.APPROVED,
            evaluation.rejection,
        )
        assertEquals(expectedEvaluation.getValue("bin").jsonPrimitive.int, evaluation.bin)
        assertEquals(expectedEvaluation.getValue("depthMm").jsonPrimitive.int, evaluation.depthMm)
        assertEquals(expectedEvaluation.getValue("projectedUQ8").jsonPrimitive.int, evaluation.projectedUQ8)
        assertEquals(expectedEvaluation.getValue("projectedVQ8").jsonPrimitive.int, evaluation.projectedVQ8)
        assertEquals(expectedEvaluation.getValue("footprintQ16").jsonPrimitive.content.toLong(), evaluation.footprintQ16)
        assertEquals(expectedEvaluation.getValue("facingQ15").jsonPrimitive.int, evaluation.facingQ15)
        assertEquals(vector(expectedEvaluation.getValue("viewDirection")), evaluation.viewDirection)

        val candidates = root.getValue("candidates").jsonArray.map { value ->
            val candidate = value.jsonObject
            M0GuidanceCandidateInput(
                surfaceId = candidate.getValue("surfaceId").jsonPrimitive.content.toLong(),
                evaluation = evaluation,
                normal = surface.normal,
                count = candidate.int("count"),
                occupancy = M0PictureVisibilityOccupancy.valueOf(
                    candidate.getValue("occupancy").jsonPrimitive.content.uppercase(),
                ),
            )
        }
        val targets = M0GuidanceReference.select(candidates)
        val expectedTargets = root.getValue("expectedTargets").jsonArray
        assertEquals(expectedTargets.size, targets.size)
        expectedTargets.forEachIndexed { index, value ->
            val expected = value.jsonObject
            val target = targets[index]
            assertEquals(expected.getValue("surfaceId").jsonPrimitive.content.toLong(), target.surfaceId)
            assertEquals(expected.getValue("bin").jsonPrimitive.int, target.bin)
            assertEquals(expected.getValue("gainQ16").jsonPrimitive.int, target.gainQ16)
            assertEquals(key(expected.getValue("standpointMm")), target.standpointMm)
        }

        root.getValue("rejectionCases").jsonArray.forEach { value ->
            val rejectionCase = value.jsonObject
            val rejected = M0PictureVisibilityEvaluator.evaluate(
                camera = camera,
                surface = surface.copy(
                    alreadyCredited = rejectionCase.booleanOrFalse("alreadyCredited"),
                ),
                cut = M0PictureVisibilityCutState(
                    coveragePending = rejectionCase.booleanOrFalse("coveragePending"),
                ),
                occludingCells = rejectionCase.array("occludingCells").map(::key),
            )
            assertEquals(
                rejection(rejectionCase.getValue("expected").jsonPrimitive.content),
                rejected.rejection,
            )
        }

        val completion = root.getValue("completion").jsonObject
        val coverage = M0VisibilityCoverage24()
        completion.getValue("creditBins").jsonArray.forEach { bin ->
            coverage.credit(
                bin.jsonPrimitive.int,
                completion.getValue("creditsPerBin").jsonPrimitive.int.toLong(),
            )
        }
        assertEquals(
            completion.getValue("expectedComplete").jsonPrimitive.boolean,
            coverage.evaluate(surface.normal, surface.normalConfidence).complete,
        )

        val environmentJson = root.getValue("outOfGridEnvironment").jsonObject
        assertTrue(
            M0GuidanceReference.select(
                candidates,
                M0GuidanceEnvironment(
                    gridMinMm = key(environmentJson.getValue("gridMinMm")),
                    gridMaxMm = key(environmentJson.getValue("gridMaxMm")),
                    occupiedCells = emptySet(),
                ),
            ).isEmpty(),
        )
    }

    private fun fixture(): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream("m0b_guidance_vector_v1.json"))
            .bufferedReader()
            .use { it.readText() },
    ).jsonObject

    private fun key(value: kotlinx.serialization.json.JsonElement): M0VoxelKey {
        val values = value.jsonArray.map { it.jsonPrimitive.content.toInt() }
        return M0VoxelKey(values[0], values[1], values[2])
    }

    private fun vector(value: kotlinx.serialization.json.JsonElement): M0Q15Vector {
        val values = value.jsonArray.map { it.jsonPrimitive.content.toInt() }
        return M0Q15Vector(values[0], values[1], values[2])
    }

    private fun rejection(name: String): M0PictureVisibilityRejection = when (name) {
        "occluded" -> M0PictureVisibilityRejection.OCCLUDED
        "captureSurfaceDuplicate" -> M0PictureVisibilityRejection.CAPTURE_SURFACE_DUPLICATE
        "coveragePending" -> M0PictureVisibilityRejection.COVERAGE_PENDING
        else -> error("Unknown rejection $name")
    }

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.booleanOrFalse(key: String): Boolean =
        get(key)?.jsonPrimitive?.boolean ?: false

    private fun JsonObject.array(key: String): List<kotlinx.serialization.json.JsonElement> =
        get(key)?.jsonArray ?: emptyList()
}
