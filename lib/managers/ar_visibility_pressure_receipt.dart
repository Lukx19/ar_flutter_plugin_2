import 'package:flutter/services.dart';

/// Fixed scalar evidence from a combined visibility/capture pressure run.
final class ARVisibilityPressureReceipt {
  ARVisibilityPressureReceipt._(
    this._integers,
    this.manualWonWaitingSlot,
    this.terminalDepthMode,
    this.terminalGuidanceStatus,
  );

  final Map<String, int> _integers;
  final bool manualWonWaitingSlot;
  final String terminalDepthMode;
  final String terminalGuidanceStatus;

  int value(String field) {
    final value = _integers[field];
    if (value == null) throw ArgumentError.value(field, 'field');
    return value;
  }

  int get groupGeneration => value('groupGeneration');
  int get bindingGeneration => value('bindingGeneration');
  int get featureOffered => value('featureOffered');
  int get featureAdmitted => value('featureAdmitted');
  int get featureCoalesced => value('featureCoalesced');
  int get featureDropped => value('featureDropped');
  int get featureMaxSamples => value('featureMaxSamples');
  int get featureMaxHz => value('featureMaxHz');
  int get depthOffered => value('depthOffered');
  int get depthAdmitted => value('depthAdmitted');
  int get depthCoalesced => value('depthCoalesced');
  int get depthDropped => value('depthDropped');
  int get depthMaxSamples => value('depthMaxSamples');
  int get depthMaxHz => value('depthMaxHz');
  int get callbackCopyP95Micros => value('callbackCopyP95Micros');
  int get rendererOwnedBytes => value('rendererOwnedBytes');
  int get maxUploadBytesPerFrame => value('maxUploadBytesPerFrame');
  int get selectionChurnPermille => value('selectionChurnPermille');
  int get rootIsolateSurfaceBytes => value('rootIsolateSurfaceBytes');
  int get rootIsolateImageBytes => value('rootIsolateImageBytes');
  int get captureMaxRunning => value('captureMaxRunning');
  int get captureMaxFundedWaiting => value('captureMaxFundedWaiting');
  int get featureLatestSlots => value('featureLatestSlots');
  int get depthLatestSlots => value('depthLatestSlots');
  int get catchUpBursts => value('catchUpBursts');

  Map<String, Object> toMap() => <String, Object>{
        for (final entry in _integers.entries) entry.key: entry.value,
        'manualWonWaitingSlot': manualWonWaitingSlot,
        'terminalDepthMode': terminalDepthMode,
        'terminalGuidanceStatus': terminalGuidanceStatus,
      };

  static ARVisibilityPressureReceipt fromMap(Object? raw) {
    if (raw is! Map) {
      throw const FormatException('Visibility pressure receipt is not a map.');
    }
    final map = <String, Object?>{};
    for (final entry in raw.entries) {
      if (entry.key is! String) {
        throw const FormatException(
            'Visibility pressure keys must be strings.');
      }
      final value = entry.value;
      if (value is! int && value is! bool && value is! String) {
        throw const FormatException(
            'Visibility pressure values must be scalar.');
      }
      map[entry.key as String] = value;
    }
    if (map.length != _allKeys.length ||
        !map.keys.toSet().containsAll(_allKeys)) {
      throw const FormatException(
          'Visibility pressure receipt fields are not fixed.');
    }
    final integers = <String, int>{};
    for (final key in _integerKeys) {
      final value = map[key];
      if (value is! int || value < 0) {
        throw FormatException('$key must be a non-negative int.');
      }
      integers[key] = value;
    }
    final manualWonWaitingSlot = map['manualWonWaitingSlot'];
    if (manualWonWaitingSlot is! bool) {
      throw const FormatException('manualWonWaitingSlot must be bool.');
    }
    final terminalDepthMode = _boundedString(map, 'terminalDepthMode');
    final terminalGuidanceStatus =
        _boundedString(map, 'terminalGuidanceStatus');
    if (integers['featureAdmitted']! +
            integers['featureCoalesced']! +
            integers['featureDropped']! >
        integers['featureOffered']!) {
      throw const FormatException(
          'Feature pressure accounting exceeds offered work.');
    }
    if (integers['depthAdmitted']! +
            integers['depthCoalesced']! +
            integers['depthDropped']! >
        integers['depthOffered']!) {
      throw const FormatException(
          'Depth pressure accounting exceeds offered work.');
    }
    if (integers['featureLatestSlots']! > 1 ||
        integers['depthLatestSlots']! > 1) {
      throw const FormatException(
          'Visibility latest slots must be at most one.');
    }
    return ARVisibilityPressureReceipt._(
      Map<String, int>.unmodifiable(integers),
      manualWonWaitingSlot,
      terminalDepthMode,
      terminalGuidanceStatus,
    );
  }

  static const Set<String> _integerKeys = <String>{
    'groupGeneration',
    'bindingGeneration',
    'featureOffered',
    'featureAdmitted',
    'featureCoalesced',
    'featureDropped',
    'featureMaxSamples',
    'featureMaxHz',
    'depthOffered',
    'depthAdmitted',
    'depthCoalesced',
    'depthDropped',
    'depthMaxSamples',
    'depthMaxHz',
    'callbackCopyP95Micros',
    'geometryRevision',
    'lineageRevision',
    'coverageRevision',
    'styleRevision',
    'canonicalSurfaceHighWater',
    'associationHighWater',
    'canonicalOwnedBytes',
    'rendererOwnedBytes',
    'maxUploadBytesPerFrame',
    'selectionChurnPermille',
    'workerMaxPendingTransactions',
    'workerCoalescedPresentations',
    'rootIsolateSurfaceBytes',
    'rootIsolateImageBytes',
    'captureMaxRunning',
    'captureMaxFundedWaiting',
    'featureLatestSlots',
    'depthLatestSlots',
    'catchUpBursts',
    'imagesAcquired',
    'imagesReleased',
    'buffersAcquired',
    'buffersReleased',
    'callbacksAcquired',
    'callbacksReleased',
    'pagesAcquired',
    'pagesReleased',
    'workerPortsAcquired',
    'workerPortsReleased',
    'rendererResourcesAcquired',
    'rendererResourcesReleased',
  };

  static const Set<String> _allKeys = <String>{
    ..._integerKeys,
    'manualWonWaitingSlot',
    'terminalDepthMode',
    'terminalGuidanceStatus',
  };
}

/// Read-only debug probe attached to the production observation channel.
final class ARVisibilityPressureProbe {
  ARVisibilityPressureProbe(int viewId, {MethodChannel? channel})
      : _channel =
            channel ?? MethodChannel('visibility_observation_v2_$viewId');

  final MethodChannel _channel;

  Future<ARVisibilityPressureReceipt> snapshot() async =>
      ARVisibilityPressureReceipt.fromMap(
        await _channel.invokeMethod<Object?>('pressureSnapshot'),
      );
}

String _boundedString(Map<String, Object?> map, String key) {
  final value = map[key];
  if (value is! String || value.isEmpty || value.length > 64) {
    throw FormatException('$key must be a bounded non-empty string.');
  }
  return value;
}
