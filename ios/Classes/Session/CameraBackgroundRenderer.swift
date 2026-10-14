import ARKit
import CoreVideo
import Metal
import simd
import UIKit

/// Draws `ARFrame.capturedImage` (bi-planar full-range YCbCr) as the camera
/// background: Y and CbCr planes become Metal textures through a
/// `CVMetalTextureCache` without a copy, are converted with full-range BT.601
/// and placed with `ARFrame.displayTransform` (view orientation, aspect fill).
///
/// Shaders are compiled at run time from [CaptureMetalShaders.source]. The
/// plugin is a static CocoaPods framework (and an SPM target later): a
/// `.metal` file would land in a pod `default.metallib` that
/// `makeDefaultLibrary()` (main bundle) does not see and would need a resource
/// bundle in both build systems. Compiling about 40 lines once per device
/// costs a few milliseconds at view creation and keeps the source next to the
/// renderer; the benchmark renderer measured on the iPhone does the same.
final class CameraBackgroundRenderer {
  let device: MTLDevice
  private let pipeline: MTLRenderPipelineState
  private let depthState: MTLDepthStencilState?
  private let textureCache: CVMetalTextureCache

  init(device: MTLDevice, colorPixelFormat: MTLPixelFormat, depthPixelFormat: MTLPixelFormat) throws {
    self.device = device
    let library = try CaptureMetalShaders.library(for: device)
    let descriptor = MTLRenderPipelineDescriptor()
    descriptor.label = "camera background"
    descriptor.vertexFunction = library.makeFunction(name: "camera_background_vertex")
    descriptor.fragmentFunction = library.makeFunction(name: "camera_background_fragment")
    descriptor.colorAttachments[0].pixelFormat = colorPixelFormat
    descriptor.depthAttachmentPixelFormat = depthPixelFormat
    pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
    if depthPixelFormat != .invalid {
      let depth = MTLDepthStencilDescriptor()
      depth.depthCompareFunction = .always
      depth.isDepthWriteEnabled = false
      depthState = device.makeDepthStencilState(descriptor: depth)
    } else {
      depthState = nil
    }
    var cache: CVMetalTextureCache?
    let status = CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, device, nil, &cache)
    guard status == kCVReturnSuccess, let created = cache else {
      throw CaptureRenderError.textureCacheUnavailable(status)
    }
    textureCache = created
  }

  /// Encodes the background quad. Returns the Core Video textures that must
  /// stay alive until the command buffer completes, or `nil` when the image
  /// could not be wrapped (nothing is drawn; the clear colour stays).
  func encode(
    capturedImage: CVPixelBuffer,
    displayTransform: CGAffineTransform,
    into encoder: MTLRenderCommandEncoder
  ) -> [CVMetalTexture]? {
    guard CVPixelBufferGetPlaneCount(capturedImage) >= 2,
          let luma = texture(capturedImage, plane: 0, format: .r8Unorm),
          let chroma = texture(capturedImage, plane: 1, format: .rg8Unorm),
          let lumaTexture = CVMetalTextureGetTexture(luma),
          let chromaTexture = CVMetalTextureGetTexture(chroma)
    else { return nil }
    var vertices = CameraBackgroundRenderer.quadVertices(displayTransform: displayTransform)
    encoder.pushDebugGroup("camera background")
    encoder.setRenderPipelineState(pipeline)
    if let depthState = depthState { encoder.setDepthStencilState(depthState) }
    encoder.setCullMode(.none)
    encoder.setVertexBytes(&vertices, length: MemoryLayout<SIMD4<Float>>.stride * vertices.count, index: 0)
    encoder.setFragmentTexture(lumaTexture, index: 0)
    encoder.setFragmentTexture(chromaTexture, index: 1)
    encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
    encoder.popDebugGroup()
    return [luma, chroma]
  }

  private func texture(_ buffer: CVPixelBuffer, plane: Int, format: MTLPixelFormat) -> CVMetalTexture? {
    let width = CVPixelBufferGetWidthOfPlane(buffer, plane)
    let height = CVPixelBufferGetHeightOfPlane(buffer, plane)
    var texture: CVMetalTexture?
    let status = CVMetalTextureCacheCreateTextureFromImage(
      kCFAllocatorDefault, textureCache, buffer, nil, format, width, height, plane, &texture)
    return status == kCVReturnSuccess ? texture : nil
  }

  /// Triangle strip of the full-screen quad: xy in clip space, zw the image
  /// texture coordinate. `displayTransform` maps normalized image coordinates
  /// to normalized view coordinates (top-left origin), so each view corner
  /// samples the image at the inverse transform; this crops the image to fill
  /// the view (aspect fill) and rotates it to the interface orientation.
  static func quadVertices(displayTransform: CGAffineTransform) -> [SIMD4<Float>] {
    let viewToImage = displayTransform.inverted()
    // Clip-space corner and the matching normalized view coordinate.
    let corners: [(Float, Float, CGFloat, CGFloat)] = [
      (-1, -1, 0, 1),
      (1, -1, 1, 1),
      (-1, 1, 0, 0),
      (1, 1, 1, 0),
    ]
    return corners.map { clipX, clipY, viewU, viewV in
      let image = CGPoint(x: viewU, y: viewV).applying(viewToImage)
      return SIMD4<Float>(clipX, clipY, Float(image.x), Float(image.y))
    }
  }
}

/// Per-frame camera state handed to a [GridOverlayRenderer].
struct GridOverlayFrameContext {
  /// World to camera (view) matrix for the interface orientation.
  let viewMatrix: simd_float4x4
  /// Camera projection for the interface orientation and viewport.
  let projectionMatrix: simd_float4x4
  /// Camera-to-world transform (sensor orientation) of the drawn frame.
  let cameraTransform: simd_float4x4
  let viewportSize: CGSize
  let drawableSize: CGSize
  let orientation: UIInterfaceOrientation
  /// `ARFrame.timestamp` of the drawn camera image, seconds.
  let frameTimestamp: TimeInterval
  let trackingState: ARCamera.TrackingState
}

/// Draws over the camera background inside the same render pass. The pass
/// has the view's colour and depth attachments; the background neither tests
/// nor writes depth, and depth is cleared to 1 every frame.
protocol GridOverlayRenderer: AnyObject {
  /// Called on the main thread once per render host before the first encode
  /// and again when the render host changes (new view, new formats).
  func prepare(device: MTLDevice, colorPixelFormat: MTLPixelFormat, depthPixelFormat: MTLPixelFormat)
  /// Called on the main thread for every drawn frame after the background.
  func encodeOverlay(into encoder: MTLRenderCommandEncoder, context: GridOverlayFrameContext)
}

/// Optional upload step of a [GridOverlayRenderer]: GPU copies into the
/// overlay's persistent buffers, encoded into the frame's command buffer
/// before its render pass (no encoder is open), so Metal orders the pass's
/// reads after them and the overlay can fence its staging memory on the
/// command buffer. Called on the main thread for every drawn frame, after
/// `prepare` and before `encodeOverlay`.
protocol GridOverlayFrameUploader: AnyObject {
  func encodeFrameUploads(into commandBuffer: MTLCommandBuffer)
}

/// Process-wide overlay slot read by the active capture view each frame, so
/// an overlay owned by another component (the grid renderer) can attach
/// before or after the view exists. Set and read on the main thread.
final class GridOverlayRegistry {
  static let shared = GridOverlayRegistry()

  private(set) var overlay: GridOverlayRenderer?
  /// Increments on every change so render hosts re-run `prepare`.
  private(set) var revision = 0

  func setOverlay(_ overlay: GridOverlayRenderer?) {
    dispatchPrecondition(condition: .onQueue(.main))
    self.overlay = overlay
    revision += 1
  }
}

enum CaptureRenderError: Error {
  case metalUnavailable
  case textureCacheUnavailable(CVReturn)
}

enum CaptureMetalShaders {
  private static let lock = NSLock()
  private static var libraries: [ObjectIdentifier: MTLLibrary] = [:]

  /// The compiled library, cached per device.
  static func library(for device: MTLDevice) throws -> MTLLibrary {
    lock.lock(); defer { lock.unlock() }
    let key = ObjectIdentifier(device)
    if let library = libraries[key] { return library }
    let library = try device.makeLibrary(source: source, options: nil)
    libraries[key] = library
    return library
  }

  static let source = """
  #include <metal_stdlib>
  using namespace metal;

  struct CameraBackgroundVertex {
    float4 position [[position]];
    float2 imageCoordinate;
  };

  vertex CameraBackgroundVertex camera_background_vertex(
      uint vertexId [[vertex_id]],
      constant float4 *quad [[buffer(0)]]) {
    CameraBackgroundVertex out;
    out.position = float4(quad[vertexId].xy, 0.0, 1.0);
    out.imageCoordinate = quad[vertexId].zw;
    return out;
  }

  // Full-range BT.601 YCbCr to RGB (ARKit capturedImage is
  // kCVPixelFormatType_420YpCbCr8BiPlanarFullRange).
  fragment half4 camera_background_fragment(
      CameraBackgroundVertex in [[stage_in]],
      texture2d<float, access::sample> lumaTexture [[texture(0)]],
      texture2d<float, access::sample> chromaTexture [[texture(1)]]) {
    constexpr sampler imageSampler(filter::linear, address::clamp_to_edge);
    float luma = lumaTexture.sample(imageSampler, in.imageCoordinate).r;
    float2 chroma = chromaTexture.sample(imageSampler, in.imageCoordinate).rg - float2(0.5);
    float3 rgb = float3(
        luma + 1.402 * chroma.y,
        luma - 0.344136 * chroma.x - 0.714136 * chroma.y,
        luma + 1.772 * chroma.x);
    return half4(half3(saturate(rgb)), 1.0h);
  }
  """
}
