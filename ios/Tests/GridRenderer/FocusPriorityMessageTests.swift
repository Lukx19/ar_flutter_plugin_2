import Foundation
import XCTest
@testable import ar_flutter_plugin_2

final class FocusPriorityMessageTests: XCTestCase {
  private func message(count: Int = 1, priority: Float = 0.75) -> Data {
    var bytes = [UInt8](repeating: 0, count: 8 + count * 16)
    func word(_ offset: Int, _ value: UInt32) {
      for i in 0..<4 { bytes[offset + i] = UInt8(truncatingIfNeeded: value >> UInt32(8 * i)) }
    }
    word(0, contractMagic("C3FP"))
    bytes[4] = 1
    bytes[6] = UInt8(count)
    for i in 0..<count {
      word(8 + i * 16, Float(1).bitPattern)
      word(12 + i * 16, Float(2).bitPattern)
      word(16 + i * 16, Float(-3).bitPattern)
      word(20 + i * 16, priority.bitPattern)
    }
    return Data(bytes)
  }

  func testBoundedSnapshotAndMalformedUpdatesPreservePreviousPaint() {
    let handler = DisplayModeHandler()
    XCTAssertEqual(handler.onMessage(message(count: 64)), .overlay)
    var out = [Float](repeating: 0, count: 256)
    XCTAssertEqual(handler.snapshotFocus(into: &out, knownVersion: -1), 64)
    XCTAssertEqual(out[255], 0.75)
    let version = handler.currentFocusVersion
    for bad in [message(count: 65), message(priority: .nan), message(priority: 1.1), Data(message().prefix(23))] {
      XCTAssertEqual(handler.onMessage(bad), .rejected)
      XCTAssertEqual(handler.currentFocusVersion, version)
    }
    _ = handler.snapshotFocus(into: &out, knownVersion: -1)
    XCTAssertEqual(out[255], 0.75)
    XCTAssertEqual(handler.onMessage(message(count: 0)), .overlay)
    XCTAssertEqual(handler.snapshotFocus(into: &out, knownVersion: -1), 0)
    XCTAssertTrue(out.allSatisfy { $0 == 0 })
    _ = handler.onMessage(message())
    handler.reset()
    XCTAssertEqual(handler.snapshotFocus(into: &out, knownVersion: -1), 0)
  }
}
