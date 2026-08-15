import 'dart:typed_data';

import 'package:flutter/services.dart';

/// Packed debug control endpoint for the Proposal 08 M0a reference seam.
///
/// Each invocation is one VGC2 request and returns one VGD2 response. The V1
/// visibility-grid manager remains the compatibility product path.
final class ARVisibilityGridV2Control {
  ARVisibilityGridV2Control(
    int viewId, {
    MethodChannel? channel,
  }) : _channel = channel ?? MethodChannel('visibility_grid_control_$viewId');

  final MethodChannel _channel;

  Future<Uint8List> start(Uint8List request) => _invoke('start', request);

  Future<Uint8List> beginCheckpoint(Uint8List request) =>
      _invoke('beginCheckpoint', request);

  Future<Uint8List> releaseCheckpoint(Uint8List request) =>
      _invoke('releaseCheckpoint', request);

  Future<Uint8List> stop(Uint8List request) => _invoke('stop', request);

  Future<Uint8List> _invoke(String method, Uint8List request) async {
    final response = await _channel.invokeMethod<Object?>(method, request);
    if (response is! Uint8List) {
      throw StateError('M0a control returned a non-byte response.');
    }
    return Uint8List.fromList(response);
  }

  Future<void> dispose() => _channel.invokeMethod<void>('dispose');
}
