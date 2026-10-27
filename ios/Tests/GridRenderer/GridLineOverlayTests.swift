import Foundation
import Metal
import simd
import XCTest

@testable import ar_flutter_plugin_2

/// Records overlay segments.
private final class SegmentRecorder: GridOverlayLineSink {
  struct Segment {
    let a: SIMD3<Float>
    let b: SIMD3<Float>
    let rgba: UInt32
  }

  var segments: [Segment] = []

  func line(_ x0: Float, _ y0: Float, _ z0: Float, _ x1: Float, _ y1: Float, _ z1: Float, rgba: UInt32) {
    segments.append(Segment(a: SIMD3(x0, y0, z0), b: SIMD3(x1, y1, z1), rgba: rgba))
  }
}

/// Little-endian message builder for overlay tests.
private struct MessageBytes {
  var bytes: [UInt8]

  init(count: Int) { bytes = [UInt8](repeating: 0, count: count) }

  mutating func u8(_ o: Int, _ v: Int) { bytes[o] = UInt8(truncatingIfNeeded: v) }
  mutating func u16(_ o: Int, _ v: Int) { for i in 0..<2 { bytes[o + i] = UInt8(truncatingIfNeeded: v >> (8 * i)) } }
  mutating func u32(_ o: Int, _ v: UInt32) { for i in 0..<4 { bytes[o + i] = UInt8(truncatingIfNeeded: v >> (8 * UInt32(i))) } }
  mutating func f32(_ o: Int, _ v: Float) { u32(o, v.bitPattern) }

  var data: Data { Data(bytes) }
}

private func hexBytes(_ hex: String) -> Data {
  var out = Data()
  var i = hex.startIndex
  while i < hex.endIndex {
    let j = hex.index(i, offsetBy: 2)
    out.append(UInt8(hex[i..<j], radix: 16)!)
    i = j
  }
  return out
}

private func vertex(_ batch: GridLineBatch, _ index: Int) -> SIMD3<Float> {
  let o = index * GridLineBatch.vertexBytes
  return SIMD3(
    batch.vertices.load(fromByteOffset: o, as: Float.self),
    batch.vertices.load(fromByteOffset: o + 4, as: Float.self),
    batch.vertices.load(fromByteOffset: o + 8, as: Float.self))
}

private func vertexByte(_ batch: GridLineBatch, _ offset: Int) -> UInt8 {
  batch.vertices.load(fromByteOffset: offset, as: UInt8.self)
}

/// Data-only overlays of the grid renderer: routing by magic, per-frame
/// lines and scope, marker outlines (`C3MO`), pose frusta (`C3PM`) and
/// guided-capture quests (`C3QO`), as the Android overlay tests.
final class GridLineOverlayTests: XCTestCase {
  private final class RecordingOverlay: GridLineOverlay {
    var messages = 0
    var lastLength = 0
    var firstWord: UInt32 = 0
    var grey = false

    var messageMagic: UInt32 { QuestOverlayLayout.magic }
    var greyOutside: Bool { grey }

    func onMessage(_ message: UnsafeRawBufferPointer) -> Bool {
      messages += 1
      lastLength = message.count
      firstWord = message.leU32(0)
      return message.count >= 8
    }

    func buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink) -> Int {
      sink.line(camX, camY, camZ, camX, camY, camZ - 1, rgba: 0x00FF_00FF)
      sink.line(0, 0, 0, 1, 0, 0, rgba: 0xFF00_00FF)
      return 2
    }

    func isInScope(_ x: Float, _ y: Float, _ z: Float) -> Bool { x >= 0 }
    func reset() {}
  }

  // MARK: routing and the line batch

  func testDisplayMessagesWithTheOverlayMagicReachTheOverlayOthersTheModeHandler() {
    let core = GridRendererCore(target: ByteArrayGpuTarget())
    let overlay = RecordingOverlay()
    core.addLineOverlay(overlay)
    var message = MessageBytes(count: 20)
    message.u32(0, QuestOverlayLayout.magic)
    XCTAssertEqual(core.display(message.data), .overlay)
    XCTAssertEqual(overlay.messages, 1)
    XCTAssertEqual(overlay.lastLength, 20)
    XCTAssertEqual(overlay.firstWord, QuestOverlayLayout.magic)
    XCTAssertEqual(core.display(message.data.prefix(4)), .rejected)
    let w = RendererDeltaWriter()
    w.displayMode(baseLayer: GridDisplayCodes.baseSplats, overlayBits: 0, colourAttribute: GridDisplayCodes.colourAge, sizeRule: 0)
    XCTAssertEqual(core.display(w), .mode)
    XCTAssertEqual(overlay.messages, 2)
  }

  func testLinesCollectIntoTheBatchAndTheScopeOverlayIsReported() {
    let core = GridRendererCore(target: ByteArrayGpuTarget())
    let overlay = RecordingOverlay()
    core.addLineOverlay(overlay)
    let batch = GridLineBatch(capacitySegments: 3)
    XCTAssertEqual(core.buildOverlayLines(camX: 0.5, camY: 1, camZ: 2, batch: batch), 2)
    XCTAssertEqual(vertex(batch, 0).x, 0.5)
    XCTAssertEqual(vertex(batch, 1).y, 1)
    XCTAssertEqual(vertexByte(batch, 12), 0x00)
    XCTAssertEqual(vertexByte(batch, 13), 0xFF)
    XCTAssertEqual(core.buildOverlayLines(camX: 0, camY: 0, camZ: 0, batch: batch), 2)
    for _ in 0..<2 { batch.line(0, 0, 0, 0, 0, 0, rgba: 0) }
    XCTAssertEqual(batch.segments, 3)
    XCTAssertEqual(batch.dropped, 1)
    XCTAssertNil(core.scopeOverlay())
    overlay.grey = true
    XCTAssertTrue(core.scopeOverlay() === overlay)
  }

  // MARK: marker outlines

  private var now: Int64 = 10_000

  /// One entry per (id, state, flags, size, x, y, z, age ms); identity rotation.
  private func markerMessage(_ entries: [(Int, Int, Int, Float, Float, Float, Float, Int)]) -> Data {
    var b = MessageBytes(count: MarkerOutlineLayout.headerBytes + entries.count * MarkerOutlineLayout.entryBytes)
    b.u32(0, MarkerOutlineLayout.magic)
    b.u16(4, 1)
    b.u16(6, entries.count)
    b.u16(8, 1920)
    b.u16(10, 1080)
    b.u16(12, 1)
    for (i, e) in entries.enumerated() {
      let at = MarkerOutlineLayout.headerBytes + i * MarkerOutlineLayout.entryBytes
      b.u16(at, e.0)
      b.u8(at + 2, e.1)
      b.u8(at + 3, e.2)
      b.f32(at + 4, e.3)
      b.f32(at + 40, e.4)
      b.f32(at + 44, e.5)
      b.f32(at + 48, e.6)
      b.f32(at + 64, 1)  // q w
      b.u32(at + 68, UInt32(bitPattern: Int32(e.7)))
    }
    return b.data
  }

  private func markerCore() -> (GridRendererCore, MarkerOutlineOverlay) {
    let overlay = MarkerOutlineOverlay(nowMs: { [unowned self] in self.now })
    let core = GridRendererCore(target: ByteArrayGpuTarget())
    core.addLineOverlay(overlay)
    return (core, overlay)
  }

  func testAStableMarkerDrawsASquareOfItsPrintedSizeAtItsPose() {
    let (core, _) = markerCore()
    let batch = GridLineBatch()
    XCTAssertEqual(core.display(markerMessage([(7, MarkerOutlineLayout.stateStable, 3, 0.1, 1, 2, -3, 0)])), .overlay)
    XCTAssertEqual(core.buildOverlayLines(camX: 0, camY: 0, camZ: 0, batch: batch), MarkerOutlineOverlay.segmentsPerMarker)
    for i in 0..<4 { XCTAssertEqual(simd_length(vertex(batch, 2 * i) - vertex(batch, 2 * i + 1)), 0.1, accuracy: 1e-5) }
    // Corners lie on the marker plane z = -3 around (1, 2).
    XCTAssertEqual(vertex(batch, 0).x, 0.95, accuracy: 1e-5)
    XCTAssertEqual(vertex(batch, 0).y, 2.05, accuracy: 1e-5)
    XCTAssertEqual(vertex(batch, 0).z, -3, accuracy: 1e-5)
    XCTAssertEqual(vertexByte(batch, 12), UInt8(MarkerOutlineOverlay.colour(of: MarkerOutlineLayout.stateStable) >> 24))
  }

  func testAMarkerSeenNowFlashesAndExpiresAndEntriesWithoutAPoseAreSkipped() {
    let (core, _) = markerCore()
    let batch = GridLineBatch()
    _ = core.display(markerMessage([
      (3, MarkerOutlineLayout.stateSeenNow, 3, 0.055, 0, 0, -1, 100),
      (4, MarkerOutlineLayout.stateSeenNow, 1, 0.055, 0, 0, 0, 0),
    ]))
    XCTAssertEqual(core.buildOverlayLines(camX: 0, camY: 0, camZ: 0, batch: batch), MarkerOutlineOverlay.segmentsPerMarker)
    now += 400
    XCTAssertEqual(core.buildOverlayLines(camX: 0, camY: 0, camZ: 0, batch: batch), MarkerOutlineOverlay.segmentsPerMarker)
    now += 200  // 100 + 600 ms > flash time
    XCTAssertEqual(core.buildOverlayLines(camX: 0, camY: 0, camZ: 0, batch: batch), 0)
    // An empty message (new group) clears everything at once.
    _ = core.display(markerMessage([(5, MarkerOutlineLayout.stateMoved, 2, 0.1, 0, 0, -2, -1)]))
    XCTAssertEqual(core.buildOverlayLines(camX: 0, camY: 0, camZ: 0, batch: batch), MarkerOutlineOverlay.segmentsPerMarker)
    _ = core.display(markerMessage([]))
    XCTAssertEqual(core.buildOverlayLines(camX: 0, camY: 0, camZ: 0, batch: batch), 0)
  }

  func testMalformedOutlineMessagesAreRejectedAndKeepTheShownOutlines() {
    let (core, overlay) = markerCore()
    let batch = GridLineBatch()
    _ = core.display(markerMessage([(7, MarkerOutlineLayout.stateStable, 2, 0.1, 0, 0, -1, 0)]))
    let short = markerMessage([(8, MarkerOutlineLayout.stateStable, 2, 0.1, 0, 0, -1, 0)])
    XCTAssertEqual(core.display(short.prefix(short.count - 1)), .rejected)
    var badVersion = [UInt8](markerMessage([]))
    badVersion[4] = 2
    XCTAssertEqual(core.display(Data(badVersion)), .rejected)
    XCTAssertEqual(overlay.rejected, 2)
    XCTAssertEqual(core.buildOverlayLines(camX: 0, camY: 0, camZ: 0, batch: batch), MarkerOutlineOverlay.segmentsPerMarker)
  }

  // MARK: pose frusta

  private var clockNs: UInt64 = 0
  private let both = PoseFrustumStyle.flagShowPhotos | PoseFrustumStyle.flagShowKeyframes

  /// (id, kind, state, x, y, z, quaternion xyzw)
  private func poseMessage(_ flags: Int, _ poses: [(Int, Int, Int, Float, Float, Float, SIMD4<Float>)],
                           magic: UInt32 = PoseFrustumStyle.magic) -> Data {
    var b = MessageBytes(count: PoseFrustumStyle.headerBytes + poses.count * PoseFrustumStyle.recordBytes)
    b.u32(0, magic)
    b.u16(4, 1)
    b.u16(PoseFrustumStyle.countOffset, poses.count)
    b.u32(PoseFrustumStyle.flagsOffset, UInt32(flags))
    for (i, p) in poses.enumerated() {
      let o = PoseFrustumStyle.headerBytes + i * PoseFrustumStyle.recordBytes
      b.u32(o, UInt32(p.0))
      b.u8(o + PoseFrustumStyle.kindOffset, p.1)
      b.u8(o + PoseFrustumStyle.stateOffset, p.2)
      b.f32(o + PoseFrustumStyle.positionOffset, p.3)
      b.f32(o + PoseFrustumStyle.positionOffset + 4, p.4)
      b.f32(o + PoseFrustumStyle.positionOffset + 8, p.5)
      for k in 0..<4 { b.u16(o + PoseFrustumStyle.rotationOffset + 2 * k, Int((p.6[k] * 32767).rounded())) }
    }
    return b.data
  }

  private func pose(_ id: Int, _ kind: Int, state: Int = PoseFrustumStyle.stateStable, x: Float = 0, y: Float = 0,
                    z: Float = 0, q: SIMD4<Float> = SIMD4(0, 0, 0, 1)) -> (Int, Int, Int, Float, Float, Float, SIMD4<Float>) {
    (id, kind, state, x, y, z, q)
  }

  private func lines(_ style: PoseFrustumStyle) -> [SegmentRecorder.Segment] {
    let r = SegmentRecorder()
    XCTAssertEqual(style.buildLines(camX: 0, camY: 0, camZ: 0, sink: r), r.segments.count)
    return r.segments
  }

  private func send(_ style: PoseFrustumStyle, _ data: Data) -> Bool {
    data.withUnsafeBytes { style.onMessage($0) }
  }

  func testPhotosDrawEightSegmentsAndKeyframesThree() {
    let style = PoseFrustumStyle(clockNs: { [unowned self] in self.clockNs })
    XCTAssertTrue(send(style, poseMessage(both, [
      pose(1, PoseFrustumStyle.kindPhoto), pose(2, PoseFrustumStyle.kindKeyframe), pose(3, PoseFrustumStyle.kindKeyframe),
    ])))
    XCTAssertEqual(style.poseCount, 3)
    XCTAssertEqual(lines(style).count, 8 + 3 + 3)
    XCTAssertTrue(send(style, poseMessage(PoseFrustumStyle.flagShowPhotos, [])))
    XCTAssertEqual(lines(style).count, 8)
    XCTAssertTrue(send(style, poseMessage(0, [])))
    XCTAssertEqual(lines(style).count, 0)
  }

  func testRemovedStateDeletesAndFullRefreshReplaces() {
    let style = PoseFrustumStyle(clockNs: { [unowned self] in self.clockNs })
    _ = send(style, poseMessage(both, [
      pose(1, PoseFrustumStyle.kindPhoto), pose(2, PoseFrustumStyle.kindKeyframe), pose(3, PoseFrustumStyle.kindPhoto),
    ]))
    _ = send(style, poseMessage(both, [pose(1, PoseFrustumStyle.kindPhoto, state: PoseFrustumStyle.stateRemoved)]))
    XCTAssertEqual(style.poseCount, 2)
    XCTAssertEqual(lines(style).count, 8 + 3)
    _ = send(style, poseMessage(both | PoseFrustumStyle.flagFullRefresh, [pose(9, PoseFrustumStyle.kindKeyframe)]))
    XCTAssertEqual(style.poseCount, 1)
    _ = send(style, poseMessage(both, [
      pose(9, PoseFrustumStyle.kindKeyframe, x: 1), pose(42, PoseFrustumStyle.kindPhoto, state: PoseFrustumStyle.stateRemoved),
    ]))
    XCTAssertEqual(style.poseCount, 1)
    let s = lines(style)[0]
    XCTAssertEqual((s.a.x + s.b.x) / 2, 1, accuracy: 1e-6)
  }

  func testColoursFollowTheStateTheColourTableAndTheFlash() {
    let style = PoseFrustumStyle(clockNs: { [unowned self] in self.clockNs })
    _ = send(style, poseMessage(both, [
      pose(1, PoseFrustumStyle.kindKeyframe, state: PoseFrustumStyle.stateFresh),
      pose(2, PoseFrustumStyle.kindKeyframe, state: PoseFrustumStyle.stateStabilizing, x: 0.5),
      pose(3, PoseFrustumStyle.kindKeyframe, state: PoseFrustumStyle.stateStable | PoseFrustumStyle.flashBit, x: 1),
    ]))
    clockNs = PoseFrustumStyle.flashHalfPeriodNs
    var colours = lines(style).map { $0.rgba }
    XCTAssertEqual(colours[0], PoseFrustumStyle.defaultFresh)
    XCTAssertEqual(colours[3], PoseFrustumStyle.defaultStabilizing)
    XCTAssertEqual(colours[6], PoseFrustumStyle.defaultStable)
    clockNs = 2 * PoseFrustumStyle.flashHalfPeriodNs
    XCTAssertEqual(lines(style)[6].rgba, PoseFrustumStyle.defaultFlash)
    var table = MessageBytes(count: PoseFrustumStyle.headerBytes + 16)
    table.u32(0, PoseFrustumStyle.magic)
    table.u16(4, 1)
    table.u32(8, UInt32(PoseFrustumStyle.flagColourTable))
    table.u32(16, 0x1111_1111)
    table.u32(20, 0x2222_2222)
    table.u32(24, 0x3333_3333)
    table.u32(28, 0x4444_4444)
    XCTAssertTrue(send(style, table.data))
    clockNs = PoseFrustumStyle.flashHalfPeriodNs
    colours = lines(style).map { $0.rgba }
    XCTAssertEqual([colours[0], colours[3], colours[6]], [0x1111_1111, 0x2222_2222, 0x3333_3333])
    XCTAssertEqual(style.colour(at: 3), 0x4444_4444)
  }

  func testMalformedPoseMessagesAreRejected() {
    let style = PoseFrustumStyle(clockNs: { [unowned self] in self.clockNs })
    XCTAssertFalse(send(style, poseMessage(both, [pose(1, PoseFrustumStyle.kindPhoto)], magic: 0x0BAD_F00D)))
    XCTAssertFalse(send(style, poseMessage(both, [pose(1, PoseFrustumStyle.kindPhoto)]).prefix(20)))
    XCTAssertFalse(send(style, Data(count: 8)))
    XCTAssertEqual(style.rejectedMessages, 3)
    XCTAssertEqual(style.poseCount, 0)
  }

  func testFrustumApexCornersAndRotation() {
    let style = PoseFrustumStyle(clockNs: { [unowned self] in self.clockNs })
    _ = send(style, poseMessage(both, [pose(1, PoseFrustumStyle.kindPhoto, x: 1, y: 2, z: 3)]))
    var s = lines(style)
    for i in 0..<4 { XCTAssertLessThan(simd_length(s[i].a - SIMD3(1, 2, 3)), 1e-5) }
    XCTAssertLessThan(simd_length(s[0].b - SIMD3(0.95, 1.9625, 2.92)), 1e-5)
    XCTAssertLessThan(simd_length(s[2].b - SIMD3(1.05, 2.0375, 2.92)), 1e-5)
    for i in 0..<4 { XCTAssertLessThan(simd_length(s[i].b - s[4 + i].a), 1e-5) }
    // 90 degrees about +Y: local -Z (the view direction) maps to world -X.
    let h = Float(0.5).squareRoot()
    _ = send(style, poseMessage(both | PoseFrustumStyle.flagFullRefresh, [pose(1, PoseFrustumStyle.kindPhoto, q: SIMD4(0, h, 0, h))]))
    s = lines(style)
    XCTAssertLessThan(simd_length(s[0].b - SIMD3(-0.08, -0.0375, 0.05)), 2e-4)
  }

  // MARK: guided-capture quests

  /// The reference quest overlay message of the Dart encoder
  /// (`quest_overlay_message_test.dart`; shared with the Android tests).
  private let questReference = hexBytes(
    "4333514f01001f0001000000010003000100040001186000cdcccc3d66662640" +
      "00000000000000000000000000000000010000000100ff01000000409a99993f" +
      "0000c03f000000409a99993f0000000000a0993e00008040000070410ad7a33d" +
      "0000803f9a99993f0000000000000000000000000000c03f9a99993f00000000" +
      "0000020000000000000000409a99993f00000000000000000000000000000040" +
      "8fc2f53d0000c03fdb0fc9bf0000000000000000000080400000000000008040" +
      "000040400000000000004040")

  func testTheReferenceQuestMessageDecodesFieldByField() {
    let frame = QuestOverlayFrame()
    XCTAssertEqual(questReference.withUnsafeBytes { frame.decode($0) }, .ok)
    XCTAssertEqual(frame.layers, 0x1f)
    XCTAssertEqual(frame.sequence, 1)
    XCTAssertEqual(frame.donutCount, 1)
    XCTAssertEqual(frame.trailVertexCount, 3)
    XCTAssertEqual(frame.footprintCount, 1)
    XCTAssertEqual(frame.zoneVertexCount, 4)
    XCTAssertEqual(frame.zoneKind, QuestOverlayLayout.zoneSpace)
    XCTAssertEqual(frame.zoneParams[1], 2.6, accuracy: 1e-6)
    XCTAssertEqual(frame.donutFlash[0], QuestOverlayLayout.flashNone)
    XCTAssertEqual(frame.donutFlags[0], QuestOverlayLayout.donutFlagAimed)
    XCTAssertEqual(frame.donutStandpoint[2], 1.5, accuracy: 1e-6)
    XCTAssertEqual(frame.ringMinDiameterDp, 24)
    XCTAssertEqual(frame.ringMaxDiameterDp, 96)
    XCTAssertEqual(frame.donutRingRadius[0], 0.08, accuracy: 1e-6)
    XCTAssertEqual(frame.trailStationState[1], QuestOverlayLayout.stationCaptured)
    XCTAssertEqual(frame.footprintYaw[0], -Float.pi / 2, accuracy: 1e-6)
    XCTAssertTrue(frame.isInScope(2, 1, 1.5))
    XCTAssertFalse(frame.isInScope(5, 1, 1.5))
    XCTAssertFalse(frame.isInScope(2, 3, 1.5))
    XCTAssertEqual(questReference.prefix(12).withUnsafeBytes { frame.decode($0) }, .tooShort)
  }

  func testADonutIsAThinSixteenCentimetreRingAtTheStandpoint() {
    let layer = QuestOverlayLayer()
    XCTAssertEqual(questReference.withUnsafeBytes { layer.onMessage($0) }, .ok)
    let r = SegmentRecorder()
    // 1.5 m behind the standpoint: three 32-segment loops, no reticle; plus
    // 2 dashed trail segments x 5 dashes, 2 soles x 10 edges, 4 zone edges.
    XCTAssertEqual(layer.buildLines(camX: 2, camY: 1.2, camZ: 3, sink: r), 3 * 32 + 10 + 20 + 4)
    let loops: [Float] = [0.064, 0.072, 0.08]
    for k in 0..<96 {
      let s = r.segments[k]
      XCTAssertEqual(s.a.z, 1.5, accuracy: 1e-5)
      XCTAssertEqual(simd_length(s.a - SIMD3(2, 1.2, 1.5)), loops[k / 32], accuracy: 1e-5)
      XCTAssertEqual(s.rgba, QuestOverlayGeometry.colourAimed)
    }
    XCTAssertTrue(layer.greyOutside)
  }

  func testQuestVolumesAreDepthTestedAndDiagnosticLinesRemainSeparate() {
    let core = GridRendererCore(target: ByteArrayGpuTarget())
    let quest = QuestLineOverlay()
    core.addLineOverlay(quest)
    core.addLineOverlay(DepthTestedOverlay())
    XCTAssertEqual(core.display(questReference), .overlay)
    let lines = GridLineBatch()
    XCTAssertEqual(core.buildOverlayLines(camX: 2, camY: 1.2, camZ: 12, batch: lines), 1)
    let batch = GridLineBatch(capacitySegments: 2048, volumetric: true)
    XCTAssertGreaterThan(core.buildOverlayLines(camX: 2, camY: 1.2, camZ: 12, batch: batch, focalPx: 1500, pxPerDp: 2), 1)
    XCTAssertEqual(batch.depthTestedSegments, batch.segments)
    XCTAssertEqual(batch.verticesPerSegment, 36)
    XCTAssertEqual(simd_length(vertex(batch, 0) - SIMD3(2, 1.2, 1.5)), 0.8 * 24 * 10.5 / 1500, accuracy: 0.02)
    XCTAssertEqual(core.display(questReference.prefix(12)), .rejected)
    XCTAssertEqual(quest.layer.rejectedMessages, 1)
  }

  func testVolumeBufferBoundsWholeTubesAndRejectsInvalidSegments() {
    let batch = GridLineBatch(capacitySegments: 1, volumetric: true)
    batch.line(0,0,0,0,0,0,rgba: 0)
    batch.line(0,0,0,.nan,1,0,rgba: 0)
    XCTAssertEqual(batch.segments,0)
    batch.line(0,0,0,0,1,0,rgba: 0x12345678)
    XCTAssertEqual(batch.segments,1)
    XCTAssertEqual(batch.byteCount,36*16)
    for i in 0..<36 {
      XCTAssertEqual(abs(vertex(batch,i).x),0.012,accuracy: 1e-6)
      XCTAssertEqual(vertexByte(batch,i*16+12),0x12)
    }
    batch.line(0,0,0,1,0,0,rgba: 0)
    XCTAssertEqual(batch.dropped,1)
    batch.clear()
    XCTAssertEqual(batch.segments,0)
  }

  func testNearLensTargetHidesRatherThanMovingToTheSurface() {
    let layer = QuestOverlayLayer()
    XCTAssertEqual(questReference.withUnsafeBytes { layer.onMessage($0) }, .ok)
    let r = SegmentRecorder()
    XCTAssertEqual(layer.buildLines(camX:2,camY:1.2,camZ:1.5,sink:r),10+20+4)
    let away = SegmentRecorder()
    _ = layer.buildLines(camX:2,camY:1.2,camZ:1.8,sink:away)
    for i in 0..<96 { XCTAssertEqual(away.segments[i].a.z,1.5,accuracy:1e-6) }
  }

  private final class DepthTestedOverlay: GridLineOverlay {
    var messageMagic: UInt32 { 0x1111_1111 }
    var greyOutside: Bool { false }
    func onMessage(_ message: UnsafeRawBufferPointer) -> Bool { false }
    func buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink) -> Int {
      sink.line(0, 0, 0, 1, 0, 0, rgba: 0x00FF_00FF)
      return 1
    }
    func isInScope(_ x: Float, _ y: Float, _ z: Float) -> Bool { true }
    func reset() {}
  }

  // MARK: overlay lifetime

  /// A new capture view starts without the previous view's quest frame,
  /// marker outlines and pose frusta (the Android renderer host is created
  /// per view); an engine detach forgets them too.
  func testANewCaptureViewAndAnEngineDetachDropThePreviousOverlayFrames() throws {
    guard let device = MTLCreateSystemDefaultDevice() else { throw XCTSkip("no Metal device") }
    let host = try GridRendererHost(device: device)
    let batch = GridLineBatch()
    let install = {
      host.beginView()
      XCTAssertEqual(host.core.display(self.questReference), .overlay)
      XCTAssertEqual(host.core.display(self.poseMessage(self.both, [self.pose(1, PoseFrustumStyle.kindPhoto)])), .overlay)
      XCTAssertEqual(host.core.display(
        self.markerMessage([(7, MarkerOutlineLayout.stateStable, 3, 0.1, 1, 2, -3, 0)])), .overlay)
      XCTAssertTrue(host.core.scopeOverlay() === host.questOverlay)
      XCTAssertGreaterThan(host.core.buildOverlayLines(camX: 2, camY: 1.2, camZ: 3, batch: batch), 8 + 4)
    }
    install()
    host.beginView()
    XCTAssertEqual(host.core.buildOverlayLines(camX: 2, camY: 1.2, camZ: 3, batch: batch), 0)
    XCTAssertNil(host.core.scopeOverlay())
    XCTAssertEqual(host.poseFrustumStyle.poseCount, 0)
    install()
    host.releaseBuffers()
    XCTAssertEqual(host.core.buildOverlayLines(camX: 2, camY: 1.2, camZ: 3, batch: batch), 0)
    XCTAssertNil(host.core.scopeOverlay())
  }
}
