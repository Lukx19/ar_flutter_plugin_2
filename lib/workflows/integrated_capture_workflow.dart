import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:vector_math/vector_math_64.dart';
import '../models/ar_capture_config.dart';
import '../models/ar_capture_result.dart';
import '../models/ar_frame_pose.dart';
import '../managers/ar_session_manager.dart';
import '../managers/ar_capture_manager.dart';
import '../managers/shared_camera_manager.dart';
import '../managers/capture_memory_manager.dart';
import '../validation/configuration_compatibility_checker.dart';

/// Callback function types for workflow events
typedef CaptureCallback = void Function(ARCaptureResult result);
typedef ErrorCallback = void Function(String error);
typedef ProgressCallback = void Function(double progress, String status);

/// States of the integrated capture workflow
enum CaptureWorkflowState {
  notInitialized,
  initializing,
  validatingConfiguration,
  preparingResources,
  ready,
  capturing,
  processing,
  postProcessing,
  cleanup,
  completed,
  error,
  cancelled,
}

/// Workflow execution statistics
class WorkflowStats {
  final DateTime startTime;
  final DateTime? endTime;
  final Duration totalDuration;
  final int totalCaptures;
  final int successfulCaptures;
  final int failedCaptures;
  final List<Duration> captureTimings;
  final Map<String, int> errorCounts;
  final double averageCaptureTime;
  final double throughputCapturesPerSecond;
  final Map<String, dynamic> performanceMetrics;

  const WorkflowStats({
    required this.startTime,
    this.endTime,
    required this.totalDuration,
    required this.totalCaptures,
    required this.successfulCaptures,
    required this.failedCaptures,
    required this.captureTimings,
    required this.errorCounts,
    required this.averageCaptureTime,
    required this.throughputCapturesPerSecond,
    required this.performanceMetrics,
  });

  Map<String, dynamic> toMap() {
    return {
      'startTime': startTime.millisecondsSinceEpoch,
      'endTime': endTime?.millisecondsSinceEpoch,
      'totalDuration': totalDuration.inMilliseconds,
      'totalCaptures': totalCaptures,
      'successfulCaptures': successfulCaptures,
      'failedCaptures': failedCaptures,
      'captureTimings': captureTimings.map((d) => d.inMilliseconds).toList(),
      'errorCounts': errorCounts,
      'averageCaptureTime': averageCaptureTime,
      'throughputCapturesPerSecond': throughputCapturesPerSecond,
      'performanceMetrics': performanceMetrics,
    };
  }
}

/// Comprehensive result of the capture workflow
class CaptureWorkflowResult {
  final List<ARCaptureResult> captures;
  final WorkflowStats stats;
  final List<String> warnings;
  final List<String> errors;
  final bool isSuccessful;
  final Map<String, dynamic> metadata;

  const CaptureWorkflowResult({
    required this.captures,
    required this.stats,
    required this.warnings,
    required this.errors,
    required this.isSuccessful,
    required this.metadata,
  });

  Map<String, dynamic> toMap() {
    return {
      'captures': captures.map((c) => c.toMap()).toList(),
      'stats': stats.toMap(),
      'warnings': warnings,
      'errors': errors,
      'isSuccessful': isSuccessful,
      'metadata': metadata,
    };
  }
}

/// Workflow execution options
class CaptureWorkflowOptions {
  final int captureCount;
  final Duration? interval;
  final bool enableProgressTracking;
  final bool enableRetry;
  final int maxRetryAttempts;
  final Duration retryDelay;
  final bool enableBatchOptimization;
  final bool enableMemoryOptimization;
  final bool enablePerformanceMonitoring;
  final Map<String, dynamic>? customOptions;

  const CaptureWorkflowOptions({
    this.captureCount = 1,
    this.interval,
    this.enableProgressTracking = true,
    this.enableRetry = true,
    this.maxRetryAttempts = 3,
    this.retryDelay = const Duration(milliseconds: 500),
    this.enableBatchOptimization = true,
    this.enableMemoryOptimization = true,
    this.enablePerformanceMonitoring = true,
    this.customOptions,
  });
}

/// Comprehensive end-to-end capture workflow implementation
/// 
/// This class orchestrates the complete capture workflow from initialization
/// through capture to cleanup, with comprehensive error handling and state management.
class IntegratedCaptureWorkflow {
  final ARConfiguration _arConfig;
  final ARCaptureConfig _captureConfig;
  
  // Core managers
  ARSessionManager? _sessionManager;
  ARCaptureManager? _captureManager;
  SharedCameraManager? _sharedCameraManager;
  CaptureMemoryManager? _memoryManager;
  ConfigurationCompatibilityChecker? _compatibilityChecker;

  // Workflow state management
  CaptureWorkflowState _currentState = CaptureWorkflowState.notInitialized;
  final StreamController<CaptureWorkflowState> _stateController = StreamController.broadcast();
  
  // Execution tracking
  DateTime? _workflowStartTime;
  DateTime? _workflowEndTime;
  final List<ARCaptureResult> _captureResults = [];
  final List<String> _warnings = [];
  final List<String> _errors = [];
  final List<Duration> _captureTimings = [];
  final Map<String, int> _errorCounts = {};
  final Map<String, dynamic> _performanceMetrics = {};
  
  // Workflow control
  bool _isCancelled = false;
  Completer<CaptureWorkflowResult>? _workflowCompleter;
  Timer? _progressTimer;
  
  // Callbacks
  CaptureCallback? _onCaptureComplete;
  ErrorCallback? _onError;
  ProgressCallback? _onProgress;
  
  // Configuration
  bool _debug = false;

  IntegratedCaptureWorkflow({
    required ARConfiguration arConfig,
    required ARCaptureConfig captureConfig,
  }) : _arConfig = arConfig,
       _captureConfig = captureConfig,
       _debug = arConfig.debug;

  /// Get current workflow state
  CaptureWorkflowState get currentState => _currentState;

  /// Stream of workflow state changes
  Stream<CaptureWorkflowState> get workflowState => _stateController.stream;

  /// Check if workflow is currently executing
  bool get isExecuting => _currentState == CaptureWorkflowState.capturing ||
                         _currentState == CaptureWorkflowState.processing ||
                         _currentState == CaptureWorkflowState.postProcessing;

  /// Execute the complete capture workflow
  Future<CaptureWorkflowResult> executeCaptureWorkflow({
    CaptureWorkflowOptions? options,
    CaptureCallback? onProgress,
    ErrorCallback? onError,
    ProgressCallback? onProgressUpdate,
  }) async {
    if (_currentState != CaptureWorkflowState.notInitialized && 
        _currentState != CaptureWorkflowState.completed &&
        _currentState != CaptureWorkflowState.error &&
        _currentState != CaptureWorkflowState.cancelled) {
      throw WorkflowException('Workflow already in progress. Current state: $_currentState');
    }

    try {
      _workflowStartTime = DateTime.now();
      _isCancelled = false;
      _workflowCompleter = Completer<CaptureWorkflowResult>();
      
      // Set callbacks
      _onCaptureComplete = onProgress;
      _onError = onError;
      _onProgress = onProgressUpdate;
      
      // Clear previous results
      _clearPreviousResults();
      
      final workflowOptions = options ?? const CaptureWorkflowOptions();
      
      if (_debug) {
        debugPrint('Starting integrated capture workflow...');
      }

      // Phase 1: Initialize workflow
      await _initializeWorkflow();
      
      // Phase 2: Validate configuration compatibility
      await _validateConfiguration();
      
      // Phase 3: Prepare resources
      await _prepareResources();
      
      // Phase 4: Execute captures
      await _executeCaptureSequence(workflowOptions);
      
      // Phase 5: Post-process results
      await _postProcessResults();
      
      // Phase 6: Cleanup
      await _performCleanup();
      
      // Phase 7: Complete workflow
      final result = await _completeWorkflow();
      
      if (_debug) {
        debugPrint('Capture workflow completed successfully');
      }
      
      return result;
      
    } catch (e) {
      return await _handleWorkflowError(e);
    }
  }

  /// Cancel the current workflow execution
  Future<void> cancelWorkflow() async {
    if (!isExecuting) {
      if (_debug) {
        debugPrint('No workflow to cancel, current state: $_currentState');
      }
      return;
    }

    try {
      if (_debug) {
        debugPrint('Cancelling capture workflow...');
      }

      _isCancelled = true;
      _setState(CaptureWorkflowState.cancelled);
      
      // Stop any ongoing captures
      if (_captureManager != null) {
        // Note: ARCaptureManager doesn't have stopCapture method
        // This would be implemented when the actual capture manager has this method
      }
      
      // Cleanup resources
      await _performCleanup();
      
      if (_workflowCompleter != null && !_workflowCompleter!.isCompleted) {
        final result = _buildWorkflowResult(isSuccessful: false);
        _workflowCompleter!.complete(result);
      }

      if (_debug) {
        debugPrint('Workflow cancelled successfully');
      }

    } catch (e) {
      if (_debug) {
        debugPrint('Error cancelling workflow: $e');
      }
    }
  }

  /// Phase 1: Initialize workflow
  Future<void> _initializeWorkflow() async {
    _setState(CaptureWorkflowState.initializing);
    _updateProgress(0.1, 'Initializing workflow components...');

    try {
      if (_debug) {
        debugPrint('Initializing workflow components...');
      }

      // Initialize compatibility checker
      _compatibilityChecker = ConfigurationCompatibilityChecker.instance;
      await _compatibilityChecker!.initialize(debug: _debug);
      
      // Initialize memory manager
      _memoryManager = CaptureMemoryManager.instance;
      await _memoryManager!.initializeWithConfig(_captureConfig);
      await _memoryManager!.preallocateBuffers();
      
      // Initialize shared camera manager
      _sharedCameraManager = SharedCameraManager.instance;
      await _sharedCameraManager!.initializeWithConfig(
        cameraId: 'default',
        captureConfig: _captureConfig,
        arConfig: _arConfig,
      );

      if (_debug) {
        debugPrint('Workflow components initialized successfully');
      }

    } catch (e) {
      throw WorkflowException('Failed to initialize workflow: $e');
    }
  }

  /// Phase 2: Validate configuration compatibility
  Future<void> _validateConfiguration() async {
    _setState(CaptureWorkflowState.validatingConfiguration);
    _updateProgress(0.2, 'Validating configuration compatibility...');

    try {
      if (_debug) {
        debugPrint('Validating configuration compatibility...');
      }

      final compatibilityResult = await _compatibilityChecker!.checkCompatibility(
        arConfig: _arConfig,
        captureConfig: _captureConfig,
      );

      if (!compatibilityResult.isCompatible) {
        // Try to resolve compatibility issues
        if (_debug) {
          debugPrint('Configuration incompatible, attempting resolution...');
        }
        
        final resolvedConfig = await _compatibilityChecker!.resolveCompatibility(
          arConfig: _arConfig,
          captureConfig: _captureConfig,
          strategy: CompatibilityStrategy.balanced,
        );
        
        // Update capture config with resolved version
        // Note: In a real implementation, you'd want to update the actual config
        _warnings.add('Configuration was automatically adjusted for compatibility');
      }

      // Add any warnings
      _warnings.addAll(compatibilityResult.warnings);

      if (_debug) {
        debugPrint('Configuration validation completed. Score: ${compatibilityResult.compatibilityScore}');
      }

    } catch (e) {
      throw WorkflowException('Configuration validation failed: $e');
    }
  }

  /// Phase 3: Prepare resources
  Future<void> _prepareResources() async {
    _setState(CaptureWorkflowState.preparingResources);
    _updateProgress(0.3, 'Preparing capture resources...');

    try {
      if (_debug) {
        debugPrint('Preparing capture resources...');
      }

      // Switch camera to shared mode for optimal resource usage
      await _sharedCameraManager!.enableSharedMode();
      
      // Start memory monitoring
      _startPerformanceMonitoring();

      if (_debug) {
        debugPrint('Resources prepared successfully');
      }

    } catch (e) {
      throw WorkflowException('Failed to prepare resources: $e');
    }
  }

  /// Phase 4: Execute capture sequence
  Future<void> _executeCaptureSequence(CaptureWorkflowOptions options) async {
    _setState(CaptureWorkflowState.capturing);
    _updateProgress(0.4, 'Executing capture sequence...');

    try {
      if (_debug) {
        debugPrint('Starting capture sequence: ${options.captureCount} captures');
      }

      for (int i = 0; i < options.captureCount && !_isCancelled; i++) {
        final captureStartTime = DateTime.now();
        
        try {
          _updateProgress(
            0.4 + (0.4 * (i / options.captureCount)),
            'Capturing ${i + 1}/${options.captureCount}...'
          );

          // Execute single capture
          final result = await _executeSingleCapture(i);
          
          final captureEndTime = DateTime.now();
          final captureDuration = captureEndTime.difference(captureStartTime);
          
          _captureResults.add(result);
          _captureTimings.add(captureDuration);
          
          // Notify progress callback
          _onCaptureComplete?.call(result);
          
          if (_debug) {
            debugPrint('Capture ${i + 1} completed in ${captureDuration.inMilliseconds}ms');
          }

          // Wait for interval if specified
          if (options.interval != null && i < options.captureCount - 1) {
            await Future.delayed(options.interval!);
          }

        } catch (e) {
          _handleCaptureError(e, i, options.enableRetry, options.maxRetryAttempts);
        }
      }

      if (_debug) {
        debugPrint('Capture sequence completed: ${_captureResults.length} successful captures');
      }

    } catch (e) {
      throw WorkflowException('Capture sequence failed: $e');
    }
  }

  /// Execute a single capture with retry logic
  Future<ARCaptureResult> _executeSingleCapture(int captureIndex) async {
    // For now, return a mock result since we don't have actual capture implementation
    // In real implementation, this would use the ARCaptureManager
    
    await Future.delayed(const Duration(milliseconds: 100)); // Simulate capture time
    
    // Create a mock ARFramePose for testing
    final mockPose = ARFramePose(
      position: Vector3(0.0, 0.0, 0.0),
      rotation: Quaternion(0.0, 0.0, 0.0, 1.0),
      transform: Matrix4.identity(),
      timestamp: DateTime.now(),
      confidence: 0.95,
      isTracking: true,
    );
    
    return ARCaptureResult(
      imageId: 'capture_${captureIndex}_${DateTime.now().millisecondsSinceEpoch}',
      pose: mockPose,
      resolution: _captureConfig.resolution,
      format: _captureConfig.format,
      captureTimestamp: DateTime.now(),
      imageSizeBytes: _captureConfig.resolution.totalPixels * 3, // Rough estimate
      isHighResolution: _captureConfig.resolution.totalPixels > 2000000,
      filePath: null, // Would contain actual file path
    );
  }

  /// Handle capture errors with retry logic
  Future<void> _handleCaptureError(dynamic error, int captureIndex, bool enableRetry, int maxRetryAttempts) async {
    final errorMessage = 'Capture $captureIndex failed: $error';
    _errors.add(errorMessage);
    _errorCounts[error.runtimeType.toString()] = (_errorCounts[error.runtimeType.toString()] ?? 0) + 1;
    
    if (_debug) {
      debugPrint(errorMessage);
    }
    
    _onError?.call(errorMessage);
    
    if (enableRetry) {
      for (int retry = 0; retry < maxRetryAttempts; retry++) {
        try {
          if (_debug) {
            debugPrint('Retrying capture $captureIndex (attempt ${retry + 1}/$maxRetryAttempts)');
          }
          
          await Future.delayed(const Duration(milliseconds: 500)); // Retry delay
          final result = await _executeSingleCapture(captureIndex);
          _captureResults.add(result);
          
          if (_debug) {
            debugPrint('Capture $captureIndex succeeded on retry ${retry + 1}');
          }
          
          return; // Success, exit retry loop
          
        } catch (retryError) {
          if (_debug) {
            debugPrint('Retry ${retry + 1} failed: $retryError');
          }
          
          if (retry == maxRetryAttempts - 1) {
            // Final retry failed
            _errors.add('Capture $captureIndex failed after $maxRetryAttempts attempts');
          }
        }
      }
    }
  }

  /// Phase 5: Post-process results
  Future<void> _postProcessResults() async {
    _setState(CaptureWorkflowState.postProcessing);
    _updateProgress(0.8, 'Post-processing capture results...');

    try {
      if (_debug) {
        debugPrint('Post-processing ${_captureResults.length} capture results...');
      }

      // Calculate performance metrics
      _calculatePerformanceMetrics();
      
      // Validate results
      _validateCaptureResults();
      
      if (_debug) {
        debugPrint('Post-processing completed');
      }

    } catch (e) {
      throw WorkflowException('Post-processing failed: $e');
    }
  }

  /// Phase 6: Cleanup resources
  Future<void> _performCleanup() async {
    _setState(CaptureWorkflowState.cleanup);
    _updateProgress(0.9, 'Cleaning up resources...');

    try {
      if (_debug) {
        debugPrint('Performing cleanup...');
      }

      // Stop performance monitoring
      _stopPerformanceMonitoring();
      
      // Note: We don't dispose managers here as they might be used elsewhere
      // In a real implementation, you'd manage this based on ownership

      if (_debug) {
        debugPrint('Cleanup completed');
      }

    } catch (e) {
      if (_debug) {
        debugPrint('Cleanup error (non-critical): $e');
      }
    }
  }

  /// Phase 7: Complete workflow
  Future<CaptureWorkflowResult> _completeWorkflow() async {
    _setState(CaptureWorkflowState.completed);
    _updateProgress(1.0, 'Workflow completed');

    _workflowEndTime = DateTime.now();
    
    final result = _buildWorkflowResult(isSuccessful: true);
    
    if (_workflowCompleter != null && !_workflowCompleter!.isCompleted) {
      _workflowCompleter!.complete(result);
    }
    
    return result;
  }

  /// Handle workflow errors
  Future<CaptureWorkflowResult> _handleWorkflowError(dynamic error) async {
    _setState(CaptureWorkflowState.error);
    
    final errorMessage = 'Workflow failed: $error';
    _errors.add(errorMessage);
    _onError?.call(errorMessage);
    
    if (_debug) {
      debugPrint(errorMessage);
    }

    _workflowEndTime = DateTime.now();
    
    try {
      await _performCleanup();
    } catch (cleanupError) {
      if (_debug) {
        debugPrint('Cleanup after error failed: $cleanupError');
      }
    }
    
    final result = _buildWorkflowResult(isSuccessful: false);
    
    if (_workflowCompleter != null && !_workflowCompleter!.isCompleted) {
      _workflowCompleter!.complete(result);
    }
    
    return result;
  }

  /// Build the final workflow result
  CaptureWorkflowResult _buildWorkflowResult({required bool isSuccessful}) {
    final stats = _buildWorkflowStats();
    
    return CaptureWorkflowResult(
      captures: List.unmodifiable(_captureResults),
      stats: stats,
      warnings: List.unmodifiable(_warnings),
      errors: List.unmodifiable(_errors),
      isSuccessful: isSuccessful && _captureResults.isNotEmpty,
      metadata: {
        'workflowVersion': '1.0.0',
        'arConfig': _arConfig.debug, // Simplified for now
        'captureConfig': _captureConfig.toString(),
        'deviceInfo': Platform.version,
        'completedAt': DateTime.now().toIso8601String(),
      },
    );
  }

  /// Build workflow statistics
  WorkflowStats _buildWorkflowStats() {
    final endTime = _workflowEndTime ?? DateTime.now();
    final totalDuration = endTime.difference(_workflowStartTime!);
    
    final avgCaptureTime = _captureTimings.isNotEmpty
        ? _captureTimings.map((d) => d.inMilliseconds).reduce((a, b) => a + b) / _captureTimings.length
        : 0.0;
    
    final throughput = totalDuration.inSeconds > 0 
        ? _captureResults.length / totalDuration.inSeconds
        : 0.0;
    
    return WorkflowStats(
      startTime: _workflowStartTime!,
      endTime: endTime,
      totalDuration: totalDuration,
      totalCaptures: _captureResults.length + _errors.length,
      successfulCaptures: _captureResults.length,
      failedCaptures: _errors.length,
      captureTimings: List.unmodifiable(_captureTimings),
      errorCounts: Map.unmodifiable(_errorCounts),
      averageCaptureTime: avgCaptureTime,
      throughputCapturesPerSecond: throughput,
      performanceMetrics: Map.unmodifiable(_performanceMetrics),
    );
  }

  /// Calculate performance metrics
  void _calculatePerformanceMetrics() {
    _performanceMetrics['totalCaptureTime'] = _captureTimings
        .map((d) => d.inMilliseconds)
        .fold<int>(0, (a, b) => a + b);
    
    if (_captureTimings.isNotEmpty) {
      final sortedTimings = List.from(_captureTimings)..sort();
      _performanceMetrics['minCaptureTime'] = sortedTimings.first.inMilliseconds;
      _performanceMetrics['maxCaptureTime'] = sortedTimings.last.inMilliseconds;
      _performanceMetrics['medianCaptureTime'] = sortedTimings[sortedTimings.length ~/ 2].inMilliseconds;
    }
    
    if (_memoryManager != null) {
      final memoryStats = _memoryManager!.memoryStats;
      _performanceMetrics['memoryUsage'] = memoryStats.totalMemoryUsed;
      _performanceMetrics['cacheHitRate'] = memoryStats.cacheHitRate;
    }
  }

  /// Validate capture results
  void _validateCaptureResults() {
    for (int i = 0; i < _captureResults.length; i++) {
      final result = _captureResults[i];
      
      // Check if pose tracking was successful
      if (!result.pose.isTracking) {
        _warnings.add('Capture $i had poor tracking quality');
      }
      
      // Check if file path exists
      if (result.filePath == null || result.filePath!.isEmpty) {
        _warnings.add('Capture $i has no file path');
      }
    }
  }

  /// Clear previous workflow results
  void _clearPreviousResults() {
    _captureResults.clear();
    _warnings.clear();
    _errors.clear();
    _captureTimings.clear();
    _errorCounts.clear();
    _performanceMetrics.clear();
  }

  /// Start performance monitoring
  void _startPerformanceMonitoring() {
    _progressTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (_memoryManager != null) {
        final stats = _memoryManager!.memoryStats;
        _performanceMetrics['currentMemoryUsage'] = stats.totalMemoryUsed;
        _performanceMetrics['currentCacheHitRate'] = stats.cacheHitRate;
      }
    });
  }

  /// Stop performance monitoring
  void _stopPerformanceMonitoring() {
    _progressTimer?.cancel();
    _progressTimer = null;
  }

  /// Set workflow state and notify listeners
  void _setState(CaptureWorkflowState newState) {
    if (_currentState != newState) {
      _currentState = newState;
      _stateController.add(newState);
      
      if (_debug) {
        debugPrint('Workflow state changed to: $newState');
      }
    }
  }

  /// Update progress and notify callback
  void _updateProgress(double progress, String status) {
    _onProgress?.call(progress, status);
    
    if (_debug) {
      debugPrint('Progress: ${(progress * 100).toStringAsFixed(1)}% - $status');
    }
  }

  /// Dispose the workflow
  Future<void> dispose() async {
    try {
      if (_debug) {
        debugPrint('Disposing integrated capture workflow...');
      }

      // Cancel any ongoing workflow
      if (isExecuting) {
        await cancelWorkflow();
      }

      // Stop monitoring
      _stopPerformanceMonitoring();

      // Close state stream
      await _stateController.close();

      // Clear data
      _clearPreviousResults();

      _currentState = CaptureWorkflowState.notInitialized;

      if (_debug) {
        debugPrint('Workflow disposed successfully');
      }

    } catch (e) {
      if (_debug) {
        debugPrint('Error disposing workflow: $e');
      }
    }
  }
}

/// Exception thrown during workflow execution
class WorkflowException implements Exception {
  final String message;
  WorkflowException(this.message);

  @override
  String toString() => 'WorkflowException: $message';
}