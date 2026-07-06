import 'ar_frame_pose.dart';
import 'camera_resolution.dart';
import '../datatypes/image_format.dart';

/// Result of AR capture operation with synchronized pose data
class ARCaptureResult {
  final String imageId;
  final ARFramePose pose;
  final CameraResolution resolution;
  final ImageFormat format;
  final DateTime captureTimestamp;
  final int imageSizeBytes;
  final bool isHighResolution;
  final String? filePath;

  const ARCaptureResult({
    required this.imageId,
    required this.pose,
    required this.resolution,
    required this.format,
    required this.captureTimestamp,
    required this.imageSizeBytes,
    required this.isHighResolution,
    this.filePath,
  });

  factory ARCaptureResult.fromMap(Map<String, dynamic> map) {
    return ARCaptureResult(
      imageId: map['imageId'] as String,
      pose: ARFramePose.fromMap(map['pose'] as Map<String, dynamic>),
      resolution: CameraResolution.fromMap(map['resolution'] as Map<String, dynamic>),
      format: ImageFormat.values.firstWhere(
        (f) => f.name == map['format'],
        orElse: () => ImageFormat.jpeg,
      ),
      captureTimestamp: DateTime.fromMillisecondsSinceEpoch(map['captureTimestampMs'] as int),
      imageSizeBytes: map['imageSizeBytes'] as int,
      isHighResolution: map['isHighResolution'] as bool,
      filePath: map['filePath'] as String?,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'imageId': imageId,
      'pose': pose.toMap(),
      'resolution': resolution.toMap(),
      'format': format.name,
      'captureTimestampMs': captureTimestamp.millisecondsSinceEpoch,
      'imageSizeBytes': imageSizeBytes,
      'isHighResolution': isHighResolution,
      'filePath': filePath,
    };
  }

  /// Get image size in MB
  double get sizeInMB => imageSizeBytes / (1024 * 1024);

  /// Get image size in KB
  double get sizeInKB => imageSizeBytes / 1024;

  /// Check if image is saved to file
  bool get isSavedToFile => filePath != null && filePath!.isNotEmpty;

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is ARCaptureResult &&
        other.imageId == imageId &&
        other.pose == pose &&
        other.resolution == resolution &&
        other.format == format &&
        other.captureTimestamp == captureTimestamp &&
        other.imageSizeBytes == imageSizeBytes &&
        other.isHighResolution == isHighResolution &&
        other.filePath == filePath;
  }

  @override
  int get hashCode =>
      imageId.hashCode ^
      pose.hashCode ^
      resolution.hashCode ^
      format.hashCode ^
      captureTimestamp.hashCode ^
      imageSizeBytes.hashCode ^
      isHighResolution.hashCode ^
      filePath.hashCode;

  @override
  String toString() => 'ARCaptureResult(id: $imageId, res: $resolution, '
      'size: ${sizeInMB.toStringAsFixed(1)}MB, tracking: ${pose.isTracking})';
}