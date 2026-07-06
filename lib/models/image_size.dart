/// Represents image dimensions and memory requirements
class ImageSize {
  final int width;
  final int height;
  final int bytesPerPixel;
  final int totalBytes;

  const ImageSize({
    required this.width,
    required this.height,
    required this.bytesPerPixel,
    required this.totalBytes,
  });

  factory ImageSize.fromMap(Map<String, dynamic> map) {
    return ImageSize(
      width: map['width'] as int,
      height: map['height'] as int,
      bytesPerPixel: map['bytesPerPixel'] as int,
      totalBytes: map['totalBytes'] as int,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'width': width,
      'height': height,
      'bytesPerPixel': bytesPerPixel,
      'totalBytes': totalBytes,
    };
  }

  /// Calculate expected total bytes
  int get expectedTotalBytes => width * height * bytesPerPixel;

  /// Check if total bytes matches expected calculation
  bool get isConsistent => totalBytes == expectedTotalBytes;

  /// Get memory size in MB
  double get sizeInMB => totalBytes / (1024 * 1024);

  /// Get memory size in KB
  double get sizeInKB => totalBytes / 1024;

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is ImageSize &&
        other.width == width &&
        other.height == height &&
        other.bytesPerPixel == bytesPerPixel &&
        other.totalBytes == totalBytes;
  }

  @override
  int get hashCode =>
      width.hashCode ^ height.hashCode ^ bytesPerPixel.hashCode ^ totalBytes.hashCode;

  @override
  String toString() => 'ImageSize(${width}x$height, ${bytesPerPixel}bpp, ${sizeInMB.toStringAsFixed(1)}MB)';
}