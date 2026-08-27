import 'capture_intent_contract.dart';

const nativeCaptureV2WireVersion = 'native_capture_v2';

/// Canonical conservative liabilities for native durable capture records.
/// These are contract bounds, not estimates of a particular filesystem's
/// allocation unit. Checked addition keeps malformed profiles fail-closed.
abstract final class NativeCaptureReservationBoundsV2 {
  static const int acceptedRecordBytes = 64 * 1024;
  static const int componentDescriptorBytes = 16 * 1024;
  static const int selectedRootBytes = 64 * 1024;
  static const int receiptBytes = 32 * 1024;
  static const int alternatingPointerBytes = 32 * 1024;
  static const int directorySyncBytes = 32 * 1024;
  static const int terminalMetadataBytes = 32 * 1024;
  static const int coexistenceBytes = 1024 * 1024;
  static const int rollbackMetadataBytes = 256 * 1024;

  static int physicalBytes(int componentBytes, int componentCount) {
    var value = componentBytes;
    for (final liability in <int>[
      acceptedRecordBytes,
      _checkedMultiply(componentDescriptorBytes, componentCount),
      selectedRootBytes,
      receiptBytes,
      alternatingPointerBytes,
      directorySyncBytes,
      terminalMetadataBytes,
      coexistenceBytes,
    ]) {
      value = _checkedReservationAdd(value, liability);
    }
    return value;
  }

  static int rollbackBytes(int componentBytes) => _checkedReservationAdd(
        componentBytes,
        rollbackMetadataBytes,
      );
}

/// Immutable accepted-before-shutter fields. Component descriptors are derived
/// by the native store after Camera2 output and can never be supplied by Dart.
final class ARNativeCaptureAdmissionV2 {
  ARNativeCaptureAdmissionV2({
    required this.accepted,
    required List<int> poseRecordHash,
    required List<int> cameraModelHash,
    required List<int> validationRecordHash,
    required List<int> ledgerRecordHash,
    required this.recoveryContext,
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
        reservation.physicalStoreBytes <
            NativeCaptureReservationBoundsV2.physicalBytes(
              profile.maximumComponentBytes,
              kinds.length,
            ) ||
        reservation.rollbackBytes <
            NativeCaptureReservationBoundsV2.rollbackBytes(
              profile.maximumComponentBytes,
            ) ||
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
  final ARNativeCaptureRecoveryContextV2 recoveryContext;

  Map<String, Object?> toMap() => {
        'wireVersion': nativeCaptureV2WireVersion,
        'accepted': _acceptedToMap(accepted),
        'poseRecordHash': poseRecordHash,
        'cameraModelHash': cameraModelHash,
        'validationRecordHash': validationRecordHash,
        'ledgerRecordHash': ledgerRecordHash,
        'recoveryContext': recoveryContext.toMap(),
      };
}

final class ARNativeCaptureRecoveryContextV2 {
  ARNativeCaptureRecoveryContextV2({
    required this.sessionId,
    required this.groupId,
    required this.groupIndex,
    required this.groupGeneration,
    required this.trigger,
    required this.requestedAtMs,
    required this.coverageRevision,
    required this.timestampMs,
    required List<double> position,
    required List<double> rotation,
    required List<double> viewMatrix,
    required List<double> projectionMatrix,
    required List<double> groupFromWorld,
    required List<double> worldFromGroup,
  })  : position = List.unmodifiable(position),
        rotation = List.unmodifiable(rotation),
        viewMatrix = List.unmodifiable(viewMatrix),
        projectionMatrix = List.unmodifiable(projectionMatrix),
        groupFromWorld = List.unmodifiable(groupFromWorld),
        worldFromGroup = List.unmodifiable(worldFromGroup) {
    if (sessionId.isEmpty ||
        groupId.isEmpty ||
        trigger.isEmpty ||
        groupIndex < 0 ||
        groupGeneration < 0 ||
        coverageRevision < 0 ||
        requestedAtMs < 0 ||
        timestampMs < 0 ||
        this.position.length != 3 ||
        this.rotation.length != 4 ||
        this.viewMatrix.length != 16 ||
        this.projectionMatrix.length != 16 ||
        this.groupFromWorld.length != 16 ||
        this.worldFromGroup.length != 16 ||
        [
          this.position,
          this.rotation,
          this.viewMatrix,
          this.projectionMatrix,
          this.groupFromWorld,
          this.worldFromGroup
        ].expand((value) => value).any((value) => !value.isFinite)) {
      throw ArgumentError('Malformed native recovery product context.');
    }
  }

  factory ARNativeCaptureRecoveryContextV2.fromMap(Map<String, dynamic> map) {
    const keys = {
      'sessionId',
      'groupId',
      'groupIndex',
      'groupGeneration',
      'trigger',
      'requestedAtMs',
      'coverageRevision',
      'timestampMs',
      'position',
      'rotation',
      'viewMatrix',
      'projectionMatrix',
      'groupFromWorld',
      'worldFromGroup'
    };
    _requireExactKeys(map, keys);
    List<double> values(String key) {
      final value = map[key];
      if (value is! List || value.any((item) => item is! num))
        throw FormatException('$key must be numeric scalars.');
      return value.cast<num>().map((item) => item.toDouble()).toList();
    }

    return ARNativeCaptureRecoveryContextV2(
      sessionId: _nonEmptyString(map, 'sessionId'),
      groupId: _nonEmptyString(map, 'groupId'),
      groupIndex: _integer(map, 'groupIndex'),
      groupGeneration: _integer(map, 'groupGeneration'),
      trigger: _nonEmptyString(map, 'trigger'),
      requestedAtMs: _integer(map, 'requestedAtMs'),
      coverageRevision: _integer(map, 'coverageRevision'),
      timestampMs: _integer(map, 'timestampMs'),
      position: values('position'),
      rotation: values('rotation'),
      viewMatrix: values('viewMatrix'),
      projectionMatrix: values('projectionMatrix'),
      groupFromWorld: values('groupFromWorld'),
      worldFromGroup: values('worldFromGroup'),
    );
  }

  final String sessionId, groupId, trigger;
  final int groupIndex,
      groupGeneration,
      requestedAtMs,
      coverageRevision,
      timestampMs;
  final List<double> position,
      rotation,
      viewMatrix,
      projectionMatrix,
      groupFromWorld,
      worldFromGroup;
  Map<String, Object?> toMap() => {
        'sessionId': sessionId,
        'groupId': groupId,
        'groupIndex': groupIndex,
        'groupGeneration': groupGeneration,
        'trigger': trigger,
        'requestedAtMs': requestedAtMs,
        'coverageRevision': coverageRevision,
        'timestampMs': timestampMs,
        'position': position,
        'rotation': rotation,
        'viewMatrix': viewMatrix,
        'projectionMatrix': projectionMatrix,
        'groupFromWorld': groupFromWorld,
        'worldFromGroup': worldFromGroup
      };
}

int _checkedReservationAdd(int left, int right) {
  final result = left + right;
  if (left < 0 || right < 0 || result > 0x7fffffffffffffff) {
    throw ArgumentError('Native capture reservation arithmetic overflow.');
  }
  return result;
}

int _checkedMultiply(int left, int right) {
  if (left < 0 ||
      right < 0 ||
      (right != 0 && left > 0x7fffffffffffffff ~/ right)) {
    throw ArgumentError('Native capture reservation arithmetic overflow.');
  }
  return left * right;
}

enum ARNativeCaptureEventKindV2 {
  accepted,
  finalizing,
  recovering,
  recoveryFailed,
  ready,
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

  /// Number of unique exposed attempts whose durable outcome is still unknown.
  /// Re-querying one attempt does not increase this liability.
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
    this.recoveryContext,
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
          const {
            'wireVersion',
            'kind',
            'attemptId',
            'reason',
            'recoveryContext'
          },
        );
        return ARNativeCaptureEventV2(
          kind: kind,
          attemptId: _nonEmptyString(map, 'attemptId'),
          reason: _nonEmptyString(map, 'reason'),
          recoveryContext: ARNativeCaptureRecoveryContextV2.fromMap(
              Map<String, dynamic>.from(map['recoveryContext'] as Map)),
        );
      case ARNativeCaptureEventKindV2.recoveryFailed:
        _requireExactKeys(map, const {'wireVersion', 'kind', 'reason'});
        return ARNativeCaptureEventV2(
          kind: kind,
          reason: _nonEmptyString(map, 'reason'),
        );
      case ARNativeCaptureEventKindV2.ready:
        _requireExactKeys(map, const {'wireVersion', 'kind', 'reason'});
        return ARNativeCaptureEventV2(
            kind: kind, reason: _nonEmptyString(map, 'reason'));
      case ARNativeCaptureEventKindV2.committed:
        _requireExactKeys(map, const {
          'wireVersion',
          'kind',
          'attemptId',
          'captureId',
          'captureRevision',
          'manifestId',
          'reason',
          'recoveryContext',
        });
        return ARNativeCaptureEventV2(
          kind: kind,
          attemptId: _nonEmptyString(map, 'attemptId'),
          captureId: _nonEmptyString(map, 'captureId'),
          captureRevision: _integer(map, 'captureRevision'),
          manifestId: _nonEmptyString(map, 'manifestId'),
          reason: _nonEmptyString(map, 'reason'),
          recoveryContext: ARNativeCaptureRecoveryContextV2.fromMap(
              Map<String, dynamic>.from(map['recoveryContext'] as Map)),
        );
      case ARNativeCaptureEventKindV2.abandoned:
        _requireExactKeys(
          map,
          const {
            'wireVersion',
            'kind',
            'attemptId',
            'reason',
            'recoveryContext'
          },
        );
        return ARNativeCaptureEventV2(
          kind: kind,
          attemptId: _nonEmptyString(map, 'attemptId'),
          reason: _nonEmptyString(map, 'reason'),
          recoveryContext: ARNativeCaptureRecoveryContextV2.fromMap(
              Map<String, dynamic>.from(map['recoveryContext'] as Map)),
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
  final ARNativeCaptureRecoveryContextV2? recoveryContext;

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
