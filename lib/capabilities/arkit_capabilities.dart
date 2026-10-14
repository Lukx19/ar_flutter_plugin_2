import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'ar_camera_capabilities.dart';

/// Reply keys of [ARKitCapabilityQuery.method]; the Swift host writes the
/// same names (`ARKitCapabilityChannel.Key`).
abstract final class ARKitCapabilityReplyKey {
  static const String modelIdentifier = 'modelIdentifier';
  static const String sceneDepth = 'sceneDepth';
  static const String smoothedSceneDepth = 'smoothedSceneDepth';
  static const String highResolutionFrameCapture =
      'highResolutionFrameCapture';
  static const String worldMap = 'worldMap';
}

/// ARKit capabilities the iOS session host reports at run time
/// ([ARKitCapabilityQuery.method] on [ARKitCapabilityQuery.channelName]).
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
    final sceneDepth = reply[ARKitCapabilityReplyKey.sceneDepth];
    final highResolution =
        reply[ARKitCapabilityReplyKey.highResolutionFrameCapture];
    if (sceneDepth is! bool || highResolution is! bool) {
      return null;
    }
    final smoothed = reply[ARKitCapabilityReplyKey.smoothedSceneDepth];
    final worldMap = reply[ARKitCapabilityReplyKey.worldMap];
    final model = reply[ARKitCapabilityReplyKey.modelIdentifier];
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

  /// The plugin's capability channel; on iOS the Swift handler there answers
  /// [method] (`ARKitCapabilityChannel`), Android replies not-implemented.
  static const String channelName = ARCameraCapabilities.channelName;

  static const MethodChannel defaultChannel = MethodChannel(channelName);

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
