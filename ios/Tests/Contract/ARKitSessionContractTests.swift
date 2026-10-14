import Foundation
import XCTest
@testable import ar_flutter_plugin_2

/// The ARKit capability query and capture view creation parameters against
/// `test_assets/arkit_session/arkit_session_contract.json`, the fixture the
/// plugin's Dart test `arkit_session_contract_test.dart` reads too.
final class ARKitSessionContractTests: XCTestCase {
  private func fixture() throws -> [String: Any] {
    guard let url = GoldenFixture.bundle.resourceURL?
      .appendingPathComponent("arkit_session/arkit_session_contract.json"),
      FileManager.default.fileExists(atPath: url.path) else {
      throw GoldenFixtureError.missing("arkit_session/arkit_session_contract.json in \(GoldenFixture.bundle.bundlePath)")
    }
    let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
    return try XCTUnwrap(object as? [String: Any])
  }

  func testCapabilityQueryUsesTheSharedChannelMethodAndReplyKeys() throws {
    let query = try XCTUnwrap(try fixture()["capabilityQuery"] as? [String: Any])
    XCTAssertEqual(query["channel"] as? String, ARKitCapabilityChannel.name)
    XCTAssertEqual(query["method"] as? String, ARKitCapabilityChannel.method)

    let host = try XCTUnwrap(query["host"] as? [String: Any])
    let format = ARVideoFormatDescription(
      width: 1920, height: 1440, framesPerSecond: 60, captureDeviceType: "AVCaptureDeviceTypeBuiltInWideAngleCamera",
      isRecommendedForHighResolutionFrameCapturing: true, isVideoHDRSupported: false)
    let capabilities = ARCameraCapabilities(
      modelIdentifier: try XCTUnwrap(host["modelIdentifier"] as? String),
      worldTrackingSupported: try XCTUnwrap(host["worldTracking"] as? Bool),
      sceneDepthSupported: try XCTUnwrap(host["sceneDepth"] as? Bool),
      smoothedSceneDepthSupported: try XCTUnwrap(host["smoothedSceneDepth"] as? Bool),
      sceneReconstructionSupported: try XCTUnwrap(host["sceneReconstruction"] as? Bool),
      videoFormats: [format],
      highResolutionFrameCaptureFormat: try XCTUnwrap(host["highResolutionFrameCapture"] as? Bool) ? format : nil)
    let reply = capabilities.channelMap

    let expected = try XCTUnwrap(query["reply"] as? [String: Any])
    for (key, value) in expected {
      switch value {
      case let text as String:
        XCTAssertEqual(reply[key] as? String, text, key)
      case let number as NSNumber:
        XCTAssertEqual(reply[key] as? Bool, number.boolValue, key)
      default:
        XCTFail("unexpected fixture value for \(key)")
      }
    }
  }

  func testCaptureViewCreationParametersReadAsDartWritesThem() throws {
    let view = try XCTUnwrap(try fixture()["captureView"] as? [String: Any])
    XCTAssertEqual(view["viewType"] as? String, CaptureARViewFactory.viewType)
    let known: Set<String> = [
      ARCaptureViewParameter.sceneDepth, ARCaptureViewParameter.smoothedSceneDepth,
      ARCaptureViewParameter.depthWidth, ARCaptureViewParameter.depthHeight,
      ARCaptureViewParameter.highResolutionFrameCapture,
    ]
    let cases = try XCTUnwrap(view["cases"] as? [[String: Any]])
    XCTAssertFalse(cases.isEmpty)
    for testCase in cases {
      let name = testCase["name"] as? String ?? "?"
      let params = try XCTUnwrap(testCase["creationParams"] as? [String: Any])
      XCTAssertTrue(Set(params.keys).isSubset(of: known), name)
      let options = ARSessionOptions(creationParams: params)
      XCTAssertEqual(options.sceneDepth, params[ARCaptureViewParameter.sceneDepth] as? Bool, name)
      XCTAssertEqual(
        options.highResolutionFrameCapture, params[ARCaptureViewParameter.highResolutionFrameCapture] as? Bool, name)
      XCTAssertEqual(options.depthOutputSize.width, params[ARCaptureViewParameter.depthWidth] as? Int, name)
      XCTAssertEqual(options.depthOutputSize.height, params[ARCaptureViewParameter.depthHeight] as? Int, name)
    }
  }
}
