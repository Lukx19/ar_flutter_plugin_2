import Foundation

/// The live durable route admits only assessed JPEGs above the shared production threshold.
/// A burst remains atomic: one unassessed or soft member rejects the whole shot, without
/// changing its image indices or promoting another image to the primary component.
enum StillImageQualityGate {
  static func outcome(for set: StillComponentSet) -> StillExposureOutcome {
    let scores = [set.metadata.sharpness] + (set.shot?.images.map { $0.metadata.sharpness } ?? [])
    let assessed = scores.compactMap { $0 }
    guard assessed.count == scores.count, assessed.allSatisfy({ $0.isFinite && $0 >= 0 }) else {
      return .failure(set.qualifier, "QUALITY_ANALYSIS_FAILED")
    }
    guard assessed.allSatisfy({ $0 >= LaplacianSharpness.productionThreshold }) else {
      return .failure(set.qualifier, "REJECTED_BLUR")
    }
    return .components(set)
  }
}
