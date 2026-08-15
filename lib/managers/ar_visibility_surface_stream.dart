import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/services.dart';

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
  Future<void>? _inFlight;

  /// Sends one packed request. A second request waits for the first, keeping
  /// one outstanding binding invocation as required by chapter 8.
  Future<Uint8List> exchange(Uint8List request) async {
    _ensureOpen();
    final previous = _inFlight;
    if (previous != null) await previous;
    final operation = _exchangeNow(request);
    final completion = operation.then<void>((_) {});
    _inFlight = completion;
    try {
      return await operation;
    } finally {
      if (identical(_inFlight, completion)) _inFlight = null;
    }
  }

  Future<Uint8List> _exchangeNow(Uint8List request) async {
    final response = await _channel.send(ByteData.sublistView(request));
    if (response == null) {
      throw StateError('Visibility surface stream returned no response.');
    }
    return Uint8List.fromList(response.buffer.asUint8List(
      response.offsetInBytes,
      response.lengthInBytes,
    ));
  }

  Future<void> dispose() async {
    _closed = true;
    final inFlight = _inFlight;
    if (inFlight != null) await inFlight;
  }

  void _ensureOpen() {
    if (_closed) throw StateError('Visibility surface stream is disposed.');
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
    return Uint8List.fromList(
      response.buffer.asUint8List(
        response.offsetInBytes,
        response.offsetInBytes + response.lengthInBytes,
      ),
    );
  }
}
