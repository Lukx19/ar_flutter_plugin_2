import 'package:flutter/material.dart';
import 'package:ar_flutter_plugin_2/ar_flutter_plugin.dart';
import 'package:ar_flutter_plugin_2/managers/ar_anchor_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_location_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_object_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_session_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_capture_manager.dart';
import 'package:ar_flutter_plugin_2/models/ar_anchor.dart';
import 'package:ar_flutter_plugin_2/models/ar_hittest_result.dart';
import 'package:ar_flutter_plugin_2/models/ar_node.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_result.dart';
import 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/datatypes/hittest_result_types.dart';
import 'package:ar_flutter_plugin_2/datatypes/node_types.dart';
import 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
import 'package:vector_math/vector_math_64.dart' hide Colors;

/// Direct Configuration Pattern Example demonstrating Phase 2 implementation:
/// - Direct configuration of ARSessionManager with capture capabilities
/// - Constructor-based initialization (no separate enableCapture() call)
/// - Immediate access to capture manager after session creation
/// - Configuration validation during construction
/// - Single-step AR session creation with capture functionality
class DirectConfigurationPatternExample extends StatefulWidget {
  const DirectConfigurationPatternExample({super.key});

  @override
  State<DirectConfigurationPatternExample> createState() => _DirectConfigurationPatternExampleState();
}

class _DirectConfigurationPatternExampleState extends State<DirectConfigurationPatternExample> {
  // AR managers
  ARSessionManager? _arSessionManager;
  ARObjectManager? _arObjectManager;
  ARAnchorManager? _arAnchorManager;
  ARCaptureManager? _arCaptureManager;

  // State
  bool _arInitialized = false;
  String _statusMessage = 'Initializing AR with direct configuration...';
  List<ARNode> _nodes = [];
  List<ARAnchor> _anchors = [];
  List<ARCaptureResult> _captureResults = [];

  // Configuration examples
  ARCaptureConfig? _currentConfig;
  bool _showCaptureResults = false;
  bool _autoCapture = false;

  @override
  void dispose() {
    _arSessionManager?.dispose();
    super.dispose();
  }

  // Example 1: Basic direct configuration - minimum setup
  ARCaptureConfig _createBasicConfig() {
    return const ARCaptureConfig(
      enableHighResCapture: false,
      captureIntervalMs: 2000,
      resolution: CameraResolution(width: 1280, height: 720),
      format: ImageFormat.jpeg,
    );
  }

  // Example 2: High-quality direct configuration
  ARCaptureConfig _createHighQualityConfig() {
    return const ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 1000,
      resolution: CameraResolution(width: 1920, height: 1080),
      format: ImageFormat.jpeg,
    );
  }

  // Example 3: RAW capture configuration for advanced use cases
  ARCaptureConfig _createRAWConfig() {
    return const ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 5000, // Longer interval for RAW to manage memory
      resolution: CameraResolution(width: 1920, height: 1080),
      format: ImageFormat.rawJpeg,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Direct Configuration Pattern'),
        actions: [
          IconButton(
            onPressed: () => setState(() => _showCaptureResults = !_showCaptureResults),
            icon: Icon(_showCaptureResults ? Icons.list : Icons.camera),
            tooltip: 'Toggle capture results',
          ),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    return Stack(
      children: [
        // Configuration selection screen
        if (_currentConfig == null)
          _buildConfigurationSelection(),

        // AR View with direct configuration
        if (_currentConfig != null) ...[
          ARView(
            onARViewCreated: _onARViewCreated,
            planeDetectionConfig: PlaneDetectionConfig.horizontalAndVertical,
            captureConfig: _currentConfig!, // Direct configuration passed to ARView
          ),

          // Status overlay during initialization
          if (!_arInitialized)
            Positioned.fill(
              child: Container(
                color: Colors.black.withOpacity(0.7),
                child: Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const CircularProgressIndicator(color: Colors.white),
                      const SizedBox(height: 16),
                      Text(
                        _statusMessage,
                        style: const TextStyle(color: Colors.white, fontSize: 16),
                        textAlign: TextAlign.center,
                      ),
                    ],
                  ),
                ),
              ),
            ),

          // Capture results overlay
          if (_showCaptureResults && _arInitialized)
            Positioned(
              top: 16,
              left: 16,
              right: 16,
              child: _buildCaptureResultsOverlay(),
            ),

          // Controls
          if (_arInitialized)
            Positioned(
              bottom: 16,
              left: 16,
              right: 16,
              child: _buildControls(),
            ),
        ],
      ],
    );
  }

  Widget _buildConfigurationSelection() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.settings, size: 64, color: Colors.blue),
            const SizedBox(height: 24),
            const Text(
              'Choose Configuration Pattern',
              style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            const Text(
              'Select a configuration to demonstrate the direct configuration pattern.\n'
              'Configuration is passed directly to ARSessionManager constructor.',
              style: TextStyle(fontSize: 14, color: Colors.grey),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            
            // Basic Configuration Option
            Card(
              child: ListTile(
                leading: const Icon(Icons.camera, color: Colors.green),
                title: const Text('Basic Configuration'),
                subtitle: const Text('720p JPEG, 2s interval\nRecommended for most use cases'),
                onTap: () => _selectConfiguration(_createBasicConfig()),
              ),
            ),
            const SizedBox(height: 8),
            
            // High Quality Configuration Option
            Card(
              child: ListTile(
                leading: const Icon(Icons.camera_enhance, color: Colors.orange),
                title: const Text('High Quality Configuration'),
                subtitle: const Text('1080p JPEG, 1s interval\nBetter quality, higher resource usage'),
                onTap: () => _selectConfiguration(_createHighQualityConfig()),
              ),
            ),
            const SizedBox(height: 8),
            
            // RAW Configuration Option
            Card(
              child: ListTile(
                leading: const Icon(Icons.camera_raw, color: Colors.red),
                title: const Text('RAW Configuration'),
                subtitle: const Text('1080p RAW, 5s interval\nMaximum quality, highest resource usage'),
                onTap: () => _selectConfiguration(_createRAWConfig()),
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _selectConfiguration(ARCaptureConfig config) {
    setState(() {
      _currentConfig = config;
      _statusMessage = 'Configuration selected: ${config.resolution}, ${config.format.name.toUpperCase()}';
    });
  }

  Widget _buildCaptureResultsOverlay() {
    return Card(
      color: Colors.black.withOpacity(0.8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                const Icon(Icons.photo_library, color: Colors.white, size: 16),
                const SizedBox(width: 8),
                const Text(
                  'Capture Results',
                  style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
                ),
                const Spacer(),
                Text(
                  '${_captureResults.length} captures',
                  style: const TextStyle(color: Colors.grey, fontSize: 12),
                ),
              ],
            ),
            const SizedBox(height: 8),
            if (_captureResults.isEmpty)
              const Text(
                'Tap "Capture" to take photos with synchronized AR pose data',
                style: TextStyle(color: Colors.grey, fontSize: 12),
              )
            else ...[
              SizedBox(
                height: 120,
                child: ListView.builder(
                  scrollDirection: Axis.horizontal,
                  itemCount: _captureResults.length,
                  itemBuilder: (context, index) {
                    final result = _captureResults[index];
                    return Container(
                      width: 100,
                      margin: const EdgeInsets.only(right: 8),
                      child: Card(
                        child: Padding(
                          padding: const EdgeInsets.all(8),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                result.imageId.substring(0, 8),
                                style: const TextStyle(fontSize: 10, fontWeight: FontWeight.bold),
                              ),
                              const SizedBox(height: 4),
                              Text(
                                result.resolution.toString(),
                                style: const TextStyle(fontSize: 9),
                              ),
                              Text(
                                '${result.sizeInMB.toStringAsFixed(1)}MB',
                                style: const TextStyle(fontSize: 9),
                              ),
                              Text(
                                result.format.name.toUpperCase(),
                                style: const TextStyle(fontSize: 9),
                              ),
                              const SizedBox(height: 4),
                              Icon(
                                result.pose.isTracking ? Icons.gps_fixed : Icons.gps_off,
                                size: 12,
                                color: result.pose.isTracking ? Colors.green : Colors.red,
                              ),
                            ],
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildControls() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _captureImage,
                    icon: const Icon(Icons.camera_alt),
                    label: const Text('Capture'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _nodes.isNotEmpty ? _removeAllAnchors : null,
                    icon: const Icon(Icons.clear_all),
                    label: const Text('Clear'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: () => setState(() => _currentConfig = null),
                    icon: const Icon(Icons.settings),
                    label: const Text('Config'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Checkbox(
                  value: _autoCapture,
                  onChanged: (value) => _toggleAutoCapture(value ?? false),
                ),
                const Text('Auto Capture'),
                const Spacer(),
                if (_arCaptureManager != null)
                  Text(
                    'Capture Manager: Ready',
                    style: const TextStyle(fontSize: 12, color: Colors.green),
                  ),
              ],
            ),
            Text(
              _statusMessage,
              style: const TextStyle(fontSize: 12, color: Colors.grey),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }

  void _onARViewCreated(
    ARSessionManager arSessionManager,
    ARObjectManager arObjectManager,
    ARAnchorManager arAnchorManager,
    ARLocationManager arLocationManager,
  ) {
    _arSessionManager = arSessionManager;
    _arObjectManager = arObjectManager;
    _arAnchorManager = arAnchorManager;

    // PHASE 2: Direct access to capture manager immediately after construction
    // No need to call enableCapture() - manager is available immediately
    _arCaptureManager = arSessionManager.captureManager;

    // Initialize AR session
    arSessionManager.onInitialize(
      showFeaturePoints: false,
      showPlanes: true,
      showWorldOrigin: false,
      handleTaps: true,
    );
    
    arObjectManager.onInitialize();

    // Set up tap handling
    arSessionManager.onPlaneOrPointTap = _onPlaneOrPointTapped;

    // Set up automatic capture stream if capture manager is available
    if (_arCaptureManager != null) {
      _arCaptureManager!.automaticCaptureStream.listen((result) {
        setState(() {
          _captureResults.add(result);
          _statusMessage = 'Auto-captured: ${result.imageId.substring(0, 8)}... (${result.sizeInMB.toStringAsFixed(1)}MB)';
        });
      });
    }

    setState(() {
      _arInitialized = true;
      _statusMessage = _arCaptureManager != null 
        ? 'AR + Capture ready! Config: ${_currentConfig!.resolution}'
        : 'AR ready (no capture support)';
    });
  }

  Future<void> _onPlaneOrPointTapped(List<ARHitTestResult> hitTestResults) async {
    final singleHitTestResult = hitTestResults.firstWhere(
      (hitTestResult) => hitTestResult.type == ARHitTestResultType.plane,
      orElse: () => hitTestResults.first,
    );

    if (singleHitTestResult != null) {
      final newAnchor = ARPlaneAnchor(transformation: singleHitTestResult.worldTransform);
      
      final didAddAnchor = await _arAnchorManager!.addAnchor(newAnchor);
      
      if (didAddAnchor == true) {
        _anchors.add(newAnchor);
        
        // Add a simple cube to the anchor
        final newNode = ARNode(
          type: NodeType.webGLB,
          uri: "https://github.com/KhronosGroup/glTF-Sample-Models/raw/refs/heads/main/2.0/Box/glTF-Binary/Box.glb",
          scale: Vector3(0.1, 0.1, 0.1),
          position: Vector3(0.0, 0.0, 0.0),
          rotation: Vector4(1.0, 0.0, 0.0, 0.0),
        );
        
        final didAddNodeToAnchor = await _arObjectManager!.addNode(newNode, planeAnchor: newAnchor);
        
        if (didAddNodeToAnchor == true) {
          _nodes.add(newNode);
          setState(() {
            _statusMessage = 'Added object (${_nodes.length} total)';
          });
        }
      }
    }
  }

  Future<void> _captureImage() async {
    if (_arCaptureManager == null) {
      setState(() {
        _statusMessage = 'Capture manager not available';
      });
      return;
    }

    try {
      setState(() {
        _statusMessage = 'Capturing image...';
      });

      final result = await _arCaptureManager!.captureImage();
      
      if (result != null) {
        setState(() {
          _captureResults.add(result);
          _statusMessage = 'Captured: ${result.imageId.substring(0, 8)}... (${result.sizeInMB.toStringAsFixed(1)}MB)';
        });
      } else {
        setState(() {
          _statusMessage = 'Capture failed - no result returned';
        });
      }
    } catch (e) {
      setState(() {
        _statusMessage = 'Capture error: ${e.toString()}';
      });
    }
  }

  void _toggleAutoCapture(bool enabled) {
    setState(() {
      _autoCapture = enabled;
      _statusMessage = enabled 
        ? 'Auto-capture enabled (${_currentConfig!.captureIntervalMs}ms interval)'
        : 'Auto-capture disabled';
    });
    
    // Note: Actual auto-capture would need platform implementation
    // This is just UI state for demonstration
  }

  Future<void> _removeAllAnchors() async {
    for (final anchor in _anchors) {
      await _arAnchorManager!.removeAnchor(anchor);
    }
    
    setState(() {
      _anchors.clear();
      _nodes.clear();
      _statusMessage = 'All objects cleared';
    });
  }
}

/// Extension to ARView to support direct configuration pattern
/// This demonstrates how ARView could be extended to accept capture config directly
extension ARViewWithCapture on ARView {
  // This would be implemented in the actual ARView widget
  // ARView({
  //   required Function onARViewCreated,
  //   required PlaneDetectionConfig planeDetectionConfig,
  //   ARCaptureConfig? captureConfig, // New parameter for direct configuration
  // })
}
