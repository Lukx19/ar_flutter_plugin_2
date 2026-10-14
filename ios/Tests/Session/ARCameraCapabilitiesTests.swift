import ARKit
import XCTest
@testable import ar_flutter_plugin_2

/// ARKit capability mapping, session configuration decisions and the camera
/// background quad placement.
final class ARCameraCapabilitiesTests: XCTestCase {
  static let highResolutionFormat = ARVideoFormatDescription(
    width: 1920, height: 1440, framesPerSecond: 60, captureDeviceType: "AVCaptureDeviceTypeBuiltInWideAngleCamera",
    isRecommendedForHighResolutionFrameCapturing: true, isVideoHDRSupported: false)

  static func capabilities(lidar: Bool, highResolution: Bool) -> ARCameraCapabilities {
    ARCameraCapabilities(
      deviceModel: "iPhone13,3",
      worldTrackingSupported: true,
      sceneDepthSupported: lidar,
      smoothedSceneDepthSupported: lidar,
      sceneReconstructionSupported: lidar,
      videoFormats: [highResolutionFormat],
      highResolutionFrameCaptureFormat: highResolution ? highResolutionFormat : nil)
  }

  func testChannelMapReportsLiDARFormatsAndHighResolutionCapture() {
    let map = ARCameraCapabilitiesTests.capabilities(lidar: true, highResolution: true).channelMap
    XCTAssertEqual(map["platform"] as? String, "ios")
    XCTAssertEqual(map["deviceModel"] as? String, "iPhone13,3")
    XCTAssertEqual(map["worldTracking"] as? Bool, true)
    XCTAssertEqual(map["sceneDepth"] as? Bool, true)
    XCTAssertEqual(map["smoothedSceneDepth"] as? Bool, true)
    XCTAssertEqual(map["lidarDepth"] as? Bool, true)
    XCTAssertEqual(map["highResolutionFrameCapture"] as? Bool, true)
    XCTAssertEqual(map["worldMap"] as? Bool, true)
    let formats = map["videoFormats"] as? [[String: Any]]
    XCTAssertEqual(formats?.count, 1)
    XCTAssertEqual(formats?.first?["width"] as? Int, 1920)
    XCTAssertEqual(formats?.first?["height"] as? Int, 1440)
    XCTAssertEqual(formats?.first?["framesPerSecond"] as? Int, 60)
    XCTAssertEqual(formats?.first?["recommendedForHighResolutionFrameCapturing"] as? Bool, true)
    let highResolution = map["highResolutionFrameCaptureFormat"] as? [String: Any]
    XCTAssertEqual(highResolution?["width"] as? Int, 1920)
  }

  func testChannelMapWithoutLiDARAndHighResolutionCapture() {
    let map = ARCameraCapabilitiesTests.capabilities(lidar: false, highResolution: false).channelMap
    XCTAssertEqual(map["sceneDepth"] as? Bool, false)
    XCTAssertEqual(map["lidarDepth"] as? Bool, false)
    XCTAssertEqual(map["highResolutionFrameCapture"] as? Bool, false)
    XCTAssertNil(map["highResolutionFrameCaptureFormat"])
  }

  func testSessionOptionsFromCreationParameters() {
    XCTAssertEqual(ARSessionOptions(creationParams: nil), ARSessionOptions())
    let options = ARSessionOptions(creationParams: [
      "sceneDepth": false, "smoothedSceneDepth": true, "highResolutionVideoFormat": false,
    ])
    XCTAssertFalse(options.sceneDepth)
    XCTAssertTrue(options.smoothedSceneDepth)
    XCTAssertFalse(options.highResolutionVideoFormat)
  }

  func testPlanUsesDepthOnlyWhenSupportedAndRequested() {
    let lidar = ARCameraCapabilitiesTests.capabilities(lidar: true, highResolution: true)
    let plan = ARSessionConfigurationPlan(options: ARSessionOptions(), capabilities: lidar)
    XCTAssertTrue(plan.sceneDepth)
    XCTAssertTrue(plan.deliversSceneDepth)
    XCTAssertFalse(plan.smoothedSceneDepth)
    XCTAssertEqual(plan.videoFormat, ARCameraCapabilitiesTests.highResolutionFormat)

    var pointsOnly = ARSessionOptions()
    pointsOnly.sceneDepth = false
    XCTAssertFalse(ARSessionConfigurationPlan(options: pointsOnly, capabilities: lidar).deliversSceneDepth)

    let noLiDAR = ARCameraCapabilitiesTests.capabilities(lidar: false, highResolution: false)
    let fallback = ARSessionConfigurationPlan(options: ARSessionOptions(), capabilities: noLiDAR)
    XCTAssertFalse(fallback.deliversSceneDepth)
    XCTAssertNil(fallback.videoFormat)
  }

  func testPlanConfigurationIsGravityAlignedWithoutPlanes() {
    var options = ARSessionOptions()
    options.sceneDepth = false
    let plan = ARSessionConfigurationPlan(
      options: options, capabilities: ARCameraCapabilitiesTests.capabilities(lidar: false, highResolution: false))
    let configuration = plan.makeConfiguration()
    XCTAssertEqual(configuration.worldAlignment, .gravity)
    XCTAssertEqual(configuration.planeDetection, [])
    XCTAssertFalse(configuration.frameSemantics.contains(.sceneDepth))
  }

  func testBackgroundQuadSamplesTheImageAtTheInverseDisplayTransform() {
    let identity = CameraBackgroundRenderer.quadVertices(displayTransform: .identity)
    XCTAssertEqual(identity.map { [$0.x, $0.y, $0.z, $0.w] }, [
      [-1, -1, 0, 1], [1, -1, 1, 1], [-1, 1, 0, 0], [1, 1, 1, 0],
    ])
    // Portrait: the landscape image is rotated by 90 degrees and cropped.
    let portrait = CGAffineTransform(a: 0, b: 1, c: -1.2, d: 0, tx: 1.1, ty: 0)
    let viewCorners: [CGPoint] = [CGPoint(x: 0, y: 1), CGPoint(x: 1, y: 1), CGPoint(x: 0, y: 0), CGPoint(x: 1, y: 0)]
    for (vertex, view) in zip(CameraBackgroundRenderer.quadVertices(displayTransform: portrait), viewCorners) {
      let back = CGPoint(x: CGFloat(vertex.z), y: CGFloat(vertex.w)).applying(portrait)
      XCTAssertEqual(back.x, view.x, accuracy: 1e-5)
      XCTAssertEqual(back.y, view.y, accuracy: 1e-5)
    }
  }
}
