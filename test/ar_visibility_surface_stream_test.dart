import 'dart:async';
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

  test('timeout fences the binding and preserves an immutable attempt', () async {
    final pending = Completer<ByteData?>();
    final channel = BasicMessageChannel<ByteData?>(
      'visibility_surface_stream_timeout',
      const BinaryCodec(),
    );
    channel.setMockMessageHandler((message) async {
      expect(message, isNotNull);
      return pending.future;
    });

    final request = Uint8List.fromList(<int>[1, 2, 3]);
    final attempt = ARVisibilitySurfaceStreamAttempt(request);
    request[0] = 9;
    expect(attempt.requestBytes, orderedEquals(<int>[1, 2, 3]));

    final stream = ARVisibilitySurfaceStream(
      0,
      channel: channel,
    );
    await expectLater(
      stream.exchange(Uint8List.fromList(<int>[4]), timeout: const Duration(milliseconds: 1)),
      throwsA(isA<ARVisibilitySurfaceStreamUnknownOutcome>()),
    );
    expect(
      () => stream.exchange(Uint8List.fromList(<int>[5])),
      throwsA(isA<ARVisibilitySurfaceStreamUnknownOutcome>()),
    );

    await stream.dispose();
    channel.setMockMessageHandler(null);
  });

  test('dispose waits for an accepted invocation before returning', () async {
    final pending = Completer<ByteData?>();
    final channel = BasicMessageChannel<ByteData?>(
      'visibility_surface_stream_dispose_wait',
      const BinaryCodec(),
    );
    channel.setMockMessageHandler((_) => pending.future);

    final stream = ARVisibilitySurfaceStream(0, channel: channel);
    final exchange = stream.exchange(Uint8List.fromList(<int>[1]));
    await Future<void>.delayed(Duration.zero);

    var disposed = false;
    final dispose = stream.dispose().then<void>((_) => disposed = true);
    await Future<void>.delayed(Duration.zero);
    expect(disposed, isFalse);

    pending.complete(ByteData.sublistView(Uint8List.fromList(<int>[2])));
    expect(await exchange, orderedEquals(<int>[2]));
    await dispose;
    expect(disposed, isTrue);

    channel.setMockMessageHandler(null);
  });
}
