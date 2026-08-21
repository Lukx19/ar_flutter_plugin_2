import 'package:ar_flutter_plugin_2/managers/ar_visibility_grid_v2_control.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('dispose fences only the V2 binding on its dedicated channel', () async {
    const channel = MethodChannel('visibility_grid_v2_control_41');
    final methods = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      methods.add(call.method);
      return true;
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );

    await ARVisibilityGridV2Control(41).dispose();

    expect(methods, <String>['disposeBinding']);
  });
}
