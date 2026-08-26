import 'package:ar_flutter_plugin_2/models/capture_intent_contract.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('Issue 99 capture intent contract', () {
    test('preflight failures allocate no ID and automatic requires selector',
        () {
      final model = _admissionModel();
      expect(
        model
            .preflight(
                _intent(CaptureLane.automatic, selector: false), _liability())
            .state,
        CaptureIntentState.automaticWaitingSelector,
      );
      expect(
        model
            .preflight(_intent(CaptureLane.manual), _liability())
            .accepted!
            .identity
            .attemptOrdinal,
        1,
      );
    });

    test('automatic preserves protected manual lane and both coexist', () {
      final model = _admissionModel();
      expect(
        model
            .preflight(
              _intent(CaptureLane.automatic),
              _liability(memory: 70 * 1024 * 1024),
            )
            .reason,
        'protected-manual-capacity',
      );
      expect(
        model
            .preflight(_intent(CaptureLane.automatic), _liability())
            .isAccepted,
        isTrue,
      );
      expect(
        model.preflight(_intent(CaptureLane.manual), _liability()).isAccepted,
        isTrue,
      );
      expect(model.admittedCount, 2);
    });

    test('accepted receipt precedes exactly one exposure and complete commit',
        () {
      final accepted = _admissionModel()
          .preflight(_intent(CaptureLane.manual), _liability())
          .accepted!;
      final machine = CaptureAttemptReferenceMachine(accepted);
      expect(machine.receipt.durable, isTrue);
      expect(machine.requestExposure('expose').disposition,
          CaptureTransitionDisposition.applied);
      expect(machine.requestExposure('expose').disposition,
          CaptureTransitionDisposition.exactReplay);
      expect(machine.exposureCount, 1);
      final components = [
        _component(CaptureComponentKind.jpeg),
        _component(CaptureComponentKind.dng)
      ];
      machine.ownSensorOutput('output', components);
      expect(machine.validate('validate').disposition,
          CaptureTransitionDisposition.applied);
      expect(
          machine
              .prepareDurable('prepare', _commit(accepted, components))
              .disposition,
          CaptureTransitionDisposition.applied);
      expect(machine.retainedImageBytes, 0);
      expect(
        machine
            .commit('commit',
                captureId: 'capture',
                captureRevision: 1,
                manifestId: 'manifest')
            .receipt
            .terminal!
            .kind,
        CaptureTerminalKind.committedPicture,
      );
      expect(
        machine
            .commit('commit',
                captureId: 'capture',
                captureRevision: 1,
                manifestId: 'manifest')
            .disposition,
        CaptureTransitionDisposition.exactReplay,
      );
      expect(
        machine
            .commit('changed',
                captureId: 'other', captureRevision: 2, manifestId: 'other')
            .disposition,
        CaptureTransitionDisposition.conflict,
      );
    });

    test('incomplete components reject before commit', () {
      final accepted = _admissionModel()
          .preflight(_intent(CaptureLane.manual), _liability())
          .accepted!;
      final machine = CaptureAttemptReferenceMachine(accepted)
        ..requestExposure('expose');
      machine
          .ownSensorOutput('output', [_component(CaptureComponentKind.jpeg)]);
      expect(machine.validate('validate').reason, 'incomplete-component-set');
      expect(machine.receipt.phase, CaptureAttemptPhase.sensorOutputOwned);
    });

    test(
        'timeout remains unknown, abandonment fences late work, later succeeds',
        () {
      final model = _admissionModel();
      final first =
          model.preflight(_intent(CaptureLane.manual), _liability()).accepted!;
      final failed = CaptureAttemptReferenceMachine(first)
        ..requestExposure('expose');
      expect(failed.timeoutUnknown('timeout').receipt.phase,
          CaptureAttemptPhase.exposureRequested);
      expect(failed.outcomeUnknown, isTrue);
      expect(
          failed
              .abandon('abandon', 'proven-absent')
              .receipt
              .terminal!
              .metadataOnly,
          isTrue);
      expect(failed.lateCallback('late').reason, 'late-producer-fenced');
      model.release(first.identity);
      final later =
          model.preflight(_intent(CaptureLane.manual), _liability()).accepted!;
      expect(later.identity.attemptOrdinal, 2);
    });

    test('reservation and ordinal overflow abandon or reject without wrap', () {
      final model = _admissionModel();
      final accepted = model
          .preflight(_intent(CaptureLane.manual), _liability(store: 100))
          .accepted!;
      final machine = CaptureAttemptReferenceMachine(accepted)
        ..requestExposure('expose');
      final components = [
        _component(CaptureComponentKind.jpeg, bytes: 80),
        _component(CaptureComponentKind.dng, bytes: 80),
      ];
      machine.ownSensorOutput('output', components);
      machine.validate('validate');
      expect(
        machine
            .prepareDurable('prepare', _commit(accepted, components))
            .receipt
            .terminal!
            .reason,
        'reservation-overflow',
      );
      final exhausted = _admissionModel()
        ..setNextOrdinalForTest(capturePortableOrdinalMaximum + 1);
      expect(
        exhausted.preflight(_intent(CaptureLane.manual), _liability()).reason,
        'ordinal-or-liability-overflow',
      );
    });

    test('generated fault lifecycle matrix is the complete cross-product', () {
      final matrix = CaptureFaultLifecycleMatrix.generate();
      expect(
          matrix,
          hasLength(CaptureLane.values.length *
              5 *
              CaptureFault.values.length *
              CaptureLifecycleEvent.values.length));
      expect(matrix.map((value) => value.phase).toSet(),
          CaptureAttemptPhase.values.take(5).toSet());
      expect(matrix.map((value) => value.lane).toSet(),
          CaptureLane.values.toSet());
      expect(matrix.map((value) => value.fault).toSet(),
          CaptureFault.values.toSet());
      expect(matrix.map((value) => value.lifecycleEvent).toSet(),
          CaptureLifecycleEvent.values.toSet());
      expect(matrix.map((value) => value.expectedOutcome).toSet(),
          CaptureMatrixOutcome.values.toSet());
    });

    test('transition table rejects every edge outside the canonical graph', () {
      final pairs = [
        for (final from in CaptureAttemptPhase.values)
          for (final to in CaptureAttemptPhase.values) (from, to),
      ];
      expect(
        pairs
            .where((pair) =>
                CaptureAttemptTransitionTable.isLegal(pair.$1, pair.$2))
            .length,
        10,
      );
      expect(
        pairs
            .where((pair) =>
                !CaptureAttemptTransitionTable.isLegal(pair.$1, pair.$2))
            .length,
        39,
      );
    });

    test('overflow releases the lane and a later full commit succeeds', () {
      final model = _admissionModel();
      final failed = model
          .preflight(_intent(CaptureLane.manual), _liability(store: 100))
          .accepted!;
      final failedMachine = CaptureAttemptReferenceMachine(failed)
        ..requestExposure('expose');
      final oversized = [
        _component(CaptureComponentKind.jpeg, bytes: 80),
        _component(CaptureComponentKind.dng, bytes: 80),
      ];
      failedMachine
        ..ownSensorOutput('output', oversized)
        ..validate('validate')
        ..prepareDurable('prepare', _commit(failed, oversized));
      model.release(failed.identity);
      final later =
          model.preflight(_intent(CaptureLane.manual), _liability()).accepted!;
      final laterMachine = CaptureAttemptReferenceMachine(later)
        ..requestExposure('later-expose');
      final complete = [
        _component(CaptureComponentKind.jpeg),
        _component(CaptureComponentKind.dng),
      ];
      laterMachine
        ..ownSensorOutput('later-output', complete)
        ..validate('later-validate')
        ..prepareDurable('later-prepare', _commit(later, complete));
      expect(
        laterMachine
            .commit(
              'later-commit',
              captureId: 'later-capture',
              captureRevision: 1,
              manifestId: 'later-manifest',
            )
            .receipt
            .terminal!
            .kind,
        CaptureTerminalKind.committedPicture,
      );
    });

    test('DTO value equality hashes deep lists and copies mutable bytes', () {
      final bytes = _digest('mutable');
      final first = CaptureComponentDescriptor(
          kind: CaptureComponentKind.jpeg,
          byteLength: 40,
          sha256: bytes,
          durableObjectId: 'object');
      final second = CaptureComponentDescriptor(
          kind: CaptureComponentKind.jpeg,
          byteLength: 40,
          sha256: List<int>.of(bytes),
          durableObjectId: 'object');
      expect(first, second);
      expect(first.hashCode, second.hashCode);
      bytes[0] ^= 0xff;
      expect(first, second);
      expect(
          _commit(_accepted(CaptureLane.manual), [
            _component(CaptureComponentKind.jpeg),
            _component(CaptureComponentKind.dng)
          ]),
          _commit(_accepted(CaptureLane.manual), [
            _component(CaptureComponentKind.jpeg),
            _component(CaptureComponentKind.dng)
          ]));
    });

    test('numeric domains reject negative overflow and malformed digests', () {
      expect(
          () => CaptureComponentDescriptor(
              kind: CaptureComponentKind.jpeg,
              byteLength: -1,
              sha256: _digest('x'),
              durableObjectId: 'x'),
          throwsArgumentError);
      expect(
          () => CaptureReservationLiability(
              memoryBytes: -1,
              physicalStoreBytes: 0,
              componentEntries: 0,
              terminalEntries: 0,
              rollbackBytes: 0,
              physicallyBacked: true),
          throwsArgumentError);
      expect(
          () => CaptureReservationLiability(
              memoryBytes: 0,
              physicalStoreBytes: 0,
              componentEntries: capturePortableEntryMaximum + 1,
              terminalEntries: 0,
              rollbackBytes: 0,
              physicallyBacked: true),
          throwsArgumentError);
      expect(
          () => CaptureAttemptIdentity(
              attemptId: 'a',
              commitId: 'c',
              attemptOrdinal: 0,
              lifecycleCut: _cut()),
          throwsArgumentError);
      expect(
          () => CaptureLifecycleCut(
              sessionId: 's',
              sessionGeneration: -1,
              groupId: 'g',
              groupGeneration: 0,
              arSessionId: 'a',
              viewId: 'v',
              viewGeneration: 0,
              bindingToken: 'b',
              lifecycleSequence: 0,
              operationGeneration: 0),
          throwsArgumentError);
      expect(
          () => CaptureComponentDescriptor(
              kind: CaptureComponentKind.jpeg,
              byteLength: 1,
              sha256: [1],
              durableObjectId: 'x'),
          throwsArgumentError);
    });

    test('finalizer scheduler gives manual running priority and promotes once',
        () {
      final manual = _accepted(CaptureLane.manual);
      final automatic = _accepted(CaptureLane.automatic, ordinal: 2);
      final scheduler = CaptureFinalizerScheduler();
      final assignments =
          scheduler.scheduleReady([automatic, manual, automatic]);
      expect(assignments.map((value) => value.position), [
        CaptureFinalizerPosition.running,
        CaptureFinalizerPosition.fundedWaiting,
        CaptureFinalizerPosition.rejected
      ]);
      expect(scheduler.running, manual);
      expect(scheduler.fundedWaiting, automatic);
      expect(scheduler.ownsExposure(automatic.identity), isFalse);
      expect(CaptureAttemptReferenceMachine(automatic).exposureCount, 0);
      expect(scheduler.release(manual.identity), automatic);
      expect(scheduler.ownsExposure(automatic.identity), isTrue);
      expect(scheduler.release(automatic.identity), isNull);
    });

    test('all 540 rows execute canonical terminal query and replay outcomes',
        () {
      final outcomeCounts = <CaptureMatrixOutcome, int>{};
      final accepted = {
        CaptureLane.manual: _accepted(CaptureLane.manual),
        CaptureLane.automatic: _accepted(CaptureLane.automatic, ordinal: 2),
      };
      for (final row in CaptureFaultLifecycleMatrix.generate()) {
        final attempt = accepted[row.lane]!;
        final request = _commit(attempt, [
          _component(CaptureComponentKind.jpeg),
          _component(CaptureComponentKind.dng)
        ]);
        final execution =
            CaptureFaultLifecycleMatrix.execute(row, attempt, request);
        expect(execution.outcome, row.expectedOutcome,
            reason:
                '${row.lane}/${row.phase}/${row.fault}/${row.lifecycleEvent}');
        expect(execution.exactReplay, isTrue);
        expect(execution.changedReplayConflict, isTrue);
        expect(execution.lifecycleFenceNoOp, isTrue);
        expect(
            execution.lateCallbackNoOp, row.fault == CaptureFault.lateCallback);
        expect(execution.exposureCount,
            row.phase == CaptureAttemptPhase.reservedAccepted ? 0 : 1);
        outcomeCounts.update(execution.outcome, (value) => value + 1,
            ifAbsent: () => 1);
      }
      expect(outcomeCounts, {
        CaptureMatrixOutcome.abandoned: 396,
        CaptureMatrixOutcome.outcomeUnknown: 108,
        CaptureMatrixOutcome.committed: 36,
      });

      final committed = CaptureFaultLifecycleCase(
          CaptureLane.manual,
          CaptureAttemptPhase.durablePrepared,
          CaptureFault.timeout,
          CaptureLifecycleEvent.backgrounded,
          CaptureMatrixOutcome.committed);
      final mutated = CaptureFaultLifecycleCase(
          committed.lane,
          committed.phase,
          CaptureFault.componentFailure,
          committed.lifecycleEvent,
          CaptureMatrixOutcome.abandoned);
      final attempt = accepted[CaptureLane.manual]!;
      expect(
          CaptureFaultLifecycleMatrix.execute(
              committed,
              attempt,
              _commit(attempt, [
                _component(CaptureComponentKind.jpeg),
                _component(CaptureComponentKind.dng)
              ])).outcome,
          isNot(CaptureFaultLifecycleMatrix.execute(
              mutated,
              attempt,
              _commit(attempt, [
                _component(CaptureComponentKind.jpeg),
                _component(CaptureComponentKind.dng)
              ])).outcome));
    });
  });
}

CaptureIntent _intent(CaptureLane lane, {bool selector = true}) =>
    CaptureIntent(
      lane: lane,
      lifecycleCut: CaptureLifecycleCut(
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
      ),
      profile: CaptureComponentProfile(
        profileId: 'raw+jpeg',
        requiredComponents: const {
          CaptureComponentKind.jpeg,
          CaptureComponentKind.dng
        },
        maximumComponentBytes: 200,
        maximumWorkingBytes: 60 * 1024 * 1024,
      ),
      selectorValid: selector,
      trackingValid: true,
      captureHealthy: true,
      durabilityPreflightValid: true,
      canonicalIntentHash: _digest('intent-${lane.name}'),
    );

CaptureAdmissionReferenceModel _admissionModel() =>
    CaptureAdmissionReferenceModel(
      protectedManualMemoryBytes: 64 * 1024 * 1024,
    );

CaptureLifecycleCut _cut() => CaptureLifecycleCut(
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

CaptureAcceptedAttempt _accepted(CaptureLane lane, {int ordinal = 1}) =>
    CaptureAcceptedAttempt(
      identity: CaptureAttemptIdentity(
          attemptId: 'attempt-$ordinal',
          commitId: 'commit-$ordinal',
          attemptOrdinal: ordinal,
          lifecycleCut: _cut()),
      lane: lane,
      profile: CaptureComponentProfile(
          profileId: 'raw+jpeg',
          requiredComponents: const {
            CaptureComponentKind.jpeg,
            CaptureComponentKind.dng
          },
          maximumComponentBytes: 200,
          maximumWorkingBytes: 1024),
      reservation: CaptureReservationLiability(
          memoryBytes: 1024,
          physicalStoreBytes: 200,
          componentEntries: 2,
          terminalEntries: 1,
          rollbackBytes: 0,
          physicallyBacked: true),
      canonicalIntentHash: _digest('intent-$ordinal'),
      acceptedReceiptHash: _digest('accepted-$ordinal'),
    );

CaptureReservationLiability _liability(
        {int memory = 60 * 1024 * 1024, int store = 200}) =>
    CaptureReservationLiability(
      memoryBytes: memory,
      physicalStoreBytes: store,
      componentEntries: 2,
      terminalEntries: 1,
      rollbackBytes: 0,
      physicallyBacked: true,
    );

CaptureComponentDescriptor _component(CaptureComponentKind kind,
        {int bytes = 40}) =>
    CaptureComponentDescriptor(
      kind: kind,
      byteLength: bytes,
      sha256: _digest('sha-${kind.name}-$bytes'),
      durableObjectId: 'object-${kind.name}',
    );

CaptureCommitRequest _commit(CaptureAcceptedAttempt accepted,
        List<CaptureComponentDescriptor> components) =>
    CaptureCommitRequest(
      accepted: accepted,
      components: components,
      exposureTimestampNanoseconds: 10,
      poseRecordHash: _digest('pose'),
      cameraModelHash: _digest('camera'),
      validationRecordHash: _digest('validation'),
      ledgerRecordHash: _digest('ledger'),
    );

List<int> _digest(String value) => List<int>.generate(
      32,
      (index) => value.codeUnitAt(index % value.length) ^ index,
      growable: false,
    );
