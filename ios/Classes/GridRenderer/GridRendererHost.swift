import Foundation
import Metal
import simd

/// Instanced grid renderer drawn into the capture view's render pass (the
/// Swift twin of the Android `VisibilityGridRendererHost`; decision 5.2:
/// plain Metal in the `MTKView` host).
///
/// The render and display channels hand messages to [onRenderMessage] and
/// [onDisplayMessage] on their background queue. Once per drawn frame, on
/// the main thread, the capture view calls [encodeUploads] with the frame's
/// command buffer before its render pass (queued deltas within the 64 KiB
/// budget, colour tables) and then [encode] inside the pass after the camera
/// background. Instance buffers belong to the Metal device, not to a view,
/// so a replaced capture view keeps them; only a failure releases them and
/// requires the grid's full resynchronisation (the first message after a
/// working [prepare] is answered 1, resync required).
final class GridRendererHost {
  let device: MTLDevice
  let core: GridRendererCore
  let target: MetalStagedBufferTarget
  let completion = GridFrameCompletion()
  private var draws: GridStyleDrawSet?
  private var preparedFormats: (MTLPixelFormat, MTLPixelFormat)?
  private let lineBatch = GridLineBatch()
  private var frameValue: UInt64?
  private let stateLock = NSLock()
  private var failed = false
  private var failures = 0

  /// Guided-capture quests; drawn only after their first overlay message.
  let questOverlay = QuestLineOverlay()
  let poseFrustumStyle = PoseFrustumStyle()
  /// Marker outlines (`C3MO` from the grid isolate marker stage).
  let markerOverlay = MarkerOutlineOverlay()

  /// Called with a failure description for the session log (any thread).
  var onFailure: ((String) -> Void)?

  init(device: MTLDevice, frameBudgetBytes: Int = DeltaUploadScheduler.defaultFrameBudgetBytes) throws {
    self.device = device
    target = try MetalStagedBufferTarget(device: device)
    core = GridRendererCore(target: target, frameBudgetBytes: frameBudgetBytes)
    core.addLineOverlay(questOverlay)
    core.addLineOverlay(poseFrustumStyle)
    core.addLineOverlay(markerOverlay)
  }

  /// True after a failure (pipelines or a buffer allocation); render
  /// messages are refused with status 4 until a working [prepare].
  var rendererFailed: Bool {
    stateLock.lock(); defer { stateLock.unlock() }
    return failed
  }

  var failureCount: Int {
    stateLock.lock(); defer { stateLock.unlock() }
    return failures
  }

  /// Render channel: returns the 4 B status (0 queued, 1 resync required,
  /// 2 malformed, 4 renderer failed). Channel queue.
  func onRenderMessage(_ message: UnsafeRawBufferPointer) -> Int32 {
    if rendererFailed { return DeltaIntake.rendererFailed.rawValue }
    return core.onRenderMessage(message).rawValue
  }

  /// Display channel: the message is handled or ignored (reply always 0). Channel queue.
  @discardableResult
  func onDisplayMessage(_ message: UnsafeRawBufferPointer) -> DisplayIntake {
    core.onDisplayMessage(message)
  }

  /// Builds the pipelines for the render host's formats (called by every
  /// new render host). Main thread. Returns false when they could not be
  /// built (the renderer failed); a working prepare after a failure accepts
  /// render messages again, starting with a resynchronisation.
  @discardableResult
  func prepare(colorPixelFormat: MTLPixelFormat, depthPixelFormat: MTLPixelFormat) -> Bool {
    if let formats = preparedFormats, formats == (colorPixelFormat, depthPixelFormat), draws != nil {
      recoverIfFailed()
      return true
    }
    do {
      draws = try GridStyleDrawSet(device: device, colorPixelFormat: colorPixelFormat, depthPixelFormat: depthPixelFormat)
      preparedFormats = (colorPixelFormat, depthPixelFormat)
    } catch {
      draws = nil
      preparedFormats = nil
      rendererDidFail("pipelines: \(error)")
      return false
    }
    recoverIfFailed()
    return true
  }

  /// Encodes this frame's uploads into [commandBuffer] (no encoder open).
  /// Main thread, before the render pass. Returns uploaded bytes.
  @discardableResult
  func encodeUploads(into commandBuffer: MTLCommandBuffer) -> Int {
    frameValue = completion.track(commandBuffer)
    guard !rendererFailed else { return 0 }
    target.beginFrame(commandBuffer)
    var uploaded = 0
    do {
      uploaded = try core.frame()
      draws?.syncDisplayResources(core.display, target: target)
    } catch {
      target.endFrame()
      rendererDidFail("upload: \(error)")
      return 0
    }
    target.endFrame()
    return uploaded
  }

  /// Draws the grid styles and line overlays of the current display mode.
  /// Main thread, inside the render pass, after [encodeUploads] of the same frame.
  func encode(into encoder: MTLRenderCommandEncoder, view: GridFrameView) {
    guard let draws = draws, let frameValue = frameValue, !rendererFailed else { return }
    self.frameValue = nil
    encoder.pushDebugGroup("grid styles")
    draws.scope = core.scopeOverlay()
    draws.draw(encoder, mode: core.mode, view: view, store: core.store, buffers: target)
    let cam = view.cameraPosition
    if core.buildOverlayLines(
      camX: cam.x, camY: cam.y, camZ: cam.z, batch: lineBatch, focalPx: view.focalPx, pxPerDp: view.pxPerDp) > 0 {
      draws.drawLines(encoder, batch: lineBatch, view: view, completion: completion, frameValue: frameValue)
    }
    encoder.popDebugGroup()
  }

  /// Draw statistics of the last encoded frame.
  var drawCounters: StyleDrawCounters? { draws?.counters }

  /// A new capture view starts drawing: overlays forget the previous view's
  /// quest frame, marker outlines and pose frusta, as the Android host that
  /// is created per view. Grid buffers stay (they belong to the device).
  func beginView() {
    core.resetLineOverlays()
  }

  /// Releases the GPU buffers (engine detached) and the overlays' frames;
  /// the grid must resend everything.
  func releaseBuffers() {
    core.resetLineOverlays()
    target.releaseBuffers()
    core.store.forgetGpuBuffers()
    core.scheduler.requireReset()
  }

  /// The renderer failed (pipelines or a buffer allocation): buffers are
  /// released and render messages refused until a working [prepare].
  func rendererDidFail(_ reason: String) {
    stateLock.lock()
    failed = true
    failures += 1
    stateLock.unlock()
    // Drop anything queued; the next working frame starts from a reset.
    target.releaseBuffers()
    core.store.forgetGpuBuffers()
    core.scheduler.requireReset()
    onFailure?(reason)
  }

  private func recoverIfFailed() {
    stateLock.lock()
    let wasFailed = failed
    failed = false
    stateLock.unlock()
    if wasFailed { core.scheduler.requireReset() }
  }
}

extension GridFrameView {
  /// The frame view of a camera with [view] and [projection] (Metal clip
  /// space) drawn into a [drawableWidth] x [drawableHeight] target;
  /// [pxPerDp] scales screen-sized overlay elements.
  init(view: simd_float4x4, projection: simd_float4x4, cameraPosition: SIMD3<Float>,
       drawableWidth: Int, drawableHeight: Int, pxPerDp: Float) {
    self.init()
    viewProjection = projection * view
    self.cameraPosition = cameraPosition
    widthPx = max(drawableWidth, 1)
    heightPx = max(drawableHeight, 1)
    focalPx = projection.columns.1.y * Float(heightPx) * 0.5
    self.pxPerDp = pxPerDp
  }
}
