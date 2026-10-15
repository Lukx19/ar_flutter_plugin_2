import XCTest
@testable import ar_flutter_plugin_2

/// Startup tolerance and reservation reconciliation of the durable store: an
/// accepted record whose write never finished is repaired as proven absent
/// and dropped from the recovery index instead of failing recovery; at
/// binding open every live reservation is reconciled with its accepted
/// record, so reservations that the bounded startup recovery did not reach
/// are released, settled or kept by the recovery rules, and one failing
/// reservation never fails the open.
final class DurableCaptureReconciliationTests: XCTestCase {
  private var root: URL!
  private let jpegBytes = Int64(CaptureFixture.jpeg.count)

  override func setUp() {
    super.setUp()
    root = CaptureFixture.temporaryDirectory("durable-reconciliation")
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
    super.tearDown()
  }

  // MARK: Paths

  private func url(_ relative: String) -> URL { root.appendingPathComponent(relative) }
  private func exists(_ relative: String) -> Bool { FileManager.default.fileExists(atPath: url(relative).path) }
  private func session(_ id: String = "session-ios") -> String { "store/sessions/\(DurableStoreEncoding.safeName(id))" }
  private func attempt(_ request: CaptureCommitRequest) -> String {
    "\(session(request.accepted.identity.lifecycleCut.sessionId))/attempts/\(DurableStoreEncoding.safeName(request.accepted.identity.commitId))"
  }
  private func blob(_ request: CaptureCommitRequest) -> String {
    let digest = DurableStoreEncoding.hex(DurableStoreEncoding.sha256(CaptureFixture.jpeg))
    return "\(session(request.accepted.identity.lifecycleCut.sessionId))/assets/\(DurableStoreEncoding.safeName(request.accepted.identity.attemptId))/jpeg.\(digest).blob"
  }
  private func receiptValues(_ request: CaptureCommitRequest) throws -> [String: String] {
    DurableStoreEncoding.parseProperties(Array(try Data(contentsOf: url("\(attempt(request))/receipt.properties"))))
  }

  private func request(_ index: Int) -> CaptureCommitRequest {
    CaptureFixture.request(attemptId: String(format: "6f1c2d3e-0000-4000-8000-%012d", index), commitId: "commit-\(index)",
                           ordinal: Int64(index))
  }

  private func commit(_ store: DurableCaptureStore, _ request: CaptureCommitRequest) throws {
    _ = try store.acceptCaptureBeforeExposure(request)
    _ = try store.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: request.accepted.identity.attemptOrdinal,
                                          components: [(.jpeg, CaptureFixture.jpeg)])
  }

  /// The recovery index is a bounded hint: without it startup recovery
  /// reaches no attempt, as for attempts beyond its capacity.
  private func dropRecoveryIndex() throws {
    try FileManager.default.removeItem(at: url("store/recovery-index.properties"))
  }

  // MARK: Unfinished acceptance

  func testUnfinishedAcceptedRecordIsDroppedAndCaptureContinues() throws {
    for variant in ["empty", "partial", "recovery"] {
      try? FileManager.default.removeItem(at: root)
      root = CaptureFixture.temporaryDirectory("durable-reconciliation")
      let (store, _) = try CaptureFixture.store(root)
      let crashed = request(1)
      _ = try store.acceptCaptureBeforeExposure(crashed)
      // The record was created under its final name after its index entry;
      // the crash came before (or while) its bytes were written.
      let record = url("\(attempt(crashed))/accepted.properties")
      let written = try String(contentsOf: record, encoding: .utf8)
      let cut: String
      switch variant {
      case "empty": cut = ""
      case "partial": cut = String(written[..<written.range(of: "attemptId=")!.lowerBound])
      default:
        // Cut inside the last recovery value: the attempt identity and the
        // context session are present, the context is short and `session`
        // is never reached.
        let value = written.range(of: "recovery.worldFromGroup=")!.upperBound
        cut = String(written[..<written.index(value, offsetBy: 5)])
        XCTAssertTrue(cut.contains("attemptId=") && cut.contains("recovery.sessionId="))
      }
      try Data(cut.utf8).write(to: record)

      let (restarted, budget) = try CaptureFixture.store(root)
      var projected: [DurableCaptureStore.RecoveryProjection] = []
      XCTAssertNoThrow(projected = try restarted.recoverAndProject(), variant)
      XCTAssertTrue(projected.isEmpty, "\(variant): an unfinished acceptance is not projected")
      let index = DurableStoreEncoding.parseProperties(Array(try Data(contentsOf: url("store/recovery-index.properties"))))
      XCTAssertEqual(index["count"], "0", "\(variant): its index entry is dropped")
      XCTAssertEqual(try receiptValues(crashed)["reason"], "recovered-proven-absent", variant)
      XCTAssertEqual(try receiptValues(crashed)["requestBinding"], DurableCaptureStore.bindingRecovered, variant)
      XCTAssertEqual(restarted.reconcileReservations(), 1, "\(variant): its reservation names no accepted token")
      XCTAssertEqual(try budget.reservedBytesTotal(), 0, variant)

      let next = request(2)
      try commit(restarted, next)
      XCTAssertEqual(try restarted.queryReceipt(next.accepted.identity)?.phase, .committedPicture, variant)
      XCTAssertEqual(try budget.reservedBytesTotal(), 0, variant)
      XCTAssertEqual(try budget.committedBytes(), jpegBytes, variant)
      let (again, _) = try CaptureFixture.store(root)
      XCTAssertEqual(try again.recoverAndProject().map { $0.attemptId }, [next.accepted.identity.attemptId], variant)
    }
  }

  /// A finished record with a malformed recovery context is not an
  /// unfinished write: recovery still fails instead of dropping it.
  func testFinishedAcceptedRecordWithMalformedContextStillFailsRecovery() throws {
    let (store, _) = try CaptureFixture.store(root)
    let accepted = request(1)
    _ = try store.acceptCaptureBeforeExposure(accepted)
    let record = url("\(attempt(accepted))/accepted.properties")
    var values = DurableStoreEncoding.parseProperties(Array(try Data(contentsOf: record)))
    values["recovery.position"] = "1,2"
    try Data(DurableStoreEncoding.propertiesBytes(values)).write(to: record)

    let (restarted, _) = try CaptureFixture.store(root)
    XCTAssertThrowsError(try restarted.recoverAndProject())
  }

  // MARK: Reservation reconciliation

  func testReservationWithoutAcceptedRecordIsReleased() throws {
    let (_, budget) = try CaptureFixture.store(root)
    let crashed = request(1)
    // Crash between the reservation and the accepted record.
    let reservation = try XCTUnwrap(try budget.reserve(
      owner: DurableStoreEncoding.reservationOwner(crashed.accepted.identity), bytes: crashed.accepted.reservation.totalStoreLiability))

    let (restarted, restartedBudget) = try CaptureFixture.store(root)
    XCTAssertTrue(try restarted.recoverAndProject().isEmpty)
    XCTAssertEqual(try restartedBudget.reservedBytesTotal(), reservation.bytes, "bounded recovery does not see it")
    XCTAssertEqual(restarted.reconcileReservations(), 1)
    XCTAssertEqual(try restartedBudget.reservedBytesTotal(), 0)
    XCTAssertFalse(exists("budget/reservations-v2/\(reservation.token).allocation"))
  }

  func testReservationNamedByNoAcceptedRecordIsReleasedBesideTheAcceptedOne() throws {
    let (store, budget) = try CaptureFixture.store(root)
    let accepted = request(1)
    _ = try store.acceptCaptureBeforeExposure(accepted)
    let stray = try XCTUnwrap(try budget.reserve(
      owner: DurableStoreEncoding.reservationOwner(accepted.accepted.identity), bytes: 4096))
    try dropRecoveryIndex()

    let (restarted, restartedBudget) = try CaptureFixture.store(root)
    XCTAssertEqual(restarted.reconcileReservations(), 2)
    XCTAssertNil(try restartedBudget.reservation(token: stray.token))
    XCTAssertEqual(try restartedBudget.reservedBytesTotal(), 0)
    XCTAssertEqual(try receiptValues(accepted)["reason"], "recovered-proven-absent", "the accepted attempt is proven absent")
  }

  func testEveryLeakedAttemptBeyondTheBoundedRecoveryIsReclaimed() throws {
    let (store, budget) = try CaptureFixture.store(root)
    let leaked = (1...5).map(request)
    for value in leaked { _ = try store.acceptCaptureBeforeExposure(value) }
    XCTAssertEqual(try budget.reservedBytesTotal(), 5 * leaked[0].accepted.reservation.totalStoreLiability)

    let (restarted, restartedBudget) = try CaptureFixture.store(root)
    XCTAssertEqual(try restarted.recoverAndProject().count, 2)
    XCTAssertGreaterThan(try restartedBudget.reservedBytesTotal(), 0, "bounded recovery repairs at most two")
    XCTAssertEqual(restarted.reconcileReservations(), 3)
    XCTAssertEqual(try restartedBudget.reservedBytesTotal(), 0)
    for value in leaked {
      XCTAssertEqual(try receiptValues(value)["reason"], "recovered-proven-absent", value.accepted.identity.commitId)
    }
    XCTAssertEqual(restarted.reconcileReservations(), 0, "a second pass finds nothing")
  }

  func testCommittedReceiptInTheRetainedGraphIsSettledWithItsAssets() throws {
    // The settlement is cut before its marker is durable (its rename):
    // the reservation stays live. A cut after the marker is finished by the
    // ledger itself (StorageBudgetLedgerTests).
    let fault = PersistentFault(.pointerSlotReplace)
    fault.armed = false
    let (store, budget) = try CaptureFixture.store(root, budgetFault: fault.injector())
    let committed = request(1)
    _ = try store.acceptCaptureBeforeExposure(committed)
    fault.armed = true
    // The root is published; the settlement of its reservation fails.
    _ = try? store.commitStreamedFinalized(committed.template, exposureTimestampNanoseconds: 1, components: [(.jpeg, CaptureFixture.jpeg)])
    fault.armed = false
    XCTAssertGreaterThan(try budget.reservedBytesTotal(), 0)
    try dropRecoveryIndex()

    let (restarted, restartedBudget) = try CaptureFixture.store(root)
    XCTAssertEqual(restarted.reconcileReservations(), 1)
    XCTAssertEqual(try restartedBudget.reservedBytesTotal(), 0)
    XCTAssertEqual(try restartedBudget.committedBytes(), jpegBytes, "charged exactly once")
    XCTAssertEqual(try restarted.queryReceipt(committed.accepted.identity)?.phase, .committedPicture)
    XCTAssertTrue(exists(blob(committed)), "the committed asset is kept")
    XCTAssertFalse(exists("\(attempt(committed))/staging"))
  }

  func testPartialReceiptKeepsItsLiabilityAndEveryFile() throws {
    var partial: PartialWriteBackend?
    let (store, budget) = try CaptureFixture.store(root, storeBackend: { inner in
      let wrapped = PartialWriteBackend(inner, name: "receipt.properties")
      partial = wrapped
      return wrapped
    })
    let unknown = request(1)
    _ = try store.acceptCaptureBeforeExposure(unknown)
    XCTAssertThrowsError(try store.commitStreamedFinalized(unknown.template, exposureTimestampNanoseconds: 1,
                                                           components: [(.jpeg, CaptureFixture.jpeg)]))
    XCTAssertEqual(partial?.partialWrites, 1)
    let liability = try budget.reservedBytesTotal()
    try dropRecoveryIndex()

    let (restarted, restartedBudget) = try CaptureFixture.store(root)
    XCTAssertEqual(restarted.reconcileReservations(), 0)
    XCTAssertEqual(try restartedBudget.reservedBytesTotal(), liability, "the unknown cut keeps its liability")
    XCTAssertEqual(try restartedBudget.committedBytes(), 0)
    XCTAssertTrue(exists(blob(unknown)))
    XCTAssertTrue(exists("\(attempt(unknown))/staging"))
    XCTAssertTrue(exists("\(attempt(unknown))/receipt.properties"))
  }

  func testFailureOnOneReservationIsLoggedAndTheOthersAreReconciled() throws {
    let (store, budget) = try CaptureFixture.store(root)
    for value in [request(1), request(2)] { _ = try store.acceptCaptureBeforeExposure(value) }
    try dropRecoveryIndex()
    XCTAssertGreaterThan(try budget.reservedBytesTotal(), 0)

    let plan = FaultPlan(.abandonmentRecord)
    let (restarted, restartedBudget) = try CaptureFixture.store(root, fault: plan.injector())
    var logged: [String] = []
    XCTAssertEqual(restarted.reconcileReservations(log: { logged.append($0) }), 1)
    XCTAssertEqual(plan.injected, 1)
    XCTAssertEqual(logged.count, 1)
    XCTAssertEqual(try restartedBudget.reservedBytesTotal(), request(1).accepted.reservation.totalStoreLiability,
                   "the failed reservation stays for the next open")
    XCTAssertEqual(restarted.reconcileReservations(log: { logged.append($0) }), 1, "the next open finishes it")
    XCTAssertEqual(try restartedBudget.reservedBytesTotal(), 0)
  }
}
