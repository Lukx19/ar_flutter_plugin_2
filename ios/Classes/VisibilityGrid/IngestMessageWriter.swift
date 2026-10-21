import Foundation
import simd

/// Fixed-size, little-endian byte buffer allocated once (no per-message
/// allocation). Producers stage messages here; the pull handler copies them
/// into its own slots.
final class IngestMessageBuffer {
  let capacity: Int
  let bytes: UnsafeMutableRawPointer

  init(capacity: Int) {
    self.capacity = capacity
    bytes = UnsafeMutableRawPointer.allocate(byteCount: capacity, alignment: 16)
    bytes.initializeMemory(as: UInt8.self, repeating: 0, count: capacity)
  }

  deinit { bytes.deallocate() }

  @inline(__always) func u8(_ o: Int, _ v: Int) { bytes.storeBytes(of: UInt8(truncatingIfNeeded: v), toByteOffset: o, as: UInt8.self) }
  @inline(__always) func u16(_ o: Int, _ v: Int) { bytes.storeBytes(of: UInt16(truncatingIfNeeded: v).littleEndian, toByteOffset: o, as: UInt16.self) }
  @inline(__always) func i32(_ o: Int, _ v: Int32) { bytes.storeBytes(of: v.littleEndian, toByteOffset: o, as: Int32.self) }
  @inline(__always) func u32(_ o: Int, _ v: UInt32) { bytes.storeBytes(of: v.littleEndian, toByteOffset: o, as: UInt32.self) }
  @inline(__always) func i64(_ o: Int, _ v: Int64) { bytes.storeBytes(of: v.littleEndian, toByteOffset: o, as: Int64.self) }
  @inline(__always) func f32(_ o: Int, _ v: Float) { bytes.storeBytes(of: v.bitPattern.littleEndian, toByteOffset: o, as: UInt32.self) }

  @inline(__always) func readU8(_ o: Int) -> Int { Int(bytes.load(fromByteOffset: o, as: UInt8.self)) }
  @inline(__always) func readU16(_ o: Int) -> Int { Int(UInt16(littleEndian: bytes.loadUnaligned(fromByteOffset: o, as: UInt16.self))) }
  @inline(__always) func readI32(_ o: Int) -> Int32 { Int32(littleEndian: bytes.loadUnaligned(fromByteOffset: o, as: Int32.self)) }
  @inline(__always) func readU32(_ o: Int) -> UInt32 { UInt32(littleEndian: bytes.loadUnaligned(fromByteOffset: o, as: UInt32.self)) }
  @inline(__always) func readI64(_ o: Int) -> Int64 { Int64(littleEndian: bytes.loadUnaligned(fromByteOffset: o, as: Int64.self)) }
  @inline(__always) func readF32(_ o: Int) -> Float { Float(bitPattern: readU32(o)) }

  func zero(_ offset: Int, _ count: Int) {
    (bytes + offset).initializeMemory(as: UInt8.self, repeating: 0, count: count)
  }

  /// Copies `source[0, length)` to the start of this buffer.
  func copy(from source: UnsafeRawPointer, length: Int) {
    precondition(length <= capacity)
    bytes.copyMemory(from: source, byteCount: length)
  }

  /// A copy of the first [length] bytes (tests and diagnostics; allocates).
  func data(_ length: Int) -> Data { Data(bytes: bytes, count: length) }
}

/// Writes the 160 B ingest message header (contract 2.4) at absolute offsets.
enum IngestMessageWriter {
  /// Writes magic and version and zeroes every other header byte.
  static func begin(_ b: IngestMessageBuffer, kind: Int) {
    b.zero(0, IngestMessageLayout.headerBytes)
    b.u32(0, IngestMessageLayout.magic)
    b.u16(4, IngestMessageLayout.version)
    b.u8(IngestMessageLayout.kind, kind)
  }

  static func kind(_ b: IngestMessageBuffer) -> Int { b.readU8(IngestMessageLayout.kind) }

  static func timing(_ b: IngestMessageBuffer, sampleNs: Int64, frameNs: Int64, acquireNs: Int64) {
    b.i64(IngestMessageLayout.sampleTimestampNs, sampleNs)
    b.i64(IngestMessageLayout.frameTimestampNs, frameNs)
    b.i64(IngestMessageLayout.acquireTimeNs, acquireNs)
  }

  static func sourceFlags(_ b: IngestMessageBuffer, _ flags: Int) { b.u8(IngestMessageLayout.sourceFlags, flags) }

  static func payloadBytes(_ b: IngestMessageBuffer, _ bytes: Int) {
    b.u32(IngestMessageLayout.payloadBytes, UInt32(bytes))
  }

  static func payloadBytesOf(_ b: IngestMessageBuffer) -> Int { Int(b.readU32(IngestMessageLayout.payloadBytes)) }

  static func sequence(_ b: IngestMessageBuffer, _ sequence: UInt32) { b.u32(IngestMessageLayout.sequence, sequence) }

  static func sendTime(_ b: IngestMessageBuffer, _ ns: Int64) { b.i64(IngestMessageLayout.sendTimeNs, ns) }

  static func dropped(_ b: IngestMessageBuffer, _ count: UInt32) { b.u32(IngestMessageLayout.dropped, count) }

  /// Pose fields; [cameraToWorld] is written column-major (simd's storage order).
  /// Header pose from the ARKit camera transform [m]; written as
  /// `T_world_cam` (contract version 2).
  static func pose(
    _ b: IngestMessageBuffer, trackingState: Int, poseMatch: Int, groupEpoch: Int, poseGapUs: Int32,
    cameraToWorld m: simd_float4x4
  ) {
    b.u8(IngestMessageLayout.trackingState, trackingState)
    b.u8(IngestMessageLayout.poseMatch, poseMatch)
    b.u16(IngestMessageLayout.groupEpoch, groupEpoch)
    b.i32(IngestMessageLayout.poseGapUs, poseGapUs)
    writeColumnMajor(b, IngestMessageLayout.cameraToWorld, openCv(rigidBottomRow(m)))
  }

  /// `T_world_arcam * diag(1, -1, -1, 1)`: the header pose of contract
  /// version 2 from the ARKit camera transform [m] (camera Y and Z columns
  /// negated, as `PoseBatchWire` and the Android writer).
  static func openCv(_ m: simd_float4x4) -> simd_float4x4 {
    // Multiply rather than negate: simd's vector negation can produce +0 for
    // a 0 lane, and the contract (as Android and Dart) stores -0.
    var r = m
    r.columns.1 = m.columns.1 * Float(-1)
    r.columns.2 = m.columns.2 * Float(-1)
    return r
  }

  /// The contract's camera-to-world is a rigid transform with an exact
  /// 0 0 0 1 bottom row (as ARCore writes it). ARKit's `camera.transform`
  /// can carry 0.99999994 in the corner, so the row is written exactly.
  /// An all-zero matrix (pose missing) stays zero.
  static func rigidBottomRow(_ m: simd_float4x4) -> simd_float4x4 {
    if m == simd_float4x4() { return m }
    var r = m
    r.columns.0.w = 0
    r.columns.1.w = 0
    r.columns.2.w = 0
    r.columns.3.w = 1
    return r
  }

  static func writeColumnMajor(_ b: IngestMessageBuffer, _ o: Int, _ m: simd_float4x4) {
    for c in 0..<4 {
      let col = m[c]
      for r in 0..<4 { b.f32(o + 4 * (c * 4 + r), col[r]) }
    }
  }

  static func intrinsics(_ b: IngestMessageBuffer, fx: Float, fy: Float, cx: Float, cy: Float) {
    b.f32(IngestMessageLayout.intrinsics, fx)
    b.f32(IngestMessageLayout.intrinsics + 4, fy)
    b.f32(IngestMessageLayout.intrinsics + 8, cx)
    b.f32(IngestMessageLayout.intrinsics + 12, cy)
  }

  static func image(
    _ b: IngestMessageBuffer, width: Int, height: Int, rowStrideBytes: Int, depthUnit: Int, confidenceFormat: Int
  ) {
    b.u16(IngestMessageLayout.width, width)
    b.u16(IngestMessageLayout.height, height)
    b.u16(IngestMessageLayout.rowStride, rowStrideBytes)
    b.u8(IngestMessageLayout.depthUnit, depthUnit)
    b.u8(IngestMessageLayout.confidenceFormat, confidenceFormat)
  }

  /// Total message length (header plus declared payload).
  static func messageBytes(_ b: IngestMessageBuffer) -> Int { IngestMessageLayout.headerBytes + payloadBytesOf(b) }
}

/// Saturating microseconds of a nanosecond gap.
func gapMicros(_ ns: Int64) -> Int32 {
  let us = ns / 1_000
  if us > Int64(Int32.max) { return Int32.max }
  if us < Int64(Int32.min) { return Int32.min }
  return Int32(us)
}
