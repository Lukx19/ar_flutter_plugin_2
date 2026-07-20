import 'dart:async';
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/managers/ar_point_cloud_manager.dart';
import 'package:ar_flutter_plugin_2/models/ar_point_cloud.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

Future<Object?> sendPlatformCall(
  MethodChannel channel,
  String method,
  Object? arguments,
) async {
  final completer = Completer<Object?>();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  await messenger.handlePlatformMessage(
    channel.name,
    channel.codec.encodeMethodCall(MethodCall(method, arguments)),
    (data) {
      try {
        completer.complete(channel.codec.decodeEnvelope(data!));
      } catch (error, stackTrace) {
        completer.completeError(error, stackTrace);
      }
    },
  );
  return completer.future;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('arpointcloud_77');
  late List<MethodCall> calls;

  setUp(() {
    calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return switch (call.method) {
        'init' => <String, Object>{
            'version': pointCloudWireVersion,
            'rendererReady': true,
            'acquisitionReady': true,
          },
        'updateVoxels' => <String, Object>{
            'applied': true,
            'lastAppliedEpoch': 1,
          },
        'getRenderingStats' => statsMap(),
        'setPointsEnabled' ||
        'setVoxelRenderMode' ||
        'clear' ||
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

  test('uses pointcloud_wire_v4 methods and typed voxel payloads', () async {
    final manager = ARPointCloudManager(77);
    final initialized = await manager.initialize(
      const ARPointCloudNativeConfig(syntheticSource: true),
    );
    expect(initialized.rendererReady, isTrue);
    expect(initialized.acquisitionReady, isTrue);
    expect(
      await manager.updateVoxels(
        ARVoxelRenderPatch(
          epoch: 1,
          keys: Int64List.fromList(<int>[4]),
          positionsWorld: Float32List.fromList(<double>[1, 2, 3]),
          colors: Int32List.fromList(<int>[-65536]),
        ),
      ),
      isTrue,
    );
    await manager.setPointsEnabled(false);
    await manager.setVoxelRenderMode('cubes');
    expect((await manager.getRenderingStats()).livePointCount, 4);
    await manager.clear();
    await manager.dispose();
    await manager.dispose();
    expect(
      calls.map((call) => call.method),
      <String>[
        'init',
        'updateVoxels',
        'setPointsEnabled',
        'setVoxelRenderMode',
        'getRenderingStats',
        'clear',
        'dispose',
      ],
    );
    final voxelArguments =
        Map<Object?, Object?>.from(calls[1].arguments as Map);
    expect(voxelArguments['keys'], isA<Int64List>());
    expect(voxelArguments['positionsWorld'], isA<Float32List>());
    expect(voxelArguments['colors'], isA<Int32List>());
  });

  test('publishes valid frames synchronously before acknowledging', () async {
    final manager = ARPointCloudManager(77);
    final events = <String>[];
    manager.frames.listen((frame) => events.add('frame:${frame.sequence}'));
    events.add('before');
    final response = await sendPlatformCall(
      channel,
      'onPointCloudFrame',
      <String, Object>{
        'version': pointCloudWireVersion,
        'sequence': 9,
        'timestampNs': 100,
        'count': 1,
        'ids': Int32List.fromList(<int>[42]),
        'points': Float32List.fromList(<double>[1, 2, 3, 0.8]),
      },
    );
    events.add('after');
    expect(events, <String>['before', 'frame:9', 'after']);
    expect(response, <Object?, Object?>{'acceptedSequence': 9});
    await manager.dispose();
  });

  test('rejects malformed typed data and emits PC_PROTOCOL_INVALID', () async {
    final manager = ARPointCloudManager(77);
    final errors = <ARPointCloudError>[];
    manager.errors.listen(errors.add);
    await expectLater(
      sendPlatformCall(
        channel,
        'onPointCloudFrame',
        <String, Object>{
          'version': pointCloudWireVersion,
          'sequence': 1,
          'timestampNs': 1,
          'count': 2,
          'ids': Int32List.fromList(<int>[1]),
          'points': Float32List.fromList(<double>[0, 0, 0, 1]),
        },
      ),
      throwsA(anything),
    );
    expect(errors.single.code, 'PC_PROTOCOL_INVALID');
    await manager.dispose();
  });

  test('publishes renderer stats and independent error flags', () async {
    final manager = ARPointCloudManager(77);
    final stats = <ARPointCloudRenderingStats>[];
    final errors = <ARPointCloudError>[];
    manager.stats.listen(stats.add);
    manager.errors.listen(errors.add);
    await sendPlatformCall(channel, 'onRenderingStats', statsMap());
    await sendPlatformCall(channel, 'onError', <String, Object>{
      'code': 'PC_RENDERER_INIT_FAILED',
      'message': 'renderer unavailable',
      'fatalToAcquisition': false,
      'fatalToRenderer': true,
    });
    expect(stats.single.lastAppliedColorEpoch, 3);
    expect(errors.single.fatalToRenderer, isTrue);
    expect(errors.single.fatalToAcquisition, isFalse);
    await manager.dispose();
  });

  test('publishes renderer readiness transitions', () async {
    final manager = ARPointCloudManager(77);
    final readiness = <ARPointCloudInitializationResult>[];
    manager.readiness.listen(readiness.add);
    await manager.initialize(
      const ARPointCloudNativeConfig(syntheticSource: true),
    );
    await sendPlatformCall(channel, 'onRendererReady', <String, Object>{
      'version': pointCloudWireVersion,
      'rendererReady': false,
      'acquisitionReady': false,
      'rendererMounted': false,
    });
    await sendPlatformCall(channel, 'onRendererReady', <String, Object>{
      'version': pointCloudWireVersion,
      'rendererReady': true,
      'acquisitionReady': true,
      'rendererMounted': true,
    });
    expect(
      readiness.map((value) => value.rendererReady),
      <bool>[true, false, true],
    );
    expect(
      readiness.map((value) => value.acquisitionReady),
      <bool>[true, false, true],
    );
    await manager.dispose();
  });

  test('accepts extended renderer diagnostics and rejects non-finite values',
      () {
    final stats = ARPointCloudRenderingStats.fromMap(<Object?, Object?>{
      'fps': 30.0,
      'fixedStateArrayBytes': 240,
      'keyIndexEntries': 10,
      'rendererDesiredBytes': 160,
      'uploadStagingBytes': 160,
      'gpuVertexBytes': 160,
      'gpuIndexBytes': 40,
      'uploadInFlight': true,
      'pendingDirtyRows': 2,
      'partialUploads': 4,
      'uploadedBytes': 512,
      'rendererMounted': true,
    });
    expect(stats.fixedStateArrayBytes, 240);
    expect(stats.uploadInFlight, isTrue);
    expect(stats.partialUploads, 4);
    expect(stats.rendererMounted, isTrue);
    expect(
      () => ARPointCloudRenderingStats.fromMap(<Object?, Object?>{
        'fps': double.nan,
      }),
      throwsFormatException,
    );
    expect(
      () => ARPointCloudRenderingStats.fromMap(<Object?, Object?>{
        'pendingDirtyRows': -1,
      }),
      throwsFormatException,
    );
  });

  test('dispose closes every stream and rejects later operations', () async {
    final manager = ARPointCloudManager(77);
    final frameDone = expectLater(manager.frames, emitsDone);
    final errorDone = expectLater(manager.errors, emitsDone);
    final statsDone = expectLater(manager.stats, emitsDone);

    await manager.dispose();
    await Future.wait(<Future<void>>[frameDone, errorDone, statsDone]);
    await expectLater(
      manager.initialize(const ARPointCloudNativeConfig()),
      throwsStateError,
    );
    await expectLater(manager.clear(), throwsStateError);
    expect(calls.where((call) => call.method == 'dispose'), hasLength(1));

    await manager.dispose();
    expect(calls.where((call) => call.method == 'dispose'), hasLength(1));
  });
}

Map<String, Object> statsMap() => <String, Object>{
      'fps': 59.0,
      'livePointCount': 4,
      'bufferBytes': 128,
      'emittedFrames': 5,
      'coalescedFrames': 2,
      'lastAppliedColorEpoch': 3,
    };
