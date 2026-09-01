package com.uhg0.ar_flutter_plugin_2.proposal08

import com.uhg0.ar_flutter_plugin_2.proposal08.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidencyPolicyTest {
    @Test
    fun `bounded policies execute the complete locked long path`() {
        val result = ResidencyPolicyCampaignV1.run()

        assertEquals(100_001, result.pathLength)
        assertEquals(100_001, result.cumulativeOwnerCount)
        assertEquals(100_001, result.cumulativePageCount)
        assertEquals(100_001, result.cumulativeSurfaceCount)
        assertTrue(result.reverseRevisitIdentity)
        assertEquals("A", result.selectedCandidateOrNone)
        assertEquals(listOf("A", "B", "C"), result.candidates.keys.toList())
        result.candidates.values.forEach { candidate ->
            assertTrue(candidate.candidateId, candidate.gateFailures.isEmpty())
            assertEquals(100_001, candidate.measuredOwnerCount)
            assertEquals(100_001, candidate.measuredPageCount)
            assertEquals(100_001, candidate.measuredSurfaceCount)
            assertTrue(candidate.maximumResidentRegions <= 3)
            assertTrue(candidate.maximumQueueDepth <= 2)
            assertTrue(candidate.maximumPrefetchRegions <= 2)
            assertTrue(candidate.maximumOpenFiles <= 4)
            assertTrue(candidate.maximumDirectoryBytes <= 1024 * 1024)
            assertTrue(candidate.stableRevisitIdentity)
            assertTrue(candidate.noPartialDemand)
            assertTrue(candidate.noStarvation)
        }
        assertEquals(listOf(0, 1, 2), result.candidates.values.map { it.complexityScore })
    }

    @Test
    fun `policy retains queued prefetch until hysteresis permits an eviction`() {
        val config = ResidencyPolicyConfigV1(
            candidateId = "test",
            maximumResidentRegions = 2,
            prefetchRegions = 1,
            queueCapacity = 2,
            hysteresisTicks = 2,
            maximumIndexEntries = 2,
            maximumOpenFiles = 2,
            maximumDirectoryBytes = 1024,
        )
        val policy = BoundedResidencyPolicyV1(config)
        val first = RegionCoordinate(-1, 0, 0)
        val second = RegionCoordinate(0, 0, 0)
        val third = RegionCoordinate(1, 0, 0)
        assertTrue(policy.submit(ResidencyDemandV1(0, listOf(first, second))).accepted)
        val blocked = policy.submit(ResidencyDemandV1(1, listOf(second), listOf(third)))
        assertTrue(blocked.accepted)
        assertEquals(1, blocked.queueDepth)
        assertEquals(listOf(third), policy.pending)
        val drained = policy.submit(ResidencyDemandV1(2, listOf(third)))
        assertTrue(drained.accepted)
        assertEquals(0, drained.queueDepth)
        assertTrue(third in drained.resident)
        assertEquals(2, drained.openFiles)
        assertTrue(drained.directoryBytes > 0)
    }

    @Test
    fun `policy preserves signed boundaries and refuses partial dense demand`() {
        assertEquals(
            listOf(-3, -2, -2, -1, -1, 0, 0, 1),
            listOf(-6001, -6000, -3001, -3000, -1, 0, 2999, 3000)
                .map { regionForMillimetres(it, 0, 0).x },
        )
        val policy = BoundedResidencyPolicyV1(ResidencyPolicyConfigV1.candidates.first())
        val accepted = policy.submit(
            ResidencyDemandV1(
                tick = 0,
                required = listOf(
                    RegionCoordinate(-1, 0, 0),
                    RegionCoordinate(0, 0, 0),
                    RegionCoordinate(1, 0, 0),
                ),
            ),
        )
        assertTrue(accepted.accepted)
        val refused = policy.submit(
            ResidencyDemandV1(
                tick = 1,
                required = listOf(
                    RegionCoordinate(-2, 0, 0),
                    RegionCoordinate(-1, 0, 0),
                    RegionCoordinate(0, 0, 0),
                    RegionCoordinate(1, 0, 0),
                ),
            ),
        )
        assertTrue(!refused.accepted)
        assertEquals("densityExceeded", refused.reason)
        assertEquals(accepted.resident, refused.resident)
    }

    @Test
    fun `policy retains one atomic required batch without exceeding queue bound`() {
        val config = ResidencyPolicyConfigV1("atomic-batch", 2, 0, 1, 3, 2, 2, 1024)
        val policy = BoundedResidencyPolicyV1(config)
        val first = RegionCoordinate(-1, 0, 0)
        val second = RegionCoordinate(0, 0, 0)
        val third = RegionCoordinate(1, 0, 0)
        val fourth = RegionCoordinate(2, 0, 0)
        assertTrue(policy.submit(ResidencyDemandV1(0, listOf(first, second))).accepted)
        val before = policy.resident

        val blocked = policy.submit(ResidencyDemandV1(1, listOf(third, fourth)))
        assertTrue(!blocked.accepted)
        assertEquals(1, blocked.queueDepth)
        assertEquals(before, blocked.resident)
        assertEquals(listOf(third, fourth), policy.pending)

        val drained = policy.submit(ResidencyDemandV1(3, listOf(third, fourth)))
        assertTrue(drained.accepted)
        assertEquals(0, drained.queueDepth)
        assertEquals(listOf(third, fourth), drained.resident)
    }

    @Test
    fun `policy resolves signed millimetres through residency model`() {
        val policy = BoundedResidencyPolicyV1(ResidencyPolicyConfigV1.candidates.first())
        val result = policy.submitMillimetreDemand(
            tick = 0,
            required = listOf(MillimetreCoordinateV1(-1, -3001, 2999)),
        )

        assertTrue(result.accepted)
        assertEquals(RegionCoordinate(-1, -2, 0), result.transitions.single().owner)
        assertEquals(
            PageCoordinate(RegionCoordinate(-1, -2, 0), 2, 2, 2),
            result.transitions.single().page,
        )
    }

    @Test
    fun `resource accounting rejects a record that cannot be retained`() {
        val config = ResidencyPolicyConfigV1("directory-bound", 1, 0, 1, 1, 1, 1, 1)
        val policy = BoundedResidencyPolicyV1(config)
        val result = policy.submit(ResidencyDemandV1(0, listOf(RegionCoordinate(0, 0, 0))))

        assertTrue(!result.accepted)
        assertEquals("resourceBound", result.reason)
        assertTrue(result.resident.isEmpty())
        assertEquals(0, result.openFiles)
        assertEquals(0, result.directoryBytes)
        assertEquals(1, result.queueDepth)
    }
}
