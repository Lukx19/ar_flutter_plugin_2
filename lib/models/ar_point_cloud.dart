import 'dart:typed_data';

const String pointCloudWireVersion = 'pointcloud_wire_v4';

class ARPointCloudFrame {
  ARPointCloudFrame({
    required this.sequence,
    required this.timestampNs,
    required Int32List ids,
    required Float32List points,
  })  : ids = Int32List.fromList(ids),
        points = Float32List.fromList(points) {
    _validateFrame(sequence, timestampNs, this.ids, this.points);
  }

  /// The platform callback owns these freshly decoded typed lists.
  ARPointCloudFrame.owned({
    required this.sequence,
    required this.timestampNs,
    required this.ids,
    required this.points,
  }) {
    _validateFrame(sequence, timestampNs, ids, points);
  }

  static void _validateFrame(
    int sequence,
    int timestampNs,
    Int32List ids,
    Float32List points,
  ) {
    if (sequence < 0 || timestampNs < 0 || points.length != ids.length * 4) {
      throw const FormatException('Invalid pointcloud_wire_v4 frame.');
    }
  }

  final int sequence;
  final int timestampNs;
  final Int32List ids;
  final Float32List points;
  int get count => ids.length;
}

class ARVoxelRenderPatch {
  ARVoxelRenderPatch({
    required this.epoch,
    required Int64List keys,
    required Float32List positionsWorld,
    required Int32List colors,
  })  : keys = Int64List.fromList(keys),
        positionsWorld = Float32List.fromList(positionsWorld),
        colors = Int32List.fromList(colors) {
    _validatePatch(epoch, this.keys, this.positionsWorld, this.colors);
  }

  ARVoxelRenderPatch.owned({
    required this.epoch,
    required this.keys,
    required this.positionsWorld,
    required this.colors,
  }) {
    _validatePatch(epoch, keys, positionsWorld, colors);
  }

  static void _validatePatch(
    int epoch,
    Int64List keys,
    Float32List positionsWorld,
    Int32List colors,
  ) {
    if (epoch < 0 ||
        keys.length != colors.length ||
        positionsWorld.length != keys.length * 3) {
      throw ArgumentError('Invalid voxel render patch.');
    }
  }

  final int epoch;
  final Int64List keys;
  final Float32List positionsWorld;
  final Int32List colors;

  Map<String, Object> toMap() => <String, Object>{
        'epoch': epoch,
        'keys': keys,
        'positionsWorld': positionsWorld,
        'colors': colors,
      };
}

class ARPointCloudNativeConfig {
  const ARPointCloudNativeConfig({
    this.wireVersion = pointCloudWireVersion,
    this.renderCapacity = 100000,
    this.defaultColor = 0xFFFF0000,
    this.pointSizePx = 6,
    this.frameRateHz = 10,
    this.maxConsecutiveAcquisitionErrors = 3,
    this.minConfidence = 0.3,
    this.enabled = true,
    this.syntheticSource = false,
    this.voxelRenderMode = 'points',
    this.voxelSizeMeters = 0.1,
  });

  final String wireVersion;
  final int renderCapacity;
  final int defaultColor;
  final double pointSizePx;
  final int frameRateHz;
  final int maxConsecutiveAcquisitionErrors;
  final double minConfidence;
  final bool enabled;
  final bool syntheticSource;
  final String voxelRenderMode;
  final double voxelSizeMeters;

  Map<String, Object> toMap() => <String, Object>{
        'version': wireVersion,
        'renderCapacity': renderCapacity,
        'defaultColor': defaultColor,
        'pointSizePx': pointSizePx,
        'frameRateHz': frameRateHz,
        'maxConsecutiveAcquisitionErrors': maxConsecutiveAcquisitionErrors,
        'minConfidence': minConfidence,
        'enabled': enabled,
        'syntheticSource': syntheticSource,
        'voxelRenderMode': voxelRenderMode,
        'voxelSizeMeters': voxelSizeMeters,
      };
}

class ARPointCloudInitializationResult {
  const ARPointCloudInitializationResult({
    required this.rendererReady,
    required this.acquisitionReady,
  });
  final bool rendererReady;
  final bool acquisitionReady;
}

class ARPointCloudRenderingStats {
  const ARPointCloudRenderingStats({
    this.fps = 0,
    this.livePointCount = 0,
    this.bufferBytes = 0,
    this.emittedFrames = 0,
    this.coalescedFrames = 0,
    this.lastAppliedColorEpoch = 0,
    this.arFrameCallbackFps = 0,
    this.fixedStateArrayBytes = 0,
    this.keyIndexEntries = 0,
    this.rendererDesiredBytes = 0,
    this.uploadStagingBytes = 0,
    this.gpuVertexBytes = 0,
    this.gpuIndexBytes = 0,
    this.uploadInFlight = false,
    this.pendingDirtyRows = 0,
    this.droppedVoxelRows = 0,
    this.unchangedVoxelRows = 0,
    this.fullResyncUploads = 0,
    this.partialUploads = 0,
    this.uploadedBytes = 0,
    this.coalescedRendererUpdates = 0,
    this.frameCallbackInFlight = 0,
    this.frameCallbackPending = 0,
    this.rendererMounted = false,
    this.trackingFrameCallbacks = 0,
    this.nonTrackingFrameCallbacks = 0,
    this.acquisitionAttempts = 0,
    this.emptyAcquisitions = 0,
    this.acquisitionErrors = 0,
    this.rawPointCloudIds = 0,
    this.rawPointCloudFloats = 0,
    this.acceptedSourcePoints = 0,
    this.confidenceRejectedPoints = 0,
    this.nonFiniteRejectedPoints = 0,
    this.invalidPointCloudAcquisitions = 0,
    this.unchangedPointCloudTimestamps = 0,
    this.lastPointCloudTimestampNs = 0,
  });

  factory ARPointCloudRenderingStats.fromMap(Map<Object?, Object?> map) =>
      ARPointCloudRenderingStats(
        fps: _optionalFiniteNonNegative(map, 'fps') ??
            _optionalFiniteNonNegative(map, 'arFrameCallbackFps') ??
            0,
        livePointCount: _optionalNonNegativeInt(map, 'livePointCount') ?? 0,
        bufferBytes: _optionalNonNegativeInt(map, 'bufferBytes') ?? 0,
        emittedFrames: _optionalNonNegativeInt(map, 'emittedFrames') ?? 0,
        coalescedFrames: _optionalNonNegativeInt(map, 'coalescedFrames') ?? 0,
        lastAppliedColorEpoch:
            _optionalNonNegativeInt(map, 'lastAppliedColorEpoch') ?? 0,
        arFrameCallbackFps:
            _optionalFiniteNonNegative(map, 'arFrameCallbackFps') ?? 0,
        fixedStateArrayBytes:
            _optionalNonNegativeInt(map, 'fixedStateArrayBytes') ?? 0,
        keyIndexEntries: _optionalNonNegativeInt(map, 'keyIndexEntries') ?? 0,
        rendererDesiredBytes:
            _optionalNonNegativeInt(map, 'rendererDesiredBytes') ?? 0,
        uploadStagingBytes:
            _optionalNonNegativeInt(map, 'uploadStagingBytes') ?? 0,
        gpuVertexBytes: _optionalNonNegativeInt(map, 'gpuVertexBytes') ?? 0,
        gpuIndexBytes: _optionalNonNegativeInt(map, 'gpuIndexBytes') ?? 0,
        uploadInFlight: _optionalBool(map, 'uploadInFlight') ?? false,
        pendingDirtyRows: _optionalNonNegativeInt(map, 'pendingDirtyRows') ?? 0,
        droppedVoxelRows: _optionalNonNegativeInt(map, 'droppedVoxelRows') ?? 0,
        unchangedVoxelRows:
            _optionalNonNegativeInt(map, 'unchangedVoxelRows') ?? 0,
        fullResyncUploads:
            _optionalNonNegativeInt(map, 'fullResyncUploads') ?? 0,
        partialUploads: _optionalNonNegativeInt(map, 'partialUploads') ?? 0,
        uploadedBytes: _optionalNonNegativeInt(map, 'uploadedBytes') ?? 0,
        coalescedRendererUpdates:
            _optionalNonNegativeInt(map, 'coalescedRendererUpdates') ?? 0,
        frameCallbackInFlight:
            _optionalNonNegativeInt(map, 'frameCallbackInFlight') ?? 0,
        frameCallbackPending:
            _optionalNonNegativeInt(map, 'frameCallbackPending') ?? 0,
        rendererMounted: _optionalBool(map, 'rendererMounted') ?? false,
        trackingFrameCallbacks:
            _optionalNonNegativeInt(map, 'trackingFrameCallbacks') ?? 0,
        nonTrackingFrameCallbacks:
            _optionalNonNegativeInt(map, 'nonTrackingFrameCallbacks') ?? 0,
        acquisitionAttempts:
            _optionalNonNegativeInt(map, 'acquisitionAttempts') ?? 0,
        emptyAcquisitions:
            _optionalNonNegativeInt(map, 'emptyAcquisitions') ?? 0,
        acquisitionErrors:
            _optionalNonNegativeInt(map, 'acquisitionErrors') ?? 0,
        rawPointCloudIds: _optionalNonNegativeInt(map, 'rawPointCloudIds') ?? 0,
        rawPointCloudFloats:
            _optionalNonNegativeInt(map, 'rawPointCloudFloats') ?? 0,
        acceptedSourcePoints:
            _optionalNonNegativeInt(map, 'acceptedSourcePoints') ?? 0,
        confidenceRejectedPoints:
            _optionalNonNegativeInt(map, 'confidenceRejectedPoints') ?? 0,
        nonFiniteRejectedPoints:
            _optionalNonNegativeInt(map, 'nonFiniteRejectedPoints') ?? 0,
        invalidPointCloudAcquisitions:
            _optionalNonNegativeInt(map, 'invalidPointCloudAcquisitions') ?? 0,
        unchangedPointCloudTimestamps:
            _optionalNonNegativeInt(map, 'unchangedPointCloudTimestamps') ?? 0,
        lastPointCloudTimestampNs:
            _optionalNonNegativeInt(map, 'lastPointCloudTimestampNs') ?? 0,
      );

  final double fps;
  final int livePointCount;
  final int bufferBytes;
  final int emittedFrames;
  final int coalescedFrames;
  final int lastAppliedColorEpoch;
  final double arFrameCallbackFps;
  final int fixedStateArrayBytes;
  final int keyIndexEntries;
  final int rendererDesiredBytes;
  final int uploadStagingBytes;
  final int gpuVertexBytes;
  final int gpuIndexBytes;
  final bool uploadInFlight;
  final int pendingDirtyRows;
  final int droppedVoxelRows;
  final int unchangedVoxelRows;
  final int fullResyncUploads;
  final int partialUploads;
  final int uploadedBytes;
  final int coalescedRendererUpdates;
  final int frameCallbackInFlight;
  final int frameCallbackPending;
  final bool rendererMounted;
  final int trackingFrameCallbacks;
  final int nonTrackingFrameCallbacks;
  final int acquisitionAttempts;
  final int emptyAcquisitions;
  final int acquisitionErrors;
  final int rawPointCloudIds;
  final int rawPointCloudFloats;
  final int acceptedSourcePoints;
  final int confidenceRejectedPoints;
  final int nonFiniteRejectedPoints;
  final int invalidPointCloudAcquisitions;
  final int unchangedPointCloudTimestamps;
  final int lastPointCloudTimestampNs;
}

class ARPointCloudError {
  const ARPointCloudError({
    required this.code,
    required this.message,
    required this.fatalToAcquisition,
    required this.fatalToRenderer,
  });
  final String code;
  final String message;
  final bool fatalToAcquisition;
  final bool fatalToRenderer;
}

double? _optionalFiniteNonNegative(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value == null) return null;
  if (value is! num || !value.isFinite || value < 0) {
    throw FormatException('Expected finite non-negative numeric $key.');
  }
  return value.toDouble();
}

int? _optionalNonNegativeInt(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value == null) return null;
  if (value is! int || value < 0) {
    throw FormatException('Expected non-negative integer $key.');
  }
  return value;
}

bool? _optionalBool(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value == null) return null;
  if (value is! bool) throw FormatException('Expected bool $key.');
  return value;
}
