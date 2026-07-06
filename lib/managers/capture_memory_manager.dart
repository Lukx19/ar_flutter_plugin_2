import 'dart:async';
import 'dart:collection';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../models/ar_capture_config.dart';

/// Cache entry types for different data categories
enum CacheEntryType {
  texture,
  cameraFrame,
  captureResult,
  configuration,
  intrinsics,
  metadata,
  temporary,
}

/// Cache eviction policies
enum EvictionPolicy {
  lru,        // Least Recently Used
  lfu,        // Least Frequently Used
  fifo,       // First In, First Out
  ttl,        // Time To Live
  size,       // Size-based eviction
}

/// Memory buffer allocation strategies
enum BufferStrategy {
  fixed,      // Fixed-size buffers
  dynamic,    // Dynamic allocation
  pooled,     // Buffer pooling
  hybrid,     // Combination of strategies
}

/// Cache entry with metadata
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

  bool get isExpired => expiresAt != null && DateTime.now().isAfter(expiresAt!);
  
  void markAccessed() {
    lastAccessed = DateTime.now();
    accessCount++;
  }
  
  Duration get age => DateTime.now().difference(createdAt);
  Duration get timeSinceLastAccess => DateTime.now().difference(lastAccessed);
}

/// Memory statistics for monitoring
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

/// Buffer pool for efficient memory management
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

  /// Get a buffer from the pool or allocate a new one
  Uint8List getBuffer() {
    _totalRequests++;
    
    if (_availableBuffers.isNotEmpty) {
      final buffer = _availableBuffers.removeFirst();
      _inUseBuffers.add(buffer);
      _poolHits++;
      return buffer;
    }
    
    // Allocate new buffer
    final buffer = Uint8List(bufferSize);
    _inUseBuffers.add(buffer);
    _totalAllocated++;
    
    return buffer;
  }

  /// Return a buffer to the pool
  void returnBuffer(Uint8List buffer) {
    if (_inUseBuffers.remove(buffer)) {
      if (_availableBuffers.length < maxPoolSize) {
        _availableBuffers.add(buffer);
      }
      // Otherwise, let it be garbage collected
    }
  }

  /// Get pool statistics
  Map<String, dynamic> getStats() {
    return {
      'name': name,
      'bufferSize': bufferSize,
      'maxPoolSize': maxPoolSize,
      'availableBuffers': _availableBuffers.length,
      'inUseBuffers': _inUseBuffers.length,
      'totalAllocated': _totalAllocated,
      'totalRequests': _totalRequests,
      'poolHitRate': _totalRequests > 0 ? (_poolHits / _totalRequests * 100).round() : 0,
    };
  }

  /// Clear the pool
  void clear() {
    _availableBuffers.clear();
    _inUseBuffers.clear();
  }
}

/// Comprehensive memory cache system for AR capture operations
class CaptureMemoryManager {
  static CaptureMemoryManager? _instance;
  static CaptureMemoryManager get instance => _instance ??= CaptureMemoryManager._();

  CaptureMemoryManager._();

  // Configuration
  ARCaptureConfig? _config;
  bool _isInitialized = false;
  bool _debug = false;

  // Cache storage with type-specific maps
  final Map<String, CacheEntry> _cache = {};
  final Map<CacheEntryType, Map<String, CacheEntry>> _typedCaches = {};
  
  // Memory management
  int _maxCacheSize = 100 * 1024 * 1024; // 100MB default
  int _currentCacheSize = 0;
  EvictionPolicy _evictionPolicy = EvictionPolicy.lru;
  
  // Buffer pools for different purposes
  final Map<String, BufferPool> _bufferPools = {};
  
  // Statistics tracking
  int _cacheHits = 0;
  int _cacheMisses = 0;
  int _evictions = 0;
  DateTime _lastCleanup = DateTime.now();
  
  // Cleanup management
  Timer? _cleanupTimer;
  Duration _cleanupInterval = const Duration(minutes: 5);
  
  // Memory monitoring
  final StreamController<MemoryStats> _statsController = StreamController.broadcast();

  /// Initialize the memory manager with configuration
  Future<void> initializeWithConfig(ARCaptureConfig config) async {
    if (_isInitialized) {
      if (_debug) {
        debugPrint('CaptureMemoryManager already initialized');
      }
      return;
    }

    try {
      _config = config;
      _debug = false; // Set debug separately if needed

      if (_debug) {
        debugPrint('Initializing CaptureMemoryManager...');
      }

      // Configure cache size based on config
      _maxCacheSize = config.maxCacheSize;
      
      // Initialize typed caches
      for (final type in CacheEntryType.values) {
        _typedCaches[type] = <String, CacheEntry>{};
      }

      // Create buffer pools based on configuration
      await _createBufferPools(config);

      // Start periodic cleanup
      _startPeriodicCleanup();

      _isInitialized = true;

      if (_debug) {
        debugPrint('CaptureMemoryManager initialized successfully');
        debugPrint('Max cache size: ${_maxCacheSize / (1024 * 1024)} MB');
      }

    } catch (e) {
      throw Exception('Failed to initialize CaptureMemoryManager: $e');
    }
  }

  /// Preallocate buffers for optimal performance
  Future<void> preallocateBuffers() async {
    if (!_isInitialized || _config == null) {
      throw Exception('CaptureMemoryManager not initialized');
    }

    try {
      if (_debug) {
        debugPrint('Preallocating buffers...');
      }

      // Preallocate buffers for each pool
      for (final pool in _bufferPools.values) {
        final preallocationCount = (pool.maxPoolSize * 0.5).round();
        
        for (int i = 0; i < preallocationCount; i++) {
          final buffer = pool.getBuffer();
          pool.returnBuffer(buffer);
        }
        
        if (_debug) {
          debugPrint('Preallocated ${preallocationCount} buffers for ${pool.name}');
        }
      }

      if (_debug) {
        debugPrint('Buffer preallocation completed');
      }

    } catch (e) {
      throw Exception('Failed to preallocate buffers: $e');
    }
  }

  /// Get cached data by key
  T? getCached<T>(String key) {
    if (!_isInitialized) return null;

    final entry = _cache[key];
    if (entry == null) {
      _cacheMisses++;
      return null;
    }

    // Check if expired
    if (entry.isExpired) {
      _removeEntry(key);
      _cacheMisses++;
      return null;
    }

    // Update access information
    entry.markAccessed();
    _cacheHits++;

    if (_debug) {
      debugPrint('Cache hit: $key (${entry.type.name})');
    }

    return entry.data as T?;
  }

  /// Cache data with optional TTL
  Future<void> cache<T>(String key, T data, {
    Duration? ttl,
    CacheEntryType? type,
    int? estimatedSize,
  }) async {
    if (!_isInitialized) return;

    try {
      // Determine cache entry type
      final entryType = type ?? _inferCacheEntryType<T>(data);
      
      // Calculate size
      final size = estimatedSize ?? _calculateDataSize(data);
      
      // Check if we need to make room
      await _ensureCacheSpace(size);
      
      // Create cache entry
      final entry = CacheEntry<T>(
        key: key,
        data: data,
        type: entryType,
        size: size,
        expiresAt: ttl != null ? DateTime.now().add(ttl) : null,
      );

      // Remove existing entry if present
      if (_cache.containsKey(key)) {
        _removeEntry(key);
      }

      // Add to caches
      _cache[key] = entry;
      _typedCaches[entryType]![key] = entry;
      _currentCacheSize += size;

      if (_debug) {
        debugPrint('Cached: $key (${entryType.name}, ${size} bytes)');
      }

      // Update statistics periodically
      _updateStatistics();

    } catch (e) {
      if (_debug) {
        debugPrint('Failed to cache data: $e');
      }
    }
  }

  /// Clear all cached data
  void clearCache() {
    if (_debug) {
      debugPrint('Clearing all cache data...');
    }

    _cache.clear();
    for (final typeCache in _typedCaches.values) {
      typeCache.clear();
    }
    
    _currentCacheSize = 0;
    _cacheHits = 0;
    _cacheMisses = 0;
    _evictions = 0;

    if (_debug) {
      debugPrint('Cache cleared');
    }
  }

  /// Clear cache by type
  void clearCacheByType(CacheEntryType type) {
    if (_debug) {
      debugPrint('Clearing cache for type: ${type.name}');
    }

    final typeCache = _typedCaches[type];
    if (typeCache != null) {
      for (final key in typeCache.keys.toList()) {
        _removeEntry(key);
      }
    }
  }

  /// Get memory statistics
  MemoryStats get memoryStats {
    final categoryUsage = <String, int>{};
    final accessPatterns = <String, int>{};
    
    for (final type in CacheEntryType.values) {
      final typeCache = _typedCaches[type]!;
      int totalSize = 0;
      int totalAccess = 0;
      
      for (final entry in typeCache.values) {
        totalSize += entry.size;
        totalAccess += entry.accessCount;
      }
      
      categoryUsage[type.name] = totalSize;
      accessPatterns[type.name] = totalAccess;
    }

    final totalRequests = _cacheHits + _cacheMisses;
    final hitRate = totalRequests > 0 ? ((_cacheHits / totalRequests) * 100).round() : 0;
    
    final expiredCount = _cache.values.where((entry) => entry.isExpired).length;
    final avgSize = _cache.isNotEmpty ? _currentCacheSize / _cache.length : 0.0;
    final efficiency = _maxCacheSize > 0 ? (_currentCacheSize / _maxCacheSize) : 0.0;

    return MemoryStats(
      totalMemoryUsed: _currentCacheSize,
      cacheHitRate: hitRate,
      bufferCount: _getTotalBufferCount(),
      categoryUsage: categoryUsage,
      totalEntries: _cache.length,
      expiredEntries: expiredCount,
      averageEntrySize: avgSize,
      accessPatterns: accessPatterns,
      lastCleanup: _lastCleanup,
      memoryEfficiency: efficiency,
    );
  }

  /// Get buffer from pool
  Uint8List? getBuffer(String poolName) {
    final pool = _bufferPools[poolName];
    return pool?.getBuffer();
  }

  /// Return buffer to pool
  void returnBuffer(String poolName, Uint8List buffer) {
    final pool = _bufferPools[poolName];
    pool?.returnBuffer(buffer);
  }

  /// Get buffer pool statistics
  Map<String, dynamic> getBufferPoolStats() {
    final stats = <String, dynamic>{};
    for (final pool in _bufferPools.values) {
      stats[pool.name] = pool.getStats();
    }
    return stats;
  }

  /// Stream of memory statistics
  Stream<MemoryStats> get statisticsStream => _statsController.stream;

  /// Create buffer pools based on configuration
  Future<void> _createBufferPools(ARCaptureConfig config) async {
    // Create pools for different data types
    
    // Camera frame buffer pool
    final frameSize = config.resolution.width * config.resolution.height * 4; // RGBA
    _bufferPools['camera_frames'] = BufferPool(
      name: 'camera_frames',
      bufferSize: frameSize,
      maxPoolSize: 10,
    );

    // Texture buffer pool
    _bufferPools['textures'] = BufferPool(
      name: 'textures',
      bufferSize: frameSize,
      maxPoolSize: 5,
    );

    // Small metadata buffer pool
    _bufferPools['metadata'] = BufferPool(
      name: 'metadata',
      bufferSize: 4096, // 4KB
      maxPoolSize: 20,
    );

    // Large processing buffer pool
    _bufferPools['processing'] = BufferPool(
      name: 'processing',
      bufferSize: frameSize * 2, // Double size for processing
      maxPoolSize: 3,
    );

    if (_debug) {
      debugPrint('Created ${_bufferPools.length} buffer pools');
    }
  }

  /// Infer cache entry type from data
  CacheEntryType _inferCacheEntryType<T>(T data) {
    if (data is Uint8List) {
      return CacheEntryType.texture;
    } else if (data is Map) {
      return CacheEntryType.configuration;
    } else if (data.toString().contains('CameraIntrinsics')) {
      return CacheEntryType.intrinsics;
    } else if (data.toString().contains('CaptureResult')) {
      return CacheEntryType.captureResult;
    } else {
      return CacheEntryType.temporary;
    }
  }

  /// Calculate estimated size of data
  int _calculateDataSize<T>(T data) {
    if (data is Uint8List) {
      return data.length;
    } else if (data is String) {
      return data.length * 2; // Approximate UTF-16 encoding
    } else if (data is Map || data is List) {
      return data.toString().length * 2; // Rough estimate
    } else {
      return 1024; // Default 1KB estimate
    }
  }

  /// Ensure there's enough space in cache for new data
  Future<void> _ensureCacheSpace(int requiredSize) async {
    while (_currentCacheSize + requiredSize > _maxCacheSize && _cache.isNotEmpty) {
      await _evictEntry();
    }
  }

  /// Evict an entry based on the eviction policy
  Future<void> _evictEntry() async {
    String? keyToEvict;

    switch (_evictionPolicy) {
      case EvictionPolicy.lru:
        keyToEvict = _findLRUKey();
        break;
      case EvictionPolicy.lfu:
        keyToEvict = _findLFUKey();
        break;
      case EvictionPolicy.fifo:
        keyToEvict = _findFIFOKey();
        break;
      case EvictionPolicy.ttl:
        keyToEvict = _findExpiredKey();
        break;
      case EvictionPolicy.size:
        keyToEvict = _findLargestKey();
        break;
    }

    if (keyToEvict != null) {
      _removeEntry(keyToEvict);
      _evictions++;
      
      if (_debug) {
        debugPrint('Evicted entry: $keyToEvict');
      }
    }
  }

  /// Find least recently used key
  String? _findLRUKey() {
    if (_cache.isEmpty) return null;
    
    var oldestTime = DateTime.now();
    String? oldestKey;
    
    for (final entry in _cache.entries) {
      if (entry.value.lastAccessed.isBefore(oldestTime)) {
        oldestTime = entry.value.lastAccessed;
        oldestKey = entry.key;
      }
    }
    
    return oldestKey;
  }

  /// Find least frequently used key
  String? _findLFUKey() {
    if (_cache.isEmpty) return null;
    
    int minAccessCount = double.maxFinite.toInt();
    String? leastUsedKey;
    
    for (final entry in _cache.entries) {
      if (entry.value.accessCount < minAccessCount) {
        minAccessCount = entry.value.accessCount;
        leastUsedKey = entry.key;
      }
    }
    
    return leastUsedKey;
  }

  /// Find first in first out key
  String? _findFIFOKey() {
    if (_cache.isEmpty) return null;
    
    var oldestTime = DateTime.now();
    String? oldestKey;
    
    for (final entry in _cache.entries) {
      if (entry.value.createdAt.isBefore(oldestTime)) {
        oldestTime = entry.value.createdAt;
        oldestKey = entry.key;
      }
    }
    
    return oldestKey;
  }

  /// Find expired key
  String? _findExpiredKey() {
    for (final entry in _cache.entries) {
      if (entry.value.isExpired) {
        return entry.key;
      }
    }
    return null;
  }

  /// Find largest entry key
  String? _findLargestKey() {
    if (_cache.isEmpty) return null;
    
    int maxSize = 0;
    String? largestKey;
    
    for (final entry in _cache.entries) {
      if (entry.value.size > maxSize) {
        maxSize = entry.value.size;
        largestKey = entry.key;
      }
    }
    
    return largestKey;
  }

  /// Remove cache entry
  void _removeEntry(String key) {
    final entry = _cache.remove(key);
    if (entry != null) {
      _typedCaches[entry.type]?.remove(key);
      _currentCacheSize -= entry.size;
    }
  }

  /// Get total buffer count across all pools
  int _getTotalBufferCount() {
    int total = 0;
    for (final pool in _bufferPools.values) {
      final stats = pool.getStats();
      total += stats['availableBuffers'] as int;
      total += stats['inUseBuffers'] as int;
    }
    return total;
  }

  /// Start periodic cleanup timer
  void _startPeriodicCleanup() {
    _cleanupTimer = Timer.periodic(_cleanupInterval, (_) {
      _performCleanup();
    });
  }

  /// Perform periodic cleanup
  void _performCleanup() {
    if (_debug) {
      debugPrint('Performing periodic cache cleanup...');
    }

    final keysToRemove = <String>[];
    
    // Remove expired entries
    for (final entry in _cache.entries) {
      if (entry.value.isExpired) {
        keysToRemove.add(entry.key);
      }
    }
    
    for (final key in keysToRemove) {
      _removeEntry(key);
    }
    
    _lastCleanup = DateTime.now();
    
    if (_debug && keysToRemove.isNotEmpty) {
      debugPrint('Cleaned up ${keysToRemove.length} expired entries');
    }

    _updateStatistics();
  }

  /// Update and broadcast statistics
  void _updateStatistics() {
    if (_statsController.hasListener) {
      _statsController.add(memoryStats);
    }
  }

  /// Dispose the memory manager
  Future<void> dispose() async {
    if (!_isInitialized) return;

    try {
      if (_debug) {
        debugPrint('Disposing CaptureMemoryManager...');
      }

      // Stop cleanup timer
      _cleanupTimer?.cancel();

      // Clear all caches
      clearCache();

      // Clear buffer pools
      for (final pool in _bufferPools.values) {
        pool.clear();
      }
      _bufferPools.clear();

      // Close statistics stream
      await _statsController.close();

      _isInitialized = false;
      _instance = null;

      if (_debug) {
        debugPrint('CaptureMemoryManager disposed successfully');
      }

    } catch (e) {
      if (_debug) {
        debugPrint('Error disposing CaptureMemoryManager: $e');
      }
    }
  }
}