import Foundation
import XCTest

@testable import ar_flutter_plugin_2

/// Byte-array mirror of the GPU buffers, counting every operation (the
/// Swift twin of the Android test target).
final class ByteArrayGpuTarget: GpuBufferTarget {
  var uploadCapacity: Int
  private(set) var bytes: [[UInt8]] = Array(repeating: [], count: GridBuffer.allCases.count)
  private(set) var writes = 0
  private(set) var clears = 0
  private(set) var grows = 0
  private(set) var writtenBytes = 0
  private(set) var frameWritten = 0
  /// Throws on the next growth when set (allocation failure).
  var failNextGrowth = false

  init(uploadCapacity: Int = Int.max) {
    self.uploadCapacity = uploadCapacity
  }

  var operations: Int { writes + clears + grows }

  func buffer(_ buffer: GridBuffer) -> [UInt8] { bytes[buffer.rawValue] }

  func beginFrame() {
    frameWritten = 0
  }

  func ensureCapacity(_ buffer: GridBuffer, bytes count: Int) throws {
    if failNextGrowth {
      failNextGrowth = false
      throw GridRendererAllocationError(buffer: "\(buffer)", bytes: count)
    }
    let old = bytes[buffer.rawValue]
    if count <= old.count { return }
    bytes[buffer.rawValue] = old + [UInt8](repeating: 0, count: count - old.count)
    grows += 1
  }

  func write(_ buffer: GridBuffer, dstOffset: Int, source: UnsafeRawBufferPointer) {
    for i in 0..<source.count { bytes[buffer.rawValue][dstOffset + i] = source[i] }
    writes += 1
    writtenBytes += source.count
    frameWritten += source.count
  }

  func clear(_ buffer: GridBuffer, dstOffset: Int, length: Int) {
    for i in dstOffset..<(dstOffset + length) { bytes[buffer.rawValue][i] = 0 }
    clears += 1
    frameWritten += length
  }

  func frameUploadCapacity() -> Int { max(uploadCapacity == Int.max ? Int.max : uploadCapacity - frameWritten, 0) }
}

/// Fixtures of the grid renderer tests: contract goldens of
/// `test_assets/visibility_grid/` (test bundle, `VISIBILITY_GRID_GOLDENS`,
/// or the source tree) and the Dart exporter's room stream of
/// `test_assets/grid_renderer/` (source tree).
enum GridRendererFixtures {
  static func golden(_ name: String, file: StaticString = #filePath) throws -> Data {
    for bundle in Bundle.allBundles + [Bundle.main] {
      if let url = bundle.url(forResource: name, withExtension: nil, subdirectory: "visibility_grid") {
        return try Data(contentsOf: url)
      }
    }
    if let dir = ProcessInfo.processInfo.environment["VISIBILITY_GRID_GOLDENS"] {
      return try Data(contentsOf: URL(fileURLWithPath: dir).appendingPathComponent(name))
    }
    return try Data(contentsOf: pluginRoot(file).appendingPathComponent("test_assets/visibility_grid/\(name)"))
  }

  /// The length-prefixed room export stream (`room_export_stream.bin`,
  /// written by `renderer_delta_exporter_test.dart`: a 1.6 x 1.2 x 1.2 m
  /// room, 864 surfaces, nearest-K 64) split into messages.
  static func roomExportStream(file: StaticString = #filePath) throws -> [Data] {
    let data = try Data(contentsOf: pluginRoot(file).appendingPathComponent("test_assets/grid_renderer/room_export_stream.bin"))
    var messages: [Data] = []
    var at = 0
    while at < data.count {
      let length = data.withUnsafeBytes { Int($0.leU32(at)) }
      messages.append(data.subdata(in: (data.startIndex + at + 4)..<(data.startIndex + at + 4 + length)))
      at += 4 + length
    }
    XCTAssertEqual(at, data.count)
    return messages
  }

  private static func pluginRoot(_ file: StaticString) -> URL {
    // Resolved, so a test package of symlinks finds the source tree.
    URL(fileURLWithPath: "\(file)").resolvingSymlinksInPath()
      .deletingLastPathComponent()  // GridRenderer
      .deletingLastPathComponent()  // Tests
      .deletingLastPathComponent()  // ios
      .deletingLastPathComponent()  // plugin root
  }
}

extension GridRendererCore {
  /// Offers the writer's current message.
  func offer(_ writer: RendererDeltaWriter) -> DeltaIntake {
    writer.withMessage { onRenderMessage($0) }
  }

  /// Sends the writer's current message on the display path.
  func display(_ writer: RendererDeltaWriter) -> DisplayIntake {
    writer.withMessage { onDisplayMessage($0) }
  }

  func display(_ data: Data) -> DisplayIntake {
    data.withUnsafeBytes { onDisplayMessage($0) }
  }
}

extension InstanceBufferStore {
  @discardableResult
  func apply(_ data: Data) throws -> DeltaStatus {
    try data.withUnsafeBytes { try apply($0) }
  }
}

/// Little-endian reads of test bytes.
func gridLE16(_ b: [UInt8], _ o: Int) -> Int { Int(b[o]) | Int(b[o + 1]) << 8 }
func gridLEI16(_ b: [UInt8], _ o: Int) -> Int { Int(Int16(bitPattern: UInt16(gridLE16(b, o)))) }
func gridLE32(_ b: [UInt8], _ o: Int) -> UInt32 {
  UInt32(b[o]) | UInt32(b[o + 1]) << 8 | UInt32(b[o + 2]) << 16 | UInt32(b[o + 3]) << 24
}
func gridLEF32(_ b: [UInt8], _ o: Int) -> Float { Float(bitPattern: gridLE32(b, o)) }
