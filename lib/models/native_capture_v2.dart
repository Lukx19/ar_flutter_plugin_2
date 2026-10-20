import 'frame_convention.dart';
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

  factory ARNativeCaptureAdmissionResultV2.fromMap(Map<String, dynamic> map,
      {bool allowAssessedQuality = false}) {
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
                  Map<String, dynamic>.from(terminal),
                  allowAssessedQuality: allowAssessedQuality)
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

/// Assessed events are opt-in until publication and credit isolation are integrated.
const nativeCaptureQualityV1WireVersion = 'native_capture_quality_v1';

enum ARNativeCaptureQualityDispositionV1 { policyPassed, reviewRequired }
enum ARNativeCaptureQualityReasonV1 {
  policyPassed,
  belowThreshold,
  analysisUnavailable,
}
enum ARNativeCaptureQualityUnavailableReasonV1 {
  decodeFailed,
  analysisFailed,
  evidenceMissing,
}

/// Immutable bounded scalar assessment; unavailable evidence cannot decode as passed.
final class ARNativeCaptureQualityAssessmentV1 {
  const ARNativeCaptureQualityAssessmentV1._({
    required this.schemaVersion,
    required this.policyVersion,
    required this.disposition,
    required this.reason,
    required this.score,
    required this.threshold,
    required this.sampleWidth,
    required this.sampleHeight,
    required this.unavailableReason,
  });

  factory ARNativeCaptureQualityAssessmentV1.fromMap(Map<String, dynamic> map) {
    _requireExactKeys(map, const {
      'schemaVersion', 'policyVersion', 'disposition', 'reason', 'score',
      'threshold', 'sampleWidth', 'sampleHeight', 'unavailableReason',
    });
    final schemaVersion = _integer(map, 'schemaVersion');
    final policyVersion = _nonEmptyString(map, 'policyVersion');
    if (schemaVersion != 1 || policyVersion.length > 128 ||
        !RegExp(r'^[A-Za-z0-9._:-]+$').hasMatch(policyVersion)) {
      throw const FormatException('Unsupported or malformed quality policy.');
    }
    T enumValue<T extends Enum>(Iterable<T> values, String name) {
      final value = values.where((value) => value.name == name).firstOrNull;
      if (value == null) throw FormatException('Unknown quality value: $name');
      return value;
    }
    double? metric(String key, {required bool positive}) {
      final value = map[key];
      if (value == null) return null;
      if (value is! num || !value.toDouble().isFinite ||
          (positive ? value <= 0 : value < 0)) {
        throw FormatException('$key must be a finite quality metric.');
      }
      return value.toDouble();
    }
    int? dimension(String key) {
      if (map[key] == null) return null;
      final value = _positiveInteger(map, key);
      if (value > 4096) throw FormatException('$key exceeds quality bounds.');
      return value;
    }
    final disposition = enumValue(ARNativeCaptureQualityDispositionV1.values,
        _string(map, 'disposition'));
    final reason = enumValue(ARNativeCaptureQualityReasonV1.values,
        _string(map, 'reason'));
    final score = metric('score', positive: false);
    final threshold = metric('threshold', positive: true);
    final sampleWidth = dimension('sampleWidth');
    final sampleHeight = dimension('sampleHeight');
    final unavailableReason = map['unavailableReason'] == null ? null :
        enumValue(ARNativeCaptureQualityUnavailableReasonV1.values,
            _string(map, 'unavailableReason'));
    if ((sampleWidth == null) != (sampleHeight == null)) {
      throw const FormatException('Quality sample dimensions must be paired.');
    }
    final complete = score != null && threshold != null &&
        sampleWidth != null && sampleHeight != null;
    final valid = switch (reason) {
      ARNativeCaptureQualityReasonV1.policyPassed =>
        disposition == ARNativeCaptureQualityDispositionV1.policyPassed &&
        unavailableReason == null && complete && score! >= threshold!,
      ARNativeCaptureQualityReasonV1.belowThreshold =>
        disposition == ARNativeCaptureQualityDispositionV1.reviewRequired &&
        unavailableReason == null && complete && score! < threshold!,
      ARNativeCaptureQualityReasonV1.analysisUnavailable =>
        disposition == ARNativeCaptureQualityDispositionV1.reviewRequired &&
        unavailableReason != null && score == null,
    };
    if (!valid) throw const FormatException('Quality disposition contradicts evidence.');
    return ARNativeCaptureQualityAssessmentV1._(
      schemaVersion: schemaVersion, policyVersion: policyVersion,
      disposition: disposition, reason: reason, score: score, threshold: threshold,
      sampleWidth: sampleWidth, sampleHeight: sampleHeight,
      unavailableReason: unavailableReason,
    );
  }

  final int schemaVersion;
  final String policyVersion;
  final ARNativeCaptureQualityDispositionV1 disposition;
  final ARNativeCaptureQualityReasonV1 reason;
  final double? score;
  final double? threshold;
  final int? sampleWidth;
  final int? sampleHeight;
  final ARNativeCaptureQualityUnavailableReasonV1? unavailableReason;

  Map<String, Object?> toMap() => {
    'schemaVersion': schemaVersion, 'policyVersion': policyVersion,
    'disposition': disposition.name, 'reason': reason.name,
    'score': score, 'threshold': threshold, 'sampleWidth': sampleWidth,
    'sampleHeight': sampleHeight, 'unavailableReason': unavailableReason?.name,
  };
}

final class ARNativeCaptureEventV2 {
  const ARNativeCaptureEventV2({
    required this.kind,
    this.attemptId,
    this.captureId,
    this.captureRevision,
    this.manifestId,
    this.jpegSizeBytes,
    this.dngSizeBytes,
    this.reason,
    this.health,
    this.recoveryContext,
    this.qualityAssessment,
    this.qualityAssessmentHash,
    this.shot,
  });

  factory ARNativeCaptureEventV2.fromMap(Map<String, dynamic> map,
      {bool allowAssessedQuality = false}) {
    final assessed = map['wireVersion'] == nativeCaptureQualityV1WireVersion;
    if (map['wireVersion'] != nativeCaptureV2WireVersion &&
        !(assessed && allowAssessedQuality)) {
      throw const FormatException('Unsupported native capture wire version.');
    }
    final kindName = _string(map, 'kind');
    final kind = ARNativeCaptureEventKindV2.values
        .where((value) => value.name == kindName)
        .firstOrNull;
    if (kind == null) {
      throw FormatException('Unknown native capture event: $kindName');
    }
    if (assessed && kind != ARNativeCaptureEventKindV2.committed) {
      throw const FormatException('Assessed wire requires a committed terminal.');
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
        _requireExactKeys(map, {
          'wireVersion',
          'kind',
          'attemptId',
          'captureId',
          'captureRevision',
          'manifestId',
          if (assessed || map.containsKey('jpegSizeBytes')) 'jpegSizeBytes',
          if (map.containsKey('dngSizeBytes')) 'dngSizeBytes',
          'reason',
          'recoveryContext',
          if (!assessed && map.containsKey('shot')) 'shot',
          if (assessed) 'qualityDisposition',
          if (assessed) 'qualityAssessmentHash',
          if (assessed) 'qualityAssessment',
        });
        ARNativeCaptureQualityAssessmentV1? qualityAssessment;
        String? qualityAssessmentHash;
        if (assessed) {
          final qualityMap = map['qualityAssessment'];
          if (qualityMap is! Map) {
            throw const FormatException('Quality assessment must be a map.');
          }
          qualityAssessment = ARNativeCaptureQualityAssessmentV1.fromMap(
              Map<String, dynamic>.from(qualityMap));
          qualityAssessmentHash = _nonEmptyString(map, 'qualityAssessmentHash');
          if (!RegExp(r'^[0-9a-f]{64}$').hasMatch(qualityAssessmentHash) ||
              _string(map, 'qualityDisposition') != qualityAssessment.disposition.name) {
            throw const FormatException('Quality assessment identity or disposition is invalid.');
          }
        }
        final jpegSizeBytes = map.containsKey('jpegSizeBytes')
            ? _positiveInteger(map, 'jpegSizeBytes')
            : null;
        final dngSizeBytes = map.containsKey('dngSizeBytes')
            ? _positiveInteger(map, 'dngSizeBytes')
            : null;
        if (dngSizeBytes != null && jpegSizeBytes == null) {
          throw const FormatException('DNG component requires its JPEG size.');
        }
        final shotValue = map['shot'];
        if (shotValue != null && shotValue is! Map) {
          throw const FormatException('Shot must be a map.');
        }
        final captureId = _nonEmptyString(map, 'captureId');
        final shot = shotValue == null
            ? null
            : ARNativeCaptureShotV2.fromMap(
                Map<String, dynamic>.from(shotValue as Map),
                captureId: captureId,
              );
        return ARNativeCaptureEventV2(
          kind: kind,
          attemptId: _nonEmptyString(map, 'attemptId'),
          captureId: captureId,
          shot: shot,
          captureRevision: _integer(map, 'captureRevision'),
          manifestId: _nonEmptyString(map, 'manifestId'),
          jpegSizeBytes: jpegSizeBytes,
          dngSizeBytes: dngSizeBytes,
          qualityAssessment: qualityAssessment,
          qualityAssessmentHash: qualityAssessmentHash,
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
  final int? jpegSizeBytes;
  final int? dngSizeBytes;
  final String? reason;
  final ARNativeCaptureHealthV2? health;
  final ARNativeCaptureRecoveryContextV2? recoveryContext;
  final ARNativeCaptureQualityAssessmentV1? qualityAssessment;
  final String? qualityAssessmentHash;

  /// The images after the first one of a committed iOS burst shot; null
  /// for a single-image commit (and always on Android).
  final ARNativeCaptureShotV2? shot;

  ARNativeCaptureQualityDispositionV1? get qualityDisposition => qualityAssessment?.disposition;

  bool get isTerminal =>
      kind == ARNativeCaptureEventKindV2.committed ||
      kind == ARNativeCaptureEventKindV2.abandoned;
}

/// One image after the first one of a committed iOS burst shot: its own
/// durable capture id (SHA-256 hex of `<first capture id>#<index>`, the id
/// the session manifest and the photo saved event use), JPEG size, exposure
/// time, size and, when the frame had them, its camera-to-world
/// (column-major, tagged [cameraToWorldConvention]) and fx, fy, cx, cy at the
/// image size.
final class ARNativeCaptureShotImageV2 {
  ARNativeCaptureShotImageV2({
    required this.index,
    required this.captureId,
    required this.jpegSizeBytes,
    required this.exposureTimestampNs,
    required this.width,
    required this.height,
    required this.trackingNormal,
    required this.flags,
    List<double>? cameraToWorld,
    this.cameraToWorldConvention = arkitCameraToWorldConvention,
    List<double>? intrinsics,
  })  : cameraToWorld =
            cameraToWorld == null ? null : List.unmodifiable(cameraToWorld),
        intrinsics = intrinsics == null ? null : List.unmodifiable(intrinsics);

  factory ARNativeCaptureShotImageV2.fromMap(Map<String, dynamic> map) {
    _requireExactKeys(map, {
      'index',
      'captureId',
      'jpegSizeBytes',
      'exposureTimestampNs',
      'width',
      'height',
      'trackingNormal',
      'flags',
      if (map.containsKey('cameraToWorld')) 'cameraToWorld',
      if (map.containsKey('cameraToWorldConvention')) 'cameraToWorldConvention',
      if (map.containsKey('intrinsics')) 'intrinsics',
    });
    List<double>? numbers(String key, int count) {
      if (!map.containsKey(key)) return null;
      final value = map[key];
      if (value is! List ||
          value.length != count ||
          value.any((item) => item is! num || !item.toDouble().isFinite)) {
        throw FormatException('$key must be $count finite numbers.');
      }
      return value.cast<num>().map((item) => item.toDouble()).toList();
    }

    final captureId = _nonEmptyString(map, 'captureId');
    final trackingNormal = map['trackingNormal'];
    final intrinsics = numbers('intrinsics', 4);
    if (!RegExp(r'^[0-9a-f]{64}$').hasMatch(captureId) ||
        trackingNormal is! bool ||
        (intrinsics != null && (intrinsics[0] <= 0 || intrinsics[1] <= 0))) {
      throw const FormatException('Malformed shot image.');
    }
    final flags = _integer(map, 'flags');
    if (flags > 3) throw const FormatException('Unknown shot image flags.');
    // Photo store layout 4 tags the pose; earlier plugins sent the ARKit
    // camera transform untagged.
    final convention =
        map['cameraToWorldConvention'] ?? arkitCameraToWorldConvention;
    if (convention != openCvCameraToWorldConvention &&
        !isArCameraConvention(convention as String?)) {
      throw const FormatException('Unknown shot image camera convention.');
    }
    return ARNativeCaptureShotImageV2(
      index: _positiveInteger(map, 'index'),
      captureId: captureId,
      jpegSizeBytes: _positiveInteger(map, 'jpegSizeBytes'),
      exposureTimestampNs: _integer(map, 'exposureTimestampNs'),
      width: _positiveInteger(map, 'width'),
      height: _positiveInteger(map, 'height'),
      trackingNormal: trackingNormal,
      flags: flags,
      cameraToWorld: numbers('cameraToWorld', 16),
      cameraToWorldConvention: convention as String,
      intrinsics: intrinsics,
    );
  }

  /// Flag bit: tracking was not normal at this image's frame.
  static const int flagTrackingNotNormal = 1;

  /// Flag bit: the high-resolution frame failed; the video frame was used.
  static const int flagVideoFrameFallback = 2;

  final int index;
  final String captureId;
  final int jpegSizeBytes;
  final int exposureTimestampNs;
  final int width;
  final int height;
  final bool trackingNormal;
  final int flags;
  final List<double>? cameraToWorld;

  /// Camera axes of [cameraToWorld]: `opencv_c2w_v1` from photo store layout
  /// 4, `arkit_c2w_v1` (untagged) before.
  final String cameraToWorldConvention;
  final List<double>? intrinsics;

  /// Photo credit and picture delivery may use this image: its frame was
  /// tracking normally and has a pose.
  bool get creditable => trackingNormal && cameraToWorld != null;
}

/// The images after the first one of a committed iOS burst shot. The first
/// image is the terminal's own capture.
final class ARNativeCaptureShotV2 {
  ARNativeCaptureShotV2({
    required this.shotId,
    required this.mode,
    required this.imageCount,
    required this.firstExposureTimestampNs,
    required List<ARNativeCaptureShotImageV2> images,
  }) : images = List.unmodifiable(images);

  /// Decodes the `shot` map of the committed terminal whose first image is
  /// [captureId]: image indices are 1 to [imageCount] - 1 at most, strictly
  /// increasing, and each capture id differs from the first one's.
  factory ARNativeCaptureShotV2.fromMap(
    Map<String, dynamic> map, {
    required String captureId,
  }) {
    _requireExactKeys(map, const {
      'shotId',
      'mode',
      'imageCount',
      'firstExposureTimestampNs',
      'images',
    });
    final shotId = _nonEmptyString(map, 'shotId');
    final firstExposureTimestampNs = _integer(map, 'firstExposureTimestampNs');
    final mode = _nonEmptyString(map, 'mode');
    final imageCount = _positiveInteger(map, 'imageCount');
    final list = map['images'];
    if (list is! List ||
        list.isEmpty ||
        list.any((item) => item is! Map) ||
        !RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
            .hasMatch(shotId) ||
        mode != 'burst' ||
        imageCount > 5 ||
        list.length != imageCount - 1) {
      throw const FormatException('Malformed shot.');
    }
    final images = [
      for (final item in list)
        ARNativeCaptureShotImageV2.fromMap(
          Map<String, dynamic>.from(item as Map),
        ),
    ];
    for (var i = 0; i < images.length; i++) {
      final image = images[i];
      if (image.index >= imageCount ||
          (i > 0 && image.index <= images[i - 1].index) ||
          image.captureId == captureId ||
          images.take(i).any((other) => other.captureId == image.captureId)) {
        throw const FormatException('Shot images are not consistent.');
      }
    }
    return ARNativeCaptureShotV2(
      shotId: shotId,
      mode: mode,
      imageCount: imageCount,
      firstExposureTimestampNs: firstExposureTimestampNs,
      images: images,
    );
  }

  final String shotId;
  final String mode;

  /// Images of the shot including the first one.
  final int imageCount;

  /// Exposure time of the first image, on the clock of every image's
  /// [ARNativeCaptureShotImageV2.exposureTimestampNs].
  final int firstExposureTimestampNs;
  final List<ARNativeCaptureShotImageV2> images;
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

int _positiveInteger(Map<String, dynamic> map, String key) {
  final value = _integer(map, key);
  if (value == 0) {
    throw FormatException('$key must be a positive integer.');
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
