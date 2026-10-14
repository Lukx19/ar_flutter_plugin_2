import Foundation
import XCTest

/// Shared fixtures of the plugin's Swift tests.
///
/// The tests under `ios/Tests/**` compile into the app's `RunnerTests`
/// target (a file-system-synchronized group in
/// `capture3d/ios/Runner.xcodeproj`), so a new file in any subfolder is
/// picked up without a project edit. They reach plugin internals through
/// `@testable import ar_flutter_plugin_2` (the pod is built with testability
/// in Debug). The golden contract files of
/// `plugins/ar_flutter_plugin_2/test_assets/visibility_grid/` are copied into
/// the test bundle as the `visibility_grid` folder.
enum GoldenFixture {
  /// Folder of the golden contract files inside the test bundle.
  static let folder = "visibility_grid"

  /// Bundle that holds the test classes and their resources.
  static var bundle: Bundle { Bundle(for: BundleToken.self) }

  /// The golden folder in the test bundle.
  static func folderURL() throws -> URL {
    guard let base = bundle.resourceURL?.appendingPathComponent(folder, isDirectory: true),
          FileManager.default.fileExists(atPath: base.path) else {
      throw GoldenFixtureError.missing("\(folder)/ in \(bundle.bundlePath)")
    }
    return base
  }

  /// URL of the golden file [name] (for example `ingest_points.bin`).
  static func url(_ name: String) throws -> URL {
    let url = try folderURL().appendingPathComponent(name)
    guard FileManager.default.fileExists(atPath: url.path) else {
      throw GoldenFixtureError.missing(url.path)
    }
    return url
  }

  /// Bytes of the golden file [name].
  static func bytes(_ name: String) throws -> GoldenBytes {
    GoldenBytes(data: try Data(contentsOf: url(name)))
  }

  /// Names of every `.bin` golden in the bundle, sorted.
  static func allNames() throws -> [String] {
    try FileManager.default.contentsOfDirectory(atPath: folderURL().path)
      .filter { $0.hasSuffix(".bin") }
      .sorted()
  }

  /// Contract magic of a four-character tag as stored on the wire: the
  /// tag's bytes in order, read as a little-endian u32 (Dart `contractMagic`).
  static func magic(_ tag: String) -> Int {
    let b = Array(tag.utf8)
    precondition(b.count == 4, "a contract tag has four characters")
    return Int(b[0]) | Int(b[1]) << 8 | Int(b[2]) << 16 | Int(b[3]) << 24
  }

  private final class BundleToken {}
}

enum GoldenFixtureError: Error, CustomStringConvertible {
  case missing(String)

  var description: String {
    switch self {
    case .missing(let path): return "golden fixture missing: \(path)"
    }
  }
}

/// Little-endian reads of contract bytes (every contract field is
/// little-endian; see `visibility_grid_contract/binary_support.dart`).
struct GoldenBytes {
  let data: Data

  var count: Int { data.count }

  func u8(_ offset: Int) -> Int {
    Int(data[data.startIndex + offset])
  }

  func u16(_ offset: Int) -> Int {
    u8(offset) | u8(offset + 1) << 8
  }

  func u32(_ offset: Int) -> Int {
    u16(offset) | u16(offset + 2) << 16
  }

  func f32(_ offset: Int) -> Float {
    Float(bitPattern: UInt32(u32(offset)))
  }

  /// Bytes [offset, offset + length) as their own reader.
  func slice(_ offset: Int, _ length: Int) -> GoldenBytes {
    let start = data.startIndex + offset
    return GoldenBytes(data: data.subdata(in: start..<(start + length)))
  }
}
