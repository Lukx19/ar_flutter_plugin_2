import ARKit
import CoreVideo
import XCTest
@testable import ar_flutter_plugin_2

/// Reading the ARKit session recorder's format: frame records, depth chunks,
/// point chunks, stills and video segments of the trimmed room2 fixture, and
/// depth decoding against the recorder's own encoding.
final class RecordedSessionTests: XCTestCase {
  func testFixtureFrameRecordsParseWithRecordedValues() throws {
    let session = try RecordedSession(directory: try RecordedSessionFixture.room2URL())
    XCTAssertEqual(session.frameCount, 24)
    XCTAssertEqual(session.meta.frames, 24)
    let first = try session.frame(at: 0)
    XCTAssertEqual(first.timestamp, 40368.430217458, accuracy: 1e-9)
    XCTAssertEqual(first.frameIndex, 104)
    XCTAssertEqual(first.trackingState, .normal)
    XCTAssertEqual(first.imageWidth, 1920)
    XCTAssertEqual(first.imageHeight, 1440)
    XCTAssertEqual(first.intrinsics[0][0], 1450.602, accuracy: 0.01)
    XCTAssertEqual(first.intrinsics[2][0], 971.559, accuracy: 0.01)
    XCTAssertEqual(first.intrinsics[2][1], 686.22, accuracy: 0.01)
    XCTAssertEqual(first.cameraTransform[3][3], 1, accuracy: 1e-6)
    XCTAssertEqual(first.videoIndex, 0)
    XCTAssertEqual(first.depthIndex, 0)
    XCTAssertNil(first.pointsIndex)
    XCTAssertEqual(first.lightEstimate?.ambientIntensity ?? 0, 955.265, accuracy: 0.01)
    var previous = -Double.infinity
    var videoIndexes: [UInt32] = []
    for index in 0..<session.frameCount {
      let record = try session.frame(at: index)
      XCTAssertGreaterThan(record.timestamp, previous, "timestamps increase at frame \(index)")
      previous = record.timestamp
      if let video = record.videoIndex { videoIndexes.append(video) }
    }
    XCTAssertEqual(videoIndexes, Array(0..<12), "video at every second frame")
    XCTAssertThrowsError(try session.frame(at: 24))
  }

  func testFixtureDepthMapsDecodeToTheRecordedSize() throws {
    let session = try RecordedSession(directory: try RecordedSessionFixture.room2URL())
    XCTAssertEqual(session.depthMapCount, 6)
    for index in 0..<UInt32(session.depthMapCount) {
      let map = try session.depthMap(index)
      XCTAssertEqual(map.index, index)
      XCTAssertEqual(map.width, 256)
      XCTAssertEqual(map.height, 192)
      XCTAssertEqual(map.millimetres.count, 256 * 192)
      XCTAssertEqual(map.confidence.count, 256 * 192)
      XCTAssertTrue(map.confidence.allSatisfy { $0 <= 2 }, "ARKit confidence levels")
      let valid = map.millimetres.filter { $0 > 0 }
      XCTAssertGreaterThan(valid.count, map.millimetres.count / 2)
      XCTAssertTrue(valid.allSatisfy { $0 < 10_000 }, "room depth is below 10 m")
    }
    let buffers = RecordedDepthBuffers()
    let map = try session.depthMap(0)
    let data = try XCTUnwrap(buffers.make(map))
    XCTAssertEqual(CVPixelBufferGetPixelFormatType(data.depthMap), kCVPixelFormatType_DepthFloat32)
    XCTAssertEqual(CVPixelBufferGetWidth(data.depthMap), 256)
    CVPixelBufferLockBaseAddress(data.depthMap, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(data.depthMap, .readOnly) }
    let row = CVPixelBufferGetBaseAddress(data.depthMap)!.advanced(by: 100 * CVPixelBufferGetBytesPerRow(data.depthMap))
      .assumingMemoryBound(to: Float32.self)
    XCTAssertEqual(row[128], Float32(map.millimetres[100 * 256 + 128]) / 1000, accuracy: 1e-6)
  }

  func testDepthDecodeEqualsTheRecorderEncoding() throws {
    let width = 16
    let height = 12
    let metres: [Float] = (0..<(width * height)).map { i in
      switch i % 7 {
      case 0: return 0
      case 1: return .nan
      case 2: return 70
      default: return 0.25 + Float(i) * 0.0123
      }
    }
    let confidence: [UInt8] = (0..<(width * height)).map { UInt8($0 % 3) }
    var recording = SyntheticRecording()
    recording.frames = [
      .init(timestamp: 10, depthIndex: 0),
      .init(timestamp: 10.1, depthIndex: 1),
    ]
    recording.depthChunks = [
      SyntheticRecording.depthChunk(index: 0, timestamp: 10, width: width, height: height,
                                    metres: metres, confidence: confidence),
      SyntheticRecording.depthChunk(index: 1, timestamp: 10.1, width: width, height: height,
                                    metres: metres, confidence: confidence, compress: false),
    ]
    let session = try RecordedSession(directory: try recording.write())
    let expected = metres.map { v -> UInt16 in v.isFinite && v > 0 ? UInt16(min(65535, (v * 1000).rounded())) : 0 }
    for index: UInt32 in [0, 1] {
      let map = try session.depthMap(index)
      XCTAssertEqual(map.millimetres, expected, "chunk \(index)")
      XCTAssertEqual(map.confidence, confidence)
      XCTAssertEqual(map.timestamp, index == 0 ? 10 : 10.1)
    }
    XCTAssertEqual(try session.depthMap(0).millimetres[2], 65535, "70 m clamps like the recorder")
  }

  func testFixturePointCloudsCarryIdentifiers() throws {
    let session = try RecordedSession(directory: try RecordedSessionFixture.room2URL())
    XCTAssertEqual(session.pointCloudCount, 18)
    let cloud = try session.featurePoints(17)
    XCTAssertFalse(cloud.points.isEmpty)
    XCTAssertEqual(cloud.points.count, cloud.identifiers.count)
    XCTAssertTrue(cloud.points.allSatisfy { simd_length($0) < 20 })
    XCTAssertEqual(Set(cloud.identifiers).count, cloud.identifiers.count, "identifiers are unique per cloud")
  }

  func testStillLookupPicksTheNearestRecordedStill() throws {
    let session = try RecordedSession(directory: try RecordedSessionFixture.room2URL())
    XCTAssertEqual(session.stills.map(\.index), [0, 1])
    let first = session.stills[0].timestamp
    let second = session.stills[1].timestamp
    XCTAssertEqual(first, 40367.696505833, accuracy: 1e-9)
    XCTAssertEqual(session.stillNearest(to: first - 100)?.index, 0)
    XCTAssertEqual(session.stillNearest(to: first + 0.1)?.index, 0)
    XCTAssertEqual(session.stillNearest(to: (first + second) / 2 + 0.01)?.index, 1)
    XCTAssertEqual(session.stillNearest(to: second + 100)?.index, 1)
    let still = session.stills[1]
    XCTAssertEqual(still.width, 2016)
    XCTAssertEqual(still.height, 1512)
    let image = try XCTUnwrap(RecordedStillDecoder.decode(session.url(of: still)))
    XCTAssertEqual(CVPixelBufferGetPixelFormatType(image), kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)
    XCTAssertEqual(CVPixelBufferGetWidth(image), 252, "fixture stills are 1/8 size")
  }

  func testVideoSegmentsDecodeInVideoIndexOrder() throws {
    let session = try RecordedSession(directory: try RecordedSessionFixture.room2URL())
    XCTAssertTrue(session.hasVideo)
    let reader = RecordedVideoReader(directory: session.directory, segments: session.videoSegments)
    for index: UInt32 in [0, 1, 5, 11] {
      let image = try XCTUnwrap(reader.image(videoIndex: index), "video index \(index)")
      XCTAssertEqual(CVPixelBufferGetWidth(image), 240)
      XCTAssertEqual(CVPixelBufferGetHeight(image), 180)
      XCTAssertEqual(CVPixelBufferGetPixelFormatType(image), kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)
    }
    XCTAssertNotNil(reader.image(videoIndex: 2), "an earlier index reopens the segment")
    XCTAssertNil(reader.image(videoIndex: 12), "past the last segment")
    reader.close()
  }

  func testMissingVideoLeavesNoSegmentAndThePlaceholderHasTheRecordedSize() throws {
    let session = try RecordedSession(directory: try RecordedSessionFixture.room2Copy(omitting: ["video_000.mov"]))
    XCTAssertFalse(session.hasVideo)
    XCTAssertEqual(session.meta.videoSegments.count, 1, "meta.json still lists the segment")
    let placeholder = try XCTUnwrap(RecordedPlaceholderImage.make(width: 1920, height: 1440))
    XCTAssertEqual(CVPixelBufferGetWidth(placeholder), 1920)
    XCTAssertEqual(CVPixelBufferGetPlaneCount(placeholder), 2)
  }

  func testUnsupportedFormatsAreRefused() throws {
    let directory = try SyntheticRecording(frames: [.init(timestamp: 1)]).write()
    try JSONSerialization.data(withJSONObject: ["format": "capture3d-arkit-recording", "version": 2])
      .write(to: directory.appendingPathComponent("meta.json"))
    XCTAssertThrowsError(try RecordedSession(directory: directory)) { error in
      XCTAssertEqual(error as? RecordedSessionError, .unsupportedFormat("version 2"))
    }
    XCTAssertThrowsError(try RecordedSession(directory: directory.appendingPathComponent("missing"))) { error in
      XCTAssertEqual(error as? RecordedSessionError, .missingFile("meta.json"))
    }
  }
}

/// Selecting playback from the capture view creation parameters.
final class RecordedPlaybackSelectionTests: XCTestCase {
  private let recordings = URL(fileURLWithPath: "/data/Library/Application Support/recordings", isDirectory: true)

  private func select(_ params: [String: Any], allows: Bool = true) -> RecordedPlaybackSelection {
    RecordedPlaybackSelector.select(
      creationParams: params, allowsPlayback: allows, recordingsDirectory: recordings, isRecording: { _ in true })
  }

  func testRelativeNamesResolveUnderApplicationSupportRecordings() {
    let expected = RecordedPlaybackConfig(
      directory: recordings.appendingPathComponent("room2", isDirectory: true), restart: .session,
      restartGeneration: 2)
    XCTAssertEqual(
      select(["arcorePlaybackDataset": "room2", "arcorePlaybackRestart": "session",
              "arcorePlaybackRestartGeneration": 2]),
      .enabled(expected))
    XCTAssertEqual(
      select(["arcorePlaybackDataset": "recordings/room2", "arcorePlaybackRestart": "session",
              "arcorePlaybackRestartGeneration": 2]),
      .enabled(expected), "the Android-style recordings/ prefix names the same folder")
  }

  func testReleaseBuildsParentSegmentsAndMissingRecordingsAreRefused() {
    XCTAssertEqual(select([:]), .disabled)
    XCTAssertEqual(select(["arcorePlaybackDataset": "  "]), .disabled)
    XCTAssertEqual(select(["arcorePlaybackDataset": "room2"], allows: false),
                   .refused(path: "room2", reason: "release-build"))
    XCTAssertEqual(select(["arcorePlaybackDataset": "../room2"]), .refused(path: "../room2", reason: "parent-segment"))
    let missing = RecordedPlaybackSelector.select(
      creationParams: ["arcorePlaybackDataset": "room9"], allowsPlayback: true, recordingsDirectory: recordings,
      isRecording: { _ in false })
    XCTAssertEqual(missing, .refused(path: recordings.appendingPathComponent("room9").path, reason: "dataset-missing"))
  }

  func testUnknownRestartModesSelectView() {
    XCTAssertEqual(RecordedPlaybackRestart.parse("off"), .off)
    XCTAssertEqual(RecordedPlaybackRestart.parse("loop"), .view)
    XCTAssertEqual(RecordedPlaybackRestart.parse(nil), .view)
  }
}

/// Camera geometry of recorded frames: a world point projected with the
/// view and projection matrices lands where the display transform puts its
/// image pixel, for every interface orientation and a non-matching viewport.
final class RecordedCameraGeometryTests: XCTestCase {
  func testProjectionAgreesWithTheDisplayTransform() {
    let resolution = CGSize(width: 1920, height: 1440)
    let k = simd_float3x3(columns: (simd_float3(1450, 0, 0), simd_float3(0, 1452, 0), simd_float3(971, 686, 1)))
    var camera = simd_float4x4(simd_quatf(angle: 0.4, axis: simd_normalize(simd_float3(0.3, 1, 0.2))))
    camera.columns.3 = simd_float4(0.5, -0.2, 1.3, 1)
    let viewport = CGSize(width: 390, height: 844)
    for orientation: UIInterfaceOrientation in [.portrait, .landscapeRight, .landscapeLeft, .portraitUpsideDown] {
      let view = RecordedCameraGeometry.viewMatrix(camera, orientation: orientation)
      let projection = RecordedCameraGeometry.projectionMatrix(
        intrinsics: k, imageResolution: resolution, orientation: orientation, viewportSize: viewport,
        zNear: 0.01, zFar: 100)
      let display = RecordedCameraGeometry.displayTransform(
        imageResolution: resolution, orientation: orientation, viewportSize: viewport)
      for cameraPoint in [simd_float3(0.1, 0.05, -1), simd_float3(-0.3, 0.2, -2), simd_float3(0, 0, -0.5)] {
        let world = camera * simd_float4(cameraPoint, 1)
        // Image pixel of the point (sensor camera, image v down).
        let u = k[0][0] * cameraPoint.x / -cameraPoint.z + k[2][0]
        let v = k[1][1] * -cameraPoint.y / -cameraPoint.z + k[2][1]
        let viewPoint = CGPoint(x: CGFloat(u) / resolution.width, y: CGFloat(v) / resolution.height).applying(display)
        let clip = projection * view * world
        XCTAssertEqual(Double(clip.x / clip.w), Double(viewPoint.x * 2 - 1), accuracy: 1e-3, "\(orientation.rawValue) x")
        XCTAssertEqual(Double(clip.y / clip.w), Double(1 - viewPoint.y * 2), accuracy: 1e-3, "\(orientation.rawValue) y")
        XCTAssertGreaterThan(clip.z / clip.w, 0)
        XCTAssertLessThan(clip.z / clip.w, 1)
      }
    }
  }

  func testLandscapeRightDisplayTransformIsIdentityWhenAspectsMatch() {
    let transform = RecordedCameraGeometry.displayTransform(
      imageResolution: CGSize(width: 1920, height: 1440), orientation: .landscapeRight,
      viewportSize: CGSize(width: 960, height: 720))
    XCTAssertEqual(transform, .identity)
    let portrait = RecordedCameraGeometry.displayTransform(
      imageResolution: CGSize(width: 1920, height: 1440), orientation: .portrait,
      viewportSize: CGSize(width: 720, height: 960))
    XCTAssertEqual(portrait, CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 1, ty: 0))
  }
}
