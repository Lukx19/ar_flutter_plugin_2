import 'package:ar_flutter_plugin_2/capabilities/arkit_capabilities.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_view_config.dart';
import 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
import 'package:ar_flutter_plugin_2/models/ar_session_inputs.dart';
import 'package:ar_flutter_plugin_2/models/ar_playback_config.dart';
import 'package:ar_flutter_plugin_2/widgets/ar_view.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    messenger.setMockMethodCallHandler(SystemChannels.platform_views,
        (call) async {
      calls.add(call);
      return null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
  });

  Widget host(ARCaptureViewConfig? captureView, {ARPlaybackConfig? playback}) =>
      MaterialApp(
        home: Builder(
          builder: (context) => IosARView().build(
            context: context,
            captureView: captureView,
            playback: playback,
          ),
        ),
      );

  Map<Object?, Object?> created() {
    final create = calls.lastWhere((call) => call.method == 'create');
    return create.arguments as Map<Object?, Object?>;
  }

  Object? params(Map<Object?, Object?> arguments) =>
      const StandardMessageCodec().decodeMessage(
          ByteData.sublistView(arguments['params']! as Uint8List));

  testWidgets(
      'the capture route creates the capture view with its depth and '
      'video format parameters', (tester) async {
    await tester.pumpWidget(host(const ARCaptureViewConfig(
      sceneDepth: true,
      highResolutionFrameCapture: true,
    )));
    await tester.pumpAndSettle();
    final arguments = created();
    expect(arguments['viewType'], 'capture3d/capture_ar_view');
    expect(params(arguments), {
      'sceneDepth': true,
      'depthWidth': 128,
      'depthHeight': 96,
      'highResolutionFrameCapture': true,
    });
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  }, variant: TargetPlatformVariant.only(TargetPlatform.iOS));

  testWidgets('other routes keep the legacy iOS view', (tester) async {
    await tester.pumpWidget(host(null));
    await tester.pumpAndSettle();
    final arguments = created();
    expect(arguments['viewType'], 'ar_flutter_plugin_2');
    expect(params(arguments), isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  }, variant: TargetPlatformVariant.only(TargetPlatform.iOS));

  testWidgets('a changed capture configuration recreates the view',
      (tester) async {
    await tester.pumpWidget(host(const ARCaptureViewConfig(sceneDepth: true)));
    await tester.pumpAndSettle();
    await tester.pumpWidget(host(const ARCaptureViewConfig(sceneDepth: false)));
    await tester.pumpAndSettle();
    expect(calls.where((call) => call.method == 'create'), hasLength(2));
    expect(params(created()), containsPair('sceneDepth', false));
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  }, variant: TargetPlatformVariant.only(TargetPlatform.iOS));

  testWidgets(
      'a playback configuration reaches the capture view and a new '
      'generation recreates it', (tester) async {
    const playback = ARPlaybackConfig(
      datasetPath: 'room2-iphone12pro-20261014',
      restart: ARPlaybackRestart.session,
    );
    await tester.pumpWidget(
        host(const ARCaptureViewConfig(sceneDepth: true), playback: playback));
    await tester.pumpAndSettle();
    expect(
        params(created()),
        allOf(
          containsPair('arcorePlaybackDataset', 'room2-iphone12pro-20261014'),
          containsPair('arcorePlaybackRestart', 'session'),
          containsPair('arcorePlaybackRestartGeneration', 0),
          containsPair('sceneDepth', true),
        ));
    await tester.pumpWidget(host(const ARCaptureViewConfig(sceneDepth: true),
        playback: playback.withRestartGeneration(1)));
    await tester.pumpAndSettle();
    expect(calls.where((call) => call.method == 'create'), hasLength(2));
    expect(
        params(created()), containsPair('arcorePlaybackRestartGeneration', 1));
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  }, variant: TargetPlatformVariant.only(TargetPlatform.iOS));

  testWidgets('the legacy iOS view ignores a playback configuration',
      (tester) async {
    await tester.pumpWidget(
        host(null, playback: const ARPlaybackConfig(datasetPath: 'room2')));
    await tester.pumpAndSettle();
    expect(params(created()), isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  }, variant: TargetPlatformVariant.only(TargetPlatform.iOS));

  testWidgets(
      'exact still profile reaches native creation and changes view identity',
      (tester) async {
    const size = CameraResolution(width: 2016, height: 1512);
    const automatic =
        ARCaptureViewConfig(sceneDepth: true, highResolutionFrameCapture: true);
    final exact = automatic
        .withStillResolution(size)
        .withVideoHdr(true)
        .withSessionInputs(const ARSessionInputs());
    await tester.pumpWidget(host(exact));
    await tester.pumpAndSettle();
    expect(
        params(created()),
        allOf(containsPair('stillWidth', 2016),
            containsPair('stillHeight', 1512)));
    await tester.pumpWidget(host(exact.withStillResolution(null)));
    await tester.pumpAndSettle();
    expect(calls.where((call) => call.method == 'create'), hasLength(2));
    expect(params(created()), isNot(contains('stillWidth')));
    expect(exact, isNot(automatic));
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  }, variant: TargetPlatformVariant.only(TargetPlatform.iOS));

  group('ARKit capability query', () {
    const channel = MethodChannel('ar_flutter_plugin_2/camera_capabilities');
    tearDown(() => messenger.setMockMethodCallHandler(channel, null));

    test('parses the host reply', () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'getARKitCapabilities');
        return <String, Object>{
          'sceneDepth': true,
          'smoothedSceneDepth': true,
          'highResolutionFrameCapture': true,
          'worldMap': true,
          'modelIdentifier': 'iPhone13,3',
        };
      });
      expect(
        await const ARKitCapabilityQuery().query(),
        const ARKitCapabilities(
          sceneDepth: true,
          smoothedSceneDepth: true,
          highResolutionFrameCapture: true,
          worldMap: true,
          modelIdentifier: 'iPhone13,3',
        ),
      );
    });

    test('a missing, failing or unreadable reply is null', () async {
      expect(await const ARKitCapabilityQuery().query(), isNull,
          reason: 'no handler');
      messenger.setMockMethodCallHandler(
          channel, (call) async => throw PlatformException(code: 'FAILED'));
      expect(await const ARKitCapabilityQuery().query(), isNull);
      messenger.setMockMethodCallHandler(
          channel, (call) async => <String, Object>{'sceneDepth': true});
      expect(await const ARKitCapabilityQuery().query(), isNull,
          reason: 'high-resolution answer missing');
      expect(ARKitCapabilities.fromReply('yes'), isNull);
    });
  });
}
