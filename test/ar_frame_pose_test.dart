import 'package:ar_flutter_plugin_2/models/ar_frame_pose.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:vector_math/vector_math_64.dart';

void main() {
  test('round-trips the nested GL tracking pose without changing legacy fields', () {
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
}
