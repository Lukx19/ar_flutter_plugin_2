import 'package:flutter/foundation.dart';

import 'ar_session_inputs.dart';
import 'camera_resolution.dart';

/// Creation parameter keys of the iOS capture view; the Swift twin is
/// `ARCaptureViewParameter`.
abstract final class ARCaptureViewParameter {
  static const String sceneDepth = 'sceneDepth';
  static const String depthWidth = 'depthWidth';
  static const String depthHeight = 'depthHeight';
  static const String highResolutionFrameCapture = 'highResolutionFrameCapture';
  static const String videoHdr = 'videoHdr';
  static const String maxCameraImageHeight = 'maxCameraImageHeight';
  static const String stillWidth = 'stillWidth';
  static const String stillHeight = 'stillHeight';
}

/// Creation configuration of the iOS capture view (`capture3d/capture_ar_view`):
/// one ARKit session on a Metal view host, used by the capture route only.
///
/// ARKit frame semantics and the video format are fixed when the session
/// runs, so every field is a creation parameter; a change rebuilds the view.
@immutable
class ARCaptureViewConfig {
  const ARCaptureViewConfig({
    required this.sceneDepth,
    this.depthWidth = defaultDepthWidth,
    this.depthHeight = defaultDepthHeight,
    this.highResolutionFrameCapture = false,
    this.videoHdr = false,
    this.maxVideoHeight = 0,
    this.stillResolution,
  });

  /// Platform view type the iOS session host registers.
  static const String viewType = 'capture3d/capture_ar_view';

  /// LiDAR depth sent to the grid: 256x192 reduced 2x2 (nearest valid).
  static const int defaultDepthWidth = 128;
  static const int defaultDepthHeight = 96;

  /// Request `.sceneDepth` (LiDAR) frame semantics. False runs points-only.
  final bool sceneDepth;

  /// Depth map size sent to the grid when [sceneDepth] is on.
  final int depthWidth;
  final int depthHeight;

  /// Choose the video format recommended for high-resolution frame capture.
  final bool highResolutionFrameCapture;

  /// Allow ARKit video HDR on a video format that supports it (the iOS
  /// quality still mode); sent only when true.
  final bool videoHdr;

  /// Highest video format image height ([ARCameraResolution]); 0 keeps
  /// ARKit's default format. Ignored with [highResolutionFrameCapture],
  /// whose recommended format sets the still resolution. Sent only when
  /// set.
  final int maxVideoHeight;

  /// Exact native still output, selected through an ARKit video/still pair.
  /// Null keeps Automatic. It is a sensor request, never a resize operation.
  final CameraResolution? stillResolution;

  /// Select an exact native still/video pair; null restores Automatic.
  ARCaptureViewConfig withStillResolution(CameraResolution? resolution) =>
      ARCaptureViewConfig(
        sceneDepth: sceneDepth,
        depthWidth: depthWidth,
        depthHeight: depthHeight,
        highResolutionFrameCapture: highResolutionFrameCapture,
        videoHdr: videoHdr,
        maxVideoHeight: maxVideoHeight,
        stillResolution: resolution,
      );

  /// This configuration with [videoHdr] replaced.
  ARCaptureViewConfig withVideoHdr(bool videoHdr) => ARCaptureViewConfig(
        sceneDepth: sceneDepth,
        depthWidth: depthWidth,
        depthHeight: depthHeight,
        highResolutionFrameCapture: highResolutionFrameCapture,
        videoHdr: videoHdr,
        maxVideoHeight: maxVideoHeight,
        stillResolution: stillResolution,
      );

  /// This configuration under the user's session [inputs]: no scene depth
  /// when depth input is off, and the camera image tier.
  ARCaptureViewConfig withSessionInputs(ARSessionInputs inputs) =>
      ARCaptureViewConfig(
        sceneDepth: sceneDepth && inputs.depthInputEnabled,
        depthWidth: depthWidth,
        depthHeight: depthHeight,
        highResolutionFrameCapture: highResolutionFrameCapture,
        videoHdr: videoHdr,
        maxVideoHeight: inputs.cameraResolution.maxImageHeight,
        stillResolution: stillResolution,
      );

  /// Creation parameters for the platform view ([ARCaptureViewParameter]
  /// keys; the Swift host reads the same names in `ARSessionOptions`).
  Map<String, Object> toCreationParams() => <String, Object>{
        ARCaptureViewParameter.sceneDepth: sceneDepth,
        ARCaptureViewParameter.depthWidth: depthWidth,
        ARCaptureViewParameter.depthHeight: depthHeight,
        ARCaptureViewParameter.highResolutionFrameCapture:
            highResolutionFrameCapture,
        if (videoHdr) ARCaptureViewParameter.videoHdr: true,
        if (stillResolution case final size?) ...{
          ARCaptureViewParameter.stillWidth: size.width,
          ARCaptureViewParameter.stillHeight: size.height,
        },
        if (maxVideoHeight > 0)
          ARCaptureViewParameter.maxCameraImageHeight: maxVideoHeight,
      };

  @override
  bool operator ==(Object other) =>
      other is ARCaptureViewConfig &&
      other.sceneDepth == sceneDepth &&
      other.depthWidth == depthWidth &&
      other.depthHeight == depthHeight &&
      other.highResolutionFrameCapture == highResolutionFrameCapture &&
      other.videoHdr == videoHdr &&
      other.maxVideoHeight == maxVideoHeight &&
      other.stillResolution == stillResolution;

  @override
  int get hashCode => Object.hash(sceneDepth, depthWidth, depthHeight,
      highResolutionFrameCapture, videoHdr, maxVideoHeight, stillResolution);

  @override
  String toString() => 'ARCaptureViewConfig(sceneDepth: $sceneDepth, '
      'depth: ${depthWidth}x$depthHeight, '
      'highResolutionFrameCapture: $highResolutionFrameCapture, '
      'videoHdr: $videoHdr, maxVideoHeight: $maxVideoHeight)';
}
