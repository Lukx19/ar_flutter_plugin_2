import 'package:flutter/material.dart';
import 'package:ar_flutter_plugin_2/ar_flutter_plugin.dart';
import 'package:ar_flutter_plugin_2/managers/ar_anchor_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_location_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_object_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_session_manager.dart';
import 'package:ar_flutter_plugin_2/models/ar_anchor.dart';
import 'package:ar_flutter_plugin_2/models/ar_hittest_result.dart';
import 'package:ar_flutter_plugin_2/models/ar_node.dart';
import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/datatypes/hittest_result_types.dart';
import 'package:ar_flutter_plugin_2/datatypes/node_types.dart';
import 'package:vector_math/vector_math_64.dart' hide Colors;

/// AR integration with camera capabilities example demonstrating:
/// - Querying device camera capabilities before AR session
/// - Configuring optimal capture settings for AR
/// - Real-time performance monitoring during AR session
/// - Adaptive configuration based on device capabilities
/// - Integration of new camera API with existing AR workflow
class ARWithCameraCapabilitiesExample extends StatefulWidget {
  const ARWithCameraCapabilitiesExample({super.key});

  @override
  State<ARWithCameraCapabilitiesExample> createState() => _ARWithCameraCapabilitiesExampleState();
}

class _ARWithCameraCapabilitiesExampleState extends State<ARWithCameraCapabilitiesExample> {
  final ARCameraCapabilities _capabilities = ARCameraCapabilities();
  
  ARSessionManager? _arSessionManager;
  ARObjectManager? _arObjectManager;
  ARAnchorManager? _arAnchorManager;

  // Camera capability state
  ARCaptureConfig? _optimalConfig;
  ARCaptureConfig? _currentConfig;
  bool _capabilitiesLoaded = false;
  bool _arInitialized = false;
  String _statusMessage = 'Loading camera capabilities...';
  
  // AR session state
  List<ARNode> _nodes = [];
  List<ARAnchor> _anchors = [];
  bool _showCapabilityOverlay = false;
  PerformanceImpact? _currentPerformanceImpact;

  @override
  void initState() {
    super.initState();
    _loadCameraCapabilities();
  }

  @override
  void dispose() {
    _arSessionManager?.dispose();
    super.dispose();
  }

  Future<void> _loadCameraCapabilities() async {
    if (!_capabilities.isSupported) {
      setState(() {
        _statusMessage = 'Camera capabilities not supported on this platform';
        _capabilitiesLoaded = true;
      });
      return;
    }

    try {
      setState(() {
        _statusMessage = 'Querying device camera capabilities...';
      });

      // Get recommended configuration
      final recommendedConfig = await _capabilities.getRecommendedConfig();
      
      // Validate the configuration
      final validation = await _capabilities.validateCaptureConfig(recommendedConfig);
      
      if (!validation.isValid && validation.suggestedConfig != null) {
        _optimalConfig = validation.suggestedConfig;
        setState(() {
          _statusMessage = 'Using suggested configuration (recommended had issues)';
        });
      } else {
        _optimalConfig = recommendedConfig;
        setState(() {
          _statusMessage = 'Device-optimal configuration loaded';
        });
      }

      // Assess performance impact
      final performanceImpact = await _capabilities.assessPerformanceImpact(_optimalConfig!);
      
      setState(() {
        _currentConfig = _optimalConfig;
        _currentPerformanceImpact = performanceImpact;
        _capabilitiesLoaded = true;
        _statusMessage = 'Ready to start AR with optimal camera configuration';
      });
    } catch (e) {
      setState(() {
        _statusMessage = 'Failed to load camera capabilities: ${e.toString()}';
        _capabilitiesLoaded = true;
      });
    }
  }

  Future<void> _switchToLowPerformanceConfig() async {
    if (!_capabilities.isSupported) return;

    try {
      setState(() {
        _statusMessage = 'Switching to low performance configuration...';
      });

      // Create a low performance configuration
      final lowPerfConfig = ARCaptureConfig(
        enableHighResCapture: false,
        captureIntervalMs: 5000, // Less frequent captures
        resolution: const CameraResolution(width: 1280, height: 720), // Lower resolution
        format: ImageFormat.jpeg,
      );

      final validation = await _capabilities.validateCaptureConfig(lowPerfConfig);
      final performanceImpact = await _capabilities.assessPerformanceImpact(lowPerfConfig);

      if (validation.isValid) {
        setState(() {
          _currentConfig = lowPerfConfig;
          _currentPerformanceImpact = performanceImpact;
          _statusMessage = 'Switched to low performance configuration';
        });
      } else {
        setState(() {
          _statusMessage = 'Low performance config validation failed';
        });
      }
    } catch (e) {
      setState(() {
        _statusMessage = 'Failed to switch configuration: ${e.toString()}';
      });
    }
  }

  Future<void> _switchToHighPerformanceConfig() async {
    if (!_capabilities.isSupported) return;

    try {
      setState(() {
        _statusMessage = 'Switching to high performance configuration...';
      });

      // Create a high performance configuration
      final highPerfConfig = ARCaptureConfig(
        enableHighResCapture: true,
        captureIntervalMs: 1500, // More frequent captures
        resolution: const CameraResolution(width: 1920, height: 1080), // Higher resolution
        format: ImageFormat.jpeg,
      );

      final validation = await _capabilities.validateCaptureConfig(highPerfConfig);
      final performanceImpact = await _capabilities.assessPerformanceImpact(highPerfConfig);

      if (validation.isValid) {
        setState(() {
          _currentConfig = highPerfConfig;
          _currentPerformanceImpact = performanceImpact;
          _statusMessage = 'Switched to high performance configuration';
        });
      } else {
        setState(() {
          _statusMessage = validation.suggestedConfig != null 
            ? 'Using suggested high performance config'
            : 'High performance config validation failed';
          if (validation.suggestedConfig != null) {
            _currentConfig = validation.suggestedConfig;
          }
        });
      }
    } catch (e) {
      setState(() {
        _statusMessage = 'Failed to switch configuration: ${e.toString()}';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('AR + Camera Capabilities'),
        actions: [
          IconButton(
            onPressed: () => setState(() => _showCapabilityOverlay = !_showCapabilityOverlay),
            icon: Icon(
              _showCapabilityOverlay ? Icons.visibility_off : Icons.visibility,
            ),
            tooltip: 'Toggle capability overlay',
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
              'Camera capabilities not supported',
              style: TextStyle(fontSize: 18),
              textAlign: TextAlign.center,
            ),
            SizedBox(height: 8),
            Text(
              'AR will work with default settings',
              style: TextStyle(fontSize: 14, color: Colors.grey),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      );
    }

    if (!_capabilitiesLoaded) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const CircularProgressIndicator(),
            const SizedBox(height: 16),
            Text(_statusMessage),
          ],
        ),
      );
    }

    return Stack(
      children: [
        // AR View
        ARView(
          onARViewCreated: _onARViewCreated,
          planeDetectionConfig: PlaneDetectionConfig.horizontalAndVertical,
        ),

        // Status overlay
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

        // Capability overlay
        if (_showCapabilityOverlay && _capabilitiesLoaded)
          Positioned(
            top: 16,
            left: 16,
            right: 16,
            child: _buildCapabilityOverlay(),
          ),

        // Controls
        Positioned(
          bottom: 16,
          left: 16,
          right: 16,
          child: _buildControls(),
        ),
      ],
    );
  }

  Widget _buildCapabilityOverlay() {
    if (_currentConfig == null) return const SizedBox.shrink();

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
                const Icon(Icons.camera, color: Colors.white, size: 16),
                const SizedBox(width: 8),
                const Text(
                  'Current Configuration',
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                    fontSize: 14,
                  ),
                ),
                const Spacer(),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: _getPerformanceColor(_currentPerformanceImpact),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    _getPerformanceText(_currentPerformanceImpact),
                    style: const TextStyle(fontSize: 10, color: Colors.white),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            _buildConfigItem('Resolution', _currentConfig!.resolution.toString()),
            _buildConfigItem('Format', _currentConfig!.format.name.toUpperCase()),
            _buildConfigItem('High Res', _currentConfig!.enableHighResCapture ? 'Enabled' : 'Disabled'),
            _buildConfigItem('Interval', '${_currentConfig!.captureIntervalMs}ms'),
          ],
        ),
      ),
    );
  }

  Widget _buildConfigItem(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          SizedBox(
            width: 70,
            child: Text(
              '$label:',
              style: const TextStyle(color: Colors.grey, fontSize: 12),
            ),
          ),
          Text(
            value,
            style: const TextStyle(color: Colors.white, fontSize: 12),
          ),
        ],
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
                    onPressed: _arInitialized ? _removeAllAnchors : null,
                    icon: const Icon(Icons.clear_all),
                    label: const Text('Clear'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _switchToLowPerformanceConfig,
                    icon: const Icon(Icons.speed),
                    label: const Text('Low Perf'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _switchToHighPerformanceConfig,
                    icon: const Icon(Icons.flash_on),
                    label: const Text('High Perf'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
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

    setState(() {
      _arInitialized = true;
      _statusMessage = 'AR initialized with camera capabilities';
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
            _statusMessage = 'Added object (${_nodes.length} total) - Current config: ${_getPerformanceText(_currentPerformanceImpact)}';
          });
        }
      }
    }
  }

  Future<void> _removeAllAnchors() async {
    for (final anchor in _anchors) {
      await _arAnchorManager!.removeAnchor(anchor);
    }
    
    setState(() {
      _anchors.clear();
      _nodes.clear();
      _statusMessage = 'All objects cleared - Current config: ${_getPerformanceText(_currentPerformanceImpact)}';
    });
  }

  Color _getPerformanceColor(PerformanceImpact? impact) {
    switch (impact) {
      case PerformanceImpact.low:
        return Colors.green;
      case PerformanceImpact.medium:
        return Colors.orange;
      case PerformanceImpact.high:
        return Colors.red;
      case PerformanceImpact.extreme:
        return Colors.purple;
      case null:
        return Colors.grey;
    }
  }

  String _getPerformanceText(PerformanceImpact? impact) {
    switch (impact) {
      case PerformanceImpact.low:
        return 'LOW';
      case PerformanceImpact.medium:
        return 'MED';
      case PerformanceImpact.high:
        return 'HIGH';
      case PerformanceImpact.extreme:
        return 'EXTREME';
      case null:
        return 'UNKNOWN';
    }
  }
}