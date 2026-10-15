import XCTest
@testable import ar_flutter_plugin_2

/// A store fault after the component blobs moved into `assets/` (receipt
/// write and sync, pointer slot replace and directory sync, budget
/// settlement) never deletes the blob or the staging: the owner's query,
/// the 30 s fence and abandonment keep them, a rebase in process or after a
/// restart publishes the receipt's root, and the reservation is settled
/// exactly once. A partial receipt is unknown and keeps every file. The
/// ledger opens above its quota and refuses only new charges; a deleted
/// session's bytes leave the ledger once, also across a crash at any step.
final class DurableCaptureRecoveryTests: XCTestCase {
  private var root: URL!
  private var camera: FakeStillExposure!
  private var events: [NativeCaptureEvent] = []
  private var now: Int64 = 1_000
  private let jpegBytes = Int64(CaptureFixture.jpeg.count)

  override func setUp() {
    super.setUp()
    root = CaptureFixture.temporaryDirectory("durable-recovery")
    camera = FakeStillExposure()
    events = []
    now = 1_000
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
    super.tearDown()
  }

  // MARK: Paths

  private func exists(_ relative: String) -> Bool { FileManager.default.fileExists(atPath: root.appendingPathComponent(relative).path) }
  private func session(_ id: String = "session-ios") -> String { "store/sessions/\(DurableStoreEncoding.safeName(id))" }
  private func attempt(_ request: CaptureCommitRequest) -> String {
    "\(session(request.accepted.identity.lifecycleCut.sessionId))/attempts/\(DurableStoreEncoding.safeName(request.accepted.identity.commitId))"
  }
  private func blob(_ request: CaptureCommitRequest, _ bytes: [UInt8] = CaptureFixture.jpeg) -> String {
    let digest = DurableStoreEncoding.hex(DurableStoreEncoding.sha256(bytes))
    return "\(session(request.accepted.identity.lifecycleCut.sessionId))/assets/\(DurableStoreEncoding.safeName(request.accepted.identity.attemptId))/jpeg.\(digest).blob"
  }

  private func owner(_ store: DurableCaptureStore) -> NativeCaptureAdmissionOwner {
    NativeCaptureAdmissionOwner(
      store: store, exposure: camera, nowMs: { [unowned self] in self.now },
      events: { [unowned self] in self.events.append($0) }, durableTerminal: { _ in })
  }

  private func terminals() -> [NativeCaptureEvent] { events.filter { $0.isTerminal } }

  /// Admits [request] on [owner], delivers its JPEG (the store commit
  /// fails) and runs the 30 s fence.
  private func commitThroughOwner(_ owner: NativeCaptureAdmissionOwner, _ request: CaptureCommitRequest) throws {
    _ = try owner.admit(request)
    camera.deliverJpeg()
    now += NativeCaptureAdmissionOwner.terminalFenceMs
    owner.advanceDeadlines()
  }

  /// The attempt left the owner without a terminal; blob, staging and the
  /// receipt stay with the live reservation for restart recovery.
  private func assertHandedToRestart(_ owner: NativeCaptureAdmissionOwner, _ request: CaptureCommitRequest, _ budget: StorageBudgetLedger,
                                     _ label: String, file: StaticString = #filePath, line: UInt = #line) throws {
    XCTAssertTrue(terminals().isEmpty, "\(label): no terminal before the restart", file: file, line: line)
    XCTAssertEqual(owner.activeAttemptCount, 0, "\(label): the lane is free", file: file, line: line)
    XCTAssertTrue(exists(blob(request)), "\(label): asset blob kept", file: file, line: line)
    XCTAssertTrue(exists("\(attempt(request))/staging"), "\(label): staging kept", file: file, line: line)
    XCTAssertTrue(exists("\(attempt(request))/receipt.properties"), "\(label): receipt kept", file: file, line: line)
    XCTAssertGreaterThan(try budget.reservedBytesTotal(), 0, "\(label): liability kept", file: file, line: line)
  }

  /// A restart projects the committed photo and settles the reservation
  /// once; a second restart changes nothing.
  private func assertRestartRebases(_ request: CaptureCommitRequest, _ label: String, file: StaticString = #filePath, line: UInt = #line) throws {
    for pass in 1...2 {
      let (store, budget) = try CaptureFixture.store(root)
      let projected = try store.recoverAndProject()
      XCTAssertEqual(projected.first?.kind, .committedPicture, "\(label) restart \(pass)", file: file, line: line)
      XCTAssertEqual(projected.first?.reason, "recovered-committed", "\(label) restart \(pass)", file: file, line: line)
      XCTAssertEqual(try store.queryReceipt(request.accepted.identity)?.phase, .committedPicture, file: file, line: line)
      XCTAssertEqual(try budget.reservedBytesTotal(), 0, "\(label) restart \(pass): reservation closed", file: file, line: line)
      XCTAssertEqual(try budget.committedBytes(), jpegBytes, "\(label) restart \(pass): charged exactly once", file: file, line: line)
      XCTAssertTrue(exists(blob(request)), "\(label) restart \(pass): blob kept", file: file, line: line)
      XCTAssertFalse(exists("\(attempt(request))/staging"), "\(label) restart \(pass): staging settled", file: file, line: line)
    }
  }

  // MARK: Faults after the blob moved

  func testReceiptSyncFaultRebasesInProcessAndSettlesOnce() throws {
    let plan = FaultPlan(.receiptFileSync)
    let (store, budget) = try CaptureFixture.store(root, fault: plan.injector())
    let owner = owner(store)
    let request = CaptureFixture.request()
    try commitThroughOwner(owner, request)
    XCTAssertEqual(plan.injected, 1)
    XCTAssertEqual(terminals().map { $0.kind }, [.committed], "the query rebases the durable receipt")
    XCTAssertEqual(try budget.reservedBytesTotal(), 0)
    XCTAssertEqual(try budget.committedBytes(), jpegBytes)
    XCTAssertTrue(exists(blob(request)))
    try assertRestartRebases(request, "receiptFileSync")
  }

  func testPointerFaultsHandTheAttemptToRestartAndTheRestartRebases() throws {
    for point in [DurableStoreFaultPoint.pointerSlotReplace, .pointerDirectorySync] {
      try FileManager.default.removeItem(at: root)
      root = CaptureFixture.temporaryDirectory("durable-recovery")
      events = []
      camera = FakeStillExposure()
      let fault = PersistentFault(point)
      let (store, budget) = try CaptureFixture.store(root, fault: fault.injector())
      let owner = owner(store)
      let request = CaptureFixture.request()
      try commitThroughOwner(owner, request)
      XCTAssertGreaterThan(fault.injected, 1, "\(point): commit and the rebase both hit the fault")
      try assertHandedToRestart(owner, request, budget, "\(point)")
      // Later deadline ticks never retry, abandon or delete.
      now += 60_000
      owner.advanceDeadlines()
      XCTAssertTrue(exists(blob(request)), "\(point)")
      XCTAssertNil(events.first { $0.kind == .abandoned }, "\(point)")
      try assertRestartRebases(request, "\(point)")
    }
  }

  /// A pointer rename whose directory sync failed leaves an uncertain root
  /// in its slot. While the sync keeps failing, a later commit is refused
  /// and never overwrites that slot; once the sync succeeds the uncertain
  /// root is selected again, its reservation is settled, and the later
  /// commit chains after it. Both survive a restart.
  func testAnUncertainRootPointerIsNeverOverwrittenByALaterCommit() throws {
    var failing = true
    let (store, budget) = try CaptureFixture.store(root, fault: { point in
      if failing && point == .pointerDirectorySync { throw DurableStoreStateError("injected-directory-sync") }
    })
    let first = CaptureFixture.request()
    _ = try store.acceptCaptureBeforeExposure(first)
    XCTAssertThrowsError(try store.commitStreamedFinalized(first.template, exposureTimestampNanoseconds: 1,
                                                           components: [(.jpeg, CaptureFixture.jpeg)]))
    XCTAssertThrowsError(try store.rebaseSameAttempt(first), "the retried sync fails too")
    let slotA = root.appendingPathComponent("\(session())/root-A.ptr")
    let uncertain = try Data(contentsOf: slotA)

    let second = CaptureFixture.request(attemptId: "6f1c2d3e-0000-4000-8000-000000000002", commitId: "commit-2", ordinal: 2)
    let secondJpeg: [UInt8] = [0xff, 0xd8, 2, 2, 0xff, 0xd9]
    _ = try store.acceptCaptureBeforeExposure(second)
    XCTAssertThrowsError(try store.commitStreamedFinalized(second.template, exposureTimestampNanoseconds: 2,
                                                           components: [(.jpeg, secondJpeg)]),
                         "a commit is refused while the uncertain root cannot be made durable")
    XCTAssertEqual(try Data(contentsOf: slotA), uncertain, "the uncertain slot is never overwritten")
    XCTAssertFalse(exists("\(attempt(second))/receipt.properties"))

    failing = false
    let committed = try store.commitStreamedFinalized(second.template, exposureTimestampNanoseconds: 2,
                                                      components: [(.jpeg, secondJpeg)])
    XCTAssertEqual(committed.terminal?.captureRevision, 2, "the later commit chains after the uncertain root")
    let firstReceipt = try XCTUnwrap(try store.queryReceipt(first.accepted.identity)?.terminal)
    XCTAssertEqual(firstReceipt.kind, .committedPicture)
    XCTAssertEqual(firstReceipt.captureRevision, 1)
    XCTAssertEqual(try budget.reservedBytesTotal(), 0, "the resolved root's reservation is settled")
    XCTAssertEqual(try budget.committedBytes(), jpegBytes + Int64(secondJpeg.count), "each photo charged once")

    let (reopened, reopenedBudget) = try CaptureFixture.store(root)
    XCTAssertEqual(try reopened.recoverAndProject().compactMap { $0.captureRevision }.sorted(), [1, 2])
    XCTAssertEqual(try reopenedBudget.committedBytes(), jpegBytes + Int64(secondJpeg.count))
  }

  func testBudgetSettlementFaultKeepsTheCommittedPhotoAndSettlesOnceAtRestart() throws {
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
    camera.deliverJpeg()
    XCTAssertGreaterThan(fault.injected, 0)
    XCTAssertEqual(terminals().map { $0.kind }, [.committed], "the published root is the terminal")
    XCTAssertTrue(exists(blob(request)))
    fault.armed = false
    XCTAssertGreaterThan(try budget.reservedBytesTotal(), 0, "settlement failed, the liability stays")
    try assertRestartRebases(request, "budget settlement")
  }

  func testPartialReceiptIsUnknownAndKeepsEveryFile() throws {
    var partial: PartialWriteBackend?
    let (store, budget) = try CaptureFixture.store(root, storeBackend: { inner in
      let wrapped = PartialWriteBackend(inner, name: "receipt.properties")
      partial = wrapped
      return wrapped
    })
    let owner = owner(store)
    let request = CaptureFixture.request()
    try commitThroughOwner(owner, request)
    XCTAssertEqual(partial?.partialWrites, 1)
    try assertHandedToRestart(owner, request, budget, "partial receipt")
    let terminal = CaptureTerminal(kind: .abandonedAttempt, identity: request.accepted.identity, canonicalTerminalHash: "x", reason: "x")
    XCTAssertThrowsError(try store.abandonTemplate(request.template, terminal: terminal)) { XCTAssertTrue($0 is DurableReceiptUnknown) }

    for pass in 1...2 {
      let (restarted, restartedBudget) = try CaptureFixture.store(root)
      let projected = try restarted.recoverAndProject()
      XCTAssertEqual(projected.map { $0.reason }, ["recovered-receipt-unknown"], "pass \(pass)")
      XCTAssertNil(projected.first?.kind)
      XCTAssertNoThrow(try restarted.recover(), "pass \(pass)")
      XCTAssertTrue(exists(blob(request)), "pass \(pass)")
      XCTAssertTrue(exists("\(attempt(request))/staging"), "pass \(pass)")
      XCTAssertGreaterThan(try restartedBudget.reservedBytesTotal(), 0, "pass \(pass): liability kept")
      XCTAssertEqual(try restartedBudget.committedBytes(), 0)
    }
  }

  func testTerminalFenceKeepsAnUnresolvedReceiptForRestart() throws {
    let fault = PersistentFault(.pointerSlotReplace)
    let (store, budget) = try CaptureFixture.store(root, fault: fault.injector())
    let owner = owner(store)
    let request = CaptureFixture.request()
    _ = try owner.admit(request)
    // The components reached the store through an earlier delivery whose
    // pointer cut is unknown; the camera callback of this owner never came.
    XCTAssertThrowsError(try store.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: 1_000_000,
                                                           components: [(.jpeg, CaptureFixture.jpeg)]))
    now += NativeCaptureAdmissionOwner.terminalFenceMs
    owner.advanceDeadlines()
    XCTAssertEqual(events.last { $0.kind == .recovering }?.reason, "terminal-fence-30s")
    try assertHandedToRestart(owner, request, budget, "fence")
    camera.deliverJpeg()
    XCTAssertTrue(terminals().isEmpty, "a late still after the hand-off is discarded")
    try assertRestartRebases(request, "fence")
  }

  func testReceiptWriteCutBeforeTheRecordAbandonsWithoutATrace() throws {
    let plan = FaultPlan(.receiptWrite)
    let (store, budget) = try CaptureFixture.store(root, fault: plan.injector())
    let request = CaptureFixture.request()
    try commitThroughOwner(owner(store), request)
    XCTAssertEqual(terminals().map { $0.kind }, [.abandoned], "no receipt: nothing can be durable")
    XCTAssertFalse(exists(blob(request)))
    XCTAssertEqual(try budget.reservedBytesTotal(), 0)
    XCTAssertEqual(try budget.committedBytes(), 0)
  }

  // MARK: Ledger

  func testLedgerOpensAboveItsQuotaAndRefusesOnlyNewCharges() throws {
    let (store, _) = try CaptureFixture.store(root)
    let first = CaptureFixture.request()
    _ = try store.acceptCaptureBeforeExposure(first)
    _ = try store.commitStreamedFinalized(first.template, exposureTimestampNanoseconds: 1, components: [(.jpeg, CaptureFixture.jpeg)])

    // Free space shrank: the quota is now below the committed bytes.
    let (reopened, budget) = try CaptureFixture.store(root, quota: 4)
    XCTAssertEqual(try budget.committedBytes(), jpegBytes)
    XCTAssertEqual(try reopened.recoverAndProject().first?.kind, .committedPicture)
    XCTAssertEqual(try reopened.queryReceipt(first.accepted.identity)?.phase, .committedPicture)
    let second = CaptureFixture.request(attemptId: "second", commitId: "commit-2", ordinal: 2)
    XCTAssertThrowsError(try reopened.acceptCaptureBeforeExposure(second)) { error in
      XCTAssertEqual(NativeCaptureChannel.flutterError(error).code, "NATIVE_CAPTURE_V2_STORAGE_FULL")
    }
    XCTAssertEqual(try budget.reservedBytesTotal(), 0)
  }

  private func commit(_ store: DurableCaptureStore, session: String, index: Int, bytes: [UInt8]) throws -> CaptureCommitRequest {
    let request = CaptureFixture.request(attemptId: "\(session)-attempt-\(index)", commitId: "\(session)-commit-\(index)",
                                         ordinal: Int64(index), session: session)
    _ = try store.acceptCaptureBeforeExposure(request)
    _ = try store.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: Int64(index), components: [(.jpeg, bytes)])
    return request
  }

  func testSessionDeletionReclaimsItsCommittedBytesOnce() throws {
    let (store, budget) = try CaptureFixture.store(root)
    let deleted = try commit(store, session: "deleted", index: 1, bytes: [1, 2, 3])
    _ = try commit(store, session: "deleted", index: 2, bytes: [4, 5, 6, 7])
    let kept = try commit(store, session: "kept", index: 1, bytes: [9, 9])
    let pending = CaptureFixture.request(attemptId: "deleted-pending", commitId: "deleted-pending", ordinal: 3, session: "deleted")
    _ = try store.acceptCaptureBeforeExposure(pending)
    XCTAssertEqual(try budget.committedBytes(), 9)

    XCTAssertEqual(try store.deleteSession("deleted"), 7)
    XCTAssertEqual(try budget.committedBytes(), 2)
    XCTAssertEqual(try budget.reservedBytesTotal(), 0, "the pending attempt's reservation is released")
    XCTAssertTrue(exists("\(session("deleted"))/tombstone"))
    XCTAssertFalse(exists("\(session("deleted"))/assets"))
    XCTAssertFalse(exists(blob(deleted, [1, 2, 3])))
    XCTAssertTrue(exists(blob(kept, [9, 9])))
    let marker = "budget/reclaims-v2/verified-\(DurableCaptureStore.sessionReclaimId("deleted")).reclaim"
    XCTAssertEqual(try String(contentsOf: root.appendingPathComponent(marker), encoding: .utf8),
                   "DONE\n\(DurableCaptureStore.sessionReclaimId("deleted"))\n7\n9\n")

    XCTAssertEqual(try store.deleteSession("deleted"), 0, "a repeated deletion reclaims nothing")
    let (restarted, restartedBudget) = try CaptureFixture.store(root)
    XCTAssertEqual(try restarted.deleteSession("deleted"), 0)
    XCTAssertEqual(try restartedBudget.committedBytes(), 2)
    XCTAssertEqual(try restarted.queryReceipt(kept.accepted.identity)?.phase, .committedPicture)
    XCTAssertThrowsError(try restarted.acceptCaptureBeforeExposure(pending), "the deleted session stays fenced")
  }

  func testSessionDeletionReclaimsOnceAfterACrashAtEveryStep() throws {
    let cuts: [(String, DurableStoreFaultPoint)] = [
      ("budget", .pointerSlotReplace), ("budget", .deleteReclaim),
      ("store", .tombstoneRecord), ("store", .tombstoneDirectorySync), ("store", .deleteReclaim),
    ]
    var exercised = 0
    for (side, point) in cuts {
      for nth in 1...12 {
        try FileManager.default.removeItem(at: root)
        root = CaptureFixture.temporaryDirectory("durable-recovery")
        let (setup, _) = try CaptureFixture.store(root)
        _ = try commit(setup, session: "deleted", index: 1, bytes: [1, 2, 3])
        // A committed receipt whose pointer switch never happened: its
        // reservation is released on deletion, never charged or reclaimed.
        let unknownCut = PersistentFault(.pointerSlotReplace)
        let (unresolved, _) = try CaptureFixture.store(root, fault: unknownCut.injector())
        let request = CaptureFixture.request(attemptId: "deleted-attempt-2", commitId: "deleted-commit-2", ordinal: 2, session: "deleted")
        _ = try unresolved.acceptCaptureBeforeExposure(request)
        XCTAssertThrowsError(try unresolved.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: 2, components: [(.jpeg, [4, 5])]))
        _ = try commit(setup, session: "kept", index: 1, bytes: [9, 9])
        let pending = CaptureFixture.request(attemptId: "deleted-pending", commitId: "deleted-pending", ordinal: 3, session: "deleted")
        _ = try setup.acceptCaptureBeforeExposure(pending)

        let cut = NthVisitFault(point, nth: nth)
        let (poisoned, _) = side == "budget"
          ? try CaptureFixture.store(root, budgetFault: cut.injector())
          : try CaptureFixture.store(root, fault: cut.injector())
        let label = "\(side) \(point) #\(nth)"
        do {
          try poisoned.deleteSession("deleted")
          XCTAssertLessThan(cut.visits, nth, "\(label) completed although its cut was reached")
          break
        } catch {
          exercised += 1
        }
        let (clean, budget) = try CaptureFixture.store(root)
        try clean.deleteSession("deleted")
        try clean.deleteSession("deleted")
        XCTAssertEqual(try budget.committedBytes(), 2, "\(label): only the kept session stays charged")
        XCTAssertEqual(try budget.reservedBytesTotal(), 0, label)
        XCTAssertFalse(exists("\(session("deleted"))/assets"), label)
        XCTAssertTrue(exists("\(session("deleted"))/tombstone"), label)
        XCTAssertEqual(try clean.recoverAndProject().compactMap { $0.kind }, [.committedPicture], label)
      }
    }
    XCTAssertGreaterThanOrEqual(exercised, 8, "the crash matrix reached the reclaim steps")
  }
}
