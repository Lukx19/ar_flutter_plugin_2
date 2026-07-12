import 'ar_capture_result.dart';

enum NativeCaptureStatus { staged, acceptedPending, rejectedBlur }

class CaptureQualityResult {
  const CaptureQualityResult({
    required this.blurScore,
    required this.blurThreshold,
    required this.blurPassed,
    required this.analyzedWidth,
    required this.analyzedHeight,
    required this.algorithm,
  });

  final double blurScore;
  final double blurThreshold;
  final bool blurPassed;
  final int analyzedWidth;
  final int analyzedHeight;
  final String algorithm;

  factory CaptureQualityResult.fromMap(Map<String, dynamic> map) {
    return CaptureQualityResult(
      blurScore: (map['blurScore'] as num?)?.toDouble() ?? 0.0,
      blurThreshold: (map['blurThreshold'] as num?)?.toDouble() ?? 0.0,
      blurPassed: map['blurPassed'] as bool? ?? false,
      analyzedWidth: (map['analyzedWidth'] as num?)?.toInt() ?? 0,
      analyzedHeight: (map['analyzedHeight'] as num?)?.toInt() ?? 0,
      algorithm: map['algorithm'] as String? ?? 'unknown',
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'blurScore': blurScore,
      'blurThreshold': blurThreshold,
      'blurPassed': blurPassed,
      'analyzedWidth': analyzedWidth,
      'analyzedHeight': analyzedHeight,
      'algorithm': algorithm,
    };
  }
}

class ARCaptureAttemptResult {
  const ARCaptureAttemptResult({
    required this.status,
    required this.attemptId,
    this.imageId,
    this.capture,
    this.quality,
  });

  final NativeCaptureStatus status;
  final String attemptId;
  final String? imageId;
  final ARCaptureResult? capture;
  final CaptureQualityResult? quality;

  bool get isStaged => status == NativeCaptureStatus.staged;
  bool get isAcceptedPending => status == NativeCaptureStatus.acceptedPending;
  bool get isRejectedBlur => status == NativeCaptureStatus.rejectedBlur;

  factory ARCaptureAttemptResult.fromMap(Map<String, dynamic> map) {
    return ARCaptureAttemptResult(
      status: NativeCaptureStatus.values.firstWhere(
        (value) => value.name == map['status'],
        orElse: () => NativeCaptureStatus.staged,
      ),
      attemptId: map['attemptId'] as String,
      imageId: map['imageId'] as String?,
      capture: map['capture'] != null
          ? ARCaptureResult.fromMap(map['capture'] as Map<String, dynamic>)
          : null,
      quality: map['quality'] != null
          ? CaptureQualityResult.fromMap(map['quality'] as Map<String, dynamic>)
          : null,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'status': status.name,
      'attemptId': attemptId,
      'imageId': imageId,
      'capture': capture?.toMap(),
      'quality': quality?.toMap(),
    };
  }
}
