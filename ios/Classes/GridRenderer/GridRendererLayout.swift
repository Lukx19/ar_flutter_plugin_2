import Foundation

// Byte layouts of the render and display channels (visibility grid plan
// section 2.7, amendments 2.16 rows 6, 12, 20 and 22), mirrored from the
// Android `visibilitycontract/VisibilityGridContractLayout.kt` and
// `gridrenderer/DisplayModeHandler.kt`. Every field is little-endian; the
// golden bytes shared with Dart and Kotlin live in
// `test_assets/visibility_grid/render_*.bin`, `display_mode.bin` and
// `colour_table.bin`.

enum GridRendererChannelNames {
  static let render = "capture3d/visibility_grid/render"
  static let display = "capture3d/visibility_grid/display"
}

enum RendererDeltaLayout {
  static let magic = contractMagic("C3RD")
  static let version = 1
  static let headerBytes = 32
  static let maxMessageBytes = 65_536
  static let kind = 6
  static let gridVersion = 8
  static let firstSlot = 12
  static let recordCount = 16
  static let bufferCapacity = 20
  static let brickKey = 24

  static let kindSurfaceSlice = 1
  static let kindBrickSummaries = 2
  static let kindOccupancyMasks = 3
  static let kindRegionSummaries = 4
  static let kindFreeSlots = 5
  static let kindReset = 6
  static let kindFrameEnd = 7
  static let kindCompactVoxels = 8

  static let occupancyMaskBytes = 64
  static let compactVoxelBytes = 8
  static let freeSlotRangeBytes = 8

  /// Byte size of one record of [kind], or -1 for an unknown kind.
  static func recordBytes(_ kind: Int) -> Int {
    switch kind {
    case kindSurfaceSlice: return SurfaceInstanceLayout.bytes
    case kindBrickSummaries: return BrickSummaryLayout.bytes
    case kindOccupancyMasks: return occupancyMaskBytes
    case kindRegionSummaries: return RegionSummaryLayout.bytes
    case kindFreeSlots: return freeSlotRangeBytes
    case kindReset, kindFrameEnd: return 0
    case kindCompactVoxels: return compactVoxelBytes
    default: return -1
    }
  }
}

/// Surface instance record, 40 B (2.7 as implemented, row 20 of 2.16).
enum SurfaceInstanceLayout {
  static let bytes = 40
  static let position = 0
  static let normal = 12
  static let sizeF16 = 16
  static let source = 18
  static let flags = 19
  static let coverage = 20
  static let completion = 21
  static let confidence = 22
  static let age = 23
  static let bins = 24
  static let stableId = 36
  static let flagPublished = 1
  static let flagPaged = 2
  static let flagSelected = 4
}

enum BrickSummaryLayout {
  static let bytes = 16
  static let origin = 0
  static let surfaceCount = 6
  static let completeCount = 8
  static let meanCoverage = 10
  static let residency = 11
  static let lastUpdate = 12
}

enum RegionSummaryLayout {
  static let bytes = 16
  static let key = 0
  static let brickCount = 6
  static let surfaceCount = 8
  static let completion = 12
  static let residency = 13
}

/// Display mode message (UI to platform), 32 B.
enum DisplayModeLayout {
  static let magic = contractMagic("C3DM")
  static let version = 1
  static let bytes = 32
  static let baseLayer = 6
  static let overlayBits = 7
  static let colourAttribute = 8
  static let sizeRule = 9
  static let spikeNearestK = 10
  static let pointSize = 12
  static let colourTableGeneration = 16
  /// u8 minimum surface quality (confidence byte); 0 draws every surface.
  static let minQuality = 20
}

/// Colour table message (UI to platform), 1,036 B: 12 B header, 256 RGBA8.
enum ColourTableLayout {
  static let magic = contractMagic("C3CT")
  static let version = 1
  static let headerBytes = 12
  static let entries = 256
  static let bytes = headerBytes + entries * 4
  static let attribute = 6
  static let generation = 8
}

/// Provisional planner target message on the display channel (`C3TG`): u16
/// version at 4, u8 count at 6, then 16 targets x 8 f32 (standpoint xyz,
/// view direction xyz, priority, reserved). Mirrors
/// `planner_target_message.dart`.
enum PlannerTargetMessageLayout {
  static let magic = contractMagic("C3TG")
  static let version = 1
  static let headerBytes = 8
  static let maxTargets = 16
  static let floatsPerTarget = 8
  static let bytes = headerBytes + maxTargets * floatsPerTarget * 4
  static let count = 6
}

/// Codes of the display mode message, mirroring the Dart contract.
enum GridDisplayCodes {
  static let basePoints = 0
  static let baseSplats = 1
  static let baseVoxels = 2
  static let baseNone = 3

  static let overlaySpikeBalls = 1
  static let overlayBricks = 2
  static let overlayRegions = 4
  static let overlayTargets = 8
  static let overlayMarkers = 16

  static let colourCoverage = 0
  static let colourConfidence = 1
  static let colourAge = 2
  static let colourSource = 3
  static let colourCompletion = 4
  static let colourAttributes = 5

  static let sizeFixed = 0
  static let sizeByCoverage = 1
  static let sizeByConfidence = 2
}

/// Render channel replies (2.12 row 3, 2.16 row 22).
enum GridRendererStatus {
  static let ok: Int32 = 0
  static let resyncRequired: Int32 = 1
  static let malformed: Int32 = 2
  static let noRenderer: Int32 = 3
  static let rendererFailed: Int32 = 4
}

/// Brick keys of the grid: signed 21-bit brick coordinates packed x highest,
/// mirroring `BrickKey` in the Dart grid contract.
enum GridBrickKey {
  private static let bias: Int64 = 0x100000
  private static let mask: Int64 = 0x1fffff
  static let brickMetres: Float = 0.8
  static let voxelMetres: Float = 0.1

  static func x(_ key: Int64) -> Int { Int(((key >> 42) & mask) - bias) }
  static func y(_ key: Int64) -> Int { Int(((key >> 21) & mask) - bias) }
  static func z(_ key: Int64) -> Int { Int((key & mask) - bias) }

  static func pack(_ x: Int, _ y: Int, _ z: Int) -> Int64 {
    ((Int64(x) + bias) << 42) | ((Int64(y) + bias) << 21) | (Int64(z) + bias)
  }
}

/// Little-endian reads from raw bytes; offsets need no alignment.
extension UnsafeRawBufferPointer {
  @inline(__always) func le8(_ o: Int) -> Int { Int(self[o]) }
  @inline(__always) func le16(_ o: Int) -> Int {
    Int(UInt16(littleEndian: loadUnaligned(fromByteOffset: o, as: UInt16.self)))
  }
  @inline(__always) func leI16(_ o: Int) -> Int {
    Int(Int16(littleEndian: loadUnaligned(fromByteOffset: o, as: Int16.self)))
  }
  @inline(__always) func leU32(_ o: Int) -> UInt32 {
    UInt32(littleEndian: loadUnaligned(fromByteOffset: o, as: UInt32.self))
  }
  @inline(__always) func leI32(_ o: Int) -> Int {
    Int(Int32(littleEndian: loadUnaligned(fromByteOffset: o, as: Int32.self)))
  }
  @inline(__always) func leI64(_ o: Int) -> Int64 {
    Int64(littleEndian: loadUnaligned(fromByteOffset: o, as: Int64.self))
  }
  @inline(__always) func leF32(_ o: Int) -> Float { Float(bitPattern: leU32(o)) }
}
