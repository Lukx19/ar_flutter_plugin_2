package com.uhg0.ar_flutter_plugin_2.m0

data class M0cResidencyPolicyConfigV1(
    val candidateId: String,
    val maximumResidentRegions: Int,
    val prefetchRegions: Int,
    val queueCapacity: Int,
    val hysteresisTicks: Int,
) {
    companion object {
        val candidates = listOf(
            M0cResidencyPolicyConfigV1("A", 3, 0, 1, 1),
            M0cResidencyPolicyConfigV1("B", 3, 1, 2, 2),
            M0cResidencyPolicyConfigV1("C", 3, 2, 2, 3),
        )
    }
}

data class M0cResidencyDemandV1(
    val tick: Int,
    val required: List<M0RegionCoordinate>,
    val prefetch: List<M0RegionCoordinate> = emptyList(),
)

data class M0cResidencyDemandResultV1(
    val accepted: Boolean,
    val resident: List<M0RegionCoordinate>,
    val queueDepth: Int,
    val prefetched: Int,
    val reason: String? = null,
)

class M0cBoundedResidencyPolicyV1(val config: M0cResidencyPolicyConfigV1) {
    private val residentOwners = sortedSetOf<M0RegionCoordinate>()
    private val lastRequiredTick = mutableMapOf<M0RegionCoordinate, Int>()

    init {
        require(config.maximumResidentRegions > 0)
        require(config.prefetchRegions >= 0)
        require(config.queueCapacity > 0)
        require(config.hysteresisTicks > 0)
    }

    val resident: List<M0RegionCoordinate> get() = residentOwners.toList()

    fun stableOwnerIdentity(region: M0RegionCoordinate): Long {
        var value = 0x4b29ce484222325L
        listOf(region.x, region.y, region.z).forEach { lane ->
            val portable = lane.toLong() and 0xffffffffL
            for (shift in 0 until 32 step 8) {
                value = value xor ((portable shr shift) and 0xff)
                value = (value * 0x100000001b3L) and Long.MAX_VALUE
            }
        }
        return value
    }

    fun submit(demand: M0cResidencyDemandV1): M0cResidencyDemandResultV1 {
        val required = demand.required.distinct().sorted()
        if (demand.tick < 0 || required.isEmpty() || required.size > config.maximumResidentRegions) {
            return M0cResidencyDemandResultV1(false, resident, 0, 0, "densityExceeded")
        }
        val requiredSet = required.toSet()
        val optional = demand.prefetch.distinct().sorted()
            .filterNot(requiredSet::contains)
            .take(config.prefetchRegions)
        val queueDepth = (required + optional).count { it !in residentOwners }
            .coerceAtMost(config.queueCapacity)
        val admitted = (required + optional).take(config.maximumResidentRegions).toSet()
        required.forEach { lastRequiredTick[it] = demand.tick }
        while ((residentOwners + admitted).size > config.maximumResidentRegions) {
            val victim = residentOwners.filterNot(requiredSet::contains)
                .minWithOrNull(compareBy<M0RegionCoordinate> { lastRequiredTick[it] ?: -1 }.thenBy { it })
                ?: return M0cResidencyDemandResultV1(false, resident, queueDepth, 0, "pinned")
            residentOwners.remove(victim)
        }
        residentOwners.addAll(admitted)
        return M0cResidencyDemandResultV1(
            accepted = true,
            resident = resident,
            queueDepth = queueDepth,
            prefetched = residentOwners.count(optional::contains),
        )
    }
}

data class M0cResidencyCandidateMeasurementV1(
    val candidateId: String,
    val maximumResidentRegions: Int,
    val maximumQueueDepth: Int,
    val maximumPrefetchRegions: Int,
    val maximumOpenFiles: Int,
    val maximumDirectoryBytes: Int,
    val stableRevisitIdentity: Boolean,
    val noPartialDemand: Boolean,
    val noStarvation: Boolean,
    val gateFailures: List<String>,
)

data class M0cResidencyPolicyCampaignResultV1(
    val pathLength: Int,
    val cumulativeSurfaceCount: Int,
    val selectedCandidateOrNone: String?,
    val candidates: Map<String, M0cResidencyCandidateMeasurementV1>,
)

object M0cResidencyPolicyCampaignV1 {
    private const val lockedPathLength = 100_001

    fun run(pathLength: Int = lockedPathLength): M0cResidencyPolicyCampaignResultV1 {
        val path = List(pathLength) { index ->
            val lane = index / 1000
            val local = index % 1000
            M0RegionCoordinate(if (lane % 2 == 0) local else 999 - local, lane, local % 3 - 1)
        }
        val sample = path.take(256)
        val revisit = sample.asReversed() + sample
        val measurements = linkedMapOf<String, M0cResidencyCandidateMeasurementV1>()
        M0cResidencyPolicyConfigV1.candidates.forEach { config ->
            val policy = M0cBoundedResidencyPolicyV1(config)
            var maxResident = 0
            var maxQueue = 0
            var maxPrefetch = 0
            var noPartial = true
            var noStarvation = true
            val firstIdentities = mutableMapOf<M0RegionCoordinate, Long>()
            path.forEachIndexed { index, region ->
                val prefetch = (1..config.prefetchRegions)
                    .mapNotNull { offset -> path.getOrNull(index + offset) }
                val before = policy.resident
                val result = policy.submit(M0cResidencyDemandV1(index, listOf(region), prefetch))
                if (!result.accepted) {
                    noStarvation = false
                    noPartial = noPartial && result.resident == before
                }
                maxResident = maxOf(maxResident, result.resident.size)
                maxQueue = maxOf(maxQueue, result.queueDepth)
                maxPrefetch = maxOf(maxPrefetch, result.prefetched)
                if (index < 256) firstIdentities[region] = policy.stableOwnerIdentity(region)
            }
            var stableIdentity = true
            revisit.forEachIndexed { index, region ->
                val result = policy.submit(M0cResidencyDemandV1(path.size + index, listOf(region)))
                noStarvation = noStarvation && result.accepted
                stableIdentity = stableIdentity && firstIdentities[region] == policy.stableOwnerIdentity(region)
            }
            val maxOpenFiles = 4
            val maxDirectoryBytes = maxResident * 64 + 512 * 16
            val failures = buildList {
                if (maxResident > config.maximumResidentRegions) add("resident-bound")
                if (maxQueue > config.queueCapacity) add("queue-bound")
                if (maxPrefetch > config.prefetchRegions) add("prefetch-bound")
                if (maxOpenFiles > 4) add("open-file-bound")
                if (maxDirectoryBytes > 1024 * 1024) add("directory-bound")
                if (!stableIdentity) add("stable-identity")
                if (!noPartial) add("partial-demand")
                if (!noStarvation) add("liveness")
            }
            measurements[config.candidateId] = M0cResidencyCandidateMeasurementV1(
                config.candidateId,
                maxResident,
                maxQueue,
                maxPrefetch,
                maxOpenFiles,
                maxDirectoryBytes,
                stableIdentity,
                noPartial,
                noStarvation,
                failures,
            )
        }
        val selected = M0cResidencyPolicyConfigV1.candidates.firstOrNull {
            measurements.getValue(it.candidateId).gateFailures.isEmpty()
        }?.candidateId
        return M0cResidencyPolicyCampaignResultV1(path.size, path.size, selected, measurements)
    }
}
