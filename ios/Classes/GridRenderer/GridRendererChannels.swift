import ARKit
import Flutter
import Metal
import UIKit

/// Process-wide grid renderer: the render and display channel handlers and
/// the capture view's overlay slot share it. Created on first registration
/// with the system Metal device; without Metal there is no renderer and the
/// render channel answers 3 (no renderer attached).
final class GridRendererRegistry {
  static let shared = GridRendererRegistry()

  private let lock = NSLock()
  private var created = false
  private var current: GridRendererHost?
  private var overlay: GridRendererOverlay?

  /// The renderer, nil when the device has no Metal or creation failed.
  var host: GridRendererHost? {
    lock.lock(); defer { lock.unlock() }
    return current
  }

  /// Creates the renderer once and installs it in the capture view overlay slot.
  func ensureHost() {
    lock.lock()
    if created {
      lock.unlock()
      return
    }
    created = true
    var made: GridRendererHost?
    if let device = MTLCreateSystemDefaultDevice() {
      do {
        made = try GridRendererHost(device: device)
      } catch {
        NSLog("[GridRenderer] renderer unavailable: %@", "\(error)")
      }
    }
    current = made
    let adapter = made.map { GridRendererOverlay(host: $0) }
    overlay = adapter
    lock.unlock()
    made?.onFailure = { reason in NSLog("[GridRenderer] renderer failed: %@", reason) }
    guard let adapter = adapter else { return }
    let install = { GridOverlayRegistry.shared.setOverlay(adapter) }
    if Thread.isMainThread { install() } else { DispatchQueue.main.async(execute: install) }
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
      reply(ChannelStatusReply.data(GridRendererChannels.renderStatus(message as? Data, host: registry.host)))
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
    host.beginView()
    guard device === host.device else {
      NSLog("[GridRenderer] capture view uses another Metal device; grid not drawn")
      return
    }
    host.prepare(colorPixelFormat: colorPixelFormat, depthPixelFormat: depthPixelFormat)
  }

  func encodeFrameUploads(into commandBuffer: MTLCommandBuffer) {
    host.encodeUploads(into: commandBuffer)
  }

  func encodeOverlay(into encoder: MTLRenderCommandEncoder, context: GridOverlayFrameContext) {
    if case .notAvailable = context.trackingState { return }
    let t = context.cameraTransform.columns.3
    let scale = context.viewportSize.width > 0 ? Float(context.drawableSize.width / context.viewportSize.width) : 1
    let view = GridFrameView(
      view: context.viewMatrix, projection: context.projectionMatrix, cameraPosition: SIMD3(t.x, t.y, t.z),
      drawableWidth: Int(context.drawableSize.width), drawableHeight: Int(context.drawableSize.height), pxPerDp: scale)
    host.encode(into: encoder, view: view)
  }
}
