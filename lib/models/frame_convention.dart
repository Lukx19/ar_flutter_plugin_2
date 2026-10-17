import 'dart:typed_data';

import 'package:vector_math/vector_math_64.dart';

/// Camera pose conventions and their read-only converters to the canonical
/// frame (docs/02-architecture.md, Coordinate frames).
///
/// Canonical: `T_world_cam`, camera-to-world, column-major, OpenCV camera
/// axes (+X right, +Y down, +Z forward, sensor orientation), tag
/// [openCvCameraToWorldConvention]. The AR camera axes of ARCore and ARKit
/// (+Y up, -Z forward) differ by `F = diag(1, -1, -1, 1)`:
/// `T_world_cam = T_world_arcam * F`. `F` is its own inverse.
///
/// Stored data keeps its tag; readers convert through [worldFromCameraOpenCv]
/// and never rewrite it.
const String openCvCameraToWorldConvention = 'opencv_c2w_v1';

/// ARCore `Camera.getPose()` camera-to-world (AR camera axes).
const String arcoreGlCameraToWorldConvention = 'arcore_gl_c2w_v1';

/// ARKit `ARCamera.transform` camera-to-world (the same AR camera axes).
const String arkitCameraToWorldConvention = 'arkit_c2w_v1';

/// Whether [convention] names a camera-to-world in the AR camera axes.
bool isArCameraConvention(String? convention) =>
    convention == arcoreGlCameraToWorldConvention ||
    convention == arkitCameraToWorldConvention;

/// Thrown for a pose whose convention is missing or unknown; such a pose is
/// never guessed into a frame.
final class UnknownPoseConventionException implements Exception {
  const UnknownPoseConventionException(this.convention);

  final String? convention;

  @override
  String toString() =>
      'UnknownPoseConventionException: no converter for convention '
      '${convention == null ? '(none)' : "'$convention'"}';
}

/// `matrix * F` in place on column-major [storage] at [offset]: negates the
/// camera Y and Z columns. Converts between the AR camera and OpenCV axes of
/// a camera-to-world transform in either direction.
void flipCameraYZ(Float64List storage, [int offset = 0]) {
  for (var row = 0; row < 4; row++) {
    storage[offset + 4 + row] = -storage[offset + 4 + row];
    storage[offset + 8 + row] = -storage[offset + 8 + row];
  }
}

/// A new `T_world_cam` from a camera-to-world [matrix] tagged [convention].
///
/// Throws [UnknownPoseConventionException] when [convention] is null or not
/// a known tag.
Matrix4 worldFromCameraOpenCv(Matrix4 matrix, String? convention) {
  final out = Matrix4.copy(matrix);
  if (convention == openCvCameraToWorldConvention) return out;
  if (isArCameraConvention(convention)) {
    flipCameraYZ(out.storage);
    return out;
  }
  throw UnknownPoseConventionException(convention);
}

/// A new AR camera axes camera-to-world (`T_world_cam * F`) from a
/// camera-to-world [matrix] tagged [convention]; the input of renderers and
/// of code that still works in the AR camera axes.
Matrix4 worldFromArCamera(Matrix4 matrix, String? convention) {
  final out = worldFromCameraOpenCv(matrix, convention);
  flipCameraYZ(out.storage);
  return out;
}
