import 'dart:io';
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/visibility_grid_contract/visibility_grid_contract.dart';
import 'package:ar_flutter_plugin_2/visibility_grid_shell/visibility_grid_shell.dart';
import 'package:flutter_test/flutter_test.dart';

Uint8List _golden(String name) =>
    File('test_assets/visibility_grid/$name').readAsBytesSync();

ByteData _status(int code) => ByteData(4)..setInt32(0, code, Endian.little);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMessageHandler(VisibilityGridChannels.control, null);
    messenger.setMockMessageHandler(VisibilityGridChannels.ingest, null);
  });

  test('start sends the shell control start golden and decodes the status',
      () async {
    final sent = <Uint8List>[];
    messenger.setMockMessageHandler(VisibilityGridChannels.control,
        (message) async {
      sent.add(Uint8List.fromList(message!.buffer
          .asUint8List(message.offsetInBytes, message.lengthInBytes)));
      return _status(NativeStatusCode.ok.code);
    });
    final control = VisibilityGridShellControl(messenger: messenger);
    final status = await control.start(const VisibilityGridShellStart(
      depthSourcePreference: DepthSourcePreferenceCode.depth,
      markerDetection: true,
      pointCloud: true,
      markerDetectionHz: 8,
      markerSizeMetres: 0.12,
      groupEpoch: 3,
    ));
    expect(status, NativeStatusCode.ok);
    expect(sent.single, orderedEquals(_golden('shell_control_start.bin')));
  });

  test('stop, marker detection and recording messages validate', () async {
    final kinds = <int>[];
    final flags = <int>[];
    final view = ShellControlView();
    messenger.setMockMessageHandler(VisibilityGridChannels.control,
        (message) async {
      view.attach(message!);
      expect(view.validate(message.lengthInBytes), ContractStatus.ok);
      kinds.add(view.kind);
      flags.add(view.flags);
      return _status(view.kind == ShellControlKind.setRecording
          ? NativeStatusCode.recordingWriteFailed.code
          : NativeStatusCode.ok.code);
    });
    final control = VisibilityGridShellControl(messenger: messenger);
    expect(await control.stop(), NativeStatusCode.ok);
    expect(await control.setMarkerDetection(enabled: true, hz: 5),
        NativeStatusCode.ok);
    expect(
        await control.setRecording(true), NativeStatusCode.recordingWriteFailed);
    expect(kinds, [
      ShellControlKind.stop,
      ShellControlKind.setMarkerDetection,
      ShellControlKind.setRecording,
    ]);
    expect(flags, [
      0,
      ShellControlLayout.flagMarkerDetection,
      ShellControlLayout.flagRecording,
    ]);
  });

  test('unreadable status replies decode as malformed', () {
    expect(VisibilityGridShellControl.decodeStatus(null),
        NativeStatusCode.contractMessageMalformed);
    expect(VisibilityGridShellControl.decodeStatus(_status(9999)),
        NativeStatusCode.contractMessageMalformed);
  });

  test('pull sends the pull request golden and returns the reply', () async {
    final idle = _golden('ingest_idle.bin');
    Uint8List? request;
    messenger.setMockMessageHandler(VisibilityGridChannels.ingest,
        (message) async {
      request = Uint8List.fromList(message!.buffer
          .asUint8List(message.offsetInBytes, message.lengthInBytes));
      return ByteData.sublistView(idle);
    });
    final client = VisibilityGridIngestPullClient(messenger);
    // The golden is the kinds 1-6 request; the default also accepts kind 8.
    final reply = await client.pull(0x7e);
    expect(request, orderedEquals(_golden('ingest_pull_request.bin')));
    await client.pull();
    expect(request![6], 0x7e);
    expect(request![7], 1 << (IngestKind.photoTiePoints - 8));
    final view = IngestMessageView()..attach(reply);
    expect(view.validate(reply.lengthInBytes), ContractStatus.ok);
    expect(view.kind, IngestKind.idle);
    await client.close();
    await expectLater(client.pull(), throwsStateError);
  });

  test('a second concurrent pull is refused', () async {
    messenger.setMockMessageHandler(VisibilityGridChannels.ingest,
        (message) async => ByteData.sublistView(_golden('ingest_idle.bin')));
    final client = VisibilityGridIngestPullClient(messenger);
    final first = client.pull(1 << IngestKind.depthImage);
    await expectLater(client.pull(), throwsStateError);
    await first;
  });
}
