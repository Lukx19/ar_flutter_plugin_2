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

  func setCustomExposure(seconds: Double?, iso: Float?) {
    let duration = seconds.map { CMTime(seconds: $0, preferredTimescale: 1_000_000_000) } ?? AVCaptureDevice.currentExposureDuration
    let value = iso ?? AVCaptureDevice.currentISO
    device.setExposureModeCustom(duration: duration, iso: value, completionHandler: nil)
  }

  func setExposureMode(_ mode: CameraExposureModeValue) {
    switch mode {
    case .auto: device.exposureMode = .continuousAutoExposure
    case .locked: device.exposureMode = .locked
    case .custom: setCustomExposure(seconds: nil, iso: nil)
    }
  }

  func setExposureBias(_ bias: Float) { device.setExposureTargetBias(bias, completionHandler: nil) }

  func setFocusMode(_ mode: CameraFocusModeValue) {
    switch mode {
    case .auto: device.focusMode = .autoFocus
    case .continuous: device.focusMode = .continuousAutoFocus
    case .locked: device.focusMode = .locked
    }
  }

  func setLensPosition(_ position: Float) {
    device.setFocusModeLocked(lensPosition: min(max(position, 0), 1), completionHandler: nil)
  }

  func setFocusPoint(x: Double, y: Double) {
    device.focusPointOfInterest = CGPoint(x: x, y: y)
    if device.isExposurePointOfInterestSupported, device.exposureMode != .custom {
      device.exposurePointOfInterest = CGPoint(x: x, y: y)
    }
  }

  func setWhiteBalance(kelvin: Float?) {
    guard let kelvin = kelvin else {
      device.whiteBalanceMode = .continuousAutoWhiteBalance
      return
    }
    var gains = device.deviceWhiteBalanceGains(for: AVCaptureDevice.WhiteBalanceTemperatureAndTintValues(temperature: kelvin, tint: 0))
    let maximum = device.maxWhiteBalanceGain
    gains.redGain = min(max(gains.redGain, 1), maximum)
    gains.greenGain = min(max(gains.greenGain, 1), maximum)
    gains.blueGain = min(max(gains.blueGain, 1), maximum)
    device.setWhiteBalanceModeLocked(with: gains, completionHandler: nil)
  }

  func lockWhiteBalance() { device.whiteBalanceMode = .locked }

  func setTorch(_ on: Bool) throws {
    if on {
      try device.setTorchModeOn(level: AVCaptureDevice.maxAvailableTorchLevel)
    } else {
      device.torchMode = .off
    }
  }
}
