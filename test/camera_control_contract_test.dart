import 'dart:convert';
import 'dart:io';

import 'package:ar_flutter_plugin_2/capabilities/ar_camera_capabilities.dart';
import 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
import 'package:ar_flutter_plugin_2/managers/ar_capture_manager.dart';
import 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// The runtime camera control replies shared by Android
/// (`Runtime*ControlBridge`) and iOS (`CameraControlBridge`): the contract
/// `test_assets/camera_controls/runtime_control_replies.json` names every
/// reply key and mode name; its examples decode with the Dart models. The
/// camera queries and native device profile also reach the channel on iOS,
/// while ARCore availability remains an Android-only query.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final contract = jsonDecode(
    File(
      'test_assets/camera_controls/runtime_control_replies.json',
    ).readAsStringSync(),
  ) as Map<String, dynamic>;
  final examples = (contract['examples'] as Map).cast<String, dynamic>();
  final stateKeys = (contract['stateKeys'] as Map).cast<String, dynamic>();
  Map<String, dynamic> example(String method) =>
      Map<String, dynamic>.from(examples[method] as Map);

  test('every example carries exactly the contract keys', () {
    for (final entry in examples.entries) {
      expect(
        (entry.value as Map).keys.toSet(),
        (stateKeys[entry.key] as List).toSet(),
        reason: entry.key,
      );
    }
  });

  test('the examples decode with the Dart state models', () {
    final exposure = CameraExposureState.fromMap(
      example('getCurrentExposureState'),
    );
    expect(exposure.currentISO, 200);
    expect(exposure.currentExposureTime, const Duration(microseconds: 16667));
    expect(exposure.exposureMode, ExposureMode.manual);
    final compensation = ExposureCompensationInfo.fromMap(
      example('getExposureCompensationInfo'),
    );
    expect(compensation.stepSize, closeTo(1 / 3, 1e-12));
    final focus = CameraFocusState.fromMap(example('getCurrentFocusState'));
    expect(focus.focusStatus, FocusStatus.locked);
    expect(focus.focusRegion!.width, closeTo(0.15, 1e-12));
    final whiteBalance = CameraWhiteBalanceState.fromMap(
      example('getCurrentWhiteBalanceState'),
    );
    expect(whiteBalance.currentMode, WhiteBalanceMode.manual);
    expect(whiteBalance.status, WhiteBalanceStatus.locked);
    final flash = CameraFlashState.fromMap(example('getCurrentFlashState'));
    expect(flash.currentFlashMode, FlashMode.torch);
    expect(flash.flashStatus, FlashStatus.ready);
  });

  test('mode names equal the Dart enums', () {
    final modes = (contract['modeNames'] as Map).cast<String, dynamic>();
    Set<String> names(List<Enum> values) => values.map((v) => v.name).toSet();
    expect((modes['focus'] as List).toSet(), names(FocusMode.values));
    expect(
      (modes['whiteBalance'] as List).toSet(),
      names(WhiteBalanceMode.values),
    );
    expect((modes['flash'] as List).toSet(), names(FlashMode.values));
  });

  group('camera queries on iOS', () {
    const channel = MethodChannel(ARCameraCapabilities.channelName);
    late List<String> calls;

    setUp(() {
      calls = [];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        return switch (call.method) {
          'getDeviceCapabilityProfile' => {
              'presetVersion': ARCameraCapabilities.capabilityPresetVersion,
              'fingerprint': 'iPhone13,3|iOS27|arkit-primary',
              'primaryCameraId': 'arkit-primary',
              'supportsExactStillResolutionSelection': false,
              'advertisedJpegRegularResolutions': <Map<String, int>>[],
              'advertisedJpegHighResolutionResolutions': [
                {'width': 4032, 'height': 3024},
              ],
              'sharedResolutionValidationComplete': false,
              'sharedCameraProbeStatus': 'pending',
              'sharedCameraProbeError': '',
              'validatedSharedResolutions': <Map<String, int>>[],
              'validatedRawJpegResolutions': <Map<String, int>>[],
              'rawJpegProbeStatus': 'unsupported',
              'rawJpegProbeError':
                  'The ARKit capture route delivers JPEG only.',
              'rawCapture': false,
              'manualSensorControls': true,
              'flash': false,
              'cached': false,
            },
          'getSupportedResolutions' => [
              {'width': 4032, 'height': 3024},
              {'width': 1920, 'height': 1440},
            ],
          'getSupportedFormats' => ['jpeg'],
          'getSupportedISORange' => [50, 100, 200, 400, 800, 1600, 3200],
          'getSupportedExposureRange' => {'min': 14, 'max': 333333},
          'isFormatSupported' => call.arguments == 'jpeg',
          _ => null,
        };
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test(
        'reach the native iOS profile channel while ARCore availability stays local',
        () async {
      final capabilities = ARCameraCapabilities(
        supportedOverride: false,
        cameraQueriesOverride: true,
      );
      expect(await capabilities.getSupportedResolutions(), const [
        CameraResolution(width: 4032, height: 3024),
        CameraResolution(width: 1920, height: 1440),
      ]);
      expect(await capabilities.getSupportedFormats(), [CaptureFormat.jpeg]);
      expect(
          await capabilities.isFormatSupported(CaptureFormat.rawJpeg), isFalse);
      expect(await capabilities.getSupportedISORange(), hasLength(7));
      expect(
        (await capabilities.getSupportedExposureRange())['max'],
        const Duration(microseconds: 333333),
      );
      final profile = await capabilities.getDeviceCapabilityProfile();
      expect(
          profile.presetVersion, ARCameraCapabilities.capabilityPresetVersion);
      expect(profile.isCurrentPreset, isTrue);
      expect(profile.fingerprint, 'iPhone13,3|iOS27|arkit-primary');
      expect(profile.primaryCameraId, 'arkit-primary');
      expect(profile.supportsExactStillResolutionSelection, isFalse);
      expect(profile.advertisedJpegRegularResolutions, isEmpty);
      expect(profile.advertisedJpegHighResolutionResolutions, const [
        CameraResolution(width: 4032, height: 3024),
      ]);
      expect(profile.jpegDiscoveryCandidates, const [
        CameraResolution(width: 4032, height: 3024),
      ]);
      expect(profile.sharedResolutionValidationComplete, isFalse);
      expect(profile.sharedCameraProbeStatus, 'pending');
      expect(profile.sharedCameraCapture, isFalse);
      expect(profile.validatedSharedResolutions, isEmpty);
      expect(profile.validatedRawJpegResolutions, isEmpty);
      expect(profile.rawJpegProbeStatus, 'unsupported');
      expect(profile.rawJpegProbeError,
          'The ARKit capture route delivers JPEG only.');
      expect(profile.rawCapture, isFalse);
      expect(profile.rawJpegCapture, isFalse);
      expect(profile.manualSensorControls, isTrue);
      expect(profile.flash, isFalse);
      expect(profile.cached, isFalse);
      expect(calls.where((method) => method == 'getDeviceCapabilityProfile'),
          hasLength(1));
      final availability = await capabilities.getARCoreAvailability();
      expect(availability.supported, isFalse);
      expect(availability.name, 'platform');
      expect(calls, isNot(contains('getARCoreAvailability')));
    });
  });
}
