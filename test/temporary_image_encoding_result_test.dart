import 'package:ar_flutter_plugin_2/models/temporary_image_encoding_result.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const verified = <String, Object>{
    'requestedFormat': 'heif',
    'actualFormat': 'heif',
    'width': 4032,
    'height': 3024,
    'byteLength': 1234567,
    'encodeUs': 200000,
    'captureAndEncodeUs': 850000,
    'quality': 95,
    'highResolution': true,
    'temporaryFileRemoved': true,
    'stillCaptureMode': 'single',
    'imageCount': 1,
    'requestedImageCount': 1,
  };
  test('temporary format proof requires decoded identity and complete cleanup',
      () {
    final result = TemporaryImageEncodingResult.fromMap(verified);
    expect(result.isVerified, isTrue);
    expect(result.meetsOneSecondTarget, isTrue);
    for (final mutation in <String, Object>{
      'actualFormat': 'jpeg',
      'width': 0,
      'byteLength': 0,
      'encodeUs': -1,
      'captureAndEncodeUs': 1,
      'quality': 101,
      'highResolution': false,
      'temporaryFileRemoved': false,
    }.entries) {
      expect(
          TemporaryImageEncodingResult.fromMap(
              {...verified, mutation.key: mutation.value}).isVerified,
          isFalse,
          reason: mutation.key);
    }
    expect(TemporaryImageEncodingResult.fromMap({}).isVerified, isFalse);
  });

  test('slow verified format remains measurable without passing latency target',
      () {
    final result = TemporaryImageEncodingResult.fromMap(
        {...verified, 'captureAndEncodeUs': 1000001});
    expect(result.isVerified, isTrue);
    expect(result.meetsOneSecondTarget, isFalse);
  });
}
