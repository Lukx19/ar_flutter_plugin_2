import 'dart:async';
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/managers/ar_visibility_grid_manager.dart';
import 'package:ar_flutter_plugin_2/models/ar_visibility_grid.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('background-request-test');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('pull request carries an explicit id and cancellation waits for ack',
      () async {
    final calls = <MethodCall>[];
    final original = Completer<Object?>();
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'pullGridDelta') return original.future;
      if (call.method == 'cancelBackgroundRequest') {
        return <String, Object?>{'acknowledged': true, 'cancelled': true};
      }
      fail('unexpected method ${call.method}');
    });
    final client = ARVisibilityGridBackgroundChannel.forTesting(channel);

    final request = client.pullDelta(
      groupId: 'group',
      groupGeneration: 2,
      sessionGeneration: 3,
    );
    final cancelled = await request.cancel();

    expect(cancelled, isTrue);
    expect(calls.map((call) => call.method), <String>[
      'pullGridDelta',
      'cancelBackgroundRequest',
    ]);
    expect(
      (calls.first.arguments as Map)['backgroundRequestId'],
      request.requestId,
    );
    expect(
      (calls.last.arguments as Map)['backgroundRequestId'],
      request.requestId,
    );
    original.completeError(PlatformException(code: 'VG_CANCELLED'));
    await expectLater(request.result, throwsA(isA<PlatformException>()));
  });

  test('apply result can arrive before or after a cancellation attempt',
      () async {
    final patch = ARVisibilityGridVisibilityPatch(
      groupId: 'group',
      groupGeneration: 2,
      sessionGeneration: 3,
      geometryRevision: 4,
      visibilityRevision: 5,
      keys: Int64List(0),
      styles: const <ARCoverageRendererStyleRowV1>[],
    );
    var cancelCount = 0;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'applyVisibility') {
        return <String, Object?>{'applied': true};
      }
      cancelCount++;
      return <String, Object?>{'acknowledged': true, 'cancelled': false};
    });
    final client = ARVisibilityGridBackgroundChannel.forTesting(channel);

    final before = client.applyVisibility(patch);
    expect(await before.result, isTrue);
    expect(await before.cancel(), isFalse);
    expect(cancelCount, 1);

    final never = Completer<Object?>();
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'applyVisibility') return never.future;
      return <String, Object?>{'acknowledged': true, 'cancelled': true};
    });
    final after = client.applyVisibility(patch);
    expect(await after.cancel(), isTrue);
  });

  test('rejects a cancellation response without a native acknowledgement',
      () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'pullGridDelta') return Completer<Object?>().future;
      return <String, Object?>{'acknowledged': false, 'cancelled': true};
    });
    final request = ARVisibilityGridBackgroundChannel.forTesting(
      channel,
    ).pullDelta(
      groupId: 'group',
      groupGeneration: 2,
      sessionGeneration: 3,
    );

    await expectLater(request.cancel(), throwsFormatException);
  });

  for (final response in <Map<String, Object?>>[
    <String, Object?>{'acknowledged': true},
    <String, Object?>{'acknowledged': true, 'cancelled': 1},
    <String, Object?>{'acknowledged': true, 'cancelled': true, 'extra': true},
  ]) {
    test('rejects malformed cancellation response $response', () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'pullGridDelta') return Completer<Object?>().future;
        return response;
      });
      final request =
          ARVisibilityGridBackgroundChannel.forTesting(channel).pullDelta(
        groupId: 'group',
        groupGeneration: 2,
        sessionGeneration: 3,
      );
      await expectLater(request.cancel(), throwsFormatException);
    });
  }
}
