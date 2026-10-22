import ARKit
import Flutter
import Metal
import UIKit

/// Process-wide grid renderer: the render and display channel handlers and
/// the capture view's overlay slot share it. Created on first registration
/// with the system Metal device; without Metal there is no renderer and the
/// render channel answers 3 (no renderer attached) and records why
/// ([recordUnavailable]). A failed creation is retried by the next engine
/// registration.
final class GridRendererRegistry {
  static let shared = GridRendererRegistry()

  private let lock = NSLock()
  private var created = false
  private var current: GridRendererHost?
  private var overlay: GridRendererOverlay?
  private var unavailable: String?
  /// Failed renderer creations so far (registry lock).
  private var creationFailures = 0
  private let makeHost: () throws -> GridRendererHost?
  private let sessionLog: NativeSessionLogEmitter

  /// [makeHost] returns nil without a Metal device (tests inject a failing one).
  init(makeHost: @escaping () throws -> GridRendererHost? = GridRendererRegistry.systemHost,
       sessionLog: NativeSessionLogEmitter = .shared) {
    self.makeHost = makeHost
    self.sessionLog = sessionLog
  }

  /// The renderer on the system Metal device, nil without one.
  static func systemHost() throws -> GridRendererHost? {
    guard let device = MTLCreateSystemDefaultDevice() else { return nil }
    return try GridRendererHost(device: device)
  }

  /// The renderer, nil when the device has no Metal or creation failed.
  var host: GridRendererHost? {
    lock.lock(); defer { lock.unlock() }
    return current
  }

  /// Creates the renderer once and installs it in the capture view overlay
  /// slot; after a failed creation the next call tries again.
  func ensureHost() {
    lock.lock()
    if created {
      lock.unlock()
      return
    }
    var made: GridRendererHost?
    do {
      made = try makeHost()
      if made == nil { unavailable = "no Metal device" }
    } catch {
      unavailable = "\(error)"
    }
    if made == nil {
      creationFailures += 1
      NSLog("[GridRenderer] renderer unavailable: %@", unavailable ?? "")
    } else {
      created = true
      unavailable = nil
    }
    current = made
    let adapter = made.map { GridRendererOverlay(host: $0) }
    overlay = adapter
    lock.unlock()
    if let made { Self.recordFailures(of: made, sessionLog: sessionLog) }
    guard let adapter = adapter else { return }
    let install = { GridOverlayRegistry.shared.setOverlay(adapter) }
    if Thread.isMainThread { install() } else { DispatchQueue.main.async(execute: install) }
  }

  /// Reports every failure of [host] to the system log (with the error) and
  /// to the native session log as `RENDERER_FAILED`, the counterpart of the
  /// Android render thread failure: the variant is the failed stage (iOS has
  /// no render thread) and the data is Android's only key,
  /// `renderThreadFailures`, the host's running failure count. Repeats are
  /// counted by the flood policy.
  static func recordFailures(of host: GridRendererHost, sessionLog: NativeSessionLogEmitter) {
    host.onFailure = { failure in
      NSLog("[GridRenderer] renderer failed at %@: %@", failure.stage, failure.error)
      sessionLog.failure(NativeSessionLogCodes.rendererFailed, variant: failure.stage, message: "Grid renderer failed") {
        ["renderThreadFailures": failure.failures]
      }
    }
  }

  /// A render message found no renderer because creation failed: recorded as
  /// `RENDERER_FAILED` (variant `create`, data `renderThreadFailures`: the
  /// running count of failed creations) while a session log is open, so a
  /// grid that never draws is explained; creation can precede the open log.
  /// The flood policy counts the repeats. Nothing is recorded before a
  /// creation or with a renderer.
  func recordUnavailable() {
    lock.lock()
    let failures = current == nil ? creationFailures : 0
    lock.unlock()
    guard failures > 0 else { return }
    sessionLog.failure(NativeSessionLogCodes.rendererFailed, variant: "create", message: "Grid renderer unavailable") {
      ["renderThreadFailures": failures]
    }
  }
}

/// Binary handlers of the render (`capture3d/visibility_grid/render`) and
/// display (`capture3d/visibility_grid/display`) channels, the Swift twin of
/// the Android `GridRendererChannels`. Both run on one background task
/// queue (in arrival order), never on the main thread that draws; the
/// capture view uploads the queued deltas within its frame budget.
///
/// Every message is answered with a 4-byte little-endian status, never nil
/// (the grid isolate sends through `BackgroundIsolateBinaryMessenger`,
/// which cannot deliver a nil reply). Render channel: 0 queued, 1
/// resynchronisation required (the grid calls `requestFullResync` and the
/// next export starts with a reset), 2 malformed, 3 no renderer attached,
/// 4 renderer failed. Display channel: 0 always (handled or ignored).
final class GridRendererChannels {
  static let shared = GridRendererChannels()

  private let lock = NSLock()
  private var channels: [FlutterBasicMessageChannel] = []
  private weak var owner: FlutterBinaryMessenger?
  private let registry: GridRendererRegistry

  init(registry: GridRendererRegistry = .shared) {
    self.registry = registry
  }

  /// Installs both handlers on [messenger] (the latest engine owns them).
  func register(messenger: FlutterBinaryMessenger) {
    registry.ensureHost()
    let registry = self.registry
    let codec = FlutterBinaryCodec.sharedInstance()
    let queue = messenger.makeBackgroundTaskQueue?()
    let render = FlutterBasicMessageChannel(
      name: GridRendererChannelNames.render, binaryMessenger: messenger, codec: codec, taskQueue: queue)
    render.setMessageHandler { message, reply in
      let status = GridRendererChannels.renderStatus(message as? Data, host: registry.host)
      if status == GridRendererStatus.noRenderer { registry.recordUnavailable() }
      reply(ChannelStatusReply.data(status))
    }
    let display = FlutterBasicMessageChannel(
      name: GridRendererChannelNames.display, binaryMessenger: messenger, codec: codec, taskQueue: queue)
    display.setMessageHandler { message, reply in
      if let data = message as? Data, let host = registry.host {
        data.withUnsafeBytes { _ = host.onDisplayMessage($0) }
      }
      reply(ChannelStatusReply.data(GridRendererStatus.ok))
    }
    lock.lock()
    let replaced = channels
    channels = [render, display]
    owner = messenger
    lock.unlock()
    for channel in replaced { channel.setMessageHandler(nil) }
  }

  /// The engine of [messenger] detached: removes the handlers and releases
  /// the GPU buffers when that engine owns them (the next engine's grid
  /// starts from a reset); a later engine's registration stays.
  func unregister(messenger: FlutterBinaryMessenger) {
    lock.lock()
    guard let current = owner, current === messenger else {
      lock.unlock()
      return
    }
    let removed = channels
    channels = []
    owner = nil
    lock.unlock()
    for channel in removed { channel.setMessageHandler(nil) }
    if let host = registry.host {
      let release = { host.releaseBuffers() }
      if Thread.isMainThread { release() } else { DispatchQueue.main.async(execute: release) }
    }
  }

  /// True while the engine of [messenger] owns the handlers.
  func isRegistered(messenger: FlutterBinaryMessenger) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return owner === messenger && !channels.isEmpty
  }

  /// Status of one render message (2.12 row 3).
  static func renderStatus(_ message: Data?, host: GridRendererHost?) -> Int32 {
    guard let message = message else { return GridRendererStatus.malformed }
    guard let host = host else { return GridRendererStatus.noRenderer }
    return message.withUnsafeBytes { host.onRenderMessage($0) }
  }
}

/// Adapts the renderer to the capture view's [GridOverlayRenderer] hook:
/// uploads before the render pass, styles and line overlays inside it with
/// the ARKit camera of the drawn frame.
final class GridRendererOverlay: GridOverlayRenderer, GridOverlayFrameUploader {
  let host: GridRendererHost

  init(host: GridRendererHost) {
    self.host = host
  }

  func prepare(device: MTLDevice, colorPixelFormat: MTLPixelFormat, depthPixelFormat: MTLPixelFormat) {
    prepare(owner: self, device: device, colorPixelFormat: colorPixelFormat, depthPixelFormat: depthPixelFormat)
  }

  func prepare(owner: AnyObject, device: MTLDevice, colorPixelFormat: MTLPixelFormat, depthPixelFormat: MTLPixelFormat) {
    host.beginView()
    guard device === host.device else {
      NSLog("[GridRenderer] capture view uses another Metal device; grid not drawn")
      return
    }
    host.prepare(colorPixelFormat: colorPixelFormat, depthPixelFormat: depthPixelFormat, owner: owner)
  }

  func detach(owner: AnyObject) {
    host.detachView(owner)
  }

  func encodeFrameUploads(into commandBuffer: MTLCommandBuffer) -> Int {
    host.encodeUploads(into: commandBuffer)
  }

  func encodeOverlay(into encoder: MTLRenderCommandEncoder, context: GridOverlayFrameContext) {
    if case .notAvailable = context.trackingState { return }
    let t = context.cameraTransform.columns.3
    let scale = context.viewportSize.width > 0 ? Float(context.drawableSize.width / context.viewportSize.width) : 1
    let view = GridFrameView(
      view: context.viewMatrix, projection: context.projectionMatrix, cameraPosition: SIMD3(t.x, t.y, t.z),
      drawableWidth: Int(context.drawableSize.width), drawableHeight: Int(context.drawableSize.height), pxPerDp: scale)
    host.encode(
      into: encoder, view: view, frameTimestamp: context.frameTimestamp, featurePoints: context.featurePoints)
  }
}
