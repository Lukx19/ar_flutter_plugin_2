import Foundation

/// Exposure mode of the capture device ARKit runs.
enum CameraExposureModeValue: Equatable {
  /// Continuous auto exposure.
  case auto
  /// Auto exposure held at its current values.
  case locked
  /// Manual ISO and exposure duration.
  case custom
}

/// Focus mode of the capture device ARKit runs.
enum CameraFocusModeValue: Equatable, Hashable {
  /// One auto-focus scan, then held.
  case auto
  case continuous
  /// Held at a lens position.
  case locked
}

/// The adjustable state of the camera ARKit runs
/// (`ARWorldTrackingConfiguration.configurableCaptureDeviceForPrimaryCamera`).
/// Every change goes through [configure] (`lockForConfiguration`) on the
/// running device: the AR session is never paused or reconfigured for it.
protocol CameraControlDevice: AnyObject {
  /// Stable identity of the physical device across per-view wrappers.
  var configurationIdentity: AnyHashable { get }
  var minISO: Float { get }
  var maxISO: Float { get }
  var iso: Float { get }
  var minExposureSeconds: Double { get }
  var maxExposureSeconds: Double { get }
  var exposureSeconds: Double { get }
  var exposureMode: CameraExposureModeValue { get }
  var supportsCustomExposure: Bool { get }
  var supportsLockedExposure: Bool { get }
  var minExposureBias: Float { get }
  var maxExposureBias: Float { get }
  var exposureBias: Float { get }

  var focusModes: Set<CameraFocusModeValue> { get }
  var focusMode: CameraFocusModeValue { get }
  /// 0 closest, 1 furthest (`AVCaptureDevice.lensPosition`).
  var lensPosition: Float { get }
  var supportsCustomLensPosition: Bool { get }
  var supportsFocusPoint: Bool { get }
  var isAdjustingFocus: Bool { get }

  var supportsLockedWhiteBalanceGains: Bool { get }
  var isWhiteBalanceAuto: Bool { get }
  var isAdjustingWhiteBalance: Bool { get }
  /// Correlated colour temperature of the current gains, in kelvin.
  var whiteBalanceTemperature: Float? { get }

  var hasTorch: Bool { get }
  var isTorchAvailable: Bool { get }
  var isTorchOn: Bool { get }

  /// Runs [change] inside `lockForConfiguration` / `unlockForConfiguration`.
  func configure(_ change: () throws -> Void) throws
  /// Manual exposure; nil keeps the current duration or ISO.
  func setCustomExposure(seconds: Double?, iso: Float?)
  func setExposureMode(_ mode: CameraExposureModeValue)
  func setExposureBias(_ bias: Float)
  func setFocusMode(_ mode: CameraFocusModeValue)
  func setLensPosition(_ position: Float)
  /// Normalized point of interest in the sensor image (0...1, origin top left).
  func setFocusPoint(x: Double, y: Double)
  /// nil returns to continuous auto white balance; otherwise gains for
  /// [kelvin] (tint 0), clamped to the device's gain range, locked.
  func setWhiteBalance(kelvin: Float?)
  /// Holds the current auto white balance gains.
  func lockWhiteBalance()
  func setTorch(_ on: Bool) throws
}

extension CameraControlDevice {
  var configurationIdentity: AnyHashable { AnyHashable(ObjectIdentifier(self)) }
}

/// A replacement view supersedes the previous device configuration owner.
/// The registry retains only identity values and tokens, never devices or
/// bridges. A lease removes only its own current token on destruction.
private final class CameraControlOwnership {
  private static let lock = NSLock()
  private static var current: [AnyHashable: UUID] = [:]
  private let identity: AnyHashable
  private let token = UUID()

  init(identity: AnyHashable) {
    self.identity = identity
    Self.lock.lock()
    Self.current[identity] = token
    Self.lock.unlock()
  }

  var isCurrent: Bool {
    Self.lock.lock(); defer { Self.lock.unlock() }
    return Self.current[identity] == token
  }

  deinit {
    Self.lock.lock(); defer { Self.lock.unlock() }
    if Self.current[identity] == token { Self.current.removeValue(forKey: identity) }
  }
}

/// A refused or failed camera control: the Android `CaptureSessionException`
/// codes (`CONTROL_UNSUPPORTED`, `CONTROL_UPDATE_FAILED`).
struct CameraControlError: Error, Equatable, CustomStringConvertible {
  static let unsupportedCode = "CONTROL_UNSUPPORTED"
  static let updateFailedCode = "CONTROL_UPDATE_FAILED"

  let code: String
  let message: String
  var description: String { "\(code): \(message)" }

  static func unsupported(_ message: String) -> CameraControlError { CameraControlError(code: unsupportedCode, message: message) }
  static func failed(_ message: String) -> CameraControlError { CameraControlError(code: updateFailedCode, message: message) }
}

/// The Android runtime camera controls of `arcapture_<id>` (`setISO` to
/// `setTorchEnabled`, `RuntimeExposureControlBridge`,
/// `RuntimeFocusControlBridge`, `RuntimeWhiteBalanceControlBridge`,
/// `RuntimeFlashControlBridge`) on the camera ARKit runs, with the Android
/// argument names, reply shapes and error codes. Values apply live to the
/// AR session's camera (ARKit keeps running and tracking); stills taken
/// afterwards use them. Differences from Android, by the platform:
/// exposure compensation moves in steps of 1/3 EV (AVFoundation takes any
/// bias); white balance presets are fixed colour temperatures and there is
/// no white balance point; flash for stills is not available while ARKit
/// runs, so the flash modes are `off` and `torch`. Caller-confined; live calls
/// run on the camera access queue.
final class CameraControlBridge {
  static let methods: Set<String> = [
    "setISO", "setExposureTime", "setAutoExposureEnabled", "getCurrentISO", "getCurrentExposureTime",
    "getSupportedISORange", "getSupportedExposureRange", "getCurrentExposureState", "getExposureCompensationInfo",
    "setExposureCompensation", "lockExposure", "unlockExposure", "setFocusDistance", "setAutofocusEnabled",
    "focusAtPoint", "getCurrentFocusState", "getSupportedFocusModes", "setFocusMode", "setWhiteBalanceMode",
    "setColorTemperature", "getCurrentWhiteBalanceState", "getSupportedColorTemperatureRange", "lockWhiteBalance",
    "unlockWhiteBalance", "setWhiteBalanceFromPoint", "getSupportedWhiteBalanceModes", "setFlashMode",
    "getCurrentFlashState", "isFlashAvailable", "setTorchEnabled", "getSupportedFlashModes",
  ]

  /// Exposure compensation step reported and applied (EV).
  static let exposureCompensationStepEv = 1.0 / 3.0
  /// Colour temperatures of the white balance presets (kelvin), the usual
  /// CIE illuminant values of the Camera2 AWB modes.
  static let whiteBalancePresets: [String: Float] = [
    "incandescent": 2850, "warmFluorescent": 3000, "fluorescent": 4150, "twilight": 4000,
    "daylight": 5500, "cloudyDaylight": 6500, "shade": 7500,
  ]
  /// Manual colour temperature range (kelvin) the gain conversion covers.
  static let colorTemperatureRange: ClosedRange<Int> = 2500...10000

  private let device: CameraControlDevice
  private let ownership: CameraControlOwnership
  private var whiteBalanceMode = "auto"
  private var manualTemperature: Int?
  private var focusRegion: (x: Double, y: Double)?

  init(device: CameraControlDevice) {
    self.device = device
    self.ownership = CameraControlOwnership(identity: device.configurationIdentity)
  }

  /// Handles one control method with the Android argument map; throws
  /// [CameraControlError] for a refusal or failure.
  func handle(_ method: String, _ arguments: [String: Any]) throws -> Any? {
    func number(_ key: String) throws -> Double {
      guard let value = arguments[key] as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID() else {
        throw CameraControlError(code: "CONFIG_INVALID", message: "\(key) is required")
      }
      return value.doubleValue
    }
    func flag(_ key: String) throws -> Bool {
      guard let value = arguments[key] as? Bool else { throw CameraControlError(code: "CONFIG_INVALID", message: "\(key) is required") }
      return value
    }
    func text(_ key: String) throws -> String {
      guard let value = arguments[key] as? String else { throw CameraControlError(code: "CONFIG_INVALID", message: "\(key) is required") }
      return value
    }
    switch method {
    case "setISO": return ["actualISO": try setISO(Int(try number("isoValue")))]
    case "setExposureTime":
      return ["actualExposureTimeMicroseconds": try setExposureTime(micros: Int64(try number("exposureTimeMicroseconds")))]
    case "setAutoExposureEnabled": return try setAutoExposureEnabled(try flag("enabled"))
    case "getCurrentISO": return Int(device.iso.rounded())
    case "getCurrentExposureTime": return Self.micros(device.exposureSeconds)
    case "getSupportedISORange": return try supportedISORange()
    case "getSupportedExposureRange": return try supportedExposureRange()
    case "getCurrentExposureState": return exposureState()
    case "getExposureCompensationInfo": return try exposureCompensationInfo()
    case "setExposureCompensation": return ["actualCompensation": try setExposureCompensation(try number("evStep"))]
    case "lockExposure": return try setExposureLocked(true)
    case "unlockExposure": return try setExposureLocked(false)
    case "setFocusDistance": return ["actualDistance": try setFocusDistance(try number("distance"))]
    case "setAutofocusEnabled": return try setAutofocusEnabled(try flag("enabled"))
    case "focusAtPoint": return try focusAtPoint(x: try number("x"), y: try number("y"))
    case "getCurrentFocusState": return focusState()
    case "getSupportedFocusModes": return supportedFocusModes()
    case "setFocusMode": return try setFocusMode(try text("mode"))
    case "setWhiteBalanceMode": return try setWhiteBalanceMode(try text("mode"))
    case "setColorTemperature":
      return ["actualColorTemperature": try setColorTemperature(Int(try number("colorTemperatureK")))]
    case "getCurrentWhiteBalanceState": return whiteBalanceState()
    case "getSupportedColorTemperatureRange": return try supportedColorTemperatureRange()
    case "lockWhiteBalance": return try setWhiteBalanceLocked(true)
    case "unlockWhiteBalance": return try setWhiteBalanceLocked(false)
    case "setWhiteBalanceFromPoint":
      _ = try number("x"); _ = try number("y")
      throw CameraControlError.unsupported("Point white balance is not supported on iOS (AVFoundation has no white balance point)")
    case "getSupportedWhiteBalanceModes": return supportedWhiteBalanceModes()
    case "setFlashMode": return try setFlashMode(try text("mode"))
    case "getCurrentFlashState": return flashState()
    case "isFlashAvailable": return device.hasTorch
    case "getSupportedFlashModes": return supportedFlashModes()
    case "setTorchEnabled": return try setFlashMode(try flag("enabled") ? "torch" : "off")
    default: throw CameraControlError.unsupported("\(method) is not a camera control")
    }
  }

  // MARK: Exposure

  static func micros(_ seconds: Double) -> Int64 { Int64((seconds * 1_000_000).rounded()) }

  /// The exposure range in whole microseconds inside the device's range:
  /// the minimum rounded up and the maximum rounded down, so a clamped value
  /// never leaves `activeFormat` (AVFoundation raises an exception for a
  /// duration outside it). Nil when no whole microsecond fits.
  static func microsRange(_ minimum: Double, _ maximum: Double) -> ClosedRange<Int64>? {
    let low = Int64((minimum * 1_000_000).rounded(.up)), high = Int64((maximum * 1_000_000).rounded(.down))
    return low <= high ? low...high : nil
  }

  private func supportedISORange() throws -> [Int] {
    guard device.supportsCustomExposure, device.maxISO >= device.minISO, device.minISO > 0 else {
      throw CameraControlError.unsupported("Runtime ISO control is not supported on this camera")
    }
    return [Int(device.minISO.rounded(.up)), Int(device.maxISO.rounded(.down))]
  }

  private func supportedExposureRange() throws -> [String: Int64] {
    guard device.supportsCustomExposure, device.minExposureSeconds > 0,
          let range = Self.microsRange(device.minExposureSeconds, device.maxExposureSeconds) else {
      throw CameraControlError.unsupported("Manual exposure time is not supported on this camera")
    }
    return ["min": range.lowerBound, "max": range.upperBound]
  }

  private func setISO(_ value: Int) throws -> Int {
    let range = try supportedISORange()
    let clamped = min(max(value, range[0]), range[1])
    try apply("ISO \(clamped)") { device.setCustomExposure(seconds: nil, iso: Float(clamped)) }
    return clamped
  }

  private func setExposureTime(micros: Int64) throws -> Int64 {
    let range = try supportedExposureRange()
    let clamped = min(max(micros, range["min"]!), range["max"]!)
    try apply("exposure time \(clamped)us") { device.setCustomExposure(seconds: Double(clamped) / 1_000_000, iso: nil) }
    return clamped
  }

  private func setAutoExposureEnabled(_ enabled: Bool) throws -> Bool {
    if enabled {
      try apply("auto exposure") { device.setExposureMode(.auto) }
    } else {
      guard device.supportsCustomExposure else { throw CameraControlError.unsupported("Manual exposure is not supported on this camera") }
      try apply("manual exposure") { device.setCustomExposure(seconds: nil, iso: nil) }
    }
    return device.exposureMode != .custom
  }

  private func exposureState() -> [String: Any?] {
    let auto = device.exposureMode != .custom
    return [
      "currentISO": Int(device.iso.rounded()),
      "currentExposureTime": Self.micros(device.exposureSeconds),
      "isAutoExposureEnabled": auto,
      "isExposureLocked": device.exposureMode == .locked,
      "exposureCompensation": Self.steppedBias(Double(device.exposureBias)),
      "exposureMode": auto ? "auto" : "manual",
    ]
  }

  private static func steppedBias(_ bias: Double) -> Double {
    (bias / exposureCompensationStepEv).rounded() * exposureCompensationStepEv
  }

  private func compensationSteps() throws -> ClosedRange<Int> {
    let step = Self.exposureCompensationStepEv
    let low = Int((Double(device.minExposureBias) / step).rounded(.up))
    let high = Int((Double(device.maxExposureBias) / step).rounded(.down))
    guard low <= high, low < high || low != 0 else {
      throw CameraControlError.unsupported("Exposure compensation is not supported on this camera")
    }
    return low...high
  }

  private func exposureCompensationInfo() throws -> [String: Double] {
    let steps = try compensationSteps()
    let step = Self.exposureCompensationStepEv
    return [
      "minCompensation": Double(steps.lowerBound) * step, "maxCompensation": Double(steps.upperBound) * step,
      "currentCompensation": Self.steppedBias(Double(device.exposureBias)), "stepSize": step,
    ]
  }

  private func setExposureCompensation(_ ev: Double) throws -> Double {
    let steps = try compensationSteps()
    let step = Self.exposureCompensationStepEv
    let requested = Int((ev / step).rounded())
    let clamped = min(max(requested, steps.lowerBound), steps.upperBound)
    let bias = Double(clamped) * step
    try apply("exposure compensation \(bias) EV") { device.setExposureBias(Float(bias)) }
    return bias
  }

  private func setExposureLocked(_ locked: Bool) throws -> Bool {
    guard device.supportsLockedExposure else { throw CameraControlError.unsupported("Exposure lock is not supported on this camera") }
    try apply(locked ? "exposure lock" : "exposure unlock") { device.setExposureMode(locked ? .locked : .auto) }
    return locked ? device.exposureMode == .locked : device.exposureMode != .locked
  }

  // MARK: Focus

  /// Android normalized distance (0 closest, 1 infinity) is the lens position.
  private func setFocusDistance(_ normalized: Double) throws -> Double {
    guard device.supportsCustomLensPosition else {
      throw CameraControlError.unsupported("Manual focus distance is not supported on this camera")
    }
    let clamped = min(max(normalized, 0), 1)
    try apply("focus distance") { device.setLensPosition(Float(clamped)) }
    focusRegion = nil
    return clamped
  }

  private func setAutofocusEnabled(_ enabled: Bool) throws -> Bool {
    let mode: CameraFocusModeValue = enabled ? (device.focusModes.contains(.continuous) ? .continuous : .auto) : .locked
    guard device.focusModes.contains(mode) else {
      throw CameraControlError.failed("Failed to update autofocus mode")
    }
    try apply("autofocus mode") { device.setFocusMode(mode) }
    return device.focusMode != .locked
  }

  private func focusAtPoint(x: Double, y: Double) throws -> Bool {
    guard device.supportsFocusPoint, device.focusModes.contains(.auto) || device.focusModes.contains(.continuous) else {
      throw CameraControlError.unsupported("Point focus is not supported on this camera")
    }
    let point = (x: min(max(x, 0), 1), y: min(max(y, 0), 1))
    try apply("focus point") {
      device.setFocusPoint(x: point.x, y: point.y)
      device.setFocusMode(device.focusModes.contains(.auto) ? .auto : .continuous)
    }
    focusRegion = point
    return true
  }

  private func focusModeName(_ mode: CameraFocusModeValue) -> String {
    switch mode {
    case .auto: return "auto"
    case .continuous: return "continuous"
    case .locked: return device.lensPosition >= 1 ? "infinity" : "fixed"
    }
  }

  private func supportedFocusModes() -> [String] {
    var names = Set<String>()
    if device.focusModes.contains(.auto) { names.insert("auto") }
    if device.focusModes.contains(.continuous) { names.insert("continuous") }
    if device.focusModes.contains(.locked) && device.supportsCustomLensPosition {
      names.insert("fixed")
      names.insert("infinity")
    }
    return names.sorted()
  }

  private func setFocusMode(_ name: String) throws -> Bool {
    guard supportedFocusModes().contains(name) else {
      throw CameraControlError.unsupported("Focus mode \(name) is not supported on this camera")
    }
    try apply("focus mode \(name)") {
      switch name {
      case "auto": device.setFocusMode(.auto)
      case "continuous": device.setFocusMode(.continuous)
      case "infinity": device.setLensPosition(1)
      default: device.setLensPosition(device.lensPosition)
      }
    }
    return true
  }

  private func focusState() -> [String: Any?] {
    let region: [String: Double]? = focusRegion.map { point in
      let half = 0.075
      let left = min(max(point.x - half, 0), 1 - 2 * half), top = min(max(point.y - half, 0), 1 - 2 * half)
      return ["left": left, "top": top, "width": 2 * half, "height": 2 * half]
    }
    return [
      "currentFocusDistance": device.supportsCustomLensPosition ? Double(device.lensPosition) : nil,
      "isAutofocusEnabled": device.focusMode != .locked,
      "currentFocusMode": focusModeName(device.focusMode),
      "isFocusLocked": device.focusMode == .locked,
      "isFocusPeakingEnabled": false,
      "focusStatus": device.isAdjustingFocus
        ? "scanning" : device.focusMode == .locked || (device.focusMode == .auto && region != nil) ? "locked" : "inactive",
      "focusRegion": region,
    ]
  }

  // MARK: White balance

  private func supportedWhiteBalanceModes() -> [String] {
    var names: Set<String> = ["auto"]
    if device.supportsLockedWhiteBalanceGains { names.formUnion(Self.whiteBalancePresets.keys) }
    return names.sorted()
  }

  private func setWhiteBalanceMode(_ name: String) throws -> Bool {
    guard supportedWhiteBalanceModes().contains(name) else {
      throw CameraControlError.unsupported("White balance mode \(name) is not supported on this camera")
    }
    try apply("white balance mode \(name)") { device.setWhiteBalance(kelvin: Self.whiteBalancePresets[name]) }
    whiteBalanceMode = name
    manualTemperature = nil
    return true
  }

  private func supportedColorTemperatureRange() throws -> [String: Int] {
    guard device.supportsLockedWhiteBalanceGains else {
      throw CameraControlError.unsupported("Manual color temperature is not supported on this camera")
    }
    return ["min": Self.colorTemperatureRange.lowerBound, "max": Self.colorTemperatureRange.upperBound]
  }

  private func setColorTemperature(_ kelvin: Int) throws -> Int {
    let range = try supportedColorTemperatureRange()
    let clamped = min(max(kelvin, range["min"]!), range["max"]!)
    try apply("manual color temperature \(clamped)K") { device.setWhiteBalance(kelvin: Float(clamped)) }
    whiteBalanceMode = "manual"
    manualTemperature = clamped
    return clamped
  }

  private func setWhiteBalanceLocked(_ locked: Bool) throws -> Bool {
    guard whiteBalanceMode == "auto" else {
      throw CameraControlError.unsupported("White balance lock is not supported in manual color temperature mode")
    }
    guard device.supportsLockedWhiteBalanceGains else { throw CameraControlError.unsupported("White balance lock is not supported on this camera") }
    try apply(locked ? "white balance lock" : "white balance unlock") {
      if locked { device.lockWhiteBalance() } else { device.setWhiteBalance(kelvin: nil) }
    }
    return locked ? !device.isWhiteBalanceAuto : device.isWhiteBalanceAuto
  }

  private func whiteBalanceState() -> [String: Any?] {
    let temperature = manualTemperature ?? device.whiteBalanceTemperature.map { Int($0.rounded()) }
    let auto = whiteBalanceMode == "auto"
    return [
      "currentMode": whiteBalanceMode,
      "currentColorTemperature": temperature,
      "isWhiteBalanceLocked": auto && !device.isWhiteBalanceAuto,
      "isAutoWhiteBalanceEnabled": auto,
      "status": device.isAdjustingWhiteBalance ? "searching" : auto && device.isWhiteBalanceAuto ? "converged" : "locked",
    ]
  }

  // MARK: Flash

  private func setFlashMode(_ name: String) throws -> Bool {
    // A camera without a torch has nothing to switch: Android stages
    // `off` for any request; nothing on the device changes.
    if !device.hasTorch {
      guard CameraControlBridge.androidFlashModes.contains(name) else {
        throw CameraControlError.unsupported("Flash mode \(name) is not supported on this camera")
      }
      return true
    }
    guard name == "off" || name == "torch" else {
      throw CameraControlError.unsupported("Flash mode \(name) is not supported on iOS while ARKit runs (torch only)")
    }
    guard name == "off" || device.isTorchAvailable else {
      throw CameraControlError.failed("Failed to stage flash mode \(name): the torch is not available now")
    }
    try apply("flash mode \(name)") { try device.setTorch(name == "torch") }
    return true
  }

  private func flashState() -> [String: Any?] {
    [
      "currentFlashMode": device.isTorchOn ? "torch" : "off",
      "isTorchEnabled": device.isTorchOn,
      "isFlashReady": device.hasTorch && device.isTorchAvailable,
      "flashStatus": device.hasTorch ? (device.isTorchAvailable ? "ready" : "unavailable") : "unavailable",
    ]
  }

  static let androidFlashModes: Set<String> = ["off", "auto", "on", "redEyeReduction", "torch"]

  /// Flash modes [setFlashMode] accepts while ARKit runs: `off`, and `torch`
  /// when the camera has one (iOS only; Android stills take every mode).
  private func supportedFlashModes() -> [String] {
    device.hasTorch ? ["off", "torch"] : ["off"]
  }

  // MARK: Reset

  /// Returns the camera ARKit runs to its automatic state when the capture
  /// view closes: torch off, continuous auto exposure with no bias,
  /// continuous auto focus and continuous auto white balance, in one
  /// configuration lock. The device is shared by the process, so a later
  /// view would otherwise inherit manual settings or a lit torch.
  func reset() throws {
    try device.configure {
      // Check while holding the physical configuration lock: a replacement
      // either already owns the device, or applies its settings after this
      // lock is released. The retired view cannot overwrite either outcome.
      guard ownership.isCurrent else { return }
      if device.hasTorch { try device.setTorch(false) }
      device.setExposureMode(.auto)
      device.setExposureBias(0)
      if device.focusModes.contains(.continuous) { device.setFocusMode(.continuous) }
      device.setWhiteBalance(kelvin: nil)
    }
    whiteBalanceMode = "auto"
    manualTemperature = nil
    focusRegion = nil
  }

  // MARK: Configuration

  private func apply(_ what: String, _ change: () throws -> Void) throws {
    do {
      try device.configure {
        guard ownership.isCurrent else {
          throw CameraControlError.failed("The camera control owner was replaced")
        }
        try change()
      }
    } catch {
      throw CameraControlError.failed("Failed to apply \(what): \(error)")
    }
  }
}

/// Serial authority over physical still requests and runtime device configuration.
/// A durable attempt's terminal/cancellation does not cancel ARKit's physical
/// frame request. Keep ownership until its callback or a confirmed source stop.
/// Configuration waits off the main thread; encoding and durable publication do
/// not hold the camera, so a valid still is retained while controls take effect.
final class StillCameraAccess: ARFrameObserver {
  typealias FrameCompletion = (CaptureFrame?, Error?) -> Void
  /// A frame callback gets ten seconds. Expiry requests a real source pause;
  /// it never proves cancellation or releases the physical camera by itself.
  static let frameCallbackDeadline: TimeInterval = 10
  private final class DeadlineTicket {
    private let lock = NSLock()
    private var cancelled = false
    private var claimed = false
    func claimPause() -> Bool {
      lock.lock(); defer { lock.unlock() }
      guard !cancelled, !claimed else { return false }
      claimed = true
      return true
    }
    @discardableResult func cancel() -> Bool {
      lock.lock(); defer { lock.unlock() }
      cancelled = true
      return claimed
    }
  }
  private struct Recovery {
    var ticket: DeadlineTicket
    var observedStop = false
    var resume: (() -> Void)?
  }
  private enum Operation {
    case frame((@escaping FrameCompletion) -> Void, FrameCompletion)
    case control(() -> Void, () -> Void)
  }
  private let queue = DispatchQueue(label: "capture3d.still-camera-access", qos: .userInitiated)
  private var pending: [Operation] = []
  private var active: (generation: UInt64, completion: FrameCompletion, ticket: DeadlineTicket)?
  private var recovery: Recovery?
  private var requestRecoveryPause: (() -> (() -> Void)?)?
  private var generation: UInt64 = 0
  private var closed = false
  private var stopped: Bool
  private var reset: (() -> Void)?

  init(running: Bool = true) { stopped = !running }

  /// Configured before the first request. The handler runs on main and may
  /// return a source-qualified resume action after performing an actual pause.
  func setRecoveryHandler(_ handler: @escaping () -> (() -> Void)?) {
    queue.async { self.requestRecoveryPause = handler }
  }

  func capture(_ request: @escaping (@escaping FrameCompletion) -> Void,
               completion: @escaping FrameCompletion) {
    queue.async {
      self.pending.append(.frame(request, completion))
      self.drain()
    }
  }

  func configure(_ change: @escaping () -> Void, unavailable: @escaping () -> Void) {
    queue.async {
      self.pending.append(.control(change, unavailable))
      self.drain()
    }
  }

  /// Closing rejects queued work, but cannot establish physical cancellation.
  /// A final device reset waits for the frame callback or confirmed source stop.
  func close(reset: @escaping () -> Void) {
    queue.async {
      guard !self.closed else { return }
      self.closed = true
      self.reset = reset
      self.rejectPending(reason: "closed")
      if let current = self.active {
        // Logical callers finish now, while physical ownership is retained
        // until ARKit returns or the source confirms it stopped.
        let pauseClaimed = current.ticket.cancel()
        if !pauseClaimed { self.recovery = nil }
        self.active = (current.generation, { _, _ in }, current.ticket)
        current.completion(nil, StillCaptureFailure(reason: "closed"))
      } else { self.finishReset() }
    }
  }

  private func drain() {
    guard !closed else { rejectPending(reason: "closed"); return }
    guard !stopped else { rejectPending(reason: "camera-stopped"); return }
    guard active == nil, recovery == nil else { return }
    while !pending.isEmpty {
      switch pending.removeFirst() {
      case .control(let change, _): change()
      case .frame(let request, let completion):
        generation &+= 1
        let token = generation
        let ticket = DeadlineTicket()
        active = (token, completion, ticket)
        if requestRecoveryPause != nil {
          queue.asyncAfter(deadline: .now() + Self.frameCallbackDeadline) { [weak self, weak ticket] in
            guard let self = self, let ticket = ticket else { return }
            self.frameDeadlineExpired(token, ticket: ticket)
          }
        }
        request { frame, error in
          self.queue.async {
            guard let current = self.active, current.generation == token else { return }
            self.active = nil
            let pauseClaimed = current.ticket.cancel()
            if !pauseClaimed, self.recovery?.ticket === current.ticket { self.recovery = nil }
            current.completion(frame, error)
            if self.closed { self.finishReset() } else { self.drain() }
          }
        }
        return
      }
    }
  }

  private func rejectPending(reason: String) {
    let refused = pending
    pending.removeAll()
    for operation in refused {
      switch operation {
      case .frame(_, let completion): completion(nil, StillCaptureFailure(reason: reason))
      case .control(_, let unavailable): unavailable()
      }
    }
  }

  private func finishReset() {
    let action = reset
    reset = nil
    action?()
  }

  func frameSource(_ source: ARFrameSource, didUpdate frame: CaptureFrame) {}
  func frameSourceDidStop(_ source: ARFrameSource) { cameraStopped(confirmedPause: true) }
  func frameSourceWasInterrupted(_ source: ARFrameSource) { cameraStopped(confirmedPause: false) }
  func frameSourceInterruptionEnded(_ source: ARFrameSource) {
    if source.lifecycleState == .running { cameraStarted() }
  }
  func frameSource(_ source: ARFrameSource, didStart configuration: CaptureRunConfiguration, epoch: Int) {
    cameraStarted()
  }

  private func cameraStopped(confirmedPause: Bool) {
    queue.async {
      self.stopped = true
      let current = self.active
      self.active = nil
      let pauseClaimed = current?.ticket.cancel() ?? self.recovery?.ticket.cancel() ?? false
      if !pauseClaimed { self.recovery = nil }
      if confirmedPause, self.recovery != nil { self.recovery?.observedStop = true }
      self.rejectPending(reason: "camera-stopped")
      current?.completion(nil, StillCaptureFailure(reason: "camera-stopped"))
      if self.closed { self.finishReset() }
      self.finishRecoveryIfStopped()
    }
  }

  private func frameDeadlineExpired(_ generation: UInt64, ticket: DeadlineTicket) {
    guard !closed, !stopped, active?.generation == generation, active?.ticket === ticket,
          let requestPause = requestRecoveryPause else { return }
    recovery = Recovery(ticket: ticket)
    DispatchQueue.main.async { [weak self] in
      let resume = ticket.claimPause() ? requestPause() : nil
      self?.queue.async { [weak self] in
        guard let self = self, self.recovery?.ticket === ticket else { return }
        guard let resume = resume else {
          self.recovery = nil
          self.drain()
          return
        }
        self.recovery?.resume = resume
        self.finishRecoveryIfStopped()
      }
    }
  }

  private func finishRecoveryIfStopped() {
    guard let current = recovery, current.observedStop, let resume = current.resume else { return }
    recovery = nil
    DispatchQueue.main.async(execute: resume)
  }

  private func cameraStarted() {
    queue.async {
      guard !self.closed else { return }
      self.stopped = false
      self.drain()
    }
  }
}
