import 'dart:io';
import 'package:flutter/services.dart';
import '../models/camera_resolution.dart';
import '../models/ar_capture_config.dart';
import '../models/ar_camera_intrinsics.dart';
import '../datatypes/image_format.dart';

/// Provides information about available camera capabilities for AR capture
/// Can be used independently throughout the application for capability discovery
class ARCameraCapabilities {
  /// Platform channel for camera capability queries
  static const MethodChannel _channel =
      MethodChannel('ar_flutter_plugin_2/camera_capabilities');

  /// Platform availability check - currently supports Android only
  bool get isSupported => Platform.isAndroid;

  /// Initialize capability querier
  ARCameraCapabilities();

  /// Get list of all supported camera resolutions
  /// Returns empty list on unsupported platforms
  Future<List<CameraResolution>> getSupportedResolutions() async {
    if (!isSupported) return [];

    try {
      final List<dynamic> result =
          await _channel.invokeMethod('getSupportedResolutions');
      return result
          .map(
              (map) => CameraResolution.fromMap(Map<String, dynamic>.from(map)))
          .toList();
    } on PlatformException catch (e) {
      throw ARCameraCapabilityException(
          'Failed to get supported resolutions: ${e.message}');
    }
  }

  /// Get list of all supported image formats
  /// Returns empty list on unsupported platforms
  Future<List<ImageFormat>> getSupportedFormats() async {
    if (!isSupported) return [];

    try {
      final List<dynamic> result =
          await _channel.invokeMethod('getSupportedFormats');
      return result
          .map((formatString) => ImageFormat.values.firstWhere(
                (format) => format.name == formatString,
                orElse: () => ImageFormat.jpeg,
              ))
          .toList();
    } on PlatformException catch (e) {
      throw ARCameraCapabilityException(
          'Failed to get supported formats: ${e.message}');
    }
  }

  /// Get supported ISO sensitivity range for camera
  /// Returns empty list on unsupported platforms
  Future<List<int>> getSupportedISORange() async {
    if (!isSupported) return [];

    try {
      final List<dynamic> result =
          await _channel.invokeMethod('getSupportedISORange');
      return result.cast<int>();
    } on PlatformException catch (e) {
      throw ARCameraCapabilityException(
          'Failed to get supported ISO range: ${e.message}');
    }
  }

  /// Get supported exposure time range for camera
  /// Returns map with 'min' and 'max' Duration keys
  /// Returns empty map on unsupported platforms
  Future<Map<String, Duration>> getSupportedExposureRange() async {
    if (!isSupported) return {};

    try {
      final Map<dynamic, dynamic> result =
          await _channel.invokeMethod('getSupportedExposureRange');
      return {
        'min': Duration(microseconds: result['min'] ?? 0),
        'max': Duration(microseconds: result['max'] ?? 0),
      };
    } on PlatformException catch (e) {
      throw ARCameraCapabilityException(
          'Failed to get supported exposure range: ${e.message}');
    }
  }

  /// Check if specific resolution is supported on current device
  /// Returns false on unsupported platforms
  Future<bool> isResolutionSupported(CameraResolution resolution) async {
    if (!isSupported) return false;

    try {
      return await _channel.invokeMethod(
          'isResolutionSupported', resolution.toMap());
    } on PlatformException catch (e) {
      throw ARCameraCapabilityException(
          'Failed to check resolution support: ${e.message}');
    }
  }

  /// Check if specific format is supported on current device
  /// Returns false on unsupported platforms
  Future<bool> isFormatSupported(ImageFormat format) async {
    if (!isSupported) return false;

    try {
      return await _channel.invokeMethod('isFormatSupported', format.name);
    } on PlatformException catch (e) {
      throw ARCameraCapabilityException(
          'Failed to check format support: ${e.message}');
    }
  }

  /// Get camera intrinsics for the default camera
  /// Returns null on unsupported platforms or if intrinsics cannot be extracted
  Future<ARCameraIntrinsics?> getCameraIntrinsics() async {
    if (!isSupported) return null;

    try {
      final Map<dynamic, dynamic>? result = 
          await _channel.invokeMethod('getCameraIntrinsics');
      if (result != null) {
        final intrinsicsMap = Map<String, dynamic>.from(result);
        return ARCameraIntrinsics.fromMap(intrinsicsMap);
      }
      return null;
    } on PlatformException catch (e) {
      throw ARCameraCapabilityException(
          'Failed to get camera intrinsics: ${e.message}');
    }
  }

  /// Validate complete capture configuration
  Future<ValidationResult> validateCaptureConfig(ARCaptureConfig config) async {
    if (!isSupported) {
      return ValidationResult(
        isValid: false,
        errors: ['Camera capture not supported on this platform'],
      );
    }

    final errors = <String>[];
    final warnings = <String>[];
    ARCaptureConfig? suggestedConfig;

    try {
      // Validate resolution
      final isResSupported = await isResolutionSupported(config.resolution);
      if (!isResSupported) {
        errors.add('Resolution ${config.resolution} is not supported');
        final closestRes = await findClosestResolution(config.resolution);
        if (closestRes != null) {
          suggestedConfig = ARCaptureConfig(
            enableHighResCapture: config.enableHighResCapture,
            captureIntervalMs: config.captureIntervalMs,
            resolution: closestRes,
            format: config.format,
          );
        }
      }

      // Validate format
      final isFormatSupported = await this.isFormatSupported(config.format);
      if (!isFormatSupported) {
        errors.add('Image format ${config.format.name} is not supported');
        final supportedFormats = await getSupportedFormats();
        if (supportedFormats.contains(ImageFormat.jpeg)) {
          suggestedConfig = ARCaptureConfig(
            enableHighResCapture: config.enableHighResCapture,
            captureIntervalMs: config.captureIntervalMs,
            resolution: suggestedConfig?.resolution ?? config.resolution,
            format: ImageFormat.jpeg,
          );
        }
      }

      // Performance warnings
      final impact = await assessPerformanceImpact(config);
      if (impact == PerformanceImpact.high) {
        warnings
            .add('High performance impact expected with this configuration');
      } else if (impact == PerformanceImpact.extreme) {
        warnings.add('Extreme performance impact - consider lower resolution');
      }

      // Capture interval warnings
      if (config.captureIntervalMs < 1000 && config.enableHighResCapture) {
        warnings.add(
            'Very frequent captures with high resolution may cause performance issues');
      }

      return ValidationResult(
        isValid: errors.isEmpty,
        errors: errors,
        warnings: warnings,
        suggestedConfig: suggestedConfig,
      );
    } catch (e) {
      return ValidationResult(
        isValid: false,
        errors: ['Validation failed: ${e.toString()}'],
      );
    }
  }

  /// Find closest supported resolution to requested resolution
  Future<CameraResolution?> findClosestResolution(
      CameraResolution requested) async {
    if (!isSupported) return null;

    try {
      final supportedResolutions = await getSupportedResolutions();
      if (supportedResolutions.isEmpty) return null;

      // Find closest resolution by total pixels
      CameraResolution? closest;
      int minDifference = double.maxFinite.toInt();

      for (final resolution in supportedResolutions) {
        final difference =
            (resolution.totalPixels - requested.totalPixels).abs();
        if (difference < minDifference) {
          minDifference = difference;
          closest = resolution;
        }
      }

      return closest;
    } catch (e) {
      return null;
    }
  }

  /// Get recommended configuration for device
  Future<ARCaptureConfig> getRecommendedConfig() async {
    if (!isSupported) {
      throw ARCameraCapabilityException('Platform not supported');
    }

    try {
      final supportedResolutions = await getSupportedResolutions();
      final supportedFormats = await getSupportedFormats();

      // Choose a balanced resolution (not highest, not lowest)
      final sortedResolutions = supportedResolutions.toList()
        ..sort((a, b) => b.totalPixels.compareTo(a.totalPixels));

      final recommendedResolution = sortedResolutions.length > 2
          ? sortedResolutions[sortedResolutions.length ~/ 3] // Upper third
          : sortedResolutions.first;

      // Prefer JPEG for compatibility
      final recommendedFormat = supportedFormats.contains(ImageFormat.jpeg)
          ? ImageFormat.jpeg
          : supportedFormats.first;

      return ARCaptureConfig(
        enableHighResCapture: true,
        captureIntervalMs: 1000,
        resolution: recommendedResolution,
        format: recommendedFormat,
      );
    } catch (e) {
      throw ARCameraCapabilityException(
          'Failed to generate recommended config: ${e.toString()}');
    }
  }

  /// Check if configuration is optimal for device
  Future<bool> isOptimalConfig(ARCaptureConfig config) async {
    if (!isSupported) return false;

    try {
      final validation = await validateCaptureConfig(config);
      if (!validation.isValid) return false;

      // Check if using reasonable resolution (not too high)
      final supportedResolutions = await getSupportedResolutions();
      final sortedResolutions = supportedResolutions.toList()
        ..sort((a, b) => b.totalPixels.compareTo(a.totalPixels));

      final maxRecommendedResolution = sortedResolutions.length > 1
          ? sortedResolutions[sortedResolutions.length ~/ 2]
          : sortedResolutions.first;

      if (config.resolution.totalPixels >
          maxRecommendedResolution.totalPixels) {
        return false; // Too high resolution
      }

      // Check capture interval
      if (config.captureIntervalMs < 2000) {
        return false; // Too frequent
      }

      return true;
    } catch (e) {
      return false;
    }
  }

  /// Get performance impact assessment
  Future<PerformanceImpact> assessPerformanceImpact(
      ARCaptureConfig config) async {
    if (!isSupported) return PerformanceImpact.low;

    try {
      final totalPixels = config.resolution.totalPixels;
      final captureFrequency = 1000.0 / config.captureIntervalMs;

      // Calculate impact score based on resolution and frequency
      double impactScore = 0.0;

      // Resolution impact
      if (totalPixels > 8000000) {
        // 4K+
        impactScore += 4.0;
      } else if (totalPixels > 2000000) {
        // 1080p+
        impactScore += 2.0;
      } else if (totalPixels > 1000000) {
        // 720p+
        impactScore += 1.0;
      }

      // Frequency impact
      if (captureFrequency > 1.0) {
        // More than once per second
        impactScore += 2.0;
      } else if (captureFrequency > 0.5) {
        // More than once per 2 seconds
        impactScore += 1.0;
      }

      // Format impact
      if (config.format == ImageFormat.raw) {
        impactScore += 2.0;
      }

      // Determine impact level
      if (impactScore >= 6.0) return PerformanceImpact.extreme;
      if (impactScore >= 4.0) return PerformanceImpact.high;
      if (impactScore >= 2.0) return PerformanceImpact.medium;
      return PerformanceImpact.low;
    } catch (e) {
      return PerformanceImpact.medium; // Safe fallback
    }
  }
}

/// Exception thrown when camera capability operations fail
class ARCameraCapabilityException implements Exception {
  final String message;
  ARCameraCapabilityException(this.message);

  @override
  String toString() => 'ARCameraCapabilityException: $message';
}

/// Result of configuration validation
class ValidationResult {
  final bool isValid;
  final List<String> errors;
  final List<String> warnings;
  final ARCaptureConfig? suggestedConfig;

  const ValidationResult({
    required this.isValid,
    this.errors = const [],
    this.warnings = const [],
    this.suggestedConfig,
  });

  bool get hasErrors => errors.isNotEmpty;
  bool get hasWarnings => warnings.isNotEmpty;
}

/// Performance impact assessment
enum PerformanceImpact { low, medium, high, extreme }
