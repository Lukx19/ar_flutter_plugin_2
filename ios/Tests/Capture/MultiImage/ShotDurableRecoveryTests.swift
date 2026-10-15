import XCTest
@testable import ar_flutter_plugin_2

/// The durable recovery guarantees hold for a burst shot root: a fault after
/// the shot's blobs moved into `assets/` (receipt sync, pointer slot replace
/// and directory sync, budget settlement) keeps every image, the record and
/// the staging; a rebase in process or after a restart verifies every image
/// blob before it publishes the shot root, and the reservation is charged
/// with the whole shot exactly once. A session deletion reclaims every image
/// of a published shot once and releases an unresolved shot's reservation.
final class ShotDurableRecoveryTests: XCTestCase {
  private var root: URL!
  private var camera: FakeStillExposure!
  private var events: [NativeCaptureEvent] = []
  private var now: Int64 = 1_000

  private let shot = ShotFixture.burst()
  private var payload: DurableShotPayload { shot.payload! }
  private var wide: [UInt8] { shot.first.jpeg }
  private var shotBytes: Int64 {
    Int64(wide.count + payload.images.reduce(0) { $0 + $1.bytes.count } + payload.record.count)
  }

  override func setUp() {
    super.setUp()
    root = CaptureFixture.temporaryDirectory("shot-recovery")
    camera = FakeStillExposure()
    events = []
    now = 1_000
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
    super.tearDown()
  }

  private func resetRoot() throws {
    try FileManager.default.removeItem(at: root)
    root = CaptureFixture.temporaryDirectory("shot-recovery")
    camera = FakeStillExposure()
    events = []
  }

  // MARK: Paths

  private func exists(_ relative: String) -> Bool { FileManager.default.fileExists(atPath: root.appendingPathComponent(relative).path) }
  private func session(_ id: String = "session-ios") -> String { "store/sessions/\(DurableStoreEncoding.safeName(id))" }
  private func attempt(_ request: CaptureCommitRequest) -> String {
    "\(session(request.accepted.identity.lifecycleCut.sessionId))/attempts/\(DurableStoreEncoding.safeName(request.accepted.identity.commitId))"
  }
  private func assets(_ request: CaptureCommitRequest) -> String {
    "\(session(request.accepted.identity.lifecycleCut.sessionId))/assets/\(DurableStoreEncoding.safeName(request.accepted.identity.attemptId))"
  }
  private static func sha(_ bytes: [UInt8]) -> String { DurableStoreEncoding.hex(DurableStoreEncoding.sha256(bytes)) }

  /// Every blob of the shot: the first JPEG, each later image and the record.
  private func shotBlobs(_ request: CaptureCommitRequest) -> [String] {
    let folder = assets(request)
    return ["\(folder)/jpeg.\(Self.sha(wide)).blob"]
      + payload.images.map { "\(folder)/\(DurableCaptureStore.shotImageBlobName(index: $0.index, hash: Self.sha($0.bytes)))" }
      + ["\(folder)/\(DurableCaptureStore.shotRecordBlobName(hash: Self.sha(payload.record)))"]
  }

  private func assertEveryImageKept(_ request: CaptureCommitRequest, _ label: String, file: StaticString = #filePath, line: UInt = #line) {
    for blob in shotBlobs(request) {
      XCTAssertTrue(exists(blob), "\(label): \(blob) kept", file: file, line: line)
    }
  }

  // MARK: Owner

  private func owner(_ store: DurableCaptureStore) -> NativeCaptureAdmissionOwner {
    NativeCaptureAdmissionOwner(
      store: store, exposure: camera, nowMs: { [unowned self] in self.now },
      events: { [unowned self] in self.events.append($0) }, durableTerminal: { _ in })
  }

  private func terminals() -> [NativeCaptureEvent] { events.filter { $0.isTerminal } }

  private func deliverShot() {
    let (qualifier, callback) = camera.requests[0]
    callback(.components(StillComponentSet(
      qualifier: qualifier, components: [(.jpeg, wide)], metadata: shot.first.metadata, shot: shot)))
  }

  /// Admits [request], delivers the burst shot (the store commit may fail)
  /// and runs the 30 s fence.
  private func commitShotThroughOwner(_ owner: NativeCaptureAdmissionOwner, _ request: CaptureCommitRequest) throws {
    _ = try owner.admit(request)
    deliverShot()
    now += NativeCaptureAdmissionOwner.terminalFenceMs
    owner.advanceDeadlines()
  }

  /// A restart projects the committed shot, every image verifies and the
  /// reservation is charged with the whole shot once; a second restart
  /// changes nothing.
  private func assertRestartRebasesTheShot(_ request: CaptureCommitRequest, _ label: String,
                                           file: StaticString = #filePath, line: UInt = #line) throws {
    for pass in 1...2 {
      let (store, budget) = try CaptureFixture.store(root)
      let projected = try store.recoverAndProject()
      XCTAssertEqual(projected.first?.kind, .committedPicture, "\(label) restart \(pass)", file: file, line: line)
      let receipt = try XCTUnwrap(try store.queryReceipt(request.accepted.identity), file: file, line: line)
      XCTAssertEqual(receipt.phase, .committedPicture, file: file, line: line)
      let files = try XCTUnwrap(store.committedShotFiles(try XCTUnwrap(receipt.terminal)), "\(label) restart \(pass)", file: file, line: line)
      XCTAssertEqual(files.images.keys.sorted(), [1, 2], file: file, line: line)
      XCTAssertEqual(try budget.reservedBytesTotal(), 0, "\(label) restart \(pass): reservation closed", file: file, line: line)
      XCTAssertEqual(try budget.committedBytes(), shotBytes, "\(label) restart \(pass): whole shot charged once", file: file, line: line)
      assertEveryImageKept(request, "\(label) restart \(pass)", file: file, line: line)
      XCTAssertFalse(exists("\(attempt(request))/staging"), "\(label) restart \(pass): staging settled", file: file, line: line)
    }
  }

  // MARK: Faults after the shot's blobs moved

  func testReceiptSyncFaultRebasesTheShotInProcessAndChargesItOnce() throws {
    let plan = FaultPlan(.receiptFileSync)
    let (store, budget) = try CaptureFixture.store(root, fault: plan.injector())
    let request = CaptureFixture.request()
    try commitShotThroughOwner(owner(store), request)
    XCTAssertEqual(plan.injected, 1)
    XCTAssertEqual(terminals().map { $0.kind }, [.committed], "the query rebases the durable shot receipt")
    XCTAssertEqual(try budget.reservedBytesTotal(), 0)
    XCTAssertEqual(try budget.committedBytes(), shotBytes)
    assertEveryImageKept(request, "in process")
    try assertRestartRebasesTheShot(request, "receiptFileSync")
  }

  func testPointerFaultsKeepEveryShotImageAndTheRestartRebasesTheShot() throws {
    for point in [DurableStoreFaultPoint.pointerSlotReplace, .pointerDirectorySync] {
      try resetRoot()
      let fault = PersistentFault(point)
      let (store, budget) = try CaptureFixture.store(root, fault: fault.injector())
      let owner = owner(store)
      let request = CaptureFixture.request()
      try commitShotThroughOwner(owner, request)
      XCTAssertGreaterThan(fault.injected, 1, "\(point): commit and the rebase both hit the fault")
      XCTAssertTrue(terminals().isEmpty, "\(point): no terminal before the restart")
      XCTAssertEqual(owner.activeAttemptCount, 0, "\(point): the lane is free")
      assertEveryImageKept(request, "\(point)")
      XCTAssertTrue(exists("\(attempt(request))/staging"), "\(point): staging kept")
      XCTAssertTrue(exists("\(attempt(request))/receipt.properties"), "\(point): receipt kept")
      XCTAssertGreaterThan(try budget.reservedBytesTotal(), 0, "\(point): liability kept")
      XCTAssertEqual(try budget.committedBytes(), 0, "\(point): nothing charged before the pointer is known")
      // Later deadline ticks never retry, abandon or delete an image.
      now += 60_000
      owner.advanceDeadlines()
      assertEveryImageKept(request, "\(point) after ticks")
      XCTAssertNil(events.first { $0.kind == .abandoned }, "\(point)")
      try assertRestartRebasesTheShot(request, "\(point)")
    }
  }

  func testBudgetSettlementFaultKeepsTheShotAndChargesItOnceAtRestart() throws {
    // The settlement is cut before its marker is durable (its rename):
    // the reservation stays live. A cut after the marker is finished by the
    // ledger itself (StorageBudgetLedgerTests).
    let fault = PersistentFault(.pointerSlotReplace)
    fault.armed = false
    let (store, budget) = try CaptureFixture.store(root, budgetFault: fault.injector())
    let owner = owner(store)
    let request = CaptureFixture.request()
    _ = try owner.admit(request)
    fault.armed = true
    deliverShot()
    XCTAssertGreaterThan(fault.injected, 0)
    XCTAssertEqual(terminals().map { $0.kind }, [.committed], "the published shot root is the terminal")
    assertEveryImageKept(request, "settlement fault")
    fault.armed = false
    XCTAssertGreaterThan(try budget.reservedBytesTotal(), 0, "settlement failed, the liability stays")
    XCTAssertEqual(try budget.committedBytes(), 0)
    try assertRestartRebasesTheShot(request, "budget settlement")
  }

  func testPartialShotReceiptIsUnknownAndKeepsEveryImage() throws {
    let (store, budget) = try CaptureFixture.store(root, storeBackend: { PartialWriteBackend($0, name: "receipt.properties") })
    let request = CaptureFixture.request()
    try commitShotThroughOwner(owner(store), request)
    XCTAssertTrue(terminals().isEmpty)
    assertEveryImageKept(request, "partial receipt")
    let terminal = CaptureTerminal(kind: .abandonedAttempt, identity: request.accepted.identity, canonicalTerminalHash: "x", reason: "x")
    XCTAssertThrowsError(try store.abandonTemplate(request.template, terminal: terminal)) { XCTAssertTrue($0 is DurableReceiptUnknown) }
    XCTAssertGreaterThan(try budget.reservedBytesTotal(), 0)

    for pass in 1...2 {
      let (restarted, restartedBudget) = try CaptureFixture.store(root)
      XCTAssertEqual(try restarted.recoverAndProject().map { $0.reason }, ["recovered-receipt-unknown"], "pass \(pass)")
      XCTAssertNoThrow(try restarted.recover(), "pass \(pass)")
      assertEveryImageKept(request, "pass \(pass)")
      XCTAssertGreaterThan(try restartedBudget.reservedBytesTotal(), 0, "pass \(pass): liability kept")
      XCTAssertEqual(try restartedBudget.committedBytes(), 0)
    }
  }

  /// The rebase verifies every blob of the shot root, not only the first
  /// JPEG: a missing or changed later image keeps the receipt unresolved
  /// with every remaining file and the liability; restoring the image lets
  /// the next restart publish the whole shot.
  func testRebaseVerifiesEveryShotImageBlob() throws {
    let fault = PersistentFault(.pointerSlotReplace)
    let (store, _) = try CaptureFixture.store(root, fault: fault.injector())
    let request = CaptureFixture.request()
    _ = try store.acceptCaptureBeforeExposure(request)
    XCTAssertThrowsError(try store.commitStreamedFinalized(
      request.template, exposureTimestampNanoseconds: 1, components: [(.jpeg, wide)], shot: payload))
    let second = root.appendingPathComponent(shotBlobs(request)[2])
    let original = try Data(contentsOf: second)

    for damage in ["missing", "changed"] {
      if damage == "missing" { try FileManager.default.removeItem(at: second) }
      else { try Data(original.reversed()).write(to: second) }
      let (restarted, budget) = try CaptureFixture.store(root)
      XCTAssertNotEqual(try restarted.recoverAndProject().first?.kind, .committedPicture, damage)
      XCTAssertNil(try restarted.queryReceipt(request.accepted.identity), "\(damage): no visible terminal")
      XCTAssertThrowsError(try restarted.rebaseSameAttempt(request), damage)
      XCTAssertGreaterThan(try budget.reservedBytesTotal(), 0, "\(damage): liability kept")
      XCTAssertEqual(try budget.committedBytes(), 0, damage)
      for blob in shotBlobs(request) where blob != shotBlobs(request)[2] {
        XCTAssertTrue(exists(blob), "\(damage): \(blob) kept")
      }
      XCTAssertTrue(exists("\(attempt(request))/staging"), damage)
    }

    try original.write(to: second)
    try assertRestartRebasesTheShot(request, "restored image")
  }

  // MARK: Session deletion

  func testSessionDeletionReclaimsEveryImageOfAShotOnce() throws {
    let (store, budget) = try CaptureFixture.store(root)
    let burst = CaptureFixture.request(attemptId: "deleted-shot", commitId: "deleted-shot", ordinal: 1, session: "deleted")
    _ = try store.acceptCaptureBeforeExposure(burst)
    _ = try store.commitStreamedFinalized(burst.template, exposureTimestampNanoseconds: 1, components: [(.jpeg, wide)], shot: payload)
    let plain = CaptureFixture.request(attemptId: "deleted-plain", commitId: "deleted-plain", ordinal: 2, session: "deleted")
    _ = try store.acceptCaptureBeforeExposure(plain)
    _ = try store.commitStreamedFinalized(plain.template, exposureTimestampNanoseconds: 2, components: [(.jpeg, [1, 2, 3])])
    let kept = CaptureFixture.request(attemptId: "kept-shot", commitId: "kept-shot", ordinal: 1, session: "kept")
    _ = try store.acceptCaptureBeforeExposure(kept)
    _ = try store.commitStreamedFinalized(kept.template, exposureTimestampNanoseconds: 3, components: [(.jpeg, wide)], shot: payload)
    XCTAssertEqual(try budget.committedBytes(), 2 * shotBytes + 3)
    // A shot whose pointer switch never happened: its reservation is
    // released on deletion, never charged or reclaimed.
    let unknownCut = PersistentFault(.pointerSlotReplace)
    let (unresolved, _) = try CaptureFixture.store(root, fault: unknownCut.injector())
    let pending = CaptureFixture.request(attemptId: "deleted-unknown", commitId: "deleted-unknown", ordinal: 3, session: "deleted")
    _ = try unresolved.acceptCaptureBeforeExposure(pending)
    XCTAssertThrowsError(try unresolved.commitStreamedFinalized(
      pending.template, exposureTimestampNanoseconds: 4, components: [(.jpeg, wide)], shot: payload))

    let (deleting, deletingBudget) = try CaptureFixture.store(root)
    XCTAssertEqual(try deleting.deleteSession("deleted"), shotBytes + 3, "every image of the shot leaves the ledger")
    XCTAssertEqual(try deletingBudget.committedBytes(), shotBytes, "only the kept shot stays charged")
    XCTAssertEqual(try deletingBudget.reservedBytesTotal(), 0, "the unresolved shot's reservation is released")
    XCTAssertFalse(exists("\(session("deleted"))/assets"))
    XCTAssertFalse(exists("\(session("deleted"))/attempts"))
    XCTAssertTrue(exists("\(session("deleted"))/tombstone"))
    assertEveryImageKept(kept, "kept session")

    XCTAssertEqual(try deleting.deleteSession("deleted"), 0, "a repeated deletion reclaims nothing")
    let (restarted, restartedBudget) = try CaptureFixture.store(root)
    XCTAssertEqual(try restarted.deleteSession("deleted"), 0)
    XCTAssertEqual(try restartedBudget.committedBytes(), shotBytes)
    let receipt = try XCTUnwrap(try restarted.queryReceipt(kept.accepted.identity))
    XCTAssertNotNil(restarted.committedShotFiles(try XCTUnwrap(receipt.terminal)), "the kept shot stays whole")
  }

  func testShotSessionDeletionReclaimsOnceAfterACrashAtEveryStep() throws {
    let cuts: [(String, DurableStoreFaultPoint)] = [
      ("budget", .pointerSlotReplace), ("budget", .deleteReclaim),
      ("store", .tombstoneRecord), ("store", .tombstoneDirectorySync), ("store", .deleteReclaim),
    ]
    var exercised = 0
    for (side, point) in cuts {
      for nth in 1...12 {
        try resetRoot()
        let (setup, _) = try CaptureFixture.store(root)
        let burst = CaptureFixture.request(attemptId: "deleted-shot", commitId: "deleted-shot", ordinal: 1, session: "deleted")
        _ = try setup.acceptCaptureBeforeExposure(burst)
        _ = try setup.commitStreamedFinalized(burst.template, exposureTimestampNanoseconds: 1, components: [(.jpeg, wide)], shot: payload)
        let kept = CaptureFixture.request(attemptId: "kept", commitId: "kept", ordinal: 1, session: "kept")
        _ = try setup.acceptCaptureBeforeExposure(kept)
        _ = try setup.commitStreamedFinalized(kept.template, exposureTimestampNanoseconds: 2, components: [(.jpeg, [9, 9])])

        let cut = NthVisitFault(point, nth: nth)
        let (poisoned, _) = side == "budget"
          ? try CaptureFixture.store(root, budgetFault: cut.injector())
          : try CaptureFixture.store(root, fault: cut.injector())
        let label = "\(side) \(point) #\(nth)"
        do {
          try poisoned.deleteSession("deleted")
          XCTAssertFalse(cut.injected, "\(label) completed although its cut was reached")
          break
        } catch {
          exercised += 1
        }
        let (clean, budget) = try CaptureFixture.store(root)
        try clean.deleteSession("deleted")
        try clean.deleteSession("deleted")
        XCTAssertEqual(try budget.committedBytes(), 2, "\(label): only the kept photo stays charged")
        XCTAssertEqual(try budget.reservedBytesTotal(), 0, label)
        XCTAssertFalse(exists("\(session("deleted"))/assets"), label)
        XCTAssertTrue(exists("\(session("deleted"))/tombstone"), label)
      }
    }
    XCTAssertGreaterThanOrEqual(exercised, 8, "the crash matrix reached the reclaim steps")
  }
}
