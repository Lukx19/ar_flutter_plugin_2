import 'package:ar_flutter_plugin_2/managers/ar_visibility_synthetic_scene.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('finite scene wrapper sends adjacent commands and disposes with DISARM',
      () async {
    const channel = MethodChannel('visibility_scenario_v2_17');
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      final arguments = Map<Object?, Object?>.from(call.arguments! as Map);
      return _receipt(
        scenarioId: call.method == 'arm'
            ? 'small-scene-primary'
            : 'small-scene-primary',
        sequence: arguments['sequence']! as int,
      );
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );

    final scene = ARVisibilitySyntheticScene(17, channel: channel);
    final arm = await scene.arm(
      scenarioId: 'small-scene-primary',
      depthCapability: ARVisibilitySyntheticDepthCapability.automatic,
      expectedBindingGeneration: 4,
      expectedGroupGeneration: 7,
    );
    expect(arm.sequence, 1);
    final wall = await scene.emit(ARVisibilitySyntheticSceneStep.wall);
    expect(wall.sequence, 2);
    final fault = await scene.setFault(
      ARVisibilitySyntheticFault.rendererUnavailable,
    );
    expect(fault.sequence, 3);
    await scene.dispose();

    expect(
      calls.map((call) => call.method),
      <String>['arm', 'emit', 'setFault', 'disarm'],
    );
    expect((calls[0].arguments as Map)['depthCapability'], 'automatic');
    expect((calls[0].arguments as Map)['expectedBindingGeneration'], 4);
    expect((calls[0].arguments as Map)['expectedGroupGeneration'], 7);
    expect((calls[1].arguments as Map)['step'], 'wall');
    expect((calls[1].arguments as Map)['scenarioId'], 'small-scene-primary');
    expect((calls[1].arguments as Map)['sequence'], 2);
    expect((calls[2].arguments as Map)['fault'], 'rendererUnavailable');
    expect((calls[2].arguments as Map)['sequence'], 3);
    expect((calls[3].arguments as Map)['scenarioId'], 'small-scene-primary');
    expect((calls[3].arguments as Map)['sequence'], 4);
    await expectLater(
      scene.snapshot(),
      throwsA(isA<StateError>()),
    );
  });

  test('receipt codec rejects non-scalar and unknown fields', () {
    final valid = _receipt();
    expect(
      ARVisibilitySyntheticReceipt.fromMap(valid),
      isA<ARVisibilitySyntheticReceipt>(),
    );
    expect(
      () => ARVisibilitySyntheticReceipt.fromMap(<String, Object?>{
        ...valid,
        'rendererRows': <int>[1],
      }),
      throwsA(isA<FormatException>()),
    );
    expect(
      () => ARVisibilitySyntheticReceipt.fromMap(<String, Object?>{
        ...valid,
        'unexpected': 1,
      }),
      throwsA(isA<FormatException>()),
    );
    expect(
      () => ARVisibilitySyntheticReceipt.fromMap(<String, Object?>{
        ...valid,
        'rendererRows': <String, Object?>{'nested': 1},
      }),
      throwsA(isA<FormatException>()),
    );
    expect(
      () => ARVisibilitySyntheticReceipt.fromMap(<String, Object?>{
        ...valid,
        'rendererRows': Uint8List.fromList(<int>[1]),
      }),
      throwsA(isA<FormatException>()),
    );
    expect(
      () => ARVisibilitySyntheticReceipt.fromMap(<String, Object?>{
        ...valid,
        'sequence': 0,
      }),
      throwsA(isA<FormatException>()),
    );
    expect(
      () => ARVisibilitySyntheticReceipt.fromMap(<String, Object?>{
        ...valid,
        'resourceBalance': -1,
      }),
      throwsA(isA<FormatException>()),
    );
    expect(valid['rootIsolateSurfaceBytes'], 0);
    expect(valid['rootIsolateImageBytes'], 0);
  });

  test('scenario id is bounded printable ASCII', () async {
    const channel = MethodChannel('visibility_scenario_v2_18');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async => _receipt());
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    final scene = ARVisibilitySyntheticScene(18, channel: channel);
    await expectLater(
      scene.arm(
        scenarioId: 'not printable\n',
        depthCapability: ARVisibilitySyntheticDepthCapability.automatic,
        expectedBindingGeneration: 1,
        expectedGroupGeneration: 1,
      ),
      throwsA(isA<ArgumentError>()),
    );
    await scene.dispose();
  });

  test('failed DISARM keeps the exact sequence available for retry', () async {
    const channel = MethodChannel('visibility_scenario_v2_19');
    var disarmAttempts = 0;
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      final arguments = Map<Object?, Object?>.from(call.arguments! as Map);
      if (call.method == 'disarm' && disarmAttempts++ == 0) {
        throw PlatformException(code: 'reply-lost');
      }
      return _receipt(sequence: arguments['sequence']! as int);
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );

    final scene = ARVisibilitySyntheticScene(19, channel: channel);
    await scene.arm(
      scenarioId: 'small-scene-primary',
      depthCapability: ARVisibilitySyntheticDepthCapability.automatic,
      expectedBindingGeneration: 1,
      expectedGroupGeneration: 1,
    );
    await expectLater(
      scene.dispose(),
      throwsA(isA<PlatformException>()),
    );
    await scene.dispose();

    final disarms = calls.where((call) => call.method == 'disarm').toList();
    expect(disarms, hasLength(2));
    expect((disarms[0].arguments as Map)['sequence'], 2);
    expect((disarms[1].arguments as Map)['sequence'], 2);
    expect(
      (disarms[1].arguments as Map)['scenarioId'],
      'small-scene-primary',
    );
    await expectLater(scene.snapshot(), throwsA(isA<StateError>()));
  });

  test('receipt identity mismatch does not advance the local sequence',
      () async {
    const channel = MethodChannel('visibility_scenario_v2_20');
    final sequences = <int>[];
    var wrongReply = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      final arguments = Map<Object?, Object?>.from(call.arguments! as Map);
      final sequence = arguments['sequence']! as int;
      sequences.add(sequence);
      return _receipt(sequence: wrongReply ? sequence + 1 : sequence);
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );

    final scene = ARVisibilitySyntheticScene(20, channel: channel);
    await expectLater(
      scene.arm(
        scenarioId: 'small-scene-primary',
        depthCapability: ARVisibilitySyntheticDepthCapability.automatic,
        expectedBindingGeneration: 1,
        expectedGroupGeneration: 1,
      ),
      throwsA(isA<FormatException>()),
    );
    wrongReply = false;
    await scene.arm(
      scenarioId: 'small-scene-primary',
      depthCapability: ARVisibilitySyntheticDepthCapability.automatic,
      expectedBindingGeneration: 1,
      expectedGroupGeneration: 1,
    );
    expect(sequences, <int>[1, 1]);
    await scene.dispose();
  });
}

Map<String, Object?> _receipt({
  String scenarioId = 'small-scene-primary',
  int sequence = 1,
}) {
  return <String, Object?>{
    'scenarioId': scenarioId,
    'sequence': sequence,
    'acceptedFeatureObservations': 4,
    'acceptedDepthObservations': 4,
    'geometryRevision': 3,
    'lineageRevision': 2,
    'durableCaptureRevision': 1,
    'coverageRevision': 1,
    'styleRevision': 1,
    'targetSurfaceId': null,
    'rendererRows': 4,
    'automaticEligible': true,
    'guidanceStatus': 'tracking',
    'rootIsolateSurfaceBytes': 0,
    'resourceBalance': 0,
    'callbackCopyP95Micros': 120,
    'rootIsolateImageBytes': 0,
    'rendererOwnedBytes': 0,
  };
}
