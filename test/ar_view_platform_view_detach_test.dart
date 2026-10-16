import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
import 'package:ar_flutter_plugin_2/managers/ar_capture_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_session_manager.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_view_config.dart';
import 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
import 'package:ar_flutter_plugin_2/models/capture_intent_contract.dart';
import 'package:ar_flutter_plugin_2/widgets/ar_view.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// A platform view removed without `ARSessionManager.dispose` (its widget
/// left the tree or a new configuration replaced it) takes its native
/// channels with it on iOS: the Dart managers it created become disposed
/// without a native call, so a later lifecycle notification fails as on a
/// disposed manager instead of reaching `arcapture_<id>` without a handler.
/// An Android view still drains its capture teardown after removal, so its
/// managers keep the inbound handler and streams and late finalization
/// events still reach the app.
class _FakeContext extends Fake implements BuildContext {}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const permissions = MethodChannel('flutter.baseflow.com/permissions/methods');
  const config = ARCaptureConfig(
    enableHighResCapture: true,
    resolution: CameraResolution(width: 640, height: 480),
    format: CaptureFormat.jpeg,
  );
  final nativeCalls = <String>[];
  // Platform view ids grow across the tests of this file.
  final channelNames = [
    for (var id = 0; id < 8; id++) ...['arsession_$id', 'arcapture_$id'],
  ];

  setUp(() {
    ARCaptureManager.debugIsSupportedOverride = true;
    nativeCalls.clear();
    messenger.setMockMethodCallHandler(
        SystemChannels.platform_views, (call) async => null);
    // Camera permission granted (Permission.camera 1, granted 1).
    messenger.setMockMethodCallHandler(permissions, (call) async {
      if (call.method == 'requestPermissions') return <int, int>{1: 1};
      if (call.method == 'checkPermissionStatus') return 1;
      return null;
    });
    for (final name in channelNames) {
      messenger.setMockMethodCallHandler(MethodChannel(name), (call) async {
        nativeCalls.add('$name.${call.method}');
        return null;
      });
    }
  });

  tearDown(() {
    ARCaptureManager.debugIsSupportedOverride = null;
    messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
    messenger.setMockMethodCallHandler(permissions, null);
    for (final name in channelNames) {
      messenger.setMockMethodCallHandler(MethodChannel(name), null);
    }
  });

  Widget view(List<ARSessionManager> created, {Key? key}) => MaterialApp(
        home: ARView(
          key: key,
          captureConfig: config,
          captureView: const ARCaptureViewConfig(sceneDepth: false),
          onARViewCreated: (session, _, __, ___) => created.add(session),
        ),
      );

  testWidgets(
      'removing the view detaches its session and capture manager '
      'without a native call', (tester) async {
    final created = <ARSessionManager>[];
    await tester.pumpWidget(view(created));
    await tester.pumpAndSettle();
    expect(created, hasLength(1));
    final session = created.single;
    final capture = session.captureManager!;
    expect(capture.isEnabled, isTrue);
    nativeCalls.clear();

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();

    expect(session.sessionState, ARSessionState.disposed);
    expect(session.captureManager, isNull);
    expect(capture.isDisposed, isTrue);
    expect(capture.isEnabled, isFalse);
    await expectLater(
      capture.notifyNativeCaptureLifecycleV2(CaptureLifecycleEvent.routeLeft),
      throwsA(isA<ARCaptureException>()
          .having((e) => e.code, 'code', 'CAPTURE_NOT_INITIALIZED')),
    );
    await session.dispose();
    expect(nativeCalls, isEmpty, reason: 'the native view is gone');
  }, variant: TargetPlatformVariant.only(TargetPlatform.iOS));

  testWidgets(
      'a view replaced by a new configuration detaches the old managers '
      'only', (tester) async {
    final created = <ARSessionManager>[];
    await tester.pumpWidget(view(created, key: const ValueKey(1)));
    await tester.pumpAndSettle();
    await tester.pumpWidget(view(created, key: const ValueKey(2)));
    await tester.pumpAndSettle();
    expect(created, hasLength(2));
    expect(created.first.sessionState, ARSessionState.disposed);
    expect(created.last.sessionState, isNot(ARSessionState.disposed));
    expect(created.last.captureManager!.isEnabled, isTrue);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  }, variant: TargetPlatformVariant.only(TargetPlatform.iOS));

  // A message from the native view, delivered by the following pumps.
  void sendInbound(int id, String method, Object? arguments) {
    final message = const StandardMethodCodec()
        .encodeMethodCall(MethodCall(method, arguments));
    ServicesBinding.instance.channelBuffers
        .push('arcapture_$id', message, (_) {});
  }

  testWidgets(
      'an Android view removed during capture teardown still delivers a late '
      'onCaptureFinalized', (tester) async {
    final created = <ARSessionManager>[];
    await tester.pumpWidget(view(created));
    await tester.pumpAndSettle();
    expect(created, hasLength(1));
    final session = created.single;
    final capture = session.captureManager!;
    final finalized = <Map<String, dynamic>>[];
    final subscription = capture.captureFinalizedStream.listen(finalized.add);
    nativeCalls.clear();

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();

    expect(session.sessionState, isNot(ARSessionState.disposed));
    expect(capture.isDisposed, isFalse);
    sendInbound(session.channelId, 'onCaptureFinalized',
        <String, Object?>{'captureId': 'late', 'status': 'committed'});
    await tester.pump();
    await tester.pump();
    expect(finalized, hasLength(1));
    expect(finalized.single['status'], 'committed');
    expect(nativeCalls, isEmpty);
    // Both wait on futures outside the test's fake async zone.
    await tester.runAsync(subscription.cancel);
    await tester.runAsync(session.dispose);
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));

  testWidgets(
      'an Android view replaced by a new configuration keeps the old '
      'managers open', (tester) async {
    final created = <ARSessionManager>[];
    await tester.pumpWidget(view(created, key: const ValueKey(1)));
    await tester.pumpAndSettle();
    await tester.pumpWidget(view(created, key: const ValueKey(2)));
    await tester.pumpAndSettle();
    expect(created, hasLength(2));
    expect(created.first.sessionState, isNot(ARSessionState.disposed));
    expect(created.first.captureManager!.isDisposed, isFalse);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
    await tester.runAsync(() async {
      for (final session in created) {
        await session.dispose();
      }
    });
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));

  test(
      'a dispose in flight keeps ownership; detaching after dispose is a '
      'no-op', () async {
    final session = ARSessionManager(
        0, _FakeContext(), PlaneDetectionConfig.none,
        captureConfig: config);
    final capture = session.captureManager!;
    final disposal = session.dispose();
    session.platformViewDetached();
    await disposal;
    expect(nativeCalls, ['arcapture_0.dispose', 'arsession_0.dispose']);
    expect(capture.isDisposed, isTrue);
    session.platformViewDetached();
    expect(session.sessionState, ARSessionState.disposed);
  });
}
