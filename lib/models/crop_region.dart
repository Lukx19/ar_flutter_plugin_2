class CropRegion {
  final int left;
  final int top;
  final int width;
  final int height;

  const CropRegion({
    required this.left,
    required this.top,
    required this.width,
    required this.height,
  });

  factory CropRegion.fromMap(Map<String, dynamic> map) {
    return CropRegion(
      left: map['left'] as int? ?? 0,
      top: map['top'] as int? ?? 0,
      width: map['width'] as int? ?? 0,
      height: map['height'] as int? ?? 0,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'left': left,
      'top': top,
      'width': width,
      'height': height,
    };
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is CropRegion &&
        other.left == left &&
        other.top == top &&
        other.width == width &&
        other.height == height;
  }

  @override
  int get hashCode => left.hashCode ^ top.hashCode ^ width.hashCode ^ height.hashCode;
}
