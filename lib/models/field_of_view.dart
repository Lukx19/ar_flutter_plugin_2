import 'dart:math' as math;
import 'camera_resolution.dart';
import 'focal_length.dart';

/// Represents camera field of view in radians
/// Defines the angular extent of the camera's view
class FieldOfView {
  /// Horizontal field of view in radians
  final double horizontal;
  
  /// Vertical field of view in radians
  final double vertical;

  const FieldOfView({
    required this.horizontal,
    required this.vertical,
  });

  factory FieldOfView.fromMap(Map<String, dynamic> map) {
    return FieldOfView(
      horizontal: (map['horizontal'] as num).toDouble(),
      vertical: (map['vertical'] as num).toDouble(),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'horizontal': horizontal,
      'vertical': vertical,
    };
  }

  /// Create from degrees
  factory FieldOfView.fromDegrees({
    required double horizontal,
    required double vertical,
  }) {
    return FieldOfView(
      horizontal: horizontal * math.pi / 180.0,
      vertical: vertical * math.pi / 180.0,
    );
  }

  /// Create from focal length and resolution
  factory FieldOfView.fromFocalLength(
    FocalLength focalLength,
    CameraResolution resolution,
  ) {
    final fovH = 2.0 * math.atan(resolution.width / (2.0 * focalLength.fx));
    final fovV = 2.0 * math.atan(resolution.height / (2.0 * focalLength.fy));
    return FieldOfView(horizontal: fovH, vertical: fovV);
  }

  /// Create symmetric field of view (same horizontal and vertical)
  factory FieldOfView.symmetric(double fov) {
    return FieldOfView(horizontal: fov, vertical: fov);
  }

  /// Get horizontal FOV in degrees
  double get horizontalDegrees => horizontal * 180.0 / math.pi;

  /// Get vertical FOV in degrees  
  double get verticalDegrees => vertical * 180.0 / math.pi;

  /// Get diagonal field of view in radians
  double getDiagonalFOV(CameraResolution resolution) {
    final diagonalPixels = math.sqrt(
      resolution.width * resolution.width + 
      resolution.height * resolution.height
    );
    
    // Approximate diagonal FOV using pythagorean theorem in angular space
    return math.sqrt(horizontal * horizontal + vertical * vertical);
  }

  /// Get diagonal field of view in degrees
  double getDiagonalFOVDegrees(CameraResolution resolution) {
    return getDiagonalFOV(resolution) * 180.0 / math.pi;
  }

  /// Check if field of view values are reasonable
  bool get isValid {
    // FOV should be positive and less than 180 degrees (π radians)
    return horizontal > 0 && horizontal < math.pi &&
           vertical > 0 && vertical < math.pi;
  }

  /// Check if this is a wide-angle lens (> 90 degrees horizontal)
  bool get isWideAngle => horizontalDegrees > 90.0;

  /// Check if this is a telephoto lens (< 30 degrees horizontal)
  bool get isTelephoto => horizontalDegrees < 30.0;

  /// Check if this is a normal lens (30-90 degrees horizontal)
  bool get isNormal => horizontalDegrees >= 30.0 && horizontalDegrees <= 90.0;

  /// Get aspect ratio of field of view
  double get aspectRatio => horizontal / vertical;

  /// Convert to focal length for given resolution
  FocalLength toFocalLength(CameraResolution resolution) {
    final fx = resolution.width / (2.0 * math.tan(horizontal / 2.0));
    final fy = resolution.height / (2.0 * math.tan(vertical / 2.0));
    return FocalLength(fx: fx, fy: fy);
  }

  /// Scale field of view (used when changing resolution while maintaining same physical lens)
  FieldOfView scaleForResolution(
    CameraResolution fromResolution,
    CameraResolution toResolution,
  ) {
    // Convert to focal length, scale, then back to FOV
    final focalLength = toFocalLength(fromResolution);
    final scaledFocalLength = focalLength.scaleForResolution(fromResolution, toResolution);
    return FieldOfView.fromFocalLength(scaledFocalLength, toResolution);
  }

  /// Get angular resolution (radians per pixel)
  AngularResolution getAngularResolution(CameraResolution resolution) {
    return AngularResolution(
      horizontal: horizontal / resolution.width,
      vertical: vertical / resolution.height,
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is FieldOfView &&
        (other.horizontal - horizontal).abs() < 1e-9 &&
        (other.vertical - vertical).abs() < 1e-9;
  }

  @override
  int get hashCode => horizontal.hashCode ^ vertical.hashCode;

  @override
  String toString() => 'FieldOfView(${horizontalDegrees.toStringAsFixed(1)}°×${verticalDegrees.toStringAsFixed(1)}°)';
}

/// Helper class for angular resolution
class AngularResolution {
  final double horizontal; // radians per pixel
  final double vertical;   // radians per pixel
  
  const AngularResolution({
    required this.horizontal,
    required this.vertical,
  });
  
  double get horizontalDegrees => horizontal * 180.0 / math.pi;
  double get verticalDegrees => vertical * 180.0 / math.pi;
}