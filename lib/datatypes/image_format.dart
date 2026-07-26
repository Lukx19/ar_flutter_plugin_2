/// Logical formats accepted by the capture contract.
///
/// `raw+jpeg` is one capture with two atomically persisted assets; it is not a
/// request for a standalone RAW image.
enum CaptureFormat {
  jpeg,

  /// Simultaneous RAW sensor master and hardware JPEG from one shutter event.
  rawJpeg,

  ;

  String get wireValue => switch (this) {
        CaptureFormat.jpeg => 'jpeg',
        CaptureFormat.rawJpeg => 'raw+jpeg',
      };

  static CaptureFormat fromWire(Object? value) {
    return switch (value) {
      'jpeg' => CaptureFormat.jpeg,
      'raw+jpeg' => CaptureFormat.rawJpeg,
      _ => throw CaptureFormatException(
          code: 'UNSUPPORTED_CAPTURE_FORMAT',
          wireValue: value,
        ),
    };
  }

  static CaptureFormat? tryFromWire(Object? value) {
    try {
      return fromWire(value);
    } on CaptureFormatException {
      return null;
    }
  }
}

/// A file belonging to a logical [CaptureFormat] capture.
enum CaptureAssetFormat {
  jpeg,
  dng;

  String get wireValue => name;

  static CaptureAssetFormat fromWire(Object? value) {
    return switch (value) {
      'jpeg' => CaptureAssetFormat.jpeg,
      'dng' => CaptureAssetFormat.dng,
      _ => throw CaptureFormatException(
          code: 'UNSUPPORTED_CAPTURE_FORMAT',
          wireValue: value,
        ),
    };
  }
}

/// Typed rejection for a logical or asset format outside the current contract.
class CaptureFormatException implements Exception {
  const CaptureFormatException({required this.code, required this.wireValue});

  final String code;
  final Object? wireValue;

  @override
  String toString() => '$code: unsupported capture format $wireValue';
}

/// Deprecated source compatibility alias. It intentionally exposes only the
/// two logical formats; callers that address a file must use
/// [CaptureAssetFormat].
@Deprecated('Use CaptureFormat or CaptureAssetFormat explicitly.')
typedef ImageFormat = CaptureFormat;
