import 'dart:convert';
import 'dart:io';
import 'package:flutter/services.dart';
import 'package:device_info_plus/device_info_plus.dart';
import '../ar_flutter_plugin.dart';
import '../capabilities/ar_camera_capabilities.dart';
import '../models/ar_capture_config.dart';
import '../models/camera_resolution.dart';

/// Performance analysis configuration
class PerformanceAnalysisConfig {
  final Duration analysisWindow;
  final bool enableMemoryProfiling;
  final bool enableCPUProfiling;
  final bool enableNetworkProfiling;
  final int samplingIntervalMs;
  
  const PerformanceAnalysisConfig({
    this.analysisWindow = const Duration(minutes: 5),
    this.enableMemoryProfiling = true,
    this.enableCPUProfiling = true,
    this.enableNetworkProfiling = false,
    this.samplingIntervalMs = 1000,
  });
}

/// Diagnostic report containing system and configuration information
class DiagnosticReport {
  final DateTime timestamp;
  final SystemInfo systemInfo;
  final CapabilityInfo capabilityInfo;
  final List<ConfigurationIssue> issues;
  final Map<String, dynamic> performanceMetrics;
  final List<String> recommendations;
  
  const DiagnosticReport({
    required this.timestamp,
    required this.systemInfo,
    required this.capabilityInfo,
    required this.issues,
    required this.performanceMetrics,
    required this.recommendations,
  });
  
  Map<String, dynamic> toJson() {
    return {
      'timestamp': timestamp.toIso8601String(),
      'system_info': systemInfo.toJson(),
      'capability_info': capabilityInfo.toJson(),
      'issues': issues.map((i) => i.toJson()).toList(),
      'performance_metrics': performanceMetrics,
      'recommendations': recommendations,
    };
  }
}

/// System information
class SystemInfo {
  final String platform;
  final String platformVersion;
  final String deviceModel;
  final String deviceManufacturer;
  final int availableMemoryMB;
  final int totalMemoryMB;
  final int cpuCores;
  final double batteryLevel;
  
  const SystemInfo({
    required this.platform,
    required this.platformVersion,
    required this.deviceModel,
    required this.deviceManufacturer,
    required this.availableMemoryMB,
    required this.totalMemoryMB,
    required this.cpuCores,
    required this.batteryLevel,
  });
  
  Map<String, dynamic> toJson() {
    return {
      'platform': platform,
      'platform_version': platformVersion,
      'device_model': deviceModel,
      'device_manufacturer': deviceManufacturer,
      'available_memory_mb': availableMemoryMB,
      'total_memory_mb': totalMemoryMB,
      'cpu_cores': cpuCores,
      'battery_level': batteryLevel,
    };
  }
}

/// Camera capability information
class CapabilityInfo {
  final bool isSupported;
  final List<CameraResolution> supportedResolutions;
  final List<String> supportedFormats;
  final List<int> isoRange;
  final Map<String, Duration> exposureRange;
  final bool supportsSharedCamera;
  final bool supportsManualFocus;
  
  const CapabilityInfo({
    required this.isSupported,
    required this.supportedResolutions,
    required this.supportedFormats,
    required this.isoRange,
    required this.exposureRange,
    required this.supportsSharedCamera,
    required this.supportsManualFocus,
  });
  
  Map<String, dynamic> toJson() {
    return {
      'is_supported': isSupported,
      'supported_resolutions': supportedResolutions.map((r) => {
        'width': r.width,
        'height': r.height,
        'aspect_ratio': r.aspectRatio,
        'total_pixels': r.totalPixels,
      }).toList(),
      'supported_formats': supportedFormats,
      'iso_range': isoRange,
      'exposure_range': exposureRange.map((k, v) => MapEntry(k, v.inMicroseconds)),
      'supports_shared_camera': supportsSharedCamera,
      'supports_manual_focus': supportsManualFocus,
    };
  }
}

/// Configuration issue definition
class ConfigurationIssue {
  final String type;
  final String severity; // 'error', 'warning', 'info'
  final String message;
  final String? suggestion;
  final Map<String, dynamic> details;
  
  const ConfigurationIssue({
    required this.type,
    required this.severity,
    required this.message,
    this.suggestion,
    this.details = const {},
  });
  
  Map<String, dynamic> toJson() {
    return {
      'type': type,
      'severity': severity,
      'message': message,
      'suggestion': suggestion,
      'details': details,
    };
  }
}

/// Developer tools and utilities for AR capture manager
/// 
/// This class provides comprehensive developer tools including configuration
/// validation, performance analysis, diagnostic reporting, and compatibility
/// checking to support capture manager development and maintenance.
class CaptureManagerDevTools {
  static const String _version = '1.0.0';
  
  /// Validate capture configuration with detailed analysis
  static Future<void> validateConfiguration(ARCaptureConfig config) async {
    print('=== AR Capture Configuration Validation ===');
    print('Timestamp: ${DateTime.now().toIso8601String()}');
    print('Tool Version: $_version');
    print('');
    
    final capabilities = ARCameraCapabilities();
    
    // Basic validation
    print('🔍 Basic Configuration Analysis:');
    print('  Resolution: ${config.resolution.width}x${config.resolution.height}');
    print('  Format: ${config.format}');
    print('  High Resolution: ${config.enableHighResCapture}');
    print('  Capture Interval: ${config.captureIntervalMs}ms');
    print('  Total Pixels: ${config.resolution.totalPixels}');
    print('  Aspect Ratio: ${config.resolution.aspectRatio.toStringAsFixed(2)}');
    print('');
    
    // Platform support check
    print('🚀 Platform Support:');
    if (!capabilities.isSupported) {
      print('  ❌ Camera capabilities not supported on this platform');
      print('  Platform: ${Platform.operatingSystem}');
      return;
    }
    print('  ✅ Platform supported');
    print('');
    
    // Detailed validation
    print('📋 Detailed Validation:');
    final validation = await capabilities.validateCaptureConfig(config);
    
    if (validation.isValid) {
      print('  ✅ Configuration is valid');
    } else {
      print('  ❌ Configuration validation failed');
      for (final error in validation.errors) {
        print('    Error: $error');
      }
    }
    
    if (validation.warnings.isNotEmpty) {
      print('  ⚠️  Warnings:');
      for (final warning in validation.warnings) {
        print('    Warning: $warning');
      }
    }
    
    if (validation.suggestedConfig != null) {
      final suggested = validation.suggestedConfig!;
      print('  💡 Suggested Configuration:');
      print('    Resolution: ${suggested.resolution.width}x${suggested.resolution.height}');
      print('    Format: ${suggested.format}');
      print('    High Resolution: ${suggested.enableHighResCapture}');
      print('    Capture Interval: ${suggested.captureIntervalMs}ms');
    }
    print('');
    
    // Performance impact analysis
    print('⚡ Performance Impact Analysis:');
    final impact = await capabilities.assessPerformanceImpact(config);
    print('  Impact Level: $impact');
    
    final recommendations = await _generatePerformanceRecommendations(config, impact);
    if (recommendations.isNotEmpty) {
      print('  📝 Recommendations:');
      for (final recommendation in recommendations) {
        print('    • $recommendation');
      }
    }
    print('');
    
    // Capability compatibility
    print('🔧 Capability Compatibility:');
    await _analyzeCapabilityCompatibility(config, capabilities);
    
    print('=== Validation Complete ===');
  }
  
  /// Analyze performance with detailed metrics
  static Future<void> analyzePerformance(PerformanceAnalysisConfig config) async {
    print('=== Performance Analysis ===');
    print('Analysis Window: ${config.analysisWindow.inMinutes} minutes');
    print('Sampling Interval: ${config.samplingIntervalMs}ms');
    print('');
    
    final metrics = <String, List<double>>{
      'memory_usage': [],
      'cpu_usage': [],
      'capture_latency': [],
    };
    
    final startTime = DateTime.now();
    final endTime = startTime.add(config.analysisWindow);
    
    print('📊 Starting performance monitoring...');
    
    while (DateTime.now().isBefore(endTime)) {
      // Collect metrics
      if (config.enableMemoryProfiling) {
        final memoryUsage = await _getCurrentMemoryUsage();
        metrics['memory_usage']!.add(memoryUsage);
      }
      
      if (config.enableCPUProfiling) {
        final cpuUsage = await _getCurrentCPUUsage();
        metrics['cpu_usage']!.add(cpuUsage);
      }
      
      // Simulate capture latency measurement
      final latency = await _measureCaptureLatency();
      metrics['capture_latency']!.add(latency);
      
      await Future.delayed(Duration(milliseconds: config.samplingIntervalMs));
      
      // Progress indicator
      final elapsed = DateTime.now().difference(startTime);
      final progress = elapsed.inMilliseconds / config.analysisWindow.inMilliseconds;
      if (elapsed.inSeconds % 30 == 0) {
        print('  Progress: ${(progress * 100).toStringAsFixed(1)}%');
      }
    }
    
    print('');
    print('📈 Performance Analysis Results:');
    
    // Analyze memory metrics
    if (metrics['memory_usage']!.isNotEmpty) {
      final memoryData = metrics['memory_usage']!;
      print('  Memory Usage:');
      print('    Average: ${_calculateAverage(memoryData).toStringAsFixed(1)} MB');
      print('    Peak: ${_calculateMax(memoryData).toStringAsFixed(1)} MB');
      print('    Min: ${_calculateMin(memoryData).toStringAsFixed(1)} MB');
      print('    Growth: ${(memoryData.last - memoryData.first).toStringAsFixed(1)} MB');
    }
    
    // Analyze CPU metrics
    if (metrics['cpu_usage']!.isNotEmpty) {
      final cpuData = metrics['cpu_usage']!;
      print('  CPU Usage:');
      print('    Average: ${_calculateAverage(cpuData).toStringAsFixed(1)}%');
      print('    Peak: ${_calculateMax(cpuData).toStringAsFixed(1)}%');
      print('    95th Percentile: ${_calculatePercentile(cpuData, 0.95).toStringAsFixed(1)}%');
    }
    
    // Analyze capture latency
    if (metrics['capture_latency']!.isNotEmpty) {
      final latencyData = metrics['capture_latency']!;
      print('  Capture Latency:');
      print('    Average: ${_calculateAverage(latencyData).toStringAsFixed(1)} ms');
      print('    Median: ${_calculateMedian(latencyData).toStringAsFixed(1)} ms');
      print('    95th Percentile: ${_calculatePercentile(latencyData, 0.95).toStringAsFixed(1)} ms');
      print('    Max: ${_calculateMax(latencyData).toStringAsFixed(1)} ms');
    }
    
    print('');
    print('=== Performance Analysis Complete ===');
  }
  
  /// Generate comprehensive diagnostic report
  static Future<void> generateDiagnosticReport() async {
    print('=== Generating Diagnostic Report ===');
    
    final systemInfo = await _collectSystemInfo();
    final capabilityInfo = await _collectCapabilityInfo();
    final issues = await _detectConfigurationIssues();
    final performanceMetrics = await _collectPerformanceMetrics();
    final recommendations = await _generateSystemRecommendations(systemInfo, capabilityInfo);
    
    final report = DiagnosticReport(
      timestamp: DateTime.now(),
      systemInfo: systemInfo,
      capabilityInfo: capabilityInfo,
      issues: issues,
      performanceMetrics: performanceMetrics,
      recommendations: recommendations,
    );
    
    // Save report to file
    final reportJson = JsonEncoder.withIndent('  ').convert(report.toJson());
    final timestamp = DateTime.now().toIso8601String().replaceAll(':', '-');
    final filename = 'diagnostic_report_$timestamp.json';
    
    try {
      final file = File(filename);
      await file.writeAsString(reportJson);
      print('📄 Diagnostic report saved to: $filename');
    } catch (e) {
      print('❌ Failed to save report: $e');
      print('📄 Report content:');
      print(reportJson);
    }
    
    // Print summary
    print('');
    print('📋 Diagnostic Summary:');
    print('  System: ${systemInfo.deviceManufacturer} ${systemInfo.deviceModel}');
    print('  Platform: ${systemInfo.platform} ${systemInfo.platformVersion}');
    print('  Memory: ${systemInfo.availableMemoryMB}/${systemInfo.totalMemoryMB} MB');
    print('  CPU Cores: ${systemInfo.cpuCores}');
    print('  Battery: ${(systemInfo.batteryLevel * 100).toStringAsFixed(1)}%');
    print('  Camera Support: ${capabilityInfo.isSupported ? "✅" : "❌"}');
    print('  Issues Found: ${issues.length}');
    print('  Recommendations: ${recommendations.length}');
    
    if (issues.isNotEmpty) {
      print('');
      print('⚠️  Issues Detected:');
      for (final issue in issues) {
        final icon = issue.severity == 'error' ? '❌' : 
                    issue.severity == 'warning' ? '⚠️' : 'ℹ️';
        print('  $icon ${issue.type}: ${issue.message}');
      }
    }
    
    if (recommendations.isNotEmpty) {
      print('');
      print('💡 Recommendations:');
      for (final recommendation in recommendations) {
        print('  • $recommendation');
      }
    }
    
    print('');
    print('=== Diagnostic Report Complete ===');
  }
  
  /// Run compatibility check across different configurations
  static Future<void> runCompatibilityCheck() async {
    print('=== Compatibility Check ===');
    
    final capabilities = ARCameraCapabilities();
    
    if (!capabilities.isSupported) {
      print('❌ Platform not supported for compatibility check');
      return;
    }
    
    print('🔍 Testing multiple configuration scenarios...');
    print('');
    
    final testConfigs = await _generateTestConfigurations(capabilities);
    int passedCount = 0;
    int totalCount = testConfigs.length;
    
    for (int i = 0; i < testConfigs.length; i++) {
      final config = testConfigs[i];
      final testName = 'Test ${i + 1}';
      
      print('📋 $testName: ${config.resolution.width}x${config.resolution.height}, ${config.format}, interval:${config.captureIntervalMs}ms');
      
      try {
        final validation = await capabilities.validateCaptureConfig(config);
        final impact = await capabilities.assessPerformanceImpact(config);
        
        if (validation.isValid) {
          print('  ✅ Valid (Impact: $impact)');
          passedCount++;
        } else {
          print('  ❌ Invalid: ${validation.errors.join(", ")}');
          if (validation.suggestedConfig != null) {
            final suggested = validation.suggestedConfig!;
            print('     💡 Suggested: ${suggested.resolution.width}x${suggested.resolution.height}');
          }
        }
      } catch (e) {
        print('  ❌ Error: $e');
      }
      
      print('');
    }
    
    print('📊 Compatibility Results:');
    print('  Passed: $passedCount/$totalCount (${(passedCount / totalCount * 100).toStringAsFixed(1)}%)');
    print('  Failed: ${totalCount - passedCount}/$totalCount');
    
    if (passedCount == totalCount) {
      print('  🎉 Excellent compatibility!');
    } else if (passedCount / totalCount >= 0.8) {
      print('  ✅ Good compatibility');
    } else if (passedCount / totalCount >= 0.6) {
      print('  ⚠️  Moderate compatibility - consider optimizations');
    } else {
      print('  ❌ Poor compatibility - significant issues detected');
    }
    
    print('');
    print('=== Compatibility Check Complete ===');
  }
  
  // Private helper methods
  
  static Future<List<String>> _generatePerformanceRecommendations(
    ARCaptureConfig config,
    PerformanceImpact impact,
  ) async {
    final recommendations = <String>[];
    
    switch (impact) {
      case PerformanceImpact.extreme:
        recommendations.add('Consider reducing resolution significantly');
        recommendations.add('Increase capture interval to >3000ms');
        recommendations.add('Disable high resolution capture');
        break;
      case PerformanceImpact.high:
        recommendations.add('Consider reducing resolution or increasing interval');
        recommendations.add('Monitor memory usage closely');
        break;
      case PerformanceImpact.medium:
        recommendations.add('Configuration should work well on most devices');
        break;
      case PerformanceImpact.low:
        recommendations.add('Excellent configuration for performance');
        break;
    }
    
    // Resolution-specific recommendations
    if (config.resolution.totalPixels > 8000000) {
      recommendations.add('4K+ resolution may cause issues on lower-end devices');
    }
    
    // Interval-specific recommendations
    if (config.captureIntervalMs < 500) {
      recommendations.add('Very fast capture interval may overwhelm some devices');
    }
    
    return recommendations;
  }
  
  static Future<void> _analyzeCapabilityCompatibility(
    ARCaptureConfig config,
    ARCameraCapabilities capabilities,
  ) async {
    final resolutions = await capabilities.getSupportedResolutions();
    final formats = await capabilities.getSupportedFormats();
    
    // Check resolution support
    final isResolutionSupported = resolutions.any((r) => 
        r.width == config.resolution.width && r.height == config.resolution.height);
    
    if (isResolutionSupported) {
      print('  ✅ Resolution supported');
    } else {
      print('  ❌ Resolution not supported');
      final closest = _findClosestResolution(resolutions, config.resolution);
      print('    Closest: ${closest.width}x${closest.height}');
    }
    
    // Check format support
    final formatNames = formats.map((f) => f.toString().split('.').last).toList();
    final configFormat = config.format.toString().split('.').last;
    
    if (formatNames.contains(configFormat)) {
      print('  ✅ Format supported');
    } else {
      print('  ❌ Format not supported');
      print('    Available: ${formatNames.join(", ")}');
    }
    
    // Additional capability checks
    final intrinsics = await capabilities.getCameraIntrinsics();
    if (intrinsics != null) {
      print('  ✅ Camera intrinsics available');
      print('    Focal Length: ${intrinsics.focalLength.fx.toStringAsFixed(1)}x${intrinsics.focalLength.fy.toStringAsFixed(1)}');
    } else {
      print('  ⚠️  Camera intrinsics not available');
    }
  }
  
  static CameraResolution _findClosestResolution(
    List<CameraResolution> available,
    CameraResolution target,
  ) {
    available.sort((a, b) => 
        (a.totalPixels - target.totalPixels).abs()
            .compareTo((b.totalPixels - target.totalPixels).abs()));
    return available.first;
  }
  
  static Future<SystemInfo> _collectSystemInfo() async {
    final deviceInfo = DeviceInfoPlugin();
    
    try {
      if (Platform.isAndroid) {
        final androidInfo = await deviceInfo.androidInfo;
        return SystemInfo(
          platform: 'Android',
          platformVersion: 'API ${androidInfo.version.sdkInt} (${androidInfo.version.release})',
          deviceModel: androidInfo.model,
          deviceManufacturer: androidInfo.manufacturer,
          availableMemoryMB: await _getAvailableMemoryMB(),
          totalMemoryMB: await _getTotalMemoryMB(),
          cpuCores: Platform.numberOfProcessors,
          batteryLevel: await _getBatteryLevel(),
        );
      } else if (Platform.isIOS) {
        final iosInfo = await deviceInfo.iosInfo;
        return SystemInfo(
          platform: 'iOS',
          platformVersion: iosInfo.systemVersion,
          deviceModel: iosInfo.model,
          deviceManufacturer: 'Apple',
          availableMemoryMB: await _getAvailableMemoryMB(),
          totalMemoryMB: await _getTotalMemoryMB(),
          cpuCores: Platform.numberOfProcessors,
          batteryLevel: await _getBatteryLevel(),
        );
      }
    } catch (e) {
      print('Error collecting device info: $e');
    }
    
    // Fallback
    return SystemInfo(
      platform: Platform.operatingSystem,
      platformVersion: Platform.operatingSystemVersion,
      deviceModel: 'Unknown',
      deviceManufacturer: 'Unknown',
      availableMemoryMB: 1024, // Default 1GB
      totalMemoryMB: 2048, // Default 2GB
      cpuCores: Platform.numberOfProcessors,
      batteryLevel: 1.0, // Default 100%
    );
  }
  
  static Future<CapabilityInfo> _collectCapabilityInfo() async {
    final capabilities = ARCameraCapabilities();
    
    if (!capabilities.isSupported) {
      return const CapabilityInfo(
        isSupported: false,
        supportedResolutions: [],
        supportedFormats: [],
        isoRange: [],
        exposureRange: {},
        supportsSharedCamera: false,
        supportsManualFocus: false,
      );
    }
    
    try {
      final resolutions = await capabilities.getSupportedResolutions();
      final formats = await capabilities.getSupportedFormats();
      final isoRange = await capabilities.getSupportedISORange();
      final exposureRange = await capabilities.getSupportedExposureRange();
      
      return CapabilityInfo(
        isSupported: true,
        supportedResolutions: resolutions,
        supportedFormats: formats.map((f) => f.toString()).toList(),
        isoRange: isoRange,
        exposureRange: exposureRange,
        supportsSharedCamera: true, // Mock - would need actual implementation
        supportsManualFocus: true, // Mock - would need actual implementation
      );
    } catch (e) {
      print('Error collecting capability info: $e');
      return const CapabilityInfo(
        isSupported: true,
        supportedResolutions: [],
        supportedFormats: [],
        isoRange: [],
        exposureRange: {},
        supportsSharedCamera: false,
        supportsManualFocus: false,
      );
    }
  }
  
  static Future<List<ConfigurationIssue>> _detectConfigurationIssues() async {
    final issues = <ConfigurationIssue>[];
    
    // Check platform support
    final capabilities = ARCameraCapabilities();
    if (!capabilities.isSupported) {
      issues.add(const ConfigurationIssue(
        type: 'platform_support',
        severity: 'error',
        message: 'Camera capabilities not supported on this platform',
        suggestion: 'Use Android platform for full functionality',
      ));
    }
    
    // Check memory constraints
    final availableMemory = await _getAvailableMemoryMB();
    if (availableMemory < 512) {
      issues.add(ConfigurationIssue(
        type: 'memory_constraint',
        severity: 'warning',
        message: 'Low available memory detected: ${availableMemory}MB',
        suggestion: 'Consider using lower resolution configurations',
        details: {'available_memory_mb': availableMemory},
      ));
    }
    
    // Check battery level
    final batteryLevel = await _getBatteryLevel();
    if (batteryLevel < 0.2) {
      issues.add(ConfigurationIssue(
        type: 'battery_low',
        severity: 'warning',
        message: 'Low battery level: ${(batteryLevel * 100).toStringAsFixed(1)}%',
        suggestion: 'Consider enabling battery optimization mode',
        details: {'battery_level': batteryLevel},
      ));
    }
    
    return issues;
  }
  
  static Future<Map<String, dynamic>> _collectPerformanceMetrics() async {
    return {
      'memory_usage_mb': await _getCurrentMemoryUsage(),
      'cpu_usage_percent': await _getCurrentCPUUsage(),
      'battery_level_percent': (await _getBatteryLevel()) * 100,
      'available_storage_mb': await _getAvailableStorageMB(),
      'capture_latency_ms': await _measureCaptureLatency(),
    };
  }
  
  static Future<List<String>> _generateSystemRecommendations(
    SystemInfo systemInfo,
    CapabilityInfo capabilityInfo,
  ) async {
    final recommendations = <String>[];
    
    // Memory recommendations
    if (systemInfo.availableMemoryMB < 1024) {
      recommendations.add('Consider using memory-optimized configurations due to limited RAM');
    }
    
    // CPU recommendations
    if (systemInfo.cpuCores < 4) {
      recommendations.add('Limited CPU cores detected - use longer capture intervals');
    }
    
    // Battery recommendations
    if (systemInfo.batteryLevel < 0.3) {
      recommendations.add('Enable battery optimization mode for extended usage');
    }
    
    // Capability recommendations
    if (capabilityInfo.supportedResolutions.length < 3) {
      recommendations.add('Limited resolution options - test thoroughly on this device');
    }
    
    return recommendations;
  }
  
  static Future<List<ARCaptureConfig>> _generateTestConfigurations(
    ARCameraCapabilities capabilities,
  ) async {
    final resolutions = await capabilities.getSupportedResolutions();
    final formats = await capabilities.getSupportedFormats();
    
    final configs = <ARCaptureConfig>[];
    
    // Test with different resolutions
    for (final resolution in resolutions.take(3)) {
      configs.add(ARCaptureConfig(
        resolution: resolution,
        format: formats.isNotEmpty ? formats.first : ImageFormat.jpeg,
        enableHighResCapture: true,
        captureIntervalMs: 1000,
      ));
    }
    
    // Test with different intervals
    if (resolutions.isNotEmpty) {
      final midResolution = resolutions[resolutions.length ~/ 2];
      for (final interval in [500, 1000, 2000, 5000]) {
        configs.add(ARCaptureConfig(
          resolution: midResolution,
          format: formats.isNotEmpty ? formats.first : ImageFormat.jpeg,
          enableHighResCapture: false,
          captureIntervalMs: interval,
        ));
      }
    }
    
    return configs;
  }
  
  // Mock implementations for metrics (would be platform-specific in real implementation)
  static Future<double> _getCurrentMemoryUsage() async => 150.0 + (DateTime.now().millisecondsSinceEpoch % 100);
  static Future<double> _getCurrentCPUUsage() async => 25.0 + (DateTime.now().millisecondsSinceEpoch % 50);
  static Future<double> _getBatteryLevel() async => 0.75; // 75%
  static Future<int> _getAvailableMemoryMB() async => 2048; // 2GB
  static Future<int> _getTotalMemoryMB() async => 4096; // 4GB
  static Future<int> _getAvailableStorageMB() async => 8192; // 8GB
  static Future<double> _measureCaptureLatency() async => 120.0 + (DateTime.now().millisecondsSinceEpoch % 80);
  
  // Statistical helper functions
  static double _calculateAverage(List<double> values) {
    return values.reduce((a, b) => a + b) / values.length;
  }
  
  static double _calculateMax(List<double> values) {
    return values.reduce((a, b) => a > b ? a : b);
  }
  
  static double _calculateMin(List<double> values) {
    return values.reduce((a, b) => a < b ? a : b);
  }
  
  static double _calculateMedian(List<double> values) {
    final sorted = List<double>.from(values)..sort();
    final middle = sorted.length ~/ 2;
    
    if (sorted.length % 2 == 0) {
      return (sorted[middle - 1] + sorted[middle]) / 2;
    } else {
      return sorted[middle];
    }
  }
  
  static double _calculatePercentile(List<double> values, double percentile) {
    final sorted = List<double>.from(values)..sort();
    final index = (sorted.length * percentile).round() - 1;
    return sorted[index.clamp(0, sorted.length - 1)];
  }
}

/// Developer utilities for common development tasks
class DeveloperUtilities {
  static const ConfigurationValidator validator = ConfigurationValidator();
  static const PerformanceProfiler profiler = PerformanceProfiler();
  static const DiagnosticTool diagnostic = DiagnosticTool();
}

/// Configuration validation utility
class ConfigurationValidator {
  const ConfigurationValidator();
  
  Future<bool> isValid(ARCaptureConfig config) async {
    final capabilities = ARCameraCapabilities();
    if (!capabilities.isSupported) return false;
    
    final validation = await capabilities.validateCaptureConfig(config);
    return validation.isValid;
  }
  
  Future<List<String>> getValidationErrors(ARCaptureConfig config) async {
    final capabilities = ARCameraCapabilities();
    if (!capabilities.isSupported) {
      return ['Platform not supported'];
    }
    
    final validation = await capabilities.validateCaptureConfig(config);
    return validation.errors;
  }
}

/// Performance profiling utility
class PerformanceProfiler {
  const PerformanceProfiler();
  
  Future<Map<String, double>> profileConfiguration(ARCaptureConfig config) async {
    final capabilities = ARCameraCapabilities();
    final impact = await capabilities.assessPerformanceImpact(config);
    
    return {
      'impact_score': _impactToScore(impact),
      'estimated_memory_mb': _estimateMemoryUsage(config),
      'estimated_cpu_percent': _estimateCPUUsage(config),
    };
  }
  
  double _impactToScore(PerformanceImpact impact) {
    switch (impact) {
      case PerformanceImpact.low: return 1.0;
      case PerformanceImpact.medium: return 2.0;
      case PerformanceImpact.high: return 3.0;
      case PerformanceImpact.extreme: return 4.0;
    }
  }
  
  double _estimateMemoryUsage(ARCaptureConfig config) {
    return (config.resolution.totalPixels * 4) / (1024 * 1024); // 4 bytes per pixel
  }
  
  double _estimateCPUUsage(ARCaptureConfig config) {
    final pixelsPerSecond = config.resolution.totalPixels / (config.captureIntervalMs / 1000.0);
    return (pixelsPerSecond / 1000000) * 20; // Rough estimate
  }
}

/// Diagnostic utility
class DiagnosticTool {
  const DiagnosticTool();
  
  Future<Map<String, dynamic>> runQuickDiagnostic() async {
    final capabilities = ARCameraCapabilities();
    
    return {
      'platform_supported': capabilities.isSupported,
      'resolutions_available': capabilities.isSupported 
          ? (await capabilities.getSupportedResolutions()).length 
          : 0,
      'formats_available': capabilities.isSupported 
          ? (await capabilities.getSupportedFormats()).length 
          : 0,
      'memory_available_mb': await CaptureManagerDevTools._getAvailableMemoryMB(),
      'battery_level': await CaptureManagerDevTools._getBatteryLevel(),
    };
  }
}