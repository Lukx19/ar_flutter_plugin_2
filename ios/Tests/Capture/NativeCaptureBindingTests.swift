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

  private func binding(nowMs: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) -> NativeCaptureBinding {
    let files = appFiles!
    return NativeCaptureBinding(
      openResources: {
        let lease = try NativeCaptureRootLease.acquire(NativeCaptureStorePaths.nativeRoot(files))
        let (store, _) = try CaptureFixture.store(NativeCaptureStorePaths.nativeRoot(files))
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

    let health = try complete { binding.health(completion: $0) }.get()
    XCTAssertEqual((health["health"] as? [String: Any])?["committed"] as? Int, 1)
    let replayed = try complete { binding.replay(completion: $0) }.get()
    XCTAssertEqual(replayed.map { $0["kind"] as? String }, ["committed"])
    XCTAssertTrue(try complete { binding.acknowledge(request.accepted.identity.attemptId, completion: $0) }.get())
    XCTAssertEqual(try complete { binding.replay(completion: $0) }.get().first?["kind"] as? String, "ready")
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

  func testStoreVersionsReportThePlainRootSchema() {
    let report = NativeStoreFormatVersions.report()
    XCTAssertEqual(report["nativePhotoStore"] as? Int, 2)
    XCTAssertEqual(report["nativePhotoRootSchema"] as? Int, 5)
    XCTAssertEqual(report["nativePhotoRootSchemasReadable"] as? [Int], [5])
    XCTAssertEqual(report["nativePreviewCache"] as? Int, 1)
  }

  func testAndroidOnlyCameraControlsAnswerTheDefinedError() {
    let error = AndroidOnlyCameraControls.error("setISO")
    XCTAssertEqual(error.code, "CAMERA_CONTROL_UNSUPPORTED")
    XCTAssertTrue(AndroidOnlyCameraControls.methods.isSuperset(of: ["setISO", "getSupportedWhiteBalanceModes", "isFlashAvailable", "setFocusMode"]))
  }
}
