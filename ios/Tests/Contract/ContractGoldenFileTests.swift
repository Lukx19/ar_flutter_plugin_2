import XCTest

/// The shared golden contract files (plan contracts 2.4-2.7, 2.11, 2.12)
/// reach the iOS test bundle intact: every file has its contract size, magic,
/// version and kind, and its declared payload or record count matches its
/// length. The Swift shell's own writer tests compare their bytes with these
/// files, so a missing or truncated golden must fail here first.
final class ContractGoldenFileTests: XCTestCase {
  private struct Expectation {
    let tag: String
    let bytes: Int
    /// Kind byte at offset 6, or nil for formats without one there.
    let kind: Int?
  }

  private static let ingestHeaderBytes = 160
  private static let rendererHeaderBytes = 32
  private static let recordingHeaderBytes = 32

  /// Every golden file with its contract size, magic and kind.
  private static let expectations: [String: Expectation] = [
    "ingest_idle.bin": Expectation(tag: "C3VI", bytes: 160, kind: 0),
    "ingest_depth_u16.bin": Expectation(tag: "C3VI", bytes: 196, kind: 1),
    "ingest_points.bin": Expectation(tag: "C3VI", bytes: 224, kind: 2),
    "ingest_voxels.bin": Expectation(tag: "C3VI", bytes: 220, kind: 3),
    "ingest_photo_saved.bin": Expectation(tag: "C3VI", bytes: 288, kind: 4),
    "ingest_markers.bin": Expectation(tag: "C3VI", bytes: 296, kind: 5),
    "ingest_session_event.bin": Expectation(tag: "C3VI", bytes: 176, kind: 6),
    "ingest_pull_request.bin": Expectation(tag: "C3VP", bytes: 8, kind: nil),
    "ingest_recording.bin": Expectation(tag: "C3IR", bytes: 2212, kind: nil),
    "shell_control_start.bin": Expectation(tag: "C3GC", bytes: 32, kind: 1),
    "render_surface_slice.bin": Expectation(tag: "C3RD", bytes: 112, kind: 1),
    "render_brick_summaries.bin": Expectation(tag: "C3RD", bytes: 64, kind: 2),
    "render_occupancy_masks.bin": Expectation(tag: "C3RD", bytes: 96, kind: 3),
    "render_region_summaries.bin": Expectation(tag: "C3RD", bytes: 48, kind: 4),
    "render_free_slots.bin": Expectation(tag: "C3RD", bytes: 40, kind: 5),
    "render_reset.bin": Expectation(tag: "C3RD", bytes: 32, kind: 6),
    "render_frame_end.bin": Expectation(tag: "C3RD", bytes: 32, kind: 7),
    "render_compact_voxels.bin": Expectation(tag: "C3RD", bytes: 56, kind: 8),
    "display_mode.bin": Expectation(tag: "C3DM", bytes: 32, kind: nil),
    "colour_table.bin": Expectation(tag: "C3CT", bytes: 1036, kind: nil),
  ]

  func testEveryGoldenInTheBundleIsListed() throws {
    let names = try GoldenFixture.allNames()
    XCTAssertFalse(names.isEmpty, "golden folder is empty")
    XCTAssertEqual(Set(names), Set(Self.expectations.keys),
                   "add a row for a new golden or restore a missing one")
  }

  func testEachGoldenHasItsContractSizeMagicVersionAndKind() throws {
    for (name, expected) in Self.expectations.sorted(by: { $0.key < $1.key }) {
      let golden = try GoldenFixture.bytes(name)
      XCTAssertEqual(golden.count, expected.bytes, "\(name) size")
      guard golden.count >= 8 else { continue }
      XCTAssertEqual(golden.u32(0), GoldenFixture.magic(expected.tag), "\(name) magic")
      XCTAssertEqual(golden.u16(4), 1, "\(name) version")
      if let kind = expected.kind {
        XCTAssertEqual(golden.u8(6), kind, "\(name) kind")
      }
    }
  }

  /// 2.4: header 160 B, payload length by kind, reserved words zero.
  func testIngestMessagesDeclareTheirPayloadLength() throws {
    for name in Self.expectations.keys.sorted() where Self.expectations[name]!.tag == "C3VI" {
      let message = try GoldenFixture.bytes(name)
      try assertIngestMessage(message, name: name)
    }
  }

  /// 2.4 pull request: 8 B, accepted kind mask in bytes 6-7.
  func testPullRequestAcceptsTheIngestKinds() throws {
    let request = try GoldenFixture.bytes("ingest_pull_request.bin")
    XCTAssertEqual(request.u16(6), 0x7e, "golden accepts kinds 1-6")
  }

  /// 2.7: header 32 B plus record count times the kind's record size.
  func testRendererDeltasDeclareTheirRecordCount() throws {
    let recordBytes = [1: 40, 2: 16, 3: 64, 4: 16, 5: 8, 6: 0, 7: 0, 8: 8]
    for name in Self.expectations.keys.sorted() where Self.expectations[name]!.tag == "C3RD" {
      let delta = try GoldenFixture.bytes(name)
      let kind = delta.u8(6)
      guard let size = recordBytes[kind] else {
        XCTFail("\(name): unknown renderer delta kind \(kind)")
        continue
      }
      let records = delta.u32(16)
      XCTAssertEqual(delta.count, Self.rendererHeaderBytes + records * size, "\(name) length")
      XCTAssertLessThanOrEqual(delta.count, 65_536, "\(name) above the 64 KiB message limit")
    }
  }

  /// 2.7 display messages: colour attribute codes below 5.
  func testDisplayMessagesUseKnownColourAttributes() throws {
    let mode = try GoldenFixture.bytes("display_mode.bin")
    XCTAssertLessThan(mode.u8(8), 5, "display mode colour attribute")
    XCTAssertLessThan(mode.u8(6), 4, "display mode base layer")
    let table = try GoldenFixture.bytes("colour_table.bin")
    XCTAssertLessThan(table.u8(6), 5, "colour table attribute")
    XCTAssertEqual(table.count, 12 + 256 * 4)
  }

  /// 2.11: 32 B header, then `u32 length` plus one valid ingest message,
  /// repeated to the end of the file; the header counts the messages.
  func testRecordingFramesValidIngestMessages() throws {
    let recording = try GoldenFixture.bytes("ingest_recording.bin")
    var at = Self.recordingHeaderBytes
    var messages = 0
    while at < recording.count {
      XCTAssertLessThanOrEqual(at + 4, recording.count, "truncated length prefix at \(at)")
      guard at + 4 <= recording.count else { break }
      let length = recording.u32(at)
      at += 4
      XCTAssertLessThanOrEqual(at + length, recording.count, "message \(messages) overruns the file")
      guard at + length <= recording.count else { break }
      try assertIngestMessage(recording.slice(at, length), name: "recording message \(messages)")
      at += length
      messages += 1
    }
    XCTAssertEqual(at, recording.count, "recording ends on a message boundary")
    XCTAssertEqual(messages, recording.u32(24), "recording header message count")
    XCTAssertEqual(messages, 10)
  }

  private func assertIngestMessage(_ message: GoldenBytes, name: String) throws {
    let header = Self.ingestHeaderBytes
    XCTAssertGreaterThanOrEqual(message.count, header, "\(name) shorter than the header")
    guard message.count >= header else { return }
    XCTAssertEqual(message.u32(0), GoldenFixture.magic("C3VI"), "\(name) magic")
    XCTAssertEqual(message.u16(4), 1, "\(name) version")
    let kind = message.u8(6)
    let payload = message.u32(12)
    XCTAssertEqual(message.count, header + payload, "\(name) payload length")
    XCTAssertLessThanOrEqual(message.count, 262_144, "\(name) above the 256 KiB message limit")
    for word in 0..<3 {
      XCTAssertEqual(message.u32(148 + 4 * word), 0, "\(name) reserved word \(word)")
    }
    XCTAssertLessThan(message.u8(49), 4, "\(name) pose match code")
    guard message.count == header + payload else { return }
    switch kind {
    case 0:
      XCTAssertEqual(payload, 0, "\(name) idle has no payload")
    case 1:
      let width = message.u16(136)
      let height = message.u16(138)
      let stride = message.u16(140)
      let valueBytes = message.u8(142) == 0 ? 2 : 4
      XCTAssertLessThan(message.u8(142), 2, "\(name) depth unit")
      XCTAssertGreaterThanOrEqual(stride, width * valueBytes, "\(name) row stride")
      let confidence = message.u8(143) == 1 ? width * height : 0
      XCTAssertEqual(payload, height * stride + confidence, "\(name) depth payload")
      XCTAssertNotEqual(message.u8(7) & 1, 0, "\(name) depth source flag")
    case 2:
      XCTAssertEqual(payload, 4 + 20 * message.u32(header), "\(name) point payload")
      XCTAssertNotEqual(message.u8(7) & 2, 0, "\(name) points source flag")
    case 3:
      XCTAssertEqual(payload, 4 + 28 * message.u32(header), "\(name) voxel payload")
    case 4:
      XCTAssertEqual(payload, 128, "\(name) photo saved payload")
    case 5:
      XCTAssertEqual(payload, 8 + 64 * message.u16(header), "\(name) marker payload")
    case 6:
      XCTAssertEqual(payload, 16, "\(name) session event payload")
      XCTAssertLessThan(message.u8(header), 9, "\(name) session event code")
    case 8:
      XCTAssertGreaterThanOrEqual(payload, 64, "\(name) tie point batch header")
    default:
      XCTFail("\(name): ingest kind \(kind) is not a pull reply kind")
    }
  }
}
