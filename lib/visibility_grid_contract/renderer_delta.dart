import 'dart:typed_data';

import 'binary_support.dart';

/// Renderer delta kinds (header byte 6).
abstract final class RendererDeltaKind {
  static const int surfaceSlice = 1;
  static const int brickSummaries = 2;
  static const int occupancyMasks = 3;
  static const int regionSummaries = 4;
  static const int freeSlots = 5;
  static const int reset = 6;
  static const int frameEnd = 7;
  static const int compactVoxels = 8;

  /// Record size of [kind], 0 for kinds without records, -1 when unknown.
  static int recordBytes(int kind) => switch (kind) {
        surfaceSlice => SurfaceInstanceLayout.bytes,
        brickSummaries => BrickSummaryLayout.bytes,
        occupancyMasks => OccupancyMaskLayout.bytes,
        regionSummaries => RegionSummaryLayout.bytes,
        freeSlots => FreeSlotRangeLayout.bytes,
        reset || frameEnd => 0,
        compactVoxels => CompactVoxelLayout.bytes,
        _ => -1,
      };
}

/// 32 B renderer delta header; the whole message is at most 65,536 B.
///
/// `first slot index` addresses the record buffer of the kind: surface slots
/// for kind 1, brick slots for kinds 2 and 3 (summary and mask of one brick
/// share its slot), region slots for kind 4 and compact voxel entries for
/// kind 8. Kind 5 carries free slot ranges; kind 6 resets every buffer to
/// `buffer capacity` records; kind 7 ends a frame.
abstract final class RendererDeltaLayout {
  static final int magic = contractMagic('C3RD');
  static const int version = 1;
  static const int headerBytes = 32;
  static const int maxMessageBytes = 65536;

  static const int kindOffset = 6; // u8
  static const int gridVersionOffset = 8; // u32
  static const int firstSlotOffset = 12; // u32
  static const int recordCountOffset = 16; // u32
  static const int bufferCapacityOffset = 20; // u32
  static const int brickKeyOffset = 24; // i64

  /// Largest record count of [kind] that fits one message.
  static int maxRecords(int kind) {
    final bytes = RendererDeltaKind.recordBytes(kind);
    return bytes <= 0 ? 0 : (maxMessageBytes - headerBytes) ~/ bytes;
  }
}

/// Reusable view over a renderer delta message.
final class RendererDeltaView extends ContractRecordView {
  @override
  int get recordBytes => RendererDeltaLayout.headerBytes;

  void begin(int kind) {
    clear();
    setU32(0, RendererDeltaLayout.magic);
    setU16(4, RendererDeltaLayout.version);
    setU8(RendererDeltaLayout.kindOffset, kind);
  }

  ContractStatus validate(int available) {
    final status = validateMagicAndVersion(
        data,
        offset,
        available,
        RendererDeltaLayout.headerBytes,
        RendererDeltaLayout.magic,
        RendererDeltaLayout.version);
    if (status != ContractStatus.ok) return status;
    final size = RendererDeltaKind.recordBytes(kind);
    if (size < 0) return ContractStatus.badKind;
    final total = messageBytes;
    if (total > RendererDeltaLayout.maxMessageBytes) {
      return ContractStatus.tooLarge;
    }
    if (total > available) return ContractStatus.badLength;
    return ContractStatus.ok;
  }

  /// Header plus records of this message.
  int get messageBytes =>
      RendererDeltaLayout.headerBytes +
      recordCount * RendererDeltaKind.recordBytes(kind).clamp(0, 1 << 20);

  int get kind => u8(RendererDeltaLayout.kindOffset);
  int get gridVersion => u32(RendererDeltaLayout.gridVersionOffset);
  set gridVersion(int v) => setU32(RendererDeltaLayout.gridVersionOffset, v);
  int get firstSlot => u32(RendererDeltaLayout.firstSlotOffset);
  set firstSlot(int v) => setU32(RendererDeltaLayout.firstSlotOffset, v);
  int get recordCount => u32(RendererDeltaLayout.recordCountOffset);
  set recordCount(int v) => setU32(RendererDeltaLayout.recordCountOffset, v);
  int get bufferCapacity => u32(RendererDeltaLayout.bufferCapacityOffset);
  set bufferCapacity(int v) =>
      setU32(RendererDeltaLayout.bufferCapacityOffset, v);
  int get brickKey => i64(RendererDeltaLayout.brickKeyOffset);
  set brickKey(int v) => setI64(RendererDeltaLayout.brickKeyOffset, v);

  /// Byte offset in [data] of record [index].
  int recordOffset(int index) =>
      offset +
      RendererDeltaLayout.headerBytes +
      index * RendererDeltaKind.recordBytes(kind);
}

/// 40 B surface instance record, ordered for 4-byte attribute alignment as
/// measured by the renderer prototype: size f16 at 16, source and flags at
/// 18-19, then coverage, completion, confidence, age, then 24 bins x 4 bit
/// as three u32 words (bin b in word b ~/ 8 at bits 4 * (b % 8)), then the
/// surface stable id.
abstract final class SurfaceInstanceLayout {
  static const int bytes = 40;
  static const int positionOffset = 0; // f32 x3
  static const int normalOffset = 12; // i16 x2 octahedral snorm
  static const int sizeOffset = 16; // f16 metres
  static const int sourceOffset = 18; // u8 source bits
  static const int flagsOffset = 19; // u8
  static const int coverageOffset = 20; // u8
  static const int completionOffset = 21; // u8
  static const int confidenceOffset = 22; // u8
  static const int ageOffset = 23; // u8
  static const int binsOffset = 24; // u32 x3
  static const int stableIdOffset = 36; // u32
  static const int binCount = 24;

  static const int flagPublished = 1;
  static const int flagPaged = 2;
  static const int flagSelected = 4;
}

/// Reusable view over one surface instance record.
final class SurfaceInstanceRecordView extends ContractRecordView {
  @override
  int get recordBytes => SurfaceInstanceLayout.bytes;

  /// Writes the whole record. [bins] holds 24 qualities 0-15 from
  /// [binsOffset].
  void write({
    required double x,
    required double y,
    required double z,
    required double nx,
    required double ny,
    required double nz,
    required double size,
    required int source,
    required int flags,
    required int coverage,
    required int completion,
    required int confidence,
    required int age,
    required Uint8List bins,
    int binsOffset = 0,
    required int stableId,
  }) {
    setF32(SurfaceInstanceLayout.positionOffset, x);
    setF32(SurfaceInstanceLayout.positionOffset + 4, y);
    setF32(SurfaceInstanceLayout.positionOffset + 8, z);
    setI16(
        SurfaceInstanceLayout.normalOffset, octahedralComponent(nx, ny, nz, 0));
    setI16(SurfaceInstanceLayout.normalOffset + 2,
        octahedralComponent(nx, ny, nz, 1));
    setF16(SurfaceInstanceLayout.sizeOffset, size);
    setU8(SurfaceInstanceLayout.sourceOffset, source);
    setU8(SurfaceInstanceLayout.flagsOffset, flags);
    setU8(SurfaceInstanceLayout.coverageOffset, coverage);
    setU8(SurfaceInstanceLayout.completionOffset, completion);
    setU8(SurfaceInstanceLayout.confidenceOffset, confidence);
    setU8(SurfaceInstanceLayout.ageOffset, age);
    for (var w = 0; w < 3; w++) {
      var word = 0;
      for (var i = 0; i < 8; i++) {
        word |= (bins[binsOffset + w * 8 + i] & 15) << (4 * i);
      }
      setU32(SurfaceInstanceLayout.binsOffset + 4 * w, word);
    }
    setU32(SurfaceInstanceLayout.stableIdOffset, stableId);
  }

  double position(int axis) =>
      f32(SurfaceInstanceLayout.positionOffset + 4 * axis);
  int get normalX => i16(SurfaceInstanceLayout.normalOffset);
  int get normalY => i16(SurfaceInstanceLayout.normalOffset + 2);
  double get size => f16(SurfaceInstanceLayout.sizeOffset);
  int get source => u8(SurfaceInstanceLayout.sourceOffset);
  int get flags => u8(SurfaceInstanceLayout.flagsOffset);
  int get coverage => u8(SurfaceInstanceLayout.coverageOffset);
  int get completion => u8(SurfaceInstanceLayout.completionOffset);
  int get confidence => u8(SurfaceInstanceLayout.confidenceOffset);
  int get age => u8(SurfaceInstanceLayout.ageOffset);
  int get stableId => u32(SurfaceInstanceLayout.stableIdOffset);

  /// Quality 0-15 of direction [bin], decoded as the spike shader does.
  int binQuality(int bin) =>
      (u32(SurfaceInstanceLayout.binsOffset + 4 * (bin ~/ 8)) >>
          (4 * (bin % 8))) &
      15;
}

/// 16 B brick summary: origin i16 x3 (brick units), surface count u16,
/// complete count u16, mean coverage u8, residency u8, last update u32.
abstract final class BrickSummaryLayout {
  static const int bytes = 16;
  static const int originOffset = 0;
  static const int surfaceCountOffset = 6;
  static const int completeCountOffset = 8;
  static const int meanCoverageOffset = 10;
  static const int residencyOffset = 11;
  static const int lastUpdateOffset = 12;

  static const int residencyResident = 0;
  static const int residencyOnDisk = 1;
  static const int residencySummaryOnly = 2;
}

final class BrickSummaryRecordView extends ContractRecordView {
  @override
  int get recordBytes => BrickSummaryLayout.bytes;

  void write(int bx, int by, int bz, int surfaces, int complete,
      int meanCoverage, int residency, int lastUpdate) {
    setI16(BrickSummaryLayout.originOffset, bx);
    setI16(BrickSummaryLayout.originOffset + 2, by);
    setI16(BrickSummaryLayout.originOffset + 4, bz);
    setU16(BrickSummaryLayout.surfaceCountOffset,
        surfaces > 0xffff ? 0xffff : surfaces);
    setU16(BrickSummaryLayout.completeCountOffset,
        complete > 0xffff ? 0xffff : complete);
    setU8(BrickSummaryLayout.meanCoverageOffset, meanCoverage);
    setU8(BrickSummaryLayout.residencyOffset, residency);
    setU32(BrickSummaryLayout.lastUpdateOffset, lastUpdate);
  }

  int origin(int axis) => i16(BrickSummaryLayout.originOffset + 2 * axis);
  int get surfaceCount => u16(BrickSummaryLayout.surfaceCountOffset);
  int get completeCount => u16(BrickSummaryLayout.completeCountOffset);
  int get meanCoverage => u8(BrickSummaryLayout.meanCoverageOffset);
  int get residency => u8(BrickSummaryLayout.residencyOffset);
  int get lastUpdate => u32(BrickSummaryLayout.lastUpdateOffset);
}

/// 16 B region summary: region key i16 x3, brick count u16, surface count
/// u32, completion u8, residency u8, reserved u16.
abstract final class RegionSummaryLayout {
  static const int bytes = 16;
  static const int keyOffset = 0;
  static const int brickCountOffset = 6;
  static const int surfaceCountOffset = 8;
  static const int completionOffset = 12;
  static const int residencyOffset = 13;
}

final class RegionSummaryRecordView extends ContractRecordView {
  @override
  int get recordBytes => RegionSummaryLayout.bytes;

  void write(int rx, int ry, int rz, int bricks, int surfaces, int completion,
      int residency) {
    setI16(RegionSummaryLayout.keyOffset, rx);
    setI16(RegionSummaryLayout.keyOffset + 2, ry);
    setI16(RegionSummaryLayout.keyOffset + 4, rz);
    setU16(RegionSummaryLayout.brickCountOffset, bricks);
    setU32(RegionSummaryLayout.surfaceCountOffset, surfaces);
    setU8(RegionSummaryLayout.completionOffset, completion);
    setU8(RegionSummaryLayout.residencyOffset, residency);
    setU16(14, 0);
  }

  int key(int axis) => i16(RegionSummaryLayout.keyOffset + 2 * axis);
  int get brickCount => u16(RegionSummaryLayout.brickCountOffset);
  int get surfaceCount => u32(RegionSummaryLayout.surfaceCountOffset);
  int get completion => u8(RegionSummaryLayout.completionOffset);
  int get residency => u8(RegionSummaryLayout.residencyOffset);
}

/// 64 B occupancy mask per brick: bit `x + 8y + 64z` of 16 little-endian u32.
abstract final class OccupancyMaskLayout {
  static const int bytes = 64;
  static int bit(int x, int y, int z) => x + 8 * y + 64 * z;

  static void set(ByteData data, int recordOffset, int x, int y, int z) {
    final b = bit(x, y, z);
    final o = recordOffset + 4 * (b ~/ 32);
    data.setUint32(
        o, data.getUint32(o, contractEndian) | (1 << (b % 32)), contractEndian);
  }

  static bool isSet(ByteData data, int recordOffset, int x, int y, int z) {
    final b = bit(x, y, z);
    return (data.getUint32(recordOffset + 4 * (b ~/ 32), contractEndian) >>
                (b % 32)) &
            1 ==
        1;
  }
}

/// 8 B compact occupied-voxel entry: voxel x, y, z as i16 (grid voxel
/// units), colour value u16. Padding entries of a brick slice have every
/// byte 0xFF (as measured by the renderer prototype).
abstract final class CompactVoxelLayout {
  static const int bytes = 8;
  static const int voxelOffset = 0;
  static const int colourOffset = 6;

  static void write(
      ByteData data, int recordOffset, int x, int y, int z, int colour) {
    data.setInt16(recordOffset, x, contractEndian);
    data.setInt16(recordOffset + 2, y, contractEndian);
    data.setInt16(recordOffset + 4, z, contractEndian);
    data.setUint16(recordOffset + colourOffset, colour, contractEndian);
  }

  static void writePadding(ByteData data, int recordOffset) =>
      data.setInt64(recordOffset, -1, contractEndian);

  static bool isPadding(ByteData data, int recordOffset) =>
      data.getInt64(recordOffset, contractEndian) == -1;
}

/// 8 B free slot range (kind 5): first slot u32, slot count u32.
abstract final class FreeSlotRangeLayout {
  static const int bytes = 8;
  static const int firstOffset = 0;
  static const int countOffset = 4;
}
