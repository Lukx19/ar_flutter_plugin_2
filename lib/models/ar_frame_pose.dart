import 'package:vector_math/vector_math_64.dart';

/// Represents AR tracking pose data synchronized with capture
class ARFramePose {
  final Vector3 position;
  final Quaternion rotation;
  final Matrix4 transform;
  final DateTime timestamp;
  final String? convention;
  final int? sensorTimestampNs;
  final double confidence;
  final bool isTracking;
  final String? poseAlignment;
  final int? poseTimeErrorNs;
  final String? trackingState;

  const ARFramePose({
    required this.position,
    required this.rotation,
    required this.transform,
    required this.timestamp,
    this.convention,
    this.sensorTimestampNs,
    required this.confidence,
    required this.isTracking,
    this.poseAlignment,
    this.poseTimeErrorNs,
    this.trackingState,
  });

  factory ARFramePose.fromMap(Map<String, dynamic> map) {
    return ARFramePose(
      position: Vector3(
        map['position']['x'] as double,
        map['position']['y'] as double,
        map['position']['z'] as double,
      ),
      rotation: Quaternion(
        map['rotation']['x'] as double,
        map['rotation']['y'] as double,
        map['rotation']['z'] as double,
        map['rotation']['w'] as double,
      ),
      transform: Matrix4.fromList(List<double>.from(map['transform'])),
      timestamp: DateTime.fromMillisecondsSinceEpoch(map['timestampMs'] as int),
      convention: map['convention'] as String?,
      sensorTimestampNs: map['sensorTimestampNs'] as int?,
      confidence: map['confidence'] as double,
      isTracking: map['isTracking'] as bool,
      poseAlignment: map['poseAlignment'] as String?,
      poseTimeErrorNs: map['poseTimeErrorNs'] as int?,
      trackingState: map['trackingState'] as String?,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'position': {
        'x': position.x,
        'y': position.y,
        'z': position.z,
      },
      'rotation': {
        'x': rotation.x,
        'y': rotation.y,
        'z': rotation.z,
        'w': rotation.w,
      },
      'transform': transform.storage.toList(),
      'timestampMs': timestamp.millisecondsSinceEpoch,
      if (convention != null) 'convention': convention,
      if (sensorTimestampNs != null) 'sensorTimestampNs': sensorTimestampNs,
      'confidence': confidence,
      'isTracking': isTracking,
      if (poseAlignment != null) 'poseAlignment': poseAlignment,
      if (poseTimeErrorNs != null) 'poseTimeErrorNs': poseTimeErrorNs,
      if (trackingState != null) 'trackingState': trackingState,
    };
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is ARFramePose &&
        other.position == position &&
        other.rotation == rotation &&
        other.transform == transform &&
        other.timestamp == timestamp &&
        other.convention == convention &&
        other.sensorTimestampNs == sensorTimestampNs &&
        other.confidence == confidence &&
        other.isTracking == isTracking &&
        other.poseAlignment == poseAlignment &&
        other.poseTimeErrorNs == poseTimeErrorNs &&
        other.trackingState == trackingState;
  }

  @override
  int get hashCode =>
      position.hashCode ^
      rotation.hashCode ^
      transform.hashCode ^
      timestamp.hashCode ^
      convention.hashCode ^
      sensorTimestampNs.hashCode ^
      confidence.hashCode ^
      isTracking.hashCode ^
      poseAlignment.hashCode ^
      poseTimeErrorNs.hashCode ^
      trackingState.hashCode;

  @override
  String toString() =>
      'ARFramePose(pos: $position, confidence: $confidence, tracking: $isTracking)';
}
