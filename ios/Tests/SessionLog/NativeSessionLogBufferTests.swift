import XCTest
@testable import ar_flutter_plugin_2

/// Flood policy, capacity bound and drain of the native session log buffer
/// (the Swift twin of the Android `NativeSessionLogBufferTest`).
final class NativeSessionLogBufferTests: XCTestCase {
  private var now: UInt64 = 1_000_000_000

  private func makeBuffer(capacity: Int = 256) -> NativeSessionLogBuffer {
    NativeSessionLogBuffer(capacity: capacity, nowNs: { [unowned self] in self.now })
  }

  func testFirstOccurrenceIsFullThenCountedWithinTheInterval() {
    let buffer = makeBuffer()
    buffer.record("MEMORY_PRESSURE", message: "first", data: ["trimLevel": "memoryWarning"], variant: "memoryWarning")
    now += 1_000_000_000
    buffer.record("MEMORY_PRESSURE", message: "second", variant: "memoryWarning")
    buffer.record("MEMORY_PRESSURE", message: "third", variant: "memoryWarning")

    let drained = buffer.drain()
    XCTAssertEqual(drained.count, 2)
    XCTAssertEqual(drained[0][0] as? String, "F")
    XCTAssertEqual(drained[0][1] as? String, "MEMORY_PRESSURE")
    XCTAssertEqual(drained[0][2] as? String, "warning")
    XCTAssertEqual(drained[0][3] as? String, "first")
    XCTAssertEqual((drained[0][4] as? [String: Any])?["trimLevel"] as? String, "memoryWarning")
    XCTAssertEqual(drained[0][5] as? String, "memoryWarning")
    XCTAssertEqual(drained[1][0] as? String, "C")
    XCTAssertEqual(drained[1][1] as? String, "MEMORY_PRESSURE")
    XCTAssertEqual(drained[1][2] as? Int, 2)
    XCTAssertEqual(drained[1][3] as? String, "third")
    XCTAssertTrue(buffer.drain().isEmpty, "drain empties the buffer and the counts")
  }

  /// `always` budget codes keep every occurrence in full inside the interval
  /// (a quick second background stay keeps its own `heldMs`).
  func testAlwaysBudgetCodesAreFullOnEveryOccurrence() {
    let buffer = makeBuffer()
    for held in [100, 200] {
      buffer.record("BACKGROUND_TASK_ENDED", message: "ended", data: ["endReason": "foreground", "heldMs": held],
                    variant: "foreground", level: .info)
      now += 1_000_000_000
    }
    buffer.record("WORLD_MAP_RELOCALIZED", message: "a", level: .info)
    buffer.record("WORLD_MAP_RELOCALIZED", message: "b", level: .info)
    buffer.record("THERMAL_THROTTLING", message: "a")
    buffer.record("THERMAL_THROTTLING", message: "b")
    let drained = buffer.drain()
    XCTAssertEqual(drained.filter { $0[0] as? String == "F" }.map { $0[1] as? String },
                   ["BACKGROUND_TASK_ENDED", "BACKGROUND_TASK_ENDED", "WORLD_MAP_RELOCALIZED", "WORLD_MAP_RELOCALIZED",
                    "THERMAL_THROTTLING"])
    XCTAssertEqual(drained.filter { $0[0] as? String == "F" }.compactMap { ($0[4] as? [String: Any])?["heldMs"] as? Int }, [100, 200])
    XCTAssertEqual(drained.filter { $0[0] as? String == "C" }.map { $0[1] as? String }, ["THERMAL_THROTTLING"])
  }

  func testAfterTheIntervalAnOccurrenceIsFullAgain() {
    let buffer = makeBuffer()
    buffer.record("THERMAL_THROTTLING", message: "a")
    now += 12_000_000_000
    buffer.record("THERMAL_THROTTLING", message: "b")
    XCTAssertEqual(buffer.drain().map { $0[0] as? String }, ["F", "F"])
  }

  func testNewVariantsAreFullUpToTheLimit() {
    let buffer = makeBuffer()
    for i in 0..<(NativeSessionLogBuffer.maxVariants + 2) {
      buffer.record("TRACKING_LIMITED", message: "v\(i)", variant: "v\(i)")
    }
    let drained = buffer.drain()
    XCTAssertEqual(drained.filter { $0[0] as? String == "F" }.count, NativeSessionLogBuffer.maxVariants)
    XCTAssertEqual(drained.last?[0] as? String, "C")
    XCTAssertEqual(drained.last?[2] as? Int, 2)
  }

  func testUserActionCodesUseTheShorterInterval() {
    let buffer = makeBuffer()
    buffer.record("CAPTURE_REFUSED_OTHER", message: "a")
    now += 2_000_000_000
    buffer.record("CAPTURE_REFUSED_OTHER", message: "b")
    buffer.record("MEMORY_PRESSURE", message: "c")
    now += 2_000_000_000
    buffer.record("MEMORY_PRESSURE", message: "d")
    let drained = buffer.drain()
    XCTAssertEqual(drained.filter { $0[1] as? String == "CAPTURE_REFUSED_OTHER" && $0[0] as? String == "F" }.count, 2)
    XCTAssertEqual(drained.filter { $0[1] as? String == "MEMORY_PRESSURE" && $0[0] as? String == "F" }.count, 1)
  }

  func testCapacityBoundCountsInsteadAndReportsTheDrop() {
    let buffer = makeBuffer(capacity: 3)
    for i in 0..<5 {
      buffer.record("CODE_\(i)", message: "m\(i)")
    }
    let drained = buffer.drain()
    let full = drained.filter { $0[0] as? String == "F" }
    XCTAssertEqual(full.count, 4, "three records plus the drop record")
    XCTAssertEqual(full.last?[1] as? String, NativeSessionLogBuffer.eventsDroppedCode)
    XCTAssertEqual((full.last?[4] as? [String: Any])?["countedInsteadOfWritten"] as? Int, 2)
    XCTAssertTrue(full.last?[5] is NSNull)
    let counted = drained.filter { $0[0] as? String == "C" }.map { $0[1] as? String }
    XCTAssertEqual(counted, ["CODE_3", "CODE_4"])
    // The bound frees with the drain.
    buffer.record("CODE_5", message: "m5")
    XCTAssertEqual(buffer.drain().count, 1)
  }

  func testAdmittedRecordSkipsThePolicyAndResetForgetsEverything() {
    let buffer = makeBuffer()
    XCTAssertTrue(buffer.occur("POSE_REFUSED", variant: "anchorQuery"))
    buffer.record("POSE_REFUSED", message: "x", variant: "anchorQuery", admitted: true)
    XCTAssertFalse(buffer.occur("POSE_REFUSED", variant: "anchorQuery"))
    buffer.reset()
    XCTAssertTrue(buffer.drain().isEmpty)
    XCTAssertTrue(buffer.occur("POSE_REFUSED", variant: "anchorQuery"), "a reset forgets the last full time")
  }

  func testEmitterRecordsOnlyWhileActiveAndGuardsHandlerFailures() {
    let emitter = NativeSessionLogEmitter(buffer: makeBuffer())
    emitter.record("MEMORY_PRESSURE", message: "closed")
    XCTAssertTrue(emitter.buffer.drain().isEmpty, "no log open")
    emitter.setActive(true)
    XCTAssertEqual(emitter.activeGeneration, 1)
    let reply = emitter.guarded(channel: "c", method: "m") { throw SessionLogChannelError.forcedHandlerFailure }
    XCTAssertEqual(reply["status"] as? String, "error")
    XCTAssertEqual(reply["code"] as? String, NativeSessionLogEmitter.nativeHandlerErrorCode)
    let drained = emitter.buffer.drain()
    XCTAssertEqual(drained.first?[1] as? String, NativeSessionLogEmitter.nativeHandlerErrorCode)
    XCTAssertEqual(drained.first?[5] as? String, "c/m/SessionLogChannelError")
    emitter.setActive(false)
    emitter.setActive(true)
    XCTAssertEqual(emitter.activeGeneration, 2)
  }
}
