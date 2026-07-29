import 'dart:typed_data';

/// Exact native/Dart protocol identifier for cleaned visibility-grid traffic.
const String visibilityGridWireVersion = 'visibility_grid_wire_v1';

/// Depth acquisition capabilities advertised by the native runtime.
enum ARVisibilityGridDepthCapability {
  unsupported,
  rawDepthOnly,
  automatic,
  sceneDepth,
}

/// Depth source selected for the active native grid.
enum ARVisibilityGridDepthMode {
  disabled,
  featureOnly,
  rawDepthOnly,
  automatic,
  sceneDepth,
}

/// Health states shared by feature, depth, renderer, and total-grid sources.
enum ARVisibilityGridSourceState {
  unsupported,
  configured,
  healthy,
  transientUnavailable,
  failed,
  featureOnly,
  disabled,
}

/// Stable machine-readable visibility-grid error categories.
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

  /// Native wire value used in structured error payloads.
  final String wireName;
}

/// Bounded configuration negotiated before a capture group is started.
///
/// Construction throws [ArgumentError] when any resource or confidence bound
/// is outside the v1 contract.
class ARVisibilityGridNativeConfig {
  ARVisibilityGridNativeConfig({
    this.renderCapacity = 100000,
    this.featureTrackCapacity = 200000,
    this.maxFeaturesPerObservation = 2000,
    this.maxDepthPixelsPerObservation = 4096,
    this.maxRayVisitsPerObservation = 65536,
    this.publishIntervalMs = 500,
    this.featureConfidenceMinimum = 0.3,
    this.depthConfidenceMinimum = 128,
    this.syntheticSource = false,
  }) {
    if (renderCapacity <= 0 ||
        renderCapacity > 100000 ||
        featureTrackCapacity <= 0 ||
        featureTrackCapacity > 200000 ||
        maxFeaturesPerObservation <= 0 ||
        maxDepthPixelsPerObservation <= 0 ||
        maxRayVisitsPerObservation <= 0 ||
        publishIntervalMs < 500 ||
        !featureConfidenceMinimum.isFinite ||
        featureConfidenceMinimum < 0 ||
        featureConfidenceMinimum > 1 ||
        depthConfidenceMinimum < 0 ||
        depthConfidenceMinimum > 255) {
      throw ArgumentError('Invalid visibility-grid native configuration.');
    }
  }

  /// Maximum stable voxels and renderer rows.
  final int renderCapacity;

  /// Maximum native persistent feature tracks.
  final int featureTrackCapacity;

  /// Maximum copied feature samples per observation.
  final int maxFeaturesPerObservation;

  /// Maximum accepted depth pixels per observation.
  final int maxDepthPixelsPerObservation;

  /// Maximum depth-carving voxel visits per observation.
  final int maxRayVisitsPerObservation;

  /// Minimum interval between geometry publications.
  final int publishIntervalMs;

  /// Minimum accepted AR feature confidence.
  final double featureConfidenceMinimum;

  /// Minimum accepted raw-depth confidence byte.
  final int depthConfidenceMinimum;

  /// Whether debug-only deterministic input is requested.
  final bool syntheticSource;

  /// Serializes the immutable v1 initialization payload.
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

/// Validated capabilities and resource bounds returned by native init.
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

  /// Parses a native result, throwing [FormatException] on contract drift.
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

  /// Monotonic native session identity.
  final int sessionGeneration;

  /// Whether the native overlay can currently accept colors.
  final bool rendererReady;

  /// Whether feature acquisition is supported.
  final bool featureReady;

  /// Best available depth capability.
  final ARVisibilityGridDepthCapability depthCapability;

  /// Whether a depth adapter was configured.
  final bool depthConfigured;

  /// Depth mode selected for this session.
  final ARVisibilityGridDepthMode depthActiveMode;

  /// Accepted stable voxel capacity.
  final int renderCapacity;

  /// Accepted native feature-track capacity.
  final int featureTrackCapacity;

  /// Initial component health.
  final ARVisibilityGridSourceHealth health;
}

/// Typed native visibility-grid failure.
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

  /// Parses a native error, throwing [FormatException] when malformed.
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

  /// Stable error category.
  final ARVisibilityGridErrorCode code;

  /// Human-readable diagnostic.
  final String message;

  /// Whether retrying within this generation is valid.
  final bool recoverable;

  /// Whether feature fusion must stop.
  final bool fatalToFeature;

  /// Whether depth fusion must stop.
  final bool fatalToDepth;

  /// Whether visualization must stop.
  final bool fatalToRenderer;

  /// Whether the complete grid must stop.
  final bool fatalToGrid;

  /// Affected group generation, when known.
  final int? groupGeneration;

  /// Affected session generation, when known.
  final int? sessionGeneration;
}

/// Group identity, transforms, and restored geometry sent to native start.
///
/// Construction throws [ArgumentError] for invalid identity, bounds, keys, or
/// non-inverse transforms.
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

  /// Durable capture-group identifier.
  final String groupId;

  /// Monotonic runtime generation of that group.
  final int groupGeneration;

  /// Native/Dart shared voxel edge length.
  final double voxelSizeMeters;

  /// Maximum stable keys for the group.
  final int capacity;

  /// Column-major group-to-world transform.
  final Float64List worldFromGroupGl;

  /// Column-major world-to-group inverse transform.
  final Float64List groupFromWorldGl;

  /// Geometry revision represented by [restoredKeys].
  final int restoredGeometryRevision;

  /// Stable keys restored before live acquisition.
  final Int64List restoredKeys;

  /// Serializes the immutable v1 start payload.
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

/// Color-only update for keys in an acknowledged geometry revision.
///
/// Construction throws [ArgumentError] for invalid revisions, lengths, or
/// duplicate keys.
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

  /// Durable capture-group identifier.
  final String groupId;

  /// Exact active group generation.
  final int groupGeneration;

  /// Exact active session generation.
  final int sessionGeneration;

  /// Acknowledged geometry revision being colored.
  final int geometryRevision;

  /// Independent increasing color revision.
  final int visibilityRevision;

  /// Existing stable voxel keys.
  final Int64List keys;

  /// Packed colors parallel to [keys].
  final Int32List colors;

  /// Serializes the immutable v1 color payload.
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

/// Current health of each native visibility-grid component.
class ARVisibilityGridSourceHealth {
  const ARVisibilityGridSourceHealth({
    required this.feature,
    required this.depth,
    required this.renderer,
    required this.totalGrid,
  });

  /// Parses health, throwing [FormatException] for an unknown state.
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

  /// Feature fusion health.
  final ARVisibilityGridSourceState feature;

  /// Depth fusion health.
  final ARVisibilityGridSourceState depth;

  /// Renderer health.
  final ARVisibilityGridSourceState renderer;

  /// Overall acquisition health.
  final ARVisibilityGridSourceState totalGrid;
}

/// One bounded revisioned geometry change or full reset snapshot.
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

  /// Parses and validates a native delta.
  ///
  /// Throws [FormatException] for stale protocol shape, non-adjacent
  /// revisions, duplicate/overlapping keys, or capacity violations.
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
        removalKeys.length != removals.length) {
      throw const FormatException(
        'Visibility-grid delta keys must be unique.',
      );
    }
    if (upsertKeys.any(removals.contains)) {
      throw const FormatException(
        'A visibility-grid key cannot be upserted and removed together.',
      );
    }
    if (upsertKeys.length + removalKeys.length > capacity) {
      throw const FormatException(
        'Visibility-grid delta exceeds its negotiated capacity.',
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

  /// Durable capture-group identifier.
  final String groupId;

  /// Exact active group generation.
  final int groupGeneration;

  /// Exact active session generation.
  final int sessionGeneration;

  /// Receiver revision this delta builds on.
  final int baseGeometryRevision;

  /// Resulting geometry revision.
  final int geometryRevision;

  /// Whether [upsertKeys] is a complete replacement snapshot.
  final bool reset;

  /// Stable keys inserted or refreshed.
  final Int64List upsertKeys;

  /// Stable keys removed from the mirror.
  final Int64List removalKeys;

  /// Negotiated maximum change-set size.
  final int capacity;

  /// Component health sampled with this geometry revision.
  final ARVisibilityGridSourceHealth sourceHealth;
}

bool _hasDuplicates(Iterable<int> values) {
  final unique = <int>{};
  for (final value in values) {
    if (!unique.add(value)) return true;
  }
  return false;
}
