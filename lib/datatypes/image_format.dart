/// Supported image formats for camera capture
enum ImageFormat {
  jpeg,

  /// Lossless RGB image encoded from a Camera2 YUV_420_888 still.
  png,

  /// Simultaneous RAW sensor master plus hardware JPEG from one shutter event.
  rawJpeg,

  /// Selects the DNG asset in legacy per-format read/save APIs.
  raw,
}
