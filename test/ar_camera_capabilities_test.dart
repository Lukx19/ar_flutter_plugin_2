import 'package:ar_flutter_plugin_2/capabilities/ar_camera_capabilities.dart';
import 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('ar_flutter_plugin_2/camera_capabilities');
  late List<MethodCall> calls;
  late bool resolutionSupported;
  late bool formatSupported;

  setUp(() {
    calls = <MethodCall>[];
    resolutionSupported = true;
    formatSupported = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return switch (call.method) {
        'getARCoreAvailability' => <String, dynamic>{
            'name': 'SUPPORTED_INSTALLED',
            'supported': true,
            'transient': false,
            'unknown': false,
          },
        'getDeviceCapabilityProfile' => <String, dynamic>{
            'presetVersion': ARCameraCapabilities.capabilityPresetVersion,
            'fingerprint': 'test/device',
            'primaryCameraId': '0',
            'sharedResolutionValidationComplete': true,
            'sharedCameraProbeStatus': 'supported',
            'validatedSharedResolutions': <Map<String, int>>[
              <String, int>{'width': 1920, 'height': 1080},
            ],
            'validatedRawJpegResolutions': <Map<String, int>>[],
            'rawJpegProbeStatus': 'unsupported',
            'rawCapture': false,
            'manualSensorControls': true,
            'flash': true,
            'primaryPhysicalCameraIds': <String>['2', '5', '6'],
            'logicalMultiCamera': true,
            'concurrentCameraIdSets': <List<String>>[
              <String>['0', '1'],
              <String>['0', '3'],
            ],
            'rearConcurrentCameraIds': <String>['0', '2'],
            'cached': true,
          },
        'getSupportedResolutions' => <Map<String, int>>[
            <String, int>{'width': 3840, 'height': 2160},
            <String, int>{'width': 1920, 'height': 1080},
            <String, int>{'width': 1280, 'height': 720},
          ],
        'getSupportedSharedCameraResolutions' => <Map<String, int>>[
            <String, int>{'width': 1920, 'height': 1080},
          ],
        'getSupportedFormats' => <String>['jpeg', 'raw+jpeg', 'unknown'],
        'getSupportedISORange' => <int>[50, 3200],
        'getSupportedExposureRange' => <String, int>{
            'min': 100,
            'max': 30000000,
          },
        'isResolutionSupported' => resolutionSupported,
        'isFormatSupported' => formatSupported,
        'getCameraIntrinsics' => <String, dynamic>{
            'focalLength': <String, double>{'fx': 1000, 'fy': 1001},
            'principalPoint': <String, double>{'cx': 960, 'cy': 540},
            'resolution': <String, int>{'width': 1920, 'height': 1080},
            'fieldOfView': <String, double>{'horizontal': 1.5, 'vertical': 1.0},
          },
        _ => null,
      };
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('queries and parses every native capability result', () async {
    final capabilities = ARCameraCapabilities(supportedOverride: true);

    final availability = await capabilities.getARCoreAvailability();
    expect(availability.name, 'SUPPORTED_INSTALLED');
    expect(availability.supported, isTrue);

    final profile = await capabilities.getDeviceCapabilityProfile();
    expect(profile.isCurrentPreset, isTrue);
    expect(profile.sharedCameraCapture, isTrue);
    expect(profile.primaryPhysicalCameraIds, <String>['2', '5', '6']);
    expect(profile.logicalMultiCamera, isTrue);
    expect(profile.concurrentCameraIdSets, <List<String>>[
      <String>['0', '1'],
      <String>['0', '3'],
    ]);
    expect(profile.rearConcurrentCameraIds, <String>['0', '2']);
    expect(profile.validatedSharedResolutions.single,
        const CameraResolution(width: 1920, height: 1080));

    expect(await capabilities.getSupportedResolutions(), hasLength(3));
    expect(
        await capabilities.getSupportedSharedCameraResolutions(),
        <CameraResolution>[
          const CameraResolution(width: 1920, height: 1080),
        ]);
    expect(await capabilities.getSupportedFormats(), <CaptureFormat>[
      CaptureFormat.jpeg,
      CaptureFormat.rawJpeg,
    ]);
    expect(await capabilities.getSupportedISORange(), <int>[50, 3200]);
    expect((await capabilities.getSupportedExposureRange())['min'],
        const Duration(microseconds: 100));
    expect(
      await capabilities.isResolutionSupported(
        const CameraResolution(width: 1920, height: 1080),
      ),
      isTrue,
    );
    expect(await capabilities.isFormatSupported(CaptureFormat.jpeg), isTrue);
    final intrinsics = await capabilities.getCameraIntrinsics();
    expect(intrinsics?.isValid, isTrue);
    expect(intrinsics?.resolution,
        const CameraResolution(width: 1920, height: 1080));
  });

  test('persists operational probe outcomes with stable payloads', () async {
    final capabilities = ARCameraCapabilities(supportedOverride: true);

    await capabilities.saveSharedCameraSupported();
    await capabilities.saveSharedCameraUnsupported('camera conflict');
    await capabilities.saveRawJpegProbeResult(
      supported: false,
      reason: 'unsupported topology',
    );
    await capabilities.resetCapabilityProfileForTesting();

    expect(calls.map((call) => call.method), <String>[
      'saveSharedCameraSupported',
      'saveSharedCameraUnsupported',
      'saveRawJpegProbeResult',
      'resetCapabilityProfileForTesting',
    ]);
    expect(calls[0].arguments, isNull);
    expect(calls[1].arguments, <String, dynamic>{'reason': 'camera conflict'});
    expect(calls[2].arguments, <String, dynamic>{
      'supported': false,
      'reason': 'unsupported topology',
    });
    expect(calls[3].arguments, isNull);
  });

  test('validates, suggests, recommends, and scores configurations', () async {
    final capabilities = ARCameraCapabilities(supportedOverride: true);
    const balanced = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 2500,
      resolution: CameraResolution(width: 1920, height: 1080),
      format: ImageFormat.jpeg,
    );

    expect(
        (await capabilities.validateCaptureConfig(balanced)).isValid, isTrue);
    expect(
        await capabilities.findClosestResolution(
          const CameraResolution(width: 1600, height: 900),
        ),
        const CameraResolution(width: 1280, height: 720));
    final recommended = await capabilities.getRecommendedConfig();
    expect(recommended.resolution,
        const CameraResolution(width: 1920, height: 1080));
    expect(recommended.format, ImageFormat.jpeg);
    expect(await capabilities.isOptimalConfig(balanced), isTrue);
    expect(await capabilities.assessPerformanceImpact(balanced),
        PerformanceImpact.medium);

    resolutionSupported = false;
    formatSupported = false;
    final invalid = await capabilities.validateCaptureConfig(
      const ARCaptureConfig(
        enableHighResCapture: true,
        captureIntervalMs: 500,
        resolution: CameraResolution(width: 4000, height: 3000),
        format: ImageFormat.rawJpeg,
      ),
    );
    expect(invalid.isValid, isFalse);
    expect(invalid.errors, hasLength(2));
    expect(invalid.suggestedConfig?.format, ImageFormat.jpeg);
    expect(invalid.hasWarnings, isTrue);
  });

  test('unsupported platforms return stable capability fallbacks', () async {
    final capabilities = ARCameraCapabilities(supportedOverride: false);

    expect((await capabilities.getARCoreAvailability()).supported, isFalse);
    expect((await capabilities.getDeviceCapabilityProfile()).probeComplete,
        isTrue);
    expect(await capabilities.getSupportedResolutions(), isEmpty);
    expect(await capabilities.getSupportedFormats(), isEmpty);
    expect(await capabilities.getCameraIntrinsics(), isNull);
    expect(
      (await capabilities.validateCaptureConfig(
        const ARCaptureConfig(
          resolution: CameraResolution(width: 640, height: 480),
          format: ImageFormat.jpeg,
        ),
      ))
          .isValid,
      isFalse,
    );
    expect(calls, isEmpty);
  });

  test('native failures are normalized to capability exceptions', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async {
      throw PlatformException(code: 'CAMERA_ERROR', message: 'unavailable');
    });
    final capabilities = ARCameraCapabilities(supportedOverride: true);

    await expectLater(
      capabilities.getSupportedResolutions(),
      throwsA(
        isA<ARCameraCapabilityException>().having(
          (error) => error.message,
          'message',
          contains('unavailable'),
        ),
      ),
    );
  });
}
