import Foundation
import XCTest

@testable import ar_flutter_plugin_2

/// Debug ingest recording in the shared `C3IR` format (contract 2.11).
final class IngestRecorderTests: XCTestCase {
  func testRecordsRepliedMessagesWithHeaderAndCount() throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ingest-recorder-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: dir) }
    let recorder = IngestRecorder(directory: dir, deviceModel: "iPhone13,3", wallClockNs: { 42 })
    let clock = FakeIngestClock(5)
    let shell = VisibilityGridIngestShell(clock: clock, recorder: recorder)
    shell.start(IngestShellConfig(flags: 0, depthSourcePreference: 0, runMode: 0, markerDetectionHz: 0,
                                  markerSizeMetres: 0, groupEpoch: 1))
    XCTAssertTrue(shell.setRecording(true))
    let capture = ReplyCapture()
    shell.pull.onPull(pullRequest(), reply: capture.reply)
    shell.sessionEvents.emit(event: IngestMessageLayout.sessionTrackingLost, value: 2, sampleNs: 9, trackingState: 2, groupEpoch: 1)
    shell.pull.onPull(pullRequest(), reply: capture.reply)
    XCTAssertEqual(capture.count, 2)
    XCTAssertTrue(shell.setRecording(false))
    let path = try XCTUnwrap(recorder.path)
    let bytes = try Data(contentsOf: URL(fileURLWithPath: path))
    XCTAssertEqual(bytes.u32(0), IngestRecordingLayout.magic)
    XCTAssertEqual(Int(bytes.u16(4)), IngestRecordingLayout.version)
    XCTAssertEqual(bytes.i64(IngestRecordingLayout.deviceModelHash), IngestRecorder.deviceModelHash("iPhone13,3"))
    XCTAssertEqual(bytes.i64(IngestRecordingLayout.createdTimeNs), 42)
    // The recording started event, then the tracking lost event (the stop event follows the last reply).
    XCTAssertEqual(bytes.u32(IngestRecordingLayout.messageCount), 2)
    let first = IngestRecordingLayout.headerBytes
    let length = Int(bytes.u32(first))
    XCTAssertEqual(length, IngestMessageLayout.headerBytes + IngestMessageLayout.sessionEventBytes)
    XCTAssertEqual(bytes.u8(first + 4 + IngestMessageLayout.kind), IngestMessageLayout.kindSessionEvent)
    XCTAssertEqual(bytes.u8(first + 4 + IngestMessageLayout.headerBytes), IngestMessageLayout.sessionRecordingStarted)
    XCTAssertEqual(bytes.count, IngestRecordingLayout.headerBytes + 2 * (4 + length))
  }

  func testDeviceModelHashIsFnv1aOfTheUtf16Units() {
    XCTAssertEqual(IngestRecorder.deviceModelHash(nil), Int64(bitPattern: 0xcbf2_9ce4_8422_2325))
    XCTAssertEqual(IngestRecorder.deviceModelHash("a"), Int64(bitPattern: 0xaf63_dc4c_8601_ec8c))
  }
}
