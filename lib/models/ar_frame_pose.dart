import 'package:vector_math/vector_math_64.dart';

const String arcoreGlCameraToWorldConvention = 'arcore_gl_c2w_v1';

/// A camera-to-world transform with an explicit coordinate convention.
final class ARPoseTransform {
  ARPoseTransform({
    required Vector3 position,
    required Quaternion rotation,
    required Matrix4 cameraToWorld,
    required this.convention,
  })  : position = Vector3.copy(position),
        rotation = Quaternion.copy(rotation),
        cameraToWorld = Matrix4.copy(cameraToWorld);

  factory ARPoseTransform.fromMap(Map<String, dynamic> map) {
    final position = _vectorMap(map['position']);
    final rotation = _quaternionMap(map['rotation']);
    final values = (map['cameraToWorld'] as List).cast<num>();
    if (values.length != 16) {
      throw const FormatException('trackingPose cameraToWorld must have 16 values.');
    }
    return ARPoseTransform(
      position: position,
      rotation: rotation,
      cameraToWorld: Matrix4.fromList(
        values.map((value) => value.toDouble()).toList(growable: false),
      ),
      convention: map['convention'] as String,
    );
  }

  final Vector3 position;
  final Quaternion rotation;
  final Matrix4 cameraToWorld;
  final String convention;

  Map<String, dynamic> toMap() => <String, dynamic>{
        'position': _vectorToMap(position),
        'rotation': _quaternionToMap(rotation),
        'cameraToWorld': cameraToWorld.storage.toList(growable: false),
        'convention': convention,
      };

  @override
  bool operator ==(Object other) =>
      other is ARPoseTransform &&
      other.convention == convention &&
      other.position == position &&
      other.rotation == rotation &&
      other.cameraToWorld == cameraToWorld;

  @override
  int get hashCode => Object.hash(
        convention,
        position,
        rotation,
        cameraToWorld,
      );
}

/// Represents AR tracking pose data synchronized with capture
class ARFramePose {
  final Vector3 position;
  final Quaternion rotation;
  final Matrix4 transform;
  final DateTime timestamp;
  final String? convention;
  final ARPoseTransform? trackingPose;
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
    this.trackingPose,
    this.sensorTimestampNs,
    required this.confidence,
    required this.isTracking,
    this.poseAlignment,
    this.poseTimeErrorNs,
    this.trackingState,
  });

  factory ARFramePose.fromMap(Map<String, dynamic> map) {
    final position = _vectorMap(map['position']);
    final rotation = _quaternionMap(map['rotation']);
    return ARFramePose(
      position: position,
      rotation: rotation,
      transform: Matrix4.fromList(
        (map['transform'] as List).map((value) => (value as num).toDouble()).toList(),
      ),
      timestamp: DateTime.fromMillisecondsSinceEpoch(map['timestampMs'] as int),
      convention: map['convention'] as String?,
      trackingPose: map['trackingPose'] is Map
          ? ARPoseTransform.fromMap(
              Map<String, dynamic>.from(map['trackingPose'] as Map),
            )
          : null,
      sensorTimestampNs: map['sensorTimestampNs'] as int?,
      confidence: (map['confidence'] as num).toDouble(),
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
      if (trackingPose != null) 'trackingPose': trackingPose!.toMap(),
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
        other.trackingPose == trackingPose &&
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
      trackingPose.hashCode ^
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

Vector3 _vectorMap(Object? value) {
  final map = Map<String, dynamic>.from(value as Map);
  return Vector3(
    (map['x'] as num).toDouble(),
    (map['y'] as num).toDouble(),
    (map['z'] as num).toDouble(),
  );
}

Quaternion _quaternionMap(Object? value) {
  final map = Map<String, dynamic>.from(value as Map);
  return Quaternion(
    (map['x'] as num).toDouble(),
    (map['y'] as num).toDouble(),
    (map['z'] as num).toDouble(),
    (map['w'] as num).toDouble(),
  );
}

Map<String, double> _vectorToMap(Vector3 value) => <String, double>{
      'x': value.x,
      'y': value.y,
      'z': value.z,
    };

Map<String, double> _quaternionToMap(Quaternion value) => <String, double>{
      'x': value.x,
      'y': value.y,
      'z': value.z,
      'w': value.w,
    };
