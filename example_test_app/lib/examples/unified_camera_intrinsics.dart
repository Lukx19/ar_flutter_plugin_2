import 'package:flutter/material.dart';
import 'package:ar_flutter_plugin_2/ar_flutter_plugin.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
import 'package:ar_flutter_plugin_2/models/ar_camera_intrinsics.dart';
import 'package:ar_flutter_plugin_2/capabilities/ar_camera_capabilities.dart';
import 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
import 'package:ar_flutter_plugin_2/widgets/ar_view.dart';
import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';

/// Example demonstrating the unified camera intrinsics system from Phase 3
/// Shows how to access camera calibration data in standard computer vision format
class UnifiedCameraIntrinsicsExample extends StatefulWidget {
  const UnifiedCameraIntrinsicsExample({Key? key}) : super(key: key);

  @override
  State<UnifiedCameraIntrinsicsExample> createState() => 
      _UnifiedCameraIntrinsicsExampleState();
}

class _UnifiedCameraIntrinsicsExampleState 
    extends State<UnifiedCameraIntrinsicsExample> {
  ARCameraCapabilities? _capabilities;
  ARCameraIntrinsics? _intrinsics;
  bool _isLoading = false;
  String? _error;
  ARSessionManager? _sessionManager;
  ARCaptureManager? _captureManager;

  @override
  void initState() {
    super.initState();
    _capabilities = ARCameraCapabilities();
    _loadIntrinsics();
  }

  Future<void> _loadIntrinsics() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });

    try {
      final intrinsics = await _capabilities!.getCameraIntrinsics();
      setState(() {
        _intrinsics = intrinsics;
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _error = e.toString();
        _isLoading = false;
      });
    }
  }

  void _onARViewCreated(
    ARSessionManager sessionManager,
    ARObjectManager objectManager,
    ARAnchorManager anchorManager,
    ARLocationManager locationManager,
  ) {
    _sessionManager = sessionManager;
    _captureManager = sessionManager.captureManager;

    sessionManager.onInitialize(
      showFeaturePoints: false,
      showPlanes: true,
      customPlaneTexturePath: "Images/triangle.png",
      showWorldOrigin: true,
      handleTaps: false,
    );
  }

  Widget _buildIntrinsicsInfo() {
    if (_isLoading) {
      return const Center(
        child: CircularProgressIndicator(),
      );
    }

    if (_error != null) {
      return Card(
        color: Colors.red.shade50,
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Error Loading Intrinsics',
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  color: Colors.red,
                ),
              ),
              const SizedBox(height: 8),
              Text(_error!),
              const SizedBox(height: 8),
              ElevatedButton(
                onPressed: _loadIntrinsics,
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
      );
    }

    if (_intrinsics == null) {
      return const Card(
        child: Padding(
          padding: EdgeInsets.all(16.0),
          child: Text('Camera intrinsics not available on this platform'),
        ),
      );
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Unified Camera Intrinsics',
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 16),
            _buildIntrinsicsSection('Focal Length', [
              'fx: ${_intrinsics!.focalLength.fx.toStringAsFixed(2)} pixels',
              'fy: ${_intrinsics!.focalLength.fy.toStringAsFixed(2)} pixels',
              'Average: ${_intrinsics!.focalLength.average.toStringAsFixed(2)} pixels',
              'Symmetric: ${_intrinsics!.focalLength.isSymmetric ? 'Yes' : 'No'}',
            ]),
            const SizedBox(height: 12),
            _buildIntrinsicsSection('Principal Point', [
              'cx: ${_intrinsics!.principalPoint.cx.toStringAsFixed(2)} pixels',
              'cy: ${_intrinsics!.principalPoint.cy.toStringAsFixed(2)} pixels',
              'Centered: ${_intrinsics!.principalPoint.isCenteredFor(_intrinsics!.resolution) ? 'Yes' : 'No'}',
              'Distance from center: ${_intrinsics!.principalPoint.getDistanceFromCenter(_intrinsics!.resolution).toStringAsFixed(2)} pixels',
            ]),
            const SizedBox(height: 12),
            _buildIntrinsicsSection('Resolution', [
              'Width: ${_intrinsics!.resolution.width} pixels',
              'Height: ${_intrinsics!.resolution.height} pixels',
              'Aspect Ratio: ${_intrinsics!.resolution.aspectRatio.toStringAsFixed(3)}',
              'Total Pixels: ${_intrinsics!.resolution.totalPixels}',
            ]),
            const SizedBox(height: 12),
            _buildIntrinsicsSection('Field of View', [
              'Horizontal: ${_intrinsics!.fieldOfView.horizontalDegrees.toStringAsFixed(1)}°',
              'Vertical: ${_intrinsics!.fieldOfView.verticalDegrees.toStringAsFixed(1)}°',
              'Diagonal: ${_intrinsics!.fieldOfView.getDiagonalFOVDegrees(_intrinsics!.resolution).toStringAsFixed(1)}°',
              'Type: ${_getLensType()}',
            ]),
            const SizedBox(height: 12),
            _buildIntrinsicsSection('Validation', [
              'Valid: ${_intrinsics!.isValid ? 'Yes' : 'No'}',
              'Focal length valid: ${_intrinsics!.focalLength.isValidForResolution(_intrinsics!.resolution) ? 'Yes' : 'No'}',
              'Principal point in bounds: ${_intrinsics!.principalPoint.isWithinBounds(_intrinsics!.resolution) ? 'Yes' : 'No'}',
              'Field of view valid: ${_intrinsics!.fieldOfView.isValid ? 'Yes' : 'No'}',
            ]),
            if (_intrinsics!.distortionCoefficients != null) ...[
              const SizedBox(height: 12),
              _buildIntrinsicsSection('Distortion', [
                'Coefficients: ${_intrinsics!.distortionCoefficients!.length}',
                'k1: ${_intrinsics!.distortionCoefficients![0].toStringAsFixed(6)}',
                'k2: ${_intrinsics!.distortionCoefficients![1].toStringAsFixed(6)}',
                if (_intrinsics!.distortionCoefficients!.length > 2)
                  'p1: ${_intrinsics!.distortionCoefficients![2].toStringAsFixed(6)}',
                if (_intrinsics!.distortionCoefficients!.length > 3)
                  'p2: ${_intrinsics!.distortionCoefficients![3].toStringAsFixed(6)}',
              ]),
            ],
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              children: [
                ElevatedButton(
                  onPressed: _showCameraMatrix,
                  child: const Text('Camera Matrix'),
                ),
                ElevatedButton(
                  onPressed: _showOpenCVFormat,
                  child: const Text('OpenCV Format'),
                ),
                ElevatedButton(
                  onPressed: _testScaling,
                  child: const Text('Test Scaling'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildIntrinsicsSection(String title, List<String> values) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          style: const TextStyle(
            fontWeight: FontWeight.w600,
            fontSize: 14,
          ),
        ),
        const SizedBox(height: 4),
        ...values.map((value) => Padding(
          padding: const EdgeInsets.only(left: 8.0, bottom: 2.0),
          child: Text(
            value,
            style: const TextStyle(
              fontSize: 12,
              fontFamily: 'monospace',
            ),
          ),
        )),
      ],
    );
  }

  String _getLensType() {
    if (_intrinsics!.fieldOfView.isWideAngle) return 'Wide-angle';
    if (_intrinsics!.fieldOfView.isTelephoto) return 'Telephoto';
    if (_intrinsics!.fieldOfView.isNormal) return 'Normal';
    return 'Unknown';
  }

  void _showCameraMatrix() {
    final matrix = _intrinsics!.cameraMatrix;
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Camera Matrix (3x3)'),
        content: SizedBox(
          width: 300,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (int i = 0; i < 3; i++)
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    for (int j = 0; j < 3; j++)
                      Container(
                        width: 80,
                        padding: const EdgeInsets.all(4),
                        child: Text(
                          matrix[i][j].toStringAsFixed(2),
                          textAlign: TextAlign.center,
                          style: const TextStyle(fontFamily: 'monospace'),
                        ),
                      ),
                  ],
                ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }

  void _showOpenCVFormat() {
    final opencv = _intrinsics!.toOpenCVFormat();
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('OpenCV Format'),
        content: SizedBox(
          width: 350,
          height: 400,
          child: SingleChildScrollView(
            child: Text(
              '''Camera Matrix:
${_formatMatrix(opencv['camera_matrix'] as List<List<double>>)}

Distortion Coefficients:
${(opencv['distortion_coefficients'] as List<double>).map((d) => d.toStringAsFixed(6)).join(', ')}

Image Size:
${(opencv['image_size'] as List<int>).join(' x ')}''',
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }

  String _formatMatrix(List<List<double>> matrix) {
    return matrix.map((row) => 
      '[${row.map((val) => val.toStringAsFixed(2).padLeft(8)).join(', ')}]'
    ).join('\n');
  }

  void _testScaling() {
    final originalRes = _intrinsics!.resolution;
    final halfRes = CameraResolution(
      width: originalRes.width ~/ 2,
      height: originalRes.height ~/ 2,
    );
    final scaledIntrinsics = _intrinsics!.scaleForResolution(halfRes);

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Intrinsics Scaling Test'),
        content: SizedBox(
          width: 350,
          height: 300,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Original (${originalRes.width}x${originalRes.height}):',
                    style: const TextStyle(fontWeight: FontWeight.bold)),
                Text('fx: ${_intrinsics!.focalLength.fx.toStringAsFixed(2)}'),
                Text('fy: ${_intrinsics!.focalLength.fy.toStringAsFixed(2)}'),
                Text('cx: ${_intrinsics!.principalPoint.cx.toStringAsFixed(2)}'),
                Text('cy: ${_intrinsics!.principalPoint.cy.toStringAsFixed(2)}'),
                const SizedBox(height: 16),
                Text('Scaled (${halfRes.width}x${halfRes.height}):',
                    style: const TextStyle(fontWeight: FontWeight.bold)),
                Text('fx: ${scaledIntrinsics.focalLength.fx.toStringAsFixed(2)}'),
                Text('fy: ${scaledIntrinsics.focalLength.fy.toStringAsFixed(2)}'),
                Text('cx: ${scaledIntrinsics.principalPoint.cx.toStringAsFixed(2)}'),
                Text('cy: ${scaledIntrinsics.principalPoint.cy.toStringAsFixed(2)}'),
                const SizedBox(height: 16),
                Text('Scale factors:',
                    style: const TextStyle(fontWeight: FontWeight.bold)),
                Text('X: ${(scaledIntrinsics.focalLength.fx / _intrinsics!.focalLength.fx).toStringAsFixed(3)}'),
                Text('Y: ${(scaledIntrinsics.focalLength.fy / _intrinsics!.focalLength.fy).toStringAsFixed(3)}'),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final captureConfig = ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: 2000,
      resolution: const CameraResolution(width: 1920, height: 1080),
      format: ImageFormat.jpeg,
    );

    return Scaffold(
      appBar: AppBar(
        title: const Text('Unified Camera Intrinsics'),
        backgroundColor: Colors.blue.shade700,
        foregroundColor: Colors.white,
      ),
      body: Column(
        children: [
          Expanded(
            flex: 3,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: ARView(
                onARViewCreated: _onARViewCreated,
                planeDetectionConfig: PlaneDetectionConfig.horizontalAndVertical,
                captureConfig: captureConfig,
              ),
            ),
          ),
          Expanded(
            flex: 2,
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(8.0),
              child: _buildIntrinsicsInfo(),
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _loadIntrinsics,
        tooltip: 'Refresh Intrinsics',
        child: const Icon(Icons.refresh),
      ),
    );
  }

  @override
  void dispose() {
    _sessionManager?.dispose();
    super.dispose();
  }
}