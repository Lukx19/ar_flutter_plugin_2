package com.uhg0.ar_flutter_plugin_2.m0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M0cResidencyPolicyTest {
    @Test
    fun `bounded policies execute the complete locked long path`() {
        val result = M0cResidencyPolicyCampaignV1.run()

        assertEquals(100_001, result.pathLength)
        assertTrue(result.cumulativeSurfaceCount > 100_000)
        assertEquals("A", result.selectedCandidateOrNone)
        assertEquals(listOf("A", "B", "C"), result.candidates.keys.toList())
        result.candidates.values.forEach { candidate ->
            assertTrue(candidate.candidateId, candidate.gateFailures.isEmpty())
            assertTrue(candidate.maximumResidentRegions <= 3)
            assertTrue(candidate.maximumQueueDepth <= 2)
            assertTrue(candidate.maximumPrefetchRegions <= 2)
            assertTrue(candidate.maximumOpenFiles <= 4)
            assertTrue(candidate.maximumDirectoryBytes <= 1024 * 1024)
            assertTrue(candidate.stableRevisitIdentity)
            assertTrue(candidate.noPartialDemand)
            assertTrue(candidate.noStarvation)
        }
    }

    @Test
    fun `policy preserves signed boundaries and refuses partial dense demand`() {
        assertEquals(
            listOf(-3, -2, -2, -1, -1, 0, 0, 1),
            listOf(-6001, -6000, -3001, -3000, -1, 0, 2999, 3000)
                .map { m0RegionForMillimetres(it, 0, 0).x },
        )
        val policy = M0cBoundedResidencyPolicyV1(M0cResidencyPolicyConfigV1.candidates.first())
        val accepted = policy.submit(
            M0cResidencyDemandV1(
                tick = 0,
                required = listOf(
                    M0RegionCoordinate(-1, 0, 0),
                    M0RegionCoordinate(0, 0, 0),
                    M0RegionCoordinate(1, 0, 0),
                ),
            ),
        )
        assertTrue(accepted.accepted)
        val refused = policy.submit(
            M0cResidencyDemandV1(
                tick = 1,
                required = listOf(
                    M0RegionCoordinate(-2, 0, 0),
                    M0RegionCoordinate(-1, 0, 0),
                    M0RegionCoordinate(0, 0, 0),
                    M0RegionCoordinate(1, 0, 0),
                ),
            ),
        )
        assertTrue(!refused.accepted)
        assertEquals("densityExceeded", refused.reason)
        assertEquals(accepted.resident, refused.resident)
    }
}
