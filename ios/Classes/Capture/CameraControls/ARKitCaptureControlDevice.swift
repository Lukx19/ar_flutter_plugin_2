import ARKit
import AVFoundation
import Foundation

/// [CameraControlDevice] over the camera a running `ARWorldTrackingConfiguration`
/// uses (`configurableCaptureDeviceForPrimaryCamera`). Settings change with
/// `lockForConfiguration` on that device while ARKit keeps running: the
/// session is never paused, reset or reconfigured for a control.
final class ARKitCaptureControlDevice: CameraControlDevice {
  private let device: AVCaptureDevice

  init(device: AVCaptureDevice) {
    self.device = device
  }

  /// The AR session's configurable camera; nil on devices or OS versions
  /// without it and in the simulator.
  static func primary() -> ARKitCaptureControlDevice? {
    guard ARWorldTrackingConfiguration.isSupported,
          let device = ARWorldTrackingConfiguration.configurableCaptureDeviceForPrimaryCamera else { return nil }
    return ARKitCaptureControlDevice(device: device)
  }

  var minISO: Float { device.activeFormat.minISO }
  var maxISO: Float { device.activeFormat.maxISO }
  var iso: Float { device.iso }
  var minExposureSeconds: Double { device.activeFormat.minExposureDuration.seconds }
  var maxExposureSeconds: Double { device.activeFormat.maxExposureDuration.seconds }
  var exposureSeconds: Double { device.exposureDuration.seconds }
  var exposureMode: CameraExposureModeValue {
    switch device.exposureMode {
    case .custom: return .custom
    case .locked: return .locked
    default: return .auto
    }
  }
  var supportsCustomExposure: Bool { device.isExposureModeSupported(.custom) }
  var supportsLockedExposure: Bool { device.isExposureModeSupported(.locked) }
  var minExposureBias: Float { device.minExposureTargetBias }
  var maxExposureBias: Float { device.maxExposureTargetBias }
  var exposureBias: Float { device.exposureTargetBias }

  var focusModes: Set<CameraFocusModeValue> {
    var modes = Set<CameraFocusModeValue>()
    if device.isFocusModeSupported(.autoFocus) { modes.insert(.auto) }
    if device.isFocusModeSupported(.continuousAutoFocus) { modes.insert(.continuous) }
    if device.isFocusModeSupported(.locked) { modes.insert(.locked) }
    return modes
  }
  var focusMode: CameraFocusModeValue {
    switch device.focusMode {
    case .autoFocus: return .auto
    case .continuousAutoFocus: return .continuous
    default: return .locked
    }
  }
  var lensPosition: Float { device.lensPosition }
  var supportsCustomLensPosition: Bool { device.isLockingFocusWithCustomLensPositionSupported }
  var supportsFocusPoint: Bool { device.isFocusPointOfInterestSupported }
  var isAdjustingFocus: Bool { device.isAdjustingFocus }

  var supportsLockedWhiteBalanceGains: Bool { device.isLockingWhiteBalanceWithCustomDeviceGainsSupported }
  var isWhiteBalanceAuto: Bool { device.whiteBalanceMode != .locked }
  var isAdjustingWhiteBalance: Bool { device.isAdjustingWhiteBalance }
  var whiteBalanceTemperature: Float? {
    let value = device.temperatureAndTintValues(for: device.deviceWhiteBalanceGains).temperature
    return value.isFinite && value > 0 ? value : nil
  }

  var hasTorch: Bool { device.hasTorch }
  var isTorchAvailable: Bool { device.isTorchAvailable }
  var isTorchOn: Bool { device.torchMode == .on }

  func configure(_ change: () throws -> Void) throws {
    try device.lockForConfiguration()
    defer { device.unlockForConfiguration() }
    try change()
  }

  /// Manual exposure, clamped to the active format: AVFoundation raises an
  /// exception for a duration or ISO outside it.
  func setCustomExposure(seconds: Double?, iso: Float?) {
    guard device.isExposureModeSupported(.custom) else { return }
    let format = device.activeFormat
    var duration = AVCaptureDevice.currentExposureDuration
    if let seconds = seconds {
      duration = CMTimeMinimum(CMTimeMaximum(CMTime(seconds: seconds, preferredTimescale: 1_000_000_000), format.minExposureDuration),
                               format.maxExposureDuration)
    }
    let value = iso.map { min(max($0, format.minISO), format.maxISO) } ?? AVCaptureDevice.currentISO
    device.setExposureModeCustom(duration: duration, iso: value, completionHandler: nil)
  }

  // Every assignment below checks that the device supports the mode first:
  // AVFoundation raises an exception for an unsupported mode.

  func setExposureMode(_ mode: CameraExposureModeValue) {
    switch mode {
    case .auto:
      if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
    case .locked:
      if device.isExposureModeSupported(.locked) { device.exposureMode = .locked }
    case .custom: setCustomExposure(seconds: nil, iso: nil)
    }
  }

  func setExposureBias(_ bias: Float) {
    device.setExposureTargetBias(min(max(bias, device.minExposureTargetBias), device.maxExposureTargetBias), completionHandler: nil)
  }

  func setFocusMode(_ mode: CameraFocusModeValue) {
    let value: AVCaptureDevice.FocusMode
    switch mode {
    case .auto: value = .autoFocus
    case .continuous: value = .continuousAutoFocus
    case .locked: value = .locked
    }
    if device.isFocusModeSupported(value) { device.focusMode = value }
  }

  func setLensPosition(_ position: Float) {
    guard device.isLockingFocusWithCustomLensPositionSupported else { return }
    device.setFocusModeLocked(lensPosition: min(max(position, 0), 1), completionHandler: nil)
  }

  func setFocusPoint(x: Double, y: Double) {
    guard device.isFocusPointOfInterestSupported else { return }
    device.focusPointOfInterest = CGPoint(x: x, y: y)
    if device.isExposurePointOfInterestSupported, device.exposureMode != .custom {
      device.exposurePointOfInterest = CGPoint(x: x, y: y)
    }
  }

  func setWhiteBalance(kelvin: Float?) {
    guard let kelvin = kelvin else {
      if device.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) { device.whiteBalanceMode = .continuousAutoWhiteBalance }
      return
    }
    guard device.isLockingWhiteBalanceWithCustomDeviceGainsSupported else { return }
    var gains = device.deviceWhiteBalanceGains(for: AVCaptureDevice.WhiteBalanceTemperatureAndTintValues(temperature: kelvin, tint: 0))
    let maximum = device.maxWhiteBalanceGain
    gains.redGain = min(max(gains.redGain, 1), maximum)
    gains.greenGain = min(max(gains.greenGain, 1), maximum)
    gains.blueGain = min(max(gains.blueGain, 1), maximum)
    device.setWhiteBalanceModeLocked(with: gains, completionHandler: nil)
  }

  func lockWhiteBalance() {
    if device.isWhiteBalanceModeSupported(.locked) { device.whiteBalanceMode = .locked }
  }

  func setTorch(_ on: Bool) throws {
    guard device.hasTorch, device.isTorchModeSupported(on ? .on : .off) else { return }
    if on {
      try device.setTorchModeOn(level: AVCaptureDevice.maxAvailableTorchLevel)
    } else {
      device.torchMode = .off
    }
  }
}
