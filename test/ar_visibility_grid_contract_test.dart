import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/models/ar_visibility_grid.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('visibility_grid_wire_v1 geometry delta', () {
    test('decodes a revisioned cleaned-grid delta', () {
      final delta = ARVisibilityGridDelta.fromMap(<Object?, Object?>{
        'version': visibilityGridWireVersion,
        'groupId': 'group-1',
        'groupGeneration': 7,
        'sessionGeneration': 11,
        'baseGeometryRevision': 3,
        'geometryRevision': 4,
        'reset': false,
        'upsertKeys': Int64List.fromList(<int>[10, 20]),
        'removalKeys': Int64List.fromList(<int>[30]),
        'capacity': 100000,
        'sourceHealth': <Object?, Object?>{
          'feature': 'healthy',
          'depth': 'featureOnly',
          'renderer': 'healthy',
          'totalGrid': 'healthy',
        },
        'diagnostics': <Object?, Object?>{
          'candidateTracks': 2,
          'stableTracks': 18,
          'stableVoxels': 17,
          'featureTrackCapacity': 200000,
          'stableVoxelCapacity': 100000,
          'featureObservationCount': 42,
          'featureMigrations': 3,
          'featureJumpResets': 1,
          'candidateExpirations': 4,
          'supportRemovals': 5,
          'acceptedSamples': 123,
          'rejectedSamples': 6,
          'capacityRejectedCandidates': 7,
          'featureTransientUnavailableCount': 8,
          'featureFailureCount': 9,
          'lastFeatureFusionNs': 1000,
          'maxFeatureFusionNs': 2000,
          'featureFusionP95Ns': 1500,
          'estimatedStateBytes': 4096,
          'depthObservationCount': 10,
          'depthAcceptedPixels': 11,
          'depthRejectedPixels': 12,
          'depthCapacityRejectedPixels': 13,
          'depthRayVisits': 14,
          'carvedVoxels': 15,
          'restoredVoxels': 16,
          'depthTransientUnavailableCount': 17,
          'depthFailureCount': 18,
          'lastDepthFusionNs': 3000,
          'maxDepthFusionNs': 4000,
          'depthFusionP95Ns': 3500,
          'callbackCopyP95Ns': 500,
          'coalescedFeatureObservations': 2,
          'coalescedDepthObservations': 1,
          'coalescedGeometryChanges': 19,
          'geometryRevision': 4,
          'pendingGeometryKeys': 0,
          'unacknowledgedGeometryCallbacks': 1,
          'publishedDeltaCount': 20,
          'snapshotRecoveryCount': 2,
          'geometryAcknowledgementCount': 18,
          'rendererRows': 17,
          'rendererFreeRows': 99983,
        },
      });

      expect(delta.groupId, 'group-1');
      expect(delta.groupGeneration, 7);
      expect(delta.sessionGeneration, 11);
      expect(delta.baseGeometryRevision, 3);
      expect(delta.geometryRevision, 4);
      expect(delta.upsertKeys, <int>[10, 20]);
      expect(delta.removalKeys, <int>[30]);
      expect(delta.sourceHealth.depth, ARVisibilityGridSourceState.featureOnly);
      expect(delta.diagnostics.featureMigrations, 3);
      expect(delta.diagnostics.featureJumpResets, 1);
      expect(delta.diagnostics.carvedVoxels, 15);
      expect(delta.diagnostics.restoredVoxels, 16);
      expect(delta.diagnostics.featureFusionP95Ns, 1500);
      expect(delta.diagnostics.depthFusionP95Ns, 3500);
      expect(delta.diagnostics.rendererRows, 17);
      expect(delta.diagnostics.rendererFreeRows, 99983);
    });

    test('accepts feature p95 normalized from fewer than 1000 points', () {
      final diagnostics = _initialDiagnostics(100, 200)
        ..['acceptedSamples'] = 100
        ..['lastFeatureFusionNs'] = 1000
        ..['maxFeatureFusionNs'] = 1000
        ..['featureFusionP95Ns'] = 10000;

      expect(
        ARVisibilityGridDiagnostics.fromMap(diagnostics).featureFusionP95Ns,
        10000,
      );
    });

    test('rejects a non-reset revision gap', () {
      expect(
        () => ARVisibilityGridDelta.fromMap(<Object?, Object?>{
          'version': visibilityGridWireVersion,
          'groupId': 'group-1',
          'groupGeneration': 7,
          'sessionGeneration': 11,
          'baseGeometryRevision': 3,
          'geometryRevision': 5,
          'reset': false,
          'upsertKeys': Int64List(0),
          'removalKeys': Int64List(0),
          'capacity': 100000,
          'sourceHealth': <Object?, Object?>{
            'feature': 'healthy',
            'depth': 'unsupported',
            'renderer': 'healthy',
            'totalGrid': 'healthy',
          },
        }),
        throwsFormatException,
      );
    });

    test('rejects a key present in both final-state lists', () {
      expect(
        () => ARVisibilityGridDelta.fromMap(<Object?, Object?>{
          'version': visibilityGridWireVersion,
          'groupId': 'group-1',
          'groupGeneration': 7,
          'sessionGeneration': 11,
          'baseGeometryRevision': 3,
          'geometryRevision': 4,
          'reset': false,
          'upsertKeys': Int64List.fromList(<int>[10]),
          'removalKeys': Int64List.fromList(<int>[10]),
          'capacity': 100000,
          'sourceHealth': <Object?, Object?>{
            'feature': 'healthy',
            'depth': 'transientUnavailable',
            'renderer': 'healthy',
            'totalGrid': 'healthy',
          },
        }),
        throwsFormatException,
      );
    });

    test('rejects duplicate keys within a final-state list', () {
      expect(
        () => ARVisibilityGridDelta.fromMap(<Object?, Object?>{
          'version': visibilityGridWireVersion,
          'groupId': 'group-1',
          'groupGeneration': 7,
          'sessionGeneration': 11,
          'baseGeometryRevision': 3,
          'geometryRevision': 4,
          'reset': false,
          'upsertKeys': Int64List.fromList(<int>[10, 10]),
          'removalKeys': Int64List(0),
          'capacity': 100000,
          'sourceHealth': <Object?, Object?>{
            'feature': 'healthy',
            'depth': 'featureOnly',
            'renderer': 'healthy',
            'totalGrid': 'healthy',
          },
        }),
        throwsFormatException,
      );
    });
  });

  group('visibility_grid_wire_v1 outbound contract', () {
    test('encodes bounded native initialization budgets', () {
      final config = ARVisibilityGridNativeConfig(syntheticSource: true);

      expect(config.toMap(), <String, Object>{
        'version': visibilityGridWireVersion,
        'renderCapacity': 100000,
        'featureTrackCapacity': 200000,
        'maxFeaturesPerObservation': 2000,
        'maxDepthPixelsPerObservation': 4096,
        'maxRayVisitsPerObservation': 65536,
        'publishIntervalMs': 500,
        'featureConfidenceMinimum': 0.3,
        'depthConfidenceMinimum': 128,
        'syntheticSource': true,
        'defaultColor': 0xFFFF0000,
        'pointSizePx': 6.0,
        'enabled': true,
        'voxelRenderMode': 'centroids',
        'cubeSizeFactor': 1.0,
      });
    });

    test('rejects invalid native budgets at runtime', () {
      expect(
        () => ARVisibilityGridNativeConfig(renderCapacity: 100001),
        throwsArgumentError,
      );
      expect(
        () => ARVisibilityGridNativeConfig(publishIntervalMs: 499),
        throwsArgumentError,
      );
      expect(
        () => ARVisibilityGridNativeConfig(
          featureConfidenceMinimum: double.nan,
        ),
        throwsArgumentError,
      );
    });

    test('encodes group transforms and restored baseline keys', () {
      final worldFromGroup = Float64List.fromList(<double>[
        1,
        0,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        0,
        1,
        0,
        1,
        2,
        3,
        1,
      ]);
      final groupFromWorld = Float64List.fromList(<double>[
        1,
        0,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        0,
        1,
        0,
        -1,
        -2,
        -3,
        1,
      ]);
      final config = ARVisibilityGridGroupConfig(
        groupId: 'group-1',
        groupGeneration: 7,
        voxelSizeMeters: 0.1,
        capacity: 100000,
        worldFromGroupGl: worldFromGroup,
        groupFromWorldGl: groupFromWorld,
        restoredGeometryRevision: 12,
        restoredVisibilityRevision: 8,
        restoredKeys: Int64List.fromList(<int>[10, 20]),
      );

      final map = config.toMap();

      expect(map['version'], visibilityGridWireVersion);
      expect(map['groupFrameConvention'], 'gravity_y_up_meters_v1');
      expect(map['matrixConvention'], 'column_major_gl_v1');
      expect(map['restoredGeometryRevision'], 12);
      expect(map['restoredVisibilityRevision'], 8);
      expect(map['restoredKeys'], <int>[10, 20]);
    });

    test('rejects a malformed group transform', () {
      expect(
        () => ARVisibilityGridGroupConfig(
          groupId: 'group-1',
          groupGeneration: 7,
          voxelSizeMeters: 0.1,
          capacity: 100000,
          worldFromGroupGl: Float64List(15),
          groupFromWorldGl: Float64List(16),
        ),
        throwsArgumentError,
      );
    });

    test('rejects transforms that are not mutual inverses', () {
      expect(
        () => ARVisibilityGridGroupConfig(
          groupId: 'group-1',
          groupGeneration: 7,
          voxelSizeMeters: 0.1,
          capacity: 100000,
          worldFromGroupGl: Float64List.fromList(<double>[
            1,
            0,
            0,
            0,
            0,
            1,
            0,
            0,
            0,
            0,
            1,
            0,
            1,
            2,
            3,
            1,
          ]),
          groupFromWorldGl: Float64List.fromList(<double>[
            1,
            0,
            0,
            0,
            0,
            1,
            0,
            0,
            0,
            0,
            1,
            0,
            0,
            0,
            0,
            1,
          ]),
        ),
        throwsArgumentError,
      );
    });

    test('rejects duplicate or over-capacity restored keys', () {
      final identity = Float64List.fromList(<double>[
        1,
        0,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        0,
        1,
      ]);
      expect(
        () => ARVisibilityGridGroupConfig(
          groupId: 'group-1',
          groupGeneration: 7,
          voxelSizeMeters: 0.1,
          capacity: 2,
          worldFromGroupGl: identity,
          groupFromWorldGl: identity,
          restoredGeometryRevision: 1,
          restoredKeys: Int64List.fromList(<int>[10, 10]),
        ),
        throwsArgumentError,
      );
      expect(
        () => ARVisibilityGridGroupConfig(
          groupId: 'group-1',
          groupGeneration: 7,
          voxelSizeMeters: 0.1,
          capacity: 1,
          worldFromGroupGl: identity,
          groupFromWorldGl: identity,
          restoredGeometryRevision: 1,
          restoredKeys: Int64List.fromList(<int>[10, 20]),
        ),
        throwsArgumentError,
      );
    });

    test('encodes a color-only visibility patch', () {
      final patch = ARVisibilityGridVisibilityPatch(
        groupId: 'group-1',
        groupGeneration: 7,
        sessionGeneration: 11,
        geometryRevision: 4,
        visibilityRevision: 9,
        keys: Int64List.fromList(<int>[10, 20]),
        colors: Int32List.fromList(<int>[0xFFFF0000, 0xFF00FF00]),
      );

      final map = patch.toMap();

      expect(map['geometryRevision'], 4);
      expect(map['visibilityRevision'], 9);
      expect(map['keys'], <int>[10, 20]);
      expect(map['colors'], <int>[-65536, -16711936]);
      expect(map, isNot(contains('positions')));
    });

    test('rejects visibility keys and colors with different lengths', () {
      expect(
        () => ARVisibilityGridVisibilityPatch(
          groupId: 'group-1',
          groupGeneration: 7,
          sessionGeneration: 11,
          geometryRevision: 4,
          visibilityRevision: 9,
          keys: Int64List.fromList(<int>[10]),
          colors: Int32List(0),
        ),
        throwsArgumentError,
      );
    });

    test('rejects duplicate visibility keys', () {
      expect(
        () => ARVisibilityGridVisibilityPatch(
          groupId: 'group-1',
          groupGeneration: 7,
          sessionGeneration: 11,
          geometryRevision: 4,
          visibilityRevision: 9,
          keys: Int64List.fromList(<int>[10, 10]),
          colors: Int32List.fromList(<int>[0xFFFF0000, 0xFF00FF00]),
        ),
        throwsArgumentError,
      );
    });
  });

  group('visibility_grid_wire_v1 native status', () {
    test('decodes capability separately from configured and active depth', () {
      final result = ARVisibilityGridInitializationResult.fromMap(
        <Object?, Object?>{
          'version': visibilityGridWireVersion,
          'sessionGeneration': 11,
          'rendererReady': true,
          'featureReady': true,
          'depthCapability': 'rawDepthOnly',
          'depthConfigured': true,
          'depthActiveMode': 'rawDepthOnly',
          'renderCapacity': 100000,
          'featureTrackCapacity': 200000,
          'diagnostics': _initialDiagnostics(100000, 200000),
          'health': <Object?, Object?>{
            'feature': 'healthy',
            'depth': 'transientUnavailable',
            'renderer': 'healthy',
            'totalGrid': 'healthy',
          },
        },
      );

      expect(
          result.depthCapability, ARVisibilityGridDepthCapability.rawDepthOnly);
      expect(result.depthConfigured, isTrue);
      expect(
        result.depthActiveMode,
        ARVisibilityGridDepthMode.rawDepthOnly,
      );
      expect(
        result.health.depth,
        ARVisibilityGridSourceState.transientUnavailable,
      );
      expect(result.diagnostics.featureTrackCapacity, 200000);
      expect(result.diagnostics.rendererFreeRows, 100000);
    });

    test('rejects health state used as a selected depth mode', () {
      expect(
        () => ARVisibilityGridInitializationResult.fromMap(
          <Object?, Object?>{
            'version': visibilityGridWireVersion,
            'sessionGeneration': 11,
            'rendererReady': true,
            'featureReady': true,
            'depthCapability': 'rawDepthOnly',
            'depthConfigured': true,
            'depthActiveMode': 'transientUnavailable',
            'renderCapacity': 100000,
            'featureTrackCapacity': 200000,
            'diagnostics': _initialDiagnostics(100000, 200000),
            'health': <Object?, Object?>{
              'feature': 'healthy',
              'depth': 'transientUnavailable',
              'renderer': 'healthy',
              'totalGrid': 'healthy',
            },
          },
        ),
        throwsFormatException,
      );
    });

    test('decodes fatal scopes independently', () {
      final error = ARVisibilityGridError.fromMap(<Object?, Object?>{
        'code': 'VG_DEPTH_FAILED',
        'message': 'Depth operational probe failed.',
        'recoverable': true,
        'fatalToFeature': false,
        'fatalToDepth': true,
        'fatalToRenderer': false,
        'fatalToGrid': false,
        'groupGeneration': 7,
        'sessionGeneration': 11,
      });

      expect(error.code, ARVisibilityGridErrorCode.depthFailed);
      expect(error.recoverable, isTrue);
      expect(error.fatalToDepth, isTrue);
      expect(error.fatalToGrid, isFalse);
    });

    test('rejects an unknown typed error', () {
      expect(
        () => ARVisibilityGridError.fromMap(<Object?, Object?>{
          'code': 'VG_MYSTERY',
          'message': 'Unknown.',
          'recoverable': false,
          'fatalToFeature': false,
          'fatalToDepth': false,
          'fatalToRenderer': false,
          'fatalToGrid': true,
        }),
        throwsFormatException,
      );
    });
  });

  test('shared fixture corpus pins cross-platform contract scenarios', () {
    final fixture = jsonDecode(
      File(
        'test/fixtures/visibility_grid/visibility_grid_wire_v1.json',
      ).readAsStringSync(),
    ) as Map<String, dynamic>;

    expect(fixture['version'], visibilityGridWireVersion);
    expect(fixture['voxelKeyConvention'], 'coverage_grid_v3');
    expect(fixture['matrixConvention'], 'column_major_gl_meters_v1');
    final scenarios =
        (fixture['scenarios'] as List<dynamic>).cast<Map<String, dynamic>>();
    expect(
      scenarios.map((scenario) => scenario['name']),
      containsAll(<String>[
        'group_transform_and_packed_keys',
        'persistent_id_relocation',
        'shared_feature_support',
        'android_raw_depth_unprojection',
        'depth_safe_band_and_multiview_carving',
        'ios_scene_depth_orientation_and_fallback',
        'source_health_and_resource_closure',
        'geometry_revision_and_resync',
      ]),
    );

    final transform = scenarios.firstWhere(
      (scenario) => scenario['name'] == 'group_transform_and_packed_keys',
    );
    final groupFromWorld = _doubleList(transform['groupFromWorldGl']);
    final worldPoints =
        (transform['worldPoints'] as List<dynamic>).map(_doubleList).toList();
    final computedCoordinates = worldPoints
        .map((point) => _transformPoint(groupFromWorld, point))
        .map(
          (point) =>
              point.map((coordinate) => (coordinate / 0.1).floor()).toList(),
        )
        .toList();
    expect(
      computedCoordinates,
      (transform['expectedGroupCellCoordinates'] as List<dynamic>)
          .map((value) => (value as List<dynamic>).cast<int>())
          .toList(),
    );
    expect(
      computedCoordinates
          .map((coordinates) => _packKey(coordinates).toString())
          .toList(),
      (transform['expectedPackedKeys'] as List<dynamic>).cast<String>(),
    );

    final unprojection = scenarios.firstWhere(
      (scenario) => scenario['name'] == 'android_raw_depth_unprojection',
    );
    final frame = _map(unprojection['sourceFrame']);
    final intrinsics = _map(frame['intrinsics']);
    final sample = _map(unprojection['sample']);
    final pixel = (sample['pixel'] as List<dynamic>).cast<int>();
    final depthMeters = (sample['depthMillimeters'] as num).toDouble() / 1000;
    final cameraPoint = <double>[
      (pixel[0] - (intrinsics['cx'] as num).toDouble()) *
          depthMeters /
          (intrinsics['fx'] as num).toDouble(),
      -(pixel[1] - (intrinsics['cy'] as num).toDouble()) *
          depthMeters /
          (intrinsics['fy'] as num).toDouble(),
      -depthMeters,
    ];
    _expectClose(cameraPoint, _doubleList(unprojection['expectedCameraGl']));
    final worldPoint = _transformPoint(
      _doubleList(frame['worldFromCameraGl']),
      cameraPoint,
    );
    final groupPoint = _transformPoint(
      _doubleList(frame['groupFromWorldGl']),
      worldPoint,
    );
    _expectClose(groupPoint, _doubleList(unprojection['expectedGroupPoint']));
    final cellCoordinates =
        groupPoint.map((coordinate) => (coordinate / 0.1).floor()).toList();
    expect(
      cellCoordinates,
      (unprojection['expectedCellCoordinates'] as List<dynamic>).cast<int>(),
    );
    expect(
      _packKey(cellCoordinates).toString(),
      unprojection['expectedPackedKey'],
    );

    final carving = scenarios.firstWhere(
      (scenario) => scenario['name'] == 'depth_safe_band_and_multiview_carving',
    );
    expect(carving['safetyBandMeters'], 0.15);
    expect(carving['singleViewRemovesOccupied'], isFalse);
    expect(carving['separatedDirectionBinsRequired'], 2);
    expect(
      _referenceCarvingStates(carving),
      (carving['evidenceSteps'] as List<dynamic>)
          .map((step) => _map(step)['expectedState'])
          .toList(),
    );
    expect(
      _referenceFreeKeys(carving).map((key) => key.toString()).toList(),
      (carving['freeKeysBeforeSafetyBand'] as List<dynamic>).cast<String>(),
    );

    final movement = scenarios.firstWhere(
      (scenario) => scenario['name'] == 'persistent_id_relocation',
    );
    final featureResult = _referenceFeatureStates(
      movement,
      _map(fixture['defaults']),
    );
    expect(
      featureResult.states,
      (movement['observations'] as List<dynamic>)
          .map((observation) => _map(observation)['expectedState'])
          .toList(),
    );
    expect(featureResult.promotedKey.toString(), movement['promotedKey']);
    expect(featureResult.relocatedKey.toString(), movement['relocatedKey']);
    final jump = _map(movement['jumpObservation']);
    expect(featureResult.jumpState, jump['expectedState']);
    expect(featureResult.jumpRemovedKey.toString(), jump['expectedRemovedKey']);
    expect(
        featureResult.jumpContributedVoxels, jump['expectedContributedVoxels']);

    final sharedSupport = scenarios.firstWhere(
      (scenario) => scenario['name'] == 'shared_feature_support',
    );
    final featureIds = (sharedSupport['featureIds'] as List<dynamic>).toSet();
    expect(featureIds.length, sharedSupport['supportAfterPromotion']);
    featureIds.remove(sharedSupport['removedFeatureId']);
    expect(featureIds.length, sharedSupport['supportAfterRemoval']);
    expect(featureIds.isNotEmpty, sharedSupport['voxelRemainsOccupied']);

    final ios = scenarios.firstWhere(
      (scenario) =>
          scenario['name'] == 'ios_scene_depth_orientation_and_fallback',
    );
    final iosFrame = _map(ios['sourceFrame']);
    final rawPixel = (ios['rawPixel'] as List<dynamic>).cast<int>();
    final orientedPixel = <int>[
      rawPixel[1],
      (iosFrame['imageWidth'] as int) - 1 - rawPixel[0],
    ];
    expect(
      orientedPixel,
      (ios['expectedOrientedPixel'] as List<dynamic>).cast<int>(),
    );
    const confidenceRank = <String, int>{'low': 0, 'medium': 1, 'high': 2};
    final minimum = confidenceRank[ios['confidenceMinimum']]!;
    for (final value in ios['samples'] as List<dynamic>) {
      final sample = _map(value);
      expect(
        confidenceRank[sample['confidence']]! >= minimum,
        sample['expectedAccepted'],
      );
    }
    expect(
      _map(ios['unsupported'])['expectedActiveMode'],
      'featureOnly',
    );

    final resources = scenarios.firstWhere(
      (scenario) => scenario['name'] == 'source_health_and_resource_closure',
    );
    final android = _map(resources['android']);
    for (final eventName in <String>['usableFrame', 'transientUnavailable']) {
      final event = _map(android[eventName]);
      expect(event['depthImagesClosed'], event['depthImagesAcquired']);
      expect(
        event['confidenceImagesClosed'],
        event['confidenceImagesAcquired'],
      );
    }
    final iosResources = _map(resources['ios']);
    expect(
      iosResources['sceneDepthBuffersReleased'],
      iosResources['sceneDepthBuffersRetained'],
    );
    expect(
      iosResources['confidenceBuffersReleased'],
      iosResources['confidenceBuffersRetained'],
    );

    final revisions = scenarios.firstWhere(
      (scenario) => scenario['name'] == 'geometry_revision_and_resync',
    );
    final ordered = _map(revisions['orderedDelta']);
    expect(
      ordered['geometryRevision'],
      (ordered['baseGeometryRevision'] as int) + 1,
    );
    final gap = _map(revisions['gapDelta']);
    expect(
      gap['baseGeometryRevision'],
      isNot(gap['receiverGeometryRevision']),
    );
    expect(
      gap['geometryRevision'],
      (gap['baseGeometryRevision'] as int) + 1,
    );
    expect(revisions['expectedGapAction'], 'requestSnapshot');
    final snapshot = _map(revisions['snapshot']);
    expect(snapshot['reset'], isTrue);
    expect(snapshot['geometryRevision'], gap['geometryRevision']);
    expect(
      revisions['acknowledgedGeometryRevision'],
      snapshot['geometryRevision'],
    );
  });
}

Map<String, Object> _initialDiagnostics(int capacity, int featureCapacity) =>
    <String, Object>{
      'candidateTracks': 0,
      'stableTracks': 0,
      'stableVoxels': 0,
      'featureTrackCapacity': featureCapacity,
      'stableVoxelCapacity': capacity,
      'featureObservationCount': 0,
      'featureMigrations': 0,
      'featureJumpResets': 0,
      'candidateExpirations': 0,
      'supportRemovals': 0,
      'acceptedSamples': 0,
      'rejectedSamples': 0,
      'capacityRejectedCandidates': 0,
      'featureTransientUnavailableCount': 0,
      'featureFailureCount': 0,
      'lastFeatureFusionNs': 0,
      'maxFeatureFusionNs': 0,
      'featureFusionP95Ns': 0,
      'estimatedStateBytes': 0,
      'depthObservationCount': 0,
      'depthAcceptedPixels': 0,
      'depthRejectedPixels': 0,
      'depthCapacityRejectedPixels': 0,
      'depthRayVisits': 0,
      'carvedVoxels': 0,
      'restoredVoxels': 0,
      'depthTransientUnavailableCount': 0,
      'depthFailureCount': 0,
      'lastDepthFusionNs': 0,
      'maxDepthFusionNs': 0,
      'depthFusionP95Ns': 0,
      'callbackCopyP95Ns': 0,
      'coalescedFeatureObservations': 0,
      'coalescedDepthObservations': 0,
      'coalescedGeometryChanges': 0,
      'geometryRevision': 0,
      'pendingGeometryKeys': 0,
      'unacknowledgedGeometryCallbacks': 0,
      'publishedDeltaCount': 0,
      'snapshotRecoveryCount': 0,
      'geometryAcknowledgementCount': 0,
      'rendererRows': 0,
      'rendererFreeRows': capacity,
    };

Map<String, dynamic> _map(Object? value) => (value as Map<String, dynamic>);

List<double> _doubleList(Object? value) => (value as List<dynamic>)
    .map((element) => (element as num).toDouble())
    .toList();

List<double> _transformPoint(List<double> matrix, List<double> point) {
  final homogeneous = <double>[...point, 1];
  return List<double>.generate(
    3,
    (row) => List<double>.generate(
      4,
      (column) => matrix[column * 4 + row] * homogeneous[column],
    ).reduce((sum, value) => sum + value),
  );
}

int _packKey(List<int> coordinates) {
  const bias = 1 << 20;
  return ((coordinates[0] + bias) << 42) |
      ((coordinates[1] + bias) << 21) |
      (coordinates[2] + bias);
}

void _expectClose(List<double> actual, List<double> expected) {
  expect(actual, hasLength(expected.length));
  for (var index = 0; index < expected.length; index++) {
    expect(actual[index], closeTo(expected[index], 1e-9));
  }
}

class _FeatureReferenceResult {
  const _FeatureReferenceResult({
    required this.states,
    required this.promotedKey,
    required this.relocatedKey,
    required this.jumpState,
    required this.jumpRemovedKey,
    required this.jumpContributedVoxels,
  });

  final List<String> states;
  final int promotedKey;
  final int relocatedKey;
  final String jumpState;
  final int jumpRemovedKey;
  final int jumpContributedVoxels;
}

_FeatureReferenceResult _referenceFeatureStates(
  Map<String, dynamic> fixture,
  Map<String, dynamic> defaults,
) {
  final voxelSize = (defaults['voxelSizeMeters'] as num).toDouble();
  final requiredSamples = defaults['candidateSamples'] as int;
  final requiredSpan = defaults['candidateSpanNs'] as int;
  final maxStdDev = (defaults['candidateMaxStdDevMeters'] as num).toDouble();
  final hysteresis = (defaults['relocationHysteresisMeters'] as num).toDouble();
  final jumpThreshold = (defaults['jumpResetMeters'] as num).toDouble();
  final samples = <List<double>>[];
  final states = <String>[];
  List<double>? filtered;
  int? firstTimestamp;
  List<int>? stableCoordinates;
  int? promotedKey;
  int? relocatedKey;

  for (final value in fixture['observations'] as List<dynamic>) {
    final observation = _map(value);
    final position = _doubleList(observation['positionGroup']);
    final timestamp = observation['timestampNs'] as int;
    final confidence = (observation['confidence'] as num).toDouble();
    if (filtered == null) {
      filtered = List<double>.from(position);
    } else {
      final alpha = (0.15 + 0.35 * confidence).clamp(0.15, 0.50);
      final previous = filtered;
      filtered = List<double>.generate(
        3,
        (index) =>
            previous[index] + alpha * (position[index] - previous[index]),
      );
    }

    if (stableCoordinates == null) {
      firstTimestamp ??= timestamp;
      samples.add(position);
      final stableEnough = samples.length >= requiredSamples &&
          timestamp - firstTimestamp >= requiredSpan &&
          List<int>.generate(3, (index) => index).every((axis) {
            final mean =
                samples.map((sample) => sample[axis]).reduce((a, b) => a + b) /
                    samples.length;
            final variance = samples
                    .map((sample) => math.pow(sample[axis] - mean, 2))
                    .reduce((a, b) => a + b) /
                samples.length;
            return math.sqrt(variance) <= maxStdDev;
          });
      if (stableEnough) {
        stableCoordinates = filtered
            .map((coordinate) => (coordinate / voxelSize).floor())
            .toList();
        promotedKey = _packKey(stableCoordinates);
        states.add('stable');
      } else {
        states.add('candidate');
      }
      continue;
    }

    final nextCoordinates = List<int>.from(stableCoordinates);
    for (var axis = 0; axis < 3; axis++) {
      final lower = stableCoordinates[axis] * voxelSize - hysteresis;
      final upper = (stableCoordinates[axis] + 1) * voxelSize + hysteresis;
      if (filtered[axis] < lower || filtered[axis] >= upper) {
        nextCoordinates[axis] = (filtered[axis] / voxelSize).floor();
      }
    }
    if (_packKey(nextCoordinates) != _packKey(stableCoordinates)) {
      stableCoordinates = nextCoordinates;
      relocatedKey = _packKey(stableCoordinates);
      states.add('relocated');
    } else {
      states.add('stable');
    }
  }

  final jump = _map(fixture['jumpObservation']);
  final jumpPosition = _doubleList(jump['positionGroup']);
  final jumpDistance = math.sqrt(
    List<int>.generate(
      3,
      (index) => index,
    ).map((axis) => math.pow(jumpPosition[axis] - filtered![axis], 2)).reduce(
          (a, b) => a + b,
        ),
  );
  final removedKey = _packKey(stableCoordinates!);
  return _FeatureReferenceResult(
    states: states,
    promotedKey: promotedKey!,
    relocatedKey: relocatedKey!,
    jumpState: jumpDistance >= jumpThreshold ? 'candidate' : 'stable',
    jumpRemovedKey: removedKey,
    jumpContributedVoxels: jumpDistance >= jumpThreshold ? 0 : 1,
  );
}

List<String> _referenceCarvingStates(Map<String, dynamic> fixture) {
  final freeThreshold = fixture['freeEvidenceToCarve'] as int;
  final occupiedRestore = fixture['occupiedEvidenceToRestore'] as int;
  final requiredBins = fixture['separatedDirectionBinsRequired'] as int;
  var contradicted = false;
  return (fixture['evidenceSteps'] as List<dynamic>).map((value) {
    final step = _map(value);
    final occupied = step['occupied'] as int;
    final free = step['free'] as int;
    final bins = (step['directionBins'] as List<dynamic>).toSet().length;
    if (contradicted && occupied >= occupiedRestore) {
      contradicted = false;
      return 'restored';
    }
    if (!contradicted &&
        free >= freeThreshold &&
        bins >= requiredBins &&
        free >= occupied + 4) {
      contradicted = true;
      return 'contradicted';
    }
    return 'occupied';
  }).toList();
}

List<int> _referenceFreeKeys(Map<String, dynamic> fixture) {
  final start = _doubleList(fixture['cameraGroup']);
  final end = _doubleList(fixture['surfaceGroup']);
  final safety = (fixture['safetyBandMeters'] as num).toDouble();
  const voxelSize = 0.1;
  final delta = List<double>.generate(3, (index) => end[index] - start[index]);
  final length =
      math.sqrt(delta.map((value) => value * value).reduce((a, b) => a + b));
  final direction = delta.map((value) => value / length).toList();
  final keys = <int>[];
  for (var distance = voxelSize;
      distance <= length - safety + 1e-9;
      distance += voxelSize) {
    final point = List<double>.generate(
      3,
      (index) => start[index] + direction[index] * distance,
    );
    final coordinates =
        point.map((coordinate) => (coordinate / voxelSize).floor()).toList();
    final key = _packKey(coordinates);
    if (keys.isEmpty || keys.last != key) keys.add(key);
  }
  return keys;
}
