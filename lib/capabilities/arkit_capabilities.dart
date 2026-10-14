import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// ARKit capabilities the iOS session host reports at run time
/// (`getARKitCapabilities` on `ar_flutter_plugin_2/camera_capabilities`).
@immutable
class ARKitCapabilities {
  const ARKitCapabilities({
    required this.sceneDepth,
    required this.highResolutionFrameCapture,
    this.smoothedSceneDepth = false,
    this.worldMap = false,
    this.modelIdentifier,
  });

  /// `ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth)`.
  final bool sceneDepth;

  /// `.smoothedSceneDepth` frame semantics supported.
  final bool smoothedSceneDepth;

  /// `recommendedVideoFormatForHighResolutionFrameCapturing` is not nil.
  final bool highResolutionFrameCapture;

  /// `ARSession.getCurrentWorldMap` available.
  final bool worldMap;

  /// Hardware model identifier the host saw (for example `iPhone13,3`).
  final String? modelIdentifier;

  /// Parses a reply; null when it is not a map or lacks the depth and
  /// high-resolution answers.
  static ARKitCapabilities? fromReply(Object? reply) {
    if (reply is! Map) {
      return null;
    }
    final sceneDepth = reply['sceneDepth'];
    final highResolution = reply['highResolutionFrameCapture'];
    if (sceneDepth is! bool || highResolution is! bool) {
      return null;
    }
    final smoothed = reply['smoothedSceneDepth'];
    final worldMap = reply['worldMap'];
    final model = reply['modelIdentifier'];
    return ARKitCapabilities(
      sceneDepth: sceneDepth,
      highResolutionFrameCapture: highResolution,
      smoothedSceneDepth: smoothed is bool && smoothed,
      worldMap: worldMap is bool && worldMap,
      modelIdentifier: model is String && model.isNotEmpty ? model : null,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is ARKitCapabilities &&
      other.sceneDepth == sceneDepth &&
      other.smoothedSceneDepth == smoothedSceneDepth &&
      other.highResolutionFrameCapture == highResolutionFrameCapture &&
      other.worldMap == worldMap &&
      other.modelIdentifier == modelIdentifier;

  @override
  int get hashCode => Object.hash(sceneDepth, smoothedSceneDepth,
      highResolutionFrameCapture, worldMap, modelIdentifier);

  @override
  String toString() => 'ARKitCapabilities(sceneDepth: $sceneDepth, '
      'smoothedSceneDepth: $smoothedSceneDepth, '
      'highResolutionFrameCapture: $highResolutionFrameCapture, '
      'worldMap: $worldMap, modelIdentifier: $modelIdentifier)';
}

/// Asks the iOS session host for [ARKitCapabilities].
class ARKitCapabilityQuery {
  const ARKitCapabilityQuery({
    MethodChannel channel = defaultChannel,
  }) : _channel = channel;

  static const MethodChannel defaultChannel =
      MethodChannel('ar_flutter_plugin_2/camera_capabilities');

  static const String method = 'getARKitCapabilities';

  final MethodChannel _channel;

  /// The host's answer, or null when the host does not implement the query,
  /// fails, or replies with something unreadable (callers then keep their
  /// model preset).
  Future<ARKitCapabilities?> query() async {
    try {
      return ARKitCapabilities.fromReply(
        await _channel.invokeMethod<Object?>(method),
      );
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }
}
