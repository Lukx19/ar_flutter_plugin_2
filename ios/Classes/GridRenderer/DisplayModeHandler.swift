import Foundation

/// One display state; the render thread keeps its own copy.
struct GridDisplayState: Equatable {
  var baseLayer = GridDisplayCodes.basePoints
  var overlayBits = 0
  var colourAttribute = GridDisplayCodes.colourCoverage
  var sizeRule = GridDisplayCodes.sizeByCoverage
  /// Display mode byte 10. Carried for parity; the exporter already limits
  /// the selected (spike ball) surfaces to the nearest K, as on Android.
  var spikeNearestK = 512
  var pointSizePx: Float = 6
  var colourTableGeneration: UInt32 = 0
  /// Surfaces with a confidence byte below this (0-255) are not drawn; 0 draws all.
  var minQuality = 0
  /// Increments with every accepted display mode message.
  var modeVersion = 0

  func has(_ overlay: Int) -> Bool { overlayBits & overlay != 0 }
}

/// Result of one display channel message.
enum DisplayIntake: Equatable {
  case mode, colourTable, targets, overlay, rejected
}

/// Parses display channel messages (display mode, colour tables, provisional
/// planner targets) on the channel queue and hands a consistent snapshot to
/// the render thread. A mode switch changes only this state; draw calls and
/// uniforms follow it, instance buffers are never touched.
final class DisplayModeHandler {
  static let tableBytes = GridDisplayCodes.colourAttributes * ColourTableLayout.entries * 4

  private let lock = NSLock()
  private var pending = GridDisplayState()
  /// When the pending mode version was accepted ([now] seconds).
  private var modeAcceptedAtS = 0.0
  /// Monotonic seconds; the same clock as the render loop's frame times.
  let now: () -> Double
  private var tables = [UInt8](repeating: 0, count: DisplayModeHandler.tableBytes)
  private var tablesVersion = 0
  private var targets = [Float](
    repeating: 0, count: PlannerTargetMessageLayout.maxTargets * PlannerTargetMessageLayout.floatsPerTarget)
  private var targetCount = 0
  private var targetsVersion = 0
  private var rejectedCount = 0

  init(now: @escaping () -> Double = { ProcessInfo.processInfo.systemUptime }) {
    self.now = now
    // Neutral grey ramp until the first colour tables arrive.
    for t in 0..<GridDisplayCodes.colourAttributes {
      for i in 0..<ColourTableLayout.entries {
        let o = (t * ColourTableLayout.entries + i) * 4
        tables[o] = UInt8(i)
        tables[o + 1] = UInt8(i)
        tables[o + 2] = UInt8(i)
        tables[o + 3] = 255
      }
    }
  }

  var rejected: Int { locked { rejectedCount } }

  /// Back to the default display mode without planner targets (a new grid
  /// session); the render thread picks it up as a new mode version. The
  /// colour tables stay until the next session sends its own.
  func reset() {
    locked {
      let version = pending.modeVersion + 1
      pending = GridDisplayState()
      pending.modeVersion = version
      modeAcceptedAtS = now()
      targetCount = 0
      targetsVersion += 1
    }
  }

  func onMessage(_ message: Data) -> DisplayIntake {
    message.withUnsafeBytes { onMessage($0) }
  }

  func onMessage(_ b: UnsafeRawBufferPointer) -> DisplayIntake {
    let length = b.count
    guard length >= 8, b.baseAddress != nil else { return reject() }
    let magic = b.leU32(0)
    let version = b.le16(4)
    switch magic {
    case DisplayModeLayout.magic:
      guard version == DisplayModeLayout.version, length >= DisplayModeLayout.bytes else { return reject() }
      let base = b.le8(DisplayModeLayout.baseLayer)
      let colour = b.le8(DisplayModeLayout.colourAttribute)
      let size = b.le8(DisplayModeLayout.sizeRule)
      guard base <= GridDisplayCodes.baseNone, colour < GridDisplayCodes.colourAttributes,
            size <= GridDisplayCodes.sizeByConfidence else { return reject() }
      let px = b.leF32(DisplayModeLayout.pointSize)
      let acceptedAt = now()
      lock.lock()
      pending.baseLayer = base
      pending.overlayBits = b.le8(DisplayModeLayout.overlayBits)
      pending.colourAttribute = colour
      pending.sizeRule = size
      pending.spikeNearestK = b.le16(DisplayModeLayout.spikeNearestK)
      pending.pointSizePx = px.isFinite ? min(max(px, 1), 64) : 6
      pending.colourTableGeneration = b.leU32(DisplayModeLayout.colourTableGeneration)
      pending.minQuality = b.le8(DisplayModeLayout.minQuality)
      pending.modeVersion += 1
      modeAcceptedAtS = acceptedAt
      lock.unlock()
      return .mode
    case ColourTableLayout.magic:
      guard version == ColourTableLayout.version, length >= ColourTableLayout.bytes else { return reject() }
      let attribute = b.le8(ColourTableLayout.attribute)
      guard attribute < GridDisplayCodes.colourAttributes else { return reject() }
      lock.lock()
      let dst = attribute * ColourTableLayout.entries * 4
      for i in 0..<(ColourTableLayout.entries * 4) {
        tables[dst + i] = b[ColourTableLayout.headerBytes + i]
      }
      tablesVersion += 1
      lock.unlock()
      return .colourTable
    case PlannerTargetMessageLayout.magic:
      guard version == PlannerTargetMessageLayout.version, length >= PlannerTargetMessageLayout.bytes else {
        return reject()
      }
      let count = min(b.le8(PlannerTargetMessageLayout.count), PlannerTargetMessageLayout.maxTargets)
      lock.lock()
      for i in targets.indices {
        targets[i] = b.leF32(PlannerTargetMessageLayout.headerBytes + 4 * i)
      }
      targetCount = count
      targetsVersion += 1
      lock.unlock()
      return .targets
    default:
      return reject()
    }
  }

  private func reject() -> DisplayIntake {
    lock.lock()
    rejectedCount += 1
    lock.unlock()
    return .rejected
  }

  private func locked<T>(_ body: () -> T) -> T {
    lock.lock(); defer { lock.unlock() }
    return body()
  }

  /// Copies the current mode into [out]; returns whether it changed since [knownVersion].
  func snapshotMode(into out: inout GridDisplayState, knownVersion: Int) -> Bool {
    snapshotModeAcceptedAt(into: &out, knownVersion: knownVersion) != nil
  }

  /// Like [snapshotMode]; returns when the copied mode was accepted, or nil
  /// when it did not change since [knownVersion].
  func snapshotModeAcceptedAt(into out: inout GridDisplayState, knownVersion: Int) -> Double? {
    lock.lock(); defer { lock.unlock() }
    if pending.modeVersion == knownVersion { return nil }
    out = pending
    return modeAcceptedAtS
  }

  /// Copies the colour tables (5 x 256 RGBA8) into [out] when newer than
  /// [knownVersion]; returns the version or -1.
  func snapshotTables(into out: UnsafeMutableRawPointer, knownVersion: Int) -> Int {
    lock.lock(); defer { lock.unlock() }
    if tablesVersion == knownVersion { return -1 }
    tables.withUnsafeBytes { out.copyMemory(from: $0.baseAddress!, byteCount: $0.count) }
    return tablesVersion
  }

  /// Copies planner targets into [out]; returns the count, or -1 when
  /// unchanged since [knownVersion].
  func snapshotTargets(into out: inout [Float], knownVersion: Int) -> Int {
    lock.lock(); defer { lock.unlock() }
    if targetsVersion == knownVersion { return -1 }
    for i in targets.indices { out[i] = targets[i] }
    return targetCount
  }

  var currentTargetsVersion: Int { locked { targetsVersion } }
  var currentTablesVersion: Int { locked { tablesVersion } }

  /// Colour of [attribute] entry [index] as 0xRRGGBBAA (diagnostics and tests).
  func rgba(attribute: Int, index: Int) -> UInt32 {
    lock.lock(); defer { lock.unlock() }
    let o = (attribute * ColourTableLayout.entries + index) * 4
    return UInt32(tables[o]) << 24 | UInt32(tables[o + 1]) << 16 | UInt32(tables[o + 2]) << 8 | UInt32(tables[o + 3])
  }
}
