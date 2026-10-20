import Foundation
import simd

/// Pose and timing of one sample, as written into the ingest header.
struct IngestSampleContext {
  var sampleNs: Int64
  var frameNs: Int64
  var acquireNs: Int64
  var trackingState: Int
  var groupEpoch: Int
  var cameraToWorld: simd_float4x4
  /// Depth and points belong to the `ARFrame` they arrive with, so ARKit
  /// samples are always an exact match with a zero gap.
  var poseMatch: Int = IngestMessageLayout.poseMatchExact
  var poseGapUs: Int32 = 0
}

/// Depth map size requested for the grid ingest (Foundation-only twin of the
/// session host's `ARDepthOutputSize`).
struct IngestDepthSize: Equatable {
  var width: Int
  var height: Int
}

/// Kind 1 depth image producer (contract 2.4, iOS mapping of plan section 3).
///
/// At most every [acquireIntervalNs] of frame time it converts the frame's
/// LiDAR depth (`ARFrame.sceneDepth`, f32 metres, 256x192) to u16
/// millimetres, downscaled to the capture view's [requestedSize] (default
/// 128x96, decision 5.2; see [blockFactor]) with a nearest-valid rule per block, and maps `ARConfidenceLevel`
/// low/medium/high to 0/128/255. Intrinsics are `ARCamera.intrinsics`
/// scaled from the camera image to the payload size. The staging buffer is
/// allocated once; the pull handler copies it on offer.
///
/// Source flags: bit 0 depth and bit 2 LiDAR-class; `sceneDepth` exists only
/// on LiDAR devices, so a device without LiDAR never sends depth.
final class DepthIngestProducer {
  static let acquireIntervalNs: Int64 = 250_000_000
  static let defaultDownscale = 2
  /// Medium confidence and up (contract byte) wins a block over low confidence.
  static let preferredConfidence: UInt8 = 128

  private let sink: IngestMessageSink
  private let staging = IngestMessageBuffer(capacity: IngestMessageLayout.maxMessageBytes)

  /// Block size of the downscale (1 keeps the full 256x192 map) when no
  /// [requestedSize] is set.
  var downscale = DepthIngestProducer.defaultDownscale

  /// Depth map size the capture view requested (`depthWidth`/`depthHeight`);
  /// set by the shell from the frame source under its frame lock.
  var requestedSize: IngestDepthSize?

  /// Block size for a [sourceWidth] x [sourceHeight] map: the largest integer
  /// factor whose output still covers [requestedSize] on both axes (128x96
  /// from 256x192 is 2, 256x192 is 1), or [downscale] without a request.
  func blockFactor(sourceWidth: Int, sourceHeight: Int) -> Int {
    guard let size = requestedSize, size.width > 0, size.height > 0 else { return max(1, downscale) }
    return max(1, min(sourceWidth / size.width, sourceHeight / size.height))
  }

  private var lastAcquireNs: Int64 = .min
  private var lastSampleNs: Int64 = .min

  // Counters, delegate queue writes; read for diagnostics.
  private(set) var acquired = 0
  private(set) var published = 0
  private(set) var duplicateDropped = 0
  private(set) var oversized = 0
  private(set) var calibrationUnavailable = 0
  private(set) var missing = 0
  private(set) var latestSampleNs: Int64 = .min

  init(sink: IngestMessageSink) {
    self.sink = sink
  }

  func reset() {
    lastAcquireNs = .min
    lastSampleNs = .min
  }

  /// Acquires when due; [frame] is tracking.
  func onFrame(_ frame: IngestFrame, mode: Int, context: IngestSampleContext) {
    guard mode != IngestDepthMode.disabled else { return }
    let ts = frame.timestampNs
    if lastAcquireNs != .min && ts - lastAcquireNs < Self.acquireIntervalNs && ts >= lastAcquireNs { return }
    var handled = false
    let present = frame.withDepth(mode: mode) { planes in
      handled = true
      publish(
        planes, intrinsics: frame.intrinsics, imageWidth: frame.imageWidth, imageHeight: frame.imageHeight,
        context: context, sourceFlags: IngestMessageLayout.sourceDepth | IngestMessageLayout.sourceLidarClass
      )
    }
    if !present || !handled {
      missing += 1
      return
    }
    acquired += 1
    lastAcquireNs = ts
  }

  /// Converts [planes] into the staging message and offers it. Returns false
  /// when the map was dropped (duplicate, oversized, no calibration).
  @discardableResult
  func publish(
    _ planes: DepthPlanes, intrinsics k: simd_float4, imageWidth: Int, imageHeight: Int,
    context: IngestSampleContext, sourceFlags: Int
  ) -> Bool {
    if context.sampleNs == lastSampleNs {
      duplicateDropped += 1
      return false
    }
    let s = blockFactor(sourceWidth: planes.width, sourceHeight: planes.height)
    let w = planes.width / s
    let h = planes.height / s
    let withConfidence = planes.confidence != nil
    let payload = w * h * 2 + (withConfidence ? w * h : 0)
    guard w > 0, h > 0, IngestMessageLayout.headerBytes + payload <= IngestMessageLayout.maxMessageBytes else {
      oversized += 1
      sink.countSourceDrop(kind: IngestMessageLayout.kindDepthImage)
      return false
    }
    // Multiply first, then divide: exact for the integer ratios of the camera
    // image. Pixel centres are at integer coordinates (OpenCV), so the
    // principal point scales as (c + 0.5) * s - 0.5.
    let fx = k.x * Float(w) / Float(imageWidth)
    let fy = k.y * Float(h) / Float(imageHeight)
    let cx = (k.z + 0.5) * Float(w) / Float(imageWidth) - 0.5
    let cy = (k.w + 0.5) * Float(h) / Float(imageHeight) - 0.5
    guard imageWidth > 0, imageHeight > 0, fx.isFinite, fx > 0, fy.isFinite, fy > 0, cx.isFinite, cy.isFinite else {
      calibrationUnavailable += 1
      sink.countSourceDrop(kind: IngestMessageLayout.kindDepthImage)
      return false
    }
    lastSampleNs = context.sampleNs
    latestSampleNs = context.sampleNs
    let b = staging
    IngestMessageWriter.begin(b, kind: IngestMessageLayout.kindDepthImage)
    IngestMessageWriter.sourceFlags(b, sourceFlags)
    IngestMessageWriter.payloadBytes(b, payload)
    IngestMessageWriter.timing(b, sampleNs: context.sampleNs, frameNs: context.frameNs, acquireNs: context.acquireNs)
    IngestMessageWriter.pose(
      b, trackingState: context.trackingState, poseMatch: context.poseMatch, groupEpoch: context.groupEpoch,
      poseGapUs: context.poseGapUs, cameraToWorld: context.cameraToWorld
    )
    IngestMessageWriter.intrinsics(b, fx: fx, fy: fy, cx: cx, cy: cy)
    IngestMessageWriter.image(
      b, width: w, height: h, rowStrideBytes: w * 2, depthUnit: IngestMessageLayout.depthUnitU16Mm,
      confidenceFormat: withConfidence ? IngestMessageLayout.confidenceU8 : IngestMessageLayout.confidenceNone
    )
    let depthOut = (b.bytes + IngestMessageLayout.headerBytes).assumingMemoryBound(to: UInt16.self)
    let confidenceOut = (b.bytes + IngestMessageLayout.headerBytes + w * h * 2).assumingMemoryBound(to: UInt8.self)
    Self.downscaleNearestValid(planes, factor: s, depthOut: depthOut, confidenceOut: withConfidence ? confidenceOut : nil)
    let offered = sink.offer(b, length: IngestMessageLayout.headerBytes + payload)
    if offered { published += 1 }
    return offered
  }

  /// Per output pixel, the nearest valid depth (finite, positive) of its
  /// `factor` x `factor` block among pixels of medium or high confidence;
  /// only when the block has none, the nearest valid pixel of any
  /// confidence. The output confidence is the mapped confidence of the chosen
  /// pixel; a block without valid depth writes depth 0 and confidence 0.
  /// Millimetres are rounded and clamped to 65,535. Little-endian host.
  static func downscaleNearestValid(
    _ planes: DepthPlanes, factor s: Int, depthOut: UnsafeMutablePointer<UInt16>,
    confidenceOut: UnsafeMutablePointer<UInt8>?
  ) {
    let w = planes.width / s
    let h = planes.height / s
    let table = planes.confidenceMapping.table
    table.withUnsafeBufferPointer { map in
      for y in 0..<h {
        for x in 0..<w {
          var best = Float.infinity
          var bestConfidence: UInt8 = 0
          var bestPreferred = false
          for dy in 0..<s {
            let py = y * s + dy
            let row = (planes.depth + py * planes.depthRowBytes).assumingMemoryBound(to: Float.self)
            let crow = planes.confidence.map { ($0 + py * planes.confidenceRowBytes).assumingMemoryBound(to: UInt8.self) }
            for dx in 0..<s {
              let px = x * s + dx
              let v = row[px]
              guard v.isFinite, v > 0 else { continue }
              let c: UInt8 = crow.map { map[Int($0[px])] } ?? 255
              let preferred = c >= preferredConfidence
              if (preferred && !bestPreferred) || (preferred == bestPreferred && v < best) {
                best = v
                bestConfidence = c
                bestPreferred = preferred
              }
            }
          }
          let i = y * w + x
          if best.isFinite {
            let mm = (best * 1000).rounded()
            depthOut[i] = (mm >= 65_535 ? 65_535 : UInt16(mm)).littleEndian
          } else {
            depthOut[i] = 0
            bestConfidence = 0
          }
          confidenceOut?[i] = bestConfidence
        }
      }
    }
  }
}
