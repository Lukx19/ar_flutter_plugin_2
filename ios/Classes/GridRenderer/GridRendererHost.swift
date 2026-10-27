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
/// background. Like the Android host that lives with its view, the grid
/// belongs to one capture view and one grid session: until a view prepares
/// it, render messages are answered 3 (no renderer attached); when that
/// view goes away ([detachView]) or the grid shell stops ([gridShellStopped])
/// the instance buffers, line overlays and display mode are dropped and the
/// next session starts from a full resynchronisation (the first non-reset
/// message is answered 1). A failure releases the buffers too.
final class GridRendererHost {
  let device: MTLDevice
  let core: GridRendererCore
  let target: MetalStagedBufferTarget
  let completion = GridFrameCompletion()
  private var draws: GridStyleDrawSet?
  private var preparedFormats: (MTLPixelFormat, MTLPixelFormat)?
  private let lineBatch = GridLineBatch()
  private let volumeBatch = GridLineBatch(capacitySegments: 2048, volumetric: true)
  private var frameValue: UInt64?
  private let stateLock = NSLock()
  private var failed = false
  private var failures = 0
  /// A capture view prepared the renderer and has not gone away (state lock).
  private var viewAttached = false
  /// The render loop that prepared last; only it detaches the view.
  private var attachedOwner: ObjectIdentifier?
  /// Messages were refused while no view was attached (state lock): the
  /// grid must resend everything once one prepares.
  private var resyncOnAttach = false

  /// Guided-capture quests; drawn only after their first overlay message.
  let questOverlay = QuestLineOverlay()
  let poseFrustumStyle = PoseFrustumStyle()
  /// Marker outlines (`C3MO` from the grid isolate marker stage).
  let markerOverlay = MarkerOutlineOverlay()

  /// Called with each failure for the session log (any thread).
  var onFailure: ((GridRendererFailure) -> Void)?

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
    stateLock.lock()
    if failed {
      stateLock.unlock()
      return DeltaIntake.rendererFailed.rawValue
    }
    if !viewAttached {
      stateLock.unlock()
      let count = message.count
      if count < RendererDeltaLayout.headerBytes || count > RendererDeltaLayout.maxMessageBytes {
        return DeltaIntake.malformed.rawValue
      }
      stateLock.lock()
      resyncOnAttach = true
      stateLock.unlock()
      return GridRendererStatus.noRenderer
    }
    stateLock.unlock()
    return core.onRenderMessage(message).rawValue
  }

  /// True while a capture view has the renderer prepared.
  var hasAttachedView: Bool {
    stateLock.lock(); defer { stateLock.unlock() }
    return viewAttached
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
  func prepare(colorPixelFormat: MTLPixelFormat, depthPixelFormat: MTLPixelFormat, owner: AnyObject? = nil) -> Bool {
    attach(owner)
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
      rendererDidFail(stage: "pipelines", error: "\(error)")
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
      rendererDidFail(stage: "upload", error: "\(error)")
      return 0
    }
    target.endFrame()
    return uploaded
  }

  /// Draws the grid styles and line overlays of the current display mode,
  /// and the raw feature points of the drawn frame ([frameTimestamp],
  /// [featurePoints] read only when the mode asks for them and the frame is
  /// new). Main thread, inside the render pass, after [encodeUploads] of the
  /// same frame.
  func encode(into encoder: MTLRenderCommandEncoder, view: GridFrameView,
              frameTimestamp: TimeInterval = 0, featurePoints: () -> [simd_float3]? = { nil }) {
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
    if core.buildOverlayLines(
      camX: cam.x, camY: cam.y, camZ: cam.z, batch: volumeBatch, focalPx: view.focalPx, pxPerDp: view.pxPerDp) > 0 {
      draws.drawLines(encoder, batch: volumeBatch, view: view, completion: completion, frameValue: frameValue)
    }
    if core.mode.has(GridDisplayCodes.overlayRawFeaturePoints) {
      rawFeaturePoints.update(frameTimestamp: frameTimestamp, read: featurePoints)
      draws.drawRawPoints(
        encoder, points: rawFeaturePoints.points, view: view, completion: completion, frameValue: frameValue)
    } else {
      rawFeaturePoints.clear()
    }
    encoder.popDebugGroup()
  }

  /// Raw feature points of the last drawn frame (main thread).
  let rawFeaturePoints = RawFeaturePointCache()

  /// Draw statistics of the last encoded frame.
  var drawCounters: StyleDrawCounters? { draws?.counters }

  /// Renderer state for the capture performance snapshot (`gridRenderer`):
  /// whether a capture view prepared it, failures, upload scheduler counters
  /// and the last frame's draw counters. Main thread.
  func diagnostics() -> [String: Any] {
    stateLock.lock()
    let attached = viewAttached
    let hasFailed = failed
    let failureTotal = failures
    stateLock.unlock()
    let upload = core.scheduler.counters
    let drawn = draws?.counters
    let switches = core.modeSwitches
    return [
      "viewAttached": attached,
      "failed": hasFailed,
      "failures": failureTotal,
      "mode": "\(core.mode)",
      "upload": [
        "offered": upload.offered,
        "accepted": upload.accepted,
        "droppedAwaitingReset": upload.droppedAwaitingReset,
        "overflows": upload.overflows,
        "malformed": upload.malformed,
        "frames": upload.frames,
        "maxFrameBytes": upload.maxFrameBytes,
        "deferredFrames": upload.deferredFrames,
        "maxBacklogBytes": upload.maxBacklogBytes,
      ],
      "modeSwitch": [
        "switches": switches.switches,
        "lastLatencyMs": switches.lastLatencyMs,
        "maxLatencyMs": switches.maxLatencyMs,
        "appliedFrame": core.modeAppliedFrame,
        "frame": core.frameNumber,
      ],
      "lastFrame": [
        "drawCalls": drawn?.drawCalls ?? 0,
        "instances": drawn?.instances ?? 0,
        "voxelInstances": drawn?.voxelInstances ?? 0,
        "lineSegments": drawn?.lineSegments ?? 0,
      ],
    ]
  }

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

  /// The capture view of [owner] goes away (main thread): when it is the
  /// view that prepared last, the renderer drops the session's buffers,
  /// overlays and display mode and answers 3 until the next view prepares.
  /// A replaced view detaching after its successor prepared changes nothing.
  func detachView(_ owner: AnyObject) {
    stateLock.lock()
    guard attachedOwner == ObjectIdentifier(owner) else {
      stateLock.unlock()
      return
    }
    attachedOwner = nil
    viewAttached = false
    resyncOnAttach = true
    stateLock.unlock()
    clearSession()
  }

  /// The grid shell stopped (main thread): the stopped session's buffers,
  /// overlays and display mode are dropped; the next session's first
  /// non-reset message is answered 1.
  func gridShellStopped() {
    clearSession()
  }

  private func clearSession() {
    releaseBuffers()
    core.display.reset()
  }

  private func attach(_ owner: AnyObject?) {
    stateLock.lock()
    let resync = !viewAttached && resyncOnAttach
    viewAttached = true
    resyncOnAttach = false
    if let owner = owner { attachedOwner = ObjectIdentifier(owner) }
    stateLock.unlock()
    if resync { core.scheduler.requireReset() }
  }

  /// The renderer failed at [stage] (`pipelines` or `upload`, a buffer
  /// allocation): buffers are released and render messages refused until a
  /// working [prepare].
  func rendererDidFail(stage: String, error: String) {
    stateLock.lock()
    failed = true
    failures += 1
    let failure = GridRendererFailure(stage: stage, error: error, failures: failures)
    stateLock.unlock()
    // Drop anything queued; the next working frame starts from a reset.
    target.releaseBuffers()
    core.store.forgetGpuBuffers()
    core.scheduler.requireReset()
    onFailure?(failure)
  }

  private func recoverIfFailed() {
    stateLock.lock()
    let wasFailed = failed
    failed = false
    stateLock.unlock()
    if wasFailed { core.scheduler.requireReset() }
  }
}

/// One renderer failure: the [stage] that failed, the [error] and the
/// renderer's failure count including this one.
struct GridRendererFailure: Equatable {
  let stage: String
  let error: String
  let failures: Int
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

/// Raw feature points of the drawn frame, read once per new frame while the
/// display mode asks for them, with counts logged at most every two seconds
/// of frame time. Main thread.
final class RawFeaturePointCache {
  private(set) var points: [simd_float3] = []
  private var timestamp: TimeInterval = -1
  private(set) var clouds = 0
  private(set) var pointsIn = 0
  private(set) var pointsKept = 0
  private var lastLogTimestamp: TimeInterval = -1
  private let log: (String) -> Void

  static let logIntervalS: TimeInterval = 2

  init(log: @escaping (String) -> Void = { NSLog("%@", $0) }) {
    self.log = log
  }

  /// Reads [read] when [frameTimestamp] is new; keeps the previous points
  /// when it returns nil.
  func update(frameTimestamp: TimeInterval, read: () -> [simd_float3]?) {
    if frameTimestamp == timestamp { return }
    timestamp = frameTimestamp
    guard let next = read() else { return }
    // Finite points only, at most what the draw takes.
    points.removeAll(keepingCapacity: true)
    for p in next where p.x.isFinite && p.y.isFinite && p.z.isFinite {
      if points.count == GridStyleDrawSet.maxRawFeaturePoints { break }
      points.append(p)
    }
    let kept = points.count
    clouds += 1
    pointsIn += next.count
    pointsKept += kept
    if lastLogTimestamp < 0 || frameTimestamp - lastLogTimestamp >= RawFeaturePointCache.logIntervalS {
      lastLogTimestamp = frameTimestamp
      log("[grid-raw-points] clouds=\(clouds) pointsIn=\(pointsIn) pointsKept=\(pointsKept) last=\(kept)")
    }
  }

  func clear() {
    if points.isEmpty && timestamp < 0 { return }
    points.removeAll(keepingCapacity: true)
    timestamp = -1
  }
}
