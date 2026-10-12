import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/visibility_grid_contract/visibility_grid_contract.dart';
import 'package:flutter_test/flutter_test.dart';

import 'contract_fixtures.dart';

final RendererDeltaView _delta = RendererDeltaView();

Uint8List _message(int kind, int count, {int firstSlot = 0}) {
  final size = RendererDeltaKind.recordBytes(kind);
  final bytes = Uint8List(RendererDeltaLayout.headerBytes + count * size);
  _delta
    ..attach(ByteData.sublistView(bytes))
    ..begin(kind)
    ..gridVersion = 41
    ..firstSlot = firstSlot
    ..recordCount = count
    ..bufferCapacity = 4096
    ..brickKey = 0x0000123400005678;
  return bytes;
}

Uint8List buildSurfaceSlice() {
  final bytes = _message(RendererDeltaKind.surfaceSlice, 2, firstSlot: 64);
  final data = ByteData.sublistView(bytes);
  final record = SurfaceInstanceRecordView();
  final bins = Uint8List.fromList(List<int>.generate(24, (i) => i % 16));
  for (var i = 0; i < 2; i++) {
    record.attach(data, _delta.recordOffset(i));
    record.write(
      x: 1.0 + i,
      y: 0.5,
      z: -2.25,
      nx: 0,
      ny: i == 0 ? 1 : 0,
      nz: i == 0 ? 0 : -1,
      size: 0.1,
      source: 1,
      flags: SurfaceInstanceLayout.flagPublished,
      coverage: 128,
      completion: 2,
      confidence: 200,
      age: 3,
      bins: bins,
      stableId: 9000 + i,
    );
  }
  return bytes;
}

Uint8List buildBrickSummaries() {
  final bytes = _message(RendererDeltaKind.brickSummaries, 2, firstSlot: 5);
  final data = ByteData.sublistView(bytes);
  final view = BrickSummaryRecordView();
  view.attach(data, _delta.recordOffset(0));
  view.write(-3, 1, 7, 120, 40, 90, BrickSummaryLayout.residencyResident, 1000);
  view.attach(data, _delta.recordOffset(1));
  view.write(4, 0, -2, 70000, 3, 10, BrickSummaryLayout.residencyOnDisk, 77);
  return bytes;
}

Uint8List buildOccupancyMasks() {
  final bytes = _message(RendererDeltaKind.occupancyMasks, 1, firstSlot: 5);
  final data = ByteData.sublistView(bytes);
  final at = _delta.recordOffset(0);
  OccupancyMaskLayout.set(data, at, 0, 0, 0);
  OccupancyMaskLayout.set(data, at, 7, 7, 7);
  OccupancyMaskLayout.set(data, at, 3, 2, 1);
  return bytes;
}

Uint8List buildRegionSummaries() {
  final bytes = _message(RendererDeltaKind.regionSummaries, 1);
  final view = RegionSummaryRecordView()
    ..attach(ByteData.sublistView(bytes), _delta.recordOffset(0));
  view.write(-1, 0, 2, 64, 5000, 180, BrickSummaryLayout.residencySummaryOnly);
  return bytes;
}

Uint8List buildCompactVoxels() {
  final bytes = _message(RendererDeltaKind.compactVoxels, 3, firstSlot: 32);
  final data = ByteData.sublistView(bytes);
  CompactVoxelLayout.write(data, _delta.recordOffset(0), -24, 8, 15, 90);
  CompactVoxelLayout.write(data, _delta.recordOffset(1), -23, 8, 15, 91);
  CompactVoxelLayout.writePadding(data, _delta.recordOffset(2));
  return bytes;
}

Uint8List buildFreeSlots() {
  final bytes = _message(RendererDeltaKind.freeSlots, 1);
  final data = ByteData.sublistView(bytes);
  final at = _delta.recordOffset(0);
  data.setUint32(at + FreeSlotRangeLayout.firstOffset, 128, Endian.little);
  data.setUint32(at + FreeSlotRangeLayout.countOffset, 16, Endian.little);
  return bytes;
}

final Map<String, Uint8List Function()> rendererGoldens = {
  'render_surface_slice.bin': buildSurfaceSlice,
  'render_brick_summaries.bin': buildBrickSummaries,
  'render_occupancy_masks.bin': buildOccupancyMasks,
  'render_region_summaries.bin': buildRegionSummaries,
  'render_compact_voxels.bin': buildCompactVoxels,
  'render_free_slots.bin': buildFreeSlots,
  'render_reset.bin': () => _message(RendererDeltaKind.reset, 0),
  'render_frame_end.bin': () => _message(RendererDeltaKind.frameEnd, 0),
};

void main() {
  group('renderer delta goldens', () {
    for (final entry in rendererGoldens.entries) {
      test('${entry.key} encodes byte-exact and validates', () {
        final bytes = entry.value();
        expectGoldenBytes(entry.key, bytes);
        final view = RendererDeltaView()..attach(ByteData.sublistView(bytes));
        expect(view.validate(bytes.length), ContractStatus.ok);
        expect(view.messageBytes, bytes.length);
        expect(view.gridVersion, 41);
      });
    }
  });

  test('record sizes and offsets match the measured renderer layout', () {
    expect(RendererDeltaLayout.headerBytes, 32);
    expect(SurfaceInstanceLayout.bytes, 40);
    expect(SurfaceInstanceLayout.sizeOffset, 16);
    expect(SurfaceInstanceLayout.binsOffset, 24);
    expect(SurfaceInstanceLayout.stableIdOffset, 36);
    expect(BrickSummaryLayout.bytes, 16);
    expect(RegionSummaryLayout.bytes, 16);
    expect(OccupancyMaskLayout.bytes, 64);
    expect(CompactVoxelLayout.bytes, 8);
    expect(
        RendererDeltaLayout.maxRecords(RendererDeltaKind.surfaceSlice), 1637);
  });

  test('surface slice golden decodes fields as the shaders read them', () {
    final data = readGolden('render_surface_slice.bin');
    final delta = RendererDeltaView()..attach(data);
    expect(delta.kind, RendererDeltaKind.surfaceSlice);
    expect(delta.firstSlot, 64);
    expect(delta.brickKey, 0x0000123400005678);
    final record = SurfaceInstanceRecordView()
      ..attach(data, delta.recordOffset(1));
    expect(record.position(0), 2.0);
    expect(record.size, closeTo(0.1, 1e-4));
    expect(record.stableId, 9001);
    expect(record.binQuality(17), 1);
    expect(record.binQuality(23), 7);
    final normal = Float64List(3);
    octahedralDecode(record.normalX, record.normalY, normal);
    expect(normal[2], closeTo(-1, 1e-4));
  });

  test('summaries, masks and compact voxels decode', () {
    final bricks = readGolden('render_brick_summaries.bin');
    final delta = RendererDeltaView()..attach(bricks);
    final brick = BrickSummaryRecordView()
      ..attach(bricks, delta.recordOffset(1));
    expect([brick.origin(0), brick.origin(2)], [4, -2]);
    expect(brick.surfaceCount, 0xffff, reason: 'saturates');
    expect(brick.residency, BrickSummaryLayout.residencyOnDisk);

    final masks = readGolden('render_occupancy_masks.bin');
    delta.attach(masks);
    final at = delta.recordOffset(0);
    expect(OccupancyMaskLayout.isSet(masks, at, 7, 7, 7), isTrue);
    expect(OccupancyMaskLayout.isSet(masks, at, 3, 2, 1), isTrue);
    expect(OccupancyMaskLayout.isSet(masks, at, 1, 2, 3), isFalse);

    final voxels = readGolden('render_compact_voxels.bin');
    delta.attach(voxels);
    expect(
        CompactVoxelLayout.isPadding(voxels, delta.recordOffset(1)), isFalse);
    expect(CompactVoxelLayout.isPadding(voxels, delta.recordOffset(2)), isTrue);
  });

  test('validation rejects oversized and unknown deltas', () {
    final bytes = buildFreeSlots();
    final view = RendererDeltaView()..attach(ByteData.sublistView(bytes));
    view.recordCount = 10000;
    expect(view.validate(bytes.length), ContractStatus.tooLarge);
    view.recordCount = 2;
    expect(view.validate(bytes.length), ContractStatus.badLength);
    view.data.setUint8(RendererDeltaLayout.kindOffset, 99);
    expect(view.validate(bytes.length), ContractStatus.badKind);
  });

  test('display mode and colour table goldens round-trip', () {
    final mode = Uint8List(DisplayModeLayout.bytes);
    final modeView = DisplayModeView()..attach(ByteData.sublistView(mode));
    modeView.write(
      baseLayer: DisplayBaseLayer.splats,
      overlayBits: DisplayOverlay.spikeBalls | DisplayOverlay.targets,
      colourAttribute: DisplayColourAttribute.completion,
      sizeRule: DisplaySizeRule.byCoverage,
      spikeNearestK: 512,
      pointSize: 6.5,
      colourTableGeneration: 3,
      minQuality: 64,
    );
    expectGoldenBytes('display_mode.bin', mode);
    expect(modeView.validate(mode.length), ContractStatus.ok);
    expect(modeView.spikeNearestK, 512);
    expect(modeView.pointSize, 6.5);
    expect(modeView.minQuality, 64);
    expect(mode.sublist(DisplayModeLayout.reservedOffset), everyElement(0));

    final table = Uint8List(ColourTableLayout.bytes);
    final tableView = ColourTableView()..attach(ByteData.sublistView(table));
    tableView.begin(DisplayColourAttribute.coverage, 3);
    for (var i = 0; i < ColourTableLayout.entries; i++) {
      tableView.setRgba(i, (i << 24) | ((255 - i) << 16) | 0x40ff);
    }
    expectGoldenBytes('colour_table.bin', table);
    expect(tableView.validate(table.length), ContractStatus.ok);
    expect(tableView.rgba(10), 0x0af540ff);
  });

  test('shell control start golden round-trips', () {
    final bytes = Uint8List(ShellControlLayout.bytes);
    final view = ShellControlView()..attach(ByteData.sublistView(bytes));
    view.write(
      kind: ShellControlKind.start,
      flags: ShellControlLayout.flagMarkerDetection |
          ShellControlLayout.flagPointCloud,
      depthSourcePreference: DepthSourcePreferenceCode.depth,
      runMode: GridRunModeCode.shadow,
      markerDetectionHz: 8,
      markerSizeMetres: 0.12,
      groupEpoch: fixtureGroupEpoch,
    );
    expectGoldenBytes('shell_control_start.bin', bytes);
    expect(view.validate(bytes.length), ContractStatus.ok);
    expect(view.markerSizeMetres, closeTo(0.12, 1e-7));
    view.data.setUint8(ShellControlLayout.kindOffset, 0);
    expect(view.validate(bytes.length), ContractStatus.badKind);
  });

  test('native status codes are unique and stable', () {
    final codes = NativeStatusCode.values.map((s) => s.code).toList();
    expect(codes.toSet().length, codes.length);
    expect(NativeStatusCode.fromCode(304),
        NativeStatusCode.markerMovedBetweenGroups);
    expect(NativeStatusCode.fromCode(9999), isNull);
  });

  test('platform shell message list names every channel', () {
    final channels = platformShellMessages.map((m) => m.channel).toSet();
    expect(
        channels,
        containsAll(<String>[
          VisibilityGridChannels.ingest,
          VisibilityGridChannels.render,
          VisibilityGridChannels.display,
          VisibilityGridChannels.control,
        ]));
    expect(PoseBatchSampleLayout.bytes, 236);
  });

  test('half and CRC helpers match reference values', () {
    expect(halfFromDouble(0.75), 0x3a00);
    expect(doubleFromHalf(0x3a00), 0.75);
    expect(
        Crc32.of(Uint8List.fromList('123456789'.codeUnits), 0, 9), 0xcbf43926);
  });
}
