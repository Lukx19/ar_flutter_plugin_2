/// Dart clients of the Android (later iOS) visibility grid platform shell:
/// the UI-isolate control client and the grid-isolate ingest pull client.
/// Message formats are the frozen platform contract in
/// `package:ar_flutter_plugin_2/visibility_grid_contract/`.
library;

import 'dart:typed_data';

import 'package:flutter/services.dart';

import '../visibility_grid_contract/visibility_grid_contract.dart';

/// Tracking state byte of the ingest header (offset 48), as the shell writes
/// it. The contract leaves the values open; these mirror ARCore's states.
abstract final class IngestTrackingState {
  static const int stopped = 0;
  static const int tracking = 1;
  static const int paused = 2;
}

/// Configuration sent with the control start message.
final class VisibilityGridShellStart {
  const VisibilityGridShellStart({
    this.depthSourcePreference = DepthSourcePreferenceCode.automatic,
    this.runMode = GridRunModeCode.shadow,
    this.pointCloud = false,
    this.markerDetection = false,
    this.markerDetectionHz = 0,
    this.markerSizeMetres = 0,
    this.recording = false,
    this.rendererOutput = false,
    this.groupEpoch = 0,
  });

  final int depthSourcePreference;
  final int runMode;

  /// Also send feature points when depth is available.
  final bool pointCloud;
  final bool markerDetection;
  final int markerDetectionHz;
  final double markerSizeMetres;

  /// Write an ingest recording (debug builds only).
  final bool recording;
  final bool rendererOutput;

  /// AR session generation the shell starts counting from.
  final int groupEpoch;

  int get flags =>
      (markerDetection ? ShellControlLayout.flagMarkerDetection : 0) |
      (recording ? ShellControlLayout.flagRecording : 0) |
      (rendererOutput ? ShellControlLayout.flagRendererOutput : 0) |
      (pointCloud ? ShellControlLayout.flagPointCloud : 0);
}

/// UI-isolate client of the control channel
/// (`capture3d/visibility_grid/control`). Each call sends one 32 B shell
/// control message and returns the platform's status code, or
/// [NativeStatusCode.contractMessageMalformed] for an unreadable reply.
final class VisibilityGridShellControl {
  VisibilityGridShellControl({BinaryMessenger? messenger})
      : _messenger = messenger ?? ServicesBinding.instance.defaultBinaryMessenger;

  final BinaryMessenger _messenger;
  final ShellControlView _view = ShellControlView();

  Future<NativeStatusCode> start(VisibilityGridShellStart config) => _send(
        ShellControlKind.start,
        config.flags,
        depthSourcePreference: config.depthSourcePreference,
        runMode: config.runMode,
        markerDetectionHz: config.markerDetectionHz,
        markerSizeMetres: config.markerSizeMetres,
        groupEpoch: config.groupEpoch,
      );

  Future<NativeStatusCode> stop() => _send(ShellControlKind.stop, 0);

  Future<NativeStatusCode> setMarkerDetection({
    required bool enabled,
    int hz = 0,
    double markerSizeMetres = 0,
  }) =>
      _send(
        ShellControlKind.setMarkerDetection,
        enabled ? ShellControlLayout.flagMarkerDetection : 0,
        markerDetectionHz: hz,
        markerSizeMetres: markerSizeMetres,
      );

  /// Starts or stops the debug ingest recording; release builds answer
  /// [NativeStatusCode.recordingWriteFailed] when asked to record.
  Future<NativeStatusCode> setRecording(bool enabled) => _send(
      ShellControlKind.setRecording,
      enabled ? ShellControlLayout.flagRecording : 0);

  /// Encodes one control message into a fresh 32 B buffer.
  ByteData encode(
    int kind,
    int flags, {
    int depthSourcePreference = DepthSourcePreferenceCode.automatic,
    int runMode = GridRunModeCode.shadow,
    int markerDetectionHz = 0,
    double markerSizeMetres = 0,
    int groupEpoch = 0,
  }) {
    final data = ByteData(ShellControlLayout.bytes);
    _view
      ..attach(data)
      ..write(
        kind: kind,
        flags: flags,
        depthSourcePreference: depthSourcePreference,
        runMode: runMode,
        markerDetectionHz: markerDetectionHz,
        markerSizeMetres: markerSizeMetres,
        groupEpoch: groupEpoch,
      );
    return data;
  }

  Future<NativeStatusCode> _send(
    int kind,
    int flags, {
    int depthSourcePreference = DepthSourcePreferenceCode.automatic,
    int runMode = GridRunModeCode.shadow,
    int markerDetectionHz = 0,
    double markerSizeMetres = 0,
    int groupEpoch = 0,
  }) async {
    final reply = await _messenger.send(
      VisibilityGridChannels.control,
      encode(kind, flags,
          depthSourcePreference: depthSourcePreference,
          runMode: runMode,
          markerDetectionHz: markerDetectionHz,
          markerSizeMetres: markerSizeMetres,
          groupEpoch: groupEpoch),
    );
    return decodeStatus(reply);
  }

  /// Decodes the 4 B little-endian status reply.
  static NativeStatusCode decodeStatus(ByteData? reply) {
    if (reply == null || reply.lengthInBytes < 4) {
      return NativeStatusCode.contractMessageMalformed;
    }
    return NativeStatusCode.fromCode(reply.getInt32(0, Endian.little)) ??
        NativeStatusCode.contractMessageMalformed;
  }
}

/// Grid-isolate client of the ingest channel
/// (`capture3d/visibility_grid/ingest`). Background isolates cannot receive
/// platform-initiated messages, so the grid isolate pulls: each [pull] sends
/// the 8 B pull request and completes with the freshest unsent accepted
/// message, or an idle message after 500 ms without data. Keep one pull in
/// flight; the returned bytes stay valid until the next pull.
///
/// In a background isolate pass
/// `BackgroundIsolateBinaryMessenger.instance` after
/// `BackgroundIsolateBinaryMessenger.ensureInitialized(token)`.
final class VisibilityGridIngestPullClient {
  VisibilityGridIngestPullClient(this._messenger) {
    _request.attach(_requestData);
  }

  final BinaryMessenger _messenger;
  final ByteData _requestData = ByteData(IngestPullRequestView.bytes);
  final IngestPullRequestView _request = IngestPullRequestView();
  bool _inFlight = false;
  bool _closed = false;

  /// Bit k accepts ingest kind k (kinds 1-6 and 8, photo tie points).
  static const int acceptAll = 0x17e;

  Future<ByteData> pull([int acceptedKindMask = acceptAll]) async {
    if (_closed) {
      throw StateError('pull after close');
    }
    if (_inFlight) {
      throw StateError('one ingest pull may be in flight');
    }
    _inFlight = true;
    try {
      _request.write(acceptedKindMask);
      final reply =
          await _messenger.send(VisibilityGridChannels.ingest, _requestData);
      if (reply == null) {
        throw StateError('no ingest handler is registered on the platform');
      }
      return reply;
    } finally {
      _inFlight = false;
    }
  }

  Future<void> close() async => _closed = true;
}
