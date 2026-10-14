import Foundation
import Metal
import simd
import XCTest

@testable import ar_flutter_plugin_2

/// Draws every grid style from a known synthetic delta set offscreen with
/// Metal and probes pixels at known screen positions (functional bounds on
/// the host or simulator GPU, not performance), as the Android
/// `InstancedStyleRenderTest` does with GLES.
///
/// Scene: camera at the origin looking down -Z, 60 degree vertical field of
/// view, 256 x 256 pixels. An 8 x 8 patch of surfaces faces the camera at
/// z = -2 around the screen centre (brick (-1, -1, -3)), its compact voxels
/// sit at the same place, one brick summary and one region summary enclose
/// it. Colour tables are solid per attribute: coverage red, confidence
/// green, age white, source yellow, completion blue, so a probe tells which
/// attribute drew.
final class GridStyleRenderTests: XCTestCase {
  private static let size = 256
  private var device: MTLDevice!
  private var queue: MTLCommandQueue!
  private var host: GridRendererHost!
  private var colour: MTLTexture!
  private var depth: MTLTexture!
  private var readback: MTLBuffer!
  private var pixels: [UInt8] = []
  private var view = GridFrameView()
  private let writer = RendererDeltaWriter()
  private let brickKey = GridBrickKey.pack(-1, -1, -3)

  override func setUpWithError() throws {
    guard let device = MTLCreateSystemDefaultDevice() else { throw XCTSkip("no Metal device") }
    self.device = device
    queue = try XCTUnwrap(device.makeCommandQueue())
    host = try GridRendererHost(device: device)
    XCTAssertTrue(host.prepare(colorPixelFormat: .rgba8Unorm, depthPixelFormat: .depth32Float))
    let size = GridStyleRenderTests.size
    let cd = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: size, height: size, mipmapped: false)
    cd.usage = [.renderTarget]
    cd.storageMode = .private
    colour = try XCTUnwrap(device.makeTexture(descriptor: cd))
    let dd = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .depth32Float, width: size, height: size, mipmapped: false)
    dd.usage = [.renderTarget]
    dd.storageMode = .private
    depth = try XCTUnwrap(device.makeTexture(descriptor: dd))
    readback = try XCTUnwrap(device.makeBuffer(length: size * size * 4, options: .storageModeShared))
    let projection = GridStyleRenderTests.perspective(fovY: .pi / 3, aspect: 1, near: 0.05, far: 100)
    view = GridFrameView(view: matrix_identity_float4x4, projection: projection, cameraPosition: .zero,
                         drawableWidth: size, drawableHeight: size, pxPerDp: 1)
    sendTables()
    sendScene()
  }

  /// Metal (clip z 0..1) right-handed perspective, as ARKit's projection.
  static func perspective(fovY: Float, aspect: Float, near: Float, far: Float) -> simd_float4x4 {
    let ys = 1 / tan(fovY * 0.5)
    let xs = ys / aspect
    let zs = far / (near - far)
    return simd_float4x4(columns: (
      SIMD4(xs, 0, 0, 0), SIMD4(0, ys, 0, 0), SIMD4(0, 0, zs, -1), SIMD4(0, 0, zs * near, 0)))
  }

  /// Runs one frame: uploads, the grid styles and line overlays; returns uploaded bytes.
  @discardableResult
  private func render() throws -> Int {
    let commandBuffer = try XCTUnwrap(queue.makeCommandBuffer())
    let uploaded = host.encodeUploads(into: commandBuffer)
    let pass = MTLRenderPassDescriptor()
    pass.colorAttachments[0].texture = colour
    pass.colorAttachments[0].loadAction = .clear
    pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
    pass.colorAttachments[0].storeAction = .store
    pass.depthAttachment.texture = depth
    pass.depthAttachment.loadAction = .clear
    pass.depthAttachment.clearDepth = 1
    pass.depthAttachment.storeAction = .dontCare
    let encoder = try XCTUnwrap(commandBuffer.makeRenderCommandEncoder(descriptor: pass))
    host.encode(into: encoder, view: view)
    encoder.endEncoding()
    let size = GridStyleRenderTests.size
    let blit = try XCTUnwrap(commandBuffer.makeBlitCommandEncoder())
    blit.copy(from: colour, sourceSlice: 0, sourceLevel: 0, sourceOrigin: MTLOrigin(x: 0, y: 0, z: 0),
              sourceSize: MTLSize(width: size, height: size, depth: 1), to: readback, destinationOffset: 0,
              destinationBytesPerRow: size * 4, destinationBytesPerImage: size * size * 4)
    blit.endEncoding()
    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    XCTAssertNil(commandBuffer.error)
    XCTAssertFalse(host.rendererFailed)
    pixels = [UInt8](UnsafeRawBufferPointer(start: readback.contents(), count: size * size * 4))
    return uploaded
  }

  private struct RGBA: CustomStringConvertible {
    let r: Int, g: Int, b: Int, a: Int
    var description: String { "(\(r), \(g), \(b), \(a))" }
  }

  /// RGBA of the pixel at window (x, y) with y measured from the top.
  private func probe(_ x: Int, _ y: Int) -> RGBA {
    let o = (y * GridStyleRenderTests.size + x) * 4
    return RGBA(r: Int(pixels[o]), g: Int(pixels[o + 1]), b: Int(pixels[o + 2]), a: Int(pixels[o + 3]))
  }

  private func drawnPixels() -> Int {
    stride(from: 3, to: pixels.count, by: 4).filter { pixels[$0] != 0 }.count
  }

  /// Window pixel (x, y from the top) of a world point.
  private func pixel(of p: SIMD3<Float>) -> (Int, Int) {
    let clip = view.viewProjection * SIMD4(p, 1)
    let size = Float(GridStyleRenderTests.size)
    return (Int((clip.x / clip.w * 0.5 + 0.5) * size), Int((0.5 - clip.y / clip.w * 0.5) * size))
  }

  private func mode(_ base: Int, overlays: Int = 0, colour: Int = GridDisplayCodes.colourCoverage,
                    size: Int = GridDisplayCodes.sizeFixed, minQuality: Int = 0) {
    writer.displayMode(baseLayer: base, overlayBits: overlays, colourAttribute: colour, sizeRule: size,
                       pointSizePx: 16, minQuality: minQuality)
    XCTAssertEqual(writer.withMessage { host.onDisplayMessage($0) }, .mode)
  }

  private func sendTables() {
    let colours: [UInt32] = [0xFF00_00FF, 0x00FF_00FF, 0xFFFF_FFFF, 0xFFFF_00FF, 0x0000_FFFF]
    for attribute in 0..<GridDisplayCodes.colourAttributes {
      writer.colourTable(attribute: attribute, rgba: colours[attribute])
      XCTAssertEqual(writer.withMessage { host.onDisplayMessage($0) }, .colourTable)
    }
  }

  private func offer() {
    XCTAssertEqual(writer.withMessage { host.onRenderMessage($0) }, GridRendererStatus.ok)
  }

  private func sendScene() {
    writer.begin(RendererDeltaLayout.kindReset, firstSlot: 0, records: 0, capacity: 4096, brickKey: 0)
    offer()
    writer.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: 0, records: 64, capacity: 4096, brickKey: brickKey)
    for i in 0..<64 {
      let x = (Float(i % 8) - 3.5) * 0.03
      let y = (Float(i / 8) - 3.5) * 0.03
      writer.surface(
        i, x, y, -2, 0, 0, 1, size: 0.08, coverage: 255, confidence: 255,
        flags: SurfaceInstanceLayout.flagPublished | (i == 27 ? SurfaceInstanceLayout.flagSelected : 0), binQuality: 0)
    }
    offer()
    writer.begin(RendererDeltaLayout.kindCompactVoxels, firstSlot: 0, records: 16, capacity: 1024, brickKey: brickKey)
    writer.voxel(0, -1, -1, -20, colour: 200)
    writer.voxel(1, 0, -1, -20, colour: 200)
    writer.voxel(2, -1, 0, -20, colour: 200)
    writer.voxel(3, 0, 0, -20, colour: 200)
    for i in 4..<16 { writer.voxel(i, 0, 0, 0, colour: -1) }
    offer()
    writer.begin(RendererDeltaLayout.kindBrickSummaries, firstSlot: 0, records: 1, capacity: 64, brickKey: 0)
    writer.brickSummary(0, -1, -1, -3, surfaces: 64, complete: 0, meanCoverage: 200, residency: 0)
    offer()
    writer.begin(RendererDeltaLayout.kindRegionSummaries, firstSlot: 0, records: 1, capacity: 16, brickKey: 0)
    writer.regionSummary(0, -1, -1, -1, bricks: 1, surfaces: 64, completion: 200, residency: 0)
    offer()
    writer.begin(RendererDeltaLayout.kindFrameEnd, firstSlot: 0, records: 0, capacity: 0, brickKey: 0)
    offer()
  }

  private var centre: Int { GridStyleRenderTests.size / 2 }

  func testPointsDrawAtTheirProjectedPosition() throws {
    mode(GridDisplayCodes.basePoints)
    try render()
    let c = probe(centre, centre)
    XCTAssertTrue(c.r > 60 && c.g < 40 && c.a > 0, "centre \(c)")
    XCTAssertEqual(probe(4, 4).a, 0)
    XCTAssertEqual(host.drawCounters?.visibleSurfaceSlices, 1)
  }

  func testSplatsCoverThePatchWithoutHoles() throws {
    mode(GridDisplayCodes.baseSplats)
    try render()
    // Overlapping coplanar splats leave no hole inside the patch (one
    // splat's transparent rim must not hide its neighbours by depth).
    for dy in stride(from: -9, through: 9, by: 3) {
      for dx in stride(from: -9, through: 9, by: 3) {
        let p = probe(centre + dx, centre + dy)
        XCTAssertTrue(p.r > 60 && p.a > 0, "(\(dx), \(dy)) \(p)")
      }
    }
    XCTAssertEqual(probe(4, 4).a, 0)
  }

  func testCompactVoxelsDrawAndPaddingCollapses() throws {
    mode(GridDisplayCodes.baseVoxels)
    try render()
    // Cubes are 9 cm in 10 cm cells: the screen centre lies in the 1 cm gap
    // between the four voxels, so probe each voxel's centre.
    for (vx, vy) in [(-1, -1), (0, -1), (-1, 0), (0, 0)] {
      let (x, y) = pixel(of: SIMD3((Float(vx) + 0.5) * 0.1, (Float(vy) + 0.5) * 0.1, -1.95))
      let p = probe(x, y)
      XCTAssertTrue(p.r > 60 && p.a > 0, "voxel (\(vx), \(vy)) at (\(x), \(y)): \(p)")
    }
    // Twelve padding entries are drawn and collapse off screen.
    XCTAssertEqual(host.drawCounters?.voxelInstances, 16)
    XCTAssertEqual(host.drawCounters?.visibleVoxelSlices, 1)
    let cube = Int(0.09 / 1.905 * view.focalPx)
    let drawn = drawnPixels()
    XCTAssertTrue((4 * (cube - 2) * (cube - 2))...(4 * (cube + 3) * (cube + 3)) ~= drawn, "drawn \(drawn), cube \(cube)")
  }

  func testSpikeBallsExpandTwentyFourInstancesPerSelectedSurface() throws {
    mode(GridDisplayCodes.baseNone, overlays: GridDisplayCodes.overlaySpikeBalls)
    try render()
    // 64 surfaces x 24 spikes in the selected slice, then the ball sprites.
    XCTAssertGreaterThanOrEqual(host.drawCounters?.instances ?? 0, 64 * 24)
    XCTAssertGreaterThan(drawnPixels(), 0)
    // The selected surface (index 27) sits 2 px from the screen centre.
    XCTAssertGreaterThan(probe(centre, centre).a, 0)
  }

  func testBricksAndRegionsDrawFromSummaries() throws {
    mode(GridDisplayCodes.baseNone, overlays: GridDisplayCodes.overlayBricks)
    try render()
    XCTAssertGreaterThan(drawnPixels(), 0)
    mode(GridDisplayCodes.baseNone, overlays: GridDisplayCodes.overlayRegions)
    try render()
    // The region box spans x, y, z in [-6.4, 0]: lower left of the view.
    XCTAssertGreaterThan(probe(GridStyleRenderTests.size / 4, GridStyleRenderTests.size * 3 / 4).a, 0)
  }

  func testMinimumQualityHidesLowConfidenceSurfaces() throws {
    mode(GridDisplayCodes.basePoints, minQuality: 255)
    try render()
    XCTAssertGreaterThan(probe(centre, centre).a, 0, "confidence 255 passes 255")
    writer.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: 0, records: 64, capacity: 4096, brickKey: brickKey)
    for i in 0..<64 {
      writer.surface(i, (Float(i % 8) - 3.5) * 0.03, (Float(i / 8) - 3.5) * 0.03, -2, 0, 0, 1, size: 0.08,
                     coverage: 255, confidence: 100, binQuality: 0)
    }
    offer()
    try render()
    XCTAssertEqual(drawnPixels(), 0)
  }

  func testModeSwitchIsVisibleInTheNextFrameWithoutUploads() throws {
    mode(GridDisplayCodes.basePoints, colour: GridDisplayCodes.colourCoverage)
    try render()
    XCTAssertGreaterThan(probe(centre, centre).r, 60)
    let staged = host.target.stagedBytes
    let growths = host.target.growths
    let surfaces = host.target.metalBuffer(.surfaces).map { ObjectIdentifier($0) }
    let tableVersion = host.core.store.tableVersion
    let frameBefore = host.core.frameNumber
    mode(GridDisplayCodes.basePoints, colour: GridDisplayCodes.colourConfidence)
    try render()
    XCTAssertEqual(host.core.modeAppliedFrame, frameBefore + 1)
    let c = probe(centre, centre)
    XCTAssertTrue(c.g > 60 && c.r < 40, "centre \(c)")
    XCTAssertEqual(host.target.stagedBytes, staged)
    XCTAssertEqual(host.target.growths, growths)
    XCTAssertEqual(host.target.metalBuffer(.surfaces).map { ObjectIdentifier($0) }, surfaces)
    XCTAssertEqual(host.core.store.tableVersion, tableVersion)
  }

  func testColourTableChangeUploadsOnlyTheTableTexture() throws {
    mode(GridDisplayCodes.basePoints)
    try render()
    let surfaceBlits = host.target.stagedBytes
    writer.colourTable(attribute: GridDisplayCodes.colourCoverage, rgba: 0x0000_FFFF)
    XCTAssertEqual(writer.withMessage { host.onDisplayMessage($0) }, .colourTable)
    try render()
    let c = probe(centre, centre)
    XCTAssertTrue(c.b > 60 && c.r < 40, "centre \(c)")
    XCTAssertEqual(host.target.stagedBytes - surfaceBlits, DisplayModeHandler.tableBytes)
  }

  func testOverlayLinesDrawAndOutOfScopeSurfacesGrey() throws {
    let overlay = ScopeLineOverlay()
    host.core.addLineOverlay(overlay)
    mode(GridDisplayCodes.basePoints)
    try render()
    // The surface patch is out of scope: grey, not red.
    let c = probe(centre, centre)
    XCTAssertTrue(c.a > 0 && abs(c.r - c.g) < 16, "centre \(c)")
    // The line at y = 0.5 m, z = -2 m projects about 55 px above the centre.
    let found = ((centre - 64)..<(centre - 44)).contains { probe(centre, $0).g > 100 }
    XCTAssertTrue(found)
    XCTAssertEqual(host.drawCounters?.lineSegments, 1)
  }

  func testHundredThousandSurfacesUploadWithinTheFrameBudget() throws {
    mode(GridDisplayCodes.basePoints)
    try render()
    let bricks = 100_000 / 256 + 1
    writer.begin(RendererDeltaLayout.kindReset, firstSlot: 0, records: 0, capacity: bricks * 256, brickKey: 0)
    offer()
    var frames = 0
    var b = 0
    var worstMs = 0.0
    while b < bricks || host.core.scheduler.queuedMessages > 0 {
      // Feed about one fusion batch per frame, as the grid isolate does.
      var fed = 0
      while b < bricks && fed < 6 {
        let key = GridBrickKey.pack(b % 32 - 16, b / 32 % 4 - 2, -4 - b / 128)
        writer.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: b * 256, records: 256, capacity: bricks * 256,
                     brickKey: key)
        for i in 0..<256 {
          writer.surface(i, Float(b % 32 - 16) * 0.8 + Float(i % 16) * 0.05, Float(b / 32 % 4 - 2) * 0.8 + Float(i / 16) * 0.05,
                         -3.2 - Float(b / 128) * 0.8, 0, 0, 1)
        }
        if writer.withMessage({ host.onRenderMessage($0) }) != GridRendererStatus.ok { break }
        b += 1
        fed += 1
      }
      let start = Date()
      let uploaded = try render()
      worstMs = max(worstMs, Date().timeIntervalSince(start) * 1000)
      XCTAssertLessThanOrEqual(uploaded, DeltaUploadScheduler.defaultFrameBudgetBytes)
      frames += 1
      XCTAssertLessThan(frames, 10_000, "upload did not finish")
      if frames >= 10_000 { break }
    }
    print("GRID_RENDERER_UPLOAD 100k surfaces: \(frames) frames, worst \(Int(worstMs)) ms, " +
          "staged \(host.target.stagedBytes) B, blit encoders \(host.target.blitEncoders)")
    XCTAssertEqual(host.core.store.surfaceSlices.size, bricks)
    XCTAssertLessThanOrEqual(host.core.scheduler.counters.maxFrameBytes, DeltaUploadScheduler.defaultFrameBudgetBytes)
    XCTAssertEqual(host.core.scheduler.counters.overflows, 0)
    XCTAssertEqual(host.core.store.capacityBytes(.surfaces), bricks * 256 * SurfaceInstanceLayout.bytes)
  }

  /// The bytes of a persistent private buffer, copied back through a blit.
  private func readBack(_ buffer: GridBuffer) throws -> [UInt8] {
    let source = try XCTUnwrap(host.target.metalBuffer(buffer))
    let shared = try XCTUnwrap(device.makeBuffer(length: source.length, options: .storageModeShared))
    let commandBuffer = try XCTUnwrap(queue.makeCommandBuffer())
    let blit = try XCTUnwrap(commandBuffer.makeBlitCommandEncoder())
    blit.copy(from: source, sourceOffset: 0, to: shared, destinationOffset: 0, size: source.length)
    blit.endEncoding()
    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    return [UInt8](UnsafeRawBufferPointer(start: shared.contents(), count: source.length))
  }

  /// Every contract golden kind plus overlapping writes, clears and a growth
  /// within one frame leave the Metal buffers byte-equal to the CPU mirror.
  func testMetalBuffersMatchTheByteMirrorForEveryDeltaKind() throws {
    // A fresh renderer without the shared scene.
    host = try GridRendererHost(device: device)
    XCTAssertTrue(host.prepare(colorPixelFormat: .rgba8Unorm, depthPixelFormat: .depth32Float))
    let mirror = ByteArrayGpuTarget()
    let mirrorStore = InstanceBufferStore(target: mirror)
    var messages: [Data] = []
    writer.begin(RendererDeltaLayout.kindReset, firstSlot: 0, records: 0, capacity: 64, brickKey: 0)
    messages.append(writer.message)
    writer.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: 0, records: 64, capacity: 64, brickKey: brickKey)
    for i in 0..<64 { writer.surface(i, Float(i), 1, -2, 0, 0, 1, stableId: 100 + i) }
    messages.append(writer.message)
    writer.begin(RendererDeltaLayout.kindFreeSlots, firstSlot: 0, records: 1, capacity: 64, brickKey: 0)
    writer.freeRange(0, first: 32, count: 32)
    messages.append(writer.message)
    writer.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: 48, records: 8, capacity: 64, brickKey: GridBrickKey.pack(0, 0, 0))
    for i in 0..<8 { writer.surface(i, -Float(i), 2, -3, 0, 1, 0, stableId: 500 + i) }
    messages.append(writer.message)
    for name in ["render_brick_summaries.bin", "render_region_summaries.bin", "render_compact_voxels.bin",
                 "render_occupancy_masks.bin", "render_surface_slice.bin", "render_free_slots.bin",
                 "render_frame_end.bin"] {
      messages.append(try GridRendererFixtures.golden(name))
    }
    for message in messages {
      XCTAssertEqual(message.withUnsafeBytes { host.onRenderMessage($0) }, GridRendererStatus.ok)
      XCTAssertEqual(try mirrorStore.apply(message), .ok)
    }
    // One frame takes all of them (about 4 KiB of uploads); the surface
    // buffer grows from 64 to 4096 records within it.
    try render()
    XCTAssertEqual(host.core.scheduler.queuedMessages, 0)
    for buffer in GridBuffer.allCases {
      let bytes = try readBack(buffer)
      XCTAssertEqual(bytes.count, mirror.buffer(buffer).count, "\(buffer)")
      XCTAssertTrue(bytes == mirror.buffer(buffer), "\(buffer) differs from the mirror")
    }
    XCTAssertEqual(host.core.store.surfaceSlices.size, mirrorStore.surfaceSlices.size)
    XCTAssertEqual(host.core.store.brickSlots, mirrorStore.brickSlots)
  }

  func testAFailedRendererRefusesDeltasUntilPrepareAndThenAsksForAResync() throws {
    mode(GridDisplayCodes.basePoints)
    try render()
    XCTAssertNotNil(host.target.metalBuffer(.surfaces))
    var reasons: [String] = []
    host.onFailure = { reasons.append($0) }
    host.rendererDidFail("test")
    XCTAssertEqual(reasons, ["test"])
    XCTAssertTrue(host.rendererFailed)
    XCTAssertEqual(host.failureCount, 1)
    XCTAssertNil(host.target.metalBuffer(.surfaces))
    writer.begin(RendererDeltaLayout.kindReset, firstSlot: 0, records: 0, capacity: 64, brickKey: 0)
    XCTAssertEqual(writer.withMessage { host.onRenderMessage($0) }, GridRendererStatus.rendererFailed)
    XCTAssertTrue(host.prepare(colorPixelFormat: .rgba8Unorm, depthPixelFormat: .depth32Float))
    writer.begin(RendererDeltaLayout.kindSurfaceSlice, firstSlot: 0, records: 1, capacity: 64, brickKey: brickKey)
    XCTAssertEqual(writer.withMessage { host.onRenderMessage($0) }, GridRendererStatus.resyncRequired)
    writer.begin(RendererDeltaLayout.kindReset, firstSlot: 0, records: 0, capacity: 64, brickKey: 0)
    XCTAssertEqual(writer.withMessage { host.onRenderMessage($0) }, GridRendererStatus.ok)
  }
}

/// A line overlay that greys everything and draws one green line at y = 0.5 m, z = -2 m.
private final class ScopeLineOverlay: GridLineOverlay {
  var messageMagic: UInt32 { contractMagic("C3ZZ") }
  func onMessage(_ message: UnsafeRawBufferPointer) -> Bool { true }
  func buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink) -> Int {
    sink.line(-1, 0.5, -2, 1, 0.5, -2, rgba: 0x00FF_00FF)
    return 1
  }
  var greyOutside: Bool { true }
  func isInScope(_ x: Float, _ y: Float, _ z: Float) -> Bool { false }
  func reset() {}
}
