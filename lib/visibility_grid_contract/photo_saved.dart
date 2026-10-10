import 'binary_support.dart';

/// Photo saved event: the 128 B payload of ingest kind 4, sent once per photo
/// after its durable terminal commit.
abstract final class PhotoSavedLayout {
  static const int bytes = 128;
  static const int photoIdOffset = 0; // u8 x32 SHA-256
  static const int photoIdBytes = 32;
  static const int attemptUuidOffset = 32; // u8 x16
  static const int attemptUuidBytes = 16;
  static const int exposureTimestampNsOffset = 48; // i64
  static const int cameraToWorldRowsOffset = 56; // f32 x12, rows 0-2
  static const int intrinsicsOffset = 104; // f32 x4 at still resolution
  static const int widthDiv8Offset = 120; // u16
  static const int heightDiv8Offset = 122; // u16
  static const int sharpnessOffset = 124; // u16 JPEG Laplacian score
  static const int flagsOffset = 126; // u8
  static const int groupEpochLowOffset = 127; // u8

  static const int flagAutomatic = 1;
  static const int flagBlurRejected = 2;
}

/// Reusable view over a photo saved event.
final class PhotoSavedView extends ContractRecordView {
  @override
  int get recordBytes => PhotoSavedLayout.bytes;

  void readPhotoId(List<int> out, [int outOffset = 0]) => readBytes(
      PhotoSavedLayout.photoIdOffset,
      PhotoSavedLayout.photoIdBytes,
      out,
      outOffset);
  void writePhotoId(List<int> id, [int idOffset = 0]) => writeBytes(
      PhotoSavedLayout.photoIdOffset,
      PhotoSavedLayout.photoIdBytes,
      id,
      idOffset);
  int photoIdByte(int index) => u8(PhotoSavedLayout.photoIdOffset + index);

  void readAttemptUuid(List<int> out, [int outOffset = 0]) => readBytes(
      PhotoSavedLayout.attemptUuidOffset,
      PhotoSavedLayout.attemptUuidBytes,
      out,
      outOffset);
  void writeAttemptUuid(List<int> uuid, [int uuidOffset = 0]) => writeBytes(
      PhotoSavedLayout.attemptUuidOffset,
      PhotoSavedLayout.attemptUuidBytes,
      uuid,
      uuidOffset);

  int get exposureTimestampNs =>
      i64(PhotoSavedLayout.exposureTimestampNsOffset);
  set exposureTimestampNs(int v) =>
      setI64(PhotoSavedLayout.exposureTimestampNsOffset, v);

  /// Camera-to-world element at [row] (0-2) and [column] (0-3).
  double cameraToWorld(int row, int column) =>
      f32(PhotoSavedLayout.cameraToWorldRowsOffset + 4 * (row * 4 + column));
  void readCameraToWorldRows(List<double> out, [int outOffset = 0]) =>
      readF32s(PhotoSavedLayout.cameraToWorldRowsOffset, 12, out, outOffset);
  void writeCameraToWorldRows(List<double> rows, [int rowsOffset = 0]) =>
      writeF32s(PhotoSavedLayout.cameraToWorldRowsOffset, 12, rows, rowsOffset);

  double get fx => f32(PhotoSavedLayout.intrinsicsOffset);
  double get fy => f32(PhotoSavedLayout.intrinsicsOffset + 4);
  double get cx => f32(PhotoSavedLayout.intrinsicsOffset + 8);
  double get cy => f32(PhotoSavedLayout.intrinsicsOffset + 12);
  void setIntrinsics(double fx, double fy, double cx, double cy) {
    setF32(PhotoSavedLayout.intrinsicsOffset, fx);
    setF32(PhotoSavedLayout.intrinsicsOffset + 4, fy);
    setF32(PhotoSavedLayout.intrinsicsOffset + 8, cx);
    setF32(PhotoSavedLayout.intrinsicsOffset + 12, cy);
  }

  int get widthDiv8 => u16(PhotoSavedLayout.widthDiv8Offset);
  set widthDiv8(int v) => setU16(PhotoSavedLayout.widthDiv8Offset, v);
  int get heightDiv8 => u16(PhotoSavedLayout.heightDiv8Offset);
  set heightDiv8(int v) => setU16(PhotoSavedLayout.heightDiv8Offset, v);
  int get sharpness => u16(PhotoSavedLayout.sharpnessOffset);
  set sharpness(int v) => setU16(PhotoSavedLayout.sharpnessOffset, v);
  int get flags => u8(PhotoSavedLayout.flagsOffset);
  set flags(int v) => setU8(PhotoSavedLayout.flagsOffset, v);
  int get groupEpochLow => u8(PhotoSavedLayout.groupEpochLowOffset);
  set groupEpochLow(int v) => setU8(PhotoSavedLayout.groupEpochLowOffset, v);

  bool get automatic => flags & PhotoSavedLayout.flagAutomatic != 0;
  bool get blurRejected => flags & PhotoSavedLayout.flagBlurRejected != 0;
}
