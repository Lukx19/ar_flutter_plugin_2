import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../models/ar_capture_config.dart';
import '../models/camera_resolution.dart';
import '../datatypes/image_format.dart';
import '../capabilities/ar_camera_capabilities.dart';
import '../managers/ar_session_manager.dart';

/// Compatibility strategies for resolution
enum CompatibilityStrategy {
  /// Conservative approach - prioritize reliability
  conservative,

  /// Performance approach - prioritize speed and efficiency
  performance,

  /// Balanced approach - balance between reliability and performance
  balanced,
}

/// Severity levels for compatibility issues
enum CompatibilityIssueSeverity {
  info,
  warning,
  error,
  critical,
}

/// Categories of compatibility issues
enum CompatibilityIssueCategory {
  resolution,
  format,
  performance,
  memory,
  platform,
  hardware,
  configuration,
}

/// Detailed compatibility issue information
class CompatibilityIssue {
  final String id;
  final CompatibilityIssueCategory category;
  final CompatibilityIssueSeverity severity;
  final String title;
  final String description;
  final String? suggestedFix;
  final Map<String, dynamic>? context;

  const CompatibilityIssue({
    required this.id,
    required this.category,
    required this.severity,
    required this.title,
    required this.description,
    this.suggestedFix,
    this.context,
  });

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'category': category.name,
      'severity': severity.name,
      'title': title,
      'description': description,
      'suggestedFix': suggestedFix,
      'context': context,
    };
  }

  @override
  String toString() {
    return '${severity.name.toUpperCase()}: $title - $description';
  }
}

/// Comprehensive compatibility result for the legacy checker.
class ConfigurationCompatibilityCheckResult {
  final bool isCompatible;
  final List<CompatibilityIssue> issues;
  final List<String> warnings;
  final Map<String, dynamic> suggestedChanges;
  final double compatibilityScore; // 0.0 to 1.0
  final Map<String, dynamic> deviceInfo;
  final DateTime checkTimestamp;
  final CompatibilityStrategy? recommendedStrategy;

  const ConfigurationCompatibilityCheckResult({
    required this.isCompatible,
    required this.issues,
    required this.warnings,
    required this.suggestedChanges,
    required this.compatibilityScore,
    required this.deviceInfo,
    required this.checkTimestamp,
    this.recommendedStrategy,
  });

  /// Get issues by severity
  List<CompatibilityIssue> getIssuesBySeverity(
      CompatibilityIssueSeverity severity) {
    return issues.where((issue) => issue.severity == severity).toList();
  }

  /// Get issues by category
  List<CompatibilityIssue> getIssuesByCategory(
      CompatibilityIssueCategory category) {
    return issues.where((issue) => issue.category == category).toList();
  }

  /// Check if has issues of specified severity or higher
  bool hasIssuesOfSeverity(CompatibilityIssueSeverity minSeverity) {
    final severityIndex =
        CompatibilityIssueSeverity.values.indexOf(minSeverity);
    return issues.any((issue) =>
        CompatibilityIssueSeverity.values.indexOf(issue.severity) >=
        severityIndex);
  }

  Map<String, dynamic> toMap() {
    return {
      'isCompatible': isCompatible,
      'issues': issues.map((issue) => issue.toMap()).toList(),
      'warnings': warnings,
      'suggestedChanges': suggestedChanges,
      'compatibilityScore': compatibilityScore,
      'deviceInfo': deviceInfo,
      'checkTimestamp': checkTimestamp.millisecondsSinceEpoch,
      'recommendedStrategy': recommendedStrategy?.name,
    };
  }
}

/// Device capability information for compatibility checking
class DeviceCapabilityInfo {
  final String deviceId;
  final String model;
  final String osVersion;
  final List<CameraResolution> supportedResolutions;
  final List<ImageFormat> supportedFormats;
  final int maxMemory;
  final bool hasARSupport;
  final bool hasAdvancedCamera;
  final Map<String, dynamic> additionalCapabilities;

  const DeviceCapabilityInfo({
    required this.deviceId,
    required this.model,
    required this.osVersion,
    required this.supportedResolutions,
    required this.supportedFormats,
    required this.maxMemory,
    required this.hasARSupport,
    required this.hasAdvancedCamera,
    required this.additionalCapabilities,
  });
}

/// Comprehensive configuration compatibility checking system
@Deprecated(
  'ConfigurationCompatibilityChecker is a legacy global surface. '
  'Use ConfigurationCompatibilityValidator and models/compatibility_result.dart instead.',
)
class ConfigurationCompatibilityChecker {
  static ConfigurationCompatibilityChecker? _instance;
  static ConfigurationCompatibilityChecker get instance =>
      _instance ??= ConfigurationCompatibilityChecker._();

  ConfigurationCompatibilityChecker._();

  // Platform channel for device capability queries
  late MethodChannel _channel;
  bool _isInitialized = false;
  bool _debug = false;

  // Cached device capabilities
  DeviceCapabilityInfo? _deviceCapabilities;
  DateTime? _capabilitiesCacheTime;
  static const Duration _cacheValidDuration = Duration(hours: 1);

  // Compatibility rules
  final List<CompatibilityRule> _rules = [];

  /// Initialize the compatibility checker
  Future<void> initialize({bool debug = false}) async {
    if (_isInitialized) return;

    try {
      _debug = debug;
      _channel = const MethodChannel('configuration_compatibility_checker');

      // Load compatibility rules
      await _loadCompatibilityRules();

      // Initialize compatibility matrix
      await _buildCompatibilityMatrix();

      _isInitialized = true;

      if (_debug) {
        debugPrint('ConfigurationCompatibilityChecker initialized');
      }
    } catch (e) {
      throw Exception(
          'Failed to initialize ConfigurationCompatibilityChecker: $e');
    }
  }

  /// Check compatibility between AR and capture configurations
  Future<ConfigurationCompatibilityCheckResult> checkCompatibility({
    required ARConfiguration arConfig,
    required ARCaptureConfig captureConfig,
    String? deviceId,
  }) async {
    if (!_isInitialized) {
      await initialize();
    }

    try {
      if (_debug) {
        debugPrint('Checking configuration compatibility...');
      }

      // Get device capabilities
      final deviceCapabilities = await _getDeviceCapabilities(deviceId);

      // Initialize compatibility checking
      final issues = <CompatibilityIssue>[];
      final warnings = <String>[];
      final suggestedChanges = <String, dynamic>{};

      // Check individual configuration validity
      issues.addAll(
          await _checkARConfigCompatibility(arConfig, deviceCapabilities));
      issues.addAll(await _checkCaptureConfigCompatibility(
          captureConfig, deviceCapabilities));

      // Check cross-configuration compatibility
      issues.addAll(await _checkCrossConfigCompatibility(
          arConfig, captureConfig, deviceCapabilities));

      // Check platform-specific compatibility
      issues.addAll(await _checkPlatformCompatibility(
          arConfig, captureConfig, deviceCapabilities));

      // Check performance implications
      issues.addAll(await _checkPerformanceCompatibility(
          arConfig, captureConfig, deviceCapabilities));

      // Check memory requirements
      issues.addAll(await _checkMemoryCompatibility(
          arConfig, captureConfig, deviceCapabilities));

      // Generate warnings from non-critical issues
      warnings.addAll(_generateWarnings(issues));

      // Generate suggested changes
      suggestedChanges.addAll(await _generateSuggestedChanges(
          arConfig, captureConfig, issues, deviceCapabilities));

      // Calculate compatibility score
      final compatibilityScore = _calculateCompatibilityScore(issues);

      // Determine if compatible (no critical or error issues)
      final isCompatible = !issues.any((issue) =>
          issue.severity == CompatibilityIssueSeverity.critical ||
          issue.severity == CompatibilityIssueSeverity.error);

      // Recommend strategy
      final recommendedStrategy =
          _recommendStrategy(issues, deviceCapabilities);

      final result = ConfigurationCompatibilityCheckResult(
        isCompatible: isCompatible,
        issues: issues,
        warnings: warnings,
        suggestedChanges: suggestedChanges,
        compatibilityScore: compatibilityScore,
        deviceInfo: deviceCapabilities.additionalCapabilities,
        checkTimestamp: DateTime.now(),
        recommendedStrategy: recommendedStrategy,
      );

      if (_debug) {
        debugPrint(
            'Compatibility check completed: ${result.isCompatible ? 'COMPATIBLE' : 'INCOMPATIBLE'}');
        debugPrint('Score: ${result.compatibilityScore.toStringAsFixed(2)}');
        debugPrint('Issues: ${result.issues.length}');
      }

      return result;
    } catch (e) {
      throw Exception('Failed to check compatibility: $e');
    }
  }

  /// Resolve compatibility issues by adjusting configuration
  Future<ARCaptureConfig> resolveCompatibility({
    required ARConfiguration arConfig,
    required ARCaptureConfig captureConfig,
    CompatibilityStrategy strategy = CompatibilityStrategy.conservative,
  }) async {
    if (!_isInitialized) {
      await initialize();
    }

    try {
      if (_debug) {
        debugPrint('Resolving compatibility with ${strategy.name} strategy...');
      }

      // First check current compatibility
      final compatibilityResult = await checkCompatibility(
        arConfig: arConfig,
        captureConfig: captureConfig,
      );

      // If already compatible, return original config
      if (compatibilityResult.isCompatible) {
        if (_debug) {
          debugPrint('Configuration already compatible');
        }
        return captureConfig;
      }

      // Get device capabilities
      final deviceCapabilities = await _getDeviceCapabilities();

      // Create resolved configuration based on strategy
      var resolvedConfig = captureConfig;

      switch (strategy) {
        case CompatibilityStrategy.conservative:
          resolvedConfig = await _resolveConservative(
              arConfig, captureConfig, compatibilityResult, deviceCapabilities);
          break;
        case CompatibilityStrategy.performance:
          resolvedConfig = await _resolvePerformance(
              arConfig, captureConfig, compatibilityResult, deviceCapabilities);
          break;
        case CompatibilityStrategy.balanced:
          resolvedConfig = await _resolveBalanced(
              arConfig, captureConfig, compatibilityResult, deviceCapabilities);
          break;
      }

      // Verify the resolved configuration
      final verificationResult = await checkCompatibility(
        arConfig: arConfig,
        captureConfig: resolvedConfig,
      );

      if (_debug) {
        debugPrint(
            'Resolution completed. New compatibility score: ${verificationResult.compatibilityScore.toStringAsFixed(2)}');
      }

      return resolvedConfig;
    } catch (e) {
      throw Exception('Failed to resolve compatibility: $e');
    }
  }

  /// Get optimal configuration for device
  Future<ARCaptureConfig> getOptimalConfiguration({
    required ARConfiguration arConfig,
    String? deviceId,
    CompatibilityStrategy strategy = CompatibilityStrategy.balanced,
  }) async {
    if (!_isInitialized) {
      await initialize();
    }

    try {
      final deviceCapabilities = await _getDeviceCapabilities(deviceId);

      // Start with a base configuration
      var optimalConfig = ARCaptureConfig(
        resolution: _selectOptimalResolution(deviceCapabilities, strategy),
        format: _selectOptimalFormat(deviceCapabilities, strategy),
        captureIntervalMs: 1000, // 1 second
        maxCacheSize: _selectOptimalCacheSize(deviceCapabilities, strategy),
      );

      // Refine based on AR configuration requirements
      optimalConfig = await _refineForARConfiguration(
          arConfig, optimalConfig, deviceCapabilities, strategy);

      if (_debug) {
        debugPrint(
            'Generated optimal configuration: ${optimalConfig.toString()}');
      }

      return optimalConfig;
    } catch (e) {
      throw Exception('Failed to get optimal configuration: $e');
    }
  }

  /// Check if specific resolution is compatible
  Future<bool> isResolutionCompatible({
    required CameraResolution resolution,
    required ARConfiguration arConfig,
    String? deviceId,
  }) async {
    final deviceCapabilities = await _getDeviceCapabilities(deviceId);

    // Check if resolution is supported by device
    if (!deviceCapabilities.supportedResolutions.contains(resolution)) {
      return false;
    }

    // Check memory requirements
    final memoryRequired =
        _calculateMemoryRequirement(resolution, ImageFormat.jpeg);
    if (memoryRequired > deviceCapabilities.maxMemory * 0.8) {
      // Use max 80% of available memory
      return false;
    }

    // Check performance implications
    if (resolution.totalPixels > 8000000 &&
        !deviceCapabilities.hasAdvancedCamera) {
      // 4K+
      return false;
    }

    return true;
  }

  /// Get device capabilities
  Future<DeviceCapabilityInfo> _getDeviceCapabilities(
      [String? deviceId]) async {
    // Check cache first
    if (_deviceCapabilities != null && _capabilitiesCacheTime != null) {
      final age = DateTime.now().difference(_capabilitiesCacheTime!);
      if (age < _cacheValidDuration) {
        return _deviceCapabilities!;
      }
    }

    try {
      // Get capabilities from platform
      final capabilities = ARCameraCapabilities();
      final supportedResolutions = await capabilities.getSupportedResolutions();
      final supportedFormats = await capabilities.getSupportedFormats();

      // Get device info
      final deviceInfo = await _channel.invokeMethod('getDeviceInfo', {
        'deviceId': deviceId,
      });

      _deviceCapabilities = DeviceCapabilityInfo(
        deviceId: deviceInfo['deviceId'] ?? 'unknown',
        model: deviceInfo['model'] ?? 'unknown',
        osVersion: deviceInfo['osVersion'] ?? 'unknown',
        supportedResolutions: supportedResolutions,
        supportedFormats: supportedFormats,
        maxMemory:
            deviceInfo['maxMemory'] ?? 512 * 1024 * 1024, // Default 512MB
        hasARSupport: deviceInfo['hasARSupport'] ?? false,
        hasAdvancedCamera: deviceInfo['hasAdvancedCamera'] ?? false,
        additionalCapabilities: Map<String, dynamic>.from(
            deviceInfo['additionalCapabilities'] ?? {}),
      );

      _capabilitiesCacheTime = DateTime.now();

      return _deviceCapabilities!;
    } catch (e) {
      // Fallback to basic capabilities
      return DeviceCapabilityInfo(
        deviceId: 'fallback',
        model: 'unknown',
        osVersion: Platform.version,
        supportedResolutions: [
          const CameraResolution(width: 1920, height: 1080)
        ],
        supportedFormats: [ImageFormat.jpeg],
        maxMemory: 256 * 1024 * 1024, // 256MB fallback
        hasARSupport: Platform.isAndroid,
        hasAdvancedCamera: false,
        additionalCapabilities: {},
      );
    }
  }

  /// Check AR configuration compatibility
  Future<List<CompatibilityIssue>> _checkARConfigCompatibility(
      ARConfiguration arConfig, DeviceCapabilityInfo deviceCapabilities) async {
    final issues = <CompatibilityIssue>[];

    // Check AR support
    if (!deviceCapabilities.hasARSupport) {
      issues.add(CompatibilityIssue(
        id: 'ar_not_supported',
        category: CompatibilityIssueCategory.platform,
        severity: CompatibilityIssueSeverity.critical,
        title: 'AR Not Supported',
        description: 'This device does not support AR functionality',
        suggestedFix: 'Use a device with AR support (ARCore for Android)',
      ));
    }

    // Check capture configuration if enabled
    if (arConfig.enableCapture && arConfig.captureConfig == null) {
      issues.add(CompatibilityIssue(
        id: 'capture_config_missing',
        category: CompatibilityIssueCategory.configuration,
        severity: CompatibilityIssueSeverity.error,
        title: 'Missing Capture Configuration',
        description: 'Capture is enabled but no capture configuration provided',
        suggestedFix: 'Provide ARCaptureConfig when enableCapture is true',
      ));
    }

    return issues;
  }

  /// Check capture configuration compatibility
  Future<List<CompatibilityIssue>> _checkCaptureConfigCompatibility(
      ARCaptureConfig captureConfig,
      DeviceCapabilityInfo deviceCapabilities) async {
    final issues = <CompatibilityIssue>[];

    // Check resolution support
    if (!deviceCapabilities.supportedResolutions
        .contains(captureConfig.resolution)) {
      issues.add(CompatibilityIssue(
        id: 'resolution_not_supported',
        category: CompatibilityIssueCategory.resolution,
        severity: CompatibilityIssueSeverity.error,
        title: 'Unsupported Resolution',
        description:
            'Resolution ${captureConfig.resolution} is not supported by this device',
        suggestedFix:
            'Use one of the supported resolutions: ${deviceCapabilities.supportedResolutions.map((r) => r.toString()).join(', ')}',
        context: {
          'requestedResolution': captureConfig.resolution.toString(),
          'supportedResolutions': deviceCapabilities.supportedResolutions
              .map((r) => r.toString())
              .toList(),
        },
      ));
    }

    // Check format support
    if (!deviceCapabilities.supportedFormats.contains(captureConfig.format)) {
      issues.add(CompatibilityIssue(
        id: 'format_not_supported',
        category: CompatibilityIssueCategory.format,
        severity: CompatibilityIssueSeverity.error,
        title: 'Unsupported Format',
        description:
            'Format ${captureConfig.format} is not supported by this device',
        suggestedFix:
            'Use one of the supported formats: ${deviceCapabilities.supportedFormats.map((f) => f.name).join(', ')}',
      ));
    }

    // Check capture interval
    if (captureConfig.captureIntervalMs < 100) {
      issues.add(CompatibilityIssue(
        id: 'capture_interval_too_short',
        category: CompatibilityIssueCategory.performance,
        severity: CompatibilityIssueSeverity.warning,
        title: 'Very Short Capture Interval',
        description:
            'Capture interval of ${captureConfig.captureIntervalMs}ms may impact performance',
        suggestedFix:
            'Consider using intervals >= 500ms for better performance',
      ));
    }

    return issues;
  }

  /// Check cross-configuration compatibility
  Future<List<CompatibilityIssue>> _checkCrossConfigCompatibility(
      ARConfiguration arConfig,
      ARCaptureConfig captureConfig,
      DeviceCapabilityInfo deviceCapabilities) async {
    final issues = <CompatibilityIssue>[];

    // Check if high-frequency capture with AR tracking is sustainable
    if (arConfig.enableCapture && captureConfig.captureIntervalMs < 1000) {
      issues.add(CompatibilityIssue(
        id: 'high_frequency_capture_with_ar',
        category: CompatibilityIssueCategory.performance,
        severity: CompatibilityIssueSeverity.warning,
        title: 'High Frequency Capture with AR',
        description:
            'Frequent capture operations may interfere with AR tracking',
        suggestedFix:
            'Use capture intervals >= 1000ms when AR tracking is active',
      ));
    }

    // Check high resolution capture impact on AR
    if (captureConfig.resolution.totalPixels > 8000000) {
      // 4K+
      issues.add(CompatibilityIssue(
        id: 'high_resolution_ar_impact',
        category: CompatibilityIssueCategory.performance,
        severity: CompatibilityIssueSeverity.warning,
        title: 'High Resolution May Impact AR',
        description:
            'High resolution capture may reduce AR tracking performance',
        suggestedFix:
            'Consider using lower resolution for AR sessions or enable shared camera mode',
      ));
    }

    return issues;
  }

  /// Check platform-specific compatibility
  Future<List<CompatibilityIssue>> _checkPlatformCompatibility(
      ARConfiguration arConfig,
      ARCaptureConfig captureConfig,
      DeviceCapabilityInfo deviceCapabilities) async {
    final issues = <CompatibilityIssue>[];

    // Check platform support
    if (!Platform.isAndroid) {
      issues.add(CompatibilityIssue(
        id: 'platform_not_supported',
        category: CompatibilityIssueCategory.platform,
        severity: CompatibilityIssueSeverity.critical,
        title: 'Platform Not Supported',
        description: 'This platform is not currently supported for AR capture',
        suggestedFix: 'Use Android device for AR capture functionality',
      ));
    }

    return issues;
  }

  /// Check performance compatibility
  Future<List<CompatibilityIssue>> _checkPerformanceCompatibility(
      ARConfiguration arConfig,
      ARCaptureConfig captureConfig,
      DeviceCapabilityInfo deviceCapabilities) async {
    final issues = <CompatibilityIssue>[];

    // Calculate estimated performance load
    double performanceLoad = 0.0;

    if (arConfig.enableCapture) performanceLoad += 0.3;
    if (captureConfig.resolution.totalPixels > 2000000)
      performanceLoad += 0.2; // HD+
    if (captureConfig.captureIntervalMs < 1000) performanceLoad += 0.3;
    if (captureConfig.format == CaptureFormat.rawJpeg) performanceLoad += 0.2;

    if (performanceLoad > 0.8 && !deviceCapabilities.hasAdvancedCamera) {
      issues.add(CompatibilityIssue(
        id: 'high_performance_load',
        category: CompatibilityIssueCategory.performance,
        severity: CompatibilityIssueSeverity.warning,
        title: 'High Performance Load',
        description: 'Configuration may exceed device performance capabilities',
        suggestedFix:
            'Reduce resolution, increase capture interval, or use JPEG format',
        context: {
          'estimatedLoad': performanceLoad,
          'deviceCapable': deviceCapabilities.hasAdvancedCamera,
        },
      ));
    }

    return issues;
  }

  /// Check memory compatibility
  Future<List<CompatibilityIssue>> _checkMemoryCompatibility(
      ARConfiguration arConfig,
      ARCaptureConfig captureConfig,
      DeviceCapabilityInfo deviceCapabilities) async {
    final issues = <CompatibilityIssue>[];

    // Calculate memory requirements
    final memoryRequired = _calculateMemoryRequirement(
        captureConfig.resolution, captureConfig.format);
    final cacheMemory = captureConfig.maxCacheSize;
    final totalMemory = memoryRequired + cacheMemory;

    if (totalMemory > deviceCapabilities.maxMemory * 0.9) {
      // Use max 90% of available memory
      issues.add(CompatibilityIssue(
        id: 'insufficient_memory',
        category: CompatibilityIssueCategory.memory,
        severity: CompatibilityIssueSeverity.error,
        title: 'Insufficient Memory',
        description: 'Configuration requires more memory than available',
        suggestedFix: 'Reduce cache size or use lower resolution',
        context: {
          'requiredMemory': totalMemory,
          'availableMemory': deviceCapabilities.maxMemory,
        },
      ));
    } else if (totalMemory > deviceCapabilities.maxMemory * 0.7) {
      // Warn at 70%
      issues.add(CompatibilityIssue(
        id: 'high_memory_usage',
        category: CompatibilityIssueCategory.memory,
        severity: CompatibilityIssueSeverity.warning,
        title: 'High Memory Usage',
        description:
            'Configuration uses significant memory which may impact performance',
        suggestedFix: 'Consider reducing cache size for better performance',
      ));
    }

    return issues;
  }

  /// Calculate memory requirement for configuration
  int _calculateMemoryRequirement(
      CameraResolution resolution, ImageFormat format) {
    final pixelCount = resolution.width * resolution.height;

    switch (format) {
      case ImageFormat.jpeg:
        return (pixelCount * 0.5).round(); // JPEG compression ~50%
      case ImageFormat.rawJpeg:
        return pixelCount * 4; // RGBA
      // case ImageFormat.yuv420:
      //   return (pixelCount * 1.5).round(); // YUV420 is 1.5 bytes per pixel
    }
  }

  /// Generate warnings from issues
  List<String> _generateWarnings(List<CompatibilityIssue> issues) {
    return issues
        .where((issue) => issue.severity == CompatibilityIssueSeverity.warning)
        .map((issue) => issue.description)
        .toList();
  }

  /// Generate suggested changes
  Future<Map<String, dynamic>> _generateSuggestedChanges(
    ARConfiguration arConfig,
    ARCaptureConfig captureConfig,
    List<CompatibilityIssue> issues,
    DeviceCapabilityInfo deviceCapabilities,
  ) async {
    final suggestions = <String, dynamic>{};

    for (final issue in issues) {
      switch (issue.id) {
        case 'resolution_not_supported':
          suggestions['resolution'] = _selectOptimalResolution(
              deviceCapabilities, CompatibilityStrategy.conservative);
          break;
        case 'format_not_supported':
          suggestions['format'] = _selectOptimalFormat(
              deviceCapabilities, CompatibilityStrategy.conservative);
          break;
        case 'capture_interval_too_short':
          suggestions['captureInterval'] = const Duration(milliseconds: 1000);
          break;
        case 'insufficient_memory':
          suggestions['maxCacheSize'] =
              deviceCapabilities.maxMemory ~/ 4; // Use 25% of memory for cache
          break;
      }
    }

    return suggestions;
  }

  /// Calculate compatibility score
  double _calculateCompatibilityScore(List<CompatibilityIssue> issues) {
    if (issues.isEmpty) return 1.0;

    double score = 1.0;

    for (final issue in issues) {
      switch (issue.severity) {
        case CompatibilityIssueSeverity.critical:
          score -= 0.5;
          break;
        case CompatibilityIssueSeverity.error:
          score -= 0.3;
          break;
        case CompatibilityIssueSeverity.warning:
          score -= 0.1;
          break;
        case CompatibilityIssueSeverity.info:
          score -= 0.05;
          break;
      }
    }

    return (score < 0.0) ? 0.0 : score;
  }

  /// Recommend compatibility strategy
  CompatibilityStrategy _recommendStrategy(
    List<CompatibilityIssue> issues,
    DeviceCapabilityInfo deviceCapabilities,
  ) {
    final hasPerformanceIssues = issues.any(
        (issue) => issue.category == CompatibilityIssueCategory.performance);
    final hasMemoryIssues = issues
        .any((issue) => issue.category == CompatibilityIssueCategory.memory);

    if (hasPerformanceIssues ||
        hasMemoryIssues ||
        !deviceCapabilities.hasAdvancedCamera) {
      return CompatibilityStrategy.conservative;
    } else if (deviceCapabilities.hasAdvancedCamera &&
        deviceCapabilities.maxMemory > 1024 * 1024 * 1024) {
      // 1GB+
      return CompatibilityStrategy.performance;
    } else {
      return CompatibilityStrategy.balanced;
    }
  }

  /// Select optimal resolution based on strategy
  CameraResolution _selectOptimalResolution(
    DeviceCapabilityInfo deviceCapabilities,
    CompatibilityStrategy strategy,
  ) {
    final resolutions = deviceCapabilities.supportedResolutions;
    if (resolutions.isEmpty)
      return const CameraResolution(width: 1920, height: 1080);

    switch (strategy) {
      case CompatibilityStrategy.conservative:
        // Select a mid-range resolution
        return resolutions.firstWhere(
          (r) => r.totalPixels >= 1000000 && r.totalPixels <= 3000000,
          orElse: () => resolutions.first,
        );
      case CompatibilityStrategy.performance:
        // Select highest resolution if device can handle it
        return deviceCapabilities.hasAdvancedCamera
            ? resolutions.last
            : resolutions[resolutions.length ~/ 2];
      case CompatibilityStrategy.balanced:
        // Select middle resolution
        return resolutions[resolutions.length ~/ 2];
    }
  }

  /// Select optimal format based on strategy
  ImageFormat _selectOptimalFormat(
    DeviceCapabilityInfo deviceCapabilities,
    CompatibilityStrategy strategy,
  ) {
    final formats = deviceCapabilities.supportedFormats;
    if (formats.isEmpty) return ImageFormat.jpeg;

    switch (strategy) {
      case CompatibilityStrategy.conservative:
        return ImageFormat.jpeg; // Most compatible
      case CompatibilityStrategy.performance:
        return formats.contains(CaptureFormat.rawJpeg) &&
                deviceCapabilities.hasAdvancedCamera
            ? CaptureFormat.rawJpeg
            : ImageFormat.jpeg;
      case CompatibilityStrategy.balanced:
        return ImageFormat.jpeg; // Good balance of quality and performance
    }
  }

  /// Select optimal cache size based on strategy
  int _selectOptimalCacheSize(
    DeviceCapabilityInfo deviceCapabilities,
    CompatibilityStrategy strategy,
  ) {
    final maxMemory = deviceCapabilities.maxMemory;

    switch (strategy) {
      case CompatibilityStrategy.conservative:
        return maxMemory ~/ 8; // Use 12.5% of memory
      case CompatibilityStrategy.performance:
        return maxMemory ~/ 4; // Use 25% of memory
      case CompatibilityStrategy.balanced:
        return maxMemory ~/ 6; // Use ~16.7% of memory
    }
  }

  /// Resolve configuration using conservative strategy
  Future<ARCaptureConfig> _resolveConservative(
    ARConfiguration arConfig,
    ARCaptureConfig captureConfig,
    ConfigurationCompatibilityCheckResult compatibilityResult,
    DeviceCapabilityInfo deviceCapabilities,
  ) async {
    return ARCaptureConfig(
      resolution: _selectOptimalResolution(
          deviceCapabilities, CompatibilityStrategy.conservative),
      format: ImageFormat.jpeg,
      captureIntervalMs: 2000, // 2 seconds
      maxCacheSize: _selectOptimalCacheSize(
          deviceCapabilities, CompatibilityStrategy.conservative),
    );
  }

  /// Resolve configuration using performance strategy
  Future<ARCaptureConfig> _resolvePerformance(
    ARConfiguration arConfig,
    ARCaptureConfig captureConfig,
    ConfigurationCompatibilityCheckResult compatibilityResult,
    DeviceCapabilityInfo deviceCapabilities,
  ) async {
    return ARCaptureConfig(
      resolution: _selectOptimalResolution(
          deviceCapabilities, CompatibilityStrategy.performance),
      format: _selectOptimalFormat(
          deviceCapabilities, CompatibilityStrategy.performance),
      captureIntervalMs: 500, // 500ms
      maxCacheSize: _selectOptimalCacheSize(
          deviceCapabilities, CompatibilityStrategy.performance),
    );
  }

  /// Resolve configuration using balanced strategy
  Future<ARCaptureConfig> _resolveBalanced(
    ARConfiguration arConfig,
    ARCaptureConfig captureConfig,
    ConfigurationCompatibilityCheckResult compatibilityResult,
    DeviceCapabilityInfo deviceCapabilities,
  ) async {
    return ARCaptureConfig(
      resolution: _selectOptimalResolution(
          deviceCapabilities, CompatibilityStrategy.balanced),
      format: ImageFormat.jpeg,
      captureIntervalMs: 1000, // 1 second
      maxCacheSize: _selectOptimalCacheSize(
          deviceCapabilities, CompatibilityStrategy.balanced),
    );
  }

  /// Refine configuration for AR requirements
  Future<ARCaptureConfig> _refineForARConfiguration(
    ARConfiguration arConfig,
    ARCaptureConfig baseConfig,
    DeviceCapabilityInfo deviceCapabilities,
    CompatibilityStrategy strategy,
  ) async {
    var refinedConfig = baseConfig;

    // Adjust for AR-specific requirements
    if (arConfig.enableCapture) {
      // Increase capture interval for AR stability
      if (baseConfig.captureIntervalMs < 1000) {
        refinedConfig = ARCaptureConfig(
          resolution: refinedConfig.resolution,
          format: refinedConfig.format,
          captureIntervalMs: 1000, // 1 second
          maxCacheSize: refinedConfig.maxCacheSize,
        );
      }
    }

    return refinedConfig;
  }

  /// Load compatibility rules
  Future<void> _loadCompatibilityRules() async {
    // Load predefined compatibility rules
    _rules.addAll([
      // Add compatibility rules here
    ]);
  }

  /// Build compatibility matrix
  Future<void> _buildCompatibilityMatrix() async {
    // Build matrix mapping configurations to compatibility
    // This would typically be loaded from a configuration file or database
  }

  /// Dispose the compatibility checker
  Future<void> dispose() async {
    _instance = null;
    _isInitialized = false;
    _deviceCapabilities = null;
    _capabilitiesCacheTime = null;
  }
}

/// Compatibility rule for automated checking
class CompatibilityRule {
  final String id;
  final String description;
  final bool Function(ARConfiguration, ARCaptureConfig, DeviceCapabilityInfo)
      check;
  final CompatibilityIssue Function(
      ARConfiguration, ARCaptureConfig, DeviceCapabilityInfo) createIssue;

  const CompatibilityRule({
    required this.id,
    required this.description,
    required this.check,
    required this.createIssue,
  });
}
