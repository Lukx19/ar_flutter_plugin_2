import XCTest
@testable import ar_flutter_plugin_2

/// The `native_capture_v2` codec of `arcapture_<id>` against the Android
/// `NativeCaptureWireV2` and the Dart models: the admission map Dart sends
/// decodes exactly; every event map has the key set and value types the Dart
/// `ARNativeCaptureEventV2.fromMap` requires. The same event literals are
/// parsed by the plugin's Dart test `native_capture_ios_wire_test.dart`.
final class NativeCaptureWireTests: XCTestCase {
  let request = CaptureFixture.request()

  func testAdmissionFromDartDecodesExactly() throws {
    let decoded = try NativeCaptureWire.decodeAdmission(CaptureFixture.admissionMap(request) as NSDictionary)
    XCTAssertEqual(decoded, request)
    XCTAssertEqual(decoded.recoveryContext, request.recoveryContext)
    XCTAssertTrue(decoded.components.isEmpty)
    XCTAssertEqual(decoded.exposureTimestampNanoseconds, 0)
  }

  func testAdmissionRefusesChangedShapesWithAndroidErrorClasses() {
    func expect(_ map: [String: Any], invalid: Bool, _ line: UInt = #line) {
      XCTAssertThrowsError(try NativeCaptureWire.decodeAdmission(map), line: line) { error in
        guard let wire = error as? NativeCaptureWireError else { return XCTFail("\(error)", line: line) }
        switch wire {
        case .invalid: XCTAssertTrue(invalid, "\(wire)", line: line)
        case .malformed: XCTAssertFalse(invalid, "\(wire)", line: line)
        }
      }
    }
    var map = CaptureFixture.admissionMap(request)
    map["extra"] = 1
    expect(map, invalid: true)
    map = CaptureFixture.admissionMap(request)
    map["wireVersion"] = "native_capture_v1"
    expect(map, invalid: true)
    map = CaptureFixture.admissionMap(request)
    map["poseRecordHash"] = Array(repeating: 1, count: 31)
    expect(map, invalid: true)
    map["poseRecordHash"] = Array(repeating: 256, count: 32)
    expect(map, invalid: true)
    map["poseRecordHash"] = "not a list"
    expect(map, invalid: false)
    map = CaptureFixture.admissionMap(request)
    var accepted = map["accepted"] as! [String: Any]
    var profile = accepted["profile"] as! [String: Any]
    profile["requiredComponents"] = ["raw"]
    accepted["profile"] = profile
    map["accepted"] = accepted
    expect(map, invalid: true)
    map = CaptureFixture.admissionMap(request)
    accepted = map["accepted"] as! [String: Any]
    var reservation = accepted["reservation"] as! [String: Any]
    reservation["memoryBytes"] = true
    accepted["reservation"] = reservation
    map["accepted"] = accepted
    expect(map, invalid: false)
    reservation["memoryBytes"] = 1.5
    accepted["reservation"] = reservation
    map["accepted"] = accepted
    expect(map, invalid: false)
    reservation["memoryBytes"] = 1
    accepted["reservation"] = reservation
    map["accepted"] = accepted
    expect(map, invalid: true)
  }

  func testCommittedAndAbandonedEventsHaveTheAndroidKeys() {
    let committed = NativeCaptureWire.event(NativeCaptureEvent(
      kind: .committed, attemptId: "attempt", captureId: "capture", captureRevision: 2, manifestId: "manifest",
      jpegSizeBytes: 4_659_553, reason: "committed", recoveryContext: request.recoveryContext))
    XCTAssertEqual(Set(committed.keys), ["wireVersion", "kind", "attemptId", "captureId", "captureRevision", "manifestId", "jpegSizeBytes", "reason", "recoveryContext"])
    XCTAssertEqual(committed["wireVersion"] as? String, "native_capture_v2")
    XCTAssertEqual(committed["kind"] as? String, "committed")
    XCTAssertEqual(committed["captureRevision"] as? Int, 2)
    XCTAssertEqual(committed["jpegSizeBytes"] as? Int, 4_659_553)
    let context = committed["recoveryContext"] as! [String: Any]
    XCTAssertEqual(Set(context.keys), ["sessionId", "groupId", "groupIndex", "groupGeneration", "trigger", "requestedAtMs", "coverageRevision", "timestampMs", "position", "rotation", "viewMatrix", "projectionMatrix", "groupFromWorld", "worldFromGroup"])
    XCTAssertEqual(context["position"] as? [Double], [0.25, -1.5, 2])

    let abandoned = NativeCaptureWire.event(NativeCaptureWire.terminalEvent(
      CaptureTerminal(kind: .abandonedAttempt, identity: request.accepted.identity, canonicalTerminalHash: "camera-x", reason: "camera-x"),
      recoveryContext: request.recoveryContext))
    XCTAssertEqual(Set(abandoned.keys), ["wireVersion", "kind", "attemptId", "reason", "recoveryContext"])
    XCTAssertEqual(abandoned["kind"] as? String, "abandoned")
  }

  func testScalarEventsHaveTheAndroidKeys() {
    XCTAssertEqual(Set(NativeCaptureWire.event(NativeCaptureEvent(kind: .accepted, attemptId: "a")).keys), ["wireVersion", "kind", "attemptId"])
    XCTAssertEqual(Set(NativeCaptureWire.event(NativeCaptureEvent(kind: .finalizing, attemptId: "a")).keys), ["wireVersion", "kind", "attemptId"])
    let recovering = NativeCaptureWire.event(NativeCaptureEvent(kind: .recovering, attemptId: "a", reason: "store-unknown", recoveryContext: request.recoveryContext))
    XCTAssertEqual(Set(recovering.keys), ["wireVersion", "kind", "attemptId", "reason", "recoveryContext"])
    let failed = NativeCaptureWire.event(NativeCaptureEvent(kind: .recoveryFailed, reason: NativeCaptureRecoveryReplay.failedReason))
    XCTAssertEqual(failed["kind"] as? String, "recoveryFailed")
    XCTAssertEqual(Set(failed.keys), ["wireVersion", "kind", "reason"])
    XCTAssertEqual(NativeCaptureWire.event(NativeCaptureRecoveryReplay.readyEvent)["reason"] as? String, "native-owner-ready")
    let health = NativeCaptureWire.event(NativeCaptureEvent(kind: .health, resources: CaptureResourceSnapshot(
      exposures: 2, lateCallbacks: 1, closedComponents: 3, committed: 1, abandoned: 1, unknownQueries: 1, running: 0, fundedWaiting: 0)))
    XCTAssertEqual(Set(health.keys), ["wireVersion", "kind", "health"])
    let values = health["health"] as! [String: Any]
    XCTAssertEqual(values as NSDictionary, [
      "exposures": 2, "lateCallbacks": 1, "closedComponents": 3, "committed": 1, "abandoned": 1, "unknownQueries": 1,
      "running": 0, "fundedWaiting": 0, "retainedImageBytes": 0, "readyPictureSets": 0,
    ] as NSDictionary)
  }

  func testAdmissionReplyCarriesTerminalAsNullOrEvent() {
    let pending = NativeCaptureWire.admissionReply(
      CaptureReceipt(identity: request.accepted.identity, phase: .reservedAccepted, requestHash: "r", receiptHash: "h", durable: true, terminal: nil),
      recoveryContext: request.recoveryContext)
    XCTAssertEqual(Set(pending.keys), ["wireVersion", "attemptId", "phase", "durable", "terminal"])
    XCTAssertEqual(pending["phase"] as? String, "reserved_accepted")
    XCTAssertTrue(pending["terminal"] is NSNull)
    let terminal = CaptureTerminal(kind: .abandonedAttempt, identity: request.accepted.identity, canonicalTerminalHash: "x",
                                   reason: "durable-replay-no-reexposure")
    let done = NativeCaptureWire.admissionReply(
      CaptureReceipt(identity: request.accepted.identity, phase: .abandonedAttempt, requestHash: "x", receiptHash: "h", durable: true, terminal: terminal),
      recoveryContext: request.recoveryContext)
    XCTAssertEqual(done["phase"] as? String, "abandoned_attempt")
    XCTAssertEqual((done["terminal"] as? [String: Any])?["reason"] as? String, "durable-replay-no-reexposure")
  }
}
