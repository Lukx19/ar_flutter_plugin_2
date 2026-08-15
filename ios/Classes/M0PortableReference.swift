import Foundation

enum M0PortableCodecError: Error, Equatable {
    case malformed(String)
    case crc
    case sequenceGap
    case sequenceStale
    case replayConflict
}

private struct M0ByteBuffer {
    var bytes: [UInt8]

    init(count: Int) {
        bytes = Array(repeating: 0, count: count)
    }

    mutating func putUInt16(_ value: UInt16, at offset: Int) {
        bytes[offset] = UInt8(truncatingIfNeeded: value)
        bytes[offset + 1] = UInt8(truncatingIfNeeded: value >> 8)
    }

    mutating func putUInt32(_ value: UInt32, at offset: Int) {
        for index in 0..<4 {
            bytes[offset + index] = UInt8(truncatingIfNeeded: value >> UInt32(index * 8))
        }
    }

    mutating func putUInt64(_ value: UInt64, at offset: Int) {
        for index in 0..<8 {
            bytes[offset + index] = UInt8(truncatingIfNeeded: value >> UInt64(index * 8))
        }
    }
}

private struct M0ByteReader {
    let bytes: [UInt8]

    func uint16(at offset: Int) -> UInt16 {
        UInt16(bytes[offset]) |
            (UInt16(bytes[offset + 1]) << 8)
    }

    func uint32(at offset: Int) -> UInt32 {
        var value: UInt32 = 0
        for index in 0..<4 {
            value |= UInt32(bytes[offset + index]) << UInt32(index * 8)
        }
        return value
    }

    func uint64(at offset: Int) -> UInt64 {
        var value: UInt64 = 0
        for index in 0..<8 {
            value |= UInt64(bytes[offset + index]) << UInt64(index * 8)
        }
        return value
    }
}

func m0PortableCRC32(_ bytes: [UInt8], zeroOffset: Int) -> UInt32 {
    var crc: UInt32 = 0xffffffff
    for index in bytes.indices {
        let byte = index >= zeroOffset && index < zeroOffset + 4
            ? UInt32(0)
            : UInt32(bytes[index])
        crc ^= byte
        for _ in 0..<8 {
            crc = (crc & 1) == 1
                ? (crc >> 1) ^ 0xedb88320
                : crc >> 1
        }
    }
    return crc ^ 0xffffffff
}

struct M0PortableRequestV2: Equatable {
    static let headerBytes = 80
    static let maximumBytes = 16 * 1024

    let requestFlags: UInt16
    let streamToken: UInt64
    let acknowledgedTransactionID: UInt64
    let acknowledgedGeometryRevision: UInt64
    let acknowledgedLineageRevision: UInt64
    let nextStyleRevision: UInt64
    let maximumResponseBytes: UInt32
    let styleRecords: [[UInt8]]
    let commandBytes: [UInt8]
    let requestSequence: UInt64

    func encode() throws -> [UInt8] {
        guard requestFlags <= 0x3f,
              streamToken > 0,
              requestSequence > 0,
              maximumResponseBytes >= 4096,
              maximumResponseBytes <= 16 * 1024,
              styleRecords.allSatisfy({ $0.count == 8 }),
              commandBytes.count <= 0xffff
        else {
            throw M0PortableCodecError.malformed("request fields")
        }
        let payloadBytes = styleRecords.count * 8 + commandBytes.count
        let packetBytes = Self.headerBytes + payloadBytes
        guard packetBytes <= Self.maximumBytes else {
            throw M0PortableCodecError.malformed("request ceiling")
        }
        var buffer = M0ByteBuffer(count: packetBytes)
        buffer.bytes.replaceSubrange(0..<4, with: Array("VGR2".utf8))
        buffer.putUInt16(2, at: 4)
        buffer.putUInt16(UInt16(Self.headerBytes), at: 6)
        buffer.putUInt16(requestFlags, at: 8)
        buffer.putUInt32(UInt32(packetBytes), at: 12)
        buffer.putUInt64(streamToken, at: 16)
        buffer.putUInt64(acknowledgedTransactionID, at: 24)
        buffer.putUInt64(acknowledgedGeometryRevision, at: 32)
        buffer.putUInt64(acknowledgedLineageRevision, at: 40)
        buffer.putUInt64(nextStyleRevision, at: 48)
        buffer.putUInt32(maximumResponseBytes, at: 56)
        buffer.putUInt16(UInt16(styleRecords.count), at: 60)
        buffer.putUInt16(UInt16(commandBytes.count), at: 62)
        buffer.putUInt64(requestSequence, at: 64)
        var offset = Self.headerBytes
        for record in styleRecords {
            buffer.bytes.replaceSubrange(offset..<(offset + record.count), with: record)
            offset += record.count
        }
        buffer.bytes.replaceSubrange(offset..<(offset + commandBytes.count), with: commandBytes)
        buffer.putUInt32(m0PortableCRC32(buffer.bytes, zeroOffset: 72), at: 72)
        return buffer.bytes
    }

    static func decode(_ bytes: [UInt8]) throws -> M0PortableRequestV2 {
        guard bytes.count >= headerBytes else {
            throw M0PortableCodecError.malformed("request header")
        }
        guard Array(bytes[0..<4]) == Array("VGR2".utf8) else {
            throw M0PortableCodecError.malformed("request magic")
        }
        let reader = M0ByteReader(bytes: bytes)
        guard reader.uint16(at: 4) == 2,
              reader.uint16(at: 6) == headerBytes,
              reader.uint16(at: 10) == 0,
              reader.uint32(at: 76) == 0,
              UInt64(reader.uint32(at: 12)) == UInt64(bytes.count),
              bytes.count <= maximumBytes
        else {
            throw M0PortableCodecError.malformed("request framing")
        }
        guard reader.uint32(at: 72) == m0PortableCRC32(bytes, zeroOffset: 72) else {
            throw M0PortableCodecError.crc
        }
        let styleCount = Int(reader.uint16(at: 60))
        let commandCount = Int(reader.uint16(at: 62))
        guard headerBytes + styleCount * 8 + commandCount == bytes.count else {
            throw M0PortableCodecError.malformed("request payload")
        }
        var records: [[UInt8]] = []
        var offset = headerBytes
        for _ in 0..<styleCount {
            records.append(Array(bytes[offset..<(offset + 8)]))
            offset += 8
        }
        return M0PortableRequestV2(
            requestFlags: reader.uint16(at: 8),
            streamToken: reader.uint64(at: 16),
            acknowledgedTransactionID: reader.uint64(at: 24),
            acknowledgedGeometryRevision: reader.uint64(at: 32),
            acknowledgedLineageRevision: reader.uint64(at: 40),
            nextStyleRevision: reader.uint64(at: 48),
            maximumResponseBytes: reader.uint32(at: 56),
            styleRecords: records,
            commandBytes: Array(bytes[offset..<bytes.count]),
            requestSequence: reader.uint64(at: 64)
        )
    }
}

struct M0PortableResponseV2: Equatable {
    static let headerBytes = 112

    let messageKind: UInt8
    let responseFlags: UInt8
    let resultFlags: UInt16
    let errorID: UInt16
    let streamToken: UInt64
    let echoedRequestSequence: UInt64
    let nextExpectedRequestSequence: UInt64
    let transactionID: UInt64
    let baseGeometryRevision: UInt64
    let targetGeometryRevision: UInt64
    let targetLineageRevision: UInt64
    let acceptedStyleRevision: UInt64
    let chunkIndex: UInt16
    let chunkCount: UInt16
    let upsertCount: UInt16
    let removalCount: UInt16
    let lineageCount: UInt16
    let regionResultCount: UInt16
    let payload: [UInt8]
    let diagnostic: [UInt8]

    func encode(maximumBytes: Int) throws -> [UInt8] {
        guard responseFlags <= 0xff,
              resultFlags <= 0x1f,
              diagnostic.count <= 1024
        else {
            throw M0PortableCodecError.malformed("response fields")
        }
        let body = payload + diagnostic
        let packetBytes = Self.headerBytes + body.count
        guard packetBytes <= maximumBytes, packetBytes <= 64 * 1024 else {
            throw M0PortableCodecError.malformed("response ceiling")
        }
        var buffer = M0ByteBuffer(count: packetBytes)
        buffer.bytes.replaceSubrange(0..<4, with: Array("VGS2".utf8))
        buffer.putUInt16(2, at: 4)
        buffer.putUInt16(UInt16(Self.headerBytes), at: 6)
        buffer.bytes[8] = messageKind
        buffer.bytes[9] = responseFlags
        buffer.putUInt16(resultFlags, at: 10)
        buffer.putUInt16(errorID, at: 12)
        buffer.putUInt32(UInt32(packetBytes), at: 16)
        buffer.putUInt16(UInt16(diagnostic.count), at: 20)
        buffer.putUInt64(streamToken, at: 24)
        buffer.putUInt64(echoedRequestSequence, at: 32)
        buffer.putUInt64(nextExpectedRequestSequence, at: 40)
        buffer.putUInt64(transactionID, at: 48)
        buffer.putUInt64(baseGeometryRevision, at: 56)
        buffer.putUInt64(targetGeometryRevision, at: 64)
        buffer.putUInt64(targetLineageRevision, at: 72)
        buffer.putUInt64(acceptedStyleRevision, at: 80)
        buffer.putUInt16(chunkIndex, at: 88)
        buffer.putUInt16(chunkCount, at: 90)
        buffer.putUInt16(upsertCount, at: 92)
        buffer.putUInt16(removalCount, at: 94)
        buffer.putUInt16(lineageCount, at: 96)
        buffer.putUInt16(regionResultCount, at: 98)
        buffer.putUInt32(UInt32(body.count), at: 100)
        buffer.bytes.replaceSubrange(Self.headerBytes..<packetBytes, with: body)
        buffer.putUInt32(m0PortableCRC32(buffer.bytes, zeroOffset: 104), at: 104)
        return buffer.bytes
    }

    static func decode(_ bytes: [UInt8]) throws -> M0PortableResponseV2 {
        guard bytes.count >= headerBytes else {
            throw M0PortableCodecError.malformed("response header")
        }
        let reader = M0ByteReader(bytes: bytes)
        guard Array(bytes[0..<4]) == Array("VGS2".utf8),
              reader.uint16(at: 4) == 2,
              reader.uint16(at: 6) == headerBytes,
              reader.uint16(at: 14) == 0,
              reader.uint16(at: 22) == 0,
              reader.uint32(at: 108) == 0,
              reader.uint16(at: 10) <= 0x1f,
              UInt64(reader.uint32(at: 16)) == UInt64(bytes.count),
              UInt64(reader.uint32(at: 100)) == UInt64(bytes.count - headerBytes),
              Int(reader.uint16(at: 20)) <= bytes.count - headerBytes
        else {
            throw M0PortableCodecError.malformed("response framing")
        }
        guard reader.uint32(at: 104) == m0PortableCRC32(bytes, zeroOffset: 104) else {
            throw M0PortableCodecError.crc
        }
        let diagnosticBytes = Int(reader.uint16(at: 20))
        let split = bytes.count - diagnosticBytes
        return M0PortableResponseV2(
            messageKind: bytes[8],
            responseFlags: bytes[9],
            resultFlags: reader.uint16(at: 10),
            errorID: reader.uint16(at: 12),
            streamToken: reader.uint64(at: 24),
            echoedRequestSequence: reader.uint64(at: 32),
            nextExpectedRequestSequence: reader.uint64(at: 40),
            transactionID: reader.uint64(at: 48),
            baseGeometryRevision: reader.uint64(at: 56),
            targetGeometryRevision: reader.uint64(at: 64),
            targetLineageRevision: reader.uint64(at: 72),
            acceptedStyleRevision: reader.uint64(at: 80),
            chunkIndex: reader.uint16(at: 88),
            chunkCount: reader.uint16(at: 90),
            upsertCount: reader.uint16(at: 92),
            removalCount: reader.uint16(at: 94),
            lineageCount: reader.uint16(at: 96),
            regionResultCount: reader.uint16(at: 98),
            payload: Array(bytes[headerBytes..<split]),
            diagnostic: Array(bytes[split..<bytes.count])
        )
    }
}

struct M0PortableUUID: Equatable {
    let bytes: [UInt8]

    init(_ bytes: [UInt8]) throws {
        guard bytes.count == 16, bytes.contains(where: { $0 != 0 }),
              bytes[6] >> 4 != 0, bytes[8] & 0xc0 == 0x80 else {
            throw M0PortableCodecError.malformed("uuid")
        }
        self.bytes = bytes
    }
}

enum M0PortableControlOperation: UInt8 {
    case start = 1
    case beginCheckpoint = 2
    case releaseCheckpoint = 3
    case stop = 4
}

struct M0PortableControlRequestV2: Equatable {
    static let headerBytes = 104
    static let maximumBytes = 16 * 1024

    let operation: M0PortableControlOperation
    let flags: UInt8
    let controlRequestID: M0PortableUUID
    let sessionID: M0PortableUUID
    let captureGroupID: M0PortableUUID
    let sessionGeneration: UInt64
    let groupGeneration: UInt64
    let coverageEpoch: UInt64
    let streamToken: UInt64
    let payload: [UInt8]

    func encode() throws -> [UInt8] {
        guard flags & 0xfe == 0,
              sessionGeneration > 0 && sessionGeneration <= UInt64.max >> 1,
              groupGeneration > 0 && groupGeneration <= UInt64.max >> 1,
              coverageEpoch > 0 && coverageEpoch <= UInt64.max >> 1,
              streamToken <= UInt64.max >> 1,
              (operation == .start) == (streamToken == 0),
              payload.count <= 0xffff else {
            throw M0PortableCodecError.malformed("control request fields")
        }
        let packetBytes = Self.headerBytes + payload.count
        guard packetBytes <= Self.maximumBytes else {
            throw M0PortableCodecError.malformed("control request ceiling")
        }
        var buffer = M0ByteBuffer(count: packetBytes)
        buffer.bytes.replaceSubrange(0..<4, with: Array("VGC2".utf8))
        buffer.putUInt16(2, at: 4)
        buffer.bytes[6] = operation.rawValue
        buffer.bytes[7] = flags
        buffer.putUInt16(UInt16(Self.headerBytes), at: 8)
        buffer.putUInt16(UInt16(payload.count), at: 10)
        buffer.putUInt32(UInt32(packetBytes), at: 12)
        buffer.bytes.replaceSubrange(16..<32, with: controlRequestID.bytes)
        buffer.bytes.replaceSubrange(32..<48, with: sessionID.bytes)
        buffer.bytes.replaceSubrange(48..<64, with: captureGroupID.bytes)
        buffer.putUInt64(sessionGeneration, at: 64)
        buffer.putUInt64(groupGeneration, at: 72)
        buffer.putUInt64(coverageEpoch, at: 80)
        buffer.putUInt64(streamToken, at: 88)
        buffer.putUInt32(0, at: 96)
        buffer.putUInt32(0, at: 100)
        buffer.bytes.replaceSubrange(Self.headerBytes..<packetBytes, with: payload)
        buffer.putUInt32(m0PortableCRC32(buffer.bytes, zeroOffset: 96), at: 96)
        return buffer.bytes
    }

    static func decode(_ bytes: [UInt8]) throws -> M0PortableControlRequestV2 {
        guard bytes.count >= headerBytes, bytes.count <= maximumBytes else {
            throw M0PortableCodecError.malformed("control request length")
        }
        let reader = M0ByteReader(bytes: bytes)
        guard Array(bytes[0..<4]) == Array("VGC2".utf8),
              reader.uint16(at: 4) == 2,
              reader.uint16(at: 8) == headerBytes,
              UInt64(reader.uint32(at: 12)) == UInt64(bytes.count),
              Int(reader.uint16(at: 10)) == bytes.count - headerBytes,
              reader.uint32(at: 100) == 0,
              reader.uint32(at: 96) == m0PortableCRC32(bytes, zeroOffset: 96) else {
            throw M0PortableCodecError.malformed("control request framing")
        }
        guard let operation = M0PortableControlOperation(rawValue: bytes[6]) else {
            throw M0PortableCodecError.malformed("control operation")
        }
        return try M0PortableControlRequestV2(
            operation: operation,
            flags: bytes[7],
            controlRequestID: M0PortableUUID(Array(bytes[16..<32])),
            sessionID: M0PortableUUID(Array(bytes[32..<48])),
            captureGroupID: M0PortableUUID(Array(bytes[48..<64])),
            sessionGeneration: reader.uint64(at: 64),
            groupGeneration: reader.uint64(at: 72),
            coverageEpoch: reader.uint64(at: 80),
            streamToken: reader.uint64(at: 88),
            payload: Array(bytes[headerBytes..<bytes.count])
        )
    }
}

struct M0PortableControlResponseV2: Equatable {
    static let headerBytes = 128
    static let maximumBytes = 16 * 1024

    let operation: M0PortableControlOperation
    let outcome: UInt8
    let resultFlags: UInt16
    let errorID: UInt16
    let controlRequestID: M0PortableUUID
    let sessionID: M0PortableUUID
    let captureGroupID: M0PortableUUID
    let sessionGeneration: UInt64
    let groupGeneration: UInt64
    let coverageEpoch: UInt64
    let streamToken: UInt64
    let nextExchangeRequestSequence: UInt64
    let nativeTransactionID: UInt64
    let payload: [UInt8]
    let diagnostic: [UInt8]

    func encode(maximumBytes: Int) throws -> [UInt8] {
        guard outcome <= 1, resultFlags <= 0x1f, errorID <= 0xffff,
              diagnostic.count <= 256,
              sessionGeneration > 0 && sessionGeneration <= UInt64.max >> 1,
              groupGeneration > 0 && groupGeneration <= UInt64.max >> 1,
              coverageEpoch > 0 && coverageEpoch <= UInt64.max >> 1,
              streamToken <= UInt64.max >> 1,
              nextExchangeRequestSequence <= UInt64.max >> 1,
              nativeTransactionID <= UInt64.max >> 1 else {
            throw M0PortableCodecError.malformed("control response fields")
        }
        let body = payload + diagnostic
        let packetBytes = Self.headerBytes + body.count
        guard packetBytes <= maximumBytes, packetBytes <= Self.maximumBytes else {
            throw M0PortableCodecError.malformed("control response ceiling")
        }
        var buffer = M0ByteBuffer(count: packetBytes)
        buffer.bytes.replaceSubrange(0..<4, with: Array("VGD2".utf8))
        buffer.putUInt16(2, at: 4)
        buffer.bytes[6] = operation.rawValue
        buffer.bytes[7] = outcome
        buffer.putUInt16(UInt16(Self.headerBytes), at: 8)
        buffer.putUInt16(resultFlags, at: 10)
        buffer.putUInt16(errorID, at: 12)
        buffer.putUInt16(UInt16(diagnostic.count), at: 14)
        buffer.putUInt32(UInt32(packetBytes), at: 16)
        buffer.putUInt32(UInt32(body.count), at: 20)
        buffer.bytes.replaceSubrange(24..<40, with: controlRequestID.bytes)
        buffer.bytes.replaceSubrange(40..<56, with: sessionID.bytes)
        buffer.bytes.replaceSubrange(56..<72, with: captureGroupID.bytes)
        buffer.putUInt64(sessionGeneration, at: 72)
        buffer.putUInt64(groupGeneration, at: 80)
        buffer.putUInt64(coverageEpoch, at: 88)
        buffer.putUInt64(streamToken, at: 96)
        buffer.putUInt64(nextExchangeRequestSequence, at: 104)
        buffer.putUInt64(nativeTransactionID, at: 112)
        buffer.putUInt32(0, at: 120)
        buffer.putUInt32(0, at: 124)
        buffer.bytes.replaceSubrange(Self.headerBytes..<packetBytes, with: body)
        buffer.putUInt32(m0PortableCRC32(buffer.bytes, zeroOffset: 120), at: 120)
        return buffer.bytes
    }

    static func decode(_ bytes: [UInt8]) throws -> M0PortableControlResponseV2 {
        guard bytes.count >= headerBytes, bytes.count <= maximumBytes else {
            throw M0PortableCodecError.malformed("control response length")
        }
        let reader = M0ByteReader(bytes: bytes)
        guard Array(bytes[0..<4]) == Array("VGD2".utf8),
              reader.uint16(at: 4) == 2,
              reader.uint16(at: 8) == headerBytes,
              UInt64(reader.uint32(at: 16)) == UInt64(bytes.count),
              UInt64(reader.uint32(at: 20)) == UInt64(bytes.count - headerBytes),
              Int(reader.uint16(at: 14)) <= bytes.count - headerBytes,
              reader.uint16(at: 10) <= 0x1f,
              reader.uint32(at: 124) == 0,
              reader.uint32(at: 120) == m0PortableCRC32(bytes, zeroOffset: 120) else {
            throw M0PortableCodecError.malformed("control response framing")
        }
        guard let operation = M0PortableControlOperation(rawValue: bytes[6]) else {
            throw M0PortableCodecError.malformed("control operation")
        }
        let diagnosticBytes = Int(reader.uint16(at: 14))
        let split = bytes.count - diagnosticBytes
        return try M0PortableControlResponseV2(
            operation: operation,
            outcome: bytes[7],
            resultFlags: reader.uint16(at: 10),
            errorID: reader.uint16(at: 12),
            controlRequestID: M0PortableUUID(Array(bytes[24..<40])),
            sessionID: M0PortableUUID(Array(bytes[40..<56])),
            captureGroupID: M0PortableUUID(Array(bytes[56..<72])),
            sessionGeneration: reader.uint64(at: 72),
            groupGeneration: reader.uint64(at: 80),
            coverageEpoch: reader.uint64(at: 88),
            streamToken: reader.uint64(at: 96),
            nextExchangeRequestSequence: reader.uint64(at: 104),
            nativeTransactionID: reader.uint64(at: 112),
            payload: Array(bytes[headerBytes..<split]),
            diagnostic: Array(bytes[split..<bytes.count])
        )
    }
}

struct M0PortableErrorDetailV2: Equatable {
    static let bytes = 96

    let errorID: UInt16
    let scope: UInt8
    let disposition: UInt8
    let validationPhase: UInt8
    let recoveryAction: UInt8
    let fieldID: UInt16
    let authorityKind: UInt16
    let diagnosticBytes: UInt16
    let geometryRevision: UInt64
    let lineageRevision: UInt64
    let captureRevision: UInt64
    let coverageRevision: UInt64
    let acceptedStyleRevision: UInt64
    let regionManifestRevision: UInt64
    let nextSurfaceIDHighWater: UInt64
    let expectedValue: UInt64
    let observedValue: UInt64
    let schemaRootRevision: UInt64

    func encode() throws -> [UInt8] {
        guard errorID > 0 && errorID <= 150, scope <= 7, disposition <= 2,
              validationPhase >= 1 && validationPhase <= 10,
              recoveryAction <= 9, diagnosticBytes <= 256 else {
            throw M0PortableCodecError.malformed("error detail fields")
        }
        var buffer = M0ByteBuffer(count: Self.bytes)
        buffer.putUInt16(errorID, at: 0)
        buffer.bytes[2] = scope
        buffer.bytes[3] = disposition
        buffer.bytes[4] = validationPhase
        buffer.bytes[5] = recoveryAction
        buffer.putUInt16(fieldID, at: 6)
        buffer.putUInt16(authorityKind, at: 8)
        buffer.putUInt16(diagnosticBytes, at: 10)
        buffer.putUInt64(geometryRevision, at: 12)
        buffer.putUInt64(lineageRevision, at: 20)
        buffer.putUInt64(captureRevision, at: 28)
        buffer.putUInt64(coverageRevision, at: 36)
        buffer.putUInt64(acceptedStyleRevision, at: 44)
        buffer.putUInt64(regionManifestRevision, at: 52)
        buffer.putUInt64(nextSurfaceIDHighWater, at: 60)
        buffer.putUInt64(expectedValue, at: 68)
        buffer.putUInt64(observedValue, at: 76)
        buffer.putUInt64(schemaRootRevision, at: 84)
        return buffer.bytes
    }

    static func decode(_ bytes: [UInt8]) throws -> M0PortableErrorDetailV2 {
        guard bytes.count == Self.bytes else {
            throw M0PortableCodecError.malformed("error detail length")
        }
        let reader = M0ByteReader(bytes: bytes)
        guard reader.uint32(at: 92) == 0 else {
            throw M0PortableCodecError.malformed("error detail reserved")
        }
        return M0PortableErrorDetailV2(
            errorID: reader.uint16(at: 0), scope: bytes[2], disposition: bytes[3],
            validationPhase: bytes[4], recoveryAction: bytes[5], fieldID: reader.uint16(at: 6),
            authorityKind: reader.uint16(at: 8), diagnosticBytes: reader.uint16(at: 10),
            geometryRevision: reader.uint64(at: 12), lineageRevision: reader.uint64(at: 20),
            captureRevision: reader.uint64(at: 28), coverageRevision: reader.uint64(at: 36),
            acceptedStyleRevision: reader.uint64(at: 44), regionManifestRevision: reader.uint64(at: 52),
            nextSurfaceIDHighWater: reader.uint64(at: 60), expectedValue: reader.uint64(at: 68),
            observedValue: reader.uint64(at: 76), schemaRootRevision: reader.uint64(at: 84)
        )
    }
}

struct M0PortableRegionCoordinate: Equatable, Comparable {
    let x: Int32
    let y: Int32
    let z: Int32

    static func < (lhs: M0PortableRegionCoordinate, rhs: M0PortableRegionCoordinate) -> Bool {
        if lhs.x != rhs.x { return lhs.x < rhs.x }
        if lhs.y != rhs.y { return lhs.y < rhs.y }
        return lhs.z < rhs.z
    }

    var neighbors26: [M0PortableRegionCoordinate] {
        var result: [M0PortableRegionCoordinate] = []
        for dx in -1...1 {
            for dy in -1...1 {
                for dz in -1...1 where dx != 0 || dy != 0 || dz != 0 {
                    result.append(M0PortableRegionCoordinate(x: x + Int32(dx), y: y + Int32(dy), z: z + Int32(dz)))
                }
            }
        }
        return result
    }
}

func m0PortableFloorDiv(_ numerator: Int32, _ denominator: Int32) -> Int32 {
    let value = Int64(numerator)
    let divisor = Int64(denominator)
    let quotient = value / divisor
    let remainder = value % divisor
    return Int32(remainder < 0 ? quotient - 1 : quotient)
}

func m0PortableRegionForMillimetres(_ x: Int32, _ y: Int32, _ z: Int32) -> M0PortableRegionCoordinate {
    M0PortableRegionCoordinate(
        x: m0PortableFloorDiv(x, 3000),
        y: m0PortableFloorDiv(y, 3000),
        z: m0PortableFloorDiv(z, 3000)
    )
}

struct M0PortableVoxelKey: Equatable, Comparable {
    let x: Int32
    let y: Int32
    let z: Int32

    static func < (lhs: M0PortableVoxelKey, rhs: M0PortableVoxelKey) -> Bool {
        if lhs.x != rhs.x { return lhs.x < rhs.x }
        if lhs.y != rhs.y { return lhs.y < rhs.y }
        return lhs.z < rhs.z
    }
}

struct M0PortableVoxelObservation {
    let key: M0PortableVoxelKey
    let signedWeight: Int32
}

struct M0PortableSurface: Equatable {
    let surfaceID: UInt64
    let key: M0PortableVoxelKey
    let weight: Int32
    let normalOctant: Int32
}

func m0PortableSignedOccupancy(
    _ observations: [M0PortableVoxelObservation],
    capacity: Int = 100_000,
    threshold: Int32 = 2,
    saturation: Int32 = 127
) -> [M0PortableSurface] {
    var weights: [M0PortableVoxelKey: Int32] = [:]
    for observation in observations {
        let next = (weights[observation.key] ?? 0) + observation.signedWeight
        weights[observation.key] = min(saturation, max(-saturation, next))
    }
    return weights.keys.sorted()
        .filter { (weights[$0] ?? 0) >= threshold }
        .prefix(capacity)
        .enumerated()
        .map { index, key in
            let octant = ((key.x.signum() << 2) | (key.y.signum() << 1) | key.z.signum()) & 7
            return M0PortableSurface(
                surfaceID: UInt64(index + 1),
                key: key,
                weight: weights[key]!,
                normalOctant: octant
            )
        }
}
