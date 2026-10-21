import 'binary_support.dart';

/// Marker dictionaries (record byte 2).
abstract final class MarkerDictionary {
  static const int arucoMip36h12 = 0;
}

/// 64 B marker detection record, written by the native marker library
/// (`marker_detection_record.h`) and carried in ingest kind 5. The message
/// header carries time and pose.
abstract final class MarkerDetectionLayout {
  static const int bytes = 64;
  static const int markerIdOffset = 0; // u16
  static const int dictionaryOffset = 2; // u8
  static const int flagsOffset = 3; // u8
  static const int cornersOffset = 4; // f32 x8, image pixels
  static const int rvecOffset = 36; // f32 x3, camera-from-marker
  static const int tvecOffset = 48; // f32 x3, camera-from-marker, metres
  static const int reprojectionErrorOffset = 60; // f16, pixels
  static const int viewingAngleOffset = 62; // u8, degrees
  static const int sharpnessOffset = 63; // u8, 0-255

  static const int flagRejectedSmall = 1;
  static const int flagRejectedBlur = 2;
  static const int flagRejectedOblique = 4;

  /// Not a rejection: the pose may be the IPPE mirror of the true one (a
  /// small marker seen nearly head-on). Corners are valid; the rotation is
  /// not confident (`marker_pose.h`, `squarePoseAmbiguous`).
  static const int flagPoseAmbiguous = 8;

  /// The rejection bits; a record with any of them is not used.
  static const int flagsRejected =
      flagRejectedSmall | flagRejectedBlur | flagRejectedOblique;
}

/// Reusable view over one marker detection record.
final class MarkerDetectionRecordView extends ContractRecordView {
  @override
  int get recordBytes => MarkerDetectionLayout.bytes;

  int get markerId => u16(MarkerDetectionLayout.markerIdOffset);
  set markerId(int v) => setU16(MarkerDetectionLayout.markerIdOffset, v);
  int get dictionary => u8(MarkerDetectionLayout.dictionaryOffset);
  set dictionary(int v) => setU8(MarkerDetectionLayout.dictionaryOffset, v);
  int get flags => u8(MarkerDetectionLayout.flagsOffset);
  set flags(int v) => setU8(MarkerDetectionLayout.flagsOffset, v);

  /// Whether a quality gate rejected the detection.
  bool get rejected => flags & MarkerDetectionLayout.flagsRejected != 0;

  /// Whether the pose may be the IPPE mirror of the true one.
  bool get poseAmbiguous =>
      flags & MarkerDetectionLayout.flagPoseAmbiguous != 0;

  /// Corner coordinate [index] (0-7: x0, y0, ..., x3, y3).
  double corner(int index) =>
      f32(MarkerDetectionLayout.cornersOffset + 4 * index);
  void readCorners(List<double> out, [int outOffset = 0]) =>
      readF32s(MarkerDetectionLayout.cornersOffset, 8, out, outOffset);
  void writeCorners(List<double> values, [int valuesOffset = 0]) =>
      writeF32s(MarkerDetectionLayout.cornersOffset, 8, values, valuesOffset);

  double rvec(int axis) => f32(MarkerDetectionLayout.rvecOffset + 4 * axis);
  double tvec(int axis) => f32(MarkerDetectionLayout.tvecOffset + 4 * axis);
  void setPose(
      double rx, double ry, double rz, double tx, double ty, double tz) {
    setF32(MarkerDetectionLayout.rvecOffset, rx);
    setF32(MarkerDetectionLayout.rvecOffset + 4, ry);
    setF32(MarkerDetectionLayout.rvecOffset + 8, rz);
    setF32(MarkerDetectionLayout.tvecOffset, tx);
    setF32(MarkerDetectionLayout.tvecOffset + 4, ty);
    setF32(MarkerDetectionLayout.tvecOffset + 8, tz);
  }

  double get reprojectionErrorPx =>
      f16(MarkerDetectionLayout.reprojectionErrorOffset);
  set reprojectionErrorPx(double v) =>
      setF16(MarkerDetectionLayout.reprojectionErrorOffset, v);
  int get viewingAngleDegrees => u8(MarkerDetectionLayout.viewingAngleOffset);
  set viewingAngleDegrees(int v) =>
      setU8(MarkerDetectionLayout.viewingAngleOffset, v);
  int get sharpness => u8(MarkerDetectionLayout.sharpnessOffset);
  set sharpness(int v) => setU8(MarkerDetectionLayout.sharpnessOffset, v);
}
