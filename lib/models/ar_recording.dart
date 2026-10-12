import 'dart:typed_data';

import 'package:flutter/foundation.dart';

/// Debug/profile ARCore session recording (Android only): the running
/// session writes a Recording & Playback MP4 dataset at [path] (absolute, or
/// relative to the app's external files directory) with one optional custom
/// data track [trackId] the app fills with
/// `ARSessionManager.recordTrackData`.
///
/// The recording keeps the session's Shared Camera; it stops automatically
/// when the session pauses.
@immutable
class ARRecordingRequest {
  const ARRecordingRequest({
    required this.path,
    this.trackId,
    this.trackMimeType,
    this.trackMetadata,
  });

  final String path;

  /// UUID string of the custom data track, or null for none.
  final String? trackId;
  final String? trackMimeType;
  final Uint8List? trackMetadata;

  Map<String, Object> toArguments() => <String, Object>{
    'path': path,
    if (trackId != null) 'trackId': trackId!,
    if (trackMimeType != null) 'trackMimeType': trackMimeType!,
    if (trackMetadata != null) 'trackMetadata': trackMetadata!,
  };
}

/// Native recording state (`startRecording`, `stopRecording` and
/// `recordingStatus` replies).
@immutable
class ARRecordingStatus {
  const ARRecordingStatus({
    required this.path,
    required this.recording,
    required this.status,
    this.recordedTrackData = 0,
    this.droppedTrackData = 0,
    this.pendingTrackData = 0,
  });

  static const ARRecordingStatus none = ARRecordingStatus(
    path: null,
    recording: false,
    status: 'NONE',
  );

  factory ARRecordingStatus.fromMap(Object? map) {
    if (map is! Map) return none;
    int count(String key) => (map[key] as num?)?.toInt() ?? 0;
    return ARRecordingStatus(
      path: map['path'] as String?,
      recording: map['recording'] == true,
      status: map['status'] as String? ?? 'NONE',
      recordedTrackData: count('recordedTrackData'),
      droppedTrackData: count('droppedTrackData'),
      pendingTrackData: count('pendingTrackData'),
    );
  }

  /// Absolute dataset path of the last or running recording.
  final String? path;
  final bool recording;

  /// ARCore `RecordingStatus`: NONE, OK or IO_ERROR.
  final String status;
  final int recordedTrackData;
  final int droppedTrackData;
  final int pendingTrackData;

  @override
  String toString() =>
      'ARRecordingStatus($path, recording: $recording, '
      '$status, recorded: $recordedTrackData, dropped: $droppedTrackData)';
}

/// One custom track record of a playback frame (`onArPlaybackTrackData`).
@immutable
class ARPlaybackTrackRecord {
  const ARPlaybackTrackRecord({
    required this.trackId,
    required this.frameTimestampNs,
    required this.data,
  });

  final String trackId;

  /// Timestamp of the frame the record was attached to while recording.
  final int frameTimestampNs;
  final Uint8List data;

  /// Records of an `onArPlaybackTrackData` call; malformed entries skipped.
  static List<ARPlaybackTrackRecord> parseEvent(Object? arguments) {
    if (arguments is! List) return const [];
    final records = <ARPlaybackTrackRecord>[];
    for (final entry in arguments) {
      if (entry is! Map) continue;
      final id = entry['trackId'];
      final timestamp = entry['frameTimestampNs'];
      final data = entry['data'];
      if (id is String && timestamp is int && data is Uint8List) {
        records.add(
          ARPlaybackTrackRecord(
            trackId: id,
            frameTimestampNs: timestamp,
            data: data,
          ),
        );
      }
    }
    return records;
  }
}
