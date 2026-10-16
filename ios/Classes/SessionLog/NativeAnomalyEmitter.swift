import ARKit
import Foundation
import UIKit

/// Catalogue codes (`session_log_codes.dart`) the iOS shell records. Codes
/// shared with Android keep the Android data keys.
enum NativeSessionLogCodes {
  static let trackingLimited = "TRACKING_LIMITED"
  static let arSessionInterrupted = "AR_SESSION_INTERRUPTED"
  static let thermalThrottling = "THERMAL_THROTTLING"
  static let memoryPressure = "MEMORY_PRESSURE"
  static let backgroundTaskEnded = "BACKGROUND_TASK_ENDED"
  static let worldMapFailed = "WORLD_MAP_FAILED"
  static let worldMapRelocalized = "WORLD_MAP_RELOCALIZED"
  static let worldMapApplied = "WORLD_MAP_APPLIED"
}

/// Maps ARKit and system events to native session log records:
///
/// | Event | Code | Variant | Data |
/// |---|---|---|---|
/// | tracking becomes limited (on a change of reason) | `TRACKING_LIMITED` | reason | `reason` |
/// | `sessionWasInterrupted` / `sessionInterruptionEnded` | `AR_SESSION_INTERRUPTED` | `began` / `ended` | `phase`, `interruptedMs` on end |
/// | thermal state serious or critical | `THERMAL_THROTTLING` | `status3` / `status4` | `thermalStatus` (Android scale) |
/// | memory warning | `MEMORY_PRESSURE` | `memoryWarning` | `trimLevel: memoryWarning` |
///
/// Tracking and interruption callbacks arrive on the AR session delegate
/// queue; system notifications on the main thread. The mapping is pure
/// ([limitedReasonName], [thermalStatus]); the buffer is thread safe.
final class NativeAnomalyEmitter: ARFrameObserver {
  private let emitter: NativeSessionLogEmitter
  private let notificationCenter: NotificationCenter
  private let thermalState: () -> ProcessInfo.ThermalState
  private let nowNs: () -> UInt64
  private let lock = NSLock()
  private var lastLimitedReason: String?
  private var interruptedAtNs: UInt64?
  private var tokens: [NSObjectProtocol] = []

  init(
    emitter: NativeSessionLogEmitter = .shared,
    notificationCenter: NotificationCenter = .default,
    thermalState: @escaping () -> ProcessInfo.ThermalState = { ProcessInfo.processInfo.thermalState },
    nowNs: @escaping () -> UInt64 = { clock_gettime_nsec_np(CLOCK_UPTIME_RAW) }
  ) {
    self.emitter = emitter
    self.notificationCenter = notificationCenter
    self.thermalState = thermalState
    self.nowNs = nowNs
  }

  deinit { stopSystemObservers() }

  // MARK: Mapping

  /// Stable lower camel case name of a limited tracking reason.
  static func limitedReasonName(_ reason: ARCamera.TrackingState.Reason) -> String {
    switch reason {
    case .initializing: return "initializing"
    case .excessiveMotion: return "excessiveMotion"
    case .insufficientFeatures: return "insufficientFeatures"
    case .relocalizing: return "relocalizing"
    @unknown default: return "unknown"
    }
  }

  /// The Android thermal status (`PowerManager.THERMAL_STATUS_*`) closest to
  /// [state]: nominal 0, fair 1 (light), serious 3 (severe), critical 4.
  static func thermalStatus(_ state: ProcessInfo.ThermalState) -> Int {
    switch state {
    case .nominal: return 0
    case .fair: return 1
    case .serious: return 3
    case .critical: return 4
    @unknown default: return 4
    }
  }

  /// Android records thermal status moderate (2) and above.
  static let thermalRecordThreshold = 2

  // MARK: System observers

  func startSystemObservers() {
    lock.lock(); defer { lock.unlock() }
    guard tokens.isEmpty else { return }
    tokens.append(notificationCenter.addObserver(
      forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: nil
    ) { [weak self] _ in self?.thermalStateChanged() })
    tokens.append(notificationCenter.addObserver(
      forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: nil
    ) { [weak self] _ in self?.memoryWarning() })
  }

  func stopSystemObservers() {
    lock.lock()
    let removed = tokens
    tokens = []
    lock.unlock()
    removed.forEach(notificationCenter.removeObserver)
  }

  func thermalStateChanged() {
    let state = thermalState()
    let status = Self.thermalStatus(state)
    guard status >= Self.thermalRecordThreshold else { return }
    emitter.record(
      NativeSessionLogCodes.thermalThrottling,
      message: "Thermal status \(status)",
      data: ["thermalStatus": status],
      variant: "status\(status)"
    )
  }

  func memoryWarning() {
    emitter.record(
      NativeSessionLogCodes.memoryPressure,
      message: "System memory warning",
      data: ["trimLevel": "memoryWarning"],
      variant: "memoryWarning"
    )
  }

  // MARK: ARFrameObserver (session delegate queue)

  func frameSource(_ source: ARFrameSource, didUpdate frame: CaptureFrame) {}

  func frameSource(_ source: ARFrameSource, didStart configuration: CaptureRunConfiguration, epoch: Int) {
    lock.lock(); lastLimitedReason = nil; lock.unlock()
  }

  func frameSource(_ source: ARFrameSource, trackingChanged state: ARCamera.TrackingState) {
    trackingChanged(state)
  }

  func trackingChanged(_ state: ARCamera.TrackingState) {
    guard case .limited(let reason) = state else {
      lock.lock(); lastLimitedReason = nil; lock.unlock()
      return
    }
    let name = Self.limitedReasonName(reason)
    lock.lock()
    let changed = lastLimitedReason != name
    lastLimitedReason = name
    lock.unlock()
    guard changed else { return }
    emitter.record(
      NativeSessionLogCodes.trackingLimited,
      message: "AR tracking limited: \(name)",
      data: ["reason": name],
      variant: name
    )
  }

  func frameSourceWasInterrupted(_ source: ARFrameSource) { interruptionBegan() }

  func frameSourceInterruptionEnded(_ source: ARFrameSource) { interruptionEnded() }

  func interruptionBegan() {
    lock.lock(); interruptedAtNs = nowNs(); lock.unlock()
    emitter.record(
      NativeSessionLogCodes.arSessionInterrupted,
      message: "AR session interrupted",
      data: ["phase": "began"],
      variant: "began"
    )
  }

  func interruptionEnded() {
    lock.lock()
    let began = interruptedAtNs
    interruptedAtNs = nil
    let now = nowNs()
    lock.unlock()
    var data: [String: Any] = ["phase": "ended"]
    if let began = began { data["interruptedMs"] = Int((now &- began) / 1_000_000) }
    emitter.record(
      NativeSessionLogCodes.arSessionInterrupted,
      message: "AR session interruption ended",
      data: data,
      variant: "ended",
      level: .info
    )
  }
}
