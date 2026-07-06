import 'package:vector_math/vector_math_64.dart';

/// Represents AR tracking pose data synchronized with capture
class ARFramePose {
  final Vector3 position;
  final Quaternion rotation;
  final Matrix4 transform;
  final DateTime timestamp;
  final double confidence;
  final bool isTracking;

  const ARFramePose({
    required this.position,
    required this.rotation,
    required this.transform,
    required this.timestamp,
    required this.confidence,
    required this.isTracking,
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
      confidence: map['confidence'] as double,
      isTracking: map['isTracking'] as bool,
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
      'confidence': confidence,
      'isTracking': isTracking,
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
        other.confidence == confidence &&
        other.isTracking == isTracking;
  }

  @override
  int get hashCode =>
      position.hashCode ^
      rotation.hashCode ^
      transform.hashCode ^
      timestamp.hashCode ^
      confidence.hashCode ^
      isTracking.hashCode;

  @override
  String toString() => 'ARFramePose(pos: $position, confidence: $confidence, tracking: $isTracking)';
}