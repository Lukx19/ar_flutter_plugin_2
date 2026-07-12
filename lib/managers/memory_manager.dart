import '../models/ar_capture_config.dart';

/// Memory management for AR capture operations
@Deprecated(
  'The global ar_flutter_plugin_2/memory channel is not wired. '
  'Use ARCaptureManager.getCaptureCapacity() on the live per-view capture path instead.',
)
class MemoryManager {
  static UnsupportedError _unsupported() {
    return UnsupportedError(
      'MemoryManager uses the deprecated global ar_flutter_plugin_2/memory channel, '
      'which has no native handler in the current capture pipeline. '
      'Use ARCaptureManager.getCaptureCapacity() on the per-view capture channel instead.',
    );
  }

  /// Get memory statistics from native side
  static Future<Map<String, dynamic>> getMemoryStats() async {
    throw _unsupported();
  }

  /// Get recommended configuration for current memory situation
  static Future<ARCaptureConfig> getMemoryOptimizedConfig(
    ARCaptureConfig baseConfig
  ) async {
    throw _unsupported();
  }

  /// Monitor memory usage during capture session
  static Stream<Map<String, dynamic>> monitorMemoryUsage() {
    throw _unsupported();
  }

  /// Get device memory class (low, medium, high)
  static Future<DeviceMemoryClass> getDeviceMemoryClass() async {
    throw _unsupported();
  }

  /// Get optimal configuration based on device memory class
  static Future<ARCaptureConfig> getOptimalConfigForDevice(
    ARCaptureConfig baseConfig
  ) async {
    throw _unsupported();
  }

  /// Check if configuration is safe for current device
  static Future<ConfigurationSafety> checkConfigurationSafety(
    ARCaptureConfig config
  ) async {
    throw _unsupported();
  }

  /// Force garbage collection on native side
  static Future<bool> forceGarbageCollection() async {
    throw _unsupported();
  }

  /// Get detailed memory breakdown
  static Future<MemoryBreakdown> getDetailedMemoryBreakdown() async {
    throw _unsupported();
  }

  /// Cleanup memory caches
  static Future<bool> cleanupMemoryCaches() async {
    throw _unsupported();
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
