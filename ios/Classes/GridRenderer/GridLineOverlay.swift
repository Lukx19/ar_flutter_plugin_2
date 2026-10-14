import Foundation

/// Receives overlay line segments in world coordinates with an RGBA8 colour
/// (0xRRGGBBAA).
protocol GridOverlayLineSink: AnyObject {
  func line(_ x0: Float, _ y0: Float, _ z0: Float, _ x1: Float, _ y1: Float, _ z1: Float, rgba: UInt32)
}

/// Data-only overlay of the grid renderer (guided-capture quests, marker
/// outlines, pose frusta): the renderer forwards the overlay's
/// display-channel messages to [onMessage] (routed by their magic), asks it
/// once per frame for line segments, draws them as one line list after the
/// grid styles, and greys grid surfaces of bricks whose centre is outside
/// [isInScope] while [greyOutside] is true. Mirrors the Android
/// `GridLineOverlay`.
protocol GridLineOverlay: AnyObject {
  /// Magic (first u32, little-endian) of the display-channel messages this overlay consumes.
  var messageMagic: UInt32 { get }

  /// Channel queue; [message] holds exactly the message. Returns whether it was accepted.
  func onMessage(_ message: UnsafeRawBufferPointer) -> Bool

  /// Whether the segments are drawn after the depth-tested ones without the
  /// depth test, so grid surfaces never hide them (guidance rings, reticles).
  var drawOnTop: Bool { get }

  /// Render thread, before [buildLines]: projection focal length in pixels and display density.
  func setView(focalPx: Float, pxPerDp: Float)

  /// Render thread; returns the number of segments emitted.
  func buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink) -> Int

  /// Whether surfaces outside the scope are greyed this frame.
  var greyOutside: Bool { get }

  /// Render thread; called per visible brick centre while [greyOutside] is true.
  func isInScope(_ x: Float, _ y: Float, _ z: Float) -> Bool

  /// Forgets every received frame, as a fresh overlay: a new capture view
  /// starts without the previous view's guidance, outlines or frusta (the
  /// Android host is created per view). Any thread.
  func reset()
}

extension GridLineOverlay {
  var drawOnTop: Bool { false }
  func setView(focalPx: Float, pxPerDp: Float) {}
}

/// Fixed-capacity line list filled through [GridOverlayLineSink]: per vertex
/// x, y, z as f32 and the colour as RGBA8, ready for one line-list draw.
/// Segments beyond the capacity are dropped and counted; nothing allocates
/// per frame.
final class GridLineBatch: GridOverlayLineSink {
  static let vertexBytes = 16
  static let defaultCapacity = 8192

  let capacitySegments: Int
  let vertices: UnsafeMutableRawPointer
  private(set) var segments = 0
  private(set) var dropped = 0
  /// Segments `[0, depthTestedSegments)` are drawn with the depth test, the rest on top.
  private(set) var depthTestedSegments = 0

  init(capacitySegments: Int = GridLineBatch.defaultCapacity) {
    self.capacitySegments = capacitySegments
    vertices = UnsafeMutableRawPointer.allocate(
      byteCount: capacitySegments * 2 * GridLineBatch.vertexBytes, alignment: 16)
  }

  deinit {
    vertices.deallocate()
  }

  var byteCount: Int { segments * 2 * GridLineBatch.vertexBytes }

  func clear() {
    segments = 0
    depthTestedSegments = 0
  }

  /// Ends the depth-tested part: later segments are drawn on top.
  func endDepthTested() {
    depthTestedSegments = segments
  }

  func line(_ x0: Float, _ y0: Float, _ z0: Float, _ x1: Float, _ y1: Float, _ z1: Float, rgba: UInt32) {
    if segments == capacitySegments {
      dropped += 1
      return
    }
    let o = segments * 2 * GridLineBatch.vertexBytes
    put(o, x0, y0, z0, rgba)
    put(o + GridLineBatch.vertexBytes, x1, y1, z1, rgba)
    segments += 1
  }

  private func put(_ o: Int, _ x: Float, _ y: Float, _ z: Float, _ rgba: UInt32) {
    vertices.storeBytes(of: x, toByteOffset: o, as: Float.self)
    vertices.storeBytes(of: y, toByteOffset: o + 4, as: Float.self)
    vertices.storeBytes(of: z, toByteOffset: o + 8, as: Float.self)
    vertices.storeBytes(of: UInt8(truncatingIfNeeded: rgba >> 24), toByteOffset: o + 12, as: UInt8.self)
    vertices.storeBytes(of: UInt8(truncatingIfNeeded: rgba >> 16), toByteOffset: o + 13, as: UInt8.self)
    vertices.storeBytes(of: UInt8(truncatingIfNeeded: rgba >> 8), toByteOffset: o + 14, as: UInt8.self)
    vertices.storeBytes(of: UInt8(truncatingIfNeeded: rgba), toByteOffset: o + 15, as: UInt8.self)
  }
}
