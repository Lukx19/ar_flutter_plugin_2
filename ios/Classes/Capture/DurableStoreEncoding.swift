import CryptoKit
import Foundation

/// Byte encodings of the durable capture store that must equal the Android
/// writer (`DurableSessionStoreV2.kt`): SHA-256 hex names, Java
/// `DataOutputStream` canonical hashes, `java.util.Properties` records and Java
/// `Double.toString` text.
enum DurableStoreEncoding {
  // MARK: Hashes

  static func sha256(_ bytes: [UInt8]) -> [UInt8] { Array(SHA256.hash(data: bytes)) }
  static func sha256(_ text: String) -> [UInt8] { sha256(Array(text.utf8)) }

  static func hex(_ bytes: [UInt8]) -> String {
    let digits = Array("0123456789abcdef".utf8)
    var out = [UInt8]()
    out.reserveCapacity(bytes.count * 2)
    for byte in bytes {
      out.append(digits[Int(byte >> 4)])
      out.append(digits[Int(byte & 0x0f)])
    }
    return String(decoding: out, as: UTF8.self)
  }

  /// Lowercase 64-digit SHA-256 hex.
  static func isHash(_ value: String?) -> Bool {
    guard let value = value, value.utf8.count == 64 else { return false }
    return value.utf8.allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x66) }
  }

  /// Directory and capture names: SHA-256 hex of the UTF-8 identity (`safe`).
  static func safeName(_ value: String) -> String { hex(sha256(value)) }

  static func acceptedHash(_ attempt: CaptureAcceptedAttempt) -> String {
    hex(sha256("\(attempt.identity.commitId)|\(attempt.identity.attemptId)|\(attempt.reservation.totalStoreLiability)|\(hex(attempt.canonicalIntentHash))"))
  }

  static func templateHash(_ template: CapturePostOutputTemplate) -> String {
    hex(sha256("\(acceptedHash(template.accepted))|\(hex(template.poseRecordHash))|\(hex(template.cameraModelHash))|\(hex(template.validationRecordHash))|\(hex(template.ledgerRecordHash))"))
  }

  static func receiptHash(requestHash: String, rootHash: String) -> String {
    hex(sha256("\(requestHash)|\(rootHash)"))
  }

  static func reservationOwner(_ identity: CaptureAttemptIdentity) -> String {
    "capture:\(safeName(identity.lifecycleCut.sessionId)):\(safeName(identity.commitId))"
  }

  /// Canonical structural hash of every request field (Android `requestHash`).
  static func requestHash(_ request: CaptureCommitRequest) -> String {
    var out = JavaDataOutput()
    func text(_ value: String) {
      let encoded = Array(value.utf8)
      out.writeInt(Int32(encoded.count))
      out.write(encoded)
    }
    func digest(_ value: [UInt8]) {
      out.writeInt(Int32(value.count))
      out.write(value)
    }
    let accepted = request.accepted
    let identity = accepted.identity
    let cut = identity.lifecycleCut
    text(identity.attemptId); text(identity.commitId); out.writeLong(identity.attemptOrdinal)
    text(cut.sessionId); out.writeLong(cut.sessionGeneration); text(cut.groupId)
    out.writeLong(cut.groupGeneration); text(cut.arSessionId); text(cut.viewId)
    out.writeLong(cut.viewGeneration); text(cut.bindingToken)
    out.writeLong(cut.lifecycleSequence); out.writeLong(cut.operationGeneration)
    out.writeInt(Int32(accepted.lane.rawValue))
    text(accepted.profile.profileId)
    let kinds = accepted.profile.requiredComponents.sorted()
    out.writeInt(Int32(kinds.count))
    kinds.forEach { out.writeInt(Int32($0.rawValue)) }
    out.writeLong(accepted.profile.maximumComponentBytes)
    out.writeLong(accepted.profile.maximumWorkingBytes)
    out.writeLong(accepted.reservation.memoryBytes)
    out.writeLong(accepted.reservation.physicalStoreBytes)
    out.writeLong(accepted.reservation.componentEntries)
    out.writeLong(accepted.reservation.terminalEntries)
    out.writeLong(accepted.reservation.rollbackBytes)
    out.writeBoolean(accepted.reservation.physicallyBacked)
    digest(accepted.canonicalIntentHash); digest(accepted.acceptedReceiptHash)
    let components = request.components.sorted { $0.kind < $1.kind }
    out.writeInt(Int32(components.count))
    for component in components {
      out.writeInt(Int32(component.kind.rawValue)); out.writeLong(component.byteLength)
      digest(component.sha256); text(component.durableObjectId)
    }
    out.writeLong(request.exposureTimestampNanoseconds)
    digest(request.poseRecordHash); digest(request.cameraModelHash)
    digest(request.validationRecordHash); digest(request.ledgerRecordHash)
    return hex(sha256(out.bytes))
  }

  /// `acceptedIdentity` property: Base64 of the Java `writeUTF`/`writeLong`
  /// encoding of the exact attempt identity (version 1).
  static func exactIdentityBase64(_ identity: CaptureAttemptIdentity) -> String {
    var out = JavaDataOutput()
    out.writeUTF(identity.attemptId); out.writeUTF(identity.commitId); out.writeLong(identity.attemptOrdinal)
    let cut = identity.lifecycleCut
    out.writeUTF(cut.sessionId); out.writeLong(cut.sessionGeneration)
    out.writeUTF(cut.groupId); out.writeLong(cut.groupGeneration)
    out.writeUTF(cut.arSessionId); out.writeUTF(cut.viewId); out.writeLong(cut.viewGeneration)
    out.writeUTF(cut.bindingToken); out.writeLong(cut.lifecycleSequence); out.writeLong(cut.operationGeneration)
    return Data(out.bytes).base64EncodedString()
  }

  /// Inverse of [exactIdentityBase64]; nil when malformed.
  static func exactIdentity(base64: String) -> CaptureAttemptIdentity? {
    guard let data = Data(base64Encoded: base64) else { return nil }
    var input = JavaDataInput(Array(data))
    guard let attemptId = input.readUTF(), let commitId = input.readUTF(), let ordinal = input.readLong(),
          let sessionId = input.readUTF(), let sessionGeneration = input.readLong(),
          let groupId = input.readUTF(), let groupGeneration = input.readLong(),
          let arSessionId = input.readUTF(), let viewId = input.readUTF(), let viewGeneration = input.readLong(),
          let bindingToken = input.readUTF(), let sequence = input.readLong(), let operation = input.readLong(),
          input.isAtEnd
    else { return nil }
    return CaptureAttemptIdentity(
      attemptId: attemptId, commitId: commitId, attemptOrdinal: ordinal,
      lifecycleCut: CaptureLifecycleCut(
        sessionId: sessionId, sessionGeneration: sessionGeneration, groupId: groupId,
        groupGeneration: groupGeneration, arSessionId: arSessionId, viewId: viewId,
        viewGeneration: viewGeneration, bindingToken: bindingToken, lifecycleSequence: sequence,
        operationGeneration: operation))
  }

  // MARK: Properties

  /// Record bytes: `key=value\n` for every key in ascending order, no escaping
  /// (Android `propertiesBytes`).
  static func propertiesBytes(_ values: [String: String]) -> [UInt8] {
    var text = ""
    for key in values.keys.sorted() { text += "\(key)=\(values[key]!)\n" }
    return Array(text.utf8)
  }

  /// `java.util.Properties.load` of a UTF-8 record (the Android writer only
  /// emits ASCII, so ISO 8859-1 and UTF-8 agree).
  static func parseProperties(_ bytes: [UInt8]) -> [String: String] {
    let text = String(decoding: bytes, as: UTF8.self)
    var result: [String: String] = [:]
    var logical: [Character] = []
    var lines = text.split(omittingEmptySubsequences: false, whereSeparator: { $0 == "\n" || $0 == "\r" || $0 == "\r\n" })
      .map { Array($0) }
    var index = 0
    while index < lines.count {
      var line = lines[index]
      index += 1
      // Leading whitespace of every natural line is ignored.
      while let first = line.first, first == " " || first == "\t" || first == "\u{0C}" { line.removeFirst() }
      if logical.isEmpty {
        if line.isEmpty || line.first == "#" || line.first == "!" { continue }
      }
      var trailing = 0
      for character in line.reversed() { if character == "\\" { trailing += 1 } else { break } }
      if trailing % 2 == 1 {
        logical += line.dropLast()
        if index < lines.count { continue }
      } else {
        logical += line
      }
      let (key, value) = splitPropertyLine(logical)
      result[key] = value
      logical = []
    }
    lines.removeAll()
    return result
  }

  private static func splitPropertyLine(_ line: [Character]) -> (String, String) {
    var keyEnd = line.count
    var valueStart = line.count
    var escaped = false
    var hasSeparator = false
    for (i, c) in line.enumerated() {
      if escaped { escaped = false; continue }
      if c == "\\" { escaped = true; continue }
      if c == "=" || c == ":" { keyEnd = i; valueStart = i + 1; hasSeparator = true; break }
      if c == " " || c == "\t" || c == "\u{0C}" { keyEnd = i; valueStart = i + 1; break }
    }
    var v = valueStart
    while v < line.count, line[v] == " " || line[v] == "\t" || line[v] == "\u{0C}" { v += 1 }
    if !hasSeparator, v < line.count, line[v] == "=" || line[v] == ":" {
      v += 1
      while v < line.count, line[v] == " " || line[v] == "\t" || line[v] == "\u{0C}" { v += 1 }
    }
    return (unescape(Array(line[0..<keyEnd])), unescape(Array(line[min(v, line.count)...])))
  }

  private static func unescape(_ chars: [Character]) -> String {
    var out = ""
    var i = 0
    while i < chars.count {
      let c = chars[i]
      if c == "\\", i + 1 < chars.count {
        let next = chars[i + 1]
        i += 2
        switch next {
        case "t": out.append("\t")
        case "n": out.append("\n")
        case "r": out.append("\r")
        case "f": out.append("\u{0C}")
        case "u":
          if i + 4 <= chars.count, let code = UInt32(String(chars[i..<i + 4]), radix: 16), let scalar = Unicode.Scalar(code) {
            out.unicodeScalars.append(scalar)
            i += 4
          }
        default: out.append(next)
        }
      } else {
        out.append(c)
        i += 1
      }
    }
    return out
  }

  // MARK: Java number text

  /// Java `Double.toString`: shortest round-trip digits, plain notation for
  /// 1e-3 <= |d| < 1e7 with at least one fraction digit, otherwise
  /// `d.dddE[-]n`.
  static func javaDouble(_ value: Double) -> String {
    if value.isNaN { return "NaN" }
    if value.isInfinite { return value < 0 ? "-Infinity" : "Infinity" }
    if value == 0 { return value.sign == .minus ? "-0.0" : "0.0" }
    let negative = value < 0
    let (digits, exponent) = shortestDigits(abs(value))
    // value = 0.digits * 10^exponent
    var text: String
    let magnitude = abs(value)
    if magnitude >= 1e-3 && magnitude < 1e7 {
      if exponent <= 0 {
        text = "0." + String(repeating: "0", count: -exponent) + digits
      } else if exponent >= digits.count {
        text = digits + String(repeating: "0", count: exponent - digits.count) + ".0"
      } else {
        let split = digits.index(digits.startIndex, offsetBy: exponent)
        text = String(digits[..<split]) + "." + String(digits[split...])
      }
    } else {
      let first = String(digits.prefix(1))
      let rest = digits.count > 1 ? String(digits.dropFirst()) : "0"
      text = "\(first).\(rest)E\(exponent - 1)"
    }
    return negative ? "-" + text : text
  }

  /// Shortest round-trip decimal digits of a positive finite double and the
  /// exponent e with value = 0.d1d2... * 10^e.
  private static func shortestDigits(_ value: Double) -> (String, Int) {
    // Swift's description is the shortest round-trip representation.
    let description = "\(value)"
    var mantissa = description
    var exponent = 0
    if let e = description.firstIndex(where: { $0 == "e" || $0 == "E" }) {
      mantissa = String(description[..<e])
      exponent = Int(description[description.index(after: e)...]) ?? 0
    }
    var intPart = mantissa
    var fracPart = ""
    if let dot = mantissa.firstIndex(of: ".") {
      intPart = String(mantissa[..<dot])
      fracPart = String(mantissa[mantissa.index(after: dot)...])
    }
    var digits = intPart + fracPart
    var pointPosition = intPart.count + exponent
    while digits.hasPrefix("0") && digits.count > 1 {
      digits.removeFirst()
      pointPosition -= 1
    }
    while digits.hasSuffix("0") && digits.count > 1 { digits.removeLast() }
    return (digits, pointPosition)
  }

  static func javaDoubles(_ values: [Double]) -> String { values.map(javaDouble).joined(separator: ",") }
}

/// Big-endian Java `DataOutputStream`.
struct JavaDataOutput {
  private(set) var bytes: [UInt8] = []

  mutating func write(_ value: [UInt8]) { bytes += value }
  mutating func writeBoolean(_ value: Bool) { bytes.append(value ? 1 : 0) }
  mutating func writeShort(_ value: UInt16) { bytes += [UInt8(value >> 8), UInt8(value & 0xff)] }
  mutating func writeInt(_ value: Int32) {
    let bits = UInt32(bitPattern: value)
    for shift in stride(from: 24, through: 0, by: -8) { bytes.append(UInt8(truncatingIfNeeded: bits >> UInt32(shift))) }
  }
  mutating func writeLong(_ value: Int64) {
    let bits = UInt64(bitPattern: value)
    for shift in stride(from: 56, through: 0, by: -8) { bytes.append(UInt8(truncatingIfNeeded: bits >> UInt64(shift))) }
  }

  /// Modified UTF-8 with a u16 length (Java `writeUTF`).
  mutating func writeUTF(_ value: String) {
    var encoded: [UInt8] = []
    for unit in value.utf16 {
      switch unit {
      case 0x0001...0x007f: encoded.append(UInt8(unit))
      case 0x0000, 0x0080...0x07ff:
        encoded.append(UInt8(0xc0 | ((unit >> 6) & 0x1f)))
        encoded.append(UInt8(0x80 | (unit & 0x3f)))
      default:
        encoded.append(UInt8(0xe0 | ((unit >> 12) & 0x0f)))
        encoded.append(UInt8(0x80 | ((unit >> 6) & 0x3f)))
        encoded.append(UInt8(0x80 | (unit & 0x3f)))
      }
    }
    precondition(encoded.count <= 65_535, "writeUTF value too long")
    writeShort(UInt16(encoded.count))
    bytes += encoded
  }
}

/// Big-endian Java `DataInputStream` reader of the identity record.
struct JavaDataInput {
  private let bytes: [UInt8]
  private var offset = 0

  init(_ bytes: [UInt8]) { self.bytes = bytes }

  var isAtEnd: Bool { offset == bytes.count }

  mutating func readLong() -> Int64? {
    guard offset + 8 <= bytes.count else { return nil }
    var bits: UInt64 = 0
    for i in 0..<8 { bits = bits << 8 | UInt64(bytes[offset + i]) }
    offset += 8
    return Int64(bitPattern: bits)
  }

  mutating func readUTF() -> String? {
    guard offset + 2 <= bytes.count else { return nil }
    let length = Int(bytes[offset]) << 8 | Int(bytes[offset + 1])
    offset += 2
    guard offset + length <= bytes.count else { return nil }
    let encoded = bytes[offset..<offset + length]
    offset += length
    var units: [UInt16] = []
    var i = encoded.startIndex
    while i < encoded.endIndex {
      let a = UInt16(encoded[i])
      if a & 0x80 == 0 {
        units.append(a); i += 1
      } else if a & 0xe0 == 0xc0, i + 1 < encoded.endIndex {
        units.append((a & 0x1f) << 6 | UInt16(encoded[i + 1]) & 0x3f); i += 2
      } else if a & 0xf0 == 0xe0, i + 2 < encoded.endIndex {
        units.append((a & 0x0f) << 12 | (UInt16(encoded[i + 1]) & 0x3f) << 6 | UInt16(encoded[i + 2]) & 0x3f); i += 3
      } else {
        return nil
      }
    }
    return String(utf16CodeUnits: units, count: units.count)
  }
}
