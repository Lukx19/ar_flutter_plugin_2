import 'dart:typed_data';

import 'package:vector_math/vector_math_64.dart';

import 'frame_convention.dart';

export 'frame_convention.dart'
    show arcoreGlCameraToWorldConvention, arkitCameraToWorldConvention;

const String poseBatchWireVersion = 'pose_batch_v1';
const String packedPoseBatchWireVersion = 'pose_batch_v2';
const int packedPoseSampleBytes = 236;

/// Optional sidecar aligned one-for-one with the packed samples. A malformed
/// sidecar never invalidates tracking, but cannot qualify display-space cues.
List<double?>? decodePoseDisplayRotations(Object? value, int sampleCount) {
  if (sampleCount < 1 ||
      sampleCount > 8 ||
      value is! List ||
      value.length != sampleCount) {
    return null;
  }
  final result = <double?>[];
  for (final rotation in value) {
    if (rotation == null) {
      result.add(null);
      continue;
    }
    if (rotation is! num ||
        !rotation.isFinite ||
        rotation.abs() > 3.141592653589794) {
      return null;
    }
    result.add(rotation.toDouble());
  }
  return result;
}

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
      throw const FormatException(
          'trackingPose cameraToWorld must have 16 values.');
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
  final String? poseSource;
  final String? wireVersion;
  final int? sequence;

  /// Display right = cos(theta)*optical right + sin(theta)*optical up.
  /// Native-qualified for this exact sample, absent on older producers.
  final double? displayRotationRadians;

  /// Per-sample native semantic plane facts. Shared application policy owns stability qualification.
  final Map<String, Object>? semanticFloor;

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
    this.poseSource,
    this.wireVersion,
    this.sequence,
    this.displayRotationRadians,
    this.semanticFloor,
  });

  factory ARFramePose.fromMap(Map<String, dynamic> map) {
    final position = _vectorMap(map['position']);
    final rotation = _quaternionMap(map['rotation']);
    return ARFramePose(
      position: position,
      rotation: rotation,
      transform: Matrix4.fromList(
        (map['transform'] as List)
            .map((value) => (value as num).toDouble())
            .toList(),
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
      poseSource: map['poseSource'] as String?,
      wireVersion: map['wireVersion'] as String?,
      sequence: (map['sequence'] as num?)?.toInt(),
      displayRotationRadians:
          decodePoseDisplayRotations([map['displayRotationV1']], 1)?.first,
      semanticFloor: decodeSemanticFloor(map['semanticFloorV1']),
    );
  }

  /// Decodes native float32 values without changing coordinate conversion precision.
  ///
  /// [trackingConvention] tags the tracking pose: the wire carries no
  /// platform, so the caller names it (ARCore [arcoreGlCameraToWorldConvention]
  /// or ARKit [arkitCameraToWorldConvention]; both are the AR camera axes).
  factory ARFramePose.fromPacked(
    ByteData bytes,
    int offset, {
    String trackingConvention = arcoreGlCameraToWorldConvention,
    double? displayRotationRadians,
    Map<String, Object>? semanticFloor,
  }) {
    if (!isArCameraConvention(trackingConvention)) {
      throw ArgumentError.value(trackingConvention, 'trackingConvention');
    }
    if (offset < 0 || offset + packedPoseSampleBytes > bytes.lengthInBytes) {
      throw const FormatException('Truncated packed pose.');
    }
    int integer(int field) => bytes.getInt64(offset + field, Endian.little);
    double scalar(int field) => bytes.getFloat32(offset + field, Endian.little);
    Vector3 position(int field) =>
        Vector3(scalar(field), scalar(field + 4), scalar(field + 8));
    Quaternion rotation(int field) => Quaternion(scalar(field),
        scalar(field + 4), scalar(field + 8), scalar(field + 12));
    Matrix4 matrix(int field) {
      final value = Matrix4.zero();
      for (var index = 0; index < 16; index++) {
        value.storage[index] = scalar(field + index * 4);
      }
      return value;
    }

    final flags = bytes.getInt32(offset + 44, Endian.little);
    final state = flags & 255;
    final source = bytes.getInt32(offset + 48, Endian.little);
    if ((flags & ~511) != 0 ||
        state < 1 ||
        state > 4 ||
        source < 0 ||
        source > 1) {
      throw const FormatException('Invalid packed pose flags.');
    }
    return ARFramePose(
      position: position(52),
      rotation: rotation(64),
      transform: matrix(80),
      timestamp: DateTime.fromMillisecondsSinceEpoch(integer(8)),
      convention: openCvCameraToWorldConvention,
      trackingPose: ARPoseTransform(
        position: position(144),
        rotation: rotation(156),
        cameraToWorld: matrix(172),
        convention: trackingConvention,
      ),
      sensorTimestampNs: integer(16),
      confidence: scalar(40),
      isTracking: (flags & 256) != 0,
      poseAlignment: 'exact',
      poseTimeErrorNs: 0,
      trackingState: const [
        'tracking',
        'paused',
        'stopped',
        'synthetic'
      ][state - 1],
      poseSource: source == 0 ? 'liveAnchor' : 'synthetic',
      semanticFloor: decodeSemanticFloor(semanticFloor),
      wireVersion: packedPoseBatchWireVersion,
      sequence: integer(0),
      displayRotationRadians:
          decodePoseDisplayRotations([displayRotationRadians], 1)?.first,
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
      if (poseSource != null) 'poseSource': poseSource,
      if (wireVersion != null) 'wireVersion': wireVersion,
      if (sequence != null) 'sequence': sequence,
      if (displayRotationRadians != null)
        'displayRotationV1': displayRotationRadians,
      if (semanticFloor != null) 'semanticFloorV1': semanticFloor,
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
        other.trackingState == trackingState &&
        other.poseSource == poseSource &&
        other.wireVersion == wireVersion &&
        other.sequence == sequence &&
        other.displayRotationRadians == displayRotationRadians &&
        _sameSemanticFloor(other.semanticFloor, semanticFloor);
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
      trackingState.hashCode ^
      poseSource.hashCode ^
      wireVersion.hashCode ^
      sequence.hashCode ^
      displayRotationRadians.hashCode ^
      Object.hash(
          semanticFloor?['classification'],
          semanticFloor?['anchorId'],
          semanticFloor?['worldY'],
          semanticFloor?['normalY'],
          semanticFloor?['areaSquareMetres']);

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

/// Versioned optional sidecar, never inferred from ordinary horizontal planes.
Map<String, Object>? decodeSemanticFloor(dynamic value) {
  if (value is! Map || value['classification'] != 'floor') return null;
  final id = value['anchorId'];
  final y = value['worldY'];
  final normal = value['normalY'];
  final area = value['areaSquareMetres'];
  if (id is! String ||
      id.isEmpty ||
      id.length > 128 ||
      y is! num ||
      !y.isFinite ||
      normal is! num ||
      !normal.isFinite ||
      normal.abs() > 1.001 ||
      area is! num ||
      !area.isFinite ||
      area <= 0) return null;
  return Map.unmodifiable({
    'classification': 'floor',
    'anchorId': id,
    'worldY': y.toDouble(),
    'normalY': normal.toDouble(),
    'areaSquareMetres': area.toDouble()
  });
}

bool _sameSemanticFloor(Map<String, Object>? a, Map<String, Object>? b) {
  if (a == null || b == null) return a == b;
  return a.length == b.length && a.entries.every((e) => b[e.key] == e.value);
}
