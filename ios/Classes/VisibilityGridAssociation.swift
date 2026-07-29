import Foundation

struct UnassociatedFeatureSample {
    let world: VisibilityGridPoint
    let confidence: Double
}

struct VisibilityGridAssociationResult {
    let samples: [FeatureSample]
    let rejectedSamples: Int
}

final class BoundedFeatureAssociator {
    private static let memoryBudgetBytes = 3 * 1024 * 1024
    // Covers the persistent dictionary row plus its predicted-cell copy.
    private static let estimatedTrackBytes = 256

    private struct AssociationCell: Hashable {
        let x: Int
        let y: Int
        let z: Int
    }

    private struct Track {
        let identifier: UInt64
        var previous: VisibilityGridPoint?
        var current: VisibilityGridPoint
        var timestampNanoseconds: Int64

        func predicted(at timestampNanoseconds: Int64) -> VisibilityGridPoint {
            guard let previous,
                timestampNanoseconds > self.timestampNanoseconds
            else {
                return current
            }
            return VisibilityGridPoint(
                x: current.x + current.x - previous.x,
                y: current.y + current.y - previous.y,
                z: current.z + current.z - previous.z
            )
        }
    }

    private struct Candidate {
        let distanceSquared: Double
        let trackIdentifier: UInt64
        let sampleIndex: Int
    }

    let acceptedCapacity: Int
    private let gateMeters: Double
    private let expiryNanoseconds: Int64
    private let maximumTracksPerCell = 16
    private let maximumCandidatesPerSample = 16
    private var nextIdentifier: UInt64 = 1
    private var tracks: [UInt64: Track] = [:]

    init(
        capacity: Int,
        gateMeters: Double = 0.15,
        expiryNanoseconds: Int64 = 2_000_000_000
    ) throws {
        guard capacity > 0,
            gateMeters.isFinite && gateMeters > 0,
            expiryNanoseconds > 0
        else {
            throw VisibilityGridContractError.invalidArgument(
                "Invalid feature-association bounds"
            )
        }
        acceptedCapacity = Self.acceptedCapacity(for: capacity)
        self.gateMeters = gateMeters
        self.expiryNanoseconds = expiryNanoseconds
        tracks.reserveCapacity(acceptedCapacity)
    }

    static func acceptedCapacity(for requested: Int) -> Int {
        min(requested, memoryBudgetBytes / estimatedTrackBytes)
    }

    func reset() {
        tracks.removeAll(keepingCapacity: true)
        nextIdentifier = 1
    }

    func associate(
        timestampNanoseconds: Int64,
        samples input: [UnassociatedFeatureSample]
    ) -> VisibilityGridAssociationResult {
        guard timestampNanoseconds >= 0 else {
            return VisibilityGridAssociationResult(
                samples: [],
                rejectedSamples: input.count
            )
        }
        tracks = tracks.filter {
            timestampNanoseconds >= $0.value.timestampNanoseconds &&
                timestampNanoseconds - $0.value.timestampNanoseconds <=
                expiryNanoseconds
        }
        let samples = input
            .filter { $0.world.isFinite && $0.confidence.isFinite }
            .sorted {
                if $0.world.x != $1.world.x {
                    return $0.world.x < $1.world.x
                }
                if $0.world.y != $1.world.y {
                    return $0.world.y < $1.world.y
                }
                if $0.world.z != $1.world.z {
                    return $0.world.z < $1.world.z
                }
                return $0.confidence > $1.confidence
            }
        let invalidCount = input.count - samples.count
        let gateSquared = gateMeters * gateMeters
        var tracksByPredictedCell: [AssociationCell: [Track]] = [:]
        for track in tracks.values {
            let predicted = track.predicted(at: timestampNanoseconds)
            tracksByPredictedCell[cell(for: predicted), default: []]
                .append(track)
        }
        for key in Array(tracksByPredictedCell.keys) {
            tracksByPredictedCell[key]?.sort {
                $0.identifier < $1.identifier
            }
            if tracksByPredictedCell[key]!.count >
                maximumTracksPerCell {
                tracksByPredictedCell[key] =
                    Array(
                        tracksByPredictedCell[key]!
                            .prefix(maximumTracksPerCell)
                    )
            }
        }
        var candidates: [Candidate] = []
        candidates.reserveCapacity(
            samples.count * maximumCandidatesPerSample
        )
        for index in samples.indices {
            let sampleCell = cell(for: samples[index].world)
            var localCandidates: [Candidate] = []
            localCandidates.reserveCapacity(maximumCandidatesPerSample)
            for xOffset in -1...1 {
                for yOffset in -1...1 {
                    for zOffset in -1...1 {
                        let nearby = AssociationCell(
                            x: sampleCell.x + xOffset,
                            y: sampleCell.y + yOffset,
                            z: sampleCell.z + zOffset
                        )
                        for track in tracksByPredictedCell[nearby] ?? [] {
                            let predicted = track.predicted(
                                at: timestampNanoseconds
                            )
                            let distance = squaredDistance(
                                predicted,
                                samples[index].world
                            )
                            if distance <= gateSquared {
                                localCandidates.append(
                                    Candidate(
                                        distanceSquared: distance,
                                        trackIdentifier: track.identifier,
                                        sampleIndex: index
                                    )
                                )
                            }
                        }
                    }
                }
            }
            localCandidates.sort(by: candidatePrecedes)
            candidates.append(
                contentsOf:
                    localCandidates.prefix(maximumCandidatesPerSample)
            )
        }
        candidates.sort(by: candidatePrecedes)
        var assignedTracks: Set<UInt64> = []
        var assignedSamples: Set<Int> = []
        var identifiersBySample: [Int: UInt64] = [:]
        for candidate in candidates
        where !assignedTracks.contains(candidate.trackIdentifier) &&
            !assignedSamples.contains(candidate.sampleIndex) {
            assignedTracks.insert(candidate.trackIdentifier)
            assignedSamples.insert(candidate.sampleIndex)
            identifiersBySample[candidate.sampleIndex] =
                candidate.trackIdentifier
        }
        var capacityRejected = 0
        var currentCellCounts: [AssociationCell: Int] = [:]
        for track in tracks.values {
            currentCellCounts[cell(for: track.current), default: 0] += 1
        }
        var newTrackCount = 0
        for index in samples.indices where identifiersBySample[index] == nil {
            guard tracks.count + newTrackCount < acceptedCapacity else {
                capacityRejected += 1
                continue
            }
            let sampleCell = cell(for: samples[index].world)
            guard currentCellCounts[sampleCell, default: 0] <
                maximumTracksPerCell
            else {
                capacityRejected += 1
                continue
            }
            while tracks[nextIdentifier] != nil {
                nextIdentifier &+= 1
            }
            identifiersBySample[index] = nextIdentifier
            currentCellCounts[sampleCell, default: 0] += 1
            newTrackCount += 1
            nextIdentifier &+= 1
        }

        var associated: [FeatureSample] = []
        associated.reserveCapacity(identifiersBySample.count)
        for index in samples.indices {
            guard let identifier = identifiersBySample[index] else {
                continue
            }
            let sample = samples[index]
            let old = tracks[identifier]
            tracks[identifier] = Track(
                identifier: identifier,
                previous: old?.current,
                current: sample.world,
                timestampNanoseconds: timestampNanoseconds
            )
            associated.append(
                FeatureSample(
                    identifier: identifier,
                    world: sample.world,
                    confidence: sample.confidence
                )
            )
        }
        return VisibilityGridAssociationResult(
            samples: associated,
            rejectedSamples: invalidCount + capacityRejected
        )
    }

    private func candidatePrecedes(
        _ left: Candidate,
        _ right: Candidate
    ) -> Bool {
        if left.distanceSquared != right.distanceSquared {
            return left.distanceSquared < right.distanceSquared
        }
        if left.trackIdentifier != right.trackIdentifier {
            return left.trackIdentifier < right.trackIdentifier
        }
        return left.sampleIndex < right.sampleIndex
    }

    private func squaredDistance(
        _ left: VisibilityGridPoint,
        _ right: VisibilityGridPoint
    ) -> Double {
        let x = left.x - right.x
        let y = left.y - right.y
        let z = left.z - right.z
        return x * x + y * y + z * z
    }

    private func cell(for point: VisibilityGridPoint) -> AssociationCell {
        AssociationCell(
            x: Int(floor(point.x / gateMeters)),
            y: Int(floor(point.y / gateMeters)),
            z: Int(floor(point.z / gateMeters))
        )
    }
}
