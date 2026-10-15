import CryptoKit
import Foundation
import simd

/// One committed durable photo as the iOS capture layer reports it after its
/// durable terminal (called by capture later; this is the API).
struct DurablePhotoRecord {
  /// Durable store capture id: SHA-256 hex of the attempt id (any other text is hashed).
  var captureId: String
  /// Attempt UUID string (any other text maps to the first 16 bytes of its SHA-256).
  var attemptId: String
  /// `ARFrame.timestamp` of the high-resolution frame, in nanoseconds.
  var exposureTimestampNs: Int64
  /// `ARCamera.transform` of that frame; nil when unknown (the event then carries no pose).
  var cameraToWorld: simd_float4x4?
  /// fx, fy, cx, cy at the still resolution.
  var intrinsics: simd_float4?
  var width: Int
  var height: Int
  /// JPEG Laplacian sharpness score (vImage), when assessed.
  var sharpness: Double?
  var automatic: Bool
  var blurRejected: Bool
  /// The committed JPEG in the durable store; nil when unknown.
  var imageURL: URL? = nil
  /// Set when the photo is one image of a burst or quality shot.
  var shotImage: ShotImageRecord? = nil
}

/// One image of a burst or quality shot in the photo saved shot extension
/// ([PhotoShotImageLayout]); contract bytes, no capture types.
struct ShotImageRecord: Equatable {
  var shotId: UUID
  var index: Int
  var count: Int
  /// [PhotoShotImageLayout] shot mode byte: 1 burst, 2 quality.
  var mode: Int
  /// [PhotoShotImageLayout] flag bits.
  var flags: Int
}

/// Receives one call per durable photo after its photo saved event was built.
protocol DurablePhotoListener: AnyObject {
  /// [event] is the 128 B photo saved event (contract 2.5).
  func onPhotoDurable(event: [UInt8], record: DurablePhotoRecord)
}

/// Builds the photo saved event (contract 2.5) once per committed durable
/// photo, offers it to the ingest queue as kind 4 while the shell is started
/// and hands it to every [DurablePhotoListener] (photo anchors).
///
/// Exactly once: a capture id emitted among the last [recent] photos is
/// ignored. The group epoch byte is the epoch active at the exposure
/// (recorded per frame on the AR frame clock, which is also the exposure
/// clock on iOS), so a photo exposed before a group switch keeps its group.
final class PhotoSavedEmitter {
  static let recent = 64
  static let epochHistory = 16

  private let sink: IngestMessageSink
  private let clock: IngestClock
  private let lock = NSLock()
  private let message = IngestMessageBuffer(capacity: IngestMessageLayout.headerBytes + PhotoSavedLayout.bytes + PhotoShotImageLayout.bytes)
  private let event = IngestMessageBuffer(capacity: PhotoSavedLayout.bytes + PhotoShotImageLayout.bytes)
  private var recentIds = [String?](repeating: nil, count: PhotoSavedEmitter.recent)
  private var recentNext = 0

  private var epochStartNs = [Int64](repeating: 0, count: PhotoSavedEmitter.epochHistory)
  private var epochValue = [Int](repeating: 0, count: PhotoSavedEmitter.epochHistory)
  private var epochCount = 0

  /// True while the shell is started (the event is offered to the ingest queue).
  var offersToIngest: () -> Bool = { false }
  /// Current group epoch and tracking state of the shell.
  var currentEpoch: () -> Int = { 0 }
  var trackingState: () -> Int = { IngestTrackingState.stopped }
  var durableListeners: () -> [DurablePhotoListener] = { [] }

  private(set) var emitted = 0
  private(set) var duplicates = 0
  private(set) var poseMissing = 0
  private(set) var offered = 0

  init(sink: IngestMessageSink, clock: IngestClock) {
    self.sink = sink
    self.clock = clock
  }

  /// AR frame queue: [epoch] applies from the frame at [frameNs] on.
  func recordEpochStart(frameNs: Int64, epoch: Int) {
    guard frameNs > 0 else { return }
    lock.lock(); defer { lock.unlock() }
    if epochCount > 0 && epochValue[epochCount - 1] == epoch { return }
    if epochCount == Self.epochHistory {
      for i in 1..<Self.epochHistory {
        epochStartNs[i - 1] = epochStartNs[i]
        epochValue[i - 1] = epochValue[i]
      }
      epochCount -= 1
    }
    epochStartNs[epochCount] = frameNs
    epochValue[epochCount] = epoch
    epochCount += 1
  }

  /// Epoch active at [exposureNs]: the newest epoch started at or before it.
  func epochAt(_ exposureNs: Int64) -> Int {
    lock.lock(); defer { lock.unlock() }
    return epochAtLocked(exposureNs)
  }

  private func epochAtLocked(_ exposureNs: Int64) -> Int {
    if epochCount == 0 || exposureNs <= 0 { return currentEpoch() }
    for i in stride(from: epochCount - 1, through: 0, by: -1) where epochStartNs[i] <= exposureNs {
      return epochValue[i]
    }
    return epochValue[0]
  }

  /// Emits the event of [record] once; returns false for a duplicate.
  @discardableResult
  func onDurableTerminal(_ record: DurablePhotoRecord) -> Bool {
    lock.lock()
    if recentIds.contains(where: { $0 == record.captureId }) {
      duplicates += 1
      lock.unlock()
      return false
    }
    recentIds[recentNext] = record.captureId
    recentNext = (recentNext + 1) % Self.recent
    let epoch = epochAtLocked(record.exposureTimestampNs)
    Self.fillEvent(event, record: record, groupEpoch: epoch)
    if record.cameraToWorld == nil { poseMissing += 1 }
    emitted += 1
    if offersToIngest() { offerLocked(record, epoch: epoch) }
    let bytes = [UInt8](UnsafeRawBufferPointer(start: event.bytes, count: Self.payloadBytes(record)))
    lock.unlock()
    for listener in durableListeners() { listener.onPhotoDurable(event: bytes, record: record) }
    return true
  }

  /// Kind 4 message: header with the exposure time, the photo pose
  /// (column-major), still intrinsics and size, then the 128 B event.
  private func offerLocked(_ record: DurablePhotoRecord, epoch: Int) {
    let b = message
    let payload = Self.payloadBytes(record)
    IngestMessageWriter.begin(b, kind: IngestMessageLayout.kindPhotoSaved)
    IngestMessageWriter.payloadBytes(b, payload)
    let ns = record.exposureTimestampNs
    IngestMessageWriter.timing(b, sampleNs: ns, frameNs: ns, acquireNs: clock.nowNs())
    let posed = record.cameraToWorld != nil
    IngestMessageWriter.pose(
      b, trackingState: trackingState(),
      poseMatch: posed ? IngestMessageLayout.poseMatchExact : IngestMessageLayout.poseMatchMissing,
      groupEpoch: epoch, poseGapUs: 0, cameraToWorld: record.cameraToWorld ?? simd_float4x4()
    )
    IngestMessageWriter.intrinsics(
      b, fx: event.readF32(PhotoSavedLayout.intrinsics), fy: event.readF32(PhotoSavedLayout.intrinsics + 4),
      cx: event.readF32(PhotoSavedLayout.intrinsics + 8), cy: event.readF32(PhotoSavedLayout.intrinsics + 12)
    )
    IngestMessageWriter.image(
      b, width: event.readU16(PhotoSavedLayout.widthDiv8) * 8, height: event.readU16(PhotoSavedLayout.heightDiv8) * 8,
      rowStrideBytes: 0, depthUnit: IngestMessageLayout.depthUnitU16Mm, confidenceFormat: IngestMessageLayout.confidenceNone
    )
    (b.bytes + IngestMessageLayout.headerBytes).copyMemory(from: event.bytes, byteCount: payload)
    if sink.offer(b, length: IngestMessageLayout.headerBytes + payload) { offered += 1 }
  }

  /// 128 B, or 160 B with the shot image extension.
  static func payloadBytes(_ record: DurablePhotoRecord) -> Int {
    PhotoSavedLayout.bytes + (record.shotImage == nil ? 0 : PhotoShotImageLayout.bytes)
  }

  /// Capture id of image [index] (1...) of the shot whose first image is
  /// [captureId]: SHA-256 hex of `<captureId>#<index>`, so each image is
  /// its own photo in the credit ledger.
  static func shotImageCaptureId(_ captureId: String, index: Int) -> String {
    SHA256.hash(data: Data("\(captureId)#\(index)".utf8)).map { String(format: "%02x", $0) }.joined()
  }

  /// Writes the shot image extension of [shot] at [base] of [e].
  static func fillShotImage(_ e: IngestMessageBuffer, _ shot: ShotImageRecord, base: Int = PhotoSavedLayout.bytes) {
    let L = PhotoShotImageLayout.self
    e.zero(base, L.bytes)
    e.u32(base, L.magic)
    e.u16(base + 4, L.version)
    e.u16(base + 6, L.bytes)
    let uuid = withUnsafeBytes(of: shot.shotId.uuid) { Array($0) }
    for i in 0..<16 { e.u8(base + L.shotId + i, Int(uuid[i])) }
    e.u8(base + L.imageIndex, shot.index)
    e.u8(base + L.imageCount, shot.count)
    e.u8(base + L.shotMode, shot.mode)
    e.u16(base + L.flags, shot.flags)
  }

  /// Writes the 128 B event of [record] into [e].
  static func fillEvent(_ e: IngestMessageBuffer, record: DurablePhotoRecord, groupEpoch: Int) {
    e.zero(0, PhotoSavedLayout.bytes)
    let id = photoIdBytes(record.captureId)
    for i in 0..<32 { e.u8(PhotoSavedLayout.photoId + i, Int(id[i])) }
    let uuid = attemptUuidBytes(record.attemptId)
    for i in 0..<16 { e.u8(PhotoSavedLayout.attemptUuid + i, Int(uuid[i])) }
    e.i64(PhotoSavedLayout.exposureTimestampNs, record.exposureTimestampNs)
    if let m = record.cameraToWorld {
      for row in 0..<3 {
        for col in 0..<4 { e.f32(PhotoSavedLayout.cameraToWorldRows + 4 * (row * 4 + col), m[col][row]) }
      }
      if let k = record.intrinsics, record.width > 0, record.height > 0,
         k.x.isFinite, k.x > 0, k.y.isFinite, k.y > 0, k.z.isFinite, k.w.isFinite {
        e.f32(PhotoSavedLayout.intrinsics, k.x)
        e.f32(PhotoSavedLayout.intrinsics + 4, k.y)
        e.f32(PhotoSavedLayout.intrinsics + 8, k.z)
        e.f32(PhotoSavedLayout.intrinsics + 12, k.w)
        e.u16(PhotoSavedLayout.widthDiv8, record.width / 8)
        e.u16(PhotoSavedLayout.heightDiv8, record.height / 8)
      }
    }
    if let score = record.sharpness, score.isFinite, score > 0 {
      e.u16(PhotoSavedLayout.sharpness, Int(min(65_535, score.rounded())))
    }
    var flags = 0
    if record.automatic { flags |= PhotoSavedLayout.flagAutomatic }
    if record.blurRejected { flags |= PhotoSavedLayout.flagBlurRejected }
    e.u8(PhotoSavedLayout.flags, flags)
    e.u8(PhotoSavedLayout.groupEpochLow, groupEpoch & 0xff)
    if let shot = record.shotImage, e.capacity >= PhotoSavedLayout.bytes + PhotoShotImageLayout.bytes {
      fillShotImage(e, shot)
    }
  }

  /// The durable store's capture id is SHA-256 hex; anything else is hashed.
  static func photoIdBytes(_ captureId: String) -> [UInt8] {
    let hex = Array(captureId.utf8)
    if hex.count == 64, let bytes = hexBytes(hex) { return bytes }
    return Array(SHA256.hash(data: Data(captureId.utf8)))
  }

  /// A canonical 36-character UUID string as its 16 bytes (big-endian, RFC
  /// 4122 order); anything else maps to the first 16 bytes of its SHA-256.
  static func attemptUuidBytes(_ attemptId: String) -> [UInt8] {
    if attemptId.utf8.count == 36, let uuid = UUID(uuidString: attemptId) {
      return withUnsafeBytes(of: uuid.uuid) { Array($0) }
    }
    return Array(Array(SHA256.hash(data: Data(attemptId.utf8))).prefix(16))
  }

  private static func hexBytes(_ hex: [UInt8]) -> [UInt8]? {
    func nibble(_ c: UInt8) -> UInt8? {
      switch c {
      case 0x30...0x39: return c - 0x30
      case 0x61...0x66: return c - 0x61 + 10
      default: return nil
      }
    }
    var out = [UInt8](repeating: 0, count: hex.count / 2)
    for i in 0..<out.count {
      guard let hi = nibble(hex[2 * i]), let lo = nibble(hex[2 * i + 1]) else { return nil }
      out[i] = hi << 4 | lo
    }
    return out
  }
}
