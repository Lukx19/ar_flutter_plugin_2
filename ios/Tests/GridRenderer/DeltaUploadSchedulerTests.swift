import Foundation
import XCTest

@testable import ar_flutter_plugin_2

/// Upload scheduling on the render thread, the per-frame byte budget,
/// resynchronisation and display mode switches.
final class DeltaUploadSchedulerTests: XCTestCase {
  private let w = RendererDeltaWriter()

  private func offerReset(_ core: GridRendererCore) {
    w.begin(RendererDeltaLayout.kindReset, firstSlot: 0, records: 0, capacity: 4096, brickKey: 0)
    XCTAssertEqual(core.offer(w), .accepted)
  }

  /// Offers [count] full-brick surface slices of 256 records (10,240 upload bytes each).
  private func offerSlices(_ core: GridRendererCore, _ count: Int, firstBrick: Int = 0) {
    for b in firstBrick..<(firstBrick + count) {
      w.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: b * 256, records: 256, capacity: 4096 * 64,
              brickKey: GridBrickKey.pack(b, 0, 0))
      for i in 0..<256 { w.surface(i, Float(b) * 0.8 + 0.05, 0.05, 0.05, 0, 1, 0) }
      XCTAssertEqual(core.offer(w), .accepted)
    }
  }

  @discardableResult
  private func frame(_ core: GridRendererCore, _ target: ByteArrayGpuTarget) throws -> Int {
    target.beginFrame()
    return try core.frame()
  }

  func testEveryFrameStaysWithinTheByteBudgetAndTheBacklogDrainsInOrder() throws {
    let target = ByteArrayGpuTarget()
    let core = GridRendererCore(target: target, frameBudgetBytes: 32 * 1024)
    offerReset(core)
    offerSlices(core, 40)
    var frames = 0
    while core.scheduler.queuedMessages > 0 {
      let bytes = try frame(core, target)
      XCTAssertLessThanOrEqual(bytes, 32 * 1024)
      frames += 1
    }
    // 40 x 10,240 B at three slices per 32 KiB frame.
    XCTAssertEqual(frames, 14)
    XCTAssertEqual(core.store.surfaceSlices.size, 40)
    XCTAssertGreaterThan(core.scheduler.counters.deferredFrames, 0)
    XCTAssertLessThanOrEqual(core.scheduler.counters.maxFrameBytes, 32 * 1024)
  }

  func testTheDefaultBudgetIsSixtyFourKibibytesPerFrame() throws {
    let target = ByteArrayGpuTarget()
    let core = GridRendererCore(target: target)
    XCTAssertEqual(core.frameBudgetBytes, 64 * 1024)
    offerReset(core)
    offerSlices(core, 20)
    while core.scheduler.queuedMessages > 0 {
      XCTAssertLessThanOrEqual(try frame(core, target), 64 * 1024)
    }
    // Six 10,240 B slices fit a 64 KiB frame.
    XCTAssertEqual(core.scheduler.counters.maxFrameBytes, 6 * 10_240)
    XCTAssertEqual(core.store.surfaceSlices.size, 20)
  }

  func testAMessageLargerThanTheBudgetStillProgressesOnePerFrame() throws {
    let target = ByteArrayGpuTarget()
    let core = GridRendererCore(target: target, frameBudgetBytes: 1024)
    offerReset(core)
    offerSlices(core, 3)
    try frame(core, target)  // reset plus the first slice
    XCTAssertEqual(core.store.surfaceSlices.size, 1)
    try frame(core, target)
    try frame(core, target)
    XCTAssertEqual(core.store.surfaceSlices.size, 3)
    XCTAssertEqual(core.scheduler.queuedMessages, 0)
  }

  func testStagingCapacityDefersUploadsInsteadOfOverrunning() throws {
    let target = ByteArrayGpuTarget(uploadCapacity: 0)
    let core = GridRendererCore(target: target)
    offerReset(core)
    offerSlices(core, 2)
    try frame(core, target)
    // The reset uploads nothing; the slices wait for staging space.
    XCTAssertEqual(core.scheduler.queuedMessages, 2)
    target.uploadCapacity = Int.max
    try frame(core, target)
    XCTAssertEqual(core.scheduler.queuedMessages, 0)
  }

  func testIntakeWaitsForAResetAndAFullRingAsksForResynchronisation() throws {
    let target = ByteArrayGpuTarget()
    let core = GridRendererCore(target: target, scheduler: DeltaUploadScheduler(ringBytes: 64 * 1024))
    w.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: 0, records: 1, capacity: 64, brickKey: 1)
    XCTAssertEqual(core.offer(w), .resyncRequired)
    offerReset(core)
    w.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: 0, records: 256, capacity: 4096, brickKey: 1)
    var result = DeltaIntake.accepted
    var offered = 0
    while result == .accepted && offered < 100 {
      result = core.offer(w)
      offered += 1
    }
    XCTAssertEqual(result, .resyncRequired)
    XCTAssertEqual(core.scheduler.counters.overflows, 1)
    XCTAssertTrue(core.scheduler.isAwaitingReset)
    XCTAssertEqual(core.scheduler.queuedMessages, 0)
    offerReset(core)
    try frame(core, target)
    // The first reset was discarded with the overflowing queue.
    XCTAssertEqual(core.store.counters.resets, 1)
  }

  func testTooShortAndOversizedMessagesAreMalformed() {
    let core = GridRendererCore(target: ByteArrayGpuTarget())
    XCTAssertEqual(Data(count: 3).withUnsafeBytes { core.onRenderMessage($0) }, .malformed)
    XCTAssertEqual(Data(count: 65_537).withUnsafeBytes { core.onRenderMessage($0) }, .malformed)
    XCTAssertEqual(core.scheduler.counters.malformed, 2)
  }

  func testRingWrapsAroundAndKeepsMessageOrder() throws {
    let target = ByteArrayGpuTarget()
    // One slice per frame and a ring of four messages: the backlog never
    // empties, so writes wrap around the ring end.
    let core = GridRendererCore(
      target: target, scheduler: DeltaUploadScheduler(ringBytes: 40 * 1024), frameBudgetBytes: 10 * 1024)
    offerReset(core)
    offerSlices(core, 3)
    for b in 3..<40 {
      try frame(core, target)
      offerSlices(core, 1, firstBrick: b)
    }
    while core.scheduler.queuedMessages > 0 { try frame(core, target) }
    XCTAssertEqual(core.store.surfaceSlices.size, 40)
    for b in 0..<40 {
      let s = core.store.surfaceSlices.find(GridBrickKey.pack(b, 0, 0))
      XCTAssertEqual(core.store.surfaceSlices.first[s], b * 256)
    }
    XCTAssertEqual(core.scheduler.counters.overflows, 0)
  }

  func testModeSwitchChangesTheNextFrameAndTouchesNoBuffer() throws {
    let target = ByteArrayGpuTarget()
    let core = GridRendererCore(target: target)
    offerReset(core)
    offerSlices(core, 2)
    try frame(core, target)
    let operations = target.operations
    let bytes = target.writtenBytes
    let tableVersion = core.store.tableVersion
    w.displayMode(baseLayer: GridDisplayCodes.baseVoxels, overlayBits: GridDisplayCodes.overlayBricks,
                  colourAttribute: GridDisplayCodes.colourCompletion, sizeRule: 0)
    XCTAssertEqual(core.display(w), .mode)
    w.colourTable(attribute: GridDisplayCodes.colourCompletion, rgba: 0x1122_3344)
    XCTAssertEqual(core.display(w), .colourTable)
    XCTAssertEqual(core.mode.baseLayer, GridDisplayCodes.basePoints)
    try frame(core, target)
    XCTAssertEqual(core.modeAppliedFrame, core.frameNumber)
    XCTAssertEqual(core.mode.baseLayer, GridDisplayCodes.baseVoxels)
    XCTAssertTrue(core.mode.has(GridDisplayCodes.overlayBricks))
    XCTAssertEqual(core.mode.colourAttribute, GridDisplayCodes.colourCompletion)
    XCTAssertEqual(core.display.rgba(attribute: GridDisplayCodes.colourCompletion, index: 200), 0x1122_3344)
    XCTAssertEqual(target.operations, operations)
    XCTAssertEqual(target.writtenBytes, bytes)
    XCTAssertEqual(core.store.tableVersion, tableVersion)
  }

  /// iM3 mode switch timing: the frame after the accepted display message
  /// applies it; its wait from the message (latest and longest) is counted
  /// for the performance snapshot.
  func testModeSwitchLatencyIsMeasuredFromTheMessageToTheApplyingFrame() throws {
    var nowS = 10.0
    let target = ByteArrayGpuTarget()
    let core = GridRendererCore(target: target, display: DisplayModeHandler(now: { nowS }))
    try frame(core, target)
    XCTAssertEqual(core.modeSwitches.switches, 0)
    nowS = 10.004
    w.displayMode(baseLayer: GridDisplayCodes.baseSplats, overlayBits: 0, colourAttribute: 0, sizeRule: 0)
    XCTAssertEqual(core.display(w), .mode)
    nowS = 10.0167
    try frame(core, target)
    XCTAssertEqual(core.mode.baseLayer, GridDisplayCodes.baseSplats)
    XCTAssertEqual(core.modeSwitches.switches, 1)
    XCTAssertEqual(core.modeSwitches.lastLatencyMs, 12.7, accuracy: 1e-6)
    nowS = 10.0334
    try frame(core, target)
    XCTAssertEqual(core.modeSwitches.switches, 1, "no new message, no switch")

    w.displayMode(baseLayer: GridDisplayCodes.baseVoxels, overlayBits: 0, colourAttribute: 0, sizeRule: 0)
    XCTAssertEqual(core.display(w), .mode)
    nowS = 10.0335
    try frame(core, target)
    XCTAssertEqual(core.modeSwitches.switches, 2)
    XCTAssertEqual(core.modeSwitches.lastLatencyMs, 0.1, accuracy: 1e-6)
    XCTAssertEqual(core.modeSwitches.maxLatencyMs, 12.7, accuracy: 1e-6)
  }

  func testAnAllocationFailureKeepsTheMessageQueuedAndThrows() throws {
    let target = ByteArrayGpuTarget()
    let core = GridRendererCore(target: target)
    offerReset(core)
    target.failNextGrowth = true
    XCTAssertThrowsError(try frame(core, target))
    XCTAssertEqual(core.store.counters.allocationFailures, 1)
    XCTAssertEqual(core.scheduler.queuedMessages, 1)
  }

  func testLevelOfDetailPassesFollowTheBaseLayerAndRegionOverlay() {
    func passes(_ mode: GridDisplayState) -> [String] {
      var out: [String] = []
      GridStyleDrawSet.forEachLevelOfDetailPass(mode) { buffer, residency, _ in out.append("\(buffer):\(residency)") }
      return out
    }
    XCTAssertEqual(passes(GridDisplayState()), ["bricks:2", "regions:1"])
    for base in [GridDisplayCodes.basePoints, GridDisplayCodes.baseSplats, GridDisplayCodes.baseVoxels] {
      var mode = GridDisplayState()
      mode.baseLayer = base
      XCTAssertEqual(passes(mode).last, "regions:1")
    }
    var overlay = GridDisplayState()
    overlay.overlayBits = GridDisplayCodes.overlayRegions
    XCTAssertEqual(passes(overlay), ["bricks:2"])
    var none = GridDisplayState()
    none.baseLayer = GridDisplayCodes.baseNone
    XCTAssertEqual(passes(none), [])
  }
}
