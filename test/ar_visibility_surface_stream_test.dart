import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/managers/ar_visibility_surface_stream.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('copies only the response ByteData view', () async {
    final channel = BasicMessageChannel<ByteData?>(
      'visibility_surface_stream_test',
      const BinaryCodec(),
    );
    channel.setMockMessageHandler((_) async {
      final bytes = Uint8List.fromList(<int>[0, 0x11, 0x22, 0x33, 0]);
      return ByteData.sublistView(bytes, 1, 4);
    });

    final stream = ARVisibilitySurfaceStream(0, channel: channel);
    expect(
      await stream.exchange(Uint8List.fromList(<int>[0x7f])),
      orderedEquals(<int>[0x11, 0x22, 0x33]),
    );

    await stream.dispose();
    channel.setMockMessageHandler(null);
  });
}
