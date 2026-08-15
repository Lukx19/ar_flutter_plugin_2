import XCTest
@testable import ar_flutter_plugin_2

final class M0PortableReferenceTests: XCTestCase {
    func testRequestAndResponseUseThePinnedM0aFraming() throws {
        let request = M0PortableRequestV2(
            requestFlags: 0,
            streamToken: 7,
            acknowledgedTransactionID: 0,
            acknowledgedGeometryRevision: 0,
            acknowledgedLineageRevision: 0,
            nextStyleRevision: 0,
            maximumResponseBytes: 4096,
            styleRecords: [[1, 2, 3, 4, 5, 6, 7, 8]],
            commandBytes: [9, 10],
            requestSequence: 1
        )
        let requestBytes = try request.encode()
        XCTAssertEqual(requestBytes.count, 90)
        XCTAssertEqual(try M0PortableRequestV2.decode(requestBytes), request)

        let response = M0PortableResponseV2(
            messageKind: 0,
            responseFlags: 0,
            resultFlags: 0,
            errorID: 0,
            streamToken: 7,
            echoedRequestSequence: 1,
            nextExpectedRequestSequence: 2,
            transactionID: 0,
            baseGeometryRevision: 0,
            targetGeometryRevision: 0,
            targetLineageRevision: 0,
            acceptedStyleRevision: 0,
            chunkIndex: 0,
            chunkCount: 0,
            upsertCount: 0,
            removalCount: 0,
            lineageCount: 0,
            regionResultCount: 0,
            payload: [],
            diagnostic: []
        )
        let responseBytes = try response.encode(maximumBytes: 4096)
        XCTAssertEqual(responseBytes.count, 112)
        XCTAssertEqual(try M0PortableResponseV2.decode(responseBytes), response)
    }

    func testPortableSignedCoordinatesAndOccupancyMatchTheReference() {
        XCTAssertEqual(
            m0PortableRegionForMillimetres(-1, 0, -3000),
            M0PortableRegionCoordinate(x: -1, y: 0, z: -1)
        )
        XCTAssertEqual(M0PortableRegionCoordinate(x: 0, y: 0, z: 0).neighbors26.count, 26)

        let surfaces = m0PortableSignedOccupancy([
            M0PortableVoxelObservation(
                key: M0PortableVoxelKey(x: 0, y: 0, z: 0),
                signedWeight: 2
            )
        ])
        XCTAssertEqual(surfaces.count, 1)
        XCTAssertEqual(surfaces.first?.surfaceID, 1)
    }

    func testPortableControlAndErrorDetailUsePinnedHeaders() throws {
        let id = try M0PortableUUID([
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x46, 0x17,
            0x98, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f
        ])
        let session = try M0PortableUUID(id.bytes.map { $0 ^ 1 })
        let group = try M0PortableUUID(id.bytes.map { $0 ^ 2 })
        let request = M0PortableControlRequestV2(
            operation: .start,
            flags: 0,
            controlRequestID: id,
            sessionID: session,
            captureGroupID: group,
            sessionGeneration: 1,
            groupGeneration: 2,
            coverageEpoch: 3,
            streamToken: 0,
            payload: [1, 2, 3]
        )
        let requestBytes = try request.encode()
        XCTAssertEqual(requestBytes.count, 107)
        XCTAssertEqual(try M0PortableControlRequestV2.decode(requestBytes), request)

        let detail = M0PortableErrorDetailV2(
            errorID: 6, scope: 0, disposition: 0, validationPhase: 2,
            recoveryAction: 0, fieldID: 4, authorityKind: 1, diagnosticBytes: 0,
            geometryRevision: 7, lineageRevision: 8, captureRevision: 9,
            coverageRevision: 10, acceptedStyleRevision: 11,
            regionManifestRevision: 12, nextSurfaceIDHighWater: 13,
            expectedValue: 14, observedValue: 15, schemaRootRevision: 16
        )
        XCTAssertEqual(
            try M0PortableErrorDetailV2.decode(detail.encode()),
            detail
        )

        let response = M0PortableControlResponseV2(
            operation: .start,
            outcome: 1,
            resultFlags: 1,
            errorID: 6,
            controlRequestID: id,
            sessionID: session,
            captureGroupID: group,
            sessionGeneration: 1,
            groupGeneration: 2,
            coverageEpoch: 3,
            streamToken: 0,
            nextExchangeRequestSequence: 0,
            nativeTransactionID: 0,
            payload: try detail.encode(),
            diagnostic: [4, 5]
        )
        let responseBytes = try response.encode(maximumBytes: 4096)
        XCTAssertEqual(responseBytes.count, 226)
        XCTAssertEqual(try M0PortableControlResponseV2.decode(responseBytes), response)
    }
}
