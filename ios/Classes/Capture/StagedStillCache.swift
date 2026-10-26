import Foundation

/// Parsed `initializeCapture` configuration (Android `CaptureConfig.fromMap`,
/// same codes and bounds). iOS captures JPEG and HEIC.
struct StillCaptureConfig {
  var format: String
  var captureIntervalMs: Int
  var resolutionWidth: Int
  var resolutionHeight: Int
  var maxCacheSize: Int
  var jpegQuality: Int

  struct Invalid: Error {
    var code: String
    var message: String
  }

  static func parse(_ value: Any?) throws -> StillCaptureConfig {
    guard let map = value as? [String: Any] else { throw Invalid(code: "CONFIG_INVALID", message: "Capture configuration is required") }
    guard let format = map["format"] as? String else {
      throw Invalid(code: "UNSUPPORTED_CAPTURE_FORMAT", message: "A logical capture format is required")
    }
    func int(_ key: String, _ fallback: Int) -> Int { (map[key] as? NSNumber)?.intValue ?? fallback }
    let resolution = map["resolution"] as? [String: Any]
    let config = StillCaptureConfig(
      format: format, captureIntervalMs: int("captureIntervalMs", 5000),
      resolutionWidth: (resolution?["width"] as? NSNumber)?.intValue ?? 0,
      resolutionHeight: (resolution?["height"] as? NSNumber)?.intValue ?? 0,
      maxCacheSize: int("maxCacheSize", 10), jpegQuality: int("jpegQuality", 95))
    if format == "raw+jpeg" {
      throw Invalid(code: "UNSUPPORTED_CAPTURE_FORMAT", message: "RAW+JPEG capture is not available on iOS; ARKit exposes processed stills, not RAW")
    }
    guard format == "jpeg" || format == "heic" else { throw Invalid(code: "UNSUPPORTED_CAPTURE_FORMAT", message: "Unsupported capture mode=\(format)") }
    guard config.captureIntervalMs >= 0 else { throw Invalid(code: "CONFIG_INVALID", message: "captureIntervalMs must be zero or a positive value") }
    guard !(1...99).contains(config.captureIntervalMs) else { throw Invalid(code: "CONFIG_INVALID", message: "captureIntervalMs must be 0 or at least 100ms") }
    guard config.captureIntervalMs <= 3_600_000 else { throw Invalid(code: "CONFIG_INVALID", message: "captureIntervalMs must not exceed 3600000ms") }
    guard config.resolutionWidth > 0, config.resolutionHeight > 0 else {
      throw Invalid(code: "CONFIG_INVALID", message: "resolution.width and resolution.height must be positive")
    }
    guard (1...100).contains(config.maxCacheSize) else { throw Invalid(code: "CONFIG_INVALID", message: "maxCacheSize must be between 1 and 100") }
    guard (10...100).contains(config.jpegQuality) else { throw Invalid(code: "CONFIG_INVALID", message: "jpegQuality must be between 10 and 100") }
    return config
  }
}

/// In-memory staged stills of the legacy `captureHighResImage` route
/// (Android `CaptureByteCache`): bounded by `maxCacheSize`, persisted or
/// discarded by id. The production shutter uses the durable store instead.
final class StagedStillCache {
  struct Entry {
    var imageId: String
    var bytes: Data
    var width: Int
    var height: Int
    var timestampMs: Int64
    var format: String = "jpeg"
  }

  struct Failure: Error {
    var code: String
    var message: String
  }

  private var config: StillCaptureConfig
  private var entries: [String: Entry] = [:]
  private var order: [String] = []

  init(config: StillCaptureConfig) { self.config = config }

  /// Applies [config] to stills cached afterwards; staged entries stay. A
  /// smaller `maxCacheSize` refuses new stills until enough are released.
  func reconfigure(_ config: StillCaptureConfig) { self.config = config }

  func nextImageId(timestampMs: Int64) -> String { "img_\(timestampMs)_\(entries.count + 1)" }

  func cache(_ entry: Entry) throws {
    if entries[entry.imageId] == nil && entries.count >= config.maxCacheSize {
      throw Failure(code: "CACHE_FULL", message: "Capture cache is full")
    }
    if entries[entry.imageId] == nil { order.append(entry.imageId) }
    entries[entry.imageId] = entry
  }

  func capacity() -> [String: Any] {
    let staged = entries.values.reduce(0) { $0 + $1.bytes.count }
    let used = entries.count
    var map: [String: Any] = [
      "maxEntries": config.maxCacheSize, "usedEntries": used, "reservedEntries": 0, "readyEntries": used,
      "persistingEntries": 0, "pendingRetryEntries": 0, "stagedBytes": staged,
      "maxStagedBytes": config.maxCacheSize * config.resolutionWidth * config.resolutionHeight * 4,
      "canCapture": used < config.maxCacheSize,
    ]
    map["blockedReason"] = used < config.maxCacheSize ? NSNull() : "cacheFull"
    return map
  }

  func data(_ imageId: String, format: String) throws -> Data {
    try requireFormat(format, imageId: imageId)
    return try entry(imageId).bytes
  }

  func size(_ imageId: String) throws -> [String: Any] {
    let value = try entry(imageId)
    return ["width": value.width, "height": value.height, "bytesPerPixel": 1, "totalBytes": value.bytes.count]
  }

  func save(_ imageId: String, to path: String, format: String) throws -> Bool {
    try requireFormat(format, imageId: imageId)
    let lower = path.lowercased()
    guard format == "heic" ? lower.hasSuffix(".heic") : (lower.hasSuffix(".jpg") || lower.hasSuffix(".jpeg")) else {
      throw Failure(code: "FORMAT_MISMATCH", message: "The destination extension must match the captured format")
    }
    let url = URL(fileURLWithPath: path)
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try entry(imageId).bytes.write(to: url)
    return true
  }

  /// Writes `<root>/<sessionFolder>/<baseName>.jpg.part` and releases the slot.
  func persist(_ imageId: String, root: String, sessionFolder: String, baseName: String, format: String) throws -> [String: Any] {
    try requireFormat(format, imageId: imageId)
    guard !baseName.trimmingCharacters(in: .whitespaces).isEmpty else { throw Failure(code: "CONFIG_INVALID", message: "baseName is required") }
    let value = try entry(imageId)
    var directory = URL(fileURLWithPath: root, isDirectory: true)
    if !sessionFolder.trimmingCharacters(in: .whitespaces).isEmpty { directory.appendPathComponent(sessionFolder, isDirectory: true) }
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let ext = format == "heic" ? "heic" : "jpg"
    let part = directory.appendingPathComponent("\(baseName).\(ext).part")
    try value.bytes.write(to: part)
    remove(imageId)
    return [
      "files": [format: part.path],
      "sizes": [format: value.bytes.count],
      "hashes": [format: DurableStoreEncoding.hex(DurableStoreEncoding.sha256([UInt8](value.bytes)))],
    ]
  }

  func discard(_ imageId: String) -> Bool { remove(imageId) }

  func clear() {
    entries.removeAll()
    order.removeAll()
  }

  @discardableResult
  private func remove(_ imageId: String) -> Bool {
    order.removeAll { $0 == imageId }
    return entries.removeValue(forKey: imageId) != nil
  }

  private func entry(_ imageId: String) throws -> Entry {
    guard let value = entries[imageId] else { throw Failure(code: "IMAGE_NOT_FOUND", message: "No cached capture exists for imageId=\(imageId)") }
    return value
  }

  private func requireFormat(_ format: String, imageId: String) throws {
    guard format == (try entry(imageId)).format else { throw Failure(code: "FORMAT_NOT_CAPTURED", message: "Requested format differs from the staged image") }
  }
}
