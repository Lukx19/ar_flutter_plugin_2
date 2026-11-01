import Foundation

/// Actual metering from one recent camera frame, in the monotonic uptime clock.
struct AdaptiveStillExposureMetering {
  var timestamp: TimeInterval
  var exposureSeconds: Double
  var iso: Float
}

/// Facts of the currently active camera format. Unknown flicker is not permission
/// to shorten exposure, and a video-HDR owner retains its normal camera policy.
struct AdaptiveStillExposureCapabilities {
  var minISO: Float
  var maxISO: Float
  var minExposureSeconds: Double
  var maxExposureSeconds: Double
  var fixedAperture: Bool
  var videoHDREnabled: Bool
  var flickerDetected: Bool?
}

struct AdaptiveStillExposurePlan: Equatable {
  var exposureSeconds: Double
  var iso: Float
}

enum AdaptiveStillExposurePolicy {
  static let maximumMeteringAge: TimeInterval = 0.25
  static let targetExposureSeconds = 0.01
  static let maximumNoiseISO: Float = 1600

  /// Retains the complete metered ISO-duration product or leaves ordinary AE
  /// alone. Unsupported ranges, uncertain flicker and no ISO headroom are not
  /// permission to darken a photograph to meet the shutter target.
  static func plan(
    capabilities: AdaptiveStillExposureCapabilities?, metering: AdaptiveStillExposureMetering?,
    now: TimeInterval, ordinarySingle: Bool, automaticExposure: Bool,
    adjustingExposure: Bool
  ) -> AdaptiveStillExposurePlan? {
    guard ordinarySingle, automaticExposure, !adjustingExposure,
      let capabilities = capabilities, let metering = metering,
      capabilities.fixedAperture, !capabilities.videoHDREnabled,
      capabilities.flickerDetected == false,
      now.isFinite, metering.timestamp.isFinite, metering.timestamp > 0,
      now >= metering.timestamp, now - metering.timestamp <= maximumMeteringAge,
      capabilities.minISO.isFinite, capabilities.maxISO.isFinite,
      capabilities.minISO > 0, capabilities.maxISO >= capabilities.minISO,
      capabilities.minExposureSeconds.isFinite, capabilities.maxExposureSeconds.isFinite,
      capabilities.minExposureSeconds > 0,
      capabilities.maxExposureSeconds >= capabilities.minExposureSeconds,
      metering.exposureSeconds.isFinite, metering.iso.isFinite,
      (capabilities.minExposureSeconds...capabilities.maxExposureSeconds).contains(
        metering.exposureSeconds),
      (capabilities.minISO...capabilities.maxISO).contains(metering.iso),
      targetExposureSeconds >= capabilities.minExposureSeconds,
      targetExposureSeconds <= capabilities.maxExposureSeconds,
      targetExposureSeconds < metering.exposureSeconds
    else { return nil }
    let requested = Double(metering.iso) * metering.exposureSeconds / targetExposureSeconds
    let ceiling = min(capabilities.maxISO, maximumNoiseISO)
    guard requested.isFinite, requested >= Double(metering.iso), requested <= Double(ceiling) else {
      return nil
    }
    // Round upward to the reported whole ISO so float conversion cannot make
    // the metered exposure darker. The hardware ceiling still applies.
    let iso = Float(requested.rounded(.up))
    guard iso.isFinite, iso <= ceiling, iso >= capabilities.minISO else { return nil }
    return AdaptiveStillExposurePlan(exposureSeconds: targetExposureSeconds, iso: iso)
  }
}

/// Confined to the same scheduler as runtime camera controls.
final class AdaptiveStillExposurePreparation {
  var replied = false
  var ended = false
  var configurationFinished = false
  var acknowledged = false
}

/// All callbacks use the physical camera owner's existing serial queue. The
/// deterministic fake scheduler exercises deadlines without wall-clock sleeps.
protocol AdaptiveStillExposureScheduler: AnyObject {
  var now: TimeInterval { get }
  func perform(_ action: @escaping () -> Void)
  func after(_ delay: TimeInterval, _ action: @escaping () -> Void)
}

/// Finishing a lease restores only the still-owned temporary exposure, never
/// another manual control or a replacement physical camera owner.
final class AdaptiveStillExposureLease {
  let plan: AdaptiveStillExposurePlan
  private let end: () -> Void

  init(plan: AdaptiveStillExposurePlan, end: @escaping () -> Void) {
    self.plan = plan
    self.end = end
  }

  func finish() { end() }
}
