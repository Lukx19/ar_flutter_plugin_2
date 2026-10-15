import 'package:ar_flutter_plugin_2/managers/ar_capture_manager.dart';
import 'package:ar_flutter_plugin_2/models/native_capture_v2.dart';
import 'package:flutter_test/flutter_test.dart';

/// The `native_capture_v2` maps the iOS capture channel sends (asserted key
/// for key by the Swift `NativeCaptureWireTests` and
/// `NativeCaptureBindingTests`) decode with the unchanged Dart models, and
/// the capture gate admits iOS.
void main() {
  tearDown(() {
    ARCaptureManager.debugIsSupportedOverride = null;
  });

  final context = <String, Object?>{
    'sessionId': 'session-ios',
    'groupId': 'group-a',
    'groupIndex': 0,
    'groupGeneration': 3,
    'trigger': 'manual',
    'requestedAtMs': 1760000000000,
    'coverageRevision': 7,
    'timestampMs': 1760000000001,
    'position': [0.25, -1.5, 2.0],
    'rotation': [0.0, 0.0, 0.0, 1.0],
    'viewMatrix': _identity,
    'projectionMatrix': _identity,
    'groupFromWorld': _identity,
    'worldFromGroup': _identity,
  };

  test('iOS committed, abandoned and recovering events decode', () {
    final committed = ARNativeCaptureEventV2.fromMap({
      'wireVersion': 'native_capture_v2',
      'kind': 'committed',
      'attemptId': '6f1c2d3e-0000-4000-8000-000000000001',
      'captureId':
          'e94ebffc90c4bfd8a755f41c8d748d3d834378fc0e076eee9df77e188070bd73',
      'captureRevision': 1,
      'manifestId':
          '780001b3ed620e8e96135827fe6961238b262b52ebac89108d43f2dc0036af53',
      'jpegSizeBytes': 16,
      'reason': 'committed',
      'recoveryContext': context,
    });
    expect(committed.isTerminal, isTrue);
    expect(committed.jpegSizeBytes, 16);
    expect(committed.recoveryContext!.position, [0.25, -1.5, 2.0]);

    final abandoned = ARNativeCaptureEventV2.fromMap({
      'wireVersion': 'native_capture_v2',
      'kind': 'abandoned',
      'attemptId': 'attempt',
      'reason': 'camera-high-resolution-frame-unavailable',
      'recoveryContext': context,
    });
    expect(abandoned.kind, ARNativeCaptureEventKindV2.abandoned);

    final recovering = ARNativeCaptureEventV2.fromMap({
      'wireVersion': 'native_capture_v2',
      'kind': 'recovering',
      'attemptId': 'attempt',
      'reason': 'route-left-transfer',
      'recoveryContext': context,
    });
    expect(recovering.isTerminal, isFalse);
  });

  group('iOS committed burst shot', () {
    const first =
        'e94ebffc90c4bfd8a755f41c8d748d3d834378fc0e076eee9df77e188070bd73';
    Map<String, Object?> image(int index, {bool tracked = true}) => {
          'index': index,
          'captureId': (index.toRadixString(16) * 64).substring(0, 64),
          'jpegSizeBytes': 1000 + index,
          'exposureTimestampNs': 9000000000 + index * 150000000,
          'width': 4032,
          'height': 3024,
          'trackingNormal': tracked,
          'flags': tracked ? 0 : 1,
          'cameraToWorld': _identity,
          'intrinsics': [2940.0, 2940.0, 2016.0, 1512.0],
        };
    Map<String, Object?> committed(Object? shot) => {
          'wireVersion': 'native_capture_v2',
          'kind': 'committed',
          'attemptId': '6f1c2d3e-0000-4000-8000-000000000001',
          'captureId': first,
          'captureRevision': 1,
          'manifestId':
              '780001b3ed620e8e96135827fe6961238b262b52ebac89108d43f2dc0036af53',
          'jpegSizeBytes': 16,
          'reason': 'committed',
          'recoveryContext': context,
          'shot': shot,
        };
    Map<String, Object?> shot(List<Map<String, Object?>> images,
            {int? imageCount}) =>
        {
          'shotId': '10111213-1415-1617-1819-1a1b1c1d1e1f',
          'mode': 'burst',
          'imageCount': imageCount ?? images.length + 1,
          'firstExposureTimestampNs': 9000000000,
          'images': images,
        };

    test('decodes images 1 to N with their own frames', () {
      final event = ARNativeCaptureEventV2.fromMap(
          committed(shot([image(1), image(2, tracked: false)])));
      final decoded = event.shot!;
      expect(decoded.imageCount, 3);
      expect(decoded.firstExposureTimestampNs, 9000000000);
      expect(decoded.images.map((i) => i.index), [1, 2]);
      expect(decoded.images[0].creditable, isTrue);
      expect(decoded.images[1].creditable, isFalse);
      expect(decoded.images[1].flags,
          ARNativeCaptureShotImageV2.flagTrackingNotNormal);
      expect(decoded.images[0].cameraToWorld, hasLength(16));
      expect(decoded.images[0].intrinsics, [2940.0, 2940.0, 2016.0, 1512.0]);
    });

    test('an image without a frame pose decodes as not creditable', () {
      final unposed = image(1)..remove('cameraToWorld');
      final event = ARNativeCaptureEventV2.fromMap(committed(shot([unposed])));
      expect(event.shot!.images.single.cameraToWorld, isNull);
      expect(event.shot!.images.single.creditable, isFalse);
    });

    test('a terminal without a shot decodes with none', () {
      final map = committed(null)..remove('shot');
      expect(ARNativeCaptureEventV2.fromMap(map).shot, isNull);
    });

    test('malformed shots are refused', () {
      final malformed = <Object?>[
        'not a map',
        shot([image(1)], imageCount: 3),
        shot([image(2), image(1)]),
        shot([image(1), image(1)]),
        shot([image(1)..['captureId'] = first]),
        shot([image(1)..['flags'] = 8]),
        shot([image(1)..['extra'] = 1]),
        shot([image(1)..['cameraToWorld'] = [1.0]]),
        shot([image(1)..['trackingNormal'] = 1]),
        shot([image(1)..['captureId'] = 'not-a-hash']),
        shot([image(5)], imageCount: 2),
        {...shot([image(1)]), 'mode': 'quality'},
        {...shot([image(1)]), 'shotId': 'shot'},
        <String, Object?>{},
      ];
      for (final value in malformed) {
        expect(() => ARNativeCaptureEventV2.fromMap(committed(value)),
            throwsFormatException,
            reason: '$value');
      }
    });

    test('an assessed committed terminal never carries a shot', () {
      expect(
        () => ARNativeCaptureEventV2.fromMap(
          {...committed(shot([image(1)])), 'wireVersion': 'native_capture_quality_v1'},
          allowAssessedQuality: true,
        ),
        throwsFormatException,
      );
    });
  });

  test('iOS admission reply with a null or replayed terminal decodes', () {
    final pending = ARNativeCaptureAdmissionResultV2.fromMap({
      'wireVersion': 'native_capture_v2',
      'attemptId': 'attempt',
      'phase': 'reserved_accepted',
      'durable': true,
      'terminal': null,
    });
    expect(pending.terminal, isNull);
    final replayed = ARNativeCaptureAdmissionResultV2.fromMap({
      'wireVersion': 'native_capture_v2',
      'attemptId': 'attempt',
      'phase': 'abandoned_attempt',
      'durable': true,
      'terminal': {
        'wireVersion': 'native_capture_v2',
        'kind': 'abandoned',
        'attemptId': 'attempt',
        'reason': 'durable-replay-no-reexposure',
        'recoveryContext': context,
      },
    });
    expect(replayed.terminal!.reason, 'durable-replay-no-reexposure');
  });

  test('iOS health, ready and recovery failure events decode', () {
    final health = ARNativeCaptureEventV2.fromMap({
      'wireVersion': 'native_capture_v2',
      'kind': 'health',
      'health': {
        'exposures': 2,
        'lateCallbacks': 1,
        'closedComponents': 3,
        'committed': 1,
        'abandoned': 1,
        'unknownQueries': 1,
        'running': 0,
        'fundedWaiting': 0,
        'retainedImageBytes': 0,
        'readyPictureSets': 0,
      },
    });
    expect(health.health!.closedComponents, 3);
    expect(
      ARNativeCaptureEventV2.fromMap({
        'wireVersion': 'native_capture_v2',
        'kind': 'ready',
        'reason': 'native-owner-ready',
      }).kind,
      ARNativeCaptureEventKindV2.ready,
    );
    expect(
      ARNativeCaptureEventV2.fromMap({
        'wireVersion': 'native_capture_v2',
        'kind': 'recoveryFailed',
        'reason': 'durable-startup-recovery-failed',
      }).kind,
      ARNativeCaptureEventKindV2.recoveryFailed,
    );
  });

  test('capture gate follows the platform override', () {
    ARCaptureManager.debugIsSupportedOverride = true;
    expect(ARCaptureManager.platformSupportsCapture, isTrue);
    ARCaptureManager.debugIsSupportedOverride = false;
    expect(ARCaptureManager.platformSupportsCapture, isFalse);
  });
}

const _identity = <double>[
  1, 0, 0, 0, //
  0, 1, 0, 0, //
  0, 0, 1, 0, //
  0, 0, 0, 1, //
];
