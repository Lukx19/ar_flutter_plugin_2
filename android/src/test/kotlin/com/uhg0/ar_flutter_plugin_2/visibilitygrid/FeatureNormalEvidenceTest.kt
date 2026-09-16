package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureNormalEvidenceTest {
    @Test
    fun `normalization and oct encoding are exact deterministic integer operations`() {
        assertEquals(listOf(19_660, 26_214, 0), requireNotNull(FeatureNormalMath.normalizeQ15(3, 4, 0)).toList())
        assertEquals(listOf(32_767, 0, 0), requireNotNull(FeatureNormalMath.normalizeQ15(4_294_967_295L, 1, 0)).toList())
        val vector = requireNotNull(FeatureNormalMath.normalizeQ15(1_000, -2_000, 3_000))
        val encoded = FeatureNormalMath.encodeOct(vector)
        var expected = 0
        var best = Long.MIN_VALUE
        for (packed in 0..0xffff) {
            val decoded = FeatureNormalMath.decodeOct((packed ushr 8).toByte().toInt(), packed.toByte().toInt()) ?: continue
            val dot = decoded[0].toLong() * vector[0] + decoded[1].toLong() * vector[1] + decoded[2].toLong() * vector[2]
            if (dot > best || (dot == best && packed < expected)) { best = dot; expected = packed }
        }
        assertEquals(expected, encoded)
        val decoded = requireNotNull(FeatureNormalMath.decodeOct(encoded.ushr(8).toByte().toInt(), encoded.toByte().toInt()))
        assertTrue(decoded[0].toLong() * vector[0] + decoded[1].toLong() * vector[1] + decoded[2].toLong() * vector[2] > 0)
    }

    @Test
    fun `reliable opposing support becomes two ordered hypotheses and does not flip primary`() {
        val kernel = FeatureFusionKernel()
        val first = accepted(kernel, batch(1, List(2) { evidence(1, it, true) }))
        val primary = upsert(first).normalCandidates.single()
        val originalCode = primary.normalOctX to primary.normalOctY
        assertEquals(FeatureNormalFace.PRIMARY, primary.face)
        assertTrue(primary.normalConfidence >= 64)

        val opposing = accepted(kernel, batch(2, listOf(evidence(10, 5, false))))
        val candidates = upsert(opposing).normalCandidates
        assertEquals(listOf(FeatureNormalFace.PRIMARY, FeatureNormalFace.OPPOSING), candidates.map { it.face }.sortedBy { it.ordinal })
        assertEquals(candidates.map { ((it.normalOctX and 0xff) shl 8) or (it.normalOctY and 0xff) }.sorted(), candidates.map { ((it.normalOctX and 0xff) shl 8) or (it.normalOctY and 0xff) })
        assertTrue(candidates.all { it.normalConfidence >= 64 })
        assertTrue(candidates.any { (it.normalOctX to it.normalOctY) == originalCode })

        val overtaken = accepted(kernel, batch(3, List(3) { evidence(20, it + 6, false) }))
        val overtakenCandidates = upsert(overtaken).normalCandidates
        assertEquals(originalCode, overtakenCandidates.single { it.face == FeatureNormalFace.PRIMARY }.let { it.normalOctX to it.normalOctY })
        assertTrue(overtakenCandidates.single { it.face == FeatureNormalFace.OPPOSING }.normalConfidence >
            overtakenCandidates.single { it.face == FeatureNormalFace.PRIMARY }.normalConfidence)
    }

    @Test
    fun `exact equal reliable sides publish one unknown lexical primary`() {
        // C12 says an exact hemisphere tie publishes confidence 0. This takes
        // precedence over the ticket shorthand saying two reliable sides emit
        // two hypotheses: two oriented faces would contradict that tie rule.
        val kernel = FeatureFusionKernel()
        val result = accepted(kernel, batch(1, List(4) { evidence(7, it, true) } + List(4) { evidence(8, it, false) }))
        val normals = upsert(result).normalCandidates
        assertEquals(1, normals.size)
        assertEquals(FeatureNormalFace.PRIMARY, normals.single().face)
        assertEquals(0, normals.single().normalConfidence)
    }

    @Test
    fun `normal evidence rejection is atomic`() {
        val kernel = FeatureFusionKernel()
        val initial = accepted(kernel, batch(1, listOf(evidence(0, 0, true))))
        val invalid = FeatureFusionEvidence(0.02, 0.02, 0.02, 2, 2,
            FeatureNormalEvidence(0, 0, 0, 20, 20, 20, 20, 20, 20, 32_767))
        val refusal = kernel.accept(batch(2, listOf(invalid))) as FeatureFusionResult.Refused
        assertEquals(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE, refusal.reason)
        assertEquals(initial.receipt, refusal.receipt)
        assertFalse(accepted(kernel, batch(2, emptyList())).delta.isNotEmpty())
    }

    @Test
    fun `adapter uses column major translation and ties-even quantization`() {
        assertEquals(0, FeatureNormalEvidence.intMillimeters(0.0005))
        assertEquals(2, FeatureNormalEvidence.intMillimeters(0.0015))
        assertEquals(-2, FeatureNormalEvidence.intMillimeters(-0.0015))
        assertEquals(16_384, FeatureNormalEvidence.q15(0.5))

        val converted = FeatureNormalEvidence.from(observation(
            samples = listOf(VisibilityFeatureSample(1, 0.0005, 0.0015, -0.0015, 0.5)),
            camera = doubleArrayOf(0.125, -0.25, 0.375),
        )) as FeatureNormalEvidence.Conversion.Accepted
        assertEquals(
            FeatureNormalEvidence(0, 0, -1, 0, 2, -2, 125, -250, 375, 16_384),
            converted.evidence.single(),
        )
    }

    @Test
    fun `adapter refuses a complete batch for degenerate or out of range evidence`() {
        val outOfRange = FeatureNormalEvidence.from(observation(listOf(
            VisibilityFeatureSample(1, 0.02, 0.02, 0.02, 1.0),
            VisibilityFeatureSample(2, 104_857.6, 0.02, 0.02, 1.0),
        )))
        assertEquals(FeatureNormalEvidence.Conversion.Refused, outOfRange)

        val degenerate = FeatureNormalEvidence.from(observation(
            listOf(VisibilityFeatureSample(1, 0.125, -0.25, 0.375, 1.0)),
            doubleArrayOf(0.125, -0.25, 0.375),
        ))
        assertEquals(FeatureNormalEvidence.Conversion.Refused, degenerate)
    }

    @Test
    fun `permutation is byte identical and retained state has only five direction arrays`() {
        val evidence = listOf(
            evidence(30, 1, true),
            FeatureFusionEvidence(0.02, 0.02, 0.02, 2, 2,
                FeatureNormalEvidence(0, 0, 0, 20, 20, 20, 1_020, 1_020, 20, 16_384)),
            evidence(30, 3, false),
        )
        val first = accepted(FeatureFusionKernel(), batch(1, evidence))
        val second = accepted(FeatureFusionKernel(), batch(1, evidence.reversed()))
        assertEquals(first.delta, second.delta)

        val retainedDirectionArrays = FeatureFusionKernel::class.java.declaredFields
            .filter { it.type == IntArray::class.java && it.name in setOf(
                "axisXQ13", "axisYQ13", "axisZQ13", "positiveSupportQ13", "negativeSupportQ13",
            ) }
        assertEquals(5, retainedDirectionArrays.size)
        assertTrue(FeatureFusionKernel::class.java.declaredFields.none { it.type == FeatureNormalEvidence::class.java })
    }

    private fun evidence(x: Int, support: Int, positive: Boolean): FeatureFusionEvidence {
        val cameraX = if (positive) 1_020 else -980
        return FeatureFusionEvidence(0.02, 0.02, 0.02, 2, x * 100 + support,
            FeatureNormalEvidence(0, 0, 0, 20, 20, 20, cameraX, 20, 20, 32_767))
    }
    private fun batch(sequence: Long, evidence: List<FeatureFusionEvidence>) = FeatureFusionBatch(sequence, sequence, evidence)
    private fun accepted(kernel: FeatureFusionKernel, batch: FeatureFusionBatch) = kernel.accept(batch) as FeatureFusionResult.Accepted
    private fun upsert(result: FeatureFusionResult.Accepted) = (result.delta.single() as FeatureFusionChange.Upsert).candidate

    private fun observation(
        samples: List<VisibilityFeatureSample>,
        camera: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0),
    ): VisibilityFeatureObservation {
        val pose = identityVisibilityGridTransform().also {
            it[12] = camera[0]; it[13] = camera[1]; it[14] = camera[2]
        }
        val copied = VisibilityFeatureObservation.copySamples(samples)
        return VisibilityFeatureObservation(
            ownership = VisibilityObservationOwnership(
                "00000000000000000000000000000001", 1,
                "00000000000000000000000000000002", 1, 1,
                "00000000000000000000000000000003",
                "00000000000000000000000000000004", 1,
                "00000000000000000000000000000005",
                "00000000000000000000000000000006", 1, 1, 1,
                VisibilityGroupFrame.copyOf(
                    identityVisibilityGridTransform(), identityVisibilityGridTransform(), 1_000, 100_000,
                ),
            ),
            frame = VisibilityObservationFrame(
                VisibilityObservationSource.SYNTHETIC_FEATURE, 1, 1, 1, "camera", true,
                "landscape_right_x_right_y_down_v1", VisibilityCameraPose.copyOf(pose),
                VisibilityCameraIntrinsics(16, 12, 10.0, 10.0, 8.0, 6.0),
                VisibilityDepthCapability.UNSUPPORTED,
            ),
            samples = copied,
            sourceRejectedSamples = 0,
            payloadBytes = VisibilityFeatureObservation.FEATURE_FIXED_BYTES +
                copied.size * VisibilityFeatureObservation.FEATURE_SAMPLE_BYTES,
        )
    }
}
