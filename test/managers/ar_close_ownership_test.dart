import 'dart:async';

import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
import 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
import 'package:ar_flutter_plugin_2/managers/ar_capture_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_session_manager.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

class _Context extends Fake implements BuildContext {}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _NativeOwner native;
  setUp(() {
    ARCaptureManager.debugIsSupportedOverride = true;
    native = _NativeOwner();
  });
  tearDown(() async {
    await native.releaseForCleanup();
    ARCaptureManager.debugIsSupportedOverride = null;
  });

  test('capture shares close and refuses admission before acknowledged release',
      () async {
    final capture = native.capture;
    final first = capture.dispose();
    expect(identical(first, capture.dispose()), isTrue);
    await native.captureRequested.future;
    expect(capture.closeState, ARCaptureCloseState.closing);
    expect(capture.isDisposed, isFalse);
    await expectLater(
        capture.captureImageAttempt(),
        throwsA(isA<ARCaptureException>()
            .having((e) => e.code, 'code', 'CAPTURE_CLOSING')));
    expect(native.captureMethods, ['dispose']);
    native.captureReply.complete();
    await first;
    expect(capture.isDisposed, isTrue);
    await capture.dispose();
    expect(native.captureMethods, ['dispose']);
  });

  test('capture native failure retains channel and streams and propagates code',
      () async {
    final capture = native.capture;
    var streamDone = false;
    var capacities = 0;
    final subscription = capture.captureCapacityStream
        .listen((_) => capacities++, onDone: () => streamDone = true);
    final close = capture.dispose();
    final failure = expectLater(
        close,
        throwsA(isA<PlatformException>()
            .having((e) => e.code, 'code', 'CAPTURE_DISPOSE_FAILED')));
    await native.captureRequested.future;
    native.captureReply
        .completeError(PlatformException(code: 'CAPTURE_DISPOSE_FAILED'));
    await failure;
    expect(capture.closeState, ARCaptureCloseState.retained);
    expect(capture.isDisposed, isFalse);
    expect(streamDone, isFalse);
    await native.capacityCallback();
    expect(capacities, 1);
    await capture.acknowledgeNativeCaptureTerminalV2('owned-terminal');
    expect(native.captureMethods.last, 'acknowledgeNativeCaptureTerminalV2');
    final repeated = capture.dispose();
    await expectLater(repeated, throwsA(isA<PlatformException>()));
    expect(capture.closeState, ARCaptureCloseState.retained);
    await subscription.cancel();
  });

  test('initialization completion cannot issue capture across the close fence',
      () async {
    native.initializationReply = Completer<Object?>();
    final pending = native.capture.captureImageAttempt();
    final rejected = expectLater(pending, throwsA(isA<ARCaptureException>()));
    await native.initializationRequested.future;
    final close = native.capture.dispose();
    native.initializationReply!.complete({'mode': 'sharedCamera'});
    await rejected;
    expect(native.captureMethods, isNot(contains('captureHighResImage')));
    native.captureReply.complete();
    await close;
  });

  test(
      'capture refusal stops session close and preserves both owner references',
      () async {
    final capture = native.capture;
    final states = <ARSessionState>[];
    final subscription = native.session.stateStream.listen(states.add);
    final first = native.session.dispose();
    expect(identical(first, native.session.disposeLegacy()), isTrue);
    final failure = expectLater(
        first,
        throwsA(isA<PlatformException>()
            .having((e) => e.code, 'code', 'CAPTURE_DISPOSE_FAILED')));
    await native.captureRequested.future;
    native.captureReply
        .completeError(PlatformException(code: 'CAPTURE_DISPOSE_FAILED'));
    await failure;
    expect(native.session.captureManager, same(capture));
    expect(native.session.sessionState, ARSessionState.retained);
    expect(native.sessionMethods, isEmpty);
    expect(states, [ARSessionState.closing, ARSessionState.retained]);
    await expectLater(
        native.session.onInitialize(), throwsA(isA<ARSessionException>()));
    expect(native.sessionMethods, isEmpty);
    await subscription.cancel();
  });

  test(
      'session refusal after capture acknowledgment retains session channel and reference',
      () async {
    final capture = native.capture;
    final close = native.session.dispose();
    final failure = expectLater(
        close,
        throwsA(isA<PlatformException>()
            .having((e) => e.code, 'code', 'SESSION_DISPOSE_FAILED')));
    await native.captureRequested.future;
    native.captureReply.complete();
    await native.sessionRequested.future;
    expect(capture.isDisposed, isTrue);
    native.sessionReply
        .completeError(PlatformException(code: 'SESSION_DISPOSE_FAILED'));
    await failure;
    expect(native.session.captureManager, same(capture));
    expect(native.session.sessionState, ARSessionState.retained);
    expect(native.session.isInitialized, isFalse);
    // A later attempt still reaches the retained session channel; failure is honest.
    await expectLater(
        native.session.dispose(), throwsA(isA<PlatformException>()));
    expect(native.sessionMethods, ['dispose', 'dispose']);
  });

  test('positive session disposal waits for capture then view acknowledgment',
      () async {
    final close = native.session.disposeLegacy();
    expect(identical(close, native.session.dispose()), isTrue);
    await native.captureRequested.future;
    expect(native.sessionMethods, isEmpty);
    native.captureReply.complete();
    await native.sessionRequested.future;
    expect(native.session.sessionState, ARSessionState.closing);
    expect(native.session.captureManager, isNotNull);
    native.sessionReply.complete();
    await close;
    expect(native.session.sessionState, ARSessionState.disposed);
    expect(native.session.captureManager, isNull);
    await native.session.dispose();
    expect(native.sessionMethods, ['dispose']);
  });

  test('late resume reply cannot overwrite closing state', () async {
    native.resumeReply = Completer<void>();
    final resume = native.session.resume();
    await native.resumeRequested.future;
    final close = native.session.dispose();
    native.resumeReply!.complete();
    await resume;
    expect(native.session.sessionState, ARSessionState.closing);
    native.captureReply.complete();
    await native.sessionRequested.future;
    native.sessionReply.complete();
    await close;
  });
}

class _NativeOwner {
  static const captureChannel = MethodChannel('arcapture_991');
  static const sessionChannel = MethodChannel('arsession_991');
  final captureMethods = <String>[];
  final sessionMethods = <String>[];
  final captureRequested = Completer<void>();
  final sessionRequested = Completer<void>();
  final initializationRequested = Completer<void>();
  final resumeRequested = Completer<void>();
  Completer<void> captureReply = Completer<void>();
  Completer<void> sessionReply = Completer<void>();
  Completer<Object?>? initializationReply;
  Completer<void>? resumeReply;
  late final ARSessionManager session;
  ARCaptureManager get capture => session.captureManager!;

  _NativeOwner() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(captureChannel, (call) async {
      captureMethods.add(call.method);
      if (call.method == 'dispose') {
        if (!captureRequested.isCompleted) captureRequested.complete();
        await captureReply.future;
      }
      if (call.method == 'initializeCapture') {
        initializationRequested.complete();
        return await initializationReply?.future ?? {'mode': 'sharedCamera'};
      }
      return null;
    });
    messenger.setMockMethodCallHandler(sessionChannel, (call) async {
      sessionMethods.add(call.method);
      if (call.method == 'dispose') {
        if (!sessionRequested.isCompleted) sessionRequested.complete();
        await sessionReply.future;
      } else if (call.method == 'resumeSession') {
        resumeRequested.complete();
        await resumeReply?.future;
      }
      return null;
    });
    session = ARSessionManager(991, _Context(), PlaneDetectionConfig.none,
        captureConfig: const ARCaptureConfig(
            enableHighResCapture: true,
            resolution: CameraResolution(width: 640, height: 480),
            format: CaptureFormat.jpeg));
  }

  Future<void> capacityCallback() async {
    final reply = Completer<void>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
      captureChannel.name,
      const StandardMethodCodec().encodeMethodCall(
          const MethodCall('onCaptureCapacityChanged', {'canCapture': false})),
      (_) => reply.complete(),
    );
    await reply.future;
    await Future<void>.delayed(Duration.zero);
  }

  Future<void> releaseForCleanup() async {
    // Only mock native replies are released. Production retained failure is not recovery-certified.
    captureReply = Completer<void>()..complete();
    sessionReply = Completer<void>()..complete();
    await session.dispose();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(captureChannel, null);
    messenger.setMockMethodCallHandler(sessionChannel, null);
  }
}
