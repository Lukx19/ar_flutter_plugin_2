import 'dart:math' as math;
import 'camera_resolution.dart';
import '../datatypes/image_format.dart';
import '../datatypes/buffer_strategy.dart';

/// The still a manual shutter takes on iOS (`initializeCapture`
/// `stillCaptureMode`). Automatic captures always take [single]; Android
/// ignores the setting. Every mode runs inside the running AR session: the
/// session is never paused for a still, and the other cameras (ultra-wide,
/// telephoto) are not available while ARKit runs.
enum ARStillCaptureMode {
  /// One ARKit high-resolution frame (about 100 ms on the iPhone 12 Pro).
  single('single'),

  /// [ARCaptureConfig.burstImageCount] high-resolution frames in sequence,
  /// each with its own frame's pose, intrinsics and exposure time.
  burst('burst'),

  /// One frame with ARKit multi-frame processing (balanced photo quality,
  /// about 490 ms; iOS 26 and later, else a plain frame) and video HDR on
  /// formats that support it: the in-AR HDR.
  inArQuality('inArQuality');

  const ARStillCaptureMode(this.wireValue);

  final String wireValue;

  static ARStillCaptureMode fromWire(Object? value) => values.firstWhere(
        (mode) => mode.wireValue == value,
        orElse: () => ARStillCaptureMode.single,
      );
}

/// Complete configuration for AR capture functionality
/// Contains all parameters needed for capture system initialization
class ARCaptureConfig {
  /// Enable high-resolution capture capability
  final bool enableHighResCapture;

  /// Immutable construction mode for new assessed originals. Readers remain
  /// disposition-aware when this producer mode is off; Android debug only.
  final bool retainAssessedOriginals;

  /// Automatic capture interval in milliseconds (0 = manual only)
  final int captureIntervalMs;

  /// Capture resolution (required)
  final CameraResolution resolution;

  /// Image format for captures (required)
  final CaptureFormat format;

  /// Maximum number of images to cache in memory
  final int maxCacheSize;

  /// JPEG quality (0-100, only used for JPEG format)
  final int jpegQuality;

  /// Enable automatic exposure adjustment
  final bool autoExposure;

  /// Enable automatic white balance
  final bool autoWhiteBalance;

  /// Default ISO value (null = auto)
  final int? defaultISO;

  /// Default exposure time (null = auto)
  final Duration? defaultExposureTime;

  /// Enable pose data streaming
  final bool enablePoseStream;

  /// Buffer management strategy
  final BufferStrategy bufferStrategy;

  /// iOS still of a manual shutter; sent only when not [ARStillCaptureMode.single].
  final ARStillCaptureMode stillCaptureMode;

  /// Images of an iOS burst shot (2-5).
  final int burstImageCount;

  /// Wait between burst images (0-1000 ms); a little hand motion over it
  /// gives extra viewpoints.
  final int burstIntervalMs;

  static const int defaultBurstImageCount = 3;
  static const int defaultBurstIntervalMs = 150;

  const ARCaptureConfig({
    this.enableHighResCapture = false,
    this.retainAssessedOriginals = false,
    this.captureIntervalMs = 5000,
    required this.resolution,
    required this.format,
    this.maxCacheSize = 10,
    this.jpegQuality = 95,
    this.autoExposure = true,
    this.autoWhiteBalance = true,
    this.defaultISO,
    this.defaultExposureTime,
    this.enablePoseStream = true,
    this.bufferStrategy = BufferStrategy.balanced,
    this.stillCaptureMode = ARStillCaptureMode.single,
    this.burstImageCount = defaultBurstImageCount,
    this.burstIntervalMs = defaultBurstIntervalMs,
  });

  /// Create from platform channel data
  factory ARCaptureConfig.fromMap(Map<String, dynamic> map) {
    return ARCaptureConfig(
      enableHighResCapture: map['enableHighResCapture'] as bool? ?? false,
      retainAssessedOriginals: map['retainAssessedOriginals'] as bool? ?? false,
      captureIntervalMs: map['captureIntervalMs'] as int? ?? 5000,
      resolution:
          CameraResolution.fromMap(map['resolution'] as Map<String, dynamic>),
      format: CaptureFormat.fromWire(map['format']),
      maxCacheSize: map['maxCacheSize'] as int? ?? 10,
      jpegQuality: map['jpegQuality'] as int? ?? 95,
      autoExposure: map['autoExposure'] as bool? ?? true,
      autoWhiteBalance: map['autoWhiteBalance'] as bool? ?? true,
      defaultISO: map['defaultISO'] as int?,
      defaultExposureTime: map['defaultExposureTime'] != null
          ? Duration(microseconds: map['defaultExposureTime'] as int)
          : null,
      enablePoseStream: map['enablePoseStream'] as bool? ?? true,
      bufferStrategy: BufferStrategy.values.firstWhere(
        (s) => s.name == map['bufferStrategy'] as String?,
        orElse: () => BufferStrategy.balanced,
      ),
      stillCaptureMode: ARStillCaptureMode.fromWire(map['stillCaptureMode']),
      burstImageCount:
          map['burstImageCount'] as int? ?? defaultBurstImageCount,
      burstIntervalMs:
          map['burstIntervalMs'] as int? ?? defaultBurstIntervalMs,
    );
  }

  /// Convert to platform channel data
  Map<String, dynamic> toMap() {
    return {
      'enableHighResCapture': enableHighResCapture,
      'retainAssessedOriginals': retainAssessedOriginals,
      'captureIntervalMs': captureIntervalMs,
      'resolution': resolution.toMap(),
      'format': format.wireValue,
      'maxCacheSize': maxCacheSize,
      'jpegQuality': jpegQuality,
      'autoExposure': autoExposure,
      'autoWhiteBalance': autoWhiteBalance,
      'defaultISO': defaultISO,
      'defaultExposureTime': defaultExposureTime?.inMicroseconds,
      'enablePoseStream': enablePoseStream,
      'bufferStrategy': bufferStrategy.name,
      if (stillCaptureMode != ARStillCaptureMode.single)
        'stillCaptureMode': stillCaptureMode.wireValue,
      if (stillCaptureMode == ARStillCaptureMode.burst) ...{
        'burstImageCount': burstImageCount,
        'burstIntervalMs': burstIntervalMs,
      },
    };
  }

  /// Create configuration optimized for performance
  factory ARCaptureConfig.performance({
    required CameraResolution resolution,
    CaptureFormat format = CaptureFormat.jpeg,
    int captureIntervalMs = 1000,
  }) {
    return ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: captureIntervalMs,
      resolution: resolution,
      format: format,
      maxCacheSize: 20, // Larger cache
      jpegQuality: 90, // Higher quality
      bufferStrategy: BufferStrategy.performance,
      enablePoseStream: true,
    );
  }

  /// Create configuration optimized for memory usage
  factory ARCaptureConfig.memoryOptimized({
    required CameraResolution resolution,
    CaptureFormat format = CaptureFormat.jpeg,
    int captureIntervalMs = 5000,
  }) {
    return ARCaptureConfig(
      enableHighResCapture: false,
      captureIntervalMs: captureIntervalMs,
      resolution: resolution,
      format: format,
      maxCacheSize: 5, // Smaller cache
      jpegQuality: 70, // Lower quality
      bufferStrategy: BufferStrategy.memory,
      enablePoseStream: false, // Disabled to save memory
    );
  }

  /// Create configuration for research/professional use
  factory ARCaptureConfig.research({
    required CameraResolution resolution,
    CaptureFormat format = CaptureFormat.rawJpeg,
    int captureIntervalMs = 10000,
  }) {
    return ARCaptureConfig(
      enableHighResCapture: true,
      captureIntervalMs: captureIntervalMs,
      resolution: resolution,
      format: format,
      maxCacheSize: 15,
      jpegQuality: 95,
      autoExposure: false, // Manual control for research
      autoWhiteBalance: false,
      bufferStrategy: BufferStrategy.balanced,
      enablePoseStream: true,
    );
  }

  /// Get estimated memory usage in bytes
  int getEstimatedMemoryUsage() {
    final bytesPerPixel =
        format == CaptureFormat.rawJpeg
            ? 2
            : 3; // RAW = 16bit, JPEG = 24bit uncompressed
    final imageSize = resolution.totalPixels * bytesPerPixel;
    final cacheSize = imageSize * maxCacheSize;

    // Add overhead for pose data, metadata, etc.
    final overhead = maxCacheSize * 1024; // 1KB per cached image for metadata

    return cacheSize + overhead;
  }

  /// Get estimated memory usage in MB
  double get estimatedMemoryUsageMB =>
      getEstimatedMemoryUsage() / (1024 * 1024);

  /// Check if configuration is valid
  bool get isValid {
    // Check basic constraints
    if (captureIntervalMs < 0) return false;
    if (captureIntervalMs > 0 && captureIntervalMs < 100) return false;
    if (captureIntervalMs > 3600000) return false;
    if (maxCacheSize <= 0 || maxCacheSize > 100) return false;
    if (jpegQuality < 10 || jpegQuality > 100) return false;
    if (burstImageCount < 2 || burstImageCount > 5) return false;
    if (burstIntervalMs < 0 || burstIntervalMs > 1000) return false;
    if (defaultISO != null && (defaultISO! <= 0 || defaultISO! > 25600))
      return false;

    // Check resolution constraints
    if (resolution.width <= 0 || resolution.height <= 0) return false;
    if (resolution.width > 8192 || resolution.height > 8192) return false;

    // Check exposure time constraints
    if (defaultExposureTime != null) {
      final micros = defaultExposureTime!.inMicroseconds;
      if (micros <= 0 || micros > 1000000) return false; // 0 to 1 second
    }

    return true;
  }

  /// Check if configuration requires high memory usage
  bool get isHighMemoryUsage => estimatedMemoryUsageMB > 100.0;

  /// Check if configuration is optimized for performance
  bool get isPerformanceOptimized {
    return bufferStrategy == BufferStrategy.performance &&
        maxCacheSize >= 15 &&
        jpegQuality >= 85;
  }

  /// Check if configuration is optimized for memory
  bool get isMemoryOptimized {
    return bufferStrategy == BufferStrategy.memory &&
        maxCacheSize <= 8 &&
        jpegQuality <= 75;
  }

  /// Get capture frequency in Hz
  double get captureFrequencyHz {
    return captureIntervalMs > 0 ? 1000.0 / captureIntervalMs : 0.0;
  }

  /// Create copy with modified parameters
  ARCaptureConfig copyWith({
    bool? enableHighResCapture,
    bool? retainAssessedOriginals,
    int? captureIntervalMs,
    CameraResolution? resolution,
    CaptureFormat? format,
    int? maxCacheSize,
    int? jpegQuality,
    bool? autoExposure,
    bool? autoWhiteBalance,
    int? defaultISO,
    Duration? defaultExposureTime,
    bool? enablePoseStream,
    BufferStrategy? bufferStrategy,
    ARStillCaptureMode? stillCaptureMode,
    int? burstImageCount,
    int? burstIntervalMs,
  }) {
    return ARCaptureConfig(
      enableHighResCapture: enableHighResCapture ?? this.enableHighResCapture,
      retainAssessedOriginals: retainAssessedOriginals ?? this.retainAssessedOriginals,
      captureIntervalMs: captureIntervalMs ?? this.captureIntervalMs,
      resolution: resolution ?? this.resolution,
      format: format ?? this.format,
      maxCacheSize: maxCacheSize ?? this.maxCacheSize,
      jpegQuality: jpegQuality ?? this.jpegQuality,
      autoExposure: autoExposure ?? this.autoExposure,
      autoWhiteBalance: autoWhiteBalance ?? this.autoWhiteBalance,
      defaultISO: defaultISO ?? this.defaultISO,
      defaultExposureTime: defaultExposureTime ?? this.defaultExposureTime,
      enablePoseStream: enablePoseStream ?? this.enablePoseStream,
      bufferStrategy: bufferStrategy ?? this.bufferStrategy,
      stillCaptureMode: stillCaptureMode ?? this.stillCaptureMode,
      burstImageCount: burstImageCount ?? this.burstImageCount,
      burstIntervalMs: burstIntervalMs ?? this.burstIntervalMs,
    );
  }

  /// Create configuration with adjusted cache size based on available memory
  ARCaptureConfig adjustForAvailableMemory(int availableMemoryMB) {
    final estimatedUsageMB = estimatedMemoryUsageMB;

    if (estimatedUsageMB <= availableMemoryMB * 0.5) {
      return this; // Configuration is fine
    }

    // Reduce cache size to fit within memory constraints
    final bytesPerImage = getEstimatedMemoryUsage() / maxCacheSize;
    final maxAllowableImages =
        ((availableMemoryMB * 0.3) * 1024 * 1024 / bytesPerImage).floor();
    final adjustedCacheSize = math.max(1, math.min(maxAllowableImages, 20));

    return copyWith(
      maxCacheSize: adjustedCacheSize,
      bufferStrategy: BufferStrategy.memory,
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is ARCaptureConfig &&
        other.enableHighResCapture == enableHighResCapture &&
        other.retainAssessedOriginals == retainAssessedOriginals &&
        other.captureIntervalMs == captureIntervalMs &&
        other.resolution == resolution &&
        other.format == format &&
        other.maxCacheSize == maxCacheSize &&
        other.jpegQuality == jpegQuality &&
        other.autoExposure == autoExposure &&
        other.autoWhiteBalance == autoWhiteBalance &&
        other.defaultISO == defaultISO &&
        other.defaultExposureTime == defaultExposureTime &&
        other.enablePoseStream == enablePoseStream &&
        other.bufferStrategy == bufferStrategy &&
        other.stillCaptureMode == stillCaptureMode &&
        other.burstImageCount == burstImageCount &&
        other.burstIntervalMs == burstIntervalMs;
  }

  @override
  int get hashCode {
    return enableHighResCapture.hashCode ^
        retainAssessedOriginals.hashCode ^
        captureIntervalMs.hashCode ^
        resolution.hashCode ^
        format.hashCode ^
        maxCacheSize.hashCode ^
        jpegQuality.hashCode ^
        autoExposure.hashCode ^
        autoWhiteBalance.hashCode ^
        defaultISO.hashCode ^
        defaultExposureTime.hashCode ^
        enablePoseStream.hashCode ^
        bufferStrategy.hashCode ^
        stillCaptureMode.hashCode ^
        burstImageCount.hashCode ^
        burstIntervalMs.hashCode;
  }

  @override
  String toString() {
    return 'ARCaptureConfig('
        'resolution: $resolution, '
        'format: ${format.name}, '
        'interval: ${captureIntervalMs}ms, '
        'cache: $maxCacheSize, '
        'memory: ${estimatedMemoryUsageMB.toStringAsFixed(1)}MB'
        ')';
  }
}
