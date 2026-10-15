import Flutter
import Foundation
import XCTest
@testable import ar_flutter_plugin_2

/// Engine detach of the plugin: every per-engine service is released with
/// the messenger kept from registration (the registrar's messenger may be
/// nil while the engine deallocates), once, and only for the engine that
/// owns it.
final class PluginEngineDetachTests: XCTestCase {
  private static let channels: Set = [
    VisibilityGridChannels.ingest, VisibilityGridChannels.control, VisibilityGridChannels.poseTracking,
    GridRendererChannelNames.render, GridRendererChannelNames.display,
    SessionLogChannelHandler.channelName, WorldMapChannelHandler.channelName,
    NativeCapturePreviewChannel.name, NativeStoreFormatVersions.channelName,
  ]

  private func makeServices() -> PluginEngineServices {
    PluginEngineServices(
      gridShell: VisibilityGridShellRegistry(),
      gridRenderer: GridRendererChannels(),
      sessionLifecycle: SessionLifecycleServices(),
      capture: NativeCaptureRegistration())
  }

  private func registered(_ services: PluginEngineServices, _ messenger: FlutterBinaryMessenger) -> [Bool] {
    [
      services.gridShell.isRegistered(messenger: messenger),
      services.gridRenderer.isRegistered(messenger: messenger),
      services.sessionLifecycle.isRegistered(messenger: messenger),
      services.capture.isRegistered(messenger: messenger),
    ]
  }

  func testDetachReleasesEveryServiceWithTheRegistrationMessengerOnce() {
    let services = makeServices()
    let messenger = ChannelRecordingMessenger()
    let plugin = SwiftArFlutterPlugin(messenger: messenger, services: services)
    services.register(messenger: messenger)
    XCTAssertEqual(messenger.handledChannels, Self.channels)
    XCTAssertEqual(registered(services, messenger), [true, true, true, true])

    plugin.detach()
    XCTAssertEqual(registered(services, messenger), [false, false, false, false])
    XCTAssertTrue(messenger.handledChannels.isEmpty)

    // A second engine registered after the detach keeps its services when
    // the first plugin instance is detached again.
    let next = ChannelRecordingMessenger()
    services.register(messenger: next)
    plugin.detach()
    XCTAssertEqual(registered(services, next), [true, true, true, true])
    XCTAssertEqual(next.handledChannels, Self.channels)
    services.unregister(messenger: next)
  }

  func testAStaleEngineDetachKeepsTheNewerEngineServices() {
    let services = makeServices()
    let first = ChannelRecordingMessenger()
    let second = ChannelRecordingMessenger()
    let firstPlugin = SwiftArFlutterPlugin(messenger: first, services: services)
    services.register(messenger: first)
    let secondPlugin = SwiftArFlutterPlugin(messenger: second, services: services)
    services.register(messenger: second)
    XCTAssertTrue(first.handledChannels.isEmpty, "the latest engine owns the channels")

    firstPlugin.detach()
    XCTAssertEqual(registered(services, second), [true, true, true, true])
    XCTAssertEqual(second.handledChannels, Self.channels)

    secondPlugin.detach()
    XCTAssertEqual(registered(services, second), [false, false, false, false])
    XCTAssertTrue(second.handledChannels.isEmpty)
  }
}

/// Records channel handlers; without background task queues, handlers run
/// on the calling thread. A channel clears its handler either with a nil
/// handler or by cleaning up its connection.
private final class ChannelRecordingMessenger: NSObject, FlutterBinaryMessenger {
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

  func setMessageHandlerOnChannel(
    _ channel: String, binaryMessageHandler handler: FlutterBinaryMessageHandler? = nil,
    taskQueue: (NSObjectProtocol & FlutterTaskQueue)?
  ) -> FlutterBinaryMessengerConnection {
    setMessageHandlerOnChannel(channel, binaryMessageHandler: handler)
  }

  func cleanUpConnection(_ connection: FlutterBinaryMessengerConnection) {
    guard let channel = connections.removeValue(forKey: connection) else { return }
    handlers[channel] = nil
  }
}
