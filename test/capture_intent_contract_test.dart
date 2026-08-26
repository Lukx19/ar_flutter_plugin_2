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
      expect(
          matrix.every((value) =>
              value.expectedTerminal == CaptureTerminalKind.abandonedAttempt),
          isTrue);
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
  });
}

CaptureIntent _intent(CaptureLane lane, {bool selector = true}) =>
    CaptureIntent(
      lane: lane,
      lifecycleCut: const CaptureLifecycleCut(
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
      canonicalIntentHash: 'intent-${lane.name}',
    );

CaptureAdmissionReferenceModel _admissionModel() =>
    CaptureAdmissionReferenceModel(
      protectedManualMemoryBytes: 64 * 1024 * 1024,
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
      sha256: 'sha-${kind.name}-$bytes',
      durableObjectId: 'object-${kind.name}',
    );

CaptureCommitRequest _commit(CaptureAcceptedAttempt accepted,
        List<CaptureComponentDescriptor> components) =>
    CaptureCommitRequest(
      accepted: accepted,
      components: components,
      exposureTimestampNanoseconds: 10,
      poseRecordHash: 'pose',
      cameraModelHash: 'camera',
      validationRecordHash: 'validation',
      ledgerRecordHash: 'ledger',
    );
