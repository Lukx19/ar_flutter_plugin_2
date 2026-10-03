import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/models/ar_frame_pose.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:vector_math/vector_math_64.dart';

void main() {
  test(
      'packed poses preserve int64 timestamps, float geometry and retained ownership',
      () {
    final bytes = ByteData(packedPoseSampleBytes + 3);
    const offset = 3;
    bytes.setInt64(offset, 7, Endian.little);
    bytes.setInt64(offset + 8, 123, Endian.little);
    bytes.setInt64(offset + 16, 9007199254740993, Endian.little);
    bytes.setFloat32(offset + 40, 1, Endian.little);
    bytes.setInt32(offset + 44, 260, Endian.little);
    bytes.setInt32(offset + 48, 1, Endian.little);
    bytes.setFloat32(offset + 52, 0.24, Endian.little);
    bytes.setFloat32(offset + 144, 0.24, Endian.little);
    for (var index = 0; index < 16; index++) {
      bytes.setFloat32(
          offset + 80 + index * 4, index.toDouble(), Endian.little);
      bytes.setFloat32(
          offset + 172 + index * 4, -index.toDouble(), Endian.little);
    }
    final pose = ARFramePose.fromPacked(bytes, offset);
    expect(pose.sequence, 7);
    expect(pose.sensorTimestampNs, 9007199254740993);
    expect(pose.position.x, bytes.getFloat32(offset + 52, Endian.little));
    expect(
        pose.transform.storage, List<double>.generate(16, (i) => i.toDouble()));
    expect(pose.trackingPose!.cameraToWorld.storage,
        List<double>.generate(16, (i) => -i.toDouble()));
    expect(pose.poseSource, 'synthetic');
    expect(pose.trackingState, 'synthetic');
    expect(pose.isTracking, isTrue);
    expect(pose.wireVersion, packedPoseBatchWireVersion);
    bytes.setFloat32(offset + 52, 99, Endian.little);
    expect(pose.position.x, closeTo(0.24, 0.000001));
    expect(
        () => ARFramePose.fromPacked(bytes, offset + 1), throwsFormatException);
    bytes.setInt32(offset + 44, 512, Endian.little);
    expect(() => ARFramePose.fromPacked(bytes, offset), throwsFormatException);
  });

  test('round-trips the nested GL tracking pose without changing legacy fields',
      () {
    final map = <String, dynamic>{
      'position': {'x': 1.0, 'y': 2.0, 'z': 3.0},
      'rotation': {'x': 0.0, 'y': 0.0, 'z': 0.0, 'w': 1.0},
      'transform': Matrix4.identity().storage.toList(),
      'timestampMs': 123,
      'confidence': 1.0,
      'isTracking': true,
      'convention': 'opencv_c2w_v1',
      'trackingPose': {
        'position': {'x': 1.0, 'y': 2.0, 'z': 3.0},
        'rotation': {'x': 0.0, 'y': 0.0, 'z': 0.0, 'w': 1.0},
        'cameraToWorld': Matrix4.identity().storage.toList(),
        'convention': arcoreGlCameraToWorldConvention,
      },
    };

    final pose = ARFramePose.fromMap(map);
    expect(pose.trackingPose?.convention, arcoreGlCameraToWorldConvention);
    expect(
      ARFramePose.fromMap(pose.toMap()).trackingPose?.cameraToWorld,
      Matrix4.identity(),
    );
    expect(pose.toMap()['convention'], 'opencv_c2w_v1');
  });

  test('legacy payloads remain readable without a nested tracking pose', () {
    final pose = ARFramePose.fromMap({
      'position': {'x': 0.0, 'y': 0.0, 'z': 0.0},
      'rotation': {'x': 0.0, 'y': 0.0, 'z': 0.0, 'w': 1.0},
      'transform': Matrix4.identity().storage.toList(),
      'timestampMs': 123,
      'confidence': 1.0,
      'isTracking': true,
    });
    expect(pose.trackingPose, isNull);
    expect(pose.toMap().containsKey('trackingPose'), isFalse);
  });

  test('synthetic pose provenance survives the wire round trip', () {
    final pose = ARFramePose.fromMap({
      'position': {'x': 0.0, 'y': 0.0, 'z': 0.0},
      'rotation': {'x': 0.0, 'y': 0.0, 'z': 0.0, 'w': 1.0},
      'transform': Matrix4.identity().storage.toList(),
      'timestampMs': 123,
      'sensorTimestampNs': 456,
      'confidence': 1.0,
      'isTracking': true,
      'trackingState': 'synthetic',
      'poseSource': 'synthetic',
      'wireVersion': poseBatchWireVersion,
      'sequence': 1,
    });

    final roundTripped = ARFramePose.fromMap(pose.toMap());
    expect(roundTripped.poseSource, 'synthetic');
    expect(roundTripped.trackingState, 'synthetic');
    expect(roundTripped.sequence, 1);
  });
}
