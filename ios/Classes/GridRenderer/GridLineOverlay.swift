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
  var volumetric: Bool { get }

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
  var volumetric: Bool { false }
  func setView(focalPx: Float, pxPerDp: Float) {}
}

/// Fixed-capacity line list filled through [GridOverlayLineSink]: per vertex
/// x, y, z as f32 and the colour as RGBA8, ready for one line-list draw.
/// Segments beyond the capacity are dropped and counted; nothing allocates
/// per frame.
final class GridLineBatch: GridOverlayLineSink {
  static let vertexBytes = 16
  static let defaultCapacity = 8192

  let volumetric: Bool
  var verticesPerSegment: Int { volumetric ? 36 : 2 }
  let capacitySegments: Int
  let vertices: UnsafeMutableRawPointer
  private(set) var segments = 0
  private(set) var dropped = 0
  /// Segments `[0, depthTestedSegments)` are drawn with the depth test, the rest on top.
  private(set) var depthTestedSegments = 0

  init(capacitySegments: Int = GridLineBatch.defaultCapacity, volumetric: Bool = false) {
    self.capacitySegments = capacitySegments
    self.volumetric = volumetric
    vertices = UnsafeMutableRawPointer.allocate(
      byteCount: capacitySegments * (volumetric ? 36 : 2) * GridLineBatch.vertexBytes, alignment: 16)
  }

  deinit {
    vertices.deallocate()
  }

  var byteCount: Int { segments * verticesPerSegment * GridLineBatch.vertexBytes }

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
    if volumetric {
      tube(x0, y0, z0, x1, y1, z1, rgba: rgba)
      return
    }
    let o = segments * 2 * GridLineBatch.vertexBytes
    put(o, x0, y0, z0, rgba)
    put(o + GridLineBatch.vertexBytes, x1, y1, z1, rgba)
    segments += 1
  }

  // A closed square prism, 24 mm thick, matching Android. Scratch is reused.
  private static let tubeIndices = [0,1,5,0,5,4,1,2,6,1,6,5,2,3,7,2,7,6,3,0,4,3,4,7,0,3,2,0,2,1,4,5,6,4,6,7]
  private var corners = [Float](repeating: 0, count: 24)
  private func tube(_ x0: Float, _ y0: Float, _ z0: Float, _ x1: Float, _ y1: Float, _ z1: Float, rgba: UInt32) {
    let dx=x1-x0, dy=y1-y0, dz=z1-z0
    let length=sqrt(dx*dx+dy*dy+dz*dz)
    if !length.isFinite || length < 0.0001 { return }
    let nx=dx/length, ny=dy/length, nz=dz/length
    var ux: Float = abs(ny)<0.9 ? -nz : 0
    var uy: Float = abs(ny)<0.9 ? 0 : nz
    var uz: Float = abs(ny)<0.9 ? nx : -ny
    let ul=sqrt(ux*ux+uy*uy+uz*uz)
    ux/=ul; uy/=ul; uz/=ul
    let vx=ny*uz-nz*uy, vy=nz*ux-nx*uz, vz=nx*uy-ny*ux
    for i in 0...7 {
      let a: Float = i%4==0 || i%4==3 ? -0.012 : 0.012
      let b: Float = i%4<2 ? -0.012 : 0.012
      corners[i*3]=(i<4 ? x0 : x1)+ux*a+vx*b
      corners[i*3+1]=(i<4 ? y0 : y1)+uy*a+vy*b
      corners[i*3+2]=(i<4 ? z0 : z1)+uz*a+vz*b
    }
    var offset=segments*36*GridLineBatch.vertexBytes
    for j in GridLineBatch.tubeIndices {
      put(offset,corners[j*3],corners[j*3+1],corners[j*3+2],rgba)
      offset+=GridLineBatch.vertexBytes
    }
    segments+=1
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
