import Foundation

/// Anchor operations the tracker needs, so it runs in XCTests with a fake
/// backend. The ARKit implementation is `ARKitAnchorBackend`. Every call
/// happens on the AR session delegate queue (except a final detach on close).
protocol AnchorBackend: AnyObject {
  /// Creates an anchor at [tq] (t xyz, q xyzw) in [session]; nil when refused.
  func create(session: AnyObject, tq: UnsafePointer<Float>) -> AnyObject?
  func detach(_ anchor: AnyObject, session: AnyObject?)
  /// Stable identity of an anchor across frames (ARKit hands out new
  /// `ARAnchor` copies per frame with the same identifier).
  func key(of anchor: AnyObject) -> AnyHashable
  /// One of [AnchorTracking] tracking, paused, stopped.
  func tracking(_ anchor: AnyObject) -> Int
  /// Writes t xyz, q xyzw of [anchor] to `out[0, 7)`.
  func readPose(_ anchor: AnyObject, into out: UnsafeMutablePointer<Float>)
}

/// The parts of one AR frame the tracker reads.
protocol AnchorFrame {
  var timestampNs: Int64 { get }
  /// One of [IngestTrackingState].
  var cameraTracking: Int { get }
  /// Writes the sensor-oriented camera pose as t xyz, q xyzw.
  func readCameraPose(into out: UnsafeMutablePointer<Float>)
  /// Loads the anchors of this frame and returns their number.
  func loadUpdatedAnchors() -> Int
  func updatedAnchor(_ index: Int) -> AnyObject
}

/// Anchor pose tracker of the pose tracking channel (pose stability, plan
/// 2.14), the Swift twin of the Android `AnchorPoseTracker`: the anchors this
/// tracker created, their poses, what was last sent to the grid isolate, the
/// creation queue and the creation results.
///
/// Threads: [onFrame] runs on the AR delegate queue, [handleQuery] on the
/// channel's background task queue, [enqueuePhoto] on the capture thread; all
/// share [lock]. ARKit anchor calls happen only on the AR delegate queue,
/// except the final detach in [close].
///
/// Poses are kept as t xyz, q xyzw (7 floats); rotation differences are
/// measured on the quaternions (|q_a - +-q_b|^2 = 4 sin^2(angle / 4)). Update
/// records carry rows 0-2 of the 4x4 built from the quaternion.
final class AnchorPoseTracker {
  static let queryMalformed = -1
  static let defaultCapacity = 2048
  static let queueCapacity = 128
  static let activeTimeoutNs: Int64 = 10_000_000_000
  static let updateMoveM = 0.001
  static let updateTurnDeg = 0.05
  /// A tracking anchor moving more than 5 cm within one frame was moved by a map correction.
  static let jumpMoveM = 0.05
  static let stableMoveM = 0.005
  static let stableTurnDeg = 0.2
  private static let poseStride = 7
  private static let none: Int8 = -1
  private static let maxPhotoId = AnchorIds.firstKeyframeId - 1

  static func turnDistanceSquared(_ degrees: Double) -> Double {
    let s = sin(degrees * .pi / 180 / 4)
    return 4 * s * s
  }

  private static let updateTurnD2 = turnDistanceSquared(updateTurnDeg)
  private static let stableTurnD2 = turnDistanceSquared(stableTurnDeg)

  /// Rows 0-2 of the 4x4 of [tq] (`r00 r01 r02 tx`, ...) when [rows], else
  /// the column-major 4x4, written to [out].
  static func poseMatrix(_ tq: UnsafePointer<Float>, _ out: UnsafeMutablePointer<Float>, rows: Bool) {
    let tx = Double(tq[0]), ty = Double(tq[1]), tz = Double(tq[2])
    let qx = Double(tq[3]), qy = Double(tq[4]), qz = Double(tq[5]), qw = Double(tq[6])
    let xx = qx * qx, yy = qy * qy, zz = qz * qz
    let xy = qx * qy, xz = qx * qz, yz = qy * qz
    let xw = qx * qw, yw = qy * qw, zw = qz * qw
    let r00 = 1 - 2 * (yy + zz), r01 = 2 * (xy - zw), r02 = 2 * (xz + yw)
    let r10 = 2 * (xy + zw), r11 = 1 - 2 * (xx + zz), r12 = 2 * (yz - xw)
    let r20 = 2 * (xz - yw), r21 = 2 * (yz + xw), r22 = 1 - 2 * (xx + yy)
    let values: [Double]
    if rows {
      values = [r00, r01, r02, tx, r10, r11, r12, ty, r20, r21, r22, tz]
    } else {
      values = [r00, r10, r20, 0, r01, r11, r21, 0, r02, r12, r22, 0, tx, ty, tz, 1]
    }
    for (i, v) in values.enumerated() { out[i] = Float(v) }
  }

  /// Camera-to-world rows 0-2 (12 floats) to t xyz, q xyzw; false when the
  /// rotation is not proper (for example the zero rows of an unposed photo).
  static func rowsToPose(_ m: UnsafePointer<Float>, _ out: UnsafeMutablePointer<Float>) -> Bool {
    let r00 = Double(m[0]), r01 = Double(m[1]), r02 = Double(m[2])
    let r10 = Double(m[4]), r11 = Double(m[5]), r12 = Double(m[6])
    let r20 = Double(m[8]), r21 = Double(m[9]), r22 = Double(m[10])
    let det = r00 * (r11 * r22 - r12 * r21) - r01 * (r10 * r22 - r12 * r20) + r02 * (r10 * r21 - r11 * r20)
    guard det > 0.9 && det < 1.1 else { return false }
    let trace = r00 + r11 + r22
    var qx: Double, qy: Double, qz: Double, qw: Double
    if trace > 0 {
      let s = (trace + 1).squareRoot() * 2
      qw = 0.25 * s; qx = (r21 - r12) / s; qy = (r02 - r20) / s; qz = (r10 - r01) / s
    } else if r00 > r11 && r00 > r22 {
      let s = (1 + r00 - r11 - r22).squareRoot() * 2
      qw = (r21 - r12) / s; qx = 0.25 * s; qy = (r01 + r10) / s; qz = (r02 + r20) / s
    } else if r11 > r22 {
      let s = (1 + r11 - r00 - r22).squareRoot() * 2
      qw = (r02 - r20) / s; qx = (r01 + r10) / s; qy = 0.25 * s; qz = (r12 + r21) / s
    } else {
      let s = (1 + r22 - r00 - r11).squareRoot() * 2
      qw = (r10 - r01) / s; qx = (r02 + r20) / s; qy = (r12 + r21) / s; qz = 0.25 * s
    }
    let n = (qx * qx + qy * qy + qz * qz + qw * qw).squareRoot()
    guard n > 0 else { return false }
    out[0] = m[3]; out[1] = m[7]; out[2] = m[11]
    out[3] = Float(qx / n); out[4] = Float(qy / n); out[5] = Float(qz / n); out[6] = Float(qw / n)
    return true
  }

  private static func moved(_ a: UnsafePointer<Float>, _ b: UnsafePointer<Float>, move: Double, turnD2: Double) -> Bool {
    let dx = Double(a[0] - b[0]), dy = Double(a[1] - b[1]), dz = Double(a[2] - b[2])
    if dx * dx + dy * dy + dz * dz > move * move { return true }
    var dot = 0.0
    for k in 3..<7 { dot += Double(a[k]) * Double(b[k]) }
    let s = dot < 0 ? -1.0 : 1.0
    var d2 = 0.0
    for k in 3..<7 {
      let d = Double(a[k]) - s * Double(b[k])
      d2 += d * d
    }
    return d2 > turnD2
  }

  let lock = NSLock()
  private let backend: AnchorBackend
  private let clock: IngestClock
  private let readTimer: IngestClock

  /// Told of anchor jumps, outside the lock.
  var jumpListener: ((Int64, Int) -> Void)?

  /// True while tracking is active or detaches are pending; the frame path returns after reading it when false.
  private var armedFlag = false
  var armed: Bool { lock.lock(); defer { lock.unlock() }; return armedFlag }

  private var closed = false
  private var active = false
  private var lastQueryNs: Int64 = 0
  private var anchorLimit = PoseQueryLayout.defaultAnchorLimit
  private var stableNs = Int64(PoseQueryLayout.defaultDetachAfterSeconds) * 1_000_000_000
  private weak var lastSession: AnyObject?
  private var hadSession = false
  private var lastEpoch: Int?

  // Latest AR frame.
  private var frameNs: Int64 = 0
  private var previousFrameNs: Int64 = 0
  private var cameraTracking = IngestTrackingState.stopped
  private var cameraTq: [Float] = [0, 0, 0, 0, 0, 0, 1]
  private var cameraMatrix = [Float](repeating: 0, count: 16)
  private var rowScratch = [Float](repeating: 0, count: 12)
  private var previousTq = [Float](repeating: 0, count: poseStride)
  private var requestTq = [Float](repeating: 0, count: poseStride)
  private var maxReadNs: Int64 = 0
  private var maxIntervalNs: Int64 = 0
  private var frameJumpM = 0.0

  // Anchor table, dense slots [0, count).
  private var capacity: Int
  private var ids: [UInt32]
  private var kinds: [UInt8]
  private var subjects: [Int64]
  private var captureNs: [Int64]
  private var createdNs: [Int64]
  private var anchors: [AnyObject?]
  private var anchorKeys: [AnyHashable?]
  private var tracking: [UInt8]
  private var sentTracking: [Int8]
  private var current: [Float]
  private var sent: [Float]
  private var reference: [Float]
  private var stableSinceNs: [Int64]
  private var dirty: [Bool]
  private var final: [Bool]
  private var count = 0
  private var attached = 0
  private var dirtyCount = 0
  private var cursor = 0
  private var idIndex: [UInt32: Int] = [:]
  private var keyIndex: [AnyHashable: Int] = [:]

  // Creation queue and results (FIFO).
  private struct Request {
    var id: UInt32, kind: Int, status: Int, subject: Int64, captureNs: Int64, tq: [Float]
  }
  private struct Result {
    var id: UInt32, kind: Int, status: Int, subject: Int64, captureNs: Int64, frameNs: Int64
  }
  private var requests: [Request] = []
  private var requestHead = 0
  private var results: [Result] = []
  private var resultHead = 0

  // Anchors waiting for a detach on the AR queue.
  private var pendingDetach: [AnyObject] = []
  private var nextPhotoId: UInt32 = 1

  // Counters.
  private(set) var created = 0
  private(set) var failed = 0
  private(set) var overLimit = 0
  private(set) var notTracking = 0
  private(set) var detachedByLimit = 0
  private(set) var updatesSent = 0
  private(set) var repliesSent = 0
  private(set) var malformedQueries = 0
  private(set) var deactivations = 0
  private(set) var sessionReplacements = 0
  private(set) var anchorJumps = 0
  private(set) var tableGrowth = 0

  init(backend: AnchorBackend, clock: IngestClock, readTimer: IngestClock, initialCapacity: Int = AnchorPoseTracker.defaultCapacity) {
    self.backend = backend
    self.clock = clock
    self.readTimer = readTimer
    capacity = max(1, initialCapacity)
    ids = [UInt32](repeating: 0, count: capacity)
    kinds = [UInt8](repeating: 0, count: capacity)
    subjects = [Int64](repeating: 0, count: capacity)
    captureNs = [Int64](repeating: 0, count: capacity)
    createdNs = [Int64](repeating: 0, count: capacity)
    anchors = [AnyObject?](repeating: nil, count: capacity)
    anchorKeys = [AnyHashable?](repeating: nil, count: capacity)
    tracking = [UInt8](repeating: 0, count: capacity)
    sentTracking = [Int8](repeating: 0, count: capacity)
    current = [Float](repeating: 0, count: capacity * Self.poseStride)
    sent = [Float](repeating: 0, count: capacity * Self.poseStride)
    reference = [Float](repeating: 0, count: capacity * Self.poseStride)
    stableSinceNs = [Int64](repeating: 0, count: capacity)
    dirty = [Bool](repeating: false, count: capacity)
    final = [Bool](repeating: false, count: capacity)
    idIndex.reserveCapacity(capacity)
    keyIndex.reserveCapacity(capacity)
    requests.reserveCapacity(Self.queueCapacity)
    results.reserveCapacity(Self.queueCapacity)
  }

  // MARK: AR delegate queue

  /// Once per frame with the frame's session.
  func onFrame(session: AnyObject, frame: AnchorFrame) {
    lock.lock()
    guard armedFlag else { lock.unlock(); return }
    let start = readTimer.nowNs()
    if hadSession && lastSession !== session {
      if count > 0 || !pendingDetach.isEmpty {
        sessionReplacements += 1
        detachAllLocked()
        abandonRequestsLocked()
      }
      previousFrameNs = 0
    }
    lastSession = session
    hadSession = true
    if active && clock.nowNs() - lastQueryNs >= Self.activeTimeoutNs { deactivateLocked() }
    drainDetachLocked(session: session)
    if !active || closed {
      armedFlag = false
      lock.unlock()
      return
    }
    let ts = frame.timestampNs
    if previousFrameNs != 0 { maxIntervalNs = max(maxIntervalNs, ts - previousFrameNs) }
    previousFrameNs = ts
    frameNs = ts
    cameraTracking = frame.cameraTracking
    cameraTq.withUnsafeMutableBufferPointer { frame.readCameraPose(into: $0.baseAddress!) }
    processRequestsLocked(session: session, ts: ts)
    while attached > anchorLimit && detachOneStableLocked(ts: ts, session: session) {}
    frameJumpM = 0
    readUpdatedLocked(frame, ts: ts)
    let jumpM = frameJumpM
    if jumpM > 0 { anchorJumps += 1 }
    maxReadNs = max(maxReadNs, readTimer.nowNs() - start)
    lock.unlock()
    if jumpM > 0 { jumpListener?(ts, Int(jumpM * 1000)) }
  }

  /// The frame source (re)started at [epoch]. A run with `.resetTracking`
  /// keeps the same ARSession but moves the world origin, so an epoch change
  /// ends every anchor of the previous epoch: each is detached on the next
  /// frame and reported once more as a final record with stopped tracking,
  /// and queued creations answer not tracking.
  func onSessionStarted(epoch: Int) {
    lock.lock(); defer { lock.unlock() }
    let previous = lastEpoch
    lastEpoch = epoch
    guard let previous, previous != epoch, !closed else { return }
    if attached > 0 || requestHead < requests.count {
      sessionReplacements += 1
      endAllAnchorsLocked()
      abandonRequestsLocked()
    }
    previousFrameNs = 0
  }

  /// Hands every attached anchor to the AR queue for detaching and marks its
  /// slot final with stopped tracking, so the next reply reports it once.
  private func endAllAnchorsLocked() {
    for s in 0..<count {
      guard let a = anchors[s] else { continue }
      pendingDetach.append(a)
      if let key = anchorKeys[s] { keyIndex[key] = nil }
      anchors[s] = nil
      anchorKeys[s] = nil
      final[s] = true
      tracking[s] = UInt8(AnchorTracking.stopped)
      if !dirty[s] {
        dirty[s] = true
        dirtyCount += 1
      }
    }
    attached = 0
    armedFlag = !closed && (active || !pendingDetach.isEmpty)
  }

  private func processRequestsLocked(session: AnyObject, ts: Int64) {
    while requestHead < requests.count {
      let r = requests[requestHead]
      requestHead += 1
      var status = r.status
      if status < 0 { status = createLocked(session: session, request: r, ts: ts) }
      pushResultLocked(id: r.id, kind: r.kind, status: status, subject: r.subject, captureNs: r.captureNs, ts: ts)
    }
    requests.removeAll(keepingCapacity: true)
    requestHead = 0
  }

  private func createLocked(session: AnyObject, request r: Request, ts: Int64) -> Int {
    if cameraTracking != IngestTrackingState.tracking {
      notTracking += 1
      return AnchorCreation.notTracking
    }
    if idIndex[r.id] != nil {
      failed += 1
      return AnchorCreation.failed
    }
    if attached >= anchorLimit && !detachOneStableLocked(ts: ts, session: session) {
      overLimit += 1
      return AnchorCreation.overLimit
    }
    guard let anchor = r.tq.withUnsafeBufferPointer({ backend.create(session: session, tq: $0.baseAddress!) }) else {
      failed += 1
      return AnchorCreation.failed
    }
    if count == capacity { growTableLocked() }
    let s = count
    count += 1
    ids[s] = r.id
    kinds[s] = UInt8(r.kind)
    subjects[s] = r.subject
    captureNs[s] = r.captureNs
    createdNs[s] = ts
    anchors[s] = anchor
    let key = backend.key(of: anchor)
    anchorKeys[s] = key
    final[s] = false
    sentTracking[s] = Self.none
    let o = s * Self.poseStride
    current.withUnsafeMutableBufferPointer { backend.readPose(anchor, into: $0.baseAddress! + o) }
    tracking[s] = UInt8(backend.tracking(anchor))
    for k in 0..<Self.poseStride {
      sent[o + k] = current[o + k]
      reference[o + k] = current[o + k]
    }
    stableSinceNs[s] = ts
    dirty[s] = true
    dirtyCount += 1
    idIndex[r.id] = s
    keyIndex[key] = s
    attached += 1
    created += 1
    return AnchorCreation.created
  }

  /// Detaches the oldest attached non-submap anchor stable for [stableNs]; false when none qualifies.
  private func detachOneStableLocked(ts: Int64, session: AnyObject?) -> Bool {
    var best = -1
    var bestCreated = Int64.max
    for s in 0..<count {
      if final[s] || anchors[s] == nil { continue }
      // Submap anchors carry the grid's map segments; never detached by the limit.
      if Int(kinds[s]) == AnchorKind.submap { continue }
      if ts - stableSinceNs[s] < stableNs { continue }
      if createdNs[s] < bestCreated {
        bestCreated = createdNs[s]
        best = s
      }
    }
    if best < 0 { return false }
    if let key = anchorKeys[best] { keyIndex[key] = nil }
    backend.detach(anchors[best]!, session: session)
    anchors[best] = nil
    final[best] = true
    tracking[best] = UInt8(AnchorTracking.detachedByLimit)
    if !dirty[best] {
      dirty[best] = true
      dirtyCount += 1
    }
    attached -= 1
    detachedByLimit += 1
    return true
  }

  private func readUpdatedLocked(_ frame: AnchorFrame, ts: Int64) {
    if attached == 0 { return }
    let n = frame.loadUpdatedAnchors()
    for i in 0..<n {
      let a = frame.updatedAnchor(i)
      guard let s = keyIndex[backend.key(of: a)], anchors[s] != nil else { continue }
      let o = s * Self.poseStride
      let state = backend.tracking(a)
      for k in 0..<Self.poseStride { previousTq[k] = current[o + k] }
      let wasTracking = Int(tracking[s]) == AnchorTracking.tracking
      current.withUnsafeMutableBufferPointer { backend.readPose(a, into: $0.baseAddress! + o) }
      tracking[s] = UInt8(state)
      if wasTracking && state == AnchorTracking.tracking {
        let dx = Double(current[o] - previousTq[0])
        let dy = Double(current[o + 1] - previousTq[1])
        let dz = Double(current[o + 2] - previousTq[2])
        let d = (dx * dx + dy * dy + dz * dz).squareRoot()
        if d > Self.jumpMoveM && d > frameJumpM { frameJumpM = d }
      }
      let movedSinceSent = current.withUnsafeBufferPointer { c in
        sent.withUnsafeBufferPointer { Self.moved(c.baseAddress! + o, $0.baseAddress! + o, move: Self.updateMoveM, turnD2: Self.updateTurnD2) }
      }
      if !dirty[s] && (state != Int(sentTracking[s]) || movedSinceSent) {
        dirty[s] = true
        dirtyCount += 1
      }
      if state == AnchorTracking.tracking {
        let unstable = current.withUnsafeBufferPointer { c in
          reference.withUnsafeBufferPointer { Self.moved(c.baseAddress! + o, $0.baseAddress! + o, move: Self.stableMoveM, turnD2: Self.stableTurnD2) }
        }
        if unstable {
          for k in 0..<Self.poseStride { reference[o + k] = current[o + k] }
          stableSinceNs[s] = ts
        }
      }
    }
  }

  private func drainDetachLocked(session: AnyObject?) {
    for a in pendingDetach { backend.detach(a, session: session) }
    pendingDetach.removeAll(keepingCapacity: true)
  }

  // MARK: channel queue

  /// Applies [query] and writes the reply (kind 7 ingest header plus
  /// payload) to [out] from index 0. Returns the reply length, or
  /// [queryMalformed] for a malformed query or a closed tracker.
  func handleQuery(_ query: IngestMessageBuffer?, length: Int, out b: IngestMessageBuffer, sequence: UInt32) -> Int {
    let nowNs = clock.nowNs()
    lock.lock(); defer { lock.unlock() }
    let valid = query.map { Self.validQuery($0, length: length) } ?? false
    guard valid, !closed, let q = query else {
      if !valid { malformedQueries += 1 }
      return Self.queryMalformed
    }
    let flags = q.readU16(PoseQueryLayout.flagsOffset)
    let requestCount = q.readU16(PoseQueryLayout.countOffset)
    let epoch = q.readU16(PoseQueryLayout.groupEpochOffset)
    let limit = Int(q.readI32(PoseQueryLayout.anchorLimitOffset))
    let detachAfter = Int64(q.readU32(PoseQueryLayout.detachAfterOffset))
    if active && nowNs - lastQueryNs >= Self.activeTimeoutNs { deactivateLocked() }
    if flags & PoseQueryLayout.flagDetachAll != 0 {
      detachAllLocked()
      abandonRequestsLocked()
    }
    if flags & PoseQueryLayout.flagTracking == 0 {
      if active { deactivateLocked() }
    } else {
      active = true
      lastQueryNs = nowNs
    }
    anchorLimit = limit == 0 ? PoseQueryLayout.defaultAnchorLimit : max(1, limit)
    stableNs = (detachAfter == 0 ? Int64(PoseQueryLayout.defaultDetachAfterSeconds) : detachAfter) * 1_000_000_000
    for i in 0..<requestCount {
      let o = PoseQueryLayout.headerBytes + i * PoseQueryLayout.requestBytes
      var tq = [Float](repeating: 0, count: Self.poseStride)
      for k in 0..<Self.poseStride { tq[k] = q.readF32(o + PoseQueryLayout.requestPoseOffset + 4 * k) }
      let id = q.readU32(o + PoseQueryLayout.requestAnchorIdOffset)
      let requestFlags = Int(q.readU32(o + PoseQueryLayout.requestFlagsOffset))
      let kind = requestFlags & PoseQueryLayout.requestFlagSubmap != 0 ? AnchorKind.submap : AnchorKind.keyframe
      let subject = q.readI64(o + PoseQueryLayout.requestSubjectOffset)
      let capture = q.readI64(o + PoseQueryLayout.requestCaptureNsOffset)
      if active {
        requests.append(Request(id: id, kind: kind, status: -1, subject: subject, captureNs: capture, tq: tq))
      } else {
        notTracking += 1
        pushResultLocked(id: id, kind: kind, status: AnchorCreation.notTracking, subject: subject, captureNs: capture, ts: frameNs)
      }
    }
    armedFlag = !closed && (active || !pendingDetach.isEmpty)
    return writeReplyLocked(b, sequence: sequence, epoch: epoch, nowNs: nowNs)
  }

  static func validQuery(_ q: IngestMessageBuffer, length: Int) -> Bool {
    guard length >= PoseQueryLayout.headerBytes, q.capacity >= length else { return false }
    guard q.readU32(0) == PoseQueryLayout.magic, q.readU16(PoseQueryLayout.versionOffset) == PoseQueryLayout.version else { return false }
    let n = q.readU16(PoseQueryLayout.countOffset)
    return n <= PoseQueryLayout.maxRequests && length >= PoseQueryLayout.headerBytes + n * PoseQueryLayout.requestBytes
  }

  private func writeReplyLocked(_ b: IngestMessageBuffer, sequence: UInt32, epoch: Int, nowNs: Int64) -> Int {
    IngestMessageWriter.begin(b, kind: IngestMessageLayout.kindAnchorPoseUpdate)
    let payload = IngestMessageLayout.headerBytes
    var updates = 0
    var finalsSent = false
    let n = count
    if dirtyCount > 0 && n > 0 {
      var s = cursor < n ? cursor : 0
      var scanned = 0
      while scanned < n && updates < AnchorPoseUpdateLayout.maxUpdates {
        if dirty[s] {
          writeUpdateLocked(b, payload + AnchorPoseUpdateLayout.prefixBytes + updates * AnchorPoseUpdateLayout.updateBytes, s)
          dirty[s] = false
          dirtyCount -= 1
          if final[s] { finalsSent = true }
          updates += 1
        }
        s += 1
        if s == n { s = 0 }
        scanned += 1
      }
      cursor = s
    }
    let pendingResults = results.count - resultHead
    let creations = min(pendingResults, AnchorPoseUpdateLayout.maxCreations)
    let creationStart = payload + AnchorPoseUpdateLayout.prefixBytes + updates * AnchorPoseUpdateLayout.updateBytes
    for i in 0..<creations {
      let r = results[resultHead]
      resultHead += 1
      let o = creationStart + i * AnchorPoseUpdateLayout.creationBytes
      b.u32(o, r.id)
      b.u8(o + AnchorPoseUpdateLayout.creationKindOffset, r.kind)
      b.u8(o + AnchorPoseUpdateLayout.creationStatusOffset, r.status)
      b.u16(o + 6, 0)
      b.i64(o + AnchorPoseUpdateLayout.creationSubjectOffset, r.subject)
      b.i64(o + AnchorPoseUpdateLayout.creationCaptureNsOffset, r.captureNs)
      b.i64(o + AnchorPoseUpdateLayout.creationFrameNsOffset, r.frameNs)
      b.u32(o + 32, 0)
      b.u32(o + 36, 0)
    }
    if resultHead == results.count {
      results.removeAll(keepingCapacity: true)
      resultHead = 0
    }
    b.u16(payload, updates)
    b.u16(payload + 2, creations)
    let payloadBytes = AnchorPoseUpdateLayout.payloadBytes(updates: updates, creations: creations)
    IngestMessageWriter.payloadBytes(b, payloadBytes)
    IngestMessageWriter.sequence(b, sequence)
    IngestMessageWriter.timing(b, sampleNs: frameNs, frameNs: frameNs, acquireNs: nowNs)
    IngestMessageWriter.sendTime(b, nowNs)
    cameraTq.withUnsafeBufferPointer { tq in
      cameraMatrix.withUnsafeMutableBufferPointer { Self.poseMatrix(tq.baseAddress!, $0.baseAddress!, rows: false) }
    }
    b.u8(IngestMessageLayout.trackingState, cameraTracking)
    b.u8(IngestMessageLayout.poseMatch, IngestMessageLayout.poseMatchExact)
    b.u16(IngestMessageLayout.groupEpoch, epoch)
    b.i32(IngestMessageLayout.poseGapUs, 0)
    // Contract version 2: T_world_cam, the ARKit camera Y and Z columns negated.
    for i in 0..<16 { b.f32(IngestMessageLayout.cameraToWorld + 4 * i, (4..<12).contains(i) ? -cameraMatrix[i] : cameraMatrix[i]) }
    b.u32(AnchorPoseUpdateLayout.trackedAnchorsOffset, UInt32(attached))
    b.u32(AnchorPoseUpdateLayout.anchorReadMaxMicrosOffset, UInt32(Self.micros(maxReadNs)))
    b.u32(AnchorPoseUpdateLayout.frameIntervalMaxMicrosOffset, UInt32(Self.micros(maxIntervalNs)))
    maxReadNs = 0
    maxIntervalNs = 0
    if finalsSent { removeSentFinalsLocked() }
    updatesSent += updates
    repliesSent += 1
    return IngestMessageLayout.headerBytes + payloadBytes
  }

  private static func micros(_ ns: Int64) -> Int32 { max(0, gapMicros(ns)) }

  private func writeUpdateLocked(_ b: IngestMessageBuffer, _ o: Int, _ s: Int) {
    b.u32(o, ids[s])
    b.u8(o + AnchorPoseUpdateLayout.updateKindOffset, Int(kinds[s]))
    b.u8(o + AnchorPoseUpdateLayout.updateTrackingOffset, Int(tracking[s]))
    b.u16(o + 6, 0)
    let p = s * Self.poseStride
    current.withUnsafeBufferPointer { c in
      rowScratch.withUnsafeMutableBufferPointer { Self.poseMatrix(c.baseAddress! + p, $0.baseAddress!, rows: true) }
    }
    for k in 0..<12 { b.f32(o + AnchorPoseUpdateLayout.updateMatrixOffset + 4 * k, rowScratch[k]) }
    for k in 0..<Self.poseStride { sent[p + k] = current[p + k] }
    sentTracking[s] = Int8(tracking[s])
  }

  private func removeSentFinalsLocked() {
    var s = count - 1
    while s >= 0 {
      if final[s] && !dirty[s] { removeSlotLocked(s) }
      s -= 1
    }
  }

  /// Swap-removes slot [s] (its anchor already detached).
  private func removeSlotLocked(_ s: Int) {
    let last = count - 1
    if idIndex[ids[s]] == s { idIndex[ids[s]] = nil }
    if s != last {
      ids[s] = ids[last]; kinds[s] = kinds[last]; subjects[s] = subjects[last]
      captureNs[s] = captureNs[last]; createdNs[s] = createdNs[last]
      anchors[s] = anchors[last]; anchorKeys[s] = anchorKeys[last]
      tracking[s] = tracking[last]; sentTracking[s] = sentTracking[last]
      for k in 0..<Self.poseStride {
        current[s * Self.poseStride + k] = current[last * Self.poseStride + k]
        sent[s * Self.poseStride + k] = sent[last * Self.poseStride + k]
        reference[s * Self.poseStride + k] = reference[last * Self.poseStride + k]
      }
      stableSinceNs[s] = stableSinceNs[last]
      dirty[s] = dirty[last]
      final[s] = final[last]
      idIndex[ids[s]] = s
      if anchors[s] != nil, let key = anchorKeys[s] { keyIndex[key] = s }
    }
    anchors[last] = nil
    anchorKeys[last] = nil
    dirty[last] = false
    count -= 1
  }

  // MARK: capture thread

  /// Queues a photo anchor at [tq] while tracking is active; [poseValid]
  /// false yields a failed creation record. Returns the anchor id, or nil while inactive.
  @discardableResult
  func enqueuePhoto(subject: Int64, captureNs capture: Int64, tq: [Float], poseValid: Bool) -> UInt32? {
    lock.lock(); defer { lock.unlock() }
    guard active, !closed else { return nil }
    let id = nextPhotoId
    nextPhotoId = nextPhotoId >= Self.maxPhotoId ? 1 : nextPhotoId + 1
    requests.append(Request(id: id, kind: AnchorKind.photo, status: poseValid ? -1 : AnchorCreation.failed,
                            subject: subject, captureNs: capture, tq: tq))
    if !poseValid { failed += 1 }
    return id
  }

  // MARK: shared

  private func pushResultLocked(id: UInt32, kind: Int, status: Int, subject: Int64, captureNs: Int64, ts: Int64) {
    results.append(Result(id: id, kind: kind, status: status, subject: subject, captureNs: captureNs, frameNs: ts))
  }

  /// Queued creations that will not run answer not tracking.
  private func abandonRequestsLocked() {
    while requestHead < requests.count {
      let r = requests[requestHead]
      requestHead += 1
      let status = r.status < 0 ? AnchorCreation.notTracking : r.status
      if status == AnchorCreation.notTracking { notTracking += 1 }
      pushResultLocked(id: r.id, kind: r.kind, status: status, subject: r.subject, captureNs: r.captureNs, ts: frameNs)
    }
    requests.removeAll(keepingCapacity: true)
    requestHead = 0
  }

  private func deactivateLocked() {
    active = false
    deactivations += 1
    detachAllLocked()
    abandonRequestsLocked()
    previousFrameNs = 0
  }

  /// Hands every anchor to the AR queue for detaching and clears the table (no records).
  private func detachAllLocked() {
    for s in 0..<count {
      if let a = anchors[s] { pendingDetach.append(a) }
      anchors[s] = nil
      anchorKeys[s] = nil
      dirty[s] = false
      final[s] = false
    }
    count = 0
    attached = 0
    dirtyCount = 0
    cursor = 0
    idIndex.removeAll(keepingCapacity: true)
    keyIndex.removeAll(keepingCapacity: true)
    armedFlag = !closed && (active || !pendingDetach.isEmpty)
  }

  private func growTableLocked() {
    let c = capacity * 2
    func grow<T>(_ a: inout [T], _ fill: T, _ stride: Int = 1) { a.append(contentsOf: repeatElement(fill, count: (c - capacity) * stride)) }
    grow(&ids, 0); grow(&kinds, 0); grow(&subjects, 0); grow(&captureNs, 0); grow(&createdNs, 0)
    grow(&anchors, nil); grow(&anchorKeys, nil); grow(&tracking, 0); grow(&sentTracking, 0)
    grow(&current, 0, Self.poseStride); grow(&sent, 0, Self.poseStride); grow(&reference, 0, Self.poseStride)
    grow(&stableSinceNs, 0); grow(&dirty, false); grow(&final, false)
    capacity = c
    tableGrowth += 1
  }

  /// Engine detached: detaches every anchor on the calling thread.
  func close() {
    lock.lock(); defer { lock.unlock() }
    closed = true
    active = false
    detachAllLocked()
    drainDetachLocked(session: lastSession)
    armedFlag = false
  }

  /// Diagnostic counters (allocates; not for the frame path).
  func snapshot() -> [String: Any] {
    lock.lock(); defer { lock.unlock() }
    return [
      "active": active, "trackedAnchors": attached, "created": created, "failed": failed, "overLimit": overLimit,
      "notTracking": notTracking, "detachedByLimit": detachedByLimit, "updatesSent": updatesSent,
      "repliesSent": repliesSent, "malformedQueries": malformedQueries, "growthEvents": tableGrowth,
      "deactivations": deactivations, "sessionReplacements": sessionReplacements, "anchorJumps": anchorJumps,
      "pendingCreations": requests.count - requestHead, "pendingResults": results.count - resultHead,
      "dirtyAnchors": dirtyCount,
    ]
  }

  var trackedAnchors: Int { lock.lock(); defer { lock.unlock() }; return attached }
  var isActive: Bool { lock.lock(); defer { lock.unlock() }; return active }
  var pendingDetaches: Int { lock.lock(); defer { lock.unlock() }; return pendingDetach.count }
}
