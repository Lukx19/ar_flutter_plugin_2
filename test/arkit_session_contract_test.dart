import 'dart:convert';
import 'dart:io';

import 'package:ar_flutter_plugin_2/capabilities/arkit_capabilities.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_view_config.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// The ARKit capability query and capture view creation parameters against
/// `test_assets/arkit_session/arkit_session_contract.json`, the fixture the
/// Swift `ARKitSessionContractTests` read too.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final fixture = jsonDecode(
    File('test_assets/arkit_session/arkit_session_contract.json')
        .readAsStringSync(),
  ) as Map<String, Object?>;
  final query = fixture['capabilityQuery']! as Map<String, Object?>;
  final view = fixture['captureView']! as Map<String, Object?>;

  test('the capability query uses the shared channel and method', () {
    expect(ARKitCapabilityQuery.channelName, query['channel']);
    expect(ARKitCapabilityQuery.defaultChannel.name, query['channel']);
    expect(ARKitCapabilityQuery.method, query['method']);
  });

  test('the shared host reply parses with every key', () async {
    final reply = query['reply']! as Map<String, Object?>;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final channel = MethodChannel(query['channel']! as String);
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, query['method']);
      return reply;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    expect(
      await const ARKitCapabilityQuery().query(),
      ARKitCapabilities(
        sceneDepth: reply[ARKitCapabilityReplyKey.sceneDepth]! as bool,
        smoothedSceneDepth:
            reply[ARKitCapabilityReplyKey.smoothedSceneDepth]! as bool,
        highResolutionFrameCapture:
            reply[ARKitCapabilityReplyKey.highResolutionFrameCapture]! as bool,
        worldMap: reply[ARKitCapabilityReplyKey.worldMap]! as bool,
        modelIdentifier:
            reply[ARKitCapabilityReplyKey.modelIdentifier]! as String,
      ),
    );
  });

  test('capture view creation parameters match the shared cases', () {
    expect(ARCaptureViewConfig.viewType, view['viewType']);
    final cases = (view['cases']! as List).cast<Map<String, Object?>>();
    expect(cases, isNotEmpty);
    for (final testCase in cases) {
      final config = testCase['config']! as Map<String, Object?>;
      final built = ARCaptureViewConfig(
        sceneDepth: config[ARCaptureViewParameter.sceneDepth]! as bool,
        depthWidth: config[ARCaptureViewParameter.depthWidth] as int? ??
            ARCaptureViewConfig.defaultDepthWidth,
        depthHeight: config[ARCaptureViewParameter.depthHeight] as int? ??
            ARCaptureViewConfig.defaultDepthHeight,
        highResolutionFrameCapture:
            config[ARCaptureViewParameter.highResolutionFrameCapture]
                    as bool? ??
                false,
      );
      expect(built.toCreationParams(), testCase['creationParams'],
          reason: testCase['name'] as String?);
    }
  });
}
