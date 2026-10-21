import 'dart:typed_data';

import 'binary_support.dart';
import 'ingest_message.dart';

/// Ingest recording file: a 32 B header followed by `u32 length` plus one
/// ingest message, repeated. Recordings are the replay fixtures for host
/// tests.
///
/// Version 2 (coordinate frame migration U4b) records ingest contract
/// version 2 messages (camera poses as `T_world_cam`); version 1 recordings
/// hold contract version 1 messages (AR camera axes) and stay readable: each
/// message names its own contract version, which the readers convert.
abstract final class IngestRecordingLayout {
  static final int magic = contractMagic('C3IR');
  static const int version = 2;

  /// Recordings of contract version 1 messages.
  static const int arCameraPoseVersion = 1;
  static const int headerBytes = 32;
  static const int deviceModelHashOffset = 8; // u64
  static const int createdTimeNsOffset = 16; // i64, wall clock
  static const int messageCountOffset = 24; // u32, 0 when unknown
  static const int lengthPrefixBytes = 4;
}

final class IngestRecordingHeaderView extends ContractRecordView {
  @override
  int get recordBytes => IngestRecordingLayout.headerBytes;

  ContractStatus validate(int available) {
    final status = validateMagicAndVersion(
        data,
        offset,
        available,
        IngestRecordingLayout.headerBytes,
        IngestRecordingLayout.magic,
        IngestRecordingLayout.version);
    return status == ContractStatus.badVersion &&
            version == IngestRecordingLayout.arCameraPoseVersion
        ? ContractStatus.ok
        : status;
  }

  /// Recording format version (1 or 2).
  int get version => u16(4);

  void write(int deviceModelHash, int createdTimeNs, int messageCount) {
    clear();
    setU32(0, IngestRecordingLayout.magic);
    setU16(4, IngestRecordingLayout.version);
    setI64(IngestRecordingLayout.deviceModelHashOffset, deviceModelHash);
    setI64(IngestRecordingLayout.createdTimeNsOffset, createdTimeNs);
    setU32(IngestRecordingLayout.messageCountOffset, messageCount);
  }

  int get deviceModelHash => i64(IngestRecordingLayout.deviceModelHashOffset);
  int get createdTimeNs => i64(IngestRecordingLayout.createdTimeNsOffset);
  int get messageCount => u32(IngestRecordingLayout.messageCountOffset);
}

/// Allocation-free cursor over the messages of a recording held in memory.
final class IngestRecordingCursor {
  IngestRecordingCursor();

  final IngestRecordingHeaderView _header = IngestRecordingHeaderView();
  ByteData _data = ByteData(0);
  int _length = 0;
  int _next = 0;

  /// Offset and length of the current message after [moveNext].
  int messageOffset = 0;
  int messageLength = 0;

  /// The current message header, attached after a successful [moveNext].
  final IngestMessageView message = IngestMessageView();

  /// Starts reading the recording in `data[0, length)`; returns the header
  /// status.
  ContractStatus open(ByteData data, int length) {
    final header = _header;
    if (length < IngestRecordingLayout.headerBytes) {
      return ContractStatus.tooShort;
    }
    header.attach(data);
    final status = header.validate(length);
    if (status != ContractStatus.ok) return status;
    _data = data;
    _length = length;
    _next = IngestRecordingLayout.headerBytes;
    return ContractStatus.ok;
  }

  /// Rewinds to the first message.
  void rewind() => _next = IngestRecordingLayout.headerBytes;

  /// Advances to the next valid message; false at the end or on a malformed
  /// message (see [lastStatus]).
  bool moveNext() {
    lastStatus = ContractStatus.ok;
    if (_next + IngestRecordingLayout.lengthPrefixBytes > _length) return false;
    final len = _data.getUint32(_next, contractEndian);
    final start = _next + IngestRecordingLayout.lengthPrefixBytes;
    if (start + len > _length || len < IngestHeaderLayout.bytes) {
      lastStatus = ContractStatus.badLength;
      return false;
    }
    message.attach(_data, start);
    final status = message.validate(len);
    if (status != ContractStatus.ok) {
      lastStatus = status;
      return false;
    }
    messageOffset = start;
    messageLength = len;
    _next = start + len;
    return true;
  }

  ContractStatus lastStatus = ContractStatus.ok;
}

/// Shell control message kinds.
abstract final class ShellControlKind {
  static const int start = 1;
  static const int stop = 2;
  static const int setMarkerDetection = 3;
  static const int setRecording = 4;
}

/// Run mode of the grid as configured by the control channel.
abstract final class GridRunModeCode {
  static const int shadow = 0;
  static const int active = 1;
}

/// Depth source preference sent to the platform shell.
abstract final class DepthSourcePreferenceCode {
  static const int automatic = 0;
  static const int depth = 1;
  static const int pointsOnly = 2;
}

/// 32 B shell control message (UI isolate to platform, channel
/// `capture3d/visibility_grid/control`). Native code reads its configuration
/// from the start message, so one Dart flag switches both sides.
abstract final class ShellControlLayout {
  static final int magic = contractMagic('C3GC');
  static const int version = 1;
  static const int bytes = 32;
  static const int kindOffset = 6; // u8
  static const int flagsOffset = 7; // u8
  static const int depthSourcePreferenceOffset = 8; // u8
  static const int runModeOffset = 9; // u8
  static const int markerDetectionHzOffset = 10; // u16
  static const int markerSizeMetresOffset = 12; // f32
  static const int groupEpochOffset = 16; // u32
  static const int reservedOffset = 20; // u32 x3

  static const int flagMarkerDetection = 1;
  static const int flagRecording = 2;
  static const int flagRendererOutput = 4;
  static const int flagPointCloud = 8;
}

final class ShellControlView extends ContractRecordView {
  @override
  int get recordBytes => ShellControlLayout.bytes;

  ContractStatus validate(int available) {
    final status = validateMagicAndVersion(
        data,
        offset,
        available,
        ShellControlLayout.bytes,
        ShellControlLayout.magic,
        ShellControlLayout.version);
    if (status != ContractStatus.ok) return status;
    if (kind < ShellControlKind.start || kind > ShellControlKind.setRecording) {
      return ContractStatus.badKind;
    }
    return ContractStatus.ok;
  }

  void write({
    required int kind,
    required int flags,
    int depthSourcePreference = DepthSourcePreferenceCode.automatic,
    int runMode = GridRunModeCode.shadow,
    int markerDetectionHz = 0,
    double markerSizeMetres = 0,
    int groupEpoch = 0,
  }) {
    clear();
    setU32(0, ShellControlLayout.magic);
    setU16(4, ShellControlLayout.version);
    setU8(ShellControlLayout.kindOffset, kind);
    setU8(ShellControlLayout.flagsOffset, flags);
    setU8(
        ShellControlLayout.depthSourcePreferenceOffset, depthSourcePreference);
    setU8(ShellControlLayout.runModeOffset, runMode);
    setU16(ShellControlLayout.markerDetectionHzOffset, markerDetectionHz);
    setF32(ShellControlLayout.markerSizeMetresOffset, markerSizeMetres);
    setU32(ShellControlLayout.groupEpochOffset, groupEpoch);
  }

  int get kind => u8(ShellControlLayout.kindOffset);
  int get flags => u8(ShellControlLayout.flagsOffset);
  int get depthSourcePreference =>
      u8(ShellControlLayout.depthSourcePreferenceOffset);
  int get runMode => u8(ShellControlLayout.runModeOffset);
  int get markerDetectionHz => u16(ShellControlLayout.markerDetectionHzOffset);
  double get markerSizeMetres => f32(ShellControlLayout.markerSizeMetresOffset);
  int get groupEpoch => u32(ShellControlLayout.groupEpochOffset);
}
