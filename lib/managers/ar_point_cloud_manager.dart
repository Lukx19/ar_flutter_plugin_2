import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/services.dart';

import '../models/ar_point_cloud.dart';

class ARPointCloudManager {
  ARPointCloudManager(int viewId, {MethodChannel? channel})
      : _channel = channel ?? MethodChannel('arpointcloud_$viewId') {
    _channel.setMethodCallHandler(_handleNativeCall);
  }

  final MethodChannel _channel;
  final StreamController<ARPointCloudFrame> _frames =
      StreamController<ARPointCloudFrame>.broadcast(sync: true);
  final StreamController<ARPointCloudError> _errors =
      StreamController<ARPointCloudError>.broadcast(sync: true);
  final StreamController<ARPointCloudRenderingStats> _stats =
      StreamController<ARPointCloudRenderingStats>.broadcast(sync: true);
  final StreamController<ARPointCloudInitializationResult> _readiness =
      StreamController<ARPointCloudInitializationResult>.broadcast(sync: true);
  bool _disposed = false;

  Stream<ARPointCloudFrame> get frames => _frames.stream;
  Stream<ARPointCloudError> get errors => _errors.stream;
  Stream<ARPointCloudRenderingStats> get stats => _stats.stream;
  Stream<ARPointCloudInitializationResult> get readiness => _readiness.stream;

  Future<ARPointCloudInitializationResult> initialize(
    ARPointCloudNativeConfig config,
  ) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'init',
      config.toMap(),
    );
    if (result == null || result['version'] != pointCloudWireVersion) {
      throw const FormatException('Point-cloud init version mismatch.');
    }
    final readiness = ARPointCloudInitializationResult(
      rendererReady: _bool(result, 'rendererReady'),
      acquisitionReady: _bool(result, 'acquisitionReady'),
    );
    _readiness.add(readiness);
    return readiness;
  }

  Future<bool> updateVoxels(ARVoxelRenderPatch patch) async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'updateVoxels',
      patch.toMap(),
    );
    return result != null && result['applied'] == true;
  }

  Future<void> setPointsEnabled(bool enabled) async {
    _ensureActive();
    await _channel.invokeMethod<bool>('setPointsEnabled', <String, Object>{
      'enabled': enabled,
    });
  }

  Future<void> setVoxelRenderMode(String mode) async {
    _ensureActive();
    if (mode != 'points' && mode != 'cubes') {
      throw ArgumentError.value(mode, 'mode', 'Expected points or cubes.');
    }
    await _channel.invokeMethod<bool>('setVoxelRenderMode', <String, Object>{
      'mode': mode,
    });
  }

  Future<ARPointCloudRenderingStats> getRenderingStats() async {
    _ensureActive();
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'getRenderingStats',
    );
    if (result == null) {
      throw const FormatException('Missing rendering stats.');
    }
    return ARPointCloudRenderingStats.fromMap(result);
  }

  Future<void> clear() async {
    _ensureActive();
    await _channel.invokeMethod<bool>('clear');
  }

  Future<void> dispose() async {
    if (_disposed) {
      return;
    }
    _disposed = true;
    try {
      try {
        await _channel.invokeMethod<bool>('dispose');
      } on MissingPluginException {
        // iOS remains deferred and older native hosts may not expose the
        // optional point-cloud channel. Session teardown must still complete.
      }
    } finally {
      _channel.setMethodCallHandler(null);
      await _frames.close();
      await _errors.close();
      await _stats.close();
      await _readiness.close();
    }
  }

  Future<Object?> _handleNativeCall(MethodCall call) async {
    if (_disposed) {
      return null;
    }
    switch (call.method) {
      case 'onPointCloudFrame':
        final map = _map(call.arguments);
        if (map['version'] != pointCloudWireVersion) {
          _emitProtocolError('Frame wire version mismatch.');
          throw const FormatException('Frame wire version mismatch.');
        }
        final ids = map['ids'];
        final points = map['points'];
        final count = map['count'];
        if (ids is! Int32List ||
            points is! Float32List ||
            count is! int ||
            count != ids.length ||
            points.length != count * 4) {
          _emitProtocolError('Invalid typed-data frame shape.');
          throw const FormatException('Invalid typed-data frame shape.');
        }
        final frame = ARPointCloudFrame.owned(
          sequence: _integer(map, 'sequence'),
          timestampNs: _integer(map, 'timestampNs'),
          ids: ids,
          points: points,
        );
        _frames.add(frame);
        return <String, Object>{'acceptedSequence': frame.sequence};
      case 'onRenderingStats':
        _stats.add(ARPointCloudRenderingStats.fromMap(_map(call.arguments)));
        return null;
      case 'onRendererReady':
        final map = _map(call.arguments);
        if (map['version'] != pointCloudWireVersion) {
          _emitProtocolError('Renderer readiness wire version mismatch.');
          throw const FormatException(
              'Renderer readiness wire version mismatch.');
        }
        _readiness.add(
          ARPointCloudInitializationResult(
            rendererReady: _bool(map, 'rendererReady'),
            acquisitionReady: _bool(map, 'acquisitionReady'),
          ),
        );
        return null;
      case 'onError':
        final map = _map(call.arguments);
        _errors.add(
          ARPointCloudError(
            code: _string(map, 'code'),
            message: _string(map, 'message'),
            fatalToAcquisition: _bool(map, 'fatalToAcquisition'),
            fatalToRenderer: _bool(map, 'fatalToRenderer'),
          ),
        );
        return null;
      default:
        throw MissingPluginException(
            'Unknown point-cloud callback ${call.method}.');
    }
  }

  void _emitProtocolError(String message) {
    _errors.add(
      ARPointCloudError(
        code: 'PC_PROTOCOL_INVALID',
        message: message,
        fatalToAcquisition: true,
        fatalToRenderer: false,
      ),
    );
  }

  void _ensureActive() {
    if (_disposed) {
      throw StateError('ARPointCloudManager is disposed.');
    }
  }
}

Map<Object?, Object?> _map(Object? value) {
  if (value is! Map) {
    throw const FormatException('Expected point-cloud map payload.');
  }
  return Map<Object?, Object?>.from(value);
}

int _integer(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is! int || value < 0) {
    throw FormatException('Expected non-negative integer $key.');
  }
  return value;
}

String _string(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is! String) {
    throw FormatException('Expected string $key.');
  }
  return value;
}

bool _bool(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is! bool) {
    throw FormatException('Expected bool $key.');
  }
  return value;
}
