import 'ar_frame_pose.dart';
import 'camera_resolution.dart';
import 'ar_camera_intrinsics.dart';
import '../datatypes/image_format.dart';

/// Result of AR capture operation with synchronized pose data
class ARCaptureResult {
  final String imageId;
  final ARFramePose pose;
  final CameraResolution resolution;
  final CaptureFormat format;
  final List<CaptureAssetFormat> formats;
  final Map<CaptureAssetFormat, int> imageSizeBytesByFormat;
  final DateTime captureTimestamp;
  final int imageSizeBytes;
  final bool isHighResolution;
  final int? exposureStartTimestampNs;
  final int? exposureTimeNs;
  final int? rollingShutterSkewNs;
  final ARCameraIntrinsics? intrinsics;
  final String? filePath;

  const ARCaptureResult({
    required this.imageId,
    required this.pose,
    required this.resolution,
    required this.format,
    this.formats = const [CaptureAssetFormat.jpeg],
    this.imageSizeBytesByFormat = const {},
    required this.captureTimestamp,
    required this.imageSizeBytes,
    required this.isHighResolution,
    this.exposureStartTimestampNs,
    this.exposureTimeNs,
    this.rollingShutterSkewNs,
    this.intrinsics,
    this.filePath,
  });

  factory ARCaptureResult.fromMap(Map<String, dynamic> map) {
    return ARCaptureResult(
      imageId: map['imageId'] as String,
      pose: ARFramePose.fromMap(map['pose'] as Map<String, dynamic>),
      resolution:
          CameraResolution.fromMap(map['resolution'] as Map<String, dynamic>),
      format: CaptureFormat.fromWire(map['format']),
      formats: ((map['formats'] as List<dynamic>?) ?? const ['jpeg'])
          .map(CaptureAssetFormat.fromWire)
          .toList(growable: false),
      imageSizeBytesByFormat:
          ((map['imageSizeBytesByFormat'] as Map<dynamic, dynamic>?) ??
                  const {})
              .map(
        (key, value) => MapEntry(
          CaptureAssetFormat.fromWire(key),
          (value as num).toInt(),
        ),
      ),
      captureTimestamp:
          DateTime.fromMillisecondsSinceEpoch(map['captureTimestampMs'] as int),
      imageSizeBytes: map['imageSizeBytes'] as int,
      isHighResolution: map['isHighResolution'] as bool,
      exposureStartTimestampNs: map['exposureStartTimestampNs'] as int?,
      exposureTimeNs: map['exposureTimeNs'] as int?,
      rollingShutterSkewNs: map['rollingShutterSkewNs'] as int?,
      intrinsics: map['intrinsics'] != null
          ? ARCameraIntrinsics.fromMap(
              map['intrinsics'] as Map<String, dynamic>)
          : null,
      filePath: map['filePath'] as String?,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'imageId': imageId,
      'pose': pose.toMap(),
      'resolution': resolution.toMap(),
      'format': format.wireValue,
      'formats': formats.map((value) => value.wireValue).toList(growable: false),
      'imageSizeBytesByFormat': imageSizeBytesByFormat.map(
        (key, value) => MapEntry(key.wireValue, value),
      ),
      'captureTimestampMs': captureTimestamp.millisecondsSinceEpoch,
      'imageSizeBytes': imageSizeBytes,
      'isHighResolution': isHighResolution,
      if (exposureStartTimestampNs != null)
        'exposureStartTimestampNs': exposureStartTimestampNs,
      if (exposureTimeNs != null) 'exposureTimeNs': exposureTimeNs,
      if (rollingShutterSkewNs != null)
        'rollingShutterSkewNs': rollingShutterSkewNs,
      if (intrinsics != null) 'intrinsics': intrinsics!.toMap(),
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
        _listEquals(other.formats, formats) &&
        _mapEquals(other.imageSizeBytesByFormat, imageSizeBytesByFormat) &&
        other.captureTimestamp == captureTimestamp &&
        other.imageSizeBytes == imageSizeBytes &&
        other.isHighResolution == isHighResolution &&
        other.exposureStartTimestampNs == exposureStartTimestampNs &&
        other.exposureTimeNs == exposureTimeNs &&
        other.rollingShutterSkewNs == rollingShutterSkewNs &&
        other.intrinsics == intrinsics &&
        other.filePath == filePath;
  }

  @override
  int get hashCode =>
      imageId.hashCode ^
      pose.hashCode ^
      resolution.hashCode ^
      format.hashCode ^
      Object.hashAll(formats) ^
      Object.hashAllUnordered(imageSizeBytesByFormat.entries) ^
      captureTimestamp.hashCode ^
      imageSizeBytes.hashCode ^
      isHighResolution.hashCode ^
      exposureStartTimestampNs.hashCode ^
      exposureTimeNs.hashCode ^
      rollingShutterSkewNs.hashCode ^
      intrinsics.hashCode ^
      filePath.hashCode;

  @override
  String toString() => 'ARCaptureResult(id: $imageId, res: $resolution, '
      'size: ${sizeInMB.toStringAsFixed(1)}MB, tracking: ${pose.isTracking})';
}

bool _listEquals<T>(List<T> left, List<T> right) {
  if (left.length != right.length) return false;
  for (var index = 0; index < left.length; index++) {
    if (left[index] != right[index]) return false;
  }
  return true;
}

bool _mapEquals<K, V>(Map<K, V> left, Map<K, V> right) {
  if (left.length != right.length) return false;
  return left.entries.every((entry) => right[entry.key] == entry.value);
}
