import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
import 'package:ar_flutter_plugin_2/widgets/ar_view.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <MethodCall>[];
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    calls.clear();
    messenger.setMockMethodCallHandler(SystemChannels.platform_views,
        (call) async {
      calls.add(call);
      final arguments = call.arguments as Map<Object?, Object?>?;
      switch (call.method) {
        case 'create':
          return arguments?['hybrid'] == true ? null : 17;
        case 'resize':
          return {
            'width': arguments!['width'],
            'height': arguments['height'],
          };
        default:
          return null;
      }
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
  });

  test('Android AR factory uses the declared default-off composition route',
      () {
    final view = PlatformARView(TargetPlatform.android) as AndroidARView;
    expect(
        view.useHybridComposition,
        const bool.fromEnvironment('CAPTURE3D_AR_HYBRID_COMPOSITION',
            defaultValue: false));
  });

  for (final hybrid in [false, true]) {
    testWidgets(
        'Android AR ${hybrid ? 'hybrid' : 'surface'} route preserves creation parameters and listeners',
        (tester) async {
      final createdIds = <int>[];
      final view = AndroidARView(useHybridComposition: hybrid);
      await tester.pumpWidget(_view(view, createdIds));
      await tester.pumpAndSettle();
      final creates = calls.where((call) => call.method == 'create').toList();
      expect(creates, hasLength(1));
      final arguments = creates.single.arguments as Map<Object?, Object?>;
      expect(arguments['viewType'], 'ar_flutter_plugin_2');
      expect(arguments['direction'], 0);
      expect(arguments['hybrid'] == true, hybrid);
      expect(arguments['hybridFallback'] == true, !hybrid);
      final parameters = const StandardMessageCodec().decodeMessage(
          ByteData.sublistView(arguments['params']! as Uint8List));
      expect(parameters, {
        'enableHighResCapture': true,
        'retainAssessedOriginals': false,
        'rearCameraId': '0',
      });
      expect(createdIds, [arguments['id']]);
      final surface =
          tester.widget<AndroidViewSurface>(find.byType(AndroidViewSurface));
      expect(
          surface.controller,
          hybrid
              ? isA<ExpensiveAndroidViewController>()
              : isA<SurfaceAndroidViewController>());
      expect(surface.hitTestBehavior, PlatformViewHitTestBehavior.opaque);
      expect(surface.gestureRecognizers, isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpAndSettle();
      final disposed = calls.where((call) => call.method == 'dispose');
      expect(disposed, hasLength(1));
      expect((disposed.single.arguments as Map<Object?, Object?>)['id'],
          arguments['id']);
    }, variant: TargetPlatformVariant.only(TargetPlatform.android));
  }

  testWidgets('Android review mode recreates the view and reaches native creation with the same manager config', (tester) async {
    final ids = <int>[];
    await tester.pumpWidget(_view(AndroidARView(), ids));
    await tester.pumpAndSettle();
    final offKey = tester.widget<PlatformViewLink>(find.byType(PlatformViewLink)).key;
    await tester.pumpWidget(_view(AndroidARView(), ids, retainAssessedOriginals: true));
    await tester.pumpAndSettle();
    final onKey = tester.widget<PlatformViewLink>(find.byType(PlatformViewLink)).key;
    expect(onKey, isNot(offKey));
    expect(ids, hasLength(2));
    final creates = calls.where((call) => call.method == 'create').toList();
    final params = const StandardMessageCodec().decodeMessage(ByteData.sublistView(
      (creates.last.arguments as Map<Object?, Object?>)['params']! as Uint8List,
    )) as Map<Object?, Object?>;
    expect(params['retainAssessedOriginals'], isTrue);
    expect(calls.where((call) => call.method == 'dispose'), hasLength(1));
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));

  testWidgets(
      'Android AR composition change recreates rather than reuses the platform view',
      (tester) async {
    final createdIds = <int>[];
    await tester.pumpWidget(
        _view(AndroidARView(useHybridComposition: false), createdIds));
    await tester.pumpAndSettle();
    final surfaceKey =
        tester.widget<PlatformViewLink>(find.byType(PlatformViewLink)).key;
    await tester.pumpWidget(
        _view(AndroidARView(useHybridComposition: true), createdIds));
    await tester.pumpAndSettle();
    final hybridKey =
        tester.widget<PlatformViewLink>(find.byType(PlatformViewLink)).key;
    expect(hybridKey, isNot(surfaceKey));
    expect(createdIds, hasLength(2));
    expect(createdIds[1], isNot(createdIds[0]));
    final disposed = calls.where((call) => call.method == 'dispose').toList();
    expect(disposed, hasLength(1));
    expect((disposed.single.arguments as Map<Object?, Object?>)['id'],
        createdIds.first);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
    expect(calls.where((call) => call.method == 'dispose'), hasLength(2));
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));
}

Widget _view(AndroidARView view, List<int> createdIds, {bool retainAssessedOriginals = false}) => MaterialApp(
      home: Builder(
          builder: (context) => view.build(
                context: context,
                planeDetectionConfig: PlaneDetectionConfig.none,
                captureConfig: ARCaptureConfig(
                  enableHighResCapture: true,
                  retainAssessedOriginals: retainAssessedOriginals,
                  resolution: const CameraResolution(width: 4128, height: 3096),
                  format: CaptureFormat.jpeg,
                ),
                rearCameraId: '0',
                arViewCreatedCallback: (session, objects, anchors, location) {
                  createdIds.add(session.channelId);
                  expect(session.captureManager!.config.retainAssessedOriginals, retainAssessedOriginals);
                  expect(session.captureManager!.allowAssessedQuality, isTrue);
                },
              )),
    );
