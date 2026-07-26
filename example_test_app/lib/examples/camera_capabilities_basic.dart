import 'package:flutter/material.dart';
import 'package:ar_flutter_plugin_2/ar_flutter_plugin.dart';

/// Basic example demonstrating camera capabilities API
/// Shows how to query device camera capabilities including:
/// - Supported resolutions
/// - Supported image formats  
/// - ISO sensitivity range
/// - Exposure time range
/// - Resolution/format support testing
class CameraCapabilitiesBasicExample extends StatefulWidget {
  const CameraCapabilitiesBasicExample({super.key});

  @override
  State<CameraCapabilitiesBasicExample> createState() => _CameraCapabilitiesBasicExampleState();
}

class _CameraCapabilitiesBasicExampleState extends State<CameraCapabilitiesBasicExample> {
  final ARCameraCapabilities _capabilities = ARCameraCapabilities();
  
  List<CameraResolution>? _supportedResolutions;
  List<ImageFormat>? _supportedFormats;
  List<int>? _supportedISORange;
  Map<String, Duration>? _supportedExposureRange;
  bool _isLoading = true;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _loadCapabilities();
  }

  Future<void> _loadCapabilities() async {
    try {
      setState(() {
        _isLoading = true;
        _errorMessage = null;
      });

      final resolutions = await _capabilities.getSupportedResolutions();
      final formats = await _capabilities.getSupportedFormats();
      final isoRange = await _capabilities.getSupportedISORange();
      final exposureRange = await _capabilities.getSupportedExposureRange();

      setState(() {
        _supportedResolutions = resolutions;
        _supportedFormats = formats;
        _supportedISORange = isoRange;
        _supportedExposureRange = exposureRange;
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _errorMessage = 'Failed to load capabilities: ${e.toString()}';
        _isLoading = false;
      });
    }
  }

  Future<void> _testResolutionSupport(CameraResolution resolution) async {
    try {
      final isSupported = await _capabilities.isResolutionSupported(resolution);
      _showDialog('Resolution Support Test', 
        'Resolution ${resolution.toString()} is ${isSupported ? 'supported' : 'not supported'}');
    } catch (e) {
      _showDialog('Error', 'Failed to test resolution support: ${e.toString()}');
    }
  }

  Future<void> _testFormatSupport(ImageFormat format) async {
    try {
      final isSupported = await _capabilities.isFormatSupported(format);
      _showDialog('Format Support Test', 
        'Format ${format.name} is ${isSupported ? 'supported' : 'not supported'}');
    } catch (e) {
      _showDialog('Error', 'Failed to test format support: ${e.toString()}');
    }
  }

  void _showDialog(String title, String content) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: Text(content),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('OK'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Camera Capabilities - Basic'),
        actions: [
          IconButton(
            onPressed: _loadCapabilities,
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh capabilities',
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
            Text('Loading camera capabilities...'),
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
              onPressed: _loadCapabilities,
              child: const Text('Retry'),
            ),
          ],
        ),
      );
    }

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _buildSupportedResolutionsCard(),
        const SizedBox(height: 16),
        _buildSupportedFormatsCard(),
        const SizedBox(height: 16),
        _buildISOExposureCard(),
        const SizedBox(height: 16),
        _buildTestingCard(),
      ],
    );
  }

  Widget _buildSupportedResolutionsCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Supported Resolutions',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 12),
            if (_supportedResolutions?.isEmpty ?? true)
              const Text('No supported resolutions found')
            else
              Column(
                children: _supportedResolutions!.map((resolution) {
                  return ListTile(
                    leading: Icon(
                      resolution.isLandscape ? Icons.landscape : 
                      resolution.isPortrait ? Icons.portrait : Icons.crop_square,
                    ),
                    title: Text(resolution.toString()),
                    subtitle: Text(
                      '${(resolution.totalPixels / 1000000).toStringAsFixed(1)}MP • '
                      'Aspect Ratio: ${resolution.aspectRatio.toStringAsFixed(2)}'
                    ),
                    trailing: IconButton(
                      icon: const Icon(Icons.check_circle_outline),
                      onPressed: () => _testResolutionSupport(resolution),
                      tooltip: 'Test support',
                    ),
                  );
                }).toList(),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildSupportedFormatsCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Supported Image Formats',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 12),
            if (_supportedFormats?.isEmpty ?? true)
              const Text('No supported formats found')
            else
              Column(
                children: _supportedFormats!.map((format) {
                  return ListTile(
                    leading: Icon(
                      format == ImageFormat.jpeg ? Icons.image : Icons.raw_on,
                      color: format == ImageFormat.jpeg ? Colors.blue : Colors.orange,
                    ),
                    title: Text(format.name.toUpperCase()),
                    subtitle: Text(
                      format == ImageFormat.jpeg 
                        ? 'Compressed format, smaller file size'
                        : 'Uncompressed format, larger file size, higher quality'
                    ),
                    trailing: IconButton(
                      icon: const Icon(Icons.check_circle_outline),
                      onPressed: () => _testFormatSupport(format),
                      tooltip: 'Test support',
                    ),
                  );
                }).toList(),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildISOExposureCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Camera Settings Ranges',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 12),
            
            // ISO Range
            ListTile(
              leading: const Icon(Icons.iso),
              title: const Text('ISO Sensitivity Range'),
              subtitle: Text(
                _supportedISORange?.isEmpty ?? true
                  ? 'No ISO range available'
                  : 'Range: ${_supportedISORange!.first} - ${_supportedISORange!.last}\n'
                    'Supported values: ${_supportedISORange!.join(', ')}'
              ),
            ),
            
            const Divider(),
            
            // Exposure Range
            ListTile(
              leading: const Icon(Icons.exposure),
              title: const Text('Exposure Time Range'),
              subtitle: Text(
                _supportedExposureRange?.isEmpty ?? true
                  ? 'No exposure range available'
                  : 'Min: ${_supportedExposureRange!['min']?.inMicroseconds ?? 0}μs\n'
                    'Max: ${_supportedExposureRange!['max']?.inMicroseconds ?? 0}μs'
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTestingCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Capability Testing',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 12),
            const Text(
              'Tap the check icons above to test specific resolution or format support.',
              style: TextStyle(color: Colors.grey),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: () => _testResolutionSupport(
                      const CameraResolution(width: 1920, height: 1080)
                    ),
                    icon: const Icon(Icons.hd),
                    label: const Text('Test 1080p'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: () => _testFormatSupport(ImageFormat.rawJpeg),
                    icon: const Icon(Icons.raw_on),
                    label: const Text('Test RAW'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
