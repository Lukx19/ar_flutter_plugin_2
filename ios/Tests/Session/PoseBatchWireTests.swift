import ARKit
import simd
import XCTest
@testable import ar_flutter_plugin_2

/// `pose_batch_v2` bytes against the Android encoder layout
/// (`PoseDataExtractor.latestPacked`, `PackedPoseWireV2`) and its transport.
///
/// The goldens were produced by an independent transcription of the Kotlin
/// encoder with float32 arithmetic emulated op by op (no JVM on the build
/// host); the Dart decoder `ARFramePose.fromPacked` reads the same offsets.
final class PoseBatchWireTests: XCTestCase {
  func testSemanticFloorFactsStayPairedThroughDropAndPause() {
    let sample = Data(repeating: 0, count: PoseBatchWire.sampleBytes)
    let floor = SemanticFloorSample(anchorId: "floor", worldY: -1.5, normalY: 1, areaSquareMetres: 4)
    var sent: [PoseBatch] = []
    var completions: [() -> Void] = []
    let dispatcher = PoseBatchDispatcher(capacity: 2) { batch, completion in
      sent.append(batch)
      completions.append(completion)
    }
    let sink = PoseBatchSink(dispatcher: dispatcher)
    sink.open()
    sink.offer(sample, semanticFloor: floor)
    sink.offer(sample, semanticFloor: floor)
    sink.offer(sample)
    sink.offer(sample, semanticFloor: floor)
    completions.removeFirst()()
    XCTAssertEqual(sent[0].semanticFloorV1, [floor])
    XCTAssertEqual(sent[1].semanticFloorV1, [nil, floor])
    sink.pause()
    completions.removeFirst()()
    sink.resume()
    sink.offer(sample)
    XCTAssertEqual(sent.last?.semanticFloorV1, [])
  }

  func testDisplayRotationUsesTheActualViewBasisForBothPortraitsAndLandscapes() throws {
    let camera = matrix_identity_float4x4
    for angle in [Float(0), .pi / 2, -.pi / 2, .pi] {
      let display = simd_float4x4(simd_quatf(angle: angle, axis: SIMD3<Float>(0, 0, 1)))
      let actual = try XCTUnwrap(PoseStreamObserver.displayRotation(camera: camera, view: simd_inverse(display)))
      XCTAssertEqual(sin(actual), Double(sin(angle)), accuracy: 1e-5)
      XCTAssertEqual(cos(actual), Double(cos(angle)), accuracy: 1e-5)
    }
    var invalid = matrix_identity_float4x4
    invalid.columns.0.x = .nan
    XCTAssertNil(PoseStreamObserver.displayRotation(camera: invalid, view: matrix_identity_float4x4))
  }

  func testDisplayRotationStaysWithItsSampleAcrossQueueDropsAndPause() {
    var sent: [PoseBatch] = []
    var acknowledgements: [() -> Void] = []
    let dispatcher = PoseBatchDispatcher(capacity: 2) { batch, acknowledge in
      sent.append(batch); acknowledgements.append(acknowledge)
    }
    let sink = PoseBatchSink(dispatcher: dispatcher)
    let sample = Self.bytes(Self.yaw180Golden)
    sink.open()
    sink.offer(sample, displayRotation: 0)
    sink.offer(sample, displayRotation: .pi / 2)
    sink.offer(sample, displayRotation: -.pi / 2)
    sink.offer(sample)
    acknowledgements[0]()
    XCTAssertEqual(sent[0].displayRotationV1, [0])
    XCTAssertEqual(sent[1].displayRotationV1, [-Double.pi / 2, nil])
    XCTAssertEqual(sent[1].droppedOldestCount, 1)
    sink.pause()
    sink.offer(sample, displayRotation: .pi)
    acknowledgements[1]()
    XCTAssertEqual(sent.count, 2)
    sink.resume()
    sink.offer(sample, displayRotation: .pi)
    XCTAssertEqual(sent[2].displayRotationV1, [Double.pi])
  }

  /// 180 degrees about +Y, translation (1.5, -0.25, 2.0), tracking, sequence 7.
  static let yaw180Golden =
    "07000000000000007bc02cc8990100000100000000002000000000000000000000000000000000000000803f01010000"
    + "000000000000c03f000080be0000004000000000000000800000803f00000000000080bf000000000000000000000000"
    + "00000080000080bf000000800000008000000080000000800000803f000000800000c03f000080be000000400000803f"
    + "0000c03f000080be00000040000000000000803f0000000000000000000080bf00000000000000000000000000000000"
    + "0000803f00000000000000000000000000000000000080bf000000000000c03f000080be000000400000803f"

  /// 30 degrees about the normalized axis (1, 2, 3), limited tracking.
  static let axis30Golden =
    "0100000000000000e7c32cc89901000079df0d8648700000000000000000000000000000000000000000000002000000"
    + "00000000000040bf0000a03f0000003fea46773f507f543e35aa0dbe35aa8dbdff26603f510ed73e154774be00000000"
    + "1575c33e758067bf2ba243be00000080648997be87159c3d3bc073bf00000080000040bf0000a03f0000003f0000803f"
    + "000040bf0000a03f0000003f35aa8d3d35aa0d3e507f543eea46773fff26603f510ed73e154774be000000001575c3be"
    + "7580673f2ba2433e000000006489973e87159cbd3bc0733f00000000000040bf0000a03f0000003f0000803f"

  static func bytes(_ hex: String) -> Data {
    var data = Data()
    var index = hex.startIndex
    while index < hex.endIndex {
      let next = hex.index(index, offsetBy: 2)
      data.append(UInt8(hex[index..<next], radix: 16)!)
      index = next
    }
    return data
  }

  static func float(_ data: Data, _ offset: Int) -> Float {
    var bits: UInt32 = 0
    for i in 0..<4 { bits |= UInt32(data[offset + i]) << UInt32(i * 8) }
    return Float(bitPattern: bits)
  }

  static func matrix(columnMajor values: [Float]) -> simd_float4x4 {
    simd_float4x4(columns: (
      SIMD4(values[0], values[1], values[2], values[3]),
      SIMD4(values[4], values[5], values[6], values[7]),
      SIMD4(values[8], values[9], values[10], values[11]),
      SIMD4(values[12], values[13], values[14], values[15])))
  }

  func testYaw180SampleEqualsAndroidGolden() {
    let transform = PoseBatchWireTests.matrix(columnMajor: [
      -1, 0, 0, 0,
      0, 1, 0, 0,
      0, 0, -1, 0,
      1.5, -0.25, 2.0, 1,
    ])
    let sample = PoseWireSample(
      sequence: 7,
      systemTimestampMs: 1_760_000_000_123,
      timestampNs: 9_007_199_254_740_993,
      cameraTransform: transform,
      trackingState: .normal)
    let encoded = PoseBatchWire.encode(sample)
    XCTAssertEqual(encoded.count, PoseBatchWire.sampleBytes)
    XCTAssertEqual(encoded, PoseBatchWireTests.bytes(PoseBatchWireTests.yaw180Golden))
  }

  func testGeneralRotationSampleEqualsAndroidGolden() {
    let golden = PoseBatchWireTests.bytes(PoseBatchWireTests.axis30Golden)
    let transform = (0..<16).map { PoseBatchWireTests.float(golden, 172 + $0 * 4) }
    let sample = PoseWireSample(
      sequence: 1,
      systemTimestampMs: 1_760_000_000_999,
      timestampNs: 123_456_789_012_345,
      cameraTransform: PoseBatchWireTests.matrix(columnMajor: transform),
      trackingState: .limited(.initializing))
    XCTAssertEqual(PoseBatchWire.encode(sample), golden)
  }

  func testFieldOffsetsMatchTheDartDecoder() {
    let golden = PoseBatchWireTests.bytes(PoseBatchWireTests.yaw180Golden)
    XCTAssertEqual(golden[0], 7)
    // Flags: state 1 (tracking) plus the tracking bit 256; source 0 (live).
    XCTAssertEqual(golden[44], 1)
    XCTAssertEqual(golden[45], 1)
    XCTAssertEqual(golden[48], 0)
    XCTAssertEqual(PoseBatchWireTests.float(golden, 40), 1)
    // OpenCV position equals the tracking position.
    XCTAssertEqual(PoseBatchWireTests.float(golden, 52), 1.5)
    XCTAssertEqual(PoseBatchWireTests.float(golden, 144), 1.5)
    // OpenCV transform flips camera Y and Z columns.
    XCTAssertEqual(PoseBatchWireTests.float(golden, 80 + 5 * 4), -1)
    XCTAssertEqual(PoseBatchWireTests.float(golden, 80 + 10 * 4), 1)
  }

  func testTrackingStateMapping() {
    XCTAssertTrue(PoseWireSample.wireTrackingState(.normal) == (.tracking, true))
    XCTAssertTrue(PoseWireSample.wireTrackingState(.limited(.excessiveMotion)) == (.paused, false))
    XCTAssertTrue(PoseWireSample.wireTrackingState(.limited(.relocalizing)) == (.paused, false))
    XCTAssertTrue(PoseWireSample.wireTrackingState(.notAvailable) == (.paused, false))
  }

  func testNanosecondTimestamps() {
    XCTAssertEqual(PoseWireSample.nanoseconds(1.5), 1_500_000_000)
    XCTAssertEqual(PoseWireSample.nanoseconds(12_345.000_000_001), 12_345_000_000_001)
  }

  func testRateLimiterAllowsThirtyHertzOfIncreasingTimestamps() {
    var limiter = PoseRateLimiter()
    XCTAssertTrue(limiter.shouldEmit(1_000_000_000))
    XCTAssertFalse(limiter.shouldEmit(1_000_000_000))
    XCTAssertFalse(limiter.shouldEmit(1_016_666_666))
    XCTAssertTrue(limiter.shouldEmit(1_033_333_333))
    XCTAssertFalse(limiter.shouldEmit(900_000_000))
    limiter.reset()
    XCTAssertTrue(limiter.shouldEmit(900_000_000))
  }

  func testRateLimiterKeepsEveryFrameOfAJitteredThirtyHertzCamera() {
    var limiter = PoseRateLimiter()
    var timestampNs: Int64 = 1_000_000_000
    var emitted = 0
    for index in 0..<300 {
      timestampNs += index % 2 == 0 ? 33_200_000 : 33_400_000
      if limiter.shouldEmit(timestampNs) { emitted += 1 }
    }
    XCTAssertEqual(emitted, 300)
  }

  func testRateLimiterKeepsEveryOtherFrameOfASixtyHertzCamera() {
    var limiter = PoseRateLimiter()
    var timestampNs: Int64 = 1_000_000_000
    var emitted = 0
    for index in 0..<600 {
      timestampNs += index % 2 == 0 ? 16_600_000 : 16_733_334
      if limiter.shouldEmit(timestampNs) { emitted += 1 }
    }
    XCTAssertEqual(emitted, 300)
  }

  func testDispatcherKeepsOneBatchInFlightAndDropsOldestBeyondCapacity() {
    var sent: [PoseBatch] = []
    var acknowledgements: [() -> Void] = []
    let dispatcher = PoseBatchDispatcher(capacity: 3) { batch, acknowledge in
      sent.append(batch)
      acknowledgements.append(acknowledge)
    }
    func sample(_ marker: UInt8) -> Data {
      var data = Data(repeating: 0, count: PoseBatchWire.sampleBytes)
      data[0] = marker
      return data
    }
    dispatcher.offer(sample(1))
    XCTAssertEqual(sent.count, 1)
    XCTAssertEqual(sent[0].sampleCount, 1)
    for marker: UInt8 in 2...6 { dispatcher.offer(sample(marker)) }
    XCTAssertEqual(sent.count, 1, "one batch in flight")
    XCTAssertEqual(dispatcher.pendingCount, 3)
    acknowledgements[0]()
    XCTAssertEqual(sent.count, 2)
    XCTAssertEqual(sent[1].sampleCount, 3)
    XCTAssertEqual(sent[1].droppedOldestCount, 2)
    XCTAssertEqual(sent[1].sampleBytes.count, 3 * PoseBatchWire.sampleBytes)
    XCTAssertEqual(sent[1].sampleBytes[0], 4)
    XCTAssertEqual(sent[1].sampleBytes[2 * PoseBatchWire.sampleBytes], 6)
    acknowledgements[1]()
    XCTAssertFalse(dispatcher.isInFlight)
  }

  func testDispatcherClearDropsPendingButKeepsTheInFlightBatch() {
    var sent: [PoseBatch] = []
    var acknowledgements: [() -> Void] = []
    let dispatcher = PoseBatchDispatcher { batch, acknowledge in
      sent.append(batch)
      acknowledgements.append(acknowledge)
    }
    let sample = Data(repeating: 1, count: PoseBatchWire.sampleBytes)
    dispatcher.offer(sample)
    dispatcher.offer(sample)
    dispatcher.clear()
    XCTAssertEqual(dispatcher.pendingCount, 0)
    XCTAssertTrue(dispatcher.isInFlight)
    dispatcher.offer(sample)
    XCTAssertEqual(sent.count, 1)
    acknowledgements[0]()
    XCTAssertEqual(sent.count, 2)
    XCTAssertEqual(sent[1].sampleCount, 1)
    XCTAssertEqual(sent[1].droppedOldestCount, 0)
  }

  func testPoseStreamSequenceRestartsWhenEnabledAndStopsWhenDisabled() {
    let stream = PoseStreamObserver { _ in }
    XCTAssertNil(stream.admit(timestampNs: 1_000_000_000))
    stream.enable()
    XCTAssertEqual(stream.admit(timestampNs: 1_000_000_000), 1)
    XCTAssertNil(stream.admit(timestampNs: 1_010_000_000))
    XCTAssertEqual(stream.admit(timestampNs: 1_040_000_000), 2)
    stream.disable()
    XCTAssertNil(stream.admit(timestampNs: 2_000_000_000))
    stream.enable()
    XCTAssertEqual(stream.admit(timestampNs: 2_000_000_000), 1)
  }

  func testSinkDropsSamplesOfferedWhilePausedAndResumesAfterwards() {
    var sent: [PoseBatch] = []
    var acknowledgements: [() -> Void] = []
    let sink = PoseBatchSink(dispatcher: PoseBatchDispatcher { batch, acknowledge in
      sent.append(batch)
      acknowledgements.append(acknowledge)
    })
    let sample = Data(repeating: 2, count: PoseBatchWire.sampleBytes)
    sink.offer(sample)
    XCTAssertEqual(sent.count, 0, "closed sink sends nothing")
    sink.open()
    sink.offer(sample)
    sink.offer(sample)
    XCTAssertEqual(sent.count, 1)
    sink.pause()
    // A sample queued on the main queue before the pause arrives afterwards.
    sink.offer(sample)
    acknowledgements[0]()
    XCTAssertEqual(sent.count, 1, "no pose reaches Dart while paused")
    sink.resume()
    sink.offer(sample)
    XCTAssertEqual(sent.count, 2)
    XCTAssertEqual(sent[1].sampleCount, 1)
    XCTAssertEqual(sent[1].droppedOldestCount, 0)
  }

  func testCaptureInitSequenceReplayIsAnsweredWithAnEmptyList() {
    let reply = CaptureChannelHostReplies.reply(
      for: CaptureChannelHostReplies.replayNativeCaptureRecoveryV2)
    XCTAssertEqual(CaptureChannelHostReplies.replayNativeCaptureRecoveryV2,
                   "replayNativeCaptureRecoveryV2")
    XCTAssertEqual((reply as? [Any])?.count, 0)
    XCTAssertNil(CaptureChannelHostReplies.reply(for: "captureImageAttempt"))
  }
}
