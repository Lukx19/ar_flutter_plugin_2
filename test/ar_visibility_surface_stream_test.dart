import 'dart:async';

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
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<ByteData?>(channel, (_) async {
      final bytes = Uint8List.fromList(<int>[0, 0x11, 0x22, 0x33, 0]);
      return ByteData.sublistView(bytes, 1, 4);
    });

    final stream = ARVisibilitySurfaceStream(0, channel: channel);
    expect(
      await stream.exchange(Uint8List.fromList(<int>[0x7f])),
      orderedEquals(<int>[0x11, 0x22, 0x33]),
    );

    await stream.dispose();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<ByteData?>(channel, null);
  });

  test('timeout fences the binding and preserves an immutable attempt',
      () async {
    final pending = Completer<ByteData?>();
    final channel = BasicMessageChannel<ByteData?>(
      'visibility_surface_stream_timeout',
      const BinaryCodec(),
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<ByteData?>(channel, (message) async {
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
      stream.exchange(Uint8List.fromList(<int>[4]),
          timeout: const Duration(milliseconds: 1)),
      throwsA(isA<ARVisibilitySurfaceStreamUnknownOutcome>()),
    );
    expect(
      () => stream.exchange(Uint8List.fromList(<int>[5])),
      throwsA(isA<ARVisibilitySurfaceStreamUnknownOutcome>()),
    );

    pending.complete(ByteData.sublistView(Uint8List.fromList(<int>[6])));
    await stream.callbacksDrained;
    await stream.dispose();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<ByteData?>(channel, null);
  });

  test('dispose fences without waiting and exposes callback drain', () async {
    final pending = Completer<ByteData?>();
    final channel = BasicMessageChannel<ByteData?>(
      'visibility_surface_stream_dispose_wait',
      const BinaryCodec(),
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<ByteData?>(
            channel, (_) => pending.future);

    final stream = ARVisibilitySurfaceStream(0, channel: channel);
    final exchange = stream.exchange(Uint8List.fromList(<int>[1]));
    await Future<void>.delayed(Duration.zero);

    var disposed = false;
    final dispose = stream.dispose().then<void>((_) => disposed = true);
    await Future<void>.delayed(Duration.zero);
    expect(disposed, isTrue);
    var drained = false;
    final callbacksDrained = stream.callbacksDrained.then<void>((_) {
      drained = true;
    });
    await Future<void>.delayed(Duration.zero);
    expect(drained, isFalse);

    pending.complete(ByteData.sublistView(Uint8List.fromList(<int>[2])));
    expect(await exchange, orderedEquals(<int>[2]));
    await callbacksDrained;
    expect(drained, isTrue);
    await dispose;

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<ByteData?>(channel, null);
  });

  test('concurrent callers enter the platform channel one at a time', () async {
    final firstReply = Completer<ByteData?>();
    final firstEntered = Completer<void>();
    var active = 0;
    var peakActive = 0;
    var calls = 0;
    final channel = BasicMessageChannel<ByteData?>(
      'visibility_surface_stream_serial',
      const BinaryCodec(),
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<ByteData?>(channel, (_) async {
      calls++;
      active++;
      if (active > peakActive) peakActive = active;
      if (calls == 1) {
        firstEntered.complete();
        await firstReply.future;
      }
      active--;
      return ByteData.sublistView(Uint8List.fromList(<int>[calls]));
    });

    final stream = ARVisibilitySurfaceStream(0, channel: channel);
    final first = stream.exchange(Uint8List.fromList(<int>[1]));
    final second = stream.exchange(Uint8List.fromList(<int>[2]));
    await firstEntered.future;
    expect(calls, 1);
    firstReply.complete(ByteData.sublistView(Uint8List.fromList(<int>[1])));
    expect(await first, orderedEquals(<int>[1]));
    expect(await second, orderedEquals(<int>[2]));
    expect(peakActive, 1);

    await stream.dispose();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<ByteData?>(channel, null);
  });
}
