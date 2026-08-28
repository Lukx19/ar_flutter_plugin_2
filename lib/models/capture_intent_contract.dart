import 'dart:collection';

/// Shared deep value semantics for immutable contract DTOs.
mixin CaptureValueEquality {
  List<Object?> get equalityFields;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other.runtimeType == runtimeType &&
          other is CaptureValueEquality &&
          _deepEquals(equalityFields, other.equalityFields);

  @override
  int get hashCode => Object.hash(runtimeType, _deepHash(equalityFields));
}

/// Largest portable ordinal. Capture ordinals never wrap or reuse a value.
const int capturePortableOrdinalMaximum = 0x7fffffffffffffff;
const int capturePortableEntryMaximum = 0x7fffffff;

/// Fixed coexistence ceiling for one protected manual and one automatic attempt.
const int captureCoexistenceBytes = 128 * 1024 * 1024;

enum CaptureLane { manual, automatic }

enum CaptureIntentState {
  manualReady,
  manualWaitingDurability,
  automaticDisabledByUser,
  automaticReady,
  automaticWaitingSelector,
  automaticWaitingDurability,
  automaticWaitingCaptureHealth,
  automaticRecovering,
}

enum CaptureAttemptPhase {
  reservedAccepted,
  exposureRequested,
  sensorOutputOwned,
  validated,
  durablePrepared,
  committedPicture,
  abandonedAttempt,
}

enum CaptureComponentKind { jpeg, dng, raw, hdr, sidecar }

enum CaptureTerminalKind { committedPicture, abandonedAttempt }

enum CaptureTransitionDisposition { applied, exactReplay, rejected, conflict }

enum CaptureFault {
  timeout,
  cancellation,
  malformedOutput,
  componentFailure,
  storageFailure,
  isolateLoss,
  processLoss,
  lateCallback,
  reservationOverflow,
}

enum CaptureLifecycleEvent {
  automaticDisabled,
  routeLeft,
  viewReplaced,
  arSessionReplaced,
  backgrounded,
  processRestarted,
}

/// Complete immutable identity cut qualifying capture work and late callbacks.
final class CaptureLifecycleCut with CaptureValueEquality {
  CaptureLifecycleCut({
    required this.sessionId,
    required this.sessionGeneration,
    required this.groupId,
    required this.groupGeneration,
    required this.arSessionId,
    required this.viewId,
    required this.viewGeneration,
    required this.bindingToken,
    required this.lifecycleSequence,
    required this.operationGeneration,
  }) {
    for (final value in [
      sessionGeneration,
      groupGeneration,
      viewGeneration,
      lifecycleSequence,
      operationGeneration
    ]) {
      _requirePortableNonNegative(value, 'lifecycle generation/sequence');
    }
    if ([sessionId, groupId, arSessionId, viewId, bindingToken]
        .any((value) => value.isEmpty)) {
      throw ArgumentError('Lifecycle identities must be non-empty.');
    }
  }

  final String sessionId;
  final int sessionGeneration;
  final String groupId;
  final int groupGeneration;
  final String arSessionId;
  final String viewId;
  final int viewGeneration;
  final String bindingToken;
  final int lifecycleSequence;
  final int operationGeneration;

  String get canonicalKey => <Object>[
        sessionId,
        sessionGeneration,
        groupId,
        groupGeneration,
        arSessionId,
        viewId,
        viewGeneration,
        bindingToken,
        lifecycleSequence,
        operationGeneration,
      ].join('|');

  @override
  List<Object?> get equalityFields => [
        sessionId,
        sessionGeneration,
        groupId,
        groupGeneration,
        arSessionId,
        viewId,
        viewGeneration,
        bindingToken,
        lifecycleSequence,
        operationGeneration
      ];
}

/// A user or selector-originated request before any durable identity exists.
final class CaptureIntent with CaptureValueEquality {
  CaptureIntent({
    required this.lane,
    required this.lifecycleCut,
    required this.profile,
    required this.selectorValid,
    required this.trackingValid,
    required this.captureHealthy,
    required this.durabilityPreflightValid,
    required List<int> canonicalIntentHash,
  }) : canonicalIntentHash =
            _copyDigest(canonicalIntentHash, 'canonicalIntentHash');

  final CaptureLane lane;
  final CaptureLifecycleCut lifecycleCut;
  final CaptureComponentProfile profile;
  final bool selectorValid;
  final bool trackingValid;
  final bool captureHealthy;
  final bool durabilityPreflightValid;
  final List<int> canonicalIntentHash;

  @override
  List<Object?> get equalityFields => [
        lane,
        lifecycleCut,
        profile,
        selectorValid,
        trackingValid,
        captureHealthy,
        durabilityPreflightValid,
        canonicalIntentHash
      ];
}

/// Fixed component composition selected before admission.
final class CaptureComponentProfile with CaptureValueEquality {
  CaptureComponentProfile({
    required this.profileId,
    required Set<CaptureComponentKind> requiredComponents,
    required this.maximumComponentBytes,
    required this.maximumWorkingBytes,
  }) : requiredComponents = UnmodifiableSetView(Set.of(requiredComponents)) {
    if (profileId.isEmpty ||
        requiredComponents.isEmpty ||
        maximumComponentBytes <= 0 ||
        maximumWorkingBytes <= 0) {
      throw ArgumentError(
          'A capture profile must have positive, bounded components and working bytes.');
    }
    _requirePortableNonNegative(maximumComponentBytes, 'maximumComponentBytes');
    _requirePortableNonNegative(maximumWorkingBytes, 'maximumWorkingBytes');
  }

  final String profileId;
  final Set<CaptureComponentKind> requiredComponents;
  final int maximumComponentBytes;
  final int maximumWorkingBytes;

  String get canonicalKey =>
      '$profileId:${requiredComponents.map((e) => e.name).toList()..sort()}:$maximumComponentBytes:$maximumWorkingBytes';

  @override
  List<Object?> get equalityFields => [
        profileId,
        requiredComponents,
        maximumComponentBytes,
        maximumWorkingBytes
      ];
}

/// Complete pre-exposure RAM and physical-store liability.
final class CaptureReservationLiability with CaptureValueEquality {
  CaptureReservationLiability({
    required this.memoryBytes,
    required this.physicalStoreBytes,
    required this.componentEntries,
    required this.terminalEntries,
    required this.rollbackBytes,
    required this.physicallyBacked,
  }) {
    for (final value in [memoryBytes, physicalStoreBytes, rollbackBytes]) {
      _requirePortableNonNegative(value, 'reservation value');
    }
    _requirePortableEntry(componentEntries, 'componentEntries');
    _requirePortableEntry(terminalEntries, 'terminalEntries');
    totalStoreLiability;
  }

  final int memoryBytes;
  final int physicalStoreBytes;
  final int componentEntries;
  final int terminalEntries;
  final int rollbackBytes;
  final bool physicallyBacked;

  int get totalStoreLiability => _checkedAdd(physicalStoreBytes, rollbackBytes);

  @override
  List<Object?> get equalityFields => [
        memoryBytes,
        physicalStoreBytes,
        componentEntries,
        terminalEntries,
        rollbackBytes,
        physicallyBacked
      ];
}

/// Stable identity allocated only after every preflight gate passes.
final class CaptureAttemptIdentity with CaptureValueEquality {
  CaptureAttemptIdentity({
    required this.attemptId,
    required this.commitId,
    required this.attemptOrdinal,
    required this.lifecycleCut,
  }) {
    if (attemptId.isEmpty || commitId.isEmpty || attemptOrdinal < 1) {
      throw ArgumentError('Attempt identity fields are invalid.');
    }
    _requirePortableNonNegative(attemptOrdinal, 'attemptOrdinal');
  }

  final String attemptId;
  final String commitId;
  final int attemptOrdinal;
  final CaptureLifecycleCut lifecycleCut;

  String get canonicalKey =>
      '$attemptId|$commitId|$attemptOrdinal|${lifecycleCut.canonicalKey}';

  @override
  List<Object?> get equalityFields =>
      [attemptId, commitId, attemptOrdinal, lifecycleCut];
}

/// Durable accepted record which must exist before shutter operation.
final class CaptureAcceptedAttempt with CaptureValueEquality {
  CaptureAcceptedAttempt({
    required this.identity,
    required this.lane,
    required this.profile,
    required this.reservation,
    required List<int> canonicalIntentHash,
    required List<int> acceptedReceiptHash,
  })  : canonicalIntentHash =
            _copyDigest(canonicalIntentHash, 'canonicalIntentHash'),
        acceptedReceiptHash =
            _copyDigest(acceptedReceiptHash, 'acceptedReceiptHash');

  final CaptureAttemptIdentity identity;
  final CaptureLane lane;
  final CaptureComponentProfile profile;
  final CaptureReservationLiability reservation;
  final List<int> canonicalIntentHash;
  final List<int> acceptedReceiptHash;

  @override
  List<Object?> get equalityFields => [
        identity,
        lane,
        profile,
        reservation,
        canonicalIntentHash,
        acceptedReceiptHash
      ];
}

/// Metadata for one streamed, attempt-owned component. It carries no bytes.
final class CaptureComponentDescriptor with CaptureValueEquality {
  CaptureComponentDescriptor({
    required this.kind,
    required this.byteLength,
    required List<int> sha256,
    required this.durableObjectId,
  }) : sha256 = _copyDigest(sha256, 'sha256') {
    _requirePortableNonNegative(byteLength, 'component byteLength');
    if (durableObjectId.isEmpty)
      throw ArgumentError('durableObjectId must be non-empty');
  }

  final CaptureComponentKind kind;
  final int byteLength;
  final List<int> sha256;
  final String durableObjectId;

  String get canonicalKey =>
      '${kind.name}|$byteLength|$sha256|$durableObjectId';

  @override
  List<Object?> get equalityFields =>
      [kind, byteLength, sha256, durableObjectId];
}

/// Complete typed native-to-store commit input after validation.
final class CaptureCommitRequest with CaptureValueEquality {
  CaptureCommitRequest({
    required this.accepted,
    required Iterable<CaptureComponentDescriptor> components,
    required this.exposureTimestampNanoseconds,
    required List<int> poseRecordHash,
    required List<int> cameraModelHash,
    required List<int> validationRecordHash,
    required List<int> ledgerRecordHash,
  })  : components = List.unmodifiable(components),
        poseRecordHash = _copyDigest(poseRecordHash, 'poseRecordHash'),
        cameraModelHash = _copyDigest(cameraModelHash, 'cameraModelHash'),
        validationRecordHash =
            _copyDigest(validationRecordHash, 'validationRecordHash'),
        ledgerRecordHash = _copyDigest(ledgerRecordHash, 'ledgerRecordHash') {
    _requirePortableNonNegative(
        exposureTimestampNanoseconds, 'exposureTimestampNanoseconds');
  }

  final CaptureAcceptedAttempt accepted;
  final List<CaptureComponentDescriptor> components;
  final int exposureTimestampNanoseconds;
  final List<int> poseRecordHash;
  final List<int> cameraModelHash;
  final List<int> validationRecordHash;
  final List<int> ledgerRecordHash;

  bool get hasCompleteComponentSet =>
      components.map((value) => value.kind).toSet().length ==
          components.length &&
      components
          .map((value) => value.kind)
          .toSet()
          .containsAll(accepted.profile.requiredComponents) &&
      components.length == accepted.profile.requiredComponents.length;

  String get canonicalKey => <Object>[
        accepted.identity.canonicalKey,
        for (final component in components) component.canonicalKey,
        exposureTimestampNanoseconds,
        poseRecordHash,
        cameraModelHash,
        validationRecordHash,
        ledgerRecordHash,
      ].join('|');

  @override
  List<Object?> get equalityFields => [
        accepted,
        components,
        exposureTimestampNanoseconds,
        poseRecordHash,
        cameraModelHash,
        validationRecordHash,
        ledgerRecordHash
      ];
}

/// Exactly one durable committed or metadata-only abandoned terminal.
final class CaptureTerminal with CaptureValueEquality {
  CaptureTerminal({
    required this.kind,
    required this.identity,
    required this.canonicalTerminalHash,
    required this.reason,
    this.captureId,
    this.captureRevision,
    this.manifestId,
  }) {
    if (canonicalTerminalHash.isEmpty || reason.isEmpty)
      throw ArgumentError('Terminal hash and reason must be non-empty.');
    if (captureRevision != null)
      _requirePortableNonNegative(captureRevision!, 'captureRevision');
    if (kind == CaptureTerminalKind.committedPicture &&
        (captureId == null || captureRevision == null || manifestId == null)) {
      throw ArgumentError(
          'Committed terminal requires capture identity, revision, and manifest.');
    }
    if (kind == CaptureTerminalKind.abandonedAttempt &&
        (captureId != null || captureRevision != null || manifestId != null)) {
      throw ArgumentError('Abandoned terminal must be metadata-only.');
    }
  }

  final CaptureTerminalKind kind;
  final CaptureAttemptIdentity identity;
  final String canonicalTerminalHash;
  final String reason;
  final String? captureId;
  final int? captureRevision;
  final String? manifestId;

  bool get metadataOnly => kind == CaptureTerminalKind.abandonedAttempt;

  @override
  List<Object?> get equalityFields => [
        kind,
        identity,
        canonicalTerminalHash,
        reason,
        captureId,
        captureRevision,
        manifestId
      ];
}

/// Durable receipt returned by accept, query, commit, and abandonment APIs.
final class CaptureReceipt with CaptureValueEquality {
  const CaptureReceipt({
    required this.identity,
    required this.phase,
    required this.requestHash,
    required this.receiptHash,
    required this.durable,
    this.terminal,
  });

  final CaptureAttemptIdentity identity;
  final CaptureAttemptPhase phase;
  final String requestHash;
  final String receiptHash;
  final bool durable;
  final CaptureTerminal? terminal;

  @override
  List<Object?> get equalityFields =>
      [identity, phase, requestHash, receiptHash, durable, terminal];
}

/// Frozen storage seam. Issue 99 declares it; later issues implement it.
abstract interface class CaptureCommitPort {
  /// Persists the accepted identity and complete reservations before exposure.
  Future<CaptureReceipt> acceptBeforeExposure(CaptureAcceptedAttempt attempt);

  /// Streams a validated complete component set into commit-owned staging.
  Future<CaptureReceipt> prepareCommit(CaptureCommitRequest request);

  /// Queries an unknown outcome without starting a second exposure.
  Future<CaptureReceipt?> queryReceipt(CaptureAttemptIdentity identity);

  /// Publishes one metadata-only abandonment after commit absence is proven.
  Future<CaptureReceipt> abandon(CaptureTerminal terminal);
}

final class CaptureAdmissionDecision {
  const CaptureAdmissionDecision._(
      {this.accepted, required this.state, required this.reason});

  const CaptureAdmissionDecision.rejected(
      CaptureIntentState state, String reason)
      : this._(state: state, reason: reason);

  CaptureAdmissionDecision.accepted(CaptureAcceptedAttempt accepted)
      : this._(
          accepted: accepted,
          state: accepted.lane == CaptureLane.manual
              ? CaptureIntentState.manualReady
              : CaptureIntentState.automaticReady,
          reason: 'accepted',
        );

  final CaptureAcceptedAttempt? accepted;
  final CaptureIntentState state;
  final String reason;
  bool get isAccepted => accepted != null;
}

enum CaptureFinalizerPosition { running, fundedWaiting, rejected }

final class CaptureFinalizerAssignment with CaptureValueEquality {
  const CaptureFinalizerAssignment(this.attempt, this.position, this.reason);
  final CaptureAcceptedAttempt attempt;
  final CaptureFinalizerPosition position;
  final String reason;
  @override
  List<Object?> get equalityFields => [attempt, position, reason];
}

/// Executable one-running plus one-funded-waiting scheduler reference.
final class CaptureFinalizerScheduler {
  CaptureAcceptedAttempt? _running;
  CaptureAcceptedAttempt? _waiting;

  CaptureAcceptedAttempt? get running => _running;
  CaptureAcceptedAttempt? get fundedWaiting => _waiting;

  List<CaptureFinalizerAssignment> scheduleReady(
      Iterable<CaptureAcceptedAttempt> candidates) {
    final ordered = candidates.toList()
      ..sort((left, right) => left.lane == right.lane
          ? left.identity.attemptOrdinal
              .compareTo(right.identity.attemptOrdinal)
          : (left.lane == CaptureLane.manual ? -1 : 1));
    final results = <CaptureFinalizerAssignment>[];
    for (final attempt in ordered) {
      if (_running == null) {
        _running = attempt;
        results.add(CaptureFinalizerAssignment(
            attempt, CaptureFinalizerPosition.running, 'running'));
      } else if (_waiting == null) {
        _waiting = attempt;
        results.add(CaptureFinalizerAssignment(
            attempt, CaptureFinalizerPosition.fundedWaiting, 'funded-waiting'));
      } else {
        results.add(CaptureFinalizerAssignment(
            attempt, CaptureFinalizerPosition.rejected, 'finalizer-capacity'));
      }
    }
    return List.unmodifiable(results);
  }

  CaptureAcceptedAttempt? release(CaptureAttemptIdentity identity) {
    if (_running?.identity == identity) {
      _running = _waiting;
      _waiting = null;
      return _running;
    }
    if (_waiting?.identity == identity) _waiting = null;
    return _running;
  }

  bool ownsExposure(CaptureAttemptIdentity identity) =>
      _running?.identity == identity;
}

/// Executable T1 admission oracle; it owns identities but no storage or camera.
final class CaptureAdmissionReferenceModel {
  CaptureAdmissionReferenceModel({
    required this.protectedManualMemoryBytes,
    this.maximumMemoryBytes = captureCoexistenceBytes,
  });

  final int maximumMemoryBytes;
  final int protectedManualMemoryBytes;
  int _nextOrdinal = 1;
  final Map<CaptureLane, CaptureAcceptedAttempt> _admitted = {};

  int get admittedCount => _admitted.length;

  CaptureAdmissionDecision preflight(
      CaptureIntent intent, CaptureReservationLiability liability) {
    if (!intent.trackingValid || !intent.captureHealthy) {
      return CaptureAdmissionDecision.rejected(
        intent.lane == CaptureLane.manual
            ? CaptureIntentState.manualReady
            : CaptureIntentState.automaticWaitingCaptureHealth,
        'capture-health',
      );
    }
    if (intent.lane == CaptureLane.automatic && !intent.selectorValid) {
      return const CaptureAdmissionDecision.rejected(
          CaptureIntentState.automaticWaitingSelector, 'selector-invalid');
    }
    if (!intent.durabilityPreflightValid || !liability.physicallyBacked) {
      return CaptureAdmissionDecision.rejected(
        intent.lane == CaptureLane.manual
            ? CaptureIntentState.manualWaitingDurability
            : CaptureIntentState.automaticWaitingDurability,
        'durability-preflight',
      );
    }
    if (_admitted.containsKey(intent.lane)) {
      return CaptureAdmissionDecision.rejected(
        intent.lane == CaptureLane.manual
            ? CaptureIntentState.manualWaitingDurability
            : CaptureIntentState.automaticWaitingDurability,
        'lane-occupied',
      );
    }
    try {
      liability.totalStoreLiability;
    } on StateError {
      return CaptureAdmissionDecision.rejected(
        intent.lane == CaptureLane.manual
            ? CaptureIntentState.manualWaitingDurability
            : CaptureIntentState.automaticWaitingDurability,
        'ordinal-or-liability-overflow',
      );
    }
    if (_nextOrdinal < 1 ||
        _nextOrdinal > capturePortableOrdinalMaximum ||
        liability.memoryBytes < 0 ||
        protectedManualMemoryBytes < 0) {
      return CaptureAdmissionDecision.rejected(
        intent.lane == CaptureLane.manual
            ? CaptureIntentState.manualWaitingDurability
            : CaptureIntentState.automaticWaitingDurability,
        'ordinal-or-liability-overflow',
      );
    }
    int used;
    try {
      used = _admitted.values.fold<int>(
        0,
        (sum, value) => _checkedAdd(sum, value.reservation.memoryBytes),
      );
    } on StateError {
      return CaptureAdmissionDecision.rejected(
        intent.lane == CaptureLane.manual
            ? CaptureIntentState.manualWaitingDurability
            : CaptureIntentState.automaticWaitingDurability,
        'ordinal-or-liability-overflow',
      );
    }
    final protectedBytes = intent.lane == CaptureLane.automatic &&
            !_admitted.containsKey(CaptureLane.manual)
        ? protectedManualMemoryBytes
        : 0;
    int coexistence;
    try {
      coexistence = _checkedAdd(
        _checkedAdd(used, liability.memoryBytes),
        protectedBytes,
      );
    } on StateError {
      return CaptureAdmissionDecision.rejected(
        intent.lane == CaptureLane.manual
            ? CaptureIntentState.manualWaitingDurability
            : CaptureIntentState.automaticWaitingDurability,
        'ordinal-or-liability-overflow',
      );
    }
    if (coexistence > maximumMemoryBytes) {
      return CaptureAdmissionDecision.rejected(
        intent.lane == CaptureLane.manual
            ? CaptureIntentState.manualWaitingDurability
            : CaptureIntentState.automaticWaitingDurability,
        protectedBytes == 0 ? 'memory-capacity' : 'protected-manual-capacity',
      );
    }
    final ordinal = _nextOrdinal++;
    final identity = CaptureAttemptIdentity(
      attemptId: 'attempt-$ordinal',
      commitId: 'commit-$ordinal',
      attemptOrdinal: ordinal,
      lifecycleCut: intent.lifecycleCut,
    );
    final accepted = CaptureAcceptedAttempt(
      identity: identity,
      lane: intent.lane,
      profile: intent.profile,
      reservation: liability,
      canonicalIntentHash: intent.canonicalIntentHash,
      acceptedReceiptHash: _referenceDigest(
          'accepted:${identity.canonicalKey}:${liability.totalStoreLiability}'),
    );
    _admitted[intent.lane] = accepted;
    return CaptureAdmissionDecision.accepted(accepted);
  }

  void release(CaptureAttemptIdentity identity) {
    _admitted.removeWhere(
        (_, value) => value.identity.canonicalKey == identity.canonicalKey);
  }

  /// Test-only boundary setter proving that exhaustion rejects before identity allocation.
  void setNextOrdinalForTest(int value) => _nextOrdinal = value;
}

final class CaptureTransitionResult {
  const CaptureTransitionResult(this.disposition, this.receipt, this.reason);
  final CaptureTransitionDisposition disposition;
  final CaptureReceipt receipt;
  final String reason;
}

/// Executable accepted-to-terminal oracle with exact replay and terminal fencing.
final class CaptureAttemptReferenceMachine {
  CaptureAttemptReferenceMachine(this.accepted)
      : _receipt = CaptureReceipt(
          identity: accepted.identity,
          phase: CaptureAttemptPhase.reservedAccepted,
          requestHash: _bytesKey(accepted.acceptedReceiptHash),
          receiptHash: _bytesKey(accepted.acceptedReceiptHash),
          durable: true,
        );

  final CaptureAcceptedAttempt accepted;
  CaptureReceipt _receipt;
  final Map<String, CaptureReceipt> _replay = {};
  final Set<CaptureComponentKind> _ownedComponents = {};
  bool outcomeUnknown = false;
  String? _unknownRequestHash;
  int exposureCount = 0;
  int retainedImageBytes = 0;

  CaptureReceipt get receipt => _receipt;
  bool get isTerminal => _receipt.terminal != null;

  CaptureTransitionResult requestExposure(String requestHash) => _transition(
          requestHash,
          CaptureAttemptPhase.reservedAccepted,
          CaptureAttemptPhase.exposureRequested, () {
        if (!_receipt.durable)
          throw StateError('accepted receipt must be durable before exposure');
        exposureCount++;
      });

  CaptureTransitionResult ownSensorOutput(
      String requestHash, Iterable<CaptureComponentDescriptor> components) {
    return _transition(requestHash, CaptureAttemptPhase.exposureRequested,
        CaptureAttemptPhase.sensorOutputOwned, () {
      for (final component in components) {
        if (!_ownedComponents.add(component.kind))
          throw StateError('duplicate component');
        retainedImageBytes =
            _checkedAdd(retainedImageBytes, component.byteLength);
      }
    });
  }

  CaptureTransitionResult validate(String requestHash) {
    if (!_ownedComponents.containsAll(accepted.profile.requiredComponents) ||
        _ownedComponents.length != accepted.profile.requiredComponents.length) {
      return _unchanged(CaptureTransitionDisposition.rejected, requestHash,
          'incomplete-component-set');
    }
    return _transition(requestHash, CaptureAttemptPhase.sensorOutputOwned,
        CaptureAttemptPhase.validated, () {});
  }

  CaptureTransitionResult prepareDurable(
      String requestHash, CaptureCommitRequest request) {
    if (!request.hasCompleteComponentSet ||
        request.accepted.identity.canonicalKey !=
            accepted.identity.canonicalKey) {
      return _unchanged(CaptureTransitionDisposition.rejected, requestHash,
          'invalid-commit-input');
    }
    final actualBytes = request.components
        .fold<int>(0, (sum, item) => _checkedAdd(sum, item.byteLength));
    if (actualBytes > accepted.profile.maximumComponentBytes ||
        actualBytes > accepted.reservation.physicalStoreBytes) {
      return abandon('$requestHash:overflow', 'reservation-overflow');
    }
    return _transition(requestHash, CaptureAttemptPhase.validated,
        CaptureAttemptPhase.durablePrepared, () {
      retainedImageBytes = 0;
    });
  }

  CaptureTransitionResult commit(String requestHash,
      {required String captureId,
      required int captureRevision,
      required String manifestId,
      String reason = 'committed'}) {
    if (_receipt.phase != CaptureAttemptPhase.durablePrepared) {
      return _replayOrReject(requestHash, 'commit-before-durable-prepare');
    }
    final terminal = CaptureTerminal(
      kind: CaptureTerminalKind.committedPicture,
      identity: accepted.identity,
      canonicalTerminalHash: requestHash,
      reason: reason,
      captureId: captureId,
      captureRevision: captureRevision,
      manifestId: manifestId,
    );
    return _terminal(requestHash, terminal);
  }

  CaptureTransitionResult abandon(String requestHash, String reason) {
    if (isTerminal)
      return _replayOrReject(requestHash, 'terminal-already-published');
    final terminal = CaptureTerminal(
      kind: CaptureTerminalKind.abandonedAttempt,
      identity: accepted.identity,
      canonicalTerminalHash: requestHash,
      reason: reason,
    );
    retainedImageBytes = 0;
    return _terminal(requestHash, terminal);
  }

  CaptureTransitionResult timeoutUnknown(String requestHash) {
    if (isTerminal)
      return _replayOrReject(requestHash, 'terminal-already-published');
    if (_unknownRequestHash != null) {
      return _unknownRequestHash == requestHash
          ? CaptureTransitionResult(CaptureTransitionDisposition.exactReplay,
              _receipt, 'exact-unknown-replay')
          : _unchanged(CaptureTransitionDisposition.conflict, requestHash,
              'changed-unknown-replay');
    }
    outcomeUnknown = true;
    _unknownRequestHash = requestHash;
    return _unchanged(CaptureTransitionDisposition.applied, requestHash,
        'outcome-unknown-query-same-identity');
  }

  CaptureTransitionResult lateCallback(String requestHash) => _unchanged(
      CaptureTransitionDisposition.rejected,
      requestHash,
      'late-producer-fenced');

  CaptureTransitionResult _transition(String hash, CaptureAttemptPhase expected,
      CaptureAttemptPhase next, void Function() effect) {
    if (_receipt.phase != expected || isTerminal)
      return _replayOrReject(hash, 'invalid-transition');
    try {
      effect();
    } on StateError catch (error) {
      return _unchanged(CaptureTransitionDisposition.rejected, hash,
          error.message.toString());
    }
    _receipt = CaptureReceipt(
      identity: accepted.identity,
      phase: next,
      requestHash: hash,
      receiptHash: '$hash:${next.name}',
      durable: next == CaptureAttemptPhase.reservedAccepted ||
          next == CaptureAttemptPhase.durablePrepared,
    );
    _replay[hash] = _receipt;
    return CaptureTransitionResult(
        CaptureTransitionDisposition.applied, _receipt, 'applied');
  }

  CaptureTransitionResult _terminal(String hash, CaptureTerminal terminal) {
    _receipt = CaptureReceipt(
      identity: accepted.identity,
      phase: terminal.kind == CaptureTerminalKind.committedPicture
          ? CaptureAttemptPhase.committedPicture
          : CaptureAttemptPhase.abandonedAttempt,
      requestHash: hash,
      receiptHash: '$hash:${terminal.kind.name}',
      durable: true,
      terminal: terminal,
    );
    _replay[hash] = _receipt;
    outcomeUnknown = false;
    _unknownRequestHash = null;
    return CaptureTransitionResult(
        CaptureTransitionDisposition.applied, _receipt, 'terminal');
  }

  CaptureTransitionResult _replayOrReject(String hash, String reason) {
    final replay = _replay[hash];
    if (replay != null)
      return CaptureTransitionResult(
          CaptureTransitionDisposition.exactReplay, replay, 'exact-replay');
    if (isTerminal)
      return CaptureTransitionResult(CaptureTransitionDisposition.conflict,
          _receipt, 'changed-terminal-replay');
    return _unchanged(CaptureTransitionDisposition.rejected, hash, reason);
  }

  CaptureTransitionResult _unchanged(CaptureTransitionDisposition disposition,
          String hash, String reason) =>
      CaptureTransitionResult(disposition, _receipt, reason);
}

final class CaptureFaultLifecycleCase {
  const CaptureFaultLifecycleCase(this.lane, this.phase, this.fault,
      this.lifecycleEvent, this.expectedOutcome);
  final CaptureLane lane;
  final CaptureAttemptPhase phase;
  final CaptureFault fault;
  final CaptureLifecycleEvent lifecycleEvent;
  final CaptureMatrixOutcome expectedOutcome;
}

enum CaptureMatrixOutcome { abandoned, outcomeUnknown, committed }

enum CaptureLifecycleOwnershipEffect {
  automaticActiveOwnerContinues,
  routePreOutputClassified,
  routeOwnedOutputTransferred,
  routeDurableStoreOwned,
  backgroundPreOutputClassified,
  backgroundOwnedOutputFinishing,
  backgroundDurableStoreOwned,
  viewPreOutputFenced,
  viewOwnedOutputTransferred,
  viewDurableStoreOwned,
  arPreOutputGroupFenced,
  arOwnedOutputFrozenGroup,
  arDurableOldGroupStoreOwned,
  processPreDurableAbsent,
  processDurableReceiptRecovered,
}

final class CaptureMatrixExecution {
  const CaptureMatrixExecution(
      this.outcome,
      this.phase,
      this.exposureCount,
      this.exactReplay,
      this.changedReplayConflict,
      this.ownershipEffect,
      this.lateCallbackNoOp,
      this.sensorOutputOwnedAtCut,
      this.continuedExposureAfterCut,
      this.sensorOutputAcquiredAfterCut,
      this.classificationReason);
  final CaptureMatrixOutcome outcome;
  final CaptureAttemptPhase phase;
  final int exposureCount;
  final bool exactReplay;
  final bool changedReplayConflict;
  final CaptureLifecycleOwnershipEffect ownershipEffect;
  final bool lateCallbackNoOp;
  final bool sensorOutputOwnedAtCut;
  final bool continuedExposureAfterCut;
  final bool sensorOutputAcquiredAfterCut;
  final String classificationReason;
}

/// Canonical legal edges. Every other phase pair is rejected deterministically.
abstract final class CaptureAttemptTransitionTable {
  static const Set<(CaptureAttemptPhase, CaptureAttemptPhase)> legalEdges = {
    (
      CaptureAttemptPhase.reservedAccepted,
      CaptureAttemptPhase.exposureRequested
    ),
    (
      CaptureAttemptPhase.exposureRequested,
      CaptureAttemptPhase.sensorOutputOwned
    ),
    (CaptureAttemptPhase.sensorOutputOwned, CaptureAttemptPhase.validated),
    (CaptureAttemptPhase.validated, CaptureAttemptPhase.durablePrepared),
    (CaptureAttemptPhase.durablePrepared, CaptureAttemptPhase.committedPicture),
    (
      CaptureAttemptPhase.reservedAccepted,
      CaptureAttemptPhase.abandonedAttempt
    ),
    (
      CaptureAttemptPhase.exposureRequested,
      CaptureAttemptPhase.abandonedAttempt
    ),
    (
      CaptureAttemptPhase.sensorOutputOwned,
      CaptureAttemptPhase.abandonedAttempt
    ),
    (CaptureAttemptPhase.validated, CaptureAttemptPhase.abandonedAttempt),
    (CaptureAttemptPhase.durablePrepared, CaptureAttemptPhase.abandonedAttempt),
  };

  static bool isLegal(CaptureAttemptPhase from, CaptureAttemptPhase to) =>
      legalEdges.contains((from, to));
}

/// Generates the complete #99 accepted-to-terminal fault/lifecycle cross-product.
final class CaptureFaultLifecycleMatrix {
  static List<CaptureFaultLifecycleCase> generate() => List.unmodifiable([
        for (final lane in CaptureLane.values)
          for (final phase in CaptureAttemptPhase.values.where((value) =>
              value.index < CaptureAttemptPhase.committedPicture.index))
            for (final fault in CaptureFault.values)
              for (final event in CaptureLifecycleEvent.values)
                CaptureFaultLifecycleCase(lane, phase, fault, event,
                    _expectedOutcome(phase, fault, event)),
      ]);

  static CaptureMatrixOutcome _expectedOutcome(CaptureAttemptPhase phase,
      CaptureFault fault, CaptureLifecycleEvent lifecycleEvent) {
    if (const {
      CaptureFault.cancellation,
      CaptureFault.malformedOutput,
      CaptureFault.componentFailure,
      CaptureFault.storageFailure,
      CaptureFault.reservationOverflow,
    }.contains(fault)) {
      return CaptureMatrixOutcome.abandoned;
    }
    if (fault == CaptureFault.lateCallback) {
      return _lateCallbackOutcome(phase, lifecycleEvent);
    }
    final uncertain = const {
      CaptureFault.timeout,
      CaptureFault.isolateLoss,
      CaptureFault.processLoss
    }.contains(fault);
    if (phase == CaptureAttemptPhase.durablePrepared && uncertain) {
      return CaptureMatrixOutcome.committed;
    }
    if (lifecycleEvent == CaptureLifecycleEvent.processRestarted) {
      return CaptureMatrixOutcome.abandoned;
    }
    if (phase != CaptureAttemptPhase.reservedAccepted && uncertain) {
      return CaptureMatrixOutcome.outcomeUnknown;
    }
    return CaptureMatrixOutcome.abandoned;
  }

  static CaptureMatrixOutcome _lateCallbackOutcome(
      CaptureAttemptPhase phase, CaptureLifecycleEvent event) {
    switch (event) {
      case CaptureLifecycleEvent.automaticDisabled:
        return CaptureMatrixOutcome.committed;
      case CaptureLifecycleEvent.routeLeft:
      case CaptureLifecycleEvent.backgrounded:
      case CaptureLifecycleEvent.viewReplaced:
      case CaptureLifecycleEvent.arSessionReplaced:
        return phase.index < CaptureAttemptPhase.sensorOutputOwned.index
            ? CaptureMatrixOutcome.abandoned
            : CaptureMatrixOutcome.committed;
      case CaptureLifecycleEvent.processRestarted:
        return phase == CaptureAttemptPhase.durablePrepared
            ? CaptureMatrixOutcome.committed
            : CaptureMatrixOutcome.abandoned;
    }
  }

  static CaptureLifecycleOwnershipEffect _ownershipEffect(
      CaptureLifecycleEvent event, CaptureAttemptPhase phase) {
    final preOutput = phase.index < CaptureAttemptPhase.sensorOutputOwned.index;
    final durable = phase == CaptureAttemptPhase.durablePrepared;
    switch (event) {
      case CaptureLifecycleEvent.automaticDisabled:
        return CaptureLifecycleOwnershipEffect.automaticActiveOwnerContinues;
      case CaptureLifecycleEvent.routeLeft:
        return preOutput
            ? CaptureLifecycleOwnershipEffect.routePreOutputClassified
            : durable
                ? CaptureLifecycleOwnershipEffect.routeDurableStoreOwned
                : CaptureLifecycleOwnershipEffect.routeOwnedOutputTransferred;
      case CaptureLifecycleEvent.backgrounded:
        return preOutput
            ? CaptureLifecycleOwnershipEffect.backgroundPreOutputClassified
            : durable
                ? CaptureLifecycleOwnershipEffect.backgroundDurableStoreOwned
                : CaptureLifecycleOwnershipEffect
                    .backgroundOwnedOutputFinishing;
      case CaptureLifecycleEvent.viewReplaced:
        return preOutput
            ? CaptureLifecycleOwnershipEffect.viewPreOutputFenced
            : durable
                ? CaptureLifecycleOwnershipEffect.viewDurableStoreOwned
                : CaptureLifecycleOwnershipEffect.viewOwnedOutputTransferred;
      case CaptureLifecycleEvent.arSessionReplaced:
        return preOutput
            ? CaptureLifecycleOwnershipEffect.arPreOutputGroupFenced
            : durable
                ? CaptureLifecycleOwnershipEffect.arDurableOldGroupStoreOwned
                : CaptureLifecycleOwnershipEffect.arOwnedOutputFrozenGroup;
      case CaptureLifecycleEvent.processRestarted:
        return durable
            ? CaptureLifecycleOwnershipEffect.processDurableReceiptRecovered
            : CaptureLifecycleOwnershipEffect.processPreDurableAbsent;
    }
  }

  static CaptureMatrixExecution execute(CaptureFaultLifecycleCase row,
      CaptureAcceptedAttempt accepted, CaptureCommitRequest request) {
    final machine = CaptureAttemptReferenceMachine(accepted);
    if (row.phase.index >= CaptureAttemptPhase.exposureRequested.index) {
      machine.requestExposure('expose');
    }
    if (row.phase.index >= CaptureAttemptPhase.sensorOutputOwned.index) {
      machine.ownSensorOutput('output', request.components);
    }
    if (row.phase.index >= CaptureAttemptPhase.validated.index) {
      machine.validate('validate');
    }
    if (row.phase.index >= CaptureAttemptPhase.durablePrepared.index) {
      machine.prepareDurable('prepare', request);
    }
    var lateCallbackNoOp = false;
    if (row.fault == CaptureFault.lateCallback) {
      final phaseBeforeCallback = machine.receipt.phase;
      final exposureBeforeCallback = machine.exposureCount;
      final callback = machine.lateCallback('fault:late-callback');
      lateCallbackNoOp =
          callback.disposition == CaptureTransitionDisposition.rejected &&
              machine.receipt.phase == phaseBeforeCallback &&
              machine.exposureCount == exposureBeforeCallback;
    }
    final ownershipEffect = _ownershipEffect(row.lifecycleEvent, row.phase);
    final sensorOutputOwnedAtCut =
        row.phase.index >= CaptureAttemptPhase.sensorOutputOwned.index;
    final canonicalOutcome =
        _expectedOutcome(row.phase, row.fault, row.lifecycleEvent);
    if (canonicalOutcome == CaptureMatrixOutcome.committed) {
      final continuation =
          _continuePreservedOwnership(machine, request, row.lifecycleEvent);
      machine.commit('terminal',
          captureId: 'matrix-capture',
          captureRevision: 1,
          manifestId: 'matrix-manifest',
          reason: 'lifecycle:${ownershipEffect.name}');
      final exact = machine.commit('terminal',
          captureId: 'matrix-capture',
          captureRevision: 1,
          manifestId: 'matrix-manifest');
      final changed = machine.commit('changed-terminal',
          captureId: 'changed', captureRevision: 2, manifestId: 'changed');
      return CaptureMatrixExecution(
          CaptureMatrixOutcome.committed,
          machine.receipt.phase,
          machine.exposureCount,
          exact.disposition == CaptureTransitionDisposition.exactReplay,
          changed.disposition == CaptureTransitionDisposition.conflict,
          ownershipEffect,
          lateCallbackNoOp,
          sensorOutputOwnedAtCut,
          continuation.exposure,
          continuation.sensorOutput,
          machine.receipt.terminal!.reason);
    }
    if (canonicalOutcome == CaptureMatrixOutcome.outcomeUnknown) {
      machine.timeoutUnknown('unknown');
      final exact = machine.timeoutUnknown('unknown');
      final changed = machine.timeoutUnknown('changed-unknown');
      return CaptureMatrixExecution(
          CaptureMatrixOutcome.outcomeUnknown,
          machine.receipt.phase,
          machine.exposureCount,
          exact.disposition == CaptureTransitionDisposition.exactReplay,
          changed.disposition == CaptureTransitionDisposition.conflict,
          ownershipEffect,
          lateCallbackNoOp,
          sensorOutputOwnedAtCut,
          false,
          false,
          'unknown:${ownershipEffect.name}:query-same-identity');
    }
    machine.abandon(
        'terminal', 'lifecycle:${ownershipEffect.name}:proven-absent');
    final exact = machine.abandon('terminal', 'proven-absent');
    final changed = machine.abandon('changed-terminal', 'changed');
    return CaptureMatrixExecution(
        CaptureMatrixOutcome.abandoned,
        machine.receipt.phase,
        machine.exposureCount,
        exact.disposition == CaptureTransitionDisposition.exactReplay,
        changed.disposition == CaptureTransitionDisposition.conflict,
        ownershipEffect,
        lateCallbackNoOp,
        sensorOutputOwnedAtCut,
        false,
        false,
        machine.receipt.terminal!.reason);
  }

  static ({bool exposure, bool sensorOutput}) _continuePreservedOwnership(
      CaptureAttemptReferenceMachine machine,
      CaptureCommitRequest request,
      CaptureLifecycleEvent event) {
    var continuedExposureAfterCut = false;
    var sensorOutputAcquiredAfterCut = false;
    if (machine.receipt.phase == CaptureAttemptPhase.reservedAccepted) {
      if (event != CaptureLifecycleEvent.automaticDisabled) {
        throw StateError('lifecycle cannot request a new exposure');
      }
      machine.requestExposure('lifecycle-expose');
      continuedExposureAfterCut = true;
    }
    if (machine.receipt.phase == CaptureAttemptPhase.exposureRequested) {
      if (event != CaptureLifecycleEvent.automaticDisabled) {
        throw StateError('lifecycle cannot fabricate sensor-output ownership');
      }
      machine.ownSensorOutput('lifecycle-output', request.components);
      sensorOutputAcquiredAfterCut = true;
    }
    if (machine.receipt.phase == CaptureAttemptPhase.sensorOutputOwned) {
      machine.validate('lifecycle-validate');
    }
    if (machine.receipt.phase == CaptureAttemptPhase.validated) {
      machine.prepareDurable('lifecycle-prepare', request);
    }
    return (
      exposure: continuedExposureAfterCut,
      sensorOutput: sensorOutputAcquiredAfterCut
    );
  }
}

int _checkedAdd(int left, int right) {
  if (left < 0 || right < 0 || left > capturePortableOrdinalMaximum - right) {
    throw StateError('portable non-negative integer overflow');
  }
  return left + right;
}

String _bytesKey(List<int> bytes) =>
    bytes.map((value) => value.toRadixString(16).padLeft(2, '0')).join();

void _requirePortableNonNegative(int value, String name) {
  if (value < 0 || value > capturePortableOrdinalMaximum) {
    throw ArgumentError.value(value, name, 'must be in 0..2^63-1');
  }
}

void _requirePortableEntry(int value, String name) {
  if (value < 0 || value > capturePortableEntryMaximum) {
    throw ArgumentError.value(value, name, 'must be in 0..2^31-1');
  }
}

List<int> _copyDigest(List<int> bytes, String name) {
  if (bytes.length != 32 || bytes.any((value) => value < 0 || value > 255)) {
    throw ArgumentError.value(bytes, name, 'must be exactly 32 unsigned bytes');
  }
  return List.unmodifiable(bytes);
}

List<int> _referenceDigest(String value) {
  final source = value.codeUnits;
  return List<int>.generate(
      32, (index) => source[index % source.length] ^ index,
      growable: false);
}

bool _deepEquals(Object? left, Object? right) {
  if (identical(left, right)) return true;
  if (left is Set && right is Set) {
    return left.length == right.length &&
        left.every(
            (value) => right.any((candidate) => _deepEquals(value, candidate)));
  }
  if (left is Iterable && right is Iterable) {
    final a = left.iterator;
    final b = right.iterator;
    while (true) {
      final hasA = a.moveNext();
      final hasB = b.moveNext();
      if (hasA != hasB) return false;
      if (!hasA) return true;
      if (!_deepEquals(a.current, b.current)) return false;
    }
  }
  return left == right;
}

int _deepHash(Object? value) {
  if (value is Set) return Object.hashAllUnordered(value.map(_deepHash));
  if (value is Iterable) return Object.hashAll(value.map(_deepHash));
  return value.hashCode;
}
