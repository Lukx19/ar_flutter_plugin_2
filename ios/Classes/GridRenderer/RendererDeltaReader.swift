import Foundation

/// Outcome of validating one renderer delta message.
enum DeltaStatus: Equatable {
  case ok, tooShort, badMagic, badVersion, badKind, badLength, tooLarge
}

/// Reusable cursor over one renderer delta message (contract section 2.7)
/// in little-endian bytes. [attach] moves it without allocating; the bytes
/// must stay valid while the reader is used.
struct RendererDeltaReader {
  private(set) var bytes = UnsafeRawBufferPointer(start: nil, count: 0)

  /// Points the reader at the message [message] (exactly its bytes).
  mutating func attach(_ message: UnsafeRawBufferPointer) {
    bytes = message
  }

  func validate() -> DeltaStatus {
    guard bytes.count >= RendererDeltaLayout.headerBytes, bytes.baseAddress != nil else { return .tooShort }
    if bytes.leU32(0) != RendererDeltaLayout.magic { return .badMagic }
    if bytes.le16(4) != RendererDeltaLayout.version { return .badVersion }
    let size = RendererDeltaLayout.recordBytes(kind)
    if size < 0 { return .badKind }
    let count = recordCount
    if count < 0 || count > RendererDeltaLayout.maxMessageBytes { return .tooLarge }
    let total = RendererDeltaLayout.headerBytes + count * size
    if total > RendererDeltaLayout.maxMessageBytes { return .tooLarge }
    if total != bytes.count { return .badLength }
    return .ok
  }

  var kind: Int { bytes.le8(RendererDeltaLayout.kind) }
  var gridVersion: UInt32 { bytes.leU32(RendererDeltaLayout.gridVersion) }
  var firstSlot: Int { bytes.leI32(RendererDeltaLayout.firstSlot) }
  /// Record count as a signed i32 (negative values are rejected by [validate]).
  var recordCount: Int { bytes.leI32(RendererDeltaLayout.recordCount) }
  var bufferCapacity: Int { bytes.leI32(RendererDeltaLayout.bufferCapacity) }
  var brickKey: Int64 { bytes.leI64(RendererDeltaLayout.brickKey) }

  /// Byte size of one record of this message's kind.
  var recordBytes: Int { RendererDeltaLayout.recordBytes(kind) }

  /// Offset of the first record within the message.
  var recordsOffset: Int { RendererDeltaLayout.headerBytes }

  /// Bytes of all records of this message.
  var recordsLength: Int { recordCount * recordBytes }

  /// The records as their own byte range.
  var records: UnsafeRawBufferPointer {
    UnsafeRawBufferPointer(rebasing: bytes[recordsOffset..<(recordsOffset + recordsLength)])
  }
}
