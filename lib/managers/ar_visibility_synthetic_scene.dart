import 'package:flutter/services.dart';

/// Depth capability used by the bounded synthetic-scene fixture.
enum ARVisibilitySyntheticDepthCapability {
  unsupported('unsupported'),
  rawDepth('rawDepth'),
  automatic('automatic');

  const ARVisibilitySyntheticDepthCapability(this.wireName);

  final String wireName;
}

/// Finite native-side scenes. Observation samples are generated in Kotlin.
enum ARVisibilitySyntheticSceneStep {
  wall('wall'),
  corner('corner'),
  foregroundOccluder('foregroundOccluder'),
  depthAfterPublication('depthAfterPublication'),
  secondView('secondView'),
  automaticRevisit('automaticRevisit');

  const ARVisibilitySyntheticSceneStep(this.wireName);

  final String wireName;
}

/// Product-owner faults invoked through the production integration hook.
enum ARVisibilitySyntheticFault {
  rendererUnavailable('rendererUnavailable'),
  rendererRecovered('rendererRecovered'),
  guidanceTerminal('guidanceTerminal');

  const ARVisibilitySyntheticFault(this.wireName);

  final String wireName;
}

/// Scalar receipt returned by every synthetic-scene command.
final class ARVisibilitySyntheticReceipt {
  const ARVisibilitySyntheticReceipt({
    required this.scenarioId,
    required this.sequence,
    required this.acceptedFeatureObservations,
    required this.acceptedDepthObservations,
    required this.admittedFeatureObservations,
    required this.admittedDepthObservations,
    required this.integrationStatus,
    required this.geometryRevision,
    required this.lineageRevision,
    required this.durableCaptureRevision,
    required this.coverageRevision,
    required this.styleRevision,
    required this.targetSurfaceId,
    required this.rendererRows,
    required this.automaticEligible,
    required this.guidanceStatus,
    required this.rootIsolateSurfaceBytes,
    required this.resourceBalance,
    required this.callbackCopyP95Micros,
    required this.rootIsolateImageBytes,
    required this.rendererOwnedBytes,
  });

  final String scenarioId;
  final int sequence;
  final int acceptedFeatureObservations;
  final int acceptedDepthObservations;
  final int admittedFeatureObservations;
  final int admittedDepthObservations;
  final String integrationStatus;
  final int geometryRevision;
  final int lineageRevision;
  final int durableCaptureRevision;
  final int coverageRevision;
  final int styleRevision;
  final int? targetSurfaceId;
  final int rendererRows;
  final bool automaticEligible;
  final String guidanceStatus;
  final int rootIsolateSurfaceBytes;
  final int resourceBalance;
  final int callbackCopyP95Micros;
  final int rootIsolateImageBytes;
  final int rendererOwnedBytes;

  Map<String, Object?> toMap() => <String, Object?>{
        'scenarioId': scenarioId,
        'sequence': sequence,
        'acceptedFeatureObservations': acceptedFeatureObservations,
        'acceptedDepthObservations': acceptedDepthObservations,
        'admittedFeatureObservations': admittedFeatureObservations,
        'admittedDepthObservations': admittedDepthObservations,
        'integrationStatus': integrationStatus,
        'geometryRevision': geometryRevision,
        'lineageRevision': lineageRevision,
        'durableCaptureRevision': durableCaptureRevision,
        'coverageRevision': coverageRevision,
        'styleRevision': styleRevision,
        'targetSurfaceId': targetSurfaceId,
        'rendererRows': rendererRows,
        'automaticEligible': automaticEligible,
        'guidanceStatus': guidanceStatus,
        'rootIsolateSurfaceBytes': rootIsolateSurfaceBytes,
        'resourceBalance': resourceBalance,
        'callbackCopyP95Micros': callbackCopyP95Micros,
        'rootIsolateImageBytes': rootIsolateImageBytes,
        'rendererOwnedBytes': rendererOwnedBytes,
      };

  static ARVisibilitySyntheticReceipt fromMap(Object? raw) {
    final map = _scalarMap(raw);
    const keys = <String>{
      'scenarioId',
      'sequence',
      'acceptedFeatureObservations',
      'acceptedDepthObservations',
      'admittedFeatureObservations',
      'admittedDepthObservations',
      'integrationStatus',
      'geometryRevision',
      'lineageRevision',
      'durableCaptureRevision',
      'coverageRevision',
      'styleRevision',
      'targetSurfaceId',
      'rendererRows',
      'automaticEligible',
      'guidanceStatus',
      'rootIsolateSurfaceBytes',
      'resourceBalance',
      'callbackCopyP95Micros',
      'rootIsolateImageBytes',
      'rendererOwnedBytes',
    };
    if (!map.keys.toSet().containsAll(keys) || map.length != keys.length) {
      throw FormatException('Synthetic scene receipt fields are not fixed.');
    }
    return ARVisibilitySyntheticReceipt(
      scenarioId: _string(map, 'scenarioId'),
      sequence: _positiveInt(map, 'sequence'),
      acceptedFeatureObservations:
          _nonNegativeInt(map, 'acceptedFeatureObservations'),
      acceptedDepthObservations:
          _nonNegativeInt(map, 'acceptedDepthObservations'),
      admittedFeatureObservations:
          _nonNegativeInt(map, 'admittedFeatureObservations'),
      admittedDepthObservations:
          _nonNegativeInt(map, 'admittedDepthObservations'),
      integrationStatus: _string(map, 'integrationStatus'),
      geometryRevision: _nonNegativeInt(map, 'geometryRevision'),
      lineageRevision: _nonNegativeInt(map, 'lineageRevision'),
      durableCaptureRevision: _nonNegativeInt(map, 'durableCaptureRevision'),
      coverageRevision: _nonNegativeInt(map, 'coverageRevision'),
      styleRevision: _nonNegativeInt(map, 'styleRevision'),
      targetSurfaceId: _nullableNonNegativeInt(map, 'targetSurfaceId'),
      rendererRows: _nonNegativeInt(map, 'rendererRows'),
      automaticEligible: _bool(map, 'automaticEligible'),
      guidanceStatus: _string(map, 'guidanceStatus'),
      rootIsolateSurfaceBytes: _nonNegativeInt(map, 'rootIsolateSurfaceBytes'),
      resourceBalance: _nonNegativeInt(map, 'resourceBalance'),
      callbackCopyP95Micros: _nonNegativeInt(map, 'callbackCopyP95Micros'),
      rootIsolateImageBytes: _nonNegativeInt(map, 'rootIsolateImageBytes'),
      rendererOwnedBytes: _nonNegativeInt(map, 'rendererOwnedBytes'),
    );
  }
}

/// Typed controller for the debug-only finite native scene.
final class ARVisibilitySyntheticScene {
  ARVisibilitySyntheticScene(int viewId, {MethodChannel? channel})
      : _channel = channel ?? MethodChannel('visibility_scenario_v2_$viewId');

  final MethodChannel _channel;
  int _nextSequence = 1;
  String? _scenarioId;
  ARVisibilitySyntheticDepthCapability? _preparedDepthCapability;
  bool _armed = false;
  bool _disposed = false;
  bool _disposeRequested = false;
  bool _commandInFlight = false;
  Future<void>? _disposeFuture;

  Future<ARVisibilitySyntheticReceipt> arm({
    required String scenarioId,
    required ARVisibilitySyntheticDepthCapability depthCapability,
    required int expectedBindingGeneration,
    required int expectedGroupGeneration,
  }) async {
    _validateScenarioId(scenarioId);
    if (expectedBindingGeneration <= 0) {
      throw ArgumentError.value(
        expectedBindingGeneration,
        'expectedBindingGeneration',
      );
    }
    if (expectedGroupGeneration <= 0) {
      throw ArgumentError.value(
        expectedGroupGeneration,
        'expectedGroupGeneration',
      );
    }
    return _runExclusive(() async {
      if (_armed) throw StateError('Synthetic scene is already armed.');
      final prepared = _preparedDepthCapability;
      if (prepared != null && prepared != depthCapability) {
        throw StateError(
          'Depth capability does not match the prepared synthetic source.',
        );
      }
      final receipt = await _invokeReceipt(
        'arm',
        <String, Object?>{
          'scenarioId': scenarioId,
          'depthCapability': depthCapability.wireName,
          'sequence': 1,
          'expectedBindingGeneration': expectedBindingGeneration,
          'expectedGroupGeneration': expectedGroupGeneration,
        },
      );
      _validateReceiptIdentity(receipt, scenarioId, 1);
      _scenarioId = scenarioId;
      _nextSequence = receipt.sequence + 1;
      _armed = true;
      return receipt;
    });
  }

  /// Fences real native observation callbacks before ownership is available.
  ///
  /// Preparation is idempotent for one capability and does not consume the
  /// finite scene sequence. [arm] remains sequence 1 and validates the exact
  /// binding and group generations once native ownership is published.
  Future<void> prepare(
    ARVisibilitySyntheticDepthCapability depthCapability,
  ) =>
      _runExclusive(() async {
        if (_armed) throw StateError('Synthetic scene is already armed.');
        final prepared = _preparedDepthCapability;
        if (prepared != null && prepared != depthCapability) {
          throw StateError(
            'Depth capability does not match the prepared synthetic source.',
          );
        }
        await _channel.invokeMethod<void>(
          'prepare',
          <String, Object?>{'depthCapability': depthCapability.wireName},
        );
        _preparedDepthCapability = depthCapability;
      });

  Future<ARVisibilitySyntheticReceipt> emit(
    ARVisibilitySyntheticSceneStep step,
  ) =>
      _runArmedExclusive(
        () => _invokeSequenced(
          'emit',
          <String, Object?>{'step': step.wireName},
        ),
      );

  Future<ARVisibilitySyntheticReceipt> setFault(
    ARVisibilitySyntheticFault fault,
  ) =>
      _runArmedExclusive(
        () => _invokeSequenced(
          'setFault',
          <String, Object?>{'fault': fault.wireName},
        ),
      );

  Future<ARVisibilitySyntheticReceipt> pauseResume() => _runArmedExclusive(
        () => _invokeSequenced('pauseResume', const <String, Object?>{}),
      );

  Future<ARVisibilitySyntheticReceipt> snapshot() => _runArmedExclusive(
        () => _invokeSequenced('snapshot', const <String, Object?>{}),
      );

  Future<void> dispose() {
    if (_disposed) return Future<void>.value();
    final pending = _disposeFuture;
    if (pending != null) return pending;
    final operation = _disposeInternal();
    _disposeFuture = operation;
    operation.then<void>(
      (_) {},
      onError: (Object _, StackTrace __) {
        _disposeFuture = null;
      },
    );
    return operation;
  }

  Future<void> _disposeInternal() async {
    _ensureUsable();
    if (_commandInFlight) {
      throw StateError('A synthetic scene command is already in flight.');
    }
    _disposeRequested = true;
    _commandInFlight = true;
    try {
      if (_armed) {
        await _invokeSequenced('disarm', const <String, Object?>{});
      }
      _armed = false;
      _scenarioId = null;
      _disposed = true;
    } catch (_) {
      _disposeRequested = false;
      rethrow;
    } finally {
      _commandInFlight = false;
    }
  }

  Future<ARVisibilitySyntheticReceipt> _runArmedExclusive(
    Future<ARVisibilitySyntheticReceipt> Function() operation,
  ) =>
      _runExclusive(() {
        _ensureArmed();
        return operation();
      });

  Future<T> _runExclusive<T>(Future<T> Function() operation) async {
    _ensureUsable();
    if (_commandInFlight) {
      throw StateError('A synthetic scene command is already in flight.');
    }
    _commandInFlight = true;
    try {
      return await operation();
    } finally {
      _commandInFlight = false;
    }
  }

  Future<ARVisibilitySyntheticReceipt> _invokeSequenced(
    String method,
    Map<String, Object?> arguments,
  ) async {
    final sequence = _nextSequence;
    final receipt = await _invokeReceipt(
      method,
      <String, Object?>{
        ...arguments,
        'scenarioId': _scenarioId,
        'sequence': sequence,
      },
    );
    _validateReceiptIdentity(receipt, _scenarioId!, sequence);
    _nextSequence = receipt.sequence + 1;
    return receipt;
  }

  Future<ARVisibilitySyntheticReceipt> _invokeReceipt(
    String method,
    Map<String, Object?> arguments,
  ) async {
    final response = await _channel.invokeMethod<Object?>(method, arguments);
    return ARVisibilitySyntheticReceipt.fromMap(response);
  }

  void _ensureUsable() {
    if (_disposed || _disposeRequested) {
      throw StateError('Synthetic scene is disposed.');
    }
  }

  void _ensureArmed() {
    _ensureUsable();
    if (!_armed) throw StateError('Synthetic scene is not armed.');
  }

  static void _validateScenarioId(String value) {
    if (value.isEmpty ||
        value.length > 64 ||
        value.codeUnits.any((code) => code < 0x21 || code > 0x7e)) {
      throw ArgumentError.value(value, 'scenarioId');
    }
  }

  static void _validateReceiptIdentity(
    ARVisibilitySyntheticReceipt receipt,
    String scenarioId,
    int sequence,
  ) {
    if (receipt.scenarioId != scenarioId || receipt.sequence != sequence) {
      throw FormatException(
        'Synthetic scene receipt does not match its command identity.',
      );
    }
  }
}

Map<String, Object?> _scalarMap(Object? raw) {
  if (raw is! Map)
    throw FormatException('Synthetic scene receipt is not a map.');
  final result = <String, Object?>{};
  for (final entry in raw.entries) {
    if (entry.key is! String) {
      throw FormatException('Synthetic scene receipt keys must be strings.');
    }
    final value = entry.value;
    if (value is! String && value is! int && value is! bool && value != null) {
      throw FormatException('Synthetic scene receipt values must be scalar.');
    }
    result[entry.key as String] = value;
  }
  return result;
}

String _string(Map<String, Object?> map, String key) {
  final value = map[key];
  if (value is! String || value.isEmpty) {
    throw FormatException(
        'Synthetic scene field $key must be a non-empty string.');
  }
  if (value.length > 64 ||
      value.codeUnits.any((code) => code < 0x21 || code > 0x7e)) {
    throw FormatException('Synthetic scene field $key is not bounded ASCII.');
  }
  return value;
}

bool _bool(Map<String, Object?> map, String key) {
  final value = map[key];
  if (value is! bool)
    throw FormatException('Synthetic scene field $key must be bool.');
  return value;
}

int _nonNegativeInt(Map<String, Object?> map, String key) {
  final value = map[key];
  if (value is! int || value < 0) {
    throw FormatException(
        'Synthetic scene field $key must be non-negative int.');
  }
  return value;
}

int _positiveInt(Map<String, Object?> map, String key) {
  final value = _nonNegativeInt(map, key);
  if (value == 0) {
    throw FormatException('Synthetic scene field $key must be positive.');
  }
  return value;
}

int? _nullableNonNegativeInt(Map<String, Object?> map, String key) {
  final value = map[key];
  if (value == null) return null;
  if (value is! int || value < 0) {
    throw FormatException(
        'Synthetic scene field $key must be null or non-negative int.');
  }
  return value;
}
