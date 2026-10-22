import 'package:ar_flutter_plugin_2/capabilities/arkit_capabilities.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_view_config.dart';
import 'package:ar_flutter_plugin_2/models/ar_session_inputs.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, Object> _arcore(int w, int h, int tw, int th, int fps) => {
      'imageWidth': w,
      'imageHeight': h,
      'textureWidth': tw,
      'textureHeight': th,
      'maxFps': fps,
    };

Map<String, Object> _arkit(int w, int h, int fps,
        {String device = ARCameraFormat.wideAngleDeviceType}) =>
    {
      'width': w,
      'height': h,
      'framesPerSecond': fps,
      'captureDeviceType': device,
      'recommendedForHighResolutionFrameCapturing': false,
      'videoHDRSupported': false,
    };

void main() {
  test('session inputs: creation parameters and tier heights', () {
    expect(const ARSessionInputs().toCreationParams(), {
      'depthInputEnabled': true,
      'maxCameraImageHeight': 0,
    });
    expect(
      const ARSessionInputs(
        depthInputEnabled: false,
        cameraResolution: ARCameraResolution.p480,
      ).toCreationParams(),
      {'depthInputEnabled': false, 'maxCameraImageHeight': 480},
    );
    expect(ARCameraResolution.fromMaxImageHeight(720), ARCameraResolution.p720);
    expect(ARCameraResolution.fromMaxImageHeight(999), ARCameraResolution.auto);
    expect(
      const ARSessionInputs(cameraResolution: ARCameraResolution.p720),
      isNot(const ARSessionInputs()),
    );
  });

  test('the iOS capture view takes depth off and the tier from the inputs', () {
    const base = ARCaptureViewConfig(
        sceneDepth: true, highResolutionFrameCapture: true);
    final points = base.withSessionInputs(const ARSessionInputs(
        depthInputEnabled: false, cameraResolution: ARCameraResolution.p720));
    expect(points.sceneDepth, isFalse);
    expect(points.highResolutionFrameCapture, isTrue);
    expect(points.toCreationParams()['maxCameraImageHeight'], 720);
    expect(points, isNot(base));
    // Auto sends no tier; depth stays as the device allows.
    final auto = base.withSessionInputs(const ARSessionInputs());
    expect(auto, base);
    expect(auto.toCreationParams().containsKey('maxCameraImageHeight'), isFalse);
    expect(
      const ARCaptureViewConfig(sceneDepth: false)
          .withSessionInputs(const ARSessionInputs())
          .sceneDepth,
      isFalse,
    );
  });

  test('ARCore reply lists only the tiers that select another config', () {
    final choices = ARCameraResolutionChoices.fromArCoreReply({
      'auto': _arcore(1920, 1080, 1920, 1080, 30),
      'tiers': {
        '720': _arcore(1280, 720, 1280, 720, 30),
        '480': _arcore(640, 480, 1280, 720, 30),
        '999': _arcore(1, 1, 1, 1, 1),
      },
    });
    expect(choices.offered, [
      ARCameraResolution.auto,
      ARCameraResolution.p720,
      ARCameraResolution.p480,
    ]);
    expect(choices.tiers[ARCameraResolution.p480]!.describe(),
        '640x480 30 fps (GPU 1280x720)');
    // The Galaxy A40 reply: nothing below its 640x480 default.
    expect(
        ARCameraResolutionChoices.fromArCoreReply({
          'auto': _arcore(640, 480, 1920, 1080, 30),
          'tiers': <String, Object>{},
        }).offered,
        [ARCameraResolution.auto]);
    expect(ARCameraResolutionChoices.fromArCoreReply(null).offered,
        [ARCameraResolution.auto]);
    expect(ARCameraResolutionChoices.fromArCoreReply({}).offered,
        [ARCameraResolution.auto]);
  });

  test('ARKit formats map to tiers with the Swift plan rule', () {
    final caps = ARKitCapabilities.fromReply({
      'sceneDepth': true,
      'highResolutionFrameCapture': true,
      'videoFormats': [
        _arkit(1920, 1440, 60),
        _arkit(1920, 1080, 60),
        _arkit(1280, 720, 30),
        _arkit(1280, 720, 60),
        _arkit(640, 480, 60,
            device: 'AVCaptureDeviceTypeBuiltInUltraWideCamera'),
        {'width': 'bad'},
      ],
    })!;
    expect(caps.videoFormats, hasLength(5));
    final choices = ARCameraResolutionChoices.fromArkitFormats(caps.videoFormats);
    expect(choices.auto!.imageHeight, 1440);
    expect(choices.tiers[ARCameraResolution.p1080]!.imageHeight, 1080);
    expect(choices.tiers[ARCameraResolution.p720]!.maxFps, 60);
    expect(choices.tiers.containsKey(ARCameraResolution.p480), isFalse,
        reason: 'only an ultra-wide 480p format');
    expect(ARCameraResolutionChoices.fromArkitFormats(const []).offered,
        [ARCameraResolution.auto]);
    // Scene depth on: only the default's 4:3 aspect (no 4:3 format below
    // 1440 rows here, so nothing is offered).
    expect(
        ARCameraResolutionChoices.fromArkitFormats(caps.videoFormats,
                keepDefaultAspect: true)
            .offered,
        [ARCameraResolution.auto]);
    expect(
        ARCameraResolutionChoices.fromArkitFormats([
          ...caps.videoFormats,
          ARCameraFormat(
              imageWidth: 1440,
              imageHeight: 1080,
              maxFps: 60,
              captureDeviceType: ARCameraFormat.wideAngleDeviceType),
        ], keepDefaultAspect: true)
            .tiers[ARCameraResolution.p1080]!
            .imageWidth,
        1440);
    // A default already within a tier offers nothing at or above it.
    expect(
        ARCameraResolutionChoices.fromArkitFormats([
          caps.videoFormats[2],
          caps.videoFormats[1],
        ]).offered,
        [ARCameraResolution.auto]);
  });
}
