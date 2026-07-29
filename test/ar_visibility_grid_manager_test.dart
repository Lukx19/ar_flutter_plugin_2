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
          },
        'startGrid' => _delta(revision: 1, reset: true),
        'ackGeometry' => <String, Object>{'accepted': true},
        'requestSnapshot' => _delta(revision: 7, reset: true),
        'checkpointBarrier' => _delta(revision: 8, reset: true),
        'releaseCheckpoint' => <String, Object>{'released': true},
        'applyVisibility' => <String, Object>{'applied': true},
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
    manager.deltas.listen(received.add);
    manager.health.listen(health.add);
    final initialized = await manager.initialize(
      ARVisibilityGridNativeConfig(
        renderCapacity: 100,
        featureTrackCapacity: 200,
        syntheticSource: true,
      ),
    );
    expect(initialized.sessionGeneration, 4);

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
    });
    expect(
      health.single.depth,
      ARVisibilityGridSourceState.transientUnavailable,
    );
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
}

Map<String, Object> _health() => const <String, Object>{
      'feature': 'healthy',
      'depth': 'healthy',
      'renderer': 'healthy',
      'totalGrid': 'healthy',
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
