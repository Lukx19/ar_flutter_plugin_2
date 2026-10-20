import XCTest
@testable import ar_flutter_plugin_2

/// The per-view binding end to end on the wire (admission map in, reply and
/// event maps out on the main thread), the exclusive root lease, startup
/// recovery replay after a restart, and the preview copies of committed
/// photos (`native_capture_preview`).
final class NativeCaptureBindingTests: XCTestCase {
  private var appFiles: URL!
  private var camera: FakeStillExposure!
  private var events: [[String: Any]] = []
  private var photos: [DurableTerminalPhoto] = []

  override func setUp() {
    super.setUp()
    appFiles = CaptureFixture.temporaryDirectory("binding")
    camera = FakeStillExposure()
    events = []
    photos = []
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: appFiles)
    super.tearDown()
  }

  private func binding(nowMs: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
                       fault: @escaping DurableStoreFaultInjector = { _ in }) -> NativeCaptureBinding {
    let files = appFiles!
    return NativeCaptureBinding(
      openResources: {
        let lease = try NativeCaptureRootLease.acquire(NativeCaptureStorePaths.nativeRoot(files))
        let (store, _) = try CaptureFixture.store(NativeCaptureStorePaths.nativeRoot(files), fault: fault)
        return NativeCaptureRootResources(lease: lease, store: store)
      },
      exposure: camera,
      nowMs: nowMs,
      events: { [unowned self] event in
        let map = NativeCaptureWire.event(event)
        DispatchQueue.main.async { self.events.append(map) }
      },
      durableTerminal: { [unowned self] photo in DispatchQueue.main.async { self.photos.append(photo) } })
  }

  private func complete<T>(_ operation: (@escaping (Result<T, Error>) -> Void) -> Void) -> Result<T, Error> {
    let done = expectation(description: "binding reply")
    var outcome: Result<T, Error>!
    operation { result in
      XCTAssertTrue(Thread.isMainThread)
      outcome = result
      done.fulfill()
    }
    wait(for: [done], timeout: 10)
    return outcome
  }

  private func drain(_ binding: NativeCaptureBinding) {
    let done = expectation(description: "queue drained")
    binding.queue.async { DispatchQueue.main.async { done.fulfill() } }
    wait(for: [done], timeout: 10)
  }

  func testAdmissionOnTheWireCommitsAndReportsOnePhoto() throws {
    let binding = binding()
    let request = CaptureFixture.request()
    let replay = try complete { binding.replay(completion: $0) }.get()
    XCTAssertEqual(replay.first?["kind"] as? String, "ready")
    let reply = try complete { binding.admit(CaptureFixture.admissionMap(request), completion: $0) }.get()
    XCTAssertEqual(reply["phase"] as? String, "reserved_accepted")
    XCTAssertEqual(reply["durable"] as? Bool, true)
    XCTAssertTrue(reply["terminal"] is NSNull)
    XCTAssertEqual(camera.requests.count, 1)

    camera.deliverJpeg()
    drain(binding)
    XCTAssertEqual(events.compactMap { $0["kind"] as? String }, ["ready", "accepted", "finalizing", "committed", "health"])
    XCTAssertEqual(photos.count, 1)
    XCTAssertEqual(photos.first?.terminal.captureId, DurableStoreEncoding.safeName(request.accepted.identity.attemptId))
    // The durable terminal names the committed JPEG for tie points.
    let image = try XCTUnwrap(photos.first?.imageURL)
    XCTAssertEqual(image.lastPathComponent.prefix(5), "jpeg.")
    XCTAssertEqual([UInt8](try Data(contentsOf: image)), CaptureFixture.jpeg)

    let health = try complete { binding.health(completion: $0) }.get()
    XCTAssertEqual((health["health"] as? [String: Any])?["committed"] as? Int, 1)
    let replayed = try complete { binding.replay(completion: $0) }.get()
    XCTAssertEqual(replayed.map { $0["kind"] as? String }, ["committed"])
    XCTAssertTrue(try complete { binding.acknowledge(request.accepted.identity.attemptId, completion: $0) }.get())
    XCTAssertEqual(try complete { binding.replay(completion: $0) }.get().first?["kind"] as? String, "ready")
  }

  /// Session deletion runs on the open view's durable queue while a view
  /// owns the root and opens the root itself once the view closed.
  func testSessionDeletionReachesTheStoreWithAndWithoutAnOpenView() throws {
    let files = appFiles!
    let nativeRoot = NativeCaptureStorePaths.nativeRoot(files)
    let open: () throws -> NativeCaptureRootResources = {
      let lease = try NativeCaptureRootLease.acquire(nativeRoot)
      let (store, _) = try CaptureFixture.store(nativeRoot)
      return NativeCaptureRootResources(lease: lease, store: store)
    }
    func delete(_ session: String) throws -> Int64 {
      let done = expectation(description: "deleted")
      var outcome: Result<Int64, Error>!
      DispatchQueue.global().async {
        outcome = Result { try NativeCaptureSessionDeletion.delete(
          sessionId: session, openResources: open, leaseKey: NativeCaptureRootLease.leaseKey(nativeRoot)) }
        done.fulfill()
      }
      wait(for: [done], timeout: 10)
      return try outcome.get()
    }
    let binding = binding()
    _ = try complete { binding.admit(CaptureFixture.admissionMap(CaptureFixture.request()), completion: $0) }.get()
    camera.deliverJpeg()
    drain(binding)
    XCTAssertEqual(photos.count, 1)
    XCTAssertEqual(try delete("session-ios"), Int64(CaptureFixture.jpeg.count), "through the open view")
    let closed = expectation(description: "closed")
    binding.close { closed.fulfill() }
    wait(for: [closed], timeout: 10)
    XCTAssertEqual(try delete("session-ios"), 0, "the root opened briefly; nothing left to reclaim")
    let (store, budget) = try CaptureFixture.store(nativeRoot)
    XCTAssertEqual(try budget.committedBytes(), 0)
    XCTAssertNil(try store.queryReceipt(CaptureFixture.request().accepted.identity))
    XCTAssertThrowsError(try delete(""))
  }

  func testMalformedAdmissionAndUnknownLifecycleAreRefused() {
    let binding = binding()
    var map = CaptureFixture.admissionMap(CaptureFixture.request())
    map["extra"] = true
    guard case .failure(let error) = complete({ binding.admit(map, completion: $0) }) else { return XCTFail("accepted") }
    XCTAssertEqual(NativeCaptureChannel.flutterError(error).code, "NATIVE_CAPTURE_V2_INVALID")
    guard case .failure(let lifecycle) = complete({ binding.lifecycle("backgrounded", completion: $0) }) else { return XCTFail("accepted") }
    XCTAssertEqual(NativeCaptureChannel.flutterError(lifecycle).code, "NATIVE_CAPTURE_V2_INVALID")
    XCTAssertTrue(camera.requests.isEmpty)
  }

  func testLeaseKeyIsTheSameBeforeAndAfterTheRootExists() {
    let parent = CaptureFixture.temporaryDirectory("lease-key")
    defer { try? FileManager.default.removeItem(at: parent) }
    let root = parent.appendingPathComponent("not-yet/native", isDirectory: true)
    let before = NativeCaptureRootLease.leaseKey(root)
    let after = NativeCaptureRootLease.leaseKey(root)
    XCTAssertEqual(before, after)
    XCTAssertTrue(FileManager.default.fileExists(atPath: root.path))
  }

  func testRootIsExclusiveUntilTheOwnerCloses() throws {
    let first = binding()
    _ = try complete { first.replay(completion: $0) }.get()
    let second = binding()
    guard case .failure(let error) = complete({ second.admit(CaptureFixture.admissionMap(CaptureFixture.request()), completion: $0) }) else {
      return XCTFail("second owner opened the root")
    }
    XCTAssertEqual(NativeCaptureChannel.flutterError(error).code, "NATIVE_CAPTURE_V2_ROOT_PENDING")
    let closed = expectation(description: "closed")
    first.close { closed.fulfill() }
    wait(for: [closed], timeout: 10)
    let third = binding()
    XCTAssertNoThrow(try complete { third.replay(completion: $0) }.get())
    // A replacement view waits briefly for the previous owner's release.
    let fourth = binding()
    DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) { third.close() }
    XCTAssertNoThrow(try complete { fourth.replay(completion: $0) }.get())
  }

  func testCloseTransfersAnExposedAttemptAndTheRestartReplaysIt() throws {
    let request = CaptureFixture.request()
    let first = binding()
    _ = try complete { first.admit(CaptureFixture.admissionMap(request), completion: $0) }.get()
    let closed = expectation(description: "closed")
    first.close { closed.fulfill() }
    wait(for: [closed], timeout: 10)
    camera.deliverJpeg()
    drain(first)
    XCTAssertFalse(events.contains { $0["kind"] as? String == "committed" }, "no terminal after the close")

    events = []
    let restarted = binding()
    let replay = try complete { restarted.replay(completion: $0) }.get()
    XCTAssertEqual(replay.count, 1)
    XCTAssertEqual(replay.first?["kind"] as? String, "abandoned")
    XCTAssertEqual(replay.first?["reason"] as? String, "recovered-proven-absent")
    XCTAssertEqual(replay.first?["attemptId"] as? String, request.accepted.identity.attemptId)
    XCTAssertNotNil(replay.first?["recoveryContext"])
  }

  func testPauseCutsOnlyAttemptsLiveWhenThePauseArrived() throws {
    var clock: Int64 = 1_000_000
    let binding = binding(nowMs: { clock })
    let first = CaptureFixture.request()
    let second = CaptureFixture.request(attemptId: "6f1c2d3e-0000-4000-8000-000000000002", commitId: "commit-2", lane: .automatic)
    _ = try complete { binding.admit(CaptureFixture.admissionMap(first), completion: $0) }.get()
    binding.onSessionPaused()
    drain(binding)
    // The session resumes and a second attempt is admitted before the first settles.
    _ = try complete { binding.admit(CaptureFixture.admissionMap(second), completion: $0) }.get()
    clock += NativeCaptureBinding.pauseSettleMs + 1_000
    camera.deliverJpeg(0)
    drain(binding)
    XCTAssertEqual(camera.requests.count, 2, "the second attempt is exposed after the first commits")
    // A deadline tick past the pause settle must not cut the second attempt.
    let tick = expectation(description: "deadline tick")
    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { tick.fulfill() }
    wait(for: [tick], timeout: 5)
    drain(binding)
    camera.deliverJpeg(1)
    drain(binding)
    let committed = events.filter { $0["kind"] as? String == "committed" }.compactMap { $0["attemptId"] as? String }
    XCTAssertEqual(committed, [first.accepted.identity.attemptId, second.accepted.identity.attemptId])
    XCTAssertFalse(events.contains { $0["kind"] as? String == "recovering" })
    XCTAssertEqual(photos.count, 2)
  }

  func testCloseCutIsDurableWhenTheViewDropsTheBindingAtOnce() throws {
    let exposed = CaptureFixture.request()
    let waiting = CaptureFixture.request(attemptId: "6f1c2d3e-0000-4000-8000-000000000002", commitId: "commit-2", lane: .automatic)
    var first: NativeCaptureBinding? = binding()
    _ = try complete { first!.admit(CaptureFixture.admissionMap(exposed), completion: $0) }.get()
    _ = try complete { first!.admit(CaptureFixture.admissionMap(waiting), completion: $0) }.get()
    let closed = expectation(description: "closed")
    first!.close { closed.fulfill() }
    first = nil
    wait(for: [closed], timeout: 10)

    events = []
    let restarted = binding()
    let replay = try complete { restarted.replay(completion: $0) }.get()
    let byAttempt = Dictionary(uniqueKeysWithValues: replay.compactMap { event in (event["attemptId"] as? String).map { ($0, event) } })
    XCTAssertEqual(byAttempt[waiting.accepted.identity.attemptId]?["kind"] as? String, "abandoned")
    XCTAssertEqual(byAttempt[waiting.accepted.identity.attemptId]?["reason"] as? String, "view-replaced-before-exposure")
    XCTAssertEqual(byAttempt[exposed.accepted.identity.attemptId]?["reason"] as? String, "recovered-proven-absent")
  }

  /// An attempt cut by a pause while the view stays open goes to restart
  /// recovery; once later photos push it out of the bounded recovery index,
  /// the next open still proves it absent and releases its reservation.
  func testPauseCutAttemptBeyondTheRecoveryIndexIsReleasedAtTheNextOpen() throws {
    var clock: Int64 = 1_000_000
    let first = binding(nowMs: { clock })
    let cut = CaptureFixture.request()
    _ = try complete { first.admit(CaptureFixture.admissionMap(cut), completion: $0) }.get()
    first.onSessionPaused()
    drain(first)
    clock += NativeCaptureBinding.pauseSettleMs + 1_000
    let tick = expectation(description: "deadline tick")
    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { tick.fulfill() }
    wait(for: [tick], timeout: 5)
    drain(first)
    XCTAssertTrue(events.contains { $0["kind"] as? String == "recovering" && $0["attemptId"] as? String == cut.accepted.identity.attemptId })
    let later = (2...4).map {
      CaptureFixture.request(attemptId: "6f1c2d3e-0000-4000-8000-00000000000\($0)", commitId: "commit-\($0)", ordinal: Int64($0))
    }
    for (offset, request) in later.enumerated() {
      _ = try complete { first.admit(CaptureFixture.admissionMap(request), completion: $0) }.get()
      camera.deliverJpeg(offset + 1)
      drain(first)
    }
    XCTAssertEqual(photos.count, 3)
    let closed = expectation(description: "closed")
    first.close { closed.fulfill() }
    wait(for: [closed], timeout: 10)

    let nativeRoot = NativeCaptureStorePaths.nativeRoot(appFiles)
    XCTAssertGreaterThan(try CaptureFixture.store(nativeRoot).1.reservedBytesTotal(), 0, "the cut attempt still holds its reservation")
    let restarted = binding()
    let replay = try complete { restarted.replay(completion: $0) }.get()
    XCTAssertEqual(replay.compactMap { $0["kind"] as? String }, ["committed", "committed"])
    let (store, budget) = try CaptureFixture.store(nativeRoot)
    XCTAssertEqual(try budget.reservedBytesTotal(), 0, "the next open released the cut attempt")
    XCTAssertEqual(try budget.committedBytes(), 3 * Int64(CaptureFixture.jpeg.count), "committed photos stay charged")
    XCTAssertEqual(try store.queryReceipt(cut.accepted.identity)?.terminal?.reason, "recovered-proven-absent")
    for request in later {
      XCTAssertEqual(try store.queryReceipt(request.accepted.identity)?.phase, .committedPicture, "captures are kept")
    }
  }

  /// A reservation that fails to reconcile never fails the open: recovery
  /// is ready and admission goes on.
  func testReconciliationFailureNeverFailsTheOpen() throws {
    let nativeRoot = NativeCaptureStorePaths.nativeRoot(appFiles)
    let (seed, _) = try CaptureFixture.store(nativeRoot)
    for index in 1...2 {
      _ = try seed.acceptCaptureBeforeExposure(CaptureFixture.request(
        attemptId: "6f1c2d3e-0000-4000-8000-00000000000\(index)", commitId: "leaked-\(index)", ordinal: Int64(index)))
    }
    try FileManager.default.removeItem(at: nativeRoot.appendingPathComponent("store/recovery-index.properties"))
    let plan = FaultPlan(.abandonmentRecord)
    let binding = binding(fault: plan.injector())
    let replay = try complete { binding.replay(completion: $0) }.get()
    XCTAssertEqual(replay.first?["kind"] as? String, "ready")
    XCTAssertEqual(plan.injected, 1)
    let request = CaptureFixture.request(attemptId: "6f1c2d3e-0000-4000-8000-000000000009", commitId: "commit-9", ordinal: 9)
    let reply = try complete { binding.admit(CaptureFixture.admissionMap(request), completion: $0) }.get()
    XCTAssertEqual(reply["phase"] as? String, "reserved_accepted")
    camera.deliverJpeg()
    drain(binding)
    XCTAssertEqual(photos.count, 1)
    let liability = request.accepted.reservation.totalStoreLiability
    XCTAssertEqual(try CaptureFixture.store(nativeRoot).1.reservedBytesTotal(), liability, "only the failed reservation remains")
  }

  func testCommittedPhotoMaterializesAPreviewAfterReconcile() throws {
    let (store, _) = try CaptureFixture.store(NativeCaptureStorePaths.nativeRoot(appFiles))
    let request = CaptureFixture.request()
    _ = try store.acceptCaptureBeforeExposure(request)
    let jpeg = StillQualityTests.jpeg(width: 1200, height: 900) { x, y in UInt8((x ^ y) & 0xff) }
    let receipt = try store.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: 1, components: [(.jpeg, [UInt8](jpeg))])
    let manifest = try XCTUnwrap(receipt.terminal?.manifestId)
    let capture = try XCTUnwrap(receipt.terminal?.captureId)
    let reference = "native-v2://\(manifest)/\(capture)"

    let owner = NativeCapturePreviewOwner(appFiles: appFiles)
    XCTAssertNil(try owner.materialize(manifestId: manifest, captureId: capture), "previews wait for the startup reconcile")
    try owner.reconcile(liveReferences: [reference])
    let path = try XCTUnwrap(try owner.materialize(manifestId: manifest, captureId: capture))
    XCTAssertTrue(CapturePreviewThumbnail.valid(URL(fileURLWithPath: path), edge: 512))
    let detail = try XCTUnwrap(try owner.materialize(manifestId: manifest, captureId: capture, edge: 1024))
    XCTAssertTrue(CapturePreviewThumbnail.valid(URL(fileURLWithPath: detail), edge: 1024))

    let token = try owner.beginDeletion(references: [reference])
    XCTAssertNil(try owner.materialize(manifestId: manifest, captureId: capture), "deletion fences readers")
    try owner.rollbackDeletion(token: token)
    XCTAssertNotNil(try owner.materialize(manifestId: manifest, captureId: capture))
    let committed = try owner.beginDeletion(references: [reference])
    try owner.commitDeletion(token: committed)
    XCTAssertNil(try owner.materialize(manifestId: manifest, captureId: capture))
    XCTAssertThrowsError(try owner.beginDeletion(references: ["native-v2://bad/ref"]))
  }

  func testTamperedBlobIsNotPreviewed() throws {
    let (store, _) = try CaptureFixture.store(NativeCaptureStorePaths.nativeRoot(appFiles))
    let request = CaptureFixture.request()
    _ = try store.acceptCaptureBeforeExposure(request)
    let jpeg = [UInt8](StillQualityTests.jpeg(width: 64, height: 48) { x, _ in UInt8(x * 4) })
    let receipt = try store.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: 1, components: [(.jpeg, jpeg)])
    let reader = NativeCapturePreviewReader(storeRoot: NativeCaptureStorePaths.store(appFiles))
    let target = appFiles.appendingPathComponent("preview.jpg")
    let blob = NativeCaptureStorePaths.store(appFiles).appendingPathComponent(
      "sessions/\(DurableStoreEncoding.safeName("session-ios"))/assets/\(receipt.terminal!.captureId!)/jpeg.\(DurableStoreEncoding.hex(DurableStoreEncoding.sha256(jpeg))).blob")
    var tampered = jpeg
    tampered[tampered.count / 2] ^= 0xff
    try Data(tampered).write(to: blob)
    XCTAssertFalse(reader.materialize(manifestId: receipt.terminal!.manifestId!, captureId: receipt.terminal!.captureId!, target: target, edge: 512, cancelled: { false }))
  }

  func testStoreVersionsReportThePlainAndShotRootSchemas() {
    let report = NativeStoreFormatVersions.report()
    XCTAssertEqual(report["nativePhotoStore"] as? Int, 4)
    XCTAssertEqual(report["nativePhotoRootSchema"] as? Int, 5)
    XCTAssertEqual(report["nativePhotoShotRootSchema"] as? Int, 7)
    XCTAssertEqual(report["nativePhotoRootSchemasReadable"] as? [Int], [5, 7])
    XCTAssertEqual(report["nativePreviewCache"] as? Int, 1)
    XCTAssertEqual(report["nativeStorageBudget"] as? Int, 2, "settlement markers")
  }

  func testCameraControlsWithoutAConfigurableCameraAnswerTheAndroidError() {
    let error = CameraControlMethods.unavailable("setISO", reason: "no camera")
    XCTAssertEqual(error.code, "CONTROL_UNSUPPORTED")
    XCTAssertTrue(CameraControlMethods.methods.isSuperset(of: ["setISO", "getSupportedWhiteBalanceModes", "isFlashAvailable", "setFocusMode"]))
    XCTAssertEqual(CameraControlMethods.methods.count, 31, "every Android runtime camera control and the iOS flash mode query")
  }
}
