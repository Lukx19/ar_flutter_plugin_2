import 'package:flutter/foundation.dart';

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

  /// Creation parameters for the platform view.
  Map<String, Object> toCreationParams() => <String, Object>{
        'sceneDepth': sceneDepth,
        'depthWidth': depthWidth,
        'depthHeight': depthHeight,
        'highResolutionFrameCapture': highResolutionFrameCapture,
      };

  @override
  bool operator ==(Object other) =>
      other is ARCaptureViewConfig &&
      other.sceneDepth == sceneDepth &&
      other.depthWidth == depthWidth &&
      other.depthHeight == depthHeight &&
      other.highResolutionFrameCapture == highResolutionFrameCapture;

  @override
  int get hashCode => Object.hash(
      sceneDepth, depthWidth, depthHeight, highResolutionFrameCapture);

  @override
  String toString() => 'ARCaptureViewConfig(sceneDepth: $sceneDepth, '
      'depth: ${depthWidth}x$depthHeight, '
      'highResolutionFrameCapture: $highResolutionFrameCapture)';
}
