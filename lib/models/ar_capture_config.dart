import 'dart:math' as math;
import 'camera_resolution.dart';
import '../datatypes/image_format.dart';
import '../datatypes/buffer_strategy.dart';

/// Complete configuration for AR capture functionality
/// Contains all parameters needed for capture system initialization
class ARCaptureConfig {
  /// Enable high-resolution capture capability
  final bool enableHighResCapture;

  /// Automatic capture interval in milliseconds (0 = manual only)
  final int captureIntervalMs;

  /// Capture resolution (required)
  final CameraResolution resolution;

  /// Image format for captures (required)
  final ImageFormat format;

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

  const ARCaptureConfig({
    this.enableHighResCapture = false,
    this.captureIntervalMs = 5000,
    required this.resolution,
    required this.format,
    this.maxCacheSize = 10,
    this.jpegQuality = 85,
    this.autoExposure = true,
    this.autoWhiteBalance = true,
    this.defaultISO,
    this.defaultExposureTime,
    this.enablePoseStream = true,
    this.bufferStrategy = BufferStrategy.balanced,
  });

  /// Create from platform channel data
  factory ARCaptureConfig.fromMap(Map<String, dynamic> map) {
    return ARCaptureConfig(
      enableHighResCapture: map['enableHighResCapture'] as bool? ?? false,
      captureIntervalMs: map['captureIntervalMs'] as int? ?? 5000,
      resolution: CameraResolution.fromMap(map['resolution'] as Map<String, dynamic>),
      format: ImageFormat.values.firstWhere(
        (f) => f.name == map['format'] as String,
        orElse: () => ImageFormat.jpeg,
      ),
      maxCacheSize: map['maxCacheSize'] as int? ?? 10,
      jpegQuality: map['jpegQuality'] as int? ?? 85,
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
    );
  }

  /// Convert to platform channel data
  Map<String, dynamic> toMap() {
    return {
      'enableHighResCapture': enableHighResCapture,
      'captureIntervalMs': captureIntervalMs,
      'resolution': resolution.toMap(),
      'format': format.name,
      'maxCacheSize': maxCacheSize,
      'jpegQuality': jpegQuality,
      'autoExposure': autoExposure,
      'autoWhiteBalance': autoWhiteBalance,
      'defaultISO': defaultISO,
      'defaultExposureTime': defaultExposureTime?.inMicroseconds,
      'enablePoseStream': enablePoseStream,
      'bufferStrategy': bufferStrategy.name,
    };
  }

  /// Create configuration optimized for performance
  factory ARCaptureConfig.performance({
    required CameraResolution resolution,
    ImageFormat format = ImageFormat.jpeg,
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
    ImageFormat format = ImageFormat.jpeg,
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
    ImageFormat format = ImageFormat.raw,
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
    final bytesPerPixel = format == ImageFormat.raw ? 2 : 3; // RAW = 16bit, JPEG = 24bit uncompressed
    final imageSize = resolution.totalPixels * bytesPerPixel;
    final cacheSize = imageSize * maxCacheSize;

    // Add overhead for pose data, metadata, etc.
    final overhead = maxCacheSize * 1024; // 1KB per cached image for metadata

    return cacheSize + overhead;
  }

  /// Get estimated memory usage in MB
  double get estimatedMemoryUsageMB => getEstimatedMemoryUsage() / (1024 * 1024);

  /// Check if configuration is valid
  bool get isValid {
    // Check basic constraints
    if (captureIntervalMs < 0) return false;
    if (maxCacheSize <= 0 || maxCacheSize > 100) return false;
    if (jpegQuality < 10 || jpegQuality > 100) return false;
    if (defaultISO != null && (defaultISO! <= 0 || defaultISO! > 25600)) return false;

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
    int? captureIntervalMs,
    CameraResolution? resolution,
    ImageFormat? format,
    int? maxCacheSize,
    int? jpegQuality,
    bool? autoExposure,
    bool? autoWhiteBalance,
    int? defaultISO,
    Duration? defaultExposureTime,
    bool? enablePoseStream,
    BufferStrategy? bufferStrategy,
  }) {
    return ARCaptureConfig(
      enableHighResCapture: enableHighResCapture ?? this.enableHighResCapture,
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
    final maxAllowableImages = ((availableMemoryMB * 0.3) * 1024 * 1024 / bytesPerImage).floor();
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
        other.bufferStrategy == bufferStrategy;
  }

  @override
  int get hashCode {
    return enableHighResCapture.hashCode ^
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
        bufferStrategy.hashCode;
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