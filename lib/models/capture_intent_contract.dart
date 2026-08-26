import 'dart:collection';

/// Largest portable ordinal. Capture ordinals never wrap or reuse a value.
const int capturePortableOrdinalMaximum = 0x7fffffffffffffff;

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
final class CaptureLifecycleCut {
  const CaptureLifecycleCut({
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
  });

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
}

/// A user or selector-originated request before any durable identity exists.
final class CaptureIntent {
  const CaptureIntent({
    required this.lane,
    required this.lifecycleCut,
    required this.profile,
    required this.selectorValid,
    required this.trackingValid,
    required this.captureHealthy,
    required this.durabilityPreflightValid,
    required this.canonicalIntentHash,
  });

  final CaptureLane lane;
  final CaptureLifecycleCut lifecycleCut;
  final CaptureComponentProfile profile;
  final bool selectorValid;
  final bool trackingValid;
  final bool captureHealthy;
  final bool durabilityPreflightValid;
  final String canonicalIntentHash;
}

/// Fixed component composition selected before admission.
final class CaptureComponentProfile {
  CaptureComponentProfile({
    required this.profileId,
    required Set<CaptureComponentKind> requiredComponents,
    required this.maximumComponentBytes,
    required this.maximumWorkingBytes,
  }) : requiredComponents = UnmodifiableSetView(Set.of(requiredComponents)) {
    if (requiredComponents.isEmpty ||
        maximumComponentBytes <= 0 ||
        maximumWorkingBytes <= 0) {
      throw ArgumentError(
          'A capture profile must have positive, bounded components and working bytes.');
    }
  }

  final String profileId;
  final Set<CaptureComponentKind> requiredComponents;
  final int maximumComponentBytes;
  final int maximumWorkingBytes;

  String get canonicalKey =>
      '$profileId:${requiredComponents.map((e) => e.name).toList()..sort()}:$maximumComponentBytes:$maximumWorkingBytes';
}

/// Complete pre-exposure RAM and physical-store liability.
final class CaptureReservationLiability {
  const CaptureReservationLiability({
    required this.memoryBytes,
    required this.physicalStoreBytes,
    required this.componentEntries,
    required this.terminalEntries,
    required this.rollbackBytes,
    required this.physicallyBacked,
  });

  final int memoryBytes;
  final int physicalStoreBytes;
  final int componentEntries;
  final int terminalEntries;
  final int rollbackBytes;
  final bool physicallyBacked;

  int get totalStoreLiability => _checkedAdd(physicalStoreBytes, rollbackBytes);
}

/// Stable identity allocated only after every preflight gate passes.
final class CaptureAttemptIdentity {
  const CaptureAttemptIdentity({
    required this.attemptId,
    required this.commitId,
    required this.attemptOrdinal,
    required this.lifecycleCut,
  });

  final String attemptId;
  final String commitId;
  final int attemptOrdinal;
  final CaptureLifecycleCut lifecycleCut;

  String get canonicalKey =>
      '$attemptId|$commitId|$attemptOrdinal|${lifecycleCut.canonicalKey}';
}

/// Durable accepted record which must exist before shutter operation.
final class CaptureAcceptedAttempt {
  const CaptureAcceptedAttempt({
    required this.identity,
    required this.lane,
    required this.profile,
    required this.reservation,
    required this.canonicalIntentHash,
    required this.acceptedReceiptHash,
  });

  final CaptureAttemptIdentity identity;
  final CaptureLane lane;
  final CaptureComponentProfile profile;
  final CaptureReservationLiability reservation;
  final String canonicalIntentHash;
  final String acceptedReceiptHash;
}

/// Metadata for one streamed, attempt-owned component. It carries no bytes.
final class CaptureComponentDescriptor {
  const CaptureComponentDescriptor({
    required this.kind,
    required this.byteLength,
    required this.sha256,
    required this.durableObjectId,
  });

  final CaptureComponentKind kind;
  final int byteLength;
  final String sha256;
  final String durableObjectId;

  String get canonicalKey =>
      '${kind.name}|$byteLength|$sha256|$durableObjectId';
}

/// Complete typed native-to-store commit input after validation.
final class CaptureCommitRequest {
  CaptureCommitRequest({
    required this.accepted,
    required Iterable<CaptureComponentDescriptor> components,
    required this.exposureTimestampNanoseconds,
    required this.poseRecordHash,
    required this.cameraModelHash,
    required this.validationRecordHash,
    required this.ledgerRecordHash,
  }) : components = List.unmodifiable(components);

  final CaptureAcceptedAttempt accepted;
  final List<CaptureComponentDescriptor> components;
  final int exposureTimestampNanoseconds;
  final String poseRecordHash;
  final String cameraModelHash;
  final String validationRecordHash;
  final String ledgerRecordHash;

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
}

/// Exactly one durable committed or metadata-only abandoned terminal.
final class CaptureTerminal {
  const CaptureTerminal({
    required this.kind,
    required this.identity,
    required this.canonicalTerminalHash,
    required this.reason,
    this.captureId,
    this.captureRevision,
    this.manifestId,
  });

  final CaptureTerminalKind kind;
  final CaptureAttemptIdentity identity;
  final String canonicalTerminalHash;
  final String reason;
  final String? captureId;
  final int? captureRevision;
  final String? manifestId;

  bool get metadataOnly => kind == CaptureTerminalKind.abandonedAttempt;
}

/// Durable receipt returned by accept, query, commit, and abandonment APIs.
final class CaptureReceipt {
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
      acceptedReceiptHash:
          'accepted:${identity.canonicalKey}:${liability.totalStoreLiability}',
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
          requestHash: accepted.acceptedReceiptHash,
          receiptHash: accepted.acceptedReceiptHash,
          durable: true,
        );

  final CaptureAcceptedAttempt accepted;
  CaptureReceipt _receipt;
  final Map<String, CaptureReceipt> _replay = {};
  final Set<CaptureComponentKind> _ownedComponents = {};
  bool outcomeUnknown = false;
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
      required String manifestId}) {
    if (_receipt.phase != CaptureAttemptPhase.durablePrepared) {
      return _replayOrReject(requestHash, 'commit-before-durable-prepare');
    }
    final terminal = CaptureTerminal(
      kind: CaptureTerminalKind.committedPicture,
      identity: accepted.identity,
      canonicalTerminalHash: requestHash,
      reason: 'committed',
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
    outcomeUnknown = true;
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
  const CaptureFaultLifecycleCase(
      this.phase, this.fault, this.lifecycleEvent, this.expectedTerminal);
  final CaptureAttemptPhase phase;
  final CaptureFault fault;
  final CaptureLifecycleEvent lifecycleEvent;
  final CaptureTerminalKind expectedTerminal;
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
        for (final phase in CaptureAttemptPhase.values.where((value) =>
            value.index < CaptureAttemptPhase.committedPicture.index))
          for (final fault in CaptureFault.values)
            for (final event in CaptureLifecycleEvent.values)
              CaptureFaultLifecycleCase(
                  phase, fault, event, CaptureTerminalKind.abandonedAttempt),
      ]);
}

int _checkedAdd(int left, int right) {
  if (left < 0 || right < 0 || left > capturePortableOrdinalMaximum - right) {
    throw StateError('portable non-negative integer overflow');
  }
  return left + right;
}
