import ARKit
import XCTest
@testable import ar_flutter_plugin_2

/// `ar_flutter_plugin_2/session_log` replies: never nil, the Android method
/// set, share limited to the export folder.
final class SessionLogChannelTests: XCTestCase {
  private var exportRoot: URL!
  private var sharer: RecordingSharer!
  private var emitter: NativeSessionLogEmitter!

  override func setUpWithError() throws {
    exportRoot = FileManager.default.temporaryDirectory
      .appendingPathComponent("session-log-tests-\(UUID().uuidString)", isDirectory: true)
      .appendingPathComponent(SessionLogExportRoots.folderName, isDirectory: true)
    try FileManager.default.createDirectory(at: exportRoot, withIntermediateDirectories: true)
    sharer = RecordingSharer()
    emitter = NativeSessionLogEmitter()
  }

  override func tearDownWithError() throws {
    try? FileManager.default.removeItem(at: exportRoot.deletingLastPathComponent())
  }

  private func handler(debug: Bool = true) -> SessionLogChannelHandler {
    SessionLogChannelHandler(
      emitter: emitter, sharer: sharer, exportRoots: { [exportRoot] in [exportRoot!] },
      debugBuild: debug, osVersion: "18.6"
    )
  }

  func testIdentitySetActiveDrainAndUnknownMethods() {
    let h = handler()
    XCTAssertEqual(h.reply(method: "identity", arguments: nil)["arVersion"] as? String, "ARKit iOS 18.6")
    XCTAssertEqual(h.reply(method: "setActive", arguments: ["active": true])["status"] as? String, "ok")
    XCTAssertTrue(emitter.active)
    emitter.record("MEMORY_PRESSURE", message: "m")
    let drain = h.reply(method: "drain", arguments: nil)
    XCTAssertEqual(drain["status"] as? String, "ok")
    XCTAssertEqual((drain["records"] as? [[Any]])?.count, 1)
    XCTAssertEqual(h.reply(method: "setActive", arguments: nil)["status"] as? String, "ok")
    XCTAssertFalse(emitter.active, "a missing flag closes the window")
    XCTAssertEqual(h.reply(method: "nope", arguments: nil)["status"] as? String, "notImplemented")
  }

  func testDebugThrowIsGuardedInDebugAndAbsentInRelease() {
    emitter.setActive(true)
    let debug = handler(debug: true).reply(method: "debugThrow", arguments: nil)
    XCTAssertEqual(debug["status"] as? String, "error")
    XCTAssertEqual(debug["code"] as? String, "NATIVE_HANDLER_ERROR")
    XCTAssertEqual(emitter.buffer.drain().first?[1] as? String, "NATIVE_HANDLER_ERROR")
    XCTAssertEqual(handler(debug: false).reply(method: "debugThrow", arguments: nil)["status"] as? String, "notImplemented")
  }

  func testShareAcceptsOnlyFilesInsideTheExportFolder() throws {
    let folder = exportRoot.appendingPathComponent("session-1", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let log = folder.appendingPathComponent("session_log.jsonl")
    try Data("{}\n".utf8).write(to: log)
    let h = handler()

    XCTAssertEqual(h.reply(method: "share", arguments: ["paths": [log.path]])["status"] as? String, "ok")
    XCTAssertEqual(sharer.shared, [[log.path]])

    let sibling = exportRoot.deletingLastPathComponent()
      .appendingPathComponent(SessionLogExportRoots.folderName + "-other/x.txt").path
    let escape = folder.path + "/../../../outside.txt"
    for paths in [[], [sibling], [escape], [folder.path], [folder.appendingPathComponent("missing.txt").path]] {
      XCTAssertEqual(h.reply(method: "share", arguments: ["paths": paths])["status"] as? String, "unavailable", "\(paths)")
    }
    XCTAssertEqual(sharer.shared.count, 1)
    sharer.result = false
    XCTAssertEqual(h.reply(method: "share", arguments: ["paths": [log.path]])["status"] as? String, "unavailable")
  }
}

private final class RecordingSharer: SessionLogSharing {
  var shared: [[String]] = []
  var result = true
  func share(_ files: [URL]) -> Bool {
    if result { shared.append(files.map(\.path)) }
    return result
  }
}
