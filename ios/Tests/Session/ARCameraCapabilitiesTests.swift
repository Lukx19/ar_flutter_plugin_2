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
      modelIdentifier: "iPhone13,3",
      worldTrackingSupported: true,
      sceneDepthSupported: lidar,
      smoothedSceneDepthSupported: lidar,
      sceneReconstructionSupported: lidar,
      videoFormats: [highResolutionFormat],
      highResolutionFrameCaptureFormat: highResolution ? highResolutionFormat : nil)
  }

  func testExactStillSizeSelectsItsRealVideoPairAndKeepsAutomaticDistinct() {
    func format(_ photoWidth: Int, _ photoHeight: Int, videoWidth: Int, videoHeight: Int, fps: Int) -> ARVideoFormatDescription {
      ARVideoFormatDescription(width: videoWidth, height: videoHeight, framesPerSecond: fps,
        captureDeviceType: ARSessionConfigurationPlan.wideAngleDeviceType,
        isRecommendedForHighResolutionFrameCapturing: true, isVideoHDRSupported: false,
        stillWidth: photoWidth, stillHeight: photoHeight)
    }
    let large = format(4032, 3024, videoWidth: 1920, videoHeight: 1440, fps: 60)
    let smallSlow = format(2016, 1512, videoWidth: 1920, videoHeight: 1440, fps: 30)
    let smallFast = format(2016, 1512, videoWidth: 1440, videoHeight: 1080, fps: 60)
    let smallWide = format(2016, 1512, videoWidth: 1920, videoHeight: 1080, fps: 120)
    var capabilities = Self.capabilities(lidar: true, highResolution: true)
    capabilities.videoFormats = [large, smallSlow, smallFast, smallWide]
    capabilities.highResolutionFrameCaptureFormat = large
    var automatic = ARSessionOptions()
    automatic.highResolutionFrameCapture = true
    XCTAssertEqual(ARSessionConfigurationPlan(options: automatic, capabilities: capabilities).videoFormat, large)
    let exact = ARSessionOptions(creationParams: ["highResolutionFrameCapture": true,
      "stillWidth": 2016, "stillHeight": 1512, "maxCameraImageHeight": 480])
    let plan = ARSessionConfigurationPlan(options: exact, capabilities: capabilities)
    XCTAssertEqual(plan.videoFormat, smallFast, "exact photo and video are coupled, not independently downscaled")
    XCTAssertFalse(plan.unsupportedStillResolution)
    XCTAssertEqual(plan.exactStillResolution, CGSize(width: 2016, height: 1512))
    var pointsOnly = exact
    pointsOnly.sceneDepth = false
    XCTAssertEqual(ARSessionConfigurationPlan(options: pointsOnly, capabilities: capabilities).videoFormat, smallWide)
    XCTAssertFalse(plan.highResolutionFrameCapture, "makeConfiguration must use this pair, not replace it with Automatic")
    XCTAssertEqual(smallFast.channelMap["stillWidth"] as? Int, 2016)
    XCTAssertEqual(smallFast.channelMap["stillHeight"] as? Int, 1512)
  }

  func testUnsupportedExactStillSizeCannotBecomeNativeDefault() {
    let options = ARSessionOptions(creationParams: ["stillWidth": 8000, "stillHeight": 6000])
    let plan = ARSessionConfigurationPlan(options: options, capabilities: Self.capabilities(lidar: true, highResolution: true))
    XCTAssertTrue(plan.unsupportedStillResolution)
    XCTAssertNil(plan.videoFormat)
    let malformed = ARSessionOptions(creationParams: ["stillWidth": -1, "stillHeight": 3024])
    XCTAssertNil(malformed.stillWidth)
    XCTAssertNil(malformed.stillHeight)
  }

  func testChannelMapReportsLiDARFormatsAndHighResolutionCapture() {
    let map = ARCameraCapabilitiesTests.capabilities(lidar: true, highResolution: true).channelMap
    XCTAssertEqual(map["platform"] as? String, "ios")
    XCTAssertEqual(map["modelIdentifier"] as? String, "iPhone13,3")
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
    let defaults = ARSessionOptions()
    XCTAssertTrue(defaults.sceneDepth)
    XCTAssertFalse(defaults.highResolutionFrameCapture, "standard video format unless requested")
    XCTAssertEqual(defaults.depthOutputSize, ARDepthOutputSize(width: 128, height: 96))
    let options = ARSessionOptions(creationParams: [
      "sceneDepth": false, "smoothedSceneDepth": true, "highResolutionFrameCapture": true,
      "depthWidth": 256, "depthHeight": 192,
    ])
    XCTAssertFalse(options.sceneDepth)
    XCTAssertTrue(options.smoothedSceneDepth)
    XCTAssertTrue(options.highResolutionFrameCapture)
    XCTAssertEqual(options.depthOutputSize, ARDepthOutputSize(width: 256, height: 192))
    let malformed = ARSessionOptions(creationParams: ["depthWidth": 0, "depthHeight": 96])
    XCTAssertEqual(malformed.depthOutputSize, .standard, "a non-positive size keeps the default")
  }

  func testPlanUsesDepthOnlyWhenSupportedAndRequested() {
    let lidar = ARCameraCapabilitiesTests.capabilities(lidar: true, highResolution: true)
    let plan = ARSessionConfigurationPlan(options: ARSessionOptions(), capabilities: lidar)
    XCTAssertTrue(plan.sceneDepth)
    XCTAssertTrue(plan.deliversSceneDepth)
    XCTAssertFalse(plan.smoothedSceneDepth)
    XCTAssertNil(plan.videoFormat, "high-resolution format only on request")
    XCTAssertEqual(plan.depthOutputSize, .standard)

    var highResolution = ARSessionOptions()
    highResolution.highResolutionFrameCapture = true
    XCTAssertEqual(
      ARSessionConfigurationPlan(options: highResolution, capabilities: lidar).videoFormat,
      ARCameraCapabilitiesTests.highResolutionFormat)

    var pointsOnly = ARSessionOptions()
    pointsOnly.sceneDepth = false
    XCTAssertFalse(ARSessionConfigurationPlan(options: pointsOnly, capabilities: lidar).deliversSceneDepth)

    let noLiDAR = ARCameraCapabilitiesTests.capabilities(lidar: false, highResolution: false)
    let fallback = ARSessionConfigurationPlan(options: ARSessionOptions(), capabilities: noLiDAR)
    XCTAssertFalse(fallback.deliversSceneDepth)
    XCTAssertNil(fallback.videoFormat)
  }

  func testPlanChoosesTheCameraTierFormatUnlessStillsNeedTheRecommendedOne() {
    func format(_ width: Int, _ height: Int, _ fps: Int, device: String = ARSessionConfigurationPlan.wideAngleDeviceType)
      -> ARVideoFormatDescription {
      ARVideoFormatDescription(
        width: width, height: height, framesPerSecond: fps, captureDeviceType: device,
        isRecommendedForHighResolutionFrameCapturing: false, isVideoHDRSupported: false)
    }
    let p1080 = format(1920, 1080, 60)
    let p720 = format(1280, 720, 60)
    let p720Slow = format(1280, 720, 30)
    let fourThree1080 = format(1440, 1080, 60)
    let ultraWide = format(640, 480, 60, device: "AVCaptureDeviceTypeBuiltInUltraWideCamera")
    var capabilities = ARCameraCapabilitiesTests.capabilities(lidar: true, highResolution: true)
    capabilities.videoFormats = [
      ARCameraCapabilitiesTests.highResolutionFormat, p1080, p720Slow, p720, fourThree1080, ultraWide,
    ]

    // Points-only (no scene depth): any aspect.
    var options = ARSessionOptions(creationParams: ["maxCameraImageHeight": 720, "sceneDepth": false])
    XCTAssertEqual(options.maxVideoHeight, 720)
    XCTAssertEqual(ARSessionConfigurationPlan(options: options, capabilities: capabilities).videoFormat, p720)
    options.maxVideoHeight = 1080
    XCTAssertEqual(ARSessionConfigurationPlan(options: options, capabilities: capabilities).videoFormat, p1080)
    options.maxVideoHeight = 480
    XCTAssertNil(
      ARSessionConfigurationPlan(options: options, capabilities: capabilities).videoFormat,
      "no wide-angle format fits: ARKit's default stays")

    // Scene depth on: only the default's 4:3 aspect.
    var depth = ARSessionOptions()
    depth.maxVideoHeight = 1080
    XCTAssertEqual(ARSessionConfigurationPlan(options: depth, capabilities: capabilities).videoFormat, fourThree1080)
    depth.maxVideoHeight = 720
    XCTAssertNil(ARSessionConfigurationPlan(options: depth, capabilities: capabilities).videoFormat)

    // A tier never raises the format above ARKit's default.
    var smallDefault = capabilities
    smallDefault.videoFormats = [p720Slow, p1080]
    options.maxVideoHeight = 1080
    XCTAssertNil(ARSessionConfigurationPlan(options: options, capabilities: smallDefault).videoFormat)

    // High-resolution stills keep the recommended format and ignore the tier.
    var stills = options
    stills.highResolutionFrameCapture = true
    let plan = ARSessionConfigurationPlan(options: stills, capabilities: capabilities)
    XCTAssertEqual(plan.videoFormat, ARCameraCapabilitiesTests.highResolutionFormat)
    XCTAssertTrue(plan.highResolutionFrameCapture)
    XCTAssertEqual(ARSessionOptions(creationParams: ["maxCameraImageHeight": 0]).maxVideoHeight, 0)
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
