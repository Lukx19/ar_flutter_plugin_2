import Foundation
import XCTest

@testable import ar_flutter_plugin_2

/// Delta reader and instance buffers against the contract goldens
/// (`render_*.bin`, written by Dart and read by Kotlin) and the Dart
/// exporter's room stream; display mode and colour table goldens.
final class RendererDeltaGoldenTests: XCTestCase {
  func testDenseScreenPointDiametersAreRetainedWithBoundedPhysicalSize() throws {
    let handler = DisplayModeHandler()
    var mode = [UInt8](try GridRendererFixtures.golden("display_mode.bin"))
    var state = GridDisplayState()
    let cases: [(Float, Float)] = [(96, 96), (128, 128), (400, 128), (-2, 1), (.nan, 6)]
    for (requested, expected) in cases {
      let bits = requested.bitPattern
      for byte in 0..<4 {
        mode[DisplayModeLayout.pointSize + byte] = UInt8(truncatingIfNeeded: bits >> (byte * 8))
      }
      XCTAssertEqual(handler.onMessage(Data(mode)), .mode)
      XCTAssertTrue(handler.snapshotMode(into: &state, knownVersion: -1))
      XCTAssertEqual(state.pointSizePx, expected)
    }
  }

  private let renderGoldens = [
    "render_surface_slice.bin", "render_brick_summaries.bin", "render_occupancy_masks.bin",
    "render_region_summaries.bin", "render_compact_voxels.bin", "render_free_slots.bin",
    "render_reset.bin", "render_frame_end.bin",
  ]

  private func reader(_ data: Data, _ body: (RendererDeltaReader) -> Void) {
    data.withUnsafeBytes { raw in
      var r = RendererDeltaReader()
      r.attach(raw)
      body(r)
    }
  }

  func testReaderValidatesEveryContractGolden() throws {
    for name in renderGoldens {
      reader(try GridRendererFixtures.golden(name)) { r in
        XCTAssertEqual(r.validate(), .ok, name)
        XCTAssertEqual(r.gridVersion, 41, name)
        XCTAssertEqual(r.bufferCapacity, 4096, name)
      }
    }
    reader(try GridRendererFixtures.golden("render_surface_slice.bin")) { r in
      XCTAssertEqual(r.kind, RendererDeltaLayout.kindSurfaceSlice)
      XCTAssertEqual(r.firstSlot, 64)
      XCTAssertEqual(r.recordCount, 2)
      XCTAssertEqual(r.brickKey, 0x0000_1234_0000_5678)
      XCTAssertEqual(r.recordsLength, 2 * SurfaceInstanceLayout.bytes)
    }
  }

  func testReaderRejectsMalformedMessages() throws {
    let golden = try GridRendererFixtures.golden("render_free_slots.bin")
    reader(golden.prefix(golden.count - 1)) { XCTAssertEqual($0.validate(), .badLength) }
    reader(golden.prefix(16)) { XCTAssertEqual($0.validate(), .tooShort) }
    var bad = [UInt8](golden)
    bad[RendererDeltaLayout.kind] = 99
    reader(Data(bad)) { XCTAssertEqual($0.validate(), .badKind) }
    bad[RendererDeltaLayout.kind] = UInt8(RendererDeltaLayout.kindFreeSlots)
    bad[RendererDeltaLayout.recordCount] = 0xA0
    bad[RendererDeltaLayout.recordCount + 1] = 0x86
    bad[RendererDeltaLayout.recordCount + 2] = 0x01  // 100,000 records
    reader(Data(bad)) { XCTAssertEqual($0.validate(), .tooLarge) }
    bad[0] = 0
    reader(Data(bad)) { XCTAssertEqual($0.validate(), .badMagic) }
    var version = [UInt8](golden)
    version[4] = 2
    reader(Data(version)) { XCTAssertEqual($0.validate(), .badVersion) }
  }

  /// A slice with first slot -1 is rejected before it reaches the slice
  /// tables (a negative first would trap when the draw packs it).
  func testNegativeFirstSlotIsRejectedAndLeavesTheTables() throws {
    let store = InstanceBufferStore(target: ByteArrayGpuTarget())
    XCTAssertEqual(try store.apply(GridRendererFixtures.golden("render_reset.bin")), .ok)
    for name in ["render_surface_slice.bin", "render_compact_voxels.bin"] {
      var bad = [UInt8](try GridRendererFixtures.golden(name))
      for i in 0..<4 { bad[RendererDeltaLayout.firstSlot + i] = 0xFF }
      reader(Data(bad)) { XCTAssertEqual($0.validate(), .badSlot, name) }
      let rejected = store.counters.rejected
      XCTAssertEqual(try store.apply(Data(bad)), .badSlot, name)
      XCTAssertEqual(store.counters.rejected, rejected + 1, name)
      XCTAssertEqual(Data(bad).withUnsafeBytes { store.uploadBytes($0) }, 0, name)
    }
    XCTAssertLessThan(store.surfaceSlices.find(0x0000_1234_0000_5678), 0, "nothing recorded")
  }

  /// Each delta kind applied to the buffers equals the golden's record bytes.
  func testEachGoldenKindLandsInItsBuffer() throws {
    let target = ByteArrayGpuTarget()
    let store = InstanceBufferStore(target: target)
    XCTAssertEqual(try store.apply(GridRendererFixtures.golden("render_reset.bin")), .ok)
    XCTAssertEqual(store.counters.resets, 1)
    XCTAssertEqual(target.buffer(.surfaces).count, 4096 * SurfaceInstanceLayout.bytes)

    let slice = [UInt8](try GridRendererFixtures.golden("render_surface_slice.bin"))
    XCTAssertEqual(try store.apply(Data(slice)), .ok)
    let surfaces = target.buffer(.surfaces)
    XCTAssertEqual(Array(surfaces[(64 * 40)..<(66 * 40)]), Array(slice[32...]))
    let s = store.surfaceSlices.find(0x0000_1234_0000_5678)
    XCTAssertGreaterThanOrEqual(s, 0)
    XCTAssertEqual(store.surfaceSlices.first[s], 64)
    XCTAssertEqual(store.surfaceSlices.count[s], 2)
    XCTAssertEqual(store.surfaceSlices.selected[s], 0)
    // The golden's records: published, positions (1, 0.5, -2.25) and (2, 0.5, -2.25).
    XCTAssertEqual(gridLEF32(surfaces, 64 * 40), 1)
    XCTAssertEqual(gridLEF32(surfaces, 65 * 40), 2)
    XCTAssertEqual(Int(surfaces[64 * 40 + SurfaceInstanceLayout.flags]), SurfaceInstanceLayout.flagPublished)
    XCTAssertEqual(gridLE32(surfaces, 64 * 40 + SurfaceInstanceLayout.stableId), 9000)

    let bricks = [UInt8](try GridRendererFixtures.golden("render_brick_summaries.bin"))
    XCTAssertEqual(try store.apply(Data(bricks)), .ok)
    XCTAssertEqual(store.brickSlots, 7)
    XCTAssertEqual(Array(target.buffer(.bricks)[(5 * 16)..<(7 * 16)]), Array(bricks[32...]))
    XCTAssertEqual(target.buffer(.bricks).count, 4096 * 16)

    let regions = [UInt8](try GridRendererFixtures.golden("render_region_summaries.bin"))
    XCTAssertEqual(try store.apply(Data(regions)), .ok)
    XCTAssertEqual(store.regionSlots, 1)
    XCTAssertEqual(Array(target.buffer(.regions)[0..<16]), Array(regions[32...]))

    let voxels = [UInt8](try GridRendererFixtures.golden("render_compact_voxels.bin"))
    XCTAssertEqual(try store.apply(Data(voxels)), .ok)
    XCTAssertEqual(Array(target.buffer(.voxels)[(32 * 8)..<(35 * 8)]), Array(voxels[32...]))
    let v = store.voxelSlices.find(0x0000_1234_0000_5678)
    XCTAssertEqual(store.voxelSlices.first[v], 32)
    XCTAssertEqual(store.voxelSlices.count[v], 3)
    // The third entry is padding: every byte 0xFF.
    XCTAssertEqual(Array(target.buffer(.voxels)[(34 * 8)..<(35 * 8)]), [UInt8](repeating: 0xFF, count: 8))

    // Free slots: range (128, 16) of surface slots is zeroed.
    let writesBefore = target.writes
    XCTAssertEqual(try store.apply(GridRendererFixtures.golden("render_free_slots.bin")), .ok)
    XCTAssertEqual(target.clears, 1)
    XCTAssertEqual(target.writes, writesBefore)

    let masks = try GridRendererFixtures.golden("render_occupancy_masks.bin")
    XCTAssertEqual(try store.apply(masks), .ok)
    XCTAssertEqual(store.counters.ignoredOccupancyMasks, 1)
    XCTAssertEqual(target.writes, writesBefore, "dense occupancy masks are not uploaded")

    XCTAssertEqual(try store.apply(GridRendererFixtures.golden("render_frame_end.bin")), .ok)
    XCTAssertEqual(store.counters.frameEnds, 1)
    XCTAssertEqual(store.counters.rejected, 0)
    XCTAssertEqual(store.counters.lastGridVersion, 41)
  }

  func testDartExportStreamBuildsTheRoomInTheInstanceBuffers() throws {
    let target = ByteArrayGpuTarget()
    let store = InstanceBufferStore(target: target)
    for message in try GridRendererFixtures.roomExportStream() {
      XCTAssertEqual(try store.apply(message), .ok)
    }
    XCTAssertEqual(store.counters.resets, 1)
    XCTAssertEqual(store.counters.rejected, 0)
    let surfaces = target.buffer(.surfaces)
    var published = 0
    var selected = 0
    var ids = Set<UInt32>()
    let table = store.surfaceSlices
    for i in 0..<table.highWater {
      for r in table.first[i]..<(table.first[i] + table.count[i]) {
        let o = r * SurfaceInstanceLayout.bytes
        let flags = Int(surfaces[o + SurfaceInstanceLayout.flags])
        if flags & SurfaceInstanceLayout.flagPublished == 0 { continue }
        published += 1
        if flags & SurfaceInstanceLayout.flagSelected != 0 { selected += 1 }
        ids.insert(gridLE32(surfaces, o + SurfaceInstanceLayout.stableId))
      }
    }
    XCTAssertEqual(published, 864)
    XCTAssertEqual(ids.count, 864)
    XCTAssertEqual(selected, 64)
    XCTAssertEqual((0..<table.highWater).reduce(0) { $0 + table.selected[$1] }, 64)
    XCTAssertGreaterThan(store.voxelSlices.size, 0)
    XCTAssertEqual(table.size, store.voxelSlices.size)
    // One summary per brick slot; every live brick has a surface slice.
    let bricks = target.buffer(.bricks)
    let live = (0..<store.brickSlots).filter { gridLEI16(bricks, $0 * 16 + 6) != 0 }.count
    XCTAssertEqual(live, table.size)
    XCTAssertGreaterThanOrEqual(store.regionSlots, 1)
  }

  func testFreeSlotsZeroTheSurfaceRangeAndZeroRecordSlicesLeaveTheDrawTable() throws {
    let w = RendererDeltaWriter()
    let target = ByteArrayGpuTarget()
    let store = InstanceBufferStore(target: target)
    w.begin(RendererDeltaLayout.kindReset, firstSlot: 0, records: 0, capacity: 64, brickKey: 0)
    try w.withMessage { _ = try store.apply($0) }
    let key = GridBrickKey.pack(1, 0, -2)
    w.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: 32, records: 32, capacity: 64, brickKey: key)
    for i in 0..<32 { w.surface(i, 1, 0.1 * Float(i), -1.5, 0, 1, 0) }
    try w.withMessage { _ = try store.apply($0) }
    XCTAssertEqual(store.surfaceSlices.size, 1)
    w.begin(RendererDeltaLayout.kindFreeSlots, firstSlot: 0, records: 1, capacity: 64, brickKey: 0)
    w.freeRange(0, first: 32, count: 32)
    try w.withMessage { _ = try store.apply($0) }
    XCTAssertTrue(target.buffer(.surfaces)[(32 * 40)..<(64 * 40)].allSatisfy { $0 == 0 })
    w.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: 32, records: 0, capacity: 64, brickKey: key)
    try w.withMessage { _ = try store.apply($0) }
    XCTAssertEqual(store.surfaceSlices.size, 0)
    XCTAssertEqual(store.surfaceSlices.find(key), -1)
  }

  func testCapacityGrowthKeepsContentAndIsCounted() throws {
    let w = RendererDeltaWriter()
    let target = ByteArrayGpuTarget()
    let store = InstanceBufferStore(target: target)
    w.begin(RendererDeltaLayout.kindReset, firstSlot: 0, records: 0, capacity: 32, brickKey: 0)
    try w.withMessage { _ = try store.apply($0) }
    w.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: 0, records: 32, capacity: 32, brickKey: GridBrickKey.pack(0, 0, 0))
    w.surface(0, 1, 2, 3, 0, 1, 0, stableId: 77)
    try w.withMessage { _ = try store.apply($0) }
    w.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: 32, records: 32, capacity: 64, brickKey: GridBrickKey.pack(1, 0, 0))
    try w.withMessage { _ = try store.apply($0) }
    XCTAssertEqual(store.counters.capacityGrowths, 1)
    XCTAssertEqual(target.buffer(.surfaces).count, 64 * 40)
    XCTAssertEqual(gridLE32(target.buffer(.surfaces), SurfaceInstanceLayout.stableId), 77)
  }

  func testSliceTableRemovalKeepsEveryOtherKeyReachable() {
    let table = BrickSliceTable(initialCapacity: 4)
    let keys = (0..<200).map { GridBrickKey.pack($0 % 13 - 6, $0 / 13, -($0 % 7)) }
    for (i, k) in keys.enumerated() { table.put(k, first: i * 32, count: 32, selected: 0) }
    for i in stride(from: 0, to: keys.count, by: 3) { XCTAssertTrue(table.remove(keys[i])) }
    for (i, k) in keys.enumerated() {
      if i % 3 == 0 {
        XCTAssertEqual(table.find(k), -1)
      } else {
        XCTAssertEqual(table.first[table.find(k)], i * 32)
      }
    }
    XCTAssertFalse(table.remove(keys[0]))
    let growth = table.growthEvents
    for _ in 0..<10 {
      for i in stride(from: 0, to: keys.count, by: 3) { table.put(keys[i], first: i, count: 1, selected: 0) }
      for i in stride(from: 0, to: keys.count, by: 3) { table.remove(keys[i]) }
    }
    XCTAssertEqual(table.growthEvents, growth)
  }

  func testBrickKeysRoundTrip() {
    for (x, y, z) in [(0, 0, 0), (-1, 2, -3), (1000, -1000, 7), (-0x100000, 0xFFFFF, 0)] {
      let key = GridBrickKey.pack(x, y, z)
      XCTAssertEqual(GridBrickKey.x(key), x)
      XCTAssertEqual(GridBrickKey.y(key), y)
      XCTAssertEqual(GridBrickKey.z(key), z)
    }
  }

  func testDisplayGoldensDecodeAndBadDisplayMessagesAreRejected() throws {
    let handler = DisplayModeHandler()
    var mode = [UInt8](try GridRendererFixtures.golden("display_mode.bin"))
    XCTAssertEqual(mode.count, DisplayModeLayout.bytes)
    XCTAssertEqual(handler.onMessage(Data(mode)), .mode)
    var state = GridDisplayState()
    XCTAssertTrue(handler.snapshotMode(into: &state, knownVersion: -1))
    XCTAssertEqual(state.baseLayer, GridDisplayCodes.baseSplats)
    XCTAssertEqual(state.overlayBits, GridDisplayCodes.overlaySpikeBalls | GridDisplayCodes.overlayTargets)
    XCTAssertEqual(state.colourAttribute, GridDisplayCodes.colourCompletion)
    XCTAssertEqual(state.sizeRule, GridDisplayCodes.sizeByCoverage)
    XCTAssertEqual(state.spikeNearestK, 512)
    XCTAssertEqual(state.pointSizePx, 6.5)
    XCTAssertEqual(state.colourTableGeneration, 3)
    XCTAssertEqual(state.minQuality, 64)
    let table = try GridRendererFixtures.golden("colour_table.bin")
    XCTAssertEqual(table.count, ColourTableLayout.bytes)
    XCTAssertEqual(handler.onMessage(table), .colourTable)
    XCTAssertEqual(handler.rgba(attribute: GridDisplayCodes.colourCoverage, index: 10), 0x0AF5_40FF)
    var tables = [UInt8](repeating: 0, count: DisplayModeHandler.tableBytes)
    XCTAssertGreaterThan(tables.withUnsafeMutableBytes { handler.snapshotTables(into: $0.baseAddress!, knownVersion: 0) }, 0)
    XCTAssertEqual(Array(tables[40..<44]), [0x0A, 0xF5, 0x40, 0xFF])
    mode[4] = 9
    XCTAssertEqual(handler.onMessage(Data(mode)), .rejected)
    XCTAssertEqual(handler.onMessage(Data(count: 4)), .rejected)
    var badAttribute = [UInt8](table)
    badAttribute[ColourTableLayout.attribute] = 5
    XCTAssertEqual(handler.onMessage(Data(badAttribute)), .rejected)
  }

  func testMinimumQualityReachesTheRenderStateAndAnOlderMessageDrawsEverySurface() {
    let core = GridRendererCore(target: ByteArrayGpuTarget())
    let w = RendererDeltaWriter()
    w.displayMode(baseLayer: GridDisplayCodes.baseSplats, overlayBits: 0,
                  colourAttribute: GridDisplayCodes.colourConfidence, sizeRule: 0, minQuality: 200)
    XCTAssertEqual(core.display(w), .mode)
    var state = GridDisplayState()
    XCTAssertTrue(core.display.snapshotMode(into: &state, knownVersion: -1))
    XCTAssertEqual(state.minQuality, 200)
    // Byte 20 was reserved zero: a message written before the threshold decodes as 0.
    w.displayMode(baseLayer: GridDisplayCodes.basePoints, overlayBits: 0,
                  colourAttribute: GridDisplayCodes.colourCoverage, sizeRule: 0)
    XCTAssertEqual(core.display(w), .mode)
    XCTAssertTrue(core.display.snapshotMode(into: &state, knownVersion: state.modeVersion))
    XCTAssertEqual(state.minQuality, 0)
  }

  func testPlannerTargetsDecodeFromTheProvisionalDisplayMessage() {
    let handler = DisplayModeHandler()
    var bytes = [UInt8](repeating: 0, count: PlannerTargetMessageLayout.bytes)
    func putF(_ o: Int, _ v: Float) { for i in 0..<4 { bytes[o + i] = UInt8(truncatingIfNeeded: v.bitPattern >> (8 * UInt32(i))) } }
    for i in 0..<4 { bytes[i] = UInt8(truncatingIfNeeded: PlannerTargetMessageLayout.magic >> (8 * UInt32(i))) }
    bytes[4] = 1
    bytes[PlannerTargetMessageLayout.count] = 2
    putF(8, 1.5)
    putF(8 + 8 * 4 + 4, -2)
    XCTAssertEqual(handler.onMessage(Data(bytes)), .targets)
    var out = [Float](repeating: 0, count: 128)
    XCTAssertEqual(handler.snapshotTargets(into: &out, knownVersion: -1), 2)
    XCTAssertEqual(out[0], 1.5)
    XCTAssertEqual(out[9], -2)
    XCTAssertEqual(handler.snapshotTargets(into: &out, knownVersion: handler.currentTargetsVersion), -1)
  }
}
