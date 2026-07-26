import 'package:flutter/material.dart';
import 'package:ar_flutter_plugin_2/ar_flutter_plugin.dart';

/// Configuration validation example demonstrating:
/// - Creating different ARCaptureConfig configurations
/// - Validating configurations with device capabilities
/// - Showing validation results, errors, and warnings
/// - Displaying recommended and optimal configurations
/// - Performance impact assessment
class CameraConfigValidationExample extends StatefulWidget {
  const CameraConfigValidationExample({super.key});

  @override
  State<CameraConfigValidationExample> createState() => _CameraConfigValidationExampleState();
}

class _CameraConfigValidationExampleState extends State<CameraConfigValidationExample> {
  final ARCameraCapabilities _capabilities = ARCameraCapabilities();
  
  List<ConfigTestCase> _testCases = [];
  bool _isLoading = true;
  String? _errorMessage;
  ARCaptureConfig? _recommendedConfig;
  
  @override
  void initState() {
    super.initState();
    _loadTestCases();
  }

  Future<void> _loadTestCases() async {
    try {
      setState(() {
        _isLoading = true;
        _errorMessage = null;
      });

      // Get recommended configuration
      final recommendedConfig = await _capabilities.getRecommendedConfig();
      
      // Create test configurations
      final testConfigs = [
        ARCaptureConfig(
          enableHighResCapture: true,
          captureIntervalMs: 1000,
          resolution: const CameraResolution(width: 3840, height: 2160), // 4K
          format: ImageFormat.rawJpeg,
        ),
        ARCaptureConfig(
          enableHighResCapture: true,
          captureIntervalMs: 5000,
          resolution: const CameraResolution(width: 1920, height: 1080), // 1080p
          format: ImageFormat.jpeg,
        ),
        ARCaptureConfig(
          enableHighResCapture: false,
          captureIntervalMs: 500, // Very frequent
          resolution: const CameraResolution(width: 1280, height: 720), // 720p
          format: ImageFormat.jpeg,
        ),
        recommendedConfig, // Device-recommended config
      ];

      final testCases = <ConfigTestCase>[];
      
      // Validate each configuration
      for (int i = 0; i < testConfigs.length; i++) {
        final config = testConfigs[i];
        final validation = await _capabilities.validateCaptureConfig(config);
        final performanceImpact = await _capabilities.assessPerformanceImpact(config);
        final isOptimal = await _capabilities.isOptimalConfig(config);
        
        String name;
        String description;
        switch (i) {
          case 0:
            name = 'High Performance';
            description = '4K resolution, RAW format, frequent capture';
            break;
          case 1:
            name = 'Balanced Quality';
            description = '1080p resolution, JPEG format, moderate interval';
            break;
          case 2:
            name = 'High Frequency';
            description = '720p resolution, very frequent capture';
            break;
          case 3:
            name = 'Device Recommended';
            description = 'Automatically recommended by device capabilities';
            break;
          default:
            name = 'Test Config $i';
            description = 'Test configuration';
        }
        
        testCases.add(ConfigTestCase(
          name: name,
          description: description,
          config: config,
          validation: validation,
          performanceImpact: performanceImpact,
          isOptimal: isOptimal,
        ));
      }

      setState(() {
        _testCases = testCases;
        _recommendedConfig = recommendedConfig;
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _errorMessage = 'Failed to load test cases: ${e.toString()}';
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Config Validation'),
        actions: [
          IconButton(
            onPressed: _loadTestCases,
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh validation',
          ),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (!_capabilities.isSupported) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.warning, size: 64, color: Colors.orange),
            SizedBox(height: 16),
            Text(
              'Camera capabilities not supported on this platform',
              style: TextStyle(fontSize: 18),
              textAlign: TextAlign.center,
            ),
            SizedBox(height: 8),
            Text(
              'Currently supported on Android only',
              style: TextStyle(fontSize: 14, color: Colors.grey),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      );
    }

    if (_isLoading) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('Validating configurations...'),
          ],
        ),
      );
    }

    if (_errorMessage != null) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.error, size: 64, color: Colors.red),
            const SizedBox(height: 16),
            Text(
              _errorMessage!,
              style: const TextStyle(fontSize: 16),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: _loadTestCases,
              child: const Text('Retry'),
            ),
          ],
        ),
      );
    }

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _buildSummaryCard(),
        const SizedBox(height: 16),
        ..._testCases.map((testCase) => Padding(
          padding: const EdgeInsets.only(bottom: 16),
          child: _buildTestCaseCard(testCase),
        )),
      ],
    );
  }

  Widget _buildSummaryCard() {
    final validConfigs = _testCases.where((tc) => tc.validation.isValid).length;
    final optimalConfigs = _testCases.where((tc) => tc.isOptimal).length;
    
    return Card(
      color: Colors.blue.shade50,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.assessment, color: Colors.blue),
                const SizedBox(width: 8),
                const Text(
                  'Validation Summary',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: _buildSummaryItem(
                    'Valid Configs',
                    '$validConfigs / ${_testCases.length}',
                    validConfigs == _testCases.length ? Colors.green : Colors.orange,
                  ),
                ),
                Expanded(
                  child: _buildSummaryItem(
                    'Optimal Configs',
                    '$optimalConfigs / ${_testCases.length}',
                    optimalConfigs > 0 ? Colors.green : Colors.red,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSummaryItem(String title, String value, Color color) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withOpacity(0.1),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: color.withOpacity(0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: TextStyle(fontSize: 12, color: color.withOpacity(0.8)),
          ),
          const SizedBox(height: 4),
          Text(
            value,
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.bold,
              color: color,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTestCaseCard(ConfigTestCase testCase) {
    final config = testCase.config;
    final validation = testCase.validation;
    
    Color borderColor;
    Color headerColor;
    IconData headerIcon;
    
    if (!validation.isValid) {
      borderColor = Colors.red;
      headerColor = Colors.red;
      headerIcon = Icons.error;
    } else if (validation.hasWarnings) {
      borderColor = Colors.orange;
      headerColor = Colors.orange;
      headerIcon = Icons.warning;
    } else {
      borderColor = Colors.green;
      headerColor = Colors.green;
      headerIcon = Icons.check_circle;
    }

    return Card(
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: borderColor, width: 2),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: headerColor.withOpacity(0.1),
              borderRadius: const BorderRadius.only(
                topLeft: Radius.circular(10),
                topRight: Radius.circular(10),
              ),
            ),
            child: Row(
              children: [
                Icon(headerIcon, color: headerColor),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        testCase.name,
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                          color: headerColor,
                        ),
                      ),
                      Text(
                        testCase.description,
                        style: TextStyle(
                          fontSize: 12,
                          color: headerColor.withOpacity(0.8),
                        ),
                      ),
                    ],
                  ),
                ),
                if (testCase.isOptimal)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    decoration: BoxDecoration(
                      color: Colors.blue.withOpacity(0.2),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: Colors.blue),
                    ),
                    child: const Text(
                      'OPTIMAL',
                      style: TextStyle(
                        fontSize: 10,
                        fontWeight: FontWeight.bold,
                        color: Colors.blue,
                      ),
                    ),
                  ),
              ],
            ),
          ),
          
          // Configuration details
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildConfigDetail('Resolution', config.resolution.toString()),
                _buildConfigDetail('Format', config.format.name.toUpperCase()),
                _buildConfigDetail('High Resolution', config.enableHighResCapture ? 'Enabled' : 'Disabled'),
                _buildConfigDetail('Capture Interval', '${config.captureIntervalMs}ms'),
                _buildConfigDetail('Performance Impact', _getPerformanceImpactText(testCase.performanceImpact)),
                
                if (validation.hasErrors) ...[
                  const SizedBox(height: 16),
                  _buildValidationSection('Errors', validation.errors, Colors.red),
                ],
                
                if (validation.hasWarnings) ...[
                  const SizedBox(height: 16),
                  _buildValidationSection('Warnings', validation.warnings, Colors.orange),
                ],
                
                if (validation.suggestedConfig != null) ...[
                  const SizedBox(height: 16),
                  _buildSuggestedConfigSection(validation.suggestedConfig!),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildConfigDetail(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 120,
            child: Text(
              '$label:',
              style: const TextStyle(fontWeight: FontWeight.w500),
            ),
          ),
          Expanded(
            child: Text(value),
          ),
        ],
      ),
    );
  }

  Widget _buildValidationSection(String title, List<String> items, Color color) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withOpacity(0.1),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: color.withOpacity(0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: TextStyle(
              fontWeight: FontWeight.bold,
              color: color,
            ),
          ),
          const SizedBox(height: 8),
          ...items.map((item) => Padding(
            padding: const EdgeInsets.only(bottom: 4),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('• ', style: TextStyle(color: color)),
                Expanded(child: Text(item)),
              ],
            ),
          )),
        ],
      ),
    );
  }

  Widget _buildSuggestedConfigSection(ARCaptureConfig suggestedConfig) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.blue.withOpacity(0.1),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.blue.withOpacity(0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Suggested Configuration',
            style: TextStyle(
              fontWeight: FontWeight.bold,
              color: Colors.blue,
            ),
          ),
          const SizedBox(height: 8),
          _buildConfigDetail('Resolution', suggestedConfig.resolution.toString()),
          _buildConfigDetail('Format', suggestedConfig.format.name.toUpperCase()),
          _buildConfigDetail('High Resolution', suggestedConfig.enableHighResCapture ? 'Enabled' : 'Disabled'),
          _buildConfigDetail('Capture Interval', '${suggestedConfig.captureIntervalMs}ms'),
        ],
      ),
    );
  }

  String _getPerformanceImpactText(PerformanceImpact impact) {
    switch (impact) {
      case PerformanceImpact.low:
        return 'Low';
      case PerformanceImpact.medium:
        return 'Medium';
      case PerformanceImpact.high:
        return 'High';
      case PerformanceImpact.extreme:
        return 'Extreme';
    }
  }
}

class ConfigTestCase {
  final String name;
  final String description;
  final ARCaptureConfig config;
  final ValidationResult validation;
  final PerformanceImpact performanceImpact;
  final bool isOptimal;

  ConfigTestCase({
    required this.name,
    required this.description,
    required this.config,
    required this.validation,
    required this.performanceImpact,
    required this.isOptimal,
  });
}
