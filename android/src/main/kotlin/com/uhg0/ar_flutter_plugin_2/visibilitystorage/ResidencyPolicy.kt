package com.uhg0.ar_flutter_plugin_2.visibilitystorage

import com.uhg0.ar_flutter_plugin_2.visibilitystorage.PageCoordinate
import com.uhg0.ar_flutter_plugin_2.visibilitystorage.RegionCoordinate
import com.uhg0.ar_flutter_plugin_2.visibilitystorage.ResidencyModel
import com.uhg0.ar_flutter_plugin_2.visibilitystorage.ResolvedResidencyCoordinate
import com.uhg0.ar_flutter_plugin_2.visibilitystorage.pageEdgeMillimetres
import com.uhg0.ar_flutter_plugin_2.visibilitystorage.regionEdgeMillimetres
import kotlin.math.max

data class ResidencyPolicyConfigV1(
    val candidateId: String,
    val maximumResidentRegions: Int,
    val prefetchRegions: Int,
    val queueCapacity: Int,
    val hysteresisTicks: Int,
    val maximumIndexEntries: Int,
    val maximumOpenFiles: Int,
    val maximumDirectoryBytes: Int,
) {
    companion object {
        val candidates = listOf(
            ResidencyPolicyConfigV1("A", 3, 0, 1, 1, 3, 3, 1024 * 1024),
            ResidencyPolicyConfigV1("B", 3, 1, 2, 1, 3, 3, 1024 * 1024),
            ResidencyPolicyConfigV1("C", 3, 2, 2, 2, 3, 3, 1024 * 1024),
        )
    }
}

data class MillimetreCoordinateV1(
    val xMillimetres: Int,
    val yMillimetres: Int,
    val zMillimetres: Int,
)

data class SignedRegionPageTransitionV1(
    val xMillimetres: Int,
    val yMillimetres: Int,
    val zMillimetres: Int,
    val owner: RegionCoordinate,
    val page: PageCoordinate,
) {
    val surfaceKey: String get() = listOf(owner, page.linearIndex).joinToString("/")

    companion object {
        fun fromMillimetres(x: Int, y: Int, z: Int): SignedRegionPageTransitionV1 {
            return fromResolved(ResidencyModel(1).resolveMillimetres(x, y, z))
        }

        fun fromResolved(resolved: ResolvedResidencyCoordinate) = SignedRegionPageTransitionV1(
            resolved.xMillimetres,
            resolved.yMillimetres,
            resolved.zMillimetres,
            resolved.owner,
            resolved.page,
        )
    }
}

data class ResidencyDemandV1(
    val tick: Int,
    val required: List<RegionCoordinate>,
    val prefetch: List<RegionCoordinate> = emptyList(),
)

data class ResidencyDemandResultV1(
    val accepted: Boolean,
    val resident: List<RegionCoordinate>,
    val queueDepth: Int,
    val prefetched: Int,
    val openFiles: Int,
    val directoryBytes: Int,
    val transitions: List<SignedRegionPageTransitionV1>,
    val reason: String? = null,
)

class BoundedResidencyPolicyV1(val config: ResidencyPolicyConfigV1) {
    private data class CacheEntry(
        val transition: SignedRegionPageTransitionV1,
        var lastRequiredTick: Int,
        var prefetched: Boolean,
    ) {
        val handle = OpenRegionHandle.open(transition)
    }

    private data class QueuedWork(
        val regions: List<RegionCoordinate>,
        val transitions: List<SignedRegionPageTransitionV1>,
        var required: Boolean,
        val order: Int,
        var lastRequestedTick: Int,
    )

    private class OpenRegionHandle private constructor(val directoryRecord: ByteArray) {
        var isOpen = true
            private set
        val directoryRecordBytes: Int get() = directoryRecord.size
        fun close() { isOpen = false }

        companion object {
            fun open(transition: SignedRegionPageTransitionV1): OpenRegionHandle {
                val owner = transition.owner
                val record = "schema5/regions/${owner.x}/${owner.y}/${owner.z}/" +
                    "page-${transition.page.linearIndex}.record"
                return OpenRegionHandle(record.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private val ownership = ResidencyModel(config.maximumResidentRegions)
    private val index = sortedMapOf<RegionCoordinate, CacheEntry>()
    private val queue = mutableListOf<QueuedWork>()
    private var lastTick = -1
    private var nextQueueOrder = 0

    init {
        require(config.candidateId.isNotEmpty())
        require(config.maximumResidentRegions > 0)
        require(config.prefetchRegions >= 0)
        require(config.queueCapacity > 0)
        require(config.hysteresisTicks > 0)
        require(config.maximumIndexEntries >= config.maximumResidentRegions)
        require(config.maximumOpenFiles >= config.maximumResidentRegions)
        require(config.maximumDirectoryBytes > 0)
    }

    val resident: List<RegionCoordinate> get() = index.keys.toList()
    val pending: List<RegionCoordinate>
        get() = queue.sortedWith(queueComparator).flatMap { it.regions }

    fun stableOwnerIdentity(region: RegionCoordinate): Long {
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

    fun submit(demand: ResidencyDemandV1): ResidencyDemandResultV1 {
        val required = demand.required.distinct().sorted()
        val prefetch = demand.prefetch.distinct().sorted()
        val transitions = (required + prefetch.filterNot(required::contains)).map { owner ->
            resolve(
                MillimetreCoordinateV1(
                    owner.x * regionEdgeMillimetres,
                    owner.y * regionEdgeMillimetres,
                    owner.z * regionEdgeMillimetres,
                ),
            )
        }
        return submitResolved(ResidencyDemandV1(demand.tick, required, prefetch), transitions)
    }

    fun submitMillimetreDemand(
        tick: Int,
        required: List<MillimetreCoordinateV1>,
        prefetch: List<MillimetreCoordinateV1> = emptyList(),
    ): ResidencyDemandResultV1 {
        require(required.isNotEmpty())
        val requiredTransitions = required.map(::resolve)
        val requiredOwners = requiredTransitions.map { it.owner }
        val requiredOwnerSet = requiredOwners.toSet()
        val prefetchTransitions = prefetch.map(::resolve).filterNot { it.owner in requiredOwnerSet }
        return submitResolved(
            ResidencyDemandV1(tick, requiredOwners, prefetchTransitions.map { it.owner }),
            requiredTransitions + prefetchTransitions,
        )
    }

    private fun submitResolved(
        demand: ResidencyDemandV1,
        transitions: List<SignedRegionPageTransitionV1>,
    ): ResidencyDemandResultV1 {
        val required = demand.required.distinct().sorted()
        val prefetch = demand.prefetch.distinct().sorted()
        if (demand.tick < 0 || demand.tick <= lastTick) return result(false, transitions, "staleTick")
        if (required.isEmpty() || required.size > config.maximumResidentRegions) {
            return result(false, transitions, "densityExceeded")
        }
        lastTick = demand.tick
        val requiredSet = required.toSet()
        required.forEach { region ->
            index[region]?.let {
                it.lastRequiredTick = demand.tick
                it.prefetched = false
            }
        }
        var admissionFailure = enqueue(
            required.filterNot(index::containsKey),
            transitions.filter { it.owner in requiredSet },
            true,
            demand.tick,
        )
        val optional = prefetch.filterNot(requiredSet::contains).take(config.prefetchRegions).toSet()
        queue.removeAll { work -> !work.required && work.regions.any { it !in optional } }
        val optionalFailure = enqueue(
            optional.filterNot(index::containsKey),
            transitions.filter { it.owner in optional },
            false,
            demand.tick,
        )
        if (admissionFailure == null) admissionFailure = optionalFailure
        val drainFailure = drain(demand.tick, requiredSet)
        if (admissionFailure == null) admissionFailure = drainFailure
        val accepted = required.all(index::containsKey)
        return result(accepted, transitions, if (accepted) null else admissionFailure ?: "backpressure")
    }

    private fun enqueue(
        regions: Collection<RegionCoordinate>,
        transitions: Collection<SignedRegionPageTransitionV1>,
        required: Boolean,
        tick: Int,
    ): String? {
        val batch = regions.distinct().sorted()
        if (batch.isEmpty()) return null
        val existing = queue.firstOrNull { it.regions == batch }
        if (existing != null) {
            existing.required = existing.required || required
            existing.lastRequestedTick = tick
            return null
        }
        if (queue.size >= config.queueCapacity) {
            if (!required) return "queueFull"
            val optionalIndex = queue.indexOfLast { !it.required }
            if (optionalIndex < 0) return "queueFull"
            queue.removeAt(optionalIndex)
        }
        val transitionByOwner = transitions.associateBy { it.owner }
        queue += QueuedWork(
            batch,
            batch.map { transitionByOwner[it] ?: transitionFor(it) },
            required,
            nextQueueOrder++,
            tick,
        )
        return null
    }

    private fun drain(tick: Int, protectedRequired: Set<RegionCoordinate>): String? {
        while (queue.isNotEmpty()) {
            queue.sortWith(queueComparator)
            val work = queue.first()
            if (work.regions.all(index::containsKey)) {
                if (work.required) {
                    work.regions.forEach { region ->
                        index.getValue(region).lastRequiredTick = tick
                        index.getValue(region).prefetched = false
                    }
                }
                queue.removeAt(0)
                continue
            }
            val failure = install(work, tick, protectedRequired)
            if (failure != null) return failure
            queue.removeAt(0)
        }
        return null
    }

    private fun install(
        work: QueuedWork,
        tick: Int,
        protectedRequired: Set<RegionCoordinate>,
    ): String? {
        val missing = work.regions.filterNot(index::containsKey)
        val next = sortedMapOf<RegionCoordinate, CacheEntry>().apply { putAll(index) }
        val capacity = minOf(config.maximumResidentRegions, config.maximumIndexEntries)
        val removalsNeeded = max(0, next.size + missing.size - capacity)
        val protected = protectedRequired + if (work.required) work.regions else emptyList()
        val victims = next.values.filter {
            it.transition.owner !in protected && tick - it.lastRequiredTick >= config.hysteresisTicks
        }.sortedWith(
            compareByDescending<CacheEntry> { it.prefetched }
                .thenBy { it.lastRequiredTick }
                .thenBy { it.transition.owner },
        )
        if (victims.size < removalsNeeded) return "backpressure"
        val removed = victims.take(removalsNeeded)
        removed.forEach { next.remove(it.transition.owner) }
        val transitionByOwner = work.transitions.associateBy { it.owner }
        val created = missing.map { region ->
            CacheEntry(
                transitionByOwner[region] ?: transitionFor(region),
                if (work.required) tick else -1,
                !work.required,
            ).also { next[region] = it }
        }
        val nextOpenFiles = next.values.count { it.handle.isOpen }
        val nextDirectoryBytes = next.values.sumOf { it.handle.directoryRecordBytes }
        if (nextOpenFiles > config.maximumOpenFiles || nextDirectoryBytes > config.maximumDirectoryBytes) {
            created.forEach { it.handle.close() }
            return "resourceBound"
        }
        check(ownership.submitDemand(next.keys).accepted)
        removed.forEach { it.handle.close() }
        index.clear()
        index.putAll(next)
        return null
    }

    private val openFiles: Int get() = index.values.count { it.handle.isOpen }
    private val directoryBytes: Int get() = index.values.sumOf { it.handle.directoryRecordBytes }

    private fun result(
        accepted: Boolean,
        transitions: List<SignedRegionPageTransitionV1>,
        reason: String?,
    ) = ResidencyDemandResultV1(
        accepted,
        resident,
        queue.size,
        index.values.count { it.prefetched },
        openFiles,
        directoryBytes,
        transitions,
        reason,
    )

    private fun resolve(coordinate: MillimetreCoordinateV1) =
        SignedRegionPageTransitionV1.fromResolved(
            ownership.resolveMillimetres(
                coordinate.xMillimetres,
                coordinate.yMillimetres,
                coordinate.zMillimetres,
            ),
        )

    private fun transitionFor(region: RegionCoordinate): SignedRegionPageTransitionV1 {
        val transition = resolve(
            MillimetreCoordinateV1(
                region.x * regionEdgeMillimetres,
                region.y * regionEdgeMillimetres,
                region.z * regionEdgeMillimetres,
            ),
        )
        check(transition.owner == region)
        return transition
    }

    private companion object {
        val queueComparator = compareByDescending<QueuedWork> { it.required }.thenBy { it.order }
    }
}

data class ResidencyCandidateMeasurementV1(
    val candidateId: String,
    val maximumResidentRegions: Int,
    val maximumQueueDepth: Int,
    val maximumPrefetchRegions: Int,
    val maximumOpenFiles: Int,
    val maximumDirectoryBytes: Int,
    val measuredOwnerCount: Int,
    val measuredPageCount: Int,
    val measuredSurfaceCount: Int,
    val stableRevisitIdentity: Boolean,
    val noPartialDemand: Boolean,
    val noStarvation: Boolean,
    val complexityScore: Int,
    val gateFailures: List<String>,
)

data class ResidencyPolicyCampaignResultV1(
    val pathLength: Int,
    val cumulativeOwnerCount: Int,
    val cumulativePageCount: Int,
    val cumulativeSurfaceCount: Int,
    val reverseRevisitIdentity: Boolean,
    val selectedCandidateOrNone: String?,
    val candidates: Map<String, ResidencyCandidateMeasurementV1>,
)

object ResidencyPolicyCampaignV1 {
    private const val lockedPathLength = 100_001

    fun run(pathLength: Int = lockedPathLength): ResidencyPolicyCampaignResultV1 {
        require(pathLength > 0)
        val path = List(pathLength) { index ->
            val lane = index / 1000
            val local = index % 1000
            RegionCoordinate(if (lane % 2 == 0) local else 999 - local, lane, local % 3 - 1)
        }
        val samples = path.mapIndexed(::sampleForOwner)
        val revisit = samples.take(256).asReversed() + samples.take(256)
        val measurements = linkedMapOf<String, ResidencyCandidateMeasurementV1>()
        ResidencyPolicyConfigV1.candidates.forEach { config ->
            val policy = BoundedResidencyPolicyV1(config)
            var maxResident = 0
            var maxQueue = 0
            var maxPrefetch = 0
            var maxOpenFiles = 0
            var maxDirectoryBytes = 0
            var noPartial = true
            var noStarvation = true
            var stableIdentity = true
            val owners = mutableSetOf<RegionCoordinate>()
            val pages = mutableSetOf<PageCoordinate>()
            val surfaces = mutableSetOf<String>()
            val original = mutableMapOf<RegionCoordinate, String>()
            fun observe(result: ResidencyDemandResultV1, isRevisit: Boolean = false) {
                maxResident = max(maxResident, result.resident.size)
                maxQueue = max(maxQueue, result.queueDepth)
                maxPrefetch = max(maxPrefetch, result.prefetched)
                maxOpenFiles = max(maxOpenFiles, result.openFiles)
                maxDirectoryBytes = max(maxDirectoryBytes, result.directoryBytes)
                result.transitions.forEach { transition ->
                    owners += transition.owner
                    pages += transition.page
                    surfaces += transition.surfaceKey
                    val prior = original[transition.owner]
                    if (isRevisit && prior != null && prior != transition.surfaceKey) stableIdentity = false
                    if (!isRevisit) original[transition.owner] = transition.surfaceKey
                }
            }
            path.forEachIndexed { index, region ->
                val prefetch = (1..config.prefetchRegions).mapNotNull { samples.getOrNull(index + it) }
                val result = policy.submitMillimetreDemand(index, listOf(samples[index]), prefetch)
                observe(result)
                noPartial = noPartial && result.accepted
                noStarvation = noStarvation && result.accepted
            }
            revisit.forEachIndexed { index, region ->
                val result = policy.submitMillimetreDemand(path.size + index, listOf(region))
                observe(result, true)
                noPartial = noPartial && result.accepted
                noStarvation = noStarvation && result.accepted
            }
            val failures = buildList {
                if (maxResident > config.maximumResidentRegions) add("resident-bound")
                if (maxQueue > config.queueCapacity) add("queue-bound")
                if (maxPrefetch > config.prefetchRegions) add("prefetch-bound")
                if (maxOpenFiles > config.maximumOpenFiles) add("open-file-bound")
                if (maxDirectoryBytes > config.maximumDirectoryBytes) add("directory-bound")
                if (owners.size != pathLength) add("owner-count")
                if (pages.size != pathLength) add("page-count")
                if (surfaces.size != pathLength) add("surface-count")
                if (!stableIdentity) add("stable-identity")
                if (!noPartial) add("partial-demand")
                if (!noStarvation) add("liveness")
            }
            measurements[config.candidateId] = ResidencyCandidateMeasurementV1(
                config.candidateId, maxResident, maxQueue, maxPrefetch, maxOpenFiles,
                maxDirectoryBytes, owners.size, pages.size, surfaces.size, stableIdentity,
                noPartial, noStarvation, maxQueue + maxPrefetch, failures,
            )
        }
        val selected = measurements.values.filter { it.gateFailures.isEmpty() }
            .minWithOrNull(compareBy<ResidencyCandidateMeasurementV1> { it.complexityScore }
                .thenBy { it.candidateId })
        return ResidencyPolicyCampaignResultV1(
            path.size,
            selected?.measuredOwnerCount ?: 0,
            selected?.measuredPageCount ?: 0,
            selected?.measuredSurfaceCount ?: 0,
            selected?.stableRevisitIdentity ?: false,
            selected?.candidateId,
            measurements,
        )
    }

    private fun sampleForOwner(index: Int, owner: RegionCoordinate) = MillimetreCoordinateV1(
        owner.x * regionEdgeMillimetres + (index % 3) * pageEdgeMillimetres + 499,
        owner.y * regionEdgeMillimetres + ((index / 3) % 3) * pageEdgeMillimetres + 499,
        owner.z * regionEdgeMillimetres + ((index / 9) % 3) * pageEdgeMillimetres + 499,
    )
}
