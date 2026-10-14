import Foundation
import simd

/// Kind 2 point cloud producer (contract 2.4, iOS mapping of plan section 3).
///
/// At most every [acquireIntervalNs] of frame time it copies
/// `ARFrame.rawFeaturePoints` (world space) into a staging buffer allocated
/// once: identifiers become their low 32 bits and confidence is 1.0 (ARKit
/// reports none; contract amendment requested). Like the Android producer it
/// drops and counts non-finite points, points beyond [farMetres], points
/// below [minConfidence] and points outside [nearRangeMetres] to
/// [farRangeMetres] from the camera of the frame.
final class PointCloudIngestProducer {
  static let acquireIntervalNs: Int64 = 125_000_000
  static let maxPoints = 8_192
  static let farMetres: Float = 10_000
  static let minConfidence: Float = 0.3
  static let nearRangeMetres: Float = 0.2
  static let farRangeMetres: Float = 4
  /// ARKit feature points carry no confidence.
  static let unreportedConfidence: Float = 1

  private let sink: IngestMessageSink
  private let staging = IngestMessageBuffer(
    capacity: IngestMessageLayout.headerBytes + IngestMessageLayout.pointCountBytes + maxPoints * IngestMessageLayout.pointBytes
  )

  /// Confidence gate; lowered only by wire tests whose goldens predate it.
  var minConfidence = PointCloudIngestProducer.minConfidence

  private var lastAcquireNs: Int64 = .min
  private var lastCloudNs: Int64 = .min

  private(set) var clouds = 0
  private(set) var published = 0
  private(set) var pointsIn = 0
  private(set) var pointsSent = 0
  private(set) var nonFiniteDropped = 0
  private(set) var farDropped = 0
  private(set) var lowConfidenceDropped = 0
  private(set) var rangeDropped = 0
  private(set) var truncated = 0
  private(set) var duplicateDropped = 0

  init(sink: IngestMessageSink) {
    self.sink = sink
  }

  func reset() {
    lastAcquireNs = .min
    lastCloudNs = .min
  }

  /// Acquires when due; [frame] is tracking.
  func onFrame(_ frame: IngestFrame, context: IngestSampleContext) {
    let ts = frame.timestampNs
    if lastAcquireNs != .min && ts - lastAcquireNs < Self.acquireIntervalNs && ts >= lastAcquireNs { return }
    let camera = frame.cameraToWorld.columns.3
    let present = frame.withFeaturePoints { points, ids in
      publish(points: points, identifiers: ids, confidences: nil,
              cameraPosition: simd_float3(camera.x, camera.y, camera.z), context: context)
    }
    if present {
      clouds += 1
      lastAcquireNs = ts
    }
  }

  /// Filters and writes one cloud and offers it. [confidences] nil means
  /// [unreportedConfidence] for every point.
  @discardableResult
  func publish(
    points: UnsafeBufferPointer<simd_float3>, identifiers: UnsafeBufferPointer<UInt64>,
    confidences: UnsafeBufferPointer<Float>?, cameraPosition: simd_float3, context: IngestSampleContext
  ) -> Bool {
    if context.sampleNs == lastCloudNs {
      duplicateDropped += 1
      return false
    }
    lastCloudNs = context.sampleNs
    var n = min(points.count, identifiers.count)
    if let c = confidences { n = min(n, c.count) }
    if n > Self.maxPoints {
      truncated += 1
      n = Self.maxPoints
    }
    let b = staging
    IngestMessageWriter.begin(b, kind: IngestMessageLayout.kindPointCloud)
    IngestMessageWriter.sourceFlags(b, IngestMessageLayout.sourcePoints)
    IngestMessageWriter.timing(b, sampleNs: context.sampleNs, frameNs: context.frameNs, acquireNs: context.acquireNs)
    IngestMessageWriter.pose(
      b, trackingState: context.trackingState, poseMatch: context.poseMatch, groupEpoch: context.groupEpoch,
      poseGapUs: context.poseGapUs, cameraToWorld: context.cameraToWorld
    )
    let near2 = Self.nearRangeMetres * Self.nearRangeMetres
    let far2 = Self.farRangeMetres * Self.farRangeMetres
    var o = IngestMessageLayout.headerBytes + IngestMessageLayout.pointCountBytes
    var kept = 0, nonFinite = 0, far = 0, low = 0, outOfRange = 0
    for i in 0..<n {
      let p = points[i]
      let c = confidences?[i] ?? Self.unreportedConfidence
      if !p.x.isFinite || !p.y.isFinite || !p.z.isFinite || !c.isFinite { nonFinite += 1; continue }
      if abs(p.x) > Self.farMetres || abs(p.y) > Self.farMetres || abs(p.z) > Self.farMetres { far += 1; continue }
      if c < minConfidence { low += 1; continue }
      let d = p - cameraPosition
      let d2 = simd_dot(d, d)
      if d2 < near2 || d2 > far2 { outOfRange += 1; continue }
      b.u32(o, UInt32(truncatingIfNeeded: identifiers[i]))
      b.f32(o + 4, p.x)
      b.f32(o + 8, p.y)
      b.f32(o + 12, p.z)
      b.f32(o + 16, c)
      o += IngestMessageLayout.pointBytes
      kept += 1
    }
    b.u32(IngestMessageLayout.headerBytes, UInt32(kept))
    IngestMessageWriter.payloadBytes(b, IngestMessageLayout.pointCountBytes + kept * IngestMessageLayout.pointBytes)
    pointsIn += n
    pointsSent += kept
    nonFiniteDropped += nonFinite
    farDropped += far
    lowConfidenceDropped += low
    rangeDropped += outOfRange
    let offered = sink.offer(b, length: IngestMessageWriter.messageBytes(b))
    if offered { published += 1 }
    return offered
  }
}
