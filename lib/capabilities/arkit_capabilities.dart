import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../models/ar_playback_config.dart';
import '../models/ar_session_inputs.dart';
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
  static const String videoFormats = 'videoFormats';

  /// Present and true when the reply describes the recorded device of a
  /// replayed recording ([ARKitCapabilityQuery.query] with a playback).
  static const String recordedPlayback = 'recordedPlayback';
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
    this.recordedPlayback = false,
    this.videoFormats = const [],
  });

  /// `ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth)`.
  final bool sceneDepth;

  /// `.smoothedSceneDepth` frame semantics supported.
  final bool smoothedSceneDepth;

  /// `recommendedVideoFormatForHighResolutionFrameCapturing` is not nil.
  final bool highResolutionFrameCapture;

  /// `ARSession.getCurrentWorldMap` available.
  final bool worldMap;

  /// Hardware model identifier the host saw (for example `iPhone13,3`);
  /// during recorded playback the recording iPhone's.
  final String? modelIdentifier;

  /// The values describe the device that recorded the replayed recording
  /// (from its `meta.json`), not the device running the app.
  final bool recordedPlayback;

  /// `ARWorldTrackingConfiguration.supportedVideoFormats`, ARKit's default
  /// first.
  final List<ARCameraFormat> videoFormats;

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
    final recorded = reply[ARKitCapabilityReplyKey.recordedPlayback];
    return ARKitCapabilities(
      sceneDepth: sceneDepth,
      highResolutionFrameCapture: highResolution,
      smoothedSceneDepth: smoothed is bool && smoothed,
      worldMap: worldMap is bool && worldMap,
      modelIdentifier: model is String && model.isNotEmpty ? model : null,
      recordedPlayback: recorded is bool && recorded,
      videoFormats: ARCameraFormat.listFromReply(
        reply[ARKitCapabilityReplyKey.videoFormats],
      ),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is ARKitCapabilities &&
      other.sceneDepth == sceneDepth &&
      other.smoothedSceneDepth == smoothedSceneDepth &&
      other.highResolutionFrameCapture == highResolutionFrameCapture &&
      other.worldMap == worldMap &&
      other.modelIdentifier == modelIdentifier &&
      other.recordedPlayback == recordedPlayback &&
      listEquals(other.videoFormats, videoFormats);

  @override
  int get hashCode => Object.hash(sceneDepth, smoothedSceneDepth,
      highResolutionFrameCapture, worldMap, modelIdentifier, recordedPlayback,
      Object.hashAll(videoFormats));

  @override
  String toString() => 'ARKitCapabilities(sceneDepth: $sceneDepth, '
      'smoothedSceneDepth: $smoothedSceneDepth, '
      'highResolutionFrameCapture: $highResolutionFrameCapture, '
      'worldMap: $worldMap, modelIdentifier: $modelIdentifier, '
      'recordedPlayback: $recordedPlayback, videoFormats: $videoFormats)';
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
  ///
  /// With [playback] (the recording the capture view will replay) the host
  /// describes the recorded device from the recording instead of itself,
  /// and marks the reply [ARKitCapabilities.recordedPlayback]; a recording
  /// that cannot be opened leaves the host's own answer.
  Future<ARKitCapabilities?> query({ARPlaybackConfig? playback}) async {
    try {
      return ARKitCapabilities.fromReply(
        await _channel.invokeMethod<Object?>(
          method,
          playback?.toCreationParams(),
        ),
      );
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }
}
