import 'package:ar_flutter_plugin_2/models/capture_intent_contract.dart';
import 'package:ar_flutter_plugin_2/models/native_capture_v2.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('admission serializes accepted template without descriptors or bytes',
      () {
    final admission = _admission();
    final map = admission.toMap();

    expect(map.keys, {
      'wireVersion',
      'accepted',
      'poseRecordHash',
      'cameraModelHash',
      'validationRecordHash',
      'ledgerRecordHash',
    });
    expect(map.toString(), isNot(contains('components')));
    expect(map.toString(), isNot(contains('bytes')));
  });

  test('health and terminal events are strict typed scalar values', () {
    final health = ARNativeCaptureEventV2.fromMap({
      'wireVersion': nativeCaptureV2WireVersion,
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
    expect(health.health!.retainedImageBytes, 0);
    expect(health.health!.readyPictureSets, 0);

    final committed = ARNativeCaptureEventV2.fromMap({
      'wireVersion': nativeCaptureV2WireVersion,
      'kind': 'committed',
      'attemptId': 'attempt',
      'captureId': 'capture',
      'captureRevision': 2,
      'manifestId': 'manifest',
      'reason': 'committed',
    });
    expect(committed.isTerminal, isTrue);
    expect(committed.captureRevision, 2);

    final recoveryFailed = ARNativeCaptureEventV2.fromMap({
      'wireVersion': nativeCaptureV2WireVersion,
      'kind': 'recoveryFailed',
      'reason': 'durable-startup-recovery-failed',
    });
    expect(recoveryFailed.kind, ARNativeCaptureEventKindV2.recoveryFailed);
    expect(recoveryFailed.attemptId, isNull);
  });

  test('rejects non-qualified profiles and unbacked reservations', () {
    expect(
      () => _admission(components: {CaptureComponentKind.raw}),
      throwsArgumentError,
    );
    expect(() => _admission(physicallyBacked: false), throwsArgumentError);
  });

  test('rejects incomplete terminals, fractional counters and extra fields',
      () {
    expect(
      () => ARNativeCaptureEventV2.fromMap({
        'wireVersion': nativeCaptureV2WireVersion,
        'kind': 'committed',
        'attemptId': 'attempt',
      }),
      throwsFormatException,
    );
    expect(
      () => ARNativeCaptureEventV2.fromMap({
        'wireVersion': nativeCaptureV2WireVersion,
        'kind': 'recoveryFailed',
        'attemptId': 'fabricated-startup-attempt',
        'reason': 'durable-startup-recovery-failed',
      }),
      throwsFormatException,
    );
    expect(
      () => ARNativeCaptureEventV2.fromMap({
        'wireVersion': nativeCaptureV2WireVersion,
        'kind': 'health',
        'unexpected': true,
        'health': _health(exposures: 1),
      }),
      throwsFormatException,
    );
    expect(
      () => ARNativeCaptureEventV2.fromMap({
        'wireVersion': nativeCaptureV2WireVersion,
        'kind': 'health',
        'health': _health(exposures: 1.5),
      }),
      throwsFormatException,
    );
  });

  test('event union enforces exact keys and scalar types per kind', () {
    expect(
      () => ARNativeCaptureEventV2.fromMap({
        'wireVersion': nativeCaptureV2WireVersion,
        'kind': 'accepted',
        'attemptId': 'attempt',
        'reason': 'not-allowed',
      }),
      throwsFormatException,
    );
    expect(
      () => ARNativeCaptureEventV2.fromMap({
        'wireVersion': nativeCaptureV2WireVersion,
        'kind': 'recovering',
        'attemptId': 'attempt',
      }),
      throwsFormatException,
    );
    expect(
      () => ARNativeCaptureEventV2.fromMap({
        'wireVersion': nativeCaptureV2WireVersion,
        'kind': 'committed',
        'attemptId': 'attempt',
        'captureId': 'capture',
        'captureRevision': 1.0,
        'manifestId': 'manifest',
        'reason': 'committed',
      }),
      throwsFormatException,
    );
  });
}

Map<String, Object> _health({required num exposures}) => {
      'exposures': exposures,
      'lateCallbacks': 0,
      'closedComponents': 0,
      'committed': 0,
      'abandoned': 0,
      'unknownQueries': 0,
      'running': 0,
      'fundedWaiting': 0,
      'retainedImageBytes': 0,
      'readyPictureSets': 0,
    };

ARNativeCaptureAdmissionV2 _admission({
  Set<CaptureComponentKind> components = const {CaptureComponentKind.jpeg},
  bool physicallyBacked = true,
}) {
  final digest = List<int>.filled(32, 7);
  final cut = CaptureLifecycleCut(
    sessionId: 'session',
    sessionGeneration: 1,
    groupId: 'group',
    groupGeneration: 1,
    arSessionId: 'ar',
    viewId: 'view',
    viewGeneration: 1,
    bindingToken: 'binding',
    lifecycleSequence: 1,
    operationGeneration: 1,
  );
  return ARNativeCaptureAdmissionV2(
    accepted: CaptureAcceptedAttempt(
      identity: CaptureAttemptIdentity(
        attemptId: 'attempt',
        commitId: 'commit',
        attemptOrdinal: 1,
        lifecycleCut: cut,
      ),
      lane: CaptureLane.manual,
      profile: CaptureComponentProfile(
        profileId: 'jpeg-v2',
        requiredComponents: components,
        maximumComponentBytes: 1024,
        maximumWorkingBytes: 128,
      ),
      reservation: CaptureReservationLiability(
        memoryBytes: 128,
        physicalStoreBytes: 1024,
        componentEntries: components.length,
        terminalEntries: 1,
        rollbackBytes: 0,
        physicallyBacked: physicallyBacked,
      ),
      canonicalIntentHash: digest,
      acceptedReceiptHash: digest,
    ),
    poseRecordHash: digest,
    cameraModelHash: digest,
    validationRecordHash: digest,
    ledgerRecordHash: digest,
  );
}
