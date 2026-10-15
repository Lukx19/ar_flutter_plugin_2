import Foundation

/// Layout of the quest overlay message written by the Dart guided capture
/// controller (`QuestOverlayLayout` in
/// `capture3d/lib/capture/guided_capture/quest_overlay_message.dart`).
/// Little-endian, at most 64 KiB; header 48 B, then donuts (48 B), trail
/// vertices (20 B), footprints (16 B) and zone vertices (8 B).
enum QuestOverlayLayout {
  static let magic = contractMagic("C3QO")
  static let version = 1
  static let headerBytes = 48
  static let donutBytes = 48
  static let trailVertexBytes = 20
  static let footprintBytes = 16
  static let zoneVertexBytes = 8
  static let maxMessageBytes = 65_536

  static let layerDonuts = 1
  static let layerTrails = 2
  static let layerFootprints = 4
  static let layerZoneOutline = 8
  static let layerGreyOutside = 16

  static let zoneNone = 0
  static let zoneSpace = 1
  static let zoneObject = 2

  static let flashNone = 255
  static let donutFlagAimed = 1
  static let donutFlagTrailStart = 2
  static let trailFlagActive = 1
  static let trailFlagCompleted = 2

  static let stateCaptured = 2
  static let stateTimedOut = 3
  static let stationCaptured = 2

  static let maxDonuts = 72
  static let maxTrailVertices = 2048
  static let maxFootprints = 72
  static let maxZoneVertices = 256
}

/// Why a quest overlay message was rejected.
enum QuestOverlayStatus: Equatable {
  case ok, tooShort, badMagic, badVersion, badLength
}

/// Decoded quest overlay in preallocated columns. [decode] copies a message
/// into the columns without allocating; the renderer reads them on its
/// thread after the hand-over.
final class QuestOverlayFrame {
  var sequence: UInt32 = 0
  var layers = 0
  var donutCount = 0
  var trailVertexCount = 0
  var footprintCount = 0
  var zoneVertexCount = 0
  var zoneKind = QuestOverlayLayout.zoneNone
  /// On-screen diameter range of donut rings in dp; 0 selects the platform default.
  var ringMinDiameterDp = 0
  var ringMaxDiameterDp = 0
  /// Space: floor y, ceiling y; object: centre xyz, half extents xyz.
  var zoneParams = [Float](repeating: 0, count: 6)

  var donutQuestId = [Int](repeating: 0, count: QuestOverlayLayout.maxDonuts)
  var donutState = [Int](repeating: 0, count: QuestOverlayLayout.maxDonuts)
  var donutKind = [Int](repeating: 0, count: QuestOverlayLayout.maxDonuts)
  var donutFlash = [Int](repeating: 0, count: QuestOverlayLayout.maxDonuts)
  var donutFlags = [Int](repeating: 0, count: QuestOverlayLayout.maxDonuts)
  var donutStandpoint = [Float](repeating: 0, count: QuestOverlayLayout.maxDonuts * 3)
  var donutAim = [Float](repeating: 0, count: QuestOverlayLayout.maxDonuts * 3)
  var donutPositionTolerance = [Float](repeating: 0, count: QuestOverlayLayout.maxDonuts)
  var donutInnerCircleDegrees = [Float](repeating: 0, count: QuestOverlayLayout.maxDonuts)
  var donutAngleToleranceDegrees = [Float](repeating: 0, count: QuestOverlayLayout.maxDonuts)
  /// Ring outer radius in metres; 0 selects the platform default.
  var donutRingRadius = [Float](repeating: 0, count: QuestOverlayLayout.maxDonuts)

  var trailPoint = [Float](repeating: 0, count: QuestOverlayLayout.maxTrailVertices * 3)
  var trailIndex = [Int](repeating: 0, count: QuestOverlayLayout.maxTrailVertices)
  var trailStationState = [Int](repeating: 0, count: QuestOverlayLayout.maxTrailVertices)
  var trailFlags = [Int](repeating: 0, count: QuestOverlayLayout.maxTrailVertices)

  var footprint = [Float](repeating: 0, count: QuestOverlayLayout.maxFootprints * 3)
  var footprintYaw = [Float](repeating: 0, count: QuestOverlayLayout.maxFootprints)

  var zoneVertexXz = [Float](repeating: 0, count: QuestOverlayLayout.maxZoneVertices * 2)

  func hasLayer(_ bit: Int) -> Bool { layers & bit != 0 }

  /// Decodes [b] (exactly one message); on failure the previous frame stays.
  func decode(_ b: UnsafeRawBufferPointer) -> QuestOverlayStatus {
    let length = b.count
    if length < QuestOverlayLayout.headerBytes { return .tooShort }
    if b.leU32(0) != QuestOverlayLayout.magic { return .badMagic }
    if b.le16(4) != QuestOverlayLayout.version { return .badVersion }
    let donuts = b.le16(12)
    let vertices = b.le16(14)
    let footprints = b.le16(16)
    let zoneVertices = b.le16(18)
    let expected = QuestOverlayLayout.headerBytes +
      donuts * QuestOverlayLayout.donutBytes +
      vertices * QuestOverlayLayout.trailVertexBytes +
      footprints * QuestOverlayLayout.footprintBytes +
      zoneVertices * QuestOverlayLayout.zoneVertexBytes
    if expected != length || donuts > QuestOverlayLayout.maxDonuts ||
      vertices > QuestOverlayLayout.maxTrailVertices || footprints > QuestOverlayLayout.maxFootprints ||
      zoneVertices > QuestOverlayLayout.maxZoneVertices {
      return .badLength
    }
    layers = b.le16(6)
    sequence = b.leU32(8)
    zoneKind = b.le8(20)
    ringMinDiameterDp = b.le8(21)
    ringMaxDiameterDp = b.le8(22)
    for i in 0..<6 { zoneParams[i] = b.leF32(24 + 4 * i) }
    var o = QuestOverlayLayout.headerBytes
    for i in 0..<donuts {
      donutQuestId[i] = b.leI32(o)
      donutState[i] = b.le8(o + 4)
      donutKind[i] = b.le8(o + 5)
      donutFlash[i] = b.le8(o + 6)
      donutFlags[i] = b.le8(o + 7)
      for a in 0..<3 {
        donutStandpoint[3 * i + a] = b.leF32(o + 8 + 4 * a)
        donutAim[3 * i + a] = b.leF32(o + 20 + 4 * a)
      }
      donutPositionTolerance[i] = b.leF32(o + 32)
      donutInnerCircleDegrees[i] = b.leF32(o + 36)
      donutAngleToleranceDegrees[i] = b.leF32(o + 40)
      donutRingRadius[i] = b.leF32(o + 44)
      o += QuestOverlayLayout.donutBytes
    }
    for i in 0..<vertices {
      for a in 0..<3 { trailPoint[3 * i + a] = b.leF32(o + 4 * a) }
      trailIndex[i] = b.le16(o + 12)
      trailStationState[i] = b.le8(o + 14)
      trailFlags[i] = b.le8(o + 15)
      o += QuestOverlayLayout.trailVertexBytes
    }
    for i in 0..<footprints {
      for a in 0..<3 { footprint[3 * i + a] = b.leF32(o + 4 * a) }
      footprintYaw[i] = b.leF32(o + 12)
      o += QuestOverlayLayout.footprintBytes
    }
    for i in 0..<zoneVertices {
      zoneVertexXz[2 * i] = b.leF32(o)
      zoneVertexXz[2 * i + 1] = b.leF32(o + 4)
      o += QuestOverlayLayout.zoneVertexBytes
    }
    donutCount = donuts
    trailVertexCount = vertices
    footprintCount = footprints
    zoneVertexCount = zoneVertices
    return .ok
  }

  /// Whether a point is inside the scan zone (or no zone is set); the
  /// renderer greys surfaces outside when the grey-outside layer is set.
  func isInScope(_ x: Float, _ y: Float, _ z: Float) -> Bool {
    switch zoneKind {
    case QuestOverlayLayout.zoneSpace:
      if y < zoneParams[0] || y > zoneParams[1] { return false }
      var inside = false
      let n = zoneVertexCount
      var j = n - 1
      for i in 0..<n {
        let xi = zoneVertexXz[2 * i], zi = zoneVertexXz[2 * i + 1]
        let xj = zoneVertexXz[2 * j], zj = zoneVertexXz[2 * j + 1]
        if (zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi {
          inside.toggle()
        }
        j = i
      }
      return inside
    case QuestOverlayLayout.zoneObject:
      return abs(x - zoneParams[0]) <= zoneParams[3] &&
        abs(y - zoneParams[1]) <= zoneParams[4] &&
        abs(z - zoneParams[2]) <= zoneParams[5]
    default:
      return true
    }
  }
}
