import ARKit
import ImageIO
import UIKit
import XCTest

@testable import ar_flutter_plugin_2

/// Real exposure owner + serialized physical request + encoded EXIF. The
/// fixture adds actual frame metadata rather than reading restored controls.
private struct MeteredStillFrame: CaptureFrame, StillExposureFrameMetadata {
  let base: CaptureFrame
  var seconds: Double = 0.03
  var iso: Double = 200
  var stillExifData: [String: Any] { ["ExposureTime": seconds, "ISOSpeedRatings": [iso]] }
  var stillExposureDurationSeconds: Double? { seconds }
  var timestamp: TimeInterval { base.timestamp }
  var cameraTransform: simd_float4x4 { base.cameraTransform }
  var intrinsics: simd_float3x3 { base.intrinsics }
  var imageResolution: CGSize { base.imageResolution }
  var trackingState: ARCamera.TrackingState { base.trackingState }
  var capturedImage: CVPixelBuffer { base.capturedImage }
  var featurePoints: CaptureFeaturePoints? { base.featurePoints }
  var lightEstimate: CaptureLightEstimate? { base.lightEstimate }
  var anchors: [ARAnchor] { base.anchors }
  var worldMappingStatus: ARFrame.WorldMappingStatus { base.worldMappingStatus }
  func depthData(_ kind: CaptureDepthKind) -> CaptureDepthData? { base.depthData(kind) }
  func displayTransform(for orientation: UIInterfaceOrientation, viewportSize: CGSize)
    -> CGAffineTransform
  {
    base.displayTransform(for: orientation, viewportSize: viewportSize)
  }
  func viewMatrix(for orientation: UIInterfaceOrientation) -> simd_float4x4 {
    base.viewMatrix(for: orientation)
  }
  func projectionMatrix(
    for orientation: UIInterfaceOrientation, viewportSize: CGSize, zNear: CGFloat, zFar: CGFloat
  ) -> simd_float4x4 {
    base.projectionMatrix(for: orientation, viewportSize: viewportSize, zNear: zNear, zFar: zFar)
  }
}

final class AdaptiveStillCaptureTests: XCTestCase {
  private func device() -> FakeCameraControlDevice {
    let device = FakeCameraControlDevice()
    device.iso = 200
    device.exposureSeconds = 0.03
    device.adaptiveStillCapabilities = AdaptiveStillExposureCapabilities(
      minISO: device.minISO, maxISO: device.maxISO,
      minExposureSeconds: device.minExposureSeconds, maxExposureSeconds: device.maxExposureSeconds,
      fixedAperture: true, videoHDREnabled: false, flickerDetected: false)
    return device
  }

  private func frame(seconds: Double = 0.03, iso: Double = 200) -> MeteredStillFrame {
    MeteredStillFrame(
      base: ShotFixture.frame(
        timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose()),
      seconds: seconds, iso: iso)
  }

  func testBothLanesPrepareBalancedSingleAndRestoreBeforeEncodingActualExif() throws {
    for lane in [CaptureLane.manual, .automatic] {
      let device = device()
      let source = StillFrameSource()
      source.currentFrame = frame()
      let access = StillCameraAccess()
      defer { access.close {} }
      let bridge = CameraControlBridge(device: device)
      let exposure = HighResolutionStillExposure(
        frameSource: { source }, jpegQuality: 95,
        cameraAccess: access, controlBridge: bridge)
      exposure.mode = .inArQuality
      source.interceptStill = { callback in
        XCTAssertEqual(device.exposureMode, .custom)
        XCTAssertEqual(device.exposureSeconds, 0.01, accuracy: 1e-9)
        XCTAssertEqual(device.iso, 600, accuracy: 0.01)
        callback(self.frame(seconds: 0.012, iso: 500), nil)
      }
      let done = expectation(description: "balanced \(lane)")
      let qualifier = CaptureAttemptQualifier(
        identity: CaptureFixture.request().accepted.identity, exposureGeneration: 1)
      XCTAssertTrue(
        exposure.requestExposure(qualifier, required: [.jpeg], lane: lane) { result in
          XCTAssertEqual(device.exposureMode, .auto)
          guard case .components(let set) = result, let bytes = set.components.first?.1,
            let image = CGImageSourceCreateWithData(Data(bytes) as CFData, nil),
            let properties = CGImageSourceCopyPropertiesAtIndex(image, 0, nil) as? [String: Any],
            let exif = properties[kCGImagePropertyExifDictionary as String] as? [String: Any]
          else {
            XCTFail("expected actual encoded still")
            done.fulfill()
            return
          }
          XCTAssertEqual(
            (exif[kCGImagePropertyExifExposureTime as String] as? NSNumber)?.doubleValue ?? 0,
            0.012, accuracy: 1e-6)
          XCTAssertEqual(
            (exif[kCGImagePropertyExifISOSpeedRatings as String] as? [NSNumber])?.first?
              .doubleValue, 500)
          XCTAssertFalse(
            (exif[kCGImagePropertyExifUserComment as String] as? String)?.contains("adaptiveTarget")
              == true,
            "adaptive targets stay in the bounded diagnostic cache, not persisted photo comments")
          done.fulfill()
        })
      wait(for: [done], timeout: 5)
      XCTAssertEqual(source.qualities, [.balanced])
      XCTAssertEqual(device.adaptiveSetters, 1)
      XCTAssertEqual(source.pauseCalls, 0)
    }
  }

  func testMissingFrameMetadataReplayAndBurstKeepOrdinaryExposure() {
    for fallback in 0..<3 {
      let device = device()
      let source = StillFrameSource()
      source.currentFrame =
        fallback == 0
        ? ShotFixture.frame(
          timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose()) : frame()
      source.replaysRecordedStills = fallback == 1
      let access = StillCameraAccess()
      defer { access.close {} }
      let exposure = HighResolutionStillExposure(
        frameSource: { source }, jpegQuality: 95,
        cameraAccess: access, controlBridge: CameraControlBridge(device: device))
      if fallback == 2 {
        exposure.mode = .burst
        exposure.burst = BurstSettings(imageCount: 2, intervalMs: 0)
      }
      source.interceptStill = { callback in
        XCTAssertEqual(device.exposureMode, .auto)
        callback(self.frame(), nil)
      }
      let done = expectation(description: "fallback \(fallback)")
      let qualifier = CaptureAttemptQualifier(
        identity: CaptureFixture.request().accepted.identity, exposureGeneration: 1)
      XCTAssertTrue(exposure.requestExposure(qualifier, required: [.jpeg]) { _ in done.fulfill() })
      wait(for: [done], timeout: 5)
      XCTAssertEqual(device.adaptiveSetters, 0)
    }
  }

  func testUnacknowledgedPreparationFallsBackWithoutStallingPhysicalRequest() {
    let device = device()
    device.delayAdaptiveAcknowledgement = true
    let source = StillFrameSource()
    source.currentFrame = frame()
    let access = StillCameraAccess()
    defer { access.close {} }
    let exposure = HighResolutionStillExposure(
      frameSource: { source }, jpegQuality: 95,
      cameraAccess: access, controlBridge: CameraControlBridge(device: device))
    let captured = expectation(description: "ordinary capture after 100ms watchdog")
    let done = expectation(description: "encoded")
    source.interceptStill = { callback in
      XCTAssertEqual(device.exposureMode, .auto)
      callback(self.frame(), nil)
      captured.fulfill()
    }
    let started = ProcessInfo.processInfo.systemUptime
    XCTAssertTrue(exposure.captureStill { _ in done.fulfill() })
    wait(for: [captured, done], timeout: 3)
    XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - started, 2)
    access.perform { device.adaptiveAcknowledgements.forEach { $0() } }
  }

  func testRejectedBlurRecordsExistingAssessmentAndActualFrameExposure() throws {
    let device = device()
    let source = StillFrameSource()
    source.currentFrame = frame()
    let access = StillCameraAccess()
    defer { access.close {} }
    let recorded = expectation(description: "rejected assessment persisted")
    let diagnostic = StillExposureAssessments(url: URL(fileURLWithPath: "/unused-diagnostic-test"))
    { bytes, _, completed in
      defer {
        completed()
        recorded.fulfill()
      }
      let root = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any]
      let row = (root?["assessments"] as? [[String: Any]])?.last
      XCTAssertEqual(row?["assessment"] as? String, "REJECTED_BLUR")
      XCTAssertEqual(row?["sharpness"] as? Double, 0)
      XCTAssertEqual(row?["minimumSharpness"] as? Double, 110)
      XCTAssertEqual(row?["iso"] as? Double, 500)
      XCTAssertEqual(row?["targetISO"] as? Double, 600)
      XCTAssertEqual(row?["mode"] as? String, "single")
    }
    let exposure = HighResolutionStillExposure(
      frameSource: { source }, jpegQuality: 95,
      cameraAccess: access, controlBridge: CameraControlBridge(device: device),
      assessments: diagnostic)
    source.interceptStill = { callback in
      let flat = MeteredStillFrame(
        base: ShotFixture.frame(
          timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose(), flat: true),
        seconds: 0.012, iso: 500)
      callback(flat, nil)
    }
    let rejected = expectation(description: "unchanged blur rule rejects flat still")
    let qualifier = CaptureAttemptQualifier(
      identity: CaptureFixture.request().accepted.identity, exposureGeneration: 1)
    XCTAssertTrue(
      exposure.requestExposure(qualifier, required: [.jpeg]) { result in
        if case .failure(_, let reason) = result {
          XCTAssertEqual(reason, "REJECTED_BLUR")
        } else {
          XCTFail("flat image must remain rejected")
        }
        rejected.fulfill()
      })
    wait(for: [recorded, rejected], timeout: 5)
  }

  func testPausedLiveSourceNeverAppliesControlsOrRequestsPhysicalFrame() {
    let device = device()
    let source = StillFrameSource()
    source.currentFrame = frame()
    source.lifecycleState = .paused
    let access = StillCameraAccess()
    defer { access.close {} }
    let exposure = HighResolutionStillExposure(
      frameSource: { source }, jpegQuality: 95,
      cameraAccess: access, controlBridge: CameraControlBridge(device: device))
    let rejected = expectation(description: "paused live capture rejected")
    XCTAssertTrue(
      exposure.captureStill { result in
        if case .failure(let failure) = result {
          XCTAssertEqual(failure.reason, "camera-stopped")
        } else {
          XCTFail("paused live owner must not capture")
        }
        rejected.fulfill()
      })
    wait(for: [rejected], timeout: 2)
    XCTAssertEqual(source.qualities.count, 0)
    XCTAssertEqual(device.adaptiveSetters, 0)
  }

  func testClosingDuringApplyAcknowledgementCannotStartLatePhysicalCapture() {
    let device = device()
    device.delayAdaptiveAcknowledgement = true
    let source = StillFrameSource()
    source.currentFrame = frame()
    let access = StillCameraAccess()
    let exposure = HighResolutionStillExposure(
      frameSource: { source }, jpegQuality: 95,
      cameraAccess: access, controlBridge: CameraControlBridge(device: device))
    let awaiting = expectation(description: "setter awaiting callback")
    let closed = expectation(description: "logical request closed")
    let drained = expectation(description: "late callback drained")
    XCTAssertTrue(
      exposure.captureStill { result in
        if case .failure(let failure) = result {
          XCTAssertEqual(failure.reason, "closed")
        } else {
          XCTFail("closed request must fail")
        }
        closed.fulfill()
      })
    access.perform {
      XCTAssertEqual(device.adaptiveSetters, 1)
      awaiting.fulfill()
    }
    wait(for: [awaiting], timeout: 2)
    access.close {}
    access.perform {
      device.adaptiveAcknowledgements.forEach { $0() }
      access.perform { drained.fulfill() }
    }
    wait(for: [closed, drained], timeout: 2)
    XCTAssertEqual(source.qualities.count, 0, "late ACK cannot launch a frame for a closed owner")
    XCTAssertEqual(device.exposureMode, .auto)
  }
}
