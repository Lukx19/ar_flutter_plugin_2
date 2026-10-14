import Flutter
import Foundation
import XCTest
@testable import ar_flutter_plugin_2

/// Engine registration of the grid shell: the latest engine owns it, and an
/// engine detaching releases only its own registration. (A separate folder
/// from `VisibilityGrid/`: those tests also run on the macOS host without
/// Flutter.)
final class VisibilityGridShellRegistrationTests: XCTestCase {
  func testDetachingAnEngineReleasesOnlyItsOwnShell() throws {
    let registry = VisibilityGridShellRegistry()
    let first = RecordingBinaryMessenger()
    let second = RecordingBinaryMessenger()
    let channels = [VisibilityGridChannels.ingest, VisibilityGridChannels.control, VisibilityGridChannels.poseTracking]

    registry.register(messenger: first)
    XCTAssertTrue(registry.isRegistered(messenger: first))
    XCTAssertEqual(first.handledChannels, Set(channels))
    registry.unregister(messenger: second)
    XCTAssertTrue(registry.isRegistered(messenger: first), "another engine's detach leaves the shell")

    registry.register(messenger: second)
    XCTAssertTrue(first.handledChannels.isEmpty, "the latest engine owns the shell")
    XCTAssertEqual(second.handledChannels, Set(channels))
    registry.unregister(messenger: first)
    XCTAssertTrue(registry.isRegistered(messenger: second), "a stale engine's detach keeps the new shell")

    // A parked pull of the owning engine is answered idle when it detaches.
    var replies: [Data] = []
    let ingest = try XCTUnwrap(second.handlers[VisibilityGridChannels.ingest])
    ingest(pullRequest()) { replies.append($0 ?? Data()) }
    XCTAssertTrue(replies.isEmpty, "parked")
    registry.unregister(messenger: second)
    XCTAssertFalse(registry.isRegistered(messenger: second))
    XCTAssertNil(registry.activeShell)
    XCTAssertTrue(second.handledChannels.isEmpty)
    XCTAssertEqual(replies.count, 1)
    XCTAssertEqual(replies.first?.ingestKind, IngestMessageLayout.kindIdle)
  }
}

/// Records channel handlers; without background task queues, handlers run
/// on the calling thread. A channel clears its handler either with a nil
/// handler or by cleaning up its connection.
private final class RecordingBinaryMessenger: NSObject, FlutterBinaryMessenger {
  private(set) var handlers: [String: FlutterBinaryMessageHandler] = [:]
  private var connections: [FlutterBinaryMessengerConnection: String] = [:]
  private var nextConnection: FlutterBinaryMessengerConnection = 0

  var handledChannels: Set<String> { Set(handlers.keys) }

  func send(onChannel channel: String, message: Data?) {}

  func send(onChannel channel: String, message: Data?, binaryReply callback: FlutterBinaryReply? = nil) {}

  func setMessageHandlerOnChannel(
    _ channel: String, binaryMessageHandler handler: FlutterBinaryMessageHandler? = nil
  ) -> FlutterBinaryMessengerConnection {
    handlers[channel] = handler
    guard handler != nil else { return 0 }
    nextConnection += 1
    connections[nextConnection] = channel
    return nextConnection
  }

  func cleanUpConnection(_ connection: FlutterBinaryMessengerConnection) {
    guard let channel = connections.removeValue(forKey: connection) else { return }
    handlers[channel] = nil
  }
}
