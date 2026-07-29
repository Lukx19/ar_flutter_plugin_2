import XCTest
@testable import ar_flutter_plugin_2

final class VisibilityGridDepthCoreTests: XCTestCase {
    func testSeparatedRepeatedFreeEvidenceCarvesAndOccupiedRestores() throws {
        let key = packVisibilityGridKey([0, 0, -5])
        let group = try VisibilityGridGroupConfiguration(
            groupId: "group",
            groupGeneration: 1,
            sessionGeneration: 1,
            voxelSizeMeters: 0.1,
            capacity: 100,
            groupFromWorldGL: identityVisibilityGridTransform(),
            restoredGeometryRevision: 1,
            restoredKeys: [key]
        )
        let grid = try NativeVisibilityGrid(
            featureConfiguration: .fixture(),
            depthConfiguration: VisibilityGridDepthConfiguration()
        )
        try grid.startGroup(group)

        for index in 0..<8 {
            try grid.applyDepthEvidence(
                occupiedKeys: [],
                freeDirectionsByKey: [key: index < 4 ? 0 : 2]
            )
        }
        XCTAssertFalse(grid.snapshot().stableKeys.contains(key))

        for _ in 0..<4 {
            try grid.applyDepthEvidence(
                occupiedKeys: [key],
                freeDirectionsByKey: [:]
            )
        }
        XCTAssertTrue(grid.snapshot().stableKeys.contains(key))
    }

    func testDepthObservationMapsEndpointRejectsEdgesAndNeverCarvesBehind()
        throws {
        let freeCell = packVisibilityGridKey([0, 0, -2])
        let endpoint = packVisibilityGridKey([0, 0, -5])
        let behind = packVisibilityGridKey([0, 0, -7])
        let group = try VisibilityGridGroupConfiguration(
            groupId: "group",
            groupGeneration: 1,
            sessionGeneration: 1,
            voxelSizeMeters: 0.1,
            capacity: 100,
            groupFromWorldGL: identityVisibilityGridTransform(),
            restoredGeometryRevision: 1,
            restoredKeys: [freeCell, behind]
        )
        let grid = try NativeVisibilityGrid(
            featureConfiguration: .fixture(),
            depthConfiguration: VisibilityGridDepthConfiguration()
        )
        try grid.startGroup(group)

        for timestamp in 1...4 {
            let result = try grid.observeDepth(
                DepthObservation(
                    timestampNanoseconds: Int64(timestamp),
                    groupGeneration: 1,
                    sessionGeneration: 1,
                    tracking: true,
                    width: 3,
                    height: 1,
                    samples: [
                        DepthPixelSample(
                            x: 0,
                            y: 0,
                            depthMeters: .nan,
                            confidence: 255
                        ),
                        DepthPixelSample(
                            x: 1,
                            y: 0,
                            depthMeters: 0.5,
                            confidence: 0
                        ),
                        DepthPixelSample(
                            x: 2,
                            y: 0,
                            depthMeters: 0.5,
                            confidence: 255
                        )
                    ],
                    sourceRejectedPixels: 0,
                    intrinsics: DepthIntrinsics(
                        fx: 1,
                        fy: 1,
                        cx: 2,
                        cy: 0
                    ),
                    worldFromCameraGL:
                        identityVisibilityGridTransform()
                )
            )
            XCTAssertEqual(result.accepted, 1)
            XCTAssertEqual(result.rejected, 2)
        }
        let visible = Set(grid.snapshot().stableKeys)
        XCTAssertTrue(visible.contains(freeCell))
        XCTAssertTrue(visible.contains(endpoint))
        XCTAssertTrue(visible.contains(behind))
    }

    func testTerminalDepthFailureAndRendererHealthRemainIndependent()
        throws {
        let grid = try NativeVisibilityGrid(
            featureConfiguration: .fixture(),
            depthConfiguration: VisibilityGridDepthConfiguration(
                terminalFailureThreshold: 2
            )
        )
        try grid.startGroup(.fixture())

        grid.reportDepthFailure()
        XCTAssertEqual(
            grid.snapshot().diagnostics.health(renderer: "healthy")["depth"],
            "transientUnavailable"
        )
        grid.reportDepthFailure()
        let health =
            grid.snapshot().diagnostics.health(renderer: "failed")
        XCTAssertEqual(health["depth"], "failed")
        XCTAssertEqual(health["renderer"], "failed")
        XCTAssertEqual(health["totalGrid"], "featureOnly")
    }
}
