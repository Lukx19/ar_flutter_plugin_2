import Foundation

/// Pose stability overlay of the grid renderer: draws the poses of photos
/// (camera frusta) and keyframes (small axis crosses) from pose marker
/// deltas (`C3PM`, plan 2.14 and 2.16 row 12) received on the display
/// channel, coloured by their stability state. Mirrors the Android
/// `PoseFrustumStyle`.
///
/// Message layout (`PoseMarkerDeltaLayout` in
/// `capture3d/lib/capture/visibility_grid/pose_stability/pose_tracking_messages.dart`):
/// 16 B header (magic u32, version u16, count u16, flags u32, reserved
/// u32), then 32 B records (id u32, kind u8, state u8 with bit 7 flash,
/// submap id u16, position f32 x3, rotation quaternion x y z w snorm16, age
/// s u16, last correction mm u16). A colour table message carries four
/// RGBA8 words (new, stabilizing, stable, flash) instead of records. The
/// submap id is ignored: positions are world coordinates (submap 0).
///
/// Threads: [onMessage] on the channel queue and [buildLines] on the render
/// thread share one short lock over a fixed table of [maxPoses] poses;
/// nothing allocates after construction.
final class PoseFrustumStyle: GridLineOverlay {
  static let magic = contractMagic("C3PM")
  static let version = 1
  static let headerBytes = 16
  static let recordBytes = 32
  static let maxMessageBytes = 65_536
  static let maxRecords = (maxMessageBytes - headerBytes) / recordBytes

  static let countOffset = 6
  static let flagsOffset = 8

  static let flagShowPhotos = 1
  static let flagShowKeyframes = 2
  static let flagFullRefresh = 4
  static let flagColourTable = 8

  static let idOffset = 0
  static let kindOffset = 4
  static let stateOffset = 5
  static let positionOffset = 8
  static let rotationOffset = 20

  static let kindPhoto = 1
  static let kindKeyframe = 2

  static let stateFresh = 0
  static let stateStabilizing = 1
  static let stateStable = 2
  static let stateRemoved = 3
  static let flashBit = 0x80

  static let defaultFresh: UInt32 = 0x9E9E_9EFF
  static let defaultStabilizing: UInt32 = 0xFFB3_00FF
  static let defaultStable: UInt32 = 0x43A0_47FF
  static let defaultFlash: UInt32 = 0xFFFF_FFFF

  static let maxPoses = 4096
  static let maxSegments = 5000
  static let maxDistanceM: Float = 8

  /// Photo frustum: far rectangle [frustumDepth] in front (-Z) with these half extents.
  static let frustumDepth: Float = 0.08
  static let frustumHalfWidth: Float = 0.05
  static let frustumHalfHeight: Float = 0.0375
  /// Keyframe cross: total length of each axis segment, centred on the position.
  static let crossLength: Float = 0.03
  /// Half period of the flash: the colour toggles every 250 ms (2 Hz).
  static let flashHalfPeriodNs: UInt64 = 250_000_000

  private static let segmentsPhoto = 8
  private static let segmentsKeyframe = 3
  private static let emptySlot = -1

  private let clockNs: () -> UInt64
  private let lock = NSLock()
  private var ids = [Int32](repeating: 0, count: PoseFrustumStyle.maxPoses)
  private var kinds = [UInt8](repeating: 0, count: PoseFrustumStyle.maxPoses)
  private var states = [UInt8](repeating: 0, count: PoseFrustumStyle.maxPoses)
  private var positions = [Float](repeating: 0, count: PoseFrustumStyle.maxPoses * 3)
  private var rotations = [Float](repeating: 0, count: PoseFrustumStyle.maxPoses * 4)
  private var count = 0
  // id -> slot, linear probing with backward-shift removal.
  private var indexKeys = [Int32](repeating: 0, count: PoseFrustumStyle.maxPoses * 2)
  private var indexSlots = [Int](repeating: PoseFrustumStyle.emptySlot, count: PoseFrustumStyle.maxPoses * 2)
  private let indexMask = PoseFrustumStyle.maxPoses * 2 - 1
  private var flags = 0
  private var colourFresh = PoseFrustumStyle.defaultFresh
  private var colourStabilizing = PoseFrustumStyle.defaultStabilizing
  private var colourStable = PoseFrustumStyle.defaultStable
  private var colourFlash = PoseFrustumStyle.defaultFlash
  private var corner = [Float](repeating: 0, count: 12)

  /// Records dropped because the table was full.
  private(set) var tableFullDrops = 0
  /// Segments not emitted because of the per-frame cap.
  private(set) var droppedSegments = 0
  private(set) var rejectedMessages = 0

  init(clockNs: @escaping () -> UInt64 = { clock_gettime_nsec_np(CLOCK_UPTIME_RAW) }) {
    self.clockNs = clockNs
  }

  var messageMagic: UInt32 { PoseFrustumStyle.magic }
  var greyOutside: Bool { false }
  func isInScope(_ x: Float, _ y: Float, _ z: Float) -> Bool { true }

  func onMessage(_ b: UnsafeRawBufferPointer) -> Bool {
    let length = b.count
    let valid = length >= PoseFrustumStyle.headerBytes && length <= PoseFrustumStyle.maxMessageBytes &&
      b.leU32(0) == PoseFrustumStyle.magic && b.le16(4) == PoseFrustumStyle.version
    lock.lock(); defer { lock.unlock() }
    guard valid else { return rejectLocked() }
    let n = b.le16(PoseFrustumStyle.countOffset)
    let messageFlags = Int(b.leU32(PoseFrustumStyle.flagsOffset))
    if messageFlags & PoseFrustumStyle.flagColourTable != 0 {
      guard length >= PoseFrustumStyle.headerBytes + 16 else { return rejectLocked() }
      // RGBA8 words stored as little-endian u32 (Kotlin `getInt`).
      colourFresh = b.leU32(PoseFrustumStyle.headerBytes)
      colourStabilizing = b.leU32(PoseFrustumStyle.headerBytes + 4)
      colourStable = b.leU32(PoseFrustumStyle.headerBytes + 8)
      colourFlash = b.leU32(PoseFrustumStyle.headerBytes + 12)
      return true
    }
    guard n <= PoseFrustumStyle.maxRecords, length >= PoseFrustumStyle.headerBytes + n * PoseFrustumStyle.recordBytes else {
      return rejectLocked()
    }
    flags = messageFlags
    if messageFlags & PoseFrustumStyle.flagFullRefresh != 0 { clearLocked() }
    for i in 0..<n { applyRecordLocked(b, PoseFrustumStyle.headerBytes + i * PoseFrustumStyle.recordBytes) }
    return true
  }

  private func rejectLocked() -> Bool {
    rejectedMessages += 1
    return false
  }

  private func applyRecordLocked(_ b: UnsafeRawBufferPointer, _ o: Int) {
    let id = Int32(truncatingIfNeeded: b.leI32(o + PoseFrustumStyle.idOffset))
    let state = b.le8(o + PoseFrustumStyle.stateOffset)
    var s = find(id)
    if state & ~PoseFrustumStyle.flashBit == PoseFrustumStyle.stateRemoved {
      if s >= 0 { removeLocked(s) }
      return
    }
    if s < 0 {
      if count == PoseFrustumStyle.maxPoses {
        tableFullDrops += 1
        return
      }
      s = count
      count += 1
      ids[s] = id
      insert(id, s)
    }
    kinds[s] = UInt8(b.le8(o + PoseFrustumStyle.kindOffset))
    states[s] = UInt8(state)
    positions[s * 3] = b.leF32(o + PoseFrustumStyle.positionOffset)
    positions[s * 3 + 1] = b.leF32(o + PoseFrustumStyle.positionOffset + 4)
    positions[s * 3 + 2] = b.leF32(o + PoseFrustumStyle.positionOffset + 8)
    let r = o + PoseFrustumStyle.rotationOffset
    var qx = Float(b.leI16(r)) / 32767, qy = Float(b.leI16(r + 2)) / 32767
    var qz = Float(b.leI16(r + 4)) / 32767, qw = Float(b.leI16(r + 6)) / 32767
    let norm = (qx * qx + qy * qy + qz * qz + qw * qw).squareRoot()
    if norm > 1e-6 {
      qx /= norm; qy /= norm; qz /= norm; qw /= norm
    } else {
      qx = 0; qy = 0; qz = 0; qw = 1
    }
    rotations[s * 4] = qx
    rotations[s * 4 + 1] = qy
    rotations[s * 4 + 2] = qz
    rotations[s * 4 + 3] = qw
  }

  /// Forgets every pose, the show flags and the colour table, as a new style.
  func reset() {
    lock.lock(); defer { lock.unlock() }
    clearLocked()
    flags = 0
    colourFresh = PoseFrustumStyle.defaultFresh
    colourStabilizing = PoseFrustumStyle.defaultStabilizing
    colourStable = PoseFrustumStyle.defaultStable
    colourFlash = PoseFrustumStyle.defaultFlash
  }

  private func clearLocked() {
    for i in indexSlots.indices { indexSlots[i] = PoseFrustumStyle.emptySlot }
    count = 0
  }

  private func removeLocked(_ s: Int) {
    let last = count - 1
    removeFromIndex(ids[s])
    if s != last {
      ids[s] = ids[last]
      kinds[s] = kinds[last]
      states[s] = states[last]
      for a in 0..<3 { positions[s * 3 + a] = positions[last * 3 + a] }
      for a in 0..<4 { rotations[s * 4 + a] = rotations[last * 4 + a] }
      indexSlots[position(ids[s])] = s
    }
    count -= 1
  }

  func buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink) -> Int {
    lock.lock(); defer { lock.unlock() }
    let showPhotos = flags & PoseFrustumStyle.flagShowPhotos != 0
    let showKeyframes = flags & PoseFrustumStyle.flagShowKeyframes != 0
    if !showPhotos && !showKeyframes { return 0 }
    let flashOn = (clockNs() / PoseFrustumStyle.flashHalfPeriodNs) & 1 == 0
    let maxD2 = PoseFrustumStyle.maxDistanceM * PoseFrustumStyle.maxDistanceM
    var segments = 0
    for s in 0..<count {
      let kind = Int(kinds[s])
      let needed: Int
      if kind == PoseFrustumStyle.kindPhoto && showPhotos {
        needed = PoseFrustumStyle.segmentsPhoto
      } else if kind == PoseFrustumStyle.kindKeyframe && showKeyframes {
        needed = PoseFrustumStyle.segmentsKeyframe
      } else {
        needed = 0
      }
      if needed == 0 { continue }
      let px = positions[s * 3], py = positions[s * 3 + 1], pz = positions[s * 3 + 2]
      let dx = px - camX, dy = py - camY, dz = pz - camZ
      if dx * dx + dy * dy + dz * dz > maxD2 { continue }
      if segments + needed > PoseFrustumStyle.maxSegments {
        droppedSegments += needed
        continue
      }
      let rgba = colour(Int(states[s]), flashOn)
      segments += kind == PoseFrustumStyle.kindPhoto
        ? emitFrustum(s, px, py, pz, rgba, sink)
        : emitCross(s, px, py, pz, rgba, sink)
    }
    return segments
  }

  private func colour(_ state: Int, _ flashOn: Bool) -> UInt32 {
    if state & PoseFrustumStyle.flashBit != 0 && flashOn { return colourFlash }
    switch state & ~PoseFrustumStyle.flashBit {
    case PoseFrustumStyle.stateStabilizing: return colourStabilizing
    case PoseFrustumStyle.stateStable: return colourStable
    default: return colourFresh
    }
  }

  /// Rotates local (lx, ly, lz) by the pose of slot [s] into [corner] at [o] (without translation).
  private func rotate(_ s: Int, _ lx: Float, _ ly: Float, _ lz: Float, _ o: Int) {
    let qx = rotations[s * 4], qy = rotations[s * 4 + 1], qz = rotations[s * 4 + 2], qw = rotations[s * 4 + 3]
    // v' = v + 2 q_w (q x v) + 2 q x (q x v)
    let tx = 2 * (qy * lz - qz * ly)
    let ty = 2 * (qz * lx - qx * lz)
    let tz = 2 * (qx * ly - qy * lx)
    corner[o] = lx + qw * tx + (qy * tz - qz * ty)
    corner[o + 1] = ly + qw * ty + (qz * tx - qx * tz)
    corner[o + 2] = lz + qw * tz + (qx * ty - qy * tx)
  }

  /// Camera frustum (x right, y up, looking along -Z): apex at the position,
  /// far rectangle [frustumDepth] in front; four apex and four rim edges.
  private func emitFrustum(_ s: Int, _ px: Float, _ py: Float, _ pz: Float, _ rgba: UInt32, _ sink: GridOverlayLineSink) -> Int {
    let w = PoseFrustumStyle.frustumHalfWidth, h = PoseFrustumStyle.frustumHalfHeight, d = PoseFrustumStyle.frustumDepth
    rotate(s, -w, -h, -d, 0)
    rotate(s, w, -h, -d, 3)
    rotate(s, w, h, -d, 6)
    rotate(s, -w, h, -d, 9)
    for i in 0..<4 {
      let o = i * 3
      sink.line(px, py, pz, px + corner[o], py + corner[o + 1], pz + corner[o + 2], rgba: rgba)
    }
    for i in 0..<4 {
      let a = i * 3
      let b = ((i + 1) & 3) * 3
      sink.line(
        px + corner[a], py + corner[a + 1], pz + corner[a + 2],
        px + corner[b], py + corner[b + 1], pz + corner[b + 2], rgba: rgba)
    }
    return PoseFrustumStyle.segmentsPhoto
  }

  /// Keyframe: three segments of [crossLength] along the rotated local axes.
  private func emitCross(_ s: Int, _ px: Float, _ py: Float, _ pz: Float, _ rgba: UInt32, _ sink: GridOverlayLineSink) -> Int {
    let h = PoseFrustumStyle.crossLength * 0.5
    rotate(s, h, 0, 0, 0)
    rotate(s, 0, h, 0, 3)
    rotate(s, 0, 0, h, 6)
    for i in 0..<3 {
      let o = i * 3
      sink.line(
        px - corner[o], py - corner[o + 1], pz - corner[o + 2],
        px + corner[o], py + corner[o + 1], pz + corner[o + 2], rgba: rgba)
    }
    return PoseFrustumStyle.segmentsKeyframe
  }

  // MARK: id index

  private func mix(_ key: Int32) -> Int {
    let h = UInt32(bitPattern: key) &* 0x9E37_79B9
    return Int(h ^ (h >> 16)) & indexMask
  }

  private func position(_ id: Int32) -> Int {
    var i = mix(id)
    while indexSlots[i] != PoseFrustumStyle.emptySlot {
      if indexKeys[i] == id { return i }
      i = (i + 1) & indexMask
    }
    return -1
  }

  private func find(_ id: Int32) -> Int {
    let p = position(id)
    return p < 0 ? -1 : indexSlots[p]
  }

  private func insert(_ id: Int32, _ slot: Int) {
    var i = mix(id)
    while indexSlots[i] != PoseFrustumStyle.emptySlot { i = (i + 1) & indexMask }
    indexKeys[i] = id
    indexSlots[i] = slot
  }

  private func removeFromIndex(_ id: Int32) {
    var hole = position(id)
    if hole < 0 { return }
    var i = (hole + 1) & indexMask
    while indexSlots[i] != PoseFrustumStyle.emptySlot {
      let ideal = mix(indexKeys[i])
      let move = hole <= i ? (ideal <= hole || ideal > i) : (ideal <= hole && ideal > i)
      if move {
        indexKeys[hole] = indexKeys[i]
        indexSlots[hole] = indexSlots[i]
        hole = i
      }
      i = (i + 1) & indexMask
    }
    indexSlots[hole] = PoseFrustumStyle.emptySlot
  }

  /// Poses held (diagnostics and tests).
  var poseCount: Int {
    lock.lock(); defer { lock.unlock() }
    return count
  }

  /// Current colours new, stabilizing, stable, flash (tests).
  func colour(at index: Int) -> UInt32 {
    lock.lock(); defer { lock.unlock() }
    switch index {
    case 0: return colourFresh
    case 1: return colourStabilizing
    case 2: return colourStable
    default: return colourFlash
    }
  }
}
