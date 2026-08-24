import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/managers/ar_visibility_grid_v2_control.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
      'connection-owned disposal rejects stale replacement and returns receipt',
      () async {
    const channel = MethodChannel('visibility_grid_v2_control_41');
    final methods = <String>[];
    Uint8List? disposalQualifier;
    var currentQualifier = <int>[
      ...List<int>.generate(16, (i) => i),
      ...List<int>.generate(16, (i) => i + 16),
    ];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      methods.add(call.method);
      if (call.method == 'disposeBinding') {
        final qualifier = List<int>.from(call.arguments! as Uint8List);
        if (!_sameBytes(qualifier, currentQualifier)) {
          throw PlatformException(code: 'VG_STREAM_BINDING_ABANDONED');
        }
        disposalQualifier = Uint8List.fromList(qualifier);
        return <String, Object>{
          'closedResources': 3,
          'disposed': false,
        };
      }
      throw PlatformException(code: 'unsupported');
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );

    final stale = ARVisibilityGridV2Control(
      41,
      initialBindingSnapshot: <Object?, Object?>{
        'nativeStreamToken':
            Uint8List.fromList(List<int>.generate(16, (i) => i)),
        'workerBindingToken': Uint8List.fromList(
          List<int>.generate(16, (i) => i + 16),
        ),
      },
    );
    currentQualifier = <int>[
      ...List<int>.generate(16, (i) => i + 32),
      ...List<int>.generate(16, (i) => i + 48),
    ];

    await expectLater(
      stale.dispose(),
      throwsA(
        isA<PlatformException>().having(
          (error) => error.code,
          'code',
          'VG_STREAM_BINDING_ABANDONED',
        ),
      ),
    );

    final current = ARVisibilityGridV2Control(
      41,
      initialBindingSnapshot: <Object?, Object?>{
        'nativeStreamToken': Uint8List.fromList(
          List<int>.generate(16, (i) => i + 32),
        ),
        'workerBindingToken': Uint8List.fromList(
          List<int>.generate(16, (i) => i + 48),
        ),
      },
    );
    final receipt = await current.dispose();

    expect(methods, <String>['disposeBinding', 'disposeBinding']);
    expect(disposalQualifier, currentQualifier);
    expect(receipt['closedResources'], 3);
    expect(receipt['disposed'], false);
  });
}

bool _sameBytes(List<int> left, List<int> right) {
  if (left.length != right.length) return false;
  for (var index = 0; index < left.length; index++) {
    if (left[index] != right[index]) return false;
  }
  return true;
}
