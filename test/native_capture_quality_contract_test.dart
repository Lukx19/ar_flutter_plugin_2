import 'package:ar_flutter_plugin_2/models/native_capture_v2.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
      'assessed HEIC keeps its primary size without inventing a JPEG component',
      () {
    final map = _event()..remove('jpegSizeBytes');
    map['heicSizeBytes'] = 1536;
    final event =
        ARNativeCaptureEventV2.fromMap(map, allowAssessedQuality: true);
    expect(event.heicSizeBytes, 1536);
    expect(event.jpegSizeBytes, isNull);
    expect(event.qualityAssessment, isNotNull);
    expect(
        () => ARNativeCaptureEventV2.fromMap({...map, 'jpegSizeBytes': 1536},
            allowAssessedQuality: true),
        throwsFormatException);
  });

  test(
      'review quality round trip is typed and assessed event decoding is opt in',
      () {
    final quality = ARNativeCaptureQualityAssessmentV1.fromMap(_quality());
    expect(quality.toMap(), _quality());
    expect(quality.disposition,
        ARNativeCaptureQualityDispositionV1.reviewRequired);
    expect(
        () => ARNativeCaptureEventV2.fromMap(_event()), throwsFormatException);
    final event =
        ARNativeCaptureEventV2.fromMap(_event(), allowAssessedQuality: true);
    expect(event.qualityAssessment!.score, 58.6645);
    expect(event.qualityAssessment!.threshold, 110.0);
    expect(event.qualityAssessmentHash, 'a' * 64);
    expect(event.qualityAssessment!.toMap(), quality.toMap());
    final source = _quality();
    final held = ARNativeCaptureQualityAssessmentV1.fromMap(source);
    source['disposition'] = 'policyPassed';
    source['score'] = 999.0;
    expect(
        held.disposition, ARNativeCaptureQualityDispositionV1.reviewRequired);
    expect(held.score, 58.6645);
  });

  test(
      'unavailable evidence remains review required and passing policy requires complete evidence',
      () {
    final unavailable = _quality()
      ..['reason'] = 'analysisUnavailable'
      ..['score'] = null
      ..['sampleWidth'] = null
      ..['sampleHeight'] = null
      ..['unavailableReason'] = 'decodeFailed';
    final assessment = ARNativeCaptureQualityAssessmentV1.fromMap(unavailable);
    expect(assessment.disposition,
        ARNativeCaptureQualityDispositionV1.reviewRequired);
    expect(assessment.unavailableReason,
        ARNativeCaptureQualityUnavailableReasonV1.decodeFailed);
    final passed = _quality()
      ..['disposition'] = 'policyPassed'
      ..['reason'] = 'policyPassed'
      ..['score'] = 120.0;
    expect(ARNativeCaptureQualityAssessmentV1.fromMap(passed).disposition,
        ARNativeCaptureQualityDispositionV1.policyPassed);
    for (final bad in <Map<String, dynamic>>[
      {...passed, 'score': null},
      {...passed, 'score': 58.6645},
      {...passed, 'sampleHeight': null},
      {...passed, 'unavailableReason': 'decodeFailed'},
      {...unavailable, 'unavailableReason': null},
      {...unavailable, 'disposition': 'policyPassed'},
    ]) {
      expect(() => ARNativeCaptureQualityAssessmentV1.fromMap(bad),
          throwsFormatException);
    }
  });

  test(
      'new schema missing unknown malformed evidence and legacy hybrids fail closed',
      () {
    for (final bad in <Map<String, dynamic>>[
      {..._quality(), 'schemaVersion': 2},
      {..._quality(), 'schemaVersion': 1.0},
      {..._quality(), 'disposition': 'good'},
      {..._quality(), 'reason': 'unknown'},
      {..._quality(), 'score': double.nan},
      {..._quality(), 'threshold': double.infinity},
      {..._quality(), 'sampleWidth': 4097},
      {..._quality(), 'policyVersion': 'x' * 129},
      {..._quality(), 'policyVersion': 'line\nbreak'},
      {..._quality(), 'imageBytes': <int>[]},
      {..._quality()}..remove('score'),
    ]) {
      expect(() => ARNativeCaptureQualityAssessmentV1.fromMap(bad),
          throwsFormatException);
    }
    for (final bad in <Map<String, dynamic>>[
      {..._event()}..remove('qualityAssessment'),
      {..._event()}..remove('qualityAssessmentHash'),
      {..._event()}..remove('jpegSizeBytes'),
      {..._event(), 'qualityAssessmentHash': 'changed'},
      {..._event(), 'qualityDisposition': 'policyPassed'},
      {..._event(), 'wireVersion': 'native_capture_quality_v2'},
      {..._event(), 'wireVersion': nativeCaptureV2WireVersion},
      {..._event(), 'kind': 'accepted'},
    ]) {
      expect(
          () => ARNativeCaptureEventV2.fromMap(bad, allowAssessedQuality: true),
          throwsFormatException);
    }
    final legacy = _event()
      ..['wireVersion'] = nativeCaptureV2WireVersion
      ..remove('qualityDisposition')
      ..remove('qualityAssessmentHash')
      ..remove('qualityAssessment');
    final decoded = ARNativeCaptureEventV2.fromMap(legacy);
    expect(decoded.qualityAssessment, isNull);
    expect(decoded.qualityAssessmentHash, isNull);
    expect(decoded.jpegSizeBytes, 799);
  });

  test('durable admission replay propagates the same explicit quality gate',
      () {
    final result = <String, dynamic>{
      'wireVersion': nativeCaptureV2WireVersion,
      'attemptId': 'attempt',
      'phase': 'committed',
      'durable': true,
      'terminal': _event(),
    };
    expect(() => ARNativeCaptureAdmissionResultV2.fromMap(result),
        throwsFormatException);
    final decoded = ARNativeCaptureAdmissionResultV2.fromMap(result,
        allowAssessedQuality: true);
    expect(decoded.terminal!.qualityAssessment!.disposition,
        ARNativeCaptureQualityDispositionV1.reviewRequired);
    expect(decoded.terminal!.qualityAssessmentHash, 'a' * 64);
  });
}

Map<String, dynamic> _quality() => {
      'schemaVersion': 1,
      'policyVersion': 'jpeg-global-variance-v1',
      'disposition': 'reviewRequired',
      'reason': 'belowThreshold',
      'score': 58.6645,
      'threshold': 110.0,
      'sampleWidth': 256,
      'sampleHeight': 192,
      'unavailableReason': null,
    };

Map<String, dynamic> _event() => {
      'wireVersion': nativeCaptureQualityV1WireVersion,
      'kind': 'committed',
      'attemptId': 'attempt',
      'captureId': 'capture',
      'captureRevision': 1,
      'manifestId': 'b' * 64,
      'jpegSizeBytes': 799,
      'reason': 'original-retained',
      'qualityDisposition': 'reviewRequired',
      'qualityAssessmentHash': 'a' * 64,
      'qualityAssessment': _quality(),
      'recoveryContext': {
        'sessionId': 'session',
        'groupId': 'group',
        'groupIndex': 0,
        'groupGeneration': 1,
        'trigger': 'manual',
        'requestedAtMs': 1,
        'coverageRevision': 0,
        'timestampMs': 1,
        'position': [0.0, 0.0, 0.0],
        'rotation': [0.0, 0.0, 0.0, 1.0],
        'viewMatrix': _matrix(),
        'projectionMatrix': _matrix(),
        'groupFromWorld': _matrix(),
        'worldFromGroup': _matrix(),
      },
    };
List<double> _matrix() =>
    List.generate(16, (index) => index % 5 == 0 ? 1.0 : 0.0);
