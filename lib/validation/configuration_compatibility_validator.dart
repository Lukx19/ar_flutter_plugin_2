import 'dart:io';
import 'dart:math' as math;
import '../datatypes/config_planedetection.dart';
import '../models/ar_capture_config.dart';
import '../models/camera_resolution.dart';
import '../models/compatibility_result.dart';
import '../datatypes/image_format.dart';
import '../datatypes/buffer_strategy.dart';

/// Comprehensive configuration compatibility validator
class ConfigurationCompatibilityValidator {
  /// Validate compatibility between plane detection and capture configurations
  static Future<CompatibilityResult> validateConfigurations({
    required PlaneDetectionConfig planeConfig,
    required ARCaptureConfig captureConfig,
    String? deviceModel,
    ValidationContext? context,
  }) async {
    final errors = <String>[];
    final warnings = <String>[];
    final suggestions = <String>[];

    try {
      // Platform compatibility check
      await _checkPlatformCompatibility(captureConfig, errors);

      // Performance impact analysis
      await _analyzePerformanceImpact(
          planeConfig, captureConfig, warnings, suggestions);

      // Memory usage validation
      await _validateMemoryUsage(
          planeConfig, captureConfig, errors, warnings, suggestions);

      // Device-specific compatibility
      if (deviceModel != null) {
        await _checkDeviceSpecificCompatibility(
            deviceModel, planeConfig, captureConfig, warnings, suggestions);
      }

      // Configuration optimization check
      await _checkOptimizationOpportunities(
          planeConfig, captureConfig, suggestions);

      // Cross-component validation
      await _validateCrossComponentCompatibility(
          planeConfig, captureConfig, warnings, suggestions);

      return CompatibilityResult(
        isCompatible: errors.isEmpty,
        errors: errors,
        warnings: warnings,
        suggestions: suggestions,
        overallScore:
            _calculateCompatibilityScore(errors.length, warnings.length),
      );
    } catch (e) {
      return CompatibilityResult(
        isCompatible: false,
        errors: ['Validation failed: ${e.toString()}'],
        warnings: [],
        suggestions: ['Check your configuration parameters and try again'],
        overallScore: 0,
      );
    }
  }

  /// Check performance impact of combined configurations
  static Future<List<CompatibilityWarning>> checkPerformanceImpact({
    required PlaneDetectionConfig planeConfig,
    required ARCaptureConfig captureConfig,
  }) async {
    final warnings = <CompatibilityWarning>[];
    final isAutomaticCapture = _isAutomaticCaptureEnabled(captureConfig);

    // High-frequency capture with intensive plane detection
    if (isAutomaticCapture &&
        captureConfig.captureIntervalMs < 2000 &&
        planeConfig == PlaneDetectionConfig.horizontalAndVertical) {
      warnings.add(CompatibilityWarning(
        type: WarningType.performance,
        severity: WarningSeverity.high,
        message:
            'High-frequency capture (${captureConfig.captureIntervalMs}ms) with horizontal and vertical plane detection may cause frame rate issues',
        suggestion:
            'Consider increasing capture interval to 2000ms or using only horizontal plane detection',
        affectedComponents: ['capture', 'planeDetection'],
      ));
    }

    // High resolution with intensive plane detection
    final isHighResolution =
        captureConfig.resolution.totalPixels > 2073600; // 1920x1080
    if (isHighResolution &&
        planeConfig == PlaneDetectionConfig.horizontalAndVertical) {
      warnings.add(CompatibilityWarning(
        type: WarningType.performance,
        severity: WarningSeverity.medium,
        message:
            'High resolution capture with horizontal and vertical plane detection may impact AR tracking performance',
        suggestion:
            'Consider reducing resolution or using only horizontal plane detection',
        affectedComponents: ['capture', 'planeDetection', 'tracking'],
      ));
    }

    // RAW format with frequent capture
    if (captureConfig.format == ImageFormat.raw &&
        isAutomaticCapture &&
        captureConfig.captureIntervalMs < 5000) {
      warnings.add(CompatibilityWarning(
        type: WarningType.memory,
        severity: WarningSeverity.high,
        message:
            'RAW format with frequent capture will consume significant memory and storage',
        suggestion:
            'Consider using JPEG format or increasing capture interval to 5000ms+',
        affectedComponents: ['capture', 'storage'],
      ));
    }

    // Performance strategy with memory constraints
    if (captureConfig.bufferStrategy == BufferStrategy.performance &&
        captureConfig.maxCacheSize > 15) {
      warnings.add(CompatibilityWarning(
        type: WarningType.memory,
        severity: WarningSeverity.medium,
        message:
            'Performance buffer strategy with large cache may cause memory pressure',
        suggestion: 'Monitor memory usage or consider balanced strategy',
        affectedComponents: ['capture', 'memory'],
      ));
    }

    return warnings;
  }

  /// Suggest optimal capture configuration for given plane detection config
  static Future<ARCaptureConfig> suggestOptimalCaptureConfig({
    required PlaneDetectionConfig planeConfig,
    required List<CameraResolution> availableResolutions,
    String? deviceModel,
  }) async {
    // Base configuration
    var optimalResolution =
        _selectOptimalResolution(availableResolutions, planeConfig);
    var optimalInterval = _calculateOptimalInterval(planeConfig);
    var optimalFormat = _selectOptimalFormat(planeConfig);
    var optimalCacheSize = _calculateOptimalCacheSize(planeConfig);
    var optimalBufferStrategy = _selectOptimalBufferStrategy(planeConfig);

    // Device-specific adjustments
    if (deviceModel != null) {
      final deviceOptimizations = _getDeviceSpecificOptimizations(deviceModel);
      if (deviceOptimizations['reduceResolution'] == true) {
        optimalResolution =
            _findLowerResolution(availableResolutions, optimalResolution);
      }
      if (deviceOptimizations['increaseInterval'] == true) {
        optimalInterval = (optimalInterval * 1.5).round();
      }
    }

    return ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: optimalInterval,
      resolution: optimalResolution,
      format: optimalFormat,
      maxCacheSize: optimalCacheSize,
      bufferStrategy: optimalBufferStrategy,
      jpegQuality: 95, // Standard production default
      autoExposure: true,
      autoWhiteBalance: true,
      enablePoseStream: true,
    );
  }

  /// Get detailed performance assessment
  static Future<ConfigurationPerformanceImpact> assessPerformanceImpact({
    required PlaneDetectionConfig planeConfig,
    required ARCaptureConfig captureConfig,
  }) async {
    int performanceScore = 100;
    final impactFactors = <String>[];
    final recommendations = <String>[];

    // Analyze plane detection impact
    final planeImpact = _getPlaneDetectionImpact(planeConfig);
    performanceScore -= planeImpact;
    if (planeImpact > 30) {
      impactFactors.add('Intensive plane detection (${planeConfig.name})');
      recommendations
          .add('Reduce plane detection complexity for better performance');
    }

    // Analyze resolution impact
    final resolutionImpact =
        _calculateResolutionImpact(captureConfig.resolution);
    performanceScore -= resolutionImpact;
    if (resolutionImpact > 20) {
      impactFactors
          .add('High resolution capture (${captureConfig.resolution})');
      recommendations.add('Consider lower resolution for better performance');
    }

    // Analyze capture frequency impact
    final frequencyImpact =
        _calculateFrequencyImpact(captureConfig.captureIntervalMs);
    performanceScore -= frequencyImpact;
    if (frequencyImpact > 15) {
      impactFactors.add(
          'High capture frequency (${captureConfig.captureFrequencyHz.toStringAsFixed(1)}Hz)');
      recommendations.add('Increase capture interval for better performance');
    }

    // Analyze format impact
    if (captureConfig.format == ImageFormat.raw) {
      performanceScore -= 20;
      impactFactors.add('RAW format processing overhead');
      recommendations.add('Use JPEG format for better performance');
    }

    // Calculate frame rate impact estimate
    final frameRateImpact =
        math.max(0, (100 - performanceScore) * 0.3).toDouble();

    return ConfigurationPerformanceImpact(
      performanceScore: math.max(0, performanceScore),
      impactFactors: impactFactors,
      recommendations: recommendations,
      estimatedFrameRateImpact: frameRateImpact,
    );
  }

  /// Get detailed memory assessment
  static Future<MemoryImpact> assessMemoryImpact({
    required PlaneDetectionConfig planeConfig,
    required ARCaptureConfig captureConfig,
    int? availableMemoryMB,
  }) async {
    final estimatedUsageMB = captureConfig.estimatedMemoryUsageMB;
    final availableMB =
        availableMemoryMB?.toDouble() ?? 2048.0; // Default assumption
    final memoryPressure = estimatedUsageMB / availableMB;

    final optimizations = <String>[];
    if (memoryPressure > 0.5) {
      optimizations.add(
          'Reduce cache size from ${captureConfig.maxCacheSize} to ${(captureConfig.maxCacheSize * 0.7).round()}');
    }
    if (captureConfig.format == ImageFormat.raw && memoryPressure > 0.3) {
      optimizations.add('Switch from RAW to JPEG format');
    }
    if (captureConfig.bufferStrategy == BufferStrategy.performance &&
        memoryPressure > 0.4) {
      optimizations.add('Use memory or balanced buffer strategy');
    }

    return MemoryImpact(
      estimatedMemoryUsageMB: estimatedUsageMB,
      availableMemoryMB: availableMB,
      memoryPressure: memoryPressure,
      memoryOptimizations: optimizations,
    );
  }

  // Private validation methods
  static Future<void> _checkPlatformCompatibility(
    ARCaptureConfig captureConfig,
    List<String> errors,
  ) async {
    if (!Platform.isAndroid) {
      errors
          .add('Capture functionality is currently only supported on Android');
      return;
    }

    // Check if specific format is supported on this platform version
    if (captureConfig.format == ImageFormat.raw && Platform.isAndroid) {
      // RAW support requires Android API 21+
      // This would require platform channel call to check actual API level
      // Simplified here for demonstration
    }
  }

  static Future<void> _analyzePerformanceImpact(
    PlaneDetectionConfig planeConfig,
    ARCaptureConfig captureConfig,
    List<String> warnings,
    List<String> suggestions,
  ) async {
    // Calculate performance score
    int performanceScore = 100;
    final isAutomaticCapture = _isAutomaticCaptureEnabled(captureConfig);

    // Deduct for intensive plane detection
    performanceScore -= _getPlaneDetectionImpact(planeConfig);

    // Deduct for high-resolution capture
    if (captureConfig.resolution.totalPixels > 8000000) {
      // 4K+
      performanceScore -= 30;
    } else if (captureConfig.resolution.totalPixels > 2000000) {
      // 1080p+
      performanceScore -= 15;
    }

    // Deduct for frequent capture
    if (isAutomaticCapture && captureConfig.captureIntervalMs < 1000) {
      performanceScore -= 25;
    } else if (isAutomaticCapture && captureConfig.captureIntervalMs < 3000) {
      performanceScore -= 10;
    }

    // Deduct for RAW format
    if (captureConfig.format == ImageFormat.raw) {
      performanceScore -= 20;
    }

    if (performanceScore < 30) {
      warnings.add(
          'Configuration may cause significant performance issues (score: $performanceScore/100)');
      suggestions.add(
          'Consider reducing resolution, increasing capture interval, or limiting plane detection types');
    } else if (performanceScore < 60) {
      warnings.add(
          'Configuration may impact performance (score: $performanceScore/100)');
      suggestions.add('Monitor frame rate and adjust settings if needed');
    }
  }

  static Future<void> _validateMemoryUsage(
    PlaneDetectionConfig planeConfig,
    ARCaptureConfig captureConfig,
    List<String> errors,
    List<String> warnings,
    List<String> suggestions,
  ) async {
    final estimatedMemoryMB = captureConfig.estimatedMemoryUsageMB;

    // Critical memory usage (likely to cause crashes)
    if (estimatedMemoryMB > 300) {
      errors.add(
          'Configuration requires ${estimatedMemoryMB.toStringAsFixed(1)}MB memory - likely to cause crashes');
      suggestions.add(
          'Reduce cache size, lower resolution, or use JPEG format to reduce memory usage');
    }
    // High memory usage (may cause issues on low-end devices)
    else if (estimatedMemoryMB > 150) {
      warnings.add(
          'Configuration requires ${estimatedMemoryMB.toStringAsFixed(1)}MB memory - may cause issues on low-end devices');
      suggestions
          .add('Consider testing on target devices or reducing memory usage');
    }
    // Moderate memory usage (should be noted)
    else if (estimatedMemoryMB > 50) {
      warnings.add(
          'Configuration will use ${estimatedMemoryMB.toStringAsFixed(1)}MB memory');
    }

    // Check for memory strategy mismatch
    if (estimatedMemoryMB > 100 &&
        captureConfig.bufferStrategy != BufferStrategy.memory) {
      suggestions.add(
          'Consider using BufferStrategy.memory for high memory configurations');
    }
  }

  static Future<void> _checkDeviceSpecificCompatibility(
    String deviceModel,
    PlaneDetectionConfig planeConfig,
    ARCaptureConfig captureConfig,
    List<String> warnings,
    List<String> suggestions,
  ) async {
    // Device-specific performance characteristics
    final lowEndDevices = [
      'SM-A105',
      'Redmi 8A',
      'Galaxy A10'
    ]; // Example low-end devices
    final highEndDevices = [
      'Pixel 7',
      'Galaxy S23',
      'OnePlus 11'
    ]; // Example high-end devices

    final isLowEndDevice =
        lowEndDevices.any((device) => deviceModel.contains(device));
    final isHighEndDevice =
        highEndDevices.any((device) => deviceModel.contains(device));

    if (isLowEndDevice) {
      // More restrictive recommendations for low-end devices
      if (captureConfig.resolution.totalPixels > 1000000) {
        // 720p+
        warnings.add(
            'High resolution may not perform well on this device ($deviceModel)');
        suggestions.add(
            'Consider using 720p or lower resolution for better performance');
      }

      if (_isAutomaticCaptureEnabled(captureConfig) &&
          captureConfig.captureIntervalMs < 5000) {
        warnings.add(
            'Frequent capture may impact performance on this device ($deviceModel)');
        suggestions
            .add('Consider increasing capture interval to 5000ms or higher');
      }

      if (planeConfig == PlaneDetectionConfig.horizontalAndVertical) {
        warnings.add(
            'Complex plane detection may impact performance on this device ($deviceModel)');
        suggestions.add('Consider limiting to horizontal plane detection only');
      }
    } else if (isHighEndDevice) {
      // Optimization suggestions for high-end devices
      if (captureConfig.bufferStrategy == BufferStrategy.memory) {
        suggestions.add(
            'This device can handle BufferStrategy.performance for better responsiveness');
      }

      if (captureConfig.resolution.totalPixels < 2000000) {
        // Less than 1080p
        suggestions
            .add('This device can handle higher resolution capture if needed');
      }
    }
  }

  static Future<void> _checkOptimizationOpportunities(
    PlaneDetectionConfig planeConfig,
    ARCaptureConfig captureConfig,
    List<String> suggestions,
  ) async {
    // Check for unused capabilities
    if (!captureConfig.enableHighResCapture) {
      suggestions.add(
          'High-resolution capture is disabled - enable it for better image quality');
    }

    if (!captureConfig.enablePoseStream &&
        planeConfig != PlaneDetectionConfig.none) {
      suggestions.add(
          'Pose stream is disabled but plane detection is enabled - pose stream may be useful for your use case');
    }

    // Check for suboptimal settings
    if (captureConfig.jpegQuality < 80 &&
        captureConfig.bufferStrategy == BufferStrategy.performance) {
      suggestions.add(
          'JPEG quality is low for performance strategy - consider increasing to 85+ for better quality');
    }

    if (captureConfig.maxCacheSize < 5 &&
        _isAutomaticCaptureEnabled(captureConfig) &&
        captureConfig.captureIntervalMs < 10000) {
      suggestions.add(
          'Cache size is small for frequent capture - consider increasing to 8+ images');
    }

    // Check for conservative settings on capable hardware
    if (captureConfig.bufferStrategy == BufferStrategy.memory &&
        captureConfig.maxCacheSize <= 5) {
      suggestions.add(
          'Very conservative memory settings - consider balanced strategy if device can handle it');
    }
  }

  static Future<void> _validateCrossComponentCompatibility(
    PlaneDetectionConfig planeConfig,
    ARCaptureConfig captureConfig,
    List<String> warnings,
    List<String> suggestions,
  ) async {
    // Check for conflicting enable states
    if (planeConfig == PlaneDetectionConfig.none &&
        captureConfig.enablePoseStream) {
      warnings.add(
          'Pose stream enabled but no plane detection - pose data may be limited');
      suggestions.add(
          'Enable plane detection or disable pose stream to save resources');
    }

    // Check for mismatched quality levels
    if (captureConfig.format == ImageFormat.raw &&
        captureConfig.jpegQuality < 90) {
      warnings.add(
          'RAW format specified but JPEG quality setting is low - JPEG quality ignored for RAW');
    }

    // Check for automation conflicts
    if (captureConfig.captureIntervalMs == 0 &&
        captureConfig.maxCacheSize > 10) {
      warnings.add(
          'Manual capture mode with large cache size - cache may not be utilized efficiently');
      suggestions.add(
          'Reduce cache size for manual capture or enable automatic capture');
    }
  }

  // Helper methods for optimal configuration calculation
  static CameraResolution _selectOptimalResolution(
    List<CameraResolution> availableResolutions,
    PlaneDetectionConfig planeConfig,
  ) {
    final sortedResolutions = availableResolutions.toList()
      ..sort((a, b) => b.totalPixels.compareTo(a.totalPixels));

    // For intensive plane detection, use moderate resolution
    if (planeConfig == PlaneDetectionConfig.horizontalAndVertical) {
      // Find resolution around 1080p
      final target1080p = sortedResolutions.firstWhere(
        (res) => res.totalPixels <= 2073600 && res.totalPixels >= 1000000,
        orElse: () => sortedResolutions[sortedResolutions.length ~/ 2],
      );
      return target1080p;
    }

    // For minimal plane detection, can use higher resolution
    return sortedResolutions.length > 2
        ? sortedResolutions[sortedResolutions.length ~/ 3] // Upper third
        : sortedResolutions.first;
  }

  static int _calculateOptimalInterval(PlaneDetectionConfig planeConfig) {
    // More intensive plane detection requires longer intervals
    final baseInterval = 3000; // 3 seconds base
    final additionalInterval = _getPlaneDetectionComplexity(planeConfig) * 1000;
    return baseInterval + additionalInterval;
  }

  static ImageFormat _selectOptimalFormat(PlaneDetectionConfig planeConfig) {
    // For research use cases with minimal plane detection, RAW might be preferred
    // For general use, JPEG is more practical
    return ImageFormat.jpeg; // Generally optimal
  }

  static int _calculateOptimalCacheSize(PlaneDetectionConfig planeConfig) {
    // More intensive plane detection may benefit from smaller cache to preserve memory
    final baseCache = 10;
    final reductionFactor = _getPlaneDetectionComplexity(planeConfig);
    return math.max(5, baseCache - reductionFactor);
  }

  static BufferStrategy _selectOptimalBufferStrategy(
      PlaneDetectionConfig planeConfig) {
    switch (planeConfig) {
      case PlaneDetectionConfig.horizontalAndVertical:
        return BufferStrategy
            .memory; // Memory-conscious for intensive plane detection
      case PlaneDetectionConfig.none:
        return BufferStrategy.performance; // Can afford performance strategy
      default:
        return BufferStrategy.balanced; // Balanced approach
    }
  }

  static int _calculateCompatibilityScore(int errorCount, int warningCount) {
    if (errorCount > 0) return 0;
    final baseScore = 100;
    final warningPenalty = warningCount * 10;
    return math.max(0, baseScore - warningPenalty);
  }

  static int _calculateResolutionImpact(CameraResolution resolution) {
    if (resolution.totalPixels > 8000000) return 30; // 4K+
    if (resolution.totalPixels > 3000000) return 20; // > 3MP
    if (resolution.totalPixels > 2000000) return 15; // 1080p+
    if (resolution.totalPixels > 1000000) return 10; // 720p+
    return 5; // Lower resolutions
  }

  static int _calculateFrequencyImpact(int intervalMs) {
    if (intervalMs <= 0) return 0; // manual-only capture
    if (intervalMs < 500) return 30; // > 2Hz
    if (intervalMs < 1000) return 25; // 1-2Hz
    if (intervalMs < 2000) return 15; // 0.5-1Hz
    if (intervalMs < 5000) return 10; // 0.2-0.5Hz
    return 5; // < 0.2Hz
  }

  static bool _isAutomaticCaptureEnabled(ARCaptureConfig captureConfig) {
    return captureConfig.captureIntervalMs > 0;
  }

  static Map<String, dynamic> _getDeviceSpecificOptimizations(
      String deviceModel) {
    // This would be expanded with real device database
    final lowEndDevices = ['SM-A105', 'Redmi 8A', 'Galaxy A10'];
    final isLowEnd =
        lowEndDevices.any((device) => deviceModel.contains(device));

    return {
      'reduceResolution': isLowEnd,
      'increaseInterval': isLowEnd,
      'preferMemoryStrategy': isLowEnd,
    };
  }

  static CameraResolution _findLowerResolution(
    List<CameraResolution> resolutions,
    CameraResolution current,
  ) {
    final sorted = resolutions.toList()
      ..sort((a, b) => a.totalPixels.compareTo(b.totalPixels));

    final currentIndex = sorted.indexWhere((r) => r == current);
    if (currentIndex > 0) {
      return sorted[currentIndex - 1];
    }
    return current; // Can't go lower
  }

  /// Get performance impact score for plane detection configuration
  static int _getPlaneDetectionImpact(PlaneDetectionConfig config) {
    switch (config) {
      case PlaneDetectionConfig.none:
        return 0;
      case PlaneDetectionConfig.horizontal:
        return 15;
      case PlaneDetectionConfig.vertical:
        return 20;
      case PlaneDetectionConfig.horizontalAndVertical:
        return 35;
    }
  }

  /// Get complexity level for plane detection configuration
  static int _getPlaneDetectionComplexity(PlaneDetectionConfig config) {
    switch (config) {
      case PlaneDetectionConfig.none:
        return 0;
      case PlaneDetectionConfig.horizontal:
        return 1;
      case PlaneDetectionConfig.vertical:
        return 1;
      case PlaneDetectionConfig.horizontalAndVertical:
        return 2;
    }
  }
}
