import 'dart:math' as math;
import 'camera_resolution.dart';

/// Represents camera focal length in pixels
/// In pinhole camera model: f = (real_focal_length_mm / sensor_width_mm) * image_width_pixels
class FocalLength {
  /// Horizontal focal length in pixels
  final double fx;
  
  /// Vertical focal length in pixels  
  final double fy;

  const FocalLength({
    required this.fx,
    required this.fy,
  });

  factory FocalLength.fromMap(Map<String, dynamic> map) {
    return FocalLength(
      fx: (map['fx'] as num).toDouble(),
      fy: (map['fy'] as num).toDouble(),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'fx': fx,
      'fy': fy,
    };
  }

  /// Create symmetric focal length (fx = fy)
  factory FocalLength.symmetric(double focalLength) {
    return FocalLength(fx: focalLength, fy: focalLength);
  }

  /// Check if focal lengths are equal (symmetric lens)
  bool get isSymmetric => (fx - fy).abs() < 0.001;

  /// Get average focal length
  double get average => (fx + fy) / 2;

  /// Get aspect ratio (fx/fy)
  double get aspectRatio => fx / fy;

  /// Check if focal length values are reasonable
  bool isValidForResolution(CameraResolution resolution) {
    // Focal length should be positive
    if (fx <= 0 || fy <= 0) return false;
    
    // Should be reasonable compared to image size
    final minFocalLength = math.min(resolution.width, resolution.height) * 0.1;
    final maxFocalLength = math.max(resolution.width, resolution.height) * 10.0;
    
    return fx >= minFocalLength && fx <= maxFocalLength &&
           fy >= minFocalLength && fy <= maxFocalLength;
  }

  /// Scale focal length for different resolution
  FocalLength scaleForResolution(
    CameraResolution fromResolution, 
    CameraResolution toResolution,
  ) {
    final scaleX = toResolution.width / fromResolution.width;
    final scaleY = toResolution.height / fromResolution.height;
    return FocalLength(fx: fx * scaleX, fy: fy * scaleY);
  }

  /// Convert to millimeters if sensor size known
  FocalLength toMillimeters(double sensorWidthMm, CameraResolution resolution) {
    final pixelSizeMm = sensorWidthMm / resolution.width;
    return FocalLength(
      fx: fx * pixelSizeMm,
      fy: fy * pixelSizeMm,
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is FocalLength &&
        (other.fx - fx).abs() < 1e-9 &&
        (other.fy - fy).abs() < 1e-9;
  }

  @override
  int get hashCode => fx.hashCode ^ fy.hashCode;

  @override
  String toString() => 'FocalLength(fx: ${fx.toStringAsFixed(2)}, fy: ${fy.toStringAsFixed(2)})';
}