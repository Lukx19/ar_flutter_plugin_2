package com.uhg0.ar_flutter_plugin_2.m0

enum class M0PictureVisibilityOccupancy { CONFIRMED, AMBIGUOUS, ABSENT }

data class M0PictureVisibilityCamera(
    val imageWidth: Int,
    val imageHeight: Int,
    val fxQ8: Int,
    val fyQ8: Int,
    val cxQ8: Int,
    val cyQ8: Int,
    val groupFromCameraTranslationMm: M0VoxelKey,
    val cameraFromGroupRotationQ30: List<Long>,
    val model: String = "rectified_pinhole_q24_8_v1",
    val nearMm: Int = 100,
    val farMm: Int = 3000,
    val poseValid: Boolean = true,
) {
    val isValid: Boolean
        get() = model == "rectified_pinhole_q24_8_v1" &&
            imageWidth in 1..16384 && imageHeight in 1..16384 &&
            fxQ8 in 1..65535 && fyQ8 in 1..65535 &&
            cxQ8 in 0..imageWidth * 256 && cyQ8 in 0..imageHeight * 256 &&
            nearMm == 100 && farMm == 3000 &&
            cameraFromGroupRotationQ30.size == 9 &&
            cameraFromGroupRotationQ30.all { it in -(1L shl 30)..(1L shl 30) }
}

data class M0PictureVisibilitySurface(
    val surfaceId: Long,
    val key: M0VoxelKey,
    val normal: M0Q15Vector,
    val normalConfidence: Int,
    val published: Boolean = true,
    val occlusionEligible: Boolean = true,
    val occupancy: M0PictureVisibilityOccupancy = M0PictureVisibilityOccupancy.CONFIRMED,
    val semanticRevision: Long = 1,
    val alreadyCredited: Boolean = false,
)

data class M0PictureVisibilityCutState(
    val scopeStale: Boolean = false,
    val cutIncompatible: Boolean = false,
    val historyIndeterminate: Boolean = false,
    val coveragePending: Boolean = false,
)

enum class M0PictureVisibilityRejection {
    APPROVED,
    SCOPE_STALE,
    CUT_INCOMPATIBLE,
    HISTORY_INDETERMINATE,
    COVERAGE_PENDING,
    SURFACE_INELIGIBLE,
    CAMERA_MODEL_UNSUPPORTED,
    POSE_INVALID,
    DIRECTION_DEGENERATE,
    DEPTH_RANGE,
    FRUSTUM_OUTSIDE,
    FOOTPRINT_TOO_SMALL,
    BACKSIDE,
    OCCLUDED,
    OCCLUSION_INDETERMINATE,
    CAPTURE_SURFACE_DUPLICATE,
}

data class M0PictureVisibilityEvaluation(
    val rejection: M0PictureVisibilityRejection,
    val bin: Int? = null,
    val surfaceCenterMm: M0VoxelKey? = null,
    val cameraPointMm: M0VoxelKey? = null,
    val depthMm: Int? = null,
    val projectedUQ8: Int? = null,
    val projectedVQ8: Int? = null,
    val footprintQ16: Long = 0,
    val facingQ15: Int = 0,
    val viewDirection: M0Q15Vector? = null,
) {
    val approved: Boolean get() = rejection == M0PictureVisibilityRejection.APPROVED
}

object M0PictureVisibilityEvaluator {
    fun evaluate(
        camera: M0PictureVisibilityCamera,
        surface: M0PictureVisibilitySurface,
        cut: M0PictureVisibilityCutState = M0PictureVisibilityCutState(),
        occludingCells: Iterable<M0VoxelKey> = emptyList(),
    ): M0PictureVisibilityEvaluation {
        if (cut.scopeStale) return reject(M0PictureVisibilityRejection.SCOPE_STALE)
        if (cut.cutIncompatible) return reject(M0PictureVisibilityRejection.CUT_INCOMPATIBLE)
        if (cut.historyIndeterminate) return reject(M0PictureVisibilityRejection.HISTORY_INDETERMINATE)
        if (cut.coveragePending) return reject(M0PictureVisibilityRejection.COVERAGE_PENDING)
        if (!surface.published || surface.normalConfidence !in 0..255 ||
            surface.occupancy == M0PictureVisibilityOccupancy.ABSENT
        ) return reject(M0PictureVisibilityRejection.SURFACE_INELIGIBLE)
        if (!camera.isValid) return reject(M0PictureVisibilityRejection.CAMERA_MODEL_UNSUPPORTED)
        if (!camera.poseValid) return reject(M0PictureVisibilityRejection.POSE_INVALID)

        val surfaceCenter = M0VoxelKey(
            100 * surface.key.x + 50,
            100 * surface.key.y + 50,
            100 * surface.key.z + 50,
        )
        val cameraCenter = camera.groupFromCameraTranslationMm
        val viewDirection = try {
            M0PictureViewBins24.normalize(
                cameraCenter.x - surfaceCenter.x,
                cameraCenter.y - surfaceCenter.y,
                cameraCenter.z - surfaceCenter.z,
            )
        } catch (_: IllegalArgumentException) {
            return reject(
                M0PictureVisibilityRejection.DIRECTION_DEGENERATE,
                surfaceCenterMm = surfaceCenter,
            )
        }
        val cameraPoint = transform(camera, surfaceCenter)
        val depth = -cameraPoint.z
        if (depth < camera.nearMm || depth > camera.farMm) {
            return reject(
                M0PictureVisibilityRejection.DEPTH_RANGE,
                surfaceCenter,
                cameraPoint,
                depth,
                viewDirection = viewDirection,
            )
        }
        val projectedU = camera.cxQ8 + roundTiesEven(camera.fxQ8.toLong() * cameraPoint.x, depth.toLong()).toInt()
        val projectedV = camera.cyQ8 - roundTiesEven(camera.fyQ8.toLong() * cameraPoint.y, depth.toLong()).toInt()
        if (projectedU !in 0 until camera.imageWidth * 256 ||
            projectedV !in 0 until camera.imageHeight * 256
        ) {
            return reject(
                M0PictureVisibilityRejection.FRUSTUM_OUTSIDE,
                surfaceCenter,
                cameraPoint,
                depth,
                projectedU,
                projectedV,
                viewDirection = viewDirection,
            )
        }
        val widthQ8 = roundTiesEven(camera.fxQ8.toLong() * 100, depth.toLong())
        val heightQ8 = roundTiesEven(camera.fyQ8.toLong() * 100, depth.toLong())
        val baseAreaQ16 = widthQ8 * heightQ8
        val facing = if (surface.normalConfidence >= 64) {
            roundTiesEven(surface.normal.dot(viewDirection), 32767).coerceIn(0, 32767).toInt()
        } else {
            32767
        }
        val footprint = roundTiesEven(baseAreaQ16 * facing, 32767)
        if (footprint < 64L * 65536L) {
            return reject(
                M0PictureVisibilityRejection.FOOTPRINT_TOO_SMALL,
                surfaceCenter,
                cameraPoint,
                depth,
                projectedU,
                projectedV,
                footprint,
                facing,
                viewDirection,
            )
        }
        if (surface.normalConfidence >= 64 &&
            surface.normal.dot(viewDirection) < M0PictureViewBins24.FRONT_DOT
        ) {
            return reject(
                M0PictureVisibilityRejection.BACKSIDE,
                surfaceCenter,
                cameraPoint,
                depth,
                projectedU,
                projectedV,
                footprint,
                facing,
                viewDirection,
            )
        }
        val occluding = occludingCells.toSet()
        if (surface.occlusionEligible &&
            M0SupercoverCells100mm.cellsBetween(cameraCenter, surfaceCenter).any { it in occluding }
        ) {
            return reject(
                M0PictureVisibilityRejection.OCCLUDED,
                surfaceCenter,
                cameraPoint,
                depth,
                projectedU,
                projectedV,
                footprint,
                facing,
                viewDirection,
            )
        }
        if (surface.alreadyCredited) {
            return reject(
                M0PictureVisibilityRejection.CAPTURE_SURFACE_DUPLICATE,
                surfaceCenter,
                cameraPoint,
                depth,
                projectedU,
                projectedV,
                footprint,
                facing,
                viewDirection,
            )
        }
        return M0PictureVisibilityEvaluation(
            rejection = M0PictureVisibilityRejection.APPROVED,
            bin = M0PictureViewBins24.classify(viewDirection),
            surfaceCenterMm = surfaceCenter,
            cameraPointMm = cameraPoint,
            depthMm = depth,
            projectedUQ8 = projectedU,
            projectedVQ8 = projectedV,
            footprintQ16 = footprint,
            facingQ15 = facing,
            viewDirection = viewDirection,
        )
    }

    private fun transform(camera: M0PictureVisibilityCamera, point: M0VoxelKey): M0VoxelKey {
        val deltaX = point.x - camera.groupFromCameraTranslationMm.x
        val deltaY = point.y - camera.groupFromCameraTranslationMm.y
        val deltaZ = point.z - camera.groupFromCameraTranslationMm.z
        val matrix = camera.cameraFromGroupRotationQ30
        return M0VoxelKey(
            roundTiesEven(matrix[0] * deltaX + matrix[1] * deltaY + matrix[2] * deltaZ, 1L shl 30).toInt(),
            roundTiesEven(matrix[3] * deltaX + matrix[4] * deltaY + matrix[5] * deltaZ, 1L shl 30).toInt(),
            roundTiesEven(matrix[6] * deltaX + matrix[7] * deltaY + matrix[8] * deltaZ, 1L shl 30).toInt(),
        )
    }

    private fun reject(
        rejection: M0PictureVisibilityRejection,
        surfaceCenterMm: M0VoxelKey? = null,
        cameraPointMm: M0VoxelKey? = null,
        depthMm: Int? = null,
        projectedUQ8: Int? = null,
        projectedVQ8: Int? = null,
        footprintQ16: Long = 0,
        facingQ15: Int = 0,
        viewDirection: M0Q15Vector? = null,
    ) = M0PictureVisibilityEvaluation(
        rejection,
        surfaceCenterMm = surfaceCenterMm,
        cameraPointMm = cameraPointMm,
        depthMm = depthMm,
        projectedUQ8 = projectedUQ8,
        projectedVQ8 = projectedVQ8,
        footprintQ16 = footprintQ16,
        facingQ15 = facingQ15,
        viewDirection = viewDirection,
    )
}

object M0SupercoverCells100mm {
    fun cellsBetween(startMm: M0VoxelKey, endMm: M0VoxelKey): Set<M0VoxelKey> {
        val start = M0VoxelKey(
            Math.floorDiv(startMm.x, 100),
            Math.floorDiv(startMm.y, 100),
            Math.floorDiv(startMm.z, 100),
        )
        val target = M0VoxelKey(
            Math.floorDiv(endMm.x, 100),
            Math.floorDiv(endMm.y, 100),
            Math.floorDiv(endMm.z, 100),
        )
        val current = intArrayOf(start.x, start.y, start.z)
        val targetValues = intArrayOf(target.x, target.y, target.z)
        val starts = intArrayOf(startMm.x, startMm.y, startMm.z)
        val ends = intArrayOf(endMm.x, endMm.y, endMm.z)
        val steps = IntArray(3) { sign(ends[it] - starts[it]) }
        val denominators = LongArray(3) { (ends[it] - starts[it]).toLong().let { kotlin.math.abs(it) } }
        val numerators = LongArray(3) { nextBoundaryDistance(starts[it], current[it], steps[it]) }
        val touched = linkedSetOf<M0VoxelKey>()
        var guard = 0
        while (guard++ < 100_000) {
            if (current.contentEquals(targetValues)) break
            val active = (0..2).filter { denominators[it] > 0 }
            if (active.isEmpty()) break
            val minAxis = active.minWithOrNull { left, right ->
                fractionCompare(numerators[left], denominators[left], numerators[right], denominators[right])
            } ?: break
            val tied = active.filter {
                fractionCompare(numerators[it], denominators[it], numerators[minAxis], denominators[minAxis]) == 0
            }
            for (mask in 1 until (1 shl tied.size)) {
                val cell = current.copyOf()
                tied.forEachIndexed { index, axis ->
                    if (mask and (1 shl index) != 0) cell[axis] += steps[axis]
                }
                touched += M0VoxelKey(cell[0], cell[1], cell[2])
            }
            tied.forEach { axis ->
                current[axis] += steps[axis]
                numerators[axis] += 100L * denominators[axis]
            }
        }
        touched.remove(start)
        touched.remove(target)
        return touched
    }

    private fun nextBoundaryDistance(point: Int, cell: Int, step: Int): Long = when {
        step > 0 -> kotlin.math.abs(100L * (cell + 1) - point)
        step < 0 -> kotlin.math.abs(point - 100L * cell)
        else -> 0
    }

    private fun fractionCompare(leftN: Long, leftD: Long, rightN: Long, rightD: Long): Int = when {
        leftD == 0L && rightD == 0L -> 0
        leftD == 0L -> 1
        rightD == 0L -> -1
        else -> (leftN * rightD).compareTo(rightN * leftD)
    }
}

data class M0GuidanceCandidateInput(
    val surfaceId: Long,
    val evaluation: M0PictureVisibilityEvaluation,
    val normal: M0Q15Vector,
    val count: Int,
    val occupancy: M0PictureVisibilityOccupancy,
)

data class M0GuidanceTarget(
    val surfaceId: Long,
    val bin: Int,
    val gainQ16: Int,
    val standpointMm: M0VoxelKey,
)

object M0GuidanceReference {
    fun select(candidates: Iterable<M0GuidanceCandidateInput>): List<M0GuidanceTarget> =
        candidates.mapNotNull { candidate ->
            val evaluation = candidate.evaluation
            val bin = evaluation.bin ?: return@mapNotNull null
            if (!evaluation.approved) return@mapNotNull null
            val need = when (candidate.count) {
                0 -> 20
                1 -> 10
                else -> 1
            }
            val occupancyWeight = when (candidate.occupancy) {
                M0PictureVisibilityOccupancy.CONFIRMED -> 65535
                M0PictureVisibilityOccupancy.AMBIGUOUS -> 32768
                M0PictureVisibilityOccupancy.ABSENT -> 0
            }
            val footprintWeight = roundTiesEven(65535L * evaluation.footprintQ16, 256L * 65536L)
                .coerceIn(0, 65535)
            val incidence = roundTiesEven(
                candidate.normal.dot(M0PictureViewBins24.centers[bin]),
                32767,
            ).coerceIn(0, 65535)
            var gain = 65535L
            listOf(occupancyWeight.toLong(), footprintWeight, incidence).forEach { factor ->
                gain = roundTiesEven(gain * factor, 65535)
            }
            gain = roundTiesEven(gain * need, 10)
            val center = evaluation.surfaceCenterMm ?: return@mapNotNull null
            val direction = M0PictureViewBins24.centers[bin]
            M0GuidanceTarget(
                surfaceId = candidate.surfaceId,
                bin = bin,
                gainQ16 = gain.coerceIn(0, 65535).toInt(),
                standpointMm = M0VoxelKey(
                    center.x + roundTiesEven(direction.x.toLong() * 2000, 32767).toInt(),
                    center.y + roundTiesEven(direction.y.toLong() * 2000, 32767).toInt(),
                    center.z + roundTiesEven(direction.z.toLong() * 2000, 32767).toInt(),
                ),
            )
        }.sortedWith(compareByDescending<M0GuidanceTarget> { it.gainQ16 }
            .thenBy { it.surfaceId }
            .thenBy { it.bin })
        .take(3)

}

private fun roundTiesEven(numerator: Long, denominator: Long): Long {
    require(denominator > 0)
    val negative = numerator < 0
    val absolute = kotlin.math.abs(numerator)
    var quotient = absolute / denominator
    val remainder = absolute % denominator
    if (2 * remainder > denominator || (2 * remainder == denominator && quotient % 2L != 0L)) {
        quotient++
    }
    return if (negative) -quotient else quotient
}

private fun sign(value: Int): Int = when {
    value < 0 -> -1
    value > 0 -> 1
    else -> 0
}
