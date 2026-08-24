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

  /// Sends a byte-only `start` request and returns the byte-only response.
  ///
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel fails, and [StateError] when native returns a non-byte response.
  Future<Uint8List> start(Uint8List request) => _invoke('start', request);

  /// Sends a byte-only `beginCheckpoint` request and returns its response.
  ///
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel fails, and [StateError] when native returns a non-byte response.
  Future<Uint8List> beginCheckpoint(Uint8List request) =>
      _invoke('beginCheckpoint', request);

  /// Sends a byte-only `releaseCheckpoint` request and returns its response.
  ///
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel fails, and [StateError] when native returns a non-byte response.
  Future<Uint8List> releaseCheckpoint(Uint8List request) =>
      _invoke('releaseCheckpoint', request);

  /// Sends a byte-only `stop` request and returns its response.
  ///
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel fails, and [StateError] when native returns a non-byte response.
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
  /// This no-argument call returns the native dispose result.
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel cannot apply the fence.
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

  /// Creates a worker binding using Flutter's background messenger.
  ///
  /// The control channel accepts `start`, `beginCheckpoint`,
  /// `releaseCheckpoint`, and `stop`; each method takes one [Uint8List] and
  /// returns one [Uint8List]. `disposeBinding` takes no argument. The stream
  /// channel accepts one binary [ByteData] envelope and returns one binary
  /// [ByteData] envelope, or null when the native side has no response.
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

  /// Sends START through the worker-owned control channel.
  /// Throws [StateError] when closed or for a non-byte response, and
  /// propagates [PlatformException] or [MissingPluginException].
  Future<Uint8List> start(Uint8List request) =>
      _control(_V2Control.start, request);

  /// Begins a checkpoint through the worker-owned control channel.
  /// Throws [StateError] when closed or for a non-byte response, and
  /// propagates [PlatformException] or [MissingPluginException].
  Future<Uint8List> beginCheckpoint(Uint8List request) =>
      _control(_V2Control.beginCheckpoint, request);

  /// Releases a checkpoint through the worker-owned control channel.
  /// Throws [StateError] when closed or for a non-byte response, and
  /// propagates [PlatformException] or [MissingPluginException].
  Future<Uint8List> releaseCheckpoint(Uint8List request) =>
      _control(_V2Control.releaseCheckpoint, request);

  /// Stops the worker-owned native stream.
  /// Throws [StateError] when closed or for a non-byte response, and
  /// propagates [PlatformException] or [MissingPluginException].
  Future<Uint8List> stop(Uint8List request) =>
      _control(_V2Control.stop, request);

  /// Throws [StateError] if closed or native returns a non-byte response.
  /// Throws [PlatformException] or [MissingPluginException] on channel failure.
  Future<Uint8List> _control(_V2Control operation, Uint8List request) async {
    _ensureOpen();
    final response =
        await _controlChannel.invokeMethod<Object?>(operation.method, request);
    if (response is! Uint8List) {
      throw StateError('V2 control returned a non-byte response.');
    }
    return Uint8List.fromList(response);
  }

  /// Exchanges one binary stream envelope and returns its binary response.
  /// Throws [StateError] when closed or no response arrives, and propagates
  /// [PlatformException] or [MissingPluginException] on channel failure.
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
  /// The no-argument call returns a scalar [Map] snapshot.
  ///
  /// The map contains `bindingGeneration`, `streamToken`, `acceptedControls`,
  /// `initialTransactionQueued`, `disposed`, `closedResources`, the complete
  /// group epoch, `arSessionIdentity`, `viewInstanceId`, `viewGeneration`,
  /// `nativeStreamToken`, `workerBindingToken`, `lifecycleSequence`,
  /// `operationGeneration`, and `executorTrace`.
  /// Throws [StateError] for a closed binding or non-map result and propagates
  /// [PlatformException] or [MissingPluginException] on channel failure.
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

  /// Locally fences future control and exchange calls without invoking native.
  void close() => _closed = true;

  /// Fences this worker's native binding before the worker isolate exits.
  ///
  /// The Android owner replaces the per-view stream/control lifecycle after
  /// this call, so a subsequent group can negotiate a new binding generation
  /// without reusing the old stream token or transaction cursor.
  /// Throws [PlatformException], [MissingPluginException], or [StateError] if
  /// the native fence or its post-fence snapshot cannot be observed.
  Future<void> dispose() async {
    await disposeAndSnapshot();
  }

  /// Disposes the native binding and reads the post-disposal scalar snapshot
  /// before fencing this Dart object. The snapshot includes closed-resource
  /// and lifecycle evidence for teardown receipts.
  /// Throws [PlatformException], [MissingPluginException], or [StateError] if
  /// native disposal or the required snapshot fails.
  Future<Map<Object?, Object?>> disposeAndSnapshot() async {
    if (_closed) return const <Object?, Object?>{};
    try {
      final response = await _controlChannel.invokeMethod<Object?>(
        'disposeBinding',
      );
      if (response is! Map) {
        throw StateError('V2 binding returned no teardown receipt.');
      }
      return Map<Object?, Object?>.from(response);
    } finally {
      _closed = true;
    }
  }

  void _ensureOpen() {
    if (_closed) throw StateError('V2 worker binding is closed.');
  }
}

enum _V2Control {
  start('start'),
  beginCheckpoint('beginCheckpoint'),
  releaseCheckpoint('releaseCheckpoint'),
  stop('stop');

  const _V2Control(this.method);
  final String method;
}
