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
    let slice = writer.message
    XCTAssertEqual(GridRendererChannels.renderStatus(reset, host: host), GridRendererStatus.noRenderer,
                   "no capture view prepared the renderer")
    XCTAssertTrue(host.prepare(colorPixelFormat: .bgra8Unorm, depthPixelFormat: .depth32Float))
    XCTAssertEqual(GridRendererChannels.renderStatus(slice, host: host), GridRendererStatus.resyncRequired)
    XCTAssertEqual(GridRendererChannels.renderStatus(reset, host: host), GridRendererStatus.ok)
    XCTAssertEqual(GridRendererChannels.renderStatus(Data(count: 3), host: host), GridRendererStatus.malformed)
    host.rendererDidFail(stage: "upload", error: "test")
    XCTAssertEqual(GridRendererChannels.renderStatus(reset, host: host), GridRendererStatus.rendererFailed)
  }

  func testRendererFailuresAreRecordedAsRendererFailedPerStage() throws {
    guard let device = MTLCreateSystemDefaultDevice() else { throw XCTSkip("no Metal device") }
    let host = try GridRendererHost(device: device)
    let log = NativeSessionLogEmitter(buffer: NativeSessionLogBuffer(nowNs: { 1 }))
    GridRendererRegistry.recordFailures(of: host, sessionLog: log)
    host.rendererDidFail(stage: "upload", error: "closed log")
    XCTAssertTrue(log.buffer.drain().isEmpty, "nothing is recorded without an open session log")
    log.setActive(true)
    host.rendererDidFail(stage: "upload", error: "allocation failed")
    host.rendererDidFail(stage: "pipelines", error: "no library")
    host.rendererDidFail(stage: "upload", error: "allocation failed again")
    let records = log.buffer.drain()
    XCTAssertEqual(records.count, 3)
    XCTAssertEqual(records.map { $0[0] as? String }, ["F", "F", "C"])
    XCTAssertEqual(records.map { $0[1] as? String }, Array(repeating: "RENDERER_FAILED", count: 3))
    XCTAssertEqual(records[0][5] as? String, "upload")
    XCTAssertEqual(records[1][5] as? String, "pipelines", "a new stage is a new variant, written in full")
    let data = try XCTUnwrap(records[0][4] as? [String: Any])
    XCTAssertEqual(data["stage"] as? String, "upload")
    XCTAssertEqual(data["rendererFailures"] as? Int, 2)
    XCTAssertEqual(data["error"] as? String, "allocation failed")
    XCTAssertEqual(records[2][2] as? Int, 1, "the repeated stage within the flood interval is counted")
  }

  func testARendererThatCouldNotBeCreatedIsRecordedOnRenderMessages() throws {
    struct CreationFailed: Error {}
    let log = NativeSessionLogEmitter(buffer: NativeSessionLogBuffer(nowNs: { 1 }))
    log.setActive(true)
    let registry = GridRendererRegistry(makeHost: { throw CreationFailed() }, sessionLog: log)
    let channels = GridRendererChannels(registry: registry)
    let messenger = TaskQueueRecordingMessenger()
    channels.register(messenger: messenger)
    XCTAssertNil(registry.host)
    let render = try XCTUnwrap(messenger.handlers[GridRendererChannelNames.render])
    writer.begin(RendererDeltaLayout.kindReset, firstSlot: 0, records: 0, capacity: 64, brickKey: 0)
    let reset = writer.message
    var replies: [Data?] = []
    render(reset) { replies.append($0) }
    render(reset) { replies.append($0) }
    XCTAssertEqual(replies, Array(repeating: ChannelStatusReply.data(GridRendererStatus.noRenderer), count: 2))
    let records = log.buffer.drain()
    XCTAssertEqual(records.count, 2)
    XCTAssertEqual(records[0][1] as? String, "RENDERER_FAILED")
    XCTAssertEqual(records[0][3] as? String, "Grid renderer unavailable")
    XCTAssertEqual(records[0][5] as? String, "create")
    let data = try XCTUnwrap(records[0][4] as? [String: Any])
    XCTAssertEqual(data["stage"] as? String, "create")
    XCTAssertEqual(data["error"] as? String, "CreationFailed()")
    XCTAssertEqual(records[1][0] as? String, "C")
    channels.unregister(messenger: messenger)
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

  func testEveryCaptureViewPrepareResetsTheLineOverlays() throws {
    guard let device = MTLCreateSystemDefaultDevice() else { throw XCTSkip("no Metal device") }
    let host = try GridRendererHost(device: device)
    let probe = ResetCountingOverlay()
    host.core.addLineOverlay(probe)
    let overlay = GridRendererOverlay(host: host)
    overlay.prepare(device: device, colorPixelFormat: .bgra8Unorm, depthPixelFormat: .depth32Float)
    XCTAssertEqual(probe.resets, 1)
    overlay.prepare(device: device, colorPixelFormat: .bgra8Unorm, depthPixelFormat: .depth32Float)
    XCTAssertEqual(probe.resets, 2, "a new capture view starts without the previous overlay frames")
    host.releaseBuffers()
    XCTAssertEqual(probe.resets, 3)
  }

  /// The grid belongs to one capture view and one grid session: when the
  /// view that prepared last goes away, or the grid shell stops, buffers,
  /// overlays and display mode are dropped; without a view messages are
  /// answered 3, and the next view's first non-reset message is answered 1.
  /// A replaced view detaching after its successor prepared changes nothing.
  func testViewTeardownAndShellStopDropTheSessionGrid() throws {
    guard let device = MTLCreateSystemDefaultDevice() else { throw XCTSkip("no Metal device") }
    let host = try GridRendererHost(device: device)
    let probe = ResetCountingOverlay()
    host.core.addLineOverlay(probe)
    let overlay = GridRendererOverlay(host: host)
    let firstView = NSObject(), secondView = NSObject()
    writer.begin(RendererDeltaLayout.kindReset, firstSlot: 0, records: 0, capacity: 64, brickKey: 0)
    let reset = writer.message
    writer.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: 0, records: 1, capacity: 64, brickKey: 1)
    let slice = writer.message
    func status(_ message: Data) -> Int32 { GridRendererChannels.renderStatus(message, host: host) }
    let queue = try XCTUnwrap(device.makeCommandQueue())
    func applyQueued() {
      guard let commands = queue.makeCommandBuffer() else { return XCTFail("no command buffer") }
      host.encodeUploads(into: commands)
      commands.commit()
      commands.waitUntilCompleted()
    }

    overlay.prepare(owner: firstView, device: device, colorPixelFormat: .bgra8Unorm, depthPixelFormat: .depth32Float)
    XCTAssertTrue(host.hasAttachedView)
    XCTAssertEqual(status(reset), GridRendererStatus.ok)
    XCTAssertEqual(status(slice), GridRendererStatus.ok)
    applyQueued()
    XCTAssertGreaterThanOrEqual(host.core.store.surfaceSlices.find(1), 0)
    var mode = GridDisplayState()
    _ = host.core.display.snapshotMode(into: &mode, knownVersion: -1)
    let versionBefore = mode.modeVersion

    // A successor view prepares; the replaced view's late teardown keeps it.
    overlay.prepare(owner: secondView, device: device, colorPixelFormat: .bgra8Unorm, depthPixelFormat: .depth32Float)
    overlay.detach(owner: firstView)
    XCTAssertTrue(host.hasAttachedView)
    XCTAssertGreaterThanOrEqual(host.core.store.surfaceSlices.find(1), 0)

    let resetsBefore = probe.resets
    overlay.detach(owner: secondView)
    XCTAssertFalse(host.hasAttachedView)
    XCTAssertLessThan(host.core.store.surfaceSlices.find(1), 0, "the session's slices are gone")
    XCTAssertGreaterThan(probe.resets, resetsBefore)
    XCTAssertTrue(host.core.display.snapshotMode(into: &mode, knownVersion: versionBefore), "display mode reset")
    XCTAssertEqual(mode.baseLayer, GridDisplayState().baseLayer)
    XCTAssertEqual(status(slice), GridRendererStatus.noRenderer)

    overlay.prepare(owner: firstView, device: device, colorPixelFormat: .bgra8Unorm, depthPixelFormat: .depth32Float)
    XCTAssertEqual(status(slice), GridRendererStatus.resyncRequired, "the next view starts from a reset")
    XCTAssertEqual(status(reset), GridRendererStatus.ok)
    XCTAssertEqual(status(slice), GridRendererStatus.ok)
    applyQueued()
    XCTAssertGreaterThanOrEqual(host.core.store.surfaceSlices.find(1), 0)

    host.gridShellStopped()
    XCTAssertLessThan(host.core.store.surfaceSlices.find(1), 0, "a stopped grid session leaves nothing")
    XCTAssertTrue(host.hasAttachedView)
    XCTAssertEqual(status(slice), GridRendererStatus.resyncRequired)
    XCTAssertEqual(status(reset), GridRendererStatus.ok)
  }

  private final class ResetCountingOverlay: GridLineOverlay {
    var resets = 0
    var messageMagic: UInt32 { 0x2222_2222 }
    var greyOutside: Bool { false }
    func onMessage(_ message: UnsafeRawBufferPointer) -> Bool { false }
    func buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink) -> Int { 0 }
    func isInScope(_ x: Float, _ y: Float, _ z: Float) -> Bool { true }
    func reset() { resets += 1 }
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
