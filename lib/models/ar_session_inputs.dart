import 'package:flutter/foundation.dart';

/// Upper bound of the AR camera image the session runs with: the ARCore
/// CameraConfig (CPU image and GPU texture) on Android, the ARKit video
/// format on iOS. [auto] keeps the platform's default choice. High
/// resolution stills keep their own resolution.
enum ARCameraResolution {
  auto(0),
  p1080(1080),
  p720(720),
  p480(480);

  const ARCameraResolution(this.maxImageHeight);

  /// Highest image height of the tier in pixels; 0 for [auto].
  final int maxImageHeight;

  /// The tier of [maxImageHeight], [auto] for an unknown value.
  static ARCameraResolution fromMaxImageHeight(int? maxImageHeight) =>
      values.firstWhere(
        (r) => r.maxImageHeight == maxImageHeight,
        orElse: () => auto,
      );
}

/// Android creation parameter keys of the session inputs; Kotlin
/// `ArViewFactory` reads the same names. The iOS capture view takes the tier
/// as `ARCaptureViewParameter.maxCameraImageHeight` and depth input as
/// `sceneDepth`.
abstract final class ARSessionInputParameter {
  static const String depthInputEnabled = 'depthInputEnabled';
  static const String maxCameraImageHeight = 'maxCameraImageHeight';
}

/// AR session inputs fixed when the session starts: whether the session
/// enables depth at all and the camera image tier. A change rebuilds the
/// native view (a new AR session).
@immutable
class ARSessionInputs {
  const ARSessionInputs({
    this.depthInputEnabled = true,
    this.cameraResolution = ARCameraResolution.auto,
  });

  /// Off: no ARCore depth mode (Android) and no `sceneDepth` (iOS); the
  /// visibility grid runs on tracking points only.
  final bool depthInputEnabled;
  final ARCameraResolution cameraResolution;

  /// Android creation parameters.
  Map<String, Object> toCreationParams() => <String, Object>{
        ARSessionInputParameter.depthInputEnabled: depthInputEnabled,
        ARSessionInputParameter.maxCameraImageHeight:
            cameraResolution.maxImageHeight,
      };

  @override
  bool operator ==(Object other) =>
      other is ARSessionInputs &&
      other.depthInputEnabled == depthInputEnabled &&
      other.cameraResolution == cameraResolution;

  @override
  int get hashCode => Object.hash(depthInputEnabled, cameraResolution);

  @override
  String toString() => 'ARSessionInputs(depthInputEnabled: '
      '$depthInputEnabled, cameraResolution: ${cameraResolution.name})';
}

/// One AR camera format: the CPU image (ARKit video format) size, the GPU
/// texture size (ARCore only) and the frame rate.
@immutable
class ARCameraFormat {
  const ARCameraFormat({
    required this.imageWidth,
    required this.imageHeight,
    required this.maxFps,
    this.textureWidth,
    this.textureHeight,
    this.captureDeviceType,
  });

  final int imageWidth;
  final int imageHeight;
  final int maxFps;
  final int? textureWidth;
  final int? textureHeight;

  /// ARKit capture device type (`AVCaptureDevice.DeviceType` raw value).
  final String? captureDeviceType;

  /// ARKit's wide-angle camera, the only one the capture view runs.
  static const String wideAngleDeviceType =
      'AVCaptureDeviceTypeBuiltInWideAngleCamera';

  /// An ARCore config shape (`ArCameraConfigShape.toChannelMap`), or null.
  static ARCameraFormat? fromArCoreMap(Object? value) {
    if (value is! Map) return null;
    final w = value['imageWidth'], h = value['imageHeight'];
    final fps = value['maxFps'];
    if (w is! int || h is! int || fps is! int) return null;
    final tw = value['textureWidth'], th = value['textureHeight'];
    return ARCameraFormat(
      imageWidth: w,
      imageHeight: h,
      maxFps: fps,
      textureWidth: tw is int ? tw : null,
      textureHeight: th is int ? th : null,
    );
  }

  /// ARKit video formats of a capability reply (`ARVideoFormatDescription`).
  static List<ARCameraFormat> listFromReply(Object? value) {
    if (value is! List) return const [];
    final formats = <ARCameraFormat>[];
    for (final item in value) {
      if (item is! Map) continue;
      final w = item['width'], h = item['height'];
      final fps = item['framesPerSecond'], device = item['captureDeviceType'];
      if (w is! int || h is! int || fps is! int) continue;
      formats.add(
        ARCameraFormat(
          imageWidth: w,
          imageHeight: h,
          maxFps: fps,
          captureDeviceType: device is String ? device : null,
        ),
      );
    }
    return List.unmodifiable(formats);
  }

  /// `1280x720 30 fps` plus the GPU texture when it differs.
  String describe() {
    final texture = textureWidth != null &&
            textureHeight != null &&
            (textureWidth != imageWidth || textureHeight != imageHeight)
        ? ' (GPU ${textureWidth}x$textureHeight)'
        : '';
    return '${imageWidth}x$imageHeight $maxFps fps$texture';
  }

  @override
  bool operator ==(Object other) =>
      other is ARCameraFormat &&
      other.imageWidth == imageWidth &&
      other.imageHeight == imageHeight &&
      other.maxFps == maxFps &&
      other.textureWidth == textureWidth &&
      other.textureHeight == textureHeight &&
      other.captureDeviceType == captureDeviceType;

  @override
  int get hashCode => Object.hash(
        imageWidth,
        imageHeight,
        maxFps,
        textureWidth,
        textureHeight,
        captureDeviceType,
      );

  @override
  String toString() => 'ARCameraFormat(${describe()})';
}

/// The AR camera tiers this device offers: the format [auto] runs with and
/// the tiers that select another format. A tier that would run the same
/// format as [auto] is not offered.
@immutable
class ARCameraResolutionChoices {
  const ARCameraResolutionChoices({this.auto, this.tiers = const {}});

  /// Unknown (no answer): only [ARCameraResolution.auto] is offered.
  static const ARCameraResolutionChoices unknown = ARCameraResolutionChoices();

  final ARCameraFormat? auto;
  final Map<ARCameraResolution, ARCameraFormat> tiers;

  /// Resolutions to offer, [ARCameraResolution.auto] first.
  List<ARCameraResolution> get offered => [
        ARCameraResolution.auto,
        for (final r in ARCameraResolution.values)
          if (tiers.containsKey(r)) r,
      ];

  /// The Android reply of `getArCameraResolutions`.
  static ARCameraResolutionChoices fromArCoreReply(Object? reply) {
    if (reply is! Map) return unknown;
    final auto = ARCameraFormat.fromArCoreMap(reply['auto']);
    final tiers = <ARCameraResolution, ARCameraFormat>{};
    final raw = reply['tiers'];
    if (raw is Map) {
      for (final entry in raw.entries) {
        final height = int.tryParse('${entry.key}');
        final resolution = ARCameraResolution.fromMaxImageHeight(height);
        final format = ARCameraFormat.fromArCoreMap(entry.value);
        if (resolution != ARCameraResolution.auto && format != null) {
          tiers[resolution] = format;
        }
      }
    }
    return ARCameraResolutionChoices(auto: auto, tiers: tiers);
  }

  /// The ARKit tiers from [formats] (ARKit's default first), with the
  /// Swift `ARSessionConfigurationPlan.videoFormat` rule: only tiers below
  /// the default's height, wide-angle formats of at most the tier's
  /// height, highest frame rate, then the largest image. With
  /// [keepDefaultAspect] (scene depth on) only formats of the default's
  /// aspect ratio qualify.
  static ARCameraResolutionChoices fromArkitFormats(
    List<ARCameraFormat> formats, {
    bool keepDefaultAspect = false,
  }) {
    if (formats.isEmpty) return unknown;
    final auto = formats.first;
    final tiers = <ARCameraResolution, ARCameraFormat>{};
    for (final r in ARCameraResolution.values) {
      if (r == ARCameraResolution.auto) continue;
      if (auto.imageHeight <= r.maxImageHeight) continue;
      final selected = arkitFormatForTier(
        formats,
        r.maxImageHeight,
        aspectOf: keepDefaultAspect ? auto : null,
      );
      if (selected != null && selected != auto) tiers[r] = selected;
    }
    return ARCameraResolutionChoices(auto: auto, tiers: tiers);
  }

  /// ARKit format of a tier of at most [maxHeight] rows, of the aspect
  /// ratio of [aspectOf] when given, or null.
  static ARCameraFormat? arkitFormatForTier(
    List<ARCameraFormat> formats,
    int maxHeight, {
    ARCameraFormat? aspectOf,
  }) {
    ARCameraFormat? best;
    for (final f in formats) {
      if (f.imageHeight > maxHeight ||
          f.captureDeviceType != ARCameraFormat.wideAngleDeviceType) {
        continue;
      }
      if (aspectOf != null &&
          f.imageWidth * aspectOf.imageHeight !=
              aspectOf.imageWidth * f.imageHeight) {
        continue;
      }
      if (best == null || _rank(f, best) > 0) best = f;
    }
    return best;
  }

  /// Orders by frame rate, then image height, then image width.
  static int _rank(ARCameraFormat a, ARCameraFormat b) {
    if (a.maxFps != b.maxFps) return a.maxFps.compareTo(b.maxFps);
    if (a.imageHeight != b.imageHeight) {
      return a.imageHeight.compareTo(b.imageHeight);
    }
    return a.imageWidth.compareTo(b.imageWidth);
  }

  @override
  bool operator ==(Object other) =>
      other is ARCameraResolutionChoices &&
      other.auto == auto &&
      mapEquals(other.tiers, tiers);

  @override
  int get hashCode => Object.hash(
        auto,
        Object.hashAll(tiers.entries.map((e) => Object.hash(e.key, e.value))),
      );
}
