import 'package:flutter/material.dart';
import 'package:ar_flutter_plugin_2/ar_flutter_plugin.dart';

/// Camera intrinsics models example demonstrating:
/// - FocalLength model with fx/fy values and symmetry checks
/// - PrincipalPoint model with cx/cy values and centering validation  
/// - ImageSize model with memory calculations
/// - CameraResolution model with utility methods
/// - Interactive calculations and visualizations
class CameraIntrinsicsModelsExample extends StatefulWidget {
  const CameraIntrinsicsModelsExample({super.key});

  @override
  State<CameraIntrinsicsModelsExample> createState() => _CameraIntrinsicsModelsExampleState();
}

class _CameraIntrinsicsModelsExampleState extends State<CameraIntrinsicsModelsExample> {
  // Sample data for demonstrations
  final List<CameraResolution> _sampleResolutions = [
    const CameraResolution(width: 1920, height: 1080),
    const CameraResolution(width: 1280, height: 720),
    const CameraResolution(width: 640, height: 480),
    const CameraResolution(width: 1080, height: 1920),
    const CameraResolution(width: 1024, height: 1024),
  ];

  final List<FocalLength> _sampleFocalLengths = [
    const FocalLength(fx: 800.0, fy: 800.0),
    const FocalLength(fx: 750.5, fy: 755.2),
    const FocalLength(fx: 1000.0, fy: 950.0),
    const FocalLength(fx: 600.0, fy: 600.0),
  ];

  final List<PrincipalPoint> _samplePrincipalPoints = [
    const PrincipalPoint(cx: 960.0, cy: 540.0),
    const PrincipalPoint(cx: 640.0, cy: 360.0),
    const PrincipalPoint(cx: 320.0, cy: 240.0),
    const PrincipalPoint(cx: 500.0, cy: 300.0),
  ];

  final List<ImageSize> _sampleImageSizes = [
    const ImageSize(width: 1920, height: 1080, bytesPerPixel: 3, totalBytes: 6220800),
    const ImageSize(width: 1920, height: 1080, bytesPerPixel: 4, totalBytes: 8294400),
    const ImageSize(width: 640, height: 480, bytesPerPixel: 3, totalBytes: 921600),
    const ImageSize(width: 1024, height: 1024, bytesPerPixel: 1, totalBytes: 1048576),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Camera Intrinsics Models'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _buildCameraResolutionCard(),
          const SizedBox(height: 16),
          _buildFocalLengthCard(),
          const SizedBox(height: 16),
          _buildPrincipalPointCard(),
          const SizedBox(height: 16),
          _buildImageSizeCard(),
          const SizedBox(height: 16),
          _buildInteractiveCalculatorCard(),
        ],
      ),
    );
  }

  Widget _buildCameraResolutionCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.aspect_ratio, color: Colors.blue),
                SizedBox(width: 8),
                Text(
                  'Camera Resolution',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
              ],
            ),
            const SizedBox(height: 12),
            const Text(
              'Demonstrates resolution calculations and properties:',
              style: TextStyle(color: Colors.grey),
            ),
            const SizedBox(height: 16),
            
            ..._sampleResolutions.map((resolution) {
              return Container(
                margin: const EdgeInsets.only(bottom: 12),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.blue.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.blue.withOpacity(0.3)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          resolution.isLandscape ? Icons.landscape : 
                          resolution.isPortrait ? Icons.portrait : Icons.crop_square,
                          size: 20,
                          color: Colors.blue,
                        ),
                        const SizedBox(width: 8),
                        Text(
                          resolution.toString(),
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                        const Spacer(),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                          decoration: BoxDecoration(
                            color: resolution.isLandscape ? Colors.green.withOpacity(0.2) :
                                  resolution.isPortrait ? Colors.orange.withOpacity(0.2) :
                                  Colors.purple.withOpacity(0.2),
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Text(
                            resolution.isLandscape ? 'Landscape' :
                            resolution.isPortrait ? 'Portrait' : 'Square',
                            style: TextStyle(
                              fontSize: 10,
                              color: resolution.isLandscape ? Colors.green :
                                    resolution.isPortrait ? Colors.orange : Colors.purple,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Expanded(
                          child: _buildResolutionProperty(
                            'Total Pixels',
                            '${(resolution.totalPixels / 1000000).toStringAsFixed(1)}MP',
                          ),
                        ),
                        Expanded(
                          child: _buildResolutionProperty(
                            'Aspect Ratio',
                            resolution.aspectRatio.toStringAsFixed(3),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              );
            }).toList(),
          ],
        ),
      ),
    );
  }

  Widget _buildResolutionProperty(String label, String value) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: TextStyle(fontSize: 12, color: Colors.grey.shade700),
        ),
        Text(
          value,
          style: const TextStyle(fontWeight: FontWeight.w500),
        ),
      ],
    );
  }

  Widget _buildFocalLengthCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.center_focus_strong, color: Colors.green),
                SizedBox(width: 8),
                Text(
                  'Focal Length',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
              ],
            ),
            const SizedBox(height: 12),
            const Text(
              'Camera focal length in pixels (fx, fy):',
              style: TextStyle(color: Colors.grey),
            ),
            const SizedBox(height: 16),
            
            ..._sampleFocalLengths.map((focalLength) {
              return Container(
                margin: const EdgeInsets.only(bottom: 12),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.green.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.green.withOpacity(0.3)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text(
                          'fx: ${focalLength.fx.toStringAsFixed(1)}, fy: ${focalLength.fy.toStringAsFixed(1)}',
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                        const Spacer(),
                        if (focalLength.isSymmetric)
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                            decoration: BoxDecoration(
                              color: Colors.blue.withOpacity(0.2),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: const Text(
                              'SYMMETRIC',
                              style: TextStyle(fontSize: 10, color: Colors.blue),
                            ),
                          ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Expanded(
                          child: _buildFocalProperty(
                            'Average',
                            focalLength.average.toStringAsFixed(1),
                          ),
                        ),
                        Expanded(
                          child: _buildFocalProperty(
                            'Difference',
                            '${(focalLength.fx - focalLength.fy).abs().toStringAsFixed(1)}px',
                          ),
                        ),
                        Expanded(
                          child: _buildFocalProperty(
                            'Symmetric',
                            focalLength.isSymmetric ? 'Yes' : 'No',
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              );
            }).toList(),
          ],
        ),
      ),
    );
  }

  Widget _buildFocalProperty(String label, String value) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: TextStyle(fontSize: 12, color: Colors.grey.shade700),
        ),
        Text(
          value,
          style: const TextStyle(fontWeight: FontWeight.w500),
        ),
      ],
    );
  }

  Widget _buildPrincipalPointCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.my_location, color: Colors.orange),
                SizedBox(width: 8),
                Text(
                  'Principal Point',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
              ],
            ),
            const SizedBox(height: 12),
            const Text(
              'Camera principal point coordinates (cx, cy):',
              style: TextStyle(color: Colors.grey),
            ),
            const SizedBox(height: 16),
            
            for (int i = 0; i < _samplePrincipalPoints.length; i++) ...[
              Builder(
                builder: (context) {
                  final principalPoint = _samplePrincipalPoints[i];
                  final resolution = _sampleResolutions[i % _sampleResolutions.length];
                  final isCentered = principalPoint.isCenteredFor(resolution);
                  
                  return Container(
                    margin: const EdgeInsets.only(bottom: 12),
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: Colors.orange.withOpacity(0.1),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: Colors.orange.withOpacity(0.3)),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Text(
                              'cx: ${principalPoint.cx.toStringAsFixed(1)}, cy: ${principalPoint.cy.toStringAsFixed(1)}',
                              style: const TextStyle(fontWeight: FontWeight.bold),
                            ),
                            const Spacer(),
                            if (isCentered)
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                                decoration: BoxDecoration(
                                  color: Colors.green.withOpacity(0.2),
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: const Text(
                                  'CENTERED',
                                  style: TextStyle(fontSize: 10, color: Colors.green),
                                ),
                              ),
                          ],
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'For resolution ${resolution.toString()}:',
                          style: TextStyle(fontSize: 12, color: Colors.grey.shade700),
                        ),
                        const SizedBox(height: 4),
                        Row(
                          children: [
                            Expanded(
                              child: _buildPrincipalProperty(
                                'Expected Center',
                                '(${(resolution.width / 2).toStringAsFixed(1)}, ${(resolution.height / 2).toStringAsFixed(1)})',
                              ),
                            ),
                            Expanded(
                              child: _buildPrincipalProperty(
                                'Is Centered',
                                isCentered ? 'Yes' : 'No',
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  );
                },
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildPrincipalProperty(String label, String value) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: TextStyle(fontSize: 12, color: Colors.grey.shade700),
        ),
        Text(
          value,
          style: const TextStyle(fontWeight: FontWeight.w500),
        ),
      ],
    );
  }

  Widget _buildImageSizeCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.storage, color: Colors.purple),
                SizedBox(width: 8),
                Text(
                  'Image Size',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
              ],
            ),
            const SizedBox(height: 12),
            const Text(
              'Image dimensions and memory requirements:',
              style: TextStyle(color: Colors.grey),
            ),
            const SizedBox(height: 16),
            
            ..._sampleImageSizes.map((imageSize) {
              return Container(
                margin: const EdgeInsets.only(bottom: 12),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.purple.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(
                    color: imageSize.isConsistent 
                      ? Colors.purple.withOpacity(0.3)
                      : Colors.red.withOpacity(0.5),
                  ),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text(
                          '${imageSize.width}×${imageSize.height} @ ${imageSize.bytesPerPixel}bpp',
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                        const Spacer(),
                        if (!imageSize.isConsistent)
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                            decoration: BoxDecoration(
                              color: Colors.red.withOpacity(0.2),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: const Text(
                              'INCONSISTENT',
                              style: TextStyle(fontSize: 10, color: Colors.red),
                            ),
                          ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Expanded(
                          child: _buildImageProperty(
                            'Total Size',
                            '${imageSize.sizeInMB.toStringAsFixed(2)} MB',
                          ),
                        ),
                        Expanded(
                          child: _buildImageProperty(
                            'Expected Size',
                            '${(imageSize.expectedTotalBytes / (1024 * 1024)).toStringAsFixed(2)} MB',
                          ),
                        ),
                        Expanded(
                          child: _buildImageProperty(
                            'KB Size',
                            '${imageSize.sizeInKB.toStringAsFixed(1)} KB',
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              );
            }).toList(),
          ],
        ),
      ),
    );
  }

  Widget _buildImageProperty(String label, String value) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: TextStyle(fontSize: 12, color: Colors.grey.shade700),
        ),
        Text(
          value,
          style: const TextStyle(fontWeight: FontWeight.w500),
        ),
      ],
    );
  }

  Widget _buildInteractiveCalculatorCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.calculate, color: Colors.teal),
                SizedBox(width: 8),
                Text(
                  'Interactive Calculator',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
              ],
            ),
            const SizedBox(height: 12),
            const Text(
              'Tap below to see comparisons and calculations:',
              style: TextStyle(color: Colors.grey),
            ),
            const SizedBox(height: 16),
            
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: () => _showResolutionComparison(),
                    icon: const Icon(Icons.compare),
                    label: const Text('Compare Resolutions'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: () => _showMemoryCalculator(),
                    icon: const Icon(Icons.memory),
                    label: const Text('Memory Calculator'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: () => _showFocalLengthAnalysis(),
                    icon: const Icon(Icons.analytics),
                    label: const Text('Focal Analysis'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: () => _showModelComparison(),
                    icon: const Icon(Icons.compare_arrows),
                    label: const Text('Model Comparison'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  void _showResolutionComparison() {
    final sortedResolutions = _sampleResolutions.toList()
      ..sort((a, b) => b.totalPixels.compareTo(a.totalPixels));
      
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Resolution Comparison'),
        content: SizedBox(
          width: double.maxFinite,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: sortedResolutions.map((resolution) {
              return ListTile(
                leading: Icon(
                  resolution.isLandscape ? Icons.landscape : 
                  resolution.isPortrait ? Icons.portrait : Icons.crop_square,
                ),
                title: Text(resolution.toString()),
                subtitle: Text('${(resolution.totalPixels / 1000000).toStringAsFixed(1)}MP'),
                trailing: Text('${resolution.aspectRatio.toStringAsFixed(2)}:1'),
              );
            }).toList(),
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

  void _showMemoryCalculator() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Memory Usage Calculator'),
        content: SizedBox(
          width: double.maxFinite,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Memory usage for different formats:'),
              const SizedBox(height: 12),
              ..._sampleResolutions.map((resolution) {
                final rgbSize = resolution.totalPixels * 3; // 3 bytes per pixel (RGB)
                final rgbaSize = resolution.totalPixels * 4; // 4 bytes per pixel (RGBA)
                return Container(
                  margin: const EdgeInsets.only(bottom: 8),
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: Colors.grey.withOpacity(0.1),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        resolution.toString(),
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                      Text('RGB: ${(rgbSize / (1024 * 1024)).toStringAsFixed(2)} MB'),
                      Text('RGBA: ${(rgbaSize / (1024 * 1024)).toStringAsFixed(2)} MB'),
                    ],
                  ),
                );
              }).toList(),
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

  void _showFocalLengthAnalysis() {
    final symmetricCount = _sampleFocalLengths.where((f) => f.isSymmetric).length;
    final avgFocalLength = _sampleFocalLengths
        .map((f) => f.average)
        .reduce((a, b) => a + b) / _sampleFocalLengths.length;
        
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Focal Length Analysis'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Symmetric focal lengths: $symmetricCount / ${_sampleFocalLengths.length}'),
            Text('Average focal length: ${avgFocalLength.toStringAsFixed(1)}px'),
            const SizedBox(height: 12),
            const Text('Focal length distribution:'),
            ..._sampleFocalLengths.map((f) => Text(
              '• ${f.fx.toStringAsFixed(1)}, ${f.fy.toStringAsFixed(1)} (avg: ${f.average.toStringAsFixed(1)})'
            )),
          ],
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

  void _showModelComparison() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Model Comparison'),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Model Features Comparison:', style: TextStyle(fontWeight: FontWeight.bold)),
            SizedBox(height: 8),
            Text('• CameraResolution: Aspect ratio, pixel count, orientation'),
            Text('• FocalLength: Symmetry check, averaging'),
            Text('• PrincipalPoint: Centering validation'),
            Text('• ImageSize: Memory calculations, consistency checks'),
            SizedBox(height: 12),
            Text('All models support:', style: TextStyle(fontWeight: FontWeight.bold)),
            Text('• Equality comparison'),
            Text('• JSON serialization (fromMap/toMap)'),
            Text('• String representation'),
            Text('• Hash code generation'),
          ],
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
}