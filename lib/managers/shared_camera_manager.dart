import 'dart:async';

import '../models/ar_capture_config.dart';
import 'ar_capture_manager.dart';
import 'ar_session_manager.dart';

/// Camera modes for shared camera management.
enum CameraMode { ar, capture, shared }

/// Priority levels for camera resource allocation.
enum CameraPriority { low, medium, high, critical }

/// Camera resource allocation request.
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

/// Camera resource state information.
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

/// Legacy global shared-camera manager.
@Deprecated(
  'SharedCameraManager uses the deprecated global shared_camera_manager channel. '
  'Use the per-view ARCaptureManager path instead.',
)
class SharedCameraManager {
  SharedCameraManager._();

  static SharedCameraManager? _instance;
  static SharedCameraManager get instance =>
      _instance ??= SharedCameraManager._();

  final StreamController<CameraResourceState> _stateController =
      StreamController<CameraResourceState>.broadcast();

  static UnsupportedError _unsupported() {
    return UnsupportedError(
      'SharedCameraManager uses the deprecated global shared_camera_manager channel, '
      'which has no native handler in the current capture pipeline. '
      'Use ARCaptureManager on the per-view arcapture_<id> channel instead.',
    );
  }

  Future<void> initializeWithConfig({
    required String cameraId,
    required ARCaptureConfig captureConfig,
    ARConfiguration? arConfig,
  }) async {
    throw _unsupported();
  }

  Future<void> switchToArSession() async {
    throw _unsupported();
  }

  Future<void> switchToCaptureMode() async {
    throw _unsupported();
  }

  Future<void> enableSharedMode() async {
    throw _unsupported();
  }

  Future<bool> requestCameraResource({
    required String requesterId,
    required CameraMode requestedMode,
    CameraPriority priority = CameraPriority.medium,
    String? reason,
  }) async {
    throw _unsupported();
  }

  Future<void> releaseCameraResource({
    required String requesterId,
  }) async {
    throw _unsupported();
  }

  void registerArSession(String sessionId, ARSessionManager sessionManager) {
    throw _unsupported();
  }

  void unregisterArSession(String sessionId) {
    throw _unsupported();
  }

  void setCaptureManager(ARCaptureManager? manager) {
    throw _unsupported();
  }

  bool get isSharedModeActive => false;

  CameraMode get currentMode => CameraMode.ar;

  ARCaptureManager? get captureManager => null;

  CameraResourceState get resourceState => const CameraResourceState(
    currentMode: CameraMode.ar,
    currentOwnerId: null,
    isSharedModeActive: false,
    hasConflicts: false,
    pendingRequests: <CameraResourceRequest>[],
    resourceMetrics: <String, dynamic>{},
  );

  Stream<CameraResourceState> get stateStream => _stateController.stream;

  Future<void> dispose() async {
    await _stateController.close();
    _instance = null;
  }
}
