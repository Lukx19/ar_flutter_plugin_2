import XCTest
@testable import ar_flutter_plugin_2

final class VisibilityGridDepthRendererLifecycleTests: XCTestCase {
    func testSceneDepthOrientationConfidenceAndFallback() {
        XCTAssertEqual(
            SceneDepthAdapter.orientedDimensions(
                width: 3,
                height: 2,
                orientation: .portrait
            ),
            [2, 3]
        )
        XCTAssertEqual(
            SceneDepthAdapter.orientedPixel(
                x: 0,
                y: 1,
                width: 3,
                height: 2,
                orientation: .portrait
            ),
            [1, 2]
        )
        XCTAssertTrue(
            SceneDepthAdapter.accepts(
                confidence: .high,
                minimum: .medium
            )
        )
        XCTAssertTrue(
            SceneDepthAdapter.accepts(
                confidence: .medium,
                minimum: .medium
            )
        )
        XCTAssertFalse(
            SceneDepthAdapter.accepts(
                confidence: .low,
                minimum: .medium
            )
        )
        XCTAssertEqual(
            SceneDepthAdapter.mode(
                capabilitySupported: true,
                sceneDepthAvailable: false
            ),
            .sceneDepthTransientlyUnavailable
        )
        XCTAssertEqual(
            SceneDepthAdapter.mode(
                capabilitySupported: false,
                sceneDepthAvailable: false
            ),
            .featureOnly
        )
    }

    func testDepthSafetyBandCarvingNeedsSeparatedRepeatedViews() throws {
        let depth = try VisibilityGridDepthConfiguration()
        let group = try VisibilityGridGroupConfiguration(
            groupId: "group",
            groupGeneration: 1,
            sessionGeneration: 1,
            voxelSizeMeters: 0.1,
            capacity: 100,
            groupFromWorldGL: identityVisibilityGridTransform(),
            worldFromGroupGL: identityVisibilityGridTransform(),
            restoredGeometryRevision: 1,
            restoredKeys: [
                packVisibilityGridKey([0, 0, -5])
            ]
        )
        let grid = try NativeVisibilityGrid(
            featureConfiguration: VisibilityGridFeatureConfiguration.fixture(),
            depthConfiguration: depth
        )
        try grid.startGroup(group)
        let occupiedKey = packVisibilityGridKey([0, 0, -5])

        for index in 0..<8 {
            try grid.applyDepthEvidence(
                occupiedKeys: [],
                freeDirectionsByKey: [occupiedKey: index < 4 ? 0 : 2]
            )
        }
        XCTAssertFalse(grid.snapshot().stableKeys.contains(occupiedKey))

        for _ in 0..<4 {
            try grid.applyDepthEvidence(
                occupiedKeys: [occupiedKey],
                freeDirectionsByKey: [:]
            )
        }
        XCTAssertTrue(grid.snapshot().stableKeys.contains(occupiedKey))
    }

    func testSyntheticDepthEndpointIsGroupLocalAndDoesNotCarveBehind() throws {
        let endpoint = packVisibilityGridKey([0, 0, -5])
        let freeCell = packVisibilityGridKey([0, 0, -2])
        let behindEndpoint = packVisibilityGridKey([0, 0, -7])
        let group = try VisibilityGridGroupConfiguration(
            groupId: "group",
            groupGeneration: 1,
            sessionGeneration: 1,
            voxelSizeMeters: 0.1,
            capacity: 100,
            groupFromWorldGL: identityVisibilityGridTransform(),
            worldFromGroupGL: identityVisibilityGridTransform(),
            restoredGeometryRevision: 1,
            restoredKeys: [freeCell, behindEndpoint]
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
                    width: 1,
                    height: 1,
                    samples: [
                        DepthPixelSample(
                            x: 0,
                            y: 0,
                            depthMeters: 0.5,
                            confidence: 255
                        )
                    ],
                    sourceRejectedPixels: 0,
                    intrinsics: DepthIntrinsics(
                        fx: 1,
                        fy: 1,
                        cx: 0,
                        cy: 0
                    ),
                    worldFromCameraGL:
                        identityVisibilityGridTransform()
                )
            )
            XCTAssertEqual(result.accepted, 1)
        }
        let visible = Set(grid.snapshot().stableKeys)
        XCTAssertTrue(visible.contains(endpoint))
        XCTAssertTrue(visible.contains(freeCell))
        XCTAssertTrue(visible.contains(behindEndpoint))
    }

    func testSyntheticDepthRejectsLowConfidenceAndEdgeValues() throws {
        let grid = try NativeVisibilityGrid(
            featureConfiguration: .fixture(),
            depthConfiguration: VisibilityGridDepthConfiguration()
        )
        let group = try VisibilityGridGroupConfiguration.fixture()
        try grid.startGroup(group)
        let result = try grid.observeDepth(
            DepthObservation(
                timestampNanoseconds: 1,
                groupGeneration: 1,
                sessionGeneration: 1,
                tracking: true,
                width: 3,
                height: 1,
                samples: [
                    DepthPixelSample(
                        x: 0,
                        y: 0,
                        depthMeters: 0.5,
                        confidence: 0
                    ),
                    DepthPixelSample(
                        x: 1,
                        y: 0,
                        depthMeters: .nan,
                        confidence: 255
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
                worldFromCameraGL: identityVisibilityGridTransform()
            )
        )
        XCTAssertEqual(result.accepted, 1)
        XCTAssertEqual(result.rejected, 2)
    }

    func testRendererRemovalReclaimsRowsInBothModes() throws {
        let renderer = try VisibilityGridRendererState(capacity: 2)
        let first = packVisibilityGridKey([0, 0, 0])
        let second = packVisibilityGridKey([1, 0, 0])
        let third = packVisibilityGridKey([2, 0, 0])
        try renderer.startGroup(
            voxelSizeMeters: 0.1,
            worldFromGroupGL: identityVisibilityGridTransform(),
            geometryRevision: 0,
            visibilityRevision: 0,
            restoredKeys: []
        )
        XCTAssertTrue(
            renderer.applyGeometry(
                revision: 1,
                reset: false,
                upsertKeys: [first, second],
                removalKeys: []
            )
        )
        XCTAssertTrue(
            renderer.applyGeometry(
                revision: 2,
                reset: false,
                upsertKeys: [third],
                removalKeys: [first]
            )
        )
        renderer.mode = .centroids
        XCTAssertEqual(renderer.snapshot().keys.sorted(), [second, third])
        renderer.mode = .cubes
        XCTAssertEqual(renderer.snapshot().keys.sorted(), [second, third])
        XCTAssertEqual(renderer.freeRowCount, 0)

        XCTAssertTrue(
            renderer.applyGeometry(
                revision: 3,
                reset: false,
                upsertKeys: [],
                removalKeys: [second, third]
            )
        )
        XCTAssertEqual(renderer.freeRowCount, 2)
    }

    func testRendererRejectsStaleColorAndIgnoresDeletedKeys() throws {
        let renderer = try VisibilityGridRendererState(capacity: 2)
        let key = packVisibilityGridKey([0, 0, 0])
        let deleted = packVisibilityGridKey([1, 0, 0])
        try renderer.startGroup(
            voxelSizeMeters: 0.1,
            worldFromGroupGL: identityVisibilityGridTransform(),
            geometryRevision: 4,
            visibilityRevision: 0,
            restoredKeys: [key]
        )
        XCTAssertFalse(
            renderer.applyVisibility(
                geometryRevision: 3,
                visibilityRevision: 1,
                keys: [key],
                colors: [0xff00ff00]
            )
        )
        XCTAssertTrue(
            renderer.applyVisibility(
                geometryRevision: 4,
                visibilityRevision: 1,
                keys: [key, deleted],
                colors: [0xff00ff00, 0xffffffff]
            )
        )
        XCTAssertEqual(renderer.ignoredDeletedVisibilityKeys, 1)
        XCTAssertEqual(renderer.snapshot().colorsARGB, [0xff00ff00])
        XCTAssertFalse(
            renderer.applyVisibility(
                geometryRevision: 4,
                visibilityRevision: 1,
                keys: [key],
                colors: [0]
            )
        )
    }

    func testRendererChurnReclaimsEveryBoundedRow() throws {
        let renderer = try VisibilityGridRendererState(capacity: 8)
        try renderer.startGroup(
            voxelSizeMeters: 0.1,
            worldFromGroupGL: identityVisibilityGridTransform(),
            geometryRevision: 0,
            visibilityRevision: 0,
            restoredKeys: []
        )
        var revision: Int64 = 0
        for cycle in 0..<2_000 {
            let key = packVisibilityGridKey([cycle, 0, 0])
            revision += 1
            XCTAssertTrue(
                renderer.applyGeometry(
                    revision: revision,
                    reset: false,
                    upsertKeys: [key],
                    removalKeys: []
                )
            )
            revision += 1
            XCTAssertTrue(
                renderer.applyGeometry(
                    revision: revision,
                    reset: false,
                    upsertKeys: [],
                    removalKeys: [key]
                )
            )
        }
        XCTAssertEqual(renderer.freeRowCount, 8)
        XCTAssertTrue(renderer.snapshot().keys.isEmpty)
    }

    func testLifecycleEpochRejectsCallbacksAcrossEveryBoundary() {
        let lifecycle = VisibilityGridLifecycleEpoch()
        let initial = lifecycle.token
        XCTAssertTrue(lifecycle.allows(initial))
        lifecycle.pause()
        lifecycle.pause()
        XCTAssertFalse(lifecycle.allows(initial))
        lifecycle.resume()
        let resumed = lifecycle.token
        XCTAssertTrue(lifecycle.allows(resumed))
        lifecycle.resetSession()
        XCTAssertFalse(lifecycle.allows(resumed))
        lifecycle.changeGroup()
        let changed = lifecycle.token
        XCTAssertTrue(lifecycle.allows(changed))
        lifecycle.dispose()
        lifecycle.dispose()
        XCTAssertFalse(lifecycle.allows(changed))
        XCTAssertFalse(lifecycle.allows(lifecycle.token))
    }
}
