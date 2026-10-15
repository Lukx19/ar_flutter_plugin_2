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

  init(device: MTLDevice, source: ARFrameSource, overlays: GridOverlayRegistry = .shared) throws {
    guard let queue = device.makeCommandQueue() else { throw CaptureRenderError.metalUnavailable }
    queue.label = "capture view"
    commandQueue = queue
    background = try CameraBackgroundRenderer(
      device: device,
      colorPixelFormat: CaptureRenderLoop.colorPixelFormat,
      depthPixelFormat: CaptureRenderLoop.depthPixelFormat)
    self.source = source
    self.overlays = overlays
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

  func draw(in view: MTKView) {
    guard let pass = view.currentRenderPassDescriptor,
          let commandBuffer = commandQueue.makeCommandBuffer()
    else { return }
    commandBuffer.label = "capture view frame"
    let overlay = overlays.overlay
    if let overlay = overlay, preparedOverlayRevision != overlays.revision {
      overlay.prepare(
        device: background.device,
        colorPixelFormat: CaptureRenderLoop.colorPixelFormat,
        depthPixelFormat: CaptureRenderLoop.depthPixelFormat)
      preparedOverlayRevision = overlays.revision
    }
    (overlay as? GridOverlayFrameUploader)?.encodeFrameUploads(into: commandBuffer)
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
      }
    }
    encoder.endEncoding()
    if let drawable = view.currentDrawable { commandBuffer.present(drawable) }
    let retained = imageTextures
    commandBuffer.addCompletedHandler { _ in withExtendedLifetime(retained) {} }
    commandBuffer.commit()
  }
}
