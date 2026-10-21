import 'dart:io';
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/visibility_grid_contract/visibility_grid_contract.dart';
import 'package:flutter_test/flutter_test.dart';

import 'contract_fixtures.dart';

/// Ten messages cycling through every ingest golden kind.
Uint8List buildRecording() {
  final messages = <Uint8List>[];
  final builders = ingestGoldens.values.toList();
  for (var i = 0; i < 10; i++) {
    final message = builders[i % builders.length]();
    (IngestMessageView()..attach(ByteData.sublistView(message))).sequence = i;
    messages.add(message);
  }
  final total = IngestRecordingLayout.headerBytes +
      messages.fold<int>(0, (sum, m) => sum + 4 + m.length);
  final bytes = Uint8List(total);
  final data = ByteData.sublistView(bytes);
  IngestRecordingHeaderView()
    ..attach(data)
    ..write(0x1122334455667788, 1760140800000000000, messages.length);
  var at = IngestRecordingLayout.headerBytes;
  for (final m in messages) {
    data.setUint32(at, m.length, Endian.little);
    bytes.setRange(at + 4, at + 4 + m.length, m);
    at += 4 + m.length;
  }
  return bytes;
}

void main() {
  test('recording of ten messages encodes byte-exact', () {
    expectGoldenBytes('ingest_recording.bin', buildRecording());
  });

  test('cursor replays every message in order', () {
    final data = readGolden('ingest_recording.bin');
    final cursor = IngestRecordingCursor();
    expect(cursor.open(data, data.lengthInBytes), ContractStatus.ok);
    final kinds = <int>[];
    var expected = 0;
    while (cursor.moveNext()) {
      expect(cursor.message.sequence, expected++);
      kinds.add(cursor.message.kind);
    }
    expect(cursor.lastStatus, ContractStatus.ok);
    expect(kinds, [0, 1, 2, 3, 4, 5, 6, 0, 1, 2]);
    cursor.rewind();
    expect(cursor.moveNext(), isTrue);
    expect(cursor.message.kind, IngestKind.idle);
  });

  test(
      'a version 1 recording replays as the version 2 golden in the '
      'canonical frame', () {
    final versionOneBytes = ByteData.sublistView(
        File('test_assets/ingest_contract_v1/ingest_recording.bin')
            .readAsBytesSync());
    final versionTwoBytes = readGolden('ingest_recording.bin');
    final versionOneCursor = IngestRecordingCursor();
    final versionTwoCursor = IngestRecordingCursor();
    expect(
        versionOneCursor.open(versionOneBytes, versionOneBytes.lengthInBytes),
        ContractStatus.ok);
    expect(
        versionTwoCursor.open(versionTwoBytes, versionTwoBytes.lengthInBytes),
        ContractStatus.ok);
    expect((IngestRecordingHeaderView()..attach(versionOneBytes)).version, 1);
    final versionOnePose = List<double>.filled(16, 0);
    final versionTwoPose = List<double>.filled(16, 0);
    final versionOnePhoto = PhotoSavedView();
    final versionTwoPhoto = PhotoSavedView();
    final versionOneRows = List<double>.filled(12, 0);
    final versionTwoRows = List<double>.filled(12, 0);
    final kinds = <int>{};
    var messages = 0;
    while (versionOneCursor.moveNext()) {
      expect(versionTwoCursor.moveNext(), isTrue);
      final versionOneMessage = versionOneCursor.message;
      final versionTwoMessage = versionTwoCursor.message;
      expect([versionOneMessage.version, versionTwoMessage.version], [1, 2]);
      expect(versionOneMessage.kind, versionTwoMessage.kind);
      expect(versionOneMessage.sequence, versionTwoMessage.sequence);
      kinds.add(versionOneMessage.kind);
      versionOneMessage.readWorldFromCamera(versionOnePose);
      versionTwoMessage.readWorldFromCamera(versionTwoPose);
      expect(versionOnePose, versionTwoPose,
          reason: 'message ${versionOneMessage.sequence} pose');
      expect([versionOneMessage.fx, versionOneMessage.fy],
          [versionTwoMessage.fx, versionTwoMessage.fy]);
      // Same stored bytes. Only a version 1 depth image meant the
      // pixel-edge reading; marker detections and the other kinds keep
      // their value.
      final shift = versionOneMessage.kind == IngestKind.depthImage ? 0.5 : 0.0;
      expect([
        versionOneMessage.cx,
        versionOneMessage.cy
      ], [
        versionTwoMessage.cx - shift,
        versionTwoMessage.cy - shift
      ], reason: 'kind ${versionOneMessage.kind}');
      if (versionOneMessage.kind == IngestKind.photoSaved) {
        versionOnePhoto
          ..attach(versionOneBytes, versionOneMessage.payloadOffset)
          ..contractVersion = versionOneMessage.version;
        versionTwoPhoto
          ..attach(versionTwoBytes, versionTwoMessage.payloadOffset)
          ..contractVersion = versionTwoMessage.version;
        versionOnePhoto.readWorldFromCameraRows(versionOneRows);
        versionTwoPhoto.readWorldFromCameraRows(versionTwoRows);
        expect(versionOneRows, versionTwoRows);
        expect([versionOnePhoto.cx, versionOnePhoto.cy],
            [versionTwoPhoto.cx - 0.5, versionTwoPhoto.cy - 0.5]);
      }
      messages++;
    }
    expect(versionTwoCursor.moveNext(), isFalse);
    expect(messages, 10);
    expect(kinds,
        containsAll([IngestKind.depthImage, IngestKind.markerDetections]));
  });

  test('truncated recording stops with a length error', () {
    final bytes = buildRecording();
    final data = ByteData.sublistView(bytes);
    final cursor = IngestRecordingCursor();
    expect(cursor.open(data, bytes.length - 3), ContractStatus.ok);
    var count = 0;
    while (cursor.moveNext()) {
      count++;
    }
    expect(count, 9);
    expect(cursor.lastStatus, ContractStatus.badLength);
    data.setUint16(4, 9, Endian.little);
    expect(cursor.open(data, bytes.length), ContractStatus.badVersion);
  });
}
