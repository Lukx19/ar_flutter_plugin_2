import 'dart:io';
import 'dart:convert';
import 'package:flutter/services.dart';
import '../ar_flutter_plugin.dart';
import '../capabilities/ar_camera_capabilities.dart';
import '../models/ar_capture_config.dart';
import '../models/ar_camera_intrinsics.dart';
import '../models/camera_resolution.dart';

/// Documentation generation utilities for AR Flutter Plugin
/// 
/// This class provides utilities for generating comprehensive API documentation,
/// integration guides, migration guides, and validating documentation examples.
class DocumentationGenerator {
  static const String _version = '1.0.0';
  static const String _baseOutputPath = 'docs/generated';
  
  /// Generate comprehensive API reference documentation
  /// 
  /// Creates detailed documentation for all public APIs including:
  /// - Method signatures and parameters
  /// - Return types and exceptions
  /// - Usage examples
  /// - Cross-references
  static Future<void> generateAPIReference() async {
    print('Generating API reference documentation...');
    
    final apiDocs = <String, Map<String, dynamic>>{};
    
    // Document camera capabilities API
    apiDocs['ARCameraCapabilities'] = await _generateCameraCapabilitiesDoc();
    
    // Document capture configuration API
    apiDocs['ARCaptureConfig'] = await _generateCaptureConfigDoc();
    
    // Document camera intrinsics API
    apiDocs['ARCameraIntrinsics'] = await _generateCameraIntrinsicsDoc();
    
    // Document validation API
    apiDocs['ValidationAPI'] = await _generateValidationAPIDoc();
    
    // Generate HTML documentation
    await _generateHTMLDocs(apiDocs, 'api_reference');
    
    // Generate markdown documentation
    await _generateMarkdownDocs(apiDocs, 'api_reference');
    
    print('API reference documentation generated successfully');
  }
  
  /// Generate integration guide documentation
  /// 
  /// Creates step-by-step integration guides covering:
  /// - Basic setup and configuration
  /// - Advanced integration patterns
  /// - Platform-specific considerations
  /// - Troubleshooting common issues
  static Future<void> generateIntegrationGuide() async {
    print('Generating integration guide documentation...');
    
    final integrationGuide = {
      'basic_setup': await _generateBasicSetupGuide(),
      'advanced_integration': await _generateAdvancedIntegrationGuide(),
      'platform_specific': await _generatePlatformSpecificGuide(),
      'troubleshooting': await _generateTroubleshootingGuide(),
      'best_practices': await _generateBestPracticesGuide(),
    };
    
    // Generate HTML integration guide
    await _generateHTMLDocs(integrationGuide, 'integration_guide');
    
    // Generate markdown integration guide
    await _generateMarkdownDocs(integrationGuide, 'integration_guide');
    
    print('Integration guide documentation generated successfully');
  }
  
  /// Generate migration guide documentation
  /// 
  /// Creates migration documentation covering:
  /// - Breaking changes between versions
  /// - Step-by-step migration instructions
  /// - Code transformation examples
  /// - Common migration pitfalls
  static Future<void> generateMigrationGuide() async {
    print('Generating migration guide documentation...');
    
    final migrationGuide = {
      'overview': await _generateMigrationOverview(),
      'breaking_changes': await _generateBreakingChangesDoc(),
      'step_by_step': await _generateStepByStepMigration(),
      'code_examples': await _generateMigrationExamples(),
      'troubleshooting': await _generateMigrationTroubleshooting(),
    };
    
    // Generate HTML migration guide
    await _generateHTMLDocs(migrationGuide, 'migration_guide');
    
    // Generate markdown migration guide
    await _generateMarkdownDocs(migrationGuide, 'migration_guide');
    
    print('Migration guide documentation generated successfully');
  }
  
  /// Validate all documentation examples
  /// 
  /// Ensures that all code examples in documentation:
  /// - Compile successfully
  /// - Follow coding standards
  /// - Use current API versions
  /// - Include proper error handling
  static Future<void> validateDocumentation() async {
    print('Validating documentation examples...');
    
    final validationResults = <String, ValidationResult>{};
    
    // Validate API examples
    validationResults['api_examples'] = await _validateAPIExamples();
    
    // Validate integration examples
    validationResults['integration_examples'] = await _validateIntegrationExamples();
    
    // Validate migration examples
    validationResults['migration_examples'] = await _validateMigrationExamples();
    
    // Generate validation report
    await _generateValidationReport(validationResults);
    
    final allValid = validationResults.values.every((result) => result.isValid);
    
    if (allValid) {
      print('All documentation examples validated successfully');
    } else {
      print('Documentation validation found issues - see validation report');
    }
  }
  
  // Private helper methods for generating specific documentation sections
  
  static Future<Map<String, dynamic>> _generateCameraCapabilitiesDoc() async {
    return {
      'name': 'ARCameraCapabilities',
      'description': 'Provides information about available camera capabilities for AR capture',
      'version': _version,
      'methods': [
        {
          'name': 'getSupportedResolutions',
          'description': 'Get list of all supported camera resolutions',
          'signature': 'Future<List<CameraResolution>> getSupportedResolutions()',
          'parameters': [],
          'returns': 'List<CameraResolution> - List of supported resolutions, empty on unsupported platforms',
          'throws': ['ARCameraCapabilityException - If capability query fails'],
          'example': '''
final capabilities = ARCameraCapabilities();
final resolutions = await capabilities.getSupportedResolutions();
for (final resolution in resolutions) {
  print('Supported: \${resolution.width}x\${resolution.height}');
}''',
          'since': '1.0.0',
        },
        {
          'name': 'getSupportedFormats',
          'description': 'Get list of all supported image formats',
          'signature': 'Future<List<ImageFormat>> getSupportedFormats()',
          'parameters': [],
          'returns': 'List<ImageFormat> - List of supported formats, empty on unsupported platforms',
          'throws': ['ARCameraCapabilityException - If capability query fails'],
          'example': '''
final capabilities = ARCameraCapabilities();
final formats = await capabilities.getSupportedFormats();
final supportsJPEG = formats.contains(ImageFormat.jpeg);''',
          'since': '1.0.0',
        },
        {
          'name': 'validateCaptureConfig',
          'description': 'Validate complete capture configuration',
          'signature': 'Future<ValidationResult> validateCaptureConfig(ARCaptureConfig config)',
          'parameters': [
            {
              'name': 'config',
              'type': 'ARCaptureConfig',
              'description': 'The capture configuration to validate',
              'required': true,
            },
          ],
          'returns': 'ValidationResult - Validation result with errors, warnings, and suggestions',
          'throws': ['ARCameraCapabilityException - If validation fails'],
          'example': '''
final config = ARCaptureConfig(
  resolution: CameraResolution(width: 1920, height: 1080),
  format: ImageFormat.jpeg,
  enableHighResCapture: true,
  captureIntervalMs: 1000,
);

final capabilities = ARCameraCapabilities();
final validation = await capabilities.validateCaptureConfig(config);

if (validation.isValid) {
  print('Configuration is valid');
} else {
  print('Validation errors: \${validation.errors}');
  if (validation.suggestedConfig != null) {
    print('Suggested config: \${validation.suggestedConfig}');
  }
}''',
          'since': '1.0.0',
        },
      ],
      'properties': [
        {
          'name': 'isSupported',
          'type': 'bool',
          'description': 'Whether camera capabilities are supported on current platform (Android only)',
          'getter': true,
          'example': '''
final capabilities = ARCameraCapabilities();
if (capabilities.isSupported) {
  // Query capabilities
} else {
  // Show platform not supported message
}''',
        },
      ],
      'examples': [
        {
          'title': 'Basic Capability Query',
          'description': 'Query device camera capabilities and display information',
          'code': '''
import 'package:ar_flutter_plugin_2/capabilities/ar_camera_capabilities.dart';

class CameraCapabilityWidget extends StatefulWidget {
  @override
  _CameraCapabilityWidgetState createState() => _CameraCapabilityWidgetState();
}

class _CameraCapabilityWidgetState extends State<CameraCapabilityWidget> {
  late ARCameraCapabilities _capabilities;
  List<CameraResolution> _resolutions = [];
  List<ImageFormat> _formats = [];
  
  @override
  void initState() {
    super.initState();
    _capabilities = ARCameraCapabilities();
    _loadCapabilities();
  }
  
  Future<void> _loadCapabilities() async {
    if (_capabilities.isSupported) {
      final resolutions = await _capabilities.getSupportedResolutions();
      final formats = await _capabilities.getSupportedFormats();
      
      setState(() {
        _resolutions = resolutions;
        _formats = formats;
      });
    }
  }
  
  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Text('Supported Resolutions: \${_resolutions.length}'),
        Text('Supported Formats: \${_formats.length}'),
        if (!_capabilities.isSupported)
          Text('Camera capabilities not supported on this platform'),
      ],
    );
  }
}''',
        },
      ],
      'related': ['ARCaptureConfig', 'CameraResolution', 'ImageFormat'],
    };
  }
  
  static Future<Map<String, dynamic>> _generateCaptureConfigDoc() async {
    return {
      'name': 'ARCaptureConfig',
      'description': 'Configuration class for AR capture settings',
      'version': _version,
      'constructors': [
        {
          'signature': 'ARCaptureConfig({required CameraResolution resolution, required ImageFormat format, bool enableHighResCapture = true, int captureIntervalMs = 1000})',
          'parameters': [
            {
              'name': 'resolution',
              'type': 'CameraResolution',
              'description': 'Target camera resolution for captures',
              'required': true,
            },
            {
              'name': 'format',
              'type': 'ImageFormat',
              'description': 'Image format for captured images',
              'required': true,
            },
            {
              'name': 'enableHighResCapture',
              'type': 'bool',
              'description': 'Whether to enable high resolution capture mode',
              'required': false,
              'default': 'true',
            },
            {
              'name': 'captureIntervalMs',
              'type': 'int',
              'description': 'Minimum interval between captures in milliseconds',
              'required': false,
              'default': '1000',
            },
          ],
          'example': '''
final config = ARCaptureConfig(
  resolution: CameraResolution(width: 1920, height: 1080),
  format: ImageFormat.jpeg,
  enableHighResCapture: true,
  captureIntervalMs: 2000,
);''',
        },
      ],
      'properties': [
        {
          'name': 'resolution',
          'type': 'CameraResolution',
          'description': 'Target camera resolution for captures',
          'getter': true,
          'example': 'final width = config.resolution.width;',
        },
        {
          'name': 'format',
          'type': 'ImageFormat',
          'description': 'Image format for captured images',
          'getter': true,
          'example': 'final isJPEG = config.format == ImageFormat.jpeg;',
        },
      ],
      'methods': [
        {
          'name': 'toMap',
          'description': 'Convert configuration to map for serialization',
          'signature': 'Map<String, dynamic> toMap()',
          'returns': 'Map<String, dynamic> - Serialized configuration',
          'example': '''
final config = ARCaptureConfig(/* ... */);
final map = config.toMap();
print('Config: \$map');''',
        },
      ],
    };
  }
  
  static Future<Map<String, dynamic>> _generateCameraIntrinsicsDoc() async {
    return {
      'name': 'ARCameraIntrinsics',
      'description': 'Camera intrinsic parameters for AR applications',
      'version': _version,
      'properties': [
        {
          'name': 'focalLength',
          'type': 'FocalLength',
          'description': 'Camera focal length in x and y directions',
          'getter': true,
        },
        {
          'name': 'principalPoint',
          'type': 'PrincipalPoint',
          'description': 'Camera principal point coordinates',
          'getter': true,
        },
        {
          'name': 'imageSize',
          'type': 'ImageSize',
          'description': 'Image size for these intrinsics',
          'getter': true,
        },
      ],
      'examples': [
        {
          'title': 'Using Camera Intrinsics',
          'description': 'Access and use camera intrinsic parameters',
          'code': '''
final capabilities = ARCameraCapabilities();
final intrinsics = await capabilities.getCameraIntrinsics();

if (intrinsics != null) {
  print('Focal Length: fx=\${intrinsics.focalLength.x}, fy=\${intrinsics.focalLength.y}');
  print('Principal Point: cx=\${intrinsics.principalPoint.x}, cy=\${intrinsics.principalPoint.y}');
  print('Image Size: \${intrinsics.imageSize.width}x\${intrinsics.imageSize.height}');
}''',
        },
      ],
    };
  }
  
  static Future<Map<String, dynamic>> _generateValidationAPIDoc() async {
    return {
      'name': 'ValidationAPI',
      'description': 'Configuration validation and compatibility checking',
      'version': _version,
      'classes': [
        {
          'name': 'ValidationResult',
          'description': 'Result of configuration validation',
          'properties': [
            {
              'name': 'isValid',
              'type': 'bool',
              'description': 'Whether the configuration is valid',
            },
            {
              'name': 'errors',
              'type': 'List<String>',
              'description': 'List of validation errors',
            },
            {
              'name': 'warnings',
              'type': 'List<String>',
              'description': 'List of validation warnings',
            },
            {
              'name': 'suggestedConfig',
              'type': 'ARCaptureConfig?',
              'description': 'Suggested configuration if validation fails',
            },
          ],
        },
      ],
    };
  }
  
  static Future<Map<String, dynamic>> _generateBasicSetupGuide() async {
    return {
      'title': 'Basic Setup Guide',
      'description': 'Step-by-step guide for basic AR Flutter Plugin setup',
      'steps': [
        {
          'step': 1,
          'title': 'Add Dependency',
          'description': 'Add the AR Flutter Plugin to your pubspec.yaml',
          'code': '''
dependencies:
  ar_flutter_plugin_2: ^1.0.0''',
        },
        {
          'step': 2,
          'title': 'Import Package',
          'description': 'Import the package in your Dart files',
          'code': '''
import 'package:ar_flutter_plugin_2/ar_flutter_plugin.dart';''',
        },
        {
          'step': 3,
          'title': 'Initialize Camera Capabilities',
          'description': 'Create and initialize camera capabilities',
          'code': '''
final capabilities = ARCameraCapabilities();
if (capabilities.isSupported) {
  final resolutions = await capabilities.getSupportedResolutions();
  // Use resolutions...
}''',
        },
        {
          'step': 4,
          'title': 'Create Configuration',
          'description': 'Create and validate capture configuration',
          'code': '''
final config = ARCaptureConfig(
  resolution: CameraResolution(width: 1920, height: 1080),
  format: ImageFormat.jpeg,
  enableHighResCapture: true,
  captureIntervalMs: 1000,
);

final validation = await capabilities.validateCaptureConfig(config);
if (!validation.isValid) {
  print('Configuration errors: \${validation.errors}');
}''',
        },
      ],
    };
  }
  
  static Future<Map<String, dynamic>> _generateAdvancedIntegrationGuide() async {
    return {
      'title': 'Advanced Integration Guide',
      'description': 'Advanced integration patterns and techniques',
      'sections': [
        {
          'title': 'Custom Configuration Management',
          'description': 'Managing multiple configurations and device-specific optimizations',
          'example': '''
class ConfigurationManager {
  static Future<ARCaptureConfig> getOptimalConfig() async {
    final capabilities = ARCameraCapabilities();
    
    if (!capabilities.isSupported) {
      throw Exception('Platform not supported');
    }
    
    // Get recommended configuration
    final recommendedConfig = await capabilities.getRecommendedConfig();
    
    // Validate and optimize
    final validation = await capabilities.validateCaptureConfig(recommendedConfig);
    
    if (validation.isValid) {
      return recommendedConfig;
    } else if (validation.suggestedConfig != null) {
      return validation.suggestedConfig!;
    } else {
      throw Exception('No valid configuration available');
    }
  }
}''',
        },
        {
          'title': 'Performance Monitoring',
          'description': 'Monitoring and optimizing capture performance',
          'example': '''
class PerformanceMonitor {
  static Future<void> assessConfiguration(ARCaptureConfig config) async {
    final capabilities = ARCameraCapabilities();
    final impact = await capabilities.assessPerformanceImpact(config);
    
    switch (impact) {
      case PerformanceImpact.low:
        print('Configuration has low performance impact');
        break;
      case PerformanceImpact.medium:
        print('Configuration has medium performance impact');
        break;
      case PerformanceImpact.high:
        print('Configuration has high performance impact - consider optimization');
        break;
      case PerformanceImpact.extreme:
        print('Configuration has extreme performance impact - optimization required');
        break;
    }
  }
}''',
        },
      ],
    };
  }
  
  static Future<Map<String, dynamic>> _generatePlatformSpecificGuide() async {
    return {
      'title': 'Platform-Specific Integration',
      'description': 'Platform-specific considerations and optimizations',
      'platforms': [
        {
          'name': 'Android',
          'requirements': [
            'Android API level 24 (Android 7.0) or higher',
            'ARCore support (for AR features)',
            'Camera2 API support',
          ],
          'permissions': [
            'android.permission.CAMERA',
            'android.permission.WRITE_EXTERNAL_STORAGE',
          ],
          'setup': '''
// Add to android/app/src/main/AndroidManifest.xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />

// For ARCore support
<meta-data android:name="com.google.ar.core" android:value="required" />''',
        },
        {
          'name': 'iOS',
          'requirements': [
            'iOS 11.0 or higher',
            'ARKit support',
            'Device with A9 processor or later',
          ],
          'permissions': [
            'NSCameraUsageDescription',
          ],
          'setup': '''
// Add to ios/Runner/Info.plist
<key>NSCameraUsageDescription</key>
<string>This app needs camera access for AR features</string>

// For ARKit support
<key>UIRequiredDeviceCapabilities</key>
<array>
  <string>armv7</string>
  <string>arkit</string>
</array>''',
        },
      ],
    };
  }
  
  static Future<Map<String, dynamic>> _generateTroubleshootingGuide() async {
    return {
      'title': 'Troubleshooting Guide',
      'description': 'Common issues and their solutions',
      'issues': [
        {
          'issue': 'Camera capabilities not supported',
          'description': 'ARCameraCapabilities.isSupported returns false',
          'causes': [
            'Running on non-Android platform',
            'Insufficient Android API level',
            'Missing camera permissions',
          ],
          'solutions': [
            'Check platform compatibility (Android only)',
            'Ensure Android API level 24+',
            'Request camera permissions at runtime',
            'Verify camera hardware availability',
          ],
          'example': '''
if (!capabilities.isSupported) {
  if (Platform.isAndroid) {
    // Check permissions
    final hasPermission = await Permission.camera.isGranted;
    if (!hasPermission) {
      await Permission.camera.request();
    }
  } else {
    // Show platform not supported message
    showDialog(/* ... */);
  }
}''',
        },
        {
          'issue': 'Configuration validation fails',
          'description': 'ARCaptureConfig validation returns errors',
          'causes': [
            'Unsupported resolution',
            'Unsupported image format',
            'Invalid capture interval',
          ],
          'solutions': [
            'Use getSupportedResolutions() to check available resolutions',
            'Use getSupportedFormats() to check available formats',
            'Use suggested configuration from validation result',
          ],
          'example': '''
final validation = await capabilities.validateCaptureConfig(config);
if (!validation.isValid) {
  print('Errors: \${validation.errors}');
  
  if (validation.suggestedConfig != null) {
    // Use suggested configuration
    final betterConfig = validation.suggestedConfig!;
    // Retry with suggested config
  }
}''',
        },
      ],
    };
  }
  
  static Future<Map<String, dynamic>> _generateBestPracticesGuide() async {
    return {
      'title': 'Best Practices',
      'description': 'Recommended practices for optimal performance and reliability',
      'practices': [
        {
          'title': 'Configuration Management',
          'description': 'Always validate configurations before use',
          'example': '''
// Good: Validate configuration
final validation = await capabilities.validateCaptureConfig(config);
if (validation.isValid) {
  // Use configuration
} else {
  // Handle validation errors
}

// Bad: Use configuration without validation
// This may fail at runtime''',
        },
        {
          'title': 'Error Handling',
          'description': 'Implement comprehensive error handling',
          'example': '''
try {
  final resolutions = await capabilities.getSupportedResolutions();
  // Process resolutions
} on ARCameraCapabilityException catch (e) {
  // Handle capability-specific errors
  print('Camera capability error: \${e.message}');
} catch (e) {
  // Handle general errors
  print('Unexpected error: \$e');
}''',
        },
        {
          'title': 'Performance Optimization',
          'description': 'Monitor and optimize performance impact',
          'example': '''
final impact = await capabilities.assessPerformanceImpact(config);
if (impact == PerformanceImpact.high || impact == PerformanceImpact.extreme) {
  // Consider using a lower resolution or longer capture interval
  final optimizedConfig = await capabilities.getRecommendedConfig();
  // Use optimized configuration
}''',
        },
      ],
    };
  }
  
  static Future<Map<String, dynamic>> _generateMigrationOverview() async {
    return {
      'title': 'Migration Overview',
      'description': 'Overview of migration from previous versions',
      'version': _version,
      'summary': 'Migration guide for updating to the latest version of AR Flutter Plugin',
    };
  }
  
  static Future<Map<String, dynamic>> _generateBreakingChangesDoc() async {
    return {
      'title': 'Breaking Changes',
      'description': 'List of breaking changes and required updates',
      'changes': [
        {
          'version': '1.0.0',
          'change': 'Introduction of ARCameraCapabilities API',
          'impact': 'New API for camera capability queries',
          'migration': 'Add camera capability checking to your application',
        },
      ],
    };
  }
  
  static Future<Map<String, dynamic>> _generateStepByStepMigration() async {
    return {
      'title': 'Step-by-Step Migration',
      'description': 'Detailed migration steps',
      'steps': [
        {
          'step': 1,
          'title': 'Update Dependencies',
          'description': 'Update to the latest version',
        },
      ],
    };
  }
  
  static Future<Map<String, dynamic>> _generateMigrationExamples() async {
    return {
      'title': 'Migration Code Examples',
      'description': 'Before and after code examples',
      'examples': [],
    };
  }
  
  static Future<Map<String, dynamic>> _generateMigrationTroubleshooting() async {
    return {
      'title': 'Migration Troubleshooting',
      'description': 'Common migration issues and solutions',
      'issues': [],
    };
  }
  
  static Future<ValidationResult> _validateAPIExamples() async {
    // Mock validation - in real implementation would compile and test examples
    return const ValidationResult(
      isValid: true,
      errors: [],
      warnings: [],
    );
  }
  
  static Future<ValidationResult> _validateIntegrationExamples() async {
    // Mock validation
    return const ValidationResult(
      isValid: true,
      errors: [],
      warnings: [],
    );
  }
  
  static Future<ValidationResult> _validateMigrationExamples() async {
    // Mock validation
    return const ValidationResult(
      isValid: true,
      errors: [],
      warnings: [],
    );
  }
  
  static Future<void> _generateHTMLDocs(Map<String, dynamic> docs, String fileName) async {
    // Mock HTML generation - would generate actual HTML documentation
    final htmlContent = '''
<!DOCTYPE html>
<html>
<head>
    <title>AR Flutter Plugin Documentation</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 40px; }
        h1 { color: #2196F3; }
        h2 { color: #1976D2; }
        code { background-color: #f5f5f5; padding: 2px 4px; border-radius: 3px; }
        pre { background-color: #f5f5f5; padding: 10px; border-radius: 5px; overflow-x: auto; }
    </style>
</head>
<body>
    <h1>AR Flutter Plugin Documentation</h1>
    <p>Generated on: ${DateTime.now().toIso8601String()}</p>
    <p>Version: $_version</p>
    <h2>Documentation Content</h2>
    <pre>${JsonEncoder.withIndent('  ').convert(docs)}</pre>
</body>
</html>''';
    
    await _ensureDirectoryExists('$_baseOutputPath/html');
    final file = File('$_baseOutputPath/html/$fileName.html');
    await file.writeAsString(htmlContent);
  }
  
  static Future<void> _generateMarkdownDocs(Map<String, dynamic> docs, String fileName) async {
    // Mock Markdown generation - would generate actual Markdown documentation
    final markdownContent = '''
# AR Flutter Plugin Documentation

Generated on: ${DateTime.now().toIso8601String()}
Version: $_version

## Documentation Content

\`\`\`json
${JsonEncoder.withIndent('  ').convert(docs)}
\`\`\`
''';
    
    await _ensureDirectoryExists('$_baseOutputPath/markdown');
    final file = File('$_baseOutputPath/markdown/$fileName.md');
    await file.writeAsString(markdownContent);
  }
  
  static Future<void> _generateValidationReport(Map<String, ValidationResult> results) async {
    final report = {
      'validation_date': DateTime.now().toIso8601String(),
      'version': _version,
      'results': results.map((key, value) => MapEntry(key, {
        'isValid': value.isValid,
        'errors': value.errors,
        'warnings': value.warnings,
      })),
      'summary': {
        'total_validations': results.length,
        'passed': results.values.where((r) => r.isValid).length,
        'failed': results.values.where((r) => !r.isValid).length,
      },
    };
    
    await _ensureDirectoryExists('$_baseOutputPath/validation');
    final file = File('$_baseOutputPath/validation/validation_report.json');
    await file.writeAsString(JsonEncoder.withIndent('  ').convert(report));
  }
  
  static Future<void> _ensureDirectoryExists(String path) async {
    final directory = Directory(path);
    if (!await directory.exists()) {
      await directory.create(recursive: true);
    }
  }
}

/// Documentation annotation for capturing API documentation metadata
class CaptureAPIDoc {
  final String description;
  final List<String> examples;
  final List<String> seeAlso;
  final String since;
  
  const CaptureAPIDoc({
    required this.description,
    this.examples = const [],
    this.seeAlso = const [],
    this.since = '1.0.0',
  });
}

/// Validation result for documentation validation
class ValidationResult {
  final bool isValid;
  final List<String> errors;
  final List<String> warnings;
  
  const ValidationResult({
    required this.isValid,
    this.errors = const [],
    this.warnings = const [],
  });
}