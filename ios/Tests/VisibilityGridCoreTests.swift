import XCTest
@testable import ar_flutter_plugin_2

final class VisibilityGridCoreTests: XCTestCase {
    private lazy var corpus: [String: Any] = {
        let bundle = Bundle(for: type(of: self))
        let bundled = bundle.url(
                forResource: "visibility_grid_wire_v1",
                withExtension: "json"
            )
        let sourceFixture = ProcessInfo.processInfo.environment[
            "CAPTURE3D_PLUGIN_ROOT"
        ].map {
            URL(fileURLWithPath: $0)
                .appendingPathComponent(
                    "test/fixtures/visibility_grid/" +
                    "visibility_grid_wire_v1.json"
                )
        }
        let url = try! XCTUnwrap(
            bundled ?? sourceFixture
        )
        let data = try! Data(contentsOf: url)
        return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    }()

    private func scenario(_ name: String) -> [String: Any] {
        let scenarios = corpus["scenarios"] as! [[String: Any]]
        return scenarios.first { $0["name"] as? String == name }!
    }

    func testSharedCorpusPackedKeysAndGroupTransform() throws {
        XCTAssertEqual(corpus["version"] as? String, visibilityGridWireVersion)
        let fixture = scenario("group_transform_and_packed_keys")
        let groupFromWorld = fixture["groupFromWorldGl"] as! [Double]
        let worldPoints = fixture["worldPoints"] as! [[Double]]
        let expectedCoordinates =
            fixture["expectedGroupCellCoordinates"] as! [[Int]]
        let expectedKeys = fixture["expectedPackedKeys"] as! [String]

        for index in worldPoints.indices {
            let groupPoint = VisibilityGridPoint.transform(
                matrix: groupFromWorld,
                point: VisibilityGridPoint(worldPoints[index])
            )
            let coordinates = groupPoint.cellCoordinates(voxelSizeMeters: 0.1)
            XCTAssertEqual(coordinates, expectedCoordinates[index])
            XCTAssertEqual(
                String(packVisibilityGridKey(coordinates)),
                expectedKeys[index]
            )
        }
    }

    func testPersistentIdentifierRelocatesOneContributionAndResetsOnJump() throws {
        let fixture = scenario("persistent_id_relocation")
        let observations = fixture["observations"] as! [[String: Any]]
        let group = try VisibilityGridGroupConfiguration.fixture()
        let grid = try NativeVisibilityGrid(
            featureConfiguration: .fixture(),
            depthConfiguration: nil
        )
        try grid.startGroup(group)

        for item in observations {
            let position = item["positionGroup"] as! [Double]
            try grid.observeFeatures(
                FeatureObservation(
                    timestampNanoseconds:
                        (item["timestampNs"] as! NSNumber).int64Value,
                    groupGeneration: group.groupGeneration,
                    sessionGeneration: group.sessionGeneration,
                    samples: [
                        FeatureSample(
                            identifier: 42,
                            world: VisibilityGridPoint(position),
                            confidence: item["confidence"] as! Double
                        )
                    ]
                )
            )
        }
        XCTAssertEqual(
            grid.snapshot().stableKeys,
            [UInt64(fixture["relocatedKey"] as! String)!]
        )

        let jump = fixture["jumpObservation"] as! [String: Any]
        try grid.observeFeatures(
            FeatureObservation(
                timestampNanoseconds:
                    (jump["timestampNs"] as! NSNumber).int64Value,
                groupGeneration: group.groupGeneration,
                sessionGeneration: group.sessionGeneration,
                samples: [
                    FeatureSample(
                        identifier: 42,
                        world: VisibilityGridPoint(
                            jump["positionGroup"] as! [Double]
                        ),
                        confidence: 0.9
                    )
                ]
            )
        )
        XCTAssertTrue(grid.snapshot().stableKeys.isEmpty)
        XCTAssertEqual(grid.snapshot().diagnostics.featureMigrations, 1)
        XCTAssertEqual(grid.snapshot().diagnostics.featureJumpResets, 1)
        XCTAssertEqual(grid.snapshot().diagnostics.supportRemovals, 2)

        for item in
            fixture["jumpRecoveryObservations"] as! [[String: Any]] {
            try grid.observeFeatures(
                FeatureObservation(
                    timestampNanoseconds:
                        (item["timestampNs"] as! NSNumber).int64Value,
                    groupGeneration: group.groupGeneration,
                    sessionGeneration: group.sessionGeneration,
                    samples: [
                        FeatureSample(
                            identifier: 42,
                            world: VisibilityGridPoint(
                                item["positionGroup"] as! [Double]
                            ),
                            confidence: item["confidence"] as! Double
                        )
                    ]
                )
            )
        }
        XCTAssertEqual(
            grid.snapshot().stableKeys,
            [UInt64(fixture["expectedRecoveredKey"] as! String)!]
        )
        XCTAssertEqual(
            grid.snapshot().stableKeys.count,
            fixture["expectedRecoveredContributedVoxels"] as! Int
        )
        XCTAssertEqual(
            grid.snapshot().diagnostics.featureObservationCount,
            Int64(
                observations.count + 1 +
                    (
                        fixture["jumpRecoveryObservations"]
                            as! [[String: Any]]
                    ).count
            )
        )
    }

    func testSharedSupportKeepsVoxelUntilLastTrackLeaves() throws {
        let group = try VisibilityGridGroupConfiguration.fixture()
        let grid = try NativeVisibilityGrid(
            featureConfiguration: .fixture(
                candidateSamples: 1,
                candidateSpanNanoseconds: 0
            ),
            depthConfiguration: nil
        )
        try grid.startGroup(group)
        let origin = VisibilityGridPoint(x: 0.02, y: 0.02, z: 0.02)
        try grid.observeFeatures(
            FeatureObservation(
                timestampNanoseconds: 1,
                groupGeneration: 1,
                sessionGeneration: 1,
                samples: [
                    FeatureSample(identifier: 42, world: origin, confidence: 1),
                    FeatureSample(identifier: 84, world: origin, confidence: 1)
                ]
            )
        )
        let key = try XCTUnwrap(grid.snapshot().stableKeys.first)
        XCTAssertEqual(grid.snapshot().supportByKey[key], 2)

        try grid.observeFeatures(
            FeatureObservation(
                timestampNanoseconds: 2,
                groupGeneration: 1,
                sessionGeneration: 1,
                samples: [
                    FeatureSample(
                        identifier: 42,
                        world: VisibilityGridPoint(x: 2, y: 0, z: 0),
                        confidence: 1
                    )
                ]
            )
        )
        XCTAssertEqual(grid.snapshot().supportByKey[key], 1)
        XCTAssertTrue(grid.snapshot().stableKeys.contains(key))
    }

    func testSharedSyntheticQualityCertification() throws {
        let fixture = scenario("synthetic_quality_certification")
        let voxelSize = fixture["voxelSizeMeters"] as! Double
        let wallCoordinates =
            fixture["wallCellCoordinates"] as! [[Int]]
        let wallGrid = try NativeVisibilityGrid(
            featureConfiguration: .fixture(),
            depthConfiguration: nil
        )
        try wallGrid.startGroup(.fixture())
        for observationIndex in 0..<5 {
            try wallGrid.observeFeatures(
                FeatureObservation(
                    timestampNanoseconds:
                        Int64(observationIndex) * 125_000_000,
                    groupGeneration: 1,
                    sessionGeneration: 1,
                    samples: wallCoordinates.enumerated().map {
                        index, coordinate in
                        FeatureSample(
                            identifier: UInt64(index + 1),
                            world: VisibilityGridPoint(
                                x: (Double(coordinate[0]) + 0.5) *
                                    voxelSize,
                                y: (Double(coordinate[1]) + 0.5) *
                                    voxelSize,
                                z: (Double(coordinate[2]) + 0.5) *
                                    voxelSize
                            ),
                            confidence: 1
                        )
                    }
                )
            )
        }
        let maximumDistance =
            fixture["maximumWallDistanceVoxels"] as! Int
        let expectedFraction =
            fixture["minimumWallInlierFraction"] as! Double
        let surfaceCell = Int(
            floor(
                (fixture["wallSurfaceZMeters"] as! Double) /
                    voxelSize
            )
        )
        let stableCoordinates =
            wallGrid.snapshot().stableKeys.map(unpackVisibilityGridKey)
        let inlierCount = stableCoordinates.filter {
            abs($0[2] - surfaceCell) <= maximumDistance
        }.count
        XCTAssertEqual(stableCoordinates.count, wallCoordinates.count)
        XCTAssertGreaterThanOrEqual(
            Double(inlierCount) / Double(stableCoordinates.count),
            expectedFraction
        )

        let corridorCoordinates =
            fixture["corridorPhantomCoordinates"] as! [[Int]]
        let corridorKeys =
            corridorCoordinates.map(packVisibilityGridKey)
        let corridorGrid = try NativeVisibilityGrid(
            featureConfiguration: .fixture(),
            depthConfiguration: VisibilityGridDepthConfiguration()
        )
        try corridorGrid.startGroup(
            try VisibilityGridGroupConfiguration(
                groupId: "corridor",
                groupGeneration: 1,
                sessionGeneration: 1,
                voxelSizeMeters: voxelSize,
                capacity: 100,
                groupFromWorldGL: identityVisibilityGridTransform(),
                restoredGeometryRevision: 1,
                restoredKeys: corridorKeys
            )
        )
        for direction in
            fixture["freeEvidenceDirectionBins"] as! [Int] {
            try corridorGrid.applyDepthEvidence(
                occupiedKeys: [],
                freeDirectionsByKey: Dictionary(
                    uniqueKeysWithValues:
                        corridorKeys.map { ($0, direction) }
                )
            )
        }
        XCTAssertLessThanOrEqual(
            corridorGrid.snapshot().stableKeys.count,
            fixture[
                "maximumRemainingPhantomThicknessVoxels"
            ] as! Int
        )

        let protectedCoordinates =
            (fixture["thinWallCoordinates"] as! [[Int]]) +
            (fixture["doubleWallCoordinates"] as! [[Int]])
        let protectedKeys =
            protectedCoordinates.map(packVisibilityGridKey)
        let protectedGrid = try NativeVisibilityGrid(
            featureConfiguration: .fixture(),
            depthConfiguration: VisibilityGridDepthConfiguration()
        )
        try protectedGrid.startGroup(
            try VisibilityGridGroupConfiguration(
                groupId: "protected-walls",
                groupGeneration: 1,
                sessionGeneration: 1,
                voxelSizeMeters: voxelSize,
                capacity: 100,
                groupFromWorldGL: identityVisibilityGridTransform(),
                restoredGeometryRevision: 1,
                restoredKeys: protectedKeys
            )
        )
        for _ in 0..<8 {
            try protectedGrid.applyDepthEvidence(
                occupiedKeys: [],
                freeDirectionsByKey: Dictionary(
                    uniqueKeysWithValues:
                        protectedKeys.map { ($0, 0) }
                )
            )
        }
        XCTAssertEqual(
            Set(protectedGrid.snapshot().stableKeys),
            Set(protectedKeys)
        )
    }

    func testRevisionDeltaAcknowledgementAndSnapshotAreExact() throws {
        let grid = try NativeVisibilityGrid(
            featureConfiguration: .fixture(
                candidateSamples: 1,
                candidateSpanNanoseconds: 0,
                publishIntervalMilliseconds: 500
            ),
            depthConfiguration: nil
        )
        let group = try VisibilityGridGroupConfiguration.fixture()
        try grid.startGroup(group)
        try grid.observeFeatures(
            FeatureObservation(
                timestampNanoseconds: 1,
                groupGeneration: 1,
                sessionGeneration: 1,
                samples: [
                    FeatureSample(
                        identifier: 7,
                        world: VisibilityGridPoint(x: 0, y: 0, z: -1),
                        confidence: 1
                    )
                ]
            )
        )
        let delta = try XCTUnwrap(
            grid.takeGeometryDelta(nowNanoseconds: 500_000_000)
        )
        XCTAssertEqual(delta.baseGeometryRevision, 0)
        XCTAssertEqual(delta.geometryRevision, 1)
        XCTAssertTrue(
            grid.acknowledgeGeometry(
                identity: group.identity,
                acceptedGeometryRevision: 1
            )
        )
        let snapshot = try XCTUnwrap(
            grid.requestSnapshot(
                identity: group.identity,
                receiverGeometryRevision: 5,
                nowNanoseconds: 1_000_000_000
            )
        )
        XCTAssertTrue(snapshot.reset)
        XCTAssertEqual(snapshot.baseGeometryRevision, 5)
        XCTAssertEqual(snapshot.geometryRevision, 6)
    }

    func testStoppedGridStillRejectsAStaleGroupGeneration() throws {
        let grid = try NativeVisibilityGrid(
            featureConfiguration: .fixture(),
            depthConfiguration: nil
        )
        let group = try VisibilityGridGroupConfiguration.fixture()
        try grid.startGroup(group)
        grid.stopGroup()
        XCTAssertThrowsError(try grid.startGroup(group)) {
            XCTAssertEqual(
                $0 as? VisibilityGridContractError,
                .staleIdentity
            )
        }
    }

    func testBoundedSpatialAssociationIsPredictiveAndOneToOne() throws {
        let associator = try BoundedFeatureAssociator(capacity: 2)
        let first = associator.associate(
            timestampNanoseconds: 1,
            samples: [
                UnassociatedFeatureSample(
                    world: VisibilityGridPoint(x: 0, y: 0, z: 0),
                    confidence: 1
                ),
                UnassociatedFeatureSample(
                    world: VisibilityGridPoint(x: 1, y: 0, z: 0),
                    confidence: 1
                )
            ]
        )
        XCTAssertEqual(first.samples.count, 2)
        let firstIds = first.samples.map(\.identifier)

        let second = associator.associate(
            timestampNanoseconds: 2,
            samples: [
                UnassociatedFeatureSample(
                    world: VisibilityGridPoint(x: 0.08, y: 0, z: 0),
                    confidence: 1
                ),
                UnassociatedFeatureSample(
                    world: VisibilityGridPoint(x: 0.09, y: 0, z: 0),
                    confidence: 1
                )
            ]
        )
        XCTAssertEqual(second.samples.count, 1)
        XCTAssertEqual(second.rejectedSamples, 1)
        XCTAssertEqual(second.samples[0].identifier, firstIds[0])

        let predicted = associator.associate(
            timestampNanoseconds: 3,
            samples: [
                UnassociatedFeatureSample(
                    world: VisibilityGridPoint(x: 0.17, y: 0, z: 0),
                    confidence: 1
                )
            ]
        )
        XCTAssertEqual(predicted.samples[0].identifier, firstIds[0])
    }

    func testBoundedSpatialAssociationExpiresAndReusesCapacity() throws {
        let associator = try BoundedFeatureAssociator(
            capacity: 1,
            expiryNanoseconds: 10
        )
        let first = associator.associate(
            timestampNanoseconds: 1,
            samples: [
                UnassociatedFeatureSample(
                    world: VisibilityGridPoint(x: 0, y: 0, z: 0),
                    confidence: 1
                )
            ]
        )
        let rejected = associator.associate(
            timestampNanoseconds: 2,
            samples: [
                UnassociatedFeatureSample(
                    world: VisibilityGridPoint(x: 1, y: 0, z: 0),
                    confidence: 1
                )
            ]
        )
        XCTAssertTrue(rejected.samples.isEmpty)
        XCTAssertEqual(rejected.rejectedSamples, 1)

        let afterExpiry = associator.associate(
            timestampNanoseconds: 12,
            samples: [
                UnassociatedFeatureSample(
                    world: VisibilityGridPoint(x: 1, y: 0, z: 0),
                    confidence: 1
                )
            ]
        )
        XCTAssertEqual(afterExpiry.samples.count, 1)
        XCTAssertNotEqual(
            afterExpiry.samples[0].identifier,
            first.samples[0].identifier
        )
    }

    func testAssociationChoosesGloballyNearestCandidateFirst() throws {
        let associator = try BoundedFeatureAssociator(capacity: 2)
        let first = associator.associate(
            timestampNanoseconds: 1,
            samples: [
                UnassociatedFeatureSample(
                    world: VisibilityGridPoint(x: 0.1, y: 0, z: 0),
                    confidence: 1
                ),
                UnassociatedFeatureSample(
                    world: VisibilityGridPoint(x: 0.3, y: 0, z: 0),
                    confidence: 1
                )
            ]
        )
        let nearestTrack = try XCTUnwrap(
            first.samples.first { $0.world.x == 0.1 }
        )

        let second = associator.associate(
            timestampNanoseconds: 2,
            samples: [
                UnassociatedFeatureSample(
                    world: VisibilityGridPoint(x: 0, y: 0, z: 0),
                    confidence: 1
                ),
                UnassociatedFeatureSample(
                    world: VisibilityGridPoint(x: 0.09, y: 0, z: 0),
                    confidence: 1
                )
            ]
        )

        XCTAssertEqual(second.samples.count, 1)
        XCTAssertEqual(second.rejectedSamples, 1)
        XCTAssertEqual(second.samples[0].identifier, nearestTrack.identifier)
        XCTAssertEqual(second.samples[0].world.x, 0.09)
    }

    func testAssociationCapacityIsClampedToItsMemoryBudget() throws {
        let associator = try BoundedFeatureAssociator(capacity: 200_000)
        XCTAssertEqual(associator.acceptedCapacity, 12_288)
    }

    func testSensorHandoffCoalescesFeatureAndDepthIndependently() {
        let handoff = LatestSensorHandoff<String, Int>()
        XCTAssertFalse(handoff.offerFeature("feature-1"))
        XCTAssertFalse(handoff.offerDepth(1))
        XCTAssertTrue(handoff.offerFeature("feature-2"))

        let first = handoff.take()
        XCTAssertEqual(first?.feature, "feature-2")
        XCTAssertEqual(first?.depth, 1)
        XCTAssertFalse(handoff.hasPending)

        XCTAssertFalse(handoff.offerDepth(2))
        XCTAssertTrue(handoff.offerDepth(3))
        let second = handoff.take()
        XCTAssertNil(second?.feature)
        XCTAssertEqual(second?.depth, 3)
    }

    func testSensorHandoffProcessesTimestampOrderAfterCoalescing() {
        struct Timed {
            let timestampNanoseconds: Int64
        }

        let handoff = LatestSensorHandoff<Timed, Timed>()
        handoff.offerFeature(Timed(timestampNanoseconds: 30))
        handoff.offerDepth(Timed(timestampNanoseconds: 20))
        let depthFirst = handoff.take()
        XCTAssertEqual(
            sensorProcessingOrder(
                featureTimestampNanoseconds:
                    depthFirst?.feature?.timestampNanoseconds,
                depthTimestampNanoseconds:
                    depthFirst?.depth?.timestampNanoseconds
            ),
            [.depth, .feature]
        )

        handoff.offerFeature(Timed(timestampNanoseconds: 10))
        handoff.offerDepth(Timed(timestampNanoseconds: 40))
        let featureFirst = handoff.take()
        XCTAssertEqual(
            sensorProcessingOrder(
                featureTimestampNanoseconds:
                    featureFirst?.feature?.timestampNanoseconds,
                depthTimestampNanoseconds:
                    featureFirst?.depth?.timestampNanoseconds
            ),
            [.feature, .depth]
        )

        handoff.offerFeature(Timed(timestampNanoseconds: 50))
        XCTAssertTrue(
            handoff.offerFeature(Timed(timestampNanoseconds: 15))
        )
        handoff.offerDepth(Timed(timestampNanoseconds: 25))
        let displaced = handoff.take()
        XCTAssertEqual(
            sensorProcessingOrder(
                featureTimestampNanoseconds:
                    displaced?.feature?.timestampNanoseconds,
                depthTimestampNanoseconds:
                    displaced?.depth?.timestampNanoseconds
            ),
            [.feature, .depth]
        )
    }

    func testZeroAcceptedObservationsDoNotEnterNormalizedFeatureP95() throws {
        let grid = try NativeVisibilityGrid(
            featureConfiguration: .fixture(
                candidateSamples: 1,
                candidateSpanNanoseconds: 0
            ),
            depthConfiguration: nil
        )
        try grid.startGroup(.fixture())
        try grid.observeFeatures(
            FeatureObservation(
                timestampNanoseconds: 1,
                groupGeneration: 1,
                sessionGeneration: 1,
                samples: [
                    FeatureSample(
                        identifier: 1,
                        world: VisibilityGridPoint(x: 0.02, y: 0.02, z: 0.02),
                        confidence: 1
                    )
                ]
            )
        )
        let p95AfterAccepted =
            grid.snapshot().diagnostics.featureFusionP95Nanoseconds

        try grid.observeFeatures(
            FeatureObservation(
                timestampNanoseconds: 2,
                groupGeneration: 1,
                sessionGeneration: 1,
                samples: []
            )
        )

        XCTAssertEqual(
            grid.snapshot().diagnostics.featureFusionP95Nanoseconds,
            p95AfterAccepted
        )
    }
}

extension VisibilityGridFeatureConfiguration {
    static func fixture(
        candidateSamples: Int = 5,
        candidateSpanNanoseconds: Int64 = 500_000_000,
        publishIntervalMilliseconds: Int = 500
    ) -> VisibilityGridFeatureConfiguration {
        try! VisibilityGridFeatureConfiguration(
            stableVoxelCapacity: 100,
            featureTrackCapacity: 200,
            maxFeaturesPerObservation: 2_000,
            publishIntervalMilliseconds: publishIntervalMilliseconds,
            minimumConfidence: 0.3,
            candidateSamples: candidateSamples,
            candidateSpanNanoseconds: candidateSpanNanoseconds
        )
    }
}

extension VisibilityGridGroupConfiguration {
    static func fixture() throws -> VisibilityGridGroupConfiguration {
        try VisibilityGridGroupConfiguration(
            groupId: "group",
            groupGeneration: 1,
            sessionGeneration: 1,
            voxelSizeMeters: 0.1,
            capacity: 100,
            groupFromWorldGL: identityVisibilityGridTransform(),
            worldFromGroupGL: identityVisibilityGridTransform()
        )
    }
}
