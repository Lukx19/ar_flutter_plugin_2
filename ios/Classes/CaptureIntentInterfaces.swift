import Foundation

/// Declaration-only #99 seam. No Apple runtime behavior or codec is provided.
protocol CaptureCommitPortInterface {
    func acceptBeforeExposure(_ attempt: CaptureAcceptedAttemptDescriptor) async throws -> CaptureReceiptDescriptor
    func prepareCommit(_ request: CaptureCommitRequestDescriptor) async throws -> CaptureReceiptDescriptor
    func queryReceipt(_ identity: CaptureAttemptIdentityDescriptor) async throws -> CaptureReceiptDescriptor?
    func abandon(_ terminal: CaptureTerminalDescriptor) async throws -> CaptureReceiptDescriptor
}

enum CaptureIntentInterfaceConstants {
    static let portableOrdinalMaximum = UInt64(Int64.max)
    static let coexistenceBytes: UInt64 = 128 * 1024 * 1024
    static let maximumAdmittedAttempts = 2
    static let maximumAutomaticAttempts = 1
    static let maximumManualAttempts = 1
    static let maximumRunningFinalizers = 1
    static let maximumWaitingFinalizers = 1
    static let maximumReadyPictureSets = 0
}

enum CaptureLaneDescriptor { case manual, automatic }
enum CaptureAttemptPhaseDescriptor { case reservedAccepted, exposureRequested, sensorOutputOwned, validated, durablePrepared, committedPicture, abandonedAttempt }
enum CaptureComponentKindDescriptor { case jpeg, dng, raw, hdr, sidecar }
enum CaptureTerminalKindDescriptor { case committedPicture, abandonedAttempt }

struct CaptureLifecycleCutDescriptor {
    let sessionID: UUID
    let sessionGeneration: UInt64
    let groupID: UUID
    let groupGeneration: UInt64
    let arSessionID: UUID
    let viewID: UUID
    let viewGeneration: UInt64
    let bindingToken: UUID
    let lifecycleSequence: UInt64
    let operationGeneration: UInt64
}

struct CaptureComponentProfileDescriptor {
    let profileID: String
    let requiredComponents: [CaptureComponentKindDescriptor]
    let maximumComponentBytes: UInt64
    let maximumWorkingBytes: UInt64
}

struct CaptureReservationLiabilityDescriptor {
    let memoryBytes: UInt64
    let physicalStoreBytes: UInt64
    let componentEntries: UInt32
    let terminalEntries: UInt32
    let rollbackBytes: UInt64
    let physicallyBacked: Bool
}

struct CaptureAttemptIdentityDescriptor {
    let attemptID: UUID
    let commitID: UUID
    let attemptOrdinal: UInt64
    let lifecycleCut: CaptureLifecycleCutDescriptor
}

struct CaptureAcceptedAttemptDescriptor {
    let identity: CaptureAttemptIdentityDescriptor
    let lane: CaptureLaneDescriptor
    let profile: CaptureComponentProfileDescriptor
    let reservation: CaptureReservationLiabilityDescriptor
    let canonicalIntentHash: Data
    let acceptedReceiptHash: Data
}

struct CaptureComponentDescriptor {
    let kind: CaptureComponentKindDescriptor
    let byteLength: UInt64
    let sha256: Data
    let durableObjectID: String
}

struct CaptureCommitRequestDescriptor {
    let accepted: CaptureAcceptedAttemptDescriptor
    let components: [CaptureComponentDescriptor]
    let exposureTimestampNanoseconds: UInt64
    let poseRecordHash: Data
    let cameraModelHash: Data
    let validationRecordHash: Data
    let ledgerRecordHash: Data
}

struct CaptureTerminalDescriptor {
    let kind: CaptureTerminalKindDescriptor
    let identity: CaptureAttemptIdentityDescriptor
    let canonicalTerminalHash: Data
    let reasonCode: String
    let captureID: UUID?
    let captureRevision: UInt64?
    let manifestID: String?
}

struct CaptureReceiptDescriptor {
    let identity: CaptureAttemptIdentityDescriptor
    let phase: CaptureAttemptPhaseDescriptor
    let requestHash: Data
    let receiptHash: Data
    let durable: Bool
    let terminal: CaptureTerminalDescriptor?
}
