import 'dart:async';
import 'dart:io';
import 'dart:math' show sqrt;
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
import 'package:ar_flutter_plugin_2/models/ar_anchor.dart';
import 'package:ar_flutter_plugin_2/models/ar_hittest_result.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:ar_flutter_plugin_2/models/ar_recording.dart';
import 'package:ar_flutter_plugin_2/models/canonical_ready_hint.dart';
import 'package:ar_flutter_plugin_2/capabilities/ar_camera_capabilities.dart';
import 'package:ar_flutter_plugin_2/utils/json_converters.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:vector_math/vector_math_64.dart';
import 'ar_capture_manager.dart';

// Type definitions to enforce a consistent use of the API
typedef ARHitResultHandler = void Function(List<ARHitTestResult> hits);
typedef ARPlaneResultHandler = void Function(int planeCount);
typedef ErrorHandler = void Function(String error);

/// Completes after worker advisory admission only, never after native polling.
typedef CanonicalReadyHintHandler = Future<bool> Function(
    CanonicalReadyHintV1 hint);

/// AR Session states for lifecycle management
enum ARSessionState {
  notInitialized,
  initializing,
  initialized,
  resuming,
  running,
  pausing,
  paused,
  error,
  closing,
  retained,
  disposed,
}

/// AR Configuration for session setup
class ARConfiguration {
  final bool enableCapture;
  final ARCaptureConfig? captureConfig;
  final PlaneDetectionConfig? planeDetectionConfig;
  final bool showAnimatedGuide;
  final bool showFeaturePoints;
  final bool showPlanes;
  final String? customPlaneTexturePath;
  final bool showWorldOrigin;
  final bool handleTaps;
  final bool handlePans;
  final bool handleRotation;
  final bool debug;

  const ARConfiguration({
    this.enableCapture = false,
    this.captureConfig,
    this.planeDetectionConfig,
    this.showAnimatedGuide = true,
    this.showFeaturePoints = false,
    this.showPlanes = true,
    this.customPlaneTexturePath,
    this.showWorldOrigin = false,
    this.handleTaps = true,
    this.handlePans = false,
    this.handleRotation = false,
    this.debug = false,
  });
}

/// Manages the session configuration, parameters and events of an [ARView]
class ARSessionManager {
  final int _channelId;

  /// Platform channel used for communication from and to [ARSessionManager]
  late MethodChannel _channel;

  /// Complete AR Configuration for session setup
  final ARConfiguration? _arConfig;

  /// Debugging status flag. If true, all platform calls are printed. Defaults to false.
  final bool debug;

  /// Context of the [ARView] widget that this manager is attributed to
  final BuildContext buildContext;

  /// Determines the types of planes ARCore and ARKit should show
  final PlaneDetectionConfig planeDetectionConfig;

  /// Capture manager - created at construction time if config provided
  ARCaptureManager? _captureManager;

  /// Current session state
  ARSessionState _sessionState = ARSessionState.notInitialized;
  bool _closeStarted = false;
  Future<void>? _disposeAttempt;

  /// Session state change stream controller
  final StreamController<ARSessionState> _stateController =
      StreamController.broadcast();

  /// Initialization completion completer
  Completer<void>? _initializationCompleter;

  /// Error state tracker
  String? _lastError;

  /// Receives hit results from user taps with tracked planes or feature points
  ARHitResultHandler? onPlaneOrPointTap;

  /// Receives total number of Planes when a plane is detected and added to the view
  ARPlaneResultHandler? onPlaneDetected;

  /// Callback that is triggered once error is triggered
  ErrorHandler? onError;

  /// Debug/profile ARCore playback: the dataset reached its end. The argument
  /// map carries `path`, `restart` (view, session or off) and `restarts`.
  void Function(Map<String, Object?> event)? onArPlaybackFinished;

  /// Debug/profile ARCore playback: custom track records of a replayed frame
  /// (tracks named by `ARPlaybackConfig.trackIds`), in recording order.
  void Function(List<ARPlaybackTrackRecord> records)? onArPlaybackTrackData;

  CanonicalReadyHintHandler? _canonicalReadyHandler;
  bool _canonicalReadyDeliveryPending = false;
  bool _canonicalReadyRegistrationPending = false;
  static bool _canonicalReadyRegistrationReserved = false;

  /// Synchronous local fence; no Future, codec buffer or platform invocation.
  void clearCanonicalReadyHandler(CanonicalReadyHintHandler handler) {
    if (identical(_canonicalReadyHandler, handler))
      _canonicalReadyHandler = null;
  }

  Future<bool> _invokeCanonicalReadyRegistration(
      String method, Uint8List bytes) async {
    if (_closeStarted ||
        _canonicalReadyRegistrationPending ||
        _canonicalReadyRegistrationReserved) return false;
    _canonicalReadyRegistrationPending = true;
    _canonicalReadyRegistrationReserved = true;
    final invocation = _invokeOpenSession<bool>(method, bytes);
    // A lost reply keeps one reservation occupied; fallback cannot queue RPCs.
    unawaited(invocation.then<void>((_) {
      _canonicalReadyRegistrationPending = false;
      _canonicalReadyRegistrationReserved = false;
    }, onError: (Object _, StackTrace __) {
      _canonicalReadyRegistrationPending = false;
      _canonicalReadyRegistrationReserved = false;
    }));
    try {
      return await invocation.timeout(const Duration(seconds: 2)) ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    } on TimeoutException {
      return false;
    }
  }

  /// Optional Android ready capability on this existing root handler. Missing
  /// or older/iOS peers retain ordinary polling. No second manager is created.
  Future<bool> registerCanonicalReady(
      CanonicalReadyHintV1 owner, CanonicalReadyHintHandler handler) async {
    if (owner.viewId != channelId || _closeStarted) return false;
    _canonicalReadyHandler = handler;
    try {
      final supported = await _invokeCanonicalReadyRegistration(
          'registerCanonicalReady', owner.bytes);
      if (!supported && identical(_canonicalReadyHandler, handler))
        _canonicalReadyHandler = null;
      return supported;
    } on MissingPluginException {
      if (identical(_canonicalReadyHandler, handler))
        _canonicalReadyHandler = null;
      return false;
    } on PlatformException {
      if (identical(_canonicalReadyHandler, handler))
        _canonicalReadyHandler = null;
      return false;
    }
  }

  /// Locally fences old delivery before the optional native unregister reply.
  Future<void> unregisterCanonicalReady(
      CanonicalReadyHintV1? owner, CanonicalReadyHintHandler handler) async {
    clearCanonicalReadyHandler(handler);
    if (owner == null) return;
    try {
      await _invokeCanonicalReadyRegistration(
          'unregisterCanonicalReady', owner.bytes);
    } on MissingPluginException {
      /* Older peers have no advisory subscription. */
    } on PlatformException {/* Replaced/disposed peers are already fenced. */}
  }

  // Legacy constructor for backward compatibility
  ARSessionManager(int id, this.buildContext, this.planeDetectionConfig,
      {this.debug = false, ARCaptureConfig? captureConfig})
      : _arConfig = null,
        _channelId = id {
    _channel = MethodChannel('arsession_$id');
    _channel.setMethodCallHandler(_platformCallHandler);

    try {
      // Validate configurations before initialization
      _validateConfigurations(planeDetectionConfig, captureConfig);

      // Initialize capture manager if config provided
      if (captureConfig != null) {
        // Reader capability survives the default-off producer experiment.
        _captureManager = ARCaptureManager(this, captureConfig, buildContext,
            allowAssessedQuality: true);
        if (debug) {
          print("ARSessionManager initialized with capture capabilities");
        }
      } else {
        if (debug) {
          print("ARSessionManager initialized without capture capabilities");
        }
      }

      _sessionState = ARSessionState.initialized;
      if (debug) {
        print("ARSessionManager initialized");
      }
    } catch (e) {
      _sessionState = ARSessionState.error;
      _lastError = e.toString();
      throw ARSessionException('ARSessionManager initialization failed: $e');
    }
  }

  // Enhanced constructor with ARConfiguration
  ARSessionManager.withConfig({
    required this.buildContext,
    required ARConfiguration arConfig,
    int? id,
  })  : _arConfig = arConfig,
        debug = arConfig.debug,
        planeDetectionConfig =
            arConfig.planeDetectionConfig ?? PlaneDetectionConfig.horizontal,
        _channelId = id ?? DateTime.now().millisecondsSinceEpoch {
    _channel = MethodChannel('arsession_$_channelId');
    _channel.setMethodCallHandler(_platformCallHandler);

    if (debug) {
      print("ARSessionManager created with enhanced configuration");
    }
  }

  /// Access to capture functionality (non-null if captureConfig was provided)
  ARCaptureManager? get captureManager => _captureManager;

  /// The platform-view specific channel id shared by session/object/anchor/capture managers.
  int get channelId => _channelId;

  /// Check if capture manager is available
  bool get hasCaptureManager => _captureManager != null;

  /// Check if capture is currently enabled
  bool get isCaptureEnabled => _captureManager?.isEnabled ?? false;

  // PHASE 6 LIFECYCLE MANAGEMENT METHODS

  /// Check if session is initialized
  bool get isInitialized =>
      _sessionState == ARSessionState.initialized ||
      _sessionState == ARSessionState.running ||
      _sessionState == ARSessionState.paused;

  /// Check if capture is ready for use
  bool get isCaptureReady =>
      _captureManager != null && _captureManager!.isEnabled && isInitialized;

  /// Get current session state
  ARSessionState get sessionState => _sessionState;

  /// Get last error message if any
  String? get lastError => _lastError;

  /// Stream of session state changes
  Stream<ARSessionState> get stateStream => _stateController.stream;

  /// Initialize AR session with integrated capture
  Future<void> initialize() async {
    _requireOpenSession();
    if (_sessionState != ARSessionState.notInitialized) {
      if (debug) {
        print('Session already initialized or in process');
      }
      return;
    }

    try {
      _setState(ARSessionState.initializing);
      _lastError = null;

      if (debug) {
        print('Starting AR session initialization...');
      }

      // Validate configurations if using enhanced config
      if (_arConfig != null) {
        await _validateARConfiguration(_arConfig!);
      }

      _requireOpenSession();
      // Initialize capture manager if enabled
      if (_arConfig?.enableCapture == true &&
          _arConfig?.captureConfig != null) {
        if (debug) {
          print('Initializing integrated capture manager...');
        }

        _captureManager =
            ARCaptureManager(this, _arConfig!.captureConfig!, buildContext,
                allowAssessedQuality: true);

        if (debug) {
          print('Capture manager initialized successfully');
        }
      }

      // Initialize platform AR session
      await _initializePlatformSession();

      _setState(ARSessionState.initialized);

      if (debug) {
        print('AR session initialization completed');
      }
    } catch (e) {
      _lastError = e.toString();
      _setState(ARSessionState.error);
      throw ARSessionException('Failed to initialize AR session: $e');
    }
  }

  /// Resume AR session and capture
  Future<void> resume() async {
    if (_sessionState != ARSessionState.initialized &&
        _sessionState != ARSessionState.paused) {
      throw ARSessionException(
          'Cannot resume session in state: $_sessionState');
    }

    try {
      _setState(ARSessionState.resuming);
      _lastError = null;

      if (debug) {
        print('Resuming AR session...');
      }

      // Resume platform AR session
      await _invokeOpenSession('resumeSession');

      // Resume capture if available
      if (_captureManager != null) {
        // Capture manager doesn't need explicit resume - it's always ready when session is running
        if (debug) {
          print('Capture manager is ready');
        }
      }

      _setState(ARSessionState.running);

      if (debug) {
        print('AR session resumed successfully');
      }
    } catch (e) {
      _lastError = e.toString();
      _setState(ARSessionState.error);
      throw ARSessionException('Failed to resume AR session: $e');
    }
  }

  /// Pause AR session and capture
  Future<void> pause() async {
    if (_sessionState != ARSessionState.running) {
      if (debug) {
        print('Session not running, current state: $_sessionState');
      }
      return;
    }

    try {
      _setState(ARSessionState.pausing);

      if (debug) {
        print('Pausing AR session...');
      }

      // Pause platform AR session
      await _invokeOpenSession('pauseSession');

      _setState(ARSessionState.paused);

      if (debug) {
        print('AR session paused successfully');
      }
    } catch (e) {
      _lastError = e.toString();
      _setState(ARSessionState.error);
      throw ARSessionException('Failed to pause AR session: $e');
    }
  }

  void _requireOpenSession() {
    if (_closeStarted) {
      throw ARSessionException(
          'AR session is closing or retains native resources');
    }
  }

  Future<T?> _invokeOpenSession<T>(String method, [Object? arguments]) {
    _requireOpenSession();
    return _channel.invokeMethod<T>(method, arguments);
  }

  /// Shares one capture-then-view close. Failure preserves remaining owners.
  /// Throws the native [PlatformException] from capture or session release.
  Future<void> dispose() {
    final pending = _disposeAttempt;
    if (pending != null) return pending;
    if (_sessionState == ARSessionState.disposed) return Future<void>.value();
    final completion = Completer<void>();
    _disposeAttempt = completion.future;
    _closeStarted = true;
    _setState(ARSessionState.closing);
    unawaited(() async {
      try {
        await _captureManager?.dispose();
        await _channel.invokeMethod<void>('dispose');
        _captureManager = null;
        _canonicalReadyHandler = null;
        _channel.setMethodCallHandler(null);
        _setState(ARSessionState.disposed);
        unawaited(_stateController.close());
        completion.complete();
      } on Object catch (error, stack) {
        _lastError = error.toString();
        _setState(ARSessionState.retained);
        completion.completeError(error, stack);
      } finally {
        _disposeAttempt = null;
      }
    }());
    return completion.future;
  }

  /// Set session state and notify listeners
  void _setState(ARSessionState newState) {
    if (_closeStarted &&
        newState != ARSessionState.closing &&
        newState != ARSessionState.retained &&
        newState != ARSessionState.disposed) {
      return;
    }
    if (_sessionState != newState) {
      _sessionState = newState;
      _stateController.add(newState);

      if (debug) {
        print('Session state changed to: $newState');
      }
    }
  }

  /// Validate AR configuration
  Future<void> _validateARConfiguration(ARConfiguration config) async {
    if (config.enableCapture && config.captureConfig == null) {
      throw ARSessionException(
          'Capture enabled but no capture config provided');
    }

    if (config.captureConfig != null) {
      _validateCaptureConfigSync(config.captureConfig!);
      await _validateCaptureConfigAsync(config.captureConfig!);
    }

    if (debug) {
      print('AR configuration validation completed');
    }
  }

  /// Initialize platform-specific AR session
  Future<void> _initializePlatformSession() async {
    final config = _arConfig ??
        ARConfiguration(
          planeDetectionConfig: planeDetectionConfig,
          debug: debug,
        );

    await _invokeOpenSession('init', {
      'showAnimatedGuide': config.showAnimatedGuide,
      'showFeaturePoints': config.showFeaturePoints,
      'planeDetectionConfig':
          config.planeDetectionConfig?.index ?? planeDetectionConfig.index,
      'showPlanes': config.showPlanes,
      'customPlaneTexturePath': config.customPlaneTexturePath,
      'showWorldOrigin': config.showWorldOrigin,
      'handleTaps': config.handleTaps,
      'handlePans': config.handlePans,
      'handleRotation': config.handleRotation,
    });

    if (debug) {
      print('Platform AR session initialized');
    }
  }

  /// Validate all provided configurations before initialization
  void _validateConfigurations(
    PlaneDetectionConfig planeDetectionConfig,
    ARCaptureConfig? captureConfig,
  ) {
    try {
      // Validate plane detection configuration
      _validatePlaneDetectionConfig(planeDetectionConfig);

      // Validate capture configuration if provided
      if (captureConfig != null) {
        _validateCaptureConfigSync(captureConfig);
        // Schedule async validation
        _scheduleCaptureConfigValidation(captureConfig);
      }

      // Check configuration compatibility
      if (captureConfig != null) {
        _checkConfigurationCompatibility(planeDetectionConfig, captureConfig);
      }

      if (debug) {
        print('Configuration validation passed');
      }
    } catch (e) {
      throw ARSessionException('Configuration validation failed: $e');
    }
  }

  /// Validate plane detection configuration
  void _validatePlaneDetectionConfig(PlaneDetectionConfig config) {
    // Basic validation - all plane detection configs are currently valid
    if (debug) {
      print('Plane detection configuration validated');
    }
  }

  /// Synchronous validation of capture configuration
  void _validateCaptureConfigSync(ARCaptureConfig config) {
    // Check platform support
    final isCaptureSupported = ARCaptureManager.debugIsSupportedOverride ??
        (Platform.isAndroid ||
            Platform.environment.containsKey('FLUTTER_TEST'));
    if (!isCaptureSupported) {
      throw ARSessionException('Capture not supported on this platform');
    }

    // Validate basic configuration values
    if (config.captureIntervalMs > 0 && config.captureIntervalMs < 100) {
      throw ARSessionException('Capture interval must be at least 100ms');
    }

    if (config.captureIntervalMs > 3600000) {
      // 1 hour
      throw ARSessionException('Capture interval cannot exceed 1 hour');
    }

    // Validate resolution values
    if (config.resolution.width <= 0 || config.resolution.height <= 0) {
      throw ARSessionException('Resolution dimensions must be positive');
    }

    if (config.resolution.width > 8192 || config.resolution.height > 8192) {
      throw ARSessionException(
          'Resolution dimensions cannot exceed 8192 pixels');
    }

    if (debug) {
      print('Capture configuration basic validation passed');
    }
  }

  /// Schedule asynchronous validation of capture configuration
  void _scheduleCaptureConfigValidation(ARCaptureConfig config) {
    // Use microtask to avoid blocking constructor
    scheduleMicrotask(() async {
      try {
        await _validateCaptureConfigAsync(config);
      } catch (e) {
        if (debug) {
          print('Warning: Async capture validation failed: $e');
        }
        // Note: We don't throw here as constructor is already complete
        // This validation provides warnings only
      }
    });
  }

  /// Asynchronous validation of capture configuration
  Future<void> _validateCaptureConfigAsync(ARCaptureConfig config) async {
    final capabilities = ARCameraCapabilities();

    try {
      // Validate using full capability system
      final validationResult = await capabilities.validateCaptureConfig(config);

      if (!validationResult.isValid) {
        if (debug) {
          print('Warning: Capture configuration validation issues:');
          for (final error in validationResult.errors) {
            print('  - Error: $error');
          }
        }
      }

      if (validationResult.hasWarnings) {
        if (debug) {
          print('Capture configuration warnings:');
          for (final warning in validationResult.warnings) {
            print('  - Warning: $warning');
          }
        }
      }

      if (validationResult.suggestedConfig != null) {
        if (debug) {
          print('Suggested configuration: ${validationResult.suggestedConfig}');
        }
      }

      if (debug) {
        print('Async capture configuration validation completed');
      }
    } catch (e) {
      if (debug) {
        print('Async validation error: $e');
      }
    }
  }

  /// Check compatibility between different configurations
  void _checkConfigurationCompatibility(
    PlaneDetectionConfig planeConfig,
    ARCaptureConfig captureConfig,
  ) {
    // Check for known incompatibilities

    // High-frequency capture with intensive plane detection may cause issues
    if (captureConfig.captureIntervalMs < 1000) {
      if (debug) {
        print('Warning: High-frequency capture may impact performance');
      }
    }

    // Raw format with frequent capture may cause memory issues
    if (captureConfig.format == CaptureFormat.rawJpeg &&
        captureConfig.captureIntervalMs < 5000) {
      if (debug) {
        print(
            'Warning: RAW format with frequent capture may cause memory pressure');
      }
    }

    // High resolution capture
    if (captureConfig.resolution.totalPixels > 8000000) {
      // 4K+
      if (debug) {
        print(
            'Warning: High resolution capture may impact AR tracking performance');
      }
    }

    if (debug) {
      print('Configuration compatibility check completed');
    }
  }

  /// Returns the camera pose in Matrix4 format with respect to the world coordinate system of the [ARView]
  Future<Matrix4?> getCameraPose() async {
    try {
      final serializedCameraPose =
          await _invokeOpenSession<List<dynamic>>('getCameraPose', {});
      if (serializedCameraPose == null) {
        return null;
      }
      return MatrixConverter().fromJson(serializedCameraPose);
    } catch (e) {
      print('Error caught: ' + e.toString());
      return null;
    }
  }

  /// Debug/profile builds (Android): starts an ARCore MP4 recording of the
  /// running session. Throws a [PlatformException] (`RECORDING_REFUSED`,
  /// `RECORDING_FAILED`) when the session cannot record.
  Future<ARRecordingStatus> startRecording(ARRecordingRequest request) async =>
      ARRecordingStatus.fromMap(await _invokeOpenSession<Object?>(
          'startRecording', request.toArguments()));

  /// Stops the recording after queued track data reached a frame (bounded
  /// natively); returns the final status. Safe when nothing records.
  Future<ARRecordingStatus> stopRecording() async =>
      ARRecordingStatus.fromMap(
          await _invokeOpenSession<Object?>('stopRecording'));

  /// Queues [data] for the next frame of the recording's custom track
  /// [trackId]; false when nothing records or the native queue is full.
  Future<bool> recordTrackData(String trackId, Uint8List data) async =>
      await _invokeOpenSession<bool>(
          'recordTrackData', {'trackId': trackId, 'data': data}) ??
      false;

  Future<ARRecordingStatus> recordingStatus() async => ARRecordingStatus.fromMap(
      await _invokeOpenSession<Object?>('recordingStatus'));

  /// Returns native frame-cadence diagnostics for this AR view.
  Future<Map<String, Object?>> getRendererPerformanceSnapshot() async {
    return await _channel.invokeMapMethod<String, Object?>(
          'getRendererPerformanceSnapshot',
        ) ??
        const <String, Object?>{};
  }

  /// Returns the given anchor pose in Matrix4 format with respect to the world coordinate system of the [ARView]
  Future<Matrix4?> getPose(ARAnchor anchor) async {
    try {
      if (anchor.name.isEmpty) {
        throw Exception("Anchor can not be resolved. Anchor name is empty.");
      }
      final serializedCameraPose =
          await _invokeOpenSession<List<dynamic>>('getAnchorPose', {
        "anchorId": anchor.name,
      });
      if (serializedCameraPose == null) {
        return null;
      }
      return MatrixConverter().fromJson(serializedCameraPose);
    } catch (e) {
      print('Error caught: ' + e.toString());
      return null;
    }
  }

  /// Returns the distance in meters between @anchor1 and @anchor2.
  Future<double?> getDistanceBetweenAnchors(
      ARAnchor anchor1, ARAnchor anchor2) async {
    var anchor1Pose = await getPose(anchor1);
    var anchor2Pose = await getPose(anchor2);
    var anchor1Translation = anchor1Pose?.getTranslation();
    var anchor2Translation = anchor2Pose?.getTranslation();
    if (anchor1Translation != null && anchor2Translation != null) {
      return getDistanceBetweenVectors(anchor1Translation, anchor2Translation);
    } else {
      return null;
    }
  }

  /// Returns the distance in meters between @anchor and device's camera.
  Future<double?> getDistanceFromAnchor(ARAnchor anchor) async {
    Matrix4? cameraPose = await getCameraPose();
    Matrix4? anchorPose = await getPose(anchor);
    Vector3? cameraTranslation = cameraPose?.getTranslation();
    Vector3? anchorTranslation = anchorPose?.getTranslation();
    if (anchorTranslation != null && cameraTranslation != null) {
      return getDistanceBetweenVectors(anchorTranslation, cameraTranslation);
    } else {
      return null;
    }
  }

  /// Returns the distance in meters between @vector1 and @vector2.
  double getDistanceBetweenVectors(Vector3 vector1, Vector3 vector2) {
    num dx = vector1.x - vector2.x;
    num dy = vector1.y - vector2.y;
    num dz = vector1.z - vector2.z;
    double distance = sqrt(dx * dx + dy * dy + dz * dz);
    return distance;
  }

  //Disable Camera
  void disableCamera() {
    _invokeOpenSession<void>('disableCamera');
  }

  //Enable Camera
  void enableCamera() {
    _invokeOpenSession<void>('enableCamera');
  }

  //Show or hide planes
  void showPlanes(bool showPlanes) {
    _invokeOpenSession<void>('showPlanes', {
      "showPlanes": showPlanes,
    });
  }

  Future<Object?> _platformCallHandler(MethodCall call) async {
    if (call.method == 'dispose') {
      await dispose();
      return null;
    }
    if (call.method == 'onCanonicalReady') {
      final handler = _canonicalReadyHandler;
      // Native has one send outstanding. Reject unexpected callback floods
      // before decoding/retaining another payload; fallback polling persists.
      if (handler == null || _canonicalReadyDeliveryPending || _closeStarted)
        return false;
      final hint = CanonicalReadyHintV1.decode(call.arguments);
      if (hint == null || hint.viewId != channelId) return false;
      _canonicalReadyDeliveryPending = true;
      try {
        return await handler(hint);
      } catch (_) {
        return false;
      } finally {
        _canonicalReadyDeliveryPending = false;
      }
    }
    if (debug) {
      print('_platformCallHandler call ${call.method} ${call.arguments}');
    }
    try {
      switch (call.method) {
        case 'onError':
          if (onError != null) {
            onError!(call.arguments[0]);
            print(call.arguments);
          } else {
            ScaffoldMessenger.of(buildContext).showSnackBar(SnackBar(
                content: Text(call.arguments[0]),
                action: SnackBarAction(
                    label: 'HIDE',
                    onPressed: ScaffoldMessenger.of(buildContext)
                        .hideCurrentSnackBar)));
          }
          break;
        case 'onPlaneOrPointTap':
          if (onPlaneOrPointTap != null) {
            final rawHitTestResults = call.arguments as List<dynamic>;
            final serializedHitTestResults = rawHitTestResults
                .map(
                    (hitTestResult) => Map<String, dynamic>.from(hitTestResult))
                .toList();
            final hitTestResults = serializedHitTestResults.map((e) {
              return ARHitTestResult.fromJson(e);
            }).toList();
            onPlaneOrPointTap!(hitTestResults);
          }
          break;
        case 'onArPlaybackFinished':
          final arguments = call.arguments;
          onArPlaybackFinished?.call(arguments is Map
              ? Map<String, Object?>.from(arguments)
              : const <String, Object?>{});
          break;
        case 'onArPlaybackTrackData':
          final records = ARPlaybackTrackRecord.parseEvent(call.arguments);
          if (records.isNotEmpty) onArPlaybackTrackData?.call(records);
          break;
        case 'onPlaneDetected':
          if (onPlaneDetected != null) {
            final planeCountResult = call.arguments as int;
            onPlaneDetected!(planeCountResult);
          }
          break;
        default:
          if (debug) {
            print('Unimplemented method ${call.method} ');
          }
      }
    } catch (e) {
      print('Error caught: ' + e.toString());
    }
    return null;
  }

  /// Function to initialize the platform-specific AR view. Can be used to initially set or update session settings.
  /// [customPlaneTexturePath] refers to flutter assets from the app that is calling this function, NOT to assets within this plugin. Make sure
  /// the assets are correctly registered in the pubspec.yaml of the parent app (e.g. the ./example app in this plugin's repo)
  Future<void> onInitialize({
    bool showAnimatedGuide = true,
    bool showFeaturePoints = false,
    bool showPlanes = true,
    String? customPlaneTexturePath,
    bool showWorldOrigin = false,
    bool handleTaps = true,
    bool handlePans = false, // nodes are not draggable by default
    bool handleRotation = false, // nodes can not be rotated by default
  }) async {
    await _invokeOpenSession<void>('init', {
      'showAnimatedGuide': showAnimatedGuide,
      'showFeaturePoints': showFeaturePoints,
      'planeDetectionConfig': planeDetectionConfig.index,
      'showPlanes': showPlanes,
      'customPlaneTexturePath': customPlaneTexturePath,
      'showWorldOrigin': showWorldOrigin,
      'handleTaps': handleTaps,
      'handlePans': handlePans,
      'handleRotation': handleRotation,
    });
  }

  /// Legacy dispose method for backward compatibility
  /// You should call this before removing the AR view to prevent out of memory errors
  Future<void> disposeLegacy() => dispose();

  /// Returns a future ImageProvider that contains a screenshot of the current AR Scene
  Future<ImageProvider> snapshot() async {
    final result = await _invokeOpenSession<Uint8List>('snapshot');
    return MemoryImage(result!);
  }
}

/// Exception thrown when session operations fail
class ARSessionException implements Exception {
  final String message;
  ARSessionException(this.message);

  @override
  String toString() => 'ARSessionException: $message';
}
