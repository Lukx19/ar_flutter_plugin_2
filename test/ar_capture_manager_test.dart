import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
import 'package:ar_flutter_plugin_2/managers/ar_capture_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_session_manager.dart';
import 'package:ar_flutter_plugin_2/models/ar_camera_intrinsics.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeBuildContext extends Fake implements BuildContext {}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const captureChannel = MethodChannel('arcapture_42');
  const captureConfig = ARCaptureConfig(
    enableHighResCapture: true,
    resolution: CameraResolution(width: 640, height: 480),
    format: ImageFormat.jpeg,
    maxCacheSize: 4,
    jpegQuality: 95,
  );

  late List<MethodCall> methodCalls;
  late bool isDisposed;
  late bool captureInProgress;

  setUp(() {
    ARCaptureManager.debugIsSupportedOverride = true;
    methodCalls = <MethodCall>[];
    isDisposed = false;
    captureInProgress = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(captureChannel, (call) async {
      methodCalls.add(call);

      switch (call.method) {
        case 'initializeCapture':
          isDisposed = false;
          return true;
        case 'dispose':
          isDisposed = true;
          return null;
        case 'captureHighResImage':
          if (isDisposed) {
            throw PlatformException(
              code: 'CAPTURE_NOT_INITIALIZED',
              message: 'Capture session is not initialized',
            );
          }
          if (captureInProgress) {
            throw PlatformException(
              code: 'CAPTURE_IN_PROGRESS',
              message: 'A capture is already in progress',
            );
          }
          return <String, dynamic>{
            'imageId': 'img-1',
            'pose': <String, dynamic>{
              'position': <String, dynamic>{'x': 1.0, 'y': 2.0, 'z': 3.0},
              'rotation': <String, dynamic>{
                'x': 0.0,
                'y': 0.0,
                'z': 0.0,
                'w': 1.0,
              },
              'transform': List<double>.generate(
                  16,
                  (index) =>
                      index == 0 || index == 5 || index == 10 || index == 15
                          ? 1.0
                          : 0.0),
              'timestampMs': DateTime(2026).millisecondsSinceEpoch,
              'confidence': 1.0,
              'isTracking': true,
            },
            'resolution': <String, dynamic>{'width': 640, 'height': 480},
            'format': 'jpeg',
            'captureTimestampMs': DateTime(2026).millisecondsSinceEpoch,
            'imageSizeBytes': 6,
            'isHighResolution': false,
            'filePath': null,
          };
        case 'getCameraIntrinsics':
          return <String, dynamic>{
            'focalLength': <String, dynamic>{'fx': 1200.0, 'fy': 1180.0},
            'principalPoint': <String, dynamic>{'cx': 320.0, 'cy': 240.0},
            'resolution': <String, dynamic>{'width': 640, 'height': 480},
            'distortionCoefficients': <double>[0.1, 0.01, 0.0, 0.0, 0.0],
            'fieldOfView': <String, dynamic>{
              'horizontal': 1.0,
              'vertical': 0.8,
            },
          };
        case 'getImageData':
          if ((call.arguments as Map<dynamic, dynamic>)['imageId'] ==
              'missing-image') {
            throw PlatformException(
              code: 'IMAGE_NOT_FOUND',
              message: 'No cached capture exists for imageId=missing-image',
            );
          }
          return Uint8List.fromList(<int>[1, 2, 3, 4, 5, 6]);
        case 'getImageSize':
          if ((call.arguments as Map<dynamic, dynamic>)['imageId'] ==
              'missing-image') {
            throw PlatformException(
              code: 'IMAGE_NOT_FOUND',
              message: 'No cached capture exists for imageId=missing-image',
            );
          }
          return <String, dynamic>{
            'width': 640,
            'height': 480,
            'bytesPerPixel': 1,
            'totalBytes': 6,
          };
        case 'saveImageToFile':
          final filePath =
              (call.arguments as Map<dynamic, dynamic>)['filePath'];
          if (filePath == '/tmp/test.png') {
            throw PlatformException(
              code: 'FORMAT_MISMATCH',
              message: 'JPEG captures must be saved to a .jpg or .jpeg path',
            );
          }
          return true;
        case 'getCurrentSceneFlashState':
          return <String, dynamic>{
            'currentSceneMode': 'portrait',
            'currentFlashMode': 'auto',
            'isTorchEnabled': false,
            'isFlashReady': true,
            'flashCompensation': 0.5,
            'flashStatus': 'ready',
          };
        default:
          return null;
      }
    });
  });

  tearDown(() {
    ARCaptureManager.debugIsSupportedOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(captureChannel, null);
  });

  test('uses the per-view capture channel and initializes natively', () async {
    final sessionManager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
    );
    final captureManager = ARCaptureManager(
      sessionManager,
      captureConfig,
      _FakeBuildContext(),
    );

    final captureResult = await captureManager.captureImage();

    expect(methodCalls.first.method, 'initializeCapture');
    expect(captureResult?.imageId, 'img-1');
    expect(captureResult?.resolution.width, 640);
  });

  test('returns image bytes from the native channel', () async {
    final sessionManager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
    );
    final captureManager = ARCaptureManager(
      sessionManager,
      captureConfig,
      _FakeBuildContext(),
    );

    final imageBytes =
        await captureManager.getImageData('img-1', ImageFormat.jpeg);

    expect(imageBytes, Uint8List.fromList(<int>[1, 2, 3, 4, 5, 6]));
  });

  test('forwards JPEG save requests to the native channel', () async {
    final sessionManager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
    );
    final captureManager = ARCaptureManager(
      sessionManager,
      captureConfig,
      _FakeBuildContext(),
    );

    final saveSucceeded = await captureManager.saveImageToFile(
      'img-1',
      '/tmp/test.jpg',
      ImageFormat.jpeg,
    );

    expect(saveSucceeded, isTrue);
    expect(
      methodCalls.any((call) => call.method == 'saveImageToFile'),
      isTrue,
    );
  });

  test('parses image size data from the native channel', () async {
    final sessionManager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
    );
    final captureManager = ARCaptureManager(
      sessionManager,
      captureConfig,
      _FakeBuildContext(),
    );

    final imageSize = await captureManager.getImageSize('img-1');

    expect(imageSize, isNotNull);
    expect(imageSize?.width, 640);
    expect(imageSize?.height, 480);
    expect(imageSize?.totalBytes, 6);
  });

  test('parses camera intrinsics from the native channel', () async {
    final sessionManager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
    );
    final captureManager = ARCaptureManager(
      sessionManager,
      captureConfig,
      _FakeBuildContext(),
    );

    final intrinsics = await captureManager.getCameraIntrinsics();

    expect(intrinsics, isA<ARCameraIntrinsics>());
    expect(intrinsics?.focalLength.fx, 1200.0);
    expect(intrinsics?.focalLength.fy, 1180.0);
    expect(intrinsics?.principalPoint.cx, 320.0);
    expect(intrinsics?.principalPoint.cy, 240.0);
    expect(intrinsics?.resolution.width, 640);
    expect(intrinsics?.resolution.height, 480);
    expect(intrinsics?.distortionCoefficients, hasLength(5));
  });

  test('surfaces missing-image errors from the native channel', () async {
    final sessionManager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
    );
    final captureManager = ARCaptureManager(
      sessionManager,
      captureConfig,
      _FakeBuildContext(),
    );

    await expectLater(
      captureManager.getImageData('missing-image', ImageFormat.jpeg),
      throwsA(
        isA<ARCaptureException>()
            .having((error) => error.code, 'code', 'IMAGE_NOT_FOUND')
            .having(
              (error) => error.message,
              'message',
              contains('No cached capture exists for imageId=missing-image'),
            ),
      ),
    );
  });

  test('surfaces file-format errors from the native channel', () async {
    final sessionManager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
    );
    final captureManager = ARCaptureManager(
      sessionManager,
      captureConfig,
      _FakeBuildContext(),
    );

    await expectLater(
      captureManager.saveImageToFile(
          'img-1', '/tmp/test.png', ImageFormat.jpeg),
      throwsA(
        isA<ARCaptureException>()
            .having((error) => error.code, 'code', 'FORMAT_MISMATCH')
            .having(
              (error) => error.message,
              'message',
              contains('.jpg or .jpeg'),
            ),
      ),
    );
  });

  test('surfaces native lifecycle errors after dispose', () async {
    final sessionManager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
    );
    final captureManager = ARCaptureManager(
      sessionManager,
      captureConfig,
      _FakeBuildContext(),
    );

    captureManager.dispose();

    await expectLater(
      captureManager.captureImage(),
      throwsA(
        isA<ARCaptureException>()
            .having(
              (error) => error.code,
              'code',
              'CAPTURE_NOT_INITIALIZED',
            )
            .having(
              (error) => error.message,
              'message',
              contains('Capture session is not initialized'),
            ),
      ),
    );
  });

  test('surfaces concurrent capture errors from the native channel', () async {
    final sessionManager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
    );
    final captureManager = ARCaptureManager(
      sessionManager,
      captureConfig,
      _FakeBuildContext(),
    );

    captureInProgress = true;

    await expectLater(
      captureManager.captureImage(),
      throwsA(
        isA<ARCaptureException>()
            .having((error) => error.code, 'code', 'CAPTURE_IN_PROGRESS')
            .having(
              (error) => error.message,
              'message',
              contains('A capture is already in progress'),
            ),
      ),
    );
  });

  test('parses flash state while ignoring deprecated scene fields', () async {
    final sessionManager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
    );
    final captureManager = ARCaptureManager(
      sessionManager,
      captureConfig,
      _FakeBuildContext(),
    );

    final flashState = await captureManager.getCurrentFlashState();

    expect(flashState.currentFlashMode, FlashMode.auto);
    expect(flashState.isFlashReady, isTrue);
    expect(flashState.flashStatus, FlashStatus.ready);
  });

  test('profile parameters omit pruned fields on serialization', () {
    final parameters = CameraProfileParameters.fromMap(<String, dynamic>{
      'isoValue': 200,
      'focusMode': 'continuous',
      'whiteBalanceMode': 'daylight',
      'flashMode': 'on',
      'sceneMode': 'night',
      'flashCompensation': 1.0,
    });

    final serialized = parameters.toMap();

    expect(parameters.flashMode, FlashMode.on);
    expect(serialized.containsKey('sceneMode'), isFalse);
    expect(serialized.containsKey('flashCompensation'), isFalse);
  });

  test('session manager without capture config does not create capture manager',
      () {
    final sessionManager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
    );

    expect(sessionManager.hasCaptureManager, isFalse);
    expect(sessionManager.captureManager, isNull);
    expect(sessionManager.isCaptureEnabled, isFalse);
    expect(sessionManager.isCaptureReady, isFalse);
  });

  test('unsupported platform rejects capture configuration during setup', () {
    ARCaptureManager.debugIsSupportedOverride = false;

    expect(
      () => ARSessionManager(
        42,
        _FakeBuildContext(),
        PlaneDetectionConfig.horizontal,
        captureConfig: captureConfig,
      ),
      throwsA(
        isA<ARSessionException>().having(
          (error) => error.message,
          'message',
          contains('Capture not supported on this platform'),
        ),
      ),
    );
  });

  test('invalid capture configuration is rejected before manager creation', () {
    const invalidModelConfig = ARCaptureConfig(
      enableHighResCapture: true,
      resolution: CameraResolution(width: 640, height: 480),
      format: ImageFormat.jpeg,
      maxCacheSize: 0,
      jpegQuality: 95,
    );
    const invalidConfig = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 50,
      resolution: CameraResolution(width: 640, height: 480),
      format: ImageFormat.jpeg,
      maxCacheSize: 4,
      jpegQuality: 95,
    );

    expect(
      invalidModelConfig.isValid,
      isFalse,
      reason: 'maxCacheSize of 0 should be invalid at the model layer',
    );

    expect(
      () => ARSessionManager(
        42,
        _FakeBuildContext(),
        PlaneDetectionConfig.horizontal,
        captureConfig: invalidConfig,
      ),
      throwsA(
        isA<ARSessionException>().having(
          (error) => error.message,
          'message',
          contains('Capture interval must be at least 100ms'),
        ),
      ),
    );
  });

  test('manual-only capture interval remains valid and initializes session',
      () {
    const manualOnlyConfig = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 0,
      resolution: CameraResolution(width: 640, height: 480),
      format: ImageFormat.jpeg,
      maxCacheSize: 4,
      jpegQuality: 95,
    );

    expect(manualOnlyConfig.isValid, isTrue);

    final sessionManager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
      captureConfig: manualOnlyConfig,
    );

    expect(sessionManager.hasCaptureManager, isTrue);
    expect(sessionManager.isCaptureEnabled, isTrue);
  });

  test('disabled capture config does not initialize native capture', () async {
    const disabledConfig = ARCaptureConfig(
      enableHighResCapture: false,
      resolution: CameraResolution(width: 640, height: 480),
      format: ImageFormat.jpeg,
      maxCacheSize: 4,
      jpegQuality: 95,
    );

    final sessionManager = ARSessionManager(
      42,
      _FakeBuildContext(),
      PlaneDetectionConfig.horizontal,
      captureConfig: disabledConfig,
    );
    final captureManager = ARCaptureManager(
      sessionManager,
      disabledConfig,
      _FakeBuildContext(),
    );

    expect(captureManager.isEnabled, isFalse);
    expect(sessionManager.isCaptureEnabled, isFalse);

    await expectLater(
      captureManager.captureImage(),
      throwsA(
        isA<ARCaptureException>()
            .having((error) => error.code, 'code', 'CAPTURE_DISABLED')
            .having(
              (error) => error.message,
              'message',
              contains('Capture manager not enabled'),
            ),
      ),
    );

    expect(
      methodCalls.where((call) => call.method == 'initializeCapture'),
      isEmpty,
    );
    expect(await captureManager.getImageData('img-1', ImageFormat.jpeg), isNull);
    expect(await captureManager.getCameraIntrinsics(), isNull);
  });
}
