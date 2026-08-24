import 'dart:ui' as ui;
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
  }) : _channel =
            channel ?? MethodChannel('visibility_grid_v2_control_$viewId');

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

  /// Fences only the V2 binding while leaving the shared V1 point-cloud/
  /// visibility channel alive for the owning platform view's normal teardown.
  Future<void> dispose() => _channel.invokeMethod<void>('disposeBinding');
}

/// Background-isolate owner for one production V2 control/stream binding.
///
/// Both channels use the background messenger directly. The root isolate is
/// needed only to supply Flutter's registration token and never sees ordinary
/// stream request or response bytes.
final class ARVisibilityGridV2WorkerBinding {
  ARVisibilityGridV2WorkerBinding._({
    required MethodChannel controlChannel,
    required BasicMessageChannel<ByteData?> streamChannel,
  })  : _controlChannel = controlChannel,
        _streamChannel = streamChannel;

  factory ARVisibilityGridV2WorkerBinding.connect({
    required ui.RootIsolateToken rootIsolateToken,
    required int viewId,
  }) {
    BackgroundIsolateBinaryMessenger.ensureInitialized(rootIsolateToken);
    final messenger = BackgroundIsolateBinaryMessenger.instance;
    return ARVisibilityGridV2WorkerBinding._(
      controlChannel: MethodChannel(
        'visibility_grid_v2_control_$viewId',
        const StandardMethodCodec(),
        messenger,
      ),
      streamChannel: BasicMessageChannel<ByteData?>(
        'visibility_surface_stream_$viewId',
        const BinaryCodec(),
        binaryMessenger: messenger,
      ),
    );
  }

  final MethodChannel _controlChannel;
  final BasicMessageChannel<ByteData?> _streamChannel;
  bool _closed = false;

  Future<Uint8List> control(String method, Uint8List request) async {
    _ensureOpen();
    final response =
        await _controlChannel.invokeMethod<Object?>(method, request);
    if (response is! Uint8List) {
      throw StateError('V2 control returned a non-byte response.');
    }
    return Uint8List.fromList(response);
  }

  Future<Uint8List> exchange(Uint8List request) async {
    _ensureOpen();
    final response = await _streamChannel.send(ByteData.sublistView(request));
    if (response == null) {
      throw StateError('V2 stream returned no response.');
    }
    return Uint8List.fromList(
      response.buffer.asUint8List(
        response.offsetInBytes,
        response.lengthInBytes,
      ),
    );
  }

  /// Reads bounded scalar binding telemetry on the same native executor as
  /// control and exchange operations. No surface payload bytes are returned.
  Future<Map<Object?, Object?>> snapshot() async {
    _ensureOpen();
    final response = await _controlChannel.invokeMethod<Object?>(
      'bindingSnapshot',
    );
    if (response is! Map) {
      throw StateError('V2 binding returned a non-map snapshot.');
    }
    return Map<Object?, Object?>.from(response);
  }

  void close() => _closed = true;

  /// Fences this worker's native binding before the worker isolate exits.
  ///
  /// The Android owner replaces the per-view stream/control lifecycle after
  /// this call, so a subsequent group can negotiate a new binding generation
  /// without reusing the old stream token or transaction cursor.
  Future<void> dispose() async {
    if (_closed) return;
    try {
      await _controlChannel.invokeMethod<Object?>('disposeBinding');
    } finally {
      _closed = true;
    }
  }

  void _ensureOpen() {
    if (_closed) throw StateError('V2 worker binding is closed.');
  }
}
