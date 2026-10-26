import CoreGraphics
import Foundation
import XCTest
@testable import ar_flutter_plugin_2

final class CameraCapabilityProfileStoreTests: XCTestCase {
  private var defaults: UserDefaults!
  private var suiteName: String!

  override func setUp() {
    super.setUp()
    suiteName = "camera-capability-profile-tests-\(UUID().uuidString)"
    defaults = UserDefaults(suiteName: suiteName)!
  }

  override func tearDown() {
    defaults.removePersistentDomain(forName: suiteName)
    defaults = nil
    super.tearDown()
  }

  private func store(_ fingerprint: String = "phone|os|arkit-primary", supported: Bool = true) -> CameraCapabilityProfileStore {
    CameraCapabilityProfileStore(defaults: defaults, fingerprint: fingerprint, supported: supported,
      advertisedStillResolutions: [["width": 4032, "height": 3024]])
  }

  func testAdvertisedStillGeometryIsPendingUntilOperationalCapture() {
    let profile = store().profile
    XCTAssertEqual(profile["presetVersion"] as? Int, 13)
    XCTAssertEqual(profile["sharedCameraProbeStatus"] as? String, "pending")
    XCTAssertEqual(profile["sharedResolutionValidationComplete"] as? Bool, false)
    XCTAssertEqual(profile["supportsExactStillResolutionSelection"] as? Bool, true)
    XCTAssertEqual((profile["validatedSharedResolutions"] as? [[String: Int]])?.count, 0)
    XCTAssertEqual((profile["advertisedJpegHighResolutionResolutions"] as? [[String: Int]])?.first?["width"], 4032)
    XCTAssertEqual(profile["rawCapture"] as? Bool, false)
    XCTAssertEqual(profile["rawJpegProbeStatus"] as? String, "unsupported")
  }

  func testUnknownStillMetadataRetainsAutomaticWithoutInventingVideoSizes() {
    let unknown = CameraCapabilityProfileStore(defaults: defaults, fingerprint: "old-ios",
      supported: true, advertisedStillResolutions: [])
    XCTAssertEqual(unknown.profile["supportsExactStillResolutionSelection"] as? Bool, false)
    XCTAssertEqual((unknown.profile["advertisedJpegHighResolutionResolutions"] as? [[String: Int]])?.count, 0)
  }

  func testSupportedResolutionQueryDoesNotMislabelVideoGeometryAsStillOutput() {
    var snapshot = CameraCapabilitySnapshot(stillSize: CGSize(width: 4032, height: 3024),
      videoSizes: [CGSize(width: 1920, height: 1440), CGSize(width: 640, height: 480)],
      isoRange: nil, exposureRange: nil, intrinsics: nil)
    XCTAssertEqual(CameraCapabilityQueries.resolutions(snapshot).map { [$0.0, $0.1] }, [[4032, 3024]])
    snapshot.selectableStillSizes = [CGSize(width: 2016, height: 1512), CGSize(width: 4032, height: 3024)]
    XCTAssertEqual(CameraCapabilityQueries.resolutions(snapshot).map { [$0.0, $0.1] }, [[4032, 3024], [2016, 1512]])
  }

  func testSupportedRequiresActualPositiveGeometryAndPreservesKnownDimensions() {
    let profile = store()
    profile.saveSupported(arguments: nil)
    profile.saveSupported(arguments: ["width": 0, "height": 3024])
    XCTAssertTrue(profile.validatedResolutions.isEmpty)
    profile.saveSupported(arguments: ["width": 1920, "height": 1080])
    profile.saveSupported(arguments: ["width": 4032, "height": 3024])
    profile.saveSupported(arguments: ["width": 4032, "height": 3024])
    XCTAssertEqual(profile.validatedResolutions, [["width": 4032, "height": 3024], ["width": 1920, "height": 1080]])
    XCTAssertEqual(store().profile["sharedCameraProbeStatus"] as? String, "supported")
    XCTAssertEqual(store().profile["sharedResolutionValidationComplete"] as? Bool, true)
  }

  func testUnsupportedCannotPoisonPreviouslyVerifiedOutput() {
    let profile = store()
    profile.saveSupported(arguments: ["width": 4032, "height": 3024])
    profile.saveUnsupported(reason: "Another candidate failed")
    XCTAssertEqual(store().profile["sharedCameraProbeStatus"] as? String, "supported")
    XCTAssertEqual(store().validatedResolutions.count, 1)
  }

  func testChangedEnvironmentMissesCacheWithoutDeletingPreviousMeasurements() {
    store("old-environment").saveSupported(arguments: ["width": 4032, "height": 3024])
    XCTAssertEqual(store("new-environment").profile["sharedCameraProbeStatus"] as? String, "pending")
    XCTAssertEqual(store("new-environment").profile["cached"] as? Bool, false)
    XCTAssertEqual(store("old-environment").validatedResolutions.count, 1)
  }

  func testResetTouchesOnlyCurrentDeviceCapabilityCache() {
    defaults.set("preserved", forKey: "capture-settings")
    defaults.set("legacy", forKey: "old-camera-cache")
    store("other-environment").saveSupported(arguments: ["width": 1920, "height": 1080])
    let profile = store()
    profile.saveUnsupported(reason: "Stable unsupported")
    XCTAssertEqual(profile.profile["sharedCameraProbeStatus"] as? String, "unsupported")
    profile.reset()
    XCTAssertEqual(profile.profile["sharedCameraProbeStatus"] as? String, "pending")
    XCTAssertEqual(defaults.string(forKey: "capture-settings"), "preserved")
    XCTAssertEqual(defaults.string(forKey: "old-camera-cache"), "legacy")
    XCTAssertEqual(store("other-environment").validatedResolutions.count, 1)
  }

  func testUnavailableWorldTrackingNeverAdvertisesOperationalSupport() {
    let profile = store(supported: false)
    profile.saveSupported(arguments: ["width": 4032, "height": 3024])
    XCTAssertTrue(profile.validatedResolutions.isEmpty)
    XCTAssertEqual(profile.profile["platformSupport"] as? Bool, false)
    XCTAssertEqual(profile.profile["sharedCameraProbeStatus"] as? String, "unsupported")
  }
}
