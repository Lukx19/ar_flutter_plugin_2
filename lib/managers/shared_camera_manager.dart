import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../models/ar_capture_config.dart';
import 'ar_session_manager.dart';
import 'ar_capture_manager.dart';

/// Camera modes for shared camera management
enum CameraMode {
  /// AR session has exclusive camera access
  ar,
  /// Capture manager has exclusive camera access
  capture,
  /// Camera is shared between AR and capture
  shared,
}

/// Priority levels for camera resource allocation
enum CameraPriority {
  low,
  medium,
  high,
  critical,
}

/// Camera resource allocation request
class CameraResourceRequest {
  final String requesterId;
  final CameraMode requestedMode;
  final CameraPriority priority;
  final DateTime timestamp;
  final String? reason;

  const CameraResourceRequest({
    required this.requesterId,
    required this.requestedMode,
    required this.priority,
    required this.timestamp,
    this.reason,
  });
}

/// Camera resource state information
class CameraResourceState {
  final CameraMode currentMode;
  final String? currentOwnerId;
  final bool isSharedModeActive;
  final bool hasConflicts;
  final List<CameraResourceRequest> pendingRequests;
  final Map<String, dynamic> resourceMetrics;

  const CameraResourceState({
    required this.currentMode,
    this.currentOwnerId,
    required this.isSharedModeActive,
    required this.hasConflicts,
    required this.pendingRequests,
    required this.resourceMetrics,
  });
}

/// Manages shared camera resources between AR session and capture functionality
/// 
/// This singleton class coordinates camera access between AR tracking and capture operations,
/// ensuring efficient resource sharing and conflict resolution.
class SharedCameraManager {
  static SharedCameraManager? _instance;
  static SharedCameraManager get instance => _instance ??= SharedCameraManager._();

  SharedCameraManager._();

  // Platform channel for camera coordination
  late MethodChannel _channel;
  bool _isInitialized = false;

  // Current camera state
  CameraMode _currentMode = CameraMode.ar;
  String? _currentOwnerId;
  bool _isSharedModeActive = false;
  
  // Camera configuration
  String? _cameraId;
  ARCaptureConfig? _captureConfig;
  ARConfiguration? _arConfig;

  // Resource management
  final List<CameraResourceRequest> _pendingRequests = [];
  final Map<String, dynamic> _resourceMetrics = {};
  final StreamController<CameraResourceState> _stateController = StreamController.broadcast();

  // Managed components
  ARCaptureManager? _captureManager;
  final Map<String, ARSessionManager> _arSessions = {};

  // Debugging
  bool _debug = false;

  /// Initialize shared camera manager with configuration
  Future<void> initializeWithConfig({
    required String cameraId,
    required ARCaptureConfig captureConfig,
    ARConfiguration? arConfig,
  }) async {
    if (_isInitialized) {
      if (_debug) {
        debugPrint('SharedCameraManager already initialized');
      }
      return;
    }

    try {
      _cameraId = cameraId;
      _captureConfig = captureConfig;
      _arConfig = arConfig;
      _debug = arConfig?.debug ?? false;

      // Initialize platform channel
      _channel = const MethodChannel('shared_camera_manager');
      _channel.setMethodCallHandler(_platformCallHandler);

      if (_debug) {
        debugPrint('Initializing SharedCameraManager with camera: $cameraId');
      }

      // Initialize platform-specific camera management
      await _channel.invokeMethod('initialize', {
        'cameraId': cameraId,
        'captureConfig': captureConfig.toMap(),
        'arConfig': arConfig?.toMap(),
      });

      // Initialize in AR mode by default
      _currentMode = CameraMode.ar;
      _isSharedModeActive = false;
      _isInitialized = true;

      _updateResourceMetrics();
      _notifyStateChange();

      if (_debug) {
        debugPrint('SharedCameraManager initialized successfully');
      }

    } catch (e) {
      throw Exception('Failed to initialize SharedCameraManager: $e');
    }
  }

  /// Switch camera to AR session mode
  Future<void> switchToArSession() async {
    if (!_isInitialized) {
      throw Exception('SharedCameraManager not initialized');
    }

    if (_currentMode == CameraMode.ar) {
      if (_debug) {
        debugPrint('Already in AR mode');
      }
      return;
    }

    try {
      if (_debug) {
        debugPrint('Switching to AR session mode...');
      }

      // Stop capture operations
      if (_captureManager != null) {
        // Capture manager will gracefully yield camera access
        if (_debug) {
          debugPrint('Notifying capture manager of mode switch');
        }
      }

      // Switch platform camera to AR mode
      await _channel.invokeMethod('switchToArMode');

      _currentMode = CameraMode.ar;
      _currentOwnerId = 'ar_session';
      _isSharedModeActive = false;

      _updateResourceMetrics();
      _notifyStateChange();

      if (_debug) {
        debugPrint('Successfully switched to AR session mode');
      }

    } catch (e) {
      throw Exception('Failed to switch to AR session mode: $e');
    }
  }

  /// Switch camera to capture mode
  Future<void> switchToCaptureMode() async {
    if (!_isInitialized) {
      throw Exception('SharedCameraManager not initialized');
    }

    if (_currentMode == CameraMode.capture) {
      if (_debug) {
        debugPrint('Already in capture mode');
      }
      return;
    }

    try {
      if (_debug) {
        debugPrint('Switching to capture mode...');
      }

      // Pause AR operations
      for (final session in _arSessions.values) {
        if (session.sessionState == ARSessionState.running) {
          await session.pause();
        }
      }

      // Switch platform camera to capture mode
      await _channel.invokeMethod('switchToCaptureMode');

      _currentMode = CameraMode.capture;
      _currentOwnerId = 'capture_manager';
      _isSharedModeActive = false;

      _updateResourceMetrics();
      _notifyStateChange();

      if (_debug) {
        debugPrint('Successfully switched to capture mode');
      }

    } catch (e) {
      throw Exception('Failed to switch to capture mode: $e');
    }
  }

  /// Enable shared camera mode
  Future<void> enableSharedMode() async {
    if (!_isInitialized) {
      throw Exception('SharedCameraManager not initialized');
    }

    if (_isSharedModeActive) {
      if (_debug) {
        debugPrint('Shared mode already active');
      }
      return;
    }

    try {
      if (_debug) {
        debugPrint('Enabling shared camera mode...');
      }

      // Configure platform for shared access
      await _channel.invokeMethod('enableSharedMode', {
        'arPriority': CameraPriority.medium.index,
        'capturePriority': CameraPriority.medium.index,
      });

      _currentMode = CameraMode.shared;
      _currentOwnerId = 'shared';
      _isSharedModeActive = true;

      _updateResourceMetrics();
      _notifyStateChange();

      if (_debug) {
        debugPrint('Shared camera mode enabled successfully');
      }

    } catch (e) {
      throw Exception('Failed to enable shared camera mode: $e');
    }
  }

  /// Request camera resource with priority
  Future<bool> requestCameraResource({
    required String requesterId,
    required CameraMode requestedMode,
    CameraPriority priority = CameraPriority.medium,
    String? reason,
  }) async {
    if (!_isInitialized) {
      throw Exception('SharedCameraManager not initialized');
    }

    final request = CameraResourceRequest(
      requesterId: requesterId,
      requestedMode: requestedMode,
      priority: priority,
      timestamp: DateTime.now(),
      reason: reason,
    );

    if (_debug) {
      debugPrint('Camera resource request: $requesterId -> ${requestedMode.name} (${priority.name})');
    }

    // Evaluate request based on current state and priority
    final shouldGrant = _evaluateResourceRequest(request);

    if (shouldGrant) {
      return await _grantResourceRequest(request);
    } else {
      _pendingRequests.add(request);
      _notifyStateChange();
      
      if (_debug) {
        debugPrint('Camera resource request queued: $requesterId');
      }
      
      return false;
    }
  }

  /// Release camera resource
  Future<void> releaseCameraResource({
    required String requesterId,
  }) async {
    if (!_isInitialized) {
      throw Exception('SharedCameraManager not initialized');
    }

    if (_currentOwnerId == requesterId) {
      if (_debug) {
        debugPrint('Releasing camera resource: $requesterId');
      }

      // Process pending requests
      await _processPendingRequests();
      
      _updateResourceMetrics();
      _notifyStateChange();
    }

    // Remove any pending requests from this requester
    _pendingRequests.removeWhere((req) => req.requesterId == requesterId);
  }

  /// Register AR session for management
  void registerArSession(String sessionId, ARSessionManager sessionManager) {
    _arSessions[sessionId] = sessionManager;
    
    if (_debug) {
      debugPrint('Registered AR session: $sessionId');
    }
  }

  /// Unregister AR session
  void unregisterArSession(String sessionId) {
    _arSessions.remove(sessionId);
    
    if (_debug) {
      debugPrint('Unregistered AR session: $sessionId');
    }
  }

  /// Set capture manager
  void setCaptureManager(ARCaptureManager? manager) {
    _captureManager = manager;
    
    if (_debug) {
      debugPrint('Capture manager ${manager != null ? 'set' : 'cleared'}');
    }
  }

  /// Check if shared mode is active
  bool get isSharedModeActive => _isSharedModeActive;

  /// Get current camera mode
  CameraMode get currentMode => _currentMode;

  /// Get capture manager
  ARCaptureManager? get captureManager => _captureManager;

  /// Get current camera resource state
  CameraResourceState get resourceState => CameraResourceState(
    currentMode: _currentMode,
    currentOwnerId: _currentOwnerId,
    isSharedModeActive: _isSharedModeActive,
    hasConflicts: _pendingRequests.isNotEmpty,
    pendingRequests: List.unmodifiable(_pendingRequests),
    resourceMetrics: Map.unmodifiable(_resourceMetrics),
  );

  /// Stream of camera resource state changes
  Stream<CameraResourceState> get stateStream => _stateController.stream;

  /// Evaluate if a resource request should be granted immediately
  bool _evaluateResourceRequest(CameraResourceRequest request) {
    // If no current owner, grant immediately
    if (_currentOwnerId == null) {
      return true;
    }

    // If same requester, allow mode change
    if (_currentOwnerId == request.requesterId) {
      return true;
    }

    // High priority requests can preempt lower priority operations
    if (request.priority == CameraPriority.critical) {
      return true;
    }

    // In shared mode, evaluate based on current load
    if (_isSharedModeActive) {
      return _resourceMetrics['load'] != null && 
             (_resourceMetrics['load'] as double) < 0.8;
    }

    return false;
  }

  /// Grant a resource request
  Future<bool> _grantResourceRequest(CameraResourceRequest request) async {
    try {
      switch (request.requestedMode) {
        case CameraMode.ar:
          await switchToArSession();
          break;
        case CameraMode.capture:
          await switchToCaptureMode();
          break;
        case CameraMode.shared:
          await enableSharedMode();
          break;
      }

      _currentOwnerId = request.requesterId;
      
      if (_debug) {
        debugPrint('Granted camera resource to: ${request.requesterId}');
      }

      return true;
    } catch (e) {
      if (_debug) {
        debugPrint('Failed to grant camera resource: $e');
      }
      return false;
    }
  }

  /// Process pending resource requests
  Future<void> _processPendingRequests() async {
    if (_pendingRequests.isEmpty) return;

    // Sort by priority and timestamp
    _pendingRequests.sort((a, b) {
      final priorityComparison = b.priority.index.compareTo(a.priority.index);
      if (priorityComparison != 0) return priorityComparison;
      return a.timestamp.compareTo(b.timestamp);
    });

    final nextRequest = _pendingRequests.removeAt(0);
    
    if (_debug) {
      debugPrint('Processing pending request: ${nextRequest.requesterId}');
    }

    await _grantResourceRequest(nextRequest);
  }

  /// Update resource metrics
  void _updateResourceMetrics() {
    _resourceMetrics.clear();
    
    _resourceMetrics['currentMode'] = _currentMode.name;
    _resourceMetrics['ownerId'] = _currentOwnerId;
    _resourceMetrics['isShared'] = _isSharedModeActive;
    _resourceMetrics['pendingRequests'] = _pendingRequests.length;
    _resourceMetrics['arSessions'] = _arSessions.length;
    _resourceMetrics['hasCaptureManager'] = _captureManager != null;
    _resourceMetrics['timestamp'] = DateTime.now().millisecondsSinceEpoch;
    
    // Calculate load metric (0.0 to 1.0)
    double load = 0.0;
    if (_arSessions.isNotEmpty) load += 0.4;
    if (_captureManager != null) load += 0.4;
    if (_pendingRequests.isNotEmpty) load += 0.2;
    _resourceMetrics['load'] = load;
  }

  /// Notify state change to listeners
  void _notifyStateChange() {
    if (_stateController.hasListener) {
      _stateController.add(resourceState);
    }
  }

  /// Platform method call handler
  Future<void> _platformCallHandler(MethodCall call) async {
    if (_debug) {
      debugPrint('Platform call: ${call.method}');
    }

    try {
      switch (call.method) {
        case 'onCameraStateChanged':
          final state = call.arguments as Map<String, dynamic>;
          await _handleCameraStateChange(state);
          break;
        case 'onResourceConflict':
          final conflict = call.arguments as Map<String, dynamic>;
          await _handleResourceConflict(conflict);
          break;
        case 'onPerformanceMetrics':
          final metrics = call.arguments as Map<String, dynamic>;
          _updatePerformanceMetrics(metrics);
          break;
        default:
          if (_debug) {
            debugPrint('Unhandled platform call: ${call.method}');
          }
      }
    } catch (e) {
      if (_debug) {
        debugPrint('Error handling platform call: $e');
      }
    }
  }

  /// Handle camera state changes from platform
  Future<void> _handleCameraStateChange(Map<String, dynamic> state) async {
    if (_debug) {
      debugPrint('Camera state changed: $state');
    }

    // Update internal state based on platform feedback
    _updateResourceMetrics();
    _notifyStateChange();
  }

  /// Handle resource conflicts
  Future<void> _handleResourceConflict(Map<String, dynamic> conflict) async {
    if (_debug) {
      debugPrint('Resource conflict detected: $conflict');
    }

    // Implement conflict resolution logic
    await _resolveResourceConflict(conflict);
  }

  /// Resolve resource conflicts
  Future<void> _resolveResourceConflict(Map<String, dynamic> conflict) async {
    final conflictType = conflict['type'] as String?;
    
    switch (conflictType) {
      case 'camera_busy':
        // Try to enable shared mode if not active
        if (!_isSharedModeActive) {
          await enableSharedMode();
        }
        break;
      case 'resource_exhaustion':
        // Process pending requests to free resources
        await _processPendingRequests();
        break;
      default:
        if (_debug) {
          debugPrint('Unknown conflict type: $conflictType');
        }
    }
  }

  /// Update performance metrics from platform
  void _updatePerformanceMetrics(Map<String, dynamic> metrics) {
    _resourceMetrics.addAll(metrics);
    _notifyStateChange();
  }

  /// Dispose shared camera manager
  Future<void> dispose() async {
    if (!_isInitialized) return;

    try {
      if (_debug) {
        debugPrint('Disposing SharedCameraManager...');
      }

      // Release all resources
      for (final sessionId in _arSessions.keys.toList()) {
        unregisterArSession(sessionId);
      }

      _captureManager = null;
      _pendingRequests.clear();

      // Dispose platform resources
      await _channel.invokeMethod('dispose');

      // Close state stream
      await _stateController.close();

      _isInitialized = false;
      _instance = null;

      if (_debug) {
        debugPrint('SharedCameraManager disposed successfully');
      }

    } catch (e) {
      if (_debug) {
        debugPrint('Error disposing SharedCameraManager: $e');
      }
    }
  }
}

/// Extension for ARConfiguration to support toMap conversion
extension ARConfigurationExtension on ARConfiguration {
  Map<String, dynamic> toMap() {
    return {
      'enableCapture': enableCapture,
      'captureConfig': captureConfig?.toMap(),
      'planeDetectionConfig': planeDetectionConfig?.index,
      'showAnimatedGuide': showAnimatedGuide,
      'showFeaturePoints': showFeaturePoints,
      'showPlanes': showPlanes,
      'customPlaneTexturePath': customPlaneTexturePath,
      'showWorldOrigin': showWorldOrigin,
      'handleTaps': handleTaps,
      'handlePans': handlePans,
      'handleRotation': handleRotation,
      'debug': debug,
    };
  }
}