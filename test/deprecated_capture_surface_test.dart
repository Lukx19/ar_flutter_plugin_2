import 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
import 'package:ar_flutter_plugin_2/managers/capture_memory_manager.dart';
import 'package:ar_flutter_plugin_2/managers/memory_manager.dart';
import 'package:ar_flutter_plugin_2/managers/shared_camera_manager.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const config = ARCaptureConfig(
    enableHighResCapture: true,
    captureIntervalMs: 5000,
    resolution: CameraResolution(width: 1920, height: 1080),
    format: ImageFormat.jpeg,
    maxCacheSize: 4,
    jpegQuality: 95,
  );

  test('MemoryManager fails explicitly for the deprecated global channel', () {
    expect(MemoryManager.getMemoryStats, throwsUnsupportedError);
    expect(
      () => MemoryManager.getMemoryOptimizedConfig(config),
      throwsUnsupportedError,
    );
  });

  test('SharedCameraManager fails explicitly for the deprecated global channel', () {
    expect(
      () => SharedCameraManager.instance.initializeWithConfig(
        cameraId: '0',
        captureConfig: config,
      ),
      throwsUnsupportedError,
    );
  });

  test('CaptureMemoryManager fails explicitly for the dead manager surface', () {
    expect(
      () => CaptureMemoryManager.instance.initializeWithConfig(config),
      throwsUnsupportedError,
    );
  });
}
