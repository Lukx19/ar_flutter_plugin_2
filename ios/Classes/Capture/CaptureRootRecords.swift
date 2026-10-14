import Foundation

struct CaptureRootComponent: Hashable {
  var kind: String
  var length: Int64
  var hash: String
}

/// One immutable root record (`objects/<sha256>.root`).
struct CaptureRootMetadata: Hashable {
  var schema: String
  var revision: Int64?
  var request: String?
  var previous: String?
  var secondPrevious: String?
  var components: [CaptureRootComponent]
}

/// One alternating pointer slot (`root-A.ptr`, `root-B.ptr`).
struct CaptureRootPointer: Hashable {
  var revision: Int64
  var hash: String
  var commit: String
}

/// Parser of root records and pointer slots (Android
/// `CaptureRootMetadataReaderV2`). Every read verifies the content hash; at
/// most 64 KiB are read. Schema 5 (plain) is the format this platform writes
/// and reads; schema 6 (quality-assessed originals, an Android debug producer)
/// is reported as unreadable.
enum CaptureRootRecords {
  static let maxMetadataBytes = 64 * 1024

  static func parseRoot(expectedHash: String, bytes: [UInt8]) -> CaptureRootMetadata? {
    guard DurableStoreEncoding.isHash(expectedHash), bytes.count <= maxMetadataBytes else { return nil }
    guard DurableStoreEncoding.hex(DurableStoreEncoding.sha256(bytes)) == expectedHash else { return nil }
    var schema: String?
    var revision: Int64?
    var request: String?
    var previous: String?
    var secondPrevious: String?
    var keys: [String: Int] = [:]
    var components: [CaptureRootComponent] = []
    var malformedComponent = false
    for line in lines(bytes) {
      guard let separator = line.firstIndex(of: "=") else { continue }
      let key = String(line[..<separator])
      let value = String(line[line.index(after: separator)...])
      if key != "component" { keys[key, default: 0] += 1 }
      switch key {
      case "schema": schema = value
      case "revision": revision = Int64(value)
      case "request": request = value
      case "previous": previous = value
      case "secondPrevious": secondPrevious = value
      case "component":
        let parts = value.split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
        if parts.count == 3, !parts[0].isEmpty, let length = Int64(parts[1]), length >= 0, DurableStoreEncoding.isHash(parts[2]) {
          components.append(CaptureRootComponent(kind: parts[0], length: length, hash: parts[2]))
        } else {
          malformedComponent = true
        }
      default: break
      }
    }
    if malformedComponent || (keys["schema"] ?? 0) > 1 { return nil }
    guard schema == "5" else { return nil }
    if keys.keys.contains(where: { $0.hasPrefix("quality") || $0 == "exposureTimestampNanoseconds" }) { return nil }
    return CaptureRootMetadata(
      schema: "5", revision: revision, request: request, previous: previous,
      secondPrevious: secondPrevious, components: components)
  }

  static func parsePointer(_ bytes: [UInt8]) -> CaptureRootPointer? {
    guard bytes.count <= maxMetadataBytes else { return nil }
    let all = lines(bytes)
    guard all.count == 3, let revision = Int64(all[0]), DurableStoreEncoding.isHash(all[1]) else { return nil }
    return CaptureRootPointer(revision: revision, hash: all[1], commit: all[2])
  }

  /// Lines split at LF with a trailing CR removed; a final line without LF counts.
  private static func lines(_ bytes: [UInt8]) -> [String] {
    var out: [String] = []
    var start = 0
    for index in 0..<bytes.count where bytes[index] == 0x0a {
      let end = index > start && bytes[index - 1] == 0x0d ? index - 1 : index
      out.append(String(decoding: bytes[start..<end], as: UTF8.self))
      start = index + 1
    }
    if start < bytes.count { out.append(String(decoding: bytes[start...], as: UTF8.self)) }
    return out
  }
}
