import 'dart:async';
import 'dart:math';
import 'dart:ui' as ui;

import 'package:flutter/services.dart';

import 'ar_visibility_surface_stream.dart';

/// The winner of one identity-qualified, outcome-unknown COMMIT attempt.
enum ARVisibilityGridV2CommitDecision {
  /// Native committed the exact queried transaction.
  commit,

  /// Native fenced the attempt before COMMIT publication.
  abandon,
}

/// Exact bounded qualification for a COMMIT receipt query.
///
/// The binding tokens identify the old runtime binding whose response was
/// lost. The manager adds its current binding qualifier privately when it
/// sends the query, so a stale control object cannot query a replacement.
/// UUID fields are 32 hexadecimal characters, tokens are 16 bytes, and all
/// scalars fit a signed 64-bit platform integer. Stream token, request
/// sequence, and transaction ID are positive; generations and target
/// revisions are non-negative. The native ledger retains at most eight exact
/// receipts.
final class ARVisibilityGridV2CommitReceiptQuery {
  /// Creates and validates the complete bounded identity for one query.
  ///
  /// Throws [ArgumentError] for a malformed UUID, a token that is not exactly
  /// 16 bytes, a non-positive stream/request/transaction identity, or a
  /// generation/revision outside the non-negative signed 64-bit range.
  ARVisibilityGridV2CommitReceiptQuery({
    required this.controlRequestId,
    required this.sessionId,
    required this.captureGroupId,
    required this.sessionGeneration,
    required this.groupGeneration,
    required Uint8List nativeStreamToken,
    required Uint8List workerBindingToken,
    required this.streamToken,
    required this.requestSequence,
    required this.transactionId,
    required this.targetGeometryRevision,
    required this.targetLineageRevision,
  })  : nativeStreamToken = Uint8List.fromList(nativeStreamToken),
        workerBindingToken = Uint8List.fromList(workerBindingToken) {
    _validate();
  }

  /// UUID of the old START control request, encoded as 32 hex characters.
  final String controlRequestId;

  /// UUID of the capture session, encoded as 32 hex characters.
  final String sessionId;

  /// UUID of the capture group, encoded as 32 hex characters.
  final String captureGroupId;

  /// Session generation of the old binding.
  final int sessionGeneration;

  /// Group generation of the old binding.
  final int groupGeneration;

  /// Native stream identity token from the old binding.
  final Uint8List nativeStreamToken;

  /// Worker binding identity token from the old binding.
  final Uint8List workerBindingToken;

  /// Stream token used by the lost COMMIT request.
  final int streamToken;

  /// Exact request sequence used by the lost COMMIT request.
  final int requestSequence;

  /// Transaction targeted by the lost COMMIT request.
  final int transactionId;

  /// Geometry revision targeted by the lost COMMIT request.
  final int targetGeometryRevision;

  /// Lineage revision targeted by the lost COMMIT request.
  final int targetLineageRevision;

  /// Encodes this bounded identity for the platform channel.
  ///
  /// Revalidates mutable token fields and throws [ArgumentError] if callers
  /// changed either token to an invalid length after construction.
  Map<String, Object?> toMap() {
    _validate();
    return <String, Object?>{
      'controlRequestId': controlRequestId,
      'sessionId': sessionId,
      'captureGroupId': captureGroupId,
      'sessionGeneration': sessionGeneration,
      'groupGeneration': groupGeneration,
      'nativeStreamToken': Uint8List.fromList(nativeStreamToken),
      'workerBindingToken': Uint8List.fromList(workerBindingToken),
      'streamToken': streamToken,
      'requestSequence': requestSequence,
      'transactionId': transactionId,
      'targetGeometryRevision': targetGeometryRevision,
      'targetLineageRevision': targetLineageRevision,
    };
  }

  void _validate() {
    for (final entry in <String, String>{
      'controlRequestId': controlRequestId,
      'sessionId': sessionId,
      'captureGroupId': captureGroupId,
    }.entries) {
      if (!_uuidHex.hasMatch(entry.value)) {
        throw ArgumentError.value(
          entry.value,
          entry.key,
          'Must be exactly 32 hexadecimal characters.',
        );
      }
    }
    _queryToken(nativeStreamToken, 'nativeStreamToken');
    _queryToken(workerBindingToken, 'workerBindingToken');
    _queryInt(sessionGeneration, 'sessionGeneration');
    _queryInt(groupGeneration, 'groupGeneration');
    _queryInt(streamToken, 'streamToken', positive: true);
    _queryInt(requestSequence, 'requestSequence', positive: true);
    _queryInt(transactionId, 'transactionId', positive: true);
    _queryInt(targetGeometryRevision, 'targetGeometryRevision');
    _queryInt(targetLineageRevision, 'targetLineageRevision');
  }
}

final RegExp _uuidHex = RegExp(r'^[0-9a-fA-F]{32}$');
const int _maximumPlatformInt = 0x7fffffffffffffff;

void _queryToken(Uint8List value, String name) {
  if (value.length != 16) {
    throw ArgumentError.value(value, name, 'Must be exactly 16 bytes.');
  }
}

void _queryInt(int value, String name, {bool positive = false}) {
  if (value > _maximumPlatformInt || (positive ? value <= 0 : value < 0)) {
    throw ArgumentError.value(
      value,
      name,
      positive
          ? 'Must be a positive signed 64-bit integer.'
          : 'Must be a non-negative signed 64-bit integer.',
    );
  }
}

/// Complete native baseline returned by an exact COMMIT receipt query.
///
/// It contains only the bounded scalar/revision and identity fields needed by
/// a later reattachment owner; it never contains structural surface bytes.
final class ARVisibilityGridV2CommittedBaseline {
  /// Creates a complete scalar/revision baseline for later reattachment.
  const ARVisibilityGridV2CommittedBaseline({
    required this.transactionId,
    required this.geometryRevision,
    required this.lineageRevision,
    required this.styleRevision,
    required this.evidenceRevision,
    required this.captureRevision,
    required this.coverageRevision,
    required this.producedStyleRevision,
    required this.regionManifestRevision,
    required this.schemaRootRevision,
    required this.nextSurfaceIdHighWater,
    required this.schemaRootHashIdentity,
    required this.manifestRootHashIdentity,
    required this.groupFrameConvention,
    required this.matrixConvention,
    required this.directionConvention,
    required this.normalEncoding,
    required this.groupFromWorldIdentity,
    required this.worldFromGroupIdentity,
  });

  /// Committed transaction identity.
  final int transactionId;

  /// Committed geometry revision.
  final int geometryRevision;

  /// Committed lineage revision.
  final int lineageRevision;

  /// Committed style revision.
  final int styleRevision;

  /// Committed evidence revision.
  final int evidenceRevision;

  /// Committed capture revision.
  final int captureRevision;

  /// Committed coverage revision.
  final int coverageRevision;

  /// Produced-style revision included in the baseline.
  final int producedStyleRevision;

  /// Region-manifest revision included in the baseline.
  final int regionManifestRevision;

  /// Schema-root revision included in the baseline.
  final int schemaRootRevision;

  /// Highest committed surface identifier.
  final int nextSurfaceIdHighWater;

  /// Bounded schema-root identity string.
  final String schemaRootHashIdentity;

  /// Bounded manifest-root identity string.
  final String manifestRootHashIdentity;

  /// Group-frame convention identifier.
  final int groupFrameConvention;

  /// Matrix convention identifier.
  final int matrixConvention;

  /// Direction convention identifier.
  final int directionConvention;

  /// Normal encoding identifier.
  final int normalEncoding;

  /// Group-from-world transform identity.
  final String groupFromWorldIdentity;

  /// World-from-group transform identity.
  final String worldFromGroupIdentity;

  /// Decodes and validates a bounded native baseline map.
  ///
  /// Throws [StateError] when a field is missing, has the wrong type, exceeds
  /// its documented bound, or a scalar is outside signed 64-bit range.
  static ARVisibilityGridV2CommittedBaseline fromMap(Object? raw) {
    if (raw is! Map) throw StateError('V2 receipt omitted its baseline.');
    final map = Map<Object?, Object?>.from(raw);
    String string(String key) {
      final value = map[key];
      if (value is! String || value.length > 1024) {
        throw StateError('V2 receipt baseline field $key is invalid.');
      }
      return value;
    }

    return ARVisibilityGridV2CommittedBaseline(
      transactionId: _receiptInt(map, 'transactionId'),
      geometryRevision: _receiptInt(map, 'geometryRevision'),
      lineageRevision: _receiptInt(map, 'lineageRevision'),
      styleRevision: _receiptInt(map, 'styleRevision'),
      evidenceRevision: _receiptInt(map, 'evidenceRevision'),
      captureRevision: _receiptInt(map, 'captureRevision'),
      coverageRevision: _receiptInt(map, 'coverageRevision'),
      producedStyleRevision: _receiptInt(map, 'producedStyleRevision'),
      regionManifestRevision: _receiptInt(map, 'regionManifestRevision'),
      schemaRootRevision: _receiptInt(map, 'schemaRootRevision'),
      nextSurfaceIdHighWater: _receiptInt(map, 'nextSurfaceIdHighWater'),
      schemaRootHashIdentity: string('schemaRootHashIdentity'),
      manifestRootHashIdentity: string('manifestRootHashIdentity'),
      groupFrameConvention: _receiptInt(map, 'groupFrameConvention'),
      matrixConvention: _receiptInt(map, 'matrixConvention'),
      directionConvention: _receiptInt(map, 'directionConvention'),
      normalEncoding: _receiptInt(map, 'normalEncoding'),
      groupFromWorldIdentity: string('groupFromWorldIdentity'),
      worldFromGroupIdentity: string('worldFromGroupIdentity'),
    );
  }

  bool get _isCanonicalZero =>
      transactionId == 0 &&
      geometryRevision == 0 &&
      lineageRevision == 0 &&
      styleRevision == 0 &&
      evidenceRevision == 0 &&
      captureRevision == 0 &&
      coverageRevision == 0 &&
      producedStyleRevision == 0 &&
      regionManifestRevision == 0 &&
      schemaRootRevision == 0 &&
      nextSurfaceIdHighWater == 0 &&
      schemaRootHashIdentity.isEmpty &&
      manifestRootHashIdentity.isEmpty &&
      groupFrameConvention == 1 &&
      matrixConvention == 1 &&
      directionConvention == 1 &&
      normalEncoding == 1 &&
      groupFromWorldIdentity == _identityMatrixIdentity &&
      worldFromGroupIdentity == _identityMatrixIdentity;
}

const String _identityMatrixIdentity =
    '3ff0000000000000,0,0,0,0,3ff0000000000000,0,0,0,0,'
    '3ff0000000000000,0,0,0,0,3ff0000000000000';

/// Read-only outcome and scalar evidence for one exact COMMIT attempt.
///
/// Tokens are 16 bytes, identity strings are bounded, scalar fields are
/// non-negative integers, the baseline has the fixed fields above, and
/// [rootIsolateSurfaceBytes] must be zero. [fromMap] rejects mismatched
/// COMMIT baselines and non-zero abandon baselines with [StateError].
final class ARVisibilityGridV2CommitReceipt {
  const ARVisibilityGridV2CommitReceipt._({
    required this.decision,
    required this.controlRequestId,
    required this.sessionId,
    required this.captureGroupId,
    required this.sessionGeneration,
    required this.groupGeneration,
    required this.nativeStreamToken,
    required this.workerBindingToken,
    required this.streamToken,
    required this.requestSequence,
    required this.transactionId,
    required this.targetGeometryRevision,
    required this.targetLineageRevision,
    required this.baseline,
    required this.rootIsolateSurfaceBytes,
  });

  /// Whether native won the COMMIT publication fence.
  final ARVisibilityGridV2CommitDecision decision;

  /// UUID of the old START control request.
  final String controlRequestId;

  /// UUID of the capture session.
  final String sessionId;

  /// UUID of the capture group.
  final String captureGroupId;

  /// Session generation of the queried binding.
  final int sessionGeneration;

  /// Group generation of the queried binding.
  final int groupGeneration;

  /// Native stream identity token of the queried binding.
  final Uint8List nativeStreamToken;

  /// Worker binding identity token of the queried binding.
  final Uint8List workerBindingToken;

  /// Stream token of the queried COMMIT.
  final int streamToken;

  /// Request sequence of the queried COMMIT.
  final int requestSequence;

  /// Transaction identity of the queried COMMIT.
  final int transactionId;

  /// Target geometry revision of the queried COMMIT.
  final int targetGeometryRevision;

  /// Target lineage revision of the queried COMMIT.
  final int targetLineageRevision;

  /// Scalar committed baseline, or the canonical zero abandon baseline.
  final ARVisibilityGridV2CommittedBaseline baseline;

  /// Root-isolate structural payload bytes; always zero.
  final int rootIsolateSurfaceBytes;

  /// Whether [decision] is [ARVisibilityGridV2CommitDecision.commit].
  bool get committed => decision == ARVisibilityGridV2CommitDecision.commit;

  /// Whether this receipt exactly matches [query].
  bool matchesQuery(ARVisibilityGridV2CommitReceiptQuery query) =>
      controlRequestId == query.controlRequestId &&
      sessionId == query.sessionId &&
      captureGroupId == query.captureGroupId &&
      sessionGeneration == query.sessionGeneration &&
      groupGeneration == query.groupGeneration &&
      _bytesEqual(nativeStreamToken, query.nativeStreamToken) &&
      _bytesEqual(workerBindingToken, query.workerBindingToken) &&
      streamToken == query.streamToken &&
      requestSequence == query.requestSequence &&
      transactionId == query.transactionId &&
      targetGeometryRevision == query.targetGeometryRevision &&
      targetLineageRevision == query.targetLineageRevision;

  /// Decodes and validates bounded native receipt evidence.
  ///
  /// Throws [StateError] for malformed identity, scalar bounds, a non-zero
  /// root payload count, a mismatched COMMIT baseline, or any abandon baseline
  /// that differs from native's complete canonical zero value.
  static ARVisibilityGridV2CommitReceipt fromMap(Object? raw) {
    if (raw is! Map) throw StateError('V2 receipt was not a map.');
    final map = Map<Object?, Object?>.from(raw);
    final decision = switch (map['decision']) {
      'commit' => ARVisibilityGridV2CommitDecision.commit,
      'abandon' => ARVisibilityGridV2CommitDecision.abandon,
      _ => throw StateError('V2 receipt decision is invalid.'),
    };
    String string(String key) {
      final value = map[key];
      if (value is! String || !_uuidHex.hasMatch(value)) {
        throw StateError('V2 receipt field $key is invalid.');
      }
      return value;
    }

    Uint8List token(String key) {
      final value = map[key];
      if (value is! Uint8List || value.length != 16) {
        throw StateError('V2 receipt token $key is invalid.');
      }
      return Uint8List.fromList(value);
    }

    final rootBytes = _receiptInt(map, 'rootIsolateSurfaceBytes');
    if (rootBytes != 0) {
      throw StateError('V2 receipt exposed ordinary root-isolate bytes.');
    }
    final receipt = ARVisibilityGridV2CommitReceipt._(
      decision: decision,
      controlRequestId: string('controlRequestId'),
      sessionId: string('sessionId'),
      captureGroupId: string('captureGroupId'),
      sessionGeneration: _receiptInt(map, 'sessionGeneration'),
      groupGeneration: _receiptInt(map, 'groupGeneration'),
      nativeStreamToken: token('nativeStreamToken'),
      workerBindingToken: token('workerBindingToken'),
      streamToken: _receiptPositiveInt(map, 'streamToken'),
      requestSequence: _receiptPositiveInt(map, 'requestSequence'),
      transactionId: _receiptPositiveInt(map, 'transactionId'),
      targetGeometryRevision: _receiptInt(map, 'targetGeometryRevision'),
      targetLineageRevision: _receiptInt(map, 'targetLineageRevision'),
      baseline: ARVisibilityGridV2CommittedBaseline.fromMap(map['baseline']),
      rootIsolateSurfaceBytes: rootBytes,
    );
    if (receipt.committed &&
        (receipt.baseline.transactionId != receipt.transactionId ||
            receipt.baseline.geometryRevision !=
                receipt.targetGeometryRevision ||
            receipt.baseline.lineageRevision !=
                receipt.targetLineageRevision)) {
      throw StateError('V2 COMMIT receipt baseline does not match its query.');
    }
    if (!receipt.committed && !receipt.baseline._isCanonicalZero) {
      throw StateError('V2 abandon receipt exposed a committed baseline.');
    }
    return receipt;
  }
}

int _receiptInt(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is! num ||
      !value.isFinite ||
      value < 0 ||
      value > _maximumPlatformInt ||
      value != value.truncate()) {
    throw StateError('V2 receipt field $key is invalid.');
  }
  return value.toInt();
}

int _receiptPositiveInt(Map<Object?, Object?> map, String key) {
  final value = _receiptInt(map, key);
  if (value == 0) throw StateError('V2 receipt field $key is invalid.');
  return value;
}

ARVisibilityGridV2CommitReceipt _exactCommitReceipt(
  Object? response,
  ARVisibilityGridV2CommitReceiptQuery query,
) {
  final receipt = ARVisibilityGridV2CommitReceipt.fromMap(response);
  if (!receipt.matchesQuery(query)) {
    throw StateError('V2 receipt identity does not match its query.');
  }
  return receipt;
}

/// Packed debug control endpoint for the Proposal 08 M0a reference seam.
///
/// Each invocation is one VGC2 request and returns one VGD2 response. The V1
/// visibility-grid manager remains the compatibility product path.
final class ARVisibilityGridV2Control {
  /// Creates a compatibility control and immediately starts one hidden
  /// connection-time `bindingSnapshot` invocation. The snapshot's two
  /// 16-byte tokens are owned for this control's lifetime; disposal never
  /// snapshots replacement state. [initialBindingSnapshot] is the
  /// deterministic host/fake seam. The worker API below has the stronger
  /// connection-owned cleanup-lease contract used by production workers.
  ARVisibilityGridV2Control(
    int viewId, {
    MethodChannel? channel,
    Map<Object?, Object?>? initialBindingSnapshot,
  }) : _channel =
            channel ?? MethodChannel('visibility_grid_v2_control_$viewId') {
    _bindingReady = initialBindingSnapshot == null
        ? _captureBindingAtConnection()
        : Future<void>(() {
            bindSnapshot(initialBindingSnapshot);
          });
  }

  /// Connects to a V2 endpoint and captures its binding identity before the
  /// control is handed to callers. A later native replacement cannot change
  /// the qualifier owned by this object.
  ///
  /// Propagates [PlatformException] when native rejects or cannot complete the
  /// connection-time snapshot. Throws [StateError] when native returns a map
  /// whose binding tokens are missing or are not exactly 16 bytes.
  static Future<ARVisibilityGridV2Control> connect(
    int viewId, {
    MethodChannel? channel,
  }) async {
    final control = ARVisibilityGridV2Control(viewId, channel: channel);
    await control.bindingReady;
    return control;
  }

  final MethodChannel _channel;
  late final Future<void> _bindingReady;
  Uint8List? _bindingQualifier;
  Map<Object?, Object?>? _disposeReceipt;
  Future<Map<Object?, Object?>>? _disposeFuture;
  bool _closed = false;

  /// Completes after the connection-time native identity snapshot has been
  /// captured (or an optional compatibility endpoint has declined it).
  /// Propagates [PlatformException] from the snapshot call and throws
  /// [StateError] for malformed native binding tokens.
  Future<void> get bindingReady => _bindingReady;

  /// Sends a byte-only `start` request and returns the byte-only response.
  ///
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel fails, and [StateError] when native returns a non-byte response.
  Future<Uint8List> start(Uint8List request) async {
    await _bindingReady;
    final response = await _invoke('start', request);
    return response;
  }

  /// Sends a byte-only `beginCheckpoint` request and returns its response.
  ///
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel fails, and [StateError] when native returns a non-byte response.
  Future<Uint8List> beginCheckpoint(Uint8List request) =>
      _invoke('beginCheckpoint', request);

  /// Sends a byte-only `releaseCheckpoint` request and returns its response.
  ///
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel fails, and [StateError] when native returns a non-byte response.
  Future<Uint8List> releaseCheckpoint(Uint8List request) =>
      _invoke('releaseCheckpoint', request);

  /// Sends a byte-only `stop` request and returns its response.
  ///
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel fails, and [StateError] when native returns a non-byte response.
  Future<Uint8List> stop(Uint8List request) => _invoke('stop', request);

  /// Arms the debug-only first-exchange stall used by the Android T6
  /// recovery tracer.
  ///
  /// Returns `true` only when native accepted the bounded scalar arm request.
  /// Throws [PlatformException] or [MissingPluginException] when the endpoint
  /// is unavailable or the build is not debuggable, and [StateError] for a
  /// malformed native response. This method is not a production capability.
  Future<bool> configureDebugV2ExchangeStall() async {
    await _bindingReady;
    return _armV2DebugControl(
      _channel,
      'configureDebugV2ExchangeStall',
      'V2 debug stall returned an invalid arm receipt.',
    );
  }

  /// Arms the debug-only COMMIT-publication stall used by the Android T6
  /// recovery tracer.
  ///
  /// The latch is taken after native publishes revision 1 and before the
  /// platform reply is delivered. Throws the same bounded channel/response
  /// failures as [configureDebugV2ExchangeStall].
  Future<bool> configureDebugV2CommitPublicationStall() async {
    await _bindingReady;
    return _armV2DebugControl(
      _channel,
      'configureDebugV2CommitPublicationStall',
      'V2 debug COMMIT stall returned an invalid arm receipt.',
    );
  }

  /// Arms the debug-only exchange-3 ACK stall used by the Android T6
  /// known-COMMIT recovery tracer. Native waits after decoding request
  /// sequence 3, which is the exact post-COMMIT acknowledgement pull.
  ///
  /// Throws the same bounded channel/response failures as
  /// [configureDebugV2ExchangeStall]. This method is not a production
  /// capability.
  Future<bool> configureDebugV2AcknowledgementStall() async {
    await _bindingReady;
    return _armV2DebugControl(
      _channel,
      'configureDebugV2AckStall',
      'V2 debug ACK stall returned an invalid arm receipt.',
    );
  }

  /// Arms the debug-only composite recovery cut that first deadlines the
  /// known-COMMIT acknowledgement and then deadlines the restored START.
  Future<bool> configureDebugV2RestoredStartStall() async {
    await _bindingReady;
    return _armV2DebugControl(
      _channel,
      'configureDebugV2RestoredStartStall',
      'V2 debug restored START stall returned an invalid arm receipt.',
    );
  }

  /// Reads the bounded debug recovery trace from native.
  ///
  /// Each entry is a scalar trace label of at most 1024 characters and at
  /// most 64 entries are returned. Throws [PlatformException] or
  /// [MissingPluginException] when unavailable and [StateError] for malformed
  /// native trace data. This method is not a production capability.
  Future<List<String>> getDebugV2RecoveryTrace() async {
    await _bindingReady;
    return _readV2DebugRecoveryTrace(_channel);
  }

  /// Reconciles an outcome-unknown COMMIT against native's exact receipt.
  ///
  /// [query] must identify the old binding and accepted group/control cut.
  /// The connection-time qualifier of this control is added privately. The
  /// bounded result is either the authoritative committed baseline or an
  /// explicit zero abandon decision; it never mutates lifecycle or cursors.
  /// A teardown receipt is cleanup evidence only and is never treated as the
  /// outcome authority. Throws [PlatformException] or
  /// [MissingPluginException] for channel failure/stale qualification and
  /// [ArgumentError] before invocation for malformed query identity/bounds,
  /// and [StateError] for malformed scalar identity, bounds, or root evidence.
  /// The result contains zero ordinary root-isolate surface bytes.
  Future<ARVisibilityGridV2CommitReceipt> queryCommitReceipt(
    ARVisibilityGridV2CommitReceiptQuery query,
  ) async {
    if (_closed) throw StateError('V2 control is closed.');
    await _bindingReady;
    final qualifier = _bindingQualifier;
    if (qualifier == null) {
      throw StateError('V2 control has not claimed native tokens.');
    }
    final response = await _channel.invokeMethod<Object?>(
      'queryCommitReceipt',
      <String, Object?>{
        ...query.toMap(),
        'currentBindingQualifier': Uint8List.fromList(qualifier),
      },
    );
    return _exactCommitReceipt(response, query);
  }

  Future<Uint8List> _invoke(String method, Uint8List request) async {
    final response = await _channel.invokeMethod<Object?>(method, request);
    if (response is! Uint8List) {
      throw StateError('M0a control returned a non-byte response.');
    }
    return Uint8List.fromList(response);
  }

  /// Claims the connection-time native binding tokens used by [dispose].
  ///
  /// The first valid snapshot wins for this control's lifetime. Reusing the
  /// same qualifier is idempotent; a different replacement snapshot throws
  /// [StateError]. Either token being absent or not a 16-byte [Uint8List] also
  /// throws [StateError].
  void bindSnapshot(Map<Object?, Object?> snapshot) {
    final qualifier = _qualifierFromSnapshot(snapshot);
    final existing = _bindingQualifier;
    if (existing != null) {
      if (!_bytesEqual(existing, qualifier)) {
        throw StateError('V2 binding qualifier cannot be replaced.');
      }
      return;
    }
    _bindingQualifier = qualifier;
  }

  /// Fences only the currently claimed compatibility V2 binding.
  ///
  /// The public API is intentionally argument-free. Its connection-time
  /// qualifier is supplied internally so a stale control object cannot
  /// dispose a replacement. An endpoint without the optional snapshot seam
  /// sends a null argument and lets native reject the request; it must not
  /// guess a replacement identity. This compatibility seam is distinct from
  /// the worker cleanup lease: worker `dispose` and `abandonAndSnapshot`
  /// always send their same claimed 16-byte connection lease.
  ///
  /// Throws [PlatformException] or [MissingPluginException] when the platform
  /// channel cannot apply the fence, including stale or unclaimed native
  /// authority. Throws [StateError] only when binding-token capture or the
  /// teardown receipt is malformed or internally contradictory, including a
  /// missing/non-numeric `closedResources` field.
  ///
  /// Returns the native teardown map. It contains the old binding's scalar
  /// identity and lifecycle fields plus numeric `closedResources`; it never
  /// contains structural surface payloads.
  /// Concurrent calls for this control share one terminal Future, receipt, or
  /// channel failure and issue at most one native disposal request.
  Future<Map<Object?, Object?>> dispose() {
    final existing = _disposeFuture;
    if (existing != null) return existing;
    final future = _disposeOwnedBinding();
    _disposeFuture = future;
    return future;
  }

  Future<Map<Object?, Object?>> _disposeOwnedBinding() async {
    if (_closed) {
      return _disposeReceipt ??
          (throw StateError('V2 control has no teardown receipt.'));
    }
    try {
      await _bindingReady;
      final raw = await _channel.invokeMethod<Object?>(
        'disposeBinding',
        _bindingQualifier,
      );
      final receipt = _teardownReceipt(raw);
      _disposeReceipt = receipt;
      return receipt;
    } finally {
      _closed = true;
    }
  }

  Future<void> _captureBindingAtConnection() async {
    try {
      final raw = await _channel.invokeMethod<Object?>('bindingSnapshot');
      if (raw is Map) {
        bindSnapshot(Map<Object?, Object?>.from(raw));
      }
    } on MissingPluginException {
      // Compatibility/fake endpoints may not expose the optional snapshot.
      // Disposal remains qualified when a V2 native snapshot is available.
    }
  }
}

Future<bool> _armV2DebugControl(
  MethodChannel channel,
  String method,
  String malformedMessage,
) async {
  final raw = await channel.invokeMethod<Object?>(method);
  if (raw is! Map || raw['armed'] is! bool) {
    throw StateError(malformedMessage);
  }
  return raw['armed']! as bool;
}

Future<List<String>> _readV2DebugRecoveryTrace(MethodChannel channel) async {
  final raw = await channel.invokeMethod<Object?>('getDebugV2RecoveryTrace');
  if (raw is! Map || raw['trace'] is! List) {
    throw StateError('V2 debug recovery trace is malformed.');
  }
  final entries = (raw['trace']! as List).toList(growable: false);
  if (entries.length > 64 ||
      entries.any((entry) => entry is! String || entry.length > 1024)) {
    throw StateError('V2 debug recovery trace exceeds its bounds.');
  }
  return entries.cast<String>();
}

Uint8List _qualifierFromSnapshot(Map<Object?, Object?> snapshot) {
  Uint8List token(String key) {
    final value = snapshot[key];
    if (value is! Uint8List || value.length != 16) {
      throw StateError('V2 $key must be exactly 16 bytes.');
    }
    return value;
  }

  return Uint8List.fromList(<int>[
    ...token('nativeStreamToken'),
    ...token('workerBindingToken'),
  ]);
}

bool _bytesEqual(Uint8List left, Uint8List right) {
  if (left.length != right.length) return false;
  for (var index = 0; index < left.length; index++) {
    if (left[index] != right[index]) return false;
  }
  return true;
}

Map<Object?, Object?> _teardownReceipt(Object? raw) {
  if (raw is! Map) {
    throw StateError('V2 binding returned no teardown receipt.');
  }
  final receipt = Map<Object?, Object?>.from(raw);
  if (receipt['closedResources'] is! num) {
    throw StateError('V2 teardown receipt omitted closed resources.');
  }
  const beforeAfterKeys = <String>[
    'closedResourcesBefore',
    'closedResourcesAfter',
    'handlerCountBefore',
    'handlerCountAfter',
    'callbackCountBefore',
    'callbackCountAfter',
    'executorCountBefore',
    'executorCountAfter',
    'timeoutSchedulerCountBefore',
    'timeoutSchedulerCountAfter',
    'ownedResourceCountBefore',
    'ownedResourceCountAfter',
  ];
  const balanceKeys = <String>[
    'handlerBalance',
    'callbackBalance',
    'executorBalance',
    'timeoutSchedulerBalance',
    'ownedResourceBalance',
  ];
  final hasBalances =
      <String>[...beforeAfterKeys, ...balanceKeys].any(receipt.containsKey);
  if (hasBalances) {
    int exact(String key, {required bool nonNegative}) {
      final value = receipt[key];
      if (value is! num ||
          !value.isFinite ||
          value != value.truncate() ||
          value.abs() > 0x7fffffffffffffff ||
          (nonNegative && value < 0)) {
        throw StateError('V2 teardown receipt field $key is invalid.');
      }
      return value.toInt();
    }

    for (final key in beforeAfterKeys) {
      exact(key, nonNegative: true);
    }
    for (final kind in const <String>[
      'handler',
      'callback',
      'executor',
      'timeoutScheduler',
      'ownedResource',
    ]) {
      final before = exact('${kind}CountBefore', nonNegative: true);
      final after = exact('${kind}CountAfter', nonNegative: true);
      final balance = exact('${kind}Balance', nonNegative: false);
      if (balance != after - before) {
        throw StateError(
            'V2 teardown receipt field ${kind}Balance is incoherent.');
      }
    }
    if (exact('closedResourcesAfter', nonNegative: true) !=
        (receipt['closedResources'] as num).toInt()) {
      throw StateError('V2 teardown closed-resource totals are incoherent.');
    }
  }
  return receipt;
}

/// Background-isolate owner for one production V2 control/stream binding.
///
/// Both channels use the background messenger directly. The root isolate is
/// needed only to supply Flutter's registration token and never sees ordinary
/// stream request or response bytes.
final class ARVisibilityGridV2WorkerBinding {
  ARVisibilityGridV2WorkerBinding._({
    required MethodChannel controlChannel,
    required BasicMessageChannel<ByteData?> streamChannel,
    required Uint8List cleanupLease,
  })  : _controlChannel = controlChannel,
        _streamChannel = streamChannel,
        _cleanupLease = cleanupLease;

  /// Creates a worker binding using Flutter's background messenger with one
  /// fresh, connection-owned 16-byte Dart cleanup lease available before any
  /// native reply. The lease is never replaced by a later binding generation.
  ///
  /// The control channel accepts `start`, `beginCheckpoint`,
  /// `releaseCheckpoint`, and `stop`; each method takes one [Uint8List] and
  /// returns one [Uint8List]. Public `dispose` takes no argument and supplies
  /// its claimed lease internally. Hidden `claimBindingLease` receives exactly
  /// the connection's 16-byte Dart cleanup lease, atomically binds it to the
  /// exact current native binding identity, and returns that binding's bounded
  /// scalar snapshot only after the claim is owned. Hidden `bindingSnapshot`
  /// remains a no-argument telemetry query. `disposeBinding` and independent
  /// `abandonBinding` receive that exact same 16-byte lease; both replay only
  /// its bounded old terminal, while a winning abandon installs a fresh
  /// identity. The stream
  /// channel accepts one binary [ByteData] envelope and returns one binary
  /// [ByteData] envelope, or null when the native side has no response.
  ///
  /// [captureCleanupAuthority] atomically claims that lease against the exact
  /// native current identity before its scalar snapshot reply is queued. The
  /// attempt owner must retain its [close], [disposeAndSnapshot], and
  /// [abandonAndSnapshot] callbacks before awaiting the claim response. Thus a
  /// stalled or malformed claim reply still leaves native cleanup reachable
  /// for that exact attempt, while a delayed old cleanup only replays its
  /// bounded terminal and cannot mutate or rotate a replacement. Native
  /// stale/unclaimed rejection is a [PlatformException] terminal, not a local
  /// [StateError]. The optional channels are a host-test transport seam and
  /// must be supplied together.
  factory ARVisibilityGridV2WorkerBinding.connect({
    required ui.RootIsolateToken rootIsolateToken,
    required int viewId,
    MethodChannel? controlChannel,
    BasicMessageChannel<ByteData?>? streamChannel,
  }) {
    if ((controlChannel == null) != (streamChannel == null)) {
      throw ArgumentError('Worker binding test channels must be paired.');
    }
    if (controlChannel == null) {
      BackgroundIsolateBinaryMessenger.ensureInitialized(rootIsolateToken);
      final messenger = BackgroundIsolateBinaryMessenger.instance;
      controlChannel = MethodChannel(
        'visibility_grid_v2_control_$viewId',
        const StandardMethodCodec(),
        messenger,
      );
      streamChannel = BasicMessageChannel<ByteData?>(
        'visibility_surface_stream_$viewId',
        const BinaryCodec(),
        binaryMessenger: messenger,
      );
    }
    return ARVisibilityGridV2WorkerBinding._(
      controlChannel: controlChannel,
      streamChannel: streamChannel!,
      cleanupLease: Uint8List.fromList(
        List<int>.generate(16, (_) => Random.secure().nextInt(256)),
      ),
    );
  }

  /// Atomically claims this connection's immutable 16-byte native cleanup
  /// lease with `claimBindingLease`.
  ///
  /// Callers must attach [close], [disposeAndSnapshot], and
  /// [abandonAndSnapshot] to their attempt owner before awaiting this method.
  /// The request is exactly the fresh connection lease, and native owns that
  /// lease against the exact current binding before the bounded scalar snapshot
  /// reply is queued. Cleanup therefore remains available even when this
  /// Future stalls or its reply is malformed. Native stale/unclaimed
  /// rejection propagates as [PlatformException]; an unavailable channel
  /// propagates [MissingPluginException]. [StateError] is reserved for a
  /// malformed claim snapshot.
  Future<void> captureCleanupAuthority() async {
    final response = await _controlChannel.invokeMethod<Object?>(
      'claimBindingLease',
      _cleanupLease,
    );
    if (response is! Map) {
      throw StateError('V2 binding lease claim returned a non-map snapshot.');
    }
    bindSnapshot(Map<Object?, Object?>.from(response));
  }

  final MethodChannel _controlChannel;
  final BasicMessageChannel<ByteData?> _streamChannel;
  final Uint8List _cleanupLease;
  Uint8List? _bindingQualifier;
  bool _closed = false;
  bool _abandoned = false;
  Future<Map<Object?, Object?>>? _disposeFuture;
  Future<Map<Object?, Object?>>? _abandonFuture;

  /// Stable sendable identity for this connection-owned cleanup lease.
  String get cleanupLeaseIdentity => _cleanupLease
      .map((byte) => byte.toRadixString(16).padLeft(2, '0'))
      .join();

  /// Claims the bounded native binding snapshot returned by
  /// `claimBindingLease` or `bindingSnapshot`.
  /// The first valid snapshot owns this Dart binding for its lifetime. Reusing
  /// the same qualifier is idempotent; a different later qualifier throws
  /// [StateError]. Either token being absent or not a 16-byte [Uint8List] also
  /// throws [StateError].
  void bindSnapshot(Map<Object?, Object?> snapshot) {
    final qualifier = _qualifierFromSnapshot(snapshot);
    final existing = _bindingQualifier;
    if (existing != null) {
      if (!_bytesEqual(existing, qualifier)) {
        throw StateError('V2 binding qualifier cannot be replaced.');
      }
      return;
    }
    _bindingQualifier = qualifier;
  }

  /// Sends START through the worker-owned control channel.
  /// Throws [StateError] when closed or for a non-byte response, and
  /// propagates [PlatformException] or [MissingPluginException].
  Future<Uint8List> start(Uint8List request) =>
      _control(_V2Control.start, request);

  /// Begins a checkpoint through the worker-owned control channel.
  /// Throws [StateError] when closed or for a non-byte response, and
  /// propagates [PlatformException] or [MissingPluginException].
  Future<Uint8List> beginCheckpoint(Uint8List request) =>
      _control(_V2Control.beginCheckpoint, request);

  /// Releases a checkpoint through the worker-owned control channel.
  /// Throws [StateError] when closed or for a non-byte response, and
  /// propagates [PlatformException] or [MissingPluginException].
  Future<Uint8List> releaseCheckpoint(Uint8List request) =>
      _control(_V2Control.releaseCheckpoint, request);

  /// Stops the worker-owned native stream.
  /// Throws [StateError] when closed or for a non-byte response, and
  /// propagates [PlatformException] or [MissingPluginException].
  Future<Uint8List> stop(Uint8List request) =>
      _control(_V2Control.stop, request);

  /// Arms the debug-only first-exchange stall used by the Android T6
  /// recovery tracer. Throws [PlatformException] or
  /// [MissingPluginException] when unavailable or non-debuggable and
  /// [StateError] for a malformed arm receipt.
  Future<bool> configureDebugV2ExchangeStall() async {
    _ensureOpen();
    return _armV2DebugControl(
      _controlChannel,
      'configureDebugV2ExchangeStall',
      'V2 debug stall returned an invalid arm receipt.',
    );
  }

  /// Arms the debug-only COMMIT-publication stall used by the Android T6
  /// recovery tracer. The latch is taken after native publishes revision 1
  /// and before its platform reply is delivered.
  Future<bool> configureDebugV2CommitPublicationStall() async {
    _ensureOpen();
    return _armV2DebugControl(
      _controlChannel,
      'configureDebugV2CommitPublicationStall',
      'V2 debug COMMIT stall returned an invalid arm receipt.',
    );
  }

  /// Arms the debug-only exchange-3 ACK stall used by the Android T6
  /// known-COMMIT recovery tracer. The native latch is taken after request
  /// sequence 3 is decoded and before its response can be published.
  Future<bool> configureDebugV2AcknowledgementStall() async {
    _ensureOpen();
    return _armV2DebugControl(
      _controlChannel,
      'configureDebugV2AckStall',
      'V2 debug ACK stall returned an invalid arm receipt.',
    );
  }

  /// Arms the debug-only known-COMMIT then restored-START recovery cut.
  Future<bool> configureDebugV2RestoredStartStall() async {
    _ensureOpen();
    return _armV2DebugControl(
      _controlChannel,
      'configureDebugV2RestoredStartStall',
      'V2 debug restored START stall returned an invalid arm receipt.',
    );
  }

  /// Reads the bounded debug recovery trace from native. At most 64 scalar
  /// labels of at most 1024 characters cross the channel. Throws
  /// [PlatformException], [MissingPluginException], or [StateError] for the
  /// documented unavailable/malformed cases. This is not a product API.
  Future<List<String>> getDebugV2RecoveryTrace() async {
    _ensureOpen();
    return _readV2DebugRecoveryTrace(_controlChannel);
  }

  /// Queries the bounded native receipt for one outcome-unknown COMMIT.
  ///
  /// The query is read-only and identity-qualified by both this fresh
  /// binding's private qualifier and [query]'s old binding tokens. It returns
  /// the exact COMMIT baseline or an abandon-wins zero baseline, with no
  /// ordinary surface payload. A teardown receipt is cleanup evidence only;
  /// it is not outcome authority. Stale/mismatched qualification or channel
  /// failure is reported as [PlatformException] or
  /// [MissingPluginException]; malformed query fields throw [ArgumentError]
  /// before invocation, and malformed scalar replies throw [StateError].
  Future<ARVisibilityGridV2CommitReceipt> queryCommitReceipt(
    ARVisibilityGridV2CommitReceiptQuery query,
  ) async {
    _ensureOpen();
    final response = await _controlChannel.invokeMethod<Object?>(
      'queryCommitReceipt',
      <String, Object?>{
        ...query.toMap(),
        'currentBindingQualifier': _requireQualifier(),
      },
    );
    return _exactCommitReceipt(response, query);
  }

  /// Throws [StateError] if closed or native returns a non-byte response.
  /// Throws [PlatformException] or [MissingPluginException] on channel failure.
  Future<Uint8List> _control(_V2Control operation, Uint8List request) async {
    _ensureOpen();
    final response = await _controlChannel.invokeMethod<Object?>(
      operation.method,
      _qualify(request),
    );
    if (response is! Uint8List) {
      throw StateError('V2 control returned a non-byte response.');
    }
    return _authenticate(response);
  }

  /// Exchanges one binary stream envelope and returns its binary response.
  /// Throws [ARVisibilitySurfaceStreamUnknownOutcome] when the bounded exchange
  /// times out. Throws [StateError] when closed, native returns no response, or
  /// the response qualifier is absent/stale. Propagates [PlatformException] or
  /// [MissingPluginException] on other channel failures.
  Future<Uint8List> exchange(
    Uint8List request, {
    Duration? timeout,
  }) async {
    _ensureOpen();
    final operation = _streamChannel.send(
      ByteData.sublistView(_qualify(request)),
    );
    ByteData? response;
    try {
      response =
          timeout == null ? await operation : await operation.timeout(timeout);
    } on TimeoutException {
      // A timeout does not cancel the engine invocation. Fence this binding
      // before its caller can attempt another request; the late reply is
      // intentionally ignored by the abandoned binding.
      _abandoned = true;
      throw const ARVisibilitySurfaceStreamUnknownOutcome();
    }
    if (response == null) {
      throw StateError('V2 stream returned no response.');
    }
    return _authenticate(Uint8List.fromList(
      response.buffer.asUint8List(
        response.offsetInBytes,
        response.lengthInBytes,
      ),
    ));
  }

  /// Invokes native `bindingSnapshot` and reads bounded scalar telemetry on
  /// the same native executor as
  /// control and exchange operations. No surface payload bytes are returned.
  /// The no-argument call returns a scalar [Map] snapshot.
  ///
  /// Exact keys/types: `bindingGeneration`, `streamToken`, `acceptedControls`,
  /// `closedResources`, `sessionGeneration`, `groupGeneration`,
  /// `coverageEpoch`, `viewId`, `viewGeneration`, `lifecycleSequence`, and
  /// `operationGeneration` are [int]; `initialTransactionQueued` and
  /// `disposed` are [bool]; `controlRequestId`, `sessionId`, and
  /// `captureGroupId` are nullable [String]; `arSessionIdentity`,
  /// `viewInstanceId`, `nativeStreamToken`, and `workerBindingToken` are
  /// 16-byte [Uint8List]; `executorTrace` is `List<String>`.
  /// Throws [StateError] for a closed binding or non-map result and propagates
  /// [PlatformException] or [MissingPluginException] on channel failure.
  Future<Map<Object?, Object?>> snapshot() async {
    _ensureOpen();
    final response = await _controlChannel.invokeMethod<Object?>(
      'bindingSnapshot',
    );
    if (response is! Map) {
      throw StateError('V2 binding returned a non-map snapshot.');
    }
    return Map<Object?, Object?>.from(response);
  }

  /// Locally fences future control and exchange calls without invoking native.
  void close() => _closed = true;

  /// Fences this worker's native binding before the worker isolate exits.
  ///
  /// The Android owner replaces the per-view stream/control lifecycle after
  /// this call, so a subsequent group can negotiate a new binding generation
  /// without reusing the old stream token or transaction cursor.
  /// The no-argument method internally sends this worker's same claimed
  /// 16-byte cleanup lease. Native stale/unclaimed rejection is a
  /// [PlatformException] and leaves any replacement generation, tokens,
  /// callbacks, lifecycle, and resources unchanged. An unavailable channel
  /// propagates [MissingPluginException]. [StateError] is reserved for
  /// malformed or contradictory teardown evidence.
  Future<void> dispose() async {
    await disposeAndSnapshot();
  }

  /// Disposes the native binding and reads the post-disposal scalar snapshot
  /// before fencing this Dart object. The snapshot includes closed-resource
  /// and lifecycle evidence for teardown receipts. Handler, callback, serial
  /// executor, timeout-scheduler, and aggregate owned-resource fields are
  /// state-derived `Before`/`After` counts; each `Balance` is `After - Before`.
  /// The no-argument method internally sends this worker's same claimed
  /// 16-byte cleanup lease. Native stale/unclaimed rejection is a
  /// [PlatformException] and leaves any replacement generation, tokens,
  /// callbacks, lifecycle, and resources unchanged. An unavailable channel
  /// propagates [MissingPluginException]. [StateError] is reserved for
  /// malformed or contradictory teardown evidence, including missing or
  /// incoherent closure/ledger fields.
  Future<Map<Object?, Object?>> disposeAndSnapshot() async {
    final existing = _disposeFuture;
    if (existing != null) return existing;
    final future = () async {
      try {
        final response = await _controlChannel.invokeMethod<Object?>(
          'disposeBinding',
          _cleanupLease,
        );
        return _teardownReceipt(response);
      } finally {
        _closed = true;
      }
    }();
    _disposeFuture = future;
    return future;
  }

  /// Uses the independent native abandon/fence path when the serial dispose
  /// call is stalled behind an admitted invocation. Native returns the old
  /// binding's teardown receipt and installs a fresh identity on the channel;
  /// this object is permanently closed afterward. The argument-free method
  /// internally sends this worker's same claimed 16-byte cleanup lease. Native
  /// stale/unclaimed rejection is a [PlatformException] and leaves the
  /// replacement generation, tokens, lifecycle, callbacks, and resources
  /// unchanged. An unavailable channel propagates [MissingPluginException].
  /// [StateError] is reserved for malformed or contradictory teardown
  /// evidence, including missing or incoherent closure/ledger fields.
  Future<Map<Object?, Object?>> abandonAndSnapshot() async {
    final existing = _abandonFuture;
    if (existing != null) return existing;
    final future = () async {
      try {
        final response = await _controlChannel.invokeMethod<Object?>(
          'abandonBinding',
          _cleanupLease,
        );
        return _teardownReceipt(response);
      } finally {
        _closed = true;
        _abandoned = true;
      }
    }();
    _abandonFuture = future;
    return future;
  }

  void _ensureOpen() {
    if (_closed) throw StateError('V2 worker binding is closed.');
    if (_abandoned) {
      throw const ARVisibilitySurfaceStreamUnknownOutcome();
    }
  }

  Uint8List _requireQualifier() =>
      _bindingQualifier ??
      (throw StateError('V2 worker binding has not claimed native tokens.'));

  Uint8List _qualify(Uint8List payload) => Uint8List.fromList(<int>[
        ..._requireQualifier(),
        ...payload,
      ]);

  Uint8List _authenticate(Uint8List response) {
    final qualifier = _requireQualifier();
    if (response.length < qualifier.length) {
      throw StateError('V2 response omitted its binding qualifier.');
    }
    for (var index = 0; index < qualifier.length; index++) {
      if (response[index] != qualifier[index]) {
        throw StateError('V2 response used a stale binding qualifier.');
      }
    }
    return Uint8List.fromList(response.sublist(qualifier.length));
  }
}

enum _V2Control {
  start('start'),
  beginCheckpoint('beginCheckpoint'),
  releaseCheckpoint('releaseCheckpoint'),
  stop('stop');

  const _V2Control(this.method);
  final String method;
}
