import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/managers/ar_visibility_grid_v2_control.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('dispose fences only the V2 binding on its dedicated channel', () async {
    const channel = MethodChannel('visibility_grid_v2_control_41');
    final methods = <String>[];
    Uint8List? disposalQualifier;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      methods.add(call.method);
      if (call.method == 'bindingSnapshot') {
        return <String, Object>{
          'nativeStreamToken': Uint8List.fromList(
            List<int>.generate(16, (index) => index),
          ),
          'workerBindingToken': Uint8List.fromList(
            List<int>.generate(16, (index) => index + 16),
          ),
        };
      }
      disposalQualifier = call.arguments as Uint8List;
      return true;
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );

    await ARVisibilityGridV2Control(41).dispose();

    expect(methods, <String>['bindingSnapshot', 'disposeBinding']);
    expect(disposalQualifier, <int>[
      ...List<int>.generate(16, (i) => i),
      ...List<int>.generate(16, (i) => i + 16)
    ]);
  });
}
