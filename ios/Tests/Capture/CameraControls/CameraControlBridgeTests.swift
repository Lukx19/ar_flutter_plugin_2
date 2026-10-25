import Foundation
import XCTest
@testable import ar_flutter_plugin_2

/// A configurable camera that applies every change at once and records
/// whether it was locked for configuration. It has no AR session: a control
/// can only lock the device, never pause or reconfigure ARKit.
final class FakeCameraControlDevice: CameraControlDevice {
  var minISO: Float = 34
  var maxISO: Float = 3264
  var iso: Float = 100
  /// Not whole microseconds, as on real formats (29.05 us, 41666.67 us).
  var minExposureSeconds = 1.0 / 34_420
  var maxExposureSeconds = 1.0 / 24
  var exposureSeconds = 1.0 / 60.0
  var exposureMode: CameraExposureModeValue = .auto
  var supportsCustomExposure = true
  var supportsLockedExposure = true
  var minExposureBias: Float = -8
  var maxExposureBias: Float = 8
  var exposureBias: Float = 0
  var focusModes: Set<CameraFocusModeValue> = [.auto, .continuous, .locked]
  var focusMode: CameraFocusModeValue = .continuous
  var lensPosition: Float = 0.5
  var supportsCustomLensPosition = true
  var supportsFocusPoint = true
  var isAdjustingFocus = false
  var supportsLockedWhiteBalanceGains = true
  var isWhiteBalanceAuto = true
  var isAdjustingWhiteBalance = false
  var whiteBalanceTemperature: Float? = 5000
  var hasTorch = true
  var isTorchAvailable = true
  var isTorchOn = false

  var configurationFails = false
  private(set) var configurations = 0
  private(set) var locked = false
  private(set) var focusPoint: (Double, Double)?
  /// Values AVFoundation would refuse with an exception (outside the active
  /// format, an unsupported mode, a torch change without a torch).
  private(set) var violations: [String] = []

  func configure(_ change: () throws -> Void) throws {
    if configurationFails { throw NSError(domain: "AVFoundationErrorDomain", code: -11800) }
    locked = true
    defer { locked = false }
    configurations += 1
    try change()
  }

  private func requireLocked() { precondition(locked, "changes only inside lockForConfiguration") }

  func setCustomExposure(seconds: Double?, iso: Float?) {
    requireLocked()
    if let seconds = seconds, !(minExposureSeconds...maxExposureSeconds).contains(seconds) {
      violations.append("exposure \(seconds) s outside \(minExposureSeconds)...\(maxExposureSeconds)")
    }
    if let iso = iso, !(minISO...maxISO).contains(iso) { violations.append("ISO \(iso) outside \(minISO)...\(maxISO)") }
    exposureMode = .custom
    if let seconds = seconds { exposureSeconds = seconds }
    if let iso = iso { self.iso = iso }
  }

  func setExposureMode(_ mode: CameraExposureModeValue) { requireLocked(); exposureMode = mode }
  func setExposureBias(_ bias: Float) { requireLocked(); exposureBias = bias }
  func setFocusMode(_ mode: CameraFocusModeValue) {
    requireLocked()
    if !focusModes.contains(mode) { violations.append("focus mode \(mode) unsupported") }
    focusMode = mode
  }
  func setLensPosition(_ position: Float) { requireLocked(); focusMode = .locked; lensPosition = position }
  func setFocusPoint(x: Double, y: Double) { requireLocked(); focusPoint = (x, y) }

  func setWhiteBalance(kelvin: Float?) {
    requireLocked()
    isWhiteBalanceAuto = kelvin == nil
    if let kelvin = kelvin { whiteBalanceTemperature = kelvin }
  }

  func lockWhiteBalance() { requireLocked(); isWhiteBalanceAuto = false }

  func setTorch(_ on: Bool) throws {
    requireLocked()
    if !hasTorch { violations.append("torch change without a torch") }
    isTorchOn = on
  }
}

/// The Android runtime camera controls on the camera ARKit runs: Android
/// argument names, reply maps, clamping and error codes
/// (`CONTROL_UNSUPPORTED`, `CONTROL_UPDATE_FAILED`), every change inside
/// one `lockForConfiguration`, and reply key sets equal to the shared
/// contract `test_assets/camera_controls/runtime_control_replies.json`.
final class CameraControlBridgeTests: XCTestCase {
  private var device: FakeCameraControlDevice!
  private var bridge: CameraControlBridge!

  override func setUp() {
    super.setUp()
    device = FakeCameraControlDevice()
    bridge = CameraControlBridge(device: device)
  }

  override func tearDown() {
    XCTAssertEqual(device.violations, [], "no value AVFoundation would refuse reaches the device")
    super.tearDown()
  }

  private func call(_ method: String, _ arguments: [String: Any] = [:]) throws -> Any? {
    try bridge.handle(method, arguments)
  }

  private func map(_ method: String, _ arguments: [String: Any] = [:]) throws -> [String: Any?] {
    try XCTUnwrap(try call(method, arguments) as? [String: Any?], method)
  }

  private func assertCode(_ code: String, _ method: String, _ arguments: [String: Any] = [:],
                          file: StaticString = #filePath, line: UInt = #line) {
    XCTAssertThrowsError(try call(method, arguments), method, file: file, line: line) { error in
      XCTAssertEqual((error as? CameraControlError)?.code, code, "\(method): \(error)", file: file, line: line)
    }
  }

  func testExposureControlsClampToTheActiveFormatAndReplyLikeAndroid() throws {
    XCTAssertEqual(try call("getSupportedISORange") as? [Int], [34, 3264])
    XCTAssertEqual(try map("getSupportedExposureRange")["min"] as? Int64, 30, "minimum rounded up")
    XCTAssertEqual(try map("getSupportedExposureRange")["max"] as? Int64, 41_666, "maximum rounded down")
    XCTAssertEqual(try map("setISO", ["isoValue": 12_800])["actualISO"] as? Int, 3264, "clamped to the range")
    XCTAssertEqual(device.exposureMode, .custom)
    XCTAssertEqual(try map("setExposureTime", ["exposureTimeMicroseconds": 5])["actualExposureTimeMicroseconds"] as? Int64, 30)
    XCTAssertEqual(device.iso, 3264, "setting the time keeps the ISO")
    XCTAssertEqual(try call("getCurrentISO") as? Int, 3264)
    XCTAssertEqual(try call("getCurrentExposureTime") as? Int64, 30)
    var state = try map("getCurrentExposureState")
    XCTAssertEqual(state["exposureMode"] as? String, "manual")
    XCTAssertEqual(state["isAutoExposureEnabled"] as? Bool, false)
    XCTAssertEqual(try call("setAutoExposureEnabled", ["enabled": true]) as? Bool, true)
    XCTAssertEqual(device.exposureMode, .auto)
    XCTAssertEqual(try call("lockExposure") as? Bool, true)
    state = try map("getCurrentExposureState")
    XCTAssertEqual(state["isExposureLocked"] as? Bool, true)
    XCTAssertEqual(state["isAutoExposureEnabled"] as? Bool, true, "a locked auto exposure is still auto")
    XCTAssertEqual(try call("unlockExposure") as? Bool, true)
    XCTAssertEqual(device.exposureMode, .auto)
  }

  /// Ranges that are not whole microseconds: both edges stay inside the
  /// device range (the fake records any value AVFoundation would refuse).
  func testExposureTimeEdgesStayInsideTheActiveFormat() throws {
    for requested: Int64 in [0, 29, 30, 41_666, 41_667, 10_000_000] {
      let applied = try XCTUnwrap(try map("setExposureTime", ["exposureTimeMicroseconds": requested])["actualExposureTimeMicroseconds"] as? Int64)
      XCTAssertTrue((30...41_666).contains(applied), "\(requested) -> \(applied)")
      XCTAssertTrue((device.minExposureSeconds...device.maxExposureSeconds).contains(device.exposureSeconds), "\(requested)")
    }
    device.minISO = 33.5
    device.maxISO = 3168.7
    XCTAssertEqual(try call("getSupportedISORange") as? [Int], [34, 3168])
    XCTAssertEqual(try map("setISO", ["isoValue": 1])["actualISO"] as? Int, 34)
    XCTAssertEqual(try map("setISO", ["isoValue": 99_999])["actualISO"] as? Int, 3168)
    // A range inside one microsecond has no whole value: unsupported.
    device.minExposureSeconds = 0.000_010_2
    device.maxExposureSeconds = 0.000_010_8
    assertCode(CameraControlError.unsupportedCode, "getSupportedExposureRange")
  }

  func testATorchlessCameraIsNeverAskedToSwitchItsTorch() throws {
    device.hasTorch = false
    device.isTorchAvailable = false
    XCTAssertEqual(try call("setFlashMode", ["mode": "off"]) as? Bool, true)
    XCTAssertEqual(try call("setTorchEnabled", ["enabled": false]) as? Bool, true)
    XCTAssertEqual(try call("setTorchEnabled", ["enabled": true]) as? Bool, true, "Android stages off")
    XCTAssertEqual(try call("isFlashAvailable") as? Bool, false)
    XCTAssertEqual(try call("getSupportedFlashModes") as? [String], ["off"])
    XCTAssertEqual(try map("getCurrentFlashState")["flashStatus"] as? String, "unavailable")
    XCTAssertEqual(device.configurations, 0, "nothing on the device changes")
    try bridge.reset()
    XCTAssertFalse(device.isTorchOn)
  }

  func testResetReturnsTheSharedCameraToAutoWithTheTorchOff() throws {
    _ = try call("setISO", ["isoValue": 400])
    _ = try call("setExposureCompensation", ["evStep": 1])
    _ = try call("setFocusDistance", ["distance": 0.3])
    _ = try call("setColorTemperature", ["colorTemperatureK": 4000])
    _ = try call("setTorchEnabled", ["enabled": true])
    try bridge.reset()
    XCTAssertEqual(device.exposureMode, .auto)
    XCTAssertEqual(device.exposureBias, 0)
    XCTAssertEqual(device.focusMode, .continuous)
    XCTAssertTrue(device.isWhiteBalanceAuto)
    XCTAssertFalse(device.isTorchOn)
    let state = try map("getCurrentWhiteBalanceState")
    XCTAssertEqual(state["currentMode"] as? String, "auto")
    XCTAssertEqual(state["isAutoWhiteBalanceEnabled"] as? Bool, true)
    let fresh = CameraControlBridge(device: device)
    let exposure = try XCTUnwrap(try fresh.handle("getCurrentExposureState", [:]) as? [String: Any?])
    XCTAssertEqual(exposure["exposureMode"] as? String, "auto", "a new bridge agrees with the device")
  }

  func testExposureCompensationMovesInThirdEvSteps() throws {
    let info = try map("getExposureCompensationInfo")
    XCTAssertEqual(info["stepSize"] as? Double ?? 0, 1.0 / 3.0, accuracy: 1e-12)
    XCTAssertEqual(info["minCompensation"] as? Double ?? 0, -8, accuracy: 1e-12)
    let applied = try XCTUnwrap(try map("setExposureCompensation", ["evStep": 0.4])["actualCompensation"] as? Double)
    XCTAssertEqual(applied, 1.0 / 3.0, accuracy: 1e-12, "rounded to the nearest step")
    XCTAssertEqual(Double(device.exposureBias), 1.0 / 3.0, accuracy: 1e-6)
    XCTAssertEqual(try map("setExposureCompensation", ["evStep": 20])["actualCompensation"] as? Double ?? 0, 8, accuracy: 1e-9)
  }

  func testFocusControlsUseTheLensPositionAndPointOfInterest() throws {
    XCTAssertEqual(try call("getSupportedFocusModes") as? [String], ["auto", "continuous", "fixed", "infinity"])
    XCTAssertEqual(try map("setFocusDistance", ["distance": 1.4])["actualDistance"] as? Double, 1)
    XCTAssertEqual(device.focusMode, .locked)
    var state = try map("getCurrentFocusState")
    XCTAssertEqual(state["currentFocusMode"] as? String, "infinity")
    XCTAssertEqual(state["isFocusLocked"] as? Bool, true)
    XCTAssertEqual(try call("setAutofocusEnabled", ["enabled": true]) as? Bool, true)
    XCTAssertEqual(device.focusMode, .continuous)
    XCTAssertEqual(try call("focusAtPoint", ["x": 0.5, "y": 0.5]) as? Bool, true)
    XCTAssertEqual(device.focusMode, .auto, "one scan at the point")
    state = try map("getCurrentFocusState")
    let region = try XCTUnwrap(state["focusRegion"] as? [String: Double])
    XCTAssertEqual(region["left"] ?? 0, 0.425, accuracy: 1e-12)
    XCTAssertEqual(region["width"] ?? 0, 0.15, accuracy: 1e-12)
    XCTAssertEqual(try call("setFocusMode", ["mode": "fixed"]) as? Bool, true)
    XCTAssertEqual(device.focusMode, .locked)
    assertCode(CameraControlError.unsupportedCode, "setFocusMode", ["mode": "macro"])
    device.supportsCustomLensPosition = false
    assertCode(CameraControlError.unsupportedCode, "setFocusDistance", ["distance": 0.5])
  }

  func testWhiteBalancePresetsManualTemperatureAndLocks() throws {
    XCTAssertEqual(try call("getSupportedWhiteBalanceModes") as? [String], [
      "auto", "cloudyDaylight", "daylight", "fluorescent", "incandescent", "shade", "twilight", "warmFluorescent",
    ])
    XCTAssertEqual(try call("setWhiteBalanceMode", ["mode": "daylight"]) as? Bool, true)
    XCTAssertEqual(device.whiteBalanceTemperature, 5500)
    XCTAssertEqual(try map("getCurrentWhiteBalanceState")["currentMode"] as? String, "daylight")
    XCTAssertEqual(try map("getSupportedColorTemperatureRange")["min"] as? Int, 2500)
    XCTAssertEqual(try map("setColorTemperature", ["colorTemperatureK": 1000])["actualColorTemperature"] as? Int, 2500)
    var state = try map("getCurrentWhiteBalanceState")
    XCTAssertEqual(state["currentMode"] as? String, "manual")
    XCTAssertEqual(state["currentColorTemperature"] as? Int, 2500)
    XCTAssertEqual(state["isAutoWhiteBalanceEnabled"] as? Bool, false)
    assertCode(CameraControlError.unsupportedCode, "lockWhiteBalance")
    XCTAssertEqual(try call("setWhiteBalanceMode", ["mode": "auto"]) as? Bool, true)
    XCTAssertTrue(device.isWhiteBalanceAuto)
    XCTAssertEqual(try call("lockWhiteBalance") as? Bool, true)
    state = try map("getCurrentWhiteBalanceState")
    XCTAssertEqual(state["isWhiteBalanceLocked"] as? Bool, true)
    XCTAssertEqual(try call("unlockWhiteBalance") as? Bool, true)
    assertCode(CameraControlError.unsupportedCode, "setWhiteBalanceFromPoint", ["x": 0.5, "y": 0.5])
    assertCode(CameraControlError.unsupportedCode, "setWhiteBalanceMode", ["mode": "manual"])
  }

  func testOnlyTheTorchIsAvailableWhileARKitRuns() throws {
    XCTAssertEqual(try call("isFlashAvailable") as? Bool, true)
    XCTAssertEqual(try call("getSupportedFlashModes") as? [String], ["off", "torch"])
    XCTAssertEqual(try call("setTorchEnabled", ["enabled": true]) as? Bool, true)
    XCTAssertTrue(device.isTorchOn)
    let state = try map("getCurrentFlashState")
    XCTAssertEqual(state["currentFlashMode"] as? String, "torch")
    XCTAssertEqual(state["flashStatus"] as? String, "ready")
    assertCode(CameraControlError.unsupportedCode, "setFlashMode", ["mode": "on"])
    XCTAssertEqual(try call("setFlashMode", ["mode": "off"]) as? Bool, true)
    XCTAssertFalse(device.isTorchOn)
    device.hasTorch = false
    XCTAssertEqual(try call("setFlashMode", ["mode": "on"]) as? Bool, true, "no torch: non-off falls back to off")
    XCTAssertFalse(device.isTorchOn)
  }

  func testFailuresAndMissingArgumentsUseTheAndroidCodes() {
    device.configurationFails = true
    assertCode(CameraControlError.updateFailedCode, "setISO", ["isoValue": 100])
    device.configurationFails = false
    assertCode("CONFIG_INVALID", "setISO")
    assertCode("CONFIG_INVALID", "setAutofocusEnabled", ["enabled": 1])
    device.supportsCustomExposure = false
    assertCode(CameraControlError.unsupportedCode, "getSupportedISORange")
    assertCode(CameraControlError.unsupportedCode, "setExposureTime", ["exposureTimeMicroseconds": 1000])
    device.supportsLockedExposure = false
    assertCode(CameraControlError.unsupportedCode, "lockExposure")
  }

  func testEveryChangeRunsInOneConfigurationLock() throws {
    _ = try call("setISO", ["isoValue": 400])
    _ = try call("setFocusDistance", ["distance": 0.2])
    _ = try call("setColorTemperature", ["colorTemperatureK": 4500])
    _ = try call("getCurrentExposureState")
    XCTAssertEqual(device.configurations, 3, "reads never lock; each change locks once")
  }

  func testHDRMechanismMetadataSeparatesBalancedFallbackFromMergedHDR() {
    let beforeBalancedAPI = CameraCapabilityQueries.hdrSupport(
      supportsBalancedAPI: false, highResolutionFrameCapture: true, videoHDR: true)
    XCTAssertEqual(beforeBalancedAPI["balancedPhotoProcessing"] as? Bool, false)
    XCTAssertEqual(beforeBalancedAPI["balancedPhotoMayFallback"] as? Bool, false)
    XCTAssertEqual(beforeBalancedAPI["video"] as? Bool, true)
    let balanced = CameraCapabilityQueries.hdrSupport(
      supportsBalancedAPI: true, highResolutionFrameCapture: true, videoHDR: false)
    XCTAssertEqual(balanced["balancedPhotoProcessing"] as? Bool, true)
    XCTAssertEqual(balanced["balancedPhotoMayFallback"] as? Bool, true)
    XCTAssertEqual(balanced["exposureBracketing"] as? Bool, false)
    XCTAssertTrue(balanced["pipelinePhoto"] is NSNull)
    XCTAssertTrue(balanced["mergedPhoto"] is NSNull)
    XCTAssertTrue(balanced["photo"] is NSNull)
    let noHighResolution = CameraCapabilityQueries.hdrSupport(
      supportsBalancedAPI: true, highResolutionFrameCapture: false, videoHDR: false)
    XCTAssertEqual(noHighResolution["balancedPhotoProcessing"] as? Bool, false)
  }

  // MARK: Shared contract

  static func contract() throws -> [String: Any] {
    let bundled = GoldenFixtureBundle.url("camera_controls/runtime_control_replies.json")
    let source = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
      .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
      .appendingPathComponent("test_assets/camera_controls/runtime_control_replies.json")
    let override = ProcessInfo.processInfo.environment["CAPTURE3D_PLUGIN_ROOT"].map {
      URL(fileURLWithPath: $0).appendingPathComponent("test_assets/camera_controls/runtime_control_replies.json")
    }
    let url = bundled ?? override ?? source
    return try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
  }

  func testReplyKeysEqualTheSharedAndroidContract() throws {
    let contract = try Self.contract()
    let stateKeys = try XCTUnwrap(contract["stateKeys"] as? [String: [String]])
    _ = try call("focusAtPoint", ["x": 0.2, "y": 0.3])
    let region = try XCTUnwrap(try map("getCurrentFocusState")["focusRegion"] as? [String: Double])
    XCTAssertEqual(Set(region.keys), Set(try XCTUnwrap(contract["focusRegionKeys"] as? [String])))
    for (method, keys) in stateKeys {
      XCTAssertEqual(Set(try map(method).keys), Set(keys), method)
    }
    let setters = try XCTUnwrap(contract["setterReplyKeys"] as? [String: String])
    let arguments: [String: [String: Any]] = [
      "setISO": ["isoValue": 200], "setExposureTime": ["exposureTimeMicroseconds": 10_000],
      "setExposureCompensation": ["evStep": 0], "setFocusDistance": ["distance": 0.5],
      "setColorTemperature": ["colorTemperatureK": 5000],
    ]
    for (method, key) in setters {
      XCTAssertEqual(Array(try map(method, arguments[method] ?? [:]).keys), [key], method)
    }
    let modes = try XCTUnwrap(contract["modeNames"] as? [String: [String]])
    XCTAssertTrue(Set(try call("getSupportedFocusModes") as? [String] ?? []).isSubset(of: Set(modes["focus"] ?? [])))
    XCTAssertTrue(Set(try call("getSupportedWhiteBalanceModes") as? [String] ?? []).isSubset(of: Set(modes["whiteBalance"] ?? [])))
    XCTAssertTrue(Set(["off", "torch"]).isSubset(of: Set(modes["flash"] ?? [])))
    XCTAssertTrue(Set(try call("getSupportedFlashModes") as? [String] ?? []).isSubset(of: Set(modes["flash"] ?? [])))
    XCTAssertEqual(Set(contract["errorCodes"] as? [String] ?? []), [CameraControlError.unsupportedCode, CameraControlError.updateFailedCode])
    XCTAssertEqual(CameraControlBridge.methods.count, 31, "the 30 Android runtime controls and the iOS flash mode query")
  }
}

/// Resource lookup that works in the app's test bundle and in a host test
/// package without bundled resources.
enum GoldenFixtureBundle {
  static func url(_ relative: String) -> URL? {
    final class Token {}
    guard let base = Bundle(for: Token.self).resourceURL else { return nil }
    let url = base.appendingPathComponent(relative)
    return FileManager.default.fileExists(atPath: url.path) ? url : nil
  }
}
