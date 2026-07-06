import 'package:flutter/material.dart';
import 'package:ar_flutter_plugin_2/ar_flutter_plugin.dart';

/// Phase 6: Comprehensive demonstration of integrated workflow implementation
/// 
/// This example showcases:
/// - AR Session Lifecycle Integration
/// - Shared Camera Manager Integration  
/// - Memory Cache System Integration
/// - Configuration Compatibility System
/// - End-to-End Capture Workflow
class Phase6IntegratedWorkflowExample extends StatefulWidget {
  const Phase6IntegratedWorkflowExample({Key? key}) : super(key: key);

  @override
  State<Phase6IntegratedWorkflowExample> createState() => _Phase6IntegratedWorkflowExampleState();
}

class _Phase6IntegratedWorkflowExampleState extends State<Phase6IntegratedWorkflowExample> {
  // Core managers
  SharedCameraManager? _sharedCameraManager;
  CaptureMemoryManager? _memoryManager;
  ConfigurationCompatibilityChecker? _compatibilityChecker;
  IntegratedCaptureWorkflow? _workflow;
  
  // State management
  bool _isInitialized = false;
  bool _isWorkflowRunning = false;
  String _statusMessage = 'Not initialized';
  double _workflowProgress = 0.0;
  
  // Workflow results
  CaptureWorkflowResult? _lastWorkflowResult;
  List<ARCaptureResult> _captureResults = [];
  
  // Configuration
  late ARConfiguration _arConfig;
  late ARCaptureConfig _captureConfig;
  
  // UI state
  int _selectedCaptureCount = 3;
  Duration _selectedInterval = const Duration(seconds: 2);
  bool _enableRetry = true;
  bool _enablePerformanceMonitoring = true;
  
  // Statistics
  MemoryStats? _memoryStats;
  CameraResourceState? _cameraState;
  CompatibilityResult? _compatibilityResult;
  
  @override
  void initState() {
    super.initState();
    _setupConfigurations();
  }

  void _setupConfigurations() {
    // Setup AR configuration with integrated capture
    _captureConfig = ARCaptureConfig(
      resolution: const CameraResolution(width: 1920, height: 1080),
      format: ImageFormat.jpeg,
      captureIntervalMs: 1000, // 1 second
      maxCacheSize: 15, // 15 images
    );
    
    _arConfig = ARConfiguration(
      enableCapture: true,
      captureConfig: _captureConfig,
      planeDetectionConfig: PlaneDetectionConfig.horizontal,
      showFeaturePoints: true,
      showPlanes: true,
      debug: true,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Phase 6: Integrated Workflow'),
        backgroundColor: Colors.deepPurple,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _buildHeaderSection(),
            const SizedBox(height: 20),
            _buildInitializationSection(),
            const SizedBox(height: 20),
            _buildConfigurationSection(),
            const SizedBox(height: 20),
            _buildWorkflowControlSection(),
            const SizedBox(height: 20),
            _buildStatusSection(),
            const SizedBox(height: 20),
            _buildStatisticsSection(),
            const SizedBox(height: 20),
            _buildResultsSection(),
          ],
        ),
      ),
    );
  }

  Widget _buildHeaderSection() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.integration_instructions, color: Colors.deepPurple, size: 28),
                SizedBox(width: 10),
                Text(
                  'Integrated Workflow System',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                ),
              ],
            ),
            const SizedBox(height: 10),
            const Text(
              'Comprehensive end-to-end capture workflow that integrates AR session management, '
              'shared camera resources, memory optimization, and configuration compatibility.',
              style: TextStyle(fontSize: 14, color: Colors.grey),
            ),
            const SizedBox(height: 15),
            _buildFeatureChips(),
          ],
        ),
      ),
    );
  }

  Widget _buildFeatureChips() {
    final features = [
      'AR Lifecycle Integration',
      'Shared Camera Management',
      'Memory Optimization',
      'Configuration Compatibility',
      'End-to-End Workflow',
    ];
    
    return Wrap(
      spacing: 8.0,
      runSpacing: 4.0,
      children: features.map((feature) => Chip(
        label: Text(feature, style: const TextStyle(fontSize: 12)),
        backgroundColor: Colors.deepPurple.withOpacity(0.1),
      )).toList(),
    );
  }

  Widget _buildInitializationSection() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.settings, color: Colors.blue),
                SizedBox(width: 8),
                Text('System Initialization', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
              ],
            ),
            const SizedBox(height: 15),
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _isInitialized ? null : _initializeSystem,
                    icon: const Icon(Icons.power_settings_new),
                    label: const Text('Initialize System'),
                    style: ElevatedButton.styleFrom(backgroundColor: Colors.green),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: !_isInitialized ? null : _shutdownSystem,
                    icon: const Icon(Icons.power_off),
                    label: const Text('Shutdown'),
                    style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Text(
              'Status: $_statusMessage',
              style: TextStyle(
                color: _isInitialized ? Colors.green : Colors.orange,
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildConfigurationSection() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.tune, color: Colors.orange),
                SizedBox(width: 8),
                Text('Workflow Configuration', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
              ],
            ),
            const SizedBox(height: 15),
            
            // Capture count selector
            Row(
              children: [
                const Text('Capture Count:', style: TextStyle(fontWeight: FontWeight.w500)),
                const SizedBox(width: 10),
                Expanded(
                  child: Slider(
                    value: _selectedCaptureCount.toDouble(),
                    min: 1,
                    max: 10,
                    divisions: 9,
                    label: _selectedCaptureCount.toString(),
                    onChanged: _isWorkflowRunning ? null : (value) {
                      setState(() {
                        _selectedCaptureCount = value.toInt();
                      });
                    },
                  ),
                ),
              ],
            ),
            
            // Interval selector
            Row(
              children: [
                const Text('Interval (sec):', style: TextStyle(fontWeight: FontWeight.w500)),
                const SizedBox(width: 10),
                Expanded(
                  child: Slider(
                    value: _selectedInterval.inSeconds.toDouble(),
                    min: 1,
                    max: 10,
                    divisions: 9,
                    label: _selectedInterval.inSeconds.toString(),
                    onChanged: _isWorkflowRunning ? null : (value) {
                      setState(() {
                        _selectedInterval = Duration(seconds: value.toInt());
                      });
                    },
                  ),
                ),
              ],
            ),
            
            // Options
            Row(
              children: [
                Expanded(
                  child: CheckboxListTile(
                    title: const Text('Enable Retry', style: TextStyle(fontSize: 14)),
                    value: _enableRetry,
                    onChanged: _isWorkflowRunning ? null : (value) {
                      setState(() {
                        _enableRetry = value ?? true;
                      });
                    },
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                  ),
                ),
                Expanded(
                  child: CheckboxListTile(
                    title: const Text('Performance Monitoring', style: TextStyle(fontSize: 14)),
                    value: _enablePerformanceMonitoring,
                    onChanged: _isWorkflowRunning ? null : (value) {
                      setState(() {
                        _enablePerformanceMonitoring = value ?? true;
                      });
                    },
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                  ),
                ),
              ],
            ),
            
            // Compatibility check
            if (_compatibilityResult != null) ...[
              const SizedBox(height: 10),
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: _compatibilityResult!.isCompatible ? Colors.green.withOpacity(0.1) : Colors.orange.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          _compatibilityResult!.isCompatible ? Icons.check_circle : Icons.warning,
                          color: _compatibilityResult!.isCompatible ? Colors.green : Colors.orange,
                          size: 16,
                        ),
                        const SizedBox(width: 5),
                        Text(
                          'Compatibility: ${(_compatibilityResult!.compatibilityScore * 100).toStringAsFixed(1)}%',
                          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
                        ),
                      ],
                    ),
                    if (_compatibilityResult!.warnings.isNotEmpty) ...[
                      const SizedBox(height: 5),
                      ...(_compatibilityResult!.warnings.take(2).map((warning) => Text(
                        '• $warning',
                        style: const TextStyle(fontSize: 12, color: Colors.orange),
                      ))),
                    ],
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildWorkflowControlSection() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.play_circle, color: Colors.purple),
                SizedBox(width: 8),
                Text('Workflow Control', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
              ],
            ),
            const SizedBox(height: 15),
            
            // Progress indicator
            if (_isWorkflowRunning) ...[
              LinearProgressIndicator(
                value: _workflowProgress,
                backgroundColor: Colors.grey[300],
                valueColor: const AlwaysStoppedAnimation<Color>(Colors.purple),
              ),
              const SizedBox(height: 8),
              Text(
                'Progress: ${(_workflowProgress * 100).toStringAsFixed(1)}%',
                style: const TextStyle(fontSize: 14),
              ),
              const SizedBox(height: 15),
            ],
            
            // Control buttons
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: (_isInitialized && !_isWorkflowRunning) ? _startWorkflow : null,
                    icon: const Icon(Icons.play_arrow),
                    label: const Text('Start Workflow'),
                    style: ElevatedButton.styleFrom(backgroundColor: Colors.purple),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _isWorkflowRunning ? _cancelWorkflow : null,
                    icon: const Icon(Icons.stop),
                    label: const Text('Cancel'),
                    style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusSection() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.info, color: Colors.blue),
                SizedBox(width: 8),
                Text('System Status', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
              ],
            ),
            const SizedBox(height: 15),
            
            _buildStatusRow('Camera Manager', _sharedCameraManager != null ? 'Ready' : 'Not initialized'),
            _buildStatusRow('Memory Manager', _memoryManager != null ? 'Active' : 'Not initialized'),
            _buildStatusRow('Compatibility Checker', _compatibilityChecker != null ? 'Ready' : 'Not initialized'),
            _buildStatusRow('Workflow Engine', _workflow != null ? 'Ready' : 'Not initialized'),
            
            if (_cameraState != null) ...[
              const SizedBox(height: 10),
              _buildStatusRow('Camera Mode', _cameraState!.currentMode.name.toUpperCase()),
              _buildStatusRow('Shared Mode', _cameraState!.isSharedModeActive ? 'Active' : 'Inactive'),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildStatusRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: const TextStyle(fontWeight: FontWeight.w500)),
          Text(value, style: TextStyle(
            color: value.contains('Not') ? Colors.red : Colors.green,
            fontWeight: FontWeight.w500,
          )),
        ],
      ),
    );
  }

  Widget _buildStatisticsSection() {
    if (_memoryStats == null) return const SizedBox.shrink();
    
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.analytics, color: Colors.green),
                SizedBox(width: 8),
                Text('Performance Statistics', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
              ],
            ),
            const SizedBox(height: 15),
            
            Row(
              children: [
                Expanded(
                  child: _buildStatCard('Memory Usage', '${(_memoryStats!.totalMemoryUsed / (1024 * 1024)).toStringAsFixed(1)} MB'),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: _buildStatCard('Cache Hit Rate', '${_memoryStats!.cacheHitRate}%'),
                ),
              ],
            ),
            
            const SizedBox(height: 10),
            
            Row(
              children: [
                Expanded(
                  child: _buildStatCard('Total Entries', _memoryStats!.totalEntries.toString()),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: _buildStatCard('Buffer Count', _memoryStats!.bufferCount.toString()),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatCard(String label, String value) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.grey[100],
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: const TextStyle(fontSize: 12, color: Colors.grey)),
          const SizedBox(height: 4),
          Text(value, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }

  Widget _buildResultsSection() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.photo_library, color: Colors.indigo),
                SizedBox(width: 8),
                Text('Workflow Results', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
              ],
            ),
            const SizedBox(height: 15),
            
            if (_lastWorkflowResult != null) ...[
              _buildResultSummary(),
              const SizedBox(height: 15),
            ],
            
            if (_captureResults.isNotEmpty) ...[
              const Text('Recent Captures:', style: TextStyle(fontWeight: FontWeight.w500)),
              const SizedBox(height: 10),
              SizedBox(
                height: 120,
                child: ListView.builder(
                  scrollDirection: Axis.horizontal,
                  itemCount: _captureResults.length,
                  itemBuilder: (context, index) {
                    final result = _captureResults[index];
                    return _buildCaptureCard(result, index);
                  },
                ),
              ),
            ] else ...[
              const Center(
                child: Text(
                  'No captures yet. Run a workflow to see results.',
                  style: TextStyle(color: Colors.grey),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildResultSummary() {
    final result = _lastWorkflowResult!;
    
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: result.isSuccessful ? Colors.green.withOpacity(0.1) : Colors.red.withOpacity(0.1),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                result.isSuccessful ? Icons.check_circle : Icons.error,
                color: result.isSuccessful ? Colors.green : Colors.red,
                size: 18,
              ),
              const SizedBox(width: 8),
              Text(
                'Workflow ${result.isSuccessful ? 'Completed' : 'Failed'}',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text('Successful Captures: ${result.stats.successfulCaptures}/${result.stats.totalCaptures}'),
          Text('Duration: ${result.stats.totalDuration.inSeconds}s'),
          Text('Average Capture Time: ${result.stats.averageCaptureTime.toStringAsFixed(0)}ms'),
          if (result.warnings.isNotEmpty)
            Text('Warnings: ${result.warnings.length}', style: const TextStyle(color: Colors.orange)),
          if (result.errors.isNotEmpty)
            Text('Errors: ${result.errors.length}', style: const TextStyle(color: Colors.red)),
        ],
      ),
    );
  }

  Widget _buildCaptureCard(ARCaptureResult result, int index) {
    return Container(
      width: 120,
      margin: const EdgeInsets.only(right: 10),
      decoration: BoxDecoration(
        border: Border.all(color: Colors.grey[300]!),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        children: [
          Expanded(
            child: Container(
              decoration: BoxDecoration(
                color: Colors.grey[200],
                borderRadius: const BorderRadius.vertical(top: Radius.circular(8)),
              ),
              child: const Center(
                child: Icon(Icons.image, size: 40, color: Colors.grey),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(8.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Capture ${index + 1}', style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 12)),
                Text('${result.resolution}', style: const TextStyle(fontSize: 10, color: Colors.grey)),
                Text('${result.sizeInMB.toStringAsFixed(1)} MB', style: const TextStyle(fontSize: 10, color: Colors.grey)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _initializeSystem() async {
    setState(() {
      _statusMessage = 'Initializing system...';
    });

    try {
      // Initialize managers
      _compatibilityChecker = ConfigurationCompatibilityChecker.instance;
      await _compatibilityChecker!.initialize(debug: true);
      
      _memoryManager = CaptureMemoryManager.instance;
      await _memoryManager!.initializeWithConfig(_captureConfig);
      
      _sharedCameraManager = SharedCameraManager.instance;
      await _sharedCameraManager!.initializeWithConfig(
        cameraId: 'default',
        captureConfig: _captureConfig,
        arConfig: _arConfig,
      );
      
      // Check configuration compatibility
      _compatibilityResult = await _compatibilityChecker!.checkCompatibility(
        arConfig: _arConfig,
        captureConfig: _captureConfig,
      );
      
      // Initialize workflow
      _workflow = IntegratedCaptureWorkflow(
        arConfig: _arConfig,
        captureConfig: _captureConfig,
      );
      
      // Setup monitoring
      _startMonitoring();
      
      setState(() {
        _isInitialized = true;
        _statusMessage = 'System initialized successfully';
      });
      
    } catch (e) {
      setState(() {
        _statusMessage = 'Initialization failed: $e';
      });
    }
  }

  Future<void> _shutdownSystem() async {
    setState(() {
      _statusMessage = 'Shutting down system...';
    });

    try {
      // Stop monitoring
      _stopMonitoring();
      
      // Dispose workflow
      await _workflow?.dispose();
      _workflow = null;
      
      // Note: We don't dispose singletons here as they might be used elsewhere
      
      setState(() {
        _isInitialized = false;
        _statusMessage = 'System shutdown complete';
        _memoryStats = null;
        _cameraState = null;
      });
      
    } catch (e) {
      setState(() {
        _statusMessage = 'Shutdown error: $e';
      });
    }
  }

  Future<void> _startWorkflow() async {
    if (_workflow == null) return;
    
    setState(() {
      _isWorkflowRunning = true;
      _workflowProgress = 0.0;
      _statusMessage = 'Starting workflow...';
    });

    try {
      final options = CaptureWorkflowOptions(
        captureCount: _selectedCaptureCount,
        interval: _selectedInterval,
        enableRetry: _enableRetry,
        enablePerformanceMonitoring: _enablePerformanceMonitoring,
      );
      
      final result = await _workflow!.executeCaptureWorkflow(
        options: options,
        onProgress: (captureResult) {
          setState(() {
            _captureResults.add(captureResult);
          });
        },
        onError: (error) {
          setState(() {
            _statusMessage = 'Workflow error: $error';
          });
        },
        onProgressUpdate: (progress, status) {
          setState(() {
            _workflowProgress = progress;
            _statusMessage = status;
          });
        },
      );
      
      setState(() {
        _lastWorkflowResult = result;
        _isWorkflowRunning = false;
        _workflowProgress = 1.0;
        _statusMessage = result.isSuccessful ? 'Workflow completed successfully' : 'Workflow failed';
      });
      
    } catch (e) {
      setState(() {
        _isWorkflowRunning = false;
        _statusMessage = 'Workflow failed: $e';
      });
    }
  }

  Future<void> _cancelWorkflow() async {
    if (_workflow == null) return;
    
    await _workflow!.cancelWorkflow();
    
    setState(() {
      _isWorkflowRunning = false;
      _workflowProgress = 0.0;
      _statusMessage = 'Workflow cancelled';
    });
  }

  void _startMonitoring() {
    // Monitor memory stats
    if (_memoryManager != null) {
      _memoryManager!.statisticsStream.listen((stats) {
        if (mounted) {
          setState(() {
            _memoryStats = stats;
          });
        }
      });
    }
    
    // Monitor camera state
    if (_sharedCameraManager != null) {
      _sharedCameraManager!.stateStream.listen((state) {
        if (mounted) {
          setState(() {
            _cameraState = state;
          });
        }
      });
    }
  }

  void _stopMonitoring() {
    // Monitoring streams will be disposed when managers are disposed
  }

  @override
  void dispose() {
    _workflow?.dispose();
    super.dispose();
  }
}