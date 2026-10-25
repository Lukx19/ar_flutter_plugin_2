import ARKit
import CoreVideo
import Foundation
import simd
import XCTest
@testable import ar_flutter_plugin_2

/// Fixtures of the burst and quality shot tests.
enum ShotFixture {
  static let shotId = UUID(uuidString: "10111213-1415-1617-1819-1a1b1c1d1e1f")!

  static func jpeg(_ tag: String) -> [UInt8] { [0xff, 0xd8] + Array("shot-\(tag)".utf8) + [0xff, 0xd9] }

  /// ARKit intrinsics of a 1920x1440 video image.
  static let arkitIntrinsics = simd_float3x3(
    simd_float3(1400, 0, 0), simd_float3(0, 1400, 0), simd_float3(960, 720, 1))
  static let arkitResolution = CGSize(width: 1920, height: 1440)

  static func pose(x: Float = 0, y: Float = 0, z: Float = 0) -> simd_float4x4 {
    var m = matrix_identity_float4x4
    m.columns.3 = simd_float4(x, y, z, 1)
    return m
  }

  static func metadata(index: Int, tracked: Bool = true) -> StillExposureMetadata {
    StillExposureMetadata(
      exposureTimestampNs: 9_000_000_000 + Int64(index) * 150_000_000, cameraToWorld: pose(x: 1 + 0.01 * Float(index), y: 2, z: 3),
      intrinsics: simd_float4(2940, 2940, 2016, 1512), width: 4032, height: 3024, sharpness: 100 + Double(index),
      highResolution: true)
  }

  /// A burst of three, the third taken while tracking was limited.
  static func burst(limitedThird: Bool = true) -> CapturedShot {
    CapturedShot(shotId: shotId, mode: .burst, images: (0..<3).map { index in
      ShotImage(index: index, jpeg: jpeg("burst-\(index)"), metadata: metadata(index: index),
                trackingNormal: !(limitedThird && index == 2))
    })
  }

  static func pixelBuffer(width: Int = 64, height: Int = 48, flat: Bool = false) -> CVPixelBuffer {
    var buffer: CVPixelBuffer?
    CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
                        [kCVPixelBufferIOSurfacePropertiesKey as String: [:]] as CFDictionary, &buffer)
    let result = buffer!
    CVPixelBufferLockBaseAddress(result, [])
    defer { CVPixelBufferUnlockBaseAddress(result, []) }
    for plane in 0..<CVPixelBufferGetPlaneCount(result) {
      let stride = CVPixelBufferGetBytesPerRowOfPlane(result, plane)
      let planeHeight = CVPixelBufferGetHeightOfPlane(result, plane)
      let planeWidth = CVPixelBufferGetWidthOfPlane(result, plane)
      let bytes = CVPixelBufferGetBaseAddressOfPlane(result, plane)!.assumingMemoryBound(to: UInt8.self)
      bytes.initialize(repeating: 128, count: stride * planeHeight)
      if plane == 0 && !flat {
        for y in 0..<planeHeight {
          for x in 0..<planeWidth {
            bytes[y * stride + x] = (x / 4 + y / 4) % 2 == 0 ? 235 : 16
          }
        }
      }
    }
    return result
  }

  static func frame(timestamp: TimeInterval, pose: simd_float4x4, tracking: ARCamera.TrackingState = .normal,
                    flat: Bool = false) -> CaptureFrame {
    RecordedCaptureFrame(
      timestamp: timestamp, recordedTimestamp: timestamp, cameraTransform: pose, intrinsics: arkitIntrinsics,
      imageResolution: arkitResolution, trackingState: tracking, capturedImage: pixelBuffer(flat: flat),
      depth: nil, featurePoints: nil, lightEstimate: nil, anchors: [])
  }
}

/// Frame source fake: answers each still request with the next queued frame
/// (or the last one again) and records the requested quality. It has no
/// pause: shots never stop the session.
final class StillFrameSource: ARFrameSource {
  var anchorHost: CaptureAnchorHost { anchors }
  var currentFrame: CaptureFrame?
  var runConfiguration: CaptureRunConfiguration? { CaptureRunConfiguration(depth: .none) }
  var lifecycleState: ARSessionLifecycleState { .running }
  var deliversSceneDepth: Bool { false }
  var depthOutputSize: ARDepthOutputSize { .standard }
  var epoch: Int { 1 }
  var replaysRecordedStills = false
  var frames: [CaptureFrame] = []
  private(set) var qualities: [StillFrameQuality] = []
  private(set) var requestTimes: [TimeInterval] = []
  private let anchors = RecordedAnchorStore()
  private let lock = NSLock()

  func addFrameObserver(_ observer: ARFrameObserver) {}
  func removeFrameObserver(_ observer: ARFrameObserver) {}
  func getCurrentWorldMap(completion: @escaping (ARWorldMap?, Error?) -> Void) { completion(nil, nil) }

  func captureHighResolutionFrame(completion: @escaping (CaptureFrame?, Error?) -> Void) {
    captureHighResolutionFrame(quality: .fast, completion: completion)
  }

  func captureHighResolutionFrameForProfile(quality: StillFrameQuality, completion: @escaping (CaptureFrame?, Error?) -> Void) {
    captureHighResolutionFrame(quality: quality, completion: completion)
  }

  func captureHighResolutionFrame(quality: StillFrameQuality, completion: @escaping (CaptureFrame?, Error?) -> Void) {
    lock.lock()
    qualities.append(quality)
    requestTimes.append(ProcessInfo.processInfo.systemUptime)
    let frame = frames.count > 1 ? frames.removeFirst() : frames.first
    lock.unlock()
    DispatchQueue.global().async { completion(frame, frame == nil ? RecordedSessionError.noStill : nil) }
  }
}

