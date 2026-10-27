import Foundation
import Metal
import simd

/// View inputs of one frame, filled by the host from the AR camera.
struct GridFrameView {
  var viewProjection = matrix_identity_float4x4
  var cameraPosition = SIMD3<Float>(repeating: 0)
  var widthPx = 1
  var heightPx = 1
  /// Projection focal length in pixels (`P[1][1] * height / 2`).
  var focalPx: Float = 1
  /// Render target pixels per point, for screen-sized overlay elements.
  var pxPerDp: Float = 1
}

/// Draw statistics of the last frame.
struct StyleDrawCounters {
  var drawCalls = 0
  var instances = 0
  var visibleSurfaceSlices = 0
  var visibleVoxelSlices = 0
  /// Voxel instances drawn (padding entries included; they collapse in the shader).
  var voxelInstances = 0
  var lineSegments = 0
  var rawFeaturePoints = 0
}

/// Uniforms of every grid program, matching `GridUniforms` in [GridStyleShaders].
struct GridStyleUniforms {
  var viewProj = matrix_identity_float4x4
  var camPos = SIMD4<Float>(repeating: 0)
  var focalPx: Float = 1
  var fixedPx: Float = 6
  var colorAttr: Int32 = 0
  var sizeRule: Int32 = 0
  var selectedOnly: Int32 = 0
  var grey: Int32 = 0
  var minQuality: Int32 = 0
  var sphere: Int32 = 0
  var scale: Float = 1
  var summaryKind: Int32 = 0
  var residencyFilter: Int32 = -1
  var alpha: Float = 1
  var focusCount: Int32 = 0
}

/// The grid styles drawn from the persistent instance buffers with Metal
/// instancing: points (fixed or sized, sphere shading), splats (two-pass),
/// directional spike balls (instance = surface x 24 + bin, selected
/// surfaces only), compact occupancy voxels, brick boxes, region boxes and
/// planner targets, plus the overlay line list. A display mode only selects
/// pipelines, uniforms and draw calls; nothing here writes the instance
/// buffers. Mirrors the Android `StyleDrawSet`.
///
/// Culling and runs follow the measured prototype: brick slices are culled
/// against the frustum by their bounding sphere, sorted by buffer offset and
/// merged when exactly adjacent; runs re-point buffer 0 at their first record.
final class GridStyleDrawSet {
  static let spikesPerSurface = 24
  static let spikeBallPx: Float = 10
  static let splatDepthBias: Float = 4
  static let splatDepthSlope: Float = 1
  /// Residency bytes of brick and region summaries (`renderer_delta.dart`).
  static let residencyOnDisk = 1
  static let residencySummaryOnly = 2
  static let summaryBrickAlpha: Float = 0.22
  static let pagedRegionAlpha: Float = 0.15
  static let brickMetres = GridBrickKey.brickMetres
  private static let brickRadius = GridBrickKey.brickMetres * Float(3).squareRoot() * 0.5
  private static let greyBit: UInt64 = 0x8000_0000
  static let lineRingSlots = 3

  let device: MTLDevice
  private let points: MTLRenderPipelineState
  private var focusUniform = [Float](repeating: 0, count: 64 * 4)
  private var focusCount = 0
  private var focusVersion = -1
  private let splatDepth: MTLRenderPipelineState
  private let splatColour: MTLRenderPipelineState
  private let spike: MTLRenderPipelineState
  private let voxel: MTLRenderPipelineState
  private let boxWire: MTLRenderPipelineState
  private let boxFill: MTLRenderPipelineState
  private let targets: MTLRenderPipelineState
  private let lines: MTLRenderPipelineState
  private let rawPoints: MTLRenderPipelineState
  private let depthLessWrite: MTLDepthStencilState?
  private let depthLessEqualRead: MTLDepthStencilState?
  private let depthLessRead: MTLDepthStencilState?
  private let depthAlways: MTLDepthStencilState?
  /// Colour tables: 256 x 6 RGBA8, row = colour attribute code.
  let colourTexture: MTLTexture
  private var tablesVersion = -1
  private var targetFloats = [Float](
    repeating: 0, count: PlannerTargetMessageLayout.maxTargets * PlannerTargetMessageLayout.floatsPerTarget)
  private var targetUniform = [SIMD4<Float>](repeating: .zero, count: PlannerTargetMessageLayout.maxTargets * 2)
  private var targetCount = 0
  private var targetsVersion = -1
  private var lineRing: [MTLBuffer]
  private var lineRingValue: [UInt64]
  private var lineSlot = 0
  private var volumeRing: [MTLBuffer]
  private var volumeRingValue: [UInt64]
  private var volumeSlot = 0
  private var rawPointRing: [MTLBuffer]
  private var rawPointRingValue: [UInt64]
  private var rawPointSlot = 0

  // Visible runs per slice table, reused per frame.
  private var runKeys = [UInt64](repeating: 0, count: 1024)
  private var surfaceRunStart = [Int](repeating: 0, count: 1024)
  private var surfaceRunEnd = [Int](repeating: 0, count: 1024)
  private var surfaceRunGrey = [Bool](repeating: false, count: 1024)
  private var surfaceRunCount = 0
  private var voxelRunStart = [Int](repeating: 0, count: 1024)
  private var voxelRunEnd = [Int](repeating: 0, count: 1024)
  private var voxelRunGrey = [Bool](repeating: false, count: 1024)
  private var voxelRunCount = 0
  private var spikeSlices = [Int](repeating: 0, count: 256)
  private var spikeSliceCount = 0
  private var planes = [SIMD4<Float>](repeating: .zero, count: 6)

  /// Overlay whose scope greys grid content this frame (bricks whose centre
  /// is outside it draw desaturated), or nil. Set by the host per frame.
  var scope: GridLineOverlay?

  private(set) var counters = StyleDrawCounters()
  /// Times a run table had to grow (zero in steady state once warm).
  private(set) var runGrowths = 0
  /// Frames whose overlay lines were skipped because every line slot was in flight.
  private(set) var lineSlotBusyFrames = 0

  init(device: MTLDevice, colorPixelFormat: MTLPixelFormat, depthPixelFormat: MTLPixelFormat,
       lineCapacitySegments: Int = GridLineBatch.defaultCapacity) throws {
    self.device = device
    let library = try GridStyleShaders.library(for: device)
    func pipeline(_ label: String, _ vs: String, _ fs: String, blend: Bool = false, colourWrites: Bool = true) throws
      -> MTLRenderPipelineState {
      let d = MTLRenderPipelineDescriptor()
      d.label = "grid \(label)"
      d.vertexFunction = library.makeFunction(name: vs)
      d.fragmentFunction = library.makeFunction(name: fs)
      d.colorAttachments[0].pixelFormat = colorPixelFormat
      d.depthAttachmentPixelFormat = depthPixelFormat
      if !colourWrites { d.colorAttachments[0].writeMask = [] }
      if blend {
        let a = d.colorAttachments[0]!
        a.isBlendingEnabled = true
        a.sourceRGBBlendFactor = .sourceAlpha
        a.destinationRGBBlendFactor = .oneMinusSourceAlpha
        a.sourceAlphaBlendFactor = .sourceAlpha
        a.destinationAlphaBlendFactor = .oneMinusSourceAlpha
      }
      return try device.makeRenderPipelineState(descriptor: d)
    }
    points = try pipeline("points", "grid_points_vs", "grid_points_fs")
    splatDepth = try pipeline("splat depth", "grid_splat_vs", "grid_splat_fs", colourWrites: false)
    splatColour = try pipeline("splat colour", "grid_splat_vs", "grid_splat_fs", blend: true)
    spike = try pipeline("spikes", "grid_spike_vs", "grid_flat_fs")
    voxel = try pipeline("voxels", "grid_voxel_vs", "grid_flat_fs")
    boxWire = try pipeline("box wire", "grid_box_wire_vs", "grid_box_fs")
    boxFill = try pipeline("box fill", "grid_box_fill_vs", "grid_box_fs", blend: true)
    targets = try pipeline("targets", "grid_target_vs", "grid_flat_fs")
    lines = try pipeline("lines", "grid_line_vs", "grid_line_fs", blend: true)
    rawPoints = try pipeline("raw feature points", "grid_raw_points_vs", "grid_raw_points_fs", blend: true)
    func depth(_ compare: MTLCompareFunction, write: Bool) -> MTLDepthStencilState? {
      guard depthPixelFormat != .invalid else { return nil }
      let d = MTLDepthStencilDescriptor()
      d.depthCompareFunction = compare
      d.isDepthWriteEnabled = write
      return device.makeDepthStencilState(descriptor: d)
    }
    depthLessWrite = depth(.less, write: true)
    depthLessEqualRead = depth(.lessEqual, write: false)
    depthLessRead = depth(.less, write: false)
    depthAlways = depth(.always, write: false)
    let td = MTLTextureDescriptor.texture2DDescriptor(
      pixelFormat: .rgba8Unorm, width: ColourTableLayout.entries, height: GridDisplayCodes.colourAttributes,
      mipmapped: false)
    td.usage = [.shaderRead]
    td.storageMode = .private
    guard let texture = device.makeTexture(descriptor: td) else {
      throw GridRendererAllocationError(buffer: "colour tables", bytes: DisplayModeHandler.tableBytes)
    }
    texture.label = "grid colour tables"
    colourTexture = texture
    var ring: [MTLBuffer] = []
    let lineBytes = lineCapacitySegments * 2 * GridLineBatch.vertexBytes
    for s in 0..<GridStyleDrawSet.lineRingSlots {
      guard let b = device.makeBuffer(length: lineBytes, options: [.storageModeShared, .cpuCacheModeWriteCombined]) else {
        throw GridRendererAllocationError(buffer: "overlay lines", bytes: lineBytes)
      }
      b.label = "grid overlay lines \(s)"
      ring.append(b)
    }
    lineRing = ring
    lineRingValue = Array(repeating: 0, count: GridStyleDrawSet.lineRingSlots)
    var volumes: [MTLBuffer] = []
    for s in 0..<GridStyleDrawSet.lineRingSlots {
      guard let b = device.makeBuffer(length: 2048 * 36 * GridLineBatch.vertexBytes, options: .storageModeShared) else {
        throw GridRendererAllocationError(buffer: "overlay volumes", bytes: 2048 * 36 * GridLineBatch.vertexBytes)
      }
      b.label = "grid overlay volumes \(s)"
      volumes.append(b)
    }
    volumeRing = volumes
    volumeRingValue = Array(repeating: 0, count: GridStyleDrawSet.lineRingSlots)
    var pointRing: [MTLBuffer] = []
    let pointBytes = GridStyleDrawSet.maxRawFeaturePoints * GridStyleDrawSet.rawPointBytes
    for s in 0..<GridStyleDrawSet.lineRingSlots {
      guard let b = device.makeBuffer(length: pointBytes, options: [.storageModeShared, .cpuCacheModeWriteCombined]) else {
        throw GridRendererAllocationError(buffer: "raw feature points", bytes: pointBytes)
      }
      b.label = "grid raw feature points \(s)"
      pointRing.append(b)
    }
    rawPointRing = pointRing
    rawPointRingValue = Array(repeating: 0, count: GridStyleDrawSet.lineRingSlots)
  }

  /// Uploads colour tables (through the staging ring) and takes planner
  /// targets when the display handler has newer ones. Render thread, while
  /// [target] has an open upload frame.
  func syncDisplayResources(_ display: DisplayModeHandler, target: MetalStagedBufferTarget) {
    let focusKnown = display.currentFocusVersion
    if focusKnown != focusVersion {
      focusCount = max(0, display.snapshotFocus(into: &focusUniform, knownVersion: focusVersion))
      focusVersion = focusKnown
    }
    if display.currentTablesVersion != tablesVersion {
      var staged: (MTLBuffer, Int)?
      var version = -1
      withUnsafeTemporaryAllocation(byteCount: DisplayModeHandler.tableBytes, alignment: 16) { scratch in
        version = display.snapshotTables(into: scratch.baseAddress!, knownVersion: tablesVersion)
        if version >= 0 { staged = target.stage(UnsafeRawBufferPointer(scratch)) }
      }
      if let (buffer, offset) = staged,
         let blit = target.blitEncoder(for: colourTexture, range: 0..<DisplayModeHandler.tableBytes) {
        let rowBytes = ColourTableLayout.entries * 4
        blit.copy(
          from: buffer, sourceOffset: offset, sourceBytesPerRow: rowBytes,
          sourceBytesPerImage: rowBytes * GridDisplayCodes.colourAttributes,
          sourceSize: MTLSize(width: ColourTableLayout.entries, height: GridDisplayCodes.colourAttributes, depth: 1),
          to: colourTexture, destinationSlice: 0, destinationLevel: 0, destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0))
        tablesVersion = version
      }
      // Not staged (slot busy): retried next frame.
    }
    let known = display.currentTargetsVersion
    if known != targetsVersion {
      let count = display.snapshotTargets(into: &targetFloats, knownVersion: targetsVersion)
      if count >= 0 {
        targetCount = count
        let f = PlannerTargetMessageLayout.floatsPerTarget
        for t in 0..<count {
          let s = t * f
          targetUniform[t * 2] = SIMD4(targetFloats[s], targetFloats[s + 1], targetFloats[s + 2], targetFloats[s + 6])
          targetUniform[t * 2 + 1] = SIMD4(targetFloats[s + 3], targetFloats[s + 4], targetFloats[s + 5], 0)
        }
      }
      targetsVersion = known
    }
  }

  /// Draws the grid styles of [mode] into [encoder] (the pass is already set
  /// up, depth cleared).
  func draw(_ encoder: MTLRenderCommandEncoder, mode: GridDisplayState, view: GridFrameView,
            store: InstanceBufferStore, buffers: MetalStagedBufferTarget) {
    counters = StyleDrawCounters()
    extractPlanes(view.viewProjection)
    cullSlices(store.surfaceSlices, isVoxel: false)
    cullSlices(store.voxelSlices, isVoxel: true)
    encoder.setCullMode(.none)
    encoder.setVertexTexture(colourTexture, index: 0)
    var base = GridStyleUniforms()
    base.viewProj = view.viewProjection
    base.camPos = SIMD4(view.cameraPosition, 1)
    base.focusCount = mode.has(GridDisplayCodes.overlayFocusPriority) ? Int32(focusCount) : 0
    focusUniform.withUnsafeBytes { bytes in
      encoder.setVertexBytes(bytes.baseAddress!, length: bytes.count, index: 2)
    }
    base.focalPx = view.focalPx
    base.colorAttr = Int32(mode.colourAttribute)
    base.sizeRule = Int32(mode.sizeRule)
    base.minQuality = Int32(mode.minQuality)
    base.alpha = mode.has(GridDisplayCodes.overlayHalfTransparency) ? 0.5 : 1
    switch mode.baseLayer {
    case GridDisplayCodes.basePoints:
      drawPoints(encoder, base, buffers, store, selectedOnly: false, fixedPx: mode.pointSizePx)
    case GridDisplayCodes.baseSplats:
      drawSplats(encoder, base, buffers)
    case GridDisplayCodes.baseVoxels:
      drawVoxels(encoder, base, buffers)
    default:
      break
    }
    GridStyleDrawSet.forEachLevelOfDetailPass(mode) { buffer, residency, alpha in
      drawBoxes(encoder, base, buffers, store, buffer, wire: false, residencyFilter: residency, alpha: alpha)
    }
    if mode.has(GridDisplayCodes.overlaySpikeBalls) { drawSpikes(encoder, base, buffers, store) }
    if mode.has(GridDisplayCodes.overlayBricks) {
      drawBoxes(encoder, base, buffers, store, .bricks, wire: true, residencyFilter: -1, alpha: 1)
    }
    if mode.has(GridDisplayCodes.overlayRegions) {
      drawBoxes(encoder, base, buffers, store, .regions, wire: false, residencyFilter: -1,
                alpha: GridStyleDrawSet.pagedRegionAlpha)
    }
    if mode.has(GridDisplayCodes.overlayTargets) { drawTargets(encoder, base) }
  }

  /// Draws the overlay segments of [batch] after the grid styles: the
  /// depth-tested part, then the on-top part without the depth test. The
  /// vertices go to a line ring slot that [completion] reports free; a frame
  /// with every slot in flight skips its lines. [frameValue] is the value
  /// [completion] reports once this frame's command buffer completed.
  func drawLines(_ encoder: MTLRenderCommandEncoder, batch: GridLineBatch, view: GridFrameView,
                 completion: GridFrameCompletion, frameValue: UInt64) {
    if batch.segments == 0 { return }
    let buffer: MTLBuffer
    if batch.volumetric {
      volumeSlot = (volumeSlot + 1) % volumeRing.count
      if completion.completedValue < volumeRingValue[volumeSlot] {
        lineSlotBusyFrames += 1
        return
      }
      buffer = volumeRing[volumeSlot]
      volumeRingValue[volumeSlot] = frameValue
    } else {
    lineSlot = (lineSlot + 1) % lineRing.count
    if completion.completedValue < lineRingValue[lineSlot] {
      lineSlotBusyFrames += 1
      return
    }
    buffer = lineRing[lineSlot]
    lineRingValue[lineSlot] = frameValue
    }
    let bytes = min(batch.byteCount, buffer.length)
    buffer.contents().copyMemory(from: batch.vertices, byteCount: bytes)
    var u = GridStyleUniforms()
    u.viewProj = view.viewProjection
    encoder.setRenderPipelineState(lines)
    encoder.setVertexBuffer(buffer, offset: 0, index: 0)
    setUniforms(encoder, &u)
    let primitive: MTLPrimitiveType = batch.volumetric ? .triangle : .line
    let vertexCount = batch.verticesPerSegment
    let tested = batch.depthTestedSegments
    if tested > 0 {
      if let d = depthLessWrite { encoder.setDepthStencilState(d) }
      encoder.drawPrimitives(type: primitive, vertexStart: 0, vertexCount: tested * vertexCount)
      counters.drawCalls += 1
    }
    if batch.segments > tested {
      if let d = depthAlways { encoder.setDepthStencilState(d) }
      encoder.drawPrimitives(type: primitive, vertexStart: tested * vertexCount, vertexCount: (batch.segments - tested) * vertexCount)
      counters.drawCalls += 1
    }
    counters.lineSegments = batch.segments
  }

  /// Raw feature points drawn at most (ARKit reports a few hundred).
  static let maxRawFeaturePoints = 2048
  /// One packed float3 per point.
  static let rawPointBytes = 12
  /// Raw point dot diameter in points (dp).
  static let rawPointDp: Float = 5

  /// Draws [points] (world frame) as round dots on top of everything, from
  /// a ring slot that [completion] reports free; a frame with every slot in
  /// flight skips them. At most [maxRawFeaturePoints] are drawn.
  func drawRawPoints(_ encoder: MTLRenderCommandEncoder, points: [simd_float3], view: GridFrameView,
                     completion: GridFrameCompletion, frameValue: UInt64) {
    let count = min(points.count, GridStyleDrawSet.maxRawFeaturePoints)
    if count == 0 { return }
    rawPointSlot = (rawPointSlot + 1) % rawPointRing.count
    if completion.completedValue < rawPointRingValue[rawPointSlot] { return }
    let buffer = rawPointRing[rawPointSlot]
    let out = buffer.contents()
    let stride = GridStyleDrawSet.rawPointBytes
    for i in 0..<count {
      let p = points[i]
      out.storeBytes(of: p.x, toByteOffset: i * stride, as: Float.self)
      out.storeBytes(of: p.y, toByteOffset: i * stride + 4, as: Float.self)
      out.storeBytes(of: p.z, toByteOffset: i * stride + 8, as: Float.self)
    }
    rawPointRingValue[rawPointSlot] = frameValue
    var u = GridStyleUniforms()
    u.viewProj = view.viewProjection
    u.fixedPx = GridStyleDrawSet.rawPointDp * view.pxPerDp
    encoder.setRenderPipelineState(rawPoints)
    encoder.setVertexBuffer(buffer, offset: 0, index: 0)
    setUniforms(encoder, &u)
    if let d = depthAlways { encoder.setDepthStencilState(d) }
    encoder.drawPrimitives(type: .point, vertexStart: 0, vertexCount: count)
    counters.drawCalls += 1
    counters.rawFeaturePoints = count
  }

  // MARK: styles

  private func setUniforms(_ encoder: MTLRenderCommandEncoder, _ u: inout GridStyleUniforms) {
    let size = MemoryLayout<GridStyleUniforms>.stride
    encoder.setVertexBytes(&u, length: size, index: 1)
    encoder.setFragmentBytes(&u, length: size, index: 1)
  }

  private func drawPoints(_ encoder: MTLRenderCommandEncoder, _ base: GridStyleUniforms, _ buffers: MetalStagedBufferTarget,
                          _ store: InstanceBufferStore, selectedOnly: Bool, fixedPx: Float) {
    guard let surfaces = buffers.metalBuffer(.surfaces) else { return }
    var u = base
    u.fixedPx = fixedPx
    u.sphere = 1
    u.selectedOnly = selectedOnly ? 1 : 0
    if selectedOnly { u.sizeRule = Int32(GridDisplayCodes.sizeFixed) }
    encoder.setRenderPipelineState(points)
    if let d = depthLessWrite { encoder.setDepthStencilState(d) }
    let stride = SurfaceInstanceLayout.bytes
    if selectedOnly {
      setUniforms(encoder, &u)
      let table = store.surfaceSlices
      for i in 0..<spikeSliceCount {
        let s = spikeSlices[i]
        encoder.setVertexBuffer(surfaces, offset: table.first[s] * stride, index: 0)
        encoder.drawPrimitives(type: .point, vertexStart: 0, vertexCount: table.count[s])
        counters.drawCalls += 1
        counters.instances += table.count[s]
      }
    } else {
      for r in 0..<surfaceRunCount {
        let n = surfaceRunEnd[r] - surfaceRunStart[r]
        u.grey = surfaceRunGrey[r] ? 1 : 0
        setUniforms(encoder, &u)
        encoder.setVertexBuffer(surfaces, offset: surfaceRunStart[r] * stride, index: 0)
        encoder.drawPrimitives(type: .point, vertexStart: 0, vertexCount: n)
        counters.drawCalls += 1
        counters.instances += n
      }
    }
  }

  /// Soft splats in two passes ("visibility splatting"), as on Android: pass
  /// 1 writes only depth, pushed back by a depth bias; pass 2 blends the
  /// colour of every splat in front of that depth without writing depth, so
  /// coplanar splats accumulate while hidden surfaces stay hidden. The
  /// half-transparency flag uses an identical checkerboard discard in both
  /// passes and opaque surviving fragments: overlap never fills the camera
  /// pixels. This is spatial transparency, with no extra framebuffer; depth
  /// comes from reconstructed surfels rather than live camera scene depth.
  private func drawSplats(_ encoder: MTLRenderCommandEncoder, _ base: GridStyleUniforms, _ buffers: MetalStagedBufferTarget) {
    guard let surfaces = buffers.metalBuffer(.surfaces) else { return }
    encoder.setRenderPipelineState(splatDepth)
    if let d = depthLessWrite { encoder.setDepthStencilState(d) }
    encoder.setDepthBias(GridStyleDrawSet.splatDepthBias, slopeScale: GridStyleDrawSet.splatDepthSlope, clamp: 0)
    splatRuns(encoder, base, surfaces, colour: false)
    encoder.setDepthBias(0, slopeScale: 0, clamp: 0)
    encoder.setRenderPipelineState(splatColour)
    if let d = depthLessEqualRead { encoder.setDepthStencilState(d) }
    splatRuns(encoder, base, surfaces, colour: true)
  }

  private func splatRuns(_ encoder: MTLRenderCommandEncoder, _ base: GridStyleUniforms, _ surfaces: MTLBuffer, colour: Bool) {
    var u = base
    for r in 0..<surfaceRunCount {
      let n = surfaceRunEnd[r] - surfaceRunStart[r]
      u.grey = surfaceRunGrey[r] ? 1 : 0
      setUniforms(encoder, &u)
      encoder.setVertexBuffer(surfaces, offset: surfaceRunStart[r] * SurfaceInstanceLayout.bytes, index: 0)
      encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4, instanceCount: n)
      counters.drawCalls += 1
      if colour { counters.instances += n }
    }
  }

  private func drawSpikes(_ encoder: MTLRenderCommandEncoder, _ base: GridStyleUniforms, _ buffers: MetalStagedBufferTarget,
                          _ store: InstanceBufferStore) {
    guard let surfaces = buffers.metalBuffer(.surfaces), spikeSliceCount > 0 else { return }
    var u = base
    u.selectedOnly = 1
    encoder.setRenderPipelineState(spike)
    if let d = depthLessWrite { encoder.setDepthStencilState(d) }
    setUniforms(encoder, &u)
    let table = store.surfaceSlices
    for i in 0..<spikeSliceCount {
      let s = spikeSlices[i]
      encoder.setVertexBuffer(surfaces, offset: table.first[s] * SurfaceInstanceLayout.bytes, index: 0)
      encoder.drawPrimitives(
        type: .triangleStrip, vertexStart: 0, vertexCount: 4, instanceCount: table.count[s] * GridStyleDrawSet.spikesPerSurface)
      counters.drawCalls += 1
      counters.instances += table.count[s] * GridStyleDrawSet.spikesPerSurface
    }
    // The ball: a shaded sprite at each selected surface.
    drawPoints(encoder, base, buffers, store, selectedOnly: true, fixedPx: GridStyleDrawSet.spikeBallPx)
  }

  private func drawVoxels(_ encoder: MTLRenderCommandEncoder, _ base: GridStyleUniforms, _ buffers: MetalStagedBufferTarget) {
    guard let voxels = buffers.metalBuffer(.voxels) else { return }
    var u = base
    encoder.setRenderPipelineState(voxel)
    if let d = depthLessWrite { encoder.setDepthStencilState(d) }
    for r in 0..<voxelRunCount {
      let n = voxelRunEnd[r] - voxelRunStart[r]
      u.grey = voxelRunGrey[r] ? 1 : 0
      setUniforms(encoder, &u)
      encoder.setVertexBuffer(voxels, offset: voxelRunStart[r] * GridBuffer.voxels.recordBytes, index: 0)
      encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 14, instanceCount: n)
      counters.drawCalls += 1
      counters.instances += n
      counters.voxelInstances += n
    }
  }

  private func drawBoxes(_ encoder: MTLRenderCommandEncoder, _ base: GridStyleUniforms, _ buffers: MetalStagedBufferTarget,
                         _ store: InstanceBufferStore, _ buffer: GridBuffer, wire: Bool, residencyFilter: Int, alpha: Float) {
    let count = buffer == .bricks ? store.brickSlots : store.regionSlots
    guard let summaries = buffers.metalBuffer(buffer), count > 0 else { return }
    var u = base
    u.scale = buffer == .bricks ? GridStyleDrawSet.brickMetres : GridStyleDrawSet.brickMetres * 8
    u.summaryKind = buffer == .bricks ? 0 : 1
    u.residencyFilter = Int32(residencyFilter)
    u.alpha = alpha
    let translucent = alpha < 1
    encoder.setRenderPipelineState(wire ? boxWire : boxFill)
    if let d = translucent ? depthLessRead : depthLessWrite { encoder.setDepthStencilState(d) }
    setUniforms(encoder, &u)
    encoder.setVertexBuffer(summaries, offset: 0, index: 0)
    if wire {
      encoder.drawPrimitives(type: .line, vertexStart: 0, vertexCount: 24, instanceCount: count)
    } else {
      encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 14, instanceCount: count)
    }
    counters.drawCalls += 1
    counters.instances += count
  }

  private func drawTargets(_ encoder: MTLRenderCommandEncoder, _ base: GridStyleUniforms) {
    if targetCount == 0 { return }
    var u = base
    encoder.setRenderPipelineState(targets)
    if let d = depthLessWrite { encoder.setDepthStencilState(d) }
    setUniforms(encoder, &u)
    targetUniform.withUnsafeBytes { raw in
      encoder.setVertexBytes(raw.baseAddress!, length: targetCount * 2 * MemoryLayout<SIMD4<Float>>.stride, index: 0)
    }
    encoder.drawPrimitives(type: .line, vertexStart: 0, vertexCount: 6, instanceCount: targetCount)
    counters.drawCalls += 1
    counters.instances += targetCount
  }

  // MARK: culling

  private func extractPlanes(_ m: simd_float4x4) {
    // Gribb-Hartmann: plane = row 3 +/- row k (rows of the column-major matrix).
    let row3 = SIMD4(m.columns.0.w, m.columns.1.w, m.columns.2.w, m.columns.3.w)
    for p in 0..<6 {
      let k = p / 2
      let sign: Float = p % 2 == 0 ? 1 : -1
      let rowK = SIMD4(m.columns.0[k], m.columns.1[k], m.columns.2[k], m.columns.3[k])
      let plane = row3 + sign * rowK
      let len = max(simd_length(SIMD3(plane.x, plane.y, plane.z)), 1e-6)
      planes[p] = plane / len
    }
  }

  private func visible(_ key: Int64) -> Bool {
    let c = SIMD4<Float>(
      (Float(GridBrickKey.x(key)) + 0.5) * GridStyleDrawSet.brickMetres,
      (Float(GridBrickKey.y(key)) + 0.5) * GridStyleDrawSet.brickMetres,
      (Float(GridBrickKey.z(key)) + 0.5) * GridStyleDrawSet.brickMetres, 1)
    for p in planes where simd_dot(p, c) < -GridStyleDrawSet.brickRadius { return false }
    return true
  }

  private func cullSlices(_ table: BrickSliceTable, isVoxel: Bool) {
    var n = 0
    if !isVoxel { spikeSliceCount = 0 }
    let scope = self.scope
    for i in 0..<table.highWater {
      let count = table.count[i]
      let key = table.brickKey[i]
      if count == 0 || !visible(key) { continue }
      if n == runKeys.count {
        runKeys.append(contentsOf: repeatElement(0, count: n))
        runGrowths += 1
      }
      var grey = false
      if let scope = scope {
        grey = !scope.isInScope(
          (Float(GridBrickKey.x(key)) + 0.5) * GridStyleDrawSet.brickMetres,
          (Float(GridBrickKey.y(key)) + 0.5) * GridStyleDrawSet.brickMetres,
          (Float(GridBrickKey.z(key)) + 0.5) * GridStyleDrawSet.brickMetres)
      }
      runKeys[n] = UInt64(table.first[i]) << 32 | UInt64(count) | (grey ? GridStyleDrawSet.greyBit : 0)
      n += 1
      if !isVoxel && table.selected[i] > 0 {
        if spikeSliceCount == spikeSlices.count {
          spikeSlices.append(contentsOf: repeatElement(0, count: spikeSliceCount))
          runGrowths += 1
        }
        spikeSlices[spikeSliceCount] = i
        spikeSliceCount += 1
      }
    }
    runKeys[0..<n].sort()
    var starts = isVoxel ? voxelRunStart : surfaceRunStart
    var ends = isVoxel ? voxelRunEnd : surfaceRunEnd
    var greys = isVoxel ? voxelRunGrey : surfaceRunGrey
    if isVoxel {
      voxelRunStart = []; voxelRunEnd = []; voxelRunGrey = []
    } else {
      surfaceRunStart = []; surfaceRunEnd = []; surfaceRunGrey = []
    }
    var runs = 0
    for k in 0..<n {
      let start = Int(runKeys[k] >> 32)
      let end = start + Int(runKeys[k] & 0x7fff_ffff)
      let grey = runKeys[k] & GridStyleDrawSet.greyBit != 0
      if runs > 0 && ends[runs - 1] == start && greys[runs - 1] == grey {
        ends[runs - 1] = end
      } else {
        if runs == starts.count {
          starts.append(contentsOf: repeatElement(0, count: runs))
          ends.append(contentsOf: repeatElement(0, count: runs))
          greys.append(contentsOf: repeatElement(false, count: runs))
          runGrowths += 1
        }
        starts[runs] = start
        ends[runs] = end
        greys[runs] = grey
        runs += 1
      }
    }
    if isVoxel {
      voxelRunStart = starts; voxelRunEnd = ends; voxelRunGrey = greys; voxelRunCount = runs
      counters.visibleVoxelSlices = n
    } else {
      surfaceRunStart = starts; surfaceRunEnd = ends; surfaceRunGrey = greys; surfaceRunCount = runs
      counters.visibleSurfaceSlices = n
    }
  }

  /// The level-of-detail boxes under a base layer, as (buffer, residency
  /// filter, alpha): far bricks have no surface slices, so their summaries
  /// stand in; paged-out regions have neither surfaces nor brick summaries
  /// in RAM, so their region summaries stand in (the region overlay, when
  /// on, already draws every region).
  static func forEachLevelOfDetailPass(_ mode: GridDisplayState, _ pass: (GridBuffer, Int, Float) -> Void) {
    if mode.baseLayer == GridDisplayCodes.baseNone { return }
    pass(.bricks, residencySummaryOnly, summaryBrickAlpha)
    if !mode.has(GridDisplayCodes.overlayRegions) { pass(.regions, residencyOnDisk, pagedRegionAlpha) }
  }
}

extension GridStyleShaders {
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
}
