import Foundation
import simd
import XCTest

@testable import ar_flutter_plugin_2

/// Every message kind the iOS shell produces equals the golden bytes shared
/// with Dart and Kotlin (`test_assets/visibility_grid/`).
final class IngestGoldenTests: XCTestCase {
  private let clock = FakeIngestClock(IngestGoldenHeader.acquireNs)
  private lazy var pull = IngestPullHandler(clock: clock)
  private let capture = ReplyCapture()

  private func pullOnce(mask: Int = 0x17e) -> Data {
    clock.now = IngestGoldenHeader.sendNs
    pull.onPull(pullRequest(mask: mask), reply: capture.reply)
    return capture.last!
  }

  func testDepthProducerWritesTheDepthGolden() throws {
    let golden = try VisibilityGridGoldens.data("ingest_depth_u16.bin")
    IngestGoldenHeader.primeSequence(pull, kind: IngestMessageLayout.kindDepthImage, golden: golden, clock: clock)
    let producer = DepthIngestProducer(sink: pull)
    producer.downscale = 1
    let metres: [Float] = (1...12).map { Float($0) * 0.25 }
    let confidence: [UInt8] = (0..<12).map { UInt8($0 * 20) }
    let offered = metres.withUnsafeBytes { d in
      confidence.withUnsafeBytes { c in
        producer.publish(
          DepthPlanes(depth: d.baseAddress!, width: 4, height: 3, depthRowBytes: 16, confidence: c.baseAddress!,
                      confidenceRowBytes: 4, confidenceMapping: .identity),
          // 180, 181, 80, 45 at the 4x3 payload from a 1920x1440 camera image
          // (principal point (c + 0.5) / 480 - 0.5).
          intrinsics: simd_float4(86_400, 86_880, 38_639.5, 21_839.5), imageWidth: 1920, imageHeight: 1440,
          context: IngestGoldenHeader.context, sourceFlags: IngestMessageLayout.sourceDepth)
      }
    }
    XCTAssertTrue(offered)
    XCTAssertEqual(pullOnce(), golden)
  }

  func testPointProducerWritesThePointsGoldenDroppingNonFiniteAndFarPoints() throws {
    let golden = try VisibilityGridGoldens.data("ingest_points.bin")
    IngestGoldenHeader.primeSequence(pull, kind: IngestMessageLayout.kindPointCloud, golden: golden, clock: clock)
    let producer = PointCloudIngestProducer(sink: pull)
    // The golden predates the confidence gate and carries a 0.25 point.
    producer.minConfidence = 0
    let nan = Float.nan
    let points: [simd_float3] = [
      [nan, 0, 0], [0, 1, -1.5], [0, .infinity, 0], [0.5, 1.25, -1.5], [1, 1, nan], [20_000, 0, 0], [1, 1.5, -1.5],
    ]
    let confidences: [Float] = [1, 0.25, 1, 0.5, 1, 1, 0.75]
    let ids: [UInt64] = [1, 100, 2, 101, 3, 4, 102]
    points.withUnsafeBufferPointer { p in
      ids.withUnsafeBufferPointer { i in
        confidences.withUnsafeBufferPointer { c in
          XCTAssertTrue(producer.publish(points: p, identifiers: i, confidences: c,
                                         cameraPosition: simd_float3(0.5, 1.25, -2), context: IngestGoldenHeader.context))
        }
      }
    }
    XCTAssertEqual(pullOnce(), golden)
    XCTAssertEqual(producer.pointsIn, 7)
    XCTAssertEqual(producer.pointsSent, 3)
    XCTAssertEqual(producer.nonFiniteDropped, 3)
    XCTAssertEqual(producer.farDropped, 1)
  }

  func testPointProducerKeepsTheLowIdentifierBitsAndReportsConfidenceOne() {
    let producer = PointCloudIngestProducer(sink: pull)
    let points: [simd_float3] = [[0, 0, -1]]
    let ids: [UInt64] = [0x1234_5678_9abc_def0]
    points.withUnsafeBufferPointer { p in
      ids.withUnsafeBufferPointer { i in
        var context = IngestGoldenHeader.context
        context.cameraToWorld = matrix_identity_float4x4
        XCTAssertTrue(producer.publish(points: p, identifiers: i, confidences: nil, cameraPosition: .zero, context: context))
      }
    }
    let reply = pullOnce()
    let p = IngestMessageLayout.headerBytes
    XCTAssertEqual(reply.u32(p), 1)
    XCTAssertEqual(reply.u32(p + 4), 0x9abc_def0)
    XCTAssertEqual(reply.f32(p + 20), 1)
    XCTAssertEqual(reply.u8(IngestMessageLayout.sourceFlags), IngestMessageLayout.sourcePoints)
  }

  func testPhotoSavedEventEqualsTheGoldenPayload() throws {
    let golden = try VisibilityGridGoldens.data("ingest_photo_saved.bin")
    let payload = golden.subdata(in: IngestMessageLayout.headerBytes..<golden.count)
    let event = IngestMessageBuffer(capacity: PhotoSavedLayout.bytes)
    PhotoSavedEmitter.fillEvent(event, record: PhotoSavedFixture.record(), groupEpoch: 3)
    XCTAssertEqual(event.data(PhotoSavedLayout.bytes), payload)
  }

  func testPhotoSavedMessageEqualsTheGoldenThroughTheWriter() throws {
    let golden = try VisibilityGridGoldens.data("ingest_photo_saved.bin")
    IngestGoldenHeader.primeSequence(pull, kind: IngestMessageLayout.kindPhotoSaved, golden: golden, clock: clock)
    let b = IngestMessageBuffer(capacity: golden.count)
    IngestMessageWriter.begin(b, kind: IngestMessageLayout.kindPhotoSaved)
    IngestMessageWriter.payloadBytes(b, PhotoSavedLayout.bytes)
    writeFixtureHeader(b)
    let event = IngestMessageBuffer(capacity: PhotoSavedLayout.bytes)
    PhotoSavedEmitter.fillEvent(event, record: PhotoSavedFixture.record(), groupEpoch: 3)
    (b.bytes + IngestMessageLayout.headerBytes).copyMemory(from: event.bytes, byteCount: PhotoSavedLayout.bytes)
    XCTAssertTrue(pull.offer(b, length: golden.count))
    XCTAssertEqual(pullOnce(), golden)
  }

  func testSessionEventEqualsTheGoldenThroughTheWriter() throws {
    let golden = try VisibilityGridGoldens.data("ingest_session_event.bin")
    IngestGoldenHeader.primeSequence(pull, kind: IngestMessageLayout.kindSessionEvent, golden: golden, clock: clock)
    let b = IngestMessageBuffer(capacity: golden.count)
    IngestMessageWriter.begin(b, kind: IngestMessageLayout.kindSessionEvent)
    IngestMessageWriter.payloadBytes(b, IngestMessageLayout.sessionEventBytes)
    writeFixtureHeader(b)
    b.u8(IngestMessageLayout.headerBytes, IngestMessageLayout.sessionDepthModeChanged)
    b.i64(IngestMessageLayout.headerBytes + 8, 42)
    XCTAssertTrue(pull.offer(b, length: golden.count))
    XCTAssertEqual(pullOnce(), golden)
  }

  func testSessionEventEmitterWritesEventValueAndMissingPose() {
    let emitter = SessionEventEmitter(sink: pull, clock: clock)
    emitter.emit(event: IngestMessageLayout.sessionArRestarted, value: 4, sampleNs: 5, trackingState: 1, groupEpoch: 4)
    let reply = pullOnce()
    XCTAssertEqual(reply.ingestKind, IngestMessageLayout.kindSessionEvent)
    XCTAssertEqual(reply.count, IngestMessageLayout.headerBytes + IngestMessageLayout.sessionEventBytes)
    XCTAssertEqual(reply.u8(IngestMessageLayout.poseMatch), IngestMessageLayout.poseMatchMissing)
    XCTAssertEqual(reply.u16(IngestMessageLayout.groupEpoch), 4)
    XCTAssertEqual(reply.u8(IngestMessageLayout.headerBytes), IngestMessageLayout.sessionArRestarted)
    XCTAssertEqual(reply.i64(IngestMessageLayout.headerBytes + 8), 4)
    XCTAssertEqual(reply.f32(IngestMessageLayout.cameraToWorld), 1)
  }

  func testIdleReplyEqualsTheIdleGolden() throws {
    let golden = try VisibilityGridGoldens.data("ingest_idle.bin")
    let drain = IngestPullHandler.drainKindMask
    for _ in 0..<7 { _ = pullOnce(mask: drain) }
    XCTAssertEqual(pullOnce(mask: drain), golden)
    XCTAssertEqual(capture.count, 8)
  }

  func testPullRequestGoldenCarriesTheGridLoopMask() throws {
    let golden = try VisibilityGridGoldens.data("ingest_pull_request.bin")
    XCTAssertEqual(IngestPullHandler.pullMask(golden), 0x7e)
    XCTAssertEqual(pullRequest(mask: 0x7e), golden)
  }

  func testControlStartGoldenStartsTheShellOnlyWithAMarkerDetector() throws {
    let golden = try VisibilityGridGoldens.data("shell_control_start.bin")
    let shell = VisibilityGridIngestShell(clock: clock)
    var detector = false
    let handler = GridShellControlHandler(shell: { shell }, markerDetectorAvailable: { detector })
    XCTAssertEqual(handler.handle(golden), .markerDetectorUnavailable)
    XCTAssertFalse(shell.isStarted)
    detector = true
    var replies: [Data] = []
    handler.onMessage(golden) { replies.append($0) }
    XCTAssertEqual(replies, [ChannelStatusReply.data(0)])
    XCTAssertTrue(shell.isStarted)
    XCTAssertEqual(shell.currentConfig, IngestShellConfig(
      flags: 9, depthSourcePreference: 1, runMode: 0, markerDetectionHz: 8, markerSizeMetres: 0.12, groupEpoch: 3))
    XCTAssertEqual(shell.eventEpoch, 3)
  }

  func testControlRejectsMalformedAndWrongVersionAndStops() throws {
    let golden = try VisibilityGridGoldens.data("shell_control_start.bin")
    let shell = VisibilityGridIngestShell(clock: clock)
    let handler = GridShellControlHandler(shell: { shell }, markerDetectorAvailable: { true })
    XCTAssertEqual(handler.handle(nil), .contractMessageMalformed)
    XCTAssertEqual(handler.handle(golden.prefix(31)), .contractMessageMalformed)
    var badVersion = golden
    badVersion[4] = 9
    XCTAssertEqual(handler.handle(badVersion), .contractVersionMismatch)
    var unknownKind = golden
    unknownKind[ShellControlLayout.kind] = 77
    XCTAssertEqual(handler.handle(unknownKind), .contractMessageMalformed)
    var record = golden
    record[ShellControlLayout.kind] = UInt8(ShellControlLayout.kindSetRecording)
    record[ShellControlLayout.flags] = UInt8(ShellControlLayout.flagRecording)
    XCTAssertEqual(handler.handle(record), .recordingWriteFailed, "no recorder in this shell")
    XCTAssertEqual(handler.handle(golden), .ok)
    var stop = golden
    stop[ShellControlLayout.kind] = UInt8(ShellControlLayout.kindStop)
    XCTAssertEqual(handler.handle(stop), .ok)
    XCTAssertFalse(shell.isStarted)
    let detached = GridShellControlHandler(shell: { nil }, markerDetectorAvailable: { true })
    XCTAssertEqual(detached.handle(golden), .arSessionUnsupported)
  }

  /// Header fields of the golden fixture after begin and payload size.
  private func writeFixtureHeader(_ b: IngestMessageBuffer) {
    IngestMessageWriter.timing(b, sampleNs: IngestGoldenHeader.sampleNs, frameNs: IngestGoldenHeader.frameNs, acquireNs: IngestGoldenHeader.acquireNs)
    IngestMessageWriter.pose(
      b, trackingState: IngestTrackingState.tracking, poseMatch: IngestMessageLayout.poseMatchInterpolated,
      groupEpoch: 3, poseGapUs: -1250, cameraToWorld: IngestGoldenHeader.pose)
  }
  func testCameraToWorldIsWrittenWithAnExactRigidBottomRow() {
    var arkit = IngestGoldenHeader.pose
    arkit.columns.3.w = 0.99999994
    arkit.columns.0.w = 1e-9
    let fixed = IngestMessageWriter.rigidBottomRow(arkit)
    XCTAssertEqual(fixed.columns.3.w, 1)
    XCTAssertEqual(fixed.columns.0.w, 0)
    XCTAssertEqual(fixed.columns.3.x, 0.5)
    XCTAssertEqual(IngestMessageWriter.rigidBottomRow(simd_float4x4()), simd_float4x4())
  }
}

/// The photo of the shared photo saved golden: photo id bytes 0-31, attempt
/// UUID a0..af, exposure 1,234,567,890,123 ns, translation (0.1, 0.2, 0.3),
/// 4000x3000 still with fx 2800, fy 2801, cx 2000, cy 1500, sharpness 812,
/// automatic, group epoch 3.
enum PhotoSavedFixture {
  static let captureId = (0..<32).map { String(format: "%02x", $0) }.joined()
  static let attemptId = "a0a1a2a3-a4a5-a6a7-a8a9-aaabacadaeaf"
  static let exposureNs: Int64 = 1_234_567_890_123

  static func record(captureId: String = captureId, exposureNs: Int64 = exposureNs) -> DurablePhotoRecord {
    var pose = matrix_identity_float4x4
    pose.columns.3 = simd_float4(0.1, 0.2, 0.3, 1)
    return DurablePhotoRecord(
      captureId: captureId, attemptId: attemptId, exposureTimestampNs: exposureNs, cameraToWorld: pose,
      intrinsics: simd_float4(2800, 2801, 2000, 1500), width: 4000, height: 3000, sharpness: 812,
      automatic: true, blurRejected: false)
  }
}
