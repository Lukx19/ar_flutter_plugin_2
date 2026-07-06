import 'dart:math' as math;
import 'camera_resolution.dart';

/// Represents camera principal point (optical center) in pixels
/// Usually close to the image center but may be offset due to manufacturing
class PrincipalPoint {
  /// Horizontal principal point coordinate in pixels
  final double cx;
  
  /// Vertical principal point coordinate in pixels
  final double cy;

  const PrincipalPoint({
    required this.cx,
    required this.cy,
  });

  factory PrincipalPoint.fromMap(Map<String, dynamic> map) {
    return PrincipalPoint(
      cx: (map['cx'] as num).toDouble(),
      cy: (map['cy'] as num).toDouble(),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'cx': cx,
      'cy': cy,
    };
  }

  /// Create principal point at image center
  factory PrincipalPoint.centered(CameraResolution resolution) {
    return PrincipalPoint(
      cx: resolution.width / 2.0,
      cy: resolution.height / 2.0,
    );
  }

  /// Create with offset from center
  factory PrincipalPoint.offsetFromCenter(
    CameraResolution resolution,
    double offsetX,
    double offsetY,
  ) {
    return PrincipalPoint(
      cx: resolution.width / 2.0 + offsetX,
      cy: resolution.height / 2.0 + offsetY,
    );
  }

  /// Check if principal point is reasonably centered
  bool isCenteredFor(CameraResolution resolution, {double tolerance = 5.0}) {
    final expectedCx = resolution.width / 2.0;
    final expectedCy = resolution.height / 2.0;
    return (cx - expectedCx).abs() <= tolerance && 
           (cy - expectedCy).abs() <= tolerance;
  }

  /// Check if principal point is within image bounds  
  bool isWithinBounds(CameraResolution resolution) {
    return cx >= 0 && cx < resolution.width &&
           cy >= 0 && cy < resolution.height;
  }

  /// Get offset from image center
  PrincipalPointOffset getOffsetFromCenter(CameraResolution resolution) {
    final centerX = resolution.width / 2.0;
    final centerY = resolution.height / 2.0;
    return PrincipalPointOffset(
      offsetX: cx - centerX,
      offsetY: cy - centerY,
    );
  }

  /// Get distance from image center in pixels
  double getDistanceFromCenter(CameraResolution resolution) {
    final offset = getOffsetFromCenter(resolution);
    return math.sqrt(offset.offsetX * offset.offsetX + offset.offsetY * offset.offsetY);
  }

  /// Scale principal point for different resolution
  PrincipalPoint scaleForResolution(
    CameraResolution fromResolution,
    CameraResolution toResolution,
  ) {
    final scaleX = toResolution.width / fromResolution.width;
    final scaleY = toResolution.height / fromResolution.height;
    return PrincipalPoint(
      cx: cx * scaleX,
      cy: cy * scaleY,
    );
  }

  /// Convert to normalized coordinates (0.0 to 1.0)
  NormalizedPrincipalPoint normalize(CameraResolution resolution) {
    return NormalizedPrincipalPoint(
      cx: cx / resolution.width,
      cy: cy / resolution.height,
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is PrincipalPoint &&
        (other.cx - cx).abs() < 1e-9 &&
        (other.cy - cy).abs() < 1e-9;
  }

  @override
  int get hashCode => cx.hashCode ^ cy.hashCode;

  @override
  String toString() => 'PrincipalPoint(cx: ${cx.toStringAsFixed(2)}, cy: ${cy.toStringAsFixed(2)})';
}

/// Helper class for principal point offset from center
class PrincipalPointOffset {
  final double offsetX;
  final double offsetY;
  
  const PrincipalPointOffset({
    required this.offsetX,
    required this.offsetY,
  });
  
  double get magnitude => math.sqrt(offsetX * offsetX + offsetY * offsetY);
  bool get isSignificant => magnitude > 2.0; // More than 2 pixels offset
}

/// Normalized principal point coordinates (0.0 to 1.0)
class NormalizedPrincipalPoint {
  final double cx;
  final double cy;
  
  const NormalizedPrincipalPoint({
    required this.cx,
    required this.cy,
  });
  
  /// Convert back to pixel coordinates
  PrincipalPoint denormalize(CameraResolution resolution) {
    return PrincipalPoint(
      cx: cx * resolution.width,
      cy: cy * resolution.height,
    );
  }
}