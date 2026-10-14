import Foundation

/// Marker outline overlay message (`C3MO`), written by the grid isolate's
/// marker stage (`MarkerOutlineLayout` in
/// `capture3d/lib/capture/visibility_grid/markers/marker_outline_feedback.dart`):
/// a 32 B header and 80 B entries.
enum MarkerOutlineLayout {
  static let magic = contractMagic("C3MO")
  static let version = 1
  static let headerBytes = 32
  static let entryBytes = 80
  static let count = 6
  static let markerId = 0
  static let state = 2
  static let flags = 3
  static let size = 4
  static let pose = 40
  static let ageMs = 68
  static let flagPoseValid = 2

  static let stateSeenNow = 1
  static let stateRejected = 2
  static let stateStable = 3
  static let stateUsedForAlignment = 4
  static let stateMoved = 5

  /// Most entries a message may carry (the Dart feedback default).
  static let maxEntries = 128
  static let maxBytes = headerBytes + maxEntries * entryBytes
}

/// Monotonic milliseconds (`CLOCK_UPTIME_RAW`, the ARKit frame clock base).
func gridOverlayUptimeMs() -> Int64 {
  Int64(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1_000_000)
}

/// Draws detected markers through the grid renderer's line overlay hook, so
/// the user sees when a marker is detected: a square of the printed size at
/// the marker pose and a short normal, coloured by state (seen now yellow,
/// rejected orange, stable green, used for alignment cyan, moved red).
/// Seen-now and rejected outlines disappear [flashMs] after their detection,
/// counted from the age the message reported at receipt, so the grid
/// isolate needs no clearing message. Entries without a valid pose are not
/// drawn. Messages are copied on the channel queue into a staging buffer and
/// swapped into the render buffer at the next frame; nothing allocates per
/// message or frame. Mirrors the Android `MarkerOutlineOverlay`.
final class MarkerOutlineOverlay: GridLineOverlay {
  static let defaultFlashMs: Int64 = 600
  static let segmentsPerMarker = 5
  private static let defaultSizeM: Float = 0.1
  private static let maxSizeM: Float = 2
  private static let normalFraction: Float = 0.5

  private let nowMs: () -> Int64
  private let flashMs: Int64
  private let lock = NSLock()
  private var staging = [UInt8](repeating: 0, count: MarkerOutlineLayout.maxBytes)
  private var stagingCount = 0
  private var stagingReceivedMs: Int64 = 0
  private var stagingVersion = 0
  private var shown = [UInt8](repeating: 0, count: MarkerOutlineLayout.maxBytes)
  private var shownCount = 0
  private var shownReceivedMs: Int64 = 0
  private var shownVersion = 0
  private var corner = [Float](repeating: 0, count: 12)
  private(set) var accepted = 0
  private(set) var rejected = 0

  init(nowMs: @escaping () -> Int64 = gridOverlayUptimeMs, flashMs: Int64 = MarkerOutlineOverlay.defaultFlashMs) {
    self.nowMs = nowMs
    self.flashMs = flashMs
  }

  var messageMagic: UInt32 { MarkerOutlineLayout.magic }
  var greyOutside: Bool { false }
  func isInScope(_ x: Float, _ y: Float, _ z: Float) -> Bool { true }

  func onMessage(_ b: UnsafeRawBufferPointer) -> Bool {
    let length = b.count
    guard length >= MarkerOutlineLayout.headerBytes, b.leU32(0) == MarkerOutlineLayout.magic,
          b.le16(4) == MarkerOutlineLayout.version else {
      lock.lock(); rejected += 1; lock.unlock()
      return false
    }
    let count = b.le16(MarkerOutlineLayout.count)
    let bytes = MarkerOutlineLayout.headerBytes + count * MarkerOutlineLayout.entryBytes
    guard count <= MarkerOutlineLayout.maxEntries, bytes <= length else {
      lock.lock(); rejected += 1; lock.unlock()
      return false
    }
    let received = nowMs()
    lock.lock()
    for i in 0..<bytes { staging[i] = b[i] }
    stagingCount = count
    stagingReceivedMs = received
    stagingVersion += 1
    accepted += 1
    lock.unlock()
    return true
  }

  /// Drops the outlines; the next [buildLines] shows none until a message arrives.
  func reset() {
    lock.lock(); defer { lock.unlock() }
    stagingCount = 0
    stagingVersion += 1
  }

  func buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink) -> Int {
    lock.lock()
    if stagingVersion != shownVersion {
      let bytes = MarkerOutlineLayout.headerBytes + stagingCount * MarkerOutlineLayout.entryBytes
      for i in 0..<bytes { shown[i] = staging[i] }
      shownCount = stagingCount
      shownReceivedMs = stagingReceivedMs
      shownVersion = stagingVersion
    }
    lock.unlock()
    let sinceReceipt = nowMs() - shownReceivedMs
    var segments = 0
    shown.withUnsafeBytes { s in
      for e in 0..<shownCount {
        let at = MarkerOutlineLayout.headerBytes + e * MarkerOutlineLayout.entryBytes
        let flags = s.le8(at + MarkerOutlineLayout.flags)
        if flags & MarkerOutlineLayout.flagPoseValid == 0 { continue }
        let state = s.le8(at + MarkerOutlineLayout.state)
        if state == MarkerOutlineLayout.stateSeenNow || state == MarkerOutlineLayout.stateRejected {
          let age = Int64(s.leU32(at + MarkerOutlineLayout.ageMs))
          if age + sinceReceipt > flashMs { continue }
        }
        segments += outline(s, at, MarkerOutlineOverlay.colour(of: state), sink)
      }
    }
    return segments
  }

  /// Square of the printed size in the marker plane plus a normal tick; returns the segments.
  private func outline(_ s: UnsafeRawBufferPointer, _ at: Int, _ rgba: UInt32, _ sink: GridOverlayLineSink) -> Int {
    var size = s.leF32(at + MarkerOutlineLayout.size)
    if !(size > 0) || size > MarkerOutlineOverlay.maxSizeM { size = MarkerOutlineOverlay.defaultSizeM }
    let p = at + MarkerOutlineLayout.pose
    let tx = s.leF32(p), ty = s.leF32(p + 4), tz = s.leF32(p + 8)
    var qx = s.leF32(p + 12), qy = s.leF32(p + 16), qz = s.leF32(p + 20), qw = s.leF32(p + 24)
    let n = (qx * qx + qy * qy + qz * qz + qw * qw).squareRoot()
    if !(n > 1e-6) || !tx.isFinite || !ty.isFinite || !tz.isFinite { return 0 }
    qx /= n; qy /= n; qz /= n; qw /= n
    // Columns of the rotation: marker x, y and z axes in the world.
    let xx = 1 - 2 * (qy * qy + qz * qz)
    let xy = 2 * (qx * qy + qz * qw)
    let xz = 2 * (qx * qz - qy * qw)
    let yx = 2 * (qx * qy - qz * qw)
    let yy = 1 - 2 * (qx * qx + qz * qz)
    let yz = 2 * (qy * qz + qx * qw)
    let zx = 2 * (qx * qz + qy * qw)
    let zy = 2 * (qy * qz - qx * qw)
    let zz = 1 - 2 * (qx * qx + qy * qy)
    let h = size * 0.5
    for k in 0..<4 {
      let u = (k == 0 || k == 3) ? -h : h
      let v = k < 2 ? h : -h
      corner[3 * k] = tx + xx * u + yx * v
      corner[3 * k + 1] = ty + xy * u + yy * v
      corner[3 * k + 2] = tz + xz * u + yz * v
    }
    for k in 0..<4 {
      let a = 3 * k
      let b = 3 * ((k + 1) % 4)
      sink.line(corner[a], corner[a + 1], corner[a + 2], corner[b], corner[b + 1], corner[b + 2], rgba: rgba)
    }
    let l = size * MarkerOutlineOverlay.normalFraction
    sink.line(tx, ty, tz, tx + zx * l, ty + zy * l, tz + zz * l, rgba: rgba)
    return MarkerOutlineOverlay.segmentsPerMarker
  }

  static func colour(of state: Int) -> UInt32 {
    switch state {
    case MarkerOutlineLayout.stateSeenNow: return 0xFFE0_40FF
    case MarkerOutlineLayout.stateRejected: return 0xFF80_20FF
    case MarkerOutlineLayout.stateStable: return 0x40E0_60FF
    case MarkerOutlineLayout.stateUsedForAlignment: return 0x30D0_FFFF
    case MarkerOutlineLayout.stateMoved: return 0xFF30_30FF
    default: return 0xFFFF_FFFF
    }
  }
}
