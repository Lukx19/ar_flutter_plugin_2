import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/services.dart';

import '../models/ar_visibility_grid.dart';

/// Strict Dart endpoint for the per-view `visibility_grid_wire_v1` channel.
class ARVisibilityGridManager {
  ARVisibilityGridManager(
    int viewId, {
    MethodChannel? channel,
    Duration rendererFenceTimeout = const Duration(seconds: 45),
  })  : viewId = viewId,
        _rendererFenceTimeout = rendererFenceTimeout,
        _channel = channel ?? MethodChannel('arpointcloud_$viewId') {
    _channel.setMethodCallHandler(_handleNativeCall);
  }

  final MethodChannel _channel;
  final int viewId;
  final Duration _rendererFenceTimeout;
  final StreamController<ARVisibilityGridDelta> _deltas =
      StreamController<ARVisibilityGridDelta>.broadcast(sync: true);
  final StreamController<ARVisibilityGridDeltaSummary> _summaries =
      StreamController<ARVisibilityGridDeltaSummary>.broadcast(sync: true);
  final StreamController<ARVisibilityGridError> _errors =
      StreamController<ARVisibilityGridError>.broadcast(sync: true);
  final StreamController<ARVisibilityGridSourceHealth> _health =
      StreamController<ARVisibilityGridSourceHealth>.broadcast(sync: true);
  final StreamController<ARVisibilityGridDiagnostics> _diagnostics =
      StreamController<ARVisibilityGridDiagnostics>.broadcast(sync: true);
  bool _disposed = false;

  /// Revisioned stable-key upserts, removals, and reset snapshots.
  Stream<ARVisibilityGridDelta> get deltas => _deltas.stream;

  /// Fixed-size ordinary callbacks. No semantic keys cross the UI isolate.
  Stream<ARVisibilityGridDeltaSummary> get summaries => _summaries.stream;

  /// Typed native protocol failures.
  Stream<ARVisibilityGridError> get errors => _errors.stream;

  /// Latest component health without raw sensor payloads.
  Stream<ARVisibilityGridSourceHealth> get health => _health.stream;

  /// Latest bounded native counters, including heartbeat-only updates.
  Stream<ARVisibilityGridDiagnostics> get diagnostics => _diagnostics.stream;

  /// Negotiates the v1 protocol and bounded native configuration.
  ///
  /// Throws [FormatException] when the native response violates the contract.
  Future<ARVisibilityGridInitializationResult> initialize(
    ARVisibilityGridNativeConfig config,
  ) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'init',
      config.toMap(),
    );
    if (result == null) {
      throw const FormatException(
        'Missing visibility-grid initialization result.',
      );
    }
    return ARVisibilityGridInitializationResult.fromMap(result);
  }

  /// Starts acquisition for one validated capture-group coordinate frame.
  Future<ARVisibilityGridDelta> startGrid(
    ARVisibilityGridGroupConfig config,
  ) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'startGrid',
      config.toMap(),
    );
    if (result == null) {
      throw const FormatException('Missing visibility-grid start snapshot.');
    }
    return ARVisibilityGridDelta.fromMap(result);
  }

  /// Starts native acquisition without materializing its reset keys on the
  /// root isolate. A background worker must call [pullDeltaInBackground].
  Future<ARVisibilityGridDeltaSummary> startGridSummary(
    ARVisibilityGridGroupConfig config,
  ) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'startGridSummary',
      config.toMap(),
    );
    if (result == null) {
      throw const FormatException('Missing visibility-grid start summary.');
    }
    return ARVisibilityGridDeltaSummary.fromMap(result);
  }

  /// Acknowledges an atomically applied geometry revision.
  Future<bool> ackGeometry(ARVisibilityGridDelta delta) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'ackGeometry',
      <String, Object>{
        'version': visibilityGridWireVersion,
        'groupId': delta.groupId,
        'groupGeneration': delta.groupGeneration,
        'sessionGeneration': delta.sessionGeneration,
        'acceptedGeometryRevision': delta.geometryRevision,
      },
    );
    return result?['accepted'] == true;
  }

  /// Acknowledges a worker-pulled revision without reintroducing its keys to
  /// the root isolate.
  Future<bool> ackGeometrySummary(ARVisibilityGridDeltaSummary summary) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'ackGeometry',
      <String, Object>{
        'version': visibilityGridWireVersion,
        'groupId': summary.groupId,
        'groupGeneration': summary.groupGeneration,
        'sessionGeneration': summary.sessionGeneration,
        'acceptedGeometryRevision': summary.geometryRevision,
      },
    );
    return result?['accepted'] == true;
  }

  /// Requests a bounded full reset after a revision gap.
  Future<ARVisibilityGridDelta> requestSnapshot(
    ARVisibilityGridDelta current,
  ) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'requestSnapshot',
      <String, Object>{
        'version': visibilityGridWireVersion,
        'groupId': current.groupId,
        'groupGeneration': current.groupGeneration,
        'sessionGeneration': current.sessionGeneration,
        'receiverGeometryRevision': current.geometryRevision,
      },
    );
    if (result == null) {
      throw const FormatException('Missing visibility-grid snapshot.');
    }
    final snapshot = ARVisibilityGridDelta.fromMap(result);
    if (!snapshot.reset) {
      throw const FormatException('Visibility-grid snapshot must reset.');
    }
    return snapshot;
  }

  /// Explicit recovery reset without returning semantic keys to the root
  /// isolate. The coverage worker pulls the retained reset before ACK.
  Future<ARVisibilityGridDeltaSummary> requestSnapshotSummary({
    required String groupId,
    required int groupGeneration,
    required int sessionGeneration,
    required int receiverGeometryRevision,
  }) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'requestSnapshotSummary',
      <String, Object>{
        'version': visibilityGridWireVersion,
        'groupId': groupId,
        'groupGeneration': groupGeneration,
        'sessionGeneration': sessionGeneration,
        'receiverGeometryRevision': receiverGeometryRevision,
      },
    );
    if (result == null) {
      throw const FormatException('Missing visibility-grid recovery summary.');
    }
    final summary = ARVisibilityGridDeltaSummary.fromMap(result);
    if (!summary.reset) {
      throw const FormatException('Visibility-grid recovery must reset.');
    }
    return summary;
  }

  /// Applies colors to existing native-owned geometry only.
  Future<bool> applyVisibility(
    ARVisibilityGridVisibilityPatch patch,
  ) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'applyVisibility',
      patch.toMap(),
    );
    return result?['applied'] == true;
  }

  /// Reads the native renderer's authoritative revision pair. Use this after
  /// an unknown apply outcome; it is deliberately separate from a retry.
  Future<ARVisibilityGridVisibilityRevision> getAppliedVisibilityRevision({
    required String groupId,
    required int groupGeneration,
    required int sessionGeneration,
  }) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'getVisibilityRevision',
      <String, Object>{
        'version': visibilityGridWireVersion,
        'groupId': groupId,
        'groupGeneration': groupGeneration,
        'sessionGeneration': sessionGeneration,
      },
    );
    if (result == null) {
      throw const FormatException('Missing visibility-grid revision receipt.');
    }
    return ARVisibilityGridVisibilityRevision.fromMap(result);
  }

  /// Arms one debug-build-only native background request seam.
  ///
  /// [method] must be `pullGridDelta`, `applyVisibility`, or
  /// `getVisibilityRevision`; [delay] must be between zero and 30 seconds.
  /// When [neverReply] is true, only `cancelBackgroundRequest` can complete the
  /// original request. Throws [PlatformException] in release builds or for an
  /// invalid method/argument map.
  Future<void> configureDebugBackgroundRequest({
    required String method,
    Duration delay = Duration.zero,
    bool neverReply = false,
  }) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'configureDebugBackgroundRequest',
      <String, Object>{
        'method': method,
        'delayMs': delay.inMilliseconds,
        'neverReply': neverReply,
      },
    );
    if (result?['armed'] != true) {
      throw const FormatException(
        'Native background request test seam was not armed.',
      );
    }
  }

  /// Reads the debug seam's ordered native trace and pending-request count.
  ///
  /// Throws [PlatformException] outside a debuggable Android build and
  /// [FormatException] when the response does not match `{trace, pendingCount}`.
  Future<Map<Object?, Object?>> getDebugBackgroundRequestTrace() async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'getDebugBackgroundRequestTrace',
    );
    if (result == null ||
        result['trace'] is! List ||
        result['pendingCount'] is! num) {
      throw const FormatException(
        'Invalid native background request test trace.',
      );
    }
    return result;
  }

  /// Reads current health and diagnostics without waiting for geometry.
  Future<ARVisibilityGridHealthSnapshot> getHealth() async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'getHealth',
    );
    if (result == null) {
      throw const FormatException('Missing visibility-grid health payload.');
    }
    return ARVisibilityGridHealthSnapshot.fromMap(result);
  }

  /// Freezes native acquisition and returns an authoritative reset snapshot.
  ///
  /// Throws [StateError] after disposal and [FormatException] when native
  /// returns no snapshot or a payload outside the v1 contract.
  Future<ARVisibilityGridDelta> checkpointBarrier({
    required String groupId,
    required int groupGeneration,
    required int sessionGeneration,
    required int receiverGeometryRevision,
  }) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'checkpointBarrier',
      <String, Object>{
        'version': visibilityGridWireVersion,
        'groupId': groupId,
        'groupGeneration': groupGeneration,
        'sessionGeneration': sessionGeneration,
        'receiverGeometryRevision': receiverGeometryRevision,
      },
    );
    if (result == null) {
      throw const FormatException('Missing checkpoint barrier snapshot.');
    }
    return ARVisibilityGridDelta.fromMap(result);
  }

  /// Releases a barrier after comparing the exact final revision pair.
  ///
  /// Returns false for a mismatch (native still unfreezes to avoid deadlock).
  /// Throws [StateError] after disposal.
  Future<bool> releaseCheckpoint({
    required String groupId,
    required int groupGeneration,
    required int sessionGeneration,
    required int geometryRevision,
    required int visibilityRevision,
  }) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'releaseCheckpoint',
      <String, Object>{
        'version': visibilityGridWireVersion,
        'groupId': groupId,
        'groupGeneration': groupGeneration,
        'sessionGeneration': sessionGeneration,
        'geometryRevision': geometryRevision,
        'visibilityRevision': visibilityRevision,
      },
    );
    return result?['released'] == true;
  }

  /// Changes renderer visibility without changing grid acquisition.
  Future<void> setPointsEnabled(bool enabled) async {
    _ensureActive();
    await _channel.invokeMethod<bool>('setPointsEnabled', <String, Object>{
      'enabled': enabled,
    });
  }

  /// Switches the retained native renderer without rebuilding geometry.
  ///
  /// Throws [StateError] after this manager has been disposed.
  Future<void> setVoxelRenderMode(ARVisibilityGridRenderMode mode) async {
    _ensureActive();
    await _channel.invokeMethod<bool>('setVoxelRenderMode', <String, Object>{
      'mode': mode.name,
    });
  }

  /// Waits for the native Compose mesh requested by the latest mode change.
  ///
  /// This is a lifecycle fence, not a frame-delay heuristic: it completes
  /// only after SceneView reports that an actual renderer mesh mounted. The
  /// wait is bounded so a stopped PlatformView cannot strand its Dart owner.
  Future<void> awaitRendererMounted() async {
    _ensureActive();
    final mounted = await _channel
        .invokeMethod<bool>('awaitRendererMounted')
        .timeout(_rendererFenceTimeout);
    if (mounted != true) {
      throw StateError('Native visibility-grid renderer did not mount.');
    }
  }

  /// Waits until SceneView reports disposal of the active native mesh.
  ///
  /// The wait is bounded so teardown can release the owning PlatformView even
  /// when a Compose disposal callback is lost.
  Future<void> awaitRendererUnmounted() async {
    _ensureActive();
    final unmounted = await _channel
        .invokeMethod<bool>('awaitRendererUnmounted')
        .timeout(_rendererFenceTimeout);
    if (unmounted != true) {
      throw StateError('Native visibility-grid renderer did not unmount.');
    }
  }

  /// Stops the exact active group generation.
  Future<void> stopGrid({
    required String groupId,
    required int groupGeneration,
    required int sessionGeneration,
  }) async {
    _ensureActive();
    await _channel.invokeMethod<bool>('stopGrid', <String, Object>{
      'version': visibilityGridWireVersion,
      'groupId': groupId,
      'groupGeneration': groupGeneration,
      'sessionGeneration': sessionGeneration,
    });
  }

  /// Releases the per-view channel and all stream controllers.
  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    try {
      try {
        await _channel.invokeMethod<bool>('dispose');
      } on MissingPluginException {
        // Session teardown remains safe on hosts without the new protocol.
      }
    } finally {
      _channel.setMethodCallHandler(null);
      await _deltas.close();
      await _summaries.close();
      await _errors.close();
      await _health.close();
      await _diagnostics.close();
    }
  }

  Future<Object?> _handleNativeCall(MethodCall call) async {
    if (_disposed) return null;
    switch (call.method) {
      case 'onGridDelta':
        try {
          _deltas.add(ARVisibilityGridDelta.fromMap(_map(call.arguments)));
        } on FormatException catch (error) {
          _errors.add(_protocolError(error.message));
          rethrow;
        }
        return null;
      case 'onGridSummary':
        try {
          _summaries.add(
            ARVisibilityGridDeltaSummary.fromMap(_map(call.arguments)),
          );
        } on FormatException catch (error) {
          _errors.add(_protocolError(error.message));
          rethrow;
        }
        return null;
      case 'onGridHealth':
        try {
          final snapshot = ARVisibilityGridHealthSnapshot.fromMap(
            _map(call.arguments),
          );
          _health.add(snapshot.sourceHealth);
          _diagnostics.add(snapshot.diagnostics);
        } on FormatException catch (error) {
          _errors.add(_protocolError(error.message));
          rethrow;
        }
        return null;
      case 'onError':
        _errors.add(ARVisibilityGridError.fromMap(_map(call.arguments)));
        return null;
      default:
        throw MissingPluginException(
          'Unknown visibility-grid callback ${call.method}.',
        );
    }
  }

  ARVisibilityGridError _protocolError(String message) => ARVisibilityGridError(
        code: ARVisibilityGridErrorCode.protocolInvalid,
        message: message,
        recoverable: false,
        fatalToFeature: false,
        fatalToDepth: false,
        fatalToRenderer: false,
        fatalToGrid: true,
      );

  void _ensureActive() {
    if (_disposed) {
      throw StateError('ARVisibilityGridManager is disposed.');
    }
  }
}

/// Production background-isolate pull for the semantic delta named by a
/// root-isolate [ARVisibilityGridDeltaSummary].
///
/// The method channel is initialized in the worker isolate, so no ordinary
/// callback or semantic key collection is materialized by the UI isolate.
final class ARVisibilityGridBackgroundWorker {
  const ARVisibilityGridBackgroundWorker._();

  static Future<ARVisibilityGridDelta> pullDelta({
    required ui.RootIsolateToken rootIsolateToken,
    required int viewId,
    required String groupId,
    required int groupGeneration,
    required int sessionGeneration,
  }) async {
    BackgroundIsolateBinaryMessenger.ensureInitialized(rootIsolateToken);
    final channel = MethodChannel(
      'arpointcloud_$viewId',
      const StandardMethodCodec(),
      BackgroundIsolateBinaryMessenger.instance,
    );
    final result = await channel.invokeMapMethod<Object?, Object?>(
      'pullGridDelta',
      <String, Object>{
        'version': visibilityGridWireVersion,
        'groupId': groupId,
        'groupGeneration': groupGeneration,
        'sessionGeneration': sessionGeneration,
      },
    );
    if (result == null) {
      throw const FormatException('Missing worker-pulled visibility delta.');
    }
    return ARVisibilityGridDelta.fromMap(result);
  }

  /// Applies a worker-owned color patch directly from the background isolate.
  /// The UI isolate receives only its bounded revision result; it never
  /// materializes ordinary semantic keys or colors.
  static Future<bool> applyVisibility({
    required ui.RootIsolateToken rootIsolateToken,
    required int viewId,
    required ARVisibilityGridVisibilityPatch patch,
  }) async {
    BackgroundIsolateBinaryMessenger.ensureInitialized(rootIsolateToken);
    final channel = MethodChannel(
      'arpointcloud_$viewId',
      const StandardMethodCodec(),
      BackgroundIsolateBinaryMessenger.instance,
    );
    final result = await channel.invokeMapMethod<Object?, Object?>(
      'applyVisibility',
      patch.toMap(),
    );
    return result?['applied'] == true;
  }

  /// Background-isolate equivalent of [getAppliedVisibilityRevision].
  static Future<ARVisibilityGridVisibilityRevision>
      getAppliedVisibilityRevision({
    required ui.RootIsolateToken rootIsolateToken,
    required int viewId,
    required String groupId,
    required int groupGeneration,
    required int sessionGeneration,
  }) async {
    BackgroundIsolateBinaryMessenger.ensureInitialized(rootIsolateToken);
    final channel = MethodChannel(
      'arpointcloud_$viewId',
      const StandardMethodCodec(),
      BackgroundIsolateBinaryMessenger.instance,
    );
    final result = await channel.invokeMapMethod<Object?, Object?>(
      'getVisibilityRevision',
      <String, Object>{
        'version': visibilityGridWireVersion,
        'groupId': groupId,
        'groupGeneration': groupGeneration,
        'sessionGeneration': sessionGeneration,
      },
    );
    if (result == null) {
      throw const FormatException('Missing visibility-grid revision receipt.');
    }
    return ARVisibilityGridVisibilityRevision.fromMap(result);
  }
}

/// One cancellable platform request issued from the background coverage
/// isolate. Cancellation completes only after native code has acknowledged
/// that it completed the original MethodChannel result exactly once.
final class ARVisibilityGridBackgroundRequest<T> {
  const ARVisibilityGridBackgroundRequest._({
    required this.requestId,
    required this.result,
    required Future<bool> Function() cancel,
  }) : _cancel = cancel;

  final String requestId;
  final Future<T> result;
  final Future<bool> Function() _cancel;

  Future<bool> cancel() => _cancel();
}

/// Production request/ack protocol used by a background-isolate grid owner.
///
/// A client is isolate-local and keeps monotonically increasing request IDs.
/// The native endpoint retains each original result until either the operation
/// or an explicit cancellation completes it, while suppressing late replies.
/// Request methods add a non-empty `backgroundRequestId` string to their normal
/// v1 argument map. Cancellation sends `{version, backgroundRequestId}` and
/// requires `{acknowledged: true, cancelled: bool}`; malformed or missing
/// responses throw [FormatException], and native operation errors remain
/// [PlatformException]s on [ARVisibilityGridBackgroundRequest.result].
final class ARVisibilityGridBackgroundChannel {
  ARVisibilityGridBackgroundChannel._(this._channel, this._requestPrefix);

  /// Connects this background isolate to one platform view.
  factory ARVisibilityGridBackgroundChannel.connect({
    required ui.RootIsolateToken rootIsolateToken,
    required int viewId,
  }) {
    BackgroundIsolateBinaryMessenger.ensureInitialized(rootIsolateToken);
    return ARVisibilityGridBackgroundChannel._(
      MethodChannel(
        'arpointcloud_$viewId',
        const StandardMethodCodec(),
        BackgroundIsolateBinaryMessenger.instance,
      ),
      'view-$viewId',
    );
  }

  /// Injects the real MethodChannel seam for host tests.
  factory ARVisibilityGridBackgroundChannel.forTesting(MethodChannel channel) =>
      ARVisibilityGridBackgroundChannel._(channel, 'test');

  final MethodChannel _channel;
  final String _requestPrefix;
  int _nextRequest = 0;

  ARVisibilityGridBackgroundRequest<ARVisibilityGridDelta> pullDelta({
    required String groupId,
    required int groupGeneration,
    required int sessionGeneration,
  }) =>
      _request<ARVisibilityGridDelta>(
        method: 'pullGridDelta',
        arguments: <String, Object>{
          'version': visibilityGridWireVersion,
          'groupId': groupId,
          'groupGeneration': groupGeneration,
          'sessionGeneration': sessionGeneration,
        },
        decode: (value) => ARVisibilityGridDelta.fromMap(value),
        missingMessage: 'Missing worker-pulled visibility delta.',
      );

  ARVisibilityGridBackgroundRequest<bool> applyVisibility(
    ARVisibilityGridVisibilityPatch patch,
  ) =>
      _request<bool>(
        method: 'applyVisibility',
        arguments: patch.toMap(),
        decode: (value) => value['applied'] == true,
        missingMessage: 'Missing worker visibility application receipt.',
      );

  ARVisibilityGridBackgroundRequest<ARVisibilityGridVisibilityRevision>
      getAppliedVisibilityRevision({
    required String groupId,
    required int groupGeneration,
    required int sessionGeneration,
  }) =>
          _request<ARVisibilityGridVisibilityRevision>(
            method: 'getVisibilityRevision',
            arguments: <String, Object>{
              'version': visibilityGridWireVersion,
              'groupId': groupId,
              'groupGeneration': groupGeneration,
              'sessionGeneration': sessionGeneration,
            },
            decode: ARVisibilityGridVisibilityRevision.fromMap,
            missingMessage: 'Missing visibility-grid revision receipt.',
          );

  ARVisibilityGridBackgroundRequest<T> _request<T>({
    required String method,
    required Map<String, Object> arguments,
    required T Function(Map<Object?, Object?> value) decode,
    required String missingMessage,
  }) {
    final requestId = '$_requestPrefix-${++_nextRequest}';
    final result = _channel.invokeMapMethod<Object?, Object?>(
      method,
      <String, Object>{
        ...arguments,
        'backgroundRequestId': requestId,
      },
    ).then<T>((value) {
      if (value == null) throw FormatException(missingMessage);
      return decode(value);
    });
    return ARVisibilityGridBackgroundRequest<T>._(
      requestId: requestId,
      result: result,
      cancel: () => _cancel(requestId),
    );
  }

  Future<bool> _cancel(String requestId) async {
    final response = await _channel.invokeMapMethod<Object?, Object?>(
      'cancelBackgroundRequest',
      <String, Object>{
        'version': visibilityGridWireVersion,
        'backgroundRequestId': requestId,
      },
    );
    if (response?['acknowledged'] != true) {
      throw const FormatException(
        'Native visibility-grid cancellation was not acknowledged.',
      );
    }
    return response?['cancelled'] == true;
  }
}

Map<Object?, Object?> _map(Object? value) {
  if (value is! Map) {
    throw const FormatException('Expected visibility-grid map payload.');
  }
  return Map<Object?, Object?>.from(value);
}
