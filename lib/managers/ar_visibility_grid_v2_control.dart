import 'dart:async';
import 'dart:ui' as ui;
import 'dart:typed_data';

import 'package:flutter/services.dart';

import 'ar_visibility_surface_stream.dart';

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
  Uint8List? _bindingQualifier;
  bool _closed = false;

  /// Sends a byte-only `start` request and returns the byte-only response.
  ///
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel fails, and [StateError] when native returns a non-byte response.
  Future<Uint8List> start(Uint8List request) async {
    final response = await _invoke('start', request);
    await _claimBindingSnapshotIfAvailable();
    return response;
  }

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
  /// This no-argument call supplies the claimed qualifier internally and
  /// returns the native dispose result.
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel cannot apply the fence.
  /// Claims the opaque native binding tokens from [snapshot] for the next
  /// argument-free [dispose] call. The tokens remain an internal transport
  /// detail; callers still use the public no-argument lifecycle method.
  ///
  /// Throws [StateError] when either token is not a 16-byte byte list.
  void bindSnapshot(Map<Object?, Object?> snapshot) {
    Uint8List token(String key) {
      final value = snapshot[key];
      if (value is! Uint8List || value.length != 16) {
        throw StateError('V2 $key must be exactly 16 bytes.');
      }
      return value;
    }

    _bindingQualifier = Uint8List.fromList(<int>[
      ...token('nativeStreamToken'),
      ...token('workerBindingToken'),
    ]);
  }

  /// Fences only the currently claimed V2 binding.
  ///
  /// The public API is intentionally argument-free. When [bindSnapshot] has
  /// claimed a binding, its qualifier is supplied internally so a stale
  /// control object cannot dispose a replacement. An unclaimed object still
  /// sends a null argument and lets the native endpoint reject the request;
  /// it must not guess a replacement identity.
  ///
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel cannot apply the fence.
  Future<void> dispose() async {
    if (_closed) return;
    try {
      await _claimBindingSnapshotIfAvailable();
      await _channel.invokeMethod<void>('disposeBinding', _bindingQualifier);
    } finally {
      _closed = true;
    }
  }

  Future<void> _claimBindingSnapshotIfAvailable() async {
    if (_bindingQualifier != null) return;
    try {
      final raw = await _channel.invokeMethod<Object?>('bindingSnapshot');
      if (raw is Map) {
        bindSnapshot(Map<Object?, Object?>.from(raw));
      }
    } on MissingPluginException {
      // Compatibility/fake endpoints may not expose the optional snapshot.
      // Disposal remains qualified when a V2 native snapshot is available.
    }
  }
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
  /// returns one [Uint8List]. Public `dispose` takes no argument and supplies
  /// its claimed qualifier internally. The stream
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
  Uint8List? _bindingQualifier;
  bool _closed = false;
  bool _abandoned = false;
  Future<Map<Object?, Object?>>? _disposeFuture;

  /// Claims the native binding tokens returned by `bindingSnapshot`.
  /// Throws [StateError] when either token is not a 16-byte [Uint8List].
  void bindSnapshot(Map<Object?, Object?> snapshot) {
    Uint8List token(String key) {
      final value = snapshot[key];
      if (value is! Uint8List || value.length != 16) {
        throw StateError('V2 $key must be exactly 16 bytes.');
      }
      return value;
    }

    _bindingQualifier = Uint8List.fromList(<int>[
      ...token('nativeStreamToken'),
      ...token('workerBindingToken'),
    ]);
  }

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
    final response = await _controlChannel.invokeMethod<Object?>(
      operation.method,
      _qualify(request),
    );
    if (response is! Uint8List) {
      throw StateError('V2 control returned a non-byte response.');
    }
    return _authenticate(response);
  }

  /// Exchanges one binary stream envelope and returns its binary response.
  /// Throws [StateError] when closed or no response arrives, and propagates
  /// [PlatformException] or [MissingPluginException] on channel failure.
  Future<Uint8List> exchange(
    Uint8List request, {
    Duration? timeout,
  }) async {
    _ensureOpen();
    final operation = _streamChannel.send(
      ByteData.sublistView(_qualify(request)),
    );
    ByteData? response;
    try {
      response =
          timeout == null ? await operation : await operation.timeout(timeout);
    } on TimeoutException {
      // A timeout does not cancel the engine invocation. Fence this binding
      // before its caller can attempt another request; the late reply is
      // intentionally ignored by the abandoned binding.
      _abandoned = true;
      throw const ARVisibilitySurfaceStreamUnknownOutcome();
    }
    if (response == null) {
      throw StateError('V2 stream returned no response.');
    }
    return _authenticate(Uint8List.fromList(
      response.buffer.asUint8List(
        response.offsetInBytes,
        response.lengthInBytes,
      ),
    ));
  }

  /// Invokes native `bindingSnapshot` and reads bounded scalar telemetry on
  /// the same native executor as
  /// control and exchange operations. No surface payload bytes are returned.
  /// The no-argument call returns a scalar [Map] snapshot.
  ///
  /// Exact keys/types: `bindingGeneration`, `streamToken`, `acceptedControls`,
  /// `closedResources`, `sessionGeneration`, `groupGeneration`,
  /// `coverageEpoch`, `viewId`, `viewGeneration`, `lifecycleSequence`, and
  /// `operationGeneration` are [int]; `initialTransactionQueued` and
  /// `disposed` are [bool]; `controlRequestId`, `sessionId`, and
  /// `captureGroupId` are nullable [String]; `arSessionIdentity`,
  /// `viewInstanceId`, `nativeStreamToken`, and `workerBindingToken` are
  /// 16-byte [Uint8List]; `executorTrace` is `List<String>`.
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
    final existing = _disposeFuture;
    if (existing != null) return existing;
    final future = () async {
      try {
        final response = await _controlChannel.invokeMethod<Object?>(
          'disposeBinding',
          _requireQualifier(),
        );
        if (response is! Map) {
          throw StateError('V2 binding returned no teardown receipt.');
        }
        return Map<Object?, Object?>.from(response);
      } finally {
        _closed = true;
      }
    }();
    _disposeFuture = future;
    return future;
  }

  void _ensureOpen() {
    if (_closed) throw StateError('V2 worker binding is closed.');
    if (_abandoned) {
      throw const ARVisibilitySurfaceStreamUnknownOutcome();
    }
  }

  Uint8List _requireQualifier() =>
      _bindingQualifier ??
      (throw StateError('V2 worker binding has not claimed native tokens.'));

  Uint8List _qualify(Uint8List payload) => Uint8List.fromList(<int>[
        ..._requireQualifier(),
        ...payload,
      ]);

  Uint8List _authenticate(Uint8List response) {
    final qualifier = _requireQualifier();
    if (response.length < qualifier.length) {
      throw StateError('V2 response omitted its binding qualifier.');
    }
    for (var index = 0; index < qualifier.length; index++) {
      if (response[index] != qualifier[index]) {
        throw StateError('V2 response used a stale binding qualifier.');
      }
    }
    return Uint8List.fromList(response.sublist(qualifier.length));
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
