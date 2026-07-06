/// Types of compatibility warnings
enum WarningType { performance, memory, compatibility, optimization }

/// Severity levels for compatibility warnings  
enum WarningSeverity { low, medium, high, critical }

/// Overall compatibility assessment result
class CompatibilityResult {
  final bool isCompatible;
  final List<String> errors;
  final List<String> warnings;
  final List<String> suggestions;
  final int overallScore; // 0-100 compatibility score

  const CompatibilityResult({
    required this.isCompatible,
    required this.errors,
    required this.warnings,
    required this.suggestions,
    required this.overallScore,
  });

  bool get hasIssues => errors.isNotEmpty || warnings.isNotEmpty;
  bool get hasCriticalIssues => errors.isNotEmpty;
  bool get hasOptimizationOpportunities => suggestions.isNotEmpty;

  String get scoreDescription {
    if (overallScore >= 90) return 'Excellent';
    if (overallScore >= 75) return 'Good';
    if (overallScore >= 50) return 'Acceptable';
    if (overallScore >= 25) return 'Poor';
    return 'Critical Issues';
  }

  /// Create a copy with additional issues
  CompatibilityResult copyWith({
    bool? isCompatible,
    List<String>? errors,
    List<String>? warnings,
    List<String>? suggestions,
    int? overallScore,
  }) {
    return CompatibilityResult(
      isCompatible: isCompatible ?? this.isCompatible,
      errors: errors ?? this.errors,
      warnings: warnings ?? this.warnings,
      suggestions: suggestions ?? this.suggestions,
      overallScore: overallScore ?? this.overallScore,
    );
  }

  /// Merge with another compatibility result
  CompatibilityResult merge(CompatibilityResult other) {
    return CompatibilityResult(
      isCompatible: isCompatible && other.isCompatible,
      errors: [...errors, ...other.errors],
      warnings: [...warnings, ...other.warnings],
      suggestions: [...suggestions, ...other.suggestions],
      overallScore: ((overallScore + other.overallScore) / 2).round(),
    );
  }

  @override
  String toString() {
    return 'CompatibilityResult(compatible: $isCompatible, score: $overallScore, '
           'errors: ${errors.length}, warnings: ${warnings.length}, suggestions: ${suggestions.length})';
  }
}

/// Detailed compatibility warning with context
class CompatibilityWarning {
  final WarningType type;
  final WarningSeverity severity;
  final String message;
  final String suggestion;
  final List<String> affectedComponents;

  const CompatibilityWarning({
    required this.type,
    required this.severity,
    required this.message,
    required this.suggestion,
    required this.affectedComponents,
  });

  /// Get priority score for sorting warnings
  int get priorityScore {
    switch (severity) {
      case WarningSeverity.critical:
        return 4;
      case WarningSeverity.high:
        return 3;
      case WarningSeverity.medium:
        return 2;
      case WarningSeverity.low:
        return 1;
    }
  }

  /// Check if this warning affects a specific component
  bool affectsComponent(String component) {
    return affectedComponents.contains(component);
  }

  @override
  String toString() {
    return 'CompatibilityWarning(${severity.name} ${type.name}: $message)';
  }
}

/// Performance impact assessment
class ConfigurationPerformanceImpact {
  final int performanceScore; // 0-100
  final List<String> impactFactors;
  final List<String> recommendations;
  final double estimatedFrameRateImpact; // Percentage

  const ConfigurationPerformanceImpact({
    required this.performanceScore,
    required this.impactFactors,
    required this.recommendations,
    required this.estimatedFrameRateImpact,
  });

  String get impactLevel {
    if (performanceScore >= 80) return 'Minimal';
    if (performanceScore >= 60) return 'Low';
    if (performanceScore >= 40) return 'Moderate';
    if (performanceScore >= 20) return 'High';
    return 'Severe';
  }

  bool get isAcceptable => performanceScore >= 50;

  @override
  String toString() {
    return 'PerformanceImpact(score: $performanceScore, level: $impactLevel, '
           'frameRateImpact: ${estimatedFrameRateImpact.toStringAsFixed(1)}%)';
  }
}

/// Memory impact assessment
class MemoryImpact {
  final double estimatedMemoryUsageMB;
  final double availableMemoryMB;
  final double memoryPressure; // 0.0-1.0
  final List<String> memoryOptimizations;

  const MemoryImpact({
    required this.estimatedMemoryUsageMB,
    required this.availableMemoryMB,
    required this.memoryPressure,
    required this.memoryOptimizations,
  });

  bool get isMemorySafe => memoryPressure <= 0.7;
  bool get isMemoryCritical => memoryPressure >= 0.9;

  String get pressureLevel {
    if (memoryPressure <= 0.3) return 'Low';
    if (memoryPressure <= 0.6) return 'Moderate';
    if (memoryPressure <= 0.8) return 'High';
    return 'Critical';
  }

  double get usagePercentage => (estimatedMemoryUsageMB / availableMemoryMB) * 100;

  @override
  String toString() {
    return 'MemoryImpact(usage: ${estimatedMemoryUsageMB.toStringAsFixed(1)}MB, '
           'pressure: ${pressureLevel}, safe: $isMemorySafe)';
  }
}

/// Device-specific compatibility assessment
class DeviceCompatibility {
  final String deviceModel;
  final Map<String, bool> featureSupport;
  final List<String> deviceSpecificWarnings;
  final List<String> deviceOptimizations;

  const DeviceCompatibility({
    required this.deviceModel,
    required this.featureSupport,
    required this.deviceSpecificWarnings,
    required this.deviceOptimizations,
  });

  bool isFeatureSupported(String feature) {
    return featureSupport[feature] ?? false;
  }

  bool get hasDeviceSpecificIssues => deviceSpecificWarnings.isNotEmpty;

  @override
  String toString() {
    return 'DeviceCompatibility(device: $deviceModel, '
           'supportedFeatures: ${featureSupport.length}, warnings: ${deviceSpecificWarnings.length})';
  }
}

/// Comprehensive validation context
class ValidationContext {
  final String? deviceModel;
  final Map<String, dynamic>? deviceCapabilities;
  final Map<String, dynamic>? systemInfo;
  final List<String> enabledFeatures;

  const ValidationContext({
    this.deviceModel,
    this.deviceCapabilities,
    this.systemInfo,
    this.enabledFeatures = const [],
  });

  bool isFeatureEnabled(String feature) {
    return enabledFeatures.contains(feature);
  }

  T? getCapability<T>(String capability) {
    return deviceCapabilities?[capability] as T?;
  }

  T? getSystemInfo<T>(String key) {
    return systemInfo?[key] as T?;
  }
}

/// Configuration optimization recommendation
class OptimizationRecommendation {
  final String title;
  final String description;
  final Map<String, dynamic> suggestedChanges;
  final double expectedImprovementPercent;
  final List<String> tradeoffs;

  const OptimizationRecommendation({
    required this.title,
    required this.description,
    required this.suggestedChanges,
    required this.expectedImprovementPercent,
    required this.tradeoffs,
  });

  bool get hasTradeoffs => tradeoffs.isNotEmpty;

  @override
  String toString() {
    return 'OptimizationRecommendation($title: ${expectedImprovementPercent.toStringAsFixed(1)}% improvement)';
  }
}