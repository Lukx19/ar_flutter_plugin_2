import 'dart:async';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/managers/background_platform_messenger.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

final class _Sent {
  _Sent(this.channel, this.message, this.identifier, this.replyPort);
  final String channel;
  final ByteData? message;
  final int identifier;
  final SendPort replyPort;

  void reply(List<int> bytes) =>
      replyPort.send(<Object?>[identifier, Uint8List.fromList(bytes)]);

  void replyEmpty() => replyPort.send(null);
}

void main() {
  late List<_Sent> sent;
  late BackgroundPlatformMessenger messenger;

  setUp(() {
    sent = <_Sent>[];
    messenger = BackgroundPlatformMessenger.withDispatch(
      (channel, message, identifier, replyPort) =>
          sent.add(_Sent(channel, message, identifier, replyPort)),
    );
  });

  test('a host reply completes its own send', () async {
    final first = messenger.send('channel_a', ByteData(1))!;
    final second = messenger.send('channel_b', ByteData(2))!;
    expect(messenger.outstandingReplies, 2);
    sent[1].reply(<int>[7, 8]);
    sent[0].reply(<int>[9]);
    expect((await first)!.buffer.asUint8List(), <int>[9]);
    expect((await second)!.buffer.asUint8List(), <int>[7, 8]);
    expect(messenger.outstandingReplies, 0);
    expect(sent.map((value) => value.channel), <String>['channel_a', 'channel_b']);
  });

  test('an empty host reply completes its send with null', () async {
    final reply = messenger.send('closed_view_channel', ByteData(1))!;
    sent.single.replyEmpty();
    expect(await reply.timeout(const Duration(seconds: 1)), isNull);
    expect(messenger.outstandingReplies, 0);
  });

  test('a method call answered empty fails as missing instead of waiting',
      () async {
    final channel = MethodChannel(
      'visibility_grid_v2_control_0',
      const StandardMethodCodec(),
      messenger,
    );
    final call = channel.invokeMethod<Object?>('disposeBinding');
    await Future<void>.delayed(Duration.zero);
    sent.single.replyEmpty();
    await expectLater(
      call.timeout(const Duration(seconds: 1)),
      throwsA(isA<MissingPluginException>()),
    );
  });

  test('close completes only after every sent message has its reply',
      () async {
    final first = messenger.send('channel', ByteData(1))!;
    final second = messenger.send('channel', ByteData(1))!;
    var closed = false;
    unawaited(messenger.close().then((_) => closed = true));
    sent[0].replyEmpty();
    await first;
    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(closed, isFalse);
    expect(messenger.outstandingReplies, 1);
    sent[1].reply(<int>[1]);
    await second;
    await Future<void>.delayed(Duration.zero);
    expect(closed, isTrue);
  });

  test('a closed messenger sends nothing and answers null', () async {
    await messenger.close();
    expect(messenger.isClosed, isTrue);
    expect(await messenger.send('channel', ByteData(1)), isNull);
    expect(sent, isEmpty);
    expect(messenger.outstandingReplies, 0);
  });

  test('a failed dispatch fails its send and leaves nothing outstanding',
      () async {
    final failing = BackgroundPlatformMessenger.withDispatch(
      (channel, message, identifier, replyPort) =>
          throw StateError('engine unavailable'),
    );
    await expectLater(
      failing.send('channel', ByteData(1)),
      throwsA(isA<StateError>()),
    );
    expect(failing.outstandingReplies, 0);
    await failing.close().timeout(const Duration(seconds: 1));
  });
}
