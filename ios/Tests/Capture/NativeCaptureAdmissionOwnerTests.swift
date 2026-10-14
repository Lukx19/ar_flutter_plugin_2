import XCTest
@testable import ar_flutter_plugin_2

/// The admission owner over the real durable store and a fake camera:
/// durable acceptance before the only exposure, exactly one terminal per
/// attempt across duplicate, late and retried callbacks, restart replay
/// without a second exposure, deadlines and lifecycle transfer, and one
/// photo saved call per committed terminal.
final class NativeCaptureAdmissionOwnerTests: XCTestCase {
  private var root: URL!
  private var camera: FakeStillExposure!
  private var events: [NativeCaptureEvent] = []
  private var photos: [DurableTerminalPhoto] = []
  private var now: Int64 = 1_000

  override func setUp() {
    super.setUp()
    root = CaptureFixture.temporaryDirectory("admission-owner")
    camera = FakeStillExposure()
    events = []
    photos = []
    now = 1_000
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
    super.tearDown()
  }

  private func owner() throws -> (NativeCaptureAdmissionOwner, DurableCaptureStore, StorageBudgetLedger) {
    let (store, budget) = try CaptureFixture.store(root)
    let owner = NativeCaptureAdmissionOwner(
      store: store, exposure: camera, nowMs: { [unowned self] in self.now },
      events: { [unowned self] in self.events.append($0) }, durableTerminal: { [unowned self] in self.photos.append($0) })
    return (owner, store, budget)
  }

  private func terminals() -> [NativeCaptureEvent] { events.filter { $0.isTerminal } }

  func testAcceptedBeforeExposureThenOneCommittedTerminal() throws {
    let (owner, store, budget) = try owner()
    let request = CaptureFixture.request()
    let receipt = try owner.admit(request)
    XCTAssertEqual(receipt.phase, .reservedAccepted)
    XCTAssertNil(receipt.terminal)
    XCTAssertEqual(camera.requests.count, 1, "exposure is requested after durable acceptance")
    XCTAssertEqual(try store.replayTemplateFenceBeforeExposure(request.template)?.phase, .reservedAccepted)
    XCTAssertEqual(events.map { $0.kind }, [.accepted])

    camera.deliverJpeg()
    XCTAssertEqual(events.map { $0.kind }, [.accepted, .finalizing, .committed, .health])
    let committed = try XCTUnwrap(terminals().first)
    XCTAssertEqual(committed.attemptId, request.accepted.identity.attemptId)
    XCTAssertEqual(committed.jpegSizeBytes, Int64(CaptureFixture.jpeg.count))
    XCTAssertEqual(committed.recoveryContext, request.recoveryContext)
    XCTAssertEqual(photos.count, 1)
    XCTAssertEqual(photos.first?.metadata?.exposureTimestampNs, 1_000_000)
    XCTAssertEqual(try budget.reservedBytesTotal(), 0)
    XCTAssertEqual(owner.snapshot(), CaptureResourceSnapshot(exposures: 1, lateCallbacks: 0, closedComponents: 1, committed: 1, abandoned: 0, unknownQueries: 0, running: 0, fundedWaiting: 0))
  }

  func testDuplicateAndLateCallbacksNeverAddATerminal() throws {
    let (owner, _, _) = try owner()
    _ = try owner.admit(CaptureFixture.request())
    camera.deliverJpeg()
    camera.deliverJpeg()
    camera.fail()
    XCTAssertEqual(terminals().count, 1)
    XCTAssertEqual(photos.count, 1)
    XCTAssertEqual(owner.snapshot().lateCallbacks, 2)
  }

  func testRetriedAdmissionReturnsTheSameReceiptWithoutASecondExposure() throws {
    let (owner, _, _) = try owner()
    let request = CaptureFixture.request()
    let first = try owner.admit(request)
    XCTAssertEqual(try owner.admit(request), first, "a retried reply while active")
    XCTAssertEqual(camera.requests.count, 1)
    camera.deliverJpeg()
    let terminal = try XCTUnwrap(terminals().first)
    // The same request after the terminal replays the durable terminal.
    let replay = try owner.admit(request)
    XCTAssertEqual(replay.terminal?.manifestId, terminal.manifestId)
    XCTAssertEqual(camera.requests.count, 1)
    XCTAssertEqual(terminals().count, 1, "replayed terminals are returned, not re-emitted")
    var changed = request
    changed.ledgerRecordHash = CaptureFixture.sha("other")
    XCTAssertThrowsError(try owner.admit(changed))
  }

  func testAcceptedAttemptOfAKilledProcessIsFencedWithoutReexposure() throws {
    let request = CaptureFixture.request()
    do {
      let (first, _, _) = try owner()
      _ = try first.admit(request)
      XCTAssertEqual(camera.requests.count, 1)
      // Process death: the owner is gone without a terminal.
    }
    camera = FakeStillExposure()
    let (second, _, budget) = try owner()
    let replay = try second.admit(request)
    XCTAssertEqual(replay.terminal?.reason, "durable-replay-no-reexposure")
    XCTAssertTrue(camera.requests.isEmpty)
    XCTAssertEqual(try budget.reservedBytesTotal(), 0)
    XCTAssertEqual(terminals().filter { $0.attemptId == request.accepted.identity.attemptId }.count, 1)
  }

  func testCameraFailureAbandonsOnceAndReleases() throws {
    let (owner, _, budget) = try owner()
    _ = try owner.admit(CaptureFixture.request())
    camera.fail(reason: "high-resolution-frame-unavailable")
    XCTAssertEqual(terminals().map { $0.reason }, ["camera-high-resolution-frame-unavailable"])
    XCTAssertEqual(try budget.reservedBytesTotal(), 0)
    XCTAssertTrue(photos.isEmpty)
    camera.deliverJpeg()
    XCTAssertEqual(terminals().count, 1)
  }

  func testRefusedExposureAbandons() throws {
    camera.accept = false
    let (owner, _, _) = try owner()
    _ = try owner.admit(CaptureFixture.request())
    XCTAssertEqual(terminals().map { $0.reason }, ["exposure-request-rejected"])
  }

  func testWaitingOwnerStartsOnlyAfterTheRunningTerminal() throws {
    let (owner, _, _) = try owner()
    _ = try owner.admit(CaptureFixture.request(attemptId: "auto-1", commitId: "c1", ordinal: 1, lane: .automatic))
    XCTAssertThrowsError(try owner.admit(CaptureFixture.request(attemptId: "auto-2", commitId: "c2", ordinal: 2, lane: .automatic)),
                         "one owner per lane")
    _ = try owner.admit(CaptureFixture.request(attemptId: "manual-1", commitId: "c3", ordinal: 3, lane: .manual))
    XCTAssertEqual(owner.snapshot().fundedWaiting, 1)
    XCTAssertEqual(camera.requests.count, 1, "the waiting owner never requests the camera")
    camera.deliverJpeg(0)
    XCTAssertEqual(camera.requests.count, 2, "the waiting manual owner starts after the running terminal")
    camera.deliverJpeg(1)
    XCTAssertEqual(terminals().compactMap { $0.attemptId }, ["auto-1", "manual-1"])
  }

  func testDeadlinesPresentAt10sAndQueryOnceAt30s() throws {
    let (owner, _, budget) = try owner()
    let request = CaptureFixture.request()
    _ = try owner.admit(request)
    now += 10_000
    owner.advanceDeadlines()
    XCTAssertEqual(events.last?.kind, .recovering)
    XCTAssertEqual(events.last?.reason, "terminal-presentation-10s")
    XCTAssertEqual(owner.snapshot().unknownQueries, 1)
    now += 20_000
    owner.advanceDeadlines()
    XCTAssertEqual(terminals().map { $0.reason }, ["terminal-fence-30s"])
    owner.advanceDeadlines()
    camera.deliverJpeg()
    XCTAssertEqual(terminals().count, 1, "a late still after the fence is discarded")
    XCTAssertEqual(try budget.reservedBytesTotal(), 0)
    XCTAssertEqual(owner.snapshot().unknownQueries, 0)
  }

  func testRouteLeftTransfersAnExposedAttemptToRecovery() throws {
    let (owner, _, budget) = try owner()
    _ = try owner.admit(CaptureFixture.request())
    owner.onLifecycle(.routeLeft)
    XCTAssertEqual(events.last?.kind, .recovering)
    XCTAssertEqual(events.last?.reason, "route-left-transfer")
    XCTAssertTrue(terminals().isEmpty)
    camera.deliverJpeg()
    XCTAssertTrue(terminals().isEmpty, "a still after the cut is discarded")
    XCTAssertGreaterThan(try budget.reservedBytesTotal(), 0, "durable restart recovery owns the outcome")
    // The next start proves the attempt absent exactly once.
    let (store, _) = try CaptureFixture.store(root)
    XCTAssertEqual(try store.recoverAndProject().first?.reason, "recovered-proven-absent")
    XCTAssertEqual(try budget.reservedBytesTotal(), 0)
  }

  func testPauseAfterTheTerminalKeepsIt() throws {
    let (owner, _, _) = try owner()
    _ = try owner.admit(CaptureFixture.request())
    camera.deliverJpeg()
    owner.onLifecycle(.backgrounded)
    XCTAssertEqual(terminals().count, 1)
  }

  func testCloseAbandonsUnexposedAndTransfersExposedOwners() throws {
    let (owner, _, _) = try owner()
    _ = try owner.admit(CaptureFixture.request(attemptId: "running", commitId: "c1", ordinal: 1, lane: .automatic))
    _ = try owner.admit(CaptureFixture.request(attemptId: "waiting", commitId: "c2", ordinal: 2, lane: .manual))
    owner.close()
    XCTAssertEqual(terminals().map { $0.attemptId }, ["waiting"])
    XCTAssertEqual(terminals().first?.reason, "shutdown-before-exposure")
    XCTAssertEqual(events.last(where: { $0.attemptId == "running" })?.reason, "shutdown-transfer")
    XCTAssertThrowsError(try owner.admit(CaptureFixture.request(attemptId: "late", commitId: "c3", ordinal: 3)))
  }

  func testRecoveryReplayKeepsTheNewestTwoUntilAcknowledged() {
    var emitted: [NativeCaptureEvent] = []
    let replay = NativeCaptureRecoveryReplay { emitted.append($0) }
    XCTAssertTrue(replay.replaySnapshot().isEmpty, "nothing before recovery")
    XCTAssertThrowsError(try replay.requireAdmissionReady())
    replay.recover { [] }
    XCTAssertEqual(emitted.map { $0.kind }, [.ready])
    XCTAssertEqual(replay.replaySnapshot().map { $0.kind }, [.ready])
    let context = CaptureFixture.context()
    for id in ["a", "b", "c"] {
      replay.emitLive(NativeCaptureEvent(kind: .committed, attemptId: id, captureId: "c-\(id)", captureRevision: 1,
                                         manifestId: "m-\(id)", jpegSizeBytes: 1, reason: "committed", recoveryContext: context))
    }
    XCTAssertEqual(replay.replaySnapshot().compactMap { $0.attemptId }, ["b", "c"])
    // A changed terminal for a retained attempt is a conflict, never replayed.
    replay.emitLive(NativeCaptureEvent(kind: .committed, attemptId: "c", captureId: "other", captureRevision: 1,
                                       manifestId: "m-c", jpegSizeBytes: 1, reason: "committed", recoveryContext: context))
    XCTAssertEqual(replay.replaySnapshot().last?.captureId, "c-c")
    replay.acknowledge("b")
    replay.acknowledge("c")
    XCTAssertEqual(replay.replaySnapshot().map { $0.kind }, [.ready])
    XCTAssertEqual(emitted.last?.kind, .ready)
  }

  func testFailedRecoveryRefusesAdmissionAndReplaysTheFailure() {
    let replay = NativeCaptureRecoveryReplay { _ in }
    replay.recover { throw DurableStoreConflict("corrupt") }
    XCTAssertThrowsError(try replay.requireAdmissionReady()) {
      XCTAssertEqual(($0 as? NativeCaptureOwnerError)?.code, "NATIVE_CAPTURE_V2_RECOVERY_FAILED")
    }
    XCTAssertEqual(replay.replaySnapshot().map { $0.kind }, [.recoveryFailed])
  }
}
