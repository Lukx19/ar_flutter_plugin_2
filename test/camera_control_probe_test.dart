import 'dart:async';

import 'package:ar_flutter_plugin_2/capabilities/camera_capability_report.dart';
import 'package:ar_flutter_plugin_2/capabilities/camera_control_probe.dart';
import 'package:ar_flutter_plugin_2/managers/ar_capture_manager.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  CameraCapabilityReport metadata(Map<String, bool?> controls) =>
      CameraCapabilityReport.fromMap({'controls': controls});

  test('unknown controls and exhausted budget never mutate camera', () async {
    final camera = _ProbeCamera();
    final unknown = await CameraControlProbe.run(camera,
        capabilities: const CameraCapabilityReport.unknown());
    expect(camera.mutations, isEmpty);
    expect(unknown.controls['iso']?['supported'], isNull);
    final expired = await CameraControlProbe.run(camera,
        capabilities: metadata({'iso': true}), budget: Duration.zero);
    expect(camera.mutations, isEmpty);
    expect(expired.controls['iso']?['status'], 'not_tested');
    expect(expired.toMap()['sensorConfirmed'], false);
  });

  test('ISO checks alternate response and restores original auto state',
      () async {
    final camera = _ProbeCamera();
    final result = await CameraControlProbe.run(camera,
        capabilities: metadata({'iso': true}));
    expect(result.controls['iso'], containsPair('requested', 200));
    expect(result.controls['iso'], containsPair('readbackMatched', true));
    expect(result.controls['iso'], containsPair('restored', true));
    expect(result.controls['iso'], containsPair('sensorConfirmed', false));
    expect(camera.iso, 100);
    expect(camera.auto, true);
    expect(camera.mutations.first, 'iso:200');
    expect(result.restored, true);
  });

  test('refused mutation still restores and does not mark hardware unsupported',
      () async {
    final camera = _ProbeCamera()..refuseFirst = true;
    final result = await CameraControlProbe.run(camera,
        capabilities: metadata({'iso': true}));
    expect(result.controls['iso']?['status'], 'inconclusive');
    expect(result.controls['iso']?['supported'], true);
    expect(result.controls['iso']?['restored'], true);
    expect(camera.iso, 100);
    expect(camera.auto, true);
  });

  test('all advertised controls make alternate checks before restoring',
      () async {
    final camera = _ProbeCamera();
    final result = await CameraControlProbe.run(camera,
        capabilities: metadata({
          'iso': true,
          'exposureTime': true,
          'focus': true,
          'whiteBalance': true,
          'exposureCompensation': true,
        }));
    for (final name in [
      'iso',
      'exposureTime',
      'focus',
      'whiteBalance',
      'exposureCompensation'
    ]) {
      expect(result.controls[name]?['readbackMatched'], true, reason: name);
      expect(result.controls[name]?['restored'], true, reason: name);
      expect(result.controls[name]?['sensorConfirmed'], false, reason: name);
    }
    expect(result.controls['hdr']?['status'], 'inconclusive');
    expect(camera.iso, 100);
    expect(camera.duration.inMicroseconds, 10000);
    expect(camera.focus, .4);
    expect(camera.focusMode, FocusMode.continuous);
    expect(camera.wb, WhiteBalanceMode.auto);
    expect(camera.compensation, 0);
  });

  test('cancellation after mutation still awaits restoration', () async {
    var current = true;
    final camera = _ProbeCamera()
      ..onMutation = () {
        current = false;
      };
    final result = await CameraControlProbe.run(camera,
        capabilities: metadata({'iso': true, 'exposureTime': true}),
        isCurrent: () => current);
    expect(result.controls['iso']?['restored'], true);
    expect(result.controls['exposureTime']?['status'], 'not_tested');
    expect(camera.iso, 100);
    expect(camera.auto, true);
  });

  test('HDR mechanisms stay unverified without actual mode output evidence',
      () async {
    Future<CameraControlProbeReport> probe(Map<String, bool?> hdr) =>
        CameraControlProbe.run(_ProbeCamera(),
            capabilities: CameraCapabilityReport.fromMap({'hdrSupport': hdr}));
    final bracket = await probe({
      'pipelinePhoto': null,
      'mergedPhoto': null,
      'exposureBracketing': true,
      'balancedPhotoProcessing': false,
    });
    expect(bracket.controls['hdr']?['status'], 'not_tested');
    expect(bracket.controls['hdr']?['mechanismAvailable'], true);
    expect(bracket.controls['hdr']?['supported'], isNull);
    expect(bracket.controls['hdr']?['setterAccepted'], isNull);
    expect(
        bracket.controls['hdr']?['error'], 'missing_mode_output_observation');
    final balanced = await probe({
      'pipelinePhoto': null,
      'mergedPhoto': null,
      'exposureBracketing': false,
      'balancedPhotoProcessing': true,
      'balancedPhotoMayFallback': true,
    });
    expect(balanced.controls['hdr']?['status'], 'not_tested');
    expect(balanced.controls['hdr']?['readbackMatched'], isNull);
    expect(balanced.controls['hdr']?['sensorConfirmed'], false);
    final unknown = await probe({'pipelinePhoto': false});
    expect(unknown.controls['hdr']?['status'], 'inconclusive');
    final none = await probe({
      'pipelinePhoto': false,
      'mergedPhoto': false,
      'exposureBracketing': false,
      'balancedPhotoProcessing': false,
    });
    expect(none.controls['hdr']?['status'], 'pipeline_unavailable');
    expect(none.controls['hdr']?.containsKey('error'), false);
  });

  for (final control in ['focus', 'whiteBalance', 'exposureCompensation']) {
    test(
        'delayed $control observation is awaited before restore and completion',
        () async {
      final camera = _DelayedObservationCamera();
      try {
        final result = await CameraControlProbe.run(camera,
            capabilities: metadata({control: true}),
            budget: const Duration(seconds: 1));
        expect(result.controls[control]?['status'], 'response_verified');
        expect(result.controls[control]?['setterAccepted'], true);
        expect(result.controls[control]?['readbackMatched'], true);
        expect(result.controls[control]?['restored'], true);
        expect(result.controls[control]?['sensorConfirmed'], false);
        expect(camera.observations, contains('$control:alternate'));
        expect(camera.observations.last, '$control:restored',
            reason:
                'The owner must not close while restoration still awaits its callback');
      } finally {
        await camera.settleObservations();
      }
    });
  }

  test('fractional EV metadata step is preserved through request and response',
      () async {
    final camera = _FractionalStepCamera();
    final result = await CameraControlProbe.run(camera,
        capabilities: metadata({'exposureCompensation': true}));
    expect(result.controls['exposureCompensation']?['requested'], .1);
    expect(result.controls['exposureCompensation']?['readback'], .1);
    expect(result.controls['exposureCompensation']?['readbackMatched'], true);
    expect(result.controls['exposureCompensation']?['restored'], true);
  });

  test('cancel during unsettled setter still awaits mutation and restoration',
      () async {
    var current = true;
    var completed = false;
    final camera = _PendingMutationCamera();
    final pending = CameraControlProbe.run(camera,
        capabilities: metadata({'focus': true, 'whiteBalance': true}),
        isCurrent: () => current).then((result) {
      completed = true;
      return result;
    });
    await camera.started.future;
    current = false;
    await Future<void>.delayed(Duration.zero);
    expect(completed, false);
    expect(camera.restorationRequested, false);
    camera.release.complete();
    final result = await pending;
    expect(camera.restorationRequested, true);
    expect(result.controls['focus']?['restored'], true);
    expect(result.controls['whiteBalance']?['status'], 'not_tested');
  });

  for (final control in ['iso', 'exposureTime']) {
    test('delayed $control observations use the same settlement window',
        () async {
      final camera = _DelayedObservationCamera();
      try {
        final result = await CameraControlProbe.run(camera,
            capabilities: metadata({control: true}));
        expect(result.controls[control]?['readbackMatched'], true);
        expect(result.controls[control]?['restored'], true);
        expect(result.controls[control]?['sensorConfirmed'], false);
      } finally {
        await camera.settleObservations();
      }
    });
  }

  test('never matching observation is bounded and remains inconclusive',
      () async {
    final camera = _NeverMatchingCamera();
    final elapsed = Stopwatch()..start();
    final result = await CameraControlProbe.run(camera,
        capabilities: metadata({'focus': true}));
    expect(result.controls['focus']?['status'], 'inconclusive');
    expect(result.controls['focus']?['setterAccepted'], true);
    expect(result.controls['focus']?['readbackMatched'], false);
    expect(result.controls['focus']?['readback'], .4);
    expect(result.controls['focus']?['restored'], true);
    // Poll counts vary at fractional timer deadlines. The contract is bounded
    // elapsed time and no reads after completion, rather than a fixed count.
    expect(camera.focusReads, greaterThanOrEqualTo(2));
    expect(elapsed.elapsed,
        greaterThanOrEqualTo(const Duration(milliseconds: 200)));
    expect(elapsed.elapsed, lessThan(const Duration(seconds: 2)));
    final readsAtCompletion = camera.focusReads;
    await Future<void>.delayed(const Duration(milliseconds: 60));
    expect(camera.focusReads, readsAtCompletion);
  });

  test('expired admission stops response reads and prevents next mutation',
      () async {
    final camera = _NeverMatchingCamera();
    final result = await CameraControlProbe.run(camera,
        capabilities: metadata({'focus': true, 'whiteBalance': true}),
        budget: const Duration(milliseconds: 40));
    expect(result.controls['focus']?['status'], 'inconclusive');
    expect(result.controls['focus']?['restored'], true);
    expect(result.controls['whiteBalance']?['status'], 'not_tested');
    expect(camera.wb, WhiteBalanceMode.auto);
    final readsAtCompletion = camera.focusReads;
    await Future<void>.delayed(const Duration(milliseconds: 60));
    expect(camera.focusReads, readsAtCompletion);
  });

  test('delayed original restoration is observed even after cancellation',
      () async {
    var current = true;
    final camera = _DelayedObservationCamera(
      restoreDelay: const Duration(milliseconds: 80),
    )..onAlternateObserved = () {
        current = false;
      };
    try {
      final result = await CameraControlProbe.run(camera,
          capabilities: metadata({'focus': true, 'whiteBalance': true}),
          isCurrent: () => current);
      expect(result.controls['focus']?['restored'], true);
      expect(result.controls['whiteBalance']?['status'], 'not_tested');
      expect(camera.observations.last, 'focus:restored');
      expect(camera.wb, WhiteBalanceMode.auto);
    } finally {
      await camera.settleObservations();
    }
  });

  test('pending getter settles before restoration and terminal return',
      () async {
    var current = true;
    var completed = false;
    final camera = _PendingObservationCamera();
    final pending = CameraControlProbe.run(camera,
        capabilities: metadata({'focus': true, 'whiteBalance': true}),
        isCurrent: () => current).then((value) {
      completed = true;
      return value;
    });
    await camera.readStarted.future;
    current = false;
    await Future<void>.delayed(Duration.zero);
    expect(completed, false);
    expect(camera.restorationRequested, false);
    camera.release.complete();
    final result = await pending;
    expect(result.controls['focus']?['restored'], true);
    expect(result.controls['whiteBalance']?['status'], 'not_tested');
    final readsAtCompletion = camera.reads;
    await Future<void>.delayed(const Duration(milliseconds: 60));
    expect(camera.reads, readsAtCompletion);
  });

  test(
      'original observation mismatch stops later mutations after restore window',
      () async {
    final camera = _NeverRestoringCamera();
    final result = await CameraControlProbe.run(camera,
        capabilities: metadata({'focus': true, 'whiteBalance': true}));
    expect(result.controls['focus']?['readbackMatched'], true);
    expect(result.controls['focus']?['restored'], false);
    expect(result.restored, false);
    expect(result.controls.containsKey('whiteBalance'), false);
    expect(camera.wb, WhiteBalanceMode.auto);
  });

  test('restoration refusal stops remaining mutations', () async {
    final camera = _ProbeCamera()..refuseRestore = true;
    final result = await CameraControlProbe.run(camera,
        capabilities: metadata({'iso': true, 'exposureTime': true}));
    expect(result.controls['iso']?['restored'], false);
    expect(result.restored, false);
    expect(result.controls.containsKey('exposureTime'), false);
    expect(camera.mutations.where((value) => value.startsWith('time:')),
        ['time:10000']);
  });
}

class _ProbeCamera implements ARCaptureManager {
  int iso = 100;
  Duration duration = const Duration(milliseconds: 10);
  bool auto = true;
  bool refuseFirst = false;
  bool refuseRestore = false;
  final mutations = <String>[];
  void Function()? onMutation;
  double focus = .4;
  FocusMode focusMode = FocusMode.continuous;
  bool autofocus = true;
  WhiteBalanceMode wb = WhiteBalanceMode.auto;
  double compensation = 0;

  @override
  bool get isEnabled => true;
  @override
  Future<CameraExposureState> getCurrentExposureState() async =>
      CameraExposureState(
          currentISO: iso,
          currentExposureTime: duration,
          isAutoExposureEnabled: auto,
          isExposureLocked: false,
          exposureCompensation: compensation,
          exposureMode: auto ? ExposureMode.auto : ExposureMode.manual);
  @override
  Future<List<int>?> getSupportedISORange() async => [100, 200, 400];
  @override
  Future<int?> setISO(int value, {bool temporary = true}) async {
    mutations.add('iso:$value');
    onMutation?.call();
    iso = value;
    auto = false;
    if (refuseFirst) {
      refuseFirst = false;
      throw StateError('response refused');
    }
    if (refuseRestore && value == 100) throw StateError('restore refused');
    return iso;
  }

  @override
  Future<Duration?> setExposureTime(Duration value,
      {bool temporary = true}) async {
    mutations.add('time:${value.inMicroseconds}');
    duration = value;
    auto = false;
    return duration;
  }

  @override
  Future<bool> setAutoExposureEnabled(bool value) async => auto = value;
  @override
  Future<bool> unlockExposure() async => true;
  @override
  Future<Map<String, Duration>?> getSupportedExposureRange() async => {
        'min': const Duration(microseconds: 100),
        'max': const Duration(seconds: 1),
      };
  @override
  Future<CameraFocusState> getCurrentFocusState() async => CameraFocusState(
        currentFocusDistance: focus,
        isAutofocusEnabled: autofocus,
        currentFocusMode: focusMode,
        isFocusLocked: !autofocus,
        isFocusPeakingEnabled: false,
        focusStatus: FocusStatus.inactive,
      );
  @override
  Future<double?> setFocusDistance(double distance) async {
    focus = distance;
    autofocus = false;
    focusMode = FocusMode.fixed;
    return distance;
  }

  @override
  Future<bool> setFocusMode(FocusMode value) async {
    focusMode = value;
    return true;
  }

  @override
  Future<bool> setAutofocusEnabled(bool value) async => autofocus = value;
  @override
  Future<CameraWhiteBalanceState> getCurrentWhiteBalanceState() async =>
      CameraWhiteBalanceState(
        currentMode: wb,
        isWhiteBalanceLocked: false,
        isAutoWhiteBalanceEnabled: wb == WhiteBalanceMode.auto,
        status: WhiteBalanceStatus.converged,
      );
  @override
  Future<bool> setWhiteBalanceMode(WhiteBalanceMode value) async {
    wb = value;
    return true;
  }

  @override
  Future<bool> unlockWhiteBalance() async => true;
  @override
  Future<ExposureCompensationInfo?> getExposureCompensationInfo() async =>
      ExposureCompensationInfo(
        minCompensation: -2,
        maxCompensation: 2,
        currentCompensation: compensation,
        stepSize: 1,
      );
  @override
  Future<double?> setExposureCompensation(double value) async =>
      compensation = value;
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// Native bridges acknowledge staging before a newer TotalCaptureResult.
/// Focus's setter reply itself may still contain the prior observed position.
class _DelayedObservationCamera extends _FractionalStepCamera {
  _DelayedObservationCamera(
      {this.restoreDelay = const Duration(milliseconds: 20)});
  final Duration restoreDelay;
  void Function()? onAlternateObserved;
  int _observedISO = 100;
  Duration _observedDuration = const Duration(milliseconds: 10);
  bool _observedAuto = true;
  final observations = <String>[];
  final _pending = <Future<void>>[];
  double _observedFocus = .4;
  FocusMode _observedFocusMode = FocusMode.continuous;
  bool _observedAutofocus = true;
  WhiteBalanceMode _observedWhiteBalance = WhiteBalanceMode.auto;
  double _observedCompensation = 0;

  void _enqueue(void Function() callback, {bool restoring = false}) {
    _pending.add(Future<void>.delayed(
        restoring ? restoreDelay : const Duration(milliseconds: 20), callback));
  }

  Future<void> settleObservations() async {
    await Future.wait(_pending);
  }

  @override
  Future<CameraFocusState> getCurrentFocusState() async => CameraFocusState(
        currentFocusDistance: _observedFocus,
        isAutofocusEnabled: _observedAutofocus,
        currentFocusMode: _observedFocusMode,
        isFocusLocked: !_observedAutofocus,
        isFocusPeakingEnabled: false,
        focusStatus: FocusStatus.inactive,
      );
  @override
  Future<double?> setFocusDistance(double value) async {
    final previous = _observedFocus;
    await super.setFocusDistance(value);
    _enqueue(() {
      _observedFocus = value;
      _observedAutofocus = false;
      _observedFocusMode = FocusMode.fixed;
      observations.add('focus:${value == .4 ? 'restored' : 'alternate'}');
      if (value != .4) onAlternateObserved?.call();
    }, restoring: value == .4);
    return previous;
  }

  @override
  Future<bool> setFocusMode(FocusMode value) async {
    await super.setFocusMode(value);
    _enqueue(() {
      _observedFocusMode = value;
    }, restoring: value == FocusMode.continuous);
    return true;
  }

  @override
  Future<bool> setAutofocusEnabled(bool value) async {
    await super.setAutofocusEnabled(value);
    _enqueue(() {
      _observedAutofocus = value;
    }, restoring: value);
    return value;
  }

  @override
  Future<CameraWhiteBalanceState> getCurrentWhiteBalanceState() async =>
      CameraWhiteBalanceState(
        currentMode: _observedWhiteBalance,
        isWhiteBalanceLocked: false,
        isAutoWhiteBalanceEnabled:
            _observedWhiteBalance == WhiteBalanceMode.auto,
        status: WhiteBalanceStatus.converged,
      );
  @override
  Future<bool> setWhiteBalanceMode(WhiteBalanceMode value) async {
    await super.setWhiteBalanceMode(value);
    _enqueue(() {
      _observedWhiteBalance = value;
      observations.add(
          'whiteBalance:${value == WhiteBalanceMode.auto ? 'restored' : 'alternate'}');
    }, restoring: value == WhiteBalanceMode.auto);
    return true;
  }

  @override
  Future<CameraExposureState> getCurrentExposureState() async =>
      CameraExposureState(
        currentISO: _observedISO,
        currentExposureTime: _observedDuration,
        isAutoExposureEnabled: _observedAuto,
        isExposureLocked: false,
        exposureCompensation: _observedCompensation,
        exposureMode: _observedAuto ? ExposureMode.auto : ExposureMode.manual,
      );
  @override
  Future<int?> setISO(int value, {bool temporary = true}) async {
    final applied = await super.setISO(value, temporary: temporary);
    _enqueue(() {
      _observedISO = value;
      _observedAuto = false;
    }, restoring: value == 100);
    return applied;
  }

  @override
  Future<Duration?> setExposureTime(Duration value,
      {bool temporary = true}) async {
    final applied = await super.setExposureTime(value, temporary: temporary);
    _enqueue(() {
      _observedDuration = value;
      _observedAuto = false;
    }, restoring: value == const Duration(milliseconds: 10));
    return applied;
  }

  @override
  Future<bool> setAutoExposureEnabled(bool value) async {
    await super.setAutoExposureEnabled(value);
    _enqueue(() {
      _observedAuto = value;
    }, restoring: value);
    return value;
  }

  @override
  Future<double?> setExposureCompensation(double value) async {
    final staged = await super.setExposureCompensation(value);
    _enqueue(() {
      _observedCompensation = value;
      observations
          .add('exposureCompensation:${value == 0 ? 'restored' : 'alternate'}');
    }, restoring: value == 0);
    return staged;
  }
}

class _FractionalStepCamera extends _ProbeCamera {
  @override
  Future<ExposureCompensationInfo?> getExposureCompensationInfo() async =>
      ExposureCompensationInfo.fromMap({
        'minCompensation': -2.0,
        'maxCompensation': 2.0,
        'currentCompensation': compensation,
        'stepSize': .1,
      });
}

class _PendingMutationCamera extends _ProbeCamera {
  final started = Completer<void>();
  final release = Completer<void>();
  bool restorationRequested = false;
  @override
  Future<double?> setFocusDistance(double value) async {
    if (!started.isCompleted) {
      started.complete();
      await release.future;
    } else {
      restorationRequested = true;
    }
    return super.setFocusDistance(value);
  }
}

class _NeverMatchingCamera extends _ProbeCamera {
  int focusReads = 0;
  @override
  Future<CameraFocusState> getCurrentFocusState() async {
    focusReads++;
    return const CameraFocusState(
        currentFocusDistance: .4,
        isAutofocusEnabled: true,
        currentFocusMode: FocusMode.continuous,
        isFocusLocked: false,
        isFocusPeakingEnabled: false,
        focusStatus: FocusStatus.inactive);
  }
}

class _NeverRestoringCamera extends _ProbeCamera {
  bool alternateRequested = false;
  @override
  Future<double?> setFocusDistance(double value) async {
    alternateRequested = true;
    return super.setFocusDistance(value);
  }

  @override
  Future<CameraFocusState> getCurrentFocusState() async {
    if (!alternateRequested) return super.getCurrentFocusState();
    return const CameraFocusState(
        currentFocusDistance: .5,
        isAutofocusEnabled: false,
        currentFocusMode: FocusMode.fixed,
        isFocusLocked: true,
        isFocusPeakingEnabled: false,
        focusStatus: FocusStatus.inactive);
  }
}

class _PendingObservationCamera extends _ProbeCamera {
  final readStarted = Completer<void>();
  final release = Completer<void>();
  int reads = 0;
  bool restorationRequested = false;
  bool alternateRequested = false;
  @override
  Future<double?> setFocusDistance(double value) async {
    if (alternateRequested) restorationRequested = true;
    alternateRequested = true;
    return super.setFocusDistance(value);
  }

  @override
  Future<CameraFocusState> getCurrentFocusState() async {
    reads++;
    if (alternateRequested && !readStarted.isCompleted) {
      readStarted.complete();
      await release.future;
    }
    return super.getCurrentFocusState();
  }
}
