import Foundation

/// Quest overlay layer: double-buffered decoded frames handed from the
/// channel queue to the render thread under one short lock (a rejected
/// message never replaces the shown frame); no allocation after
/// construction.
final class QuestOverlayLayer {
  private let frames = [QuestOverlayFrame(), QuestOverlayFrame()]
  private let geometry = QuestOverlayGeometry()
  private let lock = NSLock()
  private var front = 0
  private var hasFrame = false

  /// Messages rejected so far, for diagnostics.
  private(set) var rejectedMessages = 0

  func onMessage(_ message: UnsafeRawBufferPointer) -> QuestOverlayStatus {
    lock.lock(); defer { lock.unlock() }
    let back = 1 - front
    let status = frames[back].decode(message)
    if status == .ok {
      front = back
      hasFrame = true
    } else {
      rejectedMessages += 1
    }
    return status
  }

  func setView(focalPx: Float, pxPerDp: Float) {
    geometry.setView(focalPx: focalPx, pxPerDp: pxPerDp)
  }

  /// Drops the shown frame: no lines and no greyed scope until the next message.
  func reset() {
    lock.lock(); defer { lock.unlock() }
    hasFrame = false
  }

  func buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink) -> Int {
    lock.lock(); defer { lock.unlock() }
    if !hasFrame { return 0 }
    return geometry.build(frames[front], camX: camX, camY: camY, camZ: camZ, sink: sink)
  }

  var greyOutside: Bool {
    lock.lock(); defer { lock.unlock() }
    return hasFrame && frames[front].hasLayer(QuestOverlayLayout.layerGreyOutside)
  }

  func isInScope(_ x: Float, _ y: Float, _ z: Float) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return !hasFrame || frames[front].isInScope(x, y, z)
  }
}

/// Plugs the guided-capture [QuestOverlayLayer] into the grid renderer's
/// line overlay hook: quest overlay messages (`C3QO`) arrive on the display
/// channel, segments are drawn after the grid styles without the depth test
/// and surfaces outside the scan zone are greyed.
final class QuestLineOverlay: GridLineOverlay {
  let layer: QuestOverlayLayer

  init(layer: QuestOverlayLayer = QuestOverlayLayer()) {
    self.layer = layer
  }

  var messageMagic: UInt32 { QuestOverlayLayout.magic }

  func onMessage(_ message: UnsafeRawBufferPointer) -> Bool {
    layer.onMessage(message) == .ok
  }

  /// Guidance stays readable over the grid: drawn without the depth test.
  var drawOnTop: Bool { false }
  var volumetric: Bool { true }

  func setView(focalPx: Float, pxPerDp: Float) {
    layer.setView(focalPx: focalPx, pxPerDp: pxPerDp)
  }

  func buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink) -> Int {
    layer.buildLines(camX: camX, camY: camY, camZ: camZ, sink: sink)
  }

  var greyOutside: Bool { layer.greyOutside }

  func isInScope(_ x: Float, _ y: Float, _ z: Float) -> Bool {
    layer.isInScope(x, y, z)
  }

  func reset() {
    layer.reset()
  }
}
