import 'dart:typed_data';

const String visibilityGridWireVersion = 'visibility_grid_wire_v1';

enum ARVisibilityGridDepthCapability {
  unsupported,
  rawDepthOnly,
  automatic,
  sceneDepth,
}

enum ARVisibilityGridDepthMode {
  disabled,
  featureOnly,
  rawDepthOnly,
  automatic,
  sceneDepth,
}

enum ARVisibilityGridSourceState {
  unsupported,
  configured,
  healthy,
  transientUnavailable,
  failed,
  featureOnly,
  disabled,
}

enum ARVisibilityGridErrorCode {
  versionMismatch('VG_VERSION_MISMATCH'),
  protocolInvalid('VG_PROTOCOL_INVALID'),
  groupMismatch('VG_GROUP_MISMATCH'),
  sessionMismatch('VG_SESSION_MISMATCH'),
  geometryRevisionGap('VG_GEOMETRY_REVISION_GAP'),
  visibilityRevisionStale('VG_VISIBILITY_REVISION_STALE'),
  capacityReached('VG_CAPACITY_REACHED'),
  featureUnsupported('VG_FEATURE_UNSUPPORTED'),
  featureFailed('VG_FEATURE_FAILED'),
  depthUnsupported('VG_DEPTH_UNSUPPORTED'),
  depthFailed('VG_DEPTH_FAILED'),
  rendererFailed('VG_RENDERER_FAILED'),
  gridFailed('VG_GRID_FAILED'),
  notInitialized('VG_NOT_INITIALIZED'),
  syntheticForbidden('VG_SYNTHETIC_FORBIDDEN'),
  internal('VG_INTERNAL');

  const ARVisibilityGridErrorCode(this.wireName);
  final String wireName;
}

class ARVisibilityGridNativeConfig {
  const ARVisibilityGridNativeConfig({
    this.renderCapacity = 100000,
    this.featureTrackCapacity = 200000,
    this.maxFeaturesPerObservation = 2000,
    this.maxDepthPixelsPerObservation = 4096,
    this.maxRayVisitsPerObservation = 65536,
    this.publishIntervalMs = 500,
    this.featureConfidenceMinimum = 0.3,
    this.depthConfidenceMinimum = 128,
    this.syntheticSource = false,
  })  : assert(renderCapacity > 0 && renderCapacity <= 100000),
        assert(featureTrackCapacity > 0 && featureTrackCapacity <= 200000),
        assert(maxFeaturesPerObservation > 0),
        assert(maxDepthPixelsPerObservation > 0),
        assert(maxRayVisitsPerObservation > 0),
        assert(publishIntervalMs >= 500),
        assert(
          featureConfidenceMinimum >= 0 && featureConfidenceMinimum <= 1,
        ),
        assert(depthConfidenceMinimum >= 0 && depthConfidenceMinimum <= 255);

  final int renderCapacity;
  final int featureTrackCapacity;
  final int maxFeaturesPerObservation;
  final int maxDepthPixelsPerObservation;
  final int maxRayVisitsPerObservation;
  final int publishIntervalMs;
  final double featureConfidenceMinimum;
  final int depthConfidenceMinimum;
  final bool syntheticSource;

  Map<String, Object> toMap() => <String, Object>{
        'version': visibilityGridWireVersion,
        'renderCapacity': renderCapacity,
        'featureTrackCapacity': featureTrackCapacity,
        'maxFeaturesPerObservation': maxFeaturesPerObservation,
        'maxDepthPixelsPerObservation': maxDepthPixelsPerObservation,
        'maxRayVisitsPerObservation': maxRayVisitsPerObservation,
        'publishIntervalMs': publishIntervalMs,
        'featureConfidenceMinimum': featureConfidenceMinimum,
        'depthConfidenceMinimum': depthConfidenceMinimum,
        'syntheticSource': syntheticSource,
      };
}

class ARVisibilityGridInitializationResult {
  const ARVisibilityGridInitializationResult({
    required this.sessionGeneration,
    required this.rendererReady,
    required this.featureReady,
    required this.depthCapability,
    required this.depthConfigured,
    required this.depthActiveMode,
    required this.renderCapacity,
    required this.featureTrackCapacity,
    required this.health,
  });

  factory ARVisibilityGridInitializationResult.fromMap(
    Map<Object?, Object?> map,
  ) {
    if (map['version'] != visibilityGridWireVersion) {
      throw const FormatException('Unsupported visibility-grid wire version.');
    }
    final sessionGeneration = map['sessionGeneration'];
    final rendererReady = map['rendererReady'];
    final featureReady = map['featureReady'];
    final depthCapability = _enumByName(
      ARVisibilityGridDepthCapability.values,
      map['depthCapability'],
      'depthCapability',
    );
    final depthConfigured = map['depthConfigured'];
    final depthActiveMode = _enumByName(
      ARVisibilityGridDepthMode.values,
      map['depthActiveMode'],
      'depthActiveMode',
    );
    final renderCapacity = map['renderCapacity'];
    final featureTrackCapacity = map['featureTrackCapacity'];
    final health = map['health'];
    if (sessionGeneration is! int ||
        sessionGeneration < 0 ||
        rendererReady is! bool ||
        featureReady is! bool ||
        depthConfigured is! bool ||
        renderCapacity is! int ||
        renderCapacity <= 0 ||
        featureTrackCapacity is! int ||
        featureTrackCapacity <= 0 ||
        health is! Map<Object?, Object?>) {
      throw const FormatException(
        'Invalid visibility-grid initialization result.',
      );
    }
    return ARVisibilityGridInitializationResult(
      sessionGeneration: sessionGeneration,
      rendererReady: rendererReady,
      featureReady: featureReady,
      depthCapability: depthCapability,
      depthConfigured: depthConfigured,
      depthActiveMode: depthActiveMode,
      renderCapacity: renderCapacity,
      featureTrackCapacity: featureTrackCapacity,
      health: ARVisibilityGridSourceHealth.fromMap(health),
    );
  }

  final int sessionGeneration;
  final bool rendererReady;
  final bool featureReady;
  final ARVisibilityGridDepthCapability depthCapability;
  final bool depthConfigured;
  final ARVisibilityGridDepthMode depthActiveMode;
  final int renderCapacity;
  final int featureTrackCapacity;
  final ARVisibilityGridSourceHealth health;
}

class ARVisibilityGridError {
  const ARVisibilityGridError({
    required this.code,
    required this.message,
    required this.recoverable,
    required this.fatalToFeature,
    required this.fatalToDepth,
    required this.fatalToRenderer,
    required this.fatalToGrid,
    this.groupGeneration,
    this.sessionGeneration,
  });

  factory ARVisibilityGridError.fromMap(Map<Object?, Object?> map) {
    final wireCode = map['code'];
    final code = ARVisibilityGridErrorCode.values
        .where((candidate) => candidate.wireName == wireCode)
        .firstOrNull;
    final message = map['message'];
    final recoverable = map['recoverable'];
    final fatalToFeature = map['fatalToFeature'];
    final fatalToDepth = map['fatalToDepth'];
    final fatalToRenderer = map['fatalToRenderer'];
    final fatalToGrid = map['fatalToGrid'];
    final groupGeneration = map['groupGeneration'];
    final sessionGeneration = map['sessionGeneration'];
    if (code == null ||
        message is! String ||
        recoverable is! bool ||
        fatalToFeature is! bool ||
        fatalToDepth is! bool ||
        fatalToRenderer is! bool ||
        fatalToGrid is! bool ||
        (groupGeneration != null &&
            (groupGeneration is! int || groupGeneration < 0)) ||
        (sessionGeneration != null &&
            (sessionGeneration is! int || sessionGeneration < 0))) {
      throw const FormatException('Invalid visibility-grid error.');
    }
    return ARVisibilityGridError(
      code: code,
      message: message,
      recoverable: recoverable,
      fatalToFeature: fatalToFeature,
      fatalToDepth: fatalToDepth,
      fatalToRenderer: fatalToRenderer,
      fatalToGrid: fatalToGrid,
      groupGeneration: groupGeneration as int?,
      sessionGeneration: sessionGeneration as int?,
    );
  }

  final ARVisibilityGridErrorCode code;
  final String message;
  final bool recoverable;
  final bool fatalToFeature;
  final bool fatalToDepth;
  final bool fatalToRenderer;
  final bool fatalToGrid;
  final int? groupGeneration;
  final int? sessionGeneration;
}

class ARVisibilityGridGroupConfig {
  ARVisibilityGridGroupConfig({
    required this.groupId,
    required this.groupGeneration,
    required this.voxelSizeMeters,
    required this.capacity,
    required Float64List worldFromGroupGl,
    required Float64List groupFromWorldGl,
    this.restoredGeometryRevision = 0,
    Int64List? restoredKeys,
  })  : worldFromGroupGl = Float64List.fromList(worldFromGroupGl),
        groupFromWorldGl = Float64List.fromList(groupFromWorldGl),
        restoredKeys = Int64List.fromList(restoredKeys ?? Int64List(0)) {
    if (groupId.isEmpty ||
        groupGeneration < 0 ||
        !voxelSizeMeters.isFinite ||
        voxelSizeMeters <= 0 ||
        capacity <= 0 ||
        capacity > 100000 ||
        worldFromGroupGl.length != 16 ||
        groupFromWorldGl.length != 16 ||
        worldFromGroupGl.any((value) => !value.isFinite) ||
        groupFromWorldGl.any((value) => !value.isFinite) ||
        restoredGeometryRevision < 0 ||
        this.restoredKeys.length > capacity ||
        _hasDuplicates(this.restoredKeys) ||
        (this.restoredKeys.isNotEmpty && restoredGeometryRevision == 0)) {
      throw ArgumentError('Invalid visibility-grid group configuration.');
    }
    if (!_areMutualInverses(worldFromGroupGl, groupFromWorldGl)) {
      throw ArgumentError(
        'Visibility-grid group transforms must be mutual inverses.',
      );
    }
  }

  final String groupId;
  final int groupGeneration;
  final double voxelSizeMeters;
  final int capacity;
  final Float64List worldFromGroupGl;
  final Float64List groupFromWorldGl;
  final int restoredGeometryRevision;
  final Int64List restoredKeys;

  Map<String, Object> toMap() => <String, Object>{
        'version': visibilityGridWireVersion,
        'groupId': groupId,
        'groupGeneration': groupGeneration,
        'voxelSizeMeters': voxelSizeMeters,
        'capacity': capacity,
        'groupFrameConvention': 'gravity_y_up_meters_v1',
        'matrixConvention': 'column_major_gl_v1',
        'worldFromGroupGl': Float64List.fromList(worldFromGroupGl),
        'groupFromWorldGl': Float64List.fromList(groupFromWorldGl),
        'restoredGeometryRevision': restoredGeometryRevision,
        'restoredKeys': Int64List.fromList(restoredKeys),
      };
}

bool _areMutualInverses(Float64List first, Float64List second) {
  const tolerance = 1e-6;

  bool productIsIdentity(Float64List left, Float64List right) {
    for (var column = 0; column < 4; column++) {
      for (var row = 0; row < 4; row++) {
        var value = 0.0;
        for (var index = 0; index < 4; index++) {
          value += left[index * 4 + row] * right[column * 4 + index];
        }
        final expected = row == column ? 1.0 : 0.0;
        if ((value - expected).abs() > tolerance) return false;
      }
    }
    return true;
  }

  return productIsIdentity(first, second) && productIsIdentity(second, first);
}

T _enumByName<T extends Enum>(
  List<T> values,
  Object? wireValue,
  String field,
) {
  for (final value in values) {
    if (value.name == wireValue) return value;
  }
  throw FormatException('Invalid visibility-grid value for $field.');
}

class ARVisibilityGridVisibilityPatch {
  ARVisibilityGridVisibilityPatch({
    required this.groupId,
    required this.groupGeneration,
    required this.sessionGeneration,
    required this.geometryRevision,
    required this.visibilityRevision,
    required Int64List keys,
    required Int32List colors,
  })  : keys = Int64List.fromList(keys),
        colors = Int32List.fromList(colors) {
    if (groupId.isEmpty ||
        groupGeneration < 0 ||
        sessionGeneration < 0 ||
        geometryRevision < 0 ||
        visibilityRevision <= 0 ||
        keys.length != colors.length ||
        _hasDuplicates(keys)) {
      throw ArgumentError('Invalid visibility-grid visibility patch.');
    }
  }

  final String groupId;
  final int groupGeneration;
  final int sessionGeneration;
  final int geometryRevision;
  final int visibilityRevision;
  final Int64List keys;
  final Int32List colors;

  Map<String, Object> toMap() => <String, Object>{
        'version': visibilityGridWireVersion,
        'groupId': groupId,
        'groupGeneration': groupGeneration,
        'sessionGeneration': sessionGeneration,
        'geometryRevision': geometryRevision,
        'visibilityRevision': visibilityRevision,
        'keys': Int64List.fromList(keys),
        'colors': Int32List.fromList(colors),
      };
}

class ARVisibilityGridSourceHealth {
  const ARVisibilityGridSourceHealth({
    required this.feature,
    required this.depth,
    required this.renderer,
    required this.totalGrid,
  });

  factory ARVisibilityGridSourceHealth.fromMap(Map<Object?, Object?> map) {
    ARVisibilityGridSourceState read(String field) {
      final value = map[field];
      return ARVisibilityGridSourceState.values.firstWhere(
        (state) => state.name == value,
        orElse: () => throw FormatException(
          'Invalid visibility-grid source state for $field.',
        ),
      );
    }

    return ARVisibilityGridSourceHealth(
      feature: read('feature'),
      depth: read('depth'),
      renderer: read('renderer'),
      totalGrid: read('totalGrid'),
    );
  }

  final ARVisibilityGridSourceState feature;
  final ARVisibilityGridSourceState depth;
  final ARVisibilityGridSourceState renderer;
  final ARVisibilityGridSourceState totalGrid;
}

class ARVisibilityGridDelta {
  ARVisibilityGridDelta({
    required this.groupId,
    required this.groupGeneration,
    required this.sessionGeneration,
    required this.baseGeometryRevision,
    required this.geometryRevision,
    required this.reset,
    required Int64List upsertKeys,
    required Int64List removalKeys,
    required this.capacity,
    required this.sourceHealth,
  })  : upsertKeys = Int64List.fromList(upsertKeys),
        removalKeys = Int64List.fromList(removalKeys);

  factory ARVisibilityGridDelta.fromMap(Map<Object?, Object?> map) {
    if (map['version'] != visibilityGridWireVersion) {
      throw const FormatException('Unsupported visibility-grid wire version.');
    }
    final groupId = map['groupId'];
    final groupGeneration = map['groupGeneration'];
    final sessionGeneration = map['sessionGeneration'];
    final baseGeometryRevision = map['baseGeometryRevision'];
    final geometryRevision = map['geometryRevision'];
    final reset = map['reset'];
    final upsertKeys = map['upsertKeys'];
    final removalKeys = map['removalKeys'];
    final capacity = map['capacity'];
    final sourceHealth = map['sourceHealth'];
    if (groupId is! String ||
        groupId.isEmpty ||
        groupGeneration is! int ||
        groupGeneration < 0 ||
        sessionGeneration is! int ||
        sessionGeneration < 0 ||
        baseGeometryRevision is! int ||
        baseGeometryRevision < 0 ||
        geometryRevision is! int ||
        geometryRevision <= baseGeometryRevision ||
        reset is! bool ||
        upsertKeys is! Int64List ||
        removalKeys is! Int64List ||
        capacity is! int ||
        capacity <= 0 ||
        sourceHealth is! Map<Object?, Object?>) {
      throw const FormatException('Invalid visibility-grid geometry delta.');
    }
    if (!reset && geometryRevision != baseGeometryRevision + 1) {
      throw const FormatException(
          'Non-reset geometry revisions must be adjacent.');
    }
    final removals = removalKeys.toSet();
    if (upsertKeys.length != upsertKeys.toSet().length ||
        removalKeys.length != removals.length ||
        upsertKeys.any(removals.contains)) {
      throw const FormatException(
        'A visibility-grid key cannot be upserted and removed together.',
      );
    }
    return ARVisibilityGridDelta(
      groupId: groupId,
      groupGeneration: groupGeneration,
      sessionGeneration: sessionGeneration,
      baseGeometryRevision: baseGeometryRevision,
      geometryRevision: geometryRevision,
      reset: reset,
      upsertKeys: upsertKeys,
      removalKeys: removalKeys,
      capacity: capacity,
      sourceHealth: ARVisibilityGridSourceHealth.fromMap(sourceHealth),
    );
  }

  final String groupId;
  final int groupGeneration;
  final int sessionGeneration;
  final int baseGeometryRevision;
  final int geometryRevision;
  final bool reset;
  final Int64List upsertKeys;
  final Int64List removalKeys;
  final int capacity;
  final ARVisibilityGridSourceHealth sourceHealth;
}

bool _hasDuplicates(Iterable<int> values) {
  final unique = <int>{};
  for (final value in values) {
    if (!unique.add(value)) return true;
  }
  return false;
}
