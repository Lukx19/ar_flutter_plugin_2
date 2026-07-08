import 'dart:async';

import 'package:ar_flutter_plugin_2/ar_flutter_plugin.dart';
import 'package:ar_flutter_plugin_2/managers/ar_capture_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_session_manager.dart';
import 'package:flutter/material.dart';
import 'package:vector_math/vector_math_64.dart' as vector;

/// Phase 5: Runtime Camera Controls Example
///
/// This example demonstrates the comprehensive runtime camera control features:
/// - Dynamic ISO and exposure controls with real-time feedback
/// - Focus control with tap-to-focus
/// - White balance and color temperature controls
/// - Flash and torch control integration
/// - Camera parameter profiles and quick switching
class Phase5RuntimeCameraControlsWidget extends StatefulWidget {
  const Phase5RuntimeCameraControlsWidget({Key? key}) : super(key: key);

  @override
  State<Phase5RuntimeCameraControlsWidget> createState() =>
      _Phase5RuntimeCameraControlsWidgetState();
}

class _Phase5RuntimeCameraControlsWidgetState
    extends State<Phase5RuntimeCameraControlsWidget> {
  ARSessionManager? arSessionManager;
  ARCaptureManager? captureManager;

  // Stream subscriptions
  StreamSubscription<CameraExposureState>? _exposureSubscription;
  StreamSubscription<CameraFocusState>? _focusSubscription;
  StreamSubscription<CameraWhiteBalanceState>? _whiteBalanceSubscription;
  StreamSubscription<CameraFlashState>? _flashSubscription;
  StreamSubscription<ProfileApplicationStatus>? _profileSubscription;

  // Current camera states
  CameraExposureState? _currentExposureState;
  CameraFocusState? _currentFocusState;
  CameraWhiteBalanceState? _currentWhiteBalanceState;
  CameraFlashState? _currentFlashState;
  ProfileApplicationStatus? _profileStatus;

  // UI State
  bool _showAdvancedControls = false;
  bool _isCapturing = false;
  String _statusMessage = 'Ready to start AR session';
  List<CameraProfile> _savedProfiles = [];
  List<CameraProfile> _builtInProfiles = [];

  @override
  void initState() {
    super.initState();
    _initializeARSession();
  }

  Future<void> _initializeARSession() async {
    try {
      setState(() {
        _statusMessage = 'Initializing AR session...';
      });

      // Create AR configuration with capture enabled
      final arConfig = ARConfiguration(
        enableCapture: true,
        captureConfig: ARCaptureConfig(
          resolution: const CameraResolution(width: 1920, height: 1080),
          captureIntervalMs: 2000, // 2 seconds
          maxCacheSize: 10, // 10 images
          format: ImageFormat.jpeg,
        ),
      );

      // Initialize AR session manager with enhanced constructor
      arSessionManager = ARSessionManager.withConfig(
        buildContext: context,
        arConfig: arConfig,
      );

      await arSessionManager!.initialize();
      captureManager = arSessionManager!.captureManager;

      if (captureManager != null) {
        // Subscribe to camera state streams
        _subscribeToStreams();

        // Load profiles
        await _loadProfiles();

        setState(() {
          _statusMessage = 'AR session ready - Runtime camera controls active';
        });
      } else {
        setState(() {
          _statusMessage = 'Capture manager not available';
        });
      }
    } catch (e) {
      setState(() {
        _statusMessage = 'Failed to initialize: $e';
      });
    }
  }

  void _subscribeToStreams() {
    if (captureManager == null) return;

    // Subscribe to exposure state changes
    _exposureSubscription = captureManager!.exposureStateStream.listen((state) {
      setState(() {
        _currentExposureState = state;
      });
    });

    // Subscribe to focus state changes
    _focusSubscription = captureManager!.focusStateStream.listen((state) {
      setState(() {
        _currentFocusState = state;
      });
    });

    // Subscribe to white balance state changes
    _whiteBalanceSubscription = captureManager!.whiteBalanceStateStream.listen((
      state,
    ) {
      setState(() {
        _currentWhiteBalanceState = state;
      });
    });

    // Subscribe to flash state changes
    _flashSubscription = captureManager!.flashStateStream.listen((state) {
      setState(() {
        _currentFlashState = state;
      });
    });

    // Subscribe to profile application status
    _profileSubscription = captureManager!.profileStatusStream.listen((status) {
      setState(() {
        _profileStatus = status;
      });
    });
  }

  Future<void> _loadProfiles() async {
    if (captureManager == null) return;

    try {
      final saved = await captureManager!.getSavedProfiles();
      final builtIn = await captureManager!.getBuiltInProfiles();

      setState(() {
        _savedProfiles = saved;
        _builtInProfiles = builtIn;
      });
    } catch (e) {
      debugPrint('Failed to load profiles: $e');
    }
  }

  Future<void> _captureImage() async {
    if (captureManager == null || _isCapturing) return;

    setState(() {
      _isCapturing = true;
      _statusMessage = 'Capturing image...';
    });

    try {
      final result = await captureManager!.captureImage();
      if (result != null) {
        setState(() {
          _statusMessage = 'Image captured: ${result.imageId}';
        });
      } else {
        setState(() {
          _statusMessage = 'Capture failed';
        });
      }
    } catch (e) {
      setState(() {
        _statusMessage = 'Capture error: $e';
      });
    } finally {
      setState(() {
        _isCapturing = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Phase 5: Runtime Camera Controls'),
        backgroundColor: Colors.blue,
      ),
      body: Column(
        children: [
          // Status Bar
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            color: Colors.grey[100],
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Status: $_statusMessage',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
                if (_profileStatus != null)
                  Text('Profile Status: ${_profileStatus!.name}'),
              ],
            ),
          ),

          // AR View
          Expanded(
            flex: 2,
            child: arSessionManager != null
                ? ARView(
                    onARViewCreated:
                        (
                          arSessionManager,
                          arObjectManager,
                          arAnchorManager,
                          arLocationManager,
                        ) {
                          // AR view created with all managers
                        },
                    planeDetectionConfig: PlaneDetectionConfig.horizontal,
                  )
                : Container(
                    color: Colors.black,
                    child: const Center(child: CircularProgressIndicator()),
                  ),
          ),

          // Camera Controls
          Expanded(
            flex: 2,
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Main Controls
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                    children: [
                      ElevatedButton(
                        onPressed: _captureImage,
                        child: Text(_isCapturing ? 'Capturing...' : 'Capture'),
                      ),
                      ElevatedButton(
                        onPressed: () {
                          setState(() {
                            _showAdvancedControls = !_showAdvancedControls;
                          });
                        },
                        child: Text(
                          _showAdvancedControls
                              ? 'Hide Controls'
                              : 'Show Controls',
                        ),
                      ),
                    ],
                  ),

                  const SizedBox(height: 16),

                  if (_showAdvancedControls) ...[
                    // Quick Profile Controls
                    _buildQuickProfileControls(),

                    const SizedBox(height: 16),

                    // Current Camera State Display
                    _buildCameraStateDisplay(),

                    const SizedBox(height: 16),

                    // Exposure Controls
                    _buildExposureControls(),

                    const SizedBox(height: 16),

                    // Focus Controls
                    _buildFocusControls(),

                    const SizedBox(height: 16),

                    // White Balance Controls
                    _buildWhiteBalanceControls(),

                    const SizedBox(height: 16),

                    // Scene Mode and Flash Controls
                    _buildFlashControls(),

                    const SizedBox(height: 16),

                    // Profile Management
                    _buildProfileManagement(),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildQuickProfileControls() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Quick Profiles',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: QuickProfileType.values.map((type) {
                return ElevatedButton(
                  onPressed: () => _applyQuickProfile(type),
                  child: Text(type.name.toUpperCase()),
                );
              }).toList(),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCameraStateDisplay() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Current Camera State',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            if (_currentExposureState != null)
              Text('ISO: ${_currentExposureState!.currentISO ?? "Auto"}'),
            if (_currentExposureState != null)
              Text(
                'Exposure: ${_currentExposureState!.currentExposureTime?.inMicroseconds ?? "Auto"}μs',
              ),
            if (_currentFocusState != null)
              Text('Focus: ${_currentFocusState!.currentFocusMode.name}'),
            if (_currentWhiteBalanceState != null)
              Text('WB: ${_currentWhiteBalanceState!.currentMode.name}'),
            if (_currentFlashState != null)
              Text('Flash: ${_currentFlashState!.currentFlashMode.name}'),
          ],
        ),
      ),
    );
  }

  Widget _buildExposureControls() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Exposure Controls',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                ElevatedButton(
                  onPressed: () => _setISO(200),
                  child: const Text('ISO 200'),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                  onPressed: () => _setISO(800),
                  child: const Text('ISO 800'),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                  onPressed: () => _setISO(1600),
                  child: const Text('ISO 1600'),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                ElevatedButton(
                  onPressed: () => _setExposureCompensation(-1.0),
                  child: const Text('EV -1'),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                  onPressed: () => _setExposureCompensation(0.0),
                  child: const Text('EV 0'),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                  onPressed: () => _setExposureCompensation(1.0),
                  child: const Text('EV +1'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildFocusControls() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Focus Controls',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                ElevatedButton(
                  onPressed: () => _setFocusMode(FocusMode.auto),
                  child: const Text('Auto Focus'),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                  onPressed: () => _setFocusMode(FocusMode.macro),
                  child: const Text('Macro'),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                  onPressed: () => _setFocusMode(FocusMode.infinity),
                  child: const Text('Infinity'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildWhiteBalanceControls() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'White Balance Controls',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: [
                ElevatedButton(
                  onPressed: () => _setWhiteBalance(WhiteBalanceMode.auto),
                  child: const Text('Auto'),
                ),
                ElevatedButton(
                  onPressed: () => _setWhiteBalance(WhiteBalanceMode.daylight),
                  child: const Text('Daylight'),
                ),
                ElevatedButton(
                  onPressed: () =>
                      _setWhiteBalance(WhiteBalanceMode.incandescent),
                  child: const Text('Incandescent'),
                ),
                ElevatedButton(
                  onPressed: () => _setColorTemperature(3200),
                  child: const Text('3200K'),
                ),
                ElevatedButton(
                  onPressed: () => _setColorTemperature(5600),
                  child: const Text('5600K'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildFlashControls() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Flash Controls',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                ElevatedButton(
                  onPressed: () => _setFlashMode(FlashMode.auto),
                  child: const Text('Flash Auto'),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                  onPressed: () => _setFlashMode(FlashMode.on),
                  child: const Text('Flash On'),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                  onPressed: () => _setFlashMode(FlashMode.off),
                  child: const Text('Flash Off'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildProfileManagement() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Profile Management',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                ElevatedButton(
                  onPressed: _saveCurrentProfile,
                  child: const Text('Save Profile'),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                  onPressed: _loadProfiles,
                  child: const Text('Refresh'),
                ),
              ],
            ),
            const SizedBox(height: 8),
            if (_builtInProfiles.isNotEmpty) ...[
              const Text('Built-in Profiles:'),
              Wrap(
                spacing: 8,
                children: _builtInProfiles.map((profile) {
                  return ElevatedButton(
                    onPressed: () => _loadProfile(profile.name),
                    child: Text(profile.name),
                  );
                }).toList(),
              ),
            ],
            if (_savedProfiles.isNotEmpty) ...[
              const SizedBox(height: 8),
              const Text('Saved Profiles:'),
              Wrap(
                spacing: 8,
                children: _savedProfiles.map((profile) {
                  return ElevatedButton(
                    onPressed: () => _loadProfile(profile.name),
                    child: Text(profile.name),
                  );
                }).toList(),
              ),
            ],
          ],
        ),
      ),
    );
  }

  // Control Methods
  Future<void> _applyQuickProfile(QuickProfileType type) async {
    try {
      await captureManager?.applyQuickProfile(type);
    } catch (e) {
      debugPrint('Failed to apply quick profile: $e');
    }
  }

  Future<void> _setISO(int iso) async {
    try {
      await captureManager?.setISO(iso);
    } catch (e) {
      debugPrint('Failed to set ISO: $e');
    }
  }

  Future<void> _setExposureCompensation(double ev) async {
    try {
      await captureManager?.setExposureCompensation(ev);
    } catch (e) {
      debugPrint('Failed to set exposure compensation: $e');
    }
  }

  Future<void> _setFocusMode(FocusMode mode) async {
    try {
      await captureManager?.setFocusMode(mode);
    } catch (e) {
      debugPrint('Failed to set focus mode: $e');
    }
  }

  Future<void> _setWhiteBalance(WhiteBalanceMode mode) async {
    try {
      await captureManager?.setWhiteBalanceMode(mode);
    } catch (e) {
      debugPrint('Failed to set white balance: $e');
    }
  }

  Future<void> _setColorTemperature(int temp) async {
    try {
      await captureManager?.setColorTemperature(temp);
    } catch (e) {
      debugPrint('Failed to set color temperature: $e');
    }
  }

  Future<void> _setFlashMode(FlashMode mode) async {
    try {
      await captureManager?.setFlashMode(mode);
    } catch (e) {
      debugPrint('Failed to set flash mode: $e');
    }
  }

  Future<void> _saveCurrentProfile() async {
    try {
      final name = 'Custom_${DateTime.now().millisecondsSinceEpoch}';
      await captureManager?.saveProfile(
        name,
        description: 'User saved profile',
      );
      await _loadProfiles();
    } catch (e) {
      debugPrint('Failed to save profile: $e');
    }
  }

  Future<void> _loadProfile(String name) async {
    try {
      await captureManager?.loadProfile(name);
    } catch (e) {
      debugPrint('Failed to load profile: $e');
    }
  }

  @override
  void dispose() {
    _exposureSubscription?.cancel();
    _focusSubscription?.cancel();
    _whiteBalanceSubscription?.cancel();
    _flashSubscription?.cancel();
    _profileSubscription?.cancel();
    arSessionManager?.dispose();
    super.dispose();
  }
}
