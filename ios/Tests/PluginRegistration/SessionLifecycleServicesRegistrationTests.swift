import Flutter
import Foundation
import XCTest
@testable import ar_flutter_plugin_2

/// Engine registration of the session log and world map channels: the
/// latest engine owns them, and an engine detaching releases only its own.
final class SessionLifecycleServicesRegistrationTests: XCTestCase {
  func testLatestEngineOwnsTheChannelsAndDetachReleasesOnlyItsOwn() {
    let services = SessionLifecycleServices()
    let first = MethodChannelRecordingMessenger()
    let second = MethodChannelRecordingMessenger()
    let channels: Set = [SessionLogChannelHandler.channelName, WorldMapChannelHandler.channelName]

    services.register(messenger: first)
    XCTAssertEqual(first.handledChannels, channels)
    services.register(messenger: second)
    XCTAssertTrue(first.handledChannels.isEmpty, "the latest engine owns the channels")
    XCTAssertEqual(second.handledChannels, channels)
    services.unregister(messenger: first)
    XCTAssertTrue(services.isRegistered(messenger: second), "a stale engine's detach keeps the new channels")
    services.unregister(messenger: second)
    XCTAssertFalse(services.isRegistered(messenger: second))
    XCTAssertTrue(second.handledChannels.isEmpty)
  }
}

/// Records channel handlers (see `VisibilityGridShellRegistrationTests`).
private final class MethodChannelRecordingMessenger: NSObject, FlutterBinaryMessenger {
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
