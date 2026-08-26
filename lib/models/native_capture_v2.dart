import 'capture_intent_contract.dart';

const nativeCaptureV2WireVersion = 'native_capture_v2';

/// Immutable accepted-before-shutter fields. Component descriptors are derived
/// by the native store after Camera2 output and can never be supplied by Dart.
final class ARNativeCaptureAdmissionV2 {
  ARNativeCaptureAdmissionV2({
    required this.accepted,
    required List<int> poseRecordHash,
    required List<int> cameraModelHash,
    required List<int> validationRecordHash,
    required List<int> ledgerRecordHash,
  })  : poseRecordHash = List<int>.unmodifiable(poseRecordHash),
        cameraModelHash = List<int>.unmodifiable(cameraModelHash),
        validationRecordHash = List<int>.unmodifiable(validationRecordHash),
        ledgerRecordHash = List<int>.unmodifiable(ledgerRecordHash) {
    for (final digest in [
      this.poseRecordHash,
      this.cameraModelHash,
      this.validationRecordHash,
      this.ledgerRecordHash,
    ]) {
      if (digest.length != 32 || digest.any((byte) => byte < 0 || byte > 255)) {
        throw ArgumentError('Native capture hashes must be 32-byte digests.');
      }
    }
    final kinds = accepted.profile.requiredComponents;
    if (kinds.length > 2 ||
        !kinds.contains(CaptureComponentKind.jpeg) ||
        kinds.any((kind) =>
            kind != CaptureComponentKind.jpeg &&
            kind != CaptureComponentKind.dng)) {
      throw ArgumentError('V2 supports JPEG or JPEG+DNG only.');
    }
    if (!accepted.reservation.physicallyBacked) {
      throw ArgumentError(
          'V2 admission requires a physically-backed reservation.');
    }
    final reservation = accepted.reservation;
    final profile = accepted.profile;
    if (reservation.memoryBytes < profile.maximumWorkingBytes ||
        reservation.physicalStoreBytes < profile.maximumComponentBytes ||
        reservation.componentEntries != kinds.length ||
        reservation.terminalEntries != 1) {
      throw ArgumentError(
        'V2 admission liabilities must completely fund working memory, '
        'component bytes/entries, and one terminal.',
      );
    }
  }

  final CaptureAcceptedAttempt accepted;
  final List<int> poseRecordHash;
  final List<int> cameraModelHash;
  final List<int> validationRecordHash;
  final List<int> ledgerRecordHash;

  Map<String, Object?> toMap() => {
        'wireVersion': nativeCaptureV2WireVersion,
        'accepted': _acceptedToMap(accepted),
        'poseRecordHash': poseRecordHash,
        'cameraModelHash': cameraModelHash,
        'validationRecordHash': validationRecordHash,
        'ledgerRecordHash': ledgerRecordHash,
      };
}

enum ARNativeCaptureEventKindV2 {
  accepted,
  finalizing,
  recovering,
  committed,
  abandoned,
  health,
}

final class ARNativeCaptureAdmissionResultV2 {
  const ARNativeCaptureAdmissionResultV2({
    required this.attemptId,
    required this.phase,
    required this.durable,
    this.terminal,
  });

  factory ARNativeCaptureAdmissionResultV2.fromMap(Map<String, dynamic> map) {
    _requireExactKeys(map, const {
      'wireVersion',
      'attemptId',
      'phase',
      'durable',
      'terminal',
    });
    if (map['wireVersion'] != nativeCaptureV2WireVersion ||
        map['attemptId'] is! String ||
        (map['attemptId'] as String).isEmpty ||
        map['phase'] is! String ||
        map['durable'] is! bool) {
      throw const FormatException('Malformed native capture admission result.');
    }
    final terminal = map['terminal'];
    return ARNativeCaptureAdmissionResultV2(
      attemptId: map['attemptId'] as String,
      phase: map['phase'] as String,
      durable: map['durable'] as bool,
      terminal: terminal == null
          ? null
          : terminal is Map
              ? ARNativeCaptureEventV2.fromMap(
                  Map<String, dynamic>.from(terminal))
              : throw const FormatException(
                  'Native capture terminal must be a map.'),
    );
  }

  final String attemptId;
  final String phase;
  final bool durable;
  final ARNativeCaptureEventV2? terminal;
}

final class ARNativeCaptureHealthV2 {
  const ARNativeCaptureHealthV2({
    required this.exposures,
    required this.lateCallbacks,
    required this.closedComponents,
    required this.committed,
    required this.abandoned,
    required this.unknownQueries,
    required this.running,
    required this.fundedWaiting,
    required this.retainedImageBytes,
    required this.readyPictureSets,
  });

  factory ARNativeCaptureHealthV2.fromMap(Map<String, dynamic> map) {
    _requireExactKeys(map, const {
      'exposures',
      'lateCallbacks',
      'closedComponents',
      'committed',
      'abandoned',
      'unknownQueries',
      'running',
      'fundedWaiting',
      'retainedImageBytes',
      'readyPictureSets',
    });
    return ARNativeCaptureHealthV2(
      exposures: _integer(map, 'exposures'),
      lateCallbacks: _integer(map, 'lateCallbacks'),
      closedComponents: _integer(map, 'closedComponents'),
      committed: _integer(map, 'committed'),
      abandoned: _integer(map, 'abandoned'),
      unknownQueries: _integer(map, 'unknownQueries'),
      running: _integer(map, 'running'),
      fundedWaiting: _integer(map, 'fundedWaiting'),
      retainedImageBytes: _integer(map, 'retainedImageBytes'),
      readyPictureSets: _integer(map, 'readyPictureSets'),
    );
  }

  final int exposures;
  final int lateCallbacks;
  final int closedComponents;
  final int committed;
  final int abandoned;
  final int unknownQueries;
  final int running;
  final int fundedWaiting;
  final int retainedImageBytes;
  final int readyPictureSets;
}

final class ARNativeCaptureEventV2 {
  const ARNativeCaptureEventV2({
    required this.kind,
    this.attemptId,
    this.captureId,
    this.captureRevision,
    this.manifestId,
    this.reason,
    this.health,
  });

  factory ARNativeCaptureEventV2.fromMap(Map<String, dynamic> map) {
    if (map['wireVersion'] != nativeCaptureV2WireVersion) {
      throw const FormatException('Unsupported native capture wire version.');
    }
    final kindName = _string(map, 'kind');
    final kind = ARNativeCaptureEventKindV2.values
        .where((value) => value.name == kindName)
        .firstOrNull;
    if (kind == null) {
      throw FormatException('Unknown native capture event: $kindName');
    }
    switch (kind) {
      case ARNativeCaptureEventKindV2.accepted:
      case ARNativeCaptureEventKindV2.finalizing:
        _requireExactKeys(map, const {'wireVersion', 'kind', 'attemptId'});
        return ARNativeCaptureEventV2(
          kind: kind,
          attemptId: _nonEmptyString(map, 'attemptId'),
        );
      case ARNativeCaptureEventKindV2.recovering:
        _requireExactKeys(
          map,
          const {'wireVersion', 'kind', 'attemptId', 'reason'},
        );
        return ARNativeCaptureEventV2(
          kind: kind,
          attemptId: _nonEmptyString(map, 'attemptId'),
          reason: _nonEmptyString(map, 'reason'),
        );
      case ARNativeCaptureEventKindV2.committed:
        _requireExactKeys(map, const {
          'wireVersion',
          'kind',
          'attemptId',
          'captureId',
          'captureRevision',
          'manifestId',
          'reason',
        });
        return ARNativeCaptureEventV2(
          kind: kind,
          attemptId: _nonEmptyString(map, 'attemptId'),
          captureId: _nonEmptyString(map, 'captureId'),
          captureRevision: _integer(map, 'captureRevision'),
          manifestId: _nonEmptyString(map, 'manifestId'),
          reason: _nonEmptyString(map, 'reason'),
        );
      case ARNativeCaptureEventKindV2.abandoned:
        _requireExactKeys(
          map,
          const {'wireVersion', 'kind', 'attemptId', 'reason'},
        );
        return ARNativeCaptureEventV2(
          kind: kind,
          attemptId: _nonEmptyString(map, 'attemptId'),
          reason: _nonEmptyString(map, 'reason'),
        );
      case ARNativeCaptureEventKindV2.health:
        _requireExactKeys(map, const {'wireVersion', 'kind', 'health'});
        final healthValue = map['health'];
        if (healthValue is! Map) {
          throw const FormatException('Health must be a map.');
        }
        return ARNativeCaptureEventV2(
          kind: kind,
          health: ARNativeCaptureHealthV2.fromMap(
            Map<String, dynamic>.from(healthValue),
          ),
        );
    }
  }

  final ARNativeCaptureEventKindV2 kind;
  final String? attemptId;
  final String? captureId;
  final int? captureRevision;
  final String? manifestId;
  final String? reason;
  final ARNativeCaptureHealthV2? health;

  bool get isTerminal =>
      kind == ARNativeCaptureEventKindV2.committed ||
      kind == ARNativeCaptureEventKindV2.abandoned;
}

Map<String, Object?> _acceptedToMap(CaptureAcceptedAttempt value) => {
      'identity': {
        'attemptId': value.identity.attemptId,
        'commitId': value.identity.commitId,
        'attemptOrdinal': value.identity.attemptOrdinal,
        'lifecycleCut': _cutToMap(value.identity.lifecycleCut),
      },
      'lane': value.lane.name,
      'profile': {
        'profileId': value.profile.profileId,
        'requiredComponents':
            value.profile.requiredComponents.map((value) => value.name).toList()
              ..sort(),
        'maximumComponentBytes': value.profile.maximumComponentBytes,
        'maximumWorkingBytes': value.profile.maximumWorkingBytes,
      },
      'reservation': {
        'memoryBytes': value.reservation.memoryBytes,
        'physicalStoreBytes': value.reservation.physicalStoreBytes,
        'componentEntries': value.reservation.componentEntries,
        'terminalEntries': value.reservation.terminalEntries,
        'rollbackBytes': value.reservation.rollbackBytes,
        'physicallyBacked': value.reservation.physicallyBacked,
      },
      'canonicalIntentHash': value.canonicalIntentHash,
      'acceptedReceiptHash': value.acceptedReceiptHash,
    };

Map<String, Object?> _cutToMap(CaptureLifecycleCut value) => {
      'sessionId': value.sessionId,
      'sessionGeneration': value.sessionGeneration,
      'groupId': value.groupId,
      'groupGeneration': value.groupGeneration,
      'arSessionId': value.arSessionId,
      'viewId': value.viewId,
      'viewGeneration': value.viewGeneration,
      'bindingToken': value.bindingToken,
      'lifecycleSequence': value.lifecycleSequence,
      'operationGeneration': value.operationGeneration,
    };

int _integer(Map<String, dynamic> map, String key) {
  final value = map[key];
  if (value is! int || value < 0) {
    throw FormatException('$key must be a non-negative integer.');
  }
  return value;
}

void _requireExactKeys(Map<String, dynamic> map, Set<String> expected) {
  if (map.keys.toSet().difference(expected).isNotEmpty ||
      expected.difference(map.keys.toSet()).isNotEmpty) {
    throw const FormatException('Native capture payload keys are not exact.');
  }
}

String _string(Map<String, dynamic> map, String key) {
  final value = map[key];
  if (value is! String) throw FormatException('$key must be a string.');
  return value;
}

String _nonEmptyString(Map<String, dynamic> map, String key) {
  final value = _string(map, key);
  if (value.isEmpty) throw FormatException('$key must not be empty.');
  return value;
}
