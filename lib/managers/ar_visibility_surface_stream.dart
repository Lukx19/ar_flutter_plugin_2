import 'dart:async';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/services.dart';

/// Immutable bytes pinned for one platform invocation. The bytes remain
/// unchanged while the invocation is in flight, including after a timeout.
final class ARVisibilitySurfaceStreamAttempt {
  ARVisibilitySurfaceStreamAttempt(Uint8List request)
      : requestBytes = Uint8List.fromList(request);

  final Uint8List requestBytes;
}

/// Raised when a timeout leaves the native invocation outcome unknown.
final class ARVisibilitySurfaceStreamUnknownOutcome implements Exception {
  const ARVisibilitySurfaceStreamUnknownOutcome();

  @override
  String toString() =>
      'ARVisibilitySurfaceStreamUnknownOutcome: binding must be replaced.';
}

/// Worker-facing packed T2 stream. Ordinary surface bytes must stay in the
/// worker isolate; callers receive only the response for their request.
final class ARVisibilitySurfaceStream {
  ARVisibilitySurfaceStream(
    int viewId, {
    BasicMessageChannel<ByteData?>? channel,
  }) : _channel = channel ??
            BasicMessageChannel<ByteData?>(
              'visibility_surface_stream_$viewId',
              const BinaryCodec(),
            );

  final BasicMessageChannel<ByteData?> _channel;
  bool _closed = false;
  bool _abandoned = false;
  Future<void>? _inFlight;

  /// Sends one packed request. A second request waits for the first, keeping
  /// one outstanding binding invocation as required by chapter 8.
  Future<Uint8List> exchange(
    Uint8List request, {
    Duration? timeout,
  }) async {
    _ensureOpen();
    final attempt = ARVisibilitySurfaceStreamAttempt(request);
    final previous = _inFlight;
    if (previous != null) await previous;
    final operation = _exchangeNow(attempt.requestBytes);
    final completion = operation.then<void>((_) {});
    _inFlight = completion;
    try {
      final response =
          timeout == null ? await operation : await operation.timeout(timeout);
      return response;
    } on TimeoutException {
      _abandoned = true;
      _inFlight = null;
      throw const ARVisibilitySurfaceStreamUnknownOutcome();
    } finally {
      if (identical(_inFlight, completion)) _inFlight = null;
    }
  }

  Future<Uint8List> _exchangeNow(Uint8List request) async {
    final response = await _channel.send(ByteData.sublistView(request));
    if (response == null) {
      throw StateError('Visibility surface stream returned no response.');
    }
    return _copyResponseBytes(response);
  }

  Future<void> dispose() async {
    _closed = true;
    _abandoned = true;
    final inFlight = _inFlight;
    // Marking the binding abandoned fences new calls, but an invocation that
    // was already accepted still owns a native reply. Wait for that reply so
    // platform-view teardown cannot race the serial worker. A timed-out
    // attempt clears `_inFlight`, so disposal remains bounded after the
    // unknown-outcome fence.
    if (inFlight != null) await inFlight;
  }

  void _ensureOpen() {
    if (_closed) throw StateError('Visibility surface stream is disposed.');
    if (_abandoned) {
      throw const ARVisibilitySurfaceStreamUnknownOutcome();
    }
  }
}

/// Stateless entry point for a real Flutter background isolate.
///
/// The caller must invoke this from the spawned isolate and pass the root
/// token captured before spawning. No ordinary surface collection is
/// materialized by the root isolate; only the bounded packed request and
/// response cross the isolate boundary.
final class ARVisibilitySurfaceStreamWorker {
  const ARVisibilitySurfaceStreamWorker._();

  static Future<Uint8List> exchange({
    required ui.RootIsolateToken rootIsolateToken,
    required int viewId,
    required Uint8List request,
  }) async {
    BackgroundIsolateBinaryMessenger.ensureInitialized(rootIsolateToken);
    final channel = BasicMessageChannel<ByteData?>(
      'visibility_surface_stream_$viewId',
      const BinaryCodec(),
      binaryMessenger: BackgroundIsolateBinaryMessenger.instance,
    );
    final response = await channel.send(ByteData.sublistView(request));
    if (response == null) {
      throw StateError('Visibility surface stream returned no response.');
    }
    return _copyResponseBytes(response);
  }
}

/// Low-rate fixed-width numeric telemetry for one V2 stream binding.
///
/// Native throttles this channel to five samples per second. The 168-byte
/// result contains counters only and never includes surface or packet bytes.
final class ARVisibilitySurfaceMetrics {
  ARVisibilitySurfaceMetrics(int viewId)
      : _channel = BasicMessageChannel<ByteData>(
          'visibility_surface_metrics_$viewId',
          const BinaryCodec(),
        );

  final BasicMessageChannel<ByteData> _channel;

  Future<ByteData?> sample() => _channel.send(ByteData(0));
}

Uint8List _copyResponseBytes(ByteData response) => Uint8List.fromList(
      response.buffer.asUint8List(
        response.offsetInBytes,
        response.lengthInBytes,
      ),
    );
