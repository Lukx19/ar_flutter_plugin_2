import '../managers/ar_capture_manager.dart';
import 'camera_capability_report.dart';

/// Response checks on an owned disposable setup camera, after its test photo.
///
/// The budget stops admitting controls. In-flight calls and restoration always
/// settle before completion: applying Future.timeout to this future would leave
/// a mutation racing camera shutdown. Cancellation belongs to the camera owner.
/// Readback may be requested bridge state, so it never certifies sensor output.
class CameraControlProbe {
  static Future<CameraControlProbeReport> run(
    ARCaptureManager manager, {
    required CameraCapabilityReport capabilities,
    Duration budget = const Duration(seconds: 2),
    bool Function()? isCurrent,
  }) async {
    final clock = Stopwatch()..start();
    final results = <String, Map<String, Object?>>{};
    bool mayStart() =>
        manager.isEnabled &&
        clock.elapsed < budget &&
        (isCurrent?.call() ?? true);
    Future<void> check(
      String key,
      Future<Map<String, Object?>> Function() action,
    ) async {
      final supported = capabilities.controls[key];
      final result = <String, Object?>{
        'supported': supported,
        'setterAccepted': null,
        'readbackMatched': null,
        'restored': null,
        'sensorConfirmed': false,
        'status': supported == false ? 'not_advertised' : 'not_tested',
      };
      results[key] = result;
      if (supported != true || !mayStart()) return;
      try {
        result.addAll(await action());
      } catch (_) {
        // The action restores in finally. A refusal does not prove hardware
        // unsupported, and native exception text can contain private values.
        result['status'] = 'inconclusive';
        result['error'] = 'control_response_failed';
      }
    }

    Future<T?> settle<T>(Future<T> Function() action) async {
      try {
        return await action();
      } catch (_) {
        return null;
      }
    }

    // Native setters can acknowledge staging before the next camera result.
    // The window bounds admission of reads; every admitted call is awaited.
    Future<T?> observe<T>(
      Future<T> Function() read,
      bool Function(T) matches, {
      bool restoring = false,
      void Function()? readFailed,
    }) async {
      const window = Duration(milliseconds: 250);
      const interval = Duration(milliseconds: 20);
      final elapsed = Stopwatch()..start();
      T? last;
      // Let already acknowledged restore requests reach the observation path
      // before accepting an original value that may still be a stale sample.
      // This remains a bridge response check, without sensor timestamp proof.
      if (restoring) await Future<void>.delayed(interval);
      while (manager.isEnabled &&
          elapsed.elapsed < window &&
          (restoring || mayStart())) {
        try {
          final value = await read();
          last = value;
          if (matches(value)) return value;
        } catch (_) {
          readFailed?.call();
          return last;
        }
        final remaining = window - elapsed.elapsed;
        final admissionRemaining =
            restoring ? remaining : budget - clock.elapsed;
        if (remaining <= Duration.zero ||
            admissionRemaining <= Duration.zero ||
            (!restoring && !mayStart())) break;
        final delay = remaining < interval ? remaining : interval;
        await Future<void>.delayed(
            admissionRemaining < delay ? admissionRemaining : delay);
      }
      return last;
    }

    Future<Map<String, Object?>> exposure(String key) async {
      final before = await manager.getCurrentExposureState();
      final iso = before.currentISO;
      final duration = before.currentExposureTime;
      if (iso == null ||
          duration == null ||
          iso <= 0 ||
          duration <= Duration.zero) {
        return {
          'status': 'inconclusive',
          'error': 'original_state_unavailable'
        };
      }
      final result = <String, Object?>{'status': 'inconclusive'};
      var mutated = false;
      try {
        if (key == 'iso') {
          final values = await manager.getSupportedISORange();
          if (values == null || values.isEmpty) return {'status': 'not_tested'};
          final alternatives = values
              .where((value) => value > 0 && value != iso)
              .toList()
            ..sort((a, b) => (a - iso).abs().compareTo((b - iso).abs()));
          if (alternatives.isEmpty || !mayStart()) {
            return {'status': 'not_tested'};
          }
          final requested = alternatives.first;
          mutated = true;
          final applied = await manager.setISO(requested);
          result['setterAccepted'] = applied != null;
          final observation = applied == null
              ? null
              : await observe(
                  manager.getCurrentExposureState,
                  (state) =>
                      state.currentISO != null &&
                      (state.currentISO! - requested).abs() <=
                          (requested * .05).ceil(),
                  readFailed: () => result['error'] = 'control_readback_failed',
                );
          final readback = observation?.currentISO;
          result['requested'] = requested;
          result['acknowledgedValue'] = applied;
          result['readback'] = readback;
          result['readbackMatched'] = applied == requested &&
              readback != null &&
              (readback - requested).abs() <= (requested * .05).ceil();
        } else {
          final range = await manager.getSupportedExposureRange();
          final low = range?['min']?.inMicroseconds;
          final high = range?['max']?.inMicroseconds;
          if (low == null ||
              high == null ||
              low <= 0 ||
              high <= low ||
              !mayStart()) {
            return {'status': 'not_tested'};
          }
          final original = duration.inMicroseconds;
          final requested = (original * .8).round().clamp(low, high);
          if (requested == original) return {'status': 'not_tested'};
          mutated = true;
          final applied =
              await manager.setExposureTime(Duration(microseconds: requested));
          result['setterAccepted'] = applied != null;
          final observation = applied == null
              ? null
              : await observe(
                  manager.getCurrentExposureState,
                  (state) =>
                      state.currentExposureTime != null &&
                      (state.currentExposureTime!.inMicroseconds - requested)
                              .abs() <=
                          (requested * .05).ceil(),
                  readFailed: () => result['error'] = 'control_readback_failed',
                );
          final readback = observation?.currentExposureTime?.inMicroseconds;
          result['acknowledgedValue'] = applied?.inMicroseconds;
          result['requested'] = requested;
          result['readback'] = readback;
          result['readbackMatched'] = applied?.inMicroseconds == requested &&
              readback != null &&
              (readback - requested).abs() <= (requested * .05).ceil();
        }
        result['status'] = result['readbackMatched'] == true
            ? 'response_verified'
            : 'inconclusive';
      } catch (_) {
        result['error'] = 'control_response_failed';
      } finally {
        if (mutated) {
          try {
            final restoredISO = await settle(() => manager.setISO(iso));
            final restoredDuration =
                await settle(() => manager.setExposureTime(duration));
            final restoredAuto = await settle(() =>
                manager.setAutoExposureEnabled(before.isAutoExposureEnabled));
            final lockRestored = before.isExposureLocked
                ? await settle(manager.lockExposure)
                : true;
            if (!before.isExposureLocked && before.isAutoExposureEnabled) {
              await settle(manager.unlockExposure);
            }
            bool originalMatches(CameraExposureState after) =>
                after.isAutoExposureEnabled == before.isAutoExposureEnabled &&
                after.isExposureLocked == before.isExposureLocked &&
                (before.isAutoExposureEnabled ||
                    (after.currentISO == iso &&
                        after.currentExposureTime == duration));
            final after = await observe(
                manager.getCurrentExposureState, originalMatches,
                restoring: true);
            result['restored'] = lockRestored == true &&
                restoredISO != null &&
                restoredDuration != null &&
                restoredAuto == before.isAutoExposureEnabled &&
                after != null &&
                originalMatches(after);
          } catch (_) {
            result['restored'] = false;
          }
        }
      }
      return result;
    }

    await check('iso', () => exposure('iso'));
    if (results['iso']?['restored'] == false) {
      return CameraControlProbeReport(results);
    }
    await check('exposureTime', () => exposure('exposureTime'));
    if (results['exposureTime']?['restored'] == false) {
      return CameraControlProbeReport(results);
    }
    await check('focus', () async {
      final before = await manager.getCurrentFocusState();
      final original = before.currentFocusDistance;
      if (original == null ||
          original < 0 ||
          original > 1 ||
          before.focusRegion != null ||
          !mayStart()) {
        return {
          'status': 'inconclusive',
          'error': 'original_state_unavailable'
        };
      }
      final requested = original < .5 ? (original + .1) : (original - .1);
      final result = <String, Object?>{'status': 'inconclusive'};
      try {
        final applied = await manager.setFocusDistance(requested);
        final observation = applied == null
            ? null
            : await observe(
                manager.getCurrentFocusState,
                (state) =>
                    state.currentFocusDistance != null &&
                    (state.currentFocusDistance! - requested).abs() < .02,
                readFailed: () => result['error'] = 'control_readback_failed',
              );
        final readback = observation?.currentFocusDistance;
        result.addAll({
          'setterAccepted': applied != null,
          'acknowledgedValue': applied,
          'requested': requested,
          'readback': readback,
          // Focus's acknowledgement may contain the previous observed value.
          'readbackMatched': applied != null &&
              readback != null &&
              (readback - requested).abs() < .02,
        });
        result['status'] = result['readbackMatched'] == true
            ? 'response_verified'
            : 'inconclusive';
      } catch (_) {
        result['error'] = 'control_response_failed';
      } finally {
        try {
          final distanceRestored =
              await settle(() => manager.setFocusDistance(original));
          final modeRestored =
              await settle(() => manager.setFocusMode(before.currentFocusMode));
          final autoRestored = await settle(
              () => manager.setAutofocusEnabled(before.isAutofocusEnabled));
          bool originalMatches(CameraFocusState after) =>
              after.isAutofocusEnabled == before.isAutofocusEnabled &&
              after.currentFocusMode == before.currentFocusMode &&
              (before.isAutofocusEnabled ||
                  (after.currentFocusDistance != null &&
                      (after.currentFocusDistance! - original).abs() < .02));
          final after = await observe(
              manager.getCurrentFocusState, originalMatches,
              restoring: true);
          result['restored'] = distanceRestored != null &&
              modeRestored == true &&
              autoRestored == before.isAutofocusEnabled &&
              after != null &&
              originalMatches(after);
        } catch (_) {
          result['restored'] = false;
        }
      }
      return result;
    });
    if (results['focus']?['restored'] == false) {
      return CameraControlProbeReport(results);
    }
    await check('whiteBalance', () async {
      final before = await manager.getCurrentWhiteBalanceState();
      if ((before.currentMode == WhiteBalanceMode.manual &&
              before.currentColorTemperature == null) ||
          before.isWhiteBalanceLocked) {
        return {
          'status': 'inconclusive',
          'error': 'original_state_unavailable'
        };
      }
      if (!mayStart()) return {'status': 'not_tested'};
      final requested = before.currentMode == WhiteBalanceMode.daylight
          ? WhiteBalanceMode.cloudyDaylight
          : WhiteBalanceMode.daylight;
      final result = <String, Object?>{'status': 'inconclusive'};
      try {
        final applied = await manager.setWhiteBalanceMode(requested);
        final readback = !applied
            ? null
            : await observe(
                manager.getCurrentWhiteBalanceState,
                (state) => state.currentMode == requested,
                readFailed: () => result['error'] = 'control_readback_failed',
              );
        result.addAll({
          'setterAccepted': applied,
          'requested': requested.name,
          'readback': readback?.currentMode.name,
          'readbackMatched': applied && readback?.currentMode == requested,
        });
        result['status'] = result['readbackMatched'] == true
            ? 'response_verified'
            : 'inconclusive';
      } catch (_) {
        result['error'] = 'control_response_failed';
      } finally {
        try {
          final modeRestored = await settle(
              () => manager.setWhiteBalanceMode(before.currentMode));
          if (before.currentMode == WhiteBalanceMode.manual) {
            await settle(() =>
                manager.setColorTemperature(before.currentColorTemperature!));
          }
          if (before.isWhiteBalanceLocked)
            await settle(manager.lockWhiteBalance);
          if (!before.isWhiteBalanceLocked &&
              before.isAutoWhiteBalanceEnabled) {
            await settle(manager.unlockWhiteBalance);
          }
          bool originalMatches(CameraWhiteBalanceState after) =>
              after.currentMode == before.currentMode &&
              after.isWhiteBalanceLocked == before.isWhiteBalanceLocked &&
              (before.currentMode != WhiteBalanceMode.manual ||
                  after.currentColorTemperature ==
                      before.currentColorTemperature);
          final after = await observe(
              manager.getCurrentWhiteBalanceState, originalMatches,
              restoring: true);
          result['restored'] =
              modeRestored == true && after != null && originalMatches(after);
        } catch (_) {
          result['restored'] = false;
        }
      }
      return result;
    });
    if (results['whiteBalance']?['restored'] == false) {
      return CameraControlProbeReport(results);
    }
    await check('exposureCompensation', () async {
      final info = await manager.getExposureCompensationInfo();
      if (info == null || info.stepSize <= 0 || !mayStart()) {
        return {'status': 'not_tested'};
      }
      final original = info.currentCompensation;
      final requested = (original + info.stepSize <= info.maxCompensation)
          ? original + info.stepSize
          : original - info.stepSize;
      if (requested < info.minCompensation || requested == original) {
        return {'status': 'not_tested'};
      }
      final result = <String, Object?>{'status': 'inconclusive'};
      try {
        final applied = await manager.setExposureCompensation(requested);
        final observation = applied == null
            ? null
            : await observe(
                manager.getCurrentExposureState,
                (state) => (state.exposureCompensation - requested).abs() < .01,
                readFailed: () => result['error'] = 'control_readback_failed',
              );
        final readback = observation?.exposureCompensation;
        result.addAll({
          'setterAccepted': applied != null,
          'acknowledgedValue': applied,
          'requested': requested,
          'readback': readback,
          'readbackMatched': applied != null &&
              (applied - requested).abs() < .01 &&
              readback != null &&
              (readback - requested).abs() < .01,
        });
        result['status'] = result['readbackMatched'] == true
            ? 'response_verified'
            : 'inconclusive';
      } catch (_) {
        result['error'] = 'control_response_failed';
      } finally {
        try {
          final restored = await manager.setExposureCompensation(original);
          final after = await observe(manager.getCurrentExposureState,
              (state) => (state.exposureCompensation - original).abs() < .01,
              restoring: true);
          result['restored'] = restored != null &&
              after != null &&
              (after.exposureCompensation - original).abs() < .01;
        } catch (_) {
          result['restored'] = false;
        }
      }
      return result;
    });
    final hdrMechanisms = [
      for (final key in [
        'pipelinePhoto',
        'mergedPhoto',
        'exposureBracketing',
        'balancedPhotoProcessing',
      ])
        capabilities.hdrSupport[key],
    ];
    final hasMechanism = hdrMechanisms.any((value) => value == true);
    final allUnavailable = hdrMechanisms.every((value) => value == false);
    results['hdr'] = {
      // Existing bracket/balanced mechanisms do not prove merged photo HDR.
      'supported': capabilities.hdrSupport['pipelinePhoto'],
      'mechanismAvailable': hasMechanism
          ? true
          : allUnavailable
              ? false
              : null,
      'setterAccepted': null,
      'readbackMatched': null,
      'restored': null,
      'sensorConfirmed': false,
      'status': allUnavailable
          ? 'pipeline_unavailable'
          : hasMechanism
              ? 'not_tested'
              : 'inconclusive',
      if (!allUnavailable) 'error': 'missing_mode_output_observation',
    };
    return CameraControlProbeReport(results);
  }
}

class CameraControlProbeReport {
  CameraControlProbeReport(Map<String, Map<String, Object?>> controls)
      : controls = Map.unmodifiable({
          for (final entry in controls.entries)
            entry.key: Map<String, Object?>.unmodifiable(entry.value),
        });

  final Map<String, Map<String, Object?>> controls;
  bool get restored =>
      controls.values.every((value) => value['restored'] != false);
  Map<String, Object?> toMap() => {
        'schemaVersion': 1,
        'evidence': 'control_response',
        'sensorConfirmed': false,
        'restored': restored,
        'controls': controls,
      };
}
