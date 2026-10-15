import simd
import XCTest
@testable import ar_flutter_plugin_2

/// The durable store commits every image of a burst shot or none:
/// a shot root (schema 7) lists the images after the first one and the shot
/// record, written into the same staging and asset folder, made visible by
/// the one pointer swap. A crash at every visit of every durable commit
/// step, then a restart, leaves either a complete shot or no visible
/// terminal (an abandoned attempt keeps no image of the shot).
final class ShotDurableCommitTests: XCTestCase {
  private var root: URL!

  override func setUp() {
    super.setUp()
    root = CaptureFixture.temporaryDirectory("shot-store")
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
    super.tearDown()
  }

  private let payload = ShotFixture.burst().payload!
  private let wide = ShotFixture.burst().first.jpeg

  private var shotBytes: Int64 {
    Int64(wide.count + payload.images.reduce(0) { $0 + $1.bytes.count } + payload.record.count)
  }

  private func commit(_ store: DurableCaptureStore, _ request: CaptureCommitRequest) throws -> CaptureReceipt {
    try store.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: 9_000_000_000,
                                      components: [(.jpeg, wide)], shot: payload)
  }

  /// Every file of the committed shot exists with the committed bytes.
  private func assertCompleteShot(_ store: DurableCaptureStore, _ terminal: CaptureTerminal, _ message: String = "",
                                  file: StaticString = #filePath, line: UInt = #line) throws {
    let files = try XCTUnwrap(store.committedShotFiles(terminal), message, file: file, line: line)
    XCTAssertEqual(files.images.keys.sorted(), [1, 2], message, file: file, line: line)
    for image in payload.images {
      XCTAssertEqual([UInt8](try Data(contentsOf: files.images[image.index]!)), image.bytes, message, file: file, line: line)
    }
    XCTAssertEqual([UInt8](try Data(contentsOf: files.record)), payload.record, message, file: file, line: line)
    let wideURL = try XCTUnwrap(store.committedImageURL(terminal), message, file: file, line: line)
    XCTAssertEqual([UInt8](try Data(contentsOf: wideURL)), wide, message, file: file, line: line)
  }

  func testAShotCommitsEveryImageUnderOneShotRoot() throws {
    let (store, budget) = try CaptureFixture.store(root)
    let request = CaptureFixture.request()
    _ = try store.acceptCaptureBeforeExposure(request)
    let receipt = try commit(store, request)
    let terminal = try XCTUnwrap(receipt.terminal)
    XCTAssertEqual(receipt.phase, .committedPicture)
    XCTAssertEqual(terminal.jpegSizeBytes, Int64(wide.count), "the receipt names the first JPEG")
    try assertCompleteShot(store, terminal)

    let session = root.appendingPathComponent("store/sessions/\(DurableStoreEncoding.safeName("session-ios"))")
    let rootText = try String(contentsOf: session.appendingPathComponent("objects/\(terminal.manifestId!).root"), encoding: .utf8)
    let lines = rootText.split(separator: "\n").map(String.init)
    XCTAssertEqual(lines.first, "schema=7")
    XCTAssertTrue(lines.contains("shot=10111213-1415-1617-1819-1a1b1c1d1e1f"))
    let sha = { (bytes: [UInt8]) in DurableStoreEncoding.hex(DurableStoreEncoding.sha256(bytes)) }
    XCTAssertTrue(lines.contains("component=JPEG:\(wide.count):\(sha(wide))"))
    XCTAssertTrue(lines.contains("shotImage=1:BURST:\(payload.images[0].bytes.count):\(sha(payload.images[0].bytes))"))
    XCTAssertTrue(lines.contains("shotImage=2:BURST:\(payload.images[1].bytes.count):\(sha(payload.images[1].bytes))"))
    XCTAssertTrue(lines.contains("shotRecord=\(payload.record.count):\(sha(payload.record))"))
    let assets = try FileManager.default.contentsOfDirectory(
      atPath: session.appendingPathComponent("assets/\(terminal.captureId!)").path).sorted()
    XCTAssertEqual(assets, [
      "jpeg.\(sha(wide)).blob", "shot-image-1.\(sha(payload.images[0].bytes)).blob",
      "shot-image-2.\(sha(payload.images[1].bytes)).blob", "shot-record.\(sha(payload.record)).blob",
    ])
    XCTAssertEqual(try budget.reservedBytesTotal(), 0)
    XCTAssertEqual(try budget.committedBytes(), shotBytes, "the budget charges every image of the shot")

    // A restart reads the shot root: the receipt validates and the startup
    // projection reports the committed shot.
    let (reopened, _) = try CaptureFixture.store(root)
    XCTAssertEqual(try reopened.queryReceipt(request.accepted.identity)?.phase, .committedPicture)
    XCTAssertEqual(try reopened.recoverAndProject().first?.reason, "recovered-committed")
    try assertCompleteShot(reopened, terminal)

    // A plain photo after the shot still writes schema 5 and chains to the shot root.
    let later = CaptureFixture.request(attemptId: "later", commitId: "later")
    _ = try reopened.acceptCaptureBeforeExposure(later)
    let plain = try reopened.commitStreamedFinalized(later.template, exposureTimestampNanoseconds: 1, components: [(.jpeg, [1, 2])])
    XCTAssertEqual(plain.terminal?.captureRevision, 2)
    XCTAssertNil(reopened.committedShotFiles(try XCTUnwrap(plain.terminal)))
  }

  func testShotRootsParseStrictly() {
    let hash = String(repeating: "a", count: 64)
    func parse(_ text: String) -> CaptureRootMetadata? {
      let bytes = Array(text.utf8)
      return CaptureRootRecords.parseRoot(expectedHash: DurableStoreEncoding.hex(DurableStoreEncoding.sha256(bytes)), bytes: bytes)
    }
    let head = "revision=1\nrequest=\(hash)\nprevious=-\nsecondPrevious=-\ncomponent=JPEG:4:\(hash)\n"
    let shot = "shot=10111213-1415-1617-1819-1a1b1c1d1e1f\nshotImage=1:BURST:3:\(hash)\nshotRecord=2:\(hash)\n"
    let parsed = parse("schema=7\n" + head + shot)
    XCTAssertEqual(parsed?.shot?.images.map { $0.role }, ["BURST"])
    XCTAssertEqual(parsed?.shot?.byteLength, 5)
    XCTAssertNil(parse("schema=5\n" + head + shot), "a plain root carries no shot lines")
    XCTAssertNil(parse("schema=7\n" + head), "a shot root needs its shot and record")
    XCTAssertNil(parse("schema=7\n" + head + shot.replacingOccurrences(of: "BURST", with: "ULTRA_WIDE")))
    XCTAssertNil(parse("schema=7\n" + head + shot + "shotImage=1:BURST:3:\(hash)\n"), "duplicate index")
    XCTAssertNil(parse("schema=7\n" + head + shot.replacingOccurrences(of: "shotImage=1", with: "shotImage=0")), "index 0 is the first JPEG")
    XCTAssertNotNil(parse("schema=5\n" + head), "plain roots are unchanged")
  }

  /// Visits of each durable step in one successful shot commit.
  private func commitVisits() throws -> [DurableStoreFaultPoint: Int] {
    var visits: [DurableStoreFaultPoint: Int] = [:]
    let dry = CaptureFixture.temporaryDirectory("shot-dry")
    defer { try? FileManager.default.removeItem(at: dry) }
    var counting = false
    let (store, _) = try CaptureFixture.store(dry, fault: { point in if counting { visits[point, default: 0] += 1 } })
    let request = CaptureFixture.request()
    _ = try store.acceptCaptureBeforeExposure(request)
    counting = true
    _ = try commit(store, request)
    return visits
  }

  func testACrashAtEveryCommitStepLeavesTheWholeShotOrNoVisibleTerminal() throws {
    let visits = try commitVisits()
    XCTAssertEqual(visits[.componentOpen], 4, "first JPEG, two images and the record")
    var cuts = 0
    for (point, count) in visits.sorted(by: { $0.key.rawValue < $1.key.rawValue }) {
      for visit in 1...count {
        try? FileManager.default.removeItem(at: root)
        root = CaptureFixture.temporaryDirectory("shot-store")
        let fault = NthVisitFault(point, visit: visit)
        let (poisoned, _) = try CaptureFixture.store(root, fault: fault.injector())
        let request = CaptureFixture.request(attemptId: "shot-\(point.rawValue)-\(visit)", commitId: "commit-\(point.rawValue)-\(visit)")
        let name = "\(point.rawValue) visit \(visit)"
        _ = try poisoned.acceptCaptureBeforeExposure(request)
        fault.armed = true
        XCTAssertThrowsError(try commit(poisoned, request), name)
        XCTAssertTrue(fault.injected, name)
        cuts += 1

        let (clean, budget) = try CaptureFixture.store(root)
        try clean.recover()
        let receipt = try clean.queryReceipt(request.accepted.identity)
        let assets = root.appendingPathComponent(
          "store/sessions/\(DurableStoreEncoding.safeName("session-ios"))/assets/\(DurableStoreEncoding.safeName(request.accepted.identity.attemptId))")
        switch receipt?.phase {
        case .committedPicture?:
          try assertCompleteShot(clean, try XCTUnwrap(receipt?.terminal), name)
          XCTAssertEqual(try budget.reservedBytesTotal(), 0, name)
          XCTAssertEqual(try budget.committedBytes(), shotBytes, name)
        case .abandonedAttempt?:
          XCTAssertFalse(FileManager.default.fileExists(atPath: assets.path), "\(name): no image of an abandoned shot remains")
          XCTAssertEqual(try budget.reservedBytesTotal(), 0, name)
        default:
          // Receipt durable, pointer cut unknown: nothing is visible and the
          // liability stays for an exact query or rebase.
          XCTAssertGreaterThan(try budget.reservedBytesTotal(), 0, name)
        }
        // A later shot after the restart commits completely.
        let later = CaptureFixture.request(attemptId: "later-\(point.rawValue)-\(visit)", commitId: "later-\(point.rawValue)-\(visit)")
        _ = try clean.acceptCaptureBeforeExposure(later)
        let committed = try commit(clean, later)
        try assertCompleteShot(clean, try XCTUnwrap(committed.terminal), "\(name) later")
      }
    }
    XCTAssertGreaterThanOrEqual(cuts, 30)
  }

  func testTheAdmissionOwnerCommitsTheShotAndReportsEveryImageWithItsOwnFrame() throws {
    let (store, _) = try CaptureFixture.store(root)
    let camera = FakeStillExposure()
    var photos: [DurableTerminalPhoto] = []
    let owner = NativeCaptureAdmissionOwner(store: store, exposure: camera, nowMs: { 1_000 }, events: { _ in },
                                            durableTerminal: { photos.append($0) })
    let request = CaptureFixture.request()
    _ = try owner.admit(request)
    let shot = ShotFixture.burst()
    let (qualifier, callback) = camera.requests[0]
    callback(.components(StillComponentSet(
      qualifier: qualifier, components: [(.jpeg, shot.first.jpeg)], metadata: shot.first.metadata, shot: shot)))
    let photo = try XCTUnwrap(photos.first)
    XCTAssertEqual(photo.terminal.kind, .committedPicture)
    XCTAssertEqual(photo.shotImageURLs.keys.sorted(), [1, 2])
    let records = NativeCaptureChannel.photoSavedRecords(photo)
    XCTAssertEqual(records.count, 3)
    XCTAssertEqual(records[0].captureId, photo.terminal.captureId, "the first image keeps the capture id")
    XCTAssertEqual(Set(records.map { $0.captureId }).count, 3, "every image is its own photo")
    XCTAssertEqual(records[1].captureId, PhotoSavedEmitter.shotImageCaptureId(photo.terminal.captureId!, index: 1))
    XCTAssertEqual(records.map { $0.shotImage?.index }, [0, 1, 2])
    XCTAssertEqual(records.map { $0.shotImage?.mode }, [1, 1, 1])
    XCTAssertEqual(records.map { $0.exposureTimestampNs }, shot.images.map { $0.metadata.exposureTimestampNs },
                   "each image keeps its own exposure time")
    XCTAssertEqual(records[1].cameraToWorld, shot.images[1].metadata.cameraToWorld, "and its own frame's pose")
    XCTAssertNotEqual(records[0].cameraToWorld, records[1].cameraToWorld)
    XCTAssertEqual(records.map { $0.sharpness }, [100, 101, 102])
    XCTAssertEqual(records[1].imageURL, photo.shotImageURLs[1])
    XCTAssertTrue(records.allSatisfy { !$0.automatic && $0.shotImage?.count == 3 })
    // The third image was taken while tracking was limited: kept, no pose (no credit), flagged.
    XCTAssertNil(records[2].cameraToWorld)
    XCTAssertEqual(records[2].shotImage?.flags, PhotoShotImageLayout.flagTrackingNotNormal)
    XCTAssertEqual(records[0].shotImage?.flags, 0)

    // A quality shot is one image: a plain commit that still reports its mode.
    var quality = photo
    quality.shot = CapturedShot(shotId: ShotFixture.shotId, mode: .quality, images: [shot.images[0]])
    quality.shotImageURLs = [:]
    let qualityRecords = NativeCaptureChannel.photoSavedRecords(quality)
    XCTAssertEqual(qualityRecords.count, 1)
    XCTAssertEqual(qualityRecords[0].shotImage?.mode, PhotoShotImageLayout.modeQuality)
    XCTAssertNil(quality.shot?.payload, "a shot of one image is committed as a plain photo")
  }

  /// Image 1 of the shared golden (`ingest_photo_saved_shot_image.bin`,
  /// written by the Dart contract fixture) byte for byte.
  func testTheShotImageEventEqualsTheSharedGolden() throws {
    let golden = try VisibilityGridGoldens.data("ingest_photo_saved_shot_image.bin")
    let payload = golden.subdata(in: IngestMessageLayout.headerBytes..<golden.count)
    XCTAssertEqual(payload.count, PhotoSavedLayout.bytes + PhotoShotImageLayout.bytes)
    var pose = matrix_identity_float4x4
    pose.columns.3 = simd_float4(0.11, 0.2, 0.3, 1)
    let record = DurablePhotoRecord(
      captureId: (32..<64).map { String(format: "%02x", $0) }.joined(), attemptId: "a0a1a2a3-a4a5-a6a7-a8a9-aaabacadaeaf",
      exposureTimestampNs: 1_234_567_990_123, cameraToWorld: pose, intrinsics: simd_float4(2940, 2940, 2016, 1512),
      width: 4032, height: 3024, sharpness: 640, automatic: false, blurRejected: false,
      shotImage: ShotImageRecord(shotId: ShotFixture.shotId, index: 1, count: 3, mode: PhotoShotImageLayout.modeBurst, flags: 0))
    let event = IngestMessageBuffer(capacity: PhotoSavedLayout.bytes + PhotoShotImageLayout.bytes)
    PhotoSavedEmitter.fillEvent(event, record: record, groupEpoch: 3)
    XCTAssertEqual(event.data(PhotoSavedLayout.bytes + PhotoShotImageLayout.bytes), payload)
    XCTAssertEqual(PhotoSavedEmitter.payloadBytes(record), 160)
  }
}
