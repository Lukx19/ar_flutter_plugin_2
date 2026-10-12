import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/managers/ar_session_manager.dart';
import 'package:ar_flutter_plugin_2/models/ar_playback_config.dart';
import 'package:ar_flutter_plugin_2/models/ar_recording.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

class _Context extends Fake implements BuildContext {}

const _track = '5b0a4c1e-6f3c-4d8e-9a71-2c3d4e5f6a7b';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const channel = MethodChannel('arsession_41');
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return switch (call.method) {
        'startRecording' => {
          'path': '/files/recordings/a.mp4',
          'recording': true,
          'status': 'OK',
        },
        'stopRecording' => {
          'path': '/files/recordings/a.mp4',
          'recording': false,
          'status': 'NONE',
          'recordedTrackData': 3,
          'droppedTrackData': 1,
        },
        'recordTrackData' => true,
        _ => null,
      };
    });
  });

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('recording calls carry the request and parse the status', () async {
    final session = ARSessionManager(
      41,
      _Context(),
      PlaneDetectionConfig.horizontal,
    );
    final started = await session.startRecording(
      ARRecordingRequest(
        path: 'recordings/a.mp4',
        trackId: _track,
        trackMimeType: 'application/x-capture3d-actions',
        trackMetadata: Uint8List.fromList([1, 2]),
      ),
    );
    expect(started.recording, isTrue);
    expect(started.status, 'OK');
    expect(calls.single.arguments, {
      'path': 'recordings/a.mp4',
      'trackId': _track,
      'trackMimeType': 'application/x-capture3d-actions',
      'trackMetadata': Uint8List.fromList([1, 2]),
    });
    expect(
      await session.recordTrackData(_track, Uint8List.fromList([9])),
      isTrue,
    );
    expect(calls.last.arguments, {
      'trackId': _track,
      'data': Uint8List.fromList([9]),
    });
    final stopped = await session.stopRecording();
    expect(stopped.recording, isFalse);
    expect(stopped.recordedTrackData, 3);
    expect(stopped.droppedTrackData, 1);
    expect((await session.recordingStatus()).path, isNull);
  });

  test(
    'playback track records reach the callback; malformed ones are skipped',
    () async {
      final session = ARSessionManager(
        41,
        _Context(),
        PlaneDetectionConfig.horizontal,
      );
      final received = <ARPlaybackTrackRecord>[];
      session.onArPlaybackTrackData = received.addAll;
      await messenger.handlePlatformMessage(
        'arsession_41',
        const StandardMethodCodec().encodeMethodCall(
          MethodCall('onArPlaybackTrackData', [
            {
              'trackId': _track,
              'frameTimestampNs': 1234,
              'data': Uint8List.fromList([7]),
            },
            {'trackId': _track},
            'junk',
          ]),
        ),
        (_) {},
      );
      expect(received, hasLength(1));
      expect(received.single.frameTimestampNs, 1234);
      expect(received.single.data, [7]);
    },
  );

  test('playback config sends track ids only when present', () {
    const plain = ARPlaybackConfig(datasetPath: 'recordings/a.mp4');
    expect(
      plain.toCreationParams().containsKey('arcorePlaybackTrackIds'),
      isFalse,
    );
    final withTracks = plain.withTrackIds(const [_track]);
    expect(withTracks.toCreationParams()['arcorePlaybackTrackIds'], [_track]);
    expect(withTracks, isNot(plain));
    expect(withTracks.withRestartGeneration(2).trackIds, [_track]);
    expect(withTracks, plain.withTrackIds(const [_track]));
  });

  test('status parses a missing reply as no recording', () {
    expect(ARRecordingStatus.fromMap(null).recording, isFalse);
    expect(ARRecordingStatus.fromMap(null).status, 'NONE');
  });
}
