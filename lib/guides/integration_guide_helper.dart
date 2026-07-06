import 'dart:io';
import 'package:flutter/material.dart';
import '../ar_flutter_plugin.dart';
import '../capabilities/ar_camera_capabilities.dart';
import '../models/ar_capture_config.dart';
import '../models/camera_resolution.dart';

/// Integration step definition
class IntegrationStep {
  final String title;
  final String description;
  final List<String> codeExamples;
  final List<String> warnings;
  final List<String> notes;
  final String? platformNote;
  
  const IntegrationStep({
    required this.title,
    required this.description,
    required this.codeExamples,
    this.warnings = const [],
    this.notes = const [],
    this.platformNote,
  });
}

/// Best practice definition
class BestPractice {
  final String title;
  final String description;
  final String category;
  final List<String> examples;
  final String priority; // 'high', 'medium', 'low'
  
  const BestPractice({
    required this.title,
    required this.description,
    required this.category,
    required this.examples,
    required this.priority,
  });
}

/// Integration guide utilities and helpers
/// 
/// This class provides comprehensive integration guidance including
/// step-by-step integration processes, common integration patterns,
/// troubleshooting information, and best practices for production deployment.
class IntegrationGuideHelper {
  /// Get basic integration steps for new projects
  static List<IntegrationStep> getBasicIntegrationSteps() {
    return [
      IntegrationStep(
        title: 'Add Dependencies',
        description: 'Add the AR Flutter Plugin to your project dependencies',
        codeExamples: [
          '''
# Add to pubspec.yaml
dependencies:
  flutter:
    sdk: flutter
  ar_flutter_plugin_2: ^1.0.0
  
# For camera capabilities
  permission_handler: ^11.0.1
  device_info_plus: ^10.1.0''',
        ],
        warnings: [
          'Ensure you are using compatible Flutter and Dart versions',
          'Check platform-specific requirements before adding dependencies',
        ],
        notes: [
          'The plugin requires minimum Flutter 3.0.0',
          'Android API level 24+ is required for full functionality',
        ],
      ),
      
      IntegrationStep(
        title: 'Configure Platform Permissions',
        description: 'Set up required permissions for camera and storage access',
        codeExamples: [
          '''
<!-- Android: Add to android/app/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-feature 
    android:name="android.hardware.camera" 
    android:required="true" />
<uses-feature 
    android:name="android.hardware.camera.autofocus" 
    android:required="false" />

<!-- For ARCore support -->
<meta-data 
    android:name="com.google.ar.core" 
    android:value="required" />''',
          '''
<!-- iOS: Add to ios/Runner/Info.plist -->
<key>NSCameraUsageDescription</key>
<string>This app needs camera access for AR capture functionality</string>

<key>UIRequiredDeviceCapabilities</key>
<array>
    <string>armv7</string>
    <string>arkit</string>
</array>''',
        ],
        warnings: [
          'Camera permission is required for all capture functionality',
          'ARCore/ARKit support may not be available on all devices',
        ],
        platformNote: 'iOS requires ARKit, Android requires ARCore for full AR functionality',
      ),
      
      IntegrationStep(
        title: 'Import Required Packages',
        description: 'Import the necessary packages in your Dart files',
        codeExamples: [
          '''
import 'package:ar_flutter_plugin_2/ar_flutter_plugin.dart';
import 'package:ar_flutter_plugin_2/capabilities/ar_camera_capabilities.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:permission_handler/permission_handler.dart';''',
        ],
        notes: [
          'Only import what you need to reduce app size',
          'Camera capabilities can be used independently of AR features',
        ],
      ),
      
      IntegrationStep(
        title: 'Initialize Camera Capabilities',
        description: 'Set up camera capabilities discovery and validation',
        codeExamples: [
          '''
class CameraSetup {
  late ARCameraCapabilities _capabilities;
  
  Future<void> initialize() async {
    // Request camera permission
    final permission = await Permission.camera.request();
    if (permission != PermissionStatus.granted) {
      throw Exception('Camera permission required');
    }
    
    // Initialize capabilities
    _capabilities = ARCameraCapabilities();
    
    // Check platform support
    if (!_capabilities.isSupported) {
      throw Exception('Camera capabilities not supported on this platform');
    }
    
    print('Camera capabilities initialized successfully');
  }
  
  Future<List<CameraResolution>> getAvailableResolutions() async {
    return await _capabilities.getSupportedResolutions();
  }
}''',
        ],
        warnings: [
          'Always check platform support before using capabilities',
          'Handle permission denial gracefully',
        ],
      ),
      
      IntegrationStep(
        title: 'Create and Validate Configuration',
        description: 'Set up capture configuration with proper validation',
        codeExamples: [
          '''
class ConfigurationManager {
  final ARCameraCapabilities _capabilities;
  
  ConfigurationManager(this._capabilities);
  
  Future<ARCaptureConfig> createValidatedConfig() async {
    // Get supported capabilities
    final resolutions = await _capabilities.getSupportedResolutions();
    final formats = await _capabilities.getSupportedFormats();
    
    if (resolutions.isEmpty) {
      throw Exception('No supported resolutions available');
    }
    
    // Create configuration
    final config = ARCaptureConfig(
      resolution: resolutions.first,
      format: formats.isNotEmpty ? formats.first : ImageFormat.jpeg,
      enableHighResCapture: true,
      captureIntervalMs: 1000,
    );
    
    // Validate configuration
    final validation = await _capabilities.validateCaptureConfig(config);
    
    if (!validation.isValid) {
      print('Configuration validation failed: \${validation.errors}');
      
      // Use suggested configuration if available
      if (validation.suggestedConfig != null) {
        return validation.suggestedConfig!;
      }
      
      // Fall back to recommended configuration
      return await _capabilities.getRecommendedConfig();
    }
    
    return config;
  }
}''',
        ],
        warnings: [
          'Always validate configurations before use',
          'Handle validation failures with appropriate fallbacks',
        ],
        notes: [
          'Suggested configurations are optimized for the device',
          'Recommended configurations provide safe defaults',
        ],
      ),
      
      IntegrationStep(
        title: 'Implement Error Handling',
        description: 'Add comprehensive error handling for robust operation',
        codeExamples: [
          '''
class RobustCaptureManager {
  Future<void> initializeWithErrorHandling() async {
    try {
      await _initializeCapture();
    } on ARCameraCapabilityException catch (e) {
      // Handle camera capability specific errors
      _handleCapabilityError(e);
    } on PermissionException catch (e) {
      // Handle permission errors
      _handlePermissionError(e);
    } catch (e) {
      // Handle unexpected errors
      _handleUnexpectedError(e);
    }
  }
  
  void _handleCapabilityError(ARCameraCapabilityException e) {
    print('Camera capability error: \${e.message}');
    // Show user-friendly message
    // Attempt fallback initialization
  }
  
  void _handlePermissionError(Exception e) {
    print('Permission error: \$e');
    // Guide user to grant permissions
    // Show permission request dialog
  }
  
  void _handleUnexpectedError(dynamic e) {
    print('Unexpected error: \$e');
    // Log error for debugging
    // Show generic error message
  }
}''',
        ],
        warnings: [
          'Never ignore exceptions - always handle appropriately',
          'Provide meaningful error messages to users',
        ],
      ),
    ];
  }
  
  /// Get advanced integration steps for complex scenarios
  static List<IntegrationStep> getAdvancedIntegrationSteps() {
    return [
      IntegrationStep(
        title: 'Implement Custom Configuration Management',
        description: 'Create advanced configuration management with device optimization',
        codeExamples: [
          '''
class AdvancedConfigurationManager {
  final ARCameraCapabilities _capabilities;
  final Map<String, ARCaptureConfig> _presetConfigs = {};
  
  AdvancedConfigurationManager(this._capabilities);
  
  Future<void> initializePresets() async {
    // Create device-optimized presets
    _presetConfigs['high_quality'] = await _createHighQualityPreset();
    _presetConfigs['balanced'] = await _createBalancedPreset();
    _presetConfigs['performance'] = await _createPerformancePreset();
    _presetConfigs['battery_saver'] = await _createBatterySaverPreset();
  }
  
  Future<ARCaptureConfig> _createHighQualityPreset() async {
    final resolutions = await _capabilities.getSupportedResolutions();
    resolutions.sort((a, b) => b.totalPixels.compareTo(a.totalPixels));
    
    return ARCaptureConfig(
      resolution: resolutions.first, // Highest resolution
      format: ImageFormat.jpeg,
      enableHighResCapture: true,
      captureIntervalMs: 2000, // Slower for stability
    );
  }
  
  Future<ARCaptureConfig> getOptimalConfigForContext(CaptureContext context) async {
    // Analyze context and device capabilities
    final devicePerformance = await _assessDevicePerformance();
    final batteryLevel = await _getBatteryLevel();
    final memoryAvailable = await _getAvailableMemory();
    
    // Select configuration based on context and device state
    if (context.requiresHighQuality && devicePerformance.isHigh) {
      return _presetConfigs['high_quality']!;
    } else if (batteryLevel < 0.2) {
      return _presetConfigs['battery_saver']!;
    } else if (memoryAvailable < 512) {
      return _presetConfigs['performance']!;
    } else {
      return _presetConfigs['balanced']!;
    }
  }
}

class CaptureContext {
  final bool requiresHighQuality;
  final bool isRealTime;
  final bool isBatteryConstrained;
  
  const CaptureContext({
    this.requiresHighQuality = false,
    this.isRealTime = false,
    this.isBatteryConstrained = false,
  });
}''',
        ],
        notes: [
          'Advanced configuration management adapts to device state',
          'Context-aware configuration selection improves user experience',
        ],
      ),
      
      IntegrationStep(
        title: 'Implement Performance Monitoring',
        description: 'Add real-time performance monitoring and optimization',
        codeExamples: [
          '''
class PerformanceMonitor {
  final List<double> _captureLatencies = [];
  final List<double> _memoryUsages = [];
  DateTime? _lastCaptureTime;
  
  void recordCaptureLatency(Duration latency) {
    _captureLatencies.add(latency.inMilliseconds.toDouble());
    
    // Keep only recent measurements
    if (_captureLatencies.length > 100) {
      _captureLatencies.removeRange(0, _captureLatencies.length - 100);
    }
  }
  
  void recordMemoryUsage(double memoryMB) {
    _memoryUsages.add(memoryMB);
    
    if (_memoryUsages.length > 50) {
      _memoryUsages.removeRange(0, _memoryUsages.length - 50);
    }
  }
  
  PerformanceMetrics getMetrics() {
    return PerformanceMetrics(
      averageLatency: _captureLatencies.isNotEmpty 
          ? _captureLatencies.reduce((a, b) => a + b) / _captureLatencies.length 
          : 0.0,
      peakMemoryUsage: _memoryUsages.isNotEmpty 
          ? _memoryUsages.reduce((a, b) => a > b ? a : b) 
          : 0.0,
      currentFPS: _calculateCurrentFPS(),
    );
  }
  
  double _calculateCurrentFPS() {
    if (_lastCaptureTime == null) return 0.0;
    
    final now = DateTime.now();
    final timeDiff = now.difference(_lastCaptureTime!);
    return 1000.0 / timeDiff.inMilliseconds;
  }
  
  bool shouldOptimizeConfiguration(PerformanceMetrics metrics) {
    return metrics.averageLatency > 200.0 || // >200ms latency
           metrics.peakMemoryUsage > 1024.0 || // >1GB memory
           metrics.currentFPS < 10.0; // <10 FPS
  }
}

class PerformanceMetrics {
  final double averageLatency;
  final double peakMemoryUsage;
  final double currentFPS;
  
  const PerformanceMetrics({
    required this.averageLatency,
    required this.peakMemoryUsage,
    required this.currentFPS,
  });
}''',
        ],
        warnings: [
          'Performance monitoring adds overhead - use judiciously',
          'Set appropriate thresholds for your use case',
        ],
      ),
      
      IntegrationStep(
        title: 'Implement State Management Integration',
        description: 'Integrate with state management solutions (Riverpod, Bloc, etc.)',
        codeExamples: [
          '''
// Riverpod integration example
import 'package:flutter_riverpod/flutter_riverpod.dart';

final cameraCapabilitiesProvider = Provider<ARCameraCapabilities>((ref) {
  return ARCameraCapabilities();
});

final captureConfigProvider = FutureProvider<ARCaptureConfig>((ref) async {
  final capabilities = ref.watch(cameraCapabilitiesProvider);
  
  if (!capabilities.isSupported) {
    throw Exception('Platform not supported');
  }
  
  return await capabilities.getRecommendedConfig();
});

final captureStateProvider = StateNotifierProvider<CaptureStateNotifier, CaptureState>((ref) {
  final capabilities = ref.watch(cameraCapabilitiesProvider);
  return CaptureStateNotifier(capabilities);
});

class CaptureStateNotifier extends StateNotifier<CaptureState> {
  final ARCameraCapabilities _capabilities;
  
  CaptureStateNotifier(this._capabilities) : super(const CaptureState.idle());
  
  Future<void> initializeCapture() async {
    state = const CaptureState.initializing();
    
    try {
      final config = await _capabilities.getRecommendedConfig();
      final validation = await _capabilities.validateCaptureConfig(config);
      
      if (validation.isValid) {
        state = CaptureState.ready(config);
      } else {
        state = CaptureState.error('Configuration validation failed');
      }
    } catch (e) {
      state = CaptureState.error(e.toString());
    }
  }
}

@freezed
class CaptureState with _\$CaptureState {
  const factory CaptureState.idle() = _Idle;
  const factory CaptureState.initializing() = _Initializing;
  const factory CaptureState.ready(ARCaptureConfig config) = _Ready;
  const factory CaptureState.capturing() = _Capturing;
  const factory CaptureState.error(String message) = _Error;
}''',
        ],
        notes: [
          'State management integration provides reactive UI updates',
          'Choose the state management solution that fits your app architecture',
        ],
      ),
    ];
  }
  
  /// Get common issue resolutions
  static Map<String, String> getCommonIssueResolutions() {
    return {
      'Platform not supported': '''
This error occurs when trying to use camera capabilities on a non-Android platform.

Resolution:
1. Check Platform.isAndroid before using capabilities
2. Implement platform-specific fallbacks
3. Show appropriate UI messages for unsupported platforms

Example:
```dart
if (Platform.isAndroid && capabilities.isSupported) {
  // Use full functionality
} else {
  // Show limited functionality or error message
}
```''',
      
      'Camera permission denied': '''
Camera permission is required for all capture functionality.

Resolution:
1. Request permission using permission_handler
2. Handle permission denial gracefully
3. Guide user to app settings if permission is permanently denied

Example:
```dart
final permission = await Permission.camera.request();
if (permission == PermissionStatus.denied) {
  // Show explanation and request again
} else if (permission == PermissionStatus.permanentlyDenied) {
  // Guide to settings
  await openAppSettings();
}
```''',
      
      'No supported resolutions': '''
This occurs when the device camera doesn't support any standard resolutions.

Resolution:
1. Check if any resolutions are returned
2. Implement fallback resolutions
3. Use getRecommendedConfig() for safe defaults

Example:
```dart
final resolutions = await capabilities.getSupportedResolutions();
if (resolutions.isEmpty) {
  // Use fallback or recommended configuration
  final config = await capabilities.getRecommendedConfig();
}
```''',
      
      'Configuration validation failed': '''
Configuration validation can fail for various reasons.

Resolution:
1. Check validation.errors for specific issues
2. Use suggested configuration if available
3. Fall back to recommended configuration
4. Implement progressive fallbacks

Example:
```dart
final validation = await capabilities.validateCaptureConfig(config);
if (!validation.isValid) {
  if (validation.suggestedConfig != null) {
    config = validation.suggestedConfig!;
  } else {
    config = await capabilities.getRecommendedConfig();
  }
}
```''',
      
      'High performance impact': '''
Configuration may cause performance issues on the device.

Resolution:
1. Use assessPerformanceImpact() to check impact level
2. Optimize configuration for better performance
3. Consider device-specific adjustments

Example:
```dart
final impact = await capabilities.assessPerformanceImpact(config);
if (impact == PerformanceImpact.high) {
  // Reduce resolution or increase capture interval
  config = config.copyWith(
    resolution: lowerResolution,
    captureIntervalMs: config.captureIntervalMs * 2,
  );
}
```''',
      
      'Memory issues during capture': '''
Memory problems can occur with high-resolution capture or frequent captures.

Resolution:
1. Monitor memory usage during capture
2. Implement memory cleanup strategies
3. Adjust configuration based on available memory

Example:
```dart
// Cleanup strategy
if (captureCount % 10 == 0) {
  // Clean up old captures
  await cleanupOldCaptures();
  
  // Force garbage collection if needed
  System.gc(); // Android specific
}
```''',
      
      'ARCore not available': '''
ARCore may not be available on all Android devices.

Resolution:
1. Check ARCore availability before using AR features
2. Implement fallback for non-ARCore devices
3. Gracefully degrade functionality

Example:
```dart
final arCoreAvailable = await ArCoreAvailability.check();
if (arCoreAvailable == ArCoreAvailability.supported) {
  // Use full AR functionality
} else {
  // Use camera-only functionality
}
```''',
    };
  }
  
  /// Get best practices for production deployment
  static List<BestPractice> getBestPractices() {
    return [
      BestPractice(
        title: 'Always Validate Configurations',
        description: 'Validate all capture configurations before use to prevent runtime failures',
        category: 'Configuration Management',
        priority: 'high',
        examples: [
          '''
// Good: Always validate
final validation = await capabilities.validateCaptureConfig(config);
if (validation.isValid) {
  await startCapture(config);
} else {
  handleValidationErrors(validation.errors);
}''',
          '''
// Bad: Using configuration without validation
await startCapture(config); // May fail at runtime
''',
        ],
      ),
      
      BestPractice(
        title: 'Implement Comprehensive Error Handling',
        description: 'Handle all potential errors gracefully with appropriate user feedback',
        category: 'Error Handling',
        priority: 'high',
        examples: [
          '''
// Good: Comprehensive error handling
try {
  await initializeCapture();
} on ARCameraCapabilityException catch (e) {
  showUserFriendlyError('Camera setup failed');
  logError(e);
} on PermissionException catch (e) {
  requestPermissions();
} catch (e) {
  showGenericError();
  logError(e);
}''',
        ],
      ),
      
      BestPractice(
        title: 'Monitor Performance Impact',
        description: 'Regularly assess and optimize configuration performance impact',
        category: 'Performance',
        priority: 'high',
        examples: [
          '''
// Monitor performance impact
final impact = await capabilities.assessPerformanceImpact(config);
if (impact == PerformanceImpact.extreme) {
  config = await getOptimizedConfiguration();
}''',
        ],
      ),
      
      BestPractice(
        title: 'Use Device-Appropriate Configurations',
        description: 'Adapt configurations based on device capabilities and constraints',
        category: 'Configuration Management',
        priority: 'medium',
        examples: [
          '''
// Adapt to device capabilities
final deviceInfo = await DeviceInfoPlugin().androidInfo;
final availableMemory = await getAvailableMemory();

if (availableMemory < 512) {
  config = await getLowMemoryConfiguration();
} else if (deviceInfo.version.sdkInt < 28) {
  config = await getLegacyConfiguration();
}''',
        ],
      ),
      
      BestPractice(
        title: 'Implement Progressive Fallbacks',
        description: 'Provide multiple fallback options when primary configuration fails',
        category: 'Reliability',
        priority: 'medium',
        examples: [
          '''
// Progressive fallbacks
ARCaptureConfig? config;

// Try optimal configuration
config = await tryOptimalConfiguration();

// Fall back to balanced configuration
config ??= await tryBalancedConfiguration();

// Fall back to minimal configuration
config ??= await getMinimalConfiguration();

// Final fallback
config ??= getHardcodedFallback();''',
        ],
      ),
      
      BestPractice(
        title: 'Cache Configuration Results',
        description: 'Cache capability queries and validation results to improve performance',
        category: 'Performance',
        priority: 'low',
        examples: [
          '''
// Cache expensive operations
class ConfigurationCache {
  static List<CameraResolution>? _cachedResolutions;
  static DateTime? _cacheTime;
  
  static Future<List<CameraResolution>> getCachedResolutions() async {
    final now = DateTime.now();
    
    if (_cachedResolutions == null || 
        _cacheTime == null ||
        now.difference(_cacheTime!).inMinutes > 30) {
      
      _cachedResolutions = await capabilities.getSupportedResolutions();
      _cacheTime = now;
    }
    
    return _cachedResolutions!;
  }
}''',
        ],
      ),
      
      BestPractice(
        title: 'Provide User Feedback',
        description: 'Give clear feedback about initialization, errors, and system state',
        category: 'User Experience',
        priority: 'medium',
        examples: [
          '''
// Clear user feedback
void showInitializationProgress() {
  showDialog(
    context: context,
    builder: (context) => AlertDialog(
      title: Text('Initializing Camera'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          CircularProgressIndicator(),
          SizedBox(height: 16),
          Text('Setting up camera capabilities...'),
        ],
      ),
    ),
  );
}''',
        ],
      ),
    ];
  }
  
  /// Get troubleshooting steps for common integration issues
  static Map<String, List<String>> getTroubleshootingSteps() {
    return {
      'Installation Issues': [
        'Verify Flutter and Dart SDK versions meet minimum requirements',
        'Check that all dependencies are compatible',
        'Run flutter clean and flutter pub get',
        'Verify platform-specific configuration files',
        'Check for conflicting dependencies',
      ],
      
      'Permission Issues': [
        'Verify manifest permissions are correctly declared',
        'Check runtime permission handling in code',
        'Test permission request flow on different devices',
        'Ensure permission rationale is shown to users',
        'Handle permission denial and permanent denial cases',
      ],
      
      'Platform Compatibility': [
        'Verify minimum Android API level (24+)',
        'Check ARCore availability on target devices',
        'Test on devices with different camera configurations',
        'Implement platform detection and fallbacks',
        'Validate device-specific capability queries',
      ],
      
      'Performance Issues': [
        'Monitor memory usage during capture operations',
        'Profile CPU usage and identify bottlenecks',
        'Test with different resolution and interval settings',
        'Implement performance monitoring and optimization',
        'Use device-appropriate configuration presets',
      ],
      
      'Configuration Problems': [
        'Always validate configurations before use',
        'Check device capability support for requested settings',
        'Implement fallback configurations for edge cases',
        'Monitor validation errors and adapt accordingly',
        'Test configuration on different device types',
      ],
    };
  }
}