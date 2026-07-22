class CaptureQualityPolicy {
  const CaptureQualityPolicy({
    required this.blurFilterEnabled,
    required this.blurThreshold,
    required this.keepRejectedCaptures,
  });

  const CaptureQualityPolicy.productionDefault()
    : blurFilterEnabled = true,
      blurThreshold = 110.0,
      keepRejectedCaptures = false;

  final bool blurFilterEnabled;
  final double blurThreshold;
  final bool keepRejectedCaptures;

  factory CaptureQualityPolicy.fromMap(Map<String, dynamic> map) {
    return CaptureQualityPolicy(
      blurFilterEnabled: map['blurFilterEnabled'] as bool? ?? true,
      blurThreshold: (map['blurThreshold'] as num?)?.toDouble() ?? 110.0,
      keepRejectedCaptures: map['keepRejectedCaptures'] as bool? ?? false,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'blurFilterEnabled': blurFilterEnabled,
      'blurThreshold': blurThreshold,
      'keepRejectedCaptures': keepRejectedCaptures,
    };
  }
}
