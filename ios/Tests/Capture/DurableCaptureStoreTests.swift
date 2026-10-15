import XCTest
@testable import ar_flutter_plugin_2

/// The iOS durable photo store: Android layout and records, replay fences,
/// startup projection, and a crash at every durable write step followed by a
/// restart (a new store over the same files) that finds either no terminal or
/// one complete terminal, releases or charges the reservation exactly once
/// and accepts a later capture.
final class DurableCaptureStoreTests: XCTestCase {
  private var root: URL!

  override func setUp() {
    super.setUp()
    root = CaptureFixture.temporaryDirectory("durable-store")
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
    super.tearDown()
  }

  private func path(_ relative: String) -> String { root.appendingPathComponent(relative).path }
  private func exists(_ relative: String) -> Bool { FileManager.default.fileExists(atPath: path(relative)) }
  private func text(_ relative: String) throws -> String { try String(contentsOfFile: path(relative), encoding: .utf8) }

  func testCommitWritesTheAndroidLayoutAndRecords() throws {
    let (store, budget) = try CaptureFixture.store(root)
    let request = CaptureFixture.request()
    let accepted = try store.acceptCaptureBeforeExposure(request)
    XCTAssertEqual(accepted.phase, .reservedAccepted)
    XCTAssertEqual(try budget.reservedBytesTotal(), request.accepted.reservation.totalStoreLiability)

    let session = "store/sessions/bfe0190c7d522f78adc769817d0ca814cf7fd6d83d24abf297bcad17cfe10ac6"
    let attempt = "\(session)/attempts/69fdd6276a37f5e42af7f0405a21703b605543d088242983ac425fa848b38906"
    let acceptedValues = DurableStoreEncoding.parseProperties(Array(try text("\(attempt)/accepted.properties").utf8))
    XCTAssertEqual(acceptedValues["acceptedHash"], "ea05f93ec0597022acbec613b42645c71b298d597ab39a9164e788758dc663f5")
    XCTAssertEqual(acceptedValues["acceptedIdentityVersion"], "1")
    XCTAssertEqual(acceptedValues["recovery.position"], "0.25,-1.5,2.0")
    XCTAssertEqual(acceptedValues["recovery.viewMatrix"], "1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0")
    XCTAssertEqual(acceptedValues["reservationBytes"], String(request.accepted.reservation.totalStoreLiability))

    let receipt = try store.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: 1_000_000, components: [(.jpeg, CaptureFixture.jpeg)])
    let terminal = try XCTUnwrap(receipt.terminal)
    XCTAssertEqual(receipt.phase, .committedPicture)
    XCTAssertEqual(terminal.manifestId, "780001b3ed620e8e96135827fe6961238b262b52ebac89108d43f2dc0036af53")
    XCTAssertEqual(terminal.captureId, "e94ebffc90c4bfd8a755f41c8d748d3d834378fc0e076eee9df77e188070bd73")
    XCTAssertEqual(terminal.captureRevision, 1)
    XCTAssertEqual(terminal.jpegSizeBytes, Int64(CaptureFixture.jpeg.count))
    XCTAssertEqual(receipt.receiptHash, "fc0c509d85b124bd7772e94c0bf6f4fe1badf03801ef967f9306a5c027767b3f")

    XCTAssertTrue(exists("\(session)/assets/e94ebffc90c4bfd8a755f41c8d748d3d834378fc0e076eee9df77e188070bd73/jpeg.5a69598ccc6ebe57596a1a06128c64d10b5ffe498a65d0ad6de6df058a971c87.blob"))
    XCTAssertTrue(exists("\(session)/objects/780001b3ed620e8e96135827fe6961238b262b52ebac89108d43f2dc0036af53.root"))
    XCTAssertEqual(try text("\(session)/root-A.ptr"), "1\n780001b3ed620e8e96135827fe6961238b262b52ebac89108d43f2dc0036af53\ncommit-1\n")
    XCTAssertFalse(exists("\(attempt)/staging"))
    let receiptValues = DurableStoreEncoding.parseProperties(Array(try text("\(attempt)/receipt.properties").utf8))
    XCTAssertEqual(receiptValues, [
      "kind": "COMMITTED_PICTURE", "requestHash": "80df787568709cd10266c528fadd569048cbf0d7e4b477e7695e304a61917dbf",
      "receiptHash": "fc0c509d85b124bd7772e94c0bf6f4fe1badf03801ef967f9306a5c027767b3f",
      "rootHash": "780001b3ed620e8e96135827fe6961238b262b52ebac89108d43f2dc0036af53", "reason": "committed",
      "captureId": "e94ebffc90c4bfd8a755f41c8d748d3d834378fc0e076eee9df77e188070bd73", "revision": "1",
      "jpegSizeBytes": String(CaptureFixture.jpeg.count), "templateHash": "924d9c0ec6f17090fe4caa6e145b5239a66b27e673a821d14ecc67f88d894244",
    ])
    let index = DurableStoreEncoding.parseProperties(Array(try text("store/recovery-index.properties").utf8))
    XCTAssertEqual(index["schema"], "bounded-recovery-index-v1")
    XCTAssertEqual(index["count"], "1")
    XCTAssertEqual(try budget.reservedBytesTotal(), 0)
    XCTAssertEqual(try budget.committedBytes(), Int64(CaptureFixture.jpeg.count))
    XCTAssertEqual(try text("budget/ledger-v2"), "\(CaptureFixture.jpeg.count)\n")
  }

  func testSecondCommitChainsRootsAndAlternatesPointerSlots() throws {
    let (store, _) = try CaptureFixture.store(root)
    let first = CaptureFixture.request()
    _ = try store.acceptCaptureBeforeExposure(first)
    let one = try store.commitStreamedFinalized(first.template, exposureTimestampNanoseconds: 1, components: [(.jpeg, [1, 2, 3])])
    let second = CaptureFixture.request(attemptId: "attempt-2", commitId: "commit-2", ordinal: 2)
    _ = try store.acceptCaptureBeforeExposure(second)
    let two = try store.commitStreamedFinalized(second.template, exposureTimestampNanoseconds: 2, components: [(.jpeg, [4, 5])])
    XCTAssertEqual(two.terminal?.captureRevision, 2)
    let session = "store/sessions/\(DurableStoreEncoding.safeName("session-ios"))"
    XCTAssertTrue(try text("\(session)/root-B.ptr").hasPrefix("2\n\(two.terminal!.manifestId!)\n"))
    let rootText = try text("\(session)/objects/\(two.terminal!.manifestId!).root")
    XCTAssertTrue(rootText.contains("previous=\(one.terminal!.manifestId!)\nsecondPrevious=-\n"))
    // Both receipts stay authoritative through the retained graph.
    XCTAssertEqual(try store.queryReceipt(first.accepted.identity)?.terminal?.manifestId, one.terminal?.manifestId)
  }

  func testReplayFenceReturnsTheExactTerminalAndRefusesAChangedTemplate() throws {
    let (store, _) = try CaptureFixture.store(root)
    let request = CaptureFixture.request()
    XCTAssertNil(try store.replayTemplateFenceBeforeExposure(request.template))
    _ = try store.acceptCaptureBeforeExposure(request)
    XCTAssertEqual(try store.replayTemplateFenceBeforeExposure(request.template)?.phase, .reservedAccepted)
    let committed = try store.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: 9, components: [(.jpeg, CaptureFixture.jpeg)])
    XCTAssertEqual(try store.replayTemplateFenceBeforeExposure(request.template), committed)
    // A retried commit of the same attempt returns the durable terminal; no second root.
    XCTAssertEqual(try store.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: 10, components: [(.jpeg, [9])]), committed)
    var changed = request
    changed.poseRecordHash = CaptureFixture.sha("other pose")
    XCTAssertThrowsError(try store.replayTemplateFenceBeforeExposure(changed.template)) { XCTAssertTrue($0 is DurableStoreConflict) }
  }

  func testAbandonmentReleasesTheReservationAndIsTerminal() throws {
    let (store, budget) = try CaptureFixture.store(root)
    let request = CaptureFixture.request()
    _ = try store.acceptCaptureBeforeExposure(request)
    let terminal = CaptureTerminal(kind: .abandonedAttempt, identity: request.accepted.identity, canonicalTerminalHash: "camera-failed", reason: "camera-failed")
    let abandoned = try store.abandonTemplate(request.template, terminal: terminal)
    XCTAssertEqual(abandoned.phase, .abandonedAttempt)
    XCTAssertEqual(try budget.reservedBytesTotal(), 0)
    XCTAssertEqual(try store.abandonTemplate(request.template, terminal: terminal), abandoned)
    XCTAssertEqual(try store.replayTemplateFenceBeforeExposure(request.template)?.terminal?.reason, "camera-failed")
  }

  func testStartupProjectionReportsTheTwoNewestOutcomes() throws {
    var (store, _) = try CaptureFixture.store(root)
    let requests = (1...3).map { CaptureFixture.request(attemptId: "attempt-\($0)", commitId: "commit-\($0)", ordinal: Int64($0)) }
    for request in requests { _ = try store.acceptCaptureBeforeExposure(request) }
    _ = try store.commitStreamedFinalized(requests[1].template, exposureTimestampNanoseconds: 5, components: [(.jpeg, [7, 7])])
    // Restart: attempt-3 has no receipt (proven absent), attempt-2 committed.
    (store, _) = try CaptureFixture.store(root)
    let projected = try store.recoverAndProject()
    XCTAssertEqual(projected.map { $0.attemptId }, ["attempt-3", "attempt-2"])
    XCTAssertEqual(projected[0].kind, .abandonedAttempt)
    XCTAssertEqual(projected[0].reason, "recovered-proven-absent")
    XCTAssertEqual(projected[1].kind, .committedPicture)
    XCTAssertEqual(projected[1].reason, "recovered-committed")
    XCTAssertEqual(projected[1].jpegSizeBytes, 2)
    XCTAssertEqual(projected[1].recoveryContext, CaptureFixture.context())
  }

  /// Android `generated durable fault matrix`: every durable step is cut once.
  func testCrashAtEveryWriteStepRecoversToOneOutcomeAndReleasesOnce() throws {
    XCTAssertEqual(DurableStoreFaultPoint.allCases.count, 21)
    for point in DurableStoreFaultPoint.allCases {
      try FileManager.default.removeItem(at: root)
      root = CaptureFixture.temporaryDirectory("durable-store")
      let plan = FaultPlan(point)
      let (poisoned, _) = try CaptureFixture.store(root, fault: plan.injector())
      let request = CaptureFixture.request(attemptId: "fault-\(point.rawValue)", commitId: "commit-\(point.rawValue)")
      let clean = { try CaptureFixture.store(self.root) }

      switch point {
      case .acceptedRecord:
        XCTAssertThrowsError(try poisoned.acceptCaptureBeforeExposure(request), "\(point)")
        XCTAssertNil(try clean().0.queryReceipt(request.accepted.identity))
        XCTAssertEqual(try clean().1.reservedBytesTotal(), 0, "\(point)")
      case .abandonmentRecord, .abandonmentCleanup:
        _ = try poisoned.acceptCaptureBeforeExposure(request)
        let terminal = CaptureTerminal(kind: .abandonedAttempt, identity: request.accepted.identity, canonicalTerminalHash: "injected", reason: "injected")
        XCTAssertThrowsError(try poisoned.abandonTemplate(request.template, terminal: terminal), "\(point)")
        XCTAssertNil(try clean().0.queryReceipt(request.accepted.identity))
        XCTAssertGreaterThan(try clean().1.reservedBytesTotal(), 0)
        try clean().0.recover()
        XCTAssertEqual(try clean().0.queryReceipt(request.accepted.identity)?.phase, .abandonedAttempt, "\(point)")
        XCTAssertEqual(try clean().1.reservedBytesTotal(), 0, "\(point)")
      case .tombstoneRecord, .tombstoneDirectorySync:
        _ = try poisoned.acceptCaptureBeforeExposure(request)
        XCTAssertThrowsError(try poisoned.tombstoneSession("session-ios"), "\(point)")
        try clean().0.recover()
        if point == .tombstoneRecord {
          XCTAssertEqual(try clean().0.queryReceipt(request.accepted.identity)?.phase, .abandonedAttempt, "\(point)")
        } else {
          XCTAssertNil(try clean().0.queryReceipt(request.accepted.identity), "\(point)")
        }
        XCTAssertEqual(try clean().1.reservedBytesTotal(), 0, "\(point)")
      case .recovery:
        _ = try poisoned.acceptCaptureBeforeExposure(request)
        XCTAssertThrowsError(try poisoned.recover(), "\(point)")
        XCTAssertNil(try clean().0.queryReceipt(request.accepted.identity))
        XCTAssertGreaterThan(try clean().1.reservedBytesTotal(), 0)
        try clean().0.recover()
        XCTAssertEqual(try clean().0.queryReceipt(request.accepted.identity)?.phase, .abandonedAttempt, "\(point)")
        XCTAssertEqual(try clean().1.reservedBytesTotal(), 0, "\(point)")
      default:
        _ = try poisoned.acceptCaptureBeforeExposure(request)
        XCTAssertThrowsError(
          try poisoned.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: 3, components: [(.jpeg, CaptureFixture.jpeg)]),
          "\(point)")
        switch point {
        case .pointerDirectorySync:
          // The rename happened but its directory sync is unknown: the owner
          // that saw the failure does not trust the new root; a restart finds
          // the renamed pointer and recovery charges the bytes once.
          XCTAssertNil(try poisoned.queryReceipt(request.accepted.identity), "\(point)")
          XCTAssertEqual(try clean().0.queryReceipt(request.accepted.identity)?.phase, .committedPicture, "\(point)")
          XCTAssertGreaterThan(try clean().1.reservedBytesTotal(), 0, "\(point)")
          try clean().0.recover()
          XCTAssertEqual(try clean().1.reservedBytesTotal(), 0, "\(point)")
        case .receiptFileSync, .pointerSlotReplace:
          // The receipt is durable but the pointer cut is unknown: no terminal
          // is visible, the liability stays for an exact query or rebase.
          XCTAssertNil(try clean().0.queryReceipt(request.accepted.identity), "\(point)")
          XCTAssertGreaterThan(try clean().1.reservedBytesTotal(), 0, "\(point)")
          try clean().0.recover()
          XCTAssertNil(try clean().0.queryReceipt(request.accepted.identity), "\(point) stays unknown, never abandoned")
          XCTAssertTrue(exists("store/sessions/\(DurableStoreEncoding.safeName("session-ios"))/attempts/\(DurableStoreEncoding.safeName(request.accepted.identity.commitId))/receipt.properties"))
        case .deleteReclaim:
          XCTAssertEqual(try clean().0.queryReceipt(request.accepted.identity)?.phase, .committedPicture, "\(point)")
          try clean().0.recover()
          XCTAssertEqual(try clean().1.reservedBytesTotal(), 0, "\(point)")
        default:
          XCTAssertNil(try clean().0.queryReceipt(request.accepted.identity), "\(point)")
          XCTAssertGreaterThan(try clean().1.reservedBytesTotal(), 0, "\(point)")
          try clean().0.recover()
          XCTAssertEqual(try clean().0.queryReceipt(request.accepted.identity)?.phase, .abandonedAttempt, "\(point)")
          XCTAssertEqual(try clean().1.reservedBytesTotal(), 0, "\(point)")
        }
      }
      XCTAssertGreaterThan(plan.injected, 0, "fault cut was not exercised: \(point)")

      // A later capture after the restart commits normally.
      let (recovered, budget) = try clean()
      let session = point == .tombstoneDirectorySync || point == .tombstoneRecord ? "session-after-tombstone" : "session-ios"
      let later = CaptureFixture.request(attemptId: "later-\(point.rawValue)", commitId: "later-\(point.rawValue)", session: session)
      let reservedBefore = try budget.reservedBytesTotal()
      XCTAssertEqual(try recovered.acceptCaptureBeforeExposure(later).phase, .reservedAccepted, "\(point)")
      XCTAssertEqual(try recovered.commitStreamedFinalized(later.template, exposureTimestampNanoseconds: 4, components: [(.jpeg, [1, 2])]).phase,
                     .committedPicture, "\(point)")
      XCTAssertEqual(try budget.reservedBytesTotal(), reservedBefore, "\(point)")
    }
  }

  func testTombstonedSessionRefusesLateCallbacks() throws {
    let (store, budget) = try CaptureFixture.store(root)
    let request = CaptureFixture.request()
    _ = try store.acceptCaptureBeforeExposure(request)
    try store.tombstoneSession("session-ios")
    XCTAssertEqual(try budget.reservedBytesTotal(), 0)
    XCTAssertThrowsError(try store.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: 1, components: [(.jpeg, [1])]))
    XCTAssertNil(try store.queryReceipt(request.accepted.identity))
  }

  func testBudgetRefusesBeyondQuota() throws {
    let (store, _) = try CaptureFixture.store(root, quota: 1024)
    XCTAssertThrowsError(try store.acceptCaptureBeforeExposure(CaptureFixture.request()))
  }
}
