import ARKit
import MetalKit
import UIKit

/// `MTKView` delegate of the capture view: one render pass per display
/// frame with the camera background first and the grid overlay (when one is
/// registered) on top. Runs on the main thread. The source's current
/// [CaptureFrame] is read for the duration of one draw only; the Core Video textures wrapping its
/// image live until the GPU finishes with them.
final class CaptureRenderLoop: NSObject, MTKViewDelegate {
  static let colorPixelFormat: MTLPixelFormat = .bgra8Unorm
  static let depthPixelFormat: MTLPixelFormat = .depth32Float
  static let overlayNearPlane: Float = 0.01
  static let overlayFarPlane: Float = 100

  private weak var source: ARFrameSource?
  private let background: CameraBackgroundRenderer
  private let commandQueue: MTLCommandQueue
  private let overlays: GridOverlayRegistry
  private var preparedOverlayRevision = -1
  /// The overlay this loop prepared; told once when the loop goes away.
  private weak var preparedOverlay: GridOverlayRenderer?
  private let frameStats: CaptureViewFrameStats

  init(
    device: MTLDevice, source: ARFrameSource, overlays: GridOverlayRegistry = .shared,
    frameStats: CaptureViewFrameStats = .shared
  ) throws {
    guard let queue = device.makeCommandQueue() else { throw CaptureRenderError.metalUnavailable }
    queue.label = "capture view"
    commandQueue = queue
    background = try CameraBackgroundRenderer(
      device: device,
      colorPixelFormat: CaptureRenderLoop.colorPixelFormat,
      depthPixelFormat: CaptureRenderLoop.depthPixelFormat)
    self.source = source
    self.overlays = overlays
    self.frameStats = frameStats
    super.init()
  }

  /// Applies the formats the renderers were built for.
  func configure(_ view: MTKView) {
    view.device = background.device
    view.colorPixelFormat = CaptureRenderLoop.colorPixelFormat
    view.depthStencilPixelFormat = CaptureRenderLoop.depthPixelFormat
    view.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
    view.clearDepth = 1
    view.framebufferOnly = true
    view.enableSetNeedsDisplay = false
    view.preferredFramesPerSecond = 60
    view.autoResizeDrawable = true
    view.delegate = self
  }

  func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

  /// The capture view goes away (main thread, idempotent): the overlay it
  /// prepared drops the view's grid (a successor view keeps its own).
  func detach() {
    preparedOverlay?.detach(owner: self)
    preparedOverlay = nil
    preparedOverlayRevision = -1
  }

  func draw(in view: MTKView) {
    let startedS = CACurrentMediaTime()
    guard let pass = view.currentRenderPassDescriptor,
          let commandBuffer = commandQueue.makeCommandBuffer()
    else { return }
    commandBuffer.label = "capture view frame"
    let overlay = overlays.overlay
    if let overlay = overlay, preparedOverlayRevision != overlays.revision {
      overlay.prepare(
        owner: self,
        device: background.device,
        colorPixelFormat: CaptureRenderLoop.colorPixelFormat,
        depthPixelFormat: CaptureRenderLoop.depthPixelFormat)
      preparedOverlayRevision = overlays.revision
      preparedOverlay = overlay
    }
    let uploaded = (overlay as? GridOverlayFrameUploader)?.encodeFrameUploads(into: commandBuffer) ?? 0
    var overlayDrawn = false
    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
      // Uploads already encoded must still run.
      commandBuffer.commit()
      return
    }
    var imageTextures: [CVMetalTexture] = []
    let viewport = view.bounds.size
    if let source = source, source.lifecycleState == .running,
       viewport.width > 0, viewport.height > 0,
       let frame = source.currentFrame {
      let orientation = view.window?.windowScene?.interfaceOrientation ?? .portrait
      let displayTransform = frame.displayTransform(for: orientation, viewportSize: viewport)
      imageTextures = background.encode(
        capturedImage: frame.capturedImage, displayTransform: displayTransform, into: encoder) ?? []
      if let overlay = overlay {
        let context = GridOverlayFrameContext(
          viewMatrix: frame.viewMatrix(for: orientation),
          projectionMatrix: frame.projectionMatrix(
            for: orientation, viewportSize: viewport,
            zNear: CGFloat(CaptureRenderLoop.overlayNearPlane), zFar: CGFloat(CaptureRenderLoop.overlayFarPlane)),
          cameraTransform: frame.cameraTransform,
          viewportSize: viewport,
          drawableSize: view.drawableSize,
          orientation: orientation,
          frameTimestamp: frame.timestamp,
          trackingState: frame.trackingState)
        encoder.pushDebugGroup("grid overlay")
        overlay.encodeOverlay(into: encoder, context: context)
        encoder.popDebugGroup()
        overlayDrawn = true
      }
    }
    encoder.endEncoding()
    if let drawable = view.currentDrawable { commandBuffer.present(drawable) }
    // The camera textures stay alive until the GPU is done with them, and
    // their CVMetalTextureCache (owned by `background`) must outlive them:
    // releasing a texture after its cache was freed (view torn down with a
    // frame in flight) crashes inside CoreVideo. Drop the textures first,
    // then let the cache owner go.
    var inFlightTextures: [CVMetalTexture]? = imageTextures
    let textureCacheOwner = background
    let stats = frameStats
    commandBuffer.addCompletedHandler { buffer in
      inFlightTextures = nil
      withExtendedLifetime(textureCacheOwner) {}
      if buffer.gpuEndTime > buffer.gpuStartTime, buffer.gpuStartTime > 0 {
        stats.recordGpu(ms: (buffer.gpuEndTime - buffer.gpuStartTime) * 1000)
      }
    }
    commandBuffer.commit()
    frameStats.recordDraw(
      nowS: startedS, encodeMs: (CACurrentMediaTime() - startedS) * 1000,
      uploadedDeltas: uploaded > 0, overlayDrawn: overlayDrawn)
  }
}


/// Display-frame timing of the capture view's render loop for the
/// performance snapshot (`getPerformanceSnapshot` key `captureView`): the
/// interval between draws, the CPU time of one draw (camera background,
/// grid uploads and styles encoded), the GPU time of its command buffer and
/// whether grid deltas were uploaded in it. A frame gap is an interval over
/// 100 ms (the iM3 bound). An interval runs from the previous draw's start
/// to this draw's start, so it holds the previous draw's encode: a gap is
/// counted as upload-caused when the previous or the current frame uploaded
/// grid deltas. Fixed 1 ms histograms up to 250 ms (one overflow
/// bucket), so recording never allocates. Draws record on the main thread,
/// GPU times on Metal's completion queue; reads come from the main thread.
final class CaptureViewFrameStats {
  static let shared = CaptureViewFrameStats()

  static let gapThresholdMs = 100.0
  static let bucketCount = 251

  private struct Histogram {
    var buckets = [Int](repeating: 0, count: CaptureViewFrameStats.bucketCount)
    var count = 0
    var sumMs = 0.0
    var maxMs = 0.0

    mutating func add(_ ms: Double) {
      let index = min(max(Int(ms.rounded(.down)), 0), CaptureViewFrameStats.bucketCount - 1)
      buckets[index] += 1
      count += 1
      sumMs += ms
      if ms > maxMs { maxMs = ms }
    }

    /// Upper edge of the bucket holding quantile [q] (ms); 0 when empty.
    func quantile(_ q: Double) -> Double {
      guard count > 0 else { return 0 }
      let target = Int((Double(count) * q).rounded(.up))
      var seen = 0
      for (index, n) in buckets.enumerated() {
        seen += n
        if seen >= max(target, 1) { return Double(index + 1) }
      }
      return Double(CaptureViewFrameStats.bucketCount)
    }

    func summary() -> [String: Any] {
      [
        "count": count,
        "meanMs": count > 0 ? sumMs / Double(count) : 0,
        "p50Ms": quantile(0.5),
        "p95Ms": quantile(0.95),
        "p99Ms": quantile(0.99),
        "maxMs": maxMs,
      ]
    }
  }

  private let lock = NSLock()
  private var intervals = Histogram()
  private var encode = Histogram()
  private var gpu = Histogram()
  private var lastDrawS: Double?
  private var lastDrawUploaded = false
  private var frames = 0
  private var uploadFrames = 0
  private var overlayFrames = 0
  private var gaps = 0
  private var uploadGaps = 0
  private var longestGapMs = 0.0
  private var startedS: Double?

  /// One drawn frame that started at [nowS] (seconds, monotonic).
  func recordDraw(nowS: Double, encodeMs: Double, uploadedDeltas: Bool, overlayDrawn: Bool) {
    lock.lock(); defer { lock.unlock() }
    if startedS == nil { startedS = nowS }
    frames += 1
    if uploadedDeltas { uploadFrames += 1 }
    if overlayDrawn { overlayFrames += 1 }
    encode.add(encodeMs)
    if let last = lastDrawS {
      let ms = (nowS - last) * 1000
      intervals.add(ms)
      if ms > Self.gapThresholdMs {
        gaps += 1
        if uploadedDeltas || lastDrawUploaded { uploadGaps += 1 }
        if ms > longestGapMs { longestGapMs = ms }
      }
    }
    lastDrawS = nowS
    lastDrawUploaded = uploadedDeltas
  }

  func recordGpu(ms: Double) {
    guard ms.isFinite, ms >= 0 else { return }
    lock.lock(); gpu.add(ms); lock.unlock()
  }

  /// Starts a new measurement window (`resetDiagnosticTiming`).
  func reset() {
    lock.lock(); defer { lock.unlock() }
    intervals = Histogram()
    encode = Histogram()
    gpu = Histogram()
    lastDrawS = nil
    lastDrawUploaded = false
    frames = 0
    uploadFrames = 0
    overlayFrames = 0
    gaps = 0
    uploadGaps = 0
    longestGapMs = 0
    startedS = nil
  }

  func snapshot(nowS: Double) -> [String: Any] {
    lock.lock(); defer { lock.unlock() }
    return [
      "frames": frames,
      "windowMs": startedS.map { (nowS - $0) * 1000 } ?? 0,
      "uploadFrames": uploadFrames,
      "overlayFrames": overlayFrames,
      "gapThresholdMs": Self.gapThresholdMs,
      "gaps": gaps,
      "uploadGaps": uploadGaps,
      "longestGapMs": longestGapMs,
      "intervalMs": intervals.summary(),
      "encodeMs": encode.summary(),
      "gpuMs": gpu.summary(),
    ]
  }
}
