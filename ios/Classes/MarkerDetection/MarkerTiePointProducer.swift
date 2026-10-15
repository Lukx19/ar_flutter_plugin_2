import CoreGraphics
import Foundation
import ImageIO

/// Full-resolution marker tie points of every durable photo, the iOS twin of
/// the Android `TiePointPhotoRelay` and `PhotoTiePointProducer`: registered
/// as a [DurablePhotoListener]; while the shell runs with marker detection
/// on, the committed still is decoded to grey (ImageIO into one reused
/// buffer), searched at full resolution, refined with edge lines, and the
/// batch travels as ingest kind 8 ([TiePointMessageWriter]). Runs on its own
/// serial queue, one photo at a time, off the capture path.
///
/// The durable photo record names the committed JPEG (the capture layer
/// resolves it from the durable store at the terminal); [imageURL] reads it
/// and is replaceable in tests. A photo whose image cannot be resolved or
/// decoded still sends a batch, with status `C3MD_ERROR_ARGUMENT` and no
/// records (as on Android).
final class MarkerTiePointProducer: DurablePhotoListener {
  /// Created on the detection queue at the first photo.
  private let makeDetector: () -> NativeMarkerDetector?
  private var detector: NativeMarkerDetector?
  private let settings: () -> MarkerDetectionSettings?
  private let sink: () -> IngestMessageSink?
  private let clock: IngestClock
  private let queue: DispatchQueue
  private let writer = TiePointMessageWriter()
  private var grey: UnsafeMutablePointer<UInt8>?
  private var greyBytes = 0
  private var appliedSize: Float = 0

  /// Resolves the committed image of a durable photo; nil while unknown.
  var imageURL: (DurablePhotoRecord) -> URL? = { $0.imageURL }

  private let lock = NSLock()
  private(set) var photos = 0
  private(set) var failures = 0
  private(set) var offered = 0

  init(
    makeDetector: @escaping () -> NativeMarkerDetector?, settings: @escaping () -> MarkerDetectionSettings?,
    sink: @escaping () -> IngestMessageSink?, clock: IngestClock,
    queue: DispatchQueue = DispatchQueue(label: "capture3d.markers.tiepoints", qos: .utility)
  ) {
    self.makeDetector = makeDetector
    self.settings = settings
    self.sink = sink
    self.clock = clock
    self.queue = queue
  }

  deinit { grey?.deallocate() }

  func onPhotoDurable(event: [UInt8], record: DurablePhotoRecord) {
    guard let s = settings(), s.enabled, event.count >= PhotoSavedLayout.bytes else { return }
    let resolve = imageURL
    queue.async { [self] in
      let started = clock.nowNs()
      let url = resolve(record)
      process(event: event, epoch: s.groupEpoch, markerSize: s.markerSizeMetres, startedNs: started) { body in
        guard let u = url else { return false }
        return decodeGrey(u, body)
      }
    }
  }

  /// Detection queue: one photo. [image] calls its body with the grey image
  /// (pointer, width, height, stride) and returns false when there is none.
  /// Returns the tie point count or a negative status.
  @discardableResult
  func process(
    event: [UInt8], epoch: Int, markerSize: Float, startedNs: Int64,
    image: ((UnsafePointer<UInt8>, Int, Int, Int) -> Void) -> Bool
  ) -> Int32 {
    if detector == nil { detector = makeDetector() }
    guard let detector else {
      lock.lock(); photos += 1; failures += 1; lock.unlock()
      return NativeMarkerDetector.statusArgument
    }
    if markerSize > 0, markerSize != appliedSize {
      detector.setMarkerSize(Double(markerSize))
      appliedSize = markerSize
    }
    let photoId = Array(event[PhotoSavedLayout.photoId..<(PhotoSavedLayout.photoId + 32)])
    let exposure = event.withUnsafeBytes {
      Int64(littleEndian: $0.loadUnaligned(fromByteOffset: PhotoSavedLayout.exposureTimestampNs, as: Int64.self))
    }
    func f32(_ o: Int) -> Float {
      event.withUnsafeBytes { Float(bitPattern: UInt32(littleEndian: $0.loadUnaligned(fromByteOffset: o, as: UInt32.self))) }
    }
    func u16(_ o: Int) -> Int { Int(event[o]) | Int(event[o + 1]) << 8 }
    let stillWidth = u16(PhotoSavedLayout.widthDiv8) * 8, stillHeight = u16(PhotoSavedLayout.heightDiv8) * 8
    var status = NativeMarkerDetector.statusArgument
    var count: Int32 = 0
    var width = 0, height = 0
    let decoded = image { grey, w, h, stride in
      width = w
      height = h
      // Intrinsics are at the still resolution; scale to the decoded image.
      let sx = stillWidth > 0 ? Double(w) / Double(stillWidth) : 1
      let sy = stillHeight > 0 ? Double(h) / Double(stillHeight) : 1
      detector.setCamera(
        fx: Double(f32(PhotoSavedLayout.intrinsics)) * sx, fy: Double(f32(PhotoSavedLayout.intrinsics + 4)) * sy,
        cx: (Double(f32(PhotoSavedLayout.intrinsics + 8)) + 0.5) * sx - 0.5,
        cy: (Double(f32(PhotoSavedLayout.intrinsics + 12)) + 0.5) * sy - 0.5)
      let n = detector.detectTiePoints(grey, width: w, height: h, stride: stride)
      status = min(n, 0)
      count = max(n, 0)
    }
    let ms = Float(clock.nowNs() - startedNs) / 1e6
    let length = writer.write(
      photoId: photoId, width: width, height: height, groupEpoch: epoch, exposureNs: exposure,
      status: decoded ? status : NativeMarkerDetector.statusArgument, timeMs: ms, acquireNs: clock.nowNs(),
      records: count > 0 ? detector.tiePoints : nil, count: Int(count))
    let ok = sink()?.offer(writer.buffer, length: length) ?? false
    lock.lock()
    photos += 1
    if !decoded || status < 0 { failures += 1 }
    if ok { offered += 1 }
    lock.unlock()
    return decoded ? (status < 0 ? status : count) : NativeMarkerDetector.statusArgument
  }

  /// Decodes the image at [url] to 8-bit grey in the reused buffer.
  func decodeGrey(_ url: URL, _ body: (UnsafePointer<UInt8>, Int, Int, Int) -> Void) -> Bool {
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let cg = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCache: false] as CFDictionary)
    else { return false }
    let w = cg.width, h = cg.height
    guard w > 0, h > 0 else { return false }
    if greyBytes < w * h {
      grey?.deallocate()
      grey = UnsafeMutablePointer<UInt8>.allocate(capacity: w * h)
      greyBytes = w * h
    }
    guard let buffer = grey,
          let context = CGContext(
            data: buffer, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
            space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)
    else { return false }
    context.interpolationQuality = .none
    context.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
    body(UnsafePointer(buffer), w, h, w)
    return true
  }
}
