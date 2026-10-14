import Foundation

/// Platform side of the anchor pose tracking channel
/// `capture3d/visibility_grid/pose_tracking` (pose stability, plan 2.14),
/// the Swift twin of the Android `AnchorPoseUpdateProducer`.
///
/// The grid isolate sends a `C3PQ` query at most 10 Hz; every query is
/// answered at once (no parking) with one kind 7 message: the 160 B ingest
/// header (latest AR frame timestamp, camera tracking and pose, the query's
/// group epoch, tracker diagnostics in the reserved words) followed by up to
/// 1024 dirty anchor updates and up to 64 creation records. A malformed
/// query, a query after close or a failing tracker is answered with the
/// 4-byte status [statusQueryRefused], never with nil.
///
/// Anchors are created for the query's keyframe and submap requests and,
/// while tracking is active, for every durable photo. The reply is written
/// into a buffer allocated once and copied into the reply `Data`.
final class AnchorPoseUpdateProducer: DurablePhotoListener {
  /// 4-byte status reply of a refused query.
  static let statusQueryRefused: Int32 = 2
  static let maxQueryBytes = PoseQueryLayout.headerBytes + PoseQueryLayout.maxRequests * PoseQueryLayout.requestBytes

  let tracker: AnchorPoseTracker
  private let replyLock = NSLock()
  private let query = IngestMessageBuffer(capacity: AnchorPoseUpdateProducer.maxQueryBytes)
  private let reply = IngestMessageBuffer(capacity: AnchorPoseUpdateLayout.maxMessageBytes)
  private var sequence: UInt32 = 0

  private(set) var queriesRefused = 0

  init(backend: AnchorBackend, clock: IngestClock, readTimer: IngestClock = UptimeRawClock(),
       initialCapacity: Int = AnchorPoseTracker.defaultCapacity) {
    tracker = AnchorPoseTracker(backend: backend, clock: clock, readTimer: readTimer, initialCapacity: initialCapacity)
  }

  /// Channel handler; runs on the background task queue. Never replies nil.
  func onQuery(_ message: Data?, reply send: (Data) -> Void) {
    replyLock.lock(); defer { replyLock.unlock() }
    var length = AnchorPoseTracker.queryMalformed
    if let m = message {
      let n = min(m.count, query.capacity)
      m.withUnsafeBytes { raw in
        if let base = raw.baseAddress, n > 0 { query.copy(from: base, length: n) }
      }
      // An oversized query keeps its first bytes; validation checks the declared count against them.
      length = tracker.handleQuery(m.count > query.capacity ? nil : query, length: n, out: reply, sequence: sequence)
    } else {
      length = tracker.handleQuery(nil, length: 0, out: reply, sequence: sequence)
    }
    if length < 0 {
      queriesRefused += 1
      send(ChannelStatusReply.data(Self.statusQueryRefused))
      return
    }
    sequence &+= 1
    send(reply.data(length))
  }

  /// AR delegate queue, once per frame. Returns after one locked read while tracking is inactive.
  func onFrame(session: AnyObject, frame: AnchorFrame) {
    tracker.onFrame(session: session, frame: frame)
  }

  /// Capture thread: queues a photo anchor at the photo's camera-to-world
  /// pose (event rows 0-2) while tracking is active. Subject id is photo id
  /// bytes 0-7 as little-endian i64.
  func onPhotoDurable(event: [UInt8], record: DurablePhotoRecord) {
    guard event.count >= PhotoSavedLayout.bytes, tracker.armed else { return }
    event.withUnsafeBytes { raw in
      let subject = Int64(littleEndian: raw.loadUnaligned(fromByteOffset: PhotoSavedLayout.photoId, as: Int64.self))
      let exposureNs = Int64(littleEndian: raw.loadUnaligned(fromByteOffset: PhotoSavedLayout.exposureTimestampNs, as: Int64.self))
      var rows = [Float](repeating: 0, count: 12)
      for k in 0..<12 {
        rows[k] = Float(bitPattern: UInt32(littleEndian: raw.loadUnaligned(
          fromByteOffset: PhotoSavedLayout.cameraToWorldRows + 4 * k, as: UInt32.self)))
      }
      var tq = [Float](repeating: 0, count: 7)
      let valid = rows.withUnsafeBufferPointer { r in
        tq.withUnsafeMutableBufferPointer { AnchorPoseTracker.rowsToPose(r.baseAddress!, $0.baseAddress!) }
      }
      tracker.enqueuePhoto(subject: subject, captureNs: exposureNs, tq: tq, poseValid: valid)
    }
  }

  func close() { tracker.close() }

  func snapshot() -> [String: Any] { tracker.snapshot() }
}
