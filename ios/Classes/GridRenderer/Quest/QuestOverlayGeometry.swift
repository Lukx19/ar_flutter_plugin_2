import Foundation

/// Turns a [QuestOverlayFrame] into line segments, following the guided
/// capture design ("Donuts, reward and compass"), as the Android
/// `QuestOverlayGeometry`:
///
/// - A donut is a thin ring (three concentric loops) of the message's world
///   radius at the standpoint, facing the aim point; its on-screen diameter
///   is clamped to the message's dp range using the focal length and
///   density from [setView].
/// - Within twice the position tolerance the user is at the standpoint: the
///   donut frames the aim point instead, around a dashed aim reticle whose
///   radius is the inner circle as seen from the camera.
/// - Shown donuts are amber, aimed green, the next trail start cyan;
///   captured donuts flash white three times, timed-out donuts fade out.
/// - Trails are solid where captured and dashed elsewhere; footprints are
///   two foot-sized soles on the floor at the standpoint.
///
/// Uses only fields, precomputed tables and the caller's sink; nothing is
/// allocated per frame.
final class QuestOverlayGeometry {
  static let ringSegments = 32
  static let farSegments = 16
  /// Beyond this camera distance a donut is drawn with [farSegments].
  static let farMetres: Float = 3
  static let defaultRingRadiusMetres: Float = 0.08
  static let defaultRingMinDiameterDp = 24
  static let defaultRingMaxDiameterDp = 96
  static let defaultInnerCircleDegrees: Float = 4
  /// Loop radii of the ring band as fractions of the outer radius.
  static let ringLoops: [Float] = [0.8, 0.9, 1]
  /// Near: the aim ring's outer radius in reticle radii.
  static let aimRingReticleRatio: Float = 1.5
  /// Trail parts still to capture: one dash per this length, at most [maxDashes] per segment.
  static let trailDashMetres: Float = 0.1
  static let maxDashes = 8

  static let colourShown: UInt32 = 0xFFB3_00FF
  static let colourAimed: UInt32 = 0x43A0_47FF
  static let colourFlash: UInt32 = 0xFFFF_FFFF
  static let colourTimedOut: UInt32 = 0xFFB3_00FF
  static let colourTrailStart: UInt32 = 0x26C6_DAFF
  static let colourTrailPending: UInt32 = 0xFFFF_FF99
  static let colourTrailActive: UInt32 = 0xFFFF_FFFF
  static let colourTrailCaptured: UInt32 = 0x43A0_47FF
  static let colourFootprint: UInt32 = 0xFFB3_00CC
  static let colourZone: UInt32 = 0x90A4_AEFF

  /// Half sole length and width; sole centres [footSpacing] either side of the standpoint.
  static let footHalfLength: Float = 0.13
  static let footHalfWidth: Float = 0.05
  static let footSpacing: Float = 0.1
  /// Sole outline as (forward, side) pairs in half-extent units, a closed loop.
  static let footOutline: [Float] = [
    1, 0, 0.8, 0.75, 0.3, 1, -0.4, 0.8, -0.9, 0.6,
    -1, 0, -0.9, -0.6, -0.4, -0.8, 0.3, -1, 0.8, -0.75,
  ]

  /// Alpha of a timed-out donut in fade phase 0-5.
  static func fadeAlpha(_ phase: Int) -> UInt32 { UInt32(255 * (6 - min(max(phase, 0), 5)) / 7) }

  private static let cosTable: [Float] = (0...ringSegments).map { Float(cos(2.0 * Double.pi * Double($0) / Double(ringSegments))) }
  private static let sinTable: [Float] = (0...ringSegments).map { Float(sin(2.0 * Double.pi * Double($0) / Double(ringSegments))) }

  private var u: [Float] = [0, 0, 0]
  private var v: [Float] = [0, 0, 0]
  /// Projection focal length in pixels; 0 disables the screen clamp.
  private var focalPx: Float = 0
  private var pxPerDp: Float = 1
  private(set) var lines = 0
  private var target: GridOverlayLineSink?

  /// Render thread: this frame's projection focal length in pixels and the display density.
  func setView(focalPx: Float, pxPerDp: Float) {
    self.focalPx = focalPx
    self.pxPerDp = pxPerDp
  }

  /// Emits every visible element of [frame]; returns the segment count.
  func build(_ frame: QuestOverlayFrame, camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink) -> Int {
    lines = 0
    target = sink
    defer { target = nil }
    if frame.hasLayer(QuestOverlayLayout.layerDonuts) || frame.hasLayer(QuestOverlayLayout.layerTrails) {
      for i in 0..<frame.donutCount { donut(frame, i, camX, camY, camZ) }
    }
    if frame.hasLayer(QuestOverlayLayout.layerTrails) { trails(frame) }
    if frame.hasLayer(QuestOverlayLayout.layerFootprints) {
      for i in 0..<frame.footprintCount { footprint(frame, i) }
    }
    if frame.hasLayer(QuestOverlayLayout.layerZoneOutline) { zone(frame) }
    return lines
  }

  @inline(__always)
  private func emit(_ x0: Float, _ y0: Float, _ z0: Float, _ x1: Float, _ y1: Float, _ z1: Float, _ rgba: UInt32) {
    lines += 1
    target?.line(x0, y0, z0, x1, y1, z1, rgba: rgba)
  }

  /// Colour of donut [i] this frame, or 0 when it is not drawn.
  func donutColour(_ f: QuestOverlayFrame, _ i: Int) -> UInt32 {
    let flash = f.donutFlash[i]
    switch f.donutState[i] {
    case QuestOverlayLayout.stateCaptured:
      // Three flashes: even phases lit, odd phases dark, then gone.
      return (flash == QuestOverlayLayout.flashNone || flash % 2 == 1) ? 0 : QuestOverlayGeometry.colourFlash
    case QuestOverlayLayout.stateTimedOut:
      return flash == QuestOverlayLayout.flashNone
        ? 0 : (QuestOverlayGeometry.colourTimedOut & 0xFFFF_FF00) | QuestOverlayGeometry.fadeAlpha(flash)
    default:
      if f.donutFlags[i] & QuestOverlayLayout.donutFlagTrailStart != 0 { return QuestOverlayGeometry.colourTrailStart }
      if f.donutFlags[i] & QuestOverlayLayout.donutFlagAimed != 0 { return QuestOverlayGeometry.colourAimed }
      return QuestOverlayGeometry.colourShown
    }
  }

  /// World radius at [distance] whose projection is [radius]'s, clamped to
  /// [minPx, maxPx] pixels; unclamped until [setView] gave a focal length.
  func clampRadius(_ radius: Float, distance: Float, minPx: Float, maxPx: Float) -> Float {
    if focalPx <= 0 || distance <= 1e-4 { return radius }
    let px = min(max(radius * focalPx / distance, minPx), maxPx)
    return px * distance / focalPx
  }

  private func minRingPx(_ f: QuestOverlayFrame) -> Float {
    0.5 * pxPerDp * Float(f.ringMinDiameterDp > 0 ? f.ringMinDiameterDp : QuestOverlayGeometry.defaultRingMinDiameterDp)
  }

  private func maxRingPx(_ f: QuestOverlayFrame) -> Float {
    0.5 * pxPerDp * Float(f.ringMaxDiameterDp > 0 ? f.ringMaxDiameterDp : QuestOverlayGeometry.defaultRingMaxDiameterDp)
  }

  private func donut(_ f: QuestOverlayFrame, _ i: Int, _ camX: Float, _ camY: Float, _ camZ: Float) {
    let colour = donutColour(f, i)
    if colour == 0 { return }
    let sx = f.donutStandpoint[3 * i], sy = f.donutStandpoint[3 * i + 1], sz = f.donutStandpoint[3 * i + 2]
    let ax = f.donutAim[3 * i], ay = f.donutAim[3 * i + 1], az = f.donutAim[3 * i + 2]
    if !basis(ax - sx, ay - sy, az - sz) { return }
    let camDistance = distance(camX, camY, camZ, sx, sy, sz)
    // Keep the lens target fixed. The shared HUD owns the near-lens sight.
    if camDistance < 0.25 { return }
    let given = f.donutRingRadius[i]
    let radius = given > 0 ? given : QuestOverlayGeometry.defaultRingRadiusMetres
    let outer = clampRadius(radius, distance: camDistance, minPx: minRingPx(f), maxPx: maxRingPx(f))
    ring(sx, sy, sz, outer,
         camDistance > QuestOverlayGeometry.farMetres ? QuestOverlayGeometry.farSegments : QuestOverlayGeometry.ringSegments,
         colour)
  }

  private func ring(_ cx: Float, _ cy: Float, _ cz: Float, _ outer: Float, _ segments: Int, _ colour: UInt32) {
    for loop in QuestOverlayGeometry.ringLoops { circle(cx, cy, cz, outer * loop, segments, colour) }
  }

  /// Aim reticle: a circle of [ringSegments] / 2 dashes.
  private func dashedCircle(_ cx: Float, _ cy: Float, _ cz: Float, _ r: Float, _ colour: UInt32) {
    let c = QuestOverlayGeometry.cosTable, s = QuestOverlayGeometry.sinTable
    var k = 0
    while k < QuestOverlayGeometry.ringSegments {
      emit(
        cx + (u[0] * c[k] + v[0] * s[k]) * r,
        cy + (u[1] * c[k] + v[1] * s[k]) * r,
        cz + (u[2] * c[k] + v[2] * s[k]) * r,
        cx + (u[0] * c[k + 1] + v[0] * s[k + 1]) * r,
        cy + (u[1] * c[k + 1] + v[1] * s[k + 1]) * r,
        cz + (u[2] * c[k + 1] + v[2] * s[k + 1]) * r,
        colour)
      k += 2
    }
  }

  private func trails(_ f: QuestOverlayFrame) {
    if f.trailVertexCount < 2 { return }
    for i in 0..<(f.trailVertexCount - 1) {
      if f.trailIndex[i] != f.trailIndex[i + 1] { continue }
      let flags = f.trailFlags[i]
      let captured = f.trailStationState[i] == QuestOverlayLayout.stationCaptured &&
        f.trailStationState[i + 1] == QuestOverlayLayout.stationCaptured
      let solid = captured || flags & QuestOverlayLayout.trailFlagCompleted != 0
      let colour: UInt32
      if solid {
        colour = QuestOverlayGeometry.colourTrailCaptured
      } else if flags & QuestOverlayLayout.trailFlagActive != 0 {
        colour = QuestOverlayGeometry.colourTrailActive
      } else {
        colour = QuestOverlayGeometry.colourTrailPending
      }
      let x0 = f.trailPoint[3 * i], y0 = f.trailPoint[3 * i + 1], z0 = f.trailPoint[3 * i + 2]
      let dx = f.trailPoint[3 * i + 3] - x0, dy = f.trailPoint[3 * i + 4] - y0, dz = f.trailPoint[3 * i + 5] - z0
      if solid {
        emit(x0, y0, z0, x0 + dx, y0 + dy, z0 + dz, colour)
        continue
      }
      // Still to capture: dashed, the first half of each dash period drawn.
      let length = (dx * dx + dy * dy + dz * dz).squareRoot()
      let dashes = min(max(Int(length / QuestOverlayGeometry.trailDashMetres), 1), QuestOverlayGeometry.maxDashes)
      for k in 0..<dashes {
        let t0 = Float(k) / Float(dashes)
        let t1 = (Float(k) + 0.5) / Float(dashes)
        emit(x0 + dx * t0, y0 + dy * t0, z0 + dz * t0, x0 + dx * t1, y0 + dy * t1, z0 + dz * t1, colour)
      }
    }
  }

  private func footprint(_ f: QuestOverlayFrame, _ i: Int) {
    let x = f.footprint[3 * i], y = f.footprint[3 * i + 1], z = f.footprint[3 * i + 2]
    let yaw = f.footprintYaw[i]
    let fx = cos(yaw), fz = sin(yaw)
    // Side axis, perpendicular to the facing direction on the floor.
    let rx = -fz, rz = fx
    for side in 0..<2 {
      let s = side == 0 ? -QuestOverlayGeometry.footSpacing : QuestOverlayGeometry.footSpacing
      sole(x + rx * s, y, z + rz * s, fx, fz, rx, rz)
    }
  }

  /// One sole outline centred at (cx, cz) facing (fx, fz) with side axis (rx, rz).
  private func sole(_ cx: Float, _ y: Float, _ cz: Float, _ fx: Float, _ fz: Float, _ rx: Float, _ rz: Float) {
    let outline = QuestOverlayGeometry.footOutline
    let n = outline.count / 2
    let hl = QuestOverlayGeometry.footHalfLength, hw = QuestOverlayGeometry.footHalfWidth
    for k in 0..<n {
      let j = (k + 1) % n
      let a = outline[2 * k] * hl, b = outline[2 * k + 1] * hw
      let c = outline[2 * j] * hl, d = outline[2 * j + 1] * hw
      emit(cx + fx * a + rx * b, y, cz + fz * a + rz * b, cx + fx * c + rx * d, y, cz + fz * c + rz * d,
           QuestOverlayGeometry.colourFootprint)
    }
  }

  private func zone(_ f: QuestOverlayFrame) {
    switch f.zoneKind {
    case QuestOverlayLayout.zoneSpace:
      let y = f.zoneParams[0]
      let n = f.zoneVertexCount
      for i in 0..<n {
        let j = (i + 1) % n
        emit(f.zoneVertexXz[2 * i], y, f.zoneVertexXz[2 * i + 1], f.zoneVertexXz[2 * j], y, f.zoneVertexXz[2 * j + 1],
             QuestOverlayGeometry.colourZone)
      }
    case QuestOverlayLayout.zoneObject:
      let p = f.zoneParams
      for e in 0..<12 {
        // Box edges: 4 along each axis.
        let axis = e / 4
        let s1: Float = e % 2 == 0 ? -1 : 1
        let s2: Float = (e / 2) % 2 == 0 ? -1 : 1
        let a1 = (axis + 1) % 3
        let a2 = (axis + 2) % 3
        u[axis] = p[axis] - p[3 + axis]
        v[axis] = p[axis] + p[3 + axis]
        u[a1] = p[a1] + s1 * p[3 + a1]
        v[a1] = u[a1]
        u[a2] = p[a2] + s2 * p[3 + a2]
        v[a2] = u[a2]
        emit(u[0], u[1], u[2], v[0], v[1], v[2], QuestOverlayGeometry.colourZone)
      }
    default:
      break
    }
  }

  /// Orthonormal u, v perpendicular to the direction (dx, dy, dz).
  private func basis(_ dx: Float, _ dy: Float, _ dz: Float) -> Bool {
    let l = (dx * dx + dy * dy + dz * dz).squareRoot()
    if l < 1e-6 { return false }
    let nx = dx / l, ny = dy / l, nz = dz / l
    // Pick the world axis least parallel to n.
    var hx: Float = 0, hy: Float = 1
    let hz: Float = 0
    if abs(ny) > 0.9 {
      hx = 1
      hy = 0
    }
    // u = normalize(h x n), v = n x u.
    var ux = hy * nz - hz * ny
    var uy = hz * nx - hx * nz
    var uz = hx * ny - hy * nx
    let ul = (ux * ux + uy * uy + uz * uz).squareRoot()
    ux /= ul; uy /= ul; uz /= ul
    u[0] = ux; u[1] = uy; u[2] = uz
    v[0] = ny * uz - nz * uy
    v[1] = nz * ux - nx * uz
    v[2] = nx * uy - ny * ux
    return true
  }

  private func circle(_ cx: Float, _ cy: Float, _ cz: Float, _ r: Float, _ segments: Int, _ colour: UInt32) {
    let c = QuestOverlayGeometry.cosTable, s = QuestOverlayGeometry.sinTable
    let step = QuestOverlayGeometry.ringSegments / segments
    var px = cx + u[0] * r, py = cy + u[1] * r, pz = cz + u[2] * r
    var k = step
    while k <= QuestOverlayGeometry.ringSegments {
      let cr = c[k] * r, sr = s[k] * r
      let qx = cx + u[0] * cr + v[0] * sr
      let qy = cy + u[1] * cr + v[1] * sr
      let qz = cz + u[2] * cr + v[2] * sr
      emit(px, py, pz, qx, qy, qz, colour)
      px = qx; py = qy; pz = qz
      k += step
    }
  }

  private func distance(_ ax: Float, _ ay: Float, _ az: Float, _ bx: Float, _ by: Float, _ bz: Float) -> Float {
    let dx = ax - bx, dy = ay - by, dz = az - bz
    return (dx * dx + dy * dy + dz * dz).squareRoot()
  }
}
