import 'package:flutter/services.dart';
import '../models/ar_capture_config.dart';
import '../datatypes/buffer_strategy.dart';

/// Memory management for AR capture operations
class MemoryManager {
  static const MethodChannel _channel = MethodChannel('ar_flutter_plugin_2/memory');
  static const EventChannel _eventChannel = EventChannel('ar_flutter_plugin_2/memory_events');

  /// Get memory statistics from native side
  static Future<Map<String, dynamic>> getMemoryStats() async {
    try {
      final result = await _channel.invokeMethod('getMemoryStats');
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (e) {
      throw MemoryManagerException('Failed to get memory stats: ${e.message}');
    }
  }

  /// Get recommended configuration for current memory situation
  static Future<ARCaptureConfig> getMemoryOptimizedConfig(
    ARCaptureConfig baseConfig
  ) async {
    try {
      final memStats = await getMemoryStats();
      final availableMemoryMB = memStats['availableMemoryMB'] as int? ?? 1024;

      if (availableMemoryMB < 512) {
        // Very low memory device
        return baseConfig.copyWith(
          maxCacheSize: 3,
          jpegQuality: 60,
          bufferStrategy: BufferStrategy.memory,
        );
      } else if (availableMemoryMB < 1024) {
        // Low memory device
        return baseConfig.copyWith(
          maxCacheSize: 5,
          jpegQuality: 70,
          bufferStrategy: BufferStrategy.memory,
        );
      } else {
        // Sufficient memory
        return baseConfig;
      }
    } catch (e) {
      // Return conservative config on error
      return baseConfig.copyWith(
        maxCacheSize: 5,
        jpegQuality: 70,
        bufferStrategy: BufferStrategy.memory,
      );
    }
  }

  /// Monitor memory usage during capture session
  static Stream<Map<String, dynamic>> monitorMemoryUsage() {
    return _eventChannel.receiveBroadcastStream().map((event) {
      return Map<String, dynamic>.from(event);
    });
  }

  /// Get device memory class (low, medium, high)
  static Future<DeviceMemoryClass> getDeviceMemoryClass() async {
    try {
      final memStats = await getMemoryStats();
      final totalMemoryMB = memStats['totalMemoryMB'] as int? ?? 0;
      final availableMemoryMB = memStats['availableMemoryMB'] as int? ?? 0;

      if (totalMemoryMB < 2048 || availableMemoryMB < 512) {
        return DeviceMemoryClass.low;
      } else if (totalMemoryMB < 6144 || availableMemoryMB < 1536) {
        return DeviceMemoryClass.medium;
      } else {
        return DeviceMemoryClass.high;
      }
    } catch (e) {
      return DeviceMemoryClass.low; // Conservative fallback
    }
  }

  /// Get optimal configuration based on device memory class
  static Future<ARCaptureConfig> getOptimalConfigForDevice(
    ARCaptureConfig baseConfig
  ) async {
    final memoryClass = await getDeviceMemoryClass();
    
    switch (memoryClass) {
      case DeviceMemoryClass.low:
        return ARCaptureConfig.memoryOptimized(
          resolution: baseConfig.resolution,
          format: baseConfig.format,
          captureIntervalMs: 8000, // Less frequent captures
        );
      
      case DeviceMemoryClass.medium:
        return baseConfig.copyWith(
          maxCacheSize: 8,
          jpegQuality: 80,
          bufferStrategy: BufferStrategy.balanced,
        );
      
      case DeviceMemoryClass.high:
        return ARCaptureConfig.performance(
          resolution: baseConfig.resolution,
          format: baseConfig.format,
          captureIntervalMs: baseConfig.captureIntervalMs,
        );
    }
  }

  /// Check if configuration is safe for current device
  static Future<ConfigurationSafety> checkConfigurationSafety(
    ARCaptureConfig config
  ) async {
    try {
      final memStats = await getMemoryStats();
      final availableMemoryMB = memStats['availableMemoryMB'] as int? ?? 1024;
      final estimatedUsageMB = config.estimatedMemoryUsageMB;

      if (estimatedUsageMB > availableMemoryMB * 0.8) {
        return ConfigurationSafety(
          isSafe: false,
          riskLevel: MemoryRiskLevel.critical,
          message: 'Configuration requires ${estimatedUsageMB.toStringAsFixed(1)}MB but only ${availableMemoryMB}MB available',
          recommendation: 'Reduce cache size or resolution',
        );
      } else if (estimatedUsageMB > availableMemoryMB * 0.5) {
        return ConfigurationSafety(
          isSafe: true,
          riskLevel: MemoryRiskLevel.high,
          message: 'Configuration will use significant memory (${estimatedUsageMB.toStringAsFixed(1)}MB)',
          recommendation: 'Monitor memory usage carefully',
        );
      } else if (estimatedUsageMB > availableMemoryMB * 0.2) {
        return ConfigurationSafety(
          isSafe: true,
          riskLevel: MemoryRiskLevel.medium,
          message: 'Configuration should work well (${estimatedUsageMB.toStringAsFixed(1)}MB)',
          recommendation: null,
        );
      } else {
        return ConfigurationSafety(
          isSafe: true,
          riskLevel: MemoryRiskLevel.low,
          message: 'Configuration is very safe (${estimatedUsageMB.toStringAsFixed(1)}MB)',
          recommendation: null,
        );
      }
    } catch (e) {
      return ConfigurationSafety(
        isSafe: false,
        riskLevel: MemoryRiskLevel.unknown,
        message: 'Unable to assess configuration safety',
        recommendation: 'Use conservative settings',
      );
    }
  }

  /// Force garbage collection on native side
  static Future<bool> forceGarbageCollection() async {
    try {
      return await _channel.invokeMethod('forceGarbageCollection');
    } on PlatformException catch (e) {
      throw MemoryManagerException('Failed to force garbage collection: ${e.message}');
    }
  }

  /// Get detailed memory breakdown
  static Future<MemoryBreakdown> getDetailedMemoryBreakdown() async {
    try {
      final result = await _channel.invokeMethod('getDetailedMemoryBreakdown');
      return MemoryBreakdown.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw MemoryManagerException('Failed to get memory breakdown: ${e.message}');
    }
  }

  /// Cleanup memory caches
  static Future<bool> cleanupMemoryCaches() async {
    try {
      return await _channel.invokeMethod('cleanupMemoryCaches');
    } on PlatformException catch (e) {
      throw MemoryManagerException('Failed to cleanup memory caches: ${e.message}');
    }
  }
}

/// Device memory classification
enum DeviceMemoryClass {
  low,    // < 2GB total, < 512MB available
  medium, // 2-6GB total, 512MB-1.5GB available  
  high,   // > 6GB total, > 1.5GB available
}

/// Memory risk levels for configurations
enum MemoryRiskLevel {
  low,      // < 20% of available memory
  medium,   // 20-50% of available memory
  high,     // 50-80% of available memory
  critical, // > 80% of available memory
  unknown,  // Unable to determine
}

/// Configuration safety assessment
class ConfigurationSafety {
  final bool isSafe;
  final MemoryRiskLevel riskLevel;
  final String message;
  final String? recommendation;

  const ConfigurationSafety({
    required this.isSafe,
    required this.riskLevel,
    required this.message,
    this.recommendation,
  });

  @override
  String toString() {
    return 'ConfigurationSafety(safe: $isSafe, risk: $riskLevel, message: $message)';
  }
}

/// Detailed memory usage breakdown
class MemoryBreakdown {
  final int totalMemoryMB;
  final int availableMemoryMB;
  final int usedMemoryMB;
  final int imageCacheMemoryMB;
  final int systemMemoryMB;
  final int otherMemoryMB;
  final double memoryPressure; // 0.0 - 1.0

  const MemoryBreakdown({
    required this.totalMemoryMB,
    required this.availableMemoryMB,
    required this.usedMemoryMB,
    required this.imageCacheMemoryMB,
    required this.systemMemoryMB,
    required this.otherMemoryMB,
    required this.memoryPressure,
  });

  factory MemoryBreakdown.fromMap(Map<String, dynamic> map) {
    return MemoryBreakdown(
      totalMemoryMB: map['totalMemoryMB'] as int? ?? 0,
      availableMemoryMB: map['availableMemoryMB'] as int? ?? 0,
      usedMemoryMB: map['usedMemoryMB'] as int? ?? 0,
      imageCacheMemoryMB: map['imageCacheMemoryMB'] as int? ?? 0,
      systemMemoryMB: map['systemMemoryMB'] as int? ?? 0,
      otherMemoryMB: map['otherMemoryMB'] as int? ?? 0,
      memoryPressure: (map['memoryPressure'] as num?)?.toDouble() ?? 0.0,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'totalMemoryMB': totalMemoryMB,
      'availableMemoryMB': availableMemoryMB,
      'usedMemoryMB': usedMemoryMB,
      'imageCacheMemoryMB': imageCacheMemoryMB,
      'systemMemoryMB': systemMemoryMB,
      'otherMemoryMB': otherMemoryMB,
      'memoryPressure': memoryPressure,
    };
  }

  /// Get memory usage percentage
  double get usagePercentage => totalMemoryMB > 0 ? (usedMemoryMB / totalMemoryMB) * 100.0 : 0.0;

  /// Check if memory pressure is high
  bool get isHighPressure => memoryPressure > 0.8;

  /// Check if memory pressure is critical
  bool get isCriticalPressure => memoryPressure > 0.95;

  @override
  String toString() {
    return 'MemoryBreakdown(total: ${totalMemoryMB}MB, available: ${availableMemoryMB}MB, '
           'cache: ${imageCacheMemoryMB}MB, pressure: ${(memoryPressure * 100).toStringAsFixed(1)}%)';
  }
}

/// Exception thrown by memory manager
class MemoryManagerException implements Exception {
  final String message;
  const MemoryManagerException(this.message);
  
  @override
  String toString() => 'MemoryManagerException: $message';
}