import 'dart:async';
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/datatypes/node_types.dart';
import 'package:ar_flutter_plugin_2/managers/ar_anchor_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_object_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_session_manager.dart';
import 'package:ar_flutter_plugin_2/models/ar_anchor.dart';
import 'package:ar_flutter_plugin_2/models/ar_node.dart';
import 'package:ar_flutter_plugin_2/widgets/ar_view.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:vector_math/vector_math_64.dart';

class _FakeBuildContext extends Fake implements BuildContext {}

Future<dynamic> _sendPlatformCall(
  MethodChannel channel,
  String method,
  dynamic arguments,
) async {
  final completer = Completer<dynamic>();
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

  const sessionChannel = MethodChannel('arsession_42');
  const objectChannel = MethodChannel('arobjects_42');
  const anchorChannel = MethodChannel('aranchors_42');
  final identity = Matrix4.identity().storage.toList();

  late List<MethodCall> sessionCalls;
  late List<MethodCall> objectCalls;
  late List<MethodCall> anchorCalls;

  setUp(() {
    sessionCalls = <MethodCall>[];
    objectCalls = <MethodCall>[];
    anchorCalls = <MethodCall>[];

    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(sessionChannel, (call) async {
      sessionCalls.add(call);
      switch (call.method) {
        case 'getCameraPose':
        case 'getAnchorPose':
          return identity;
        case 'getRendererPerformanceSnapshot':
          return <String, Object?>{
            'sampleCount': 2,
            'medianFps': 30.0,
          };
        case 'snapshot':
          return Uint8List.fromList(<int>[137, 80, 78, 71]);
        case 'failOnce':
          throw PlatformException(
            code: 'SCENEVIEW_TEST_ERROR',
            message: 'contract error',
            details: <String, dynamic>{'stage': 'session'},
          );
      }
      return null;
    });
    messenger.setMockMethodCallHandler(objectChannel, (call) async {
      objectCalls.add(call);
      return switch (call.method) {
        'addNode' || 'addNodeToPlaneAnchor' => true,
        'removeNode' => true,
        _ => null,
      };
    });
    messenger.setMockMethodCallHandler(anchorChannel, (call) async {
      anchorCalls.add(call);
      return switch (call.method) {
        'addAnchor' || 'uploadAnchor' || 'downloadAnchor' => true,
        'estimateFeatureMapQualityForHosting' => 'good',
        'removeAnchor' => 'removed',
        _ => null,
      };
    });
  });

  tearDown(() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(sessionChannel, null);
    messenger.setMockMethodCallHandler(objectChannel, null);
    messenger.setMockMethodCallHandler(anchorChannel, null);
  });

  test('session channel preserves methods, payloads, callbacks, and ordering',
      () async {
    final manager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontalAndVertical,
    );
    final events = <String>[];
    manager.onPlaneDetected = (count) => events.add('plane:$count');
    manager.onPlaneOrPointTap =
        (hits) => events.add('tap:${hits.single.distance}');
    manager.onError = (error) => events.add('error:$error');

    await manager.onInitialize(
      showAnimatedGuide: false,
      showFeaturePoints: true,
      showPlanes: false,
      customPlaneTexturePath: 'assets/plane.png',
      showWorldOrigin: true,
      handleTaps: true,
      handlePans: true,
      handleRotation: true,
    );
    manager.showPlanes(true);
    manager.disableCamera();
    manager.enableCamera();
    expect(await manager.getCameraPose(), isNotNull);
    expect(
      await manager.getRendererPerformanceSnapshot(),
      <String, Object?>{'sampleCount': 2, 'medianFps': 30.0},
    );
    final anchor =
        ARPlaneAnchor(transformation: Matrix4.identity(), name: 'a1');
    expect(await manager.getPose(anchor), isNotNull);
    expect(await manager.snapshot(), isA<MemoryImage>());

    await _sendPlatformCall(sessionChannel, 'onPlaneDetected', 3);
    await _sendPlatformCall(sessionChannel, 'onPlaneOrPointTap', <dynamic>[
      <String, dynamic>{
        'type': 1,
        'distance': 1.25,
        'worldTransform': identity,
      },
    ]);
    await _sendPlatformCall(sessionChannel, 'onError', <dynamic>['boom']);

    expect(
      sessionCalls.map((call) => call.method),
      containsAllInOrder(<String>[
        'init',
        'showPlanes',
        'disableCamera',
        'enableCamera',
        'getCameraPose',
        'getRendererPerformanceSnapshot',
        'getAnchorPose',
        'snapshot',
      ]),
    );
    expect(sessionCalls.first.arguments, <String, dynamic>{
      'showAnimatedGuide': false,
      'showFeaturePoints': true,
      'planeDetectionConfig': PlaneDetectionConfig.horizontalAndVertical.index,
      'showPlanes': false,
      'customPlaneTexturePath': 'assets/plane.png',
      'showWorldOrigin': true,
      'handleTaps': true,
      'handlePans': true,
      'handleRotation': true,
    });
    expect(events, <String>['plane:3', 'tap:1.25', 'error:boom']);
  });

  test('enhanced session lifecycle preserves states and native method order',
      () async {
    final manager = ARSessionManager.withConfig(
      buildContext: _FakeBuildContext(),
      arConfig: const ARConfiguration(
        planeDetectionConfig: PlaneDetectionConfig.vertical,
        showAnimatedGuide: false,
        showFeaturePoints: true,
        showPlanes: false,
        showWorldOrigin: true,
        handleTaps: false,
        handlePans: true,
        handleRotation: true,
      ),
      id: 42,
    );
    final states = <ARSessionState>[];
    final subscription = manager.stateStream.listen(states.add);

    await manager.initialize();
    expect(manager.sessionState, ARSessionState.initialized);
    expect(manager.isInitialized, isTrue);
    await manager.resume();
    expect(manager.sessionState, ARSessionState.running);
    await manager.pause();
    expect(manager.sessionState, ARSessionState.paused);
    await manager.dispose();
    await manager.dispose();
    await subscription.cancel();

    expect(
      sessionCalls.map((call) => call.method),
      <String>['init', 'resumeSession', 'pauseSession', 'dispose'],
    );
    expect(
      sessionCalls.first.arguments,
      <String, dynamic>{
        'showAnimatedGuide': false,
        'showFeaturePoints': true,
        'planeDetectionConfig': PlaneDetectionConfig.vertical.index,
        'showPlanes': false,
        'customPlaneTexturePath': null,
        'showWorldOrigin': true,
        'handleTaps': false,
        'handlePans': true,
        'handleRotation': true,
      },
    );
    expect(
      states,
      <ARSessionState>[
        ARSessionState.initializing,
        ARSessionState.initialized,
        ARSessionState.resuming,
        ARSessionState.running,
        ARSessionState.pausing,
        ARSessionState.paused,
        ARSessionState.disposed,
      ],
    );
  });

  test('session distance helpers retain matrix and metric semantics', () async {
    final manager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
    );
    final first = ARPlaneAnchor(
      transformation: Matrix4.identity(),
      name: 'first',
    );
    final second = ARPlaneAnchor(
      transformation: Matrix4.identity(),
      name: 'second',
    );

    expect(
      manager.getDistanceBetweenVectors(
        Vector3.zero(),
        Vector3(2, 3, 6),
      ),
      7,
    );
    expect(await manager.getDistanceBetweenAnchors(first, second), 0);
    expect(await manager.getDistanceFromAnchor(first), 0);
    expect(
      sessionCalls.where((call) => call.method == 'getAnchorPose'),
      hasLength(3),
    );
    expect(
      sessionCalls.where((call) => call.method == 'getCameraPose'),
      hasLength(1),
    );
  });

  test('pose queries treat an unavailable native pose as null', () async {
    final manager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
    );
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(sessionChannel, (call) async {
      sessionCalls.add(call);
      return null;
    });

    expect(await manager.getCameraPose(), isNull);
    expect(
      await manager.getPose(
        ARPlaneAnchor(transformation: Matrix4.identity(), name: 'missing'),
      ),
      isNull,
    );
  });

  test('platform errors complete once with stable code, message, and details',
      () async {
    var completions = 0;
    final future = sessionChannel.invokeMethod<void>('failOnce')
      ..then<void>((_) => completions++, onError: (_) => completions++);

    await expectLater(
      future,
      throwsA(
        isA<PlatformException>()
            .having((error) => error.code, 'code', 'SCENEVIEW_TEST_ERROR')
            .having((error) => error.message, 'message', 'contract error')
            .having(
          (error) => error.details,
          'details',
          <String, dynamic>{'stage': 'session'},
        ),
      ),
    );
    await Future<void>.delayed(Duration.zero);

    expect(completions, 1);
    expect(
        sessionCalls.where((call) => call.method == 'failOnce'), hasLength(1));
  });

  test('object channel preserves node IDs, transforms, and gesture order',
      () async {
    final manager = ARObjectManager(42);
    final events = <String>[];
    manager.onNodeTap = (nodes) => events.add('tap:${nodes.join(',')}');
    manager.onPanStart = (node) => events.add('panStart:$node');
    manager.onPanChange = (node) => events.add('panChange:$node');
    manager.onPanEnd = (node, _) => events.add('panEnd:$node');
    manager.onRotationStart = (node) => events.add('rotationStart:$node');
    manager.onRotationChange = (node) => events.add('rotationChange:$node');
    manager.onRotationEnd = (node, _) => events.add('rotationEnd:$node');
    final node = ARNode(
      type: NodeType.localGLTF2,
      uri: 'assets/model.glb',
      name: 'node-1',
      transformation: Matrix4.identity(),
    );

    manager.onInitialize();
    expect(await manager.addNode(node), isTrue);
    node.position = Vector3(1, 2, 3);
    await Future<void>.delayed(Duration.zero);
    await manager.removeNode(node);

    await _sendPlatformCall(objectChannel, 'onNodeTap', <String>['node-1']);
    await _sendPlatformCall(objectChannel, 'onPanStart', 'node-1');
    await _sendPlatformCall(objectChannel, 'onPanChange', 'node-1');
    await _sendPlatformCall(objectChannel, 'onPanEnd', <String, dynamic>{
      'name': 'node-1',
      'transform': identity,
    });
    await _sendPlatformCall(objectChannel, 'onRotationStart', 'node-1');
    await _sendPlatformCall(objectChannel, 'onRotationChange', 'node-1');
    await _sendPlatformCall(objectChannel, 'onRotationEnd', <String, dynamic>{
      'name': 'node-1',
      'transform': identity,
    });

    expect(
      objectCalls.map((call) => call.method),
      containsAllInOrder(<String>[
        'init',
        'addNode',
        'transformationChanged',
        'removeNode',
      ]),
    );
    expect(
      events,
      <String>[
        'tap:node-1',
        'panStart:node-1',
        'panChange:node-1',
        'panEnd:node-1',
        'rotationStart:node-1',
        'rotationChange:node-1',
        'rotationEnd:node-1',
      ],
    );
  });

  test('object channel preserves plane-anchor attachment payload', () async {
    final manager = ARObjectManager(42);
    final anchor = ARPlaneAnchor(
      transformation: Matrix4.identity(),
      name: 'plane-anchor',
    );
    final node = ARNode(
      type: NodeType.localGLTF2,
      uri: 'assets/anchored.glb',
      name: 'anchored-node',
      transformation: Matrix4.identity(),
    );

    expect(await manager.addNode(node, planeAnchor: anchor), isTrue);

    final call = objectCalls.single;
    expect(call.method, 'addNodeToPlaneAnchor');
    expect(call.arguments, <String, dynamic>{
      'node': node.toMap(),
      'anchor': anchor.toJson(),
    });
  });

  test('anchor channel preserves local and cloud-anchor completion contracts',
      () async {
    final manager = ARAnchorManager(42);
    final anchor = ARPlaneAnchor(
      transformation: Matrix4.identity(),
      name: 'anchor-1',
      ttl: 7,
    );
    final events = <String>[];
    manager.onAnchorUploaded =
        (uploaded) => events.add('uploaded:${uploaded.name}');
    manager.onAnchorDownloaded = (serialized) {
      events.add('downloaded:${serialized['name']}');
      return ARAnchor.fromJson(serialized);
    };

    await manager.initGoogleCloudAnchorMode();
    expect(
      await manager.estimateFeatureMapQualityForHosting(),
      AndroidFeatureMapQuality.good,
    );
    expect(await manager.addAnchor(anchor), isTrue);
    expect(await manager.uploadAnchor(anchor), isTrue);
    manager.removeAnchor(anchor);
    expect(await manager.downloadAnchor('cloud-1'), isTrue);

    await _sendPlatformCall(
      anchorChannel,
      'onCloudAnchorUploaded',
      <String, dynamic>{'name': 'anchor-1', 'cloudanchorid': 'cloud-1'},
    );
    final downloadResult = await _sendPlatformCall(
      anchorChannel,
      'onAnchorDownloadSuccess',
      <String, dynamic>{
        ...anchor.toJson(),
        'name': 'downloaded-anchor',
        'cloudanchorid': 'cloud-1',
      },
    );

    expect(downloadResult, 'downloaded-anchor');
    expect(anchor.cloudanchorid, 'cloud-1');
    expect(events, <String>[
      'uploaded:anchor-1',
      'downloaded:downloaded-anchor',
    ]);
    expect(
      anchorCalls.map((call) => call.method),
      containsAllInOrder(<String>[
        'initGoogleCloudAnchorMode',
        'estimateFeatureMapQualityForHosting',
        'addAnchor',
        'uploadAnchor',
        'removeAnchor',
        'downloadAnchor',
      ]),
    );
    final qualityCall = anchorCalls.firstWhere(
      (call) => call.method == 'estimateFeatureMapQualityForHosting',
    );
    expect(qualityCall.arguments, isNull);
  });

  test('native-camera anchor creation preserves name and TTL', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(anchorChannel, (call) async {
      anchorCalls.add(call);
      if (call.method == 'addAnchorAtCurrentCameraPose') {
        return <String, dynamic>{
          'type': 0,
          'name': call.arguments['name'],
          'transformation': Matrix4.identity().storage.toList(),
          'childNodes': <String>[],
          'cloudanchorid': null,
          'ttl': call.arguments['ttl'],
        };
      }
      return true;
    });
    final manager = ARAnchorManager(42);

    final anchor = await manager.addAnchorAtCurrentCameraPose(
      name: 'native-camera-anchor',
      ttl: 7,
    );

    expect(anchor.name, 'native-camera-anchor');
    expect(anchor.ttl, 7);
    expect(anchorCalls.single.arguments, <String, dynamic>{
      'name': 'native-camera-anchor',
      'ttl': 7,
    });
  });

  test('anchor is pending before native upload completion callback', () async {
    final uploadStarted = Completer<void>();
    final completeUpload = Completer<bool>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(anchorChannel, (call) async {
      anchorCalls.add(call);
      if (call.method == 'uploadAnchor') {
        uploadStarted.complete();
        return completeUpload.future;
      }
      return true;
    });
    final manager = ARAnchorManager(42);
    final anchor = ARPlaneAnchor(
      transformation: Matrix4.identity(),
      name: 'race-free-anchor',
    );

    final upload = manager.uploadAnchor(anchor);
    await uploadStarted.future;

    expect(manager.pendingAnchors, contains(anchor));
    completeUpload.complete(true);
    expect(await upload, isTrue);
  });

  test('cloud anchor authorization errors remain actionable', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(anchorChannel, (call) async {
      if (call.method == 'uploadAnchor') {
        throw PlatformException(
          code: 'ANCHOR_NOT_AUTHORIZED',
          message: 'Package and certificate are not registered.',
        );
      }
      return true;
    });
    final manager = ARAnchorManager(42);
    final anchor = ARPlaneAnchor(
      transformation: Matrix4.identity(),
      name: 'unauthorized-anchor',
    );

    await expectLater(
      manager.uploadAnchor(anchor),
      throwsA(
        isA<PlatformException>().having(
          (error) => error.code,
          'code',
          'ANCHOR_NOT_AUTHORIZED',
        ),
      ),
    );
    expect(manager.pendingAnchors, isEmpty);
  });

  test('cloud anchor TTL is validated before a platform call', () async {
    final manager = ARAnchorManager(42);
    final anchor = ARPlaneAnchor(
      transformation: Matrix4.identity(),
      name: 'invalid-ttl-anchor',
      ttl: 366,
    );

    await expectLater(manager.uploadAnchor(anchor), throwsArgumentError);
    expect(
      anchorCalls.where((call) => call.method == 'uploadAnchor'),
      isEmpty,
    );
  });

  testWidgets(
      'Android ARView creation wires all managers without target-specific Dart',
      (tester) async {
    final platformView = AndroidARView();
    Widget? builtView;
    ARSessionManager? sessionManager;
    ARObjectManager? objectManager;
    ARAnchorManager? anchorManager;

    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) {
            builtView = platformView.build(
              context: context,
              planeDetectionConfig: PlaneDetectionConfig.horizontal,
              arViewCreatedCallback: (session, objects, anchors, location) {
                sessionManager = session;
                objectManager = objects;
                anchorManager = anchors;
              },
            );
            return const SizedBox.shrink();
          },
        ),
      ),
    );

    expect(builtView, isA<PlatformViewLink>());
    platformView.onPlatformViewCreated(42);

    expect(sessionManager?.channelId, 42);
    expect(objectManager, isNotNull);
    expect(anchorManager, isNotNull);
    await sessionManager!.onInitialize();
    objectManager!.onInitialize();
    await anchorManager!.initGoogleCloudAnchorMode();
    expect(
      sessionCalls.map((call) => call.method),
      contains('init'),
    );
    expect(
      objectCalls.map((call) => call.method),
      contains('init'),
    );
    expect(
      anchorCalls.map((call) => call.method),
      contains('initGoogleCloudAnchorMode'),
    );
  });
}
