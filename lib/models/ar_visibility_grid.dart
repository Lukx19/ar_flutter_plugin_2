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
    this.defaultColor = 0xFFFF0000,
    this.pointSizePx = 6,
    this.enabled = true,
    this.voxelRenderMode = ARVisibilityGridRenderMode.centroids,
    this.cubeSizeFactor = 1,
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
    if (!pointSizePx.isFinite ||
        pointSizePx <= 0 ||
        !cubeSizeFactor.isFinite ||
        cubeSizeFactor < 0.1 ||
        cubeSizeFactor > 1) {
      throw ArgumentError('Invalid visibility-grid renderer configuration.');
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

  /// Packed ARGB color assigned until Dart supplies saved-image visibility.
  final int defaultColor;

  /// Requested point diameter for centroid/point renderer modes.
  final double pointSizePx;

  /// Whether the retained native overlay starts visible.
  final bool enabled;

  /// Native representation selected without rebuilding stable geometry.
  final ARVisibilityGridRenderMode voxelRenderMode;

  /// Cube edge length as a fraction of the configured voxel size.
  final double cubeSizeFactor;

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
        'defaultColor': defaultColor,
        'pointSizePx': pointSizePx,
        'enabled': enabled,
        'voxelRenderMode': voxelRenderMode.name,
        'cubeSizeFactor': cubeSizeFactor,
      };
}

/// Native retained-overlay representation.
enum ARVisibilityGridRenderMode { points, centroids, cubes }

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
    required this.diagnostics,
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
    final diagnostics = map['diagnostics'];
    if (sessionGeneration is! int ||
        sessionGeneration < 0 ||
        rendererReady is! bool ||
        featureReady is! bool ||
        depthConfigured is! bool ||
        renderCapacity is! int ||
        renderCapacity <= 0 ||
        featureTrackCapacity is! int ||
        featureTrackCapacity <= 0 ||
        health is! Map<Object?, Object?> ||
        diagnostics is! Map<Object?, Object?>) {
      throw const FormatException(
        'Invalid visibility-grid initialization result.',
      );
    }
    final parsedDiagnostics = ARVisibilityGridDiagnostics.fromMap(diagnostics);
    if (parsedDiagnostics.geometryRevision != 0 ||
        parsedDiagnostics.featureTrackCapacity != featureTrackCapacity ||
        parsedDiagnostics.stableVoxelCapacity != renderCapacity) {
      throw const FormatException(
        'Visibility-grid initialization diagnostics do not match capacities.',
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
      diagnostics: parsedDiagnostics,
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

  /// Zero-revision counters and negotiated native capacity baselines.
  final ARVisibilityGridDiagnostics diagnostics;
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
    this.restoredVisibilityRevision = 0,
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
        restoredVisibilityRevision < 0 ||
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

  /// Last color revision paired with the restored geometry artifact.
  final int restoredVisibilityRevision;

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
        'restoredVisibilityRevision': restoredVisibilityRevision,
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

/// Fixed byte width of [ARCoverageRendererStyleRowV1].
const int coverageRendererStyleRowV1Bytes = 16;

/// Maximum key/style rows in one 64-KiB renderer request payload.
const int coverageRendererMaxStylePatchRows = 2048;

enum ARCoverageRendererSemantic { confirmed, ambiguous, suppressedDebug }

enum ARCoverageRendererCoverage { uncovered, partial, complete }

enum ARCoverageRendererPalette {
  uniform,
  coverage,
  normal,
  occupancy,
  lineage,
  age,
  sourceHealth,
  residency,
  direction,
}

enum ARCoverageRendererCut {
  exactCurrent,
  staleDisplay,
  lowerBound,
  coveragePending,
  indeterminateHistory,
  unavailable,
}

enum ARCoverageRendererResidency { activeL0, warmL1, coldL2 }

enum ARCoverageRendererTarget { none, primary, halo }

enum ARCoverageRendererGlyph { none, normal, desiredDirection, viewRose }

enum ARCoverageRendererAge { fresh, recent, aging, old }

enum ARCoverageRendererSourceHealth {
  healthy,
  featureOnly,
  transientUnavailable,
  failed,
  unsupported,
}

/// Fixed-width, disposable renderer projection of one semantic/style cut.
final class ARCoverageRendererStyleRowV1 {
  ARCoverageRendererStyleRowV1({
    this.semanticGeneration = 0,
    this.styleGeneration = 0,
    this.semantic = ARCoverageRendererSemantic.confirmed,
    this.coverage = ARCoverageRendererCoverage.uncovered,
    this.palette = ARCoverageRendererPalette.coverage,
    this.cut = ARCoverageRendererCut.exactCurrent,
    this.residency = ARCoverageRendererResidency.activeL0,
    this.target = ARCoverageRendererTarget.none,
    this.directionBin = 0xff,
    this.glyph = ARCoverageRendererGlyph.none,
    this.lineageCount = 0,
    this.age = ARCoverageRendererAge.fresh,
    this.sourceHealth = ARCoverageRendererSourceHealth.healthy,
  }) {
    if (semanticGeneration < 0 ||
        semanticGeneration > 0xffffffff ||
        styleGeneration < 0 ||
        styleGeneration > 0xffffffff ||
        lineageCount < 0 ||
        lineageCount > 0xffff ||
        !(directionBin == 0xff || directionBin >= 0 && directionBin <= 23) ||
        ((glyph == ARCoverageRendererGlyph.none ||
                glyph == ARCoverageRendererGlyph.normal) !=
            (directionBin == 0xff))) {
      throw ArgumentError('Invalid coverage renderer style row.');
    }
  }

  final int semanticGeneration;
  final int styleGeneration;
  final ARCoverageRendererSemantic semantic;
  final ARCoverageRendererCoverage coverage;
  final ARCoverageRendererPalette palette;
  final ARCoverageRendererCut cut;
  final ARCoverageRendererResidency residency;
  final ARCoverageRendererTarget target;
  final int directionBin;
  final ARCoverageRendererGlyph glyph;
  final int lineageCount;
  final ARCoverageRendererAge age;
  final ARCoverageRendererSourceHealth sourceHealth;

  /// True when a packet's rows name one committed semantic/style cut.
  ///
  /// Residency is intentionally per-row presentation state within that cut,
  /// not a separately publishable snapshot.
  static bool hasCoherentGenerations(
    Iterable<ARCoverageRendererStyleRowV1> rows,
  ) {
    int? semantic;
    int? style;
    for (final row in rows) {
      semantic ??= row.semanticGeneration;
      style ??= row.styleGeneration;
      if (row.semanticGeneration != semantic || row.styleGeneration != style) {
        return false;
      }
    }
    return true;
  }

  Uint8List encode() {
    final bytes = Uint8List(coverageRendererStyleRowV1Bytes);
    final data = ByteData.sublistView(bytes);
    bytes[0] = 1;
    bytes[1] = semantic.index |
        coverage.index << 2 |
        residency.index << 4 |
        target.index << 6;
    bytes[2] = palette.index | cut.index << 4;
    bytes[3] = glyph.index | age.index << 2 | sourceHealth.index << 4;
    bytes[4] = directionBin;
    data.setUint16(6, lineageCount, Endian.little);
    data.setUint32(8, semanticGeneration, Endian.little);
    data.setUint32(12, styleGeneration, Endian.little);
    return bytes;
  }

  static ARCoverageRendererStyleRowV1 decode(Uint8List bytes,
      [int offset = 0]) {
    if (offset < 0 || bytes.length - offset < coverageRendererStyleRowV1Bytes) {
      throw const FormatException('Truncated coverage renderer style row.');
    }
    final row = Uint8List.sublistView(
      bytes,
      offset,
      offset + coverageRendererStyleRowV1Bytes,
    );
    if (row[0] != 1 ||
        row[2] & 0x80 != 0 ||
        row[3] & 0x80 != 0 ||
        row[5] != 0) {
      throw const FormatException('Reserved coverage renderer style value.');
    }
    T value<T extends Enum>(List<T> values, int index) {
      if (index < 0 || index >= values.length) {
        throw const FormatException('Reserved coverage renderer enum code.');
      }
      return values[index];
    }

    final semanticBits = row[1];
    final paletteCutBits = row[2];
    final glyphAgeHealthBits = row[3];
    final data = ByteData.sublistView(row);
    try {
      return ARCoverageRendererStyleRowV1(
        semanticGeneration: data.getUint32(8, Endian.little),
        styleGeneration: data.getUint32(12, Endian.little),
        semantic: value(ARCoverageRendererSemantic.values, semanticBits & 0x3),
        coverage:
            value(ARCoverageRendererCoverage.values, (semanticBits >> 2) & 0x3),
        palette: value(ARCoverageRendererPalette.values, paletteCutBits & 0xf),
        cut: value(ARCoverageRendererCut.values, (paletteCutBits >> 4) & 0x7),
        residency: value(
            ARCoverageRendererResidency.values, (semanticBits >> 4) & 0x3),
        target:
            value(ARCoverageRendererTarget.values, (semanticBits >> 6) & 0x3),
        directionBin: row[4],
        glyph: value(ARCoverageRendererGlyph.values, glyphAgeHealthBits & 0x3),
        lineageCount: data.getUint16(6, Endian.little),
        age: value(
            ARCoverageRendererAge.values, (glyphAgeHealthBits >> 2) & 0x3),
        sourceHealth: value(
          ARCoverageRendererSourceHealth.values,
          (glyphAgeHealthBits >> 4) & 0x7,
        ),
      );
    } on ArgumentError catch (error) {
      throw FormatException('Invalid coverage renderer style row.', error);
    }
  }

  @override
  bool operator ==(Object other) =>
      other is ARCoverageRendererStyleRowV1 &&
      semanticGeneration == other.semanticGeneration &&
      styleGeneration == other.styleGeneration &&
      semantic == other.semantic &&
      coverage == other.coverage &&
      palette == other.palette &&
      cut == other.cut &&
      residency == other.residency &&
      target == other.target &&
      directionBin == other.directionBin &&
      glyph == other.glyph &&
      lineageCount == other.lineageCount &&
      age == other.age &&
      sourceHealth == other.sourceHealth;

  @override
  int get hashCode => Object.hash(
        semanticGeneration,
        styleGeneration,
        semantic,
        coverage,
        palette,
        cut,
        residency,
        target,
        directionBin,
        glyph,
        lineageCount,
        age,
        sourceHealth,
      );
}

/// Semantic/style update for keys in an acknowledged geometry revision.
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
    required Iterable<ARCoverageRendererStyleRowV1> styles,
  })  : keys = Int64List.fromList(keys),
        styles = List<ARCoverageRendererStyleRowV1>.unmodifiable(styles) {
    if (groupId.isEmpty ||
        groupGeneration < 0 ||
        sessionGeneration < 0 ||
        geometryRevision < 0 ||
        visibilityRevision <= 0 ||
        keys.length > coverageRendererMaxStylePatchRows ||
        keys.length != this.styles.length ||
        _hasDuplicates(keys)) {
      throw ArgumentError('Invalid visibility-grid visibility patch.');
    }
    if (!ARCoverageRendererStyleRowV1.hasCoherentGenerations(this.styles)) {
      throw ArgumentError('Visibility patch contains mixed renderer cuts.');
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

  /// Fixed-width semantic/style rows parallel to [keys].
  final List<ARCoverageRendererStyleRowV1> styles;

  /// Serializes the immutable v1 semantic/style payload.
  Map<String, Object> toMap() => <String, Object>{
        'version': visibilityGridWireVersion,
        'groupId': groupId,
        'groupGeneration': groupGeneration,
        'sessionGeneration': sessionGeneration,
        'geometryRevision': geometryRevision,
        'visibilityRevision': visibilityRevision,
        'keys': Int64List.fromList(keys),
        'styles': Uint8List.fromList(<int>[
          for (final style in styles) ...style.encode(),
        ]),
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

/// Lightweight native heartbeat containing health and bounded diagnostics.
class ARVisibilityGridHealthSnapshot {
  const ARVisibilityGridHealthSnapshot({
    required this.sourceHealth,
    required this.diagnostics,
  });

  /// Parses the strict v1 health payload.
  factory ARVisibilityGridHealthSnapshot.fromMap(Map<Object?, Object?> map) {
    if (map['version'] != visibilityGridWireVersion ||
        map['sourceHealth'] is! Map<Object?, Object?> ||
        map['diagnostics'] is! Map<Object?, Object?>) {
      throw const FormatException('Invalid visibility-grid health payload.');
    }
    return ARVisibilityGridHealthSnapshot(
      sourceHealth: ARVisibilityGridSourceHealth.fromMap(
        map['sourceHealth']! as Map<Object?, Object?>,
      ),
      diagnostics: ARVisibilityGridDiagnostics.fromMap(
        map['diagnostics']! as Map<Object?, Object?>,
      ),
    );
  }

  /// Current component health.
  final ARVisibilityGridSourceHealth sourceHealth;

  /// Bounded counters sampled independently of geometry publication.
  final ARVisibilityGridDiagnostics diagnostics;
}

/// Bounded native counters and latency samples carried with each geometry
/// revision.
///
/// Raw feature coordinates, depth samples, and platform identifiers are never
/// included.
class ARVisibilityGridDiagnostics {
  const ARVisibilityGridDiagnostics({
    required this.candidateTracks,
    required this.stableTracks,
    required this.stableVoxels,
    required this.featureTrackCapacity,
    required this.stableVoxelCapacity,
    required this.featureObservationCount,
    required this.featureMigrations,
    required this.featureJumpResets,
    required this.candidateExpirations,
    required this.supportRemovals,
    required this.acceptedSamples,
    required this.rejectedSamples,
    required this.capacityRejectedCandidates,
    required this.featureTransientUnavailableCount,
    required this.featureFailureCount,
    required this.lastFeatureFusionNs,
    required this.maxFeatureFusionNs,
    required this.featureFusionP95Ns,
    required this.estimatedStateBytes,
    required this.depthObservationCount,
    required this.depthAcceptedPixels,
    required this.depthRejectedPixels,
    required this.depthCapacityRejectedPixels,
    required this.depthRayVisits,
    required this.carvedVoxels,
    required this.restoredVoxels,
    required this.depthTransientUnavailableCount,
    required this.depthFailureCount,
    required this.lastDepthFusionNs,
    required this.maxDepthFusionNs,
    required this.depthFusionP95Ns,
    required this.callbackCopyP95Ns,
    required this.coalescedFeatureObservations,
    required this.coalescedDepthObservations,
    required this.coalescedGeometryChanges,
    required this.geometryRevision,
    required this.pendingGeometryKeys,
    required this.unacknowledgedGeometryCallbacks,
    required this.publishedDeltaCount,
    required this.snapshotRecoveryCount,
    required this.geometryAcknowledgementCount,
    required this.rendererRows,
    required this.rendererFreeRows,
  });

  /// Parses a complete v1 diagnostics payload.
  factory ARVisibilityGridDiagnostics.fromMap(Map<Object?, Object?> map) {
    int read(String field) {
      final value = map[field];
      if (value is! int || value < 0) {
        throw FormatException('Invalid visibility-grid diagnostic $field.');
      }
      return value;
    }

    final result = ARVisibilityGridDiagnostics(
      candidateTracks: read('candidateTracks'),
      stableTracks: read('stableTracks'),
      stableVoxels: read('stableVoxels'),
      featureTrackCapacity: read('featureTrackCapacity'),
      stableVoxelCapacity: read('stableVoxelCapacity'),
      featureObservationCount: read('featureObservationCount'),
      featureMigrations: read('featureMigrations'),
      featureJumpResets: read('featureJumpResets'),
      candidateExpirations: read('candidateExpirations'),
      supportRemovals: read('supportRemovals'),
      acceptedSamples: read('acceptedSamples'),
      rejectedSamples: read('rejectedSamples'),
      capacityRejectedCandidates: read('capacityRejectedCandidates'),
      featureTransientUnavailableCount:
          read('featureTransientUnavailableCount'),
      featureFailureCount: read('featureFailureCount'),
      lastFeatureFusionNs: read('lastFeatureFusionNs'),
      maxFeatureFusionNs: read('maxFeatureFusionNs'),
      featureFusionP95Ns: read('featureFusionP95Ns'),
      estimatedStateBytes: read('estimatedStateBytes'),
      depthObservationCount: read('depthObservationCount'),
      depthAcceptedPixels: read('depthAcceptedPixels'),
      depthRejectedPixels: read('depthRejectedPixels'),
      depthCapacityRejectedPixels: read('depthCapacityRejectedPixels'),
      depthRayVisits: read('depthRayVisits'),
      carvedVoxels: read('carvedVoxels'),
      restoredVoxels: read('restoredVoxels'),
      depthTransientUnavailableCount: read('depthTransientUnavailableCount'),
      depthFailureCount: read('depthFailureCount'),
      lastDepthFusionNs: read('lastDepthFusionNs'),
      maxDepthFusionNs: read('maxDepthFusionNs'),
      depthFusionP95Ns: read('depthFusionP95Ns'),
      callbackCopyP95Ns: read('callbackCopyP95Ns'),
      coalescedFeatureObservations: read('coalescedFeatureObservations'),
      coalescedDepthObservations: read('coalescedDepthObservations'),
      coalescedGeometryChanges: read('coalescedGeometryChanges'),
      geometryRevision: read('geometryRevision'),
      pendingGeometryKeys: read('pendingGeometryKeys'),
      unacknowledgedGeometryCallbacks: read('unacknowledgedGeometryCallbacks'),
      publishedDeltaCount: read('publishedDeltaCount'),
      snapshotRecoveryCount: read('snapshotRecoveryCount'),
      geometryAcknowledgementCount: read('geometryAcknowledgementCount'),
      rendererRows: read('rendererRows'),
      rendererFreeRows: read('rendererFreeRows'),
    );
    if (result.depthFusionP95Ns > result.maxDepthFusionNs ||
        result.unacknowledgedGeometryCallbacks > 1 ||
        result.candidateTracks + result.stableTracks >
            result.featureTrackCapacity ||
        result.stableVoxels > result.stableVoxelCapacity ||
        result.rendererRows + result.rendererFreeRows !=
            result.stableVoxelCapacity) {
      throw const FormatException(
        'Inconsistent visibility-grid diagnostics.',
      );
    }
    return result;
  }

  final int candidateTracks;
  final int stableTracks;
  final int stableVoxels;
  final int featureTrackCapacity;
  final int stableVoxelCapacity;
  final int featureObservationCount;
  final int featureMigrations;
  final int featureJumpResets;
  final int candidateExpirations;
  final int supportRemovals;
  final int acceptedSamples;
  final int rejectedSamples;
  final int capacityRejectedCandidates;
  final int featureTransientUnavailableCount;
  final int featureFailureCount;
  final int lastFeatureFusionNs;
  final int maxFeatureFusionNs;
  final int featureFusionP95Ns;
  final int estimatedStateBytes;
  final int depthObservationCount;
  final int depthAcceptedPixels;
  final int depthRejectedPixels;
  final int depthCapacityRejectedPixels;
  final int depthRayVisits;
  final int carvedVoxels;
  final int restoredVoxels;
  final int depthTransientUnavailableCount;
  final int depthFailureCount;
  final int lastDepthFusionNs;
  final int maxDepthFusionNs;
  final int depthFusionP95Ns;
  final int callbackCopyP95Ns;
  final int coalescedFeatureObservations;
  final int coalescedDepthObservations;
  final int coalescedGeometryChanges;
  final int geometryRevision;
  final int pendingGeometryKeys;
  final int unacknowledgedGeometryCallbacks;
  final int publishedDeltaCount;
  final int snapshotRecoveryCount;
  final int geometryAcknowledgementCount;
  final int rendererRows;
  final int rendererFreeRows;
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
    required this.diagnostics,
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
    final diagnostics = map['diagnostics'];
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
        sourceHealth is! Map<Object?, Object?> ||
        diagnostics is! Map<Object?, Object?>) {
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
    final parsedDiagnostics = ARVisibilityGridDiagnostics.fromMap(diagnostics);
    if (parsedDiagnostics.geometryRevision != geometryRevision ||
        parsedDiagnostics.stableVoxelCapacity != capacity) {
      throw const FormatException(
        'Visibility-grid diagnostics do not match the delta revision.',
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
      diagnostics: parsedDiagnostics,
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

  /// Native counters sampled atomically with [geometryRevision].
  final ARVisibilityGridDiagnostics diagnostics;
}

/// Fixed-size notification that a native-owned geometry revision is ready.
///
/// This is deliberately distinct from [ARVisibilityGridDelta]: ordinary
/// platform callbacks carry no stable-key collections into the root isolate.
/// A background worker must pull and validate the corresponding delta before
/// acknowledging the revision. Full deltas remain available only through the
/// explicit worker-pull/recovery endpoint.
class ARVisibilityGridDeltaSummary {
  ARVisibilityGridDeltaSummary({
    required this.groupId,
    required this.groupGeneration,
    required this.sessionGeneration,
    required this.baseGeometryRevision,
    required this.geometryRevision,
    required this.reset,
    required this.capacity,
    required this.sourceHealth,
    required this.diagnostics,
  });

  factory ARVisibilityGridDeltaSummary.fromMap(Map<Object?, Object?> map) {
    if (map['version'] != visibilityGridWireVersion) {
      throw const FormatException('Unsupported visibility-grid wire version.');
    }
    // Rejecting these fields is intentional: accepting them would make an
    // ordinary root-isolate callback a semantic-surface transport again.
    if (map.containsKey('upsertKeys') || map.containsKey('removalKeys')) {
      throw const FormatException(
        'Visibility-grid summary must not contain semantic keys.',
      );
    }
    final groupId = map['groupId'];
    final groupGeneration = map['groupGeneration'];
    final sessionGeneration = map['sessionGeneration'];
    final baseGeometryRevision = map['baseGeometryRevision'];
    final geometryRevision = map['geometryRevision'];
    final reset = map['reset'];
    final capacity = map['capacity'];
    final sourceHealth = map['sourceHealth'];
    final diagnostics = map['diagnostics'];
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
        capacity is! int ||
        capacity <= 0 ||
        sourceHealth is! Map<Object?, Object?> ||
        diagnostics is! Map<Object?, Object?>) {
      throw const FormatException('Invalid visibility-grid delta summary.');
    }
    if (!reset && geometryRevision != baseGeometryRevision + 1) {
      throw const FormatException(
        'Non-reset geometry revisions must be adjacent.',
      );
    }
    final parsedDiagnostics = ARVisibilityGridDiagnostics.fromMap(diagnostics);
    if (parsedDiagnostics.geometryRevision != geometryRevision ||
        parsedDiagnostics.stableVoxelCapacity != capacity) {
      throw const FormatException(
        'Visibility-grid diagnostics do not match the summary revision.',
      );
    }
    return ARVisibilityGridDeltaSummary(
      groupId: groupId,
      groupGeneration: groupGeneration,
      sessionGeneration: sessionGeneration,
      baseGeometryRevision: baseGeometryRevision,
      geometryRevision: geometryRevision,
      reset: reset,
      capacity: capacity,
      sourceHealth: ARVisibilityGridSourceHealth.fromMap(sourceHealth),
      diagnostics: parsedDiagnostics,
    );
  }

  final String groupId;
  final int groupGeneration;
  final int sessionGeneration;
  final int baseGeometryRevision;
  final int geometryRevision;
  final bool reset;
  final int capacity;
  final ARVisibilityGridSourceHealth sourceHealth;
  final ARVisibilityGridDiagnostics diagnostics;
}

bool _hasDuplicates(Iterable<int> values) {
  final unique = <int>{};
  for (final value in values) {
    if (!unique.add(value)) return true;
  }
  return false;
}
