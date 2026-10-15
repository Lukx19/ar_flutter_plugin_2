import AVFoundation
import CoreVideo
import Foundation

/// Decodes the camera images of a recording (`video_NNN.mov`, HEVC at every
/// second frame, presentation time = `ARFrame.timestamp`) in video index
/// order with `AVAssetReader`, as bi-planar full-range YCbCr buffers that
/// Metal can wrap without a copy. Reads forward only; asking for an earlier
/// index reopens its segment. Used from one queue at a time (the replay queue).
final class RecordedVideoReader {
  private let directory: URL
  private let segments: [RecordedVideoSegment]
  private var segmentPosition: Int?
  private var reader: AVAssetReader?
  private var output: AVAssetReaderTrackOutput?
  /// Video index of the next sample [output] returns.
  private var nextIndex: UInt32 = 0
  /// Segments that failed to open or decode; never retried.
  private var failedSegments = Set<Int>()
  /// Where a movie really ended when it holds fewer frames than `meta.json`
  /// lists (an unflushed last fragment): segment position to the first
  /// missing video index. Indexes from there on return nil at once.
  private var segmentEnds: [Int: UInt32] = [:]
  /// Number of segment opens (tests: a short movie is never reopened for
  /// its missing indexes).
  private(set) var opens = 0

  /// Called once per failed segment with its file name and the reason.
  var onSegmentFailed: ((String, String) -> Void)?

  init(directory: URL, segments: [RecordedVideoSegment]) {
    self.directory = directory
    self.segments = segments
  }

  /// The image of video index [videoIndex], or nil when its segment is
  /// missing or unreadable or the movie ends before it.
  func image(videoIndex: UInt32) -> CVPixelBuffer? {
    guard let position = segments.firstIndex(where: { $0.contains(videoIndex) }),
          !failedSegments.contains(position) else { return nil }
    if let end = segmentEnds[position], videoIndex >= end { return nil }
    if position != segmentPosition || videoIndex < nextIndex {
      guard open(position) else { return nil }
    }
    guard let output = output else { return nil }
    while nextIndex <= videoIndex {
      guard let sample = output.copyNextSampleBuffer() else {
        if let reader = reader, reader.status == .failed {
          fail(position, reason: reader.error.map { "\($0)" } ?? "reader failed")
        } else {
          // The movie ended before the indexes meta.json lists: remember
          // the real end instead of decoding the segment again per index.
          segmentEnds[position] = nextIndex
        }
        close()
        return nil
      }
      guard let image = CMSampleBufferGetImageBuffer(sample) else { continue }
      let index = nextIndex
      nextIndex += 1
      if index == videoIndex { return image }
    }
    return nil
  }

  /// Releases the open movie.
  func close() {
    reader?.cancelReading()
    reader = nil
    output = nil
    segmentPosition = nil
  }

  private func open(_ position: Int) -> Bool {
    close()
    opens += 1
    let segment = segments[position]
    let asset = AVURLAsset(url: directory.appendingPathComponent(segment.file))
    do {
      let reader = try AVAssetReader(asset: asset)
      guard let track = Self.videoTrack(of: asset) else {
        fail(position, reason: "no video track")
        return false
      }
      let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
        // IOSurface-backed buffers wrap into Metal textures without a copy.
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
        kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
      ])
      output.alwaysCopiesSampleData = false
      guard reader.canAdd(output) else {
        fail(position, reason: "cannot add track output")
        return false
      }
      reader.add(output)
      guard reader.startReading() else {
        fail(position, reason: reader.error.map { "\($0)" } ?? "startReading failed")
        return false
      }
      self.reader = reader
      self.output = output
      segmentPosition = position
      nextIndex = segment.firstVideoIndex
      return true
    } catch {
      fail(position, reason: "\(error)")
      return false
    }
  }

  /// The movie's first video track; waits for the asset to load it (the
  /// caller is the replay queue, never the main thread).
  private static func videoTrack(of asset: AVURLAsset) -> AVAssetTrack? {
    let loaded = DispatchSemaphore(value: 0)
    var track: AVAssetTrack?
    asset.loadTracks(withMediaType: .video) { tracks, _ in
      track = tracks?.first
      loaded.signal()
    }
    loaded.wait()
    return track
  }

  private func fail(_ position: Int, reason: String) {
    guard failedSegments.insert(position).inserted else { return }
    onSegmentFailed?(segments[position].file, reason)
  }
}

/// The camera image shown when a recording has no usable video: a dark
/// grey frame with a lighter grid, bi-planar full-range YCbCr of the
/// recording's image size, so consumers that scale intrinsics by the image
/// size still see the recorded resolution.
enum RecordedPlaceholderImage {
  static let background: UInt8 = 40
  static let gridLine: UInt8 = 110
  static let gridCells = 8

  static func make(width: Int, height: Int) -> CVPixelBuffer? {
    guard width > 0, height > 0 else { return nil }
    var buffer: CVPixelBuffer?
    let attributes: [String: Any] = [
      kCVPixelBufferMetalCompatibilityKey as String: true,
      kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
    ]
    guard CVPixelBufferCreate(
      kCFAllocatorDefault, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
      attributes as CFDictionary, &buffer) == kCVReturnSuccess, let image = buffer else { return nil }
    CVPixelBufferLockBaseAddress(image, [])
    defer { CVPixelBufferUnlockBaseAddress(image, []) }
    if let luma = CVPixelBufferGetBaseAddressOfPlane(image, 0) {
      let rowBytes = CVPixelBufferGetBytesPerRowOfPlane(image, 0)
      let cellWidth = max(1, width / gridCells)
      let cellHeight = max(1, height / gridCells)
      let lineWidth = max(2, width / 400)
      for y in 0..<height {
        let row = luma.advanced(by: y * rowBytes).assumingMemoryBound(to: UInt8.self)
        let horizontalLine = y % cellHeight < lineWidth
        for x in 0..<width {
          row[x] = horizontalLine || x % cellWidth < lineWidth ? gridLine : background
        }
      }
    }
    if let chroma = CVPixelBufferGetBaseAddressOfPlane(image, 1) {
      let rowBytes = CVPixelBufferGetBytesPerRowOfPlane(image, 1)
      memset(chroma, 128, rowBytes * CVPixelBufferGetHeightOfPlane(image, 1))
    }
    return image
  }
}
