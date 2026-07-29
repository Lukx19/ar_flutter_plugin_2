import Foundation

/// Two independently coalesced latest-value slots used before serialized
/// feature/depth fusion. Callers provide synchronization.
final class LatestSensorHandoff<Feature, Depth> {
    private var feature: Feature?
    private var depth: Depth?

    var hasPending: Bool {
        feature != nil || depth != nil
    }

    @discardableResult
    func offerFeature(_ value: Feature) -> Bool {
        let replaced = feature != nil
        feature = value
        return replaced
    }

    @discardableResult
    func offerDepth(_ value: Depth) -> Bool {
        let replaced = depth != nil
        depth = value
        return replaced
    }

    func take() -> (feature: Feature?, depth: Depth?)? {
        guard hasPending else { return nil }
        let result = (feature: feature, depth: depth)
        feature = nil
        depth = nil
        return result
    }

    func clear() {
        feature = nil
        depth = nil
    }
}

enum SensorHandoffSource: Equatable {
    case feature
    case depth
}

func sensorProcessingOrder(
    featureTimestampNanoseconds: Int64?,
    depthTimestampNanoseconds: Int64?
) -> [SensorHandoffSource] {
    switch (
        featureTimestampNanoseconds,
        depthTimestampNanoseconds
    ) {
    case (nil, nil):
        return []
    case (nil, _):
        return [.depth]
    case (_, nil):
        return [.feature]
    case let (.some(feature), .some(depth)) where depth < feature:
        return [.depth, .feature]
    default:
        return [.feature, .depth]
    }
}

final class NativeVisibilityGrid {
    private enum PendingGeometryState {
        case upsert
        case removal
    }

    private final class Track {
        var filtered: VisibilityGridPoint
        var sampleCount: Int
        var sampleSums: [Double]
        var sampleSquareSums: [Double]
        var firstTimestampNanoseconds: Int64
        var lastTimestampNanoseconds: Int64
        var stableKey: UInt64?
        var stableCoordinates: [Int]?

        init(
            filtered: VisibilityGridPoint,
            timestampNanoseconds: Int64
        ) {
            self.filtered = filtered
            sampleCount = 1
            sampleSums = [filtered.x, filtered.y, filtered.z]
            sampleSquareSums = [
                filtered.x * filtered.x,
                filtered.y * filtered.y,
                filtered.z * filtered.z
            ]
            firstTimestampNanoseconds = timestampNanoseconds
            lastTimestampNanoseconds = timestampNanoseconds
        }
    }

    private struct DepthEvidence {
        var occupied: UInt8 = 0
        var free: UInt8 = 0
        var directionMask: UInt32 = 0
        var contradicted = false
    }

    private let featureConfiguration: VisibilityGridFeatureConfiguration
    private let depthConfiguration: VisibilityGridDepthConfiguration?
    private var group: VisibilityGridGroupConfiguration?
    private var lastStartedIdentity: VisibilityGridIdentity?
    private var tracks: [UInt64: Track] = [:]
    private var supportByKey: [UInt64: Int] = [:]
    private var restoredKeys: Set<UInt64> = []
    private var visibleKeys: Set<UInt64> = []
    private var depthEvidenceByKey: [UInt64: DepthEvidence] = [:]
    private var pendingGeometry: [UInt64: PendingGeometryState] = [:]
    private var snapshotRequired = false
    private var geometryRevision: Int64 = 0
    private var inFlightDelta: VisibilityGridDelta?
    private var lastPublicationNanoseconds: Int64?
    private var lastObservationTimestampNanoseconds: Int64 = -1
    private var lastDepthTimestampNanoseconds: Int64 = -1
    private var diagnostics = VisibilityGridDiagnostics()
    private var consecutiveDepthFailures = 0
    private var depthReservations = 0
    private let featureFusionSamples = BoundedLatencySamples()
    private let depthFusionSamples = BoundedLatencySamples()

    init(
        featureConfiguration: VisibilityGridFeatureConfiguration,
        depthConfiguration: VisibilityGridDepthConfiguration?
    ) throws {
        self.featureConfiguration = featureConfiguration
        self.depthConfiguration = depthConfiguration
    }

    @discardableResult
    func startGroup(
        _ next: VisibilityGridGroupConfiguration
    ) throws -> VisibilityGridSnapshot {
        guard next.capacity <= featureConfiguration.stableVoxelCapacity else {
            throw VisibilityGridContractError.capacityExceeded
        }
        guard Int64(next.restoredKeys.count * 160) <=
            visibilityGridCoreMemoryBudgetBytes
        else {
            throw VisibilityGridContractError.capacityExceeded
        }
        if let current = lastStartedIdentity {
            guard
                next.sessionGeneration > current.sessionGeneration ||
                (
                    next.sessionGeneration == current.sessionGeneration &&
                    next.groupGeneration > current.groupGeneration
                )
            else {
                throw VisibilityGridContractError.staleIdentity
            }
        }
        group = next
        lastStartedIdentity = next.identity
        tracks.removeAll(keepingCapacity: true)
        supportByKey.removeAll(keepingCapacity: true)
        restoredKeys = Set(next.restoredKeys)
        visibleKeys = Set(next.restoredKeys)
        depthEvidenceByKey.removeAll(keepingCapacity: true)
        pendingGeometry.removeAll(keepingCapacity: true)
        snapshotRequired = false
        geometryRevision = next.restoredGeometryRevision
        inFlightDelta = nil
        lastPublicationNanoseconds = nil
        lastObservationTimestampNanoseconds = -1
        lastDepthTimestampNanoseconds = -1
        consecutiveDepthFailures = 0
        depthReservations = 0
        diagnostics = VisibilityGridDiagnostics()
        featureFusionSamples.clear()
        depthFusionSamples.clear()
        diagnostics.featureTrackCapacity =
            featureConfiguration.featureTrackCapacity
        diagnostics.stableVoxelCapacity = next.capacity
        diagnostics.depthHealth =
            depthConfiguration == nil ? "unsupported" : "configured"
        return snapshot()
    }

    func stopGroup() {
        group = nil
        tracks.removeAll()
        supportByKey.removeAll()
        restoredKeys.removeAll()
        visibleKeys.removeAll()
        depthEvidenceByKey.removeAll()
        pendingGeometry.removeAll()
        snapshotRequired = false
        geometryRevision = 0
        inFlightDelta = nil
        lastPublicationNanoseconds = nil
    }

    func observeFeatures(_ observation: FeatureObservation) throws {
        let active = try requireGroup()
        try requireIdentity(
            groupGeneration: observation.groupGeneration,
            sessionGeneration: observation.sessionGeneration
        )
        let started = DispatchTime.now().uptimeNanoseconds
        let acceptedBefore = diagnostics.acceptedSamples
        defer {
            let elapsed =
                Int64(DispatchTime.now().uptimeNanoseconds - started)
            diagnostics.lastFeatureFusionNanoseconds = elapsed
            diagnostics.maxFeatureFusionNanoseconds =
                max(diagnostics.maxFeatureFusionNanoseconds, elapsed)
            let accepted =
                diagnostics.acceptedSamples - acceptedBefore
            if accepted > 0 {
                featureFusionSamples.record(elapsed * 1_000 / accepted)
                diagnostics.featureFusionP95Nanoseconds =
                    featureFusionSamples.p95()
            }
            refreshDiagnostics()
        }
        diagnostics.featureObservationCount += 1
        guard observation.timestampNanoseconds >
            lastObservationTimestampNanoseconds
        else {
            diagnostics.rejectedSamples += Int64(observation.samples.count)
            return
        }
        lastObservationTimestampNanoseconds =
            observation.timestampNanoseconds
        expireCandidates(observation.timestampNanoseconds)

        let accepted = sanitizeAndBound(observation.samples)
        diagnostics.rejectedSamples +=
            Int64(observation.sourceRejectedSamples)
        for sample in accepted {
            let position = VisibilityGridPoint.transform(
                matrix: active.groupFromWorldGL,
                point: sample.world
            )
            guard isQuantizable(position) else {
                diagnostics.rejectedSamples += 1
                continue
            }
            if let track = tracks[sample.identifier] {
                if track.stableKey == nil {
                    let alpha =
                        min(0.50, max(0.15, 0.15 + 0.35 * sample.confidence))
                    track.filtered = interpolate(
                        previous: track.filtered,
                        current: position,
                        alpha: alpha
                    )
                    addCandidateSample(track, position)
                    track.lastTimestampNanoseconds =
                        observation.timestampNanoseconds
                    promoteIfReady(
                        track,
                        timestampNanoseconds:
                            observation.timestampNanoseconds
                    )
                } else {
                    updateStableTrack(
                        track,
                        position: position,
                        confidence: sample.confidence,
                        timestampNanoseconds:
                            observation.timestampNanoseconds
                    )
                }
                diagnostics.acceptedSamples += 1
                continue
            }
            guard tracks.count < featureConfiguration.featureTrackCapacity,
                reservationCount() < active.capacity,
                estimatedStateBytes() + 512 <=
                    visibilityGridCoreMemoryBudgetBytes
            else {
                diagnostics.capacityRejectedCandidates += 1
                continue
            }
            let track = Track(
                filtered: position,
                timestampNanoseconds: observation.timestampNanoseconds
            )
            tracks[sample.identifier] = track
            diagnostics.acceptedSamples += 1
            promoteIfReady(
                track,
                timestampNanoseconds: observation.timestampNanoseconds
            )
        }
        diagnostics.featureHealth = "healthy"
    }

    @discardableResult
    func observeDepth(
        _ observation: DepthObservation
    ) throws -> (accepted: Int, rejected: Int, rayVisits: Int) {
        let active = try requireGroup()
        let config = try requireDepthConfiguration()
        try requireIdentity(
            groupGeneration: observation.groupGeneration,
            sessionGeneration: observation.sessionGeneration
        )
        let started = DispatchTime.now().uptimeNanoseconds
        defer {
            let elapsed =
                Int64(DispatchTime.now().uptimeNanoseconds - started)
            diagnostics.lastDepthFusionNanoseconds = elapsed
            diagnostics.maxDepthFusionNanoseconds =
                max(diagnostics.maxDepthFusionNanoseconds, elapsed)
            depthFusionSamples.record(elapsed)
            diagnostics.depthFusionP95Nanoseconds =
                depthFusionSamples.p95()
            refreshDiagnostics()
        }
        diagnostics.depthObservationCount += 1
        guard observation.tracking else {
            let rejected =
                observation.samples.count +
                observation.sourceRejectedPixels
            diagnostics.depthRejectedPixels += Int64(rejected)
            return (0, rejected, 0)
        }
        guard observation.timestampNanoseconds >
            lastDepthTimestampNanoseconds
        else {
            let rejected =
                observation.samples.count +
                observation.sourceRejectedPixels
            diagnostics.depthRejectedPixels += Int64(rejected)
            return (0, rejected, 0)
        }
        lastDepthTimestampNanoseconds =
            observation.timestampNanoseconds

        let cameraWorld = VisibilityGridPoint.transform(
            matrix: observation.worldFromCameraGL,
            point: VisibilityGridPoint(x: 0, y: 0, z: 0)
        )
        let cameraGroup = VisibilityGridPoint.transform(
            matrix: active.groupFromWorldGL,
            point: cameraWorld
        )
        var occupiedKeys: Set<UInt64> = []
        var freeDirectionsByKey: [UInt64: Int] = [:]
        var accepted = 0
        var rejected = observation.sourceRejectedPixels
        var rayVisits = 0

        for sample in observation.samples.prefix(
            config.maxAcceptedPixelsPerObservation
        ) {
            guard sample.confidence >= config.minimumConfidence,
                sample.depthMeters.isFinite,
                sample.depthMeters >= config.minimumDepthMeters,
                sample.depthMeters <= config.maximumDepthMeters
            else {
                rejected += 1
                continue
            }
            let cameraPoint = VisibilityGridPoint(
                x:
                    (Double(sample.x) - observation.intrinsics.cx) *
                    sample.depthMeters / observation.intrinsics.fx,
                y:
                    -(Double(sample.y) - observation.intrinsics.cy) *
                    sample.depthMeters / observation.intrinsics.fy,
                z: -sample.depthMeters
            )
            let endpointWorld = VisibilityGridPoint.transform(
                matrix: observation.worldFromCameraGL,
                point: cameraPoint
            )
            let endpointGroup = VisibilityGridPoint.transform(
                matrix: active.groupFromWorldGL,
                point: endpointWorld
            )
            guard isQuantizable(endpointGroup) else {
                rejected += 1
                continue
            }
            let endpointKey = key(for: endpointGroup)
            if ensureDepthEvidence(for: endpointKey) {
                occupiedKeys.insert(endpointKey)
            } else {
                diagnostics.depthCapacityRejectedPixels += 1
            }
            let remaining =
                config.maxRayVisitsPerObservation - rayVisits
            if remaining > 0 {
                let freeKeys = traverseFreeKeys(
                    camera: cameraGroup,
                    endpoint: endpointGroup,
                    maximumVisits: remaining,
                    safetyBandMeters: config.safetyBandMeters
                )
                for key in freeKeys {
                    if visibleKeys.contains(key) ||
                        depthEvidenceByKey[key] != nil {
                        if ensureDepthEvidence(for: key) {
                            freeDirectionsByKey[key] =
                                freeDirectionsByKey[key] ??
                                directionBin(
                                    camera: cameraGroup,
                                    endpoint: voxelCenter(key)
                                )
                        }
                    }
                }
                rayVisits += freeKeys.count
            }
            accepted += 1
        }
        rejected += max(
            0,
            observation.samples.count -
                config.maxAcceptedPixelsPerObservation
        )
        try applyDepthEvidence(
            occupiedKeys: occupiedKeys,
            freeDirectionsByKey: freeDirectionsByKey,
            countObservation: false
        )
        diagnostics.depthAcceptedPixels += Int64(accepted)
        diagnostics.depthRejectedPixels += Int64(rejected)
        diagnostics.depthRayVisits += Int64(rayVisits)
        diagnostics.depthHealth = "healthy"
        consecutiveDepthFailures = 0
        return (accepted, rejected, rayVisits)
    }

    func applyDepthEvidence(
        occupiedKeys: Set<UInt64>,
        freeDirectionsByKey: [UInt64: Int],
        countObservation: Bool = true
    ) throws {
        _ = try requireDepthConfiguration()
        if countObservation {
            diagnostics.depthObservationCount += 1
        }
        var touched: Set<UInt64> = []
        for key in occupiedKeys where ensureDepthEvidence(for: key) {
            var evidence = depthEvidenceByKey[key]!
            evidence.occupied = saturatingIncrement(evidence.occupied)
            depthEvidenceByKey[key] = evidence
            touched.insert(key)
        }
        for (key, direction) in freeDirectionsByKey
        where ensureDepthEvidence(for: key) {
            var evidence = depthEvidenceByKey[key]!
            evidence.free = saturatingIncrement(evidence.free)
            evidence.directionMask |= UInt32(1) << UInt32(direction)
            depthEvidenceByKey[key] = evidence
            touched.insert(key)
        }
        for key in touched {
            try applyDepthState(key)
        }
    }

    func reportFeatureTransientUnavailable() {
        guard diagnostics.featureHealth != "failed" else { return }
        diagnostics.featureHealth = "transientUnavailable"
        diagnostics.featureTransientUnavailableCount += 1
    }

    func reportFeatureFailure() {
        diagnostics.featureHealth = "failed"
        diagnostics.featureFailureCount += 1
    }

    func reportDepthTransientUnavailable() {
        guard depthConfiguration != nil,
            diagnostics.depthHealth != "failed"
        else {
            return
        }
        diagnostics.depthHealth = "transientUnavailable"
        diagnostics.depthTransientUnavailableCount += 1
    }

    func reportDepthFailure() {
        guard let config = depthConfiguration,
            diagnostics.depthHealth != "failed"
        else {
            return
        }
        consecutiveDepthFailures += 1
        diagnostics.depthFailureCount += 1
        diagnostics.depthHealth =
            consecutiveDepthFailures >= config.terminalFailureThreshold
            ? "failed"
            : "transientUnavailable"
    }

    func snapshot() -> VisibilityGridSnapshot {
        let active = try! requireGroup()
        return VisibilityGridSnapshot(
            identity: active.identity,
            geometryRevision:
                geometryRevision +
                (
                    pendingGeometry.isEmpty && !snapshotRequired
                    ? 0
                    : 1
                ),
            stableKeys: visibleKeys.sorted(),
            supportByKey: supportByKey,
            diagnostics: currentDiagnostics()
        )
    }

    func takeGeometryDelta(
        nowNanoseconds: Int64
    ) -> VisibilityGridDelta? {
        guard let active = group else { return nil }
        if let inFlightDelta {
            return inFlightDelta
        }
        guard !pendingGeometry.isEmpty || snapshotRequired else {
            return nil
        }
        if let lastPublicationNanoseconds,
            (
                nowNanoseconds < lastPublicationNanoseconds ||
                    nowNanoseconds - lastPublicationNanoseconds <
                    Int64(
                        featureConfiguration.publishIntervalMilliseconds
                    ) * 1_000_000
            ) {
            return nil
        }
        let nextRevision = geometryRevision + 1
        let previousRevision = geometryRevision
        let reset = snapshotRequired
        let upserts =
            reset
                ? visibleKeys.sorted()
                : pendingGeometry.compactMap {
                    $0.value == .upsert ? $0.key : nil
                }.sorted()
        let removals =
            reset
                ? []
                : pendingGeometry.compactMap {
                    $0.value == .removal ? $0.key : nil
                }.sorted()
        geometryRevision = nextRevision
        pendingGeometry.removeAll(keepingCapacity: true)
        snapshotRequired = false
        lastPublicationNanoseconds = nowNanoseconds
        diagnostics.publishedDeltaCount += 1
        let delta = VisibilityGridDelta(
            identity: active.identity,
            baseGeometryRevision: previousRevision,
            geometryRevision: nextRevision,
            reset: reset,
            upsertKeys: upserts,
            removalKeys: removals,
            capacity: active.capacity,
            diagnostics: currentDiagnostics(publishing: true)
        )
        inFlightDelta = delta
        return delta
    }

    func acknowledgeGeometry(
        identity: VisibilityGridIdentity,
        acceptedGeometryRevision: Int64
    ) -> Bool {
        guard group?.identity == identity else { return false }
        guard let current = inFlightDelta else {
            return acceptedGeometryRevision == geometryRevision
        }
        guard current.geometryRevision ==
            acceptedGeometryRevision
        else {
            return false
        }
        inFlightDelta = nil
        diagnostics.geometryAcknowledgementCount += 1
        return true
    }

    func requestSnapshot(
        identity: VisibilityGridIdentity,
        receiverGeometryRevision: Int64,
        nowNanoseconds: Int64
    ) -> VisibilityGridDelta? {
        guard let active = group,
            active.identity == identity,
            receiverGeometryRevision >= 0
        else {
            return nil
        }
        let nextRevision =
            max(geometryRevision, receiverGeometryRevision) + 1
        geometryRevision = nextRevision
        pendingGeometry.removeAll(keepingCapacity: true)
        snapshotRequired = false
        lastPublicationNanoseconds = nowNanoseconds
        diagnostics.publishedDeltaCount += 1
        diagnostics.snapshotRecoveryCount += 1
        let delta = VisibilityGridDelta(
            identity: active.identity,
            baseGeometryRevision: receiverGeometryRevision,
            geometryRevision: nextRevision,
            reset: true,
            upsertKeys: visibleKeys.sorted(),
            removalKeys: [],
            capacity: active.capacity,
            diagnostics: currentDiagnostics(publishing: true)
        )
        inFlightDelta = delta
        return delta
    }

    private func promoteIfReady(
        _ track: Track,
        timestampNanoseconds: Int64
    ) {
        guard track.sampleCount >= featureConfiguration.candidateSamples,
            timestampNanoseconds - track.firstTimestampNanoseconds >=
                featureConfiguration.candidateSpanNanoseconds,
            hasLowVariance(track),
            let active = group,
            reservationCount() < active.capacity
        else {
            return
        }
        let coordinates = track.filtered.cellCoordinates(
            voxelSizeMeters: active.voxelSizeMeters
        )
        let key = packVisibilityGridKey(coordinates)
        track.stableKey = key
        track.stableCoordinates = coordinates
        attachSupport(key)
    }

    private func updateStableTrack(
        _ track: Track,
        position: VisibilityGridPoint,
        confidence: Double,
        timestampNanoseconds: Int64
    ) {
        let previous = track.filtered
        let dx = position.x - previous.x
        let dy = position.y - previous.y
        let dz = position.z - previous.z
        if dx * dx + dy * dy + dz * dz >=
            featureConfiguration.jumpResetMeters *
                featureConfiguration.jumpResetMeters {
            diagnostics.featureJumpResets += 1
            detachSupport(track.stableKey!)
            track.filtered = position
            track.sampleCount = 1
            track.sampleSums = [position.x, position.y, position.z]
            track.sampleSquareSums = [
                position.x * position.x,
                position.y * position.y,
                position.z * position.z
            ]
            track.firstTimestampNanoseconds = timestampNanoseconds
            track.lastTimestampNanoseconds = timestampNanoseconds
            track.stableKey = nil
            track.stableCoordinates = nil
            return
        }
        let alpha = min(0.50, max(0.15, 0.15 + 0.35 * confidence))
        track.filtered = interpolate(
            previous: previous,
            current: position,
            alpha: alpha
        )
        track.lastTimestampNanoseconds = timestampNanoseconds
        guard let active = group,
            let current = track.stableCoordinates,
            let previousKey = track.stableKey
        else {
            return
        }
        var next = current
        for axis in 0..<3 {
            let lower =
                Double(current[axis]) * active.voxelSizeMeters -
                featureConfiguration.relocationHysteresisMeters
            let upper =
                Double(current[axis] + 1) * active.voxelSizeMeters +
                featureConfiguration.relocationHysteresisMeters
            if track.filtered[axis] < lower ||
                track.filtered[axis] >= upper {
                next[axis] = Int(
                    floor(track.filtered[axis] / active.voxelSizeMeters)
                )
            }
        }
        let nextKey = packVisibilityGridKey(next)
        guard nextKey != previousKey else { return }
        diagnostics.featureMigrations += 1
        detachSupport(previousKey)
        attachSupport(nextKey)
        track.stableKey = nextKey
        track.stableCoordinates = next
    }

    private func attachSupport(_ key: UInt64) {
        supportByKey[key, default: 0] += 1
        if supportByKey[key] == 1,
            !restoredKeys.contains(key),
            depthEvidenceByKey[key] != nil {
            depthReservations -= 1
        }
        if var evidence = depthEvidenceByKey[key] {
            evidence.contradicted = false
            evidence.free = 0
            evidence.directionMask = 0
            depthEvidenceByKey[key] = evidence
        }
        refreshVisibility(key)
    }

    private func detachSupport(_ key: UInt64) {
        diagnostics.supportRemovals += 1
        let remaining = (supportByKey[key] ?? 1) - 1
        if remaining <= 0 {
            supportByKey[key] = nil
            if !restoredKeys.contains(key),
                depthEvidenceByKey[key] != nil {
                depthReservations += 1
            }
        } else {
            supportByKey[key] = remaining
        }
        refreshVisibility(key)
    }

    private func sanitizeAndBound(
        _ samples: [FeatureSample]
    ) -> [FeatureSample] {
        var bestByIdentifier: [UInt64: FeatureSample] = [:]
        for sample in samples {
            guard sample.isFinite,
                sample.confidence >=
                    featureConfiguration.minimumConfidence
            else {
                continue
            }
            if let current = bestByIdentifier[sample.identifier],
                current.confidence >= sample.confidence {
                continue
            }
            bestByIdentifier[sample.identifier] = sample
        }
        let accepted = bestByIdentifier.values
            .sorted { $0.identifier < $1.identifier }
            .prefix(featureConfiguration.maxFeaturesPerObservation)
        diagnostics.rejectedSamples +=
            Int64(samples.count - accepted.count)
        return Array(accepted)
    }

    private func addCandidateSample(
        _ track: Track,
        _ point: VisibilityGridPoint
    ) {
        track.sampleCount += 1
        for axis in 0..<3 {
            track.sampleSums[axis] += point[axis]
            track.sampleSquareSums[axis] += point[axis] * point[axis]
        }
    }

    private func hasLowVariance(_ track: Track) -> Bool {
        for axis in 0..<3 {
            let mean =
                track.sampleSums[axis] / Double(track.sampleCount)
            let variance = max(
                0,
                track.sampleSquareSums[axis] /
                    Double(track.sampleCount) -
                    mean * mean
            )
            if sqrt(variance) >
                featureConfiguration
                    .candidateMaximumStandardDeviationMeters {
                return false
            }
        }
        return true
    }

    private func interpolate(
        previous: VisibilityGridPoint,
        current: VisibilityGridPoint,
        alpha: Double
    ) -> VisibilityGridPoint {
        VisibilityGridPoint(
            x: previous.x + alpha * (current.x - previous.x),
            y: previous.y + alpha * (current.y - previous.y),
            z: previous.z + alpha * (current.z - previous.z)
        )
    }

    private func expireCandidates(_ timestampNanoseconds: Int64) {
        let before = tracks.count
        tracks = tracks.filter {
            $0.value.stableKey != nil ||
            timestampNanoseconds -
                $0.value.lastTimestampNanoseconds <=
                featureConfiguration.candidateExpiryNanoseconds
        }
        diagnostics.candidateExpirations += Int64(before - tracks.count)
    }

    private func isQuantizable(_ point: VisibilityGridPoint) -> Bool {
        guard let active = group, point.isFinite else { return false }
        return point.cellCoordinates(
            voxelSizeMeters: active.voxelSizeMeters
        ).allSatisfy {
            (voxelCoordinateMinimum...voxelCoordinateMaximum)
                .contains($0)
        }
    }

    private func key(for point: VisibilityGridPoint) -> UInt64 {
        packVisibilityGridKey(
            point.cellCoordinates(
                voxelSizeMeters: group!.voxelSizeMeters
            )
        )
    }

    private func voxelCenter(_ key: UInt64) -> VisibilityGridPoint {
        let coordinates = unpackVisibilityGridKey(key)
        let voxel = group!.voxelSizeMeters
        let half = voxel / 2
        return VisibilityGridPoint(
            x: Double(coordinates[0]) * voxel + half,
            y: Double(coordinates[1]) * voxel + half,
            z: Double(coordinates[2]) * voxel + half
        )
    }

    private func ensureDepthEvidence(for key: UInt64) -> Bool {
        if depthEvidenceByKey[key] != nil {
            return true
        }
        let requiresReservation =
            !restoredKeys.contains(key) &&
            supportByKey[key, default: 0] == 0
        if requiresReservation,
            reservationCount() >= (group?.capacity ?? 0) {
            return false
        }
        if estimatedStateBytes() + 32 >
            visibilityGridCoreMemoryBudgetBytes {
            return false
        }
        depthEvidenceByKey[key] = DepthEvidence()
        if requiresReservation {
            depthReservations += 1
        }
        return true
    }

    private func applyDepthState(_ key: UInt64) throws {
        let config = try requireDepthConfiguration()
        guard var evidence = depthEvidenceByKey[key] else { return }
        if evidence.contradicted {
            if evidence.occupied >= config.occupiedEvidenceToShow {
                evidence.contradicted = false
                diagnostics.restoredVoxels += 1
                evidence.free = 0
                evidence.directionMask = 0
            }
        } else if evidence.free >= config.freeEvidenceToCarve,
            Int(evidence.free) - Int(evidence.occupied) >=
                Int(config.freeEvidenceMargin),
            hasSeparatedDirections(
                evidence.directionMask,
                required: config.separatedDirectionBinsRequired
        ) {
            evidence.contradicted = true
            diagnostics.carvedVoxels += 1
            evidence.occupied = 0
        }
        depthEvidenceByKey[key] = evidence
        refreshVisibility(key)
    }

    private func isVisible(_ key: UInt64) -> Bool {
        if depthEvidenceByKey[key]?.contradicted == true {
            return false
        }
        if restoredKeys.contains(key) ||
            supportByKey[key, default: 0] > 0 {
            return true
        }
        guard let depthConfiguration,
            let evidence = depthEvidenceByKey[key]
        else {
            return false
        }
        return evidence.occupied >=
            depthConfiguration.occupiedEvidenceToShow
    }

    private func refreshVisibility(_ key: UInt64) {
        let shouldBeVisible = isVisible(key)
        let wasVisible = visibleKeys.contains(key)
        guard shouldBeVisible != wasVisible else { return }
        if shouldBeVisible {
            visibleKeys.insert(key)
            recordGeometry(key, state: .upsert)
        } else {
            visibleKeys.remove(key)
            recordGeometry(key, state: .removal)
        }
    }

    private func recordGeometry(
        _ key: UInt64,
        state: PendingGeometryState
    ) {
        guard !snapshotRequired, let active = group else { return }
        if pendingGeometry[key] != nil {
            diagnostics.coalescedGeometryChanges += 1
        }
        pendingGeometry[key] = state
        if pendingGeometry.count > active.capacity {
            pendingGeometry.removeAll(keepingCapacity: true)
            snapshotRequired = true
        }
    }

    private func saturatingIncrement(_ value: UInt8) -> UInt8 {
        value == .max ? .max : value + 1
    }

    private func directionBin(
        camera: VisibilityGridPoint,
        endpoint: VisibilityGridPoint
    ) -> Int {
        let x = endpoint.x - camera.x
        let y = endpoint.y - camera.y
        let z = endpoint.z - camera.z
        let length = sqrt(x * x + y * y + z * z)
        let azimuth = atan2(x, -z)
        let azimuthBin = min(
            7,
            max(0, Int(floor((azimuth + .pi) / (2 * .pi) * 8)))
        )
        let elevation = asin(min(1, max(-1, y / length)))
        let elevationBin =
            elevation < -.pi / 8
            ? 0
            : elevation > .pi / 8
                ? 2
                : 1
        return elevationBin * 8 + azimuthBin
    }

    private func hasSeparatedDirections(
        _ mask: UInt32,
        required: Int
    ) -> Bool {
        let bins = (0..<24).filter {
            mask & (UInt32(1) << UInt32($0)) != 0
        }
        guard bins.count >= required else { return false }
        guard required > 1 else { return true }
        for first in bins {
            for second in bins where first != second {
                if directionBinDot(first, second) <= cos(.pi / 6) {
                    return true
                }
            }
        }
        return false
    }

    private func directionBinDot(_ first: Int, _ second: Int) -> Double {
        func vector(_ bin: Int) -> [Double] {
            let elevation: Double
            switch bin / 8 {
            case 0:
                elevation = -.pi / 4
            case 1:
                elevation = 0
            default:
                elevation = .pi / 4
            }
            let azimuth =
                -.pi + (Double(bin % 8) + 0.5) * .pi / 4
            let horizontal = cos(elevation)
            return [
                sin(azimuth) * horizontal,
                sin(elevation),
                -cos(azimuth) * horizontal
            ]
        }
        let left = vector(first)
        let right = vector(second)
        return zip(left, right)
            .map { $0.0 * $0.1 }
            .reduce(0, +)
    }

    private func traverseFreeKeys(
        camera: VisibilityGridPoint,
        endpoint: VisibilityGridPoint,
        maximumVisits: Int,
        safetyBandMeters: Double
    ) -> [UInt64] {
        guard maximumVisits > 0, let active = group else { return [] }
        let dx = endpoint.x - camera.x
        let dy = endpoint.y - camera.y
        let dz = endpoint.z - camera.z
        let distance = sqrt(dx * dx + dy * dy + dz * dz)
        let maximumDistance = distance - safetyBandMeters
        guard maximumDistance.isFinite, maximumDistance > 0 else {
            return []
        }
        let unit = [dx / distance, dy / distance, dz / distance]
        let voxel = active.voxelSizeMeters
        var cells = camera.cellCoordinates(voxelSizeMeters: voxel)
        let steps = unit.map { $0 == 0 ? 0 : ($0 > 0 ? 1 : -1) }

        func firstBoundaryDistance(
            coordinate: Double,
            cell: Int,
            step: Int,
            unit: Double
        ) -> Double {
            guard step != 0 else { return .infinity }
            let boundary =
                step > 0
                ? Double(cell + 1) * voxel
                : Double(cell) * voxel
            return (boundary - coordinate) / unit
        }

        var next = [
            firstBoundaryDistance(
                coordinate: camera.x,
                cell: cells[0],
                step: steps[0],
                unit: unit[0]
            ),
            firstBoundaryDistance(
                coordinate: camera.y,
                cell: cells[1],
                step: steps[1],
                unit: unit[1]
            ),
            firstBoundaryDistance(
                coordinate: camera.z,
                cell: cells[2],
                step: steps[2],
                unit: unit[2]
            )
        ]
        let delta = unit.map {
            $0 == 0 ? Double.infinity : voxel / abs($0)
        }
        var keys: [UInt64] = []
        while keys.count < maximumVisits {
            let crossing = next.min()!
            if crossing >= maximumDistance {
                break
            }
            let crossed = (0..<3).filter { next[$0] == crossing }
            let old = cells
            for axis in crossed {
                cells[axis] += steps[axis]
                next[axis] += delta[axis]
            }
            for combination in 1..<(1 << crossed.count) {
                var candidate = old
                for (bit, axis) in crossed.enumerated()
                where combination & (1 << bit) != 0 {
                    candidate[axis] += steps[axis]
                }
                if candidate.allSatisfy({
                    (voxelCoordinateMinimum...voxelCoordinateMaximum)
                        .contains($0)
                }) {
                    keys.append(packVisibilityGridKey(candidate))
                    if keys.count >= maximumVisits {
                        break
                    }
                }
            }
        }
        return keys
    }

    private func reservationCount() -> Int {
        restoredKeys.count +
            tracks.values.filter { $0.stableKey != nil }.count +
            depthReservations
    }

    private func estimatedStateBytes() -> Int64 {
        8_192 +
            Int64(tracks.count * 192) +
            Int64(supportByKey.count * 128) +
            Int64(restoredKeys.count * 160) +
            Int64(pendingGeometry.count * 64) +
            Int64(
                (
                    (inFlightDelta?.upsertKeys.count ?? 0) +
                    (inFlightDelta?.removalKeys.count ?? 0)
                ) * 32
            ) +
            Int64(depthEvidenceByKey.count * 32)
    }

    private func refreshDiagnostics() {
        diagnostics.candidateTracks =
            tracks.values.filter { $0.stableKey == nil }.count
        diagnostics.stableTracks =
            tracks.values.filter { $0.stableKey != nil }.count
        diagnostics.stableVoxels = visibleKeys.count
        diagnostics.estimatedStateBytes = estimatedStateBytes()
        diagnostics.geometryRevision = geometryRevision
        diagnostics.pendingGeometryKeys = pendingGeometry.count
        diagnostics.unacknowledgedGeometryCallbacks =
            inFlightDelta == nil ? 0 : 1
        diagnostics.rendererRows = visibleKeys.count
        diagnostics.rendererFreeRows =
            max(0, (group?.capacity ?? 0) - visibleKeys.count)
    }

    private func currentDiagnostics(
        publishing: Bool = false
    ) -> VisibilityGridDiagnostics {
        refreshDiagnostics()
        if publishing {
            diagnostics.unacknowledgedGeometryCallbacks = 1
        }
        return diagnostics
    }

    private final class BoundedLatencySamples {
        private let capacity: Int
        private var values: [Int64] = []
        private var next = 0

        init(capacity: Int = 256) {
            self.capacity = capacity
            values.reserveCapacity(capacity)
        }

        func clear() {
            values.removeAll(keepingCapacity: true)
            next = 0
        }

        func record(_ value: Int64) {
            let bounded = max(0, value)
            if values.count < capacity {
                values.append(bounded)
            } else {
                values[next] = bounded
                next = (next + 1) % capacity
            }
        }

        func p95() -> Int64 {
            guard !values.isEmpty else { return 0 }
            let sorted = values.sorted()
            let index =
                min(
                    sorted.count - 1,
                    max(0, (sorted.count * 95 + 99) / 100 - 1)
                )
            return sorted[index]
        }
    }

    private func requireGroup() throws
        -> VisibilityGridGroupConfiguration {
        guard let group else {
            throw VisibilityGridContractError.notInitialized
        }
        return group
    }

    private func requireDepthConfiguration() throws
        -> VisibilityGridDepthConfiguration {
        guard let depthConfiguration else {
            throw VisibilityGridContractError.invalidArgument(
                "Scene depth is unsupported"
            )
        }
        return depthConfiguration
    }

    private func requireIdentity(
        groupGeneration: Int64,
        sessionGeneration: Int64
    ) throws {
        let active = try requireGroup()
        guard active.groupGeneration == groupGeneration,
            active.sessionGeneration == sessionGeneration
        else {
            throw VisibilityGridContractError.staleIdentity
        }
    }
}
