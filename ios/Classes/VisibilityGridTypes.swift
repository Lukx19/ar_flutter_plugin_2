import Foundation

let visibilityGridWireVersion = "visibility_grid_wire_v1"
let visibilityGridMemoryBudgetBytes: Int64 = 16 * 1024 * 1024
let visibilityGridCoreMemoryBudgetBytes: Int64 = 6 * 1024 * 1024

private let voxelCoordinateBias = 1 << 20
let voxelCoordinateMinimum = -(1 << 20)
let voxelCoordinateMaximum = (1 << 20) - 1

enum VisibilityGridContractError: Error, Equatable {
    case invalidArgument(String)
    case versionMismatch
    case staleIdentity
    case staleGroup
    case staleSession
    case staleRevision
    case staleVisibility
    case syntheticForbidden
    case notInitialized
    case capacityExceeded
    case disposed
}

struct VisibilityGridPoint: Equatable {
    let x: Double
    let y: Double
    let z: Double

    init(x: Double, y: Double, z: Double) {
        self.x = x
        self.y = y
        self.z = z
    }

    init(_ values: [Double]) {
        precondition(values.count == 3)
        self.init(x: values[0], y: values[1], z: values[2])
    }

    subscript(axis: Int) -> Double {
        switch axis {
        case 0:
            return x
        case 1:
            return y
        default:
            return z
        }
    }

    var isFinite: Bool {
        x.isFinite && y.isFinite && z.isFinite
    }

    func cellCoordinates(voxelSizeMeters: Double) -> [Int] {
        [
            Int(floor(x / voxelSizeMeters)),
            Int(floor(y / voxelSizeMeters)),
            Int(floor(z / voxelSizeMeters))
        ]
    }

    static func transform(
        matrix: [Double],
        point: VisibilityGridPoint
    ) -> VisibilityGridPoint {
        precondition(matrix.count == 16)
        return VisibilityGridPoint(
            x:
                matrix[0] * point.x +
                matrix[4] * point.y +
                matrix[8] * point.z +
                matrix[12],
            y:
                matrix[1] * point.x +
                matrix[5] * point.y +
                matrix[9] * point.z +
                matrix[13],
            z:
                matrix[2] * point.x +
                matrix[6] * point.y +
                matrix[10] * point.z +
                matrix[14]
        )
    }
}

func identityVisibilityGridTransform() -> [Double] {
    [
        1, 0, 0, 0,
        0, 1, 0, 0,
        0, 0, 1, 0,
        0, 0, 0, 1
    ]
}

func packVisibilityGridKey(_ coordinates: [Int]) -> UInt64 {
    precondition(coordinates.count == 3)
    return packVisibilityGridKey(
        x: coordinates[0],
        y: coordinates[1],
        z: coordinates[2]
    )
}

func packVisibilityGridKey(x: Int, y: Int, z: Int) -> UInt64 {
    precondition((voxelCoordinateMinimum...voxelCoordinateMaximum).contains(x))
    precondition((voxelCoordinateMinimum...voxelCoordinateMaximum).contains(y))
    precondition((voxelCoordinateMinimum...voxelCoordinateMaximum).contains(z))
    return
        (UInt64(x + voxelCoordinateBias) << 42) |
        (UInt64(y + voxelCoordinateBias) << 21) |
        UInt64(z + voxelCoordinateBias)
}

func unpackVisibilityGridKey(_ key: UInt64) -> [Int] {
    let mask = UInt64((1 << 21) - 1)
    return [
        Int((key >> 42) & mask) - voxelCoordinateBias,
        Int((key >> 21) & mask) - voxelCoordinateBias,
        Int(key & mask) - voxelCoordinateBias
    ]
}

struct VisibilityGridIdentity: Equatable {
    let groupId: String
    let groupGeneration: Int64
    let sessionGeneration: Int64
}

struct VisibilityGridFeatureConfiguration {
    let stableVoxelCapacity: Int
    let featureTrackCapacity: Int
    let maxFeaturesPerObservation: Int
    let publishIntervalMilliseconds: Int
    let minimumConfidence: Double
    let candidateSamples: Int
    let candidateSpanNanoseconds: Int64
    let candidateMaximumStandardDeviationMeters: Double
    let relocationHysteresisMeters: Double
    let jumpResetMeters: Double
    let candidateExpiryNanoseconds: Int64

    init(
        stableVoxelCapacity: Int = 100_000,
        featureTrackCapacity: Int = 200_000,
        maxFeaturesPerObservation: Int = 2_000,
        publishIntervalMilliseconds: Int = 500,
        minimumConfidence: Double = 0.30,
        candidateSamples: Int = 5,
        candidateSpanNanoseconds: Int64 = 500_000_000,
        candidateMaximumStandardDeviationMeters: Double = 0.05,
        relocationHysteresisMeters: Double = 0.015,
        jumpResetMeters: Double = 0.30,
        candidateExpiryNanoseconds: Int64 = 2_000_000_000
    ) throws {
        guard (1...100_000).contains(stableVoxelCapacity),
            (1...200_000).contains(featureTrackCapacity),
            (1...2_000).contains(maxFeaturesPerObservation),
            publishIntervalMilliseconds >= 500,
            minimumConfidence.isFinite &&
                (0...1).contains(minimumConfidence),
            candidateSamples > 0,
            candidateSpanNanoseconds >= 0,
            candidateMaximumStandardDeviationMeters.isFinite &&
                candidateMaximumStandardDeviationMeters >= 0,
            relocationHysteresisMeters.isFinite &&
                relocationHysteresisMeters >= 0,
            jumpResetMeters.isFinite && jumpResetMeters > 0,
            candidateExpiryNanoseconds > 0
        else {
            throw VisibilityGridContractError.invalidArgument(
                "Invalid feature configuration"
            )
        }
        self.stableVoxelCapacity = stableVoxelCapacity
        self.featureTrackCapacity = featureTrackCapacity
        self.maxFeaturesPerObservation = maxFeaturesPerObservation
        self.publishIntervalMilliseconds = publishIntervalMilliseconds
        self.minimumConfidence = minimumConfidence
        self.candidateSamples = candidateSamples
        self.candidateSpanNanoseconds = candidateSpanNanoseconds
        self.candidateMaximumStandardDeviationMeters =
            candidateMaximumStandardDeviationMeters
        self.relocationHysteresisMeters = relocationHysteresisMeters
        self.jumpResetMeters = jumpResetMeters
        self.candidateExpiryNanoseconds = candidateExpiryNanoseconds
    }
}

struct VisibilityGridDepthConfiguration {
    let minimumConfidence: UInt8
    let safetyBandMeters: Double
    let minimumDepthMeters: Double
    let maximumDepthMeters: Double
    let occupiedEvidenceToShow: UInt8
    let freeEvidenceToCarve: UInt8
    let freeEvidenceMargin: UInt8
    let separatedDirectionBinsRequired: Int
    let maxAcceptedPixelsPerObservation: Int
    let maxRayVisitsPerObservation: Int
    let terminalFailureThreshold: Int

    init(
        minimumConfidence: UInt8 = 128,
        safetyBandMeters: Double = 0.15,
        minimumDepthMeters: Double = 0.20,
        maximumDepthMeters: Double = 8.0,
        occupiedEvidenceToShow: UInt8 = 4,
        freeEvidenceToCarve: UInt8 = 8,
        freeEvidenceMargin: UInt8 = 4,
        separatedDirectionBinsRequired: Int = 2,
        maxAcceptedPixelsPerObservation: Int = 4_096,
        maxRayVisitsPerObservation: Int = 65_536,
        terminalFailureThreshold: Int = 3
    ) throws {
        guard safetyBandMeters.isFinite && safetyBandMeters >= 0,
            minimumDepthMeters.isFinite && minimumDepthMeters > 0,
            maximumDepthMeters.isFinite &&
                maximumDepthMeters > minimumDepthMeters,
            (1...24).contains(separatedDirectionBinsRequired),
            (1...4_096).contains(maxAcceptedPixelsPerObservation),
            (1...65_536).contains(maxRayVisitsPerObservation),
            terminalFailureThreshold > 0
        else {
            throw VisibilityGridContractError.invalidArgument(
                "Invalid depth configuration"
            )
        }
        self.minimumConfidence = minimumConfidence
        self.safetyBandMeters = safetyBandMeters
        self.minimumDepthMeters = minimumDepthMeters
        self.maximumDepthMeters = maximumDepthMeters
        self.occupiedEvidenceToShow = occupiedEvidenceToShow
        self.freeEvidenceToCarve = freeEvidenceToCarve
        self.freeEvidenceMargin = freeEvidenceMargin
        self.separatedDirectionBinsRequired =
            separatedDirectionBinsRequired
        self.maxAcceptedPixelsPerObservation =
            maxAcceptedPixelsPerObservation
        self.maxRayVisitsPerObservation = maxRayVisitsPerObservation
        self.terminalFailureThreshold = terminalFailureThreshold
    }
}

struct VisibilityGridGroupConfiguration {
    let groupId: String
    let groupGeneration: Int64
    let sessionGeneration: Int64
    let voxelSizeMeters: Double
    let capacity: Int
    let groupFromWorldGL: [Double]
    let worldFromGroupGL: [Double]
    let restoredGeometryRevision: Int64
    let restoredVisibilityRevision: Int64
    let restoredKeys: [UInt64]

    var identity: VisibilityGridIdentity {
        VisibilityGridIdentity(
            groupId: groupId,
            groupGeneration: groupGeneration,
            sessionGeneration: sessionGeneration
        )
    }

    init(
        groupId: String,
        groupGeneration: Int64,
        sessionGeneration: Int64,
        voxelSizeMeters: Double,
        capacity: Int,
        groupFromWorldGL: [Double],
        worldFromGroupGL: [Double] = identityVisibilityGridTransform(),
        restoredGeometryRevision: Int64 = 0,
        restoredVisibilityRevision: Int64 = 0,
        restoredKeys: [UInt64] = []
    ) throws {
        guard !groupId.isEmpty,
            groupGeneration >= 0,
            sessionGeneration >= 0,
            voxelSizeMeters.isFinite && voxelSizeMeters > 0,
            (1...100_000).contains(capacity),
            groupFromWorldGL.count == 16,
            worldFromGroupGL.count == 16,
            groupFromWorldGL.allSatisfy({ $0.isFinite }),
            worldFromGroupGL.allSatisfy({ $0.isFinite }),
            areInverseTransforms(groupFromWorldGL, worldFromGroupGL),
            restoredGeometryRevision >= 0,
            restoredVisibilityRevision >= 0,
            Set(restoredKeys).count == restoredKeys.count,
            restoredKeys.allSatisfy({ $0 <= UInt64(Int64.max) }),
            restoredKeys.count <= capacity,
            restoredKeys.isEmpty || restoredGeometryRevision > 0
        else {
            throw VisibilityGridContractError.invalidArgument(
                "Invalid group configuration"
            )
        }
        self.groupId = groupId
        self.groupGeneration = groupGeneration
        self.sessionGeneration = sessionGeneration
        self.voxelSizeMeters = voxelSizeMeters
        self.capacity = capacity
        self.groupFromWorldGL = groupFromWorldGL
        self.worldFromGroupGL = worldFromGroupGL
        self.restoredGeometryRevision = restoredGeometryRevision
        self.restoredVisibilityRevision = restoredVisibilityRevision
        self.restoredKeys = restoredKeys
    }
}

private func areInverseTransforms(
    _ first: [Double],
    _ second: [Double]
) -> Bool {
    for row in 0..<4 {
        for column in 0..<4 {
            var actual = 0.0
            for index in 0..<4 {
                actual +=
                    first[index * 4 + row] *
                    second[column * 4 + index]
            }
            let expected = row == column ? 1.0 : 0.0
            if abs(actual - expected) > 1e-6 {
                return false
            }
        }
    }
    return true
}

struct FeatureSample {
    let identifier: UInt64
    let world: VisibilityGridPoint
    let confidence: Double

    var isFinite: Bool {
        world.isFinite && confidence.isFinite
    }
}

struct FeatureObservation {
    let timestampNanoseconds: Int64
    let groupGeneration: Int64
    let sessionGeneration: Int64
    let samples: [FeatureSample]
    let sourceRejectedSamples: Int

    init(
        timestampNanoseconds: Int64,
        groupGeneration: Int64,
        sessionGeneration: Int64,
        samples: [FeatureSample],
        sourceRejectedSamples: Int = 0
    ) {
        precondition(timestampNanoseconds >= 0)
        precondition(sourceRejectedSamples >= 0)
        self.timestampNanoseconds = timestampNanoseconds
        self.groupGeneration = groupGeneration
        self.sessionGeneration = sessionGeneration
        self.samples = samples
        self.sourceRejectedSamples = sourceRejectedSamples
    }
}

struct DepthIntrinsics {
    let fx: Double
    let fy: Double
    let cx: Double
    let cy: Double
}

struct DepthPixelSample {
    let x: Int
    let y: Int
    let depthMeters: Double
    let confidence: UInt8
}

struct DepthObservation {
    let timestampNanoseconds: Int64
    let groupGeneration: Int64
    let sessionGeneration: Int64
    let tracking: Bool
    let width: Int
    let height: Int
    let samples: [DepthPixelSample]
    let sourceRejectedPixels: Int
    let intrinsics: DepthIntrinsics
    let worldFromCameraGL: [Double]
}

struct VisibilityGridDiagnostics {
    var candidateTracks = 0
    var stableTracks = 0
    var stableVoxels = 0
    var featureTrackCapacity = 0
    var stableVoxelCapacity = 0
    var featureObservationCount: Int64 = 0
    var featureMigrations: Int64 = 0
    var featureJumpResets: Int64 = 0
    var candidateExpirations: Int64 = 0
    var supportRemovals: Int64 = 0
    var acceptedSamples: Int64 = 0
    var rejectedSamples: Int64 = 0
    var capacityRejectedCandidates: Int64 = 0
    var featureHealth = "configured"
    var featureTransientUnavailableCount: Int64 = 0
    var featureFailureCount: Int64 = 0
    var lastFeatureFusionNanoseconds: Int64 = 0
    var maxFeatureFusionNanoseconds: Int64 = 0
    var featureFusionP95Nanoseconds: Int64 = 0
    var estimatedStateBytes: Int64 = 0
    var depthHealth = "unsupported"
    var depthObservationCount: Int64 = 0
    var depthAcceptedPixels: Int64 = 0
    var depthRejectedPixels: Int64 = 0
    var depthCapacityRejectedPixels: Int64 = 0
    var depthRayVisits: Int64 = 0
    var carvedVoxels: Int64 = 0
    var restoredVoxels: Int64 = 0
    var depthTransientUnavailableCount: Int64 = 0
    var depthFailureCount: Int64 = 0
    var lastDepthFusionNanoseconds: Int64 = 0
    var maxDepthFusionNanoseconds: Int64 = 0
    var depthFusionP95Nanoseconds: Int64 = 0
    var callbackCopyP95Nanoseconds: Int64 = 0
    var coalescedFeatureObservations: Int64 = 0
    var coalescedDepthObservations: Int64 = 0
    var coalescedGeometryChanges: Int64 = 0
    var geometryRevision: Int64 = 0
    var pendingGeometryKeys = 0
    var unacknowledgedGeometryCallbacks = 0
    var publishedDeltaCount: Int64 = 0
    var snapshotRecoveryCount: Int64 = 0
    var geometryAcknowledgementCount: Int64 = 0
    var rendererRows = 0
    var rendererFreeRows = 0

    func health(renderer: String = "configured") -> [String: String] {
        let total: String
        if featureHealth == "failed" &&
            (depthHealth == "failed" || depthHealth == "unsupported") {
            total = "failed"
        } else if depthHealth == "failed" {
            total = "featureOnly"
        } else {
            total = "healthy"
        }
        return [
            "feature": featureHealth,
            "depth": depthHealth,
            "renderer": renderer,
            "totalGrid": total
        ]
    }

    func wireMap() -> [String: Any] {
        [
            "candidateTracks": candidateTracks,
            "stableTracks": stableTracks,
            "stableVoxels": stableVoxels,
            "featureTrackCapacity": featureTrackCapacity,
            "stableVoxelCapacity": stableVoxelCapacity,
            "featureObservationCount": featureObservationCount,
            "featureMigrations": featureMigrations,
            "featureJumpResets": featureJumpResets,
            "candidateExpirations": candidateExpirations,
            "supportRemovals": supportRemovals,
            "acceptedSamples": acceptedSamples,
            "rejectedSamples": rejectedSamples,
            "capacityRejectedCandidates": capacityRejectedCandidates,
            "featureTransientUnavailableCount":
                featureTransientUnavailableCount,
            "featureFailureCount": featureFailureCount,
            "lastFeatureFusionNs": lastFeatureFusionNanoseconds,
            "maxFeatureFusionNs": maxFeatureFusionNanoseconds,
            "featureFusionP95Ns": featureFusionP95Nanoseconds,
            "estimatedStateBytes": estimatedStateBytes,
            "depthObservationCount": depthObservationCount,
            "depthAcceptedPixels": depthAcceptedPixels,
            "depthRejectedPixels": depthRejectedPixels,
            "depthCapacityRejectedPixels": depthCapacityRejectedPixels,
            "depthRayVisits": depthRayVisits,
            "carvedVoxels": carvedVoxels,
            "restoredVoxels": restoredVoxels,
            "depthTransientUnavailableCount":
                depthTransientUnavailableCount,
            "depthFailureCount": depthFailureCount,
            "lastDepthFusionNs": lastDepthFusionNanoseconds,
            "maxDepthFusionNs": maxDepthFusionNanoseconds,
            "depthFusionP95Ns": depthFusionP95Nanoseconds,
            "callbackCopyP95Ns": callbackCopyP95Nanoseconds,
            "coalescedFeatureObservations":
                coalescedFeatureObservations,
            "coalescedDepthObservations":
                coalescedDepthObservations,
            "coalescedGeometryChanges": coalescedGeometryChanges,
            "geometryRevision": geometryRevision,
            "pendingGeometryKeys": pendingGeometryKeys,
            "unacknowledgedGeometryCallbacks":
                unacknowledgedGeometryCallbacks,
            "publishedDeltaCount": publishedDeltaCount,
            "snapshotRecoveryCount": snapshotRecoveryCount,
            "geometryAcknowledgementCount":
                geometryAcknowledgementCount,
            "rendererRows": rendererRows,
            "rendererFreeRows": rendererFreeRows
        ]
    }
}

struct VisibilityGridSnapshot {
    let identity: VisibilityGridIdentity
    let geometryRevision: Int64
    let stableKeys: [UInt64]
    let supportByKey: [UInt64: Int]
    let diagnostics: VisibilityGridDiagnostics
}

struct VisibilityGridDelta {
    let identity: VisibilityGridIdentity
    let baseGeometryRevision: Int64
    let geometryRevision: Int64
    let reset: Bool
    let upsertKeys: [UInt64]
    let removalKeys: [UInt64]
    let capacity: Int
    let diagnostics: VisibilityGridDiagnostics
}

enum VisibilityGridRenderMode: String {
    case points
    case centroids
    case cubes
}

enum SceneDepthConfidence: Int {
    case low = 0
    case medium = 1
    case high = 2
}

enum SceneDepthMode: Equatable {
    case sceneDepth
    case sceneDepthTransientlyUnavailable
    case featureOnly
}

final class VisibilityGridLifecycleEpoch {
    private(set) var token: Int64 = 0
    private var paused = false
    private var disposed = false

    func allows(_ candidate: Int64) -> Bool {
        !disposed && !paused && token == candidate
    }

    func allowsCheckpoint(_ candidate: Int64) -> Bool {
        !disposed && token == candidate
    }

    func allowsCall(_ method: String, token candidate: Int64) -> Bool {
        switch method {
        case "checkpointBarrier", "releaseCheckpoint":
            return allowsCheckpoint(candidate)
        default:
            return allows(candidate)
        }
    }

    func pause() {
        guard !disposed && !paused else { return }
        paused = true
        token += 1
    }

    func resume() {
        guard !disposed && paused else { return }
        paused = false
        token += 1
    }

    func resetSession() {
        guard !disposed else { return }
        token += 1
    }

    func changeGroup() {
        resetSession()
    }

    func dispose() {
        guard !disposed else { return }
        disposed = true
        token += 1
    }
}
