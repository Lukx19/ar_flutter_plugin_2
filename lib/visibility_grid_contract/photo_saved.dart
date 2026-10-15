import 'dart:typed_data';

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

  /// The event carries a camera (pose, intrinsics and size); a photo whose
  /// pose is unknown, or a shot image taken while tracking was not normal,
  /// has none.
  bool get hasCamera => fx > 0 && fy > 0 && widthDiv8 > 0 && heightDiv8 > 0;
}

/// Shot image extension of the photo saved event (amendment 2026-10-15):
/// 32 B right after the 128 B event when the photo is one image of an iOS
/// burst or quality shot (kind 4 payload of 160 B). Every image arrives as
/// its own event with its own photo id and its own frame's pose, intrinsics
/// and exposure time. Readers of the 128 B event ignore it; Android never
/// writes it.
abstract final class PhotoShotImageLayout {
  static final int magic = contractMagic('C3SI');
  static const int version = 1;
  static const int bytes = 32;

  /// Kind 4 payload with the extension.
  static const int payloadBytes = PhotoSavedLayout.bytes + bytes;

  static const int magicOffset = 0; // u32
  static const int versionOffset = 4; // u16
  static const int extensionBytesOffset = 6; // u16, 32
  static const int shotIdOffset = 8; // u8 x16 UUID
  static const int shotIdBytes = 16;
  static const int imageIndexOffset = 24; // u8, 0 = the attempt's JPEG
  static const int imageCountOffset = 25; // u8
  static const int shotModeOffset = 26; // u8
  static const int flagsOffset = 28; // u16

  static const int modeSingle = 0;
  static const int modeBurst = 1;

  /// One frame with ARKit multi-frame processing (in-AR HDR).
  static const int modeQuality = 2;

  /// Tracking was not normal at this image's frame: kept, no credit (its
  /// 128 B event then carries no pose).
  static const int flagTrackingNotNormal = 1;

  /// The high-resolution frame failed; the video frame was used.
  static const int flagVideoFrameFallback = 2;
}

/// Reusable view over the shot image extension; attach it at the payload
/// offset plus [PhotoSavedLayout.bytes].
final class PhotoShotImageView extends ContractRecordView {
  @override
  int get recordBytes => PhotoShotImageLayout.bytes;

  /// True when a kind 4 payload of [payloadBytes] at [payloadOffset] of
  /// [data] carries a valid shot image extension.
  static bool present(ByteData data, int payloadOffset, int payloadBytes) =>
      payloadBytes >= PhotoShotImageLayout.payloadBytes &&
      validateMagicAndVersion(
            data,
            payloadOffset + PhotoSavedLayout.bytes,
            payloadBytes - PhotoSavedLayout.bytes,
            PhotoShotImageLayout.bytes,
            PhotoShotImageLayout.magic,
            PhotoShotImageLayout.version,
          ) ==
          ContractStatus.ok;

  /// Writes magic, version and size and zeroes every other byte.
  void begin() {
    for (var i = 0; i < PhotoShotImageLayout.bytes; i++) {
      setU8(i, 0);
    }
    setU32(PhotoShotImageLayout.magicOffset, PhotoShotImageLayout.magic);
    setU16(PhotoShotImageLayout.versionOffset, PhotoShotImageLayout.version);
    setU16(
        PhotoShotImageLayout.extensionBytesOffset, PhotoShotImageLayout.bytes);
  }

  void readShotId(List<int> out, [int outOffset = 0]) => readBytes(
      PhotoShotImageLayout.shotIdOffset,
      PhotoShotImageLayout.shotIdBytes,
      out,
      outOffset);
  void writeShotId(List<int> id, [int idOffset = 0]) => writeBytes(
      PhotoShotImageLayout.shotIdOffset,
      PhotoShotImageLayout.shotIdBytes,
      id,
      idOffset);

  int get imageIndex => u8(PhotoShotImageLayout.imageIndexOffset);
  set imageIndex(int v) => setU8(PhotoShotImageLayout.imageIndexOffset, v);
  int get imageCount => u8(PhotoShotImageLayout.imageCountOffset);
  set imageCount(int v) => setU8(PhotoShotImageLayout.imageCountOffset, v);
  int get shotMode => u8(PhotoShotImageLayout.shotModeOffset);
  set shotMode(int v) => setU8(PhotoShotImageLayout.shotModeOffset, v);
  int get flags => u16(PhotoShotImageLayout.flagsOffset);
  set flags(int v) => setU16(PhotoShotImageLayout.flagsOffset, v);

  bool get trackingNotNormal =>
      flags & PhotoShotImageLayout.flagTrackingNotNormal != 0;
  bool get videoFrameFallback =>
      flags & PhotoShotImageLayout.flagVideoFrameFallback != 0;
}
