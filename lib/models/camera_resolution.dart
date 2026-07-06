/// Represents camera resolution with width/height
class CameraResolution {
  final int width;
  final int height;

  const CameraResolution({
    required this.width,
    required this.height,
  });

  /// Create resolution from platform channel data
  factory CameraResolution.fromMap(Map<String, dynamic> map) {
    return CameraResolution(
      width: map['width'] as int,
      height: map['height'] as int,
    );
  }

  /// Convert to map for platform channel communication
  Map<String, dynamic> toMap() {
    return {
      'width': width,
      'height': height,
    };
  }

  /// Calculate aspect ratio
  double get aspectRatio => width / height;

  /// Calculate total pixels
  int get totalPixels => width * height;

  /// Check if resolution is landscape
  bool get isLandscape => width > height;

  /// Check if resolution is portrait
  bool get isPortrait => height > width;

  /// Check if resolution is square
  bool get isSquare => width == height;

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is CameraResolution &&
        other.width == width &&
        other.height == height;
  }

  @override
  int get hashCode => width.hashCode ^ height.hashCode;

  @override
  String toString() => '${width}x$height';

  /// Compare resolutions by total pixels
  int compareTo(CameraResolution other) {
    return totalPixels.compareTo(other.totalPixels);
  }
}