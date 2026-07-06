import 'package:flutter/material.dart';
import '../ar_flutter_plugin.dart';
import '../capabilities/ar_camera_capabilities.dart';
import '../models/ar_capture_config.dart';
import '../models/camera_resolution.dart';
import '../datatypes/image_format.dart';

/// Example scenarios demonstrating AR capture usage patterns
enum ExampleScenario {
  basicCapture,
  advancedConfiguration,
  memoryOptimization,
  errorHandling,
  customPresets,
}

/// Comprehensive example application showcasing AR capture capabilities
/// 
/// This class provides a collection of usage examples and code samples
/// demonstrating common capture scenarios, configuration patterns, and
/// best practices for AR capture integration.
class CaptureExampleApp extends StatefulWidget {
  static const List<ExampleScenario> scenarios = [
    ExampleScenario.basicCapture,
    ExampleScenario.advancedConfiguration,
    ExampleScenario.memoryOptimization,
    ExampleScenario.errorHandling,
    ExampleScenario.customPresets,
  ];
  
  const CaptureExampleApp({Key? key}) : super(key: key);
  
  @override
  State<CaptureExampleApp> createState() => _CaptureExampleAppState();
}

class _CaptureExampleAppState extends State<CaptureExampleApp> {
  ExampleScenario _currentScenario = ExampleScenario.basicCapture;
  late ARCameraCapabilities _capabilities;
  String _output = '';
  bool _isRunning = false;
  
  @override
  void initState() {
    super.initState();
    _capabilities = ARCameraCapabilities();
  }
  
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Capture Examples'),
        backgroundColor: Colors.green,
      ),
      body: Column(
        children: [
          _buildScenarioSelector(),
          Expanded(child: _buildCurrentExample()),
          _buildOutput(),
        ],
      ),
    );
  }
  
  Widget _buildScenarioSelector() {
    return Container(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Example Scenarios',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            children: CaptureExampleApp.scenarios.map((scenario) {
              return ChoiceChip(
                label: Text(_getScenarioTitle(scenario)),
                selected: _currentScenario == scenario,
                onSelected: (selected) {
                  if (selected) {
                    setState(() {
                      _currentScenario = scenario;
                      _output = '';
                    });
                  }
                },
              );
            }).toList(),
          ),
        ],
      ),
    );
  }
  
  Widget _buildCurrentExample() {
    switch (_currentScenario) {
      case ExampleScenario.basicCapture:
        return _buildBasicCaptureExample();
      case ExampleScenario.advancedConfiguration:
        return _buildAdvancedConfigurationExample();
      case ExampleScenario.memoryOptimization:
        return _buildMemoryOptimizationExample();
      case ExampleScenario.errorHandling:
        return _buildErrorHandlingExample();
      case ExampleScenario.customPresets:
        return _buildCustomPresetsExample();
    }
  }
  
  Widget _buildBasicCaptureExample() {
    return _buildExampleCard(
      title: 'Basic Capture Setup',
      description: 'Simple capture setup with minimal configuration',
      codeExample: '''
// Basic capture setup example
class BasicCaptureExample {
  late ARCameraCapabilities _capabilities;
  ARCaptureConfig? _config;
  
  Future<void> initialize() async {
    _capabilities = ARCameraCapabilities();
    
    // Check platform support
    if (!_capabilities.isSupported) {
      throw Exception('Platform not supported');
    }
    
    // Get supported resolutions
    final resolutions = await _capabilities.getSupportedResolutions();
    if (resolutions.isEmpty) {
      throw Exception('No supported resolutions');
    }
    
    // Create basic configuration
    _config = ARCaptureConfig(
      resolution: resolutions.first, // Use first supported resolution
      format: ImageFormat.jpeg,     // Standard JPEG format
      enableHighResCapture: true,   // Enable high resolution
      captureIntervalMs: 1000,      // 1 second interval
    );
    
    // Validate configuration
    final validation = await _capabilities.validateCaptureConfig(_config!);
    if (!validation.isValid) {
      print('Configuration validation failed: \${validation.errors}');
      // Use suggested configuration if available
      if (validation.suggestedConfig != null) {
        _config = validation.suggestedConfig;
      }
    }
  }
  
  Future<void> startCapture() async {
    if (_config == null) {
      await initialize();
    }
    
    print('Starting capture with resolution: \${_config!.resolution}');
    print('Format: \${_config!.format}');
    // Start actual capture process...
  }
}''',
      onRun: _runBasicCaptureExample,
    );
  }
  
  Widget _buildAdvancedConfigurationExample() {
    return _buildExampleCard(
      title: 'Advanced Configuration',
      description: 'Complex configuration scenarios with optimization',
      codeExample: '''
// Advanced configuration example
class AdvancedConfigurationExample {
  late ARCameraCapabilities _capabilities;
  
  Future<ARCaptureConfig> createOptimalConfiguration() async {
    _capabilities = ARCameraCapabilities();
    
    // Get all supported capabilities
    final resolutions = await _capabilities.getSupportedResolutions();
    final formats = await _capabilities.getSupportedFormats();
    
    // Find optimal resolution (balance between quality and performance)
    final optimalResolution = _findOptimalResolution(resolutions);
    
    // Choose best format
    final optimalFormat = _chooseBestFormat(formats);
    
    // Create configuration with device-specific optimizations
    final config = ARCaptureConfig(
      resolution: optimalResolution,
      format: optimalFormat,
      enableHighResCapture: _shouldEnableHighRes(optimalResolution),
      captureIntervalMs: _calculateOptimalInterval(optimalResolution),
    );
    
    // Assess performance impact
    final impact = await _capabilities.assessPerformanceImpact(config);
    print('Performance impact: \$impact');
    
    // Optimize based on performance assessment
    if (impact == PerformanceImpact.high || impact == PerformanceImpact.extreme) {
      return await _createOptimizedConfiguration(config);
    }
    
    return config;
  }
  
  CameraResolution _findOptimalResolution(List<CameraResolution> resolutions) {
    // Sort by total pixels and choose middle-high option
    resolutions.sort((a, b) => b.totalPixels.compareTo(a.totalPixels));
    
    // Choose resolution in upper third for good quality without extreme performance cost
    final targetIndex = (resolutions.length * 0.3).round();
    return resolutions[targetIndex.clamp(0, resolutions.length - 1)];
  }
  
  ImageFormat _chooseBestFormat(List<ImageFormat> formats) {
    // Prefer JPEG for compatibility, fallback to first available
    return formats.contains(ImageFormat.jpeg) 
        ? ImageFormat.jpeg 
        : formats.first;
  }
  
  bool _shouldEnableHighRes(CameraResolution resolution) {
    // Enable high res for resolutions below 4K to maintain quality
    return resolution.totalPixels < 8000000; // 8MP threshold
  }
  
  int _calculateOptimalInterval(CameraResolution resolution) {
    // Longer intervals for higher resolutions to prevent performance issues
    if (resolution.totalPixels > 8000000) return 3000; // 3 seconds for 4K+
    if (resolution.totalPixels > 2000000) return 2000; // 2 seconds for 1080p+
    return 1000; // 1 second for lower resolutions
  }
  
  Future<ARCaptureConfig> _createOptimizedConfiguration(ARCaptureConfig original) async {
    // Create optimized version with reduced performance impact
    final resolutions = await _capabilities.getSupportedResolutions();
    resolutions.sort((a, b) => a.totalPixels.compareTo(b.totalPixels));
    
    // Choose lower resolution
    final optimizedResolution = resolutions[resolutions.length ~/ 2];
    
    return ARCaptureConfig(
      resolution: optimizedResolution,
      format: original.format,
      enableHighResCapture: false, // Disable high res for performance
      captureIntervalMs: original.captureIntervalMs * 2, // Double interval
    );
  }
}''',
      onRun: _runAdvancedConfigurationExample,
    );
  }
  
  Widget _buildMemoryOptimizationExample() {
    return _buildExampleCard(
      title: 'Memory Optimization',
      description: 'Memory-conscious configuration and resource management',
      codeExample: '''
// Memory optimization example
class MemoryOptimizedCaptureExample {
  late ARCameraCapabilities _capabilities;
  final List<String> _captureHistory = [];
  static const int maxHistorySize = 50;
  
  Future<ARCaptureConfig> createMemoryOptimizedConfig() async {
    _capabilities = ARCameraCapabilities();
    
    // Get available memory (mock - in real app, use device_info_plus)
    final availableMemoryMB = await _getAvailableMemory();
    
    // Choose resolution based on available memory
    final optimalResolution = await _chooseResolutionForMemory(availableMemoryMB);
    
    // Configure with memory constraints
    final config = ARCaptureConfig(
      resolution: optimalResolution,
      format: ImageFormat.jpeg, // JPEG uses less memory than RAW
      enableHighResCapture: availableMemoryMB > 512, // Only if sufficient memory
      captureIntervalMs: _calculateIntervalForMemory(availableMemoryMB),
    );
    
    return config;
  }
  
  Future<double> _getAvailableMemory() async {
    // Mock implementation - real app would use platform-specific methods
    return 1024.0; // MB
  }
  
  Future<CameraResolution> _chooseResolutionForMemory(double availableMemoryMB) async {
    final resolutions = await _capabilities.getSupportedResolutions();
    resolutions.sort((a, b) => a.totalPixels.compareTo(b.totalPixels));
    
    // Memory usage estimation: roughly 4 bytes per pixel for processing
    // Plus additional overhead for JPEG compression/decompression
    
    for (final resolution in resolutions) {
      final estimatedMemoryMB = (resolution.totalPixels * 4) / (1024 * 1024) * 2; // 2x for overhead
      
      if (estimatedMemoryMB < availableMemoryMB * 0.3) { // Use max 30% of available memory
        return resolution;
      }
    }
    
    // If no resolution fits, use the smallest
    return resolutions.first;
  }
  
  int _calculateIntervalForMemory(double availableMemoryMB) {
    // Longer intervals for lower memory to prevent accumulation
    if (availableMemoryMB < 256) return 5000;  // 5 seconds
    if (availableMemoryMB < 512) return 3000;  // 3 seconds
    return 1000; // 1 second
  }
  
  void manageMemoryUsage() {
    // Implement capture history management to prevent memory accumulation
    if (_captureHistory.length > maxHistorySize) {
      // Remove oldest captures
      final removeCount = _captureHistory.length - maxHistorySize;
      _captureHistory.removeRange(0, removeCount);
      print('Cleaned up \$removeCount old captures to manage memory');
    }
    
    // Force garbage collection periodically (platform-specific)
    // This is just a placeholder - real implementation would be platform-specific
    _forceGarbageCollection();
  }
  
  void _forceGarbageCollection() {
    // Mock implementation
    print('Triggering garbage collection...');
  }
  
  Future<void> optimizedCaptureWorkflow() async {
    final config = await createMemoryOptimizedConfig();
    
    // Monitor memory usage during capture
    var captureCount = 0;
    
    while (captureCount < 100) { // Example: 100 captures
      // Simulate capture
      final capturePath = '/tmp/capture_\${captureCount}.jpg';
      _captureHistory.add(capturePath);
      
      // Manage memory every 10 captures
      if (captureCount % 10 == 0) {
        manageMemoryUsage();
      }
      
      captureCount++;
      
      // Wait for configured interval
      await Future.delayed(Duration(milliseconds: config.captureIntervalMs));
    }
  }
}''',
      onRun: _runMemoryOptimizationExample,
    );
  }
  
  Widget _buildErrorHandlingExample() {
    return _buildExampleCard(
      title: 'Comprehensive Error Handling',
      description: 'Robust error handling for various failure scenarios',
      codeExample: '''
// Comprehensive error handling example
class ErrorHandlingCaptureExample {
  late ARCameraCapabilities _capabilities;
  int _retryCount = 0;
  static const int maxRetries = 3;
  
  Future<void> robustCaptureSetup() async {
    try {
      await _initializeWithRetry();
      await _setupConfiguration();
      print('Capture setup completed successfully');
    } catch (e) {
      await _handleFatalError(e);
    }
  }
  
  Future<void> _initializeWithRetry() async {
    _retryCount = 0;
    
    while (_retryCount < maxRetries) {
      try {
        _capabilities = ARCameraCapabilities();
        
        // Check platform support
        if (!_capabilities.isSupported) {
          throw UnsupportedPlatformException('Camera capabilities not supported on this platform');
        }
        
        // Test basic capability query
        await _capabilities.getSupportedResolutions();
        
        print('Initialization successful on attempt \${_retryCount + 1}');
        return;
        
      } on ARCameraCapabilityException catch (e) {
        _retryCount++;
        print('Capability error (attempt \$_retryCount): \${e.message}');
        
        if (_retryCount >= maxRetries) {
          throw CapabilityInitializationException('Failed to initialize after \$maxRetries attempts: \${e.message}');
        }
        
        // Wait before retry with exponential backoff
        await Future.delayed(Duration(milliseconds: 500 * _retryCount));
        
      } catch (e) {
        _retryCount++;
        print('Unexpected error (attempt \$_retryCount): \$e');
        
        if (_retryCount >= maxRetries) {
          throw InitializationException('Unexpected initialization failure: \$e');
        }
        
        await Future.delayed(Duration(milliseconds: 1000 * _retryCount));
      }
    }
  }
  
  Future<ARCaptureConfig> _setupConfiguration() async {
    try {
      // Get capabilities with error handling
      final resolutions = await _getResolutionsWithFallback();
      final formats = await _getFormatsWithFallback();
      
      // Create and validate configuration
      final config = ARCaptureConfig(
        resolution: resolutions.first,
        format: formats.first,
        enableHighResCapture: true,
        captureIntervalMs: 1000,
      );
      
      final validation = await _validateConfigurationWithRetry(config);
      
      if (validation.isValid) {
        return config;
      } else {
        return await _handleValidationFailure(validation);
      }
      
    } catch (e) {
      throw ConfigurationException('Failed to setup configuration: \$e');
    }
  }
  
  Future<List<CameraResolution>> _getResolutionsWithFallback() async {
    try {
      final resolutions = await _capabilities.getSupportedResolutions();
      
      if (resolutions.isEmpty) {
        print('No supported resolutions found, using fallback');
        // Return common fallback resolutions
        return [
          const CameraResolution(width: 1280, height: 720),
          const CameraResolution(width: 640, height: 480),
        ];
      }
      
      return resolutions;
      
    } catch (e) {
      print('Failed to get resolutions, using fallback: \$e');
      return [const CameraResolution(width: 640, height: 480)];
    }
  }
  
  Future<List<ImageFormat>> _getFormatsWithFallback() async {
    try {
      final formats = await _capabilities.getSupportedFormats();
      
      if (formats.isEmpty) {
        print('No supported formats found, using JPEG fallback');
        return [ImageFormat.jpeg];
      }
      
      return formats;
      
    } catch (e) {
      print('Failed to get formats, using JPEG fallback: \$e');
      return [ImageFormat.jpeg];
    }
  }
  
  Future<ValidationResult> _validateConfigurationWithRetry(ARCaptureConfig config) async {
    for (int attempt = 0; attempt < maxRetries; attempt++) {
      try {
        return await _capabilities.validateCaptureConfig(config);
      } catch (e) {
        print('Validation attempt \${attempt + 1} failed: \$e');
        
        if (attempt == maxRetries - 1) {
          // Last attempt failed, return failed validation
          return const ValidationResult(
            isValid: false,
            errors: ['Validation failed after multiple attempts'],
          );
        }
        
        await Future.delayed(Duration(milliseconds: 500 * (attempt + 1)));
      }
    }
    
    // This should never be reached, but adding for safety
    return const ValidationResult(
      isValid: false,
      errors: ['Validation failed - unexpected state'],
    );
  }
  
  Future<ARCaptureConfig> _handleValidationFailure(ValidationResult validation) async {
    print('Configuration validation failed:');
    for (final error in validation.errors) {
      print('  Error: \$error');
    }
    
    for (final warning in validation.warnings) {
      print('  Warning: \$warning');
    }
    
    // Try to use suggested configuration
    if (validation.suggestedConfig != null) {
      print('Using suggested configuration');
      return validation.suggestedConfig!;
    }
    
    // Fall back to recommended configuration
    try {
      print('Attempting to get recommended configuration');
      return await _capabilities.getRecommendedConfig();
    } catch (e) {
      print('Failed to get recommended config: \$e');
      throw ConfigurationException('No valid configuration available');
    }
  }
  
  Future<void> _handleFatalError(dynamic error) async {
    print('Fatal error occurred: \$error');
    
    // Log error details
    if (error is Exception) {
      print('Exception type: \${error.runtimeType}');
    }
    
    // Attempt cleanup
    try {
      await _cleanup();
    } catch (cleanupError) {
      print('Cleanup failed: \$cleanupError');
    }
    
    // Rethrow for higher-level handling
    rethrow;
  }
  
  Future<void> _cleanup() async {
    print('Performing cleanup...');
    // Cleanup resources, reset state, etc.
  }
}

// Custom exception classes for better error categorization
class UnsupportedPlatformException implements Exception {
  final String message;
  UnsupportedPlatformException(this.message);
  @override
  String toString() => 'UnsupportedPlatformException: \$message';
}

class CapabilityInitializationException implements Exception {
  final String message;
  CapabilityInitializationException(this.message);
  @override
  String toString() => 'CapabilityInitializationException: \$message';
}

class InitializationException implements Exception {
  final String message;
  InitializationException(this.message);
  @override
  String toString() => 'InitializationException: \$message';
}

class ConfigurationException implements Exception {
  final String message;
  ConfigurationException(this.message);
  @override
  String toString() => 'ConfigurationException: \$message';
}''',
      onRun: _runErrorHandlingExample,
    );
  }
  
  Widget _buildCustomPresetsExample() {
    return _buildExampleCard(
      title: 'Custom Configuration Presets',
      description: 'Predefined configurations for different use cases',
      codeExample: '''
// Custom configuration presets example
class CustomPresetsExample {
  late ARCameraCapabilities _capabilities;
  
  Future<void> initialize() async {
    _capabilities = ARCameraCapabilities();
  }
  
  // Preset for high-quality photogrammetry capture
  Future<ARCaptureConfig> getPhotogrammetryPreset() async {
    final resolutions = await _capabilities.getSupportedResolutions();
    
    // Find highest resolution for maximum detail
    resolutions.sort((a, b) => b.totalPixels.compareTo(a.totalPixels));
    final highestRes = resolutions.isNotEmpty 
        ? resolutions.first 
        : const CameraResolution(width: 1920, height: 1080);
    
    return ARCaptureConfig(
      resolution: highestRes,
      format: ImageFormat.jpeg,
      enableHighResCapture: true,
      captureIntervalMs: 2000, // Slower for stability
    );
  }
  
  // Preset for real-time AR applications
  Future<ARCaptureConfig> getRealTimeARPreset() async {
    final resolutions = await _capabilities.getSupportedResolutions();
    
    // Find medium resolution for balance of quality and performance
    resolutions.sort((a, b) => a.totalPixels.compareTo(b.totalPixels));
    final mediumRes = resolutions.isNotEmpty 
        ? resolutions[resolutions.length ~/ 2]
        : const CameraResolution(width: 1280, height: 720);
    
    return ARCaptureConfig(
      resolution: mediumRes,
      format: ImageFormat.jpeg,
      enableHighResCapture: false, // Prioritize speed
      captureIntervalMs: 500, // Fast capture for real-time
    );
  }
  
  // Preset for battery-efficient capture
  Future<ARCaptureConfig> getBatteryEfficientPreset() async {
    final resolutions = await _capabilities.getSupportedResolutions();
    
    // Find lower resolution to save battery
    resolutions.sort((a, b) => a.totalPixels.compareTo(b.totalPixels));
    final lowRes = resolutions.isNotEmpty 
        ? resolutions[resolutions.length ~/ 4]
        : const CameraResolution(width: 640, height: 480);
    
    return ARCaptureConfig(
      resolution: lowRes,
      format: ImageFormat.jpeg,
      enableHighResCapture: false,
      captureIntervalMs: 5000, // Infrequent capture
    );
  }
  
  // Preset for motion capture applications
  Future<ARCaptureConfig> getMotionCapturePreset() async {
    final resolutions = await _capabilities.getSupportedResolutions();
    
    // Balance resolution and speed for motion tracking
    resolutions.sort((a, b) => a.totalPixels.compareTo(b.totalPixels));
    final motionRes = resolutions.isNotEmpty 
        ? resolutions[resolutions.length * 2 ~/ 3]
        : const CameraResolution(width: 1280, height: 720);
    
    return ARCaptureConfig(
      resolution: motionRes,
      format: ImageFormat.jpeg,
      enableHighResCapture: true,
      captureIntervalMs: 100, // Very fast for motion
    );
  }
  
  // Preset selector based on use case
  Future<ARCaptureConfig> getPresetForUseCase(CaptureUseCase useCase) async {
    switch (useCase) {
      case CaptureUseCase.photogrammetry:
        return await getPhotogrammetryPreset();
      case CaptureUseCase.realTimeAR:
        return await getRealTimeARPreset();
      case CaptureUseCase.batteryEfficient:
        return await getBatteryEfficientPreset();
      case CaptureUseCase.motionCapture:
        return await getMotionCapturePreset();
    }
  }
  
  // Validate and optimize any preset
  Future<ARCaptureConfig> optimizePreset(ARCaptureConfig preset) async {
    final validation = await _capabilities.validateCaptureConfig(preset);
    
    if (validation.isValid) {
      return preset;
    }
    
    // If preset is invalid, try to fix it
    if (validation.suggestedConfig != null) {
      return validation.suggestedConfig!;
    }
    
    // Last resort: get recommended config
    return await _capabilities.getRecommendedConfig();
  }
  
  // Get all available presets
  Future<Map<String, ARCaptureConfig>> getAllPresets() async {
    return {
      'photogrammetry': await getPhotogrammetryPreset(),
      'realtime_ar': await getRealTimeARPreset(),
      'battery_efficient': await getBatteryEfficientPreset(),
      'motion_capture': await getMotionCapturePreset(),
    };
  }
}

enum CaptureUseCase {
  photogrammetry,
  realTimeAR,
  batteryEfficient,
  motionCapture,
}''',
      onRun: _runCustomPresetsExample,
    );
  }
  
  Widget _buildExampleCard({
    required String title,
    required String description,
    required String codeExample,
    required VoidCallback onRun,
  }) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          Text(
            description,
            style: TextStyle(fontSize: 16, color: Colors.grey[600]),
          ),
          const SizedBox(height: 16),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Colors.grey[100],
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: Colors.grey[300]!),
            ),
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Text(
                codeExample,
                style: const TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 12,
                ),
              ),
            ),
          ),
          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              onPressed: _isRunning ? null : onRun,
              child: Text(_isRunning ? 'Running...' : 'Run Example'),
            ),
          ),
        ],
      ),
    );
  }
  
  Widget _buildOutput() {
    return Container(
      height: 150,
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Output',
            style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          Expanded(
            child: Container(
              width: double.infinity,
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: Colors.black,
                borderRadius: BorderRadius.circular(4),
              ),
              child: SingleChildScrollView(
                child: Text(
                  _output.isEmpty ? 'No output yet. Run an example to see results.' : _output,
                  style: const TextStyle(
                    color: Colors.green,
                    fontFamily: 'monospace',
                    fontSize: 12,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
  
  String _getScenarioTitle(ExampleScenario scenario) {
    switch (scenario) {
      case ExampleScenario.basicCapture:
        return 'Basic Capture';
      case ExampleScenario.advancedConfiguration:
        return 'Advanced Config';
      case ExampleScenario.memoryOptimization:
        return 'Memory Optimization';
      case ExampleScenario.errorHandling:
        return 'Error Handling';
      case ExampleScenario.customPresets:
        return 'Custom Presets';
    }
  }
  
  // Example runners
  
  Future<void> _runBasicCaptureExample() async {
    setState(() {
      _isRunning = true;
      _output = 'Running basic capture example...\n';
    });
    
    try {
      if (!_capabilities.isSupported) {
        _addOutput('Platform not supported - using mock data\n');
      }
      
      _addOutput('Checking supported resolutions...\n');
      final resolutions = await _capabilities.getSupportedResolutions();
      _addOutput('Found ${resolutions.length} supported resolutions\n');
      
      if (resolutions.isNotEmpty) {
        final config = ARCaptureConfig(
          resolution: resolutions.first,
          format: ImageFormat.jpeg,
          enableHighResCapture: true,
          captureIntervalMs: 1000,
        );
        
        _addOutput('Created configuration: ${config.resolution.width}x${config.resolution.height}\n');
        
        final validation = await _capabilities.validateCaptureConfig(config);
        _addOutput('Configuration valid: ${validation.isValid}\n');
        
        if (!validation.isValid) {
          _addOutput('Validation errors: ${validation.errors.join(', ')}\n');
        }
      }
      
      _addOutput('Basic capture example completed successfully!\n');
      
    } catch (e) {
      _addOutput('Error: $e\n');
    } finally {
      setState(() => _isRunning = false);
    }
  }
  
  Future<void> _runAdvancedConfigurationExample() async {
    setState(() {
      _isRunning = true;
      _output = 'Running advanced configuration example...\n';
    });
    
    try {
      _addOutput('Getting supported capabilities...\n');
      final resolutions = await _capabilities.getSupportedResolutions();
      final formats = await _capabilities.getSupportedFormats();
      
      _addOutput('Found ${resolutions.length} resolutions and ${formats.length} formats\n');
      
      if (resolutions.isNotEmpty) {
        // Sort and pick optimal resolution
        resolutions.sort((a, b) => b.totalPixels.compareTo(a.totalPixels));
        final optimalIndex = (resolutions.length * 0.3).round().clamp(0, resolutions.length - 1);
        final optimalResolution = resolutions[optimalIndex];
        
        _addOutput('Selected optimal resolution: ${optimalResolution.width}x${optimalResolution.height}\n');
        
        final config = ARCaptureConfig(
          resolution: optimalResolution,
          format: formats.isNotEmpty ? formats.first : ImageFormat.jpeg,
          enableHighResCapture: optimalResolution.totalPixels < 8000000,
          captureIntervalMs: optimalResolution.totalPixels > 2000000 ? 2000 : 1000,
        );
        
        final impact = await _capabilities.assessPerformanceImpact(config);
        _addOutput('Performance impact: $impact\n');
        
        final validation = await _capabilities.validateCaptureConfig(config);
        _addOutput('Advanced configuration valid: ${validation.isValid}\n');
      }
      
      _addOutput('Advanced configuration example completed!\n');
      
    } catch (e) {
      _addOutput('Error: $e\n');
    } finally {
      setState(() => _isRunning = false);
    }
  }
  
  Future<void> _runMemoryOptimizationExample() async {
    setState(() {
      _isRunning = true;
      _output = 'Running memory optimization example...\n';
    });
    
    try {
      _addOutput('Simulating memory-constrained environment...\n');
      
      const availableMemoryMB = 512.0; // Simulate 512MB available
      _addOutput('Available memory: ${availableMemoryMB}MB\n');
      
      final resolutions = await _capabilities.getSupportedResolutions();
      
      if (resolutions.isNotEmpty) {
        resolutions.sort((a, b) => a.totalPixels.compareTo(b.totalPixels));
        
        // Find memory-appropriate resolution
        CameraResolution? memoryOptimalRes;
        for (final resolution in resolutions) {
          final estimatedMemoryMB = (resolution.totalPixels * 4) / (1024 * 1024) * 2; // 2x overhead
          if (estimatedMemoryMB < availableMemoryMB * 0.3) { // Use max 30% of memory
            memoryOptimalRes = resolution;
          }
        }
        
        memoryOptimalRes ??= resolutions.first; // Fallback to smallest
        
        _addOutput('Selected memory-optimal resolution: ${memoryOptimalRes.width}x${memoryOptimalRes.height}\n');
        
        final config = ARCaptureConfig(
          resolution: memoryOptimalRes,
          format: ImageFormat.jpeg,
          enableHighResCapture: availableMemoryMB > 512,
          captureIntervalMs: availableMemoryMB < 256 ? 5000 : 1000,
        );
        
        _addOutput('Memory-optimized configuration created\n');
        _addOutput('High res enabled: ${config.enableHighResCapture}\n');
        _addOutput('Capture interval: ${config.captureIntervalMs}ms\n');
      }
      
      _addOutput('Memory optimization example completed!\n');
      
    } catch (e) {
      _addOutput('Error: $e\n');
    } finally {
      setState(() => _isRunning = false);
    }
  }
  
  Future<void> _runErrorHandlingExample() async {
    setState(() {
      _isRunning = true;
      _output = 'Running error handling example...\n';
    });
    
    try {
      _addOutput('Testing platform support...\n');
      
      if (!_capabilities.isSupported) {
        _addOutput('Platform not supported - implementing fallback behavior\n');
        _addOutput('Fallback: Using mock resolutions\n');
        
        // Simulate fallback behavior
        const fallbackResolutions = [
          CameraResolution(width: 1280, height: 720),
          CameraResolution(width: 640, height: 480),
        ];
        
        _addOutput('Using fallback resolutions: ${fallbackResolutions.length} available\n');
      } else {
        _addOutput('Platform supported - proceeding with capability queries\n');
      }
      
      _addOutput('Testing configuration validation with potential failures...\n');
      
      // Test with potentially problematic configuration
      const testConfig = ARCaptureConfig(
        resolution: CameraResolution(width: 9999, height: 9999), // Likely unsupported
        format: ImageFormat.jpeg,
        enableHighResCapture: true,
        captureIntervalMs: 1,
      );
      
      try {
        final validation = await _capabilities.validateCaptureConfig(testConfig);
        
        if (!validation.isValid) {
          _addOutput('Configuration validation failed as expected\n');
          _addOutput('Errors found: ${validation.errors.length}\n');
          
          if (validation.suggestedConfig != null) {
            _addOutput('Suggested configuration available - using fallback\n');
            final suggested = validation.suggestedConfig!;
            _addOutput('Suggested resolution: ${suggested.resolution.width}x${suggested.resolution.height}\n');
          }
        } else {
          _addOutput('Unexpected: problematic configuration was valid\n');
        }
      } catch (e) {
        _addOutput('Caught validation exception: ${e.toString()}\n');
        _addOutput('Implementing error recovery...\n');
        
        // Attempt to get recommended configuration as fallback
        try {
          final recommended = await _capabilities.getRecommendedConfig();
          _addOutput('Successfully recovered with recommended configuration\n');
          _addOutput('Recovery resolution: ${recommended.resolution.width}x${recommended.resolution.height}\n');
        } catch (recoveryError) {
          _addOutput('Recovery failed: ${recoveryError.toString()}\n');
          _addOutput('Using hardcoded fallback configuration\n');
        }
      }
      
      _addOutput('Error handling example completed successfully!\n');
      
    } catch (e) {
      _addOutput('Unhandled error: $e\n');
    } finally {
      setState(() => _isRunning = false);
    }
  }
  
  Future<void> _runCustomPresetsExample() async {
    setState(() {
      _isRunning = true;
      _output = 'Running custom presets example...\n';
    });
    
    try {
      _addOutput('Creating custom configuration presets...\n');
      
      final resolutions = await _capabilities.getSupportedResolutions();
      
      if (resolutions.isNotEmpty) {
        resolutions.sort((a, b) => b.totalPixels.compareTo(a.totalPixels));
        
        // Photogrammetry preset (highest quality)
        final photogrammetryConfig = ARCaptureConfig(
          resolution: resolutions.first,
          format: ImageFormat.jpeg,
          enableHighResCapture: true,
          captureIntervalMs: 2000,
        );
        _addOutput('Created photogrammetry preset: ${photogrammetryConfig.resolution.width}x${photogrammetryConfig.resolution.height}\n');
        
        // Real-time AR preset (balanced)
        final realTimeConfig = ARCaptureConfig(
          resolution: resolutions[resolutions.length ~/ 2],
          format: ImageFormat.jpeg,
          enableHighResCapture: false,
          captureIntervalMs: 500,
        );
        _addOutput('Created real-time AR preset: ${realTimeConfig.resolution.width}x${realTimeConfig.resolution.height}\n');
        
        // Battery efficient preset (low resource usage)
        final batteryConfig = ARCaptureConfig(
          resolution: resolutions[resolutions.length ~/ 4],
          format: ImageFormat.jpeg,
          enableHighResCapture: false,
          captureIntervalMs: 5000,
        );
        _addOutput('Created battery-efficient preset: ${batteryConfig.resolution.width}x${batteryConfig.resolution.height}\n');
        
        // Validate all presets
        for (final config in [photogrammetryConfig, realTimeConfig, batteryConfig]) {
          final validation = await _capabilities.validateCaptureConfig(config);
          final name = config == photogrammetryConfig ? 'photogrammetry' :
                      config == realTimeConfig ? 'real-time' : 'battery-efficient';
          _addOutput('$name preset valid: ${validation.isValid}\n');
        }
      }
      
      _addOutput('Custom presets example completed!\n');
      
    } catch (e) {
      _addOutput('Error: $e\n');
    } finally {
      setState(() => _isRunning = false);
    }
  }
  
  void _addOutput(String text) {
    setState(() {
      _output += text;
    });
  }
}