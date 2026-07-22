class CaptureCapacity {
  const CaptureCapacity({
    required this.maxEntries,
    required this.usedEntries,
    required this.reservedEntries,
    required this.readyEntries,
    required this.persistingEntries,
    required this.pendingRetryEntries,
    required this.stagedBytes,
    required this.maxStagedBytes,
    required this.canCapture,
    this.blockedReason,
  });

  factory CaptureCapacity.fromMap(Map<String, dynamic> map) {
    return CaptureCapacity(
      maxEntries: (map['maxEntries'] as num?)?.toInt() ?? 0,
      usedEntries: (map['usedEntries'] as num?)?.toInt() ?? 0,
      reservedEntries: (map['reservedEntries'] as num?)?.toInt() ?? 0,
      readyEntries: (map['readyEntries'] as num?)?.toInt() ?? 0,
      persistingEntries: (map['persistingEntries'] as num?)?.toInt() ?? 0,
      pendingRetryEntries: (map['pendingRetryEntries'] as num?)?.toInt() ?? 0,
      stagedBytes: (map['stagedBytes'] as num?)?.toInt() ?? 0,
      maxStagedBytes: (map['maxStagedBytes'] as num?)?.toInt() ?? 0,
      canCapture: map['canCapture'] as bool? ?? false,
      blockedReason: map['blockedReason'] as String?,
    );
  }

  final int maxEntries;
  final int usedEntries;
  final int reservedEntries;
  final int readyEntries;
  final int persistingEntries;
  final int pendingRetryEntries;
  final int stagedBytes;
  final int maxStagedBytes;
  final bool canCapture;
  final String? blockedReason;

  Map<String, dynamic> toMap() {
    return {
      'maxEntries': maxEntries,
      'usedEntries': usedEntries,
      'reservedEntries': reservedEntries,
      'readyEntries': readyEntries,
      'persistingEntries': persistingEntries,
      'pendingRetryEntries': pendingRetryEntries,
      'stagedBytes': stagedBytes,
      'maxStagedBytes': maxStagedBytes,
      'canCapture': canCapture,
      if (blockedReason != null) 'blockedReason': blockedReason,
    };
  }
}
