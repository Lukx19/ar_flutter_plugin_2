import 'package:ar_flutter_plugin_2/managers/ar_visibility_grid_v2_control.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
      'connection-owned disposal rejects stale replacement and returns receipt',
      () async {
    const channel = MethodChannel('visibility_grid_v2_control_41');
    final methods = <String>[];
    Uint8List? disposalQualifier;
    var currentQualifier = <int>[
      ...List<int>.generate(16, (i) => i),
      ...List<int>.generate(16, (i) => i + 16),
    ];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      methods.add(call.method);
      if (call.method == 'disposeBinding') {
        final qualifier = List<int>.from(call.arguments! as Uint8List);
        if (!_sameBytes(qualifier, currentQualifier)) {
          throw PlatformException(code: 'VG_STREAM_BINDING_ABANDONED');
        }
        disposalQualifier = Uint8List.fromList(qualifier);
        return <String, Object>{
          'closedResources': 3,
          'disposed': false,
        };
      }
      throw PlatformException(code: 'unsupported');
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );

    final stale = ARVisibilityGridV2Control(
      41,
      initialBindingSnapshot: <Object?, Object?>{
        'nativeStreamToken':
            Uint8List.fromList(List<int>.generate(16, (i) => i)),
        'workerBindingToken': Uint8List.fromList(
          List<int>.generate(16, (i) => i + 16),
        ),
      },
    );
    currentQualifier = <int>[
      ...List<int>.generate(16, (i) => i + 32),
      ...List<int>.generate(16, (i) => i + 48),
    ];

    await expectLater(
      stale.dispose(),
      throwsA(
        isA<PlatformException>().having(
          (error) => error.code,
          'code',
          'VG_STREAM_BINDING_ABANDONED',
        ),
      ),
    );

    final current = ARVisibilityGridV2Control(
      41,
      initialBindingSnapshot: <Object?, Object?>{
        'nativeStreamToken': Uint8List.fromList(
          List<int>.generate(16, (i) => i + 32),
        ),
        'workerBindingToken': Uint8List.fromList(
          List<int>.generate(16, (i) => i + 48),
        ),
      },
    );
    final receipt = await current.dispose();

    expect(methods, <String>['disposeBinding', 'disposeBinding']);
    expect(disposalQualifier, currentQualifier);
    expect(receipt['closedResources'], 3);
    expect(receipt['disposed'], false);
  });

  test('public receipt query returns bounded commit baseline and qualifier',
      () async {
    const channel = MethodChannel('visibility_grid_v2_control_83');
    final currentQualifier = Uint8List.fromList(
      List<int>.generate(32, (index) => index + 1),
    );
    final oldNative = Uint8List.fromList(List<int>.generate(16, (i) => i + 33));
    final oldWorker = Uint8List.fromList(List<int>.generate(16, (i) => i + 49));
    Uint8List? observedCurrentQualifier;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'queryCommitReceipt');
      final arguments = Map<Object?, Object?>.from(call.arguments! as Map);
      observedCurrentQualifier =
          arguments['currentBindingQualifier'] as Uint8List;
      return <String, Object?>{
        'decision': 'commit',
        'controlRequestId': '0102030405064708890a0b0c0d0e0f10',
        'sessionId': '1112131415164718991a1b1c1d1e1f20',
        'captureGroupId': '2122232425264728a92a2b2c2d2e2f30',
        'sessionGeneration': 3,
        'groupGeneration': 4,
        'nativeStreamToken': oldNative,
        'workerBindingToken': oldWorker,
        'streamToken': 7,
        'requestSequence': 2,
        'transactionId': 1,
        'targetGeometryRevision': 1,
        'targetLineageRevision': 1,
        'rootIsolateSurfaceBytes': 0,
        'baseline': <String, Object?>{
          'transactionId': 1,
          'geometryRevision': 1,
          'lineageRevision': 1,
          'styleRevision': 0,
          'evidenceRevision': 0,
          'captureRevision': 0,
          'coverageRevision': 0,
          'producedStyleRevision': 0,
          'regionManifestRevision': 0,
          'schemaRootRevision': 0,
          'nextSurfaceIdHighWater': 0,
          'schemaRootHashIdentity': '',
          'manifestRootHashIdentity': '',
          'groupFrameConvention': 1,
          'matrixConvention': 1,
          'directionConvention': 1,
          'normalEncoding': 1,
          'groupFromWorldIdentity': 'identity',
          'worldFromGroupIdentity': 'identity',
        },
      };
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );

    final control = ARVisibilityGridV2Control(
      83,
      channel: channel,
      initialBindingSnapshot: <Object?, Object?>{
        'nativeStreamToken': currentQualifier.sublist(0, 16),
        'workerBindingToken': currentQualifier.sublist(16),
      },
    );
    final receipt = await control.queryCommitReceipt(
      ARVisibilityGridV2CommitReceiptQuery(
        controlRequestId: '0102030405064708890a0b0c0d0e0f10',
        sessionId: '1112131415164718991a1b1c1d1e1f20',
        captureGroupId: '2122232425264728a92a2b2c2d2e2f30',
        sessionGeneration: 3,
        groupGeneration: 4,
        nativeStreamToken: oldNative,
        workerBindingToken: oldWorker,
        streamToken: 7,
        requestSequence: 2,
        transactionId: 1,
        targetGeometryRevision: 1,
        targetLineageRevision: 1,
      ),
    );
    expect(receipt.committed, isTrue);
    expect(receipt.baseline.geometryRevision, 1);
    expect(receipt.rootIsolateSurfaceBytes, 0);
    expect(observedCurrentQualifier, currentQualifier);
  });

  test('public query rejects malformed identity and bounds before channel', () {
    final token = Uint8List(16);
    ARVisibilityGridV2CommitReceiptQuery query({
      String controlRequestId = '0102030405064708890a0b0c0d0e0f10',
      int streamToken = 7,
    }) =>
        ARVisibilityGridV2CommitReceiptQuery(
          controlRequestId: controlRequestId,
          sessionId: '1112131415164718991a1b1c1d1e1f20',
          captureGroupId: '2122232425264728a92a2b2c2d2e2f30',
          sessionGeneration: 3,
          groupGeneration: 4,
          nativeStreamToken: token,
          workerBindingToken: token,
          streamToken: streamToken,
          requestSequence: 2,
          transactionId: 1,
          targetGeometryRevision: 1,
          targetLineageRevision: 1,
        );

    expect(() => query(controlRequestId: 'not-a-uuid'), throwsArgumentError);
    expect(() => query(streamToken: 0), throwsArgumentError);
    expect(
      () => ARVisibilityGridV2CommitReceiptQuery(
        controlRequestId: '0102030405064708890a0b0c0d0e0f10',
        sessionId: '1112131415164718991a1b1c1d1e1f20',
        captureGroupId: '2122232425264728a92a2b2c2d2e2f30',
        sessionGeneration: 3,
        groupGeneration: 4,
        nativeStreamToken: Uint8List(15),
        workerBindingToken: token,
        streamToken: 7,
        requestSequence: 2,
        transactionId: 1,
        targetGeometryRevision: 1,
        targetLineageRevision: 1,
      ),
      throwsArgumentError,
    );

    final mutable = query();
    mutable.nativeStreamToken.fillRange(0, 16, 1);
    expect(mutable.toMap()['nativeStreamToken'], isA<Uint8List>());
  });

  test('abandon receipt requires every canonical zero baseline field', () {
    final receipt = ARVisibilityGridV2CommitReceipt.fromMap(
      _receiptMap(decision: 'abandon', baseline: _zeroBaseline()),
    );
    expect(receipt.committed, isFalse);

    for (final mutation in <MapEntry<String, Object?>>[
      const MapEntry('styleRevision', 1),
      const MapEntry('evidenceRevision', 1),
      const MapEntry('nextSurfaceIdHighWater', 1),
      const MapEntry('schemaRootHashIdentity', 'not-zero'),
      const MapEntry('groupFrameConvention', 0),
      const MapEntry('normalEncoding', 0),
      const MapEntry('groupFromWorldIdentity', 'not-identity'),
    ]) {
      expect(
        () => ARVisibilityGridV2CommitReceipt.fromMap(
          _receiptMap(
            decision: 'abandon',
            baseline: <String, Object?>{
              ..._zeroBaseline(),
              mutation.key: mutation.value,
            },
          ),
        ),
        throwsStateError,
        reason: mutation.key,
      );
    }
  });
}

Map<String, Object?> _receiptMap({
  required String decision,
  required Map<String, Object?> baseline,
}) =>
    <String, Object?>{
      'decision': decision,
      'controlRequestId': '0102030405064708890a0b0c0d0e0f10',
      'sessionId': '1112131415164718991a1b1c1d1e1f20',
      'captureGroupId': '2122232425264728a92a2b2c2d2e2f30',
      'sessionGeneration': 3,
      'groupGeneration': 4,
      'nativeStreamToken': Uint8List(16),
      'workerBindingToken': Uint8List(16),
      'streamToken': 7,
      'requestSequence': 2,
      'transactionId': 1,
      'targetGeometryRevision': 1,
      'targetLineageRevision': 1,
      'rootIsolateSurfaceBytes': 0,
      'baseline': baseline,
    };

Map<String, Object?> _zeroBaseline() => <String, Object?>{
      'transactionId': 0,
      'geometryRevision': 0,
      'lineageRevision': 0,
      'styleRevision': 0,
      'evidenceRevision': 0,
      'captureRevision': 0,
      'coverageRevision': 0,
      'producedStyleRevision': 0,
      'regionManifestRevision': 0,
      'schemaRootRevision': 0,
      'nextSurfaceIdHighWater': 0,
      'schemaRootHashIdentity': '',
      'manifestRootHashIdentity': '',
      'groupFrameConvention': 1,
      'matrixConvention': 1,
      'directionConvention': 1,
      'normalEncoding': 1,
      'groupFromWorldIdentity':
          '3ff0000000000000,0,0,0,0,3ff0000000000000,0,0,0,0,'
              '3ff0000000000000,0,0,0,0,3ff0000000000000',
      'worldFromGroupIdentity':
          '3ff0000000000000,0,0,0,0,3ff0000000000000,0,0,0,0,'
              '3ff0000000000000,0,0,0,0,3ff0000000000000',
    };

bool _sameBytes(List<int> left, List<int> right) {
  if (left.length != right.length) return false;
  for (var index = 0; index < left.length; index++) {
    if (left[index] != right[index]) return false;
  }
  return true;
}
