import Foundation

/// Monotonic clock in nanoseconds.
protocol IngestClock: AnyObject {
  func nowNs() -> Int64
}

/// `CLOCK_UPTIME_RAW`, the base of `ARFrame.timestamp` (acquire and send times).
final class UptimeRawClock: IngestClock {
  func nowNs() -> Int64 { Int64(bitPattern: clock_gettime_nsec_np(CLOCK_UPTIME_RAW)) }
}

/// Reply of a binary channel; never called with nil (2.16 row 22).
typealias IngestReply = (Data) -> Void

/// Accepts complete ingest messages (header plus payload, contract 2.4) from
/// any producer. The bytes are copied before the call returns; the sink
/// stamps sequence, send time and the dropped count.
protocol IngestMessageSink: AnyObject {
  /// Offers `message[0, length)`. Kinds 1, 2, 3 and 5 keep only the freshest
  /// unsent message (a replaced one counts as dropped); kinds 4, 6 and 8 are
  /// queued in order. Returns false when the message was refused (malformed,
  /// oversized or a full queue); the refusal is counted.
  @discardableResult
  func offer(_ message: IngestMessageBuffer, length: Int) -> Bool

  /// Counts a message of [kind] dropped before it was offered.
  func countSourceDrop(kind: Int)
}

/// Pull side of the ingest channel `capture3d/visibility_grid/ingest`, the
/// Swift twin of the Android `IngestPullHandler`.
///
/// The grid isolate pulls: each 8 B pull request is answered with the
/// freshest unsent accepted message, or parked until one is offered; a parked
/// pull is answered with an idle message after [idleAfterNs]. A drain pull
/// (its mask accepts only photo saved and tie point kinds) is never parked.
/// One pull is in flight; a new pull answers a parked one with idle first.
///
/// Slots are allocated once. Offered messages are copied into per-kind slots;
/// a reply copies the slot into a new `Data` (copy-on-reply), so the engine
/// owns its reply bytes and no producer or slot buffer ever escapes. Every
/// reply carries a message, never nil.
final class IngestPullHandler: IngestMessageSink {
  static let idleAfterNs: Int64 = 500_000_000
  static let photoQueue = 32
  static let sessionQueue = 64
  static let photoMessageBytes = IngestMessageLayout.headerBytes + PhotoSavedLayout.bytes
  static let sessionMessageBytes = IngestMessageLayout.headerBytes + IngestMessageLayout.sessionEventBytes
  /// Photo tie point batches waiting (one per saved photo).
  static let tiePointQueue = 8
  /// Header, `C3TB` batch header and up to 128 native tie point records of 64 B.
  static let tiePointMessageBytes = IngestMessageLayout.headerBytes + 64 + 128 * 64
  static let drainKindMask = (1 << IngestMessageLayout.kindPhotoSaved) | (1 << IngestMessageLayout.kindPhotoTiePoints)
  /// Delivery order of the freshest-only kinds.
  static let latestOrder = [
    IngestMessageLayout.kindDepthImage,
    IngestMessageLayout.kindMarkerDetections,
    IngestMessageLayout.kindPointCloud,
    IngestMessageLayout.kindAggregatedVoxels,
  ]

  private let clock: IngestClock
  private let lock = NSLock()

  /// Called with the delay after which [idleCheckFired] should run (a pull
  /// was parked). At most one check is armed at a time.
  var scheduleIdleCheck: ((Int64) -> Void)?

  /// Receives every replied message (debug recording), on the reply path.
  var recorder: ((UnsafeRawPointer, Int) -> Void)?

  private var latest: [IngestMessageBuffer?] = Array(repeating: nil, count: IngestMessageLayout.kindCount)
  private var latestLength = [Int](repeating: 0, count: IngestMessageLayout.kindCount)
  private var latestFresh = [Bool](repeating: false, count: IngestMessageLayout.kindCount)

  private let photoRing = (0..<photoQueue).map { _ in IngestMessageBuffer(capacity: photoMessageBytes) }
  private var photoHead = 0, photoCount = 0
  private let sessionRing = (0..<sessionQueue).map { _ in IngestMessageBuffer(capacity: sessionMessageBytes) }
  private var sessionHead = 0, sessionCount = 0
  private let tieRing = (0..<tiePointQueue).map { _ in IngestMessageBuffer(capacity: tiePointMessageBytes) }
  private var tieHead = 0, tieCount = 0
  private let idleBuffer = IngestMessageBuffer(capacity: IngestMessageLayout.headerBytes)

  private var sequence = [UInt32](repeating: 0, count: IngestMessageLayout.kindCount)
  private var droppedSinceSent = [UInt32](repeating: 0, count: IngestMessageLayout.kindCount)
  private var idleSequence: UInt32 = 0

  private var pending: IngestReply?
  private var pendingMask = 0
  private var pendingSinceNs: Int64 = 0
  /// One idle check is scheduled at a time: set when [scheduleIdleCheck] is
  /// called, cleared when it fires ([idleCheckFired]).
  private var idleCheckArmed = false
  private var closed = false

  // Counters (read under the lock).
  private var sent = [Int](repeating: 0, count: IngestMessageLayout.kindCount)
  private var replaced = [Int](repeating: 0, count: IngestMessageLayout.kindCount)
  private var refused = 0
  private var queueOverflow = 0
  private var malformedPulls = 0
  private var idleReplies = 0

  init(clock: IngestClock) {
    self.clock = clock
    for kind in Self.latestOrder { latest[kind] = IngestMessageBuffer(capacity: IngestMessageLayout.maxMessageBytes) }
  }

  @discardableResult
  func offer(_ message: IngestMessageBuffer, length: Int) -> Bool {
    let valid = length >= IngestMessageLayout.headerBytes && length <= IngestMessageLayout.maxMessageBytes &&
      message.capacity >= length && message.readU32(0) == IngestMessageLayout.magic &&
      IngestMessageWriter.messageBytes(message) == length
    lock.lock(); defer { lock.unlock() }
    if !valid || closed { refused += 1; return false }
    let kind = IngestMessageWriter.kind(message)
    switch kind {
    case IngestMessageLayout.kindPhotoSaved:
      if length > Self.photoMessageBytes || photoCount == Self.photoQueue { return overflowLocked(kind) }
      let slot = photoRing[(photoHead + photoCount) % Self.photoQueue]
      slot.copy(from: message.bytes, length: length)
      stampSequenceLocked(slot, kind)
      photoCount += 1
    case IngestMessageLayout.kindSessionEvent:
      if length > Self.sessionMessageBytes || sessionCount == Self.sessionQueue { return overflowLocked(kind) }
      let slot = sessionRing[(sessionHead + sessionCount) % Self.sessionQueue]
      slot.copy(from: message.bytes, length: length)
      stampSequenceLocked(slot, kind)
      sessionCount += 1
    case IngestMessageLayout.kindPhotoTiePoints:
      if length > Self.tiePointMessageBytes || tieCount == Self.tiePointQueue { return overflowLocked(kind) }
      let slot = tieRing[(tieHead + tieCount) % Self.tiePointQueue]
      slot.copy(from: message.bytes, length: length)
      stampSequenceLocked(slot, kind)
      tieCount += 1
    case IngestMessageLayout.kindDepthImage, IngestMessageLayout.kindPointCloud,
      IngestMessageLayout.kindAggregatedVoxels, IngestMessageLayout.kindMarkerDetections:
      let slot = latest[kind]!
      if latestFresh[kind] {
        replaced[kind] += 1
        droppedSinceSent[kind] &+= 1
      }
      slot.copy(from: message.bytes, length: length)
      stampSequenceLocked(slot, kind)
      latestLength[kind] = length
      latestFresh[kind] = true
    default:
      refused += 1
      return false
    }
    deliverPendingLocked()
    return true
  }

  func countSourceDrop(kind: Int) {
    guard kind >= 0 && kind < IngestMessageLayout.kindCount else { return }
    lock.lock(); droppedSinceSent[kind] &+= 1; lock.unlock()
  }

  private func overflowLocked(_ kind: Int) -> Bool {
    queueOverflow += 1
    droppedSinceSent[kind] &+= 1
    return false
  }

  private func stampSequenceLocked(_ slot: IngestMessageBuffer, _ kind: Int) {
    IngestMessageWriter.sequence(slot, sequence[kind])
    sequence[kind] &+= 1
  }

  /// Channel handler; runs on the ingest background task queue.
  func onPull(_ message: Data?, reply: @escaping IngestReply) {
    let mask = Self.pullMask(message)
    var schedule: Int64 = -1
    lock.lock()
    if let previous = pending {
      pending = nil
      replyIdleLocked(previous)
    }
    if mask < 0 { malformedPulls += 1 }
    if closed || mask < 0 {
      replyIdleLocked(reply)
    } else if !deliverLocked(reply, mask) {
      if mask & ~Self.drainKindMask == 0 {
        // A drain: the queues are empty now; waiting would only delay the stop.
        replyIdleLocked(reply)
      } else {
        pending = reply
        pendingMask = mask
        pendingSinceNs = clock.nowNs()
        // An armed check fires before this pull's deadline (deadlines only
        // move later) and re-arms for the remainder.
        if !idleCheckArmed {
          idleCheckArmed = true
          schedule = Self.idleAfterNs
        }
      }
    }
    lock.unlock()
    if schedule >= 0 { scheduleIdleCheck?(schedule) }
  }

  /// The scheduled idle check ran: disarms it, then [tick]s.
  func idleCheckFired() {
    lock.lock(); idleCheckArmed = false; lock.unlock()
    tick()
  }

  /// Answers a parked pull with idle once it waited [idleAfterNs]; returns ns
  /// until the parked pull's deadline, or -1 when nothing is parked. Arms an
  /// idle check for the remainder unless one is armed already.
  @discardableResult
  func tick() -> Int64 {
    var remaining: Int64 = -1
    var schedule = false
    lock.lock()
    if let p = pending {
      let waited = clock.nowNs() - pendingSinceNs
      if waited >= Self.idleAfterNs {
        pending = nil
        replyIdleLocked(p)
      } else {
        remaining = Self.idleAfterNs - waited
        if !idleCheckArmed {
          idleCheckArmed = true
          schedule = true
        }
      }
    }
    lock.unlock()
    if schedule { scheduleIdleCheck?(remaining) }
    return remaining
  }

  /// Drops queued data (shell start or stop); a parked pull stays parked.
  func clearQueued() {
    lock.lock(); defer { lock.unlock() }
    for kind in Self.latestOrder { latestFresh[kind] = false }
    photoCount = 0
    sessionCount = 0
    tieCount = 0
  }

  /// Engine detached: answers a parked pull with idle; every later pull is answered idle.
  func close() {
    lock.lock(); defer { lock.unlock() }
    closed = true
    if let p = pending {
      pending = nil
      replyIdleLocked(p)
    }
  }

  /// The accepted kind mask of a pull request, or -1 when malformed.
  static func pullMask(_ message: Data?) -> Int {
    guard let m = message, m.count >= IngestMessageLayout.pullRequestBytes else { return -1 }
    return m.withUnsafeBytes { raw -> Int in
      let magic = UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: 0, as: UInt32.self))
      let version = UInt16(littleEndian: raw.loadUnaligned(fromByteOffset: 4, as: UInt16.self))
      guard magic == IngestMessageLayout.pullRequestMagic, version == 1 else { return -1 }
      return Int(UInt16(littleEndian: raw.loadUnaligned(fromByteOffset: IngestMessageLayout.pullRequestMask, as: UInt16.self)))
    }
  }

  private func deliverPendingLocked() {
    guard let p = pending else { return }
    if deliverLocked(p, pendingMask) { pending = nil }
  }

  @inline(__always) private func accepts(_ mask: Int, _ kind: Int) -> Bool { (mask >> kind) & 1 != 0 }

  private func deliverLocked(_ reply: IngestReply, _ mask: Int) -> Bool {
    if photoCount > 0 && accepts(mask, IngestMessageLayout.kindPhotoSaved) {
      let slot = photoRing[photoHead]
      photoHead = (photoHead + 1) % Self.photoQueue
      photoCount -= 1
      replyLocked(reply, slot, IngestMessageWriter.messageBytes(slot), IngestMessageLayout.kindPhotoSaved)
      return true
    }
    if tieCount > 0 && accepts(mask, IngestMessageLayout.kindPhotoTiePoints) {
      let slot = tieRing[tieHead]
      tieHead = (tieHead + 1) % Self.tiePointQueue
      tieCount -= 1
      replyLocked(reply, slot, IngestMessageWriter.messageBytes(slot), IngestMessageLayout.kindPhotoTiePoints)
      return true
    }
    if sessionCount > 0 && accepts(mask, IngestMessageLayout.kindSessionEvent) {
      let slot = sessionRing[sessionHead]
      sessionHead = (sessionHead + 1) % Self.sessionQueue
      sessionCount -= 1
      replyLocked(reply, slot, IngestMessageWriter.messageBytes(slot), IngestMessageLayout.kindSessionEvent)
      return true
    }
    for kind in Self.latestOrder where latestFresh[kind] && accepts(mask, kind) {
      latestFresh[kind] = false
      replyLocked(reply, latest[kind]!, latestLength[kind], kind)
      return true
    }
    return false
  }

  /// Stamps send time and dropped count on the slot and replies with a copy of it.
  private func replyLocked(_ reply: IngestReply, _ slot: IngestMessageBuffer, _ length: Int, _ kind: Int) {
    IngestMessageWriter.sendTime(slot, clock.nowNs())
    IngestMessageWriter.dropped(slot, droppedSinceSent[kind])
    droppedSinceSent[kind] = 0
    sent[kind] += 1
    recorder?(UnsafeRawPointer(slot.bytes), length)
    reply(Data(bytes: slot.bytes, count: length))
  }

  private func replyIdleLocked(_ reply: IngestReply) {
    let b = idleBuffer
    IngestMessageWriter.begin(b, kind: IngestMessageLayout.kindIdle)
    IngestMessageWriter.sequence(b, idleSequence)
    idleSequence &+= 1
    IngestMessageWriter.sendTime(b, clock.nowNs())
    b.u8(IngestMessageLayout.poseMatch, IngestMessageLayout.poseMatchMissing)
    idleReplies += 1
    reply(Data(bytes: b.bytes, count: IngestMessageLayout.headerBytes))
  }

  /// Diagnostic counters (allocates; not for the frame path).
  func snapshot() -> [String: Any] {
    lock.lock(); defer { lock.unlock() }
    return [
      "sent": sent, "replaced": replaced, "refused": refused, "queueOverflow": queueOverflow,
      "malformedPulls": malformedPulls, "idleReplies": idleReplies, "parked": pending != nil,
    ]
  }

  var idleReplyCount: Int { lock.lock(); defer { lock.unlock() }; return idleReplies }
  var queueOverflowCount: Int { lock.lock(); defer { lock.unlock() }; return queueOverflow }
  func sentCount(kind: Int) -> Int { lock.lock(); defer { lock.unlock() }; return sent[kind] }
  func replacedCount(kind: Int) -> Int { lock.lock(); defer { lock.unlock() }; return replaced[kind] }
  var isParked: Bool { lock.lock(); defer { lock.unlock() }; return pending != nil }
}
