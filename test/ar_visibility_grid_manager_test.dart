import 'dart:async';
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/managers/ar_visibility_grid_manager.dart';
import 'package:ar_flutter_plugin_2/models/ar_visibility_grid.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

Future<Object?> _platformCall(
  MethodChannel channel,
  String method,
  Object arguments,
) async {
  final completer = Completer<Object?>();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  await messenger.handlePlatformMessage(
    channel.name,
    channel.codec.encodeMethodCall(MethodCall(method, arguments)),
    (data) => completer.complete(channel.codec.decodeEnvelope(data!)),
  );
  return completer.future;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('arpointcloud_91');
  late List<MethodCall> calls;

  setUp(() {
    calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return switch (call.method) {
        'init' => <String, Object>{
            'version': visibilityGridWireVersion,
            'sessionGeneration': 4,
            'rendererReady': true,
            'featureReady': true,
            'depthCapability': 'automatic',
            'depthConfigured': true,
            'depthActiveMode': 'automatic',
            'renderCapacity': 100,
            'featureTrackCapacity': 200,
            'health': _health(),
            'diagnostics': _initialDiagnostics(100, 200),
          },
        'startGrid' => _delta(revision: 1, reset: true),
        'startGridSummary' => <String, Object>{
            ..._delta(revision: 1, reset: true),
          }
            ..remove('upsertKeys')
            ..remove('removalKeys'),
        'ackGeometry' => <String, Object>{'accepted': true},
        'requestSnapshot' => _delta(revision: 7, reset: true),
        'requestSnapshotSummary' => <String, Object>{
            ..._delta(revision: 7, reset: true),
          }
            ..remove('upsertKeys')
            ..remove('removalKeys'),
        'checkpointBarrier' => _delta(revision: 8, reset: true),
        'releaseCheckpoint' => <String, Object>{'released': true},
        'applyVisibility' => <String, Object>{'applied': true},
        'getHealth' => <String, Object>{
            'version': visibilityGridWireVersion,
            'sourceHealth': _health(),
            'diagnostics': _diagnostics(1),
          },
        'setPointsEnabled' ||
        'setVoxelRenderMode' ||
        'stopGrid' ||
        'dispose' =>
          true,
        _ => null,
      };
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('negotiates v1 and publishes typed cleaned deltas', () async {
    final manager = ARVisibilityGridManager(91);
    final received = <ARVisibilityGridDelta>[];
    final health = <ARVisibilityGridSourceHealth>[];
    final diagnostics = <ARVisibilityGridDiagnostics>[];
    manager.deltas.listen(received.add);
    manager.health.listen(health.add);
    manager.diagnostics.listen(diagnostics.add);
    final initialized = await manager.initialize(
      ARVisibilityGridNativeConfig(
        renderCapacity: 100,
        featureTrackCapacity: 200,
        syntheticSource: true,
      ),
    );
    expect(initialized.sessionGeneration, 4);
    expect(initialized.diagnostics.geometryRevision, 0);
    expect(initialized.diagnostics.rendererFreeRows, 100);

    final started = await manager.startGrid(
      ARVisibilityGridGroupConfig(
        groupId: 'group',
        groupGeneration: 3,
        voxelSizeMeters: 0.1,
        capacity: 100,
        worldFromGroupGl: Float64List.fromList(_identity),
        groupFromWorldGl: Float64List.fromList(_identity),
      ),
    );
    expect(started.reset, isTrue);

    final response = await _platformCall(
      channel,
      'onGridDelta',
      _delta(revision: 2),
    );
    expect(received.single.upsertKeys, Int64List.fromList(<int>[11]));
    expect(response, isNull);
    await _platformCall(channel, 'onGridHealth', <String, Object>{
      'version': visibilityGridWireVersion,
      'sourceHealth': <String, Object>{
        ..._health(),
        'depth': 'transientUnavailable',
      },
      'diagnostics': _diagnostics(2),
    });
    expect(
      health.single.depth,
      ARVisibilityGridSourceState.transientUnavailable,
    );
    expect(diagnostics.single.geometryRevision, 2);
    expect((await manager.getHealth()).diagnostics.geometryRevision, 1);
    expect(await manager.ackGeometry(received.single), isTrue);
    expect((await manager.requestSnapshot(received.single)).reset, isTrue);
    final barrier = await manager.checkpointBarrier(
      groupId: 'group',
      groupGeneration: 3,
      sessionGeneration: 4,
      receiverGeometryRevision: 7,
    );
    expect(barrier.geometryRevision, 8);
    expect(
      await manager.releaseCheckpoint(
        groupId: 'group',
        groupGeneration: 3,
        sessionGeneration: 4,
        geometryRevision: 8,
        visibilityRevision: 2,
      ),
      isTrue,
    );
    await manager.dispose();

    expect(calls.map((call) => call.method), <String>[
      'init',
      'startGrid',
      'getHealth',
      'ackGeometry',
      'requestSnapshot',
      'checkpointBarrier',
      'releaseCheckpoint',
      'dispose',
    ]);
  });

  test('malformed callback is rejected atomically', () async {
    final manager = ARVisibilityGridManager(91);
    final errors = <ARVisibilityGridError>[];
    manager.errors.listen(errors.add);
    await expectLater(
      _platformCall(channel, 'onGridDelta', <String, Object>{
        ..._delta(revision: 2),
        'upsertKeys': Int64List.fromList(<int>[11, 11]),
      }),
      throwsA(isA<PlatformException>()),
    );
    expect(errors.single.code, ARVisibilityGridErrorCode.protocolInvalid);
    await manager.dispose();
  });

  test('ordinary summary callbacks contain no semantic surface keys', () async {
    final manager = ARVisibilityGridManager(91);
    final received = <ARVisibilityGridDeltaSummary>[];
    manager.summaries.listen(received.add);

    final started = await manager.startGridSummary(
      ARVisibilityGridGroupConfig(
        groupId: 'group',
        groupGeneration: 3,
        voxelSizeMeters: 0.1,
        capacity: 100,
        worldFromGroupGl: Float64List.fromList(_identity),
        groupFromWorldGl: Float64List.fromList(_identity),
      ),
    );
    expect(started.geometryRevision, 1);

    final recovered = await manager.requestSnapshotSummary(
      groupId: 'group',
      groupGeneration: 3,
      sessionGeneration: 4,
      receiverGeometryRevision: 1,
    );
    expect(recovered.reset, isTrue);
    expect(recovered.geometryRevision, 7);

    final summary = <String, Object>{
      ..._delta(revision: 2),
    }
      ..remove('upsertKeys')
      ..remove('removalKeys');
    await _platformCall(channel, 'onGridSummary', summary);

    expect(received.single.geometryRevision, 2);
    expect(received.single.capacity, 100);
    await expectLater(
      _platformCall(channel, 'onGridSummary', _delta(revision: 3)),
      throwsA(isA<PlatformException>()),
    );
    await manager.dispose();
  });
}

Map<String, Object> _health() => const <String, Object>{
      'feature': 'healthy',
      'depth': 'healthy',
      'renderer': 'healthy',
      'totalGrid': 'healthy',
    };

Map<String, Object> _initialDiagnostics(int capacity, int featureCapacity) =>
    <String, Object>{
      ..._diagnostics(0),
      'stableTracks': 0,
      'stableVoxels': 0,
      'featureTrackCapacity': featureCapacity,
      'stableVoxelCapacity': capacity,
      'featureObservationCount': 0,
      'acceptedSamples': 0,
      'lastFeatureFusionNs': 0,
      'maxFeatureFusionNs': 0,
      'featureFusionP95Ns': 0,
      'estimatedStateBytes': 0,
      'unacknowledgedGeometryCallbacks': 0,
      'publishedDeltaCount': 0,
      'geometryAcknowledgementCount': 0,
      'rendererRows': 0,
      'rendererFreeRows': capacity,
    };

Map<String, Object> _delta({required int revision, bool reset = false}) =>
    <String, Object>{
      'version': visibilityGridWireVersion,
      'groupId': 'group',
      'groupGeneration': 3,
      'sessionGeneration': 4,
      'baseGeometryRevision': reset ? 0 : revision - 1,
      'geometryRevision': revision,
      'reset': reset,
      'upsertKeys': Int64List.fromList(<int>[11]),
      'removalKeys': Int64List(0),
      'capacity': 100,
      'sourceHealth': _health(),
      'diagnostics': _diagnostics(revision),
    };

Map<String, Object> _diagnostics(int revision) => <String, Object>{
      'candidateTracks': 0,
      'stableTracks': 1,
      'stableVoxels': 1,
      'featureTrackCapacity': 10,
      'stableVoxelCapacity': 100,
      'featureObservationCount': 1,
      'featureMigrations': 0,
      'featureJumpResets': 0,
      'candidateExpirations': 0,
      'supportRemovals': 0,
      'acceptedSamples': 1,
      'rejectedSamples': 0,
      'capacityRejectedCandidates': 0,
      'featureTransientUnavailableCount': 0,
      'featureFailureCount': 0,
      'lastFeatureFusionNs': 1,
      'maxFeatureFusionNs': 1,
      'featureFusionP95Ns': 1,
      'estimatedStateBytes': 512,
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
      'geometryRevision': revision,
      'pendingGeometryKeys': 0,
      'unacknowledgedGeometryCallbacks': 1,
      'publishedDeltaCount': revision,
      'snapshotRecoveryCount': 0,
      'geometryAcknowledgementCount': revision - 1,
      'rendererRows': 1,
      'rendererFreeRows': 99,
    };

const List<double> _identity = <double>[
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
];
