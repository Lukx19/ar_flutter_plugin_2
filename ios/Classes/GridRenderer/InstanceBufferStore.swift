import Foundation

/// Persistent GPU buffers of the grid renderer and their record sizes.
enum GridBuffer: Int, CaseIterable {
  case surfaces, voxels, bricks, regions

  var recordBytes: Int {
    switch self {
    case .surfaces: return SurfaceInstanceLayout.bytes
    case .voxels: return RendererDeltaLayout.compactVoxelBytes
    case .bricks: return BrickSummaryLayout.bytes
    case .regions: return RegionSummaryLayout.bytes
    }
  }
}

/// Where delta records land. The Metal implementation stages bytes through a
/// fenced upload ring and copies them on the GPU; tests use a byte-array
/// mirror. Every call runs on the render (main) thread.
protocol GpuBufferTarget: AnyObject {
  /// Grows [buffer] to at least [bytes], keeping its content; new space reads zero.
  func ensureCapacity(_ buffer: GridBuffer, bytes: Int) throws

  /// Copies [source] to [dstOffset] of [buffer].
  func write(_ buffer: GridBuffer, dstOffset: Int, source: UnsafeRawBufferPointer)

  /// Zeroes `[dstOffset, dstOffset + length)` of [buffer].
  func clear(_ buffer: GridBuffer, dstOffset: Int, length: Int)

  /// Bytes this target can still accept in the current frame without stalling.
  func frameUploadCapacity() -> Int
}

/// Open-addressing map from brick key to a slice index; removal shifts the
/// probe chain back, so there are no tombstones. Grows by doubling (counted).
/// Slice columns are indexed by slice index.
final class BrickSliceTable {
  static let empty = Int64.min

  private var keys: [Int64]
  private var values: [Int]
  private(set) var size = 0
  private(set) var growthEvents = 0

  private(set) var first: [Int]
  private(set) var count: [Int]
  private(set) var brickKey: [Int64]
  private(set) var selected: [Int]
  private var freeIndices: [Int] = []
  private(set) var highWater = 0

  init(initialCapacity: Int = 1024) {
    keys = Array(repeating: BrickSliceTable.empty, count: BrickSliceTable.roundPow2(initialCapacity * 2))
    values = Array(repeating: 0, count: keys.count)
    first = Array(repeating: 0, count: initialCapacity)
    count = Array(repeating: 0, count: initialCapacity)
    brickKey = Array(repeating: BrickSliceTable.empty, count: initialCapacity)
    selected = Array(repeating: 0, count: initialCapacity)
    freeIndices.reserveCapacity(initialCapacity)
  }

  /// Index of the slice of [key], or -1.
  func find(_ key: Int64) -> Int {
    let mask = keys.count - 1
    var i = BrickSliceTable.mix(key) & mask
    while true {
      let k = keys[i]
      if k == key { return values[i] }
      if k == BrickSliceTable.empty { return -1 }
      i = (i + 1) & mask
    }
  }

  /// Sets the slice of [key] to ([sliceFirst], [sliceCount]); returns its index.
  @discardableResult
  func put(_ key: Int64, first sliceFirst: Int, count sliceCount: Int, selected selectedCount: Int) -> Int {
    var index = find(key)
    if index < 0 {
      if (size + 1) * 2 > keys.count { rehash(keys.count * 2) }
      if let free = freeIndices.popLast() {
        index = free
      } else {
        index = highWater
        highWater += 1
      }
      if index >= first.count { growColumns(first.count * 2) }
      let mask = keys.count - 1
      var i = BrickSliceTable.mix(key) & mask
      while keys[i] != BrickSliceTable.empty { i = (i + 1) & mask }
      keys[i] = key
      values[i] = index
      size += 1
    }
    first[index] = sliceFirst
    count[index] = sliceCount
    brickKey[index] = key
    selected[index] = selectedCount
    return index
  }

  /// Removes the slice of [key]; returns whether it existed.
  @discardableResult
  func remove(_ key: Int64) -> Bool {
    let mask = keys.count - 1
    var i = BrickSliceTable.mix(key) & mask
    while true {
      let k = keys[i]
      if k == BrickSliceTable.empty { return false }
      if k == key { break }
      i = (i + 1) & mask
    }
    let index = values[i]
    count[index] = 0
    selected[index] = 0
    brickKey[index] = BrickSliceTable.empty
    freeIndices.append(index)
    size -= 1
    // Backward-shift deletion.
    var hole = i
    var j = (i + 1) & mask
    while keys[j] != BrickSliceTable.empty {
      let home = BrickSliceTable.mix(keys[j]) & mask
      let between = hole <= j ? (home > hole && home <= j) : (home > hole || home <= j)
      if !between {
        keys[hole] = keys[j]
        values[hole] = values[j]
        hole = j
      }
      j = (j + 1) & mask
    }
    keys[hole] = BrickSliceTable.empty
    return true
  }

  func clear() {
    for i in keys.indices { keys[i] = BrickSliceTable.empty }
    for i in 0..<highWater {
      count[i] = 0
      selected[i] = 0
      brickKey[i] = BrickSliceTable.empty
    }
    size = 0
    freeIndices.removeAll(keepingCapacity: true)
    highWater = 0
  }

  private func rehash(_ capacity: Int) {
    let oldKeys = keys
    let oldValues = values
    keys = Array(repeating: BrickSliceTable.empty, count: capacity)
    values = Array(repeating: 0, count: capacity)
    let mask = capacity - 1
    for s in oldKeys.indices {
      let k = oldKeys[s]
      if k == BrickSliceTable.empty { continue }
      var i = BrickSliceTable.mix(k) & mask
      while keys[i] != BrickSliceTable.empty { i = (i + 1) & mask }
      keys[i] = k
      values[i] = oldValues[s]
    }
    growthEvents += 1
  }

  private func growColumns(_ capacity: Int) {
    let extra = capacity - first.count
    first.append(contentsOf: repeatElement(0, count: extra))
    count.append(contentsOf: repeatElement(0, count: extra))
    brickKey.append(contentsOf: repeatElement(BrickSliceTable.empty, count: extra))
    selected.append(contentsOf: repeatElement(0, count: extra))
    growthEvents += 1
  }

  static func mix(_ key: Int64) -> Int {
    let h = UInt64(bitPattern: key) &* 0x9E37_79B9_7F4A_7C15
    return Int(Int32(truncatingIfNeeded: h ^ (h >> 29)))
  }

  static func roundPow2(_ v: Int) -> Int {
    var p = 1
    while p < v { p <<= 1 }
    return p
  }
}

/// Counters of the instance buffers, read by diagnostics and tests.
struct InstanceBufferCounters {
  var messages = 0
  var bytes = 0
  var rejected = 0
  var ignoredOccupancyMasks = 0
  var resets = 0
  var frameEnds = 0
  var capacityGrowths = 0
  var allocationFailures = 0
  var lastGridVersion: UInt32 = 0
}

/// CPU-side bookkeeping of the persistent instance buffers: applies
/// validated renderer deltas to a [GpuBufferTarget] and keeps the draw
/// tables (surface and voxel slices per brick, summary and region high water
/// marks). It never touches Metal itself, so tests drive it with a
/// byte-array target.
///
/// Kind 3 (dense occupancy masks) is ignored: occupancy voxels are drawn
/// only from compact voxel lists (kind 8), as on Android, where dense
/// 512-instance-per-brick drawing measured 29/104/276 ms frames while the
/// compact path held 60 fps.
final class InstanceBufferStore {
  let surfaceSlices = BrickSliceTable()
  let voxelSlices = BrickSliceTable()
  private(set) var counters = InstanceBufferCounters()
  private let target: GpuBufferTarget
  private var reader = RendererDeltaReader()
  private var capacity = Array(repeating: 0, count: GridBuffer.allCases.count)

  /// Brick summary slots in use (draw count of brick styles).
  private(set) var brickSlots = 0

  /// Region summary slots in use.
  private(set) var regionSlots = 0

  /// Increments whenever any draw table changes.
  private(set) var tableVersion = 0

  init(target: GpuBufferTarget) {
    self.target = target
  }

  func capacityBytes(_ buffer: GridBuffer) -> Int { capacity[buffer.rawValue] }

  /// Validates and applies the message [message]; returns its status.
  /// Throws when a buffer could not be allocated (the renderer failed).
  @discardableResult
  func apply(_ message: UnsafeRawBufferPointer) throws -> DeltaStatus {
    reader.attach(message)
    let r = reader
    let status = r.validate()
    if status != .ok {
      counters.rejected += 1
      return status
    }
    counters.messages += 1
    counters.bytes += message.count
    counters.lastGridVersion = r.gridVersion
    switch r.kind {
    case RendererDeltaLayout.kindReset:
      surfaceSlices.clear()
      voxelSlices.clear()
      brickSlots = 0
      regionSlots = 0
      try ensure(.surfaces, records: r.bufferCapacity)
      counters.resets += 1
      tableVersion += 1
    case RendererDeltaLayout.kindSurfaceSlice:
      try ensure(.surfaces, records: r.bufferCapacity)
      if r.recordCount == 0 {
        surfaceSlices.remove(r.brickKey)
      } else {
        try writeRecords(.surfaces, r)
        surfaceSlices.put(r.brickKey, first: r.firstSlot, count: r.recordCount, selected: countSelected(r))
      }
      tableVersion += 1
    case RendererDeltaLayout.kindCompactVoxels:
      try ensure(.voxels, records: r.bufferCapacity)
      if r.recordCount == 0 {
        voxelSlices.remove(r.brickKey)
      } else {
        try writeRecords(.voxels, r)
        voxelSlices.put(r.brickKey, first: r.firstSlot, count: r.recordCount, selected: 0)
      }
      tableVersion += 1
    case RendererDeltaLayout.kindFreeSlots:
      try ensure(.surfaces, records: r.bufferCapacity)
      let records = r.records
      let bytes = GridBuffer.surfaces.recordBytes
      for i in 0..<r.recordCount {
        let at = i * RendererDeltaLayout.freeSlotRangeBytes
        let first = records.leI32(at)
        let count = records.leI32(at + 4)
        if first < 0 || count <= 0 || (first + count) * bytes > capacity[GridBuffer.surfaces.rawValue] { continue }
        target.clear(.surfaces, dstOffset: first * bytes, length: count * bytes)
      }
    case RendererDeltaLayout.kindBrickSummaries:
      try ensure(.bricks, records: r.bufferCapacity)
      try writeRecords(.bricks, r)
      brickSlots = max(brickSlots, r.firstSlot + r.recordCount)
      tableVersion += 1
    case RendererDeltaLayout.kindRegionSummaries:
      try ensure(.regions, records: r.bufferCapacity)
      try writeRecords(.regions, r)
      regionSlots = max(regionSlots, r.firstSlot + r.recordCount)
      tableVersion += 1
    case RendererDeltaLayout.kindOccupancyMasks:
      counters.ignoredOccupancyMasks += 1
    case RendererDeltaLayout.kindFrameEnd:
      counters.frameEnds += 1
    default:
      break
    }
    return .ok
  }

  /// Forgets buffers and draw tables after the GPU buffers were released.
  func forgetGpuBuffers() {
    surfaceSlices.clear()
    voxelSlices.clear()
    brickSlots = 0
    regionSlots = 0
    for i in capacity.indices { capacity[i] = 0 }
    tableVersion += 1
  }

  /// Bytes [apply] would hand to the target for the message, used for budgeting.
  func uploadBytes(_ message: UnsafeRawBufferPointer) -> Int {
    var r = RendererDeltaReader()
    r.attach(message)
    guard r.validate() == .ok else { return 0 }
    switch r.kind {
    case RendererDeltaLayout.kindSurfaceSlice, RendererDeltaLayout.kindCompactVoxels,
         RendererDeltaLayout.kindBrickSummaries, RendererDeltaLayout.kindRegionSummaries:
      return r.recordsLength
    case RendererDeltaLayout.kindFreeSlots:
      let records = r.records
      var bytes = 0
      for i in 0..<r.recordCount {
        bytes += max(0, records.leI32(i * RendererDeltaLayout.freeSlotRangeBytes + 4)) * GridBuffer.surfaces.recordBytes
      }
      return bytes
    default:
      return 0
    }
  }

  private func ensure(_ buffer: GridBuffer, records: Int) throws {
    if records <= 0 { return }
    let bytes = records * buffer.recordBytes
    if bytes > capacity[buffer.rawValue] {
      do {
        try target.ensureCapacity(buffer, bytes: bytes)
      } catch {
        counters.allocationFailures += 1
        throw error
      }
      if capacity[buffer.rawValue] > 0 { counters.capacityGrowths += 1 }
      capacity[buffer.rawValue] = bytes
    }
  }

  private func writeRecords(_ buffer: GridBuffer, _ r: RendererDeltaReader) throws {
    let length = r.recordsLength
    if r.firstSlot < 0 { return }
    let dst = r.firstSlot * buffer.recordBytes
    if dst + length > capacity[buffer.rawValue] {
      // A slot beyond the announced capacity: grow to fit rather than drop.
      try ensure(buffer, records: (dst + length + buffer.recordBytes - 1) / buffer.recordBytes)
    }
    target.write(buffer, dstOffset: dst, source: r.records)
  }

  private func countSelected(_ r: RendererDeltaReader) -> Int {
    var n = 0
    let records = r.records
    for i in 0..<r.recordCount {
      let flags = records.le8(i * SurfaceInstanceLayout.bytes + SurfaceInstanceLayout.flags)
      if flags & SurfaceInstanceLayout.flagSelected != 0 && flags & SurfaceInstanceLayout.flagPublished != 0 {
        n += 1
      }
    }
    return n
  }
}
