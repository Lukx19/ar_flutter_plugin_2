import 'dart:async';
import 'dart:collection';
import 'dart:typed_data';

import '../models/ar_capture_config.dart';

/// Cache entry types for different data categories.
enum CacheEntryType {
  texture,
  cameraFrame,
  captureResult,
  configuration,
  intrinsics,
  metadata,
  temporary,
}

/// Cache eviction policies.
enum EvictionPolicy { lru, lfu, fifo, ttl, size }

/// Memory buffer allocation strategies for the legacy cache manager.
enum CaptureMemoryBufferStrategy { fixed, dynamic, pooled, hybrid }

/// Cache entry with metadata.
class CacheEntry<T> {
  final String key;
  final T data;
  final CacheEntryType type;
  final DateTime createdAt;
  final DateTime? expiresAt;
  final int size;

  DateTime lastAccessed;
  int accessCount;

  CacheEntry({
    required this.key,
    required this.data,
    required this.type,
    required this.size,
    DateTime? createdAt,
    this.expiresAt,
  }) : createdAt = createdAt ?? DateTime.now(),
       lastAccessed = DateTime.now(),
       accessCount = 1;

  bool get isExpired =>
      expiresAt != null && DateTime.now().isAfter(expiresAt!);

  void markAccessed() {
    lastAccessed = DateTime.now();
    accessCount++;
  }

  Duration get age => DateTime.now().difference(createdAt);
  Duration get timeSinceLastAccess => DateTime.now().difference(lastAccessed);
}

/// Memory statistics for monitoring.
class MemoryStats {
  final int totalMemoryUsed;
  final int cacheHitRate;
  final int bufferCount;
  final Map<String, int> categoryUsage;
  final int totalEntries;
  final int expiredEntries;
  final double averageEntrySize;
  final Map<String, int> accessPatterns;
  final DateTime lastCleanup;
  final double memoryEfficiency;

  const MemoryStats({
    required this.totalMemoryUsed,
    required this.cacheHitRate,
    required this.bufferCount,
    required this.categoryUsage,
    required this.totalEntries,
    required this.expiredEntries,
    required this.averageEntrySize,
    required this.accessPatterns,
    required this.lastCleanup,
    required this.memoryEfficiency,
  });

  Map<String, dynamic> toMap() {
    return {
      'totalMemoryUsed': totalMemoryUsed,
      'cacheHitRate': cacheHitRate,
      'bufferCount': bufferCount,
      'categoryUsage': categoryUsage,
      'totalEntries': totalEntries,
      'expiredEntries': expiredEntries,
      'averageEntrySize': averageEntrySize,
      'accessPatterns': accessPatterns,
      'lastCleanup': lastCleanup.millisecondsSinceEpoch,
      'memoryEfficiency': memoryEfficiency,
    };
  }
}

/// Buffer pool for efficient memory management.
class BufferPool {
  final String name;
  final int bufferSize;
  final int maxPoolSize;
  final Queue<Uint8List> _availableBuffers = Queue<Uint8List>();
  final Set<Uint8List> _inUseBuffers = <Uint8List>{};

  int _totalAllocated = 0;
  int _totalRequests = 0;
  int _poolHits = 0;

  BufferPool({
    required this.name,
    required this.bufferSize,
    required this.maxPoolSize,
  });

  Uint8List getBuffer() {
    _totalRequests++;

    if (_availableBuffers.isNotEmpty) {
      final buffer = _availableBuffers.removeFirst();
      _inUseBuffers.add(buffer);
      _poolHits++;
      return buffer;
    }

    final buffer = Uint8List(bufferSize);
    _inUseBuffers.add(buffer);
    _totalAllocated++;
    return buffer;
  }

  void returnBuffer(Uint8List buffer) {
    if (_inUseBuffers.remove(buffer) && _availableBuffers.length < maxPoolSize) {
      _availableBuffers.add(buffer);
    }
  }

  Map<String, dynamic> getStats() {
    return {
      'name': name,
      'bufferSize': bufferSize,
      'maxPoolSize': maxPoolSize,
      'availableBuffers': _availableBuffers.length,
      'inUseBuffers': _inUseBuffers.length,
      'totalAllocated': _totalAllocated,
      'totalRequests': _totalRequests,
      'poolHitRate': _totalRequests > 0
          ? (_poolHits / _totalRequests * 100).round()
          : 0,
    };
  }

  void clear() {
    _availableBuffers.clear();
    _inUseBuffers.clear();
  }
}

/// Legacy pure-Dart cache manager.
@Deprecated(
  'CaptureMemoryManager is not part of the live routed capture stack. '
  'Use ARCaptureManager capacity feedback on the per-view capture channel instead.',
)
class CaptureMemoryManager {
  CaptureMemoryManager._();

  static CaptureMemoryManager? _instance;
  static CaptureMemoryManager get instance =>
      _instance ??= CaptureMemoryManager._();

  final StreamController<MemoryStats> _statsController =
      StreamController<MemoryStats>.broadcast();

  static UnsupportedError _unsupported() {
    return UnsupportedError(
      'CaptureMemoryManager is not wired into the current capture pipeline. '
      'Use ARCaptureManager.getCaptureCapacity() and the app-owned backpressure flow instead.',
    );
  }

  Future<void> initializeWithConfig(ARCaptureConfig config) async {
    throw _unsupported();
  }

  Future<void> preallocateBuffers() async {
    throw _unsupported();
  }

  T? getCached<T>(String key) {
    throw _unsupported();
  }

  Future<void> cache<T>(
    String key,
    T data, {
    Duration? ttl,
    CacheEntryType? type,
    int? estimatedSize,
  }) async {
    throw _unsupported();
  }

  void clearCache() {
    throw _unsupported();
  }

  void clearCacheByType(CacheEntryType type) {
    throw _unsupported();
  }

  MemoryStats get memoryStats => MemoryStats(
    totalMemoryUsed: 0,
    cacheHitRate: 0,
    bufferCount: 0,
    categoryUsage: const <String, int>{},
    totalEntries: 0,
    expiredEntries: 0,
    averageEntrySize: 0,
    accessPatterns: const <String, int>{},
    lastCleanup: DateTime.fromMillisecondsSinceEpoch(0),
    memoryEfficiency: 0,
  );

  Uint8List? getBuffer(String poolName) {
    throw _unsupported();
  }

  void returnBuffer(String poolName, Uint8List buffer) {
    throw _unsupported();
  }

  Map<String, dynamic> getBufferPoolStats() {
    throw _unsupported();
  }

  Stream<MemoryStats> get statisticsStream => _statsController.stream;

  Future<void> dispose() async {
    await _statsController.close();
    _instance = null;
  }
}
