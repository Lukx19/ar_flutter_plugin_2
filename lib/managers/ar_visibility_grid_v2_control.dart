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
  /// Creates a control and immediately starts one hidden connection-time
  /// `bindingSnapshot` invocation. The snapshot's two 16-byte tokens are
  /// owned for this control's lifetime; disposal never snapshots replacement
  /// state. [initialBindingSnapshot] is the deterministic host/fake seam.
  ARVisibilityGridV2Control(
    int viewId, {
    MethodChannel? channel,
    Map<Object?, Object?>? initialBindingSnapshot,
  }) : _channel =
            channel ?? MethodChannel('visibility_grid_v2_control_$viewId') {
    _bindingReady = initialBindingSnapshot == null
        ? _captureBindingAtConnection()
        : Future<void>(() {
            bindSnapshot(initialBindingSnapshot);
          });
  }

  /// Connects to a V2 endpoint and captures its binding identity before the
  /// control is handed to callers. A later native replacement cannot change
  /// the qualifier owned by this object.
  static Future<ARVisibilityGridV2Control> connect(
    int viewId, {
    MethodChannel? channel,
  }) async {
    final control = ARVisibilityGridV2Control(viewId, channel: channel);
    await control.bindingReady;
    return control;
  }

  final MethodChannel _channel;
  late final Future<void> _bindingReady;
  Uint8List? _bindingQualifier;
  Map<Object?, Object?>? _disposeReceipt;
  bool _closed = false;

  /// Completes after the connection-time native identity snapshot has been
  /// captured (or an optional compatibility endpoint has declined it).
  Future<void> get bindingReady => _bindingReady;

  /// Sends a byte-only `start` request and returns the byte-only response.
  ///
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel fails, and [StateError] when native returns a non-byte response.
  Future<Uint8List> start(Uint8List request) async {
    await _bindingReady;
    final response = await _invoke('start', request);
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
  /// This no-argument call supplies the connection-time qualifier internally
  /// and returns the native teardown receipt map.
  /// Native receives either null or the exact 32-byte concatenation of the
  /// owned `nativeStreamToken` and `workerBindingToken`; the public method
  /// remains argument-free. The returned map includes `closedResources`.
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel cannot apply the fence.
  ///
  /// The first claim wins; a replacement claim is rejected. Throws [StateError]
  /// when either token is not a 16-byte byte list.
  void bindSnapshot(Map<Object?, Object?> snapshot) {
    final qualifier = _qualifierFromSnapshot(snapshot);
    final existing = _bindingQualifier;
    if (existing != null) {
      if (!_bytesEqual(existing, qualifier)) {
        throw StateError('V2 binding qualifier cannot be replaced.');
      }
      return;
    }
    _bindingQualifier = qualifier;
  }

  /// Fences only the currently claimed V2 binding.
  ///
  /// The public API is intentionally argument-free. Its connection-time
  /// qualifier is supplied internally so a stale control object cannot
  /// dispose a replacement. An endpoint without the optional snapshot seam
  /// sends a null argument and lets native reject the request; it must not
  /// guess a replacement identity.
  ///
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel cannot apply the fence.
  Future<Map<Object?, Object?>> dispose() async {
    if (_closed) {
      return _disposeReceipt ??
          (throw StateError('V2 control has no teardown receipt.'));
    }
    try {
      await _bindingReady;
      final raw = await _channel.invokeMethod<Object?>(
        'disposeBinding',
        _bindingQualifier,
      );
      final receipt = _teardownReceipt(raw);
      _disposeReceipt = receipt;
      return receipt;
    } finally {
      _closed = true;
    }
  }

  Future<void> _captureBindingAtConnection() async {
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

Uint8List _qualifierFromSnapshot(Map<Object?, Object?> snapshot) {
  Uint8List token(String key) {
    final value = snapshot[key];
    if (value is! Uint8List || value.length != 16) {
      throw StateError('V2 $key must be exactly 16 bytes.');
    }
    return value;
  }

  return Uint8List.fromList(<int>[
    ...token('nativeStreamToken'),
    ...token('workerBindingToken'),
  ]);
}

bool _bytesEqual(Uint8List left, Uint8List right) {
  if (left.length != right.length) return false;
  for (var index = 0; index < left.length; index++) {
    if (left[index] != right[index]) return false;
  }
  return true;
}

Map<Object?, Object?> _teardownReceipt(Object? raw) {
  if (raw is! Map) {
    throw StateError('V2 binding returned no teardown receipt.');
  }
  final receipt = Map<Object?, Object?>.from(raw);
  if (receipt['closedResources'] is! num) {
    throw StateError('V2 teardown receipt omitted closed resources.');
  }
  return receipt;
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
  /// its claimed qualifier internally. Hidden `bindingSnapshot` takes no
  /// argument and returns the scalar identity map. `disposeBinding` receives
  /// a nullable exact 32-byte qualifier and returns a teardown map. The
  /// independent `abandonBinding` uses the same qualifier and returns the old
  /// binding's teardown map while installing a fresh identity. The stream
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
  Future<Map<Object?, Object?>>? _abandonFuture;

  /// Claims the native binding tokens returned by `bindingSnapshot`.
  /// Throws [StateError] when either token is not a 16-byte [Uint8List].
  void bindSnapshot(Map<Object?, Object?> snapshot) {
    final qualifier = _qualifierFromSnapshot(snapshot);
    final existing = _bindingQualifier;
    if (existing != null) {
      if (!_bytesEqual(existing, qualifier)) {
        throw StateError('V2 binding qualifier cannot be replaced.');
      }
      return;
    }
    _bindingQualifier = qualifier;
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
        return _teardownReceipt(response);
      } finally {
        _closed = true;
      }
    }();
    _disposeFuture = future;
    return future;
  }

  /// Uses the independent native abandon/fence path when the serial dispose
  /// call is stalled behind an admitted invocation. Native returns the old
  /// binding's teardown receipt and installs a fresh identity on the channel;
  /// this object is permanently closed afterward.
  Future<Map<Object?, Object?>> abandonAndSnapshot() async {
    final existing = _abandonFuture;
    if (existing != null) return existing;
    final future = () async {
      try {
        final response = await _controlChannel.invokeMethod<Object?>(
          'abandonBinding',
          _requireQualifier(),
        );
        return _teardownReceipt(response);
      } finally {
        _closed = true;
        _abandoned = true;
      }
    }();
    _abandonFuture = future;
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
