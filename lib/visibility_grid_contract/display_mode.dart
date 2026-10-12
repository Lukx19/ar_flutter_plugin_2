import 'binary_support.dart';

/// Base layer of the grid display.
abstract final class DisplayBaseLayer {
  static const int points = 0;
  static const int splats = 1;
  static const int voxels = 2;
  static const int none = 3;
}

/// Overlay bits of the grid display.
abstract final class DisplayOverlay {
  static const int spikeBalls = 1;
  static const int bricks = 2;
  static const int regions = 4;
  static const int targets = 8;
  static const int markers = 16;
}

/// Surface attribute mapped through a colour table.
abstract final class DisplayColourAttribute {
  static const int coverage = 0;
  static const int confidence = 1;
  static const int age = 2;
  static const int source = 3;
  static const int completion = 4;
  static const int count = 5;
}

/// Point or splat size rule.
abstract final class DisplaySizeRule {
  static const int fixed = 0;
  static const int byCoverage = 1;
  static const int byConfidence = 2;
}

/// 32 B display mode message (UI isolate to platform). Switching a mode sends
/// only this message.
///
/// Byte 20 is the minimum surface quality (0-255): the renderer discards
/// surfaces whose confidence byte (fusion weight scaled to the weight cap)
/// is below it, and voxels whose confidence (colour high byte) is below
/// min(it, 254). It was reserved zero before, so an older message reads as
/// 0 (draw every surface) and the version stays 1.
abstract final class DisplayModeLayout {
  static final int magic = contractMagic('C3DM');
  static const int version = 1;
  static const int bytes = 32;
  static const int baseLayerOffset = 6; // u8
  static const int overlayBitsOffset = 7; // u8
  static const int colourAttributeOffset = 8; // u8
  static const int sizeRuleOffset = 9; // u8
  static const int spikeNearestKOffset = 10; // u16
  static const int pointSizeOffset = 12; // f32
  static const int colourTableGenerationOffset = 16; // u32
  static const int minQualityOffset = 20; // u8
  static const int reservedOffset = 21; // 11 bytes, zero
}

final class DisplayModeView extends ContractRecordView {
  @override
  int get recordBytes => DisplayModeLayout.bytes;

  ContractStatus validate(int available) => validateMagicAndVersion(
      data,
      offset,
      available,
      DisplayModeLayout.bytes,
      DisplayModeLayout.magic,
      DisplayModeLayout.version);

  void write({
    required int baseLayer,
    required int overlayBits,
    required int colourAttribute,
    required int sizeRule,
    required int spikeNearestK,
    required double pointSize,
    required int colourTableGeneration,
    required int minQuality,
  }) {
    clear();
    setU32(0, DisplayModeLayout.magic);
    setU16(4, DisplayModeLayout.version);
    setU8(DisplayModeLayout.baseLayerOffset, baseLayer);
    setU8(DisplayModeLayout.overlayBitsOffset, overlayBits);
    setU8(DisplayModeLayout.colourAttributeOffset, colourAttribute);
    setU8(DisplayModeLayout.sizeRuleOffset, sizeRule);
    setU16(DisplayModeLayout.spikeNearestKOffset, spikeNearestK);
    setF32(DisplayModeLayout.pointSizeOffset, pointSize);
    setU32(
        DisplayModeLayout.colourTableGenerationOffset, colourTableGeneration);
    setU8(DisplayModeLayout.minQualityOffset, minQuality);
  }

  int get baseLayer => u8(DisplayModeLayout.baseLayerOffset);
  int get overlayBits => u8(DisplayModeLayout.overlayBitsOffset);
  int get colourAttribute => u8(DisplayModeLayout.colourAttributeOffset);
  int get sizeRule => u8(DisplayModeLayout.sizeRuleOffset);
  int get spikeNearestK => u16(DisplayModeLayout.spikeNearestKOffset);
  double get pointSize => f32(DisplayModeLayout.pointSizeOffset);
  int get colourTableGeneration =>
      u32(DisplayModeLayout.colourTableGenerationOffset);
  int get minQuality => u8(DisplayModeLayout.minQualityOffset);
}

/// Colour table message (UI isolate to platform): 12 B header (magic `C3CT`,
/// u16 version, u8 attribute, u8 reserved, u32 generation) then 256 RGBA8
/// entries.
abstract final class ColourTableLayout {
  static final int magic = contractMagic('C3CT');
  static const int version = 1;
  static const int headerBytes = 12;
  static const int entries = 256;
  static const int bytes = headerBytes + entries * 4;
  static const int attributeOffset = 6; // u8
  static const int generationOffset = 8; // u32
}

final class ColourTableView extends ContractRecordView {
  @override
  int get recordBytes => ColourTableLayout.bytes;

  ContractStatus validate(int available) {
    final status = validateMagicAndVersion(
        data,
        offset,
        available,
        ColourTableLayout.bytes,
        ColourTableLayout.magic,
        ColourTableLayout.version);
    if (status != ContractStatus.ok) return status;
    if (attribute >= DisplayColourAttribute.count) {
      return ContractStatus.badKind;
    }
    return ContractStatus.ok;
  }

  void begin(int attribute, int generation) {
    setU32(0, ColourTableLayout.magic);
    setU16(4, ColourTableLayout.version);
    setU8(ColourTableLayout.attributeOffset, attribute);
    setU8(7, 0);
    setU32(ColourTableLayout.generationOffset, generation);
  }

  int get attribute => u8(ColourTableLayout.attributeOffset);
  int get generation => u32(ColourTableLayout.generationOffset);

  /// Entry [index] as 0xRRGGBBAA.
  int rgba(int index) {
    final at = ColourTableLayout.headerBytes + 4 * index;
    return u8(at) << 24 | u8(at + 1) << 16 | u8(at + 2) << 8 | u8(at + 3);
  }

  void setRgba(int index, int rgba) {
    final at = ColourTableLayout.headerBytes + 4 * index;
    setU8(at, (rgba >> 24) & 0xff);
    setU8(at + 1, (rgba >> 16) & 0xff);
    setU8(at + 2, (rgba >> 8) & 0xff);
    setU8(at + 3, rgba & 0xff);
  }
}
