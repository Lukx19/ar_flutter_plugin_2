import 'dart:convert';
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
import 'package:ar_flutter_plugin_2/managers/ar_capture_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_session_manager.dart';
import 'package:ar_flutter_plugin_2/models/ar_camera_intrinsics.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
import 'package:ar_flutter_plugin_2/models/capture_capacity.dart';
import 'package:ar_flutter_plugin_2/models/capture_quality_policy.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

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
  late bool rejectBlur;
  late Map<String, dynamic>? currentFocusRegion;
  late String currentFlashModeName;
  late String currentWhiteBalanceModeName;
  late int? currentColorTemperatureK;
  late bool isWhiteBalanceLocked;
  late String? failingMethodName;
  late String failingMethodCode;
  late String failingMethodMessage;
  String? initializeErrorCode;
  String? initializeErrorMessage;
  dynamic initializeCaptureResponse;

  setUp(() {
    ARCaptureManager.debugIsSupportedOverride = true;
    methodCalls = <MethodCall>[];
    isDisposed = false;
    captureInProgress = false;
    rejectBlur = false;
    currentFocusRegion = null;
    currentFlashModeName = 'auto';
    currentWhiteBalanceModeName = 'daylight';
    currentColorTemperatureK = null;
    isWhiteBalanceLocked = false;
    failingMethodName = null;
    failingMethodCode = 'CONTROL_UNSUPPORTED';
    failingMethodMessage = 'Control is unsupported on this backend';
    initializeErrorCode = null;
    initializeErrorMessage = null;
    initializeCaptureResponse = <String, dynamic>{'mode': 'sharedCamera'};
    SharedPreferences.setMockInitialValues(<String, Object>{});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(captureChannel, (call) async {
      methodCalls.add(call);

      if (call.method == failingMethodName) {
        throw PlatformException(
          code: failingMethodCode,
          message: failingMethodMessage,
        );
      }

      switch (call.method) {
        case 'initializeCapture':
          if (initializeErrorCode != null) {
            throw PlatformException(
              code: initializeErrorCode!,
              message: initializeErrorMessage,
            );
          }
          isDisposed = false;
          return initializeCaptureResponse;
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
          if (rejectBlur) {
            return <String, dynamic>{
              'status': 'rejectedBlur',
              'attemptId': 'attempt-blur',
              'imageId': null,
              'capture': null,
              'quality': <String, dynamic>{
                'blurScore': 12.0,
                'blurThreshold': 110.0,
                'blurPassed': false,
                'analyzedWidth': 64,
                'analyzedHeight': 48,
                'algorithm': 'previewLaplacianVarianceV1',
              },
            };
          }
          return <String, dynamic>{
            'status': 'staged',
            'attemptId': 'attempt-1',
            'imageId': 'img-1',
            'capture': <String, dynamic>{
              'imageId': 'img-1',
              'pose': <String, dynamic>{
                'position': <String, dynamic>{'x': 1.0, 'y': 2.0, 'z': 3.0},
                'rotation': <String, dynamic>{
                  'x': 1.0,
                  'y': 0.0,
                  'z': 0.0,
                  'w': 0.0,
                },
                'transform': List<double>.generate(
                  16,
                  (index) => switch (index) {
                    0 || 15 => 1.0,
                    5 || 10 => -1.0,
                    _ => 0.0,
                  },
                ),
                'convention': 'opencv_c2w_v1',
                'timestampMs': DateTime(2026).millisecondsSinceEpoch,
                'confidence': 1.0,
                'isTracking': true,
              },
              'resolution': <String, dynamic>{'width': 640, 'height': 480},
              'format': 'jpeg',
              'captureTimestampMs': DateTime(2026).millisecondsSinceEpoch,
              'imageSizeBytes': 6,
              'isHighResolution': false,
              'intrinsics': <String, dynamic>{
                'focalLength': <String, dynamic>{'fx': 1300.0, 'fy': 1290.0},
                'principalPoint': <String, dynamic>{'cx': 321.0, 'cy': 241.0},
                'resolution': <String, dynamic>{'width': 640, 'height': 480},
                'cropRegion': <String, dynamic>{
                  'left': 0,
                  'top': 0,
                  'width': 640,
                  'height': 480,
                },
                'distortionCoefficients': <double>[0.2, 0.02, 0.0, 0.0, 0.0],
                'fieldOfView': <String, dynamic>{
                  'horizontal': 0.95,
                  'vertical': 0.78,
                },
              },
              'filePath': null,
            },
          };
        case 'getCameraIntrinsics':
          return <String, dynamic>{
            'focalLength': <String, dynamic>{'fx': 1200.0, 'fy': 1180.0},
            'principalPoint': <String, dynamic>{'cx': 320.0, 'cy': 240.0},
            'resolution': <String, dynamic>{'width': 640, 'height': 480},
            'cropRegion': <String, dynamic>{
              'left': 0,
              'top': 0,
              'width': 640,
              'height': 480,
            },
            'distortionCoefficients': <double>[0.1, 0.01, 0.0, 0.0, 0.0],
            'fieldOfView': <String, dynamic>{
              'horizontal': 1.0,
              'vertical': 0.8,
            },
          };
        case 'getCaptureCapacity':
          return <String, dynamic>{
            'maxEntries': 4,
            'usedEntries': 1,
            'reservedEntries': 0,
            'readyEntries': 1,
            'persistingEntries': 0,
            'pendingRetryEntries': 0,
            'stagedBytes': 6,
            'maxStagedBytes': 1228800,
            'canCapture': true,
          };
        case 'getPerformanceSnapshot':
          return <String, dynamic>{
            'processPssBytes': 400000000,
            'openFileDescriptors': 120,
            'threadCount': 40,
            'imagesAcquired': 20,
            'imagesClosed': 20,
            'batteryPercent': 85,
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
        case 'persistCapture':
          return <String, dynamic>{
            'files': <String, dynamic>{
              'jpeg': '/tmp/session/images/img-1.jpg.part'
            },
            'sizes': <String, dynamic>{'jpeg': 6},
            'hashes': <String, dynamic>{'jpeg': 'abc123'},
          };
        case 'discardCapture':
          return true;
        case 'setISO':
          return <String, dynamic>{
            'actualISO': (call.arguments as Map<dynamic, dynamic>)['isoValue'],
          };
        case 'setExposureTime':
          return <String, dynamic>{
            'actualExposureTimeMicroseconds': (call.arguments
                as Map<dynamic, dynamic>)['exposureTimeMicroseconds'],
          };
        case 'getCurrentISO':
          return 320;
        case 'getCurrentExposureTime':
          return 12500;
        case 'getSupportedISORange':
          return <int>[100, 6400];
        case 'getSupportedExposureRange':
          return <String, dynamic>{'min': 100, 'max': 33333};
        case 'setAutoExposureEnabled':
          if (failingMethodName == 'setAutoExposureEnabled') {
            return false;
          }
          return (call.arguments as Map<dynamic, dynamic>)['enabled'] as bool;
        case 'getExposureCompensationInfo':
          return <String, dynamic>{
            'minCompensation': -2.0,
            'maxCompensation': 2.0,
            'currentCompensation': 0.5,
            'stepSize': 0.5,
          };
        case 'setExposureCompensation':
          if (failingMethodName == 'setExposureCompensation') {
            return null;
          }
          return <String, dynamic>{'actualCompensation': 0.5};
        case 'lockExposure':
          return true;
        case 'unlockExposure':
          return true;
        case 'getCurrentExposureState':
          return <String, dynamic>{
            'currentISO': 320,
            'currentExposureTime': 12500,
            'isAutoExposureEnabled': false,
            'isExposureLocked': true,
            'exposureCompensation': 0.5,
            'exposureMode': 'manual',
          };
        case 'setFocusDistance':
          if (failingMethodName == 'setFocusDistance') {
            return null;
          }
          currentFocusRegion = null;
          return <String, dynamic>{
            'actualDistance':
                (call.arguments as Map<dynamic, dynamic>)['distance'] as double,
          };
        case 'setAutofocusEnabled':
          if (failingMethodName == 'setAutofocusEnabled') {
            return false;
          }
          if (!((call.arguments as Map<dynamic, dynamic>)['enabled'] as bool)) {
            currentFocusRegion = null;
          }
          return (call.arguments as Map<dynamic, dynamic>)['enabled'] as bool;
        case 'focusAtPoint':
          currentFocusRegion = <String, dynamic>{
            'left': 0.425,
            'top': 0.425,
            'width': 0.15,
            'height': 0.15,
          };
          return true;
        case 'getCurrentFocusState':
          return <String, dynamic>{
            'currentFocusDistance': 0.4,
            'isAutofocusEnabled': currentFocusRegion != null,
            'currentFocusMode': currentFocusRegion != null ? 'auto' : 'fixed',
            'isFocusLocked': false,
            'isFocusPeakingEnabled': false,
            'focusStatus': currentFocusRegion != null ? 'scanning' : 'inactive',
            'focusRegion': currentFocusRegion,
          };
        case 'getSupportedFocusModes':
          return <String>['auto', 'continuous', 'fixed', 'infinity'];
        case 'setFocusMode':
          if (failingMethodName == 'setFocusMode') {
            return false;
          }
          currentFocusRegion = null;
          return true;
        case 'setWhiteBalanceMode':
          if (failingMethodName == 'setWhiteBalanceMode') {
            return false;
          }
          currentWhiteBalanceModeName =
              (call.arguments as Map<dynamic, dynamic>)['mode'] as String;
          currentColorTemperatureK = null;
          return true;
        case 'setColorTemperature':
          if (failingMethodName == 'setColorTemperature') {
            return null;
          }
          currentWhiteBalanceModeName = 'manual';
          currentColorTemperatureK = (call.arguments
              as Map<dynamic, dynamic>)['colorTemperatureK'] as int;
          isWhiteBalanceLocked = false;
          return <String, dynamic>{
            'actualColorTemperature': currentColorTemperatureK,
          };
        case 'getCurrentWhiteBalanceState':
          return <String, dynamic>{
            'currentMode': currentWhiteBalanceModeName,
            'currentColorTemperature': currentColorTemperatureK,
            'isWhiteBalanceLocked': isWhiteBalanceLocked,
            'isAutoWhiteBalanceEnabled': currentColorTemperatureK == null,
            'status': 'inactive',
          };
        case 'getSupportedColorTemperatureRange':
          return <String, dynamic>{'min': 2000, 'max': 8000};
        case 'lockWhiteBalance':
          isWhiteBalanceLocked = true;
          return true;
        case 'unlockWhiteBalance':
          isWhiteBalanceLocked = false;
          return true;
        case 'setWhiteBalanceFromPoint':
          currentWhiteBalanceModeName = 'auto';
          currentColorTemperatureK = null;
          isWhiteBalanceLocked = false;
          return true;
        case 'getSupportedWhiteBalanceModes':
          return <String>['auto', 'daylight', 'shade'];
        case 'setFlashMode':
          if (failingMethodName == 'setFlashMode') {
            return false;
          }
          currentFlashModeName =
              (call.arguments as Map<dynamic, dynamic>)['mode'] as String;
          return true;
        case 'isFlashAvailable':
          return true;
        case 'setTorchEnabled':
          currentFlashModeName =
              (call.arguments as Map<dynamic, dynamic>)['enabled'] as bool
                  ? 'torch'
                  : 'off';
          return true;
        case 'getCurrentFlashState':
          return <String, dynamic>{
            'currentFlashMode': currentFlashModeName,
            'isTorchEnabled': currentFlashModeName == 'torch',
            'isFlashReady': true,
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
    expect(captureResult?.intrinsics?.focalLength.fx, 1300.0);
    expect(captureResult?.intrinsics?.principalPoint.cx, 321.0);
    expect(
      captureManager.initializationResult?.mode,
      CaptureInitializationMode.sharedCamera,
    );
  });

  test('records preview-fallback initialization warnings from native setup',
      () async {
    initializeCaptureResponse = <String, dynamic>{
      'mode': 'previewFallback',
      'warning':
          'Capture is using preview-resolution fallback; shared camera is unavailable on this device.',
    };

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

    await captureManager.getCaptureCapacity();

    expect(
      captureManager.initializationResult?.mode,
      CaptureInitializationMode.previewFallback,
    );
    expect(
      captureManager.initializationResult?.warning,
      contains('preview-resolution fallback'),
    );
  });

  test(
    'surfaces shared-camera restart requirements from native initialization',
    () async {
      initializeErrorCode = 'SHARED_CAMERA_REQUIRES_RESTART';
      initializeErrorMessage =
          'Shared camera must be requested before the AR session is created';

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
        captureManager.captureImageAttempt(),
        throwsA(
          isA<ARCaptureException>()
              .having(
                (error) => error.code,
                'code',
                'SHARED_CAMERA_REQUIRES_RESTART',
              )
              .having(
                (error) => error.message,
                'message',
                contains('before the AR session is created'),
              ),
        ),
      );
    },
  );

  test('serializes quality policy for structured capture attempts', () async {
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

    final attempt = await captureManager.captureImageAttempt(
      qualityPolicy: const CaptureQualityPolicy(
        blurFilterEnabled: true,
        blurThreshold: 90.0,
        keepRejectedCaptures: false,
      ),
    );

    final captureCall = methodCalls.firstWhere(
      (call) => call.method == 'captureHighResImage',
    );
    final captureArgs = captureCall.arguments as Map<dynamic, dynamic>;
    final qualityPolicy = captureArgs['qualityPolicy'] as Map<dynamic, dynamic>;

    expect(attempt.isStaged, isTrue);
    expect(attempt.capture?.imageId, 'img-1');
    expect(qualityPolicy['blurFilterEnabled'], isTrue);
    expect(qualityPolicy['blurThreshold'], 90.0);
    expect(qualityPolicy['keepRejectedCaptures'], isFalse);
  });

  test('parses rejected blur attempt results', () async {
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
    rejectBlur = true;

    final attempt = await captureManager.captureImageAttempt();
    final captureResult = await captureManager.captureImage();

    expect(attempt.isRejectedBlur, isTrue);
    expect(attempt.imageId, isNull);
    expect(attempt.capture, isNull);
    expect(attempt.quality, isNotNull);
    expect(attempt.quality!.blurPassed, isFalse);
    expect(attempt.quality!.algorithm, 'previewLaplacianVarianceV1');
    expect(captureResult, isNull);
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

    final imageBytes = await captureManager.getImageData(
      'img-1',
      ImageFormat.jpeg,
    );

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
    expect(methodCalls.any((call) => call.method == 'saveImageToFile'), isTrue);
  });

  test('forwards staged persist requests to the native channel', () async {
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

    final persistResult = await captureManager.persistCapture(
      'img-1',
      '/tmp/root',
      'session/images',
      'img-1',
      ImageFormat.jpeg,
    );

    final persistCall = methodCalls.firstWhere(
      (call) => call.method == 'persistCapture',
    );

    expect(persistResult, isNotNull);
    expect(persistResult?.fileFor(ImageFormat.jpeg),
        '/tmp/session/images/img-1.jpg.part');
    expect(persistResult?.sizeFor(ImageFormat.jpeg), 6);
    expect(persistResult?.hashFor(ImageFormat.jpeg), 'abc123');
    expect(
      persistCall.arguments,
      <String, dynamic>{
        'imageId': 'img-1',
        'destination': <String, dynamic>{
          'kind': 'appPath',
          'root': '/tmp/root'
        },
        'sessionFolder': 'session/images',
        'baseName': 'img-1',
        'format': 'jpeg',
      },
    );
  });

  test(
    'surfaces explicit unsupported raw capture from native initialization',
    () async {
      final rawConfig = captureConfig.copyWith(format: ImageFormat.raw);
      initializeErrorCode = 'RAW_JPEG_UNSUPPORTED';
      initializeErrorMessage =
          'RAW capture is not yet supported on the live shared-camera path';

      final sessionManager = ARSessionManager(
        42,
        _FakeBuildContext(),
        PlaneDetectionConfig.horizontal,
      );
      final captureManager = ARCaptureManager(
        sessionManager,
        rawConfig,
        _FakeBuildContext(),
      );

      await expectLater(
        captureManager.captureImageAttempt(),
        throwsA(
          isA<ARCaptureException>()
              .having(
                (error) => error.code,
                'code',
                'RAW_JPEG_UNSUPPORTED',
              )
              .having(
                (error) => error.message,
                'message',
                contains('RAW capture is not yet supported'),
              ),
        ),
      );
    },
  );

  test('forwards staged discard requests to the native channel', () async {
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

    final discardSucceeded = await captureManager.discardCapture('img-1');

    final discardCall = methodCalls.firstWhere(
      (call) => call.method == 'discardCapture',
    );

    expect(discardSucceeded, isTrue);
    expect(
      discardCall.arguments,
      <String, dynamic>{'imageId': 'img-1'},
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
    expect(intrinsics?.cropRegion?.width, 640);
    expect(intrinsics?.cropRegion?.height, 480);
    expect(intrinsics?.distortionCoefficients, hasLength(5));
  });

  test('parses capture capacity snapshots from the native channel', () async {
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

    final capacity = await captureManager.getCaptureCapacity();

    expect(capacity, isA<CaptureCapacity>());
    expect(capacity?.maxEntries, 4);
    expect(capacity?.usedEntries, 1);
    expect(capacity?.readyEntries, 1);
    expect(capacity?.stagedBytes, 6);
    expect(capacity?.canCapture, isTrue);
  });

  test('reads native performance snapshots from the per-view channel',
      () async {
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

    final snapshot = await captureManager.getPerformanceSnapshot();

    expect(snapshot?['processPssBytes'], 400000000);
    expect(snapshot?['imagesAcquired'], 20);
    expect(snapshot?['imagesClosed'], 20);
    expect(snapshot?['batteryPercent'], 85);
  });

  test('stages ISO through the per-view capture channel', () async {
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

    final actualIso = await captureManager.setISO(200);
    final setIsoCall =
        methodCalls.firstWhere((call) => call.method == 'setISO');

    expect(actualIso, 200);
    expect(
      setIsoCall.arguments,
      <String, dynamic>{'isoValue': 200, 'temporary': true},
    );
  });

  test('stages exposure time through the per-view capture channel', () async {
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

    final actualExposure = await captureManager.setExposureTime(
      const Duration(milliseconds: 10),
    );
    final setExposureCall = methodCalls.firstWhere(
      (call) => call.method == 'setExposureTime',
    );

    expect(actualExposure, const Duration(milliseconds: 10));
    expect(
      setExposureCall.arguments,
      <String, dynamic>{
        'exposureTimeMicroseconds': 10000,
        'temporary': true,
      },
    );
  });

  test('parses current exposure state from the native channel', () async {
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

    final currentIso = await captureManager.getCurrentISO();
    final currentExposure = await captureManager.getCurrentExposureTime();
    final isoRange = await captureManager.getSupportedISORange();
    final exposureRange = await captureManager.getSupportedExposureRange();
    final state = await captureManager.getCurrentExposureState();

    expect(currentIso, 320);
    expect(currentExposure, const Duration(microseconds: 12500));
    expect(isoRange, <int>[100, 6400]);
    expect(
      exposureRange,
      <String, Duration>{
        'min': const Duration(microseconds: 100),
        'max': const Duration(microseconds: 33333),
      },
    );
    expect(state.currentISO, 320);
    expect(state.currentExposureTime, const Duration(microseconds: 12500));
    expect(state.isAutoExposureEnabled, isFalse);
    expect(state.exposureMode, ExposureMode.manual);
  });

  test('toggles auto exposure through the per-view capture channel', () async {
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

    final enabled = await captureManager.setAutoExposureEnabled(false);
    final call = methodCalls.firstWhere(
      (methodCall) => methodCall.method == 'setAutoExposureEnabled',
    );

    expect(enabled, isFalse);
    expect(call.arguments, <String, dynamic>{'enabled': false});
    expect(
      methodCalls
          .any((methodCall) => methodCall.method == 'getCurrentExposureState'),
      isFalse,
    );
  });

  test('stages exposure compensation and parses compensation info', () async {
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

    final info = await captureManager.getExposureCompensationInfo();
    final actualCompensation =
        await captureManager.setExposureCompensation(0.5);

    expect(info, isNotNull);
    expect(info?.minCompensation, -2.0);
    expect(info?.maxCompensation, 2.0);
    expect(info?.currentCompensation, 0.5);
    expect(info?.stepSize, 0.5);
    expect(actualCompensation, 0.5);
  });

  test('locks and unlocks exposure through the per-view capture channel',
      () async {
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

    final didLock = await captureManager.lockExposure();
    final didUnlock = await captureManager.unlockExposure();

    expect(didLock, isTrue);
    expect(didUnlock, isTrue);
    expect(
      methodCalls.any((methodCall) => methodCall.method == 'lockExposure'),
      isTrue,
    );
    expect(
      methodCalls.any((methodCall) => methodCall.method == 'unlockExposure'),
      isTrue,
    );
  });

  test('stages focus distance through the per-view capture channel', () async {
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

    final actualDistance = await captureManager.setFocusDistance(0.4);
    final call = methodCalls.firstWhere(
      (methodCall) => methodCall.method == 'setFocusDistance',
    );

    expect(actualDistance, 0.4);
    expect(call.arguments, <String, dynamic>{'distance': 0.4});
  });

  test('parses current focus state from the native channel', () async {
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

    final state = await captureManager.getCurrentFocusState();
    final supportedModes = await captureManager.getSupportedFocusModes();

    expect(state.currentFocusDistance, 0.4);
    expect(state.isAutofocusEnabled, isFalse);
    expect(state.currentFocusMode, FocusMode.fixed);
    expect(state.focusStatus, FocusStatus.inactive);
    expect(
      supportedModes,
      <FocusMode>[
        FocusMode.auto,
        FocusMode.continuous,
        FocusMode.fixed,
        FocusMode.infinity,
      ],
    );
  });

  test('stages focus mode and autofocus through the per-view capture channel',
      () async {
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

    final focusModeApplied = await captureManager.setFocusMode(FocusMode.fixed);
    final autofocusApplied = await captureManager.setAutofocusEnabled(false);

    expect(focusModeApplied, isTrue);
    expect(autofocusApplied, isFalse);
    expect(
      methodCalls
          .any((methodCall) => methodCall.method == 'getCurrentFocusState'),
      isFalse,
    );
  });

  test('stages focus-at-point through the per-view capture channel', () async {
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

    final applied = await captureManager.focusAtPoint(const Offset(0.5, 0.5));
    final state = await captureManager.getCurrentFocusState();

    expect(applied, isTrue);
    expect(state.currentFocusMode, FocusMode.auto);
    expect(state.isAutofocusEnabled, isTrue);
    expect(state.focusStatus, FocusStatus.scanning);
    expect(state.focusRegion, const Rect.fromLTWH(0.425, 0.425, 0.15, 0.15));
  });

  test('stages white balance mode through the per-view capture channel',
      () async {
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

    final applied = await captureManager.setWhiteBalanceMode(
      WhiteBalanceMode.daylight,
    );
    final state = await captureManager.getCurrentWhiteBalanceState();
    final modes = await captureManager.getSupportedWhiteBalanceModes();

    expect(applied, isTrue);
    expect(state.currentMode, WhiteBalanceMode.daylight);
    expect(state.isWhiteBalanceLocked, isFalse);
    expect(state.isAutoWhiteBalanceEnabled, isTrue);
    expect(state.status, WhiteBalanceStatus.inactive);
    expect(
      modes,
      <WhiteBalanceMode>[
        WhiteBalanceMode.auto,
        WhiteBalanceMode.daylight,
        WhiteBalanceMode.shade,
      ],
    );
  });

  test('stages white balance lock state through the per-view capture channel',
      () async {
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

    final locked = await captureManager.lockWhiteBalance();
    final unlocked = await captureManager.unlockWhiteBalance();

    expect(locked, isTrue);
    expect(unlocked, isTrue);
    expect(
      methodCalls.any(
        (methodCall) => methodCall.method == 'getCurrentWhiteBalanceState',
      ),
      isFalse,
    );
  });

  test(
      'stages manual color temperature and reports the supported temperature range',
      () async {
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

    final range = await captureManager.getSupportedColorTemperatureRange();
    final actualTemperature = await captureManager.setColorTemperature(4200);
    final state = await captureManager.getCurrentWhiteBalanceState();

    expect(range, <String, int>{'min': 2000, 'max': 8000});
    expect(actualTemperature, 4200);
    expect(state.currentMode, WhiteBalanceMode.manual);
    expect(state.currentColorTemperature, 4200);
    expect(state.isAutoWhiteBalanceEnabled, isFalse);
  });

  test('stages point white balance through the per-view capture channel',
      () async {
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

    final applied = await captureManager.setWhiteBalanceFromPoint(
      const Offset(0.5, 0.5),
    );
    final state = await captureManager.getCurrentWhiteBalanceState();

    expect(applied, isTrue);
    expect(state.currentMode, WhiteBalanceMode.auto);
    expect(state.currentColorTemperature, isNull);
    expect(state.isAutoWhiteBalanceEnabled, isTrue);
  });

  test('stages flash mode and parses flash state through the per-view channel',
      () async {
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

    final available = await captureManager.isFlashAvailable();
    final applied = await captureManager.setFlashMode(FlashMode.auto);
    final state = await captureManager.getCurrentFlashState();

    expect(available, isTrue);
    expect(applied, isTrue);
    expect(state.currentFlashMode, FlashMode.auto);
    expect(state.isTorchEnabled, isFalse);
    expect(state.isFlashReady, isTrue);
    expect(state.flashStatus, FlashStatus.ready);
  });

  test('stages torch through the per-view capture channel', () async {
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

    final enabled = await captureManager.setTorchEnabled(true);
    final torchState = await captureManager.getCurrentFlashState();
    final disabled = await captureManager.setTorchEnabled(false);
    final offState = await captureManager.getCurrentFlashState();

    expect(enabled, isTrue);
    expect(torchState.currentFlashMode, FlashMode.torch);
    expect(torchState.isTorchEnabled, isTrue);
    expect(disabled, isTrue);
    expect(offState.currentFlashMode, FlashMode.off);
    expect(offState.isTorchEnabled, isFalse);
  });

  test('emits pose updates from platform events', () async {
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
    final poses = <dynamic>[];

    final subscription = captureManager.poseDataStream.listen(poses.add);
    final codec = const StandardMethodCodec();

    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
      'arcapture_42',
      codec.encodeMethodCall(
        MethodCall(
          'onPoseUpdate',
          <String, dynamic>{
            'position': <String, dynamic>{'x': 1.0, 'y': 2.0, 'z': 3.0},
            'rotation': <String, dynamic>{
              'x': 1.0,
              'y': 0.0,
              'z': 0.0,
              'w': 0.0,
            },
            'transform': List<double>.generate(
              16,
              (index) => switch (index) {
                0 || 15 => 1.0,
                5 || 10 => -1.0,
                _ => 0.0,
              },
            ),
            'convention': 'opencv_c2w_v1',
            'timestampMs': DateTime(2026).millisecondsSinceEpoch,
            'sensorTimestampNs': 123456789,
            'confidence': 1.0,
            'isTracking': true,
            'trackingState': 'tracking',
          },
        ),
      ),
      (_) {},
    );

    await Future<void>.delayed(Duration.zero);

    expect(poses, hasLength(1));
    expect(poses.single.position.x, 1.0);
    expect(poses.single.convention, 'opencv_c2w_v1');
    expect(poses.single.sensorTimestampNs, 123456789);
    expect(poses.single.trackingState, 'tracking');

    await subscription.cancel();
  });

  test('emits observed control-state updates from platform events', () async {
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
    final exposures = <CameraExposureState>[];
    final focuses = <CameraFocusState>[];
    final whiteBalances = <CameraWhiteBalanceState>[];
    final flashes = <CameraFlashState>[];
    final codec = const StandardMethodCodec();

    final exposureSub =
        captureManager.exposureStateStream.listen(exposures.add);
    final focusSub = captureManager.focusStateStream.listen(focuses.add);
    final whiteBalanceSub = captureManager.whiteBalanceStateStream.listen(
      whiteBalances.add,
    );
    final flashSub = captureManager.flashStateStream.listen(flashes.add);

    Future<void> emitEvent(String method, Map<String, dynamic> args) async {
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .handlePlatformMessage(
        'arcapture_42',
        codec.encodeMethodCall(MethodCall(method, args)),
        (_) {},
      );
    }

    await emitEvent('onExposureStateChanged', <String, dynamic>{
      'currentISO': 640,
      'currentExposureTime': 8000,
      'isAutoExposureEnabled': false,
      'isExposureLocked': true,
      'exposureCompensation': -1.0,
      'exposureMode': 'manual',
    });
    await emitEvent('onFocusStateChanged', <String, dynamic>{
      'currentFocusDistance': 0.5,
      'isAutofocusEnabled': true,
      'currentFocusMode': 'auto',
      'isFocusLocked': true,
      'isFocusPeakingEnabled': false,
      'focusStatus': 'locked',
      'focusRegion': <String, dynamic>{
        'left': 0.1,
        'top': 0.2,
        'width': 0.3,
        'height': 0.4,
      },
    });
    await emitEvent('onWhiteBalanceStateChanged', <String, dynamic>{
      'currentMode': 'manual',
      'currentColorTemperature': 4200,
      'isWhiteBalanceLocked': false,
      'isAutoWhiteBalanceEnabled': false,
      'status': 'locked',
    });
    await emitEvent('onFlashStateChanged', <String, dynamic>{
      'currentFlashMode': 'torch',
      'isTorchEnabled': true,
      'isFlashReady': false,
      'flashStatus': 'charging',
    });

    await Future<void>.delayed(Duration.zero);

    expect(exposures, hasLength(1));
    expect(exposures.single.currentISO, 640);
    expect(exposures.single.exposureMode, ExposureMode.manual);
    expect(exposures.single.isExposureLocked, isTrue);

    expect(focuses, hasLength(1));
    expect(focuses.single.currentFocusMode, FocusMode.auto);
    expect(focuses.single.focusStatus, FocusStatus.locked);
    expect(
      focuses.single.focusRegion,
      const Rect.fromLTWH(0.1, 0.2, 0.3, 0.4),
    );

    expect(whiteBalances, hasLength(1));
    expect(whiteBalances.single.currentMode, WhiteBalanceMode.manual);
    expect(whiteBalances.single.currentColorTemperature, 4200);
    expect(whiteBalances.single.status, WhiteBalanceStatus.locked);

    expect(flashes, hasLength(1));
    expect(flashes.single.currentFlashMode, FlashMode.torch);
    expect(flashes.single.isTorchEnabled, isTrue);
    expect(flashes.single.flashStatus, FlashStatus.charging);

    await exposureSub.cancel();
    await focusSub.cancel();
    await whiteBalanceSub.cancel();
    await flashSub.cancel();
  });

  test('setISO does not force an immediate exposure-state readback', () async {
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

    await captureManager.setISO(800);

    expect(
      methodCalls.where((call) => call.method == 'getCurrentExposureState'),
      isEmpty,
    );
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
        'img-1',
        '/tmp/test.png',
        ImageFormat.jpeg,
      ),
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
            .having((error) => error.code, 'code', 'CAPTURE_NOT_INITIALIZED')
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

  test(
    'preserves native control error codes across the remaining exposure helpers',
    () async {
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

      Future<void> expectControlUnsupported(
        Future<dynamic> Function() action,
      ) async {
        await expectLater(
          action(),
          throwsA(
            isA<ARCaptureException>()
                .having((error) => error.code, 'code', 'CONTROL_UNSUPPORTED')
                .having(
                  (error) => error.message,
                  'message',
                  contains('unsupported on this backend'),
                ),
          ),
        );
      }

      failingMethodName = 'setISO';
      await expectControlUnsupported(() => captureManager.setISO(200));

      failingMethodName = 'setExposureTime';
      await expectControlUnsupported(
        () => captureManager.setExposureTime(
          const Duration(microseconds: 12500),
        ),
      );

      failingMethodName = 'getCurrentISO';
      await expectControlUnsupported(captureManager.getCurrentISO);

      failingMethodName = 'getCurrentExposureTime';
      await expectControlUnsupported(captureManager.getCurrentExposureTime);

      failingMethodName = 'getSupportedISORange';
      await expectControlUnsupported(captureManager.getSupportedISORange);

      failingMethodName = 'getSupportedExposureRange';
      await expectControlUnsupported(captureManager.getSupportedExposureRange);

      failingMethodName = 'setAutoExposureEnabled';
      await expectControlUnsupported(
        () => captureManager.setAutoExposureEnabled(false),
      );

      failingMethodName = 'getCurrentExposureState';
      await expectControlUnsupported(captureManager.getCurrentExposureState);
    },
  );

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

  test('loadProfile preserves a stable code when the profile does not exist',
      () async {
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
    final statuses = <ProfileApplicationStatus>[];
    final subscription =
        captureManager.profileStatusStream.listen(statuses.add);

    await expectLater(
      captureManager.loadProfile('DoesNotExist'),
      throwsA(
        isA<ARCaptureException>()
            .having((error) => error.code, 'code', 'PROFILE_NOT_FOUND')
            .having(
              (error) => error.message,
              'message',
              contains('DoesNotExist'),
            ),
      ),
    );
    await Future<void>.delayed(Duration.zero);

    expect(
      statuses,
      <ProfileApplicationStatus>[
        ProfileApplicationStatus.loading,
        ProfileApplicationStatus.error,
      ],
    );

    await subscription.cancel();
  });

  test('loadProfile applies saved profiles in WB, focus, exposure, flash order',
      () async {
    final profile = CameraProfile(
      name: 'Ordered',
      description: 'Applies every stage',
      createdAt: DateTime.utc(2026, 1, 1),
      parameters: CameraProfileParameters(
        whiteBalanceMode: WhiteBalanceMode.daylight,
        colorTemperature: 4200,
        autofocusEnabled: false,
        focusMode: FocusMode.fixed,
        focusDistance: 0.4,
        autoExposureEnabled: true,
        isoValue: 320,
        exposureTime: const Duration(microseconds: 12500),
        exposureCompensation: 0.5,
        flashMode: FlashMode.on,
      ),
    );
    SharedPreferences.setMockInitialValues(<String, Object>{
      'ar_capture_profiles':
          jsonEncode(<Map<String, dynamic>>[profile.toMap()]),
    });

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
    final statuses = <ProfileApplicationStatus>[];
    final subscription =
        captureManager.profileStatusStream.listen(statuses.add);

    final applied = await captureManager.loadProfile('Ordered');
    await Future<void>.delayed(Duration.zero);

    final stagedMethods = methodCalls
        .map((call) => call.method)
        .where(
          (method) => <String>[
            'setWhiteBalanceMode',
            'setColorTemperature',
            'setAutofocusEnabled',
            'setFocusMode',
            'setFocusDistance',
            'setAutoExposureEnabled',
            'setISO',
            'setExposureTime',
            'setExposureCompensation',
            'setFlashMode',
          ].contains(method),
        )
        .toList();

    expect(applied, isTrue);
    expect(
      statuses,
      <ProfileApplicationStatus>[
        ProfileApplicationStatus.loading,
        ProfileApplicationStatus.applying,
        ProfileApplicationStatus.applied,
      ],
    );
    expect(
      stagedMethods,
      <String>[
        'setWhiteBalanceMode',
        'setColorTemperature',
        'setAutofocusEnabled',
        'setFocusMode',
        'setFocusDistance',
        'setAutoExposureEnabled',
        'setISO',
        'setExposureTime',
        'setExposureCompensation',
        'setFlashMode',
      ],
    );

    await subscription.cancel();
  });

  test('loadProfile reports the exact failed stage on partial application',
      () async {
    final profile = CameraProfile(
      name: 'ExposureFail',
      description: 'Fails in exposure stage',
      createdAt: DateTime.utc(2026, 1, 1),
      parameters: CameraProfileParameters(
        whiteBalanceMode: WhiteBalanceMode.daylight,
        autofocusEnabled: false,
        focusMode: FocusMode.fixed,
        autoExposureEnabled: true,
        exposureCompensation: 0.5,
        flashMode: FlashMode.on,
      ),
    );
    SharedPreferences.setMockInitialValues(<String, Object>{
      'ar_capture_profiles':
          jsonEncode(<Map<String, dynamic>>[profile.toMap()]),
    });
    failingMethodName = 'setExposureCompensation';

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
    final statuses = <ProfileApplicationStatus>[];
    final subscription =
        captureManager.profileStatusStream.listen(statuses.add);

    final applied = await captureManager.loadProfile('ExposureFail');
    await Future<void>.delayed(Duration.zero);

    expect(applied, isFalse);
    expect(
      statuses,
      <ProfileApplicationStatus>[
        ProfileApplicationStatus.loading,
        ProfileApplicationStatus.applying,
        ProfileApplicationStatus.failedExposure,
      ],
    );
    expect(
      methodCalls.any((call) => call.method == 'setFlashMode'),
      isTrue,
      reason: 'Current partial-apply semantics continue through later stages.',
    );

    await subscription.cancel();
  });

  test('applyQuickProfile uses built-in profiles through the same status flow',
      () async {
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
    final statuses = <ProfileApplicationStatus>[];
    final subscription =
        captureManager.profileStatusStream.listen(statuses.add);

    final applied = await captureManager.applyQuickProfile(
      QuickProfileType.landscape,
    );
    await Future<void>.delayed(Duration.zero);

    expect(applied, isTrue);
    expect(
      statuses,
      <ProfileApplicationStatus>[
        ProfileApplicationStatus.loading,
        ProfileApplicationStatus.applying,
        ProfileApplicationStatus.applied,
      ],
    );
    expect(
      methodCalls.any(
        (call) =>
            call.method == 'setFocusMode' &&
            (call.arguments as Map<dynamic, dynamic>)['mode'] == 'infinity',
      ),
      isTrue,
    );

    await subscription.cancel();
  });

  test('saveProfile preserves underlying control error codes', () async {
    failingMethodName = 'getCurrentExposureState';

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
      captureManager.saveProfile('Broken'),
      throwsA(
        isA<ARCaptureException>()
            .having((error) => error.code, 'code', 'CONTROL_UNSUPPORTED')
            .having(
              (error) => error.message,
              'message',
              contains('unsupported on this backend'),
            ),
      ),
    );
  });

  test('importProfile persists imported parameters instead of current settings',
      () async {
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

    final importedProfile = CameraProfile(
      name: 'Imported',
      description: 'Imported profile payload',
      createdAt: DateTime.utc(2026, 1, 1),
      parameters: CameraProfileParameters(
        isoValue: 1600,
        exposureTime: const Duration(microseconds: 32000),
        autoExposureEnabled: false,
        exposureCompensation: -0.5,
        focusDistance: 0.7,
        autofocusEnabled: false,
        focusMode: FocusMode.infinity,
        whiteBalanceMode: WhiteBalanceMode.shade,
        colorTemperature: 5600,
        flashMode: FlashMode.on,
      ),
      author: 'Import Fixture',
    );

    final imported = await captureManager.importProfile(
      importedProfile.toJsonString(),
    );
    final savedProfiles = await captureManager.getSavedProfiles();
    final saved =
        savedProfiles.singleWhere((profile) => profile.name == 'Imported');

    expect(imported, isTrue);
    expect(saved.description, 'Imported profile payload');
    expect(saved.author, 'Import Fixture');
    expect(saved.parameters.isoValue, 1600);
    expect(saved.parameters.exposureTime, const Duration(microseconds: 32000));
    expect(saved.parameters.autoExposureEnabled, isFalse);
    expect(saved.parameters.exposureCompensation, -0.5);
    expect(saved.parameters.focusDistance, 0.7);
    expect(saved.parameters.autofocusEnabled, isFalse);
    expect(saved.parameters.focusMode, FocusMode.infinity);
    expect(saved.parameters.whiteBalanceMode, WhiteBalanceMode.shade);
    expect(saved.parameters.colorTemperature, 5600);
    expect(saved.parameters.flashMode, FlashMode.on);
  });

  test('importProfile respects customName without mutating imported parameters',
      () async {
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

    final importedProfile = CameraProfile(
      name: 'OriginalName',
      description: 'Rename on import',
      createdAt: DateTime.utc(2026, 1, 1),
      parameters: CameraProfileParameters(
        isoValue: 800,
        autoExposureEnabled: false,
        focusMode: FocusMode.fixed,
        whiteBalanceMode: WhiteBalanceMode.daylight,
        flashMode: FlashMode.off,
      ),
    );

    final imported = await captureManager.importProfile(
      importedProfile.toJsonString(),
      customName: 'CustomImported',
    );
    final savedProfiles = await captureManager.getSavedProfiles();
    final saved = savedProfiles.singleWhere(
      (profile) => profile.name == 'CustomImported',
    );

    expect(imported, isTrue);
    expect(
      savedProfiles.any((profile) => profile.name == 'OriginalName'),
      isFalse,
    );
    expect(saved.description, 'Rename on import');
    expect(saved.parameters.isoValue, 800);
    expect(saved.parameters.autoExposureEnabled, isFalse);
    expect(saved.parameters.focusMode, FocusMode.fixed);
    expect(saved.parameters.whiteBalanceMode, WhiteBalanceMode.daylight);
    expect(saved.parameters.flashMode, FlashMode.off);
  });

  test('importProfile accepts integer JSON literals for double-backed fields',
      () async {
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

    final imported = await captureManager.importProfile(
      jsonEncode(<String, dynamic>{
        'name': 'IntegerJson',
        'description': 'Numeric coercion test',
        'createdAt': DateTime.utc(2026, 1, 1).toIso8601String(),
        'parameters': <String, dynamic>{
          'isoValue': 400,
          'autoExposureEnabled': false,
          'exposureCompensation': 1,
          'focusDistance': 1,
          'autofocusEnabled': false,
          'focusMode': 'fixed',
          'whiteBalanceMode': 'shade',
          'flashMode': 'off',
        },
      }),
    );
    final savedProfiles = await captureManager.getSavedProfiles();
    final saved =
        savedProfiles.singleWhere((profile) => profile.name == 'IntegerJson');

    expect(imported, isTrue);
    expect(saved.parameters.exposureCompensation, 1.0);
    expect(saved.parameters.focusDistance, 1.0);
    expect(saved.parameters.autoExposureEnabled, isFalse);
    expect(saved.parameters.focusMode, FocusMode.fixed);
    expect(saved.parameters.whiteBalanceMode, WhiteBalanceMode.shade);
  });

  test(
    'session manager without capture config does not create capture manager',
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
    },
  );

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

  test(
    'manual-only capture interval remains valid and initializes session',
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
    },
  );

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
    expect(
      await captureManager.getImageData('img-1', ImageFormat.jpeg),
      isNull,
    );
    expect(await captureManager.getCameraIntrinsics(), isNull);
  });
}
