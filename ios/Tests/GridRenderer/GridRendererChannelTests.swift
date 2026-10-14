import Flutter
import Foundation
import Metal
import XCTest

@testable import ar_flutter_plugin_2

/// Render and display channel handlers: one background task queue, a 4-byte
/// status reply for every message (never nil) and engine ownership. Needs
/// Flutter, so it runs only in the app's RunnerTests.
final class GridRendererChannelTests: XCTestCase {
  private let writer = RendererDeltaWriter()

  func testRenderAndDisplayHandlersRunOnOneBackgroundTaskQueue() throws {
    let channels = GridRendererChannels()
    let messenger = TaskQueueRecordingMessenger()
    channels.register(messenger: messenger)
    XCTAssertEqual(messenger.handledChannels, [GridRendererChannelNames.render, GridRendererChannelNames.display])
    let render = try XCTUnwrap(messenger.queues[GridRendererChannelNames.render])
    XCTAssertTrue(render === messenger.created.first)
    XCTAssertTrue(messenger.queues[GridRendererChannelNames.display] === render)
    XCTAssertEqual(messenger.created.count, 1)
  }

  func testEveryRenderAndDisplayMessageGetsAFourByteStatusNeverNil() throws {
    let channels = GridRendererChannels()
    let messenger = TaskQueueRecordingMessenger()
    channels.register(messenger: messenger)
    let render = try XCTUnwrap(messenger.handlers[GridRendererChannelNames.render])
    let display = try XCTUnwrap(messenger.handlers[GridRendererChannelNames.display])
    func status(_ handler: FlutterBinaryMessageHandler, _ message: Data?) -> Int32? {
      var replies: [Data?] = []
      handler(message) { replies.append($0) }
      XCTAssertEqual(replies.count, 1)
      guard let reply = replies.first ?? nil else { return nil }
      XCTAssertEqual(reply.count, 4)
      return reply.withUnsafeBytes { Int32(littleEndian: $0.loadUnaligned(as: Int32.self)) }
    }
    XCTAssertEqual(status(render, nil), GridRendererStatus.malformed)
    XCTAssertEqual(status(render, Data(count: 3)), GridRendererStatus.malformed)
    writer.begin(RendererDeltaLayout.kindFrameEnd, firstSlot: 0, records: 0, capacity: 0, brickKey: 0)
    let frameEnd = writer.message
    let expected: Set<Int32> = [GridRendererStatus.ok, GridRendererStatus.resyncRequired, GridRendererStatus.noRenderer]
    XCTAssertTrue(expected.contains(try XCTUnwrap(status(render, frameEnd))))
    for message: Data? in [nil, Data(count: 3), Data(count: 8)] {
      XCTAssertEqual(status(display, message), GridRendererStatus.ok)
    }
    writer.displayMode(baseLayer: GridDisplayCodes.basePoints, overlayBits: 0,
                       colourAttribute: GridDisplayCodes.colourCoverage, sizeRule: 0)
    XCTAssertEqual(status(display, writer.message), GridRendererStatus.ok)
  }

  func testRenderStatusesForMissingFailedAndWorkingRenderers() throws {
    writer.begin(RendererDeltaLayout.kindReset, firstSlot: 0, records: 0, capacity: 64, brickKey: 0)
    let reset = writer.message
    XCTAssertEqual(GridRendererChannels.renderStatus(reset, host: nil), GridRendererStatus.noRenderer)
    XCTAssertEqual(GridRendererChannels.renderStatus(nil, host: nil), GridRendererStatus.malformed)
    guard let device = MTLCreateSystemDefaultDevice() else { throw XCTSkip("no Metal device") }
    let host = try GridRendererHost(device: device)
    writer.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: 0, records: 1, capacity: 64, brickKey: 1)
    XCTAssertEqual(GridRendererChannels.renderStatus(writer.message, host: host), GridRendererStatus.resyncRequired)
    XCTAssertEqual(GridRendererChannels.renderStatus(reset, host: host), GridRendererStatus.ok)
    XCTAssertEqual(GridRendererChannels.renderStatus(Data(count: 3), host: host), GridRendererStatus.malformed)
    host.rendererDidFail("test")
    XCTAssertEqual(GridRendererChannels.renderStatus(reset, host: host), GridRendererStatus.rendererFailed)
  }

  func testDetachingAnEngineReleasesOnlyItsOwnHandlers() {
    let channels = GridRendererChannels()
    let first = TaskQueueRecordingMessenger()
    let second = TaskQueueRecordingMessenger()
    channels.register(messenger: first)
    XCTAssertTrue(channels.isRegistered(messenger: first))
    channels.unregister(messenger: second)
    XCTAssertTrue(channels.isRegistered(messenger: first), "another engine's detach leaves the handlers")
    channels.register(messenger: second)
    XCTAssertTrue(first.handledChannels.isEmpty, "the latest engine owns the handlers")
    channels.unregister(messenger: first)
    XCTAssertTrue(channels.isRegistered(messenger: second))
    channels.unregister(messenger: second)
    XCTAssertFalse(channels.isRegistered(messenger: second))
    XCTAssertTrue(second.handledChannels.isEmpty)
  }
}

/// Records channel handlers and the task queues they were installed on.
private final class TaskQueueRecordingMessenger: NSObject, FlutterBinaryMessenger {
  private(set) var handlers: [String: FlutterBinaryMessageHandler] = [:]
  private(set) var queues: [String: NSObjectProtocol] = [:]
  private(set) var created: [NSObjectProtocol] = []
  private var connections: [FlutterBinaryMessengerConnection: String] = [:]
  private var nextConnection: FlutterBinaryMessengerConnection = 0

  var handledChannels: Set<String> { Set(handlers.keys) }

  func makeBackgroundTaskQueue() -> NSObjectProtocol & FlutterTaskQueue {
    let queue = RecordedTaskQueue()
    created.append(queue)
    return queue
  }

  func send(onChannel channel: String, message: Data?) {}

  func send(onChannel channel: String, message: Data?, binaryReply callback: FlutterBinaryReply? = nil) {}

  func setMessageHandlerOnChannel(
    _ channel: String, binaryMessageHandler handler: FlutterBinaryMessageHandler? = nil
  ) -> FlutterBinaryMessengerConnection {
    record(channel, handler, queue: nil)
  }

  func setMessageHandlerOnChannel(
    _ channel: String, binaryMessageHandler handler: FlutterBinaryMessageHandler? = nil,
    taskQueue: (NSObjectProtocol & FlutterTaskQueue)?
  ) -> FlutterBinaryMessengerConnection {
    record(channel, handler, queue: taskQueue)
  }

  private func record(_ channel: String, _ handler: FlutterBinaryMessageHandler?, queue: NSObjectProtocol?)
    -> FlutterBinaryMessengerConnection {
    handlers[channel] = handler
    queues[channel] = queue
    guard handler != nil else { return 0 }
    nextConnection += 1
    connections[nextConnection] = channel
    return nextConnection
  }

  func cleanUpConnection(_ connection: FlutterBinaryMessengerConnection) {
    guard let channel = connections.removeValue(forKey: connection) else { return }
    handlers[channel] = nil
    queues[channel] = nil
  }
}

private final class RecordedTaskQueue: NSObject, FlutterTaskQueue {}
