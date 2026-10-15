import Foundation
import simd
import XCTest
@testable import ar_flutter_plugin_2

/// Fixtures of the still capture tests. The attempt equals the one of the
/// golden generator (an independent transcription of the Android
/// `DurableSessionStoreV2` encodings, see `DurableStoreEncodingTests`).
enum CaptureFixture {
  static let mib: Int64 = 1024 * 1024
  static let jpeg: [UInt8] = [0xff, 0xd8] + Array("jpeg-fixture".utf8) + [0xff, 0xd9]

  static func sha(_ text: String) -> [UInt8] { DurableStoreEncoding.sha256(text) }

  static func cut(session: String = "session-ios", viewId: String = "4242") -> CaptureLifecycleCut {
    CaptureLifecycleCut(
      sessionId: session, sessionGeneration: 2, groupId: "group-a", groupGeneration: 3, arSessionId: "ar-session-ios",
      viewId: viewId, viewGeneration: 3, bindingToken: "\(session):group-a:\(viewId)", lifecycleSequence: 1, operationGeneration: 1)
  }

  static func context(session: String = "session-ios") -> NativeCaptureRecoveryContext {
    NativeCaptureRecoveryContext(
      sessionId: session, groupId: "group-a", groupIndex: 0, groupGeneration: 3, trigger: "manual",
      requestedAtMs: 1_760_000_000_000, coverageRevision: 7, timestampMs: 1_760_000_000_001,
      position: [0.25, -1.5, 2], rotation: [0, 0, 0, 1],
      viewMatrix: identity, projectionMatrix: identity, groupFromWorld: identity, worldFromGroup: identity)
  }

  static let identity: [Double] = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]

  static func request(
    attemptId: String = "6f1c2d3e-0000-4000-8000-000000000001", commitId: String = "commit-1", ordinal: Int64 = 1,
    lane: CaptureLane = .manual, session: String = "session-ios", componentBytes: Int64 = 32 * 1024 * 1024
  ) -> CaptureCommitRequest {
    let accepted = CaptureAcceptedAttempt(
      identity: CaptureAttemptIdentity(attemptId: attemptId, commitId: commitId, attemptOrdinal: ordinal, lifecycleCut: cut(session: session)),
      lane: lane,
      profile: CaptureComponentProfile(profileId: "native-jpeg-v2", requiredComponents: [.jpeg],
                                       maximumComponentBytes: componentBytes, maximumWorkingBytes: 8 * mib),
      reservation: CaptureReservationLiability(
        memoryBytes: 8 * mib, physicalStoreBytes: NativeCaptureReservationBounds.physicalBytes(componentBytes: componentBytes, componentCount: 1),
        componentEntries: 1, terminalEntries: 1, rollbackBytes: NativeCaptureReservationBounds.rollbackBytes(componentBytes: componentBytes),
        physicallyBacked: true),
      canonicalIntentHash: sha("intent"), acceptedReceiptHash: sha("accepted"))
    return CaptureCommitRequest(
      accepted: accepted, components: [], exposureTimestampNanoseconds: 0, poseRecordHash: sha("pose"),
      cameraModelHash: sha("camera"), validationRecordHash: sha("validation"), ledgerRecordHash: sha("ledger"),
      recoveryContext: context(session: session))
  }

  /// The admission map the Dart `ARNativeCaptureAdmissionV2.toMap` sends for [request].
  static func admissionMap(_ request: CaptureCommitRequest) -> [String: Any] {
    let a = request.accepted
    let c = a.identity.lifecycleCut
    return [
      "wireVersion": "native_capture_v2",
      "accepted": [
        "identity": [
          "attemptId": a.identity.attemptId, "commitId": a.identity.commitId, "attemptOrdinal": Int(a.identity.attemptOrdinal),
          "lifecycleCut": [
            "sessionId": c.sessionId, "sessionGeneration": Int(c.sessionGeneration), "groupId": c.groupId,
            "groupGeneration": Int(c.groupGeneration), "arSessionId": c.arSessionId, "viewId": c.viewId,
            "viewGeneration": Int(c.viewGeneration), "bindingToken": c.bindingToken,
            "lifecycleSequence": Int(c.lifecycleSequence), "operationGeneration": Int(c.operationGeneration),
          ] as [String: Any],
        ] as [String: Any],
        "lane": a.lane.wireName,
        "profile": [
          "profileId": a.profile.profileId, "requiredComponents": ["jpeg"],
          "maximumComponentBytes": Int(a.profile.maximumComponentBytes), "maximumWorkingBytes": Int(a.profile.maximumWorkingBytes),
        ] as [String: Any],
        "reservation": [
          "memoryBytes": Int(a.reservation.memoryBytes), "physicalStoreBytes": Int(a.reservation.physicalStoreBytes),
          "componentEntries": 1, "terminalEntries": 1, "rollbackBytes": Int(a.reservation.rollbackBytes), "physicallyBacked": true,
        ] as [String: Any],
        "canonicalIntentHash": a.canonicalIntentHash.map(Int.init),
        "acceptedReceiptHash": a.acceptedReceiptHash.map(Int.init),
      ] as [String: Any],
      "poseRecordHash": request.poseRecordHash.map(Int.init),
      "cameraModelHash": request.cameraModelHash.map(Int.init),
      "validationRecordHash": request.validationRecordHash.map(Int.init),
      "ledgerRecordHash": request.ledgerRecordHash.map(Int.init),
      "recoveryContext": NativeCaptureWire.recoveryContextMap(request.recoveryContext!),
    ]
  }

  static func temporaryDirectory(_ name: String = "capture") -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(name)-\(UUID().uuidString)", isDirectory: true)
    try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
  }

  /// A store and budget under [root] (the `capture-v2-native` folder).
  static func store(_ root: URL, fault: @escaping DurableStoreFaultInjector = { _ in }, quota: Int64 = 4 * 1024 * 1024 * 1024) throws -> (DurableCaptureStore, StorageBudgetLedger) {
    let budget = try StorageBudgetLedger(
      files: RootedDurableFiles(backend: try PosixDurableFileBackend(root: root.appendingPathComponent("budget"))),
      policy: StorageBudgetPolicy(quotaBytes: quota, freeSpaceFloorBytes: 0), freeBytes: { Int64.max / 4 })
    let store = try DurableCaptureStore(
      files: RootedDurableFiles(backend: try PosixDurableFileBackend(root: root.appendingPathComponent("store")), fault: fault),
      budget: budget)
    return (store, budget)
  }

  static func metadata(width: Int = 4032, height: Int = 3024) -> StillExposureMetadata {
    StillExposureMetadata(
      exposureTimestampNs: 1_000_000, cameraToWorld: matrix_identity_float4x4, intrinsics: simd_float4(2800, 2800, 2016, 1512),
      width: width, height: height, sharpness: 321.5, highResolution: true)
  }
}

/// Camera fake: records requests; the test completes each one explicitly.
final class FakeStillExposure: StillExposurePort {
  var accept = true
  private(set) var requests: [(CaptureAttemptQualifier, (StillExposureOutcome) -> Void)] = []
  private(set) var cancelled: [CaptureAttemptQualifier] = []

  func requestExposure(_ qualifier: CaptureAttemptQualifier, required: Set<CaptureComponentKind>,
                       callback: @escaping (StillExposureOutcome) -> Void) -> Bool {
    guard accept else { return false }
    requests.append((qualifier, callback))
    return true
  }

  func cancelExposure(_ qualifier: CaptureAttemptQualifier) { cancelled.append(qualifier) }

  func deliverJpeg(_ index: Int = 0, bytes: [UInt8] = CaptureFixture.jpeg) {
    let (qualifier, callback) = requests[index]
    callback(.components(StillComponentSet(qualifier: qualifier, components: [(.jpeg, bytes)], metadata: CaptureFixture.metadata())))
  }

  func fail(_ index: Int = 0, reason: String = "test-failure") {
    let (qualifier, callback) = requests[index]
    callback(.failure(qualifier, reason))
  }
}

/// Failure seam: throws at the first visit of [point] and counts visits.
final class FaultPlan {
  let point: DurableStoreFaultPoint
  private(set) var injected = 0
  private(set) var visits = 0

  init(_ point: DurableStoreFaultPoint) { self.point = point }

  func injector() -> DurableStoreFaultInjector {
    { [self] visited in
      guard visited == point else { return }
      visits += 1
      if injected == 0 {
        injected += 1
        throw DurableStoreStateError("injected-\(point.rawValue)")
      }
    }
  }
}
