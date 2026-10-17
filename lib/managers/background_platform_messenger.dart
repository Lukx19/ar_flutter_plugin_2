import 'dart:async';
import 'dart:isolate';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Sends one platform message to the host for [channel] with the reply
/// delivered to [replyPort] (the shape of
/// `PlatformDispatcher.sendPortPlatformMessage`).
typedef BackgroundPlatformMessageDispatch = void Function(
  String channel,
  ByteData? message,
  int identifier,
  SendPort replyPort,
);

/// Platform messenger of a background isolate that answers every message,
/// including an empty host reply, and knows when every reply has arrived.
///
/// Flutter's `BackgroundIsolateBinaryMessenger` shares one reply port for all
/// messages. The engine posts an empty reply (no host handler for the
/// channel, for example a closed platform view, or a null host reply) as a
/// bare `null` without the message identifier, which that messenger cannot
/// attribute: the send then never completes. The engine also aborts the
/// process (`platform_message_response_dart_port.cc` `did_send`) when a reply
/// arrives after its isolate ended. This messenger gives each message its own
/// reply port, so an empty reply completes that send with `null`
/// (`MissingPluginException` on a method channel), and [close] lets an
/// isolate end only after every message it sent has its reply.
final class BackgroundPlatformMessenger implements BinaryMessenger {
  /// A messenger over [dispatch]; production uses [ensureInitialized].
  @visibleForTesting
  BackgroundPlatformMessenger.withDispatch(this._dispatch);

  static BackgroundPlatformMessenger? _instance;

  /// The messenger of the current isolate, registering the isolate with the
  /// root isolate's engine on first use (idempotent).
  static BackgroundPlatformMessenger ensureInitialized(
    ui.RootIsolateToken token,
  ) =>
      _instance ??= () {
        BackgroundIsolateBinaryMessenger.ensureInitialized(token);
        return BackgroundPlatformMessenger.withDispatch(
          ui.PlatformDispatcher.instance.sendPortPlatformMessage,
        );
      }();

  /// The current isolate's messenger, or null when it never sent.
  static BackgroundPlatformMessenger? get instanceOrNull => _instance;

  /// The current isolate's messenger, which [ensureInitialized] (or
  /// [debugInstall]) set up.
  ///
  /// Throws a [StateError] before that.
  static BackgroundPlatformMessenger get instance =>
      _instance ??
      (throw StateError(
        'BackgroundPlatformMessenger.ensureInitialized was not called in '
        'this isolate.',
      ));

  /// Makes [messenger] the current isolate's messenger (null clears it), so
  /// a test drives an isolate's platform replies through
  /// [BackgroundPlatformMessenger.withDispatch].
  @visibleForTesting
  static void debugInstall(BackgroundPlatformMessenger? messenger) =>
      _instance = messenger;

  final BackgroundPlatformMessageDispatch _dispatch;
  int _sequence = 0;
  int _outstanding = 0;
  bool _closed = false;
  Completer<void>? _allReplied;

  /// Messages sent whose reply has not arrived yet.
  int get outstandingReplies => _outstanding;

  /// Whether [close] was called; later sends complete with `null` unsent.
  bool get isClosed => _closed;

  /// Completes once every message sent so far has its reply.
  Future<void> get allReplied => _outstanding == 0
      ? Future<void>.value()
      : (_allReplied ??= Completer<void>()).future;

  /// Sends nothing more and completes once every sent message has its reply,
  /// after the reply callbacks registered on those sends ran. An isolate
  /// awaits this before it exits.
  Future<void> close() {
    _closed = true;
    return allReplied;
  }

  @override
  Future<ByteData?>? send(String channel, ByteData? message) {
    if (_closed) {
      return Future<ByteData?>.value();
    }
    final completer = Completer<ByteData?>();
    final port = RawReceivePort();
    _outstanding += 1;
    var settled = false;
    void settle() {
      settled = true;
      port.close();
      _outstanding -= 1;
      if (_outstanding == 0) {
        final waiting = _allReplied;
        _allReplied = null;
        waiting?.complete();
      }
    }

    port.handler = (Object? reply) {
      if (settled) {
        return;
      }
      // A reply is [identifier, bytes]; an empty reply is a bare null. The
      // send completes before [allReplied] does, so its reply callbacks run
      // before an isolate waiting on [close] exits.
      if (reply is List && reply.length == 2 && reply[1] is Uint8List) {
        completer.complete(ByteData.sublistView(reply[1] as Uint8List));
      } else {
        completer.complete(null);
      }
      settle();
    };
    _sequence += 1;
    try {
      _dispatch(channel, message, _sequence, port.sendPort);
    } on Object catch (error, stackTrace) {
      completer.completeError(error, stackTrace);
      settle();
    }
    return completer.future;
  }

  @override
  Future<void> handlePlatformMessage(
    String channel,
    ByteData? data,
    ui.PlatformMessageResponseCallback? callback,
  ) {
    throw UnsupportedError('handlePlatformMessage is not supported.');
  }

  @override
  void setMessageHandler(String channel, MessageHandler? handler) {
    throw UnsupportedError(
      'Background isolates receive no messages from the host platform.',
    );
  }
}
