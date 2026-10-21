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

  test('a version 1 recording replays as the version 2 golden in the '
      'canonical frame', () {
    final old = ByteData.sublistView(
        File('test_assets/ingest_contract_v1/ingest_recording.bin')
            .readAsBytesSync());
    final now = readGolden('ingest_recording.bin');
    final v1 = IngestRecordingCursor(), v2 = IngestRecordingCursor();
    expect(v1.open(old, old.lengthInBytes), ContractStatus.ok);
    expect(v2.open(now, now.lengthInBytes), ContractStatus.ok);
    expect((IngestRecordingHeaderView()..attach(old)).version, 1);
    final a = List<double>.filled(16, 0), b = List<double>.filled(16, 0);
    final photo1 = PhotoSavedView(), photo2 = PhotoSavedView();
    final r1 = List<double>.filled(12, 0), r2 = List<double>.filled(12, 0);
    var messages = 0;
    while (v1.moveNext()) {
      expect(v2.moveNext(), isTrue);
      final m1 = v1.message, m2 = v2.message;
      expect([m1.version, m2.version], [1, 2]);
      expect(m1.kind, m2.kind);
      expect(m1.sequence, m2.sequence);
      m1.readWorldFromCamera(a);
      m2.readWorldFromCamera(b);
      expect(a, b, reason: 'message ${m1.sequence} pose');
      expect([m1.fx, m1.fy], [m2.fx, m2.fy]);
      // Same stored bytes, version 1 meaning the pixel-edge convention.
      expect([m1.cx, m1.cy], [m2.cx - 0.5, m2.cy - 0.5]);
      if (m1.kind == IngestKind.photoSaved) {
        photo1
          ..attach(old, m1.payloadOffset)
          ..contractVersion = m1.version;
        photo2
          ..attach(now, m2.payloadOffset)
          ..contractVersion = m2.version;
        photo1.readWorldFromCameraRows(r1);
        photo2.readWorldFromCameraRows(r2);
        expect(r1, r2);
        expect([photo1.cx, photo1.cy], [photo2.cx - 0.5, photo2.cy - 0.5]);
      }
      messages++;
    }
    expect(v2.moveNext(), isFalse);
    expect(messages, 10);
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
