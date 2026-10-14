import Foundation

/// Metal-free core of the grid renderer: delta intake and upload scheduling
/// into the instance buffers, plus display state and line overlays. The
/// Metal host owns one and calls [frame] once per rendered frame; tests
/// drive it with a byte-array [GpuBufferTarget]. Mirrors the Android
/// `GridRendererCore`.
final class GridRendererCore {
  let target: GpuBufferTarget
  let scheduler: DeltaUploadScheduler
  let display: DisplayModeHandler
  let store: InstanceBufferStore
  var frameBudgetBytes: Int

  /// Render-thread copy of the display mode.
  private(set) var mode = GridDisplayState()

  /// Rendered frames.
  private(set) var frameNumber = 0

  /// Frame in which the last display mode change took effect.
  private(set) var modeAppliedFrame = -1

  /// Data-only overlays; their display-channel messages are routed by magic.
  /// Set up before the channels deliver messages; read on both threads.
  private let overlayLock = NSLock()
  private var overlays: [GridLineOverlay] = []

  init(
    target: GpuBufferTarget,
    scheduler: DeltaUploadScheduler = DeltaUploadScheduler(),
    display: DisplayModeHandler = DisplayModeHandler(),
    frameBudgetBytes: Int = DeltaUploadScheduler.defaultFrameBudgetBytes
  ) {
    self.target = target
    self.scheduler = scheduler
    self.display = display
    self.frameBudgetBytes = frameBudgetBytes
    store = InstanceBufferStore(target: target)
  }

  var lineOverlays: [GridLineOverlay] {
    overlayLock.lock(); defer { overlayLock.unlock() }
    return overlays
  }

  func addLineOverlay(_ overlay: GridLineOverlay) {
    overlayLock.lock(); defer { overlayLock.unlock() }
    if !overlays.contains(where: { $0 === overlay }) { overlays.append(overlay) }
  }

  func removeLineOverlay(_ overlay: GridLineOverlay) {
    overlayLock.lock(); defer { overlayLock.unlock() }
    overlays.removeAll { $0 === overlay }
  }

  func onRenderMessage(_ message: UnsafeRawBufferPointer) -> DeltaIntake {
    scheduler.offer(message)
  }

  func onDisplayMessage(_ message: UnsafeRawBufferPointer) -> DisplayIntake {
    if message.count >= 4 {
      let magic = message.leU32(0)
      for overlay in lineOverlays where overlay.messageMagic == magic {
        return overlay.onMessage(message) ? .overlay : .rejected
      }
    }
    return display.onMessage(message)
  }

  /// Render thread: collects this frame's overlay segments into [batch],
  /// depth-tested overlays first, then [GridLineOverlay.drawOnTop] ones.
  @discardableResult
  func buildOverlayLines(
    camX: Float, camY: Float, camZ: Float, batch: GridLineBatch, focalPx: Float = 0, pxPerDp: Float = 1
  ) -> Int {
    batch.clear()
    let current = lineOverlays
    for overlay in current where !overlay.drawOnTop {
      overlay.setView(focalPx: focalPx, pxPerDp: pxPerDp)
      _ = overlay.buildLines(camX: camX, camY: camY, camZ: camZ, sink: batch)
    }
    batch.endDepthTested()
    for overlay in current where overlay.drawOnTop {
      overlay.setView(focalPx: focalPx, pxPerDp: pxPerDp)
      _ = overlay.buildLines(camX: camX, camY: camY, camZ: camZ, sink: batch)
    }
    return batch.segments
  }

  /// The overlay that greys out-of-scope surfaces this frame, or nil.
  func scopeOverlay() -> GridLineOverlay? {
    lineOverlays.first { $0.greyOutside }
  }

  /// Starts one render-thread frame: uploads queued deltas within the frame
  /// budget and takes the newest display mode. Returns uploaded bytes.
  /// Throws when a GPU buffer could not be allocated.
  @discardableResult
  func frame() throws -> Int {
    frameNumber += 1
    if display.snapshotMode(into: &mode, knownVersion: mode.modeVersion) { modeAppliedFrame = frameNumber }
    return try scheduler.drainFrame(store: store, target: target, budgetBytes: frameBudgetBytes)
  }
}
