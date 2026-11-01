import Foundation

/// Quality assessment only, not a terminal capture/retention result.
/// Never stores images, world poses, image features or coordinates.
struct StillExposureAssessment: Codable {
  var attemptId: String? = nil
  var commitId: String? = nil
  var attemptOrdinal: Int64? = nil
  var lane: String
  var mode: String
  var format: String
  var exposureTimestampNs: Int64? = nil
  var exposureSeconds: Double? = nil
  var iso: Double? = nil
  var targetExposureSeconds: Double? = nil
  var targetISO: Float? = nil
  var sharpness: Double? = nil
  var minimumSharpness: Double = LaplacianSharpness.productionThreshold
  var assessment: String

  fileprivate func sanitized() -> StillExposureAssessment {
    var value = self
    func finite(_ x: Double?) -> Double? { x.flatMap { $0.isFinite ? $0 : nil } }
    value.attemptId = attemptId.map { String($0.prefix(128)) }
    value.commitId = commitId.map { String($0.prefix(128)) }
    value.lane = String(lane.prefix(24))
    value.mode = String(mode.prefix(24))
    value.format = String(format.prefix(16))
    value.assessment = String(assessment.prefix(64))
    value.exposureSeconds = finite(exposureSeconds)
    value.iso = finite(iso)
    value.targetExposureSeconds = finite(targetExposureSeconds)
    value.targetISO = targetISO.flatMap { $0.isFinite ? $0 : nil }
    value.sharpness = finite(sharpness)
    if !value.minimumSharpness.isFinite {
      value.minimumSharpness = LaplacianSharpness.productionThreshold
    }
    return value
  }
}

/// One bounded app-local diagnostic cache. A slow write retains just one latest
/// pending snapshot; neither camera admission nor quality waits for persistence.
final class StillExposureAssessments {
  typealias Writer = (Data, URL, @escaping () -> Void) -> Void
  static let maximumRecords = 64
  static let cacheFilename = "capture3d-still-exposure-assessments.json"
  static let shared = StillExposureAssessments(
    url: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
      .appendingPathComponent(cacheFilename))

  private struct Snapshot: Encodable {
    let format = "capture3d-still-exposure-assessments-v1"
    var assessments: [StillExposureAssessment]
  }
  private let lock = NSLock()
  private let queue = DispatchQueue(label: "capture3d.still-exposure-assessments", qos: .utility)
  private let writer: Writer
  let url: URL
  private var records: [StillExposureAssessment] = []
  private var pending: [StillExposureAssessment]?
  private var writing = false

  init(url: URL, writer: Writer? = nil) {
    self.url = url
    self.writer =
      writer ?? { bytes, path, completed in
        defer { completed() }
        do {
          try FileManager.default.createDirectory(
            at: path.deletingLastPathComponent(), withIntermediateDirectories: true)
          try bytes.write(to: path, options: .atomic)
        } catch { /* Diagnostic persistence cannot fail a capture. */  }
      }
  }

  func append(_ assessment: StillExposureAssessment) {
    lock.lock()
    records.append(assessment.sanitized())
    if records.count > Self.maximumRecords {
      records.removeFirst(records.count - Self.maximumRecords)
    }
    pending = records
    let start = !writing
    writing = true
    lock.unlock()
    if start { queue.async { self.writeLatest() } }
  }

  private func writeLatest() {
    lock.lock()
    guard let latest = pending else {
      writing = false
      lock.unlock()
      return
    }
    pending = nil
    lock.unlock()
    guard let bytes = try? JSONEncoder().encode(Snapshot(assessments: latest)) else {
      return queue.async { self.writeLatest() }
    }
    writer(bytes, url) { [weak self] in
      guard let self = self else { return }
      self.queue.async { self.writeLatest() }
    }
  }
}
