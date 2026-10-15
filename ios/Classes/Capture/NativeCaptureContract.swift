import Foundation

// Value types of the native durable capture contract, one to one with the
// Android `CaptureIntentContract.kt` types the `native_capture_v2` wire and the
// durable store use. Enum case order is the Android ordinal order: the store's
// canonical request hash writes ordinals, so the order is part of the stored
// format.

enum CaptureLane: Int, CaseIterable {
  case manual = 0
  case automatic = 1

  var wireName: String { self == .manual ? "manual" : "automatic" }
  /// Kotlin enum name (`MANUAL`, `AUTOMATIC`).
  var storedName: String { self == .manual ? "MANUAL" : "AUTOMATIC" }
}

enum CaptureComponentKind: Int, CaseIterable, Comparable {
  case jpeg = 0
  case dng = 1
  case raw = 2
  case hdr = 3
  case sidecar = 4

  /// Kotlin enum name, used in root records and blob names (lowercased).
  var storedName: String {
    switch self {
    case .jpeg: return "JPEG"
    case .dng: return "DNG"
    case .raw: return "RAW"
    case .hdr: return "HDR"
    case .sidecar: return "SIDECAR"
    }
  }

  init?(storedName: String) {
    guard let kind = CaptureComponentKind.allCases.first(where: { $0.storedName == storedName }) else { return nil }
    self = kind
  }

  static func < (lhs: CaptureComponentKind, rhs: CaptureComponentKind) -> Bool { lhs.rawValue < rhs.rawValue }
}

enum CaptureTerminalKind: Int {
  case committedPicture = 0
  case abandonedAttempt = 1

  var storedName: String { self == .committedPicture ? "COMMITTED_PICTURE" : "ABANDONED_ATTEMPT" }
}

enum CaptureAttemptPhase: String {
  case reservedAccepted = "RESERVED_ACCEPTED"
  case exposureRequested = "EXPOSURE_REQUESTED"
  case sensorOutputOwned = "SENSOR_OUTPUT_OWNED"
  case validated = "VALIDATED"
  case durablePrepared = "DURABLE_PREPARED"
  case committedPicture = "COMMITTED_PICTURE"
  case abandonedAttempt = "ABANDONED_ATTEMPT"

  /// Admission reply `phase` (Kotlin `phase.name.lowercase()`).
  var wireName: String { rawValue.lowercased() }
}

enum CaptureLifecycleEvent: String {
  case automaticDisabled, routeLeft, viewReplaced, arSessionReplaced, backgrounded, processRestarted
}

/// Complete immutable cut that qualifies every callback and durable receipt.
struct CaptureLifecycleCut: Hashable {
  var sessionId: String
  var sessionGeneration: Int64
  var groupId: String
  var groupGeneration: Int64
  var arSessionId: String
  var viewId: String
  var viewGeneration: Int64
  var bindingToken: String
  var lifecycleSequence: Int64
  var operationGeneration: Int64

  var isValid: Bool {
    [sessionGeneration, groupGeneration, viewGeneration, lifecycleSequence, operationGeneration].allSatisfy { $0 >= 0 }
      && [sessionId, groupId, arSessionId, viewId, bindingToken].allSatisfy { !$0.isEmpty }
  }
}

struct CaptureAttemptIdentity: Hashable {
  var attemptId: String
  var commitId: String
  var attemptOrdinal: Int64
  var lifecycleCut: CaptureLifecycleCut
}

struct CaptureComponentProfile: Hashable {
  var profileId: String
  var requiredComponents: Set<CaptureComponentKind>
  var maximumComponentBytes: Int64
  var maximumWorkingBytes: Int64
}

/// Complete RAM and store liability physically backed before exposure.
struct CaptureReservationLiability: Hashable {
  var memoryBytes: Int64
  var physicalStoreBytes: Int64
  var componentEntries: Int64
  var terminalEntries: Int64
  var rollbackBytes: Int64
  var physicallyBacked: Bool

  var totalStoreLiability: Int64 { physicalStoreBytes &+ rollbackBytes }
}

/// Conservative contract bounds for every pre-shutter durable liability.
enum NativeCaptureReservationBounds {
  static let acceptedRecordBytes: Int64 = 64 * 1024
  static let componentDescriptorBytes: Int64 = 16 * 1024
  static let selectedRootBytes: Int64 = 64 * 1024
  static let receiptBytes: Int64 = 32 * 1024
  static let alternatingPointerBytes: Int64 = 32 * 1024
  static let directorySyncBytes: Int64 = 32 * 1024
  static let terminalMetadataBytes: Int64 = 32 * 1024
  static let coexistenceBytes: Int64 = 1024 * 1024
  static let rollbackMetadataBytes: Int64 = 256 * 1024
  /// Android `CAPTURE_COEXISTENCE_BYTES`: lower bound of the store quota.
  static let captureCoexistenceBytes: Int64 = 128 * 1024 * 1024

  static func physicalBytes(componentBytes: Int64, componentCount: Int) -> Int64 {
    componentBytes + acceptedRecordBytes + componentDescriptorBytes * Int64(componentCount) + selectedRootBytes
      + receiptBytes + alternatingPointerBytes + directorySyncBytes + terminalMetadataBytes + coexistenceBytes
  }

  static func rollbackBytes(componentBytes: Int64) -> Int64 { componentBytes + rollbackMetadataBytes }
}

/// Durable accepted record written before the only exposure request.
struct CaptureAcceptedAttempt: Hashable {
  var identity: CaptureAttemptIdentity
  var lane: CaptureLane
  var profile: CaptureComponentProfile
  var reservation: CaptureReservationLiability
  /// 32 unsigned bytes.
  var canonicalIntentHash: [UInt8]
  var acceptedReceiptHash: [UInt8]
}

/// One streamed component descriptor; the bytes stay in the store.
struct DurableComponentDescriptor: Hashable {
  var kind: CaptureComponentKind
  var byteLength: Int64
  var sha256: [UInt8]
  var durableObjectId: String
}

/// Scalar product context retained with acceptance; never image bytes or paths.
struct NativeCaptureRecoveryContext: Hashable {
  var sessionId: String
  var groupId: String
  var groupIndex: Int64
  var groupGeneration: Int64
  var trigger: String
  var requestedAtMs: Int64
  var coverageRevision: Int64
  var timestampMs: Int64
  var position: [Double]
  var rotation: [Double]
  var viewMatrix: [Double]
  var projectionMatrix: [Double]
  var groupFromWorld: [Double]
  var worldFromGroup: [Double]

  var isValid: Bool {
    !sessionId.isEmpty && !groupId.isEmpty && !trigger.isEmpty && groupIndex >= 0 && groupGeneration >= 0
      && coverageRevision >= 0 && requestedAtMs >= 0 && timestampMs >= 0 && position.count == 3
      && rotation.count == 4 && viewMatrix.count == 16 && projectionMatrix.count == 16
      && groupFromWorld.count == 16 && worldFromGroup.count == 16
      && (position + rotation + viewMatrix + projectionMatrix + groupFromWorld + worldFromGroup).allSatisfy { $0.isFinite }
  }
}

/// Complete typed input to the durable store. Admission requests carry no
/// components and a zero exposure time; the store derives both after output.
struct CaptureCommitRequest: Hashable {
  var accepted: CaptureAcceptedAttempt
  var components: [DurableComponentDescriptor]
  var exposureTimestampNanoseconds: Int64
  var poseRecordHash: [UInt8]
  var cameraModelHash: [UInt8]
  var validationRecordHash: [UInt8]
  var ledgerRecordHash: [UInt8]
  var recoveryContext: NativeCaptureRecoveryContext?

  /// Equality and hashing ignore the recovery context, as the Kotlin request does.
  static func == (lhs: CaptureCommitRequest, rhs: CaptureCommitRequest) -> Bool {
    lhs.accepted == rhs.accepted && lhs.components == rhs.components
      && lhs.exposureTimestampNanoseconds == rhs.exposureTimestampNanoseconds
      && lhs.poseRecordHash == rhs.poseRecordHash && lhs.cameraModelHash == rhs.cameraModelHash
      && lhs.validationRecordHash == rhs.validationRecordHash && lhs.ledgerRecordHash == rhs.ledgerRecordHash
  }

  func hash(into hasher: inout Hasher) {
    hasher.combine(accepted)
    hasher.combine(components)
    hasher.combine(exposureTimestampNanoseconds)
  }

  var template: CapturePostOutputTemplate {
    CapturePostOutputTemplate(
      accepted: accepted, poseRecordHash: poseRecordHash, cameraModelHash: cameraModelHash,
      validationRecordHash: validationRecordHash, ledgerRecordHash: ledgerRecordHash)
  }
}

/// Immutable non-image fields frozen at durable acceptance; descriptors arrive after the shutter.
struct CapturePostOutputTemplate: Hashable {
  var accepted: CaptureAcceptedAttempt
  var poseRecordHash: [UInt8]
  var cameraModelHash: [UInt8]
  var validationRecordHash: [UInt8]
  var ledgerRecordHash: [UInt8]
}

struct CaptureTerminal: Hashable {
  var kind: CaptureTerminalKind
  var identity: CaptureAttemptIdentity
  var canonicalTerminalHash: String
  var reason: String
  var captureId: String?
  var captureRevision: Int64?
  var manifestId: String?
  var jpegSizeBytes: Int64?
  var dngSizeBytes: Int64?
  /// The images after the first one of a committed burst shot (shot root,
  /// schema 7); nil for a single-image commit.
  var shot: CommittedShot? = nil
}

struct CaptureReceipt: Hashable {
  var identity: CaptureAttemptIdentity
  var phase: CaptureAttemptPhase
  var requestHash: String
  var receiptHash: String
  var durable: Bool
  var terminal: CaptureTerminal?
}

/// A durable store refusal: the request conflicts with recorded authority.
struct DurableStoreConflict: Error, CustomStringConvertible {
  let message: String
  init(_ message: String) { self.message = message }
  var description: String { message }
}

/// A store precondition failed (Kotlin `check`/`IllegalStateException`).
struct DurableStoreStateError: Error, CustomStringConvertible {
  let message: String
  init(_ message: String) { self.message = message }
  var description: String { message }
}
