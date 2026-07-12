import 'dart:math' as math;
import 'camera_resolution.dart';
import 'crop_region.dart';
import 'focal_length.dart';
import 'principal_point.dart';
import 'field_of_view.dart';

/// Unified camera intrinsics for both AR tracking and high-resolution capture
/// Provides essential camera calibration parameters in standard computer vision format
class ARCameraIntrinsics {
  /// Camera focal length in pixels (fx, fy)
  final FocalLength focalLength;
  
  /// Camera principal point in pixels (cx, cy) - image center
  final PrincipalPoint principalPoint;
  
  /// Image resolution these intrinsics apply to
  final CameraResolution resolution;

  /// Crop region in active-array coordinates that was applied to derive these intrinsics
  final CropRegion? cropRegion;
  
  /// Radial and tangential distortion coefficients (optional)
  /// Format: [k1, k2, p1, p2, k3] following OpenCV convention
  final List<double>? distortionCoefficients;
  
  /// Field of view in radians (horizontal, vertical)
  final FieldOfView fieldOfView;

  const ARCameraIntrinsics({
    required this.focalLength,
    required this.principalPoint, 
    required this.resolution,
    this.cropRegion,
    this.distortionCoefficients,
    required this.fieldOfView,
  });

  /// Create from platform channel data
  factory ARCameraIntrinsics.fromMap(Map<String, dynamic> map) {
    return ARCameraIntrinsics(
      focalLength: FocalLength.fromMap(map['focalLength'] ?? {}),
      principalPoint: PrincipalPoint.fromMap(map['principalPoint'] ?? {}),
      resolution: CameraResolution.fromMap(map['resolution'] ?? {}),
      cropRegion: map['cropRegion'] != null
          ? CropRegion.fromMap(map['cropRegion'] as Map<String, dynamic>)
          : null,
      distortionCoefficients: (map['distortionCoefficients'] as List<dynamic>?)?.cast<double>(),
      fieldOfView: FieldOfView.fromMap(map['fieldOfView'] ?? {}),
    );
  }

  /// Convert to platform channel data
  Map<String, dynamic> toMap() {
    return {
      'focalLength': focalLength.toMap(),
      'principalPoint': principalPoint.toMap(),
      'resolution': resolution.toMap(),
      if (cropRegion != null) 'cropRegion': cropRegion!.toMap(),
      'distortionCoefficients': distortionCoefficients,
      'fieldOfView': fieldOfView.toMap(),
    };
  }

  /// Create intrinsics from camera matrix
  /// Camera matrix format: [[fx, 0, cx], [0, fy, cy], [0, 0, 1]]
  factory ARCameraIntrinsics.fromCameraMatrix(
    List<List<double>> cameraMatrix,
    CameraResolution resolution, {
    List<double>? distortionCoefficients,
  }) {
    if (cameraMatrix.length != 3 || cameraMatrix[0].length != 3) {
      throw ArgumentError('Camera matrix must be 3x3');
    }

    final fx = cameraMatrix[0][0];
    final fy = cameraMatrix[1][1];
    final cx = cameraMatrix[0][2];
    final cy = cameraMatrix[1][2];

    final focalLength = FocalLength(fx: fx, fy: fy);
    final principalPoint = PrincipalPoint(cx: cx, cy: cy);
    
    // Calculate field of view from focal length and resolution
    final fovHorizontal = 2 * math.atan(resolution.width / (2 * fx));
    final fovVertical = 2 * math.atan(resolution.height / (2 * fy));
    final fieldOfView = FieldOfView(horizontal: fovHorizontal, vertical: fovVertical);

    return ARCameraIntrinsics(
      focalLength: focalLength,
      principalPoint: principalPoint,
      resolution: resolution,
      cropRegion: null,
      distortionCoefficients: distortionCoefficients,
      fieldOfView: fieldOfView,
    );
  }

  /// Camera matrix in standard 3x3 format
  List<List<double>> get cameraMatrix {
    return [
      [focalLength.fx, 0.0, principalPoint.cx],
      [0.0, focalLength.fy, principalPoint.cy],
      [0.0, 0.0, 1.0],
    ];
  }

  /// Check if intrinsics are valid
  bool get isValid {
    // Check for positive focal lengths
    if (focalLength.fx <= 0 || focalLength.fy <= 0) return false;
    
    // Check if principal point is within resolution bounds
    if (principalPoint.cx < 0 || principalPoint.cx >= resolution.width) return false;
    if (principalPoint.cy < 0 || principalPoint.cy >= resolution.height) return false;
    
    // Check for reasonable focal length values (not too extreme)
    final minFocalLength = math.min(resolution.width, resolution.height) * 0.1;
    final maxFocalLength = math.max(resolution.width, resolution.height) * 10.0;
    if (focalLength.fx < minFocalLength || focalLength.fx > maxFocalLength) return false;
    if (focalLength.fy < minFocalLength || focalLength.fy > maxFocalLength) return false;
    
    // Check field of view reasonableness (0.1 to 3.0 radians)
    if (fieldOfView.horizontal <= 0.1 || fieldOfView.horizontal >= 3.0) return false;
    if (fieldOfView.vertical <= 0.1 || fieldOfView.vertical >= 3.0) return false;
    
    return true;
  }

  /// Convert to OpenCV format for external libraries
  Map<String, dynamic> toOpenCVFormat() {
    return {
      'camera_matrix': [
        [focalLength.fx, 0.0, principalPoint.cx],
        [0.0, focalLength.fy, principalPoint.cy],
        [0.0, 0.0, 1.0],
      ],
      'distortion_coefficients': distortionCoefficients ?? [0.0, 0.0, 0.0, 0.0, 0.0],
      'image_size': [resolution.width, resolution.height],
    };
  }

  /// Get projection matrix for 3D rendering (4x4)
  /// Used for OpenGL/Metal rendering pipelines
  List<List<double>> getProjectionMatrix(double nearPlane, double farPlane) {
    final width = resolution.width.toDouble();
    final height = resolution.height.toDouble();
    
    // Convert from camera intrinsics to OpenGL projection matrix
    final fx = focalLength.fx;
    final fy = focalLength.fy;
    final cx = principalPoint.cx;
    final cy = principalPoint.cy;
    
    final left = -cx * nearPlane / fx;
    final right = (width - cx) * nearPlane / fx;
    final bottom = -(height - cy) * nearPlane / fy;
    final top = cy * nearPlane / fy;
    
    // OpenGL projection matrix
    return [
      [2 * nearPlane / (right - left), 0.0, (right + left) / (right - left), 0.0],
      [0.0, 2 * nearPlane / (top - bottom), (top + bottom) / (top - bottom), 0.0],
      [0.0, 0.0, -(farPlane + nearPlane) / (farPlane - nearPlane), -2 * farPlane * nearPlane / (farPlane - nearPlane)],
      [0.0, 0.0, -1.0, 0.0],
    ];
  }

  /// Calculate pixel size in millimeters (if sensor size known)
  double? getPixelSize(double sensorWidthMm) {
    return sensorWidthMm / resolution.width;
  }

  /// Calculate real-world field of view in degrees
  FieldOfView get fieldOfViewDegrees {
    return FieldOfView(
      horizontal: fieldOfView.horizontal * 180.0 / math.pi,
      vertical: fieldOfView.vertical * 180.0 / math.pi,
    );
  }

  /// Check if intrinsics are suitable for stereo calibration
  bool isSuitableForStereo(ARCameraIntrinsics other) {
    // Focal lengths should be similar
    final fxDiff = (focalLength.fx - other.focalLength.fx).abs() / focalLength.fx;
    final fyDiff = (focalLength.fy - other.focalLength.fy).abs() / focalLength.fy;
    
    if (fxDiff > 0.1 || fyDiff > 0.1) return false; // More than 10% difference
    
    // Resolutions should match
    if (resolution != other.resolution) return false;
    
    return true;
  }

  /// Create scaled intrinsics for different resolution
  ARCameraIntrinsics scaleForResolution(CameraResolution newResolution) {
    final scaleX = newResolution.width / resolution.width;
    final scaleY = newResolution.height / resolution.height;
    
    return ARCameraIntrinsics(
      focalLength: FocalLength(
        fx: focalLength.fx * scaleX,
        fy: focalLength.fy * scaleY,
      ),
      principalPoint: PrincipalPoint(
        cx: principalPoint.cx * scaleX,
        cy: principalPoint.cy * scaleY,
      ),
      resolution: newResolution,
      cropRegion: cropRegion,
      distortionCoefficients: distortionCoefficients, // Distortion coefficients don't scale
      fieldOfView: fieldOfView, // FOV remains the same
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is ARCameraIntrinsics &&
        other.focalLength == focalLength &&
        other.principalPoint == principalPoint &&
        other.resolution == resolution &&
        other.cropRegion == cropRegion &&
        _listEquals(other.distortionCoefficients, distortionCoefficients) &&
        other.fieldOfView == fieldOfView;
  }

  @override
  int get hashCode {
    return focalLength.hashCode ^
        principalPoint.hashCode ^
        resolution.hashCode ^
        cropRegion.hashCode ^
        (distortionCoefficients?.hashCode ?? 0) ^
        fieldOfView.hashCode;
  }

  @override
  String toString() {
    return 'ARCameraIntrinsics('
        'focal: ${focalLength}, '
        'principal: ${principalPoint}, '
        'resolution: ${resolution}, '
        'fov: ${fieldOfViewDegrees.horizontal.toStringAsFixed(1)}°x${fieldOfViewDegrees.vertical.toStringAsFixed(1)}°'
        ')';
  }

  /// Helper method to compare lists
  bool _listEquals(List<double>? a, List<double>? b) {
    if (a == null && b == null) return true;
    if (a == null || b == null) return false;
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) {
      if ((a[i] - b[i]).abs() > 1e-9) return false;
    }
    return true;
  }
}
