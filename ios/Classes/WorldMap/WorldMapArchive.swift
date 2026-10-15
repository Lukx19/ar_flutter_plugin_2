import ARKit
import CryptoKit
import Foundation

/// Stable failure codes of the world map channel and store.
enum WorldMapFailureCode {
  static let noSession = "WORLD_MAP_NO_SESSION"
  static let notAvailable = "WORLD_MAP_NOT_AVAILABLE"
  static let invalidPath = "WORLD_MAP_INVALID_PATH"
  static let writeFailed = "WORLD_MAP_WRITE_FAILED"
  static let readFailed = "WORLD_MAP_READ_FAILED"
  static let checksumMismatch = "WORLD_MAP_CHECKSUM_MISMATCH"
  static let decodeFailed = "WORLD_MAP_DECODE_FAILED"
}

struct WorldMapError: Error, Equatable {
  let code: String
  let message: String
}

/// `ARWorldMap` to bytes and back with secure keyed archiving (the format
/// Apple documents for persisting a world map), plus the SHA-256 the session
/// manifest records for every referenced file.
enum WorldMapArchive {
  static func encode(_ map: ARWorldMap) throws -> Data {
    do {
      return try NSKeyedArchiver.archivedData(withRootObject: map, requiringSecureCoding: true)
    } catch {
      throw WorldMapError(code: WorldMapFailureCode.writeFailed, message: "archive: \(error)")
    }
  }

  static func decode(_ data: Data) throws -> ARWorldMap {
    let map: ARWorldMap?
    do {
      map = try NSKeyedUnarchiver.unarchivedObject(ofClass: ARWorldMap.self, from: data)
    } catch {
      throw WorldMapError(code: WorldMapFailureCode.decodeFailed, message: "unarchive: \(error)")
    }
    guard let decoded = map else {
      throw WorldMapError(code: WorldMapFailureCode.decodeFailed, message: "not a world map archive")
    }
    return decoded
  }

  static func sha256Hex(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
  }

  /// Scalar summary of [map] for channel replies and the session log.
  static func summary(_ map: ARWorldMap) -> [String: Any] {
    [
      "anchors": map.anchors.count,
      "featurePoints": map.rawFeaturePoints.points.count,
      "center": [Double(map.center.x), Double(map.center.y), Double(map.center.z)],
      "extent": [Double(map.extent.x), Double(map.extent.y), Double(map.extent.z)],
    ]
  }

  /// Stable name of a world mapping status.
  static func mappingStatusName(_ status: ARFrame.WorldMappingStatus) -> String {
    switch status {
    case .notAvailable: return "notAvailable"
    case .limited: return "limited"
    case .extending: return "extending"
    case .mapped: return "mapped"
    @unknown default: return "unknown"
    }
  }
}

/// A written world map file: [path] relative to the session folder.
struct WorldMapFileRef: Equatable {
  let path: String
  let sha256: String
  let bytes: Int
}

/// World map files inside a session folder. The app names the file (by
/// convention `groups/<groupId>/world_map.arworldmap`, next to the group's
/// other files); the store only accepts a relative path that stays inside the
/// session folder, writes it durably (temporary file, `F_FULLFSYNC`, rename,
/// folder sync) and verifies the SHA-256 on read.
enum WorldMapFileStore {
  static let fileName = "world_map.arworldmap"

  /// [relativePath] resolved under [sessionDirectory], or nil when it is
  /// empty, absolute, or leaves the folder (`..`, `.`).
  static func resolve(sessionDirectory: String, relativePath: String) -> URL? {
    guard !sessionDirectory.isEmpty, sessionDirectory.hasPrefix("/"),
          !relativePath.isEmpty, !relativePath.hasPrefix("/")
    else { return nil }
    let components = relativePath.split(separator: "/", omittingEmptySubsequences: false)
    guard components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else { return nil }
    return URL(fileURLWithPath: sessionDirectory, isDirectory: true).appendingPathComponent(relativePath)
  }

  static func write(_ data: Data, sessionDirectory: String, relativePath: String) throws -> WorldMapFileRef {
    guard let target = resolve(sessionDirectory: sessionDirectory, relativePath: relativePath) else {
      throw WorldMapError(code: WorldMapFailureCode.invalidPath, message: "invalid world map path")
    }
    let folder = target.deletingLastPathComponent()
    do {
      try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    } catch {
      throw WorldMapError(code: WorldMapFailureCode.writeFailed, message: "folder: \(error)")
    }
    let temporary = folder.appendingPathComponent(".\(target.lastPathComponent).\(UUID().uuidString).tmp")
    do {
      try writeSynced(data, to: temporary)
      guard rename(temporary.path, target.path) == 0 else {
        throw WorldMapError(code: WorldMapFailureCode.writeFailed, message: "rename errno \(errno)")
      }
      syncFolder(folder)
    } catch {
      unlink(temporary.path)
      if let mapError = error as? WorldMapError { throw mapError }
      throw WorldMapError(code: WorldMapFailureCode.writeFailed, message: "\(error)")
    }
    return WorldMapFileRef(path: relativePath, sha256: WorldMapArchive.sha256Hex(data), bytes: data.count)
  }

  /// The file's bytes; a non-nil [expectedSha256] must match.
  static func read(sessionDirectory: String, relativePath: String, expectedSha256: String?) throws -> Data {
    guard let source = resolve(sessionDirectory: sessionDirectory, relativePath: relativePath) else {
      throw WorldMapError(code: WorldMapFailureCode.invalidPath, message: "invalid world map path")
    }
    let data: Data
    do {
      data = try Data(contentsOf: source)
    } catch {
      throw WorldMapError(code: WorldMapFailureCode.readFailed, message: "\(error)")
    }
    if let expected = expectedSha256, !expected.isEmpty,
       WorldMapArchive.sha256Hex(data) != expected.lowercased() {
      throw WorldMapError(code: WorldMapFailureCode.checksumMismatch, message: "sha256 differs from the manifest")
    }
    return data
  }

  private static func writeSynced(_ data: Data, to url: URL) throws {
    let fd = open(url.path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0o644)
    guard fd >= 0 else {
      throw WorldMapError(code: WorldMapFailureCode.writeFailed, message: "open errno \(errno)")
    }
    defer { close(fd) }
    try data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
      var offset = 0
      while offset < raw.count {
        let n = Darwin.write(fd, raw.baseAddress!.advanced(by: offset), raw.count - offset)
        if n < 0 {
          if errno == EINTR { continue }
          throw WorldMapError(code: WorldMapFailureCode.writeFailed, message: "write errno \(errno)")
        }
        offset += n
      }
    }
    // F_FULLFSYNC flushes the drive cache; fall back to fsync where refused.
    if fcntl(fd, F_FULLFSYNC) != 0 && fsync(fd) != 0 {
      throw WorldMapError(code: WorldMapFailureCode.writeFailed, message: "sync errno \(errno)")
    }
  }

  private static func syncFolder(_ folder: URL) {
    let fd = open(folder.path, O_RDONLY | O_CLOEXEC)
    guard fd >= 0 else { return }
    if fcntl(fd, F_FULLFSYNC) != 0 { _ = fsync(fd) }
    close(fd)
  }
}
