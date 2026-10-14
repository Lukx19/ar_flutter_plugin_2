import Foundation
import simd
import XCTest

@testable import ar_flutter_plugin_2

/// LiDAR depth conversion: 2x2 nearest-valid downscale to 128x96, ARKit
/// confidence low/medium/high to 0/128/255, millimetres, intrinsics scaled to
/// the payload, LiDAR-class source flags and the acquisition throttle.
final class DepthIngestProducerTests: XCTestCase {
  private let clock = FakeIngestClock(5_000)
  private lazy var pull = IngestPullHandler(clock: clock)
  private let capture = ReplyCapture()

  private func downscale(_ metres: [Float], _ confidence: [UInt8]?, width: Int, height: Int, factor: Int = 2,
                         mapping: DepthConfidenceMapping = .arKitLevels) -> ([UInt16], [UInt8]) {
    let w = width / factor, h = height / factor
    var depth = [UInt16](repeating: 0xffff, count: w * h)
    var conf = [UInt8](repeating: 0xff, count: w * h)
    metres.withUnsafeBytes { d in
      let run = { (c: UnsafeRawPointer?) in
        let planes = DepthPlanes(depth: d.baseAddress!, width: width, height: height, depthRowBytes: width * 4,
                                 confidence: c, confidenceRowBytes: width, confidenceMapping: mapping)
        depth.withUnsafeMutableBufferPointer { dp in
          conf.withUnsafeMutableBufferPointer { cp in
            DepthIngestProducer.downscaleNearestValid(planes, factor: factor, depthOut: dp.baseAddress!,
                                                     confidenceOut: c == nil ? nil : cp.baseAddress!)
          }
        }
      }
      if let confidence { confidence.withUnsafeBytes { run($0.baseAddress!) } } else { run(nil) }
    }
    return (depth, conf)
  }

  func testConfidenceLevelsMapToContractBytes() {
    let table = DepthConfidenceMapping.arKitLevels.table
    XCTAssertEqual(table[0], 0)
    XCTAssertEqual(table[1], 128)
    XCTAssertEqual(table[2], 255)
    XCTAssertEqual(table[3], 0)
  }

  func testDownscaleKeepsTheNearestValidPixelOfEachBlock() {
    // Two 2x2 blocks side by side (4x2 input).
    let metres: [Float] = [
      2.0, 1.0, .nan, 0,
      1.5, 3.0, -1, .infinity,
    ]
    let (depth, conf) = downscale(metres, [2, 2, 2, 2, 2, 2, 2, 2], width: 4, height: 2)
    XCTAssertEqual(depth, [1000, 0])
    XCTAssertEqual(conf, [255, 0], "a block without valid depth has confidence 0")
  }

  func testMediumOrHighConfidenceWinsOverANearerLowConfidencePixel() {
    let metres: [Float] = [
      0.5, 1.2,
      0.9, 2.0,
    ]
    let (depth, conf) = downscale(metres, [0, 1, 0, 2], width: 2, height: 2)
    XCTAssertEqual(depth, [1200])
    XCTAssertEqual(conf, [128])
    let (lowOnly, lowConf) = downscale(metres, [0, 0, 0, 0], width: 2, height: 2)
    XCTAssertEqual(lowOnly, [500], "only low confidence: nearest valid")
    XCTAssertEqual(lowConf, [0])
  }

  func testMillimetresRoundAndClamp() {
    let (depth, _) = downscale([1.2344, 70, 0.0006, 1.0], nil, width: 4, height: 1, factor: 1)
    XCTAssertEqual(depth, [1234, 65_535, 1, 1000])
  }

  func testLidarFrameBecomesA128x96MessageWithLidarFlagsAndScaledIntrinsics() {
    let producer = DepthIngestProducer(sink: pull)
    let frame = FakeIngestFrame()
    frame.timestampNs = 10_000_000_000
    frame.lidarDepth(metres: 1.5)
    var pose = matrix_identity_float4x4
    pose.columns.3 = simd_float4(1, 2, 3, 1)
    frame.cameraToWorld = pose
    let context = IngestSampleContext(sampleNs: frame.timestampNs, frameNs: frame.timestampNs, acquireNs: 4_000,
                                      trackingState: IngestTrackingState.tracking, groupEpoch: 2, cameraToWorld: pose)
    producer.onFrame(frame, mode: IngestDepthMode.raw, context: context)
    pull.onPull(pullRequest(), reply: capture.reply)
    let m = capture.last!
    XCTAssertEqual(m.ingestKind, IngestMessageLayout.kindDepthImage)
    XCTAssertEqual(m.u8(IngestMessageLayout.sourceFlags), IngestMessageLayout.sourceDepth | IngestMessageLayout.sourceLidarClass)
    XCTAssertEqual(m.u16(IngestMessageLayout.width), 128)
    XCTAssertEqual(m.u16(IngestMessageLayout.height), 96)
    XCTAssertEqual(m.u16(IngestMessageLayout.rowStride), 256)
    XCTAssertEqual(m.u8(IngestMessageLayout.depthUnit), IngestMessageLayout.depthUnitU16Mm)
    XCTAssertEqual(m.u8(IngestMessageLayout.confidenceFormat), IngestMessageLayout.confidenceU8)
    XCTAssertEqual(m.count, IngestMessageLayout.headerBytes + 128 * 96 * 3)
    XCTAssertEqual(m.u32(IngestMessageLayout.payloadBytes), UInt32(128 * 96 * 3))
    XCTAssertEqual(m.u16(IngestMessageLayout.headerBytes), 1500)
    XCTAssertEqual(m.u8(IngestMessageLayout.headerBytes + 128 * 96 * 2), 255)
    XCTAssertEqual(m.u8(IngestMessageLayout.poseMatch), IngestMessageLayout.poseMatchExact)
    XCTAssertEqual(m.i32(IngestMessageLayout.poseGapUs), 0)
    XCTAssertEqual(m.i64(IngestMessageLayout.sampleTimestampNs), 10_000_000_000)
    XCTAssertEqual(m.i64(IngestMessageLayout.frameTimestampNs), 10_000_000_000)
    XCTAssertEqual(m.i64(IngestMessageLayout.acquireTimeNs), 4_000)
    XCTAssertEqual(m.u16(IngestMessageLayout.groupEpoch), 2)
    XCTAssertEqual(m.f32(IngestMessageLayout.cameraToWorld + 13 * 4), 2, "column-major translation y")
    // 1440 x 128 / 1920 = 96; 960 x 128 / 1920 = 64; 720 x 96 / 1440 = 48.
    XCTAssertEqual(m.f32(IngestMessageLayout.intrinsics), 96)
    XCTAssertEqual(m.f32(IngestMessageLayout.intrinsics + 4), 96)
    XCTAssertEqual(m.f32(IngestMessageLayout.intrinsics + 8), 64)
    XCTAssertEqual(m.f32(IngestMessageLayout.intrinsics + 12), 48)
  }

  func testFullResolutionWhenDownscaleIsOne() {
    let producer = DepthIngestProducer(sink: pull)
    producer.downscale = 1
    let frame = FakeIngestFrame()
    frame.timestampNs = 1
    frame.lidarDepth()
    producer.onFrame(frame, mode: IngestDepthMode.raw, context: IngestSampleContext(
      sampleNs: 1, frameNs: 1, acquireNs: 1, trackingState: 1, groupEpoch: 0, cameraToWorld: matrix_identity_float4x4))
    pull.onPull(pullRequest(), reply: capture.reply)
    XCTAssertEqual(capture.last?.u16(IngestMessageLayout.width), 256)
    XCTAssertEqual(capture.last?.count, IngestMessageLayout.headerBytes + 256 * 192 * 3)
  }

  func testRequestedSizeChoosesTheBlockFactor() {
    let producer = DepthIngestProducer(sink: pull)
    XCTAssertEqual(producer.blockFactor(sourceWidth: 256, sourceHeight: 192), 2, "no request keeps the 2x2 default")
    producer.requestedSize = IngestDepthSize(width: 128, height: 96)
    XCTAssertEqual(producer.blockFactor(sourceWidth: 256, sourceHeight: 192), 2)
    producer.requestedSize = IngestDepthSize(width: 256, height: 192)
    XCTAssertEqual(producer.blockFactor(sourceWidth: 256, sourceHeight: 192), 1)
    producer.requestedSize = IngestDepthSize(width: 64, height: 48)
    XCTAssertEqual(producer.blockFactor(sourceWidth: 256, sourceHeight: 192), 4)
    producer.requestedSize = IngestDepthSize(width: 100, height: 96)
    XCTAssertEqual(producer.blockFactor(sourceWidth: 256, sourceHeight: 192), 2, "output covers the request")
    producer.requestedSize = IngestDepthSize(width: 512, height: 384)
    XCTAssertEqual(producer.blockFactor(sourceWidth: 256, sourceHeight: 192), 1, "never upsampled")
  }

  func testRequestedFullSizeSendsTheFullMap() {
    let producer = DepthIngestProducer(sink: pull)
    producer.requestedSize = IngestDepthSize(width: 256, height: 192)
    let frame = FakeIngestFrame()
    frame.timestampNs = 1
    frame.lidarDepth()
    producer.onFrame(frame, mode: IngestDepthMode.raw, context: IngestSampleContext(
      sampleNs: 1, frameNs: 1, acquireNs: 1, trackingState: 1, groupEpoch: 0, cameraToWorld: matrix_identity_float4x4))
    pull.onPull(pullRequest(), reply: capture.reply)
    XCTAssertEqual(capture.last?.u16(IngestMessageLayout.width), 256)
    XCTAssertEqual(capture.last?.u16(IngestMessageLayout.height), 192)
  }

  func testAcquisitionIsThrottledToFourHertzOfFrameTimeAndSkipsDisabledOrMissingDepth() {
    let producer = DepthIngestProducer(sink: pull)
    let frame = FakeIngestFrame()
    frame.lidarDepth()
    func at(_ ns: Int64, mode: Int = IngestDepthMode.raw) {
      frame.timestampNs = ns
      producer.onFrame(frame, mode: mode, context: IngestSampleContext(
        sampleNs: ns, frameNs: ns, acquireNs: ns, trackingState: 1, groupEpoch: 0, cameraToWorld: matrix_identity_float4x4))
    }
    at(1_000_000_000)
    at(1_100_000_000)
    at(1_249_999_999)
    at(1_250_000_000)
    at(1_600_000_000, mode: IngestDepthMode.disabled)
    XCTAssertEqual(producer.acquired, 2)
    XCTAssertEqual(producer.published, 2)
    frame.depthWidth = 0
    at(2_000_000_000)
    XCTAssertEqual(producer.missing, 1)
  }
}
