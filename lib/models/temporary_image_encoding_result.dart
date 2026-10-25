import 'ar_capture_config.dart';

/// Observed output of an isolated native camera-buffer encoding experiment.
///
/// This is neither a saved photo nor permission to change the durable capture
/// format. Native code has removed its unique temporary file before replying.
class TemporaryImageEncodingResult {
  const TemporaryImageEncodingResult({
    required this.requestedFormat,
    required this.actualFormat,
    required this.width,
    required this.height,
    required this.byteLength,
    required this.encodeUs,
    required this.captureAndEncodeUs,
    required this.quality,
    required this.highResolution,
    required this.temporaryFileRemoved,
    this.stillCaptureMode = ARStillCaptureMode.single,
    this.imageCount = 1,
    this.requestedImageCount = 1,
  });

  factory TemporaryImageEncodingResult.fromMap(Map<Object?, Object?> map) {
    int integer(String key) => map[key] is int ? map[key] as int : 0;
    String text(String key) => map[key] is String ? map[key] as String : '';
    return TemporaryImageEncodingResult(
      requestedFormat: text('requestedFormat'),
      actualFormat: text('actualFormat'),
      width: integer('width'),
      height: integer('height'),
      byteLength: integer('byteLength'),
      encodeUs: integer('encodeUs'),
      captureAndEncodeUs: integer('captureAndEncodeUs'),
      quality: integer('quality'),
      highResolution: map['highResolution'] == true,
      temporaryFileRemoved: map['temporaryFileRemoved'] == true,
      stillCaptureMode: ARStillCaptureMode.fromWire(map['stillCaptureMode']),
      imageCount: ARStillCaptureMode.values
              .any((mode) => mode.wireValue == map['stillCaptureMode'])
          ? integer('imageCount')
          : 0,
      requestedImageCount: integer('requestedImageCount'),
    );
  }

  final String requestedFormat;
  final String actualFormat;
  final int width;
  final int height;
  final int byteLength;
  final int encodeUs;

  /// Request through encode, flush, decode verification and owned-file removal.
  final int captureAndEncodeUs;
  final int quality;
  final bool highResolution;
  final bool temporaryFileRemoved;
  final ARStillCaptureMode stillCaptureMode;
  final int imageCount;
  final int requestedImageCount;

  bool get isVerified =>
      const ['jpeg', 'heif'].contains(requestedFormat) &&
      actualFormat == requestedFormat &&
      width > 0 &&
      height > 0 &&
      byteLength > 0 &&
      encodeUs >= 0 &&
      captureAndEncodeUs > 0 &&
      captureAndEncodeUs >= encodeUs &&
      quality >= 10 &&
      quality <= 100 &&
      highResolution &&
      temporaryFileRemoved &&
      imageCount == requestedImageCount &&
      (stillCaptureMode == ARStillCaptureMode.burst
          ? imageCount >= 2 && imageCount <= 5
          : imageCount == 1) &&
      (actualFormat == 'jpeg' || stillCaptureMode == ARStillCaptureMode.single);

  bool get meetsOneSecondTarget => isVerified && captureAndEncodeUs <= 1000000;
}
