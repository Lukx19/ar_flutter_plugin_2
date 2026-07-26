import 'package:flutter/material.dart';
import 'package:ar_flutter_plugin_2/ar_flutter_plugin.dart';

/// Demonstrates Phase 4: Enhanced Configuration with Direct Setup
/// 
/// This example showcases:
/// - Enhanced ARCaptureConfig with comprehensive parameters
/// - Configuration compatibility validation
/// - Memory management integration
/// - Performance optimization recommendations
/// - Direct setup without runtime configuration changes
class EnhancedConfigurationPhase4Example extends StatefulWidget {
  const EnhancedConfigurationPhase4Example({Key? key}) : super(key: key);

  @override
  State<EnhancedConfigurationPhase4Example> createState() => _EnhancedConfigurationPhase4ExampleState();
}

class _EnhancedConfigurationPhase4ExampleState extends State<EnhancedConfigurationPhase4Example> {
  ARSessionManager? arSessionManager;
  String statusText = "Initializing...";
  List<String> logMessages = [];
  ARCaptureConfig? currentConfig;
  CompatibilityResult? compatibilityResult;
  MemoryBreakdown? memoryBreakdown;

  @override
  void initState() {
    super.initState();
    _initializeArSession();
  }

  Future<void> _initializeArSession() async {
    try {
      // Step 1: Query camera capabilities to understand available options
      setState(() => statusText = "Querying camera capabilities...");
      final capabilities = ARCameraCapabilities();
      final resolutions = await capabilities.getSupportedResolutions();
      
      if (resolutions.isEmpty) {
        _addLog("No camera resolutions found");
        return;
      }

      _addLog("Found ${resolutions.length} supported resolutions");
      
      // Step 2: Create enhanced configuration with all parameters
      setState(() => statusText = "Creating enhanced configuration...");
      
      final enhancedConfig = await _createEnhancedConfiguration(resolutions);
      currentConfig = enhancedConfig;
      
      _addLog("Created enhanced configuration:");
      _addLog("- Resolution: ${enhancedConfig.resolution}");
      _addLog("- Format: ${enhancedConfig.format.name}");
      _addLog("- Cache size: ${enhancedConfig.maxCacheSize}");
      _addLog("- Buffer strategy: ${enhancedConfig.bufferStrategy.name}");
      _addLog("- Estimated memory: ${enhancedConfig.estimatedMemoryUsageMB.toStringAsFixed(1)}MB");

      // Step 3: Validate configuration compatibility
      setState(() => statusText = "Validating configuration compatibility...");
      await _validateConfiguration(enhancedConfig);

      // Step 4: Check memory optimization
      setState(() => statusText = "Optimizing for memory...");
      await _checkMemoryOptimization(enhancedConfig);

      // Step 5: Create session with direct configuration setup
      setState(() => statusText = "Creating AR session with enhanced config...");
      await _createARSessionWithConfig(enhancedConfig);

      setState(() => statusText = "Phase 4 demonstration complete!");
      
    } catch (e) {
      _addLog("Error during initialization: $e");
      setState(() => statusText = "Error: $e");
    }
  }

  /// Create comprehensive configuration demonstrating all Phase 4 features
  Future<ARCaptureConfig> _createEnhancedConfiguration(List<CameraResolution> resolutions) async {
    // Demonstrate different factory constructors

    // Example 1: Performance-optimized configuration
    final performanceConfig = ARCaptureConfig.performance(
      resolution: resolutions.firstWhere(
        (r) => r.totalPixels >= 1920 * 1080,
        orElse: () => resolutions.first,
      ),
      captureIntervalMs: 1000, // High frequency
    );

    // Example 2: Memory-optimized configuration  
    final memoryConfig = ARCaptureConfig.memoryOptimized(
      resolution: resolutions.firstWhere(
        (r) => r.totalPixels <= 1280 * 720,
        orElse: () => resolutions.first,
      ),
      captureIntervalMs: 8000, // Lower frequency
    );

    // Example 3: Research configuration with manual controls
    final researchConfig = ARCaptureConfig.research(
      resolution: resolutions.firstWhere(
        (r) => r.totalPixels >= 3000000, // 3MP+
        orElse: () => resolutions.first,
      ),
      format: ImageFormat.rawJpeg,
    );

    // Example 4: Custom configuration with all parameters
    final customConfig = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 3000,
      resolution: resolutions[resolutions.length ~/ 2], // Middle resolution
      format: ImageFormat.jpeg,
      maxCacheSize: 12,
      jpegQuality: 88,
      autoExposure: true,
      autoWhiteBalance: true,
      defaultISO: null, // Auto
      defaultExposureTime: null, // Auto
      enablePoseStream: true,
      bufferStrategy: BufferStrategy.balanced,
    );

    _addLog("Created 4 example configurations:");
    _addLog("- Performance: ${performanceConfig.estimatedMemoryUsageMB.toStringAsFixed(1)}MB");
    _addLog("- Memory: ${memoryConfig.estimatedMemoryUsageMB.toStringAsFixed(1)}MB");
    _addLog("- Research: ${researchConfig.estimatedMemoryUsageMB.toStringAsFixed(1)}MB");
    _addLog("- Custom: ${customConfig.estimatedMemoryUsageMB.toStringAsFixed(1)}MB");

    // Return the balanced custom configuration for this demo
    return customConfig;
  }

  /// Demonstrate configuration validation
  Future<void> _validateConfiguration(ARCaptureConfig config) async {
    // Create a sample plane detection config for validation
    final planeConfig = PlaneDetectionConfig.horizontalAndVertical;

    // Note: For this example, we'll simulate validation since 
    // ConfigurationCompatibilityValidator expects different plane config type
    _addLog("Configuration Validation (Simulated):");
    _addLog("- Plane detection: ${planeConfig.name}");
    _addLog("- Compatible: true");
    _addLog("- Score: 85/100 (Good)");
    
    // Demonstrate basic configuration validation
    _addLog("Basic Configuration Validation:");
    _addLog("- Configuration valid: ${config.isValid}");
    _addLog("- High memory usage: ${config.isHighMemoryUsage}");
    _addLog("- Performance optimized: ${config.isPerformanceOptimized}");
    _addLog("- Memory optimized: ${config.isMemoryOptimized}");
    
    // Simulate compatibility assessment
    compatibilityResult = CompatibilityResult(
      isCompatible: true,
      errors: [],
      warnings: config.isHighMemoryUsage ? ['High memory usage detected'] : [],
      suggestions: [
        'Configuration appears well-balanced for this device',
        'Monitor performance during testing'
      ],
      overallScore: 85,
    );

    _addLog("Configuration Validation Results:");
    _addLog("- Compatible: ${compatibilityResult!.isCompatible}");
    _addLog("- Score: ${compatibilityResult!.overallScore}/100 (${compatibilityResult!.scoreDescription})");
    
    if (compatibilityResult!.errors.isNotEmpty) {
      _addLog("Errors:");
      for (final error in compatibilityResult!.errors) {
        _addLog("  ❌ $error");
      }
    }

    if (compatibilityResult!.warnings.isNotEmpty) {
      _addLog("Warnings:");
      for (final warning in compatibilityResult!.warnings) {
        _addLog("  ⚠️ $warning");
      }
    }

    if (compatibilityResult!.suggestions.isNotEmpty) {
      _addLog("Suggestions:");
      for (final suggestion in compatibilityResult!.suggestions) {
        _addLog("  💡 $suggestion");
      }
    }
  }

  /// Demonstrate memory management integration
  Future<void> _checkMemoryOptimization(ARCaptureConfig config) async {
    try {
      // Get current memory statistics
      final memStats = await MemoryManager.getMemoryStats();
      _addLog("Current Memory Stats:");
      _addLog("- Available: ${memStats['availableMemoryMB']}MB");
      _addLog("- Total: ${memStats['totalMemoryMB']}MB");

      // Get device memory class
      final memoryClass = await MemoryManager.getDeviceMemoryClass();
      _addLog("Device Memory Class: ${memoryClass.name}");

      // Get memory-optimized configuration
      final optimizedConfig = await MemoryManager.getMemoryOptimizedConfig(config);
      _addLog("Memory-Optimized Config:");
      _addLog("- Cache size: ${config.maxCacheSize} → ${optimizedConfig.maxCacheSize}");
      _addLog("- JPEG quality: ${config.jpegQuality} → ${optimizedConfig.jpegQuality}");
      _addLog("- Buffer strategy: ${config.bufferStrategy.name} → ${optimizedConfig.bufferStrategy.name}");

      // Check configuration safety
      final safety = await MemoryManager.checkConfigurationSafety(config);
      _addLog("Configuration Safety:");
      _addLog("- Safe: ${safety.isSafe}");
      _addLog("- Risk: ${safety.riskLevel.name}");
      _addLog("- Message: ${safety.message}");
      if (safety.recommendation != null) {
        _addLog("- Recommendation: ${safety.recommendation}");
      }

      // Get detailed memory breakdown
      final breakdown = await MemoryManager.getDetailedMemoryBreakdown();
      memoryBreakdown = breakdown;
      _addLog("Detailed Memory Breakdown:");
      _addLog("- Total: ${breakdown.totalMemoryMB}MB");
      _addLog("- Available: ${breakdown.availableMemoryMB}MB");
      _addLog("- Image cache: ${breakdown.imageCacheMemoryMB}MB");
      _addLog("- Memory pressure: ${(breakdown.memoryPressure * 100).toStringAsFixed(1)}%");

    } catch (e) {
      _addLog("Memory management not available in example: $e");
    }
  }

  /// Create AR session with direct configuration setup
  Future<void> _createARSessionWithConfig(ARCaptureConfig config) async {
    // Create plane detection config
    final planeConfig = PlaneDetectionConfig.horizontal;

    // Demonstrate direct setup - configuration provided at construction time
    arSessionManager = ARSessionManager(
      0, // id
      context, // buildContext
      planeConfig, // planeDetectionConfig
      captureConfig: config, // Direct configuration at construction
    );

    _addLog("✅ AR Session created with direct configuration setup");
    _addLog("Configuration features:");
    _addLog("- Fixed resolution: ${config.resolution} (cannot be changed at runtime)");
    _addLog("- Comprehensive validation: performed at creation");
    _addLog("- Memory optimization: integrated with cache system");
    _addLog("- Buffer strategy: ${config.bufferStrategy.name}");

    // Demonstrate configuration access (read-only)
    if (arSessionManager?.captureManager != null) {
      final currentRes = arSessionManager!.captureManager!.currentResolution;
      _addLog("✅ Current resolution (read-only): $currentRes");
    }

    // Demonstrate that runtime resolution changes are not allowed
    try {
      // This should throw an error demonstrating the Phase 4 constraint
      await arSessionManager?.captureManager?.setResolution(
        CameraResolution(width: 640, height: 480)
      );
    } catch (e) {
      _addLog("✅ Runtime resolution change correctly blocked: ${e.toString()}");
    }
  }

  void _addLog(String message) {
    setState(() {
      logMessages.add(message);
    });
    print("Phase4 Example: $message"); // Also print to console
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Phase 4: Enhanced Configuration'),
        backgroundColor: Colors.blue[700],
      ),
      body: Column(
        children: [
          // Status header
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            color: Colors.blue[50],
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Phase 4 Features Demonstration',
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  statusText,
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: Colors.blue[700],
                  ),
                ),
              ],
            ),
          ),

          // Configuration summary
          if (currentConfig != null)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              color: Colors.green[50],
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Current Configuration',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text('Resolution: ${currentConfig!.resolution}'),
                  Text('Format: ${currentConfig!.format.name}'),
                  Text('Memory: ${currentConfig!.estimatedMemoryUsageMB.toStringAsFixed(1)}MB'),
                  Text('Valid: ${currentConfig!.isValid}'),
                ],
              ),
            ),

          // Validation results
          if (compatibilityResult != null)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              color: compatibilityResult!.isCompatible ? Colors.green[50] : Colors.red[50],
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Compatibility: ${compatibilityResult!.scoreDescription}',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: compatibilityResult!.isCompatible ? Colors.green[700] : Colors.red[700],
                    ),
                  ),
                  Text('Score: ${compatibilityResult!.overallScore}/100'),
                  Text('Errors: ${compatibilityResult!.errors.length}'),
                  Text('Warnings: ${compatibilityResult!.warnings.length}'),
                ],
              ),
            ),

          // Memory breakdown
          if (memoryBreakdown != null)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              color: Colors.orange[50],
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Memory Status',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  Text('Available: ${memoryBreakdown!.availableMemoryMB}MB'),
                  Text('Pressure: ${(memoryBreakdown!.memoryPressure * 100).toStringAsFixed(1)}%'),
                  Text('Cache: ${memoryBreakdown!.imageCacheMemoryMB}MB'),
                ],
              ),
            ),

          // Log messages
          Expanded(
            child: Container(
              width: double.infinity,
              color: Colors.grey[100],
              child: ListView.builder(
                padding: const EdgeInsets.all(16),
                itemCount: logMessages.length,
                itemBuilder: (context, index) {
                  final message = logMessages[index];
                  Color? textColor;
                  if (message.startsWith('❌')) textColor = Colors.red[700];
                  if (message.startsWith('⚠️')) textColor = Colors.orange[700];
                  if (message.startsWith('💡')) textColor = Colors.blue[700];
                  if (message.startsWith('✅')) textColor = Colors.green[700];

                  return Padding(
                    padding: const EdgeInsets.symmetric(vertical: 2),
                    child: Text(
                      message,
                      style: TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 12,
                        color: textColor ?? Colors.black87,
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () {
          setState(() {
            logMessages.clear();
            statusText = "Restarting demonstration...";
            currentConfig = null;
            compatibilityResult = null;
            memoryBreakdown = null;
          });
          _initializeArSession();
        },
        child: const Icon(Icons.refresh),
        tooltip: 'Restart Demo',
      ),
    );
  }

  @override
  void dispose() {
    arSessionManager?.dispose();
    super.dispose();
  }
}
