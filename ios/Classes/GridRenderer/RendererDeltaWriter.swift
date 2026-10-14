import Foundation

/// Writes renderer delta, display mode and colour table messages in the
/// contract format into one reusable buffer (the Swift twin of the Android
/// `RendererDeltaWriter`). Used by synthetic renderer scenes (style tests,
/// diagnostics); production deltas come from the Dart exporter.
final class RendererDeltaWriter {
  private(set) var bytes: [UInt8]
  private(set) var length = 0

  init(capacity: Int = RendererDeltaLayout.maxMessageBytes) {
    bytes = [UInt8](repeating: 0, count: capacity)
  }

  /// The current message.
  var message: Data { Data(bytes[0..<length]) }

  /// Runs [body] with the current message bytes.
  func withMessage<T>(_ body: (UnsafeRawBufferPointer) throws -> T) rethrows -> T {
    try bytes.withUnsafeBytes { try body(UnsafeRawBufferPointer(rebasing: $0[0..<length])) }
  }

  @discardableResult
  func begin(_ kind: Int, firstSlot: Int, records: Int, capacity: Int, brickKey: Int64, gridVersion: UInt32 = 1)
    -> RendererDeltaWriter {
    let size = RendererDeltaLayout.recordBytes(kind)
    length = RendererDeltaLayout.headerBytes + records * max(size, 0)
    for i in 0..<length { bytes[i] = 0 }
    put32(0, RendererDeltaLayout.magic)
    put16(4, UInt16(RendererDeltaLayout.version))
    bytes[RendererDeltaLayout.kind] = UInt8(kind)
    put32(RendererDeltaLayout.gridVersion, gridVersion)
    put32(RendererDeltaLayout.firstSlot, UInt32(bitPattern: Int32(firstSlot)))
    put32(RendererDeltaLayout.recordCount, UInt32(bitPattern: Int32(records)))
    put32(RendererDeltaLayout.bufferCapacity, UInt32(bitPattern: Int32(capacity)))
    put64(RendererDeltaLayout.brickKey, UInt64(bitPattern: brickKey))
    return self
  }

  /// Writes surface record [index] of the current kind 1 message.
  func surface(
    _ index: Int, _ x: Float, _ y: Float, _ z: Float, _ nx: Float, _ ny: Float, _ nz: Float,
    size: Float = 0.1, coverage: Int = 128, completion: Int = 1, confidence: Int = 200, age: Int = 0,
    source: Int = 1, flags: Int = SurfaceInstanceLayout.flagPublished, binQuality: Int = 8, stableId: Int? = nil
  ) {
    let o = RendererDeltaLayout.headerBytes + index * SurfaceInstanceLayout.bytes
    putF(o, x)
    putF(o + 4, y)
    putF(o + 8, z)
    let l1 = abs(nx) + abs(ny) + abs(nz)
    var ox = nx / l1
    var oy = ny / l1
    if nz < 0 {
      let fx = (1 - abs(oy)) * (ox >= 0 ? 1 : -1)
      let fy = (1 - abs(ox)) * (oy >= 0 ? 1 : -1)
      ox = fx
      oy = fy
    }
    put16(o + SurfaceInstanceLayout.normal, UInt16(bitPattern: Int16((min(max(ox, -1), 1) * 32767).rounded())))
    put16(o + SurfaceInstanceLayout.normal + 2, UInt16(bitPattern: Int16((min(max(oy, -1), 1) * 32767).rounded())))
    put16(o + SurfaceInstanceLayout.sizeF16, RendererDeltaWriter.halfBits(size))
    bytes[o + SurfaceInstanceLayout.source] = UInt8(truncatingIfNeeded: source)
    bytes[o + SurfaceInstanceLayout.flags] = UInt8(truncatingIfNeeded: flags)
    bytes[o + SurfaceInstanceLayout.coverage] = UInt8(truncatingIfNeeded: coverage)
    bytes[o + SurfaceInstanceLayout.completion] = UInt8(truncatingIfNeeded: completion)
    bytes[o + SurfaceInstanceLayout.confidence] = UInt8(truncatingIfNeeded: confidence)
    bytes[o + SurfaceInstanceLayout.age] = UInt8(truncatingIfNeeded: age)
    var word: UInt32 = 0
    for i in 0..<8 { word |= UInt32(binQuality & 15) << (4 * i) }
    for w in 0..<3 { put32(o + SurfaceInstanceLayout.bins + 4 * w, word) }
    put32(o + SurfaceInstanceLayout.stableId, UInt32(truncatingIfNeeded: stableId ?? index + 1))
  }

  /// Writes compact voxel entry [index] (padding when [colour] is negative).
  func voxel(_ index: Int, _ vx: Int, _ vy: Int, _ vz: Int, colour: Int) {
    let o = RendererDeltaLayout.headerBytes + index * RendererDeltaLayout.compactVoxelBytes
    if colour < 0 {
      for i in 0..<8 { bytes[o + i] = 0xFF }
      return
    }
    put16(o, UInt16(bitPattern: Int16(vx)))
    put16(o + 2, UInt16(bitPattern: Int16(vy)))
    put16(o + 4, UInt16(bitPattern: Int16(vz)))
    put16(o + 6, UInt16(truncatingIfNeeded: colour))
  }

  /// Writes brick summary [index] of the current kind 2 message.
  func brickSummary(_ index: Int, _ bx: Int, _ by: Int, _ bz: Int, surfaces: Int, complete: Int, meanCoverage: Int,
                    residency: Int) {
    let o = RendererDeltaLayout.headerBytes + index * BrickSummaryLayout.bytes
    put16(o, UInt16(bitPattern: Int16(bx)))
    put16(o + 2, UInt16(bitPattern: Int16(by)))
    put16(o + 4, UInt16(bitPattern: Int16(bz)))
    put16(o + 6, UInt16(truncatingIfNeeded: surfaces))
    put16(o + 8, UInt16(truncatingIfNeeded: complete))
    bytes[o + 10] = UInt8(truncatingIfNeeded: meanCoverage)
    bytes[o + 11] = UInt8(truncatingIfNeeded: residency)
    put32(o + 12, 1)
  }

  /// Writes region summary [index] of the current kind 4 message.
  func regionSummary(_ index: Int, _ rx: Int, _ ry: Int, _ rz: Int, bricks: Int, surfaces: Int, completion: Int,
                     residency: Int) {
    let o = RendererDeltaLayout.headerBytes + index * RegionSummaryLayout.bytes
    put16(o, UInt16(bitPattern: Int16(rx)))
    put16(o + 2, UInt16(bitPattern: Int16(ry)))
    put16(o + 4, UInt16(bitPattern: Int16(rz)))
    put16(o + 6, UInt16(truncatingIfNeeded: bricks))
    put32(o + 8, UInt32(truncatingIfNeeded: surfaces))
    bytes[o + 12] = UInt8(truncatingIfNeeded: completion)
    bytes[o + 13] = UInt8(truncatingIfNeeded: residency)
  }

  /// Writes free range [index] of the current kind 5 message.
  func freeRange(_ index: Int, first: Int, count: Int) {
    let o = RendererDeltaLayout.headerBytes + index * RendererDeltaLayout.freeSlotRangeBytes
    put32(o, UInt32(bitPattern: Int32(first)))
    put32(o + 4, UInt32(bitPattern: Int32(count)))
  }

  /// Writes a 32 B display mode message; returns its length.
  @discardableResult
  func displayMode(
    baseLayer: Int, overlayBits: Int, colourAttribute: Int, sizeRule: Int,
    spikeNearestK: Int = 512, pointSizePx: Float = 6, generation: UInt32 = 1, minQuality: Int = 0
  ) -> Int {
    for i in 0..<DisplayModeLayout.bytes { bytes[i] = 0 }
    put32(0, DisplayModeLayout.magic)
    put16(4, UInt16(DisplayModeLayout.version))
    bytes[DisplayModeLayout.baseLayer] = UInt8(baseLayer)
    bytes[DisplayModeLayout.overlayBits] = UInt8(overlayBits)
    bytes[DisplayModeLayout.colourAttribute] = UInt8(colourAttribute)
    bytes[DisplayModeLayout.sizeRule] = UInt8(sizeRule)
    put16(DisplayModeLayout.spikeNearestK, UInt16(spikeNearestK))
    putF(DisplayModeLayout.pointSize, pointSizePx)
    put32(DisplayModeLayout.colourTableGeneration, generation)
    bytes[DisplayModeLayout.minQuality] = UInt8(minQuality)
    length = DisplayModeLayout.bytes
    return length
  }

  /// Writes a colour table message filling every entry with [rgba]
  /// (0xRRGGBBAA); returns its length.
  @discardableResult
  func colourTable(attribute: Int, rgba: UInt32, generation: UInt32 = 1) -> Int {
    put32(0, ColourTableLayout.magic)
    put16(4, UInt16(ColourTableLayout.version))
    bytes[ColourTableLayout.attribute] = UInt8(attribute)
    bytes[7] = 0
    put32(ColourTableLayout.generation, generation)
    for i in 0..<ColourTableLayout.entries {
      let o = ColourTableLayout.headerBytes + 4 * i
      bytes[o] = UInt8(truncatingIfNeeded: rgba >> 24)
      bytes[o + 1] = UInt8(truncatingIfNeeded: rgba >> 16)
      bytes[o + 2] = UInt8(truncatingIfNeeded: rgba >> 8)
      bytes[o + 3] = UInt8(truncatingIfNeeded: rgba)
    }
    length = ColourTableLayout.bytes
    return length
  }

  private func put16(_ o: Int, _ v: UInt16) {
    bytes[o] = UInt8(truncatingIfNeeded: v)
    bytes[o + 1] = UInt8(truncatingIfNeeded: v >> 8)
  }

  private func put32(_ o: Int, _ v: UInt32) {
    for i in 0..<4 { bytes[o + i] = UInt8(truncatingIfNeeded: v >> (8 * UInt32(i))) }
  }

  private func put64(_ o: Int, _ v: UInt64) {
    for i in 0..<8 { bytes[o + i] = UInt8(truncatingIfNeeded: v >> (8 * UInt64(i))) }
  }

  private func putF(_ o: Int, _ v: Float) { put32(o, v.bitPattern) }

  /// IEEE binary16 bits, same rounding as the Dart contract codec.
  static func halfBits(_ v: Float) -> UInt16 {
    let bits = Int(v.bitPattern)
    let sign = (bits >> 16) & 0x8000
    let exp = ((bits >> 23) & 0xff) - 127 + 15
    let mant = bits & 0x7fffff
    if exp <= 0 { return UInt16(sign) }
    if exp >= 31 { return UInt16(sign | 0x7c00) }
    let rounded = ((exp << 10) | (mant >> 13)) + ((mant >> 12) & 1)
    return UInt16(sign | min(rounded, 0x7c00))
  }
}
