import 'package:ar_flutter_plugin_2/datatypes/buffer_strategy.dart';
import 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('HEIC config and asset formats retain a distinct canonical wire value',
      () {
    expect(CaptureFormat.fromWire('heic'), CaptureFormat.heic);
    expect(CaptureFormat.fromWire('heif').wireValue, 'heic');
    expect(CaptureAssetFormat.fromWire('heic'), CaptureAssetFormat.heic);
    final config = ARCaptureConfig(
        format: CaptureFormat.heic,
        resolution: const CameraResolution(width: 4032, height: 3024));
    expect(ARCaptureConfig.fromMap(config.toMap()).format, CaptureFormat.heic);
  });

  test(
      'assessed producer config defaults off and preserves explicit copy map identity',
      () {
    const legacy = ARCaptureConfig(
        resolution: CameraResolution(width: 4128, height: 3096),
        format: CaptureFormat.jpeg);
    expect(legacy.retainAssessedOriginals, isFalse);
    final assessed = legacy.copyWith(retainAssessedOriginals: true);
    expect(ARCaptureConfig.fromMap(assessed.toMap()), assessed);
    expect(assessed.copyWith(jpegQuality: 95).retainAssessedOriginals, isTrue);
    expect(assessed.copyWith(retainAssessedOriginals: false), legacy);
    expect(assessed, isNot(legacy));
    expect(
        ARCaptureConfig.fromMap(
                {...legacy.toMap()}..remove('retainAssessedOriginals'))
            .retainAssessedOriginals,
        isFalse);
    expect(assessed.resolution, legacy.resolution);
    expect(assessed.jpegQuality, 95);
  });

  const baseConfig = ARCaptureConfig(
    enableHighResCapture: true,
    captureIntervalMs: 5000,
    resolution: CameraResolution(width: 1920, height: 1080),
    format: ImageFormat.jpeg,
    maxCacheSize: 10,
    jpegQuality: 95,
    bufferStrategy: BufferStrategy.balanced,
  );

  test('manual-only capture interval is valid', () {
    const config = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 0,
      resolution: CameraResolution(width: 640, height: 480),
      format: ImageFormat.jpeg,
      maxCacheSize: 4,
      jpegQuality: 95,
    );

    expect(config.isValid, isTrue);
    expect(config.captureFrequencyHz, 0.0);
  });

  test('capture interval below 100ms is invalid unless manual-only', () {
    const config = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 99,
      resolution: CameraResolution(width: 640, height: 480),
      format: ImageFormat.jpeg,
      maxCacheSize: 4,
      jpegQuality: 95,
    );

    expect(config.isValid, isFalse);
  });

  test('capture interval above one hour is invalid', () {
    const config = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 3600001,
      resolution: CameraResolution(width: 640, height: 480),
      format: ImageFormat.jpeg,
      maxCacheSize: 4,
      jpegQuality: 95,
    );

    expect(config.isValid, isFalse);
  });

  test('toMap and fromMap round-trip key capture settings', () {
    final roundTripped = ARCaptureConfig.fromMap(baseConfig.toMap());

    expect(roundTripped, equals(baseConfig));
    expect(roundTripped.toMap(), equals(baseConfig.toMap()));
  });

  test('rejects removed, missing, and unknown logical capture formats', () {
    for (final format in <Object?>['raw', 'raw_only', 'png', null]) {
      expect(
        () => ARCaptureConfig.fromMap(<String, dynamic>{
          ...baseConfig.toMap(),
          if (format != null) 'format': format else 'format': null,
        }),
        throwsA(
          isA<CaptureFormatException>()
              .having(
                  (error) => error.code, 'code', 'UNSUPPORTED_CAPTURE_FORMAT')
              .having((error) => error.wireValue, 'wire value', format),
        ),
      );
    }
  });

  test('adjustForAvailableMemory keeps config when usage is already safe', () {
    final adjusted = baseConfig.adjustForAvailableMemory(512);

    expect(adjusted, equals(baseConfig));
    expect(adjusted.bufferStrategy, BufferStrategy.balanced);
  });

  test('adjustForAvailableMemory reduces cache size under pressure', () {
    const config = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 5000,
      resolution: CameraResolution(width: 4032, height: 3024),
      format: ImageFormat.jpeg,
      maxCacheSize: 20,
      jpegQuality: 95,
      bufferStrategy: BufferStrategy.performance,
    );

    final adjusted = config.adjustForAvailableMemory(64);

    expect(adjusted.maxCacheSize, lessThan(config.maxCacheSize));
    expect(adjusted.maxCacheSize, greaterThanOrEqualTo(1));
    expect(adjusted.bufferStrategy, BufferStrategy.memory);
  });

  test('factory presets advertise their intended optimization modes', () {
    final performanceConfig = ARCaptureConfig.performance(
      resolution: const CameraResolution(width: 1920, height: 1080),
    );
    final memoryConfig = ARCaptureConfig.memoryOptimized(
      resolution: const CameraResolution(width: 1920, height: 1080),
    );

    expect(performanceConfig.isPerformanceOptimized, isTrue);
    expect(memoryConfig.isMemoryOptimized, isTrue);
    expect(memoryConfig.enablePoseStream, isFalse);
  });
}
