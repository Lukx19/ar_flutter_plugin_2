import 'dart:convert';
import 'dart:io';
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
      });

      expect(delta.groupId, 'group-1');
      expect(delta.groupGeneration, 7);
      expect(delta.sessionGeneration, 11);
      expect(delta.baseGeometryRevision, 3);
      expect(delta.geometryRevision, 4);
      expect(delta.upsertKeys, <int>[10, 20]);
      expect(delta.removalKeys, <int>[30]);
      expect(delta.sourceHealth.depth, ARVisibilityGridSourceState.featureOnly);
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
      const config = ARVisibilityGridNativeConfig(syntheticSource: true);

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
      });
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
        restoredKeys: Int64List.fromList(<int>[10, 20]),
      );

      final map = config.toMap();

      expect(map['version'], visibilityGridWireVersion);
      expect(map['groupFrameConvention'], 'gravity_y_up_meters_v1');
      expect(map['matrixConvention'], 'column_major_gl_v1');
      expect(map['restoredGeometryRevision'], 12);
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
        'depth_safe_band_and_multiview_carving',
        'geometry_revision_and_resync',
      ]),
    );

    final transform = scenarios.firstWhere(
      (scenario) => scenario['name'] == 'group_transform_and_packed_keys',
    );
    final expectedKeys =
        (transform['expectedPackedKeys'] as List<dynamic>).cast<String>();
    expect(expectedKeys, <String>[
      '4611688217451692032',
      '4611692615498203136',
      '4611683819405180918',
    ]);

    final carving = scenarios.firstWhere(
      (scenario) => scenario['name'] == 'depth_safe_band_and_multiview_carving',
    );
    expect(carving['safetyBandMeters'], 0.15);
    expect(carving['singleViewRemovesOccupied'], isFalse);
    expect(carving['separatedDirectionBinsRequired'], 2);
  });
}
