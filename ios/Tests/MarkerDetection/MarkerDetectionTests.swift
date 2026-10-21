import ARKit
import CoreVideo
import Foundation
import ImageIO
import simd
import XCTest

@testable import ar_flutter_plugin_2

/// Marker detection on iOS: the C library compiled with its AprilTag backend
/// (as in the pod), the Swift wrapper, the kind 5 and kind 8 messages, the
/// live frame tap and photo tie points, against the committed golden images
/// of `test_assets/marker_detection` (rendered from the printed sheets with
/// known corners; `detected` holds the host build's records).
final class MarkerDetectionTests: XCTestCase {
  // MARK: fixtures

  static let pluginRoot: URL = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath()
    .deletingLastPathComponent()  // MarkerDetection
    .deletingLastPathComponent()  // Tests
    .deletingLastPathComponent()  // ios
    .deletingLastPathComponent()  // plugin root

  /// A test asset: from the test bundle (RunnerTests copies the
  /// `marker_detection` and `visibility_grid` folders; a device cannot read the
  /// source tree), else from the source tree (host package runs).
  static func asset(_ path: String) -> URL {
    let parts = path.split(separator: "/", maxSplits: 1).map(String.init)
    if parts.count == 2 {
      for bundle in Bundle.allBundles + [Bundle.main] {
        if let url = bundle.url(forResource: parts[1], withExtension: nil, subdirectory: parts[0]) { return url }
      }
    }
    return pluginRoot.appendingPathComponent("test_assets/\(path)")
  }

  struct GoldenMarker {
    let id: Int
    let truth: [Float]
    let detected: [Float]
    let tvec: [Float]
  }

  struct GoldenImage {
    let file: String
    let markerSize: Double
    let markers: [GoldenMarker]
  }

  struct Goldens {
    let fx: Double, fy: Double, cx: Double, cy: Double
    let images: [GoldenImage]
  }

  static func goldens() throws -> Goldens {
    let data = try Data(contentsOf: asset("marker_detection/marker_goldens.json"))
    let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    let camera = try XCTUnwrap(json["camera"] as? [String: Double])
    let images = try XCTUnwrap(json["images"] as? [[String: Any]]).map { image -> GoldenImage in
      let markers = (image["markers"] as? [[String: Any]] ?? []).map { m in
        GoldenMarker(
          id: m["id"] as? Int ?? -1, truth: (m["truth"] as? [Double] ?? []).map(Float.init),
          detected: (m["detected"] as? [Double] ?? []).map(Float.init), tvec: (m["tvec"] as? [Double] ?? []).map(Float.init))
      }
      return GoldenImage(
        file: image["file"] as? String ?? "", markerSize: image["markerSizeMetres"] as? Double ?? 0, markers: markers)
    }
    return Goldens(fx: camera["fx"]!, fy: camera["fy"]!, cx: camera["cx"]!, cy: camera["cy"]!, images: images)
  }

  /// 8-bit grey pixels of a grey PNG, exactly as stored.
  static func greyImage(_ file: String) throws -> (pixels: [UInt8], width: Int, height: Int) {
    let url = asset("marker_detection/\(file)")
    let source = try XCTUnwrap(CGImageSourceCreateWithURL(url as CFURL, nil))
    let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
    XCTAssertEqual(image.bitsPerPixel, 8)
    let data = try XCTUnwrap(image.dataProvider?.data) as Data
    var pixels = [UInt8](repeating: 0, count: image.width * image.height)
    data.withUnsafeBytes { raw in
      for y in 0..<image.height {
        for x in 0..<image.width { pixels[y * image.width + x] = raw[y * image.bytesPerRow + x] }
      }
    }
    return (pixels, image.width, image.height)
  }

  /// Bi-planar full-range YCbCr buffer whose Y plane is [grey] (chroma 128).
  static func pixelBuffer(_ grey: [UInt8], width: Int, height: Int) throws -> CVPixelBuffer {
    var buffer: CVPixelBuffer?
    let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
    XCTAssertEqual(
      CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange, attributes, &buffer),
      kCVReturnSuccess)
    let b = try XCTUnwrap(buffer)
    CVPixelBufferLockBaseAddress(b, [])
    let y = CVPixelBufferGetBaseAddressOfPlane(b, 0)!.assumingMemoryBound(to: UInt8.self)
    let yStride = CVPixelBufferGetBytesPerRowOfPlane(b, 0)
    for row in 0..<height {
      for col in 0..<width { y[row * yStride + col] = grey[row * width + col] }
    }
    let c = CVPixelBufferGetBaseAddressOfPlane(b, 1)!
    memset(c, 128, CVPixelBufferGetBytesPerRowOfPlane(b, 1) * CVPixelBufferGetHeightOfPlane(b, 1))
    CVPixelBufferUnlockBaseAddress(b, [])
    return b
  }

  final class CapturingSink: IngestMessageSink {
    var messages: [Data] = []
    func offer(_ message: IngestMessageBuffer, length: Int) -> Bool {
      messages.append(message.data(length))
      return true
    }
    func countSourceDrop(kind: Int) {}
  }

  struct FakeFrame: CaptureFrame {
    var timestamp: TimeInterval
    var cameraTransform: simd_float4x4
    var intrinsics: simd_float3x3
    var imageResolution: CGSize
    var trackingState: ARCamera.TrackingState = .normal
    var capturedImage: CVPixelBuffer
    func displayTransform(for orientation: UIInterfaceOrientation, viewportSize: CGSize) -> CGAffineTransform {
      .identity
    }
    func viewMatrix(for orientation: UIInterfaceOrientation) -> simd_float4x4 { matrix_identity_float4x4 }
    func projectionMatrix(
      for orientation: UIInterfaceOrientation, viewportSize: CGSize, zNear: CGFloat, zFar: CGFloat
    ) -> simd_float4x4 { matrix_identity_float4x4 }
    func depthData(_ kind: CaptureDepthKind) -> CaptureDepthData? { nil }
    var featurePoints: CaptureFeaturePoints? { nil }
    var lightEstimate: CaptureLightEstimate? { nil }
    var anchors: [ARAnchor] { [] }
    var worldMappingStatus: ARFrame.WorldMappingStatus { .notAvailable }
  }

  final class FakeSource: ARFrameSource {
    final class Anchors: CaptureAnchorHost {
      func add(anchor: ARAnchor) {}
      func remove(anchor: ARAnchor) {}
    }
    let anchorHost: CaptureAnchorHost = Anchors()
    var currentFrame: CaptureFrame? { nil }
    var runConfiguration: CaptureRunConfiguration? { nil }
    var lifecycleState: ARSessionLifecycleState { .running }
    var deliversSceneDepth: Bool { false }
    var depthOutputSize: ARDepthOutputSize { ARDepthOutputSize(width: 256, height: 192) }
    var epoch: Int { 0 }
    func addFrameObserver(_ observer: ARFrameObserver) {}
    func removeFrameObserver(_ observer: ARFrameObserver) {}
    func captureHighResolutionFrame(completion: @escaping (CaptureFrame?, Error?) -> Void) { completion(nil, nil) }
    func getCurrentWorldMap(completion: @escaping (ARWorldMap?, Error?) -> Void) { completion(nil, nil) }
  }

  static func records(_ message: Data) -> [(id: Int, corners: [Float])] {
    message.withUnsafeBytes { raw in
      let base = MarkerDetectionMessageWriter.recordsOffset
      let count = Int(raw.loadUnaligned(fromByteOffset: IngestMessageLayout.headerBytes, as: UInt16.self))
      return (0..<count).map { i in
        let o = base + 64 * i
        let id = Int(raw.loadUnaligned(fromByteOffset: o, as: UInt16.self))
        let corners = (0..<8).map { raw.loadUnaligned(fromByteOffset: o + 4 + 4 * $0, as: Float.self) }
        return (id, corners)
      }
    }
  }

  // MARK: tests

  func testLibraryLinksTheAprilTagBackend() {
    XCTAssertTrue(NativeMarkerDetector.available)
    XCTAssertEqual(NativeMarkerDetector.abiVersion, 1)
    XCTAssertNotNil(NativeMarkerDetector())
    XCTAssertNotNil(NativeMarkerDetector(dictionary: 1), "AprilTag 36h11")
    XCTAssertNil(NativeMarkerDetector(dictionary: 2), "4X4_50 is not decoded by AprilTag")
    XCTAssertNil(NativeMarkerDetector(dictionary: 3), "6X6_250 is not decoded by AprilTag")
  }

  func testGoldenImagesDecodeToTheHostRecords() throws {
    let goldens = try Self.goldens()
    XCTAssertEqual(goldens.images.count, 2)
    let detector = try XCTUnwrap(NativeMarkerDetector())
    detector.setCamera(fx: goldens.fx, fy: goldens.fy, cx: goldens.cx, cy: goldens.cy)
    for image in goldens.images {
      let (pixels, width, height) = try Self.greyImage(image.file)
      detector.setMarkerSize(image.markerSize)
      let count = pixels.withUnsafeBufferPointer {
        detector.detect($0.baseAddress!, width: width, height: height, stride: width)
      }
      XCTAssertEqual(Int(count), image.markers.count, image.file)
      XCTAssertEqual(Int(detector.estimatePose()), image.markers.count, image.file)
      let base = detector.records
      for (i, golden) in image.markers.enumerated() where i < Int(count) {
        let id = Int(base.loadUnaligned(fromByteOffset: 64 * i, as: UInt16.self))
        XCTAssertEqual(id, golden.id, image.file)
        for k in 0..<8 {
          let c = base.loadUnaligned(fromByteOffset: 64 * i + 4 + 4 * k, as: Float.self)
          XCTAssertEqual(c, golden.detected[k], accuracy: 2e-3, "\(image.file) id \(id) corner value \(k)")
          XCTAssertEqual(c, golden.truth[k], accuracy: 0.5, "\(image.file) id \(id) against the rendered corner")
        }
        for k in 0..<3 {
          let t = base.loadUnaligned(fromByteOffset: 64 * i + 48 + 4 * k, as: Float.self)
          XCTAssertEqual(t, golden.tvec[k], accuracy: 2e-4, "\(image.file) id \(id) tvec")
        }
      }
    }
  }

  func testDetectionMessageEqualsTheContractGolden() throws {
    let golden = try Data(contentsOf: Self.asset("visibility_grid/ingest_markers.bin"))
    let header = golden.withUnsafeBytes { raw -> MarkerDetectionHeader in
      func f(_ o: Int) -> Float { raw.loadUnaligned(fromByteOffset: o, as: Float.self) }
      var m = simd_float4x4()
      // The golden stores T_world_cam (contract version 2); the header takes
      // the ARKit camera transform, so camera Y and Z change back.
      for c in 0..<4 {
        for r in 0..<4 { m[c][r] = (c == 1 || c == 2 ? -1 : 1) * f(IngestMessageLayout.cameraToWorld + 4 * (c * 4 + r)) }
      }
      return MarkerDetectionHeader(
        sampleNs: raw.loadUnaligned(fromByteOffset: IngestMessageLayout.sampleTimestampNs, as: Int64.self),
        acquireNs: raw.loadUnaligned(fromByteOffset: IngestMessageLayout.acquireTimeNs, as: Int64.self),
        trackingState: Int(raw[IngestMessageLayout.trackingState]), poseMatch: Int(raw[IngestMessageLayout.poseMatch]),
        groupEpoch: Int(raw.loadUnaligned(fromByteOffset: IngestMessageLayout.groupEpoch, as: UInt16.self)),
        cameraToWorld: m,
        intrinsics: simd_float4(f(120), f(124), f(128), f(132)),
        imageWidth: Int(raw.loadUnaligned(fromByteOffset: IngestMessageLayout.width, as: UInt16.self)),
        imageHeight: Int(raw.loadUnaligned(fromByteOffset: IngestMessageLayout.height, as: UInt16.self)),
        dropped: raw.loadUnaligned(fromByteOffset: IngestMessageLayout.dropped, as: UInt32.self),
        frameNs: raw.loadUnaligned(fromByteOffset: IngestMessageLayout.frameTimestampNs, as: Int64.self),
        poseGapUs: raw.loadUnaligned(fromByteOffset: IngestMessageLayout.poseGapUs, as: Int32.self))
    }
    let count = golden.withUnsafeBytes {
      Int($0.loadUnaligned(fromByteOffset: IngestMessageLayout.headerBytes, as: UInt16.self))
    }
    let writer = MarkerDetectionMessageWriter()
    let length = golden.withUnsafeBytes {
      writer.write(header, records: $0.baseAddress! + MarkerDetectionMessageWriter.recordsOffset, count: count)
    }
    XCTAssertEqual(length, golden.count)
    var written = writer.buffer.data(length)
    // Sequence and send time are stamped by the ingest queue.
    written.replaceSubrange(8..<12, with: golden[8..<12])
    written.replaceSubrange(40..<48, with: golden[40..<48])
    XCTAssertEqual(written, golden)
  }

  func testFrameTapOffersDetectionsAtTheControlRate() throws {
    let goldens = try Self.goldens()
    let image = goldens.images[1]
    let (pixels, width, height) = try Self.greyImage(image.file)
    let buffer = try Self.pixelBuffer(pixels, width: width, height: height)
    let sink = CapturingSink()
    var settings: MarkerDetectionSettings? = MarkerDetectionSettings(
      enabled: true, hz: 5, markerSizeMetres: Float(image.markerSize), groupEpoch: 3)
    let queue = DispatchQueue(label: "test.markers")
    let tap = MarkerFrameTap(
      makeDetector: { NativeMarkerDetector() }, settings: { settings }, sink: { sink },
      clock: FakeIngestClock(42), queue: queue)
    let k = simd_float3x3(
      simd_float3(Float(goldens.fx), 0, 0), simd_float3(0, Float(goldens.fy), 0),
      simd_float3(Float(goldens.cx), Float(goldens.cy), 1))
    var pose = matrix_identity_float4x4
    pose.columns.3 = simd_float4(0.1, 0.2, 0.3, 1)
    let source = FakeSource()
    func frame(_ t: TimeInterval) -> FakeFrame {
      FakeFrame(timestamp: t, cameraTransform: pose, intrinsics: k,
                imageResolution: CGSize(width: width, height: height), capturedImage: buffer)
    }
    tap.frameSource(source, didUpdate: frame(10.0))
    queue.sync {}
    tap.frameSource(source, didUpdate: frame(10.1))  // within 1/5 s
    queue.sync {}
    tap.frameSource(source, didUpdate: frame(10.25))
    queue.sync {}
    settings?.enabled = false
    tap.frameSource(source, didUpdate: frame(11.0))
    queue.sync {}
    XCTAssertEqual(sink.messages.count, 2)
    XCTAssertEqual(tap.snapshot.images, 2)
    for message in sink.messages {
      message.withUnsafeBytes { raw in
        XCTAssertEqual(Int(raw[IngestMessageLayout.kind]), IngestMessageLayout.kindMarkerDetections)
        XCTAssertEqual(raw.loadUnaligned(fromByteOffset: IngestMessageLayout.groupEpoch, as: UInt16.self), 3)
        XCTAssertEqual(Int(raw[IngestMessageLayout.poseMatch]), IngestMessageLayout.poseMatchExact)
        XCTAssertEqual(raw.loadUnaligned(fromByteOffset: IngestMessageLayout.acquireTimeNs, as: Int64.self), 42)
        XCTAssertEqual(raw.loadUnaligned(fromByteOffset: IngestMessageLayout.intrinsics, as: Float.self), Float(goldens.fx))
        XCTAssertEqual(raw.loadUnaligned(fromByteOffset: IngestMessageLayout.cameraToWorld + 48, as: Float.self), 0.1)
        XCTAssertEqual(Int(raw.loadUnaligned(fromByteOffset: IngestMessageLayout.width, as: UInt16.self)), width)
      }
      let records = Self.records(message)
      XCTAssertEqual(records.map(\.id), image.markers.map(\.id))
      for (record, golden) in zip(records, image.markers) {
        for k in 0..<8 { XCTAssertEqual(record.corners[k], golden.detected[k], accuracy: 2e-3) }
      }
    }
    let first = sink.messages[0].withUnsafeBytes {
      $0.loadUnaligned(fromByteOffset: IngestMessageLayout.sampleTimestampNs, as: Int64.self)
    }
    XCTAssertEqual(first, 10_000_000_000)
  }

  func testPhotoTiePointsTravelAsKindEight() throws {
    let goldens = try Self.goldens()
    let image = goldens.images[0]
    let sink = CapturingSink()
    let queue = DispatchQueue(label: "test.tiepoints")
    let producer = MarkerTiePointProducer(
      makeDetector: { NativeMarkerDetector() },
      settings: { MarkerDetectionSettings(enabled: true, hz: 0, markerSizeMetres: 0.1, groupEpoch: 7) },
      sink: { sink }, clock: FakeIngestClock(5), queue: queue)
    let record = DurablePhotoRecord(
      captureId: String(repeating: "ab", count: 32), attemptId: UUID().uuidString, exposureTimestampNs: 123_456_789,
      cameraToWorld: matrix_identity_float4x4,
      intrinsics: simd_float4(Float(goldens.fx), Float(goldens.fy), Float(goldens.cx), Float(goldens.cy)),
      width: 1280, height: 960, sharpness: nil, automatic: false, blurRejected: false)
    let event = IngestMessageBuffer(capacity: PhotoSavedLayout.bytes)
    PhotoSavedEmitter.fillEvent(event, record: record, groupEpoch: 7)
    let eventBytes = [UInt8](event.data(PhotoSavedLayout.bytes))
    // The durable record names its committed image; a record without one sends an empty batch.
    var named = record
    named.imageURL = Self.asset("marker_detection/\(image.file)")
    producer.onPhotoDurable(event: eventBytes, record: named)
    producer.onPhotoDurable(event: eventBytes, record: record)
    queue.sync {}
    XCTAssertEqual(sink.messages.count, 2)
    let o = IngestMessageLayout.headerBytes
    sink.messages[0].withUnsafeBytes { raw in
      XCTAssertEqual(Int(raw[IngestMessageLayout.kind]), IngestMessageLayout.kindPhotoTiePoints)
      XCTAssertEqual(raw.loadUnaligned(fromByteOffset: IngestMessageLayout.sampleTimestampNs, as: Int64.self), 123_456_789)
      XCTAssertEqual(Int(raw[IngestMessageLayout.poseMatch]), IngestMessageLayout.poseMatchMissing)
      XCTAssertEqual(raw.loadUnaligned(fromByteOffset: o, as: UInt32.self), TiePointBatchLayout.magic)
      let count = Int(raw.loadUnaligned(fromByteOffset: o + TiePointBatchLayout.count, as: UInt16.self))
      XCTAssertEqual(count, image.markers.count)
      XCTAssertEqual(raw[o + TiePointBatchLayout.photoId], 0xab)
      XCTAssertEqual(raw.loadUnaligned(fromByteOffset: o + TiePointBatchLayout.width, as: UInt16.self), 1280)
      XCTAssertEqual(raw.loadUnaligned(fromByteOffset: o + TiePointBatchLayout.groupEpoch, as: UInt16.self), 7)
      XCTAssertEqual(raw.loadUnaligned(fromByteOffset: o + TiePointBatchLayout.status, as: UInt16.self), 0)
      for (i, golden) in image.markers.enumerated() where i < count {
        let r = o + TiePointBatchLayout.headerBytes + 64 * i
        XCTAssertEqual(Int(raw.loadUnaligned(fromByteOffset: r, as: UInt16.self)), golden.id)
        for k in 0..<8 {
          XCTAssertEqual(raw.loadUnaligned(fromByteOffset: r + 4 + 4 * k, as: Float.self), golden.truth[k], accuracy: 0.3)
        }
      }
    }
    sink.messages[1].withUnsafeBytes { raw in
      XCTAssertEqual(raw.loadUnaligned(fromByteOffset: o + TiePointBatchLayout.count, as: UInt16.self), 0)
      XCTAssertEqual(raw.loadUnaligned(fromByteOffset: o + TiePointBatchLayout.status, as: UInt16.self), 1)
    }
    XCTAssertEqual(producer.photos, 2)
    XCTAssertEqual(producer.failures, 1)
  }

  func testControlAcceptsMarkerDetectionWithTheLinkedDetector() throws {
    final class Target: GridShellControlTarget {
      var enabled: Bool?
      func start(_ config: IngestShellConfig) {}
      func stop() {}
      func setMarkerDetection(enabled: Bool, hz: Int, sizeMetres: Float) { self.enabled = enabled }
      func setRecording(_ enabled: Bool) -> Bool { true }
    }
    let target = Target()
    let handler = GridShellControlHandler(shell: { target }, markerDetectorAvailable: { NativeMarkerDetector.available })
    var message = [UInt8](repeating: 0, count: ShellControlLayout.bytes)
    let magic = ShellControlLayout.magic
    for i in 0..<4 { message[i] = UInt8(truncatingIfNeeded: magic >> (8 * UInt32(i))) }
    message[4] = UInt8(ShellControlLayout.version)
    message[ShellControlLayout.kind] = UInt8(ShellControlLayout.kindSetMarkerDetection)
    message[ShellControlLayout.flags] = UInt8(ShellControlLayout.flagMarkerDetection)
    XCTAssertEqual(handler.handle(Data(message)), .ok)
    XCTAssertEqual(target.enabled, true)
  }
}
