import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
import 'package:ar_flutter_plugin_2/validation/configuration_compatibility_validator.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const resolution1080p = CameraResolution(width: 1920, height: 1080);

  test('manual-only capture does not trigger frequent-capture warnings',
      () async {
    const manualOnlyConfig = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 0,
      resolution: resolution1080p,
      format: ImageFormat.jpeg,
      maxCacheSize: 12,
      jpegQuality: 95,
    );

    final warnings =
        await ConfigurationCompatibilityValidator.checkPerformanceImpact(
      planeConfig: PlaneDetectionConfig.horizontalAndVertical,
      captureConfig: manualOnlyConfig,
    );

    expect(
      warnings.any(
        (warning) => warning.message.contains('High-frequency capture'),
      ),
      isFalse,
    );
  });

  test('manual-only capture has no frequency penalty in performance score',
      () async {
    const automaticConfig = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 1500,
      resolution: resolution1080p,
      format: ImageFormat.jpeg,
      maxCacheSize: 10,
      jpegQuality: 95,
    );
    const manualOnlyConfig = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 0,
      resolution: resolution1080p,
      format: ImageFormat.jpeg,
      maxCacheSize: 10,
      jpegQuality: 95,
    );

    final automaticImpact =
        await ConfigurationCompatibilityValidator.assessPerformanceImpact(
      planeConfig: PlaneDetectionConfig.horizontal,
      captureConfig: automaticConfig,
    );
    final manualImpact =
        await ConfigurationCompatibilityValidator.assessPerformanceImpact(
      planeConfig: PlaneDetectionConfig.horizontal,
      captureConfig: manualOnlyConfig,
    );

    expect(
      manualImpact.performanceScore,
      greaterThan(automaticImpact.performanceScore),
    );
  });

  test('manual-only low-end device compatibility does not warn about cadence',
      () async {
    const manualOnlyConfig = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 0,
      resolution: resolution1080p,
      format: ImageFormat.jpeg,
      maxCacheSize: 10,
      jpegQuality: 95,
    );

    final result =
        await ConfigurationCompatibilityValidator.validateConfigurations(
      planeConfig: PlaneDetectionConfig.horizontal,
      captureConfig: manualOnlyConfig,
      deviceModel: 'SM-A105',
    );

    expect(
      result.warnings.any(
        (warning) =>
            warning.contains('Frequent capture may impact performance'),
      ),
      isFalse,
    );
  });

  test('automatic raw capture still warns when cadence is aggressive',
      () async {
    const rawAutomaticConfig = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 3000,
      resolution: resolution1080p,
      format: ImageFormat.raw,
      maxCacheSize: 10,
      jpegQuality: 95,
    );

    final warnings =
        await ConfigurationCompatibilityValidator.checkPerformanceImpact(
      planeConfig: PlaneDetectionConfig.horizontal,
      captureConfig: rawAutomaticConfig,
    );

    expect(
      warnings.any(
        (warning) => warning.message.contains(
          'RAW format with frequent capture will consume significant memory and storage',
        ),
      ),
      isTrue,
    );
  });
}
