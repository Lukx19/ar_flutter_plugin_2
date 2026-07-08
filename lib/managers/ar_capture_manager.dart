import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../datatypes/image_format.dart';
import '../models/ar_camera_intrinsics.dart';
import '../models/ar_capture_config.dart';
import '../models/ar_capture_result.dart';
import '../models/ar_frame_pose.dart';
import '../models/camera_resolution.dart';
import '../models/image_size.dart';
import 'ar_session_manager.dart';

/// Available exposure modes
enum ExposureMode {
  auto,
  manual,
  program,
  aperturePriority,
  shutterPriority,
}

/// Available focus modes
enum FocusMode {
  auto,
  macro,
  infinity,
  fixed,
  edof, // Extended depth of field
  continuous,
}

/// Focus status indicators
enum FocusStatus {
  inactive,
  scanning,
  locked,
  notLocked,
  error,
}

/// White balance modes
enum WhiteBalanceMode {
  auto,
  incandescent,
  fluorescent,
  warmFluorescent,
  daylight,
  cloudyDaylight,
  twilight,
  shade,
  manual,
}

/// White balance status
enum WhiteBalanceStatus {
  inactive,
  searching,
  converged,
  locked,
  error,
}

/// Flash modes
enum FlashMode {
  off,
  auto,
  on,
  redEyeReduction,
  torch,
}

/// Flash status indicators
enum FlashStatus {
  unavailable,
  charging,
  ready,
  firing,
  partial,
  error,
}

/// Current camera exposure state for UI display
class CameraExposureState {
  final int? currentISO;
  final Duration? currentExposureTime;
  final bool isAutoExposureEnabled;
  final bool isExposureLocked;
  final double exposureCompensation;
  final ExposureMode exposureMode;

  const CameraExposureState({
    this.currentISO,
    this.currentExposureTime,
    required this.isAutoExposureEnabled,
    required this.isExposureLocked,
    required this.exposureCompensation,
    required this.exposureMode,
  });

  factory CameraExposureState.fromMap(Map<String, dynamic> map) {
    return CameraExposureState(
      currentISO: map['currentISO'] as int?,
      currentExposureTime: map['currentExposureTime'] != null
          ? Duration(microseconds: map['currentExposureTime'] as int)
          : null,
      isAutoExposureEnabled: map['isAutoExposureEnabled'] as bool? ?? true,
      isExposureLocked: map['isExposureLocked'] as bool? ?? false,
      exposureCompensation:
          (map['exposureCompensation'] as num?)?.toDouble() ?? 0.0,
      exposureMode: ExposureMode.values.firstWhere(
        (mode) => mode.name == map['exposureMode'],
        orElse: () => ExposureMode.auto,
      ),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'currentISO': currentISO,
      'currentExposureTime': currentExposureTime?.inMicroseconds,
      'isAutoExposureEnabled': isAutoExposureEnabled,
      'isExposureLocked': isExposureLocked,
      'exposureCompensation': exposureCompensation,
      'exposureMode': exposureMode.name,
    };
  }
}

/// Exposure compensation information
class ExposureCompensationInfo {
  final double minCompensation;
  final double maxCompensation;
  final double currentCompensation;
  final double stepSize;

  const ExposureCompensationInfo({
    required this.minCompensation,
    required this.maxCompensation,
    required this.currentCompensation,
    required this.stepSize,
  });

  factory ExposureCompensationInfo.fromMap(Map<String, dynamic> map) {
    return ExposureCompensationInfo(
      minCompensation: (map['minCompensation'] as num).toDouble(),
      maxCompensation: (map['maxCompensation'] as num).toDouble(),
      currentCompensation: (map['currentCompensation'] as num).toDouble(),
      stepSize: (map['stepSize'] as num).toDouble(),
    );
  }
}

/// Current camera focus state
class CameraFocusState {
  final double? currentFocusDistance;
  final bool isAutofocusEnabled;
  final FocusMode currentFocusMode;
  final bool isFocusLocked;
  final bool isFocusPeakingEnabled;
  final FocusStatus focusStatus;
  final Rect? focusRegion;

  const CameraFocusState({
    this.currentFocusDistance,
    required this.isAutofocusEnabled,
    required this.currentFocusMode,
    required this.isFocusLocked,
    required this.isFocusPeakingEnabled,
    required this.focusStatus,
    this.focusRegion,
  });

  factory CameraFocusState.fromMap(Map<String, dynamic> map) {
    return CameraFocusState(
      currentFocusDistance: map['currentFocusDistance'] as double?,
      isAutofocusEnabled: map['isAutofocusEnabled'] as bool? ?? true,
      currentFocusMode: FocusMode.values.firstWhere(
        (mode) => mode.name == map['currentFocusMode'],
        orElse: () => FocusMode.auto,
      ),
      isFocusLocked: map['isFocusLocked'] as bool? ?? false,
      isFocusPeakingEnabled: map['isFocusPeakingEnabled'] as bool? ?? false,
      focusStatus: FocusStatus.values.firstWhere(
        (status) => status.name == map['focusStatus'],
        orElse: () => FocusStatus.inactive,
      ),
      focusRegion: map['focusRegion'] != null
          ? Rect.fromLTWH(
              map['focusRegion']['left'] as double,
              map['focusRegion']['top'] as double,
              map['focusRegion']['width'] as double,
              map['focusRegion']['height'] as double,
            )
          : null,
    );
  }
}

/// Current camera white balance state
class CameraWhiteBalanceState {
  final WhiteBalanceMode currentMode;
  final int? currentColorTemperature;
  final bool isWhiteBalanceLocked;
  final bool isAutoWhiteBalanceEnabled;
  final WhiteBalanceStatus status;

  const CameraWhiteBalanceState({
    required this.currentMode,
    this.currentColorTemperature,
    required this.isWhiteBalanceLocked,
    required this.isAutoWhiteBalanceEnabled,
    required this.status,
  });

  factory CameraWhiteBalanceState.fromMap(Map<String, dynamic> map) {
    return CameraWhiteBalanceState(
      currentMode: WhiteBalanceMode.values.firstWhere(
        (mode) => mode.name == map['currentMode'],
        orElse: () => WhiteBalanceMode.auto,
      ),
      currentColorTemperature: map['currentColorTemperature'] as int?,
      isWhiteBalanceLocked: map['isWhiteBalanceLocked'] as bool? ?? false,
      isAutoWhiteBalanceEnabled:
          map['isAutoWhiteBalanceEnabled'] as bool? ?? true,
      status: WhiteBalanceStatus.values.firstWhere(
        (status) => status.name == map['status'],
        orElse: () => WhiteBalanceStatus.inactive,
      ),
    );
  }
}

/// Current flash and torch state.
class CameraFlashState {
  final FlashMode currentFlashMode;
  final bool isTorchEnabled;
  final bool isFlashReady;
  final FlashStatus flashStatus;

  const CameraFlashState({
    required this.currentFlashMode,
    required this.isTorchEnabled,
    required this.isFlashReady,
    required this.flashStatus,
  });

  factory CameraFlashState.fromMap(Map<String, dynamic> map) {
    return CameraFlashState(
      currentFlashMode: FlashMode.values.firstWhere(
        (mode) => mode.name == map['currentFlashMode'],
        orElse: () => FlashMode.off,
      ),
      isTorchEnabled: map['isTorchEnabled'] as bool? ?? false,
      isFlashReady: map['isFlashReady'] as bool? ?? false,
      flashStatus: FlashStatus.values.firstWhere(
        (status) => status.name == map['flashStatus'],
        orElse: () => FlashStatus.unavailable,
      ),
    );
  }
}

/// Manages high-resolution image capture with synchronized AR pose data
/// Created at ARSessionManager construction time with full configuration
class ARCaptureManager {
  @visibleForTesting
  static bool? debugIsSupportedOverride;

  late MethodChannel _channel;
  final ARSessionManager _sessionManager;
  final StreamController<ARFramePose> _poseStreamController =
      StreamController.broadcast();
  final StreamController<ARCaptureResult> _captureResultController =
      StreamController.broadcast();
  final StreamController<CameraExposureState> _exposureStateController =
      StreamController.broadcast();
  final StreamController<CameraFocusState> _focusStateController =
      StreamController.broadcast();
  final StreamController<CameraWhiteBalanceState> _whiteBalanceStateController =
      StreamController.broadcast();
  final StreamController<CameraFlashState> _flashStateController =
      StreamController.broadcast();
  final StreamController<ProfileApplicationStatus> _profileStatusController =
      StreamController.broadcast();
  static const String _profilesKey = 'ar_capture_profiles';
  final ARCaptureConfig _config;
  Future<void>? _initializationFuture;
  bool _isDisposed = false;

  bool get isSupported =>
      debugIsSupportedOverride ??
      (Platform.isAndroid || Platform.environment.containsKey('FLUTTER_TEST'));
  bool get isEnabled => !_isDisposed && isSupported && _config.enableHighResCapture;
  ARCaptureConfig get config => _config;

  void _throwIfDisposed() {
    if (_isDisposed) {
      throw const ARCaptureException(
        'Capture session is not initialized',
        code: 'CAPTURE_NOT_INITIALIZED',
      );
    }
  }

  /// Get current capture resolution (read-only)
  /// Resolution is set at configuration time and cannot be changed during session
  CameraResolution get currentResolution => _config.resolution;

  /// Create capture manager with configuration at construction time
  ARCaptureManager(
      this._sessionManager, this._config, BuildContext buildContext) {
    _channel = MethodChannel('arcapture_${_sessionManager.channelId}');
    _channel.setMethodCallHandler(_platformCallHandler);
  }

  Future<void> _ensureInitialized() {
    if (_isDisposed) {
      throw const ARCaptureException(
        'Capture session is not initialized',
        code: 'CAPTURE_NOT_INITIALIZED',
      );
    }
    return _initializationFuture ??= _initializePlatformChannel();
  }

  Future<void> _initializePlatformChannel() async {
    try {
      // Validate configuration before initializing
      if (!isSupported) {
        throw const ARCaptureException(
          'Capture not supported on this platform',
          code: 'CAPTURE_UNSUPPORTED',
        );
      }

      // Initialize native capture system immediately
      await _channel.invokeMethod('initializeCapture', _config.toMap());

      debugPrint(
          'ARCaptureManager initialized with config: ${_config.toString()}');
    } on PlatformException catch (e) {
      throw _captureExceptionFromPlatformException(
        e,
        operation: 'initialize capture manager',
      );
    } catch (e) {
      if (e is ARCaptureException) {
        rethrow;
      }
      throw ARCaptureException('Failed to initialize capture manager: $e');
    }
  }

  /// Capture high-resolution image with synchronized pose data
  Future<ARCaptureResult?> captureImage() async {
    _throwIfDisposed();
    if (!isEnabled) {
      throw const ARCaptureException(
        'Capture manager not enabled',
        code: 'CAPTURE_DISABLED',
      );
    }

    try {
      await _ensureInitialized();
      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('captureHighResImage');
      if (result != null) {
        return ARCaptureResult.fromMap(_deepCastMap(result));
      }
      return null;
    } on PlatformException catch (e) {
      throw _captureExceptionFromPlatformException(
        e,
        operation: 'capture image',
      );
    }
  }

  /// Stream of automatic capture results based on configured interval
  Stream<ARCaptureResult> get automaticCaptureStream =>
      _captureResultController.stream;

  /// Get camera intrinsics data (unified for both AR tracking and capture)
  Future<ARCameraIntrinsics?> getCameraIntrinsics() async {
    _throwIfDisposed();
    if (!isEnabled) return null;

    try {
      await _ensureInitialized();
      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('getCameraIntrinsics');
      if (result != null) {
        return ARCameraIntrinsics.fromMap(_deepCastMap(result));
      }
      return null;
    } on PlatformException catch (e) {
      throw _captureExceptionFromPlatformException(
        e,
        operation: 'get camera intrinsics',
      );
    }
  }

  /// Save captured image to disk by ID
  Future<bool> saveImageToFile(
      String imageId, String filePath, ImageFormat format) async {
    _throwIfDisposed();
    if (!isEnabled) return false;

    try {
      await _ensureInitialized();
      return await _channel.invokeMethod('saveImageToFile', {
        'imageId': imageId,
        'filePath': filePath,
        'format': format.name,
      });
    } on PlatformException catch (e) {
      throw _captureExceptionFromPlatformException(
        e,
        operation: 'save image',
      );
    }
  }

  /// Get size of image in memory by ID
  Future<ImageSize?> getImageSize(String imageId) async {
    _throwIfDisposed();
    if (!isEnabled) return null;

    try {
      await _ensureInitialized();
      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('getImageSize', {
        'imageId': imageId,
      });
      if (result != null) {
        return ImageSize.fromMap(Map<String, dynamic>.from(result));
      }
      return null;
    } on PlatformException catch (e) {
      throw _captureExceptionFromPlatformException(
        e,
        operation: 'get image size',
      );
    }
  }

  /// Get image data into provided buffer
  Future<Uint8List?> getImageData(String imageId, ImageFormat format) async {
    _throwIfDisposed();
    if (!isEnabled) return null;

    try {
      await _ensureInitialized();
      final Uint8List? result =
          await _channel.invokeMethod<Uint8List>('getImageData', {
        'imageId': imageId,
        'format': format.name,
      });
      return result;
    } on PlatformException catch (e) {
      throw _captureExceptionFromPlatformException(
        e,
        operation: 'get image data',
      );
    }
  }

  /// Runtime ISO control with device capability validation
  /// Returns actual ISO set by camera (may differ from requested)
  Future<int?> setISO(int isoValue, {bool temporary = true}) async {
    if (!isEnabled) return null;

    try {
      // Validate against supported ISO range from capabilities
      final supportedISORange = await getSupportedISORange();
      if (supportedISORange != null && supportedISORange.isNotEmpty) {
        final minISO = supportedISORange.first;
        final maxISO = supportedISORange.last;

        if (isoValue < minISO || isoValue > maxISO) {
          final clampedISO = isoValue.clamp(minISO, maxISO);
          debugPrint(
              'ISO $isoValue out of range [$minISO-$maxISO], using $clampedISO');
          isoValue = clampedISO;
        }
      }

      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('setISO', {
        'isoValue': isoValue,
        'temporary': temporary,
      });

      if (result != null) {
        final actualISO = result['actualISO'] as int?;

        // Update exposure state stream
        _updateExposureState();

        return actualISO;
      }

      return null;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to set ISO: ${e.message}');
    }
  }

  /// Runtime exposure time control with validation
  /// Returns actual exposure time set by camera
  Future<Duration?> setExposureTime(Duration exposureTime,
      {bool temporary = true}) async {
    if (!isEnabled) return null;

    try {
      // Validate against supported exposure range from capabilities
      final supportedRange = await getSupportedExposureRange();
      if (supportedRange != null && supportedRange.isNotEmpty) {
        final minExposure = supportedRange['min'] ?? Duration.zero;
        final maxExposure = supportedRange['max'] ?? const Duration(seconds: 1);

        if (exposureTime < minExposure || exposureTime > maxExposure) {
          final clampedExposure = Duration(
            microseconds: exposureTime.inMicroseconds.clamp(
              minExposure.inMicroseconds,
              maxExposure.inMicroseconds,
            ),
          );
          debugPrint(
              'Exposure time ${exposureTime.inMicroseconds}μs out of range, using ${clampedExposure.inMicroseconds}μs');
          exposureTime = clampedExposure;
        }
      }

      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('setExposureTime', {
        'exposureTimeMicroseconds': exposureTime.inMicroseconds,
        'temporary': temporary,
      });

      if (result != null) {
        final actualMicroseconds =
            result['actualExposureTimeMicroseconds'] as int?;

        // Update exposure state stream
        _updateExposureState();

        return actualMicroseconds != null
            ? Duration(microseconds: actualMicroseconds)
            : null;
      }

      return null;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to set exposure time: ${e.message}');
    }
  }

  /// Get current camera ISO value
  Future<int?> getCurrentISO() async {
    if (!isEnabled) return null;

    try {
      return await _channel.invokeMethod('getCurrentISO');
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to get current ISO: ${e.message}');
    }
  }

  /// Get current camera exposure time
  Future<Duration?> getCurrentExposureTime() async {
    if (!isEnabled) return null;

    try {
      final int? microseconds =
          await _channel.invokeMethod('getCurrentExposureTime');
      if (microseconds != null) {
        return Duration(microseconds: microseconds);
      }
      return null;
    } on PlatformException catch (e) {
      throw ARCaptureException(
          'Failed to get current exposure time: ${e.message}');
    }
  }

  /// Get supported ISO range for this camera
  Future<List<int>?> getSupportedISORange() async {
    if (!isEnabled) return null;

    try {
      final List<dynamic>? result =
          await _channel.invokeMethod('getSupportedISORange');
      return result?.cast<int>();
    } on PlatformException catch (e) {
      throw ARCaptureException(
          'Failed to get supported ISO range: ${e.message}');
    }
  }

  /// Get supported exposure time range for this camera
  Future<Map<String, Duration>?> getSupportedExposureRange() async {
    if (!isEnabled) return null;

    try {
      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('getSupportedExposureRange');
      if (result != null) {
        return {
          'min': Duration(microseconds: result['min'] ?? 0),
          'max': Duration(microseconds: result['max'] ?? 0),
        };
      }
      return null;
    } on PlatformException catch (e) {
      throw ARCaptureException(
          'Failed to get supported exposure range: ${e.message}');
    }
  }

  /// Control automatic exposure mode
  Future<bool> setAutoExposureEnabled(bool enabled) async {
    if (!isEnabled) return false;

    try {
      final bool result =
          await _channel.invokeMethod('setAutoExposureEnabled', {
        'enabled': enabled,
      });

      // Update exposure state stream
      _updateExposureState();

      return result;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to set auto exposure: ${e.message}');
    }
  }

  /// Get comprehensive exposure state
  Future<CameraExposureState> getCurrentExposureState() async {
    if (!isEnabled) {
      return const CameraExposureState(
        isAutoExposureEnabled: true,
        isExposureLocked: false,
        exposureCompensation: 0.0,
        exposureMode: ExposureMode.auto,
      );
    }

    try {
      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('getCurrentExposureState');
      if (result != null) {
        return CameraExposureState.fromMap(Map<String, dynamic>.from(result));
      }

      return const CameraExposureState(
        isAutoExposureEnabled: true,
        isExposureLocked: false,
        exposureCompensation: 0.0,
        exposureMode: ExposureMode.auto,
      );
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to get exposure state: ${e.message}');
    }
  }

  /// Get exposure compensation range and current value
  Future<ExposureCompensationInfo?> getExposureCompensationInfo() async {
    if (!isEnabled) return null;

    try {
      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('getExposureCompensationInfo');
      if (result != null) {
        return ExposureCompensationInfo.fromMap(
            Map<String, dynamic>.from(result));
      }
      return null;
    } on PlatformException catch (e) {
      throw ARCaptureException(
          'Failed to get exposure compensation info: ${e.message}');
    }
  }

  /// Set exposure compensation (-2.0 to +2.0 EV typical range)
  Future<double?> setExposureCompensation(double evStep) async {
    if (!isEnabled) return null;

    try {
      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('setExposureCompensation', {
        'evStep': evStep,
      });

      if (result != null) {
        final actualCompensation = result['actualCompensation'] as double?;

        // Update exposure state stream
        _updateExposureState();

        return actualCompensation;
      }

      return null;
    } on PlatformException catch (e) {
      throw ARCaptureException(
          'Failed to set exposure compensation: ${e.message}');
    }
  }

  /// Lock current exposure settings to prevent auto-adjustment
  Future<bool> lockExposure() async {
    if (!isEnabled) return false;

    try {
      final bool result = await _channel.invokeMethod('lockExposure');

      // Update exposure state stream
      _updateExposureState();

      return result;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to lock exposure: ${e.message}');
    }
  }

  /// Unlock exposure settings to resume auto-adjustment
  Future<bool> unlockExposure() async {
    if (!isEnabled) return false;

    try {
      final bool result = await _channel.invokeMethod('unlockExposure');

      // Update exposure state stream
      _updateExposureState();

      return result;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to unlock exposure: ${e.message}');
    }
  }

  /// Update exposure state stream for real-time UI updates
  Future<void> _updateExposureState() async {
    try {
      final state = await getCurrentExposureState();
      _exposureStateController.add(state);
    } catch (e) {
      debugPrint('Failed to update exposure state: $e');
    }
  }

  /// Stream of exposure state changes for real-time UI updates
  Stream<CameraExposureState> get exposureStateStream =>
      _exposureStateController.stream;

  // FOCUS CONTROL METHODS (Task 5.2)

  /// Set manual focus distance (0.0 = nearest, 1.0 = infinity)
  Future<double?> setFocusDistance(double distance) async {
    if (!isEnabled) return null;

    // Validate distance range (0.0 to 1.0)
    distance = distance.clamp(0.0, 1.0);

    try {
      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('setFocusDistance', {
        'distance': distance,
      });

      if (result != null) {
        final actualDistance = result['actualDistance'] as double?;

        // Update focus state stream
        _updateFocusState();

        return actualDistance;
      }

      return null;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to set focus distance: ${e.message}');
    }
  }

  /// Enable/disable autofocus
  Future<bool> setAutofocusEnabled(bool enabled) async {
    if (!isEnabled) return false;

    try {
      final bool result = await _channel.invokeMethod('setAutofocusEnabled', {
        'enabled': enabled,
      });

      // Update focus state stream
      _updateFocusState();

      return result;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to set autofocus: ${e.message}');
    }
  }

  /// Trigger autofocus at specific screen coordinates
  Future<bool> focusAtPoint(Offset screenPoint) async {
    if (!isEnabled) return false;

    try {
      final bool result = await _channel.invokeMethod('focusAtPoint', {
        'x': screenPoint.dx,
        'y': screenPoint.dy,
      });

      // Update focus state stream
      _updateFocusState();

      return result;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to focus at point: ${e.message}');
    }
  }

  /// Get current focus state
  Future<CameraFocusState> getCurrentFocusState() async {
    if (!isEnabled) {
      return const CameraFocusState(
        isAutofocusEnabled: true,
        currentFocusMode: FocusMode.auto,
        isFocusLocked: false,
        isFocusPeakingEnabled: false,
        focusStatus: FocusStatus.inactive,
      );
    }

    try {
      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('getCurrentFocusState');
      if (result != null) {
        return CameraFocusState.fromMap(Map<String, dynamic>.from(result));
      }

      return const CameraFocusState(
        isAutofocusEnabled: true,
        currentFocusMode: FocusMode.auto,
        isFocusLocked: false,
        isFocusPeakingEnabled: false,
        focusStatus: FocusStatus.inactive,
      );
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to get focus state: ${e.message}');
    }
  }

  /// Get supported focus modes for this camera
  Future<List<FocusMode>> getSupportedFocusModes() async {
    if (!isEnabled) return [FocusMode.auto];

    try {
      final List<dynamic> result =
          await _channel.invokeMethod('getSupportedFocusModes');
      return result
          .map((name) => FocusMode.values.firstWhere(
                (mode) => mode.name == name,
                orElse: () => FocusMode.auto,
              ))
          .toList();
    } on PlatformException catch (e) {
      throw ARCaptureException(
          'Failed to get supported focus modes: ${e.message}');
    }
  }

  /// Set focus mode (auto, macro, infinity, etc.)
  Future<bool> setFocusMode(FocusMode mode) async {
    if (!isEnabled) return false;

    try {
      final bool result = await _channel.invokeMethod('setFocusMode', {
        'mode': mode.name,
      });

      // Update focus state stream
      _updateFocusState();

      return result;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to set focus mode: ${e.message}');
    }
  }

  /// Update focus state stream
  Future<void> _updateFocusState() async {
    try {
      final state = await getCurrentFocusState();
      _focusStateController.add(state);
    } catch (e) {
      debugPrint('Failed to update focus state: $e');
    }
  }

  /// Stream for real-time focus state updates
  Stream<CameraFocusState> get focusStateStream => _focusStateController.stream;

  // WHITE BALANCE CONTROL METHODS (Task 5.3)

  /// Set white balance mode (auto, preset, manual)
  Future<bool> setWhiteBalanceMode(WhiteBalanceMode mode) async {
    if (!isEnabled) return false;

    try {
      final bool result = await _channel.invokeMethod('setWhiteBalanceMode', {
        'mode': mode.name,
      });

      // Update white balance state stream
      _updateWhiteBalanceState();

      return result;
    } on PlatformException catch (e) {
      throw ARCaptureException(
          'Failed to set white balance mode: ${e.message}');
    }
  }

  /// Set manual color temperature in Kelvin (2000-8000K typical)
  Future<int?> setColorTemperature(int colorTemperatureK) async {
    if (!isEnabled) return null;

    try {
      // Validate against supported temperature range
      final supportedRange = await getSupportedColorTemperatureRange();
      if (supportedRange != null && supportedRange.isNotEmpty) {
        final minTemp = supportedRange['min'] ?? 2000;
        final maxTemp = supportedRange['max'] ?? 8000;

        if (colorTemperatureK < minTemp || colorTemperatureK > maxTemp) {
          final clampedTemp = colorTemperatureK.clamp(minTemp, maxTemp);
          debugPrint(
              'Color temperature ${colorTemperatureK}K out of range [$minTemp-${maxTemp}K], using ${clampedTemp}K');
          colorTemperatureK = clampedTemp;
        }
      }

      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('setColorTemperature', {
        'colorTemperatureK': colorTemperatureK,
      });

      if (result != null) {
        final actualTemp = result['actualColorTemperature'] as int?;

        // Update white balance state stream
        _updateWhiteBalanceState();

        return actualTemp;
      }

      return null;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to set color temperature: ${e.message}');
    }
  }

  /// Get current white balance state
  Future<CameraWhiteBalanceState> getCurrentWhiteBalanceState() async {
    if (!isEnabled) {
      return const CameraWhiteBalanceState(
        currentMode: WhiteBalanceMode.auto,
        isWhiteBalanceLocked: false,
        isAutoWhiteBalanceEnabled: true,
        status: WhiteBalanceStatus.inactive,
      );
    }

    try {
      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('getCurrentWhiteBalanceState');
      if (result != null) {
        return CameraWhiteBalanceState.fromMap(
            Map<String, dynamic>.from(result));
      }

      return const CameraWhiteBalanceState(
        currentMode: WhiteBalanceMode.auto,
        isWhiteBalanceLocked: false,
        isAutoWhiteBalanceEnabled: true,
        status: WhiteBalanceStatus.inactive,
      );
    } on PlatformException catch (e) {
      throw ARCaptureException(
          'Failed to get white balance state: ${e.message}');
    }
  }

  /// Get supported color temperature range for manual mode
  Future<Map<String, int>?> getSupportedColorTemperatureRange() async {
    if (!isEnabled) return null;

    try {
      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('getSupportedColorTemperatureRange');
      if (result != null) {
        return {
          'min': result['min'] as int? ?? 2000,
          'max': result['max'] as int? ?? 8000,
        };
      }
      return null;
    } on PlatformException catch (e) {
      throw ARCaptureException(
          'Failed to get supported color temperature range: ${e.message}');
    }
  }

  /// Lock current white balance to prevent auto-adjustment
  Future<bool> lockWhiteBalance() async {
    if (!isEnabled) return false;

    try {
      final bool result = await _channel.invokeMethod('lockWhiteBalance');

      // Update white balance state stream
      _updateWhiteBalanceState();

      return result;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to lock white balance: ${e.message}');
    }
  }

  /// Unlock white balance to resume auto-adjustment
  Future<bool> unlockWhiteBalance() async {
    if (!isEnabled) return false;

    try {
      final bool result = await _channel.invokeMethod('unlockWhiteBalance');

      // Update white balance state stream
      _updateWhiteBalanceState();

      return result;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to unlock white balance: ${e.message}');
    }
  }

  /// Set white balance from screen point (touch white balance)
  Future<bool> setWhiteBalanceFromPoint(Offset screenPoint) async {
    if (!isEnabled) return false;

    try {
      final bool result =
          await _channel.invokeMethod('setWhiteBalanceFromPoint', {
        'x': screenPoint.dx,
        'y': screenPoint.dy,
      });

      // Update white balance state stream
      _updateWhiteBalanceState();

      return result;
    } on PlatformException catch (e) {
      throw ARCaptureException(
          'Failed to set white balance from point: ${e.message}');
    }
  }

  /// Get supported white balance modes
  Future<List<WhiteBalanceMode>> getSupportedWhiteBalanceModes() async {
    if (!isEnabled) return [WhiteBalanceMode.auto];

    try {
      final List<dynamic> result =
          await _channel.invokeMethod('getSupportedWhiteBalanceModes');
      return result
          .map((name) => WhiteBalanceMode.values.firstWhere(
                (mode) => mode.name == name,
                orElse: () => WhiteBalanceMode.auto,
              ))
          .toList();
    } on PlatformException catch (e) {
      throw ARCaptureException(
          'Failed to get supported white balance modes: ${e.message}');
    }
  }

  /// Update white balance state stream
  Future<void> _updateWhiteBalanceState() async {
    try {
      final state = await getCurrentWhiteBalanceState();
      _whiteBalanceStateController.add(state);
    } catch (e) {
      debugPrint('Failed to update white balance state: $e');
    }
  }

  /// Stream of white balance state changes
  Stream<CameraWhiteBalanceState> get whiteBalanceStateStream =>
      _whiteBalanceStateController.stream;

  /// Set flash mode (auto, on, off, torch)
  Future<bool> setFlashMode(FlashMode mode) async {
    if (!isEnabled) return false;

    try {
      // Check flash availability first
      final isAvailable = await isFlashAvailable();
      if (!isAvailable && mode != FlashMode.off) {
        debugPrint('Flash not available, setting mode to off');
        mode = FlashMode.off;
      }

      final bool result = await _channel.invokeMethod('setFlashMode', {
        'mode': mode.name,
      });

      // Update flash state stream
      _updateFlashState();

      return result;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to set flash mode: ${e.message}');
    }
  }

  /// Get current flash mode and state.
  Future<CameraFlashState> getCurrentFlashState() async {
    if (!isEnabled) {
      return const CameraFlashState(
        currentFlashMode: FlashMode.off,
        isTorchEnabled: false,
        isFlashReady: false,
        flashStatus: FlashStatus.unavailable,
      );
    }

    try {
      final Map<dynamic, dynamic>? result =
          await _channel.invokeMethod('getCurrentSceneFlashState');
      if (result != null) {
        return CameraFlashState.fromMap(Map<String, dynamic>.from(result));
      }

      return const CameraFlashState(
        currentFlashMode: FlashMode.off,
        isTorchEnabled: false,
        isFlashReady: false,
        flashStatus: FlashStatus.unavailable,
      );
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to get scene/flash state: ${e.message}');
    }
  }

  /// Check if flash is available on this device
  Future<bool> isFlashAvailable() async {
    if (!isEnabled) return false;

    try {
      return await _channel.invokeMethod('isFlashAvailable');
    } on PlatformException catch (e) {
      throw ARCaptureException(
          'Failed to check flash availability: ${e.message}');
    }
  }

  /// Enable/disable torch (continuous flash) mode
  Future<bool> setTorchEnabled(bool enabled) async {
    if (!isEnabled) return false;

    try {
      // Check flash availability first
      if (enabled && !await isFlashAvailable()) {
        debugPrint('Flash not available, cannot enable torch');
        return false;
      }

      final bool result = await _channel.invokeMethod('setTorchEnabled', {
        'enabled': enabled,
      });

      // Update flash state stream
      _updateFlashState();

      return result;
    } on PlatformException catch (e) {
      throw ARCaptureException('Failed to set torch: ${e.message}');
    }
  }

  /// Update flash state stream.
  Future<void> _updateFlashState() async {
    try {
      final state = await getCurrentFlashState();
      _flashStateController.add(state);
    } catch (e) {
      debugPrint('Failed to update flash state: $e');
    }
  }

  /// Stream of flash state changes.
  Stream<CameraFlashState> get flashStateStream => _flashStateController.stream;

  // CAMERA PARAMETER PROFILES AND QUICK SWITCHING (Task 5.5)

  /// Save current camera parameters as a named profile
  Future<bool> saveProfile(String profileName, {String? description}) async {
    if (!isEnabled) return false;

    try {
      // Capture current parameters
      final profile = await createProfileFromCurrentSettings(profileName,
          description: description);

      // Get existing profiles
      final existingProfiles = await getSavedProfiles();

      // Remove existing profile with same name
      existingProfiles.removeWhere((p) => p.name == profileName);

      // Add new profile
      existingProfiles.add(profile);

      // Save to device storage
      final profilesJson = existingProfiles.map((p) => p.toMap()).toList();

      try {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString(_profilesKey, jsonEncode(profilesJson));

        _profileStatusController.add(ProfileApplicationStatus.saved);
        debugPrint('Profile "$profileName" saved successfully');

        return true;
      } catch (e) {
        debugPrint('Failed to save profile to storage: $e');
        return false;
      }
    } catch (e) {
      throw ARCaptureException('Failed to save profile: $e');
    }
  }

  /// Load and apply a saved profile
  Future<bool> loadProfile(String profileName) async {
    if (!isEnabled) return false;

    try {
      _profileStatusController.add(ProfileApplicationStatus.loading);

      // Get saved profiles
      final profiles = await getSavedProfiles();
      final profile = profiles.where((p) => p.name == profileName).firstOrNull;

      if (profile == null) {
        // Check built-in profiles
        final builtInProfiles = await getBuiltInProfiles();
        final builtInProfile =
            builtInProfiles.where((p) => p.name == profileName).firstOrNull;

        if (builtInProfile == null) {
          _profileStatusController.add(ProfileApplicationStatus.error);
          throw ARCaptureException('Profile "$profileName" not found');
        }

        return await _applyProfile(builtInProfile);
      }

      return await _applyProfile(profile);
    } catch (e) {
      _profileStatusController.add(ProfileApplicationStatus.error);
      throw ARCaptureException('Failed to load profile: $e');
    }
  }

  /// Get list of all saved profiles
  Future<List<CameraProfile>> getSavedProfiles() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final profilesJsonString = prefs.getString(_profilesKey);

      if (profilesJsonString == null) {
        return [];
      }

      final profilesJson = jsonDecode(profilesJsonString) as List<dynamic>;
      return profilesJson
          .map((json) => CameraProfile.fromMap(Map<String, dynamic>.from(json)))
          .toList();
    } catch (e) {
      debugPrint('Failed to get saved profiles: $e');
      return [];
    }
  }

  /// Delete a saved profile
  Future<bool> deleteProfile(String profileName) async {
    try {
      final profiles = await getSavedProfiles();
      profiles.removeWhere((p) => p.name == profileName);

      final profilesJson = profiles.map((p) => p.toMap()).toList();
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_profilesKey, jsonEncode(profilesJson));

      debugPrint('Profile "$profileName" deleted successfully');
      return true;
    } catch (e) {
      debugPrint('Failed to delete profile: $e');
      return false;
    }
  }

  /// Export profile to JSON string for sharing
  Future<String?> exportProfile(String profileName) async {
    try {
      final profiles = await getSavedProfiles();
      final profile = profiles.where((p) => p.name == profileName).firstOrNull;

      if (profile == null) {
        // Check built-in profiles
        final builtInProfiles = await getBuiltInProfiles();
        final builtInProfile =
            builtInProfiles.where((p) => p.name == profileName).firstOrNull;
        return builtInProfile?.toJsonString();
      }

      return profile.toJsonString();
    } catch (e) {
      debugPrint('Failed to export profile: $e');
      return null;
    }
  }

  /// Import profile from JSON string
  Future<bool> importProfile(String jsonData, {String? customName}) async {
    try {
      final profileMap = jsonDecode(jsonData) as Map<String, dynamic>;
      var profile = CameraProfile.fromMap(profileMap);

      // Use custom name if provided
      if (customName != null) {
        profile = CameraProfile(
          name: customName,
          description: profile.description,
          createdAt: DateTime.now(),
          parameters: profile.parameters,
          author: profile.author,
        );
      }

      // Save imported profile
      return await saveProfile(profile.name, description: profile.description);
    } catch (e) {
      debugPrint('Failed to import profile: $e');
      return false;
    }
  }

  /// Get built-in professional profiles
  Future<List<CameraProfile>> getBuiltInProfiles() async {
    return [
      // Portrait profile
      CameraProfile(
        name: 'Portrait',
        description:
            'Optimized for portrait photography with shallow depth of field',
        createdAt: DateTime.now(),
        isBuiltIn: true,
        author: 'AR Flutter Plugin',
        parameters: const CameraProfileParameters(
          focusMode: FocusMode.auto,
          whiteBalanceMode: WhiteBalanceMode.auto,
          flashMode: FlashMode.auto,
          autoExposureEnabled: true,
          exposureCompensation: 0.3, // Slightly overexpose for skin tones
        ),
      ),

      // Landscape profile
      CameraProfile(
        name: 'Landscape',
        description:
            'Optimized for landscape photography with extended depth of field',
        createdAt: DateTime.now(),
        isBuiltIn: true,
        author: 'AR Flutter Plugin',
        parameters: const CameraProfileParameters(
          focusMode: FocusMode.infinity,
          whiteBalanceMode: WhiteBalanceMode.daylight,
          flashMode: FlashMode.off,
          autoExposureEnabled: true,
          exposureCompensation: -0.3, // Slightly underexpose for richer colors
        ),
      ),

      // Night profile
      CameraProfile(
        name: 'Night',
        description: 'Optimized for low-light photography',
        createdAt: DateTime.now(),
        isBuiltIn: true,
        author: 'AR Flutter Plugin',
        parameters: CameraProfileParameters(
          focusMode: FocusMode.auto,
          whiteBalanceMode: WhiteBalanceMode.auto,
          flashMode: FlashMode.auto,
          autoExposureEnabled: false,
          isoValue: 1600,
          exposureTime: const Duration(milliseconds: 100),
        ),
      ),

      // Sports profile
      CameraProfile(
        name: 'Sports',
        description: 'Optimized for fast action photography',
        createdAt: DateTime.now(),
        isBuiltIn: true,
        author: 'AR Flutter Plugin',
        parameters: CameraProfileParameters(
          focusMode: FocusMode.continuous,
          whiteBalanceMode: WhiteBalanceMode.auto,
          flashMode: FlashMode.off,
          autoExposureEnabled: false,
          isoValue: 800,
          exposureTime: const Duration(milliseconds: 8), // 1/125s
        ),
      ),
    ];
  }

  /// Apply quick profile by type (portrait, landscape, etc.)
  Future<bool> applyQuickProfile(QuickProfileType profileType) async {
    if (!isEnabled) return false;

    try {
      String profileName;
      switch (profileType) {
        case QuickProfileType.portrait:
          profileName = 'Portrait';
          break;
        case QuickProfileType.landscape:
          profileName = 'Landscape';
          break;
        case QuickProfileType.night:
          profileName = 'Night';
          break;
        case QuickProfileType.sports:
          profileName = 'Sports';
          break;
        case QuickProfileType.auto:
        case QuickProfileType.macro:
        default:
          // Use auto settings for unsupported types
          return await _applyAutoProfile();
      }

      return await loadProfile(profileName);
    } catch (e) {
      throw ARCaptureException('Failed to apply quick profile: $e');
    }
  }

  /// Create profile from current camera settings
  Future<CameraProfile> createProfileFromCurrentSettings(String name,
      {String? description}) async {
    // Gather all current camera parameters
    final exposureState = await getCurrentExposureState();
    final focusState = await getCurrentFocusState();
    final whiteBalanceState = await getCurrentWhiteBalanceState();
    final flashState = await getCurrentFlashState();

    final parameters = CameraProfileParameters(
      isoValue: exposureState.currentISO,
      exposureTime: exposureState.currentExposureTime,
      autoExposureEnabled: exposureState.isAutoExposureEnabled,
      exposureCompensation: exposureState.exposureCompensation,
      focusDistance: focusState.currentFocusDistance,
      autofocusEnabled: focusState.isAutofocusEnabled,
      focusMode: focusState.currentFocusMode,
      whiteBalanceMode: whiteBalanceState.currentMode,
      colorTemperature: whiteBalanceState.currentColorTemperature,
      flashMode: flashState.currentFlashMode,
    );

    return CameraProfile(
      name: name,
      description: description,
      createdAt: DateTime.now(),
      parameters: parameters,
    );
  }

  /// Apply profile parameters to camera
  Future<bool> _applyProfile(CameraProfile profile) async {
    try {
      _profileStatusController.add(ProfileApplicationStatus.applying);

      final params = profile.parameters;
      bool allSuccessful = true;

      // Apply parameters in stable order.

      // 1. White balance settings
      if (!await setWhiteBalanceMode(params.whiteBalanceMode))
        allSuccessful = false;
      if (params.colorTemperature != null) {
        final result = await setColorTemperature(params.colorTemperature!);
        if (result == null) allSuccessful = false;
      }

      // 2. Focus settings
      if (!await setAutofocusEnabled(params.autofocusEnabled))
        allSuccessful = false;
      if (!await setFocusMode(params.focusMode)) allSuccessful = false;
      if (params.focusDistance != null) {
        final result = await setFocusDistance(params.focusDistance!);
        if (result == null) allSuccessful = false;
      }

      // 3. Exposure settings
      if (!await setAutoExposureEnabled(params.autoExposureEnabled))
        allSuccessful = false;
      if (params.isoValue != null) {
        final result = await setISO(params.isoValue!);
        if (result == null) allSuccessful = false;
      }
      if (params.exposureTime != null) {
        final result = await setExposureTime(params.exposureTime!);
        if (result == null) allSuccessful = false;
      }
      if (params.exposureCompensation != null) {
        final result =
            await setExposureCompensation(params.exposureCompensation!);
        if (result == null) allSuccessful = false;
      }

      // 4. Flash settings
      if (!await setFlashMode(params.flashMode)) allSuccessful = false;

      // Update last used timestamp
      await _updateProfileLastUsed(profile.name);

      if (allSuccessful) {
        _profileStatusController.add(ProfileApplicationStatus.applied);
        debugPrint('Profile "${profile.name}" applied successfully');
      } else {
        _profileStatusController.add(ProfileApplicationStatus.partiallyApplied);
        debugPrint(
            'Profile "${profile.name}" partially applied (some parameters failed)');
      }

      return allSuccessful;
    } catch (e) {
      _profileStatusController.add(ProfileApplicationStatus.error);
      debugPrint('Error applying profile: $e');
      return false;
    }
  }

  /// Apply automatic profile settings
  Future<bool> _applyAutoProfile() async {
    return await _applyProfile(CameraProfile(
      name: 'Auto',
      description: 'Automatic camera settings',
      createdAt: DateTime.now(),
      parameters: const CameraProfileParameters(),
    ));
  }

  /// Update profile last used timestamp
  Future<void> _updateProfileLastUsed(String profileName) async {
    try {
      final profiles = await getSavedProfiles();
      final profileIndex = profiles.indexWhere((p) => p.name == profileName);

      if (profileIndex != -1) {
        final profile = profiles[profileIndex];
        final updatedProfile = CameraProfile(
          name: profile.name,
          description: profile.description,
          createdAt: profile.createdAt,
          lastUsed: DateTime.now(),
          parameters: profile.parameters,
          isBuiltIn: profile.isBuiltIn,
          author: profile.author,
        );

        profiles[profileIndex] = updatedProfile;

        final profilesJson = profiles.map((p) => p.toMap()).toList();
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString(_profilesKey, jsonEncode(profilesJson));
      }
    } catch (e) {
      debugPrint('Failed to update profile last used: $e');
    }
  }

  /// Stream for profile application status
  Stream<ProfileApplicationStatus> get profileStatusStream =>
      _profileStatusController.stream;

  /// Real-time pose data stream (empty on unsupported platforms)
  Stream<ARFramePose> get poseDataStream => _poseStreamController.stream;

  /// Platform method call handler for incoming events
  Future<void> _platformCallHandler(MethodCall call) async {
    try {
      switch (call.method) {
        case 'onPoseUpdate':
          final pose = ARFramePose.fromMap(_deepCastMap(call.arguments));
          _poseStreamController.add(pose);
          break;
        case 'onAutomaticCapture':
          final captureResult =
              ARCaptureResult.fromMap(_deepCastMap(call.arguments));
          _captureResultController.add(captureResult);
          break;
        case 'onCaptureError':
          final error = call.arguments['error'] as String?;
          debugPrint('Capture error: $error');
          break;
        default:
          debugPrint('Unknown method call: ${call.method}');
      }
    } catch (e) {
      debugPrint('Error handling platform call: $e');
    }
  }

  // Deprecated runtime resolution methods with helpful error messages

  /// DEPRECATED: Resolution is fixed at configuration time
  /// To use a different resolution, create a new ARSessionManager with different ARCaptureConfig
  @Deprecated(
      'Resolution is fixed at configuration time. Create new session for different resolution.')
  Future<void> setResolution(CameraResolution resolution) async {
    throw UnsupportedError(
        'Resolution changes not supported. Create new ARSessionManager with different ARCaptureConfig to use different resolution.');
  }

  /// DEPRECATED: Use ARCameraCapabilities for pre-configuration queries
  @Deprecated(
      'Use ARCameraCapabilities.getSupportedResolutions() for pre-configuration queries.')
  Future<List<CameraResolution>> getSupportedResolutions() async {
    throw UnsupportedError(
        'Runtime resolution queries not supported. Use ARCameraCapabilities.getSupportedResolutions() before creating session.');
  }

  /// DEPRECATED: Use ARCameraCapabilities for resolution validation
  @Deprecated(
      'Use ARCameraCapabilities.isResolutionSupported() for pre-configuration validation.')
  Future<bool> isResolutionSupported(CameraResolution resolution) async {
    throw UnsupportedError(
        'Runtime resolution validation not supported. Use ARCameraCapabilities.isResolutionSupported() before creating session.');
  }

  /// Cleanup resources
  void dispose() {
    if (_isDisposed) {
      return;
    }
    _isDisposed = true;
    try {
      _channel.invokeMethod('dispose');
    } catch (e) {
      debugPrint('Error disposing capture manager: $e');
    }

    _poseStreamController.close();
    _captureResultController.close();
    _exposureStateController.close();
    _focusStateController.close();
    _whiteBalanceStateController.close();
    _flashStateController.close();
    _profileStatusController.close();
  }
}

/// Exception thrown when capture operations fail
class ARCaptureException implements Exception {
  final String message;
  final String? code;
  final Object? details;

  const ARCaptureException(this.message, {this.code, this.details});

  @override
  String toString() {
    if (code == null || code!.isEmpty) {
      return 'ARCaptureException: $message';
    }
    return 'ARCaptureException($code): $message';
  }
}

ARCaptureException _captureExceptionFromPlatformException(
  PlatformException exception, {
  required String operation,
}) {
  return ARCaptureException(
    exception.message ?? 'Failed to $operation',
    code: exception.code,
    details: exception.details,
  );
}

Map<String, dynamic> _deepCastMap(dynamic value) {
  if (value is! Map) {
    throw ArgumentError.value(value, 'value', 'Expected a map');
  }

  return value.map<String, dynamic>((key, entry) {
    if (entry is Map) {
      return MapEntry(key.toString(), _deepCastMap(entry));
    }
    if (entry is List) {
      return MapEntry(key.toString(), _deepCastList(entry));
    }
    return MapEntry(key.toString(), entry);
  });
}

List<dynamic> _deepCastList(List<dynamic> value) {
  return value.map<dynamic>((entry) {
    if (entry is Map) {
      return _deepCastMap(entry);
    }
    if (entry is List) {
      return _deepCastList(entry.cast<dynamic>());
    }
    return entry;
  }).toList();
}

/// Quick profile types for common scenarios
enum QuickProfileType {
  auto,
  portrait,
  landscape,
  night,
  sports,
  macro,
}

/// Profile application status
enum ProfileApplicationStatus {
  loading,
  applying,
  applied,
  partiallyApplied,
  saved,
  error,
}

/// All camera parameters in a profile
class CameraProfileParameters {
  final int? isoValue;
  final Duration? exposureTime;
  final bool autoExposureEnabled;
  final double? exposureCompensation;
  final double? focusDistance;
  final bool autofocusEnabled;
  final FocusMode focusMode;
  final WhiteBalanceMode whiteBalanceMode;
  final int? colorTemperature;
  final FlashMode flashMode;

  const CameraProfileParameters({
    this.isoValue,
    this.exposureTime,
    this.autoExposureEnabled = true,
    this.exposureCompensation,
    this.focusDistance,
    this.autofocusEnabled = true,
    this.focusMode = FocusMode.auto,
    this.whiteBalanceMode = WhiteBalanceMode.auto,
    this.colorTemperature,
    this.flashMode = FlashMode.auto,
  });

  factory CameraProfileParameters.fromMap(Map<String, dynamic> map) {
    return CameraProfileParameters(
      isoValue: map['isoValue'] as int?,
      exposureTime: map['exposureTime'] != null
          ? Duration(microseconds: map['exposureTime'] as int)
          : null,
      autoExposureEnabled: map['autoExposureEnabled'] as bool? ?? true,
      exposureCompensation: map['exposureCompensation'] as double?,
      focusDistance: map['focusDistance'] as double?,
      autofocusEnabled: map['autofocusEnabled'] as bool? ?? true,
      focusMode: FocusMode.values.firstWhere(
        (mode) => mode.name == map['focusMode'],
        orElse: () => FocusMode.auto,
      ),
      whiteBalanceMode: WhiteBalanceMode.values.firstWhere(
        (mode) => mode.name == map['whiteBalanceMode'],
        orElse: () => WhiteBalanceMode.auto,
      ),
      colorTemperature: map['colorTemperature'] as int?,
      flashMode: FlashMode.values.firstWhere(
        (mode) => mode.name == map['flashMode'],
        orElse: () => FlashMode.auto,
      ),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'isoValue': isoValue,
      'exposureTime': exposureTime?.inMicroseconds,
      'autoExposureEnabled': autoExposureEnabled,
      'exposureCompensation': exposureCompensation,
      'focusDistance': focusDistance,
      'autofocusEnabled': autofocusEnabled,
      'focusMode': focusMode.name,
      'whiteBalanceMode': whiteBalanceMode.name,
      'colorTemperature': colorTemperature,
      'flashMode': flashMode.name,
    };
  }
}

/// Complete camera parameter profile
class CameraProfile {
  final String name;
  final String? description;
  final DateTime createdAt;
  final DateTime? lastUsed;
  final CameraProfileParameters parameters;
  final bool isBuiltIn;
  final String? author;

  const CameraProfile({
    required this.name,
    this.description,
    required this.createdAt,
    this.lastUsed,
    required this.parameters,
    this.isBuiltIn = false,
    this.author,
  });

  factory CameraProfile.fromMap(Map<String, dynamic> map) {
    return CameraProfile(
      name: map['name'] as String,
      description: map['description'] as String?,
      createdAt: DateTime.parse(map['createdAt'] as String),
      lastUsed: map['lastUsed'] != null
          ? DateTime.parse(map['lastUsed'] as String)
          : null,
      parameters: CameraProfileParameters.fromMap(
          Map<String, dynamic>.from(map['parameters'] as Map)),
      isBuiltIn: map['isBuiltIn'] as bool? ?? false,
      author: map['author'] as String?,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'name': name,
      'description': description,
      'createdAt': createdAt.toIso8601String(),
      'lastUsed': lastUsed?.toIso8601String(),
      'parameters': parameters.toMap(),
      'isBuiltIn': isBuiltIn,
      'author': author,
    };
  }

  String toJsonString() {
    return jsonEncode(toMap());
  }
}
